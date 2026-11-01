/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A free provider of the catalog. [api] is "openai" (chat/completions) or "gemini" (generateContent). */
data class FreeProv(val id: String, val title: String, val base: String, val api: String, val keyUrl: String, val keyless: Boolean)

/** A free model. [intel] and [speed] are ranks: 1 = the smartest / fastest. Limits: 0 = unknown. */
data class FreeModel(
    val p: String, val id: String, val name: String, val intel: Int, val speed: Int, val ctx: Int,
    val rpm: Int, val rpd: Int, val tpm: Int, val tpd: Int, val vision: Boolean, val tools: Boolean
) {
    val key: String get() = "$p/$id"
}

data class FreeCatalog(val version: String, val upstream: String, val hash: String, val providers: List<FreeProv>, val models: List<FreeModel>) {
    fun prov(id: String): FreeProv? = providers.firstOrNull { it.id == id }
}

/** Today's use of one model (or of a whole provider under "id/*"). */
data class FreeSlot(var n: Int = 0, var tok: Long = 0, var coolUntil: Long = 0, var fails: Int = 0, var err: String = "")

/** Use counters; they start over on a new UTC day, cooldowns stay. */
class FreeUse(var day: String = "", val slots: MutableMap<String, FreeSlot> = mutableMapOf()) {
    fun slot(k: String) = slots.getOrPut(k) { FreeSlot() }
}

/**
 * Free AI manager (protocol lumen.freeai v1): the catalog of free tiers comes from FreeLLMAPI (tools/freeai/sync.py),
 * and every call goes to the best model the user has a key for, skipping models on cooldown or out of their daily limit.
 */
object FreeAi {
    const val PROTOCOL = "lumen.freeai v1"
    const val MAX_TRIES = 6
    const val AUTO_FAST = "auto-fast"
    const val AUTO_SMART = "auto-smart"

    enum class Fail { RATE, DAILY, AUTH, MODEL, REQUEST, SERVER, NETWORK }

    /** What a call needs from a model. [video] = a file URI that only Gemini reads. */
    data class Need(val smart: Boolean, val vision: Boolean = false, val video: Boolean = false, val inTokens: Int = 0, val outTokens: Int = 0)

    fun parse(text: String): FreeCatalog? = try {
        val o = JSONObject(text)
        if (o.optString("protocol") != PROTOCOL) null else {
            val provs = o.optJSONArray("providers") ?: JSONArray()
            val ms = o.optJSONArray("models") ?: JSONArray()
            val providers = (0 until provs.length()).mapNotNull { i ->
                val p = provs.optJSONObject(i) ?: return@mapNotNull null
                val base = p.optString("base").trimEnd('/')
                if (p.optString("id").isBlank() || !base.startsWith("https://")) null
                else FreeProv(p.optString("id"), p.optString("title", p.optString("id")), base, p.optString("api", "openai"),
                    p.optString("key"), p.optBoolean("keyless"))
            }
            val ids = providers.map { it.id }.toSet()
            val models = (0 until ms.length()).mapNotNull { i ->
                val m = ms.optJSONObject(i) ?: return@mapNotNull null
                if (m.optString("p") !in ids || m.optString("id").isBlank()) null
                else FreeModel(m.optString("p"), m.optString("id"), m.optString("name", m.optString("id")), m.optInt("intel", 50),
                    m.optInt("speed", 50), m.optInt("ctx"), m.optInt("rpm"), m.optInt("rpd"), m.optInt("tpm"), m.optInt("tpd"),
                    m.optBoolean("vision"), m.optBoolean("tools"))
            }
            if (providers.isEmpty() || models.isEmpty()) null
            else FreeCatalog(o.optString("version"), o.optString("upstream"), o.optString("hash"), providers, models)
        }
    } catch (e: Exception) {
        null
    }

    /** A downloaded catalog replaces the current one when it differs and is not older. */
    fun newer(cur: FreeCatalog?, next: FreeCatalog): Boolean = cur == null || (next.hash != cur.hash && next.version >= cur.version)

    fun day(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

    fun nextUtcMidnight(now: Long): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** New UTC day → counters to zero; cooldowns that run past midnight are kept. */
    fun roll(use: FreeUse, now: Long) {
        val d = day(now)
        if (use.day == d) return
        use.day = d
        use.slots.values.forEach { it.n = 0; it.tok = 0 }
        use.slots.entries.removeAll { it.value.coolUntil <= now && it.value.fails == 0 }
    }

    /** [code] = HTTP status, null for a network error. */
    fun classify(code: Int?, msg: String): Fail {
        val m = msg.lowercase(Locale.ROOT)
        val modelGone = Regex("model[^.]{0,60}(not found|does not exist|not exist|decommission|deprecated|no longer|unknown|unsupported|not available)")
            .containsMatchIn(m) || m.contains("no endpoints found")
        return when {
            code == null -> Fail.NETWORK
            code == 401 || code == 403 -> if (modelGone) Fail.MODEL else Fail.AUTH
            code == 402 -> Fail.DAILY
            code == 404 -> Fail.MODEL
            code == 429 -> if (Regex("per day|per-day|daily|rpd|tpd|quota|insufficient|exhausted|credits").containsMatchIn(m)) Fail.DAILY else Fail.RATE
            code in 500..599 -> Fail.SERVER
            modelGone -> Fail.MODEL
            else -> Fail.REQUEST
        }
    }

    /** Until when the model ([Fail.AUTH]: the whole provider) is skipped. [fails] counts this failure too. */
    fun coolUntil(f: Fail, fails: Int, now: Long): Long = when (f) {
        Fail.RATE -> now + minOf(15 * 60_000L, 60_000L shl (fails - 1).coerceIn(0, 4))
        Fail.DAILY -> nextUtcMidnight(now)
        Fail.AUTH -> now + 6 * 3600_000L
        Fail.MODEL -> now + 3 * 24 * 3600_000L
        Fail.SERVER -> now + 2 * 60_000L
        Fail.NETWORK -> now + 60_000L
        Fail.REQUEST -> now
    }

    fun failed(use: FreeUse, m: FreeModel, f: Fail, msg: String, now: Long) {
        val s = use.slot(if (f == Fail.AUTH) "${m.p}/*" else m.key)
        s.fails++
        s.coolUntil = coolUntil(f, s.fails, now)
        s.err = msg.take(160)
    }

    fun succeeded(use: FreeUse, m: FreeModel, tokens: Int) {
        val s = use.slot(m.key)
        s.n++
        s.tok += tokens
        s.fails = 0
        s.err = ""
        use.slots["${m.p}/*"]?.let { it.fails = 0; it.coolUntil = 0; it.err = "" }
    }

    /** Why [m] can not take this call now, or null. */
    fun blocked(cat: FreeCatalog, m: FreeModel, has: (String) -> Boolean, use: FreeUse, now: Long, need: Need, off: Set<String>): String? {
        val p = cat.prov(m.p) ?: return "no provider"
        val slot = use.slots[m.key]
        val provSlot = use.slots["${m.p}/*"]
        return when {
            m.p in off -> "off"
            !p.keyless && !has(m.p) -> "no key"
            (provSlot?.coolUntil ?: 0) > now -> "key error"
            (slot?.coolUntil ?: 0) > now -> "cooldown"
            m.rpd > 0 && (slot?.n ?: 0) >= m.rpd -> "daily limit"
            m.tpd > 0 && (slot?.tok ?: 0) + need.inTokens > m.tpd -> "daily tokens"
            m.ctx > 0 && m.ctx < need.inTokens + need.outTokens -> "context"
            need.video && p.api != "gemini" -> "no video"
            need.vision && !m.vision && p.api != "gemini" -> "no vision"
            else -> null
        }
    }

    /**
     * Models to try, best first. Smart calls rank by intelligence, fast ones by speed; recent failures push a model down.
     * Providers alternate (the best model of each provider first), so one rate-limited key does not eat every try.
     */
    fun candidates(cat: FreeCatalog, has: (String) -> Boolean, use: FreeUse, now: Long, need: Need, off: Set<String> = emptySet()): List<FreeModel> {
        val ok = cat.models.filter { blocked(cat, it, has, use, now, need, off) == null }
        val sorted = ok.sortedWith(compareBy<FreeModel> { score(it, need.smart, use.slots[it.key]?.fails ?: 0) }.thenBy { it.p }.thenBy { it.id })
        val firsts = sorted.distinctBy { it.p }
        return (firsts + (sorted - firsts.toSet())).take(MAX_TRIES)
    }

    fun score(m: FreeModel, smart: Boolean, fails: Int): Int =
        (if (smart) m.intel * 3 + m.speed else m.speed * 3 + m.intel) + fails * 4

    fun useJson(use: FreeUse): String = JSONObject().put("day", use.day).put("slots", JSONObject().apply {
        use.slots.forEach { (k, s) -> put(k, JSONObject().put("n", s.n).put("tok", s.tok).put("cool", s.coolUntil).put("fails", s.fails).put("err", s.err)) }
    }).toString()

    fun useOf(text: String?): FreeUse = try {
        val o = JSONObject(text ?: "{}")
        val sl = o.optJSONObject("slots") ?: JSONObject()
        FreeUse(o.optString("day"), sl.keys().asSequence().associateWith { k ->
            val s = sl.getJSONObject(k)
            FreeSlot(s.optInt("n"), s.optLong("tok"), s.optLong("cool"), s.optInt("fails"), s.optString("err"))
        }.toMutableMap())
    } catch (e: Exception) {
        FreeUse()
    }

    /** Short "why it failed" for the final error when no model answered. */
    fun summary(tries: List<Pair<String, String>>, cat: FreeCatalog, has: (String) -> Boolean): String = when {
        tries.isNotEmpty() -> tries.joinToString("; ") { "${it.first}: ${it.second.take(80)}" }
        cat.providers.none { it.keyless || has(it.id) } -> t("нет ключей бесплатных ИИ — добавьте хотя бы один в «Бесплатные ИИ»",
            "no free AI keys — add at least one in \"Free AI\"")
        else -> t("все бесплатные модели сейчас на паузе или исчерпали дневной лимит", "every free model is on cooldown or out of its daily limit")
    }
}

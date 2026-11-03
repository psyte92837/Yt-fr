/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Ideas for my business": a short setup (business, market country, how often) turns into a
 * monitoring project. Every run finds fresh videos of that market, collects top comments and writes
 * an AI digest: trends, customer pains (with quotes) and business ideas. Several businesses can be set up;
 * the list is in prefs "ideas_list", digests of each in files/lumen/ideas/<id>.json.
 */
data class IdeasConfig(
    var business: String = "",
    var country: String = "US",
    var queries: MutableList<String> = mutableListOf(),
    var hours: Int = 24,
    var enabled: Boolean = false,
    var projectId: String = "",
    val id: String = newId(),
    /** Search language (ISO 639-1); "" = main language of the market. */
    var lang: String = "",
    /** Queries as the user typed them (any language); [queries] are their search-language version. */
    var userQueries: MutableList<String> = mutableListOf(),
    /** Digest language: "app" = interface language, "market" = search language. */
    var digestLang: String = "app",
    var period: String = "week", // today | week | month
    var sort: String = "date", // date | views | relevance
    var duration: String = "any", // any | short | medium | long
    var noShorts: Boolean = false,
    var limit: Int = 30,
    var comments: Int = 60
) {
    /** Language the market is searched in. */
    val searchLang: String get() = lang.ifEmpty { IdeasLogic.langFor(country) }

    fun filters() = SearchFilters(sort = sort, period = period, duration = duration, excludeShorts = noShorts,
        limit = limit, country = country, origLang = searchLang)

    fun toJson(): JSONObject = JSONObject().put("business", business).put("country", country)
        .put("queries", JSONArray(queries)).put("hours", hours)
        .put("enabled", enabled).put("projectId", projectId).put("id", id)
        .put("lang", lang).put("userQueries", JSONArray(userQueries)).put("digestLang", digestLang)
        .put("period", period).put("sort", sort).put("duration", duration).put("noShorts", noShorts)
        .put("limit", limit).put("comments", comments)

    companion object {
        fun from(o: JSONObject?) = IdeasConfig(id = o?.optString("id", "")?.takeIf { it.isNotEmpty() } ?: newId()).apply {
            if (o == null) return@apply
            business = o.optString("business", "")
            country = o.optString("country", "US")
            queries = IdeasLogic.strings(o.optJSONArray("queries"))
            hours = o.optInt("hours", 24)
            enabled = o.optBoolean("enabled", false)
            projectId = o.optString("projectId", "")
            lang = o.optString("lang", "")
            userQueries = IdeasLogic.strings(o.optJSONArray("userQueries"))
            digestLang = o.optString("digestLang", "app")
            period = o.optString("period", "week")
            sort = o.optString("sort", "date")
            duration = o.optString("duration", "any")
            noShorts = o.optBoolean("noShorts", false)
            limit = o.optInt("limit", 30)
            comments = o.optInt("comments", 60)
        }
    }
}

/** Pure helpers (covered by IdeasTest). */
object IdeasLogic {
    const val MAX_QUERIES = 6

    /** Main language of a YouTube market; "" = no language filter. */
    fun langFor(country: String): String = when (country.uppercase()) {
        "US", "GB", "CA", "AU", "IN" -> "en"
        "RU", "BY", "KZ" -> "ru"
        "UA" -> "uk"
        "DE" -> "de"
        "FR" -> "fr"
        "ES", "MX" -> "es"
        "BR" -> "pt"
        "IT" -> "it"
        "PL" -> "pl"
        "TR" -> "tr"
        "JP" -> "ja"
        "KR" -> "ko"
        else -> ""
    }

    /** Days of videos a manual digest looks back over for a search period. */
    fun windowDays(period: String): Int = when (period) {
        "today" -> 2
        "month" -> 30
        else -> 7
    }

    /** True when the user's queries need translating into [lang]: their guessed language differs. */
    fun needsTranslation(queries: List<String>, lang: String): Boolean {
        if (queries.isEmpty() || lang.isEmpty()) return false
        val g = LangGuess.guess(queries.joinToString(". "))
        return g.isNotEmpty() && g != lang
    }

    fun configsFrom(a: JSONArray?): List<IdeasConfig> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(IdeasConfig::from) }.distinctBy { it.id }

    fun configsJson(list: List<IdeasConfig>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

    /** Replaces the config with the same id or appends a new one, keeping the order. */
    fun upsert(list: List<IdeasConfig>, c: IdeasConfig): List<IdeasConfig> =
        if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c

    fun strings(a: JSONArray?): MutableList<String> {
        val out = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    /** Cleans a user / AI list of queries: trims, drops quotes and bullets, dedupes ignoring case, caps the count. */
    fun clean(list: List<String>, max: Int = MAX_QUERIES): List<String> {
        val seen = HashSet<String>()
        return list.map { it.trim().trimStart('-', '•', '*', ' ').trim('"', '«', '»', ' ').replace(Regex("""^\d+[.)]\s*"""), "").trim() }
            .filter { it.length in 2..80 && seen.add(it.lowercase()) }
            .take(max)
    }

    /** Queries typed by the user: one per line or separated by commas / semicolons. */
    fun splitUser(s: String): List<String> = clean(s.split('\n', ',', ';'))

    /** Queries from an AI reply: {"queries":[...]} or a bare array; otherwise one per line. */
    fun parseQueries(reply: String): List<String> {
        val s = reply.trim()
        val obj = s.indexOf('{')
        if (obj >= 0) {
            try {
                val o = JSONObject(s.substring(obj, s.lastIndexOf('}') + 1))
                val q = clean(strings(o.optJSONArray("queries")))
                if (q.isNotEmpty()) return q
            } catch (e: Exception) {
                // fall through
            }
        }
        val arr = s.indexOf('[')
        if (arr >= 0) {
            try {
                val q = clean(strings(JSONArray(s.substring(arr, s.lastIndexOf(']') + 1))))
                if (q.isNotEmpty()) return q
            } catch (e: Exception) {
                // fall through
            }
        }
        return clean(s.lines().filter { !it.trim().startsWith("{") && !it.trim().startsWith("}") && !it.contains("```") })
    }

    /** Most liked top-level comments, shortened; spam-like very short ones skipped. */
    fun topComments(list: List<Comment>, n: Int = 6, maxLen: Int = 200): List<String> =
        list.filter { !it.isReply && it.text.trim().length >= 12 }
            .sortedByDescending { it.likes }
            .take(n)
            .map { c -> c.text.replace('\n', ' ').trim().let { if (it.length > maxLen) it.take(maxLen) + "…" else it } + if (c.likes > 0) " (👍${c.likes})" else "" }

    /** Prompt data block: "3. Title — Channel · 12k views\n   > comment\n   > comment". */
    fun dataBlock(items: List<Pair<Video, List<String>>>, maxChars: Int = 24_000): String {
        val sb = StringBuilder()
        for ((i, pair) in items.withIndex()) {
            val (v, comments) = pair
            val part = StringBuilder()
            part.append(i + 1).append(". ").append(v.title).append(" — ").append(v.channel)
            if (v.views >= 0) part.append(" · ").append(v.views).append(" views")
            if (v.description.isNotBlank()) part.append("\n   ").append(v.description.replace('\n', ' ').take(160))
            comments.forEach { part.append("\n   > ").append(it) }
            part.append('\n')
            if (sb.length + part.length > maxChars) break
            sb.append(part)
        }
        return sb.toString().trimEnd()
    }

    /** Ideas digest entry: when, how many videos / comments were read, the text. */
    data class Digest(val at: Long, val videos: Int, val comments: Int, val text: String) {
        fun toJson(): JSONObject = JSONObject().put("at", at).put("videos", videos).put("comments", comments).put("text", text)

        companion object {
            fun from(o: JSONObject) = Digest(o.optLong("at"), o.optInt("videos"), o.optInt("comments"), o.optString("text"))
        }
    }

    fun digestsFrom(a: JSONArray?): List<Digest> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Digest::from) }

    /** Newest first, at most [keep]. */
    fun addDigest(list: List<Digest>, d: Digest, keep: Int = 30): List<Digest> = (listOf(d) + list).sortedByDescending { it.at }.take(keep)

    /** First meaningful line of a digest for the home card / notification title. */
    fun headline(text: String, max: Int = 120): String {
        val line = text.lines().map { it.trim().trimStart('#', '*', '-', '•', ' ').replace("**", "").trim() }.firstOrNull { it.length > 3 } ?: ""
        return if (line.length > max) line.take(max - 1) + "…" else line
    }
}

val IDEAS_PROMPTS = linkedMapOf(
    "ideas_queries" to (
        "Бизнес пользователя (описан, возможно, на другом языке): \"{business}\". Рынок: {country}, язык поиска: {lang}.\n" +
            "Придумай {n} коротких поисковых запросов для YouTube на языке {lang}, по которым там ищут свежие видео: " +
            "тренды ниши, проблемы и вопросы клиентов, обзоры, новые продукты и сервисы, кейсы. " +
            "Пиши так, как ищут носители языка, без кавычек и операторов. Верни строго JSON: {\"queries\":[\"...\"]}"
        ),
    "ideas_translate" to (
        "Бизнес пользователя: \"{business}\". Рынок: {country}, язык поиска: {lang}.\n" +
            "Пользователь написал поисковые запросы для YouTube на своём языке. Переведи каждый на язык {lang} так, " +
            "как его набрали бы носители языка в этой стране (не дословно, привычными словами ниши), без кавычек и операторов. " +
            "Порядок и количество сохрани. Верни строго JSON: {\"queries\":[\"...\"]}\n\nЗапросы:\n{queries}"
        ),
    "ideas_digest" to (
        "Ты бизнес-аналитик. Бизнес пользователя: \"{business}\". Рынок: {country}.\n" +
            "Ниже {count} свежих видео YouTube этого рынка (номер. название — канал · просмотры, описание, лучшие комментарии зрителей «>»).\n" +
            "Напиши на языке {lang} сводку для владельца бизнеса, без вступлений, по разделам:\n" +
            "1. Главное — 2-3 строки: что сейчас происходит в нише.\n" +
            "2. Тренды — 3-5 пунктов «• тренд — почему важен (номера видео)».\n" +
            "3. Боли и запросы клиентов — 3-6 пунктов, к каждому короткая цитата из комментариев в переводе.\n" +
            "4. Идеи для бизнеса — 5 конкретных идей (продукт, услуга, контент или маркетинг), " +
            "к каждой: что сделать, кому, почему сработает, на чём основано (номера видео).\n" +
            "5. Что попробовать на этой неделе — 1-3 быстрых шага.\n" +
            "Опирайся только на данные ниже, не выдумывай цифры. Если данных мало, так и скажи.\n\n{data}"
        )
)

object Ideas {
    private const val PREF_LIST = "ideas_list"
    private const val PREF_OLD = "ideas_cfg"
    private const val FILE_OLD = "ideas.json"
    private const val NOTIFY_BASE = 49_000
    private val lock = Any()

    /** All businesses; the old single-business setup (ideas_cfg + ideas.json) is migrated once. */
    fun configs(): List<IdeasConfig> = synchronized(lock) {
        val raw = Lumen.prefs.getString(PREF_LIST, null)
        if (raw != null) {
            return try {
                IdeasLogic.configsFrom(JSONArray(raw))
            } catch (e: Exception) {
                emptyList()
            }
        }
        val old = Lumen.prefs.getString(PREF_OLD, null)?.let {
            try {
                IdeasConfig.from(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }?.takeIf { it.business.isNotBlank() }
        val list = listOfNotNull(old)
        if (old != null) {
            val f = File(Store.root, FILE_OLD)
            if (f.exists()) f.renameTo(file(old.id).also { it.parentFile?.mkdirs() })
        }
        Lumen.prefs.edit().putString(PREF_LIST, IdeasLogic.configsJson(list).toString()).remove(PREF_OLD).apply()
        list
    }

    fun config(id: String): IdeasConfig? = configs().firstOrNull { it.id == id }

    fun saveConfig(c: IdeasConfig) = synchronized(lock) {
        Lumen.prefs.edit().putString(PREF_LIST, IdeasLogic.configsJson(IdeasLogic.upsert(configs(), c)).toString()).apply()
    }

    fun byProject(pid: String): IdeasConfig? = if (pid.isEmpty()) null else configs().firstOrNull { it.projectId == pid }

    private fun file(id: String) = File(Store.root, "ideas/$id.json")

    fun digests(id: String): List<IdeasLogic.Digest> = synchronized(lock) {
        try {
            val f = file(id)
            if (f.exists()) IdeasLogic.digestsFrom(JSONArray(f.readText())) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The newest digest over all businesses (for the home card). */
    fun latest(): Pair<IdeasConfig, IdeasLogic.Digest>? =
        configs().mapNotNull { c -> digests(c.id).firstOrNull()?.let { c to it } }.maxByOrNull { it.second.at }

    private fun remember(id: String, d: IdeasLogic.Digest) = synchronized(lock) {
        val a = JSONArray()
        IdeasLogic.addDigest(digests(id), d).forEach { a.put(it.toJson()) }
        val f = file(id)
        f.parentFile?.mkdirs()
        f.writeText(a.toString())
    }

    fun countryName(code: String) = Regions.countryName(code)

    /** Language name for prompts ("en" → "Английский (en)"). */
    fun langLabel(code: String) = Regions.languageName(code).let { if (it == code) code else "$it ($code)" }

    /** AI search queries for the market language; without AI the business text itself. Blocking. */
    fun suggestQueries(business: String, country: String, lang: String = IdeasLogic.langFor(country)): List<String> {
        val prov = Provider.active() ?: return IdeasLogic.splitUser(business)
        val prompt = Prompts.get("ideas_queries").replace("{business}", business.trim())
            .replace("{country}", countryName(country).ifBlank { country }).replace("{lang}", langLabel(lang.ifEmpty { "en" }))
            .replace("{n}", IdeasLogic.MAX_QUERIES.toString())
        val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 400, task = "ideas_queries")
        return IdeasLogic.parseQueries(r.text).ifEmpty { IdeasLogic.splitUser(business) }
    }

    /** The user's queries in the search language (AI translation); unchanged without AI or when already in it. Blocking. */
    fun translateQueries(c: IdeasConfig, queries: List<String>): List<String> {
        val lang = c.searchLang
        if (!IdeasLogic.needsTranslation(queries, lang)) return queries
        val prov = Provider.active() ?: return queries
        val prompt = Prompts.get("ideas_translate").replace("{business}", c.business.trim())
            .replace("{country}", countryName(c.country).ifBlank { c.country }).replace("{lang}", langLabel(lang))
            .replace("{queries}", queries.joinToString("\n"))
        return try {
            val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 400, task = "ideas_translate")
            IdeasLogic.parseQueries(r.text).ifEmpty { queries }
        } catch (e: Exception) {
            L.w("ideas", "translate failed: ${e.message}")
            queries
        }
    }

    /** Fills [IdeasConfig.queries] in the search language from what the user typed (or from the business). Blocking. */
    fun prepareQueries(c: IdeasConfig) {
        c.queries = (if (c.userQueries.isEmpty()) suggestQueries(c.business, c.country, c.searchLang)
        else translateQueries(c, c.userQueries)).toMutableList()
    }

    /** Creates or updates the monitoring project behind the config and (re)schedules it. Blocking. */
    fun apply(ctx: Context, c: IdeasConfig): Project {
        val existing = c.projectId.takeIf { it.isNotEmpty() }?.let { Store.project(it) }
        val p = existing ?: Project()
        p.name = t("💡 Идеи: ", "💡 Ideas: ") + c.business.trim().take(40)
        p.phrase = c.queries.firstOrNull() ?: c.business.trim().take(80)
        p.domain = c.business.trim().take(200)
        p.queries = c.queries.toMutableList()
        p.filters = c.filters()
        p.collect = CollectConfig(meta = true, subtitles = false, comments = c.comments > 0, commentsLimit = c.comments.coerceAtLeast(1), commentsSort = "top", replies = false, channelStats = false)
        p.monitor = MonitorConfig(enabled = c.enabled, hours = c.hours, aiSummary = false,
            aiFilter = Provider.active() != null, recDrip = 0, anyLang = false)
        p.archived = false
        Store.save(p)
        c.projectId = p.id
        saveConfig(c)
        if (c.enabled) Settings.monitoringAllowed = true
        Monitoring.apply(ctx, p)
        return p
    }

    fun stop(ctx: Context, id: String) {
        val c = config(id) ?: return
        c.enabled = false
        saveConfig(c)
        if (c.projectId.isNotEmpty()) {
            Store.project(c.projectId)?.let {
                it.monitor.enabled = false
                Store.save(it)
            }
            Monitoring.cancel(ctx, c.projectId)
        }
    }

    /** Removes the business, its digests and stops monitoring; with [withProject] the collected videos too. */
    fun delete(ctx: Context, id: String, withProject: Boolean) {
        val c = config(id) ?: return
        stop(ctx, id)
        synchronized(lock) {
            Lumen.prefs.edit().putString(PREF_LIST, IdeasLogic.configsJson(configs().filter { it.id != id }).toString()).apply()
            file(id).delete()
        }
        if (withProject && c.projectId.isNotEmpty()) Store.trash(c.projectId)
    }

    /**
     * Called by [Monitoring.runOnce] for an ideas project after new videos were collected.
     * Writes the digest and notifies; returns the digest or null without AI / on failure.
     */
    fun afterRun(ctx: Context, pid: String, fresh: List<Video>, notify: Boolean = true): IdeasLogic.Digest? {
        val c = byProject(pid) ?: return null
        val d = digest(c, fresh) ?: return null
        remember(c.id, d)
        if (notify) notify(ctx, c, d)
        return d
    }

    /** Manual "Update now": monitoring run; when nothing new arrived, a digest of the last week's videos. Blocking. */
    fun refreshNow(ctx: Context, id: String): IdeasLogic.Digest? {
        val cfg = config(id) ?: return null
        val pid = cfg.projectId.takeIf { it.isNotEmpty() } ?: return null
        val before = digests(id).firstOrNull()?.at ?: 0
        Monitoring.runOnce(ctx, pid)
        digests(id).firstOrNull()?.takeIf { it.at > before }?.let { return it }
        val weekAgo = System.currentTimeMillis() - IdeasLogic.windowDays(cfg.period) * 86_400_000L
        val recent = Store.videos(pid).values.filter { maxOf(it.uploadMs, it.addedAt) >= weekAgo }
            .sortedByDescending { it.addedAt }.take(40)
        if (recent.isEmpty()) return null
        return afterRun(ctx, pid, recent, notify = false)
    }

    private fun digest(c: IdeasConfig, fresh: List<Video>): IdeasLogic.Digest? {
        val prov = Provider.active() ?: return null
        val pid = c.projectId
        val items = fresh.sortedByDescending { it.views }.take(40).map { v -> v to IdeasLogic.topComments(Store.comments(pid, v.id)) }
        if (items.isEmpty()) return null
        val prompt = Prompts.get("ideas_digest").replace("{business}", c.business.trim())
            .replace("{country}", countryName(c.country).ifBlank { c.country })
            .replace("{count}", items.size.toString())
            .replace("{lang}", if (c.digestLang == "market" && c.searchLang.isNotEmpty()) langLabel(c.searchLang)
            else if (Settings.lang == "en") "English" else "русский")
            .replace("{data}", IdeasLogic.dataBlock(items))
        return try {
            val text = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, maxTokens = 2500, task = "ideas_digest", pid = pid).text.trim()
            if (text.isBlank()) null else IdeasLogic.Digest(System.currentTimeMillis(), items.size, items.sumOf { it.second.size }, text)
        } catch (e: Exception) {
            L.w("ideas", "digest failed: ${e.message}")
            null
        }
    }

    private fun notify(ctx: Context, c: IdeasConfig, d: IdeasLogic.Digest) {
        Notify.channels(ctx)
        val nid = NOTIFY_BASE + (c.id.hashCode() and 0x3FF)
        val pi = Nav.pending(ctx, nid, IdeasActivity.intent(ctx, c.id))
        val n = Notify.builder(ctx, Notify.CH_MONITOR).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(t("💡 Идеи: ", "💡 Ideas: ") + c.business.trim().take(40))
            .setContentText(IdeasLogic.headline(d.text))
            .setStyle(android.app.Notification.BigTextStyle().bigText(d.text.take(1500)))
            .setContentIntent(pi).setAutoCancel(true).build()
        try {
            ctx.getSystemService(android.app.NotificationManager::class.java).notify(nid, n)
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }
}

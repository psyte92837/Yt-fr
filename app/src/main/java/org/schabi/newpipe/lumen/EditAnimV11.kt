/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** A parameter an animation offers for edits without AI: `// param <name> <kind> …`. */
data class AnimParam(
    val name: String, val kind: String, val def: String, val min: Double = 0.0, val max: Double = 1.0,
    val options: List<String> = emptyList(), val label: String = ""
)

/** The header comments of a lumen.anim block (v1.1, all optional). */
data class AnimHeader(
    val version: String = "v1", val name: String = "", val type: String = "", val size: String = "",
    val durMin: Double = 0.0, val durMax: Double = 0.0, val fps: Int = 0,
    val intro: Long = 0, val loop: Long = 0, val outro: Long = 0, val opaque: Boolean = false,
    val params: List<AnimParam> = emptyList()
) {
    val hasSegments: Boolean get() = loop > 0
    val segLen: Long get() = intro + loop + outro
}

/** A moment the animation can hit: a word, an item, the main one, a pause, a cut of the video. */
data class AnimCue(val id: String, val t: Double, val kind: String, val text: String = "")

/**
 * lumen.anim v1.1 (PLAN_INSERT_V2 §4.1): parameters, segments intro/loop/outro (any block length without a new render),
 * cues, several variants in one answer, checks of the result (fonts, edges, zones, colours hard-coded). Pure, no Android.
 */
object AnimV11 {
    const val VERSION = "lumen.anim v1.1"
    val KINDS = listOf("text", "color", "range", "bool", "choice")

    private val LINE = Regex("""^\s*//\s*([a-zA-Z]+)\s*[:：]?\s*(.*)$""")

    /** The header of [code]: the comment lines before the code (and any `// param` line anywhere). */
    fun header(code: String): AnimHeader {
        var h = AnimHeader()
        val params = mutableListOf<AnimParam>()
        for (raw in code.lineSequence().take(400)) {
            val m = LINE.find(raw) ?: continue
            val key = m.groupValues[1].lowercase()
            val v = m.groupValues[2].trim()
            when (key) {
                "lumen" -> if (v.contains("v1.1")) h = h.copy(version = "v1.1")
                "name", "название" -> if (h.name.isEmpty()) h = h.copy(name = v.take(40))
                "type" -> h = h.copy(type = v.substringBefore(' ').lowercase())
                "size" -> h = h.copy(size = v.lowercase().takeIf { it in setOf("wide", "square", "tall") } ?: "")
                "dur" -> {
                    val r = Regex("""(\d+(?:[.,]\d+)?)\s*(?:\.\.|-|–)\s*(\d+(?:[.,]\d+)?)""").find(v)
                    if (r != null) h = h.copy(durMin = r.groupValues[1].replace(',', '.').toDouble(), durMax = r.groupValues[2].replace(',', '.').toDouble())
                }
                "fps" -> v.toIntOrNull()?.takeIf { it in 12..60 }?.let { h = h.copy(fps = it) }
                "segments" -> {
                    fun part(n: String) = Regex("""$n\s*=\s*(\d+(?:[.,]\d+)?)""").find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
                    val loop = part("loop").coerceIn(0L, EditInsert.MAX_ANIM_MS)
                    if (loop > 0) h = h.copy(intro = part("intro").coerceIn(0L, 10_000L), loop = loop, outro = part("outro").coerceIn(0L, 10_000L))
                }
                "opaque" -> h = h.copy(opaque = v.startsWith("full") || v == "true")
                "param" -> param(v)?.let { params += it }
            }
        }
        return h.copy(params = params.distinctBy { it.name }.take(12))
    }

    /** One `// param` line: `title text "Кто победил" — Заголовок`, `speed range 0.5..2 =1`, `icons bool =true`, `style choice bars|line =bars`. */
    fun param(v0: String): AnimParam? {
        val label = v0.substringAfter(" — ", "").ifEmpty { v0.substringAfter(" // ", "") }.trim()
        val v = v0.substringBefore(" — ").substringBefore(" // ").trim()
        val parts = v.split(Regex("\\s+"), 3)
        if (parts.size < 2) return null
        val name = parts[0].takeIf { Regex("^[A-Za-z_][A-Za-z0-9_]{0,23}$").matches(it) } ?: return null
        val kind = parts[1].lowercase().takeIf { it in KINDS } ?: return null
        val rest = parts.getOrNull(2).orEmpty().trim()
        val def = Regex("""=\s*(.+)$""").find(rest)?.groupValues?.get(1)?.trim()?.trim('"', '«', '»')
            ?: Regex(""""([^"]*)"|«([^»]*)»""").find(rest)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
        return when (kind) {
            "range" -> {
                val r = Regex("""(-?\d+(?:[.,]\d+)?)\s*\.\.\s*(-?\d+(?:[.,]\d+)?)""").find(rest)
                val lo = r?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                val hi = r?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
                if (hi <= lo) return null
                AnimParam(name, kind, def.replace(',', '.').toDoubleOrNull()?.coerceIn(lo, hi)?.toString() ?: lo.toString(), lo, hi, label = label)
            }
            "bool" -> AnimParam(name, kind, if (def.lowercase() in setOf("false", "0", "нет", "no")) "false" else "true", label = label)
            "choice" -> {
                val opts = rest.substringBefore('=').trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(8)
                if (opts.size < 2) return null
                AnimParam(name, kind, def.takeIf { it in opts } ?: opts.first(), options = opts, label = label)
            }
            else -> AnimParam(name, kind, def.take(120), label = label)
        }
    }

    /** data.params: the author's values over the defaults, typed; a colour "theme.accent" is taken from the theme. */
    fun params(h: AnimHeader, values: Map<String, String>, theme: JSONObject?): JSONObject {
        val o = JSONObject()
        for (p in h.params) {
            val v = values[p.name]?.takeIf { it.isNotBlank() } ?: p.def
            when (p.kind) {
                "range" -> o.put(p.name, (v.replace(',', '.').toDoubleOrNull() ?: p.min).coerceIn(p.min, p.max))
                "bool" -> o.put(p.name, v == "true")
                "color" -> o.put(p.name, if (v.startsWith("theme.")) theme?.optString(v.removePrefix("theme."))?.ifEmpty { null } ?: "#FFD60A" else v)
                else -> o.put(p.name, v)
            }
        }
        return o
    }

    // ---------- segments ----------

    fun segString(h: AnimHeader): String = if (h.hasSegments) "${h.intro},${h.loop},${h.outro}" else ""

    fun seg(s: String): Triple<Long, Long, Long>? {
        val p = s.split(',').mapNotNull { it.trim().toLongOrNull() }
        if (p.size != 3 || p[1] <= 0) return null
        return Triple(max(0, p[0]), p[1], max(0, p[2]))
    }

    /**
     * Time of the animation at [tMs] of a block [durMs] long: the intro as it is, the end of the block plays the outro,
     * the middle repeats the loop. A block shorter than intro + outro squeezes both evenly.
     */
    fun segTime(tMs: Long, durMs: Long, intro: Long, loop: Long, outro: Long): Long {
        if (tMs <= 0) return 0
        if (durMs <= intro + outro) {
            val k = (intro + outro).toDouble() / max(1L, durMs)
            return min(intro + loop + outro - 1, (tMs * k).toLong().let { if (it < intro) it else it + loop })
        }
        val outStart = durMs - outro
        return when {
            tMs < intro -> tMs
            tMs >= outStart -> intro + loop + min(outro - 1, tMs - outStart).coerceAtLeast(0)
            else -> intro + (tMs - intro) % loop
        }
    }

    // ---------- cues ----------

    /**
     * Moments inside a block [startMs, endMs) as seconds from its start: every word, the words of the items ([labels]),
     * the main item, pauses longer than [pauseMs] and the cuts of the main track ([cuts], output ms).
     */
    fun cues(words: List<SWord>, startMs: Long, endMs: Long, labels: List<Pair<String, Boolean>>, cuts: List<Long>, pauseMs: Long = 350): List<AnimCue> {
        fun s(ms: Long) = Math.round((ms - startMs) / 10.0) / 100.0
        val inside = words.filter { it.startMs >= startMs && it.startMs < endMs }
        val out = mutableListOf<AnimCue>()
        inside.take(60).forEachIndexed { i, w -> out += AnimCue("w$i", s(w.startMs), "word", w.text) }
        fun key(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        labels.forEachIndexed { i, (l, main) ->
            val k = key(l).take(5)
            if (k.length < 2) return@forEachIndexed
            val hit = inside.firstOrNull { key(it.text).startsWith(k) } ?: return@forEachIndexed
            out += AnimCue("item${i + 1}", s(hit.startMs), "item", l)
            if (main) out += AnimCue("main", s(hit.startMs), "main", l)
        }
        if (out.none { it.kind == "main" }) inside.firstOrNull { w -> w.text.any { it.isDigit() } }?.let { out += AnimCue("main", s(it.startMs), "main", it.text) }
        inside.zipWithNext().forEach { (a, b) -> if (b.startMs - a.endMs >= pauseMs) out += AnimCue("pause", s(a.endMs), "pause") }
        cuts.filter { it in (startMs + 1) until endMs }.forEach { out += AnimCue("cut", s(it), "cut") }
        return out.sortedBy { it.t }
    }

    fun cuesJson(cues: List<AnimCue>): JSONArray = JSONArray().apply {
        cues.forEach { c -> put(JSONObject().put("id", c.id).put("t", c.t).put("kind", c.kind).apply { if (c.text.isNotEmpty()) put("w", c.text) }) }
    }

    /** A short line of the cues for the request (not the words: they are in the speech already). */
    fun cuesText(cues: List<AnimCue>): String = cues.filter { it.kind != "word" }.joinToString("; ") {
        it.id + " " + String.format(java.util.Locale.US, "%.2f", it.t) + (if (it.text.isNotEmpty()) " «${it.text.take(20)}»" else "")
    }

    // ---------- variants ----------

    private val FENCE = Regex("```([A-Za-z]*)\\s*\\n([\\s\\S]*?)```")

    /** Every answer in [text]: one per code block (variants #K7Q2-1…), else the whole text as one. */
    fun detectAll(text: String?): List<InsertAnswer> {
        val raw = text?.let { RelayText.clean(it, false) }.orEmpty()
        if (raw.isBlank() || RelayText.isOwnRequest(raw)) return emptyList()
        val blocks = FENCE.findAll(raw).map { "```" + it.groupValues[1] + "\n" + it.groupValues[2] + "```" }.toList()
        val found = if (blocks.size > 1) blocks.mapNotNull { EditInsert.detect(it) } else emptyList()
        return found.ifEmpty { listOfNotNull(EditInsert.detect(raw)) }
    }

    /** The lines the request gets for [n] variants. */
    fun variantsText(n: Int, req: String): String = t(
        "ВАРИАНТЫ: $n — верни $n разных ответа подряд, каждый в своём блоке кода, с метками #$req-1 … #$req-$n: разные по композиции и движению, все по одному брифу.",
        "VARIANTS: $n — return $n different answers one after another, each in its own code block, marked #$req-1 … #$req-$n: different in layout and motion, all by the same brief."
    )

    // ---------- checks of the result ----------

    private val COLOR = Regex("""['"]#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})['"]|rgba?\(\s*\d""")

    /** Colours written into the code instead of taken from data.theme (more than [allowed] different ones). */
    fun hardColors(code: String, allowed: Int = 2): Int {
        val noComments = code.lines().joinToString("\n") { it.substringBefore("//") }
        val n = COLOR.findAll(noComments).map { it.value.lowercase() }.distinct().count()
        val usesTheme = noComments.contains("theme") || noComments.contains("T(")
        return if (n > allowed && !usesTheme) n else 0
    }

    /** Share of [zone] (fractions of the frame) that [argb] (the frame [w]×[h]) covers with opaque pixels. */
    fun coverage(argb: IntArray, w: Int, h: Int, zone: FRect, step: Int = 2): Float {
        val x0 = (zone.l * w).toInt().coerceIn(0, w)
        val x1 = (zone.r * w).toInt().coerceIn(0, w)
        val y0 = (zone.t * h).toInt().coerceIn(0, h)
        val y1 = (zone.b * h).toInt().coerceIn(0, h)
        var all = 0
        var hit = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                all++
                if ((argb[y * w + x] ushr 24) >= 128) hit++
                x += step
            }
            y += step
        }
        return if (all == 0) 0f else hit.toFloat() / all
    }

    /** Problems from the probe of the trial run (`__lumen.report()`): the smallest text and text past the edges. */
    fun reportProblems(json: String?, minFont: Int): List<String> {
        val o = runCatching { JSONObject(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        val small = o.optDouble("minFont", 0.0)
        if (minFont > 0 && small > 0 && small < minFont * 0.8) out += t("текст ${small.toInt()} px — мельче минимума $minFont px", "text ${small.toInt()} px — smaller than $minFont px")
        val outside = o.optInt("outside")
        if (outside > 0) out += t("надписи выходят за край блока ($outside раз)", "texts go past the edge of the block ($outside times)")
        return out
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** A place of the video that asks for an insert: the type, the time, what is said there and why. */
data class InsertHint(val type: String, val startMs: Long, val endMs: Long, val text: String, val why: String, val score: Int, val rows: List<BriefRow> = emptyList())

/** One line of the report on the inserts of a video; [id] — the block to go to. */
data class ReportLine(val text: String, val id: String? = null, val bad: Boolean = true)

/** The inserts of a video in numbers. */
data class InsertReport(val count: Int, val perMinute: Double, val coverage: Double, val lines: List<ReportLine>)

/** A record of one insert for the local statistics: how it was made and whether the first answer was fine. */
data class InsertStat(val kind: String, val source: String, val provider: String = "", val firstPass: Boolean = true, val rounds: Int = 0, val renderMs: Long = 0, val at: Long = 0) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("source", source).put("provider", provider).put("first", firstPass)
        .put("rounds", rounds).put("render", renderMs).put("at", at)

    companion object {
        fun of(o: JSONObject) = InsertStat(o.optString("kind"), o.optString("source"), o.optString("provider"), o.optBoolean("first", true),
            o.optInt("rounds"), o.optLong("render"), o.optLong("at"))
    }
}

/**
 * The whole-video side of the inserts (PLAN_INSERT_V2 phases 2 and 4): places that ask for an insert by the speech,
 * native schemes from data without AI, the report on density, coverage, reading time, zones and style unity, texts for a
 * translation, statistics and packs of the library. Pure, no Android.
 */
object EditSuggest {
    // ---------- places for inserts ----------

    private data class Sentence(val from: Int, val to: Int, val startMs: Long, val endMs: Long, val text: String)

    /** Sentences of the speech: by .!? and by pauses longer than [pauseMs]. */
    private fun sentences(words: List<SWord>, pauseMs: Long = 700): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var a = 0
        for (i in words.indices) {
            val end = words[i].text.trimEnd().lastOrNull()?.let { it in ".!?…" } == true
            val pause = i + 1 < words.size && words[i + 1].startMs - words[i].endMs >= pauseMs
            if (end || pause || i == words.lastIndex) {
                out += Sentence(a, i, words[a].startMs, words[i].endMs, words.subList(a, i + 1).joinToString(" ") { it.text })
                a = i + 1
            }
        }
        return out
    }

    // word edges that know Cyrillic (\b of the JVM since Java 19 is ASCII only)
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val ENUM = Regex("""(?iu)$B(во-первых|во-вторых|в-третьих|первое|второе|третье|сначала|затем|потом|наконец|шаг|этап|first|second|third|then|finally|step)$E""")
    private val LISTY = Regex("""(?iu)$B(причин\p{L}*|совет\p{L}*|ошиб\p{L}*|правил\p{L}*|способ\p{L}*|пункт\p{L}*|reasons?|tips?|mistakes?|ways?|rules?)$E""")
    private val VERSUS = Regex("""(?iu)$B(против|в отличие|чем|зато|тогда как|versus|vs|than|whereas)$E""")
    private val CAUSE = Regex("""(?iu)$B(потому что|поэтому|из-за|в результате|приводит|следовательно|because|therefore|leads to|as a result)$E""")
    private val CTA = Regex("""(?iu)$B(подпиш\p{L}*|лайк\p{L}*|ссылк\p{L}*|в описании|коммент\p{L}*|subscribe|like|link in|comment)$E""")
    private val PROS = Regex("""(?iu)$B(плюс\p{L}*|минус\p{L}*|преимуществ\p{L}*|недостат\p{L}*|pros?|cons?|advantages?|downsides?)$E""")
    private val QUOTE = Regex("""«[^»]{8,}»|"[^"]{8,}"|(?iu)$B(сказал\p{L}*|говорит|по словам|said|says)$E""")
    private val YEAR = Regex("""\b(1[89]\d\d|20\d\d)\b""")

    /** Short items of a sentence (for a list or steps): its clauses of 1–6 words, at most [max]. */
    fun itemsFrom(text: String, max: Int = 5): List<BriefRow> = text.split(Regex("[,;:.!?]|\\s[—–-]\\s|\\s(и|а|затем|потом|then|and)\\s"))
        .map { it.trim().trim('«', '»', '"') }
        .map { s -> s.replace(ENUM, "").trim() }
        .filter { s -> s.split(Regex("\\s+")).count { w -> w.any(Char::isLetterOrDigit) } in 1..6 }
        .distinct().take(max).map { BriefRow(it.split(Regex("\\s+")).take(4).joinToString(" ")) }

    /**
     * Places that ask for an insert: numbers → number fact / chart / A vs B, enumerations → steps or a list, years → a
     * timeline, causes → cause and effect, pros and cons, quotes, calls to action. At least [gapMs] apart, best first in time.
     */
    fun suggest(words: List<SWord>, gapMs: Long = 4000): List<InsertHint> {
        if (words.isEmpty()) return emptyList()
        val ss = sentences(words)
        val raw = mutableListOf<InsertHint>()
        for ((k, s) in ss.withIndex()) {
            val win = ss.subList(k, min(ss.size, k + 3))
            val winText = win.joinToString(" ") { it.text }
            val winEnd = win.last().endMs
            val rows = EditInsert.dataFromSpeech(s.text)
            val nums = rows.count { it.value.isNotEmpty() }
            val years = YEAR.findAll(winText).count()
            val enums = ENUM.findAll(winText).count()
            fun add(type: String, why: String, score: Int, end: Long = s.endMs, r: List<BriefRow> = rows) {
                raw += InsertHint(type, s.startMs, max(end, s.startMs + 2500), s.text.take(160), why, score, r)
            }
            when {
                years >= 2 && YEAR.containsMatchIn(s.text) -> add("timeline", t("годы подряд", "years in a row"), 7, winEnd, emptyList())
                nums >= 3 -> add("chart", t("несколько чисел", "several numbers"), 8)
                nums == 2 && (VERSUS.containsMatchIn(s.text) || rows.count { it.label.isNotEmpty() } == 2) -> add("compare", t("два числа рядом", "two numbers side by side"), 8)
                nums >= 1 && rows.any { it.unit.isNotEmpty() } -> add("number", t("число с единицей", "a number with a unit"), 7)
            }
            if (enums >= 2) add("steps", t("перечисление по порядку", "an ordered enumeration"), 6, winEnd, itemsFrom(winText))
            else if (LISTY.containsMatchIn(s.text) && Regex("""(?iu)$B(\d|два|три|четыре|пять|семь|десять|two|three|four|five|seven|ten)$E""").containsMatchIn(s.text))
                add("list", t("«N советов/причин»", "\"N tips/reasons\""), 6, winEnd, itemsFrom(winText))
            if (CAUSE.containsMatchIn(s.text)) add("cause", t("причина и следствие", "cause and effect"), 4, r = itemsFrom(s.text, 3))
            if (PROS.containsMatchIn(s.text)) add("proscons", t("плюсы и минусы", "pros and cons"), 4, winEnd, itemsFrom(winText, 4))
            if (QUOTE.containsMatchIn(s.text)) add("quote", t("цитата", "a quote"), 3, r = emptyList())
            if (CTA.containsMatchIn(s.text)) add("cta", t("призыв", "a call to action"), 5, r = emptyList())
        }
        // the best one per place, then at least gapMs between places
        val best = raw.sortedWith(compareByDescending<InsertHint> { it.score }.thenBy { it.startMs })
        val kept = mutableListOf<InsertHint>()
        for (h in best) if (kept.none { kotlin.math.abs(it.startMs - h.startMs) < gapMs }) kept += h
        return kept.sortedBy { it.startMs }
    }

    // ---------- a scheme from data, no AI ----------

    /** The layout of a native scheme for a type of the wizard. */
    fun layoutFor(type: String, n: Int): String? = when (type) {
        "steps", "cause", "own" -> "chain"
        "timeline" -> "timeline"
        "funnel" -> "funnel"
        "concept", "list" -> "hub"
        "proscons" -> if (n == 4) "matrix" else "chain"
        "compare" -> if (n in 2..3) "venn" else null
        else -> null
    }

    /** A scheme of the brief's rows without AI (null — too few rows for the type's layout). */
    fun schemeFromRows(type: String, rows: List<BriefRow>, title: String = ""): SScheme? {
        val list = rows.filter { it.label.isNotBlank() || it.value.isNotBlank() }
        val hubTitle = title.takeIf { it.isNotBlank() && it != "-" }
        // a hub needs its centre first: the title, when there is one
        val items = if ((type == "list" || type == "concept") && hubTitle != null) listOf(BriefRow(hubTitle)) + list else list
        val layout = layoutFor(type, items.size) ?: return null
        val nodes = items.take(StudioScheme.MAX_NODES).mapIndexed { i, r ->
            val label = r.label.ifBlank { r.value }
            val value = if (r.label.isNotBlank() && r.value.isNotBlank()) r.value + (if (r.unit.isEmpty()) "" else if (r.unit == "%") "%" else " ${r.unit}") else ""
            SchNode("n${i + 1}", r.icon, label, value, -1, "", r.main)
        }
        val axes = if (layout == "matrix") listOf(t("Плюсы", "Pros"), t("Минусы", "Cons")) else emptyList()
        val s = SScheme(layout, if (layout == "hub" && hubTitle != null) "" else (hubTitle ?: ""), nodes, emptyList(), axes,
            if (layout == "venn") hubTitle ?: t("общее", "common") else "")
        return s.takeIf { StudioScheme.problems(it).isEmpty() }
    }

    // ---------- the report ----------

    private val VISUAL = setOf("scheme", "image", "text", EditInsert.KIND, EditMotion.KIND, EditLottie.KIND)

    private fun rect(b: EBlock, fw: Int, fh: Int): FRect {
        val hw = b.w / 2
        val hh = b.w * b.ratio * fw / fh / 2
        return FRect(b.x - hw, b.y - hh, b.x + hw, b.y + hh)
    }

    /**
     * Inserts per minute, the share of the video they cover, blocks shorter than their reading time, blocks over the
     * platform's interface, blocks covering each other, long stretches without an insert, style unity.
     */
    fun report(blocks: List<EBlock>, totalMs: Long, fw: Int, fh: Int, platform: String = ""): InsertReport {
        val ins = blocks.filter { it.kind in VISUAL && !EditCaptions.isSub(it) && !EditInsert.isPending(it) }
        val minutes = max(1.0 / 60, totalMs / 60_000.0)
        val lines = mutableListOf<ReportLine>()
        // coverage: the union of the intervals
        var covered = 0L
        var curA = -1L
        var curB = -1L
        for (b in ins.sortedBy { it.startMs }) {
            if (b.startMs > curB) {
                if (curB > curA) covered += curB - curA
                curA = b.startMs
                curB = b.endMs
            } else curB = max(curB, b.endMs)
        }
        if (curB > curA) covered += curB - curA
        val coverage = if (totalMs > 0) covered.toDouble() / totalMs else 0.0
        for (b in ins) {
            val words = when (b.kind) {
                "scheme" -> b.scheme?.let { s -> s.nodes.sumOf { (it.label + " " + it.value).split(Regex("\\s+")).count { w -> w.isNotBlank() } } + s.title.split(' ').count { it.isNotBlank() } } ?: 0
                "text" -> b.text.split(Regex("\\s+")).count { it.isNotBlank() }
                EditInsert.KIND, EditMotion.KIND -> EditInsert.briefFrom(b.brief)?.let { EditInsert.screenWords(it) } ?: 0
                else -> 0
            }
            if (words > 0 && b.durMs + 100 < EditInsert.readMs(words))
                lines += ReportLine(t("«${name(b)}»: ${StudioEdit.secs(b.durMs)} с, а на чтение нужно ≈${StudioEdit.secs(EditInsert.readMs(words))} с",
                    "«${name(b)}»: ${StudioEdit.secs(b.durMs)} s, reading takes ≈${StudioEdit.secs(EditInsert.readMs(words))} s"), b.id)
            val r = rect(b, fw, fh)
            if (r.w < 0.99f) EditInsert.uiZones(fw, fh, platform).firstOrNull { EditInsert.cross(r, it.r) != null }?.let { z ->
                lines += ReportLine(t("«${name(b)}» заходит на «${z.why}»", "«${name(b)}» covers «${z.why}»"), b.id)
            }
        }
        for (i in ins.indices) for (j in i + 1 until ins.size) {
            val a = ins[i]
            val c = ins[j]
            if (a.startMs < c.endMs && c.startMs < a.endMs && EditInsert.cross(rect(a, fw, fh), rect(c, fw, fh)) != null && a.kind != "image" && c.kind != "image")
                lines += ReportLine(t("«${name(a)}» и «${name(c)}» закрывают друг друга", "«${name(a)}» and «${name(c)}» cover each other"), c.id)
        }
        val gap = if (totalMs <= 90_000) 20_000L else 45_000L
        var last = 0L
        for (b in ins.sortedBy { it.startMs } + listOf(EBlock("", "text", totalMs, StudioEdit.MIN_MS))) {
            if (b.startMs - last > gap && totalMs > gap) lines += ReportLine(t("${StudioEdit.clock(last)}–${StudioEdit.clock(b.startMs)} без вставок", "${StudioEdit.clock(last)}–${StudioEdit.clock(b.startMs)} without inserts"), null, false)
            last = max(last, b.endMs)
        }
        EditThemes.issues(blocks).forEach { lines += ReportLine(it.text + t(" — приведите к одной теме", " — bring them to one theme"), it.ids.firstOrNull()) }
        val perMin = ins.size / minutes
        if (perMin > 8) lines += ReportLine(t("Вставок ${"%.1f".format(perMin)} в минуту — зритель не успеет", "${"%.1f".format(perMin)} inserts a minute — too many to follow"), null)
        return InsertReport(ins.size, perMin, coverage, lines)
    }

    fun name(b: EBlock): String = when (b.kind) {
        "scheme" -> b.scheme?.title?.ifBlank { null } ?: b.scheme?.nodes?.firstOrNull()?.label ?: t("Схема", "Scheme")
        "text" -> b.text.take(24)
        else -> b.text.ifBlank { EditMotion.preset(b.look)?.name ?: b.kind }.take(24)
    }

    // ---------- texts for a translation ----------

    /** Every text of the inserts, by a key the translation comes back with. */
    fun texts(blocks: List<EBlock>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (b in blocks) when (b.kind) {
            "scheme" -> b.scheme?.let { s ->
                if (s.title.isNotBlank()) out["${b.id}|title"] = s.title
                s.nodes.forEachIndexed { i, n ->
                    if (n.label.isNotBlank()) out["${b.id}|n$i|label"] = n.label
                    if (n.value.isNotBlank() && n.value.any { it.isLetter() }) out["${b.id}|n$i|value"] = n.value
                }
                if (s.center.isNotBlank()) out["${b.id}|center"] = s.center
            }
            "text" -> if (!EditCaptions.isSub(b) && b.text.isNotBlank()) out["${b.id}|text"] = b.text
            EditInsert.KIND, EditMotion.KIND, EditLottie.KIND -> EditInsert.briefFrom(b.brief)?.let { br ->
                if (br.title.isNotBlank() && br.title != "-") out["${b.id}|btitle"] = br.title
                br.rows.forEachIndexed { i, r -> if (r.label.isNotBlank()) out["${b.id}|r$i"] = r.label }
                br.fields.forEach { (k, v) -> if (v.isNotBlank() && v.any { it.isLetter() }) out["${b.id}|f|$k"] = v }
                br.params.forEach { (k, v) -> if (v.isNotBlank() && v.any { it.isLetter() } && !v.startsWith("#") && v !in setOf("true", "false")) out["${b.id}|p|$k"] = v }
            }
        }
        return out
    }

    /** The blocks with the translated texts [tr] (keys of [texts]); animations get new data, so they are drawn again. */
    fun applyTexts(blocks: List<EBlock>, tr: Map<String, String>): List<EBlock> = blocks.map { b ->
        fun v(k: String) = tr["${b.id}|$k"]?.takeIf { it.isNotBlank() }
        when (b.kind) {
            "scheme" -> b.scheme?.let { s ->
                b.copy(scheme = s.copy(title = v("title") ?: s.title, center = v("center") ?: s.center,
                    nodes = s.nodes.mapIndexed { i, n -> n.copy(label = v("n$i|label") ?: n.label, value = v("n$i|value") ?: n.value) }))
            } ?: b
            "text" -> v("text")?.let { b.copy(text = it) } ?: b
            EditInsert.KIND, EditMotion.KIND, EditLottie.KIND -> EditInsert.briefFrom(b.brief)?.let { br ->
                val nb = br.copy(title = v("btitle") ?: br.title, rows = br.rows.mapIndexed { i, r -> r.copy(label = v("r$i") ?: r.label) },
                    fields = br.fields.mapValues { (k, x) -> v("f|$k") ?: x }, params = br.params.mapValues { (k, x) -> v("p|$k") ?: x })
                if (nb == br) b else b.copy(brief = EditInsert.briefJson(nb).toString())
            } ?: b
            else -> b
        }
    }

    // ---------- statistics ----------

    /** Lines of the local statistics: inserts without AI, the first AI answer passing the checks, fix rounds, by provider. */
    fun statsLines(list: List<InsertStat>): List<String> {
        if (list.isEmpty()) return listOf(t("Пока нет данных", "No data yet"))
        val out = mutableListOf<String>()
        val native = list.count { it.source in setOf("native", "example", "library") }
        out += t("Вставок: ${list.size}, без ИИ: $native (${native * 100 / list.size} %)", "Inserts: ${list.size}, without AI: $native (${native * 100 / list.size} %)")
        list.filter { it.source == "ai" }.groupBy { it.provider.ifBlank { "?" } }.forEach { (p, l) ->
            val ok = l.count { it.firstPass }
            out += "$p: " + t("первый ответ прошёл проверки ${ok * 100 / l.size} % (${l.size}), кругов исправления в среднем ${"%.1f".format(l.map { it.rounds }.average())}",
                "first answer passed ${ok * 100 / l.size} % (${l.size}), fix rounds on average ${"%.1f".format(l.map { it.rounds }.average())}")
        }
        list.filter { it.renderMs > 0 }.takeIf { it.isNotEmpty() }?.let { l -> out += t("Рендер анимации в среднем ${l.map { it.renderMs }.average().toLong() / 1000} с", "Animation render on average ${l.map { it.renderMs }.average().toLong() / 1000} s") }
        return out
    }

    // ---------- packs of the library ----------

    const val PACK = "lumen.pack v1"

    /** A pack to share: saved inserts, themes and recipes in one JSON. */
    fun pack(items: List<SavedInsert>, themes: List<EditTheme>, recipes: List<Pair<String, EditBrief>>): JSONObject = JSONObject().put("protocol", PACK)
        .put("inserts", JSONArray().apply { items.forEach { put(it.json()) } })
        .put("themes", JSONArray().apply { themes.filter { !it.builtIn }.forEach { put(EditThemes.json(it)) } })
        .put("recipes", JSONArray().apply { recipes.forEach { (n, r) -> put(JSONObject().put("name", n).put("brief", EditInsert.briefJson(r))) } })

    data class Pack(val inserts: List<SavedInsert>, val themes: List<EditTheme>, val recipes: List<Pair<String, EditBrief>>)

    fun unpack(s: String): Pack? = runCatching {
        val o = JSONObject(s)
        if (o.optString("protocol") != PACK) return null
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        val ins = arr("inserts").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> SavedInsert.of(j) } } }
        val th = arr("themes").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> EditThemes.parse(j.toString()) } } }
        val rc = arr("recipes").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> EditInsert.briefFrom(j.optJSONObject("brief"))?.let { b -> j.optString("name") to b } } } }
        Pack(ins, th, rc)
    }.getOrNull()
}

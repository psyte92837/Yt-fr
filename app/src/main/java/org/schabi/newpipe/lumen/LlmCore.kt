/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/*
 * Token economy of the AI calls (PROMPTS_PLAN): pure helpers, no Android, covered by LlmCoreTest.
 * What goes into {data} costs far more than the prompt texts, so most of the saving is here.
 */

/** Token count without a tokenizer: Cyrillic text has ~3 characters per token, Latin ~4 (Gemini, GPT-4o). */
object TokenEstimate {
    fun of(text: String): Int {
        if (text.isEmpty()) return 1
        var letters = 0
        var cyr = 0
        for (c in text) {
            if (c.isLetter()) {
                letters++
                if (c in 'Ѐ'..'ӿ') cyr++
            }
        }
        val share = if (letters == 0) 0.0 else cyr.toDouble() / letters
        val perToken = 4.0 - 1.0 * share
        return (text.length / perToken).toInt() + 1
    }
}

/** Which tasks only extract or classify (no long reasoning): they run with minimal thinking. */
object LlmTasks {
    private val LIGHT = setOf(
        "domain", "chunk_summary", "relevance", "watch_filter", "watch_translate", "watch_digest", "harvest_ai",
        "algo_judge_videos", "algo_judge_channels", "algo_niches", "algo_changes", "algo_topic",
        "ideas_queries", "ideas_translate", "predict_names", "predict_classify", "dossier_channel",
        "voc_extract", "json_fix", "studio_edit_query", "studio_edit_title", "studio_edit_translate",
        "studio_cuts_filter", "studio_stt", "studio_tts_timing", "studio_mix_find", "transcribe", "player_filter", "ping"
    )

    fun isLight(task: String): Boolean = task in LIGHT || task.startsWith("studio_mix_")

    /** OpenAI reasoning models (o-series, gpt-5): low effort for light tasks; null for the others. */
    fun openAiEffort(model: String, light: Boolean): String? {
        if (!light) return null
        val m = model.lowercase()
        return if (Regex("^o\\d").containsMatchIn(m) || m.startsWith("gpt-5")) "low" else null
    }

    /** Characters one call may carry for a big-context model, otherwise [chunk] (the user's setting). */
    fun contextChars(kind: String, model: String, chunk: Int, userSet: Boolean): Int {
        if (userSet) return chunk
        val m = model.lowercase()
        val big = kind == "GEMINI" || kind == "ANTHROPIC" || m.startsWith("gpt-4o") || m.startsWith("gpt-4.1") ||
            m.startsWith("gpt-5") || m.startsWith("gemini") || m.startsWith("claude")
        return if (big) maxOf(chunk, 120_000) else chunk
    }
}

/** Transcripts as the model needs them: a timecode per ~30 s paragraph, not per caption line (−38 % tokens). */
object Transcript {
    private val TC = Regex("""^\s*\[(\d{1,2}(?::\d{2}){1,2})]\s*""")

    private fun seconds(tc: String): Int = tc.split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }

    /**
     * Joins caption lines "[m:ss] text" into paragraphs of about [stepSec] seconds, ending on a sentence when it can.
     * [timecodes] = false gives plain text. Text without timecodes is returned as is (whitespace tidied).
     */
    fun forAi(text: String, stepSec: Int = 30, timecodes: Boolean = true): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.none { TC.containsMatchIn(it) }) return lines.joinToString("\n")
        if (!timecodes) return lines.joinToString(" ") { TC.replace(it, "") }.replace(Regex("\\s+"), " ").trim()
        val out = StringBuilder()
        var start = -1
        var startTc = ""
        val para = StringBuilder()
        fun flush() {
            if (para.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            if (startTc.isNotEmpty()) out.append('[').append(startTc).append("] ")
            out.append(para.toString().trim())
            para.setLength(0)
        }
        for (l in lines) {
            val m = TC.find(l)
            val body = if (m != null) l.substring(m.range.last + 1).trim() else l
            if (m != null) {
                val tc = m.groupValues[1]
                val s = seconds(tc)
                val ended = para.isNotEmpty() && para.trimEnd().last() in ".!?…"
                if (start < 0 || (s - start >= stepSec && ended) || s - start >= stepSec * 8 / 5) {
                    flush()
                    start = s
                    startTc = tc
                }
            }
            if (body.isNotEmpty()) {
                if (para.isNotEmpty()) para.append(' ')
                para.append(body)
            }
        }
        flush()
        return out.toString()
    }

    /**
     * Fits [text] into [budget] characters keeping the beginning, the middle and the end (the hook, the body and the
     * conclusion), cut on line ends. A plain take() would lose the end of the video.
     */
    fun sample(text: String, budget: Int): String {
        if (text.length <= budget || budget < 400) return text.take(maxOf(budget, 0))
        val mark = "\n[…]\n"
        val room = budget - 2 * mark.length
        val head = cut(text, 0, room / 2)
        val midStart = text.length / 2 - room / 8
        val mid = cut(text, midStart, room / 4)
        val tail = cut(text, text.length - room / 4, room / 4)
        return head + mark + mid + mark + tail
    }

    private fun cut(text: String, from0: Int, len: Int): String {
        var from = from0.coerceIn(0, text.length)
        if (from > 0) {
            val nl = text.indexOf('\n', from)
            if (nl in from until from + len / 3) from = nl + 1
        }
        var end = (from + len).coerceAtMost(text.length)
        if (end < text.length) {
            val nl = text.lastIndexOf('\n', end)
            if (nl > from + len / 2) end = nl
        }
        return text.substring(from, end).trim()
    }
}

/** Descriptions without links, hashtag lines and the boilerplate a channel repeats under every video. */
object DescClean {
    private val URL = Regex("""https?://\S+|www\.\S+""")
    private val TAGS = Regex("""^(#\S+\s*)+$""")

    private fun norm(line: String) = line.lowercase().replace(URL, "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Lines (normalized) seen in at least 40 % (and ≥2) of one channel's descriptions: sponsor blocks, socials. */
    fun boilerplate(descs: List<String>): Set<String> {
        if (descs.size < 2) return emptySet()
        val count = HashMap<String, Int>()
        for (d in descs) d.lines().map { norm(it) }.filter { it.length >= 12 }.toSet().forEach { count[it] = (count[it] ?: 0) + 1 }
        val need = maxOf(2, (descs.size * 0.4).toInt())
        return count.filter { it.value >= need }.keys
    }

    fun clean(desc: String, boiler: Set<String> = emptySet()): String {
        val out = ArrayList<String>()
        for (raw in desc.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (TAGS.matches(l)) continue
            val n = norm(l)
            if (n.isEmpty() || n in boiler) continue
            val noUrl = URL.replace(l, "").trim().trimEnd(':', '-', '—', '|').trim()
            if (noUrl.none { it.isLetterOrDigit() }) continue
            // "Telegram: https://…", "Курс здесь — https://…": a link label says nothing about the video
            if (URL.containsMatchIn(l) && noUrl.split(Regex("\\s+")).size <= 4) continue
            out += noUrl
        }
        return out.joinToString("\n")
    }
}

/** What "works" is speed and the jump over the channel's usual level, not raw views. */
object ViewStats {
    const val DAY = 86_400_000L

    fun perDay(views: Long, uploadMs: Long, now: Long): Double {
        if (views <= 0 || uploadMs <= 0 || uploadMs > now) return Double.NaN
        val days = ((now - uploadMs).toDouble() / DAY).coerceAtLeast(1.0)
        return views / days
    }

    /** Median per-day speed of each channel (only channels with ≥2 measured videos). */
    fun channelMedians(rows: List<Pair<String, Double>>): Map<String, Double> =
        rows.filter { !it.second.isNaN() }.groupBy({ it.first }, { it.second }).filter { it.value.size >= 2 }
            .mapValues { (_, l) -> l.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 } }

    /** 1234 → "1.2k", 3_400_000 → "3.4M": fewer tokens, the same signal. */
    fun short(n: Double): String = when {
        n.isNaN() -> "?"
        n >= 1e9 -> fmt(n / 1e9) + "B"
        n >= 1e6 -> fmt(n / 1e6) + "M"
        n >= 1e3 -> fmt(n / 1e3) + "k"
        n >= 10 -> n.toLong().toString()
        else -> fmt(n)
    }

    private fun fmt(x: Double): String = if (x >= 100) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x).removeSuffix(".0")

    /** " · 1.2k/day · ×3.4 of channel" (empty parts left out). */
    fun tag(views: Long, uploadMs: Long, now: Long, channelMedian: Double?): String {
        val v = perDay(views, uploadMs, now)
        if (v.isNaN()) return ""
        val sb = StringBuilder(" · ").append(short(v)).append("/day")
        if (channelMedian != null && channelMedian > 0) sb.append(" · ×").append(fmt(v / channelMedian)).append(" ch")
        return sb.toString()
    }

    fun ageDays(uploadMs: Long, now: Long): Int = if (uploadMs <= 0 || uploadMs > now) -1 else ((now - uploadMs) / DAY).toInt()
}

/** Picks the documents a question is about (TF-IDF over 5-letter stems), within a character budget. */
object Retrieve {
    private val WORD = Regex("[\\p{L}\\p{N}]{3,}")

    private fun stems(s: String): List<String> = WORD.findAll(s.lowercase().replace('ё', 'е')).map { it.value.take(5) }.toList()

    fun topK(question: String, docs: List<String>, budgetChars: Int, minDocs: Int = 3): List<Int> {
        if (docs.isEmpty()) return emptyList()
        val q = stems(question).toSet()
        val tfs = docs.map { d -> stems(d).groupingBy { it }.eachCount() }
        val df = HashMap<String, Int>()
        tfs.forEach { tf -> tf.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.toDouble()
        val score = tfs.mapIndexed { i, tf ->
            val len = tf.values.sum().coerceAtLeast(1)
            i to q.sumOf { w -> val c = tf[w] ?: 0; if (c == 0) 0.0 else (1 + ln(c.toDouble())) * ln(1 + n / (df[w] ?: 1)) } / ln(2.0 + len)
        }
        val order = score.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first }).map { it.first }
        val out = ArrayList<Int>()
        var used = 0
        for (i in order) {
            if (out.size >= minDocs && (score[i].second <= 0.0 || used + docs[i].length > budgetChars)) break
            out += i
            used += docs[i].length
        }
        return out.sorted()
    }
}

/** API errors that no repeat, smaller request or option change can fix: retrying them only burns the quota. */
object LlmErrors {
    enum class Kind { REGION, KEY, DAILY }

    /** [code] HTTP status, [raw] the error body or message. */
    fun fatal(code: Int, raw: String): Kind? {
        val m = raw.lowercase()
        return when {
            m.contains("location is not supported") || m.contains("unsupported_country") ||
                m.contains("country, region, or territory not supported") -> Kind.REGION
            code == 401 || m.contains("api_key_invalid") || m.contains("api key not valid") || m.contains("invalid api key") ||
                m.contains("incorrect api key") -> Kind.KEY
            code == 429 && Regex("per ?day|requestsperday|daily").containsMatchIn(m) -> Kind.DAILY
            else -> null
        }
    }
}

/**
 * The model is overloaded (Gemini 503 "high demand", Anthropic 529) or hit a per-minute limit: a repeat a minute later or
 * another model of the same provider usually works. Gemini gets a short chain of neighbours of the chosen model; a busy
 * model rests for [REST_MS], so the next calls go straight to the neighbour instead of waiting for the retries again.
 */
object LlmBusy {
    const val REST_MS = 5 * 60_000L

    fun overloaded(code: Int, raw: String): Boolean {
        val m = raw.lowercase()
        if (code == 503 || code == 529) return true
        if (code == 429) return LlmErrors.fatal(code, raw) == null
        return code in 500..599 && (m.contains("overloaded") || m.contains("high demand") || m.contains("unavailable"))
    }

    /** Neighbours of a Gemini text model, lighter first for a light model; no models closed to new keys (2.5 Flash). */
    fun alternates(model: String): List<String> {
        val m = model.removePrefix("models/").lowercase()
        if (!m.startsWith("gemini") || m.contains("tts") || m.contains("image")) return emptyList()
        val lite = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest")
        val flash = listOf("gemini-3.5-flash", "gemini-flash-latest")
        val chain = if (m.contains("lite")) lite + flash else if (m.contains("pro")) listOf("gemini-pro-latest") + flash + lite else flash + lite
        return chain.filter { it != m }.distinct()
    }

    /** Models to try: the chosen one first unless it is resting, resting ones at the end (oldest rest first). */
    fun order(model: String, restingUntil: Map<String, Long>, now: Long): List<String> {
        val all = (listOf(model) + alternates(model)).distinct()
        val (rest, free) = all.partition { (restingUntil[it] ?: 0L) > now }
        return free + rest.sortedBy { restingUntil[it] ?: 0L }
    }
}

/** Cheap repairs of JSON answers: fix the answer alone, or ask only for the rest of an answer cut by the limit. */
object JsonFix {
    /** The answer is a JSON object (after fences, comments, trailing commas are dropped). */
    fun parses(text: String): Boolean = JsonText.firstObject(tidy(text))?.let { runCatching { JSONObject(it) }.isSuccess } == true

    /** Fences, line/block comments and commas before } or ] that chat models leave in JSON. */
    fun tidy(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) t = t.substringAfter('\n', "").substringBeforeLast("```")
        val out = StringBuilder(t.length)
        var i = 0
        var inStr = false
        while (i < t.length) {
            val c = t[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < t.length) {
                    out.append(t[i + 1]); i += 2; continue
                }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c) }
                c == '/' && i + 1 < t.length && t[i + 1] == '/' -> { while (i < t.length && t[i] != '\n') i++; continue }
                c == '/' && i + 1 < t.length && t[i + 1] == '*' -> { val e = t.indexOf("*/", i + 2); i = if (e < 0) t.length else e + 2; continue }
                c == ',' -> {
                    var j = i + 1
                    while (j < t.length && t[j].isWhitespace()) j++
                    if (j < t.length && (t[j] == '}' || t[j] == ']')) { i++; continue }
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** A request to fix the syntax of [answer] only: no source data, so it costs ~the answer itself. */
    fun repairPrompt(answer: String, problem: String, hint: String): String =
        "The text below should be one valid JSON object" + (if (hint.isNotBlank()) " ($hint)" else "") + ", but it is not: " + problem +
            ".\nReturn only the corrected JSON with the same content. Do not add, drop or translate values.\n\n" + answer.trim()

    /** Asks for the items after the ones already received when the answer was cut by the output limit. */
    fun continuePrompt(base: String, key: String, got: JSONArray): String {
        val n = got.length()
        val last = if (n == 0) "" else got.opt(n - 1).toString().take(300)
        return base + "\n\nCONTINUATION: your previous answer was cut by the length limit after $n items of \"$key\"" +
            (if (last.isNotEmpty()) " (the last one: $last)" else "") +
            ". Return only JSON {\"$key\":[…]} with the remaining items that follow it, in the same format, without repeating those $n."
    }

    /** The complete items of array [key] in a cut answer (empty when none). */
    fun items(text: String, key: String): JSONArray =
        JsonSalvage.array(text, key)?.let { runCatching { JSONObject(it).optJSONArray(key) }.getOrNull() } ?: JSONArray()

    /**
     * Joins the received part and the continuation into one answer: the first object (its other fields kept) with
     * [key] = first items + new items. Null when the continuation has no items.
     */
    fun merge(first: String, more: String, key: String): String? {
        val got = items(first, key)
        val add = JsonText.firstObject(tidy(more))?.let { runCatching { JSONObject(it).optJSONArray(key) }.getOrNull() } ?: items(more, key)
        if (add.length() == 0) return null
        val base = JsonText.firstObject(tidy(first))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: headOf(first, key)
        val all = JSONArray()
        for (i in 0 until got.length()) all.put(got.get(i))
        for (i in 0 until add.length()) all.put(add.get(i))
        return base.put(key, all).toString()
    }

    /** Simple fields written before the cut array ("title":"…") so they survive the merge. */
    private fun headOf(text: String, key: String): JSONObject {
        val o = JSONObject()
        val head = text.substringBefore("\"$key\"")
        Regex("\"([A-Za-z_]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false)").findAll(head).forEach { m ->
            runCatching { o.put(m.groupValues[1], JSONObject("{\"v\":" + m.groupValues[2] + "}").get("v")) }
        }
        return o
    }

    /** Output limit for a repair: the answer's size and a margin, within 256…16000. */
    fun repairLimit(answer: String): Int = (TokenEstimate.of(answer) * 13 / 10 + 200).coerceIn(256, 16_000)
}

/**
 * Voice of the audience in one pass (lumen.voc v1, PROMPTS_PLAN 1C): comments are read once into short items with
 * ids; the digest, the tone and the chat take the items instead of reading the raw comments again.
 */
object VocCore {
    val TYPES = listOf("pain", "case", "question", "objection", "idea", "tip", "hypothesis", "opinion", "praise", "joke")

    data class Line(val id: String, val text: String, val likes: Int)
    data class Item(val type: String, val text: String, val ids: List<String>, val sev: Int)

    fun line(l: Line, max: Int = 500): String = "[" + l.id + " ♥" + l.likes + "] " + l.text.replace('\n', ' ').take(max)

    /** Consecutive batches of at most [maxChars] characters of lines. */
    fun batches(lines: List<Line>, maxChars: Int): List<List<Line>> {
        val out = ArrayList<List<Line>>()
        var cur = ArrayList<Line>()
        var size = 0
        for (l in lines) {
            val n = line(l).length + 1
            if (cur.isNotEmpty() && size + n > maxChars) {
                out += cur
                cur = ArrayList()
                size = 0
            }
            cur += l
            size += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun input(batch: List<Line>): String = batch.joinToString("\n") { line(it) }

    /** Cache key of a batch: the same comments give the same items. */
    fun key(batch: List<Line>): String = Integer.toHexString(input(batch).hashCode()) + "_" + batch.size

    /** Items of an answer {"i":[{"t","s","ids","sev"}]}; unknown types become "opinion", unknown ids are dropped. */
    fun parse(reply: String, ids: Set<String>): List<Item>? {
        val o = JsonText.firstObject(JsonFix.tidy(reply))?.let { runCatching { JSONObject(it) }.getOrNull() }
        val a = o?.optJSONArray("i") ?: o?.optJSONArray("items") ?: JsonFix.items(reply, "i").takeIf { it.length() > 0 } ?: return null
        val out = ArrayList<Item>()
        for (k in 0 until a.length()) {
            val x = a.optJSONObject(k) ?: continue
            val text = x.optString("s").ifBlank { x.optString("text") }.trim()
            if (text.isEmpty()) continue
            val ia = x.optJSONArray("ids")
            val list = (0 until (ia?.length() ?: 0)).map { ia!!.optString(it).trim() }.filter { it in ids }.distinct()
            if (list.isEmpty()) continue
            val type = x.optString("t").ifBlank { x.optString("type") }.lowercase().let { if (it in TYPES) it else "opinion" }
            out += Item(type, text, list, x.optInt("sev", 0).coerceIn(0, 5))
        }
        return out
    }

    fun toJson(items: List<Item>): JSONArray = JSONArray(items.map {
        JSONObject().put("t", it.type).put("s", it.text).put("ids", JSONArray(it.ids)).put("sev", it.sev)
    })

    fun fromJson(a: JSONArray?): List<Item> = (0 until (a?.length() ?: 0)).mapNotNull { k ->
        val x = a!!.optJSONObject(k) ?: return@mapNotNull null
        val ia = x.optJSONArray("ids")
        Item(x.optString("t"), x.optString("s"), (0 until (ia?.length() ?: 0)).map { ia!!.optString(it) }, x.optInt("sev"))
    }

    /** Number of distinct comments behind each type. */
    fun counts(items: List<Item>): Map<String, Int> =
        TYPES.associateWith { t -> items.filter { it.type == t }.flatMap { it.ids }.toSet().size }.filter { it.value > 0 }

    /**
     * Items for a prompt, grouped by type, the most supported first:
     * "## pain (12 comments)\n- [×3 ♥120 sev4] statement (c3,c17,c20)". At most [maxIds] ids per item.
     */
    fun block(items: List<Item>, likes: Map<String, Int>, maxChars: Int, maxIds: Int = 6): String {
        val sb = StringBuilder()
        val counts = counts(items)
        for (t in TYPES) {
            val list = items.filter { it.type == t }
                .sortedWith(compareByDescending<Item> { it.ids.size }.thenByDescending { it.ids.sumOf { id -> likes[id] ?: 0 } }.thenByDescending { it.sev })
            if (list.isEmpty()) continue
            val head = "## $t (${counts[t] ?: 0} comments)\n"
            if (sb.length + head.length > maxChars) break
            sb.append(head)
            for (it in list) {
                val l = "- [×" + it.ids.size + " ♥" + it.ids.sumOf { id -> likes[id] ?: 0 } + (if (it.sev > 0) " sev" + it.sev else "") + "] " +
                    it.text + " (" + it.ids.take(maxIds).joinToString(",") + ")\n"
                if (sb.length + l.length > maxChars) break
                sb.append(l)
            }
        }
        return sb.toString()
    }

    /** Answer schema (Gemini responseSchema / OpenAI json_schema). */
    val SCHEMA: JSONObject
        get() = JSONObject("""{"type":"object","properties":{"i":{"type":"array","items":{"type":"object","properties":{
            "t":{"type":"string","enum":${JSONArray(TYPES)}},"s":{"type":"string"},"ids":{"type":"array","items":{"type":"string"}},
            "sev":{"type":"integer"}},"required":["t","s","ids"]}}},"required":["i"]}""")

    /**
     * "- c12" lines of a digest (quotes asked by id) become "- «text» ♥likes (c12)": the model does not copy comments.
     * Lines with text are left as they are.
     */
    fun expandQuotes(md: String, byId: Map<String, Line>, max: Int = 400): String = md.lines().joinToString("\n") { l ->
        val m = Regex("""^(\s*[-*•]\s*)\(?((?:c\d+[,\s]*)+)\)?\s*$""").find(l) ?: return@joinToString l
        val ids = Regex("""c\d+""").findAll(m.groupValues[2]).map { it.value }.toList()
        val src = ids.firstNotNullOfOrNull { byId[it] } ?: return@joinToString l
        val text = src.text.replace('\n', ' ').trim().let { if (it.length > max) it.take(max).substringBeforeLast(' ') + "…" else it }
        m.groupValues[1] + "«" + text + "» ♥" + src.likes + " (" + ids.joinToString(", ") + ")"
    }
}

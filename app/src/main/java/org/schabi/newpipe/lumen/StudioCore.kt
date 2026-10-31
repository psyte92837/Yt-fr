/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Lumen Studio: short vertical explainer videos (kinetic captions + simple infographics) from a script
 * and a voice (Microsoft Edge voice, own microphone or an audio file). Everything here is pure logic
 * without Android classes, so it is covered by unit tests; drawing and encoding live in StudioRender.kt.
 */

/** A spoken word and where it sounds in the audio. */
data class SWord(val text: String, val startMs: Long, val endMs: Long)

/** A tile of a scene visual: an emoji and a short label; [value] is the number of a "chart" bar. */
data class SItem(val icon: String, val label: String, val value: String = "")

/**
 * A moment of a scene tied to its word [at] (0 = the first word of the scene). [type]: "zoom" (camera punch-in),
 * "pop" (sticker with [text] and [icon]) or "sfx" (sound effect named [text]).
 */
data class SCue(val type: String, val at: Int, val text: String = "", val icon: String = "")

/**
 * A key frame of an animated shape: on word [at] of the scene plus [ms]. Values left null are taken from the key before.
 * [x], [y]: centre in parts of the frame; [s] scale; [r] rotation in degrees; [o] opacity; [draw] how much of a line is drawn.
 */
data class SKey(
    val at: Int, val ms: Long = 0, val x: Float? = null, val y: Float? = null, val s: Float? = null,
    val r: Float? = null, val o: Float? = null, val draw: Float? = null, val ease: String = ""
)

/** An animated shape of the AI director ([StudioKeys.SHAPES]); [w], [h] in parts of the short side of the frame. */
data class SShape(
    val el: String, val text: String = "", val color: String = "accent", val fill: Boolean = false,
    val w: Float = 0.2f, val h: Float = 0.2f, val keys: List<SKey> = emptyList(),
    /** Text shapes: "center" or "left" (starts at x). */
    val align: String = "center"
)

/**
 * One screen of the video. [say] is spoken (and shown as captions); [kind] picks the visual
 * (see [Studio.KINDS]); [title] is used by "step" and "stat"; [items] by the tile layouts.
 * Set by the AI director: [emph] words highlighted in the captions, [cues] and the entry [transition].
 */
data class SScene(
    val say: String, val kind: String = "text", val title: String = "", val items: List<SItem> = emptyList(),
    val emph: List<String> = emptyList(), val cues: List<SCue> = emptyList(), val transition: String = "",
    /** Animated shapes on key frames. */
    val shapes: List<SShape> = emptyList(),
    /** B-roll: English search words for a stock photo, its [brollMode] ("full" or "card"), the downloaded [media] file and its [credit]. */
    val broll: String = "", val brollMode: String = "", val media: String = "", val credit: String = "",
    /** Id of the YouTube clip shown instead of the graphics (Cuts task, see [StudioCuts]); empty = none. */
    val clip: String = "",
    /** Cartoon task: name of the character saying the line ("" = narrator) and its mood ([StudioToon.MOODS]). */
    val who: String = "", val mood: String = ""
)

data class SScript(val title: String, val scenes: List<SScene>)

/** A span of the audio in milliseconds. */
data class Span(val startMs: Long, val endMs: Long) {
    val ms: Long get() = endMs - startMs
}

/** Where a scene sits in the video: its time and its words (indices into the word list). */
data class SceneTime(val scene: Int, val startMs: Long, val endMs: Long, val words: IntRange)

/** Speech statistics shown after the recording is analysed. */
data class VoiceStats(val totalMs: Long, val speechMs: Long, val pauses: Int, val longestPauseMs: Long, val words: Int) {
    val wordsPerMinute: Int get() = if (speechMs <= 0) 0 else (words * 60_000L / speechMs).toInt()
}

object Studio {
    /** Threads for CPU work (frames, decoding): the cores, at most 4. */
    fun cpuWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Threads that draw video frames next to the encoder: one core stays for encoding; big frames use
     * fewer threads (each keeps a bitmap and buffers); short or tiny jobs stay on one thread.
     */
    fun frameWorkers(cores: Int, w: Int, h: Int, frames: Int): Int {
        if (frames < 60) return 1
        val byCores = (cores - 1).coerceIn(1, 3)
        return if (w.toLong() * h > 1280L * 720) minOf(byCores, 2) else byCores
    }

    /** Joins [parts] into one array with a single copy. */
    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    /** Visual layouts a scene can use. */
    val KINDS = listOf("text", "step", "icons", "list", "flow", "cycle", "big", "stat", "quote", "chart", "compare")

    /** "tpl:id": a scene drawn by a saved motion template. */
    fun isTpl(kind: String) = kind.startsWith("tpl:") && kind.length > 4

    // ---------- script: JSON from AI ----------

    /** Script from an AI reply (tolerates prose and ```json fences around the object). Null if nothing usable. */
    fun parseScript(reply: String): SScript? {
        // a reply cut off by the token limit has no closing brace: keep its complete scenes
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JsonSalvage.array(reply, "scenes")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val arr = o.optJSONArray("scenes") ?: return null
        val scenes = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::sceneOf) }
        if (scenes.isEmpty()) return null
        return SScript(o.optString("title").trim(), scenes)
    }

    fun sceneOf(o: JSONObject): SScene? {
        val say = o.optString("say").ifBlank { o.optString("text") }.trim()
        if (say.isEmpty()) return null
        val kind = o.optString("kind").trim().lowercase().takeIf { it in KINDS || isTpl(it) } ?: "text"
        val items = o.optJSONArray("items")?.let { a ->
            (0 until a.length()).mapNotNull { j ->
                val it = a.opt(j)
                when (it) {
                    is JSONObject -> SItem(it.optString("icon").trim(), it.optString("label").trim(), it.optString("value").trim())
                    is String -> itemOf(it)
                    else -> null
                }?.takeIf { s -> s.icon.isNotEmpty() || s.label.isNotEmpty() }
            }
        } ?: emptyList()
        val emph = o.optJSONArray("emph")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() && it.length <= 40 } }
            ?.take(3) ?: emptyList()
        val cues = o.optJSONArray("cues")?.let { a ->
            (0 until a.length()).mapNotNull { j ->
                val c = a.optJSONObject(j) ?: return@mapNotNull null
                val type = c.optString("type").ifBlank { c.optString("do") }.trim().lowercase()
                if (type !in listOf("zoom", "pop", "sfx")) return@mapNotNull null
                SCue(type, c.optInt("at", 0).coerceAtLeast(0), c.optString("text").ifBlank { c.optString("name") }.trim(), c.optString("icon").trim())
            }
        } ?: emptyList()
        val shapes = o.optJSONArray("shapes")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { StudioKeys.shapeOf(it) } } }
            ?.take(StudioKeys.MAX_SHAPES) ?: emptyList()
        val broll = o.opt("broll").let { b -> if (b is JSONObject) b.optString("query") else o.optString("broll") }.trim().take(80)
        val mode = o.opt("broll").let { b -> if (b is JSONObject) b.optString("mode") else o.optString("brollMode") }.trim().lowercase()
        return SScene(say, kind, o.optString("title").trim(), items.take(MAX_ITEMS), emph, cues, o.optString("transition").trim().lowercase(),
            shapes, broll, if (mode == "card") "card" else if (broll.isNotEmpty() || o.optString("media").isNotEmpty()) "full" else "",
            o.optString("media").trim(), o.optString("credit").trim(),
            o.optString("clip").trim().take(16),
            StudioToon.cleanName(o.optString("who")), o.optString("mood").trim().lowercase().takeIf { it in StudioToon.MOODS } ?: "")
    }

    const val MAX_ITEMS = 6

    fun toJson(s: SScript): String = JSONObject().put("title", s.title).put("scenes", JSONArray().apply {
        s.scenes.forEach { sc ->
            val o = JSONObject().put("say", sc.say).put("kind", sc.kind).put("title", sc.title).put("items", JSONArray().apply {
                sc.items.forEach { put(JSONObject().put("icon", it.icon).put("label", it.label).apply { if (it.value.isNotEmpty()) put("value", it.value) }) }
            })
            if (sc.emph.isNotEmpty()) o.put("emph", JSONArray(sc.emph))
            if (sc.cues.isNotEmpty()) o.put("cues", JSONArray().apply {
                sc.cues.forEach { put(JSONObject().put("type", it.type).put("at", it.at).put("text", it.text).put("icon", it.icon)) }
            })
            if (sc.transition.isNotEmpty()) o.put("transition", sc.transition)
            if (sc.shapes.isNotEmpty()) o.put("shapes", JSONArray().apply { sc.shapes.forEach { put(StudioKeys.toJson(it)) } })
            if (sc.broll.isNotEmpty()) o.put("broll", sc.broll)
            if (sc.brollMode.isNotEmpty()) o.put("brollMode", sc.brollMode)
            if (sc.media.isNotEmpty()) o.put("media", sc.media)
            if (sc.credit.isNotEmpty()) o.put("credit", sc.credit)
            if (sc.clip.isNotEmpty()) o.put("clip", sc.clip)
            if (sc.who.isNotEmpty()) o.put("who", sc.who)
            if (sc.mood.isNotEmpty()) o.put("mood", sc.mood)
            put(o)
        }
    }).toString()

    // ---------- saved analysis ----------

    /** Result of step 3, kept on disk so the screen survives the app being closed. */
    data class Analysis(
        val mode: String, val script: SScript, val words: List<SWord>, val ranges: List<IntRange>?,
        val transcript: String, val stats: VoiceStats,
        /** Parts of the original take kept after cutting pauses (null = nothing cut). */
        val cuts: List<Span>? = null
    )

    fun analysisToJson(a: Analysis): String = JSONObject()
        .put("mode", a.mode)
        .put("script", JSONObject(toJson(a.script)))
        .put("words", JSONArray().apply { a.words.forEach { put(JSONArray().put(it.text).put(it.startMs).put(it.endMs)) } })
        .put("ranges", a.ranges?.let { r -> JSONArray().apply { r.forEach { put(JSONArray().put(it.first).put(it.last + 1)) } } } ?: JSONObject.NULL)
        .put("transcript", a.transcript)
        .put("stats", JSONArray().put(a.stats.totalMs).put(a.stats.speechMs).put(a.stats.pauses).put(a.stats.longestPauseMs).put(a.stats.words))
        .put("cuts", a.cuts?.let { c -> JSONArray().apply { c.forEach { put(JSONArray().put(it.startMs).put(it.endMs)) } } } ?: JSONObject.NULL)
        .toString()

    fun analysisFromJson(s: String): Analysis? = try {
        val o = JSONObject(s)
        val script = parseScript(o.getJSONObject("script").toString()) ?: SScript("", emptyList())
        val wa = o.getJSONArray("words")
        val words = (0 until wa.length()).map { i -> wa.getJSONArray(i).let { SWord(it.getString(0), it.getLong(1), it.getLong(2)) } }
        val ranges = o.optJSONArray("ranges")?.let { ra -> (0 until ra.length()).map { i -> ra.getJSONArray(i).let { it.getInt(0) until it.getInt(1) } } }
        val st = o.getJSONArray("stats")
        val cuts = o.optJSONArray("cuts")?.let { ca -> (0 until ca.length()).map { i -> ca.getJSONArray(i).let { Span(it.getLong(0), it.getLong(1)) } } }
        Analysis(o.optString("mode"), script, words, ranges, o.optString("transcript"),
            VoiceStats(st.getLong(0), st.getLong(1), st.getInt(2), st.getLong(3), st.getInt(4)), cuts)
    } catch (e: Exception) {
        null
    }

    // ---------- script: editable plain text ----------
    //
    // One scene per block, blocks separated by a blank line:
    //   [icons] Spoken text of the scene
    //   🔍 research | 📚 context | ⚙️ process
    // The first line may start with [kind] or [kind: title]; a line with "|" (or starting with an emoji) lists tiles.

    /** "@Name(mood): line" — who says the line of a cartoon. */
    private val speaker = Regex("""^@([\p{L}\p{N}_-]{1,20})\s*(?:\(([a-zA-Z]+)\))?\s*:\s*(.*)$""")
    private val head = Regex("""^\[([a-zA-Z]+)\s*(?::\s*([^\]]*))?]\s*(.*)$""")

    fun parseText(text: String): SScript {
        val blocks = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        var title = ""
        val scenes = mutableListOf<SScene>()
        for (b in blocks) {
            val lines = b.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == 1 && lines[0].startsWith("# ")) {
                title = lines[0].removePrefix("# ").trim()
                continue
            }
            var kind = "text"
            var sceneTitle = ""
            var who = ""
            var mood = ""
            val say = StringBuilder()
            val items = mutableListOf<SItem>()
            lines.forEachIndexed { i, raw ->
                var line = raw
                if (i == 0) {
                    speaker.find(line)?.let { m ->
                        who = StudioToon.cleanName(m.groupValues[1])
                        mood = m.groupValues[2].lowercase().takeIf { it in StudioToon.MOODS } ?: ""
                        line = m.groupValues[3].trim()
                    }
                    head.find(line)?.let { m ->
                        val k = m.groupValues[1].lowercase()
                        if (k in KINDS) {
                            kind = k
                            sceneTitle = m.groupValues[2].trim()
                            line = m.groupValues[3].trim()
                        }
                    }
                }
                if (i > 0 && isItemLine(line)) {
                    items += line.split('|').map { itemOf(it) }.filter { it.icon.isNotEmpty() || it.label.isNotEmpty() }
                } else if (line.isNotEmpty()) {
                    if (say.isNotEmpty()) say.append(' ')
                    say.append(line)
                }
            }
            if (say.isNotBlank()) scenes += SScene(say.toString(), kind, sceneTitle, items.take(MAX_ITEMS), who = who, mood = mood)
        }
        return SScript(title, scenes)
    }

    fun toText(s: SScript): String = buildString {
        if (s.title.isNotBlank()) append("# ").append(s.title).append("\n\n")
        s.scenes.forEachIndexed { i, sc ->
            if (i > 0) append("\n\n")
            if (sc.who.isNotEmpty()) {
                append('@').append(sc.who)
                if (sc.mood.isNotEmpty()) append('(').append(sc.mood).append(')')
                append(": ")
            }
            if (sc.kind != "text" || sc.title.isNotBlank()) {
                append('[').append(sc.kind)
                if (sc.title.isNotBlank()) append(": ").append(sc.title)
                append("] ")
            }
            append(sc.say)
            if (sc.items.isNotEmpty()) append('\n').append(sc.items.joinToString(" | ") {
                (it.icon + " " + it.label).trim() + if (it.value.isNotEmpty()) " = " + it.value else ""
            })
        }
    }

    private fun isItemLine(line: String) = line.contains('|') || (line.isNotEmpty() && startsWithEmoji(line) && line.length <= 40)

    /** "🔍 research" → icon + label; a plain word becomes a label without an icon; "2024 = 55" sets the value of a chart bar. */
    fun itemOf(s: String): SItem {
        val eq = s.lastIndexOf('=')
        if (eq > 0) {
            val v = s.substring(eq + 1).trim()
            if (v.isNotEmpty() && v.length <= 12 && v.any { it.isDigit() }) return itemOf(s.substring(0, eq)).copy(value = v)
        }
        val t = s.trim()
        if (t.isEmpty()) return SItem("", "")
        if (!startsWithEmoji(t)) return SItem("", t)
        val sp = t.indexOf(' ')
        return if (sp < 0) SItem(t, "") else SItem(t.substring(0, sp), t.substring(sp + 1).trim())
    }

    fun startsWithEmoji(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        return cp >= 0x2190 && !Character.isLetterOrDigit(cp)
    }

    // ---------- words ----------

    /** Spoken words of a text: whitespace-separated pieces that contain a letter or a digit. */
    fun tokens(text: String): List<String> =
        text.split(Regex("\\s+")).map { it.trim() }.filter { w -> w.any { it.isLetterOrDigit() } }

    /** Letters and digits only, lower case: used to compare spoken and written words. */
    fun norm(w: String): String = w.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * WordBoundary events from an Edge "audio.metadata" frame body. Offsets are in 100 ns ticks
     * from the start of that request's audio; [shiftMs] moves them to the place of the piece in the whole track.
     */
    fun parseBoundaries(json: String, shiftMs: Long = 0): List<SWord> {
        val arr = try {
            JSONObject(json).optJSONArray("Metadata")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = mutableListOf<SWord>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("Type") != "WordBoundary") continue
            val d = m.optJSONObject("Data") ?: continue
            val text = d.optJSONObject("text")?.optString("Text").orEmpty().trim()
            if (text.isEmpty() || text.none { it.isLetterOrDigit() }) continue
            val start = d.optLong("Offset") / 10_000 + shiftMs
            val dur = max(1L, d.optLong("Duration") / 10_000)
            out += SWord(text, start, start + dur)
        }
        return out
    }

    /**
     * Splits [words] between scenes in proportion to the number of words each scene says.
     * When the texts match (Edge voice, or a recording of the same script) the split is exact.
     */
    fun assignScenes(wordCount: Int, sceneWordCounts: List<Int>): List<IntRange> {
        if (sceneWordCounts.isEmpty()) return emptyList()
        val total = sceneWordCounts.sum()
        val out = ArrayList<IntRange>(sceneWordCounts.size)
        var cum = 0
        var prevEnd = 0
        sceneWordCounts.forEachIndexed { i, n ->
            cum += n
            val end = if (i == sceneWordCounts.lastIndex) wordCount
            else if (total == 0) prevEnd else (cum.toLong() * wordCount / total).toInt().coerceIn(prevEnd, wordCount)
            out += prevEnd until end
            prevEnd = end
        }
        return out
    }

    /**
     * Scene times. A scene starts a little before its first word (or where the previous one ended
     * when it has no words) and lasts until the next scene starts; the last one until [totalMs].
     */
    fun sceneTimes(words: List<SWord>, ranges: List<IntRange>, totalMs: Long, leadMs: Long = 120): List<SceneTime> {
        val starts = LongArray(ranges.size)
        var last = 0L
        ranges.forEachIndexed { i, r ->
            val s = if (r.isEmpty() || r.first >= words.size) last else max(last, words[r.first].startMs - leadMs)
            starts[i] = if (i == 0) 0L else s.coerceAtLeast(0)
            if (!r.isEmpty() && r.last < words.size) last = words[r.last].endMs
        }
        return ranges.indices.map { i ->
            val end = if (i == ranges.lastIndex) max(totalMs, starts[i] + 1) else max(starts[i + 1], starts[i] + 1)
            SceneTime(i, starts[i], end, ranges[i])
        }
    }

    /**
     * Caption groups inside a scene: up to [maxWords] words and [maxChars] letters on screen at once;
     * a pause longer than [gapMs] or a sentence end starts a new group. Returns index ranges into [words].
     */
    fun captions(words: List<SWord>, range: IntRange, maxWords: Int = 4, maxChars: Int = 26, gapMs: Long = 650): List<IntRange> {
        if (range.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = range.first
        var chars = 0
        for (i in range) {
            val w = words[i]
            val n = i - start
            val gap = if (i > start) w.startMs - words[i - 1].endMs else 0
            val sentence = i > start && words[i - 1].text.trimEnd().lastOrNull()?.let { it in ".!?…" } == true
            if (n > 0 && (n >= maxWords || chars + 1 + w.text.length > maxChars || gap > gapMs || sentence)) {
                out += start until i
                start = i
                chars = 0
            }
            chars += (if (chars > 0) 1 else 0) + w.text.length
        }
        out += start..range.last
        return out
    }

    /** Written words of the script with punctuation, matched to the spoken ones (Edge drops punctuation). */
    fun dress(spoken: List<SWord>, written: List<String>): List<SWord> {
        var j = 0
        return spoken.map { w ->
            val n = norm(w.text)
            var k = j
            while (k < written.size && k < j + 3 && norm(written[k]) != n) k++
            if (k < written.size && norm(written[k]) == n) {
                j = k + 1
                w.copy(text = written[k])
            } else w
        }
    }

    // ---------- animation ----------

    /** 0 → 1 over [durMs] from [startMs], ease-out cubic. */
    fun appear(tMs: Long, startMs: Long, durMs: Long = 280): Float {
        if (tMs <= startMs) return 0f
        if (tMs >= startMs + durMs) return 1f
        val x = (tMs - startMs).toFloat() / durMs
        val r = 1 - x
        return 1 - r * r * r
    }

    /** When tile [i] of [n] shows up in a scene lasting [sceneMs]: spread over the first 60 %, at most 450 ms apart. */
    fun itemDelay(i: Int, n: Int, sceneMs: Long): Long {
        if (n <= 1) return 150
        val step = min(450L, max(80L, sceneMs * 6 / 10 / n))
        return 150 + i * step
    }

    // ---------- own voice: analysis ----------

    /** RMS loudness per [frameMs] frame, 0..1. */
    fun frameRms(pcm: ShortArray, rate: Int, frameMs: Int = 20): FloatArray {
        val n = max(1, rate * frameMs / 1000)
        val frames = (pcm.size + n - 1) / n
        return FloatArray(frames) { f ->
            var sum = 0.0
            val from = f * n
            val to = min(pcm.size, from + n)
            for (i in from until to) {
                val v = pcm[i] / 32768.0
                sum += v * v
            }
            sqrt(sum / max(1, to - from)).toFloat()
        }
    }

    /**
     * Speech parts of a recording (simple voice activity detection): the threshold follows the noise floor
     * and the peak of the take; pauses shorter than [minPauseMs] are bridged, blips under [minSpeechMs] dropped.
     */
    fun speechSpans(pcm: ShortArray, rate: Int, minPauseMs: Int = 280, minSpeechMs: Int = 120, frameMs: Int = 20): List<Span> {
        val rms = frameRms(pcm, rate, frameMs)
        if (rms.isEmpty()) return emptyList()
        val sorted = rms.sortedArray()
        val floor = sorted[(sorted.size * 0.15).toInt().coerceAtMost(sorted.lastIndex)]
        val peak = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.lastIndex)]
        val thr = max(max(floor * 2.5f, floor + (peak - floor) * 0.12f), 0.006f)
        val raw = mutableListOf<Span>()
        var start = -1
        for (f in rms.indices) {
            val on = rms[f] >= thr
            if (on && start < 0) start = f
            if (!on && start >= 0) {
                raw += Span(start.toLong() * frameMs, f.toLong() * frameMs)
                start = -1
            }
        }
        if (start >= 0) raw += Span(start.toLong() * frameMs, rms.size.toLong() * frameMs)
        val merged = mutableListOf<Span>()
        for (s in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && s.startMs - prev.endMs < minPauseMs) merged[merged.lastIndex] = Span(prev.startMs, s.endMs)
            else merged += s
        }
        val totalMs = pcm.size * 1000L / rate
        return merged.filter { it.ms >= minSpeechMs }.map { Span(it.startMs, min(it.endMs, totalMs)) }
    }

    /**
     * Places written words on the speech parts of a recording: speaking time is shared between words
     * in proportion to their length, so no word lands in a pause. Good enough for captions without a recogniser.
     */
    fun alignWords(words: List<String>, speech: List<Span>): List<SWord> {
        if (words.isEmpty() || speech.isEmpty()) return emptyList()
        val weights = words.map { max(2, norm(it).length) + 1 }
        val total = weights.sum().toDouble()
        val speechMs = speech.sumOf { it.ms }.toDouble()
        // cumulative position (0..1 of all letters) → time on the speech parts
        fun at(p: Double, preferNext: Boolean): Long {
            var left = p * speechMs
            for ((i, s) in speech.withIndex()) {
                val last = i == speech.lastIndex
                if (left < s.ms || (left <= s.ms && (!preferNext || last))) return s.startMs + left.roundToInt()
                left -= s.ms
            }
            return speech.last().endMs
        }
        var cum = 0
        return words.mapIndexed { i, w ->
            val a = at(cum / total, true)
            cum += weights[i]
            val b = at(cum / total, false)
            SWord(w, a, max(a + 1, b))
        }
    }

    fun stats(totalMs: Long, speech: List<Span>, words: Int): VoiceStats {
        val gaps = speech.zipWithNext { a, b -> b.startMs - a.endMs }
        return VoiceStats(totalMs, speech.sumOf { it.ms }, gaps.size, gaps.maxOrNull() ?: 0, words)
    }

    /** Joins neighbouring speech parts into pieces of at most [maxMs] (for a recogniser with a length limit). */
    fun groupSpans(speech: List<Span>, maxMs: Long = 12_000): List<Span> {
        val out = mutableListOf<Span>()
        for (s in speech) {
            val prev = out.lastOrNull()
            if (prev != null && s.endMs - prev.startMs <= maxMs) out[out.lastIndex] = Span(prev.startMs, s.endMs)
            else out += s
        }
        return out
    }

    /**
     * Words of a recognised recording: every piece's text is placed on the speech inside that piece,
     * so a recogniser error in one piece does not shift the rest.
     */
    fun alignPieces(pieces: List<Pair<Span, String>>, speech: List<Span>): List<SWord> = pieces.flatMap { (piece, text) ->
        val inside = speech.mapNotNull { s ->
            val a = max(s.startMs, piece.startMs)
            val b = min(s.endMs, piece.endMs)
            if (b > a) Span(a, b) else null
        }.ifEmpty { listOf(piece) }
        alignWords(tokens(text), inside)
    }

    /**
     * Beats without text for a take with no script that could not be recognised: the speech (or the whole
     * take when no speech is heard) is cut into pieces of about [stepMs], so the no-AI templates still work.
     */
    fun beatWords(speech: List<Span>, totalMs: Long, stepMs: Long = 1200): List<SWord> {
        val parts = speech.filter { it.ms > 0 }.ifEmpty { if (totalMs > 0) listOf(Span(0, totalMs)) else emptyList() }
        return parts.flatMap { s ->
            val n = max(1L, (s.ms + stepMs / 2) / stepMs).toInt()
            (0 until n).map { k ->
                val a = s.startMs + s.ms * k / n
                SWord("", a, max(a + 1, s.startMs + s.ms * (k + 1) / n))
            }
        }
    }

    /**
     * Scenes from a recognised take without AI: a new scene after a long pause, a sentence end
     * once the scene has some words, or at [maxWords].
     */
    fun autoScenes(words: List<SWord>, maxWords: Int = 14, pauseMs: Long = 700): SScript {
        val scenes = mutableListOf<SScene>()
        var cur = mutableListOf<String>()
        words.forEachIndexed { i, w ->
            val gap = if (i > 0) w.startMs - words[i - 1].endMs else 0
            val sentence = cur.size >= 5 && cur.last().trimEnd().lastOrNull()?.let { it in ".!?…" } == true
            if (cur.isNotEmpty() && (gap > pauseMs || sentence || cur.size >= maxWords)) {
                scenes += SScene(cur.joinToString(" "))
                cur = mutableListOf()
            }
            cur += w.text
        }
        if (cur.isNotEmpty()) scenes += SScene(cur.joinToString(" "))
        return SScript("", scenes)
    }

    // ---------- drawing helpers ----------

    /** Index of the scene shown at [tMs]. */
    fun sceneAt(times: List<SceneTime>, tMs: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0
        var hi = times.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid].startMs <= tMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Number shown on the badge of each "step" scene (1, 2, 3…); 0 for other scenes. */
    fun stepNumbers(scenes: List<SScene>): IntArray {
        var n = 0
        return IntArray(scenes.size) { if (scenes[it].kind == "step") ++n else 0 }
    }

    /** Greedy line breaking: [widths] of words, [space] between them → word index ranges per line. */
    fun lines(widths: List<Float>, space: Float, maxWidth: Float): List<IntRange> {
        if (widths.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 0
        var w = widths[0]
        for (i in 1 until widths.size) {
            if (w + space + widths[i] > maxWidth) {
                out += start until i
                start = i
                w = widths[i]
            } else {
                w += space + widths[i]
            }
        }
        out += start until widths.size
        return out
    }

    private val number = Regex("""\d+(?:[.,]\d+)?""")

    /** "80%" counted up: at [p] = 0.5 gives "40%". Text without a number is returned as is. */
    fun countUp(s: String, p: Float): String {
        val m = number.find(s) ?: return s
        val raw = m.value.replace(',', '.')
        val v = raw.toDoubleOrNull() ?: return s
        val k = p.coerceIn(0f, 1f)
        val decimals = raw.substringAfter('.', "").length
        val cur = v * k
        val txt = if (decimals == 0) cur.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.${decimals}f", cur).let { if (m.value.contains(',')) it.replace('.', ',') else it }
        return s.substring(0, m.range.first) + txt + s.substring(m.range.last + 1)
    }

    // ---------- audio ----------

    fun durationMs(samples: Int, rate: Int): Long = if (rate <= 0) 0 else samples * 1000L / rate

    /** Interleaved PCM → mono. */
    fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val n = pcm.size / channels
        return ShortArray(n) { i ->
            var s = 0
            for (c in 0 until channels) s += pcm[i * channels + c]
            (s / channels).toShort()
        }
    }

    /** Linear resampling (speech quality is enough for a voice-over). */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val n = max(1, (pcm.size.toLong() * to / from).toInt())
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(pcm.lastIndex)
            val b = min(a + 1, pcm.lastIndex)
            val f = pos - a
            (pcm[a] * (1 - f) + pcm[b] * f).roundToInt().toShort()
        }
    }

    /** Scales the take so its loudest part reaches [peak] of full scale (quiet phone recordings). Never boosts more than ×8. */
    fun normalize(pcm: ShortArray, peak: Double = 0.89): ShortArray {
        var m = 0
        for (s in pcm) m = max(m, abs(s.toInt()))
        if (m == 0) return pcm
        val k = min(8.0, peak * 32767 / m)
        if (abs(k - 1) < 0.02) return pcm
        return ShortArray(pcm.size) { (pcm[it] * k).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun silence(ms: Long, rate: Int) = ShortArray((ms * rate / 1000).toInt())

    /** 44-byte header of a 16-bit PCM WAV file. */
    fun wavHeader(dataBytes: Int, rate: Int, channels: Int = 1): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    /** Mono PCM and sample rate of a 16-bit WAV written by [wavHeader]; null for other files. */
    fun readWav(bytes: ByteArray): Pair<ShortArray, Int>? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        if (b.getShort(34).toInt() != 16) return null
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (id == "data") {
                val n = min(len, bytes.size - p - 8) / 2
                val pcm = ShortArray(n) { b.getShort(p + 8 + it * 2) }
                return downmix(pcm, channels) to rate
            }
            p += 8 + len + (len and 1)
        }
        return null
    }

    // ---------- video ----------

    /**
     * ARGB pixels → planar YUV 4:2:0 (BT.601, limited range), as video encoders expect.
     * [y] holds w×h bytes, [u] and [v] (w/2)×(h/2) each.
     */
    fun argbToYuv420(argb: IntArray, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        val cw = w / 2
        for (j in 0 until h) {
            val row = j * w
            for (i in 0 until w) {
                val c = argb[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                y[row + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
        }
        for (j in 0 until h / 2) {
            for (i in 0 until cw) {
                // average of the 2×2 block
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(j * 2 + dy) * w + i * 2 + dx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                u[j * cw + i] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[j * cw + i] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
    }

    /** One YUV pixel (BT.601, limited range) → opaque ARGB. */
    fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = 298 * (y - 16)
        val d = u - 128
        val e = v - 128
        val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Centre crop of a [dispW]×[dispH] picture into [dstW]×[dstH] (the picture fills the frame, edges are cut):
     * for every destination column / row, the column / row of the picture it shows.
     */
    fun cropAxes(dstW: Int, dstH: Int, dispW: Int, dispH: Int): Pair<IntArray, IntArray> {
        val s = max(dstW.toDouble() / dispW, dstH.toDouble() / dispH)
        val xs = IntArray(dstW) { x -> ((x + 0.5 - dstW / 2.0) / s + dispW / 2.0).toInt().coerceIn(0, dispW - 1) }
        val ys = IntArray(dstH) { y -> ((y + 0.5 - dstH / 2.0) / s + dispH / 2.0).toInt().coerceIn(0, dispH - 1) }
        return xs to ys
    }

    /** Size of a decoded [rawW]×[rawH] frame once turned by [rotation] degrees for display. */
    fun displaySize(rawW: Int, rawH: Int, rotation: Int): Pair<Int, Int> =
        if (rotation % 180 == 0) rawW to rawH else rawH to rawW

    /** Column in the decoded frame for the displayed point (u, v); the frame is turned clockwise by [rotation]. */
    fun rawX(u: Int, v: Int, rotation: Int, rawW: Int): Int = when (rotation) {
        90 -> v
        180 -> rawW - 1 - u
        270 -> rawW - 1 - v
        else -> u
    }

    /** Row in the decoded frame for the displayed point (u, v). */
    fun rawY(u: Int, v: Int, rotation: Int, rawH: Int): Int = when (rotation) {
        90 -> rawH - 1 - u
        180 -> rawH - 1 - v
        270 -> u
        else -> v
    }

    // ---------- auto-edit: jump cuts, camera, background clips ----------

    /**
     * Parts of a take to keep when pauses are cut out ("jump cuts"): speech with [padMs] of air around it;
     * pauses shorter than [minGapMs] stay, so the speech keeps its rhythm.
     */
    fun keepSegments(speech: List<Span>, totalMs: Long, padMs: Long = 120, minGapMs: Long = 350): List<Span> {
        val out = mutableListOf<Span>()
        for (s in speech) {
            val a = max(0L, s.startMs - padMs)
            val b = min(totalMs, s.endMs + padMs)
            val prev = out.lastOrNull()
            if (prev != null && a - prev.endMs < minGapMs) out[out.lastIndex] = Span(prev.startMs, max(prev.endMs, b))
            else out += Span(a, b)
        }
        return out
    }

    /** The take with only [segments] left, glued together. */
    fun cutPcm(pcm: ShortArray, rate: Int, segments: List<Span>): ShortArray {
        val parts = segments.map { s ->
            val a = (s.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
            val b = (s.endMs * rate / 1000).toInt().coerceIn(a, pcm.size)
            a to b
        }
        val out = ShortArray(parts.sumOf { it.second - it.first })
        var p = 0
        for ((a, b) in parts) {
            pcm.copyInto(out, p, a, b)
            p += b - a
        }
        return out
    }

    /** Time in the original take for time [outMs] of the cut video (past the end: the end of the last part). */
    fun sourceTime(outMs: Long, segments: List<Span>?): Long {
        if (segments.isNullOrEmpty()) return outMs
        var left = outMs
        for (s in segments) {
            if (left < s.ms) return s.startMs + left
            left -= s.ms
        }
        return segments.last().endMs
    }

    /** Which background clip plays at [tMs] and from where: clips go one after another and repeat. */
    fun clipAt(tMs: Long, durationsMs: List<Long>): Pair<Int, Long> {
        val total = durationsMs.sum()
        if (durationsMs.isEmpty() || total <= 0) return 0 to 0L
        var left = ((tMs % total) + total) % total
        durationsMs.forEachIndexed { i, d ->
            if (left < d) return i to left
            left -= d
        }
        return durationsMs.lastIndex to durationsMs.last() - 1
    }

    /**
     * Camera zoom over the speaker in scene [scene]: alternating framings (wide, punch-in, medium),
     * a slow push-in during the scene and a short bounce when the scene starts.
     */
    fun cameraZoom(scene: Int, localMs: Long, sceneMs: Long): Float {
        val base = when (scene % 4) {
            0 -> 1f
            1 -> 1.16f
            2 -> 1.06f
            else -> 1.11f
        }
        val pushMax = when (scene % 4) {
            0 -> 0.05f
            2 -> 0.07f
            else -> 0.03f
        }
        val p = if (sceneMs <= 0) 0f else (localMs.toFloat() / sceneMs).coerceIn(0f, 1f)
        val bounce = if (scene == 0) 0f else 0.035f * (1 - appear(localMs, 0, 240))
        return base + pushMax * p + bounce
    }

    // ---------- music ----------

    /** Speaking parts from word timings: words closer than [joinMs] form one part. */
    fun spansOfWords(words: List<SWord>, joinMs: Long = 400): List<Span> {
        val out = mutableListOf<Span>()
        for (w in words) {
            val prev = out.lastOrNull()
            if (prev != null && w.startMs - prev.endMs <= joinMs) out[out.lastIndex] = Span(prev.startMs, max(prev.endMs, w.endMs))
            else out += Span(w.startMs, w.endMs)
        }
        return out
    }

    /**
     * Music gain per 10 ms block: [duck] while someone speaks (with a little lead and tail), 1 in pauses,
     * smoothed so the music glides down and up instead of jumping.
     */
    fun duckEnvelope(totalMs: Long, speech: List<Span>, duck: Float = 0.35f, leadMs: Long = 150, tailMs: Long = 300, smoothMs: Long = 250): FloatArray {
        val n = (totalMs / 10).toInt() + 1
        val target = FloatArray(n) { 1f }
        for (s in speech) {
            val a = ((s.startMs - leadMs) / 10).toInt().coerceIn(0, n - 1)
            val b = ((s.endMs + tailMs) / 10).toInt().coerceIn(0, n - 1)
            for (i in a..b) target[i] = duck
        }
        val k = max(1, (smoothMs / 10).toInt())
        val out = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += target[i]
            if (i >= k) sum -= target[i - k]
            out[i] = (sum / min(i + 1, k)).toFloat()
        }
        return out
    }

    /**
     * Voice + background music → one track as long as [voice]. The music loops when shorter, fades in and out,
     * plays at [volume] (0..1) and is ducked under speech when [speech] is given.
     */
    fun mixMusic(
        voice: ShortArray, music: ShortArray, rate: Int, volume: Float, speech: List<Span>,
        duck: Float = 0.35f, fadeInMs: Long = 800, fadeOutMs: Long = 1500
    ): ShortArray {
        if (music.isEmpty() || volume <= 0f) return voice
        val totalMs = durationMs(voice.size, rate)
        val env = if (speech.isEmpty()) null else duckEnvelope(totalMs, speech, duck)
        val fadeIn = max(1L, fadeInMs * rate / 1000)
        val fadeOut = max(1L, fadeOutMs * rate / 1000)
        return ShortArray(voice.size) { i ->
            var g = volume
            if (i < fadeIn) g *= i.toFloat() / fadeIn
            val left = voice.size - i
            if (left < fadeOut) g *= left.toFloat() / fadeOut
            if (env != null) g *= env[min(env.lastIndex, (i.toLong() * 100 / rate).toInt())]
            val m = music[i % music.size] * g
            (voice[i] + m).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** [pcm] padded with silence (or cut) to [samples]. */
    fun fit(pcm: ShortArray, samples: Int): ShortArray = if (pcm.size == samples) pcm else pcm.copyOf(samples)

    /** Frames needed for [ms] at [fps] (at least one). */
    fun frameCount(ms: Long, fps: Int): Int = max(1, ((ms * fps + 999) / 1000).toInt())

    fun frameTimeUs(i: Int, fps: Int): Long = i * 1_000_000L / fps

    /** Video bitrate for the size: ~0.1 bit per pixel per frame, at least 2 Mbit/s. */
    fun bitrate(w: Int, h: Int, fps: Int): Int = max(2_000_000, (w.toLong() * h * fps / 10).toInt())

    /** Rough script length: 2.4 spoken words per second. */
    fun wordsFor(seconds: Int): Int = (seconds * 2.4).roundToInt()

    /** "1:05" */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    fun fileName(title: String, day: String): String {
        val base = title.trim().replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_').take(40).ifBlank { "video" }
        return "Lumen_Studio_${base}_$day.mp4"
    }
}

/** A track found in a free music catalog. [url] is the audio file, [landing] the page to credit. */
data class MusicHit(
    val id: String, val title: String, val creator: String, val url: String, val durationMs: Long,
    val license: String, val licenseVersion: String, val licenseUrl: String, val landing: String, val source: String
)

/** Free background music catalogs (official APIs): parsing, licences and credits. Pure, unit-tested. */
object StudioMusicCatalog {
    /** Search moods: label → English query (the catalogs are mostly English). */
    val MOODS = listOf(
        "спокойная" to "calm ambient", "энергичная" to "upbeat energetic", "вдохновляющая" to "inspiring",
        "lo-fi" to "lofi chill", "корпоративная" to "corporate", "эпическая" to "epic cinematic",
        "весёлая" to "happy", "электроника" to "electronic", "пианино" to "piano", "акустика" to "acoustic guitar"
    )

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Openverse (WordPress): no key; CC music from Jamendo, Freesound, ccMixter, Wikimedia and others. */
    fun openverseUrl(query: String, commercial: Boolean, page: Int = 1): String =
        "https://api.openverse.org/v1/audio/?q=${enc(query)}&category=music&page_size=20&page=$page" +
            (if (commercial) "&license_type=commercial" else "")

    /** Freesound API v2 (free key from freesound.org/apiv2/apply). */
    fun freesoundUrl(query: String, token: String, minSec: Int = 20, maxSec: Int = 600): String =
        "https://freesound.org/apiv2/search/text/?query=${enc(query)}" +
            "&filter=${enc("duration:[$minSec TO $maxSec]")}&sort=rating_desc&page_size=20" +
            "&fields=${enc("id,name,duration,previews,username,license,url")}&token=${enc(token)}"

    fun parseOpenverse(json: String): List<MusicHit> {
        val arr = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            MusicHit(
                o.optString("id"), o.optString("title").ifBlank { "—" }, o.optString("creator"), url,
                o.optLong("duration", 0), licenseCode(o.optString("license")), o.optString("license_version"),
                o.optString("license_url"), o.optString("foreign_landing_url"), "openverse/" + o.optString("source")
            )
        }
    }

    fun parseFreesound(json: String): List<MusicHit> {
        val arr = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optJSONObject("previews") ?: return@mapNotNull null
            val url = p.optString("preview-hq-mp3").ifBlank { p.optString("preview-lq-mp3") }
            if (!url.startsWith("http")) return@mapNotNull null
            val lic = o.optString("license")
            MusicHit(
                o.optString("id"), o.optString("name").ifBlank { "—" }, o.optString("username"), url,
                (o.optDouble("duration", 0.0) * 1000).toLong(), licenseCode(lic), versionOf(lic), lic.takeIf { it.startsWith("http") } ?: "",
                o.optString("url"), "freesound"
            )
        }
    }

    /** Normalised Creative Commons code: "cc0", "by", "by-sa", "by-nc", … ("" when unknown). */
    fun licenseCode(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return ""
        if (s.contains("publicdomain/zero") || s == "cc0" || s.contains("creative commons 0")) return "cc0"
        if (s.contains("publicdomain/mark") || s == "pdm") return "pdm"
        Regex("""licenses/([a-z-]+)/""").find(s)?.let { return it.groupValues[1] }
        if (s.startsWith("attribution")) {
            val nc = s.contains("noncommercial")
            val nd = s.contains("noderiv")
            val sa = s.contains("sharealike")
            return "by" + (if (nc) "-nc" else "") + (if (nd) "-nd" else "") + (if (sa) "-sa" else "")
        }
        return s.replace(' ', '-')
    }

    private fun versionOf(raw: String): String = Regex("""/(\d\.\d)/?""").find(raw)?.groupValues?.get(1) ?: ""

    /** Allowed in a monetised video: public domain, CC0, CC BY, CC BY-SA. Jamendo needs its own commercial licence. */
    fun commercialOk(h: MusicHit): Boolean =
        h.license in setOf("cc0", "pdm", "by", "by-sa") && !h.source.endsWith("/jamendo")

    /** Credit is required for every CC licence except CC0 and the public domain mark. */
    fun needsCredit(h: MusicHit): Boolean = h.license !in setOf("cc0", "pdm")

    fun licenseLabel(h: MusicHit): String = when (h.license) {
        "cc0" -> "CC0"
        "pdm" -> "Public Domain"
        "" -> "?"
        else -> "CC " + h.license.uppercase() + (if (h.licenseVersion.isNotBlank()) " " + h.licenseVersion else "")
    }

    /** Text for the video description: "Music: Title by Creator (CC BY 4.0) https://…". */
    fun credit(h: MusicHit): String = buildString {
        append("Music: \"").append(h.title).append('"')
        if (h.creator.isNotBlank()) append(" by ").append(h.creator)
        append(" (").append(licenseLabel(h)).append(')')
        val link = h.landing.ifBlank { h.licenseUrl }
        if (link.isNotBlank()) append(' ').append(link)
    }
}

/** Editable prompts of Lumen Studio (added to [Prompts.defaults]). */
val STUDIO_PROMPTS = linkedMapOf(
    "studio_script" to (
        "Ты сценарист коротких вертикальных видео-объяснялок (Shorts, Reels) в стиле анимированной инфографики: " +
            "крупные слова на тёмном фоне появляются под голос, рядом простые схемы из эмодзи-плиток.\n" +
            "Тема: \"{topic}\". Длительность около {seconds} секунд (примерно {words} слов). Язык: {lang}.\n{extra}\n" +
            "Разбей текст на 8-25 сцен, в сцене 1-2 коротких предложения (до 18 слов). Пиши разговорно, без канцелярита.\n" +
            "{visuals}\n" +
            "Первая сцена — цепляющий вопрос или обещание пользы. Последняя — вывод или призыв.\n" +
            "Верни строго JSON: {\"title\":\"...\",\"scenes\":[{\"say\":\"текст для озвучки\",\"kind\":\"icons\",\"title\":\"\"," +
            "\"items\":[{\"icon\":\"🔍\",\"label\":\"исследование\"}]}]}"
        ),
    "studio_script_video" to (
        "Ты сценарист горизонтальных видео для YouTube (16:9) в стиле объясняющей моушн-графики: " +
            "крупные слова и простые схемы из эмодзи-плиток, диаграммы и цитаты появляются под голос.\n" +
            "Тема: \"{topic}\". Длительность около {seconds} секунд (примерно {words} слов). Язык: {lang}.\n{extra}\n" +
            "Структура: хук в первых 10 секундах (вопрос, неожиданный факт или обещание результата), короткое вступление, " +
            "3-6 глав (каждая начинается сценой step с названием главы), в главах факты, примеры и сравнения, " +
            "в конце вывод и призыв подписаться или посмотреть следующее видео. Держи интерес: открытые вопросы, «а дальше — самое интересное».\n" +
            "Разбей текст на сцены по 1-2 предложения (до 25 слов). Пиши разговорно, как живой автор, без канцелярита.\n" +
            "{visuals}\n" +
            "Верни строго JSON: {\"title\":\"...\",\"scenes\":[{\"say\":\"текст для озвучки\",\"kind\":\"step\",\"title\":\"Глава 1\"," +
            "\"items\":[{\"icon\":\"🔍\",\"label\":\"исследование\",\"value\":\"\"}]}]}"
        ),
    "studio_script_cartoon" to (
        "Ты сценарист познавательных мультфильмов-объяснялок: нарисованные персонажи разговаривают друг с другом, " +
            "а рядом с ними появляются простые схемы из эмодзи, цифры и диаграммы.\n" +
            "Персонажи: {cast}.\n" +
            "Если у персонажа указан характер, пиши его реплики в этом характере и манере речи.\n" +
            "Тема: \"{topic}\". Длительность около {seconds} секунд (примерно {words} слов). Язык: {lang}.\n{extra}\n" +
            "Каждая сцена — одна реплика одного персонажа: who — имя точно из списка (пусто — голос рассказчика, редко, например в начале или в конце). " +
            "Пиши живой диалог с характерами: один герой спрашивает, сомневается, ошибается или удивляется, другой объясняет на простых примерах из жизни; " +
            "лёгкий юмор, короткие фразы, без лекций и канцелярита. Факты должны быть верными. " +
            "Реплика 1-2 предложения (до 20 слов), персонажи чередуются.\n" +
            "mood — эмоция реплики: calm, happy, explain (объясняет и показывает на схему), think (задумался), surprise, sad, angry.\n" +
            "{visuals}\n" +
            "Первая сцена — цепляющий вопрос или забавная ситуация, последняя — вывод одной фразой.\n" +
            "Верни строго JSON: {\"title\":\"...\",\"scenes\":[{\"who\":\"Имя\",\"mood\":\"surprise\",\"say\":\"текст реплики\",\"kind\":\"big\",\"title\":\"\"," +
            "\"items\":[{\"icon\":\"🔍\",\"label\":\"подпись\"}]}]}"
        ),
    "studio_char" to (
        "Ты художник персонажей для познавательных мультфильмов Lumen Studio. Герой собирается из готовых деталей " +
            "(тело, уши, волосы, глаза, нос, хвост, аксессуары, цвета, пропорции), потом он говорит, моргает и жестикулирует.\n" +
            "{mode}\n" +
            "Пожелания автора: {desc}\n" +
            "Сейчас герой такой: {current}\n" +
            "Имя и характер — на языке {lang}; имя одним словом. Детали сочетай так, чтобы герой был цельным и узнаваемым: " +
            "животному — мордочку, нос и хвост по виду, человеку — front clothes, волосы и цвет кожи в main. Яркие, но не кислотные цвета.\n\n" +
            "{schema}\n\n" +
            "Верни строго один JSON-объект по протоколу lumen.char v1, только допустимые значения."
        ),
    "studio_cuts_filter" to (
        "Ты помогаешь подобрать видео YouTube для вставок в ролик (B-roll и мемы-реакции). " +
            "Нужно: {want}\n" +
            "Ниже найденные видео: номер, название, канал, длительность. Выбери только те, что по названию подходят под запрос; " +
            "если сомневаешься — не бери. note — коротко, что, скорее всего, в кадре (до 8 слов, язык: {lang}).\n" +
            "Верни строго JSON: {\"picks\":[{\"n\":1,\"note\":\"...\"}]}\n\n" +
            "Видео:\n{hits}"
        ),
    "studio_script_edit" to (
        "Ты редактор сценариев коротких видео-объяснялок. Ниже текущий сценарий {format} в JSON. Язык: {lang}.\n" +
            "Просьба автора: {ask}\n" +
            "Выполни просьбу и верни сценарий целиком. Сохраняй всё, о чём не просили: тему, порядок, удачные фразы, поля сцен. " +
            "Сцена — 1-2 предложения, пиши разговорно, факты должны быть верными.\n{cast}\n" +
            "{visuals}\n" +
            "Верни строго JSON того же вида: {\"title\":\"...\",\"notes\":[\"что изменено, 1-5 коротких пунктов\"],\"scenes\":[{\"say\":\"...\",\"kind\":\"text\",\"title\":\"\",\"items\":[]}]}\n\n" +
            "Сценарий:\n{script}"
        ),
    "studio_direct" to (
        "Ты режиссёр монтажа {format}. Ниже расшифровка озвучки: каждое слово с номером [N], ⏸ — пауза в секундах.\n" +
            "Сделай план ролика, чтобы его было интересно смотреть до конца.\n" +
            "1. Раздели ВСЮ речь на сцены по смыслу, по порядку, без пропусков: from и to — номера первого и последнего слова сцены. " +
            "Сцена — 1-2 предложения, обычно 3-8 секунд{long}.\n" +
            "2. {visuals}\n" +
            "3. Режиссура сцены:\n" +
            "emph — 1-2 ключевых слова сцены точно как в тексте: они подсветятся в субтитрах;\n" +
            "transition — как сцена входит: fade (спокойно), slide (следующий пункт), zoom (акцент, раскрытие), whip (резкая смена темы), none (без перехода);\n" +
            "cues — события на номере слова at: " +
            "zoom — резкий наезд камеры на ударном слове (не чаще раза в 3-4 секунды); " +
            "pop — всплывающая плашка: text до 3 слов и icon-эмодзи («ВАЖНО», «×3», «ОШИБКА №1»), не больше 1-2 на сцену; " +
            "sfx — звук name: whoosh (смена темы), pop (появление), click (пункт списка), ding (вывод, верный ответ), boom (сильный акцент), riser (нарастание перед раскрытием; at — слово кульминации).\n" +
            "broll — только для сцен, где реальное фото лучше схемы (место, предмет, люди, событие): английский запрос для поиска стокового фото, 2-4 конкретных слова (\"night city traffic\"); " +
            "brollMode — full (фото на весь кадр, поверх только субтитры) или card (фото в карточке вместо схемы). Не больше трети сцен; для чисел, списков и процессов — графика, а не фото;\n" +
            "shapes — 0-2 анимированные фигуры там, где нужно показать, связать или выделить: el — arrow, line, circle, ring, rect, underline, check, cross, spotlight (затемнение всего кроме круга), text, emoji; " +
            "w, h — размер в долях короткой стороны кадра (для text и emoji h — размер шрифта, около 0.08); color — accent, white, ink, red, green, yellow; fill — заливка; " +
            "keys — ключевые кадры по порядку: at — номер слова, ms — сдвиг от начала слова, x и y — центр в долях кадра (0..1), s — масштаб, r — поворот в градусах, o — прозрачность 0..1, " +
            "draw — доля прорисовки линии 0..1, ease — linear, in, out, inOut, back, spring; пропущенные значения берутся из прошлого кадра. " +
            "Не ставь фигуры на субтитры внизу кадра (y больше 0.72). Пример — стрелка прорисовывается к цифре: " +
            "{\"el\":\"arrow\",\"w\":0.35,\"color\":\"yellow\",\"keys\":[{\"at\":5,\"x\":0.3,\"y\":0.62,\"r\":-35,\"draw\":0},{\"at\":5,\"ms\":450,\"draw\":1,\"ease\":\"out\"}]}; " +
            "кольцо выскакивает: {\"el\":\"ring\",\"w\":0.4,\"h\":0.4,\"keys\":[{\"at\":9,\"x\":0.5,\"y\":0.5,\"s\":0,\"draw\":0},{\"at\":9,\"ms\":400,\"s\":1,\"draw\":1,\"ease\":\"back\"}]}.\n" +
            "Первая сцена — хук: сильный визуал, zoom или pop. Чередуй визуалы и переходы. Не перегружай: 0-3 события на сцену, тихие сцены тоже нужны.\n" +
            "4. {drop}\n" +
            "Подписи и плашки на языке {lang}.\n" +
            "Верни строго JSON: {\"title\":\"короткое название\",\"drop\":[],\"scenes\":[{\"from\":0,\"to\":17,\"kind\":\"stat\",\"title\":\"80%\"," +
            "\"items\":[{\"icon\":\"📉\",\"label\":\"досмотров\",\"value\":\"\"}],\"emph\":[\"80%\"],\"transition\":\"zoom\"," +
            "\"cues\":[{\"at\":3,\"do\":\"zoom\"},{\"at\":9,\"do\":\"pop\",\"text\":\"ЗАПОМНИ\",\"icon\":\"⚠️\"},{\"at\":0,\"do\":\"sfx\",\"name\":\"whoosh\"}]}]}\n\n" +
            "Расшифровка:\n{data}"
        ),
    "studio_direct_lite" to (
        "Режиссёр монтажа {format}. В речи [N] — номер слова, с которого может начаться сцена.\n" +
            "Раздели речь на сцены по смыслу (3-8 с{long}) и для каждой дай массив [N,kind,title,items,emph,fx]. " +
            "kind: text step icons list flow cycle big stat quote chart compare{tpls}; " +
            "items: \"эмодзи подпись|…\" (chart: \"подпись=число|…\", compare: ровно 2 стороны); emph: ключевые слова через ;; " +
            "fx через ;: z@слово — зум, p@слово:ТЕКСТ:эмодзи — плашка до 3 слов, s@слово:звук (whoosh pop click ding boom riser), t:переход (fade slide zoom whip none). " +
            "Первая сцена — хук. 0-3 эффекта на сцену, чередуй визуалы. Подписи на языке {lang}.\n" +
            "Только JSON: {\"t\":\"название\",\"s\":[[0,\"stat\",\"80%\",\"😬 людей\",\"80%\",\"z@80%;t:none\"]]}\n\n{data}"
        ),
    "studio_tpl_motion" to (
        "Ты моушн-дизайнер. Придумай шаблон анимированной графики для роликов по запросу: \"{ask}\".\n" +
            "Протокол lumen.motion v1 — анимированная графика сцены с параметрами. Кадр: x и y в долях (0..1), размеры w и h в долях короткой стороны; " +
            "имена позиций: left 0.18, center 0.5, right 0.82, top 0.16, middle 0.5, bottom 0.72, third1 0.33, third2 0.67. " +
            "Держи графику в зоне y 0.30-0.70: сверху и снизу идут субтитры. " +
            "Слой: el — arrow, line, circle, ring, rect, underline, check, cross, spotlight, text, emoji или group (children — слои без keys, сдвиг dx, dy от группы); " +
            "text, color (accent, white, ink, red, green, yellow, blue или #RRGGBB), fill, align (center или left), w, h (для text и emoji h — размер шрифта ~0.05-0.09). " +
            "Текст может брать данные сцены: \$title, \$value, \$key (главное слово), \$num (номер), а в повторе — \$it.icon, \$it.label, \$it.value. " +
            "each: \"items\" повторяет слой для каждого пункта сцены (i — номер с 0, n — сколько), sync: \"word\" — пункт появляется, когда звучит его слово. " +
            "keys — ключевые кадры: t — мс от якоря at (start — начало сцены, item — появление пункта, key — главное слово, end — конец сцены; t может быть отрицательным), " +
            "x, y, s (масштаб), r (градусы), o (0..1), draw (доля прорисовки линии) — числа или формулы с i, n, num, + - * / ( ), min, max, abs, sin, cos, clamp, round, odd; " +
            "ease — linear, in, out, inOut, back, spring; пропущенные значения берутся из прошлого кадра. До 24 слоёв, до 12 ключей. " +
            "Сделай выразительно, но читаемо: появления с back или spring, по очереди, 4-10 слоёв. {tpls}\n" +
            "Верни только JSON: {\"lumen\":\"motion\",\"v\":1,\"id\":\"kinetic_list\",\"name\":\"Кинетический список\",\"use\":\"когда применять, до 10 слов\"," +
            "\"layers\":[{\"el\":\"text\",\"text\":\"\$title\",\"h\":0.07,\"keys\":[{\"t\":0,\"x\":\"center\",\"y\":0.32,\"o\":0,\"s\":0.8},{\"t\":250,\"o\":1,\"s\":1,\"ease\":\"back\"}]}," +
            "{\"each\":\"items\",\"sync\":\"word\",\"el\":\"group\",\"keys\":[{\"t\":0,\"x\":0.12,\"y\":\"0.42+i*0.08\",\"o\":0},{\"t\":300,\"x\":0.2,\"o\":1,\"ease\":\"out\"}]," +
            "\"children\":[{\"el\":\"emoji\",\"text\":\"\$it.icon\",\"h\":0.06},{\"el\":\"text\",\"text\":\"\$it.label\",\"dx\":0.07,\"align\":\"left\",\"h\":0.05}]}]}"
        ),
    "studio_tpl_edit" to (
        "Ты режиссёр монтажа. Придумай подход к монтажу роликов по запросу: \"{ask}\".\n" +
            "Протокол lumen.edit v1 — подход к монтажу всего ролика без AI. Поля: pace {sceneWords:[мин,макс] слов в сцене, cutOnPause: мс паузы для новой сцены}, " +
            "camera {punchEvery: зум на главном слове каждой N-й сцены, 0 — только по правилам}, style (dark, light или пусто), " +
            "rules — по порядку, срабатывают все подходящие (поздние перекрывают), else — если не подошло ни одно: " +
            "if — условия через & (с ! — отрицание): first, last, number, step, compare, list, quote, emoji, question, long (10+ слов), short (до 4), pause (пауза перед сценой), loud, keyword:слово|слово, every:N; " +
            "do — kind (text step icons list flow cycle big stat quote chart compare), tpl (id своего моушн-шаблона), transition (fade slide zoom whip none), " +
            "cues — [\"zoom@key\", \"sfx:ding@start\", \"pop:ТЕКСТ:эмодзи@key\"] (звуки whoosh pop click ding boom riser; якорь key, start или end). " +
            "Свои моушн-шаблоны: {tpls}\n" +
            "Верни только JSON: {\"lumen\":\"edit\",\"v\":1,\"id\":\"fast_shorts\",\"name\":\"Быстрый Shorts\",\"use\":\"когда применять\",\"pace\":{\"sceneWords\":[3,8],\"cutOnPause\":400}," +
            "\"camera\":{\"punchEvery\":2},\"style\":\"\",\"rules\":[{\"if\":\"first\",\"do\":{\"transition\":\"none\",\"cues\":[\"zoom@key\",\"sfx:whoosh@start\"]}}," +
            "{\"if\":\"number\",\"do\":{\"kind\":\"stat\",\"transition\":\"zoom\",\"cues\":[\"sfx:ding@key\"]}},{\"if\":\"else\",\"do\":{\"transition\":\"whip\"}}]}"
        ),
    "studio_tpl_patch" to (
        "Измени шаблон ({kind}) по просьбе: \"{ask}\".\n" +
            "Протокол lumen.motion v1 — анимированная графика сцены с параметрами. Кадр: x и y в долях (0..1), размеры w и h в долях короткой стороны; " +
            "имена позиций: left 0.18, center 0.5, right 0.82, top 0.16, middle 0.5, bottom 0.72, third1 0.33, third2 0.67. " +
            "Держи графику в зоне y 0.30-0.70: сверху и снизу идут субтитры. " +
            "Слой: el — arrow, line, circle, ring, rect, underline, check, cross, spotlight, text, emoji или group (children — слои без keys, сдвиг dx, dy от группы); " +
            "text, color (accent, white, ink, red, green, yellow, blue или #RRGGBB), fill, align (center или left), w, h (для text и emoji h — размер шрифта ~0.05-0.09). " +
            "Текст может брать данные сцены: \$title, \$value, \$key (главное слово), \$num (номер), а в повторе — \$it.icon, \$it.label, \$it.value. " +
            "each: \"items\" повторяет слой для каждого пункта сцены (i — номер с 0, n — сколько), sync: \"word\" — пункт появляется, когда звучит его слово. " +
            "keys — ключевые кадры: t — мс от якоря at (start — начало сцены, item — появление пункта, key — главное слово, end — конец сцены; t может быть отрицательным), " +
            "x, y, s (масштаб), r (градусы), o (0..1), draw (доля прорисовки линии) — числа или формулы с i, n, num, + - * / ( ), min, max, abs, sin, cos, clamp, round, odd; " +
            "ease — linear, in, out, inOut, back, spring; пропущенные значения берутся из прошлого кадра. До 24 слоёв, до 12 ключей. " +
            "Протокол lumen.edit v1 — подход к монтажу всего ролика без AI. Поля: pace {sceneWords:[мин,макс] слов в сцене, cutOnPause: мс паузы для новой сцены}, " +
            "camera {punchEvery: зум на главном слове каждой N-й сцены, 0 — только по правилам}, style (dark, light или пусто), " +
            "rules — по порядку, срабатывают все подходящие (поздние перекрывают), else — если не подошло ни одно: " +
            "if — условия через & (с ! — отрицание): first, last, number, step, compare, list, quote, emoji, question, long (10+ слов), short (до 4), pause (пауза перед сценой), loud, keyword:слово|слово, every:N; " +
            "do — kind (text step icons list flow cycle big stat quote chart compare), tpl (id своего моушн-шаблона), transition (fade slide zoom whip none), " +
            "cues — [\"zoom@key\", \"sfx:ding@start\", \"pop:ТЕКСТ:эмодзи@key\"] (звуки whoosh pop click ding boom riser; якорь key, start или end). " +
            "Верни только JSON Patch (RFC 6902, операции add, replace, remove) — массив изменений, без всего шаблона, например " +
            "[{\"op\":\"replace\",\"path\":\"/layers/0/keys/1/ease\",\"value\":\"spring\"}].\n\nШаблон:\n{data}"
        ),
    "studio_tpl_fix" to (
        "Исправь шаблон {kind}: проверка нашла ошибки:\n{errors}\nИсправь только их и верни весь шаблон, только JSON.\n\nШаблон:\n{data}"
        ),
    "studio_tpl_look" to (
        "На картинке кадры шаблона анимации «{name}» ({use}) на пробных данных, слева направо по времени. " +
            "Оцени: читаемость, наложения, выход за кадр, ритм появления. Если есть что исправить — верни JSON Patch (массив add/replace/remove) к шаблону ниже; " +
            "если всё хорошо — пустой массив [].\n" +
            "Протокол lumen.motion v1 — анимированная графика сцены с параметрами. Кадр: x и y в долях (0..1), размеры w и h в долях короткой стороны; " +
            "имена позиций: left 0.18, center 0.5, right 0.82, top 0.16, middle 0.5, bottom 0.72, third1 0.33, third2 0.67. " +
            "Держи графику в зоне y 0.30-0.70: сверху и снизу идут субтитры. " +
            "Слой: el — arrow, line, circle, ring, rect, underline, check, cross, spotlight, text, emoji или group (children — слои без keys, сдвиг dx, dy от группы); " +
            "text, color (accent, white, ink, red, green, yellow, blue или #RRGGBB), fill, align (center или left), w, h (для text и emoji h — размер шрифта ~0.05-0.09). " +
            "Текст может брать данные сцены: \$title, \$value, \$key (главное слово), \$num (номер), а в повторе — \$it.icon, \$it.label, \$it.value. " +
            "each: \"items\" повторяет слой для каждого пункта сцены (i — номер с 0, n — сколько), sync: \"word\" — пункт появляется, когда звучит его слово. " +
            "keys — ключевые кадры: t — мс от якоря at (start — начало сцены, item — появление пункта, key — главное слово, end — конец сцены; t может быть отрицательным), " +
            "x, y, s (масштаб), r (градусы), o (0..1), draw (доля прорисовки линии) — числа или формулы с i, n, num, + - * / ( ), min, max, abs, sin, cos, clamp, round, odd; " +
            "ease — linear, in, out, inOut, back, spring; пропущенные значения берутся из прошлого кадра. До 24 слоёв, до 12 ключей. " +
            "\n\nШаблон:\n{data}"
        ),
    "studio_direct_patch" to (
        "Улучши черновик монтажа {format}. Ниже сцены в виде «#номер вид | текст». Выбери до {n} ключевых сцен " +
            "(хук, главные мысли, цифры, сравнения, вывод) и дай им лучший визуал и эффекты; остальные не трогай.\n" +
            "Строка ответа: [номер,kind,title,items,emph,fx]. " +
            "kind: text step icons list flow cycle big stat quote chart compare{tpls}; " +
            "items: \"эмодзи подпись|…\" (chart: \"подпись=число|…\", compare: ровно 2 стороны); emph: ключевые слова через ;; " +
            "fx через ;: z@слово — зум, p@слово:ТЕКСТ:эмодзи — плашка до 3 слов, s@слово:звук (whoosh pop click ding boom riser), t:переход (fade slide zoom whip none). " +
            "Подписи на языке {lang}.\n" +
            "Только JSON: {\"t\":\"название\",\"s\":[[0,\"stat\",\"80%\",\"😬 людей\",\"80%\",\"z@80%;p@людей:ВАЖНО:⚠️\"]]}\n\n{data}"
        )
)

/** Shared description of the visuals for both prompts. */
const val STUDIO_VISUALS = "Для каждой сцены выбери визуал kind: " +
    "text — только крупный текст (когда картинка не нужна); " +
    "step — номер этапа или раздела, в title короткий заголовок (2-4 слова); " +
    "icons — 3-6 плиток (эмодзи + подпись 1-2 слова) для перечня понятий; " +
    "list — 2-5 пунктов с галочками (подписи до 4 слов); " +
    "flow — 3-5 шагов процесса по порядку; " +
    "cycle — 3-5 этапов повторяющегося цикла; " +
    "big — один крупный эмодзи и подпись; " +
    "stat — крупное число или факт в title (например \"×3\" или \"80%\") и подпись в items[0].label; " +
    "quote — цитата или главная мысль: в title фраза до 12 слов, в items[0].label источник (можно пусто), её выделит маркер; " +
    "chart — столбчатая диаграмма из 2-6 столбцов: items с label (подпись) и value (число, например \"55\" или \"3,5\"); " +
    "compare — сравнение «было/стало», «миф/факт», «плохо/хорошо»: ровно 2 items (icon + label до 5 слов), в title что сравниваем. " +
    "Чередуй визуалы, не ставь один и тот же больше двух раз подряд. Эмодзи подбирай по смыслу."

/**
 * Voiced scenes kept on disk between attempts: when the connection drops, the next "Analyse" voices
 * only the scenes that are not ready yet. Pure helpers; the files are written by StudioActivity.
 */
object StudioTtsCache {
    fun key(text: String, o: TtsOptions): String = sha1("${o.voice}|${o.rate}|${o.pitch}|${o.volume}|$text").take(20)

    fun wordsJson(ws: List<SWord>): String =
        org.json.JSONArray().apply { ws.forEach { put(org.json.JSONArray().put(it.text).put(it.startMs).put(it.endMs)) } }.toString()

    fun parseWords(s: String): List<SWord>? = try {
        val a = org.json.JSONArray(s)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { SWord(it.getString(0), it.getLong(1), it.getLong(2)) } }
    } catch (e: Exception) {
        null
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini text-to-speech for the Studio (pure logic, no Android): the 30 prebuilt voices, the request body,
 * the audio of the answer, script chunks (few requests: the free tier counts requests per day) and
 * word timing — Gemini returns no word marks, so written words are spread over the speech parts.
 * The HTTP call itself is [Llm.speech].
 */
object GeminiVoice {
    data class Voice(val name: String, val female: Boolean, val ru: String, val en: String)

    /** All prebuilt voices of the Gemini TTS models (they speak every supported language). */
    val VOICES = listOf(
        Voice("Kore", true, "твёрдый", "firm"),
        Voice("Zephyr", true, "яркий", "bright"),
        Voice("Leda", true, "юный", "youthful"),
        Voice("Aoede", true, "лёгкий", "breezy"),
        Voice("Callirrhoe", true, "спокойный", "easy-going"),
        Voice("Autonoe", true, "звонкий", "bright"),
        Voice("Despina", true, "плавный", "smooth"),
        Voice("Erinome", true, "чёткий", "clear"),
        Voice("Laomedeia", true, "бодрый", "upbeat"),
        Voice("Achernar", true, "мягкий", "soft"),
        Voice("Gacrux", true, "зрелый", "mature"),
        Voice("Pulcherrima", true, "напористый", "forward"),
        Voice("Vindemiatrix", true, "нежный", "gentle"),
        Voice("Sulafat", true, "тёплый", "warm"),
        Voice("Charon", false, "дикторский", "informative"),
        Voice("Puck", false, "бодрый", "upbeat"),
        Voice("Fenrir", false, "взволнованный", "excitable"),
        Voice("Orus", false, "твёрдый", "firm"),
        Voice("Enceladus", false, "с придыханием", "breathy"),
        Voice("Iapetus", false, "чёткий", "clear"),
        Voice("Umbriel", false, "спокойный", "easy-going"),
        Voice("Algieba", false, "плавный", "smooth"),
        Voice("Algenib", false, "с хрипотцой", "gravelly"),
        Voice("Rasalgethi", false, "рассказчик", "informative"),
        Voice("Alnilam", false, "уверенный", "firm"),
        Voice("Schedar", false, "ровный", "even"),
        Voice("Achird", false, "дружелюбный", "friendly"),
        Voice("Zubenelgenubi", false, "непринуждённый", "casual"),
        Voice("Sadachbia", false, "живой", "lively"),
        Voice("Sadaltager", false, "знающий", "knowledgeable"),
    )

    const val DEFAULT_VOICE = "Kore"
    /** "Auto": the voice models in turn ([chain]), nothing to set up; the text model of Settings is not used here. */
    const val AUTO = "auto"
    const val DEFAULT_MODEL = AUTO

    /** Models: id, Russian and English label (short: they are chips). */
    val MODELS = listOf(
        Triple(AUTO, "Авто", "Auto"),
        Triple("gemini-3.8-flash-tts", "Flash — лучше звучит", "Flash — sounds best"),
        Triple("gemini-3.8-flash-lite-tts", "Flash Lite — быстрее", "Flash Lite — faster"),
        Triple("gemini-2.5-pro-preview-tts", "2.5 Pro — платный", "2.5 Pro — paid"),
    )

    /** Voice models to try for [id], in order: "Auto" — the best one first, then the lighter one. */
    fun chain(id: String): List<String> =
        if (model(id) == AUTO) listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts") else listOf(model(id))

    /**
     * Should "Auto" try the next model after HTTP [code] with [raw] body? Yes for a missing model, a limit
     * or an overload of this model; no for a bad key or a blocked region (another model would fail the same way).
     */
    fun tryNext(code: Int, raw: String): Boolean {
        val m = raw.lowercase()
        if (m.contains("location is not supported") || m.contains("api key not valid") || m.contains("api_key_invalid") || code == 401) return false
        return code == 404 || code == 429 || code == 403 || code >= 500 || code == 400 && m.contains("model")
    }

    /** ~25 audio tokens per second of speech (measured), so a minute is ~1500 output tokens. */
    const val TOKENS_PER_SEC = 25

    /** Letters per request: ~1.5–2 minutes of speech, well under the 16k output tokens of one answer. */
    const val CHUNK_CHARS = 1500

    fun voice(name: String): Voice = VOICES.firstOrNull { it.name.equals(name, true) } ?: VOICES.first()

    fun model(id: String): String = MODELS.firstOrNull { it.first == id }?.first ?: DEFAULT_MODEL

    /** Reading instruction: the [tone] ([VoiceTone]), the Studio's speech rate (−10 … 20) and the author's own [wish]. */
    fun style(rate: Int, wish: String, ru: Boolean, tone: String = VoiceTone.DEFAULT): String = VoiceTone.instruction(tone, rate, wish, ru)

    /** Body of a generateContent request that answers with audio in [voice]. */
    fun body(text: String, voice: String, style: String): String = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject()
            .put("text", if (style.isBlank()) text else "$style\n$text")))))
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice(voice).name)))))
        .toString()

    /**
     * Mono 16-bit samples and rate of an answer: inline data is either a WAV file (3.x models)
     * or raw little-endian PCM "audio/L16;codec=pcm;rate=24000" (2.5 models).
     */
    fun audio(answer: String): Pair<ShortArray, Int> {
        val o = JSONObject(answer)
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: error(o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
                ?.let { "Gemini: $it" } ?: "Gemini returned no audio")
        val out = ArrayList<ShortArray>()
        var rate = 24_000
        for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
            val bytes = base64(d.optString("data"))
            val mime = d.optString("mimeType", d.optString("mime_type"))
            val wav = Studio.readWav(bytes)
            if (wav != null) {
                out += wav.first
                rate = wav.second
            } else {
                rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: rate
                out += ShortArray(bytes.size / 2) { k -> ((bytes[2 * k].toInt() and 0xFF) or (bytes[2 * k + 1].toInt() shl 8)).toShort() }
            }
        }
        if (out.isEmpty() || out.all { it.isEmpty() }) error("Gemini returned no audio")
        return Studio.concat(out) to rate
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Plain base64 (java.util.Base64 needs Android 8). */
    fun base64(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = when (c) {
                '-' -> 62
                '_' -> 63
                else -> B64.indexOf(c)
            }
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Scenes voiced by one request: neighbours with the same voice, up to [maxChars] letters. */
    data class Chunk(val voice: String, val scenes: List<Int>)

    fun chunks(says: List<String>, voices: List<String>, maxChars: Int = CHUNK_CHARS): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var cur = mutableListOf<Int>()
        var len = 0
        var v = ""
        says.forEachIndexed { i, s ->
            if (s.isBlank()) return@forEachIndexed
            val sv = voices.getOrElse(i) { voices.firstOrNull() ?: DEFAULT_VOICE }
            if (cur.isNotEmpty() && (sv != v || len + s.length > maxChars)) {
                out += Chunk(v, cur)
                cur = mutableListOf()
                len = 0
            }
            v = sv
            cur += i
            len += s.length + 1
        }
        if (cur.isNotEmpty()) out += Chunk(v, cur)
        return out
    }

    /** Text of a chunk: scenes as paragraphs, so the voice pauses between them. */
    fun text(says: List<String>, c: Chunk): String = c.scenes.joinToString("\n\n") { says[it].trim() }

    /**
     * Words of every scene of a chunk on its audio: written words spread over the speech parts by length
     * ([Studio.alignWords]); a scene owns the words of its text, in order.
     */
    fun words(says: List<String>, c: Chunk, pcm: ShortArray, rate: Int): List<List<SWord>> {
        val per = c.scenes.map { Studio.tokens(says[it]) }
        var spans = Studio.speechSpans(pcm, rate, minPauseMs = 200)
        if (spans.isEmpty()) spans = listOf(Span(0, pcm.size * 1000L / rate))
        // scene starts are paragraph breaks: a sure pause
        val hard = mutableSetOf<Int>()
        var at = 0
        for (p in per) { if (at > 0 && p.isNotEmpty()) hard += at; at += p.size }
        val all = Studio.alignAnchored(per.flatten(), spans, hard)
        val out = mutableListOf<List<SWord>>()
        var k = 0
        for (p in per) {
            out += all.subList(k, minOf(all.size, k + p.size)).toList()
            k += p.size
        }
        return out
    }

    // ---------- timing by the AI that hears the voice-over ----------

    /** The voice gives no word marks: an AI that hears audio tells when every phrase starts and ends. */
    const val TIMING_PROMPT = "Это запись озвучки, её текст разбит на фразы с номерами. Послушай запись и для каждой фразы укажи, " +
        "когда она начинается и заканчивается, в секундах от начала записи с десятыми (12.4). Время — по звуку, а не по длине текста; " +
        "фразы идут по порядку. Ответ — только JSON: массив [номер, начало, конец] для каждой фразы.\nФРАЗЫ:\n{phrases}"

    /** responseSchema of the timing answer: [[n, start, end], …]. */
    val TIMING_SCHEMA: JSONObject get() = JSONObject().put("type", "ARRAY")
        .put("items", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "NUMBER")))

    /** Phrases of [words] (word ranges): they end at punctuation and before [hard] breaks; a long one is split at ~[most] words. */
    fun phrases(words: List<String>, hard: Set<Int> = emptySet(), most: Int = 14): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var a = 0
        for (i in words.indices) {
            val end = i == words.lastIndex || (i + 1) in hard || Studio.breakStrength(words[i]) > 0 || i - a + 1 >= most
            if (end) { out += a..i; a = i + 1 }
        }
        return out
    }

    /** "1. text" lines of the phrases for [TIMING_PROMPT]. */
    fun phraseList(words: List<String>, phrases: List<IntRange>): String =
        phrases.withIndex().joinToString("\n") { (k, r) -> "${k + 1}. " + words.subList(r.first, r.last + 1).joinToString(" ") }

    /**
     * Start and end (ms) of each of [n] phrases from the AI answer, null where missing or impossible: outside the
     * audio ([durMs]), ending before it starts, or going back in time. Null for the whole answer when under half fit.
     */
    fun parseTiming(answer: String, n: Int, durMs: Long): List<LongArray?>? {
        val a = answer.indexOf('[')
        val b = answer.lastIndexOf(']')
        if (n <= 0 || a < 0 || b <= a) return null
        val arr = runCatching { JSONArray(answer.substring(a, b + 1)) }.getOrNull() ?: return null
        val out = arrayOfNulls<LongArray>(n)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONArray(i)
            val k: Int
            val s: Double
            val e: Double
            if (o != null && o.length() >= 3) { k = o.optInt(0) - 1; s = o.optDouble(1); e = o.optDouble(2) }
            else {
                val j = arr.optJSONObject(i) ?: continue
                k = j.optInt("n", i + 1) - 1; s = j.optDouble("start"); e = j.optDouble("end")
            }
            if (k !in 0 until n || s.isNaN() || e.isNaN()) continue
            val sm = (s * 1000).toLong()
            val em = (e * 1000).toLong()
            if (sm < 0 || em <= sm || sm > durMs + 300) continue
            out[k] = longArrayOf(sm, minOf(em, durMs))
        }
        // going back in time: keep the longest increasing run (by start)
        var last = -1L
        for (k in 0 until n) {
            val m = out[k] ?: continue
            if (m[0] < last - 150) out[k] = null else last = maxOf(last, m[0])
        }
        return out.toList().takeIf { l -> l.count { it != null } * 2 >= n }
    }

    /**
     * Words placed by the AI phrase times: every timed phrase starts and ends at the nearest pause edge of [speech]
     * (within 400 ms), untimed phrases share the time between their timed neighbours, words spread inside each phrase.
     */
    fun alignTimed(words: List<String>, phrases: List<IntRange>, marks: List<LongArray?>, speech: List<Span>, durMs: Long): List<SWord> {
        if (words.isEmpty()) return emptyList()
        val starts = speech.map { it.startMs }
        val ends = speech.map { it.endMs }
        fun snap(t: Long, edges: List<Long>): Long = edges.minByOrNull { kotlin.math.abs(it - t) }?.takeIf { kotlin.math.abs(it - t) <= 400 } ?: t
        val wt = words.map { Studio.spokenWeight(it) }
        fun weight(r: IntRange) = (r.first..r.last).sumOf { wt[it] }
        val win = arrayOfNulls<LongArray>(phrases.size)
        var prevEnd = speech.firstOrNull()?.startMs ?: 0L
        for (k in phrases.indices) {
            val m = marks.getOrNull(k) ?: continue
            val a = maxOf(prevEnd, snap(m[0], starts))
            val b = maxOf(a + 80, snap(m[1], ends))
            win[k] = longArrayOf(a, b)
            prevEnd = b
        }
        // untimed runs: the gap between neighbours by weight
        var k = 0
        val endAll = maxOf(speech.lastOrNull()?.endMs ?: durMs, prevEnd)
        while (k < phrases.size) {
            if (win[k] != null) { k++; continue }
            var j = k
            while (j < phrases.size && win[j] == null) j++
            val a = if (k > 0) win[k - 1]!![1] else speech.firstOrNull()?.startMs ?: 0L
            val b = maxOf(a + 80L * (j - k), if (j < phrases.size) win[j]!![0] else endAll)
            val total = (k until j).sumOf { weight(phrases[it]) }
            var at = a.toDouble()
            for (i in k until j) {
                val len = (b - a) * weight(phrases[i]) / total
                win[i] = longArrayOf(at.toLong(), (at + len).toLong())
                at += len
            }
            k = j
        }
        val out = ArrayList<SWord>(words.size)
        for ((i, r) in phrases.withIndex()) {
            val (a, b) = win[i]!!.let { it[0] to maxOf(it[0] + 1, it[1]) }
            val inside = speech.mapNotNull { s -> val x = maxOf(s.startMs, a); val y = minOf(s.endMs, b); if (y > x) Span(x, y) else null }
                .ifEmpty { listOf(Span(a, b)) }
            out += Studio.spread(words.subList(r.first, r.last + 1), wt.subList(r.first, r.last + 1), inside)
        }
        return out
    }

    /** Words of the chunk and its phrases for the timing request (scene starts are hard breaks). */
    fun chunkWords(says: List<String>, c: Chunk): Triple<List<List<String>>, List<String>, Set<Int>> {
        val per = c.scenes.map { Studio.tokens(says[it]) }
        val hard = mutableSetOf<Int>()
        var at = 0
        for (p in per) { if (at > 0 && p.isNotEmpty()) hard += at; at += p.size }
        return Triple(per, per.flatten(), hard)
    }

    /**
     * Words of a voiced chunk by scene: by the AI [timing] answer when it fits (see [parseTiming]),
     * else pinned to pauses ([words]).
     */
    fun wordsTimed(says: List<String>, c: Chunk, pcm: ShortArray, rate: Int, timing: String?): List<List<SWord>> {
        val (per, flat, hard) = chunkWords(says, c)
        val durMs = pcm.size * 1000L / rate
        val ph = phrases(flat, hard)
        val marks = timing?.let { parseTiming(it, ph.size, durMs) } ?: return words(says, c, pcm, rate)
        var spans = Studio.speechSpans(pcm, rate, minPauseMs = 200)
        if (spans.isEmpty()) spans = listOf(Span(0, durMs))
        val all = alignTimed(flat, ph, marks, spans, durMs)
        val out = mutableListOf<List<SWord>>()
        var k = 0
        for (p in per) { out += all.subList(k, minOf(all.size, k + p.size)).toList(); k += p.size }
        return out
    }

    /** Gemini voices of the characters: same gender as their Edge voice, all different while possible. */
    fun castVoices(female: List<Boolean?>, narrator: String): List<String> {
        val used = mutableSetOf(voice(narrator).name)
        return female.map { f ->
            val pool = if (f == null) VOICES else VOICES.filter { it.female == f }
            val v = pool.firstOrNull { it.name !in used } ?: pool.first()
            used += v.name
            v.name
        }
    }

    /** Minutes of speech a day for [requestsPerDay] requests of [CHUNK_CHARS] letters (~15 letters a second). */
    fun minutesPerDay(requestsPerDay: Int, charsPerSec: Double = 15.0): Int =
        (requestsPerDay * CHUNK_CHARS / charsPerSec / 60).toInt()
}

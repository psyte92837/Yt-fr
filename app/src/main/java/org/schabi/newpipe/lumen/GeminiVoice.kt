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
    const val DEFAULT_MODEL = "gemini-3.8-flash-tts"

    /** Models: id, Russian and English label. */
    val MODELS = listOf(
        Triple("gemini-3.8-flash-tts", "Flash — лучше звучит", "Flash — sounds best"),
        Triple("gemini-3.8-flash-lite-tts", "Flash Lite — быстрее", "Flash Lite — faster"),
        Triple("gemini-2.5-pro-preview-tts", "2.5 Pro — только платный ключ", "2.5 Pro — paid key only"),
    )

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
        var spans = Studio.speechSpans(pcm, rate)
        if (spans.isEmpty()) spans = listOf(Span(0, pcm.size * 1000L / rate))
        val all = Studio.alignWords(per.flatten(), spans)
        val out = mutableListOf<List<SWord>>()
        var k = 0
        for (p in per) {
            out += all.subList(k, minOf(all.size, k + p.size)).toList()
            k += p.size
        }
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

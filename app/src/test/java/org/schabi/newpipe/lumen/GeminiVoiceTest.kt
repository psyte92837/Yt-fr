/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiVoiceTest {
    private fun b64(bytes: ByteArray): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or ((bytes.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8) or (bytes.getOrElse(i + 2) { 0 }.toInt() and 0xFF)
            sb.append(a[n shr 18 and 63]).append(a[n shr 12 and 63])
            sb.append(if (i + 1 < bytes.size) a[n shr 6 and 63] else '=').append(if (i + 2 < bytes.size) a[n and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun answer(data: ByteArray, mime: String) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", b64(data)))))))).toString()

    @Test
    fun thirtyVoicesBothGenders() {
        assertEquals(30, GeminiVoice.VOICES.size)
        assertEquals(30, GeminiVoice.VOICES.map { it.name }.distinct().size)
        assertEquals(14, GeminiVoice.VOICES.count { it.female })
        assertEquals("Charon", GeminiVoice.voice("charon").name)
        assertEquals(GeminiVoice.DEFAULT_VOICE, GeminiVoice.voice("nope").name)
        assertEquals(GeminiVoice.DEFAULT_MODEL, GeminiVoice.model("gemini-2.5-flash"))
    }

    @Test
    fun autoModelTriesTheNextVoiceModel() {
        assertEquals(GeminiVoice.AUTO, GeminiVoice.model(""))
        assertEquals(listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"), GeminiVoice.chain(""))
        assertEquals(listOf("gemini-3.8-flash-lite-tts"), GeminiVoice.chain("gemini-3.8-flash-lite-tts"))
        assertTrue(GeminiVoice.tryNext(404, "models/x is not found"))
        assertTrue(GeminiVoice.tryNext(429, "quota"))
        assertTrue(GeminiVoice.tryNext(503, "overloaded"))
        assertTrue(!GeminiVoice.tryNext(400, "API key not valid"))
        assertTrue(!GeminiVoice.tryNext(400, "User location is not supported"))
        assertTrue(GeminiVoice.MODELS.all { it.second.length <= 26 && it.third.length <= 26 })
    }

    @Test
    fun base64RoundTrip() {
        for (n in 0..7) {
            val b = ByteArray(n) { (it * 37 - 100).toByte() }
            assertTrue(b.contentEquals(GeminiVoice.base64(b64(b))))
        }
    }

    @Test
    fun bodyAsksForAudioInTheVoice() {
        val o = JSONObject(GeminiVoice.body("Привет", "puck", "Прочитай бодро:"))
        assertEquals("AUDIO", o.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Puck", o.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertEquals("Прочитай бодро:\nПривет", o.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun audioFromRawPcmAndWav() {
        val pcm = byteArrayOf(1, 0, -1, -1, 0, 1)
        val (a, r) = GeminiVoice.audio(answer(pcm, "audio/L16;codec=pcm;rate=24000"))
        assertEquals(24_000, r)
        assertEquals(listOf<Short>(1, -1, 256), a.toList())
        val wav = Studio.wavHeader(pcm.size, 22_050) + pcm
        val (b, r2) = GeminiVoice.audio(answer(wav, "audio/wav"))
        assertEquals(22_050, r2)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun chunksKeepOrderVoiceAndSize() {
        val says = listOf("a".repeat(800), "b".repeat(800), "", "c".repeat(100), "d".repeat(100))
        val c = GeminiVoice.chunks(says, listOf("Kore", "Kore", "Kore", "Kore", "Puck"))
        assertEquals(listOf(listOf(0), listOf(1, 3), listOf(4)), c.map { it.scenes })
        assertEquals(listOf("Kore", "Kore", "Puck"), c.map { it.voice })
        assertEquals("b".repeat(800) + "\n\n" + "c".repeat(100), GeminiVoice.text(says, c[1]))
    }

    @Test
    fun wordsSplitBetweenScenesInOrder() {
        val rate = 8000
        // speech 0–1 s, pause, speech 1.5–2.5 s
        val pcm = ShortArray(rate * 3) { i -> if (i < rate || i in rate * 3 / 2 until rate * 5 / 2) (if (i % 2 == 0) 9000 else -9000).toShort() else 0 }
        val says = listOf("один два", "три четыре пять")
        val ws = GeminiVoice.words(says, GeminiVoice.Chunk("Kore", listOf(0, 1)), pcm, rate)
        assertEquals(listOf(2, 3), ws.map { it.size })
        val flat = ws.flatten()
        assertEquals(listOf("один", "два", "три", "четыре", "пять"), flat.map { it.text })
        assertTrue(flat.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 })
        assertTrue(flat.last().endMs <= 2600)
    }

    @Test
    fun castVoicesFollowGenderAndDiffer() {
        val v = GeminiVoice.castVoices(listOf(true, false, true, null), "Kore")
        assertTrue(GeminiVoice.voice(v[0]).female && !GeminiVoice.voice(v[1]).female && GeminiVoice.voice(v[2]).female)
        assertEquals(5, (v + "Kore").distinct().size)
        assertTrue(GeminiVoice.style(20, "с улыбкой.", true).let { it.contains("быстро") && it.contains("с улыбкой") && it.endsWith(":") })
        assertEquals(20, GeminiVoice.minutesPerDay(12))
    }

    @Test
    fun phrasesTimedByTheAiThatHearsTheVoice() {
        val words = Studio.tokens("Представьте: в 2025 году рынок вырос на 37%. Это много, но не рекорд. Почему так вышло?")
        val ph = GeminiVoice.phrases(words)
        assertEquals(listOf("Представьте:", "в 2025 году рынок вырос на 37%.", "Это много,", "но не рекорд.", "Почему так вышло?"),
            ph.map { r -> words.subList(r.first, r.last + 1).joinToString(" ") })
        assertTrue(GeminiVoice.phraseList(words, ph).startsWith("1. Представьте:\n2. в 2025"))
        // a live answer of gemini-3.5-flash-lite (times within ~0.1 s of the real pauses), one phrase going back in time
        val answer = "[[1,4.026,4.526],[2,4.866,8.656],[3,9.166,9.706],[4,3.0,3.5],[5,11.206,11.966]]"
        val marks = GeminiVoice.parseTiming(answer, ph.size, 12_000)!!
        assertEquals(4026L, marks[0]!![0])
        assertEquals(null, marks[3])
        assertEquals(null, GeminiVoice.parseTiming("[[1,1,2]]", 5, 12_000))
        assertEquals(null, GeminiVoice.parseTiming("нет", 5, 12_000))
        // speech parts of that voice: phrase starts snap to them, the untimed phrase fills the gap
        val speech = listOf(Span(3880, 4500), Span(4840, 8680), Span(9260, 9700), Span(9900, 10620), Span(11160, 12000))
        val ws = GeminiVoice.alignTimed(words, ph, marks, speech, 12_000)
        assertEquals(words.size, ws.size)
        assertEquals(3880L, ws[0].startMs)
        assertEquals(4840L, ws[1].startMs)
        assertEquals(9260L, ws[ph[2].first].startMs)
        assertEquals(11160L, ws[ph[4].first].startMs)
        assertTrue(ws[ph[3].first].startMs in 9700..10000)
        assertTrue(ws.zipWithNext().all { (a, b) -> a.startMs <= b.startMs && a.endMs <= b.startMs + 1 })
    }
}

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
}

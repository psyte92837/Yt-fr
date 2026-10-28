/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsTest {
    @Test
    fun gecTokenMatchesEdgeTtsReference() {
        // reference value from the python edge-tts algorithm
        val ref = "6B55639D80CE6120432F90F6318EB4344080A5252E5C49A6D119D3B00C225095"
        assertEquals(ref, EdgeTts.gecToken(1761652800))
        assertEquals(ref, EdgeTts.gecToken(1761652800 + 299)) // same 5-minute window
        assertTrue(EdgeTts.gecToken(1761652800 + 300) != ref)
    }

    @Test
    fun ssmlEscapesAndFormatsProsody() {
        val s = EdgeTts.ssml(EdgeTts.xmlEscape("A & <b> \"c\" 'd'"), TtsOptions("ru-RU-SvetlanaNeural", rate = 10, pitch = -5, volume = 0))
        assertTrue(s.contains("<voice name='Microsoft Server Speech Text to Speech Voice (ru-RU, SvetlanaNeural)'>"))
        assertTrue(s.contains("pitch='-5Hz' rate='+10%' volume='+0%'"))
        assertTrue(s.contains("A &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;"))
    }

    @Test
    fun fullVoiceNameHandlesMultiPartNames() {
        assertEquals("Microsoft Server Speech Text to Speech Voice (en-US, AvaMultilingualNeural)", EdgeTts.fullVoiceName("en-US-AvaMultilingualNeural"))
        assertEquals("x", EdgeTts.fullVoiceName("x"))
    }

    @Test
    fun splitRespectsByteLimitAndWords() {
        val text = (1..400).joinToString(" ") { "Слово$it & ещё." }
        val parts = EdgeTts.split(text, 500)
        assertTrue(parts.size > 1)
        parts.forEach {
            assertTrue(it.toByteArray().size <= 500)
            assertTrue(!it.endsWith("&amp") && !it.endsWith("&"))
        }
        val joined = parts.joinToString(" ").replace("&amp;", "&")
        assertEquals(text.split(' ').filter { it.isNotBlank() }, joined.split(' ').filter { it.isNotBlank() })
    }

    @Test
    fun splitHardCutsLongWordsAndCleansControlChars() {
        val parts = EdgeTts.split("a".repeat(1000) + "\u0001b", 300)
        assertTrue(parts.all { it.length <= 300 })
        assertEquals("a".repeat(1000) + "b", parts.joinToString("").replace(" ", ""))
        assertTrue(EdgeTts.split("   ").isEmpty())
    }

    @Test
    fun binaryFrameAudioExtraction() {
        val headers = "X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n"
        val h = headers.toByteArray()
        val frame = byteArrayOf((h.size shr 8).toByte(), h.size.toByte()) + h + byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), EdgeTts.audioPayload(frame))
        val meta = "Path:audio.metadata\r\n".toByteArray()
        assertNull(EdgeTts.audioPayload(byteArrayOf(0, meta.size.toByte()) + meta))
        assertNull(EdgeTts.audioPayload(byteArrayOf(0)))
        assertEquals("turn.end", EdgeTts.path("X-RequestId:1\r\nPath:turn.end"))
    }

    @Test
    fun voiceListParsing() {
        val json = """[{"Name":"Microsoft Server Speech Text to Speech Voice (ru-RU, DmitryNeural)","ShortName":"ru-RU-DmitryNeural",
            "Gender":"Male","Locale":"ru-RU","VoiceTag":{"ContentCategories":["News"],"VoicePersonalities":["Friendly","Positive"]}},
            {"ShortName":"en-US-AriaNeural","Gender":"Female","Locale":"en-US"},{"Gender":"Male"}]"""
        val v = EdgeTts.parseVoices(json)
        assertEquals(2, v.size)
        assertEquals("en-US-AriaNeural", v[0].shortName)
        assertEquals("Dmitry", v[1].display)
        assertEquals(listOf("Friendly", "Positive"), v[1].personalities)
        assertEquals(listOf("News"), v[1].categories)
    }

    @Test
    fun languageDetectionAndVoicePick() {
        assertEquals("ru", EdgeTts.detectLang("Привет, как дела? YouTube"))
        assertEquals("uk", EdgeTts.detectLang("Привіт, як справи? Їжак і ґанок"))
        assertEquals("en", EdgeTts.detectLang("Hello world, привет"))
        assertNull(EdgeTts.detectLang("12 34 !"))
        val voices = EdgeTts.builtIn
        assertEquals("ru-RU-SvetlanaNeural", EdgeTts.pickVoice(voices, "ru", null)?.shortName)
        assertEquals("ru-RU-DmitryNeural", EdgeTts.pickVoice(voices, "ru", "ru-RU-DmitryNeural")?.shortName)
        assertEquals("en-US-AriaNeural", EdgeTts.pickVoice(voices, "en", null)?.shortName)
        assertNull(EdgeTts.pickVoice(voices, "xx", null))
    }

    @Test
    fun messagesAndTimestamp() {
        val ts = EdgeTts.timestamp(0)
        assertEquals("Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)", ts)
        assertTrue(EdgeTts.configMessage(ts).contains("Path:speech.config\r\n\r\n"))
        assertTrue(EdgeTts.configMessage(ts).contains(EdgeTts.FORMAT))
        val m = EdgeTts.ssmlMessage("id1", ts, "<speak/>")
        assertTrue(m.startsWith("X-RequestId:id1\r\n"))
        assertTrue(m.endsWith("Path:ssml\r\n\r\n<speak/>"))
        assertEquals("+0%", EdgeTts.signed(0, "%"))
        assertEquals("-20Hz", EdgeTts.signed(-20, "Hz"))
    }
}

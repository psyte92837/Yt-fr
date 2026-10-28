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
        // reference values from edge-tts 7.3.1 drm.generate_sec_ms_gec_v2
        val ref = "AD29DDF1543B8F22-DF1E217CB193890F45AEED1207EF040A73EB71059B437222B79A19131946C063"
        assertEquals(ref, EdgeTts.gecToken(1761652800))
        assertEquals(ref, EdgeTts.gecToken(1761652800 + 299)) // same 5-minute window
        assertTrue(EdgeTts.gecToken(1761652800 + 300) != ref)
        assertEquals("572D353BA609448C-1CFCFA06664DEF495EEE079DA56F8CFABE3CA43DC8964D4AA70742781BF25FF6", EdgeTts.gecToken(1793232000))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeTts.gecToken(1800000123))
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

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            files.forEach { (n, c) ->
                z.putNextEntry(java.util.zip.ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun decodeDetectsEncodings() {
        assertEquals("Привет", TtsText.decode("Привет".toByteArray(charset("windows-1251"))))
        assertEquals("Привет", TtsText.decode("Привет".toByteArray()))
        assertEquals("Привет", TtsText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Привет".toByteArray()))
        assertEquals("Hi ё", TtsText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hi ё".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun fb2AndHtmlBecomeParagraphs() {
        val fb2 = """<?xml version="1.0"?><FictionBook><description><title-info><book-title>Книга</book-title></title-info></description>
            <body><section><title><p>Глава 1</p></title><p>Первый&nbsp;абзац &laquo;в кавычках&raquo;.</p><p>Второй &#1103; &#x44F;.</p></section></body>
            <binary id="c.jpg">QUJDREVGRw==</binary></FictionBook>"""
        val txt = TtsText.fromFile("book.fb2", null, fb2.toByteArray())
        assertTrue(txt.contains("Глава 1\n"))
        assertTrue(txt.contains("\nПервый абзац «в кавычках».\nВторой я я."))
        assertTrue(!txt.contains("QUJD"))
        val html = "<html><head><title>x</title><style>p{}</style></head><body><h1>Заголовок</h1><p>Текст<br>строка</p><script>var a=1</script></body></html>"
        assertEquals("Заголовок\nТекст\nстрока", TtsText.fromFile("a.html", "text/html", html.toByteArray()))
    }

    @Test
    fun subtitlesDropTimecodesAndRepeats() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nПривет\n\n2\n00:00:02,000 --> 00:00:03,000\nПривет\nмир <i>тут</i>\n"
        assertEquals("Привет мир тут", TtsText.fromFile("a.srt", null, srt.toByteArray()))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n\n01:00:01.000 --> 01:00:02.000 align:start\nworld\n"
        assertEquals("Hello world", TtsText.fromFile("a.vtt", null, vtt.toByteArray()))
    }

    @Test
    fun docxAndEpubAreUnzipped() {
        val docx = zip("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>Один</w:t></w:r><w:r><w:tab/><w:t>два</w:t></w:r></w:p><w:p><w:r><w:t>Три &amp; четыре</w:t></w:r></w:p></w:body></w:document>")
        assertEquals("Один два\nТри & четыре", TtsText.fromFile("a.docx", null, docx))
        val epub = zip(
            "mimetype" to "application/epub+zip",
            "OEBPS/content.opf" to "<package><manifest><item id=\"b\" href=\"ch%202.xhtml\"/><item id=\"a\" href=\"ch1.xhtml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>",
            "OEBPS/ch 2.xhtml" to "<html><body><p>Вторая</p></body></html>",
            "OEBPS/ch1.xhtml" to "<html><body><p>Первая</p></body></html>"
        )
        assertEquals("Первая\n\nВторая", TtsText.fromFile("b.epub", null, epub))
    }

    @Test
    fun markdownAndPdf() {
        assertEquals("Заголовок\nжирный и ссылка", TtsText.fromFile("a.md", null, "# Заголовок\n**жирный** и [ссылка](http://x)".toByteArray()))
        assertTrue(runCatching { TtsText.fromFile("a.pdf", null, ByteArray(3)) }.isFailure)
        assertEquals("a b\n\nc", TtsText.fromFile("a.txt", "text/plain", "a   b\n\n\n\nc  ".toByteArray()))
    }
}

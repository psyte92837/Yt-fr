/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    private val vtt = "WEBVTT\nKind: captions\nLanguage: ru\n\n" +
        "00:00:01.000 --> 00:00:03.000 align:start\nПривет <c>мир</c>\n\n" +
        "00:00:03.000 --> 00:00:05.000\nПривет мир\nкак дела\n\n" +
        "01:02:03.000 --> 01:02:05.000\nфинал &amp; конец\n"

    @Test
    fun vttRemovesTagsAndRollingDuplicates() {
        val txt = Vtt.toText(vtt)
        assertEquals(3, txt.lines().size)
        assertTrue(txt.startsWith("[00:01] Привет мир"))
        assertTrue(txt.contains("[1:02:03] финал & конец"))
        assertTrue(Vtt.stripTimecodes(txt).startsWith("Привет мир как дела"))
    }

    @Test
    fun jsonExtractionHandlesFencesAndBracesInStrings() {
        assertEquals("{\"a\":{\"b\":\"}\"}}", JsonText.firstObject("```json\n{\"a\":{\"b\":\"}\"}}\n```"))
        assertNull(JsonText.firstObject("no json here"))
    }

    @Test
    fun wordsSkipStopWords() {
        val top = Words.top(sequenceOf("Электромобили Tesla и электромобили BYD", "электромобили в России the best"), 3)
        assertEquals("электромобили" to 3, top.first())
        assertFalse(top.any { it.first == "the" })
        assertEquals("зарядные станции", Words.topBigrams(sequenceOf("зарядные станции", "зарядные станции новые")).first().first)
    }

    @Test
    fun chunksRespectMaxLength() {
        val chunks = Chunks.split("a".repeat(50) + "\n" + "b".repeat(50), 60)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 60 })
    }

    @Test
    fun youtubeHelpers() {
        assertEquals("dQw4w9WgXcQ", YtQuery.videoIdFromUrl("https://youtu.be/dQw4w9WgXcQ?t=3"))
        assertEquals("abcdefghijk", YtQuery.videoIdFromUrl("https://www.youtube.com/shorts/abcdefghijk"))
        assertEquals("dQw4w9WgXcQ", YtQuery.videoIdFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=x"))
        assertTrue(YtQuery.durationOk("medium", 600))
        assertFalse(YtQuery.durationOk("short", 600))
        assertEquals(1000L - 3_600_000L, YtQuery.periodStart("hour", 1000L))
    }

    @Test
    fun csvEscaping() {
        assertEquals("\"a,b\",\"q\"\"x\"\r\n", Csv.row("a,b", "q\"x"))
    }
}

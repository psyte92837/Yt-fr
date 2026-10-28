/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class StyleTest {
    private val autoVtt = "WEBVTT\nKind: captions\nLanguage: ru\n\n" +
        "00:00:00.500 --> 00:00:02.000\nпривет <00:00:01.000><c>всем</c>\n\n" +
        "00:00:02.000 --> 00:00:02.010\nпривет всем\n\n" +
        "00:00:02.010 --> 00:00:04.000\nпривет всем\nкак сделать монтаж?\n\n" +
        "00:00:10.000 --> 00:00:12.000\nвторая часть ролика\n\n" +
        "00:00:40.000 --> 00:00:41.000\nконец\n"

    @Test
    fun parsesTimes() {
        assertEquals(1_500L, StyleMath.parseTime("00:01.500"))
        assertEquals(3_723_250L, StyleMath.parseTime("01:02:03.250"))
        assertEquals(61_000L, StyleMath.parseTime("00:01:01,000"))
    }

    @Test
    fun cuesDropRollingDuplicatesAndTags() {
        val cues = StyleMath.cues(autoVtt)
        assertEquals(listOf("привет всем", "как сделать монтаж?", "вторая часть ролика", "конец"), cues.map { it.text })
        assertEquals(500L, cues[0].startMs)
        assertEquals(2_010L, cues[1].startMs)
    }

    @Test
    fun speechPaceAndHook() {
        val s = StyleMath.speech(StyleMath.cues(autoVtt), 60)!!
        assertEquals(9, s.words)
        assertEquals(1, s.questions)
        assertEquals(500L, s.firstSpeechMs)
        assertEquals(2, s.longPauses) // 4 s → 10 s and 12 s → 40 s
        assertTrue(s.hook.startsWith("привет всем как сделать монтаж?"))
        assertFalse(s.hook.contains("конец"))
        // speech 0.5–2 + 2.01–4 + 10–12 + 40–41 = 6.49 s of 60
        assertEquals(6.49 / 60, s.speechShare, 0.001)
        assertEquals((9 * 60000.0 / 6490).roundToInt(), s.wpm)
        assertEquals(null, StyleMath.speech(emptyList(), 10))
    }

    @Test
    fun titlePatterns() {
        val tags = StyleMath.titleTags("Как я смонтировал 10 видео за день? (ЛАЙФХАК) 🔥")
        assertTrue(tags.containsAll(listOf("number", "question", "brackets", "caps", "emoji", "how_why")))
        assertTrue(StyleMath.titleTags("simple title").isEmpty())
        assertTrue("versus" in StyleMath.titleTags("iPhone vs Pixel"))
    }

    @Test
    fun descriptionCounters() {
        val d = "Таймкоды:\n0:00 Вступление\n1:25 Монтаж\n(12:40) Итоги\nhttps://a.b/c и https://x.y\n#монтаж #видео"
        assertEquals(3, StyleMath.timecodes(d))
        assertEquals(2, StyleMath.links(d))
        assertEquals(2, StyleMath.hashtags(d))
    }

    private fun solid(c: Int, w: Int = 16, h: Int = 9) = IntArray(w * h) { c }

    @Test
    fun frameColoursAndCuts() {
        val black = StyleMath.frame(solid(0x000000), 16, 9)
        val white = StyleMath.frame(solid(0xFFFFFF), 16, 9)
        val red = StyleMath.frame(solid(0xFF0000), 16, 9)
        assertEquals(0, black.luma)
        assertEquals(255, white.luma)
        assertEquals(0, white.sat)
        assertEquals(100, red.sat)
        assertEquals(0xFF0000, red.rgb)
        assertEquals(255, StyleMath.diff(black, white))
        assertEquals(0, StyleMath.cuts(listOf(black, black, black)))
        assertEquals(2, StyleMath.cuts(listOf(black, black, white, white, black)))
    }

    @Test
    fun paletteKeepsDominantColours() {
        val px = IntArray(100) { if (it < 70) 0x101010 else if (it < 98) 0xF01010 else 0x10F010 }
        val pal = StyleMath.palette(px)
        assertEquals(2, pal.size) // the 2 % green is below the 3 % threshold
        assertEquals("#101010", StyleMath.hex(pal[0]))
        assertEquals("#F01010", StyleMath.hex(pal[1]))
        assertTrue(StyleMath.palette(IntArray(0)).isEmpty())
    }

    @Test
    fun metricsJsonRoundTrip() {
        val m = StyleMetrics(
            videoId = "abcdefghijk", duration = 600, chapters = listOf(0 to "Intro", 90 to "Main"), wpm = 150, words = 1200,
            speechShare = 0.8, frames = 61, frameStepMs = 10_000, cuts = 20, luma = 120, sat = 40, palette = listOf("#101010"),
            titleLen = 42, titleTags = listOf("number"), timecodes = 2
        )
        val back = StyleMetrics.from(JSONObject(m.toJson().toString()))
        assertEquals(m, back)
        assertEquals(2.0, back.cutsPerMin, 0.001) // 20 changes in 600 s
        assertEquals(600.0 / 21, back.avgShotSec, 0.001)
        assertTrue(back.lines().any { it.contains("150") })
    }

    @Test
    fun miniThumbnails() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            ThumbUrl.mini("x", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg?sqp=abc"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            ThumbUrl.mini("x", "https://i9.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", ThumbUrl.mini("dQw4w9WgXcQ", ""))
        assertEquals("https://yt3.ggpht.com/avatar", ThumbUrl.mini("dQw4w9WgXcQ", "https://yt3.ggpht.com/avatar"))
        assertEquals("", ThumbUrl.mini("src_local", ""))
    }

    @Test
    fun tokenEstimate() {
        assertEquals(600 * 100 + 3500, styleTokenEstimate(600, true))
        assertEquals(6500, styleTokenEstimate(600, false))
    }
}

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

class StudioPrompterTest {
    private val tokens = listOf("Один", "два", "три.", "Четыре", "пять,", "шесть")

    @Test
    fun clockLightsWordsInOrderAndLogsThem() {
        val c = PrompterClock(tokens, 120)
        assertTrue(c.tick(0))
        assertEquals(0, c.index)
        var ms = 0L
        while (!c.done && ms < 60_000) {
            ms += 80
            c.tick(ms)
        }
        assertEquals(tokens.lastIndex, c.index)
        // every word lit up once, in order
        assertEquals(tokens.indices.toList(), c.log.map { it.word })
        assertTrue(c.log.zipWithNext().all { (a, b) -> a.ms <= b.ms })
        // 6 words at 120 wpm take about 3 s
        assertTrue(ms in 2_000L..4_500L)
    }

    @Test
    fun fasterSpeedFinishesSooner() {
        fun timeAt(wpm: Int): Long {
            val c = PrompterClock(tokens, wpm)
            var ms = 0L
            while (!c.done) { ms += 50; c.tick(ms) }
            return ms
        }
        assertTrue(timeAt(200) < timeAt(100))
    }

    @Test
    fun pauseStopsAndJumpMoves() {
        val c = PrompterClock(tokens, 120)
        c.tick(0)
        c.togglePause(100)
        assertTrue(c.paused)
        c.tick(5_000)
        assertEquals(0, c.index)
        c.togglePause(5_000)
        assertTrue(c.jump(4, 5_100))
        assertEquals(4, c.index)
        assertEquals(PromptMark(4, 5_100), c.log.last())
        assertFalse(c.jump(99, 5_200))
    }

    @Test
    fun speedStaysInLimits() {
        val c = PrompterClock(tokens, 1000)
        assertEquals(StudioPrompter.MAX_WPM, c.wpm)
        c.wpm = 1
        assertEquals(StudioPrompter.MIN_WPM, c.wpm)
    }

    @Test
    fun sentenceEndWeighsMore() {
        assertTrue(StudioPrompter.weight("три.") > StudioPrompter.weight("три"))
        assertTrue(StudioPrompter.weight("пять,") > StudioPrompter.weight("пять"))
    }

    @Test
    fun layoutGivesWordOffsets() {
        val (text, ranges) = StudioPrompter.layout(listOf(listOf("Раз", "два"), listOf("Три")))
        assertEquals("Раз два\n\nТри", text)
        assertEquals("два", text.substring(ranges[1].first, ranges[1].last + 1))
        assertEquals("Три", text.substring(ranges[2].first, ranges[2].last + 1))
        assertEquals(1, StudioPrompter.wordAt(ranges, 5))
        assertEquals(2, StudioPrompter.wordAt(ranges, 9))
    }

    @Test
    fun wordsFollowTheLog() {
        val log = tokens.indices.map { PromptMark(it, 1_000L + it * 500) }
        val w = StudioPrompter.words(tokens, log, emptyList(), 10_000)
        assertEquals(tokens.size, w.size)
        assertEquals(1_000L + StudioPrompter.REACT_MS, w[0].startMs)
        assertEquals(3_500L + StudioPrompter.REACT_MS, w[5].startMs)
        assertTrue(w.zipWithNext().all { (a, b) -> a.endMs <= b.startMs && a.startMs < b.startMs })
    }

    @Test
    fun missingAndReReadWordsAreHandled() {
        // word 2 skipped by a jump; words 3..5 read twice, the second pass counts
        val log = listOf(PromptMark(0, 0), PromptMark(1, 400), PromptMark(3, 1_200), PromptMark(4, 1_600), PromptMark(5, 2_000),
            PromptMark(3, 3_000), PromptMark(4, 3_400), PromptMark(5, 3_800))
        val w = StudioPrompter.words(tokens, log, emptyList(), 10_000)
        assertEquals(3_000L + StudioPrompter.REACT_MS, w[3].startMs)
        assertTrue(w[2].startMs > w[1].startMs && w[2].startMs < w[3].startMs)
        assertTrue(StudioPrompter.words(tokens, emptyList(), emptyList(), 10_000).isEmpty())
    }

    @Test
    fun wordsSnapToSpeechAndFollowCuts() {
        val speech = listOf(Span(1_000, 2_000))
        assertEquals(1_000L, StudioPrompter.snap(700, speech))
        assertEquals(100L, StudioPrompter.snap(100, speech))
        assertEquals(1_500L, StudioPrompter.snap(1_500, speech))
        val keep = listOf(Span(0, 1_000), Span(3_000, 4_000))
        assertEquals(500L, StudioPrompter.mapCut(500, keep))
        assertEquals(1_000L, StudioPrompter.mapCut(2_000, keep))
        assertEquals(1_500L, StudioPrompter.mapCut(3_500, keep))
        assertEquals(2_000L, StudioPrompter.mapCut(9_000, keep))
    }

    @Test
    fun logIsKeptOnlyForItsScriptAndTake() {
        val log = listOf(PromptMark(0, 10), PromptMark(1, 500))
        val json = StudioPrompter.toJson(tokens, log, 20_000)
        assertEquals(log, StudioPrompter.fromJson(json, tokens, 20_300))
        assertNull(StudioPrompter.fromJson(json, tokens.drop(1), 20_000))
        assertNull(StudioPrompter.fromJson(json, tokens, 30_000))
        assertNull(StudioPrompter.fromJson("bad", tokens, 20_000))
    }
}

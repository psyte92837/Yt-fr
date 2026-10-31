/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioBeatsTest {
    @Test
    fun beatsCoverSpeech() {
        val w = Studio.beatWords(listOf(Span(0, 2400), Span(3000, 3500)), 4000)
        assertEquals(3, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(2400L, w[1].endMs)
        assertEquals(3000L, w[2].startMs)
        assertTrue(w.all { it.text.isEmpty() && it.endMs > it.startMs })
    }

    @Test
    fun noSpeechUsesWholeTake() {
        val w = Studio.beatWords(emptyList(), 6000)
        assertEquals(5, w.size)
        assertEquals(6000L, w.last().endMs)
        assertTrue(Studio.beatWords(emptyList(), 0).isEmpty())
    }

    @Test
    fun rhythmTemplateWorksWithoutText() {
        val w = Studio.beatWords(listOf(Span(0, 9000)), 9000)
        val d = StudioRhythm.direct(w, FloatArray(w.size) { it.toFloat() })
        assertTrue(d.scenes.isNotEmpty())
        val res = StudioDirector.apply(d, w, 9000, emptySet())
        assertEquals(w.size, res.ranges.sumOf { it.count() })
    }
}

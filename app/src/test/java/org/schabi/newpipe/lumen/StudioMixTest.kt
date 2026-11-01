/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMixTest {

    @Test
    fun cutsClipsIntoEqualPieces() {
        val p = StudioMix.pieces("c1", 10_000, 2_000)
        assertEquals(5, p.size)
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L), p.map { it.fromMs })
        assertTrue(p.all { it.lenMs == 2_000L })
        // 7 s by 2 s → 3 or 4 equal parts, never past the end
        val q = StudioMix.pieces("c2", 7_000, 2_000)
        assertTrue(q.last().fromMs + q.last().lenMs <= 7_000)
        assertEquals(1, StudioMix.pieces("c3", 900, 2_000).size)
        assertTrue(StudioMix.pieces("c4", 100, 2_000).isEmpty())
    }

    @Test
    fun mixHasNoNeighboursFromOneClip() {
        val clips = listOf("c1" to 10_000L, "c2" to 6_000L, "c3" to 4_000L, "c4" to 2_000L)
        for (seed in 1L..50L) {
            val m = StudioMix.mix(clips, 2_000, seed)
            assertEquals(5 + 3 + 2 + 1, m.size)
            assertEquals(0, StudioMix.repeats(m))
            // every piece once
            assertEquals(m.size, m.map { it.key }.toSet().size)
        }
    }

    @Test
    fun mixDependsOnSeed() {
        val clips = listOf("c1" to 10_000L, "c2" to 10_000L, "c3" to 10_000L)
        val a = StudioMix.mix(clips, 1_000, 1)
        val b = StudioMix.mix(clips, 1_000, 2)
        assertTrue(a.map { it.key } != b.map { it.key })
        assertEquals(a.map { it.key }, StudioMix.mix(clips, 1_000, 1).map { it.key })
    }

    @Test
    fun oneBigClipStillMixesAsWellAsPossible() {
        val m = StudioMix.mix(listOf("c1" to 10_000L, "c2" to 2_000L), 2_000, 7)
        assertEquals(6, m.size)
        // 5 pieces of c1 and 1 of c2: at most 3 neighbours can be avoided
        assertTrue(StudioMix.repeats(m) <= 3)
    }

    @Test
    fun movesAndRemoves() {
        val items = listOf(MixItem("a", 0, 1000), MixItem("b", 0, 1000, marked = true), MixItem("c", 0, 1000))
        assertEquals(listOf("b", "c", "a"), StudioMix.move(items, 0, 2).map { it.clipId })
        assertEquals(listOf("c", "a", "b"), StudioMix.move(items, 2, 0).map { it.clipId })
        assertEquals(items, StudioMix.move(items, 0, 5))
        assertEquals(listOf("a", "c"), StudioMix.removeMarked(items).map { it.clipId })
        assertEquals(listOf(1), StudioMix.chosen(items, true))
        assertEquals(listOf(0, 1, 2), StudioMix.chosen(items, false))
        assertEquals(listOf("a", "c"), StudioMix.keepClips(items, setOf("a", "c")).map { it.clipId })
    }

    @Test
    fun timesAndSlotsGoOneAfterAnother() {
        val items = listOf(MixItem("c1", 2_000, 1_500), MixItem("c2", 0, 2_000), MixItem("c1", 0, 1_000))
        val t = StudioMix.times(items)
        assertEquals(listOf(0L, 1_500L, 3_500L), t.map { it.startMs })
        assertEquals(4_500L, t.last().endMs)
        assertEquals(4_500L, StudioMix.totalMs(items))
        val clips = mapOf("c1" to SClip("c1", "u", 0, 5_000, file = "c1.mp4", leadMs = 300, lenMs = 5_000), "c2" to SClip("c2", "u", 0, 3_000, file = "c2.mp4", leadMs = 0, lenMs = 3_000))
        val s = StudioMix.slots(items, clips)
        assertEquals(3, s.size)
        assertEquals(2_000L, s[0].offsetMs)
        // the file time at the start of the first element is the lead plus its start in the clip
        assertEquals(2_300L, StudioCuts.fileTime(s[0], 0))
        assertEquals(300L, StudioCuts.fileTime(s[2], 3_500))
        // a clip that is not downloaded gets no slot
        assertEquals(2, StudioMix.slots(items, clips - "c2").size)
    }

    @Test
    fun wordsGoToTheirElements() {
        val items = listOf(MixItem("a", 0, 1_000), MixItem("b", 0, 1_000), MixItem("c", 0, 1_000))
        val words = listOf(SWord("раз", 100, 400), SWord("два", 500, 900), SWord("три", 1_200, 1_500), SWord("четыре", 3_200, 3_600))
        val r = StudioMix.wordsIn(words, StudioMix.times(items))
        assertEquals(0..1, r[0])
        assertEquals(2..2, r[1])
        // the last element takes the words after the end of the mix
        assertEquals(3..3, r[2])
        assertTrue(StudioMix.wordsIn(emptyList(), StudioMix.times(items)).all { it.isEmpty() })
    }

    @Test
    fun savesAndReads() {
        val s = SScheme("chain", "Как", listOf(SchNode("a", label = "Один"), SchNode("b", label = "Два")))
        val items = listOf(MixItem("c1", 500, 2_000, marked = true, scheme = s), MixItem("c2", 0, 1_000))
        val back = StudioMix.fromJson(StudioMix.toJson(items))
        assertEquals(2, back.size)
        assertEquals("c1", back[0].clipId)
        assertEquals(500L, back[0].fromMs)
        assertTrue(back[0].marked)
        assertNotNull(back[0].scheme)
        assertEquals("chain", back[0].scheme!!.layout)
        assertEquals(null, back[1].scheme)
        assertTrue(StudioMix.fromJson("nonsense").isEmpty())
        val w = listOf(SWord("привет", 10, 300))
        assertEquals(w, StudioMix.wordsFromJson(StudioMix.wordsJson(w)))
    }

    @Test
    fun promptHasFormatAndTimeline() {
        val items = listOf(MixItem("c1", 0, 2_000), MixItem("c2", 0, 1_500))
        val p = StudioMix.montagePrompt("9:16", items, mapOf("c1" to "кот прыгает"), 3_500, ru = true)
        assertTrue(p.contains("9:16"))
        assertTrue(p.contains("0:00–0:02 c1 «кот прыгает»"))
        assertTrue(p.contains("0:02–0:03.5 c2"))
        assertTrue(StudioMix.montagePrompt("16:9", items, emptyMap(), 3_500, ru = false).contains("16:9"))
    }

    @Test
    fun signatureFollowsOrderAndFormat() {
        val a = listOf(MixItem("c1", 0, 1_000), MixItem("c2", 0, 1_000))
        assertTrue(StudioMix.signature(a, "9:16") != StudioMix.signature(a.reversed(), "9:16"))
        assertTrue(StudioMix.signature(a, "9:16") != StudioMix.signature(a, "16:9"))
        // marks and schemes do not change the picture order
        assertEquals(StudioMix.signature(a, "9:16"), StudioMix.signature(a.map { it.copy(marked = true) }, "9:16"))
    }
}

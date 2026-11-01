/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTrackTest {

    private val whole = EditTrack.initial(10_000)

    @Test
    fun theWholeVideoIsPlain() {
        assertTrue(EditTrack.plain(whole, 10_000))
        assertEquals(10_000L, EditTrack.total(whole))
        assertFalse(EditTrack.plain(EditTrack.update(whole, 0) { it.copy(speed = 2f) }, 10_000))
        assertFalse(EditTrack.plain(EditTrack.split(whole, 4_000)!!.first, 10_000))
    }

    @Test
    fun splitMakesTwoPiecesAtThePlayhead() {
        val (list, right) = EditTrack.split(whole, 4_000)!!
        assertEquals(2, list.size)
        assertEquals(1, right)
        assertEquals(4_000L, list[0].toMs)
        assertEquals(4_000L, list[1].fromMs)
        assertEquals(10_000L, EditTrack.total(list))
        assertTrue(list[0].id != list[1].id)
        // not at the very edge
        assertNull(EditTrack.split(whole, 50))
        assertNull(EditTrack.split(list, 4_000))
    }

    @Test
    fun deleteAndReorder() {
        val (two, _) = EditTrack.split(whole, 4_000)!!
        val (three, _) = EditTrack.split(two, 7_000)!!
        val cut = EditTrack.remove(three, 1)!!
        assertEquals(7_000L, EditTrack.total(cut))
        assertEquals(listOf(0L, 7_000L), cut.map { it.fromMs })
        assertNull(EditTrack.remove(whole, 0))
        val (moved, j) = EditTrack.move(three, 2, -1)!!
        assertEquals(1, j)
        assertEquals(listOf(0L, 7_000L, 4_000L), moved.map { it.fromMs })
        assertNull(EditTrack.move(three, 0, -1))
        val (dup, k) = EditTrack.duplicate(three, 0)!!
        assertEquals(1, k)
        assertEquals(14_000L, EditTrack.total(dup))
    }

    @Test
    fun speedChangesLengthAndMapping() {
        val fast = EditTrack.update(whole, 0) { it.copy(speed = 2f) }
        assertEquals(5_000L, EditTrack.total(fast))
        assertEquals(6_000L, EditTrack.locate(fast, 3_000).srcMs)
        assertEquals(3_000L, EditTrack.outOf(fast, 0, 6_000))
        assertEquals(EditTrack.MAX_SPEED, EditTrack.update(whole, 0) { it.copy(speed = 100f) }[0].speed)
    }

    @Test
    fun freezeInsertsAStill() {
        val (list, j) = EditTrack.freeze(whole, 4_000)!!
        assertEquals(3, list.size)
        assertEquals(1, j)
        assertTrue(list[1].isHold)
        assertEquals(4_000L, list[1].fromMs)
        assertEquals(10_000L + EditTrack.HOLD_MS, EditTrack.total(list))
        // the same moment of the video before and after the still
        assertEquals(4_000L, EditTrack.locate(list, 4_000 + EditTrack.HOLD_MS).srcMs)
        assertNull(EditTrack.freeze(list, 4_500))
    }

    @Test
    fun trimToThePlayhead() {
        val (two, _) = EditTrack.split(whole, 4_000)!!
        val a = EditTrack.trimStart(two, 1, 6_000)!!
        assertEquals(6_000L, a[1].fromMs)
        val b = EditTrack.trimEnd(two, 0, 3_000)!!
        assertEquals(3_000L, b[0].toMs)
        assertNull(EditTrack.trimEnd(two, 0, 6_000))
    }

    @Test
    fun blocksFollowTheirPieces() {
        val (two, _) = EditTrack.split(whole, 4_000)!!
        val (three, _) = EditTrack.split(two, 6_000)!!
        val cut = EditTrack.remove(three, 1)!!
        // after the cut of 4..6 s a moment at 8 s moves to 6 s
        assertEquals(6_000L, EditTrack.remap(three, cut, 8_000))
        // a moment inside the cut goes to where the next piece starts
        assertEquals(4_000L, EditTrack.remap(three, cut, 5_000))
        val blocks = listOf(EBlock("x", "text", 7_000, 2_000), EBlock("y", "text", 1_000, 1_000))
        val moved = EditTrack.remapBlocks(blocks, three, cut)
        assertEquals(5_000L, moved[0].startMs)
        assertEquals(2_000L, moved[0].durMs)
        assertEquals(1_000L, moved[1].startMs)
        // speed: a block stretches with its piece
        val slow = EditTrack.update(whole, 0) { it.copy(speed = 0.5f) }
        val st = EditTrack.remapBlocks(listOf(EBlock("z", "text", 2_000, 1_000)), whole, slow)[0]
        assertEquals(4_000L, st.startMs)
        assertEquals(2_000L, st.durMs)
    }

    @Test
    fun wordsInOutputTime() {
        val ws = listOf(SWord("один", 1_000, 1_500), SWord("два", 5_000, 5_400), SWord("три", 8_000, 8_300))
        assertEquals(ws, EditTrack.words(ws, whole, 10_000))
        val (two, _) = EditTrack.split(whole, 4_000)!!
        val (three, _) = EditTrack.split(two, 6_000)!!
        val cut = EditTrack.remove(three, 1)!!
        val out = EditTrack.words(ws, cut, 10_000)
        assertEquals(listOf("один", "три"), out.map { it.text })
        assertEquals(6_000L, out[1].startMs)
        val (moved, _) = EditTrack.move(cut, 1, -1)!!
        assertEquals(listOf("три", "один"), EditTrack.words(ws, moved, 10_000).map { it.text })
    }

    @Test
    fun jsonRoundTripAndProject() {
        val (list, _) = EditTrack.freeze(EditTrack.update(whole, 0) { it.copy(speed = 1.5f, volume = 0.5f, mirror = true, zoom = 1.25f) }, 3_000)!!
        assertEquals(list, EditTrack.from(EditTrack.json(list)))
        val p = EProject("e1", "src", 1080, 1920, 10_000, clips = list)
        val back = StudioEdit.fromJson(StudioEdit.toJson(p).toString())
        assertNotNull(back)
        assertEquals(list, back!!.clips)
        assertEquals(EditTrack.total(list), EditTrack.total(EditTrack.of(back)))
        // an old project without pieces is the whole video
        assertEquals(whole, EditTrack.of(p.copy(clips = emptyList())))
    }

    @Test
    fun labels() {
        assertEquals("2×", EditTrack.speedLabel(2f))
        assertEquals("0.5×", EditTrack.speedLabel(0.5f))
        assertEquals("1.25×", EditTrack.speedLabel(1.25f))
        assertEquals("", EditTrack.badges(whole[0]))
        assertTrue(EditTrack.badges(whole[0].copy(volume = 0f)).contains("🔇"))
    }

    @Test
    fun dragAPieceToAnotherPlace() {
        val (two, _) = EditTrack.split(whole, 4_000)!!
        val (three, _) = EditTrack.split(two, 7_000)!!
        // the first piece dropped after the last one
        val to = EditTrack.dropIndex(0, 3)
        assertEquals(2, to)
        val (moved, j) = EditTrack.moveTo(three, 0, to)!!
        assertEquals(2, j)
        assertEquals(listOf(4_000L, 7_000L, 0L), moved.map { it.fromMs })
        // the last one dropped first
        assertEquals(listOf(7_000L, 0L, 4_000L), EditTrack.moveTo(three, 2, EditTrack.dropIndex(2, 0))!!.first.map { it.fromMs })
        assertNull(EditTrack.moveTo(three, 1, EditTrack.dropIndex(1, 2)))
    }

    @Test
    fun splitScreenHalvesAndCover() {
        val (main, second) = EditSplit.halves("tb")
        assertEquals(FRect(0f, 0f, 1f, 0.5f), main)
        assertEquals(FRect(0f, 0.5f, 1f, 1f), second)
        assertEquals(FRect(0.5f, 0f, 1f, 1f), EditSplit.halves("rl").first)
        // a 9:16 frame 1080×1920, its top half 1080×960: the same video covers it at full size, centred
        val c = EditSplit.cover(1080f, 1920f, main, 1080f, 1920f)
        assertEquals(0f, c.l, 0.01f)
        assertEquals(1080f, c.w, 0.01f)
        assertEquals(1920f, c.h, 0.01f)
        assertEquals(480f, (c.t + c.b) / 2, 0.01f)
        // a landscape video in the bottom half: as high as the half, wider than the frame
        val l = EditSplit.cover(1920f, 1080f, second, 1080f, 1920f)
        assertEquals(960f, l.h, 0.01f)
        assertTrue(l.w > 1080f)
        val b = EBlock("s", "split", 2_000, 5_000, file = "img/v.mp4", srcMs = 1_000)
        assertEquals(b, EditSplit.active(listOf(b), 3_000))
        assertNull(EditSplit.active(listOf(b), 7_000))
        assertEquals(2_500L, EditSplit.srcAt(b, 3_500))
        assertTrue(EditSplit.overlays(listOf(b)).isEmpty())
    }
}

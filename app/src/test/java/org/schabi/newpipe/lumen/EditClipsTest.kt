/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several videos in one project: pieces of other videos on the main track. */
class EditClipsTest {

    private val base = EditTrack.initial(10_000)
    private val other = VClip("x", 0, 4_000, src = "img/c1.mp4")

    @Test
    fun aClipGoesToTheEnd() {
        val (list, j) = EditTrack.insert(base, 2_000, other, atEnd = true)
        assertEquals(2, list.size)
        assertEquals(1, j)
        assertEquals("img/c1.mp4", list[1].src)
        assertEquals(14_000L, EditTrack.total(list))
        // ids stay unique
        assertEquals(list.size, list.map { it.id }.toSet().size)
    }

    @Test
    fun aClipAtThePlayheadCutsThePieceThere() {
        val (list, j) = EditTrack.insert(base, 3_000, other, atEnd = false)
        assertEquals(3, list.size)
        assertEquals(1, j)
        assertEquals(3_000L, list[0].toMs)
        assertEquals("img/c1.mp4", list[1].src)
        assertEquals(3_000L, list[2].fromMs)
        assertEquals(list.size, list.map { it.id }.toSet().size)
        // at the very start: before the first piece; at the very end: after the last
        assertEquals(0, EditTrack.insert(base, 0, other, false).second)
        assertEquals(1, EditTrack.insert(base, 10_000, other, false).second)
    }

    @Test
    fun anotherVideoMakesTheTrackAndItsSoundNotPlain() {
        val (list, _) = EditTrack.insert(base, 0, other, atEnd = true)
        assertFalse(EditTrack.plain(list, 10_000))
        assertFalse(EditTrack.soundPlain(list, 10_000))
        assertEquals(listOf("", "img/c1.mp4"), EditTrack.sources(list))
        assertEquals(listOf(""), EditTrack.sources(base))
    }

    @Test
    fun theVoiceStaysWithTheBaseVideo() {
        val words = listOf(SWord("раз", 500, 900), SWord("два", 1_500, 1_900))
        val (list, _) = EditTrack.insert(base, 0, other, atEnd = false)
        val out = EditTrack.words(words, list, 10_000)
        // the other video comes first: the words of the base move by its length
        assertEquals(listOf(4_500L, 5_500L), out.map { it.startMs })
    }

    @Test
    fun blocksStayOnTheirVideoWhenAClipIsPutBefore() {
        val b = EBlock("t1", "text", 1_000, 2_000, text = "hi")
        val (list, _) = EditTrack.insert(base, 0, other, atEnd = false)
        val moved = EditTrack.remapBlocks(listOf(b), base, list)
        assertEquals(5_000L, moved[0].startMs)
        assertEquals(2_000L, moved[0].durMs)
    }

    @Test
    fun sourcesAreKeptInsideTheProject() {
        assertEquals("img/c1.mp4", EditTrack.safeSrc("img/c1.mp4"))
        assertEquals("", EditTrack.safeSrc("/sdcard/x.mp4"))
        assertEquals("", EditTrack.safeSrc("../../x.mp4"))
        val back = EditTrack.from(EditTrack.json(listOf(other)))
        assertEquals(listOf(other), back)
        assertTrue(EditTrack.from(org.json.JSONArray("[{\"id\":\"c\",\"from\":0,\"to\":5,\"src\":\"/etc/passwd\"}]"))[0].src.isEmpty())
    }
}

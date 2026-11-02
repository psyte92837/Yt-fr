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

class EditCaptionsTest {
    private fun w(t: String, s: Long, e: Long) = SWord(t, s, e)

    @Test
    fun wordsAreGroupedBySizePauseAndSentence() {
        val words = listOf(w("Привет", 0, 300), w("всем.", 350, 700), w("Сегодня", 800, 1100), w("мы", 1150, 1250),
            w("разберём", 1300, 1700), w("монтаж", 1750, 2100), w("потом", 4000, 4300))
        val c = EditCaptions.cues(words, 3)
        assertEquals(listOf("Привет всем.", "Сегодня мы разберём", "монтаж", "потом"), c.map { it.text })
        assertEquals(0L, c[0].startMs)
        // never runs into the next caption
        for (i in 0 until c.size - 1) assertTrue(c[i].endMs <= c[i + 1].startMs)
        assertEquals(7, EditCaptions.cues(words, 1).size)
    }

    @Test
    fun shortWordsStillShowLongEnough() {
        val c = EditCaptions.cues(listOf(w("да", 1000, 1050)), 3)
        assertEquals(1400L, c.single().endMs)
        assertTrue(EditCaptions.cues(emptyList(), 3).isEmpty())
    }

    @Test
    fun subtitlesAreMarkedCaptions() {
        assertTrue(EditCaptions.isSub(EBlock("b1", "text", 0, 500, text = "x", ask = EditCaptions.TAG)))
        assertFalse(EditCaptions.isSub(EBlock("b2", "text", 0, 500, text = "x")))
    }
}

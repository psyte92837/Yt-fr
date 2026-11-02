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

class EditTurnTest {
    @Test
    fun aTurnedPieceSwapsItsSides() {
        assertEquals(1920 to 1080, EditCanvas.turned(1080, 1920, 90))
        assertEquals(1080 to 1920, EditCanvas.turned(1080, 1920, 180))
        assertEquals(1920 to 1080, EditCanvas.turned(1080, 1920, 270))
    }

    @Test
    fun theQuadTurnsClockwise() {
        val plain = EditCanvas.quad(false, 1f, 0)
        assertEquals(listOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), plain.toList())
        // the top-left corner of the picture (texture 0,1) goes to the top-right
        val q = EditCanvas.quad(false, 1f, 90)
        assertEquals(1f, q[4], 0f)
        assertEquals(1f, q[5], 0f)
        // four turns: back where it was
        assertEquals(plain.toList(), EditCanvas.quad(false, 1f, 360).toList())
        // mirrored: the left edge on the right
        assertEquals(1f, EditCanvas.quad(true, 1f, 0)[0], 0f)
        assertEquals(2f, EditCanvas.quad(false, 2f, 0)[2], 0f)
    }

    @Test
    fun turnAndBlurSurviveJson() {
        val c = VClip("c1", 0, 1000, rot = 270)
        assertEquals(listOf(c), EditTrack.from(EditTrack.json(listOf(c))))
        assertEquals(90, EditTrack.clean(c.copy(rot = 450)).rot)
        assertFalse(c.looksPlain)
        val p = EProject("e", "s", 1080, 1920, 1000, ratio = "16:9", blur = true)
        assertTrue(StudioEdit.fromJson(StudioEdit.toJson(p).toString())!!.blur)
    }
}

class EditKeysTest {
    private val b = EBlock("b1", "text", 1_000, 4_000, x = 0.2f, y = 0.5f, text = "hi")

    @Test
    fun withoutKeysTheBlockStandsStill() {
        assertEquals(b, EditKeys.at(b, 2_500))
    }

    @Test
    fun theBlockMovesSmoothlyBetweenKeys() {
        val k = EditKeys.set(EditKeys.set(b, 1_000, b), 3_000, b.copy(x = 0.8f, opacity = 0.5f))
        assertEquals(2, k.keys.size)
        assertEquals(0.2f, EditKeys.at(k, 500).x, 1e-4f)
        assertEquals(0.5f, EditKeys.at(k, 2_000).x, 1e-4f)
        assertEquals(0.8f, EditKeys.at(k, 3_000).x, 1e-4f)
        assertEquals(0.8f, EditKeys.at(k, 4_900).x, 1e-4f)
        // ease in-out: slower near the keys
        assertTrue(EditKeys.at(k, 1_200).x - 0.2f < 0.6f * 200 / 2000)
        assertEquals(0.75f, EditKeys.at(k, 2_000).opacity, 1e-4f)
    }

    @Test
    fun aKeyNearAnotherReplacesIt() {
        val k = EditKeys.set(EditKeys.set(b, 2_000, b), 2_050, b.copy(y = 0.9f))
        assertEquals(1, k.keys.size)
        assertEquals(0.9f, k.keys[0].y, 0f)
        assertEquals(k.keys[0], EditKeys.near(k, 2_100))
        assertTrue(EditKeys.remove(k, 2_000).keys.isEmpty())
    }

    @Test
    fun keysSurviveJsonAndMakeTheLayerMove() {
        val k = EditKeys.set(EditKeys.set(b, 1_000, b), 3_000, b.copy(x = 0.8f, rot = 30f))
        assertEquals(k, StudioEdit.blockFrom(StudioEdit.blockJson(k)))
        val still = StudioEdit.overlayKey(listOf(b), 2_500) { 0L }
        assertEquals(still, StudioEdit.overlayKey(listOf(b), 2_600) { 0L })
        assertFalse(StudioEdit.overlayKey(listOf(k), 2_500) { 0L } == StudioEdit.overlayKey(listOf(k), 2_600) { 0L })
    }
}

class EditDissolveTest {
    @Test
    fun thePictureBeforeFadesOutOverTheNextPiece() {
        val clips = listOf(VClip("a", 0, 2_000), VClip("b", 2_000, 5_000, trans = "dissolve", transMs = 1_000))
        assertEquals(null, EditColor.dissolveAt(clips, 1_900))
        assertEquals(1f, EditColor.dissolveAt(clips, 2_000)!!.first, 1e-4f)
        assertEquals(0.5f, EditColor.dissolveAt(clips, 2_500)!!.first, 1e-4f)
        assertEquals(0, EditColor.dissolveAt(clips, 2_500)!!.second)
        assertEquals(null, EditColor.dissolveAt(clips, 3_000))
        assertTrue(EditColor.dissolvesAfter(clips, 0))
        assertFalse(EditColor.dissolvesAfter(clips, 1))
        // a dissolve is not a dip to a colour
        assertEquals(null, EditColor.fadeAt(clips, 2_000))
        // on the first piece it fades in from black
        assertEquals(0xFF000000.toInt(), EditColor.fadeAt(listOf(VClip("a", 0, 2_000, trans = "dissolve")), 0)!!.color)
    }

    @Test
    fun mixIntoBlends() {
        val pix = intArrayOf((0xFF shl 24) or (200 shl 16))
        EditColor.mixInto(pix, intArrayOf((0xFF shl 24) or 100), 0.5f)
        assertEquals(100, (pix[0] shr 16) and 0xFF)
        assertEquals(50, pix[0] and 0xFF)
    }
}

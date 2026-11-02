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

class EditLookTest {

    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    @Test
    fun aPlainPieceHasNoColour() {
        val c = VClip("c0", 0, 1000)
        assertFalse(c.hasColor)
        assertNull(EditColor.matrix(c))
        // a filter at zero strength changes nothing either
        assertNull(EditColor.matrix(c.copy(filter = "bw", filterK = 0f)))
        assertNotNull(EditColor.matrix(c.copy(filter = "bw")))
        assertNotNull(EditColor.matrix(c.copy(bright = 0.3f)))
    }

    @Test
    fun everyFilterIsKnownAndChangesThePicture() {
        for (id in EditColor.FILTERS.filter { it.isNotEmpty() }) {
            assertFalse(id, EditColor.isIdentity(EditColor.filter(id)))
            assertTrue(id, EditColor.filterName(id).isNotBlank())
        }
        assertTrue(EditColor.isIdentity(EditColor.filter("")))
        assertTrue(EditColor.isIdentity(EditColor.filter("nonsense")))
    }

    @Test
    fun blackAndWhiteMakesGrey() {
        val pix = intArrayOf(px(200, 40, 90), px(10, 220, 30))
        EditColor.applyTo(pix, EditColor.filter("bw"))
        for (p in pix) {
            val (r, g, b) = ch(p)
            assertEquals(r, g)
            assertEquals(g, b)
        }
        // alpha kept
        assertEquals(0xFF, pix[0] ushr 24)
    }

    @Test
    fun slidersMoveTheColourTheRightWay() {
        val grey = px(100, 100, 100)
        fun after(c: VClip): Triple<Int, Int, Int> = intArrayOf(grey).also { EditColor.applyTo(it, EditColor.matrix(c)!!) }[0].let(::ch)
        val base = VClip("c0", 0, 1000)
        assertTrue(after(base.copy(bright = 0.5f)).first > 100)
        assertTrue(after(base.copy(bright = -0.5f)).first < 100)
        val warm = after(base.copy(warm = 1f))
        assertTrue(warm.first > warm.third)
        val cool = after(base.copy(warm = -1f))
        assertTrue(cool.third > cool.first)
        // contrast pushes away from the middle
        val dark = intArrayOf(px(60, 60, 60)).also { EditColor.applyTo(it, EditColor.matrix(base.copy(contrast = 0.8f))!!) }[0]
        assertTrue(ch(dark).first < 60)
    }

    @Test
    fun valuesAreClampedTo0To255() {
        val pix = intArrayOf(px(250, 250, 250), px(3, 3, 3))
        EditColor.applyTo(pix, EditColor.brightness(200f))
        assertEquals(255, ch(pix[0]).first)
        EditColor.applyTo(pix, EditColor.brightness(-600f))
        assertEquals(0, ch(pix[1]).second)
    }

    @Test
    fun concatAppliesTheRightOneFirst() {
        // grey then brighter: still grey; the same as doing it by hand
        val m = EditColor.concat(EditColor.brightness(20f), EditColor.saturation(0f))
        val a = intArrayOf(px(200, 40, 90))
        EditColor.applyTo(a, m)
        val b = intArrayOf(px(200, 40, 90))
        EditColor.applyTo(b, EditColor.saturation(0f))
        EditColor.applyTo(b, EditColor.brightness(20f))
        assertEquals(b[0], a[0])
    }

    @Test
    fun glMatrixIsColumnMajorWithOffsetsOn0To1() {
        val m = EditColor.identity()
        m[1] = 0.5f // red takes half of green
        m[4] = 51f
        val (mat, off) = EditColor.gl(m)
        // column 1 (green), row 0 (red)
        assertEquals(0.5f, mat[1 * 3 + 0], 1e-6f)
        assertEquals(0f, mat[0 * 3 + 1], 1e-6f)
        assertEquals(0.2f, off[0], 1e-6f)
        assertEquals(1f, mat[0], 1e-6f)
    }

    @Test
    fun aDipToBlackPeaksAtTheSeam() {
        val clips = listOf(VClip("c1", 0, 2000), VClip("c2", 2000, 4000, trans = "black", transMs = 1000))
        val at = EditColor.fadeAt(clips, 2000)!!
        assertEquals(0xFF000000.toInt(), at.color)
        assertEquals(1f, at.alpha, 1e-3f)
        assertEquals(0.5f, EditColor.fadeAt(clips, 1750)!!.alpha, 1e-3f)
        assertEquals(0.5f, EditColor.fadeAt(clips, 2250)!!.alpha, 1e-3f)
        assertNull(EditColor.fadeAt(clips, 1400))
        assertNull(EditColor.fadeAt(clips, 2600))
        // no transition: nothing
        assertNull(EditColor.fadeAt(listOf(VClip("c1", 0, 2000), VClip("c2", 2000, 4000)), 2000))
    }

    @Test
    fun theFirstPieceFadesInFromTheStart() {
        val clips = listOf(VClip("c1", 0, 3000, trans = "white", transMs = 800))
        assertEquals(1f, EditColor.fadeAt(clips, 0)!!.alpha, 1e-3f)
        assertEquals(0xFFFFFFFF.toInt(), EditColor.fadeAt(clips, 0)!!.color)
        assertEquals(0.5f, EditColor.fadeAt(clips, 400)!!.alpha, 1e-3f)
        assertNull(EditColor.fadeAt(clips, 900))
    }

    @Test
    fun blendMixesTheColourIn() {
        val pix = intArrayOf(px(200, 100, 0))
        EditColor.blend(pix, EditColor.Fade(0xFF000000.toInt(), 0.5f))
        assertEquals(Triple(100, 50, 0), ch(pix[0]))
        val w = intArrayOf(px(0, 0, 0))
        EditColor.blend(w, EditColor.Fade(0xFFFFFFFF.toInt(), 1f))
        assertEquals(Triple(255, 255, 255), ch(w[0]))
    }

    @Test
    fun colourAndTransitionsSurviveJsonAndCleaning() {
        val c = VClip("c3", 100, 900, filter = "film", filterK = 0.6f, bright = 0.2f, contrast = -0.1f, sat = 0.4f, warm = -0.3f, trans = "flash", transMs = 800)
        val back = EditTrack.from(EditTrack.json(listOf(c)))
        assertEquals(listOf(c), back)
        val bad = EditTrack.clean(c.copy(filter = "nope", bright = 5f, trans = "spin", transMs = 99_999))
        assertEquals("", bad.filter)
        assertEquals(1f, bad.bright, 0f)
        assertEquals("", bad.trans)
        assertEquals(3000L, bad.transMs)
        // an old project without the new keys reads as before
        val old = EditTrack.from(org.json.JSONArray("[{\"id\":\"c1\",\"from\":0,\"to\":500}]"))
        assertEquals(VClip("c1", 0, 500), old.single())
    }

    @Test
    fun colourMakesTheTrackNotPlainButKeepsItsSound() {
        val one = EditTrack.update(EditTrack.initial(5000), 0) { it.copy(filter = "warm") }
        assertFalse(EditTrack.plain(one, 5000))
        assertTrue(EditTrack.soundPlain(one, 5000))
        assertFalse(EditTrack.soundPlain(EditTrack.update(one, 0) { it.copy(speed = 2f) }, 5000))
        assertTrue(EditTrack.badges(one[0]).contains("🎨"))
    }

    @Test
    fun splittingKeepsTheTransitionBeforeTheLeftPieceOnly() {
        val clips = listOf(VClip("c1", 0, 4000, trans = "black", filter = "bw"))
        val (list, right) = EditTrack.split(clips, 2000)!!
        assertEquals("black", list[0].trans)
        assertEquals("", list[right].trans)
        // the colour goes on in both halves
        assertEquals("bw", list[right].filter)
        val (fz, j) = EditTrack.freeze(clips, 1000)!!
        assertEquals("", fz[j].trans)
        assertEquals("bw", fz[j].filter)
    }

    @Test
    fun canvasKeepsTheVideoWhole() {
        // a wide video in a vertical frame: the width stays, the frame grows taller
        assertEquals(1920 to 3413, EditCanvas.frame(1920, 1080, "9:16"))
        // a vertical video in a square: the height stays
        assertEquals(1920 to 1920, EditCanvas.frame(1080, 1920, "1:1"))
        assertEquals(1080 to 1920, EditCanvas.frame(1080, 1920, ""))
        assertEquals(1080 to 1920, EditCanvas.frame(1080, 1920, "x"))
        val (w, h) = EditCanvas.outSize(1920, 1080, "9:16", 1920)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertTrue(h <= 1920)
        assertEquals(9f / 16f, w.toFloat() / h, 0.01f)
    }

    @Test
    fun videoRectFitsOrFills() {
        val fit = EditCanvas.videoRect(1920f, 1080f, 1080f, 1920f, false)
        assertEquals(0f, fit.l, 0.5f)
        assertEquals(1080f, fit.w, 0.5f)
        assertEquals(607.5f, fit.h, 0.5f)
        assertEquals(960f, (fit.t + fit.b) / 2, 0.5f)
        val fill = EditCanvas.videoRect(1920f, 1080f, 1080f, 1920f, true)
        assertEquals(1920f, fill.h, 0.5f)
        assertTrue(fill.l < 0f && fill.r > 1080f)
    }

    @Test
    fun projectFormatSurvivesJson() {
        val p = EProject("e1", "src", 1920, 1080, 5000, ratio = "9:16", fill = true, bg = 0xFF0B2545.toInt())
        val back = StudioEdit.fromJson(StudioEdit.toJson(p).toString())!!
        assertEquals("9:16", back.ratio)
        assertTrue(back.fill)
        assertEquals(0xFF0B2545.toInt(), back.bg)
        assertTrue(EditCanvas.reshaped(back))
        val plain = StudioEdit.fromJson(StudioEdit.toJson(p.copy(ratio = "")).toString())!!
        assertEquals("", plain.ratio)
        assertFalse(EditCanvas.reshaped(plain))
    }

    @Test
    fun musicFadesInAndOut() {
        val b = EBlock("b1", EditMusic.KIND, 1000, 10_000, file = "img/a.m4a", volume = 0.5f, animIn = "fade", animOut = "fade")
        assertEquals(0f, EditMusic.gain(b, 999), 0f)
        assertEquals(0f, EditMusic.gain(b, 1000), 1e-4f)
        assertEquals(0.25f, EditMusic.gain(b, 1000 + EditMusic.FADE_MS / 2), 1e-3f)
        assertEquals(0.5f, EditMusic.gain(b, 6000), 1e-4f)
        assertEquals(0f, EditMusic.gain(b, 11_000), 0f)
        val flat = b.copy(animIn = "none", animOut = "none")
        assertEquals(0.5f, EditMusic.gain(flat, 1000), 1e-4f)
        assertTrue(EditMusic.mixes(listOf(b)))
        assertFalse(EditMusic.mixes(listOf(b.copy(file = ""))))
    }

    @Test
    fun theVideoGetsQuieterUnderMusicThatAsks() {
        val b = EBlock("b1", EditMusic.KIND, 0, 5000, file = "img/a.mp3", mainVolume = 0.25f)
        assertEquals(0.25f, EditMusic.mainVolume(listOf(b), 100), 1e-6f)
        assertEquals(1f, EditMusic.mainVolume(listOf(b), 6000), 1e-6f)
    }

    @Test
    fun musicIsABlockThatIsNotDrawnAndStaysInTime() {
        val b = EBlock("b1", EditMusic.KIND, 3000, 4000, file = "img/a.mp3", text = "Song", volume = 0.7f, mainVolume = 0.5f, animIn = "none")
        val back = StudioEdit.blockFrom(StudioEdit.blockJson(b))
        assertEquals(b, back)
        assertTrue(EditSplit.overlays(listOf(b)).isEmpty())
        // cutting the video before it does not move the music
        val old = EditTrack.initial(10_000)
        val new = EditTrack.remove(EditTrack.split(old, 1000)!!.first, 0)!!
        val moved = EditTrack.remapBlocks(listOf(b, b.copy(id = "t", kind = "text", text = "x")), old, new)
        assertEquals(3000L, moved[0].startMs)
        assertEquals(2000L, moved[1].startMs)
    }
}

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

class EditCaptionMotionTest {

    private val words = listOf(SWord("Привет", 1_000, 1_400), SWord("мир", 1_500, 1_800), SWord("всем.", 1_900, 2_300), SWord("Дальше", 4_000, 4_400))

    @Test
    fun subtitlesKnowWhenEachWordIsSaid() {
        val cues = EditCaptions.cues(words, 3)
        assertEquals(listOf(1_000L, 1_500L, 1_900L), cues[0].wordStarts)
        val list = EditCaptions.withSubs(emptyList(), words, 3, "plate", 0.8f, "pop", 1080, 1920, 10_000, "karaoke", "lf:oswald")
        val b = list.first()
        assertEquals("karaoke", b.motion)
        assertEquals(listOf(0L, 500L, 900L), b.wordMs)
        assertEquals("lf:oswald", b.over?.font)
        // a moving caption comes in softly, its words do the rest
        assertEquals("fade", b.animIn)
        // the word being said
        assertEquals(-1, EditCaptions.wordAt(b, 900))
        assertEquals(0, EditCaptions.wordAt(b, 1_100))
        assertEquals(2, EditCaptions.wordAt(b, 2_000))
        assertEquals(0f, EditCaptions.wordIn(b, 1, 1_500), 0f)
        assertEquals(1f, EditCaptions.wordIn(b, 1, 2_000), 0f)
        // one word a caption: no motion but the bounce
        assertEquals("", EditCaptions.withSubs(emptyList(), words, 1, "plate", 0.8f, "pop", 1080, 1920, 10_000, "karaoke").first().motion)
        assertEquals("bounce", EditCaptions.withSubs(emptyList(), words, 1, "plate", 0.8f, "pop", 1080, 1920, 10_000, "bounce").first().motion)
    }

    @Test
    fun motionIsSavedWithTheBlock() {
        val b = EBlock("b1", "text", 1_000, 2_000, text = "a b", motion = "wave", wordMs = listOf(0, 400))
        val back = StudioEdit.blockFrom(StudioEdit.blockJson(b))!!
        assertEquals("wave", back.motion)
        assertEquals(listOf(0L, 400L), back.wordMs)
        // unknown motion is dropped
        val bad = StudioEdit.blockJson(b).put("motion", "spin")
        assertEquals("", StudioEdit.blockFrom(bad)!!.motion)
        // the overlay is redrawn when the said word changes, not on every frame
        val k1 = StudioEdit.overlayKey(listOf(b), 1_700) { 0 }
        val k2 = StudioEdit.overlayKey(listOf(b), 1_800) { 0 }
        val k3 = StudioEdit.overlayKey(listOf(b), 1_300) { 0 }
        assertEquals(k1, k2)
        assertFalse(k1 == k3)
    }

    @Test
    fun builtInFonts() {
        assertEquals(11, LumenFonts.ALL.size)
        assertTrue(LumenFonts.ALL.all { it.asset.startsWith("lumen_fonts/") && it.value.startsWith("lf:") })
        assertEquals("Oswald", LumenFonts.of("lf:oswald")?.name)
        assertNull(LumenFonts.of("serif"))
        assertTrue(LumenFonts.valid("serif") && LumenFonts.valid("lf:caveat") && LumenFonts.valid("file:my.ttf"))
        assertFalse(LumenFonts.valid("lf:nope"))
        assertEquals("condensed", LumenFonts.generic("lf:oswald"))
        assertEquals("mono", LumenFonts.generic("lf:pressstart2p"))
        // every font of the catalog is in the assets, with its licence
        val dir = java.io.File("src/main/assets/lumen_fonts").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/lumen_fonts")
        for (f in LumenFonts.ALL) {
            assertTrue(f.id, java.io.File(dir, "${f.id}.ttf").length() > 10_000)
            assertTrue(f.id, java.io.File(dir, "licenses/${f.id}-OFL.txt").exists())
        }
    }
}

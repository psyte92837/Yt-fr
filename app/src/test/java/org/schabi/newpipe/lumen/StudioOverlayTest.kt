/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioOverlayTest {
    @Test
    fun onlySubtitlesOrSchemes() {
        assertEquals(StudioLayers.SUBS, StudioOverlay(mode = StudioOverlay.SUBS).layers())
        assertEquals(StudioLayers.ALL, StudioOverlay(mode = StudioOverlay.SCHEMES, subsToo = true).layers())
        assertEquals(StudioLayers.GRAPHICS, StudioOverlay(mode = StudioOverlay.SCHEMES, subsToo = false).layers())
        // subtitles mode always keeps subtitles, whatever the option says
        assertTrue(StudioOverlay(mode = StudioOverlay.SUBS, subsToo = false).subs)
    }

    @Test
    fun jsonRoundTripAndBadValues() {
        val o = StudioOverlay(StudioOverlay.SCHEMES, false, "clear", "ib:neonjump", "l", "pip", "big", "many")
        assertEquals(o, StudioOverlay.parse(o.toJson().toString()))
        val bad = StudioOverlay.parse("""{"mode":"all","bg":"rainbow","look":"nope","size":"xl","place":"moon"}""")!!
        assertEquals(StudioOverlay(), bad)
        assertEquals(null, StudioOverlay.parse("not json"))
    }

    @Test
    fun migratesOldSettings() {
        val subs = StudioOverlay.migrate("subs", true, 80, "auto", "style", "board")
        assertEquals(StudioOverlay.SUBS, subs.mode)
        val none = StudioOverlay.migrate("none", true, 80, "auto", "style", "board")
        assertEquals(StudioOverlay.SUBS, none.mode)
        val g = StudioOverlay.migrate("graphics", false, 70, "minimal", "style", "big")
        assertEquals(StudioOverlay.SCHEMES, g.mode)
        assertFalse(g.subsToo)
        assertEquals("full", g.place)
        assertEquals("clear", g.bg)
        assertEquals("s", g.size)
        assertEquals("big", g.toon)
        val pod = StudioOverlay.migrate("all", true, 100, "podcast_side", "style", "board")
        assertEquals("side", pod.place)
        assertEquals("glass", pod.look)
        assertEquals("l", pod.size)
        assertEquals("pip", StudioOverlay.migrate("all", true, 80, "paper", "pip", "board").place)
    }

    @Test
    fun everyLookReadsWithEveryBackground() {
        for (p in SchemeStyles.PRESETS) for (bg in StudioOverlay.BGS) {
            val s = StudioOverlay.style(p, bg)
            assertTrue("${p.id}/$bg", SchemeStyles.contrast(s.ink, SchemeStyles.surface(s)) >= 4.5)
            when (bg) {
                "clear" -> {
                    assertEquals("${p.id}: no plate", "none", s.fill)
                    assertEquals("${p.id}: stays over the video", "overlay", s.place)
                }
                "glass" -> assertTrue("${p.id}: see-through", (s.card ushr 24) in 0x40..0xE0)
                else -> assertEquals(0xFF, s.card ushr 24)
            }
        }
    }

    @Test
    fun builtInLooksAndOldIds() {
        val ids = SchemeStyles.PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("auto", "glass", "paper", "neon", "minimal")))
        assertEquals("glass", SchemeStyles.alias("podcast_pip"))
        val dir = java.nio.file.Files.createTempDirectory("ov").toFile()
        assertNotNull(SchemeStyles.find(dir, "podcast_side"))
        dir.deleteRecursively()
    }

    @Test
    fun cardAlphaAndAmount() {
        assertEquals(1f, StudioOverlay.cardAlpha("solid"))
        assertEquals(0f, StudioOverlay.cardAlpha("clear"))
        assertTrue(StudioOverlay.cardAlpha("glass") in 0.3f..0.8f)
        val few = StudioOverlay(amount = "few").maxSchemes(20)
        val norm = StudioOverlay().maxSchemes(20)
        val many = StudioOverlay(amount = "many").maxSchemes(20)
        assertTrue(few < norm && norm < many)
        assertEquals(1, StudioOverlay(amount = "few").maxSchemes(2))
        assertEquals("overlay", StudioOverlay(place = "full").schemePlace())
        assertFalse(StudioOverlay(place = "full").overlay())
        assertEquals("side", StudioOverlay(place = "side").schemePlace())
    }

    @Test
    fun fullScreenMoments() {
        val o = StudioOverlay(StudioOverlay.SCHEMES, false, "clear", "ib:neonjump", "l", "full", "big", "many", StudioOverlay.BLOCKS_ONLY)
        assertEquals(o, StudioOverlay.parse(o.toJson().toString()))
        assertEquals(StudioOverlay.FULL, StudioOverlay.parse("""{"full":"moon"}""")!!.full)
        // old settings without the key keep the full-screen insert
        assertEquals(StudioOverlay.FULL, StudioOverlay.parse("""{"mode":"schemes","place":"full"}""")!!.full)
        assertEquals(StudioOverlay.FullShow(true, true, true), StudioOverlay.fullShow(StudioOverlay.FULL))
        assertEquals(StudioOverlay.FullShow(false, true, false), StudioOverlay.fullShow(StudioOverlay.BLOCKS_ONLY))
        assertEquals(StudioOverlay.FullShow(false, false, true), StudioOverlay.fullShow(StudioOverlay.SUBS_ONLY))
        assertEquals(StudioOverlay.FullShow(false, false, false), StudioOverlay.fullShow(StudioOverlay.OFF))
        // the subtitles mode never covers the video
        assertEquals(StudioOverlay.SUBS_ONLY, StudioOverlay(mode = StudioOverlay.SUBS, full = StudioOverlay.FULL).fullMode())
        assertEquals(StudioOverlay.OFF, StudioOverlay(full = StudioOverlay.OFF).fullMode())
        // every scheme is full screen and those moments show none: nothing to compose
        assertFalse(StudioOverlay(place = "full", full = StudioOverlay.SUBS_ONLY).anySchemes())
        assertFalse(StudioOverlay.needCompose(StudioOverlay(place = "full", full = StudioOverlay.OFF), true, 0, "", "k1"))
        assertTrue(StudioOverlay(place = "full", full = StudioOverlay.BLOCKS_ONLY).anySchemes())
        assertTrue(StudioOverlay(place = "auto", full = StudioOverlay.SUBS_ONLY).anySchemes())
    }

    @Test
    fun composesOncePerScript() {
        val on = StudioOverlay(mode = StudioOverlay.SCHEMES)
        assertTrue(StudioOverlay.needCompose(on, true, 0, "", "k1"))
        assertFalse(StudioOverlay.needCompose(on, true, 0, "k1", "k1"))
        assertFalse(StudioOverlay.needCompose(on, true, 3, "", "k1"))
        assertFalse(StudioOverlay.needCompose(on, false, 0, "", "k1"))
        assertFalse(StudioOverlay.needCompose(StudioOverlay(mode = StudioOverlay.SUBS), true, 0, "", "k1"))
    }
}

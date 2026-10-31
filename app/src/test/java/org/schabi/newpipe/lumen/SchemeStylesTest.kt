/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SchemeStylesTest {
    @Test
    fun colours() {
        assertEquals(0xFFFF0000.toInt(), SchemeStyles.parseColor("#FF0000"))
        assertEquals(0x80FFFFFF.toInt(), SchemeStyles.parseColor("#80FFFFFF"))
        assertEquals(0xFF112233.toInt(), SchemeStyles.parseColor("123"))
        assertNull(SchemeStyles.parseColor("red"))
        assertEquals("#80FFFFFF", SchemeStyles.hex(0x80FFFFFF.toInt()))
        assertEquals(21.0, SchemeStyles.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, SchemeStyles.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        // half white over black is mid grey
        val g = SchemeStyles.over(0x80FFFFFF.toInt(), 0xFF000000.toInt())
        assertTrue((g shr 16 and 0xFF) in 120..135)
    }

    @Test
    fun presetsFollowTheRules() {
        for (p in SchemeStyles.PRESETS.filter { it.id != SchemeStyles.AUTO }) {
            val (fixed, notes) = SchemeStyles.enforce(p)
            assertTrue("${p.id}: $notes", SchemeStyles.contrast(fixed.ink, SchemeStyles.surface(fixed)) >= 4.5)
        }
    }

    @Test
    fun enforceFixesBadStyles() {
        val bad = SchemeStyle("x", "Плохой", card = 0xF0FFFFFF.toInt(), ink = 0xFFEEEEEE.toInt(), muted = 0xFFF5F5F5.toInt(),
            accent = 0xFFFAFAFA.toInt(), fill = "solid")
        val (s, notes) = SchemeStyles.enforce(bad)
        assertTrue(SchemeStyles.contrast(s.ink, SchemeStyles.surface(s)) >= 4.5)
        assertNull(s.accent)
        assertEquals(3, notes.size)
        // text without a backdrop over a live video gets a panel
        val bare = SchemeStyles.enforce(SchemeStyle("y", "Голый", fill = "none", place = "overlay")).first
        assertEquals("panel", bare.place)
    }

    @Test
    fun jsonIsLenientAndRoundTrips() {
        val s = SchemeStyles.fromJson("""```json
            {"name":"Мята","card":"#CC101418","ink":"#FFFFFF","accent":"#3DDC97","fill":"glass","font":"comic","radius":2,
             "place":"side","size":250,"dur":50,"notes":["x"]}```""", "mint")
        assertNotNull(s)
        s!!
        assertEquals("mint", s.id)
        assertEquals("sans", s.font) // unknown font
        assertEquals(0.5f, s.radius, 1e-4f)
        assertEquals(100, s.size)
        assertEquals(150, s.dur)
        assertEquals("side", s.place)
        assertEquals(s, SchemeStyles.fromJson(SchemeStyles.toJson(s).toString(), "mint"))
        assertNull(SchemeStyles.fromJson("nope"))
    }

    @Test
    fun placementKeepsSubtitlesAndFace() {
        // podcast 16:9 side: the scheme on one side, the speaker's frame slides the other way
        val (box, video) = SchemeStyles.layout("side", "auto", 16f / 9, true)
        assertTrue(box.x > 0.5f)
        assertTrue(video!!.x < 0.5f)
        val (lbox, lvideo) = SchemeStyles.layout("side", "left", 16f / 9, true)
        assertTrue(lbox.x < 0.5f && lvideo!!.x > 0.5f)
        // picture in picture: a small corner frame
        val (pbox, pip) = SchemeStyles.layout("pip", "auto", 9f / 16, true)
        assertTrue(pip!!.w < 0.5f && pip.y < 0.3f)
        assertTrue(pbox.y + pbox.h / 2 <= 0.71f) // subtitles below stay free
        // overlay: no frame change
        assertNull(SchemeStyles.layout("overlay", "auto", 1f, true).second)
        assertEquals(0.5f, SchemeStyles.layout("overlay", "auto", 9f / 16, false).first.y, 1e-4f)
    }

    @Test
    fun store() {
        val dir = Files.createTempDirectory("styles").toFile()
        val n = SchemeStyles.PRESETS.size
        assertEquals(n, SchemeStyles.list(dir).size)
        val id = SchemeStyles.freeId("Neon Mint!", setOf("neonmint"))
        assertEquals("neonmint2", id)
        SchemeStyles.save(dir, SchemeStyle(id, "Neon Mint"))
        assertEquals(n + 1, SchemeStyles.list(dir).size)
        assertEquals("Neon Mint", SchemeStyles.find(dir, id)?.name)
        SchemeStyles.delete(dir, "glass") // a preset stays
        SchemeStyles.delete(dir, id)
        assertEquals(n, SchemeStyles.list(dir).size)
        assertNotNull(SchemeStyles.find(dir, "glass"))
        dir.deleteRecursively()
    }
}

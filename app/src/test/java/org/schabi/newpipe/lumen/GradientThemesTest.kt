/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientThemesTest {
    @Test
    fun everyMenuEntryHasTheme() {
        val keys = Palette.gradientNames.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        keys.filter { it != "none" }.forEach { assertTrue(it, Palette.hasGradient(it)) }
    }

    @Test
    fun lightAndDarkGroupsSplitMenu() {
        val light = Palette.lightGradients
        val dark = Palette.darkGradients
        assertEquals(Palette.gradientNames.map { it.first }.toSet(), (light + dark).toSet())
        assertTrue(light.intersect(dark.toSet()).isEmpty())
        assertTrue(light.size >= 8)
        listOf("mint", "sky", "lavender", "sunrise", "rose", "pearl").forEach { assertTrue(it, it in light) }
        assertTrue(Palette.gradientPreview("pearl")?.second == false)
    }

    @Test
    fun gradientsAreSmoothAndAccentsReadable() {
        Palette.gradientNames.map { it.first }.filter { it != "none" }.forEach { k ->
            val bg = Palette.gradientPreview(k)!!.first
            bg.toList().zipWithNext().forEach { (a, b) ->
                listOf(16, 8, 0).forEach { sh -> assertTrue("$k step", Math.abs((a shr sh and 0xFF) - (b shr sh and 0xFF)) <= 0x14) }
            }
            val (acc, on, cont, onCont) = Palette.gradientAccent(k)!!.toList()
            assertTrue("$k button", Palette.contrast(acc, on) >= 4.5)
            assertTrue("$k container", Palette.contrast(cont, onCont) >= 4.5)
            bg.forEach { assertTrue("$k accent on bg", Palette.contrast(acc, it) >= 3.0) }
        }
    }

    @Test
    fun signatureSchemeIsReadable() {
        assertEquals("lumen", Palette.accentNames.first().first)
        for (night in listOf(true, false)) {
            val c = Palette.signature(night)
            assertEquals(17, c.size)
            val (accent, onAccent, soft, onSoft, fab) = c.toList()
            val onFab = c[5]; val surface = c[6]; val card = c[7]; val on = c[8]; val muted = c[9]
            val tonal = c[11]; val onTonal = c[12]
            assertTrue("button $night", Palette.contrast(accent, onAccent) >= 7.0)
            assertTrue("selected $night", Palette.contrast(soft, onSoft) >= 7.0)
            assertTrue("fab $night", Palette.contrast(fab, onFab) >= 7.0)
            assertTrue("tonal $night", Palette.contrast(tonal, onTonal) >= 7.0)
            listOf(surface, card).forEach { bg ->
                assertTrue("text $night", Palette.contrast(on, bg) >= 7.0)
                assertTrue("muted $night", Palette.contrast(muted, bg) >= 4.5)
                assertTrue("accent $night", Palette.contrast(accent, bg) >= 3.0)
                // error, success, warning as text
                c.slice(14..16).forEach { assertTrue("status $night", Palette.contrast(it, bg) >= 4.5) }
            }
            assertTrue("muted on tonal $night", Palette.contrast(muted, tonal) >= 4.5)
        }
    }
}

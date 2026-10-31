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
}

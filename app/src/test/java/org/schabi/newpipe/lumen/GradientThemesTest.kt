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
}

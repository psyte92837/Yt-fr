/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioLayersTest {
    @Test
    fun modes() {
        assertEquals(StudioLayers.ALL, StudioLayers.of("bad"))
        assertTrue(StudioLayers.of("subs").subs)
        assertFalse(StudioLayers.of("subs").graphics)
        assertFalse(StudioLayers.of("graphics").subs)
        assertFalse(StudioLayers.NONE.subs || StudioLayers.NONE.graphics)
    }

    @Test
    fun scaleIsClamped() {
        assertEquals(0.3f, StudioLayers.scale(5), 1e-4f)
        assertEquals(0.6f, StudioLayers.scale(60), 1e-4f)
        assertEquals(1f, StudioLayers.scale(300), 1e-4f)
    }

    @Test
    fun schemesAndPlace() {
        assertFalse(StudioLayers.hasScheme("text", 3))
        assertFalse(StudioLayers.hasScheme("icons", 0))
        assertTrue(StudioLayers.hasScheme("icons", 2))
        assertTrue(StudioLayers.hasScheme("stat", 0))
        assertEquals(0.5f, StudioLayers.overY(0.6f, false), 1e-4f)
        assertEquals(0.42f, StudioLayers.overY(1f, true), 1e-4f)
        assertTrue(StudioLayers.overY(0.3f, true) < StudioLayers.overY(0.75f, true))
    }
}

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
        assertEquals(0.7f, StudioLayers.scale(5), 1e-4f)
        assertEquals(0.8f, StudioLayers.scale(80), 1e-4f)
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

    @Test
    fun readableScale() {
        assertEquals(0.8f, StudioLayers.readable(0.3f, "icons", 4), 1e-4f)
        assertEquals(0.8f, StudioLayers.readable(0.5f, "scheme", 0), 1e-4f)
        assertEquals(0.7f, StudioLayers.readable(0.3f, "big", 1), 1e-4f)
        assertEquals(0.9f, StudioLayers.readable(0.9f, "icons", 4), 1e-4f)
        assertEquals(1f, StudioLayers.readable(1.4f, "big", 1), 1e-4f)
    }

    @Test
    fun toonModes() {
        assertEquals("board", StudioLayers.toonMode("zzz"))
        assertEquals("off", StudioLayers.toonMode("off"))
        assertEquals(1f, StudioLayers.toonScale("board", true), 1e-4f)
        assertEquals(1f, StudioLayers.toonScale("big", false), 1e-4f)
        assertTrue(StudioLayers.toonScale("big", true) < 1f)
        assertTrue(StudioLayers.toonSchemeScale("big") > StudioLayers.toonSchemeScale("board"))
    }
}

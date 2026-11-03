/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditVoiceBaseTest {

    @Test
    fun backgroundAsLongAsTheVoice() {
        assertEquals(1080 to 1920, EditVoiceBase.size(true))
        assertEquals(1920 to 1080, EditVoiceBase.size(false))
        assertEquals(150, EditVoiceBase.frames(10_000))
        assertEquals(16, EditVoiceBase.frames(1_001))
        assertEquals(1, EditVoiceBase.frames(0))
        val px = EditVoiceBase.gradient(4, 3, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(12, px.size)
        assertEquals(0xFF000000.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[11])
        // rows are even, the middle one in between, all opaque
        assertEquals(px[4], px[7])
        assertTrue(px.all { it ushr 24 == 0xFF })
    }
}

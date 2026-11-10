/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceToneTest {
    @Test
    fun tonesAreDistinctAndComplete() {
        assertEquals(VoiceTone.ALL.size, VoiceTone.ALL.map { it.id }.distinct().size)
        for (id in listOf("classic", "documentary", "news", "humor", "funny", "dramatic", "calm", "whisper")) assertEquals(id, VoiceTone.of(id).id)
        assertEquals(VoiceTone.DEFAULT, VoiceTone.of("nope").id)
        assertTrue(VoiceTone.ALL.all { it.ru.isNotBlank() && it.en.isNotBlank() && it.sayRu.isNotBlank() && it.sayEn.isNotBlank() })
    }

    @Test
    fun instructionJoinsToneRateAndWish() {
        val ru = VoiceTone.instruction("documentary", 20, " с хрипотцой. ", true)
        assertTrue(ru, ru.startsWith("Прочитай как диктор документального") && ru.contains("быстро") && ru.endsWith("с хрипотцой:"))
        val en = VoiceTone.instruction("humor", 0, "", false)
        assertEquals("Read with humour and light irony, a smile in the voice:", en)
    }

    @Test
    fun edgeShiftStaysInRange() {
        val o = VoiceTone.edge(TtsOptions("v", 45, 18), "funny")
        assertEquals(50, o.rate)
        assertEquals(20, o.pitch)
        assertEquals(TtsOptions("v", 10), VoiceTone.edge(TtsOptions("v", 10), "classic"))
    }
}

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

    @Test
    fun geminiPromptFillsEveryVariable() {
        val d = VoiceTone.direction(VoiceTone.PROMPT, "documentary", -10, "", VoiceTone.MIX_ONE, "русский")
        assertTrue(d, !d.contains('{') && d.endsWith("ТЕКСТ:") && d.contains("язык: русский") && d.contains("120 слов"))
        assertTrue(d, d.contains("документального фильма") && d.contains("один тон") && !d.contains("Пожелание") && !d.contains("\n\n\n"))
        assertTrue(VoiceTone.direction(VoiceTone.PROMPT, "classic", 0, "тише", VoiceTone.MIX_AUTO, "en").contains("Пожелание автора: тише"))
    }

    @Test
    fun ownEmotionMix() {
        val e = VoiceTone.emotions("humor,sad,nope,humor,classic", "classic")
        assertTrue(e, e.contains("с юмором") && e.contains("грустно") && !e.contains("nope") && !e.contains("классика"))
        assertTrue(VoiceTone.emotions("nope", "classic").contains("один тон"))
        assertTrue(VoiceTone.emotions(VoiceTone.MIX_AUTO, "classic").contains("по смыслу"))
    }
}

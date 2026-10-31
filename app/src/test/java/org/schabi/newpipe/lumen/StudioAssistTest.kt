/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioAssistTest {

    @Test
    fun cartoonPresetsOnlyForAnimation() {
        val plain = StudioAssist.presets(false)
        val toon = StudioAssist.presets(true)
        assertTrue(plain.none { it.cartoon })
        assertTrue(toon.size > plain.size)
        assertEquals(toon.size, toon.map { it.id }.distinct().size)
        assertTrue(toon.all { it.ask.isNotBlank() && it.label.isNotBlank() })
    }

    @Test
    fun parsesScriptAndNotes() {
        val r = StudioAssist.parse("Вот:\n```json\n{\"title\":\"Т\",\"notes\":[\"сократил хук\",\" \",\"исправил цифру\"],\"scenes\":[{\"who\":\"Лиса\",\"mood\":\"happy\",\"say\":\"Привет!\"}]}\n```")!!
        assertEquals(listOf("сократил хук", "исправил цифру"), r.notes)
        assertEquals("Лиса", r.script.scenes[0].who)
        val s = StudioAssist.parse("{\"notes\":\"- раз\\n- два\",\"scenes\":[{\"say\":\"x\"}]}")!!
        assertEquals(listOf("раз", "два"), s.notes)
        assertTrue(StudioAssist.parse("{\"scenes\":[{\"say\":\"x\"}]}")!!.notes.isEmpty())
        assertNull(StudioAssist.parse("нет json"))
    }

    @Test
    fun castRuleNamesEveryone() {
        assertEquals("", StudioAssist.castRule(emptyList()))
        val rule = StudioAssist.castRule(listOf(SChar("Лиса", "fox"), SChar("Робот", "robot")))
        assertTrue(rule.contains("Лиса") && rule.contains("Робот") && rule.contains("mood"))
    }

    @Test
    fun summaryCountsScenesAndWords() {
        val a = SScript("", listOf(SScene("раз два три"), SScene("четыре")))
        val b = SScript("", listOf(SScene("раз два")))
        assertEquals("Сцен: 2 → 1 · слов: 4 → 2", StudioAssist.summary(a, b))
    }
}

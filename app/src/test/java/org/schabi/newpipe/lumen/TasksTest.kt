/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {

    @Test
    fun oneTaskShowsItsTitleAndMessage() {
        val (title, body) = TaskText.summary(listOf("Studio" to "Кадр 3/10"))
        assertEquals("Studio", title)
        assertEquals("Кадр 3/10", body)
        assertEquals(0.3f, TaskText.fraction(listOf("Studio" to "Кадр 3/10"))!!, 0.001f)
    }

    @Test
    fun severalTasksAreListed() {
        val list = listOf("A" to "1/2", "B" to "")
        val (title, body) = TaskText.summary(list)
        assertTrue(title.contains("2"))
        assertEquals("A: 1/2\nB", body)
        assertNull(TaskText.fraction(list))
    }

    @Test
    fun blankMessageGetsPlaceholder() {
        assertTrue(TaskText.summary(listOf("A" to "")).second.isNotBlank())
    }

    @Test
    fun elapsedText() {
        assertEquals("5 с", TaskText.elapsed(5_400))
        assertEquals("2 мин 5 с", TaskText.elapsed(125_000))
    }

    @Test
    fun autoTopicFallsBackToScenesAndDraft() {
        assertEquals("Почему небо синее", StudioAuto.topic(" Почему небо  синее ", "x", "y"))
        assertEquals("Лиса спрашивает Совы", StudioAuto.topic("", "Лиса спрашивает\nСовы", ""))
        assertEquals("черновик", StudioAuto.topic("", " ", "черновик"))
        assertEquals("", StudioAuto.topic("", "", ""))
    }

    @Test
    fun autoExtraNumbersScenes() {
        val e = StudioAuto.extra("- Лиса удивляется\n\n• Сова объясняет", "")
        assertTrue(e.contains("1. Лиса удивляется"))
        assertTrue(e.contains("2. Сова объясняет"))
        assertEquals("", StudioAuto.extra(" ", " "))
        assertTrue(StudioAuto.extra("", "@Лиса: привет").contains("@Лиса: привет"))
    }
}

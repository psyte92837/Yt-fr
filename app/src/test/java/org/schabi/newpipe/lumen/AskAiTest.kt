/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskAiTest {
    @Test
    fun spokenTakesFirstNonBlankAndCapitalises() {
        assertEquals("Какие темы растут", AskAi.spoken(listOf(" ", " какие темы растут ", "другое")))
    }

    @Test
    fun spokenIsNullWithoutSpeech() {
        assertNull(AskAi.spoken(null))
        assertNull(AskAi.spoken(emptyList()))
        assertNull(AskAi.spoken(listOf("", "  ")))
    }

    @Test
    fun appendJoinsWithSingleSpace() {
        assertEquals("Привет", AskAi.append("", "Привет"))
        assertEquals("Что нового? Расскажи", AskAi.append("Что нового?  ", "Расскажи"))
    }

    @Test
    fun framedAddsTabFocusOnlyForKnownTabs() {
        assertEquals("Вопрос", AskAi.framed("unknown", "Вопрос"))
        assertEquals(true, AskAi.framed("seo", "Вопрос").endsWith("\nВопрос"))
        assertEquals(true, AskAi.framed("recs", "Вопрос").contains("Рекомендации"))
    }

    @Test
    fun everyProjectTabHasFocus() {
        listOf("overview", "videos", "collect", "channels", "analytics", "recs", "audience", "seo", "ai", "export", "schemes", "settings")
            .forEach { assertEquals(it, false, AskAi.focus(it).isBlank()) }
    }
}

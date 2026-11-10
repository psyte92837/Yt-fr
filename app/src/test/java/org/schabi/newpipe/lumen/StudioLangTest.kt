/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioLangTest {
    @Test
    fun chosenLanguageWinsOverScriptAndUi() {
        assertEquals("de", Studio.voiceLang("de", "ru", false))
        assertEquals("uk", Studio.voiceLang("", "uk", true))
        assertEquals("en", Studio.voiceLang(" ", null, true))
        assertEquals("ru", Studio.voiceLang("", null, false))
    }

    @Test
    fun languageNamesAreNative() {
        assertEquals("English", Studio.langName("en"))
        assertEquals("русский", Studio.langName("ru").lowercase())
        assertEquals("Deutsch", Studio.langName("de"))
    }

    @Test
    fun screenTextFollowsChosenLanguageOnlyInItsAlphabet() {
        assertEquals("de", Studio.textLang("de", "en"))
        assertEquals("ru", Studio.textLang("de", "ru"))
        assertEquals("kk", Studio.textLang("kk", "ru"))
        assertEquals("uk", Studio.textLang("", "uk"))
        assertEquals("ja", Studio.textLang("ja", null))
        assertEquals("FRAGE", Studio.questionWord(Studio.textLang("de", "en")))
        assertEquals("ВОПРОС", Studio.questionWord("ru"))
        assertEquals("QUESTION", Studio.questionWord("ja"))
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {
    @Test
    fun guessesLanguageByScriptAndLetters() {
        assertEquals("ru", LangGuess.guess("Как выбрать электромобиль в 2026 году"))
        assertEquals("uk", LangGuess.guess("Як обрати електромобіль: поради та її недоліки"))
        assertEquals("be", LangGuess.guess("Як выбраць электрамабіль у 2026 годзе"))
        assertEquals("kk", LangGuess.guess("Электромобиль таңдау: қалай және неге"))
        assertEquals("ja", LangGuess.guess("電気自動車の選び方を解説します"))
        assertEquals("zh", LangGuess.guess("如何选择电动汽车"))
        assertEquals("ko", LangGuess.guess("전기차 고르는 방법"))
        assertEquals("ar", LangGuess.guess("كيف تختار سيارة كهربائية"))
        assertEquals("hi", LangGuess.guess("इलेक्ट्रिक कार कैसे चुनें"))
    }

    @Test
    fun guessesLatinLanguagesByWordsAndMarks() {
        assertEquals("en", LangGuess.guess("How to choose the best electric car for your family"))
        assertEquals("de", LangGuess.guess("Wie man das beste Elektroauto für die Familie wählt"))
        assertEquals("es", LangGuess.guess("Cómo elegir el mejor coche eléctrico para la familia"))
        assertEquals("pt", LangGuess.guess("Como escolher o melhor carro elétrico para você e sua família"))
        assertEquals("pl", LangGuess.guess("Jak wybrać samochód elektryczny i czy się opłaca"))
        assertEquals("tr", LangGuess.guess("Elektrikli araba nasıl seçilir ve neden bu kadar pahalı"))
    }

    @Test
    fun unsureTextGivesEmptyGuess() {
        assertEquals("", LangGuess.guess("iPhone 17 Pro Max"))
        assertEquals("", LangGuess.guess("BYD Seal"))
        assertEquals("", LangGuess.guess("12345 !!!"))
    }

    @Test
    fun languageFilterKeepsUnsureVideos() {
        assertTrue(Regions.languageOk("", "anything"))
        assertTrue(Regions.languageOk("ru", "Обзор Tesla Model Y"))
        assertFalse(Regions.languageOk("ru", "How to choose the best electric car for your family"))
        assertTrue(Regions.languageOk("ru", "Tesla Model Y 2026"))
        assertTrue(Regions.languageOk("en", "How to choose the best electric car for your family"))
    }

    @Test
    fun regionsHaveAnyOptionFirstAndUniqueCodes() {
        assertEquals("", Regions.countries.first().first)
        assertEquals("", Regions.languages.first().first)
        assertEquals(Regions.countries.size, Regions.countries.map { it.first }.toSet().size)
        assertEquals(Regions.languages.size, Regions.languages.map { it.first }.toSet().size)
        assertEquals("Германия", Regions.countryName("DE"))
        assertEquals("XX", Regions.countryName("XX"))
    }

    @Test
    fun filtersAndMonitorKeepNewFields() {
        val f = SearchFilters.from(SearchFilters(country = "DE", origLang = "de").toJson())
        assertEquals("DE", f.country)
        assertEquals("de", f.origLang)
        val old = SearchFilters.from(org.json.JSONObject().put("sort", "date"))
        assertEquals("", old.country)
        assertEquals("", old.origLang)
        assertTrue(MonitorConfig.from(MonitorConfig(aiFilter = true).toJson()).aiFilter)
        assertFalse(MonitorConfig.from(org.json.JSONObject()).aiFilter)
        assertEquals("Германия · Немецкий · до 50", filtersSummary(SearchFilters(country = "DE", origLang = "de")))
    }

    @Test
    fun filterSplitsByNumbersAndFormatsLines() {
        val vs = (1..4).map { Video(id = "v$it", url = "u$it", title = "t$it", channel = "c$it") }
        val (keep, drop) = WatchFilter.split(vs, setOf(1, 3, 9))
        assertEquals(listOf("v1", "v3"), keep.map { it.id })
        assertEquals(listOf("v2", "v4"), drop.map { it.id })
        val v = Video(id = "x", url = "u", title = "Title", channel = "Chan").apply { description = "line1\nline2" }
        assertEquals("2. Title — Chan. line1 line2", WatchFilter.line(2, v))
        assertEquals("1. Title — Chan", WatchFilter.line(1, Video(id = "y", url = "u", title = "Title", channel = "Chan")))
    }
}

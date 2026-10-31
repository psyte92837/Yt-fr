/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDigestTest {
    private fun v(id: String, title: String, channel: String = "C", views: Long = 0) =
        Video(id = id, url = "u$id", title = title, channel = channel, views = views)

    @Test
    fun parsesTranslatedQueries() {
        val m = WatchLangs.parse("""Вот: {"queries":{"en":["electric cars","EV review"],"ES":"coches eléctricos","de":[],"fr":[""," "]}}""")
        assertEquals(listOf("electric cars", "EV review"), m["en"])
        assertEquals(listOf("coches eléctricos"), m["es"])
        assertFalse(m.containsKey("de"))
        assertFalse(m.containsKey("fr"))
    }

    @Test
    fun brokenTranslationGivesEmptyMap() {
        assertTrue(WatchLangs.parse("no json").isEmpty())
        assertTrue(WatchLangs.parse("""{"queries":["a"]}""").isEmpty())
        assertTrue(WatchLangs.parse("""{"queries":{"en":["a",""").isEmpty())
    }

    @Test
    fun mergeKeepsOriginalFirstAndDropsDuplicates() {
        val q = WatchLangs.merge(
            listOf("электромобили", "Tesla"),
            linkedMapOf("en" to listOf("electric cars", "tesla", "EV news"), "es" to listOf("coches eléctricos"), "ja" to listOf("電気自動車")),
            perLang = 2
        )
        assertEquals(listOf("электромобили", "Tesla", "electric cars", "coches eléctricos", "電気自動車"), q)
        assertEquals(3, WatchLangs.merge(listOf("a"), mapOf("en" to listOf("b", "c"), "es" to listOf("d")), max = 3).size)
    }

    @Test
    fun countsLanguagesMostFrequentFirstUnknownLast() {
        val list = listOf(
            v("1", "Как выбрать электромобиль в этом году"),
            v("2", "Почему электромобили дешевеют так быстро"),
            v("3", "How to choose the best electric car for your family"),
            v("4", "BYD Seal"),
            v("5", "電気自動車の選び方を解説します")
        )
        assertEquals(listOf("ru" to 2, "en" to 1, "ja" to 1, "?" to 1), WatchDigest.langCounts(list))
        assertEquals("RU 2 · EN 1 · JA 1 · ? 1", WatchDigest.langLine(list))
        assertEquals("RU 2 · EN 1 · +2", WatchDigest.langLine(list, max = 2))
    }

    @Test
    fun plainDigestListsLanguagesChannelsAndTopTitles() {
        val list = listOf(
            v("1", "How to choose the best electric car for your family", "AutoNews", 10),
            v("2", "Cómo elegir el mejor coche eléctrico para la familia", "AutoNews", 500),
            v("3", "Как выбрать электромобиль в этом году", "Драйв", 50)
        )
        val s = WatchDigest.plain(list, titles = 2)
        val lines = s.lines()
        assertTrue(lines[0].contains("EN 1") && lines[0].contains("ES 1") && lines[0].contains("RU 1"))
        assertTrue(lines[1].contains("AutoNews (2)") && lines[1].contains("Драйв"))
        assertEquals("• Cómo elegir el mejor coche eléctrico para la familia", lines[2])
        assertEquals("• Как выбрать электромобиль в этом году", lines[3])
        assertTrue(lines[4].contains("1"))
    }

    @Test
    fun titleMentionsLanguageCountOnlyWhenSeveral() {
        val one = listOf(v("1", "Как выбрать электромобиль в этом году"))
        val many = one + v("2", "How to choose the best electric car for your family")
        assertFalse(WatchDigest.title("EV", 1, one).contains("язык"))
        assertTrue(WatchDigest.title("EV", 2, many).endsWith("на 2 языках"))
    }

    @Test
    fun promptLineCarriesGuessedLanguage() {
        val x = v("1", "Cómo elegir el mejor coche eléctrico para la familia", "Auto").apply { description = "uno\ndos" }
        assertEquals("3. [es] Cómo elegir el mejor coche eléctrico para la familia — Auto. uno dos", WatchDigest.line(3, x))
        assertEquals("1. BYD Seal — C", WatchDigest.line(1, v("2", "BYD Seal")))
    }

    @Test
    fun bodyPrefersAiTextAndAddsFilterNote() {
        val list = listOf(v("1", "How to choose the best electric car for your family"))
        val b = WatchDigest.body(list, "Обзоры новых моделей.", 3)
        assertTrue(b.startsWith("Обзоры новых моделей.\n"))
        assertTrue(b.contains("EN 1"))
        assertTrue(b.endsWith("3"))
        assertTrue(WatchDigest.body(list, "", 0).startsWith("Языки: EN 1"))
    }

    @Test
    fun monitorConfigKeepsAnyLang() {
        val m = MonitorConfig(anyLang = true)
        assertTrue(MonitorConfig.from(m.toJson()).anyLang)
        assertFalse(MonitorConfig.from(org.json.JSONObject()).anyLang)
    }
}

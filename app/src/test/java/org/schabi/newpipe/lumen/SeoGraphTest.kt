/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeoGraphTest {
    @Test
    fun pageRankFavoursRecommendedNodes() {
        // 0,1,2 all recommend 3; 3 recommends 0
        val pr = GraphAlgo.pageRank(4, listOf(0 to 3, 1 to 3, 2 to 3, 3 to 0))
        assertEquals(1.0, pr.sum(), 1e-6)
        assertTrue(pr[3] > pr[0] && pr[0] > pr[1])
        assertEquals(pr[1], pr[2], 1e-9)
    }

    @Test
    fun layoutIsNormalisedAndDeterministic() {
        val e = listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4)
        val (x, y) = GraphAlgo.layout(5, e)
        assertTrue(x.all { it in 0.0..1.0 } && y.all { it in 0.0..1.0 })
        val (x2, _) = GraphAlgo.layout(5, e)
        assertEquals(x.toList(), x2.toList())
        val (sx, sy) = GraphAlgo.layout(1, emptyList())
        assertEquals(0.5, sx[0], 0.0)
        assertEquals(0.5, sy[0], 0.0)
    }

    @Test
    fun relevanceStemsRussianAndEnglish() {
        val kw = Relevance.keywords("электромобили зарядка charging")
        assertTrue(Relevance.matches(kw, "Лучший электромобиль 2026 года"))
        assertTrue(Relevance.matches(kw, "Home charger review"))
        assertTrue(Relevance.matches(kw, "Зарядные станции в Бишкеке"))
        assertFalse(Relevance.matches(kw, "Рецепт борща"))
    }

    @Test
    fun vocClassification() {
        assertTrue(Voc.QUESTION in Voc.classify("Как поменять батарею на телефоне?"))
        assertTrue(Voc.QUESTION in Voc.classify("подскажите где купить"))
        assertTrue(Voc.PAIN in Voc.classify("Третий раз не работает зарядка, надоело"))
        assertTrue(Voc.REQUEST in Voc.classify("Сделайте видео про обслуживание"))
        assertTrue(Voc.OBJECTION in Voc.classify("Слишком дорого, не стоит своих денег"))
        assertTrue(Voc.classify("Отличное видео, спасибо").isEmpty())
        assertTrue(Voc.isSpam("Пиши мне в t.me/xxx заработок"))
        assertTrue(Voc.isSpam("😂😂😂😂😂😂😂😂😂😂"))
        assertFalse(Voc.isSpam("Нормальный длинный комментарий"))
    }

    @Test
    fun vocGroupsExactDuplicates() {
        val msgs = listOf(
            Voc.Msg("v1", "Не работает зарядка!", 2, "u_a", 0, null),
            Voc.Msg("v2", "не работает   зарядка", 10, "u_b", 3, null),
            Voc.Msg("v1", "Как выбрать модель?", 1, "u_a", 1, null)
        )
        val items = Voc.extract(msgs)
        val pain = items.first { it.category == Voc.PAIN }
        assertEquals(2, pain.count)
        assertEquals(2, pain.authors.size)
        assertEquals(10, pain.likes)
        assertEquals("v2", pain.sourceId)
        assertEquals(1, items.count { it.category == Voc.QUESTION })
    }

    @Test
    fun quoteVerificationAndTimestamps() {
        assertTrue(Voc.quoteFound("надоело проверять цены вручную", "Честно, НАДОЕЛО проверять цены вручную каждый день"))
        assertFalse(Voc.quoteFound("автоматизация экономит время", "Честно, надоело проверять цены вручную"))
        assertEquals(125, Voc.timestampSeconds("02:05"))
        assertEquals(3725, Voc.timestampSeconds("1:02:05"))
        val tm = Voc.transcriptMessages("v", "[00:01] Привет всем. Сегодня разберём зарядку.\n[00:10] Почему она не работает зимой?")
        assertTrue(tm.any { it.text.startsWith("Почему") })
        assertEquals("00:01", tm.first().timestamp)
    }

    @Test
    fun csvParserHandlesQuotes() {
        val rows = Csv.parse("text,author\n\"Привет, мир\",a\n\"многострочный\nтекст \"\"в кавычках\"\"\",b\n")
        assertEquals(3, rows.size)
        assertEquals("Привет, мир", rows[1][0])
        assertEquals("многострочный\nтекст \"в кавычках\"", rows[2][0])
        assertEquals("b", rows[2][1])
    }

    @Test
    fun similarityForDuplicates() {
        assertTrue(Voc.similarity("Как выбрать электромобиль", "Как выбрать электромобиль для города") > 0.5)
        assertTrue(Voc.similarity("Как выбрать электромобиль", "Рецепт борща") < 0.1)
    }
}

class LogRedactTest {
    @Test
    fun secretsAreRemoved() {
        val s = L.redact("x-goog-api-key: AIzaSyA1234567890abcdefghijk Authorization=Bearer sk-or-v1-abcdef1234567890abcd key gsk_abcdefghijklmnop123 ok")
        assertFalse(s.contains("AIzaSyA1234567890"))
        assertFalse(s.contains("sk-or-v1-abcdef"))
        assertFalse(s.contains("gsk_abcdefghijklmnop123"))
        assertTrue(s.endsWith("ok"))
        assertEquals("GET api.openai.com/v1/models → 200", L.redact("GET api.openai.com/v1/models → 200"))
    }
}

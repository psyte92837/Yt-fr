/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PainsTest {
    private fun c(text: String, likes: Int = 0, author: String = "u_1") = Comment("", author, text, likes, -1, "", 0, false)

    private val msgs = Pains.messages(listOf(
        "v1" to listOf(
            c("Спина болит уже третий год, ничего не помогает, мучаюсь каждое утро", 40, "u_a"),
            c("Я пробовал массаж и уколы, был у трёх врачей, потратил кучу денег, а итог — снова боль", 25, "u_b"),
            c("Как понять, что это грыжа?", 10, "u_c"),
            c("Отличное видео!", 3, "u_d")
        ),
        "v2" to listOf(
            c("Дорого, не стоит своих денег", 7, "u_e"),
            c("Сделайте видео про упражнения для шеи", 5, "u_f")
        )
    ))

    @Test
    fun numbersCommentsAcrossVideos() {
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), msgs.map { it.id })
        assertEquals("v2", msgs[4].videoId)
    }

    @Test
    fun detectsFirstPersonStories() {
        assertTrue(Pains.isCase(msgs[1].text))
        assertTrue(Pains.isCase("I tried three apps for months and bought a premium plan, it was useless and I stopped"))
        assertFalse(Pains.isCase("Отличное видео, спасибо автору за труд и подачу материала!"))
        assertFalse(Pains.isCase("я был"))
    }

    @Test
    fun localCountsSignals() {
        val l = Pains.local(msgs)
        assertEquals(6, l.total)
        assertTrue((l.counts[Voc.PAIN] ?: 0) >= 1)
        assertEquals(1, l.counts[Voc.OBJECTION])
        assertEquals(1, l.counts[Voc.REQUEST])
        assertTrue((l.counts[Voc.QUESTION] ?: 0) >= 1)
        assertEquals(1, l.counts[Pains.CASE])
    }

    @Test
    fun aiInputPutsSignalsFirstAndRespectsLimit() {
        val s = Pains.aiInput(msgs, 10_000)
        val lines = s.trim().lines()
        assertTrue(lines.first().startsWith("[c1 ♥40]"))
        assertTrue(lines.last().contains("Отличное видео"))
        val short = Pains.aiInput(msgs, 120)
        assertTrue(short.length <= 120)
        assertEquals(1, short.trim().lines().size)
    }

    @Test
    fun parsesReportAndChecksQuotes() {
        val reply = """```json
            {"summary":"Люди с хронической болью в спине.",
             "pains":[
               {"title":"Боль не проходит","detail":"Годами","severity":5,"ids":["c1","c2","c99"],
                "quotes":[{"id":"c1","text":"ничего не помогает, мучаюсь каждое утро"},{"id":"c2","text":"придуманная цитата которой нет нигде вообще"}]},
               {"title":"Дорого","severity":3,"ids":["c5"],"quotes":[{"id":"c3","text":"Дорого, не стоит своих денег"}]}
             ],
             "cases":[{"who":"мужчина","situation":"три года боли","tried":"массаж, уколы","outcome":"не помогло","need":"понятный план",
                       "quote":{"id":"c2","text":"был у трёх врачей, потратил кучу денег"}}],
             "questions":["Как понять, что это грыжа?"],"objections":[],"ideas":["Видео про диагностику"]}
            ```"""
        val r = Pains.parse(reply, msgs)!!
        assertEquals("Люди с хронической болью в спине.", r.summary)
        assertEquals(2, r.pains.size)
        val first = r.pains[0]
        assertEquals("Боль не проходит", first.title)
        assertEquals(listOf("c1", "c2"), first.ids) // unknown c99 dropped
        assertEquals(1, first.quotes.size)
        assertEquals("c5", r.pains[1].quotes.single().id) // wrong id, quote found elsewhere
        assertEquals(1, r.dropped)
        assertEquals("c2", r.cases.single().quote!!.id)
        assertEquals(listOf("Видео про диагностику"), r.ideas)
    }

    @Test
    fun skipsBrokenElements() {
        val reply = """{"pains":[{"title","Сломано"},{"title":"Целое","severity":9,"ids":["c1"]}],"cases":[{"situation":"история"}"""
        val r = Pains.parse(reply, msgs)
        assertNotNull(r)
        assertEquals(listOf("Целое"), r!!.pains.map { it.title })
        assertEquals(5, r.pains[0].severity)
    }

    @Test
    fun unusableReplyIsNull() {
        assertNull(Pains.parse("Извините, не могу", msgs))
        assertNull(Pains.parse("{\"summary\":\"нет массивов\"", msgs))
    }

    @Test
    fun markdownHasSectionsWithAndWithoutAi() {
        val loc = Pains.local(msgs)
        val noAi = Pains.markdown("Видео", "https://youtu.be/x", loc, null)
        assertTrue(noAi.contains("Боли и кейсы людей"))
        assertTrue(noAi.contains("Сигналы из комментариев"))
        assertTrue(noAi.contains("AI-разбор не выполнен"))
        val r = Pains.parse("""{"pains":[{"title":"Боль","ids":["c1"]}],"cases":[{"who":"он","situation":"история"}]}""", msgs)
        val md = Pains.markdown("Видео", "", loc, r, "model-x")
        assertTrue(md.contains("1. **Боль** — упоминаний: 1"))
        assertTrue(md.contains("## Кейсы людей"))
        assertTrue(md.contains("AI: model-x"))
    }
}

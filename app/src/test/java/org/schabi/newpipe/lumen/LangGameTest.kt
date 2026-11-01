package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangGameTest {
    private fun doc(l: String, s: String, w: Long = 0) = LangGame.Doc(l, s, w)

    private val docs = listOf(
        doc(LangGame.TITLE, "5 ОШИБОК при ремонте ванной: как не попасть на деньги?", 1000),
        doc(LangGame.TITLE, "5 ошибок плиточника, которые стоят дорого", 500),
        doc(LangGame.TITLE, "Как выбрать гидроизоляцию для ванной", 300),
        doc(LangGame.DESC, "Гидроизоляция ванной комнаты, гидроизоляция пола, гидроизоляция стен"),
        doc(LangGame.COMMENT, "Ребят, а у меня плитка отвалилась через месяц, кошмар, мастер косячник", 40),
        doc(LangGame.COMMENT, "Мастер косячник, плитка отвалилась, что делать?", 12),
        doc(LangGame.COMMENT, "У нас тоже мастер косячник, всё переделывали, плитка отвалилась", 3),
        doc(LangGame.COMMENT, "Спасибо, очень полезно! Вы лучший", 100),
        doc(LangGame.COMMENT, "А гидроизоляция обязательна или можно без неё?", 7)
    )

    @Test
    fun shibbolethsAreAudienceWords() {
        val g = LangGame.analyse(docs, JSONObject().put("phrase", "ремонт ванной"), now = 1)
        assertTrue(LangGame.valid(g))
        val sh = g.getJSONObject("lexicon").getJSONArray("shibboleths").toString()
        assertTrue(sh, sh.contains("косячник"))
        assertFalse(sh, sh.contains("гидроизоляция\""))
        val jar = g.getJSONObject("lexicon").getJSONArray("jargon").toString()
        assertTrue(jar, jar.contains("гидроизоляц"))
    }

    @Test
    fun formulasFindRepeatedPhrases() {
        val f = LangGame.formulas(docs.filter { it.layer == LangGame.COMMENT }.map { it.text })
        assertTrue(f.toString(), f.any { it.w == "плитка отвалилась" && it.n == 3 })
        assertTrue(f.toString(), f.any { it.w == "мастер косячник" })
    }

    @Test
    fun markersCountPronounsAndModality() {
        val m = LangGame.markers(listOf("Мы всегда должны помнить: они никогда не помогут", "Я думаю, наверное, можно"))
        assertTrue(m.getDouble("we") > 0)
        assertTrue(m.getDouble("they") > 0)
        assertTrue(m.getDouble("must") > 0)
        assertTrue(m.getDouble("hedge") > 0)
        assertTrue(m.getDouble("absolute") > 0)
        assertEquals(0.5, m.getDouble("we_vs_they"), 0.001)
    }

    @Test
    fun titleShapesAndAddress() {
        assertEquals("N ошибок … [N ? : CAPS]", LangGame.titleShape("5 ОШИБОК при ремонте ванной: как не попасть на деньги?"))
        val g = LangGame.analyse(docs, now = 1)
        val t = g.getJSONObject("titles")
        assertEquals(3, t.getInt("count"))
        assertEquals(0.67, t.getDouble("numbers"), 0.01)
        val a = LangGame.address(listOf("Ребят, вы где брали?", "Вам спасибо, ваш канал топ"))
        assertEquals("vy", a.getString("register"))
        assertTrue(a.getJSONArray("group").toString().contains("ребят"))
    }

    @Test
    fun quotesSortedByLikes() {
        val g = LangGame.analyse(docs, now = 1)
        assertTrue(g.getJSONArray("quotes").getString(0).startsWith("Спасибо"))
        assertTrue(g.getJSONObject("voc").getJSONObject("question").getDouble("share") > 0)
    }

    @Test
    fun readingKeepsKnownFields() {
        val r = LangGame.reading(JSONObject("""{"form_of_life":"ремонтники","junk":1,"shibboleths":[{"word":"косячник","meaning":"плохой мастер"},""],"hooks":["Плитка отвалилась?"]}"""))
        assertEquals("ремонтники", r.getString("form_of_life"))
        assertFalse(r.has("junk"))
        assertEquals(1, r.getJSONArray("shibboleths").length())
        assertEquals("косячник", r.getJSONArray("shibboleths").getJSONObject(0).getString("word"))
    }

    @Test
    fun briefCarriesProtocolTaskAndData() {
        val g = LangGame.analyse(docs, JSONObject().put("phrase", "ремонт ванной"), now = 1)
        val b = LangGame.brief(g, LangGame.Kind.PODCAST, "", "без рекламы", "русский")
        assertTrue(b.contains("lumen.langgame v1"))
        assertTrue(b.contains("подкаста"))
        assertTrue(b.contains("Тема: ремонт ванной"))
        assertTrue(b.contains("без рекламы"))
        assertTrue(b.contains("косячник"))
    }

    @Test
    fun compactFitsLimit() {
        val many = (1..400).map { doc(LangGame.COMMENT, "слово$it другое$it третье$it слово$it другое$it") }
        val g = LangGame.analyse(many, now = 1)
        assertTrue(LangGame.compact(g, 3000).length <= 3000)
    }

    @Test
    fun scriptAndTagsFromAnswer() {
        val md = """
            # Плитка отвалилась: кто виноват
            ## Хук
            Плитка отвалилась через месяц?

            ## Сценарий
            - **Первое**: мастер-косячник экономит на клее.

            [Кадр: плитка] 0:15 — Второе: нет гидроизоляции.
            ## Теги
            #ремонт ванной, плитка
            - гидроизоляция
        """.trimIndent()
        assertEquals("Плитка отвалилась: кто виноват", LangGame.sections(md).first)
        assertEquals("Плитка отвалилась через месяц?\n\nПервое: мастер-косячник экономит на клее.\n\nВторое: нет гидроизоляции.", LangGame.script(md))
        assertEquals(listOf("ремонт ванной", "плитка", "гидроизоляция"), LangGame.tags(md))
    }

    @Test
    fun latinOnlyForCyrillicCorpus() {
        assertTrue(LangGame.latin(listOf("купил новый iphone вчера вечером", "старый iphone сломался совсем быстро")).toString().contains("iphone"))
        assertEquals(0, LangGame.latin(listOf("buy the iphone now", "iphone is great")).length())
    }
}

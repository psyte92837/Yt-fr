package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {
    private val v1 = Video("a1", "https://youtu.be/a1", title = "Плитка отвалилась", channel = "Ремонт ТВ", views = 1000, likes = 50,
        duration = 125, tags = mutableListOf("плитка", "ремонт"), description = "Почему отваливается плитка")
    private val v2 = Video("b2", "", title = "Гидроизоляция", views = 10)
    private val comments = mapOf(
        "a1" to listOf(
            Comment("c1", "Иван", "Мастер косячник", 3, -1, "", 0, false),
            Comment("c2", "Пётр", "Согласен", 9, -1, "2 дня назад", 0, true),
            Comment("c3", "Аня", "  ", 0, -1, "", 0, false)
        )
    )
    private val src = Artifacts.Source(
        JSONObject().put("name", "Ванная").put("phrase", "ремонт ванной"),
        listOf(v1, v2),
        listOf(ChannelData("https://youtube.com/@remont", name = "Ремонт ТВ", subscribers = 1200, description = "Про ремонт",
            recent = mutableListOf(Video("r1", "", title = "Шпаклёвка", views = 77)))),
        { comments[it].orEmpty() },
        { if (it == "a1") "0:01 Привет\n\n\n\n0:05 Сегодня плитка" else null }
    )

    @Test
    fun markdownHasEverythingAndHidesAuthors() {
        val sb = StringBuilder()
        Artifacts.markdown(src, Artifacts.Opts(), sb, now = 0)
        val s = sb.toString()
        assertTrue(s.startsWith("# Ванная"))
        assertTrue(s.contains("lumen.artifacts v1"))
        assertTrue(s.contains("каналов 1, видео 2, расшифровок 1, комментариев 2"))
        assertTrue(s.contains("- подписчиков: 1200"))
        assertTrue(s.contains("  - Шпаклёвка (77 просм.)"))
        assertTrue(s.contains("### 1. Плитка отвалилась"))
        assertTrue(s.contains("просмотров 1000, лайков 50, длительность 2:05"))
        assertTrue(s.contains("- теги: плитка, ремонт"))
        assertTrue(s.contains("0:01 Привет\n\n0:05 Сегодня плитка"))
        assertTrue(s.contains("- Мастер косячник [3 ♥]"))
        assertTrue(s.contains("  - Согласен [9 ♥]"))
        assertTrue(s.contains("https://www.youtube.com/watch?v=b2"))
        assertFalse(s.contains("Иван"))
    }

    @Test
    fun optionsDropParts() {
        val sb = StringBuilder()
        Artifacts.markdown(src, Artifacts.Opts(comments = false, transcripts = false, descriptions = false, authors = true), sb, now = 0)
        val s = sb.toString()
        assertFalse(s.contains("Мастер косячник"))
        assertFalse(s.contains("Сегодня плитка"))
        assertFalse(s.contains("Почему отваливается"))
    }

    @Test
    fun jsonIsValidAndRespectsLimit() {
        val sb = StringBuilder()
        Artifacts.json(src, Artifacts.Opts(authors = true, maxComments = 1), sb, now = 5)
        val o = JSONObject(sb.toString())
        assertEquals("lumen.artifacts", o.getString("protocol"))
        assertFalse(o.getBoolean("ai"))
        assertEquals(1, o.getJSONArray("channels").length())
        val v = o.getJSONArray("videos").getJSONObject(0)
        assertEquals("a1", v.getString("id"))
        assertTrue(v.getString("transcript").contains("Сегодня плитка"))
        val c = v.getJSONArray("comments")
        assertEquals(1, c.length())
        assertEquals("Пётр", c.getJSONObject(0).getString("author"))
        assertFalse(o.getJSONArray("videos").getJSONObject(1).has("transcript"))
    }

    @Test
    fun plainTextHasNoMarkup() {
        val sb = StringBuilder()
        Artifacts.markdown(src, Artifacts.Opts(), sb, now = 0, plain = true)
        val s = sb.toString()
        assertTrue(s.startsWith("Ванная\n======"))
        assertTrue(s.contains("ВИДЕО\n-----"))
        assertTrue(s.contains("▌ 1. Плитка отвалилась"))
        assertFalse(s.contains("**"))
        assertFalse(s.contains("### "))
    }

    @Test
    fun htmlIsEscapedWithContents() {
        val v = Video("x", "", title = "<script>alert(1)</script> & co")
        val sb = StringBuilder()
        Artifacts.html(Artifacts.Source(JSONObject().put("name", "Т"), listOf(v1, v), emptyList(), { comments[it].orEmpty() }, { null }), Artifacts.Opts(), sb, now = 0)
        val s = sb.toString()
        assertTrue(s.startsWith("<!doctype html>"))
        assertTrue(s.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; co"))
        assertFalse(s.contains("<script>"))
        assertTrue(s.contains("<a href=\"#v2\">"))
        assertTrue(s.contains("<summary>Комментарии (2)</summary>"))
        assertTrue(s.contains("prefers-color-scheme:dark"))
        assertTrue(s.trimEnd().endsWith("</html>"))
    }

    @Test
    fun chunksKeepPiecesUnderLimit() {
        val text = "Первый абзац. Короткий.\n\n" + "Очень длинное предложение номер один. ".repeat(30) + "\n\nКонец"
        val c = Artifacts.chunks(text, 200)
        assertTrue(c.all { it.length <= 200 })
        assertTrue(c.first().startsWith("Первый абзац. Короткий."))
        assertEquals("Конец", c.last().substringAfterLast("\n\n").substringAfterLast(" "))
        assertEquals(listOf("ab", "cd"), Artifacts.chunks("abcd", 2))
        assertEquals(listOf("один\n\nдва"), Artifacts.chunks("один\n\nдва", 100))
    }

    @Test
    fun numbersAndStats() {
        assertEquals("1 234 567", Artifacts.num(1234567))
        assertEquals("999", Artifacts.num(999))
        assertEquals("1:02:03", Artifacts.duration(3723))
        assertEquals("👁 1 000 · 👍 50 · ⏱ 2:05 · 💬 2", Artifacts.stats(v1, 2))
        assertEquals("   ↳ ♥9  Пётр: Согласен", Artifacts.commentLine(comments.getValue("a1")[1], true))
        assertEquals(Artifacts.Format.PDF, Artifacts.Format.of("artifacts_pdf"))
    }
}

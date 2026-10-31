/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BotPostsTest {
    @Test
    fun parsesChatAddresses() {
        assertEquals("@my_channel", PostCore.parseChat("@my_channel"))
        assertEquals("@my_channel", PostCore.parseChat("https://t.me/my_channel/"))
        assertEquals("@news24", PostCore.parseChat("news24"))
        assertEquals("-1001234567890", PostCore.parseChat("-1001234567890"))
        assertEquals("-100123456", PostCore.parseChat("t.me/c/123456/78"))
        assertNull(PostCore.parseChat("https://t.me/+AbCdEf"))
        assertNull(PostCore.parseChat("ab"))
        assertNull(PostCore.parseChat(""))
    }

    @Test
    fun parsesRss() {
        val xml = """<?xml version="1.0"?><rss><channel><title>Feed</title>
            <item><title><![CDATA[Первая &amp; новость]]></title><link>https://ex.com/1</link><guid>g1</guid>
            <description><![CDATA[<p>Текст <b>новости</b></p><img src="https://ex.com/1.jpg">]]></description></item>
            <item><title>Вторая</title><link>https://ex.com/2</link><enclosure url="https://ex.com/2.png" type="image/png"/>
            <description>Просто текст</description></item>
            </channel></rss>"""
        val items = PostCore.parseFeed(xml)
        assertEquals(2, items.size)
        assertEquals("Первая & новость", items[0].title)
        assertEquals("g1", items[0].key)
        assertEquals("Текст новости", items[0].summary)
        assertEquals("https://ex.com/1.jpg", items[0].image)
        assertEquals("https://ex.com/2", items[1].key)
        assertEquals("https://ex.com/2.png", items[1].image)
    }

    @Test
    fun parsesAtom() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>yt:video:abc</id><title>Видео</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
            <media:group><media:thumbnail url="https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" width="480"/>
            <media:description>Описание</media:description></media:group></entry></feed>"""
        val items = PostCore.parseFeed(xml)
        assertEquals(1, items.size)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", items[0].link)
        assertEquals("yt:video:abc", items[0].key)
        assertEquals("Описание", items[0].summary)
        assertTrue(items[0].image.contains("ytimg"))
    }

    @Test
    fun freshSkipsSeenAndKeepsOrder() {
        val items = (1..5).map { FeedItem("k$it", "t$it", "", "s") }
        val f = PostCore.fresh(items, listOf("k1"), 2)
        assertEquals(listOf("k3", "k2"), f.map { it.key })
        assertEquals(listOf("b", "c"), PostCore.remember(listOf("a", "b"), listOf("b", "c"), keep = 2))
    }

    @Test
    fun parsesSources() {
        val s = PostCore.parseSources("https://a.com/rss\n# comment\nyt: обзор\nai: советы\nмусор\nai:")
        assertEquals(listOf(PostSource.RSS, PostSource.YOUTUBE, PostSource.AI), s.map { it.type })
        assertEquals("обзор", s[1].value)
        assertEquals("https://a.com/rss\nyt: обзор\nai: советы", PostCore.sourcesText(s))
    }

    @Test
    fun cleansAiHtml() {
        assertEquals("<b>Заголовок</b>\nтекст &amp; ещё", PostCore.tgHtml("```html\n**Заголовок**\nтекст & ещё\n```"))
        assertEquals("<strong>Да</strong> 2 &lt; 3", PostCore.tgHtml("<p><strong>Да</strong> 2 < 3</p>"))
        assertEquals("<a href=\"https://x.com/?a=1&amp;b=2\">ссылка</a>", PostCore.tgHtml("<a href=\"https://x.com/?a=1&amp;b=2\" target=\"_blank\">ссылка</a>"))
        assertEquals("скрипт", PostCore.tgHtml("<a href=\"javascript:alert(1)\">скрипт</a>"))
    }

    @Test
    fun rawPostEscapes() {
        val p = PostCore.rawPost(FeedItem("k", "A <b> & B", "https://ex.com", "A <b> & B. Длинный текст."), withLink = true)
        assertTrue(p.startsWith("<b>A &lt;b&gt; &amp; B</b>"))
        assertTrue(p.contains("Длинный текст."))
        assertTrue(p.endsWith("</a>"))
        assertEquals(4, PostCore.visibleLength("<b>abcd</b>"))
    }

    @Test
    fun planDueRespectsIntervalAndWindow() {
        val p = PostPlan("1", "n", listOf(PostSource(PostSource.AI, "x")), listOf("@c"), everyMin = 60, fromHour = 22, toHour = 6, lastRun = 0)
        val now = 10_000_000_000L
        assertTrue(PostCore.due(p, now, 23))
        assertTrue(PostCore.due(p, now, 3))
        assertFalse(PostCore.due(p, now, 12))
        p.lastRun = now - 30 * 60_000L
        assertFalse(PostCore.due(p, now, 23))
        p.lastRun = 0
        p.enabled = false
        assertFalse(PostCore.due(p, now, 23))
    }

    @Test
    fun parsesWhen() {
        val tz = TimeZone.getTimeZone("UTC")
        val c = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.OCTOBER, 31, 12, 0, 0) }
        val now = c.timeInMillis
        assertEquals(now + 30 * 60_000L, PostCore.parseWhen("+30м", now, tz))
        assertEquals(now + 2 * 3_600_000L, PostCore.parseWhen("+2h", now, tz))
        assertEquals(now + 6 * 3_600_000L + 30 * 60_000L, PostCore.parseWhen("18:30", now, tz))
        assertEquals(now + 22 * 3_600_000L, PostCore.parseWhen("10:00", now, tz)) // tomorrow
        val dec = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.DECEMBER, 25, 9, 0, 0) }.timeInMillis
        assertEquals(dec, PostCore.parseWhen("25.12 09:00", now, tz))
        assertNull(PostCore.parseWhen("31.02 10:00", now, tz))
        assertNull(PostCore.parseWhen("завтра", now, tz))
    }

    @Test
    fun chatsFromUpdates() {
        val body = """{"ok":true,"result":[
            {"update_id":1,"my_chat_member":{"chat":{"id":-1001,"title":"Канал","type":"channel"},"new_chat_member":{"status":"administrator"}}},
            {"update_id":2,"my_chat_member":{"chat":{"id":-5,"title":"Группа","type":"group"},"new_chat_member":{"status":"left"}}},
            {"update_id":3,"message":{"chat":{"id":42,"type":"private"},"text":"hi"}}]}"""
        val c = PostCore.chatsFromUpdates(body)
        assertEquals(listOf(Triple("-1001", "Канал", "channel"), Triple("-5", "Группа", "left")), c)
    }

    @Test
    fun postsDataRoundTrip() {
        val d = PostsData()
        d.targets.add(PostTarget("@c", "C", "channel"))
        d.plans.add(PostPlan("p", "News", listOf(PostSource(PostSource.RSS, "https://a/rss")), listOf("@c"), seen = listOf("k")))
        d.queue.add(BotPost("q", "<b>x</b>", targets = listOf("@c"), at = 5))
        repeat(250) { d.addHistory(PostLog(it.toLong(), "@c", "t", true, "")) }
        val back = PostsData.from(JSONObject(d.toJson().toString()))
        assertEquals("C", back.target("@C")?.title)
        assertEquals(listOf("k"), back.plans[0].seen)
        assertEquals(5L, back.queue[0].at)
        assertEquals(200, back.history.size)
        assertEquals(249L, back.history[0].at)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTest {
    @Test
    fun parsesEnglishCardWithGoToChannel() {
        val it = FeedParse.parse(
            "Never Gonna Give You Up - 3 minutes, 33 seconds - Go to channel - Rick Astley - 1.5 billion views - 15 years ago - play video"
        )!!
        assertEquals("Never Gonna Give You Up", it.title)
        assertEquals("Rick Astley", it.channel)
        assertEquals(1_500_000_000L, it.views)
        assertEquals("15 years ago", it.age)
        assertEquals("3 minutes, 33 seconds", it.duration)
        assertFalse(it.short)
    }

    @Test
    fun parsesRussianCard() {
        val it = FeedParse.parse(
            "Как работает алгоритм YouTube – 12 минут 5 секунд – Перейти на канал – Канал Про Видео – 1,2 млн просмотров – 3 дня назад – воспроизвести видео"
        )!!
        assertEquals("Как работает алгоритм YouTube", it.title)
        assertEquals("Канал Про Видео", it.channel)
        assertEquals(1_200_000L, it.views)
        assertEquals("3 дня назад", it.age)
    }

    @Test
    fun titleWithDashesKeepsItsParts() {
        val it = FeedParse.parse("Song - Artist (Official Video) - ArtistVEVO - 12 345 views - 2 years ago")!!
        assertEquals("Song - Artist (Official Video)", it.title)
        assertEquals("ArtistVEVO", it.channel)
        assertEquals(12_345L, it.views)
    }

    @Test
    fun parsesShortsAndTimecode() {
        val s = FeedParse.parse("Funny cat jumps, 2.3M views - play Short")!!
        assertEquals("Funny cat jumps", s.title)
        assertEquals(2_300_000L, s.views)
        assertTrue(s.short)
        val t = FeedParse.parse("Big tutorial - 10:05 - Some Channel - 45K views - 1 month ago")!!
        assertEquals("Big tutorial", t.title)
        assertEquals("Some Channel", t.channel)
        assertEquals("10:05", t.duration)
        assertEquals(45_000L, t.views)
    }

    @Test
    fun titleThatMentionsViewsIsNotMetadata() {
        val it = FeedParse.parse("I got 1M views in a day - Creator - 500K views - 2 days ago")!!
        assertEquals("I got 1M views in a day", it.title)
        assertEquals("Creator", it.channel)
        assertEquals(500_000L, it.views)
    }

    @Test
    fun liveAndNoViews() {
        val l = FeedParse.parse("Lofi radio - LIVE - Lofi Girl - 12K watching")!!
        assertTrue(l.live)
        assertEquals("Lofi radio", l.title)
        val n = FeedParse.parse("Fresh upload - Tiny Channel - No views - 5 minutes ago")!!
        assertEquals(0L, n.views)
    }

    @Test
    fun rejectsAdsAndButtons() {
        assertNull(FeedParse.parse(null))
        assertNull(FeedParse.parse(""))
        assertNull(FeedParse.parse("Subscribe"))
        assertNull(FeedParse.parse("Search YouTube"))
        assertNull(FeedParse.parse("Sponsored - Buy now - 1M views"))
        assertNull(FeedParse.parse("Реклама – Купите – воспроизвести видео"))
    }

    @Test
    fun viewCounts() {
        assertEquals(1_000L, FeedParse.views("1 тыс. просмотров"))
        assertEquals(15_000_000L, FeedParse.views("15 млн просмотров"))
        assertEquals(1_234_567L, FeedParse.views("1,234,567 views"))
        assertEquals(3_400L, FeedParse.views("3.4K views"))
        assertEquals(0L, FeedParse.views("No views"))
        assertEquals(-1L, FeedParse.views("hello"))
    }

    @Test
    fun normalisedKeys() {
        assertEquals(FeedParse.key("Ёлка: ИТОГИ!"), FeedParse.key("елка итоги"))
        assertEquals("", FeedParse.key("!!!"))
    }

    @Test
    fun watchPageTitleAndChannel() {
        val nodes = listOf(
            WatchPick.Node("0:42 / 10:05", 300),
            WatchPick.Node("How the YouTube algorithm really works", 620),
            WatchPick.Node("1.2M views  3 days ago  ...more", 700),
            WatchPick.Node("Some Channel", 780),
            WatchPick.Node("120K subscribers", 800),
            WatchPick.Node("Subscribe", 790),
            WatchPick.Node("Share", 900)
        )
        assertEquals("How the YouTube algorithm really works", WatchPick.title(nodes, 600))
        assertEquals("Some Channel", WatchPick.channel(nodes, 600))
        assertNull(WatchPick.title(listOf(WatchPick.Node("Share", 700), WatchPick.Node("12 345", 720)), 600))
    }

    @Test
    fun matchPicksTheSameVideo() {
        assertEquals(1.0, FeedMatch.similarity("Hello World!", "hello world"), 1e-9)
        assertTrue(FeedMatch.similarity("Как работает алгоритм YouTube", "Как работает алгоритм YouTube в 2026 году") >= 0.85)
        val c = listOf(
            FeedMatch.Cand("Totally different video", "X", "u1"),
            FeedMatch.Cand("Как работает алгоритм YouTube", "Канал Про Видео", "u2"),
            FeedMatch.Cand("Как работает алгоритм YouTube", "Другой", "u3")
        )
        assertEquals("u2", FeedMatch.best("Как работает алгоритм YouTube", "Канал Про Видео", c)?.url)
        assertNull(FeedMatch.best("Рецепт борща", "Кухня", c))
    }

    @Test
    fun sessionDedupesAndCounts() {
        val s = FeedSession()
        val a = FeedItem("Video A", "Ch1", 100)
        val b = FeedItem("Video B", "Ch1", 300, short = true)
        assertEquals(2, s.add(listOf(a, b), 1_000))
        assertEquals(0, s.add(listOf(a.copy(views = 150)), 2_000)) // same screen while scrolling
        assertEquals(1, s.items[a.key]!!.count)
        assertEquals(150L, s.items[a.key]!!.item.views)
        assertEquals(1, s.add(listOf(FeedItem("Video C", "Ch2", 200), a.copy(views = 150)), 60_000))
        assertEquals(2, s.items[a.key]!!.count)
        val st = s.stats()
        assertEquals(3, st.videos)
        assertEquals(2, st.channels)
        assertEquals(1, st.shorts)
        assertEquals(200L, st.medianViews)
        assertEquals(1, st.repeated)
        assertEquals("Ch1" to 2, s.topChannels().first())
        val p = s.toPrompt()
        assertTrue(p.contains("Video A | Ch1 | 150 views"))
        assertTrue(p.contains("shown 2×"))
        s.setUrl(a.key, "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val back = FeedSession.from(JSONObject(s.toJson().toString()))
        assertEquals(3, back.items.size)
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa", back.items[a.key]!!.item.url)
        assertEquals(2, back.items[a.key]!!.count)
    }

    @Test
    fun sessionIsBounded() {
        val s = FeedSession()
        s.add((1..FeedSession.MAX + 50).map { FeedItem("Video number $it") }, 1)
        assertEquals(FeedSession.MAX, s.items.size)
        assertNotNull(s.items[FeedParse.key("Video number ${FeedSession.MAX + 50}")])
    }

    @Test
    fun queueRules() {
        val q = mutableListOf<QItem>()
        assertTrue(QueueLogic.add(q, QItem("u1", "A")))
        assertFalse(QueueLogic.add(q, QItem("u1", "A again")))
        assertTrue(QueueLogic.add(q, QItem("u1", "A", mode = "pains")))
        assertFalse(QueueLogic.add(q, QItem("", "no url")))
        assertTrue(QueueLogic.add(q, QItem("u2", "B")))
        assertEquals("u1", QueueLogic.next(q)?.url)
        q[0].status = "running"
        assertEquals("pains", QueueLogic.next(q)?.mode)
        q[0].status = "done"
        assertTrue(QueueLogic.add(q, QItem("u1", "A once more")))
        q[1].status = "running"
        QueueLogic.recover(q)
        assertEquals("wait", q[1].status)
        assertEquals(mapOf("done" to 1, "wait" to 3), QueueLogic.counts(q))
        val back = QueueLogic.fromJson(QueueLogic.toJson(q))
        assertEquals(q, back)
        assertTrue(QueueLogic.fromJson("junk").isEmpty())
        assertTrue(QueueLogic.fromJson(null).isEmpty())
    }

    @Test
    fun bubbleGeometry() {
        assertEquals(8, LensPos.snapX(100, 50, 1000, 8))
        assertEquals(942, LensPos.snapX(700, 50, 1000, 8))
        assertEquals(8, LensPos.clampY(-20, 50, 2000, 8))
        assertEquals(1942, LensPos.clampY(5000, 50, 2000, 8))
        assertTrue(LensPos.isTap(3f, 4f, 10))
        assertFalse(LensPos.isTap(30f, 4f, 10))
    }

    @Test
    fun appsAndPrompts() {
        assertTrue("com.google.android.youtube" in LensApps.YOUTUBE)
        assertTrue(LensApps.isTransient("com.android.systemui", "org.lumen.analyzer"))
        assertTrue(LensApps.isTransient("com.google.android.inputmethod.latin", "org.lumen.analyzer"))
        assertFalse(LensApps.isTransient("com.android.launcher3", "org.lumen.analyzer"))
        assertTrue(LENS_PROMPTS.getValue("lens_feed").contains("{data}"))
        assertTrue(LENS_PROMPTS.getValue("lens_screen").contains("{lang}"))
    }
}

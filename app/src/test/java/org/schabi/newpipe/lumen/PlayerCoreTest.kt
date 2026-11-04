/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCoreTest {
    // 2026-11-04 12:00 UTC
    private val now = 1793793600000L
    private val day = PlayerCore.DAY

    private fun item(id: String, ageDays: Long = 3, views: Long = 5000, dur: Long = 600, short: Boolean = false, comments: Int = -1) =
        PItem(id, "Video $id", "Chan", uploadMs = now - ageDays * day, views = views, duration = dur, isShort = short, comments = comments)

    @Test
    fun periodsYearsAndYouTubeQuery() {
        assertEquals(listOf(2026, 2025, 2024), PlayerCore.years(now, 3))
        val y = PFilter(period = "y2024")
        assertEquals(2024, PlayerCore.year(y))
        val r = PlayerCore.range(y, now)!!
        assertEquals("2024-01-01", PlayerCore.iso(r.first))
        assertEquals("2024-12-31", PlayerCore.iso(r.last))
        assertEquals("cats after:2023-12-31 before:2025-01-02", PlayerCore.ytQuery(" cats ", y, now))
        assertEquals("cats after:" + PlayerCore.iso(now - 8 * day), PlayerCore.ytQuery("cats", PFilter(period = "week"), now))
        assertEquals("cats", PlayerCore.ytQuery("cats", PFilter(), now))
        assertNull(PlayerCore.range(PFilter(), now))
        assertEquals(0, PlayerCore.year(PFilter(period = "year")))
    }

    @Test
    fun localFilters() {
        val f = PFilter(period = "week", views = 10_000, length = "medium", shorts = "no")
        assertTrue(PlayerCore.passes(item("a", ageDays = 2, views = 20_000), f, now))
        assertFalse(PlayerCore.passes(item("b", ageDays = 10, views = 20_000), f, now))
        assertFalse(PlayerCore.passes(item("c", views = 9_999), f, now))
        assertFalse(PlayerCore.passes(item("d", views = -1), f, now))
        assertFalse(PlayerCore.passes(item("e", views = 20_000, dur = 100), f, now))
        assertFalse(PlayerCore.passes(item("f", views = 20_000, short = true), f, now))
        // unknown upload date is not cut by the period
        assertTrue(PlayerCore.passes(item("g", views = 20_000).copy(uploadMs = -1), f, now))
        assertTrue(PlayerCore.passes(item("h", dur = 45), PFilter(shorts = "only"), now))
        assertFalse(PlayerCore.passes(item("i", dur = 300), PFilter(shorts = "only"), now))
        assertTrue(PlayerCore.passes(item("j", dur = 3000), PFilter(length = "long"), now))
        assertFalse(PlayerCore.passes(item("k", dur = -1), PFilter(length = "long"), now))
        assertTrue(PlayerCore.passes(item("l", ageDays = 700), PFilter(period = "y2024"), now))
        assertFalse(PlayerCore.passes(item("m", ageDays = 2), PFilter(period = "y2024"), now))
        // comments: not counted = unknown
        val fc = PFilter(comments = 100)
        assertNull(PlayerCore.commentsOk(item("n"), fc))
        assertEquals(true, PlayerCore.commentsOk(item("o", comments = 150), fc))
        assertEquals(false, PlayerCore.commentsOk(item("p", comments = 99), fc))
        assertEquals(true, PlayerCore.commentsOk(item("q"), PFilter()))
        assertEquals(listOf("b", "a"), PlayerCore.sort(listOf(item("a", views = 1), item("b", views = 9)), "views").map { it.id })
        assertEquals(listOf("a", "b"), PlayerCore.sort(listOf(item("b", ageDays = 9), item("a", ageDays = 1)), "date").map { it.id })
        assertEquals(4, PlayerCore.active(f))
        assertEquals(5, PlayerCore.active(f.copy(domain = "tech")))
    }

    @Test
    fun aiCriteriaAndVerdicts() {
        assertFalse(PlayerCore.aiOn(PFilter()))
        val f = PFilter(domain = "tech", rules = listOf("noclick", "bogus"), custom = "  про Android  ")
        val c = PlayerCore.criteria(f, false)
        assertTrue(c.startsWith("Тема: технологии"))
        assertTrue(c.contains("• название честно"))
        assertTrue(c.endsWith("Пожелание: про Android"))
        assertTrue(PlayerCore.criteria(f, true).startsWith("Topic: technology"))
        assertTrue(PlayerCore.aiOn(PFilter(custom = "x")))
        assertEquals(PlayerCore.criteriaKey(f), PlayerCore.criteriaKey(f.copy(period = "week")))
        assertEquals(PFilter(period = "week"), PlayerCore.plain(f.copy(period = "week")))

        val block = PlayerCore.aiBlock(listOf(item("a", dur = 754).copy(desc = "line\nnext"), item("b", dur = -1, views = -1)))
        assertEquals("1. Video a | Chan | 12:34 | 5000 views | line next\n2. Video b | Chan", block)

        val v = PlayerCore.parseVerdicts("```json\n{\"keep\":[1,3],\"drop\":[{\"n\":2,\"why\":\"рыбалка\"},{\"n\":3,\"why\":\"x\"},{\"n\":9,\"why\":\"y\"},4]}\n```", 5)!!
        assertEquals(mapOf(2 to "рыбалка", 4 to ""), v)
        assertEquals(emptyMap<Int, String>(), PlayerCore.parseVerdicts("{\"keep\":[1,2]}", 2))
        assertNull(PlayerCore.parseVerdicts("не JSON", 2))
        assertNull(PlayerCore.parseVerdicts("{\"other\":1}", 2))
        assertTrue(PlayerCore.SCHEMA.has("properties"))
    }

    @Test
    fun searchCacheKeepsNewestAndPrunes() {
        val f = PFilter(period = "week", rules = listOf("b", "a"))
        assertEquals(PlayerCore.key(" Cats  Dogs", f), PlayerCore.key("cats dogs", f.copy(rules = listOf("a", "b"))))
        assertFalse(PlayerCore.key("cats", f) == PlayerCore.key("cats", PFilter()))

        val old = PSearch("cats", PFilter(), now - 10 * day, listOf(item("a")))
        val mid = PSearch("dogs", PFilter(), now - 2 * day, listOf(item("b")))
        var list = PlayerCore.put(PlayerCore.put(emptyList(), old), mid)
        assertEquals(listOf("dogs", "cats"), list.map { it.query })
        // same query and filters replace the older entry
        list = PlayerCore.put(list, PSearch("CATS", PFilter(), now, listOf(item("c"))))
        assertEquals(listOf("CATS", "dogs"), list.map { it.query })
        assertEquals(listOf("dogs"), PlayerCore.prune(listOf(old, mid), now, 7).map { it.query })
        assertEquals(emptyList<PSearch>(), PlayerCore.prune(listOf(mid), now, 0))
        assertEquals(1, PlayerCore.prune(listOf(old, mid), now, 30, max = 1).size)
        assertEquals(listOf("dogs", "cats"), PlayerCore.recent(listOf(old, mid, old.copy(query = "Cats "))))
        assertEquals(listOf("cats"), PlayerCore.recent(listOf(old, mid), "CA"))
        val big = PSearch("x", PFilter(), now, (1..400).map { item("v$it") })
        assertEquals(PlayerCore.MAX_ITEMS, PlayerCore.put(emptyList(), big).first().items.size)
    }

    @Test
    fun searchJsonRoundTripAndDroppedList() {
        val s = PSearch("cats", PFilter(period = "y2025", views = 1000, comments = 10, rules = listOf("expert"), custom = "кошки"), now,
            listOf(item("a", comments = 12), item("b"), item("c")), mapOf("b" to "не про кошек", "c" to "реклама"), setOf("c"), more = true)
        val back = PlayerCore.parseSearches(PlayerCore.searchesJson(listOf(s))).single()
        assertEquals(s, back)
        assertEquals(listOf("a", "c"), back.shown.map { it.id })
        assertEquals(listOf("b"), back.hidden.map { it.id })
        assertEquals(emptyList<PSearch>(), PlayerCore.parseSearches("{bad"))
        assertEquals(emptyList<PSearch>(), PlayerCore.parseSearches(null))
    }

    @Test
    fun historyAndResume() {
        assertEquals(0L, PlayerCore.resumeFrom(10_000, 600_000))
        assertEquals(120_000L, PlayerCore.resumeFrom(120_000, 600_000))
        assertEquals(0L, PlayerCore.resumeFrom(590_000, 600_000))
        assertEquals(120_000L, PlayerCore.resumeFrom(120_000, 0))
        assertEquals(0.5f, PlayerCore.progress(300_000, 600), 0.001f)
        assertEquals(0f, PlayerCore.progress(300_000, -1), 0.001f)
        val a = PWatch("a", "A", duration = 600, posMs = 200_000, at = now)
        val b = PWatch("b", "B", duration = 600, posMs = 5_000, at = now)
        var h = PlayerCore.remember(PlayerCore.remember(emptyList(), a), b)
        h = PlayerCore.remember(h, a.copy(posMs = 300_000))
        assertEquals(listOf("a", "b"), h.map { it.id })
        assertEquals(300_000L, h.first().posMs)
        assertEquals(listOf("a"), PlayerCore.unfinished(h).map { it.id })
        assertEquals(h, PlayerCore.parseHistory(PlayerCore.historyJson(h)))
        assertEquals(3, PlayerCore.remember((1..5).map { PWatch("v$it", "") }, a, max = 3).size)
    }

    @Test
    fun formats() {
        assertEquals("12:34", PlayerCore.clock(754))
        assertEquals("1:02:03", PlayerCore.clock(3723))
        assertEquals("", PlayerCore.clock(0))
        assertEquals(5L to "min", PlayerCore.ago(now - 5 * 60_000, now))
        assertEquals(3L to "d", PlayerCore.ago(now - 3 * day, now))
        assertEquals(2L to "w", PlayerCore.ago(now - 15 * day, now))
        assertEquals(1L to "y", PlayerCore.ago(now - 400 * day, now))
        assertNull(PlayerCore.ago(-1, now))
        assertEquals("999", PlayerCore.count(999))
        assertEquals("1.2K", PlayerCore.count(1234))
        assertEquals("1K", PlayerCore.count(1000))
        assertEquals("12K", PlayerCore.count(12_345))
        assertEquals("1.2M", PlayerCore.count(1_234_567))
        assertEquals("", PlayerCore.count(-1))
    }
}

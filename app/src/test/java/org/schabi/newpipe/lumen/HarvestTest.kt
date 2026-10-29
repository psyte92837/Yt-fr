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

class HarvestTest {
    private fun v(views: Long = 1000, dur: Long = 600, up: Long = 1_700_000_000_000, short: Boolean = false) =
        HarvestItem("x", views = views, duration = dur, uploadMs = up, isShort = short)

    @Test
    fun parsesCounts() {
        assertEquals(10_000L, HarvestRules.parseCount("10k"))
        assertEquals(10_000L, HarvestRules.parseCount("10к"))
        assertEquals(1_500_000L, HarvestRules.parseCount("1,5 млн"))
        assertEquals(2_000_000L, HarvestRules.parseCount("2M"))
        assertEquals(100_000L, HarvestRules.parseCount("100 000"))
        assertEquals(300_000L, HarvestRules.parseCount("300 тыс."))
        assertEquals(0L, HarvestRules.parseCount(""))
        assertEquals(0L, HarvestRules.parseCount("много"))
    }

    @Test
    fun parsesMinutes() {
        assertEquals(600L, HarvestRules.parseMinutes("10"))
        assertEquals(90L, HarvestRules.parseMinutes("1,5"))
        assertEquals(5400L, HarvestRules.parseMinutes("1:30"))
        assertEquals(0L, HarvestRules.parseMinutes(" "))
        assertEquals("10", HarvestRules.minutesText(600))
        assertEquals("", HarvestRules.minutesText(0))
    }

    @Test
    fun cheapFiltersTreatUnknownAsFailOnlyWhenActive() {
        val none = HarvestSpec(mode = "params")
        assertTrue(HarvestRules.cheapOk(none, v(views = -1, dur = -1, up = -1)))
        val s = HarvestSpec(mode = "params", minViews = 5000, minDur = 300, maxDur = 1200, noShorts = true)
        assertFalse(HarvestRules.cheapOk(s, v(views = 1000)))
        assertTrue(HarvestRules.cheapOk(s, v(views = 6000)))
        assertFalse(HarvestRules.cheapOk(s, v(views = 6000, dur = 200)))
        assertFalse(HarvestRules.cheapOk(s, v(views = 6000, dur = 2000)))
        assertFalse(HarvestRules.cheapOk(s, v(views = 6000, dur = -1)))
        assertFalse(HarvestRules.cheapOk(s, v(views = 6000, short = true)))
        assertFalse(HarvestRules.cheapOk(s, v(views = -1)))
    }

    @Test
    fun periodAndYear() {
        val (from, to) = HarvestRules.yearRange(2020)
        assertEquals(1_577_836_800_000L, from) // 2020-01-01 UTC
        assertEquals(1_609_459_200_000L, to) // 2021-01-01 UTC
        val s = HarvestSpec(mode = "period", fromMs = from, toMs = to)
        assertTrue(HarvestRules.cheapOk(s, v(up = from + 1)))
        assertFalse(HarvestRules.cheapOk(s, v(up = to)))
        assertFalse(HarvestRules.cheapOk(s, v(up = from - 1)))
        assertFalse(HarvestRules.cheapOk(s, v(up = -1)))
        val now = 1_800_000_000_000L
        assertEquals(now - 365 * HarvestRules.DAY to -1L, HarvestRules.preset("1y", now))
        assertEquals(-1L to -1L, HarvestRules.preset("??", now))
    }

    @Test
    fun subscribers() {
        val s = HarvestSpec(minSubs = 100_000)
        assertTrue(HarvestRules.needsSubs(s))
        assertTrue(HarvestRules.subsOk(s, 200_000))
        assertFalse(HarvestRules.subsOk(s, -1))
        assertTrue(HarvestRules.subsOk(HarvestSpec(), -1))
    }

    @Test
    fun budgetsAreBounded() {
        assertEquals(5, HarvestRules.budget(HarvestSpec(mode = "related", target = 10)))
        assertEquals(40, HarvestRules.budget(HarvestSpec(mode = "ai", target = 5)))
        assertEquals(600, HarvestRules.budget(HarvestSpec(mode = "period", target = 1000)))
    }

    @Test
    fun aiPromptListsNumberedVideos() {
        val p = HarvestRules.aiPrompt("только обучающие", listOf("A — ch", "B — ch2"), english = false)
        assertTrue(p.contains("Filter: только обучающие"))
        assertTrue(p.contains("1. A — ch"))
        assertTrue(p.contains("2. B — ch2"))
        assertTrue(p.contains("\"relevant\""))
        assertEquals(setOf(2), TopicFilter.parseIndexes("```json\n{\"relevant\":[2]}\n```"))
    }

    @Test
    fun specAndStateRoundTrip() {
        val s = HarvestSpec("ai", 30, 1, 2, "1y|x", 10, 20, 30, 40, true, "фильтр", "https://youtu.be/a", "T")
        assertEquals(s, HarvestSpec.from(org.json.JSONObject(s.toJson().toString())))
        val st = HarvestState(s)
        st.items["a"] = HarvestItem("a", title = "A", order = 2, views = 5)
        st.items["b"] = HarvestItem("b", title = "B", order = 1, views = 9)
        st.status = "done"
        st.requests = 7
        val back = HarvestState.parse(st.toJson().toString())
        assertNotNull(back)
        assertEquals(2, back!!.items.size)
        assertEquals("done", back.status)
        assertEquals(7, back.requests)
        assertEquals(listOf("b", "a"), HarvestRules.sorted(back.items.values, "").map { it.id })
        assertEquals(listOf("b", "a"), HarvestRules.sorted(back.items.values, "views").map { it.id })
        assertNull(HarvestState.parse("not json"))
        // a broken item is skipped, the rest survives
        val raw = st.toJson().also { it.getJSONArray("items").put(org.json.JSONObject().put("title", "no id")) }.toString()
        assertEquals(2, HarvestState.parse(raw)!!.items.size)
    }

    @Test
    fun listTextAndConditions() {
        val st = HarvestState(HarvestSpec("related", seedTitle = "Seed"))
        st.items["a"] = HarvestItem("a", url = "https://www.youtube.com/watch?v=a", title = "A", channel = "C", order = 1)
        val txt = HarvestRules.listText(st)
        assertTrue(txt.contains("Seed"))
        assertTrue(txt.contains("1. A — C\nhttps://www.youtube.com/watch?v=a"))
        val c = HarvestRules.conditions(HarvestSpec("params", target = 20, periodLabel = "1y|01.01.2025 – сегодня", minViews = 10_000, noShorts = true))
        assertTrue(c.any { it.contains("01.01.2025") && !it.contains("1y|") })
        assertTrue(c.any { it.contains("10K") })
    }
}

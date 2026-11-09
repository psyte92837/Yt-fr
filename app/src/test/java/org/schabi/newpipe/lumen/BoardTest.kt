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

class BoardTest {
    // 2026-11-09 is a Monday
    private val today = Board.epochDay(2026, 11, 9)

    @Test
    fun calendarRoundTrip() {
        assertEquals(Triple(2026, 11, 9), Board.civil(today))
        assertEquals(0, Board.weekday(today))
        assertEquals(Triple(2024, 2, 29), Board.civil(Board.epochDay(2024, 2, 29)))
        assertEquals(0, Board.epochDay(1970, 1, 1))
        assertEquals(today + 3, Board.parseDate("12.11", today))
        assertEquals(Board.epochDay(2027, 1, 5), Board.parseDate("5.1", today))
        assertEquals(Board.epochDay(2026, 12, 1), Board.parseDate("2026-12-01", today))
        assertNull(Board.parseDate("31.02", today))
    }

    @Test
    fun quickAddUnderstandsTokens() {
        val q = Board.quick("ролик: Снять интро !high #Монтаж #shorts @пт ~3", today)
        assertEquals("Снять интро", q.title)
        assertEquals(BType.VIDEO, q.type)
        assertEquals(BPrio.HIGH, q.prio)
        assertEquals(listOf("монтаж", "shorts"), q.labels)
        assertEquals(today + 4, q.due)
        assertEquals(3, q.points)
        assertEquals(today + 1, Board.quick("x @завтра", today).due)
        assertEquals(today + 7, Board.quick("x @пн", today).due)
        assertEquals(today + 14, Board.quick("x @+2w", today).due)
        // unknown "@word" and a colon inside the title stay as text
        assertEquals("письмо @маме: важно", Board.quick("письмо @маме: важно", today).title)
    }

    @Test
    fun statusChangeIsLoggedAndOrdered() {
        var b = BoardData()
        b = Board.add(b, BQuick("a"), 1).first
        b = Board.add(b, BQuick("b"), 2).first
        // new items go on top of the column
        assertEquals(listOf("b", "a"), Board.column(b, BStatus.TODO).map { it.title })
        b = Board.move(b, 1, BStatus.DONE, 10)
        val a = b.item(1)!!
        assertEquals(BStatus.DONE, a.status)
        assertEquals(10L, a.doneAt)
        assertTrue(a.notes.any { it.auto && it.text == "status:todo>done" })
        b = Board.move(b, 1, BStatus.TODO, 11)
        assertEquals(0L, b.item(1)!!.doneAt)
        // back to the column's bottom; then moved up by hand
        assertEquals(listOf("b", "a"), Board.column(b, BStatus.TODO).map { it.title })
        b = Board.shift(b, 1, -1)
        assertEquals(listOf("a", "b"), Board.column(b, BStatus.TODO).map { it.title })
        assertEquals(BStatus.DOING, Board.step(BStatus.TODO, 1))
        assertEquals(BStatus.BACKLOG, Board.step(BStatus.BACKLOG, -1))
    }

    @Test
    fun filtersAndDueGroups() {
        var b = BoardData()
        b = Board.add(b, Board.quick("Монтаж выпуска #edit !1 @+0", today), 1).first
        b = Board.add(b, Board.quick("Баг: падает экспорт @-", today), 2).first
        b = Board.add(b, Board.quick("Старое @1.11", today), 3).first
        assertEquals(1, Board.filter(b, BFilter(label = "edit")).size)
        assertEquals(1, Board.filter(b, BFilter(query = "LUM-2")).size)
        assertEquals(1, Board.filter(b, BFilter(query = "экспорт")).size)
        assertEquals(BType.BUG, b.item(2)!!.type)
        val g = Board.byDue(b.items, today)
        assertEquals(listOf(BDue.OVERDUE, BDue.TODAY, BDue.NONE), g.keys.toList())
        val s = Board.stats(b.items, today)
        assertEquals(1, s.overdue)
        assertEquals(3, s.byStatus[BStatus.TODO])
        assertEquals(BPrio.HIGHEST, Board.ranked(b.items).first().prio)
    }

    @Test
    fun plansMoveUnfinishedWork() {
        var b = BoardData()
        val (b1, p1) = Board.addPlan(b, "Неделя 1", "3 ролика", today, 7)
        b = Board.startPlan(b1, p1.id, today)
        assertEquals(p1.id, b.activePlan?.id)
        b = Board.add(b, BQuick("a", points = 2), 1, plan = p1.id).first
        b = Board.add(b, BQuick("b", points = 3), 1, plan = p1.id).first
        b = Board.move(b, 1, BStatus.DONE, 1)
        assertEquals(1, Board.filter(b, BFilter(planOnly = true, hideDone = true)).size)
        val burn = Board.burndown(b, b.activePlan!!, today, 0)
        assertEquals(1, burn.size)
        assertEquals(5f, burn[0].first, 0.01f)
        b = Board.closePlan(b, p1.id, 0, 5)
        assertEquals("closed", b.plan(p1.id)!!.state)
        assertEquals(p1.id, b.item(1)!!.plan)
        assertEquals(0, b.item(2)!!.plan)
        assertNull(b.activePlan)
    }

    @Test
    fun jsonRoundTripAndAiPlan() {
        var b = BoardData(prefix = "VID")
        b = Board.add(b, Board.quick("Сценарий #text @завтра ~2", today), 7, checks = listOf(BCheck("хук", true), BCheck("финал"))).first
        b = Board.comment(b, 1, "готово наполовину", 8)
        b = Board.addPlan(b, "S1", "", today, 14).first
        val back = Board.fromJson(org.json.JSONObject(Board.toJson(b).toString()))
        assertEquals(b, back)
        assertEquals("VID-1", Board.key(back, back.items[0]))
        val ai = Board.parseAi("""```json
            {"tasks":[{"title":"Найти музыку","prio":"high","type":"task","points":1,"due_days":2,"checks":["бесплатная","120 bpm"]},{"title":""},"Смонтировать"]}
            ```""", today)
        assertEquals(2, ai.size)
        assertEquals(BPrio.HIGH, ai[0].first.prio)
        assertEquals(today + 2, ai[0].first.due)
        assertEquals(2, ai[0].second.size)
        assertEquals("Смонтировать", ai[1].first.title)
        assertTrue(Board.parseAi("нет json", today).isEmpty())
        assertFalse(Board.cleanPrefix("v!d").isEmpty())
    }
}

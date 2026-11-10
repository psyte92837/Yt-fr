/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Stateful property test of the task board (Studio «📋 Tasks»): random sequences of the actions the screen offers,
 * with the invariants the columns, the burndown and board.json rely on checked after every step.
 */
class BoardPropertyTest {

    private val today = Board.epochDay(2026, 11, 9)
    private val lines = listOf("Write script !high #video @fri ~3", "Fix crash #bug", "Idea: shorts series", "Edit !low ~1",
        "Thumbnail @tomorrow", "", "   ", "!!! ### @@@ ~~~", "Очень длинная задача ".repeat(20))

    private fun step(b: BoardData, r: Random, now: Long, log: MutableList<String>): BoardData {
        val item = b.items.randomOrNull(r)
        val plan = b.plans.randomOrNull(r)
        val op = r.nextInt(11)
        log += "$op/i${item?.id}/p${plan?.id}"
        return when (op) {
            0, 1 -> Board.add(b, Board.quick(lines.random(r), today), now, BStatus.entries.random(r), plan = plan?.id ?: 0).first
            2 -> item?.let { Board.move(b, it.id, BStatus.entries.random(r), now) } ?: b
            3 -> item?.let { Board.shift(b, it.id, if (r.nextBoolean()) 1 else -1) } ?: b
            4 -> item?.let { Board.place(b, it.status, it.id, r.nextInt(-1, 5)) } ?: b
            5 -> item?.let { Board.remove(b, it.id) } ?: b
            6 -> item?.let { Board.duplicate(b, it.id, now, " (copy)")?.first } ?: b
            7 -> item?.let { Board.comment(b, it.id, "note $op", now) } ?: b
            8 -> Board.addPlan(b, "Sprint ${b.nextPlan}", "", today + r.nextInt(-7, 7), 7).first
            9 -> plan?.let { Board.startPlan(b, it.id, today) } ?: b
            else -> plan?.let { Board.closePlan(b, it.id, b.plans.randomOrNull(r)?.id ?: 0, now) } ?: b
        }
    }

    private fun check(b: BoardData, log: List<String>, seed: Int) {
        fun fail(m: String): Nothing = throw AssertionError("seed $seed after $log: $m")
        val ids = b.items.map { it.id }
        if (ids.size != ids.toSet().size) fail("duplicate task ids $ids")
        if (ids.any { it >= b.nextId }) fail("nextId ${b.nextId} not above $ids")
        val pids = b.plans.map { it.id }
        if (pids.size != pids.toSet().size) fail("duplicate plan ids $pids")
        if (pids.any { it >= b.nextPlan }) fail("nextPlan ${b.nextPlan} not above $pids")
        // every task shows up in exactly one column
        val shown = BStatus.entries.flatMap { s -> Board.column(b, s).map { it.id } }
        if (shown.sorted() != ids.sorted()) fail("columns show $shown, board has $ids")
        val back = Board.fromJson(JSONObject(Board.toJson(b).toString()))
        if (back != b) fail("JSON round trip changed the board:\n$b\n$back")
    }

    @Test
    fun randomActionSequencesKeepTheBoardValid() {
        for (seed in 1..300) {
            val r = Random(seed)
            val log = ArrayList<String>()
            var b = BoardData()
            var now = 1_762_650_000_000L
            repeat(30) {
                now += r.nextLong(1, 3_600_000)
                b = step(b, r, now, log)
                check(b, log, seed)
            }
        }
    }

    @Test
    fun quickEntryNeverLosesTheTitle() {
        for (s in lines.filter { it.isNotBlank() && it.any(Char::isLetter) }) {
            val q = Board.quick(s, today)
            assertEquals("title of \"$s\"", true, q.title.isNotBlank())
        }
    }
}

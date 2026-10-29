/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiStatsTest {
    private fun r(at: Long, pid: String, task: String, status: String = "ok", i: Int = 10, o: Int = 5, model: String = "m") =
        AiStats.Rec(at, pid, task, "gemini", model, status, i, o, 100)

    private val recs = listOf(r(1000, "a", "seo", i = 100, o = 50), r(2000, "a", "seo", "error", 0, 0), r(3000, "b", "dossier", "truncated", 30, 20), r(4000, "", "x"))

    @Test
    fun groupsByProjectSortedByTokens() {
        val rows = AiStats.aggregate(recs, AiStats.Group.PROJECT, name = { it.uppercase() })
        assertEquals(listOf("A", "B", "—"), rows.map { it.key })
        assertEquals(2, rows[0].calls)
        assertEquals(1, rows[0].errors)
        assertEquals(150L, rows[0].tokens)
        assertEquals(1, rows[1].truncated)
    }

    @Test
    fun periodFilterAndTotals() {
        val rows = AiStats.aggregate(recs, AiStats.Group.TASK, sinceMs = 2500)
        assertEquals(setOf("dossier", "x"), rows.map { it.key }.toSet())
        val t = AiStats.total(rows)
        assertEquals(2, t.calls)
        assertEquals(65L, t.tokens)
    }

    @Test
    fun jsonRoundTripAndBadLine() {
        val x = r(5, "p", "t", "ok", 7, 8, "gpt")
        assertEquals(x, AiStats.parse(AiStats.toJson(x).toString()))
        assertNull(AiStats.parse("garbage"))
    }

    @Test
    fun formatsTokens() {
        assertEquals("999", AiStats.fmtTokens(999))
        assertEquals("12.0k", AiStats.fmtTokens(12000))
        assertEquals("1.50M", AiStats.fmtTokens(1_500_000))
    }
}

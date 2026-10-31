/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingTest {
    private val prompt = "Оставь только видео по теме.\nВерни JSON.\n\n1. Кошки — Канал А\n2. Котята — Канал Б\n3. Машины — Канал В\n\n\nСпасибо"

    @Test
    fun splitsIntoBlocksAndLines() {
        val b = Outgoing.blocks(prompt)
        assertEquals(3, b.size)
        assertEquals(2, b[0].lines.size)
        assertEquals("2. Котята — Канал Б", b[1].lines[1])
        assertEquals("Спасибо", b[2].text)
    }

    @Test
    fun emptyAndCrlf() {
        assertEquals(0, Outgoing.blocks("\n\n  \n").size)
        assertEquals(2, Outgoing.blocks("a\r\n\r\nb").size)
    }

    @Test
    fun nothingOffKeepsText() {
        val b = Outgoing.blocks(prompt)
        assertEquals(b.joinToString("\n\n") { it.text }, Outgoing.assemble(b, Outgoing.Selection()))
    }

    @Test
    fun dropsBlocksAndLines() {
        val b = Outgoing.blocks(prompt)
        val sel = Outgoing.Selection(mutableSetOf(2), mutableSetOf("1.2"))
        assertEquals("Оставь только видео по теме.\nВерни JSON.\n\n1. Кошки — Канал А\n2. Котята — Канал Б", Outgoing.assemble(b, sel))
    }

    @Test
    fun blockWithAllLinesOffDisappears() {
        val b = Outgoing.blocks(prompt)
        val sel = Outgoing.Selection(offLines = mutableSetOf("1.0", "1.1", "1.2"))
        assertEquals("Оставь только видео по теме.\nВерни JSON.\n\nСпасибо", Outgoing.assemble(b, sel))
        val s = Outgoing.stats(b, sel)
        assertEquals(2, s.blocks)
        assertEquals(3, s.blocksTotal)
        assertEquals(3, s.lines)
        assertEquals(6, s.linesTotal)
        assertTrue(s.chars < s.charsTotal)
    }

    @Test
    fun carriesChoiceToRetry() {
        val b = Outgoing.blocks(prompt)
        val sel = Outgoing.Selection(mutableSetOf(2), mutableSetOf("1.2"))
        // retry: same blocks plus a note in front
        val next = Outgoing.blocks("Ответь компактнее.\n\n" + prompt)
        val c = Outgoing.carry(b, sel, next)
        assertEquals(setOf(3), c.offBlocks)
        assertEquals(setOf("2.2"), c.offLines)
        assertTrue(Outgoing.assemble(next, c).startsWith("Ответь компактнее."))
    }

    @Test
    fun changedBlockStartsOn() {
        val b = Outgoing.blocks("A\n\nx\ny")
        val c = Outgoing.carry(b, Outgoing.Selection(mutableSetOf(1)), Outgoing.blocks("A\n\nx\nz"))
        assertTrue(c.isEmpty)
    }

    @Test
    fun rules() {
        assertEquals(Outgoing.Rule.ASK, Outgoing.rule(null))
        assertEquals(Outgoing.Rule.SEND, Outgoing.rule("send"))
        assertEquals(Outgoing.Rule.BLOCK, Outgoing.rule(Outgoing.ruleName(Outgoing.Rule.BLOCK)))
        assertEquals(Outgoing.Rule.ASK, Outgoing.rule("???"))
    }

    @Test
    fun decide() {
        val ask = Outgoing.Rule.ASK
        assertEquals(ask, Outgoing.decide(true, ask, null, "seo", false))
        assertEquals(Outgoing.Rule.SEND, Outgoing.decide(false, ask, null, "seo", false))
        assertEquals(Outgoing.Rule.SEND, Outgoing.decide(true, ask, null, "ping", false))
        assertEquals(Outgoing.Rule.SEND, Outgoing.decide(true, ask, null, "seo", true))
        assertEquals(Outgoing.Rule.SEND, Outgoing.decide(true, Outgoing.Rule.BLOCK, Outgoing.Rule.SEND, "seo", false))
        // a block holds even with review off
        assertEquals(Outgoing.Rule.BLOCK, Outgoing.decide(false, Outgoing.Rule.BLOCK, null, "seo", false))
        assertEquals(Outgoing.Rule.BLOCK, Outgoing.decide(true, ask, Outgoing.Rule.BLOCK, "seo", true))
    }
}

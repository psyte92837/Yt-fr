/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class StudioAlignTest {
    @Test
    fun phraseEndsStickToPausesSoWordsDoNotDrift() {
        val text = """Представьте: в 2025 году рынок вырос на 37%. Это много, но не рекорд. Почему так вышло?
Во-первых, люди стали покупать больше онлайн, а магазины закрывались. Во-вторых, курс рубля менялся каждую неделю!
Эксперты, впрочем, спорят. Одни говорят о пузыре; другие — о новой норме. Кто прав, покажет 2026 год.
Итак, что делать вам? Следите за ценами, сравнивайте предложения и не спешите. Подписывайтесь, чтобы не пропустить продолжение."""
        val words = Studio.tokens(text)
        val rnd = java.util.Random(7)
        var tot1 = 0.0; var tot2 = 0.0; var max1 = 0L; var max2 = 0L
        repeat(20) { trial ->
            // simulate: per-sentence pace varies, digits read long, pauses at sentence ends 350-700, commas 0 or 200
            val truth = mutableListOf<Long>()
            val spans = mutableListOf<Span>()
            var t = 300L; var segStart = t
            var pace = 60.0 * (0.75 + rnd.nextDouble() * 0.5)
            for ((i, w) in words.withIndex()) {
                truth += t
                val letters = w.count { it.isLetter() } + w.count { it.isDigit() } * 5 + w.count { it == '%' } * 9
                t += ((letters + 1) * pace).toLong()
                val st = Studio.breakStrength(w)
                val pause = when { st >= 3 -> 350L + rnd.nextInt(350); st >= 1 && rnd.nextBoolean() -> 220L; else -> 0L }
                // a spurious breath in the middle of a long sentence
                val spur = if (st == 0 && rnd.nextInt(25) == 0) 260L else 0L
                val p = pause + spur
                if (p > 0 && i < words.lastIndex) { spans += Span(segStart, t); t += p; segStart = t }
                if (st >= 3) pace = 60.0 * (0.75 + rnd.nextDouble() * 0.5)
            }
            spans += Span(segStart, t)
            val a = Studio.alignWords(words, spans); val b = Studio.alignAnchored(words, spans)
            val e1 = words.indices.map { abs(a[it].startMs - truth[it]) }; val e2 = words.indices.map { abs(b[it].startMs - truth[it]) }
            tot1 += e1.average(); tot2 += e2.average(); max1 = max(max1, e1.max()); max2 = max(max2, e2.max())
        }
        // spreading by letters over the whole voice drifts by a second on average; pinned to pauses it does not
        assertTrue("old ${tot1 / 20}", tot1 / 20 > 500)
        assertTrue("new ${tot2 / 20} max $max2", tot2 / 20 < 150 && max2 < 1200)
    }

    @Test
    fun weightsAndBreaks() {
        assertEquals(3, Studio.breakStrength("рекорд."))
        assertEquals(3, Studio.breakStrength("так?»"))
        assertEquals(1, Studio.breakStrength("много,"))
        assertEquals(0, Studio.breakStrength("рынок"))
        assertTrue(Studio.spokenWeight("2025") > Studio.spokenWeight("году"))
        val ws = listOf("Один", "два.", "Три", "четыре.")
        val a = Studio.alignAnchored(ws, listOf(Span(0, 1000), Span(1500, 2500)))
        assertEquals(1500L, a[2].startMs)
        assertTrue(a.zipWithNext().all { (x, y) -> x.startMs <= y.startMs })
        assertTrue(Studio.alignAnchored(emptyList(), listOf(Span(0, 10))).isEmpty())
    }
}

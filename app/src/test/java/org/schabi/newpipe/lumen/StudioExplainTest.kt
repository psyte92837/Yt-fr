/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioExplainTest {
    private fun times(vararg ms: Long): List<SceneTime> = ms.toList().zipWithNext().mapIndexed { i, (a, b) -> SceneTime(i, a, b, i..i) }
    private fun words(text: String) = text.split(' ').filter { it.isNotBlank() }.mapIndexed { i, w -> SWord(w, i * 400L, i * 400L + 300) }

    @Test
    fun questionsBecomeChapters() {
        val scenes = listOf(
            SScene("Хотите купить авто дешевле рынка?"), SScene("Это скрытый рынок."),
            SScene("Откуда берутся такие авто?"), SScene("Это бывший корпоративный транспорт."),
            SScene("Как работают перекупы?"), SScene("Стоит ли рисковать?"), SScene("Начните с малого.")
        )
        val t = times(0, 7000, 20_000, 23_000, 48_000, 51_000, 55_000, 70_000)
        // the hook is not a chapter, the question 7 s after a chapter is too close
        assertEquals(listOf(2, 4), StudioExplain.chapters(scenes, t))
        // one question alone makes no chapters
        assertEquals(emptyList<Int>(), StudioExplain.chapters(scenes.take(4), t.take(4)))
        val ch = listOf(2, 4)
        assertEquals(-1, StudioExplain.chapterAt(ch, t, 10_000))
        assertEquals(0, StudioExplain.chapterAt(ch, t, 20_000))
        assertEquals(1, StudioExplain.chapterAt(ch, t, 60_000))
    }

    @Test
    fun chapterTitle() {
        val lines = StudioExplain.title("Откуда берутся такие автомобили и почему они продаются дешевле?")
        assertTrue(lines.toString(), lines.size in 1..3)
        assertEquals("ОТКУДА БЕРУТСЯ", lines[0])
        assertTrue(lines.last().endsWith("?"))
        assertFalse(lines.last().startsWith("И?") || lines.last().endsWith(" И?"))
        assertTrue(lines.all { it == it.uppercase() })
        assertEquals(listOf("С ЧЕГО НАЧАТЬ?"), StudioExplain.title("С чего начать?"))
        assertTrue(StudioExplain.title("").isEmpty())
        assertTrue(StudioExplain.label("Как устроен бизнес перекупов?").contains("УСТРОЕН"))
    }

    @Test
    fun bigWordsOfAPlainScene() {
        val w = words("Это бывший корпоративный транспорт компаний")
        assertEquals(listOf("БЫВШИЙ", "КОРПОРАТИВНЫЙ") to 1, StudioExplain.headline(w, w.indices))
        val n = words("До 40% лотов уходят сразу")
        val (lines, key) = StudioExplain.headline(n, n.indices)
        assertEquals("40%", lines[key])
        assertEquals(emptyList<String>() to -1, StudioExplain.headline(w, IntRange.EMPTY))
    }

    @Test
    fun soundsOnCutsAndChapters() {
        val t = times(0, 1000, 5000, 9000)
        val fx = StudioExplain.sfx(t, listOf(2))
        assertEquals(listOf("whoosh", "whoosh", "boom"), fx.map { it.second })
        assertTrue(fx.none { it.first < 900 })
        // an own whoosh next to the style's one wins, the boom stays
        val merged = StudioExplain.merge(listOf(990L to "whoosh"), fx)
        assertEquals(1, merged.count { it.first in 900L..1100L })
        assertTrue(merged.any { it.second == "boom" })
        assertEquals(merged.sortedBy { it.first }, merged)
    }
}

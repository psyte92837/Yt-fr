/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Weights, new-video badges, learning path, knowledge gaps and the radial layout of the knowledge map. */
class KnowledgeMapExtrasTest {
    private val t0 = 1_700_000_000_000L

    private fun v(id: String, ch: String, views: Long, added: Long = t0, dur: Long = 600) =
        Video(id, "https://www.youtube.com/watch?v=$id", title = "Title $id", channel = ch, views = views,
            uploadMs = t0, addedAt = added, duration = dur, description = "About $id")

    private val videos = listOf(v("a", "Alpha", 1000), v("b", "Alpha", 500, added = t0 + 10), v("c", "Beta", 9000, added = t0 + 20))
    private val project = Project(id = "p", name = "Spine", phrase = "spine")

    private fun input(since: Long = 0, ai: List<AiResult> = emptyList()) = KInput(project, videos, since = since, ai = ai)

    @Test
    fun countsAndFreshVideos() {
        val tree = KMap.build(input(since = t0 + 5))
        assertEquals(3, tree.count)
        assertEquals(2, tree.fresh)
        val alpha = KMap.find(tree, "map/channels/c:Alpha")!!
        assertEquals(2, alpha.count)
        assertEquals(1, alpha.fresh)
        assertEquals(1, alpha.children.count { it.fresh == 1 })
    }

    @Test
    fun noBadgesOnFirstVisit() {
        val tree = KMap.build(input())
        assertEquals(0, tree.fresh)
        assertEquals(0, KMap.find(tree, "map/channels")!!.fresh)
    }

    @Test
    fun parsePathKeepsOrderAndDropsUnknown() {
        val text = "Вот путь:\n1. [c] Основы — обзор\n2) [zzz] нет такого\n3. [a]: практика\n4. [c] повтор\n**5.** [b] x"
        val steps = KMap.parsePath(text, videos)
        assertEquals(listOf("c", "a"), steps.map { it.first.id })
        assertEquals("Основы — обзор", steps[0].second)
        assertEquals("практика", steps[1].second)
    }

    @Test
    fun parseGapsSplitsTitleAndReason() {
        val text = "## Пробелы\n- **Лечение у детей** — спрашивают в комментариях\n• Цены: никто не называет\n1. Только заголовок\n-\nпросто текст"
        val gaps = KMap.parseGaps(text)
        assertEquals(listOf("Лечение у детей", "Цены", "Только заголовок"), gaps.map { it.first })
        assertEquals("спрашивают в комментариях", gaps[0].second)
        assertEquals("", gaps[2].second)
    }

    @Test
    fun aiAnswersBecomeBranches() {
        val ai = listOf(
            AiResult("g", KMap.GAPS, "map", "m", "- Дети — нет видео\n- Цены — нет данных", 1, 1),
            AiResult("p", KMap.PATH, "map", "m", "1. [b] начать\n2. [c] дальше", 1, 1)
        )
        val tree = KMap.build(input(ai = ai))
        val path = KMap.find(tree, "map/path")!!
        assertEquals(listOf("1. Title b", "2. Title c"), path.children.map { it.title })
        assertEquals("начать", path.children[0].sub)
        assertEquals(2, KMap.find(tree, "map/gaps")!!.children.size)
        // map-wide answers are not repeated under "AI findings"
        assertNull(KMap.find(tree, "map/ai"))
    }

    @Test
    fun unparsableAnswerAddsNoBranch() {
        val tree = KMap.build(input(ai = listOf(AiResult("p", KMap.PATH, "map", "m", "no steps here", 1, 1))))
        assertNull(KMap.find(tree, "map/path"))
    }

    @Test
    fun pathDataIsMostViewedFirstAndBounded() {
        val data = KMap.pathData(videos, mapOf("c" to "Summary c"), 10_000)
        assertTrue(data.startsWith("[c] Title c — Beta, 10 min, 9000 views: Summary c"))
        assertEquals(3, data.lines().count { it.isNotBlank() })
        val short = KMap.pathData(videos, emptyMap(), 60)
        assertEquals(1, short.lines().count { it.isNotBlank() })
    }

    @Test
    fun radialLayoutRingsAndSectors() {
        val tree = KMap.build(input())
        val closed = KRadial.layout(tree, emptySet())
        assertEquals(1 + tree.children.size, closed.size)
        assertEquals(0.0, hypot(closed[0].x, closed[0].y), 1e-9)
        closed.drop(1).forEach { assertEquals(1.0, hypot(it.x, it.y), 1e-9) }

        val opened = KRadial.layout(tree, setOf("map/channels"))
        val chIdx = opened.indexOfFirst { it.node.id == "map/channels" }
        val kids = opened.filter { it.parent == chIdx }
        assertEquals(2, kids.size)
        kids.forEach {
            assertEquals(2.0, hypot(it.x, it.y), 1e-9)
            assertEquals(opened[chIdx].branch, it.branch)
        }
        // a child lies close to its parent's direction
        val pa = atan2(opened[chIdx].y, opened[chIdx].x)
        kids.forEach {
            var da = kotlin.math.abs(atan2(it.y, it.x) - pa)
            if (da > PI) da = 2 * PI - da
            assertTrue(da < PI / 2)
        }
    }

    @Test
    fun radialLayoutLimitsChildren() {
        val many = (1..40).map { v("v$it", "Ch$it", it.toLong()) }
        val tree = KMap.build(KInput(project, many))
        val lay = KRadial.layout(tree, setOf("map/channels"), maxKids = 10)
        val chIdx = lay.indexOfFirst { it.node.id == "map/channels" }
        assertEquals(10, lay.count { it.parent == chIdx })
        assertNotNull(lay.firstOrNull { it.depth == 2 })
    }
}

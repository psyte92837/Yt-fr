/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class StudioSchemeTest {
    private val words = "Сначала идея потом прототип и наконец продажи растут".split(' ').mapIndexed { i, s -> SWord(s, i * 500L, i * 500L + 400) }

    private fun chain() = StudioScheme.fromJson(
        """{"layout":"chain","title":"Путь","nodes":[{"id":"a","icon":"💡","label":"Идея","at":1},
           {"id":"b","icon":"🛠","label":"Прототип"},{"id":"c","icon":"💰","label":"Продажи","value":"×3","accent":true}]}"""
    )!!

    @Test
    fun parsesAndRoundTrips() {
        val s = chain()
        assertEquals("chain", s.layout)
        assertEquals(3, s.nodes.size)
        assertEquals(1, s.nodes[0].at)
        assertEquals(s, StudioScheme.fromJson(StudioScheme.toJson(s).toString()))
    }

    @Test
    fun answerWithScenes() {
        val list = StudioScheme.parseAnswer("""```json
            {"schemes":[{"scene":2,"layout":"hub","nodes":[]},{"scene":0,"layout":"x"}]}```""")
        assertNotNull(list)
        assertEquals(listOf(2), list!!.map { it.first })
    }

    @Test
    fun problemsAreFound() {
        assertTrue(StudioScheme.problems(chain(), 0..8).isEmpty())
        assertTrue(StudioScheme.problems(chain().copy(layout = "spiral")).any { "layout" in it })
        assertTrue(StudioScheme.problems(chain().copy(layout = "matrix")).size >= 2) // 3 nodes and no axes
        val loop = SScheme("tree", nodes = listOf(SchNode("a", label = "A", parent = "b"), SchNode("b", label = "B", parent = "a"), SchNode("c", label = "C", parent = "a")))
        assertFalse(StudioScheme.problems(loop).isEmpty())
        assertTrue(StudioScheme.problems(null).isNotEmpty())
        // every at outside the scene
        val far = chain().copy(nodes = chain().nodes.map { it.copy(at = 50) })
        assertTrue(StudioScheme.problems(far, 0..8).any { "at" in it })
    }

    @Test
    fun repairFixesSmallThings() {
        val s = chain().copy(
            nodes = chain().nodes.map { it.copy(accent = true, at = 40) },
            links = listOf(SchLink("a", "zz"), SchLink("a", "c"))
        )
        val r = StudioScheme.repair(s, 0..8)
        assertEquals(1, r.nodes.count { it.accent })
        assertTrue(r.nodes.all { it.at == -1 })
        assertEquals(listOf(SchLink("a", "c")), r.links)
    }

    @Test
    fun naturalLinks() {
        assertEquals(listOf("a" to "b", "b" to "c"), StudioScheme.links(chain()).map { it.from to it.to })
        val hub = chain().copy(layout = "hub")
        assertEquals(listOf("a" to "b", "a" to "c"), StudioScheme.links(hub).map { it.from to it.to })
        val tree = SScheme("tree", nodes = listOf(SchNode("r", label = "R"), SchNode("x", label = "X", parent = "r"), SchNode("y", label = "Y", parent = "x")))
        assertEquals(mapOf("r" to 0, "x" to 1, "y" to 2), StudioScheme.depths(tree))
        assertEquals(2, StudioScheme.links(tree).size)
    }

    @Test
    fun layoutsStayInsideTheBox() {
        val n5 = (1..5).map { SchNode("n$it", "⭐", "Узел $it") }
        val n4 = n5.take(4)
        val cases = StudioScheme.LAYOUTS.map { l ->
            when (l) {
                "matrix" -> SScheme(l, nodes = n4, axes = listOf("X", "Y"))
                "venn", "stat" -> SScheme(l, nodes = n5.take(3))
                "card" -> SScheme(l, nodes = n5.take(1))
                else -> SScheme(l, nodes = n5)
            }
        }
        for (s in cases) for (ratio in listOf(0.7f, 1.8f)) {
            val boxes = StudioScheme.layout(s, ratio)
            assertEquals(s.layout, s.nodes.size, boxes.size)
            for (b in boxes) {
                assertTrue("${s.layout} x", b.x in 0f..1f && b.y in 0f..1f)
                assertTrue("${s.layout} size", b.w > 0f && b.h > 0f)
            }
        }
        // funnel narrows, pyramid widens
        val f = StudioScheme.layout(SScheme("funnel", nodes = n5), 1f)
        assertTrue(f.first().w > f.last().w)
        val p = StudioScheme.layout(SScheme("pyramid", nodes = n5), 1f)
        assertTrue(p.first().w < p.last().w)
    }

    @Test
    fun revealFollowsWords() {
        val t = StudioScheme.reveal(chain(), words, 0..8, 0L, 4500L)
        assertEquals(500L, t[0]) // at = 1
        assertEquals(1500L, t[1]) // "прототип" is word 3
        assertEquals(3000L, t[2]) // "продажи" is word 6
        // chain keeps the order even when a later node is spoken earlier
        val swapped = chain().copy(nodes = listOf(chain().nodes[2], chain().nodes[1]))
        val ts = StudioScheme.reveal(swapped, words, 0..8, 0L, 4500L)
        assertTrue(ts[1] >= ts[0])
        assertEquals(6, StudioScheme.matchWord("Рост продажи", words, 0..8))
    }

    @Test
    fun promptBlocks() {
        val sc = listOf(SScene("Сначала идея потом прототип", "text"), SScene("и наконец продажи растут", "icons"))
        val block = StudioScheme.scenesBlock(sc, words, listOf(0..4, 5..8))
        assertTrue(block.startsWith("Сцена 1 [0..4] (text): [0]Сначала"))
        assertTrue("Сцена 2 [5..8] (icons): [5]наконец" in block)
        assertEquals(1, StudioScheme.maxSchemes(2))
        assertEquals(4, StudioScheme.maxSchemes(12))
    }

    @Test
    fun sceneKeepsItsScheme() {
        val sc = SScene("Сначала идея", "scheme", scheme = chain())
        val back = Studio.parseScript(Studio.toJson(SScript("t", listOf(sc))))!!.scenes[0]
        assertEquals("scheme", back.kind)
        assertEquals(chain(), back.scheme)
        // a broken scheme falls back to text
        val bad = JSONObject().put("say", "x").put("kind", "scheme").put("scheme", JSONObject().put("layout", "zzz"))
        assertEquals("text", Studio.sceneOf(bad)!!.kind)
    }

    @Test
    fun dropIndexShiftsWords() {
        val f = StudioScheme.dropIndex(setOf(1, 4))
        assertEquals(0, f(0))
        assertEquals(null, f(1))
        assertEquals(1, f(2))
        assertEquals(2, f(3))
        assertEquals(3, f(5))
        assertEquals(7, StudioScheme.dropIndex(emptySet())(7))
    }

    @Test
    fun schemesSurviveANewDirection() {
        val s = chain().copy(nodes = chain().nodes.mapIndexed { i, n -> n.copy(at = 4 + i) })
        // the scheme sat on words 4..7; the director split the speech differently and cut word 1
        val scenes = listOf(SScene("a", "icons"), SScene("b", "text"), SScene("c", "flow"))
        val ranges = listOf(0..1, 2..3, 4..6)
        val out = StudioScheme.carry(listOf(s to 4..7), scenes, ranges, StudioScheme.dropIndex(setOf(1)))
        // words 4..7 are now 3..6: most of them are in the third scene
        assertEquals(listOf("icons", "text", "scheme"), out.map { it.kind })
        assertEquals(listOf(-1, 4, 5), out[2].scheme!!.nodes.map { it.at }) // word 3 left the scene
        // nothing to carry or no overlap: scenes stay as they are
        assertEquals(scenes, StudioScheme.carry(emptyList(), scenes, ranges) { it })
        assertEquals(scenes, StudioScheme.carry(listOf(s to 20..22), scenes, ranges) { it })
    }
}

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

class DiagramTest {
    private fun flow(): Diagram {
        val d = Diagram("Заявка", "flow")
        val s = d.add("Начало", DKind.START)
        val a = d.add("Проверить данные", parent = s.id)
        val q = d.add("Данные верны?", DKind.DECISION, a.id)
        val ok = d.add("Принять", parent = q.id, label = "Да")
        val bad = d.add("Вернуть", parent = q.id, label = "Нет")
        d.connect(bad.id, a.id) // loop back
        val e = d.add("Конец", DKind.END, ok.id)
        d.connect(bad.id, e.id)
        return d
    }

    private val size = { n: DNode -> (if (n.kind == DKind.DECISION) 120f else 100f) to 40f }

    @Test
    fun parsesAiReplyAndDropsBrokenParts() {
        val reply = "Вот схема:\n```json\n{\"title\":\"Вход\",\"nodes\":[{\"id\":\"1\",\"text\":\"Старт\",\"kind\":\"start\"}," +
            "{\"id\":\"2\",\"text\":\"Шаг\"},{\"id\":\"2\",\"text\":\"дубль\"},{\"id\":\"3\",\"text\":\"\"},{\"id\":\"4\",\"text\":\"Условие?\",\"kind\":\"decision\"}]," +
            "\"edges\":[{\"from\":\"1\",\"to\":\"2\"},{\"from\":\"1\",\"to\":\"2\"},{\"from\":\"2\",\"to\":\"9\"},{\"from\":\"2\",\"to\":\"2\"},{\"from\":\"2\",\"to\":\"4\",\"label\":\"ок\"}]}\n```"
        val d = Diagram.fromAi(reply, "flow", "x")!!
        assertEquals("Вход", d.title)
        assertEquals(listOf("1", "2", "4"), d.nodes.map { it.id })
        assertEquals(DKind.START, d.node("1")!!.kind)
        assertEquals(DKind.DECISION, d.node("4")!!.kind)
        assertEquals(2, d.edges.size)
        assertEquals("ок", d.edges[1].label)
    }

    @Test
    fun rejectsReplyWithoutDiagram() {
        assertNull(Diagram.fromAi("не могу", "flow", "x"))
        assertNull(Diagram.fromAi("{\"nodes\":[{\"id\":\"1\",\"text\":\"один\"}]}", "flow", "x"))
    }

    @Test
    fun jsonRoundTrip() {
        val d = flow()
        val back = Diagram.fromJson(d.toJson())!!
        assertEquals(d.nodes, back.nodes)
        assertEquals(d.edges, back.edges)
        assertEquals(d.id, back.id)
    }

    @Test
    fun layersFollowTheFlowAndLoopsAreMarkedBack() {
        val d = flow()
        val lay = DiagramLayout.layout(d, size)
        val ids = d.nodes.map { it.id }
        assertEquals(0, lay.depth[ids[0]])
        assertTrue(lay.depth[ids[1]]!! > lay.depth[ids[0]]!!)
        assertTrue(lay.depth[ids[2]]!! > lay.depth[ids[1]]!!)
        // "Вернуть" -> "Проверить данные" goes against the flow
        assertTrue((ids[4] to ids[1]) in lay.back)
        assertFalse((ids[0] to ids[1]) in lay.back)
        assertTrue(DiagramBuild.noOverlap(lay))
        assertTrue(lay.width > 0 && lay.height > 0)
    }

    @Test
    fun noOverlapInWideFlowAndMindMap() {
        val wide = Diagram("w", "flow")
        val root = wide.add("root")
        repeat(7) { i -> wide.add("child $i", parent = root.id) }
        assertTrue(DiagramBuild.noOverlap(DiagramLayout.layout(wide, size)))

        val mind = DiagramBuild.topicMap("Тема", listOf("A" to listOf("a1", "a2"), "B" to listOf("b1"), "C" to emptyList()))
        val lay = DiagramLayout.layout(mind, size)
        assertTrue(DiagramBuild.noOverlap(lay))
        val rootBox = lay.boxes[mind.nodes[0].id]!!
        assertTrue(lay.boxes.values.filter { it !== rootBox }.all { it.x > rootBox.x })
    }

    @Test
    fun cyclesWithoutStartStillLayOut() {
        val d = Diagram("c", "flow")
        val a = d.add("a")
        val b = d.add("b", parent = a.id)
        d.connect(b.id, a.id)
        val lay = DiagramLayout.layout(d, size)
        assertEquals(2, lay.boxes.size)
        assertEquals(1, lay.back.size)
    }

    @Test
    fun removeDropsLinks() {
        val d = flow()
        val before = d.edges.size
        d.remove(d.nodes[2].id)
        assertTrue(d.edges.size < before)
        assertTrue(d.edges.all { d.node(it.from) != null && d.node(it.to) != null })
    }

    @Test
    fun mermaidAndOutline() {
        val m = DiagramText.mermaid(flow())
        assertTrue(m.startsWith("flowchart TD"))
        assertTrue(m.contains("N1([\"Начало\"])"))
        assertTrue(m.contains("N3{\"Данные верны?\"}"))
        assertTrue(m.contains("N3 -->|\"Да\"| N4"))
        val mm = DiagramText.mermaid(DiagramBuild.topicMap("Тема", listOf("Канал" to listOf("Видео"))))
        assertTrue(mm.startsWith("mindmap"))
        assertTrue(mm.contains("root((Тема))"))
        assertNotNull(DiagramText.outline(flow()).lines().firstOrNull { it.contains("Принять") })
    }

    @Test
    fun topChannelsAndLabels() {
        val vs = listOf(
            Video("a", "u", title = "A1", channel = "X", views = 100, labels = mutableListOf("t")),
            Video("b", "u", title = "B1", channel = "X", views = 300, labels = mutableListOf("t")),
            Video("c", "u", title = "C1", channel = "Y", views = 50),
            Video("d", "u", title = "D1", channel = "", views = 9999)
        )
        val ch = DiagramBuild.topChannels(vs, 5, 1)
        assertEquals(listOf("X", "Y"), ch.map { it.first })
        assertEquals(listOf("B1"), ch[0].second)
        assertEquals(listOf("t"), DiagramBuild.topLabels(vs).map { it.first })
    }
}

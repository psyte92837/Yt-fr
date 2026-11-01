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

class StudioEditTest {

    private fun b(id: String, start: Long, dur: Long, kind: String = "text") = EBlock(id, kind, start, dur, text = "x")

    @Test
    fun newBlockStartsAtThePlayheadAndFitsTheVideo() {
        assertEquals(2_000L to 4_000L, StudioEdit.placeAt(2_000, 10_000))
        // 2.5 s left: shortened to the end
        assertEquals(7_500L to 2_500L, StudioEdit.placeAt(7_500, 10_000))
        // less than a second left: starts earlier instead
        assertEquals(6_000L to 4_000L, StudioEdit.placeAt(9_500, 10_000))
        // a video shorter than the default length
        assertEquals(0L to 2_000L, StudioEdit.placeAt(0, 2_000))
    }

    @Test
    fun timeEditsStayInsideTheVideo() {
        val x = b("b1", 1_000, 2_000)
        assertEquals(8_000L, StudioEdit.move(x, 9_000, 10_000).startMs)
        assertEquals(0L, StudioEdit.move(x, -500, 10_000).startMs)
        val ts = StudioEdit.trimStart(x, 500)
        assertEquals(500L, ts.startMs)
        assertEquals(3_000L, ts.endMs)
        // the start never passes the end minus the minimum length
        assertEquals(3_000L - StudioEdit.MIN_MS, StudioEdit.trimStart(x, 5_000).startMs)
        assertEquals(StudioEdit.MIN_MS, StudioEdit.trimEnd(x, 0, 10_000).durMs)
        assertEquals(9_000L, StudioEdit.trimEnd(x, 20_000, 10_000).durMs)
        assertEquals(9_000L, StudioEdit.setDuration(x, 60_000, 10_000).durMs)
        assertEquals(StudioEdit.MIN_MS, StudioEdit.clampTime(x.copy(durMs = 10), 10_000).durMs)
    }

    @Test
    fun startAndEndHere() {
        val x = b("b1", 2_000, 2_000)
        assertEquals(1_000L to 4_000L, StudioEdit.startAt(x, 1_000, 10_000).let { it.startMs to it.endMs })
        // past the end: the block moves to start there
        assertEquals(5_000L to 7_000L, StudioEdit.startAt(x, 5_000, 10_000).let { it.startMs to it.endMs })
        assertEquals(2_000L to 6_000L, StudioEdit.endAt(x, 6_000, 10_000).let { it.startMs to it.endMs })
        // before the start: the block moves to end there
        assertEquals(0L to 2_000L, StudioEdit.endAt(x, 1_500, 10_000).let { it.startMs to it.endMs })
    }

    @Test
    fun splitMakesTwoSeamlessHalves() {
        val list = listOf(b("b1", 1_000, 4_000).copy(animIn = "pop", animOut = "fade"), b("b2", 0, 1_000))
        val out = StudioEdit.split(list, "b1", 3_000)!!
        assertEquals(3, out.size)
        assertEquals(listOf("b1", "b3", "b2"), out.map { it.id })
        assertEquals(1_000L to 3_000L, out[0].startMs to out[0].endMs)
        assertEquals(3_000L to 5_000L, out[1].startMs to out[1].endMs)
        assertEquals("none", out[0].animOut)
        assertEquals("none", out[1].animIn)
        assertEquals("pop", out[0].animIn)
        assertEquals("fade", out[1].animOut)
        assertNull(StudioEdit.split(list, "b1", 1_100))
        assertNull(StudioEdit.split(list, "b1", 6_000))
        assertNull(StudioEdit.split(list, "nope", 3_000))
    }

    @Test
    fun duplicateGoesRightAfterOrShiftsWhenNoRoom() {
        val list = listOf(b("b1", 1_000, 2_000))
        val (out, id) = StudioEdit.duplicate(list, "b1", 10_000)!!
        assertEquals("b2", id)
        assertEquals(3_000L, out[1].startMs)
        val (late, _) = StudioEdit.duplicate(listOf(b("b1", 7_000, 2_000)), "b1", 10_000)!!
        assertEquals(7_000L, late[1].startMs)
        assertTrue(late[1].x > late[0].x)
    }

    @Test
    fun orderAndActiveBlocks() {
        val list = listOf(b("b1", 0, 2_000), b("b2", 1_000, 2_000), b("b3", 5_000, 1_000))
        assertEquals(listOf("b2", "b3", "b1"), StudioEdit.reorder(list, "b1", 5).map { it.id })
        assertEquals(listOf("b2", "b1", "b3"), StudioEdit.reorder(list, "b2", -1).map { it.id })
        assertEquals(listOf("b1", "b2"), StudioEdit.active(list, 1_500).map { it.id })
        // the end is not included
        assertEquals(listOf("b2"), StudioEdit.active(list, 2_000).map { it.id })
        assertTrue(StudioEdit.active(list, 4_000).isEmpty())
        assertEquals("b4", StudioEdit.nextId(list))
    }

    @Test
    fun edgesSnapToTargets() {
        val list = listOf(b("b1", 0, 2_000), b("b2", 5_000, 1_000))
        val targets = StudioEdit.snapTargets(list, "b2", 3_300, 10_000)
        assertTrue(2_000L in targets && 3_300L in targets && 10_000L in targets)
        assertFalse(5_000L in targets)
        // the start sticks to the end of b1
        assertEquals(2_000L to 2_000L, StudioEdit.snapMove(2_080, 1_000, targets, 100))
        // the end sticks to the playhead
        assertEquals(2_300L to 3_300L, StudioEdit.snapMove(2_250, 1_000, targets, 100))
        assertEquals(2_600L to null, StudioEdit.snapMove(2_600, 1_000, targets, 100))
    }

    @Test
    fun overlappingBlocksGoToDifferentLanes() {
        val list = listOf(b("a", 0, 3_000), b("b", 1_000, 3_000), b("c", 3_000, 1_000), b("d", 2_000, 500))
        val lanes = StudioEdit.lanes(list)
        assertEquals(0, lanes["a"])
        assertEquals(1, lanes["b"])
        assertEquals(2, lanes["d"])
        // c starts when a ends: back to the first lane
        assertEquals(0, lanes["c"])
    }

    @Test
    fun rulerStepKeepsLabelsApart() {
        assertEquals(1_000L, StudioEdit.tickStep(0.1f, 60f))
        assertEquals(500L, StudioEdit.tickStep(0.2f, 60f))
        assertEquals(300_000L, StudioEdit.tickStep(0.00001f, 60f))
    }

    @Test
    fun hitTestFollowsRotation() {
        // a 400×100 block in the middle of a 1000×1000 frame
        val x = EBlock("b1", "text", 0, 1_000, x = 0.5f, y = 0.5f, w = 0.4f, ratio = 0.25f)
        assertTrue(StudioEdit.hit(x, 1000f, 1000f, 680f, 520f))
        assertFalse(StudioEdit.hit(x, 1000f, 1000f, 500f, 620f))
        val r = x.copy(rot = 90f)
        assertFalse(StudioEdit.hit(r, 1000f, 1000f, 680f, 520f))
        assertTrue(StudioEdit.hit(r, 1000f, 1000f, 520f, 680f))
        val (cx, cy) = StudioEdit.corner(x, 1000f, 1000f, 2)
        assertEquals(700f, cx, 0.01f)
        assertEquals(550f, cy, 0.01f)
        val (rx, ry) = StudioEdit.corner(r, 1000f, 1000f, 2)
        assertEquals(450f, rx, 0.01f)
        assertEquals(700f, ry, 0.01f)
        val top = StudioEdit.top(listOf(x, x.copy(id = "b2")), 1000f, 1000f, 500f, 500f)
        assertEquals("b2", top?.id)
    }

    @Test
    fun transformSnapsAndClamps() {
        assertEquals(0f, StudioEdit.snapDeg(3f), 0.001f)
        assertEquals(90f, StudioEdit.snapDeg(-273f), 0.001f)
        assertEquals(30f, StudioEdit.snapDeg(30f), 0.001f)
        assertEquals(180f, StudioEdit.normDeg(-180f), 0.001f)
        assertEquals(-170f, StudioEdit.normDeg(190f), 0.001f)
        assertEquals(0.5f to true, StudioEdit.snapMiddle(0.51f))
        assertEquals(0.6f to false, StudioEdit.snapMiddle(0.6f))
        val x = b("b1", 0, 1_000)
        assertEquals(StudioEdit.MAX_W, StudioEdit.resize(x, 9f).w, 0.001f)
        assertEquals(1f, StudioEdit.moveTo(x, 1.4f, -0.2f).x, 0.001f)
        assertEquals(0f, StudioEdit.moveTo(x, 1.4f, -0.2f).y, 0.001f)
    }

    @Test
    fun animationsStartAndEndInvisibleOrScaled() {
        val x = EBlock("b1", "text", 1_000, 3_000, animIn = "fade", animOut = "fade")
        assertEquals(0f, StudioEdit.pose(x, 1_000).alpha, 0.001f)
        assertEquals(1f, StudioEdit.pose(x, 2_500).alpha, 0.001f)
        assertTrue(StudioEdit.pose(x, 3_950).alpha < 0.5f)
        val pop = x.copy(animIn = "pop", animOut = "none")
        assertEquals(0.6f, StudioEdit.pose(pop, 1_000).scale, 0.01f)
        assertEquals(1f, StudioEdit.pose(pop, 1_400).scale, 0.001f)
        assertEquals(1f, StudioEdit.pose(pop, 3_990).alpha, 0.001f)
        val slide = x.copy(animIn = "slide")
        assertTrue(StudioEdit.pose(slide, 1_050).dy > 0f)
        val none = x.copy(animIn = "none", animOut = "none")
        assertEquals(EPose(1f, 1f, 0f, 0f), StudioEdit.pose(none, 1_000))
        // a short block animates for a third of its length at most
        assertEquals(200L, StudioEdit.animMs(x.copy(durMs = 600)))
    }

    @Test
    fun wordsOfTheMoment() {
        val words = listOf(SWord("Сначала", 0, 400), SWord("идёт", 500, 800), SWord("разведка", 1_000, 1_500),
            SWord("рынка,", 1_600, 2_000), SWord("потом", 6_000, 6_300), SWord("продукт", 6_400, 7_000))
        assertEquals(2..3, StudioEdit.wordRange(words, 900, 3_000))
        assertTrue(StudioEdit.wordRange(words, 3_000, 5_000).isEmpty())
        assertEquals("разведка рынка, потом продукт", StudioEdit.momentText(words, 1_000, 100, 6_000))
        val (numbered, range) = StudioEdit.momentNumbered(words, 1_000, 0, 1_000)
        assertEquals("[2]разведка [3]рынка,", numbered)
        assertEquals(2..3, range)
        assertEquals("разведка Сначала", StudioEdit.keyWords(words, 500).split(' ').sortedBy { it.length }.reversed().joinToString(" "))
        // a scheme block reveals its nodes on their words when they are inside it
        val s = SScheme("chain", "", listOf(SchNode("a", label = "Разведка", at = 2), SchNode("b", label = "Продукт", at = 5)))
        val times = StudioEdit.schemeTimes(s, words, EBlock("b1", "scheme", 800, 7_000, scheme = s))
        assertEquals(listOf(1_000L, 6_400L), times)
    }

    @Test
    fun schemeFromLinesWithoutAi() {
        val s = StudioEdit.schemeFromLines("Путь клиента:\n🔍 Поиск — 100%\n👀 Просмотр — 40%\n🛒 Покупка — 5%")
        assertNotNull(s)
        s!!
        assertEquals("chain", s.layout)
        assertEquals("Путь клиента", s.title)
        assertEquals(3, s.nodes.size)
        assertEquals("🔍", s.nodes[0].icon)
        assertEquals("Поиск", s.nodes[0].label)
        assertEquals("100%", s.nodes[0].value)
        val f = StudioEdit.schemeFromLines("Поиск: 100\nПросмотр: 40\nПокупка: 5", "funnel")!!
        assertEquals("funnel", f.layout)
        assertEquals("40", f.nodes[1].value)
        val tree = StudioEdit.schemeFromLines("Компания\nОтдел А\nОтдел Б", "tree")!!
        assertEquals("n1", tree.nodes[2].parent)
        assertNull(StudioEdit.schemeFromLines("одна строка"))
        // matrix needs exactly four nodes and axes: three lines do not fit
        assertNull(StudioEdit.schemeFromLines("a\nb\nc", "matrix"))
        assertEquals("hub", StudioEdit.schemeFromLines((1..6).joinToString("\n") { "Пункт $it" })!!.layout)
        // back to lines and again: the same scheme; the words of the nodes and the axes stay
        assertEquals("Путь клиента:\n🔍 Поиск — 100%\n👀 Просмотр — 40%\n🛒 Покупка — 5%", StudioEdit.schemeLines(s))
        val m = SScheme("matrix", "", (1..4).map { SchNode("n$it", label = "Q$it", at = it) }, axes = listOf("цена", "качество"))
        val again = StudioEdit.schemeFromLines(StudioEdit.schemeLines(m).replace("Q2", "Дорого"), "matrix", m)!!
        assertEquals(listOf("цена", "качество"), again.axes)
        assertEquals("Дорого", again.nodes[1].label)
        assertEquals(2, again.nodes[1].at)
    }

    @Test
    fun sizesAndLabels() {
        assertTrue(StudioEdit.textRatio("Коротко") < StudioEdit.textRatio("Очень длинная подпись на экране в три строки точно"))
        assertEquals(StudioEdit.textRatio("a\nb\nc\nd\ne\nf\ng"), StudioEdit.textRatio("a\nb\nc\nd\ne"), 0.001f)
        // a scheme box like in a Studio video: 1080×1920 → 92 % × 44 %
        assertEquals((0.44f * 1920) / (0.92f * 1080), StudioEdit.schemeRatio(1080, 1920), 0.001f)
        assertEquals(1080 to 1920, StudioEdit.outSize(1080, 1920))
        assertEquals(1080 to 1920, StudioEdit.outSize(2160, 3840))
        assertEquals(1920 to 1080, StudioEdit.outSize(1921, 1081))
        assertEquals(718 to 404, StudioEdit.outSize(719, 405))
        assertEquals("1:05.3", StudioEdit.clock(65_300))
        assertEquals("0:00.0", StudioEdit.clock(-5))
        assertEquals("2.4", StudioEdit.secs(2_400))
        assertEquals(2_500L, StudioEdit.parseSeconds("2,5"))
        assertEquals(3_500L, StudioEdit.parseSeconds("0:03.5"))
        assertEquals(4_000L, StudioEdit.parseSeconds("4 с"))
        assertEquals(StudioEdit.MIN_MS, StudioEdit.parseSeconds("0.01"))
        assertEquals(0L, StudioEdit.parseTime("0"))
        assertEquals(72_500L, StudioEdit.parseTime("1:12,5"))
        assertNull(StudioEdit.parseTime("x:1"))
        // a tall picture on a wide frame is narrowed to 80 % of the height
        assertEquals(0.8f * 1080 / (1920 * 1.5f), StudioEdit.fitWidth(0.5f, 1.5f, 1920, 1080), 0.001f)
        assertEquals(0.5f, StudioEdit.fitWidth(0.5f, 0.5f, 1920, 1080), 0.001f)
        assertNull(StudioEdit.parseSeconds("abc"))
        assertNull(StudioEdit.parseSeconds(""))
    }

    @Test
    fun parsesAiAnswers() {
        val one = StudioEdit.parseScheme("```json\n{\"layout\":\"hub\",\"title\":\"Т\",\"nodes\":[{\"id\":\"a\",\"label\":\"А\"},{\"id\":\"b\",\"label\":\"Б\"},{\"id\":\"c\",\"label\":\"В\"}]}\n```")
        assertEquals("hub", one?.layout)
        val wrapped = StudioEdit.parseScheme("Вот: {\"schemes\":[{\"scene\":1,\"layout\":\"chain\",\"nodes\":[{\"id\":\"a\",\"label\":\"А\"},{\"id\":\"b\",\"label\":\"Б\"}]}]} готово")
        assertEquals("chain", wrapped?.layout)
        assertNull(StudioEdit.parseScheme("нет json"))
        assertEquals(listOf("🚀 Быстрый старт", "Старт", "Вперёд"), StudioEdit.parseField("{\"text\":\"🚀 Быстрый старт\",\"variants\":[\"Старт\",\"Вперёд\",\"Старт\"]}", "text"))
        assertEquals(listOf("cat on sofa"), StudioEdit.parseField("{\"query\":\"cat on sofa\"}", "query", "alt"))
        assertEquals(listOf("Просто текст"), StudioEdit.parseField("Просто текст", "text"))
    }

    @Test
    fun projectRoundTrip() {
        val s = SScheme("chain", "Т", listOf(SchNode("a", "🔍", "Поиск"), SchNode("b", "🛒", "Покупка")))
        val p = EProject("e1", "/x/video.mp4", 1080, 1920, 30_000, listOf(
            EBlock("b1", "scheme", 1_000, 4_000, x = 0.4f, y = 0.6f, w = 0.9f, ratio = 0.8f, rot = 12f, opacity = 0.9f,
                animIn = "slide", animOut = "zoom", look = "glass", scheme = s, ask = "воронка"),
            EBlock("b2", "image", 0, 2_000, file = "img/p1.jpg", look = "round"),
            EBlock("b3", "text", 5_000, 1_000, text = "Привет", look = "plate")
        ), created = 7, exported = "/out.mp4")
        val back = StudioEdit.fromJson(StudioEdit.toJson(p).toString())
        assertEquals(p, back)
        assertNull(StudioEdit.fromJson("{\"protocol\":\"other\"}"))
        assertNull(StudioEdit.fromJson("not json"))
        // unknown kinds and schemes without nodes are dropped, bad values fall back
        val a = org.json.JSONArray("[{\"id\":\"x\",\"kind\":\"video\"},{\"id\":\"y\",\"kind\":\"scheme\"},{\"id\":\"z\",\"kind\":\"emoji\",\"text\":\"🔥\",\"in\":\"spin\",\"w\":50}]")
        val blocks = StudioEdit.blocksFrom(a)
        assertEquals(listOf("z"), blocks.map { it.id })
        assertEquals("pop", blocks[0].animIn)
        assertEquals(StudioEdit.MAX_W, blocks[0].w, 0.001f)
    }

    @Test
    fun undoAndRedo() {
        val h = EditHistory(cap = 3)
        assertFalse(h.canUndo)
        h.push("s0")
        h.push("s0")
        h.push("s1")
        assertEquals("s1", h.undo("s2"))
        assertTrue(h.canRedo)
        assertEquals("s2", h.redo("s1"))
        assertEquals("s1", h.undo("s2"))
        // a new change clears redo
        h.push("s1b")
        assertFalse(h.canRedo)
        h.push("a")
        h.push("b")
        h.push("c")
        // only the last three are kept
        assertEquals("c", h.undo("now"))
        assertEquals("b", h.undo("c"))
        assertEquals("a", h.undo("b"))
        assertNull(h.undo("a"))
    }
}

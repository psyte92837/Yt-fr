/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditInsertV2Test {
    private val words = listOf(
        SWord("Android", 12_300, 12_700), SWord("занимает", 12_750, 13_200), SWord("72", 13_300, 13_600), SWord("%", 13_600, 13_800),
        SWord("рынка,", 13_850, 14_200), SWord("а", 14_800, 14_900), SWord("iOS", 14_950, 15_300), SWord("27", 15_400, 15_700), SWord("%.", 15_700, 16_000)
    )

    // ---------- themes ----------

    @Test
    fun themesRoundTripAndReadable() {
        assertEquals(12, EditThemes.PRESETS.size)
        for (t in EditThemes.PRESETS) {
            val back = EditThemes.parse(EditThemes.json(t).toString())!!
            assertEquals(t.copy(builtIn = false), back.copy(builtIn = false))
            assertTrue(t.id, SchemeStyles.contrast(t.ink, t.surface) >= 4.5)
            assertTrue(t.id, SchemeStyles.contrast(t.accent, t.surface) >= 3.0)
        }
        // an AI answer in a fence with a bad contrast is fixed
        val bad = EditThemes.parse("```json\n{\"name\":\"Серая\",\"color\":{\"surface\":\"#808080\",\"ink\":\"#909090\",\"accent\":\"#888888\"},\"shape\":{\"radius\":\"l\"}}\n```")!!
        assertTrue(SchemeStyles.contrast(bad.ink, bad.surface) >= 4.5)
        assertEquals("l", bad.radius)
        assertNull(EditThemes.parse("не тема"))
        assertEquals(0xFFFFAA00.toInt(), EditThemes.color("#FA0", 0))
    }

    @Test
    fun themeOnBlocks() {
        val neon = EditThemes.preset("neon")!!
        val st = EditThemes.toScheme(neon)
        assertEquals("solid", st.fill)
        assertEquals("condensed", st.font)
        assertTrue(st.upper)
        val blocks = listOf(
            EBlock("b1", "scheme", 0, 3000, scheme = SScheme("chain", nodes = listOf(SchNode("a", label = "x"), SchNode("b", label = "y")))),
            EBlock("b2", "text", 0, 3000, text = "T"),
            EBlock("b3", "anim", 0, 3000, file = "anim/x"),
            EBlock("b4", "image", 0, 3000, file = "img/1.png")
        )
        val out = EditThemes.apply(blocks, neon)
        assertEquals(neon.accent, out[0].over?.accent)
        assertEquals("", out[0].look)
        assertEquals(neon.accent, out[1].over?.accent)
        assertEquals("neon", EditInsert.briefFrom(out[2].brief)?.theme)
        assertEquals("neon", EditThemes.parse(out[2].theme)?.id)
        assertEquals(blocks[3], out[3])
        val tokens = EditThemes.tokens(neon)
        assertEquals("#00E5FF", tokens.getString("accent"))
        assertEquals("monospace", EditThemes.cssFont("mono"))
    }

    @Test
    fun themeFromTheColoursOfTheVideo() {
        val px = IntArray(400) { i -> if (i < 300) 0xFF102030.toInt() else if (i < 360) 0xFFE04040.toInt() else 0xFFF0F0F0.toInt() }
        val pal = EditThemes.palette(px, 4)
        assertTrue(pal.size in 2..4)
        assertEquals(0xFF102030.toInt(), pal.first())
        val th = EditThemes.fromPalette(pal)
        assertTrue(SchemeStyles.contrast(th.ink, th.surface) >= 4.5)
        assertTrue(SchemeStyles.contrast(th.accent, th.surface) >= 3.0)
        assertTrue(((th.accent shr 16) and 0xFF) > ((th.accent shr 8) and 0xFF))
        assertFalse(th.builtIn)
    }

    @Test
    fun styleUnity() {
        val b = { id: String, font: String, acc: Int -> EBlock(id, "text", 0, 1000, text = "x", over = BlockLook(font = font, accent = acc)) }
        val ok = listOf(b("1", "sans", 1), b("2", "sans", 1), b("3", "serif", 2))
        assertTrue(EditThemes.issues(ok).isEmpty())
        val bad = listOf(b("1", "sans", 1), b("2", "serif", 2), b("3", "mono", 3))
        assertEquals(2, EditThemes.issues(bad).size)
    }

    // ---------- native motion ----------

    @Test
    fun motionPresetsDrawAtAnyLength() {
        val th = EditThemes.preset("science")!!
        val c = EditMotion.Content(listOf(BriefRow("Android", "72", "%", main = true), BriefRow("iOS", "27", "%")), "Кто победил", emptyMap(),
            listOf("Android" to 0.0, "72" to 1.0, "iOS" to 2.4))
        for (p in EditMotion.PRESETS) for (dur in listOf(1.5, 5.0, 20.0)) {
            val mid = EditMotion.frame(p.id, c, th, dur * 0.7, dur, 972f, 690f, 40f)
            assertTrue(p.id + " " + dur, mid.isNotEmpty())
            assertTrue(p.id, mid.all { it.a in 0f..1f })
            assertTrue(p.id, mid.filterIsInstance<MPrim.Label>().all { it.size > 0f })
        }
        assertTrue(EditMotion.frame("counter", c, th, 5.0, 5.0, 972f, 690f).isEmpty())
        // the counter counts up to its number
        val end = EditMotion.frame("counter", c, th, 3.5, 5.0, 972f, 690f).filterIsInstance<MPrim.Label>().first()
        assertEquals("72%", end.text)
        assertEquals("counter", EditMotion.forType("number"))
        assertNull(EditMotion.forType("timeline"))
    }

    @Test
    fun motionTimeAndNumbers() {
        assertEquals(listOf(1.0, 2.4), EditMotion.revealTimes(listOf("Android", "iOS"), listOf("Android" to 1.0, "и" to 2.0, "iOS" to 2.4), 6.0))
        val fallback = EditMotion.revealTimes(listOf("Альфа", "Бета"), emptyList(), 6.0)
        assertTrue(fallback[0] < fallback[1])
        assertEquals("12 345,6", EditMotion.fmt(12345.6, EditMotion.parseNum("2,5")!!))
        assertEquals("1 200", EditMotion.fmt(1200.0, EditMotion.parseNum("1 200")!!))
        assertNull(EditMotion.parseNum("абв"))
        assertEquals(0f, EditMotion.envelope(5.0, 5.0, "normal"))
        assertEquals(1f, EditMotion.envelope(2.0, 5.0, "normal"))
    }

    // ---------- lumen.anim v1.1 ----------

    private val V11 = """
// lumen.anim v1.1 #K7Q2-2
// name: Столбцы с параметрами
// type: chart
// size: square
// dur: 3..10
// fps: 60
// segments: intro=0.8 loop=2 outro=0,6
// param title text "Кто победил" — Заголовок
// param speed range 0.5..2 =1.5 — Скорость
// param accent color =theme.accent
// param icons bool =false
// param style choice bars|line =line
// param bad weird 1
function frame(t, ctx, W, H, d) { ctx.fillStyle = T('accent'); ctx.fillRect(0, 0, W * param('speed'), H) }
""".trim()

    @Test
    fun headerOfV11() {
        val h = AnimV11.header(V11)
        assertEquals("v1.1", h.version)
        assertEquals("Столбцы с параметрами", h.name)
        assertEquals("chart", h.type)
        assertEquals("square", h.size)
        assertEquals(3.0, h.durMin, 1e-9)
        assertEquals(60, h.fps)
        assertEquals(800L, h.intro)
        assertEquals(2000L, h.loop)
        assertEquals(600L, h.outro)
        assertEquals(listOf("title", "speed", "accent", "icons", "style"), h.params.map { it.name })
        assertEquals("Кто победил", h.params[0].def)
        assertEquals("Заголовок", h.params[0].label)
        assertEquals("1.5", h.params[1].def)
        assertEquals(listOf("bars", "line"), h.params[4].options)
        val p = AnimV11.params(h, mapOf("speed" to "9", "icons" to "true"), JSONObject().put("accent", "#00FF00"))
        assertEquals(2.0, p.getDouble("speed"), 1e-9)
        assertTrue(p.getBoolean("icons"))
        assertEquals("#00FF00", p.getString("accent"))
        assertEquals("line", p.getString("style"))
        assertEquals("800,2000,600", AnimV11.segString(h))
        val a = EditInsert.detect(V11) as InsertAnswer.Anim
        assertEquals("K7Q2", a.req)
        assertEquals("Столбцы с параметрами", a.name)
    }

    @Test
    fun segmentsKeepTheEndAtTheEnd() {
        // intro 800, loop 2000, outro 600 on a 10 s block
        assertEquals(500L, AnimV11.segTime(500, 10_000, 800, 2000, 600))
        assertEquals(800L + 200, AnimV11.segTime(3000, 10_000, 800, 2000, 600))
        assertEquals(800L + 2000 + 100, AnimV11.segTime(9500, 10_000, 800, 2000, 600))
        // the same end on a 5 s block
        assertEquals(800L + 2000 + 100, AnimV11.segTime(4500, 5000, 800, 2000, 600))
        // shorter than intro + outro: both squeezed, no loop
        assertTrue(AnimV11.segTime(900, 1000, 800, 2000, 600) >= 2800)
        val b = EBlock("b", "anim", 0, 10_000, file = "anim/x/r_k", seg = "800,2000,600")
        val m = AnimMeta("k", 30, 10, 10, 102, 3400, 0)
        assertEquals(15, EditInsert.frameAt(500, b, m))
        assertEquals(EditInsert.frameAt(9500, b, m), EditInsert.frameAt(4500, b.copy(durMs = 5000), m))
        assertEquals(3400L, EditInsert.animLen(b))
        assertNull(AnimV11.seg("1,0,2"))
    }

    @Test
    fun cuesOfABlock() {
        val cues = AnimV11.cues(words, 12_300, 17_000, listOf("Android" to false, "iOS" to true), listOf(14_000L, 20_000L))
        assertEquals(9, cues.count { it.kind == "word" })
        assertEquals(0.0, cues.first { it.id == "item1" }.t, 1e-9)
        assertEquals(2.65, cues.first { it.id == "main" }.t, 1e-9)
        assertEquals(1, cues.count { it.kind == "cut" })
        assertEquals(1, cues.count { it.kind == "pause" })
        assertTrue(AnimV11.cuesText(cues).contains("main 2.65"))
        assertEquals(cues.size, AnimV11.cuesJson(cues).length())
    }

    @Test
    fun variantsAndChecks() {
        val text = "Вот три:\n```javascript\n// lumen.anim v1 #K7Q2-1\n// name: Первый\nfunction frame(t){}\n```\n" +
            "```javascript\n// lumen.anim v1 #K7Q2-2\n// name: Второй\nfunction frame(t){}\n```\n```javascript\n// lumen.anim v1 #K7Q2-3\n// name: Третий\nfunction frame(t){}\n```"
        val all = AnimV11.detectAll(text)
        assertEquals(listOf("Первый", "Второй", "Третий"), all.map { it.name })
        assertTrue(all.all { it.req == "K7Q2" })
        assertEquals(1, AnimV11.detectAll("```javascript\nfunction frame(t){}\n```").size)
        assertTrue(AnimV11.detectAll(null).isEmpty())
        assertEquals(3, AnimV11.hardColors("function frame(t,c){c.fillStyle='#ff0000';c.strokeStyle='#00ff00';c.fillStyle=\"#0000ff\"}"))
        assertEquals(0, AnimV11.hardColors("function frame(t,c){c.fillStyle=T('accent');c.strokeStyle='#00ff00'}"))
        assertEquals(0, AnimV11.hardColors("function frame(t,c){c.fillStyle='#ff0000';c.fillStyle='#00ff00';c.fillStyle='#0000ff';c.x=T('ink')}"))
        assertTrue(EditInsert.themeProblems("function frame(t,c){c.fillStyle='#ff0000';c.strokeStyle='#00ff00';c.fillStyle='#0000ff'}").isNotEmpty())
        // the right half opaque: the right zone is covered, the left is not
        val px = IntArray(100) { i -> if (i % 10 >= 5) 0xFF000000.toInt() else 0 }
        assertEquals(1f, AnimV11.coverage(px, 10, 10, FRect(0.5f, 0f, 1f, 1f), 1), 1e-6f)
        assertEquals(0f, AnimV11.coverage(px, 10, 10, FRect(0f, 0f, 0.5f, 1f), 1), 1e-6f)
        val pr = AnimV11.reportProblems("{\"minFont\":20,\"outside\":2,\"calls\":40}", 40)
        assertEquals(2, pr.size)
        assertTrue(AnimV11.reportProblems("{\"minFont\":38,\"outside\":0}", 40).isEmpty())
    }

    @Test
    fun requestV11() {
        val ctx = InsertCtx(1080, 1920, FRect(0.05f, 0.3f, 0.95f, 0.66f), "центр", 12_300, 5000, words, req = "K7Q2",
            theme = EditThemes.preset("neon"), cues = AnimV11.cues(words, 12_300, 17_300, listOf("Android" to true), emptyList()), platform = "tiktok")
        val r = EditInsert.request(ctx, EditBrief(type = "list"), "anim", variants = 3)
        assertTrue(r.contains("ТЕМА: Неон"))
        assertTrue(r.contains("СОБЫТИЯ"))
        assertTrue(r.contains("#K7Q2-3"))
        assertTrue(r.contains("Список с галочками"))
        val d = EditInsert.animData(ctx, EditBrief(type = "list"), AnimV11.header(V11))
        assertEquals("#00E5FF", d.getJSONObject("theme").getString("accent"))
        assertEquals(3.4, d.getDouble("dur"), 1e-9)
        assertEquals(1.5, d.getJSONObject("params").getDouble("speed"), 1e-9)
        assertTrue(d.getJSONArray("cues").length() > 0)
        // TikTok keeps more of the bottom free than Shorts
        assertEquals(0.78f, EditInsert.uiZones(1080, 1920, "tiktok")[0].r.t, 1e-6f)
        assertEquals(3, EditInsert.uiZones(1080, 1920, "reels").size)
        assertTrue(EditInsert.uiZones(1920, 1080, "youtube").isEmpty())
        val end = InsertCtx(1920, 1080, FRect(0.1f, 0.1f, 0.5f, 0.4f), "", 100_000, 5000, platform = "youtube", totalMs = 110_000)
        assertTrue(EditInsert.warnings(EditBrief(what = "x"), end).any { it.id == "endscreen" })
    }

    @Test
    fun framesHelpers() {
        assertEquals(1f, EditInsert.tailFade(1000, 5000, true))
        assertEquals(0.5f, EditInsert.tailFade(4900, 5000, true), 1e-6f)
        assertEquals(1f, EditInsert.tailFade(4900, 5000, false))
        val b = EBlock("b", "anim", 0, 4000, w = 0.5f, keys = listOf(EKey(0, 0.5f, 0.5f, 0.5f, 0f, 1f), EKey(2000, 0.5f, 0.5f, 0.8f, 0f, 1f)))
        assertEquals(1.6f, EditInsert.renderScale(b), 1e-6f)
        assertEquals(1f, EditInsert.renderScale(b.copy(keys = emptyList())))
        assertEquals(1080 to 960, EditInsert.pixels(FRect(0f, 0f, 0.5f, 0.25f), 1080, 1920, 2f))
    }

    // ---------- Lottie ----------

    @Test
    fun lottieInfoAndTime() {
        val json = "{\"v\":\"5.7\",\"fr\":30,\"ip\":0,\"op\":90,\"w\":512,\"h\":512,\"layers\":[{\"ty\":5,\"t\":{\"d\":{\"k\":[{\"s\":{\"t\":\"HELLO\"}}]}}}]," +
            "\"markers\":[{\"cm\":\"intro\",\"tm\":0,\"dr\":15},{\"cm\":\"loop\",\"tm\":15,\"dr\":45},{\"cm\":\"outro\",\"tm\":60,\"dr\":30}]}"
        val i = EditLottie.info(json)!!
        assertEquals(3000L, i.ms)
        assertEquals(listOf("HELLO"), i.texts)
        assertEquals(Triple(500L, 1500L, 1000L), EditLottie.segments(i))
        val noSeg = i.copy(markers = emptyMap())
        assertEquals(0.5f, EditLottie.progress(5000, 10_000, noSeg, "fit"), 1e-6f)
        assertEquals(0.5f, EditLottie.progress(4500, 10_000, noSeg, "loop"), 1e-6f)
        // with segments the end of the block plays the outro
        assertTrue(EditLottie.progress(9900, 10_000, i, "fit") > 0.95f)
        assertNull(EditLottie.info("{\"a\":1}"))
    }

    // ---------- suggestions, schemes from data, report, translation ----------

    @Test
    fun placesForInserts() {
        fun ws(s: String, at: Long) = s.split(' ').mapIndexed { i, w -> SWord(w, at + i * 300, at + i * 300 + 250) }
        val speech = ws("Android занимает 72 % рынка, а iOS 27 %.", 0) +
            ws("Во-первых проверьте настройки, во-вторых обновите приложение, затем перезапустите.", 6000) +
            ws("В 2019 году вышла первая версия, а в 2023 вторая.", 14_000) +
            ws("Подпишитесь на канал.", 22_000)
        val hints = EditSuggest.suggest(speech)
        assertEquals(listOf("compare", "steps", "timeline", "cta"), hints.map { it.type })
        assertEquals(2, hints[0].rows.size)
        assertTrue(hints[1].rows.size >= 2)
        assertTrue(EditSuggest.suggest(emptyList()).isEmpty())
    }

    @Test
    fun schemesWithoutAi() {
        val steps = EditSuggest.schemeFromRows("steps", listOf(BriefRow("Настройки"), BriefRow("Обновление"), BriefRow("Перезапуск")))!!
        assertEquals("chain", steps.layout)
        assertEquals(3, steps.nodes.size)
        val hub = EditSuggest.schemeFromRows("list", listOf(BriefRow("Свет"), BriefRow("Звук"), BriefRow("Кадр")), "Съёмка")!!
        assertEquals("hub", hub.layout)
        assertEquals("Съёмка", hub.nodes.first().label)
        val tl = EditSuggest.schemeFromRows("timeline", listOf(BriefRow("Первая", "2019"), BriefRow("Вторая", "2021"), BriefRow("Третья", "2023")))!!
        assertEquals("2019", tl.nodes[0].value)
        assertNull(EditSuggest.schemeFromRows("timeline", listOf(BriefRow("одна"))))
        assertNull(EditSuggest.schemeFromRows("number", listOf(BriefRow("x", "1"))))
        assertEquals("matrix", EditSuggest.schemeFromRows("proscons", (1..4).map { BriefRow("п$it") })!!.layout)
    }

    @Test
    fun reportOfTheVideo() {
        val blocks = listOf(
            EBlock("b1", "text", 1000, 800, x = 0.5f, y = 0.9f, w = 0.8f, ratio = 0.2f, text = "Очень длинная надпись из многих слов подряд здесь"),
            EBlock("b2", "scheme", 1200, 4000, x = 0.5f, y = 0.9f, w = 0.8f, ratio = 0.3f, scheme = SScheme("chain", nodes = listOf(SchNode("a", label = "x"), SchNode("b", label = "y"))))
        )
        val r = EditSuggest.report(blocks, 120_000, 1080, 1920)
        assertEquals(2, r.count)
        assertEquals(1.0, r.perMinute, 1e-9)
        assertTrue(r.coverage > 0.03 && r.coverage < 0.05)
        assertTrue(r.lines.any { it.id == "b1" && it.text.contains("чтение") })
        assertTrue(r.lines.any { it.text.contains("Shorts") })
        assertTrue(r.lines.any { it.text.contains("друг друга") })
        assertTrue(r.lines.any { !it.bad && it.text.contains("без вставок") })
    }

    @Test
    fun translationKeepsTheStructure() {
        val blocks = listOf(
            EBlock("b1", "scheme", 0, 3000, scheme = SScheme("chain", "Шаги", listOf(SchNode("a", label = "Начало", value = "1"), SchNode("b", label = "Конец")))),
            EBlock("b2", "text", 0, 3000, text = "Привет"),
            EBlock("b3", "motion", 0, 3000, look = "bars", brief = EditInsert.briefJson(EditBrief(type = "chart", title = "Итог", rows = listOf(BriefRow("Яблоки", "3")), params = mapOf("title" to "Заголовок", "speed" to "1"))).toString())
        )
        val tx = EditSuggest.texts(blocks)
        assertEquals(setOf("b1|title", "b1|n0|label", "b1|n1|label", "b2|text", "b3|btitle", "b3|r0", "b3|p|title"), tx.keys)
        val out = EditSuggest.applyTexts(blocks, tx.mapValues { it.value + "!" })
        assertEquals("Шаги!", out[0].scheme!!.title)
        assertEquals("1", out[0].scheme!!.nodes[0].value)
        assertEquals("Привет!", out[1].text)
        val br = EditInsert.briefFrom(out[2].brief)!!
        assertEquals("Яблоки!", br.rows[0].label)
        assertEquals("1", br.params["speed"])
        assertEquals("Заголовок!", br.params["title"])
    }

    @Test
    fun statsAndPacks() {
        val l = listOf(InsertStat("anim", "ai", "Gemini", true, 0), InsertStat("anim", "ai", "Gemini", false, 2), InsertStat("motion", "native"))
        val lines = EditSuggest.statsLines(l)
        assertTrue(lines[0].contains("без ИИ: 1"))
        assertTrue(lines[1].startsWith("Gemini: первый ответ прошёл проверки 50 %"))
        assertEquals(l[1], InsertStat.of(l[1].json()))
        val pack = EditSuggest.pack(listOf(SavedInsert("i1", "Счётчик", "anim", "function frame(){}", "", "ai", 1, 2)),
            listOf(EditThemes.preset("neon")!!.copy(id = "my", builtIn = false)), listOf("Мой" to EditBrief(type = "list", pace = "drive")))
        val back = EditSuggest.unpack(pack.toString())!!
        assertEquals("Счётчик", back.inserts.single().name)
        assertEquals("my", back.themes.single().id)
        assertEquals("drive", back.recipes.single().second.pace)
        assertNull(EditSuggest.unpack("{}"))
    }

    // ---------- tracking ----------

    @Test
    fun trackingFollowsAMovingSquare() {
        val w = 80
        val h = 60
        fun frame(x0: Int, y0: Int) = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in x0 until x0 + 12 && y in y0 until y0 + 10) 230 else ((x * 7 + y * 3) % 40) }
        val frames = (0 until 6).map { frame(10 + it * 4, 20 + it * 2) }
        val path = EditTracker.track(frames, w, h, FRect(10f / w, 20f / h, 22f / w, 30f / h))
        assertEquals(6, path.size)
        assertEquals((10 + 20).toFloat() / w, path[5].l, 1.5f / w)
        assertEquals((20 + 10).toFloat() / h, path[5].t, 1.5f / h)
        val b = EBlock("b", "text", 1000, 3000, x = 0.3f, y = 0.4f, w = 0.2f, text = "x")
        val keys = EditTracker.keys(b, path, (0 until 6).map { 1000L + it * 500 })
        assertEquals(6, keys.size)
        assertEquals(0L, keys[0].atMs)
        assertEquals(0.3f + 20f / w, keys[5].x, 2f / w)
        assertEquals(128, EditTracker.grey(0xFF808080.toInt()))
    }

    // ---------- new in / out animations and accents ----------

    @Test
    fun newPoses() {
        val wipe = EBlock("b", "image", 0, 3000, animIn = "wipe")
        assertTrue(StudioEdit.pose(wipe, 100).clip < 0.9f)
        assertEquals(1f, StudioEdit.pose(wipe, 1500).clip)
        val spin = EBlock("b", "image", 0, 3000, animIn = "spin")
        assertNotEquals(0f, StudioEdit.pose(spin, 50).rot)
        val type = EBlock("b", "text", 0, 4000, text = "Печатная машинка", animIn = "type")
        assertTrue(StudioEdit.pose(type, 300).reveal < 1f)
        assertEquals(1f, StudioEdit.pose(type, 2500).reveal)
        val pulse = EBlock("b", "emoji", 0, 4000, text = "🔥", emph = "pulse")
        assertNotEquals(StudioEdit.pose(pulse, 1000).scale, StudioEdit.pose(pulse, 1450).scale)
        val wiggle = pulse.copy(emph = "wiggle")
        assertNotEquals(0f, StudioEdit.pose(wiggle, 1000).rot)
        // keys of the redraw: an accent effect moves all the time
        assertNotEquals(StudioEdit.overlayKey(listOf(pulse), 1000, { 0L }), StudioEdit.overlayKey(listOf(pulse), 1010, { 0L }))
        val back = StudioEdit.blockFrom(StudioEdit.blockJson(pulse.copy(theme = "{}", seg = "1,2,3")))!!
        assertEquals("pulse", back.emph)
        assertEquals("1,2,3", back.seg)
        assertNull(StudioEdit.blockFrom(JSONObject("""{"id":"b9","kind":"lottie","start":0,"dur":3000}""")))
        assertNotNull(StudioEdit.blockFrom(JSONObject("""{"id":"b9","kind":"motion","start":0,"dur":3000,"look":"bars"}""")))
        val p = EProject("p", "/x", 1080, 1920, 1000, theme = EditThemes.json(EditThemes.preset("paper")!!).toString())
        assertEquals("paper", EditThemes.parse(StudioEdit.fromJson(StudioEdit.toJson(p).toString())!!.theme)?.id)
    }
}

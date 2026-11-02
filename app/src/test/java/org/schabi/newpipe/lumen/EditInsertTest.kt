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

class EditInsertTest {
    private val words = listOf(
        SWord("Итак,", 11_000, 11_300), SWord("Android", 12_300, 12_700), SWord("занимает", 12_750, 13_200), SWord("72", 13_300, 13_600),
        SWord("%", 13_600, 13_800), SWord("рынка,", 13_850, 14_200), SWord("а", 14_300, 14_400), SWord("iOS", 14_450, 14_800),
        SWord("—", 14_800, 14_850), SWord("27", 14_900, 15_200), SWord("%.", 15_200, 15_500), SWord("Дальше", 18_000, 18_400)
    )

    private fun ctx(block: FRect = FRect(0.05f, 0.3f, 0.95f, 0.66f), req: String = "K7Q2", ws: List<SWord> = words) =
        InsertCtx(1080, 1920, block, "центр", 12_300, 5000, ws, "русский", "по-доброму", req, accent = "#FFD60A")

    @Test
    fun requestMarksAreShortAndDistinct() {
        val a = EditInsert.reqId(1_700_000_000_000, 1)
        val b = EditInsert.reqId(1_700_000_000_000, 2)
        assertEquals(4, a.length)
        assertNotEquals(a, b)
        assertTrue(a.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    }

    @Test
    fun briefJsonKeepsOnlyWhatWasSet() {
        assertEquals("""{"type":"own"}""", EditInsert.briefJson(EditBrief()).toString())
        val b = EditBrief(
            type = "compare", draw = "scheme", what = "кто больше", rows = listOf(BriefRow("Android", "72", "%", "🤖", true), BriefRow("iOS", "27", "%")),
            fields = mapOf("winner" to "Android"), onlySaid = false, title = "-", items = 2, tone = "fun", accent = 0xFFFFD60A.toInt(), bg = "semi", bgAlpha = 70,
            border = false, upper = true, donts = listOf("gradients"), opacity = 0.8f, dim = 0.4f, dimAll = true, lenMs = 6000, pace = "drive",
            accentMain = false, holdMs = 1500, loopS = 2, place = "custom", rect = FRect(0.1f, 0.2f, 0.9f, 0.5f), face = FRect(0.3f, 0.1f, 0.7f, 0.4f), avoidUi = false
        )
        val back = EditInsert.briefFrom(EditInsert.briefJson(b).toString())
        assertEquals(b, back)
        assertNull(EditInsert.briefFrom(""))
        assertEquals("own", EditInsert.briefFrom("""{"type":"nope"}""")!!.type)
    }

    @Test
    fun kindFollowsTheType() {
        assertEquals("anim", EditInsert.kindOf(EditBrief(type = "number", draw = "scheme")))
        assertEquals("scheme", EditInsert.kindOf(EditBrief(type = "compare", draw = "scheme")))
        assertEquals("anim", EditInsert.kindOf(EditBrief(type = "compare")))
        assertEquals("scheme", EditInsert.kindOf(EditBrief(type = "timeline")))
    }

    @Test
    fun briefLinesSkipAutoAndTheLookOfASchemes() {
        val b = EditBrief(type = "compare", rows = listOf(BriefRow("Android", "72", "%", main = true), BriefRow("iOS", "27", "%")),
            bg = "semi", bgAlpha = 70, accent = 0xFFFFD60A.toInt(), font = "condensed", upper = true, pace = "drive", dim = 0.4f, donts = listOf("gradients"))
        val anim = EditInsert.briefLines(b, "anim", minFont = 86)
        assertEquals("тип: Сравнение A и B; данные: Android — 72 % ★; iOS — 27 %", anim[0])
        assertTrue(anim.any { it.startsWith("вид: подложка полупрозрачная 70 %; акцент #FFD60A; шрифт узкий, КАПС; не мельче 86 px; без градиентов") })
        assertTrue(anim.any { it.startsWith("видео под блоком: затемнено на 40 %") })
        assertTrue(anim.any { it.contains("темп драйв") })
        val scheme = EditInsert.briefLines(b, "scheme", listOf(InsertZone(FRect(0f, 0.82f, 1f, 1f), "субтитры")))
        assertFalse(scheme.any { it.startsWith("вид:") || it.startsWith("видео под блоком") })
        assertFalse(scheme.any { it.contains("темп") })
        assertTrue(scheme.contains("не закрывать (доли блока): y 0.82–1 (субтитры)"))
        // nothing set — nothing about the look, the tone, the title
        val plain = EditInsert.briefLines(EditBrief(type = "steps"), "anim")
        assertFalse(plain.any { it.contains("тон") || it.contains("заголовок") || it.startsWith("вид:") })
    }

    @Test
    fun requestIsOneSelfContainedText() {
        val b = EditBrief(type = "compare", rows = EditInsert.dataFromSpeech(words.joinToString(" ") { it.text }))
        val anim = EditInsert.request(ctx(), b, "anim")
        assertTrue(anim.startsWith("⟦Lumen → AI · lumen.insert v1 · #K7Q2⟧"))
        assertTrue(RelayText.isOwnRequest(anim))
        assertNull(EditInsert.detect(anim))
        assertTrue(anim.contains("КАДР: вертикальный 9:16, 1080×1920; блок 972×690 px"))
        assertTrue(anim.contains("ВРЕМЯ: блок 5.0 с — с 0:12.3 по 0:17.3 ролика."))
        assertTrue(anim.contains("[1]Android 0.00–0.40"))
        assertTrue(anim.contains("до: Итак,"))
        assertFalse(anim.contains("[11]"))
        assertTrue(anim.contains(EditInsert.ANIM_PROTOCOL))
        assertTrue(anim.contains("// lumen.anim v1 #K7Q2"))
        assertTrue(anim.contains("ПОЖЕЛАНИЕ АВТОРА: по-доброму"))
        val scheme = EditInsert.request(ctx(), b, "scheme")
        assertTrue(scheme.contains(StudioScheme.PROTOCOL))
        assertTrue(scheme.contains("{\"req\":\"K7Q2\""))
        assertFalse(scheme.contains("DATA"))
        val fix = EditInsert.fixRequest(scheme, "scheme", listOf("нет узлов"), "{\"layout\":\"chain\"}", "Меньше слов")
        assertTrue(RelayText.isOwnRequest(fix))
        assertTrue(fix.contains("- нет узлов"))
        assertTrue(fix.contains("ПРАВКА АВТОРА: Меньше слов"))
    }

    @Test
    fun animDataIsInsideTheBlock() {
        val d = EditInsert.animData(ctx(), EditBrief(type = "number", bgAlpha = 50, bg = "glass"))
        assertEquals(5.0, d.getDouble("dur"), 1e-9)
        assertEquals(972, d.getInt("w"))
        val ws = d.getJSONArray("words")
        assertEquals(10, ws.length())
        assertEquals(0.0, ws.getJSONObject(0).getDouble("s"), 1e-9)
        assertEquals("Android", ws.getJSONObject(0).getString("w"))
        val brief = d.getJSONObject("brief")
        assertEquals(50, brief.getInt("bgAlpha"))
        assertEquals("words", brief.getString("reveal"))
        assertEquals(86, EditInsert.minFontPx(1080, 1920, "l"))
    }

    @Test
    fun answersAreFoundInAnyWrapping() {
        val json = "Вот схема:\n```json\n{\"req\":\"K7Q2\",\"layout\":\"matrix\",\"title\":\"Кто победил\",\"nodes\":[{\"id\":\"a\",\"label\":\"Android\"}],}\n```"
        val s = EditInsert.detect(json) as InsertAnswer.Scheme
        assertEquals("K7Q2", s.req)
        assertEquals("Кто победил", s.name)
        val code = "```javascript\n// lumen.anim v1 #AB3C\n// name: Счётчик\n// loop: 2\nfunction frame(t, ctx, W, H, d) { ctx.fillRect(0,0,10,10) }\n```"
        val a = EditInsert.detect(code) as InsertAnswer.Anim
        assertEquals("AB3C", a.req)
        assertEquals("Счётчик", a.name)
        assertEquals(2000L, a.loopMs)
        assertTrue(a.code.startsWith("// lumen.anim v1"))
        val html = "<html><body><script>function frame(t, ctx){ return false }</script></body></html>"
        val h = EditInsert.detect(html) as InsertAnswer.Anim
        assertEquals("", h.req)
        assertNull(EditInsert.detect("Извините, не могу помочь"))
        assertNull(EditInsert.detect(""))
        assertNull(EditInsert.detect(null))
    }

    @Test
    fun answersGoToTheirPendingBlock() {
        val p1 = EBlock("b1", "scheme", 0, 4000, req = "AAAA")
        val p2 = EBlock("b2", "anim", 5000, 4000, req = "BBBB")
        val p3 = EBlock("b3", "anim", 9000, 4000, req = "CCCC")
        val done = EBlock("b4", "anim", 9000, 4000, file = "anim/x")
        val redo = EBlock("b5", "scheme", 9000, 4000, scheme = SScheme("chain", nodes = listOf(SchNode("a", label = "x"))), req = "EEEE")
        val blocks = listOf(p1, p2, p3, done, redo)
        assertTrue(EditInsert.isPending(p1))
        assertFalse(EditInsert.isPending(done))
        val anim = InsertAnswer.Anim("function frame(){}", "BBBB", "x", 0)
        assertEquals("b2", EditInsert.match(blocks, anim)?.id)
        assertNull(EditInsert.match(blocks, anim.copy(req = "ZZZZ")))
        assertNull(EditInsert.match(blocks, anim.copy(req = "EEEE")))
        assertEquals("b5", EditInsert.match(blocks, InsertAnswer.Scheme(SScheme("hub", nodes = emptyList()), "EEEE", "x"))?.id)
        assertEquals(listOf("b1", "b2", "b3", "b5"), EditInsert.waiting(blocks).map { it.id })
        assertEquals("b3", EditInsert.match(blocks, anim.copy(req = ""))?.id)
        assertEquals("b1", EditInsert.match(blocks, InsertAnswer.Scheme(SScheme("chain", nodes = listOf(SchNode("a", label = "x"))), "", "x"))?.id)
    }

    @Test
    fun numbersAndNamesFromTheSpeech() {
        val r = EditInsert.dataFromSpeech("Android занимает 72 % рынка, а iOS — 27 %.")
        assertEquals(listOf(BriefRow("Android", "72", "%", main = true), BriefRow("iOS", "27", "%")), r)
        val m = EditInsert.dataFromSpeech("За год канал набрал 2,5 млн подписчиков")
        assertEquals(BriefRow("подписчиков", "2,5", "млн"), m.single())
        val y = EditInsert.dataFromSpeech("В 2020 году всё изменилось")
        assertEquals("2020", y.single().value)
        assertFalse(y.single().main)
        val names = EditInsert.dataFromSpeech("Об этом рассказал Илон Маск, а потом и Тим Кук.")
        assertEquals(listOf("Илон", "Маск", "Тим", "Кук"), names.map { it.label })
        assertTrue(EditInsert.dataFromSpeech("просто слова без чисел").isEmpty())
    }

    @Test
    fun placeKeepsOutOfTheSafeZones() {
        val c = EditInsert.place(EditBrief(), 1080, 1920)
        assertTrue(c.b <= 0.8f)
        assertEquals(0.5f, (c.l + c.r) / 2, 1e-4f)
        assertEquals(FRect(0f, 0f, 1f, 1f), EditInsert.place(EditBrief(place = "full"), 1080, 1920))
        val bottom = EditInsert.place(EditBrief(place = "bottom", scale = "l"), 1080, 1920)
        assertEquals(0.8f, bottom.b, 1e-4f)
        assertEquals(0.86f, bottom.w, 1e-4f)
        // a face at the top: the block goes down
        assertEquals("bottom", EditInsert.defaultPlace(EditBrief(face = FRect(0.3f, 0.1f, 0.7f, 0.4f)), 1080, 1920))
        assertEquals("bottom", EditInsert.defaultPlace(EditBrief(type = "lower"), 1080, 1920))
        val tall = EditInsert.place(EditBrief(scale = "xl", shape = "tall"), 1920, 1080)
        assertTrue(tall.h <= 0.8001f)
        val zones = EditInsert.inBlock(FRect(0f, 0.5f, 1f, 1f), EditInsert.uiZones(1080, 1920))
        assertEquals(0.64f, zones[0].r.t, 1e-4f)
    }

    @Test
    fun warningsComeWithFixes() {
        val empty = EditInsert.warnings(EditBrief(), ctx(ws = emptyList()))
        assertTrue(empty.single { it.id == "empty" }.blocking)
        val b = EditBrief(type = "list", items = 7, lenMs = 1500)
        val w = EditInsert.warnings(b, ctx())
        assertTrue(w.any { it.id == "short" })
        val longer = EditInsert.fix(b, "long", ctx())
        assertTrue(EditInsert.warnings(longer, ctx()).none { it.id == "short" })
        // a light frame under a glass plate with white text: too little contrast
        val glass = EditBrief(type = "number", bg = "glass")
        val faint = EditInsert.warnings(glass, ctx(), frameLum = 0.8).single { it.id == "faint" }
        assertTrue(faint.fixes.any { it.first == "dim" })
        val denser = EditInsert.fix(glass, "plate", ctx())
        assertTrue(EditInsert.warnings(denser, ctx(), frameLum = 0.8).none { it.id == "faint" })
        // a block over the Shorts interface moves away
        val low = ctx(block = FRect(0.1f, 0.7f, 0.9f, 0.95f))
        assertTrue(EditInsert.warnings(glass, low).any { it.id == "zone" })
        val moved = EditInsert.fix(glass, "move", low)
        val r = EditInsert.place(moved, 1080, 1920)
        assertTrue(EditInsert.uiZones(1080, 1920).none { EditInsert.cross(r, it.r) != null })
    }

    @Test
    fun framesOfAnAnimation() {
        assertEquals(150, EditInsert.frameCount(5000, 30, 0))
        assertEquals(60, EditInsert.frameCount(5000, 30, 2000))
        assertEquals(900, EditInsert.frameCount(60_000, 30, 0))
        val m = AnimMeta("k", 30, 972, 692, 150, 5000, 0)
        assertEquals(0, EditInsert.frameIndex(0, 5000, m))
        assertEquals(149, EditInsert.frameIndex(9000, 5000, m))
        // made for 5 s, the block now lasts 10 s: stretched
        assertEquals(75, EditInsert.frameIndex(5000, 10_000, m))
        val loop = AnimMeta("k", 30, 972, 692, 60, 5000, 2000)
        assertEquals(15, EditInsert.frameIndex(2500, 9000, loop))
        assertNull(EditInsert.frameIndex(100, 5000, null))
        assertNull(EditInsert.frameIndex(-1, 5000, m))
        val k = EditInsert.renderKey(972, 692, 5000, 30, 0, "{}")
        assertNotEquals(k, EditInsert.renderKey(972, 692, 6000, 30, 0, "{}"))
        assertEquals(EditInsert.renderKey(972, 692, 5000, 30, 2000, "{}"), EditInsert.renderKey(972, 692, 9000, 30, 2000, "{}"))
        assertTrue(EditInsert.stale(null, k))
        assertFalse(EditInsert.stale(m.copy(key = k), k))
        assertEquals(m.copy(empty = setOf(3, 1)), AnimMeta.of(JSONObject(m.copy(empty = setOf(1, 3)).json().toString())))
    }

    @Test
    fun animCodeChecks() {
        assertTrue(EditInsert.animProblems("function frame(t, ctx, W, H, d) { var x = d.duration; }").any { it.contains("data.dur") })
        assertTrue(EditInsert.animProblems("function frame(t, ctx, W, H, d) { return false }").isEmpty())
        assertTrue(EditInsert.trialProblems(55.0, emptyList(), false, false, false).single().contains("55 мс"))
        assertTrue(EditInsert.trialProblems(5.0, emptyList(), true, false, false).single().contains("пустые"))
        assertTrue(EditInsert.trialProblems(5.0, emptyList(), false, true, true).single().contains("непрозрачно"))
    }

    @Test
    fun blocksOfTheWizardInTheProjectFile() {
        val anim = EBlock("b1", "anim", 1000, 5000, file = "anim/abc", text = "Счётчик", animIn = "none", animOut = "none", fps = 24, loopMs = 2000,
            brief = EditInsert.briefJson(EditBrief(type = "number")).toString(), over = BlockLook(fill = "semi", bgAlpha = 70, accent = 0xFFFFD60A.toInt()),
            dim = 0.4f, dimAll = true, history = listOf("old1", "old2"))
        val pending = EBlock("b2", "scheme", 7000, 4000, req = "K7Q2", dim = 0.2f)
        val p = EProject("p", "/x.mp4", 1080, 1920, 20_000, listOf(anim, pending), brand = "{\"type\":\"own\",\"pace\":\"drive\"}")
        val back = StudioEdit.fromJson(StudioEdit.toJson(p).toString())!!
        assertEquals(p, back)
        // without a mark a scheme or animation without content is dropped; old blocks read as before
        assertNull(StudioEdit.blockFrom(JSONObject("""{"id":"b3","kind":"scheme","start":0,"dur":3000}""")))
        assertNull(StudioEdit.blockFrom(JSONObject("""{"id":"b3","kind":"anim","start":0,"dur":3000}""")))
        val old = StudioEdit.blockFrom(JSONObject("""{"id":"b5","kind":"text","start":0,"dur":3000,"text":"x"}"""))!!
        assertEquals(30, old.fps)
        assertNull(old.over)
        assertEquals(0f, old.dim)
        assertFalse(StudioEdit.blockJson(old).has("fps"))
        assertNotNull(StudioEdit.blockJson(anim).optJSONObject("over"))
    }

    @Test
    fun lookOverTheStyle() {
        val st = SchemeStyle("x", "x", fill = "glass", radius = 0.18f, border = 1f, shadow = true, font = "sans", textScale = 1f)
        assertEquals(st, EditInsert.overStyle(st, null))
        val o = EditInsert.overStyle(st, BlockLook(fill = "solid", accent = 0xFFFF0000.toInt(), font = "serif", upper = true, textScale = 1.25f, radius = "none", border = false, shadow = false))
        assertEquals("solid", o.fill)
        assertEquals(0xFFFF0000.toInt(), o.accent)
        assertEquals("serif", o.font)
        assertTrue(o.upper)
        assertEquals(1.25f, o.textScale, 1e-6f)
        assertEquals(0f, o.radius)
        assertEquals(0f, o.border)
        assertFalse(o.shadow)
        assertEquals(st.fill, EditInsert.overStyle(st, BlockLook(font = "nope")).fill)
        assertEquals("sans", EditInsert.overStyle(st, BlockLook(font = "nope")).font)
        assertNull(EditInsert.lookOf(EditBrief()))
        assertEquals(BlockLook(fill = "semi", bgAlpha = 70, textScale = 1.25f), EditInsert.lookOf(EditBrief(bg = "semi", bgAlpha = 70, size = "l")))
        assertEquals(BlockLook(fill = "semi", bgAlpha = 70), EditInsert.lookFrom(EditInsert.lookJson(BlockLook(fill = "semi", bgAlpha = 70))))
    }

    @Test
    fun recipesAndTheStyleOfTheVideo() {
        val b = EditBrief(type = "compare", what = "кто больше", rows = listOf(BriefRow("A", "1")), title = "Итог", bg = "semi", bgAlpha = 60,
            accent = 0xFF00FF00.toInt(), pace = "drive", dim = 0.4f, place = "top", lenMs = 6000, face = FRect(0f, 0f, 0.5f, 0.5f))
        val r = EditInsert.recipeOf(b)
        assertEquals("", r.what)
        assertTrue(r.rows.isEmpty())
        assertEquals("", r.title)
        assertEquals(0L, r.lenMs)
        assertNull(r.face)
        assertEquals("semi", r.bg)
        assertEquals("top", r.place)
        val content = EditBrief(type = "list", what = "три шага", rows = listOf(BriefRow("x")), lenMs = 4000)
        val m = EditInsert.withRecipe(content, r)
        assertEquals("compare", m.type)
        assertEquals("три шага", m.what)
        assertEquals(4000L, m.lenMs)
        assertEquals("semi", m.bg)
        val brand = EditInsert.brandOf(b)
        assertEquals("own", brand.type)
        assertEquals("", brand.place)
        assertEquals(0.4f, brand.dim)
        val withBrand = EditInsert.withBrand(EditBrief(type = "number", what = "w"), brand)
        assertEquals("number", withBrand.type)
        assertEquals("drive", withBrand.pace)
        assertEquals(60, withBrand.bgAlpha)
        assertEquals(brand, EditInsert.briefFrom(EditInsert.briefJson(brand).toString()))
        // defaults: the number of the speech for a number fact, the brand over it
        val d = EditInsert.defaults("number", "Канал набрал 2,5 млн подписчиков", brand, null)
        assertEquals(listOf(BriefRow("подписчиков", "2,5", "млн")), d.rows)
        assertEquals("drive", d.pace)
        assertEquals("Илон Маск", EditInsert.defaults("kinetic", "Илон Маск", null, null).fields["phrase"])
    }

    @Test
    fun examplesAndMontageTemplatesAreValidAnimations() {
        assertEquals(6, AnimExamples.ALL.size)
        for (e in AnimExamples.ALL) {
            assertTrue(e.id + ": " + EditInsert.animProblems(e.code), EditInsert.animProblems(e.code).isEmpty())
            val a = EditInsert.detect(e.code) as InsertAnswer.Anim
            assertEquals(e.ru, a.name)
            assertTrue(EditInsert.TYPES.any { it.id == e.type })
        }
        val adapted = EditInsert.canvasAdapter(WebLayer.EXAMPLE)
        assertTrue(adapted.startsWith("// lumen.anim v1"))
        assertTrue(adapted.contains("d.duration=d.dur"))
        assertTrue(EditInsert.detect(adapted) is InsertAnswer.Anim)
        assertTrue(EditInsert.request(ctx(), EditBrief(type = "number"), "anim").contains("ОБРАЗЕЦ КОДА"))
    }

    @Test
    fun dimmingAndRedrawKeys() {
        val b = EBlock("b1", "anim", 1000, 4000, file = "anim/x/r_k", fps = 30, dim = 0.4f)
        assertEquals(0f, StudioEdit.dimAlpha(b, 999))
        assertEquals(0.5f, StudioEdit.dimAlpha(b, 1150), 1e-4f)
        assertEquals(1f, StudioEdit.dimAlpha(b, 3000))
        assertEquals(0f, StudioEdit.dimAlpha(b, 5000))
        // the overlay is redrawn when the animation frame changes, not on every video frame
        val k1 = StudioEdit.overlayKey(listOf(b), 2000, { 0L })
        val k2 = StudioEdit.overlayKey(listOf(b), 2010, { 0L })
        val k3 = StudioEdit.overlayKey(listOf(b), 2040, { 0L })
        assertEquals(k1, k2)
        assertNotEquals(k1, k3)
        val loop = b.copy(loopMs = 1000)
        assertEquals(StudioEdit.overlayKey(listOf(loop), 2000, { 0L }), StudioEdit.overlayKey(listOf(loop), 3000, { 0L }))
    }
}

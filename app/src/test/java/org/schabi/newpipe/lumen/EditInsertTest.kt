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

class EditInsertTest {

    private val words = listOf(
        SWord("За", 10_000, 10_200), SWord("год", 10_250, 10_500), SWord("канал", 10_600, 11_000), SWord("вырос", 11_050, 11_400),
        SWord("до", 11_450, 11_550), SWord("2,5", 11_600, 11_900), SWord("млн", 11_950, 12_300), SWord("подписчиков.", 12_350, 13_000),
        SWord("Потом", 16_000, 16_300)
    )

    private fun ctx(brief: EditBrief = EditBrief(), kind: String = "anim", fw: Int = 1080, fh: Int = 1920) =
        InsertCtx(fw, fh, EditInsert.place(brief, kind, fw, fh), 10_000, 4_000, words, "Russian")

    @Test
    fun numbersAndNamesComeFromTheSpeech() {
        val rows = EditInsert.dataFromSpeech(listOf("Канал", "вырос", "до", "2,5", "млн", "подписчиков,", "а", "Android", "занял", "72%"))
        val n = rows.first { it.value == "2,5" }
        assertEquals("млн", n.unit)
        assertEquals("Канал вырос", n.label)
        val pct = rows.first { it.value == "72" }
        assertEquals("%", pct.unit)
        // a capitalised word not after a full stop is a name
        assertTrue(rows.any { it.label == "Android" && it.value.isEmpty() })
        // the first word of the speech is not taken for a name
        assertFalse(rows.any { it.label == "Канал" && it.value.isEmpty() })
    }

    @Test
    fun defaultsFollowTheTypeAndTheSpeech() {
        val b = EditInsert.defaults("number", listOf("до", "2,5", "млн", "подписчиков"), null, portrait = true)
        assertEquals("anim", EditInsert.kindOf(b))
        assertEquals("2,5", b.fields["value"])
        assertEquals("млн", b.fields["unit"])
        assertEquals("code", b.enter)
        assertEquals("words", b.reveal)
        val s = EditInsert.defaults("timeline", emptyList(), null, portrait = false)
        assertEquals("scheme", EditInsert.kindOf(s))
        assertEquals("timeline", s.layout)
        assertEquals("", s.reveal)
        // a pointer covers the frame so it can point anywhere
        assertEquals("full", EditInsert.defaults("pointer", emptyList(), null, true).place)
        // "both" goes by the layout: steps are a scheme unless the author picks an animation
        assertEquals("scheme", EditInsert.kindOf(EditInsert.defaults("steps", emptyList(), null, true)))
        assertEquals("anim", EditInsert.kindOf(EditBrief(type = "steps", kind = "anim")))
    }

    @Test
    fun theStyleOfTheVideoGoesIntoNewBriefs() {
        val brand = EditBrief(fill = "glass", bgAlpha = 0.4f, accent = 0xFFFF0000.toInt(), font = "condensed", upper = true, dim = 0.2f, pace = "drive")
        val b = EditInsert.defaults("list", emptyList(), brand, true)
        assertEquals("glass", b.fill)
        assertEquals(0.4f, b.bgAlpha, 0.001f)
        assertEquals(0xFFFF0000.toInt(), b.accent)
        assertEquals(true, b.upper)
        assertEquals("drive", b.pace)
        // a recipe keeps the look but not the content
        val r = EditInsert.recipe(b.copy(what = "x", items = listOf(BriefItem("a")), title = "T", pos = listOf(0.1f, 0.2f)))
        assertEquals("", r.what)
        assertTrue(r.items.isEmpty() && r.pos.isEmpty())
        assertEquals("glass", r.fill)
    }

    @Test
    fun theRequestHasOnlyWhatTheAuthorSet() {
        val auto = EditInsert.request("anim", EditBrief(type = "number"), ctx(), "K7Q2")
        assertTrue(auto.startsWith("МЕТКА ЗАПРОСА: #K7Q2"))
        assertTrue(auto.contains("lumen.anim v1"))
        assertFalse(auto.contains("палитра"))
        assertFalse(auto.contains("затемнено"))
        // speech of the block in seconds from its start, the word after it as context
        assertTrue(auto.contains("2,5 1.60–1.90"))
        assertTrue(auto.contains("(после блока: Потом)") || !auto.contains("Потом 6"))
        val set = EditInsert.request("anim", EditBrief(type = "compare", fill = "semi", bgAlpha = 0.7f, dim = 0.4f, opacity = 0.8f, pace = "drive", upper = true,
            items = listOf(BriefItem("Android", "72", "%", main = true), BriefItem("iOS", "27", "%"))), ctx(), "K7Q2")
        assertTrue(set.contains("полупрозрачная подложка 70 %"))
        assertTrue(set.contains("затемнено на 40 %"))
        assertTrue(set.contains("прозрачность блока: 80 %"))
        assertTrue(set.contains("Android 72 % ★"))
        assertTrue(set.contains("КАПС"))
        assertTrue(set.contains("150–300 мс"))
    }

    @Test
    fun aSchemeRequestNumbersTheWordsAndLeavesTheLookToLumen() {
        val r = EditInsert.request("scheme", EditBrief(type = "steps", layout = "chain", fill = "glass", upper = true), ctx(kind = "scheme"), "AB2C")
        assertTrue(r.contains("[5]2,5"))
        assertTrue(r.contains("layout: chain"))
        assertTrue(r.contains("\"req\":\"AB2C\""))
        // the look of a scheme is drawn by Lumen: not asked of the AI
        assertFalse(r.contains("КАПС"))
        assertFalse(r.contains("стекло"))
    }

    @Test
    fun answersAreRecognisedWithTheirMark() {
        val js = "Вот код:\n```javascript\n// lumen.anim v1 #K7Q2\n// name: Счётчик\n// loop: 2\nfunction frame(t, ctx, W, H, d) { ctx.fillRect(0,0,W,H*t); }\n```"
        val a = EditInsert.detect(js) as InsertAnswer.Anim
        assertEquals("K7Q2", a.req)
        assertEquals("Счётчик", a.name)
        assertEquals(2000L, a.loopMs)
        assertTrue(a.code.startsWith("// lumen.anim"))
        val json = "```json\n{\"req\":\"AB2C\",\"layout\":\"chain\",\"title\":\"Рост\",\"nodes\":[{\"id\":\"a\",\"label\":\"Старт\"},{\"id\":\"b\",\"label\":\"Финиш\"}]}\n```"
        val s = EditInsert.detect(json) as InsertAnswer.Scheme
        assertEquals("AB2C", s.req)
        assertEquals("chain", s.scheme.layout)
        // our own request in the clipboard is not an answer; neither is plain text
        assertNull(EditInsert.detect(RelayText.request("studio_edit_anim", "role", EditInsert.request("anim", EditBrief(), ctx(), "K7Q2"), false, null, false, "")))
        assertNull(EditInsert.detect("Привет! Чем помочь?"))
    }

    @Test
    fun framesOfAnAnimationFollowTheBlock() {
        assertEquals(150, EditInsert.frameCount(5000, 30, 0))
        assertEquals(60, EditInsert.frameCount(5000, 30, 2000))
        assertEquals(0, EditInsert.frameIndex(0, 5000, 5000, 30, 0, 150))
        assertEquals(149, EditInsert.frameIndex(9999, 5000, 5000, 30, 0, 150))
        // a loop repeats
        assertEquals(EditInsert.frameIndex(500, 5000, 5000, 30, 2000, 60), EditInsert.frameIndex(2500, 5000, 5000, 30, 2000, 60))
        // frames of another length are stretched over the block until it is rendered again
        assertEquals(149, EditInsert.frameIndex(9_990, 10_000, 5_000, 30, 0, 150))
        assertEquals(75, EditInsert.frameIndex(5_000, 10_000, 5_000, 30, 0, 150))
        assertEquals(-1, EditInsert.frameIndex(0, 5000, 5000, 30, 0, 0))
    }

    @Test
    fun aRenderIsKeyedByWhatChangesItsFrames() {
        val k = EditInsert.renderKey("code", 900, 500, 5000, 30, 0, "{}")
        assertEquals(k, EditInsert.renderKey("code", 900, 500, 5000, 30, 0, "{}"))
        assertFalse(k == EditInsert.renderKey("code", 900, 500, 6000, 30, 0, "{}"))
        assertFalse(k == EditInsert.renderKey("code2", 900, 500, 5000, 30, 0, "{}"))
        assertFalse(k == EditInsert.renderKey("code", 900, 500, 5000, 30, 0, "{\"a\":1}"))
        assertTrue(EditInsert.codeDir(" x ").startsWith("anim/"))
        assertEquals("anim/abc", EditAnim.codeDirOf("anim/abc/r_123"))
        val b = EBlock("b1", EditAnim.KIND, 0, 5000, w = 0.9f, ratio = 0.6f)
        val (w, h) = EditAnim.renderSize(b, 1080, 1920)
        assertTrue(w % 2 == 0 && h % 2 == 0)
        assertTrue(w <= EditAnim.MAX_LONG && h <= EditAnim.MAX_SHORT)
    }

    @Test
    fun placesKeepClearOfTheFaceAndTheShortsUi() {
        val full = EditInsert.place(EditBrief(place = "full"), "anim", 1080, 1920)
        assertEquals(1f, full.w, 0.001f)
        val bottom = EditInsert.place(EditBrief(place = "bottom", avoid = listOf("shorts")), "anim", 1080, 1920)
        val hh = bottom.w * 1080 * bottom.ratio / 2 / 1920
        assertTrue(bottom.y + hh <= 0.8f + 0.001f)
        // a face in the middle: the block goes above or below it
        val face = listOf(0.3f, 0.35f, 0.4f, 0.3f)
        val c = EditInsert.place(EditBrief(type = "number", place = "center", face = face), "anim", 1080, 1920)
        val ch = c.w * 1080 * c.ratio / 2 / 1920
        assertTrue(c.y + ch <= 0.35f + 0.02f || c.y - ch >= 0.65f - 0.02f)
        // a place set by hand
        val own = EditInsert.place(EditBrief(place = "custom", pos = listOf(0.3f, 0.2f)), "anim", 1080, 1920)
        assertEquals(0.2f, own.y, 0.05f)
    }

    @Test
    fun warningsOfferTheirFix() {
        val empty = EditInsert.warnings(EditBrief(type = "custom"), "anim", ctx().copy(words = emptyList()), 0, null)
        assertTrue(empty.any { it.blocking && it.fix == "describe" })
        val short = EditInsert.warnings(EditBrief(type = "list", items = (1..6).map { BriefItem("пункт номер $it", "$it") }), "anim", ctx().copy(durMs = 2000), 8, null)
        assertTrue(short.any { it.fix == "longer" })
        val see = EditInsert.warnings(EditBrief(what = "x", opacity = 0.4f), "anim", ctx(), 8, null)
        assertTrue(see.any { it.fix == "opaque" })
        // no plate over a mid-grey video: the text may not read
        val grey = EditInsert.warnings(EditBrief(what = "x", fill = "none"), "anim", ctx(), 8, 0.6f)
        assertTrue(grey.any { it.id == "contrast" })
        val dark = EditInsert.warnings(EditBrief(what = "x", fill = "solid"), "anim", ctx(), 8, 0.6f)
        assertFalse(dark.any { it.id == "contrast" })
        assertTrue(EditInsert.readMs(EditBrief(type = "number"), 0) >= 2500)
    }

    @Test
    fun theBriefSurvivesJson() {
        val b = EditBrief(type = "compare", kind = "anim", what = "кто больше", items = listOf(BriefItem("A", "1", "%", "🙂", true)), fields = mapOf("winner" to "a"),
            onlySaid = false, title = "-", count = 3, accent = 0xFF00FF00.toInt(), fill = "glass", bgAlpha = 0.5f, border = false, upper = true, opacity = 0.7f,
            dim = 0.4f, dimAll = true, holdMs = 1000, loopMs = 2000, avoid = listOf("face"), face = listOf(0.1f, 0.2f, 0.3f, 0.4f), target = listOf(0.5f, 0.6f),
            pos = listOf(0.4f, 0.3f), sfx = "soft", avoidStyle = listOf("emoji"))
        assertEquals(b, EditInsert.briefFrom(EditInsert.briefJson(b).toString()))
        assertEquals(EditBrief(), EditInsert.briefFrom("{}"))
        assertNull(EditInsert.briefFrom("not json"))
    }

    @Test
    fun theLookOfABriefBecomesTheLookOfTheBlock() {
        assertNull(EditInsert.lookOf(EditBrief()))
        val l = EditInsert.lookOf(EditBrief(fill = "solid", bgAlpha = 0.9f, textSize = "l", radius = "none", upper = true))
        assertNotNull(l)
        assertEquals("solid", l!!.fill)
        assertEquals(1.2f, l.textScale, 0.001f)
        assertEquals(0f, l.radius, 0.001f)
        assertEquals(l, BlockLook.of(l.json()))
    }

    @Test
    fun animationAndWaitingBlocksSurviveTheProject() {
        val anim = EBlock("b1", EditAnim.KIND, 1000, 4000, file = "anim/abc/r_1", text = "Счётчик", fps = 30, loopMs = 2000, brief = "{\"type\":\"number\"}",
            over = BlockLook(fill = "glass"), dim = 0.3f, dimAll = true, past = listOf("anim/old/r_2"))
        assertEquals(anim, StudioEdit.blockFrom(StudioEdit.blockJson(anim)))
        // a block waiting for another AI is kept with its mark; one the built-in AI is drawing is not
        val waiting = EBlock("b2", "scheme", 0, 3000, req = "K7Q2")
        assertTrue(waiting.pending)
        assertEquals(waiting, StudioEdit.blockFrom(StudioEdit.blockJson(waiting)))
        assertNull(StudioEdit.blockFrom(StudioEdit.blockJson(EBlock("b3", "scheme", 0, 3000))))
        assertNull(StudioEdit.blockFrom(StudioEdit.blockJson(EBlock("b4", EditAnim.KIND, 0, 3000))))
        // the overlay of the export changes with the frame of the animation, not with every video frame
        val k1 = StudioEdit.overlayKey(listOf(anim.copy(animIn = "none", animOut = "none")), 2_000, { 0L })
        val k2 = StudioEdit.overlayKey(listOf(anim.copy(animIn = "none", animOut = "none")), 2_010, { 0L })
        val k3 = StudioEdit.overlayKey(listOf(anim.copy(animIn = "none", animOut = "none")), 2_100, { 0L })
        assertEquals(k1, k2)
        assertFalse(k1 == k3)
    }

    @Test
    fun aMontageTemplateRunsOnTheTimeOfTheVideo() {
        val code = "// name: Хук\nfunction frame(t, ctx, W, H, d) { return t > 1; }"
        val a = EditInsert.adaptCanvas(code, 12_500)
        assertTrue(a.startsWith("// name: Хук"))
        assertTrue(a.contains("__f(t+12.500"))
    }

    @Test
    fun examplesAreValidAnimations() {
        for (ex in AnimExamples.ALL) {
            assertTrue(ex.id, EditInsert.animProblems(ex.code).isEmpty())
            assertEquals(ex.ru, EditInsert.nameOf(ex.code, ""))
            assertTrue(ex.id, EditInsert.detect(ex.code) is InsertAnswer.Anim)
        }
    }
}

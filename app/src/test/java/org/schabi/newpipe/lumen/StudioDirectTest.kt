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
import kotlin.math.abs

class StudioDirectTest {

    private fun words(vararg s: String): List<SWord> = s.mapIndexed { i, w -> SWord(w, i * 500L, i * 500L + 400) }

    // ---------- tasks and frames ----------

    @Test
    fun frameSizes() {
        assertEquals(720 to 1280, StudioAspect.PORTRAIT.size(720))
        assertEquals(1080 to 1920, StudioAspect.PORTRAIT.size(1080))
        assertEquals(1920 to 1080, StudioAspect.LANDSCAPE.size(1080))
        assertEquals(720 to 720, StudioAspect.SQUARE.size(720))
        assertEquals(StudioTask.VOICE, StudioTask.of("voice"))
        assertEquals(StudioTask.SHORTS, StudioTask.of("???"))
        assertEquals(StudioAspect.LANDSCAPE, StudioAspect.of("16:9"))
    }

    // ---------- easing ----------

    @Test
    fun easingEnds() {
        for (f in listOf<(Float) -> Float>({ StudioMotion.inOut(it) }, { StudioMotion.backOut(it) }, { StudioMotion.spring(it) })) {
            assertTrue(abs(f(0f)) < 0.001f)
            assertTrue(abs(f(1f) - 1f) < 0.001f)
        }
        assertEquals(0.5f, StudioMotion.inOut(0.5f), 0.001f)
        // back and spring overshoot
        assertTrue((1..99).any { StudioMotion.backOut(it / 100f) > 1f })
        assertTrue((1..99).any { StudioMotion.spring(it / 100f) > 1f })
        assertEquals(0f, StudioMotion.at(100, 200, 300) { it }, 0f)
        assertEquals(1f, StudioMotion.at(600, 200, 300) { it }, 0f)
    }

    @Test
    fun punchZoomAlternates() {
        val cues = listOf(1000L, 3000L)
        assertEquals(1f, StudioMotion.punch(500, cues, 0.1f), 0f)
        assertEquals(1.1f, StudioMotion.punch(2000, cues, 0.1f), 0.0001f)
        assertEquals(1.04f, StudioMotion.punch(5000, cues, 0.1f), 0.0001f)
        assertEquals(1f, StudioMotion.punch(5000, emptyList(), 0.1f), 0f)
    }

    // ---------- protocol ----------

    @Test
    fun transcriptNumbersWordsAndPauses() {
        val w = listOf(SWord("Привет", 0, 400), SWord("мир", 450, 800), SWord("снова", 1800, 2200))
        assertEquals("[0]Привет [1]мир ⏸1.0 [2]снова", StudioDirector.transcript(w))
    }

    @Test
    fun parseOrdersCoversAndLimits() {
        val reply = "План:\n```json\n{\"title\":\"T\",\"drop\":[2,\"w3\",99],\"scenes\":[" +
            "{\"from\":\"w6\",\"to\":9,\"kind\":\"chart\",\"items\":[{\"label\":\"a\"}],\"transition\":\"whip\"," +
            "\"cues\":[{\"at\":7,\"do\":\"sfx\",\"name\":\"laser\"},{\"at\":8,\"do\":\"sfx\",\"name\":\"ding\"}]}," +
            "{\"from\":0,\"to\":4,\"kind\":\"stat\",\"title\":\"80%\",\"emph\":[\"80%\"],\"transition\":\"spin\"," +
            "\"cues\":[{\"at\":0,\"do\":\"zoom\"},{\"at\":1,\"do\":\"zoom\"},{\"at\":30,\"do\":\"pop\",\"text\":\"ВАЖНО\",\"icon\":\"⚠️\"}]}]}\n```"
        val d = StudioDirector.parse(reply, 12)!!
        assertEquals("T", d.title)
        assertEquals(setOf(2, 3), d.drop)
        assertEquals(2, d.scenes.size)
        // the gap (word 5) goes to the scene after it, the last scene ends at the last word
        assertEquals(0..4, d.scenes[0].from..d.scenes[0].to)
        assertEquals(5..11, d.scenes[1].from..d.scenes[1].to)
        val s0 = d.scenes[0]
        assertEquals("stat", s0.kind)
        assertEquals("", s0.transition)
        // zooms closer than 4 words: only the first; the pop is moved inside the scene
        assertEquals(listOf("zoom" to 0, "pop" to 4), s0.cues.map { it.type to it.at })
        val s1 = d.scenes[1]
        assertEquals("icons", s1.kind) // a chart without numbers
        assertEquals("whip", s1.transition)
        assertEquals(listOf(SCue("sfx", 8, "ding")), s1.cues)
        assertNull(StudioDirector.parse("нет json", 5))
        assertNull(StudioDirector.parse("{\"scenes\":[]}", 5))
    }

    @Test
    fun brokenReplyKeepsCompleteScenes() {
        val reply = "{\"scenes\":[{\"from\":0,\"to\":2,\"kind\":\"text\"},{\"from\":3,\"to\":5,\"ki"
        val d = StudioDirector.parse(reply, 8)!!
        assertEquals(1, d.scenes.size)
        assertEquals(7, d.scenes[0].to)
    }

    @Test
    fun safeDropLimits() {
        val sc = listOf(
            StudioDirector.DScene(0, 1, "text", "", emptyList(), emptyList(), "", emptyList()),
            StudioDirector.DScene(2, 19, "text", "", emptyList(), emptyList(), "", emptyList())
        )
        // a whole scene is never dropped
        assertEquals(setOf(5), StudioDirector.safeDrop(StudioDirector.Direction("", setOf(0, 1, 5), sc), 20))
        // too many words: nothing is cut
        assertEquals(emptySet<Int>(), StudioDirector.safeDrop(StudioDirector.Direction("", (2..8).toSet(), sc), 20))
    }

    @Test
    fun keepWithoutAndMapTime() {
        val w = words("a", "эм", "b", "c")
        // words: a 0-400, эм 500-900, b 1000-1400, c 1500-1900
        val keep = StudioDirector.keepWithout(w, setOf(1), 2000, padMs = 60)
        assertEquals(listOf(Span(0, 460), Span(940, 2000)), keep)
        assertEquals(460, StudioDirector.mapTime(940, keep))
        assertEquals(520, StudioDirector.mapTime(1000, keep))
        assertEquals(460, StudioDirector.mapTime(700, keep))
        // dropping the last word cuts to the end
        assertEquals(listOf(Span(0, 1460)), StudioDirector.keepWithout(w, setOf(3), 2000))
    }

    @Test
    fun applyCutsWordsAndMovesCues() {
        val w = words("Ну", "это", "важно", "эм", "очень", "важно")
        val d = StudioDirector.Direction("T", setOf(0, 3), listOf(
            StudioDirector.DScene(0, 2, "big", "", listOf(SItem("💡", "идея")), listOf("важно"), "zoom",
                listOf(SCue("zoom", 0), SCue("pop", 2, "ВАЖНО"))),
            StudioDirector.DScene(3, 5, "text", "", emptyList(), emptyList(), "", listOf(SCue("sfx", 3, "ding")))
        ))
        val a = StudioDirector.apply(d, w, 3000, d.drop)
        assertEquals(listOf("это", "важно", "очень", "важно"), a.words.map { it.text })
        assertEquals(listOf(0..1, 2..3), a.ranges)
        assertEquals("это важно", a.script.scenes[0].say)
        // cue on a dropped word moves to the next kept word of the scene
        assertEquals(listOf(0, 1), a.script.scenes[0].cues.map { it.at })
        assertEquals(0, a.script.scenes[1].cues[0].at)
        assertEquals(2, a.dropped)
        assertNotNull(a.keep)
        // times are continuous after the cut
        assertTrue(a.words.zipWithNext().all { (x, y) -> y.startMs >= x.endMs - 1 })
        assertTrue(a.words.first().startMs < 200)
        // nothing to drop: the take stays
        val b = StudioDirector.apply(d, w, 3000, emptySet())
        assertNull(b.keep)
        assertEquals(w, b.words)
    }

    @Test
    fun composeCutsOverOldCuts() {
        val old = listOf(Span(1000, 2000), Span(3000, 4000))
        // keep 0..500 and 800..1500 of the cut take
        val src = StudioDirector.composeCuts(old, listOf(Span(0, 500), Span(800, 1500)))
        assertEquals(listOf(Span(1000, 1500), Span(1800, 2000), Span(3000, 3500)), src)
        assertEquals(listOf(Span(5, 9)), StudioDirector.composeCuts(null, listOf(Span(5, 9))))
    }

    @Test
    fun emphMatchesWordsAndPhrases() {
        val w = listOf("Это", "в", "80%", "случаев,", "мой", "друг")
        assertEquals(setOf(2), StudioDirector.emphWords(w, 0..5, listOf("80%")))
        assertEquals(setOf(4, 5), StudioDirector.emphWords(w, 0..5, listOf("Мой друг")))
        assertEquals(emptySet<Int>(), StudioDirector.emphWords(w, 0..3, listOf("друг")))
    }

    @Test
    fun numbersAndKinds() {
        assertEquals(1200f, StudioDirector.number("1 200")!!, 0f)
        assertEquals(3.5f, StudioDirector.number("3,5 млн")!!, 0.0001f)
        assertEquals(55f, StudioDirector.number("55%")!!, 0f)
        assertNull(StudioDirector.number("нет"))
        assertEquals("chart", StudioDirector.fitKind("chart", listOf(SItem("", "a", "1"), SItem("", "b", "2"))))
        assertEquals("big", StudioDirector.fitKind("compare", listOf(SItem("❌", "миф"))))
    }

    // ---------- scene model ----------

    @Test
    fun sceneFieldsSurviveSaving() {
        val sc = SScript("T", listOf(SScene("Сказал", "chart", "Рост", listOf(SItem("📈", "2025", "55")),
            listOf("Рост"), listOf(SCue("zoom", 1), SCue("pop", 0, "ВАЖНО", "⚠️"), SCue("sfx", 0, "ding")), "slide")))
        assertEquals(sc, Studio.parseScript(Studio.toJson(sc)))
        assertEquals(SItem("", "2024", "55"), Studio.itemOf("2024 = 55"))
        assertEquals(SItem("📈", "рост", "3,5"), Studio.itemOf("📈 рост = 3,5"))
        assertEquals(SItem("", "a=b"), Studio.itemOf("a=b"))
        val text = Studio.toText(SScript("", listOf(SScene("Цифры", "chart", "", listOf(SItem("", "2024", "30"), SItem("", "2025", "55"))))))
        assertEquals(listOf("30", "55"), Studio.parseText(text).scenes[0].items.map { it.value })
    }

    // ---------- sound effects ----------

    @Test
    fun synthesisedEffects() {
        for (name in StudioDirector.SFX) {
            val s = StudioSfx.synth(name, 24_000)
            assertEquals(StudioSfx.lengthMs(name) * 24, s.size.toLong())
            assertTrue(name, s.all { it in -1.01f..1.01f })
            assertTrue(name, s.any { abs(it) > 0.05f })
        }
        assertEquals(0, StudioSfx.synth("laser", 24_000).size)
        assertEquals(800, StudioSfx.startOf("riser", 2000))
    }

    @Test
    fun effectEventsAndMix() {
        val w = words("a", "b", "c", "d")
        val script = SScript("", listOf(
            SScene("a b", cues = listOf(SCue("pop", 1, "X"))),
            SScene("c d", transition = "whip", cues = listOf(SCue("sfx", 1, "ding")))
        ))
        val times = listOf(SceneTime(0, 0, 1000, 0..1), SceneTime(1, 1000, 2000, 2..3))
        assertEquals(listOf(500L to "pop", 1000L to "whoosh", 1500L to "ding"), StudioSfx.events(script, times, w))
        val base = ShortArray(24_000) { 30_000 }
        val mixed = StudioSfx.mix(base, 24_000, listOf(0L to "boom"), 1f)
        assertEquals(base.size, mixed.size)
        assertTrue(mixed.any { it == 32767.toShort() })
        assertTrue(StudioSfx.mix(base, 24_000, emptyList()) === base)
    }
}

class StudioKeysTest {
    private fun shape(json: String) = StudioKeys.shapeOf(org.json.JSONObject(json))

    @Test
    fun readsShapesAndKeys() {
        val s = shape("{\"el\":\"Arrow\",\"w\":0.35,\"color\":\"yellow\",\"keys\":[{\"at\":\"w7+200\",\"draw\":1,\"ease\":\"OUT\"},{\"at\":5,\"x\":9,\"draw\":0}]}")!!
        assertEquals("arrow", s.el)
        assertEquals(0.35f, s.w, 0.0001f)
        // sorted by word, values clamped, "+200" read as a shift
        assertEquals(listOf(5, 7), s.keys.map { it.at })
        assertEquals(1.5f, s.keys[0].x!!, 0f)
        assertEquals(200L, s.keys[1].ms)
        assertEquals("out", s.keys[1].ease)
        assertNull(shape("{\"el\":\"star\",\"keys\":[{\"at\":1}]}"))
        assertNull(shape("{\"el\":\"text\",\"keys\":[{\"at\":1}]}"))
        assertNull(shape("{\"el\":\"ring\",\"keys\":[]}"))
        assertEquals(0.08f, shape("{\"el\":\"emoji\",\"text\":\"🔥\",\"keys\":[{\"at\":0}]}")!!.h, 0.0001f)
        // round trip
        assertEquals(s, StudioKeys.shapeOf(StudioKeys.toJson(s)))
    }

    @Test
    fun interpolatesBetweenKeys() {
        val s = SShape("ring", keys = listOf(SKey(0, x = 0.2f, s = 0f, draw = 0f), SKey(1, s = 1f, draw = 1f, ease = "linear"), SKey(2, x = 0.8f, ease = "linear")))
        val times = listOf(1000L, 2000L, 3000L)
        assertNull(StudioKeys.state(s, times, 999))
        val a = StudioKeys.state(s, times, 1500)!!
        assertEquals(0.5f, a.s, 0.0001f)
        assertEquals(0.5f, a.draw, 0.0001f)
        assertEquals(0.2f, a.x, 0.0001f)
        // the third key keeps scale and draw of the second one
        val b = StudioKeys.state(s, times, 2500)!!
        assertEquals(0.5f, b.x, 0.0001f)
        assertEquals(1f, b.s, 0.0001f)
        assertEquals(0.8f, StudioKeys.state(s, times, 9000)!!.x, 0.0001f)
        // defaults
        assertEquals(StudioKeys.State(0.5f, 0.5f, 1f, 0f, 1f, 1f), StudioKeys.resolve(listOf(SKey(0)))[0])
    }

    @Test
    fun keyTimesNeverGoBack() {
        val keys = listOf(SKey(3), SKey(1, ms = 100), SKey(4, ms = 50))
        val starts = mapOf(1 to 500L, 3 to 1500L, 4 to 2000L)
        assertEquals(listOf(1500L, 1500L, 2050L), StudioKeys.times(keys) { starts[it] ?: 0L })
    }

    @Test
    fun colorsAndEases() {
        assertEquals(0xFFFFD60A.toInt(), StudioKeys.color("yellow", 1, 2))
        assertEquals(1, StudioKeys.color("accent", 1, 2))
        assertEquals(0xFF102030.toInt(), StudioKeys.color("#102030", 1, 2))
        assertEquals(1, StudioKeys.color("#zz", 1, 2))
        for (e in StudioKeys.EASES) {
            assertEquals(e, 0f, StudioKeys.ease(e, 0f), 0.001f)
            assertEquals(e, 1f, StudioKeys.ease(e, 1f), 0.001f)
        }
    }

    @Test
    fun directorCarriesShapesAndBroll() {
        val reply = "{\"scenes\":[{\"from\":0,\"to\":2,\"broll\":\"night city\",\"brollMode\":\"card\"," +
            "\"shapes\":[{\"el\":\"ring\",\"keys\":[{\"at\":1,\"s\":0},{\"at\":1,\"ms\":300,\"s\":1}]},{\"el\":\"line\",\"keys\":[{\"at\":40}]}]}," +
            "{\"from\":3,\"to\":5,\"broll\":\"desk\"}]}"
        val d = StudioDirector.parse(reply, 6)!!
        assertEquals("card", d.scenes[0].brollMode)
        assertEquals("full", d.scenes[1].brollMode)
        // a key past the scene is moved inside it
        assertEquals(2, d.scenes[0].shapes[1].keys[0].at)
        val w = (0..5).map { SWord("w$it", it * 500L, it * 500L + 400) }
        val a = StudioDirector.apply(d, w, 3000, setOf(0))
        val sc = a.script.scenes[0]
        assertEquals("night city", sc.broll)
        // word 1 becomes the first word of the scene after word 0 is cut
        assertEquals(listOf(0, 0), sc.shapes[0].keys.map { it.at })
        assertEquals(listOf(0, 1), StudioDirector.needBroll(a.script))
        val withPhoto = a.script.copy(scenes = a.script.scenes.mapIndexed { i, s -> if (i == 0) s.copy(media = "/x.jpg", credit = "Photo by A") else s })
        assertEquals(listOf(1), StudioDirector.needBroll(withPhoto))
        assertEquals("Photo by A", StudioDirector.credits(withPhoto))
        assertEquals(withPhoto, Studio.parseScript(Studio.toJson(withPhoto)))
    }

    @Test
    fun brollCatalog() {
        val url = StudioBrollCatalog.url("night city", true, StudioAspect.LANDSCAPE)
        assertTrue(url.contains("q=night+city") && url.contains("license_type=commercial") && url.contains("aspect_ratio=wide"))
        assertTrue(!StudioBrollCatalog.url("x", false, null).contains("aspect_ratio"))
        val json = "{\"results\":[" +
            "{\"url\":\"https://a/1.jpg\",\"width\":400,\"height\":300,\"title\":\"Small\",\"license\":\"by\",\"license_version\":\"4.0\"}," +
            "{\"url\":\"https://a/2.jpg\",\"width\":1200,\"height\":1600,\"title\":\"Tall\",\"license\":\"by\"}," +
            "{\"url\":\"https://a/3.jpg\",\"width\":1920,\"height\":1080,\"title\":\"Wide\",\"creator\":\"Ann\",\"license\":\"by-sa\",\"license_version\":\"2.0\",\"foreign_landing_url\":\"https://f/3\"}," +
            "{\"url\":\"ftp://bad\"}]}"
        val hits = StudioBrollCatalog.parse(json)
        assertEquals(3, hits.size)
        assertEquals(listOf("Wide", "Tall", "Small"), StudioBrollCatalog.rank(hits, StudioAspect.LANDSCAPE).map { it.title })
        assertEquals("Tall", StudioBrollCatalog.rank(hits, StudioAspect.PORTRAIT)[0].title)
        assertEquals("Photo: \"Wide\" by Ann (CC BY-SA 2.0) https://f/3", StudioBrollCatalog.credit(hits[2]))
        assertTrue(StudioBrollCatalog.parse("oops").isEmpty())
    }
}

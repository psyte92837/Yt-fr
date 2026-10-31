/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StudioTest {

    // ---------- script ----------

    @Test
    fun parsesAiScriptWithFencesAndUnknownKind() {
        val reply = "Вот сценарий:\n```json\n{\"title\":\"Тест\",\"scenes\":[" +
            "{\"say\":\"Привет, мир\",\"kind\":\"icons\",\"items\":[{\"icon\":\"🔍\",\"label\":\"поиск\"},\"📚 контекст\"]}," +
            "{\"say\":\"Вторая\",\"kind\":\"weird\"},{\"say\":\"\"}]}\n```"
        val s = Studio.parseScript(reply)!!
        assertEquals("Тест", s.title)
        assertEquals(2, s.scenes.size)
        assertEquals("icons", s.scenes[0].kind)
        assertEquals(SItem("📚", "контекст"), s.scenes[0].items[1])
        assertEquals("text", s.scenes[1].kind)
    }

    @Test
    fun brokenScriptSalvagesCompleteScenes() {
        val reply = "{\"title\":\"x\",\"scenes\":[{\"say\":\"Один\"},{\"say\",\"битый\"},{\"say\":\"Три\"}"
        val s = Studio.parseScript(reply)!!
        assertEquals(listOf("Один", "Три"), s.scenes.map { it.say })
        assertNull(Studio.parseScript("нет json"))
    }

    @Test
    fun plainTextRoundTrip() {
        val text = "# Ролик\n\n[icons] Чем отличается инженерия?\n🔍 исследование | 📚 контекст\n\n" +
            "[step: Работа с контекстом] Второй этап.\n\nПросто текст\nв две строки.\n\n[stat: 80%] Столько.\n⏱ подготовка"
        val s = Studio.parseText(text)
        assertEquals("Ролик", s.title)
        assertEquals(4, s.scenes.size)
        assertEquals(listOf(SItem("🔍", "исследование"), SItem("📚", "контекст")), s.scenes[0].items)
        assertEquals("step", s.scenes[1].kind)
        assertEquals("Работа с контекстом", s.scenes[1].title)
        assertEquals("Просто текст в две строки.", s.scenes[2].say)
        assertEquals("80%", s.scenes[3].title)
        assertEquals(SItem("⏱", "подготовка"), s.scenes[3].items[0])
        assertEquals(s, Studio.parseText(Studio.toText(s)))
    }

    @Test
    fun jsonRoundTrip() {
        val s = SScript("t", listOf(SScene("a b", "list", "", listOf(SItem("✅", "x"))), SScene("c")))
        assertEquals(s, Studio.parseScript(Studio.toJson(s)))
    }

    @Test
    fun unknownTagStaysText() {
        val s = Studio.parseText("[foo] Слова")
        assertEquals("text", s.scenes[0].kind)
        assertEquals("[foo] Слова", s.scenes[0].say)
    }

    @Test
    fun tokensSkipPunctuation() {
        assertEquals(listOf("Привет,", "мир", "2026!"), Studio.tokens("Привет, — мир  2026!"))
        assertEquals("мир2026", Studio.norm("«Мир-2026»"))
    }

    // ---------- Edge word boundaries ----------

    @Test
    fun parsesWordBoundaries() {
        val json = "{\"Metadata\":[" +
            "{\"Type\":\"WordBoundary\",\"Data\":{\"Offset\":1000000,\"Duration\":3000000,\"text\":{\"Text\":\"Привет\",\"Length\":6}}}," +
            "{\"Type\":\"WordBoundary\",\"Data\":{\"Offset\":5000000,\"Duration\":1000000,\"text\":{\"Text\":\",\",\"Length\":1}}}," +
            "{\"Type\":\"SentenceBoundary\",\"Data\":{\"Offset\":0,\"Duration\":1,\"text\":{\"Text\":\"x\"}}}]}"
        val w = Studio.parseBoundaries(json, shiftMs = 1000)
        assertEquals(listOf(SWord("Привет", 1100, 1400)), w)
        assertTrue(Studio.parseBoundaries("garbage").isEmpty())
    }

    @Test
    fun dressRestoresPunctuation() {
        val spoken = listOf(SWord("Привет", 0, 1), SWord("мир", 1, 2), SWord("снова", 2, 3))
        val d = Studio.dress(spoken, listOf("Привет,", "мир!", "снова."))
        assertEquals(listOf("Привет,", "мир!", "снова."), d.map { it.text })
    }

    @Test
    fun configAsksForWordBoundaries() {
        assertTrue(EdgeTts.configMessage("ts", true).contains("\"wordBoundaryEnabled\":\"true\""))
        assertTrue(EdgeTts.configMessage("ts").contains("\"wordBoundaryEnabled\":\"false\""))
    }

    // ---------- timeline ----------

    @Test
    fun assignScenesProportional() {
        assertEquals(listOf(0 until 2, 2 until 5), Studio.assignScenes(5, listOf(2, 3)))
        // recogniser found more words than written: split keeps proportions and covers everything
        val r = Studio.assignScenes(10, listOf(1, 1))
        assertEquals(listOf(0 until 5, 5 until 10), r)
        assertEquals(listOf(0 until 0, 0 until 3), Studio.assignScenes(3, listOf(0, 0)))
    }

    @Test
    fun sceneTimesCoverTheVideo() {
        val w = listOf(SWord("a", 500, 900), SWord("b", 1000, 1300), SWord("c", 2000, 2400))
        val t = Studio.sceneTimes(w, listOf(0 until 2, 2 until 3), 3000)
        assertEquals(0L, t[0].startMs)
        assertEquals(1880L, t[1].startMs)
        assertEquals(1880L, t[0].endMs)
        assertEquals(3000L, t[1].endMs)
        assertEquals(1, Studio.sceneAt(t, 2000))
        assertEquals(0, Studio.sceneAt(t, 10))
    }

    @Test
    fun captionsBreakOnLimitsPausesAndSentences() {
        val w = listOf("Раз", "два", "три", "четыре", "пять", "шесть.", "Семь").mapIndexed { i, s -> SWord(s, i * 300L, i * 300L + 250) }
        val c = Studio.captions(w, w.indices, maxWords = 4)
        assertEquals(listOf(0..3, 4..5, 6..6), c)
        val gap = listOf(SWord("a", 0, 100), SWord("b", 1000, 1100))
        assertEquals(2, Studio.captions(gap, gap.indices).size)
        assertEquals(7, Studio.captions(w, w.indices, maxWords = 1).size)
        assertTrue(Studio.captions(w, IntRange.EMPTY).isEmpty())
    }

    @Test
    fun appearEasesFromZeroToOne() {
        assertEquals(0f, Studio.appear(100, 100), 0f)
        assertEquals(1f, Studio.appear(500, 100, 280), 0f)
        val mid = Studio.appear(240, 100, 280)
        assertTrue(mid > 0.5f && mid < 1f)
        assertTrue(Studio.itemDelay(1, 3, 3000) > Studio.itemDelay(0, 3, 3000))
        assertTrue(Studio.itemDelay(5, 6, 100_000) <= 150 + 5 * 450)
    }

    @Test
    fun linesWrapGreedy() {
        assertEquals(listOf(0..1, 2..2), Studio.lines(listOf(30f, 30f, 30f), 10f, 75f))
        assertEquals(listOf(0..0), Studio.lines(listOf(500f), 10f, 75f))
        assertTrue(Studio.lines(emptyList(), 1f, 1f).isEmpty())
    }

    @Test
    fun stepNumbersCountOnlySteps() {
        val s = listOf(SScene("a", "step"), SScene("b"), SScene("c", "step"))
        assertArrayEquals(intArrayOf(1, 0, 2), Studio.stepNumbers(s))
    }

    @Test
    fun countUpKeepsUnitsAndDecimals() {
        assertEquals("40%", Studio.countUp("80%", 0.5f))
        assertEquals("×3", Studio.countUp("×3", 1f))
        assertEquals("1,5 млн", Studio.countUp("3,0 млн", 0.5f))
        assertEquals("без цифр", Studio.countUp("без цифр", 0.3f))
    }

    // ---------- own voice ----------

    /** 1 s tone, 0.5 s silence, 1 s tone at 16 kHz. */
    private fun take(): ShortArray {
        val rate = 16_000
        val tone = ShortArray(rate) { (8000 * sin(2 * PI * 220 * it / rate)).toInt().toShort() }
        val noise = ShortArray(rate / 2) { ((it * 7919) % 40 - 20).toShort() }
        return noise + tone + noise + tone + noise
    }

    @Test
    fun speechSpansFindToneAndPause() {
        val s = Studio.speechSpans(take(), 16_000)
        assertEquals(2, s.size)
        assertTrue(Math.abs(s[0].startMs - 500) <= 40)
        assertTrue(Math.abs(s[0].endMs - 1500) <= 40)
        assertTrue(Math.abs(s[1].startMs - 2000) <= 40)
        assertTrue(Studio.speechSpans(ShortArray(16_000), 16_000).isEmpty())
    }

    @Test
    fun alignedWordsNeverFallIntoPauses() {
        val speech = listOf(Span(500, 1500), Span(2000, 3000))
        val w = Studio.alignWords(listOf("один", "два", "три", "четыре"), speech)
        assertEquals(4, w.size)
        assertEquals(500L, w.first().startMs)
        assertEquals(3000L, w.last().endMs)
        for (x in w) assertFalse("${x.text} starts in the pause", x.startMs in 1501..1999)
        for (i in 1 until w.size) assertTrue(w[i].startMs >= w[i - 1].startMs)
        assertTrue(Studio.alignWords(emptyList(), speech).isEmpty())
    }

    @Test
    fun alignPiecesUsesOnlySpeechInsideEachPiece() {
        val speech = listOf(Span(0, 1000), Span(5000, 6000))
        val w = Studio.alignPieces(listOf(Span(0, 1200) to "раз два", Span(4800, 6000) to "три"), speech)
        assertEquals(listOf("раз", "два", "три"), w.map { it.text })
        assertTrue(w[1].endMs <= 1000)
        assertEquals(5000L, w[2].startMs)
    }

    @Test
    fun recogLangAddsRegion() {
        assertEquals("ru-RU", Studio.recogLang("ru"))
        assertEquals("kk-KZ", Studio.recogLang("kk"))
        assertEquals("en-US", Studio.recogLang("EN"))
        assertEquals("pt-PT", Studio.recogLang("pt_PT"))
    }

    @Test
    fun groupSpansRespectsLimit() {
        val s = listOf(Span(0, 4000), Span(5000, 9000), Span(10_000, 14_000))
        assertEquals(listOf(Span(0, 9000), Span(10_000, 14_000)), Studio.groupSpans(s, 12_000))
    }

    @Test
    fun statsAndPace() {
        val st = Studio.stats(10_000, listOf(Span(0, 3000), Span(4000, 6000)), 10)
        assertEquals(5000L, st.speechMs)
        assertEquals(1, st.pauses)
        assertEquals(1000L, st.longestPauseMs)
        assertEquals(120, st.wordsPerMinute)
    }

    @Test
    fun autoScenesSplitOnPausesAndLength() {
        val w = (0 until 20).map { SWord("w$it", it * 300L, it * 300L + 200) } + SWord("после", 9000, 9200)
        val s = Studio.autoScenes(w, maxWords = 14)
        assertEquals(3, s.scenes.size)
        assertEquals("после", s.scenes.last().say)
    }

    // ---------- audio ----------

    @Test
    fun downmixResampleNormalize() {
        assertArrayEquals(shortArrayOf(15, -5), Studio.downmix(shortArrayOf(10, 20, -10, 0), 2))
        assertEquals(8, Studio.resample(ShortArray(16) { 100 }, 32_000, 16_000).size)
        val n = Studio.normalize(shortArrayOf(1000, -2000))
        assertEquals(-16000, n[1].toInt())
        assertArrayEquals(shortArrayOf(0, 0), Studio.normalize(shortArrayOf(0, 0)))
    }

    @Test
    fun wavRoundTrip() {
        val pcm = shortArrayOf(1, -2, 300, -32768, 32767)
        val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.asShortBuffer().put(pcm)
        val (back, rate) = Studio.readWav(Studio.wavHeader(pcm.size * 2, 44_100) + data.array())!!
        assertEquals(44_100, rate)
        assertArrayEquals(pcm, back)
        assertNull(Studio.readWav(ByteArray(10)))
    }

    // ---------- video ----------

    @Test
    fun yuvOfPrimaryColours() {
        val w = 2
        val h = 2
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        Studio.argbToYuv420(IntArray(4) { 0xFFFFFFFF.toInt() }, w, h, y, u, v)
        assertEquals(235, y[0].toInt() and 0xFF)
        assertEquals(128, u[0].toInt() and 0xFF)
        Studio.argbToYuv420(IntArray(4) { 0xFF000000.toInt() }, w, h, y, u, v)
        assertEquals(16, y[0].toInt() and 0xFF)
        Studio.argbToYuv420(IntArray(4) { 0xFFFF0000.toInt() }, w, h, y, u, v)
        assertTrue((v[0].toInt() and 0xFF) > 200)
    }

    @Test
    fun frameMathAndNames() {
        assertEquals(30, Studio.frameCount(1000, 30))
        assertEquals(31, Studio.frameCount(1001, 30))
        assertEquals(1, Studio.frameCount(0, 30))
        assertEquals(33_333L, Studio.frameTimeUs(1, 30))
        assertTrue(Studio.bitrate(1080, 1920, 30) >= 2_000_000)
        assertEquals("1:05", Studio.clock(65_000))
        assertEquals("Lumen_Studio_Вайб_кодинг_20261031.mp4", Studio.fileName("Вайб-кодинг!", "20261031"))
        assertEquals(144, Studio.wordsFor(60))
        assertNotNull(STUDIO_PROMPTS["studio_script"])
        assertTrue(STUDIO_PROMPTS["studio_direct"]!!.contains("{data}"))
        assertNotNull(STUDIO_PROMPTS["studio_script_video"])
    }

    // ---------- montage: video frames ----------

    @Test
    fun yuvToArgbPrimaries() {
        assertEquals(0xFFFFFFFF.toInt(), Studio.yuvToArgb(235, 128, 128))
        assertEquals(0xFF000000.toInt(), Studio.yuvToArgb(16, 128, 128))
        val red = Studio.yuvToArgb(82, 90, 240)
        assertTrue((red shr 16 and 0xFF) > 240 && (red and 0xFF) < 20)
        // round trip through the encoder formula
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        Studio.argbToYuv420(IntArray(4) { 0xFF3366CC.toInt() }, 2, 2, y, u, v)
        val back = Studio.yuvToArgb(y[0].toInt() and 0xFF, u[0].toInt() and 0xFF, v[0].toInt() and 0xFF)
        assertTrue(Math.abs((back shr 16 and 0xFF) - 0x33) <= 3)
        assertTrue(Math.abs((back and 0xFF) - 0xCC) <= 3)
    }

    @Test
    fun cropFillsFrameAndCutsEdges() {
        // landscape 1920x1080 into a 9:16 frame: the middle part of the width is used
        val (xs, ys) = Studio.cropAxes(90, 160, 1920, 1080)
        assertEquals(90, xs.size)
        assertEquals(160, ys.size)
        assertTrue(xs.first() > 600 && xs.last() < 1320)
        assertEquals(0, ys.first() / 10)
        assertTrue(ys.last() >= 1070)
        for (i in 1 until xs.size) assertTrue(xs[i] >= xs[i - 1])
        // portrait 1080x1920 into 9:16: the whole picture
        val (px, py) = Studio.cropAxes(9, 16, 1080, 1920)
        assertTrue(px.first() < 120 && px.last() > 960)
        assertTrue(py.first() < 120 && py.last() > 1800)
    }

    @Test
    fun rotationMapsDisplayToDecodedPixels() {
        // a phone portrait video: decoded 1920x1080 (landscape) with rotation 90
        assertEquals(1080 to 1920, Studio.displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, Studio.displaySize(1920, 1080, 180))
        // top-left of the picture on screen is bottom-left of the decoded frame
        assertEquals(0, Studio.rawX(0, 0, 90, 1920))
        assertEquals(1079, Studio.rawY(0, 0, 90, 1080))
        // top-right on screen (u = 1079) is the top-left of the decoded frame
        assertEquals(0, Studio.rawY(1079, 0, 90, 1080))
        assertEquals(1919, Studio.rawX(0, 0, 180, 1920))
        assertEquals(1919, Studio.rawX(0, 0, 270, 1920))
        assertEquals(5, Studio.rawY(5, 0, 270, 1080))
        assertEquals(7, Studio.rawX(7, 3, 0, 100))
        assertEquals(3, Studio.rawY(7, 3, 0, 100))
    }

    // ---------- music ----------

    @Test
    fun spansOfWordsJoinsClosePauses() {
        val w = listOf(SWord("a", 0, 300), SWord("b", 500, 800), SWord("c", 2000, 2300))
        assertEquals(listOf(Span(0, 800), Span(2000, 2300)), Studio.spansOfWords(w))
    }

    @Test
    fun duckEnvelopeLowersUnderSpeechAndGlides() {
        val env = Studio.duckEnvelope(5000, listOf(Span(2000, 3000)), duck = 0.3f)
        assertEquals(1f, env[50], 0.001f) // 0.5 s: silence
        assertEquals(0.3f, env[280], 0.001f) // 2.8 s: speech
        assertEquals(1f, env[480], 0.001f) // 4.8 s: silence again
        val mid = env[(1850 + 120) / 10]
        assertTrue(mid < 1f && mid > 0.3f) // glides, no jump
    }

    @Test
    fun mixMusicLoopsDucksFadesAndClamps() {
        val rate = 1000
        val voice = ShortArray(6000)
        val music = ShortArray(500) { 10_000 }
        val mixed = Studio.mixMusic(voice, music, rate, 1f, listOf(Span(3000, 4000)), duck = 0.2f, fadeInMs = 100, fadeOutMs = 100)
        assertEquals(voice.size, mixed.size)
        assertEquals(0, mixed[0].toInt()) // fade in starts from silence
        assertEquals(10_000, mixed[1000].toInt()) // looped music at full volume in a pause
        assertEquals(2_000, mixed[3500].toInt()) // ducked under speech
        assertTrue(mixed[5999] < 200) // faded out
        val loud = Studio.mixMusic(ShortArray(10) { 30_000 }, ShortArray(10) { 30_000 }, rate, 1f, emptyList(), fadeInMs = 1, fadeOutMs = 1)
        assertEquals(32767, loud[5].toInt())
        assertTrue(Studio.mixMusic(voice, ShortArray(0), rate, 1f, emptyList()) === voice)
    }

    @Test
    fun fitPadsOrCuts() {
        assertArrayEquals(shortArrayOf(1, 2, 0), Studio.fit(shortArrayOf(1, 2), 3))
        assertArrayEquals(shortArrayOf(1), Studio.fit(shortArrayOf(1, 2), 1))
    }

    @Test
    fun analysisSurvivesSaveAndLoad() {
        val a = Studio.Analysis(
            "mic", SScript("T", listOf(SScene("Раз два", "icons", "", listOf(SItem("🔍", "поиск"))), SScene("три"))),
            listOf(SWord("Раз", 0, 100), SWord("два", 150, 300), SWord("три", 900, 1000)),
            listOf(0 until 2, 2 until 3), "Раз два три", VoiceStats(1500, 700, 1, 600, 3)
        )
        assertEquals(a, Studio.analysisFromJson(Studio.analysisToJson(a)))
        val noRanges = a.copy(ranges = null)
        assertEquals(noRanges, Studio.analysisFromJson(Studio.analysisToJson(noRanges)))
        assertNull(Studio.analysisFromJson("{}"))
    }

    // ---------- auto-edit ----------

    @Test
    fun keepSegmentsPadsAndMergesShortGaps() {
        val speech = listOf(Span(1000, 2000), Span(2200, 3000), Span(6000, 7000))
        val keep = Studio.keepSegments(speech, 8000, padMs = 100, minGapMs = 350)
        assertEquals(listOf(Span(900, 3100), Span(5900, 7100)), keep)
        assertEquals(listOf(Span(0, 500)), Studio.keepSegments(listOf(Span(50, 450)), 500, padMs = 100))
    }

    @Test
    fun cutPcmAndSourceTimeAgree() {
        val rate = 1000
        val pcm = ShortArray(10_000) { (it / 1000).toShort() } // value = second of the take
        val keep = listOf(Span(1000, 2000), Span(5000, 6500))
        val cut = Studio.cutPcm(pcm, rate, keep)
        assertEquals(2500, cut.size)
        assertEquals(1, cut[0].toInt())
        assertEquals(5, cut[1000].toInt())
        assertEquals(1500L, Studio.sourceTime(500, keep))
        assertEquals(5200L, Studio.sourceTime(1200, keep))
        assertEquals(6500L, Studio.sourceTime(99_999, keep))
        assertEquals(777L, Studio.sourceTime(777, null))
        // the cut take's sample at output time t is the take's sample at sourceTime(t)
        for (t in listOf(0L, 999L, 1000L, 2400L)) assertEquals(pcm[Studio.sourceTime(t, keep).toInt()], cut[t.toInt()])
    }

    @Test
    fun clipsPlayInOrderAndRepeat() {
        val d = listOf(3000L, 2000L)
        assertEquals(0 to 0L, Studio.clipAt(0, d))
        assertEquals(0 to 2999L, Studio.clipAt(2999, d))
        assertEquals(1 to 0L, Studio.clipAt(3000, d))
        assertEquals(0 to 500L, Studio.clipAt(5500, d)) // second round
        assertEquals(1 to 1000L, Studio.clipAt(14_000, d))
        assertEquals(0 to 0L, Studio.clipAt(100, emptyList()))
    }

    @Test
    fun cameraZoomAlternatesAndNeverZoomsOut() {
        for (scene in 0..7) for (t in listOf(0L, 100L, 1000L, 4000L)) assertTrue(Studio.cameraZoom(scene, t, 4000) >= 1f)
        assertEquals(1f, Studio.cameraZoom(0, 0, 4000), 0.001f)
        assertTrue(Studio.cameraZoom(1, 1000, 4000) > Studio.cameraZoom(0, 1000, 4000) + 0.1f) // punch-in
        assertTrue(Studio.cameraZoom(2, 4000, 4000) > Studio.cameraZoom(2, 300, 4000)) // push-in
        assertTrue(Studio.cameraZoom(3, 0, 4000) > Studio.cameraZoom(3, 300, 4000)) // bounce settles
    }

    @Test
    fun analysisKeepsCuts() {
        val a = Studio.Analysis("video", SScript("", listOf(SScene("раз"))), listOf(SWord("раз", 0, 100)), null, "",
            VoiceStats(100, 100, 0, 0, 1), listOf(Span(500, 900), Span(1200, 3000)))
        assertEquals(a, Studio.analysisFromJson(Studio.analysisToJson(a)))
    }

    // ---------- free music catalogs ----------

    @Test
    fun parsesOpenverseAudio() {
        val json = "{\"result_count\":2,\"results\":[" +
            "{\"id\":\"a1\",\"title\":\"Calm Piano\",\"creator\":\"Ann\",\"url\":\"https://cdn.example/a1.mp3\",\"duration\":125000," +
            "\"license\":\"by\",\"license_version\":\"4.0\",\"license_url\":\"https://creativecommons.org/licenses/by/4.0/\"," +
            "\"foreign_landing_url\":\"https://ccmixter.org/files/a1\",\"source\":\"ccmixter\"}," +
            "{\"id\":\"j2\",\"title\":\"Song\",\"creator\":\"Bob\",\"url\":\"https://jamendo/j2.mp3\",\"duration\":90000," +
            "\"license\":\"by-nc\",\"license_version\":\"3.0\",\"source\":\"jamendo\"},{\"id\":\"bad\",\"url\":\"\"}]}"
        val hits = StudioMusicCatalog.parseOpenverse(json)
        assertEquals(2, hits.size)
        val a = hits[0]
        assertEquals(125_000L, a.durationMs)
        assertEquals("openverse/ccmixter", a.source)
        assertTrue(StudioMusicCatalog.commercialOk(a))
        assertTrue(StudioMusicCatalog.needsCredit(a))
        assertEquals("CC BY 4.0", StudioMusicCatalog.licenseLabel(a))
        assertEquals("Music: \"Calm Piano\" by Ann (CC BY 4.0) https://ccmixter.org/files/a1", StudioMusicCatalog.credit(a))
        assertFalse(StudioMusicCatalog.commercialOk(hits[1])) // NC licence and Jamendo
        assertTrue(StudioMusicCatalog.parseOpenverse("oops").isEmpty())
    }

    @Test
    fun parsesFreesoundAndLicences() {
        val json = "{\"count\":1,\"results\":[{\"id\":7,\"name\":\"Loop\",\"duration\":61.5,\"username\":\"zed\"," +
            "\"license\":\"http://creativecommons.org/publicdomain/zero/1.0/\",\"url\":\"https://freesound.org/people/zed/sounds/7/\"," +
            "\"previews\":{\"preview-hq-mp3\":\"https://cdn.freesound.org/7-hq.mp3\",\"preview-lq-mp3\":\"https://cdn.freesound.org/7-lq.mp3\"}}]}"
        val h = StudioMusicCatalog.parseFreesound(json).single()
        assertEquals("https://cdn.freesound.org/7-hq.mp3", h.url)
        assertEquals(61_500L, h.durationMs)
        assertEquals("cc0", h.license)
        assertFalse(StudioMusicCatalog.needsCredit(h))
        assertTrue(StudioMusicCatalog.commercialOk(h))
        assertEquals("by-nc", StudioMusicCatalog.licenseCode("https://creativecommons.org/licenses/by-nc/4.0/"))
        assertEquals("by-nc", StudioMusicCatalog.licenseCode("Attribution Noncommercial"))
        assertEquals("by", StudioMusicCatalog.licenseCode("Attribution"))
        assertEquals("cc0", StudioMusicCatalog.licenseCode("Creative Commons 0"))
        assertTrue(StudioMusicCatalog.openverseUrl("calm piano", true).contains("q=calm+piano"))
        assertTrue(StudioMusicCatalog.openverseUrl("x", true).contains("license_type=commercial"))
        assertFalse(StudioMusicCatalog.openverseUrl("x", false).contains("license_type"))
        assertTrue(StudioMusicCatalog.freesoundUrl("lofi", "KEY").contains("token=KEY"))
    }

    @Test
    fun emojiDetection() {
        assertTrue(Studio.startsWithEmoji("🔍 поиск"))
        assertTrue(Studio.startsWithEmoji("⚙️ процесс"))
        assertFalse(Studio.startsWithEmoji("поиск"))
        assertFalse(Studio.startsWithEmoji("1 шаг"))
        assertEquals(SItem("", "поиск"), Studio.itemOf(" поиск "))
    }

    @Test
    fun cleanTranscriptDropsMarks() {
        assertEquals("Привет всем. Сегодня про код", Studio.cleanTranscript("[00:01] Привет всем.\n**Спикер 1:** Сегодня (музыка) про «код»"))
        assertEquals("Hello world", Studio.cleanTranscript("0:12 - Hello world"))
        assertNotNull(STUDIO_PROMPTS["studio_stt"])
    }
}

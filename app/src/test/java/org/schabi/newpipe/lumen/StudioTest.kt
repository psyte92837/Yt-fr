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
        assertTrue(STUDIO_PROMPTS["studio_scenes"]!!.contains("{data}"))
    }

    @Test
    fun emojiDetection() {
        assertTrue(Studio.startsWithEmoji("🔍 поиск"))
        assertTrue(Studio.startsWithEmoji("⚙️ процесс"))
        assertFalse(Studio.startsWithEmoji("поиск"))
        assertFalse(Studio.startsWithEmoji("1 шаг"))
        assertEquals(SItem("", "поиск"), Studio.itemOf(" поиск "))
    }
}

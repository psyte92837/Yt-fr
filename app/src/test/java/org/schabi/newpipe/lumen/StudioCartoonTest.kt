/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioCartoonTest {

    private val cast = listOf(SChar("Лиса", "fox", "f"), SChar("Робот", "robot", "m"))

    @Test
    fun parsesSpeakerInText() {
        val sc = Studio.parseText("# Небо\n\n@Лиса(surprise): [big] Почему небо синее?\n💡 загадка\n\n@Робот: Из-за рассеяния света.\n\nПросто текст рассказчика.")
        assertEquals(3, sc.scenes.size)
        assertEquals("Лиса", sc.scenes[0].who)
        assertEquals("surprise", sc.scenes[0].mood)
        assertEquals("big", sc.scenes[0].kind)
        assertEquals("Почему небо синее?", sc.scenes[0].say)
        assertEquals("Робот", sc.scenes[1].who)
        assertEquals("", sc.scenes[1].mood)
        assertEquals("", sc.scenes[2].who)
        // round trip through the editor text and JSON
        assertEquals(sc, Studio.parseText(Studio.toText(sc)))
        assertEquals(sc.scenes.map { it.who to it.mood }, Studio.parseScript(Studio.toJson(sc))!!.scenes.map { it.who to it.mood })
    }

    @Test
    fun unknownMoodIsDropped() {
        val sc = Studio.parseText("@Лиса(dance): Привет")
        assertEquals("Лиса", sc.scenes[0].who)
        assertEquals("", sc.scenes[0].mood)
        assertEquals("calm", StudioToon.mood("dance"))
    }

    @Test
    fun findsCharacters() {
        assertEquals(0, StudioToon.find(cast, "лиса"))
        assertEquals(1, StudioToon.find(cast, "@Робот"))
        assertEquals(1, StudioToon.find(cast, "robot"))
        assertEquals(-1, StudioToon.find(cast, ""))
        assertEquals(-1, StudioToon.find(cast, "Сова"))
        assertEquals("Доктор_Кот", StudioToon.cleanName(" Доктор Кот! "))
    }

    @Test
    fun castJsonRoundTrip() {
        val c = cast + SChar("Сова", "owl", "ru-RU-SvetlanaNeural", 0x112233)
        assertEquals(c, StudioToon.parseCast(StudioToon.castJson(c)))
        assertTrue(StudioToon.parseCast("not json").isEmpty())
        assertEquals("blob", StudioToon.parseCast("""[{"name":"X","look":"dragon"}]""")[0].look)
        assertEquals(StudioToon.MAX_CAST, StudioToon.parseCast(StudioToon.castJson(c + SChar("Кот", "cat") + SChar("Пёс", "dog"))).size)
    }

    @Test
    fun picksDifferentVoices() {
        val voices = listOf(
            TtsVoice("", "ru-RU-SvetlanaNeural", "ru-RU", "Female"),
            TtsVoice("", "ru-RU-DariyaNeural", "ru-RU", "Female"),
            TtsVoice("", "ru-RU-DmitryNeural", "ru-RU", "Male"),
            TtsVoice("", "en-US-AriaNeural", "en-US", "Female")
        )
        val three = listOf(SChar("A", "fox"), SChar("B", "cat"), SChar("C", "bear"))
        assertEquals(listOf("ru-RU-SvetlanaNeural", "ru-RU-DariyaNeural", "ru-RU-DmitryNeural"), StudioToon.pickVoices(three, voices, "ru", "fb"))
        // a chosen voice wins; with no voices of the language the fallback is used
        assertEquals("ru-RU-DmitryNeural", StudioToon.pickVoices(listOf(SChar("A", "fox", "ru-RU-DmitryNeural")), voices, "ru", "fb")[0])
        assertEquals(listOf("fb"), StudioToon.pickVoices(listOf(SChar("A", "fox")), voices, "de", "fb"))
        // more characters than voices of a gender: voices repeat (pitch tells them apart)
        val v = StudioToon.pickVoices(listOf(SChar("A", "bear"), SChar("B", "robot")), voices, "ru", "fb")
        assertEquals(listOf("ru-RU-DmitryNeural", "ru-RU-DmitryNeural"), v)
        assertNotEquals(StudioToon.pitch("bear"), StudioToon.pitch("robot"))
    }

    @Test
    fun mouthFollowsSyllables() {
        val ws = listOf(SWord("молоко", 0, 600), SWord("да", 650, 800), SWord("нет", 2000, 2300))
        val r = ws.indices
        assertEquals(0f, StudioToon.mouth(ws, r, 1200), 0f) // pause
        assertTrue(StudioToon.mouth(ws, r, 100) > 0.9f) // middle of the first syllable "мо"
        assertTrue(StudioToon.mouth(ws, r, 200) < 0.3f) // between syllables
        assertEquals(0.15f, StudioToon.mouth(ws, r, 620), 0.001f) // short gap between words
        assertEquals(0f, StudioToon.mouth(ws, 2..2, 700), 0f) // the word is outside the scene
        assertEquals(3, StudioToon.syllables("молоко"))
        assertEquals(1, StudioToon.syllables("hmm"))
    }

    @Test
    fun blinksSometimes() {
        val closed = (0L until 10_000L step 10).count { StudioToon.blink(it, 5) > 0.5f }
        assertTrue(closed in 2..40)
        assertTrue((0L until 10_000L step 10).all { StudioToon.blink(it, 5) in 0f..1f })
    }

    @Test
    fun everyLookHasItsOwnFace() {
        assertEquals(16, StudioToon.LOOKS.size)
        assertEquals(StudioToon.LOOKS.size, StudioToon.LOOKS.map { StudioToon.emoji(it) }.distinct().size)
        assertEquals(StudioToon.LOOKS.size, StudioToon.LOOKS.map { StudioToon.defaultColor(it) }.distinct().size + 1) // kid and girl share the skin
        assertEquals("girl", StudioToon.parseCast("""[{"name":"Маша","look":"girl"}]""")[0].look)
    }

    @Test
    fun slotsFitTheFrame() {
        for (n in 1..StudioToon.MAX_CAST) for (wide in listOf(true, false)) {
            val s = StudioToon.slots(n, wide)
            assertEquals(n, s.size)
            assertTrue(s.all { it in 0f..1f })
            assertEquals(s.sorted(), s)
        }
    }

    @Test
    fun restoresSpeakersAfterDirector() {
        val words = "а б в г д е".split(' ').mapIndexed { i, w -> SWord(w, i * 100L, i * 100L + 80) }
        val old = listOf(SScene("а б в", who = "Лиса", mood = "happy"), SScene("г д е", who = "Робот"))
        val roles = StudioToon.wordRoles(old, listOf(0..2, 3..5), words.size, cast)
        assertEquals(0 to "happy", roles[0])
        assertEquals(1 to "calm", roles[5])
        // the director merged everything into one scene and then split 0..1 / 2..5
        val merged = listOf(SScene("а б", kind = "big", cues = listOf(SCue("zoom", 0))), SScene("в г д е", kind = "list", cues = listOf(SCue("pop", 1))))
        val (ss, rr) = StudioToon.restore(merged, listOf(0..1, 2..5), roles, cast, words)
        assertEquals(listOf(0..1, 2..2, 3..5), rr)
        assertEquals(listOf("Лиса", "Лиса", "Робот"), ss.map { it.who })
        assertEquals(listOf("happy", "happy", "calm"), ss.map { it.mood })
        assertEquals("а б", ss[0].say)
        assertEquals("в", ss[1].say)
        assertEquals("г д е", ss[2].say)
        assertEquals(1, ss[1].cues.size)
        assertTrue(ss[2].cues.isEmpty())
        assertEquals("list", ss[2].kind)
    }
}

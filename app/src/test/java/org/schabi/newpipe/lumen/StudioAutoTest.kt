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

class StudioAutoTest {
    /** Words 400 ms long with 100 ms gaps; "|" in the text makes a 900 ms pause before the next word. */
    private fun words(text: String): List<SWord> {
        val out = mutableListOf<SWord>()
        var t = 0L
        for (raw in text.split(' ').filter { it.isNotBlank() }) {
            if (raw == "|") {
                t += 800
                continue
            }
            out += SWord(raw, t, t + 400)
            t += 500
        }
        return out
    }

    @Test
    fun approaches() {
        assertEquals(StudioApproach.RULES, StudioApproach.of("rules"))
        assertEquals(StudioApproach.FULL, StudioApproach.of("?"))
        assertTrue(StudioApproach.HYBRID.usesAi)
        assertTrue(!StudioApproach.RHYTHM.usesAi)
    }

    @Test
    fun splitsByPausesSentencesAndLength() {
        val w = words("Привет всем друзья мои. Сегодня важная тема | без паузы " + (1..20).joinToString(" ") { "слово$it" })
        val r = StudioWords.split(w)
        assertEquals(0..3, r[0])
        assertEquals(4..6, r[1]) // pause after "тема"
        assertTrue(r.all { it.count() <= 14 })
        assertEquals(w.lastIndex, r.last().last)
        // contiguous
        assertTrue(r.zipWithNext().all { (a, b) -> b.first == a.last + 1 })
    }

    @Test
    fun emojiByMeaning() {
        assertEquals("💰", StudioWords.emojiFor("Деньги"))
        assertEquals("📈", StudioWords.emojiFor("ростом"))
        assertEquals("⏱", StudioWords.emojiFor("time"))
        assertNull(StudioWords.emojiFor("и"))
        // short stems match only whole short words: "дн" is not "днище"... but "дни" is
        assertEquals("📅", StudioWords.emojiFor("дни"))
    }

    @Test
    fun rulesPickVisuals() {
        val w = words("Почти 80% людей ошибаются. | Во-первых проверьте план. | Это миф а не правда жизни. | Нам нужны деньги время и идеи.")
        val d = StudioRules.direct(w)
        val kinds = d.scenes.map { it.kind }
        assertEquals("stat", kinds[0])
        assertEquals("80%", d.scenes[0].title)
        assertEquals("step", kinds[1])
        assertEquals("проверьте план", d.scenes[1].title)
        assertEquals("compare", kinds[2])
        assertEquals("icons", kinds[3])
        assertTrue(d.scenes[3].items.size >= 2)
        // hook: no transition, zoom and whoosh
        assertEquals("none", d.scenes[0].transition)
        assertTrue(d.scenes[0].cues.any { it.type == "zoom" })
        assertEquals(0, d.scenes.first().from)
        assertEquals(w.lastIndex, d.scenes.last().to)
    }

    @Test
    fun rulesListFromCommas() {
        val w = words("Берём муку, сахар, яйца, молоко.")
        assertEquals("list", StudioRules.direct(w).scenes[0].kind)
    }

    @Test
    fun rhythmZoomsOnLoudWords() {
        val w = words("раз два три четыре | пять шесть семь восемь")
        val loud = floatArrayOf(0.1f, 0.2f, 1f, 0.3f, 0.2f, 0.9f, 0.1f, 0.1f)
        val d = StudioRhythm.direct(w, loud)
        assertEquals(2, d.scenes.size)
        assertTrue(d.scenes[0].cues.any { it.type == "zoom" && it.at == 2 })
        assertTrue(d.scenes[0].cues.any { it.type == "sfx" && it.text == "boom" })
        assertTrue(d.scenes[1].cues.any { it.type == "zoom" && it.at == 5 })
        assertEquals("slide", d.scenes[1].transition) // pause of 900 ms
    }

    @Test
    fun loudnessOnsetsAndPulse() {
        val w = listOf(SWord("a", 0, 40), SWord("b", 40, 80))
        val rms = floatArrayOf(0.1f, 0.2f, 0.8f, 0.4f, 0f)
        val l = StudioRhythm.wordLoudness(w, rms)
        assertEquals(0.25f, l[0], 0.0001f)
        assertEquals(1f, l[1], 0.0001f)
        // silence, then a click every 500 ms
        val rate = 8000
        val pcm = ShortArray(rate * 3) { i -> if ((i % 4000) < 80) 20000 else 50 }
        val on = StudioRhythm.onsets(pcm, rate)
        assertTrue(on.size >= 4)
        assertTrue(on.zipWithNext().all { (a, b) -> b - a >= 280 })
        val p = StudioRhythm.pulse(floatArrayOf(0f, 1f, 1f, 0f, 0f))
        assertTrue(p[1] > 0.5f && p[3] < p[2] && p[4] > 0f)
    }

    @Test
    fun compactTranscriptMarksClauses() {
        val w = words("Привет мир, как дела. Всё | хорошо")
        assertEquals(listOf(0, 2, 4, 5), StudioCompact.marks(w))
        assertEquals("[0] Привет мир, [2] как дела. [4] Всё [5] хорошо", StudioCompact.transcript(w))
    }

    @Test
    fun compactParse() {
        val w = words("Почти 80% людей ошибаются, но есть способ. Вот три шага: план, дело, проверка.")
        val reply = "{\"t\":\"Ошибки\",\"s\":[[0,\"stat\",\"80%\",\"😬 людей\",\"80%\",\"z@80%;p@ошибаются:ВАЖНО:⚠️;t:none\"]," +
            "[7,\"flow\",\"\",\"🗺 план|⚙️ дело|✅ проверка\",\"шага\",\"s@шага:click;t:whip;z@нет\"],[99,\"x\"]]}"
        val d = StudioCompact.parse(reply, w)!!
        assertEquals("Ошибки", d.title)
        assertEquals(2, d.scenes.size)
        assertEquals(0..6, d.scenes[0].from..d.scenes[0].to)
        assertEquals(7..w.lastIndex, d.scenes[1].from..d.scenes[1].to)
        assertEquals(listOf(SCue("zoom", 1), SCue("pop", 3, "ВАЖНО", "⚠️")), d.scenes[0].cues)
        assertEquals("none", d.scenes[0].transition)
        assertEquals(3, d.scenes[1].items.size)
        assertEquals("whip", d.scenes[1].transition)
        // unknown word → the start of the scene
        assertTrue(d.scenes[1].cues.contains(SCue("zoom", 7)))
        assertTrue(d.scenes[1].cues.contains(SCue("sfx", 9, "click")))
        assertNull(StudioCompact.parse("{}", w))
        // a first scene that does not start at 0 is stretched to it
        assertEquals(0, StudioCompact.parse("{\"s\":[[3,\"text\"]]}", w)!!.scenes[0].from)
    }

    @Test
    fun hybridPatchesOnlyNamedScenes() {
        val w = words("раз два три. | четыре пять шесть. | семь восемь девять.")
        val draft = StudioRules.direct(w)
        assertEquals(3, draft.scenes.size)
        val listing = StudioCompact.listing(draft, w)
        assertTrue(listing.startsWith("#0 "))
        val p = StudioCompact.patch(draft, "{\"s\":[[1,\"big\",\"\",\"🔥 огонь\",\"пять\",\"\"]]}", w)!!
        assertEquals("big", p.scenes[1].kind)
        assertEquals(listOf("пять"), p.scenes[1].emph)
        // the other scenes and the cues of the draft stay
        assertEquals(draft.scenes[0], p.scenes[0])
        assertEquals(draft.scenes[1].cues, p.scenes[1].cues)
        assertNull(StudioCompact.patch(draft, "{\"s\":[[9,\"big\"]]}", w))
    }

    @Test
    fun liveEmojiCodes() {
        assertEquals("1f525", StudioLiveEmoji.code("🔥"))
        assertEquals("26a0", StudioLiveEmoji.code("⚠️"))
        assertEquals("1f468_200d_1f4bb", StudioLiveEmoji.code("👨‍💻"))
        assertEquals("https://fonts.gstatic.com/s/e/notoemoji/latest/1f525/lottie.json", StudioLiveEmoji.url("🔥"))
        val s = SScript("", listOf(SScene("x", "icons", items = listOf(SItem("🔥", "a"), SItem("", "b")), cues = listOf(SCue("pop", 0, "X", "⚠️")))))
        assertEquals(setOf("🔥", "⚠️"), StudioLiveEmoji.used(s))
        assertNotNull(StudioLiveEmoji.used(SScript("", emptyList())))
    }
}

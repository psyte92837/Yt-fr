/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMixAssistTest {

    @Test
    fun beatsFromLinesAndSentences() {
        val b = StudioMixAssist.beats("1. Утро в горах\n- Кофе на костре\n\n• Вид с вершины")
        assertEquals(listOf("Утро в горах", "Кофе на костре", "Вид с вершины"), b.map { it.text })
        assertEquals(listOf(1, 2, 3), b.map { it.n })
        val one = StudioMixAssist.beats("Город просыпается. Люди едут на работу! Вечером огни.")
        assertEquals(3, one.size)
        assertTrue(StudioMixAssist.beats("  \n ").isEmpty())
    }

    @Test
    fun beatsFromVoiceKeepTimes() {
        val w = listOf(SWord("Утро", 0, 400), SWord("в", 450, 500), SWord("горах.", 550, 1000),
            SWord("Кофе", 2000, 2400), SWord("готов", 2450, 3000))
        val b = StudioMixAssist.beatsFromWords(w)
        assertEquals(2, b.size)
        assertEquals("Утро в горах.", b[0].text)
        assertEquals(0L, b[0].startMs)
        assertEquals(1000L, b[0].endMs)
        assertEquals(1000L, b[1].durMs)
        assertTrue(StudioMixAssist.beatsBlock(b).startsWith("1. Утро в горах. (0:00–0:01"))
    }

    @Test
    fun parsesAnswerInDifferentShapes() {
        val a = """```json
            {"moments":[{"beat":1,"from":"1:23","to":"1:27","what":"туман","score":0.9},
             {"beat":"2","start":95,"end":99.5,"note":"костёр"},
             {"beat":3,"time":"2:00-2:04","what":"вершина"},
             {"beat":1,"what":"без времени"}]}
            ```"""
        val m = StudioMixAssist.parse(a, "u", "T")
        assertEquals(3, m.size)
        assertEquals(83_000L, m[0].fromMs)
        assertEquals(87_000L, m[0].toMs)
        assertEquals(2, m[1].beat)
        assertEquals(99_500L, m[1].toMs)
        assertEquals("костёр", m[1].what)
        assertEquals(120_000L, m[2].fromMs)
        assertEquals(1, StudioMixAssist.parse("""[{"beat":1,"from":"0:05","to":"0:09"}]""", "u", "T").size)
        assertTrue(StudioMixAssist.parse("нет", "u", "T").isEmpty())
    }

    @Test
    fun cleanFixesLengthsAndDropsOverlaps() {
        val m = listOf(
            MixMoment("u", "T", 1, 10_000, 10_500, "short", 0.4),
            MixMoment("u", "T", 1, 10_200, 12_000, "overlap, better", 0.9),
            MixMoment("u", "T", 2, 200_000, 205_000, "past the end"),
            MixMoment("u", "T", 9, 20_000, 90_000, "too long, no beat"),
            MixMoment("u", "T", 2, 58_500, 61_000, "at the end")
        )
        val c = StudioMixAssist.clean(m, 60_000, 3)
        assertEquals(listOf("overlap, better", "at the end", "too long, no beat"), c.map { it.what })
        val end = c[1]
        assertEquals(60_000L, end.toMs)
        assertTrue(end.ms >= StudioMixAssist.MIN_MOMENT_MS)
        assertEquals(0, c[2].beat)
        assertEquals(StudioCuts.MAX_CLIP_MS, c[2].ms)
    }

    @Test
    fun planFollowsBeatsAndVoice() {
        val beats = listOf(MixBeat(1, "a", 0, 4000), MixBeat(2, "b", 4000, 6000), MixBeat(3, "c"))
        val m = listOf(
            MixMoment("v1", "", 1, 0, 5000, "", 0.9),
            MixMoment("v2", "", 1, 0, 1500, "", 0.5),
            MixMoment("v1", "", 1, 9000, 12000, "", 0.1),
            MixMoment("v1", "", 2, 20000, 30000, "", 0.8),
            MixMoment("v2", "", 2, 40000, 45000, "", 0.7),
            MixMoment("v3", "", 3, 0, 9000, "", 0.6, picked = false),
            MixMoment("v3", "", 0, 100, 3000, "", 0.6)
        )
        val p = StudioMixAssist.plan(beats, m, perBeat = 2)
        // beat 1: the two best share 4 s (v2 has only 1.5 s); beat 2 starts with v1 because v2 was just shown
        assertEquals(listOf("v1", "v2", "v1", "v2", "v3"), p.map { it.moment.url })
        assertEquals(listOf(2000L, 1500L, 1000L, 1000L, 2900L), p.map { it.lenMs })
        assertEquals(1, StudioMixAssist.plan(beats, m, perBeat = 1).count { it.moment.beat == 1 })
        assertEquals(listOf(3), StudioMixAssist.uncovered(beats, m).map { it.n })
    }

    @Test
    fun clipsAndStoryboardFromPlan() {
        val old = listOf(SClip("c1", "v1", 0, 5000))
        val steps = listOf(
            MixStep(MixMoment("v1", "T1", 1, 0, 5000, "a"), 2000),
            MixStep(MixMoment("v2", "T2", 2, 3000, 6000, "b"), 3000),
            MixStep(MixMoment("v1", "T1", 3, 0, 5000, "a"), 1000)
        )
        val (clips, order) = StudioMixAssist.toClips(old, steps)
        assertEquals(2, clips.size)
        assertEquals("b", clips[1].note)
        assertEquals(listOf("c1" to 2000L, "c2" to 3000L, "c1" to 1000L), order)
        val sb = StudioMixAssist.storyboard(order, mapOf("c1" to 4800L, "c2" to 2500L))
        assertEquals(listOf(2000L, 2500L, 1000L), sb.map { it.lenMs })
        assertEquals(3, sb.map { it.key }.toSet().size)
        assertTrue(StudioMixAssist.storyboard(order, mapOf("c2" to 2500L)).size == 1)
    }

    @Test
    fun stateRoundTrip() {
        val s = StudioMixAssist.State("план", "крупно", true, 3, listOf(MixMoment("u", "T", 2, 1000, 4000, "w", 0.7, false)),
            "k", listOf("u"), listOf("c1" to 2000L))
        assertEquals(s, StudioMixAssist.fromJson(StudioMixAssist.toJson(s)))
        assertEquals(StudioMixAssist.State(), StudioMixAssist.fromJson("oops"))
    }

    @Test
    fun parsesBatchAnswers() {
        val urls = listOf("u1", "u2", "u3")
        // the schema answer: rows of numbers, seconds
        val a = StudioMixAssist.parseBatch("[[1,2,34,39,1],[3,1,5,9,0],[7,1,1,2,1]]", urls, mapOf("u1" to "One"))
        assertEquals(2, a.size)
        assertEquals("u1", a[0].url)
        assertEquals("One", a[0].title)
        assertEquals(2, a[0].beat)
        assertEquals(34_000L, a[0].fromMs)
        assertEquals(39_000L, a[0].toMs)
        assertEquals(StudioMixAssist.SIMILAR, a[1].score, 0.0)
        // without a schema: numbers as strings, an extra bracket at the end (seen live)
        val b = StudioMixAssist.parseBatch("[[\"v002\",1,10,14,1]]}", urls, emptyMap())
        assertEquals("u2", b.single().url)
        // objects of the older prompt
        val c = StudioMixAssist.parseBatch("""[{"video":3,"beat":1,"from":"0:05","to":"0:09","match":"похоже"}]""", urls, emptyMap())
        assertEquals("u3", c.single().url)
        assertEquals(StudioMixAssist.SIMILAR, c.single().score, 0.0)
        assertTrue(StudioMixAssist.parseBatch("no json", urls, emptyMap()).isEmpty())
        assertEquals(listOf(listOf("a", "b"), listOf("c")), StudioMixAssist.batches(listOf("a", "b", "b", "c"), 2))
        assertEquals("1=a, b; 2=c d", StudioMixAssist.needs(listOf(MixBeat(1, "a; b"), MixBeat(2, "c\nd"))))
    }

    @Test
    fun boundsFollowTheSettings() {
        val beats = listOf(MixBeat(1, "a", 0, 3_000), MixBeat(2, "b"))
        val m = listOf(
            MixMoment("u", "", 1, 10_000, 20_000, "", StudioMixAssist.EXACT),
            MixMoment("u", "", 2, 40_000, 40_500, "", StudioMixAssist.SIMILAR),
            MixMoment("u", "", 2, 50_000, 54_000, "", StudioMixAssist.EXACT)
        )
        val dur = mapOf("u" to 60_000L)
        // default: fitted to the phrase (3 s around the middle), short ones made longer
        val fit = StudioMixAssist.bound(m, StudioMixAssist.Bounds(), dur, beats)
        assertEquals(13_500L to 16_500L, fit.first { it.beat == 1 }.let { it.fromMs to it.toMs })
        assertTrue(fit.all { it.ms >= StudioMixAssist.MIN_MOMENT_MS })
        // as the AI gave: unchanged, too short dropped
        val raw = StudioMixAssist.bound(m, StudioMixAssist.Bounds(fit = false), dur, beats)
        assertEquals(listOf(10_000L to 20_000L, 50_000L to 54_000L), raw.map { it.fromMs to it.toMs })
        // similar only when a point has no exact one
        val fb = StudioMixAssist.bound(m, StudioMixAssist.Bounds(similar = StudioMixAssist.Similar.FALLBACK, toPhrase = false), dur, beats)
        assertTrue(fb.none { it.score < StudioMixAssist.EXACT })
        // margin, not past the end of the video
        val pad = StudioMixAssist.bound(listOf(MixMoment("u", "", 2, 57_000, 59_800, "")), StudioMixAssist.Bounds(padMs = 500), dur, beats)
        assertEquals(60_000L, pad.single().toMs)
    }

    @Test
    fun thinkingConfigByModel() {
        assertEquals("minimal", MediaOpts.thinking("gemini-3.5-flash-lite")!!.getString("thinkingLevel"))
        assertEquals(0, MediaOpts.thinking("gemini-2.5-flash")!!.getInt("thinkingBudget"))
        assertNull(MediaOpts.thinking("gemini-2.5-pro"))
        assertNull(MediaOpts.thinking("gpt-4o"))
        assertEquals(listOf("a", "b"), MediaOpts.links(" a\n\nb "))
        assertTrue(MediaOpts.links(null).isEmpty())
    }

    @Test
    fun findsVideosItself() {
        val beats = listOf(MixBeat(1, "Утро в горах, туман над озером"), MixBeat(2, "Человек варит кофе"))
        val q = StudioMixAssist.parseQueries("""[[1,"горы утро туман"]]""", beats)
        assertEquals("горы утро туман", q[1])
        assertEquals("человек варит кофе", q[2])
        assertEquals("утро горах туман над озером", StudioMixAssist.fallbackQuery("Утро в горах, туман над озером"))
        assertEquals("горы", StudioMixAssist.parseQueries("""{"1":"горы"}""", beats)[1])
        val hits = mapOf(
            1 to listOf("a" to 60_000L, "b" to 3_600_000L, "c" to 0L, "d" to 5_000L, "e" to 90_000L),
            2 to listOf("a" to 60_000L, "f" to 120_000L)
        )
        // too long, too short and already given are skipped; a link is taken once
        assertEquals(listOf("c", "e", "f"), StudioMixAssist.pickVideos(hits, 2, 20 * 60_000L, setOf("a")))
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
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
}

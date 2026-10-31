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
import java.io.ByteArrayOutputStream

class StudioCutsTest {

    // ---------- time ----------

    @Test
    fun parsesTimes() {
        assertEquals(83_000L, StudioCuts.parseTime("83"))
        assertEquals(83_500L, StudioCuts.parseTime("83,5"))
        assertEquals(83_000L, StudioCuts.parseTime("1:23"))
        assertEquals(3_723_000L, StudioCuts.parseTime("1:02:03"))
        assertEquals(83_000L, StudioCuts.parseTime("1m23s"))
        assertEquals(7_200_000L, StudioCuts.parseTime("2h"))
        assertEquals(1_500L, StudioCuts.parseTime("0:01.5"))
        assertNull(StudioCuts.parseTime(""))
        assertNull(StudioCuts.parseTime("abc"))
        assertNull(StudioCuts.parseTime("1:75"))
        assertNull(StudioCuts.parseTime("1::2"))
    }

    @Test
    fun stampsAndLinks() {
        assertEquals("1:05.5", StudioCuts.stamp(65_500))
        assertEquals("0:07", StudioCuts.stamp(7_000))
        assertEquals("1:02:05", StudioCuts.stamp(3_725_000))
        assertEquals(83_000L, StudioCuts.startOf("https://youtu.be/abc?t=83"))
        assertEquals(83_000L, StudioCuts.startOf("https://www.youtube.com/watch?v=abc&t=1m23s"))
        assertEquals(10_000L, StudioCuts.startOf("https://www.youtube.com/watch?v=abc#t=10s"))
        assertNull(StudioCuts.startOf("https://www.youtube.com/watch?v=abc"))
        // the round trip through the edit dialog keeps the time
        assertEquals(65_500L, StudioCuts.parseTime(StudioCuts.stamp(65_500)))
    }

    @Test
    fun spansAreValid() {
        assertEquals(10_000L to 15_000L, StudioCuts.span(10_000, null))
        assertEquals(10_000L to 15_000L, StudioCuts.span(10_000, 9_000))
        assertEquals(10_000L to 40_000L, StudioCuts.span(10_000, 90_000))
        assertEquals(0L to 3_000L, StudioCuts.span(-5, 3_000))
    }

    @Test
    fun idsAndJson() {
        val a = SClip("c1", "https://youtu.be/x", 1000, 4000, "кот в шоке", "Title", "c1.mp4", 700, 3000)
        val b = SClip("c7", "https://youtu.be/y", 0, 5000)
        assertEquals("c8", StudioCuts.newId(listOf(a, b)))
        assertEquals("c1", StudioCuts.newId(emptyList()))
        assertEquals(listOf(a, b), StudioCuts.fromJson(StudioCuts.toJson(listOf(a, b))))
        assertTrue(StudioCuts.fromJson("not json").isEmpty())
        assertEquals(3000L, a.ms)
    }

    // ---------- DASH index ----------

    private fun sidx(version: Int, timescale: Long, earliest: Long, firstOffset: Long, refs: List<Pair<Long, Long>>): ByteArray {
        val body = ByteArrayOutputStream()
        fun u32(v: Long) = repeat(4) { body.write(((v shr (24 - it * 8)) and 0xFF).toInt()) }
        fun u64(v: Long) {
            u32(v ushr 32)
            u32(v and 0xFFFFFFFFL)
        }
        u32(1) // reference_ID
        u32(timescale)
        if (version == 0) {
            u32(earliest)
            u32(firstOffset)
        } else {
            u64(earliest)
            u64(firstOffset)
        }
        body.write(0)
        body.write(0)
        body.write(0)
        body.write(refs.size)
        refs.forEach { (size, dur) ->
            u32(size)
            u32(dur)
            u32(0x90000000)
        }
        val inner = body.toByteArray()
        val out = ByteArrayOutputStream()
        val total = 8 + 4 + inner.size
        listOf(total shr 24, total shr 16, total shr 8, total).forEach { out.write(it and 0xFF) }
        out.write("sidx".toByteArray())
        out.write(version)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(inner)
        return out.toByteArray()
    }

    @Test
    fun readsSidx() {
        val box = sidx(0, 1000, 0, 0, listOf(1000L to 5000L, 2000L to 5000L, 1500L to 4000L))
        val segs = StudioCuts.parseSidx(box, 700)
        assertNotNull(segs)
        segs!!
        assertEquals(3, segs.size)
        // the first fragment starts right after the sidx box
        assertEquals(700L + box.size, segs[0].offset)
        assertEquals(segs[0].offset + 1000, segs[1].offset)
        assertEquals(5_000_000L, segs[1].startUs)
        assertEquals(4_000_000L, segs[2].durUs)
    }

    @Test
    fun readsSidxVersion1WithOffset() {
        val box = sidx(1, 90_000, 90_000, 16, listOf(500L to 180_000L))
        val segs = StudioCuts.parseSidx(box, 0)!!
        assertEquals(box.size + 16L, segs[0].offset)
        assertEquals(1_000_000L, segs[0].startUs)
        assertEquals(2_000_000L, segs[0].durUs)
        assertNull(StudioCuts.parseSidx(ByteArray(10), 0))
    }

    @Test
    fun picksFragments() {
        val segs = (0 until 10).map { DashSeg(it * 100L, 100, it * 5_000_000L, 5_000_000) }
        val p = StudioCuts.pickSegs(segs, 12_000_000, 17_000_000)
        assertEquals(listOf(2, 3), p.map { (it.startUs / 5_000_000).toInt() })
        assertEquals(1, StudioCuts.pickSegs(segs, 10_000_000, 15_000_000).size)
        assertTrue(StudioCuts.pickSegs(segs, 60_000_000, 65_000_000).isEmpty())
    }

    @Test
    fun shiftsZeroBasedFragments() {
        assertEquals(60_000_000L, StudioCuts.shiftUs(0, 60_000_000))
        assertEquals(0L, StudioCuts.shiftUs(60_000_000, 60_000_000))
        assertEquals(0L, StudioCuts.shiftUs(0, 0))
    }

    // ---------- montage ----------

    private fun times(vararg ms: Long): List<SceneTime> = ms.toList().zipWithNext().mapIndexed { i, (a, b) -> SceneTime(i, a, b, i..i) }

    @Test
    fun slotsFollowScenes() {
        val clips = mapOf(
            "c1" to SClip("c1", "u", 0, 3000, file = "c1.mp4", leadMs = 400, lenMs = 3000),
            "c2" to SClip("c2", "u", 0, 5000, file = "c2.mp4", leadMs = 0, lenMs = 5000),
            "c3" to SClip("c3", "u", 0, 5000)
        )
        val t = times(0, 2000, 4000, 6000, 8000, 10_000)
        val slots = StudioCuts.slots(t, listOf("c1", "c1", "", "c3", "c2"), clips)
        // c3 is not downloaded: its scene stays graphics
        assertEquals(listOf("c1", "c1", "c2"), slots.map { it.id })
        // the same clip on the next scene goes on
        assertEquals(2000L, slots[1].offsetMs)
        assertEquals(400L, StudioCuts.fileTime(slots[0], 0))
        assertEquals(400L + 2500, StudioCuts.fileTime(slots[1], 2500))
        // longer than the clip: loops
        assertEquals(400L + 500, StudioCuts.fileTime(slots[1], 3500))
        assertEquals(0, StudioCuts.slotAt(slots, 1999))
        assertEquals(1, StudioCuts.slotAt(slots, 2000))
        assertEquals(-1, StudioCuts.slotAt(slots, 5000))
        assertEquals(2, StudioCuts.slotAt(slots, 9000))
        assertEquals(-1, StudioCuts.slotAt(slots, 10_000))
        assertEquals(-1, StudioCuts.slotAt(emptyList(), 0))
    }

    @Test
    fun autoAssignMatchesNotesAndSpreads() {
        val scenes = listOf(
            SScene("Сегодня разберём новые нейросети"),
            SScene("Котики захватили интернет"),
            SScene("Дальше про деньги"),
            SScene("Ещё одна мысль"),
            SScene("Итоги")
        )
        val clips = listOf(SClip("c1", "u", 0, 1, note = "смешной котик в шоке"), SClip("c2", "u", 0, 1, note = "что-то"))
        val a = StudioCuts.autoAssign(scenes, clips)
        assertEquals("c1", a[1])
        assertEquals(1, a.count { it == "c2" })
        assertEquals(5, a.size)
        assertTrue(StudioCuts.autoAssign(scenes, emptyList()).all { it.isEmpty() })
        // more clips than scenes: every scene gets one, the rest wait
        val many = (1..8).map { SClip("c$it", "u", 0, 1) }
        assertEquals(5, StudioCuts.autoAssign(scenes, many).count { it.isNotEmpty() })
    }

    @Test
    fun promptListsOnlyReadyClips() {
        assertEquals("", StudioCuts.promptBlock(listOf(SClip("c1", "u", 0, 1))))
        val p = StudioCuts.promptBlock(listOf(SClip("c2", "u", 0, 3000, note = "Хоумлендер в шоке", file = "c2.mp4", lenMs = 3000)))
        assertTrue(p.contains("c2 — «Хоумлендер в шоке», 3 с"))
        assertTrue(p.contains("\"clip\""))
    }

    // ---------- director and scenes ----------

    @Test
    fun directorKeepsKnownClips() {
        val reply = """{"title":"t","scenes":[{"from":0,"to":1,"kind":"text","clip":"c1"},{"from":2,"to":3,"kind":"text","clip":"c9"}]}"""
        val d = StudioDirector.parse(reply, 4, setOf("c1"))!!
        assertEquals(listOf("c1", ""), d.scenes.map { it.clip })
        // without clips (other tasks) nothing is kept
        assertTrue(StudioDirector.parse(reply, 4)!!.scenes.all { it.clip.isEmpty() })
        val words = listOf("a", "b", "c", "d").mapIndexed { i, w -> SWord(w, i * 500L, i * 500L + 400) }
        val ap = StudioDirector.apply(d, words, 2000, emptySet())
        assertEquals("c1", ap.script.scenes[0].clip)
    }

    @Test
    fun sceneClipIsSaved() {
        val sc = SScript("t", listOf(SScene("привет мир", clip = "c3"), SScene("пока")))
        val back = Studio.parseScript(Studio.toJson(sc))!!
        assertEquals("c3", back.scenes[0].clip)
        assertEquals("", back.scenes[1].clip)
    }
}

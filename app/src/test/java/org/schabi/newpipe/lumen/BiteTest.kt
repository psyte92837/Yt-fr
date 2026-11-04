/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiteTest {
    @Test
    fun rangeFromTypedTimes() {
        val r = Bite.range("1:23", "1:35.5", 600_000)
        assertTrue(r.ok)
        assertEquals(83_000L, r.fromMs)
        assertEquals(95_500L, r.toMs)
        // empty end → to the end of the video; end past the video is cut
        assertEquals(600_000L, Bite.range("10", "", 600_000).toMs)
        assertEquals(600_000L, Bite.range("10", "20:00", 600_000).toMs)
        assertFalse(Bite.range("10", "", 0).ok)
        assertFalse(Bite.range("30", "20", 0).ok)
        assertFalse(Bite.range("abc", "20", 0).ok)
        assertFalse(Bite.range("11:00", "12:00", 600_000).ok)
        assertFalse(Bite.range("0", "2h", 0).ok)
    }

    @Test
    fun fileNames() {
        assertEquals("Lumen_Bite_Как_сделать_ролик_1m23s-1m35s.mp4", Bite.fileName("Как сделать ролик?!", 83_000, 95_000, false))
        assertEquals("Lumen_Bite_video_5s-1h00m00s.m4a", Bite.fileName("  ***  ", 5_000, 3_600_000, true))
        assertTrue(Bite.safeTitle("a".repeat(100)).length <= 40)
    }

    @Test
    fun lengthAndSize() {
        assertEquals(15_000L, Bite.endFor(5_000, 10, 0))
        assertEquals(12_000L, Bite.endFor(5_000, 10, 12_000))
        assertTrue(Bite.sizeMb(60_000, false, 1080) > Bite.sizeMb(60_000, false, 360))
        assertTrue(Bite.sizeMb(60_000, true, 1080) < 1.5)
    }

    @Test
    fun doneListKeepsNewestFirst() {
        var list = emptyList<Bite.Done>()
        for (i in 1..(Bite.KEEP + 2)) list = Bite.add(list, Bite.Done("f$i.mp4", "t", "u", 0, 1000, false, at = i.toLong())).first
        assertEquals(Bite.KEEP, list.size)
        assertEquals("f${Bite.KEEP + 2}.mp4", list.first().file)
        val (again, drop) = Bite.add(list, Bite.Done("f3.mp4", "t", "u", 0, 1000, true))
        assertEquals(Bite.KEEP, again.size)
        assertEquals("f3.mp4", again.first().file)
        assertTrue(drop.isEmpty())
        val back = Bite.fromJson(Bite.toJson(again))
        assertEquals(again, back)
        assertTrue(Bite.fromJson("not json").isEmpty())
    }
}

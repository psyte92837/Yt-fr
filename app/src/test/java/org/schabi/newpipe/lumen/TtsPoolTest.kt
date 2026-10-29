/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TtsPoolTest {
    @Test
    fun everyPartIsDoneOnceAndLandsAtItsIndex() {
        val out = ConcurrentHashMap<Int, ByteArray>()
        TtsPool.run(20, 4, { false }, { false }, { i, _ -> byteArrayOf(i.toByte()) }) { i, a -> out[i] = a }
        assertEquals(20, out.size)
        (0 until 20).forEach { assertEquals(it.toByte(), out[it]!![0]) }
    }

    @Test
    fun readyPartsAreSkipped() {
        val calls = AtomicInteger()
        TtsPool.run(6, 3, { it < 4 }, { false }, { _, _ -> calls.incrementAndGet(); ByteArray(1) }) { _, _ -> }
        assertEquals(2, calls.get())
    }

    @Test
    fun runsInParallel() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        TtsPool.run(8, 4, { false }, { false }, { _, _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(50)
            running.decrementAndGet()
            ByteArray(1)
        }) { _, _ -> }
        assertTrue(peak.get() in 2..4)
    }

    @Test
    fun firstFailureIsRethrownAndStopsTheRest() {
        val started = AtomicInteger()
        try {
            TtsPool.run(50, 2, { false }, { false }, { i, _ ->
                started.incrementAndGet()
                if (i == 1) throw java.io.IOException("boom")
                Thread.sleep(20)
                ByteArray(1)
            }) { _, _ -> }
            fail()
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
        assertTrue(started.get() < 50)
    }

    @Test
    fun cancelledTakesNothingNew() {
        val calls = AtomicInteger()
        TtsPool.run(10, 2, { false }, { true }, { _, _ -> calls.incrementAndGet(); ByteArray(1) }) { _, _ -> }
        assertEquals(0, calls.get())
    }

    @Test
    fun percentAndLabel() {
        assertEquals(0, TtsProgress.percent(0, 0))
        assertEquals(25, TtsProgress.percent(3, 12))
        assertEquals(100, TtsProgress.percent(15, 12))
        assertTrue(TtsProgress.label(3, 12).contains("25%"))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("lumen-Привет-мир-Это-тест-2026-01-02.mp3", TtsProgress.fileName("Привет, мир! Это тест. Ещё", "2026-01-02"))
        assertEquals("lumen-tts-2026-01-02.mp3", TtsProgress.fileName("…/\\:*?", "2026-01-02"))
        assertFalse(TtsProgress.fileName("a/b\\c:d", "d").contains("/"))
    }
}

class TtsQueueTest {
    @Test
    fun skipStaysInsideTheList() {
        assertEquals(1, TtsQueue.target(0, 5, 1))
        assertEquals(0, TtsQueue.target(0, 5, -1))
        assertEquals(4, TtsQueue.target(4, 5, 1))
        assertEquals(0, TtsQueue.target(0, 0, 1))
    }

    @Test
    fun estimateAndDuration() {
        assertEquals(0, TtsProgress.estimateSec(0, 0))
        assertEquals(60, TtsProgress.estimateSec(960, 0))
        assertEquals(30, TtsProgress.estimateSec(960, 100))
        assertTrue(TtsProgress.duration(20).contains("1"))
        assertTrue(TtsProgress.duration(600).contains("10"))
        assertTrue(TtsProgress.duration(3 * 3600 + 600).contains("3"))
    }

    @Test
    fun titleIsShortAndOneLine() {
        assertEquals("Привет мир", TtsProgress.title("  Привет\n\n  мир "))
        assertEquals(49, TtsProgress.title("a".repeat(100)).length)
    }

    @Test
    fun shortFirstPiece() {
        val text = ("Предложение номер один. ").repeat(200)
        val parts = EdgeTts.split(text, 3600, 300)
        assertTrue(parts[0].toByteArray().size <= 300)
        assertTrue(parts.drop(1).any { it.toByteArray().size > 300 })
    }
}

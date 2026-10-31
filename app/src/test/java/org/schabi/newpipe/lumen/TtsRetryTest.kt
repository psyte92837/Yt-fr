/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRetryTest {
    @Test
    fun delaysGrowThenGiveUp() {
        assertEquals(listOf(2000L, 4000L, 8000L, 15000L, 30000L, 60000L, 90000L, 120000L), (0..7).map { TtsRetry.delayMs(it) })
        assertNull(TtsRetry.delayMs(8))
        assertEquals(1500L, TtsRetry.jitter(2000, 0.0))
        assertEquals(2500L, TtsRetry.jitter(2000, 1.0))
    }

    @Test
    fun readableMessages() {
        val dns = TtsRetry.message(java.net.UnknownHostException("speech.platform.bing.com"), 3, 10)
        assertTrue(dns.contains("нет интернета"))
        assertTrue(dns.contains("3 из 10"))
        val reset = TtsRetry.message(java.net.SocketException("Connection reset"), 0, 1)
        assertTrue(reset.contains("связь оборвалась"))
        assertTrue(!reset.contains("из"))
        assertTrue(TtsRetry.message(java.net.SocketTimeoutException(), 0, 1).contains("не отвечает"))
    }
}

class TtsStallTest {
    @Test
    fun slowStreamIsNotAStall() {
        // audio keeps coming for 2 minutes: not stalled (the old fixed 90 s limit failed here)
        assertTrue(!TtsRetry.stalled(idleMs = 500, totalMs = 120_000))
        assertTrue(TtsRetry.stalled(idleMs = 31_000, totalMs = 40_000))
        assertTrue(TtsRetry.stalled(idleMs = 0, totalMs = 11 * 60_000))
    }
}

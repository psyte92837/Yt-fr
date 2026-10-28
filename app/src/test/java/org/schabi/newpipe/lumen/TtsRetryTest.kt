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
        assertEquals(listOf(2000L, 4000L, 8000L, 15000L), (0..3).map { TtsRetry.delayMs(it) })
        assertNull(TtsRetry.delayMs(4))
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

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StudioMusicRetryTest {
    @Test
    fun secondTryWins() {
        var calls = 0
        var retried = 0
        val r = StudioMusicCatalog.retrying({ retried++ }, pauseMs = 0) {
            calls++
            if (calls == 1) throw java.net.SocketTimeoutException("timeout") else "ok"
        }
        assertEquals("ok", r)
        assertEquals(2, calls)
        assertEquals(1, retried)
    }

    @Test
    fun timeoutBecomesReadable() {
        try {
            StudioMusicCatalog.retrying<String>({}, pauseMs = 0) { throw java.net.SocketTimeoutException("timeout") }
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не отвечает"))
        }
    }
}

class StudioSpeechPackTest {
    @Test
    fun installedTagWins() {
        assertEquals("ru-RU" to true, StudioSpeechPack.pick("ru-RU", listOf("en-US", "ru-RU"), listOf("ru-RU")))
        assertEquals("ru" to true, StudioSpeechPack.pick("ru-RU", listOf("ru"), emptyList()))
        assertEquals("ru-RU" to false, StudioSpeechPack.pick("ru_RU", emptyList(), listOf("de-DE", "ru-RU")))
        assertEquals(null, StudioSpeechPack.pick("kk-KZ", listOf("ru-RU"), listOf("en-US")))
    }

    @Test
    fun downloadOncePerWeek() {
        val now = 10 * StudioSpeechPack.AGAIN_MS
        assertTrue(StudioSpeechPack.mayDownload(0, now))
        assertTrue(!StudioSpeechPack.mayDownload(now - 3600_000, now))
        assertTrue(StudioSpeechPack.mayDownload(now - StudioSpeechPack.AGAIN_MS, now))
        // clock moved back: allowed
        assertTrue(StudioSpeechPack.mayDownload(now + 1000, now))
    }
}

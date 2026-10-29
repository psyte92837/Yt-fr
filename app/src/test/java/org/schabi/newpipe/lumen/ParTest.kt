package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParTest {
    @Test fun keepsOrder() {
        val r = Par.map((1..20).toList(), threads = 4) { _, x -> Thread.sleep((20 - x).toLong()); x * 2 }
        assertEquals((1..20).map { it * 2 }, r)
    }

    @Test fun boundsConcurrency() {
        val now = AtomicInteger()
        val peak = AtomicInteger()
        Par.each((1..16).toList(), threads = 3) { _, _ ->
            val n = now.incrementAndGet()
            peak.accumulateAndGet(n, ::maxOf)
            Thread.sleep(15)
            now.decrementAndGet()
        }
        assertTrue(peak.get() in 2..3)
    }

    @Test fun cancelSkipsRest() {
        val cancel = AtomicBoolean(false)
        val seen = AtomicInteger()
        val r = Par.map((1..50).toList(), threads = 1, cancel = cancel) { _, x -> seen.incrementAndGet(); if (x == 3) cancel.set(true); x }
        assertEquals(3, seen.get())
        assertEquals(null, r[10])
    }

    @Test fun mapAllRethrows() {
        try {
            Par.mapAll(listOf(1, 2, 3, 4), threads = 2) { _, x -> if (x == 3) throw IllegalStateException("boom") else x }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun retriesRateLimitOnly() {
        var calls = 0
        val v = Par.retrying(3) { if (++calls < 2) throw RuntimeException("HTTP 429: slow down") else "ok" }
        assertEquals("ok", v)
        calls = 0
        try {
            Par.retrying(3) { calls++; throw RuntimeException("HTTP 400: bad") }
            fail()
        } catch (e: RuntimeException) {
            assertEquals(1, calls)
        }
    }
}

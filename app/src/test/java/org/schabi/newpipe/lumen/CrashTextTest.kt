/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashTextTest {
    private val trace = """
----- pid 123 at 2026-11-03 -----
Cmd line: org.schabi.newpipe.debug

"Signal Catcher" daemon prio=10 tid=7 Runnable
  at a.b.C.d(C.java:1)

"main" prio=5 tid=1 Blocked
  at org.schabi.newpipe.lumen.Store.projects(Store.kt:10)
  - waiting to lock <0x1> held by thread 20
  at org.schabi.newpipe.lumen.LumenActivity.onCreate(HomeActivities.kt:120)

"pool-3" prio=5 tid=20 Sleeping
  at java.lang.Thread.sleep(Native method)
  at org.schabi.newpipe.lumen.Store.load(Store.kt:40)

"other" prio=5 tid=21 Waiting
  at java.lang.Object.wait(Native method)
""".trimIndent()

    @Test
    fun anrDigestKeepsMainAndLumenThreads() {
        val d = CrashText.anrDigest(trace.lineSequence())
        assertTrue(d.startsWith("\"main\""))
        assertTrue(d.contains("LumenActivity.onCreate"))
        assertTrue(d.contains("\"pool-3\""))
        assertFalse(d.contains("Signal Catcher"))
        assertFalse(d.contains("\"other\""))
    }

    @Test
    fun anrDigestCutsLongBlocks() {
        val long = "\"main\" tid=1\n" + (1..10).joinToString("\n") { "  at x.Y.f$it(Y.java:$it)" }
        val d = CrashText.anrDigest(long.lineSequence(), maxLines = 4)
        assertEquals(5, d.lines().size)
        assertTrue(d.endsWith("… +7"))
    }

    @Test
    fun stackMarksLumenFrames() {
        val s = CrashText.stack("main", "BLOCKED", listOf("org.schabi.newpipe.lumen.A.b(A.kt:1)", "android.os.Looper.loop(Looper.java:1)"))
        assertTrue(s.contains("▶ at org.schabi.newpipe.lumen.A.b"))
        assertTrue(s.contains("    at android.os.Looper.loop"))
    }

    @Test
    fun tailCutsAtLineStart() {
        assertEquals("abc", CrashText.tail("abc", 10))
        assertEquals("…\nccc", CrashText.tail("aaa\nbbb\nccc", 5))
    }

    @Test
    fun reasons() {
        assertEquals("ANR", CrashText.reasonName(6))
        assertTrue(CrashText.isFailure(6))
        assertFalse(CrashText.isFailure(10))
    }
}

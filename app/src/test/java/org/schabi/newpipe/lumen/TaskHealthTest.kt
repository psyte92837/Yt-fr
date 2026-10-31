/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHealthTest {
    private val min = 60_000L

    @Test
    fun stateFollowsIdleTime() {
        assertEquals(TaskHealth.State.OK, TaskHealth.state(4 * min, 0, 10))
        assertEquals(TaskHealth.State.SLOW, TaskHealth.state(5 * min, 0, 10))
        assertEquals(TaskHealth.State.STUCK, TaskHealth.state(10 * min, 0, 10))
        // auto-stop off: the label uses the default limit
        assertEquals(TaskHealth.State.OK, TaskHealth.state(9 * min, 0, 0))
        assertEquals(TaskHealth.State.STUCK, TaskHealth.state(TaskHealth.DEFAULT_MIN * min, 0, 0))
        assertEquals(TaskHealth.State.OK, TaskHealth.state(99 * min, 0, 10, waiting = true))
    }

    @Test
    fun autoStopOnlyWhenEnabledAndNotWaiting() {
        assertTrue(TaskHealth.autoStop(10 * min, 0, 10))
        assertFalse(TaskHealth.autoStop(9 * min, 0, 10))
        assertFalse(TaskHealth.autoStop(999 * min, 0, 0))
        assertFalse(TaskHealth.autoStop(999 * min, 0, 10, waiting = true))
    }

    @Test
    fun warnAtHalfTheLimit() {
        assertTrue(TaskHealth.warn(5 * min, 0, 10))
        assertFalse(TaskHealth.warn(4 * min, 0, 10))
        assertTrue(TaskHealth.warn(TaskHealth.DEFAULT_MIN * min, 0, 0))
        assertFalse(TaskHealth.warn(99 * min, 0, 10, waiting = true))
    }

    @Test
    fun forceAfterGrace() {
        assertFalse(TaskHealth.force(1000, 0))
        assertFalse(TaskHealth.force(TaskHealth.GRACE_MS - 1 + 5, 5))
        assertTrue(TaskHealth.force(TaskHealth.GRACE_MS + 5, 5))
    }

    @Test
    fun outcomeAndHistory() {
        assertEquals(TaskHealth.DONE, TaskHealth.outcome(null, false, false))
        assertEquals(TaskHealth.ERROR, TaskHealth.outcome(RuntimeException("x"), false, false))
        assertEquals(TaskHealth.STOPPED, TaskHealth.outcome(java.util.concurrent.CancellationException(), false, false))
        assertEquals(TaskHealth.STOPPED, TaskHealth.outcome(RuntimeException("x"), true, false))
        assertEquals(TaskHealth.AUTO, TaskHealth.outcome(null, true, true))
        var h = emptyList<TaskHealth.Entry>()
        repeat(5) { h = TaskHealth.push(h, TaskHealth.Entry("t$it", TaskHealth.DONE, 1, it.toLong()), max = 3) }
        assertEquals(listOf("t4", "t3", "t2"), h.map { it.title })
    }
}

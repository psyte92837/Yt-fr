/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioParTest {

    @Test
    fun concatKeepsOrder() {
        val out = Studio.concat(listOf(shortArrayOf(1, 2), ShortArray(0), shortArrayOf(3)))
        assertArrayEquals(shortArrayOf(1, 2, 3), out)
        assertEquals(0, Studio.concat(emptyList()).size)
    }

    @Test
    fun frameWorkersLeaveACoreForTheEncoder() {
        assertEquals(1, Studio.frameWorkers(8, 1080, 1920, 30))
        assertEquals(1, Studio.frameWorkers(1, 720, 1280, 900))
        assertEquals(1, Studio.frameWorkers(2, 720, 1280, 900))
        assertEquals(3, Studio.frameWorkers(8, 720, 1280, 900))
        assertEquals(2, Studio.frameWorkers(8, 1080, 1920, 900))
        assertEquals(2, Studio.frameWorkers(3, 720, 1280, 900))
    }

    @Test
    fun cpuWorkersBounded() {
        assertTrue(Studio.cpuWorkers() in 1..4)
    }

    @Test
    fun mapAllKeepsOrderInParallel() {
        val r = Par.mapAll((1..20).toList(), 4) { _, x -> Thread.sleep((20 - x).toLong()); x * 2 }
        assertEquals((1..20).map { it * 2 }, r)
    }
}

package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecogGiveUpTest {
    @Test
    fun onlineLanguageUnavailableStopsAtOnce() {
        // the report of 01.11: on-device 11, then online 13 on every piece
        assertTrue(RecogGiveUp.stop(local = false, error = 13, failsInRow = 1, recognised = 0))
        // on the phone itself 13 means "download the pack", handled elsewhere
        assertFalse(RecogGiveUp.stop(local = true, error = 13, failsInRow = 1, recognised = 0))
        // some pieces already recognised: keep going, the rest may work
        assertFalse(RecogGiveUp.stop(local = false, error = 13, failsInRow = 1, recognised = 2))
    }

    @Test
    fun threeFailuresInARowStop() {
        var f = 0
        f = RecogGiveUp.fails(f, false, 2)
        f = RecogGiveUp.fails(f, false, 7) // silence does not count
        f = RecogGiveUp.fails(f, false, 2)
        assertEquals(2, f)
        assertFalse(RecogGiveUp.stop(false, 2, f, 0))
        f = RecogGiveUp.fails(f, false, 4)
        assertTrue(RecogGiveUp.stop(false, 4, f, 0))
        assertEquals(0, RecogGiveUp.fails(f, true, 0))
    }
}

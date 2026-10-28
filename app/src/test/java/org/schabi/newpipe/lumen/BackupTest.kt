/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val h = 3_600_000L

    @Test
    fun autoBackupIsDueOnlyWithDataAfterTheInterval() {
        val now = 100 * h
        assertTrue(AutoBackup.due(enabled = true, hasData = true, lastAt = 0, now = now))
        assertTrue(AutoBackup.due(enabled = true, hasData = true, lastAt = now - 3 * h, now = now))
        assertFalse(AutoBackup.due(enabled = true, hasData = true, lastAt = now - 2 * h, now = now))
        assertFalse(AutoBackup.due(enabled = false, hasData = true, lastAt = 0, now = now))
        assertFalse(AutoBackup.due(enabled = true, hasData = false, lastAt = 0, now = now))
        // clock moved back (time zone / manual change): don't wait forever
        assertTrue(AutoBackup.due(enabled = true, hasData = true, lastAt = now + h, now = now))
    }
}

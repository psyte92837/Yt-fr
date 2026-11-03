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

    @Test
    fun backupSkipsCachesAndStoresMedia() {
        assertTrue(Store.backupSkip("watchnext/RU_abc.json"))
        assertTrue(Store.backupSkip("projects/p1/videos.json.tmp"))
        assertTrue(Store.backupSkip("studio/edit/e1/anim/h1/r_720/0001.webp"))
        assertFalse(Store.backupSkip("studio/edit/e1/anim/h1/code.js"))
        assertFalse(Store.backupSkip("projects/p1/project.json"))
        assertFalse(Store.backupSkip("studio/edit/e1/base.mp4"))
        assertTrue(Store.backupStored("studio/edit/e1/base.MP4"))
        assertTrue(Store.backupStored("studio/mix/clips/a.webm"))
        assertFalse(Store.backupStored("projects/p1/videos.json"))
        assertFalse(Store.backupStored("studio/voice.wav"))
    }
}

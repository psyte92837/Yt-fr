/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import org.schabi.newpipe.BuildConfig

/** Version of the running build. Lumen no longer checks for, downloads or installs updates itself. */
object Updates {
    val currentBuild: Int get() = BuildConfig.LUMEN_BUILD
    val currentCommit: String get() = BuildConfig.LUMEN_COMMIT
    val currentExtractor: String get() = BuildConfig.LUMEN_EXTRACTOR

    /** Older builds scheduled a persisted update check and could leave its notification: remove both once. */
    fun cleanupOld(ctx: Context) {
        if (Lumen.prefs.getBoolean("update_removed", false)) return
        runCatching {
            ctx.getSystemService(JobScheduler::class.java)?.apply { cancel(49_001); cancel(49_002) }
            ctx.getSystemService(NotificationManager::class.java)?.apply {
                cancel(49_003)
                if (android.os.Build.VERSION.SDK_INT >= 26) deleteNotificationChannel("lumen_update")
            }
        }
        Lumen.prefs.edit().putBoolean("update_removed", true)
            .remove("update_check").remove("update_info").remove("update_checked_at").remove("update_error")
            .remove("update_notified").remove("extractor_broken_at").remove("upd_hidden").apply()
    }
}

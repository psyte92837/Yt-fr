/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.io.File

/**
 * Lumen Studio has no YouTube: the clips and the Mix search are hidden. This keeps the API of the
 * Lumen file (app/…/lumen/StudioCutsLoad.kt) so the shared screens compile; every call explains itself.
 */
object StudioCutsLoad {
    private fun none(): Nothing = throw UnsupportedOperationException(
        t("В Lumen Studio нет загрузки с YouTube — возьмите видео с телефона.", "Lumen Studio does not load from YouTube — take a video from the phone."))

    class VideoBrief(val title: String, val data: String, val durationSec: Long, val frames: List<Pair<Long, android.graphics.Bitmap>>)

    fun videoBrief(url: String, say: (String) -> Unit): VideoBrief = none()

    fun dataSource(): com.google.android.exoplayer2.upstream.DataSource.Factory =
        com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory()

    class Look(val stream: String, val frames: List<Pair<Long, android.graphics.Bitmap>>)

    fun look(url: String, want: Int = 10): Look = none()

    fun durationMs(url: String): Long = none()

    fun fetch(c: SClip, dir: File, maxHeight: Int, cancelled: () -> Boolean, progress: (String) -> Unit): SClip = none()
}

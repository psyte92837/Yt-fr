/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File

/**
 * The video being built and its state, shared by [StudioService] (does the work) and
 * [StudioActivity] (shows it). Lives in the app process; the service keeps the process alive.
 */
object StudioJob {
    class Request(
        val plan: StudioPlan,
        val voice: StudioAudio.Pcm,
        val out: File,
        val name: String,
        val w: Int,
        val h: Int,
        val background: StudioBg?,
        val music: File?,
        val musicVolume: Float,
        val musicDuck: Boolean,
        /** Sound effects of the AI director (whoosh, pop…). */
        val sfx: Boolean = true
    )

    @Volatile var request: Request? = null
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var message = ""
    @Volatile var result: File? = null
    @Volatile var savedAs = ""
    @Volatile var error: String? = null
    @Volatile var startedAt = 0L

    /** The open Studio screen; called on the main thread. */
    var listener: (() -> Unit)? = null

    fun changed() = Lumen.ui { listener?.invoke() }

    /** Voice padded to the video length, with sound effects and the background music mixed in when chosen. */
    fun finalAudio(r: Request, progress: (String) -> Unit): StudioAudio.Pcm {
        val voice = r.voice
        val samples = (r.plan.totalMs * voice.rate / 1000).toInt()
        var base = Studio.fit(voice.data, samples)
        if (r.sfx) {
            val events = StudioSfx.events(r.plan.script, r.plan.times, r.plan.words)
            if (events.isNotEmpty()) {
                progress(t("Звуки эффектов", "Sound effects"))
                base = StudioSfx.mix(base, voice.rate, events)
            }
        }
        val mf = r.music?.takeIf { it.exists() } ?: return StudioAudio.Pcm(base, voice.rate)
        progress(t("Музыка: сведение", "Music: mixing"))
        val (m, rate) = Studio.readWav(mf.readBytes()) ?: return StudioAudio.Pcm(base, voice.rate)
        val music = Studio.resample(m, rate, voice.rate)
        val speech = if (r.musicDuck) Studio.spansOfWords(r.plan.words) else emptyList()
        return StudioAudio.Pcm(Studio.mixMusic(base, music, voice.rate, r.musicVolume, speech), voice.rate)
    }

    /** Mixes the sound, encodes the video and saves it to Movies (worker thread). */
    fun build(ctx: Context, r: Request, progress: (String) -> Unit) {
        val audio = finalAudio(r, progress)
        StudioVideo.render(r.plan, audio, r.out, r.w, r.h, 30, { cancel }, r.background) { progress(it) }
        progress(t("Сохранение…", "Saving…"))
        savedAs = saveToMovies(ctx, r.out, r.name)
        result = r.out
    }

    /** Movies/Lumen through MediaStore on Android 10+; older versions keep it in the app's Movies folder. */
    fun saveToMovies(ctx: Context, f: File, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Lumen")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return f.name
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            return "Movies/Lumen/$name"
        }
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return f.name
        d.mkdirs()
        val copy = File(d, name)
        f.copyTo(copy, overwrite = true)
        return copy.absolutePath
    }

    fun uriOf(ctx: Context, f: File): android.net.Uri =
        androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", f)
}

/**
 * Builds the Studio video in the background with a progress notification (and "Cancel"),
 * so the screen can be closed or the phone locked. Ends with "Open" / "Share" in a notification.
 */
class StudioService : Service() {
    companion object {
        const val ACTION_START = "lumen.STUDIO_START"
        const val ACTION_CANCEL = "lumen.STUDIO_CANCEL"
        const val NOTIF_ID = 7410

        fun start(ctx: Context, r: StudioJob.Request) {
            StudioJob.request = r
            StudioJob.cancel = false
            StudioJob.error = null
            StudioJob.result = null
            StudioJob.savedAs = ""
            StudioJob.message = t("Подготовка…", "Preparing…")
            StudioJob.running = true
            StudioJob.startedAt = System.currentTimeMillis()
            val i = Intent(ctx, StudioService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Android 12+ refuses to start it from the background (the AI chain went on with the app minimised)
                L.w("studio", "service: ${e.message}")
                inTasks(ctx.applicationContext, r)
            }
        }

        /** The same build through [BgTasks] when the service cannot be started. */
        private fun inTasks(ctx: Context, r: StudioJob.Request) {
            val key = "studio:render"
            val end = {
                StudioJob.running = false
                StudioJob.request = null
                StudioJob.changed()
            }
            BgTasks.run(ctx, key, t("Lumen Studio: сборка ролика", "Lumen Studio: building the video"),
                Intent(ctx, StudioActivity::class.java), {
                    StudioJob.build(ctx, r) { m ->
                        StudioJob.message = m
                        StudioJob.changed()
                        BgTasks.progress(key, m)
                    }
                }, { e ->
                    if (e !is java.util.concurrent.CancellationException) StudioJob.error = e.message ?: e.javaClass.simpleName
                    end()
                }, { StudioJob.cancel = true }) { end() }
        }

        fun cancel(ctx: Context) {
            StudioJob.cancel = true
            ctx.startService(Intent(ctx, StudioService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var worker: Thread? = null
    private var lastNotify = 0L
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lumen.init(this)
        Notify.channels(this)
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_CANCEL -> StudioJob.cancel = true
            else -> if (!StudioJob.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        foreground()
        val r = StudioJob.request
        if (r == null || worker?.isAlive == true) {
            if (r == null) finish()
            return
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:studio").apply { acquire(60 * 60_000L) }
        worker = Thread {
            try {
                StudioJob.build(this, r) { progress(it) }
                L.i("studio", "video ${r.out.length() / 1024} KB, ${r.w}x${r.h}, ${r.plan.totalMs} ms in ${(System.currentTimeMillis() - StudioJob.startedAt) / 1000} s")
                notifyDone(r.out)
            } catch (e: java.util.concurrent.CancellationException) {
                L.i("studio", "render cancelled")
            } catch (e: Throwable) {
                L.e("studio", "render: ${e.message}", e)
                StudioJob.error = e.message ?: e.javaClass.simpleName
                notifyFailed(StudioJob.error!!)
            } finally {
                StudioJob.running = false
                StudioJob.request = null
                StudioJob.changed()
                Lumen.ui { finish() }
            }
        }.apply { name = "studio-render"; start() }
    }

    private fun progress(msg: String) {
        StudioJob.message = msg
        StudioJob.changed()
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
        }
    }

    private fun openScreen(): PendingIntent = Nav.pending(this, 7300, Intent(this, StudioActivity::class.java))

    private fun build(): Notification {
        val msg = StudioJob.message
        val f = ProgressText.fraction(msg)
        val cancel = PendingIntent.getService(this, 1, Intent(this, StudioService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        return Notify.builder(this, Notify.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t("Lumen Studio: сборка ролика", "Lumen Studio: building the video"))
            .setContentText(msg)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, ((f ?: 0f) * 1000).toInt(), f == null)
            .setContentIntent(openScreen())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, t("Отмена", "Cancel"), cancel).build())
            .build()
    }

    private fun foreground() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun notifyDone(f: File) {
        val uri = runCatching { StudioJob.uriOf(this, f) }.getOrNull()
        val b = Notify.builder(this, Notify.doneChannel())
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(t("Ролик готов", "The video is ready"))
            .setContentText(StudioJob.savedAs.ifBlank { f.name })
            .setContentIntent(openScreen())
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val send = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name)
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, t("Открыть", "Open"),
                PendingIntent.getActivity(this, 2, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_share, t("Поделиться", "Share"),
                PendingIntent.getActivity(this, 3, send, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        post(b.build())
    }

    private fun notifyFailed(msg: String) {
        post(Notify.builder(this, Notify.doneChannel())
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(t("Ролик не собран", "The video was not built"))
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(openScreen())
            .setAutoCancel(true).build())
    }

    private fun post(n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }

    private fun finish() {
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        wake = null
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        StudioJob.cancel = true
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        super.onDestroy()
    }
}

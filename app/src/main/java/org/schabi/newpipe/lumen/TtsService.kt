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
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File

/** State of the running (or last) read-aloud job, observed by [TtsActivity]. */
object TtsState {
    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var cancel = false
    /** Finished MP3 and the key (text + options) it was made for. */
    @Volatile var file: File? = null
    @Volatile var key = ""
    @Volatile var savedAs: String? = null
    @Volatile var error: String? = null
    /** Grows with every finished or failed job so the screen reacts to each one only once. */
    @Volatile var seq = 0
    @Volatile var listener: (() -> Unit)? = null

    fun changed() = Lumen.ui { listener?.invoke() }
}

/** Synthesises in the background (foreground service) so a long text survives leaving the app; shows progress in a notification. */
class TtsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lumen.init(this)
        Notify.channels(this)
        if (intent?.action == ACTION_STOP) {
            TtsState.cancel = true
            return START_NOT_STICKY
        }
        // startForeground must follow every startForegroundService()
        show(TtsState.done, TtsState.total)
        if (TtsState.running || intent == null) {
            if (!TtsState.running) stopSelf()
            return START_NOT_STICKY
        }
        val text = runCatching { File(cacheDir, JOB_FILE).readText() }.getOrDefault("")
        val o = TtsOptions(
            intent.getStringExtra("voice") ?: TtsPrefs.voice, intent.getIntExtra("rate", 0),
            intent.getIntExtra("pitch", 0), intent.getIntExtra("volume", 0)
        )
        val key = intent.getStringExtra("key") ?: ""
        val autoSave = intent.getBooleanExtra("autoSave", false)
        TtsState.running = true
        TtsState.cancel = false
        TtsState.done = 0
        TtsState.total = 0
        TtsState.error = null
        TtsState.savedAs = null
        TtsState.changed()
        Thread { run(text, o, key, autoSave) }.apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun run(text: String, o: TtsOptions, key: String, autoSave: Boolean) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:tts")
        wake.acquire(2 * 60 * 60 * 1000L)
        var lastShown = -1
        try {
            val mp3 = EdgeTts.synthesize(text, o, { TtsState.cancel }) { done, total ->
                TtsState.done = done
                TtsState.total = total
                if (done != lastShown) {
                    lastShown = done
                    show(done, total)
                    TtsState.changed()
                }
            }
            if (TtsState.cancel) return
            val f = File(cacheDir, "tts_last.mp3").apply { writeBytes(mp3) }
            TtsState.file = f
            TtsState.key = key
            if (autoSave && Build.VERSION.SDK_INT >= 29) {
                TtsState.savedAs = runCatching { saveToMusic(f, TtsProgress.fileName(text, Fmt.isoDay(System.currentTimeMillis()))) }
                    .onFailure { L.w("tts", "auto-save failed: ${it.message}") }.getOrNull()
            }
            finish(
                t("Озвучка готова", "Read aloud is ready"),
                TtsState.savedAs?.let { t("Сохранено: $it", "Saved: $it") } ?: t("Откройте экран, чтобы сохранить MP3", "Open the screen to save the MP3")
            )
        } catch (e: Throwable) {
            L.e("tts", e.message ?: e.javaClass.simpleName, e)
            val msg = e.message ?: e.javaClass.simpleName
            TtsState.error = msg
            finish(t("Озвучка прервалась", "Reading stopped"), msg)
        } finally {
            if (wake.isHeld) wake.release()
            TtsState.running = false
            TtsState.seq++
            TtsState.changed()
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** MediaStore (Music/Lumen) needs no storage permission on Android 10+. Returns the visible path. */
    private fun saveToMusic(f: File, name: String): String {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore")
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("MediaStore")
        return "Music/Lumen/$name"
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, TtsActivity::class.java).putExtra("cached", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun show(done: Int, total: Int) {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pct = TtsProgress.percent(done, total)
        val n = Notify.builder(this, Notify.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Lumen: " + t("озвучка", "read aloud") + if (total > 0) " $pct%" else "")
            .setContentText(if (total > 0) TtsProgress.label(done, total) else t("Подготовка…", "Preparing…"))
            .setProgress(100, pct, total == 0)
            .setOngoing(true)
            .setContentIntent(open())
            .addAction(Notification.Action.Builder(null, t("Стоп", "Stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun finish(title: String, body: String) {
        stopForegroundCompat()
        val n = Notify.builder(this, Notify.CH_MONITOR).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Lumen: $title").setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open()).setAutoCancel(true).build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    companion object {
        const val ACTION_STOP = "lumen.TTS_STOP"
        const val NOTIF_ID = 7310
        const val JOB_FILE = "tts_job.txt"

        fun start(ctx: Context, text: String, o: TtsOptions, key: String, autoSave: Boolean) {
            File(ctx.cacheDir, JOB_FILE).writeText(text)
            val i = Intent(ctx, TtsService::class.java).putExtra("voice", o.voice).putExtra("rate", o.rate)
                .putExtra("pitch", o.pitch).putExtra("volume", o.volume).putExtra("key", key).putExtra("autoSave", autoSave)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            TtsState.cancel = true
            if (TtsState.running) ctx.startService(Intent(ctx, TtsService::class.java).setAction(ACTION_STOP))
        }
    }
}

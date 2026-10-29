/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File

/** What the background job is for. */
enum class TtsMode { LISTEN, SAVE, PREVIEW }

/** State of the running (or last) read-aloud job. The service writes it, [TtsActivity] only observes it. */
object TtsState {
    @Volatile var mode = TtsMode.LISTEN
    /** Parts are being synthesised. */
    @Volatile var synth = false
    @Volatile var done = 0
    @Volatile var total = 0
    /** A playback session exists (playing, paused or waiting for the next part). */
    @Volatile var playing = false
    @Volatile var paused = false
    @Volatile var buffering = false
    @Volatile var part = 0
    /** Joining the parts and saving the MP3. */
    @Volatile var saving = false
    @Volatile var title = ""
    @Volatile var cancel = false
    /** Finished MP3 of the last real job (not a voice preview) and the key (text + options) it was made for. */
    @Volatile var result: File? = null
    @Volatile var resultKey = ""
    @Volatile var savedName: String? = null
    @Volatile var savedUri: String? = null
    @Volatile var error: String? = null
    @Volatile var listener: (() -> Unit)? = null

    val active get() = synth || playing || saving

    fun changed() = Lumen.ui { listener?.invoke() }
}

/**
 * Owns synthesis and playback so both survive leaving the screen: parts are synthesised in parallel,
 * "listen" starts playing as soon as the first part is ready, "save" writes the MP3 to Music/Lumen.
 * Progress and controls (previous / pause / next / stop) are in the notification and on the lock screen.
 */
class TtsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var player: MediaPlayer? = null
    private var files: Array<File?> = arrayOfNulls(0)
    private var cur = 0
    private var waitingFor = -1
    private var generation = 0
    private var focus: Any? = null
    private var pausedByFocus = false
    private var noisy: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lumen.init(this)
        Notify.channels(this)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_TOGGLE -> main.post { toggle() }
            ACTION_NEXT -> main.post { skip(1) }
            ACTION_PREV -> main.post { skip(-1) }
            ACTION_STOP -> main.post { stopAll() }
            ACTION_SPEED -> main.post { applySpeed() }
            else -> if (!TtsState.active) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- job ----------

    private fun start(intent: Intent) {
        val mode = TtsMode.valueOf(intent.getStringExtra("mode") ?: "LISTEN")
        foreground(mode)
        if (TtsState.synth || TtsState.saving) return // the screen and share targets refuse earlier; never run two at once
        val cached = intent.getBooleanExtra("cached", false)
        val text = if (cached) "" else runCatching { File(cacheDir, JOB_FILE).readText() }.getOrDefault("")
        val o = TtsOptions(
            intent.getStringExtra("voice") ?: TtsPrefs.voice, intent.getIntExtra("rate", 0),
            intent.getIntExtra("pitch", 0), intent.getIntExtra("volume", 0)
        )
        val key = intent.getStringExtra("key") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val keepCopy = mode == TtsMode.SAVE || (mode == TtsMode.LISTEN && TtsPrefs.saveAfterListen)
        val gen = ++generation
        releasePlayer()
        cur = 0
        waitingFor = -1
        files = arrayOfNulls(0)
        with(TtsState) {
            this.mode = mode
            this.title = title
            error = null
            cancel = false
            done = 0
            total = 0
            part = 0
            paused = false
            buffering = false
            saving = false
            if (mode != TtsMode.PREVIEW && !cached) {
                result = null
                savedName = null
                savedUri = null
            }
        }
        val cachedFile = TtsState.result?.takeIf { cached && it.exists() }
        if (cached && cachedFile == null) {
            fail(gen, EdgeTts.TtsException(t("Озвучка не найдена, запустите заново", "The audio is gone, start again")))
            return
        }
        if (cachedFile != null) {
            // already synthesised: play or save the finished file
            TtsState.total = 1
            TtsState.done = 1
            files = arrayOf<File?>(cachedFile)
            if (mode == TtsMode.LISTEN) {
                beginPlayback(gen)
                partReady(gen, 0, cachedFile)
                TtsState.changed()
                update()
            } else {
                TtsState.synth = true
                Thread { finishJob(gen, mode, text, listOf(cachedFile), key, true) }.apply { isDaemon = true; start() }
            }
            return
        }
        TtsState.synth = true
        if (mode != TtsMode.SAVE) beginPlayback(gen)
        TtsState.changed()
        update()
        Thread { runJob(gen, mode, text, o, key, keepCopy) }.apply { isDaemon = true; start() }
    }

    private fun runJob(gen: Int, mode: TtsMode, text: String, o: TtsOptions, key: String, keepCopy: Boolean) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:tts")
        wake.acquire(2 * 60 * 60 * 1000L)
        try {
            val (chunks, id) = EdgeTts.plan(text, o, if (mode == TtsMode.SAVE) EdgeTts.CHUNK_BYTES else FIRST_BYTES)
            val root = File(cacheDir, "tts")
            val dir = if (mode == TtsMode.PREVIEW) File(root, "preview").also { it.deleteRecursively() } else File(root, id)
            if (mode != TtsMode.PREVIEW) root.listFiles()?.forEach { if (it.name != id) it.deleteRecursively() }
            val parts = EdgeTts.synthesizeTo(dir, chunks, o, { TtsState.cancel || gen != generation }, { d, tot ->
                main.post { progress(gen, d, tot) }
            }, { i, f -> main.post { partReady(gen, i, f) } }) ?: return
            finishJob(gen, mode, text, parts, key, keepCopy, dir)
        } catch (e: java.util.concurrent.CancellationException) {
            // stopped by the user
        } catch (e: Throwable) {
            L.e("tts", e.message ?: e.javaClass.simpleName, e)
            main.post { fail(gen, e) }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    /** Joins the parts into one MP3 and saves it when asked; runs off the main thread. */
    private fun finishJob(gen: Int, mode: TtsMode, text: String, parts: List<File>, key: String, keepCopy: Boolean, dir: File? = null) {
        try {
            if (mode == TtsMode.PREVIEW) {
                main.post { allSynthesised(gen) }
                return
            }
            main.post { if (gen == generation) { TtsState.saving = true; TtsState.changed(); update() } }
            val last = if (parts.size == 1 && parts[0].name == "tts_last.mp3") parts[0] else File(cacheDir, "tts_last.mp3").also { out ->
                out.outputStream().use { o -> parts.forEach { p -> p.inputStream().use { it.copyTo(o) } } }
            }
            var name: String? = null
            var uri: String? = null
            if (keepCopy) {
                val saved = runCatching { saveToMusic(last, TtsProgress.fileName(text.ifBlank { TtsState.title }, Fmt.isoDay(System.currentTimeMillis()))) }
                    .onFailure { L.w("tts", "save failed: ${it.message}") }.getOrNull()
                name = saved?.first
                uri = saved?.second
                if (saved == null) throw EdgeTts.TtsException(t("Не удалось сохранить MP3 в Музыку. Нажмите «Сохранить как…».", "Could not save the MP3 to Music. Use “Save as…”."))
            }
            main.post {
                if (gen != generation) return@post
                TtsState.result = last
                TtsState.resultKey = key
                TtsState.savedName = name
                TtsState.savedUri = uri
                allSynthesised(gen)
            }
        } catch (e: Throwable) {
            L.e("tts", e.message ?: e.javaClass.simpleName, e)
            main.post { fail(gen, e) }
        }
    }

    // ---------- main-thread callbacks ----------

    private fun progress(gen: Int, d: Int, tot: Int) {
        if (gen != generation) return
        if (files.size != tot) files = files.copyOf(tot)
        TtsState.total = tot
        TtsState.done = maxOf(TtsState.done, d)
        update()
        TtsState.changed()
    }

    private fun partReady(gen: Int, i: Int, f: File) {
        if (gen != generation) return
        if (i >= files.size) files = files.copyOf(i + 1)
        files[i] = f
        if (TtsState.playing && waitingFor == i) playPart(i)
    }

    /** Called when everything is synthesised (and saved, if asked). */
    private fun allSynthesised(gen: Int) {
        if (gen != generation) return
        TtsState.synth = false
        TtsState.saving = false
        TtsState.changed()
        if (TtsState.mode == TtsMode.SAVE) {
            val saved = TtsState.savedName
            finishService()
            notifyResult(t("Озвучка готова", "Read aloud is ready"),
                saved?.let { t("Сохранено: $it", "Saved: $it") } ?: t("Откройте экран, чтобы сохранить MP3", "Open the screen to save the MP3"))
        } else if (!TtsState.playing) {
            finishService()
        } else {
            update()
        }
    }

    private fun fail(gen: Int, e: Throwable) {
        if (gen != generation) return
        val msg = e.message ?: e.javaClass.simpleName
        TtsState.error = msg
        TtsState.synth = false
        TtsState.saving = false
        releasePlayer()
        TtsState.changed()
        finishService()
        notifyResult(t("Озвучка прервалась", "Reading stopped"), msg)
    }

    private fun stopAll() {
        generation++
        TtsState.cancel = true
        TtsState.synth = false
        TtsState.saving = false
        releasePlayer()
        TtsState.changed()
        finishService()
    }

    private fun finishService() {
        stopForegroundCompat()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    // ---------- playback ----------

    private fun beginPlayback(gen: Int) {
        if (gen != generation) return
        TtsState.playing = true
        TtsState.paused = false
        TtsState.buffering = true
        waitingFor = 0
        session = MediaSession(this, "LumenTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = toggleTo(false)
                override fun onPause() = toggleTo(true)
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onStop() = stopAll()
            })
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, TtsState.title.ifBlank { "Lumen" }).build())
            isActive = true
        }
        requestFocus()
        if (noisy == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = toggleTo(true)
            }
            registerReceiver(r, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisy = r
        }
        sessionState()
    }

    private fun playPart(i: Int) {
        if (!TtsState.playing) return
        if (TtsState.total > 0 && i >= TtsState.total) {
            endPlayback()
            return
        }
        val f = files.getOrNull(i)
        if (f == null) {
            // the next part is not ready yet: wait for it
            player?.let { runCatching { it.release() } }
            player = null
            cur = i
            waitingFor = i
            TtsState.part = i
            TtsState.buffering = true
            update()
            TtsState.changed()
            return
        }
        player?.let { runCatching { it.release() } }
        player = null
        cur = i
        waitingFor = -1
        TtsState.part = i
        TtsState.buffering = false
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setWakeMode(this@TtsService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(f.absolutePath)
                setOnCompletionListener { playPart(i + 1) }
                setOnErrorListener { _, what, extra ->
                    L.w("tts", "player error $what/$extra on part ${i + 1}")
                    main.post { playPart(i + 1) }
                    true
                }
                prepare()
                if (!TtsState.paused) {
                    start()
                    applySpeed()
                }
            }
        } catch (e: Exception) {
            L.w("tts", "part ${i + 1} not playable: ${e.message}")
            main.post { playPart(i + 1) }
            return
        }
        update()
        sessionState()
        TtsState.changed()
    }

    private fun endPlayback() {
        releasePlayer()
        TtsState.changed()
        if (!TtsState.synth && !TtsState.saving) finishService() else update()
    }

    private fun toggle() = toggleTo(!TtsState.paused)

    private fun toggleTo(pause: Boolean) {
        main.post {
            if (!TtsState.playing || TtsState.paused == pause) return@post
            TtsState.paused = pause
            val p = player
            if (pause) {
                runCatching { p?.pause() }
            } else {
                requestFocus()
                runCatching { p?.start() }
                applySpeed()
                if (p == null && waitingFor < 0) playPart(cur) // nothing loaded yet (e.g. paused while waiting)
            }
            update()
            sessionState()
            TtsState.changed()
        }
    }

    private fun skip(dir: Int) {
        if (!TtsState.playing) return
        val p = player
        if (dir < 0 && p != null && runCatching { p.currentPosition }.getOrDefault(0) > 3000) {
            runCatching { p.seekTo(0) }
            return
        }
        val target = TtsQueue.target(cur, maxOf(TtsState.total, 1), dir)
        if (target == cur && dir > 0) return
        playPart(target)
    }

    private fun applySpeed() {
        val p = player ?: return
        if (TtsState.paused) return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(TtsPrefs.playSpeed) }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        noisy?.let { runCatching { unregisterReceiver(it) } }
        noisy = null
        abandonFocus()
        TtsState.playing = false
        TtsState.paused = false
        TtsState.buffering = false
        waitingFor = -1
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            main.post {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> toggleTo(true)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (!TtsState.paused) {
                        pausedByFocus = true
                        toggleTo(true)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                        pausedByFocus = false
                        toggleTo(false)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(listener).build()
            focus = r
            am.requestAudioFocus(r)
        } else {
            focus = listener
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val am = getSystemService(AudioManager::class.java)
        val f = focus ?: return
        if (Build.VERSION.SDK_INT >= 26 && f is AudioFocusRequest) am.abandonAudioFocusRequest(f)
        else if (f is AudioManager.OnAudioFocusChangeListener) am.abandonAudioFocus(f)
        focus = null
    }

    private fun sessionState() {
        val s = session ?: return
        val st = when {
            TtsState.buffering -> PlaybackState.STATE_BUFFERING
            TtsState.paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        s.setPlaybackState(
            PlaybackState.Builder().setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, TtsPrefs.playSpeed)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                ).build()
        )
    }

    // ---------- saving ----------

    /** Music/Lumen through MediaStore on Android 10+ (no permission); older versions use the app's own Music folder. */
    private fun saveToMusic(f: File, name: String): Pair<String, String?> {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore")
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("MediaStore")
            return "Music/Lumen/$name" to uri.toString()
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: error("storage")
        dir.mkdirs()
        val out = File(dir, name)
        f.copyTo(out, overwrite = true)
        return out.absolutePath to null
    }

    // ---------- notifications ----------

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, TtsActivity::class.java).putExtra("cached", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(code: Int, act: String, icon: Int, label: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, TtsService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(): Notification {
        val s = TtsState
        val pct = TtsProgress.percent(s.done, s.total)
        val b = Notify.builder(this, Notify.CH_PROGRESS).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open())
        if (!s.playing) {
            val text = when {
                s.saving -> t("Сохраняю MP3…", "Saving the MP3…")
                s.total > 0 -> TtsProgress.label(s.done, s.total)
                else -> t("Подготовка…", "Preparing…")
            }
            return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Lumen: " + t("озвучка", "read aloud") + if (s.total > 0) " $pct%" else "")
                .setContentText(text)
                .setProgress(100, pct, s.total == 0 || s.saving)
                .addAction(action(1, ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, t("Стоп", "Stop")))
                .build()
        }
        val status = when {
            s.buffering -> t("Ждём следующую часть…", "Waiting for the next part…")
            s.paused -> t("Пауза", "Paused")
            else -> t("Часть ${s.part + 1} из ${maxOf(s.total, 1)}", "Part ${s.part + 1} of ${maxOf(s.total, 1)}")
        }
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.title.ifBlank { "Lumen" })
            .setContentText(if (s.synth) "$status · ${t("озвучено", "synthesised")} $pct%" else status)
            .addAction(action(2, ACTION_PREV, android.R.drawable.ic_media_previous, t("Назад", "Previous")))
            .addAction(
                if (s.paused) action(3, ACTION_TOGGLE, android.R.drawable.ic_media_play, t("Продолжить", "Resume"))
                else action(3, ACTION_TOGGLE, android.R.drawable.ic_media_pause, t("Пауза", "Pause"))
            )
            .addAction(action(4, ACTION_NEXT, android.R.drawable.ic_media_next, t("Вперёд", "Next")))
            .addAction(action(1, ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, t("Стоп", "Stop")))
        if (s.synth) b.setProgress(100, pct, false)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        return b.setStyle(style).build()
    }

    private fun foreground(mode: TtsMode) {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (mode == TtsMode.SAVE) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun update() {
        if (!TtsState.active) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
    }

    private fun notifyResult(title: String, body: String) {
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
        const val ACTION_START = "lumen.TTS_START"
        const val ACTION_STOP = "lumen.TTS_STOP"
        const val ACTION_TOGGLE = "lumen.TTS_TOGGLE"
        const val ACTION_NEXT = "lumen.TTS_NEXT"
        const val ACTION_PREV = "lumen.TTS_PREV"
        const val ACTION_SPEED = "lumen.TTS_SPEED"
        const val NOTIF_ID = 7310
        const val JOB_FILE = "tts_job.txt"
        /** The first part of a "listen" job is short so the sound starts sooner. */
        const val FIRST_BYTES = 700

        /** Voice for [text]: matched to its language when that option is on. */
        fun voiceFor(text: String): String {
            if (!TtsPrefs.auto) return TtsPrefs.voice
            val lang = EdgeTts.detectLang(text) ?: return TtsPrefs.voice
            if (TtsPrefs.voice.substringBefore('-').equals(lang, true)) return TtsPrefs.voice
            return EdgeTts.pickVoice(EdgeTts.builtIn, lang, TtsPrefs.voiceFor(lang))?.shortName ?: TtsPrefs.voice
        }

        fun options(voice: String = TtsPrefs.voice) = TtsOptions(voice, TtsPrefs.rate, TtsPrefs.pitch, TtsPrefs.volume)

        /** Starts a job. [cached] plays / saves the last finished audio of the same text without a new request. */
        fun startJob(ctx: Context, text: String, o: TtsOptions, mode: TtsMode, title: String = TtsProgress.title(text), cached: Boolean = false) {
            File(ctx.cacheDir, JOB_FILE).writeText(text)
            val i = Intent(ctx, TtsService::class.java).setAction(ACTION_START)
                .putExtra("mode", mode.name).putExtra("voice", o.voice).putExtra("rate", o.rate).putExtra("pitch", o.pitch)
                .putExtra("volume", o.volume).putExtra("key", sha1(text + "|" + o)).putExtra("title", title).putExtra("cached", cached)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun command(ctx: Context, action: String) {
            if (!TtsState.active) return
            ctx.startService(Intent(ctx, TtsService::class.java).setAction(action))
        }
    }
}

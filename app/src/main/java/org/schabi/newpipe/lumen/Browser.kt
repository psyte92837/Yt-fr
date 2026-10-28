/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure helpers of the built-in browser (covered by unit tests). */
object BrowserText {
    const val HOME = "https://m.youtube.com"

    /** Address bar input → URL: keeps links, adds https:// to bare hosts, otherwise searches. */
    fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return HOME
        if (Regex("^(https?|about|file|data):", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }

    /** 75_000 → "01:15", 3_725_000 → "1:02:05". */
    fun duration(ms: Long): String {
        val total = maxOf(0L, ms / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Safe file name from the page title: "Lumen 2026-10-28 14-05 <title>.m4a". */
    fun fileName(title: String, at: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(at))
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trim()
        return "Lumen $stamp" + (if (clean.isNotEmpty()) " $clean" else "") + ".m4a"
    }
}

/** State of the tab audio recording, observed by the browser screen. */
object TabRec {
    @Volatile var running = false
    @Volatile var startedAt = 0L
    @Volatile var title = ""
    @Volatile var lastName = ""
    @Volatile var lastUri: Uri? = null
    @Volatile var lastError = ""

    val supported get() = Build.VERSION.SDK_INT >= 29
}

/** Built-in web browser (WebView) with recording of the tab's sound. */
class BrowserActivity : LumenBase() {
    private lateinit var web: WebView
    private lateinit var address: TextView
    private lateinit var loadBar: ProgressBar
    private lateinit var recBtn: TextView
    private lateinit var recInfo: TextView
    private var fullscreen: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            paintRec()
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(Palette.SURFACE)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "http" || u.scheme == "https") return false
                    // intent:, market:, tel: … go to other apps
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                        toast(t("Нет приложения для этой ссылки", "No app for this link"))
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    address.text = url
                }

                override fun onPageFinished(view: WebView, url: String) {
                    address.text = url
                    Lumen.prefs.edit().putString("browser_url", url).apply()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) {
                    loadBar.progress = p
                    loadBar.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
                override fun onHideCustomView() = exitFullscreen()
            }
            setDownloadListener { url, _, _, _, _ -> openUrl(url) }
        }

        address = edt(this, t("Адрес или запрос", "Address or search")).apply {
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundBg(Palette.CARD, 24f, this@BrowserActivity)
            onFocusChangeListener = null
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    web.loadUrl(BrowserText.normalize(v.text.toString()))
                    v.clearFocus()
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                    web.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
            visibility = View.INVISIBLE
        }
        recBtn = btn(this, "", primary = true) { toggleRec() } as TextView
        recInfo = text(this, "", 13f, color = Palette.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnClickListener { TabRec.lastUri?.let { openRecording(it) } }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(recBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(recInfo)
            addView(loadBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        }
        page(
            t("Браузер", "Browser"), web, header = header, scroll = false,
            actions = listOf("⋮" to { menu() })
        )
        val start = intent.dataString ?: intent.getStringExtra("url")
            ?: Lumen.prefs.getString("browser_url", null) ?: BrowserText.HOME
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(start)
        paintRec()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        // keep the sound playing while recording, otherwise behave like a normal browser
        if (!TabRec.running) web.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun menu() {
        val url = web.url.orEmpty()
        val items = listOf<Pair<String, () -> Unit>>(
            t("Назад", "Back") to { if (web.canGoBack()) web.goBack() },
            t("Вперёд", "Forward") to { if (web.canGoForward()) web.goForward() },
            t("Обновить", "Reload") to { web.reload() },
            t("YouTube", "YouTube") to { web.loadUrl(BrowserText.HOME) },
            t("Анализировать в Lumen", "Analyse in Lumen") to { QuickAnalysis.start(this, url) },
            t("Поделиться ссылкой", "Share link") to { shareText(web.title.orEmpty(), url) },
            t("Открыть во внешнем браузере", "Open in external browser") to { openUrl(url) },
            t("Последняя запись", "Last recording") to {
                TabRec.lastUri?.let { openRecording(it) } ?: toast(t("Записей пока нет", "No recordings yet"))
            }
        )
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    // ------------------------------------------------------------------ fullscreen video
    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullscreen != null) {
            cb.onCustomViewHidden()
            return
        }
        fullscreen = view
        fullscreenCb = cb
        (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(-1, -1))
        view.setBackgroundColor(0xFF000000.toInt())
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun exitFullscreen() {
        val v = fullscreen ?: return
        (window.decorView as FrameLayout).removeView(v)
        fullscreen = null
        fullscreenCb?.onCustomViewHidden()
        fullscreenCb = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isNight()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    // ------------------------------------------------------------------ recording
    private fun paintRec() {
        recBtn.text = if (TabRec.running) "■ " + BrowserText.duration(System.currentTimeMillis() - TabRec.startedAt) else "● " + t("Звук", "Audio")
        recBtn.contentDescription = if (TabRec.running) t("Остановить запись", "Stop recording") else t("Записать звук вкладки", "Record tab audio")
        val (bg, fg) = if (TabRec.running) Palette.BAD to Palette.SURFACE else Palette.ACCENT_SOFT to Palette.ON_SOFT
        recBtn.background = ripple(this, android.graphics.drawable.InsetDrawable(roundBg(bg, 20f, this), 0, dp(4), 0, dp(4)), fg)
        recBtn.setTextColor(fg)
        recInfo.text = when {
            TabRec.running -> t("Идёт запись звука вкладки…", "Recording the tab's sound…")
            TabRec.lastError.isNotEmpty() -> t("Ошибка записи: ", "Recording error: ") + TabRec.lastError
            TabRec.lastName.isNotEmpty() -> t("Сохранено в Музыка/Lumen: ", "Saved to Music/Lumen: ") + TabRec.lastName + " ▸"
            else -> ""
        }
        recInfo.visibility = if (recInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleRec() {
        if (TabRec.running) {
            TabAudioService.stop(this)
            return
        }
        if (!TabRec.supported) {
            confirm(t("Запись недоступна", "Recording unavailable"),
                t("Запись звука вкладки работает на Android 10 и новее.", "Tab audio recording needs Android 10 or newer."), "OK") {}
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        askProjection()
    }

    private fun askProjection() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIFY)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            askProjection()
        } else {
            toast(t("Без разрешения «Микрофон» Android не даёт записывать звук приложения. Микрофон при этом не используется.",
                "Android requires the Microphone permission to capture app sound. The microphone itself is not used."))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            toast(t("Запись отменена", "Recording cancelled"))
            return
        }
        TabAudioService.start(this, resultCode, data, web.title.orEmpty())
        web.onResume()
    }

    private fun openRecording(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(t("Нет приложения для аудио", "No audio app"))
        }
    }

    companion object {
        private const val REQ_MIC = 41
        private const val REQ_PROJECTION = 42
        private const val REQ_NOTIFY = 43

        fun open(ctx: Context, url: String? = null) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).apply { if (url != null) putExtra("url", url) })
        }
    }
}

/**
 * Foreground service that captures the app's own playback (the WebView tab) via AudioPlaybackCapture
 * and encodes it to AAC/M4A in Music/Lumen. The microphone is never recorded.
 */
class TabAudioService : Service() {
    @Volatile private var stopFlag = false
    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            return START_NOT_STICKY
        }
        // startForeground (type mediaProjection) must come before getMediaProjection on Android 14+
        startFg()
        if (TabRec.running || Build.VERSION.SDK_INT < 29) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (mp == null) {
            TabRec.lastError = t("нет доступа к захвату", "no capture access")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopFlag = true
            }
        }, Handler(Looper.getMainLooper()))
        TabRec.title = intent?.getStringExtra("title").orEmpty()
        TabRec.lastError = ""
        TabRec.startedAt = System.currentTimeMillis()
        TabRec.running = true
        stopFlag = false
        worker = Thread({ record(mp) }, "lumen-tab-audio").also { it.start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun record(mp: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        val rate = 44100
        val channels = 2
        var uri: Uri? = null
        var rec: AudioRecord? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var wroteSamples = false
        val name = BrowserText.fileName(TabRec.title, TabRec.startedAt)
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUid(Process.myUid()).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, 8192) * 2
            rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufSize).setAudioPlaybackCaptureConfig(cfg).build()

            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lumen")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("cannot open file")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            rec.startRecording()
            val pcm = ByteArray(bufSize / 2)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var frames = 0L
            var eos = false
            var inputDone = false
            while (!eos) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val n = if (stopFlag) 0 else rec.read(pcm, 0, minOf(pcm.size, buf.remaining()))
                        val pts = frames * 1_000_000L / rate
                        if (n <= 0 && stopFlag) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else if (n > 0) {
                            buf.put(pcm, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                            frames += n / (2 * channels)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, 0)
                        }
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    } else {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(track, out, info)
                            wroteSamples = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eos = true
                            break
                        }
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Throwable) {
            L.e("browser", "tab audio: ${e.message}", e)
            TabRec.lastError = (e.message ?: e.javaClass.simpleName).take(200)
        } finally {
            try { rec?.stop() } catch (e: Exception) { }
            rec?.release()
            try { codec?.stop() } catch (e: Exception) { }
            codec?.release()
            try { if (wroteSamples) muxer?.stop() } catch (e: Exception) { wroteSamples = false }
            try { muxer?.release() } catch (e: Exception) { }
            pfd?.close()
            uri?.let {
                if (wroteSamples) {
                    contentResolver.update(it, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                    TabRec.lastUri = it
                    TabRec.lastName = name
                } else {
                    contentResolver.delete(it, null, null)
                    if (TabRec.lastError.isEmpty()) TabRec.lastError = t("звука не было — включите воспроизведение во вкладке", "no sound — start playback in the tab")
                }
            }
            TabRec.running = false
            Lumen.ui {
                projection?.stop()
                projection = null
                if (wroteSamples) toast(t("Запись сохранена: Музыка/Lumen", "Recording saved: Music/Lumen"))
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Lumen: запись звука / audio recording", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, TabAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(t("Запись звука вкладки", "Recording tab audio"))
            .setContentText(t("Lumen записывает звук браузера", "Lumen is recording the browser's sound"))
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Стоп", "Stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }

    companion object {
        private const val CH = "lumen_tab_audio"
        private const val NOTIF_ID = 7342
        private const val ACTION_STOP = "stop"

        fun start(ctx: Context, code: Int, data: Intent, title: String) {
            val i = Intent(ctx, TabAudioService::class.java).putExtra("code", code).putExtra("data", data).putExtra("title", title)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TabAudioService::class.java).setAction(ACTION_STOP))
        }
    }
}

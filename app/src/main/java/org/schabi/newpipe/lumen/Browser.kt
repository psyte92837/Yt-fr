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
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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

    /** Safe file name from the page title: "Lumen 2026-10-28 14-05 <title>.webm". */
    fun fileName(title: String, at: Long, ext: String = "webm"): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(at))
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trim()
        return "Lumen $stamp" + (if (clean.isNotEmpty()) " $clean" else "") + ".$ext"
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
    @Volatile var lastPath = ""
}

/**
 * Page script: records the sound of the page's playing <video>/<audio> element via captureStream() +
 * MediaRecorder (WebM/Opus) and streams 1-second chunks to the app (LumenRec bridge). No system dialogs,
 * no microphone: only what the tab itself plays is recorded.
 */
object TabRecJs {
    const val START = """(function(){
  var old = window.__lumenRec;
  if (old && old.state !== 'inactive') return 'busy';
  var els = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
  if (!els.length) return 'none';
  var el = els.filter(function(e){ return !e.paused && !e.ended && e.readyState > 2; })[0] || els[0];
  var cap = el.captureStream ? el.captureStream() : null;
  if (!cap) return 'unsupported';
  var tracks = cap.getAudioTracks();
  if (!tracks.length) return 'noaudio';
  var mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
  var r;
  try { r = new MediaRecorder(new MediaStream(tracks), {mimeType: mime, audioBitsPerSecond: 128000}); }
  catch (e) { return 'error:' + e; }
  var q = Promise.resolve();
  r.ondataavailable = function(ev){
    if (!ev.data || !ev.data.size) return;
    var b = ev.data;
    q = q.then(function(){ return new Promise(function(done){
      var fr = new FileReader();
      fr.onload = function(){ var s = fr.result; LumenRec.chunk(s.substring(s.indexOf(',') + 1)); done(); };
      fr.onerror = function(){ done(); };
      fr.readAsDataURL(b);
    }); });
  };
  r.onstop = function(){ q.then(function(){ LumenRec.end(); }); };
  r.onerror = function(e){ LumenRec.fail(String(e.error || e)); };
  window.__lumenRec = r;
  r.start(1000);
  return 'ok';
})()"""

    const val STOP = """(function(){
  var r = window.__lumenRec;
  if (r && r.state !== 'inactive') { r.stop(); return 'stopping'; }
  return 'idle';
})()"""

    /** evaluateJavascript returns a JSON string literal: "\"ok\"" → ok. */
    fun result(raw: String?): String = raw.orEmpty().trim().removeSurrounding("\"")
}

/** Writes the chunks streamed by the page into Music/Lumen (MediaStore on Android 10+, app folder before). */
object TabWriter {
    private var out: OutputStream? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var name = ""
    private var bytes = 0L

    @Synchronized
    fun open(ctx: Context, title: String, at: Long) {
        name = BrowserText.fileName(title, at)
        bytes = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/webm")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lumen")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            uri = u
            out = ctx.contentResolver.openOutputStream(u) ?: throw IllegalStateException("cannot open file")
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Lumen").apply { mkdirs() }
            val f = File(dir, name)
            file = f
            out = FileOutputStream(f)
        }
    }

    @Synchronized
    fun write(data: ByteArray) {
        out?.write(data)
        bytes += data.size
    }

    /** Closes the file; an empty recording is deleted. Returns true if something was saved. */
    @Synchronized
    fun close(ctx: Context): Boolean {
        try { out?.close() } catch (e: Exception) { }
        out = null
        val saved = bytes > 0
        uri?.let { u ->
            if (saved) {
                ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                TabRec.lastUri = u
                TabRec.lastPath = ""
            } else {
                ctx.contentResolver.delete(u, null, null)
            }
        }
        file?.let { f ->
            if (saved) {
                TabRec.lastUri = null
                TabRec.lastPath = f.absolutePath
            } else {
                f.delete()
            }
        }
        if (saved) TabRec.lastName = name
        uri = null
        file = null
        return saved
    }
}

/** Built-in web browser (WebView) with recording of the tab's sound (see [TabRecJs]). */
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
                    // a full page load kills the page recorder: keep what was recorded so far
                    if (TabRec.running) finishRec("")
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
            addJavascriptInterface(RecBridge(), "LumenRec")
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
            setOnClickListener { if (!TabRec.running) openRecording() }
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
        if (TabRec.running) finishRec("")
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
            t("Последняя запись", "Last recording") to { openRecording() }
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
    private var stopAsked = false

    /** Called by the page script (JavaBridge thread). Only accepted while a recording is running. */
    private inner class RecBridge {
        @JavascriptInterface
        fun chunk(b64: String) {
            if (!TabRec.running) return
            try {
                TabWriter.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                Lumen.ui { finishRec(e.message ?: e.javaClass.simpleName) }
            }
        }

        @JavascriptInterface
        fun end() {
            if (TabRec.running) Lumen.ui { finishRec("") }
        }

        @JavascriptInterface
        fun fail(msg: String) {
            if (TabRec.running) Lumen.ui { finishRec(msg.take(200)) }
        }
    }

    private fun paintRec() {
        recBtn.text = if (TabRec.running) "■ " + BrowserText.duration(System.currentTimeMillis() - TabRec.startedAt) else "● " + t("Звук", "Audio")
        recBtn.contentDescription = if (TabRec.running) t("Остановить запись", "Stop recording") else t("Записать звук вкладки", "Record tab audio")
        val (bg, fg) = if (TabRec.running) Palette.BAD to Palette.SURFACE else Palette.ACCENT_SOFT to Palette.ON_SOFT
        recBtn.background = ripple(this, android.graphics.drawable.InsetDrawable(roundBg(bg, 20f, this), 0, dp(4), 0, dp(4)), fg)
        recBtn.setTextColor(fg)
        recInfo.text = when {
            TabRec.running -> t("Идёт запись звука вкладки…", "Recording the tab's sound…")
            TabRec.lastError.isNotEmpty() -> t("Запись: ", "Recording: ") + TabRec.lastError
            TabRec.lastName.isNotEmpty() -> t("Сохранено в Музыка/Lumen: ", "Saved to Music/Lumen: ") + TabRec.lastName + " ▸"
            else -> ""
        }
        recInfo.visibility = if (recInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleRec() = if (TabRec.running) stopRec() else startRec()

    private fun startRec() {
        val at = System.currentTimeMillis()
        try {
            TabWriter.open(this, web.title.orEmpty(), at)
        } catch (e: Exception) {
            TabRec.lastError = e.message ?: e.javaClass.simpleName
            paintRec()
            return
        }
        TabRec.lastError = ""
        TabRec.startedAt = at
        TabRec.running = true
        stopAsked = false
        paintRec()
        web.evaluateJavascript(TabRecJs.START) { raw ->
            val r = TabRecJs.result(raw)
            if (r == "ok") {
                RecKeepAlive.start(this)
                return@evaluateJavascript
            }
            TabRec.running = false
            TabWriter.close(this)
            TabRec.lastError = when {
                r == "none" -> t("на странице нет видео или аудио — откройте видео", "no video or audio on the page — open a video")
                r == "noaudio" -> t("у видео пока нет звука — запустите воспроизведение и нажмите ещё раз", "no sound yet — start playback and tap again")
                r == "busy" -> t("запись уже идёт", "already recording")
                r == "unsupported" -> t("этот WebView не умеет записывать звук страницы", "this WebView cannot capture page audio")
                else -> r.removePrefix("error:").ifBlank { t("не удалось начать запись", "could not start recording") }
            }
            paintRec()
        }
    }

    private fun stopRec() {
        if (!TabRec.running) return
        stopAsked = true
        web.evaluateJavascript(TabRecJs.STOP) { raw -> if (TabRecJs.result(raw) != "stopping") finishRec("") }
        // the page flushes the last chunk and calls end(); don't wait forever if it never does
        handler.postDelayed({ if (TabRec.running && stopAsked) finishRec("") }, 5000)
    }

    /** Closes the file and resets the state; [error] is shown if nothing could be saved or the page failed. */
    private fun finishRec(error: String) {
        if (!TabRec.running) return
        TabRec.running = false
        stopAsked = false
        val saved = TabWriter.close(this)
        RecKeepAlive.stop(this)
        TabRec.lastError = when {
            error.isNotEmpty() && !saved -> error
            !saved -> t("звука не было — запустите воспроизведение во вкладке", "no sound — start playback in the tab")
            else -> ""
        }
        if (saved) toast(t("Запись сохранена: Музыка/Lumen", "Recording saved: Music/Lumen"))
        paintRec()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_STOP_REC) stopRec()
    }

    private fun openRecording() {
        val uri = TabRec.lastUri
        if (uri == null) {
            if (TabRec.lastPath.isNotEmpty()) toast(TabRec.lastPath) else toast(t("Записей пока нет", "No recordings yet"))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/webm").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(t("Нет приложения для аудио", "No audio app"))
        }
    }

    companion object {
        const val ACTION_STOP_REC = "org.schabi.newpipe.lumen.STOP_REC"

        fun open(ctx: Context, url: String? = null) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).apply { if (url != null) putExtra("url", url) })
        }
    }
}

/** Keeps the app alive with a notification (and a Stop button) while the tab is being recorded. */
class RecKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Lumen: запись звука / audio recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = Nav.pending(this, 7302, Intent(this, BrowserActivity::class.java))
        val stop = Nav.pending(this, 7303, Intent(this, BrowserActivity::class.java).setAction(BrowserActivity.ACTION_STOP_REC))
        @Suppress("DEPRECATION")
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(t("Запись звука вкладки", "Recording tab audio"))
            .setContentText(t("Lumen записывает звук браузера", "Lumen is recording the browser's sound"))
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Стоп", "Stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "lumen_tab_audio"
        private const val NOTIF_ID = 7342

        fun start(ctx: Context) {
            val i = Intent(ctx, RecKeepAlive::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // the notification is a convenience; recording works without it while the screen is open
            }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RecKeepAlive::class.java))
    }
}

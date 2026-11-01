/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "🧪 JS layer" — the second renderer (lumen.canvas v1, [WebLayer]): AI-written canvas JavaScript drawn in a WebView
 * without network frame by frame over a finished video (the Studio result or any video), then encoded with the sound.
 * The WebView stays on screen as the live picture of the layer; the screen must stay open while it renders.
 */
class WebLayerActivity : LumenBase() {
    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_DATA = "with_data"
        private const val EXTRA_SUBS = "subs"
        private const val REQ_PICK = 7410
        private const val FPS = 30

        fun dir(ctx: Context) = File(ctx.filesDir, "lumen/studio/weblayer").apply { mkdirs() }

        /** [video] with the speech data written to dir/data.json by the caller ([withData]); null = pick a video here. */
        fun open(ctx: Context, video: File?, withData: Boolean, subtitles: Boolean) {
            ctx.startActivity(Intent(ctx, WebLayerActivity::class.java).putExtra(EXTRA_VIDEO, video?.absolutePath)
                .putExtra(EXTRA_DATA, withData).putExtra(EXTRA_SUBS, subtitles))
        }
    }

    private class LayerError(msg: String) : Exception(msg)

    private val dir by lazy { dir(this) }
    private val codeFile by lazy { File(dir, "last.js") }
    private var video: File? = null
    private var data: String? = null
    private var subs = false
    private var vw = 0
    private var vh = 0
    private var durMs = 0L
    private var code = ""
    private var ask = ""
    private var busy = ""
    @Volatile private var cancel = false
    private var lastError = ""
    private var previews: List<Bitmap> = emptyList()
    private var result: File? = null
    private var savedAs = ""
    private var statusView: TextView? = null
    private var web: WebView? = null
    private var pageReady: (() -> Unit)? = null

    private var full: Boolean
        get() = Lumen.prefs.getBoolean("weblayer_full", false)
        set(v) = Lumen.prefs.edit().putBoolean("weblayer_full", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = if (codeFile.exists()) codeFile.readText() else ""
        ask = Lumen.prefs.getString("weblayer_ask", "") ?: ""
        subs = intent.getBooleanExtra(EXTRA_SUBS, false)
        data = if (intent.getBooleanExtra(EXTRA_DATA, false)) File(dir, "data.json").takeIf { it.exists() }?.readText() else null
        intent.getStringExtra(EXTRA_VIDEO)?.let { File(it) }?.takeIf { it.exists() }?.let { setVideo(it) }
        render()
    }

    override fun onDestroy() {
        cancel = true
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        web = null
        super.onDestroy()
    }

    // ---------- video ----------

    private fun setVideo(f: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) w = h.also { h = w }
            // at most 1920 on the long side, even sides for the encoder
            val k = minOf(1.0, 1920.0 / maxOf(w, h, 1))
            vw = (w * k).toInt() / 2 * 2
            vh = (h * k).toInt() / 2 * 2
            durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            video = f.takeIf { vw > 0 && vh > 0 && durMs > 0 }
            if (video == null) toast(t("Не удалось прочитать видео", "Could not read the video"))
        } catch (e: Exception) {
            toast(t("Не удалось прочитать видео: ", "Could not read the video: ") + e.message)
        } finally {
            runCatching { r.release() }
        }
        previews = emptyList()
        result = null
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        val uri = intent?.data ?: return
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        busy = "copy"
        render()
        Lumen.async({
            val f = File(dir, "source.mp4")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            f
        }, { e ->
            busy = ""
            render()
            err(e)
        }) { f ->
            busy = ""
            data = null
            subs = false
            setVideo(f)
            render()
        }
    }

    // ---------- code ----------

    private fun setCode(s: String) {
        code = s.trim()
        codeFile.writeText(code)
        previews = emptyList()
        lastError = ""
    }

    private fun accent(): String = data?.let { runCatching { JSONObject(it).optString("accent") }.getOrNull() }?.takeIf { it.isNotBlank() } ?: "#FFD60A"

    private fun layerData(): JSONObject {
        val (lw, lh) = WebLayer.layerSize(vw, vh, full)
        return WebLayer.withVideo(data, lw, lh, durMs, accent())
    }

    private val system = "You are a motion designer who writes fast, deterministic canvas 2D JavaScript. Answer with one ```javascript code block only."

    private fun aiWrite(fix: Boolean) {
        if (video == null) return toast(t("Сначала выберите видео", "Pick a video first"))
        val prov = try {
            AiTasks.requireProvider()
        } catch (e: Exception) {
            return err(e)
        }
        val prompt = if (fix) WebLayer.fixBrief(code, lastError, ask) else WebLayer.brief(layerData(), ask, subs)
        busy = "ai"
        render()
        Lumen.async({
            Llm.complete(prov, prov.smartModel, system, prompt, maxTokens = 12_000, task = if (fix) "studio_weblayer_fix" else "studio_weblayer", pid = "studio").text
        }, { e ->
            busy = ""
            render()
            err(e)
        }) { answer ->
            busy = ""
            setCode(WebLayer.extract(answer))
            render()
            if (WebLayer.problems(code).isEmpty()) preview()
        }
    }

    /** The same request as one text for any chat; the answer comes back with "📥 Paste code". */
    private fun copyRequest() {
        if (video == null) return toast(t("Сначала выберите видео", "Pick a video first"))
        val s = RelayText.request("studio_weblayer", system, WebLayer.brief(layerData(), ask, subs), false, null, false, "")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lumen", s))
        toast(t("Запрос скопирован: вставьте в Gemini/ChatGPT, скопируйте код из ответа и нажмите «📥 Вставить код»",
            "Request copied: paste it into Gemini/ChatGPT, copy the code of the answer and tap \"📥 Paste code\""))
    }

    private fun pasteCode() {
        val s = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (s.isBlank() || RelayText.isOwnRequest(s)) return toast(t("В буфере нет кода", "No code in the clipboard"))
        setCode(WebLayer.extract(s))
        render()
        if (WebLayer.problems(code).isEmpty() && video != null) preview()
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView = web ?: WebView(this).also { w ->
        w.settings.javaScriptEnabled = true
        w.settings.blockNetworkLoads = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.domStorageEnabled = false
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        w.setBackgroundColor(Color.TRANSPARENT)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            // nothing leaves the phone: every request except inline data is answered empty
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

            override fun onPageFinished(view: WebView, url: String?) {
                pageReady?.invoke()
                pageReady = null
            }
        }
        web = w
    }

    /** Runs [script] on the main thread; the returned function waits (worker thread) for its result. */
    private fun jsAsync(script: String): () -> String? {
        val q = ArrayBlockingQueue<String>(1)
        runOnUiThread {
            val w = web
            if (w == null) q.offer("null") else w.evaluateJavascript(script) { q.offer(it ?: "null") }
        }
        return { q.poll(30, TimeUnit.SECONDS) }
    }

    /** Loads the layer page and calls init(data); throws [LayerError] on a broken layer (worker thread). */
    private fun load(d: JSONObject) {
        val latch = CountDownLatch(1)
        val html = WebLayer.page(code, d.optInt("w"), d.optInt("h"))
        runOnUiThread {
            pageReady = { latch.countDown() }
            webView().loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw LayerError(t("страница слоя не загрузилась", "the layer page did not load"))
        val r = jsAsync("__lumen.init(" + d.toString() + ")")() ?: throw LayerError(t("WebView не ответил", "The WebView gave no answer"))
        val s = runCatching { org.json.JSONArray("[$r]").getString(0) }.getOrDefault(r)
        if (s != "OK") throw LayerError(s.removePrefix("ERR:").ifBlank { t("слой не запустился", "the layer did not start") })
    }

    private fun call(t: Double) = "__lumen.frame(" + String.format(java.util.Locale.US, "%.4f", t) + ")"

    private fun overlay(res: String?): Bitmap? = when (val f = WebLayer.frameOf(res)) {
        is WebLayer.Frame.Empty -> null
        is WebLayer.Frame.Error -> throw LayerError(f.message)
        is WebLayer.Frame.Png -> android.util.Base64.decode(f.base64, android.util.Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    // ---------- preview and render ----------

    private fun preview() {
        val src = video ?: return
        if (busy.isNotEmpty()) return
        busy = "preview"
        cancel = false
        render()
        val d = layerData()
        Lumen.async({
            load(d)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(src.absolutePath)
                WebLayer.previewTimes(durMs).map { tt ->
                    BgTasks.checkpoint()
                    val pw = 360
                    val ph = pw * vh / vw
                    val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                    val c = Canvas(out)
                    val p = Paint(Paint.FILTER_BITMAP_FLAG)
                    c.drawColor(Color.BLACK)
                    r.getFrameAtTime((tt * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        c.drawBitmap(b, null, Rect(0, 0, pw, ph), p)
                        b.recycle()
                    }
                    overlay(jsAsync(call(tt))())?.let { o ->
                        c.drawBitmap(o, null, Rect(0, 0, pw, ph), p)
                        o.recycle()
                    }
                    out
                }
            } finally {
                runCatching { r.release() }
            }
        }, { e ->
            busy = ""
            lastError = e.message ?: e.javaClass.simpleName
            render()
            if (e !is LayerError) err(e)
        }) { shots ->
            busy = ""
            previews = shots
            lastError = ""
            render()
        }
    }

    private fun renderVideo() {
        val src = video ?: return
        if (busy.isNotEmpty()) return
        val problems = WebLayer.problems(code)
        if (problems.any { it.contains("frame") || it.contains("пуст") || it.contains("empty") }) return toast(problems.joinToString("; "))
        busy = "render"
        cancel = false
        result = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        val d = layerData()
        val out = File(dir, "Lumen_Layer.mp4")
        val started = System.currentTimeMillis()
        Lumen.async({
            load(d)
            status(t("Звук…", "Sound…"))
            val audio = runCatching {
                val pcm = StudioAudio.decode(src)
                StudioAudio.encodeAac(pcm.data, pcm.rate, { cancel }) { }
            }.getOrNull()
            val frames = Studio.frameCount(durMs, FPS)
            val enc = FrameEncoder(out, vw, vh, FPS, audio)
            val base = StudioFrames(src, vw, vh)
            var ok = false
            try {
                val baseArgb = IntArray(vw * vh) { 0xFF000000.toInt() }
                val outArgb = IntArray(vw * vh)
                val bmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val dst = Rect(0, 0, vw, vh)
                var next = jsAsync(call(0.0))
                for (f in 0 until frames) {
                    if (cancel) throw CancellationException()
                    // a heartbeat for the task manager (and its "Stop")
                    BgTasks.checkpoint()
                    base.advance(Studio.frameTimeUs(f, FPS), baseArgb, true)
                    val res = next()
                    // the WebView draws the next frame while this one is being composed and encoded
                    if (f + 1 < frames) next = jsAsync(call((f + 1).toDouble() / FPS))
                    val o = overlay(res)
                    if (o == null) {
                        enc.frame(f, baseArgb) { cancel }
                    } else {
                        bmp.setPixels(baseArgb, 0, vw, 0, 0, vw, vh)
                        canvas.drawBitmap(o, null, dst, paint)
                        o.recycle()
                        bmp.getPixels(outArgb, 0, vw, 0, 0, vw, vh)
                        enc.frame(f, outArgb) { cancel }
                    }
                    if (f % 10 == 0 || f == frames - 1) {
                        val spent = System.currentTimeMillis() - started
                        val left = if (f > 20) spent * (frames - f - 1) / (f + 1) / 1000 else -1
                        status(t("Кадры ${f + 1}/$frames", "Frames ${f + 1}/$frames") + if (left >= 0) t(" · осталось ~${left / 60}:${"%02d".format(left % 60)}", " · ~${left / 60}:${"%02d".format(left % 60)} left") else "")
                    }
                }
                enc.finish(frames)
                bmp.recycle()
                ok = true
            } finally {
                runCatching { base.close() }
                enc.close(ok)
            }
            status(t("Сохранение…", "Saving…"))
            StudioJob.saveToMovies(this, out, "Lumen_Layer_" + System.currentTimeMillis() / 1000 + ".mp4")
        }, { e ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (e is LayerError) lastError = e.message.orEmpty()
            render()
            if (e !is LayerError) err(e)
        }) { saved ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            result = out
            savedAs = saved
            render()
        }
    }

    private fun status(s: String) = runOnUiThread { statusView?.text = s }

    private fun openResult(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            ReportKit.share(this, f, false, f.name, "video/mp4")
        }
    }

    // ---------- screen ----------

    private fun render() {
        val c = Col(this)
        c.card {
            muted(t("Второй рендер: ИИ пишет JavaScript, который рисует слой анимации (плашки, титры, стрелки, счётчики) поверх готового видео. " +
                "Код работает в WebView без интернета, каждый кадр ложится на видео, звук сохраняется. Медленнее основной сборки: ~2–5 мин на минуту ролика, экран держите открытым.",
                "Second renderer: AI writes JavaScript that draws an animation layer (stickers, titles, arrows, counters) over a finished video. " +
                    "The code runs in a WebView without internet, every frame is laid over the video, the sound is kept. Slower than the main build: ~2–5 min per minute of video, keep the screen open."))
        }
        val v = video
        c.h2(t("Видео", "Video"))
        if (v != null) {
            c.muted(v.name + " · " + vw + "×" + vh + " · " + Studio.clock(durMs) +
                (if (data != null) t(" · речь и сцены из Студии", " · speech and scenes from the Studio") else t(" · без расшифровки", " · no transcript")))
        } else {
            c.muted(t("Видео не выбрано", "No video chosen"))
        }
        if (busy.isEmpty()) c.button(t("🎞 Выбрать другое видео", "🎞 Pick another video"), style = BtnStyle.TEXT) { pick() }
        if (busy == "copy") c.muted(t("Копирование видео…", "Copying the video…"))

        c.h2(t("Слой", "Layer"))
        val askBox = c.edit(t("Что нарисовать поверх (пожелание к ИИ)", "What to draw on top (wish for the AI)"), ask, multiline = true)
        fun saveAsk() {
            ask = askBox.text.toString().trim()
            Lumen.prefs.edit().putString("weblayer_ask", ask).apply()
        }
        if (busy.isEmpty()) {
            c.row(
                btn(this, t("✨ Написать слой (ИИ)", "✨ Write the layer (AI)"), primary = true) {
                    saveAsk()
                    aiWrite(false)
                },
                btn(this, t("⧉ Запрос для другого ИИ", "⧉ Request for another AI"), style = BtnStyle.TONAL) {
                    saveAsk()
                    copyRequest()
                }
            )
            c.row(
                btn(this, t("📥 Вставить код", "📥 Paste code"), style = BtnStyle.TONAL) { pasteCode() },
                btn(this, t("📄 Протокол", "📄 Protocol"), style = BtnStyle.TEXT) { showText("lumen.canvas v1", WebLayer.PROTOCOL) }
            )
            c.button(t("🧩 Пример слоя (без ИИ)", "🧩 Example layer (no AI)"), style = BtnStyle.TEXT) {
                setCode(WebLayer.EXAMPLE)
                render()
                if (video != null) preview()
            }
            c.check(t("Слой в полном разрешении (чётче, медленнее)", "Layer at full resolution (sharper, slower)"), full) {
                full = it
                previews = emptyList()
            }
        }
        if (busy == "ai") c.muted(t("ИИ пишет код слоя…", "AI is writing the layer code…"))

        if (code.isNotBlank()) {
            c.card {
                add(text(ctx, t("Код слоя · ", "Layer code · ") + Fmt.num(code.length.toLong()) + t(" симв.", " chars"), Type.TITLE_M, bold = true), 0)
                WebLayer.problems(code).forEach { muted("⚠ $it") }
                if (lastError.isNotBlank()) add(text(ctx, "✗ $lastError", 13f, color = Palette.BAD), 4)
                if (busy.isEmpty()) {
                    row(
                        btn(ctx, t("👁 Превью", "👁 Preview"), style = BtnStyle.TONAL) { preview() },
                        btn(ctx, t("🛠 Исправить (ИИ)", "🛠 Fix (AI)"), style = BtnStyle.TEXT) {
                            saveAsk()
                            aiWrite(true)
                        }
                    )
                    row(
                        btn(ctx, t("⧉ Код", "⧉ Code"), style = BtnStyle.TEXT) {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lumen", code))
                            toast(t("Код скопирован", "Code copied"))
                        },
                        btn(ctx, t("✎ Править", "✎ Edit"), style = BtnStyle.TEXT) {
                            prompt(t("Код слоя", "Layer code"), "", code, multiline = true) {
                                setCode(it)
                                render()
                            }
                        }
                    )
                }
            }
        }

        if (previews.isNotEmpty()) {
            c.add(text(this, t("Превью: 4 момента ролика", "Preview: 4 moments"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val row = LinearLayout(this)
            previews.forEach { b ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(b)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            c.add(row, 4)
        }

        // the WebView itself: the live picture of the layer while it previews and renders
        if (busy == "preview" || busy == "render") {
            c.add(text(this, t("Слой сейчас", "The layer now"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val w = webView()
            (w.parent as? ViewGroup)?.removeView(w)
            val box = FrameLayout(this).apply { setBackgroundColor(0xFF202024.toInt()) }
            val bw = dp(180)
            box.addView(w, FrameLayout.LayoutParams(bw, if (vw > 0) bw * vh / vw else bw))
            c.add(box, 4)
        } else {
            web?.let { w -> (w.parent as? ViewGroup)?.removeView(w) }
        }

        if (busy == "render" || busy == "preview") {
            statusView = c.add(text(this, if (busy == "preview") t("Превью…", "Preview…") else t("Подготовка…", "Preparing…"), 14f), 8) as TextView
            c.button(t("Остановить", "Stop"), style = BtnStyle.OUTLINED) { cancel = true }
        } else if (busy.isEmpty() && v != null && code.isNotBlank()) {
            c.button(t("🎬 Рендер со слоем", "🎬 Render with the layer"), primary = true) { renderVideo() }
        }

        result?.takeIf { it.exists() }?.let { f ->
            c.p("✅ " + t("Готово: ", "Done: ") + savedAs.ifBlank { f.name })
            c.row(
                btn(this, t("▶ Открыть", "▶ Open"), style = BtnStyle.TONAL) { openResult(f) },
                btn(this, t("Поделиться", "Share"), style = BtnStyle.TONAL) { ReportKit.share(this, f, false, f.name, "video/mp4") }
            )
        }
        page(t("🧪 JS-слой (WebView)", "🧪 JS layer (WebView)"), c.root, keepScroll = true)
    }
}

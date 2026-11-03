/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException


private typealias LayerError = CanvasHost.LayerError

/**
 * Studio tab "🎛 Montage": the hand editor ([StudioEditorActivity]) and the second renderer (lumen.canvas v1,
 * [WebLayer]) — AI-written canvas JavaScript drawn in a WebView without network frame by frame over a finished video.
 * Every layer that comes in (built-in AI, pasted answer of another AI, example, fixes) is kept in [LayerStore], so
 * different montages can be switched, compared side by side on the same video and rendered.
 */
class WebLayerActivity : LumenBase() {
    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_SUBS = "subs"
        private const val REQ_PICK = 7410
        private const val FPS = 30
        private const val COMPARE_MAX = 8

        fun dir(ctx: Context) = File(ctx.filesDir, "lumen/studio/weblayer").apply { mkdirs() }

        /** [video] (its speech may be in dir/data.json, written by the Studio); null = the last video of this screen. */
        fun open(ctx: Context, video: File?, subtitles: Boolean) {
            ctx.startActivity(Intent(ctx, WebLayerActivity::class.java).putExtra(EXTRA_VIDEO, video?.absolutePath).putExtra(EXTRA_SUBS, subtitles))
        }
    }


    private val dir by lazy { dir(this) }
    private val store by lazy { LayerStore(File(dir, "layers")) }
    private var video: File? = null
    private var data: String? = null
    private var subs = false
    private var vw = 0
    private var vh = 0
    private var durMs = 0L
    private var active: SavedLayer? = null
    private val code: String get() = active?.code.orEmpty()
    private var ask = ""
    private var busy = ""
    @Volatile private var cancel = false
    private var lastError = ""
    private var previews: List<Bitmap> = emptyList()
    private var compare: List<Pair<SavedLayer, List<Bitmap>>> = emptyList()
    private var found: String? = null
    private var copied: String? = null
    private var libraryOpen = true
    private var result: File? = null
    private var savedAs = ""
    private var statusView: TextView? = null
    private val host by lazy { CanvasHost(this) }

    private var full: Boolean
        get() = Lumen.prefs.getBoolean("weblayer_full", false)
        set(v) = Lumen.prefs.edit().putBoolean("weblayer_full", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ask = Lumen.prefs.getString("weblayer_ask", "") ?: ""
        // the single layer of the first version becomes the first template
        File(dir, "last.js").takeIf { it.exists() }?.let { f ->
            if (store.list().isEmpty() && f.readText().isNotBlank()) store.add(f.readText(), "", "edit", f.lastModified())
            f.delete()
        }
        active = Lumen.prefs.getString("weblayer_active", null)?.let { store.get(it) } ?: store.list().firstOrNull()
        val given = intent.getStringExtra(EXTRA_VIDEO)?.let { File(it) }?.takeIf { it.exists() }
        if (given != null) {
            subs = intent.getBooleanExtra(EXTRA_SUBS, false)
            Lumen.prefs.edit().putString("weblayer_video", given.absolutePath).putBoolean("weblayer_subs", subs).apply()
            setVideo(given)
        } else {
            subs = Lumen.prefs.getBoolean("weblayer_subs", false)
            Lumen.prefs.getString("weblayer_video", null)?.let { File(it) }?.takeIf { it.exists() }?.let { setVideo(it) }
        }
        render()
    }

    override fun onDestroy() {
        cancel = true
        host.destroy()
        super.onDestroy()
    }

    // ---------- video ----------

    /** Speech and scenes written by the Studio for this very file (data.json carries its path). */
    private fun dataFor(f: File): String? = File(dir, "data.json").takeIf { it.exists() }?.readText()
        ?.takeIf { runCatching { JSONObject(it).optString("video") }.getOrNull() == f.absolutePath }

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
            video = null
            toast(t("Не удалось прочитать видео: ", "Could not read the video: ") + e.message)
        } finally {
            runCatching { r.release() }
        }
        data = video?.let { dataFor(it) }
        previews = emptyList()
        compare = emptyList()
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
            subs = false
            Lumen.prefs.edit().putString("weblayer_video", f.absolutePath).putBoolean("weblayer_subs", false).apply()
            setVideo(f)
            render()
        }
    }

    // ---------- templates ----------

    /** New code: a new template ("ai", "paste", "example") or a new version of the active one ("fix", "edit"). */
    private fun takeCode(raw: String, source: String) {
        val c = raw.trim()
        if (c.isEmpty()) return
        val now = System.currentTimeMillis()
        val cur = active
        active = if ((source == "fix" || source == "edit") && cur != null) store.update(cur.id, c, now) else store.add(c, ask, source, now)
        active?.let { Lumen.prefs.edit().putString("weblayer_active", it.id).apply() }
        previews = emptyList()
        lastError = ""
    }

    private fun activate(l: SavedLayer, show: Boolean) {
        active = l
        Lumen.prefs.edit().putString("weblayer_active", l.id).apply()
        previews = emptyList()
        lastError = ""
        render()
        if (show && video != null) preview()
    }

    private fun sourceLabel(s: String) = when (s) {
        "ai" -> t("встроенный ИИ", "built-in AI")
        "paste" -> t("другой ИИ", "another AI")
        "example" -> t("пример", "example")
        else -> t("правка", "edit")
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
            takeCode(WebLayer.extract(answer), if (fix) "fix" else "ai")
            render()
            if (WebLayer.problems(code).isEmpty()) preview()
        }
    }

    private fun clipText(): String? = try {
        getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }

    private fun copy(s: String) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lumen", s))

    /** The request for any chat: with the data of this video, or a reusable template without it. */
    private fun requestSheet() = bottomSheet(t("Запрос для другого ИИ", "Request for another AI")) { dismiss ->
        muted(t("Скопируйте запрос, вставьте в Gemini, ChatGPT или Claude, скопируйте ответ и вернитесь — Lumen сам найдёт код в буфере и сохранит его шаблоном. " +
            "Просите варианты сколько угодно: каждый ответ — отдельный шаблон.",
            "Copy the request, paste it into Gemini, ChatGPT or Claude, copy the answer and come back — Lumen finds the code in the clipboard and keeps it as a template. " +
                "Ask for as many variants as you like: every answer is its own template."))
        button(t("⧉ Под это видео (с речью и сценами)", "⧉ For this video (with speech and scenes)"), primary = true) {
            if (video == null) return@button toast(t("Сначала выберите видео", "Pick a video first"))
            dismiss()
            val s = RelayText.request("studio_weblayer", system, WebLayer.brief(layerData(), ask, subs), false, null, false, "")
            copy(s)
            copied = s
            toast(t("Запрос скопирован", "Request copied"))
        }
        button(t("⧉ Универсальный шаблон (для любого ролика)", "⧉ Reusable template (for any video)"), style = BtnStyle.TONAL) {
            dismiss()
            val s = RelayText.request("studio_weblayer", system, WebLayer.templateBrief(ask, video == null || vh >= vw), false, null, false, "")
            copy(s)
            copied = s
            toast(t("Запрос скопирован", "Request copied"))
        }
        row(
            btn(ctx, "Gemini", style = BtnStyle.OUTLINED) { openUrl("https://gemini.google.com/app") },
            btn(ctx, "ChatGPT", style = BtnStyle.OUTLINED) { openUrl("https://chatgpt.com/") },
            btn(ctx, "Claude", style = BtnStyle.OUTLINED) { openUrl("https://claude.ai/new") }
        )
    }

    private fun paste(text: String?) {
        val s = text.orEmpty()
        if (!WebLayer.looksLikeLayer(s)) return toast(t("В буфере нет кода слоя (function frame)", "No layer code (function frame) in the clipboard"))
        found = null
        copied = s
        takeCode(WebLayer.extract(s), "paste")
        toast(t("Сохранено шаблоном: ", "Kept as a template: ") + active?.name)
        render()
        if (WebLayer.problems(code).isEmpty() && video != null) preview()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets an app read the clipboard only while it has focus
        if (!hasFocus || busy.isNotEmpty()) return
        val c = clipText() ?: return
        if (c == found || c == copied || !WebLayer.looksLikeLayer(c)) return
        val extracted = WebLayer.extract(c).trim()
        if (store.list().any { it.code.trim() == extracted }) return
        found = c
        render()
    }

    // ---------- WebView ----------

    private fun webView(): WebView = host.view()

    /** Runs [script] on the main thread; the returned function waits (worker thread) for its result. */
    private fun jsAsync(script: String): () -> String? = host.jsAsync(script)

    /** Loads the page with [layerCode] and calls init(data); throws [LayerError] on a broken layer (worker thread). */
    private fun load(layerCode: String, d: JSONObject) = host.load(layerCode, d)

    private fun call(sec: Double) = CanvasHost.call(sec)

    private fun overlay(res: String?): Bitmap? = host.overlay(res)

    // ---------- preview, comparison, render ----------

    /** Frames of [layerCode] over the video at [times] seconds, [pw] pixels wide (worker thread). */
    private fun shots(layerCode: String, times: List<Double>, pw: Int): List<Bitmap> {
        val src = video ?: return emptyList()
        load(layerCode, layerData())
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(src.absolutePath)
            return times.map { sec ->
                BgTasks.checkpoint()
                if (cancel) throw CancellationException()
                val ph = pw * vh / vw
                val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val p = Paint(Paint.FILTER_BITMAP_FLAG)
                c.drawColor(Color.BLACK)
                r.getFrameAtTime((sec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    c.drawBitmap(b, null, Rect(0, 0, pw, ph), p)
                    b.recycle()
                }
                overlay(jsAsync(call(sec))())?.let { o ->
                    c.drawBitmap(o, null, Rect(0, 0, pw, ph), p)
                    o.recycle()
                }
                out
            }
        } finally {
            runCatching { r.release() }
        }
    }

    private fun preview() {
        if (video == null || busy.isNotEmpty() || code.isBlank()) return
        busy = "preview"
        cancel = false
        render()
        val layerCode = code
        Lumen.async({ shots(layerCode, WebLayer.previewTimes(durMs), 360) }, { e ->
            busy = ""
            lastError = e.message ?: e.javaClass.simpleName
            render()
            if (e !is LayerError) err(e)
        }) { s ->
            busy = ""
            previews = s
            lastError = ""
            render()
        }
    }

    /** Up to [COMPARE_MAX] templates side by side at the same three moments of this video. */
    private fun compareAll() {
        if (video == null || busy.isNotEmpty()) return
        val layers = store.list().take(COMPARE_MAX)
        if (layers.size < 2) return toast(t("Нужно хотя бы два шаблона", "At least two templates are needed"))
        busy = "compare"
        cancel = false
        render()
        val times = WebLayer.previewTimes(durMs).let { listOf(it[0], it[1], it[3]) }
        Lumen.async({
            layers.map { l ->
                status(t("Сравнение: ", "Comparing: ") + l.name)
                l to try {
                    shots(l.code, times, 200)
                } catch (e: LayerError) {
                    emptyList()
                }
            }
        }, { e ->
            busy = ""
            render()
            err(e)
        }) { rows ->
            busy = ""
            compare = rows
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
        val layerCode = code
        val out = File(dir, "Lumen_Montage.mp4")
        val started = System.currentTimeMillis()
        Lumen.async({
            load(layerCode, d)
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
                        val eta = if (left >= 0) "${left / 60}:" + "%02d".format(left % 60) else ""
                        status(t("Кадры ${f + 1}/$frames", "Frames ${f + 1}/$frames") + if (eta.isNotEmpty()) t(" · осталось ~$eta", " · ~$eta left") else "")
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
            StudioJob.saveToMovies(this, out, "Lumen_Montage_" + System.currentTimeMillis() / 1000 + ".mp4")
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

    private fun thumbs(shots: List<Bitmap>): LinearLayout = LinearLayout(this).apply {
        shots.forEach { b ->
            addView(ImageView(this@WebLayerActivity).apply {
                setImageBitmap(b)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
    }

    private fun render() {
        val c = Col(this)
        val v = video
        val idle = busy.isEmpty()

        // 1. the hand editor lives in this tab too
        c.card {
            add(text(ctx, t("✏️ Ручной редактор", "✏️ Hand editor"), Type.TITLE_M, bold = true), 0)
            muted(t("Таймлайн готового ролика: вставляйте инфосхемы, картинки, надписи и эмодзи в любой момент, двигайте и обрезайте блоки.",
                "Timeline of a finished video: insert info-schemes, pictures, captions and emoji at any moment, move and trim the blocks."))
            row(
                btn(ctx, if (v != null) t("Открыть с этим видео", "Open with this video") else t("Открыть", "Open"), style = BtnStyle.TONAL) {
                    if (v != null) StudioEditorActivity.open(this@WebLayerActivity, v) else StudioEditorActivity.open(this@WebLayerActivity)
                },
                btn(ctx, t("Проекты редактора", "Editor projects"), style = BtnStyle.TEXT) { StudioEditorActivity.open(this@WebLayerActivity) }
            )
        }

        // 2. the video
        c.h2(t("🧪 JS-монтаж по протоколу", "🧪 JS montage by the protocol"))
        c.muted(t("ИИ пишет код слоя анимации (lumen.canvas), Lumen рисует его поверх видео в WebView без интернета. Каждый ответ ИИ сохраняется шаблоном — пробуйте разные монтажи на одном видео.",
            "AI writes the code of an animation layer (lumen.canvas), Lumen draws it over the video in a WebView without internet. Every AI answer is kept as a template — try different montages on one video."))
        if (v != null) {
            c.p("🎞 " + v.name + " · " + vw + "×" + vh + " · " + Studio.clock(durMs) +
                (if (data != null) t(" · речь и сцены из Студии", " · speech and scenes from the Studio") else t(" · без расшифровки", " · no transcript")), 14f)
        } else {
            c.muted(t("Видео не выбрано: соберите ролик в Студии или выберите любое видео.", "No video: make one in the Studio or pick any video."))
        }
        if (idle) {
            val last = StudioJob.result?.takeIf { it.exists() && it.absolutePath != v?.absolutePath }
            c.row(*listOfNotNull(
                btn(this, t("🎞 Выбрать видео", "🎞 Pick a video"), style = BtnStyle.TONAL) { pick() },
                last?.let { f ->
                    btn(this, t("🎬 Последний ролик Студии", "🎬 Last Studio video"), style = BtnStyle.TEXT) {
                        Lumen.prefs.edit().putString("weblayer_video", f.absolutePath).apply()
                        setVideo(f)
                        render()
                    }
                }
            ).toTypedArray())
        }
        if (busy == "copy") c.muted(t("Копирование видео…", "Copying the video…"))

        // 3. an answer of another AI waiting in the clipboard
        found?.let { f ->
            c.card {
                add(text(ctx, t("📋 В буфере код слоя от ИИ", "📋 Layer code from an AI in the clipboard"), Type.TITLE_M, bold = true), 0)
                muted(WebLayer.nameOf(WebLayer.extract(f), f.take(60)))
                row(
                    btn(ctx, t("✓ Сохранить и проверить", "✓ Keep and preview"), primary = true) { paste(f) },
                    btn(ctx, t("Не то", "Not this"), style = BtnStyle.TEXT) {
                        copied = f
                        found = null
                        render()
                    }
                )
            }
        }

        // 4. a new montage
        val askBox = c.edit(t("Каким сделать монтаж (пожелание к ИИ)", "What montage to make (wish for the AI)"), ask, multiline = true)
        fun saveAsk() {
            ask = askBox.text.toString().trim()
            Lumen.prefs.edit().putString("weblayer_ask", ask).apply()
        }
        if (idle) {
            c.row(
                btn(this, t("⧉ Запрос для другого ИИ", "⧉ Request for another AI"), primary = true) {
                    saveAsk()
                    requestSheet()
                },
                btn(this, t("📥 Вставить ответ", "📥 Paste the answer"), style = BtnStyle.TONAL) {
                    saveAsk()
                    paste(clipText())
                }
            )
            c.row(
                btn(this, t("✨ Встроенный ИИ", "✨ Built-in AI"), style = BtnStyle.TONAL) {
                    saveAsk()
                    aiWrite(false)
                },
                btn(this, t("🧩 Пример", "🧩 Example"), style = BtnStyle.TEXT) {
                    takeCode(WebLayer.EXAMPLE, "example")
                    render()
                    if (video != null) preview()
                },
                btn(this, t("📄 Протокол", "📄 Protocol"), style = BtnStyle.TEXT) { showText("lumen.canvas v1", WebLayer.PROTOCOL) }
            )
            c.check(t("Слой в полном разрешении (чётче, медленнее)", "Layer at full resolution (sharper, slower)"), full) {
                full = it
                previews = emptyList()
                compare = emptyList()
            }
        }
        if (busy == "ai") c.muted(t("ИИ пишет код слоя…", "AI is writing the layer code…"))

        // 5. the template in use
        active?.let { a ->
            c.card {
                add(text(ctx, "▶ " + a.name, Type.TITLE_M, bold = true), 0)
                muted(sourceLabel(a.source) + " · " + Fmt.dateTime(a.updated) + " · " + Fmt.num(a.code.length.toLong()) + t(" симв.", " chars") +
                    if (a.ask.isNotBlank()) " · «" + a.ask.take(60) + "»" else "")
                WebLayer.problems(a.code).forEach { muted("⚠ $it") }
                if (lastError.isNotBlank()) add(text(ctx, "✗ $lastError", 13f, color = Palette.BAD), 4)
                if (idle) {
                    row(
                        btn(ctx, t("👁 Превью", "👁 Preview"), style = BtnStyle.TONAL) { preview() },
                        btn(ctx, t("🛠 Исправить (ИИ)", "🛠 Fix (AI)"), style = BtnStyle.TEXT) {
                            saveAsk()
                            aiWrite(true)
                        }
                    )
                    row(
                        btn(ctx, t("⧉ Код", "⧉ Code"), style = BtnStyle.TEXT) {
                            copy(a.code)
                            copied = a.code
                            toast(t("Код скопирован", "Code copied"))
                        },
                        btn(ctx, t("✎ Править", "✎ Edit"), style = BtnStyle.TEXT) {
                            prompt(t("Код слоя", "Layer code"), "", a.code, multiline = true) {
                                takeCode(it, "edit")
                                render()
                            }
                        },
                        btn(ctx, t("Имя", "Name"), style = BtnStyle.TEXT) {
                            prompt(t("Название шаблона", "Template name"), "", a.name) { n ->
                                active = store.rename(a.id, n) ?: a
                                render()
                            }
                        }
                    )
                    if (lastError.isNotBlank()) button(t("⧉ Ошибку — в другой ИИ", "⧉ The error — to another AI"), style = BtnStyle.TEXT) {
                        val s = RelayText.request("studio_weblayer_fix", system, WebLayer.fixBrief(a.code, lastError, ask), false, null, false, "")
                        copy(s)
                        copied = s
                        toast(t("Запрос на исправление скопирован — ответ вставьте «📥 Вставить ответ»", "Fix request copied — paste the answer with \"📥 Paste the answer\""))
                    }
                }
            }
        }
        if (previews.isNotEmpty()) {
            c.add(text(this, t("Превью: 4 момента ролика", "Preview: 4 moments"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            c.add(thumbs(previews), 4)
        }

        // 6. render
        if (busy == "preview" || busy == "render" || busy == "compare") {
            c.add(text(this, t("Слой сейчас", "The layer now"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val w = webView()
            (w.parent as? ViewGroup)?.removeView(w)
            val box = FrameLayout(this).apply { setBackgroundColor(0xFF202024.toInt()) }
            val bw = dp(150)
            box.addView(w, FrameLayout.LayoutParams(bw, if (vw > 0) bw * vh / vw else bw))
            c.add(box, 4)
            statusView = c.add(text(this, when (busy) {
                "preview" -> t("Превью…", "Preview…")
                "compare" -> t("Сравнение…", "Comparing…")
                else -> t("Подготовка…", "Preparing…")
            }, 14f), 8) as TextView
            c.button(t("Остановить", "Stop"), style = BtnStyle.OUTLINED) { cancel = true }
        } else {
            host.detach()
            if (idle && v != null && code.isNotBlank()) c.button(t("🎬 Рендер с этим монтажом", "🎬 Render with this montage"), primary = true) { renderVideo() }
        }
        result?.takeIf { it.exists() }?.let { f ->
            c.p("✅ " + t("Готово: ", "Done: ") + savedAs.ifBlank { f.name })
            c.row(
                btn(this, t("▶ Открыть", "▶ Open"), style = BtnStyle.TONAL) { openResult(f) },
                btn(this, t("Поделиться", "Share"), style = BtnStyle.TONAL) { ReportKit.share(this, f, false, f.name, "video/mp4") }
            )
        }

        // 7. the library of every template tried
        val all = store.list()
        c.section(t("📚 Шаблоны монтажа", "📚 Montage templates"), all.size.toString(), libraryOpen, {
            libraryOpen = it
            render()
        }) {
            if (all.isEmpty()) muted(t("Пока пусто: здесь появится каждый слой — от встроенного ИИ, из ответа другого ИИ, пример и правки.",
                "Empty for now: every layer lands here — from the built-in AI, from another AI's answer, the example and edits."))
            if (all.size >= 2 && idle && v != null) button(t("⇄ Сравнить на этом видео (до $COMPARE_MAX)", "⇄ Compare on this video (up to $COMPARE_MAX)"),
                style = BtnStyle.TONAL) { compareAll() }
            all.forEach { l ->
                val on = l.id == active?.id
                card {
                    add(text(ctx, (if (on) "● " else "") + l.name, 15f, bold = true), 0)
                    muted(sourceLabel(l.source) + " · " + Fmt.dateTime(l.updated) + if (l.ask.isNotBlank()) " · «" + l.ask.take(50) + "»" else "")
                    compare.firstOrNull { it.first.id == l.id }?.second?.let { s ->
                        if (s.isEmpty()) muted(t("✗ не запустился", "✗ did not start")) else add(thumbs(s), 4)
                    }
                    if (idle) row(
                        btn(ctx, if (on) t("👁 Превью", "👁 Preview") else t("▶ Выбрать", "▶ Use"), style = BtnStyle.TONAL) { activate(l, true) },
                        btn(ctx, "⧉", style = BtnStyle.TEXT) {
                            copy(l.code)
                            copied = l.code
                            toast(t("Код скопирован", "Code copied"))
                        },
                        btn(ctx, "🗑", style = BtnStyle.TEXT) {
                            confirm(t("Удалить «${l.name}»?", "Delete \"${l.name}\"?"), "", t("Удалить", "Delete")) {
                                store.delete(l.id)
                                if (on) active = store.list().firstOrNull()
                                compare = compare.filter { it.first.id != l.id }
                                render()
                            }
                        }
                    )
                }
            }
        }
        page(t("🎛 Монтаж", "🎛 Montage"), c.root, keepScroll = true)
    }
}

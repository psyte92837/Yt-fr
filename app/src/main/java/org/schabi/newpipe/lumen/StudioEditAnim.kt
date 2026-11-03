/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * A WebView without network that runs canvas code by lumen.canvas v1 / lumen.anim v1 ([WebLayer.page]): load the
 * code with its data, then ask for frames. Shared by the Montage tab and the animation blocks of the editor.
 * The WebView must sit in a window while it draws (see [attach]); calls that wait run on a worker thread.
 */
class CanvasHost(private val act: Activity) {
    class LayerError(msg: String) : Exception(msg)

    private var web: WebView? = null
    private var pageReady: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView = web ?: WebView(act).also { w ->
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

    /** Puts the WebView into [parent] (main thread), [wPx]×[hPx]. */
    fun attach(parent: ViewGroup, wPx: Int, hPx: Int) {
        val w = view()
        if (w.parent === parent) return
        (w.parent as? ViewGroup)?.removeView(w)
        parent.addView(w, if (parent is FrameLayout) FrameLayout.LayoutParams(wPx, hPx) else ViewGroup.LayoutParams(wPx, hPx))
    }

    fun detach() {
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w) }
    }

    val attached: Boolean get() = web?.parent != null

    fun destroy() {
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        web = null
    }

    /** Runs [script] on the main thread; the returned function waits (worker thread) for its result. */
    fun jsAsync(script: String): () -> String? {
        val q = ArrayBlockingQueue<String>(1)
        act.runOnUiThread {
            val w = web
            if (w == null) q.offer("null") else w.evaluateJavascript(script) { q.offer(it ?: "null") }
        }
        return { q.poll(30, TimeUnit.SECONDS) }
    }

    /** Loads the page with [code] and calls init(data); throws [LayerError] on broken code (worker thread). */
    fun load(code: String, d: JSONObject) {
        val latch = CountDownLatch(1)
        val html = WebLayer.page(code, d.optInt("w"), d.optInt("h"))
        act.runOnUiThread {
            pageReady = { latch.countDown() }
            view().loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw LayerError(t("страница слоя не загрузилась", "the layer page did not load"))
        val r = jsAsync("__lumen.init(" + d.toString() + ")")() ?: throw LayerError(t("WebView не ответил", "The WebView gave no answer"))
        val s = runCatching { JSONArray("[$r]").getString(0) }.getOrDefault(r)
        if (s != "OK") throw LayerError(s.removePrefix("ERR:").ifBlank { t("слой не запустился", "the layer did not start") })
    }

    /** Asks for the frame at [sec]; the returned function waits for it. */
    fun frame(sec: Double): () -> String? = jsAsync(call(sec))

    /** A frame as a picture (null = nothing drawn); throws [LayerError] on an error of the code. */
    fun overlay(res: String?): Bitmap? = png(res)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** A frame as PNG bytes (null = nothing drawn); throws [LayerError] on an error of the code. */
    fun png(res: String?): ByteArray? = when (val f = WebLayer.frameOf(res)) {
        is WebLayer.Frame.Empty -> null
        is WebLayer.Frame.Error -> throw LayerError(f.message)
        is WebLayer.Frame.Png -> android.util.Base64.decode(f.base64, android.util.Base64.DEFAULT)
    }

    companion object {
        fun call(sec: Double) = "__lumen.frame(" + String.format(java.util.Locale.US, "%.4f", sec) + ")"
    }
}

/** Renders of animation blocks: PNG frames of lumen.anim v1 code in the project folder, and quick preview shots. */
object AnimRender {
    /** What a render of an animation found: time per frame and frames with nothing drawn. */
    data class Result(val msPerFrame: Long, val count: Int, val empty: Int)

    /**
     * Renders [code] with [data] into [out] (frames f00000.png… and meta.json written last), [w]×[h] px, [durMs] at
     * [fps] (one cycle when [loopMs] > 0). The folder counts as done only with its meta.json, so a render stopped half
     * way is simply made again. Worker thread; [host] must be attached to a window.
     */
    fun run(
        host: CanvasHost, code: String, data: JSONObject, w: Int, h: Int, fps: Int, durMs: Long, loopMs: Long, out: File,
        cancelled: () -> Boolean, progress: (Int, Int) -> Unit
    ): Result {
        out.mkdirs()
        val n = EditInsert.frameCount(durMs, fps, loopMs)
        host.load(code, data.put("w", w).put("h", h).put("fps", fps))
        val emptyList = mutableListOf<Int>()
        val started = System.currentTimeMillis()
        var next = host.frame(0.0)
        for (f in 0 until n) {
            if (cancelled()) throw CancellationException()
            BgTasks.checkpoint()
            val res = next()
            // the WebView draws the next frame while this one is written
            if (f + 1 < n) next = host.frame((f + 1).toDouble() / fps)
            val png = host.png(res)
            if (png == null) emptyList += f else File(out, EditAnim.frameName(f)).writeBytes(png)
            if (f % 5 == 0 || f == n - 1) progress(f + 1, n)
        }
        File(out, EditAnim.META).writeText(EditAnim.metaJson(w, h, fps, durMs, n, loopMs, emptyList).toString())
        return Result((System.currentTimeMillis() - started) / max(1, n), n, emptyList.size)
    }

    /** Frames of [code] at [times] seconds of the block (null = nothing drawn), with the slowest frame in ms (worker thread). */
    fun shots(host: CanvasHost, code: String, data: JSONObject, times: List<Double>): Pair<List<Bitmap?>, Long> {
        host.load(code, data)
        var slow = 0L
        val list = times.map { sec ->
            BgTasks.checkpoint()
            val t0 = System.currentTimeMillis()
            val r = host.frame(sec)()
            slow = max(slow, System.currentTimeMillis() - t0)
            host.overlay(r)
        }
        return list to slow
    }
}

/** Decoded frames of rendered animations for the painter: meta of each render and a few last frames. */
class AnimFrames(private val dir: File) {
    data class Meta(val w: Int, val h: Int, val fps: Int, val durMs: Long, val count: Int, val loopMs: Long, val empty: Set<Int>)

    private val metas = HashMap<String, Pair<Meta?, Long>>()
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 14
    }

    /** Meta of the render in [file] (relative to the project), null while it is not finished (checked again each second). */
    fun meta(file: String): Meta? {
        val now = System.currentTimeMillis()
        metas[file]?.let { (m, at) -> if (m != null || now - at < 1000) return m }
        val m = runCatching {
            val o = JSONObject(File(dir, "$file/${EditAnim.META}").readText())
            val e = o.optJSONArray("empty")
            Meta(o.optInt("w"), o.optInt("h"), o.optInt("fps", EditInsert.FPS), o.optLong("dur"), o.optInt("count"), o.optLong("loop"),
                (0 until (e?.length() ?: 0)).map { e!!.optInt(it) }.toSet())
        }.getOrNull()
        metas[file] = m to now
        return m
    }

    /** Frame [idx] of [file] for a box of [bw]×[bh] px (decoded smaller when the box is much smaller); null = nothing drawn. */
    fun get(file: String, idx: Int, bw: Float, bh: Float): Bitmap? {
        val m = meta(file) ?: return null
        if (idx < 0 || idx in m.empty) return null
        var sample = 1
        while (m.w / (sample * 2) >= bw && m.h / (sample * 2) >= bh) sample *= 2
        val key = "$file#$idx#$sample"
        cache[key]?.let { return it }
        val f = File(dir, "$file/" + EditAnim.frameName(idx))
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache[key] = bmp
        return bmp
    }

    fun clear() {
        cache.clear()
        metas.clear()
    }
}

/** A saved result of the wizard: an animation (code) or a scheme (JSON) with the brief it was made by. */
data class SavedInsert(val id: String, val name: String, val kind: String, val payload: String, val brief: String, val source: String, val created: Long)

/**
 * The wizard's library in files/lumen/studio/edit/library (animations and schemes, no duplicates) and recipes —
 * briefs without content — in files/lumen/studio/edit/recipes.
 */
object InsertStore {
    private fun lib(ctx: Context) = File(ctx.filesDir, "lumen/studio/edit/library").apply { mkdirs() }
    private fun rec(ctx: Context) = File(ctx.filesDir, "lumen/studio/edit/recipes").apply { mkdirs() }

    fun list(ctx: Context): List<SavedInsert> = lib(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            SavedInsert(f.nameWithoutExtension, o.optString("name"), o.optString("kind"), o.optString("payload"), o.optString("brief"),
                o.optString("source"), o.optLong("created"))
        }.getOrNull()
    }.sortedByDescending { it.created }

    /** Saves a result; the same payload is not kept twice (its name is updated instead). */
    fun add(ctx: Context, kind: String, name: String, payload: String, brief: String, source: String): SavedInsert {
        val same = list(ctx).firstOrNull { it.payload.trim() == payload.trim() }
        val id = same?.id ?: ("i" + System.currentTimeMillis().toString(36))
        val s = SavedInsert(id, name.trim().take(40).ifBlank { t("Без названия", "Untitled") }, kind, payload, brief, source, same?.created ?: System.currentTimeMillis())
        File(lib(ctx), "$id.json").writeText(JSONObject().put("protocol", EditInsert.PROTOCOL).put("name", s.name).put("kind", kind)
            .put("payload", payload).put("brief", brief).put("source", source).put("created", s.created).toString())
        return s
    }

    fun rename(ctx: Context, id: String, name: String) {
        val f = File(lib(ctx), "$id.json")
        runCatching { f.writeText(JSONObject(f.readText()).put("name", name.trim().take(40)).toString()) }
    }

    fun delete(ctx: Context, id: String) = File(lib(ctx), "$id.json").delete()

    fun recipes(ctx: Context): List<Triple<String, String, EditBrief>> = rec(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.lastModified() }.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Triple(f.nameWithoutExtension, o.optString("name"), EditInsert.briefFrom(o.getJSONObject("brief")))
            }.getOrNull()
        }

    fun addRecipe(ctx: Context, name: String, b: EditBrief) {
        val id = "r" + System.currentTimeMillis().toString(36)
        File(rec(ctx), "$id.json").writeText(JSONObject().put("name", name.trim().take(40).ifBlank { t("Рецепт", "Recipe") })
            .put("brief", EditInsert.briefJson(EditInsert.recipe(b))).toString())
    }

    fun deleteRecipe(ctx: Context, id: String) = File(rec(ctx), "$id.json").delete()
}

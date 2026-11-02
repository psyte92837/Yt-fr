/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The WebView that runs canvas JavaScript of Lumen (lumen.canvas v1 of the Montage, lumen.anim v1 of the editor) frame by
 * frame, without network. It must be in the window of [act] while it draws; [load] and [frame] wait on a worker thread.
 */
class CanvasHost(private val act: Activity) {
    /** The layer code is broken: the message is for the author (and the AI that fixes it). */
    class LayerError(msg: String) : Exception(msg)

    private var web: WebView? = null
    private var pageReady: (() -> Unit)? = null

    val created: Boolean get() = web != null

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

    /** Puts the WebView into [parent] (it draws only while in the window). */
    fun attach(parent: ViewGroup, lp: ViewGroup.LayoutParams) {
        val w = view()
        if (w.parent === parent) return
        (w.parent as? ViewGroup)?.removeView(w)
        parent.addView(w, lp)
    }

    fun detach() {
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w) }
    }

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
            if (w == null || act.isDestroyed) q.offer("null") else w.evaluateJavascript(script) { q.offer(it ?: "null") }
        }
        return { q.poll(30, TimeUnit.SECONDS) }
    }

    /** Loads the page with [code] and calls init(data); throws [LayerError] on a broken layer (worker thread). */
    fun load(code: String, d: JSONObject) {
        val latch = CountDownLatch(1)
        val html = WebLayer.page(code, d.optInt("w"), d.optInt("h"))
        act.runOnUiThread {
            pageReady = { latch.countDown() }
            view().loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw LayerError(t("страница слоя не загрузилась", "the layer page did not load"))
        val r = jsAsync("__lumen.init(" + d.toString() + ")")() ?: throw LayerError(t("WebView не ответил", "The WebView gave no answer"))
        val s = runCatching { org.json.JSONArray("[$r]").getString(0) }.getOrDefault(r)
        if (s != "OK") throw LayerError(s.removePrefix("ERR:").ifBlank { t("слой не запустился", "the layer did not start") })
    }

    /** Asks the frame at [sec] seconds; the result (PNG data URL, "" or "ERR:…") is waited for later. */
    fun frame(sec: Double): () -> String? = jsAsync("__lumen.frame(" + String.format(java.util.Locale.US, "%.4f", sec) + ")")

    /** The picture of a frame result, null when nothing was drawn; throws [LayerError] on a JS error. */
    fun bitmap(res: String?): Bitmap? = when (val f = WebLayer.frameOf(res)) {
        is WebLayer.Frame.Empty -> null
        is WebLayer.Frame.Error -> throw LayerError(f.message)
        is WebLayer.Frame.Png -> android.util.Base64.decode(f.base64, android.util.Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

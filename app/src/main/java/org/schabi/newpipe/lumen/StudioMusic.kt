/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Free background music from official catalog APIs: Openverse (no key) and Freesound (free key).
 * Only the search words leave the phone; found tracks are downloaded into the app.
 */
object StudioMusic {
    const val SRC_OPENVERSE = "openverse"
    const val SRC_FREESOUND = "freesound"
    const val FREESOUND_KEY = "FREESOUND"
    const val FREESOUND_KEY_URL = "https://freesound.org/apiv2/apply"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private const val UA = "Lumen/1.0 (Android; open-source YouTube topic analyser)"

    fun search(source: String, query: String, commercial: Boolean): List<MusicHit> {
        val url = when (source) {
            SRC_FREESOUND -> StudioMusicCatalog.freesoundUrl(query, KeyVault.get(FREESOUND_KEY)
                ?: throw IllegalStateException(t("Нужен ключ Freesound", "A Freesound key is needed")))
            else -> StudioMusicCatalog.openverseUrl(query, commercial)
        }
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IllegalStateException(t("Каталог отклонил запрос (${r.code}). Проверьте ключ.", "The catalog rejected the request (${r.code}). Check the key."))
            if (r.code == 429) throw IllegalStateException(t("Слишком много запросов, попробуйте через минуту.", "Too many requests, try again in a minute."))
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
        val hits = if (source == SRC_FREESOUND) StudioMusicCatalog.parseFreesound(body) else StudioMusicCatalog.parseOpenverse(body)
        L.i("studio", "music search $source '${query.take(40)}': ${hits.size}")
        return if (commercial) hits.filter { StudioMusicCatalog.commercialOk(it) } else hits
    }

    /** Downloads the track into [out]; [progress] gets 0..100. */
    fun download(h: MusicHit, out: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val tmp = File(out.parentFile, out.name + ".part")
        http.newCall(Request.Builder().url(h.url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException(t("Трек не скачался: HTTP ${r.code}", "Download failed: HTTP ${r.code}"))
            val body = r.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            body.byteStream().use { inp ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 15)
                    var done = 0L
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }
}

/** B-roll photos: search on Openverse and download, scaled down to the frame. Only the search words leave the phone. */
object StudioBroll {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private const val UA = "Lumen/1.0 (Android; open-source YouTube topic analyser)"
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun text(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.code == 429) throw IllegalStateException(t("Слишком много запросов, попробуйте через минуту.", "Too many requests, try again in a minute."))
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrElse { e -> if (e is IllegalStateException) throw e else null }

    private fun bytes(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            val b = r.body
            if (!r.isSuccessful || b == null || b.contentLength() > MAX_BYTES) null else b.bytes()
        }
    }.getOrNull()

    /** Animated Noto emoji of [emojis] into [dir] (Lottie JSON); emoji without an animated version get a marker. Returns how many are ready. */
    fun liveEmoji(emojis: Set<String>, dir: File, cancelled: () -> Boolean, progress: (Int, Int) -> Unit): Int {
        dir.mkdirs()
        var ready = 0
        emojis.forEachIndexed { i, e ->
            if (cancelled()) throw java.util.concurrent.CancellationException()
            progress(i, emojis.size)
            val f = StudioLottie.file(dir, e)
            if (f.exists()) {
                ready++
                return@forEachIndexed
            }
            if (StudioLottie.missing(dir, e).exists()) return@forEachIndexed
            val code = runCatching {
                http.newCall(Request.Builder().url(StudioLiveEmoji.url(e)).header("User-Agent", UA).build()).execute().use { r ->
                    val body = r.body?.string()
                    if (r.isSuccessful && body != null && body.trimStart().startsWith("{")) {
                        f.writeText(body)
                        ready++
                    }
                    r.code
                }
            }.getOrDefault(0)
            if (code == 404) StudioLottie.missing(dir, e).writeText("")
        }
        return ready
    }

    /** Bitmap of [data] with its long side at most [maxSide]. */
    private fun decode(data: ByteArray, maxSide: Int): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val long = maxOf(b.width, b.height)
        if (long <= maxSide) return b
        val k = maxSide.toFloat() / long
        val s = android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        if (s !== b) b.recycle()
        return s
    }

    /** Finds a photo for [query] and saves it as [out] (JPEG); null when nothing usable was found. */
    fun fetch(query: String, aspect: StudioAspect, commercial: Boolean, out: File, maxSide: Int, cancelled: () -> Boolean): BrollHit? {
        var hits = text(StudioBrollCatalog.url(query, commercial, aspect))?.let { StudioBrollCatalog.parse(it) }.orEmpty()
        if (hits.isEmpty()) hits = text(StudioBrollCatalog.url(query, commercial, null))?.let { StudioBrollCatalog.parse(it) }.orEmpty()
        for (h in StudioBrollCatalog.rank(hits, aspect).take(4)) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val data = bytes(h.url) ?: h.thumb.takeIf { it.startsWith("http") }?.let { bytes(it) } ?: continue
            val bmp = decode(data, maxSide) ?: continue
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            L.i("studio", "broll '${query.take(40)}': ${h.width}x${h.height} ${h.license}")
            return h
        }
        L.i("studio", "broll '${query.take(40)}': nothing (${hits.size} hits)")
        return null
    }
}

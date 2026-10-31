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

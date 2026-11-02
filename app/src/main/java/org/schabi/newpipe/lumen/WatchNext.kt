/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File

/** One card of YouTube's "Up next" column with its raw place on the page (before any filter). */
data class WnItem(
    val id: String,
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String,
    val views: Long,
    val duration: Long,
    val uploadMs: Long,
    val uploadText: String,
    val thumb: String,
    val isShort: Boolean,
    val pos: Int
) {
    fun toJson(): JSONArray = JSONArray().put(id).put(title).put(channel).put(channelUrl).put(views).put(duration)
        .put(uploadMs).put(uploadText).put(thumb).put(isShort).put(pos)

    companion object {
        fun from(a: JSONArray) = WnItem(
            id = a.getString(0), url = "https://www.youtube.com/watch?v=" + a.getString(0), title = a.optString(1),
            channel = a.optString(2), channelUrl = a.optString(3), views = a.optLong(4, -1), duration = a.optLong(5, -1),
            uploadMs = a.optLong(6, -1), uploadText = a.optString(7), thumb = a.optString(8), isShort = a.optBoolean(9), pos = a.optInt(10)
        )

        fun short(isShortForm: Boolean, url: String, duration: Long) = isShortForm || url.contains("/shorts/") || duration in 1..60
    }
}

/** A watch page: the video's own metadata plus "Up next", as read at [at] in region [gl]. */
class WnPage(
    val id: String,
    val at: Long,
    val gl: String,
    val title: String,
    val channel: String,
    val channelUrl: String,
    val views: Long,
    val duration: Long,
    val uploadMs: Long,
    val thumb: String,
    val subs: Long,
    val items: List<WnItem>,
    /** true when served from the disk cache (no YouTube request was made) */
    val cached: Boolean = false
) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        items.forEach { a.put(it.toJson()) }
        return JSONObject().put("v", 1).put("id", id).put("at", at).put("gl", gl).put("title", title).put("channel", channel)
            .put("channelUrl", channelUrl).put("views", views).put("duration", duration).put("uploadMs", uploadMs)
            .put("thumb", thumb).put("subs", subs).put("items", a)
    }

    companion object {
        fun from(o: JSONObject, cached: Boolean = true): WnPage {
            val a = o.optJSONArray("items") ?: JSONArray()
            return WnPage(
                o.getString("id"), o.optLong("at"), o.optString("gl"), o.optString("title"), o.optString("channel"),
                o.optString("channelUrl"), o.optLong("views", -1), o.optLong("duration", -1), o.optLong("uploadMs", -1),
                o.optString("thumb"), o.optLong("subs", -1), (0 until a.length()).map { WnItem.from(a.getJSONArray(it)) }, cached
            )
        }
    }
}

/**
 * The single reader of YouTube watch pages for the Algorithm tab, the audit, the channel graph and the harvest:
 * one place for the region, the Shorts rule and a disk cache (files/lumen/watchnext/<gl>_<id>.json).
 * Repeated runs on the same day do not ask YouTube again, and the cache doubles as a log of observations.
 */
object WatchNext {
    const val DAY = 86_400_000L
    private const val KEEP_MS = 14 * DAY
    private val yt get() = ServiceList.YouTube

    /** Cache lifetime in hours (Settings → prefs algo_cache_h), 0 = always read YouTube. */
    var cacheHours: Int
        get() = try { Lumen.prefs.getInt("algo_cache_h", 24) } catch (e: Exception) { 24 }
        set(v) = Lumen.prefs.edit().putInt("algo_cache_h", v.coerceIn(0, 24 * 14)).apply()

    private val dir: File get() = File(Store.root, "watchnext").also { it.mkdirs() }

    private fun file(id: String, gl: String) = File(dir, (gl.ifBlank { "xx" }.lowercase()) + "_" + id.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    /** Cached page if it is younger than [maxAgeMs]. */
    fun cached(id: String, gl: String = "", maxAgeMs: Long = cacheHours * 3_600_000L): WnPage? {
        if (maxAgeMs <= 0) return null
        val f = file(id, gl)
        if (!f.exists()) return null
        return try {
            val p = WnPage.from(JSONObject(f.readText()))
            if (System.currentTimeMillis() - p.at <= maxAgeMs) p else null
        } catch (e: Exception) {
            null
        }
    }

    /** Last stored observation of a page, whatever its age (for comparisons over time). */
    fun last(id: String, gl: String = ""): WnPage? = cached(id, gl, Long.MAX_VALUE / 4)

    /**
     * Watch page of video [id]. Served from the cache unless [fresh]; otherwise one YouTube request
     * (spaced by [Par.gate]) in region [gl] ("" = app default). Throws on network/extractor errors.
     */
    fun get(id: String, gl: String = "", fresh: Boolean = false): WnPage {
        if (!fresh) cached(id, gl)?.let { return it }
        Par.gate()
        val ex = yt.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        if (gl.isNotBlank()) try { ex.forceContentCountry(ContentCountry(gl.uppercase())) } catch (e: Exception) { /* keep default */ }
        ex.fetchPage()
        fun <T> safe(f: () -> T): T? = try {
            f()
        } catch (e: Exception) {
            null
        }
        val raw = safe { ex.relatedItems?.items } ?: emptyList()
        val items = ArrayList<WnItem>()
        raw.forEachIndexed { i, it -> if (it is StreamInfoItem) item(it, i)?.let(items::add) }
        val page = WnPage(
            id = id, at = System.currentTimeMillis(), gl = gl,
            title = safe { ex.name } ?: "", channel = safe { ex.uploaderName } ?: "", channelUrl = safe { ex.uploaderUrl } ?: "",
            views = safe { ex.viewCount } ?: -1L, duration = safe { ex.length } ?: -1L,
            uploadMs = safe { ex.uploadDate?.instant?.toEpochMilli() } ?: -1L,
            thumb = safe { ex.thumbnails.maxByOrNull { it.width }?.url } ?: "",
            subs = safe { ex.uploaderSubscriberCount } ?: -1L,
            items = items
        )
        try {
            file(id, gl).writeText(page.toJson().toString())
            if (Math.random() < 0.03) prune()
        } catch (e: Exception) {
            L.w("watchnext", "cache write: ${e.message}")
        }
        return page
    }

    /** A card from any extractor list; [pos] is its place in the original list. */
    fun item(it: StreamInfoItem, pos: Int): WnItem? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        return WnItem(
            id = id, url = "https://www.youtube.com/watch?v=$id", title = it.name ?: "", channel = it.uploaderName ?: "",
            channelUrl = it.uploaderUrl ?: "", views = it.viewCount, duration = it.duration,
            uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L, uploadText = it.textualUploadDate ?: "",
            thumb = it.thumbnails.maxByOrNull { img -> img.width }?.url ?: "",
            isShort = WnItem.short(it.isShortFormContent, it.url, it.duration), pos = pos
        )
    }

    /** YouTube Mix (list=RD<id>) in region [gl]: the radio order of videos. */
    fun mix(id: String, gl: String = ""): List<WnItem> {
        Par.gate()
        val ex = yt.getPlaylistExtractor("https://www.youtube.com/watch?v=$id&list=RD$id")
        if (gl.isNotBlank()) try { ex.forceContentCountry(ContentCountry(gl.uppercase())) } catch (e: Exception) { /* keep default */ }
        ex.fetchPage()
        val out = ArrayList<WnItem>()
        ex.initialPage.items.forEachIndexed { i, it -> if (it is StreamInfoItem) item(it, i)?.let(out::add) }
        return out
    }

    /** Drops cache files older than two weeks. */
    fun prune() {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Number of cached pages and their size in bytes. */
    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles() ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }
}

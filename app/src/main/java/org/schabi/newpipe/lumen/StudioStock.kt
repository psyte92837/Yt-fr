/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * A stock video clip found for B-roll. [urls] are the files to try in order (best first),
 * [durMs] is 0 when the source does not tell it.
 */
data class StockClip(
    val source: String, val id: String, val urls: List<String>, val thumb: String, val width: Int, val height: Int,
    val durMs: Long, val title: String, val creator: String, val license: String, val landing: String
)

/**
 * Real footage for B-roll scenes (idea of OpenMontage's documentary montage: fan the scene's search words out over
 * stock sources, take the clip that fits the frame and the scene length). Pexels and Pixabay need a free key;
 * Wikimedia Commons needs none. Pure part: URLs, parsing, ranking, credits.
 */
object StudioStock {
    const val PEXELS = "pexels"
    const val PIXABAY = "pixabay"
    const val COMMONS = "commons"
    const val PEXELS_KEY = "PEXELS"
    const val PIXABAY_KEY = "PIXABAY"
    const val PEXELS_KEY_URL = "https://www.pexels.com/api/"
    const val PIXABAY_KEY_URL = "https://pixabay.com/api/docs/"

    /** Video files the Studio can play: up to this width (bigger files only cost download time). */
    const val MAX_W = 1920

    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("mp4", "webm", "mov", "m4v")

    /**
     * Search words from the most exact to the widest: the whole query, its two longest words, its longest word
     * (stock catalogs often find nothing for a long phrase).
     */
    fun ladder(query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'') }.filter { it.length >= 3 && it.lowercase() !in STOP }
        val byLen = words.distinct().sortedByDescending { it.length }
        val two = words.filter { it in byLen.take(2) }.distinct().joinToString(" ")
        return listOf(q, two, byLen.firstOrNull().orEmpty()).filter { it.isNotBlank() }.distinct()
    }

    private val STOP = setOf("the", "and", "with", "for", "from", "into", "onto", "over", "under", "near", "very", "some", "that", "this", "its", "their")

    private fun orientation(aspect: StudioAspect) = when (aspect) {
        StudioAspect.PORTRAIT -> "portrait"
        StudioAspect.LANDSCAPE -> "landscape"
        StudioAspect.SQUARE -> "square"
    }

    fun pexelsUrl(query: String, aspect: StudioAspect?): String =
        "https://api.pexels.com/videos/search?query=${StudioMusicCatalog.enc(query)}&per_page=15" +
            (aspect?.let { "&orientation=" + orientation(it) } ?: "")

    fun pixabayUrl(query: String, key: String): String =
        "https://pixabay.com/api/videos/?key=${StudioMusicCatalog.enc(key)}&q=${StudioMusicCatalog.enc(query.take(100))}&per_page=20&safesearch=true"

    fun commonsUrl(query: String): String =
        "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=20" +
            "&gsrsearch=${StudioMusicCatalog.enc("filetype:video $query")}&prop=imageinfo&iiprop=url|size|mime|extmetadata" +
            "&iiextmetadatafilter=LicenseShortName|Artist|ObjectName"

    fun parsePexels(json: String): List<StockClip> {
        val arr = runCatching { JSONObject(json).optJSONArray("videos") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val files = v.optJSONArray("video_files") ?: return@mapNotNull null
            // biggest mp4 that is not over MAX_W, then the smaller ones as spares
            val mp4 = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .filter { it.optString("file_type") == "video/mp4" && it.optString("link").startsWith("http") && it.optInt("width") in 1..MAX_W }
                .sortedByDescending { it.optInt("width") }
            val best = mp4.firstOrNull() ?: return@mapNotNull null
            val page = v.optString("url")
            StockClip(PEXELS, v.optString("id"), mp4.take(3).map { it.optString("link") }, v.optString("image"),
                best.optInt("width"), best.optInt("height"), (v.optDouble("duration", 0.0) * 1000).toLong(),
                titleFromSlug(page), v.optJSONObject("user")?.optString("name").orEmpty(), "pexels", page)
        }
    }

    fun parsePixabay(json: String): List<StockClip> {
        val arr = runCatching { JSONObject(json).optJSONArray("hits") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val h = arr.optJSONObject(i) ?: return@mapNotNull null
            val vids = h.optJSONObject("videos") ?: return@mapNotNull null
            val tiers = listOf("large", "medium", "small", "tiny").mapNotNull { vids.optJSONObject(it) }
                .filter { it.optString("url").startsWith("http") && it.optInt("width") in 1..MAX_W }
            val best = tiers.firstOrNull() ?: return@mapNotNull null
            StockClip(PIXABAY, h.optString("id"), tiers.take(3).map { it.optString("url") }, best.optString("thumbnail"),
                best.optInt("width"), best.optInt("height"), h.optLong("duration", 0) * 1000, h.optString("tags"),
                h.optString("user"), "pixabay", h.optString("pageURL"))
        }
    }

    fun parseCommons(json: String): List<StockClip> {
        val pages = runCatching { JSONObject(json).optJSONObject("query")?.optJSONObject("pages") }.getOrNull() ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { k ->
            val p = pages.optJSONObject(k) ?: return@mapNotNull null
            val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val url = info.optString("url")
            val mime = info.optString("mime")
            if (!url.startsWith("http") || !mime.startsWith("video/")) return@mapNotNull null
            val w = info.optInt("width")
            val h = info.optInt("height")
            val meta = info.optJSONObject("extmetadata")
            fun m(key: String) = meta?.optJSONObject(key)?.optString("value").orEmpty()
            val title = stripHtml(m("ObjectName")).ifBlank { p.optString("title").removePrefix("File:").substringBeforeLast('.') }
            StockClip(COMMONS, p.optString("pageid"), commonsFiles(url, min(w, h), mime), "", w, h,
                (info.optDouble("duration", 0.0) * 1000).toLong(), title, stripHtml(m("Artist")).take(80),
                m("LicenseShortName").ifBlank { "Wikimedia Commons" }, info.optString("descriptionurl"))
        }.toList().sortedBy { it.id.toLongOrNull() ?: 0 }
    }

    /**
     * Files of a Commons video to try: VP9 WebM transcodes (Android plays them; the originals are often Theora
     * or huge), the original last when it is WebM or MP4.
     */
    fun commonsFiles(url: String, short: Int, mime: String): List<String> {
        val i = url.indexOf("/commons/")
        if (i < 0) return listOf(url)
        val name = url.substringAfterLast('/')
        val base = url.substring(0, i) + "/commons/transcoded/" + url.substring(i + "/commons/".length) + "/" + name
        val sizes = listOf(720, 480).filter { short <= 0 || it <= short }.ifEmpty { listOf(480) }
        return sizes.map { "$base.${it}p.vp9.webm" } + (if (mime == "video/webm" || mime == "video/mp4") listOf(url) else emptyList())
    }

    private fun titleFromSlug(page: String): String =
        page.trimEnd('/').substringAfterLast('/').replace(Regex("-?\\d+$"), "").replace('-', ' ').trim()

    fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Best clips first for a scene of [needMs]: the shape of the frame, long enough not to loop (but not hour-long
     * archive films), sharp enough; a clip already used in another scene goes last.
     */
    fun rank(hits: List<StockClip>, aspect: StudioAspect, needMs: Long, used: Set<String> = emptySet()): List<StockClip> {
        val want = when (aspect) {
            StudioAspect.PORTRAIT -> 9.0 / 16
            StudioAspect.LANDSCAPE -> 16.0 / 9
            StudioAspect.SQUARE -> 1.0
        }
        return hits.sortedBy { c ->
            var s = 0.0
            if (c.source + ":" + c.id in used) s += 10
            s += if (c.width > 0 && c.height > 0) abs(ln((c.width.toDouble() / c.height) / want)) * 2 else 1.5
            if (c.durMs in 1 until needMs) s += 1.0
            if (c.durMs > 120_000) s += 1.5
            if (c.width in 1 until 640 && c.height in 1 until 640) s += 1.0
            s
        }
    }

    /** Credit line for the video description. */
    fun credit(c: StockClip): String = buildString {
        append("Video")
        if (c.title.isNotBlank()) append(": \"").append(c.title.take(80)).append('"')
        if (c.creator.isNotBlank()) append(" by ").append(c.creator.take(60))
        append(when (c.source) {
            PEXELS -> " (Pexels)"
            PIXABAY -> " (Pixabay)"
            else -> " (" + c.license + ", Wikimedia Commons)"
        })
        if (c.landing.isNotBlank()) append(' ').append(c.landing)
    }
}

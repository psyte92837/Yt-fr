/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.util.concurrent.atomic.AtomicBoolean

/** YouTube access through NewPipeExtractor (no official API key needed). */
object YouTube {
    private val yt get() = ServiceList.YouTube

    private fun pause() {
        val d = Settings.requestDelayMs
        if (d > 0) Thread.sleep(d.toLong())
    }

    private fun toVideo(it: StreamInfoItem, query: String): Video? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        val thumb = it.thumbnails.maxByOrNull { img -> img.width }?.url ?: ""
        return Video(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = it.name ?: "",
            channel = it.uploaderName ?: "",
            channelUrl = it.uploaderUrl ?: "",
            uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L,
            uploadText = it.textualUploadDate ?: "",
            views = it.viewCount,
            duration = it.duration,
            thumb = thumb,
            isShort = it.isShortFormContent || it.url.contains("/shorts/"),
            query = query,
            description = it.shortDescription ?: ""
        )
    }

    private fun passes(v: Video, f: SearchFilters, now: Long): Boolean {
        if (f.excludeShorts && (v.isShort || (v.duration in 1..60))) return false
        if (!YtQuery.durationOk(f.duration, v.duration)) return false
        val start = if (f.period == "custom") Fmt.parseIsoDay(f.from) else YtQuery.periodStart(f.period, now)
        val end = if (f.period == "custom" && f.to.isNotBlank()) Fmt.parseIsoDay(f.to) + 86_400_000L else -1L
        if (start > 0 && v.uploadMs > 0 && v.uploadMs < start) return false
        if (end > 0 && v.uploadMs > 0 && v.uploadMs > end) return false
        if (!Regions.languageOk(f.origLang, v.title + " " + v.description)) return false
        return true
    }

    /** YouTube understands the before:/after: search operators, which narrows results server-side. */
    private fun queryWithPeriod(q: String, f: SearchFilters, now: Long): String {
        val sb = StringBuilder(q)
        when (f.period) {
            "custom" -> {
                if (f.from.isNotBlank()) sb.append(" after:").append(f.from.trim())
                if (f.to.isNotBlank()) sb.append(" before:").append(Fmt.isoDay(Fmt.parseIsoDay(f.to) + 86_400_000L))
            }
            "week", "month", "year" -> sb.append(" after:").append(Fmt.isoDay(YtQuery.periodStart(f.period, now)))
            "hour", "today" -> sb.append(" after:").append(Fmt.isoDay(now - 2 * 86_400_000L))
        }
        return sb.toString()
    }

    fun sort(list: List<Video>, sort: String): List<Video> = when (sort) {
        "date" -> list.sortedByDescending { it.uploadMs }
        "views" -> list.sortedByDescending { it.views }
        "rating" -> list.sortedByDescending { v ->
            if (v.likes > 0 && v.views > 0) v.likes.toDouble() / v.views else v.views.toDouble() / 1e12
        }
        else -> list
    }

    /**
     * Searches every query of the project, merges and de-duplicates by video id.
     * [onProgress] receives (found so far, current query).
     */
    fun search(
        queries: List<String>,
        f: SearchFilters,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): List<Video> {
        val now = System.currentTimeMillis()
        val result = LinkedHashMap<String, Video>()
        val perQuery = maxOf(10, f.limit / maxOf(1, queries.size) + 5)
        for (q in queries) {
            if (cancel.get()) break
            val handler = yt.searchQHFactory.fromQuery(queryWithPeriod(q, f, now), listOf("videos"), "")
            // one extractor per query: the forced region stays with it for every page (gl=). The interface language (hl=)
            // is left alone: NewPipe parses "2 days ago" in that language, and rare ones would lose upload dates.
            val ex = yt.getSearchExtractor(handler)
            if (f.country.isNotBlank()) ex.forceContentCountry(ContentCountry(f.country))
            ex.fetchPage()
            val first = ex.initialPage
            var items: List<InfoItem> = first.items
            var next: Page? = if (first.hasNextPage()) first.nextPage else null
            var taken = 0
            var pages = 0
            while (true) {
                for (it in items) {
                    if (it !is StreamInfoItem) continue
                    val v = toVideo(it, q) ?: continue
                    if (!passes(v, f, now) || result.containsKey(v.id)) continue
                    result[v.id] = v
                    taken++
                }
                onProgress(result.size, q)
                if (cancel.get() || taken >= perQuery || result.size >= f.limit || next == null || pages >= 15) break
                pause()
                val more = ex.getPage(next)
                items = more.items
                next = if (more.hasNextPage()) more.nextPage else null
                pages++
            }
            if (result.size >= f.limit) break
            pause()
        }
        L.i("yt", "search ${queries.size} queries period=${f.period} sort=${f.sort} gl=${f.country} lang=${f.origLang} → ${result.size} videos")
        return sort(result.values.toList(), f.sort).take(f.limit)
    }

    /** Imports all (or the last [limit]) videos of a channel or playlist URL. */
    fun importList(
        url: String,
        limit: Int,
        f: SearchFilters,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Int) -> Unit = {}
    ): List<Video> {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Video>()
        val label: String
        var items: List<InfoItem>
        var next: Page?
        val fetchMore: (Page) -> Pair<List<InfoItem>, Page?>
        if (url.contains("list=")) {
            val info = PlaylistInfo.getInfo(yt, url)
            label = "playlist: " + info.name
            items = info.relatedItems
            next = info.nextPage
            fetchMore = { p ->
                val m = PlaylistInfo.getMoreItems(yt, url, p)
                m.items to (if (m.hasNextPage()) m.nextPage else null)
            }
        } else {
            val ch = ChannelInfo.getInfo(yt, url)
            label = "channel: " + ch.name
            val tab = ch.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
                ?: ch.tabs.firstOrNull()
                ?: throw IllegalStateException("No videos tab")
            val info = ChannelTabInfo.getInfo(yt, tab)
            items = info.relatedItems
            next = info.nextPage
            fetchMore = { p ->
                val m = ChannelTabInfo.getMoreItems(yt, tab, p)
                m.items to (if (m.hasNextPage()) m.nextPage else null)
            }
        }
        var pages = 0
        while (true) {
            for (it in items) {
                if (it !is StreamInfoItem) continue
                val v = toVideo(it, label) ?: continue
                if (passes(v, f, now)) out[v.id] = v
            }
            onProgress(out.size)
            if (cancel.get() || out.size >= limit || next == null || pages >= 200) break
            pause()
            val (i, n) = fetchMore(next)
            items = i
            next = n
            pages++
        }
        return out.values.take(limit)
    }

    /** Builds a project video from a pasted single-video link. */
    fun single(url: String): Video {
        val info = StreamInfo.getInfo(yt, url)
        val id = YtQuery.videoIdFromUrl(info.url) ?: YtQuery.videoIdFromUrl(url) ?: info.id
        val v = Video(id = id, url = "https://www.youtube.com/watch?v=$id", query = "link")
        applyStreamInfo(v, info)
        return v
    }

    fun applyInfo(v: Video, info: StreamInfo) = applyStreamInfo(v, info)

    fun subtitleFor(subs: List<SubtitlesStream>, lang: String): SubtitlesStream? = pickSubtitle(subs, lang)

    private fun applyStreamInfo(v: Video, info: StreamInfo) {
        v.title = info.name ?: v.title
        v.channel = info.uploaderName ?: v.channel
        v.channelUrl = info.uploaderUrl ?: v.channelUrl
        info.uploadDate?.instant?.toEpochMilli()?.let { v.uploadMs = it }
        if (info.viewCount >= 0) v.views = info.viewCount
        if (info.duration > 0) v.duration = info.duration
        v.description = info.description?.content() ?: ""
        v.tags = info.tags.toMutableList()
        v.likes = info.likeCount
        v.category = info.category ?: ""
        v.subscribers = info.uploaderSubscriberCount
        if (v.thumb.isEmpty()) v.thumb = info.thumbnails.maxByOrNull { it.width }?.url ?: ""
    }

    private fun pickSubtitle(subs: List<SubtitlesStream>, lang: String): SubtitlesStream? {
        val vtt = subs.filter { it.format == MediaFormat.VTT }.ifEmpty { subs }
        if (vtt.isEmpty()) return null
        val byLang = if (lang == "auto") vtt else vtt.filter { it.languageTag.startsWith(lang, ignoreCase = true) }.ifEmpty { vtt }
        return byLang.firstOrNull { !it.isAutoGenerated } ?: byLang.first()
    }

    /** Collects metadata, transcript and comments for one video according to [cfg]. */
    fun collect(projectId: String, phrase: String, v: Video, cfg: CollectConfig) {
        val info = StreamInfo.getInfo(yt, v.url)
        if (cfg.meta) applyStreamInfo(v, info)
        if (cfg.subtitles) {
            val sub = pickSubtitle(info.subtitles, cfg.subLang)
            if (sub != null) {
                pause()
                val raw = NewPipe.getDownloader().get(sub.content).responseBody()
                val text = if (raw.trimStart().startsWith("WEBVTT")) Vtt.toText(raw) else raw.replace(Regex("<[^>]+>"), " ")
                if (text.isNotBlank()) {
                    Store.saveTranscript(projectId, v.id, text)
                    v.hasTranscript = true
                    v.transcriptSource = if (sub.isAutoGenerated) "auto" else "manual"
                }
            }
        }
        if (!v.hasTranscript && cfg.aiTranscribe && Settings.aiTranscriptionAllowed) {
            val p = Provider.active()
            if (p == Provider.GEMINI) {
                val r = Llm.complete(
                    p, p.fastModel, Prompts.SYSTEM,
                    "Transcribe the speech of this video verbatim in its original language. " +
                        "Put a [mm:ss] timecode at the start of each paragraph. Topic hint: $phrase",
                    fileUri = v.url, maxTokens = 32000, task = "transcribe", pid = projectId
                )
                Store.saveTranscript(projectId, v.id, r.text)
                v.hasTranscript = true
                v.transcriptSource = "ai"
            }
        }
        if (cfg.comments) {
            pause()
            val list = comments(v.url, cfg)
            Store.saveComments(projectId, v.id, list)
            v.commentsCount = list.size
        }
        v.collected = true
        v.collectError = ""
    }

    private fun toComment(c: CommentsInfoItem, reply: Boolean) = Comment(
        id = c.commentId ?: "",
        author = (c.uploaderName ?: "").let { if (Settings.anonymizeAuthors && it.isNotEmpty()) "u_" + sha1(it).take(8) else it },
        text = c.commentText?.content()?.replace(Regex("<[^>]+>"), "") ?: "",
        likes = c.likeCount,
        dateMs = c.uploadDate?.instant?.toEpochMilli() ?: -1L,
        dateText = c.textualUploadDate ?: "",
        replyCount = c.replyCount,
        isReply = reply
    )

    fun comments(url: String, cfg: CollectConfig): List<Comment> {
        val out = mutableListOf<Comment>()
        val info = CommentsInfo.getInfo(yt, url) ?: return out
        if (info.isCommentsDisabled) return out
        var items: List<CommentsInfoItem> = info.relatedItems
        var next: Page? = info.nextPage
        var pages = 0
        loop@ while (true) {
            for (c in items) {
                out.add(toComment(c, false))
                if (cfg.replies && c.replyCount > 0 && c.replies != null) {
                    try {
                        pause()
                        val r = CommentsInfo.getMoreItems(yt, url, c.replies)
                        r.items.forEach { out.add(toComment(it, true)) }
                    } catch (e: Exception) {
                        // replies are optional
                    }
                }
                if (out.size >= cfg.commentsLimit) break@loop
            }
            if (next == null || pages >= 100) break
            pause()
            val more = CommentsInfo.getMoreItems(yt, url, next)
            items = more.items
            next = if (more.hasNextPage()) more.nextPage else null
            pages++
        }
        val list = out.take(cfg.commentsLimit)
        return if (cfg.commentsSort == "new") list.sortedByDescending { it.dateMs } else list
    }
}

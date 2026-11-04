/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Prompt of the player's AI filter (editable in Settings → AI → Prompts). */
val PLAYER_PROMPTS = linkedMapOf(
    PlayerAi.TASK to (
        "Ты фильтруешь выдачу поиска YouTube для зрителя. Запрос: {query}\n" +
            "Критерии зрителя:\n{criteria}\n\n" +
            "Ниже найденные видео: номер, название, канал, длительность, просмотры, начало описания. " +
            "В keep — номера видео, которые подходят под критерии; в drop — явно не подходящие, у каждого why: причина до 8 слов " +
            "на языке {lang}. Если сомневаешься — оставь. Каждый номер ровно в одном списке.\n" +
            "Верни строго JSON: {\"keep\":[1,2],\"drop\":[{\"n\":3,\"why\":\"...\"}]}\n\n" +
            "Видео:\n{items}"
        )
)

/** Files and settings of the player: cached searches (auto-deleted), watch history, preferences. */
object PlayerStore {
    private val dir: File get() = File(Store.root, "player").also { it.mkdirs() }
    private val searchesFile get() = File(dir, "searches.json")
    private val historyFile get() = File(dir, "history.json")
    private val lock = Any()

    /** Days a past search is kept (0 = not kept). */
    var keepDays: Int
        get() = Lumen.prefs.getInt("player_keep_days", PlayerCore.DEFAULT_KEEP)
        set(v) {
            Lumen.prefs.edit().putInt("player_keep_days", v).apply()
            searches() // prunes at once
        }

    /** Quality of the built-in player: "auto" or "720p"-like. */
    var quality: String
        get() = Lumen.prefs.getString("player_quality", "auto") ?: "auto"
        set(v) = Lumen.prefs.edit().putString("player_quality", v).apply()

    var speed: Float
        get() = Lumen.prefs.getFloat("player_speed", 1f)
        set(v) = Lumen.prefs.edit().putFloat("player_speed", v).apply()

    /** Leaving the app with a video playing goes on as sound in the background. */
    var bgOnLeave: Boolean
        get() = Lumen.prefs.getBoolean("player_bg_leave", true)
        set(v) = Lumen.prefs.edit().putBoolean("player_bg_leave", v).apply()

    /** The next video of the list starts when one ends. */
    var autoNext: Boolean
        get() = Lumen.prefs.getBoolean("player_auto_next", true)
        set(v) = Lumen.prefs.edit().putBoolean("player_auto_next", v).apply()

    /** The last filters (restored when the screen opens). */
    var filter: PFilter
        get() = PFilter.from(runCatching { org.json.JSONObject(Lumen.prefs.getString("player_filter", "{}") ?: "{}") }.getOrNull())
        set(v) = Lumen.prefs.edit().putString("player_filter", v.toJson().toString()).apply()

    private fun read(f: File): String? = runCatching { if (f.exists()) f.readText() else null }.getOrNull()

    private fun write(f: File, s: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(s)
            if (!tmp.renameTo(f)) {
                f.writeText(s)
                tmp.delete()
            }
        }.onFailure { L.w("player", "write ${f.name}: ${it.message}") }
    }

    /** Parsed searches kept in memory (the file is read once). */
    private var memo: List<PSearch>? = null

    private fun store(list: List<PSearch>) {
        memo = list
        if (list.isEmpty()) searchesFile.delete() else write(searchesFile, PlayerCore.searchesJson(list))
    }

    /** Past searches, newest first; old ones are deleted on the way. */
    fun searches(): List<PSearch> = synchronized(lock) {
        val all = memo ?: PlayerCore.parseSearches(read(searchesFile))
        val kept = PlayerCore.prune(all, System.currentTimeMillis(), keepDays)
        if (kept.size != all.size || memo == null) {
            if (kept.size != all.size) store(kept) else memo = kept
        }
        kept
    }

    fun save(s: PSearch) = synchronized(lock) {
        if (keepDays <= 0 || s.items.isEmpty()) return@synchronized
        store(PlayerCore.put(searches(), s))
    }

    fun delete(key: String) = synchronized(lock) { store(searches().filter { it.key != key }) }

    fun restoreSearch(s: PSearch) = synchronized(lock) {
        store(PlayerCore.prune(searches().filter { it.key != s.key } + s, System.currentTimeMillis(), maxOf(1, keepDays)))
    }

    fun clearSearches() = synchronized(lock) { store(emptyList()) }

    fun history(): List<PWatch> = synchronized(lock) { PlayerCore.parseHistory(read(historyFile)) }

    fun remember(w: PWatch) = synchronized(lock) { write(historyFile, PlayerCore.historyJson(PlayerCore.remember(history(), w))) }

    fun forget(id: String) = synchronized(lock) { write(historyFile, PlayerCore.historyJson(history().filter { it.id != id })) }

    fun clearHistory() = synchronized(lock) { historyFile.delete() }

    /** Where the user stopped in a video, ms (0 = from the start). */
    fun resumeOf(id: String): Long = history().firstOrNull { it.id == id }?.let { PlayerCore.resumeFrom(it.posMs, it.duration * 1000) } ?: 0
}

/**
 * One YouTube search with "more": keeps the extractor and the next page; [fetch] returns the next new videos that pass the
 * local filters (period, views, length, Shorts) and, when asked, have enough comments. Network; worker thread.
 */
class PlayerSearch(val query: String, val filter: PFilter) {
    private var ex: SearchExtractor? = null
    private var next: Page? = null

    /** YouTube has more pages. */
    var more = true
        private set
    private val seen = HashSet<String>()

    /** Videos already shown (e.g. from the cache): they are skipped. */
    fun seed(ids: Collection<String>) {
        seen += ids
    }

    fun fetch(want: Int, cancel: AtomicBoolean, progress: (String) -> Unit = {}): List<PItem> {
        val now = System.currentTimeMillis()
        val out = ArrayList<PItem>()
        var pages = 0
        while (out.size < want && more && pages < 8 && !cancel.get()) {
            val cur = ex
            val page = if (cur == null) {
                val yt = ServiceList.YouTube
                val h = yt.searchQHFactory.fromQuery(PlayerCore.ytQuery(query, filter, now), listOf("videos"), "")
                val created = yt.getSearchExtractor(h)
                ex = created
                created.fetchPage()
                created.initialPage
            } else {
                val n = next ?: break
                Par.gate()
                cur.getPage(n)
            }
            next = if (page.hasNextPage()) page.nextPage else null
            more = next != null
            pages++
            for (it in page.items) {
                if (it !is StreamInfoItem) continue
                val v = item(it) ?: continue
                if (!seen.add(v.id) || !PlayerCore.passes(v, filter, now)) continue
                out += v
            }
            if (filter.comments > 0 && out.isNotEmpty()) {
                // the count is not on the search page: one request per video, only for those that passed the rest
                val todo = out.indices.filter { out[it].comments < 0 }
                var done = 0
                val counts = Par.map(todo, 4, cancel) { _, i ->
                    val n = runCatching { CommentsInfo.getInfo(ServiceList.YouTube, out[i].url).let { c -> if (c.isCommentsDisabled) 0 else c.commentsCount } }
                        .onFailure { e -> L.w("player", "comments ${out[i].id}: ${e.message}") }.getOrDefault(-1)
                    synchronized(this) { done++ }
                    progress(t("Считаю комментарии $done/${todo.size}", "Counting comments $done/${todo.size}"))
                    n
                }
                todo.forEachIndexed { k, i -> counts[k]?.let { c -> out[i] = out[i].copy(comments = c) } }
                // unknown counts stay (shown without a number), too few go
                out.retainAll { PlayerCore.commentsOk(it, filter) != false }
            }
            progress(t("Найдено: ${out.size}", "Found: ${out.size}"))
        }
        L.i("player", "search \"$query\" pages=$pages → ${out.size} more=$more")
        return out
    }

    companion object {
        fun item(it: StreamInfoItem): PItem? {
            val id = YtQuery.videoIdFromUrl(it.url) ?: return null
            return PItem(
                id = id, title = it.name.orEmpty(), channel = it.uploaderName.orEmpty(), channelUrl = it.uploaderUrl.orEmpty(),
                uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L, uploadText = it.textualUploadDate.orEmpty(),
                views = it.viewCount, duration = if (it.streamType == StreamType.LIVE_STREAM) -1 else it.duration,
                isShort = it.isShortFormContent || it.url.contains("/shorts/"), desc = it.shortDescription.orEmpty()
            )
        }

        /** YouTube search suggestions for [q]; empty on any error. Network. */
        fun suggestions(q: String): List<String> = runCatching { RecEngine.suggestions(q) }.getOrDefault(emptyList()).take(6)
    }
}

/** AI filter of the player: which found videos do not fit the viewer's criteria, and why. */
object PlayerAi {
    const val TASK = "player_filter"
    private const val BATCH = 40

    /** An AI provider (or "another AI by hand") is set up. */
    fun ready(): Boolean = runCatching { AiTasks.requireProvider() }.isSuccess

    /** Dropped ids with the reason; videos not mentioned are kept. Network; worker thread. */
    fun judge(query: String, f: PFilter, items: List<PItem>, cancel: AtomicBoolean, progress: (String) -> Unit = {}): Map<String, String> {
        val prov = AiTasks.requireProvider()
        val en = Settings.lang == "en"
        val out = LinkedHashMap<String, String>()
        val batches = items.chunked(BATCH)
        batches.forEachIndexed { b, part ->
            if (cancel.get()) return out
            progress(t("ИИ проверяет ${b * BATCH + part.size}/${items.size}", "AI checks ${b * BATCH + part.size}/${items.size}"))
            val prompt = Prompts.fill(TASK, mapOf("query" to query, "criteria" to PlayerCore.criteria(f, en),
                "lang" to if (en) "English" else "русский", "items" to PlayerCore.aiBlock(part)))
            val r = LlmJson.call(prov, prov.fastModel, Prompts.SYSTEM, prompt, TASK, "player", 300 + part.size * 40,
                { PlayerCore.parseVerdicts(it, part.size) }, hint = "{\"keep\":[1],\"drop\":[{\"n\":2,\"why\":\"…\"}]}", schema = PlayerCore.SCHEMA)
            r.value.forEach { (n, why) -> part.getOrNull(n - 1)?.let { out[it.id] = why } }
        }
        return out
    }
}

/** Hand-offs to NewPipe's player service: sound in the background, a floating window, the queue. */
object PlayerHandoff {
    /** A NewPipe queue item from a found video (no network). */
    fun streamItem(p: PItem): StreamInfoItem = StreamInfoItem(ServiceList.YouTube.serviceId, p.url, p.title, StreamType.VIDEO_STREAM).apply {
        uploaderName = p.channel
        uploaderUrl = p.channelUrl
        duration = p.duration
        viewCount = p.views
        thumbnails = listOf(org.schabi.newpipe.extractor.Image(p.thumb, 180, 320, org.schabi.newpipe.extractor.Image.ResolutionLevel.MEDIUM))
    }

    /** Sound of [info] in the background from [posMs]. */
    fun background(ctx: Context, info: StreamInfo, posMs: Long) {
        NavigationHelper.playOnBackgroundPlayer(ctx.applicationContext, SinglePlayQueue(info, posMs), true)
    }

    /** All [list] as one background queue starting at [index] (each video loads when its turn comes). */
    fun backgroundAll(ctx: Context, list: List<PItem>, index: Int) {
        if (list.isEmpty()) return
        NavigationHelper.playOnBackgroundPlayer(ctx.applicationContext, SinglePlayQueue(list.map { streamItem(it) }, index.coerceIn(0, list.size - 1)), true)
    }

    /** Adds [p] to the end of the background queue (starts it when nothing plays). */
    fun enqueue(ctx: Context, p: PItem) {
        val q = SinglePlayQueue(streamItem(p))
        if (PlayerHolder.getInstance().isPlayerOpen) NavigationHelper.enqueueOnPlayer(ctx.applicationContext, q, PlayerType.AUDIO)
        else NavigationHelper.playOnBackgroundPlayer(ctx.applicationContext, q, true)
    }

    /** A floating window over other apps (asks for the permission when needed). */
    fun popup(ctx: Context, info: StreamInfo?, p: PItem, posMs: Long) {
        val q = if (info != null) SinglePlayQueue(info, posMs) else SinglePlayQueue(streamItem(p))
        NavigationHelper.playOnPopupPlayer(ctx, q, true)
    }

    /** Stops NewPipe's player (background or window), e.g. when the video comes back to the Lumen screen. */
    fun stopOthers() {
        runCatching { if (PlayerHolder.getInstance().isPlayerOpen) PlayerHolder.getInstance().stopService() }
            .onFailure { L.w("player", "stop service: ${it.message}") }
    }

    fun othersPlaying(): Boolean = runCatching { PlayerHolder.getInstance().isPlayerOpen }.getOrDefault(false)
}

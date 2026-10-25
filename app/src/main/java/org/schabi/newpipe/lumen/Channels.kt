/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class PlaylistRef(val name: String, val url: String, val count: Long) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("count", count)

    companion object {
        fun from(o: JSONObject) = PlaylistRef(o.optString("name"), o.optString("url"), o.optLong("count", -1))
    }
}

/** Channel card: profile, links, playlists and statistics of recent uploads. */
data class ChannelData(
    val url: String,
    var name: String = "",
    var subscribers: Long = -1,
    var verified: Boolean = false,
    var description: String = "",
    var avatar: String = "",
    var links: MutableList<String> = mutableListOf(),
    var tags: MutableList<String> = mutableListOf(),
    var playlists: MutableList<PlaylistRef> = mutableListOf(),
    var featured: MutableList<String> = mutableListOf(), // featured channel urls
    var recent: MutableList<Video> = mutableListOf(),
    var fetchedAt: Long = 0,
    var error: String = ""
) {
    val stats: ChannelStats.Result get() = ChannelStats.of(recent, System.currentTimeMillis())

    fun toJson(): JSONObject = JSONObject().put("url", url).put("name", name).put("subscribers", subscribers)
        .put("verified", verified).put("description", description).put("avatar", avatar)
        .put("links", JSONArray(links)).put("tags", JSONArray(tags))
        .put("playlists", JSONArray().also { a -> playlists.forEach { a.put(it.toJson()) } })
        .put("featured", JSONArray(featured))
        .put("recent", JSONArray().also { a -> recent.forEach { a.put(it.toJson()) } })
        .put("fetchedAt", fetchedAt).put("error", error)

    companion object {
        private fun strings(a: JSONArray?) = if (a == null) mutableListOf() else (0 until a.length()).map { a.optString(it) }.toMutableList()

        fun from(o: JSONObject) = ChannelData(
            url = o.getString("url"), name = o.optString("name"), subscribers = o.optLong("subscribers", -1),
            verified = o.optBoolean("verified"), description = o.optString("description"), avatar = o.optString("avatar"),
            links = strings(o.optJSONArray("links")), tags = strings(o.optJSONArray("tags")),
            playlists = (o.optJSONArray("playlists") ?: JSONArray()).let { a -> (0 until a.length()).map { PlaylistRef.from(a.getJSONObject(it)) }.toMutableList() },
            featured = strings(o.optJSONArray("featured")),
            recent = (o.optJSONArray("recent") ?: JSONArray()).let { a -> (0 until a.length()).map { Video.from(a.getJSONObject(it)) }.toMutableList() },
            fetchedAt = o.optLong("fetchedAt"), error = o.optString("error")
        )
    }
}

/** Pure statistics over a channel's recent uploads (covered by unit tests). */
object ChannelStats {
    data class Result(
        val videos: Int,
        val avgViews: Long,
        val medianViews: Long,
        val maxViews: Long,
        val perWeek: Double,
        val lastUploadMs: Long,
        val avgDurationSec: Long,
        val shortsShare: Double,
        val top: List<Video>
    )

    fun of(recent: List<Video>, now: Long): Result {
        val withViews = recent.filter { it.views >= 0 }.map { it.views }.sorted()
        val dated = recent.filter { it.uploadMs > 0 }.map { it.uploadMs }.sorted()
        val median = when {
            withViews.isEmpty() -> -1L
            withViews.size % 2 == 1 -> withViews[withViews.size / 2]
            else -> (withViews[withViews.size / 2 - 1] + withViews[withViews.size / 2]) / 2
        }
        val perWeek = if (dated.size >= 2) {
            val spanWeeks = maxOf(1.0, (maxOf(now, dated.last()) - dated.first()) / (7 * 86_400_000.0))
            dated.size / spanWeeks
        } else {
            0.0
        }
        val durs = recent.filter { it.duration > 0 }.map { it.duration }
        return Result(
            recent.size,
            if (withViews.isEmpty()) -1 else withViews.sum() / withViews.size,
            median,
            withViews.maxOrNull() ?: -1,
            perWeek,
            dated.lastOrNull() ?: -1,
            if (durs.isEmpty()) -1 else durs.sum() / durs.size,
            if (recent.isEmpty()) 0.0 else recent.count { it.isShort || it.duration in 1..60 }.toDouble() / recent.size,
            recent.sortedByDescending { it.views }.take(5)
        )
    }

    /** URLs found in a channel description (links block). */
    fun links(description: String): List<String> =
        Regex("""https?://[^\s<>"')\]]+""").findAll(description).map { it.value.trimEnd('.', ',', ';') }.distinct().toList()

    /** Short line for lists: "1.2M subs · ⌀ 40K · 2.5/wk · 3 d ago". */
    fun line(c: ChannelData, now: Long = System.currentTimeMillis()): String {
        val s = c.stats
        val parts = mutableListOf<String>()
        if (c.subscribers >= 0) parts += Fmt.num(c.subscribers) + t(" подп.", " subs")
        if (s.avgViews >= 0) parts += "⌀ " + Fmt.num(s.avgViews)
        if (s.medianViews >= 0) parts += t("медиана ", "median ") + Fmt.num(s.medianViews)
        if (s.perWeek > 0) parts += String.format("%.1f", s.perWeek) + t("/нед", "/wk")
        if (s.lastUploadMs > 0) parts += ((now - s.lastUploadMs) / 86_400_000L).toString() + t(" дн. назад", " d ago")
        return parts.joinToString(" · ")
    }
}

/** Channel files of a project: channels.json (url → ChannelData). */
object ChannelStore {
    private const val FILE = "channels.json"

    fun all(pid: String): LinkedHashMap<String, ChannelData> {
        val out = LinkedHashMap<String, ChannelData>()
        val text = Store.readFile(pid, FILE) ?: return out
        try {
            val a = JSONObject(text).optJSONArray("channels") ?: JSONArray()
            for (i in 0 until a.length()) ChannelData.from(a.getJSONObject(i)).let { out[key(it.url)] = it }
        } catch (e: Exception) {
            L.w("channels", "bad channels.json: ${e.message}")
        }
        return out
    }

    fun get(pid: String, url: String): ChannelData? = all(pid)[key(url)]

    fun put(pid: String, c: ChannelData) {
        val all = all(pid)
        all[key(c.url)] = c
        Store.writeFile(pid, FILE, JSONObject().put("channels", JSONArray().also { a -> all.values.forEach { a.put(it.toJson()) } }).toString())
    }

    /** Normalised key: channel URLs come as /channel/UC…, /@handle, /c/… */
    fun key(url: String): String = url.trim().trimEnd('/').substringBefore('?').lowercase()
        .replace("https://m.youtube.com", "https://www.youtube.com").replace("http://", "https://")
}

/** Fetches channel data through NewPipeExtractor (no API key). */
object ChannelFetch {
    private val yt get() = ServiceList.YouTube

    private fun pause() {
        val d = Settings.requestDelayMs
        if (d > 0) Thread.sleep(d.toLong())
    }

    private fun video(it: StreamInfoItem, query: String): Video? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        return Video(
            id = id, url = "https://www.youtube.com/watch?v=$id", title = it.name ?: "", channel = it.uploaderName ?: "",
            channelUrl = it.uploaderUrl ?: "", uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L,
            uploadText = it.textualUploadDate ?: "", views = it.viewCount, duration = it.duration,
            thumb = it.thumbnails.maxByOrNull { img -> img.width }?.url ?: "", isShort = it.isShortFormContent, query = query
        )
    }

    /**
     * Profile + recent uploads (up to [recentLimit]) + playlists + featured channels.
     * Cached for [maxAgeMs]; failures are stored in [ChannelData.error] instead of throwing.
     */
    fun fetch(pid: String, url: String, recentLimit: Int = 30, withPlaylists: Boolean = true, maxAgeMs: Long = 12 * 3_600_000L, force: Boolean = false): ChannelData {
        ChannelStore.get(pid, url)?.let { if (!force && it.error.isEmpty() && System.currentTimeMillis() - it.fetchedAt < maxAgeMs && it.recent.size >= minOf(recentLimit, 10)) return it }
        val c = ChannelStore.get(pid, url) ?: ChannelData(url)
        try {
            pause()
            val info = ChannelInfo.getInfo(yt, url)
            c.name = info.name ?: c.name
            c.subscribers = info.subscriberCount
            c.verified = info.isVerified
            c.description = info.description ?: ""
            c.avatar = info.avatars.maxByOrNull { it.width }?.url ?: ""
            c.tags = info.tags.toMutableList()
            c.links = (ChannelStats.links(c.description) + (info.donationLinks?.toList() ?: emptyList())).distinct().toMutableList()
            val tabs = info.tabs
            tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }?.let { tab ->
                pause()
                val ti = ChannelTabInfo.getInfo(yt, tab)
                val list = ti.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { video(it, "channel") }.toMutableList()
                var next = ti.nextPage
                var pages = 0
                while (list.size < recentLimit && next != null && pages < 20) {
                    pause()
                    val more = ChannelTabInfo.getMoreItems(yt, tab, next)
                    list += more.items.filterIsInstance<StreamInfoItem>().mapNotNull { video(it, "channel") }
                    next = if (more.hasNextPage()) more.nextPage else null
                    pages++
                }
                c.recent = list.take(recentLimit).toMutableList()
            }
            if (withPlaylists) {
                tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.PLAYLISTS) }?.let { tab ->
                    try {
                        pause()
                        c.playlists = ChannelTabInfo.getInfo(yt, tab).relatedItems.filterIsInstance<PlaylistInfoItem>()
                            .map { PlaylistRef(it.name ?: "", it.url, it.streamCount) }.toMutableList()
                    } catch (e: Exception) {
                        L.w("channels", "playlists of $url: ${e.message}")
                    }
                }
                tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.CHANNELS) }?.let { tab ->
                    try {
                        pause()
                        c.featured = ChannelTabInfo.getInfo(yt, tab).relatedItems.filterIsInstance<ChannelInfoItem>().map { it.url }.toMutableList()
                    } catch (e: Exception) {
                        L.d("channels", "featured of $url: ${e.message}")
                    }
                }
            }
            c.fetchedAt = System.currentTimeMillis()
            c.error = ""
            L.i("channels", "fetched ${c.name}: subs=${c.subscribers} recent=${c.recent.size} playlists=${c.playlists.size} featured=${c.featured.size}")
        } catch (e: Exception) {
            c.error = e.message ?: e.javaClass.simpleName
            L.w("channels", "fetch $url failed: ${c.error}")
        }
        ChannelStore.put(pid, c)
        return c
    }

    /** Fetches stats for the distinct channels of [videos] (cached ones are skipped). */
    fun forVideos(pid: String, videos: Collection<Video>, cancel: AtomicBoolean, limit: Int = 50, progress: (String) -> Unit = {}): Int {
        val urls = videos.map { it.channelUrl }.filter { it.isNotBlank() }.distinctBy { ChannelStore.key(it) }.take(limit)
        var n = 0
        for ((i, u) in urls.withIndex()) {
            if (cancel.get()) break
            progress(t("Каналы ${i + 1}/${urls.size}", "Channels ${i + 1}/${urls.size}"))
            fetch(pid, u, recentLimit = 30, withPlaylists = false)
            n++
        }
        return n
    }
}

/**
 * Channel graph: which channels YouTube connects to the seeds — through the "Up next" column of
 * their recent videos and through featured channels. Beam search by depth, stats for every node.
 * Stored as a [RecGraph] in chgraph.json (node id = channel key, url = channel url, views = subscribers).
 */
object ChannelExplorer {
    data class Config(
        val depth: Int = 2,
        val maxChannels: Int = 100,
        val videosPerChannel: Int = 2,
        val perVideo: Int = 10,
        val useFeatured: Boolean = true,
        val budget: Int = 300,
        val topic: Boolean = true
    )

    private val yt get() = ServiceList.YouTube

    fun run(pid: String, seeds: List<String>, cfg: Config, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val g = RecGraph.load(pid, RecGraph.CHANNELS_FILE)
        g.mode = "channels"
        var requests = 0
        if (cfg.topic) {
            progress(t("Подтверждение темы…", "Confirming topic…"))
            TopicFilter.ensure(pid)
        }
        val project = Store.project(pid)
        val topicOn = cfg.topic && project != null
        // off-topic channels do not count against the limit and are removed at the end
        fun size() = g.nodes.values.count { it.relevant != 0 }
        fun limit() = cancel.get() || requests >= cfg.budget || size() >= cfg.maxChannels
        fun node(url: String, name: String, depth: Int, from: String): RecNode {
            val k = ChannelStore.key(url)
            return g.nodes.getOrPut(k) { RecNode(k, url = url, title = name, channel = name, depth = depth, from = from, relevant = if (topicOn) -1 else 1) }
        }
        fun fill(n: RecNode, c: ChannelData) {
            n.title = c.name.ifBlank { n.title }
            n.channel = n.title
            n.views = c.subscribers
            n.thumb = c.avatar
            n.uploadText = ChannelStats.line(c)
        }
        fun judge(batch: List<Pair<RecNode, ChannelData>>) {
            val todo = batch.filter { it.first.relevant == -1 && !it.first.seed }
            if (!topicOn || todo.isEmpty()) return
            val verdict = TopicFilter.judgeChannels(project!!, todo.map { it.first.id to it.second }) { progress(it) }
            todo.forEach { (n, _) -> n.relevant = if (verdict[n.id] == true) 1 else 0 }
        }
        for (s in seeds.distinctBy { ChannelStore.key(it) }) node(s, "", 0, "").apply { seed = true; depth = 0; relevant = 1 }
        val beam = maxOf(1, ceil(cfg.maxChannels.toDouble() / maxOf(1, cfg.depth)).toInt())
        for (level in 0 until cfg.depth) {
            if (limit()) break
            val frontier = g.nodes.values.filter { it.depth == level && !it.expanded && it.relevant != 0 }
                .sortedWith(compareByDescending<RecNode> { it.seed }.thenByDescending { it.hits }.thenByDescending { it.views })
                .let { if (level == 0) it else it.take(beam) }
            // 1) fetch every channel of the level, 2) ask the topic filter once for the whole level, 3) expand only on-topic ones
            val fetched = ArrayList<Pair<RecNode, ChannelData>>()
            for (n in frontier) {
                if (cancel.get() || requests >= cfg.budget) break
                progress(t("Каналы: глубина ${level + 1}/${cfg.depth} · найдено ${size()}\n", "Channels: depth ${level + 1}/${cfg.depth} · found ${size()}\n") + n.title.ifBlank { n.url })
                val c = ChannelFetch.fetch(pid, n.url, recentLimit = 15, withPlaylists = cfg.useFeatured)
                requests += 3
                fill(n, c)
                fetched.add(n to c)
            }
            judge(fetched)
            for ((n, c) in fetched) {
                if (limit()) break
                if (n.relevant == 0) continue
                n.expanded = true
                val counts = LinkedHashMap<String, Pair<String, Int>>() // url -> (name, count)
                if (cfg.useFeatured) c.featured.forEach { u -> counts[u] = (counts[u]?.first ?: "") to ((counts[u]?.second ?: 0) + 2) }
                for (v in c.recent.filter { !it.isShort }.sortedByDescending { it.views }.take(cfg.videosPerChannel)) {
                    if (limit()) break
                    try {
                        Thread.sleep(Settings.requestDelayMs.toLong())
                        val ex = yt.getStreamExtractor(v.url)
                        ex.fetchPage()
                        requests++
                        ex.relatedItems?.items?.filterIsInstance<StreamInfoItem>()?.take(cfg.perVideo)?.forEach { it ->
                            val u = it.uploaderUrl ?: return@forEach
                            if (ChannelStore.key(u) == n.id) return@forEach
                            counts[u] = (it.uploaderName ?: counts[u]?.first ?: "") to ((counts[u]?.second ?: 0) + 1)
                        }
                    } catch (e: Exception) {
                        L.w("chgraph", "related of ${v.id}: ${e.message}")
                    }
                }
                var rank = 0
                for ((u, p) in counts.entries.sortedByDescending { it.value.second }) {
                    val k = ChannelStore.key(u)
                    if (!g.nodes.containsKey(k) && g.nodes.size >= cfg.maxChannels * (if (topicOn) 3 else 1)) break
                    val child = node(u, p.first, level + 1, n.id)
                    if (g.addEdge(n.id, child.id, rank)) {
                        child.weight += p.second - 1.0 / (rank + 1) // weight = how many links between the channels
                    }
                    rank++
                }
            }
        }
        // leaves that were never fetched: light fetch (no playlists), then the topic filter
        val light = ArrayList<Pair<RecNode, ChannelData>>()
        for (n in g.nodes.values.filter { !it.expanded && (it.uploadText.isBlank() || it.relevant == -1) && it.relevant != 0 }) {
            if (cancel.get() || requests >= cfg.budget + cfg.maxChannels * 2) break
            progress(t("Статистика каналов: ", "Channel stats: ") + n.title)
            val c = ChannelFetch.fetch(pid, n.url, recentLimit = 15, withPlaylists = false)
            requests += 2
            fill(n, c)
            light.add(n to c)
        }
        judge(light)
        // anything still undecided (budget ran out) is not confirmed on-topic -> dropped when the filter is on
        if (topicOn) g.nodes.values.filter { it.relevant == -1 && !it.seed }.forEach { it.relevant = 0 }
        if (topicOn) { // keep the strongest on-topic channels within the limit
            g.nodes.values.filter { it.relevant != 0 && !it.seed }.sortedByDescending { it.weight }.drop(maxOf(0, cfg.maxChannels - g.nodes.values.count { it.seed }))
                .forEach { it.relevant = 0 }
        }
        val dropped = if (topicOn) TopicFilter.prune(g) else 0
        g.computePageRank()
        g.requests += requests
        g.updatedAt = System.currentTimeMillis()
        RecGraph.save(pid, g, RecGraph.CHANNELS_FILE)
        return t("Каналов: ${g.nodes.size}, связей: ${g.edges.size}, не по теме отброшено: $dropped, запросов: $requests", "Channels: ${g.nodes.size}, edges: ${g.edges.size}, off-topic dropped: $dropped, requests: $requests")
    }
}

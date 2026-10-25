/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class RecNode(
    val id: String,
    var url: String = "https://www.youtube.com/watch?v=$id",
    var title: String = "",
    var channel: String = "",
    var channelUrl: String = "",
    var views: Long = -1,
    var duration: Long = -1,
    var uploadMs: Long = -1,
    var uploadText: String = "",
    var thumb: String = "",
    var isShort: Boolean = false,
    var depth: Int = 0,
    var seed: Boolean = false,
    var expanded: Boolean = false,
    var relevant: Int = -1, // 1 on topic, 0 off topic, -1 unknown
    var hits: Int = 0, // how many videos recommend it (in-degree)
    var weight: Double = 0.0, // sum of 1/(position+1) over recommendations
    var pr: Double = 0.0,
    var from: String = "",
    var addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title).put("channel", channel)
        .put("channelUrl", channelUrl).put("views", views).put("duration", duration).put("uploadMs", uploadMs)
        .put("uploadText", uploadText).put("thumb", thumb).put("isShort", isShort).put("depth", depth)
        .put("seed", seed).put("expanded", expanded).put("relevant", relevant).put("hits", hits)
        .put("weight", weight).put("pr", pr).put("from", from).put("addedAt", addedAt)

    fun toVideo(query: String) = Video(
        id = id, url = url, title = title, channel = channel, channelUrl = channelUrl, uploadMs = uploadMs,
        uploadText = uploadText, views = views, duration = duration, thumb = thumb, isShort = isShort, query = query
    )

    companion object {
        fun from(o: JSONObject) = RecNode(
            id = o.getString("id"), url = o.optString("url"), title = o.optString("title"),
            channel = o.optString("channel"), channelUrl = o.optString("channelUrl"), views = o.optLong("views", -1),
            duration = o.optLong("duration", -1), uploadMs = o.optLong("uploadMs", -1),
            uploadText = o.optString("uploadText"), thumb = o.optString("thumb"), isShort = o.optBoolean("isShort"),
            depth = o.optInt("depth"), seed = o.optBoolean("seed"), expanded = o.optBoolean("expanded"),
            relevant = o.optInt("relevant", -1), hits = o.optInt("hits"), weight = o.optDouble("weight", 0.0),
            pr = o.optDouble("pr", 0.0), from = o.optString("from"), addedAt = o.optLong("addedAt")
        )

        fun fromVideo(v: Video) = RecNode(
            id = v.id, url = v.url, title = v.title, channel = v.channel, channelUrl = v.channelUrl, views = v.views,
            duration = v.duration, uploadMs = v.uploadMs, uploadText = v.uploadText, thumb = v.thumb, isShort = v.isShort
        )
    }
}

data class RecEdge(val from: String, val to: String, val rank: Int)

/** Recommendation graph of a project, stored in recs.json. */
class RecGraph {
    val nodes = LinkedHashMap<String, RecNode>()
    val edges = mutableListOf<RecEdge>()
    private val keys = HashSet<String>()
    var requests = 0
    var updatedAt = 0L
    var mode = ""

    fun addEdge(from: String, to: String, rank: Int): Boolean {
        if (from == to || !keys.add("$from>$to")) return false
        edges.add(RecEdge(from, to, rank))
        nodes[to]?.let {
            it.hits++
            it.weight += 1.0 / (rank + 1)
        }
        return true
    }

    fun parents(id: String): List<RecNode> = edges.filter { it.to == id }.mapNotNull { nodes[it.from] }
    fun children(id: String): List<RecNode> = edges.filter { it.from == id }.sortedBy { it.rank }.mapNotNull { nodes[it.to] }

    fun computePageRank() {
        val ids = nodes.keys.toList()
        val index = HashMap<String, Int>()
        ids.forEachIndexed { i, s -> index[s] = i }
        val e = edges.mapNotNull { ed -> val a = index[ed.from]; val b = index[ed.to]; if (a != null && b != null) a to b else null }
        val pr = GraphAlgo.pageRank(ids.size, e)
        ids.forEachIndexed { i, s -> nodes[s]?.pr = pr[i] }
    }

    fun toJson(): JSONObject {
        val na = JSONArray()
        nodes.values.forEach { na.put(it.toJson()) }
        val ea = JSONArray()
        edges.forEach { ea.put(JSONArray().put(it.from).put(it.to).put(it.rank)) }
        return JSONObject().put("nodes", na).put("edges", ea).put("requests", requests)
            .put("updatedAt", updatedAt).put("mode", mode)
    }

    companion object {
        private const val FILE = "recs.json"

        fun load(pid: String): RecGraph {
            val g = RecGraph()
            val text = Store.readFile(pid, FILE) ?: return g
            try {
                val o = JSONObject(text)
                val na = o.optJSONArray("nodes") ?: JSONArray()
                for (i in 0 until na.length()) {
                    val n = RecNode.from(na.getJSONObject(i))
                    g.nodes[n.id] = n
                }
                val ea = o.optJSONArray("edges") ?: JSONArray()
                for (i in 0 until ea.length()) {
                    val a = ea.getJSONArray(i)
                    val from = a.getString(0)
                    val to = a.getString(1)
                    if (g.keys.add("$from>$to")) g.edges.add(RecEdge(from, to, a.optInt(2)))
                }
                g.requests = o.optInt("requests")
                g.updatedAt = o.optLong("updatedAt")
                g.mode = o.optString("mode")
            } catch (e: Exception) {
                // corrupted file: start a fresh graph
            }
            return g
        }

        fun save(pid: String, g: RecGraph) = Store.writeFile(pid, FILE, g.toJson().toString())

        fun clear(pid: String) = Store.writeFile(pid, FILE, RecGraph().toJson().toString())
    }
}

/**
 * Explores what YouTube recommends next to the project's videos (the "Up next" column),
 * without an account: snowball (beam search by depth), autoplay chain, or YouTube Mix.
 */
object RecEngine {
    private val yt get() = ServiceList.YouTube

    private class Ctx(
        val p: Project,
        val cfg: RecConfig,
        val g: RecGraph,
        val cancel: AtomicBoolean,
        val progress: (String) -> Unit
    ) {
        val keywords = Relevance.keywords(p.phrase + " " + p.queries.joinToString(" ") + " " + p.domain + " " + cfg.keywords)
        val blocked = cfg.blocked.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val perChannel = HashMap<String, Int>().also { m -> g.nodes.values.forEach { m[it.channel] = (m[it.channel] ?: 0) + 1 } }
        val now = System.currentTimeMillis()
        var requests = 0

        fun limitReached() = cancel.get() || requests >= cfg.budget || g.nodes.size >= cfg.maxNodes
    }

    private fun pause() {
        val d = Settings.requestDelayMs
        if (d > 0) Thread.sleep(d.toLong())
    }

    private fun passes(ctx: Ctx, it: StreamInfoItem): Boolean {
        val c = ctx.cfg
        val short = it.isShortFormContent || it.url.contains("/shorts/") || it.duration in 1..60
        if (c.excludeShorts && short) return false
        if (c.minViews > 0 && it.viewCount in 0 until c.minViews) return false
        if (ctx.blocked.isNotEmpty()) {
            val ch = (it.uploaderName ?: "").lowercase()
            val url = (it.uploaderUrl ?: "").lowercase()
            if (ctx.blocked.any { b -> ch.contains(b) || url.contains(b) }) return false
        }
        if (c.useProjectFilters) {
            val f = ctx.p.filters
            if (!YtQuery.durationOk(f.duration, it.duration)) return false
            val ms = it.uploadDate?.instant?.toEpochMilli() ?: -1L
            val start = if (f.period == "custom") Fmt.parseIsoDay(f.from) else YtQuery.periodStart(f.period, ctx.now)
            if (start > 0 && ms > 0 && ms < start) return false
        }
        return true
    }

    private fun node(it: StreamInfoItem, depth: Int, from: String): RecNode? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        return RecNode(
            id = id,
            title = it.name ?: "",
            channel = it.uploaderName ?: "",
            channelUrl = it.uploaderUrl ?: "",
            views = it.viewCount,
            duration = it.duration,
            uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L,
            uploadText = it.textualUploadDate ?: "",
            thumb = it.thumbnails.maxByOrNull { img -> img.width }?.url ?: "",
            isShort = it.isShortFormContent,
            depth = depth,
            from = from
        )
    }

    /** Fetches the watch page of [n] (cheap getters only, no stream deciphering) and returns its related videos. */
    private fun related(ctx: Ctx, n: RecNode): List<StreamInfoItem> {
        pause()
        val ex = yt.getStreamExtractor(n.url)
        ex.fetchPage()
        ctx.requests++
        ctx.g.requests++
        fun <T> safe(f: () -> T): T? = try {
            f()
        } catch (e: Exception) {
            null
        }
        safe { ex.name }?.takeIf { it.isNotBlank() }?.let { n.title = it }
        safe { ex.uploaderName }?.takeIf { it.isNotBlank() }?.let { n.channel = it }
        safe { ex.uploaderUrl }?.let { n.channelUrl = it }
        safe { ex.viewCount }?.takeIf { it >= 0 }?.let { n.views = it }
        safe { ex.length }?.takeIf { it > 0 }?.let { n.duration = it }
        safe { ex.uploadDate?.instant?.toEpochMilli() }?.let { n.uploadMs = it }
        if (n.thumb.isEmpty()) safe { ex.thumbnails.maxByOrNull { it.width }?.url }?.let { n.thumb = it }
        n.expanded = true
        return safe { ex.relatedItems?.items }?.filterIsInstance<StreamInfoItem>() ?: emptyList()
    }

    /** Decides relevance for nodes with unknown status (keywords, AI, or off). */
    private fun judge(ctx: Ctx, list: List<RecNode>) {
        val unknown = list.filter { it.relevant == -1 }
        if (unknown.isEmpty()) return
        when (ctx.cfg.relevance) {
            "off" -> unknown.forEach { it.relevant = 1 }
            "ai" -> {
                val prov = Provider.active()
                if (prov == null) {
                    byKeywords(ctx, unknown)
                    return
                }
                unknown.chunked(30).forEach { batch ->
                    try {
                        val data = batch.mapIndexed { i, n -> "${i + 1}. ${n.title} — ${n.channel}" }.joinToString("\n")
                        val prompt = Prompts.get("relevance").replace("{phrase}", ctx.p.phrase)
                            .replace("{domain}", ctx.p.domain.ifBlank { "-" }).replace("{data}", data)
                            .replace("{lang}", if (Settings.lang == "en") "English" else "русский")
                        val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 600, task = "relevance", pid = ctx.p.id)
                        val arr = JSONObject(JsonText.firstObject(r.text) ?: "{}").optJSONArray("relevant") ?: JSONArray()
                        val yes = (0 until arr.length()).map { arr.optInt(it) }.toSet()
                        batch.forEachIndexed { i, n -> n.relevant = if ((i + 1) in yes) 1 else 0 }
                    } catch (e: Exception) {
                        byKeywords(ctx, batch)
                    }
                }
            }
            else -> byKeywords(ctx, unknown)
        }
    }

    private fun byKeywords(ctx: Ctx, list: List<RecNode>) =
        list.forEach { it.relevant = if (Relevance.matches(ctx.keywords, it.title + " " + it.channel)) 1 else 0 }

    /** Adds up to fanout related videos of [n] to the graph; returns the new nodes. */
    private fun expand(ctx: Ctx, n: RecNode, childDepth: Int): List<RecNode> {
        val rel = try {
            related(ctx, n)
        } catch (e: Exception) {
            L.w("recs", "expand ${n.id} failed: ${e.message}")
            n.expanded = true
            return emptyList()
        }
        L.d("recs", "expand ${n.id} depth=${n.depth} related=${rel.size}")
        val fresh = mutableListOf<RecNode>()
        var rank = 0
        for (item in rel) {
            if (rank >= ctx.cfg.fanout) break
            if (!passes(ctx, item)) continue
            val id = YtQuery.videoIdFromUrl(item.url) ?: continue
            if (id == n.id) continue
            if (!ctx.g.nodes.containsKey(id)) {
                if (ctx.g.nodes.size >= ctx.cfg.maxNodes) break
                val nn = node(item, childDepth, n.id) ?: continue
                val cap = ctx.cfg.maxPerChannel
                if (cap > 0 && (ctx.perChannel[nn.channel] ?: 0) >= cap) continue
                ctx.perChannel[nn.channel] = (ctx.perChannel[nn.channel] ?: 0) + 1
                ctx.g.nodes[id] = nn
                fresh.add(nn)
            }
            ctx.g.addEdge(n.id, id, rank)
            rank++
        }
        return fresh
    }

    fun seeds(p: Project, cfg: RecConfig): List<RecNode> {
        val vids = Store.videos(p.id).values.filter { !isLocalSource(it.id) }
        val list = when (cfg.seeds) {
            "custom" -> cfg.customSeeds.split('\n', ' ', ',').mapNotNull { YtQuery.videoIdFromUrl(it.trim()) }
                .distinct().map { id -> vids.firstOrNull { it.id == id }?.let(RecNode::fromVideo) ?: RecNode(id) }
            "top" -> vids.sortedByDescending { it.views }.map(RecNode::fromVideo)
            "all" -> vids.sortedByDescending { it.addedAt }.map(RecNode::fromVideo)
            else -> vids.filter { it.marked }.ifEmpty { vids.sortedByDescending { it.views } }.map(RecNode::fromVideo)
        }
        return if (cfg.seeds == "custom") list else list.take(cfg.seedCount)
    }

    /** Estimated YouTube requests for the settings (shown before a run). */
    fun estimateRequests(cfg: RecConfig, seeds: Int): Int = when (cfg.mode) {
        "mix" -> seeds
        "chain" -> minOf(cfg.budget, seeds * cfg.depth)
        else -> cfg.budget
    }

    /** Runs the explorer for project [pid]; returns a short summary. */
    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val p = Store.project(pid) ?: return ""
        val cfg = p.recs
        val g = if (cfg.keepGraph) RecGraph.load(pid) else RecGraph()
        val seeds = seeds(p, cfg)
        if (seeds.isEmpty()) throw IllegalStateException(t("Нет стартовых видео: найдите или отметьте видео проекта.", "No seed videos: search or mark project videos first."))
        for (s in seeds) {
            val n = g.nodes.getOrPut(s.id) { s }
            n.seed = true
            n.depth = 0
            n.relevant = 1
        }
        val ctx = Ctx(p, cfg, g, cancel, progress)
        val before = g.nodes.size
        L.i("recs", "run mode=${cfg.mode} seeds=${seeds.size} depth=${cfg.depth} fanout=${cfg.fanout} budget=${cfg.budget} relevance=${cfg.relevance}")
        g.mode = cfg.mode
        when (cfg.mode) {
            "chain" -> chain(ctx, seeds.mapNotNull { g.nodes[it.id] })
            "mix" -> mix(ctx, seeds.mapNotNull { g.nodes[it.id] })
            else -> snowball(ctx)
        }
        judge(ctx, g.nodes.values.toList())
        g.computePageRank()
        g.updatedAt = System.currentTimeMillis()
        RecGraph.save(pid, g)
        val added = g.nodes.size - before
        Store.project(pid)?.let {
            it.runs.add(RunRecord(g.updatedAt, "recs:${cfg.mode}", ctx.requests, added, if (cancel.get()) "stopped" else ""))
            Store.save(it)
        }
        return t("Узлов: ${g.nodes.size} (+$added), связей: ${g.edges.size}, запросов: ${ctx.requests}",
            "Nodes: ${g.nodes.size} (+$added), edges: ${g.edges.size}, requests: ${ctx.requests}")
    }

    /** Beam search by depth: the budget is spread across levels, so deep levels are actually reached. */
    private fun snowball(ctx: Ctx) {
        val cfg = ctx.cfg
        val beam = maxOf(1, ceil(cfg.budget.toDouble() / maxOf(1, cfg.depth)).toInt())
        for (level in 0 until cfg.depth) {
            if (ctx.limitReached()) break
            val frontier = ctx.g.nodes.values.filter { it.depth == level }
            judge(ctx, frontier)
            val candidates = frontier.filter { !it.expanded && (it.relevant != 0 || cfg.expandOffTopic) }
                .sortedWith(
                    compareByDescending<RecNode> { it.seed }.thenByDescending { it.hits }
                        .thenByDescending { it.weight }.thenByDescending { it.views }
                )
            val take = if (level == 0) candidates else candidates.take(beam)
            for (n in take) {
                if (ctx.limitReached()) break
                ctx.progress(
                    t("Глубина ${level + 1}/${cfg.depth} · узлов ${ctx.g.nodes.size} · запросов ${ctx.requests}/${cfg.budget}\n",
                        "Depth ${level + 1}/${cfg.depth} · nodes ${ctx.g.nodes.size} · requests ${ctx.requests}/${cfg.budget}\n") + n.title
                )
                expand(ctx, n, level + 1)
            }
            if (ctx.g.nodes.values.none { it.depth > level && !it.expanded }) break
        }
    }

    /** Emulates autoplay: from each seed always follows the first suitable recommendation. */
    private fun chain(ctx: Ctx, seeds: List<RecNode>) {
        val cfg = ctx.cfg
        for ((si, seed) in seeds.withIndex()) {
            var cur = seed
            val visited = hashSetOf(seed.id)
            for (step in 1..cfg.depth) {
                if (ctx.limitReached()) return
                ctx.progress(
                    t("Цепочка ${si + 1}/${seeds.size} · шаг $step/${cfg.depth}\n", "Chain ${si + 1}/${seeds.size} · step $step/${cfg.depth}\n") + cur.title
                )
                val rel = try {
                    related(ctx, cur)
                } catch (e: Exception) {
                    L.w("recs", "chain step from ${cur.id} failed: ${e.message}")
                    break
                }
                val cands = rel.filter { passes(ctx, it) }.mapNotNull { item -> node(item, step, cur.id) }
                    .filter { it.id !in visited }.take(maxOf(3, cfg.fanout))
                cands.forEach { c -> ctx.g.nodes[c.id]?.let { c.relevant = it.relevant } }
                judge(ctx, cands)
                val strict = cfg.relevance != "off" && !cfg.expandOffTopic
                val pick = cands.firstOrNull { it.relevant != 0 } ?: if (strict) null else cands.firstOrNull()
                pick ?: break
                val rank = cands.indexOf(pick)
                val existing = ctx.g.nodes[pick.id]
                val n = if (existing != null) {
                    existing.depth = minOf(existing.depth, step)
                    existing
                } else {
                    if (ctx.g.nodes.size >= cfg.maxNodes) return
                    ctx.g.nodes[pick.id] = pick
                    pick
                }
                ctx.g.addEdge(cur.id, n.id, rank)
                visited.add(n.id)
                cur = n
            }
        }
    }

    /** YouTube Mix ("RD" + video id): the endless radio YouTube builds around a video. */
    private fun mix(ctx: Ctx, seeds: List<RecNode>) {
        val cfg = ctx.cfg
        val perSeed = maxOf(cfg.fanout, cfg.maxNodes / maxOf(1, seeds.size))
        for ((si, seed) in seeds.withIndex()) {
            if (ctx.limitReached()) return
            ctx.progress(t("Mix ${si + 1}/${seeds.size}\n", "Mix ${si + 1}/${seeds.size}\n") + seed.title)
            pause()
            val items = try {
                PlaylistInfo.getInfo(yt, "https://www.youtube.com/watch?v=${seed.id}&list=RD${seed.id}").relatedItems
            } catch (e: Exception) {
                L.w("recs", "mix for ${seed.id} failed: ${e.message}")
                continue
            }
            ctx.requests++
            ctx.g.requests++
            seed.expanded = true
            var rank = 0
            for (item in items) {
                if (rank >= perSeed || ctx.g.nodes.size >= cfg.maxNodes) break
                if (!passes(ctx, item)) continue
                val nn = node(item, 1, seed.id) ?: continue
                if (nn.id == seed.id) continue
                if (!ctx.g.nodes.containsKey(nn.id)) ctx.g.nodes[nn.id] = nn
                ctx.g.addEdge(seed.id, nn.id, rank)
                rank++
            }
        }
    }

    /**
     * Drip mode for monitoring: adds the top [count] recommended on-topic videos that are not yet
     * in the project, then expands them one level so the graph keeps growing. Returns added videos.
     */
    fun drip(pid: String, count: Int): List<Video> {
        val p = Store.project(pid) ?: return emptyList()
        val g = RecGraph.load(pid)
        if (g.nodes.isEmpty()) return emptyList()
        val inProject = Store.videos(pid).keys
        val picks = g.nodes.values.filter { !it.seed && it.relevant != 0 && it.id !in inProject }
            .sortedWith(compareByDescending<RecNode> { it.pr }.thenByDescending { it.hits })
            .take(count)
        if (picks.isEmpty()) return emptyList()
        val videos = picks.map { it.toVideo("rec") }
        Store.mergeVideos(pid, videos)
        val ctx = Ctx(p, p.recs, g, AtomicBoolean(false)) { }
        for (n in picks) {
            if (ctx.requests >= p.recs.budget) break
            expand(ctx, n, n.depth + 1)
        }
        judge(ctx, g.nodes.values.toList())
        g.computePageRank()
        g.updatedAt = System.currentTimeMillis()
        RecGraph.save(pid, g)
        return videos
    }

    // ------------------------------------------------------------ analysis helpers
    data class DepthStat(val depth: Int, val nodes: Int, val onTopic: Int)

    fun drift(g: RecGraph): List<DepthStat> = g.nodes.values.groupBy { it.depth }.toSortedMap().map { (d, l) ->
        DepthStat(d, l.size, l.count { it.relevant == 1 })
    }

    fun ranked(g: RecGraph, by: String): List<RecNode> {
        val list = g.nodes.values.filter { !it.seed }
        return when (by) {
            "pr" -> list.sortedByDescending { it.pr }
            "views" -> list.sortedByDescending { it.views }
            "depth" -> list.sortedWith(compareByDescending<RecNode> { it.depth }.thenByDescending { it.hits })
            else -> list.sortedWith(compareByDescending<RecNode> { it.hits }.thenByDescending { it.weight })
        }
    }

    // ------------------------------------------------------------ YouTube extras
    /** YouTube search suggestions (what people actually type). */
    fun suggestions(query: String): List<String> = yt.suggestionExtractor.suggestionList(query).filter { it.isNotBlank() }

    fun trendingKiosks(): List<String> = yt.kioskList.availableKiosks.toList().sorted()

    /** Trending kiosk videos, optionally filtered by the project's keywords. */
    fun trending(p: Project, kioskId: String, onlyOnTopic: Boolean): List<Video> {
        val ex = yt.kioskList.getExtractorById(kioskId, null)
        ex.fetchPage()
        val kw = Relevance.keywords(p.phrase + " " + p.queries.joinToString(" ") + " " + p.recs.keywords)
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { node(it, 0, "")?.toVideo("trending: $kioskId") }
            .filter { !onlyOnTopic || Relevance.matches(kw, it.title + " " + it.channel) }
    }
}

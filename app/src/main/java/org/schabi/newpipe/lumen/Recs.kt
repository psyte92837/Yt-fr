/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
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
    var hits: Int = 0, // how many videos recommend it (in-degree), derived from edges
    var weight: Double = 0.0, // sum of edge strengths (position weight; link count in the channel graph), derived
    var pr: Double = 0.0,
    var from: String = "",
    var addedAt: Long = System.currentTimeMillis(),
    var score: Double = -1.0, // text relevance 0..1 (TF-IDF against the topic), -1 = not scored
    var tier: Int = -2, // AI grade: 3 core, 2 on topic, 1 neighbouring niche, 0 unrelated, -1 unsure, -2 not asked
    var label: String = "", // niche label for neighbouring/unrelated videos (AI)
    var niche: Int = -1, // community of the last analysis
    var seenAt: Long = 0, // when its watch page was read
    var ppr: Double = 0.0 // personalised PageRank from the seeds: closeness to "my" videos
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title).put("channel", channel)
        .put("channelUrl", channelUrl).put("views", views).put("duration", duration).put("uploadMs", uploadMs)
        .put("uploadText", uploadText).put("thumb", thumb).put("isShort", isShort).put("depth", depth)
        .put("seed", seed).put("expanded", expanded).put("relevant", relevant).put("hits", hits)
        .put("weight", weight).put("pr", pr).put("from", from).put("addedAt", addedAt)
        .put("score", score).put("tier", tier).put("label", label).put("niche", niche).put("seenAt", seenAt).put("ppr", ppr)

    fun toVideo(query: String) = Video(
        id = id, url = url, title = title, channel = channel, channelUrl = channelUrl, uploadMs = uploadMs,
        uploadText = uploadText, views = views, duration = duration, thumb = thumb, isShort = isShort, query = query
    )

    /** Neighbouring niche (AI grade 1): off topic, but where the topic's viewers go. */
    val adjacent get() = tier == 1

    companion object {
        fun from(o: JSONObject) = RecNode(
            id = o.getString("id"), url = o.optString("url"), title = o.optString("title"),
            channel = o.optString("channel"), channelUrl = o.optString("channelUrl"), views = o.optLong("views", -1),
            duration = o.optLong("duration", -1), uploadMs = o.optLong("uploadMs", -1),
            uploadText = o.optString("uploadText"), thumb = o.optString("thumb"), isShort = o.optBoolean("isShort"),
            depth = o.optInt("depth"), seed = o.optBoolean("seed"), expanded = o.optBoolean("expanded"),
            relevant = o.optInt("relevant", -1), hits = o.optInt("hits"), weight = o.optDouble("weight", 0.0),
            pr = o.optDouble("pr", 0.0), from = o.optString("from"), addedAt = o.optLong("addedAt"),
            score = o.optDouble("score", -1.0), tier = o.optInt("tier", -2), label = o.optString("label"),
            niche = o.optInt("niche", -1), seenAt = o.optLong("seenAt"), ppr = o.optDouble("ppr", 0.0)
        )

        fun fromVideo(v: Video) = RecNode(
            id = v.id, url = v.url, title = v.title, channel = v.channel, channelUrl = v.channelUrl, views = v.views,
            duration = v.duration, uploadMs = v.uploadMs, uploadText = v.uploadText, thumb = v.thumb, isShort = v.isShort
        )
    }
}

/**
 * A recommendation seen on a watch page. [rank] is the raw place in "Up next" (0 = first card, before any filter);
 * kind: rel (snowball/drip), chain, mix (radio order), walk, ch (channel graph, [w] = number of links), audit.
 */
data class RecEdge(
    val from: String,
    val to: String,
    var rank: Int,
    val kind: String = "rel",
    var w: Double = 1.0,
    var seen: Int = 1,
    var first: Long = 0,
    var last: Long = 0
) {
    /** Edge strength for rankings: link count in the channel graph, else the position weight. */
    val strength get() = if (kind == "ch") w else AlgoCore.posWeight(rank)
}

/** Off-topic neighbour removed from the channel graph, kept as "where viewers leak". */
data class RecExit(val id: String, val title: String, val url: String, val label: String, val links: Double) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("label", label).put("links", links)

    companion object {
        fun from(o: JSONObject) = RecExit(o.optString("id"), o.optString("title"), o.optString("url"), o.optString("label"), o.optDouble("links"))
    }
}

/** Recommendation graph of a project, stored in recs.json (schema 2). */
class RecGraph {
    val nodes = LinkedHashMap<String, RecNode>()
    val edges = mutableListOf<RecEdge>()
    private val index = HashMap<String, RecEdge>()
    var requests = 0
    var updatedAt = 0L
    var mode = ""
    var schema = SCHEMA
    var gl = ""
    var strategy = ""
    val exits = mutableListOf<RecExit>()
    val runs = mutableListOf<JSONObject>()

    /** Adds an observation of from -> to. A repeated one updates seen/last/rank and returns false. */
    fun addEdge(from: String, to: String, rank: Int, kind: String = "rel", w: Double = 1.0, at: Long = System.currentTimeMillis()): Boolean {
        if (from == to) return false
        val key = "$from>$to"
        index[key]?.let { old ->
            old.seen++
            if (at >= old.last) {
                old.last = at
                old.rank = rank
            }
            if (w > old.w) old.w = w
            return false
        }
        val e = RecEdge(from, to, rank, kind, w, 1, at, at)
        edges.add(e)
        index[key] = e
        nodes[to]?.let {
            it.hits++
            it.weight += e.strength
        }
        return true
    }

    fun edge(from: String, to: String): RecEdge? = index["$from>$to"]

    fun rebuildKeys() {
        index.clear()
        edges.forEach { index["${it.from}>${it.to}"] = it }
    }

    /** hits and weight are derived from the edges: recount them (after load, prune or merge). */
    fun recompute() {
        nodes.values.forEach {
            it.hits = 0
            it.weight = 0.0
        }
        for (e in edges) {
            if (!nodes.containsKey(e.from)) continue
            val n = nodes[e.to] ?: continue
            n.hits++
            n.weight += e.strength
        }
    }

    fun parents(id: String): List<RecNode> = edges.filter { it.to == id }.mapNotNull { nodes[it.from] }
    fun children(id: String): List<RecNode> = edges.filter { it.from == id }.sortedBy { it.rank }.mapNotNull { nodes[it.to] }

    /** Index view for the pure algorithms: node ids in order and weighted edges. */
    fun indexed(): Pair<List<String>, List<IEdge>> {
        val ids = nodes.keys.toList()
        val ix = HashMap<String, Int>()
        ids.forEachIndexed { i, s -> ix[s] = i }
        val e = edges.mapNotNull { ed -> val a = ix[ed.from]; val b = ix[ed.to]; if (a != null && b != null) IEdge(a, b, ed.strength) else null }
        return ids to e
    }

    /** Weighted PageRank (uniform restart) into pr and personalised PageRank from the seeds into ppr. */
    fun computePageRank() {
        val (ids, e) = indexed()
        val pr = AlgoCore.pageRank(ids.size, e)
        val seeds = DoubleArray(ids.size) { if (nodes[ids[it]]?.seed == true) 1.0 else 0.0 }
        val ppr = if (seeds.sum() > 0) AlgoCore.pageRank(ids.size, e, restart = seeds) else pr
        ids.forEachIndexed { i, s ->
            nodes[s]?.pr = pr[i]
            nodes[s]?.ppr = ppr[i]
        }
    }

    fun toJson(): JSONObject {
        val na = JSONArray()
        nodes.values.forEach { na.put(it.toJson()) }
        val ea = JSONArray()
        edges.forEach { ea.put(JSONArray().put(it.from).put(it.to).put(it.rank).put(it.kind).put(it.w).put(it.seen).put(it.first).put(it.last)) }
        val xa = JSONArray()
        exits.forEach { xa.put(it.toJson()) }
        val ra = JSONArray()
        runs.takeLast(40).forEach { ra.put(it) }
        return JSONObject().put("schema", SCHEMA).put("nodes", na).put("edges", ea).put("requests", requests)
            .put("updatedAt", updatedAt).put("mode", mode).put("gl", gl).put("strategy", strategy).put("exits", xa).put("runs", ra)
    }

    companion object {
        const val SCHEMA = 2
        const val FILE = "recs.json"
        const val CHANNELS_FILE = "chgraph.json"
        const val SNAPS_FILE = "recs_snaps.json"

        fun parse(text: String): RecGraph {
            val g = RecGraph()
            try {
                val o = JSONObject(text)
                g.mode = o.optString("mode")
                g.schema = o.optInt("schema", 1)
                g.requests = o.optInt("requests")
                g.updatedAt = o.optLong("updatedAt")
                g.gl = o.optString("gl")
                g.strategy = o.optString("strategy")
                val na = o.optJSONArray("nodes") ?: JSONArray()
                for (i in 0 until na.length()) {
                    val n = RecNode.from(na.getJSONObject(i))
                    g.nodes[n.id] = n
                }
                val defKind = if (g.mode == "channels") "ch" else "rel"
                val ea = o.optJSONArray("edges") ?: JSONArray()
                for (i in 0 until ea.length()) {
                    val a = ea.getJSONArray(i)
                    val from = a.getString(0)
                    val to = a.getString(1)
                    if (g.index.containsKey("$from>$to")) continue
                    val e = RecEdge(
                        from, to, a.optInt(2), a.optString(3, defKind).ifBlank { defKind }, a.optDouble(4, 1.0),
                        a.optInt(5, 1), a.optLong(6, g.updatedAt), a.optLong(7, g.updatedAt)
                    )
                    g.edges.add(e)
                    g.index["$from>$to"] = e
                }
                o.optJSONArray("exits")?.let { xa -> for (i in 0 until xa.length()) g.exits.add(RecExit.from(xa.getJSONObject(i))) }
                o.optJSONArray("runs")?.let { ra -> for (i in 0 until ra.length()) ra.optJSONObject(i)?.let(g.runs::add) }
                // schema 1 stored weights with other meanings (and the old channel graph lost its link counts): keep them
                if (g.schema >= 2) g.recompute()
            } catch (e: Exception) {
                // corrupted file: start a fresh graph
            }
            return g
        }

        fun load(pid: String, file: String = FILE): RecGraph = Store.readFile(pid, file)?.let(::parse) ?: RecGraph()

        fun save(pid: String, g: RecGraph, file: String = FILE) = Store.writeFile(pid, file, g.toJson().toString())

        fun clear(pid: String, file: String = FILE) = Store.writeFile(pid, file, RecGraph().toJson().toString())

        // ---------------- snapshots: compact copies of the graph for "what changed"

        /** Compact snapshot: the strongest nodes by closeness to the seeds and all edge keys. */
        fun snapshotOf(g: RecGraph, at: Long = g.updatedAt): RecSnap {
            val top = g.nodes.values.filter { !it.seed }.sortedByDescending { it.ppr }.take(150)
                .map { RecSnap.Node(it.id, it.title, it.channel, it.hits, it.ppr, it.relevant) }
            return RecSnap(at, top, g.edges.take(4000).map { "${it.from}>${it.to}" }.toSet(), g.nodes.size)
        }

        /** Stores a snapshot (one per day, the last [keep]). */
        fun snapshot(pid: String, g: RecGraph, keep: Int = 8) {
            val s = snapshotOf(g)
            val day = Fmt.isoDay(s.at)
            val list = snapshots(pid).filter { Fmt.isoDay(it.at) != day } + s
            val a = JSONArray()
            list.sortedBy { it.at }.takeLast(keep).forEach { a.put(it.toJson()) }
            Store.writeFile(pid, SNAPS_FILE, a.toString())
        }

        fun snapshots(pid: String): List<RecSnap> = try {
            val a = JSONArray(Store.readFile(pid, SNAPS_FILE) ?: "[]")
            (0 until a.length()).map { RecSnap.from(a.getJSONObject(it)) }.sortedBy { it.at }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Compact snapshot of a recommendation graph (see [RecGraph.snapshot]). */
class RecSnap(val at: Long, val top: List<Node>, val edges: Set<String>, val size: Int) {
    class Node(val id: String, val title: String, val channel: String, val hits: Int, val ppr: Double, val rel: Int)

    fun toJson(): JSONObject {
        val t = JSONArray()
        top.forEach { t.put(JSONArray().put(it.id).put(it.title).put(it.channel).put(it.hits).put(it.ppr).put(it.rel)) }
        return JSONObject().put("at", at).put("size", size).put("top", t).put("edges", JSONArray(edges.toList()))
    }

    companion object {
        fun from(o: JSONObject): RecSnap {
            val t = o.optJSONArray("top") ?: JSONArray()
            val e = o.optJSONArray("edges") ?: JSONArray()
            return RecSnap(
                o.optLong("at"),
                (0 until t.length()).map { i -> t.getJSONArray(i).let { Node(it.getString(0), it.optString(1), it.optString(2), it.optInt(3), it.optDouble(4), it.optInt(5, -1)) } },
                (0 until e.length()).map { e.getString(it) }.toSet(), o.optInt("size")
            )
        }
    }
}

/**
 * Explores what YouTube recommends next to the project's videos (the "Up next" column),
 * without an account: snowball (beam by depth, or random walks), autoplay chain, or YouTube Mix.
 * Every watch page goes through [WatchNext] (region of the project, disk cache, raw positions).
 */
object RecEngine {
    private val yt get() = ServiceList.YouTube
    private const val DAY = 86_400_000L

    private class Ctx(
        val p: Project,
        val cfg: RecConfig,
        val g: RecGraph,
        val cancel: AtomicBoolean,
        val progress: (String) -> Unit
    ) {
        val keywords = TopicFilter.keywordsOf(p, cfg.keywords)
        val blocked = cfg.blocked.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val perChannel = HashMap<String, Int>() // new videos per channel in this run
        val now = System.currentTimeMillis()
        val gl = p.filters.country
        val rnd = java.util.Random(now)
        var requests = 0

        fun limitReached() = cancel.get() || requests >= cfg.budget || g.nodes.size >= cfg.maxNodes && cfg.mode != "snowball"
    }

    private fun passes(ctx: Ctx, it: WnItem): Boolean {
        val c = ctx.cfg
        if (c.excludeShorts && it.isShort) return false
        if (c.minViews > 0 && it.views in 0 until c.minViews) return false
        if (ctx.blocked.isNotEmpty()) {
            val ch = it.channel.lowercase()
            val url = it.channelUrl.lowercase()
            if (ctx.blocked.any { b -> ch.contains(b) || url.contains(b) }) return false
        }
        if (c.useProjectFilters) {
            val f = ctx.p.filters
            if (!YtQuery.durationOk(f.duration, it.duration)) return false
            val start = if (f.period == "custom") Fmt.parseIsoDay(f.from) else YtQuery.periodStart(f.period, ctx.now)
            if (start > 0 && it.uploadMs > 0 && it.uploadMs < start) return false
        }
        return true
    }

    private fun node(it: WnItem, depth: Int, from: String) = RecNode(
        id = it.id, title = it.title, channel = it.channel, channelUrl = it.channelUrl, views = it.views,
        duration = it.duration, uploadMs = it.uploadMs, uploadText = it.uploadText, thumb = it.thumb,
        isShort = it.isShort, depth = depth, from = from
    )

    /** Never read, or read longer ago than refreshDays (so a kept graph follows YouTube's changes). */
    fun stale(n: RecNode, cfg: RecConfig, now: Long = System.currentTimeMillis()): Boolean =
        !n.expanded || (cfg.keepGraph && cfg.refreshDays > 0 && n.seenAt > 0 && now - n.seenAt > cfg.refreshDays * DAY)

    /** Reads the watch page of [n] (cache or one request), refreshes its metadata and returns "Up next". */
    private fun related(ctx: Ctx, n: RecNode): List<WnItem> {
        val page = WatchNext.get(n.id, ctx.gl)
        if (!page.cached) synchronized(ctx) {
            ctx.requests++
            ctx.g.requests++
        }
        page.title.takeIf { it.isNotBlank() }?.let { n.title = it }
        page.channel.takeIf { it.isNotBlank() }?.let { n.channel = it }
        page.channelUrl.takeIf { it.isNotBlank() }?.let { n.channelUrl = it }
        page.views.takeIf { it >= 0 }?.let { n.views = it }
        page.duration.takeIf { it > 0 }?.let { n.duration = it }
        page.uploadMs.takeIf { it > 0 }?.let { n.uploadMs = it }
        if (n.thumb.isEmpty() && page.thumb.isNotBlank()) n.thumb = page.thumb
        n.expanded = true
        n.seenAt = page.at
        return page.items
    }

    /** Decides relevance for nodes with unknown status (text score, AI for the doubtful, or off). */
    private fun judge(ctx: Ctx, list: List<RecNode>) = AlgoJudge.videos(ctx.p, list, ctx.cfg.relevance, ctx.keywords) { ctx.progress(it) }

    /** Reads "Up next" of [n] and links it; returns the new nodes. */
    private fun expand(ctx: Ctx, n: RecNode, childDepth: Int): List<RecNode> {
        val rel = try {
            related(ctx, n)
        } catch (e: Exception) {
            L.w("recs", "expand ${n.id} failed: ${e.message}")
            n.expanded = true
            return emptyList()
        }
        return link(ctx, n, childDepth, rel)
    }

    /**
     * Graph edits for an already read "Up next" (always on the calling thread). Every card that is already a node
     * gets an edge (whatever its place: these closing links are the structure); new nodes are created only for the
     * first [RecConfig.fanout] cards that pass the filters, while the node limit allows.
     */
    private fun link(ctx: Ctx, n: RecNode, childDepth: Int, rel: List<WnItem>, kind: String = "rel"): List<RecNode> {
        val fresh = mutableListOf<RecNode>()
        var added = 0
        val at = if (n.seenAt > 0) n.seenAt else ctx.now
        for (item in rel) {
            if (item.id == n.id) continue
            if (ctx.g.nodes.containsKey(item.id)) {
                ctx.g.addEdge(n.id, item.id, item.pos, kind, at = at)
                continue
            }
            if (added >= ctx.cfg.fanout || ctx.g.nodes.size >= ctx.cfg.maxNodes || !passes(ctx, item)) continue
            val cap = ctx.cfg.maxPerChannel
            if (cap > 0 && (ctx.perChannel[item.channel] ?: 0) >= cap) continue
            ctx.perChannel[item.channel] = (ctx.perChannel[item.channel] ?: 0) + 1
            val nn = node(item, childDepth, n.id)
            ctx.g.nodes[nn.id] = nn
            ctx.g.addEdge(n.id, nn.id, item.pos, kind, at = at)
            fresh.add(nn)
            added++
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

    /** Estimated YouTube requests for the settings (shown before a run; cached pages cost nothing). */
    fun estimateRequests(cfg: RecConfig, seeds: Int): Int = when (cfg.mode) {
        "mix" -> seeds
        "chain" -> minOf(cfg.budget, seeds * cfg.depth)
        else -> cfg.budget
    }

    /** Runs the explorer for project [pid]; returns a short summary. */
    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        if ((Store.project(pid)?.recs?.relevance ?: "off") != "off") {
            progress(t("Подтверждение темы…", "Confirming topic…"))
            TopicFilter.ensure(pid)
        }
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
        val edgesBefore = g.edges.size
        L.i("recs", "run mode=${cfg.mode} strategy=${cfg.strategy} seeds=${seeds.size} depth=${cfg.depth} fanout=${cfg.fanout} budget=${cfg.budget} gl=${ctx.gl} relevance=${cfg.relevance}")
        g.mode = cfg.mode
        g.gl = ctx.gl
        g.strategy = if (cfg.mode == "snowball") cfg.strategy else cfg.mode
        when (cfg.mode) {
            "chain" -> chain(ctx, seeds.mapNotNull { g.nodes[it.id] })
            "mix" -> mix(ctx, seeds.mapNotNull { g.nodes[it.id] })
            else -> if (cfg.strategy == "rwr") walks(ctx) else snowball(ctx)
        }
        judge(ctx, g.nodes.values.toList())
        g.recompute()
        g.computePageRank()
        g.updatedAt = System.currentTimeMillis()
        val added = g.nodes.size - before
        g.runs.add(
            JSONObject().put("at", g.updatedAt).put("mode", cfg.mode).put("strategy", g.strategy).put("gl", ctx.gl)
                .put("seeds", seeds.size).put("requests", ctx.requests).put("added", added).put("edges", g.edges.size - edgesBefore)
        )
        RecGraph.save(pid, g)
        RecGraph.snapshot(pid, g)
        Store.project(pid)?.let {
            it.runs.add(RunRecord(g.updatedAt, "recs:${cfg.mode}", ctx.requests, added, if (cancel.get()) "stopped" else ""))
            Store.save(it)
        }
        return t("Узлов: ${g.nodes.size} (+$added), связей: ${g.edges.size}, запросов: ${ctx.requests}",
            "Nodes: ${g.nodes.size} (+$added), edges: ${g.edges.size}, requests: ${ctx.requests}")
    }

    /**
     * Picks [beam] videos of a level to open. "top": the most recommended (fast to the core, biased to it);
     * "mixed": half top, half at random (seeded), so the periphery and the drift are seen too.
     */
    fun pick(cands: List<RecNode>, beam: Int, strategy: String, rnd: java.util.Random): List<RecNode> {
        val sorted = cands.sortedWith(
            compareByDescending<RecNode> { it.seed }.thenByDescending { it.hits }.thenByDescending { it.weight }.thenByDescending { it.views }
        )
        if (strategy != "mixed" || sorted.size <= beam) return sorted.take(beam)
        val half = (beam + 1) / 2
        return sorted.take(half) + sorted.drop(half).shuffled(rnd).take(beam - half)
    }

    /** Beam search by depth: the budget is spread across levels, so deep levels are actually reached. */
    private fun snowball(ctx: Ctx) {
        val cfg = ctx.cfg
        val beam = maxOf(1, ceil(cfg.budget.toDouble() / maxOf(1, cfg.depth)).toInt())
        for (level in 0 until cfg.depth) {
            if (ctx.limitReached()) break
            val frontier = ctx.g.nodes.values.filter { it.depth == level }
            judge(ctx, frontier)
            val candidates = frontier.filter { stale(it, cfg, ctx.now) && (it.relevant != 0 || cfg.expandOffTopic) }
            val take = if (level == 0) candidates else pick(candidates, beam, cfg.strategy, ctx.rnd)
            // watch pages of a level are independent: fetch them in parallel chunks, edit the graph sequentially
            for (chunk in take.chunked(maxOf(1, Settings.parallel * 2))) {
                if (ctx.limitReached()) break
                val batch = chunk.take(maxOf(0, cfg.budget - ctx.requests))
                val fetched = Par.map(batch, cancel = ctx.cancel) { _, n ->
                    ctx.progress(
                        t("Глубина ${level + 1}/${cfg.depth} · узлов ${ctx.g.nodes.size} · запросов ${ctx.requests}/${cfg.budget}\n",
                            "Depth ${level + 1}/${cfg.depth} · nodes ${ctx.g.nodes.size} · requests ${ctx.requests}/${cfg.budget}\n") + n.title
                    )
                    try {
                        related(ctx, n)
                    } catch (e: Exception) {
                        L.w("recs", "expand ${n.id} failed: ${e.message}")
                        n.expanded = true
                        null
                    }
                }
                for ((i, n) in batch.withIndex()) fetched[i]?.let { link(ctx, n, level + 1, it) }
            }
            if (ctx.g.nodes.values.none { it.depth > level && stale(it, cfg, ctx.now) }) break
        }
    }

    /**
     * Random walks with restart (strategy "rwr"): from a random seed the viewer follows a card chosen by its
     * position weight and returns to a seed with probability 0.15 or after [RecConfig.depth] steps.
     * An unbiased sample of where "Up next" leads, at the cost of a sparser graph.
     */
    private fun walks(ctx: Ctx) {
        val cfg = ctx.cfg
        val seeds = ctx.g.nodes.values.filter { it.seed }
        if (seeds.isEmpty()) return
        var cur = seeds[ctx.rnd.nextInt(seeds.size)]
        var step = 0
        var guard = 0
        while (!ctx.limitReached() && guard++ < cfg.budget * 4) {
            ctx.progress(t("Блуждания · шаг $step · узлов ${ctx.g.nodes.size} · запросов ${ctx.requests}/${cfg.budget}\n",
                "Walks · step $step · nodes ${ctx.g.nodes.size} · requests ${ctx.requests}/${cfg.budget}\n") + cur.title)
            val items = try {
                related(ctx, cur)
            } catch (e: Exception) {
                L.w("recs", "walk from ${cur.id} failed: ${e.message}")
                emptyList()
            }
            link(ctx, cur, cur.depth + 1, items, "walk")
            step++
            val kids = items.take(10).mapNotNull { ctx.g.nodes[it.id]?.let { n -> n to it.pos } }
                .filter { (n, _) -> n.id != cur.id && (n.relevant != 0 || cfg.expandOffTopic) }
            if (kids.isEmpty() || step >= cfg.depth || ctx.rnd.nextDouble() < 0.15) {
                cur = seeds[ctx.rnd.nextInt(seeds.size)]
                step = 0
                continue
            }
            val total = kids.sumOf { AlgoCore.posWeight(it.second) }
            var r = ctx.rnd.nextDouble() * total
            cur = kids.firstOrNull { (_, pos) -> r -= AlgoCore.posWeight(pos); r <= 0 }?.first ?: kids.last().first
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
                val cands = rel.filter { it.id !in visited && it.id != cur.id && passes(ctx, it) }.take(maxOf(3, cfg.fanout))
                    .map { item -> (ctx.g.nodes[item.id] ?: node(item, step, cur.id)) to item.pos }
                judge(ctx, cands.map { it.first }.filter { !ctx.g.nodes.containsKey(it.id) })
                val strict = cfg.relevance != "off" && !cfg.expandOffTopic
                val pick = cands.firstOrNull { it.first.relevant != 0 } ?: if (strict) null else cands.firstOrNull()
                pick ?: break
                val (pn, pos) = pick
                val n = ctx.g.nodes[pn.id]?.also { it.depth = minOf(it.depth, step) } ?: run {
                    if (ctx.g.nodes.size >= cfg.maxNodes) return
                    ctx.g.nodes[pn.id] = pn
                    pn
                }
                ctx.g.addEdge(cur.id, n.id, pos, "chain", at = cur.seenAt)
                visited.add(n.id)
                cur = n
            }
        }
    }

    /** YouTube Mix ("RD" + video id): the radio YouTube builds around a video, linked in playing order. */
    private fun mix(ctx: Ctx, seeds: List<RecNode>) {
        val cfg = ctx.cfg
        val perSeed = maxOf(cfg.fanout, cfg.maxNodes / maxOf(1, seeds.size))
        val live = seeds.take(maxOf(0, cfg.budget - ctx.requests))
        val fetched = Par.map(live, cancel = ctx.cancel) { si, seed ->
            ctx.progress(t("Mix ${si + 1}/${seeds.size}\n", "Mix ${si + 1}/${seeds.size}\n") + seed.title)
            try {
                WatchNext.mix(seed.id, ctx.gl).also {
                    synchronized(ctx) {
                        ctx.requests++
                        ctx.g.requests++
                    }
                }
            } catch (e: Exception) {
                L.w("recs", "mix for ${seed.id} failed: ${e.message}")
                null
            }
        }
        for ((si, seed) in live.withIndex()) {
            if (ctx.cancel.get()) return
            val items = fetched[si] ?: continue
            seed.expanded = true
            var prev = seed
            var count = 0
            for (item in items) {
                if (count >= perSeed) break
                if (item.id == seed.id || !passes(ctx, item)) continue
                val nn = ctx.g.nodes[item.id]?.also { it.depth = minOf(it.depth, count + 1) } ?: run {
                    if (ctx.g.nodes.size >= cfg.maxNodes) null else node(item, count + 1, prev.id).also { ctx.g.nodes[it.id] = it }
                } ?: break
                ctx.g.addEdge(prev.id, nn.id, 0, "mix")
                prev = nn
                count++
            }
        }
    }

    /**
     * Drip mode for monitoring: adds the top [count] recommended on-topic videos that are not yet in the project,
     * expands them one level and re-reads the three strongest hubs, so the graph keeps growing and follows changes.
     * Returns added videos.
     */
    fun drip(pid: String, count: Int): List<Video> {
        val p = Store.project(pid) ?: return emptyList()
        val g = RecGraph.load(pid)
        if (g.nodes.isEmpty()) return emptyList()
        val inProject = Store.videos(pid).keys
        val picks = g.nodes.values.filter { !it.seed && it.relevant != 0 && it.id !in inProject }
            .sortedWith(compareByDescending<RecNode> { it.ppr }.thenByDescending { it.hits })
            .take(count)
        val videos = picks.map { it.toVideo("rec") }
        if (videos.isNotEmpty()) Store.mergeVideos(pid, videos)
        val ctx = Ctx(p, p.recs, g, AtomicBoolean(false)) { }
        for (n in picks) {
            if (ctx.requests >= p.recs.budget) break
            expand(ctx, n, n.depth + 1)
        }
        val hubs = g.nodes.values.filter { it.expanded && !it.seed && it.relevant != 0 }.sortedByDescending { it.ppr }.take(3)
        for (h in hubs) {
            if (ctx.requests >= p.recs.budget) break
            expand(ctx, h, h.depth + 1)
        }
        judge(ctx, g.nodes.values.toList())
        g.recompute()
        g.computePageRank()
        g.updatedAt = System.currentTimeMillis()
        RecGraph.save(pid, g)
        RecGraph.snapshot(pid, g)
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
            "ppr" -> list.sortedByDescending { it.ppr }
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
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapIndexedNotNull { i, it -> WatchNext.item(it, i)?.let { w -> node(w, 0, "").toVideo("trending: $kioskId") } }
            .filter { !onlyOnTopic || Relevance.matches(kw, it.title + " " + it.channel) }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/** The method line that goes first into every algo_* prompt and report: what the graph can and cannot show. */
object AlgoMethod {
    fun strategyName(s: String) = when (s) {
        "top" -> t("луч по самым рекомендуемым", "beam by most recommended")
        "mixed" -> t("смешанный луч (половина случайно)", "mixed beam (half random)")
        "rwr" -> t("случайные блуждания с рестартом", "random walks with restart")
        "chain" -> t("цепочка автовоспроизведения", "autoplay chain")
        "mix" -> "YouTube Mix"
        "channels" -> t("граф каналов", "channel graph")
        "audit" -> t("аудит", "audit")
        else -> s.ifBlank { "—" }
    }

    fun region(gl: String) = gl.ifBlank { t("по умолчанию приложения", "app default") }

    fun graph(g: RecGraph): String {
        val expanded = g.nodes.values.count { it.expanded }
        val seeds = g.nodes.values.count { it.seed }
        return t(
            "МЕТОД: «Следующее» YouTube без входа в аккаунт (неперсонализированный слой видео→видео), регион ${region(g.gl)}, " +
                "${Fmt.dateTime(g.updatedAt)}. Видна первая страница (~20 карточек); прогонов: ${max(1, g.runs.size)}. " +
                "Обход: ${strategyName(g.strategy.ifBlank { g.mode })}, сидов $seeds, раскрыто $expanded из ${g.nodes.size}. " +
                "Не видно: персональные рекомендации, главная, подписки, время просмотра, CTR.",
            "METHOD: YouTube “Up next” without signing in (non-personalised video→video layer), region ${region(g.gl)}, " +
                "${Fmt.dateTime(g.updatedAt)}. First page only (~20 cards); runs: ${max(1, g.runs.size)}. " +
                "Crawl: ${strategyName(g.strategy.ifBlank { g.mode })}, $seeds seeds, $expanded of ${g.nodes.size} opened. " +
                "Not visible: personal recommendations, home, subscriptions, watch time, CTR."
        )
    }

    fun audit(run: AuditRun, gl: String): String = t(
        "МЕТОД: виртуальный зритель без аккаунта идёт по «Следующему» (неперсонализированный слой), регион ${region(gl)}, " +
            "${Fmt.dateTime(run.at)}. Смотрит top-${run.cfg.topK} карточек; ${if (run.cfg.fresh) "каждая страница читается заново" else "страницы кешируются в прогоне"}. " +
            "Не видно: персональные рекомендации, время просмотра, CTR.",
        "METHOD: a virtual viewer without an account follows “Up next” (non-personalised layer), region ${region(gl)}, " +
            "${Fmt.dateTime(run.at)}. Looks at the top-${run.cfg.topK} cards; ${if (run.cfg.fresh) "every page is read anew" else "pages are cached within the run"}. " +
            "Not visible: personal recommendations, watch time, CTR."
    )
}

/** A niche (community) of the recommendation graph with its demand and supply. */
class AlgoNiche(
    val index: Int,
    val key: String,
    val members: List<Int>,
    val terms: List<String>,
    var name: String,
    var about: String,
    val onShare: Double,
    val channels: List<Pair<String, Int>>,
    val hhi: Double,
    val velocity: Double,
    val freshShare: Double,
    val authShare: Double,
    val channels90: Int,
    val oldAuthShare: Double,
    val durLo: Long,
    val durHi: Long,
    var opportunity: Double = 0.0
) {
    val label get() = name.ifBlank { terms.take(3).joinToString(", ").ifBlank { key } }
}

/** Channel in the video graph: links in (from other channels), out (to other channels) and to itself. */
class AlgoChannel(val name: String, val url: String, val videos: Int, val inflow: Double, val outflow: Double, val self: Double) {
    val retention get() = if (self + outflow <= 0) 0.0 else self / (self + outflow)
    val net get() = inflow - outflow
}

class AlgoResult(
    val pid: String,
    val g: RecGraph,
    val nodes: List<RecNode>,
    val edges: List<IEdge>,
    val ix: Map<String, Int>,
    val auth: DoubleArray,
    val hub: DoubleArray,
    val btw: DoubleArray,
    val comm: IntArray,
    val niches: List<AlgoNiche>,
    val nicheFlows: Map<Pair<Int, Int>, Double>,
    val leak: AlgoCore.Leak,
    val leakByDepth: List<Pair<Int, AlgoCore.Leak>>,
    val channels: List<AlgoChannel>,
    val channelFlows: List<Triple<String, String, Double>>,
    val retention: Double,
    val fresh: Double,
    val tail: Double,
    val velocity: DoubleArray,
    val popLift: Double,
    val ageSeeds: Double,
    val ageRecs: Double,
    val durSeeds: Double,
    val durRecs: Double,
    val exits: List<Pair<String, Int>>
) {
    val openness get() = (fresh + tail) / 2
    fun niche(i: Int): AlgoNiche? = comm.getOrNull(i)?.let { c -> niches.firstOrNull { it.index == c } }
    fun nicheByKey(k: String) = niches.firstOrNull { it.key == k }

    /** Non-seed nodes by closeness to the seeds. */
    val central: List<Int> by lazy { nodes.indices.filter { !nodes[it].seed }.sortedByDescending { nodes[it].ppr } }
}

/** Ego network of the author's channel in the video graph. */
class AlgoEgo(
    val name: String,
    val url: String,
    val mine: List<Int>,
    val sources: List<Pair<Int, Double>>,
    val sourceChannels: List<Pair<String, Double>>,
    val leaks: List<Pair<Int, Double>>,
    val leakChannels: List<Pair<String, Double>>,
    val selfShare: Double,
    val competitors: List<Pair<String, Double>>,
    val bestPprRank: Int,
    val nicheShare: List<Pair<String, Int>>
)

/** Change between two snapshots. */
class AlgoDiff(
    val from: Long,
    val to: Long,
    val rbo: Double,
    val churn: Double,
    val added: List<RecSnap.Node>,
    val dropped: List<RecSnap.Node>,
    val rising: List<Pair<RecSnap.Node, Int>>,
    val channelsUp: List<Pair<String, Int>>,
    val sizeFrom: Int,
    val sizeTo: Int
) {
    val significant get() = added.size + dropped.size >= 3 || rbo < 0.6
}

object AlgoAnalysis {
    private const val DAY = 86_400_000L
    private const val NAMES_FILE = "algo_niches.json"
    private val cache = HashMap<String, AlgoResult>()

    /** Analysis of the project's video graph, cached until the graph changes. */
    fun get(pid: String, force: Boolean = false): AlgoResult {
        val g = RecGraph.load(pid)
        synchronized(cache) {
            cache[pid]?.takeIf { !force && it.g.updatedAt == g.updatedAt && it.g.nodes.size == g.nodes.size }?.let { return it }
        }
        val r = compute(pid, g)
        // remember the niche of every node (graph colouring, export)
        var changed = false
        r.nodes.forEachIndexed { i, n -> if (n.niche != r.comm[i]) { n.niche = r.comm[i]; changed = true } }
        // never overwrite a graph that a crawl has saved meanwhile
        if (changed && g.nodes.isNotEmpty() && !CollectState.running && RecGraph.load(pid).updatedAt == g.updatedAt) RecGraph.save(pid, g)
        synchronized(cache) { cache[pid] = r }
        return r
    }

    fun invalidate(pid: String) = synchronized(cache) { cache.remove(pid) }

    /** Cached analysis if it matches graph [g] (no computation). */
    fun peek(pid: String, g: RecGraph): AlgoResult? = synchronized(cache) {
        cache[pid]?.takeIf { it.g.updatedAt == g.updatedAt && it.g.nodes.size == g.nodes.size }
    }

    private fun median(v: List<Double>) = AlgoCore.median(v)

    fun compute(pid: String, g: RecGraph): AlgoResult {
        val nodes = g.nodes.values.toList()
        val (ids, edges) = g.indexed()
        val n = nodes.size
        val ix = HashMap<String, Int>()
        ids.forEachIndexed { i, s -> ix[s] = i }
        val (auth, hub) = AlgoCore.hits(n, edges)
        val btw = if (n <= 1500) AlgoCore.betweenness(n, edges) else DoubleArray(n)
        val raw = AlgoCore.louvain(n, edges)
        val sizes = raw.toList().groupingBy { it }.eachCount()
        val keep = sizes.filter { it.value >= 3 }.keys.sorted().take(12).toSet()
        val comm = IntArray(n) { if (raw[it] in keep) raw[it] else -1 }
        val now = System.currentTimeMillis()
        val velocity = DoubleArray(n) { i ->
            val v = nodes[i]
            if (v.views < 0 || v.uploadMs <= 0) Double.NaN else v.views / max(1.0, (now - v.uploadMs) / DAY.toDouble())
        }
        fun ageDays(v: RecNode) = if (v.uploadMs <= 0) Double.NaN else (now - v.uploadMs) / DAY.toDouble()
        val titles = nodes.map { it.title }
        val authTotal = auth.sum().takeIf { it > 0 } ?: 1.0
        val stored = storedNames(pid)
        val niches = keep.sorted().mapIndexed { k, c ->
            val members = (0 until n).filter { comm[it] == c }
            val terms = TextSim.liftTerms(members.map { titles[it] }, titles, 6).map { it.first }
            val judged = members.filter { nodes[it].relevant != -1 }
            val ch = members.map { nodes[it].channel }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            val dated = members.filter { nodes[it].uploadMs > 0 }
            val fresh90 = dated.filter { now - nodes[it].uploadMs < 90 * DAY }
            val topAuth = members.sortedByDescending { auth[it] }.take(5).filter { nodes[it].uploadMs > 0 }
            val durs = members.map { nodes[it].duration }.filter { it > 0 }.map { it.toDouble() }.sorted()
            val ids10 = members.sortedByDescending { nodes[it].ppr }.take(30).map { nodes[it].id }.toSet()
            val name = stored.maxByOrNull { AlgoCore.jaccard(it.first, ids10) }?.takeIf { AlgoCore.jaccard(it.first, ids10) >= 0.3 }
            AlgoNiche(
                index = c, key = "g${k + 1}", members = members, terms = terms, name = name?.second ?: "", about = name?.third ?: "",
                onShare = if (judged.isEmpty()) Double.NaN else judged.count { nodes[it].relevant == 1 }.toDouble() / judged.size,
                channels = ch.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }, hhi = AlgoCore.hhi(ch.values),
                velocity = median(members.map { velocity[it] }.filter { !it.isNaN() }),
                freshShare = if (dated.isEmpty()) Double.NaN else fresh90.size.toDouble() / dated.size,
                authShare = members.sumOf { auth[it] } / authTotal,
                channels90 = fresh90.map { nodes[it].channel }.distinct().size,
                oldAuthShare = if (topAuth.isEmpty()) 0.0 else topAuth.count { now - nodes[it].uploadMs > 365 * DAY }.toDouble() / topAuth.size,
                durLo = if (durs.isEmpty()) 0 else AlgoCore.quantile(durs, 0.25).toLong(),
                durHi = if (durs.isEmpty()) 0 else AlgoCore.quantile(durs, 0.75).toLong()
            )
        }
        // opportunity: demand (views/day × authority) against supply (channels publishing in 90 days) + staleness of the core
        if (niches.isNotEmpty()) {
            val demand = AlgoCore.z(DoubleArray(niches.size) { ln(1 + (niches[it].velocity.takeIf { v -> !v.isNaN() } ?: 0.0) * (1 + niches[it].authShare)) })
            val supply = AlgoCore.z(DoubleArray(niches.size) { ln(1.0 + niches[it].channels90) })
            niches.forEachIndexed { i, ni -> ni.opportunity = demand[i] - supply[i] + 0.5 * ni.oldAuthShare }
        }
        val flows = HashMap<Pair<Int, Int>, Double>()
        for (e in edges) {
            val a = comm[e.a]
            val b = comm[e.b]
            if (a >= 0 && b >= 0 && a != b) flows[a to b] = (flows[a to b] ?: 0.0) + e.w
        }
        val rel = IntArray(n) { nodes[it].relevant }
        val leak = AlgoCore.leak(n, edges, rel)
        val maxDepth = nodes.maxOfOrNull { it.depth } ?: 0
        val leakByDepth = (0..maxDepth).mapNotNull { d ->
            val sub = edges.filter { nodes[it.a].depth == d }
            AlgoCore.leak(n, sub, rel).takeIf { it.judged > 0 }?.let { d to it }
        }
        // channels
        val inflow = HashMap<String, Double>()
        val outflow = HashMap<String, Double>()
        val self = HashMap<String, Double>()
        val pair = HashMap<Pair<String, String>, Double>()
        for (e in edges) {
            val a = nodes[e.a].channel
            val b = nodes[e.b].channel
            if (a.isBlank() || b.isBlank()) continue
            if (a == b) {
                self[a] = (self[a] ?: 0.0) + e.w
            } else {
                outflow[a] = (outflow[a] ?: 0.0) + e.w
                inflow[b] = (inflow[b] ?: 0.0) + e.w
                pair[a to b] = (pair[a to b] ?: 0.0) + e.w
            }
        }
        val byChannel = nodes.filter { it.channel.isNotBlank() }.groupBy { it.channel }
        val channels = byChannel.map { (name, list) ->
            AlgoChannel(name, list.firstOrNull { it.channelUrl.isNotBlank() }?.channelUrl.orEmpty(), list.size, inflow[name] ?: 0.0, outflow[name] ?: 0.0, self[name] ?: 0.0)
        }.sortedByDescending { it.inflow + it.self }
        val selfTotal = self.values.sum()
        val retention = if (selfTotal + outflow.values.sum() <= 0) 0.0 else selfTotal / (selfTotal + outflow.values.sum())
        // openness: recommendations next to on-topic videos that go to fresh videos / to the long tail of channels
        val top10 = channels.sortedByDescending { it.inflow }.take(10).map { it.name }.toSet()
        val fromOn = edges.filter { rel[it.a] == 1 }
        val datedTargets = fromOn.filter { nodes[it.b].uploadMs > 0 }
        val fresh = if (datedTargets.isEmpty()) Double.NaN else datedTargets.count { now - nodes[it.b].uploadMs < 90 * DAY }.toDouble() / datedTargets.size
        val tail = if (fromOn.isEmpty()) Double.NaN else fromOn.count { nodes[it.b].channel !in top10 }.toDouble() / fromOn.size
        val seeds = nodes.filter { it.seed }
        val recs = nodes.filter { !it.seed && it.relevant != 0 }
        val vs = median(seeds.filter { it.views >= 0 }.map { it.views.toDouble() })
        val vr = median(recs.filter { it.views >= 0 }.map { it.views.toDouble() })
        val exits = nodes.indices.filter { rel[it] == 0 && nodes[it].label.isNotBlank() && edges.any { e -> e.b == it && rel[e.a] == 1 } }
            .groupingBy { nodes[it].label.lowercase() }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }
        return AlgoResult(
            pid, g, nodes, edges, ix, auth, hub, btw, comm, niches, flows, leak, leakByDepth, channels,
            pair.entries.sortedByDescending { it.value }.take(40).map { Triple(it.key.first, it.key.second, it.value) }, retention,
            fresh, tail, velocity,
            if (vs > 0 && !vr.isNaN()) vr / vs else Double.NaN,
            median(seeds.map(::ageDays).filter { !it.isNaN() }), median(recs.map(::ageDays).filter { !it.isNaN() }),
            median(seeds.filter { it.duration > 0 }.map { it.duration.toDouble() }), median(recs.filter { it.duration > 0 }.map { it.duration.toDouble() }),
            exits
        )
    }

    // ---------------- niche names (algo_niches.json: members → name)

    private fun storedNames(pid: String): List<Triple<Set<String>, String, String>> = try {
        val a = JSONArray(Store.readFile(pid, NAMES_FILE) ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Triple(AlgoParse.strings(o.optJSONArray("members")).toSet(), o.optString("name"), o.optString("about"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveNames(r: AlgoResult) {
        val a = JSONArray()
        r.niches.filter { it.name.isNotBlank() }.forEach { ni ->
            a.put(JSONObject().put("members", JSONArray(ni.members.sortedByDescending { r.nodes[it].ppr }.take(30).map { r.nodes[it].id }))
                .put("name", ni.name).put("about", ni.about))
        }
        Store.writeFile(r.pid, NAMES_FILE, a.toString())
    }

    // ---------------- author's channel

    fun me(pid: String): Pair<String, String> = try {
        val o = JSONObject(Store.readFile(pid, "algo_me.json") ?: "{}")
        o.optString("url") to o.optString("name")
    } catch (e: Exception) {
        "" to ""
    }

    fun setMe(pid: String, url: String, name: String) = Store.writeFile(pid, "algo_me.json", JSONObject().put("url", url).put("name", name).toString())

    fun ego(r: AlgoResult, url: String, name: String): AlgoEgo? {
        val key = if (url.isBlank()) "" else ChannelStore.key(url)
        fun mineOf(v: RecNode) = (key.isNotBlank() && v.channelUrl.isNotBlank() && ChannelStore.key(v.channelUrl) == key) ||
            (name.isNotBlank() && v.channel.equals(name, ignoreCase = true))
        val mine = r.nodes.indices.filter { mineOf(r.nodes[it]) }
        if (mine.isEmpty()) return null
        val mineSet = mine.toSet()
        val myName = r.nodes[mine.first()].channel
        val src = HashMap<Int, Double>()
        val out = HashMap<Int, Double>()
        var selfW = 0.0
        var outW = 0.0
        for (e in r.edges) {
            val a = e.a in mineSet
            val b = e.b in mineSet
            if (a && b) selfW += e.w
            if (!a && b) src[e.a] = (src[e.a] ?: 0.0) + e.w
            if (a && !b) {
                out[e.b] = (out[e.b] ?: 0.0) + e.w
                outW += e.w
            }
        }
        fun byChannel(m: Map<Int, Double>) = m.entries.groupBy { r.nodes[it.key].channel.ifBlank { "?" } }
            .map { (c, l) -> c to l.sumOf { it.value } }.sortedByDescending { it.second }.take(8)
        val myNeighbours = r.edges.filter { it.a in mineSet || it.b in mineSet }.flatMap { listOf(it.a, it.b) }.filter { it !in mineSet }.map { r.nodes[it].id }.toSet()
        val neigh = HashMap<String, MutableSet<String>>()
        for (e in r.edges) {
            val ca = r.nodes[e.a].channel
            val cb = r.nodes[e.b].channel
            if (ca.isNotBlank() && ca != myName) neigh.getOrPut(ca) { HashSet() }.add(r.nodes[e.b].id)
            if (cb.isNotBlank() && cb != myName) neigh.getOrPut(cb) { HashSet() }.add(r.nodes[e.a].id)
        }
        val competitors = neigh.map { (c, s) -> c to AlgoCore.jaccard(s, myNeighbours) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
        val rankOf = r.central.withIndex().associate { it.value to it.index }
        return AlgoEgo(
            myName, url, mine, src.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }, byChannel(src),
            out.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }, byChannel(out),
            if (selfW + outW <= 0) 0.0 else selfW / (selfW + outW), competitors,
            mine.mapNotNull { rankOf[it] }.minOrNull() ?: -1,
            mine.mapNotNull { r.niche(it)?.label }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        )
    }

    // ---------------- search ↔ recommendations

    private val notSearch = setOf("rec", "harvest", "channel", "playlist")

    /** (strong in search but absent from recommendations, central in recommendations but not found by search). */
    fun searchOverlap(r: AlgoResult, videos: Map<String, Video>): Pair<List<Video>, List<RecNode>> {
        val searched = videos.values.filter { it.query.isNotBlank() && it.query !in notSearch && !it.query.startsWith("trending") }
        val searchOnly = searched.filter { v -> r.ix[v.id]?.let { i -> r.nodes[i].hits == 0 && !r.nodes[i].seed } ?: true }
            .sortedByDescending { it.views }.take(10)
        val searchedIds = searched.map { it.id }.toSet()
        val recsOnly = r.central.filter { r.nodes[it].relevant == 1 && r.nodes[it].id !in searchedIds }.take(10).map { r.nodes[it] }
        return searchOnly to recsOnly
    }

    // ---------------- snapshots

    fun diff(a: RecSnap, b: RecSnap): AlgoDiff {
        val ra = a.top.map { it.id }
        val rb = b.top.map { it.id }
        val aTop50 = ra.take(50).toSet()
        val bTop50 = rb.take(50).toSet()
        val byIdA = a.top.associateBy { it.id }
        val rising = b.top.mapNotNull { nb -> byIdA[nb.id]?.let { na -> (nb to nb.hits - na.hits).takeIf { it.second > 0 } } }.sortedByDescending { it.second }.take(8)
        val chA = a.top.take(50).groupingBy { it.channel }.eachCount()
        val chB = b.top.take(50).groupingBy { it.channel }.eachCount()
        val up = chB.map { (c, k) -> c to k - (chA[c] ?: 0) }.filter { it.second > 0 && it.first.isNotBlank() }.sortedByDescending { it.second }.take(6)
        return AlgoDiff(
            a.at, b.at, AlgoCore.rbo(ra.take(50), rb.take(50)), 1 - AlgoCore.jaccard(a.edges, b.edges),
            b.top.take(20).filter { it.id !in aTop50 }, a.top.take(20).filter { it.id !in bTop50 }, rising, up, a.size, b.size
        )
    }

    fun lastDiff(pid: String): AlgoDiff? = RecGraph.snapshots(pid).takeLast(2).takeIf { it.size == 2 }?.let { diff(it[0], it[1]) }

    /** Plain-language change summary (no AI). */
    fun diffText(d: AlgoDiff): String = buildString {
        append(t("Устойчивость центра (RBO): ${Math.round(d.rbo * 100)}%, обновилось связей: ${Math.round(d.churn * 100)}%, узлов ${d.sizeFrom} → ${d.sizeTo}.",
            "Core stability (RBO): ${Math.round(d.rbo * 100)}%, links changed: ${Math.round(d.churn * 100)}%, nodes ${d.sizeFrom} → ${d.sizeTo}."))
        if (d.added.isNotEmpty()) append("\n• ").append(t("Новые в центре: ", "New in the core: ")).append(d.added.take(3).joinToString("; ") { "«${it.title.take(50)}»" })
        if (d.dropped.isNotEmpty()) append("\n• ").append(t("Выпали: ", "Dropped: ")).append(d.dropped.take(3).joinToString("; ") { "«${it.title.take(50)}»" })
        if (d.channelsUp.isNotEmpty()) append("\n• ").append(t("Растут каналы: ", "Growing channels: ")).append(d.channelsUp.take(3).joinToString(", ") { "${it.first} +${it.second}" })
    }
}

/** Prompt data with ids ([v1] video, [c1] channel, [g1] niche) and the id → name map for links in answers. */
class AlgoData(val text: String, val names: Map<String, String>) {
    companion object {
        private fun num(d: Double) = if (d.isNaN()) "—" else Fmt.num(d.toLong())
        private fun pct(d: Double) = if (d.isNaN()) "—" else "${Math.round(d * 100)}%"
        private fun dur(s: Double) = if (s.isNaN() || s <= 0) "—" else Fmt.duration(s.toLong())
        private fun days(d: Double) = if (d.isNaN()) "—" else "${d.toLong()} " + t("дн", "d")

        class Ids(val r: AlgoResult) {
            val v = LinkedHashMap<Int, String>()
            val c = LinkedHashMap<String, String>()
            val names = LinkedHashMap<String, String>()

            fun video(i: Int): String = v.getOrPut(i) { "v${v.size + 1}".also { names[it] = r.nodes[i].title.take(60) } }
            fun channel(name: String): String = c.getOrPut(name) { "c${c.size + 1}".also { names[it] = name } }
            fun niche(ni: AlgoNiche): String = ni.key.also { names[it] = ni.label }

            fun line(i: Int): String {
                val n = r.nodes[i]
                val age = if (n.uploadMs > 0) " · " + ((System.currentTimeMillis() - n.uploadMs) / 86_400_000L) + t(" дн", " d") else ""
                return "[${video(i)}] ${n.title.take(90)} — ${n.channel} [${channel(n.channel)}] · PPR ${"%.1f".format(n.ppr * 1000)}‰ · " +
                    t("входящих", "in") + " ${n.hits} · " + Fmt.num(n.views) + age + (if (n.duration > 0) " · " + Fmt.duration(n.duration) else "") +
                    (r.niche(i)?.let { " · " + niche(it) } ?: "") + (if (n.relevant == 0) " · " + t("не по теме", "off topic") + (n.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") else "")
            }
        }

        fun nicheLine(ids: Ids, ni: AlgoNiche): String = "[${ids.niche(ni)}] ${ni.label} · ${ni.members.size} " + t("видео", "videos") +
            " · " + t("по теме", "on topic") + " " + pct(ni.onShare) + " · " + t("авторитет", "authority") + " " + pct(ni.authShare) +
            " · " + t("просмотров/день (медиана)", "views/day (median)") + " " + num(ni.velocity) + " · " + t("свежих (<90 дн)", "fresh (<90 d)") + " " + pct(ni.freshShare) +
            " · " + t("каналов за 90 дн", "channels in 90 d") + " " + ni.channels90 + " · HHI " + "%.2f".format(ni.hhi) +
            " · " + t("старые в ядре", "old in the core") + " " + pct(ni.oldAuthShare) +
            " · " + t("длительность", "duration") + " " + dur(ni.durLo.toDouble()) + "–" + dur(ni.durHi.toDouble()) +
            " · " + t("слова", "words") + ": " + ni.terms.joinToString(", ") +
            " · " + t("каналы", "channels") + ": " + ni.channels.take(3).joinToString(", ") { "${it.first} [${ids.channel(it.first)}]" }

        /** Data for algo_report and the tab chat. */
        fun report(r: AlgoResult, short: Boolean = false): AlgoData {
            val ids = Ids(r)
            val sb = StringBuilder()
            sb.append(AlgoMethod.graph(r.g)).append("\n\n")
            sb.append(t("НИШИ:", "NICHES:")).append('\n')
            r.niches.forEach { sb.append(nicheLine(ids, it)).append('\n') }
            if (r.nicheFlows.isNotEmpty()) {
                sb.append(t("ПОТОКИ МЕЖДУ НИШАМИ: ", "FLOWS BETWEEN NICHES: "))
                sb.append(r.nicheFlows.entries.sortedByDescending { it.value }.take(10).joinToString("; ") { (k, w) ->
                    val a = r.niches.first { it.index == k.first }
                    val b = r.niches.first { it.index == k.second }
                    "[${ids.niche(a)}]→[${ids.niche(b)}] " + "%.1f".format(w)
                }).append('\n')
            }
            sb.append(t("УТЕЧКА С ТЕМЫ ЗА ШАГ: ", "TOPIC LEAK PER HOP: ")).append(pct(r.leak.rate))
                .append(" (90%: ${pct(r.leak.lo)}–${pct(r.leak.hi)}, ${r.leak.judged} " + t("рекомендаций", "recommendations") + ")")
            if (r.leakByDepth.size > 1) sb.append("; " + t("по шагам", "by step") + ": " + r.leakByDepth.joinToString(", ") { "${it.first}: ${pct(it.second.rate)}" })
            sb.append('\n')
            if (r.exits.isNotEmpty()) sb.append(t("ВЫХОДЫ (куда уводит, метки ИИ): ", "EXITS (where it leads, AI labels): ")).append(r.exits.joinToString("; ") { "${it.first} ×${it.second}" }).append('\n')
            sb.append(t("ЦЕНТР (близость к стартовым, PPR):", "CORE (closeness to the seeds, PPR):")).append('\n')
            r.central.filter { r.nodes[it].relevant != 0 }.take(if (short) 10 else 15).forEach { sb.append(ids.line(it)).append('\n') }
            sb.append(t("АВТОРИТЕТЫ (к ним ведут тематические видео):", "AUTHORITIES (on-topic videos lead here):")).append('\n')
            r.nodes.indices.filter { !r.nodes[it].seed }.sortedByDescending { r.auth[it] }.take(if (short) 6 else 10).forEach { sb.append(ids.line(it)).append('\n') }
            sb.append(t("МОСТЫ МЕЖДУ НИШАМИ (посредничество):", "BRIDGES BETWEEN NICHES (betweenness):")).append('\n')
            r.nodes.indices.filter { r.btw[it] > 0 }.sortedByDescending { r.btw[it] }.take(if (short) 5 else 8).forEach { sb.append(ids.line(it)).append('\n') }
            sb.append(t("КАНАЛЫ:", "CHANNELS:")).append('\n')
            r.channels.take(if (short) 8 else 12).forEach { c ->
                sb.append("[${ids.channel(c.name)}] ${c.name} · " + t("видео", "videos") + " ${c.videos} · " + t("поток к каналу", "inflow") + " " + "%.1f".format(c.inflow) +
                    " · " + t("отдаёт другим", "outflow") + " " + "%.1f".format(c.outflow) + " · " + t("удержание на своём", "self retention") + " " + pct(c.retention)).append('\n')
            }
            sb.append(t("СМЕЩЕНИЯ: просмотры рекомендованных против стартовых ×", "BIASES: views of recommended vs seeds ×")).append(if (r.popLift.isNaN()) "—" else "%.1f".format(r.popLift))
                .append("; " + t("возраст", "age") + " " + days(r.ageRecs) + " " + t("против", "vs") + " " + days(r.ageSeeds))
                .append("; " + t("длительность", "duration") + " " + dur(r.durRecs) + " " + t("против", "vs") + " " + dur(r.durSeeds))
                .append("; " + t("удержание на своём канале", "same-channel retention") + " " + pct(r.retention)).append('\n')
            sb.append(t("ОТКРЫТОСТЬ: рекомендаций к свежим (<90 дн) ", "OPENNESS: recommendations to fresh (<90 d) ")).append(pct(r.fresh))
                .append(", " + t("к каналам вне топ-10", "to channels outside the top 10") + " " + pct(r.tail)).append('\n')
            return AlgoData(sb.toString(), ids.names)
        }

        /** Data for algo_niches. */
        fun niches(r: AlgoResult): AlgoData {
            val ids = Ids(r)
            val sb = StringBuilder()
            r.niches.forEach { ni ->
                sb.append("${ni.key} · ${ni.members.size} " + t("видео", "videos") + " · " + t("слова", "words") + ": " + ni.terms.joinToString(", ") +
                    " · " + t("каналы", "channels") + ": " + ni.channels.take(4).joinToString(", ") { it.first } + " · " + t("примеры", "examples") + ": " +
                    ni.members.sortedByDescending { r.auth[it] }.take(6).joinToString("; ") { "«" + r.nodes[it].title.take(80) + "»" }).append('\n')
            }
            return AlgoData(sb.toString(), ids.names)
        }

        /** Data for algo_mychannel. */
        fun ego(r: AlgoResult, e: AlgoEgo): AlgoData {
            val ids = Ids(r)
            val sb = StringBuilder()
            sb.append(AlgoMethod.graph(r.g)).append("\n\n")
            sb.append(t("КАНАЛ АВТОРА: ", "AUTHOR'S CHANNEL: ")).append("[${ids.channel(e.name)}] ${e.name} · " + t("видео в графе", "videos in the graph") + " ${e.mine.size} · " +
                t("лучшее место по PPR", "best PPR place") + " ${if (e.bestPprRank < 0) "—" else (e.bestPprRank + 1).toString()} " + t("из", "of") + " ${r.central.size} · " +
                t("удержание на своём канале", "same-channel retention") + " ${pct(e.selfShare)}").append('\n')
            sb.append(t("ВИДЕО АВТОРА:", "AUTHOR'S VIDEOS:")).append('\n')
            e.mine.sortedByDescending { r.nodes[it].ppr }.take(10).forEach { sb.append(ids.line(it)).append('\n') }
            sb.append(t("КТО ВЕДЁТ К АВТОРУ (видео других каналов):", "WHO LEADS TO THE AUTHOR (other channels' videos):")).append('\n')
            e.sources.take(10).forEach { (i, w) -> sb.append(ids.line(i)).append(" · " + t("сила", "strength") + " " + "%.2f".format(w)).append('\n') }
            if (e.sourceChannels.isNotEmpty()) sb.append(t("КАНАЛЫ-ИСТОЧНИКИ: ", "SOURCE CHANNELS: ")).append(e.sourceChannels.joinToString("; ") { "[${ids.channel(it.first)}] ${it.first} " + "%.1f".format(it.second) }).append('\n')
            sb.append(t("КУДА УХОДИТ ЗРИТЕЛЬ С ВИДЕО АВТОРА:", "WHERE VIEWERS GO FROM THE AUTHOR'S VIDEOS:")).append('\n')
            e.leaks.take(10).forEach { (i, w) -> sb.append(ids.line(i)).append(" · " + t("сила", "strength") + " " + "%.2f".format(w)).append('\n') }
            if (e.leakChannels.isNotEmpty()) sb.append(t("КАНАЛЫ-ПОЛУЧАТЕЛИ: ", "RECEIVING CHANNELS: ")).append(e.leakChannels.joinToString("; ") { "[${ids.channel(it.first)}] ${it.first} " + "%.1f".format(it.second) }).append('\n')
            if (e.competitors.isNotEmpty()) sb.append(t("КОНКУРЕНТЫ (общие соседи, Жаккар): ", "COMPETITORS (shared neighbours, Jaccard): ")).append(e.competitors.joinToString("; ") { "[${ids.channel(it.first)}] ${it.first} " + "%.2f".format(it.second) }).append('\n')
            if (e.nicheShare.isNotEmpty()) sb.append(t("НИШИ ВИДЕО АВТОРА: ", "NICHES OF THE AUTHOR'S VIDEOS: ")).append(e.nicheShare.joinToString("; ") { "${it.first} ×${it.second}" }).append('\n')
            sb.append(t("НИШИ ГРАФА:", "GRAPH NICHES:")).append('\n')
            r.niches.forEach { sb.append(nicheLine(ids, it)).append('\n') }
            return AlgoData(sb.toString(), ids.names)
        }

        /** Data for algo_opportunities: niches with demand/supply plus audience pains and the author's videos. */
        fun opportunities(r: AlgoResult, pains: List<String>, mine: List<String>): AlgoData {
            val ids = Ids(r)
            val sb = StringBuilder()
            sb.append(t("НИШИ (оценка возможности = спрос − предложение, z):", "NICHES (opportunity = demand − supply, z):")).append('\n')
            r.niches.sortedByDescending { it.opportunity }.forEach { ni ->
                sb.append(nicheLine(ids, ni)).append(" · " + t("оценка", "score") + " " + "%.2f".format(ni.opportunity)).append('\n')
                ni.members.sortedByDescending { r.auth[it] }.take(4).forEach { sb.append("   ").append(ids.line(it)).append('\n') }
            }
            if (pains.isNotEmpty()) sb.append(t("БОЛИ И ВОПРОСЫ АУДИТОРИИ:", "AUDIENCE PAINS AND QUESTIONS:")).append('\n').append(pains.take(20).joinToString("\n") { "- $it" }).append('\n')
            if (mine.isNotEmpty()) sb.append(t("ВИДЕО АВТОРА:", "AUTHOR'S VIDEOS:")).append('\n').append(mine.take(10).joinToString("\n") { "- $it" }).append('\n')
            return AlgoData(sb.toString(), ids.names)
        }
    }
}

/** AI calls of the Algorithm tab (all through Llm.complete, cached as AiResult). */
object AlgoAi {
    private fun prov(): Provider = AiTasks.requireProvider()

    private fun run(pid: String, task: String, prompt: String, smart: Boolean, json: Boolean, maxTokens: Int, force: Boolean, check: (String) -> String?): AiResult {
        val p = prov()
        val model = if (smart) p.smartModel.ifBlank { p.fastModel } else p.fastModel
        val key = sha1("$task|$model|$prompt|${Settings.lang}")
        if (!force) Store.ai(pid, key)?.let { return it }
        var r = Llm.complete(p, model, ALGO_SYSTEM, prompt, json = json, maxTokens = maxTokens, task = task, pid = pid)
        val problem = check(r.text)
        if (problem != null) r = Llm.complete(p, model, ALGO_SYSTEM, prompt + AlgoParse.fixNote(problem), json = json, maxTokens = maxTokens, task = task, pid = pid)
        return AiResult(key, task, "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(pid, it) }
    }

    private fun base(p: Project, r: AlgoResult) = AlgoText.topicVars(p) + mapOf("method" to AlgoMethod.graph(r.g))

    /** Names the niches (algo_niches) and stores them. */
    fun nameNiches(p: Project, r: AlgoResult): Int {
        if (r.niches.isEmpty()) return 0
        val data = AlgoData.niches(r)
        val keys = r.niches.map { it.key }.toSet()
        val res = run(p.id, "algo_niches", Prompts.fill("algo_niches", base(p, r) + mapOf("data" to data.text)), false, true, 1500, true) {
            AlgoParse.niches(it, keys).problem.ifBlank { null }
        }
        val names = AlgoParse.niches(res.text, keys).value ?: return 0
        r.niches.forEach { ni -> names[ni.key]?.let { ni.name = it.name; ni.about = it.about } }
        AlgoAnalysis.saveNames(r)
        return names.size
    }

    /** Markdown review of the graph with [id] links checked; returns the text with unknown ids marked. */
    fun report(p: Project, r: AlgoResult, force: Boolean = false): Pair<String, Map<String, String>> {
        val data = AlgoData.report(r)
        val res = run(p.id, "algo_report", Prompts.fill("algo_report", base(p, r) + mapOf("data" to data.text)), true, false, 3000, force) {
            AlgoParse.unknownIds(it, data.names.keys).takeIf { u -> u.size > 2 }?.let { u -> t("ссылки на несуществующие объекты: ", "links to missing objects: ") + u.joinToString(", ") }
        }
        return res.text to data.names
    }

    fun myChannel(p: Project, r: AlgoResult, e: AlgoEgo, force: Boolean = false): Pair<String, Map<String, String>> {
        val data = AlgoData.ego(r, e)
        val res = run(p.id, "algo_mychannel", Prompts.fill("algo_mychannel", base(p, r) + mapOf("channel" to e.name, "data" to data.text)), true, false, 3000, force) {
            AlgoParse.unknownIds(it, data.names.keys).takeIf { u -> u.size > 2 }?.let { u -> t("ссылки на несуществующие объекты: ", "links to missing objects: ") + u.joinToString(", ") }
        }
        return res.text to data.names
    }

    /** Opportunities as JSON (algo_opps.json); the [AlgoData.names] map resolves their ids. */
    fun opportunities(p: Project, r: AlgoResult, force: Boolean = false): Pair<List<AlgoParse.Opp>, Map<String, String>> {
        val pains = Seo.insights(p.id).filter { it.optString("type") in setOf("pain", "question", "request", "objection") }
            .map { it.optString("statement") }.filter { it.isNotBlank() }
        val (url, name) = AlgoAnalysis.me(p.id)
        val mine = AlgoAnalysis.ego(r, url, name)?.mine?.map { r.nodes[it].title }.orEmpty()
        val data = AlgoData.opportunities(r, pains, mine)
        val niches = r.niches.map { it.key }.toSet()
        val nodes = data.names.keys.filter { it.startsWith("v") }.toSet()
        val res = run(p.id, "algo_opportunities", Prompts.fill("algo_opportunities", base(p, r) + mapOf("data" to data.text)), true, true, 3000, force) {
            AlgoParse.opportunities(it, niches, nodes).problem.ifBlank { null }
        }
        val list = AlgoParse.opportunities(res.text, niches, nodes).value.orEmpty()
        Store.writeFile(p.id, "algo_opps.json", JSONObject().put("at", System.currentTimeMillis()).put("items", JSONArray(list.map { it.toJson() }))
            .put("names", JSONObject(data.names as Map<*, *>)).toString())
        return list to data.names
    }

    fun savedOpportunities(pid: String): Pair<List<AlgoParse.Opp>, Map<String, String>> = try {
        val o = JSONObject(Store.readFile(pid, "algo_opps.json") ?: "{}")
        val a = o.optJSONArray("items") ?: JSONArray()
        val names = o.optJSONObject("names")?.let { n -> n.keys().asSequence().associateWith { n.optString(it) } }.orEmpty()
        (0 until a.length()).map { AlgoParse.Opp.from(a.getJSONObject(it)) } to names
    } catch (e: Exception) {
        emptyList<AlgoParse.Opp>() to emptyMap()
    }

    /** Notification text about what changed between the last two snapshots (AI when available, else plain). */
    fun changes(p: Project, d: AlgoDiff): String {
        val plain = AlgoAnalysis.diffText(d)
        if (Provider.active() == null) return plain
        return try {
            val prompt = Prompts.fill(
                "algo_changes", AlgoText.topicVars(p) + mapOf("from" to Fmt.dateTime(d.from), "to" to Fmt.dateTime(d.to), "data" to plain + "\n" +
                    d.rising.take(5).joinToString("\n") { "+${it.second} " + t("входящих", "in-links") + ": «${it.first.title.take(60)}» — ${it.first.channel}" })
            )
            val pr = prov()
            Llm.complete(pr, pr.fastModel, ALGO_SYSTEM, prompt, maxTokens = 400, task = "algo_changes", pid = p.id).text.trim().take(600).ifBlank { plain }
        } catch (e: Exception) {
            plain
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Predictions" tab, pure logic (PredictTest), protocol lumen.predict v1.
 *
 * From the "Up next" graph and the project's search results:
 * - communities ("rooms") of the topic: Louvain on the undirected graph, distinctive title words, hubs;
 * - openness: share of recommendations that go to fresh (≤30 days) or small (<10k views) videos;
 * - doors: on-topic videos whose "Up next" already lets newcomers in;
 * - profile: what recommended videos have that videos found by search but never recommended do not,
 *   with the search side re-weighted to the same view levels (popularity is the main confounder);
 * - a small logistic model with 5-fold cross-validated AUC;
 * - the draft fit score with checks, and the post-publish placement of our video's own "Up next".
 * All of it is correlation on a sample: the tab says so and asks AI only where the data is thin.
 */
object Predict {
    const val PROTOCOL = "lumen.predict v1"
    const val DAY = 86_400_000L
    const val FRESH_DAYS = 30
    const val SMALL_VIEWS = 10_000L

    /** Views a brand-new video of a small channel is scored with (the draft has no views yet). */
    const val NEWCOMER_VIEWS = 1_000L

    // ------------------------------------------------------------ samples

    /** One video as a sample: from the graph ([inRecs]) or from the project's search results. */
    data class Item(
        val id: String,
        val title: String,
        val channel: String = "",
        val views: Long = -1,
        val uploadMs: Long = -1,
        val durationSec: Long = -1,
        val isShort: Boolean = false,
        val hits: Int = 0,
        val inRecs: Boolean = false
    )

    fun fresh(uploadMs: Long, now: Long) = uploadMs > 0 && now - uploadMs <= FRESH_DAYS * DAY

    fun isNewcomer(n: RecNode, now: Long, small: Long = SMALL_VIEWS) = fresh(n.uploadMs, now) || n.views in 0 until small

    /**
     * Positives: graph videos that YouTube recommended (on topic or unknown) at least [minHits] times
     * (2 if that leaves ≥15, else 1). Negatives: project videos (found by search, not by recommendations)
     * that never showed up as a recommendation.
     */
    fun sample(nodes: Collection<RecNode>, search: List<Item>): List<Item> {
        val strong = nodes.count { it.relevant != 0 && it.hits >= 2 }
        val minHits = if (strong >= 15) 2 else 1
        val pos = nodes.filter { it.relevant != 0 && it.hits >= minHits }.map {
            Item(it.id, it.title, it.channel, it.views, it.uploadMs, it.duration, it.isShort, it.hits, true)
        }
        val byId = nodes.associateBy { it.id }
        val neg = search.filter { s ->
            val n = byId[s.id]
            n == null || (n.hits == 0 && n.relevant != 0)
        }.map { it.copy(inRecs = false, hits = 0) }
        return pos + neg
    }

    // ------------------------------------------------------------ text

    fun stems(text: String): List<String> = Words.tokens(text, 3).map { Relevance.stem(it) }

    /** Document frequencies of stems over a corpus of titles; idf with smoothing. */
    class Vocab(docs: List<String>) {
        val n = docs.size
        val df = HashMap<String, Int>()

        init {
            docs.forEach { d -> stems(d).toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        }

        fun idf(s: String): Double = ln((n + 1.0) / ((df[s] ?: 0) + 1.0)) + 1.0

        /** Unit tf-idf vector of [text] (sub-linear tf). */
        fun vec(text: String): Map<String, Double> {
            val tf = HashMap<String, Int>()
            stems(text).forEach { tf[it] = (tf[it] ?: 0) + 1 }
            return unit(tf.mapValues { (s, c) -> (1.0 + ln(c.toDouble())) * idf(s) })
        }
    }

    fun unit(v: Map<String, Double>): Map<String, Double> {
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm <= 0.0) emptyMap() else v.mapValues { it.value / norm }
    }

    fun dot(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (s, l) = if (a.size <= b.size) a to b else b to a
        var r = 0.0
        for ((k, v) in s) r += v * (l[k] ?: 0.0)
        return r
    }

    /** Weighted sum of unit vectors (not normalised, so leave-one-out similarity stays cheap). */
    class Centroid(val sum: Map<String, Double>) {
        val norm2 = sum.values.sumOf { it * it }
        fun sim(v: Map<String, Double>): Double = if (norm2 <= 0) 0.0 else dot(sum, v) / sqrt(norm2)

        /** Similarity of a member vector [v] (added with weight [w]) to the centroid without it. */
        fun simWithout(v: Map<String, Double>, w: Double): Double {
            val d = dot(sum, v)
            val rest = norm2 - 2 * w * d + w * w
            return if (rest <= 1e-9) 0.0 else (d - w) / sqrt(rest)
        }

        companion object {
            fun of(vs: List<Pair<Map<String, Double>, Double>>): Centroid {
                val s = HashMap<String, Double>()
                vs.forEach { (v, w) -> v.forEach { (k, x) -> s[k] = (s[k] ?: 0.0) + x * w } }
                return Centroid(s)
            }
        }
    }

    fun memberWeight(hits: Int) = 1.0 + ln(1.0 + max(0, hits))

    /**
     * Distinctive words of texts [a] against [b]: log-odds ratio with an informative Dirichlet prior
     * (Monroe, Colaresi & Quinn 2008) over document frequencies of stems; returns the most frequent
     * surface form with its z-score, only z ≥ [minZ] and present in ≥ [minDf] texts of [a].
     */
    fun distinctive(a: List<String>, b: List<String>, top: Int = 8, minDf: Int = 2, minZ: Double = 1.5): List<Pair<String, Double>> {
        val da = HashMap<String, Int>()
        val db = HashMap<String, Int>()
        val surface = HashMap<String, HashMap<String, Int>>()
        a.forEach { t ->
            val seen = HashSet<String>()
            Words.tokens(t, 3).forEach { w ->
                val s = Relevance.stem(w)
                val m = surface.getOrPut(s) { HashMap() }
                m[w] = (m[w] ?: 0) + 1
                if (seen.add(s)) da[s] = (da[s] ?: 0) + 1
            }
        }
        b.forEach { t -> Words.tokens(t, 3).map { Relevance.stem(it) }.toSet().forEach { db[it] = (db[it] ?: 0) + 1 } }
        val na = da.values.sum().toDouble()
        val nb = db.values.sum().toDouble()
        if (na <= 0) return emptyList()
        val all = HashMap<String, Int>(db)
        da.forEach { (k, v) -> all[k] = (all[k] ?: 0) + v }
        val n = all.values.sum().toDouble()
        val a0 = 0.5 * n
        val out = ArrayList<Pair<String, Double>>()
        for ((s, ya) in da) {
            if (ya < minDf) continue
            val yb = (db[s] ?: 0).toDouble()
            val aw = 0.5 * (all[s] ?: 0)
            val la = ln((ya + aw) / (na + a0 - ya - aw))
            val lb = ln((yb + aw) / (nb + a0 - yb - aw))
            val z = (la - lb) / sqrt(1.0 / (ya + aw) + 1.0 / (yb + aw))
            if (z >= minZ) out.add((surface[s]?.maxByOrNull { it.value }?.key ?: s) to z)
        }
        return out.sortedByDescending { it.second }.take(top)
    }

    // ------------------------------------------------------------ title shape

    val SHAPES = listOf("number", "year", "question", "exclaim", "colon", "brackets", "caps", "emoji", "you", "i")
    private val YOU = setOf("ты", "тебя", "тебе", "твой", "твоя", "твои", "вы", "вас", "вам", "ваш", "ваша", "ваши", "you", "your", "you're")
    private val ME = setOf("я", "мой", "моя", "мои", "меня", "мне", "i", "my", "me", "i'm")
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val LETTERS = Regex("""[\p{L}']+""")

    /** Shape flags of a title used by the profile, the model and the draft checks. */
    fun shape(title: String): Map<String, Boolean> {
        val words = LETTERS.findAll(title).map { it.value }.toList()
        val lower = words.map { it.lowercase() }
        return mapOf(
            "number" to title.any { it.isDigit() },
            "year" to YEAR.containsMatchIn(title),
            "question" to title.contains('?'),
            "exclaim" to title.contains('!'),
            "colon" to (title.contains(':') || title.contains('|') || title.contains(" - ") || title.contains(" — ")),
            "brackets" to (title.contains('(') || title.contains('[')),
            "caps" to words.any { w -> w.length >= 3 && w.all { !it.isLetter() || it.isUpperCase() } && w.any { it.isLetter() } },
            "emoji" to hasEmoji(title),
            "you" to lower.any { it in YOU },
            "i" to lower.any { it in ME }
        )
    }

    fun hasEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    // ------------------------------------------------------------ communities

    data class Openness(val share: Double, val newcomers: Int, val total: Int, val medianAgeDays: Int, val freshShare: Double) {
        /** -1 too little data, 0 closed, 1 medium, 2 open. */
        val level: Int
            get() = when {
                total < 5 -> -1
                share >= 0.25 -> 2
                share >= 0.10 -> 1
                else -> 0
            }
    }

    class Community(
        val id: Int,
        /** members, most recommended first */
        val ids: List<String>,
        val terms: List<String>,
        val hubs: List<String>,
        val channels: List<Pair<String, Int>>,
        /** share of members judged on topic (unknown counts as on topic) */
        val onTopic: Double,
        /** recommendations received by members */
        val hits: Int,
        val open: Openness,
        val shortsShare: Double,
        /** quartiles of members' durations, seconds (null if < 6 known) */
        val durIqr: LongRange?
    ) {
        var name: String = ""
        var about: String = ""
        val label: String get() = name.ifBlank { terms.take(3).joinToString(" · ").ifBlank { "#$id" } }
    }

    /**
     * Louvain modularity communities on the undirected graph (edge weight 1/√(1+rank));
     * returns a community index per node (same order as [n]).
     */
    fun louvain(n: Int, edges: List<Triple<Int, Int, Double>>, maxLevels: Int = 8): IntArray {
        var adj: Array<HashMap<Int, Double>> = Array(n) { HashMap() }
        for ((a, b, w) in edges) {
            if (a == b) continue
            adj[a][b] = (adj[a][b] ?: 0.0) + w
            adj[b][a] = (adj[b][a] ?: 0.0) + w
        }
        val owner = IntArray(n) { it }
        repeat(maxLevels) {
            val size = adj.size
            val k = DoubleArray(size) { i -> adj[i].values.sum() }
            val m2 = k.sum()
            if (m2 <= 0) return owner
            val comm = IntArray(size) { it }
            val tot = k.copyOf()
            var movedAny = false
            for (pass in 0 until 20) {
                var moved = false
                for (i in 0 until size) {
                    val c = comm[i]
                    tot[c] -= k[i]
                    val kin = HashMap<Int, Double>()
                    for ((j, w) in adj[i]) if (j != i) kin[comm[j]] = (kin[comm[j]] ?: 0.0) + w
                    var best = c
                    var bestGain = (kin[c] ?: 0.0) - tot[c] * k[i] / m2
                    for ((d, kd) in kin) {
                        val g = kd - tot[d] * k[i] / m2
                        if (g > bestGain + 1e-12) {
                            best = d
                            bestGain = g
                        }
                    }
                    tot[best] += k[i]
                    if (best != c) {
                        comm[i] = best
                        moved = true
                        movedAny = true
                    }
                }
                if (!moved) break
            }
            if (!movedAny) return owner
            // renumber and aggregate
            val ren = HashMap<Int, Int>()
            for (i in 0 until size) ren.getOrPut(comm[i]) { ren.size }
            for (v in 0 until n) owner[v] = ren[comm[owner[v]]]!!
            val next: Array<HashMap<Int, Double>> = Array(ren.size) { HashMap() }
            for (i in 0 until size) {
                val ci = ren[comm[i]]!!
                for ((j, w) in adj[i]) {
                    val cj = ren[comm[j]]!!
                    next[ci][cj] = (next[ci][cj] ?: 0.0) + w
                }
            }
            if (ren.size == size) return owner
            adj = next
        }
        return owner
    }

    /**
     * A snowball graph is tree-like, so Louvain cuts one interest into several stars. Linked groups whose
     * titles are alike (tf-idf cosine ≥ [minSim]) are joined back into one community.
     */
    fun mergeSimilar(nodes: List<RecNode>, edges: List<Triple<Int, Int, Double>>, owner: IntArray, minSim: Double = 0.5): IntArray {
        val groups = nodes.indices.groupBy { owner[it] }
        if (groups.size < 2) return owner
        val vocab = Vocab(nodes.map { it.title })
        // greedy merging of the most similar linked pair, centroids recomputed after each merge (no chaining)
        val sum = HashMap<Int, HashMap<String, Double>>()
        groups.forEach { (g, idx) ->
            val s = HashMap<String, Double>()
            idx.forEach { i -> vocab.vec(nodes[i].title).forEach { (k, x) -> s[k] = (s[k] ?: 0.0) + x } }
            sum[g] = s
        }
        val links = HashMap<Int, HashSet<Int>>()
        for ((a, b, _) in edges) {
            val ga = owner[a]
            val gb = owner[b]
            if (ga == gb) continue
            links.getOrPut(ga) { HashSet() }.add(gb)
            links.getOrPut(gb) { HashSet() }.add(ga)
        }
        val into = HashMap<Int, Int>()
        while (true) {
            var best: Triple<Int, Int, Double>? = null
            val unitOf = sum.mapValues { unit(it.value) }
            for ((a, nb) in links) for (b in nb) {
                if (a >= b) continue
                val s = dot(unitOf[a].orEmpty(), unitOf[b].orEmpty())
                if (s >= minSim && (best == null || s > best.third)) best = Triple(a, b, s)
            }
            val (a, b, _) = best ?: break
            val sb = sum.remove(b) ?: break
            val sa = sum.getValue(a)
            sb.forEach { (k, x) -> sa[k] = (sa[k] ?: 0.0) + x }
            into[b] = a
            val nb = links.remove(b).orEmpty()
            for (c in nb) {
                links[c]?.remove(b)
                if (c != a) {
                    links.getOrPut(a) { HashSet() }.add(c)
                    links.getOrPut(c) { HashSet() }.add(a)
                }
            }
            links[a]?.remove(b)
        }
        fun root(g: Int): Int {
            var r = g
            while (into[r] != null) r = into[r]!!
            return r
        }
        return IntArray(owner.size) { root(owner[it]) }
    }

    /** Communities of the graph with at least [minSize] members, most recommended first, at most [max]. */
    fun communities(nodes: List<RecNode>, edges: List<RecEdge>, now: Long, minSize: Int = 3, max: Int = 12): List<Community> {
        if (nodes.isEmpty()) return emptyList()
        val index = HashMap<String, Int>()
        nodes.forEachIndexed { i, n -> index[n.id] = i }
        val e = edges.mapNotNull { ed ->
            val a = index[ed.from]
            val b = index[ed.to]
            if (a == null || b == null) null else Triple(a, b, 1.0 / sqrt(1.0 + ed.rank))
        }
        val owner = mergeSimilar(nodes, e, louvain(nodes.size, e))
        val groups = nodes.indices.groupBy { owner[it] }.values
            .map { idx -> idx.map { nodes[it] } }
            .filter { it.size >= minSize }
            .sortedWith(compareByDescending<List<RecNode>> { l -> l.sumOf { it.hits } }.thenByDescending { it.size })
            .take(max)
        val allTitles = nodes.map { it.title }
        return groups.mapIndexed { gi, members ->
            val sorted = members.sortedWith(compareByDescending<RecNode> { it.hits }.thenByDescending { it.pr })
            val ids = sorted.map { it.id }.toSet()
            val rest = allTitles.filterIndexed { i, _ -> nodes[i].id !in ids }
            val durs = members.filter { it.duration > 0 }.map { it.duration }.sorted()
            Community(
                id = gi + 1,
                ids = sorted.map { it.id },
                terms = distinctive(members.map { it.title }, rest, top = 8, minDf = 2, minZ = 1.0).map { it.first },
                hubs = sorted.filter { !it.seed || it.hits > 0 }.take(5).map { it.id },
                channels = members.groupingBy { it.channel.ifBlank { "?" } }.eachCount().entries
                    .sortedByDescending { it.value }.take(5).map { it.key to it.value },
                onTopic = members.count { it.relevant != 0 }.toDouble() / members.size,
                hits = members.sumOf { it.hits },
                open = openness(members, now),
                shortsShare = members.count { it.isShort }.toDouble() / members.size,
                durIqr = if (durs.size >= 6) quartile(durs, 0.25)..quartile(durs, 0.75) else null
            )
        }
    }

    fun quartile(sorted: List<Long>, q: Double): Long = if (sorted.isEmpty()) -1 else sorted[((sorted.size - 1) * q).toInt()]

    /** Hit-weighted share of recommendations that went to newcomers (fresh or small videos). */
    fun openness(members: Collection<RecNode>, now: Long, small: Long = SMALL_VIEWS): Openness {
        val rec = members.filter { !it.seed && it.relevant != 0 && it.hits > 0 }
        val total = rec.sumOf { it.hits }
        val newcomers = rec.filter { isNewcomer(it, now, small) }
        val ages = rec.filter { it.uploadMs in 1..now }.map { ((now - it.uploadMs) / DAY).toInt() }.sorted()
        return Openness(
            share = if (total == 0) 0.0 else newcomers.sumOf { it.hits }.toDouble() / total,
            newcomers = newcomers.size,
            total = rec.size,
            medianAgeDays = if (ages.isEmpty()) -1 else ages[ages.size / 2],
            freshShare = if (rec.isEmpty()) 0.0 else rec.count { fresh(it.uploadMs, now) }.toDouble() / rec.size
        )
    }

    // ------------------------------------------------------------ doors

    data class Door(val id: String, val children: Int, val onTopic: Int, val newcomers: List<String>, val score: Double)

    /** On-topic videos whose "Up next" (≥3 items seen) already contains fresh or small on-topic videos. */
    fun doors(nodes: List<RecNode>, edges: List<RecEdge>, now: Long, small: Long = SMALL_VIEWS, top: Int = 12): List<Door> {
        val byId = nodes.associateBy { it.id }
        return edges.groupBy { it.from }.mapNotNull { (from, out) ->
            val n = byId[from] ?: return@mapNotNull null
            if (n.relevant == 0 || out.size < 3) return@mapNotNull null
            val kids = out.sortedBy { it.rank }.mapNotNull { byId[it.to] }
            val on = kids.filter { it.relevant != 0 }
            val fresh = on.filter { !it.seed && isNewcomer(it, now, small) }
            if (fresh.isEmpty() || kids.isEmpty()) return@mapNotNull null
            val reach = min(1.0, log10(max(1L, n.views) + 1.0) / 6.0)
            Door(from, kids.size, on.size, fresh.map { it.id }, fresh.size.toDouble() / kids.size * (0.5 + 0.5 * reach))
        }.sortedWith(compareByDescending<Door> { it.score }.thenByDescending { it.children }).take(top)
    }

    // ------------------------------------------------------------ profile

    data class Feat(val key: String, val pos: Double, val neg: Double, val rate: Boolean) {
        val diff: Double get() = pos - neg
    }

    class Profile(
        val pos: Int,
        val neg: Int,
        /** true when the search side could be re-weighted to the same view levels */
        val matched: Boolean,
        val feats: List<Feat>,
        val durIqr: LongRange?,
        val titleIqr: IntRange?,
        val plus: List<Pair<String, Double>>,
        val minus: List<Pair<String, Double>>,
        val firstWords: List<Pair<String, Int>>
    ) {
        val enough: Boolean get() = pos >= 15 && neg >= 15
        fun feat(key: String) = feats.firstOrNull { it.key == key }

        /** Shape flags that differ by ≥15 points, strongest first. */
        fun strongShapes(): List<Feat> = if (pos < 8 || neg < 8) emptyList() else
            feats.filter { it.rate && it.key in SHAPES && abs(it.diff) >= 0.15 }.sortedByDescending { abs(it.diff) }
    }

    fun bucket(views: Long): Int = if (views < 0) -1 else min(7, log10(views + 1.0).toInt())

    /** Weights for [neg] so its view levels match [pos]; null when the two never overlap. */
    fun matchWeights(pos: List<Item>, neg: List<Item>): DoubleArray? {
        if (pos.isEmpty() || neg.isEmpty()) return null
        val pc = pos.groupingBy { bucket(it.views) }.eachCount()
        val nc = neg.groupingBy { bucket(it.views) }.eachCount()
        val w = DoubleArray(neg.size) { i ->
            val b = bucket(neg[i].views)
            val p = (pc[b] ?: 0).toDouble() / pos.size
            val q = (nc[b] ?: 0).toDouble() / neg.size
            if (q == 0.0) 0.0 else p / q
        }
        val covered = pos.count { (nc[bucket(it.views)] ?: 0) > 0 }
        return if (w.sum() <= 0 || covered * 2 < pos.size) null else w
    }

    fun wMedian(values: List<Double>, weights: List<Double>): Double {
        val pairs = values.zip(weights).filter { it.second > 0 && !it.first.isNaN() }.sortedBy { it.first }
        if (pairs.isEmpty()) return Double.NaN
        val half = pairs.sumOf { it.second } / 2
        var acc = 0.0
        for ((v, w) in pairs) {
            acc += w
            if (acc >= half) return v
        }
        return pairs.last().first
    }

    fun profile(items: List<Item>, now: Long): Profile {
        val pos = items.filter { it.inRecs }
        val neg = items.filter { !it.inRecs }
        val mw = matchWeights(pos, neg)
        val nw = mw?.toList() ?: List(neg.size) { 1.0 }
        val pw = List(pos.size) { 1.0 }
        fun rate(list: List<Item>, w: List<Double>, f: (Int) -> Boolean): Double {
            val s = w.sum()
            return if (s <= 0) 0.0 else list.indices.sumOf { if (f(it)) w[it] else 0.0 } / s
        }
        fun med(list: List<Item>, w: List<Double>, f: (Item) -> Double) = wMedian(list.map(f), w)
        val ps = pos.map { shape(it.title) }
        val ns = neg.map { shape(it.title) }
        val feats = ArrayList<Feat>()
        for (k in SHAPES) feats.add(Feat(k, rate(pos, pw) { ps[it][k] == true }, rate(neg, nw) { ns[it][k] == true }, true))
        feats.add(Feat("short", rate(pos, pw) { pos[it].isShort }, rate(neg, nw) { neg[it].isShort }, true))
        feats.add(Feat("fresh", rate(pos, pw) { fresh(pos[it].uploadMs, now) }, rate(neg, nw) { fresh(neg[it].uploadMs, now) }, true))
        val dur = { it: Item -> if (it.durationSec > 0) it.durationSec.toDouble() else Double.NaN }
        val len = { it: Item -> it.title.length.toDouble() }
        val age = { it: Item -> if (it.uploadMs in 1..now) ((now - it.uploadMs) / DAY).toDouble() else Double.NaN }
        feats.add(Feat("duration", med(pos, pw, dur), med(neg, nw, dur), false))
        feats.add(Feat("title_len", med(pos, pw, len), med(neg, nw, len), false))
        feats.add(Feat("age", med(pos, pw, age), med(neg, nw, age), false))
        val durs = pos.filter { it.durationSec > 0 }.map { it.durationSec }.sorted()
        val lens = pos.map { it.title.length.toLong() }.sorted()
        val first = pos.mapNotNull { Words.tokens(it.title, 2).firstOrNull() }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        return Profile(
            pos = pos.size, neg = neg.size, matched = mw != null, feats = feats,
            durIqr = if (durs.size >= 6) quartile(durs, 0.25)..quartile(durs, 0.75) else null,
            titleIqr = if (lens.size >= 6) quartile(lens, 0.25).toInt()..quartile(lens, 0.75).toInt() else null,
            plus = distinctive(pos.map { it.title }, neg.map { it.title }, top = 12),
            minus = distinctive(neg.map { it.title }, pos.map { it.title }, top = 8),
            firstWords = first
        )
    }

    // ------------------------------------------------------------ model

    val MODEL_FEATURES = listOf(
        "views", "age", "duration", "short", "title_len", "number", "question", "exclaim", "colon", "brackets", "caps", "emoji", "you", "topic"
    )

    /** Raw feature vector (NaN = unknown, imputed with the mean). [topicSim] is the title's similarity to the topic. */
    fun features(it: Item, now: Long, topicSim: Double): DoubleArray {
        val sh = shape(it.title)
        fun b(k: String) = if (sh[k] == true) 1.0 else 0.0
        return doubleArrayOf(
            if (it.views >= 0) log10(it.views + 1.0) else Double.NaN,
            if (it.uploadMs in 1..now) log10((now - it.uploadMs) / DAY + 1.0) else Double.NaN,
            if (it.durationSec > 0) ln(it.durationSec.toDouble()) else Double.NaN,
            if (it.isShort) 1.0 else 0.0,
            it.title.length / 100.0,
            b("number"), b("question"), b("exclaim"), b("colon"), b("brackets"), b("caps"), b("emoji"), b("you"),
            topicSim
        )
    }

    class Model(
        val names: List<String>,
        val mean: DoubleArray,
        val sd: DoubleArray,
        val w: DoubleArray,
        val b: Double,
        /** 5-fold cross-validated AUC (0.5 = coin) */
        val auc: Double,
        val pos: Int,
        val neg: Int,
        /** in-sample scores of the positives, sorted, to rank a draft among them */
        val posScores: List<Double>
    ) {
        fun prob(x: DoubleArray): Double = sigmoid(b + w.indices.sumOf { w[it] * std(x, it) })
        private fun std(x: DoubleArray, i: Int) = if (x[i].isNaN() || sd[i] <= 0) 0.0 else (x[i] - mean[i]) / sd[i]

        /** Coefficients by strength: positive = more often recommended, at equal other features. */
        fun factors(): List<Pair<String, Double>> = names.indices.map { names[it] to w[it] }.sortedByDescending { abs(it.second) }

        /** Share of recommended videos the score [p] beats (0..1). */
        fun rank(p: Double): Double = if (posScores.isEmpty()) 0.0 else posScores.count { it <= p }.toDouble() / posScores.size

        val reliable: Boolean get() = auc >= 0.6 && pos >= 15 && neg >= 15
    }

    fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

    /** Mann–Whitney AUC with tied ranks. */
    fun auc(scores: List<Double>, labels: List<Int>): Double {
        val np = labels.count { it == 1 }
        val nn = labels.size - np
        if (np == 0 || nn == 0) return 0.5
        val order = scores.indices.sortedBy { scores[it] }
        val ranks = DoubleArray(scores.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && scores[order[j + 1]] == scores[order[i]]) j++
            val r = (i + j) / 2.0 + 1
            for (k in i..j) ranks[order[k]] = r
            i = j + 1
        }
        val sumPos = labels.indices.filter { labels[it] == 1 }.sumOf { ranks[it] }
        return (sumPos - np * (np + 1) / 2.0) / (np.toDouble() * nn)
    }

    /** Class-balanced L2 logistic regression by gradient descent on standardised features. */
    fun train(x: List<DoubleArray>, y: List<Int>, lambda: Double = 0.05, iters: Int = 300, lr: Double = 0.5): Model? {
        val n = x.size
        val np = y.count { it == 1 }
        val nn = n - np
        if (np == 0 || nn == 0) return null
        val d = x[0].size
        val mean = DoubleArray(d) { j -> x.map { it[j] }.filter { !it.isNaN() }.let { if (it.isEmpty()) 0.0 else it.average() } }
        val sd = DoubleArray(d) { j ->
            val v = x.map { it[j] }.filter { !it.isNaN() }
            if (v.size < 2) 0.0 else sqrt(v.sumOf { (it - mean[j]) * (it - mean[j]) } / (v.size - 1))
        }
        val z = Array(n) { i -> DoubleArray(d) { j -> if (x[i][j].isNaN() || sd[j] <= 0) 0.0 else (x[i][j] - mean[j]) / sd[j] } }
        val cw = DoubleArray(n) { if (y[it] == 1) n / (2.0 * np) else n / (2.0 * nn) }
        val w = DoubleArray(d)
        var b = 0.0
        repeat(iters) {
            val gw = DoubleArray(d)
            var gb = 0.0
            for (i in 0 until n) {
                var s = b
                for (j in 0 until d) s += w[j] * z[i][j]
                val e = (sigmoid(s) - y[i]) * cw[i]
                for (j in 0 until d) gw[j] += e * z[i][j]
                gb += e
            }
            for (j in 0 until d) w[j] -= lr * (gw[j] / n + lambda * w[j])
            b -= lr * gb / n
        }
        val m = Model(MODEL_FEATURES.take(d).ifEmpty { List(d) { "f$it" } }, mean, sd, w, b, 0.5, np, nn, emptyList())
        val ps = x.indices.filter { y[it] == 1 }.map { m.prob(x[it]) }.sorted()
        return Model(m.names, mean, sd, w, b, 0.5, np, nn, ps)
    }

    /** Fits on everything and measures AUC by 5 folds split on the video id; null below 15 per class. */
    fun model(items: List<Item>, now: Long, topicSim: (Item) -> Double, minPerClass: Int = 15): Model? {
        val y = items.map { if (it.inRecs) 1 else 0 }
        if (y.count { it == 1 } < minPerClass || y.count { it == 0 } < minPerClass) return null
        val x = items.map { features(it, now, topicSim(it)) }
        val fold = items.map { (it.id.hashCode() and 0x7FFFFFFF) % 5 }
        val scores = ArrayList<Double>()
        val labels = ArrayList<Int>()
        for (f in 0 until 5) {
            val tr = items.indices.filter { fold[it] != f }
            val te = items.indices.filter { fold[it] == f }
            if (te.isEmpty()) continue
            val m = train(tr.map { x[it] }, tr.map { y[it] }) ?: continue
            te.forEach {
                scores.add(m.prob(x[it]))
                labels.add(y[it])
            }
        }
        val full = train(x, y) ?: return null
        return Model(full.names, full.mean, full.sd, full.w, full.b, auc(scores, labels), full.pos, full.neg, full.posScores)
    }

    // ------------------------------------------------------------ the whole analysis

    class Analysis(
        val now: Long,
        val nodes: Map<String, RecNode>,
        val onTopic: Int,
        val unknown: Int,
        val expanded: Int,
        val communities: List<Community>,
        /** video id → community id */
        val member: Map<String, Int>,
        val open: Openness,
        val doors: List<Door>,
        val profile: Profile,
        val model: Model?,
        val vocab: Vocab,
        val centroids: Map<Int, Centroid>,
        val topic: Centroid,
        val search: Int,
        val confidence: Int,
        val why: List<String>
    ) {
        fun community(id: Int) = communities.firstOrNull { it.id == id }

        /** Doors inside community [id] (or all doors when it has none). */
        fun doorsOf(id: Int): List<Door> = doors.filter { member[it.id] == id }.ifEmpty { doors }
    }

    fun analyse(nodes: List<RecNode>, edges: List<RecEdge>, search: List<Item>, now: Long): Analysis {
        val vocab = Vocab(nodes.map { it.title } + search.map { it.title })
        val vec = HashMap<String, Map<String, Double>>()
        nodes.forEach { vec[it.id] = vocab.vec(it.title) }
        val comms = communities(nodes, edges, now)
        val member = HashMap<String, Int>()
        comms.forEach { c -> c.ids.forEach { member[it] = c.id } }
        val byId = nodes.associateBy { it.id }
        val centroids = comms.associate { c ->
            c.id to Centroid.of(c.ids.mapNotNull { id -> vec[id]?.let { it to memberWeight(byId[id]?.hits ?: 0) } })
        }
        val topic = Centroid.of(nodes.filter { it.relevant != 0 }.mapNotNull { n -> vec[n.id]?.let { it to memberWeight(n.hits) } })
        val items = sample(nodes, search)
        val prof = profile(items, now)
        val model = model(items, now, { topic.sim(vocab.vec(it.title)) })
        val onTopic = nodes.count { it.relevant == 1 }
        val unknown = nodes.count { it.relevant == -1 }
        val (conf, why) = confidence(nodes.size - nodes.count { it.relevant == 0 }, unknown, nodes.size, comms.size, prof, model)
        return Analysis(
            now, byId, onTopic, unknown, nodes.count { it.expanded }, comms, member,
            openness(nodes, now), doors(nodes, edges, now), prof, model, vocab, centroids, topic, search.size, conf, why
        )
    }

    /** 0 low, 1 medium, 2 high, with the reasons that lowered it (keys for the UI). */
    fun confidence(onTopic: Int, unknown: Int, total: Int, communities: Int, p: Profile, m: Model?): Pair<Int, List<String>> {
        val why = ArrayList<String>()
        if (onTopic < 60) why.add("few_nodes")
        if (communities == 0) why.add("no_communities")
        if (total > 0 && unknown * 2 > total) why.add("topic_unchecked")
        if (!p.enough) why.add("few_samples")
        if (p.pos > 0 && p.neg > 0 && !p.matched) why.add("unmatched")
        if (m == null || !m.reliable) why.add("weak_model")
        val penalty = why.size
        val level = when {
            "few_nodes" in why || "no_communities" in why || penalty >= 3 -> 0
            penalty >= 1 -> 1
            else -> 2
        }
        return level to why
    }

    // ------------------------------------------------------------ draft

    data class Draft(
        val title: String = "",
        val description: String = "",
        val script: String = "",
        val durationSec: Long = -1,
        val isShort: Boolean = false
    ) {
        val isEmpty: Boolean get() = title.isBlank() && description.isBlank() && script.isBlank()

        fun toJson(): JSONObject = JSONObject().put("title", title).put("description", description).put("script", script)
            .put("durationSec", durationSec).put("isShort", isShort)

        companion object {
            fun from(o: JSONObject?): Draft = if (o == null) Draft() else Draft(
                o.optString("title"), o.optString("description"), o.optString("script"),
                o.optLong("durationSec", -1), o.optBoolean("isShort")
            )
        }
    }

    /** "8:30", "1:02:03", "45" (seconds) or "45s"/"8m" → seconds; -1 if unreadable. */
    fun parseDuration(s: String): Long {
        val t = s.trim().lowercase().replace(',', '.')
        if (t.isEmpty()) return -1
        Regex("""^(\d+)\s*(s|с|сек)$""").find(t)?.let { return it.groupValues[1].toLong() }
        Regex("""^(\d+(?:\.\d+)?)\s*(m|м|мин)$""").find(t)?.let { return (it.groupValues[1].toDouble() * 60).toLong() }
        val parts = t.split(':')
        if (parts.any { p -> p.isEmpty() || !p.all { it.isDigit() } } || parts.size > 3) return -1
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    }

    class Check(val key: String, val ok: Boolean, val weight: Double, val detail: String = "")

    class Fit(
        val score: Int,
        /** draft's topic match against the target community, 0..1 (percentile among its members) */
        val topic: Double,
        val target: Int,
        /** communities by similarity to the draft (id, cosine) */
        val sims: List<Pair<Int, Double>>,
        val checks: List<Check>,
        val missing: List<String>,
        /** model score for a new small video with this draft, and its rank among recommended videos */
        val modelP: Double?,
        val modelRank: Double?,
        val confidence: Int,
        val why: List<String>
    ) {
        val best: Int get() = sims.firstOrNull()?.first ?: 0
    }

    /** Unit vector of a draft: title 0.6, description 0.2, script 0.2 (only present parts). */
    fun draftVec(v: Vocab, d: Draft): Map<String, Double> {
        val parts = listOf(d.title to 0.6, d.description to 0.2, d.script to 0.2).filter { it.first.isNotBlank() }
        val s = HashMap<String, Double>()
        parts.forEach { (text, w) -> v.vec(text).forEach { (k, x) -> s[k] = (s[k] ?: 0.0) + x * w } }
        return unit(s)
    }

    fun fit(a: Analysis, d: Draft, target: Int): Fit {
        val dv = draftVec(a.vocab, d)
        val sims = a.centroids.map { (id, c) -> id to c.sim(dv) }.sortedByDescending { it.second }
        val tid = if (a.community(target) != null) target else sims.firstOrNull()?.first ?: 0
        val comm = a.community(tid)
        val cen = a.centroids[tid]
        // percentile of the draft among the community's own members (leave-one-out)
        val topic = if (comm == null || cen == null) 0.0 else {
            val ds = cen.sim(dv)
            val own = comm.ids.mapNotNull { id ->
                a.nodes[id]?.let { n -> cen.simWithout(a.vocab.vec(n.title), memberWeight(n.hits)) }
            }
            if (own.isEmpty()) 0.0 else own.count { it <= ds }.toDouble() / own.size
        }
        val checks = ArrayList<Check>()
        val text = (d.title + " " + d.description + " " + d.script)
        val have = stems(text).toSet()
        val titleStems = stems(d.title).toSet()
        val terms = comm?.terms.orEmpty()
        val missing = terms.filter { Relevance.stem(it.lowercase()) !in have }.take(6)
        if (terms.isNotEmpty() && d.title.isNotBlank()) {
            val hit = terms.filter { Relevance.stem(it.lowercase()) in titleStems }
            checks.add(Check("terms", hit.isNotEmpty(), 1.0, hit.joinToString(", ")))
        }
        val dIqr = comm?.durIqr ?: a.profile.durIqr
        if (d.durationSec > 0 && dIqr != null) {
            val lo = dIqr.first * 7 / 10
            val hi = dIqr.last * 13 / 10
            checks.add(Check("duration", d.durationSec in lo..hi, 1.0, "${dIqr.first}–${dIqr.last}"))
        }
        if (comm != null && comm.ids.size >= 6) {
            val ok = when {
                comm.shortsShare >= 0.6 -> d.isShort
                comm.shortsShare <= 0.2 -> !d.isShort
                else -> true
            }
            checks.add(Check("format", ok, 0.7, String.format(java.util.Locale.US, "%.0f", comm.shortsShare * 100)))
        }
        val tIqr = a.profile.titleIqr
        if (tIqr != null && d.title.isNotBlank()) {
            checks.add(Check("title_len", d.title.length in (tIqr.first * 8 / 10)..(tIqr.last * 12 / 10), 0.5, "${tIqr.first}–${tIqr.last}"))
        }
        if (d.title.isNotBlank()) {
            val sh = shape(d.title)
            a.profile.strongShapes().take(3).forEach { f ->
                val want = f.diff > 0
                checks.add(Check("shape_" + f.key, (sh[f.key] == true) == want, 0.5, if (want) "+" else "-"))
            }
        }
        val total = checks.sumOf { it.weight }
        val passed = checks.filter { it.ok }.sumOf { it.weight }
        val checkPart = if (total > 0) passed / total else topic
        val score = (100 * (0.55 * topic + 0.45 * checkPart)).toInt().coerceIn(0, 100)
        val m = a.model
        val p = m?.let {
            val item = Item("draft", d.title, views = NEWCOMER_VIEWS, uploadMs = a.now - 3 * DAY, durationSec = d.durationSec, isShort = d.isShort)
            it.prob(features(item, a.now, a.topic.sim(a.vocab.vec(d.title))))
        }
        val why = ArrayList(a.why)
        if (comm == null) why.add("no_target") else if (comm.ids.size < 8) why.add("small_target")
        if (d.title.isBlank()) why.add("no_title")
        val conf = when {
            a.confidence == 0 || comm == null || d.title.isBlank() -> 0
            comm.ids.size < 8 -> min(1, a.confidence)
            else -> a.confidence
        }
        return Fit(score, topic, tid, sims, checks, missing, p, p?.let { m.rank(it) }, conf, why.distinct())
    }

    // ------------------------------------------------------------ after publishing

    data class Rel(val id: String, val title: String, val channel: String = "")

    /** Our video's "Up next" mapped to communities: code ≥1 community, 0 on topic outside them, -1 off topic, -2 unknown. */
    class Placement(val codes: List<Int>) {
        val total get() = codes.size
        val onTopic get() = codes.count { it >= 0 }
        val off get() = codes.count { it == -1 }
        val unknown get() = codes.indices.filter { codes[it] == -2 }
        fun byCommunity(): List<Pair<Int, Int>> = codes.filter { it >= 1 }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
        fun share(id: Int): Double = if (codes.isEmpty()) 0.0 else codes.count { it == id }.toDouble() / codes.size

        fun with(ai: Map<Int, Int>): Placement = Placement(codes.mapIndexed { i, c -> if (c == -2) ai[i] ?: c else c })
    }

    fun place(a: Analysis, rel: List<Rel>, keywords: Set<String>, minSim: Double = 0.18): Placement = Placement(rel.map { r ->
        a.member[r.id]?.let { return@map it }
        val n = a.nodes[r.id]
        if (n != null && n.relevant == 0) return@map -1
        val v = a.vocab.vec(r.title)
        val best = a.centroids.map { (id, c) -> id to c.sim(v) }.maxByOrNull { it.second }
        if (best != null && best.second >= minSim) return@map best.first
        val kw = keywords.isNotEmpty() && Relevance.matches(keywords, r.title + " " + r.channel)
        when {
            kw -> 0
            a.topic.sim(v) < 0.03 && keywords.isNotEmpty() -> -1
            else -> -2
        }
    })

    data class Point(
        val at: Long,
        val views: Long,
        val related: Int,
        val onTopic: Int,
        val off: Int,
        /** label of the community most of "Up next" belongs to, and its share */
        val top: String,
        val topShare: Double,
        val target: String,
        val targetShare: Double,
        /** hubs/doors checked, and where our video was seen there ("id:position") */
        val checked: Int,
        val where: List<String>
    ) {
        val found: Int get() = where.size

        fun toJson(): JSONObject = JSONObject().put("at", at).put("views", views).put("related", related).put("onTopic", onTopic)
            .put("off", off).put("top", top).put("topShare", topShare).put("target", target).put("targetShare", targetShare)
            .put("checked", checked).put("where", JSONArray(where))

        companion object {
            fun from(o: JSONObject) = Point(
                o.optLong("at"), o.optLong("views", -1), o.optInt("related"), o.optInt("onTopic"), o.optInt("off"),
                o.optString("top"), o.optDouble("topShare", 0.0), o.optString("target"), o.optDouble("targetShare", 0.0),
                o.optInt("checked"), o.optJSONArray("where")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            )
        }
    }

    fun point(a: Analysis, p: Placement, at: Long, views: Long, target: Int, checked: Int, where: List<String>): Point {
        val top = p.byCommunity().firstOrNull()
        return Point(
            at, views, p.total, p.onTopic, p.off,
            top?.let { a.community(it.first)?.label } ?: "",
            if (top == null || p.total == 0) 0.0 else top.second.toDouble() / p.total,
            a.community(target)?.label ?: "",
            p.share(target), checked, where
        )
    }

    /** -1 worse, 0 same, 1 better: target share and sightings of the last point against the first. */
    fun trend(points: List<Point>): Int {
        if (points.size < 2) return 0
        val a = points.first()
        val b = points.last()
        val d = (b.targetShare - a.targetShare) + 0.1 * (b.found - a.found)
        return when {
            d > 0.05 -> 1
            d < -0.05 -> -1
            else -> 0
        }
    }

    // ------------------------------------------------------------ AI answers (parsers)

    class Idea(
        val door: Int,
        val title: String,
        val angle: String,
        val hook: String,
        val description: String,
        val tags: List<String>,
        val lengthSec: Long,
        val shorts: Boolean,
        val why: String
    ) {
        fun draft() = Draft(title, description, hook, lengthSec, shorts)

        fun toJson(): JSONObject = JSONObject().put("door", door).put("title", title).put("angle", angle).put("hook", hook)
            .put("description", description).put("tags", JSONArray(tags)).put("length_sec", lengthSec).put("shorts", shorts).put("why", why)

        companion object {
            fun from(o: JSONObject) = Idea(
                o.optInt("door"), o.optString("title").trim(), o.optString("angle"), o.optString("hook"), o.optString("description"),
                strings(o.optJSONArray("tags")), o.optLong("length_sec", -1), o.optBoolean("shorts"), o.optString("why")
            )
        }
    }

    class Review(
        val topicFit: Int,
        val continuation: Int,
        val door: Int,
        val click: Int,
        val promise: String,
        val risks: List<String>,
        val fixes: List<String>,
        val titles: List<String>,
        val confidence: String,
        val why: String
    ) {
        fun toJson(): JSONObject = JSONObject().put("topic_fit", topicFit).put("continuation", continuation).put("door", door)
            .put("click", click).put("promise", promise).put("risks", JSONArray(risks)).put("fixes", JSONArray(fixes))
            .put("titles", JSONArray(titles)).put("confidence", confidence).put("why", why)

        companion object {
            fun from(o: JSONObject) = Review(
                pct(o.opt("topic_fit")), pct(o.opt("continuation")), o.optInt("door"), pct(o.opt("click")), o.optString("promise"),
                strings(o.optJSONArray("risks")), strings(o.optJSONArray("fixes")), strings(o.optJSONArray("titles")),
                o.optString("confidence").lowercase().let { if (it in setOf("low", "medium", "high")) it else "low" }, o.optString("why")
            )
        }
    }

    fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }

    /** 0..100 from a number, "72", "72%" or a 0..1 fraction. */
    fun pct(v: Any?): Int {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().removeSuffix("%").trim().toDoubleOrNull() ?: return 0
            else -> return 0
        }
        return (if (d in 0.0..1.0 && d != 0.0 && d != 1.0 && v !is Int) d * 100 else d).toInt().coerceIn(0, 100)
    }

    private fun obj(text: String): JSONObject? = JsonText.firstObject(text)?.let {
        try {
            JSONObject(it)
        } catch (e: Exception) {
            null
        }
    }

    /** {"groups":[{"n":1,"name":…,"about":…,"on_topic":true}]} → n → (name, about, onTopic). */
    fun parseNames(text: String): Map<Int, Triple<String, String, Boolean>> {
        val a = obj(text)?.optJSONArray("groups") ?: return emptyMap()
        val out = LinkedHashMap<Int, Triple<String, String, Boolean>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (o.optInt("n") <= 0 || name.isEmpty()) continue
            out[o.optInt("n")] = Triple(name.take(60), o.optString("about").trim(), o.optBoolean("on_topic", true))
        }
        return out
    }

    /** {"a":[[1,2],[2,-1]]} (1-based items) → item index (0-based) → community id / 0 / -1. */
    fun parseClassify(text: String, count: Int, ids: Set<Int>): Map<Int, Int> {
        val a = obj(text)?.optJSONArray("a") ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            val item = p.optInt(0) - 1
            val g = p.optInt(1, -2)
            if (item !in 0 until count) continue
            out[item] = when {
                g in ids -> g
                g == 0 -> 0
                else -> -1
            }
        }
        return out
    }

    fun parseIdeas(text: String, doors: Int): List<Idea> {
        val a = obj(text)?.optJSONArray("ideas") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Idea::from) }
            .filter { it.title.isNotBlank() }
            .map { if (it.door in 1..doors) it else Idea(0, it.title, it.angle, it.hook, it.description, it.tags, it.lengthSec, it.shorts, it.why) }
            .distinctBy { it.title.lowercase() }
    }

    fun parseReview(text: String): Review? = obj(text)?.let { if (it.has("topic_fit") || it.has("fixes")) Review.from(it) else null }

    // ------------------------------------------------------------ data blocks for prompts

    private fun f0(d: Double) = String.format(java.util.Locale.US, "%.0f", d)

    fun title(a: Analysis, id: String): String = a.nodes[id]?.let { "${it.title} — ${it.channel}" } ?: id

    fun groupBlock(a: Analysis, c: Community, hubs: Int = 5): String =
        "#${c.id}" + (if (c.name.isNotBlank()) " «${c.name}»" else "") + ": ${c.ids.size} videos, ${c.hits} recs, on topic ${f0(c.onTopic * 100)}%, " +
            "newcomers ${f0(c.open.share * 100)}%, shorts ${f0(c.shortsShare * 100)}%" +
            (c.durIqr?.let { ", duration IQR ${it.first}–${it.last} s" } ?: "") + "\n" +
            "words: ${c.terms.joinToString(", ").ifBlank { "-" }}\n" +
            "channels: ${c.channels.joinToString(", ") { "${it.first} (${it.second})" }}\n" +
            "top: " + c.hubs.take(hubs).joinToString("; ") { title(a, it) }

    fun groupsBlock(a: Analysis, hubs: Int = 5): String = a.communities.joinToString("\n\n") { groupBlock(a, it, hubs) }

    fun doorsBlock(a: Analysis, doors: List<Door>): String = doors.mapIndexed { i, d ->
        val n = a.nodes[d.id]
        "${i + 1}. ${title(a, d.id)} | views ${n?.views ?: -1} | in Up next: ${d.newcomers.size} newcomers of ${d.children}: " +
            d.newcomers.take(3).joinToString("; ") { a.nodes[it]?.title ?: it }
    }.joinToString("\n")

    fun profileBlock(p: Profile): String {
        val sb = StringBuilder()
        sb.append("recommended ${p.pos} vs found by search but not recommended ${p.neg}")
            .append(if (p.matched) " (search side re-weighted to the same view levels)" else " (view levels not matched)").append('\n')
        p.feats.forEach { f ->
            if (f.rate) sb.append("${f.key}: ${f0(f.pos * 100)}% vs ${f0(f.neg * 100)}%\n")
            else if (!f.pos.isNaN()) sb.append("${f.key} median: ${f0(f.pos)} vs ${if (f.neg.isNaN()) "-" else f0(f.neg)}\n")
        }
        p.durIqr?.let { sb.append("duration corridor (sec, IQR of recommended): ${it.first}–${it.last}\n") }
        p.titleIqr?.let { sb.append("title length corridor (chars): ${it.first}–${it.last}\n") }
        if (p.plus.isNotEmpty()) sb.append("words of recommended: ${p.plus.joinToString(", ") { it.first }}\n")
        if (p.minus.isNotEmpty()) sb.append("words of not recommended: ${p.minus.joinToString(", ") { it.first }}\n")
        if (p.firstWords.isNotEmpty()) sb.append("first words: ${p.firstWords.joinToString(", ") { "${it.first} (${it.second})" }}\n")
        return sb.toString().trim()
    }

    fun modelBlock(m: Model?): String = if (m == null) "model: not enough data" else
        "model AUC ${String.format(java.util.Locale.US, "%.2f", m.auc)} (${m.pos}/${m.neg})" + (if (m.reliable) "" else " — unreliable") + "; factors: " +
            m.factors().take(6).joinToString(", ") { "${it.first} ${if (it.second >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", it.second)}" }

    fun draftBlock(d: Draft): String = "title: ${d.title}\nduration: ${if (d.durationSec > 0) "${d.durationSec} s" else "-"}" +
        (if (d.isShort) " (Shorts)" else "") + "\ndescription: ${d.description.take(1500)}\nscript/hook: ${d.script.take(3000)}"

    fun fitBlock(f: Fit): String = "local score ${f.score}/100, topic match ${f0(f.topic * 100)}% of members, confidence ${f.confidence}/2\n" +
        "checks: " + f.checks.joinToString("; ") { "${it.key} ${if (it.ok) "ok" else "FAIL"} ${it.detail}".trim() } +
        (if (f.missing.isNotEmpty()) "\nmissing community words: ${f.missing.joinToString(", ")}" else "") +
        (f.modelP?.let { "\nmodel: ${f0(it * 100)}% (beats ${f0((f.modelRank ?: 0.0) * 100)}% of recommended)" } ?: "")

    /** Everything the tab knows, compact, for the plan prompt and the "Ask AI" bar. */
    fun brief(a: Analysis, target: Int, draft: Draft?, points: List<Point>): String {
        val sb = StringBuilder()
        sb.append("graph: ${a.nodes.size} videos (${a.onTopic} on topic, ${a.unknown} unchecked), expanded ${a.expanded}; search sample ${a.search}\n")
        sb.append("confidence ${a.confidence}/2; weak spots: ${a.why.joinToString(", ").ifBlank { "-" }}\n")
        sb.append("openness overall: newcomers ${f0(a.open.share * 100)}% of recommendations, fresh ${f0(a.open.freshShare * 100)}%, median age ${a.open.medianAgeDays} d\n\n")
        sb.append("COMMUNITIES\n").append(groupsBlock(a, 3)).append("\n\n")
        a.community(target)?.let { sb.append("TARGET: #${it.id} ${it.label}\n\n") }
        sb.append("DOORS\n").append(doorsBlock(a, a.doorsOf(target).take(8))).append("\n\n")
        sb.append("PROFILE\n").append(profileBlock(a.profile)).append("\n\n").append(modelBlock(a.model)).append('\n')
        if (draft != null && !draft.isEmpty) sb.append("\nDRAFT\n").append(draftBlock(draft)).append('\n').append(fitBlock(fit(a, draft, target))).append('\n')
        if (points.isNotEmpty()) {
            sb.append("\nAFTER PUBLISHING (our video's Up next)\n")
            points.takeLast(10).forEach {
                sb.append("${it.at}: related ${it.related}, on topic ${it.onTopic}, top «${it.top}» ${f0(it.topShare * 100)}%, " +
                    "target ${f0(it.targetShare * 100)}%, seen in ${it.found} of ${it.checked} hubs/doors\n")
            }
        }
        return sb.toString().trim()
    }
}

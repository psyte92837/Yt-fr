/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Pure algorithms of the "Algorithm" tab (no Android, no extractor; covered by AlgoCoreTest).
 * Graphs are index graphs: nodes 0 until n, directed weighted edges a -> b.
 */

/** Directed weighted edge of an index graph. */
class IEdge(val a: Int, val b: Int, val w: Double = 1.0)

object AlgoCore {
    /** Weight of a recommendation by its place on the watch page (DCG-like): 1, 0.63, 0.5, 0.43, … */
    fun posWeight(pos: Int): Double = 1.0 / (ln(pos.coerceAtLeast(0) + 2.0) / ln(2.0))

    /**
     * Weighted PageRank. [restart] is the teleport distribution (null = uniform): with the seeds it becomes
     * personalised PageRank, "how likely a viewer starting from the seeds ends up here". Dangling nodes
     * (never expanded) send their rank to the restart distribution. The result sums to 1.
     */
    fun pageRank(n: Int, edges: List<IEdge>, restart: DoubleArray? = null, damping: Double = 0.85, iterations: Int = 60): DoubleArray {
        if (n == 0) return DoubleArray(0)
        val r = normalised(restart?.takeIf { it.size == n && it.sum() > 0 } ?: DoubleArray(n) { 1.0 })
        val out = DoubleArray(n)
        val clean = edges.filter { it.a != it.b && it.a in 0 until n && it.b in 0 until n && it.w > 0 }
        clean.forEach { out[it.a] += it.w }
        var pr = r.copyOf()
        repeat(iterations) {
            var dangling = 0.0
            for (i in 0 until n) if (out[i] == 0.0) dangling += pr[i]
            val next = DoubleArray(n) { (1 - damping) * r[it] + damping * dangling * r[it] }
            for (e in clean) next[e.b] += damping * pr[e.a] * e.w / out[e.a]
            pr = next
        }
        return normalised(pr)
    }

    private fun normalised(v: DoubleArray): DoubleArray {
        val s = v.sum()
        return if (s <= 0) DoubleArray(v.size) { 1.0 / max(1, v.size) } else DoubleArray(v.size) { v[it] / s }
    }

    /** HITS: authority (many good hubs point here) and hub (points to many good authorities), each scaled to max 1. */
    fun hits(n: Int, edges: List<IEdge>, iterations: Int = 50): Pair<DoubleArray, DoubleArray> {
        var auth = DoubleArray(n) { 1.0 }
        var hub = DoubleArray(n) { 1.0 }
        val clean = edges.filter { it.a != it.b && it.a in 0 until n && it.b in 0 until n }
        repeat(iterations) {
            val na = DoubleArray(n)
            for (e in clean) na[e.b] += hub[e.a] * e.w
            val nh = DoubleArray(n)
            for (e in clean) nh[e.a] += na[e.b] * e.w
            auth = scaledToMax(na)
            hub = scaledToMax(nh)
        }
        return auth to hub
    }

    private fun scaledToMax(v: DoubleArray): DoubleArray {
        val m = v.maxOrNull() ?: 0.0
        return if (m <= 0) DoubleArray(v.size) else DoubleArray(v.size) { v[it] / m }
    }

    /** Undirected adjacency lists (no duplicates, no self-loops). */
    fun undirected(n: Int, edges: List<IEdge>): Array<IntArray> {
        val sets = Array(n) { LinkedHashSet<Int>() }
        for (e in edges) if (e.a != e.b && e.a in 0 until n && e.b in 0 until n) {
            sets[e.a].add(e.b)
            sets[e.b].add(e.a)
        }
        return Array(n) { sets[it].toIntArray() }
    }

    /** Brandes betweenness on the undirected unweighted graph, scaled to max 1: bridges between niches score high. */
    fun betweenness(n: Int, edges: List<IEdge>): DoubleArray {
        val adj = undirected(n, edges)
        val cb = DoubleArray(n)
        val sigma = DoubleArray(n)
        val dist = IntArray(n)
        val delta = DoubleArray(n)
        val stack = IntArray(n)
        val queue = IntArray(n)
        val preds = Array(n) { ArrayList<Int>(4) }
        for (s in 0 until n) {
            if (adj[s].isEmpty()) continue
            for (i in 0 until n) {
                preds[i].clear()
                sigma[i] = 0.0
                dist[i] = -1
                delta[i] = 0.0
            }
            sigma[s] = 1.0
            dist[s] = 0
            var head = 0
            var tail = 0
            var top = 0
            queue[tail++] = s
            while (head < tail) {
                val v = queue[head++]
                stack[top++] = v
                for (w in adj[v]) {
                    if (dist[w] < 0) {
                        dist[w] = dist[v] + 1
                        queue[tail++] = w
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v]
                        preds[w].add(v)
                    }
                }
            }
            while (top > 0) {
                val w = stack[--top]
                for (v in preds[w]) delta[v] += sigma[v] / sigma[w] * (1 + delta[w])
                if (w != s) cb[w] += delta[w]
            }
        }
        return scaledToMax(cb)
    }

    /**
     * Louvain community detection on the undirected weighted graph (both directions add up).
     * Returns a community per node, numbered by size (0 = largest). Deterministic for a given [seed].
     */
    fun louvain(n: Int, edges: List<IEdge>, seed: Long = 7L, resolution: Double = 1.0): IntArray {
        if (n == 0) return IntArray(0)
        // level graph: adjacency maps + self weights
        var adj: Array<HashMap<Int, Double>> = Array(n) { HashMap() }
        var self = DoubleArray(n)
        for (e in edges) {
            if (e.a !in 0 until n || e.b !in 0 until n || e.w <= 0) continue
            if (e.a == e.b) {
                self[e.a] += 2 * e.w
            } else {
                adj[e.a][e.b] = (adj[e.a][e.b] ?: 0.0) + e.w
                adj[e.b][e.a] = (adj[e.b][e.a] ?: 0.0) + e.w
            }
        }
        var member = IntArray(n) { it } // original node -> current super node
        val rnd = java.util.Random(seed)
        while (true) {
            val size = adj.size
            val k = DoubleArray(size) { i -> adj[i].values.sum() + self[i] }
            val m2 = k.sum()
            if (m2 <= 0) break
            val comm = IntArray(size) { it }
            val tot = k.copyOf()
            var moved = false
            val order = (0 until size).shuffled(rnd)
            var improved = true
            var passes = 0
            while (improved && passes < 30) {
                improved = false
                passes++
                for (i in order) {
                    val ci = comm[i]
                    val links = HashMap<Int, Double>()
                    for ((j, w) in adj[i]) links[comm[j]] = (links[comm[j]] ?: 0.0) + w
                    tot[ci] -= k[i]
                    var best = ci
                    var bestGain = (links[ci] ?: 0.0) - resolution * tot[ci] * k[i] / m2
                    for ((c, w) in links) {
                        val gain = w - resolution * tot[c] * k[i] / m2
                        if (gain > bestGain + 1e-12) {
                            bestGain = gain
                            best = c
                        }
                    }
                    tot[best] += k[i]
                    if (best != ci) {
                        comm[i] = best
                        improved = true
                        moved = true
                    }
                }
            }
            if (!moved) break
            // aggregate
            val ids = HashMap<Int, Int>()
            for (i in 0 until size) ids.getOrPut(comm[i]) { ids.size }
            val m = ids.size
            val nAdj = Array(m) { HashMap<Int, Double>() }
            val nSelf = DoubleArray(m)
            for (i in 0 until size) {
                val ci = ids.getValue(comm[i])
                nSelf[ci] += self[i]
                for ((j, w) in adj[i]) {
                    val cj = ids.getValue(comm[j])
                    if (ci == cj) nSelf[ci] += w else nAdj[ci][cj] = (nAdj[ci][cj] ?: 0.0) + w
                }
            }
            member = IntArray(n) { ids.getValue(comm[member[it]]) }
            if (m == size) break
            adj = nAdj
            self = nSelf
        }
        // number by size, largest first
        val counts = member.toList().groupingBy { it }.eachCount()
        val rank = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .mapIndexed { i, e -> e.key to i }.toMap()
        return IntArray(n) { rank.getValue(member[it]) }
    }

    /** Newman modularity of a partition on the undirected weighted graph. */
    fun modularity(n: Int, edges: List<IEdge>, comm: IntArray): Double {
        val k = DoubleArray(n)
        var m2 = 0.0
        var inside = 0.0
        for (e in edges) {
            if (e.a !in 0 until n || e.b !in 0 until n || e.a == e.b) continue
            k[e.a] += e.w
            k[e.b] += e.w
            m2 += 2 * e.w
            if (comm[e.a] == comm[e.b]) inside += 2 * e.w
        }
        if (m2 <= 0) return 0.0
        val tot = HashMap<Int, Double>()
        for (i in 0 until n) tot[comm[i]] = (tot[comm[i]] ?: 0.0) + k[i]
        return inside / m2 - tot.values.sumOf { (it / m2) * (it / m2) }
    }

    /** Shortest directed path (BFS) from any of [sources] to [target]; empty when unreachable. */
    fun path(n: Int, edges: List<IEdge>, sources: Collection<Int>, target: Int): List<Int> {
        if (target !in 0 until n) return emptyList()
        if (target in sources) return listOf(target)
        val out = Array(n) { ArrayList<Int>() }
        edges.forEach { if (it.a in 0 until n && it.b in 0 until n) out[it.a].add(it.b) }
        val prev = IntArray(n) { -2 }
        val q = ArrayDeque<Int>()
        sources.filter { it in 0 until n }.forEach { prev[it] = -1; q.add(it) }
        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            for (w in out[v]) if (prev[w] == -2) {
                prev[w] = v
                if (w == target) {
                    val p = ArrayList<Int>()
                    var c = w
                    while (c >= 0) { p.add(c); c = prev[c] }
                    return p.reversed()
                }
                q.add(w)
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------ topic leak and statistics

    class Leak(val rate: Double, val lo: Double, val hi: Double, val parents: Int, val judged: Int, val off: Int)

    /**
     * Leak per hop: of the judged recommendations shown next to on-topic videos, the share that is off topic.
     * [rel]: 1 on topic, 0 off topic, -1 unknown. 90% bootstrap interval over parents.
     */
    fun leak(n: Int, edges: List<IEdge>, rel: IntArray, iterations: Int = 200, seed: Long = 7L): Leak {
        val perParent = HashMap<Int, IntArray>() // parent -> [judged, off]
        for (e in edges) {
            if (e.a !in 0 until n || e.b !in 0 until n || rel[e.a] != 1 || rel[e.b] == -1) continue
            val c = perParent.getOrPut(e.a) { IntArray(2) }
            c[0]++
            if (rel[e.b] == 0) c[1]++
        }
        val list = perParent.values.toList()
        val judged = list.sumOf { it[0] }
        val off = list.sumOf { it[1] }
        if (judged == 0) return Leak(0.0, 0.0, 0.0, 0, 0, 0)
        val rate = off.toDouble() / judged
        val (lo, hi) = bootstrap(list.size, iterations, seed) { idx ->
            var j = 0
            var o = 0
            idx.forEach { j += list[it][0]; o += list[it][1] }
            if (j == 0) Double.NaN else o.toDouble() / j
        }
        return Leak(rate, lo, hi, list.size, judged, off)
    }

    /** Percentile bootstrap: [stat] gets resampled indices 0 until k; returns the 5th and 95th percentiles. */
    fun bootstrap(k: Int, iterations: Int = 200, seed: Long = 7L, stat: (IntArray) -> Double): Pair<Double, Double> {
        if (k == 0) return Double.NaN to Double.NaN
        val rnd = java.util.Random(seed)
        val vals = ArrayList<Double>(iterations)
        repeat(iterations) {
            val v = stat(IntArray(k) { rnd.nextInt(k) })
            if (!v.isNaN() && !v.isInfinite()) vals.add(v)
        }
        if (vals.isEmpty()) return Double.NaN to Double.NaN
        vals.sort()
        return quantile(vals, 0.05) to quantile(vals, 0.95)
    }

    /** Linear-interpolated quantile of a sorted list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val i = pos.toInt()
        val f = pos - i
        return if (i + 1 < sorted.size) sorted[i] * (1 - f) + sorted[i + 1] * f else sorted[i]
    }

    fun median(values: List<Double>): Double = quantile(values.filter { !it.isNaN() }.sorted(), 0.5)

    /** Rank-biased overlap (extrapolated, Webber et al. 2010) of two rankings; 1 = identical top. */
    fun rbo(a: List<String>, b: List<String>, p: Double = 0.9): Double {
        val k = min(a.size, b.size)
        if (k == 0) return if (a.isEmpty() && b.isEmpty()) 1.0 else 0.0
        val sa = HashSet<String>()
        val sb = HashSet<String>()
        var overlap = 0
        var sum = 0.0
        for (d in 1..k) {
            val x = a[d - 1]
            val y = b[d - 1]
            if (x == y) {
                overlap++
            } else {
                if (x in sb) overlap++
                if (y in sa) overlap++
            }
            sa.add(x)
            sb.add(y)
            sum += overlap.toDouble() / d * p.pow(d)
        }
        return (overlap.toDouble() / k * p.pow(k) + (1 - p) / p * sum).coerceIn(0.0, 1.0)
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** z-scores (0 when the spread is zero). */
    fun z(v: DoubleArray): DoubleArray {
        if (v.isEmpty()) return v
        val m = v.average()
        val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return if (sd < 1e-12) DoubleArray(v.size) else DoubleArray(v.size) { (v[it] - m) / sd }
    }

    /** Herfindahl–Hirschman index of counts (sum of squared shares). */
    fun hhi(counts: Collection<Int>): Double {
        val total = counts.sum().toDouble()
        return if (total <= 0) 0.0 else counts.sumOf { (it / total) * (it / total) }
    }
}

/**
 * Language-agnostic text similarity: TF-IDF over words and character 3-grams of words, cosine on L2-normalised
 * sparse vectors. Handles Russian morphology and mixed languages without a stemmer.
 */
class TextSim(docs: Collection<String>) {
    private val df = HashMap<String, Int>()
    private val n: Int = docs.size

    init {
        docs.forEach { d -> features(d).keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
    }

    fun idf(f: String): Double = ln((n + 1.0) / ((df[f] ?: 0) + 1.0)) + 1.0

    fun vector(text: String): Map<String, Double> {
        val tf = features(text)
        val v = HashMap<String, Double>(tf.size)
        tf.forEach { (f, c) -> v[f] = (1 + ln(c.toDouble())) * idf(f) }
        return unit(v)
    }

    companion object {
        fun words(text: String): List<String> = Words.tokens(text, 2)

        /** Word features ("w:слово") and 3-grams of " слово " with counts. */
        fun features(text: String): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (w in words(text)) {
                out["w:$w"] = (out["w:$w"] ?: 0) + 1
                val s = " $w "
                for (i in 0..s.length - 3) {
                    val g = s.substring(i, i + 3)
                    out[g] = (out[g] ?: 0) + 1
                }
            }
            return out
        }

        fun unit(v: Map<String, Double>): Map<String, Double> {
            val norm = sqrt(v.values.sumOf { it * it })
            return if (norm <= 0) emptyMap() else v.mapValues { it.value / norm }
        }

        fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
            val (s, l) = if (a.size <= b.size) a to b else b to a
            var dot = 0.0
            s.forEach { (k, x) -> l[k]?.let { dot += x * it } }
            return dot
        }

        /** Normalised sum of vectors. */
        fun centroid(vs: Collection<Map<String, Double>>): Map<String, Double> {
            val sum = HashMap<String, Double>()
            vs.forEach { v -> v.forEach { (k, x) -> sum[k] = (sum[k] ?: 0.0) + x } }
            return unit(sum)
        }

        fun minus(sum: Map<String, Double>, v: Map<String, Double>): Map<String, Double> {
            val out = HashMap(sum)
            v.forEach { (k, x) -> out[k] = (out[k] ?: 0.0) - x }
            return unit(out)
        }

        /**
         * Words typical of [group] against [all] (both lists of texts): smoothed lift of document frequency,
         * only words met in at least [minDf] group texts.
         */
        fun liftTerms(group: List<String>, all: List<String>, k: Int = 8, minDf: Int = 2): List<Pair<String, Double>> {
            if (group.isEmpty()) return emptyList()
            fun dfOf(texts: List<String>): Map<String, Int> {
                val m = HashMap<String, Int>()
                texts.forEach { t -> words(t).filter { it.length >= 3 }.toSet().forEach { m[it] = (m[it] ?: 0) + 1 } }
                return m
            }
            val g = dfOf(group)
            val a = dfOf(all)
            val gn = group.size.toDouble()
            val an = all.size.toDouble()
            return g.entries.filter { it.value >= min(minDf, group.size) }
                .map { (w, c) -> w to (c / gn) / (((a[w] ?: c) + 1.0) / (an + 1.0)) * ln(1.0 + c) }
                .sortedByDescending { it.second }.take(k)
        }
    }
}

/** L2-regularised logistic regression on standardised features, with AUC, group cross-validation and calibration. */
class Logit(val w: DoubleArray, val b: Double, val mean: DoubleArray, val sd: DoubleArray) {
    fun standard(x: DoubleArray) = DoubleArray(w.size) { (x[it] - mean[it]) / sd[it] }

    fun logit(x: DoubleArray): Double {
        val s = standard(x)
        var z = b
        for (i in w.indices) z += w[i] * s[i]
        return z
    }

    fun predict(x: DoubleArray): Double = sigmoid(logit(x))

    /** Per-feature contribution to the logit relative to an average video. */
    fun contributions(x: DoubleArray): DoubleArray {
        val s = standard(x)
        return DoubleArray(w.size) { w[it] * s[it] }
    }

    companion object {
        fun sigmoid(z: Double) = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }

        fun fit(x: List<DoubleArray>, y: IntArray, l2: Double = 1.0, iterations: Int = 400, lr: Double = 0.3): Logit {
            val d = x.firstOrNull()?.size ?: 0
            val n = x.size
            val mean = DoubleArray(d) { j -> if (n == 0) 0.0 else x.sumOf { it[j] } / n }
            val sd = DoubleArray(d) { j ->
                val v = if (n == 0) 0.0 else x.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / n
                if (v < 1e-12) 1.0 else sqrt(v)
            }
            val xs = x.map { r -> DoubleArray(d) { (r[it] - mean[it]) / sd[it] } }
            val w = DoubleArray(d)
            val pos = y.count { it == 1 }
            var b = if (pos == 0 || pos == n) 0.0 else ln(pos.toDouble() / (n - pos))
            if (n == 0) return Logit(w, b, mean, sd)
            repeat(iterations) {
                val gw = DoubleArray(d)
                var gb = 0.0
                for (i in 0 until n) {
                    var z = b
                    for (j in 0 until d) z += w[j] * xs[i][j]
                    val err = sigmoid(z) - y[i]
                    gb += err
                    for (j in 0 until d) gw[j] += err * xs[i][j]
                }
                for (j in 0 until d) w[j] -= lr * (gw[j] / n + l2 * w[j] / n)
                b -= lr * gb / n
            }
            return Logit(w, b, mean, sd)
        }

        /** Area under the ROC curve (Mann–Whitney, ties count half); NaN without both classes. */
        fun auc(scores: DoubleArray, y: IntArray): Double {
            val idx = scores.indices.filter { !scores[it].isNaN() }
            val pos = idx.count { y[it] == 1 }
            val neg = idx.size - pos
            if (pos == 0 || neg == 0) return Double.NaN
            val sorted = idx.sortedBy { scores[it] }
            val ranks = DoubleArray(scores.size)
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && scores[sorted[j + 1]] == scores[sorted[i]]) j++
                val r = (i + j) / 2.0 + 1
                for (t in i..j) ranks[sorted[t]] = r
                i = j + 1
            }
            val sumPos = idx.filter { y[it] == 1 }.sumOf { ranks[it] }
            return (sumPos - pos * (pos + 1) / 2.0) / (pos.toDouble() * neg)
        }

        /**
         * Out-of-fold predictions with folds formed by whole [groups] (e.g. channels), so a channel never sits
         * in train and test at once. NaN for rows whose fold could not be trained.
         */
        fun groupCv(x: List<DoubleArray>, y: IntArray, groups: List<String>, folds: Int = 5, seed: Long = 7L, l2: Double = 1.0): DoubleArray {
            val keys = groups.distinct().shuffled(java.util.Random(seed))
            val foldOf = HashMap<String, Int>()
            keys.forEachIndexed { i, k -> foldOf[k] = i % max(1, folds) }
            val out = DoubleArray(x.size) { Double.NaN }
            for (f in 0 until folds) {
                val train = x.indices.filter { foldOf[groups[it]] != f }
                val test = x.indices.filter { foldOf[groups[it]] == f }
                if (test.isEmpty()) continue
                val ty = IntArray(train.size) { y[train[it]] }
                if (ty.none { it == 1 } || ty.all { it == 1 }) continue
                val m = fit(train.map { x[it] }, ty, l2)
                test.forEach { out[it] = m.predict(x[it]) }
            }
            return out
        }

        class Bin(val predicted: Double, val observed: Double, val count: Int)

        /** Reliability bins by predicted probability (equal width). */
        fun calibration(p: DoubleArray, y: IntArray, bins: Int = 5): List<Bin> = (0 until bins).mapNotNull { b ->
            val lo = b.toDouble() / bins
            val hi = (b + 1).toDouble() / bins
            val idx = p.indices.filter { !p[it].isNaN() && p[it] >= lo && (p[it] < hi || (b == bins - 1 && p[it] <= hi)) }
            if (idx.isEmpty()) null else Bin(idx.sumOf { p[it] } / idx.size, idx.count { y[it] == 1 }.toDouble() / idx.size, idx.size)
        }

        /** Mean absolute gap between predicted and observed rates, weighted by bin size. */
        fun calibrationError(bins: List<Bin>): Double {
            val total = bins.sumOf { it.count }
            return if (total == 0) Double.NaN else bins.sumOf { abs(it.predicted - it.observed) * it.count } / total
        }
    }
}

/**
 * Text relevance to a topic, 0..1, without AI: cosine of a title to the topic profile (seed titles, keywords,
 * subtopics) minus half its cosine to the anti-profile (things that look alike but are not the topic), scaled by
 * how similar the seeds are to the rest of the profile (leave-one-out), so 1 ≈ "as typical as a seed".
 */
class TopicScorer(corpus: List<String>, profile: List<String>, anti: List<String>, seeds: List<String>) {
    private val ts = TextSim(corpus + profile + anti + seeds)
    private val seedVecs = seeds.filter { it.isNotBlank() }.map(ts::vector)
    private val sum: Map<String, Double>
    private val centre: Map<String, Double>
    private val antiCentre: Map<String, Double> = TextSim.centroid(anti.filter { it.isNotBlank() }.map(ts::vector))
    private val ref: Double

    init {
        val s = HashMap<String, Double>()
        (seedVecs + profile.filter { it.isNotBlank() }.map(ts::vector)).forEach { v -> v.forEach { (k, x) -> s[k] = (s[k] ?: 0.0) + x } }
        sum = s
        centre = TextSim.unit(s)
        val loo = seedVecs.map { v -> TextSim.cosine(TextSim.minus(sum, v), v) - 0.5 * TextSim.cosine(antiCentre, v) }
        val m = if (loo.size >= 3) AlgoCore.median(loo) else Double.NaN
        ref = if (m.isNaN() || m < 0.05) 0.3 else m
    }

    val empty get() = centre.isEmpty()

    fun raw(text: String): Double {
        val v = ts.vector(text)
        return TextSim.cosine(centre, v) - 0.5 * TextSim.cosine(antiCentre, v)
    }

    fun score(text: String): Double = if (empty) 0.5 else (raw(text) / ref).coerceIn(0.0, 1.0)
}

/** Thresholds and quality of the topic filter against the user's own ✓/✗ labels (pure). */
object AlgoQuality {
    /** Cut on the score that maximises F1 for "on topic"; null with fewer than 12 labels or 3 of a class. */
    fun bestCut(scores: List<Double>, labels: List<Int>): Double? {
        val pairs = scores.indices.filter { !scores[it].isNaN() && scores[it] >= 0 }.map { scores[it] to labels[it] }
        if (pairs.size < 12 || pairs.count { it.second == 1 } < 3 || pairs.count { it.second == 0 } < 3) return null
        var best = Double.NaN
        var bestF = -1.0
        for (c in pairs.map { it.first }.distinct().sorted()) {
            val tp = pairs.count { it.first >= c && it.second == 1 }
            val fp = pairs.count { it.first >= c && it.second == 0 }
            val fn = pairs.count { it.first < c && it.second == 1 }
            val f = if (tp == 0) 0.0 else 2.0 * tp / (2.0 * tp + fp + fn)
            if (f > bestF + 1e-12) {
                bestF = f
                best = c
            }
        }
        return if (best.isNaN()) null else best
    }

    class Prf(val n: Int, val precision: Double, val recall: Double) {
        val f1 get() = if (precision + recall <= 0) 0.0 else 2 * precision * recall / (precision + recall)
    }

    /** Precision and recall of predictions (1/0, null = no prediction) against labels; only rows with a prediction count. */
    fun prf(pred: List<Int?>, truth: List<Int>): Prf {
        val idx = pred.indices.filter { pred[it] != null }
        val tp = idx.count { pred[it] == 1 && truth[it] == 1 }
        val fp = idx.count { pred[it] == 1 && truth[it] == 0 }
        val fn = idx.count { pred[it] == 0 && truth[it] == 1 }
        return Prf(idx.size, if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp), if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn))
    }
}

/** Packaging features of a title (pure): length, number, question, CAPS share, emoji, brackets, Cyrillic. */
object TitleFeatures {
    val NAMES = listOf("title_len", "number", "question", "caps", "emoji", "brackets", "cyrillic")

    fun of(title: String): DoubleArray {
        val letters = title.filter { it.isLetter() }
        val caps = if (letters.isEmpty()) 0.0 else letters.count { it.isUpperCase() }.toDouble() / letters.length
        val emoji = title.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() || it in 0x1F300..0x1FAFF }
        return doubleArrayOf(
            title.length.toDouble(),
            if (title.any { it.isDigit() }) 1.0 else 0.0,
            if (title.contains('?')) 1.0 else 0.0,
            caps,
            if (emoji) 1.0 else 0.0,
            if (title.any { it in "([«\"" }) 1.0 else 0.0,
            if (title.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }) 1.0 else 0.0
        )
    }

    /** Words of [vocab] that occur in [title] (same word or the same stem: all but the last two letters, at least 4). */
    fun covered(title: String, vocab: List<String>): List<String> {
        val words = TextSim.words(title)
        fun same(w: String, v: String): Boolean {
            if (w == v) return true
            if (w.length < 5 || v.length < 5) return false
            val k = maxOf(4, minOf(w.length, v.length) - 2)
            return w.take(k) == v.take(k)
        }
        return vocab.filter { v -> words.any { w -> same(w, v) } }
    }
}

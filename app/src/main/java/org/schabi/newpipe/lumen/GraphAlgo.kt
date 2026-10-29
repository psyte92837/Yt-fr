/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure graph algorithms (no Android dependencies, covered by unit tests). */
object GraphAlgo {
    /**
     * PageRank over a directed graph given as edges (from index -> to index).
     * Dangling nodes distribute their rank uniformly. Result sums to 1.
     */
    fun pageRank(n: Int, edges: List<Pair<Int, Int>>, damping: Double = 0.85, iterations: Int = 40): DoubleArray {
        if (n == 0) return DoubleArray(0)
        val out = IntArray(n)
        edges.forEach { out[it.first]++ }
        var pr = DoubleArray(n) { 1.0 / n }
        repeat(iterations) {
            val next = DoubleArray(n) { (1 - damping) / n }
            var dangling = 0.0
            for (i in 0 until n) if (out[i] == 0) dangling += pr[i]
            val share = damping * dangling / n
            for (i in 0 until n) next[i] += share
            for ((a, b) in edges) next[b] += damping * pr[a] / out[a]
            pr = next
        }
        return pr
    }

    /**
     * Fruchterman–Reingold force-directed layout in the unit square.
     * Returns x and y arrays in [0, 1]. Deterministic for a given [seed].
     */
    fun layout(n: Int, edges: List<Pair<Int, Int>>, iterations: Int = if (n > 600) 120 else 260, seed: Long = 7L): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        if (n == 0) return x to y
        val rnd = java.util.Random(seed)
        for (i in 0 until n) {
            x[i] = rnd.nextDouble()
            y[i] = rnd.nextDouble()
        }
        if (n == 1) {
            x[0] = 0.5
            y[0] = 0.5
            return x to y
        }
        val k = sqrt(1.0 / n)
        val k2 = k * k
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        var temp = 0.1
        val cool = temp / (iterations + 1)
        repeat(iterations) {
            java.util.Arrays.fill(dx, 0.0)
            java.util.Arrays.fill(dy, 0.0)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ddx = x[i] - x[j]
                    var ddy = y[i] - y[j]
                    var d2 = ddx * ddx + ddy * ddy
                    if (d2 < 1e-9) {
                        ddx = (rnd.nextDouble() - 0.5) * 1e-3
                        ddy = (rnd.nextDouble() - 0.5) * 1e-3
                        d2 = ddx * ddx + ddy * ddy
                    }
                    val f = k2 / d2 // (k^2 / d) / d -> unit vector scaling
                    dx[i] += ddx * f
                    dy[i] += ddy * f
                    dx[j] -= ddx * f
                    dy[j] -= ddy * f
                }
            }
            for ((a, b) in edges) {
                if (a == b) continue
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val d = sqrt(ddx * ddx + ddy * ddy) + 1e-9
                val f = d / k // (d^2 / k) / d
                dx[a] -= ddx * f
                dy[a] -= ddy * f
                dx[b] += ddx * f
                dy[b] += ddy * f
            }
            for (i in 0 until n) {
                // weak gravity keeps disconnected components on screen
                dx[i] += (0.5 - x[i]) * 0.05
                dy[i] += (0.5 - y[i]) * 0.05
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i]) + 1e-9
                val step = min(len, temp)
                x[i] += dx[i] / len * step
                y[i] += dy[i] / len * step
            }
            temp = max(0.002, temp - cool)
        }
        // normalise to [0, 1]
        val minX = x.min()
        val maxX = x.max()
        val minY = y.min()
        val maxY = y.max()
        val w = max(1e-9, maxX - minX)
        val h = max(1e-9, maxY - minY)
        for (i in 0 until n) {
            x[i] = (x[i] - minX) / w
            y[i] = (y[i] - minY) / h
        }
        return x to y
    }
}

/** Keyword relevance with a crude prefix stemmer that works for Russian and English. */
object Relevance {
    fun stem(w: String): String = if (w.length <= 4) w else w.take(max(4, (w.length * 0.7).toInt()))

    fun keywords(text: String): Set<String> = Words.tokens(text, 3).map { stem(it) }.toSet()

    fun matches(keywords: Set<String>, text: String): Boolean {
        if (keywords.isEmpty()) return true
        return Words.tokens(text, 3).any { tok -> keywords.any { tok.startsWith(it) } }
    }
}

/** Plural forms for report sentences (pure). */
object Plural {
    fun ru(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1 -> one
            b in 2..4 -> few
            else -> many
        }
    }

    fun en(n: Int, one: String, many: String) = if (n == 1) one else many
}

/**
 * Graph statistics and a plain-language story for the graph report (pure, covered by unit tests).
 * Works on the graph exactly as it is shown: [nodes] and index [edges] (from -> to).
 */
object GraphStats {
    class Summary(
        val n: Int,
        val m: Int,
        val seeds: Int,
        val onTopic: Int,
        val offTopic: Int,
        val unknown: Int,
        val maxDepth: Int,
        /** nodes per depth (index = depth) */
        val depthCounts: IntArray,
        /** on-topic and judged (on or off) nodes per depth */
        val depthOn: IntArray,
        val depthJudged: IntArray,
        val inDeg: IntArray,
        val outDeg: IntArray,
        val components: Int,
        val largest: Int,
        val isolated: Int,
        /** pairs that recommend each other, a < b */
        val mutual: List<Pair<Int, Int>>,
        /** nodes reachable from the seeds along the arrows (seeds included) */
        val reach: Int,
        val topPr: List<Int>,
        val topHits: List<Int>,
        val topHubs: List<Int>,
        /** share of PageRank held by the strongest 10% of nodes, 0..1 */
        val prShareTop10: Double,
        /** most frequent channels among the nodes (video graph) */
        val groups: List<Pair<String, Int>>
    ) {
        val density get() = if (n < 2) 0.0 else m.toDouble() / (n.toDouble() * (n - 1))
        val reciprocity get() = if (m == 0) 0.0 else mutual.size * 2.0 / m
        val judged get() = onTopic + offTopic
    }

    fun of(nodes: List<RecNode>, edges: List<Pair<Int, Int>>, top: Int = 10): Summary {
        val n = nodes.size
        val inDeg = IntArray(n)
        val outDeg = IntArray(n)
        val clean = edges.filter { (a, b) -> a != b && a in 0 until n && b in 0 until n }.distinct()
        clean.forEach { (a, b) -> outDeg[a]++; inDeg[b]++ }
        val maxDepth = nodes.maxOfOrNull { it.depth } ?: 0
        val dc = IntArray(maxDepth + 1)
        val don = IntArray(maxDepth + 1)
        val dj = IntArray(maxDepth + 1)
        nodes.forEach {
            val d = it.depth.coerceIn(0, maxDepth)
            dc[d]++
            if (it.relevant == 1) don[d]++
            if (it.relevant != -1) dj[d]++
        }
        // undirected components (union-find)
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        clean.forEach { (a, b) -> val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        val sizes = HashMap<Int, Int>()
        for (i in 0 until n) { val r = find(i); sizes[r] = (sizes[r] ?: 0) + 1 }
        val keys = HashSet<Long>()
        clean.forEach { (a, b) -> keys.add(a.toLong() * n + b) }
        val mutual = clean.filter { (a, b) -> a < b && keys.contains(b.toLong() * n + a) }
        // reach from seeds
        val adj = Array(n) { ArrayList<Int>() }
        clean.forEach { (a, b) -> adj[a].add(b) }
        val seedIdx = nodes.indices.filter { nodes[it].seed }
        val reach = if (seedIdx.isEmpty()) n else {
            val seen = BooleanArray(n)
            val q = ArrayDeque<Int>()
            seedIdx.forEach { seen[it] = true; q.add(it) }
            while (q.isNotEmpty()) for (b in adj[q.removeFirst()]) if (!seen[b]) { seen[b] = true; q.add(b) }
            seen.count { it }
        }
        val prSorted = nodes.map { it.pr }.sortedDescending()
        val prTotal = prSorted.sum()
        val share = if (prTotal <= 0) 0.0 else prSorted.take(maxOf(1, n / 10)).sum() / prTotal
        val notSeed = nodes.indices.filter { !nodes[it].seed }
        val groups = nodes.filter { it.channel.isNotBlank() }.groupingBy { it.channel }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        return Summary(
            n = n, m = clean.size, seeds = seedIdx.size,
            onTopic = nodes.count { it.relevant == 1 }, offTopic = nodes.count { it.relevant == 0 }, unknown = nodes.count { it.relevant == -1 },
            maxDepth = maxDepth, depthCounts = dc, depthOn = don, depthJudged = dj, inDeg = inDeg, outDeg = outDeg,
            components = sizes.size, largest = sizes.values.maxOrNull() ?: 0, isolated = (0 until n).count { inDeg[it] + outDeg[it] == 0 },
            mutual = mutual.sortedByDescending { (a, b) -> nodes[a].pr + nodes[b].pr }, reach = reach,
            topPr = notSeed.sortedByDescending { nodes[it].pr }.take(top),
            topHits = notSeed.filter { inDeg[it] > 0 }.sortedWith(compareByDescending<Int> { inDeg[it] }.thenByDescending { nodes[it].pr }).take(top),
            topHubs = nodes.indices.filter { outDeg[it] > 0 }.sortedWith(compareByDescending<Int> { outDeg[it] }.thenByDescending { nodes[it].pr }).take(top),
            prShareTop10 = share, groups = groups
        )
    }

    fun pct(a: Int, b: Int): Int = if (b <= 0) 0 else Math.round(100.0 * a / b).toInt()

    fun name(n: RecNode, max: Int = 48): String = n.title.ifBlank { n.channel.ifBlank { n.id } }.let { if (it.length > max) it.take(max - 1) + "…" else it }

    /** Plain-language findings: (icon, text). [channels] = channel graph (node = channel), else video graph. */
    fun story(s: Summary, nodes: List<RecNode>, channels: Boolean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (s.n == 0) return out
        val unitRu = if (channels) Plural.ru(s.n, "канала", "каналов", "каналов") else "видео"
        val unitEn = if (channels) Plural.en(s.n, "channel", "channels") else Plural.en(s.n, "video", "videos")
        out += "🧭" to t(
            "Граф вырос от ${s.seeds} ${Plural.ru(s.seeds, "стартового узла", "стартовых узлов", "стартовых узлов")} до ${s.n} $unitRu и ${s.m} " +
                "${Plural.ru(s.m, "связи", "связей", "связей")} за ${s.maxDepth} ${Plural.ru(s.maxDepth, "шаг", "шага", "шагов")} обхода рекомендаций.",
            "Starting from ${s.seeds} ${Plural.en(s.seeds, "seed", "seeds")}, the graph grew to ${s.n} $unitEn and ${s.m} " +
                "${Plural.en(s.m, "link", "links")} over ${s.maxDepth} ${Plural.en(s.maxDepth, "step", "steps")} of recommendations."
        )
        if (s.judged > 0) {
            var line = t("По теме — ${s.onTopic} из ${s.judged} проверенных (${pct(s.onTopic, s.judged)}%).",
                "${s.onTopic} of ${s.judged} checked nodes are on topic (${pct(s.onTopic, s.judged)}%).")
            val first = (1..s.maxDepth).firstOrNull { s.depthJudged[it] > 0 }
            val last = (s.maxDepth downTo 1).firstOrNull { s.depthJudged[it] > 0 }
            if (first != null && last != null && last > first) {
                val a = pct(s.depthOn[first], s.depthJudged[first])
                val b = pct(s.depthOn[last], s.depthJudged[last])
                line += " " + if (a - b >= 15) {
                    t("Чем дальше от старта, тем меньше по теме: $a% на $first-м шаге против $b% на $last-м — рекомендации уводят в сторону.",
                        "The further from the seeds, the less on topic: $a% at step $first vs $b% at step $last — recommendations drift away.")
                } else {
                    t("Доля по теме держится на всех шагах ($a% → $b%) — рекомендации не уводят от темы.",
                        "The on-topic share holds across steps ($a% → $b%) — recommendations stay on topic.")
                }
            }
            out += "🎯" to line
        }
        s.topPr.firstOrNull()?.let { i ->
            var line = t("Центр графа — «${name(nodes[i])}»: на него ведут ${s.inDeg[i]} ${Plural.ru(s.inDeg[i], "связь", "связи", "связей")}, у него самый высокий PageRank. Сюда YouTube приводит зрителя чаще всего.",
                "The centre of the graph is “${name(nodes[i])}”: ${s.inDeg[i]} ${Plural.en(s.inDeg[i], "link points", "links point")} to it and it has the highest PageRank. This is where YouTube sends viewers most.")
            val h = s.topHits.firstOrNull()
            if (h != null && h != i && s.inDeg[h] > s.inDeg[i]) {
                line += " " + t("Больше всего прямых рекомендаций у «${name(nodes[h])}» (${s.inDeg[h]}).", "The most direct recommendations go to “${name(nodes[h])}” (${s.inDeg[h]}).")
            }
            out += "⭐" to line
        }
        s.topHubs.firstOrNull()?.let { i ->
            out += "📡" to t("Главный «раздатчик» — «${name(nodes[i])}»: от него расходится ${s.outDeg[i]} ${Plural.ru(s.outDeg[i], "связь", "связи", "связей")} к другим узлам.",
                "The main “hub” is “${name(nodes[i])}”: ${s.outDeg[i]} ${Plural.en(s.outDeg[i], "link leads", "links lead")} from it to other nodes.")
        }
        if (s.n >= 10 && s.prShareTop10 > 0) {
            val k = maxOf(1, s.n / 10)
            val p = Math.round(s.prShareTop10 * 100).toInt()
            out += "⚖️" to if (p >= 40) {
                t("Внимание сосредоточено: $k самых сильных узлов (10%) собирают $p% PageRank. Попасть в рекомендации рядом с ними — самый короткий путь к аудитории.",
                    "Attention is concentrated: the strongest $k nodes (10%) hold $p% of PageRank. Getting recommended next to them is the shortest way to the audience.")
            } else {
                t("Внимание распределено ровно: 10% самых сильных узлов собирают лишь $p% PageRank — явного лидера нет, ниша открыта.",
                    "Attention is spread evenly: the strongest 10% of nodes hold only $p% of PageRank — no clear leader, the niche is open.")
            }
        }
        out += "🧩" to if (s.components <= 1) {
            t("Все узлы связаны в одну сеть — это цельная ниша.", "All nodes form one network — a coherent niche.")
        } else {
            t("Граф распадается на ${s.components} ${Plural.ru(s.components, "группу", "группы", "групп")}; крупнейшая — ${s.largest} ${Plural.ru(s.largest, "узел", "узла", "узлов")} (${pct(s.largest, s.n)}%). Отдельные группы — соседние, слабо связанные ниши.",
                "The graph splits into ${s.components} groups; the largest has ${s.largest} ${Plural.en(s.largest, "node", "nodes")} (${pct(s.largest, s.n)}%). Separate groups are neighbouring, loosely linked niches.") +
                if (s.isolated > 0) t(" Без связей: ${s.isolated}.", " Without links: ${s.isolated}.") else ""
        }
        out += "🔁" to if (s.mutual.isNotEmpty()) {
            val (a, b) = s.mutual.first()
            t("Взаимных пар: ${s.mutual.size} (${Math.round(s.reciprocity * 100)}% связей) — эти узлы рекомендуют друг друга и образуют устойчивое ядро. Например: «${name(nodes[a], 32)}» ⇄ «${name(nodes[b], 32)}».",
                "Mutual pairs: ${s.mutual.size} (${Math.round(s.reciprocity * 100)}% of links) — these nodes recommend each other and form a stable core. For example: “${name(nodes[a], 32)}” ⇄ “${name(nodes[b], 32)}”.")
        } else {
            t("Взаимных рекомендаций нет — поток идёт в одну сторону, от старта вглубь.", "No mutual recommendations — the flow goes one way, from the seeds outwards.")
        }
        if (s.maxDepth >= 1) {
            val peak = (1..s.maxDepth).maxByOrNull { s.depthCounts[it] } ?: 1
            if (s.depthCounts[peak] > 0) {
                out += "📶" to t("Больше всего новых узлов появилось на $peak-м шаге: ${s.depthCounts[peak]}.", "Most new nodes appeared at step $peak: ${s.depthCounts[peak]}.")
            }
        }
        if (channels) {
            nodes.indices.filter { nodes[it].views > 0 }.maxByOrNull { nodes[it].views }?.let { i ->
                out += "👥" to t("Крупнейший по подписчикам — «${name(nodes[i])}» (${Fmt.num(nodes[i].views)}).", "The largest by subscribers is “${name(nodes[i])}” (${Fmt.num(nodes[i].views)}).")
            }
        } else {
            s.groups.firstOrNull()?.takeIf { it.second > 1 }?.let { (ch, k) ->
                out += "📺" to t("Чаще всего в графе встречается канал «$ch»: $k видео (${pct(k, s.n)}%).", "The most frequent channel is “$ch”: $k ${Plural.en(k, "video", "videos")} (${pct(k, s.n)}%).")
            }
        }
        if (s.seeds > 0 && s.reach < s.n) {
            out += "🛤" to t("Из стартовых узлов по стрелкам достижимо ${s.reach} из ${s.n} (${pct(s.reach, s.n)}%); остальные связаны только обратными ссылками.",
                "Following the arrows from the seeds reaches ${s.reach} of ${s.n} (${pct(s.reach, s.n)}%); the rest are linked only backwards.")
        }
        return out
    }
}

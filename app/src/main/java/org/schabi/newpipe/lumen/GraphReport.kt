/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.schabi.newpipe.lumen.ReportKit.ACCENT
import org.schabi.newpipe.lumen.ReportKit.CARD
import org.schabi.newpipe.lumen.ReportKit.INNER
import org.schabi.newpipe.lumen.ReportKit.MUTED
import org.schabi.newpipe.lumen.ReportKit.ON
import org.schabi.newpipe.lumen.ReportKit.OUTLINE
import org.schabi.newpipe.lumen.ReportKit.PAD
import org.schabi.newpipe.lumen.ReportKit.W
import org.schabi.newpipe.lumen.ReportKit.drawAt
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.sqrt

/** Mobile report about a recommendation / channel graph: picture, numbers, plain-language findings, charts, key nodes. */
object GraphReport {
    class Input(
        val project: String,
        val phrase: String,
        val channels: Boolean,
        val nodes: List<RecNode>,
        val edges: List<Pair<Int, Int>>,
        val ranks: IntArray,
        val x: DoubleArray,
        val y: DoubleArray,
        val colorBy: String,
        val onlyOnTopic: Boolean,
        val updatedAt: Long,
        val requests: Int
    )

    /** The graph as shown on screen: optionally only on-topic nodes (seeds always stay). */
    fun subgraph(g: RecGraph, onlyOnTopic: Boolean): Triple<List<RecNode>, List<Pair<Int, Int>>, IntArray> {
        val all = g.nodes.values.toList()
        val list = if (onlyOnTopic) all.filter { it.relevant != 0 || it.seed } else all
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, n -> index[n.id] = i }
        val e = ArrayList<Pair<Int, Int>>()
        val r = ArrayList<Int>()
        for (ed in g.edges) {
            val a = index[ed.from] ?: continue
            val b = index[ed.to] ?: continue
            e += a to b
            r += ed.rank
        }
        return Triple(list, e, r.toIntArray())
    }

    fun input(pid: String, file: String, onlyOnTopic: Boolean = true, colorBy: String = "depth", layout: Pair<DoubleArray, DoubleArray>? = null): Input {
        val g = RecGraph.load(pid, file)
        val (list, e, r) = subgraph(g, onlyOnTopic)
        val (x, y) = layout?.takeIf { it.first.size == list.size } ?: GraphAlgo.layout(list.size, e)
        val p = Store.project(pid)
        return Input(p?.name ?: "Lumen", p?.phrase ?: "", file == RecGraph.CHANNELS_FILE, list, e, r, x, y, colorBy, onlyOnTopic, g.updatedAt, g.requests)
    }

    fun write(inp: Input, pdf: Boolean, out: OutputStream) =
        ReportKit.write(blocks(inp), pdf, out, "Lumen · " + (if (inp.channels) t("граф каналов", "channel graph") else t("граф рекомендаций", "recommendation graph")) + " · " + inp.project)

    /** The drawing of the graph with labels of the strongest nodes and a legend. */
    private class Picture(private val inp: Input, private val s: GraphStats.Summary) : ReportKit.Block(keepWithNext = false) {
        private val size = INNER
        private val colorsLegend = GraphColors.of(inp.nodes, inp.colorBy, ReportKit.OK, ReportKit.OUTLINE_SOFT, ReportKit.WARN)
        private val legendP = ReportKit.paint(11f, MUTED)
        private val legendRows: List<List<Pair<Int, String>>> = run {
            val rows = ArrayList<MutableList<Pair<Int, String>>>()
            var cur = mutableListOf<Pair<Int, String>>()
            var w = 0f
            val title = when (inp.colorBy) {
                "relevance" -> t("Цвет — тема:", "Colour — topic:")
                "channel" -> t("Цвет — канал:", "Colour — channel:")
                else -> t("Цвет — шаг от старта:", "Colour — step from seed:")
            }
            cur += 0 to title
            w += legendP.measureText(title) + 10
            for (it in colorsLegend.second) {
                val iw = 14 + legendP.measureText(it.second) + 12
                if (w + iw > INNER - 24 && cur.isNotEmpty()) {
                    rows += cur
                    cur = mutableListOf()
                    w = 0f
                }
                cur += it
                w += iw
            }
            if (cur.isNotEmpty()) rows += cur
            rows
        }
        private val caption = ReportKit.layout(
            t("Круг — ${if (inp.channels) "канал" else "видео"}, размер — сколько узлов его рекомендуют. Линия — рекомендация. Кольцо — стартовый узел. Фиолетовые линии — взаимные связи.",
                "Circle — a ${if (inp.channels) "channel" else "video"}, size — how many nodes recommend it. Line — a recommendation. Ring — a seed. Purple lines — mutual links."),
            ReportKit.paint(10.5f, MUTED), INNER - 24
        )
        override val h = size + 10 + legendRows.size * 18f + 6 + caption.height + 14 + 8

        override fun draw(c: Canvas) {
            ReportKit.card(c, PAD, 0f, W - PAD, h - 8, CARD, 20f)
            val n = inp.nodes.size
            if (n == 0) return
            val m = 22f
            val l = PAD + m
            val top = m
            val span = size - 2 * m
            fun px(i: Int) = (l + inp.x[i] * span).toFloat()
            fun py(i: Int) = (top + inp.y[i] * span).toFloat()
            val mutual = HashSet<Long>()
            s.mutual.forEach { (a, b) -> mutual.add(a.toLong() * n + b); mutual.add(b.toLong() * n + a) }
            val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (ON and 0x00FFFFFF) or (if (inp.edges.size > 800) 0x14000000 else 0x26000000)
                strokeWidth = if (n > 250) 0.35f else 0.55f
            }
            val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (ACCENT and 0x00FFFFFF) or 0x99000000.toInt(); strokeWidth = 1.1f }
            for ((a, b) in inp.edges) {
                if (a == b) continue
                c.drawLine(px(a), py(a), px(b), py(b), if (mutual.contains(a.toLong() * n + b)) hot else thin)
            }
            val maxIn = max(1, s.inDeg.maxOrNull() ?: 1)
            val (rMin, rMax) = if (n > 200) 1.4f to 5.5f else if (n > 60) 2.2f to 8f else 3.5f to 11f
            fun rad(i: Int) = rMin + (rMax - rMin) * sqrt(s.inDeg[i].toFloat() / maxIn)
            val order = inp.nodes.indices.sortedBy { inp.nodes[it].pr }
            val nodeP = Paint(Paint.ANTI_ALIAS_FLAG)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xCCFFFFFF.toInt() }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f; color = ON }
            for (i in order) {
                nodeP.color = colorsLegend.first[i]
                c.drawCircle(px(i), py(i), rad(i), nodeP)
                c.drawCircle(px(i), py(i), rad(i), rim)
                if (inp.nodes[i].seed) c.drawCircle(px(i), py(i), rad(i) + 2.2f, ring)
            }
            // labels: seeds + strongest nodes, skipping ones that would overlap
            val lp = ReportKit.paint(8.5f, ON, true)
            val bg = ReportKit.fill(0xE6FFFFFF.toInt())
            val placed = ArrayList<RectF>()
            val want = (inp.nodes.indices.filter { inp.nodes[it].seed }.take(4) + s.topPr.take(12)).distinct()
            for (i in want) {
                val label = GraphStats.name(inp.nodes[i], 24)
                val w = lp.measureText(label) + 8
                val r0 = rad(i) + 3
                val candidates = listOf(
                    RectF(px(i) + r0, py(i) - 7, px(i) + r0 + w, py(i) + 7),
                    RectF(px(i) - r0 - w, py(i) - 7, px(i) - r0, py(i) + 7),
                    RectF(px(i) - w / 2, py(i) - r0 - 15, px(i) + w / 2, py(i) - r0 - 1),
                    RectF(px(i) - w / 2, py(i) + r0 + 1, px(i) + w / 2, py(i) + r0 + 15)
                )
                val box = candidates.firstOrNull { rc ->
                    rc.left >= PAD + 4 && rc.right <= W - PAD - 4 && rc.top >= 4 && rc.bottom <= size - 4 &&
                        placed.none { RectF.intersects(it, rc) }
                } ?: continue
                placed += box
                c.drawRoundRect(box, 5f, 5f, bg)
                c.drawText(label, box.left + 4, box.bottom - 4, lp)
            }
            var y = size + 10 + 12
            for (row in legendRows) {
                var x = PAD + 12
                for ((col, txt) in row) {
                    if (col == 0) {
                        c.drawText(txt, x, y, ReportKit.paint(11f, ON, true))
                        x += ReportKit.paint(11f, ON, true).measureText(txt) + 10
                    } else {
                        c.drawCircle(x + 5, y - 4, 5f, ReportKit.fill(col))
                        c.drawText(txt, x + 14, y, legendP)
                        x += 14 + legendP.measureText(txt) + 12
                    }
                }
                y += 18
            }
            caption.drawAt(c, PAD + 12, y - 6)
        }
    }

    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    fun blocks(inp: Input): List<ReportKit.Block> {
        val s = GraphStats.of(inp.nodes, inp.edges)
        val nodes = inp.nodes
        val ch = inp.channels
        val out = ArrayList<ReportKit.Block>()
        val kind = if (ch) t("Граф каналов", "Channel graph") else t("Граф рекомендаций", "Recommendation graph")
        out += ReportKit.Hero(
            "Lumen · $kind", inp.project, inp.phrase,
            t("Обновлён: ", "Updated: ") + Fmt.dateTime(inp.updatedAt) + " · " + t("запросов: ", "requests: ") + inp.requests +
                (if (inp.onlyOnTopic) t(" · только по теме", " · on topic only") else "")
        )
        out += ReportKit.Kpis(
            listOf(
                Triple("${s.n}", if (ch) t("каналов", "channels") else t("видео", "videos"), ACCENT),
                Triple("${s.m}", t("связей", "links"), ReportKit.TERTIARY),
                Triple("${s.maxDepth}", t("шагов от старта", "steps from seed"), ReportKit.WARN),
                Triple(if (s.judged > 0) "${GraphStats.pct(s.onTopic, s.judged)}%" else "—", t("по теме", "on topic"), ReportKit.OK),
                Triple("${s.components}", t("групп (компонент)", "groups (components)"), OUTLINE),
                Triple("${s.mutual.size}", t("взаимных пар", "mutual pairs"), ACCENT)
            )
        )
        out += ReportKit.Section(t("Карта связей", "Map of links"), t("Чем ближе узлы, тем теснее они связаны рекомендациями.", "The closer the nodes, the tighter their recommendation links."))
        out += Picture(inp, s)
        val story = GraphStats.story(s, nodes, ch)
        if (story.isNotEmpty()) {
            out += ReportKit.Section(t("Что происходит", "What is going on"), t("Выводы посчитаны по структуре графа.", "Findings computed from the graph structure."))
            story.forEach { (icon, text) -> out += ReportKit.Insight(icon, text) }
        }
        if (s.depthCounts.size > 1) {
            out += ReportKit.Section(t("Рост по шагам", "Growth by step"), t("Сколько узлов найдено на каждом шаге обхода.", "How many nodes were found at each step."))
            val labels = s.depthCounts.indices.map { if (it == 0) t("старт", "seed") else "$it" }
            val hasTopic = s.judged > 0
            out += ReportKit.Bars(
                s.depthCounts.toList(), labels, if (hasTopic) s.depthOn.toList() else null,
                if (hasTopic) listOf(ACCENT to t("по теме", "on topic"), ReportKit.SOFT to t("остальные", "others")) else emptyList()
            )
        }
        if (s.judged > 0) {
            out += ReportKit.Section(t("Соответствие теме", "Topic match"))
            out += ReportKit.Split(
                listOf(
                    Triple(ReportKit.OK, t("по теме", "on topic"), s.onTopic),
                    Triple(ReportKit.OUTLINE_SOFT, t("не по теме", "off topic"), s.offTopic),
                    Triple(ReportKit.WARN, t("не проверено", "unknown"), s.unknown)
                ).filter { it.third > 0 }
            )
        }
        fun sub(i: Int): String {
            val n = nodes[i]
            return if (ch) {
                listOfNotNull(
                    if (n.views > 0) "👥 " + Fmt.num(n.views) else null,
                    t("шаг ", "step ") + n.depth,
                    n.uploadText.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
            } else {
                listOfNotNull(n.channel.takeIf { it.isNotBlank() }, if (n.views >= 0) "👁 " + Fmt.num(n.views) else null, t("шаг ", "step ") + n.depth).joinToString(" · ")
            }
        }
        if (s.topPr.isNotEmpty()) {
            out += ReportKit.Section(t("Центральные узлы", "Central nodes"), t("PageRank: куда сходится поток рекомендаций.", "PageRank: where the flow of recommendations converges."))
            out += ReportKit.Ranking(s.topPr.take(8).map { ReportKit.Ranking.Row(GraphStats.name(nodes[it], 60), sub(it), nodes[it].pr, f2(nodes[it].pr * 1000)) })
        }
        if (s.topHits.isNotEmpty()) {
            out += ReportKit.Section(t("Чаще всего рекомендуют", "Recommended most often"), t("Сколько узлов графа ведут сюда напрямую.", "How many nodes link here directly."))
            out += ReportKit.Ranking(s.topHits.take(8).map { ReportKit.Ranking.Row(GraphStats.name(nodes[it], 60), sub(it), s.inDeg[it].toDouble(), "×${s.inDeg[it]}") }, ReportKit.TERTIARY)
        }
        if (s.topHubs.isNotEmpty()) {
            out += ReportKit.Section(t("Раздатчики", "Hubs"), t("От кого расходится больше всего связей.", "Which nodes send the most links onward."))
            out += ReportKit.Ranking(s.topHubs.take(6).map { ReportKit.Ranking.Row(GraphStats.name(nodes[it], 60), sub(it), s.outDeg[it].toDouble(), "→ ${s.outDeg[it]}") }, ReportKit.WARN)
        }
        if (ch) {
            val big = nodes.indices.filter { nodes[it].views > 0 }.sortedByDescending { nodes[it].views }.take(8)
            if (big.size >= 2) {
                out += ReportKit.Section(t("Крупнейшие каналы", "Largest channels"), t("По числу подписчиков.", "By subscribers."))
                out += ReportKit.Ranking(big.map { ReportKit.Ranking.Row(GraphStats.name(nodes[it], 60), sub(it), nodes[it].views.toDouble(), Fmt.num(nodes[it].views)) }, ReportKit.OK)
            }
        } else if (s.groups.size >= 2) {
            out += ReportKit.Section(t("Каналы в графе", "Channels in the graph"), t("Чьи видео YouTube подставляет чаще.", "Whose videos YouTube suggests most."))
            out += ReportKit.Ranking(s.groups.take(8).map { (name, k) -> ReportKit.Ranking.Row(name, "${GraphStats.pct(k, s.n)}% " + t("узлов", "of nodes"), k.toDouble(), "$k") }, ReportKit.OK)
        }
        // key links: mutual pairs first, then first-place recommendations between strong nodes
        val links = ArrayList<Pair<String, String>>()
        s.mutual.take(5).forEach { (a, b) -> links += (GraphStats.name(nodes[a], 34) + "  ⇄  " + GraphStats.name(nodes[b], 34)) to t("взаимно", "mutual") }
        val strong = s.topPr.take(15).toHashSet()
        inp.edges.indices.filter { val (a, b) = inp.edges[it]; inp.ranks.getOrElse(it) { 9 } == 0 && (a in strong || b in strong) }
            .sortedByDescending { nodes[inp.edges[it].second].pr }.take(8 - links.size.coerceAtMost(5)).forEach {
                val (a, b) = inp.edges[it]
                links += (GraphStats.name(nodes[a], 34) + "  →  " + GraphStats.name(nodes[b], 34)) to t("1-е место", "1st place")
            }
        if (links.isNotEmpty()) {
            out += ReportKit.Section(t("Ключевые связи", "Key links"), t("⇄ — рекомендуют друг друга; → — первое место в рекомендациях.", "⇄ — recommend each other; → — first place in recommendations."))
            links.forEach { (line, tag) -> out += LinkRow(line, tag) }
        }
        // node details
        val detail = (nodes.indices.filter { nodes[it].seed } + s.topPr).distinct().take(12)
        if (detail.isNotEmpty()) {
            out += ReportKit.Section(t("Узлы подробно", "Nodes in detail"), t("Стартовые и самые сильные узлы.", "Seeds and the strongest nodes."))
            val inMap = HashMap<Int, MutableList<Int>>()
            val outMap = HashMap<Int, MutableList<Int>>()
            inp.edges.forEach { (a, b) -> inMap.getOrPut(b) { ArrayList() }.add(a); outMap.getOrPut(a) { ArrayList() }.add(b) }
            for (i in detail) {
                val n = nodes[i]
                val meta = listOfNotNull(
                    if (n.seed) t("старт", "seed") else t("шаг ", "step ") + n.depth,
                    t("рекомендуют ×", "recommended ×") + s.inDeg[i],
                    t("ведёт к ", "leads to ") + s.outDeg[i],
                    "PR " + f2(n.pr * 1000),
                    when (n.relevant) { 1 -> t("по теме", "on topic"); 0 -> t("не по теме", "off topic"); else -> null }
                ).joinToString(" · ")
                val from = inMap[i].orEmpty().sortedByDescending { nodes[it].pr }.take(3).joinToString("; ") { GraphStats.name(nodes[it], 30) }
                val to = outMap[i].orEmpty().sortedByDescending { nodes[it].pr }.take(3).joinToString("; ") { GraphStats.name(nodes[it], 30) }
                val body = buildString {
                    append(sub(i))
                    append('\n').append(meta)
                    if (from.isNotBlank()) append('\n').append(t("**Откуда:** ", "**From:** ")).append(from)
                    if (to.isNotBlank()) append('\n').append(t("**Ведёт к:** ", "**Leads to:** ")).append(to)
                }
                out += NodeCard(GraphStats.name(nodes[i], 90), body, ACCENT, n.seed)
            }
        }
        out += ReportKit.Section(t("Как читать отчёт", "How to read this report"))
        out += ReportKit.Paragraph(
            t(
                "**Шаг** — сколько переходов по рекомендациям от стартового узла.\n**PageRank** — вес узла: насколько часто до него доходит случайный зритель, листающий рекомендации (×1000).\n**Рекомендуют ×N** — сколько узлов графа ведут сюда.\n**Взаимная пара** — два узла рекомендуют друг друга.\n**Группа** — часть графа, не связанная с остальными.",
                "**Step** — how many recommendation hops from the seed.\n**PageRank** — node weight: how often a random viewer clicking recommendations reaches it (×1000).\n**Recommended ×N** — how many nodes link here.\n**Mutual pair** — two nodes recommend each other.\n**Group** — a part of the graph not linked to the rest."
            ), 12f, MUTED
        )
        out += ReportKit.Footer(
            t("Отчёт создан в Lumen ", "Report made with Lumen ") + Fmt.dateTime(System.currentTimeMillis()) +
                t(". Данные собраны без аккаунта и хранятся только на устройстве.", ". Data collected without an account and stored on the device only.")
        )
        return out
    }

    /** One key link. */
    private class LinkRow(line: String, private val tag: String) : ReportKit.Block() {
        private val tp = ReportKit.paint(12.5f, ON)
        private val gp = ReportKit.paint(10.5f, ACCENT, true)
        private val tw = gp.measureText(tag) + 16
        private val tl = ReportKit.layout(line, tp, INNER - 28 - tw - 8, 1.2f, 3)
        override val h = max(22f, tl.height.toFloat()) + 20 + 6

        override fun draw(c: Canvas) {
            ReportKit.card(c, PAD, 0f, W - PAD, h - 6, CARD, 14f)
            tl.drawAt(c, PAD + 14, 10f)
            val r = RectF(W - PAD - 12 - tw, 10f, W - PAD - 12, 30f)
            c.drawRoundRect(r, 10f, 10f, ReportKit.fill(ReportKit.SOFT))
            c.drawText(tag, r.left + 8, r.bottom - 6, gp)
        }
    }

    /** A node with its numbers and neighbours. */
    private class NodeCard(title: String, body: String, private val color: Int, private val seed: Boolean) : ReportKit.Block() {
        private val tl = ReportKit.layout(title, ReportKit.paint(13.5f, ON, true), INNER - 40, 1.15f, 2)
        private val bl = ReportKit.layout(ReportKit.rich(body), ReportKit.paint(11.5f, MUTED), INNER - 40, 1.25f)
        override val h = 12 + tl.height + 4 + bl.height + 14 + 8f

        override fun draw(c: Canvas) {
            ReportKit.card(c, PAD, 0f, W - PAD, h - 8, CARD, 16f)
            c.drawRoundRect(RectF(PAD + 10, 14f, PAD + 14, h - 22), 2f, 2f, ReportKit.fill(if (seed) ON else color))
            tl.drawAt(c, PAD + 24, 12f)
            bl.drawAt(c, PAD + 24, 16f + tl.height)
        }
    }
}

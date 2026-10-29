/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Block kinds: flowchart shapes plus the topic node of a mind map. */
enum class DKind { START, STEP, DECISION, END, NOTE, TOPIC }

data class DNode(val id: String, var text: String, var kind: DKind = DKind.STEP)

data class DEdge(val from: String, val to: String, var label: String = "")

/** A block diagram: [type] "flow" is laid out top to bottom, "mind" is a tree growing to the right. */
class Diagram(
    var title: String,
    var type: String,
    val nodes: MutableList<DNode> = mutableListOf(),
    val edges: MutableList<DEdge> = mutableListOf(),
    val id: String = "d" + java.lang.Long.toString(System.currentTimeMillis(), 36)
) {
    val isMind get() = type == "mind"

    fun node(id: String) = nodes.firstOrNull { it.id == id }

    fun freshId(): String {
        var i = nodes.size + 1
        while (nodes.any { it.id == "n$i" }) i++
        return "n$i"
    }

    /** Adds a block (optionally linked from [parent]) and returns it. */
    fun add(text: String, kind: DKind = if (isMind) DKind.TOPIC else DKind.STEP, parent: String? = null, label: String = ""): DNode {
        val n = DNode(freshId(), text.trim().take(MAX_TEXT), kind)
        nodes.add(n)
        if (parent != null && node(parent) != null) edges.add(DEdge(parent, n.id, label))
        return n
    }

    fun remove(id: String) {
        nodes.removeAll { it.id == id }
        edges.removeAll { it.from == id || it.to == id }
    }

    fun connect(from: String, to: String, label: String = ""): Boolean {
        if (from == to || node(from) == null || node(to) == null) return false
        if (edges.any { it.from == from && it.to == to }) return false
        edges.add(DEdge(from, to, label))
        return true
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("type", type)
        .put("nodes", JSONArray().also { a -> nodes.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("kind", it.kind.name.lowercase())) } })
        .put("edges", JSONArray().also { a -> edges.forEach { a.put(JSONObject().put("from", it.from).put("to", it.to).put("label", it.label)) } })

    companion object {
        const val MAX_TEXT = 140
        const val MAX_NODES = 60

        private fun kindOf(s: String, mind: Boolean): DKind = when (s.trim().lowercase()) {
            "start", "begin", "begin_end", "старт" -> DKind.START
            "end", "stop", "finish", "конец" -> DKind.END
            "decision", "condition", "if", "question", "решение" -> DKind.DECISION
            "note", "comment", "заметка" -> DKind.NOTE
            "topic", "idea", "branch", "root" -> DKind.TOPIC
            else -> if (mind) DKind.TOPIC else DKind.STEP
        }

        /**
         * Reads a diagram from JSON (own storage or an AI answer). Unknown ids, self links and duplicates are dropped;
         * returns null when fewer than two blocks survive.
         */
        fun fromJson(o: JSONObject, defaultType: String = "flow", defaultTitle: String = ""): Diagram? {
            val type = o.optString("type").takeIf { it == "flow" || it == "mind" } ?: defaultType
            val mind = type == "mind"
            val d = Diagram(
                o.optString("title").trim().ifBlank { defaultTitle }.take(80), type,
                id = o.optString("id").ifBlank { "d" + java.lang.Long.toString(System.currentTimeMillis(), 36) }
            )
            val arr = o.optJSONArray("nodes") ?: return null
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                if (d.nodes.size >= MAX_NODES) break
                val n = arr.optJSONObject(i) ?: continue
                val id = n.opt("id")?.toString()?.trim().orEmpty().ifBlank { "n${i + 1}" }
                val text = n.optString("text").ifBlank { n.optString("label") }.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
                if (text.isEmpty() || !seen.add(id)) continue
                d.nodes.add(DNode(id, text, kindOf(n.optString("kind").ifBlank { n.optString("type") }, mind)))
            }
            if (d.nodes.size < 2) return null
            val ea = o.optJSONArray("edges")
            for (i in 0 until (ea?.length() ?: 0)) {
                val e = ea!!.optJSONObject(i) ?: continue
                val from = (e.opt("from") ?: e.opt("source"))?.toString()?.trim().orEmpty()
                val to = (e.opt("to") ?: e.opt("target"))?.toString()?.trim().orEmpty()
                if (d.node(from) == null || d.node(to) == null) continue
                d.connect(from, to, e.optString("label").trim().take(30))
            }
            return d
        }

        /** The first JSON object inside an AI reply (fences and prose around it are ignored). */
        fun fromAi(reply: String, type: String, defaultTitle: String): Diagram? {
            val raw = JsonText.firstObject(reply) ?: return null
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return fromJson(o, type, defaultTitle)
        }
    }
}

/** Position and size of one block in diagram units. */
data class DBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val r get() = x + w
    val b get() = y + h
}

class DLayout(
    val boxes: Map<String, DBox>,
    val width: Float,
    val height: Float,
    /** edges that go against the flow (loops); they are drawn around the side */
    val back: Set<Pair<String, String>>,
    /** depth of each block (layer for flowcharts, level for mind maps) */
    val depth: Map<String, Int>
)

/** Pure layout: [size] gives the (width, height) of each block, so tests need no fonts. */
object DiagramLayout {
    const val GAP_X = 28f
    const val GAP_Y = 44f
    const val PAD = 8f

    fun layout(d: Diagram, size: (DNode) -> Pair<Float, Float>): DLayout = if (d.isMind) tree(d, size) else layered(d, size)

    /** Edges without duplicates, self links and dangling ids. */
    private fun cleanEdges(d: Diagram): List<DEdge> {
        val ids = d.nodes.map { it.id }.toSet()
        return d.edges.filter { it.from != it.to && it.from in ids && it.to in ids }.distinctBy { it.from to it.to }
    }

    private fun layered(d: Diagram, size: (DNode) -> Pair<Float, Float>): DLayout {
        if (d.nodes.isEmpty()) return DLayout(emptyMap(), 0f, 0f, emptySet(), emptyMap())
        val ids = d.nodes.map { it.id }
        val edges = cleanEdges(d)
        val out = ids.associateWith { mutableListOf<String>() }
        val inDeg = ids.associateWith { 0 }.toMutableMap()
        edges.forEach {
            out[it.from]!!.add(it.to)
            inDeg[it.to] = inDeg[it.to]!! + 1
        }
        // depth-first from sources (start blocks first): an edge to a block still on the stack is a back edge
        val state = HashMap<String, Int>() // 1 = on stack, 2 = done
        val back = HashSet<Pair<String, String>>()
        val order = mutableListOf<String>()
        fun dfs(v: String) {
            state[v] = 1
            order.add(v)
            for (w in out[v]!!) {
                when (state[w]) {
                    1 -> back.add(v to w)
                    null -> dfs(w)
                    else -> {}
                }
            }
            state[v] = 2
        }
        val sources = d.nodes.filter { it.kind == DKind.START }.map { it.id } + ids.filter { inDeg[it] == 0 }
        (sources + ids).forEach { if (state[it] == null) dfs(it) }

        // longest path layering over the acyclic edges
        val fwd = edges.filter { (it.from to it.to) !in back }
        val layer = HashMap<String, Int>()
        ids.forEach { layer[it] = 0 }
        repeat(ids.size) {
            var changed = false
            for (e in fwd) {
                val want = layer[e.from]!! + 1
                if (want > layer[e.to]!!) {
                    layer[e.to] = want
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        val maxLayer = layer.values.maxOrNull() ?: 0
        val rows = Array(maxLayer + 1) { mutableListOf<String>() }
        order.forEach { rows[layer[it]!!].add(it) }
        val preds = ids.associateWith { mutableListOf<String>() }
        val succs = ids.associateWith { mutableListOf<String>() }
        fwd.forEach {
            preds[it.to]!!.add(it.from)
            succs[it.from]!!.add(it.to)
        }
        // barycenter sweeps reduce crossings
        fun sweep(down: Boolean) {
            val range = if (down) 1..maxLayer else (maxLayer - 1) downTo 0
            for (l in range) {
                val ref = rows[if (down) l - 1 else l + 1]
                val pos = ref.withIndex().associate { it.value to it.index }
                val nb = if (down) preds else succs
                val key = rows[l].associateWith { v ->
                    val ps = nb[v]!!.mapNotNull { pos[it] }
                    if (ps.isEmpty()) -1.0 else ps.average()
                }
                val idx = rows[l].withIndex().associate { it.value to it.index }
                rows[l].sortWith(compareBy<String>({ if (key[it]!! < 0) idx[it]!!.toDouble() else key[it]!! }, { idx[it]!! }))
            }
        }
        repeat(3) {
            sweep(true)
            sweep(false)
        }

        val sizes = d.nodes.associate { it.id to size(it) }
        val rowW = rows.map { r -> r.sumOf { sizes[it]!!.first.toDouble() }.toFloat() + GAP_X * max(0, r.size - 1) }
        val widest = rowW.maxOrNull() ?: 0f
        val boxes = HashMap<String, DBox>()
        var y = PAD
        val xs = HashMap<String, Float>() // left edge of each block
        for ((l, row) in rows.withIndex()) {
            var x = PAD + (widest - rowW[l]) / 2
            for (v in row) {
                xs[v] = x
                x += sizes[v]!!.first + GAP_X
            }
        }
        // pull blocks toward the average of their parents, then resolve overlaps; two passes keep chains straight
        repeat(2) {
            for (l in 1..maxLayer) {
                val row = rows[l]
                val want = row.map { v ->
                    val ps = preds[v]!!
                    if (ps.isEmpty()) xs[v]!! + sizes[v]!!.first / 2
                    else ps.map { xs[it]!! + sizes[it]!!.first / 2 }.average().toFloat()
                }
                var minLeft = PAD
                row.forEachIndexed { i, v ->
                    val w = sizes[v]!!.first
                    val left = max(minLeft, want[i] - w / 2)
                    xs[v] = left
                    minLeft = left + w + GAP_X
                }
            }
        }
        for (row in rows) {
            val h = row.maxOfOrNull { sizes[it]!!.second } ?: 0f
            for (v in row) {
                val (w, nh) = sizes[v]!!
                boxes[v] = DBox(xs[v]!!, y + (h - nh) / 2, w, nh)
            }
            y += h + GAP_Y
        }
        val width = (boxes.values.maxOfOrNull { it.r } ?: 0f) + PAD + if (back.isNotEmpty()) 36f else 0f
        return DLayout(boxes, width, y - GAP_Y + PAD, back, layer)
    }

    private fun tree(d: Diagram, size: (DNode) -> Pair<Float, Float>): DLayout {
        if (d.nodes.isEmpty()) return DLayout(emptyMap(), 0f, 0f, emptySet(), emptyMap())
        val ids = d.nodes.map { it.id }
        val edges = cleanEdges(d)
        val children = ids.associateWith { mutableListOf<String>() }
        val parent = HashMap<String, String>()
        // roots: blocks nobody points to; the first one in list order leads
        val pointed = edges.map { it.to }.toSet()
        val roots = ids.filter { it !in pointed }.ifEmpty { listOf(ids.first()) }.toMutableList()
        val level = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        roots.forEach {
            level[it] = 0
            queue.add(it)
        }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (e in edges.filter { it.from == v }) {
                if (e.to in level) continue
                level[e.to] = level[v]!! + 1
                parent[e.to] = v
                children[v]!!.add(e.to)
                queue.add(e.to)
            }
        }
        ids.filter { it !in level }.forEach { // cycles without an entry point
            level[it] = 0
            roots.add(it)
        }
        val sizes = d.nodes.associate { it.id to size(it) }
        val colW = HashMap<Int, Float>()
        sizes.forEach { (id, s) -> colW[level[id]!!] = max(colW[level[id]!!] ?: 0f, s.first) }
        val colX = HashMap<Int, Float>()
        var x = PAD
        for (l in 0..(colW.keys.maxOrNull() ?: 0)) {
            colX[l] = x
            x += (colW[l] ?: 0f) + 56f
        }
        val subtreeH = HashMap<String, Float>()
        fun measure(v: String): Float {
            val own = sizes[v]!!.second
            val kids = children[v]!!
            val h = if (kids.isEmpty()) own else max(own, kids.sumOf { measure(it).toDouble() }.toFloat() + 14f * (kids.size - 1))
            subtreeH[v] = h
            return h
        }
        val boxes = HashMap<String, DBox>()
        fun place(v: String, top: Float) {
            val s = sizes[v]!!
            val h = subtreeH[v]!!
            boxes[v] = DBox(colX[level[v]!!]!!, top + (h - s.second) / 2, s.first, s.second)
            var t = top + (h - (children[v]!!.sumOf { subtreeH[it]!!.toDouble() }.toFloat() + 14f * max(0, children[v]!!.size - 1))).coerceAtLeast(0f) / 2
            for (c in children[v]!!) {
                place(c, t)
                t += subtreeH[c]!! + 14f
            }
        }
        var y = PAD
        for (r in roots) {
            measure(r)
            place(r, y)
            y += subtreeH[r]!! + 30f
        }
        val width = (boxes.values.maxOfOrNull { it.r } ?: 0f) + PAD
        return DLayout(boxes, width, y - 30f + PAD, emptySet(), level)
    }
}

/** Text exports of a diagram: Mermaid source for docs and wikis, and plain outline. */
object DiagramText {
    private fun esc(s: String) = s.replace("\"", "'").replace("\n", " ")

    fun mermaid(d: Diagram): String {
        if (d.isMind) return mindmap(d)
        val ids = d.nodes.withIndex().associate { it.value.id to "N${it.index + 1}" }
        val sb = StringBuilder("flowchart TD\n")
        for (n in d.nodes) {
            val id = ids[n.id]!!
            val t = esc(n.text)
            sb.append("    ").append(id).append(
                when (n.kind) {
                    DKind.START, DKind.END -> "([\"$t\"])"
                    DKind.DECISION -> "{\"$t\"}"
                    DKind.NOTE -> ">\"$t\"]"
                    DKind.TOPIC -> "(\"$t\")"
                    DKind.STEP -> "[\"$t\"]"
                }
            ).append('\n')
        }
        for (e in d.edges) {
            val a = ids[e.from] ?: continue
            val b = ids[e.to] ?: continue
            sb.append("    ").append(a).append(if (e.label.isBlank()) " --> " else " -->|\"${esc(e.label)}\"| ").append(b).append('\n')
        }
        return sb.toString()
    }

    private fun mindmap(d: Diagram): String {
        val lay = DiagramLayout.layout(d) { 1f to 1f }
        val kids = d.nodes.associate { it.id to d.edges.filter { e -> e.from == it.id }.map { e -> e.to } }
        val sb = StringBuilder("mindmap\n")
        val seen = HashSet<String>()
        fun walk(id: String, depth: Int) {
            if (!seen.add(id)) return
            val n = d.node(id) ?: return
            sb.append("  ".repeat(depth + 1))
            sb.append(if (depth == 0) "root((${esc(n.text).replace("(", "").replace(")", "")}))" else esc(n.text).replace("(", "[").replace(")", "]")).append('\n')
            kids[id]?.forEach { walk(it, depth + 1) }
        }
        d.nodes.filter { (lay.depth[it.id] ?: 0) == 0 }.forEach { walk(it.id, 0) }
        return sb.toString()
    }

    /** Indented outline, handy for pasting into a note. */
    fun outline(d: Diagram): String {
        val kids = d.nodes.associate { it.id to d.edges.filter { e -> e.from == it.id } }
        val lay = DiagramLayout.layout(d) { 1f to 1f }
        val sb = StringBuilder(d.title).append('\n')
        val seen = HashSet<String>()
        fun walk(id: String, depth: Int, label: String) {
            val n = d.node(id) ?: return
            sb.append("  ".repeat(depth)).append("- ")
            if (label.isNotBlank()) sb.append('[').append(label).append("] ")
            sb.append(n.text).append('\n')
            if (!seen.add(id)) return
            kids[id]?.forEach { walk(it.to, depth + 1, it.label) }
        }
        val rootsFirst = d.nodes.sortedBy { lay.depth[it.id] ?: 0 }.filter { (lay.depth[it.id] ?: 0) == 0 }
        rootsFirst.forEach { walk(it.id, 0, "") }
        return sb.toString()
    }
}

/** Diagrams built locally from project data, no AI needed. */
object DiagramBuild {
    private fun cut(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"

    /** Mind map: topic → channels → their most viewed videos. */
    fun topicMap(phrase: String, channels: List<Pair<String, List<String>>>): Diagram {
        val d = Diagram(phrase, "mind")
        val root = d.add(phrase, DKind.TOPIC)
        for ((name, titles) in channels) {
            val c = d.add(cut(name, 40), DKind.TOPIC, root.id)
            titles.forEach { d.add(cut(it, 60), DKind.NOTE, c.id) }
        }
        return d
    }

    /** Mind map: topic → labels → videos carrying the label. */
    fun labelMap(phrase: String, labels: List<Pair<String, List<String>>>): Diagram {
        val d = Diagram(phrase, "mind")
        val root = d.add(phrase, DKind.TOPIC)
        for ((label, titles) in labels) {
            val c = d.add("#$label", DKind.TOPIC, root.id)
            titles.forEach { d.add(cut(it, 60), DKind.NOTE, c.id) }
        }
        return d
    }

    data class Funnel(
        val queries: Int, val found: Int, val marked: Int, val collected: Int,
        val withTranscript: Int, val withComments: Int, val channels: Int, val aiTranscribe: Boolean
    )

    /** Flowchart of what happened in the project, with real counts and the transcript decision. */
    fun funnel(phrase: String, f: Funnel): Diagram {
        val d = Diagram(phrase, "flow")
        val start = d.add(t("Тема: ", "Topic: ") + phrase, DKind.START)
        val q = d.add(t("Поиск по запросам: ${f.queries}", "Search queries: ${f.queries}"), parent = start.id)
        val found = d.add(t("Найдено видео: ${f.found}", "Videos found: ${f.found}"), parent = q.id)
        val pick = d.add(t("Отмечено для сбора: ${f.marked}", "Marked to collect: ${f.marked}"), parent = found.id)
        val col = d.add(t("Собрано видео: ${f.collected}", "Videos collected: ${f.collected}"), parent = pick.id)
        val dec = d.add(t("Есть расшифровка?", "Transcript available?"), DKind.DECISION, col.id)
        val yes = d.add(t("С расшифровкой: ${f.withTranscript}", "With transcript: ${f.withTranscript}"), parent = dec.id, label = t("Да", "Yes"))
        val no = d.add(
            if (f.aiTranscribe) t("AI-транскрибация", "AI transcription") else t("Только метаданные и комментарии", "Metadata and comments only"),
            parent = dec.id, label = t("Нет", "No")
        )
        val an = d.add(t("Анализ: слова, каналы, тренды", "Analysis: words, channels, trends"), parent = yes.id)
        d.connect(no.id, an.id)
        val com = d.add(t("Комментарии: видео ${f.withComments}", "Comments: ${f.withComments} videos"), parent = col.id)
        d.connect(com.id, an.id)
        val ch = d.add(t("Каналов в выборке: ${f.channels}", "Channels in the sample: ${f.channels}"), DKind.NOTE, an.id)
        val end = d.add(t("Отчёт и экспорт", "Report and export"), DKind.END, an.id)
        d.connect(ch.id, end.id)
        return d
    }

    /** Pairs ordered by how much room they ask for, used to keep the map readable on a phone. */
    fun topChannels(videos: List<Video>, channels: Int = 6, perChannel: Int = 2): List<Pair<String, List<String>>> =
        videos.filter { it.channel.isNotBlank() }.groupBy { it.channel }
            .entries.sortedByDescending { e -> e.value.sumOf { max(0L, it.views) } }
            .take(channels).map { e -> e.key to e.value.sortedByDescending { it.views }.take(perChannel).map { it.title } }

    fun topLabels(videos: List<Video>, labels: Int = 6, perLabel: Int = 3): List<Pair<String, List<String>>> =
        videos.flatMap { v -> v.labels.map { it to v } }.groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.size }.take(labels)
            .map { e -> e.key to e.value.sortedByDescending { it.views }.take(perLabel).map { it.title } }

    /** For tests and layouts: true when no two blocks of the layout overlap. */
    fun noOverlap(lay: DLayout): Boolean {
        val b = lay.boxes.values.toList()
        for (i in b.indices) for (j in i + 1 until b.size) {
            val a = b[i]
            val c = b[j]
            if (abs(a.cx - c.cx) < (a.w + c.w) / 2 - 0.01f && abs(a.cy - c.cy) < (a.h + c.h) / 2 - 0.01f) return false
        }
        return true
    }
}

/** Saved diagrams of a project: one JSON file per diagram plus a small index. */
object DiagramStore {
    private const val INDEX = "diagrams/index.json"

    data class Entry(val id: String, val title: String, val type: String, val nodes: Int, val at: Long)

    fun list(pid: String): List<Entry> {
        val raw = Store.readFile(pid, INDEX) ?: return emptyList()
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Entry(it.optString("id"), it.optString("title"), it.optString("type"), it.optInt("nodes"), it.optLong("at")) }
        }.sortedByDescending { it.at }
    }

    fun load(pid: String, id: String): Diagram? {
        val raw = Store.readFile(pid, "diagrams/$id.json") ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return Diagram.fromJson(o)
    }

    fun save(pid: String, d: Diagram) {
        Store.writeFile(pid, "diagrams/${d.id}.json", d.toJson().toString())
        val now = System.currentTimeMillis()
        val rest = list(pid).filter { it.id != d.id }
        val a = JSONArray()
        (listOf(Entry(d.id, d.title, d.type, d.nodes.size, now)) + rest).forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("type", it.type).put("nodes", it.nodes).put("at", it.at))
        }
        Store.writeFile(pid, INDEX, a.toString())
    }

    fun delete(pid: String, id: String) {
        Store.writeFile(pid, "diagrams/$id.json", "")
        val a = JSONArray()
        list(pid).filter { it.id != id }.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("type", it.type).put("nodes", it.nodes).put("at", it.at))
        }
        Store.writeFile(pid, INDEX, a.toString())
    }
}

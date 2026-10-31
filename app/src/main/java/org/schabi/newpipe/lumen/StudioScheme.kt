/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/** A block of an info-scheme: appears on word [at] (index into the whole word list, -1 = by itself). */
data class SchNode(
    val id: String, val icon: String = "", val label: String = "", val value: String = "",
    val at: Int = -1, val parent: String = "", val accent: Boolean = false
)

data class SchLink(val from: String, val to: String, val label: String = "")

/** An info-scheme of a scene written by the AI in the lumen.scheme protocol. */
data class SScheme(
    val layout: String, val title: String = "", val nodes: List<SchNode>, val links: List<SchLink> = emptyList(),
    val axes: List<String> = emptyList(), val center: String = ""
)

/** Place of a node inside the scheme box, fractions of the box (centre x, y and size w, h); [shape] card, band or circle. */
data class SchBox(val x: Float, val y: Float, val w: Float, val h: Float, val shape: String = "card")

/**
 * Protocol lumen.scheme v1: the AI describes an info-scheme as data (layout, nodes, links, the word each node
 * appears on), Lumen checks it, asks the AI to fix what is wrong, lays it out and animates it in sync with the voice.
 * Pure logic, no Android.
 */
object StudioScheme {
    val LAYOUTS = listOf("chain", "hub", "tree", "timeline", "funnel", "pyramid", "matrix", "venn")
    const val MAX_NODES = 7
    private const val MAX_LABEL = 28

    /** Description of the protocol for the prompts. */
    const val PROTOCOL = "Протокол lumen.scheme v1. Схема сцены — JSON: " +
        "{\"layout\":\"...\",\"title\":\"до 5 слов или пусто\",\"nodes\":[{\"id\":\"a\",\"icon\":\"эмодзи\",\"label\":\"1-3 слова\",\"value\":\"число или факт, можно пусто\",\"at\":N,\"parent\":\"id\",\"accent\":true}]," +
        "\"links\":[{\"from\":\"a\",\"to\":\"b\",\"label\":\"0-2 слова\"}],\"axes\":[\"ось X\",\"ось Y\"],\"center\":\"общее\"}. " +
        "layout: chain — 2-5 шагов или цепочка причина→следствие (связи по порядку ставятся сами); " +
        "hub — главное понятие (первый узел) и 3-6 связанных с ним вокруг; " +
        "tree — иерархия 3-7 узлов до 3 уровней, у дочерних parent = id родителя, у корня parent пусто; " +
        "timeline — 3-6 событий по порядку, value — год или срок; " +
        "funnel — воронка 3-5 этапов от широкого к узкому, value — число; " +
        "pyramid — 3-5 уровней, первый узел — вершина, последний — основа; " +
        "matrix — ровно 4 узла по четвертям (левый верх, правый верх, левый низ, правый низ) и обязательные axes [ось X, ось Y]; " +
        "venn — 2-3 пересекающихся круга, center — что у них общего. " +
        "at — номер слова [N], на котором о узле говорят (тогда он и появится), только из слов этой сцены. " +
        "accent: true — у одного главного узла. links нужны только для нестандартных связей в chain, hub, tree. " +
        "Подписи короткие, на языке ролика, эмодзи по смыслу; схема передаёт только сказанное, без новых фактов."

    // ---------- JSON ----------

    private fun clean(s: String, max: Int = MAX_LABEL): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    fun fromJson(o: JSONObject): SScheme? {
        val layout = o.optString("layout").trim().lowercase()
        val arr = o.optJSONArray("nodes") ?: return null
        val nodes = (0 until arr.length()).mapNotNull { i ->
            val n = arr.optJSONObject(i) ?: return@mapNotNull null
            SchNode(
                n.optString("id").trim().ifEmpty { "n${i + 1}" }.take(16), n.optString("icon").trim().take(16),
                clean(n.optString("label")), clean(n.optString("value"), 16), n.optInt("at", -1),
                n.optString("parent").trim().take(16), n.optBoolean("accent", false)
            ).takeIf { it.icon.isNotEmpty() || it.label.isNotEmpty() || it.value.isNotEmpty() }
        }
        val links = o.optJSONArray("links")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { SchLink(it.optString("from").trim(), it.optString("to").trim(), clean(it.optString("label"), 16)) } }
        }.orEmpty()
        val axes = o.optJSONArray("axes")?.let { a -> (0 until a.length()).map { clean(a.optString(it), 20) }.filter { it.isNotEmpty() } }.orEmpty()
        return SScheme(layout, clean(o.optString("title"), 40), nodes, links, axes.take(2), clean(o.optString("center"), 20))
    }

    fun fromJson(s: String): SScheme? = runCatching { fromJson(JSONObject(s)) }.getOrNull()

    fun toJson(s: SScheme): JSONObject = JSONObject().put("layout", s.layout).put("title", s.title).put("nodes", JSONArray().apply {
        s.nodes.forEach { n ->
            put(JSONObject().put("id", n.id).put("icon", n.icon).put("label", n.label).apply {
                if (n.value.isNotEmpty()) put("value", n.value)
                if (n.at >= 0) put("at", n.at)
                if (n.parent.isNotEmpty()) put("parent", n.parent)
                if (n.accent) put("accent", true)
            })
        }
    }).apply {
        if (s.links.isNotEmpty()) put("links", JSONArray().apply { s.links.forEach { put(JSONObject().put("from", it.from).put("to", it.to).put("label", it.label)) } })
        if (s.axes.isNotEmpty()) put("axes", JSONArray(s.axes))
        if (s.center.isNotEmpty()) put("center", s.center)
    }

    /** The AI answer {"schemes":[{"scene":N, ...}]} → scene number (1-based as shown to the AI) to its raw object. */
    fun parseAnswer(reply: String): List<Pair<Int, JSONObject>>? {
        val o = runCatching { JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: JsonSalvage.array(reply, "schemes")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val a = o.optJSONArray("schemes") ?: return null
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s -> s.optInt("scene", 0).takeIf { it > 0 }?.let { it to s } } }
    }

    // ---------- checks ----------

    /** Problems the AI has to fix itself (empty = the scheme can be drawn). */
    fun problems(s: SScheme?, words: IntRange? = null): List<String> {
        if (s == null) return listOf("схема не в формате JSON протокола")
        val out = mutableListOf<String>()
        if (s.layout !in LAYOUTS) out += "неизвестный layout \"${s.layout}\" — нужен один из: ${LAYOUTS.joinToString()}"
        val n = s.nodes.size
        val (min, max) = when (s.layout) {
            "matrix" -> 4 to 4
            "venn" -> 2 to 3
            "hub" -> 3 to MAX_NODES
            "funnel", "pyramid", "timeline" -> 3 to 6
            else -> 2 to MAX_NODES
        }
        if (n < min || n > max) out += "у layout ${s.layout} должно быть узлов $min-$max, а их $n"
        val ids = s.nodes.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { out += "id \"$it\" повторяется" }
        if (s.layout == "matrix" && s.axes.size < 2) out += "у matrix нужны axes: [ось X, ось Y]"
        if (s.layout == "tree") {
            val roots = s.nodes.count { it.parent.isEmpty() || it.parent !in ids }
            if (roots != 1) out += "у tree должен быть ровно один корень (узел без parent), а их $roots"
            if (depths(s).values.any { it < 0 }) out += "в tree parent образуют цикл"
        }
        if (words != null && s.nodes.isNotEmpty() && s.nodes.none { it.at in words })
            out += "ни одно at не попадает в слова сцены [${words.first}..${words.last}]"
        return out
    }

    /** Small mechanical fixes that need no AI: extra nodes, links to unknown ids, [at] outside the scene, several accents. */
    fun repair(s: SScheme, words: IntRange? = null): SScheme {
        val nodes0 = s.nodes.take(if (s.layout == "matrix") 4 else if (s.layout == "venn") 3 else MAX_NODES)
        var accented = false
        val nodes = nodes0.map { n ->
            val acc = n.accent && !accented
            if (acc) accented = true
            n.copy(at = if (words != null && n.at !in words) -1 else n.at, accent = acc)
        }
        val ids = nodes.map { it.id }.toSet()
        val links = if (s.layout in listOf("chain", "hub", "tree")) s.links.filter { it.from in ids && it.to in ids && it.from != it.to }.take(12) else emptyList()
        return s.copy(nodes = nodes, links = links)
    }

    // ---------- layout ----------

    /** Depth of every node of a tree (root 0; -1 = in a cycle or unreachable). */
    fun depths(s: SScheme): Map<String, Int> {
        val byId = s.nodes.associateBy { it.id }
        return s.nodes.associate { n ->
            var d = 0
            var cur = n
            val seen = HashSet<String>()
            while (cur.parent.isNotEmpty() && byId.containsKey(cur.parent)) {
                if (!seen.add(cur.id)) { d = -1; break }
                cur = byId.getValue(cur.parent)
                d++
            }
            n.id to d
        }
    }

    /** Links drawn as arrows: the given ones, or the natural ones of the layout. */
    fun links(s: SScheme): List<SchLink> {
        if (s.links.isNotEmpty()) return s.links
        val n = s.nodes
        return when (s.layout) {
            "chain" -> n.zipWithNext { a, b -> SchLink(a.id, b.id) }
            "hub" -> n.drop(1).map { SchLink(n[0].id, it.id) }
            "tree" -> { val ids = n.map { it.id }.toSet(); n.filter { it.parent in ids }.map { SchLink(it.parent, it.id) } }
            else -> emptyList()
        }
    }

    /**
     * Where every node sits in a box of shape [ratio] (width / height). Fractions of the box; cards keep a pleasant
     * size and never overlap.
     */
    fun layout(s: SScheme, ratio: Float): List<SchBox> {
        val n = s.nodes.size
        if (n == 0) return emptyList()
        val wide = ratio >= 1.2f
        return when (s.layout) {
            "chain" -> if (wide || n <= 3) row(n, 0.5f) else column(n)
            "hub" -> {
                val k = n - 1
                val cw = if (wide) 0.20f else 0.30f
                val ch = cw * ratio
                listOf(SchBox(0.5f, 0.5f, cw * 1.15f, ch * 1.15f)) + (0 until k).map { i ->
                    val ang = -Math.PI / 2 + 2 * Math.PI * i / k
                    SchBox(0.5f + 0.38f * Math.cos(ang).toFloat(), 0.5f + 0.36f * Math.sin(ang).toFloat(), cw, ch)
                }
            }
            "tree" -> {
                val d = depths(s)
                val levels = s.nodes.groupBy { (d[it.id] ?: 0).coerceAtLeast(0) }
                val nl = (levels.keys.maxOrNull() ?: 0) + 1
                s.nodes.map { node ->
                    val lv = (d[node.id] ?: 0).coerceAtLeast(0)
                    val row = levels.getValue(lv)
                    val i = row.indexOf(node)
                    val cw = (0.9f / row.size).coerceAtMost(if (wide) 0.24f else 0.32f) * 0.9f
                    SchBox((i + 0.5f) / row.size, (lv + 0.5f) / nl, cw, (0.8f / nl).coerceAtMost(cw * ratio))
                }
            }
            "timeline" -> if (wide) (0 until n).map { i ->
                SchBox((i + 0.5f) / n, if (i % 2 == 0) 0.25f else 0.75f, (0.95f / n).coerceAtMost(0.24f), 0.36f)
            } else (0 until n).map { i ->
                SchBox(if (i % 2 == 0) 0.27f else 0.73f, (i + 0.5f) / n, 0.42f, (0.9f / n).coerceAtMost(0.2f))
            }
            "funnel", "pyramid" -> (0 until n).map { i ->
                val t = if (n == 1) 0f else i.toFloat() / (n - 1)
                val wdt = if (s.layout == "funnel") 1f - 0.5f * t else 0.45f + 0.55f * t
                SchBox(0.5f, (i + 0.5f) / n, wdt, 0.9f / n, "band")
            }
            "matrix" -> (0 until n).map { i -> SchBox(if (i % 2 == 0) 0.28f else 0.72f, if (i < 2) 0.27f else 0.73f, 0.38f, 0.36f) }
            "venn" -> {
                // circles: the renderer takes the smaller side, so they stay round in any box
                val d = if (n == 2) 0.58f else 0.5f
                val pts = if (n == 2) listOf(0.36f to 0.5f, 0.64f to 0.5f) else listOf(0.5f to 0.36f, 0.37f to 0.63f, 0.63f to 0.63f)
                pts.take(n).map { (x, y) -> SchBox(x, y, d, d, "circle") }
            }
            else -> row(n, 0.5f)
        }
    }

    private fun row(n: Int, y: Float) = (0 until n).map { i -> SchBox((i + 0.5f) / n, y, (0.86f / n).coerceAtMost(0.3f), 0.6f) }

    private fun column(n: Int) = (0 until n).map { i -> SchBox(0.5f, (i + 0.5f) / n, 0.7f, (0.84f / n).coerceAtMost(0.22f)) }

    // ---------- timing ----------

    /**
     * When every node appears (ms): on its word [SchNode.at], else on the first spoken word that matches its label,
     * else one after another over the first 60% of the scene. Times never go backwards in the order of the nodes
     * for chain, timeline, funnel and pyramid, where the order matters.
     */
    fun reveal(s: SScheme, words: List<SWord>, range: IntRange, startMs: Long, endMs: Long): List<Long> {
        val n = s.nodes.size
        val span = ((endMs - startMs) * 0.6f).toLong().coerceAtLeast(1)
        val raw = s.nodes.mapIndexed { i, node ->
            val byAt = node.at.takeIf { it in range && it in words.indices }?.let { words[it].startMs }
            byAt ?: matchWord(node.label, words, range)?.let { words[it].startMs } ?: (startMs + 250 + span * i / n.coerceAtLeast(1))
        }
        if (s.layout !in listOf("chain", "timeline", "funnel", "pyramid")) return raw
        var last = Long.MIN_VALUE
        return raw.map { t -> maxOf(t, last + 120).also { last = it } }
    }

    private fun stem(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(5)

    /** First word of the scene sharing a stem (5 letters) with a long word of [label]. */
    fun matchWord(label: String, words: List<SWord>, range: IntRange): Int? {
        val keys = label.split(' ').map(::stem).filter { it.length >= 4 }
        if (keys.isEmpty()) return null
        return range.firstOrNull { it in words.indices && stem(words[it].text).let { w -> w.length >= 4 && keys.any { k -> w == k } } }
    }

    // ---------- keeping schemes through a new direction ----------

    /**
     * Old word index → new one when the words in [drop] were cut out of the take (null = that word is gone).
     */
    fun dropIndex(drop: Set<Int>): (Int) -> Int? {
        if (drop.isEmpty()) return { it }
        val sorted = drop.sorted()
        return { i ->
            if (i in drop) null
            else {
                var lo = 0
                var hi = sorted.size
                while (lo < hi) {
                    val m = (lo + hi) / 2
                    if (sorted[m] < i) lo = m + 1 else hi = m
                }
                i - lo
            }
        }
    }

    /**
     * Puts the schemes made before a new direction back onto the new scenes: each goes to the scene that covers most of
     * its words ([schemes] = scheme and the word range of its old scene), one scheme per scene; the words of its
     * nodes ([SchNode.at]) follow [index].
     */
    fun carry(schemes: List<Pair<SScheme, IntRange>>, scenes: List<SScene>, ranges: List<IntRange>, index: (Int) -> Int?): List<SScene> {
        if (schemes.isEmpty()) return scenes
        val out = scenes.toMutableList()
        val used = HashSet<Int>()
        for ((s, r) in schemes) {
            val mapped = r.mapNotNull(index)
            if (mapped.isEmpty()) continue
            val best = ranges.indices.filter { it !in used && it < out.size }.maxByOrNull { j -> mapped.count { it in ranges[j] } } ?: continue
            if (mapped.none { it in ranges[best] }) continue
            used += best
            val moved = s.copy(nodes = s.nodes.map { n -> n.copy(at = if (n.at >= 0) index(n.at) ?: -1 else -1) })
            out[best] = out[best].copy(kind = "scheme", scheme = repair(moved, ranges[best]))
        }
        return out
    }

    // ---------- prompt blocks ----------

    /** Scenes for the prompt: "Сцена 3 [41..58] (kind): [41]слово [42]слово …". */
    fun scenesBlock(scenes: List<SScene>, words: List<SWord>, ranges: List<IntRange>): String = scenes.indices.joinToString("\n") { i ->
        val r = ranges.getOrNull(i) ?: IntRange.EMPTY
        val ws = r.filter { it in words.indices }.joinToString(" ") { "[$it]" + words[it].text }
        "Сцена ${i + 1} [${r.first}..${r.last}] (${scenes[i].kind}): " + ws.ifEmpty { scenes[i].say }
    }

    /** How many scenes may get a scheme: about a third, at least one. */
    fun maxSchemes(scenes: Int): Int = (scenes / 3).coerceIn(1, 12)
}

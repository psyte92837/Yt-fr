/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Knowledge map of a project: a tree of aspects (topic, channels, labels, tags, timeline, formats,
 * audience, comments, recommendations, AI findings) that opens down to videos and comments.
 * Node ids are paths ("channels/c:Name/v:id"), so expanded state and saved summaries survive a rebuild.
 */
class KNode(
    val id: String,
    val title: String,
    val sub: String = "",
    val info: String = "",
    val url: String = "",
    kids: () -> List<KNode> = { emptyList() }
) {
    /** Children are built on first access: comments of a video are read only when it is opened. */
    val children: List<KNode> by lazy(kids)
    val depth: Int get() = id.count { it == '/' }
}

class KInsight(val type: String, val statement: String, val video: String = "")

/** Everything the map is built from; loaded by the screen, plain data in tests. */
class KInput(
    val project: Project,
    val videos: List<Video>,
    val summaries: Map<String, String> = emptyMap(), // video id → AI summary
    val comments: (String) -> List<Comment> = { emptyList() },
    val insights: List<KInsight> = emptyList(),
    val recs: List<RecNode> = emptyList(),
    val ai: List<AiResult> = emptyList()
)

object KMap {
    const val KIND = "kmap"

    /** Path-safe key part: no slashes, bounded length. */
    fun key(s: String): String = s.replace('/', '∕').trim().take(80)

    fun build(inp: KInput): KNode {
        val p = inp.project
        val vs = inp.videos
        return KNode("map", p.name.ifBlank { p.phrase }, t("Видео: ", "Videos: ") + vs.size,
            info = "«${p.phrase}»" + if (p.domain.isNotBlank()) " · ${p.domain}" else "") { aspects(inp) }
    }

    private fun aspects(inp: KInput): List<KNode> {
        val p = inp.project
        val vs = inp.videos
        val out = mutableListOf<KNode>()
        val qs = p.effectiveQueries()
        out += KNode("map/topic", t("Тема", "Topic"), p.phrase,
            info = (if (p.domain.isNotBlank()) p.domain + "\n" else "") + t("Запросы: ", "Queries: ") + qs.joinToString("; ")) {
            qs.map { q -> KNode("map/topic/q:" + key(q), q, t("запрос", "query"), info = q) }
        }
        val channels = vs.groupBy { it.channel.ifBlank { "—" } }.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Video>>> { it.value.size }.thenByDescending { e -> e.value.sumOf { maxOf(it.views, 0L) } }
        )
        if (channels.isNotEmpty()) out += KNode("map/channels", t("Каналы", "Channels"), countSub(channels.size, vs.size)) {
            channels.map { (name, list) ->
                val id = "map/channels/c:" + key(name)
                KNode(id, name, videosSub(list), url = list.firstOrNull { it.channelUrl.isNotBlank() }?.channelUrl.orEmpty()) {
                    sorted(list).map { video(inp, id, it) }
                }
            }
        }
        val marked = vs.filter { it.marked }
        val labels = vs.flatMap { it.labels }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        if (marked.isNotEmpty() || labels.isNotEmpty()) out += KNode("map/labels", t("Метки и отметки", "Labels and marks"),
            t("меток: ", "labels: ") + labels.size + " · " + t("отмечено: ", "marked: ") + marked.size) {
            buildList {
                if (marked.isNotEmpty()) add(group("map/labels/marked", "★ " + t("Отмеченные", "Marked"), marked, inp))
                labels.forEach { (l, _) -> add(group("map/labels/l:" + key(l), "🏷 $l", vs.filter { l in it.labels }, inp)) }
            }
        }
        val tags = topTags(vs, 30)
        if (tags.isNotEmpty()) out += KNode("map/tags", t("Теги и ключевые темы", "Tags and key themes"),
            tags.take(5).joinToString(", ") { it.first }) {
            tags.map { (tag, list) -> group("map/tags/t:" + key(tag), "#$tag", list, inp) }
        }
        val months = byMonth(vs)
        if (months.isNotEmpty()) out += KNode("map/time", t("Хронология", "Timeline"),
            months.first().first + if (months.size > 1) " … " + months.last().first else "") {
            months.map { (m, list) -> group("map/time/m:$m", m, list, inp) }
        }
        val formats = formats(vs)
        if (formats.size > 1) out += KNode("map/format", t("Форматы и длительность", "Formats and length"),
            formats.joinToString(" · ") { "${it.first}: ${it.second.size}" }) {
            formats.map { (f, list) -> group("map/format/f:" + key(f), f, list, inp) }
        }
        if (inp.insights.isNotEmpty()) {
            val byType = inp.insights.groupBy { it.type.ifBlank { "other" } }.entries.sortedByDescending { it.value.size }
            out += KNode("map/audience", t("Аудитория", "Audience"),
                byType.take(4).joinToString(" · ") { "${insightName(it.key)}: ${it.value.size}" }) {
                byType.map { (type, list) ->
                    val id = "map/audience/i:" + key(type)
                    KNode(id, insightName(type), t("пунктов: ", "items: ") + list.size) {
                        list.mapIndexed { i, s -> KNode("$id/$i", s.statement.take(140), s.video, info = s.statement) }
                    }
                }
            }
        }
        val withComments = vs.filter { it.commentsCount > 0 }.sortedByDescending { it.commentsCount }
        if (withComments.isNotEmpty()) out += KNode("map/comments", t("Комментарии", "Comments"),
            countSub(withComments.size, withComments.sumOf { it.commentsCount }, t("видео", "videos"), t("комм.", "comm."))) {
            withComments.map { v -> commentsNode(inp, "map/comments/v:" + key(v.id), v, v.title) }
        }
        if (inp.recs.isNotEmpty()) out += KNode("map/recs", t("Рекомендации YouTube", "YouTube recommendations"),
            t("узлов: ", "nodes: ") + inp.recs.size) {
            inp.recs.map { r ->
                KNode("map/recs/r:" + key(r.id), r.title.ifBlank { r.id }, r.channel + " · ×" + r.hits + " · " + Fmt.num(r.views),
                    info = r.title + " — " + r.channel + " (hits " + r.hits + ", depth " + r.depth +
                        (if (r.relevant == 1) ", " + t("по теме", "on topic") else if (r.relevant == 0) ", " + t("не по теме", "off topic") else "") + ")",
                    url = r.url)
            }
        }
        val ai = inp.ai.filter { it.kind != "video_summary" && it.kind != KIND && it.kind != "domain" }
        if (ai.isNotEmpty()) out += KNode("map/ai", t("Выводы AI", "AI findings"), t("ответов: ", "answers: ") + ai.size) {
            ai.map { r -> KNode("map/ai/a:" + key(r.key), aiName(r), Fmt.dateTime(r.at), info = r.text) }
        }
        return out
    }

    private fun countSub(groups: Int, items: Int, a: String = t("групп", "groups"), b: String = t("видео", "videos")) = "$a: $groups · $b: $items"

    private fun videosSub(list: List<Video>) =
        t("видео: ", "videos: ") + list.size + " · " + Fmt.num(list.sumOf { maxOf(it.views, 0L) }) + t(" просм.", " views")

    private fun sorted(list: List<Video>) = list.sortedByDescending { it.views }

    private fun group(id: String, title: String, list: List<Video>, inp: KInput) =
        KNode(id, title, videosSub(list)) { sorted(list).map { video(inp, id, it) } }

    /** A video opens into its AI summary, description, tags and top comments. */
    fun video(inp: KInput, parent: String, v: Video): KNode {
        val id = "$parent/v:" + key(v.id)
        val sum = inp.summaries[v.id]
        val sub = listOf(v.channel, Fmt.num(v.views) + t(" просм.", " views"), Fmt.date(v.uploadMs)).filter { it.isNotBlank() }.joinToString(" · ")
        val info = v.title + " — " + v.channel + " (" + Fmt.num(v.views) + " views, " + Fmt.date(v.uploadMs) + ")\n" +
            (sum?.take(1200) ?: v.description.take(400))
        return KNode(id, v.title.ifBlank { v.id }, sub, info.trim(), v.url) {
            buildList {
                if (sum != null) add(KNode("$id/sum", "✨ " + t("Сводка AI", "AI summary"), "", info = sum))
                if (v.description.isNotBlank()) add(KNode("$id/desc", t("Описание", "Description"), v.description.take(80).replace('\n', ' '), info = v.description))
                if (v.tags.isNotEmpty()) add(KNode("$id/tags", t("Теги", "Tags"), v.tags.size.toString(), info = v.tags.joinToString(", ")))
                if (v.commentsCount > 0) add(commentsNode(inp, "$id/comm", v, t("Комментарии", "Comments")))
            }
        }
    }

    private fun commentsNode(inp: KInput, id: String, v: Video, title: String) =
        KNode(id, title, t("комментариев: ", "comments: ") + v.commentsCount, url = v.url) {
            inp.comments(v.id).filter { !it.isReply }.sortedByDescending { it.likes }.take(15).mapIndexed { i, c ->
                KNode("$id/$i", c.text.replace('\n', ' ').take(140), "♥ " + c.likes + " · " + c.author, info = c.text)
            }
        }

    /** Most frequent tags (case-insensitive, at least 2 videos), with their videos. */
    fun topTags(vs: List<Video>, n: Int): List<Pair<String, List<Video>>> {
        val by = LinkedHashMap<String, MutableList<Video>>()
        val shown = HashMap<String, String>()
        for (v in vs) for (tag in v.tags.map { it.trim() }.filter { it.length > 1 }.distinctBy { it.lowercase() }) {
            val k = tag.lowercase()
            shown.putIfAbsent(k, tag)
            by.getOrPut(k) { mutableListOf() }.add(v)
        }
        return by.entries.filter { it.value.size >= 2 }.sortedByDescending { it.value.size }.take(n).map { shown[it.key]!! to it.value }
    }

    /** Videos by upload month, newest first; undated ones last. */
    fun byMonth(vs: List<Video>): List<Pair<String, List<Video>>> {
        val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val dated = vs.filter { it.uploadMs > 0 }.groupBy { fmt.format(Date(it.uploadMs)) }.toSortedMap(reverseOrder()).toList()
        val undated = vs.filter { it.uploadMs <= 0 }
        return if (undated.isEmpty()) dated else dated + (t("без даты", "no date") to undated)
    }

    fun formats(vs: List<Video>): List<Pair<String, List<Video>>> = listOf(
        "Shorts" to vs.filter { it.isShort },
        t("до 4 мин", "under 4 min") to vs.filter { !it.isShort && it.duration in 1 until 240 },
        t("4–20 мин", "4–20 min") to vs.filter { !it.isShort && it.duration in 240 until 1200 },
        t("больше 20 мин", "over 20 min") to vs.filter { !it.isShort && it.duration >= 1200 },
        t("длительность неизвестна", "unknown length") to vs.filter { !it.isShort && it.duration <= 0 }
    ).filter { it.second.isNotEmpty() }

    fun insightName(type: String): String = when (type) {
        "pain" -> t("Боли", "Pains")
        "question" -> t("Вопросы", "Questions")
        "jtbd" -> t("Задачи (JTBD)", "Jobs to be done")
        "voc" -> t("Голос клиента", "Voice of customer")
        "search_intent" -> t("Поисковые намерения", "Search intents")
        "objection" -> t("Возражения", "Objections")
        "unmet_need" -> t("Неудовлетворённые потребности", "Unmet needs")
        "content_gap" -> t("Пробелы в контенте", "Content gaps")
        "topic" -> t("Подтемы", "Subtopics")
        "evidence" -> t("Доказательства", "Evidence")
        "hypothesis" -> t("Гипотезы", "Hypotheses")
        else -> type
    }

    private fun aiName(r: AiResult): String = when (r.kind) {
        "project_summary" -> t("Сводка проекта", "Project summary")
        "clusters" -> t("Кластеры", "Clusters")
        "sentiment" -> t("Тональность", "Sentiment")
        "chat" -> "💬 " + r.target.take(80)
        else -> r.kind
    }

    /** Finds a node by its path id (opens only the branches on the way). */
    fun find(root: KNode, id: String): KNode? {
        if (root.id == id) return root
        val next = root.children.firstOrNull { id == it.id || id.startsWith(it.id + "/") } ?: return null
        return find(next, id)
    }

    /** Titles from the root down to the node: "Project › Channels › Name". */
    fun path(root: KNode, id: String): List<String> {
        val out = mutableListOf<String>()
        var n: KNode? = root
        while (n != null) {
            out += n.title
            if (n.id == id) return out
            n = n.children.firstOrNull { id == it.id || id.startsWith(it.id + "/") }
        }
        return out
    }

    /**
     * Text of a branch for the AI, grouped like the tree. Goes as deep as fits into [limit]:
     * an overview of all children beats details of the first few.
     */
    fun context(node: KNode, limit: Int, maxDepth: Int = 4): String {
        var best = ""
        for (d in 1..maxDepth) {
            val sb = StringBuilder()
            val full = dump(node, 0, d, sb, limit)
            if (!full) return if (best.isEmpty()) sb.toString().take(limit) + "\n…" else best
            best = sb.toString()
            if (!hasDeeper(node, d)) break
        }
        return best
    }

    private fun dump(n: KNode, level: Int, max: Int, sb: StringBuilder, limit: Int): Boolean {
        sb.append("  ".repeat(level)).append("- ").append(n.title)
        if (n.sub.isNotBlank()) sb.append(" (").append(n.sub).append(")")
        val info = n.info.replace('\n', ' ').trim()
        if (info.isNotBlank() && info != n.title) sb.append(": ").append(info.take(if (level == max) 300 else 600))
        sb.append('\n')
        if (sb.length > limit) return false
        if (level < max) for (c in n.children) if (!dump(c, level + 1, max, sb, limit)) return false
        return true
    }

    private fun hasDeeper(n: KNode, d: Int): Boolean =
        if (d == 0) n.children.isNotEmpty() else n.children.any { hasDeeper(it, d - 1) }

    /** Cache key of a summary: same branch text, prompt, model and language → same answer. */
    fun summaryKey(nodeId: String, model: String, prompt: String, data: String, lang: String) =
        sha1("$KIND|$nodeId|$model|$prompt|${data.hashCode()}|$lang")
}

val KMAP_PROMPTS = linkedMapOf(
    "kmap_aspect" to (
        "Тема исследования YouTube: \"{phrase}\" ({domain}). Ниже ветка карты знаний проекта: «{question}».\n" +
            "Сделай сводку по этому аспекту на языке {lang}, только по данным, без выдумок:\n" +
            "1. Главное в 2-3 предложениях.\n2. Ключевые закономерности и факты (с цифрами, если есть).\n" +
            "3. Что выделяется: лидеры, исключения, противоречия.\n4. Выводы и что изучить дальше (2-4 пункта).\n" +
            "Кратко, в Markdown.\n\n{data}"
        )
)

object KMapAi {
    private fun lang() = if (Settings.lang == "en") "English" else "русский"

    fun limit(): Int = Settings.chunkChars * 2

    fun estimate(data: String): Int = Llm.estimateTokens(data) + 1500

    /** Summary of one branch of the map; cached by the branch text. */
    fun summarize(p: Project, nodeId: String, pathTitle: String, data: String, force: Boolean): AiResult {
        val prov = AiTasks.requireProvider()
        val key = KMap.summaryKey(nodeId, prov.smartModel, Prompts.get("kmap_aspect"), data, Settings.lang)
        if (!force) Store.ai(p.id, key)?.let { return it }
        val prompt = Prompts.fill("kmap_aspect", mapOf(
            "phrase" to p.phrase, "domain" to p.domain.ifBlank { "-" }, "lang" to lang(), "data" to data, "question" to pathTitle
        ))
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, maxTokens = 2500, task = "kmap_aspect", pid = p.id)
        return AiResult(key, KMap.KIND, nodeId, r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }
}

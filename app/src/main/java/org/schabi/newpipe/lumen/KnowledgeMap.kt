/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    /** Videos under the node (its weight on the map). */
    val count: Int = 0,
    /** Videos added since the previous visit of the map. */
    val fresh: Int = 0,
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
    val ai: List<AiResult> = emptyList(),
    /** Time of the previous visit: videos added later are marked as new (0 = mark nothing). */
    val since: Long = 0
)

object KMap {
    const val KIND = "kmap"
    const val GAPS = "kmap_gaps"
    const val PATH = "kmap_path"

    /** Path-safe key part: no slashes, bounded length. */
    fun key(s: String): String = s.replace('/', '∕').trim().take(80)

    fun build(inp: KInput): KNode {
        val p = inp.project
        val vs = inp.videos
        return KNode("map", p.name.ifBlank { p.phrase }, t("Видео: ", "Videos: ") + vs.size,
            info = "«${p.phrase}»" + if (p.domain.isNotBlank()) " · ${p.domain}" else "",
            count = vs.size, fresh = fresh(inp, vs)) { aspects(inp) }
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
        path(inp)?.let { out += it }
        gaps(inp)?.let { out += it }
        val channels = vs.groupBy { it.channel.ifBlank { "—" } }.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Video>>> { it.value.size }.thenByDescending { e -> e.value.sumOf { maxOf(it.views, 0L) } }
        )
        if (channels.isNotEmpty()) out += KNode("map/channels", t("Каналы", "Channels"), countSub(channels.size, vs.size),
            count = vs.size, fresh = fresh(inp, vs)) {
            channels.map { (name, list) ->
                val id = "map/channels/c:" + key(name)
                KNode(id, name, videosSub(list), url = list.firstOrNull { it.channelUrl.isNotBlank() }?.channelUrl.orEmpty(),
                    count = list.size, fresh = fresh(inp, list)) {
                    sorted(list).map { video(inp, id, it) }
                }
            }
        }
        val marked = vs.filter { it.marked }
        val labels = vs.flatMap { it.labels }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        val labelled = vs.filter { it.marked || it.labels.any { l -> l.isNotBlank() } }
        if (marked.isNotEmpty() || labels.isNotEmpty()) out += KNode("map/labels", t("Метки и отметки", "Labels and marks"),
            t("меток: ", "labels: ") + labels.size + " · " + t("отмечено: ", "marked: ") + marked.size,
            count = labelled.size, fresh = fresh(inp, labelled)) {
            buildList {
                if (marked.isNotEmpty()) add(group("map/labels/marked", "★ " + t("Отмеченные", "Marked"), marked, inp))
                labels.forEach { (l, _) -> add(group("map/labels/l:" + key(l), "🏷 $l", vs.filter { l in it.labels }, inp)) }
            }
        }
        val tags = topTags(vs, 30)
        val tagged = tags.flatMap { it.second }.distinctBy { it.id }
        if (tags.isNotEmpty()) out += KNode("map/tags", t("Теги и ключевые темы", "Tags and key themes"),
            tags.take(5).joinToString(", ") { it.first }, count = tagged.size, fresh = fresh(inp, tagged)) {
            tags.map { (tag, list) -> group("map/tags/t:" + key(tag), "#$tag", list, inp) }
        }
        val months = byMonth(vs)
        if (months.isNotEmpty()) out += KNode("map/time", t("Хронология", "Timeline"),
            months.first().first + if (months.size > 1) " … " + months.last().first else "", count = vs.size, fresh = fresh(inp, vs)) {
            months.map { (m, list) -> group("map/time/m:$m", m, list, inp) }
        }
        val formats = formats(vs)
        if (formats.size > 1) out += KNode("map/format", t("Форматы и длительность", "Formats and length"),
            formats.joinToString(" · ") { "${it.first}: ${it.second.size}" }, count = vs.size, fresh = fresh(inp, vs)) {
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
        val ai = inp.ai.filter { it.kind != "video_summary" && !it.kind.startsWith(KIND) && it.kind != "domain" }
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
        KNode(id, title, videosSub(list), count = list.size, fresh = fresh(inp, list)) { sorted(list).map { video(inp, id, it) } }

    /** How many of [list] were added after the previous visit. */
    fun fresh(inp: KInput, list: List<Video>): Int = if (inp.since <= 0) 0 else list.count { it.addedAt > inp.since }

    /** Latest saved AI answer of a map-wide task ([GAPS], [PATH]). */
    private fun latest(inp: KInput, kind: String) = inp.ai.filter { it.kind == kind }.maxByOrNull { it.at }

    /** «🧭 Learning path»: the AI's viewing order of the project's videos, step by step. */
    private fun path(inp: KInput): KNode? {
        val r = latest(inp, PATH) ?: return null
        val steps = parsePath(r.text, inp.videos)
        if (steps.isEmpty()) return null
        val id = "map/path"
        return KNode(id, "🧭 " + t("Путь обучения", "Learning path"), t("шагов: ", "steps: ") + steps.size,
            info = r.text, count = steps.size) {
            steps.mapIndexed { i, (v, why) -> video(inp, id, v, prefix = "${i + 1}. ", note = why) }
        }
    }

    /** «❓ Knowledge gaps»: what the audience asks about but the videos do not cover. */
    private fun gaps(inp: KInput): KNode? {
        val r = latest(inp, GAPS) ?: return null
        val list = parseGaps(r.text)
        if (list.isEmpty()) return null
        val id = "map/gaps"
        return KNode(id, "❓ " + t("Пробелы в знаниях", "Knowledge gaps"), t("найдено: ", "found: ") + list.size, info = r.text) {
            list.mapIndexed { i, (title, why) -> KNode("$id/$i", title, why.take(120), info = if (why.isBlank()) title else "$title\n\n$why") }
        }
    }

    private val stepRe = Regex("""^\s*\d+\s*[.)]\s*\[([^\]]+)]\s*[—–:-]?\s*(.*)$""")
    private val bulletRe = Regex("""^\s*(?:[-•*]|\d+\s*[.)])\s+(.+)$""")

    /** Lines "1. [videoId] why" → videos in that order; unknown ids and repeats are dropped. */
    fun parsePath(text: String, videos: List<Video>): List<Pair<Video, String>> {
        val byId = videos.associateBy { it.id }
        val seen = HashSet<String>()
        return text.lines().mapNotNull { line ->
            val m = stepRe.find(line) ?: return@mapNotNull null
            val v = byId[m.groupValues[1].trim()] ?: return@mapNotNull null
            if (!seen.add(v.id)) null else v to m.groupValues[2].replace("**", "").trim()
        }
    }

    /** Lines "- Gap — why" → (gap, why). Markdown bold is removed, headings are skipped. */
    fun parseGaps(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val body = bulletRe.find(line)?.groupValues?.get(1)?.replace("**", "")?.trim() ?: return@mapNotNull null
        if (body.length < 3) return@mapNotNull null
        val cut = listOf(" — ", " – ", " - ", ": ").map { body.indexOf(it) to it }.filter { it.first > 0 }.minByOrNull { it.first }
        if (cut == null) body to "" else body.substring(0, cut.first).trim() to body.substring(cut.first + cut.second.length).trim()
    }

    /** Videos for the learning-path prompt: "[id] title — channel, length, views: summary", most viewed first. */
    fun pathData(videos: List<Video>, summaries: Map<String, String>, limit: Int): String {
        val sb = StringBuilder()
        for (v in videos.sortedByDescending { it.views }) {
            val len = when {
                v.isShort -> "Shorts"
                v.duration > 0 -> "${(v.duration + 59) / 60} min"
                else -> ""
            }
            val about = (summaries[v.id] ?: v.description).replace('\n', ' ').trim().take(220)
            val line = "[${v.id}] ${v.title} — " + listOf(v.channel, len, "${maxOf(v.views, 0L)} views").filter { it.isNotBlank() }.joinToString(", ") +
                (if (about.isNotBlank()) ": $about" else "") + "\n"
            if (sb.length + line.length > limit) break
            sb.append(line)
        }
        return sb.toString()
    }

    /** A video opens into its AI summary, description, tags and top comments. */
    fun video(inp: KInput, parent: String, v: Video, prefix: String = "", note: String = ""): KNode {
        val id = "$parent/v:" + key(v.id)
        val sum = inp.summaries[v.id]
        val sub = note.ifBlank {
            listOf(v.channel, Fmt.num(v.views) + t(" просм.", " views"), Fmt.date(v.uploadMs)).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val info = (if (note.isNotBlank()) note + "\n\n" else "") +
            v.title + " — " + v.channel + " (" + Fmt.num(v.views) + " views, " + Fmt.date(v.uploadMs) + ")\n" +
            (sum?.take(1200) ?: v.description.take(400))
        return KNode(id, prefix + v.title.ifBlank { v.id }, sub, info.trim(), v.url, count = 1,
            fresh = if (inp.since > 0 && v.addedAt > inp.since) 1 else 0) {
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
        ),
    KMap.GAPS to (
        "Тема исследования YouTube: \"{phrase}\" ({domain}). Ниже карта знаний проекта: аспекты, видео, вопросы и боли аудитории, комментарии.\n" +
            "Найди пробелы в знаниях на языке {lang}, только по данным: о чём аудитория спрашивает или спорит, но видео этого не раскрывают; " +
            "важные подтемы, которых нет на карте; противоречия без ответа.\n" +
            "Формат строго: 5-10 строк вида\n- Название пробела — почему это пробел и на какие данные опираешься\n" +
            "Без вступления и заключения.\n\n{data}"
        ),
    KMap.PATH to (
        "Тема исследования YouTube: \"{phrase}\" ({domain}). Ниже видео проекта: [id] название — канал, длительность, просмотры: сводка.\n" +
            "Составь путь обучения на языке {lang}: 5-12 видео в порядке просмотра, от основ к продвинутому, без повторов по смыслу.\n" +
            "Формат строго, по строке на шаг:\n1. [id] что даёт этот шаг и почему он здесь\n" +
            "id копируй точно из данных. Без вступления и заключения.\n\n{data}"
        )
)

/** One placed node of the radial map: unit coordinates (ring n has radius n), parent index, top-level branch. */
class KPos(val node: KNode, val parent: Int, val depth: Int, val x: Double, val y: Double, val branch: Int)

/**
 * Radial layout of the open part of a tree: the root in the centre, its children on ring 1, theirs on ring 2…
 * Every node gets an angular sector proportional to the number of visible leaves under it, so an opened
 * branch takes the room it needs and the siblings move aside.
 */
object KRadial {
    fun layout(root: KNode, open: Set<String>, maxKids: Int = 24): List<KPos> {
        fun kids(n: KNode) = if (n.id == root.id || n.id in open) n.children.take(maxKids) else emptyList()
        val weights = HashMap<String, Int>()
        fun w(n: KNode): Int = weights[n.id] ?: kids(n).sumOf { w(it) }.coerceAtLeast(1).also { weights[n.id] = it }
        val out = mutableListOf(KPos(root, -1, 0, 0.0, 0.0, -1))
        fun place(n: KNode, idx: Int, depth: Int, a0: Double, a1: Double, branch: Int) {
            val ks = kids(n)
            if (ks.isEmpty()) return
            val total = ks.sumOf { w(it) }.toDouble()
            var a = a0
            ks.forEachIndexed { i, k ->
                val span = (a1 - a0) * w(k) / total
                val mid = a + span / 2
                val r = (depth + 1).toDouble()
                val b = if (depth == 0) i else branch
                out += KPos(k, idx, depth + 1, r * cos(mid), r * sin(mid), b)
                place(k, out.size - 1, depth + 1, a, a + span, b)
                a += span
            }
        }
        place(root, 0, 0, -PI / 2, 3 * PI / 2, -1)
        return out
    }
}

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

    /** Map-wide answer ([KMap.GAPS] or [KMap.PATH]); cached by the data. */
    fun mapTask(p: Project, task: String, data: String, force: Boolean): AiResult {
        val prov = AiTasks.requireProvider()
        val key = KMap.summaryKey(task, prov.smartModel, Prompts.get(task), data, Settings.lang)
        if (!force) Store.ai(p.id, key)?.let { return it }
        val prompt = Prompts.fill(task, mapOf("phrase" to p.phrase, "domain" to p.domain.ifBlank { "-" }, "lang" to lang(), "data" to data))
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, maxTokens = 2500, task = task, pid = p.id)
        return AiResult(key, task, "map", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }
}

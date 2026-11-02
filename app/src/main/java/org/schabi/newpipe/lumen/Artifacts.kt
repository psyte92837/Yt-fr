/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * Every collected artifact of a project in one file, without any AI: channels, videos (title, link, numbers,
 * tags, description), transcripts and comments. Markdown for people and chat AIs, JSON for programs.
 * Written as a stream, so a big project does not have to fit in memory. Format: lumen.artifacts v1.
 * The pure part (markdown/json over a [Source]) is covered by ArtifactsTest.
 */
object Artifacts {
    const val PROTOCOL = "lumen.artifacts"
    const val VERSION = 1

    data class Opts(
        val comments: Boolean = true,
        val transcripts: Boolean = true,
        val descriptions: Boolean = true,
        /** Comment authors' names; off by default — the file is meant to be passed on. */
        val authors: Boolean = false,
        /** Comments per video, 0 = all. */
        val maxComments: Int = 0,
        /** Everything else the project holds: AI results and analysis data (graphs, niches, topic, language game…). */
        val extras: Boolean = false,
        /** Characters per extra file; a bigger one is cut with a note. */
        val extraMax: Int = 400_000
    )

    /** One more collected thing: [section] "ai" (an AI result) or "data" (a project file). */
    class Extra(val section: String, val title: String, val file: String, val text: String)

    class Source(
        val meta: JSONObject,
        val videos: List<Video>,
        val channels: List<ChannelData>,
        val comments: (String) -> List<Comment>,
        val transcript: (String) -> String?,
        val extras: () -> List<Extra> = { emptyList() }
    )

    // ------------------------------------------------------------------ extras
    /** Human names of known project files, in the order they go into the file. */
    val LABELS = linkedMapOf(
        "project.json" to "Настройки проекта",
        "topic.json" to "Профиль темы",
        "recs.json" to "Граф рекомендаций (видео → видео)",
        "chgraph.json" to "Граф каналов",
        "recs_snaps.json" to "Снимки графа по дням",
        "algo_niches.json" to "Ниши: названия",
        "algo_labels.json" to "Разметка: по теме / не по теме",
        "algo_me.json" to "Мой канал в рекомендациях",
        "algo_opps.json" to "Возможности",
        "algo_radar.json" to "Радар",
        "predict.json" to "Предсказания",
        "audit.json" to "Аудит рекомендаций",
        "audit_graph.json" to "Граф аудита",
        "audit_cfg.json" to "Настройки аудита",
        "langgame.json" to "Языковая игра аудитории",
        "langgame_last.json" to "Языковая игра: последний текст",
        "harvest.json" to "Сбор",
        "ideas.json" to "Идеи",
        "quick.json" to "Быстрый разбор",
        "digests.json" to "Дайджесты",
        "rejected.json" to "Отклонённые видео",
        "watch_langs.json" to "Языки наблюдения",
        "chgraph_seeds.txt" to "Стартовые каналы графа"
    )
    private val PREFIX = listOf("style_" to "Стиль видео", "voc_ai/" to "Словарь аудитории (ИИ)", "diagrams/" to "Схема")

    /** Already in the main part or not text: not repeated as extras. */
    fun extraFile(path: String): Boolean {
        val p = path.replace('\\', '/')
        val name = p.substringAfterLast('/')
        if (name.startsWith(".") || name.endsWith(".tmp")) return false
        if (p == "videos.json" || p == "channels.json") return false
        if (p.startsWith("comments/") || p.startsWith("transcripts/") || p.startsWith("ai/")) return false
        return name.substringAfterLast('.', "").lowercase() in setOf("json", "txt", "md", "csv", "tsv")
    }

    fun label(path: String): String = LABELS[path] ?: PREFIX.firstOrNull { path.startsWith(it.first) }?.let { (pre, l) ->
        l + " · " + path.removePrefix(pre).substringBeforeLast('.')
    } ?: path

    /** Files in reading order: known ones first (as in [LABELS]), then the rest by name. */
    fun orderFiles(paths: Collection<String>): List<String> {
        val known = LABELS.keys.toList()
        return paths.filter(::extraFile).sortedWith(compareBy<String>({ known.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
    }

    /** JSON without spaces (fewer tokens), anything else trimmed; cut to [max] with a note. */
    fun compact(text: String, max: Int): String {
        val t = text.trim()
        val c = when {
            t.startsWith("{") -> runCatching { JSONObject(t).toString() }.getOrDefault(t)
            t.startsWith("[") -> runCatching { JSONArray(t).toString() }.getOrDefault(t)
            else -> t
        }
        return if (max <= 0 || c.length <= max) c else c.take(max) + "\n… [обрезано: показано $max из ${c.length} символов]"
    }

    private fun extrasOf(src: Source, o: Opts): List<Extra> = if (o.extras) src.extras().filter { it.text.isNotBlank() } else emptyList()

    private fun aiTitle(src: Source, kind: String, target: String, model: String, at: Long): String {
        val what = if (target.isBlank() || target == "project") "проект" else src.videos.firstOrNull { it.id == target }?.title?.let(::one) ?: target
        return listOf(kind, what, model, if (at > 0) Fmt.dateTime(at) else "").filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** An AI result as an extra; the title names the video, not its id. */
    fun aiExtra(src: Source, key: String, kind: String, target: String, model: String, at: Long, text: String) =
        Extra("ai", aiTitle(src, kind, target, model, at), "ai/$key.json", text)

    private fun cleanTranscript(s: String) = s.replace("\r", "").replace(Regex("""\n{3,}"""), "\n\n").trim()

    private fun pickComments(src: Source, v: Video, o: Opts): List<Comment> {
        if (!o.comments) return emptyList()
        val all = src.comments(v.id).filter { it.text.isNotBlank() }
        return if (o.maxComments > 0) all.sortedByDescending { it.likes }.take(o.maxComments) else all
    }

    private fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    // ------------------------------------------------------------------ Markdown
    fun markdown(src: Source, o: Opts, out: Appendable, now: Long = System.currentTimeMillis(), plain: Boolean = false) {
        val m = src.meta
        fun h(level: Int, text: String) {
            if (!plain) out.append("#".repeat(level)).append(' ').append(text).append('\n')
            else when (level) {
                1 -> out.append(text).append('\n').append("=".repeat(minOf(text.length, 60))).append('\n')
                2 -> out.append(text.uppercase()).append('\n').append("-".repeat(minOf(text.length, 60))).append('\n')
                else -> out.append("▌ ").append(text).append('\n')
            }
        }
        fun b(text: String) = if (plain) text else "**$text**"
        h(1, m.optString("name").ifBlank { m.optString("phrase") }.ifBlank { "Lumen" })
        out.append('\n')
        out.append(if (plain) "" else "> ").append("$PROTOCOL v$VERSION · ").append(Fmt.dateTime(now)).append(if (o.extras) " · исходные тексты и цифры с YouTube, выводы ИИ и данные анализа Lumen.\n\n" else " · собрано Lumen без анализа ИИ: только исходные тексты и цифры с YouTube.\n\n")
        if (m.optString("phrase").isNotBlank()) out.append(b("Тема:") + " ").append(m.optString("phrase")).append('\n')
        if (m.optString("domain").isNotBlank()) out.append(b("Область:") + " ").append(m.optString("domain")).append('\n')
        val nc = src.videos.sumOf { pickComments(src, it, o).size }
        val nt = if (o.transcripts) src.videos.count { !src.transcript(it.id).isNullOrBlank() } else 0
        out.append(b("Состав:") + " каналов ").append(src.channels.size.toString()).append(", видео ").append(src.videos.size.toString())
            .append(", расшифровок ").append(nt.toString()).append(", комментариев ").append(nc.toString()).append("\n\n")
        val extras = extrasOf(src, o)
        if (o.extras) {
            out.append(b("Как читать:") + " файл собран для анализа в другом ИИ. Разделы: «Каналы»; «Видео» — ссылка, цифры, теги, описание, расшифровка, комментарии; " +
                "«Выводы ИИ» — что уже написал ИИ в Lumen (это мнение, не факт); «Данные анализа» — файлы проекта в JSON (граф рекомендаций, ниши, профиль темы и др.). " +
                "Цифры — на момент сбора.\n\n")
        }

        if (src.channels.isNotEmpty()) {
            h(2, "Каналы")
            out.append('\n')
            for (c in src.channels) {
                h(3, one(c.name.ifBlank { c.url }))
                out.append("- ссылка: ").append(c.url).append('\n')
                if (c.subscribers >= 0) out.append("- подписчиков: ").append(c.subscribers.toString()).append('\n')
                if (o.descriptions && c.description.isNotBlank()) out.append("- описание: ").append(one(c.description)).append('\n')
                val recent = c.recent.filter { it.title.isNotBlank() }
                if (recent.isNotEmpty()) {
                    out.append("- последние видео:\n")
                    recent.forEach { r ->
                        out.append("  - ").append(one(r.title))
                        if (r.views >= 0) out.append(" (").append(r.views.toString()).append(" просм.)")
                        out.append('\n')
                    }
                }
                out.append('\n')
            }
        }

        h(2, "Видео")
        out.append('\n')
        src.videos.forEachIndexed { i, v ->
            h(3, (i + 1).toString() + ". " + one(v.title.ifBlank { v.id }))
            out.append("- ссылка: ").append(v.url.ifBlank { "https://www.youtube.com/watch?v=" + v.id }).append('\n')
            if (v.channel.isNotBlank()) out.append("- канал: ").append(one(v.channel)).append('\n')
            val date = if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText
            if (date.isNotBlank()) out.append("- дата: ").append(date).append('\n')
            val nums = listOfNotNull(
                v.views.takeIf { it >= 0 }?.let { "просмотров $it" },
                v.likes.takeIf { it >= 0 }?.let { "лайков $it" },
                v.duration.takeIf { it > 0 }?.let { "длительность ${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                if (v.isShort) "Shorts" else null
            )
            if (nums.isNotEmpty()) out.append("- ").append(nums.joinToString(", ")).append('\n')
            if (v.tags.isNotEmpty()) out.append("- теги: ").append(v.tags.joinToString(", ")).append('\n')
            if (o.descriptions && v.description.isNotBlank()) {
                out.append("\n" + b("Описание") + "\n\n").append(v.description.trim()).append('\n')
            }
            if (o.transcripts) src.transcript(v.id)?.takeIf { it.isNotBlank() }?.let {
                out.append("\n" + b("Расшифровка") + "\n\n").append(cleanTranscript(it)).append('\n')
            }
            val cm = pickComments(src, v, o)
            if (cm.isNotEmpty()) {
                out.append("\n" + b("Комментарии (${cm.size})") + "\n\n")
                for (c in cm) {
                    out.append(if (c.isReply) "  - " else "- ")
                    if (o.authors && c.author.isNotBlank()) out.append(one(c.author)).append(": ")
                    out.append(one(c.text))
                    if (c.likes > 0) out.append(" [").append(c.likes.toString()).append(" ♥]")
                    out.append('\n')
                }
            }
            out.append("\n---\n\n")
        }
        val ai = extras.filter { it.section == "ai" }
        if (ai.isNotEmpty()) {
            h(2, "Выводы ИИ (${ai.size})")
            out.append('\n')
            ai.forEach { e -> h(3, e.title); out.append('\n').append(e.text.trim()).append("\n\n") }
        }
        val data = extras.filter { it.section != "ai" }
        if (data.isNotEmpty()) {
            h(2, "Данные анализа (${data.size})")
            out.append('\n')
            data.forEach { e ->
                h(3, e.title)
                out.append(if (plain) "файл: ${e.file}\n" else "`${e.file}`\n\n")
                val text = compact(e.text, o.extraMax)
                if (plain) out.append(text).append("\n\n")
                else out.append(if (text.startsWith("{") || text.startsWith("[")) "```json\n" else "```\n").append(text).append("\n```\n\n")
            }
        }
    }

    // ------------------------------------------------------------------ JSON
    fun channelJson(c: ChannelData, o: Opts): JSONObject = JSONObject().put("url", c.url).put("name", c.name)
        .put("subscribers", c.subscribers).put("description", if (o.descriptions) c.description else "")
        .put("recent", JSONArray().also { a -> c.recent.filter { it.title.isNotBlank() }.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("views", it.views)) } })

    fun videoJson(src: Source, v: Video, o: Opts): JSONObject {
        val j = JSONObject().put("id", v.id).put("url", v.url).put("title", v.title).put("channel", v.channel)
            .put("channelUrl", v.channelUrl).put("uploadMs", v.uploadMs).put("uploadText", v.uploadText)
            .put("views", v.views).put("likes", v.likes).put("duration", v.duration).put("isShort", v.isShort)
            .put("tags", JSONArray(v.tags))
        if (o.descriptions) j.put("description", v.description)
        if (o.transcripts) src.transcript(v.id)?.takeIf { it.isNotBlank() }?.let { j.put("transcript", cleanTranscript(it)) }
        if (o.comments) j.put("comments", JSONArray().also { a ->
            pickComments(src, v, o).forEach { c ->
                val e = JSONObject().put("text", c.text).put("likes", c.likes).put("reply", c.isReply)
                if (c.dateMs > 0) e.put("dateMs", c.dateMs) else if (c.dateText.isNotBlank()) e.put("date", c.dateText)
                if (o.authors) e.put("author", c.author)
                a.put(e)
            }
        })
        return j
    }

    /** One JSON object written piece by piece: header, channels, then one video at a time. */
    fun json(src: Source, o: Opts, out: Appendable, now: Long = System.currentTimeMillis()) {
        out.append("{\"protocol\":").append(JSONObject.quote(PROTOCOL)).append(",\"version\":").append(VERSION.toString())
            .append(",\"at\":").append(now.toString()).append(",\"ai\":").append(o.extras.toString())
            .append(",\"project\":").append(src.meta.toString())
            .append(",\"options\":").append(JSONObject().put("comments", o.comments).put("transcripts", o.transcripts)
                .put("descriptions", o.descriptions).put("authors", o.authors).put("maxComments", o.maxComments).put("extras", o.extras).toString())
            .append(",\n\"channels\":[")
        src.channels.forEachIndexed { i, c -> out.append(if (i == 0) "\n" else ",\n").append(channelJson(c, o).toString()) }
        out.append("],\n\"videos\":[")
        src.videos.forEachIndexed { i, v -> out.append(if (i == 0) "\n" else ",\n").append(videoJson(src, v, o).toString()) }
        out.append("]")
        if (o.extras) {
            out.append(",\n\"extras\":[")
            extrasOf(src, o).forEachIndexed { i, e ->
                val j = JSONObject().put("section", e.section).put("title", e.title).put("file", e.file)
                val text = if (e.section == "ai") e.text.trim() else compact(e.text, o.extraMax)
                val parsed = if (e.section != "ai" && text.length == compact(e.text, 0).length) runCatching {
                    if (text.startsWith("{")) JSONObject(text) else if (text.startsWith("[")) JSONArray(text) else null
                }.getOrNull() else null
                if (parsed != null) j.put("data", parsed) else j.put("text", text)
                out.append(if (i == 0) "\n" else ",\n").append(j.toString())
            }
            out.append("]")
        }
        out.append("}\n")
    }

    // ------------------------------------------------------------------ formats
    enum class Format(val id: String, val ext: String, val mime: String, val ru: String, val en: String, val hintRu: String, val hintEn: String) {
        PDF("pdf", "pdf", "application/pdf", "PDF", "PDF", "страницы под экран телефона: карточки видео, расшифровки, комментарии", "phone-sized pages: video cards, transcripts, comments"),
        PNG("png", "png", "image/png", "Картинки", "Images", "страницы-картинки 1080×1920 альбомом в мессенджер (до 40)", "1080×1920 page images as an album (up to 40)"),
        HTML("html", "html", "text/html", "HTML-страница", "HTML page", "открывается в любом браузере, оглавление, сворачиваемые блоки", "opens in any browser, contents, collapsible blocks"),
        MD("md", "md", "text/markdown", "Markdown", "Markdown", "для чатов с ИИ и заметок (Obsidian, Notion)", "for AI chats and notes (Obsidian, Notion)"),
        TXT("txt", "txt", "text/plain", "Текст", "Text", "простой текст без разметки", "plain text without markup"),
        JSON("json", "json", "application/json", "JSON", "JSON", "для программ и ИИ, которые читают файлы", "for programs and AIs that read files");

        val kind get() = "artifacts_$id"

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id || it.kind == id } ?: MD
        }
    }

    /** "1 234 567". */
    fun num(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

    fun duration(sec: Long) = if (sec >= 3600) "${sec / 3600}:${((sec % 3600) / 60).toString().padStart(2, '0')}:${(sec % 60).toString().padStart(2, '0')}"
        else "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    /** Numbers of a video in one line: 👁 views · 👍 likes · ⏱ length · 💬 comments. */
    fun stats(v: Video, comments: Int): String = listOfNotNull(
        v.views.takeIf { it >= 0 }?.let { "👁 " + num(it) },
        v.likes.takeIf { it >= 0 }?.let { "👍 " + num(it) },
        v.duration.takeIf { it > 0 }?.let { "⏱ " + duration(it) },
        comments.takeIf { it > 0 }?.let { "💬 $it" },
        if (v.isShort) "Shorts" else null
    ).joinToString(" · ")

    fun commentLine(c: Comment, authors: Boolean): String =
        (if (c.isReply) "   ↳ " else "• ") + (if (c.likes > 0) "♥${c.likes}  " else "") +
            (if (authors && c.author.isNotBlank()) one(c.author) + ": " else "") + one(c.text)

    /**
     * Long text cut into pieces of at most [max] chars: by paragraphs, then lines, then sentences, then words,
     * so a picture or PDF page never gets a block taller than the page.
     */
    fun chunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out += cur.toString().trim()
            cur.clear()
        }
        fun add(piece: String, sep: String) {
            if (cur.isNotEmpty() && cur.length + sep.length + piece.length > max) flush()
            if (cur.isNotEmpty()) cur.append(sep)
            cur.append(piece)
        }
        fun split(p: String, level: Int, sep: String) {
            if (p.length <= max) return add(p, sep)
            if (level >= 3) {
                p.split(' ').forEach { w -> if (w.length > max) w.chunked(max).forEach { add(it, " ") } else add(w, " ") }
                return
            }
            val (parts, next) = when (level) {
                0 -> p.split(Regex("""\n\s*\n""")) to "\n\n"
                1 -> p.split('\n') to "\n"
                else -> p.split(Regex("""(?<=[.!?…])\s+""")) to " "
            }
            if (parts.size == 1) return split(p, level + 1, sep)
            parts.filter { it.isNotBlank() }.forEachIndexed { i, part -> split(part.trim(), level + 1, if (i == 0) sep else next) }
        }
        split(text.replace("\r", "").trim(), 0, "")
        flush()
        return out
    }

    // ------------------------------------------------------------------ HTML
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CSS = ":root{--bg:#fef7ff;--card:#f3edf7;--on:#1d1b20;--mut:#49454f;--acc:#6750a4;--soft:#e8def8;--line:#cac4d0}" +
        "@media(prefers-color-scheme:dark){:root{--bg:#141218;--card:#211f26;--on:#e6e0e9;--mut:#cac4d0;--acc:#d0bcff;--soft:#4a4458;--line:#49454f}}" +
        "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--on);font:16px/1.55 system-ui,-apple-system,Roboto,sans-serif}" +
        "main{max-width:820px;margin:0 auto;padding:16px}a{color:var(--acc)}" +
        ".hero{background:var(--acc);color:#fff;border-radius:24px;padding:22px 20px;margin:8px 0 16px}.hero h1{margin:4px 0;font-size:26px;line-height:1.15}" +
        ".hero small{opacity:.85;text-transform:uppercase;letter-spacing:.08em;font-size:11px}.hero p{margin:6px 0 0;opacity:.92}" +
        "@media(prefers-color-scheme:dark){.hero{color:#21005d}}" +
        ".kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px;margin:0 0 16px}" +
        ".kpi{background:var(--card);border-radius:16px;padding:12px 14px}.kpi b{display:block;font-size:22px}.kpi span{color:var(--mut);font-size:13px}" +
        "h2{margin:28px 0 10px;padding-left:10px;border-left:4px solid var(--acc)}" +
        ".card{background:var(--card);border-radius:18px;padding:14px 16px;margin:10px 0}.card h3{margin:0 0 4px;font-size:18px;line-height:1.3}" +
        ".meta{color:var(--mut);font-size:14px}.tags span{display:inline-block;background:var(--soft);border-radius:999px;padding:1px 9px;margin:3px 4px 0 0;font-size:13px}" +
        "details{margin-top:10px;border-top:1px solid var(--line);padding-top:8px}summary{cursor:pointer;font-weight:600}" +
        ".pre{white-space:pre-wrap;font-size:15px}.c{margin:6px 0}.c.r{margin-left:22px;color:var(--mut)}.likes{color:var(--acc);font-weight:600;margin-right:6px}" +
        "ol.toc{padding-left:22px}ol.toc li{margin:2px 0}footer{color:var(--mut);font-size:12px;margin:24px 0}"

    /** Self-contained page: light/dark theme, contents, video cards, collapsible description, transcript and comments. */
    fun html(src: Source, o: Opts, out: Appendable, now: Long = System.currentTimeMillis()) {
        val m = src.meta
        val title = m.optString("name").ifBlank { m.optString("phrase") }.ifBlank { "Lumen" }
        val counts = src.videos.associate { it.id to pickComments(src, it, o) }
        val nt = if (o.transcripts) src.videos.count { !src.transcript(it.id).isNullOrBlank() } else 0
        out.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>").append(esc(title)).append("</title><style>").append(CSS).append("</style></head><body><main>")
        out.append("<div class=\"hero\"><small>Lumen · артефакты без ИИ</small><h1>").append(esc(title)).append("</h1>")
        listOf(m.optString("phrase"), m.optString("domain")).filter { it.isNotBlank() && it != title }.forEach { out.append("<p>").append(esc(it)).append("</p>") }
        out.append("<p>").append(Fmt.dateTime(now)).append(" · $PROTOCOL v$VERSION</p></div>")
        out.append("<div class=\"kpis\">")
        listOf(
            src.channels.size.toLong() to "каналов", src.videos.size.toLong() to "видео", nt.toLong() to "расшифровок",
            counts.values.sumOf { it.size }.toLong() to "комментариев", src.videos.sumOf { maxOf(it.views, 0L) } to "просмотров"
        ).forEach { (n, l) -> out.append("<div class=\"kpi\"><b>").append(num(n)).append("</b><span>").append(l).append("</span></div>") }
        out.append("</div>")
        if (src.videos.size > 1) {
            out.append("<h2>Содержание</h2><ol class=\"toc\">")
            src.videos.forEachIndexed { i, v -> out.append("<li><a href=\"#v").append((i + 1).toString()).append("\">").append(esc(one(v.title.ifBlank { v.id }))).append("</a></li>") }
            out.append("</ol>")
        }
        if (src.channels.isNotEmpty()) {
            out.append("<h2>Каналы</h2>")
            for (c in src.channels) {
                out.append("<div class=\"card\"><h3><a href=\"").append(esc(c.url)).append("\">").append(esc(one(c.name.ifBlank { c.url }))).append("</a></h3>")
                if (c.subscribers >= 0) out.append("<div class=\"meta\">👥 ").append(num(c.subscribers)).append(" подписчиков</div>")
                if (o.descriptions && c.description.isNotBlank()) out.append("<details><summary>Описание</summary><div class=\"pre\">").append(esc(c.description.trim())).append("</div></details>")
                val recent = c.recent.filter { it.title.isNotBlank() }
                if (recent.isNotEmpty()) {
                    out.append("<details><summary>Последние видео (").append(recent.size.toString()).append(")</summary><ul>")
                    recent.forEach { r -> out.append("<li>").append(esc(one(r.title))).append(if (r.views >= 0) " <span class=\"meta\">👁 " + num(r.views) + "</span>" else "").append("</li>") }
                    out.append("</ul></details>")
                }
                out.append("</div>")
            }
        }
        out.append("<h2>Видео</h2>")
        src.videos.forEachIndexed { i, v ->
            val cm = counts[v.id].orEmpty()
            val url = v.url.ifBlank { "https://www.youtube.com/watch?v=" + v.id }
            out.append("<div class=\"card\" id=\"v").append((i + 1).toString()).append("\"><h3>").append((i + 1).toString()).append(". <a href=\"").append(esc(url)).append("\">")
                .append(esc(one(v.title.ifBlank { v.id }))).append("</a></h3><div class=\"meta\">")
            val date = if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText
            out.append(esc(listOf(v.channel, date).filter { it.isNotBlank() }.joinToString(" · "))).append("<br>").append(esc(stats(v, cm.size))).append("</div>")
            if (v.tags.isNotEmpty()) out.append("<div class=\"tags\">").append(v.tags.joinToString("") { "<span>#" + esc(it) + "</span>" }).append("</div>")
            if (o.descriptions && v.description.isNotBlank()) out.append("<details><summary>Описание</summary><div class=\"pre\">").append(esc(v.description.trim())).append("</div></details>")
            if (o.transcripts) src.transcript(v.id)?.takeIf { it.isNotBlank() }?.let {
                out.append("<details><summary>Расшифровка</summary><div class=\"pre\">").append(esc(cleanTranscript(it))).append("</div></details>")
            }
            if (cm.isNotEmpty()) {
                out.append("<details><summary>Комментарии (").append(cm.size.toString()).append(")</summary>")
                cm.forEach { c ->
                    out.append("<div class=\"c").append(if (c.isReply) " r" else "").append("\">")
                    if (c.likes > 0) out.append("<span class=\"likes\">♥").append(c.likes.toString()).append("</span>")
                    if (o.authors && c.author.isNotBlank()) out.append("<b>").append(esc(one(c.author))).append(":</b> ")
                    out.append(esc(one(c.text))).append("</div>")
                }
                out.append("</details>")
            }
            out.append("</div>")
        }
        val extras = extrasOf(src, o)
        val ai = extras.filter { it.section == "ai" }
        if (ai.isNotEmpty()) {
            out.append("<h2>Выводы ИИ (").append(ai.size.toString()).append(")</h2>")
            ai.forEach { e -> out.append("<div class=\"card\"><h3>").append(esc(e.title)).append("</h3><div class=\"pre\">").append(esc(e.text.trim())).append("</div></div>") }
        }
        val data = extras.filter { it.section != "ai" }
        if (data.isNotEmpty()) {
            out.append("<h2>Данные анализа (").append(data.size.toString()).append(")</h2>")
            data.forEach { e ->
                out.append("<div class=\"card\"><h3>").append(esc(e.title)).append("</h3><div class=\"meta\">").append(esc(e.file))
                    .append("</div><details><summary>Показать</summary><div class=\"pre\">").append(esc(compact(e.text, o.extraMax))).append("</div></details></div>")
            }
        }
        out.append("<footer>").append(if (o.extras) "Исходные тексты и цифры с YouTube, выводы ИИ и данные анализа Lumen." else "Собрано Lumen без анализа ИИ: только исходные тексты и цифры с YouTube.")
            .append("</footer></main></body></html>\n")
    }

    // ------------------------------------------------------------------ Android side
    fun source(pid: String): Source {
        val p = Store.project(pid)
        val meta = JSONObject().put("name", p?.name.orEmpty()).put("phrase", p?.phrase.orEmpty()).put("domain", p?.domain.orEmpty())
        val videos = Store.videos(pid).values.sortedByDescending { it.views }
        val channels = runCatching { ChannelStore.all(pid).values.toList() }.getOrDefault(emptyList())
        lateinit var src: Source
        src = Source(meta, videos, channels, { runCatching { Store.comments(pid, it) }.getOrDefault(emptyList()) }, { Store.transcript(pid, it) }) {
            Store.aiAll(pid).map { aiExtra(src, it.key, it.kind, it.target, it.model, it.at, it.text) } +
                orderFiles(Store.listFiles(pid)).mapNotNull { f -> Store.readFile(pid, f)?.let { Extra("data", label(f), f, it) } }
        }
        return src
    }

    var opts: Opts
        get() = Lumen.prefs.let {
            Opts(it.getBoolean("art_comments", true), it.getBoolean("art_transcripts", true), it.getBoolean("art_desc", true),
                it.getBoolean("art_authors", false), it.getInt("art_max_comments", 0), it.getBoolean("art_extras", true))
        }
        set(v) = Lumen.prefs.edit().putBoolean("art_comments", v.comments).putBoolean("art_transcripts", v.transcripts)
            .putBoolean("art_desc", v.descriptions).putBoolean("art_authors", v.authors).putInt("art_max_comments", v.maxComments)
            .putBoolean("art_extras", v.extras).apply()

    var format: Format
        get() = Format.of(Lumen.prefs.getString("art_format", "pdf").orEmpty())
        set(v) = Lumen.prefs.edit().putString("art_format", v.id).apply()

    /** Report blocks for PDF and page pictures. */
    fun blocks(src: Source, o: Opts, now: Long = System.currentTimeMillis()): List<ReportKit.Block> {
        val m = src.meta
        val out = ArrayList<ReportKit.Block>()
        val title = m.optString("name").ifBlank { m.optString("phrase") }.ifBlank { "Lumen" }
        out += ReportKit.Hero(t("Lumen · артефакты без ИИ", "Lumen · artifacts, no AI"), title,
            listOf(m.optString("phrase"), m.optString("domain")).filter { it.isNotBlank() && it != title }.joinToString(" · "), Fmt.dateTime(now))
        val counts = src.videos.associate { it.id to pickComments(src, it, o) }
        val nt = if (o.transcripts) src.videos.count { !src.transcript(it.id).isNullOrBlank() } else 0
        out += ReportKit.Kpis(listOf(
            Triple(num(src.videos.size.toLong()), t("видео", "videos"), ReportKit.ACCENT),
            Triple(num(src.channels.size.toLong()), t("каналов", "channels"), ReportKit.TERTIARY),
            Triple(num(nt.toLong()), t("расшифровок", "transcripts"), ReportKit.OK),
            Triple(num(counts.values.sumOf { it.size }.toLong()), t("комментариев", "comments"), ReportKit.WARN)
        ))
        // a block taller than a page would be cut off: split the text until every piece fits
        fun para(text: String, size: Float = 13f, color: Int = ReportKit.ON, bg: Int = ReportKit.CARD) {
            fun put(piece: String, depth: Int) {
                val b = ReportKit.Paragraph(piece, size, color, bg)
                if (b.h <= ReportKit.PAGE_H - 70 || depth > 4) out += b
                else chunks(piece, piece.length / 2 + 1).forEach { put(it, depth + 1) }
            }
            chunks(text, 1100).forEach { put(it, 0) }
        }
        if (src.videos.size > 1) {
            out += ReportKit.Section(t("Содержание", "Contents"))
            para(src.videos.mapIndexed { i, v -> "${i + 1}. " + LangGame.clip(v.title.ifBlank { v.id }, 80) }.joinToString("\n"), 12f)
        }
        if (src.channels.isNotEmpty()) {
            out += ReportKit.Section(t("Каналы", "Channels"))
            for (c in src.channels) {
                val sb = StringBuilder("**").append(one(c.name.ifBlank { c.url })).append("**")
                if (c.subscribers >= 0) sb.append("\n👥 ").append(num(c.subscribers))
                sb.append("\n").append(c.url)
                if (o.descriptions && c.description.isNotBlank()) sb.append("\n\n").append(LangGame.clip(c.description, 500))
                val recent = c.recent.filter { it.title.isNotBlank() }.take(8)
                if (recent.isNotEmpty()) sb.append("\n\n").append(recent.joinToString("\n") { "• " + LangGame.clip(it.title, 90) })
                para(sb.toString())
            }
        }
        out += ReportKit.Section(t("Видео", "Videos"))
        src.videos.forEachIndexed { i, v ->
            val cm = counts[v.id].orEmpty()
            val date = if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText
            out += ReportKit.Section("${i + 1}. " + LangGame.clip(v.title.ifBlank { v.id }, 140), listOf(v.channel, date).filter { it.isNotBlank() }.joinToString(" · "))
            val head = StringBuilder(stats(v, cm.size))
            head.append("\n").append(v.url.ifBlank { "https://www.youtube.com/watch?v=" + v.id })
            if (v.tags.isNotEmpty()) head.append("\n").append(v.tags.take(25).joinToString(" ") { "#" + it.replace(' ', '_') })
            para(head.toString(), 12f, ReportKit.MUTED)
            if (o.descriptions && v.description.isNotBlank()) para("**" + t("Описание", "Description") + "**\n" + v.description.trim(), 12f)
            if (o.transcripts) src.transcript(v.id)?.takeIf { it.isNotBlank() }?.let {
                para("**" + t("Расшифровка", "Transcript") + "**\n" + cleanTranscript(it), 12.5f, ReportKit.ON, ReportKit.SOFT)
            }
            if (cm.isNotEmpty()) para("**" + t("Комментарии", "Comments") + " (${cm.size})**\n" + cm.joinToString("\n") { commentLine(it, o.authors) }, 12f)
        }
        val ai = extrasOf(src, o).filter { it.section == "ai" }
        if (ai.isNotEmpty()) {
            out += ReportKit.Section(t("Выводы ИИ", "AI results"), t("данные анализа (графы, ниши) — в MD, TXT или JSON", "analysis data (graphs, niches) is in MD, TXT or JSON"))
            ai.forEach { para("**" + it.title + "**\n" + it.text.trim(), 12f) }
        }
        out += ReportKit.Footer(t("Собрано Lumen без анализа ИИ: только исходные тексты и цифры с YouTube.", "Gathered by Lumen without AI: only source texts and numbers from YouTube."))
        return out
    }

    /** One file of [f] (for PNG — the first page; use [pages] for all of them). */
    fun write(pid: String, f: Format, out: OutputStream, opts: Opts = this.opts) {
        val src = source(pid)
        when (f) {
            Format.PDF -> ReportKit.pdf(blocks(src, opts), out, "Lumen · " + (src.meta.optString("name").ifBlank { "artifacts" }))
            Format.PNG -> ReportKit.png(blocks(src, opts).take(60), out)
            else -> {
                val w = out.bufferedWriter(Charsets.UTF_8)
                when (f) {
                    Format.JSON -> json(src, opts, w)
                    Format.HTML -> html(src, opts, w)
                    Format.TXT -> markdown(src, opts, w, plain = true)
                    else -> markdown(src, opts, w)
                }
                w.flush()
            }
        }
    }

    /** Page pictures into [dir]; returns the files and the total number of pages. */
    fun pages(pid: String, dir: java.io.File, base: String, opts: Opts = this.opts): Pair<List<java.io.File>, Int> {
        val src = source(pid)
        return ReportKit.pngPages(blocks(src, opts), dir, base, "Lumen · " + LangGame.clip(src.meta.optString("name"), 40))
    }
}

/**
 * Format, then options, then the system share sheet (messenger, Drive, Files, an AI app). No AI is used.
 */
fun LumenBase.artifactsDialog(project: Project) {
    val formats = Artifacts.Format.entries
    val labels = formats.map { t(it.ru, it.en) + " — " + t(it.hintRu, it.hintEn) }.toTypedArray()
    var pick = formats.indexOf(Artifacts.format).coerceAtLeast(0)
    AlertDialog.Builder(this).setTitle(t("📦 Формат файла", "📦 File format"))
        .setSingleChoiceItems(labels, pick) { _, w -> pick = w }
        .setPositiveButton(t("Далее", "Next")) { _, _ ->
            Artifacts.format = formats[pick]
            artifactsOptions(project, formats[pick])
        }
        .setNeutralButton(t("🤖 Всё для ИИ", "🤖 All for AI")) { _, _ -> artifactsAllForAi(project) }
        .setNegativeButton(t("Отмена", "Cancel"), null)
        .show()
}

/** One Markdown file with everything the project holds (what chat AIs read best): all comments, transcripts, AI results, analysis data. */
fun LumenBase.artifactsAllForAi(project: Project) = artifactsShare(project, Artifacts.Format.MD, Artifacts.Opts(extras = true))

private fun LumenBase.artifactsOptions(project: Project, f: Artifacts.Format) {
    val o = Artifacts.opts
    val checked = booleanArrayOf(o.comments, o.transcripts, o.descriptions, o.authors, o.maxComments in 1..100, o.extras)
    val items = arrayOf(
        t("Комментарии", "Comments"), t("Расшифровки", "Transcripts"), t("Описания видео и каналов", "Video and channel descriptions"),
        t("Имена авторов комментариев", "Comment authors' names"), t("Только 50 лучших комментариев к видео", "Only the top 50 comments per video"),
        t("Выводы ИИ и данные анализа (граф, ниши, тема, языковая игра…)", "AI results and analysis data (graph, niches, topic, language game…)")
    )
    AlertDialog.Builder(this).setTitle(t(f.ru, f.en) + t(": что включить", ": what to include"))
        .setMultiChoiceItems(items, checked) { _, w, v -> checked[w] = v }
        .setPositiveButton(t("Поделиться ↗", "Share ↗")) { _, _ ->
            Artifacts.opts = Artifacts.Opts(checked[0], checked[1], checked[2], checked[3], if (checked[4]) 50 else 0, checked[5])
            artifactsShare(project, f, Artifacts.opts)
        }
        .setNegativeButton(t("Отмена", "Cancel"), null)
        .show()
}

private fun LumenBase.artifactsShare(project: Project, f: Artifacts.Format, o: Artifacts.Opts) {
    val base = ReportKit.safeName(project.name) + if (o.extras && f != Artifacts.Format.PDF && f != Artifacts.Format.PNG) "-all" else "-artifacts"
    val subject = project.name + t(" — артефакты", " — artifacts")
    bgTask(t("Файл артефактов", "Artifacts file"), {
        if (f == Artifacts.Format.PNG) {
            val dir = java.io.File(ReportKit.shareFile(this, "x").parentFile, "pages")
            Artifacts.pages(project.id, dir, base, o)
        } else {
            val file = ReportKit.shareFile(this, "$base.${f.ext}")
            file.outputStream().use { Artifacts.write(project.id, f, it, o) }
            listOf(file) to 1
        }
    }, { err(it) }) { (files, total) ->
        if (files.isEmpty()) return@bgTask toast(t("Нечего выгружать", "Nothing to export"))
        val size = StudioDrafts.mb(files.sumOf { it.length() })
        toast(if (f == Artifacts.Format.PNG && total > files.size)
            t("Страниц $total, в альбоме первые ${files.size}: всё целиком — в PDF", "$total pages, the album has the first ${files.size}: use PDF for everything")
        else t("Готово: ", "Ready: ") + size)
        ReportKit.shareMany(this, files, subject, f.mime)
    }
}

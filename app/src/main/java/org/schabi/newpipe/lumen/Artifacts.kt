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
        val maxComments: Int = 0
    )

    class Source(
        val meta: JSONObject,
        val videos: List<Video>,
        val channels: List<ChannelData>,
        val comments: (String) -> List<Comment>,
        val transcript: (String) -> String?
    )

    private fun cleanTranscript(s: String) = s.replace("\r", "").replace(Regex("""\n{3,}"""), "\n\n").trim()

    private fun pickComments(src: Source, v: Video, o: Opts): List<Comment> {
        if (!o.comments) return emptyList()
        val all = src.comments(v.id).filter { it.text.isNotBlank() }
        return if (o.maxComments > 0) all.sortedByDescending { it.likes }.take(o.maxComments) else all
    }

    private fun one(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    // ------------------------------------------------------------------ Markdown
    fun markdown(src: Source, o: Opts, out: Appendable, now: Long = System.currentTimeMillis()) {
        val m = src.meta
        out.append("# ").append(m.optString("name").ifBlank { m.optString("phrase") }.ifBlank { "Lumen" }).append("\n\n")
        out.append("> $PROTOCOL v$VERSION · ").append(Fmt.dateTime(now)).append(" · собрано Lumen без анализа ИИ: только исходные тексты и цифры с YouTube.\n\n")
        if (m.optString("phrase").isNotBlank()) out.append("**Тема:** ").append(m.optString("phrase")).append('\n')
        if (m.optString("domain").isNotBlank()) out.append("**Область:** ").append(m.optString("domain")).append('\n')
        val nc = src.videos.sumOf { pickComments(src, it, o).size }
        val nt = if (o.transcripts) src.videos.count { !src.transcript(it.id).isNullOrBlank() } else 0
        out.append("**Состав:** каналов ").append(src.channels.size.toString()).append(", видео ").append(src.videos.size.toString())
            .append(", расшифровок ").append(nt.toString()).append(", комментариев ").append(nc.toString()).append("\n\n")

        if (src.channels.isNotEmpty()) {
            out.append("## Каналы\n\n")
            for (c in src.channels) {
                out.append("### ").append(one(c.name.ifBlank { c.url })).append('\n')
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

        out.append("## Видео\n\n")
        src.videos.forEachIndexed { i, v ->
            out.append("### ").append((i + 1).toString()).append(". ").append(one(v.title.ifBlank { v.id })).append('\n')
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
                out.append("\n**Описание**\n\n").append(v.description.trim()).append('\n')
            }
            if (o.transcripts) src.transcript(v.id)?.takeIf { it.isNotBlank() }?.let {
                out.append("\n**Расшифровка**\n\n").append(cleanTranscript(it)).append('\n')
            }
            val cm = pickComments(src, v, o)
            if (cm.isNotEmpty()) {
                out.append("\n**Комментарии (").append(cm.size.toString()).append(")**\n\n")
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
            .append(",\"at\":").append(now.toString()).append(",\"ai\":false")
            .append(",\"project\":").append(src.meta.toString())
            .append(",\"options\":").append(JSONObject().put("comments", o.comments).put("transcripts", o.transcripts)
                .put("descriptions", o.descriptions).put("authors", o.authors).put("maxComments", o.maxComments).toString())
            .append(",\n\"channels\":[")
        src.channels.forEachIndexed { i, c -> out.append(if (i == 0) "\n" else ",\n").append(channelJson(c, o).toString()) }
        out.append("],\n\"videos\":[")
        src.videos.forEachIndexed { i, v -> out.append(if (i == 0) "\n" else ",\n").append(videoJson(src, v, o).toString()) }
        out.append("]}\n")
    }

    // ------------------------------------------------------------------ Android side
    fun source(pid: String): Source {
        val p = Store.project(pid)
        val meta = JSONObject().put("name", p?.name.orEmpty()).put("phrase", p?.phrase.orEmpty()).put("domain", p?.domain.orEmpty())
        val videos = Store.videos(pid).values.sortedByDescending { it.views }
        val channels = runCatching { ChannelStore.all(pid).values.toList() }.getOrDefault(emptyList())
        return Source(meta, videos, channels, { runCatching { Store.comments(pid, it) }.getOrDefault(emptyList()) }, { Store.transcript(pid, it) })
    }

    var opts: Opts
        get() = Lumen.prefs.let {
            Opts(it.getBoolean("art_comments", true), it.getBoolean("art_transcripts", true), it.getBoolean("art_desc", true),
                it.getBoolean("art_authors", false), it.getInt("art_max_comments", 0))
        }
        set(v) = Lumen.prefs.edit().putBoolean("art_comments", v.comments).putBoolean("art_transcripts", v.transcripts)
            .putBoolean("art_desc", v.descriptions).putBoolean("art_authors", v.authors).putInt("art_max_comments", v.maxComments).apply()

    /** For [Export.write]: kinds "artifacts_md" and "artifacts_json" with the saved options. */
    fun write(pid: String, json: Boolean, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        if (json) json(source(pid), opts, w) else markdown(source(pid), opts, w)
        w.flush()
    }
}

/**
 * Options and format of the artifacts file, then the system share sheet (messenger, Drive, Files, an AI app).
 * No AI is used.
 */
fun LumenBase.artifactsDialog(project: Project) {
    val o = Artifacts.opts
    val checked = booleanArrayOf(o.comments, o.transcripts, o.descriptions, o.authors, o.maxComments in 1..100)
    val items = arrayOf(
        t("Комментарии", "Comments"), t("Расшифровки", "Transcripts"), t("Описания видео и каналов", "Video and channel descriptions"),
        t("Имена авторов комментариев", "Comment authors' names"), t("Только 50 лучших комментариев к видео", "Only the top 50 comments per video")
    )
    fun go(json: Boolean) {
        Artifacts.opts = Artifacts.Opts(checked[0], checked[1], checked[2], checked[3], if (checked[4]) 50 else 0)
        val name = ReportKit.safeName(project.name) + "-artifacts." + if (json) "json" else "md"
        bgTask(t("Файл артефактов", "Artifacts file"), {
            val f = ReportKit.shareFile(this, name)
            f.outputStream().use { Artifacts.write(project.id, json, it) }
            f
        }, { err(it) }) { f ->
            toast(t("Файл ", "File ") + StudioDrafts.mb(f.length()))
            ReportKit.share(this, f, false, project.name + t(" — артефакты", " — artifacts"), if (json) "application/json" else "text/markdown")
        }
    }
    AlertDialog.Builder(this).setTitle(t("📦 Все артефакты файлом", "📦 All artifacts in a file"))
        .setMultiChoiceItems(items, checked) { _, w, v -> checked[w] = v }
        .setPositiveButton(t("Markdown ↗", "Markdown ↗")) { _, _ -> go(false) }
        .setNeutralButton(t("JSON ↗", "JSON ↗")) { _, _ -> go(true) }
        .setNegativeButton(t("Отмена", "Cancel"), null)
        .show()
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/** CSV / JSON / Markdown / PDF export. API keys are never included. */
object Export {
    fun write(ctx: Context, projectId: String, kind: String, out: OutputStream) {
        when (kind) {
            "csv_videos" -> out.write(("﻿" + csvVideos(projectId)).toByteArray())
            "csv_comments" -> out.write(("﻿" + csvComments(projectId)).toByteArray())
            "json" -> out.write(Store.projectJson(projectId).toString(2).toByteArray())
            "md" -> out.write(markdown(projectId).toByteArray())
            "pdf" -> pdf(ctx, projectId, out)
            "voc_csv" -> out.write(("\uFEFF" + vocCsv(projectId)).toByteArray())
            "insights_json" -> out.write(org.json.JSONObject().put("insights", org.json.JSONArray(Seo.insights(projectId))).toString(2).toByteArray())
            "clusters_json" -> out.write(org.json.JSONObject().put("clusters", org.json.JSONArray(Seo.clusters(projectId))).toString(2).toByteArray())
            "gexf" -> out.write(gexf(projectId).toByteArray())
            "recs_nodes" -> out.write(("\uFEFF" + recsNodes(projectId)).toByteArray())
            "recs_edges" -> out.write(("\uFEFF" + recsEdges(projectId)).toByteArray())
            "graph_pdf", "graph_png" -> GraphReport.write(GraphReport.input(projectId, RecGraph.FILE), kind.endsWith("pdf"), out)
            "chgraph_pdf", "chgraph_png" -> GraphReport.write(GraphReport.input(projectId, RecGraph.CHANNELS_FILE), kind.endsWith("pdf"), out)
            "dossier_pdf", "dossier_png" -> DossierReport.write(projectId, kind.endsWith("pdf"), out)
            "pains_pdf", "pains_png" -> Pains.write(projectId, kind.endsWith("pdf"), out)
            else -> CustomReport.Format.ofKind(kind)?.let { CustomReport.write(projectId, it, out) }
        }
    }

    fun csvVideos(pid: String): String {
        val sb = StringBuilder(
            Csv.row(
                "id", "url", "title", "channel", "channel_url", "upload_date", "views", "likes", "duration_sec",
                "comments_collected", "category", "subscribers", "tags", "labels", "marked", "collected",
                "transcript", "query", "description"
            )
        )
        for (v in Store.videos(pid).values) {
            sb.append(
                Csv.row(
                    v.id, v.url, v.title, v.channel, v.channelUrl, if (v.uploadMs > 0) Fmt.isoDay(v.uploadMs) else v.uploadText,
                    v.views, v.likes, v.duration, v.commentsCount, v.category, v.subscribers, v.tags.joinToString("|"),
                    v.labels.joinToString("|"), v.marked, v.collected, v.transcriptSource, v.query, v.description
                )
            )
        }
        return sb.toString()
    }

    fun csvComments(pid: String): String {
        val sb = StringBuilder(Csv.row("video_id", "video_title", "author", "likes", "date", "is_reply", "reply_count", "text"))
        for (v in Store.videos(pid).values) {
            for (c in Store.comments(pid, v.id)) {
                sb.append(Csv.row(v.id, v.title, c.author, c.likes, if (c.dateMs > 0) Fmt.isoDay(c.dateMs) else c.dateText, c.isReply, c.replyCount, c.text))
            }
        }
        return sb.toString()
    }

    fun vocCsv(pid: String): String {
        val vids = Store.videos(pid)
        val sb = StringBuilder(Csv.row("category", "text", "messages", "authors", "likes", "source_id", "source_title", "timestamp", "url"))
        for (it in Seo.localVoc(pid, vids.values, true)) {
            val v = vids[it.sourceId]
            val cm = if (it.commentIndex >= 0) Store.comments(pid, it.sourceId).getOrNull(it.commentIndex) else null
            sb.append(Csv.row(it.category, it.text, it.count, it.authors.size, it.likes, it.sourceId, v?.title, it.timestamp ?: "",
                Seo.sourceUrl(v, cm?.id?.takeIf { s -> s.isNotBlank() }, it.timestamp)))
        }
        return sb.toString()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    /** GEXF 1.3 for Gephi: node attributes depth, hits, pagerank, relevance, channel, views. */
    fun gexf(pid: String): String {
        val g = RecGraph.load(pid)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gexf xmlns=\"http://gexf.net/1.3\" version=\"1.3\">\n")
        sb.append("<meta><creator>Lumen</creator><description>YouTube recommendation graph</description></meta>\n")
        sb.append("<graph defaultedgetype=\"directed\">\n<attributes class=\"node\">\n")
        listOf("depth" to "integer", "hits" to "integer", "pagerank" to "double", "relevant" to "integer",
            "channel" to "string", "views" to "long", "seed" to "boolean", "url" to "string").forEachIndexed { i, (n, ty) ->
            sb.append("<attribute id=\"$i\" title=\"$n\" type=\"$ty\"/>\n")
        }
        sb.append("</attributes>\n<nodes>\n")
        for (n in g.nodes.values) {
            sb.append("<node id=\"${xml(n.id)}\" label=\"${xml(n.title.ifBlank { n.id })}\"><attvalues>")
            listOf(n.depth, n.hits, n.pr, n.relevant, xml(n.channel), n.views, n.seed, xml(n.url)).forEachIndexed { i, v ->
                sb.append("<attvalue for=\"$i\" value=\"$v\"/>")
            }
            sb.append("</attvalues></node>\n")
        }
        sb.append("</nodes>\n<edges>\n")
        g.edges.forEachIndexed { i, e ->
            sb.append("<edge id=\"$i\" source=\"${xml(e.from)}\" target=\"${xml(e.to)}\" weight=\"${1.0 / (e.rank + 1)}\"/>\n")
        }
        sb.append("</edges>\n</graph>\n</gexf>\n")
        return sb.toString()
    }

    fun recsNodes(pid: String): String {
        val sb = StringBuilder(Csv.row("id", "title", "channel", "url", "depth", "hits", "pagerank", "relevant", "seed", "views", "duration_sec", "upload_date"))
        RecGraph.load(pid).nodes.values.forEach { n ->
            sb.append(Csv.row(n.id, n.title, n.channel, n.url, n.depth, n.hits, n.pr, n.relevant, n.seed, n.views, n.duration,
                if (n.uploadMs > 0) Fmt.isoDay(n.uploadMs) else n.uploadText))
        }
        return sb.toString()
    }

    fun recsEdges(pid: String): String {
        val sb = StringBuilder(Csv.row("source", "target", "position"))
        RecGraph.load(pid).edges.forEach { sb.append(Csv.row(it.from, it.to, it.rank + 1)) }
        return sb.toString()
    }

    fun markdown(pid: String): String {
        val p = Store.project(pid) ?: return ""
        val vids = Store.videos(pid).values.toList()
        val s = Analytics.stats(vids)
        val sb = StringBuilder()
        sb.append("# ").append(p.name).append("\n\n")
        sb.append("**").append(t("Тема", "Topic")).append(":** ").append(p.phrase)
        if (p.domain.isNotBlank()) sb.append(" · ").append(p.domain)
        sb.append("  \n**").append(t("Запросы", "Queries")).append(":** ").append(p.effectiveQueries().joinToString(", "))
        sb.append("  \n**").append(t("Дата отчёта", "Report date")).append(":** ").append(Fmt.dateTime(System.currentTimeMillis())).append("\n\n")
        sb.append("## ").append(t("Обзор", "Overview")).append("\n\n")
        sb.append("- ").append(t("Видео", "Videos")).append(": ").append(s.total).append(" (").append(t("собрано", "collected")).append(" ").append(s.collected).append(")\n")
        sb.append("- ").append(t("Просмотры", "Views")).append(": ").append(Fmt.num(s.totalViews)).append(", ").append(t("в среднем", "avg")).append(" ").append(Fmt.num(s.avgViews)).append("\n")
        sb.append("- ").append(t("Период", "Period")).append(": ").append(Fmt.date(s.firstMs)).append(" — ").append(Fmt.date(s.lastMs)).append("\n")
        sb.append("- ").append(t("Комментариев", "Comments")).append(": ").append(s.comments).append("\n\n")

        Store.latestAi(pid, "project_summary", "project")?.let {
            sb.append("## ").append(t("AI-сводка", "AI summary")).append("\n\n").append(it.text.trim()).append("\n\n")
        }
        Store.latestAi(pid, "clusters", "project")?.let {
            sb.append("## ").append(t("Кластеры", "Clusters")).append("\n\n").append(it.text.trim()).append("\n\n")
        }
        Store.latestAi(pid, "sentiment", "project")?.let {
            sb.append("## ").append(t("Тональность комментариев", "Comment sentiment")).append("\n\n").append(it.text.trim()).append("\n\n")
        }

        val voc = Seo.localVoc(pid, vids, false)
        if (voc.isNotEmpty()) {
            sb.append("## ").append(t("Голос аудитории (сигналы из комментариев)", "Voice of the audience (comment signals)")).append("\n\n")
            sb.append("_").append(t("Гипотезы и формулировки аудитории, не доказательство поискового спроса.", "Hypotheses and audience language, not proof of search demand.")).append("_\n\n")
            for (cat in Voc.categories) {
                val l = voc.filter { it.category == cat }.take(5)
                if (l.isEmpty()) continue
                sb.append("**").append(cat).append("**\n\n")
                l.forEach { sb.append("- «").append(it.text.take(200).replace('\n', ' ')).append("»").append(if (it.count > 1) " ×${it.count}" else "").append('\n') }
                sb.append('\n')
            }
        }
        val topics = Seo.clusters(pid)
        if (topics.isNotEmpty()) {
            sb.append("## ").append(t("Темы аудитории", "Audience topics")).append("\n\n")
            topics.sortedByDescending { it.optInt("mention_count") }.forEach {
                sb.append("- **").append(it.optString("name")).append("** — ").append(it.optString("need"))
                    .append(" (").append(t("сообщений", "messages")).append(" ").append(it.optInt("mention_count"))
                    .append(", ").append(Seo.stage(it)).append(")\n")
            }
            sb.append('\n')
        }
        val rg = RecGraph.load(pid)
        if (rg.nodes.isNotEmpty()) {
            sb.append("## ").append(t("Что рекомендует YouTube", "What YouTube recommends")).append("\n\n")
            RecEngine.ranked(rg, "hits").take(10).forEachIndexed { i, n ->
                sb.append(i + 1).append(". [").append(n.title.replace("]", ")")).append("](").append(n.url).append(") — ")
                    .append(n.channel).append(", ×").append(n.hits).append(", ").append(t("шаг ", "step ")).append(n.depth).append('\n')
            }
            sb.append('\n')
        }
        sb.append("## ").append(t("Топ каналов", "Top channels")).append("\n\n| # | ").append(t("Канал", "Channel"))
            .append(" | ").append(t("Видео", "Videos")).append(" | ").append(t("Просмотры", "Views")).append(" |\n|---|---|---|---|\n")
        Analytics.topChannels(vids, "videos", 15).forEachIndexed { i, c ->
            sb.append("| ").append(i + 1).append(" | ").append(c.name.replace("|", "/")).append(" | ").append(c.videos).append(" | ").append(Fmt.num(c.views)).append(" |\n")
        }
        sb.append("\n## ").append(t("Топ видео по просмотрам", "Top videos by views")).append("\n\n")
        Analytics.topVideos(vids, "views", 15).forEachIndexed { i, v ->
            sb.append(i + 1).append(". [").append(v.title.replace("]", ")")).append("](").append(v.url).append(") — ")
                .append(v.channel).append(", ").append(Fmt.num(v.views)).append("\n")
        }
        sb.append("\n## ").append(t("Быстрорастущие", "Fastest growing")).append("\n\n")
        Analytics.topVideos(vids, "velocity", 10).forEachIndexed { i, v ->
            sb.append(i + 1).append(". [").append(v.title.replace("]", ")")).append("](").append(v.url).append(") — ")
                .append(Fmt.num(Analytics.velocity(v))).append(t("/день", "/day")).append("\n")
        }
        sb.append("\n## ").append(t("Частые слова", "Frequent words")).append("\n\n")
        sb.append(Words.top(Analytics.textsOf(pid, vids, "descriptions"), 30).joinToString(", ") { "${it.first} (${it.second})" }).append("\n\n")
        val bi = Words.topBigrams(Analytics.textsOf(pid, vids, "descriptions"), 15)
        if (bi.isNotEmpty()) sb.append(t("Словосочетания: ", "Phrases: ")).append(bi.joinToString(", ") { "${it.first} (${it.second})" }).append("\n\n")

        sb.append("## ").append(t("Тренд", "Trend")).append("\n\n| ").append(t("Период", "Period")).append(" | ")
            .append(t("Видео", "Videos")).append(" | ").append(t("Просмотры", "Views")).append(" |\n|---|---|---|\n")
        Analytics.trend(vids).forEach { b -> sb.append("| ").append(b.label).append(" | ").append(b.count).append(" | ").append(Fmt.num(b.views)).append(" |\n") }

        val summaries = vids.mapNotNull { v -> Store.latestAi(pid, "video_summary", v.id)?.let { v to it } }
        if (summaries.isNotEmpty()) {
            sb.append("\n## ").append(t("Сводки по видео", "Video summaries")).append("\n\n")
            summaries.forEach { (v, r) ->
                sb.append("### [").append(v.title.replace("]", ")")).append("](").append(v.url).append(")\n\n").append(r.text.trim()).append("\n\n")
            }
        }
        sb.append("\n---\n").append(t("Сгенерировано в Lumen (форк NewPipe, GPL-3.0)", "Generated with Lumen (a NewPipe fork, GPL-3.0)")).append('\n')
        return sb.toString()
    }

    /** A4 PDF: trend chart on the first page, then the Markdown report as text. */
    fun pdf(ctx: Context, pid: String, out: OutputStream) {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val margin = 40f
        val density = 1f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f }
        val md = markdown(pid)
            .replace(Regex("""\[([^\]]+)]\((https?://[^)]+)\)"""), "$1")
            .replace("**", "").replace(Regex("(?m)^#+ "), "")
            .replace(Regex("(?m)^\\|---.*$\\n?"), "")
        val vids = Store.videos(pid).values
        val trend = Analytics.trend(vids)

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var canvas = page.canvas
        canvas.drawText(Store.project(pid)?.name ?: "Lumen", margin, margin + 16, title)
        var y = margin + 36
        if (trend.isNotEmpty()) {
            body.isFakeBoldText = true
            canvas.drawText(t("Тренд: число видео по времени", "Trend: number of videos over time"), margin, y + 10, body)
            body.isFakeBoldText = false
            ChartDraw.bars(canvas, RectF(margin, y + 16, w - margin, y + 176), trend.map { it.count.toDouble() }, trend.map { it.label },
                0xFF6750A4.toInt(), 0xFF333333.toInt(), density)
            y += 196
        }
        @Suppress("DEPRECATION")
        val layout = StaticLayout(md, body, (w - 2 * margin).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false)
        var line = 0
        while (line < layout.lineCount) {
            val available = h - margin - y
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= available) last++
            if (last == line) last = line + 1
            canvas.save()
            canvas.translate(margin, y - layout.getLineTop(line))
            canvas.clipRect(0f, layout.getLineTop(line).toFloat(), w.toFloat(), layout.getLineTop(last).toFloat())
            layout.draw(canvas)
            canvas.restore()
            line = last
            if (line < layout.lineCount) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                canvas = page.canvas
                y = margin
            }
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

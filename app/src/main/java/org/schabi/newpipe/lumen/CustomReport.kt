/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.io.OutputStream

/** Editable prompt of the custom report (added to [Prompts.defaults]). */
val CUSTOM_REPORT_PROMPTS = linkedMapOf(
    "custom_report" to (
        "Тема исследования: \"{phrase}\" ({domain}). Ниже данные проекта.\n" +
            "Запрос пользователя к отчёту: {request}\n" +
            "Составь отчёт на языке {lang}, строго по данным, ничего не выдумывай. Если данных для пункта запроса нет, скажи об этом одной строкой.\n" +
            "Формат ответа — только Markdown из такого набора:\n" +
            "первая строка «# Заголовок отчёта»; сразу за ней одно короткое предложение-подзаголовок;\n" +
            "разделы «## Название»; обычные абзацы; списки «- пункт»; вывод или совет в строке «> текст»;\n" +
            "ключевые цифры по одной в строке «[kpi] значение | подпись» (от 2 до 6 подряд);\n" +
            "таблицы Markdown до 12 строк (если нужен рейтинг, последняя колонка — число); **жирный** для главного.\n" +
            "Без HTML, без блоков кода, без картинок и ссылок, не пересказывай запрос.\n\n{data}"
        )
)

/**
 * Custom report: the user describes what they want, the AI answers in a small Markdown dialect,
 * the app parses it and packs it as PDF / PNG (phone layout), Markdown, HTML or plain text.
 */
object CustomReport {
    enum class Format(val ext: String, val mime: String, val ru: String, val en: String) {
        PDF("pdf", "application/pdf", "PDF", "PDF"),
        PNG("png", "image/png", "Картинка PNG", "PNG image"),
        MD("md", "text/markdown", "Markdown", "Markdown"),
        HTML("html", "text/html", "HTML-страница", "HTML page"),
        TXT("txt", "text/plain", "Простой текст", "Plain text");

        val visual get() = this == PDF || this == PNG
        val kind get() = "custom_$ext"

        companion object {
            fun ofKind(kind: String): Format? = values().firstOrNull { it.kind == kind }
        }
    }

    sealed class Item {
        data class Section(val title: String) : Item()
        data class Para(val text: String) : Item()
        data class Bullets(val items: List<String>, val ordered: Boolean) : Item()
        data class Callout(val text: String) : Item()
        data class Kpis(val items: List<Pair<String, String>>) : Item()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Item()
    }

    class Doc(val title: String, val sub: String, val items: List<Item>)

    // ------------------------------------------------------------------ parsing

    private val fence = Regex("^\\s*```[A-Za-z]*\\s*\\n|\\n?```\\s*$")
    private val think = Regex("(?is)<think(ing)?>.*?</think(ing)?>")
    private val bullet = Regex("^([-*•]|\\d+[.)])\\s+(.*)$")
    private val kpiLine = Regex("^(\\[kpi]|kpi:)\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val tableSep = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val rule = Regex("^([-*_])\\1{2,}$")

    /** Drops reasoning tags and a code fence the model may wrap the whole answer in. */
    fun clean(raw: String): String = raw.replace(think, "").trim().replace(fence, "").trim()

    /** Links, code ticks and italics are removed; **bold** stays for [ReportKit.rich]. */
    fun inline(s: String): String = s
        .replace(Regex("!?\\[([^\\]]*)]\\(([^)]*)\\)"), "$1")
        .replace("`", "")
        .replace("~~", "")
        .replace(Regex("__(.+?)__"), "**$1**")
        .replace(Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)"), "$1")
        .trim()

    private fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split('|').map { inline(it) }
    }

    fun parse(markdown: String): Doc {
        val lines = clean(markdown).replace("\r", "").lines()
        val items = ArrayList<Item>()
        var title: String? = null
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) items += Item.Para(inline(para.joinToString(" "))).also { para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.isEmpty() -> { flush(); i++ }
                rule.matches(line) -> { flush(); i++ }
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length
                    val text = inline(line.drop(level).trim().trimEnd('#'))
                    if (text.isNotBlank()) {
                        if (title == null && level == 1) title = text else items += Item.Section(text)
                    }
                    i++
                }
                kpiLine.matches(line) -> {
                    flush()
                    val k = ArrayList<Pair<String, String>>()
                    while (i < lines.size) {
                        val m = kpiLine.matchEntire(lines[i].trim()) ?: break
                        val parts = m.groupValues[2].split('|', limit = 2)
                        val v = inline(parts[0])
                        if (v.isNotBlank()) k += v to inline(parts.getOrElse(1) { "" })
                        i++
                    }
                    if (k.isNotEmpty()) items += Item.Kpis(k)
                }
                line.startsWith(">") -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        q += lines[i].trim().trimStart('>').trim()
                        i++
                    }
                    inline(q.filter { it.isNotEmpty() }.joinToString(" ")).takeIf { it.isNotBlank() }?.let { items += Item.Callout(it) }
                }
                bullet.matches(line) -> {
                    flush()
                    val ordered = line[0].isDigit()
                    val b = ArrayList<String>()
                    while (i < lines.size) {
                        val l = lines[i].trim()
                        val m = bullet.matchEntire(l)
                        if (m != null) {
                            b += inline(m.groupValues[2])
                            i++
                        } else if (l.isNotEmpty() && b.isNotEmpty() && lines[i].startsWith(" ") && !l.startsWith("#") && !l.startsWith("|")) {
                            b[b.lastIndex] = b.last() + " " + inline(l) // wrapped continuation of the previous item
                            i++
                        } else break
                    }
                    items += Item.Bullets(b.filter { it.isNotBlank() }, ordered)
                }
                line.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1].trim()) -> {
                    flush()
                    val head = cells(line)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows += cells(lines[i]).let { r -> List(head.size) { r.getOrElse(it) { "" } } }
                        i++
                    }
                    items += Item.Table(head, rows)
                }
                else -> { para += line; i++ }
            }
        }
        flush()
        var sub = ""
        val first = items.firstOrNull()
        if (title != null && first is Item.Para && first.text.length <= 220) {
            sub = first.text
            items.removeAt(0)
        }
        return Doc(title.orEmpty(), sub, items)
    }

    /** "12 345", "1,2K", "45%", "3.5M" → number; null when the cell is not numeric. */
    fun number(cell: String): Double? {
        val s = cell.trim().replace(" ", "").replace(" ", "").replace("%", "")
        val m = Regex("^([+-]?\\d+(?:[.,]\\d+)?)([kKкКmMмМ])?$").matchEntire(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "k", "к" -> v * 1_000
            "m", "м" -> v * 1_000_000
            else -> v
        }
    }

    // ------------------------------------------------------------------ text outputs

    fun plain(d: Doc): String {
        val sb = StringBuilder()
        if (d.title.isNotBlank()) sb.append(d.title.uppercase()).append('\n')
        if (d.sub.isNotBlank()) sb.append(strip(d.sub)).append('\n')
        for (it in d.items) {
            sb.append('\n')
            when (it) {
                is Item.Section -> sb.append(strip(it.title)).append('\n').append("-".repeat(strip(it.title).length.coerceIn(3, 40))).append('\n')
                is Item.Para -> sb.append(strip(it.text)).append('\n')
                is Item.Bullets -> it.items.forEachIndexed { n, s -> sb.append(if (it.ordered) "${n + 1}. " else "• ").append(strip(s)).append('\n') }
                is Item.Callout -> sb.append("» ").append(strip(it.text)).append('\n')
                is Item.Kpis -> it.items.forEach { (v, l) -> sb.append(strip(v)).append(if (l.isBlank()) "" else " — " + strip(l)).append('\n') }
                is Item.Table -> {
                    sb.append(it.header.joinToString(" | ") { c -> strip(c) }).append('\n')
                    it.rows.forEach { r -> sb.append(r.joinToString(" | ") { c -> strip(c) }).append('\n') }
                }
            }
        }
        return sb.toString().trim() + "\n"
    }

    private fun strip(s: String) = s.replace("**", "")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(s: String): String = esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")

    fun html(d: Doc, footer: String): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(d.title.ifBlank { "Lumen" })).append("</title><style>")
        sb.append("body{margin:0;background:#fef7ff;color:#1d1b20;font:16px/1.5 system-ui,sans-serif}main{max-width:720px;margin:0 auto;padding:16px}")
        sb.append(".hero{background:#6750a4;color:#fff;border-radius:24px;padding:24px 20px;margin:8px 0 16px}.hero h1{margin:0 0 6px;font-size:26px}.hero p{margin:0;opacity:.9}")
        sb.append("h2{font-size:19px;margin:24px 0 8px;padding-left:10px;border-left:4px solid #6750a4}p,ul,ol,table{background:#f3edf7;border-radius:16px;padding:12px 16px;margin:8px 0}")
        sb.append("ul,ol{padding-left:36px}blockquote{background:#e8def8;color:#1d192b;border-radius:16px;padding:12px 16px;margin:8px 0}")
        sb.append(".kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px}.kpi{background:#f3edf7;border-radius:16px;padding:12px 14px}")
        sb.append(".kpi b{display:block;font-size:22px}.kpi span{color:#49454f;font-size:13px}table{border-collapse:collapse;width:100%;display:block;overflow-x:auto}")
        sb.append("th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #cac4d0}footer{color:#79747e;font-size:12px;margin:24px 0}</style></head><body><main>")
        sb.append("<div class=\"hero\"><h1>").append(html(d.title.ifBlank { "Lumen" })).append("</h1>")
        if (d.sub.isNotBlank()) sb.append("<p>").append(html(d.sub)).append("</p>")
        sb.append("</div>")
        for (it in d.items) when (it) {
            is Item.Section -> sb.append("<h2>").append(html(it.title)).append("</h2>")
            is Item.Para -> sb.append("<p>").append(html(it.text)).append("</p>")
            is Item.Bullets -> {
                val tag = if (it.ordered) "ol" else "ul"
                sb.append('<').append(tag).append('>')
                it.items.forEach { s -> sb.append("<li>").append(html(s)).append("</li>") }
                sb.append("</").append(tag).append('>')
            }
            is Item.Callout -> sb.append("<blockquote>").append(html(it.text)).append("</blockquote>")
            is Item.Kpis -> {
                sb.append("<div class=\"kpis\">")
                it.items.forEach { (v, l) -> sb.append("<div class=\"kpi\"><b>").append(html(v)).append("</b><span>").append(html(l)).append("</span></div>") }
                sb.append("</div>")
            }
            is Item.Table -> {
                sb.append("<table><tr>")
                it.header.forEach { c -> sb.append("<th>").append(html(c)).append("</th>") }
                sb.append("</tr>")
                it.rows.forEach { r ->
                    sb.append("<tr>")
                    r.forEach { c -> sb.append("<td>").append(html(c)).append("</td>") }
                    sb.append("</tr>")
                }
                sb.append("</table>")
            }
        }
        sb.append("<footer>").append(esc(footer)).append("</footer></main></body></html>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ phone layout

    fun blocks(d: Doc, footer: String): List<ReportKit.Block> {
        val out = ArrayList<ReportKit.Block>()
        out += ReportKit.Hero("Lumen · " + t("Свой отчёт", "Custom report"), d.title.ifBlank { t("Отчёт", "Report") }, strip(d.sub), Fmt.dateTime(System.currentTimeMillis()))
        val accents = listOf(ReportKit.ACCENT, ReportKit.TERTIARY, ReportKit.WARN, ReportKit.OK, ReportKit.OUTLINE, ReportKit.BAD)
        for (it in d.items) when (it) {
            is Item.Section -> out += ReportKit.Section(strip(it.title))
            is Item.Para -> out += ReportKit.Paragraph(it.text)
            is Item.Bullets -> out += ReportKit.Paragraph(it.items.mapIndexed { n, s -> (if (it.ordered) "${n + 1}.  " else "•  ") + s }.joinToString("\n"))
            is Item.Callout -> out += ReportKit.Insight("💡", it.text)
            is Item.Kpis -> out += ReportKit.Kpis(it.items.take(8).mapIndexed { n, (v, l) -> Triple(strip(v), strip(l), accents[n % accents.size]) })
            is Item.Table -> out += tableBlocks(it)
        }
        out += ReportKit.Footer(footer)
        return out
    }

    /** A table with a numeric last column becomes a ranking with bars, any other table becomes one text card per row. */
    private fun tableBlocks(tb: Item.Table): List<ReportKit.Block> {
        if (tb.rows.isEmpty()) return emptyList()
        val last = tb.header.lastIndex
        val nums = tb.rows.map { number(it.getOrElse(last) { "" }) }
        if (last >= 1 && nums.all { it != null } && tb.rows.size >= 2) {
            val rows = tb.rows.zip(nums).take(12).map { (r, v) ->
                val title = r.firstOrNull { c -> number(c) == null && c.isNotBlank() } ?: r.first()
                val sub = r.drop(1).take(last - 1).filter { c -> c != title }.joinToString(" · ") { c -> strip(c) }
                ReportKit.Ranking.Row(strip(title), sub, v!!, strip(r[last]))
            }
            return listOf(ReportKit.Ranking(rows))
        }
        return listOf(ReportKit.Paragraph(tb.rows.joinToString("\n\n") { r ->
            r.mapIndexed { n, c -> if (c.isBlank()) "" else (tb.header.getOrElse(n) { "" }.takeIf { h -> h.isNotBlank() && n > 0 }?.let { h -> "$h: " } ?: "") + c }
                .filter { c -> c.isNotBlank() }.let { cs -> if (cs.isEmpty()) "" else "**" + strip(cs.first()) + "**" + cs.drop(1).joinToString("") { c -> "\n" + c } }
        }.trim()))
    }

    // ------------------------------------------------------------------ AI + storage

    const val KIND = "custom_report"

    /** Short summary of the project for the prompt, bounded by the chunk size. */
    fun digest(pid: String): String {
        val limit = Settings.chunkChars * 3
        val p = Store.project(pid) ?: return ""
        val vids = Store.videos(pid).values.toList()
        val s = Analytics.stats(vids)
        val sb = StringBuilder()
        sb.append("Project: ").append(p.name).append("; queries: ").append(p.effectiveQueries().joinToString(", ")).append('\n')
        sb.append("Videos ").append(s.total).append(" (collected ").append(s.collected).append("), views ").append(s.totalViews)
            .append(", avg ").append(s.avgViews).append(", comments ").append(s.comments)
        if (s.firstMs > 0) sb.append(", period ").append(Fmt.date(s.firstMs)).append(" — ").append(Fmt.date(s.lastMs))
        sb.append("\n\nTop channels:\n")
        Analytics.topChannels(vids, "views", 10).forEach { sb.append("- ").append(it.name).append(": ").append(it.videos).append(" videos, ").append(it.views).append(" views\n") }
        sb.append("\nTop videos:\n")
        val top = Analytics.topVideos(vids, "views", 15)
        top.forEach { sb.append("- ").append(it.title).append(" | ").append(it.channel).append(" | ").append(it.views).append(" views | ").append(Fmt.date(it.uploadMs)).append('\n') }
        val trend = Analytics.trend(vids)
        if (trend.isNotEmpty()) {
            sb.append("\nTrend (period: videos, views):\n")
            trend.takeLast(24).forEach { sb.append(it.label).append(": ").append(it.count).append(", ").append(it.views).append('\n') }
        }
        fun ai(label: String, kind: String, target: String, max: Int) {
            Store.latestAi(pid, kind, target)?.text?.takeIf { it.isNotBlank() }?.let { sb.append("\n").append(label).append(":\n").append(it.trim().take(max)).append('\n') }
        }
        ai("AI summary", "project_summary", "project", 3500)
        ai("Clusters", "clusters", "project", 2500)
        ai("Comment sentiment", "sentiment", "project", 1500)
        ai("Dossier overview", "dossier_overview", "project", 2500)
        val topics = try { Seo.clusters(pid) } catch (e: Exception) { emptyList() }
        if (topics.isNotEmpty()) {
            sb.append("\nAudience topics:\n")
            topics.sortedByDescending { it.optInt("mention_count") }.take(12).forEach {
                sb.append("- ").append(it.optString("name")).append(": ").append(it.optString("need")).append(" (").append(it.optInt("mention_count")).append(" mentions)\n")
            }
        }
        sb.append("\nVideo notes:\n")
        for (v in top) {
            if (sb.length > limit) break
            sb.append("# ").append(v.title).append('\n')
            (Store.latestAi(pid, "video_summary", v.id)?.text ?: v.description).trim().take(450).replace('\n', ' ').takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            Store.comments(pid, v.id).filter { !it.isReply && !Voc.isSpam(it.text) }.sortedByDescending { it.likes }.take(3).forEach {
                sb.append("  comment (").append(it.likes).append("♥): ").append(it.text.replace('\n', ' ').take(200)).append('\n')
            }
        }
        return sb.toString().take(limit)
    }

    /** One AI request; the answer is stored as the latest custom report of the project. Returns the cleaned Markdown. */
    fun generate(pid: String, request: String): String {
        val p = Store.project(pid) ?: throw IllegalStateException("no project")
        val prov = Provider.active() ?: throw IllegalStateException(t("AI не подключён", "AI not connected"))
        val vars = mapOf(
            "phrase" to p.phrase, "domain" to p.domain.ifBlank { "-" }, "request" to request.trim(),
            "lang" to if (Settings.lang == "en") "English" else "русский", "data" to digest(pid)
        )
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("custom_report", vars), maxTokens = 6000, task = KIND, pid = pid)
        val md = clean(r.text)
        if (parse(md).let { it.items.isEmpty() && it.title.isBlank() }) throw LlmException(t("AI вернул пустой отчёт", "The AI returned an empty report"), r.text)
        val head = request.trim().replace('\n', ' ').take(120)
        Store.saveAi(pid, AiResult(sha1("$KIND|$pid|${System.nanoTime()}"), KIND, head, r.model, md, r.inTokens, r.outTokens))
        return md
    }

    fun latest(pid: String): AiResult? = Store.aiAll(pid).firstOrNull { it.kind == KIND }

    fun write(pid: String, f: Format, out: OutputStream) {
        val md = latest(pid)?.text ?: return
        val name = Store.project(pid)?.name.orEmpty()
        val footer = "Lumen · " + t("свой отчёт", "custom report") + " · " + name
        val d = parse(md)
        val note = t("Составлено AI по данным проекта в Lumen, возможны неточности.", "Written by AI from the project data in Lumen, may contain inaccuracies.")
        when (f) {
            Format.PDF, Format.PNG -> ReportKit.write(blocks(d, "$note ${Fmt.dateTime(System.currentTimeMillis())}"), f == Format.PDF, out, footer)
            Format.MD -> out.write((md.trim() + "\n\n---\n_" + note + "_\n").toByteArray())
            Format.HTML -> out.write(html(d, "$note $footer").toByteArray())
            Format.TXT -> out.write(plain(d).toByteArray())
        }
    }
}

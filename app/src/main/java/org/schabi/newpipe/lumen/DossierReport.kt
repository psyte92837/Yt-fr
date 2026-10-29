/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import org.schabi.newpipe.lumen.ReportKit.ACCENT
import org.schabi.newpipe.lumen.ReportKit.BAD
import org.schabi.newpipe.lumen.ReportKit.CARD
import org.schabi.newpipe.lumen.ReportKit.INNER
import org.schabi.newpipe.lumen.ReportKit.MUTED
import org.schabi.newpipe.lumen.ReportKit.OK
import org.schabi.newpipe.lumen.ReportKit.ON
import org.schabi.newpipe.lumen.ReportKit.OUTLINE_SOFT
import org.schabi.newpipe.lumen.ReportKit.PAD
import org.schabi.newpipe.lumen.ReportKit.SOFT
import org.schabi.newpipe.lumen.ReportKit.W
import org.schabi.newpipe.lumen.ReportKit.drawAt
import java.io.OutputStream
import kotlin.math.max

/** Mobile report "channels and videos": AI dossier with local numbers; works partly without AI. */
object DossierReport {
    fun write(pid: String, pdf: Boolean, out: OutputStream) =
        ReportKit.write(blocks(pid), pdf, out, "Lumen · " + t("досье", "dossier") + " · " + (Store.project(pid)?.name ?: ""))

    /** Counts for the export screen: channels in scope and how many already have an AI card. */
    fun status(pid: String): Pair<Int, Int> {
        val cfg = Dossier.Config.load()
        val groups = Dossier.group(Store.videos(pid).values, cfg.channels, cfg.videos)
        val st = Dossier.stored(pid)
        return groups.size to groups.count { st[it.key]?.second != null }
    }

    fun blocks(pid: String): List<ReportKit.Block> {
        val p = Store.project(pid)
        val vids = Store.videos(pid).values.toList()
        val cfg = Dossier.Config.load()
        val groups = Dossier.group(vids, cfg.channels, cfg.videos)
        val st = Dossier.stored(pid)
        val cards = groups.associate { it.key to st[it.key]?.second }
        val shown = groups.flatMap { it.videos }
        val out = ArrayList<ReportKit.Block>()
        out += ReportKit.Hero(
            "Lumen · " + t("Досье каналов и видео", "Channels & videos dossier"), p?.name ?: "Lumen", p?.phrase ?: "",
            Fmt.dateTime(System.currentTimeMillis()) + " · " + t("каналов: ", "channels: ") + groups.size + " · " + t("видео: ", "videos: ") + shown.size
        )
        // mood over all videos with an AI card
        var pos = 0
        var neg = 0
        var neu = 0
        var moods = 0
        val cardOf = HashMap<String, Dossier.VideoCard>()
        for (g in groups) {
            val c = cards[g.key] ?: continue
            val ids = st[g.key]?.first.orEmpty()
            for (vc in c.videos) {
                val id = ids.getOrNull(vc.n - 1) ?: g.videos.getOrNull(vc.n - 1)?.id ?: continue
                cardOf[id] = vc
                if (vc.hasMood) { pos += vc.pos; neg += vc.neg; neu += vc.neu; moods++ }
            }
        }
        out += ReportKit.Kpis(
            listOf(
                Triple("${groups.size}", t("каналов", "channels"), ACCENT),
                Triple("${shown.size}", t("видео в досье", "videos in dossier"), ReportKit.TERTIARY),
                Triple(Fmt.num(shown.sumOf { maxOf(0, it.views) }), t("просмотров", "views"), ReportKit.WARN),
                Triple(Fmt.num(shown.sumOf { it.commentsCount }.toLong()), t("комментариев собрано", "comments collected"), OK),
                Triple("${shown.count { it.hasTranscript }}", t("с расшифровкой", "with transcript"), ReportKit.OUTLINE),
                Triple(if (moods > 0) "${Math.round(pos.toDouble() / moods)}%" else "—", t("позитив в комментариях", "positive comments"), OK)
            )
        )
        val ready = cards.values.count { it != null }
        if (ready < groups.size) {
            out += ReportKit.Insight(
                "ℹ️",
                t("AI-описание готово для $ready из ${groups.size} каналов. Остальные показаны по собранным данным — нажмите «Составить досье» во вкладке «Экспорт».",
                    "AI description is ready for $ready of ${groups.size} channels. The rest are shown from collected data — tap “Build dossier” in the Export tab."),
                ReportKit.CARD_HI
            )
        }
        Dossier.overview(pid)?.let { o ->
            out += ReportKit.Section(t("Главное", "Key points"), t("Общая картина по всем каналам.", "The big picture across all channels."))
            if (o.summary.isNotBlank()) out += ReportKit.Paragraph(o.summary, 13.5f)
            if (o.themes.isNotEmpty()) out += ReportKit.Insight("💡", t("**Главные темы:**\n", "**Main themes:**\n") + o.themes.joinToString("\n") { "• $it" })
            if (o.leaders.isNotBlank()) out += ReportKit.Insight("👑", t("**Кто задаёт тон.** ", "**Who sets the tone.** ") + o.leaders)
            if (o.audience.isNotBlank()) out += ReportKit.Insight("💬", t("**Что волнует зрителей.** ", "**What viewers care about.** ") + o.audience)
            if (o.gaps.isNotEmpty()) out += ReportKit.Insight("🕳", t("**Незакрытые темы и идеи:**\n", "**Gaps and ideas:**\n") + o.gaps.joinToString("\n") { "• $it" }, 0xFFFFE0B2.toInt())
        }
        if (groups.size >= 2) {
            out += ReportKit.Section(t("Каналы по просмотрам", "Channels by views"), t("Сумма просмотров видео канала в проекте.", "Total views of the channel's videos in the project."))
            out += ReportKit.Ranking(groups.sortedByDescending { it.views }.take(10).map {
                ReportKit.Ranking.Row(it.name, t("видео: ", "videos: ") + it.videos.size, it.views.toDouble(), Fmt.num(it.views))
            })
        }
        if (moods > 0) {
            out += ReportKit.Section(t("Настроение комментариев", "Comment mood"), t("Среднее по видео, где AI оценил комментарии.", "Average over videos where the AI rated comments."))
            out += ReportKit.Split(
                listOf(
                    Triple(OK, t("позитив", "positive"), Math.round(pos.toDouble() / moods).toInt()),
                    Triple(OUTLINE_SOFT, t("нейтрально", "neutral"), Math.round(neu.toDouble() / moods).toInt()),
                    Triple(BAD, t("негатив", "negative"), Math.round(neg.toDouble() / moods).toInt())
                )
            )
        }
        groups.forEachIndexed { gi, g ->
            val info = if (g.url.isNotBlank()) ChannelStore.get(pid, g.url) else null
            val subs = info?.subscribers?.takeIf { it > 0 } ?: g.videos.firstOrNull()?.subscribers?.takeIf { it > 0 }
            val meta = listOfNotNull(
                subs?.let { "👥 " + Fmt.num(it) },
                t("видео в проекте: ", "videos in project: ") + g.videos.size,
                "👁 " + Fmt.num(g.views)
            ).joinToString(" · ")
            out += ChannelHeader(gi + 1, g.name, meta)
            val c = cards[g.key]
            if (c != null) {
                val sb = StringBuilder()
                if (c.about.isNotBlank()) sb.append(c.about)
                fun line(label: String, v: String) { if (v.isNotBlank()) sb.append("\n\n**").append(label).append("** ").append(v) }
                line(t("Аудитория:", "Audience:"), c.audience)
                line(t("Форматы:", "Formats:"), c.formats.joinToString(", "))
                line(t("Подача:", "Delivery:"), c.tone)
                line(t("Сильные стороны:", "Strengths:"), c.strengths)
                line(t("Слабые стороны:", "Weaknesses:"), c.weaknesses)
                out += ReportKit.Paragraph(sb.toString().trim(), 12.5f)
            } else {
                info?.description?.takeIf { it.isNotBlank() }?.let { out += ReportKit.Paragraph(it.take(400).trim(), 12f, MUTED, maxLines = 6) }
            }
            g.videos.forEachIndexed { vi, v -> out += VideoBlock(vi + 1, v, cardOf[v.id], topComment(pid, v)) }
        }
        out += ReportKit.Footer(
            t("Досье составлено в Lumen ", "Dossier made with Lumen ") + Fmt.dateTime(System.currentTimeMillis()) +
                t(". Описания сделаны AI по собранным данным (названия, описания, расшифровки, комментарии) и могут содержать неточности.",
                    ". Descriptions were written by AI from collected data (titles, descriptions, transcripts, comments) and may contain inaccuracies.")
        )
        return out
    }

    private fun topComment(pid: String, v: Video): Comment? =
        Store.comments(pid, v.id).filter { !it.isReply && it.text.length in 20..400 && !Voc.isSpam(it.text) }.maxByOrNull { it.likes }

    /** Big heading of a channel section. */
    private class ChannelHeader(private val no: Int, name: String, meta: String) : ReportKit.Block(keepWithNext = true) {
        private val tl = ReportKit.layout(name, ReportKit.paint(18f, ReportKit.ON_SOFT, true), INNER - 72, 1.1f, 2)
        private val ml = ReportKit.layout(meta, ReportKit.paint(11.5f, ReportKit.ON_SOFT), INNER - 72)
        override val h = 22 + 16 + tl.height + 4 + ml.height + 16 + 8f

        override fun draw(c: Canvas) {
            ReportKit.card(c, PAD, 22f, W - PAD, h - 8, SOFT, 20f)
            c.drawCircle(PAD + 30, 22f + (h - 30) / 2, 16f, ReportKit.fill(ACCENT))
            c.drawText("$no", PAD + 30, 22f + (h - 30) / 2 + 5, ReportKit.paint(13f, ReportKit.ON_ACCENT, true).apply { textAlign = Paint.Align.CENTER })
            tl.drawAt(c, PAD + 58, 38f)
            ml.drawAt(c, PAD + 58, 42f + tl.height)
        }
    }

    /** A video: title, numbers, what it is about, key points, what viewers say. */
    private class VideoBlock(private val no: Int, v: Video, card: Dossier.VideoCard?, top: Comment?) : ReportKit.Block() {
        private val w = INNER - 32
        private val title = ReportKit.layout(v.title, ReportKit.paint(13.5f, ON, true), w - 30, 1.15f, 3)
        private val meta = ReportKit.layout(
            listOfNotNull(
                if (v.views >= 0) "👁 " + Fmt.num(v.views) else null,
                if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText.takeIf { it.isNotBlank() },
                if (v.duration > 0) "⏱ " + Fmt.duration(v.duration) else null,
                if (v.likes >= 0) "👍 " + Fmt.num(v.likes) else null,
                if (v.commentsCount > 0) "💬 " + v.commentsCount else null
            ).joinToString(" · "), ReportKit.paint(11f, MUTED), w
        )
        private val about: StaticLayout? = (card?.about ?: v.description.lineSequence().firstOrNull { it.isNotBlank() }?.take(220))
            ?.takeIf { it.isNotBlank() }?.let { ReportKit.layout(it, ReportKit.paint(12.5f, if (card != null) ON else MUTED), w, 1.22f, 5) }
        private val points: StaticLayout? = card?.points?.take(5)?.takeIf { it.isNotEmpty() }
            ?.let { ReportKit.layout(it.joinToString("\n") { s -> "•  $s" }, ReportKit.paint(12f, ON), w, 1.25f, 12) }
        private val commentText = card?.comments?.takeIf { it.isNotBlank() } ?: top?.let { "«" + it.text.replace('\n', ' ').take(240) + "» — ♥" + it.likes }
        private val comments: StaticLayout? = commentText?.let { ReportKit.layout(it, ReportKit.paint(12f, ReportKit.ON_SOFT), w - 28, 1.22f, 6) }
        private val mood = card?.takeIf { it.hasMood }
        private val questions: StaticLayout? = card?.questions?.take(3)?.takeIf { it.isNotEmpty() }
            ?.let { ReportKit.layout(it.joinToString("\n") { s -> "❓ $s" }, ReportKit.paint(11.5f, MUTED), w - 28, 1.22f, 6) }
        private val verdict: StaticLayout? = card?.verdict?.takeIf { it.isNotBlank() }
            ?.let { ReportKit.layout(ReportKit.rich("🎯 **" + t("Для темы:", "For the topic:") + "** " + it), ReportKit.paint(12f, ON), w, 1.22f, 4) }

        private val commentBoxH = comments?.let { 12 + (if (mood != null) 22f else 0f) + it.height + (questions?.let { q -> 6f + q.height } ?: 0f) + 12 } ?: 0f
        override val h: Float = run {
            var y = 14f + max(24f, title.height.toFloat()) + 4 + meta.height
            about?.let { y += 10 + it.height }
            points?.let { y += 8 + it.height }
            if (comments != null) y += 10 + commentBoxH
            verdict?.let { y += 10 + it.height }
            y + 14 + 8
        }

        override fun draw(c: Canvas) {
            ReportKit.card(c, PAD, 0f, W - PAD, h - 8, CARD, 16f)
            val x = PAD + 16
            c.drawRoundRect(RectF(x, 14f, x + 22, 36f), 7f, 7f, ReportKit.fill(SOFT))
            c.drawText("$no", x + 11, 29.5f, ReportKit.paint(11.5f, ReportKit.ON_SOFT, true).apply { textAlign = Paint.Align.CENTER })
            title.drawAt(c, x + 30, 14f)
            var y = 14f + max(24f, title.height.toFloat()) + 4
            meta.drawAt(c, x, y)
            y += meta.height
            about?.let { y += 10; it.drawAt(c, x, y); y += it.height }
            points?.let { y += 8; it.drawAt(c, x, y); y += it.height }
            comments?.let { cl ->
                y += 10
                ReportKit.card(c, x, y, W - PAD - 16, y + commentBoxH, SOFT, 12f)
                var yy = y + 12
                c.drawText("💬", x + 8, yy + 12, ReportKit.paint(12f))
                mood?.let { m ->
                    val l = x + 28
                    val r = W - PAD - 28
                    val total = (m.pos + m.neu + m.neg).coerceAtLeast(1)
                    var bx = l
                    for ((col, v) in listOf(OK to m.pos, OUTLINE_SOFT to m.neu, BAD to m.neg)) {
                        val bw = (r - l) * v / total
                        if (bw > 0) c.drawRect(bx, yy + 2, bx + bw, yy + 8, ReportKit.fill(col))
                        bx += bw
                    }
                    c.drawText("+${m.pos}%  ·  ${m.neu}%  ·  −${m.neg}%", l, yy + 20, ReportKit.paint(9.5f, MUTED, true))
                    yy += 22
                }
                cl.drawAt(c, x + 28, yy)
                yy += cl.height
                questions?.let { q -> yy += 6; q.drawAt(c, x + 28, yy) }
                y += commentBoxH
            }
            verdict?.let { y += 10; it.drawAt(c, x, y) }
        }
    }
}

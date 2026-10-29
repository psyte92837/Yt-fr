/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.StyleSpan
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mobile-first report pages drawn with Canvas: a column of blocks, 360 units wide (a phone screen in dp).
 * The same blocks become one long PNG (1080 px wide) or a PDF with phone-shaped pages (360×640 pt).
 * A fixed light Material 3 palette keeps shared files readable everywhere.
 */
object ReportKit {
    const val W = 360f
    const val PAGE_H = 640f
    const val PAD = 16f
    const val INNER = W - 2 * PAD

    const val BG = 0xFFFEF7FF.toInt()
    const val CARD = 0xFFF3EDF7.toInt()
    const val CARD_HI = 0xFFECE6F0.toInt()
    const val ON = 0xFF1D1B20.toInt()
    const val MUTED = 0xFF49454F.toInt()
    const val OUTLINE = 0xFF79747E.toInt()
    const val OUTLINE_SOFT = 0xFFCAC4D0.toInt()
    const val ACCENT = 0xFF6750A4.toInt()
    const val ON_ACCENT = 0xFFFFFFFF.toInt()
    const val SOFT = 0xFFE8DEF8.toInt()
    const val ON_SOFT = 0xFF1D192B.toInt()
    const val TERTIARY = 0xFF7D5260.toInt()
    const val OK = 0xFF2E7D32.toInt()
    const val WARN = 0xFFB26A00.toInt()
    const val BAD = 0xFFB3261E.toInt()

    /** A piece of the report: fixed height, drawn with its top-left corner at (0, 0), full width [W]. */
    abstract class Block(val keepWithNext: Boolean = false) {
        abstract val h: Float
        abstract fun draw(c: Canvas)
    }

    fun paint(size: Float, color: Int = ON, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    @Suppress("DEPRECATION")
    fun layout(text: CharSequence, p: TextPaint, w: Float, spacing: Float = 1.18f, maxLines: Int = 0): StaticLayout {
        val l = StaticLayout(text, p, max(1, w.toInt()), Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        if (maxLines <= 0 || l.lineCount <= maxLines) return l
        val end = l.getLineEnd(maxLines - 1)
        val cut = text.subSequence(0, max(0, end - 1)).toString().trimEnd() + "…"
        return StaticLayout(cut, p, max(1, w.toInt()), Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
    }

    fun one(text: String, p: TextPaint, w: Float): String = TextUtils.ellipsize(text, p, w, TextUtils.TruncateAt.END).toString()

    fun StaticLayout.drawAt(c: Canvas, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        draw(c)
        c.restore()
    }

    fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int = CARD, radius: Float = 16f) =
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, fill(color))

    /** "**bold**" fragments become bold spans (for AI text and glossary lines). */
    fun rich(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val a = s.indexOf("**", i)
            if (a < 0) { sb.append(s.substring(i)); break }
            val b = s.indexOf("**", a + 2)
            if (b < 0) { sb.append(s.substring(i)); break }
            sb.append(s.substring(i, a))
            val st = sb.length
            sb.append(s.substring(a + 2, b))
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = b + 2
        }
        return sb
    }

    // ------------------------------------------------------------------ blocks

    /** Coloured title card that opens the report. */
    class Hero(kicker: String, title: String, sub: String, meta: String) : Block() {
        private val k = layout(kicker.uppercase(), paint(10.5f, 0xD9FFFFFF.toInt(), true).apply { letterSpacing = 0.08f }, INNER - 40)
        private val tl = layout(title, paint(24f, ON_ACCENT, true), INNER - 40, 1.1f, 4)
        private val sl = layout(sub, paint(13f, 0xF2FFFFFF.toInt()), INNER - 40, 1.2f, 4)
        private val ml = layout(meta, paint(11f, 0xCCFFFFFF.toInt()), INNER - 40)
        override val h = 16 + 20 + k.height + 6 + tl.height + (if (sub.isBlank()) 0f else 8f + sl.height) + 12 + ml.height + 20 + 8f

        override fun draw(c: Canvas) {
            card(c, PAD, 16f, W - PAD, h - 8, ACCENT, 24f)
            // soft decorative circles
            c.drawCircle(W - PAD - 30, 40f, 46f, fill(0x1FFFFFFF))
            c.drawCircle(W - PAD - 4, 92f, 26f, fill(0x14FFFFFF))
            var y = 36f
            k.drawAt(c, PAD + 20, y); y += k.height + 6
            tl.drawAt(c, PAD + 20, y); y += tl.height
            if (sl.text.isNotBlank()) { y += 8; sl.drawAt(c, PAD + 20, y); y += sl.height }
            y += 12
            ml.drawAt(c, PAD + 20, y)
        }
    }

    /** Section heading with an optional explanation line. */
    class Section(title: String, sub: String = "") : Block(keepWithNext = true) {
        private val tl = layout(title, paint(17f, ON, true), INNER)
        private val sl = if (sub.isBlank()) null else layout(sub, paint(12f, MUTED), INNER)
        override val h = 20 + tl.height + (sl?.let { 4f + it.height } ?: 0f) + 10f

        override fun draw(c: Canvas) {
            c.drawRoundRect(RectF(PAD, 22f, PAD + 4, 20f + tl.height - 2), 2f, 2f, fill(ACCENT))
            tl.drawAt(c, PAD + 12, 20f)
            sl?.drawAt(c, PAD + 12, 24f + tl.height)
        }
    }

    /** Grid of metric tiles, two per row. */
    class Kpis(private val items: List<Triple<String, String, Int>>) : Block() {
        private val rows = (items.size + 1) / 2
        private val tileH = 70f
        override val h = 8 + rows * tileH + (rows - 1).coerceAtLeast(0) * 8f + 4f
        private val vp = paint(22f, ON, true)
        private val lp = paint(11.5f, MUTED)

        override fun draw(c: Canvas) {
            val tw = (INNER - 8) / 2
            items.forEachIndexed { i, (value, label, color) ->
                val x = PAD + (i % 2) * (tw + 8)
                val y = 8 + (i / 2) * (tileH + 8)
                card(c, x, y, x + tw, y + tileH)
                c.drawRoundRect(RectF(x + 14, y + 14, x + 18, y + 40), 2f, 2f, fill(color))
                c.drawText(one(value, vp, tw - 36), x + 26, y + 36, vp)
                c.drawText(one(label, lp, tw - 36), x + 26, y + 56, lp)
            }
        }
    }

    /** A finding: emoji badge + wrapped text. */
    class Insight(private val icon: String, text: String, private val tint: Int = SOFT) : Block() {
        private val tl = layout(rich(text), paint(13f, ON), INNER - 32 - 40, 1.22f)
        override val h = max(36f, tl.height.toFloat()) + 24 + 8

        override fun draw(c: Canvas) {
            card(c, PAD, 0f, W - PAD, h - 8)
            c.drawCircle(PAD + 30, 30f, 16f, fill(tint))
            val ip = paint(15f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(icon, PAD + 30, 35.5f, ip)
            tl.drawAt(c, PAD + 56, 12f)
        }
    }

    /** Free text inside a card (AI summaries, glossary). */
    class Paragraph(text: CharSequence, size: Float = 13f, color: Int = ON, private val bg: Int = CARD, maxLines: Int = 0) : Block() {
        private val tl = layout(if (text is String) rich(text) else text, paint(size, color), INNER - 32, 1.25f, maxLines)
        override val h = tl.height + 28f + 8

        override fun draw(c: Canvas) {
            card(c, PAD, 0f, W - PAD, h - 8, bg)
            tl.drawAt(c, PAD + 16, 14f)
        }
    }

    /** Vertical bars, optionally with a highlighted part of each bar (e.g. "on topic"). */
    class Bars(
        private val values: List<Int>,
        private val labels: List<String>,
        private val part: List<Int>? = null,
        private val legend: List<Pair<Int, String>> = emptyList()
    ) : Block() {
        override val h = 190f + (if (legend.isEmpty()) 0f else 20f) + 8

        override fun draw(c: Canvas) {
            card(c, PAD, 0f, W - PAD, h - 8)
            if (values.isEmpty()) return
            val left = PAD + 16
            val right = W - PAD - 16
            val top = 30f
            val bottom = 160f
            val max = max(1, values.maxOrNull() ?: 1)
            val grid = Paint().apply { color = OUTLINE_SOFT; strokeWidth = 0.6f }
            for (i in 0..3) c.drawLine(left, bottom - (bottom - top) * i / 3f, right, bottom - (bottom - top) * i / 3f, grid)
            val n = values.size
            val slot = (right - left) / n
            val bw = min(36f, slot * 0.62f)
            val vp = paint(10f, MUTED, true).apply { textAlign = Paint.Align.CENTER }
            val lp = paint(10f, MUTED).apply { textAlign = Paint.Align.CENTER }
            val every = max(1, (n * 26 / (right - left)).toInt() + 1)
            values.forEachIndexed { i, v ->
                val cx = left + slot * i + slot / 2
                val bh = (bottom - top) * v / max
                c.drawRoundRect(RectF(cx - bw / 2, bottom - bh, cx + bw / 2, bottom), 5f, 5f, fill(if (part == null) ACCENT else SOFT))
                part?.getOrNull(i)?.takeIf { it > 0 }?.let { p ->
                    val ph = (bottom - top) * p / max
                    c.drawRoundRect(RectF(cx - bw / 2, bottom - ph, cx + bw / 2, bottom), 5f, 5f, fill(ACCENT))
                }
                if (n <= 14 || i % every == 0) c.drawText(Fmt.num(v.toLong()), cx, bottom - bh - 5, vp)
                if (i % every == 0) c.drawText(labels.getOrElse(i) { "" }.take(8), cx, bottom + 15, lp)
            }
            var x = left
            val legP = paint(11f, MUTED)
            for ((col, s) in legend) {
                c.drawCircle(x + 5, 190f - 4, 5f, fill(col))
                c.drawText(s, x + 14, 190f, legP)
                x += 14 + legP.measureText(s) + 16
            }
        }
    }

    /** One horizontal bar split into coloured parts, with a legend of counts and shares. */
    class Split(private val parts: List<Triple<Int, String, Int>>) : Block() {
        override val h = 26 + 16 + 12 + parts.size * 22f + 12 + 8

        override fun draw(c: Canvas) {
            card(c, PAD, 0f, W - PAD, h - 8)
            val total = max(1, parts.sumOf { it.third })
            val l = PAD + 16
            val r = W - PAD - 16
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(RectF(l, 22f, r, 38f), 8f, 8f, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawRect(l, 22f, r, 38f, fill(CARD_HI))
            var x = l
            for ((col, _, v) in parts) {
                val w = (r - l) * v / total
                c.drawRect(x, 22f, x + w, 38f, fill(col))
                x += w
            }
            c.restore()
            val tp = paint(12.5f, ON)
            val vp = paint(12.5f, ON, true).apply { textAlign = Paint.Align.RIGHT }
            parts.forEachIndexed { i, (col, label, v) ->
                val y = 66f + i * 22
                c.drawCircle(l + 5, y - 4, 5f, fill(col))
                c.drawText(label, l + 16, y, tp)
                c.drawText("$v · ${Math.round(100.0 * v / total)}%", r, y, vp)
            }
        }
    }

    /** Ranked list with proportional bars: (title, subtitle, value, value text). */
    class Ranking(private val rows: List<Row>, private val color: Int = ACCENT) : Block() {
        class Row(val title: String, val sub: String, val value: Double, val valueText: String)

        private val rowH = 50f
        override val h = 8 + rows.size * rowH + 8 + 8

        override fun draw(c: Canvas) {
            card(c, PAD, 0f, W - PAD, h - 8)
            val max = rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
            val np = paint(11f, ON_SOFT, true).apply { textAlign = Paint.Align.CENTER }
            val tp = paint(13f, ON, true)
            val sp = paint(10.5f, MUTED)
            val vp = paint(12f, ON, true).apply { textAlign = Paint.Align.RIGHT }
            val l = PAD + 14
            val r = W - PAD - 14
            rows.forEachIndexed { i, row ->
                val y = 8 + i * rowH
                c.drawCircle(l + 11, y + 20, 11f, fill(SOFT))
                c.drawText("${i + 1}", l + 11, y + 24, np)
                val tx = l + 30
                val vw = vp.measureText(row.valueText)
                c.drawText(row.valueText, r, y + 17, vp)
                c.drawText(one(row.title, tp, r - tx - vw - 8), tx, y + 17, tp)
                if (row.sub.isNotBlank()) c.drawText(one(row.sub, sp, r - tx), tx, y + 31, sp)
                c.drawRoundRect(RectF(tx, y + 37, r, y + 41), 2f, 2f, fill(CARD_HI))
                c.drawRoundRect(RectF(tx, y + 37, tx + ((r - tx) * (row.value / max)).toFloat().coerceAtLeast(4f), y + 41), 2f, 2f, fill(color))
            }
        }
    }

    /** Thin muted line at the end of the report. */
    class Footer(text: String) : Block() {
        private val tl = layout(text, paint(10f, OUTLINE).apply { textAlign = Paint.Align.LEFT }, INNER)
        override val h = 16 + tl.height + 20f

        override fun draw(c: Canvas) {
            c.drawLine(PAD, 6f, W - PAD, 6f, Paint().apply { color = OUTLINE_SOFT; strokeWidth = 0.6f })
            tl.drawAt(c, PAD, 14f)
        }
    }

    /** Empty space. */
    class Gap(override val h: Float) : Block() {
        override fun draw(c: Canvas) {}
    }

    // ------------------------------------------------------------------ output

    /** One long phone-width image (1080 px wide when memory allows). */
    fun png(blocks: List<Block>, out: OutputStream) {
        val total = blocks.sumOf { it.h.toDouble() }.toFloat() + 8
        var s = min(3f, sqrt(14_000_000f / (W * total)))
        var bmp: Bitmap? = null
        while (bmp == null) {
            try {
                bmp = Bitmap.createBitmap((W * s).toInt(), (total * s).toInt(), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                s *= 0.7f
                if (s < 0.8f) throw e
            }
        }
        val img = bmp!!
        val c = Canvas(img)
        c.drawColor(BG)
        c.scale(s, s)
        var y = 0f
        for (b in blocks) {
            c.save()
            c.translate(0f, y)
            b.draw(c)
            c.restore()
            y += b.h
        }
        img.compress(Bitmap.CompressFormat.PNG, 100, out)
        img.recycle()
    }

    /** PDF with phone-shaped pages; a block never splits, headings stay with what follows. */
    fun pdf(blocks: List<Block>, out: OutputStream, footer: String = "Lumen") {
        val doc = PdfDocument()
        var no = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val fp = paint(9f, OUTLINE)
        val fpr = paint(9f, OUTLINE).apply { textAlign = Paint.Align.RIGHT }
        fun close() {
            page?.let {
                it.canvas.drawText(footer, PAD, PAGE_H - 12, fp)
                it.canvas.drawText("$no", W - PAD, PAGE_H - 12, fpr)
                doc.finishPage(it)
            }
        }
        fun next() {
            close()
            no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), PAGE_H.toInt(), no).create()).also { it.canvas.drawColor(BG) }
            y = if (no == 1) 0f else 12f
        }
        next()
        val limit = PAGE_H - 28
        blocks.forEachIndexed { i, b ->
            var need = b.h
            if (b.keepWithNext && i + 1 < blocks.size) need += blocks[i + 1].h
            if (y + need > limit && y > 14f) next()
            val c = page!!.canvas
            c.save()
            c.translate(0f, y)
            b.draw(c)
            c.restore()
            y += b.h
        }
        close()
        doc.writeTo(out)
        doc.close()
    }

    fun write(blocks: List<Block>, asPdf: Boolean, out: OutputStream, footer: String = "Lumen") =
        if (asPdf) pdf(blocks, out, footer) else png(blocks, out)

    /** Writes the report into the app's external cache (shareable through the FileProvider) and opens the share sheet. */
    fun share(ctx: Context, file: File, pdf: Boolean, subject: String) {
        val auth = ctx.packageName + ".provider"
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(ctx, auth, file)
        } catch (e: IllegalArgumentException) {
            // the provider may only expose the internal cache root
            val copy = File(ctx.cacheDir, file.name)
            file.copyTo(copy, overwrite = true)
            androidx.core.content.FileProvider.getUriForFile(ctx, auth, copy)
        }
        val i = Intent(Intent.ACTION_SEND).setType(if (pdf) "application/pdf" else "image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(subject, uri)
        ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun shareFile(ctx: Context, name: String): File {
        val dir = File(ctx.externalCacheDir ?: ctx.cacheDir, "reports").apply { mkdirs() }
        return File(dir, name)
    }

    fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{Nd}_-]+"), "_").trim('_').take(40).ifBlank { "lumen" }
}

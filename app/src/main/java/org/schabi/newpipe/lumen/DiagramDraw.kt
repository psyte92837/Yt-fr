/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** Canvas drawing of a [Diagram] and its PNG / PDF export. A fixed light Material 3 palette keeps shared files readable everywhere. */
object DiagramDraw {
    private const val TEXT = 13f
    private const val PAD = 24f

    class Prepared(val texts: Map<String, StaticLayout>, val sizes: Map<String, Pair<Float, Float>>)

    private class Look(val fill: Int, val stroke: Int, val text: Int, val dashed: Boolean = false, val bold: Boolean = false)

    private fun look(n: DNode, depth: Int, mind: Boolean): Look = when {
        mind && n.kind == DKind.TOPIC -> when (depth) {
            0 -> Look(ReportKit.ACCENT, ReportKit.ACCENT, ReportKit.ON_ACCENT, bold = true)
            1 -> Look(ReportKit.SOFT, ReportKit.ACCENT, ReportKit.ON_SOFT, bold = true)
            else -> Look(ReportKit.CARD, ReportKit.OUTLINE, ReportKit.ON)
        }
        n.kind == DKind.START -> Look(0xFFDDF1E0.toInt(), ReportKit.OK, ReportKit.ON, bold = true)
        n.kind == DKind.END -> Look(0xFFFBE0DC.toInt(), ReportKit.BAD, ReportKit.ON, bold = true)
        n.kind == DKind.DECISION -> Look(0xFFFFEFD5.toInt(), ReportKit.WARN, ReportKit.ON)
        n.kind == DKind.NOTE -> Look(ReportKit.BG, ReportKit.OUTLINE, ReportKit.MUTED, dashed = true)
        n.kind == DKind.TOPIC -> Look(ReportKit.SOFT, ReportKit.ACCENT, ReportKit.ON_SOFT)
        else -> Look(ReportKit.SOFT, ReportKit.ACCENT, ReportKit.ON_SOFT)
    }

    /** Measures every block's text; the sizes feed the pure layout. */
    fun prepare(d: Diagram): Prepared {
        val texts = HashMap<String, StaticLayout>()
        val sizes = HashMap<String, Pair<Float, Float>>()
        for (n in d.nodes) {
            val maxW = when {
                n.kind == DKind.DECISION -> 120f
                d.isMind -> 150f
                else -> 170f
            }
            val l = ReportKit.layout(n.text, ReportKit.paint(TEXT, ReportKit.ON), maxW, 1.1f, 6)
            val tw = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
            val th = l.height.toFloat()
            texts[n.id] = l
            sizes[n.id] = when (n.kind) {
                DKind.DECISION -> max(120f, (tw + 16) * 1.55f) to max(64f, (th + 14) * 1.7f)
                DKind.START, DKind.END -> max(96f, tw + 40) to max(38f, th + 18)
                else -> max(84f, tw + 28) to max(40f, th + 20)
            }
        }
        return Prepared(texts, sizes)
    }

    fun layout(d: Diagram, prep: Prepared): DLayout = DiagramLayout.layout(d) { prep.sizes[it.id] ?: (96f to 40f) }

    private fun cubicAt(p: FloatArray, t: Float): Pair<Float, Float> {
        val u = 1 - t
        val x = u * u * u * p[0] + 3 * u * u * t * p[2] + 3 * u * t * t * p[4] + t * t * t * p[6]
        val y = u * u * u * p[1] + 3 * u * u * t * p[3] + 3 * u * t * t * p[5] + t * t * t * p[7]
        return x to y
    }

    fun draw(c: Canvas, d: Diagram, lay: DLayout, prep: Prepared, selected: String? = null) {
        val mind = d.isMind
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (mind) 1.4f else 1.6f
            color = if (mind) 0xFF9A93A3.toInt() else ReportKit.OUTLINE
            strokeCap = Paint.Cap.ROUND
        }
        val head = ReportKit.fill(line.color)
        val labelP = ReportKit.paint(10.5f, ReportKit.MUTED, bold = true)
        val labelBg = ReportKit.fill(ReportKit.BG)

        for (e in d.edges) {
            val a = lay.boxes[e.from] ?: continue
            val b = lay.boxes[e.to] ?: continue
            val back = (e.from to e.to) in lay.back
            val pts: FloatArray = when {
                mind -> {
                    val dx = (b.x - a.r) / 2
                    floatArrayOf(a.r, a.cy, a.r + dx, a.cy, b.x - dx, b.cy, b.x, b.cy)
                }
                back -> {
                    val ox = max(a.r, b.r) + 28f
                    floatArrayOf(a.r, a.cy, ox, a.cy, ox, b.cy, b.r, b.cy)
                }
                d.node(e.from)?.kind == DKind.DECISION && kotlin.math.abs(b.cx - a.cx) > 12f -> {
                    val dir = if (b.cx < a.cx) -1f else 1f
                    val sx = if (dir < 0) a.x else a.r
                    val ex = b.cx
                    floatArrayOf(sx, a.cy, sx + dir * max(30f, kotlin.math.abs(ex - sx) * 0.5f), a.cy, ex, b.y - max(24f, (b.y - a.cy) * 0.5f), ex, b.y)
                }
                else -> {
                    val dy = max(24f, (b.y - a.b) / 2)
                    floatArrayOf(a.cx, a.b, a.cx, a.b + dy, b.cx, b.y - dy, b.cx, b.y)
                }
            }
            val path = Path().apply {
                moveTo(pts[0], pts[1])
                cubicTo(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
            }
            c.drawPath(path, line)
            if (!mind) {
                val ang = Math.atan2((pts[7] - pts[5]).toDouble(), (pts[6] - pts[4]).toDouble())
                val s = 8f
                val tip = Path().apply {
                    moveTo(pts[6], pts[7])
                    lineTo(pts[6] - (s * Math.cos(ang - 0.45)).toFloat(), pts[7] - (s * Math.sin(ang - 0.45)).toFloat())
                    lineTo(pts[6] - (s * Math.cos(ang + 0.45)).toFloat(), pts[7] - (s * Math.sin(ang + 0.45)).toFloat())
                    close()
                }
                c.drawPath(tip, head)
            }
            if (e.label.isNotBlank()) {
                val (lx, ly) = cubicAt(pts, 0.38f)
                val w = labelP.measureText(e.label)
                c.drawRoundRect(RectF(lx - w / 2 - 4, ly - 8, lx + w / 2 + 4, ly + 7), 6f, 6f, labelBg)
                c.drawText(e.label, lx - w / 2, ly + 4, labelP)
            }
        }

        for (n in d.nodes) {
            val b = lay.boxes[n.id] ?: continue
            val lk = look(n, lay.depth[n.id] ?: 0, mind)
            val fill = ReportKit.fill(lk.fill)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (n.id == selected) 3f else 1.4f
                color = if (n.id == selected) ReportKit.TERTIARY else lk.stroke
                if (lk.dashed) pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            }
            val rect = RectF(b.x, b.y, b.r, b.b)
            when (n.kind) {
                DKind.DECISION -> {
                    val p = Path().apply {
                        moveTo(b.cx, b.y)
                        lineTo(b.r, b.cy)
                        lineTo(b.cx, b.b)
                        lineTo(b.x, b.cy)
                        close()
                    }
                    c.drawPath(p, fill)
                    c.drawPath(p, stroke)
                }
                DKind.START, DKind.END -> {
                    c.drawRoundRect(rect, b.h / 2, b.h / 2, fill)
                    c.drawRoundRect(rect, b.h / 2, b.h / 2, stroke)
                }
                else -> {
                    c.drawRoundRect(rect, 10f, 10f, fill)
                    c.drawRoundRect(rect, 10f, 10f, stroke)
                }
            }
            val l = prep.texts[n.id] ?: continue
            l.paint.color = lk.text
            l.paint.isFakeBoldText = lk.bold
            c.save()
            c.translate(b.cx - l.width / 2f, b.cy - l.height / 2f)
            l.draw(c)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ export

    private class Page(val w: Float, val h: Float, val top: Float, val title: StaticLayout, val lay: DLayout, val prep: Prepared)

    private fun page(d: Diagram): Page {
        val prep = prepare(d)
        val lay = layout(d, prep)
        val w = max(lay.width + 2 * PAD, 300f)
        val title = ReportKit.layout(d.title.ifBlank { "Lumen" }, ReportKit.paint(17f, ReportKit.ON, true), w - 2 * PAD, 1.15f, 2)
        val top = PAD + title.height + 14f
        return Page(w, top + lay.height + 34f, top, title, lay, prep)
    }

    private fun drawPage(c: Canvas, d: Diagram, pg: Page, footer: String) {
        c.drawColor(ReportKit.BG)
        c.save()
        c.translate(PAD, PAD)
        pg.title.draw(c)
        c.restore()
        c.save()
        c.translate((pg.w - pg.lay.width) / 2, pg.top)
        draw(c, d, pg.lay, pg.prep)
        c.restore()
        c.drawText(footer, PAD, pg.h - 12f, ReportKit.paint(9f, ReportKit.OUTLINE))
    }

    fun png(d: Diagram, out: OutputStream, footer: String = "Lumen") {
        val pg = page(d)
        var s = (1080f / pg.w).coerceIn(1f, 3f)
        while (pg.w * s * pg.h * s > 16_000_000f && s > 0.3f) s *= 0.85f
        var bmp: Bitmap? = null
        while (bmp == null) {
            try {
                bmp = Bitmap.createBitmap(max(1, (pg.w * s).toInt()), max(1, (pg.h * s).toInt()), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                s *= 0.7f
                if (s < 0.2f) throw e
            }
        }
        val img = bmp!!
        val c = Canvas(img)
        c.scale(s, s)
        drawPage(c, d, pg, footer)
        img.compress(Bitmap.CompressFormat.PNG, 100, out)
        img.recycle()
    }

    /** One vector page as large as the diagram (scaled down if it would exceed 1800 pt), so text stays sharp when zoomed. */
    fun pdf(d: Diagram, out: OutputStream, footer: String = "Lumen") {
        val pg = page(d)
        val k = min(1f, 1800f / max(pg.w, pg.h))
        val doc = PdfDocument()
        val p = doc.startPage(PdfDocument.PageInfo.Builder(max(1, (pg.w * k).toInt()), max(1, (pg.h * k).toInt()), 1).create())
        p.canvas.scale(k, k)
        drawPage(p.canvas, d, pg, footer)
        doc.finishPage(p)
        doc.writeTo(out)
        doc.close()
    }

    fun write(d: Diagram, asPdf: Boolean, out: OutputStream, footer: String) = if (asPdf) pdf(d, out, footer) else png(d, out, footer)
}

/** Pan, pinch-zoom and tap on blocks; double tap fits the diagram to the screen. */
class DiagramView(ctx: Context) : View(ctx) {
    var onTap: (String?) -> Unit = {}
    var selected: String? = null
        set(v) {
            field = v
            invalidate()
        }
    private var diagram: Diagram? = null
    private var prep: DiagramDraw.Prepared? = null
    private var lay: DLayout? = null
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitted = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(0.2f, 4f)
            val k = ns / scale
            tx = d.focusX - (d.focusX - tx) * k
            ty = d.focusY - (d.focusY - ty) * k
            scale = ns
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return false
            tx -= dx
            ty -= dy
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val l = lay ?: return false
            val x = (e.x - tx) / scale
            val y = (e.y - ty) / scale
            val hit = l.boxes.entries.lastOrNull { (_, b) -> x >= b.x && x <= b.r && y >= b.y && y <= b.b }?.key
            selected = hit
            onTap(hit)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            fit()
            return true
        }
    })

    /** Sets the diagram; re-measures and, the first time, fits it into view. */
    fun setDiagram(d: Diagram, refit: Boolean = false) {
        diagram = d
        val p = DiagramDraw.prepare(d)
        prep = p
        lay = DiagramDraw.layout(d, p)
        if (refit) fitted = false
        if (!fitted && width > 0) fit()
        invalidate()
    }

    fun fit() {
        val l = lay ?: return
        if (width == 0 || height == 0 || l.width <= 0f) return
        val d = diagram
        val byWidth = (width - 24f) / l.width
        val all = min(byWidth, (height - 24f) / l.height)
        scale = (if (d != null && d.isMind) max(all, min(byWidth, 1f)) else byWidth).coerceIn(0.3f, 1.3f)
        tx = (width - l.width * scale) / 2
        ty = 12f
        fitted = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!fitted) fit()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(ReportKit.BG)
        val d = diagram ?: return
        val l = lay ?: return
        val p = prep ?: return
        c.save()
        c.translate(tx, ty)
        c.scale(scale, scale)
        DiagramDraw.draw(c, d, l, p, selected)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val a = scaler.onTouchEvent(e)
        val b = gestures.onTouchEvent(e)
        return a || b
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Radial knowledge map: the focus in the centre, aspects around it, opened branches further out.
 * Zoom is the level of detail: far away only aspects are labelled, closer groups, then videos.
 * Colour is the aspect, size the number of videos, a ring means an AI summary, a green dot new videos.
 */
class KnowledgeMapView(ctx: Context) : View(ctx) {
    var onTap: (KNode) -> Unit = {}
    var onLong: (KNode) -> Unit = {}
    var summarized: Set<String> = emptySet()
        set(v) {
            field = v
            invalidate()
        }
    var selected = ""
        set(v) {
            field = v
            invalidate()
        }

    private var pos: List<KPos> = emptyList()
    private var fromX = DoubleArray(0)
    private var fromY = DoubleArray(0)
    private var anim = 1f
    private var maxCount = 1
    private var animator: ValueAnimator? = null

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitted = false
    private val d = resources.displayMetrics.density
    private val ring = 96f * d

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** New layout; nodes glide from their old places (new ones grow out of their parent). */
    fun setLayout(list: List<KPos>) {
        val oldX = HashMap<String, Double>()
        val oldY = HashMap<String, Double>()
        pos.forEachIndexed { i, p ->
            oldX[p.node.id] = cx(i)
            oldY[p.node.id] = cy(i)
        }
        fromX = DoubleArray(list.size)
        fromY = DoubleArray(list.size)
        list.forEachIndexed { i, p ->
            val par = if (p.parent >= 0) list[p.parent].node.id else p.node.id
            fromX[i] = oldX[p.node.id] ?: oldX[par] ?: p.x
            fromY[i] = oldY[p.node.id] ?: oldY[par] ?: p.y
        }
        pos = list
        maxCount = max(1, list.drop(1).maxOfOrNull { it.node.count } ?: 1)
        animator?.cancel()
        if (oldX.isEmpty()) {
            anim = 1f
        } else {
            anim = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    anim = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (!fitted && width > 0) fit()
        invalidate()
    }

    /** Fits the whole visible map into the screen. */
    fun fit() {
        val reach = pos.maxOfOrNull { hypot(it.x, it.y) }?.coerceAtLeast(1.0) ?: 1.0
        val avail = min(width, height) / 2f - 36 * d
        scale = if (avail > 0) (avail / (reach.toFloat() * ring)).coerceIn(0.35f, 1.6f) else 1f
        tx = 0f
        ty = 0f
        fitted = width > 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!fitted && pos.isNotEmpty()) fit()
    }

    // current unit coordinates (mid-animation) and their screen position
    private fun cx(i: Int) = fromX[i] + (pos[i].x - fromX[i]) * anim
    private fun cy(i: Int) = fromY[i] + (pos[i].y - fromY[i]) * anim
    private fun sx(u: Double) = (width / 2f + u.toFloat() * ring * scale + tx)
    private fun sy(u: Double) = (height / 2f + u.toFloat() * ring * scale + ty)

    private val dark get() = luminance(Palette.SURFACE) < 0.4

    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255

    /** Aspect colour: golden-angle hues, softer in the light theme. */
    private fun color(branch: Int): Int {
        if (branch < 0) return Palette.ACCENT
        val hue = (branch * 137.508f + 200f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, if (dark) 0.45f else 0.60f, if (dark) 0.95f else 0.78f))
    }

    private fun radius(i: Int): Float {
        val p = pos[i]
        if (p.depth == 0) return 22f * d
        val share = sqrt(p.node.count.toFloat() / maxCount).coerceIn(0f, 1f)
        val base = if (p.node.children.isEmpty()) 4f else 6f
        return (base + 10f * share) * d * sqrt(scale).coerceIn(0.7f, 1.6f)
    }

    /** Ids from the root to the selected node: that route is highlighted. */
    private fun route(): Set<String> {
        if (selected.isEmpty()) return emptySet()
        val out = HashSet<String>()
        var id = selected
        out += id
        while (id.contains('/')) {
            id = id.substringBeforeLast('/')
            out += id
        }
        return out
    }

    override fun onDraw(c: Canvas) {
        if (pos.isEmpty()) return
        val hot = route()
        // rings: faint guides for the depth levels
        ringPaint.strokeWidth = 1f * d
        ringPaint.color = (Palette.OUTLINE and 0x00FFFFFF) or 0x22000000
        val deepest = pos.maxOf { it.depth }
        for (k in 1..deepest) c.drawCircle(sx(0.0), sy(0.0), k * ring * scale, ringPaint)
        // edges: curves that leave the parent along the child's direction
        for (i in pos.indices) {
            val p = pos[i]
            if (p.parent < 0) continue
            val x0 = cx(p.parent)
            val y0 = cy(p.parent)
            val x1 = cx(i)
            val y1 = cy(i)
            val len = hypot(x1, y1)
            val pr = hypot(x0, y0)
            val kx = if (len > 0) x1 / len * (pr + 0.35) else x0
            val ky = if (len > 0) y1 / len * (pr + 0.35) else y0
            val on = p.node.id in hot
            edgePaint.strokeWidth = (if (on) 3f else if (p.depth == 1) 2f else 1.2f) * d
            edgePaint.color = if (on) Palette.ACCENT else (color(p.branch) and 0x00FFFFFF) or if (selected.isEmpty()) 0x99000000.toInt() else 0x55000000
            path.reset()
            path.moveTo(sx(x0), sy(y0))
            path.quadTo(sx(kx), sy(ky), sx(x1), sy(y1))
            c.drawPath(path, edgePaint)
        }
        // nodes
        for (i in pos.indices) {
            val p = pos[i]
            val x = sx(cx(i))
            val y = sy(cy(i))
            val r = radius(i)
            val dim = selected.isNotEmpty() && p.node.id !in hot && pos.getOrNull(p.parent)?.node?.id != selected
            val col = color(p.branch)
            nodePaint.color = if (dim) (col and 0x00FFFFFF) or 0x66000000 else col
            c.drawCircle(x, y, r, nodePaint)
            if (p.node.id in summarized) {
                ringPaint.strokeWidth = 2.5f * d
                ringPaint.color = Palette.ACCENT
                c.drawCircle(x, y, r + 3.5f * d, ringPaint)
            }
            if (p.node.id == selected) {
                ringPaint.strokeWidth = 2f * d
                ringPaint.color = Palette.ON_SURFACE
                c.drawCircle(x, y, r + 7f * d, ringPaint)
            }
            if (p.node.fresh > 0) {
                nodePaint.color = Palette.OK
                c.drawCircle(x + r * 0.75f, y - r * 0.75f, 4f * d, nodePaint)
            }
            // a closed branch shows a small "+" so it is clear there is more inside
            if (p.depth > 0 && p.node.children.isNotEmpty() && pos.none { it.parent == i } && r > 7 * d) {
                labelPaint.textSize = r * 1.1f
                labelPaint.isFakeBoldText = true
                labelPaint.color = if (dark) Color.BLACK else Color.WHITE
                val w = labelPaint.measureText("+")
                c.drawText("+", x - w / 2, y + r * 0.38f, labelPaint)
            }
        }
        // labels: the closer you zoom, the deeper they go
        labelBg.color = (Palette.SURFACE and 0x00FFFFFF) or 0xD9000000.toInt()
        for (i in pos.indices) {
            val p = pos[i]
            val show = p.depth <= 1 || p.node.id in hot || pos.getOrNull(p.parent)?.node?.id == selected ||
                (p.depth == 2 && scale > 1.25f) || scale > 2.2f
            if (!show) continue
            val x = sx(cx(i))
            val y = sy(cy(i))
            val r = radius(i)
            labelPaint.textSize = (if (p.depth == 0) 14f else if (p.depth == 1) 12.5f else 11f) * d
            labelPaint.isFakeBoldText = p.depth <= 1
            labelPaint.color = Palette.ON_SURFACE
            val maxLen = if (p.depth == 0) 30 else if (p.depth == 1) 24 else 28
            val s0 = p.node.title.replace('\n', ' ')
            val s = (if (s0.length > maxLen) s0.take(maxLen - 1) + "…" else s0) + if (p.node.fresh > 0 && p.depth > 0) "  +" + p.node.fresh else ""
            val w = labelPaint.measureText(s)
            val (lx, ly) = when {
                p.depth == 0 -> (x - w / 2) to (y + r + 16 * d)
                cx(i) >= -0.05 -> (x + r + 6 * d) to (y + 4 * d)
                else -> (x - r - 6 * d - w) to (y + 4 * d)
            }
            c.drawRoundRect(lx - 4 * d, ly - labelPaint.textSize, lx + w + 4 * d, ly + 5 * d, 6 * d, 6 * d, labelBg)
            c.drawText(s, lx, ly, labelPaint)
        }
    }

    private fun hit(x: Float, y: Float): KNode? {
        var best: KNode? = null
        var bestD = 28f * d
        for (i in pos.indices) {
            val dist = hypot(sx(cx(i)) - x, sy(cy(i)) - y) - radius(i) / 2
            if (dist < bestD) {
                bestD = dist
                best = pos[i].node
            }
        }
        return best
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val ns = min(8f, max(0.25f, scale * det.scaleFactor))
            val k = ns / scale
            // keep the point under the fingers in place (offsets are relative to the centre)
            val fx = det.focusX - width / 2f
            val fy = det.focusY - height / 2f
            tx = fx - (fx - tx) * k
            ty = fy - (fy - ty) * k
            scale = ns
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (abs(dx) + abs(dy) > 0) {
                tx -= dx
                ty -= dy
                invalidate()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let { onTap(it) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hit(e.x, e.y)?.let { onLong(it) }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}

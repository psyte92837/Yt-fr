/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The frame of the editor over the player: draws the blocks shown now and lets the selected one be moved
 * (drag), scaled and turned (the ⤡ corner or two fingers), deleted (✕), edited (✎) and duplicated (⧉).
 * The centre sticks to the middle of the frame (guides), the angle to 0 / 90°.
 */
@SuppressLint("ViewConstructor")
class EditStage(ctx: Context, private val makePainter: (Int, Int) -> StudioEditPainter) : View(ctx) {
    var blocks: List<EBlock> = emptyList()
        set(v) { field = v; invalidate() }
    var selected: String? = null
        set(v) { field = v; invalidate() }
    var timeMs = 0L
        set(v) { if (field != v) { field = v; invalidate() } }

    /** A block was picked on the frame (null = tap on an empty place). */
    var onSelect: (String?) -> Unit = {}
    /** The selected block changed; [done] at the end of the gesture (then it goes to the undo history). */
    var onChange: (EBlock, Boolean) -> Unit = { _, _ -> }
    /** Corner buttons: 0 delete, 1 edit, 3 duplicate. */
    var onCorner: (String, Int) -> Unit = { _, _ -> }
    var onEmptyTap: () -> Unit = {}
    var onDoubleTap: (String) -> Unit = {}
    /** Vertical video: while a block is moved, the places the Shorts / Reels interface covers are shaded. */
    var portrait = false

    private var painter: StudioEditPainter? = null
    private val dp = resources.displayMetrics.density
    private val touchR = 24 * dp
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private val frameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFFFFFFFF.toInt() }
    private val frameShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * dp; color = 0x66000000 }
    private val guideP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0xFFFF4FA3.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    }
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 13 * dp; typeface = Typeface.DEFAULT_BOLD
    }
    private val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 12 * dp }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val safeP = Paint().apply { color = 0x38FF3B30 }
    private val safeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 10 * dp }

    /** Point of the side handle ↔ (middle of the right edge) of a caption or scheme; null for other blocks. */
    private fun sideKnob(b: EBlock, fw: Float, fh: Float): Pair<Float, Float>? {
        if (b.kind != "text" && b.kind != "scheme") return null
        val (a, c) = StudioEdit.corner(b, fw, fh, 1) to StudioEdit.corner(b, fw, fh, 2)
        return (a.first + c.first) / 2 to (a.second + c.second) / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        painter?.close()
        painter = if (w > 0 && h > 0) makePainter(w, h) else null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        painter?.close()
        painter = null
    }

    /** Forgets cached pictures (after a picture block changed). */
    fun refresh() {
        if (width > 0 && height > 0) {
            painter?.close()
            painter = makePainter(width, height)
        }
        invalidate()
    }

    /** The blocks as they are at [timeMs] (keyframes applied). */
    private fun shown(): List<EBlock> = if (blocks.none { it.keys.isNotEmpty() }) blocks else blocks.map { EditKeys.at(it, timeMs) }

    private fun sel(): EBlock? = StudioEdit.find(shown(), selected)?.takeIf { timeMs >= it.startMs && timeMs < it.endMs }

    override fun onDraw(c: Canvas) {
        val fw = width.toFloat()
        val fh = height.toFloat()
        painter?.draw(c, StudioEdit.active(shown(), timeMs), timeMs)
        if (portrait && moved && (mode == MOVE || mode == SCALE || mode == PINCH || mode == WIDTH)) {
            // YouTube Shorts / Reels: the title and buttons cover the bottom fifth and the right column
            c.drawRect(0f, fh * 0.8f, fw, fh, safeP)
            c.drawRect(fw * 0.85f, fh * 0.42f, fw, fh * 0.8f, safeP)
            c.drawRect(0f, 0f, fw, fh * 0.07f, safeP)
            c.drawText(t("закроет интерфейс Shorts", "covered by the Shorts UI"), 8 * dp, fh * 0.8f + 14 * dp, safeText)
        }
        if (guideX) c.drawLine(fw / 2, 0f, fw / 2, fh, guideP)
        if (guideY) c.drawLine(0f, fh / 2, fw, fh / 2, guideP)
        val b = sel() ?: return
        val pts = (0..3).map { StudioEdit.corner(b, fw, fh, it) }
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1..3) lineTo(pts[i].first, pts[i].second)
            close()
        }
        c.drawPath(path, frameShadow)
        c.drawPath(path, frameP)
        knob(c, pts[0], 0xFFE5484D.toInt(), "✕")
        knob(c, pts[1], 0xFF3E7BFA.toInt(), "✎")
        knob(c, pts[3], 0xFF5B6270.toInt(), "⧉")
        knob(c, pts[2], 0xFFFFFFFF.toInt(), "⤡", 0xFF14161B.toInt())
        sideKnob(b, fw, fh)?.let { knob(c, it, 0xFFFFFFFF.toInt(), "↔", 0xFF14161B.toInt()) }
        if (mode == SCALE || mode == PINCH || mode == WIDTH) {
            val s = "${(b.w * 100).toInt()}% · ${b.rot.toInt()}°"
            val tw = tagP.measureText(s) / 2 + 8 * dp
            c.drawRoundRect(fw / 2 - tw, 8 * dp, fw / 2 + tw, 30 * dp, 11 * dp, 11 * dp, tagBg)
            c.drawText(s, fw / 2, 24 * dp, tagP)
        }
    }

    private fun knob(c: Canvas, at: Pair<Float, Float>, color: Int, glyph: String, ink: Int = 0xFFFFFFFF.toInt()) {
        knobP.color = 0x55000000
        c.drawCircle(at.first, at.second + 1 * dp, 12 * dp, knobP)
        knobP.color = color
        c.drawCircle(at.first, at.second, 11 * dp, knobP)
        glyphP.color = ink
        c.drawText(glyph, at.first, at.second + glyphP.textSize * 0.36f, glyphP)
    }

    // ---------- gestures ----------

    private companion object {
        const val NONE = 0
        const val MOVE = 1
        const val SCALE = 2
        const val PINCH = 3
        const val CORNER = 4
        const val EMPTY = 5
        const val WIDTH = 6
    }

    private var mode = NONE
    private var start: EBlock? = null
    private var live: EBlock? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var corner = -1
    private var startDist = 1f
    private var startAngle = 0f
    private var midX0 = 0f
    private var midY0 = 0f
    private var guideX = false
    private var guideY = false
    private var lastTapAt = 0L
    private var lastTapId = ""

    private fun emit(b: EBlock, done: Boolean) {
        live = b
        onChange(b, done)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val fw = width.toFloat()
        val fh = height.toFloat()
        if (fw <= 0f || fh <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                moved = false
                live = null
                guideX = false
                guideY = false
                val cur = sel()
                corner = -1
                if (cur != null) {
                    corner = (0..3).firstOrNull { k -> StudioEdit.corner(cur, fw, fh, k).let { hypot(it.first - e.x, it.second - e.y) <= touchR } } ?: -1
                }
                val side = cur?.let { sideKnob(it, fw, fh) }
                if (cur != null && corner < 0 && side != null && hypot(side.first - e.x, side.second - e.y) <= touchR) {
                    mode = WIDTH
                    start = cur
                } else if (cur != null && corner == 2) {
                    mode = SCALE
                    start = cur
                    startDist = max(1f, hypot(e.x - cur.x * fw, e.y - cur.y * fh))
                    startAngle = Math.toDegrees(atan2((e.y - cur.y * fh).toDouble(), (e.x - cur.x * fw).toDouble())).toFloat()
                } else if (cur != null && corner >= 0) {
                    mode = CORNER
                    start = cur
                } else {
                    val hit = StudioEdit.top(StudioEdit.active(shown(), timeMs), fw, fh, e.x, e.y, 6 * dp)
                    if (hit != null) {
                        if (hit.id != selected) onSelect(hit.id)
                        mode = MOVE
                        start = hit
                    } else {
                        mode = EMPTY
                        start = null
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val b = live ?: start ?: sel()
                if (b != null && e.pointerCount >= 2) {
                    if (mode == MOVE && moved) emit(live ?: b, false)
                    mode = PINCH
                    start = live ?: b
                    startDist = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
                    startAngle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                    midX0 = (e.getX(0) + e.getX(1)) / 2
                    midY0 = (e.getY(0) + e.getY(1)) / 2
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val b0 = start ?: return true
                when (mode) {
                    MOVE -> {
                        if (!moved && hypot(e.x - downX, e.y - downY) < slop) return true
                        moved = true
                        val (nx, gx) = StudioEdit.snapMiddle(b0.x + (e.x - downX) / fw)
                        val (ny, gy) = StudioEdit.snapMiddle(b0.y + (e.y - downY) / fh)
                        if ((gx && !guideX) || (gy && !guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        guideX = gx
                        guideY = gy
                        emit(StudioEdit.moveTo(b0, nx, ny), false)
                    }
                    SCALE -> {
                        moved = true
                        val cx = b0.x * fw
                        val cy = b0.y * fh
                        val k = hypot(e.x - cx, e.y - cy) / startDist
                        val ang = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat() - startAngle
                        emit(StudioEdit.resize(b0, b0.w * k).copy(rot = StudioEdit.snapDeg(b0.rot + ang)), false)
                    }
                    WIDTH -> {
                        moved = true
                        emit(StudioEdit.stretch(b0, fw, fh, e.x, e.y), false)
                    }
                    PINCH -> if (e.pointerCount >= 2) {
                        val k = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) / startDist
                        val ang = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat() - startAngle
                        val mx = (e.getX(0) + e.getX(1)) / 2
                        val my = (e.getY(0) + e.getY(1)) / 2
                        val b = StudioEdit.moveTo(b0, b0.x + (mx - midX0) / fw, b0.y + (my - midY0) / fh)
                        emit(StudioEdit.resize(b, b0.w * k).copy(rot = StudioEdit.snapDeg(b0.rot + ang)), false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == PINCH) {
                    live?.let { emit(it, true) }
                    // the finger that stays goes on moving the block
                    val keep = if (e.actionIndex == 0) 1 else 0
                    downX = e.getX(keep)
                    downY = e.getY(keep)
                    start = live ?: start
                    live = null
                    moved = false
                    mode = MOVE
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                when (mode) {
                    MOVE, SCALE, PINCH, WIDTH -> if (moved) live?.let { emit(it, true) } else if (!cancel && mode == MOVE) start?.let { b ->
                        val now = System.currentTimeMillis()
                        if (lastTapId == b.id && now - lastTapAt < ViewConfiguration.getDoubleTapTimeout()) {
                            lastTapAt = 0
                            onDoubleTap(b.id)
                        } else {
                            lastTapAt = now
                            lastTapId = b.id
                        }
                    }
                    CORNER -> if (!cancel) start?.let { b ->
                        val p = StudioEdit.corner(b, fw, fh, corner)
                        if (hypot(p.first - e.x, p.second - e.y) <= touchR * 1.4f) onCorner(b.id, corner)
                    }
                    EMPTY -> if (!cancel && hypot(e.x - downX, e.y - downY) < slop) {
                        if (selected != null) onSelect(null) else onEmptyTap()
                    }
                }
                mode = NONE
                start = null
                live = null
                guideX = false
                guideY = false
                invalidate()
                return true
            }
        }
        return true
    }
}

/**
 * The timeline: a ruler, frames of the video and the blocks in rows under a fixed playhead in the middle.
 * Dragging the empty part scrolls the video (with a fling), two fingers zoom; a tap selects a block, the
 * selected block is moved by dragging and trimmed by its edge handles (edges stick to the playhead,
 * to the other blocks and to the ends of the video); a long press picks up any block.
 */
class EditTimeline(ctx: Context) : View(ctx) {
    var totalMs = 1L
        set(v) { field = max(1L, v); invalidate() }
    var blocks: List<EBlock> = emptyList()
        set(v) {
            field = v
            if (mode == IDLE) relayout()
            invalidate()
        }
    var selected: String? = null
        set(v) { field = v; invalidate() }
    /** Time under the playhead. */
    var playMs = 0L
        private set
    var thumbs: List<Pair<Long, Bitmap>> = emptyList()
        set(v) { field = v; invalidate() }
    /** Frames of the other videos of the track, by their file. */
    var srcThumbs: Map<String, List<Pair<Long, Bitmap>>> = emptyMap()
        set(v) { field = v; invalidate() }
    /** Width / height of a video frame. */
    var aspect = 9f / 16f
    /** The voice with times: a row of words under the frames; edges stick to word starts. */
    var words: List<SWord> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Pieces of the main track (empty = the whole video as one). */
    var clips: List<VClip> = emptyList()
        set(v) {
            field = v
            clipStarts = EditTrack.starts(v)
            invalidate()
        }
    private var clipStarts = LongArray(0)
    /** The selected piece of the main track, -1 = none. */
    var clipSel = -1
        set(v) { field = v; invalidate() }
    var onClip: (Int) -> Unit = {}
    /** A tap on the square at the seam before piece k (its transition). */
    var onSeam: (Int) -> Unit = {}
    /** A piece dragged by the finger to another place: (from, its new index). */
    var onClipDrop: (Int, Int) -> Unit = { _, _ -> }

    /** The user moved the time ([dragging] while the finger or the fling still moves it). */
    var onScrub: (Long, Boolean) -> Unit = { _, _ -> }
    var onSelect: (String?) -> Unit = {}
    var onChange: (EBlock, Boolean) -> Unit = { _, _ -> }

    private val dp = resources.displayMetrics.density
    private val rulerH = 22 * dp
    private val stripH = 46 * dp
    private val laneH = 36 * dp
    private val gap = 8 * dp
    private val handleW = 14 * dp
    private var pxPerMs = 60 * dp / 1000f
    private var lanes: Map<String, Int> = emptyMap()
    private var laneCount = 1

    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = 1 * dp }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 10 * dp }
    private val stripBg = Paint().apply { color = 0xFF2A2D33.toInt() }
    private val thumbP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimP = Paint().apply { color = 0x99000000.toInt() }
    private val blockP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFFFFFFFF.toInt() }
    private val handleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val gripP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14161B.toInt(); strokeWidth = 2 * dp; strokeCap = Paint.Cap.ROUND }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 12 * dp; typeface = Typeface.DEFAULT_BOLD }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dp }
    private val snapP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt(); strokeWidth = 1.5f * dp }
    private val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 11 * dp; textAlign = Paint.Align.CENTER }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6000000.toInt() }
    private val wordP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); textSize = 11 * dp }
    private val wordTick = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1 * dp }
    private val sepP = Paint().apply { color = 0xFF15171B.toInt() }
    private val holdP = Paint().apply { color = 0x4D7DD3FC }
    private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 10 * dp; typeface = Typeface.DEFAULT_BOLD }
    private val clipSelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFFFFD60A.toInt() }
    private val seamP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt(); strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND }
    private val liftP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val keyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val seamBox = 18 * dp
    private val seamBoxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val seamOnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val seamTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14161B.toInt(); textSize = 11 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val seamOnTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14161B.toInt(); textSize = 12 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    /** Colours for a light panel (the light theme of the app); dark is the default. */
    fun light(on: Boolean) {
        val ink = if (on) 0xFF1D1B20.toInt() else 0xFFFFFFFF.toInt()
        tickP.color = (ink and 0x00FFFFFF) or 0x99000000.toInt()
        labelP.color = (ink and 0x00FFFFFF) or 0xCC000000.toInt()
        wordP.color = (ink and 0x00FFFFFF) or 0xD9000000.toInt()
        wordTick.color = (ink and 0x00FFFFFF) or 0x66000000
        headP.color = ink
        stripBg.color = if (on) 0xFFD5D7DD.toInt() else 0xFF2A2D33.toInt()
        sepP.color = if (on) 0xFFE9EAEE.toInt() else 0xFF15171B.toInt()
        dimP.color = if (on) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        seamBoxP.color = if (on) 0xFF1D1B20.toInt() else 0xF2FFFFFF.toInt()
        seamTextP.color = if (on) 0xFFFFFFFF.toInt() else 0xFF14161B.toInt()
        invalidate()
    }

    private fun relayout() {
        lanes = StudioEdit.lanes(blocks)
        val n = (lanes.values.maxOrNull() ?: -1) + 1
        if (n != laneCount) {
            laneCount = n
            requestLayout()
        }
    }

    private fun lanesShown() = (laneCount + 1).coerceIn(2, 5)
    private val wordsH get() = if (words.isEmpty()) 0f else 20 * dp
    private val lanesTop get() = rulerH + stripH + wordsH + gap
    private fun laneHeight() = if (laneCount + 1 > 5) laneH * 5 / (laneCount + 1) else laneH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (lanesTop + lanesShown() * laneH + 4 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    fun setPlayhead(ms: Long) {
        val v = ms.coerceIn(0, totalMs)
        if (v != playMs) {
            playMs = v
            invalidate()
        }
    }

    /** Starting zoom: about 12 seconds across the screen (a shorter video fills 90 % of it). */
    fun fitAll() {
        if (width > 0) pxPerMs = (width * 0.9f / min(totalMs, 12_000L)).coerceIn(minPx(), maxPx())
        invalidate()
    }

    private fun minPx() = if (width > 0) width * 0.25f / totalMs else 0.001f
    private fun maxPx() = 500 * dp / 1000f

    private fun xOf(ms: Long) = width / 2f + (ms - playMs) * pxPerMs
    private fun msAt(x: Float) = playMs + ((x - width / 2f) / pxPerMs).toLong()

    private fun color(kind: String) = when (kind) {
        "scheme" -> 0xFF6750A4.toInt()
        "image" -> 0xFF00796B.toInt()
        EditSplit.KIND -> 0xFF1565C0.toInt()
        EditMusic.KIND -> 0xFF2E7D32.toInt()
        "text" -> 0xFFB45309.toInt()
        else -> 0xFFC2185B.toInt()
    }

    private fun label(b: EBlock): String = when (b.kind) {
        "scheme" -> if (b.scheme == null) "🧩 ⏳ " + (if (b.req.isNotEmpty()) t("ждёт ответ ИИ #", "awaits AI answer #") + b.req else t("ИИ рисует…", "AI drawing…"))
        else "🧩 " + (b.scheme.title.takeIf { it.isNotBlank() } ?: b.scheme.nodes.firstOrNull()?.label.orEmpty())
        "image" -> "🖼 " + t("Картинка", "Picture")
        EditInsert.KIND -> "🎞 " + (if (b.file.isEmpty()) "⏳ " + (if (b.req.isNotEmpty()) t("ждёт ответ ИИ #", "awaits AI answer #") + b.req else t("ИИ рисует…", "AI drawing…"))
        else b.text.ifBlank { t("Анимация", "Animation") })
        EditSplit.KIND -> "⊟ " + t("Экран надвое", "Split screen")
        EditMusic.KIND -> (if (b.text.startsWith("🎙")) "" else "🎵 ") + b.text.ifBlank { t("Музыка", "Music") } + if (b.volume <= 0f) " 🔇" else ""
        "text" -> "T " + b.text.replace('\n', ' ')
        else -> b.text
    }

    private fun rectOf(b: EBlock, lane: Int): RectF {
        val lh = laneHeight()
        val top = lanesTop + lane * lh
        return RectF(xOf(b.startMs), top + 3 * dp, xOf(b.endMs), top + lh - 3 * dp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        // ruler
        val step = StudioEdit.tickStep(pxPerMs, 64 * dp)
        val first = (msAt(0f) / step - 1).coerceAtLeast(0) * step
        var t0 = first
        while (t0 <= min(totalMs, msAt(w) + step)) {
            val x = xOf(t0)
            c.drawLine(x, rulerH - 7 * dp, x, rulerH, tickP)
            val half = x + step * pxPerMs / 2
            if (t0 + step / 2 <= totalMs) c.drawLine(half, rulerH - 3 * dp, half, rulerH, tickP)
            val s = if (step >= 1000) StudioEdit.clock(t0).substringBefore('.') else StudioEdit.clock(t0)
            c.drawText(s, x + 3 * dp, rulerH - 9 * dp, labelP)
            t0 += step
        }
        // frames of the video
        val sx0 = xOf(0).coerceAtLeast(0f)
        val sx1 = xOf(totalMs).coerceAtMost(w)
        val stripTop = rulerH
        if (sx1 > sx0) {
            val strip = RectF(xOf(0), stripTop, xOf(totalMs), stripTop + stripH)
            c.save()
            c.clipPath(Path().apply { addRoundRect(strip, 6 * dp, 6 * dp, Path.Direction.CW) })
            c.drawRect(strip, stripBg)
            val cl = clips.ifEmpty { listOf(VClip("c0", 0, totalMs)) }
            val st = if (clips.isEmpty()) longArrayOf(0) else clipStarts
            val cw = max(16 * dp, stripH * aspect)
            val src = Rect()
            val dst = RectF()
            for (i in cl.indices) {
                val ck = cl[i]
                val l = xOf(st[i])
                val r = xOf(st[i] + ck.outMs)
                if (r < 0 || l > w) continue
                c.save()
                c.clipRect(l, stripTop, r, stripTop + stripH)
                if (thumbs.isNotEmpty() || srcThumbs.isNotEmpty()) {
                    // frames of this piece from its own left edge: a cut is seen as a seam
                    var x = l + ((max(l, 0f) - l) / cw).toInt() * cw
                    while (x < min(r, w)) {
                        val off = msAt(x + cw / 2) - st[i]
                        val at = if (ck.isHold) ck.fromMs else (ck.fromMs + (off * ck.speed).toLong()).coerceIn(ck.fromMs, ck.toMs)
                        val bmp = nearest(at, ck.src)
                        if (bmp != null) {
                            val ratio = cw / stripH
                            val bw = bmp.width.toFloat()
                            val bh = bmp.height.toFloat()
                            if (bw / bh > ratio) {
                                val sw = bh * ratio
                                src.set(((bw - sw) / 2).toInt(), 0, ((bw + sw) / 2).toInt(), bh.toInt())
                            } else {
                                val sh = bw / ratio
                                src.set(0, ((bh - sh) / 2).toInt(), bw.toInt(), ((bh + sh) / 2).toInt())
                            }
                            dst.set(x, stripTop, x + cw, stripTop + stripH)
                            c.drawBitmap(bmp, src, dst, thumbP)
                        }
                        x += cw
                    }
                }
                if (ck.isHold) c.drawRect(l, stripTop, r, stripTop + stripH, holdP)
                val badge = EditTrack.badges(ck)
                if (badge.isNotEmpty() && r - l > 24 * dp) {
                    val bw = badgeP.measureText(badge) + 8 * dp
                    val bx = max(l, 0f) + 4 * dp
                    c.drawRoundRect(bx, stripTop + 4 * dp, bx + bw, stripTop + 18 * dp, 5 * dp, 5 * dp, tagBg)
                    c.drawText(badge, bx + 4 * dp, stripTop + 15 * dp, badgeP)
                }
                c.restore()
                if (i > 0) c.drawRect(l - 1.5f * dp, stripTop, l + 1.5f * dp, stripTop + stripH, sepP)
            }
            c.restore()
            // a square at every seam, like CapCut: a tap opens its transition; filled when there is one
            if (cl.size > 1 && mode != REORDER) for (i in 1 until cl.size) {
                val x = xOf(st[i])
                if (x < -seamBox || x > w + seamBox) continue
                val cy = stripTop + stripH / 2
                val r = RectF(x - seamBox / 2, cy - seamBox / 2, x + seamBox / 2, cy + seamBox / 2)
                val on = cl[i].trans.isNotEmpty()
                c.drawRoundRect(r, 4 * dp, 4 * dp, if (on) seamOnP else seamBoxP)
                c.drawText(if (on) "⇆" else "|", r.centerX(), r.centerY() + seamTextP.textSize * 0.36f, if (on) seamOnTextP else seamTextP)
            }
            if (clipSel in cl.indices && mode != REORDER) {
                val l = xOf(st[clipSel])
                val r = xOf(st[clipSel] + cl[clipSel].outMs)
                c.drawRoundRect(RectF(l + dp, stripTop + dp, r - dp, stripTop + stripH - dp), 6 * dp, 6 * dp, clipSelP)
            }
            if (mode == REORDER && dragClip in cl.indices) {
                // the lifted piece: its place dimmed, the place it will go to marked, a copy under the finger
                val d = cl[dragClip]
                c.drawRect(xOf(st[dragClip]), stripTop, xOf(st[dragClip] + d.outMs), stripTop + stripH, liftP)
                if (dropSeam >= 0) {
                    val sx = if (dropSeam < cl.size) xOf(st[dropSeam]) else xOf(totalMs)
                    c.drawLine(sx, stripTop - 4 * dp, sx, stripTop + stripH + 4 * dp, seamP)
                }
                val bw = max(stripH, min(xOf(d.outMs) - xOf(0), 110 * dp))
                val box = RectF(lastX - bw / 2, stripTop - 6 * dp, lastX + bw / 2, stripTop + stripH - 6 * dp)
                val bmp = nearest(if (d.isHold) d.fromMs else (d.fromMs + d.toMs) / 2, d.src)
                c.save()
                c.clipPath(Path().apply { addRoundRect(box, 6 * dp, 6 * dp, Path.Direction.CW) })
                c.drawRect(box, stripBg)
                if (bmp != null) {
                    val ratio = box.width() / box.height()
                    val bw2 = bmp.width.toFloat()
                    val bh2 = bmp.height.toFloat()
                    if (bw2 / bh2 > ratio) {
                        val sw = bh2 * ratio
                        src.set(((bw2 - sw) / 2).toInt(), 0, ((bw2 + sw) / 2).toInt(), bh2.toInt())
                    } else {
                        val sh = bw2 / ratio
                        src.set(0, ((bh2 - sh) / 2).toInt(), bw2.toInt(), ((bh2 + sh) / 2).toInt())
                    }
                    c.drawBitmap(bmp, src, box, thumbP)
                }
                c.restore()
                c.drawRoundRect(box, 6 * dp, 6 * dp, clipSelP)
            }
        }
        // words of the voice: a label where there is room, a tick for every word
        if (words.isNotEmpty()) {
            val top = rulerH + stripH
            val from = msAt(-40 * dp)
            val to = msAt(w + 40 * dp)
            var lastEnd = -1f
            for (wd in words) {
                if (wd.endMs < from) continue
                if (wd.startMs > to) break
                val x = xOf(wd.startMs)
                c.drawLine(x, top + 3 * dp, x, top + 7 * dp, wordTick)
                val tw = wordP.measureText(wd.text)
                if (x > lastEnd + 4 * dp) {
                    c.drawText(wd.text, x + 2 * dp, top + 16 * dp, wordP)
                    lastEnd = x + 2 * dp + tw
                }
            }
        }
        // blocks
        val lay = if (mode == IDLE) lanes else frozen
        for (b in blocks) {
            val r = rectOf(b, lay[b.id] ?: 0)
            if (r.right < 0 || r.left > w) continue
            val isSel = b.id == selected
            blockP.color = color(b.kind)
            blockP.alpha = if (isSel) 255 else 220
            val rad = 7 * dp
            c.drawRoundRect(r, rad, rad, blockP)
            c.save()
            c.clipRect(r.left + (if (isSel) handleW else 4 * dp), r.top, r.right - (if (isSel) handleW else 4 * dp), r.bottom)
            val tx = max(r.left, 0f) + (if (isSel) handleW + 4 * dp else 8 * dp)
            c.drawText(label(b), tx, r.centerY() + nameP.textSize * 0.36f, nameP)
            c.restore()
            // keyframes: small diamonds on the bar
            for (k in b.keys) {
                val kx = xOf(b.startMs + k.atMs)
                if (kx < r.left - 1 || kx > r.right + 1) continue
                val ky = r.bottom - 5 * dp
                c.drawPath(Path().apply {
                    moveTo(kx, ky - 4 * dp); lineTo(kx + 4 * dp, ky); lineTo(kx, ky + 4 * dp); lineTo(kx - 4 * dp, ky); close()
                }, keyP)
            }
            if (isSel) {
                c.drawRoundRect(r, rad, rad, selP)
                for (left in listOf(true, false)) {
                    val hr = if (left) RectF(r.left, r.top, r.left + handleW, r.bottom) else RectF(r.right - handleW, r.top, r.right, r.bottom)
                    c.drawRoundRect(hr, rad, rad, handleP)
                    c.drawLine(hr.centerX(), hr.top + hr.height() * 0.3f, hr.centerX(), hr.bottom - hr.height() * 0.3f, gripP)
                }
                if (mode == MOVE || mode == TRIM_L || mode == TRIM_R) {
                    val s = StudioEdit.secs(b.durMs) + t(" с", " s") + "  ·  " + StudioEdit.clock(b.startMs)
                    val tw = tagP.measureText(s) / 2 + 6 * dp
                    val cx = r.centerX().coerceIn(tw, w - tw)
                    c.drawRoundRect(cx - tw, r.top - 20 * dp, cx + tw, r.top - 3 * dp, 8 * dp, 8 * dp, tagBg)
                    c.drawText(s, cx, r.top - 7.5f * dp, tagP)
                }
            }
        }
        // after the end of the video
        if (xOf(totalMs) < w) c.drawRect(xOf(totalMs), rulerH, w, height.toFloat(), dimP)
        snapAt?.let { val x = xOf(it); c.drawLine(x, rulerH, x, height.toFloat(), snapP) }
        // playhead
        val px = w / 2
        c.drawLine(px, 2 * dp, px, height.toFloat(), headP)
        c.drawCircle(px, 4 * dp, 4 * dp, headP)
    }

    private fun nearest(ms: Long, src: String = ""): Bitmap? {
        val list = if (src.isEmpty()) thumbs else srcThumbs[src].orEmpty()
        if (list.isEmpty()) return null
        var lo = 0
        var hi = list.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (list[mid].first < ms) lo = mid + 1 else hi = mid
        }
        val a = list[lo]
        val b = list.getOrNull(lo - 1)
        return if (b != null && abs(b.first - ms) < abs(a.first - ms)) b.second else a.second
    }

    // ---------- gestures ----------

    private companion object {
        const val IDLE = 0
        const val SCROLL = 1
        const val MOVE = 2
        const val TRIM_L = 3
        const val TRIM_R = 4
        const val ZOOM = 5
        const val PENDING = 6
        const val REORDER = 7
    }

    private var dragClip = -1
    private var dropSeam = -1

    /** A long press on the frames lifts that piece of the main track to drag it. */
    private val clipLongPress = Runnable {
        if (mode != PENDING || clips.size < 2) return@Runnable
        val at = msAt(downX)
        if (at < 0 || at >= totalMs) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragClip = EditTrack.locate(clips, at).index
        frozen = lanes
        mode = REORDER
        parent?.requestDisallowInterceptTouchEvent(true)
        updateSeam(lastX)
        postOnAnimation(autoScroll)
        invalidate()
    }

    /** The seam nearest to the finger at [x]: 0 = before the first piece, size = after the last. */
    private fun updateSeam(x: Float) {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in 0..clips.size) {
            val sx = if (k < clips.size) xOf(clipStarts[k]) else xOf(totalMs)
            val d = abs(sx - x)
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        if (best != dropSeam) {
            if (dropSeam >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            dropSeam = best
        }
    }

    private var mode = IDLE
    private var frozen: Map<String, Int> = emptyMap()
    private var target: EBlock? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downPlay = 0L
    private var downPointerMs = 0L
    private var snapAt: Long? = null
    private var live: EBlock? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(ctx)
    private val edge = 36 * dp

    private val longPress = Runnable {
        val b = target ?: return@Runnable
        if (mode != PENDING) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (b.id != selected) onSelect(b.id)
        selected = b.id
        startDrag(MOVE, b)
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (mode != MOVE && mode != TRIM_L && mode != TRIM_R && mode != REORDER) return
            if (live == null && mode != REORDER) {
                // not dragged yet: the finger only rests near the edge
                postOnAnimation(this)
                return
            }
            val dir = when {
                lastX < edge -> -1
                lastX > width - edge -> 1
                else -> 0
            }
            if (dir != 0) {
                setPlayhead(playMs + (dir * 8 * dp / pxPerMs).toLong())
                onScrub(playMs, true)
                if (mode == REORDER) {
                    updateSeam(lastX)
                    invalidate()
                } else drag(lastX)
            }
            postOnAnimation(this)
        }
    }

    private val zoom = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            removeCallbacks(longPress)
            if (mode == MOVE || mode == TRIM_L || mode == TRIM_R || mode == REORDER) return false
            removeCallbacks(clipLongPress)
            mode = ZOOM
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * d.scaleFactor).coerceIn(minPx(), maxPx())
            invalidate()
            return true
        }
    })

    private fun startDrag(m: Int, b: EBlock) {
        mode = m
        target = b
        live = null
        frozen = lanes
        downPointerMs = msAt(lastX)
        parent?.requestDisallowInterceptTouchEvent(true)
        postOnAnimation(autoScroll)
        invalidate()
    }

    /** Applies the drag of a block for the finger at [x] (the time under the finger follows the scrolling too). */
    private fun drag(x: Float) {
        val b0 = target ?: return
        val delta = msAt(x) - downPointerMs
        val th = (10 * dp / pxPerMs).toLong()
        val targets = StudioEdit.snapTargets(blocks, b0.id, playMs, totalMs, words)
        val old = snapAt
        val b = when (mode) {
            MOVE -> {
                val (s, hit) = StudioEdit.snapMove(b0.startMs + delta, b0.durMs, targets, th)
                snapAt = hit
                StudioEdit.move(b0, s, totalMs)
            }
            TRIM_L -> {
                val raw = b0.startMs + delta
                val s = StudioEdit.snap(raw, targets, th)
                snapAt = s
                StudioEdit.trimStart(b0, s ?: raw)
            }
            else -> {
                val raw = b0.endMs + delta
                val s = StudioEdit.snap(raw, targets, th)
                snapAt = s
                StudioEdit.trimEnd(b0, s ?: raw, totalMs)
            }
        }
        if (snapAt != null && snapAt != old) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        live = b
        onChange(b, false)
    }

    private fun blockAt(x: Float, y: Float): EBlock? {
        if (y < lanesTop) return null
        val lane = ((y - lanesTop) / laneHeight()).toInt()
        return blocks.lastOrNull { b -> (lanes[b.id] ?: 0) == lane && rectOf(b, lane).let { x >= it.left - (if (b.id == selected) handleW / 2 else 0f) && x <= it.right + (if (b.id == selected) handleW / 2 else 0f) } }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset() && mode == IDLE) {
            setPlayhead((scroller.currX / pxPerMs).toLong())
            onScrub(playMs, !scroller.isFinished)
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        zoom.onTouchEvent(e)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x
                downY = e.y
                lastX = e.x
                downPlay = playMs
                snapAt = null
                val hit = blockAt(e.x, e.y)
                target = hit
                parent?.requestDisallowInterceptTouchEvent(true)
                if (hit != null && hit.id == selected) {
                    val r = rectOf(hit, lanes[hit.id] ?: 0)
                    when {
                        e.x <= r.left + handleW * 1.2f -> startDrag(TRIM_L, hit)
                        e.x >= r.right - handleW * 1.2f -> startDrag(TRIM_R, hit)
                        else -> startDrag(MOVE, hit)
                    }
                    // a tap without moving still counts as a tap (see ACTION_UP)
                    downPointerMs = msAt(e.x)
                } else {
                    mode = PENDING
                    if (hit != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    else if (clips.size > 1 && e.y >= rulerH && e.y < rulerH + stripH) postDelayed(clipLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                removeCallbacks(clipLongPress)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                when (mode) {
                    PENDING -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        removeCallbacks(clipLongPress)
                        mode = SCROLL
                    }
                    REORDER -> {
                        updateSeam(e.x)
                        invalidate()
                    }
                    SCROLL -> {
                        setPlayhead(downPlay - ((e.x - downX) / pxPerMs).toLong())
                        onScrub(playMs, true)
                    }
                    MOVE, TRIM_L, TRIM_R -> if (live != null || abs(e.x - downX) > slop) drag(e.x)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                removeCallbacks(clipLongPress)
                removeCallbacks(autoScroll)
                val m = mode
                mode = IDLE
                when (m) {
                    PENDING -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val wordsTop = rulerH + stripH
                        val inStrip = target == null && e.y >= rulerH && e.y < wordsTop
                        val at = msAt(e.x)
                        val hitWord = if (target == null && words.isNotEmpty() && e.y >= wordsTop && e.y < wordsTop + wordsH) {
                            val at = msAt(e.x)
                            words.minByOrNull { kotlin.math.abs(it.startMs - at) }?.takeIf { kotlin.math.abs(xOf(it.startMs) - e.x) < 40 * dp }
                        } else null
                        val seam = if (inStrip && clips.size > 1) (1 until clips.size).firstOrNull { abs(xOf(clipStarts[it]) - e.x) < seamBox * 0.8f } else null
                        if (seam != null) onSeam(seam)
                        else if (inStrip && at in 0 until totalMs) {
                            // a tap on the frames selects that piece of the main track
                            onClip(if (clips.isEmpty()) 0 else EditTrack.locate(clips, at).index)
                        } else if (hitWord != null) {
                            // a tap on a word puts the playhead on it
                            setPlayhead(hitWord.startMs)
                            onScrub(playMs, false)
                        } else onSelect(target?.id)
                    }
                    SCROLL -> {
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        if (abs(vx) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                            scroller.fling((playMs * pxPerMs).toInt(), 0, -vx.toInt(), 0, 0, (totalMs * pxPerMs).toInt(), 0, 0)
                            postInvalidateOnAnimation()
                        } else onScrub(playMs, false)
                    }
                    MOVE, TRIM_L, TRIM_R -> live?.let { onChange(it, true) }
                    ZOOM -> onScrub(playMs, false)
                    REORDER -> {
                        if (e.actionMasked == MotionEvent.ACTION_UP && dragClip >= 0 && dropSeam >= 0) {
                            val to = EditTrack.dropIndex(dragClip, dropSeam)
                            if (to != dragClip && to in clips.indices) onClipDrop(dragClip, to)
                        }
                        onScrub(playMs, false)
                        dragClip = -1
                        dropSeam = -1
                    }
                }
                velocity?.recycle()
                velocity = null
                live = null
                target = null
                snapAt = null
                relayout()
                invalidate()
                return true
            }
        }
        return true
    }
}

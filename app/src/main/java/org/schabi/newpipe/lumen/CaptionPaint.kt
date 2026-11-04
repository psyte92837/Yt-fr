/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Captions in the built-in styles ([TextStyles]) and the word motions ([EditCaptions.fx]), one painter for every
 * place that draws subtitles: the Editor ([StudioEditPainter]), the Studio videos ([StudioPainter]) and the Mix.
 * [face] gives the typeface of a font value ("lf:<id>", "serif", "file:<name>"…). Times are ms from the caption start.
 */
class CaptionPaint(private val face: (String) -> Typeface) {
    val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fits = HashMap<String, Pair<Float, List<String>>>()

    private fun light(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255 > 0.5

    /** Largest text size whose wrapped lines fit [maxW]×[maxH] (with the current typeface and spacing of [textP]). */
    fun fit(text: String, maxW: Float, maxH: Float): Pair<Float, List<String>> = fits.also { if (it.size > 300) it.clear() }
        .getOrPut("$text|${maxW.toInt()}|${maxH.toInt()}|${System.identityHashCode(textP.typeface)}|${textP.letterSpacing}") {
            var lo = 6f
            var hi = max(8f, maxH)
            var best = lo to wrap(text, maxW, lo)
            repeat(18) {
                val mid = (lo + hi) / 2
                val lines = wrap(text, maxW, mid)
                textP.textSize = mid
                val ok = lines.size * mid * 1.18f <= maxH && lines.all { textP.measureText(it) <= maxW }
                if (ok) {
                    best = mid to lines
                    lo = mid
                } else hi = mid
            }
            best
        }

    private fun wrap(text: String, maxW: Float, size: Float): List<String> {
        textP.textSize = size
        val out = mutableListOf<String>()
        for (para in text.lines()) {
            var cur = ""
            for (word in para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && textP.measureText(next) > maxW) {
                    out += cur
                    cur = word
                } else cur = next
            }
            out += cur
        }
        return out.ifEmpty { listOf("") }
    }

    /** How the words of a caption are inked: text, accent, the plate under the said word, outline and shadow. */
    class CapInk(
        val ink: Int, val hot: Int, val boxColor: Int, val stroke: Float = 0f, val strokeColor: Int = 0xE6000000.toInt(),
        val shadow: String = "none", val shadowColor: Int = 0, val dimPending: Boolean = false
    )

    private val blackFace: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    /** A caption in a built-in style ([TextStyles]): the plate, then the words with their outline, shadow and motion. */
    fun styled(c: Canvas, box: RectF, text0: String, st: TextStyle, hot: Int, font0: String = "", upper0: Boolean? = null,
               motion0: String = st.motion, wordMs: List<Long> = emptyList(), at: Long = -1, reveal: Float = 1f) {
        val font = font0.ifEmpty { st.font }
        val oldFace = textP.typeface
        val oldSpacing = textP.letterSpacing
        textP.typeface = if (font.isEmpty()) blackFace else face(font)
        textP.letterSpacing = st.spacing
        try {
            val text = (if (upper0 ?: st.upper) text0.uppercase() else text0).trim()
            val m = min(box.width(), box.height())
            val bare = st.plate == "none" || st.plate == "under"
            val padX = if (bare) box.width() * 0.04f else min(m * 0.3f, box.width() * 0.09f)
            val padY = if (bare) box.height() * 0.06f else min(m * 0.2f, box.height() * 0.14f)
            val (size, lines) = fit(text, box.width() - padX * 2, box.height() - padY * 2)
            if (st.tilt != 0f) {
                c.save()
                c.rotate(st.tilt, box.centerX(), box.centerY())
            }
            plate(c, st, box, lines, size)
            val motion = if (wordMs.isNotEmpty() && at >= 0) motion0 else ""
            words(c, box, lines, size, CapInk(st.ink, hot, hot, st.stroke, st.strokeColor, st.shadow, st.shadowColor), motion, wordMs, at, reveal)
            if (st.tilt != 0f) c.restore()
        } finally {
            textP.typeface = oldFace
            textP.letterSpacing = oldSpacing
            textP.clearShadowLayer()
        }
    }

    /** The plate of a built-in style around the laid-out [lines] of [size]. */
    private fun plate(c: Canvas, st: TextStyle, box: RectF, lines: List<String>, size: Float) {
        if (st.plate == "none") return
        textP.textSize = size
        val step = size * 1.18f
        val widths = lines.map { textP.measureText(it) }
        val tw = widths.maxOrNull() ?: 0f
        val th = step * lines.size
        val px = size * 0.42f
        val py = size * 0.16f
        val cx = box.centerX()
        val cy = box.centerY()
        val top = cy - th / 2
        fill.color = st.plateColor
        when (st.plate) {
            "box", "tag" -> c.drawRoundRect(cx - tw / 2 - px, top - py, cx + tw / 2 + px, top + th + py, size * 0.22f, size * 0.22f, fill)
            "pill" -> {
                val r = min(th / 2 + py, size * 0.9f)
                c.drawRoundRect(cx - tw / 2 - px * 1.3f, top - py, cx + tw / 2 + px * 1.3f, top + th + py, r, r, fill)
            }
            "band" -> c.drawRect(box.left, top - py, box.right, top + th + py, fill)
            "outline" -> {
                line.color = st.plateColor
                line.strokeWidth = size * 0.08f
                c.drawRoundRect(cx - tw / 2 - px, top - py, cx + tw / 2 + px, top + th + py, size * 0.18f, size * 0.18f, line)
            }
            "line" -> widths.forEachIndexed { i, w ->
                val yc = top + step * (i + 0.5f)
                c.drawRoundRect(cx - w / 2 - px * 0.7f, yc - step * 0.5f, cx + w / 2 + px * 0.7f, yc + step * 0.5f, size * 0.2f, size * 0.2f, fill)
            }
            "under" -> widths.forEachIndexed { i, w ->
                val yc = top + step * (i + 0.5f)
                c.drawRoundRect(cx - w / 2 - size * 0.1f, yc + size * 0.02f, cx + w / 2 + size * 0.1f, yc + size * 0.42f, size * 0.1f, size * 0.1f, fill)
            }
        }
    }

    /**
     * A caption drawn word by word ([EBlock.motion], [EditCaptions.fx]) — "" draws every word as it is. The words stand
     * where the whole caption would stand; [reveal] < 1 types the letters of the whole caption.
     */
    fun words(c: Canvas, box: RectF, lines: List<String>, size: Float, ci: CapInk, motion: String, wordMs: List<Long>, at: Long, reveal: Float = 1f) {
        textP.textSize = size
        val step = size * 1.18f
        val fm = textP.fontMetrics
        var y = box.centerY() - step * lines.size / 2 + step / 2 - (fm.ascent + fm.descent) / 2
        val mid = (fm.ascent + fm.descent) / 2
        val words = lines.map { l -> l.split(' ').filter { it.isNotEmpty() } }
        val total = words.sumOf { it.size }
        val n = wordMs.size
        val cur = if (motion.isEmpty()) -1 else wordMs.indexOfLast { it <= at }
        val space = textP.measureText(" ")
        var left = if (reveal >= 0.999f) Int.MAX_VALUE else kotlin.math.ceil(lines.sumOf { it.length } * reveal).toInt()
        textP.textAlign = Paint.Align.LEFT
        var k = 0
        for (ws in words) {
            val widths = ws.map { textP.measureText(it) }
            var x = box.centerX() - (widths.sum() + space * (ws.size - 1).coerceAtLeast(0)) / 2
            for ((j, w0) in ws.withIndex()) {
                // the words of the text and the said words may differ in number (an edited caption): spread evenly
                val i = if (total == n) k else (k.toLong() * n / total.coerceAtLeast(1)).toInt()
                k++
                val ww = widths[j]
                val fx = if (motion.isEmpty()) EditCaptions.WordFx()
                else EditCaptions.fx(motion, i, cur, if (i <= cur) wordMs.getOrNull(i)?.let { ((at - it) / 220f).coerceIn(0f, 1f) } ?: 1f else 0f)
                var w = w0
                if (fx.chars < 0.999f) w = w.take(kotlin.math.ceil(w.length * fx.chars).toInt())
                if (left < w.length) w = w.take(left.coerceAtLeast(0))
                left -= w0.length + 1
                var alpha = fx.alpha
                if (fx.pending && ci.dimPending) alpha *= 0.55f
                if (alpha > 0.01f && w.isNotEmpty()) {
                    c.save()
                    val cx = x + ww / 2
                    val cy = y + mid
                    if (fx.dx != 0f || fx.dy != 0f) c.translate(fx.dx * size, fx.dy * size)
                    if (fx.rot != 0f) c.rotate(fx.rot, cx, cy)
                    if (fx.scale != 1f) c.scale(fx.scale, fx.scale, cx, cy)
                    val a = (alpha * 255).roundToInt().coerceIn(0, 255)
                    var color = if (fx.hot) ci.hot else ci.ink
                    val pad = size * 0.14f
                    if (fx.box) {
                        fill.color = ci.boxColor
                        fill.alpha = a
                        c.drawRoundRect(x - pad, y + fm.ascent * 0.92f, x + ww + pad, y + fm.descent * 1.1f, size * 0.2f, size * 0.2f, fill)
                        color = if (light(ci.boxColor)) 0xFF14161B.toInt() else Color.WHITE
                    }
                    if (fx.marker > 0.01f) {
                        fill.color = ci.hot
                        fill.alpha = (0xE6 * alpha).roundToInt()
                        c.drawRoundRect(x - pad * 0.6f, y + fm.ascent * 0.78f, x - pad * 0.6f + (ww + pad * 1.2f) * fx.marker, y + fm.descent * 0.9f,
                            size * 0.12f, size * 0.12f, fill)
                        if (fx.marker > 0.5f) color = if (light(ci.hot)) 0xFF14161B.toInt() else Color.WHITE
                    }
                    if (fx.underline > 0.01f) {
                        fill.color = ci.hot
                        fill.alpha = a
                        c.drawRoundRect(x, y + size * 0.12f, x + ww * fx.underline, y + size * 0.22f, size * 0.05f, size * 0.05f, fill)
                    }
                    val plain = fx.box || fx.marker > 0.5f
                    // flat shadows: an offset copy (hard) or a long trail (long)
                    if (!plain && (ci.shadow == "hard" || ci.shadow == "long")) {
                        textP.color = ci.shadowColor
                        textP.alpha = (Color.alpha(ci.shadowColor) * alpha).roundToInt()
                        val steps = if (ci.shadow == "long") 6 else 1
                        for (s in steps downTo 1) {
                            val d = size * (if (ci.shadow == "long") 0.022f * s else 0.07f)
                            c.drawText(w, x + d, y + d, textP)
                        }
                    }
                    val soft = when {
                        fx.glow -> Triple(size * 0.45f, 0f, ci.hot)
                        plain -> null
                        ci.shadow == "drop" -> Triple(size * 0.1f, size * 0.05f, ci.shadowColor)
                        ci.shadow == "glow" -> Triple(size * 0.35f, 0f, ci.shadowColor)
                        else -> null
                    }
                    if (ci.stroke > 0f && !plain) {
                        textP.style = Paint.Style.STROKE
                        textP.strokeWidth = size * ci.stroke
                        textP.strokeJoin = Paint.Join.ROUND
                        textP.color = ci.strokeColor
                        textP.alpha = (Color.alpha(ci.strokeColor) * alpha).roundToInt()
                        soft?.let { (r, d, col) -> textP.setShadowLayer(r, 0f, d, col) }
                        c.drawText(w, x, y, textP)
                        textP.clearShadowLayer()
                        textP.style = Paint.Style.FILL
                    } else soft?.let { (r, d, col) -> textP.setShadowLayer(r, 0f, d, col) }
                    textP.color = color
                    textP.alpha = (Color.alpha(color) * alpha).roundToInt()
                    c.drawText(w, x, y, textP)
                    textP.clearShadowLayer()
                    c.restore()
                }
                x += ww + space
            }
            y += step
        }
        fill.alpha = 255
        textP.alpha = 255
        textP.textAlign = Paint.Align.CENTER
    }
}

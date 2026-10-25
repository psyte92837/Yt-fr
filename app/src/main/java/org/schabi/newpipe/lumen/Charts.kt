/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Draws a vertical bar chart into [r]; shared by the on-screen view and the PDF report. */
object ChartDraw {
    fun bars(
        c: Canvas,
        r: RectF,
        values: List<Double>,
        labels: List<String>,
        barColor: Int,
        textColor: Int,
        density: Float,
        valueFmt: (Double) -> String = { Fmt.num(it.toLong()) }
    ) {
        if (values.isEmpty()) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 10f * density
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
        val grid = Paint().apply {
            color = (textColor and 0x00FFFFFF) or 0x22000000
            strokeWidth = density
        }
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        val top = r.top + 14 * density
        val bottom = r.bottom - 16 * density
        val h = bottom - top
        for (i in 0..3) {
            val y = bottom - h * i / 3f
            c.drawLine(r.left, y, r.right, y, grid)
        }
        c.drawText(valueFmt(max), r.left, r.top + 10 * density, text)
        val n = values.size
        val slot = (r.width()) / n
        val bw = (slot * 0.7f).coerceAtLeast(1f)
        val labelEvery = maxOf(1, (n * 34 * density / r.width()).toInt() + 1)
        values.forEachIndexed { i, v ->
            val x = r.left + slot * i + (slot - bw) / 2
            val bh = (h * (v / max)).toFloat()
            c.drawRoundRect(RectF(x, bottom - bh, x + bw, bottom), 3 * density, 3 * density, bar)
            if (i % labelEvery == 0 && i < labels.size) {
                c.drawText(labels[i], x, r.bottom - 3 * density, text)
            }
        }
    }

    /** Horizontal bars with labels, for "top" lists. */
    fun hbars(c: Canvas, r: RectF, items: List<Pair<String, Double>>, barColor: Int, textColor: Int, density: Float) {
        if (items.isEmpty()) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 12f * density
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
        val max = items.maxOf { it.second }.coerceAtLeast(1e-9)
        val rowH = r.height() / items.size
        items.forEachIndexed { i, (label, v) ->
            val y = r.top + rowH * i
            val w = (r.width() * (v / max)).toFloat()
            c.drawRoundRect(RectF(r.left, y + rowH * 0.12f, r.left + w, y + rowH * 0.88f), 4 * density, 4 * density, bar)
            val s = label.take(40) + "  " + Fmt.num(v.toLong())
            c.drawText(s, r.left + 6 * density, y + rowH * 0.5f + 4 * density, text)
        }
    }
}

class BarChartView(ctx: Context) : View(ctx) {
    var values: List<Double> = emptyList()
    var labels: List<String> = emptyList()
    var horizontal: List<Pair<String, Double>> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (horizontal.isNotEmpty()) context.dp(26) * horizontal.size + context.dp(8) else context.dp(180)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val textColor = Palette.MUTED
        val r = RectF(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        if (horizontal.isNotEmpty()) {
            ChartDraw.hbars(canvas, r, horizontal, Palette.ACCENT_SOFT, Palette.ON_SOFT, d)
        } else {
            ChartDraw.bars(canvas, r, values, labels, Palette.ACCENT, textColor, d)
        }
    }
}

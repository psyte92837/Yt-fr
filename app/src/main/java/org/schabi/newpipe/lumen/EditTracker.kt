/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «🎯 Прилипнуть к объекту»: follows a piece of the picture through small grey frames by template matching (sum of absolute
 * differences around the last place, the template slowly adapting), and turns the path into keyframes of a block.
 * Pure, no Android: frames are grey levels 0..255, [w]×[h] each.
 */
object EditTracker {
    /** The place of the [start] piece (fractions of the frame) in each of [frames]; the first is [start] itself. */
    fun track(frames: List<IntArray>, w: Int, h: Int, start: FRect, search: Float = 0.1f): List<FRect> {
        if (frames.isEmpty() || w < 8 || h < 8) return emptyList()
        val pw = max(6, (start.w * w).roundToInt()).coerceAtMost(w)
        val ph = max(6, (start.h * h).roundToInt()).coerceAtMost(h)
        var px = (start.l * w).roundToInt().coerceIn(0, w - pw)
        var py = (start.t * h).roundToInt().coerceIn(0, h - ph)
        val tpl = FloatArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) tpl[y * pw + x] = frames[0][(py + y) * w + px + x].toFloat()
        val out = mutableListOf(FRect(px.toFloat() / w, py.toFloat() / h, (px + pw).toFloat() / w, (py + ph).toFloat() / h))
        val r = max(2, (search * max(w, h)).roundToInt())
        val step = if (pw * ph > 1600) 2 else 1
        for (f in 1 until frames.size) {
            val img = frames[f]
            var best = Float.MAX_VALUE
            var bx = px
            var by = py
            fun sad(ox: Int, oy: Int, limit: Float): Float {
                var s = 0f
                var y = 0
                while (y < ph) {
                    val row = (oy + y) * w + ox
                    var x = 0
                    while (x < pw) {
                        s += abs(img[row + x] - tpl[y * pw + x])
                        x += step
                    }
                    if (s >= limit) return s
                    y += step
                }
                return s
            }
            // coarse then fine
            var oy = max(0, py - r)
            while (oy <= min(h - ph, py + r)) {
                var ox = max(0, px - r)
                while (ox <= min(w - pw, px + r)) {
                    val s = sad(ox, oy, best)
                    if (s < best) {
                        best = s
                        bx = ox
                        by = oy
                    }
                    ox += 2
                }
                oy += 2
            }
            for (dy in -1..1) for (dx in -1..1) {
                val ox = (bx + dx).coerceIn(0, w - pw)
                val oy2 = (by + dy).coerceIn(0, h - ph)
                val s = sad(ox, oy2, best)
                if (s < best) {
                    best = s
                    bx = ox
                    by = oy2
                }
            }
            px = bx
            py = by
            // the template follows slow changes of light and shape
            for (y in 0 until ph) for (x in 0 until pw) tpl[y * pw + x] = tpl[y * pw + x] * 0.8f + img[(py + y) * w + px + x] * 0.2f
            out += FRect(px.toFloat() / w, py.toFloat() / h, (px + pw).toFloat() / w, (py + ph).toFloat() / h)
        }
        return out
    }

    /** Keyframes that move [b] with the path: its place shifts as the tracked piece's centre does. */
    fun keys(b: EBlock, path: List<FRect>, timesMs: List<Long>): List<EKey> {
        if (path.isEmpty() || path.size != timesMs.size) return emptyList()
        val cx0 = (path[0].l + path[0].r) / 2
        val cy0 = (path[0].t + path[0].b) / 2
        return path.indices.map { i ->
            val cx = (path[i].l + path[i].r) / 2
            val cy = (path[i].t + path[i].b) / 2
            EKey((timesMs[i] - b.startMs).coerceIn(0L, b.durMs), (b.x + cx - cx0).coerceIn(0f, 1f), (b.y + cy - cy0).coerceIn(0f, 1f), b.w, b.rot, b.opacity)
        }.distinctBy { it.atMs }
    }

    /** Grey level of an ARGB colour. */
    fun grey(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import kotlin.math.max

/**
 * Lottie blocks of the editor (kind "lottie"): a Lottie JSON in the project folder drawn by the painter (vector, any size).
 * [EBlock.look] is the time mode: "fit" — once over the block, "loop" — at its own speed, repeated; markers intro / loop /
 * outro of the file make segments (the middle repeats). Texts of the file are replaced by [EditBrief.fields] "lottie:<text>".
 * Pure helpers here; drawing is in [StudioEditPainter].
 */
object EditLottie {
    const val KIND = "lottie"
    val MODES = listOf("fit", "loop")

    /** What a Lottie file is: size, frame rate, length, markers (name → start frame, length), texts of its text layers. */
    data class Info(val w: Int, val h: Int, val fps: Double, val inFrame: Double, val outFrame: Double,
                    val markers: Map<String, Pair<Double, Double>>, val texts: List<String>) {
        val ms: Long get() = ((outFrame - inFrame) / max(1.0, fps) * 1000).toLong()
    }

    /** Reads the head of a Lottie JSON; null — not a Lottie animation. */
    fun info(json: String): Info? = runCatching {
        val o = JSONObject(json)
        if (!o.has("layers") || !o.has("op")) return null
        val markers = o.optJSONArray("markers")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.associate { m ->
                m.optString("cm").trim().lowercase() to (m.optDouble("tm", 0.0) to m.optDouble("dr", 0.0))
            }
        }.orEmpty()
        val texts = mutableListOf<String>()
        fun scan(layers: org.json.JSONArray?) {
            if (layers == null) return
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                if (l.optInt("ty") == 5) {
                    val k = l.optJSONObject("t")?.optJSONObject("d")?.optJSONArray("k")
                    val s = k?.optJSONObject(0)?.optJSONObject("s")?.optString("t")
                    if (!s.isNullOrBlank()) texts += s.replace('\r', '\n').trim()
                }
            }
        }
        scan(o.optJSONArray("layers"))
        o.optJSONArray("assets")?.let { a -> for (i in 0 until a.length()) scan(a.optJSONObject(i)?.optJSONArray("layers")) }
        Info(o.optInt("w"), o.optInt("h"), o.optDouble("fr", 30.0), o.optDouble("ip", 0.0), o.optDouble("op", 0.0), markers, texts.distinct().take(12))
    }.getOrNull()

    /** Segments of the file in ms (intro, loop, outro) from its markers «intro», «loop», «outro»; null — none. */
    fun segments(i: Info): Triple<Long, Long, Long>? {
        val loop = i.markers["loop"] ?: return null
        fun ms(f: Double) = (f / max(1.0, i.fps) * 1000).toLong()
        val intro = ms(loop.first - i.inFrame)
        val outro = i.markers["outro"]?.let { ms(it.second) } ?: 0L
        return if (loop.second > 0) Triple(max(0, intro), ms(loop.second), outro) else null
    }

    /** Progress 0..1 of the file at [tMs] of a block [durMs] long. */
    fun progress(tMs: Long, durMs: Long, i: Info, mode: String): Float {
        val len = max(1L, i.ms)
        val seg = segments(i)
        val ms = when {
            seg != null -> AnimV11.segTime(tMs, durMs, seg.first, seg.second, seg.third)
            mode == "loop" -> tMs % len
            else -> tMs * len / max(1L, durMs)
        }
        return (ms.toFloat() / len).coerceIn(0f, 1f)
    }
}

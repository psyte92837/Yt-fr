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
 * Colour of a piece of the main track (like Filters and Adjust in CapCut / YouCut): a preset filter with its
 * strength and four sliders, as one 4×5 colour matrix in the layout of android.graphics.ColorMatrix (offsets on
 * the 0..255 scale). The same matrix is used by the preview (a colour filter on the TextureView), the GPU export
 * (a shader) and the CPU export ([applyTo]). Transitions at the seams: a dip to black or white, or a flash.
 * Pure, no Android.
 */
object EditColor {
    /** Filter ids in menu order ("" = none). */
    val FILTERS = listOf("", "vivid", "warm", "cool", "film", "fade", "vintage", "sepia", "bw", "noir", "pink", "teal")

    fun filterName(id: String): String = when (id) {
        "vivid" -> t("Сочный", "Vivid")
        "warm" -> t("Тёплый", "Warm")
        "cool" -> t("Холодный", "Cool")
        "film" -> t("Кино", "Film")
        "fade" -> t("Блёклый", "Faded")
        "vintage" -> t("Винтаж", "Vintage")
        "sepia" -> t("Сепия", "Sepia")
        "bw" -> t("Ч/Б", "B&W")
        "noir" -> t("Нуар", "Noir")
        "pink" -> t("Розовый", "Rose")
        "teal" -> t("Бирюза", "Teal")
        else -> t("Нет", "None")
    }

    /** Transitions into a piece from the one before it ("" = a plain cut). */
    val TRANSITIONS = listOf("", "black", "white", "flash")
    val TRANS_MS = listOf(300L, 500L, 800L, 1200L)
    const val DEFAULT_TRANS_MS = 500L

    fun transName(id: String): String = when (id) {
        "black" -> t("Через чёрный", "Dip to black")
        "white" -> t("Через белый", "Dip to white")
        "flash" -> t("Вспышка", "Flash")
        else -> t("Без перехода", "None")
    }

    private val ID = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    fun identity(): FloatArray = ID.copyOf()

    fun isIdentity(m: FloatArray): Boolean = m.indices.all { abs(m[it] - ID[it]) < 1e-4f }

    /** [a] after [b]: the colour goes through [b] first. */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                var s = 0f
                for (k in 0 until 4) s += a[i * 5 + k] * b[k * 5 + j]
                if (j == 4) s += a[i * 5 + 4]
                r[i * 5 + j] = s
            }
        }
        return r
    }

    /** Between no change (k = 0) and [m] (k = 1). */
    fun mix(m: FloatArray, k: Float): FloatArray = FloatArray(20) { ID[it] + (m[it] - ID[it]) * k }

    private fun scale(r: Float, g: Float, b: Float, off: Float = 0f, offR: Float = off, offG: Float = off, offB: Float = off) = floatArrayOf(
        r, 0f, 0f, 0f, offR,
        0f, g, 0f, 0f, offG,
        0f, 0f, b, 0f, offB,
        0f, 0f, 0f, 1f, 0f
    )

    /** Saturation: 0 = grey, 1 = as is, 2 = twice as colourful. */
    fun saturation(s: Float): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val k = 1 - s
        return floatArrayOf(
            lr * k + s, lg * k, lb * k, 0f, 0f,
            lr * k, lg * k + s, lb * k, 0f, 0f,
            lr * k, lg * k, lb * k + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Contrast around the middle grey: 1 = as is. */
    fun contrast(c: Float): FloatArray = scale(c, c, c, 128f * (1 - c))

    fun brightness(b: Float): FloatArray = scale(1f, 1f, 1f, b)

    /** Warmer (> 0, towards orange) or cooler (< 0, towards blue). */
    fun warmth(w: Float): FloatArray = scale(1f + 0.12f * w, 1f + 0.02f * w, 1f - 0.12f * w, 0f, 12f * w, 2f * w, -12f * w)

    private fun sepia(): FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** The full-strength matrix of filter [id]. */
    fun filter(id: String): FloatArray = when (id) {
        "vivid" -> concat(contrast(1.08f), saturation(1.4f))
        "warm" -> concat(warmth(0.8f), saturation(1.1f))
        "cool" -> concat(warmth(-0.8f), saturation(1.05f))
        "film" -> concat(scale(1.02f, 1f, 0.96f, 0f, 6f, 0f, 10f), concat(contrast(1.15f), saturation(0.85f)))
        "fade" -> concat(scale(1f, 1f, 1f, 22f), concat(contrast(0.8f), saturation(0.8f)))
        "vintage" -> concat(scale(1f, 1f, 1f, 0f, 18f, 8f, -6f), concat(contrast(0.9f), mix(sepia(), 0.55f)))
        "sepia" -> sepia()
        "bw" -> concat(contrast(1.1f), saturation(0f))
        "noir" -> concat(contrast(1.45f), saturation(0f))
        "pink" -> concat(scale(1.06f, 0.97f, 1.04f, 0f, 10f, 0f, 8f), saturation(1.1f))
        "teal" -> concat(scale(0.94f, 1.02f, 1.06f, 0f, -6f, 4f, 10f), concat(contrast(1.1f), saturation(1.1f)))
        else -> identity()
    }

    /** The colour of piece [c]: its filter at its strength, then the sliders; null when the picture is unchanged. */
    fun matrix(c: VClip): FloatArray? {
        if (!c.hasColor) return null
        var m = mix(filter(c.filter), c.filterK.coerceIn(0f, 1f))
        if (c.contrast != 0f) m = concat(contrast(1f + c.contrast * 0.6f), m)
        if (c.sat != 0f) m = concat(saturation(1f + c.sat), m)
        if (c.warm != 0f) m = concat(warmth(c.warm), m)
        if (c.bright != 0f) m = concat(brightness(c.bright * 70f), m)
        return m.takeIf { !isIdentity(it) }
    }

    /** For a GLSL shader: the 3×3 part column by column and the offsets on the 0..1 scale. */
    fun gl(m: FloatArray): Pair<FloatArray, FloatArray> {
        val mat = FloatArray(9)
        for (col in 0 until 3) for (row in 0 until 3) mat[col * 3 + row] = m[row * 5 + col]
        return mat to floatArrayOf(m[4] / 255f, m[9] / 255f, m[14] / 255f)
    }

    /** The matrix applied to the first [n] ARGB pixels of [pix] in place (the CPU export). */
    fun applyTo(pix: IntArray, m: FloatArray, n: Int = pix.size) {
        for (i in 0 until n) {
            val p = pix[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val nr = (m[0] * r + m[1] * g + m[2] * b + m[4]).roundToInt().coerceIn(0, 255)
            val ng = (m[5] * r + m[6] * g + m[7] * b + m[9]).roundToInt().coerceIn(0, 255)
            val nb = (m[10] * r + m[11] * g + m[12] * b + m[14]).roundToInt().coerceIn(0, 255)
            pix[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** A colour laid over the picture at a moment of a transition. */
    data class Fade(val color: Int, val alpha: Float)

    /**
     * The transition seen at output time [tMs]: around the seam before each piece with a transition the picture goes
     * to the colour and back; on the first piece it comes in from the colour at the very start. Null between them.
     */
    fun fadeAt(clips: List<VClip>, tMs: Long): Fade? {
        if (clips.isEmpty()) return null
        val st = EditTrack.starts(clips)
        var best: Fade? = null
        for (k in clips.indices) {
            val c = clips[k]
            if (c.trans.isEmpty()) continue
            val half = max(1L, c.transMs / 2)
            val seam = st[k]
            val d = abs(tMs - seam)
            if (k == 0) {
                // the start of the video: from the colour into the picture over the whole length
                if (tMs >= c.transMs) continue
                val a = 1f - tMs.toFloat() / c.transMs
                if (best == null || a > best.alpha) best = Fade(color(c.trans), shape(c.trans, a))
                continue
            }
            if (d >= half) continue
            val a = 1f - d.toFloat() / half
            if (best == null || a > best.alpha) best = Fade(color(c.trans), shape(c.trans, a))
        }
        return best?.takeIf { it.alpha > 0.002f }
    }

    private fun color(trans: String): Int = if (trans == "black") 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** A flash is short and bright: it peaks harder and falls away faster than a dip. */
    private fun shape(trans: String, a: Float): Float = if (trans == "flash") min(1f, a * a * 1.2f) else a

    /** [fade] laid over the first [n] ARGB pixels of [pix] in place. */
    fun blend(pix: IntArray, fade: Fade, n: Int = pix.size) {
        val a = fade.alpha.coerceIn(0f, 1f)
        val k = 1f - a
        val cr = ((fade.color shr 16) and 0xFF) * a
        val cg = ((fade.color shr 8) and 0xFF) * a
        val cb = (fade.color and 0xFF) * a
        for (i in 0 until n) {
            val p = pix[i]
            val r = (((p shr 16) and 0xFF) * k + cr).roundToInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * k + cg).roundToInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * k + cb).roundToInt().coerceIn(0, 255)
            pix[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * The canvas of the editor (like "Format" in CapCut, "Canvas" in YouCut): the shape of the exported frame
 * ([EProject.ratio], "" = the shape of the video), whether the video fits into it with a background colour or
 * fills it (the edges cut off). Pure, no Android.
 */
object EditCanvas {
    val RATIOS = listOf("", "9:16", "16:9", "1:1", "4:5", "3:4", "4:3")
    val BACKGROUNDS = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF1F2024.toInt(), 0xFFF3E9DC.toInt(), 0xFF0B2545.toInt(),
        0xFF1B5E20.toInt(), 0xFF880E4F.toInt(), 0xFFFFD60A.toInt(), 0xFF6750A4.toInt(), 0xFFFF7043.toInt()
    )

    fun ratioName(r: String): String = when (r) {
        "9:16" -> t("9:16 · Shorts, Reels, TikTok", "9:16 · Shorts, Reels, TikTok")
        "16:9" -> t("16:9 · YouTube", "16:9 · YouTube")
        "1:1" -> t("1:1 · квадрат", "1:1 · square")
        "4:5" -> t("4:5 · лента Instagram", "4:5 · Instagram feed")
        "3:4" -> "3:4"
        "4:3" -> "4:3"
        else -> t("Как у видео", "Original")
    }

    /** "9:16" → 9/16 (width / height); null for the shape of the video or nonsense. */
    fun aspect(r: String): Float? {
        val a = r.substringBefore(':').toFloatOrNull() ?: return null
        val b = r.substringAfter(':', "").toFloatOrNull() ?: return null
        return if (a > 0f && b > 0f) a / b else null
    }

    /** The frame for a [vw]×[vh] video in shape [ratio]: the video fits in it whole, one of its sides kept. */
    fun frame(vw: Int, vh: Int, ratio: String): Pair<Int, Int> {
        val a = aspect(ratio) ?: return vw to vh
        if (vw <= 0 || vh <= 0) return vw to vh
        val va = vw.toFloat() / vh
        return if (a > va) (vh * a).roundToInt() to vh else vw to (vw / a).roundToInt()
    }

    fun frame(p: EProject): Pair<Int, Int> = frame(p.w, p.h, p.ratio)

    /** The size of the exported video: the frame, even, its long side at most [maxSide]. */
    fun outSize(vw: Int, vh: Int, ratio: String, maxSide: Int): Pair<Int, Int> {
        val (fw, fh) = frame(vw, vh, ratio)
        return StudioEdit.outSize(fw, fh, maxSide)
    }

    /** Where a [vw]×[vh] video goes in a [fw]×[fh] frame (pixels, centred): whole inside it, or covering it when [fill]. */
    fun videoRect(vw: Float, vh: Float, fw: Float, fh: Float, fill: Boolean): FRect {
        if (vw <= 0f || vh <= 0f) return FRect(0f, 0f, fw, fh)
        val k = if (fill) max(fw / vw, fh / vh) else min(fw / vw, fh / vh)
        val w = vw * k
        val h = vh * k
        return FRect((fw - w) / 2, (fh - h) / 2, (fw + w) / 2, (fh + h) / 2)
    }

    /** The project's frame differs from its video: the export draws the video into a rectangle on the background. */
    fun reshaped(p: EProject): Boolean = aspect(p.ratio) != null && frame(p) != (p.w to p.h)
}

/**
 * Music and sounds over the video (block kind "audio", like the audio track in CapCut): a file in the project
 * folder ([EBlock.file]) heard from its moment [EBlock.srcMs] over the time of the block, at [EBlock.volume], fading in
 * and out when [EBlock.animIn] / [EBlock.animOut] is "fade"; the video itself at [EBlock.mainVolume] meanwhile.
 * Pure, no Android.
 */
object EditMusic {
    const val KIND = "audio"
    const val FADE_MS = 1500L

    fun isSound(b: EBlock): Boolean = b.kind == KIND && b.file.isNotEmpty()

    /** The sound blocks heard at [tMs] (several can overlap). */
    fun active(blocks: List<EBlock>, tMs: Long): List<EBlock> = blocks.filter { isSound(it) && tMs >= it.startMs && tMs < it.endMs }

    /** Loudness of [b] at output time [tMs], with its fades (0 outside the block). */
    fun gain(b: EBlock, tMs: Long): Float {
        if (tMs < b.startMs || tMs >= b.endMs) return 0f
        val fade = min(FADE_MS, b.durMs / 2).coerceAtLeast(1)
        var g = b.volume
        if (b.animIn == "fade") g *= ((tMs - b.startMs).toFloat() / fade).coerceIn(0f, 1f)
        if (b.animOut == "fade") g *= ((b.endMs - tMs).toFloat() / fade).coerceIn(0f, 1f)
        return g
    }

    /** Loudness of the video itself at [tMs]: quieter under music that asks for it. */
    fun mainVolume(blocks: List<EBlock>, tMs: Long): Float = active(blocks, tMs).fold(1f) { v, b -> v * b.mainVolume }

    /** Sounds that change the export. */
    fun mixes(blocks: List<EBlock>): Boolean = blocks.any { isSound(it) && (it.volume > 0f || it.mainVolume != 1f) }
}

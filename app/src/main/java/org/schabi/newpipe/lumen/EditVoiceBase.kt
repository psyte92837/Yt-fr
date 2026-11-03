/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * "🎙 Project from a voice-over" of the editor: a calm background video exactly as long as the voice-over, with it
 * as the sound, becomes the base of an editor project; captions, inserts, pictures and videos in a window go on top.
 * Pure part; the encoding is [FrameEncoder] in [StudioEditorActivity].
 */
object EditVoiceBase {
    /** Frames per second of the background: it does not move, few frames keep the file small and the encoding fast. */
    const val FPS = 15

    /** Frame size of the base: 1080 by the short side, the long one by the format (both even). */
    fun size(portrait: Boolean): Pair<Int, Int> = if (portrait) 1080 to 1920 else 1920 to 1080

    /** Frames for [durMs] at [fps] (at least one). */
    fun frames(durMs: Long, fps: Int = FPS): Int = maxOf(1, ((durMs * fps + 999) / 1000).toInt())

    /** Background colours: top and bottom of a soft vertical gradient. */
    val BACKGROUNDS = listOf(
        "night" to (0xFF0F1419.toInt() to 0xFF1E2A38.toInt()),
        "ink" to (0xFF111111.toInt() to 0xFF2A2A2A.toInt()),
        "paper" to (0xFFF4F1EA.toInt() to 0xFFE2DCCF.toInt()),
        "ocean" to (0xFF0B2545.toInt() to 0xFF13315C.toInt()),
        "plum" to (0xFF2D1B3D.toInt() to 0xFF4A2C5E.toInt())
    )

    /** ARGB pixels of a [w]×[h] frame going from [top] to [bottom] colour. */
    fun gradient(w: Int, h: Int, top: Int, bottom: Int): IntArray {
        val out = IntArray(w * h)
        for (yy in 0 until h) {
            val k = if (h > 1) yy.toFloat() / (h - 1) else 0f
            fun ch(c: Int, sh: Int) = (c shr sh) and 0xFF
            fun m(sh: Int) = (ch(top, sh) + (ch(bottom, sh) - ch(top, sh)) * k + 0.5f).toInt().coerceIn(0, 255)
            val c = (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
            java.util.Arrays.fill(out, yy * w, yy * w + w, c)
        }
        return out
    }
}

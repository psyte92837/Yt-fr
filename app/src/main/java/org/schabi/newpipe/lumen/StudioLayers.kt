/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/** What is drawn over the picture: subtitles, schemes (graphics, headlines, shapes, stickers), both or nothing. */
enum class StudioLayers(val id: String, val subs: Boolean, val graphics: Boolean) {
    ALL("all", true, true), SUBS("subs", true, false), GRAPHICS("graphics", false, true), NONE("none", false, false);

    companion object {
        fun of(id: String): StudioLayers = entries.firstOrNull { it.id == id } ?: ALL

        /** Size choices of a scheme over a background, % of its full-screen size. */
        val SIZES = listOf(30, 40, 50, 60, 75, 100)

        fun scale(percent: Int): Float = percent.coerceIn(SIZES.first(), SIZES.last()) / 100f

        /** Does the scene carry a scheme at all (not only words)? */
        fun hasScheme(kind: String, items: Int): Boolean = kind != "text" &&
            (items > 0 || Studio.isTpl(kind) || kind in listOf("step", "stat", "big", "quote", "scheme"))

        /**
         * Centre (fraction of the height) of a scheme drawn over a background: the upper part, so that the
         * subtitles below stay clear; a small scheme moves up, without subtitles it is centred.
         */
        /** Schemes in an animation with characters: off, on a board above the characters, or big with the characters smaller. */
        val TOON_MODES = listOf("off", "board", "big")

        fun toonMode(id: String): String = id.takeIf { it in TOON_MODES } ?: "board"

        /** Size of the characters during a scheme scene (1 = as usual). */
        fun toonScale(mode: String, scheme: Boolean): Float = if (scheme && toonMode(mode) == "big") 0.62f else 1f

        /** Scale of the scheme in an animation: on the board it leaves room for the characters, big takes most of the frame. */
        fun toonSchemeScale(mode: String): Float = if (toonMode(mode) == "big") 1f else 0.8f

        fun overY(scale: Float, subs: Boolean): Float = if (!subs) 0.5f else (0.42f - (1f - scale) * 0.12f).coerceIn(0.3f, 0.42f)
    }
}

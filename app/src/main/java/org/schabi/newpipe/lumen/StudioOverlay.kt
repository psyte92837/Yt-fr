/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/**
 * What the Studio puts over the picture — one simple choice in every section of the Studio (Shorts, video, voice-over,
 * cuts, animation, Mix): [mode] subtitles or info-schemes; everything else are options with good defaults
 * (protocol lumen.overlay v1). The Editor keeps its own full set of tools.
 *
 * [bg] — background of the blocks (schemes, tiles, cards), as in the Editor: solid, glass (semi-transparent) or clear
 * (no plate: text and emoji only, with a soft shadow so they stay readable over a live video).
 * [look] — a built-in look ([SchemeStyles.BUILT_IN]). [size] — s/m/l. [place] — auto (over the picture), side
 * (the speaker's frame slides aside), pip (the speaker into a corner) or full (full-screen inserts).
 * [toon] — animation with characters: the scheme on a board or big. [amount] — how many scenes get a scheme.
 * [full] — what the moments show where a scheme would cover the whole video (place full, or a cut montage scene with
 * no clip of its own): the full-screen insert, only the blocks on a transparent background over the video, only the
 * subtitles, or nothing (off: the video plays on clean). See [FullShow].
 */
data class StudioOverlay(
    val mode: String = SCHEMES,
    val subsToo: Boolean = true,
    val bg: String = "solid",
    val look: String = SchemeStyles.AUTO,
    val size: String = "m",
    val place: String = "auto",
    val toon: String = "board",
    val amount: String = "norm",
    val full: String = FULL,
    /** A built-in caption style for the subtitles ([TextStyles] id), shared with the Editor; "" = the subtitles of the video style. */
    val subStyle: String = ""
) {
    val schemes get() = mode == SCHEMES
    val subs get() = mode == SUBS || subsToo

    /** What the painter draws: subtitles only, or the graphics (with or without subtitles). */
    fun layers(): StudioLayers = when {
        !schemes -> StudioLayers.SUBS
        subsToo -> StudioLayers.ALL
        else -> StudioLayers.GRAPHICS
    }

    /** Size of a block over a background, fraction of its full-screen size. */
    fun overScale(): Float = when (size) {
        "s" -> 0.7f
        "l" -> 0.95f
        else -> 0.82f
    }

    /** Graphics over the picture (video, clip, photo); false = full-screen inserts instead of the picture. */
    fun overlay(): Boolean = place != "full"

    /** What the full-screen moments show; the subtitles mode never covers the video: subtitles only. */
    fun fullMode(): String = if (schemes) full else SUBS_ONLY

    /** Some scheme reaches the viewer: false when every scheme would be full-screen and those moments show none. */
    fun anySchemes(): Boolean = schemes && !(place == "full" && !fullShow(full).blocks)

    /** [StudioPlan.schemePlace] of the montage. */
    fun schemePlace(): String = when (place) {
        "side", "pip" -> place
        else -> "overlay"
    }

    /** How many scenes of [scenes] may get a scheme. */
    fun maxSchemes(scenes: Int): Int = when (amount) {
        "few" -> (scenes / 5).coerceIn(1, 4)
        "many" -> (scenes / 2).coerceIn(1, 14)
        else -> StudioScheme.maxSchemes(scenes)
    }

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("mode", mode).put("subs", subsToo).put("bg", bg).put("look", look)
        .put("size", size).put("place", place).put("toon", toon).put("amount", amount).put("full", full).put("sub", subStyle)

    /**
     * What a scene that would be a full-screen scheme insert shows under [full]: [insert] — the insert itself
     * (the video is hidden); otherwise the video plays on and gets the [blocks] of the scheme on a transparent
     * background and/or the [subs].
     */
    data class FullShow(val insert: Boolean, val blocks: Boolean, val subs: Boolean)

    companion object {
        const val SUBS = "subs"
        const val SCHEMES = "schemes"
        val MODES = listOf(SUBS, SCHEMES)
        val BGS = listOf("solid", "glass", "clear")
        val SIZES = listOf("s", "m", "l")
        val PLACES = listOf("auto", "side", "pip", "full")
        val TOONS = listOf("board", "big")
        val AMOUNTS = listOf("few", "norm", "many")

        /** Full-screen moments: the insert as before, blocks only (transparent, no subtitles), subtitles only, off. */
        const val FULL = "full"
        const val BLOCKS_ONLY = "blocks"
        const val SUBS_ONLY = "subs"
        const val OFF = "off"
        val FULLS = listOf(FULL, BLOCKS_ONLY, SUBS_ONLY, OFF)

        private fun one(v: String?, list: List<String>, def: String) = v?.trim()?.lowercase()?.takeIf { it in list } ?: def

        fun fromJson(o: JSONObject?): StudioOverlay {
            val d = StudioOverlay()
            if (o == null) return d
            return StudioOverlay(
                mode = one(o.optString("mode"), MODES, d.mode), subsToo = o.optBoolean("subs", d.subsToo),
                bg = one(o.optString("bg"), BGS, d.bg),
                look = o.optString("look").trim().let { SchemeStyles.alias(it) }.takeIf { id -> SchemeStyles.BUILT_IN.any { it.id == id } } ?: d.look,
                size = one(o.optString("size"), SIZES, d.size), place = one(o.optString("place"), PLACES, d.place),
                toon = one(o.optString("toon"), TOONS, d.toon), amount = one(o.optString("amount"), AMOUNTS, d.amount),
                full = one(o.optString("full"), FULLS, d.full),
                subStyle = o.optString("sub").trim().takeIf { TextStyles.of(it) != null }.orEmpty()
            )
        }

        fun parse(s: String?): StudioOverlay? = s?.takeIf { it.isNotBlank() }?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

        /**
         * From the settings of the old screens (what to overlay, scheme over the background on/off, size %, scheme
         * style and placement, schemes in the animation): nothing the user chose is lost.
         */
        fun migrate(layers: String, overlay: Boolean, overSize: Int, style: String, place: String, toon: String): StudioOverlay {
            val l = StudioLayers.of(layers)
            val st = SchemeStyles.alias(style)
            val bg = when {
                style == "minimal" -> "clear"
                style == "glass" || style == "podcast_side" -> "glass"
                else -> "solid"
            }
            return StudioOverlay(
                mode = if (l.graphics) SCHEMES else SUBS,
                subsToo = l.subs || !l.graphics,
                bg = bg,
                look = st.takeIf { id -> SchemeStyles.BUILT_IN.any { it.id == id } } ?: SchemeStyles.AUTO,
                size = when {
                    overSize < 75 -> "s"
                    overSize > 90 -> "l"
                    else -> "m"
                },
                place = when {
                    !overlay -> "full"
                    place == "side" || style == "podcast_side" && place == "style" -> "side"
                    place == "pip" || style == "podcast_pip" && place == "style" -> "pip"
                    else -> "auto"
                },
                toon = if (toon == "big") "big" else "board"
            )
        }

        /**
         * The look of the schemes: the preset [look] with the background [bg]. Glass keeps the colour of the card at
         * part of its alpha; clear removes the plate and the frame, the text gets the colour that reads over a video
         * (the painter adds a soft shadow). The numeric rules of [SchemeStyles.enforce] are kept (contrast), the
         * "no plate → panel" rule is not: the user asked for a transparent block on purpose.
         */
        fun style(look: SchemeStyle, bg: String, place: String = "overlay"): SchemeStyle {
            val base = look.copy(place = place, size = 0)
            val card = base.card or (0xFF shl 24)
            val s = when (bg) {
                "glass" -> {
                    val dark = SchemeStyles.luminance(card) < 0.3
                    base.copy(fill = "glass", card = (card and 0x00FFFFFF) or ((if (dark) 0x99 else 0xC7) shl 24), shadow = true)
                }
                "clear" -> base.copy(fill = "none", card = 0, border = 0f, shadow = true, dim = 0f,
                    ink = 0xFFFFFFFF.toInt(), muted = 0xF2FFFFFF.toInt(), line = 0xE6FFFFFF.toInt())
                else -> base.copy(fill = "solid", card = card)
            }
            return SchemeStyles.enforce(s, bare = true).first
        }

        fun fullShow(full: String): FullShow = when (full) {
            BLOCKS_ONLY -> FullShow(insert = false, blocks = true, subs = false)
            SUBS_ONLY -> FullShow(insert = false, blocks = false, subs = true)
            OFF -> FullShow(insert = false, blocks = false, subs = false)
            else -> FullShow(insert = true, blocks = true, subs = true)
        }

        /** Alpha factor of the card fill of the built-in graphics (tiles, lists, numbers) for [bg]. */
        fun cardAlpha(bg: String): Float = when (bg) {
            "glass" -> 0.55f
            "clear" -> 0f
            else -> 1f
        }

        /**
         * Should the AI compose schemes now: the schemes mode is on, an AI is there, the scenes have none yet and the
         * AI was not already asked for this very script ([tried] = key of the script it was asked for).
         */
        fun needCompose(ov: StudioOverlay, hasAi: Boolean, have: Int, tried: String, key: String): Boolean =
            ov.anySchemes() && hasAi && have == 0 && tried != key
    }
}

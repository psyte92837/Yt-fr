/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/**
 * Extra settings of a Gemini request with videos (other providers ignore them): several links in one request
 * ([Llm.complete] fileUri — one link per line), frames per second of each video, a strict answer schema and
 * no thinking, so a batch of videos costs as little as possible. Live check 03.11.2026 on gemini-3.5-flash-lite:
 * the load is the video, not the text — 5 clips of ~4 min at LOW cost ~103k tokens at 1 fps and ~47k at 0.25 fps;
 * without a schema the model writes video numbers as strings and text instead of numbers.
 */
data class MediaOpts(
    /** Frames per second of every video (0 = the model default, 1). */
    val fps: Double = 0.0,
    /** responseSchema of the answer as JSON text (null = none). */
    val schema: String? = null,
    /** Turn the model's thinking down as far as it allows. */
    val noThinking: Boolean = false
) {
    companion object {
        /** Links of a request: one per line (the journal, the send review and another AI by hand show them as they are). */
        fun links(fileUri: String?): List<String> = fileUri.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * thinkingConfig for [model]: Gemini 3+ takes a level ("minimal"; a budget is rejected with 400), 2.5 Flash
         * takes a budget of 0; Pro models cannot turn it off (null).
         */
        fun thinking(model: String): JSONObject? {
            val m = model.lowercase()
            val ver = Regex("""gemini-(\d+)(?:\.(\d+))?""").find(m)?.let { it.groupValues[1].toInt() * 10 + (it.groupValues[2].toIntOrNull() ?: 0) }
            return when {
                ver == null -> null
                ver >= 30 -> JSONObject().put("thinkingLevel", "minimal")
                "flash" in m -> JSONObject().put("thinkingBudget", 0)
                else -> null
            }
        }
    }
}

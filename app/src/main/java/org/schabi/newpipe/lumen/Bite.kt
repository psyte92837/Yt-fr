/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Bite a video or audio": a piece of a YouTube video saved on the phone, as a video with sound (MP4) or the sound
 * only (M4A). Pure logic: the piece from the typed times, file names, the list of done bites (lumen.bite v1).
 */
object Bite {
    const val PROTOCOL = "lumen.bite v1"
    /** Longest piece. */
    const val MAX_MS = 60L * 60_000
    /** Shortest piece. */
    const val MIN_MS = 500L
    /** Done bites kept in the list. */
    const val KEEP = 30
    val QUALITIES = listOf(360, 480, 720, 1080)
    val LENGTHS_S = listOf(5, 10, 15, 30, 60)

    /** A piece [fromMs]..[toMs] or the reason why the typed times are not one. */
    data class Range(val fromMs: Long, val toMs: Long, val error: String = "") {
        val ok get() = error.isEmpty()
        val lenMs get() = toMs - fromMs
    }

    /** The piece from the typed start and end ("1:23", "83", "1m23s"); [durMs] is the video length (0 = not known). */
    fun range(fromText: String, toText: String, durMs: Long): Range {
        val a = StudioCuts.parseTime(fromText.ifBlank { "0" }) ?: return Range(0, 0, t("Не понял начало: «$fromText»", "Cannot read the start: \"$fromText\""))
        val b0 = if (toText.isBlank()) (if (durMs > 0) durMs else -1) else StudioCuts.parseTime(toText) ?: return Range(0, 0, t("Не понял конец: «$toText»", "Cannot read the end: \"$toText\""))
        if (b0 < 0) return Range(0, 0, t("Укажите конец отрезка", "Set the end of the piece"))
        val b = if (durMs > 0) minOf(b0, durMs) else b0
        if (durMs > 0 && a >= durMs) return Range(a, b, t("Начало дальше конца видео (${StudioCuts.stamp(durMs)})", "The start is past the end of the video (${StudioCuts.stamp(durMs)})"))
        if (b - a < MIN_MS) return Range(a, b, t("Конец должен быть позже начала", "The end must be after the start"))
        if (b - a > MAX_MS) return Range(a, b, t("Слишком длинно: не больше ${MAX_MS / 60_000} мин", "Too long: at most ${MAX_MS / 60_000} min"))
        return Range(a, b)
    }

    /** "1m23s" style stamp for file names. */
    fun fileStamp(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "%dh%02dm%02ds".format(s / 3600, s / 60 % 60, s % 60)
            s >= 60 -> "%dm%02ds".format(s / 60, s % 60)
            else -> "${s}s"
        }
    }

    /** Safe part of a title for a file name: letters, digits, '-', '_' (spaces → '_'), at most [max] characters. */
    fun safeTitle(title: String, max: Int = 40): String {
        val s = title.trim().map { c -> if (c.isLetterOrDigit() || c == '-') c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        return s.take(max).trimEnd('_').ifEmpty { "video" }
    }

    /** File name of a bite: Lumen_Bite_<title>_<from>-<to>.mp4 (.m4a for the sound only). */
    fun fileName(title: String, fromMs: Long, toMs: Long, audio: Boolean): String =
        "Lumen_Bite_${safeTitle(title)}_${fileStamp(fromMs)}-${fileStamp(toMs)}" + if (audio) ".m4a" else ".mp4"

    /** Piece of [len] seconds from [fromMs], cut to the video ([durMs] 0 = not known). */
    fun endFor(fromMs: Long, lenS: Int, durMs: Long): Long {
        val e = fromMs + lenS * 1000L
        return if (durMs > 0) minOf(e, durMs) else e
    }

    /** Download size in MB as a rough guess: bitrate × length (video 360p ≈ 0.7, 720p ≈ 2.5, 1080p ≈ 4.5 Mbit/s; sound 0.13). */
    fun sizeMb(lenMs: Long, audio: Boolean, height: Int): Double {
        val mbit = if (audio) 0.13 else 0.13 + when {
            height <= 360 -> 0.7
            height <= 480 -> 1.2
            height <= 720 -> 2.5
            else -> 4.5
        }
        return mbit * lenMs / 1000.0 / 8
    }

    /** One done bite: the local file, what it came from, where it was saved ("" = only in the app). */
    data class Done(
        val file: String, val title: String, val url: String, val fromMs: Long, val toMs: Long,
        val audio: Boolean, val savedAs: String = "", val at: Long = 0
    )

    fun toJson(list: List<Done>): String = JSONObject().put("protocol", PROTOCOL).put("items", JSONArray().apply {
        list.forEach { d ->
            put(JSONObject().put("file", d.file).put("title", d.title).put("url", d.url).put("from", d.fromMs).put("to", d.toMs)
                .put("audio", d.audio).put("saved", d.savedAs).put("at", d.at))
        }
    }).toString()

    fun fromJson(s: String): List<Done> = runCatching {
        val a = JSONObject(s).optJSONArray("items") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val f = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Done(f, o.optString("title"), o.optString("url"), o.optLong("from"), o.optLong("to"), o.optBoolean("audio"), o.optString("saved"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    /** [d] first, an older bite of the same file replaced, at most [KEEP]; returns the list and the files to delete. */
    fun add(list: List<Done>, d: Done): Pair<List<Done>, List<String>> {
        val all = listOf(d) + list.filter { it.file != d.file }
        return all.take(KEEP) to all.drop(KEEP).map { it.file }
    }
}

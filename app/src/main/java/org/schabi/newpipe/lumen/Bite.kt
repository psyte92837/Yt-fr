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
    val QUALITIES = listOf(240, 360, 480, 720, 1080)
    /** Sound quality: the best AAC (≈128 kbit/s) or the small one (≈48 kbit/s). */
    const val AUDIO_BEST = "best"
    const val AUDIO_SMALL = "small"
    val AUDIO_QUALITIES = listOf(AUDIO_BEST, AUDIO_SMALL)

    fun audioLabel(q: String): String = if (q == AUDIO_SMALL) t("Экономное ~48 кбит/с", "Small ~48 kbit/s") else t("Высокое ~128 кбит/с", "High ~128 kbit/s")

    fun videoLabel(h: Int): String = "${h}p" + when (h) {
        240 -> t(" · мало места", " · tiny")
        1080 -> " HD"
        else -> ""
    }
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
    fun sizeMb(lenMs: Long, audio: Boolean, height: Int, audioQ: String = AUDIO_BEST): Double {
        val sound = if (audioQ == AUDIO_SMALL) 0.05 else 0.13
        val mbit = if (audio) sound else sound + when {
            height <= 240 -> 0.35
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

    // ---------- range bar ----------

    /** Step of the range bar: finer on short videos. */
    fun stepMs(durMs: Long): Long = when {
        durMs <= 2 * 60_000 -> 100
        durMs <= 10 * 60_000 -> 500
        else -> 1000
    }

    /** [ms] rounded to [step] and kept inside 0..[durMs]. */
    fun snap(ms: Long, step: Long, durMs: Long): Long {
        val s = step.coerceAtLeast(1)
        val v = (ms + s / 2) / s * s
        return v.coerceIn(0, if (durMs > 0) durMs else Long.MAX_VALUE)
    }

    /**
     * New ends after one of them moved: [start] — the start was moved to [ms], otherwise the end. The other end gives
     * way so the piece stays at least [MIN_MS] long and inside the video.
     */
    fun moveEnd(fromMs: Long, toMs: Long, ms: Long, start: Boolean, durMs: Long): Pair<Long, Long> {
        val d = if (durMs > 0) durMs else Long.MAX_VALUE
        return if (start) {
            val a = ms.coerceIn(0, maxOf(0, d - MIN_MS))
            a to maxOf(toMs, a + MIN_MS).coerceAtMost(d)
        } else {
            val b = ms.coerceIn(minOf(MIN_MS, d), d)
            minOf(fromMs, b - MIN_MS).coerceAtLeast(0) to b
        }
    }

    /** The whole piece shifted by [deltaMs], its length kept, inside the video. */
    fun shift(fromMs: Long, toMs: Long, deltaMs: Long, durMs: Long): Pair<Long, Long> {
        val len = toMs - fromMs
        val maxA = if (durMs > 0) maxOf(0, durMs - len) else Long.MAX_VALUE
        val a = (fromMs + deltaMs).coerceIn(0, maxA)
        return a to a + len
    }

    /** Index of the frame (by time) nearest to [ms]; −1 when there are none. */
    fun nearest(times: List<Long>, ms: Long): Int = times.indices.minByOrNull { kotlin.math.abs(times[it] - ms) } ?: -1

    /** Start and end for a shared link: its ?t= (or 0) and [lenS] seconds after, inside the video. */
    fun initial(url: String, durMs: Long, lenS: Int = 15): Pair<Long, Long> {
        val a0 = StudioCuts.startOf(url) ?: 0L
        val a = if (durMs > 0 && a0 >= durMs) 0L else a0
        return a to endFor(a, lenS, durMs)
    }

    /** Overall progress of a bite (0..100): with both tracks the video takes 0–80 and the sound 80–100. */
    fun overall(p: Int, sound: Boolean, both: Boolean): Int {
        val q = p.coerceIn(0, 100)
        return if (!both) q else if (sound) 80 + q * 20 / 100 else q * 80 / 100
    }
}

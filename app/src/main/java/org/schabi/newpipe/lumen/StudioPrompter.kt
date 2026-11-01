/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Teleprompter that lights up the script word by word at a set speed (words per minute, changeable while
 * recording; pause and jump to a word by tap). Every lit word is logged with its time in the take, and the
 * analysis uses that log as the word timing instead of spreading the words over the speech.
 */

/** Word [word] (index in the script tokens) lit up at [ms] of the take. */
data class PromptMark(val word: Int, val ms: Long)

/** The highlight of the prompter: where it is and the log of what lit up when. Pure, driven by the recorder clock. */
class PrompterClock(val tokens: List<String>, wpm: Int) {
    private val weights = tokens.map { StudioPrompter.weight(it) }
    /** Start of every word in weight units. */
    private val starts = DoubleArray(tokens.size).also { a -> var s = 0.0; weights.forEachIndexed { i, w -> a[i] = s; s += w } }
    private val total = weights.sum().toDouble()
    private val avg = if (tokens.isEmpty()) 1.0 else total / tokens.size

    var wpm: Int = wpm.coerceIn(StudioPrompter.MIN_WPM, StudioPrompter.MAX_WPM)
        set(v) { field = v.coerceIn(StudioPrompter.MIN_WPM, StudioPrompter.MAX_WPM) }
    var paused = false
        private set
    /** Word lit now; −1 before the start. */
    var index = -1
        private set
    private var pos = 0.0
    private var lastMs = 0L
    val log = mutableListOf<PromptMark>()

    val done: Boolean get() = tokens.isNotEmpty() && pos >= total

    /** Moves the highlight to time [ms] of the take; true when the lit word changed. */
    fun tick(ms: Long): Boolean {
        if (tokens.isEmpty()) return false
        if (!paused && ms > lastMs) pos = min(total, pos + (ms - lastMs) * wpm / 60_000.0 * avg)
        lastMs = max(lastMs, ms)
        return light(at(pos), ms)
    }

    /** Pauses or goes on (the time of the pause is not counted). */
    fun togglePause(ms: Long) {
        tick(ms)
        paused = !paused
    }

    /** The reader is at word [i] now (tap on the word). */
    fun jump(i: Int, ms: Long): Boolean {
        if (i !in tokens.indices) return false
        tick(ms)
        pos = starts[i]
        return light(i, ms, force = true)
    }

    private fun at(p: Double): Int {
        var lo = 0
        var hi = tokens.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= p) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun light(i: Int, ms: Long, force: Boolean = false): Boolean {
        if (i == index && !force) return false
        // words passed between two ticks light up at the same moment
        if (!force && i > index + 1 && index >= 0) for (k in index + 1 until i) log += PromptMark(k, ms)
        index = i
        log += PromptMark(i, ms)
        return true
    }
}

object StudioPrompter {
    const val MIN_WPM = 60
    const val MAX_WPM = 260
    const val DEFAULT_WPM = 130
    const val STEP_WPM = 10
    val SPEEDS = listOf(90, 110, 130, 150, 170, 200)
    /** The voice follows the highlight a little later than it lights up. */
    const val REACT_MS = 150L
    /** A word lit in a pause moves to the speech starting within this time. */
    const val SNAP_MS = 600L

    /** Reading weight of a word: its letters, plus a breath after the end of a sentence or a comma. */
    fun weight(w: String): Int {
        val letters = max(2, Studio.norm(w).length) + 1
        val end = w.trimEnd { it == '"' || it == '»' || it == ')' || it == '\'' }.lastOrNull()
        return letters + when (end) {
            '.', '!', '?', '…' -> 6
            ',', ';', ':', '—', '-' -> 3
            else -> 0
        }
    }

    /** Words of the display text (scenes joined by blank lines) with their character offsets. */
    fun layout(scenes: List<List<String>>): Pair<String, List<IntRange>> {
        val sb = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        scenes.forEachIndexed { k, words ->
            if (k > 0) sb.append("\n\n")
            words.forEachIndexed { j, w ->
                if (j > 0) sb.append(' ')
                ranges += sb.length until sb.length + w.length
                sb.append(w)
            }
        }
        return sb.toString() to ranges
    }

    /** Word at character [offset] of the display text (the nearest one before it), or −1. */
    fun wordAt(ranges: List<IntRange>, offset: Int): Int = ranges.indexOfLast { it.first <= offset }

    /** Time [ms] of the original take in a take cut to [keep] (a cut pause goes to the next kept part). */
    fun mapCut(ms: Long, keep: List<Span>?): Long {
        if (keep.isNullOrEmpty()) return ms
        var before = 0L
        for (s in keep) {
            if (ms < s.startMs) return before
            if (ms < s.endMs) return before + ms - s.startMs
            before += s.ms
        }
        return before
    }

    /**
     * Word timing from the prompter log: the last time each word lit up (a re-read counts), shifted by the
     * reaction time, put into the cut take, moved from a pause to the speech right after it; words that never
     * lit up are spread between their neighbours. Empty when the log has no usable mark.
     */
    fun words(tokens: List<String>, log: List<PromptMark>, speech: List<Span>, totalMs: Long, keep: List<Span>? = null): List<SWord> {
        val n = tokens.size
        if (n == 0) return emptyList()
        val raw = LongArray(n) { -1 }
        for (m in log) if (m.word in 0 until n) raw[m.word] = m.ms
        // a mark earlier than a word before it is from an old pass: dropped
        var prev = -1L
        for (i in 0 until n) if (raw[i] >= 0) {
            if (raw[i] < prev) raw[i] = -1 else prev = raw[i]
        }
        val known = (0 until n).filter { raw[it] >= 0 }
        if (known.isEmpty()) return emptyList()
        val w = tokens.map { weight(it).toDouble() }
        // speed of the known part, for the words after the last mark
        val unitMs = if (known.size > 1) {
            (raw[known.last()] - raw[known.first()]).toDouble() / (known.first() until known.last()).sumOf { w[it] }.coerceAtLeast(1.0)
        } else 60_000.0 / DEFAULT_WPM / (w.average())
        val t = DoubleArray(n)
        for (i in 0 until n) t[i] = raw[i].toDouble()
        // before the first mark
        for (i in known.first() - 1 downTo 0) t[i] = max(0.0, t[i + 1] - w[i] * unitMs)
        // between marks: by weight
        known.zipWithNext().forEach { (a, b) ->
            val span = (a until b).sumOf { w[it] }
            var acc = 0.0
            for (i in a + 1 until b) {
                acc += w[i - 1]
                t[i] = t[a] + (t[b] - t[a]) * acc / span
            }
        }
        // after the last mark
        for (i in known.last() + 1 until n) t[i] = t[i - 1] + w[i - 1] * unitMs
        val starts = LongArray(n)
        var last = -1L
        for (i in 0 until n) {
            var s = mapCut(t[i].roundToLong() + REACT_MS, keep).coerceIn(0, max(0, totalMs - 1))
            s = snap(s, speech)
            if (s <= last) s = last + 1
            starts[i] = s
            last = s
        }
        return (0 until n).map { i ->
            val own = (w[i] * unitMs * 1.3).roundToLong().coerceIn(120, 1_500)
            val next = if (i + 1 < n) starts[i + 1] else totalMs
            SWord(tokens[i], starts[i], max(starts[i] + 1, min(next, starts[i] + own)))
        }
    }

    /** A time in a pause moves to the start of the speech right after it (within [SNAP_MS]). */
    fun snap(ms: Long, speech: List<Span>): Long {
        if (speech.isEmpty() || speech.any { ms >= it.startMs && ms < it.endMs }) return ms
        val next = speech.firstOrNull { it.startMs > ms } ?: return ms
        return if (next.startMs - ms <= SNAP_MS) next.startMs else ms
    }

    // ---------- saved log ----------

    /** Key of the script the log belongs to. */
    fun key(tokens: List<String>): String = tokens.size.toString() + ":" + tokens.joinToString(" ").hashCode()

    fun toJson(tokens: List<String>, log: List<PromptMark>, takeMs: Long): String = JSONObject()
        .put("key", key(tokens)).put("take", takeMs)
        .put("marks", JSONArray().apply { log.forEach { put(JSONArray().put(it.word).put(it.ms)) } })
        .toString()

    /** The log when it belongs to these [tokens] and a take of [takeMs] (±1 s); null otherwise. */
    fun fromJson(s: String, tokens: List<String>, takeMs: Long): List<PromptMark>? = runCatching {
        val o = JSONObject(s)
        if (o.optString("key") != key(tokens)) return null
        if (kotlin.math.abs(o.optLong("take") - takeMs) > 1_000) return null
        val a = o.getJSONArray("marks")
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { PromptMark(it.optInt(0, -1), it.optLong(1)) } }.filter { it.word >= 0 }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

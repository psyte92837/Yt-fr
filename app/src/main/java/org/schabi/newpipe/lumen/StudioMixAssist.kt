/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/*
 * "Composer assistant" of the Mix: the author writes what the video must show (a plan by lines, or the voice-over),
 * an AI that watches YouTube videos (Gemini, by link) names the moments of each source video that fit each line,
 * and Lumen puts the best moments in the order of the plan, each as its own clip, cut by context instead of evenly.
 * Pure logic here; the sheet is in StudioMixActivity.
 */

/** One line of the plan: what must be seen; [startMs]..[endMs] — when it is said in the voice-over (-1 = not known). */
data class MixBeat(val n: Int, val text: String, val startMs: Long = -1, val endMs: Long = -1) {
    val durMs: Long get() = if (startMs >= 0 && endMs > startMs) endMs - startMs else 0
}

/** A moment of a source video found by the AI for plan line [beat] (0 = for no line); [score] 0..1 — how well it fits. */
data class MixMoment(
    val url: String,
    val title: String,
    val beat: Int,
    val fromMs: Long,
    val toMs: Long,
    val what: String,
    val score: Double = 0.5,
    val picked: Boolean = true
) {
    val ms: Long get() = toMs - fromMs
    val key: String get() = "$url@$fromMs-$toMs"
}

/** One element of the composed video: [moment] shown for [lenMs]. */
data class MixStep(val moment: MixMoment, val lenMs: Long)

object StudioMixAssist {
    const val MAX_BEATS = 30
    const val MIN_MOMENT_MS = 1_500L
    const val MIN_STEP_MS = 800L
    /** Without a voice-over an element shows its whole moment, but not longer than this. */
    const val STEP_MS = 4_000L
    /** Moments asked from one video. */
    const val PER_VIDEO = 12

    // ---------- plan ----------

    private val bullet = Regex("""^\s*(?:\d{1,3}[.)]|[-•*–—])\s*""")
    private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")

    /** Plan lines from the brief: one line — one beat; a single paragraph is split into sentences. */
    fun beats(brief: String): List<MixBeat> {
        var lines = brief.lines().map { it.replace(bullet, "").trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1) lines = lines[0].split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }
        return lines.take(MAX_BEATS).mapIndexed { i, s -> MixBeat(i + 1, s.take(300)) }
    }

    /**
     * Plan lines from the voice-over: words gathered into phrases that end on . ! ? …, on a pause of [pauseMs]
     * or when a phrase grows past [maxMs]; each line keeps its time so the video follows the voice.
     */
    fun beatsFromWords(words: List<SWord>, maxMs: Long = 7_000, pauseMs: Long = 700): List<MixBeat> {
        val out = mutableListOf<MixBeat>()
        var cur = mutableListOf<SWord>()
        fun flush() {
            if (cur.isEmpty()) return
            out += MixBeat(out.size + 1, cur.joinToString(" ") { it.text.trim() }.trim(), cur.first().startMs, cur.last().endMs)
            cur = mutableListOf()
        }
        for ((i, w) in words.withIndex()) {
            if (w.text.isBlank()) continue
            cur += w
            val next = words.getOrNull(i + 1)
            val end = w.text.trimEnd().lastOrNull()?.let { it in ".!?…" } == true
            val pause = next != null && next.startMs - w.endMs >= pauseMs
            val long = w.endMs - cur.first().startMs >= maxMs
            if (end || pause || long) flush()
        }
        flush()
        // too many lines for one request: neighbours are joined in twos
        var list: List<MixBeat> = out
        while (list.size > MAX_BEATS) list = list.chunked(2).mapIndexed { k, p ->
            MixBeat(k + 1, p.joinToString(" ") { it.text }, p.first().startMs, p.last().endMs)
        }
        return list
    }

    /** The plan for the prompt: "1. text (0:03–0:07, 4 s)". */
    fun beatsBlock(beats: List<MixBeat>): String = beats.joinToString("\n") { b ->
        "${b.n}. ${b.text}" + if (b.durMs > 0) " (${StudioCuts.stamp(b.startMs)}–${StudioCuts.stamp(b.endMs)}, ${(b.durMs + 500) / 1000} s)" else ""
    }

    // ---------- the AI answer ----------

    /** A time of the answer: "1:23", "83", 83 (seconds), "1m23s"; null when it is not a time. */
    fun timeOf(v: Any?): Long? = when (v) {
        is Number -> (v.toDouble() * 1000).toLong().takeIf { it >= 0 }
        is String -> StudioCuts.parseTime(v.trim().removeSuffix("s").removeSuffix("с").trim().ifEmpty { "x" })
            ?: StudioCuts.parseTime(v.trim())
        else -> null
    }

    /**
     * Moments of the answer {"moments":[{"beat","from","to","what","score"}]} (or a bare array; "start"/"end"
     * or "time":"1:23-1:27" are taken too). Wrong entries are skipped; the list is not cleaned yet ([clean]).
     */
    fun parse(answer: String, url: String, title: String): List<MixMoment> {
        val s = RelayText.clean(answer, true)
        val arr = runCatching {
            val a = s.indexOf('{')
            val b = s.indexOf('[')
            if (b >= 0 && (a < 0 || b < a)) JSONArray(s.substring(b, s.lastIndexOf(']') + 1))
            else JSONObject(s.substring(a, s.lastIndexOf('}') + 1)).let { o -> o.optJSONArray("moments") ?: o.optJSONArray("clips") ?: JSONArray() }
        }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            var from = timeOf(o.opt("from") ?: o.opt("start"))
            var to = timeOf(o.opt("to") ?: o.opt("end"))
            if (from == null) o.optString("time").split('-', '–', '—').map { it.trim() }.takeIf { it.size == 2 }?.let {
                from = timeOf(it[0])
                to = timeOf(it[1])
            }
            val a = from ?: return@mapNotNull null
            val beat = o.opt("beat").let { if (it is Number) it.toInt() else it?.toString()?.trim()?.toIntOrNull() } ?: 0
            MixMoment(url, title, beat.coerceAtLeast(0), a, to ?: (a + StudioCuts.DEFAULT_CLIP_MS),
                o.optString("what").ifBlank { o.optString("note") }.trim().take(200),
                o.optDouble("score", 0.5).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.5)
        }
    }

    /**
     * Valid moments: inside the video ([durMs] 0 = not known), at least [MIN_MOMENT_MS] (made longer when short),
     * at most [StudioCuts.MAX_CLIP_MS], beat outside the plan → 0; of moments of one video that overlap by more
     * than half the shorter one the better one stays. Sorted by beat, then by score.
     */
    fun clean(list: List<MixMoment>, durMs: Long, beats: Int): List<MixMoment> {
        val fixed = list.mapNotNull { m ->
            var a = m.fromMs.coerceAtLeast(0)
            var b = m.toMs
            if (durMs > 0 && a >= durMs - 300) return@mapNotNull null
            if (b <= a) b = a + StudioCuts.DEFAULT_CLIP_MS
            if (b - a > StudioCuts.MAX_CLIP_MS) b = a + StudioCuts.MAX_CLIP_MS
            if (durMs > 0) b = b.coerceAtMost(durMs)
            if (b - a < MIN_MOMENT_MS) {
                b = a + MIN_MOMENT_MS
                if (durMs > 0 && b > durMs) {
                    b = durMs
                    a = (durMs - MIN_MOMENT_MS).coerceAtLeast(0)
                }
            }
            m.copy(fromMs = a, toMs = b, beat = if (m.beat in 1..beats) m.beat else 0)
        }
        val out = mutableListOf<MixMoment>()
        for (m in fixed.sortedByDescending { it.score }) {
            val clash = out.any { o ->
                o.url == m.url && minOf(o.toMs, m.toMs) - maxOf(o.fromMs, m.fromMs) > minOf(o.ms, m.ms) / 2
            }
            if (!clash) out += m
        }
        return out.sortedWith(compareBy<MixMoment>({ if (it.beat == 0) Int.MAX_VALUE else it.beat }, { -it.score }))
    }

    /** Moments of one video replace the earlier ones of that video (a new search of it). */
    fun merge(old: List<MixMoment>, url: String, new: List<MixMoment>): List<MixMoment> =
        (old.filter { it.url != url } + new).sortedWith(compareBy<MixMoment>({ if (it.beat == 0) Int.MAX_VALUE else it.beat }, { -it.score }))

    /** Plan lines that got no picked moment. */
    fun uncovered(beats: List<MixBeat>, moments: List<MixMoment>): List<MixBeat> {
        val have = moments.filter { it.picked }.map { it.beat }.toSet()
        return beats.filter { it.n !in have }
    }

    // ---------- composing ----------

    /**
     * The video in the order of the plan: for every line up to [perBeat] picked moments, best first, a moment of
     * another video put first when the previous element was of the same video. With a voice-over time the line's
     * time is shared between its moments (each not longer than its moment); otherwise a moment shows up to
     * [STEP_MS]. Picked moments without a line go at the end. A moment is used once.
     */
    fun plan(beats: List<MixBeat>, moments: List<MixMoment>, perBeat: Int = 2): List<MixStep> {
        val picked = moments.filter { it.picked }
        val used = HashSet<String>()
        val out = mutableListOf<MixStep>()
        fun place(group: List<MixMoment>, durMs: Long) {
            if (group.isEmpty()) return
            val ordered = group.toMutableList()
            val prev = out.lastOrNull()?.moment?.url
            if (ordered.size > 1 && ordered[0].url == prev) ordered.indexOfFirst { it.url != prev }.takeIf { it > 0 }?.let {
                ordered.add(0, ordered.removeAt(it))
            }
            val share = if (durMs > 0) durMs / ordered.size else 0
            for (m in ordered) {
                used += m.key
                val len = if (share > 0) share.coerceIn(MIN_STEP_MS, m.ms) else minOf(m.ms, STEP_MS)
                out += MixStep(m, len.coerceAtMost(m.ms))
            }
        }
        for (b in beats) {
            val group = picked.filter { it.beat == b.n && it.key !in used }.sortedByDescending { it.score }.take(perBeat.coerceAtLeast(1))
            place(group, b.durMs)
        }
        val rest = picked.filter { it.key !in used && (it.beat == 0 || beats.none { b -> b.n == it.beat }) }
        rest.forEach { place(listOf(it), 0) }
        return out
    }

    /**
     * Clips for the steps: a moment already in [clips] (same link and part) is reused, others are added (at most
     * [StudioCuts.MAX_CLIPS] in all). Returns the new clip list and the order of the video: clip id → length.
     */
    fun toClips(clips: List<SClip>, steps: List<MixStep>): Pair<List<SClip>, List<Pair<String, Long>>> {
        val out = clips.toMutableList()
        val order = mutableListOf<Pair<String, Long>>()
        for (s in steps) {
            val m = s.moment
            val (a, b) = StudioCuts.span(m.fromMs, m.toMs)
            var cl = out.firstOrNull { it.url == m.url && it.fromMs == a && it.toMs == b }
            if (cl == null) {
                if (out.size >= StudioCuts.MAX_CLIPS) continue
                cl = SClip(StudioCuts.newId(out), m.url, a, b, m.what.ifBlank { m.title }.take(200), m.title)
                out += cl
            }
            order += cl.id to s.lenMs
        }
        return out to order
    }

    /**
     * The storyboard of the plan: one element per step from the start of its downloaded clip, not longer than the
     * clip ([clipMs]: id → usable length); a clip used twice gets copy numbers. Steps of clips not ready are skipped.
     */
    fun storyboard(order: List<Pair<String, Long>>, clipMs: Map<String, Long>): List<MixItem> {
        val seen = HashMap<String, Int>()
        return order.mapNotNull { (id, len) ->
            val cap = clipMs[id]?.takeIf { it >= StudioMix.MIN_PIECE_MS } ?: return@mapNotNull null
            val dup = seen.getOrDefault(id, -1) + 1
            seen[id] = dup
            MixItem(id, 0, len.coerceIn(StudioMix.MIN_PIECE_MS, cap), dup = dup)
        }
    }

    /** Length of the composed video. */
    fun totalMs(steps: List<MixStep>): Long = steps.sumOf { it.lenMs }

    // ---------- saved state (files/lumen/studio/mix/assist.json) ----------

    data class State(
        val brief: String = "",
        val wish: String = "",
        /** Plan by the voice-over words instead of the brief. */
        val byVoice: Boolean = false,
        val perBeat: Int = 2,
        val moments: List<MixMoment> = emptyList(),
        /** Links already watched for this plan (key of the plan → links). */
        val planKey: String = "",
        val watched: List<String> = emptyList(),
        /** Order waiting for the download of its clips: clip id → length. */
        val pending: List<Pair<String, Long>> = emptyList()
    )

    fun planKey(beats: List<MixBeat>): String = beats.joinToString("|") { it.text }.hashCode().toString(16)

    fun toJson(s: State): String = JSONObject()
        .put("v", 1).put("brief", s.brief).put("wish", s.wish).put("byVoice", s.byVoice).put("perBeat", s.perBeat)
        .put("planKey", s.planKey).put("watched", JSONArray(s.watched))
        .put("moments", JSONArray().apply {
            s.moments.forEach {
                put(JSONObject().put("url", it.url).put("title", it.title).put("beat", it.beat).put("from", it.fromMs).put("to", it.toMs)
                    .put("what", it.what).put("score", it.score).put("picked", it.picked))
            }
        })
        .put("pending", JSONArray().apply { s.pending.forEach { put(JSONObject().put("id", it.first).put("len", it.second)) } })
        .toString()

    fun fromJson(text: String): State = runCatching {
        val o = JSONObject(text)
        val ms = o.optJSONArray("moments") ?: JSONArray()
        val pd = o.optJSONArray("pending") ?: JSONArray()
        val w = o.optJSONArray("watched") ?: JSONArray()
        State(
            o.optString("brief"), o.optString("wish"), o.optBoolean("byVoice"), o.optInt("perBeat", 2).coerceIn(1, 4),
            (0 until ms.length()).mapNotNull { i ->
                val m = ms.optJSONObject(i) ?: return@mapNotNull null
                val url = m.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MixMoment(url, m.optString("title"), m.optInt("beat"), m.optLong("from"), m.optLong("to"), m.optString("what"),
                    m.optDouble("score", 0.5), m.optBoolean("picked", true))
            },
            o.optString("planKey"),
            (0 until w.length()).map { w.optString(it) }.filter { it.isNotBlank() },
            (0 until pd.length()).mapNotNull { i -> pd.optJSONObject(i)?.let { p -> p.optString("id").takeIf { it.isNotBlank() }?.let { it to p.optLong("len") } } }
        )
    }.getOrDefault(State())
}

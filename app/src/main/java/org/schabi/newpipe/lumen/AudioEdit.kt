/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * One piece of sound on a track: [from]..[to] ms of source [src] played from [at] ms of the timeline.
 * [speed] works like a tape (the pitch follows) unless [keepPitch]; [pitch] shifts by semitones; the loudness is
 * [gain] × a straight line [g0]→[g1] over the clip × the edge fades; [fx] is a preset of [EditSound] at [fxK];
 * [lo]/[mid]/[hi] are the three EQ bands in dB.
 */
data class AClip(
    val id: Int,
    val src: String,
    val from: Long,
    val to: Long,
    val at: Long,
    val gain: Float = 1f,
    val g0: Float = 1f,
    val g1: Float = 1f,
    val fadeIn: Long = 0,
    val fadeOut: Long = 0,
    val speed: Float = 1f,
    val keepPitch: Boolean = false,
    val pitch: Float = 0f,
    val reverse: Boolean = false,
    val fx: String = "",
    val fxK: Float = 1f,
    val lo: Float = 0f,
    val mid: Float = 0f,
    val hi: Float = 0f,
    val name: String = ""
) {
    val srcLen: Long get() = to - from
    /** Length on the timeline. */
    val len: Long get() = max(1L, (srcLen / speed.toDouble()).roundToLong())
    val end: Long get() = at + len
    val hasEq: Boolean get() = lo != 0f || mid != 0f || hi != 0f
    /** The pitch shift the chain must do: own semitones, plus undoing the tape pitch when the tempo keeps it. */
    val semis: Float get() = pitch - if (keepPitch && speed != 1f) (12.0 * kotlin.math.ln(speed.toDouble()) / kotlin.math.ln(2.0)).toFloat() else 0f
}

data class ATrack(
    val id: Int,
    val name: String,
    val clips: List<AClip> = emptyList(),
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false
) {
    val end: Long get() = clips.maxOfOrNull { it.end } ?: 0L
}

data class AMarker(val at: Long, val name: String)

/** An imported or recorded file, kept as raw 16-bit stereo PCM at the project's rate. */
data class ASource(val id: String, val name: String, val ms: Long)

data class AProject(
    val id: String,
    val name: String,
    val rate: Int = 44_100,
    val tracks: List<ATrack> = emptyList(),
    val sources: List<ASource> = emptyList(),
    val markers: List<AMarker> = emptyList(),
    val gain: Float = 1f,
    val nextId: Int = 1,
    val created: Long = 0L,
    val updated: Long = 0L
) {
    val length: Long get() = tracks.maxOfOrNull { it.end } ?: 0L
    fun track(id: Int): ATrack? = tracks.firstOrNull { it.id == id }
    fun source(id: String): ASource? = sources.firstOrNull { it.id == id }
    fun clip(id: Int): AClip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id } }
    fun trackOf(clipId: Int): ATrack? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }
    val clips: List<AClip> get() = tracks.flatMap { it.clips }
}

/** A copied range: the clips of each track relative to the start. */
data class ASnippet(val len: Long, val tracks: List<List<AClip>>)

/**
 * The audio editor's project (protocol lumen.audioedit v1) and every edit on it as a pure function: cut, split,
 * delete a range with or without closing the gap, crop, insert silence, copy/paste, join files one after another,
 * mix them on tracks, fades and volume over a range, crossfades on the seams, removing pauses, effects per piece.
 * Nothing is destroyed: the sources stay as they were and the project only says what plays when.
 */
object AudioEdit {
    const val PROTOCOL = "lumen.audioedit v1"
    /** Shortest piece kept after a cut. */
    const val MIN_MS = 2L
    const val MAX_TRACKS = 8
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f

    fun empty(id: String, name: String, now: Long = 0L, trackName: String = "1"): AProject =
        AProject(id, name, tracks = listOf(ATrack(1, trackName)), nextId = 2, created = now, updated = now)

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Envelope value (g0→g1 line × gain, without fades) at timeline [ms] inside the clip. */
    fun envAt(c: AClip, ms: Long): Float = c.gain * lerp(c.g0, c.g1, ((ms - c.at).toFloat() / c.len).coerceIn(0f, 1f))

    /** Source position (ms) heard at timeline [ms] of the clip. */
    fun srcAt(c: AClip, ms: Long): Long {
        val u = ((ms - c.at).coerceIn(0, c.len) * c.speed.toDouble()).roundToLong()
        return if (c.reverse) c.to - u else c.from + u
    }

    /** The part of [c] between timeline [a] and [b] (or null when it is shorter than [MIN_MS]); fades stay only on kept edges. */
    fun cut(c: AClip, a: Long, b: Long, newId: Int = c.id): AClip? {
        val s = max(a, c.at)
        val e = min(b, c.end)
        if (e - s < MIN_MS) return null
        val sp = c.speed.toDouble()
        val u0 = ((s - c.at) * sp).roundToLong().coerceIn(0, c.srcLen)
        val u1 = ((e - c.at) * sp).roundToLong().coerceIn(u0, c.srcLen)
        if (u1 - u0 < 1) return null
        val f = if (c.reverse) c.to - u1 else c.from + u0
        val t = if (c.reverse) c.to - u0 else c.from + u1
        val k0 = (s - c.at).toFloat() / c.len
        val k1 = (e - c.at).toFloat() / c.len
        return c.copy(
            id = newId, from = f, to = t, at = s,
            g0 = lerp(c.g0, c.g1, k0), g1 = lerp(c.g0, c.g1, k1),
            fadeIn = if (s == c.at) min(c.fadeIn, e - s) else 0L,
            fadeOut = if (e == c.end) min(c.fadeOut, e - s) else 0L
        )
    }

    private fun picked(t: ATrack, tracks: Set<Int>?) = tracks == null || t.id in tracks

    private fun sorted(cs: List<AClip>) = cs.sortedWith(compareBy({ it.at }, { it.id }))

    /** Clips of the chosen tracks split at [ms] (pieces keep their place). */
    fun splitAt(p: AProject, ms: Long, tracks: Set<Int>? = null): AProject {
        var next = p.nextId
        val ts = p.tracks.map { t ->
            if (!picked(t, tracks)) t else t.copy(clips = sorted(t.clips.flatMap { c ->
                if (ms - c.at < MIN_MS || c.end - ms < MIN_MS) listOf(c)
                else listOfNotNull(cut(c, c.at, ms, c.id), cut(c, ms, c.end, next++))
            }))
        }
        return p.copy(tracks = ts, nextId = next)
    }

    /** Removes [a]..[b] from the chosen tracks; with [ripple] everything after moves left to close the gap. */
    fun deleteRange(p: AProject, a: Long, b: Long, tracks: Set<Int>? = null, ripple: Boolean = true): AProject {
        if (b - a < 1) return p
        val d = b - a
        var next = p.nextId
        val ts = p.tracks.map { t ->
            if (!picked(t, tracks)) t else t.copy(clips = sorted(t.clips.flatMap { c ->
                when {
                    c.end <= a -> listOf(c)
                    c.at >= b -> listOf(if (ripple) c.copy(at = c.at - d) else c)
                    else -> listOfNotNull(
                        cut(c, c.at, a, c.id),
                        cut(c, b, c.end, next++)?.let { if (ripple) it.copy(at = it.at - d) else it }
                    )
                }
            }))
        }
        val marks = if (ripple && tracks == null) p.markers.mapNotNull { m ->
            when {
                m.at < a -> m
                m.at < b -> null
                else -> m.copy(at = m.at - d)
            }
        } else p.markers
        return p.copy(tracks = ts, nextId = next, markers = marks)
    }

    /** Keeps only [a]..[b] of every track, moved to the start. */
    fun cropTo(p: AProject, a: Long, b: Long): AProject {
        if (b - a < MIN_MS) return p
        val ts = p.tracks.map { t -> t.copy(clips = sorted(t.clips.mapNotNull { c -> cut(c, a, b)?.let { it.copy(at = it.at - a) } })) }
        val marks = p.markers.filter { it.at in a..b }.map { it.copy(at = it.at - a) }
        return p.copy(tracks = ts, markers = marks)
    }

    /** A gap of [len] ms at [at] on the chosen tracks (a clip there is split, the rest moves right). */
    fun insertGap(p: AProject, at: Long, len: Long, tracks: Set<Int>? = null): AProject {
        if (len <= 0) return p
        val s = splitAt(p, at, tracks)
        val ts = s.tracks.map { t ->
            if (!picked(t, tracks)) t else t.copy(clips = t.clips.map { c -> if (c.at >= at - 1 && c.end > at) c.copy(at = c.at + len) else c })
        }
        val marks = if (tracks == null) s.markers.map { if (it.at >= at) it.copy(at = it.at + len) else it } else s.markers
        return s.copy(tracks = ts, markers = marks)
    }

    /** The clips of [a]..[b] on the chosen tracks, relative to [a]. */
    fun copy(p: AProject, a: Long, b: Long, tracks: Set<Int>? = null): ASnippet =
        ASnippet(max(0L, b - a), p.tracks.filter { picked(it, tracks) }.map { t -> t.clips.mapNotNull { c -> cut(c, a, b)?.let { it.copy(at = it.at - a) } } })

    /**
     * [s] put in at [at]: its first track goes to [trackId] (the next ones below it, new tracks are added if needed);
     * with [ripple] the target tracks first open a gap of its length.
     */
    fun paste(p: AProject, s: ASnippet, at: Long, trackId: Int, ripple: Boolean = true): AProject {
        if (s.tracks.all { it.isEmpty() } || s.len <= 0) return p
        var q = p
        val first = q.tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        while (q.tracks.size < first + s.tracks.size && q.tracks.size < MAX_TRACKS) q = addTrack(q, "${q.tracks.size + 1}")
        val targets = (0 until s.tracks.size).mapNotNull { q.tracks.getOrNull(first + it)?.id }
        if (ripple) q = insertGap(q, at, s.len, targets.toSet())
        var next = q.nextId
        val ts = q.tracks.map { t ->
            val i = targets.indexOf(t.id)
            if (i < 0) t else t.copy(clips = sorted(t.clips + s.tracks[i].map { it.copy(id = next++, at = it.at + at) }))
        }
        return q.copy(tracks = ts, nextId = next)
    }

    /**
     * Changes every clip fully inside [a]..[b] of the chosen tracks with [f] (the clips on the edges are split first).
     * When [f] changes a clip's length (speed), the later clips of that track follow.
     */
    fun mapRange(p: AProject, a: Long, b: Long, tracks: Set<Int>? = null, f: (AClip) -> AClip): AProject {
        if (b - a < MIN_MS) return p
        val s = splitAt(splitAt(p, a, tracks), b, tracks)
        val ts = s.tracks.map { t ->
            if (!picked(t, tracks)) t else {
                var shift = 0L
                t.copy(clips = sorted(sorted(t.clips).map { c ->
                    if (c.at >= a - 1 && c.end <= b + 1) {
                        val n = f(c)
                        val moved = n.copy(at = c.at + shift)
                        shift += n.len - c.len
                        moved
                    } else if (c.at >= a) c.copy(at = c.at + shift) else c
                }))
            }
        }
        return s.copy(tracks = ts)
    }

    /** Fade in over [a]..[b]: silence at [a], full at [b] (multiplies the clips' envelopes). */
    fun fadeInRange(p: AProject, a: Long, b: Long, tracks: Set<Int>? = null): AProject = mapRange(p, a, b, tracks) { c ->
        val r0 = ((c.at - a).toFloat() / (b - a)).coerceIn(0f, 1f)
        val r1 = ((c.end - a).toFloat() / (b - a)).coerceIn(0f, 1f)
        c.copy(g0 = c.g0 * r0, g1 = c.g1 * r1)
    }

    fun fadeOutRange(p: AProject, a: Long, b: Long, tracks: Set<Int>? = null): AProject = mapRange(p, a, b, tracks) { c ->
        val r0 = ((c.at - a).toFloat() / (b - a)).coerceIn(0f, 1f)
        val r1 = ((c.end - a).toFloat() / (b - a)).coerceIn(0f, 1f)
        c.copy(g0 = c.g0 * (1f - r0), g1 = c.g1 * (1f - r1))
    }

    /** Volume of [a]..[b] times [k] (0 = silence). */
    fun gainRange(p: AProject, a: Long, b: Long, k: Float, tracks: Set<Int>? = null): AProject =
        mapRange(p, a, b, tracks) { it.copy(gain = (it.gain * k).coerceIn(0f, MAX_GAIN)) }

    const val MAX_GAIN = 8f

    /** Speed of a clip (the later clips of its track follow its new length). */
    fun setSpeed(c: AClip, speed: Float): AClip = c.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED))

    /** [f] on one clip; a new length moves the later clips of its track. */
    fun updateClip(p: AProject, id: Int, f: (AClip) -> AClip): AProject {
        val t = p.trackOf(id) ?: return p
        val c = t.clips.first { it.id == id }
        val n = f(c).copy(id = id, at = c.at)
        val d = n.len - c.len
        val clips = t.clips.map { x ->
            when {
                x.id == id -> n
                d != 0L && x.at >= c.end - 1 -> x.copy(at = x.at + d)
                else -> x
            }
        }
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) t.copy(clips = sorted(clips)) else it })
    }

    /** Every clip of the project through [f] (lengths that change move the later clips). */
    fun mapAll(p: AProject, f: (AClip) -> AClip): AProject = mapRange(p, 0, p.length + 1, null, f)

    fun removeClip(p: AProject, id: Int, ripple: Boolean): AProject {
        val t = p.trackOf(id) ?: return p
        val c = t.clips.first { it.id == id }
        val rest = t.clips.filter { it.id != id }.map { if (ripple && it.at >= c.end - 1) it.copy(at = it.at - c.len) else it }
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) t.copy(clips = rest) else it })
    }

    /** A copy of the clip right after it; the later clips of the track move right. */
    fun duplicateClip(p: AProject, id: Int): AProject {
        val t = p.trackOf(id) ?: return p
        val c = t.clips.first { it.id == id }
        val copy = c.copy(id = p.nextId, at = c.end)
        val clips = t.clips.map { if (it.id != id && it.at >= c.end - 1) it.copy(at = it.at + c.len) else it } + copy
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) t.copy(clips = sorted(clips)) else it }, nextId = p.nextId + 1)
    }

    /** Moves a clip to [at] ms of track [trackId] (clips may overlap: they mix). */
    fun moveClip(p: AProject, id: Int, at: Long, trackId: Int): AProject {
        val from = p.trackOf(id) ?: return p
        val c = from.clips.first { it.id == id }.copy(at = max(0L, at))
        if (p.track(trackId) == null) return p
        return p.copy(tracks = p.tracks.map { t ->
            val clips = t.clips.filter { it.id != id }.let { if (t.id == trackId) it + c else it }
            if (t.id == from.id || t.id == trackId) t.copy(clips = sorted(clips)) else t
        })
    }

    /** The clip's start dragged to timeline [x] (bounded by the source); its end stays. */
    fun trimStartTo(c: AClip, x: Long, srcMs: Long): AClip {
        val ds = ((x - c.at) * c.speed.toDouble()).roundToLong()
        return if (!c.reverse) {
            val f = (c.from + ds).coerceIn(0L, c.to - MIN_MS)
            val n = c.copy(from = f)
            n.copy(at = c.end - n.len, fadeIn = min(c.fadeIn, n.len))
        } else {
            val t = (c.to - ds).coerceIn(c.from + MIN_MS, max(c.from + MIN_MS, srcMs))
            val n = c.copy(to = t)
            n.copy(at = c.end - n.len, fadeIn = min(c.fadeIn, n.len))
        }
    }

    /** The clip's end dragged to timeline [x]; its start stays. */
    fun trimEndTo(c: AClip, x: Long, srcMs: Long): AClip {
        val ds = ((x - c.end) * c.speed.toDouble()).roundToLong()
        return if (!c.reverse) c.copy(to = (c.to + ds).coerceIn(c.from + MIN_MS, max(c.from + MIN_MS, srcMs))).let { it.copy(fadeOut = min(c.fadeOut, it.len)) }
        else c.copy(from = (c.from - ds).coerceIn(0L, c.to - MIN_MS)).let { it.copy(fadeOut = min(c.fadeOut, it.len)) }
    }

    /**
     * Soft seams on a track: each clip that touches the next overlaps it by up to [ms] (no more than half of either),
     * with a fade out and a fade in there; everything after moves left by the overlap.
     */
    fun crossfade(p: AProject, trackId: Int, ms: Long): AProject {
        val t = p.track(trackId) ?: return p
        val cs = sorted(t.clips).toMutableList()
        var shift = 0L
        for (i in cs.indices) {
            if (shift != 0L) cs[i] = cs[i].copy(at = cs[i].at - shift)
            if (i == 0) continue
            val a = cs[i - 1]
            val b = cs[i]
            if (abs(b.at - a.end) > 2) continue
            val ov = minOf(ms, a.len / 2, b.len / 2)
            if (ov < 5) continue
            cs[i - 1] = a.copy(fadeOut = ov)
            cs[i] = b.copy(at = b.at - ov, fadeIn = ov)
            shift += ov
        }
        return p.copy(tracks = p.tracks.map { if (it.id == trackId) t.copy(clips = cs) else it })
    }

    /** True when [b] simply continues [a] (same source, no gap or jump): no de-click fade on that seam. */
    fun seamless(a: AClip, b: AClip): Boolean = a.src == b.src && !a.reverse && !b.reverse && a.speed == b.speed && a.fx == b.fx &&
        abs(b.at - a.end) <= 1 && abs(b.from - a.to) <= 2

    /** Removes the [ranges] (timeline, any order) from every track, closing the gaps. */
    fun removeRanges(p: AProject, ranges: List<LongRange>): AProject =
        ranges.sortedByDescending { it.first }.fold(p) { q, r -> deleteRange(q, r.first, r.last, null, true) }

    // ---------- tracks ----------

    fun addTrack(p: AProject, name: String): AProject {
        if (p.tracks.size >= MAX_TRACKS) return p
        return p.copy(tracks = p.tracks + ATrack(p.nextId, name), nextId = p.nextId + 1)
    }

    fun removeTrack(p: AProject, id: Int): AProject =
        if (p.tracks.size <= 1) p.copy(tracks = p.tracks.map { it.copy(clips = emptyList()) }) else p.copy(tracks = p.tracks.filter { it.id != id })

    fun updateTrack(p: AProject, id: Int, f: (ATrack) -> ATrack): AProject = p.copy(tracks = p.tracks.map { if (it.id == id) f(it).copy(id = id, clips = it.clips) else it })

    /** The first track with nothing in [a]..[b], or a new one (or the last when the project is full). */
    fun freeTrack(p: AProject, a: Long, b: Long, name: String): Pair<AProject, Int> {
        p.tracks.firstOrNull { t -> t.clips.none { it.at < b && it.end > a } }?.let { return p to it.id }
        if (p.tracks.size >= MAX_TRACKS) return p to p.tracks.last().id
        val q = addTrack(p, name)
        return q to q.tracks.last().id
    }

    /** Tracks heard: with any solo only the solo ones, never the muted. */
    fun audible(p: AProject): List<ATrack> {
        val solo = p.tracks.any { it.solo }
        return p.tracks.filter { !it.mute && (!solo || it.solo) }
    }

    // ---------- adding sound ----------

    fun addSource(p: AProject, s: ASource): AProject = p.copy(sources = p.sources.filter { it.id != s.id } + s)

    private fun newClip(p: AProject, src: ASource, at: Long, name: String) = AClip(p.nextId, src.id, 0L, src.ms, at, name = name.ifBlank { src.name })

    /** Joins: the whole [src] after the end of track [trackId]. */
    fun append(p: AProject, trackId: Int, src: ASource, name: String = ""): AProject {
        val q = addSource(p, src)
        val t = q.track(trackId) ?: return q
        val c = newClip(q, src, t.end, name)
        return q.copy(tracks = q.tracks.map { if (it.id == trackId) it.copy(clips = sorted(it.clips + c)) else it }, nextId = q.nextId + 1)
    }

    /** Inserts: [src] at [at] of track [trackId], what was there moves right. */
    fun insert(p: AProject, trackId: Int, src: ASource, at: Long, name: String = ""): AProject {
        val q = insertGap(addSource(p, src), at, src.ms, setOf(trackId))
        val c = newClip(q, src, at, name)
        return q.copy(tracks = q.tracks.map { if (it.id == trackId) it.copy(clips = sorted(it.clips + c)) else it }, nextId = q.nextId + 1)
    }

    /** Mixes: [src] at [at] on a track that is free there (a new one if needed). */
    fun overlay(p: AProject, src: ASource, at: Long, trackName: String, name: String = ""): AProject {
        val (q0, tid) = freeTrack(addSource(p, src), at, at + src.ms, trackName)
        val c = newClip(q0, src, at, name)
        return q0.copy(tracks = q0.tracks.map { if (it.id == tid) it.copy(clips = sorted(it.clips + c)) else it }, nextId = q0.nextId + 1)
    }

    /** Replaces the source of clips [ids] by [src] (a processed copy of the same length, e.g. after noise removal). */
    fun swapSource(p: AProject, oldSrc: String, src: ASource): AProject {
        val q = addSource(p, src)
        return q.copy(tracks = q.tracks.map { t -> t.copy(clips = t.clips.map { if (it.src == oldSrc) it.copy(src = src.id) else it }) })
    }

    /** Sources no clip uses any more (their files can go). */
    fun unused(p: AProject): List<ASource> {
        val used = p.clips.map { it.src }.toSet()
        return p.sources.filter { it.id !in used }
    }

    fun dropUnused(p: AProject): AProject {
        val used = p.clips.map { it.src }.toSet()
        return p.copy(sources = p.sources.filter { it.id in used })
    }

    // ---------- markers, snapping ----------

    fun addMarker(p: AProject, at: Long, name: String): AProject =
        p.copy(markers = (p.markers.filter { abs(it.at - at) > 5 } + AMarker(max(0L, at), name)).sortedBy { it.at })

    fun removeMarker(p: AProject, at: Long): AProject = p.copy(markers = p.markers.filter { abs(it.at - at) > 5 })

    /** Places worth sticking to: 0, the clip edges and the markers. */
    fun snapPoints(p: AProject): List<Long> = (listOf(0L) + p.clips.flatMap { listOf(it.at, it.end) } + p.markers.map { it.at }).distinct().sorted()

    /** [ms] moved to the nearest of [points] closer than [tol], else as is. */
    fun snap(ms: Long, points: List<Long>, tol: Long): Long = points.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) <= tol } ?: ms

    // ---------- analysis helpers ----------

    /**
     * Pauses to cut: runs of [db] (one value per [stepMs]) below [thrDb] lasting at least [minMs]; [padMs] of each
     * edge stays so speech does not sound chopped. Timeline ranges first..last (exclusive end).
     */
    fun pauses(db: FloatArray, stepMs: Long, thrDb: Float, minMs: Long, padMs: Long): List<LongRange> {
        val out = ArrayList<LongRange>()
        var i = 0
        while (i < db.size) {
            if (db[i] >= thrDb) { i++; continue }
            var j = i
            while (j < db.size && db[j] < thrDb) j++
            val a = i * stepMs
            val b = j * stepMs
            // a pause at the very start is cut whole; next to speech the padding stays
            val ca = if (i == 0) 0L else a + padMs
            val cb = if (j == db.size) b else b - padMs
            if (b - a >= minMs && cb - ca >= MIN_MS * 5) out += ca..cb
            i = j
        }
        return out
    }

    /** Gain that brings [peak] (0..1) to [targetDb] dBFS. */
    fun peakGain(peak: Float, targetDb: Float = -1f): Float = if (peak <= 1e-5f) 1f else (EditSound.dbToGain(targetDb) / peak).coerceIn(0.05f, 32f)

    /** Gain that brings an [rmsDb] loudness to [targetDb] without the [peak] going over −1 dBFS. */
    fun loudGain(rmsDb: Float, peak: Float, targetDb: Float): Float {
        if (rmsDb < -90f) return 1f
        val g = EditSound.dbToGain(targetDb - rmsDb)
        return min(g, peakGain(peak, -1f)).coerceIn(0.05f, 32f)
    }

    // ---------- JSON ----------

    fun toJson(p: AProject): JSONObject = JSONObject().apply {
        put("v", PROTOCOL)
        put("id", p.id)
        put("name", p.name)
        put("rate", p.rate)
        put("gain", p.gain.toDouble())
        put("next", p.nextId)
        put("created", p.created)
        put("updated", p.updated)
        put("sources", JSONArray().apply { p.sources.forEach { s -> put(JSONObject().put("id", s.id).put("name", s.name).put("ms", s.ms)) } })
        put("markers", JSONArray().apply { p.markers.forEach { m -> put(JSONObject().put("at", m.at).put("name", m.name)) } })
        put("tracks", JSONArray().apply {
            p.tracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    if (t.gain != 1f) put("gain", t.gain.toDouble())
                    if (t.pan != 0f) put("pan", t.pan.toDouble())
                    if (t.mute) put("mute", true)
                    if (t.solo) put("solo", true)
                    put("clips", JSONArray().apply { t.clips.forEach { put(clipJson(it)) } })
                })
            }
        })
    }

    private fun clipJson(c: AClip) = JSONObject().apply {
        put("id", c.id)
        put("src", c.src)
        put("from", c.from)
        put("to", c.to)
        put("at", c.at)
        if (c.gain != 1f) put("gain", c.gain.toDouble())
        if (c.g0 != 1f) put("g0", c.g0.toDouble())
        if (c.g1 != 1f) put("g1", c.g1.toDouble())
        if (c.fadeIn > 0) put("fin", c.fadeIn)
        if (c.fadeOut > 0) put("fout", c.fadeOut)
        if (c.speed != 1f) put("speed", c.speed.toDouble())
        if (c.keepPitch) put("keep", true)
        if (c.pitch != 0f) put("pitch", c.pitch.toDouble())
        if (c.reverse) put("rev", true)
        if (c.fx.isNotEmpty()) { put("fx", c.fx); put("fxK", c.fxK.toDouble()) }
        if (c.lo != 0f) put("lo", c.lo.toDouble())
        if (c.mid != 0f) put("mid", c.mid.toDouble())
        if (c.hi != 0f) put("hi", c.hi.toDouble())
        if (c.name.isNotEmpty()) put("name", c.name)
    }

    private fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()

    fun fromJson(o: JSONObject): AProject {
        val srcs = o.optJSONArray("sources") ?: JSONArray()
        val marks = o.optJSONArray("markers") ?: JSONArray()
        val tr = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until tr.length()).map { i ->
            val t = tr.getJSONObject(i)
            val cs = t.optJSONArray("clips") ?: JSONArray()
            ATrack(
                t.getInt("id"), t.optString("name"),
                (0 until cs.length()).map { j ->
                    val c = cs.getJSONObject(j)
                    AClip(
                        c.getInt("id"), c.getString("src"), c.getLong("from"), c.getLong("to"), c.getLong("at"),
                        c.f("gain", 1f), c.f("g0", 1f), c.f("g1", 1f), c.optLong("fin"), c.optLong("fout"),
                        c.f("speed", 1f).coerceIn(MIN_SPEED, MAX_SPEED), c.optBoolean("keep"), c.f("pitch", 0f), c.optBoolean("rev"),
                        c.optString("fx"), c.f("fxK", 1f), c.f("lo", 0f), c.f("mid", 0f), c.f("hi", 0f), c.optString("name")
                    )
                }.filter { it.to > it.from }.let { sorted(it) },
                t.f("gain", 1f), t.f("pan", 0f), t.optBoolean("mute"), t.optBoolean("solo")
            )
        }
        val maxId = (tracks.map { it.id } + tracks.flatMap { t -> t.clips.map { it.id } }).maxOrNull() ?: 0
        return AProject(
            o.getString("id"), o.optString("name"), o.optInt("rate", 44_100),
            tracks.ifEmpty { listOf(ATrack(maxId + 1, "1")) },
            (0 until srcs.length()).map { srcs.getJSONObject(it).let { s -> ASource(s.getString("id"), s.optString("name"), s.optLong("ms")) } },
            (0 until marks.length()).map { marks.getJSONObject(it).let { m -> AMarker(m.getLong("at"), m.optString("name")) } },
            o.f("gain", 1f), max(o.optInt("next", 1), maxId + 1), o.optLong("created"), o.optLong("updated")
        )
    }

    /** "1:05.3" (or "1:02:05" past an hour). */
    fun time(ms: Long, tenths: Boolean = true): String {
        val m = max(0L, ms)
        val s = m / 1000
        val h = s / 3600
        val base = if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, s / 60 % 60, s % 60) else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
        return if (tenths && h == 0L) base + "." + (m % 1000 / 100) else base
    }

    /** "12.3" s, "1:05" or "0.5" → ms; null when unreadable. */
    fun parseTime(s: String): Long? {
        val x = s.trim().replace(',', '.')
        if (x.isEmpty()) return null
        val parts = x.split(':')
        return try {
            var sec = 0.0
            for (p in parts) sec = sec * 60 + p.toDouble()
            if (sec < 0) null else (sec * 1000).roundToLong()
        } catch (e: NumberFormatException) {
            null
        }
    }
}

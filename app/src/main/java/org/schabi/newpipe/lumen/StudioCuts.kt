/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/*
 * Lumen Studio "Cuts": a voice-over over pieces of YouTube videos (B-roll and meme reactions).
 * The pieces are marked by link and time, downloaded as a small fragment of the video-only DASH stream,
 * cut on the phone and put on the scenes by the AI director. Pure logic here; network and media in StudioCutsLoad.kt.
 */

/** A marked piece of a YouTube video. */
data class SClip(
    val id: String,
    val url: String,
    val fromMs: Long,
    val toMs: Long,
    /** What is in the piece, for the AI director and for matching by words. */
    val note: String = "",
    /** Title of the video (filled in on download). */
    val title: String = "",
    /** Name of the cut file in the clips folder; empty until downloaded. */
    val file: String = "",
    /** Where [fromMs] is inside the file (the file starts at the key frame before it). */
    val leadMs: Long = 0,
    /** Usable length of the file from [leadMs]. */
    val lenMs: Long = 0,
    /** Length of the whole YouTube video (0 = not known yet): new random moments are picked inside it. */
    val durMs: Long = 0,
    /** Quality cap (short side, px) the file was downloaded with; 0 = not known (older downloads). */
    val q: Int = 0,
    /** A piece cut at the storyboard: the scene it plays on (−1 = a clip marked by the author). */
    val scene: Int = -1,
    /** Clip (the author's mark) this piece is cut from. */
    val src: String = "",
    /** Place of the piece in its scene: [part] of [parts]. */
    val part: Int = 0,
    val parts: Int = 1
) {
    val ms: Long get() = toMs - fromMs
}

/** Part of the output where clip [id] plays; the file time is [leadMs] + ([offsetMs] + t − [startMs]) mod [lenMs]. */
data class ClipSlot(val startMs: Long, val endMs: Long, val id: String, val leadMs: Long, val lenMs: Long, val offsetMs: Long = 0)

/** A video found by title in the Cuts search. */
data class CutHit(val url: String, val title: String, val channel: String = "", val durationMs: Long = 0, val thumb: String = "", val topic: String = "")

/** A fragment of a DASH stream from its sidx box: where its bytes are and what time it covers. */
data class DashSeg(val offset: Long, val size: Long, val startUs: Long, val durUs: Long)

object StudioCuts {
    const val DEFAULT_CLIP_MS = 5_000L
    const val MAX_CLIP_MS = 30_000L
    const val MAX_CLIPS = 40
    /** Without a stream index the whole stream is downloaded, but only up to this size. */
    const val MAX_FULL_BYTES = 80L * 1024 * 1024

    // ---------- download quality ----------

    /** Choices of the download quality (short side of the frame, px; YouTube gives H.264 up to 1080p); 0 = as the export quality. */
    val QUALITIES = listOf(0, 360, 480, 720, 1080)

    /** Short side of a [w]×[h] frame (the known one when the other is 0): 1080 for both 1920×1080 and 1080×1920. */
    fun shortSide(w: Int, h: Int): Int = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h, 0)

    /** The cap actually used: [want] (0 = [export]). */
    fun capOf(want: Int, export: Int): Int = if (want > 0) want else export

    /**
     * Index of the best of [sizes] (w to h) whose short side is at most [max]; when all are bigger — the smallest of
     * them (not the worst quality), −1 for an empty list. Vertical videos are compared by their short side too.
     */
    fun bestSize(sizes: List<Pair<Int, Int>>, max: Int): Int {
        if (sizes.isEmpty()) return -1
        val sides = sizes.map { shortSide(it.first, it.second) }
        val under = sides.indices.filter { sides[it] in 1..max }
        if (under.isNotEmpty()) return under.maxBy { sides[it] }
        return sides.indices.minBy { if (sides[it] > 0) sides[it] else Int.MAX_VALUE }
    }

    /** Downloaded clips of [clips] taken with a lower cap than [cap] (or an unknown one) — worth downloading again. */
    fun belowCap(clips: List<SClip>, cap: Int): List<SClip> = clips.filter { it.file.isNotEmpty() && it.q < cap }

    // ---------- time ----------

    /** Longest time a user can type (minutes): far beyond any video, far below a Long overflow. */
    private const val MAX_TYPED_MIN = 10_000_000L

    /** "83", "83.5", "1:23", "1:02:03", "1m23s", "2h" → milliseconds; null when it is not a time. */
    fun parseTime(s: String): Long? {
        val v = s.trim().lowercase().replace(',', '.')
        if (v.isEmpty()) return null
        if (':' in v) {
            val parts = v.split(':')
            if (parts.size > 3 || parts.any { it.isEmpty() }) return null
            val sec = parts.last().toDoubleOrNull() ?: return null
            if (sec < 0 || sec >= 60) return null
            var min = 0L
            for (p in parts.dropLast(1)) {
                val n = p.toLongOrNull() ?: return null
                if (n < 0 || n > MAX_TYPED_MIN) return null
                min = min * 60 + n
            }
            // past ~17 years the sum would overflow to a negative time
            return if (min > MAX_TYPED_MIN) null else min * 60_000 + (sec * 1000).roundToLong()
        }
        val m = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+(?:\.\d+)?)s?)?""").matchEntire(v) ?: return null
        if (m.value.isEmpty()) return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val mi = m.groupValues[2].toLongOrNull() ?: 0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        if (h > MAX_TYPED_MIN / 60 || mi > MAX_TYPED_MIN || sec > MAX_TYPED_MIN * 60.0) return null
        return (h * 3600 + mi * 60) * 1000 + (sec * 1000).roundToLong()
    }

    /** 65_500 → "1:05.5", 3_725_000 → "1:02:05". */
    fun stamp(ms: Long): String {
        val s = ms / 1000
        val tenth = (ms % 1000) / 100
        val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        return if (tenth > 0) "$base.$tenth" else base
    }

    /** Start time from a link: "?t=83", "&t=1m23s", "#t=83s", "&start=83". */
    fun startOf(url: String): Long? =
        Regex("""[?&#](?:t|start)=([0-9hms.:]+)""").find(url)?.groupValues?.get(1)?.let { parseTime(it) }

    /** Piece [fromMs]..[toMs] made valid: end after start, at most [MAX_CLIP_MS]; a missing end gives [DEFAULT_CLIP_MS]. */
    fun span(fromMs: Long, toMs: Long?): Pair<Long, Long> {
        val a = fromMs.coerceAtLeast(0)
        val b = (toMs?.takeIf { it > a } ?: (a + DEFAULT_CLIP_MS)).coerceAtMost(a + MAX_CLIP_MS)
        return a to b
    }

    /** Next free id: c1, c2, … */
    fun newId(clips: List<SClip>): String = "c" + ((clips.mapNotNull { it.id.removePrefix("c").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    // ---------- saved list ----------

    fun toJson(clips: List<SClip>): String = JSONArray().apply {
        clips.forEach {
            put(JSONObject().put("id", it.id).put("url", it.url).put("from", it.fromMs).put("to", it.toMs).put("note", it.note)
                .put("title", it.title).put("file", it.file).put("lead", it.leadMs).put("len", it.lenMs).put("dur", it.durMs).apply {
                    if (it.q > 0) put("q", it.q)
                    if (it.scene >= 0) put("scene", it.scene).put("src", it.src).put("part", it.part).put("parts", it.parts)
                })
        }
    }.toString()

    fun fromJson(s: String): List<SClip> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val url = o.optString("url").trim()
            if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
            SClip(id, url, o.optLong("from"), o.optLong("to"), o.optString("note"), o.optString("title"), o.optString("file"),
                o.optLong("lead"), o.optLong("len"), o.optLong("dur"), o.optInt("q"),
                o.optInt("scene", -1), o.optString("src"), o.optInt("part"), o.optInt("parts", 1).coerceAtLeast(1))
        }
    }.getOrDefault(emptyList())

    // ---------- DASH index ----------

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    /**
     * Fragments listed in the sidx box of [b] (the bytes of the stream from [startByte]). Offsets are bytes of the
     * whole stream. Null when there is no valid sidx.
     */
    fun parseSidx(b: ByteArray, startByte: Long): List<DashSeg>? {
        var p = 0
        while (p + 8 <= b.size) {
            val size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "sidx") break
            if (size < 8) return null
            p += size.toInt()
        }
        if (p + 32 > b.size) return null
        val boxSize = u32(b, p)
        val version = b[p + 8].toInt()
        var q = p + 12 + 4 // full box header, reference_ID
        val timescale = u32(b, q)
        q += 4
        if (timescale <= 0) return null
        val earliest: Long
        val firstOffset: Long
        if (version == 0) {
            earliest = u32(b, q)
            firstOffset = u32(b, q + 4)
            q += 8
        } else {
            if (q + 16 > b.size) return null
            earliest = u64(b, q)
            firstOffset = u64(b, q + 8)
            q += 16
        }
        q += 2 // reserved
        if (q + 2 > b.size) return null
        val count = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
        q += 2
        var offset = startByte + p + boxSize + firstOffset
        var t = earliest
        val out = mutableListOf<DashSeg>()
        for (i in 0 until count) {
            if (q + 12 > b.size) break
            val size = u32(b, q) and 0x7FFFFFFF
            val dur = u32(b, q + 4)
            q += 12
            out += DashSeg(offset, size, t * 1_000_000 / timescale, dur * 1_000_000 / timescale)
            offset += size
            t += dur
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Fragments that cover [fromUs]..[toUs] (each one starts with a key frame). */
    fun pickSegs(segs: List<DashSeg>, fromUs: Long, toUs: Long): List<DashSeg> =
        segs.filter { it.startUs + it.durUs > fromUs && it.startUs < toUs }

    /**
     * Shift that turns sample times of a downloaded fragment into times of the video: some extractors count from
     * zero instead of the fragment time ([firstSegUs]).
     */
    fun shiftUs(firstSampleUs: Long, firstSegUs: Long): Long =
        if (firstSegUs > 500_000 && firstSampleUs + 500_000 < firstSegUs) firstSegUs - firstSampleUs else 0

    // ---------- montage ----------

    /**
     * Where the clips play: every scene with a downloaded clip gets a slot over its time. The same clip on the next
     * scene goes on instead of starting again; a scene longer than its clip loops it.
     */
    fun slots(times: List<SceneTime>, sceneClips: List<String>, clips: Map<String, SClip>): List<ClipSlot> {
        val out = mutableListOf<ClipSlot>()
        for (st in times) {
            val id = sceneClips.getOrNull(st.scene)?.takeIf { it.isNotEmpty() } ?: continue
            val c = clips[id]?.takeIf { it.lenMs > 0 && it.file.isNotEmpty() } ?: continue
            val prev = out.lastOrNull()
            val offset = if (prev != null && prev.id == id && prev.endMs >= st.startMs - 50) (prev.offsetMs + prev.endMs - prev.startMs) % c.lenMs else 0L
            out += ClipSlot(st.startMs, st.endMs, id, c.leadMs, c.lenMs, offset)
        }
        return out
    }

    /**
     * Like [slots], but a scene longer than what is left of its clip goes on with other clips instead of looping
     * it: every clip keeps a cursor (the next unused moment), the scene's clip starts at its cursor, and the rest
     * of the scene takes the clip with the most unused time. Clips repeat only when all of them are used up.
     */
    fun fillSlots(times: List<SceneTime>, sceneClips: List<String>, clips: Map<String, SClip>): List<ClipSlot> {
        val ready = clips.filterValues { it.lenMs > 0 && it.file.isNotEmpty() }
        if (ready.isEmpty()) return emptyList()
        val cursor = HashMap<String, Long>()
        fun left(id: String) = ready.getValue(id).lenMs - (cursor[id] ?: 0)
        val out = mutableListOf<ClipSlot>()
        for (st in times) {
            var id = sceneClips.getOrNull(st.scene)?.takeIf { it in ready } ?: continue
            var at = st.startMs
            var guard = 0
            while (at < st.endMs - MIN_SLOT_MS / 2 && guard++ < 200) {
                if (left(id) < MIN_SLOT_MS) {
                    val next = ready.keys.filter { left(it) >= MIN_SLOT_MS }.maxByOrNull { left(it) * 2 + if (it == id) 0 else 1 }
                    if (next == null) ready.keys.forEach { cursor[it] = 0 } else id = next
                }
                val c = ready.getValue(id)
                val pos = cursor[id] ?: 0
                val end = if (st.endMs - at - (c.lenMs - pos) < MIN_SLOT_MS) st.endMs else at + (c.lenMs - pos)
                out += ClipSlot(at, end, id, c.leadMs, c.lenMs, pos)
                cursor[id] = minOf(c.lenMs, pos + (end - at))
                at = end
            }
        }
        return out
    }

    // ---------- pieces cut at the storyboard ----------

    /** A piece is downloaded this much longer than its place: the scenes may move a little after a new layout. */
    const val PIECE_MARGIN_MS = 700L

    /** Pieces of a scene [lenMs] long: by [pieceMs] (0 = one piece for the scene), each at most [MAX_CLIP_MS]. */
    fun piecesOf(lenMs: Long, pieceMs: Long): Int {
        val most = MAX_CLIP_MS - PIECE_MARGIN_MS
        val byLen = if (pieceMs <= 0) 1 else ((lenMs + pieceMs / 2) / pieceMs).toInt().coerceAtLeast(1)
        return maxOf(byLen, ((lenMs + most - 1) / most).toInt())
    }

    /**
     * Pieces for the voice-over: every scene with a clip ([sceneClips], clip id per scene) gets pieces of its
     * clip's video that together last exactly the scene ([piecesOf], plus [PIECE_MARGIN_MS] each), so the cut
     * video is as long as the voice. [seed] 0 — from the author's start of each video onwards, the next piece
     * right after the previous one of that video; otherwise new random places of the whole video (needs
     * [SClip.durMs]) that miss [used] (url → parts taken before). Pieces are numbered "p<scene>_<k>".
     */
    fun cutPlan(times: List<SceneTime>, sceneClips: List<String>, clips: Map<String, SClip>, pieceMs: Long,
                seed: Long = 0, used: Map<String, List<Pair<Long, Long>>> = emptyMap()): List<SClip> {
        val out = mutableListOf<SClip>()
        val taken = HashMap<String, MutableList<Pair<Long, Long>>>()
        used.forEach { (u, l) -> taken[u] = l.toMutableList() }
        val cursor = HashMap<String, Long>()
        for (st in times) {
            val id = sceneClips.getOrNull(st.scene)?.takeIf { it.isNotEmpty() } ?: continue
            val c = clips[id] ?: continue
            val total = st.endMs - st.startMs
            if (total < MIN_SLOT_MS) continue
            val n = piecesOf(total, pieceMs)
            for (k in 0 until n) {
                val len = total / n + PIECE_MARGIN_MS
                val dur = c.durMs
                val have = taken.getOrPut(c.url) { mutableListOf() }
                val from = if (seed != 0L && dur > len) {
                    StudioMix.freshStart(dur, len, have, seed * 7919 + out.size)
                } else {
                    val at = cursor[c.url] ?: maxOf(c.fromMs, used[c.url]?.maxOfOrNull { it.second } ?: 0L)
                    // the video ended: go on from a fresh place of it
                    if (dur > len && at + len > dur - dur / 20) StudioMix.freshStart(dur, len, have, c.url.hashCode() + out.size.toLong()) else at
                }
                have += from to from + len
                cursor[c.url] = from + len
                out += SClip("p${st.scene}_$k", c.url, from, from + len, c.note, c.title, durMs = dur,
                    scene = st.scene, src = id, part = k, parts = n)
            }
        }
        return out
    }

    /** Scenes whose pieces are missing or do not fit any more (another clip, or the scene grew past the piece). */
    fun needCut(times: List<SceneTime>, sceneClips: List<String>, pieces: List<SClip>): List<Int> {
        val by = pieces.groupBy { it.scene }
        return times.filter { st ->
            val id = sceneClips.getOrNull(st.scene)?.takeIf { it.isNotEmpty() } ?: return@filter false
            if (st.endMs - st.startMs < MIN_SLOT_MS) return@filter false
            val ps = by[st.scene].orEmpty().filter { it.src == id }
            if (ps.isEmpty()) return@filter true
            val each = (st.endMs - st.startMs) / ps.size
            ps.any { it.ms + 300 < each }
        }.map { it.scene }
    }

    /**
     * Slots of the pieces cut at the storyboard (downloaded ones only): the pieces of a scene share its time
     * evenly, in their order; scenes without pieces get no slot.
     */
    fun pieceSlots(times: List<SceneTime>, sceneClips: List<String>, pieces: List<SClip>): List<ClipSlot> {
        val by = pieces.filter { it.lenMs > 0 && it.file.isNotEmpty() }.groupBy { it.scene }
        val out = mutableListOf<ClipSlot>()
        for (st in times) {
            val id = sceneClips.getOrNull(st.scene)?.takeIf { it.isNotEmpty() } ?: continue
            val ps = by[st.scene].orEmpty().filter { it.src == id }.sortedBy { it.part }
            if (ps.isEmpty()) continue
            val total = st.endMs - st.startMs
            ps.forEachIndexed { i, p ->
                val a = st.startMs + total * i / ps.size
                val b = st.startMs + total * (i + 1) / ps.size
                if (b > a) out += ClipSlot(a, b, p.id, p.leadMs, p.lenMs)
            }
        }
        return out
    }

    /** A clip piece shorter than this is not worth a cut: the scene keeps the clip a little longer instead. */
    const val MIN_SLOT_MS = 400L

    /**
     * Does scene [sc] have nothing to show on its own? Plain words, or a lone icon without a title: on a
     * light insert such a scene is an empty screen with one word, so it keeps the clip playing instead.
     */
    fun bare(sc: SScene): Boolean {
        if (sc.media.isNotBlank() || sc.shapes.isNotEmpty() || sc.scheme != null || Studio.isTpl(sc.kind)) return false
        if (sc.kind == "text") return true
        return sc.kind !in listOf("step", "stat", "big", "quote", "chart", "compare") && sc.items.size <= 1 && sc.title.isBlank()
    }

    /**
     * Clip of every scene for the montage: a bare scene ([bare]) without a ready clip of its own continues the clip
     * before it (the first ones take the next clip), so the video never drops to an empty screen. [all] — every scene
     * without a clip does so (the full-screen schemes are off: the video plays on under them).
     */
    fun effectiveClips(scenes: List<SScene>, ready: Set<String>, all: Boolean = false): List<String> {
        val own = scenes.map { it.clip.takeIf { c -> c in ready } ?: "" }
        val out = own.toMutableList()
        var last = ""
        for (i in scenes.indices) {
            if (out[i].isNotEmpty()) last = out[i] else if (all || bare(scenes[i])) out[i] = last
        }
        val first = own.indexOfFirst { it.isNotEmpty() }
        if (first > 0) for (i in 0 until first) if (out[i].isEmpty() && (all || bare(scenes[i]))) out[i] = own[first]
        return out
    }

    /** Index of the slot playing at [tMs], or −1. */
    fun slotAt(slots: List<ClipSlot>, tMs: Long): Int {
        var lo = 0
        var hi = slots.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val s = slots[mid]
            when {
                tMs < s.startMs -> hi = mid - 1
                tMs >= s.endMs -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** Time inside the clip file at output time [tMs]. */
    fun fileTime(s: ClipSlot, tMs: Long): Long =
        s.leadMs + if (s.lenMs <= 0) 0 else (s.offsetMs + (tMs - s.startMs).coerceAtLeast(0)) % s.lenMs

    /** Word stems (first 5 letters of words of 4+ letters) to match a scene with a clip note. */
    fun stems(s: String): Set<String> = Studio.tokens(s).map { Studio.norm(it) }.filter { it.length >= 4 }.map { it.take(5) }.toSet()

    /**
     * Clip of every scene without the AI: a clip goes to the free scene that shares most words with its note,
     * the rest are spread evenly over the free scenes. Every clip is used once while there are free scenes.
     */
    fun autoAssign(scenes: List<SScene>, clips: List<SClip>): List<String> {
        val out = MutableList(scenes.size) { "" }
        if (scenes.isEmpty() || clips.isEmpty()) return out
        val sceneStems = scenes.map { stems(it.say + " " + it.title) }
        val rest = mutableListOf<SClip>()
        for (c in clips) {
            val cs = stems(c.note + " " + c.title)
            val best = scenes.indices.filter { out[it].isEmpty() }
                .map { it to sceneStems[it].count { s -> s in cs } }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            if (best != null) out[best.first] = c.id else rest += c
        }
        val free = scenes.indices.filter { out[it].isEmpty() }
        if (rest.isNotEmpty() && free.isNotEmpty()) {
            val n = minOf(rest.size, free.size)
            // the middle of n equal parts of the free scenes
            for (k in 0 until n) out[free[(2 * k + 1) * free.size / (2 * n)]] = rest[k].id
        }
        return out
    }

    /** Part of the director prompt about the downloaded clips (goes after the visuals); empty without clips. */
    fun promptBlock(clips: List<SClip>, anyLength: Boolean = false): String {
        val ready = if (anyLength) clips.filter { it.url.isNotBlank() } else clips.filter { it.file.isNotEmpty() && it.lenMs > 0 }
        if (ready.isEmpty()) return ""
        return " Ещё есть видеоклипы для вставок (B-roll и мемы-реакции): " +
            ready.joinToString("; ") { c ->
                val about = c.note.ifBlank { c.title }.replace('\n', ' ').trim().take(100)
                if (anyLength) "${c.id} — «$about»" else "${c.id} — «$about», ${(c.lenMs + 500) / 1000} с"
            } +
            ". Сцене можно дать поле \"clip\" с id клипа: тогда вместо графики на экране идёт этот клип, а голос продолжает рассказ. " +
            "Ставь клип туда, где он по смыслу иллюстрирует речь или смешно реагирует на неё; " +
            (if (anyLength) "куски клипа нарежутся под длину сцены, клип можно ставить на несколько сцен; "
            else "каждый клип 1-2 раза; сцена с клипом обычно не длиннее клипа; ") + "между клипами — сцены с графикой."
    }

    // ---------- search by title ----------

    /** Numbered list of found videos for the AI filter prompt: "[1] title — channel, 3:20". */
    fun hitsBlock(hits: List<CutHit>): String = hits.withIndex().joinToString("\n") { (i, h) ->
        "[${i + 1}] " + h.title.replace('\n', ' ').trim().take(140) +
            (if (h.channel.isNotBlank()) " — " + h.channel.take(60) else "") +
            (if (h.durationMs > 0) ", " + stamp(h.durationMs) else "")
    }

    /**
     * The AI filter answer {"picks":[{"n":3,"note":"..."}]} → index in the list (0-based) to what is in the shot.
     * Numbers out of 1..[count] and repeats are dropped; null when it is not that JSON.
     */
    fun parsePicks(s: String, count: Int): Map<Int, String>? = runCatching {
        val t = s.trim().let { v -> v.substring(v.indexOf('{').coerceAtLeast(0), (v.lastIndexOf('}') + 1).coerceAtLeast(1)) }
        val arr = JSONObject(t).getJSONArray("picks")
        val out = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val n = o?.optInt("n", 0) ?: arr.optInt(i, 0)
            if (n !in 1..count || out.containsKey(n - 1)) continue
            out[n - 1] = o?.optString("note", "").orEmpty().replace('\n', ' ').trim().take(200)
        }
        out
    }.getOrNull()

    /**
     * Adds the marked videos as clips from [fromMs] (default length); a video already in the list is skipped,
     * at most [MAX_CLIPS] in all. The note is the AI note or the title.
     */
    /** Most topics one search runs. */
    const val MAX_TOPICS = 10

    /**
     * Search topics from the text of the search field: one per line (list marks "-", "•", "1." are dropped),
     * blank lines and repeats (any case) skipped, at most [MAX_TOPICS].
     */
    fun topics(text: String): List<String> {
        val seen = HashSet<String>()
        return text.lines()
            .map { it.trim().replace(Regex("^(?:[-•*·]+|\\d{1,2}[.)])\\s*"), "").trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .take(MAX_TOPICS)
    }

    /** Results per topic: one topic gets [total], several share it, but each gets [min]..[max]. */
    fun perTopic(n: Int, total: Int = 30, min: Int = 8, max: Int = 20): Int =
        if (n <= 1) total else (total * 2 / n).coerceIn(min, max)

    /** Results of several topics one by one (first of each, then second…), a video found twice is kept once. */
    fun mergeHits(groups: List<List<CutHit>>): List<CutHit> {
        val out = ArrayList<CutHit>()
        val have = HashSet<String>()
        val longest = groups.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (g in groups) {
            val h = g.getOrNull(i) ?: continue
            if (have.add(h.url)) out += h
        }
        return out
    }

    fun addHits(clips: List<SClip>, hits: List<CutHit>, notes: Map<String, String> = emptyMap(), fromMs: Long = 0): List<SClip> {
        val out = clips.toMutableList()
        val have = clips.map { it.url }.toMutableSet()
        for (h in hits) {
            if (out.size >= MAX_CLIPS) break
            if (h.url in have) continue
            have += h.url
            val (a, b) = span(fromMs, null)
            out += SClip(newId(out), h.url, a, b, notes[h.url].orEmpty().ifBlank { h.title.take(200) }, h.title, durMs = h.durationMs)
        }
        return out
    }
}

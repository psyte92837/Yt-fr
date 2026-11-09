/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/*
 * Lumen Studio "Mix": pieces of downloaded YouTube clips shuffled so that two pieces of one clip never follow
 * each other, then edited on a storyboard and built as a video (optionally with a voice-over, schemes and subtitles).
 * Pure logic here; the screen is StudioMixActivity.
 */

/**
 * One element of the mix: [lenMs] of clip [clipId] from [fromMs] (time inside the downloaded piece, from its lead).
 * [marked] — ticked on the storyboard; [scheme] — info-scheme drawn over this element; [locked] — pinned: it keeps
 * its place when the storyboard is shuffled or arranged; [dup] — copy number of a duplicated piece (0 = original).
 */
data class MixItem(
    val clipId: String,
    val fromMs: Long,
    val lenMs: Long,
    val marked: Boolean = false,
    val scheme: SScheme? = null,
    val locked: Boolean = false,
    val dup: Int = 0
) {
    /** Stable key for the storyboard: a clip piece is unique by clip, start and copy number. */
    val key: String get() = "$clipId@$fromMs" + if (dup > 0) "#$dup" else ""
}

/** How a clip is cut: equal parts, random moments of random length around the piece length, or own moments. */
enum class MixCut(val id: String) {
    EVEN("even"), RANDOM("random"), MARKS("marks");

    companion object {
        fun of(id: String?): MixCut? = values().firstOrNull { it.id == id }
    }
}

/**
 * How the storyboard reaches the set video length when the pieces are not enough: new pieces are taken (other
 * places of the clips, then new parts of the same videos are downloaded), or the pieces are stretched and repeated.
 */
enum class MixFill(val id: String) {
    PIECES("pieces"), STRETCH("stretch");

    companion object {
        fun of(id: String?): MixFill = values().firstOrNull { it.id == id } ?: PIECES
    }
}

/** How the pieces are put in a row: shuffled (no two of one clip in a row), clip after clip, or clips in turn. */
enum class MixOrder { SHUFFLE, ORDER, ROUND }

/**
 * Own cutting of one clip: [cut] null = as for all clips, [pieceMs] 0 = the common piece length, [marks] — own
 * moments (ms inside the downloaded piece) for [MixCut.MARKS].
 */
data class ClipCut(val cut: MixCut? = null, val pieceMs: Long = 0, val marks: List<Long> = emptyList()) {
    val isDefault: Boolean get() = cut == null && pieceMs <= 0
}

/** A downloaded clip as the mix sees it: id, usable length and its own cutting. */
data class MixSpec(val id: String, val ms: Long, val cut: ClipCut = ClipCut())

object StudioMix {
    val PIECES = listOf(500L, 1_000L, 1_500L, 2_000L, 3_000L, 5_000L)
    const val DEFAULT_PIECE_MS = 2_000L
    /** A piece shorter than this is not worth a cut. */
    const val MIN_PIECE_MS = 300L

    /** Own piece length typed in seconds ("1.5", "1,5", "2 с") → ms within [MIN_PIECE_MS]..[StudioCuts.MAX_CLIP_MS]; null if not a number. */
    fun parsePiece(s: String): Long? {
        val v = s.trim().lowercase().removeSuffix("с").removeSuffix("s").trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (v.isNaN() || v <= 0) return null
        return (v * 1000).toLong().coerceIn(MIN_PIECE_MS, StudioCuts.MAX_CLIP_MS)
    }

    /** 500 → "0.5 с", 2000 → "2 с". */
    fun pieceLabel(ms: Long): String =
        (if (ms % 1000 == 0L) (ms / 1000).toString() else "%.1f".format(java.util.Locale.US, ms / 1000.0).trimEnd('0').trimEnd('.')) + " с"

    /**
     * Was the storyboard changed by hand since it was made from [specs] with these settings: order, deletions,
     * ticks, pins or schemes? Such work is lost when the pieces are cut again.
     */
    fun edited(items: List<MixItem>, specs: List<MixSpec>, pieceMs: Long, mode: MixCut, cutSeed: Long, seed: Long): Boolean =
        items.any { it.marked || it.locked || it.scheme != null } ||
            items.map { it.key to it.lenMs } != build(specs, pieceMs, mode, cutSeed, seed).map { it.key to it.lenMs }

    fun edited(items: List<MixItem>, clips: List<Pair<String, Long>>, pieceMs: Long, seed: Long): Boolean =
        edited(items, clips.map { MixSpec(it.first, it.second) }, pieceMs, MixCut.EVEN, 0, seed)

    /** Pieces of a clip of usable length [clipMs]: equal parts close to [pieceMs]. */
    fun pieces(clipId: String, clipMs: Long, pieceMs: Long): List<MixItem> {
        if (clipMs < MIN_PIECE_MS || pieceMs <= 0) return emptyList()
        val n = ((clipMs + pieceMs / 2) / pieceMs).coerceAtLeast(1)
        val len = clipMs / n
        return (0 until n).map { k -> MixItem(clipId, k * len, len) }
    }

    /**
     * Pieces at random moments: about 3/4 of the clip is used, pieces of 60–140 % of [pieceMs] with random gaps
     * between them, in time order, never overlapping. The same [seed] gives the same pieces.
     */
    fun randomPieces(clipId: String, clipMs: Long, pieceMs: Long, seed: Long): List<MixItem> {
        if (clipMs < MIN_PIECE_MS || pieceMs <= 0) return emptyList()
        val rnd = Random(seed)
        val p = pieceMs.coerceIn(MIN_PIECE_MS, clipMs)
        val n = ((clipMs * 3 / 4 + p / 2) / p).toInt().coerceIn(1, (clipMs / MIN_PIECE_MS).toInt().coerceAtLeast(1))
        val lo = maxOf(MIN_PIECE_MS, p * 6 / 10)
        val hi = maxOf(lo, p * 14 / 10)
        var lens = List(n) { lo + (rnd.nextDouble() * (hi - lo)).toLong() }
        val sum = lens.sum()
        if (sum > clipMs) lens = lens.map { maxOf(MIN_PIECE_MS, it * clipMs / sum) }
        while (lens.size > 1 && lens.sum() > clipMs) lens = lens.dropLast(1)
        lens = lens.map { minOf(it, clipMs) }
        val free = (clipMs - lens.sum()).coerceAtLeast(0)
        val w = List(lens.size + 1) { 0.05 + rnd.nextDouble() }
        val wSum = w.sum()
        var at = (free * w[0] / wSum).toLong()
        return lens.mapIndexed { k, len ->
            val from = at.coerceAtMost(clipMs - len)
            at = from + len + (free * w[k + 1] / wSum).toLong()
            MixItem(clipId, from, len)
        }
    }

    /** Pieces from own moments [marks]: each one [pieceMs] long, but not past the next moment or the clip end. */
    fun markPieces(clipId: String, clipMs: Long, pieceMs: Long, marks: List<Long>): List<MixItem> {
        val ms = marks.filter { it >= 0 && it <= clipMs - MIN_PIECE_MS }.distinct().sorted()
        return ms.mapIndexedNotNull { i, at ->
            val end = minOf(at + pieceMs, clipMs, ms.getOrNull(i + 1) ?: Long.MAX_VALUE)
            (end - at).takeIf { it >= MIN_PIECE_MS }?.let { MixItem(clipId, at, it) }
        }
    }

    /** Pieces of one clip by its own cutting, or by the common [pieceMs] and [mode]. */
    fun cut(spec: MixSpec, pieceMs: Long, mode: MixCut, cutSeed: Long): List<MixItem> {
        val p = spec.cut.pieceMs.takeIf { it > 0 } ?: pieceMs
        return when (spec.cut.cut ?: mode) {
            MixCut.EVEN -> pieces(spec.id, spec.ms, p)
            MixCut.RANDOM -> randomPieces(spec.id, spec.ms, p, cutSeed * 31 + spec.id.hashCode())
            MixCut.MARKS -> markPieces(spec.id, spec.ms, p, spec.cut.marks).ifEmpty { pieces(spec.id, spec.ms, p) }
        }
    }

    /** The whole storyboard: every clip cut, then put in a row by [order]. */
    fun build(specs: List<MixSpec>, pieceMs: Long, mode: MixCut, cutSeed: Long, seed: Long, order: MixOrder = MixOrder.SHUFFLE): List<MixItem> =
        arrange(specs.flatMap { cut(it, pieceMs, mode, cutSeed) }, order, seed)

    /**
     * All pieces of [clips] (id to usable length) in a random order where two pieces of one clip are never
     * neighbours, while that is possible: a clip with more than half of what is left goes first.
     */
    fun mix(clips: List<Pair<String, Long>>, pieceMs: Long, seed: Long): List<MixItem> =
        build(clips.map { MixSpec(it.first, it.second) }, pieceMs, MixCut.EVEN, 0, seed)

    /** Clip order for "clip after clip": c1, c2, …, c10 (by number), then the rest by name. */
    private fun clipOrder(ids: Collection<String>): List<String> =
        ids.distinct().sortedWith(compareBy<String>({ it.removePrefix("c").toIntOrNull() ?: Int.MAX_VALUE }, { it }))

    /** [pool] in a row by [order], nothing pinned. */
    fun arrange(pool: List<MixItem>, order: MixOrder, seed: Long): List<MixItem> =
        fillFree(pool.map { it.copy(locked = false) }, pool.indices.toSet(), order, seed)

    /** The storyboard put in a new row by [order]: pinned elements keep their places. */
    fun rearrange(items: List<MixItem>, order: MixOrder, seed: Long): List<MixItem> =
        fillFree(items, items.indices.filter { !items[it].locked }.toSet(), order, seed)

    /** Only the ticked (not pinned) elements are shuffled among their own places. */
    fun shuffleMarked(items: List<MixItem>, seed: Long): List<MixItem> =
        fillFree(items, items.indices.filter { items[it].marked && !items[it].locked }.toSet(), MixOrder.SHUFFLE, seed)

    /** Elements at [free] places are put in a new row by [order]; the others stay where they are. */
    private fun fillFree(items: List<MixItem>, free: Set<Int>, order: MixOrder, seed: Long): List<MixItem> {
        if (free.isEmpty()) return items
        val slots = arrayOfNulls<MixItem>(items.size)
        items.forEachIndexed { i, it -> if (i !in free) slots[i] = it }
        val pool = items.filterIndexed { i, _ -> i in free }
        if (order != MixOrder.SHUFFLE) {
            val byClip = pool.groupBy { it.clipId }.mapValues { (_, l) -> l.sortedBy { it.fromMs }.toMutableList() }
            val ids = clipOrder(byClip.keys)
            val seq = if (order == MixOrder.ORDER) ids.flatMap { byClip.getValue(it) }
            else buildList {
                while (size < pool.size) for (id in ids) {
                    val l = byClip.getValue(id)
                    if (l.isNotEmpty()) add(l.removeAt(0))
                }
            }
            var k = 0
            for (i in slots.indices) if (slots[i] == null) slots[i] = seq[k++]
            return slots.map { it!! }
        }
        val rnd = Random(seed)
        val left = LinkedHashMap<String, MutableList<MixItem>>()
        pool.groupBy { it.clipId }.forEach { (id, l) -> left[id] = l.shuffled(rnd).toMutableList() }
        var remaining = pool.size
        for (i in slots.indices) {
            if (slots[i] != null) continue
            val prev = slots.getOrNull(i - 1)?.clipId
            val next = slots.getOrNull(i + 1)?.clipId
            val must = left.entries.firstOrNull { it.key != prev && it.value.size * 2 > remaining }?.key
            val pick = must ?: left.keys.filter { it != prev && it != next }
                .ifEmpty { left.keys.filter { it != prev } }.ifEmpty { left.keys.toList() }.let { ok ->
                    // weighted by what is left, so big clips do not pile up at the end
                    var r = rnd.nextInt(ok.sumOf { left.getValue(it).size })
                    ok.first { id -> r -= left.getValue(id).size; r < 0 }
                }
            val list = left.getValue(pick)
            slots[i] = list.removeAt(list.lastIndex)
            if (list.isEmpty()) left.remove(pick)
            remaining--
        }
        return slots.map { it!! }
    }

    /**
     * [new] pieces put into the storyboard at random places, between pieces of other clips where possible;
     * the rest of the storyboard keeps its order.
     */
    fun addPieces(items: List<MixItem>, new: List<MixItem>, seed: Long): List<MixItem> {
        val rnd = Random(seed)
        val out = items.toMutableList()
        for (p in new.shuffled(rnd)) {
            val good = (0..out.size).filter { at ->
                out.getOrNull(at - 1)?.clipId != p.clipId && out.getOrNull(at)?.clipId != p.clipId
            }
            val at = if (good.isNotEmpty()) good[rnd.nextInt(good.size)] else rnd.nextInt(out.size + 1)
            out.add(at, p)
        }
        return unique(out)
    }

    /** The pieces of clip [clipId] replaced by [new] ones at random places; the other elements keep their order. */
    fun replaceClip(items: List<MixItem>, clipId: String, new: List<MixItem>, seed: Long): List<MixItem> =
        addPieces(items.filter { it.clipId != clipId }, new, seed)

    /** Ready clips that have no element on the storyboard (new or re-cut clips). */
    fun missing(items: List<MixItem>, ready: List<String>): List<String> {
        val have = items.map { it.clipId }.toSet()
        return ready.filter { it !in have }
    }

    /** Elements with equal keys get their own copy numbers, so the storyboard never has two equal keys. */
    fun unique(items: List<MixItem>): List<MixItem> {
        val seen = HashSet<String>()
        return items.map { item ->
            var it = item
            while (!seen.add(it.key)) it = it.copy(dup = it.dup + 1)
            it
        }
    }

    // ---------- one element ----------

    /** Start moved by [deltaMs] inside the clip of usable length [clipMs]. */
    fun shift(item: MixItem, deltaMs: Long, clipMs: Long): MixItem =
        item.copy(fromMs = (item.fromMs + deltaMs).coerceIn(0, (clipMs - item.lenMs).coerceAtLeast(0)))

    /** New length, kept inside the clip: the start moves back when the end would pass the clip end. */
    fun withLen(item: MixItem, lenMs: Long, clipMs: Long): MixItem {
        val len = lenMs.coerceIn(MIN_PIECE_MS, clipMs.coerceAtLeast(MIN_PIECE_MS))
        return item.copy(lenMs = len, fromMs = item.fromMs.coerceAtMost((clipMs - len).coerceAtLeast(0)))
    }

    /** The same length at another random moment of the clip, at least a piece away from the old one when it fits. */
    fun moment(item: MixItem, clipMs: Long, seed: Long): MixItem {
        val room = clipMs - item.lenMs
        if (room <= 0) return item.copy(fromMs = 0)
        val rnd = Random(seed)
        var from = rnd.nextLong(room + 1)
        repeat(8) { if (kotlin.math.abs(from - item.fromMs) < item.lenMs && room > item.lenMs * 2) from = rnd.nextLong(room + 1) }
        return item.copy(fromMs = from / 100 * 100)
    }

    /** Element [i] cut in two halves; null when a half would be shorter than [MIN_PIECE_MS]. */
    fun halves(items: List<MixItem>, i: Int): List<MixItem>? {
        val it = items.getOrNull(i) ?: return null
        val a = it.lenMs / 2
        if (a < MIN_PIECE_MS || it.lenMs - a < MIN_PIECE_MS) return null
        val out = items.toMutableList()
        out[i] = it.copy(lenMs = a)
        out.add(i + 1, it.copy(fromMs = it.fromMs + a, lenMs = it.lenMs - a, scheme = null, dup = 0))
        return unique(out)
    }

    /** A copy of element [i] right after it (not ticked, not pinned, without a scheme). */
    fun duplicate(items: List<MixItem>, i: Int): List<MixItem> {
        val it = items.getOrNull(i) ?: return items
        val out = items.toMutableList()
        out.add(i + 1, it.copy(marked = false, locked = false, scheme = null))
        return unique(out)
    }

    /** Copies of the ticked elements, each right after its original. */
    fun duplicateMarked(items: List<MixItem>): List<MixItem> =
        unique(items.flatMap { if (it.marked) listOf(it, it.copy(marked = false, locked = false, scheme = null)) else listOf(it) })

    /** Ticked elements moved to the start (or the end), keeping their order. */
    fun markedTo(items: List<MixItem>, start: Boolean): List<MixItem> {
        val (on, off) = items.partition { it.marked }
        return if (start) on + off else off + on
    }

    /** The storyboard cut to [targetMs]: the elements from the start, the last one shortened (not below the minimum). */
    fun trimTo(items: List<MixItem>, targetMs: Long): List<MixItem> {
        val out = mutableListOf<MixItem>()
        var at = 0L
        for (it in items) {
            if (at >= targetMs) break
            val len = minOf(it.lenMs, targetMs - at)
            if (len < MIN_PIECE_MS && out.isNotEmpty()) break
            out += it.copy(lenMs = maxOf(len, minOf(it.lenMs, MIN_PIECE_MS)))
            at += out.last().lenMs
        }
        return out
    }

    /**
     * Piece lengths scaled so the storyboard lasts [targetMs] (e.g. the voice-over): every piece stays within
     * [MIN_PIECE_MS] and its clip ([clipMs] by clip id), what one piece cannot take goes to the others. When the
     * clips are too short for the target the storyboard gets as close as it can (see [loopTo]).
     */
    fun scaleTo(items: List<MixItem>, targetMs: Long, clipMs: Map<String, Long>): List<MixItem> {
        if (items.isEmpty() || targetMs <= 0) return items
        val cap = items.map { (clipMs[it.clipId] ?: it.fromMs + it.lenMs).coerceAtLeast(MIN_PIECE_MS) }
        val lens = items.map { it.lenMs.toDouble() }.toMutableList()
        val fixed = BooleanArray(items.size)
        repeat(6) {
            val free = items.indices.filter { !fixed[it] }
            if (free.isEmpty()) return@repeat
            val need = targetMs - items.indices.filter { fixed[it] }.sumOf { lens[it] }
            val have = free.sumOf { lens[it] }
            if (have <= 0) return@repeat
            val k = need / have
            var clamped = false
            for (i in free) {
                val v = lens[i] * k
                val c = v.coerceIn(MIN_PIECE_MS.toDouble(), cap[i].toDouble())
                if (c != v) {
                    fixed[i] = true
                    clamped = true
                }
                lens[i] = c
            }
            if (!clamped) return@repeat
        }
        val out = items.mapIndexed { i, it -> withLen(it, kotlin.math.round(lens[i]).toLong(), cap[i]) }.toMutableList()
        // rounding: the last piece with room takes the difference of a few ms
        val diff = targetMs - totalMs(out)
        if (diff != 0L) {
            val j = out.indices.lastOrNull { val n = out[it].lenMs + diff; n >= MIN_PIECE_MS && n <= cap[it] }
            if (j != null) out[j] = withLen(out[j], out[j].lenMs + diff, cap[j])
        }
        return out
    }

    /**
     * The storyboard made exactly [targetMs] long without changing pieces: cut at the end when longer, continued
     * by copies of its pieces from the start when shorter (no same clip at the joint when it can be avoided).
     */
    fun loopTo(items: List<MixItem>, targetMs: Long): List<MixItem> {
        if (items.isEmpty() || targetMs <= 0) return items
        if (totalMs(items) >= targetMs) return trimTo(items, targetMs)
        val out = items.toMutableList()
        var k = 0
        var guard = 0
        while (totalMs(out) < targetMs && guard++ < 10_000) {
            var next = items[k % items.size]
            if (next.clipId == out.last().clipId && items.size > 1) next = items[++k % items.size]
            out += next.copy(marked = false, locked = false, scheme = null)
            k++
        }
        return unique(trimTo(out, targetMs))
    }

    /**
     * Pieces dropped until the storyboard is about [targetMs] long: always from the clip with the most pieces left
     * (so every clip stays), from the middle of its pieces; pinned pieces stay. Used before [scaleTo] so a long
     * storyboard is not squeezed into flicker.
     */
    fun thinTo(items: List<MixItem>, targetMs: Long): List<MixItem> {
        val out = items.toMutableList()
        while (out.size > 1) {
            val total = totalMs(out)
            if (total <= targetMs) break
            val byClip = out.indices.filter { !out[it].locked }.groupBy { out[it].clipId }
            if (byClip.isEmpty()) break
            // a clip keeps its last piece while others have more
            val pool = byClip.filterValues { it.size > 1 }.ifEmpty { byClip }
            val idx = pool.entries.maxWith(compareBy<Map.Entry<String, List<Int>>> { it.value.size }.thenByDescending { it.key }).value
            val j = idx[idx.size / 2]
            val after = total - out[j].lenMs
            // the drop overshoots: keep the piece when that is closer to the target
            if (after < targetMs && targetMs - after > total - targetMs) break
            out.removeAt(j)
        }
        return out
    }

    /**
     * The storyboard made [targetMs] long, the way it looks best: too many pieces are thinned out ([thinTo]),
     * the rest is stretched or squeezed within its clips ([scaleTo]), and what the clips still lack is continued
     * by repeats ([loopTo]).
     */
    fun fitTo(items: List<MixItem>, targetMs: Long, clipMs: Map<String, Long>): List<MixItem> {
        if (items.isEmpty() || targetMs <= 0) return items
        var next = if (totalMs(items) > targetMs) thinTo(items, targetMs) else items
        next = scaleTo(next, targetMs, clipMs)
        if (totalMs(next) != targetMs) next = loopTo(next, targetMs)
        return next
    }

    /**
     * Pieces of the clips that the storyboard has not used yet: the free gaps of every clip ([specs]) between its
     * pieces on the storyboard, cut evenly by its piece length (its own or [pieceMs]). They fill a short storyboard.
     */
    fun spare(items: List<MixItem>, specs: List<MixSpec>, pieceMs: Long): List<MixItem> = specs.flatMap { spec ->
        val p = spec.cut.pieceMs.takeIf { it > 0 } ?: pieceMs
        val on = items.filter { it.clipId == spec.id }.map { it.fromMs to it.fromMs + it.lenMs }.sortedBy { it.first }
        // free gaps of the clip, each cut evenly; a gap shorter than half a piece is not worth it
        val gaps = mutableListOf<Pair<Long, Long>>()
        var at = 0L
        for ((a, b) in on) {
            if (a > at) gaps += at to a
            at = maxOf(at, b)
        }
        if (spec.ms > at) gaps += at to spec.ms
        gaps.filter { (a, b) -> b - a >= maxOf(MIN_PIECE_MS, p / 2) }
            .flatMap { (a, b) -> pieces(spec.id, b - a, p).map { it.copy(fromMs = a + it.fromMs) } }
    }

    /**
     * The storyboard filled to [targetMs] by whole pieces of their own length (nothing stretched or repeated):
     * too many pieces are thinned out ([thinTo]) and the end is cut; too few — [spare] pieces join, always from the
     * clip with the fewest pieces on the storyboard, at places between other clips, until the time is filled.
     * It stays shorter only when the material has ended ([lackMs] says by how much).
     */
    fun fillTo(items: List<MixItem>, targetMs: Long, spare: List<MixItem>, seed: Long): List<MixItem> {
        if (targetMs <= 0 || items.isEmpty() && spare.isEmpty()) return items
        var out = if (totalMs(items) > targetMs) thinTo(items, targetMs) else items
        if (totalMs(out) < targetMs) {
            // pieces the thinning dropped can come back to fill the last gap
            val dropped = items.filter { it !in out }.map { it.copy(marked = false, locked = false) }
            val left = (spare + dropped).groupBy { it.clipId }.mapValues { (_, l) -> l.toMutableList() }.toMutableMap()
            val rnd = Random(seed)
            val add = mutableListOf<MixItem>()
            var total = totalMs(out)
            while (total < targetMs && left.isNotEmpty()) {
                val count = (out + add).groupingBy { it.clipId }.eachCount()
                val id = left.keys.minWith(compareBy<String>({ count[it] ?: 0 }, { it }))
                val pool = left.getValue(id)
                val p = pool.removeAt(rnd.nextInt(pool.size))
                if (pool.isEmpty()) left.remove(id)
                add += p
                total += p.lenMs
            }
            if (add.isNotEmpty()) out = addPieces(out, add, seed)
        }
        return unique(trimTo(out, targetMs))
    }

    /** How much of [targetMs] the storyboard still lacks (0 when it is long enough). */
    fun lackMs(items: List<MixItem>, targetMs: Long): Long = (targetMs - totalMs(items)).coerceAtLeast(0)

    /**
     * New parts of the same videos to download when the clips are not enough for [needMs]: the videos take turns,
     * each part is long enough that together they cover the need with a margin (5–30 s), at most [room] parts;
     * a video whose known length ([SClip.durMs]) is already used up (by its clips and [used] parts) gives no more.
     * The parts keep the place of their source clip: the download picks a fresh moment for them
     * ([freshStart]). Empty when there is nothing left to take.
     */
    fun extraParts(clips: List<SClip>, needMs: Long, room: Int, used: Map<String, List<Pair<Long, Long>>> = emptyMap()): List<SClip> {
        if (needMs <= 0 || room <= 0) return emptyList()
        val byUrl = clips.filter { it.url.isNotBlank() }.groupBy { it.url }
        if (byUrl.isEmpty()) return emptyList()
        val want = needMs * 5 / 4
        val len = (want / byUrl.size).coerceIn(StudioCuts.DEFAULT_CLIP_MS, StudioCuts.MAX_CLIP_MS)
        val taken = byUrl.mapValues { (u, l) -> l.sumOf { it.ms } + used[u].orEmpty().sumOf { (a, b) -> (b - a).coerceAtLeast(0) } }.toMutableMap()
        // a video gives parts while its usable middle (without the intro and the end screen) has room
        fun hasRoom(u: String): Boolean {
            val dur = byUrl.getValue(u).maxOf { it.durMs }
            return dur <= 0 || dur * 85 / 100 - taken.getValue(u) >= len
        }
        val urls = byUrl.keys.filter { hasRoom(it) }.toMutableList()
        val out = mutableListOf<SClip>()
        var sum = 0L
        var k = 0
        while (sum < want && out.size < room && urls.isNotEmpty()) {
            val u = urls[k % urls.size]
            val src = byUrl.getValue(u).first()
            val dur = byUrl.getValue(u).maxOf { it.durMs }
            out += SClip(StudioCuts.newId(clips + out), u, src.fromMs, src.fromMs + len, src.note, src.title, durMs = dur)
            sum += len
            taken[u] = taken.getValue(u) + len
            if (!hasRoom(u)) urls.remove(u) else k++
        }
        return out
    }

    /**
     * Seams between pieces moved to the nearest word start of the voice-over (within [maxShiftMs]), so the picture
     * changes on a word. The length of the storyboard stays; a seam stays where a piece would leave its bounds.
     */
    fun snapToWords(items: List<MixItem>, words: List<SWord>, clipMs: Map<String, Long>, maxShiftMs: Long = 400): List<MixItem> {
        if (items.size < 2 || words.isEmpty()) return items
        val starts = words.map { it.startMs }.sorted()
        val seams = LongArray(items.size + 1)
        for (i in items.indices) seams[i + 1] = seams[i] + items[i].lenMs
        val out = seams.copyOf()
        for (j in 1 until items.size) {
            val near = starts.minByOrNull { kotlin.math.abs(it - seams[j]) } ?: continue
            if (kotlin.math.abs(near - seams[j]) > maxShiftMs) continue
            val a = near - out[j - 1]
            val b = seams[j + 1] - near
            val capA = clipMs[items[j - 1].clipId] ?: continue
            val capB = clipMs[items[j].clipId] ?: continue
            if (a >= MIN_PIECE_MS && b >= MIN_PIECE_MS && a <= capA && b <= capB) out[j] = near
        }
        return items.mapIndexed { i, it ->
            val len = out[i + 1] - out[i]
            if (len == it.lenMs) it else withLen(it, len, clipMs[it.clipId] ?: (it.fromMs + len))
        }
    }

    /** How many seams are on a word start (±[tolMs]). */
    fun seamsOnWords(items: List<MixItem>, words: List<SWord>, tolMs: Long = 60): Int {
        var at = 0L
        return items.dropLast(1).count { item ->
            at += item.lenMs
            words.any { kotlin.math.abs(it.startMs - at) <= tolMs }
        }
    }

    /** Length of a 16-bit PCM WAV from its header and file size; 0 when it is not one. */
    fun wavMs(header: ByteArray, fileBytes: Long): Long {
        if (header.size < 44 || String(header, 0, 4) != "RIFF") return 0
        val b = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt().coerceAtLeast(1)
        val rate = b.getInt(24)
        if (rate <= 0) return 0
        return (fileBytes - 44).coerceAtLeast(0) / (2L * channels) * 1000 / rate
    }

    /**
     * Start of a random new piece of [lenMs] in a video of [durMs] (whole 100 ms), away from [oldMs] when the video
     * is long enough; 0 when the video is not longer than the piece.
     */
    fun randomStart(durMs: Long, lenMs: Long, oldMs: Long, seed: Long): Long {
        val room = durMs - lenMs
        if (room <= 0) return 0
        val rnd = Random(seed)
        var at = rnd.nextLong(room + 1)
        repeat(8) { if (kotlin.math.abs(at - oldMs) < lenMs && room > lenMs * 3) at = rnd.nextLong(room + 1) }
        return at / 100 * 100
    }

    /**
     * A new random start of a [lenMs] part of a [durMs] long video for "🎲 New pieces": not in the first 5 % and
     * the last 10 % (intros, end screens) and not overlapping the parts taken before ([used], from–to ms); when the
     * video has no such room, anywhere apart from the last part ([randomStart]).
     */
    fun freshStart(durMs: Long, lenMs: Long, used: List<Pair<Long, Long>>, seed: Long): Long {
        if (durMs <= lenMs) return 0
        val lo = durMs / 20
        val hi = durMs - durMs / 10 - lenMs
        val rnd = Random(seed)
        if (hi > lo) repeat(24) {
            val at = (lo + rnd.nextLong(hi - lo + 1)) / 100 * 100
            if (used.none { (a, b) -> at < b && at + lenMs > a }) return at
        }
        return randomStart(durMs, lenMs, used.lastOrNull()?.first ?: -lenMs * 10, seed)
    }

    /** Parts of videos already taken, url → [from, to] ms (newest last, at most [keep] per video). */
    fun usedJson(used: Map<String, List<Pair<Long, Long>>>, keep: Int = 30): String = JSONObject().apply {
        for ((u, l) in used) put(u, JSONArray().apply { l.takeLast(keep).forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
    }.toString()

    fun usedFromJson(s: String): Map<String, List<Pair<Long, Long>>> = runCatching {
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { u ->
            val a = o.getJSONArray(u)
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optLong(0) to it.optLong(1) } }
        }
    }.getOrDefault(emptyMap())

    /** Own moments typed as "0:03, 7.5; 0:12" → sorted ms; null when a part is not a time. */
    fun parseMarks(s: String): List<Long>? {
        val parts = s.split(Regex("""[,;\s]+""")).filter { it.isNotBlank() }
        val out = parts.map { StudioCuts.parseTime(it) ?: return null }
        return out.distinct().sorted()
    }

    fun marksText(marks: List<Long>): String = marks.joinToString(", ") { StudioCuts.stamp(it) }

    // ---------- clip cutting saved per clip ----------

    fun cutsJson(cuts: Map<String, ClipCut>): String = JSONObject().apply {
        cuts.filterValues { !it.isDefault || it.marks.isNotEmpty() }.forEach { (id, c) ->
            put(id, JSONObject().put("cut", c.cut?.id ?: "").put("piece", c.pieceMs).put("marks", JSONArray(c.marks)))
        }
    }.toString()

    fun cutsFromJson(s: String): Map<String, ClipCut> = runCatching {
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { id ->
            val c = o.getJSONObject(id)
            val m = c.optJSONArray("marks")
            ClipCut(MixCut.of(c.optString("cut")), c.optLong("piece").coerceAtLeast(0),
                if (m == null) emptyList() else (0 until m.length()).map { m.optLong(it) }.filter { it >= 0 })
        }
    }.getOrDefault(emptyMap())

    /** Frames of a storyboard for a strip: [want] of [total] frames, each from the middle of an equal part. */
    fun stripFrames(total: Int, want: Int): List<Int> {
        if (total <= 0 || want <= 0) return emptyList()
        if (total <= want) return (0 until total).toList()
        return (0 until want).map { k -> ((2L * k + 1) * total / (2L * want)).toInt() }.distinct()
    }

    /** Where frame [k] is in storyboard sprite pages of [perX]×[perY] frames of [fw]×[fh]: page, left, top. */
    fun frameAt(k: Int, perX: Int, perY: Int, fw: Int, fh: Int): Triple<Int, Int, Int> {
        val perPage = (perX * perY).coerceAtLeast(1)
        val inPage = k % perPage
        return Triple(k / perPage, inPage % perX.coerceAtLeast(1) * fw, inPage / perX.coerceAtLeast(1) * fh)
    }

    /** How many neighbours are pieces of the same clip. */
    fun repeats(items: List<MixItem>): Int = items.zipWithNext().count { (a, b) -> a.clipId == b.clipId }

    /** [items] with the element at [from] moved to [to]. */
    fun move(items: List<MixItem>, from: Int, to: Int): List<MixItem> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val out = items.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    fun removeMarked(items: List<MixItem>): List<MixItem> = items.filter { !it.marked }

    /** Elements of clips that are gone are dropped. */
    fun keepClips(items: List<MixItem>, ids: Set<String>): List<MixItem> = items.filter { it.clipId in ids }

    fun totalMs(items: List<MixItem>): Long = items.sumOf { it.lenMs }

    /** Elements one after another, as scenes of the video. */
    fun times(items: List<MixItem>, words: List<IntRange> = emptyList()): List<SceneTime> {
        var at = 0L
        return items.mapIndexed { i, item ->
            val st = SceneTime(i, at, at + item.lenMs, words.getOrElse(i) { IntRange.EMPTY })
            at += item.lenMs
            st
        }
    }

    /** Where each element's clip plays in the video. */
    fun slots(items: List<MixItem>, clips: Map<String, SClip>): List<ClipSlot> {
        var at = 0L
        return items.mapNotNull { item ->
            val start = at
            at += item.lenMs
            val c = clips[item.clipId]?.takeIf { it.lenMs > 0 } ?: return@mapNotNull null
            ClipSlot(start, start + item.lenMs, item.clipId, c.leadMs, c.lenMs, item.fromMs.coerceIn(0, c.lenMs - 1))
        }
    }

    /** Words of the voice-over on every element: the words that start inside it (the last one takes the rest). */
    fun wordsIn(words: List<SWord>, times: List<SceneTime>): List<IntRange> = times.mapIndexed { k, st ->
        val last = k == times.lastIndex
        val a = words.indexOfFirst { it.startMs >= st.startMs && (last || it.startMs < st.endMs) }
        if (a < 0) IntRange.EMPTY
        else {
            var b = a
            while (b + 1 < words.size && (last || words[b + 1].startMs < st.endMs)) b++
            a..b
        }
    }

    /** Elements that get subtitles or schemes: the marked ones when [onlyMarked], else all. */
    fun chosen(items: List<MixItem>, onlyMarked: Boolean): List<Int> = items.indices.filter { !onlyMarked || items[it].marked }

    /** Signature of what the video shows (order, pieces and frame): a built video is reused while it matches. */
    fun signature(items: List<MixItem>, format: String): String =
        format + "|" + items.joinToString(";") { "${it.key}+${it.lenMs}" }

    // ---------- prompt for another AI ----------

    /**
     * A ready prompt for an AI app on the phone that gets the built video: what it is and how to edit it.
     * [notes] — what is in each clip, by id.
     */
    fun montagePrompt(format: String, items: List<MixItem>, notes: Map<String, String>, totalMs: Long, ru: Boolean): String {
        val vertical = format == StudioAspect.PORTRAIT.id
        val fmt = if (ru) {
            if (vertical) "вертикальный 9:16 (YouTube Shorts / Instagram Stories / Reels)" else "горизонтальный 16:9 (YouTube)"
        } else {
            if (vertical) "vertical 9:16 (YouTube Shorts / Instagram Stories / Reels)" else "horizontal 16:9 (YouTube)"
        }
        var at = 0L
        val lines = items.take(60).map { item ->
            val line = StudioCuts.stamp(at) + "–" + StudioCuts.stamp(at + item.lenMs) + " " + item.clipId +
                (notes[item.clipId]?.takeIf { it.isNotBlank() }?.let { " «" + it.replace('\n', ' ').take(60) + "»" } ?: "")
            at += item.lenMs
            line
        }
        val more = if (items.size > 60) "\n…" else ""
        return if (ru) {
            "Это мой видео-микс: формат $fmt, длина ${StudioCuts.stamp(totalMs)}, ${items.size} кусков из чужих клипов, поверх моя озвучка.\n" +
                "Помоги смонтировать финальную версию:\n" +
                "1. Посмотри видео и послушай озвучку: где картинка не совпадает со смыслом речи, предложи переставить или заменить куски (таймкоды).\n" +
                "2. Предложи хук на первые 3 секунды и что вырезать (паузы, оговорки, затянутые места).\n" +
                "3. Дай план монтажа по таймкодам: склейки, зумы, переходы, текст на экране, звуки, музыка и её громкость под голосом.\n" +
                "4. Напиши субтитры с таймкодами (SRT), название, описание и 5–10 хэштегов.\n" +
                "Сохраняй формат кадра. Отвечай по-русски.\n\nРаскадровка:\n" + lines.joinToString("\n") + more
        } else {
            "This is my video mix: $fmt, ${StudioCuts.stamp(totalMs)} long, ${items.size} pieces of other people's clips with my voice-over on top.\n" +
                "Help me edit the final version:\n" +
                "1. Watch the video and listen to the voice-over: where the picture does not match the speech, suggest moving or replacing pieces (timecodes).\n" +
                "2. Suggest a hook for the first 3 seconds and what to cut (pauses, slips, slow parts).\n" +
                "3. Give an edit plan by timecodes: cuts, zooms, transitions, on-screen text, sounds, music and its level under the voice.\n" +
                "4. Write subtitles with timecodes (SRT), a title, a description and 5–10 hashtags.\n" +
                "Keep the frame format.\n\nStoryboard:\n" + lines.joinToString("\n") + more
        }
    }

    // ---------- saved state ----------

    fun toJson(items: List<MixItem>): String = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("clip", it.clipId).put("from", it.fromMs).put("len", it.lenMs).put("on", it.marked).apply {
                it.scheme?.let { s -> put("scheme", StudioScheme.toJson(s)) }
                if (it.locked) put("lock", true)
                if (it.dup > 0) put("dup", it.dup)
            })
        }
    }.toString()

    fun fromJson(s: String): List<MixItem> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("clip").trim()
            val len = o.optLong("len")
            if (id.isEmpty() || len <= 0) return@mapNotNull null
            MixItem(id, o.optLong("from").coerceAtLeast(0), len, o.optBoolean("on"), o.optJSONObject("scheme")?.let { StudioScheme.fromJson(it) },
                o.optBoolean("lock"), o.optInt("dup").coerceAtLeast(0))
        }.let { unique(it) }
    }.getOrDefault(emptyList())

    fun wordsJson(words: List<SWord>): String = JSONArray().apply {
        words.forEach { put(JSONArray().put(it.text).put(it.startMs).put(it.endMs)) }
    }.toString()

    fun wordsFromJson(s: String): List<SWord> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val w = a.optJSONArray(i) ?: return@mapNotNull null
            SWord(w.optString(0), w.optLong(1), w.optLong(2)).takeIf { it.text.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    // ---------- voice-over script ----------

    /**
     * Shots of the mix for the AI writing the voice-over: neighbour pieces of one clip are one shot,
     * "N. m:ss–m:ss what is seen" per line (the note of the clip or its title).
     */
    fun scriptShots(items: List<MixItem>, notes: Map<String, String>): String {
        val sb = StringBuilder()
        var n = 0
        var at = 0L
        var i = 0
        while (i < items.size) {
            val id = items[i].clipId
            var len = 0L
            while (i < items.size && items[i].clipId == id) { len += items[i].lenMs; i++ }
            n++
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(n).append(". ").append(StudioCuts.stamp(at)).append('–').append(StudioCuts.stamp(at + len)).append(' ')
                .append(notes[id].orEmpty().replace(Regex("\\s+"), " ").trim().take(160).ifEmpty { "—" })
            at += len
        }
        return sb.toString()
    }

    /** Text to read aloud from an AI answer: no code fences, markdown, headings, timecodes or [remarks]. */
    fun cleanScript(answer: String): String = answer.replace(Regex("```[a-zA-Z]*"), "").lines().map { l ->
        l.replace(Regex("\\*\\*|__|^#+\\s*|^\\s*[-•*]\\s+"), "")
            .replace(Regex("^\\s*\\(?\\d{1,2}:\\d{2}(\\s*[-–—]\\s*\\d{1,2}:\\d{2})?\\)?\\s*[:.\\-–—]?\\s*"), "")
            .replace(Regex("\\[[^\\]]*]"), "")
            .replace(Regex("\\s+"), " ").trim()
    }.dropWhile { it.isEmpty() || Regex("^(текст|text|озвучка|voice-?over|script|сценарий)[^:]{0,20}:$", RegexOption.IGNORE_CASE).matches(it) }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().removeSurrounding("\"").removeSurrounding("«", "»").trim()

    /** Words of the script for the teleprompter, by paragraph (a line is a paragraph). */
    fun scriptLines(script: String): List<List<String>> = script.lines().map { Studio.tokens(it) }.filter { it.isNotEmpty() }
}

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
}

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
 * A piece of the base video on the main track of the editor, like a clip in CapCut. Source times in ms;
 * it lasts (to - from) / speed on the output, or [holdMs] when it is a freeze frame of the moment [fromMs].
 */
data class VClip(
    val id: String,
    val fromMs: Long,
    val toMs: Long,
    val speed: Float = 1f,
    /** 0 = no sound, 1 = as recorded, up to 2. */
    val volume: Float = 1f,
    val mirror: Boolean = false,
    /** 1..3: the frame enlarged around its centre (the edges are cut off). */
    val zoom: Float = 1f,
    /** > 0: a still picture of [fromMs] for this long. */
    val holdMs: Long = 0
) {
    val isHold: Boolean get() = holdMs > 0
    val outMs: Long get() = if (holdMs > 0) holdMs else max(1L, ((toMs - fromMs) / speed.toDouble()).roundToLong())
    val looksPlain: Boolean get() = speed == 1f && volume == 1f && !mirror && zoom == 1f && holdMs == 0L
}

/**
 * The main track of the Studio editor (protocol lumen.studioedit, key "clips"): the base video as a list of
 * pieces that can be split, deleted, duplicated, put in another order, sped up or slowed down, made quieter,
 * mirrored, zoomed and frozen; output time ↔ source time; blocks and words of the voice follow their pieces.
 * Pure, no Android.
 */
object EditTrack {
    const val MIN_MS = 100L
    const val HOLD_MS = 2000L
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    val VOLUMES = listOf(0, 25, 50, 75, 100, 125, 150, 200)
    val ZOOMS = listOf(100, 110, 125, 150, 175, 200, 250)

    fun initial(durMs: Long): List<VClip> = listOf(VClip("c0", 0, max(1L, durMs)))

    /** The track of [p]: its pieces, or the whole video when nothing was cut. */
    fun of(p: EProject): List<VClip> = p.clips.ifEmpty { initial(p.durMs) }

    /** The whole video untouched: export copies it as before (sound without re-encoding). */
    fun plain(clips: List<VClip>, durMs: Long): Boolean =
        clips.size == 1 && clips[0].let { it.fromMs <= 0 && it.toMs >= durMs - 1 && it.looksPlain }

    fun total(clips: List<VClip>): Long = max(1L, clips.sumOf { it.outMs })

    fun starts(clips: List<VClip>): LongArray {
        val a = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c ->
            a[i] = t
            t += c.outMs
        }
        return a
    }

    /** Where output time falls: piece [index], [offMs] into it, the source moment [srcMs]. */
    data class Pos(val index: Int, val offMs: Long, val srcMs: Long)

    fun locate(clips: List<VClip>, outMs: Long): Pos {
        if (clips.isEmpty()) return Pos(0, 0, 0)
        var start = 0L
        clips.forEachIndexed { i, c ->
            if (outMs < start + c.outMs || i == clips.lastIndex) {
                val off = (outMs - start).coerceIn(0, max(0L, c.outMs - 1))
                val src = if (c.isHold) c.fromMs else min(c.toMs - 1, c.fromMs + (off * c.speed.toDouble()).roundToLong()).coerceAtLeast(c.fromMs)
                return Pos(i, off, src)
            }
            start += c.outMs
        }
        return Pos(clips.lastIndex, 0, clips.last().fromMs)
    }

    /** Output time of source moment [srcMs] inside piece [index]. */
    fun outOf(clips: List<VClip>, index: Int, srcMs: Long): Long {
        val c = clips[index]
        val start = starts(clips)[index]
        if (c.isHold) return start
        return start + ((srcMs - c.fromMs).coerceIn(0, c.toMs - c.fromMs) / c.speed.toDouble()).roundToLong().coerceAtMost(c.outMs)
    }

    /** A free id like "c7". */
    fun newId(clips: List<VClip>, extra: Int = 0): String =
        "c" + ((clips.mapNotNull { it.id.removePrefix("c").toIntOrNull() }.maxOrNull() ?: 0) + 1 + extra)

    /** Cuts the piece under [outMs] in two (scissors); null at its very edge. Returns the track and the right piece. */
    fun split(clips: List<VClip>, outMs: Long): Pair<List<VClip>, Int>? {
        val pos = locate(clips, outMs)
        val c = clips.getOrNull(pos.index) ?: return null
        if (pos.offMs < MIN_MS || c.outMs - pos.offMs < MIN_MS) return null
        val nid = newId(clips)
        val (a, b) = if (c.isHold) c.copy(holdMs = pos.offMs) to c.copy(id = nid, holdMs = c.holdMs - pos.offMs)
        else c.copy(toMs = pos.srcMs) to c.copy(id = nid, fromMs = pos.srcMs)
        val list = clips.toMutableList()
        list[pos.index] = a
        list.add(pos.index + 1, b)
        return list to pos.index + 1
    }

    /** Without piece [index]; null when it is the only one left. */
    fun remove(clips: List<VClip>, index: Int): List<VClip>? =
        if (clips.size <= 1 || index !in clips.indices) null else clips.filterIndexed { i, _ -> i != index }

    /** A copy of piece [index] right after it. */
    fun duplicate(clips: List<VClip>, index: Int): Pair<List<VClip>, Int>? {
        val c = clips.getOrNull(index) ?: return null
        val list = clips.toMutableList()
        list.add(index + 1, c.copy(id = newId(clips)))
        return list to index + 1
    }

    /** Piece [index] one place earlier ([dir] -1) or later (+1); null at the end of the track. */
    fun move(clips: List<VClip>, index: Int, dir: Int): Pair<List<VClip>, Int>? {
        val j = index + dir
        if (index !in clips.indices || j !in clips.indices) return null
        val list = clips.toMutableList()
        val c = list.removeAt(index)
        list.add(j, c)
        return list to j
    }

    /** Piece [from] dragged to place [to] (its index after the move); null when nothing changes. */
    fun moveTo(clips: List<VClip>, from: Int, to: Int): Pair<List<VClip>, Int>? {
        if (from !in clips.indices || to !in clips.indices || from == to) return null
        val list = clips.toMutableList()
        list.add(to, list.removeAt(from))
        return list to to
    }

    /** Where a piece dragged from [from] lands when dropped at seam [seam] (0 = before the first piece, size = after the last). */
    fun dropIndex(from: Int, seam: Int): Int = if (seam > from) seam - 1 else seam

    /** A still picture of the moment [outMs] for [holdMs], put in at that moment; returns the track and the still. */
    fun freeze(clips: List<VClip>, outMs: Long, holdMs: Long = HOLD_MS): Pair<List<VClip>, Int>? {
        val pos = locate(clips, outMs)
        val c = clips.getOrNull(pos.index) ?: return null
        if (c.isHold) return null
        val still = VClip(newId(clips), pos.srcMs, pos.srcMs + 1, 1f, 0f, c.mirror, c.zoom, holdMs)
        val list = clips.toMutableList()
        if (pos.offMs < MIN_MS) {
            list.add(pos.index, still)
            return list to pos.index
        }
        if (c.outMs - pos.offMs < MIN_MS) {
            list.add(pos.index + 1, still)
            return list to pos.index + 1
        }
        list[pos.index] = c.copy(toMs = pos.srcMs)
        list.add(pos.index + 1, still)
        list.add(pos.index + 2, c.copy(id = newId(clips, 1), fromMs = pos.srcMs))
        return list to pos.index + 1
    }

    /** Piece [index] starts at the playhead [outMs] (the part before it is cut off); null when the playhead is not inside it. */
    fun trimStart(clips: List<VClip>, index: Int, outMs: Long): List<VClip>? {
        val c = clips.getOrNull(index) ?: return null
        val pos = locate(clips, outMs)
        if (pos.index != index || c.outMs - pos.offMs < MIN_MS || pos.offMs <= 0) return null
        val n = if (c.isHold) c.copy(holdMs = c.holdMs - pos.offMs) else c.copy(fromMs = pos.srcMs)
        return clips.toMutableList().also { it[index] = n }
    }

    /** Piece [index] ends at the playhead [outMs]. */
    fun trimEnd(clips: List<VClip>, index: Int, outMs: Long): List<VClip>? {
        val c = clips.getOrNull(index) ?: return null
        val pos = locate(clips, outMs)
        if (pos.index != index || pos.offMs < MIN_MS) return null
        val n = if (c.isHold) c.copy(holdMs = pos.offMs) else c.copy(toMs = pos.srcMs)
        return clips.toMutableList().also { it[index] = n }
    }

    fun update(clips: List<VClip>, index: Int, f: (VClip) -> VClip): List<VClip> =
        clips.mapIndexed { i, c -> if (i == index) clean(f(c)) else c }

    /** Values kept in range. */
    fun clean(c: VClip): VClip = c.copy(
        speed = c.speed.coerceIn(MIN_SPEED, MAX_SPEED),
        volume = c.volume.coerceIn(0f, 2f),
        zoom = c.zoom.coerceIn(1f, 3f),
        toMs = max(c.toMs, c.fromMs + 1),
        holdMs = max(0L, c.holdMs)
    )

    /**
     * Output time [outMs] of the old track [old] on the new track [new]: the same moment of the video stays under
     * the same picture (after a cut the blocks behind it move left, after a speed change they stretch).
     * [end]: [outMs] is the end of something, it stays with the piece before it.
     */
    fun remap(old: List<VClip>, new: List<VClip>, outMs: Long, end: Boolean = false): Long {
        if (old.isEmpty() || new.isEmpty()) return outMs
        val pos = locate(old, if (end) outMs - 1 else outMs)
        val c = old[pos.index]
        val off = if (end) pos.offMs + 1 else pos.offMs
        val src = if (end && !c.isHold) min(c.toMs, c.fromMs + (off * c.speed.toDouble()).roundToLong()) else pos.srcMs
        fun inside(n: VClip) = !n.isHold && if (end) src > n.fromMs && src <= n.toMs else src >= n.fromMs && src < n.toMs
        val ns = starts(new)
        val same = new.indexOfFirst { it.id == c.id }
        if (same >= 0) {
            val n = new[same]
            if (c.isHold && n.isHold) return ns[same] + min(off, n.holdMs)
            if (inside(n)) return outOf(new, same, src)
        }
        if (!c.isHold) {
            val any = new.indices.firstOrNull { inside(new[it]) }
            if (any != null) return outOf(new, any, src)
        }
        if (same >= 0) {
            val n = new[same]
            return if (!n.isHold && src >= n.toMs) ns[same] + n.outMs else ns[same]
        }
        // the piece is gone: where the next surviving one starts
        for (k in pos.index + 1 until old.size) {
            val j = new.indexOfFirst { it.id == old[k].id }
            if (j >= 0) return ns[j]
        }
        return total(new)
    }

    /** The blocks of [blocks] moved along with their pieces from [old] to [new]. */
    fun remapBlocks(blocks: List<EBlock>, old: List<VClip>, new: List<VClip>): List<EBlock> {
        val tot = total(new)
        return blocks.map { b ->
            val s = remap(old, new, b.startMs)
            val e = remap(old, new, max(b.startMs + 1, b.endMs), end = true)
            val d = if (e - s >= StudioEdit.MIN_MS) e - s else b.durMs
            StudioEdit.clampTime(b.copy(startMs = s, durMs = d), tot)
        }
    }

    /** The words of the voice (source times) in output time; cut-out words are gone, repeated pieces repeat them. */
    fun words(src: List<SWord>, clips: List<VClip>, durMs: Long): List<SWord> {
        if (src.isEmpty() || plain(clips, durMs)) return src
        val out = ArrayList<SWord>()
        val st = starts(clips)
        clips.forEachIndexed { i, c ->
            if (c.isHold) return@forEachIndexed
            val end = st[i] + c.outMs
            for (w in src) {
                if (w.startMs < c.fromMs || w.startMs >= c.toMs) continue
                val s = outOf(clips, i, w.startMs)
                val e = min(end, outOf(clips, i, min(w.endMs, c.toMs)))
                out += SWord(w.text, s, max(s + 1, e))
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** Speed as people say it: "2×", "0.5×". */
    fun speedLabel(s: Float): String {
        val r = (s * 100).roundToLong()
        val txt = if (r % 100 == 0L) (r / 100).toString() else if (r % 10 == 0L) "%.1f".format(java.util.Locale.US, s) else "%.2f".format(java.util.Locale.US, s)
        return "$txt×"
    }

    /** Short marks for the timeline: speed, no sound, still, mirror, zoom. */
    fun badges(c: VClip): String = buildString {
        if (c.isHold) append("❄ ")
        if (!c.isHold && abs(c.speed - 1f) > 0.001f) append(speedLabel(c.speed)).append(' ')
        if (!c.isHold && c.volume == 0f) append("🔇 ")
        if (c.mirror) append("⇋ ")
        if (c.zoom > 1.001f) append("🔍 ")
    }.trim()

    fun json(clips: List<VClip>): JSONArray = JSONArray().apply {
        clips.forEach { c ->
            put(JSONObject().put("id", c.id).put("from", c.fromMs).put("to", c.toMs).put("speed", c.speed.toDouble())
                .put("volume", c.volume.toDouble()).put("mirror", c.mirror).put("zoom", c.zoom.toDouble()).put("hold", c.holdMs))
        }
    }

    fun from(a: JSONArray?): List<VClip> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifBlank { "c$i" }
        val from = o.optLong("from", -1)
        val to = o.optLong("to", -1)
        if (from < 0 || to <= from) return@mapNotNull null
        clean(VClip(id, from, to, o.optDouble("speed", 1.0).toFloat(), o.optDouble("volume", 1.0).toFloat(),
            o.optBoolean("mirror"), o.optDouble("zoom", 1.0).toFloat(), o.optLong("hold")))
    }
}

/** A rectangle: fractions of the frame or pixels. */
data class FRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
}

/**
 * Split screen of the editor (block kind "split", like CapCut): for the time of the block the frame is shared by the
 * main video and a second one ([EBlock.file], from [EBlock.srcMs]); [EBlock.look] is the layout. Pure, no Android.
 */
object EditSplit {
    const val KIND = "split"
    /** Main video left / right / top / bottom; the second one takes the other half. */
    val LAYOUTS = listOf("lr", "rl", "tb", "bt")

    fun defaultLayout(w: Int, h: Int): String = if (h > w) "tb" else "lr"

    /** The halves of the frame (fractions): the main video's and the second one's. */
    fun halves(look: String): Pair<FRect, FRect> {
        val left = FRect(0f, 0f, 0.5f, 1f)
        val right = FRect(0.5f, 0f, 1f, 1f)
        val top = FRect(0f, 0f, 1f, 0.5f)
        val bottom = FRect(0f, 0.5f, 1f, 1f)
        return when (look) {
            "rl" -> right to left
            "tb" -> top to bottom
            "bt" -> bottom to top
            else -> left to right
        }
    }

    /** A video [vw]×[vh] scaled to cover [box] (fractions) of a frame [fw]×[fh], centred: its rectangle in pixels, larger than the box. */
    fun cover(vw: Float, vh: Float, box: FRect, fw: Float, fh: Float): FRect {
        val bw = box.w * fw
        val bh = box.h * fh
        val k = max(bw / max(1f, vw), bh / max(1f, vh))
        val cw = vw * k
        val ch = vh * k
        val cx = (box.l + box.r) / 2 * fw
        val cy = (box.t + box.b) / 2 * fh
        return FRect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
    }

    /** The split screen shown at [tMs] (the top one when several overlap). */
    fun active(blocks: List<EBlock>, tMs: Long): EBlock? = blocks.lastOrNull { it.kind == KIND && tMs >= it.startMs && tMs < it.endMs }

    /** Moment of the second video at output time [tMs]. */
    fun srcAt(b: EBlock, tMs: Long): Long = b.srcMs + (tMs - b.startMs).coerceAtLeast(0)

    /** Split screens whose sound changes the export: the second video heard, or the main one quieter. */
    fun mixes(blocks: List<EBlock>): Boolean =
        blocks.any { it.kind == KIND && it.file.isNotEmpty() && (it.volume > 0f || it.mainVolume != 1f) }

    /** Loudness of the main video at [tMs]: its piece's, times the split screen's setting. */
    fun mainVolume(blocks: List<EBlock>, tMs: Long, clipVolume: Float): Float = clipVolume * (active(blocks, tMs)?.mainVolume ?: 1f)

    /** Blocks drawn over the picture (a split screen is not one of them). */
    fun overlays(blocks: List<EBlock>): List<EBlock> = blocks.filter { it.kind != KIND }
}

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
    val holdMs: Long = 0,
    /** Colour filter ([EditColor.FILTERS], "" = none) and its strength 0..1. */
    val filter: String = "",
    val filterK: Float = 1f,
    /** Adjust sliders, -1..1 each (0 = as recorded). */
    val bright: Float = 0f,
    val contrast: Float = 0f,
    val sat: Float = 0f,
    val warm: Float = 0f,
    /** Transition into this piece from the one before it ([EditColor.TRANSITIONS]); on the first piece — from the start. */
    val trans: String = "",
    val transMs: Long = EditColor.DEFAULT_TRANS_MS,
    /** The video this piece is cut from: "" = the base video of the project, else a file inside the project folder. */
    val src: String = "",
    /** Turned clockwise by 0, 90, 180 or 270 degrees. */
    val rot: Int = 0
) {
    val isHold: Boolean get() = holdMs > 0
    val outMs: Long get() = if (holdMs > 0) holdMs else max(1L, ((toMs - fromMs) / speed.toDouble()).roundToLong())
    val hasColor: Boolean get() = (filter.isNotEmpty() && filterK > 0f) || bright != 0f || contrast != 0f || sat != 0f || warm != 0f
    /** Nothing changed in this piece (its place and length aside). */
    val looksPlain: Boolean get() = speed == 1f && volume == 1f && !mirror && zoom == 1f && holdMs == 0L && !hasColor && trans.isEmpty() && src.isEmpty() && rot == 0
    /** Its sound is as recorded. */
    val soundPlain: Boolean get() = speed == 1f && volume == 1f && holdMs == 0L && src.isEmpty()
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

    /** The sound of the whole video untouched (the picture may have filters or transitions): export copies it. */
    fun soundPlain(clips: List<VClip>, durMs: Long): Boolean =
        clips.size == 1 && clips[0].let { it.fromMs <= 0 && it.toMs >= durMs - 1 && it.soundPlain }

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
        val right = b.copy(trans = "")
        val list = clips.toMutableList()
        list[pos.index] = a
        list.add(pos.index + 1, right)
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
        val still = c.copy(id = newId(clips), fromMs = pos.srcMs, toMs = pos.srcMs + 1, speed = 1f, volume = 0f, holdMs = holdMs, trans = "")
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
        list.add(pos.index + 2, c.copy(id = newId(clips, 1), fromMs = pos.srcMs, trans = ""))
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
        holdMs = max(0L, c.holdMs),
        filter = c.filter.takeIf { it in EditColor.FILTERS } ?: "",
        filterK = c.filterK.coerceIn(0f, 1f),
        bright = c.bright.coerceIn(-1f, 1f),
        contrast = c.contrast.coerceIn(-1f, 1f),
        sat = c.sat.coerceIn(-1f, 1f),
        warm = c.warm.coerceIn(-1f, 1f),
        trans = c.trans.takeIf { it in EditColor.TRANSITIONS } ?: "",
        transMs = c.transMs.coerceIn(100L, 3000L),
        src = safeSrc(c.src),
        rot = ((c.rot / 90 * 90) % 360 + 360) % 360
    )

    /** A file name inside the project folder, or "" (no absolute paths, no way out of the folder). */
    fun safeSrc(s: String): String = s.trim().takeIf { it.isNotEmpty() && !it.startsWith("/") && ".." !in it && '\\' !in it } ?: ""

    /** The distinct videos of the track, the base one ("") first. */
    fun sources(clips: List<VClip>): List<String> = (listOf("") + clips.map { it.src }).distinct()

    /** The track ends where it should for [add]: at [outMs] (cutting the piece there in two) or at the end; returns the track and the new piece. */
    fun insert(clips: List<VClip>, outMs: Long, add: VClip, atEnd: Boolean): Pair<List<VClip>, Int> {
        if (clips.isEmpty()) return listOf(clean(add)) to 0
        val list = clips.toMutableList()
        if (atEnd) {
            list.add(clean(add.copy(id = newId(clips))))
            return list to list.lastIndex
        }
        val pos = locate(clips, outMs)
        val c = clips[pos.index]
        val n = clean(add.copy(id = newId(clips, 1)))
        return when {
            pos.offMs < MIN_MS -> list.apply { add(pos.index, n) } to pos.index
            c.outMs - pos.offMs < MIN_MS || outMs >= total(clips) -> list.apply { add(pos.index + 1, n) } to pos.index + 1
            else -> {
                val (cut, right) = split(clips, outMs) ?: return list.apply { add(pos.index + 1, n) } to pos.index + 1
                cut.toMutableList().apply { add(right, n) } to right
            }
        }
    }

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
        fun inside(n: VClip) = !n.isHold && n.src == c.src && if (end) src > n.fromMs && src <= n.toMs else src >= n.fromMs && src < n.toMs
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
            // music keeps its place in time, like the audio track in CapCut
            if (b.kind == EditMusic.KIND) return@map StudioEdit.clampTime(b, tot)
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
            // the voice belongs to the base video; pieces of other videos have none
            if (c.isHold || c.src.isNotEmpty()) return@forEachIndexed
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
        if (c.hasColor) append("🎨 ")
        if (c.rot != 0) append("⟳ ")
    }.trim()

    fun json(clips: List<VClip>): JSONArray = JSONArray().apply {
        clips.forEach { c ->
            put(JSONObject().put("id", c.id).put("from", c.fromMs).put("to", c.toMs).put("speed", c.speed.toDouble())
                .put("volume", c.volume.toDouble()).put("mirror", c.mirror).put("zoom", c.zoom.toDouble()).put("hold", c.holdMs)
                .apply {
                    if (c.filter.isNotEmpty()) put("filter", c.filter).put("filterK", c.filterK.toDouble())
                    if (c.bright != 0f) put("bright", c.bright.toDouble())
                    if (c.contrast != 0f) put("contrast", c.contrast.toDouble())
                    if (c.sat != 0f) put("sat", c.sat.toDouble())
                    if (c.warm != 0f) put("warm", c.warm.toDouble())
                    if (c.trans.isNotEmpty()) put("trans", c.trans).put("transMs", c.transMs)
                    if (c.src.isNotEmpty()) put("src", c.src)
                    if (c.rot != 0) put("rot", c.rot)
                })
        }
    }

    fun from(a: JSONArray?): List<VClip> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifBlank { "c$i" }
        val from = o.optLong("from", -1)
        val to = o.optLong("to", -1)
        if (from < 0 || to <= from) return@mapNotNull null
        fun f(k: String, d: Double = 0.0) = o.optDouble(k, d).toFloat().takeIf { !it.isNaN() } ?: d.toFloat()
        clean(VClip(id, from, to, f("speed", 1.0), f("volume", 1.0), o.optBoolean("mirror"), f("zoom", 1.0), o.optLong("hold"),
            o.optString("filter"), f("filterK", 1.0), f("bright"), f("contrast"), f("sat"), f("warm"),
            o.optString("trans"), o.optLong("transMs", EditColor.DEFAULT_TRANS_MS), o.optString("src"), o.optInt("rot")))
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
    /** Picture in picture: the main video on the whole frame, the second one in the box of the block (moved and scaled like a picture). */
    const val PIP = "pip"

    fun isPip(b: EBlock): Boolean = b.kind == KIND && b.look == PIP

    /** A new picture-in-picture box: a third of the frame width in the top right corner, the shape of the video. */
    fun pipStart(fw: Int, fh: Int, vw: Int, vh: Int): Triple<Float, Float, Float> {
        val w = 0.34f
        val hFrac = w * fw * (vh.toFloat() / max(1, vw)) / max(1, fh)
        return Triple(1f - w / 2 - 0.04f, (hFrac / 2 + 0.06f).coerceAtMost(0.5f), w)
    }

    /**
     * The main video's and the second one's boxes (fractions of a [fw]×[fh] frame): the halves of the layout, or,
     * for [PIP], the whole frame and the box of the block (its centre [EBlock.x], [EBlock.y], width [EBlock.w],
     * height by [EBlock.ratio]), kept inside the frame.
     */
    fun boxes(b: EBlock, fw: Float, fh: Float): Pair<FRect, FRect> {
        if (b.look != PIP) return halves(b.look)
        val (hw, hh) = StudioEdit.halfSize(b, fw, fh)
        val l = (b.x * fw - hw) / fw
        val t = (b.y * fh - hh) / fh
        val r = (b.x * fw + hw) / fw
        val bt = (b.y * fh + hh) / fh
        return FRect(0f, 0f, 1f, 1f) to FRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), bt.coerceIn(0f, 1f))
    }

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

    /** Blocks on the stage over the picture (a split screen and sounds are not; a picture-in-picture box is, to move it). */
    fun overlays(blocks: List<EBlock>): List<EBlock> = blocks.filter { (it.kind != KIND || it.look == PIP) && it.kind != EditMusic.KIND }
}

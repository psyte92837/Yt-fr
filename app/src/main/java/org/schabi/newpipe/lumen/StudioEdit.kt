/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A block over the finished video in the Studio editor: an info-scheme, a picture, a caption or an emoji.
 * Place: centre [x], [y] and width [w] are fractions of the frame ([w] of its width); [ratio] = height / width
 * in pixels; [rot] — degrees clockwise. Blocks later in the list are drawn on top.
 */
data class EBlock(
    val id: String,
    val kind: String,
    val startMs: Long,
    val durMs: Long,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val w: Float = 0.6f,
    val ratio: Float = 0.6f,
    val rot: Float = 0f,
    val opacity: Float = 1f,
    val animIn: String = "pop",
    val animOut: String = "fade",
    /** Text: plate, shadow, accent, light; picture: plain, round, card, circle; scheme: id of a [SchemeStyle]. */
    val look: String = "",
    val text: String = "",
    /** Picture: file name inside the project folder. */
    val file: String = "",
    val scheme: SScheme? = null,
    /** What was asked from the AI, to ask again. */
    val ask: String = "",
    /** Split screen: the moment of the second video ([file]) shown at [startMs]. */
    val srcMs: Long = 0,
    /** Split screen: loudness of the second video (0..2) and of the main one meanwhile. */
    val volume: Float = 1f,
    val mainVolume: Float = 1f,
    /** Keyframes of place, size, turn and opacity (see [EditKeys]); empty = the block stands still. */
    val keys: List<EKey> = emptyList(),
    /** Animation (kind "anim", see [EditInsert]): frames a second and the loop period (0 = no loop). */
    val fps: Int = EditInsert.DEFAULT_FPS,
    val loopMs: Long = 0,
    /** Mark of the request whose answer the block waits for (lumen.insert v1); a pending block is kept in the file. */
    val req: String = "",
    /** The wizard's brief (JSON of [EditBrief]), to open the same form again. */
    val brief: String = "",
    /** Look over the style (scheme / animation). */
    val over: BlockLook? = null,
    /** Dimming of the video while the block is shown (0..0.6): under the block or, with [dimAll], the whole frame. */
    val dim: Float = 0f,
    val dimAll: Boolean = false,
    /** Up to 3 previous answers (scheme JSON or code), newest first, for «← Прошлая версия». */
    val history: List<String> = emptyList()
) {
    val endMs: Long get() = startMs + durMs
}

/** A keyframe of a block: [atMs] from its start, its place, width, turn and opacity then. */
data class EKey(val atMs: Long, val x: Float, val y: Float, val w: Float, val rot: Float, val opacity: Float)

/** An editor project: the base video (copied into the project folder) and the blocks over it. */
data class EProject(
    val id: String,
    /** Path of the video it was opened from, to find this project again. */
    val source: String,
    val w: Int,
    val h: Int,
    val durMs: Long,
    val blocks: List<EBlock> = emptyList(),
    val created: Long = 0,
    /** Last exported file (path), empty before the first export. */
    val exported: String = "",
    /** Pieces of the main track (see [EditTrack]); empty = the whole video as it is. */
    val clips: List<VClip> = emptyList(),
    /** Shape of the exported frame ([EditCanvas.RATIOS], "" = that of the video). */
    val ratio: String = "",
    /** The video covers the frame (edges cut off) instead of fitting into it on [bg]. */
    val fill: Boolean = false,
    val bg: Int = 0xFF000000.toInt(),
    /** The background of a fitted video is the video itself, enlarged and blurred (instead of [bg]). */
    val blur: Boolean = false,
    /** Style of the video for new schemes and animations (JSON of an [EditBrief] without content), "" = none. */
    val brand: String = ""
)

/** Look of a block at a moment of its in / out animation: opacity, scale and shift (fractions of the frame). */
data class EPose(val alpha: Float, val scale: Float, val dx: Float, val dy: Float)

/**
 * Logic of the Studio editor (protocol lumen.studioedit v1): placing blocks in time (move, trim, split,
 * duplicate, snapping, timeline lanes), on the frame (move, scale, rotate, hit test), their in / out
 * animation, undo history and JSON. Pure, no Android.
 */
object StudioEdit {
    const val PROTOCOL = "lumen.studioedit"
    const val VERSION = 1
    const val MIN_MS = 300L
    const val DEFAULT_MS = 4000L
    const val MIN_W = 0.06f
    const val MAX_W = 2f

    val KINDS = listOf("scheme", "image", "text", "emoji", "split", "audio", EditInsert.KIND)
    val ANIMS = listOf("none", "fade", "pop", "slide", "zoom")
    val TEXT_LOOKS = listOf("plate", "shadow", "accent", "light")
    val IMAGE_LOOKS = listOf("plain", "round", "card", "circle")

    /** Steps of the timeline ruler, ms. */
    private val STEPS = listOf(100L, 200L, 500L, 1000L, 2000L, 5000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    // ---------- ids and lists ----------

    fun nextId(blocks: List<EBlock>): String = "b" + ((blocks.mapNotNull { it.id.removePrefix("b").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    fun find(blocks: List<EBlock>, id: String?): EBlock? = if (id == null) null else blocks.firstOrNull { it.id == id }

    fun replace(blocks: List<EBlock>, b: EBlock): List<EBlock> = blocks.map { if (it.id == b.id) b else it }

    fun remove(blocks: List<EBlock>, id: String): List<EBlock> = blocks.filter { it.id != id }

    /** Moves block [id] [delta] places up (+, drawn later = on top) or down (−) in the drawing order. */
    fun reorder(blocks: List<EBlock>, id: String, delta: Int): List<EBlock> {
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return blocks
        val j = (i + delta).coerceIn(0, blocks.lastIndex)
        if (i == j) return blocks
        val out = blocks.toMutableList()
        val b = out.removeAt(i)
        out.add(j, b)
        return out
    }

    /** Blocks on screen at [tMs], bottom first. */
    fun active(blocks: List<EBlock>, tMs: Long): List<EBlock> = blocks.filter { tMs >= it.startMs && tMs < it.endMs }

    // ---------- time ----------

    /** Keeps the block inside the video: at least [MIN_MS] long, never past the end. */
    fun clampTime(b: EBlock, totalMs: Long): EBlock {
        val total = max(totalMs, MIN_MS)
        val dur = b.durMs.coerceIn(MIN_MS, total)
        return b.copy(startMs = b.startMs.coerceIn(0, total - dur), durMs = dur)
    }

    /**
     * Time of a new block put at the playhead [atMs]: it starts there and lasts [durMs], shortened to the end
     * of the video; when less than a second is left, it starts earlier instead.
     */
    fun placeAt(atMs: Long, totalMs: Long, durMs: Long = DEFAULT_MS): Pair<Long, Long> {
        val total = max(totalMs, MIN_MS)
        val left = total - atMs.coerceIn(0, total)
        val dur = (if (left >= 1000) min(durMs, left) else min(durMs, total)).coerceIn(MIN_MS, total)
        return atMs.coerceIn(0, total - dur) to dur
    }

    fun move(b: EBlock, startMs: Long, totalMs: Long): EBlock = clampTime(b.copy(startMs = startMs), totalMs)

    /** New start, the end stays. */
    fun trimStart(b: EBlock, startMs: Long): EBlock {
        val end = b.endMs
        val s = startMs.coerceIn(0, max(0, end - MIN_MS))
        // the second video of a split screen keeps its picture: its beginning is cut off along with the block
        val src = if (b.kind == "split") max(0L, b.srcMs + s - b.startMs) else b.srcMs
        return b.copy(startMs = s, durMs = end - s, srcMs = src)
    }

    /** New end, the start stays. */
    fun trimEnd(b: EBlock, endMs: Long, totalMs: Long): EBlock {
        val e = endMs.coerceIn(b.startMs + MIN_MS, max(b.startMs + MIN_MS, totalMs))
        return b.copy(durMs = e - b.startMs)
    }

    fun setDuration(b: EBlock, durMs: Long, totalMs: Long): EBlock =
        b.copy(durMs = durMs.coerceIn(MIN_MS, max(MIN_MS, totalMs - b.startMs)))

    /** "Start here": the start moves to [ms] and the end stays; past the end the whole block moves there. */
    fun startAt(b: EBlock, ms: Long, totalMs: Long): EBlock = if (ms <= b.endMs - MIN_MS) trimStart(b, ms) else move(b, ms, totalMs)

    /** "End here": the end moves to [ms] and the start stays; before the start the whole block moves to end there. */
    fun endAt(b: EBlock, ms: Long, totalMs: Long): EBlock = if (ms >= b.startMs + MIN_MS) trimEnd(b, ms, totalMs) else move(b, ms - b.durMs, totalMs)

    /**
     * Cuts block [id] at [atMs] into two; the cut itself has no animation, so the two halves join seamlessly.
     * Null when the cut is outside the block or one half would be shorter than [MIN_MS].
     */
    fun split(blocks: List<EBlock>, id: String, atMs: Long): List<EBlock>? {
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return null
        val b = blocks[i]
        if (atMs < b.startMs + MIN_MS || atMs > b.endMs - MIN_MS) return null
        val left = b.copy(durMs = atMs - b.startMs, animOut = "none")
        val right = b.copy(id = nextId(blocks), startMs = atMs, durMs = b.endMs - atMs, animIn = "none",
            srcMs = if (b.kind == "split") b.srcMs + atMs - b.startMs else b.srcMs)
        return blocks.toMutableList().apply {
            set(i, left)
            add(i + 1, right)
        }
    }

    /** A copy of block [id] right after it (or, when it does not fit, at the same time slightly shifted); with the new id. */
    fun duplicate(blocks: List<EBlock>, id: String, totalMs: Long): Pair<List<EBlock>, String>? {
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return null
        val b = blocks[i]
        val nid = nextId(blocks)
        val copy = if (b.endMs + b.durMs <= totalMs) b.copy(id = nid, startMs = b.endMs)
        else b.copy(id = nid, x = (b.x + 0.04f).coerceAtMost(1f), y = (b.y + 0.04f).coerceAtMost(1f))
        return blocks.toMutableList().apply { add(i + 1, copy) } to nid
    }

    // ---------- snapping on the timeline ----------

    /** Times a dragged edge sticks to: start and end of the video, the playhead, edges of the other blocks. */
    fun snapTargets(blocks: List<EBlock>, exceptId: String?, playheadMs: Long, totalMs: Long, words: List<SWord> = emptyList()): List<Long> =
        (listOf(0L, totalMs, playheadMs) + blocks.filter { it.id != exceptId }.flatMap { listOf(it.startMs, it.endMs) } + words.map { it.startMs }).distinct()

    fun snap(ms: Long, targets: List<Long>, thresholdMs: Long): Long? =
        targets.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) <= thresholdMs }

    /** Start of a block of [durMs] dragged to [startMs] after either edge sticks; second = the target it stuck to. */
    fun snapMove(startMs: Long, durMs: Long, targets: List<Long>, thresholdMs: Long): Pair<Long, Long?> {
        val s = snap(startMs, targets, thresholdMs)
        val e = snap(startMs + durMs, targets, thresholdMs)
        val ds = s?.let { abs(it - startMs) } ?: Long.MAX_VALUE
        val de = e?.let { abs(it - startMs - durMs) } ?: Long.MAX_VALUE
        return when {
            s == null && e == null -> startMs to null
            de < ds -> (e!! - durMs) to e
            else -> s!! to s
        }
    }

    // ---------- timeline lanes ----------

    /** Row of every block on the timeline: blocks that overlap in time go to different rows (first free row). */
    fun lanes(blocks: List<EBlock>): Map<String, Int> {
        val ends = mutableListOf<Long>()
        val out = HashMap<String, Int>()
        blocks.withIndex().sortedWith(compareBy({ it.value.startMs }, { it.index })).forEach { (_, b) ->
            var lane = ends.indexOfFirst { it <= b.startMs }
            if (lane < 0) {
                ends += b.endMs
                lane = ends.lastIndex
            } else ends[lane] = b.endMs
            out[b.id] = lane
        }
        return out
    }

    /** Distance between ruler ticks so that labels are at least [minGapPx] apart. */
    fun tickStep(pxPerMs: Float, minGapPx: Float): Long = STEPS.firstOrNull { it * pxPerMs >= minGapPx } ?: STEPS.last()

    // ---------- the frame: move, scale, rotate, hit test ----------

    fun moveTo(b: EBlock, x: Float, y: Float): EBlock = b.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))

    fun resize(b: EBlock, w: Float): EBlock = b.copy(w = w.coerceIn(MIN_W, MAX_W))

    /** Degrees into (−180, 180]. */
    fun normDeg(d: Float): Float {
        var r = d % 360f
        if (r > 180f) r -= 360f
        if (r <= -180f) r += 360f
        return r
    }

    /** Rotation that sticks to 0, ±90 and 180 within [th] degrees. */
    fun snapDeg(d: Float, th: Float = 4f): Float {
        val n = normDeg(d)
        val k = Math.round(n / 90f) * 90f
        return if (abs(n - k) <= th) normDeg(k) else n
    }

    /** A centre coordinate that sticks to the middle of the frame; second = it stuck (show the guide). */
    fun snapMiddle(v: Float, th: Float = 0.015f): Pair<Float, Boolean> = if (abs(v - 0.5f) <= th) 0.5f to true else v to false

    /** Half width and half height of the block in pixels on a [fw]×[fh] frame. */
    fun halfSize(b: EBlock, fw: Float, fh: Float): Pair<Float, Float> {
        val hw = b.w * fw / 2
        return hw to hw * b.ratio
    }

    /** Is point ([px], [py]) inside the rotated block, with [slop] pixels around it? */
    fun hit(b: EBlock, fw: Float, fh: Float, px: Float, py: Float, slop: Float = 0f): Boolean {
        val (hw, hh) = halfSize(b, fw, fh)
        val a = Math.toRadians(-b.rot.toDouble())
        val dx = px - b.x * fw
        val dy = py - b.y * fh
        val lx = dx * cos(a) - dy * sin(a)
        val ly = dx * sin(a) + dy * cos(a)
        return abs(lx) <= hw + slop && abs(ly) <= hh + slop
    }

    /** Corner [k] of the rotated block in pixels: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
    fun corner(b: EBlock, fw: Float, fh: Float, k: Int): Pair<Float, Float> {
        val (hw, hh) = halfSize(b, fw, fh)
        val lx = if (k == 1 || k == 2) hw else -hw
        val ly = if (k >= 2) hh else -hh
        val a = Math.toRadians(b.rot.toDouble())
        return (b.x * fw + lx * cos(a) - ly * sin(a)).toFloat() to (b.y * fh + lx * sin(a) + ly * cos(a)).toFloat()
    }

    /** The topmost of [shown] blocks under the finger. */
    fun top(shown: List<EBlock>, fw: Float, fh: Float, px: Float, py: Float, slop: Float = 0f): EBlock? =
        shown.lastOrNull { hit(it, fw, fh, px, py, slop) }

    // ---------- animation ----------

    /** Length of the in and out animation: 350 ms, at most a third of the block. */
    fun animMs(b: EBlock): Long = min(350L, b.durMs / 3)

    private fun easeOut(p: Float): Float {
        val r = 1 - p
        return 1 - r * r * r
    }

    private fun backOut(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1
        val q = p - 1
        return 1 + c3 * q * q * q + c1 * q * q
    }

    /** Opacity, scale and shift of block [b] at [tMs] from its in and out animations (opacity of the block itself not included). */
    fun pose(b: EBlock, tMs: Long): EPose {
        val d = animMs(b)
        var alpha = 1f
        var scale = 1f
        var dy = 0f
        fun apply(kind: String, p: Float, out: Boolean) {
            if (p >= 1f) return
            val e = easeOut(p)
            when (kind) {
                "fade" -> alpha *= e
                "pop" -> {
                    alpha *= min(1f, p * 2.5f)
                    scale *= if (out) 0.7f + 0.3f * e else 0.6f + 0.4f * backOut(p)
                }
                "slide" -> {
                    alpha *= e
                    dy += (1 - e) * 0.06f
                }
                "zoom" -> {
                    alpha *= e
                    scale *= 1f + 0.35f * (1 - e)
                }
            }
        }
        if (d > 0) {
            if (b.animIn != "none") apply(b.animIn, ((tMs - b.startMs).toFloat() / d).coerceIn(0f, 1f), false)
            if (b.animOut != "none") apply(b.animOut, ((b.endMs - tMs).toFloat() / d).coerceIn(0f, 1f), true)
        }
        return EPose(alpha.coerceIn(0f, 1f), scale, 0f, dy)
    }

    // ---------- words of the moment ----------

    /** Words spoken from [fromMs] to [toMs] (indices into [words]); empty when none. */
    fun wordRange(words: List<SWord>, fromMs: Long, toMs: Long): IntRange {
        val first = words.indexOfFirst { it.startMs >= fromMs && it.startMs < toMs }
        if (first < 0) return IntRange.EMPTY
        val last = words.indexOfLast { it.startMs >= fromMs && it.startMs < toMs }
        return first..last
    }

    /** When the nodes of a scheme block appear: on their words when spoken inside the block, else one after another. */
    fun schemeTimes(s: SScheme, words: List<SWord>, b: EBlock): List<Long> =
        StudioScheme.reveal(s, words, wordRange(words, b.startMs, b.endMs), b.startMs, b.endMs)

    /** What is said around [atMs]: [beforeMs] before and [afterMs] after. */
    fun momentText(words: List<SWord>, atMs: Long, beforeMs: Long = 4000, afterMs: Long = 8000): String =
        words.filter { it.startMs >= atMs - beforeMs && it.startMs < atMs + afterMs }.joinToString(" ") { it.text }

    /** The same words with their numbers [N] for the AI (so a node can appear on its word) and their range. */
    fun momentNumbered(words: List<SWord>, atMs: Long, beforeMs: Long = 2000, afterMs: Long = 10_000): Pair<String, IntRange> {
        val r = wordRange(words, atMs - beforeMs, atMs + afterMs)
        if (r.isEmpty()) return "" to r
        return r.joinToString(" ") { "[$it]${words[it].text}" } to r
    }

    /** Up to [n] longest words spoken around [atMs] (in spoken order) — a search query for a photo. */
    fun keyWords(words: List<SWord>, atMs: Long, n: Int = 2): String {
        val near = words.filter { it.startMs >= atMs - 3000 && it.startMs < atMs + 5000 }
            .map { it.text.trim { c -> !c.isLetterOrDigit() } }.filter { it.length >= 4 }
        val chosen = near.map { it.lowercase() }.distinct().sortedByDescending { it.length }.take(n).toSet()
        return near.filter { it.lowercase() in chosen }.distinctBy { it.lowercase() }.joinToString(" ")
    }

    // ---------- a scheme without AI ----------

    private fun isIcon(s: String) = s.isNotEmpty() && s.none { it.isLetterOrDigit() } && s.any { Character.isSurrogate(it) || it.code > 0x2000 }

    /**
     * A scheme from lines written by hand: "emoji Label — value" on every line; a first line ending with ":" is
     * the title. [layout] "auto" picks a chain (up to 5 steps) or a hub. Null when nothing fits the protocol.
     */
    fun schemeFromLines(text: String, layout: String = "auto", base: SScheme? = null): SScheme? {
        var lines = text.lines().map { it.trim().trimStart('-', '•', '*', '—').trim() }.filter { it.isNotEmpty() }
        var title = ""
        if (lines.size > 1 && lines.first().endsWith(":")) {
            title = lines.first().removeSuffix(":").trim()
            lines = lines.drop(1)
        }
        lines = lines.take(StudioScheme.MAX_NODES)
        val nodes = lines.mapIndexed { i, l ->
            val first = l.substringBefore(' ')
            val icon = if (isIcon(first)) first else ""
            val rest = (if (icon.isNotEmpty()) l.removePrefix(first) else l).trim()
            val parts = rest.split(Regex("\\s+[—–-]\\s+|:\\s+"), limit = 2)
            SchNode("n${i + 1}", icon, parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
        }
        val n = nodes.size
        if (n < 2) return null
        val lay = layout.takeIf { it in StudioScheme.LAYOUTS && it != "auto" } ?: if (n <= 5) "chain" else "hub"
        val fixed = when (lay) {
            "tree" -> nodes.mapIndexed { i, nd -> if (i == 0) nd else nd.copy(parent = "n1") }
            else -> nodes
        }
        // edited by hand: the words the nodes appear on, the axes and the common part stay
        val kept = if (base == null) fixed else fixed.mapIndexed { i, nd -> base.nodes.getOrNull(i)?.let { nd.copy(at = it.at, accent = it.accent) } ?: nd }
        val s = StudioScheme.repair(SScheme(lay, title.take(40), kept, axes = base?.axes.orEmpty(), center = base?.center.orEmpty()))
        return s.takeIf { StudioScheme.problems(it).isEmpty() }
    }

    /** A scheme as the lines [schemeFromLines] reads (for editing by hand). */
    fun schemeLines(s: SScheme): String = buildString {
        if (s.title.isNotBlank()) append(s.title).append(":\n")
        append(s.nodes.joinToString("\n") { n ->
            listOf(n.icon, n.label).filter { it.isNotBlank() }.joinToString(" ") + if (n.value.isNotBlank()) " — ${n.value}" else ""
        })
    }

    // ---------- new blocks ----------

    /** Width of a new block as a fraction of the frame width. */
    fun defaultWidth(kind: String, portrait: Boolean): Float = when (kind) {
        "scheme" -> if (portrait) 0.92f else 0.8f
        "image" -> if (portrait) 0.8f else 0.5f
        "text" -> if (portrait) 0.86f else 0.62f
        else -> if (portrait) 0.24f else 0.13f
    }

    /** Height / width of a scheme box: like the scheme of a Studio video (portrait 92 %×44 %, wide 80 %×60 % of the frame). */
    fun schemeRatio(fw: Int, fh: Int): Float = if (fw <= fh) (0.44f * fh) / (0.92f * fw) else (0.6f * fh) / (0.8f * fw)

    /** Height / width of a caption box from the length of its text (about 16 characters a line, up to 5 lines). */
    fun textRatio(text: String): Float {
        val lines = text.lines().sumOf { l -> max(1, ceil(l.trim().length / 16.0).toInt()) }.coerceIn(1, 5)
        return 0.08f + 0.11f * lines
    }

    /**
     * Width drag of the side handle ↔ of a caption or scheme: the finger at ([px], [py]) sets the half width along
     * the block's own axis (the centre stays), the height stays, so the shape (ratio) changes and the text reflows.
     */
    fun stretch(b: EBlock, fw: Float, fh: Float, px: Float, py: Float): EBlock {
        val (hw, hh) = halfSize(b, fw, fh)
        val a = Math.toRadians(-b.rot.toDouble())
        val dx = px - b.x * fw
        val dy = py - b.y * fh
        val lx = abs(dx * cos(a) - dy * sin(a)).toFloat()
        val nhw = lx.coerceIn(max(8f, hh * 0.25f), max(hh * 20f, 8f))
        if (abs(nhw - hw) < 0.5f) return b
        val w = (nhw * 2 / fw).coerceIn(MIN_W, MAX_W)
        return b.copy(w = w, ratio = (hh / (w * fw / 2)).coerceIn(0.05f, 20f))
    }

    // ---------- the text of the voice ----------

    /** Start and length of a block covering words [range]: a little before the first, a little after the last. */
    fun spanOf(words: List<SWord>, range: IntRange, totalMs: Long): Pair<Long, Long> {
        val a = words[range.first].startMs - 150
        val e = words[range.last].endMs + 400
        val s = a.coerceIn(0, max(0, totalMs - MIN_MS))
        return s to (min(e, totalMs) - s).coerceAtLeast(MIN_MS)
    }

    /** Words [range] with their numbers [N] for the AI. */
    fun numbered(words: List<SWord>, range: IntRange): String = range.filter { it in words.indices }.joinToString(" ") { "[$it]${words[it].text}" }

    private fun sentenceEnd(w: String) = w.trimEnd('"', '»', ')', '\'', '”').let { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') || it.endsWith('…') }

    /**
     * The voice cut into pieces for automatic schemes: a piece ends on a sentence end or a pause of [pauseMs]
     * once it lasts [minMs], and is cut at [maxMs] anyway. Returns ranges of word indices.
     */
    fun segments(words: List<SWord>, minMs: Long = 4000, maxMs: Long = 14_000, pauseMs: Long = 700): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var first = 0
        for (i in words.indices) {
            val len = words[i].endMs - words[first].startMs
            val gap = words.getOrNull(i + 1)?.let { it.startMs - words[i].endMs } ?: Long.MAX_VALUE
            val end = i == words.lastIndex || len >= maxMs || (len >= minMs && (sentenceEnd(words[i].text) || gap >= pauseMs))
            if (end) {
                out += first..i
                first = i + 1
            }
        }
        // a too short tail joins the piece before it
        if (out.size >= 2) {
            val last = out.last()
            if (words[last.last].endMs - words[last.first].startMs < minMs / 2) {
                out.removeAt(out.lastIndex)
                out[out.lastIndex] = out.last().first..last.last
            }
        }
        return out
    }

    /** Paragraphs of the transcript: a new one after a pause of [pauseMs] (or a shorter one after a sentence end). */
    fun paragraphs(words: List<SWord>, pauseMs: Long = 1200): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var first = 0
        for (i in words.indices) {
            val gap = words.getOrNull(i + 1)?.let { it.startMs - words[i].endMs } ?: Long.MAX_VALUE
            if (i == words.lastIndex || gap >= pauseMs || (gap >= pauseMs / 2 && sentenceEnd(words[i].text) && i - first >= 12)) {
                out += first..i
                first = i + 1
            }
        }
        return out
    }

    // ---------- export ----------

    /**
     * What the layer of blocks looks like at [tMs], as a key: the same key means the same picture, so the export
     * draws and uploads it only when it changes. A block is "moving" during its in / out animation and,
     * for schemes, until [settle] (when the last node has come in).
     */
    fun overlayKey(shown: List<EBlock>, tMs: Long, settle: (EBlock) -> Long): String = shown.joinToString("|") { b ->
        val d = max(animMs(b), if (b.dim > 0f) DIM_MS else 0L)
        val moving = tMs < b.startMs + d || tMs > b.endMs - d || (b.kind == "scheme" && tMs < settle(b)) || b.keys.size > 1
        // an animation is redrawn only when its frame changes, not on every frame of the video
        val frame = if (b.kind == EditInsert.KIND && b.file.contains("/r_")) {
            val local = tMs - b.startMs
            ":f" + (if (b.loopMs > 0) (local % b.loopMs) else local) * b.fps / 1000
        } else ""
        b.id + "@" + b.hashCode() + frame + if (moving) ":$tMs" else ""
    }

    /** The video under a block darkens and brightens again this long ([EBlock.dim]). */
    const val DIM_MS = 300L

    /** Share of the dimming of [b] at [tMs]: rises at its start and falls at its end over [DIM_MS]. */
    fun dimAlpha(b: EBlock, tMs: Long): Float {
        if (tMs < b.startMs || tMs >= b.endMs) return 0f
        val d = min(DIM_MS, b.durMs / 2).coerceAtLeast(1)
        return min(1f, min((tMs - b.startMs).toFloat() / d, (b.endMs - tMs).toFloat() / d)).coerceIn(0f, 1f)
    }

    /** Time left of a job [fraction] done after [elapsedMs]; null while too early to tell. */
    fun eta(elapsedMs: Long, fraction: Float): Long? =
        if (fraction < 0.03f || fraction >= 1f || elapsedMs < 1500) null else (elapsedMs * (1 - fraction) / fraction).toLong()

    /** Size of the exported video: that of the original, even, its long side at most [maxSide]. */
    fun outSize(w: Int, h: Int, maxSide: Int = 1920): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1080 to 1920
        val k = min(1f, maxSide.toFloat() / max(w, h))
        return max(2, (w * k).toInt() / 2 * 2) to max(2, (h * k).toInt() / 2 * 2)
    }

    /** Width of a block of shape [ratio] narrowed so that its height stays within [maxH] of a [fw]×[fh] frame. */
    fun fitWidth(w: Float, ratio: Float, fw: Int, fh: Int, maxH: Float = 0.8f): Float {
        if (fw <= 0 || fh <= 0 || ratio <= 0f) return w
        val hFrac = w * fw * ratio / fh
        return (if (hFrac > maxH) w * maxH / hFrac else w).coerceIn(MIN_W, MAX_W)
    }

    // ---------- labels ----------

    /** "1:05.3" (minutes, seconds, tenths). */
    fun clock(ms: Long): String {
        val t = max(0L, ms) / 100
        val s = t / 10
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0') + "." + (t % 10)
    }

    /** "2.4" seconds. */
    fun secs(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

    /** A moment typed by the user ("12.5", "12,5", "0:12.5", "4 с") → ms, at least 0; null when not a time. */
    fun parseTime(s: String): Long? {
        val t = s.trim().lowercase().replace(',', '.').trimEnd('s', 'с', ' ', '.')
        if (t.isEmpty()) return null
        val sec = if (':' in t) {
            val m = t.substringBefore(':').toLongOrNull() ?: return null
            val r = t.substringAfter(':').toDoubleOrNull() ?: return null
            m * 60 + r
        } else t.toDoubleOrNull() ?: return null
        if (sec.isNaN() || sec.isInfinite()) return null
        return max(0L, (sec * 1000).toLong())
    }

    /** A length typed by the user → ms within 0.3 s … 10 min; null when not a number. */
    fun parseSeconds(s: String): Long? = parseTime(s)?.coerceIn(MIN_MS, 600_000L)

    // ---------- AI answers ----------

    /** One scheme from an AI answer: a scheme object, or the first of {"schemes":[…]}; code fences allowed. */
    fun parseScheme(reply: String): SScheme? {
        val body = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: body.indexOf('{').takeIf { it >= 0 }?.let { a -> runCatching { JSONObject(body.substring(a, body.lastIndexOf('}') + 1)) }.getOrNull() }
            ?: return null
        val one = o.optJSONArray("schemes")?.optJSONObject(0) ?: o.optJSONObject("scheme") ?: o
        return StudioScheme.fromJson(one)
    }

    /** A string field (and its variants) of a small AI answer {"text":"…","variants":[…]}. */
    fun parseField(reply: String, field: String, variants: String = "variants"): List<String> {
        val body = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(body.substring(body.indexOf('{').coerceAtLeast(0), body.lastIndexOf('}') + 1)) }.getOrNull()
            ?: return listOfNotNull(body.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"')?.takeIf { it.length in 1..80 })
        val main = o.optString(field).trim()
        val alt = o.optJSONArray(variants)?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
        return (listOf(main) + alt).filter { it.isNotEmpty() }.distinct()
    }

    // ---------- JSON ----------

    fun blockJson(b: EBlock): JSONObject = JSONObject()
        .put("id", b.id).put("kind", b.kind).put("start", b.startMs).put("dur", b.durMs)
        .put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("w", b.w.toDouble()).put("ratio", b.ratio.toDouble())
        .put("rot", b.rot.toDouble()).put("opacity", b.opacity.toDouble()).put("in", b.animIn).put("out", b.animOut)
        .apply {
            if (b.look.isNotEmpty()) put("look", b.look)
            if (b.text.isNotEmpty()) put("text", b.text)
            if (b.file.isNotEmpty()) put("file", b.file)
            b.scheme?.let { put("scheme", StudioScheme.toJson(it)) }
            if (b.ask.isNotEmpty()) put("ask", b.ask)
            if (b.srcMs > 0) put("src", b.srcMs)
            if (b.kind == "split" || b.kind == "audio") put("vol", b.volume.toDouble()).put("mainVol", b.mainVolume.toDouble())
            if (b.keys.isNotEmpty()) put("keys", EditKeys.json(b.keys))
            if (b.kind == EditInsert.KIND) put("fps", b.fps)
            if (b.loopMs > 0) put("loop", b.loopMs)
            if (b.req.isNotEmpty()) put("req", b.req)
            if (b.brief.isNotEmpty()) put("brief", b.brief)
            b.over?.let { put("over", EditInsert.lookJson(it)) }
            if (b.dim > 0f) put("dim", b.dim.toDouble())
            if (b.dimAll) put("dimAll", true)
            if (b.history.isNotEmpty()) put("history", JSONArray(b.history.take(3)))
        }

    fun blockFrom(o: JSONObject): EBlock? {
        val kind = o.optString("kind")
        if (kind !in KINDS) return null
        val scheme = o.optJSONObject("scheme")?.let { StudioScheme.fromJson(it) }
        val req = o.optString("req").trim()
        // a scheme or an animation without content is kept only while it waits for an answer
        if (kind == "scheme" && scheme == null && req.isEmpty()) return null
        if (kind == EditInsert.KIND && o.optString("file").isEmpty() && req.isEmpty()) return null
        fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat().takeIf { !it.isNaN() } ?: d
        return EBlock(
            o.optString("id").ifEmpty { return null }, kind, max(0L, o.optLong("start")), max(MIN_MS, o.optLong("dur", DEFAULT_MS)),
            f("x", 0.5f), f("y", 0.5f), f("w", 0.6f).coerceIn(MIN_W, MAX_W), f("ratio", 0.6f).coerceIn(0.05f, 20f), f("rot", 0f), f("opacity", 1f).coerceIn(0f, 1f),
            o.optString("in", "pop").takeIf { it in ANIMS } ?: "pop", o.optString("out", "fade").takeIf { it in ANIMS } ?: "fade",
            o.optString("look"), o.optString("text"), o.optString("file"), scheme, o.optString("ask"), max(0L, o.optLong("src")),
            f("vol", 1f).coerceIn(0f, 2f), f("mainVol", 1f).coerceIn(0f, 2f), EditKeys.from(o.optJSONArray("keys")),
            o.optInt("fps", EditInsert.DEFAULT_FPS).coerceIn(1, 60), o.optLong("loop").coerceIn(0L, EditInsert.MAX_ANIM_MS), req,
            o.optString("brief"), EditInsert.lookFrom(o.optJSONObject("over")), f("dim", 0f).coerceIn(0f, 0.6f), o.optBoolean("dimAll"),
            o.optJSONArray("history")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(3) }.orEmpty()
        )
    }

    fun blocksJson(blocks: List<EBlock>): JSONArray = JSONArray().apply { blocks.forEach { put(blockJson(it)) } }

    fun blocksFrom(a: JSONArray?): List<EBlock> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { blockFrom(it) } }

    fun toJson(p: EProject): JSONObject = JSONObject()
        .put("protocol", PROTOCOL).put("version", VERSION)
        .put("id", p.id).put("source", p.source).put("w", p.w).put("h", p.h).put("dur", p.durMs)
        .put("created", p.created).put("exported", p.exported)
        .put("blocks", blocksJson(p.blocks))
        .apply {
            if (p.clips.isNotEmpty()) put("clips", EditTrack.json(p.clips))
            if (p.ratio.isNotEmpty()) put("ratio", p.ratio).put("fill", p.fill).put("bg", p.bg).put("blur", p.blur)
            if (p.brand.isNotEmpty()) put("brand", p.brand)
        }

    fun fromJson(s: String): EProject? = runCatching {
        val o = JSONObject(s)
        if (o.optString("protocol") != PROTOCOL) return null
        val w = o.optInt("w")
        val h = o.optInt("h")
        if (w <= 0 || h <= 0) return null
        EProject(o.getString("id"), o.optString("source"), w, h, o.optLong("dur"), blocksFrom(o.optJSONArray("blocks")), o.optLong("created"), o.optString("exported"),
            EditTrack.from(o.optJSONArray("clips")), o.optString("ratio").takeIf { it in EditCanvas.RATIOS } ?: "", o.optBoolean("fill"),
            o.optInt("bg", 0xFF000000.toInt()) or 0xFF000000.toInt(), o.optBoolean("blur"), o.optString("brand"))
    }.getOrNull()
}

/** Undo / redo of the editor: snapshots of the blocks (JSON strings), at most [cap]. */
class EditHistory(private val cap: Int = 60) {
    private val back = ArrayDeque<String>()
    private val ahead = ArrayDeque<String>()

    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = ahead.isNotEmpty()

    /** Remembers [state] before a change; a new change clears redo. */
    fun push(state: String) {
        if (back.lastOrNull() == state) return
        back.addLast(state)
        while (back.size > cap) back.removeFirst()
        ahead.clear()
    }

    /** The state to go back to ([current] goes to redo); null when there is nothing to undo. */
    fun undo(current: String): String? {
        val s = back.removeLastOrNull() ?: return null
        ahead.addLast(current)
        return s
    }

    fun redo(current: String): String? {
        val s = ahead.removeLastOrNull() ?: return null
        back.addLast(current)
        return s
    }

    fun clear() {
        back.clear()
        ahead.clear()
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.max
import kotlin.math.min

/**
 * Effect layers of the editor (like the effect tracks of CapCut): a block of kind [KIND] on its own track right under
 * the main video track, dragged and stretched or shrunk by its edges like any block. It changes the main video while it
 * lasts: a sound effect ([EditSound], look "sfx:<id>"), a colour filter ([EditColor.FILTERS], look "flt:<id>") or the
 * colour sliders (look "adj:bright,contrast,sat,warm"). [EBlock.opacity] is the strength. Layers stack over the
 * piece's own settings and over each other in list order. Pure, no Android.
 */
object EditFx {
    const val KIND = "fx"
    const val SOUND = "sfx:"
    const val FILTER = "flt:"
    const val ADJUST = "adj:"
    /** Length of a new layer at the playhead. */
    const val DEFAULT_MS = 3000L
    /** Sound layers fade in and out this fast (no clicks at their edges). */
    const val RAMP_MS = 30L

    enum class Type { SOUND, FILTER, ADJUST }

    data class Adjust(val bright: Float = 0f, val contrast: Float = 0f, val sat: Float = 0f, val warm: Float = 0f) {
        val isZero: Boolean get() = bright == 0f && contrast == 0f && sat == 0f && warm == 0f
    }

    fun isFx(b: EBlock): Boolean = b.kind == KIND

    fun type(b: EBlock): Type? = when {
        b.kind != KIND -> null
        b.look.startsWith(SOUND) -> Type.SOUND
        b.look.startsWith(FILTER) -> Type.FILTER
        b.look.startsWith(ADJUST) -> Type.ADJUST
        else -> null
    }

    fun soundId(b: EBlock): String = if (type(b) == Type.SOUND) b.look.removePrefix(SOUND) else ""

    fun filterId(b: EBlock): String = if (type(b) == Type.FILTER) b.look.removePrefix(FILTER) else ""

    fun adjust(b: EBlock): Adjust {
        if (type(b) != Type.ADJUST) return Adjust()
        val v = b.look.removePrefix(ADJUST).split(',').map { it.trim().toFloatOrNull()?.takeIf { f -> !f.isNaN() }?.coerceIn(-1f, 1f) ?: 0f }
        return Adjust(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f }, v.getOrElse(3) { 0f })
    }

    fun soundLook(id: String): String = SOUND + id
    fun filterLook(id: String): String = FILTER + id
    fun adjustLook(a: Adjust): String = ADJUST + listOf(a.bright, a.contrast, a.sat, a.warm).joinToString(",") { r2(it) }

    private fun r2(f: Float): String = (Math.round(f * 100) / 100f).toString().removeSuffix(".0")

    /** A layer that changes nothing (no effect chosen, zero strength, sliders at zero). */
    fun isEmpty(b: EBlock): Boolean = b.opacity <= 0f || when (type(b)) {
        Type.SOUND -> !EditSound.isKnown(soundId(b))
        Type.FILTER -> filterId(b).isEmpty() || filterId(b) !in EditColor.FILTERS
        Type.ADJUST -> adjust(b).isZero
        null -> true
    }

    /** A new layer [look] from [startMs], [durMs] long, kept inside a video of [totalMs]. */
    fun make(id: String, look: String, startMs: Long, durMs: Long, totalMs: Long, strength: Float = 1f): EBlock {
        val len = max(StudioEdit.MIN_MS, min(durMs, max(StudioEdit.MIN_MS, totalMs)))
        val s = startMs.coerceIn(0L, max(0L, totalMs - len))
        return EBlock(id, KIND, s, len, x = 0.5f, y = 0.5f, w = 1f, opacity = strength.coerceIn(0f, 1f), animIn = "none", animOut = "none", look = look)
    }

    /** Where a new layer at the playhead goes: [DEFAULT_MS] from [atMs], moved back near the end. */
    fun spanAt(atMs: Long, totalMs: Long, lenMs: Long = DEFAULT_MS): Pair<Long, Long> {
        val len = max(StudioEdit.MIN_MS, min(lenMs, totalMs))
        return atMs.coerceIn(0L, max(0L, totalMs - len)) to len
    }

    /** Effect layers lasting at [tMs], in list order. */
    fun active(blocks: List<EBlock>, tMs: Long): List<EBlock> = blocks.filter { it.kind == KIND && tMs >= it.startMs && tMs < it.endMs && !isEmpty(it) }

    // ---------- colour ----------

    fun isColor(b: EBlock): Boolean = type(b).let { it == Type.FILTER || it == Type.ADJUST } && !isEmpty(b)

    /** The colour of layer [b] as a piece setting (strength included), for [EditColor.matrix]. */
    fun colorClip(b: EBlock): VClip? = when (type(b)) {
        Type.FILTER -> VClip("fx", 0, 1, filter = filterId(b), filterK = b.opacity.coerceIn(0f, 1f))
        Type.ADJUST -> adjust(b).let { a -> val k = b.opacity.coerceIn(0f, 1f); VClip("fx", 0, 1, bright = a.bright * k, contrast = a.contrast * k, sat = a.sat * k, warm = a.warm * k) }
        else -> null
    }

    /** The colour at [tMs]: piece [clip]'s own, then the colour layers lasting then; null when the picture is unchanged. */
    fun matrix(clip: VClip?, blocks: List<EBlock>, tMs: Long): FloatArray? {
        var m: FloatArray? = clip?.let { EditColor.matrix(it) }
        for (b in active(blocks, tMs)) {
            val lm = colorClip(b)?.let { EditColor.matrix(it) } ?: continue
            m = if (m == null) lm else EditColor.concat(lm, m)
        }
        return m
    }

    /** What the colour layers at [tMs] are: the picture is recoloured only when this changes. */
    fun colorKey(blocks: List<EBlock>, tMs: Long): String =
        active(blocks, tMs).filter { isColor(it) }.joinToString("|") { it.id + ":" + it.look + ":" + it.opacity }

    fun colors(blocks: List<EBlock>): Boolean = blocks.any { isColor(it) }

    // ---------- sound ----------

    fun isSound(b: EBlock): Boolean = type(b) == Type.SOUND && !isEmpty(b)

    fun sounds(blocks: List<EBlock>): List<EBlock> = blocks.filter { isSound(it) }

    /** The sound effect heard at [tMs] (the last layer when several overlap) and its strength. */
    fun soundAt(blocks: List<EBlock>, tMs: Long): Pair<String, Float>? =
        active(blocks, tMs).lastOrNull { isSound(it) }?.let { soundId(it) to it.opacity }

    /** Sound layers change the export. */
    fun mixes(blocks: List<EBlock>): Boolean = blocks.any { isSound(it) }

    /** Weight of layer [b] at [tMs]: its strength with short ramps at both edges. */
    fun weight(b: EBlock, tMs: Long): Float {
        if (tMs < b.startMs || tMs >= b.endMs) return 0f
        val edge = min(tMs - b.startMs, b.endMs - tMs).toFloat()
        return b.opacity.coerceIn(0f, 1f) * (edge / RAMP_MS).coerceIn(0f, 1f)
    }

    // ---------- names ----------

    fun name(b: EBlock): String = when (type(b)) {
        Type.SOUND -> EditSound.find(soundId(b))?.let { "${it.emoji} ${it.name}" } ?: t("🎚 Звук-эффект", "🎚 Sound effect")
        Type.FILTER -> "🌈 " + (filterId(b).takeIf { it.isNotEmpty() }?.let { EditColor.filterName(it) } ?: t("Фильтр", "Filter"))
        Type.ADJUST -> "☀ " + t("Настройка", "Adjust")
        null -> "✨ " + t("Эффект", "Effect")
    } + if (b.opacity < 0.995f) " ${Math.round(b.opacity * 100)}%" else ""

    // ---------- tracks ----------

    /** Effect layers first: they get the tracks right under the video, the other blocks the ones below them. */
    fun first(blocks: List<EBlock>): List<EBlock> = blocks.filter { it.kind == KIND } + blocks.filter { it.kind != KIND }

    // ---------- older projects ----------

    /**
     * Settings kept on the pieces themselves (an older project: filter, sliders, sound effect) moved onto effect layers
     * over the same pieces, so every effect is seen on the timeline. Null when there is nothing to move.
     */
    fun upgrade(p: EProject): EProject? {
        val clips = EditTrack.of(p)
        if (clips.none { it.hasColor || it.sfx.isNotEmpty() }) return null
        val starts = EditTrack.starts(clips)
        var list = p.blocks
        val tot = EditTrack.total(clips)
        val out = clips.mapIndexed { i, c ->
            if (!c.hasColor && c.sfx.isEmpty()) return@mapIndexed c
            val st = starts[i]
            val len = c.outMs
            fun add(look: String, k: Float) {
                list = list + make(StudioEdit.nextId(list), look, st, len, tot, k)
            }
            if (c.filter.isNotEmpty() && c.filterK > 0f) add(filterLook(c.filter), c.filterK)
            val a = Adjust(c.bright, c.contrast, c.sat, c.warm)
            if (!a.isZero) add(adjustLook(a), 1f)
            if (c.sfx.isNotEmpty() && !c.isHold) add(soundLook(c.sfx), c.sfxK)
            c.copy(filter = "", filterK = 1f, bright = 0f, contrast = 0f, sat = 0f, warm = 0f, sfx = "", sfxK = 1f)
        }
        val keep = if (EditTrack.plain(out, p.durMs)) emptyList() else out
        return p.copy(clips = keep, blocks = list)
    }
}

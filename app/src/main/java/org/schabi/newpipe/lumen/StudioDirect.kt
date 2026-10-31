/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What Lumen Studio makes. SHORTS: vertical 9:16 from a script; VIDEO: horizontal 16:9 YouTube video from a script;
 * VOICE: an existing voice-over (audio file or microphone) turned into an animated video of any shape.
 */
enum class StudioTask(val id: String) {
    SHORTS("shorts"), VIDEO("video"), VOICE("voice");

    companion object {
        fun of(id: String): StudioTask = entries.firstOrNull { it.id == id } ?: SHORTS
    }
}

/** Shape of the frame. */
enum class StudioAspect(val id: String, val label: String) {
    PORTRAIT("9:16", "9:16"), LANDSCAPE("16:9", "16:9"), SQUARE("1:1", "1:1");

    /** Frame size for the short side [short] (720 or 1080); both sides are even. */
    fun size(short: Int): Pair<Int, Int> {
        val s = short / 2 * 2
        val long = (s * 16 / 9) / 2 * 2
        return when (this) {
            PORTRAIT -> s to long
            LANDSCAPE -> long to s
            SQUARE -> s to s
        }
    }

    companion object {
        fun of(id: String): StudioAspect = entries.firstOrNull { it.id == id } ?: PORTRAIT
    }
}

/** Easing curves of the animations, x in 0..1. */
object StudioMotion {
    fun clamp(x: Float) = x.coerceIn(0f, 1f)

    /** Slow start and end. */
    fun inOut(x0: Float): Float {
        val x = clamp(x0)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    /** Overshoots a little and settles ("back"). */
    fun backOut(x0: Float, s: Float = 1.7f): Float {
        val x = clamp(x0) - 1
        return 1 + (s + 1) * x * x * x + s * x * x
    }

    /** Damped spring: overshoot, a small wobble, rest at 1. */
    fun spring(x0: Float): Float {
        val x = clamp(x0)
        if (x >= 1f) return 1f
        return (1 - exp(-6.0 * x) * kotlin.math.cos(x * 4.5 * PI)).toFloat()
    }

    /** Progress of an animation [durMs] long that starts at [startMs], through [curve]. */
    fun at(tMs: Long, startMs: Long, durMs: Long, curve: (Float) -> Float): Float {
        if (tMs <= startMs) return 0f
        if (durMs <= 0 || tMs >= startMs + durMs) return 1f
        return curve((tMs - startMs).toFloat() / durMs)
    }

    /**
     * Camera punch-ins at [cueMs] (sorted): every cue jumps the zoom, alternating close (1 + [amount])
     * and medium (1 + 0.4 × [amount]), with a short overshoot. Before the first cue the zoom is 1.
     */
    fun punch(tMs: Long, cueMs: List<Long>, amount: Float, durMs: Long = 240): Float {
        var k = 0
        while (k < cueMs.size && cueMs[k] <= tMs) k++
        if (k == 0) return 1f
        fun level(i: Int) = if (i == 0) 1f else if (i % 2 == 1) 1 + amount else 1 + amount * 0.4f
        val from = level(k - 1)
        val to = level(k)
        return from + (to - from) * at(tMs, cueMs[k - 1], durMs) { backOut(it) }
    }
}

/**
 * AI director: the AI gets the numbered transcript and returns a plan (protocol v2):
 * scenes over word ranges with visuals, highlighted words, camera punch-ins, pop-up stickers, sound effects,
 * scene transitions and words to cut out (fillers, slips). The app checks the plan and builds the video from it.
 */
object StudioDirector {
    val TRANSITIONS = listOf("fade", "slide", "zoom", "whip", "none")
    val SFX = listOf("whoosh", "pop", "click", "ding", "boom", "riser")
    const val MAX_POPS = 2
    const val MAX_ZOOMS = 3
    const val MAX_SFX = 4
    /** At most this share of the words may be cut out. */
    const val MAX_DROP_SHARE = 0.15

    /** A scene of the AI plan over words [from]..[to] (absolute indices); cue `at` are absolute too. */
    data class DScene(
        val from: Int, val to: Int, val kind: String, val title: String, val items: List<SItem>,
        val emph: List<String>, val transition: String, val cues: List<SCue>,
        val shapes: List<SShape> = emptyList(), val broll: String = "", val brollMode: String = ""
    )

    data class Direction(val title: String, val drop: Set<Int>, val scenes: List<DScene>)

    /** The plan applied to the take: new words (fillers cut), scenes, their word ranges, and what of the take is kept. */
    data class Applied(
        val script: SScript, val ranges: List<IntRange>, val words: List<SWord>,
        /** Parts of the take to keep (null = nothing cut). */
        val keep: List<Span>?, val dropped: Int
    )

    /** "[0]Привет [1]мир ⏸0.8 [2]…": numbered words with pauses of [pauseMs] or longer. */
    fun transcript(words: List<SWord>, pauseMs: Long = 500): String = buildString {
        words.forEachIndexed { i, w ->
            if (i > 0) {
                val gap = w.startMs - words[i - 1].endMs
                append(' ')
                if (gap >= pauseMs) append("⏸").append((gap / 100) / 10.0).append(' ')
            }
            append('[').append(i).append(']').append(w.text)
        }
    }

    /** "w12", "12" or 12 → 12; null when it is not a word index. */
    fun wordIndex(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().removePrefix("w").removePrefix("W").substringBefore('+').trim().toIntOrNull()
        else -> null
    }

    /** Plan from an AI reply; scenes are put in order and made to cover all [wordCount] words. Null if nothing usable. */
    fun parse(reply: String, wordCount: Int): Direction? {
        if (wordCount <= 0) return null
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JsonSalvage.array(reply, "scenes")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val arr = o.optJSONArray("scenes") ?: return null
        val raw = mutableListOf<DScene>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val from = wordIndex(s.opt("from")) ?: continue
            val to = wordIndex(s.opt("to")) ?: from
            // visuals, highlights, shapes and B-roll are read like a saved scene (their word numbers stay absolute here);
            // cues are read below
            val base = runCatching { Studio.sceneOf(JSONObject(s.toString()).put("say", "x").put("cues", org.json.JSONArray())) }
                .getOrNull() ?: SScene("x")
            val cues = mutableListOf<SCue>()
            s.optJSONArray("cues")?.let { ca ->
                for (j in 0 until ca.length()) {
                    val c = ca.optJSONObject(j) ?: continue
                    val at = wordIndex(c.opt("at")) ?: continue
                    val type = c.optString("do").ifBlank { c.optString("type") }.trim().lowercase()
                    when (type) {
                        "zoom" -> cues += SCue("zoom", at)
                        "pop" -> c.optString("text").trim().take(32).takeIf { it.isNotEmpty() || c.optString("icon").isNotBlank() }
                            ?.let { cues += SCue("pop", at, it, c.optString("icon").trim().take(8)) }
                        "sfx" -> c.optString("name").ifBlank { c.optString("text") }.trim().lowercase()
                            .takeIf { it in SFX }?.let { cues += SCue("sfx", at, it) }
                    }
                }
            }
            raw += DScene(from, to, fitKind(base.kind, base.items), base.title, base.items, base.emph,
                s.optString("transition").trim().lowercase().takeIf { it in TRANSITIONS } ?: "", cues,
                base.shapes, base.broll, base.brollMode)
        }
        val drop = mutableSetOf<Int>()
        o.optJSONArray("drop")?.let { da -> for (i in 0 until da.length()) wordIndex(da.opt(i))?.takeIf { it in 0 until wordCount }?.let { drop += it } }
        val scenes = cover(raw, wordCount)
        if (scenes.isEmpty()) return null
        return Direction(o.optString("title").trim(), drop, scenes)
    }

    /** First number of [s] ("1 200", "55%", "3,5 млн" → 1200, 55, 3.5); null when there is none. */
    fun number(s: String): Float? =
        Regex("""-?\d[\d\s]*(?:[.,]\d+)?""").find(s)?.value?.replace(Regex("\\s"), "")?.replace(',', '.')?.toFloatOrNull()

    /** A layout that can be drawn with these items: a chart needs two numbers, a comparison two sides. */
    fun fitKind(kind: String, items: List<SItem>): String = when {
        kind == "chart" && items.count { number(it.value) != null } < 2 -> if (items.isEmpty()) "text" else "icons"
        kind == "compare" && items.size < 2 -> if (items.isEmpty()) "text" else "big"
        else -> kind
    }

    /**
     * Scenes in order, without gaps or overlaps, from word 0 to the last word: a gap goes to the scene after it,
     * the first scene starts at 0 and the last one ends at the last word. Cues are limited and kept inside their scene.
     */
    fun cover(raw: List<DScene>, wordCount: Int): List<DScene> {
        val sorted = raw.filter { it.from < wordCount && it.to >= 0 }.sortedBy { it.from }
        val out = mutableListOf<DScene>()
        var next = 0
        for (s in sorted) {
            val to = min(wordCount - 1, max(s.to, s.from))
            if (to < next) continue
            out += s.copy(from = next, to = to)
            next = to + 1
        }
        if (out.isEmpty()) return emptyList()
        if (next < wordCount) out[out.lastIndex] = out.last().copy(to = wordCount - 1)
        return out.map { s ->
            s.copy(cues = limitCues(s.cues, s.from, s.to),
                shapes = s.shapes.take(StudioKeys.MAX_SHAPES).map { sh -> sh.copy(keys = sh.keys.map { it.copy(at = it.at.coerceIn(s.from, s.to)) }) })
        }
    }

    /** Cues inside [from]..[to], sorted, at most 2 pops, 3 zooms (4 words apart) and 4 sounds per scene. */
    fun limitCues(cues: List<SCue>, from: Int, to: Int): List<SCue> {
        val inside = cues.map { it.copy(at = it.at.coerceIn(from, to)) }.sortedBy { it.at }
        val zooms = mutableListOf<SCue>()
        for (z in inside.filter { it.type == "zoom" }) if (zooms.size < MAX_ZOOMS && (zooms.isEmpty() || z.at - zooms.last().at >= 4)) zooms += z
        val pops = inside.filter { it.type == "pop" }.distinctBy { it.at }.take(MAX_POPS)
        val sfx = inside.filter { it.type == "sfx" }.distinctBy { it.at }.take(MAX_SFX)
        return (zooms + pops + sfx).sortedBy { it.at }
    }

    /** Words that may really be cut: at most [MAX_DROP_SHARE] of them, never every word of a scene. */
    fun safeDrop(d: Direction, wordCount: Int): Set<Int> {
        if (d.drop.isEmpty()) return emptySet()
        val ok = d.drop.filter { i -> d.scenes.firstOrNull { i in it.from..it.to }?.let { s -> (s.from..s.to).any { it !in d.drop } } ?: false }
        if (ok.size > wordCount * MAX_DROP_SHARE) return emptySet()
        return ok.toSet()
    }

    /**
     * Parts of a take of [totalMs] that stay when [drop] words are cut out: each run of dropped words is removed
     * from the end of the word before it (plus [padMs]) to the start of the word after it (minus [padMs]).
     */
    fun keepWithout(words: List<SWord>, drop: Set<Int>, totalMs: Long, padMs: Long = 60): List<Span> {
        val cuts = mutableListOf<Span>()
        var i = 0
        while (i < words.size) {
            if (i !in drop) {
                i++
                continue
            }
            var j = i
            while (j + 1 < words.size && j + 1 in drop) j++
            val a = if (i > 0) min(words[i].startMs, words[i - 1].endMs + padMs) else 0L
            val b = if (j + 1 < words.size) max(words[j].endMs, words[j + 1].startMs - padMs) else totalMs
            if (b > a) cuts += Span(max(0, a), min(totalMs, b))
            i = j + 1
        }
        val keep = mutableListOf<Span>()
        var at = 0L
        for (c in cuts) {
            if (c.startMs > at) keep += Span(at, c.startMs)
            at = max(at, c.endMs)
        }
        if (at < totalMs) keep += Span(at, totalMs)
        return keep
    }

    /** Time [t] of the take in the glued [keep] parts (inside a removed part: where the next part starts). */
    fun mapTime(t: Long, keep: List<Span>): Long {
        var out = 0L
        for (s in keep) {
            if (t < s.startMs) return out
            if (t <= s.endMs) return out + (t - s.startMs)
            out += s.ms
        }
        return out
    }

    /**
     * [keep] (parts of a take that was already cut by [old], in its own time) as parts of the original source:
     * what [StudioBg] needs to find the video frames.
     */
    fun composeCuts(old: List<Span>?, keep: List<Span>): List<Span> {
        if (old.isNullOrEmpty()) return keep
        val out = mutableListOf<Span>()
        for (k in keep) {
            var base = 0L
            for (o in old) {
                val a = max(k.startMs, base)
                val b = min(k.endMs, base + o.ms)
                if (b > a) {
                    val s = Span(o.startMs + (a - base), o.startMs + (b - base))
                    val prev = out.lastOrNull()
                    if (prev != null && prev.endMs == s.startMs) out[out.lastIndex] = Span(prev.startMs, s.endMs) else out += s
                }
                base += o.ms
            }
        }
        return out
    }

    /** Indices of [range] words that match one of [emph] (a word or a short phrase, compared by letters and digits). */
    fun emphWords(words: List<String>, range: IntRange, emph: List<String>): Set<Int> {
        val out = mutableSetOf<Int>()
        val norms = range.associateWith { Studio.norm(words.getOrElse(it) { "" }) }
        for (e in emph) {
            val parts = Studio.tokens(e).map { Studio.norm(it) }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            for (start in range) {
                if (start + parts.size - 1 > range.last) break
                if (parts.indices.all { k -> norms[start + k] == parts[k] }) {
                    for (k in parts.indices) out += start + k
                    break
                }
            }
        }
        return out
    }

    /**
     * The plan on the take: cuts out [drop] words, gives every scene its words and turns cue word indices into
     * offsets inside the scene. [totalMs] is the length of the take.
     */
    fun apply(d: Direction, words: List<SWord>, totalMs: Long, drop: Set<Int>): Applied {
        val keep = if (drop.isEmpty()) null else keepWithout(words, drop, totalMs)
        val newIndex = IntArray(words.size) { -1 }
        val out = mutableListOf<SWord>()
        words.forEachIndexed { i, w ->
            if (i in drop) return@forEachIndexed
            newIndex[i] = out.size
            out += if (keep == null) w else SWord(w.text, mapTime(w.startMs, keep), max(mapTime(w.startMs, keep) + 1, mapTime(w.endMs, keep)))
        }
        val scenes = mutableListOf<SScene>()
        val ranges = mutableListOf<IntRange>()
        for (s in d.scenes) {
            val kept = (s.from..s.to).map { newIndex[it] }.filter { it >= 0 }
            if (kept.isEmpty()) continue
            val r = kept.first()..kept.last()
            fun local(at: Int): Int {
                val n = (at..s.to).firstNotNullOfOrNull { k -> newIndex[k].takeIf { it >= 0 } } ?: r.last
                return (n - r.first).coerceIn(0, r.last - r.first)
            }
            val cues = s.cues.map { it.copy(at = local(it.at)) }
            val shapes = s.shapes.map { sh -> sh.copy(keys = sh.keys.map { it.copy(at = local(it.at)) }) }
            scenes += SScene(r.joinToString(" ") { out[it].text }, s.kind, s.title, s.items, s.emph, cues, s.transition,
                shapes, s.broll, s.brollMode)
            ranges += r
        }
        return Applied(SScript(d.title, scenes), ranges, out, keep, drop.size)
    }

    /** Scenes that ask for a B-roll photo but have none yet. */
    fun needBroll(s: SScript): List<Int> = s.scenes.indices.filter { s.scenes[it].broll.isNotBlank() && s.scenes[it].media.isBlank() }

    /** Credits of the photos used, one per line, without repeats. */
    fun credits(s: SScript): String = s.scenes.map { it.credit }.filter { it.isNotBlank() }.distinct().joinToString("\n")

    /** Short line about a plan: "zooms 4 · stickers 2 · sounds 6". */
    fun summary(s: SScript): Triple<Int, Int, Int> {
        val cues = s.scenes.flatMap { it.cues }
        return Triple(cues.count { it.type == "zoom" }, cues.count { it.type == "pop" }, cues.count { it.type == "sfx" })
    }
}

/** Sound effects made from math (no sound files): whoosh, pop, click, ding, boom, riser. */
object StudioSfx {
    /** Length of an effect in ms. */
    fun lengthMs(name: String): Long = when (name) {
        "whoosh" -> 450
        "pop" -> 110
        "click" -> 40
        "ding" -> 900
        "boom" -> 750
        "riser" -> 1200
        else -> 0
    }

    /** A riser ends at its cue; the other effects start at it. */
    fun startOf(name: String, cueMs: Long): Long = if (name == "riser") max(0, cueMs - lengthMs(name)) else max(0, cueMs - 10)

    /** The effect as samples in -1..1 at [rate]. */
    fun synth(name: String, rate: Int): FloatArray {
        val n = (lengthMs(name) * rate / 1000).toInt()
        val out = FloatArray(n)
        if (n == 0) return out
        val rnd = java.util.Random(name.hashCode().toLong())
        var lp = 0f
        var phase = 0.0
        for (i in 0 until n) {
            val x = i.toFloat() / n
            val tSec = i.toDouble() / rate
            val noise = rnd.nextFloat() * 2 - 1
            out[i] = when (name) {
                "whoosh" -> {
                    // noise through a low-pass filter that opens and closes
                    val cut = 0.03f + 0.25f * sin(PI * x).toFloat()
                    lp += cut * (noise - lp)
                    lp * 1.6f * sin(PI * x).toFloat().let { it * it }
                }
                "pop" -> {
                    val f = 300 + 700 * exp(-x * 5.0)
                    phase += 2 * PI * f / rate
                    (sin(phase) * exp(-x * 6.0) * 0.8).toFloat()
                }
                "click" -> ((noise * 0.5 + sin(2 * PI * 2200 * tSec) * 0.5) * exp(-x * 9.0)).toFloat()
                "ding" -> ((sin(2 * PI * 1318.5 * tSec) * 0.6 + sin(2 * PI * 1975.5 * tSec) * 0.25 + sin(2 * PI * 2637 * tSec) * 0.1) *
                    exp(-x * 5.0) * min(1.0, i / (rate * 0.004))).toFloat()
                "boom" -> {
                    val f = 70 - 30 * x
                    phase += 2 * PI * f / rate
                    (sin(phase) * exp(-x * 4.0) * 0.95 + noise * 0.3 * exp(-x * 40.0)).toFloat()
                }
                "riser" -> {
                    val f = 200 + 1000 * x * x
                    phase += 2 * PI * f / rate
                    val cut = 0.02f + 0.4f * x
                    lp += cut * (noise - lp)
                    ((sin(phase) * 0.35 + lp * 0.9) * x * x * min(1.0, (1 - x) * 30.0)).toFloat()
                }
                else -> 0f
            }.coerceIn(-1f, 1f)
        }
        return out
    }

    /** Effects of a plan as (start ms, name): the AI's sounds plus a whoosh on a slide/zoom/whip entry and a pop for every sticker. */
    fun events(script: SScript, times: List<SceneTime>, words: List<SWord>): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        times.forEachIndexed { i, st ->
            val sc = script.scenes.getOrNull(st.scene) ?: return@forEachIndexed
            fun ms(at: Int): Long = words.getOrNull(st.words.first + at)?.startMs ?: st.startMs
            val own = sc.cues.filter { it.type == "sfx" }
            own.forEach { out += ms(it.at) to it.text }
            if (i > 0 && sc.transition in listOf("slide", "zoom", "whip") && own.none { it.at == 0 }) out += st.startMs to "whoosh"
            sc.cues.filter { it.type == "pop" }.forEach { p -> if (own.none { it.at == p.at }) out += ms(p.at) to "pop" }
        }
        return out.sortedBy { it.first }
    }

    /** [base] with the [events] mixed in at [gain] (0..1 of full scale), clipped to 16 bit. */
    fun mix(base: ShortArray, rate: Int, events: List<Pair<Long, String>>, gain: Float = 0.45f): ShortArray {
        if (events.isEmpty() || gain <= 0f) return base
        val acc = FloatArray(base.size) { base[it].toFloat() }
        val cache = HashMap<String, FloatArray>()
        for ((ms, name) in events) {
            val s = cache.getOrPut(name) { synth(name, rate) }
            val start = (startOf(name, ms) * rate / 1000).toInt()
            for (k in s.indices) {
                val i = start + k
                if (i >= acc.size) break
                if (i >= 0) acc[i] += s[k] * gain * 32767f
            }
        }
        return ShortArray(base.size) { acc[it].roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}

/** Shapes animated on key frames: reading, checking and the state of a shape at any moment. */
object StudioKeys {
    val SHAPES = listOf("arrow", "line", "circle", "ring", "rect", "underline", "check", "cross", "spotlight", "text", "emoji")
    val EASES = listOf("linear", "in", "out", "inOut", "back", "spring")
    const val MAX_SHAPES = 3
    const val MAX_KEYS = 8

    /** Where a shape is and how it looks at one moment. */
    data class State(val x: Float, val y: Float, val s: Float, val r: Float, val o: Float, val draw: Float)

    private fun num(o: JSONObject, k: String, lo: Float, hi: Float): Float? {
        if (!o.has(k)) return null
        val v = o.optDouble(k)
        if (v.isNaN()) return null
        return v.toFloat().coerceIn(lo, hi)
    }

    /** "w12+300" → 300 (the shift after the word); a number or "w12" → 0. */
    fun shiftMs(v: Any?): Long = (v as? String)?.substringAfter('+', "")?.trim()?.toLongOrNull() ?: 0L

    /** A shape from JSON; null for an unknown kind, text without text or no key frames. */
    fun shapeOf(o: JSONObject): SShape? {
        val el = o.optString("el").ifBlank { o.optString("type") }.trim().lowercase()
        if (el !in SHAPES) return null
        val text = o.optString("text").trim().take(40)
        if ((el == "text" || el == "emoji") && text.isEmpty()) return null
        val ka = o.optJSONArray("keys") ?: return null
        val keys = (0 until ka.length()).mapNotNull { i ->
            val k = ka.optJSONObject(i) ?: return@mapNotNull null
            val at = StudioDirector.wordIndex(k.opt("at")) ?: return@mapNotNull null
            val ms = (if (k.has("ms")) k.optLong("ms") else shiftMs(k.opt("at"))).coerceIn(0, 20_000)
            val ease = k.optString("ease").trim().let { e -> EASES.firstOrNull { it.equals(e, true) } ?: "" }
            SKey(at.coerceAtLeast(0), ms, num(k, "x", -0.5f, 1.5f), num(k, "y", -0.5f, 1.5f), num(k, "s", 0f, 6f),
                num(k, "r", -1080f, 1080f), num(k, "o", 0f, 1f), num(k, "draw", 0f, 1f), ease)
        }.sortedWith(compareBy({ it.at }, { it.ms })).take(MAX_KEYS)
        if (keys.isEmpty()) return null
        val color = o.optString("color").trim().ifBlank { "accent" }.take(9)
        return SShape(el, text, color, o.optBoolean("fill", false),
            num(o, "w", 0.01f, 1.5f) ?: if (el == "text" || el == "emoji") 0.08f else 0.2f,
            num(o, "h", 0.005f, 1.5f) ?: if (el == "text" || el == "emoji") 0.08f else 0.2f, keys)
    }

    fun toJson(s: SShape): JSONObject = JSONObject().put("el", s.el).put("text", s.text).put("color", s.color).put("fill", s.fill)
        .put("w", s.w.toDouble()).put("h", s.h.toDouble()).put("keys", org.json.JSONArray().apply {
            s.keys.forEach { k ->
                put(JSONObject().put("at", k.at).put("ms", k.ms).apply {
                    k.x?.let { put("x", it.toDouble()) }
                    k.y?.let { put("y", it.toDouble()) }
                    k.s?.let { put("s", it.toDouble()) }
                    k.r?.let { put("r", it.toDouble()) }
                    k.o?.let { put("o", it.toDouble()) }
                    k.draw?.let { put("draw", it.toDouble()) }
                    if (k.ease.isNotEmpty()) put("ease", k.ease)
                })
            }
        })

    fun ease(name: String, x: Float): Float = when (name) {
        "linear" -> StudioMotion.clamp(x)
        "in" -> StudioMotion.clamp(x).let { it * it * it }
        "out" -> 1 - (1 - StudioMotion.clamp(x)).let { it * it * it }
        "back" -> StudioMotion.backOut(x)
        "spring" -> StudioMotion.spring(x)
        else -> StudioMotion.inOut(x)
    }

    /** Key frames with every value filled in from the frames before (defaults: centre, scale 1, no turn, visible, drawn). */
    fun resolve(keys: List<SKey>): List<State> {
        var cur = State(0.5f, 0.5f, 1f, 0f, 1f, 1f)
        return keys.map { k ->
            cur = State(k.x ?: cur.x, k.y ?: cur.y, k.s ?: cur.s, k.r ?: cur.r, k.o ?: cur.o, k.draw ?: cur.draw)
            cur
        }
    }

    /** Times of the key frames, never going back: [wordMs] gives the start of word k of the scene. */
    fun times(keys: List<SKey>, wordMs: (Int) -> Long): List<Long> {
        var last = Long.MIN_VALUE
        return keys.map { k ->
            last = max(last, wordMs(k.at) + k.ms)
            last
        }
    }

    /** The shape at [tMs]; null before its first key frame. Between frames values glide with the ease of the next frame. */
    fun state(shape: SShape, times: List<Long>, tMs: Long): State? {
        if (shape.keys.isEmpty() || times.size != shape.keys.size || tMs < times[0]) return null
        val f = resolve(shape.keys)
        var k = 0
        while (k + 1 < times.size && times[k + 1] <= tMs) k++
        if (k == f.lastIndex) return f[k]
        val a = f[k]
        val b = f[k + 1]
        val dur = times[k + 1] - times[k]
        if (dur <= 0) return b
        val p = ease(shape.keys[k + 1].ease, (tMs - times[k]).toFloat() / dur)
        fun mix(x: Float, y: Float) = x + (y - x) * p
        return State(mix(a.x, b.x), mix(a.y, b.y), mix(a.s, b.s), mix(a.r, b.r), mix(a.o, b.o), mix(a.draw, b.draw))
    }

    /** Named colour or "#RRGGBB"; [accent] and [ink] come from the style. */
    fun color(name: String, accent: Int, ink: Int): Int = when (name.lowercase()) {
        "accent" -> accent
        "ink" -> ink
        "white" -> 0xFFFFFFFF.toInt()
        "black" -> 0xFF000000.toInt()
        "red" -> 0xFFFF4D6A.toInt()
        "green" -> 0xFF34C759.toInt()
        "yellow" -> 0xFFFFD60A.toInt()
        "blue" -> 0xFF4F8BFF.toInt()
        else -> if (name.startsWith("#") && name.length == 7) (name.substring(1).toLongOrNull(16)?.toInt()?.let { it or 0xFF000000.toInt() } ?: accent) else accent
    }
}

/** A stock photo found for B-roll. */
data class BrollHit(
    val url: String, val thumb: String, val width: Int, val height: Int, val title: String, val creator: String,
    val license: String, val licenseVersion: String, val landing: String
)

/** B-roll photos from Openverse (official API, no key; Wikimedia, Flickr and other open collections). Pure part. */
object StudioBrollCatalog {
    /** Search for photos; [aspect] narrows to wide, tall or square pictures. */
    fun url(query: String, commercial: Boolean, aspect: StudioAspect?): String =
        "https://api.openverse.org/v1/images/?q=${StudioMusicCatalog.enc(query)}&page_size=12&mature=false" +
            (if (commercial) "&license_type=commercial" else "") +
            when (aspect) {
                StudioAspect.PORTRAIT -> "&aspect_ratio=tall"
                StudioAspect.LANDSCAPE -> "&aspect_ratio=wide"
                StudioAspect.SQUARE -> "&aspect_ratio=square"
                null -> ""
            }

    fun parse(json: String): List<BrollHit> {
        val arr = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            BrollHit(url, o.optString("thumbnail"), o.optInt("width", 0), o.optInt("height", 0), o.optString("title"),
                o.optString("creator"), StudioMusicCatalog.licenseCode(o.optString("license")), o.optString("license_version"),
                o.optString("foreign_landing_url"))
        }
    }

    /** Best photos first: big enough for the frame, then the shape closest to it; tiny ones last. */
    fun rank(hits: List<BrollHit>, aspect: StudioAspect): List<BrollHit> {
        val want = when (aspect) {
            StudioAspect.PORTRAIT -> 9f / 16
            StudioAspect.LANDSCAPE -> 16f / 9
            StudioAspect.SQUARE -> 1f
        }
        return hits.sortedWith(compareBy<BrollHit> { if (min(it.width, it.height) in 1 until 600) 1 else 0 }
            .thenBy { h -> if (h.width > 0 && h.height > 0) abs(kotlin.math.ln((h.width.toFloat() / h.height) / want)) else 9f })
    }

    /** Credit line for the video description. */
    fun credit(h: BrollHit): String = buildString {
        append("Photo")
        if (h.title.isNotBlank()) append(": \"").append(h.title.take(80)).append('"')
        if (h.creator.isNotBlank()) append(" by ").append(h.creator.take(60))
        val lic = when (h.license) {
            "cc0" -> "CC0"
            "pdm" -> "Public Domain"
            else -> ("CC " + h.license.uppercase() + " " + h.licenseVersion).trim()
        }
        append(" (").append(lic).append(')')
        if (h.landing.isNotBlank()) append(' ').append(h.landing)
    }
}

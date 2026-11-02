/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A shape of a native motion block at one moment, in pixels of the block; [a] — opacity 0..1. */
sealed class MPrim {
    abstract val a: Float

    data class Box(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float, val color: Int, override val a: Float,
                   val stroke: Float = 0f, val glow: Float = 0f) : MPrim()

    /** [align] -1 left, 0 centre, 1 right; [maxW] — the painter shrinks the text to fit. */
    data class Label(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, override val a: Float,
                     val align: Int = 0, val weight: Int = 800, val maxW: Float = 0f, val outline: Boolean = false, val glow: Float = 0f) : MPrim()

    data class Arc(val cx: Float, val cy: Float, val r: Float, val start: Float, val sweep: Float, val width: Float, val color: Int, override val a: Float,
                   val glow: Float = 0f) : MPrim()

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Int, override val a: Float) : MPrim()

    data class Icon(val text: String, val cx: Float, val cy: Float, val size: Float, override val a: Float) : MPrim()
}

/** A built-in native motion template (kind "motion"): what it is for. */
data class MotionPreset(val id: String, val type: String, val ru: String, val en: String) {
    val name: String get() = t(ru, en)
}

/**
 * Native motion blocks of the editor: counter, bars, ring, checklist, A versus B, lower third, call to action, timer.
 * Drawn by the editor painter from [frame] (pure: shapes in pixels of the block) — instantly, at any length and size,
 * in the colours of the theme, without AI, WebView or rendered frames. The intro and the outro keep their length,
 * the middle stretches with the block.
 */
object EditMotion {
    const val KIND = "motion"

    val PRESETS = listOf(
        MotionPreset("counter", "number", "Счётчик числа", "Number counter"),
        MotionPreset("bars", "chart", "Столбцы", "Bars"),
        MotionPreset("ring", "progress", "Кольцо прогресса", "Progress ring"),
        MotionPreset("checklist", "list", "Список с галочками", "Checklist"),
        MotionPreset("compare", "compare", "A против B", "A versus B"),
        MotionPreset("lower", "lower", "Подпись спикера", "Lower third"),
        MotionPreset("cta", "cta", "Призыв", "Call to action"),
        MotionPreset("timer", "progress", "Таймер / шкала", "Timer / bar")
    )

    fun preset(id: String): MotionPreset? = PRESETS.firstOrNull { it.id == id }

    /** The native template for a type of the wizard, null — the type needs a scheme or the AI. */
    fun forType(type: String): String? = when (type) {
        "number" -> "counter"
        "chart" -> "bars"
        "progress" -> "ring"
        "list", "proscons" -> "checklist"
        "compare" -> "compare"
        "lower" -> "lower"
        "cta" -> "cta"
        else -> null
    }

    // ---------- time ----------

    fun prog(t: Double, a: Double, b: Double): Float = ((t - a) / max(1e-6, b - a)).coerceIn(0.0, 1.0).toFloat()
    fun easeOut(x: Float): Float = 1 - (1 - x).pow(3)
    fun easeInOut(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    fun back(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1
        return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
    }

    /** The ease of a theme. */
    fun ease(name: String, x: Float): Float = when (name) {
        "linear" -> x
        "inOut" -> easeInOut(x)
        "back" -> back(x)
        "elastic" -> if (x <= 0f) 0f else if (x >= 1f) 1f else (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * 2.0944) + 1).toFloat()
        else -> easeOut(x)
    }

    /** Length of one move by the pace of the theme, seconds. */
    fun moveS(pace: String): Double = when (pace) { "calm" -> 0.75; "drive" -> 0.25; else -> 0.4 }

    /** Opacity of the whole block: in over the first move, out over the last 0.35 s (never more than a fifth of it). */
    fun envelope(t: Double, dur: Double, pace: String): Float {
        val inS = min(moveS(pace), dur * 0.2)
        val outS = min(0.35, dur * 0.15)
        return min(easeOut(prog(t, 0.0, inS)), 1 - prog(t, dur - outS, dur))
    }

    /**
     * When each of [labels] comes in: on the word of the speech that starts like it ([words] — text and second from the block
     * start), else one after another over the first 60 % of the block.
     */
    fun revealTimes(labels: List<String>, words: List<Pair<String, Double>>, dur: Double): List<Double> {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val n = max(1, labels.size)
        val step = max(0.25, (dur * 0.6 - 0.2) / n)
        val used = HashSet<Int>()
        return labels.mapIndexed { i, l ->
            val k = key(l).take(5)
            val hit = if (k.length >= 3) words.indices.firstOrNull { j -> j !in used && key(words[j].first).startsWith(k) } else null
            if (hit != null) {
                used += hit
                words[hit].second.coerceIn(0.0, max(0.0, dur - 0.3))
            } else 0.2 + i * step
        }
    }

    // ---------- numbers ----------

    data class Num(val value: Double, val decimals: Int, val comma: Boolean)

    /** «2,5» → 2.5 (1 decimal, comma), «1 200» → 1200; null — not a number. */
    fun parseNum(s: String): Num? {
        val v = s.trim().replace(" ", "").replace(" ", "").replace(" ", "")
        val m = Regex("^-?\\d+(?:[.,]\\d+)?").find(v) ?: return null
        val txt = m.value
        val comma = txt.contains(',')
        val d = txt.substringAfter(if (comma) ',' else '.', "").length
        return txt.replace(',', '.').toDoubleOrNull()?.let { Num(it, d, comma) }
    }

    /** [x] with [n]'s decimals and separator, thousands split by a space: 12 345,6. */
    fun fmt(x: Double, n: Num): String {
        val s = String.format(Locale.US, "%.${n.decimals}f", x)
        val int = s.substringBefore('.')
        val frac = s.substringAfter('.', "")
        val neg = int.startsWith("-")
        val digits = int.removePrefix("-")
        val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) (if (n.comma) "," else ".") + frac else ""
    }

    private fun unitText(u: String) = when {
        u.isEmpty() -> ""
        u == "%" -> "%"
        else -> " $u"
    }

    private fun onColor(bg: Int): Int = if (SchemeStyles.luminance(bg) > 0.45) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()

    // ---------- frames ----------

    /** What a motion block shows, from its brief (rows, title, fields) and the speech inside it. */
    data class Content(val rows: List<BriefRow>, val title: String, val fields: Map<String, String>, val words: List<Pair<String, Double>>)

    fun content(b: EditBrief, words: List<Pair<String, Double>>): Content =
        Content(b.rows, if (b.title == "-") "" else b.title, b.fields, words)

    /**
     * The shapes of preset [id] at [t] seconds of a block [dur] long and [w]×[h] pixels, in theme [th];
     * [minFont] — the smallest text in pixels.
     */
    fun frame(id: String, c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, minFont: Float = 0f): List<MPrim> {
        val env = envelope(t, dur, th.pace)
        if (env <= 0.001f || w < 2 || h < 2) return emptyList()
        return when (id) {
            "bars" -> bars(c, th, t, dur, w, h, env, minFont)
            "ring" -> ring(c, th, t, dur, w, h, env, minFont)
            "checklist" -> checklist(c, th, t, dur, w, h, env, minFont)
            "compare" -> compare(c, th, t, dur, w, h, env, minFont)
            "lower" -> lower(c, th, t, dur, w, h, env, minFont)
            "cta" -> cta(c, th, t, w, h, env)
            "timer" -> timer(c, th, t, dur, w, h, env, minFont)
            else -> counter(c, th, t, dur, w, h, env, minFont)
        }
    }

    private fun plate(th: EditTheme, w: Float, h: Float, a: Float, l: Float = 0f, r: Float = w): MPrim.Box? {
        if (th.plate == "none") return null
        val alpha = (if (th.plate == "full") 1f else th.plateAlpha / 100f) * a
        val rad = min(w, h) * EditInsert.radiusOf(th.radius, 0.06f) * 2
        return MPrim.Box(l, 0f, r, h, rad, th.surface, alpha, glow = 0f)
    }

    private fun txt(th: EditTheme, s: String) = if (th.upper) s.uppercase() else s

    private fun counter(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val row = c.rows.firstOrNull { it.value.isNotEmpty() }
        val raw = c.fields["value"]?.takeIf { it.isNotBlank() } ?: row?.value
            ?: c.words.firstOrNull { w0 -> w0.first.any { it.isDigit() } }?.first ?: "100"
        val n = parseNum(raw) ?: Num(100.0, 0, false)
        val unit = c.fields["unit"]?.takeIf { it.isNotBlank() } ?: row?.unit ?: if (raw.trim().endsWith("%")) "%" else ""
        val from = c.fields["from"]?.let { parseNum(it)?.value } ?: 0.0
        val cap = txt(th, c.fields["caption"]?.takeIf { it.isNotBlank() } ?: row?.label ?: c.title)
        val at = c.words.firstOrNull { w0 -> w0.first.any { it.isDigit() } }?.second?.coerceAtMost(dur * 0.5) ?: 0.2
        val k = ease(th.ease, prog(t, at, at + min(1.6 * th.durScale, dur * 0.5)))
        val value = fmt(from + (n.value - from) * k.coerceIn(0f, 1.05f), n) + unitText(unit)
        val out = mutableListOf<MPrim>()
        plate(th, w, h, a)?.let { out += it }
        out += MPrim.Label(value, w / 2, h * (if (cap.isNotEmpty()) 0.42f else 0.5f), h * (if (cap.isNotEmpty()) 0.42f else 0.56f) * th.scale, th.accent, a,
            weight = 900, maxW = w * 0.86f, glow = th.glow)
        if (cap.isNotEmpty()) out += MPrim.Label(cap, w / 2, h * 0.79f, max(minFont, h * 0.14f * th.scale), th.ink, a * easeOut(prog(t, at, at + 0.4)), weight = th.weight - 100, maxW = w * 0.86f)
        return out
    }

    private data class Bar(val label: String, val v: Double, val text: String, val main: Boolean)

    private fun barsOf(c: Content): List<Bar> {
        val list = c.rows.mapNotNull { r -> parseNum(r.value)?.let { Bar(r.label, it.value, r.value + unitText(r.unit), r.main) } }.take(6)
        return list.ifEmpty { listOf(Bar("A", 3.0, "3", false), Bar("B", 5.0, "5", true), Bar("C", 2.0, "2", false)) }
    }

    private fun bars(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val rows = barsOf(c)
        val mx = rows.maxOf { abs(it.v) }.takeIf { it > 0 } ?: 1.0
        val hasMain = rows.any { it.main }
        val times = revealTimes(rows.map { it.label }, c.words, dur)
        val out = mutableListOf<MPrim>()
        plate(th, w, h, a)?.let { out += it }
        val pad = w * 0.08f
        val bw = (w - pad * 2) / rows.size
        val title = txt(th, c.title)
        val top = h * (if (title.isNotEmpty()) 0.3f else 0.16f)
        val base = h * 0.8f
        val fs = max(minFont, min(bw * 0.28f, h * 0.08f)) * th.scale
        if (title.isNotEmpty()) out += MPrim.Label(title, w / 2, h * 0.11f, max(minFont, h * 0.09f) * th.scale, th.ink, a, weight = th.weight, maxW = w * 0.9f)
        rows.forEachIndexed { i, r ->
            val g = ease(if (th.ease == "out") "back" else th.ease, prog(t, times[i], times[i] + moveS(th.pace) * 1.5 * th.durScale))
            val bh = max(0f, ((base - top) * (abs(r.v) / mx)).toFloat() * g)
            val x = pad + bw * i
            val main = r.main || (!hasMain && r.v == rows.maxOf { it.v })
            out += MPrim.Box(x + bw * 0.18f, base - bh, x + bw * 0.82f, base, bw * 0.08f * (if (th.radius == "none") 0f else 1f), if (main) th.accent else th.muted, a, glow = if (main) th.glow else 0f)
            if (g > 0.6f) out += MPrim.Label(txt(th, r.text), x + bw / 2, base - bh - fs * 0.8f, fs, th.ink, a * prog(t, times[i] + 0.15, times[i] + 0.4), weight = 800, maxW = bw * 0.95f)
            out += MPrim.Label(txt(th, r.label.take(16)), x + bw / 2, base + fs * 0.95f, fs * 0.9f, th.muted, a, weight = 600, maxW = bw * 0.95f)
        }
        return out
    }

    private fun ring(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val row = c.rows.firstOrNull { it.value.isNotEmpty() }
        val raw = c.fields["value"]?.takeIf { it.isNotBlank() } ?: row?.value ?: c.words.firstOrNull { w0 -> w0.first.any { it.isDigit() } }?.first ?: "75"
        val v = parseNum(raw)?.value ?: 75.0
        val p = (if (v > 1) v / 100 else v).coerceIn(0.0, 1.0).toFloat()
        val cap = txt(th, c.fields["caption"]?.takeIf { it.isNotBlank() } ?: row?.label ?: c.title)
        val cx = w / 2
        val cy = h * (if (cap.isNotEmpty()) 0.44f else 0.5f)
        val r = min(w, h) * (if (cap.isNotEmpty()) 0.3f else 0.36f)
        val lw = r * 0.22f
        val k = ease(th.ease.takeIf { it != "back" } ?: "inOut", prog(t, 0.3, 0.3 + min(1.8 * th.durScale, dur * 0.5))) * p
        val out = mutableListOf<MPrim>()
        if (th.plate != "none") out += MPrim.Box(cx - r - lw, cy - r - lw, cx + r + lw, cy + r + lw, r + lw, th.surface, a * th.plateAlpha / 100f)
        out += MPrim.Arc(cx, cy, r, 0f, 360f, lw, th.muted, a * 0.3f)
        out += MPrim.Arc(cx, cy, r, -90f, 360f * max(0.002f, k), lw, th.accent, a, glow = th.glow)
        out += MPrim.Label("${(k * 100).toInt()}%", cx, cy, r * 0.55f * th.scale, th.ink, a, weight = 900, maxW = r * 1.3f)
        if (cap.isNotEmpty()) out += MPrim.Label(cap, cx, h * 0.88f, max(minFont, h * 0.09f) * th.scale, th.ink, a, weight = th.weight - 100, maxW = w * 0.9f)
        return out
    }

    private fun checklist(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val items = c.rows.map { (if (it.icon.isNotEmpty()) it.icon + " " else "") + it.label.ifEmpty { it.value } }.filter { it.isNotBlank() }.take(6)
            .ifEmpty { listOf(t("Пункт 1", "Item 1"), t("Пункт 2", "Item 2"), t("Пункт 3", "Item 3")) }
        val times = revealTimes(items, c.words, dur)
        val out = mutableListOf<MPrim>()
        plate(th, w, h, a)?.let { out += it }
        val title = txt(th, c.title)
        val head = if (title.isNotEmpty()) 1.1f else 0f
        val rh = h / (items.size + 0.6f + head)
        val fs = max(minFont, rh * 0.42f) * th.scale
        val x0 = w * 0.08f
        if (title.isNotEmpty()) out += MPrim.Label(title, w / 2, rh * 0.75f, fs * 1.1f, th.accent, a, weight = th.weight, maxW = w * 0.9f)
        items.forEachIndexed { i, s ->
            val p = ease("back", prog(t, times[i], times[i] + moveS(th.pace) * 1.2 * th.durScale)).coerceIn(0f, 1.15f)
            if (p <= 0f) return@forEachIndexed
            val y = rh * (i + 0.8f + head)
            val al = a * p.coerceIn(0f, 1f)
            val dx = (1 - p.coerceIn(0f, 1f)) * w * 0.1f
            out += MPrim.Arc(x0 + fs * 0.5f + dx, y, fs * 0.27f, 0f, 360f, fs * 0.55f, th.accent, al, glow = th.glow)
            out += MPrim.Line(x0 + fs * 0.25f + dx, y, x0 + fs * 0.45f + dx, y + fs * 0.2f, fs * 0.14f, onColor(th.accent), al)
            out += MPrim.Line(x0 + fs * 0.45f + dx, y + fs * 0.2f, x0 + fs * 0.78f + dx, y - fs * 0.2f, fs * 0.14f, onColor(th.accent), al)
            out += MPrim.Label(txt(th, s), x0 + fs * 1.6f + dx, y, fs, th.ink, al, align = -1, weight = th.weight - 100, maxW = w - x0 * 2 - fs * 1.6f)
        }
        return out
    }

    private fun compare(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val a0 = c.rows.getOrNull(0) ?: BriefRow("A", "1")
        val b0 = c.rows.getOrNull(1) ?: BriefRow("B", "2")
        val va = parseNum(a0.value)?.value
        val vb = parseNum(b0.value)?.value
        val winner = c.fields["winner"]?.trim()?.lowercase()
        val aWins = when {
            winner != null && winner.isNotEmpty() -> a0.label.lowercase().startsWith(winner.take(3))
            a0.main != b0.main -> a0.main
            va != null && vb != null -> va >= vb
            else -> true
        }
        val times = revealTimes(listOf(a0.label, b0.label), c.words, dur)
        val out = mutableListOf<MPrim>()
        plate(th, w, h, a)?.let { out += it }
        val mx = max(abs(va ?: 1.0), abs(vb ?: 1.0)).takeIf { it > 0 } ?: 1.0
        val fs = max(minFont, h * 0.1f) * th.scale
        for ((i, r) in listOf(a0, b0).withIndex()) {
            val p = ease(th.ease, prog(t, times[i], times[i] + moveS(th.pace) * 1.5 * th.durScale))
            val cx = w * (if (i == 0) 0.27f else 0.73f)
            val win = (i == 0) == aWins
            val col = if (win) th.accent else th.ink
            val al = a * p.coerceIn(0f, 1f)
            out += MPrim.Label(txt(th, r.label.ifEmpty { if (i == 0) "A" else "B" }), cx, h * 0.2f, fs, th.muted, al, weight = 700, maxW = w * 0.34f)
            val v = if (i == 0) va else vb
            val shown = if (v != null) fmt(v * p.coerceIn(0f, 1f), parseNum(r.value)!!) + unitText(r.unit) else r.value
            out += MPrim.Label(txt(th, shown), cx, h * 0.48f, h * 0.24f * th.scale, col, al, weight = 900, maxW = w * 0.34f, glow = if (win) th.glow else 0f)
            if (v != null) {
                val bw = w * 0.36f * (abs(v) / mx).toFloat() * p
                out += MPrim.Box(cx - w * 0.18f, h * 0.7f, cx - w * 0.18f + bw, h * 0.78f, h * 0.04f, if (win) th.accent else th.muted, al)
            }
            if (win && p >= 1f) out += MPrim.Icon("👑", cx, h * 0.88f, fs * 1.1f, al * easeOut(prog(t, times[i] + 0.4, times[i] + 0.8)))
        }
        val vs = easeOut(prog(t, 0.1, 0.5))
        // «VS» stands between the names, the numbers keep their room
        out += MPrim.Arc(w / 2, h * 0.2f, h * 0.075f, 0f, 360f, h * 0.01f, th.accent2, a * vs)
        out += MPrim.Label("VS", w / 2, h * 0.2f, h * 0.065f, th.accent2, a * vs, weight = 900)
        return out
    }

    private fun lower(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val name = txt(th, c.fields["name"]?.takeIf { it.isNotBlank() } ?: c.title.ifEmpty { c.rows.firstOrNull()?.label ?: t("Имя Фамилия", "Name Surname") })
        val role = txt(th, c.fields["role"]?.takeIf { it.isNotBlank() } ?: c.rows.firstOrNull()?.value ?: "")
        val k = ease(th.ease, prog(t, 0.0, moveS(th.pace) * 1.4 * th.durScale))
        val out = mutableListOf<MPrim>()
        val right = w * k
        if (th.plate != "none") out += MPrim.Box(0f, 0f, right, h, h * EditInsert.radiusOf(th.radius, 0.06f) * 2, th.surface, a * th.plateAlpha / 100f)
        out += MPrim.Box(0f, 0f, w * 0.025f, h, 0f, th.accent, a, glow = th.glow)
        val tk = prog(t, moveS(th.pace) * 0.6, moveS(th.pace) * 1.6)
        out += MPrim.Label(name, w * 0.07f + (1 - tk) * w * 0.05f, h * (if (role.isNotEmpty()) 0.4f else 0.5f), max(minFont, h * 0.36f) * th.scale, th.ink, a * tk,
            align = -1, weight = th.weight, maxW = w * 0.88f)
        if (role.isNotEmpty()) {
            val rk = prog(t, moveS(th.pace) * 1.0, moveS(th.pace) * 2.0)
            out += MPrim.Label(role, w * 0.07f + (1 - rk) * w * 0.05f, h * 0.76f, max(minFont * 0.8f, h * 0.22f) * th.scale, th.accent, a * rk, align = -1, weight = 600, maxW = w * 0.88f)
        }
        return out
    }

    private fun cta(c: Content, th: EditTheme, t: Double, w: Float, h: Float, a: Float): List<MPrim> {
        val text = txt(th, c.fields["text"]?.takeIf { it.isNotBlank() } ?: c.title.ifEmpty { t("Подпишись", "Subscribe") })
        val icon = c.fields["icon"]?.takeIf { it.isNotBlank() } ?: "🔔"
        val pop = ease("back", prog(t, 0.0, moveS(th.pace) * 1.3))
        val pulse = if (pop >= 1f) 1f + 0.04f * sin(t * 2 * PI / 1.2).toFloat() else pop
        val bw = w * 0.92f * pulse
        val bh = h * 0.62f * pulse
        val l = (w - bw) / 2
        val tp = (h - bh) / 2
        val out = mutableListOf<MPrim>()
        out += MPrim.Box(l, tp, l + bw, tp + bh, bh / 2, th.accent, a, glow = th.glow)
        out += MPrim.Icon(icon, l + bh * 0.55f, h / 2, bh * 0.55f, a)
        out += MPrim.Label(text, l + bh * 1.05f + (bw - bh * 1.15f) / 2, h / 2, bh * 0.42f * th.scale, onColor(th.accent), a, weight = 900, maxW = bw - bh * 1.3f)
        return out
    }

    private fun timer(c: Content, th: EditTheme, t: Double, dur: Double, w: Float, h: Float, a: Float, minFont: Float): List<MPrim> {
        val from = c.fields["from"]?.let { parseNum(it)?.value }
        val to = c.fields["to"]?.let { parseNum(it)?.value }
        val cap = txt(th, c.fields["caption"]?.takeIf { it.isNotBlank() } ?: c.title)
        val p = prog(t, 0.2, max(0.4, dur - 0.4))
        val out = mutableListOf<MPrim>()
        plate(th, w, h, a)?.let { out += it }
        val label = if (from != null && to != null) fmt(from + (to - from) * p, Num(0.0, 0, false)) else {
            val left = ceil(max(0.0, dur - 0.4 - t)).toInt()
            String.format(Locale.US, "%d:%02d", left / 60, left % 60)
        }
        out += MPrim.Label(label, w / 2, h * (if (cap.isNotEmpty()) 0.36f else 0.42f), h * 0.3f * th.scale, th.ink, a, weight = 900, maxW = w * 0.8f)
        val y0 = h * 0.68f
        val y1 = h * 0.78f
        out += MPrim.Box(w * 0.08f, y0, w * 0.92f, y1, (y1 - y0) / 2, th.muted, a * 0.35f)
        out += MPrim.Box(w * 0.08f, y0, w * 0.08f + w * 0.84f * p, y1, (y1 - y0) / 2, th.accent, a, glow = th.glow)
        if (cap.isNotEmpty()) out += MPrim.Label(cap, w / 2, h * 0.9f, max(minFont, h * 0.09f) * th.scale, th.muted, a, weight = 600, maxW = w * 0.9f)
        return out
    }

    /** When the items of a block come in, seconds from its start (for the «clicks» of the sound). */
    fun beats(id: String, c: Content, dur: Double): List<Double> = when (id) {
        "bars" -> revealTimes(barsOf(c).map { it.label }, c.words, dur)
        "checklist" -> revealTimes(c.rows.map { it.label.ifEmpty { it.value } }.filter { it.isNotBlank() }.ifEmpty { listOf("1", "2", "3") }, c.words, dur)
        "compare" -> revealTimes(listOf(c.rows.getOrNull(0)?.label.orEmpty(), c.rows.getOrNull(1)?.label.orEmpty()), c.words, dur)
        else -> listOf(0.0)
    }
}

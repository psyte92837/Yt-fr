/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A theme of the inserts of a video (protocol lumen.theme v1): design tokens for colour, type, shape, motion and effects.
 * One theme recolours schemes, captions, native motion blocks and animations (they read data.theme) without AI.
 */
data class EditTheme(
    val id: String, val name: String,
    val bg: Int, val surface: Int, val ink: Int, val muted: Int, val accent: Int, val accent2: Int,
    val good: Int = 0xFF34C759.toInt(), val bad: Int = 0xFFFF453A.toInt(),
    /** sans | condensed | serif | mono | rounded, or "file:<name>" — a font file of the project. */
    val font: String = "sans", val weight: Int = 800, val scale: Float = 1f, val upper: Boolean = false, val tracking: Float = 0f,
    /** none | s | m | l */
    val radius: String = "m", val stroke: Float = 1f,
    /** none | glass | semi | solid | full */
    val plate: String = "semi", val plateAlpha: Int = 72, val shadow: Boolean = true,
    /** calm | normal | drive */
    val pace: String = "normal", val ease: String = "out", val stagger: Float = 0.12f,
    val animIn: String = "pop", val animOut: String = "fade", val durScale: Float = 1f,
    val glow: Float = 0f, val grain: Float = 0f, val gradient: Boolean = false,
    val builtIn: Boolean = false
)

/** A problem of the style unity of the inserts of a video. */
data class StyleIssue(val text: String, val ids: List<String>)

object EditThemes {
    const val PROTOCOL = "lumen.theme v1"

    private fun c(v: Long) = v.toInt()

    val PRESETS = listOf(
        EditTheme("minimal", "Минимал", c(0xFF000000), c(0xFF111318), c(0xFFFFFFFF), c(0xFFD0D4DB), c(0xFFFFD60A), c(0xFFFFFFFF),
            plate = "none", plateAlpha = 0, shadow = true, radius = "s", animIn = "fade", builtIn = true),
        EditTheme("glass", "Стекло", c(0xFF0E1116), c(0xFF1A1F27), c(0xFFFFFFFF), c(0xFFDCE2EA), c(0xFFFFD60A), c(0xFF64D2FF),
            plate = "glass", plateAlpha = 40, radius = "l", builtIn = true),
        EditTheme("neon", "Неон", c(0xFF05070D), c(0xFF0B0F1A), c(0xFFE6F1FF), c(0xFF8FA3BF), c(0xFF00E5FF), c(0xFFFF2E88),
            font = "condensed", upper = true, radius = "s", stroke = 1.6f, plate = "solid", plateAlpha = 85, pace = "drive", ease = "back", glow = 0.6f, builtIn = true),
        EditTheme("paper", "Бумага", c(0xFFEDE7DA), c(0xFFFAF7F0), c(0xFF16181D), c(0xFF5B6270), c(0xFFE4572E), c(0xFF2E86AB),
            font = "serif", weight = 700, radius = "s", plate = "solid", plateAlpha = 96, pace = "calm", animIn = "fade", builtIn = true),
        EditTheme("science", "Научпоп", c(0xFF081018), c(0xFF0F1720), c(0xFFF4F7FB), c(0xFFA9B6C6), c(0xFF36C2FF), c(0xFFFFC857),
            plate = "semi", plateAlpha = 82, builtIn = true),
        EditTheme("business", "Деловой", c(0xFFE9EEF5), c(0xFFFFFFFF), c(0xFF1B2A41), c(0xFF5C6B80), c(0xFF1E6FD9), c(0xFF00A88F),
            weight = 700, radius = "s", plate = "solid", plateAlpha = 95, pace = "calm", animIn = "slide", builtIn = true),
        EditTheme("gloss", "Глянец", c(0xFF000000), c(0xFF1C1C1E), c(0xFFFFFFFF), c(0xFFAEAEB2), c(0xFF0A84FF), c(0xFFFF375F),
            radius = "l", plate = "glass", plateAlpha = 60, ease = "inOut", builtIn = true),
        EditTheme("brutal", "Брутализм", c(0xFF000000), c(0xFFF2F2F2), c(0xFF000000), c(0xFF333333), c(0xFFFF3B00), c(0xFF0047FF),
            font = "mono", weight = 900, upper = true, radius = "none", stroke = 3f, plate = "solid", plateAlpha = 100, shadow = false, pace = "drive", ease = "linear", builtIn = true),
        EditTheme("kids", "Детский", c(0xFFFFF4E6), c(0xFFFFFFFF), c(0xFF2B2D42), c(0xFF6C6F85), c(0xFFFF6B6B), c(0xFF4ECDC4),
            font = "rounded", radius = "l", stroke = 2f, plate = "solid", plateAlpha = 96, pace = "drive", ease = "back", builtIn = true),
        EditTheme("retro", "Ретро", c(0xFF1A110E), c(0xFF2B1B17), c(0xFFF6E7C1), c(0xFFC9A66B), c(0xFFF2A541), c(0xFFE4572E),
            font = "serif", radius = "s", plate = "solid", plateAlpha = 90, grain = 0.3f, animIn = "fade", builtIn = true),
        EditTheme("cyber", "Киберпанк", c(0xFF05010F), c(0xFF0D0221), c(0xFFF5F5F5), c(0xFFB8B8D1), c(0xFFFF2A6D), c(0xFF05D9E8),
            font = "mono", upper = true, radius = "none", stroke = 1.6f, plate = "solid", plateAlpha = 88, pace = "drive", glow = 0.8f, builtIn = true),
        EditTheme("podcast", "Подкаст", c(0xFF0A0A0A), c(0xFF121212), c(0xFFFFFFFF), c(0xFFB3B3B3), c(0xFF1DB954), c(0xFFFFFFFF),
            plate = "semi", plateAlpha = 78, pace = "calm", animIn = "fade", builtIn = true)
    ).map { enforce(it) }

    fun preset(id: String): EditTheme? = PRESETS.firstOrNull { it.id == id }

    /** Built-in themes and the user's (files/lumen/studio/themes/<id>.json). */
    fun list(dir: File?): List<EditTheme> = PRESETS + (dir?.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedBy { it.lastModified() }.mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() })

    fun find(dir: File?, id: String): EditTheme? = preset(id) ?: dir?.let { d -> File(d, "$id.json").takeIf { it.exists() }?.let { runCatching { parse(it.readText()) }.getOrNull() } }

    fun save(dir: File, t: EditTheme): EditTheme {
        dir.mkdirs()
        val own = if (t.builtIn || preset(t.id) != null) t.copy(id = "t" + java.lang.Long.toString(System.currentTimeMillis(), 36), builtIn = false) else t
        File(dir, own.id + ".json").writeText(json(own).toString())
        return own
    }

    // ---------- JSON ----------

    fun hex(c: Int): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    fun color(s: String?, d: Int): Int {
        val v = s?.trim().orEmpty().removePrefix("#")
        val n = v.toLongOrNull(16) ?: return d
        return when (v.length) {
            6 -> (0xFF000000 or n).toInt()
            8 -> n.toInt()
            3 -> {
                val r = (n shr 8) and 0xF
                val g = (n shr 4) and 0xF
                val b = n and 0xF
                (0xFF000000 or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toInt()
            }
            else -> d
        }
    }

    fun json(t: EditTheme): JSONObject = JSONObject().put("protocol", PROTOCOL).put("id", t.id).put("name", t.name)
        .put("color", JSONObject().put("bg", hex(t.bg)).put("surface", hex(t.surface)).put("ink", hex(t.ink)).put("muted", hex(t.muted))
            .put("accent", hex(t.accent)).put("accent2", hex(t.accent2)).put("good", hex(t.good)).put("bad", hex(t.bad)))
        .put("type", JSONObject().put("family", t.font).put("weight", t.weight).put("scale", t.scale.toDouble()).put("upper", t.upper).put("tracking", t.tracking.toDouble()))
        .put("shape", JSONObject().put("radius", t.radius).put("stroke", t.stroke.toDouble()).put("plate", t.plate).put("plateAlpha", t.plateAlpha).put("shadow", t.shadow))
        .put("motion", JSONObject().put("pace", t.pace).put("ease", t.ease).put("stagger", t.stagger.toDouble()).put("in", t.animIn).put("out", t.animOut).put("durScale", t.durScale.toDouble()))
        .put("fx", JSONObject().put("glow", t.glow.toDouble()).put("grain", t.grain.toDouble()).put("gradient", t.gradient))

    /** A theme from JSON (an AI answer may wrap it in a code fence); missing tokens fall back to "Научпоп". */
    fun parse(s: String): EditTheme? {
        val body = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(RelayText.tidyJson(body)) }.getOrNull()
            ?: body.indexOf('{').takeIf { it >= 0 }?.let { a -> runCatching { JSONObject(RelayText.tidyJson(body.substring(a, body.lastIndexOf('}') + 1))) }.getOrNull() }
            ?: return null
        val base = PRESETS.first { it.id == "science" }
        val col = o.optJSONObject("color") ?: JSONObject()
        val ty = o.optJSONObject("type") ?: JSONObject()
        val sh = o.optJSONObject("shape") ?: JSONObject()
        val mo = o.optJSONObject("motion") ?: JSONObject()
        val fx = o.optJSONObject("fx") ?: JSONObject()
        fun one(v: String, ok: Collection<String>, d: String) = ok.firstOrNull { it.equals(v.trim(), ignoreCase = true) } ?: d
        val font = ty.optString("family").trim().let { f -> if (f.startsWith("file:")) f else one(f, SchemeStyles.FONTS, base.font) }
        return EditTheme(
            o.optString("id").trim().ifEmpty { "t" + Integer.toHexString(body.hashCode() and 0xFFFFFF) }.take(24),
            o.optString("name").trim().ifEmpty { "Тема" }.take(30),
            color(col.optString("bg"), base.bg), color(col.optString("surface"), base.surface), color(col.optString("ink"), base.ink),
            color(col.optString("muted"), base.muted), color(col.optString("accent"), base.accent), color(col.optString("accent2"), base.accent2),
            color(col.optString("good"), base.good), color(col.optString("bad"), base.bad),
            font, ty.optInt("weight", base.weight).coerceIn(300, 900), ty.optDouble("scale", 1.0).toFloat().coerceIn(0.7f, 1.6f),
            ty.optBoolean("upper", false), ty.optDouble("tracking", 0.0).toFloat().coerceIn(-0.05f, 0.3f),
            one(sh.optString("radius"), listOf("none", "s", "m", "l"), "m"), sh.optDouble("stroke", 1.0).toFloat().coerceIn(0f, 4f),
            one(sh.optString("plate"), listOf("none", "glass", "semi", "solid", "full"), "semi"), sh.optInt("plateAlpha", 72).coerceIn(0, 100), sh.optBoolean("shadow", true),
            one(mo.optString("pace"), listOf("calm", "normal", "drive"), "normal"), one(mo.optString("ease"), listOf("out", "inOut", "back", "linear", "elastic"), "out"),
            mo.optDouble("stagger", 0.12).toFloat().coerceIn(0f, 0.6f),
            one(mo.optString("in"), StudioEdit.ANIMS, "pop"), one(mo.optString("out"), StudioEdit.ANIMS, "fade"), mo.optDouble("durScale", 1.0).toFloat().coerceIn(0.5f, 2f),
            fx.optDouble("glow", 0.0).toFloat().coerceIn(0f, 1f), fx.optDouble("grain", 0.0).toFloat().coerceIn(0f, 1f), fx.optBoolean("gradient", false)
        ).let { enforce(it) }
    }

    // ---------- contrast ----------

    private fun ch(c: Int, s: Int) = (c shr s) and 0xFF

    /** [c] mixed with [to] by [k] (0..1), opaque. */
    fun mix(c: Int, to: Int, k: Float): Int {
        fun m(s: Int) = (ch(c, s) + (ch(to, s) - ch(c, s)) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** [c] pushed towards white or black (away from [bg]) until it has [ratio] contrast to [bg]. */
    fun readable(c: Int, bg: Int, ratio: Double): Int {
        if (SchemeStyles.contrast(c, bg) >= ratio) return c or (0xFF shl 24)
        // the least change towards white or black that is enough; on a mid-grey plate only one of them may be
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val first = if (SchemeStyles.luminance(bg) > 0.18) black else white
        for (to in listOf(first, if (first == black) white else black)) {
            var k = 0.1f
            while (k <= 1.001f) {
                val m = mix(c, to, k)
                if (SchemeStyles.contrast(m, bg) >= ratio) return m
                k += 0.1f
            }
        }
        return if (SchemeStyles.contrast(white, bg) >= SchemeStyles.contrast(black, bg)) white else black
    }

    /** Text 4.5:1 to the plate, captions and accents 3:1 (WCAG AA). */
    fun enforce(t: EditTheme): EditTheme = t.copy(
        ink = readable(t.ink, t.surface, 4.5), muted = readable(t.muted, t.surface, 3.0),
        accent = readable(t.accent, t.surface, 3.0), accent2 = readable(t.accent2, t.surface, 3.0)
    )

    // ---------- the theme on blocks ----------

    /** The scheme style of a theme (native schemes). */
    fun toScheme(t: EditTheme): SchemeStyle = SchemeStyle(
        "theme:" + t.id, t.name, card = (min(255, max(36, t.plateAlpha * 255 / 100)) shl 24) or (t.surface and 0xFFFFFF), ink = t.ink, muted = t.muted,
        accent = t.accent, line = (0xB3 shl 24) or (t.muted and 0xFFFFFF), bg = (0xE6 shl 24) or (t.surface and 0xFFFFFF),
        fill = when (t.plate) { "none" -> "none"; "glass" -> "glass"; else -> "solid" },
        radius = when (t.radius) { "none" -> 0f; "s" -> 0.08f; "l" -> 0.24f; else -> 0.14f }, border = t.stroke, shadow = t.shadow,
        font = if (t.font.startsWith("file:")) "sans" else t.font, upper = t.upper, textScale = t.scale,
        enter = when (t.animIn) { "fade" -> "fade"; "slide" -> "slide"; else -> "pop" }
    )

    /** The look over the style that a theme sets on a block. */
    fun toLook(t: EditTheme): BlockLook = BlockLook(t.plate, t.plateAlpha, t.accent, t.font, t.upper, if (t.scale != 1f) t.scale else 0f, t.radius, t.stroke > 0f, t.shadow)

    /** The CSS font family of a theme font. */
    fun cssFont(f: String): String = when {
        f.startsWith("file:") -> "LumenBrand, sans-serif"
        f == "serif" -> "serif"
        f == "mono" -> "monospace"
        f == "condensed" -> "sans-serif-condensed, sans-serif"
        else -> "sans-serif"
    }

    /** data.theme of an animation (lumen.anim v1.1): colours as CSS, the rest as numbers and words. */
    fun tokens(t: EditTheme): JSONObject = JSONObject().put("id", t.id)
        .put("bg", hex(t.bg)).put("surface", hex(t.surface)).put("ink", hex(t.ink)).put("muted", hex(t.muted))
        .put("accent", hex(t.accent)).put("accent2", hex(t.accent2)).put("good", hex(t.good)).put("bad", hex(t.bad))
        .put("font", cssFont(t.font)).put("weight", t.weight).put("upper", t.upper).put("scale", t.scale.toDouble())
        .put("radius", EditInsert.radiusOf(t.radius, 0.06f).toDouble()).put("stroke", t.stroke.toDouble())
        .put("plate", t.plate).put("plateAlpha", t.plateAlpha).put("shadow", t.shadow)
        .put("pace", t.pace).put("ease", t.ease).put("stagger", t.stagger.toDouble()).put("glow", t.glow.toDouble())

    /** A line for the request: what the theme is. */
    fun describe(t: EditTheme): String = "${t.name}: фон ${hex(t.surface)}, текст ${hex(t.ink)}, акцент ${hex(t.accent)}, второй ${hex(t.accent2)}, " +
        "шрифт ${t.font}${if (t.upper) " КАПС" else ""}, скругление ${t.radius}, подложка ${t.plate} ${t.plateAlpha} %, темп ${t.pace}"

    /**
     * The theme of a block: its own ([EditBrief.theme]), else the video's ([EProject.theme], a theme JSON), else none.
     * [dir] — the folder of the user's themes.
     */
    fun resolve(blockTheme: String, projectTheme: String, dir: File?): EditTheme? =
        blockTheme.takeIf { it.isNotBlank() }?.let { find(dir, it) } ?: projectTheme.takeIf { it.isNotBlank() }?.let { parse(it) }

    /**
     * Every insert of [blocks] in theme [t] (one undo step): schemes and captions get its look, animations and motion
     * blocks its id in the brief (their frames follow data.theme).
     */
    fun apply(blocks: List<EBlock>, t: EditTheme): List<EBlock> {
        val look = toLook(t)
        val js = json(t).toString()
        return blocks.map { b ->
            when (b.kind) {
                "scheme" -> b.copy(look = "", over = look, theme = js)
                "text" -> if (EditCaptions.isSub(b)) b.copy(over = BlockLook(accent = t.accent, font = t.font)) else b.copy(over = BlockLook(accent = t.accent, font = t.font, upper = t.upper))
                EditInsert.KIND, EditMotion.KIND, EditLottie.KIND -> {
                    val br = (EditInsert.briefFrom(b.brief) ?: EditBrief()).copy(theme = t.id)
                    b.copy(brief = EditInsert.briefJson(br).toString(), theme = js)
                }
                else -> b
            }
        }
    }

    // ---------- a theme from the colours of the video ----------

    private fun sat(c: Int): Float {
        val r = ch(c, 16) / 255f
        val g = ch(c, 8) / 255f
        val b = ch(c, 0) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return if (mx <= 0f) 0f else (mx - mn) / mx
    }

    /** The main colours of [pixels] (median cut into [k] boxes), the biggest first. */
    fun palette(pixels: IntArray, k: Int = 6): List<Int> {
        if (pixels.isEmpty()) return emptyList()
        var boxes = listOf(pixels.toList())
        while (boxes.size < k) {
            val i = boxes.indices.maxByOrNull { boxes[it].size } ?: break
            val box = boxes[i]
            if (box.size < 2) break
            fun range(s: Int) = box.maxOf { ch(it, s) } - box.minOf { ch(it, s) }
            val s = listOf(16, 8, 0).maxByOrNull { range(it) }!!
            val sorted = box.sortedBy { ch(it, s) }
            boxes = boxes.toMutableList().apply {
                removeAt(i)
                add(sorted.subList(0, sorted.size / 2))
                add(sorted.subList(sorted.size / 2, sorted.size))
            }
        }
        return boxes.sortedByDescending { it.size }.map { box ->
            val r = box.sumOf { ch(it, 16) } / box.size
            val g = box.sumOf { ch(it, 8) } / box.size
            val b = box.sumOf { ch(it, 0) } / box.size
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** A theme from the colours of the video: a dark plate from its darkest colour, the accents from its most vivid ones. */
    fun fromPalette(colors: List<Int>, name: String = "Из видео"): EditTheme {
        val base = PRESETS.first { it.id == "science" }
        if (colors.isEmpty()) return base.copy(id = "video", name = name, builtIn = false)
        val dark = colors.minByOrNull { SchemeStyles.luminance(it) }!!
        val surface = mix(dark, 0xFF000000.toInt(), 0.55f)
        val vivid = colors.sortedByDescending { sat(it) * (0.5 + SchemeStyles.luminance(it)) }
        val accent = vivid.firstOrNull { sat(it) > 0.25f } ?: base.accent
        val accent2 = vivid.firstOrNull { it != accent && sat(it) > 0.25f && abs(SchemeStyles.luminance(it) - SchemeStyles.luminance(accent)) > 0.05 } ?: base.accent2
        val ink = mix(0xFFFFFFFF.toInt(), accent, 0.06f)
        return enforce(base.copy(id = "video", name = name, bg = dark, surface = surface, ink = ink, muted = mix(ink, surface, 0.3f),
            accent = accent, accent2 = accent2, builtIn = false))
    }

    // ---------- unity of the style ----------

    /** Different fonts, accents or roundings among the inserts of a video (more than [maxEach] of a kind). */
    fun issues(blocks: List<EBlock>, maxEach: Int = 2): List<StyleIssue> {
        val ins = blocks.filter { it.kind == "scheme" || it.kind == "text" || it.kind == EditInsert.KIND || it.kind == EditMotion.KIND }
        if (ins.size < 2) return emptyList()
        val out = mutableListOf<StyleIssue>()
        fun check(name: String, key: (EBlock) -> String?) {
            val groups = ins.mapNotNull { b -> key(b)?.let { it to b.id } }.groupBy({ it.first }, { it.second })
            if (groups.size > maxEach) out += StyleIssue(t("Разных $name: ${groups.size}", "Different $name: ${groups.size}"), groups.values.flatten())
        }
        check(t("шрифтов", "fonts")) { b -> b.over?.font?.takeIf { it.isNotEmpty() } ?: "sans" }
        check(t("акцентов", "accents")) { b -> b.over?.accent?.let { hex(it) } }
        check(t("скруглений", "roundings")) { b -> if (b.kind == "text") null else b.over?.radius?.takeIf { it.isNotEmpty() } ?: "m" }
        check(t("тем у анимаций", "themes of animations")) { b -> if (b.kind == EditInsert.KIND) EditInsert.briefFrom(b.brief)?.theme?.ifEmpty { "-" } ?: "-" else null }
        return out
    }
}

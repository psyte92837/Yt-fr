/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Look of the info-schemes and how they sit in the video (protocol lumen.schemestyle v1). Colours are ARGB;
 * [accent] null = the accent colour of the video. [place]: overlay (over the picture, no backdrop), panel (a dimmed
 * rounded panel behind the scheme), side (the speaker's frame slides aside, the scheme takes the other part),
 * pip (the speaker shrinks into a corner, the scheme takes the frame).
 */
data class SchemeStyle(
    val id: String, val name: String,
    val card: Int = 0x29FFFFFF, val ink: Int = 0xFFFFFFFF.toInt(), val muted: Int = 0xCCFFFFFF.toInt(), val accent: Int? = null,
    val line: Int = 0xB3FFFFFF.toInt(), val bg: Int = 0xE6101318.toInt(),
    val fill: String = "glass", val radius: Float = 0.18f, val border: Float = 1f, val shadow: Boolean = true,
    val font: String = "sans", val upper: Boolean = false, val textScale: Float = 1f,
    val enter: String = "pop", val dur: Int = 320,
    val place: String = "overlay", val anchor: String = "auto", val size: Int = 0, val dim: Float = 0.45f,
    /** Built in (cannot be deleted) or made by the user / the AI. */
    val builtIn: Boolean = false
)

object SchemeStyles {
    val FILLS = listOf("solid", "glass", "outline", "none")
    val FONTS = listOf("sans", "condensed", "serif", "mono", "rounded")
    val ENTERS = listOf("pop", "fade", "slide", "draw")
    val PLACES = listOf("overlay", "panel", "side", "pip")
    val ANCHORS = listOf("auto", "left", "right", "top", "bottom")
    const val AUTO = "auto"

    /**
     * Built-in looks, made anew for the simple choice of the Studio (lumen.overlay): every look is designed as a solid
     * card that also works as glass and without a plate ([StudioOverlay.style]); the placement is a separate option.
     * "auto" follows the colours of the video style (dark, light, montage).
     */
    val PRESETS = listOf(
        SchemeStyle(AUTO, "Авто", builtIn = true),
        // white cards, graphite text: reads on any video
        SchemeStyle("clean", "Чистый", card = 0xFFFFFFFF.toInt(), ink = 0xFF14161B.toInt(), muted = 0xFF5B6270.toInt(), line = 0xFFD5DAE3.toInt(),
            fill = "solid", radius = 0.18f, border = 1f, font = "sans", enter = "pop", builtIn = true),
        // dark smoked cards, white text (the classic of podcasts and talking heads)
        SchemeStyle("glass", "Стекло", card = 0xB3161A22.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFC9CED8.toInt(), line = 0x66FFFFFF,
            fill = "glass", radius = 0.22f, border = 1f, font = "sans", enter = "fade", dur = 360, builtIn = true),
        // warm paper, serif: calm explanations, history, books
        SchemeStyle("paper", "Бумага", card = 0xFFFFF8EC.toInt(), ink = 0xFF2B2118.toInt(), muted = 0xFF6E5E4C.toInt(), accent = 0xFFB4532A.toInt(),
            line = 0xFFD9C7AE.toInt(), fill = "solid", radius = 0.10f, border = 1f, font = "serif", enter = "fade", dur = 420, builtIn = true),
        // dark with a cyan glow: tech, games, numbers
        SchemeStyle("neon", "Неон", card = 0xFF0B0F1A.toInt(), ink = 0xFFE6F1FF.toInt(), muted = 0xFF9FB3CF.toInt(), accent = 0xFF00E5FF.toInt(),
            line = 0xFF00E5FF.toInt(), fill = "solid", radius = 0.10f, border = 1.6f, font = "condensed", upper = true, enter = "draw", builtIn = true),
        // yellow plates, black capitals: fast Shorts, lists, "3 mistakes"
        SchemeStyle("bold", "Смело", card = 0xFFFFD60A.toInt(), ink = 0xFF111111.toInt(), muted = 0xFF3A3300.toInt(), accent = 0xFF111111.toInt(),
            line = 0xFF111111.toInt(), fill = "solid", radius = 0.08f, border = 0f, font = "condensed", upper = true, textScale = 1.08f,
            enter = "slide", dur = 260, builtIn = true),
        // soft lilac, rounded: lifestyle, psychology, education for kids
        SchemeStyle("soft", "Мягко", card = 0xFFF3EEFF.toInt(), ink = 0xFF2A2340.toInt(), muted = 0xFF5F5680.toInt(), accent = 0xFF6D4AFF.toInt(),
            line = 0xFFD9CFFF.toInt(), fill = "solid", radius = 0.35f, border = 1f, font = "rounded", enter = "pop", dur = 380, builtIn = true),
        // white bars with a red accent, square corners: news, facts, business
        SchemeStyle("news", "Новости", card = 0xFFFFFFFF.toInt(), ink = 0xFF0E1116.toInt(), muted = 0xFF4A505C.toInt(), accent = 0xFFD32F2F.toInt(),
            line = 0xFFD32F2F.toInt(), fill = "solid", radius = 0.03f, border = 0f, font = "condensed", upper = true, enter = "slide", dur = 280, builtIn = true),
        // text and thin lines, almost no plate: quiet design videos
        SchemeStyle("minimal", "Минимал", card = 0xFF0F1115.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFDADDE3.toInt(), line = 0x99FFFFFF.toInt(),
            fill = "solid", radius = 0.12f, border = 0.6f, font = "sans", textScale = 0.96f, enter = "fade", dur = 400, builtIn = true)
    )

    /** Ids of the old built-in looks → the new ones (saved projects and settings keep working). */
    fun alias(id: String): String = when (id) {
        "podcast_side", "podcast_pip" -> "glass"
        else -> id
    }

    /** Rules from motion-design practice: in the AI prompt and on the screen. */
    const val RULES = "Правила хорошей инфографики в видео: " +
        "1) контраст текста к подложке не ниже 4.5:1 (WCAG AA), крупных цифр — 3:1; на живом видео — подложка, затемнение или тень; " +
        "2) цвета по схеме 60-30-10: подложка 60%, текст и линии 30%, акцент 10%; один акцент, максимум ещё один дополнительный; " +
        "3) один шрифт, не больше двух начертаний; подписи до 3 слов, не больше 5-7 блоков; " +
        "4) безопасные поля 5% по краям; в Shorts нижние 20% и правые 15% закрывает интерфейс; " +
        "5) не закрывать лицо говорящего: схема на другой стороне кадра, сдвиг кадра (side) или спикер в углу (pip); " +
        "6) блоки появляются вместе с речью, 200-400 мс, плавное замедление, одинаковое движение у всех блоков; " +
        "7) схема держится не меньше времени чтения (~3 слова в секунду); " +
        "8) скругления 8-20% стороны блока, тонкие обводки, мягкая тень, чтобы отделить блоки от видео; " +
        "9) для подкаста и нарезки из говорящих голов лучше side или pip, для коротких фактов — panel, на спокойном фоне — overlay."

    /** Description of the protocol for the prompts. */
    const val PROTOCOL = "Протокол lumen.schemestyle v1 — стиль инфосхем, JSON: " +
        "{\"name\":\"2-3 слова\",\"card\":\"#AARRGGBB подложка блока\",\"ink\":\"#RRGGBB текст\",\"muted\":\"#RRGGBB подписи и стрелки\"," +
        "\"accent\":\"#RRGGBB или пусто (= акцент ролика)\",\"line\":\"#AARRGGBB обводка\",\"bg\":\"#AARRGGBB фон панели/кадра для panel, side, pip\"," +
        "\"fill\":\"solid|glass|outline|none\",\"radius\":0.0-0.5,\"border\":0-3,\"shadow\":true," +
        "\"font\":\"sans|condensed|serif|mono|rounded\",\"upper\":false,\"textScale\":0.8-1.3," +
        "\"enter\":\"pop|fade|slide|draw\",\"dur\":200-500," +
        "\"place\":\"overlay|panel|side|pip\",\"anchor\":\"auto|left|right|top|bottom\",\"size\":0 или 30-100 (% размера, 0 = как в настройках),\"dim\":0.0-0.85}. " +
        "glass — полупрозрачная подложка, outline — только обводка, none — без подложки (тогда нужен place panel или dim). " +
        "overlay — поверх видео без фона; panel — затемнённая скруглённая панель под схемой; side — кадр со спикером сдвигается вбок, схема на другой части; " +
        "pip — спикер уходит в угол картинкой в картинке, схема на весь кадр на фоне bg."

    // ---------- colours ----------

    fun parseColor(s: String?): Int? {
        val t = s?.trim()?.removePrefix("#") ?: return null
        if (!t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (t.length) {
            6 -> (0xFF000000 or t.toLong(16)).toInt()
            8 -> t.toLong(16).toInt()
            3 -> (0xFF000000 or t.map { "$it$it" }.joinToString("").toLong(16)).toInt()
            else -> null
        }
    }

    fun hex(c: Int): String = "#" + String.format("%08X", c)

    private fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF

    /** Colour [top] (with its alpha) over the opaque [under]. */
    fun over(top: Int, under: Int): Int {
        val a = ch(top, 24) / 255f
        fun mix(s: Int) = (ch(top, s) * a + ch(under, s) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Relative luminance (WCAG). */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(ch(c, 16)) + 0.7152 * lin(ch(c, 8)) + 0.0722 * lin(ch(c, 0))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a or (0xFF shl 24))
        val lb = luminance(b or (0xFF shl 24))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * The surface text sits on: the card over the backdrop (panel bg or the video under the montage's shade, taken
     * as a dark grey), so contrast is checked against what the viewer really sees.
     */
    fun surface(s: SchemeStyle): Int {
        val video = 0xFF404040.toInt()
        val behind = when (s.place) {
            "pip" -> over(s.bg, video)
            "panel", "side" -> over((s.bg and 0x00FFFFFF) or ((s.dim.coerceIn(0f, 1f) * 255).toInt() shl 24), video)
            else -> video
        }
        return if (s.fill == "solid" || s.fill == "glass") over(s.card, behind) else behind
    }

    private fun pick(bgc: Int): Int = if (contrast(0xFFFFFFFF.toInt(), bgc) >= contrast(0xFF101010.toInt(), bgc)) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()

    /**
     * Applies the rules that can be checked by numbers: text contrast 4.5:1, captions and accent 3:1 (else the
     * colour is replaced), a backdrop when the blocks have no fill over a live video. Returns the style and what was changed.
     */
    fun enforce(s0: SchemeStyle, bare: Boolean = false): Pair<SchemeStyle, List<String>> {
        var s = s0
        val notes = mutableListOf<String>()
        // [bare]: a block without a plate was chosen on purpose (the painter gives its text a shadow)
        if (!bare && s.fill in listOf("none", "outline") && s.place == "overlay") {
            s = s.copy(place = "panel", dim = max(s.dim, 0.45f))
            notes += "без подложки на видео текст не читается: включена панель с затемнением"
        }
        val surf = surface(s)
        if (contrast(s.ink, surf) < 4.5) {
            s = s.copy(ink = pick(surf))
            notes += "контраст текста был ниже 4.5:1 — цвет текста заменён"
        }
        if (contrast(s.muted, surf) < 3.0) {
            s = s.copy(muted = (s.ink and 0x00FFFFFF) or (0xD9 shl 24))
            notes += "подписи и стрелки были бледными — сделаны темнее/светлее"
        }
        s.accent?.let { a ->
            if (contrast(a, surf) < 3.0) {
                s = s.copy(accent = null)
                notes += "акцент сливался с подложкой — берётся акцент ролика"
            }
        }
        return s to notes
    }

    // ---------- JSON ----------

    private fun f(o: JSONObject, k: String, def: Float, lo: Float, hi: Float) = (if (o.has(k)) o.optDouble(k, def.toDouble()).toFloat() else def).coerceIn(lo, hi)
    private fun pickOf(o: JSONObject, k: String, list: List<String>, def: String) = o.optString(k).trim().lowercase().takeIf { it in list } ?: def

    fun fromJson(o: JSONObject, id: String = ""): SchemeStyle {
        val d = SchemeStyle("", "")
        return SchemeStyle(
            id = id.ifEmpty { o.optString("id").trim() }.ifEmpty { "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) },
            name = o.optString("name").trim().take(40).ifEmpty { "Мой стиль" },
            card = parseColor(o.optString("card")) ?: d.card, ink = parseColor(o.optString("ink")) ?: d.ink,
            muted = parseColor(o.optString("muted")) ?: d.muted, accent = parseColor(o.optString("accent")),
            line = parseColor(o.optString("line")) ?: d.line, bg = parseColor(o.optString("bg")) ?: d.bg,
            fill = pickOf(o, "fill", FILLS, d.fill), radius = f(o, "radius", d.radius, 0f, 0.5f), border = f(o, "border", d.border, 0f, 3f),
            shadow = o.optBoolean("shadow", d.shadow), font = pickOf(o, "font", FONTS, d.font), upper = o.optBoolean("upper", false),
            textScale = f(o, "textScale", 1f, 0.8f, 1.3f), enter = pickOf(o, "enter", ENTERS, d.enter),
            dur = o.optInt("dur", d.dur).coerceIn(150, 600), place = pickOf(o, "place", PLACES, d.place),
            anchor = pickOf(o, "anchor", ANCHORS, d.anchor), size = o.optInt("size", 0).let { if (it <= 0) 0 else it.coerceIn(30, 100) },
            dim = f(o, "dim", d.dim, 0f, 0.85f)
        )
    }

    fun fromJson(s: String, id: String = ""): SchemeStyle? = runCatching {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        fromJson(JSONObject(t), id)
    }.getOrNull()

    fun toJson(s: SchemeStyle): JSONObject = JSONObject().put("id", s.id).put("name", s.name)
        .put("card", hex(s.card)).put("ink", hex(s.ink)).put("muted", hex(s.muted)).put("accent", s.accent?.let { hex(it) } ?: "")
        .put("line", hex(s.line)).put("bg", hex(s.bg)).put("fill", s.fill).put("radius", s.radius.toDouble()).put("border", s.border.toDouble())
        .put("shadow", s.shadow).put("font", s.font).put("upper", s.upper).put("textScale", s.textScale.toDouble())
        .put("enter", s.enter).put("dur", s.dur).put("place", s.place).put("anchor", s.anchor).put("size", s.size).put("dim", s.dim.toDouble())

    // ---------- placement ----------

    /**
     * Where the scheme goes for [place] in a frame of [ratio] (width / height), fractions of the frame: the box of the
     * scheme and the speaker's frame (for side and pip; null = untouched). [subs] keeps the bottom free for subtitles.
     */
    fun layout(place: String, anchor: String, ratio: Float, subs: Boolean): Pair<SchBox, SchBox?> {
        val wide = ratio >= 1.2f
        val bottom = if (subs) 0.70f else 0.92f
        return when (place) {
            "side" -> if (wide) {
                // the panel on the right (or left), the speaker slides to the other side
                val left = anchor == "left"
                SchBox(if (left) 0.25f else 0.75f, 0.47f, 0.44f, 0.80f) to SchBox(if (left) 0.62f else 0.38f, 0.5f, 1f, 1f)
            } else {
                // portrait: the scheme in the upper half, the speaker slides down
                SchBox(0.5f, 0.27f, 0.90f, 0.40f) to SchBox(0.5f, 0.70f, 1f, 1f)
            }
            "pip" -> if (wide) {
                val left = anchor == "left"
                SchBox(if (left) 0.62f else 0.38f, 0.48f, 0.68f, 0.82f) to SchBox(if (left) 0.17f else 0.83f, 0.20f, 0.28f, 0.28f)
            } else {
                SchBox(0.5f, (0.30f + bottom) / 2, 0.92f, bottom - 0.30f) to SchBox(0.5f, 0.15f, 0.42f, 0.24f)
            }
            else -> {
                val top = anchor == "top"
                if (wide && (anchor == "left" || anchor == "right")) SchBox(if (anchor == "left") 0.3f else 0.7f, 0.45f, 0.5f, 0.7f) to null
                else SchBox(0.5f, if (top || !subs) (if (subs) 0.33f else 0.5f) else 0.40f, if (wide) 0.80f else 0.92f, if (wide) 0.60f else 0.46f) to null
            }
        }
    }

    // ---------- store ----------

    fun list(dir: File): List<SchemeStyle> = PRESETS + (dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { f -> fromJson(f.readText(), f.name.removeSuffix(".json")) })

    fun find(dir: File, id: String): SchemeStyle? = PRESETS.firstOrNull { it.id == alias(id) } ?: File(dir, "$id.json").takeIf { it.exists() }?.let { fromJson(it.readText(), id) }

    fun save(dir: File, s: SchemeStyle) {
        dir.mkdirs()
        File(dir, s.id + ".json").writeText(toJson(s).toString(2))
    }

    fun delete(dir: File, id: String) {
        if (PRESETS.none { it.id == id }) File(dir, "$id.json").delete()
    }

    /** Free id from a name: latin letters and digits, unique among [taken]. */
    fun freeId(name: String, taken: Set<String>): String {
        val base = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16).ifEmpty { "style" }
        var id = base
        var k = 2
        while (id in taken || PRESETS.any { it.id == id }) id = base + k++
        return id
    }
}

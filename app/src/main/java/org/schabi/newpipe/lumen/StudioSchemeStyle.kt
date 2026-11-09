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
    val builtIn: Boolean = false,
    /**
     * Info-block motion ([InfoStyles], lumen.info v1): how every block comes in ("" = [enter]), how it lives on screen
     * ([idle]: none float pulse breathe wiggle shine glow), how the blocks are linked ([link]: arrow line dash dots none),
     * the least gap between the blocks coming in ([stagger], ms) and the opacity of the whole block ([alpha]).
     */
    val motion: String = "",
    val idle: String = "none",
    val link: String = "arrow",
    val stagger: Int = 0,
    val alpha: Float = 1f
) {
    /** The entrance of the blocks: the info-block motion, else the old four. */
    val move: String get() = motion.ifEmpty { enter }
}

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

    /**
     * More built-in looks for the ready scheme templates ([SchemeTemplates]) and the Editor: modern styles of
     * different fields (bento, neo-brutalism, glass 2.0, Swiss, blueprint, chalkboard, terminal, editorial…).
     * Not in the simple Studio choice ([PRESETS]), but found by id everywhere.
     */
    val EXTRA = listOf(
        // light grey tiles with big corners, no lines: product, SaaS, Apple-like keynotes
        SchemeStyle("bento", "Бенто", card = 0xFFF2F3F5.toInt(), ink = 0xFF111318.toInt(), muted = 0xFF5F6673.toInt(), accent = 0xFF2F6BFF.toInt(),
            line = 0xFFE1E4EA.toInt(), fill = "solid", radius = 0.30f, border = 0f, shadow = false, font = "sans", enter = "pop", builtIn = true),
        // white, black frames 3 px, hard shadow, capitals: startups, bold statements
        SchemeStyle("brutal", "Брутализм", card = 0xFFFFFFFF.toInt(), ink = 0xFF000000.toInt(), muted = 0xFF222222.toInt(), accent = 0xFFE64100.toInt(),
            line = 0xFF000000.toInt(), fill = "solid", radius = 0.02f, border = 3f, font = "condensed", upper = true, enter = "slide", dur = 240, builtIn = true),
        // violet glass with a light edge: AI, crypto, future
        SchemeStyle("aurora", "Аврора", card = 0xCC1B1240.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFD9CCFF.toInt(), accent = 0xFFB18CFF.toInt(),
            line = 0x80D9CCFF.toInt(), fill = "glass", radius = 0.24f, border = 1f, font = "sans", enter = "fade", dur = 380, builtIn = true),
        // white, red accent, square corners, capitals: design, architecture, clear facts
        SchemeStyle("swiss", "Швейцарский", card = 0xFFFFFFFF.toInt(), ink = 0xFF111111.toInt(), muted = 0xFF4D4D4D.toInt(), accent = 0xFFE30613.toInt(),
            line = 0xFF111111.toInt(), fill = "solid", radius = 0f, border = 0f, shadow = false, font = "condensed", upper = true, enter = "slide", builtIn = true),
        // blue sheet, white thin lines, mono: engineering, how things work
        SchemeStyle("blueprint", "Чертёж", card = 0xFF0D3B66.toInt(), ink = 0xFFEAF4FF.toInt(), muted = 0xFFB8D4EE.toInt(), accent = 0xFF7FDBFF.toInt(),
            line = 0xCCE8F4FF.toInt(), fill = "solid", radius = 0.04f, border = 1.2f, shadow = false, font = "mono", enter = "draw", dur = 420, builtIn = true),
        // green board, chalk handwriting: school, lessons, explanations
        SchemeStyle("chalk", "Мел", card = 0xFF233B2E.toInt(), ink = 0xFFF4F1E8.toInt(), muted = 0xFFCFD8CC.toInt(), accent = 0xFFFFD166.toInt(),
            line = 0xCCF4F1E8.toInt(), fill = "solid", radius = 0.08f, border = 1.4f, font = "lf:caveat", textScale = 1.12f, enter = "draw", builtIn = true),
        // black screen, green mono: code, hacking, data
        SchemeStyle("terminal", "Терминал", card = 0xFF0A0F0A.toInt(), ink = 0xFF39FF14.toInt(), muted = 0xFF9BE89B.toInt(), accent = 0xFF39FF14.toInt(),
            line = 0xFF2E7D32.toInt(), fill = "solid", radius = 0.03f, border = 1f, font = "mono", enter = "draw", dur = 300, builtIn = true),
        // peach cards, rounded letters: lifestyle, wellbeing, family
        SchemeStyle("pastel", "Пастель", card = 0xFFFFE9E1.toInt(), ink = 0xFF3A2A2A.toInt(), muted = 0xFF6E5555.toInt(), accent = 0xFFE5532E.toInt(),
            line = 0xFFF5CFC2.toInt(), fill = "solid", radius = 0.40f, border = 0f, font = "rounded", enter = "pop", dur = 380, builtIn = true),
        // black and gold serif: luxury, premium, personal brand
        SchemeStyle("luxury", "Люкс", card = 0xFF111111.toInt(), ink = 0xFFF5E6C8.toInt(), muted = 0xFFCDBB94.toInt(), accent = 0xFFC9A45C.toInt(),
            line = 0xFFC9A45C.toInt(), fill = "solid", radius = 0.06f, border = 0.8f, font = "serif", upper = true, enter = "fade", dur = 460, builtIn = true),
        // mint and green: health, ecology, growth
        SchemeStyle("mint", "Мята", card = 0xFFE6F7F1.toInt(), ink = 0xFF0E3B2E.toInt(), muted = 0xFF3F6B5C.toInt(), accent = 0xFF0E8F62.toInt(),
            line = 0xFFBFE6D6.toInt(), fill = "solid", radius = 0.22f, border = 0f, font = "sans", enter = "pop", builtIn = true),
        // deep blue, cyan accent: science, sea, calm tech
        SchemeStyle("ocean", "Океан", card = 0xFF0B2545.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFB9CCE4.toInt(), accent = 0xFF4CC9F0.toInt(),
            line = 0x994CC9F0.toInt(), fill = "solid", radius = 0.18f, border = 1f, font = "sans", enter = "fade", builtIn = true),
        // cream cards with dark frames: retro, history, craft
        SchemeStyle("retro", "Ретро", card = 0xFFF4E1C1.toInt(), ink = 0xFF2B1D0E.toInt(), muted = 0xFF5C4630.toInt(), accent = 0xFFB8401C.toInt(),
            line = 0xFF2B1D0E.toInt(), fill = "solid", radius = 0.14f, border = 2f, font = "lf:russoone", upper = true, enter = "pop", builtIn = true),
        // white with thick black frames, yellow accent: comics, kids, fun facts
        SchemeStyle("comic", "Комикс", card = 0xFFFFFFFF.toInt(), ink = 0xFF111111.toInt(), muted = 0xFF333333.toInt(), accent = 0xFFD84315.toInt(),
            line = 0xFF111111.toInt(), fill = "solid", radius = 0.12f, border = 2.6f, font = "lf:daysone", enter = "pop", dur = 260, builtIn = true),
        // lemon cards, round letters: children, school, games
        SchemeStyle("kids", "Детский", card = 0xFFFFF3B0.toInt(), ink = 0xFF2D2A6E.toInt(), muted = 0xFF4E4A8C.toInt(), accent = 0xFFD81B60.toInt(),
            line = 0xFFFFE066.toInt(), fill = "solid", radius = 0.45f, border = 0f, font = "lf:comfortaa", enter = "pop", dur = 400, builtIn = true),
        // dark with pixels: retro games
        SchemeStyle("pixel", "Пиксель", card = 0xFF1B1B2F.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFC8C8E0.toInt(), accent = 0xFFFFDD00.toInt(),
            line = 0xFFFFDD00.toInt(), fill = "solid", radius = 0f, border = 2f, font = "lf:pressstart2p", textScale = 0.8f, enter = "pop", dur = 200, builtIn = true),
        // warm paper, display serif, blue accent: essays, analysis, culture
        SchemeStyle("editorial", "Журнальный", card = 0xFFF7F5F0.toInt(), ink = 0xFF1A1A1A.toInt(), muted = 0xFF555048.toInt(), accent = 0xFF0047FF.toInt(),
            line = 0xFF1A1A1A.toInt(), fill = "solid", radius = 0.03f, border = 0.8f, shadow = false, font = "lf:yesevaone", enter = "fade", dur = 420, builtIn = true),
        // white and teal: medicine, health care, safety
        SchemeStyle("medical", "Медицина", card = 0xFFFFFFFF.toInt(), ink = 0xFF0B2540.toInt(), muted = 0xFF3D5670.toInt(), accent = 0xFF00838F.toInt(),
            line = 0xFFB2DFE3.toInt(), fill = "solid", radius = 0.16f, border = 1f, font = "sans", enter = "fade", builtIn = true),
        // slate dashboard with green: finance, markets, metrics
        SchemeStyle("finance", "Финансы", card = 0xFF0F172A.toInt(), ink = 0xFFE2E8F0.toInt(), muted = 0xFF94A3B8.toInt(), accent = 0xFF22C55E.toInt(),
            line = 0xFF334155.toInt(), fill = "solid", radius = 0.10f, border = 1f, font = "condensed", enter = "slide", dur = 280, builtIn = true),
        // black and red, sport capitals: sport, fitness, competition
        SchemeStyle("sport", "Спорт", card = 0xFF111111.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFD0D0D0.toInt(), accent = 0xFFFF3B30.toInt(),
            line = 0xFFFF3B30.toInt(), fill = "solid", radius = 0.06f, border = 0f, font = "lf:russoone", upper = true, enter = "slide", dur = 220, builtIn = true),
        // dark cards with a pink-orange edge: creators, music, events
        SchemeStyle("sunset", "Закат", card = 0xFF22142E.toInt(), ink = 0xFFFFFFFF.toInt(), muted = 0xFFF3C6D9.toInt(), accent = 0xFFFF7A59.toInt(),
            line = 0xCCFF5C8A.toInt(), fill = "solid", radius = 0.20f, border = 1.4f, font = "lf:unbounded", textScale = 0.92f, enter = "pop", builtIn = true)
    )

    /** Every built-in look: the simple choice first, then [EXTRA]. */
    val BUILT_IN: List<SchemeStyle> = PRESETS + EXTRA

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

    fun list(dir: File): List<SchemeStyle> = BUILT_IN + (dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { f -> fromJson(f.readText(), f.name.removeSuffix(".json")) })

    fun find(dir: File, id: String): SchemeStyle? = InfoStyles.look(id) ?: BUILT_IN.firstOrNull { it.id == alias(id) } ?: File(dir, "$id.json").takeIf { it.exists() }?.let { fromJson(it.readText(), id) }

    fun save(dir: File, s: SchemeStyle) {
        dir.mkdirs()
        File(dir, s.id + ".json").writeText(toJson(s).toString(2))
    }

    fun delete(dir: File, id: String) {
        if (BUILT_IN.none { it.id == id }) File(dir, "$id.json").delete()
    }

    /** Free id from a name: latin letters and digits, unique among [taken]. */
    fun freeId(name: String, taken: Set<String>): String {
        val base = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16).ifEmpty { "style" }
        var id = base
        var k = 2
        while (id in taken || BUILT_IN.any { it.id == id }) id = base + k++
        return id
    }
}

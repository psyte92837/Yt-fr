/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * A ready animated caption style (protocol lumen.textstyle v1): font, colours, plate, outline, shadow and the
 * motion by the spoken words. A caption block wears it as look "ts:<id>" ([TextStyles.LOOK]); its font, accent and
 * capitals can still be changed over it ([EBlock.over]).
 *
 * [plate]: none, box (rounded plate), pill, line (a plate under every line, TikTok style), band (full width strip),
 * outline (a frame), tag (a tilted sticker), under (a marker bar under every line). Colours are ARGB; [hot] null =
 * the accent of the video. [stroke] — outline width, a fraction of the letter size. [shadow]: none, drop, glow,
 * hard (an offset copy), long (a long flat shadow). [spacing] — letter spacing, em. [words] — words in a caption.
 */
data class TextStyle(
    val id: String, val name: String, val nameEn: String, val area: String,
    val font: String = "lf:montserrat",
    val plate: String = "none", val plateColor: Int = 0,
    val ink: Int = WHITE, val hot: Int? = null,
    val stroke: Float = 0f, val strokeColor: Int = BLACK,
    val shadow: String = "none", val shadowColor: Int = 0x99000000.toInt(),
    val upper: Boolean = true, val spacing: Float = 0f,
    val motion: String = "karaoke", val anim: String = "pop", val words: Int = 3, val tilt: Float = 0f
) {
    val look: String get() = TextStyles.LOOK + id

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

/**
 * The built-in caption styles: 100 looks in 10 areas, made after what works in short videos, podcasts, lessons,
 * news, films, games, lifestyle, business, comedy and calm videos. Rules they follow: the said word is marked by
 * colour, size or a plate (word-level motion keeps the eye on the text); 1–6 words a caption; on a live video the text
 * always has an outline, a shadow or a plate; one accent colour; contrast of the text to its plate at least 4.5:1.
 * Pure, no Android.
 */
object TextStyles {
    const val LOOK = "ts:"
    val PLATES = listOf("none", "box", "pill", "line", "band", "outline", "tag", "under")
    val SHADOWS = listOf("none", "drop", "glow", "hard", "long")
    /** Ids of the fonts in assets/lumen_fonts ([LumenFonts.ALL]). */
    private val FONT_IDS = setOf("montserrat", "oswald", "unbounded", "russoone", "rubikmonoone", "daysone", "comfortaa", "yesevaone", "caveat", "amaticsc", "pressstart2p")

    data class Area(val id: String, val name: String, val nameEn: String)

    val AREAS = listOf(
        Area("viral", "🔥 Вирусные Shorts", "🔥 Viral Shorts"),
        Area("podcast", "🎙 Подкаст и интервью", "🎙 Podcast & interview"),
        Area("edu", "🎓 Обучение", "🎓 Education"),
        Area("news", "📰 Новости и факты", "📰 News & facts"),
        Area("cinema", "🎬 Кино и документалка", "🎬 Film & documentary"),
        Area("gaming", "🎮 Игры и техно", "🎮 Gaming & tech"),
        Area("lifestyle", "🌸 Лайфстайл", "🌸 Lifestyle"),
        Area("business", "💼 Бизнес и деньги", "💼 Business & money"),
        Area("fun", "😂 Юмор и мемы", "😂 Comedy & memes"),
        Area("calm", "🌙 Спокойно и красиво", "🌙 Calm & aesthetic")
    )

    private fun c(v: Long): Int = v.toInt()

    private const val W = 0xFFFFFFFFL
    private const val K = 0xFF000000L

    val ALL: List<TextStyle> = listOf(
        // ---------- viral Shorts: big bold capitals, outline, the said word in a bright colour ----------
        TextStyle("viral_yellow", "Жёлтый хайлайт", "Yellow highlight", "viral", stroke = 0.16f, shadow = "drop", hot = c(0xFFFFD60A)),
        TextStyle("viral_green", "Зелёный хайлайт", "Green highlight", "viral", stroke = 0.16f, shadow = "drop", hot = c(0xFF3DFF6E)),
        TextStyle("viral_pop", "Выпрыгивание", "Pop in", "viral", stroke = 0.14f, hot = c(0xFFFFD60A), motion = "pop"),
        TextStyle("viral_box", "Плашка под словом", "Box under the word", "viral", stroke = 0.1f, hot = c(0xFF7C3AED), motion = "box"),
        TextStyle("viral_one", "Одно слово крупно", "One big word", "viral", font = "lf:unbounded", stroke = 0.12f, shadow = "drop",
            hot = c(0xFFFFD60A), motion = "stamp", words = 1),
        TextStyle("viral_bounce", "Прыгающее слово", "Bouncing word", "viral", font = "lf:daysone", stroke = 0.14f, hot = c(0xFFFF3B6B), motion = "bounce"),
        TextStyle("viral_red", "Красный удар", "Red punch", "viral", font = "lf:russoone", stroke = 0.14f, hot = c(0xFFFF2D2D), motion = "stamp", words = 2),
        TextStyle("viral_duo", "Две краски", "Duotone", "viral", stroke = 0.12f, shadow = "hard", shadowColor = c(0xFFFF2D55), hot = c(0xFF00E5FF)),
        TextStyle("viral_tiktok", "Классика TikTok", "Classic TikTok", "viral", font = "", plate = "line", plateColor = c(0xFFFFFFFF),
            ink = c(0xFF111111), hot = c(0xFFFF2D55), upper = false, motion = "", anim = "fade", words = 6),
        TextStyle("viral_focus", "Фокус на слове", "Word in focus", "viral", stroke = 0.12f, hot = c(0xFFFFD60A), motion = "focus"),

        // ---------- podcast and interview: calm, readable lines, the voice marked softly ----------
        TextStyle("pod_clean", "Чистый подкаст", "Clean podcast", "podcast", shadow = "drop", hot = c(0xFFFFD60A), upper = false, anim = "fade", words = 6),
        TextStyle("pod_glass", "Стеклянная плашка", "Glass plate", "podcast", font = "sans", plate = "box", plateColor = c(0xB3101318),
            hot = c(0xFF8AB4FF), upper = false, anim = "fade", words = 6),
        TextStyle("pod_pill", "Капсула", "Capsule", "podcast", plate = "pill", plateColor = c(0xF2FFFFFF), ink = c(0xFF111111), hot = c(0xFFE8480C),
            upper = false, words = 4),
        TextStyle("pod_under", "Подчёркивание", "Underline", "podcast", shadow = "drop", hot = c(0xFFFFD60A), upper = false, motion = "underline", words = 4),
        TextStyle("pod_quote", "Цитата", "Quote", "podcast", font = "lf:yesevaone", shadow = "drop", hot = c(0xFFF5C26B), upper = false,
            motion = "word", anim = "fade", words = 6),
        TextStyle("pod_marker", "Маркер", "Highlighter", "podcast", stroke = 0.1f, hot = c(0xFFFF3D7F), upper = false, motion = "marker", words = 4),
        TextStyle("pod_lower", "Нижняя полоса", "Lower band", "podcast", font = "lf:oswald", plate = "band", plateColor = c(0xD9000000),
            hot = c(0xFFFFD60A), anim = "slide", words = 6),
        TextStyle("pod_soft", "Мягкая подсветка", "Soft light", "podcast", font = "lf:comfortaa", plate = "pill", plateColor = c(0xF2FFFFFF),
            ink = c(0xFF222222), hot = c(0xFF6A3DE8), upper = false, words = 4),
        TextStyle("pod_focus", "Затемнение лишнего", "Dim the rest", "podcast", shadow = "drop", hot = c(W), upper = false, motion = "focus", words = 6),
        TextStyle("pod_glow", "Свечение голоса", "Voice glow", "podcast", shadow = "drop", hot = c(0xFF8AF3FF), upper = false, motion = "glow", words = 4),

        // ---------- education: notes, boards, steps, a marker on the key term ----------
        TextStyle("edu_note", "Конспект", "Notebook", "edu", font = "lf:caveat", plate = "box", plateColor = c(0xF7FFF8E1), ink = c(0xFF2B2B2B),
            hot = c(0xFFD32F2F), upper = false, words = 4, tilt = -2f),
        TextStyle("edu_marker", "Маркер-выделение", "Marker", "edu", stroke = 0.12f, hot = c(0xFFFFEB3B), upper = false, motion = "marker", words = 4),
        TextStyle("edu_steps", "Шаги", "Steps", "edu", font = "lf:oswald", plate = "box", plateColor = c(0xF21565C0), hot = c(0xFFFFD54F),
            anim = "slide"),
        TextStyle("edu_board", "Доска", "Chalkboard", "edu", font = "lf:caveat", plate = "box", plateColor = c(0xF21E3A2F), ink = c(0xFFF4F1E8),
            hot = c(0xFFFFD166), upper = false, motion = "word", words = 5),
        TextStyle("edu_type", "Печатная машинка", "Typewriter", "edu", font = "mono", plate = "box", plateColor = c(0xE6111111), ink = c(0xFFE8E8E8),
            hot = c(0xFF7CFF6B), upper = false, motion = "type", anim = "none", words = 5),
        TextStyle("edu_kid", "Для детей", "For kids", "edu", font = "lf:comfortaa", plate = "pill", plateColor = c(0xFFFFE066), ink = c(0xFF2D2A6E),
            hot = c(0xFFD81B60), upper = false, motion = "bounce"),
        TextStyle("edu_formula", "Формула", "Formula", "edu", font = "serif", shadow = "drop", hot = c(0xFF7FDBFF), upper = false, motion = "word", words = 4),
        TextStyle("edu_check", "Чек-лист", "Checklist", "edu", plate = "box", plateColor = c(0xF2FFFFFF), ink = c(0xFF111111), hot = c(0xFF15803D),
            upper = false, words = 4),
        TextStyle("edu_book", "Учебник", "Textbook", "edu", font = "lf:yesevaone", shadow = "drop", hot = c(0xFFFFC857), upper = false,
            motion = "underline", words = 4),
        TextStyle("edu_wave", "Волна знаний", "Wave of knowledge", "edu", font = "lf:daysone", stroke = 0.1f, hot = c(0xFF4FC3F7), motion = "wave", words = 4),

        // ---------- news and facts: strict strips, a red accent, numbers that hit ----------
        TextStyle("news_red", "Срочно", "Breaking", "news", font = "lf:oswald", plate = "band", plateColor = c(0xF2C62828), hot = c(0xFFFFE082),
            anim = "slide", words = 6),
        TextStyle("news_white", "Белая лента", "White strip", "news", font = "lf:oswald", plate = "line", plateColor = c(0xF7FFFFFF), ink = c(0xFF0E1116),
            hot = c(0xFFC62828), anim = "slide", words = 5),
        TextStyle("news_fact", "Факт", "Fact", "news", font = "lf:russoone", plate = "box", plateColor = c(0xFF0E1116), hot = c(0xFFFF5252),
            motion = "stamp", words = 2),
        TextStyle("news_ticker", "Бегущая строка", "Ticker", "news", font = "condensed", plate = "band", plateColor = c(0xF2FFD600), ink = c(0xFF111111),
            hot = c(0xFFB71C1C), motion = "slide", anim = "slide", words = 6),
        TextStyle("news_quote", "Прямая речь", "Direct speech", "news", font = "serif", plate = "box", plateColor = c(0xD9101318), hot = c(0xFFFFD180),
            upper = false, motion = "word", anim = "fade", words = 6),
        TextStyle("news_data", "Цифры", "Numbers", "news", font = "lf:rubikmonoone", stroke = 0.1f, hot = c(0xFF00E676), motion = "stamp", words = 2),
        TextStyle("news_probe", "Расследование", "Investigation", "news", font = "mono", plate = "box", plateColor = c(0xE6000000), ink = c(0xFFF5F5F5),
            hot = c(0xFFFF1744), upper = false, motion = "type", words = 5),
        TextStyle("news_tag", "Метка", "Tag", "news", plate = "tag", plateColor = c(0xFFFFD60A), ink = c(0xFF111111), hot = c(0xFFB71C1C),
            motion = "pop", words = 2, tilt = -3f),
        TextStyle("news_blue", "Деловой синий", "Business blue", "news", font = "lf:oswald", plate = "line", plateColor = c(0xF21565C0),
            hot = c(0xFFFFD54F), words = 5),
        TextStyle("news_strict", "Строго", "Strict", "news", font = "sans", shadow = "drop", hot = c(0xFF90CAF9), upper = false, motion = "",
            anim = "fade", words = 6),

        // ---------- film and documentary: spaced titles, serif, slow fades ----------
        TextStyle("cine_title", "Титр", "Title card", "cinema", font = "lf:yesevaone", shadow = "drop", hot = c(0xFFF5E6C8), spacing = 0.12f,
            motion = "word", anim = "fade"),
        TextStyle("cine_subs", "Киносубтитры", "Film subtitles", "cinema", font = "sans", stroke = 0.08f, shadow = "drop", hot = c(0xFFFFF59D),
            upper = false, motion = "", anim = "fade", words = 6),
        TextStyle("cine_doc", "Документалка", "Documentary", "cinema", font = "lf:oswald", shadow = "drop", hot = c(0xFFE0C097), spacing = 0.2f,
            motion = "slide", words = 4),
        TextStyle("cine_noir", "Нуар", "Noir", "cinema", font = "serif", plate = "band", plateColor = c(0xE6000000), ink = c(0xFFEDEDED),
            hot = c(0xFFE53935), spacing = 0.1f, motion = "word", anim = "fade", words = 4),
        TextStyle("cine_gold", "Золото", "Gold", "cinema", font = "lf:yesevaone", ink = c(0xFFF5D78E), hot = c(0xFFFFF3C4), shadow = "glow",
            shadowColor = c(0x99C9A45C), motion = "glow"),
        TextStyle("cine_horror", "Ужас", "Horror", "cinema", font = "lf:amaticsc", ink = c(0xFFE8E8E8), hot = c(0xFFFF1744), shadow = "glow",
            shadowColor = c(0xCCB00020), motion = "shake"),
        TextStyle("cine_trailer", "Трейлер", "Trailer", "cinema", font = "lf:unbounded", hot = c(W), shadow = "glow", shadowColor = c(0x66FFFFFF),
            spacing = 0.15f, motion = "stamp", words = 2),
        TextStyle("cine_wide", "Широкий экран", "Widescreen", "cinema", font = "lf:oswald", shadow = "drop", hot = c(0xFFFFE0B2), spacing = 0.3f,
            motion = "focus", anim = "fade"),
        TextStyle("cine_vintage", "Старое кино", "Old film", "cinema", font = "serif", plate = "box", plateColor = c(0xE62B2118), ink = c(0xFFF4E9D8),
            hot = c(0xFFE0B46A), motion = "word", anim = "fade", words = 4),
        TextStyle("cine_chronicle", "Хроника", "Chronicle", "cinema", font = "mono", shadow = "drop", hot = c(0xFFFFCC80), motion = "type", words = 4),

        // ---------- gaming and tech: neon, pixels, glitch, HUD ----------
        TextStyle("game_neon", "Неон", "Neon", "gaming", font = "lf:unbounded", ink = c(0xFFE6F1FF), hot = c(0xFF00E5FF), shadow = "glow",
            shadowColor = c(0xCC00E5FF), motion = "glow"),
        TextStyle("game_pixel", "Пиксель", "Pixel", "gaming", font = "lf:pressstart2p", stroke = 0.14f, hot = c(0xFFFFDD00), motion = "pop", words = 2),
        TextStyle("game_cyber", "Киберпанк", "Cyberpunk", "gaming", font = "lf:oswald", plate = "tag", plateColor = c(0xFFFFE600), ink = c(0xFF111111),
            hot = c(0xFFD50032), motion = "shake", words = 2, tilt = -4f),
        TextStyle("game_glitch", "Глитч", "Glitch", "gaming", font = "lf:russoone", hot = c(0xFF00FFF0), shadow = "hard", shadowColor = c(0xCCFF00C8),
            motion = "shake"),
        TextStyle("game_esport", "Киберспорт", "Esports", "gaming", font = "lf:russoone", plate = "box", plateColor = c(0xF2111111), hot = c(0xFFFF3B30),
            motion = "stamp", anim = "slide", words = 2),
        TextStyle("game_terminal", "Терминал", "Terminal", "gaming", font = "mono", plate = "box", plateColor = c(0xF20A0F0A), ink = c(0xFF39FF14),
            hot = c(0xFFB9FFB0), upper = false, motion = "type", anim = "none", words = 5),
        TextStyle("game_hud", "Интерфейс HUD", "HUD", "gaming", font = "lf:oswald", plate = "outline", plateColor = c(0xCC00E5FF), ink = c(0xFFE6F1FF),
            hot = c(0xFF00E5FF), shadow = "drop", spacing = 0.08f),
        TextStyle("game_poster", "Плакат", "Poster", "gaming", font = "lf:rubikmonoone", ink = c(0xFFFFDD00), hot = c(W), stroke = 0.12f,
            motion = "bounce", words = 2),
        TextStyle("game_review", "Техно-обзор", "Tech review", "gaming", plate = "pill", plateColor = c(0xF21A1A2E), hot = c(0xFF8FA8FF),
            upper = false, words = 4),
        TextStyle("game_win", "Победа", "Victory", "gaming", font = "lf:daysone", ink = c(0xFFFFD600), hot = c(W), stroke = 0.16f, strokeColor = c(0xFF3E1F00),
            shadow = "hard", shadowColor = c(0xFFFF7A00), motion = "stamp", words = 1),

        // ---------- lifestyle and beauty: soft plates, handwriting, magazine serif ----------
        TextStyle("life_blush", "Пудровый", "Blush", "lifestyle", font = "lf:comfortaa", plate = "pill", plateColor = c(0xF2FFE4EC), ink = c(0xFF5A2A3A),
            hot = c(0xFFC2185B), upper = false, words = 4),
        TextStyle("life_script", "От руки", "Handwritten", "lifestyle", font = "lf:caveat", shadow = "drop", hot = c(0xFFFFB3C7), upper = false,
            motion = "word", anim = "fade", words = 4),
        TextStyle("life_vogue", "Журнал", "Magazine", "lifestyle", font = "lf:yesevaone", shadow = "drop", hot = c(0xFFF8BBD0), spacing = 0.1f,
            motion = "slide"),
        TextStyle("life_minimal", "Минимализм", "Minimal", "lifestyle", font = "sans", shadow = "drop", hot = c(0xFFFFFFFF), upper = false,
            motion = "word", anim = "fade", words = 5),
        TextStyle("life_food", "Рецепт", "Recipe", "lifestyle", font = "lf:daysone", plate = "tag", plateColor = c(0xFFFFF3E0), ink = c(0xFF4E2A10),
            hot = c(0xFFE65100), motion = "pop", words = 2, tilt = -3f),
        TextStyle("life_travel", "Путешествие", "Travel", "lifestyle", font = "lf:oswald", shadow = "drop", hot = c(0xFFFFE0B2), spacing = 0.25f,
            motion = "wave"),
        TextStyle("life_fit", "Фитнес", "Fitness", "lifestyle", font = "lf:russoone", stroke = 0.14f, hot = c(0xFFC6FF00), motion = "stamp", words = 2),
        TextStyle("life_mint", "Мята", "Mint", "lifestyle", font = "lf:comfortaa", plate = "box", plateColor = c(0xF2E8F5E9), ink = c(0xFF1B4332),
            hot = c(0xFF2D6A4F), upper = false, words = 4),
        TextStyle("life_sticker", "Стикер", "Sticker", "lifestyle", font = "lf:daysone", plate = "tag", plateColor = c(0xFFFFFFFF), ink = c(0xFF111111),
            hot = c(0xFFE91E63), motion = "bounce", words = 2, tilt = 3f),
        TextStyle("life_glow", "Сияние", "Shine", "lifestyle", shadow = "glow", shadowColor = c(0x99FF80C0), hot = c(0xFFFFD1F0), upper = false, motion = "glow"),

        // ---------- business and money: plates of a deck, green growth, gold of premium ----------
        TextStyle("biz_clean", "Деловой", "Business", "business", plate = "box", plateColor = c(0xF2FFFFFF), ink = c(0xFF0F172A), hot = c(0xFF2563EB),
            upper = false, words = 5),
        TextStyle("biz_dash", "Тёмный дашборд", "Dark dashboard", "business", plate = "box", plateColor = c(0xF20F172A), ink = c(0xFFE2E8F0),
            hot = c(0xFF22C55E), upper = false, words = 5),
        TextStyle("biz_money", "Деньги", "Money", "business", font = "lf:russoone", stroke = 0.14f, hot = c(0xFF22C55E), motion = "stamp", words = 2),
        TextStyle("biz_deck", "Презентация", "Slide deck", "business", font = "sans", shadow = "drop", hot = c(0xFF60A5FA), upper = false,
            motion = "underline", words = 5),
        TextStyle("biz_swiss", "Швейцарский", "Swiss", "business", font = "lf:oswald", plate = "band", plateColor = c(0xF2FFFFFF), ink = c(0xFF111111),
            hot = c(0xFFE30613), motion = "slide", words = 4),
        TextStyle("biz_luxury", "Премиум", "Premium", "business", font = "lf:yesevaone", plate = "box", plateColor = c(0xF2111111), ink = c(0xFFF5E6C8),
            hot = c(0xFFC9A45C), spacing = 0.08f, motion = "word", anim = "fade"),
        TextStyle("biz_growth", "Рост", "Growth", "business", font = "lf:unbounded", stroke = 0.1f, hot = c(0xFF34D399), motion = "wave"),
        TextStyle("biz_alert", "Важно", "Important", "business", font = "lf:oswald", plate = "box", plateColor = c(0xF2C62828), hot = c(0xFFFFE082),
            motion = "stamp", words = 2),
        TextStyle("biz_number", "Крупная цифра", "Big number", "business", font = "lf:rubikmonoone", hot = c(0xFFFACC15), shadow = "hard",
            shadowColor = c(0xCC000000), motion = "pop", words = 1),
        TextStyle("biz_analytics", "Аналитика", "Analytics", "business", font = "mono", shadow = "drop", hot = c(0xFF38BDF8), upper = false,
            motion = "type", words = 4),

        // ---------- comedy and memes: thick outlines, stickers, shaking ----------
        TextStyle("fun_meme", "Мем-классика", "Classic meme", "fun", font = "lf:oswald", stroke = 0.18f, motion = "", words = 6),
        TextStyle("fun_comic", "Комикс", "Comic", "fun", font = "lf:daysone", plate = "tag", plateColor = c(0xFFFFFFFF), ink = c(0xFF111111),
            hot = c(0xFFD50000), motion = "bounce", tilt = -2f),
        TextStyle("fun_shake", "Тряска", "Shake", "fun", font = "lf:russoone", stroke = 0.14f, hot = c(0xFFFFEA00), motion = "shake", words = 2),
        TextStyle("fun_bubble", "Пузырь", "Bubble", "fun", font = "lf:comfortaa", plate = "pill", plateColor = c(0xFFFFFFFF), ink = c(0xFF222222),
            hot = c(0xFF0277BD), upper = false, motion = "pop"),
        TextStyle("fun_felt", "Фломастер", "Felt pen", "fun", font = "lf:amaticsc", stroke = 0.12f, hot = c(0xFFFFEA00), motion = "bounce"),
        TextStyle("fun_cartoon", "Мультик", "Cartoon", "fun", font = "lf:daysone", ink = c(0xFFFFEB3B), hot = c(W), stroke = 0.18f,
            strokeColor = c(0xFF1A237E), shadow = "hard", shadowColor = c(0xFF1A237E), motion = "bounce", words = 2),
        TextStyle("fun_retro", "Ретро-70", "Retro 70s", "fun", font = "rounded", ink = c(0xFFFFB703), hot = c(0xFFFFE8A3), stroke = 0.08f,
            strokeColor = c(0xFF5A2A00), shadow = "long", shadowColor = c(0xFFB34700), motion = "wave", words = 2),
        TextStyle("fun_y2k", "Y2K", "Y2K", "fun", font = "lf:unbounded", ink = c(0xFFE0F7FF), hot = c(0xFFFF4FD8), shadow = "glow",
            shadowColor = c(0x99FF4FD8), motion = "pop", words = 2),
        TextStyle("fun_sarcasm", "Сарказм", "Sarcasm", "fun", font = "lf:caveat", plate = "box", plateColor = c(0xF2FFFF8D), ink = c(0xFF111111),
            hot = c(0xFFC51162), upper = false, motion = "word", words = 4, tilt = -2f),
        TextStyle("fun_boom", "Бум", "Boom", "fun", font = "lf:unbounded", ink = c(0xFFFFEA00), hot = c(0xFFFF6D00), stroke = 0.18f, shadow = "hard",
            shadowColor = c(0xFF000000), motion = "stamp", words = 1),

        // ---------- calm and aesthetic: lyrics, ASMR, nature, night ----------
        TextStyle("calm_lyrics", "Текст песни", "Lyrics", "calm", ink = c(0xE6FFFFFF), hot = c(0xFFFF80AB), shadow = "drop", upper = false,
            anim = "fade", words = 6),
        TextStyle("calm_asmr", "ASMR", "ASMR", "calm", font = "lf:comfortaa", ink = c(0xFFF5F5F5), hot = c(0xFFB3E5FC), shadow = "drop", upper = false,
            motion = "word", anim = "fade", words = 4),
        TextStyle("calm_serif", "Эстетика", "Aesthetic", "calm", font = "serif", shadow = "drop", hot = c(W), spacing = 0.05f, upper = false,
            motion = "focus", anim = "fade", words = 4),
        TextStyle("calm_film", "Плёнка", "Film grain", "calm", font = "mono", ink = c(0xFFF3E9D2), hot = c(0xFFFFCC80), shadow = "drop", spacing = 0.15f,
            motion = "type", words = 4),
        TextStyle("calm_nature", "Природа", "Nature", "calm", font = "lf:yesevaone", plate = "pill", plateColor = c(0xCC1B3A2A), ink = c(0xFFF1F8E9),
            hot = c(0xFFAED581), upper = false, motion = "word", anim = "fade", words = 5),
        TextStyle("calm_night", "Ночь", "Night", "calm", font = "sans", ink = c(0xFFE3F2FD), hot = c(0xFF90CAF9), shadow = "glow",
            shadowColor = c(0x6690CAF9), upper = false, motion = "glow", anim = "fade", words = 4),
        TextStyle("calm_diary", "Дневник", "Diary", "calm", font = "lf:caveat", plate = "box", plateColor = c(0xF2FFFDF5), ink = c(0xFF3E2723),
            hot = c(0xFF8D4F00), upper = false, motion = "underline", words = 5),
        TextStyle("calm_air", "Воздух", "Air", "calm", font = "lf:unbounded", shadow = "drop", hot = c(0xFFE1F5FE), spacing = 0.2f,
            motion = "slide", anim = "fade", words = 2),
        TextStyle("calm_mantra", "Мантра", "Mantra", "calm", font = "lf:amaticsc", hot = c(0xFFFFE0B2), shadow = "glow", shadowColor = c(0x66FFFFFF),
            motion = "wave", anim = "fade"),
        TextStyle("calm_poem", "Стихи", "Poem", "calm", font = "lf:yesevaone", ink = c(0xFFFFF8E1), hot = c(0xFFFFCC80), shadow = "drop", upper = false,
            motion = "word", anim = "fade", words = 6)
    )

    private val byId: Map<String, TextStyle> by lazy { ALL.associateBy { it.id } }

    fun of(id: String): TextStyle? = byId[id]

    /** The style a caption block wears ([EBlock.look] "ts:<id>"), if any. */
    fun ofLook(look: String): TextStyle? = if (look.startsWith(LOOK)) of(look.removePrefix(LOOK)) else null

    fun isStyle(look: String): Boolean = ofLook(look) != null

    fun inArea(area: String): List<TextStyle> = ALL.filter { it.area == area }

    fun name(s: TextStyle, ru: Boolean): String = if (ru) s.name else s.nameEn

    fun areaName(id: String, ru: Boolean): String = AREAS.firstOrNull { it.id == id }?.let { if (ru) it.name else it.nameEn } ?: id

    /** Search by the name in both languages, the area and the motion. */
    fun search(q: String): List<TextStyle> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return ALL
        return ALL.filter { s ->
            listOf(s.name, s.nameEn, s.id, s.motion, areaName(s.area, true), areaName(s.area, false)).any { it.lowercase().contains(t) }
        }
    }

    /** Problems of a style (a test keeps all the built-in ones clean). */
    fun problems(s: TextStyle): List<String> {
        val out = mutableListOf<String>()
        if (s.plate !in PLATES) out += "plate ${s.plate}"
        if (s.shadow !in SHADOWS) out += "shadow ${s.shadow}"
        if (s.motion !in EditCaptions.MOTIONS) out += "motion ${s.motion}"
        if (s.anim !in StudioEdit.ANIMS) out += "anim ${s.anim}"
        if (s.words !in 1..6) out += "words ${s.words}"
        if (s.area !in AREAS.map { it.id }) out += "area ${s.area}"
        if (s.font.isNotEmpty() && s.font !in SchemeStyles.FONTS && s.font.removePrefix("lf:") !in FONT_IDS) out += "font ${s.font}"
        // text on its plate must read (4.5:1); bare text on a video needs an outline, a shadow or a glow
        if (s.plate in listOf("box", "pill", "line", "band", "tag")) {
            val surf = SchemeStyles.over(s.plateColor, 0xFF404040.toInt())
            if (SchemeStyles.contrast(s.ink, surf) < 4.5) out += "contrast ink/plate ${"%.1f".format(SchemeStyles.contrast(s.ink, surf))}"
        } else if (s.stroke <= 0f && s.shadow == "none") out += "bare text without an outline or a shadow"
        return out
    }
}

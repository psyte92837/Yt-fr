/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.max

/**
 * One built-in motion style of the info-blocks (protocol lumen.info v1). An info-block is one thing from a single
 * card or a big number up to a full scheme (chain, hub, funnel, matrix…): the style is the same for all of them —
 * colours and plate ([look]), how every block comes in ([SchemeStyle.motion]), how it lives while it is on screen
 * ([SchemeStyle.idle]) and how the blocks are linked ([SchemeStyle.link]). [layout] — the layout of its preview.
 */
data class InfoStyle(
    val id: String, val area: String, val name: String, val nameEn: String,
    val palette: String, val look: SchemeStyle, val layout: String
)

/**
 * The options of an info-block style the user changes on top of it — everything has «as in the style» as default:
 * [alpha] 10–100 % of the whole block, [bg] the plate ("" style / solid / glass / clear / outline), [text] size of
 * the text s/m/l, [speed] of the motion, [motion] / [idle] / [link] instead of the style's, [corners] square / round /
 * pill, [caps] on/off, [shadow] on/off, [accent] colour, [font] ([LumenFonts] id or a generic family), [stagger]
 * cascade of the blocks ("" style / none / slow).
 */
data class InfoOpts(
    val alpha: Int = 100,
    val bg: String = "",
    val text: String = "m",
    val speed: Float = 1f,
    val motion: String = "",
    val idle: String = "",
    val link: String = "",
    val corners: String = "",
    val caps: String = "",
    val shadow: String = "",
    val accent: Int? = null,
    val font: String = "",
    val stagger: String = ""
) {
    val isDefault get() = this == InfoOpts()
}

object InfoStyles {
    const val PREFIX = "ib:"
    const val AUTO = SchemeStyles.AUTO

    /** How the blocks come in. */
    val MOTIONS = listOf("pop", "fade", "rise", "drop", "left", "right", "zoom", "stamp", "flip", "unfold",
        "draw", "wipe", "reveal", "bounce", "elastic", "spin", "swing", "ghost", "glitch", "type")

    /** How they live while on screen. */
    val IDLES = listOf("none", "float", "pulse", "breathe", "wiggle", "shine", "glow")

    /** How the blocks are linked. */
    val LINKS = listOf("arrow", "line", "dash", "dots", "none")
    val BGS = listOf("", "solid", "glass", "clear", "outline")
    val TEXTS = listOf("s", "m", "l")
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f)
    val CORNERS = listOf("", "square", "round", "pill")
    val STAGGERS = listOf("", "none", "slow")

    /** Accent colours offered in the options (null = the style's). */
    val ACCENTS: List<Int> = listOf(0xFFFFD60A, 0xFFFF3B30, 0xFFFF7A59, 0xFFFF2BD6, 0xFFB18CFF, 0xFF2F6BFF, 0xFF00E5FF, 0xFF22C55E, 0xFFFFFFFF, 0xFF111111)
        .map { it.toInt() }

    data class Area(val id: String, val ru: String, val en: String)

    val AREAS = listOf(
        Area("viral", "🔥 Вирусные", "🔥 Viral"),
        Area("podcast", "🎙 Подкаст", "🎙 Podcast"),
        Area("edu", "🎓 Обучение", "🎓 Education"),
        Area("news", "📰 Новости", "📰 News"),
        Area("business", "💼 Бизнес", "💼 Business"),
        Area("tech", "🤖 Техно и ИИ", "🤖 Tech & AI"),
        Area("cinema", "🎬 Кино", "🎬 Cinema"),
        Area("life", "🌿 Лайфстайл", "🌿 Lifestyle"),
        Area("fun", "🎮 Игры и юмор", "🎮 Games & fun"),
        Area("calm", "🤍 Спокойные", "🤍 Calm")
    )

    fun areaName(id: String, ru: Boolean) = AREAS.firstOrNull { it.id == id }?.let { if (ru) it.ru else it.en } ?: id

    // ---------- palettes: the 28 built-in looks of the schemes and 14 more ----------

    private fun c(v: Long) = v.toInt()

    private val NEW_PALETTES = listOf(
        SchemeStyle("graphite", "Графит", card = c(0xFF1F2226), ink = c(0xFFF4F5F7), muted = c(0xFFB4B9C2), accent = c(0xFFFF8A3D),
            line = c(0xFF4A505A), fill = "solid", radius = 0.14f, border = 0f, font = "sans"),
        SchemeStyle("frost", "Иней", card = c(0xD9FFFFFF), ink = c(0xFF101418), muted = c(0xFF4B5563), accent = c(0xFF2F6BFF),
            line = c(0x99FFFFFF), fill = "glass", radius = 0.24f, border = 1f, font = "sans"),
        SchemeStyle("cyber", "Кибер", card = c(0xFF0D0716), ink = c(0xFFFFFFFF), muted = c(0xFFE0C8F0), accent = c(0xFFFF2BD6),
            line = c(0xFF00E5FF), fill = "solid", radius = 0.04f, border = 1.6f, font = "lf:unbounded", textScale = 0.9f),
        SchemeStyle("lava", "Лава", card = c(0xFF1A0A08), ink = c(0xFFFFF1E8), muted = c(0xFFF2B8A0), accent = c(0xFFFF5A1F),
            line = c(0xCCFF5A1F), fill = "solid", radius = 0.10f, border = 1.2f, font = "lf:oswald", upper = true),
        SchemeStyle("candy", "Конфета", card = c(0xFFFFD6EC), ink = c(0xFF3B0A45), muted = c(0xFF6E3A78), accent = c(0xFFE0007A),
            line = c(0xFFFFA8D6), fill = "solid", radius = 0.40f, border = 0f, font = "lf:daysone"),
        SchemeStyle("forest", "Лес", card = c(0xFF14281D), ink = c(0xFFEFF7EC), muted = c(0xFFB9D4B4), accent = c(0xFFB5E61D),
            line = c(0x99B5E61D), fill = "solid", radius = 0.20f, border = 0.8f, font = "sans"),
        SchemeStyle("ice", "Лёд", card = c(0xFFEAF4FF), ink = c(0xFF0B2545), muted = c(0xFF3E5C7E), accent = c(0xFF1E88E5),
            line = c(0xFFC6DDF5), fill = "solid", radius = 0.20f, border = 0f, font = "sans"),
        SchemeStyle("sand", "Песок", card = c(0xFFF3E7D3), ink = c(0xFF3A2A18), muted = c(0xFF6E5A42), accent = c(0xFFC0602A),
            line = c(0xFFDCC7A6), fill = "solid", radius = 0.16f, border = 0f, font = "serif"),
        SchemeStyle("royal", "Королевский", card = c(0xFF1A1446), ink = c(0xFFFFF7E0), muted = c(0xFFD8CCA0), accent = c(0xFFFFC94A),
            line = c(0xCCFFC94A), fill = "solid", radius = 0.12f, border = 1f, font = "lf:yesevaone"),
        SchemeStyle("coral", "Коралл", card = c(0xFFC93A40), ink = c(0xFFFFFFFF), muted = c(0xFFFFE3DF), accent = c(0xFF2B1B3D),
            line = c(0xFF2B1B3D), fill = "solid", radius = 0.26f, border = 0f, font = "lf:montserrat"),
        SchemeStyle("noir", "Нуар", card = c(0xFF000000), ink = c(0xFFFFFFFF), muted = c(0xFFBDBDBD), accent = c(0xFFFFFFFF),
            line = c(0xFFFFFFFF), fill = "solid", radius = 0.02f, border = 0.8f, shadow = false, font = "condensed"),
        SchemeStyle("lime", "Лайм", card = c(0xFFC6FF00), ink = c(0xFF0A0A0A), muted = c(0xFF2A3300), accent = c(0xFF0A0A0A),
            line = c(0xFF0A0A0A), fill = "solid", radius = 0.10f, border = 0f, font = "lf:montserrat", upper = true),
        SchemeStyle("ink", "Тушь", card = c(0x00000000), ink = c(0xFFFFFFFF), muted = c(0xF2FFFFFF), accent = c(0xFFFFD60A),
            line = c(0xFFFFFFFF), fill = "outline", radius = 0.14f, border = 1.6f, font = "sans"),
        SchemeStyle("holo", "Голограмма", card = c(0x992A1B5C), ink = c(0xFFFFFFFF), muted = c(0xFFD6F4FF), accent = c(0xFF5CF2FF),
            line = c(0xB35CF2FF), fill = "glass", radius = 0.22f, border = 1.2f, font = "lf:unbounded", textScale = 0.9f)
    )

    private fun palette(id: String): SchemeStyle =
        NEW_PALETTES.firstOrNull { it.id == id } ?: SchemeStyles.BUILT_IN.firstOrNull { it.id == id } ?: SchemeStyles.PRESETS[1]

    // ---------- the 100 styles ----------

    /** One row: area, id, names, palette, motion, idle, link and the extras "key=value" (r f font up sc st bd sh dur lay). */
    private fun r(area: String, id: String, ru: String, en: String, pal: String, motion: String, idle: String, link: String, extra: String = ""): InfoStyle {
        val kv = extra.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val p = palette(pal)
        val look = p.copy(
            id = PREFIX + id, name = ru, builtIn = true,
            radius = kv["r"]?.toFloatOrNull() ?: p.radius,
            fill = kv["f"] ?: p.fill,
            font = kv["font"] ?: p.font,
            upper = kv["up"]?.let { it == "1" } ?: p.upper,
            textScale = kv["sc"]?.toFloatOrNull() ?: p.textScale,
            border = kv["bd"]?.toFloatOrNull() ?: p.border,
            shadow = kv["sh"]?.let { it == "1" } ?: p.shadow,
            dur = kv["dur"]?.toIntOrNull() ?: DUR[motion] ?: 340,
            enter = legacy(motion), motion = motion, idle = idle, link = link,
            stagger = kv["st"]?.toIntOrNull() ?: 0
        )
        return InfoStyle(id, area, ru, en, pal, look, kv["lay"] ?: "")
    }

    /** Natural length of each motion, ms. */
    private val DUR = mapOf("pop" to 320, "fade" to 420, "rise" to 380, "drop" to 460, "left" to 340, "right" to 340, "zoom" to 380,
        "stamp" to 260, "flip" to 420, "unfold" to 400, "draw" to 460, "wipe" to 480, "reveal" to 480, "bounce" to 520, "elastic" to 700,
        "spin" to 520, "swing" to 760, "ghost" to 440, "glitch" to 420, "type" to 700)

    /** The closest of the four old entrances, for code that knows only them. */
    private fun legacy(m: String) = when (m) {
        "fade", "ghost", "type", "reveal", "wipe" -> "fade"
        "rise", "drop", "left", "right", "swing" -> "slide"
        "draw", "flip", "unfold" -> "draw"
        else -> "pop"
    }

    val ALL: List<InfoStyle> = listOf(
        // 🔥 viral Shorts: hard hits, big plates, loud colours
        r("viral", "punch", "Жёлтый удар", "Yellow punch", "bold", "stamp", "pulse", "arrow", "st=120"),
        r("viral", "neonjump", "Неон-прыжок", "Neon jump", "neon", "bounce", "glow", "line"),
        r("viral", "bdrop", "Брутал-падение", "Brutal drop", "brutal", "drop", "wiggle", "arrow", "lay=list"),
        r("viral", "glitch", "Кибер-глитч", "Cyber glitch", "cyber", "glitch", "shine", "dash"),
        r("viral", "candypop", "Конфета", "Candy pop", "candy", "bounce", "float", "dots", "lay=grid"),
        r("viral", "limestamp", "Лайм-штамп", "Lime stamp", "lime", "stamp", "pulse", "arrow", "font=lf:rubikmonoone sc=0.82 lay=stat"),
        r("viral", "dash", "Рывок", "Dash", "sport", "left", "none", "arrow", "st=90"),
        r("viral", "coralzoom", "Коралл-зум", "Coral zoom", "coral", "zoom", "breathe", "line", "lay=card"),
        r("viral", "noirtype", "Нуар-печать", "Noir type", "noir", "type", "none", "line", "up=1 font=lf:oswald lay=list"),
        r("viral", "spring", "Закат-пружина", "Sunset spring", "sunset", "elastic", "float", "dots"),
        // 🎙 podcast and talking heads: calm glass, lower thirds
        r("podcast", "smoke", "Дымчатое стекло", "Smoked glass", "glass", "fade", "none", "line"),
        r("podcast", "frost", "Иней", "Frost", "frost", "rise", "none", "line", "lay=list"),
        r("podcast", "graphite", "Графит", "Graphite", "graphite", "wipe", "none", "line"),
        r("podcast", "quiet", "Тихий", "Quiet", "minimal", "ghost", "breathe", "none", "lay=card"),
        r("podcast", "aurora", "Аврора", "Aurora", "aurora", "unfold", "shine", "line"),
        r("podcast", "magazine", "Журнал", "Magazine", "editorial", "reveal", "none", "line", "lay=list"),
        r("podcast", "gold", "Золото", "Gold", "luxury", "fade", "shine", "line", "st=200"),
        r("podcast", "deep", "Глубина", "Deep", "ocean", "rise", "float", "dash"),
        r("podcast", "royal", "Королевский", "Royal", "royal", "flip", "none", "line", "lay=stat"),
        r("podcast", "holo", "Голограмма", "Hologram", "holo", "ghost", "shine", "dots"),
        // 🎓 education: boards, notebooks, flashcards
        r("edu", "chalk", "Доска", "Chalkboard", "chalk", "draw", "none", "arrow"),
        r("edu", "blueprint", "Чертёж", "Blueprint", "blueprint", "draw", "none", "dash"),
        r("edu", "notebook", "Тетрадь", "Notebook", "paper", "rise", "none", "arrow", "lay=list"),
        r("edu", "kids", "Детский", "Kids", "kids", "bounce", "float", "arrow"),
        r("edu", "mint", "Мята", "Mint", "mint", "pop", "none", "arrow", "lay=grid"),
        r("edu", "flash", "Карточки", "Flashcards", "soft", "swing", "wiggle", "arrow", "lay=grid"),
        r("edu", "ice", "Лёд", "Ice", "ice", "unfold", "none", "line"),
        r("edu", "notes", "Конспект", "Notes", "clean", "rise", "none", "arrow", "st=260 lay=list"),
        r("edu", "bento", "Бенто", "Bento", "bento", "zoom", "none", "none", "lay=grid"),
        r("edu", "textbook", "Учебник", "Textbook", "sand", "type", "none", "arrow"),
        // 📰 news and facts: bars, red accents, fast wipes
        r("news", "breaking", "Срочно", "Breaking", "news", "left", "none", "line", "st=120 lay=list"),
        r("news", "swiss", "Швейцарский", "Swiss", "swiss", "wipe", "none", "line"),
        r("news", "chronicle", "Хроника", "Chronicle", "noir", "reveal", "none", "line", "font=serif"),
        r("news", "brief", "Сводка", "Brief", "clean", "right", "none", "arrow"),
        r("news", "wire", "Лента", "Wire", "graphite", "type", "none", "dash", "font=mono lay=list"),
        r("news", "factcheck", "Факт-чек", "Fact check", "medical", "fade", "none", "line", "lay=stat"),
        r("news", "headline", "Заголовок", "Headline", "brutal", "stamp", "none", "arrow", "lay=card"),
        r("news", "analysis", "Аналитика", "Analysis", "ocean", "wipe", "none", "line"),
        r("news", "lower", "Плашка", "Lower third", "frost", "drop", "none", "line", "lay=list"),
        r("news", "column", "Колонка", "Column", "editorial", "flip", "none", "line"),
        // 💼 business and money: dashboards, slides, premium
        r("business", "dashboard", "Дашборд", "Dashboard", "finance", "rise", "pulse", "line", "lay=stat"),
        r("business", "product", "Продукт", "Product", "bento", "pop", "none", "line", "lay=grid"),
        r("business", "report", "Отчёт", "Report", "graphite", "unfold", "none", "line"),
        r("business", "premium", "Премиум", "Premium", "royal", "fade", "shine", "line"),
        r("business", "slide", "Слайд", "Slide", "clean", "wipe", "none", "arrow"),
        r("business", "strategy", "Стратегия", "Strategy", "swiss", "left", "none", "arrow"),
        r("business", "brand", "Бренд", "Brand", "luxury", "ghost", "none", "line", "lay=card"),
        r("business", "growth", "Рост", "Growth", "mint", "zoom", "pulse", "arrow", "lay=stat"),
        r("business", "corporate", "Корпоратив", "Corporate", "ice", "rise", "none", "line"),
        r("business", "contour", "Контур", "Outline", "ink", "draw", "none", "line"),
        // 🤖 tech and AI: terminals, circuits, holograms
        r("tech", "terminal", "Терминал", "Terminal", "terminal", "type", "none", "dash", "lay=list"),
        r("tech", "circuit", "Неон-схема", "Neon circuit", "neon", "draw", "glow", "line"),
        r("tech", "ai", "Нейросеть", "Neural", "aurora", "zoom", "float", "dots"),
        r("tech", "cyberpunk", "Киберпанк", "Cyberpunk", "cyber", "flip", "glow", "dash"),
        r("tech", "holopanel", "Голо-панель", "Holo panel", "holo", "unfold", "shine", "line", "lay=grid"),
        r("tech", "engineer", "Инженер", "Engineer", "blueprint", "wipe", "none", "dash"),
        r("tech", "gear", "Механизм", "Mechanism", "graphite", "spin", "none", "arrow"),
        r("tech", "pixel", "Пиксель", "Pixel", "pixel", "stamp", "wiggle", "arrow"),
        r("tech", "ui", "Интерфейс", "Interface", "frost", "ghost", "none", "line", "r=0.3 lay=grid"),
        r("tech", "data", "Данные", "Data", "finance", "glitch", "none", "dash", "font=mono lay=stat"),
        // 🎬 cinema and stories: titles, noir, epic
        r("cinema", "noir", "Нуар", "Noir", "noir", "fade", "breathe", "none", "font=serif up=1 lay=card"),
        r("cinema", "credits", "Титры", "Credits", "luxury", "reveal", "none", "line", "lay=list"),
        r("cinema", "arthouse", "Артхаус", "Arthouse", "editorial", "ghost", "none", "none"),
        r("cinema", "neoncity", "Неон-сити", "Neon city", "sunset", "rise", "float", "line"),
        r("cinema", "thriller", "Триллер", "Thriller", "lava", "drop", "breathe", "line"),
        r("cinema", "retrofilm", "Ретро-кино", "Retro film", "retro", "flip", "wiggle", "arrow"),
        r("cinema", "epic", "Эпос", "Epic", "royal", "unfold", "shine", "line", "up=1"),
        r("cinema", "docu", "Документалка", "Documentary", "ocean", "ghost", "none", "line", "lay=stat"),
        r("cinema", "plot", "Сюжет", "Plot", "glass", "wipe", "none", "arrow"),
        r("cinema", "screenplay", "Сценарий", "Screenplay", "ink", "type", "none", "line", "font=mono lay=list"),
        // 🌿 lifestyle: soft colours, rounded, hand-written
        r("life", "pastel", "Пастель", "Pastel", "pastel", "pop", "float", "dots"),
        r("life", "cloud", "Облако", "Cloud", "soft", "bounce", "float", "dots", "lay=grid"),
        r("life", "wellness", "Велнес", "Wellness", "mint", "rise", "breathe", "line"),
        r("life", "boho", "Бохо", "Boho", "sand", "fade", "none", "line", "font=lf:caveat sc=1.12"),
        r("life", "beauty", "Бьюти", "Beauty", "candy", "swing", "float", "dots"),
        r("life", "recipe", "Рецепт", "Recipe", "coral", "unfold", "none", "arrow", "lay=list"),
        r("life", "scandi", "Сканди", "Scandi", "frost", "zoom", "none", "line", "lay=card"),
        r("life", "nature", "Природа", "Nature", "forest", "rise", "float", "line"),
        r("life", "diary", "Дневник", "Diary", "paper", "swing", "wiggle", "dash", "font=lf:caveat sc=1.15"),
        r("life", "morning", "Утро", "Morning", "ice", "ghost", "breathe", "none", "lay=stat"),
        // 🎮 games and fun: comics, 8-bit, jelly
        r("fun", "comic", "Комикс", "Comic", "comic", "stamp", "wiggle", "arrow"),
        r("fun", "bit8", "8 бит", "8-bit", "pixel", "bounce", "none", "arrow", "lay=list"),
        r("fun", "jelly", "Желе", "Jelly", "kids", "elastic", "pulse", "arrow"),
        r("fun", "acid", "Кислота", "Acid", "lime", "spin", "wiggle", "arrow", "lay=grid"),
        r("fun", "bubblegum", "Жвачка", "Bubblegum", "candy", "bounce", "pulse", "dots", "lay=card"),
        r("fun", "stream", "Стрим", "Stream", "cyber", "stamp", "glow", "dash", "lay=stat"),
        r("fun", "meme", "Мем", "Meme", "brutal", "swing", "wiggle", "arrow"),
        r("fun", "arena", "Арена", "Arena", "sport", "drop", "pulse", "arrow"),
        r("fun", "party", "Пати", "Party", "coral", "elastic", "float", "dots", "lay=grid"),
        r("fun", "arcade", "Аркада", "Arcade", "retro", "bounce", "wiggle", "arrow"),
        // 🤍 calm and minimal: thin lines, air, slow fades
        r("calm", "minimal", "Минимал", "Minimal", "minimal", "fade", "none", "none"),
        r("calm", "air", "Воздух", "Air", "frost", "fade", "breathe", "line", "lay=card"),
        r("calm", "line", "Линия", "Line", "ink", "reveal", "none", "line"),
        r("calm", "clean", "Чисто", "Clean", "clean", "ghost", "none", "line"),
        r("calm", "mist", "Туман", "Mist", "glass", "rise", "breathe", "none", "lay=list"),
        r("calm", "paper", "Бумага", "Paper", "paper", "fade", "none", "line"),
        r("calm", "silence", "Тишина", "Silence", "ice", "wipe", "none", "line", "lay=stat"),
        r("calm", "woods", "Лес", "Woods", "forest", "ghost", "breathe", "line"),
        r("calm", "dune", "Дюна", "Dune", "sand", "unfold", "none", "line", "lay=grid"),
        r("calm", "essay", "Эссе", "Essay", "editorial", "draw", "none", "line")
    )

    private val byId: Map<String, InfoStyle> = ALL.associateBy { it.id }

    fun of(id: String): InfoStyle? = byId[id.removePrefix(PREFIX).substringBefore('?')]

    fun inArea(area: String): List<InfoStyle> = ALL.filter { it.area == area }

    fun name(st: InfoStyle, ru: Boolean) = if (ru) st.name else st.nameEn

    /** Styles whose name, area, motion or palette matches [q]. */
    fun search(q: String): List<InfoStyle> {
        val k = q.trim().lowercase()
        if (k.isEmpty()) return ALL
        return ALL.filter { s ->
            listOf(s.name, s.nameEn, s.area, areaName(s.area, true), areaName(s.area, false), s.palette, s.look.motion, s.look.idle,
                motionName(s.look.motion, true), motionName(s.look.motion, false)).any { it.lowercase().contains(k) }
        }
    }

    // ---------- spec: "ib:<id>?k=v&k=v" — the style and its options in one string, used as the look everywhere ----------

    fun isSpec(s: String?) = s != null && s.startsWith(PREFIX) && of(s) != null

    /** The spec of [id] with the options [o] (only those that differ from the defaults). */
    fun spec(id: String, o: InfoOpts = InfoOpts()): String {
        val d = InfoOpts()
        val q = buildList {
            if (o.alpha != d.alpha) add("a=${o.alpha}")
            if (o.bg != d.bg) add("bg=${o.bg}")
            if (o.text != d.text) add("t=${o.text}")
            if (o.speed != d.speed) add("sp=${o.speed}")
            if (o.motion != d.motion) add("m=${o.motion}")
            if (o.idle != d.idle) add("i=${o.idle}")
            if (o.link != d.link) add("l=${o.link}")
            if (o.corners != d.corners) add("r=${o.corners}")
            if (o.caps != d.caps) add("c=${o.caps}")
            if (o.shadow != d.shadow) add("sh=${o.shadow}")
            o.accent?.let { add("ac=" + String.format("%08X", it)) }
            if (o.font != d.font) add("f=${o.font}")
            if (o.stagger != d.stagger) add("st=${o.stagger}")
        }
        return PREFIX + id.removePrefix(PREFIX).substringBefore('?') + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    /** The options of a spec (defaults for anything unknown or out of range). */
    fun opts(spec: String?): InfoOpts {
        val d = InfoOpts()
        val q = spec?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return d
        val kv = q.split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        fun one(k: String, list: List<String>, def: String) = kv[k]?.takeIf { it in list } ?: def
        return InfoOpts(
            alpha = kv["a"]?.toIntOrNull()?.coerceIn(10, 100) ?: d.alpha,
            bg = one("bg", BGS, d.bg),
            text = one("t", TEXTS, d.text),
            speed = kv["sp"]?.toFloatOrNull()?.coerceIn(0.25f, 3f) ?: d.speed,
            motion = one("m", MOTIONS, d.motion),
            idle = one("i", IDLES, d.idle),
            link = one("l", LINKS, d.link),
            corners = one("r", CORNERS, d.corners),
            caps = one("c", listOf("", "on", "off"), d.caps),
            shadow = one("sh", listOf("", "on", "off"), d.shadow),
            accent = kv["ac"]?.let { runCatching { java.lang.Long.parseLong(it, 16).toInt() }.getOrNull() },
            font = kv["f"]?.takeIf { f -> f.isNotBlank() && f.length <= 32 && f.all { it.isLetterOrDigit() || it == ':' || it == '_' } } ?: d.font,
            stagger = one("st", STAGGERS, d.stagger)
        )
    }

    fun withOpts(spec: String, o: InfoOpts) = spec(spec, o)

    fun withStyle(spec: String, id: String) = spec(id, opts(spec))

    /**
     * The look a painter draws with: the style of [spec] with its options, for the placement [place] of the Studio
     * (overlay / panel / side / pip). Null = not a spec.
     */
    fun look(spec: String?, place: String = "overlay"): SchemeStyle? {
        if (spec == null || !spec.startsWith(PREFIX)) return null
        val st = of(spec) ?: return null
        return apply(st.look, opts(spec), place).copy(id = spec)
    }

    /** [base] with the options [o]; pure, so the preview, the Studio, the Mix and the Editor draw the same. */
    fun apply(base: SchemeStyle, o: InfoOpts, place: String = "overlay"): SchemeStyle {
        var s = base.copy(place = place, size = 0)
        s = when (o.bg) {
            "solid", "glass", "clear" -> StudioOverlay.style(s, o.bg, place)
            "outline" -> s.copy(fill = "outline", card = 0, border = max(s.border, 1.4f), shadow = true,
                line = if (SchemeStyles.luminance(s.line) < 0.25) 0xFFFFFFFF.toInt() else s.line,
                ink = 0xFFFFFFFF.toInt(), muted = 0xF2FFFFFF.toInt())
            else -> s
        }
        s = when (o.corners) {
            "square" -> s.copy(radius = 0.02f)
            "round" -> s.copy(radius = 0.22f)
            "pill" -> s.copy(radius = 0.5f)
            else -> s
        }
        if (o.caps == "on") s = s.copy(upper = true) else if (o.caps == "off") s = s.copy(upper = false)
        if (o.shadow == "on") s = s.copy(shadow = true) else if (o.shadow == "off") s = s.copy(shadow = false)
        o.accent?.let { a -> s = s.copy(accent = a) }
        if (o.font.isNotEmpty()) s = s.copy(font = o.font)
        s = s.copy(textScale = s.textScale * when (o.text) { "s" -> 0.86f; "l" -> 1.16f; else -> 1f })
        val sp = o.speed.coerceIn(0.25f, 3f)
        s = s.copy(dur = (s.dur / sp).toInt().coerceIn(80, 3000))
        if (o.motion.isNotEmpty()) s = s.copy(motion = o.motion, enter = legacy(o.motion), dur = ((DUR[o.motion] ?: 340) / sp).toInt())
        if (o.idle.isNotEmpty()) s = s.copy(idle = o.idle)
        if (o.link.isNotEmpty()) s = s.copy(link = o.link)
        s = s.copy(stagger = when (o.stagger) {
            "none" -> 0
            "slow" -> 320
            else -> (s.stagger / sp).toInt()
        })
        return s.copy(alpha = o.alpha.coerceIn(10, 100) / 100f)
    }

    /** The info-block style closest to an old scheme look (clean, glass, neon…): the first style made of it. */
    fun fromOld(look: String?): String {
        val id = SchemeStyles.alias(look.orEmpty())
        if (isSpec(look)) return look!!
        return ALL.firstOrNull { it.palette == id }?.let { spec(it.id) } ?: AUTO
    }

    // ---------- names ----------

    fun motionName(m: String, ru: Boolean): String {
        val n = when (m) {
            "pop" -> "Выскок" to "Pop"
            "fade" -> "Проявление" to "Fade"
            "rise" -> "Подъём" to "Rise"
            "drop" -> "Падение" to "Drop"
            "left" -> "Слева" to "From left"
            "right" -> "Справа" to "From right"
            "zoom" -> "Наезд" to "Zoom"
            "stamp" -> "Штамп" to "Stamp"
            "flip" -> "Переворот" to "Flip"
            "unfold" -> "Раскрытие" to "Unfold"
            "draw" -> "Прорисовка" to "Draw"
            "wipe" -> "Шторка" to "Wipe"
            "reveal" -> "Снизу вверх" to "Reveal"
            "bounce" -> "Прыжок" to "Bounce"
            "elastic" -> "Пружина" to "Elastic"
            "spin" -> "Вращение" to "Spin"
            "swing" -> "Качели" to "Swing"
            "ghost" -> "Из дымки" to "Ghost"
            "glitch" -> "Глитч" to "Glitch"
            "type" -> "Печать" to "Typewriter"
            else -> m to m
        }
        return if (ru) n.first else n.second
    }

    fun idleName(i: String, ru: Boolean): String {
        val n = when (i) {
            "float" -> "Парение" to "Float"
            "pulse" -> "Пульс" to "Pulse"
            "breathe" -> "Дыхание" to "Breathe"
            "wiggle" -> "Покачивание" to "Wiggle"
            "shine" -> "Блик" to "Shine"
            "glow" -> "Свечение" to "Glow"
            else -> "Нет" to "None"
        }
        return if (ru) n.first else n.second
    }

    fun linkName(l: String, ru: Boolean): String {
        val n = when (l) {
            "line" -> "Линия" to "Line"
            "dash" -> "Пунктир" to "Dashed"
            "dots" -> "Точки" to "Dots"
            "none" -> "Без связей" to "No links"
            else -> "Стрелка" to "Arrow"
        }
        return if (ru) n.first else n.second
    }

    /** One line about a style: motion · life · links. */
    fun about(st: InfoStyle, ru: Boolean): String = listOfNotNull(
        motionName(st.look.motion, ru),
        st.look.idle.takeIf { it != "none" }?.let { idleName(it, ru).lowercase() },
        if (st.look.link == "none") null else linkName(st.look.link, ru).lowercase()
    ).joinToString(" · ")

    /** What the options change, in a few words ("" = as in the style). */
    fun optsSummary(o: InfoOpts, ru: Boolean): String = buildList {
        if (o.alpha < 100) add("${o.alpha}%")
        when (o.bg) {
            "solid" -> add(if (ru) "плотный фон" else "solid")
            "glass" -> add(if (ru) "стекло" else "glass")
            "clear" -> add(if (ru) "без подложки" else "no plate")
            "outline" -> add(if (ru) "контур" else "outline")
        }
        if (o.motion.isNotEmpty()) add(motionName(o.motion, ru).lowercase())
        if (o.idle.isNotEmpty()) add(idleName(o.idle, ru).lowercase())
        if (o.speed != 1f) add("×" + (if (o.speed % 1f == 0f) o.speed.toInt().toString() else o.speed.toString()))
        if (o.text != "m") add(if (o.text == "s") (if (ru) "текст мельче" else "smaller text") else (if (ru) "текст крупнее" else "bigger text"))
        if (o.accent != null) add(if (ru) "свой акцент" else "own accent")
        if (o.font.isNotEmpty()) add(if (ru) "свой шрифт" else "own font")
    }.joinToString(" · ")

    // ---------- samples for the previews ----------

    /** A small info-block that shows a style in its preview: a card, a big number, a list, a grid or a chain. */
    fun sample(st: InfoStyle, ru: Boolean, index: Int = ALL.indexOf(st)): SScheme {
        val lay = st.layout.ifEmpty { listOf("chain", "list", "grid", "stat", "hub")[(index.coerceAtLeast(0)) % 5] }
        fun n(i: Int, icon: String, ru0: String, en: String, v: String = "", acc: Boolean = false) =
            SchNode("n$i", icon, if (ru) ru0 else en, v, accent = acc)
        return when (lay) {
            "card" -> SScheme("card", "", listOf(n(1, "💡", "Главная мысль", "Key idea", "3×", acc = true)))
            "stat" -> SScheme("stat", "", listOf(n(1, "📈", "рост", "growth", "+42%", acc = true), n(2, "⏱", "минут", "minutes", "15")))
            "list" -> SScheme("list", "", listOf(n(1, "1️⃣", "Идея", "Idea"), n(2, "2️⃣", "Сценарий", "Script", acc = true), n(3, "3️⃣", "Монтаж", "Edit")))
            "grid" -> SScheme("grid", "", listOf(n(1, "⚡", "Быстро", "Fast"), n(2, "🎯", "Точно", "Precise", acc = true),
                n(3, "💰", "Дёшево", "Cheap"), n(4, "😊", "Просто", "Simple")))
            "hub" -> SScheme("hub", "", listOf(n(1, "🧠", "Идея", "Idea", acc = true), n(2, "🎯", "Цель", "Goal"), n(3, "👥", "Люди", "People"), n(4, "⏱", "Время", "Time")))
            else -> SScheme("chain", "", listOf(n(1, "🔍", "Поиск", "Search"), n(2, "👀", "Интерес", "Interest", acc = true), n(3, "🛒", "Покупка", "Purchase")))
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A row of the brief's data: label, value, unit, emoji; [main] — the one to stress. */
data class BriefItem(val label: String, val value: String = "", val unit: String = "", val icon: String = "", val main: Boolean = false)

/**
 * What the author asked of a block made by the insert wizard (protocol lumen.insert v1).
 * Empty strings, -1 and null mean "auto": the AI or the style decides and the field is not written into the request.
 */
data class EditBrief(
    val type: String = "custom",
    /** "scheme" or "anim"; empty = by [type]. */
    val kind: String = "",
    // ---- A. content ----
    val what: String = "",
    /** speech | data | wish, "" = auto. */
    val source: String = "",
    val items: List<BriefItem> = emptyList(),
    /** Fields of the type: value, unit, from, label, a, b, quote, author, name, role, phrase, stress, text, to… */
    val fields: Map<String, String> = emptyMap(),
    val onlySaid: Boolean = true,
    /** "" auto, "-" without a title, else the title. */
    val title: String = "",
    val count: Int = 0,
    /** short | medium | phrase */
    val labels: String = "",
    /** emoji | none */
    val icons: String = "",
    val lang: String = "",
    /** business | friendly | fun | drama | kids */
    val tone: String = "",
    /** Scheme layout ([StudioScheme.LAYOUTS]), "" = auto. */
    val layout: String = "",
    // ---- B. look ----
    val style: String = "",
    val accent: Int? = null,
    /** mono | bright | pastel */
    val palette: String = "",
    /** none | glass | semi | solid | full */
    val fill: String = "",
    val bgAlpha: Float = -1f,
    /** none | s | m | l */
    val radius: String = "",
    val border: Boolean? = null,
    val shadow: Boolean? = null,
    /** sans | condensed | serif | mono | rounded */
    val font: String = "",
    val upper: Boolean? = null,
    /** s | m | l */
    val textSize: String = "",
    val like: String = "",
    /** A picture of the look wanted: file inside the project folder. */
    val refImage: String = "",
    /** emoji | arrows | colors2 | gradients | shadows */
    val avoidStyle: List<String> = emptyList(),
    val dont: String = "",
    // ---- C. transparency and the video under the block ----
    val opacity: Float = 1f,
    val dim: Float = 0f,
    val dimAll: Boolean = false,
    // ---- D. motion and time ----
    val durMs: Long = 0,
    /** none | fade | pop | slide | zoom | code */
    val enter: String = "",
    /** words | steps | all */
    val reveal: String = "",
    /** calm | normal | drive */
    val pace: String = "",
    val emphasis: Boolean = true,
    val holdMs: Long = -1,
    /** none | fade | pop | slide | zoom | code */
    val exit: String = "",
    val loopMs: Long = 0,
    /** none | soft | whoosh */
    val sfx: String = "",
    // ---- E. place ----
    /** full | cover | top | center | bottom | corner | custom */
    val place: String = "",
    /** s | m | l | xl */
    val size: String = "",
    /** wide | square | tall */
    val shape: String = "",
    /** face | subs | shorts | blocks */
    val avoid: List<String> = listOf("shorts", "subs", "face"),
    /** The face found on the frame (or drawn by the author): x, y, w, h — fractions of the frame. */
    val face: List<Float> = emptyList(),
    /** Where a pointer points: x, y — fractions of the frame. */
    val target: List<Float> = emptyList(),
    /** top | under */
    val layer: String = ""
)

/** A kind of block by its meaning: how it is drawn ([kind] scheme, anim or both) and the fields it asks for. */
data class InsertType(
    val id: String, val icon: String, val ru: String, val en: String, val kind: String,
    val layout: String = "", val fields: List<String> = emptyList(), val items: Boolean = false,
    val sampleRu: String = "", val sampleEn: String = "", val task: String = ""
)

/** Where a block of the wizard goes in the frame: centre and width (fractions), height / width in pixels. */
data class PlaceBox(val x: Float, val y: Float, val w: Float, val ratio: Float)

/** A soft warning of the brief with the fix offered by one tap ([fix]: longer, fewer, denser, dim, move, bigger, describe). */
data class InsertWarn(val id: String, val text: String, val fix: String, val fixLabel: String, val blocking: Boolean = false)

/** What the request needs to know about the video and the block. */
data class InsertCtx(
    val fw: Int, val fh: Int,
    val box: PlaceBox,
    val startMs: Long, val durMs: Long,
    /** All words of the voice (output time); a scheme's nodes refer to them by number. */
    val words: List<SWord>,
    val lang: String,
    /** The video already has burned-in subtitles. */
    val subs: Boolean = false,
    /** Other blocks at this time: short descriptions. */
    val others: List<String> = emptyList(),
    /** Colours of the chosen style (ARGB) for the animation: accent, ink, card. */
    val accent: Int = 0xFFFFD60A.toInt(), val ink: Int = 0xFFFFFFFF.toInt(), val card: Int = 0xE6101318.toInt(),
    val font: String = "sans"
)

/** An answer of an AI recognised by the wizard. */
sealed class InsertAnswer {
    abstract val req: String
    abstract val name: String

    class Scheme(val scheme: SScheme, override val req: String, override val name: String) : InsertAnswer()
    class Anim(val code: String, override val req: String, override val name: String, val loopMs: Long) : InsertAnswer()
}

/**
 * The insert wizard of the Studio editor (protocol lumen.insert v1): the brief of an info-scheme or an animation
 * block, the request for the built-in or another AI (the same text), recognising the answer, the animation
 * protocol lumen.anim v1, frames of a rendered animation, place presets, warnings. Pure, no Android.
 */
object EditInsert {
    const val PROTOCOL = "lumen.insert v1"
    const val ANIM = "lumen.anim v1"
    const val FPS = 30
    const val MAX_ANIM_MS = 30_000L

    val TYPES = listOf(
        InsertType("number", "🔢", "Число-факт", "A number", "anim", fields = listOf("value", "unit", "from", "label", "format"),
            sampleRu = "«2,5 млн за год» — счётчик", sampleEn = "\"2.5M a year\" — a counter",
            task = "крупное число, которое досчитывает до значения (счётчик), с подписью; число — главное на экране"),
        InsertType("compare", "⚖️", "Сравнение A и B", "A vs B", "both", "venn", listOf("winner"), true,
            "две величины рядом", "two values side by side",
            "сравнение двух вещей: две колонки или столбца с подписями и значениями, видно, кто больше/лучше"),
        InsertType("chart", "📊", "График / доли", "Chart / shares", "anim", fields = listOf("chart", "sort"), items = true,
            sampleRu = "столбцы, линия, кольцо", sampleEn = "bars, a line, a ring",
            task = "график по данным: растущие столбцы, линия или кольцо долей, подписи и значения, главное выделено"),
        InsertType("steps", "🪜", "Шаги / процесс", "Steps / process", "both", "chain", items = true,
            sampleRu = "1 → 2 → 3", sampleEn = "1 → 2 → 3", task = "шаги процесса по порядку, каждый шаг появляется, когда о нём говорят"),
        InsertType("list", "✅", "Список / чек-лист", "List / checklist", "both", "hub", items = true,
            sampleRu = "пункты с галочками", sampleEn = "points with ticks", task = "список пунктов с галочками ✓ (или ✗), пункты появляются по очереди"),
        InsertType("cause", "➡️", "Причина → следствие", "Cause → effect", "scheme", "chain", items = true,
            sampleRu = "почему так вышло", sampleEn = "why it happened", task = "причина и её следствия цепочкой"),
        InsertType("timeline", "🕰", "Хронология", "Timeline", "scheme", "timeline", items = true,
            sampleRu = "даты и события", sampleEn = "dates and events", task = "события по порядку на ленте времени, value — дата или срок"),
        InsertType("funnel", "🔻", "Воронка", "Funnel", "scheme", "funnel", items = true,
            sampleRu = "от широкого к узкому", sampleEn = "from wide to narrow", task = "воронка этапов от широкого к узкому, у этапов числа"),
        InsertType("hierarchy", "🔺", "Иерархия", "Hierarchy", "scheme", "pyramid", items = true,
            sampleRu = "уровни, пирамида", sampleEn = "levels, a pyramid", task = "уровни от вершины к основе"),
        InsertType("proscons", "👍", "Плюсы и минусы", "Pros and cons", "anim", fields = listOf("pros", "cons"),
            sampleRu = "две колонки", sampleEn = "two columns", task = "две колонки: плюсы (зелёные ✓) и минусы (красные ✗), пункты появляются по очереди"),
        InsertType("concept", "🧭", "Понятие и связи", "A concept and links", "scheme", "hub", listOf("center"), true,
            "главное и вокруг", "the main thing and around it", task = "главное понятие в центре и связанные с ним вокруг"),
        InsertType("quote", "💬", "Цитата", "A quote", "anim", fields = listOf("quote", "author"),
            sampleRu = "крупные кавычки", sampleEn = "big quotation marks", task = "цитата крупно, кавычки, автор подписью; текст проявляется по словам"),
        InsertType("lower3", "🏷", "Подпись спикера", "Lower third", "anim", fields = listOf("name", "role"),
            sampleRu = "имя и роль внизу", sampleEn = "name and role", task = "нижняя плашка: имя крупно, роль мельче, выезжает и уезжает"),
        InsertType("pointer", "👉", "Указатель на кадре", "A pointer", "anim", fields = listOf("label"),
            sampleRu = "стрелка, обводка", sampleEn = "an arrow, a circle", task = "стрелка или обводка, указывающая на точку кадра data.brief.target, с подписью"),
        InsertType("progress", "⏳", "Прогресс / таймер", "Progress / timer", "anim", fields = listOf("from", "to", "label"),
            sampleRu = "полоса или кольцо", sampleEn = "a bar or a ring", task = "прогресс от from до to (полоса или кольцо) с подписью"),
        InsertType("kinetic", "🔤", "Ключевая фраза", "Key phrase", "anim", fields = listOf("phrase", "stress"),
            sampleRu = "кинетический текст", sampleEn = "kinetic type", task = "ключевая фраза кинетическим текстом: слова появляются в такт речи, слова-акценты крупнее и цветом"),
        InsertType("map", "🗺", "Маршрут", "A route", "anim", items = true,
            sampleRu = "точки по порядку", sampleEn = "points in order", task = "схема маршрута: точки по порядку соединяются линией, у точек подписи"),
        InsertType("cta", "📣", "Призыв", "Call to action", "anim", fields = listOf("text", "icon"),
            sampleRu = "подписка, ссылка", sampleEn = "subscribe, a link", task = "призыв к действию: яркая кнопка-плашка с иконкой, пульсирует"),
        InsertType("custom", "✨", "Своё", "Your own", "both", task = "")
    )

    fun type(id: String): InsertType = TYPES.firstOrNull { it.id == id } ?: TYPES.last()

    /** "scheme" or "anim" of a brief: its own choice, else by the type (both → scheme when it has a layout, else anim). */
    fun kindOf(b: EditBrief): String {
        if (b.kind == "scheme" || b.kind == "anim") return b.kind
        val ty = type(b.type)
        return when (ty.kind) {
            "scheme", "anim" -> ty.kind
            else -> if (ty.layout.isNotEmpty()) "scheme" else "anim"
        }
    }

    fun fieldName(id: String): String = when (id) {
        "value" -> t("Значение", "Value")
        "unit" -> t("Единица", "Unit")
        "from" -> t("Считать от", "Count from")
        "to" -> t("До", "To")
        "label" -> t("Подпись", "Label")
        "format" -> t("Формат числа", "Number format")
        "winner" -> t("Кто выигрывает", "Who wins")
        "chart" -> t("Вид графика", "Chart kind")
        "sort" -> t("Сортировка", "Sorting")
        "pros" -> t("Плюсы (по строке)", "Pros (one per line)")
        "cons" -> t("Минусы (по строке)", "Cons (one per line)")
        "center" -> t("В центре", "In the centre")
        "quote" -> t("Цитата", "Quote")
        "author" -> t("Автор", "Author")
        "name" -> t("Имя", "Name")
        "role" -> t("Роль", "Role")
        "phrase" -> t("Фраза", "Phrase")
        "stress" -> t("Слова-акценты", "Stressed words")
        "text" -> t("Текст", "Text")
        "icon" -> t("Значок", "Icon")
        else -> id
    }

    /** Ready choices of a type field (chips); empty = free text. */
    fun fieldChoices(id: String): List<Pair<String, String>> = when (id) {
        "format" -> listOf("plain" to "1 200", "short" to t("1,2 тыс.", "1.2K"), "pct" to "72 %")
        "chart" -> listOf("bars" to t("Столбцы", "Bars"), "line" to t("Линия", "Line"), "ring" to t("Кольцо", "Ring"))
        "sort" -> listOf("as_is" to t("Как есть", "As is"), "desc" to t("По убыванию", "Descending"))
        "winner" -> listOf("a" to "A", "b" to "B", "none" to t("Без победителя", "No winner"))
        else -> emptyList()
    }

    // ---------- defaults from the context ----------

    /** The brief of a new block of [typeId]: what is known from the speech of the block and the style of the video ([brand]). */
    fun defaults(typeId: String, blockWords: List<String>, brand: EditBrief?, portrait: Boolean): EditBrief {
        val ty = type(typeId)
        var b = EditBrief(type = ty.id, layout = ty.layout, reveal = if (blockWords.isNotEmpty()) "words" else "")
        if (brand != null) b = withLook(b, brand)
        if (ty.items) b = b.copy(items = dataFromSpeech(blockWords).take(6))
        if (ty.id == "number") dataFromSpeech(blockWords).firstOrNull { it.value.isNotEmpty() }?.let { n ->
            b = b.copy(fields = mapOf("value" to n.value, "unit" to n.unit, "label" to n.label).filterValues { it.isNotEmpty() })
        }
        if (ty.id == "kinetic" && blockWords.isNotEmpty()) b = b.copy(fields = mapOf("phrase" to blockWords.joinToString(" ").take(80)))
        when (ty.id) {
            "pointer" -> b = b.copy(place = "full", enter = "code", exit = "code")
            "lower3" -> b = b.copy(place = "bottom", size = "l")
            "cta" -> b = b.copy(place = "bottom", size = "m")
            "kinetic" -> b = b.copy(place = "center", fill = b.fill.ifEmpty { "none" })
        }
        if (kindOf(b) == "anim") b = b.copy(enter = b.enter.ifEmpty { "code" }, exit = b.exit.ifEmpty { "code" })
        if (portrait && b.place.isEmpty()) b = b.copy(place = "center")
        return b
    }

    /** [b] with the look of [from] (style of the video, a recipe): look, transparency, pace, place. */
    fun withLook(b: EditBrief, from: EditBrief): EditBrief = b.copy(
        style = from.style, accent = from.accent, palette = from.palette, fill = from.fill, bgAlpha = from.bgAlpha, radius = from.radius,
        border = from.border, shadow = from.shadow, font = from.font, upper = from.upper, textSize = from.textSize, like = from.like,
        avoidStyle = from.avoidStyle, dont = from.dont, opacity = from.opacity, dim = from.dim, dimAll = from.dimAll, pace = from.pace,
        tone = from.tone.ifEmpty { b.tone }
    )

    /** A recipe: the brief without its content (the type, look, transparency, motion and place stay). */
    fun recipe(b: EditBrief): EditBrief = b.copy(what = "", items = emptyList(), fields = emptyMap(), title = "", face = emptyList(), target = emptyList(), refImage = "", durMs = 0)

    // ---------- numbers and names of the speech ----------

    private val NUM = Regex("""^[~≈]?\d+(?:[.,]\d+)?%?$""")
    private val UNITS = setOf(
        "%", "процентов", "процента", "процент", "млн", "миллионов", "миллиона", "миллион", "млрд", "миллиардов", "миллиарда", "миллиард",
        "тыс", "тысяч", "тысячи", "тысяча", "руб", "рублей", "рубля", "рубль", "долларов", "доллара", "доллар", "евро", "лет", "года", "год", "году",
        "раз", "раза", "часов", "часа", "минут", "минуты", "секунд", "дней", "дня", "км", "кг", "человек", "подписчиков", "просмотров",
        "percent", "million", "millions", "billion", "thousand", "k", "m", "dollars", "years", "year", "times", "hours", "minutes", "days", "people",
        "views", "subscribers", "$", "₽", "€"
    )
    private val STOP = setOf(
        "и", "в", "во", "на", "с", "со", "за", "по", "из", "от", "до", "к", "у", "о", "об", "а", "но", "что", "это", "как", "так", "же", "ли", "не", "уже",
        "всего", "около", "почти", "более", "менее", "чем", "его", "её", "их", "мы", "вы", "они", "он", "она", "оно", "то", "the", "a", "an", "of", "in",
        "on", "to", "and", "or", "by", "for", "with", "is", "are", "was", "about", "almost", "over", "than", "it", "this", "that"
    )

    private fun bare(w: String) = w.trim().trim('«', '»', '"', '\'', '(', ')', ',', '.', '!', '?', ':', ';', '…', '—', '“', '”')

    /**
     * Numbers of the speech with their units and the words before them as the label ("2,5 млн подписчиков"), then
     * names (capitalised words not after a full stop): rows for the data of the brief.
     */
    fun dataFromSpeech(words: List<String>): List<BriefItem> {
        val out = mutableListOf<BriefItem>()
        for (i in words.indices) {
            val w = bare(words[i])
            if (!NUM.matches(w)) continue
            var value = w
            var unit = ""
            if (w.endsWith("%")) {
                value = w.removeSuffix("%")
                unit = "%"
            } else words.getOrNull(i + 1)?.let { n -> bare(n).lowercase(Locale.ROOT).takeIf { it in UNITS }?.let { unit = it } }
            val label = (max(0, i - 3) until i).map { bare(words[it]) }.filter { it.length > 2 && it.lowercase(Locale.ROOT) !in STOP && !NUM.matches(it) }
                .takeLast(2).joinToString(" ")
            val after = if (label.isEmpty()) ((i + if (unit.isNotEmpty() && unit != "%") 2 else 1)..min(words.lastIndex, i + 3))
                .map { bare(words[it]) }.firstOrNull { it.length > 3 && it.lowercase(Locale.ROOT) !in STOP }.orEmpty() else ""
            if (out.none { it.value == value && it.unit == unit }) out += BriefItem(label.ifEmpty { after }, value, unit)
        }
        for (i in words.indices) {
            if (out.size >= 8) break
            val w = bare(words[i])
            if (w.length < 3 || !w[0].isUpperCase() || w.all { it.isUpperCase() } && w.length > 5) continue
            val prev = words.getOrNull(i - 1)?.trim().orEmpty()
            if (i == 0 || prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?") || prev.endsWith("…")) continue
            if (out.none { it.label.equals(w, true) }) out += BriefItem(w)
        }
        return out.take(8)
    }

    // ---------- the request ----------

    private const val B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** A short mark of a request (#K7Q2): the answer brings it back, so it finds its block. */
    fun reqId(nowMs: Long, n: Int): String {
        var v = (nowMs / 1000) * 31 + n
        val sb = StringBuilder()
        repeat(4) {
            sb.append(B32[(v % B32.length).toInt().let { if (it < 0) -it else it }])
            v /= B32.length
        }
        return sb.toString()
    }

    private fun secs(ms: Long): String = String.format(Locale.US, "%.2f", ms / 1000.0)

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    private fun pct(f: Float) = (f * 100).roundToInt().toString() + " %"

    /** The format of the frame for the AI. */
    fun formatText(fw: Int, fh: Int): String = when {
        fh > fw * 1.1f -> t("вертикальный 9:16 (Shorts/Reels)", "vertical 9:16 (Shorts/Reels)")
        fw > fh * 1.1f -> t("горизонтальный 16:9", "horizontal 16:9")
        else -> t("квадратный 1:1", "square 1:1")
    }

    /** Pixel size of the block of [ctx] in its frame. */
    fun boxPx(ctx: InsertCtx): Pair<Int, Int> {
        val w = (ctx.box.w * ctx.fw).roundToInt().coerceAtLeast(16)
        return w to (w * ctx.box.ratio).roundToInt().coerceAtLeast(16)
    }

    /** Minimum text size in pixels of a [fw]×[fh] frame for text size [size] (s, m, l): readable on a phone. */
    fun minFontPx(fw: Int, fh: Int, size: String): Int {
        val portrait = fh > fw
        val k = when (size) {
            "s" -> if (portrait) 0.026f else 0.032f
            "l" -> if (portrait) 0.045f else 0.052f
            else -> if (portrait) 0.034f else 0.04f
        }
        return (fh * k).roundToInt().coerceAtLeast(12)
    }

    fun paceText(p: String): String = when (p) {
        "calm" -> t("спокойно: движения 600–900 мс, мягкие кривые", "calm: moves of 600–900 ms, soft curves")
        "drive" -> t("драйв: движения 150–300 мс, с отскоком (ease.back)", "drive: moves of 150–300 ms with overshoot (ease.back)")
        "normal" -> t("обычно: движения 300–500 мс", "normal: moves of 300–500 ms")
        else -> ""
    }

    private fun fillText(f: String, alpha: Float): String {
        val a = if (alpha >= 0f) alpha else defaultAlpha(f)
        return when (f) {
            "none" -> t("без подложки — текст с тенью или обводкой, чтобы читался на видео", "no plate — text with a shadow or outline so it reads over the video")
            "glass" -> t("стекло: полупрозрачная подложка ", "glass: a see-through plate ") + pct(a)
            "semi" -> t("полупрозрачная подложка ", "a semi-transparent plate ") + pct(a)
            "solid" -> t("плотная подложка ", "a solid plate ") + pct(a)
            "full" -> t("перебивка: весь блок залит фоном 100 %, видео под ним не видно", "a cutaway: the whole block filled with the background, no video under it")
            else -> ""
        }
    }

    /** Opacity of a plate kind when the author did not set it. */
    fun defaultAlpha(f: String): Float = when (f) {
        "glass" -> 0.35f
        "semi" -> 0.7f
        "solid" -> 0.95f
        "full" -> 1f
        else -> 0f
    }

    private fun on(v: Boolean?, yes: String, no: String) = when (v) {
        true -> yes
        false -> no
        null -> ""
    }

    /**
     * The brief as request lines: one line per section, only what the author set. For a scheme only the content,
     * motion and zones (its look is drawn by Lumen).
     */
    fun briefLines(b: EditBrief, kind: String, ctx: InsertCtx): List<String> {
        val ty = type(b.type)
        val out = mutableListOf<String>()
        fun line(head: String, parts: List<String>) {
            val p = parts.filter { it.isNotBlank() }
            if (p.isNotEmpty()) out += head + ": " + p.joinToString("; ")
        }
        line(t("тип", "type"), listOf(ty.ru.lowercase(Locale.ROOT) + (if (ty.task.isNotEmpty()) " — " + ty.task else "")))
        if (b.what.isNotBlank()) line(t("что показать", "what to show"), listOf(b.what.trim()))
        val f = ty.fields.mapNotNull { id ->
            val v = b.fields[id]?.trim().orEmpty()
            if (v.isEmpty()) null else fieldName(id).lowercase(Locale.ROOT) + " — " + (fieldChoices(id).firstOrNull { it.first == v }?.second ?: v.replace('\n', '/'))
        }
        line(t("поля", "fields"), f)
        if (b.items.isNotEmpty()) line(t("данные", "data"), listOf(b.items.joinToString("; ") { i ->
            listOf(i.icon, i.label, listOf(i.value, i.unit).filter { it.isNotEmpty() }.joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" ").let { if (i.main) "$it ★" else it }
        }))
        line(t("содержание", "content"), listOf(
            when (b.source) {
                "speech" -> t("бери из речи", "take it from the speech")
                "data" -> t("бери из данных автора", "take it from the author's data")
                "wish" -> t("по описанию автора", "by the author's description")
                else -> ""
            },
            if (b.onlySaid) t("только сказанное и данные автора, без новых фактов и чисел", "only what is said and the author's data, no new facts or numbers") else "",
            when {
                b.title == "-" -> t("без заголовка", "no title")
                b.title.isNotBlank() -> t("заголовок «", "title \"") + b.title.trim() + t("»", "\"")
                else -> ""
            },
            if (b.count > 0) t("элементов: ", "elements: ") + b.count else "",
            when (b.labels) {
                "short" -> t("подписи 1–3 слова", "labels of 1–3 words")
                "medium" -> t("подписи до 6 слов", "labels of up to 6 words")
                "phrase" -> t("подписи — короткие фразы", "labels are short phrases")
                else -> ""
            },
            when (b.icons) {
                "emoji" -> t("иконки — эмодзи", "icons are emoji")
                "none" -> t("без иконок", "no icons")
                else -> ""
            },
            if (kind == "scheme" && b.layout.isNotEmpty()) "layout: " + b.layout else "",
            when (b.tone) {
                "business" -> t("тон деловой", "a business tone")
                "friendly" -> t("тон дружелюбный", "a friendly tone")
                "fun" -> t("тон с юмором", "a playful tone")
                "drama" -> t("тон драматичный", "a dramatic tone")
                "kids" -> t("для детей", "for kids")
                else -> ""
            }
        ))
        if (kind == "anim") {
            line(t("вид", "look"), listOf(
                fillText(b.fill, b.bgAlpha) + if (b.fill.isNotEmpty() && b.fill != "none") " " + hex(ctx.card) else "",
                if (b.accent != null || b.style.isNotEmpty()) t("акцент ", "accent ") + hex(b.accent ?: ctx.accent) else "",
                when (b.palette) {
                    "mono" -> t("палитра: монохром + акцент", "palette: monochrome + the accent")
                    "bright" -> t("палитра яркая", "a bright palette")
                    "pastel" -> t("палитра пастельная", "a pastel palette")
                    else -> ""
                },
                when (b.font) {
                    "condensed" -> t("шрифт узкий (sans-serif-condensed)", "a condensed font (sans-serif-condensed)")
                    "serif" -> t("шрифт с засечками (serif)", "a serif font")
                    "mono" -> t("шрифт моноширинный (monospace)", "a monospace font")
                    "rounded" -> t("шрифт округлый (жирный sans-serif, скругления)", "a rounded font (bold sans-serif, round shapes)")
                    "sans" -> t("шрифт обычный sans-serif", "a plain sans-serif font")
                    else -> ""
                },
                on(b.upper, t("КАПС", "ALL CAPS"), t("обычный регистр", "normal case")),
                if (b.textSize.isNotEmpty()) t("текст ", "text ") + b.textSize.uppercase(Locale.ROOT) + t(" — не мельче ", " — not smaller than ") + minFontPx(ctx.fw, ctx.fh, b.textSize) + " px"
                else t("текст не мельче ", "text not smaller than ") + minFontPx(ctx.fw, ctx.fh, "m") + " px",
                when (b.radius) {
                    "none" -> t("углы прямые", "square corners")
                    "s" -> t("скругление малое", "small rounding")
                    "m" -> t("скругление среднее", "medium rounding")
                    "l" -> t("скругление большое", "large rounding")
                    else -> ""
                },
                on(b.border, t("тонкая обводка", "a thin outline"), t("без обводки", "no outline")),
                on(b.shadow, t("с тенью", "with a shadow"), t("без теней", "no shadows")),
                b.avoidStyle.joinToString(", ") { s ->
                    when (s) {
                        "emoji" -> t("без эмодзи", "no emoji")
                        "arrows" -> t("без стрелок", "no arrows")
                        "colors2" -> t("не больше 2 цветов", "at most 2 colours")
                        "gradients" -> t("без градиентов", "no gradients")
                        "shadows" -> t("без теней", "no shadows")
                        else -> s
                    }
                }
            ))
            if (b.dim > 0.01f) line(t("видео под блоком", "video under the block"), listOf(
                t("затемнено на ", "darkened by ") + pct(b.dim) + (if (b.dimAll) t(" (весь кадр)", " (the whole frame)") else "") +
                    t(" — фон темнее, текст светлый", " — the background is darker, light text")))
            if (b.opacity < 0.99f) line(t("прозрачность блока", "block opacity"), listOf(pct(b.opacity) + t(" — Lumen применит сам, рисуй непрозрачно", " — Lumen applies it, draw opaque")))
        }
        line(t("движение", "motion"), listOf(
            when (b.reveal) {
                "words" -> t("элементы появляются на своих словах речи", "elements appear on their words")
                "steps" -> t("элементы появляются по очереди равномерно", "elements appear one by one evenly")
                "all" -> t("всё появляется сразу", "everything appears at once")
                else -> ""
            },
            paceText(b.pace),
            if (b.emphasis) t("главное подсветить на его слове", "stress the main thing on its word") else "",
            when {
                b.holdMs == 0L -> t("финал не держать", "do not hold the end")
                b.holdMs > 0 -> t("держать готовый вид ", "hold the finished look ") + secs(b.holdMs) + t(" с в конце", " s at the end")
                else -> t("держать готовый вид не меньше 1,5 с в конце", "hold the finished look at least 1.5 s at the end")
            },
            if (kind == "anim") when {
                b.enter == "code" && b.exit == "code" -> t("появление и исчезновение блока рисуй сам", "draw the block's entrance and exit yourself")
                b.enter == "code" -> t("появление рисуй сам, исчезновение сделает Lumen", "draw the entrance yourself, Lumen makes the exit")
                b.exit == "code" -> t("появление сделает Lumen, исчезновение рисуй сам", "Lumen makes the entrance, draw the exit yourself")
                else -> t("появление и исчезновение сделает Lumen — с первого до последнего кадра блок виден целиком", "Lumen makes the entrance and exit — the block is fully visible from the first to the last frame")
            } else "",
            if (kind == "anim" && b.loopMs > 0) t("цикл ", "a loop of ") + secs(b.loopMs) + t(" с: кадр в t и t+цикл одинаковый, напиши // loop: ", " s: the frame at t and t+loop is the same, write // loop: ") + secs(b.loopMs) else ""
        ))
        val zones = avoidZones(b, ctx)
        if (zones.isNotEmpty()) line(t("не закрывать (доли блока x, y, w, h)", "keep clear (fractions of the block x, y, w, h)"),
            zones.map { (n, r) -> n + " " + r.joinToString(" ") { String.format(Locale.US, "%.2f", it) } })
        if (kind == "anim" && b.type == "pointer" && b.target.size >= 2) {
            val (bx, by) = toBlock(b.target[0], b.target[1], ctx)
            line(t("указать на точку", "point at"), listOf(String.format(Locale.US, "x %.2f, y %.2f ", bx, by) + t("(доли блока; data.brief.target)", "(fractions of the block; data.brief.target)")))
        }
        line(t("похоже на", "looks like"), listOf(b.like.trim(), if (b.refImage.isNotEmpty()) t("образец — на приложенной картинке", "a sample is on the attached picture") else ""))
        if (b.dont.isNotBlank()) line(t("не делать", "do not"), listOf(b.dont.trim()))
        if (ctx.others.isNotEmpty()) line(t("в это время на экране уже есть", "already on screen then"), ctx.others.take(4))
        return out
    }

    /** A point of the frame in fractions of the block of [ctx] (the block not turned). */
    fun toBlock(fx: Float, fy: Float, ctx: InsertCtx): Pair<Float, Float> {
        val bw = ctx.box.w * ctx.fw
        val bh = bw * ctx.box.ratio
        val l = ctx.box.x * ctx.fw - bw / 2
        val tp = ctx.box.y * ctx.fh - bh / 2
        return ((fx * ctx.fw - l) / bw) to ((fy * ctx.fh - tp) / bh)
    }

    /** Zones the block must keep clear, in fractions of the block (clipped to it): name and x, y, w, h. */
    fun avoidZones(b: EditBrief, ctx: InsertCtx): List<Pair<String, List<Float>>> {
        val portrait = ctx.fh > ctx.fw
        val zones = mutableListOf<Pair<String, List<Float>>>()
        if ("shorts" in b.avoid && portrait) {
            zones += t("интерфейс Shorts снизу", "Shorts UI at the bottom") to listOf(0f, 0.82f, 1f, 0.18f)
            zones += t("кнопки Shorts справа", "Shorts buttons on the right") to listOf(0.88f, 0.45f, 0.12f, 0.55f)
        }
        if ("subs" in b.avoid && ctx.subs) zones += t("субтитры", "subtitles") to (if (portrait) listOf(0.04f, 0.7f, 0.92f, 0.16f) else listOf(0.1f, 0.8f, 0.8f, 0.14f))
        if ("face" in b.avoid && b.face.size >= 4) zones += t("лицо", "the face") to b.face.take(4)
        val bw = ctx.box.w * ctx.fw
        val bh = bw * ctx.box.ratio
        val l = ctx.box.x * ctx.fw - bw / 2
        val tp = ctx.box.y * ctx.fh - bh / 2
        return zones.mapNotNull { (n, z) ->
            val zl = (z[0] * ctx.fw - l) / bw
            val zt = (z[1] * ctx.fh - tp) / bh
            val zr = ((z[0] + z[2]) * ctx.fw - l) / bw
            val zb = ((z[1] + z[3]) * ctx.fh - tp) / bh
            val cl = zl.coerceIn(0f, 1f)
            val ct = zt.coerceIn(0f, 1f)
            val cr = zr.coerceIn(0f, 1f)
            val cb = zb.coerceIn(0f, 1f)
            if (cr - cl < 0.02f || cb - ct < 0.02f) null else n to listOf(cl, ct, cr - cl, cb - ct)
        }
    }

    /** The speech of the block for the request: words with numbers [N] (a scheme) or seconds from the block start (an animation). */
    fun speechText(kind: String, ctx: InsertCtx): String {
        val ws = ctx.words
        if (ws.isEmpty()) return ""
        val end = ctx.startMs + ctx.durMs
        val inside = ws.indices.filter { ws[it].startMs >= ctx.startMs && ws[it].startMs < end }
        if (inside.isEmpty()) return ""
        val before = ws.indices.filter { ws[it].startMs >= ctx.startMs - 2000 && ws[it].startMs < ctx.startMs }
        val after = ws.indices.filter { ws[it].startMs >= end && ws[it].startMs < end + 2000 }
        fun one(i: Int) = if (kind == "scheme") "[$i]" + ws[i].text
        else ws[i].text + " " + secs(ws[i].startMs - ctx.startMs) + "–" + secs(ws[i].endMs - ctx.startMs)
        return buildString {
            if (before.isNotEmpty()) append(t("(до блока: ", "(before the block: ")).append(before.joinToString(" ") { ws[it].text }).append(")\n")
            append(inside.joinToString(" ") { one(it) })
            if (after.isNotEmpty()) append(t("\n(после блока: ", "\n(after the block: ")).append(after.joinToString(" ") { ws[it].text }).append(")")
        }
    }

    /** Role of the AI (the system message of the built-in AI, the role of the copied request). */
    fun role(kind: String): String = if (kind == "scheme")
        t("Ты инфографист видеороликов. Отвечай только JSON.", "You are an infographic designer for videos. Answer with JSON only.")
    else t("Ты моушн-дизайнер и JavaScript-разработчик анимаций для видео. Отвечай только кодом.", "You are a motion designer and a JavaScript animation developer for videos. Answer with code only.")

    /** The request of the wizard: the same text for the built-in AI and (wrapped by [RelayText.request]) for another AI. */
    fun request(kind: String, b: EditBrief, ctx: InsertCtx, req: String): String = buildString {
        val (pw, ph) = boxPx(ctx)
        append(t("МЕТКА ЗАПРОСА: #", "REQUEST MARK: #")).append(req).append(t(" — повтори её в ответе, как сказано в конце.\n", " — repeat it in the answer as said at the end.\n"))
        append(t("ЗАДАНИЕ: ", "TASK: ")).append(
            if (kind == "scheme") t("сделай одну инфосхему, которую Lumen вставит поверх ролика и анимирует сам (узлы появятся на своих словах).",
                "make one info-scheme that Lumen puts over the video and animates itself (nodes appear on their words).")
            else t("напиши анимационный блок — JavaScript по протоколу $ANIM; Lumen отрисует его в кадры и наложит на ролик.",
                "write an animation block — JavaScript by protocol $ANIM; Lumen renders it to frames and lays it over the video.")
        ).append('\n')
        append(t("КАДР: ", "FRAME: ")).append(formatText(ctx.fw, ctx.fh)).append(", ").append(ctx.fw).append('×').append(ctx.fh)
            .append(t("; блок ", "; the block ")).append(pw).append('×').append(ph).append(" px — ").append(pct(ctx.box.w))
            .append(t(" ширины кадра, центр в ", " of the frame width, centred at ")).append(String.format(Locale.US, "x %.2f, y %.2f", ctx.box.x, ctx.box.y))
            .append(t(" (доли кадра).\n", " (fractions of the frame).\n"))
        append(t("ВРЕМЯ: блок ", "TIME: the block lasts ")).append(secs(ctx.durMs)).append(t(" с — с ", " s — from ")).append(StudioEdit.clock(ctx.startMs))
            .append(t(" по ", " to ")).append(StudioEdit.clock(ctx.startMs + ctx.durMs)).append(t(" ролика.\n", " of the video.\n"))
        append(t("ЯЗЫК подписей: ", "LANGUAGE of the labels: ")).append(b.lang.ifBlank { ctx.lang }).append(".\n")
        val sp = speechText(kind, ctx)
        if (sp.isNotEmpty()) append(t("РЕЧЬ в блоке (", "SPEECH in the block (")).append(if (kind == "scheme") t("слова с номерами [N]", "words with numbers [N]") else t("слово и секунды от начала блока", "word and seconds from the block start")).append("):\n").append(sp).append('\n')
        else append(t("РЕЧИ с таймингом нет — опирайся на бриф.\n", "No timed SPEECH — follow the brief.\n"))
        val lines = briefLines(b, kind, ctx)
        if (lines.isNotEmpty()) {
            append(t("БРИФ (только то, что задал автор; остальное выбери сам):\n", "BRIEF (only what the author set; choose the rest yourself):\n"))
            lines.forEach { append("• ").append(it).append('\n') }
        }
        append('\n')
        if (kind == "scheme") {
            append(StudioScheme.PROTOCOL).append('\n')
            append(t("ОТВЕТ: строго JSON одной схемы, первое поле — метка: ", "ANSWER: strictly the JSON of one scheme, the first field is the mark: "))
            append("{\"req\":\"").append(req).append("\",\"layout\":\"...\",\"title\":\"\",\"nodes\":[...]}")
        } else {
            append(ANIM_PROTOCOL).append('\n')
            AnimExamples.forType(b.type)?.let { ex ->
                append(t("ПРИМЕР хорошего ответа такого типа (сделай под этот бриф, можно лучше):\n```javascript\n", "A SAMPLE of a good answer of this type (make yours for this brief, better is welcome):\n```javascript\n"))
                append(ex.code).append("\n```\n")
            }
            append(t("ОТВЕТ: один блок ```javascript. Первые строки: ", "ANSWER: one ```javascript block. First lines: "))
            append("// $ANIM #").append(req).append(t(" и // name: короткое название (2–4 слова)", " and // name: a short name (2–4 words)"))
            append(t("; для цикла ещё // loop: секунды. Дальше function frame(t, ctx, W, H, data) { … } и при желании function setup(data) { … }. Без HTML и пояснений.",
                "; for a loop also // loop: seconds. Then function frame(t, ctx, W, H, data) { … } and optionally function setup(data) { … }. No HTML, no explanations."))
        }
    }

    /** A request to fix an answer: the errors found or the author's correction, and the answer as it was. */
    fun fixRequest(kind: String, errors: List<String>, correction: String, answer: String, b: EditBrief, ctx: InsertCtx, req: String): String = buildString {
        append(request(kind, b, ctx, req)).append("\n\n")
        if (errors.isNotEmpty()) append(t("ОШИБКИ прошлого ответа (исправь их):\n", "ERRORS of the previous answer (fix them):\n")).append(errors.joinToString("\n") { "• $it" }).append('\n')
        if (correction.isNotBlank()) append(t("ПРАВКА АВТОРА (сделай именно это, остальное оставь как было): ", "AUTHOR'S CORRECTION (do exactly this, keep the rest as it was): ")).append(correction.trim()).append('\n')
        append(t("ПРОШЛЫЙ ОТВЕТ:\n", "PREVIOUS ANSWER:\n")).append(answer.take(WebLayer.MAX_CODE))
    }

    /** Quick corrections of the check step: id and the text sent to the AI. */
    val CORRECTIONS = listOf(
        "bigger" to (t("Крупнее текст", "Bigger text") to t("сделай весь текст крупнее примерно в 1,3 раза", "make all the text about 1.3 times bigger")),
        "fewer" to (t("Меньше слов", "Fewer words") to t("сократи подписи и убери лишние элементы", "shorten the labels and drop needless elements")),
        "slower" to (t("Медленнее", "Slower") to t("сделай движения плавнее и медленнее", "make the moves smoother and slower")),
        "faster" to (t("Быстрее", "Faster") to t("сделай движения быстрее и энергичнее", "make the moves faster and punchier")),
        "colors" to (t("Другие цвета", "Other colours") to t("подбери другую палитру, акцент оставь", "pick another palette, keep the accent")),
        "noicons" to (t("Без иконок", "No icons") to t("убери иконки и эмодзи", "remove icons and emoji")),
        "simpler" to (t("Проще", "Simpler") to t("сделай проще и чище: меньше деталей и эффектов", "make it simpler and cleaner: fewer details and effects")),
        "accent" to (t("Ярче акцент", "Stronger accent") to t("сделай главное заметнее: крупнее, акцентным цветом", "make the main thing stand out: bigger, in the accent colour"))
    )

    // ---------- the answer ----------

    private val TAG = Regex("""#([A-HJ-NP-Z2-9]{4})\b""")
    private val REQ = Regex(""""req"\s*:\s*"#?([A-HJ-NP-Z2-9]{4})"""")
    private val LOOP = Regex("""//\s*loop\s*:\s*([0-9]+(?:[.,][0-9]+)?)""")
    private val FRAME = Regex("""function\s+frame\s*\(|(?:const|let|var)\s+frame\s*=|window\.frame\s*=""")

    /** The mark of the request an answer brings back ("" = none). */
    fun tagOf(s: String): String = REQ.find(s)?.groupValues?.get(1) ?: TAG.find(s.take(600))?.groupValues?.get(1).orEmpty()

    /** The loop of an animation from its // loop: N line, ms (0 = none). */
    fun loopOf(code: String): Long = LOOP.find(code.take(1500))?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        ?.let { (it * 1000).toLong().coerceIn(0, MAX_ANIM_MS) } ?: 0L

    /** The name of an animation: its // name: line, else the fallback. */
    fun nameOf(code: String, fallback: String): String {
        Regex("""//\s*name\s*:\s*(.+)""").find(code.take(2000))?.let { return it.groupValues[1].trim().take(40) }
        return fallback.ifBlank { t("Анимация", "Animation") }
    }

    /** Clipboard text or a pasted answer: a scheme (JSON) or an animation (code), null when neither or our own request. */
    fun detect(raw: String?): InsertAnswer? {
        val s0 = raw?.trim().orEmpty()
        if (s0.length < 12 || RelayText.isOwnRequest(s0) || s0.startsWith(t("МЕТКА ЗАПРОСА", "REQUEST MARK"))) return null
        val s = RelayText.clean(s0, false)
        if (FRAME.containsMatchIn(s)) {
            val code = WebLayer.extract(s).trim()
            if (code.isEmpty() || code.length > WebLayer.MAX_CODE || !FRAME.containsMatchIn(code)) return null
            return InsertAnswer.Anim(code, tagOf(s), nameOf(code, ""), loopOf(code))
        }
        if (!s.contains('{')) return null
        val js = RelayText.clean(s0, true)
        val sc = StudioEdit.parseScheme(js) ?: return null
        if (sc.nodes.isEmpty()) return null
        return InsertAnswer.Scheme(sc, tagOf(js), sc.title)
    }

    /** Problems of an animation answer worth fixing before it is tried (empty = fine). */
    fun animProblems(code: String): List<String> {
        val p = WebLayer.problems(code).toMutableList()
        if (Regex("""\bnew\s+Image\s*\(|\.src\s*=|@import|url\s*\(""").containsMatchIn(code))
            p += t("картинки и шрифты по ссылке не загрузятся — рисуй фигурами и системными шрифтами", "pictures and fonts by URL do not load — draw with shapes and system fonts")
        if (Regex("""\bdocument\.body|innerHTML|\balert\s*\(""").containsMatchIn(code))
            p += t("нельзя трогать страницу (DOM, alert) — только ctx", "do not touch the page (DOM, alert) — only ctx")
        return p.distinct()
    }

    // ---------- the animation ----------

    /** The data an animation gets as data: sizes, colours, the speech of the block (seconds from its start) and the brief. */
    fun animData(b: EditBrief, ctx: InsertCtx, w: Int, h: Int, fps: Int): JSONObject {
        val ws = ctx.words.filter { it.startMs >= ctx.startMs && it.startMs < ctx.startMs + ctx.durMs }
        fun s(ms: Long) = Math.round(ms / 10.0) / 100.0
        val words = JSONArray().apply {
            ws.forEach { put(JSONObject().put("w", it.text).put("s", s(it.startMs - ctx.startMs)).put("e", s(it.endMs - ctx.startMs))) }
        }
        val br = JSONObject().put("type", b.type)
            .put("items", JSONArray().apply { b.items.forEach { i -> put(JSONObject().put("label", i.label).put("value", i.value).put("unit", i.unit).put("icon", i.icon).put("main", i.main)) } })
            .put("fields", JSONObject(b.fields as Map<*, *>))
            .put("title", if (b.title == "-") "" else b.title).put("pace", b.pace.ifEmpty { "normal" }).put("reveal", b.reveal.ifEmpty { if (ws.isEmpty()) "steps" else "words" })
            .put("hold", if (b.holdMs >= 0) s(b.holdMs) else 1.5).put("bg", b.fill.ifEmpty { "semi" })
            .put("bgAlpha", (if (b.bgAlpha >= 0f) b.bgAlpha else defaultAlpha(b.fill.ifEmpty { "semi" })).toDouble())
            .put("radius", b.radius).put("font", b.font.ifEmpty { ctx.font }).put("upper", b.upper == true)
            .put("minFont", (minFontPx(ctx.fw, ctx.fh, b.textSize) * w / max(1f, ctx.box.w * ctx.fw)).roundToInt())
            .put("palette", b.palette).put("dimmed", b.dim.toDouble()).put("tone", b.tone).put("icons", b.icons).put("labels", b.labels)
            .put("count", b.count).put("emphasis", b.emphasis).put("onlySaid", b.onlySaid)
            .put("enter", if (b.enter == "code") "code" else "lumen").put("exit", if (b.exit == "code") "code" else "lumen")
            .put("avoid", JSONArray().apply { avoidZones(b, ctx).forEach { (_, r) -> put(JSONObject().put("x", r[0].toDouble()).put("y", r[1].toDouble()).put("w", r[2].toDouble()).put("h", r[3].toDouble())) } })
        if (b.target.size >= 2) toBlock(b.target[0], b.target[1], ctx).let { (x, y) -> br.put("target", JSONObject().put("x", x.toDouble()).put("y", y.toDouble())) }
        return JSONObject().put("dur", s(ctx.durMs)).put("duration", s(ctx.durMs)).put("w", w).put("h", h).put("fps", fps).put("lang", b.lang.ifBlank { ctx.lang })
            .put("accent", hex(b.accent ?: ctx.accent)).put("ink", hex(ctx.ink)).put("card", hex(ctx.card)).put("font", b.font.ifEmpty { ctx.font })
            .put("wish", b.what).put("title", if (b.title == "-") "" else b.title).put("words", words).put("scenes", JSONArray())
            .put("place", JSONObject().put("fw", ctx.fw).put("fh", ctx.fh).put("x", ctx.box.x.toDouble()).put("y", ctx.box.y.toDouble()).put("w", ctx.box.w.toDouble()).put("ratio", ctx.box.ratio.toDouble()))
            .put("brief", br)
    }

    /** Frames of an animation of [durMs] at [fps]; a loop renders only one cycle. */
    fun frameCount(durMs: Long, fps: Int, loopMs: Long): Int {
        val len = if (loopMs > 0) min(loopMs, durMs) else durMs
        return max(1, (len * fps / 1000.0).roundToInt())
    }

    /**
     * Frame of a rendered animation at [relMs] from the block start: a loop repeats, frames rendered for another
     * length ([renderDurMs]) are stretched over the block until it is rendered again.
     */
    fun frameIndex(relMs: Long, blockDurMs: Long, renderDurMs: Long, fps: Int, loopMs: Long, count: Int): Int {
        if (count <= 0 || fps <= 0) return -1
        val r = relMs.coerceIn(0, max(0, blockDurMs - 1))
        val tm = when {
            loopMs > 0 -> r % loopMs
            renderDurMs > 0 && renderDurMs != blockDurMs -> r * renderDurMs / max(1L, blockDurMs)
            else -> r
        }
        return (tm * fps / 1000).toInt().coerceIn(0, count - 1)
    }

    /** Frame of animation block [b] at [tMs] (for the overlay key of the export). */
    fun frameAt(b: EBlock, tMs: Long): Int = frameIndex(tMs - b.startMs, b.durMs, b.durMs, b.fps, b.loopMs, frameCount(b.durMs, b.fps, b.loopMs))

    /** A stable short hash (FNV-1a 64) as hex. */
    fun hash(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0').take(12)
    }

    /** Folder of the frames of one render: the code, size, length, rate, loop and data decide it. */
    fun renderKey(code: String, w: Int, h: Int, durMs: Long, fps: Int, loopMs: Long, data: String): String =
        "r_" + hash("$w|$h|$durMs|$fps|$loopMs|" + hash(code) + "|" + hash(data))

    /** Folder of an animation's code inside the project: anim/<hash of the code>. */
    fun codeDir(code: String): String = "anim/" + hash(code.trim())

    /** The animation of block [b] needs a new render: its frames are of another size, length or rate ([meta] of the frames). */
    fun stale(b: EBlock, metaW: Int, metaH: Int, metaDurMs: Long, wantW: Int, wantH: Int): Boolean =
        metaDurMs != b.durMs || abs(metaW - wantW) > 2 || abs(metaH - wantH) > 2

    /** A canvas template of the Montage tab (lumen.canvas: video time, the whole frame) as an animation block from [t0Ms]. */
    fun adaptCanvas(code: String, t0Ms: Long): String =
        "// name: " + WebLayer.nameOf(code, t("Шаблон Монтажа", "Montage template")) + "\n" + code.trim() +
            "\n;(function(){var __f=frame;frame=function(t,ctx,W,H,d){return __f(t+" + String.format(Locale.US, "%.3f", t0Ms / 1000.0) + ",ctx,W,H,d)}})();\n"

    // ---------- place, time and readability ----------

    /** Reading time of what the block will show: about 0.3 s a word plus a second, the type's own minimum. */
    fun readMs(b: EditBrief, speechWords: Int): Long {
        val shown = b.items.sumOf { (it.label + " " + it.value).split(' ').count { w -> w.isNotBlank() } } +
            b.fields.values.sumOf { v -> v.split(' ', '\n').count { it.isNotBlank() } } +
            (if (b.title.length > 1) b.title.split(' ').size else 0)
        val est = if (shown > 0) shown else when (b.type) {
            "number", "cta", "lower3", "pointer", "progress" -> 3
            else -> max(6, min(14, speechWords / 2))
        }
        val min = when (b.type) {
            "number", "pointer", "cta" -> 2500L
            else -> 3000L
        }
        return max(min, 1000L + est * 300L).coerceAtMost(MAX_ANIM_MS)
    }

    /** Width of a block of size [size] (s, m, l, xl) as a fraction of the frame width. */
    fun widthOf(size: String, portrait: Boolean): Float = when (size) {
        "s" -> if (portrait) 0.5f else 0.36f
        "l" -> if (portrait) 0.92f else 0.66f
        "xl" -> if (portrait) 0.98f else 0.86f
        else -> if (portrait) 0.8f else 0.5f
    }

    /** Height / width of a block of [shape] for the type [typeId] and [kind] in a [fw]×[fh] frame. */
    fun ratioOf(shape: String, typeId: String, kind: String, fw: Int, fh: Int): Float = when (shape) {
        "wide" -> 0.45f
        "square" -> 1f
        "tall" -> 1.5f
        else -> if (kind == "scheme") StudioEdit.schemeRatio(fw, fh) else when (typeId) {
            "number" -> 0.5f
            "lower3" -> 0.28f
            "kinetic" -> 0.45f
            "cta", "progress" -> 0.36f
            "quote" -> 0.6f
            "chart", "compare", "proscons" -> 0.75f
            else -> 0.7f
        }
    }

    /** Where a block of brief [b] stands in a [fw]×[fh] frame: the place preset, size and shape, away from the face. */
    fun place(b: EditBrief, kind: String, fw: Int, fh: Int): PlaceBox {
        val portrait = fh > fw
        if (b.place == "full" || b.place == "cover") return PlaceBox(0.5f, 0.5f, 1f, fh.toFloat() / max(1, fw))
        var w = widthOf(if (b.place == "corner" && b.size.isEmpty()) "s" else b.size, portrait)
        var ratio = ratioOf(b.shape, b.type, kind, fw, fh)
        // not taller than the frame allows
        val maxH = 0.9f * fh
        if (w * fw * ratio > maxH) w = maxH / (fw * ratio)
        val hh = w * fw * ratio / 2f / fh
        val top = (if (portrait) 0.09f else 0.06f) + hh
        val bottom = (if (portrait) (if ("subs" in b.avoid) 0.68f else 0.8f) else 0.9f) - hh
        var x = 0.5f
        var y = when (b.place) {
            "top" -> top
            "bottom" -> bottom
            "corner" -> top
            else -> 0.5f
        }
        if (b.place == "corner") x = if (portrait) 0.05f + w / 2 else 0.95f - w / 2
        if (portrait && b.place != "corner") x = min(x, 0.88f - w / 2).coerceAtLeast(w / 2)
        // the face: the block goes above or below it, where there is more room
        if ("face" in b.avoid && b.face.size >= 4 && (b.place.isEmpty() || b.place == "center")) {
            val fTop = b.face[1]
            val fBot = b.face[1] + b.face[3]
            val boxTop = y - hh
            val boxBot = y + hh
            if (boxBot > fTop && boxTop < fBot) y = if (fTop - 0.09f > (if (portrait) 0.8f else 0.92f) - fBot) top.coerceAtMost(fTop - hh - 0.01f).coerceAtLeast(top)
            else bottom.coerceAtLeast(fBot + hh + 0.01f).coerceAtMost(bottom)
        }
        y = y.coerceIn(min(0.5f, hh + 0.01f), max(0.5f, 0.99f - hh))
        ratio = ratio.coerceIn(0.05f, 20f)
        return PlaceBox(x, y, w.coerceIn(StudioEdit.MIN_W, StudioEdit.MAX_W), ratio)
    }

    /**
     * Best contrast text can get on a plate of colour [plate] at opacity [alpha] over a video of mean luminance
     * [videoLum] (0..1) darkened by [dim]: the better of light and dark text.
     */
    fun contrastOver(plate: Int, alpha: Float, videoLum: Float, dim: Float): Double {
        val g = (videoLum.coerceIn(0f, 1f) * 255 * (1 - dim.coerceIn(0f, 1f))).roundToInt()
        fun mix(c: Int, sh: Int) = (((c shr sh) and 0xFF) * alpha + g * (1 - alpha)).roundToInt().coerceIn(0, 255)
        val a = alpha.coerceIn(0f, 1f)
        val bg = (0xFF shl 24) or (mix(plate, 16) shl 16) or (mix(plate, 8) shl 8) or mix(plate, 0)
        val bgC = if (a <= 0f) (0xFF shl 24) or (g shl 16) or (g shl 8) or g else bg
        return max(SchemeStyles.contrast(0xFFFFFFFF.toInt(), bgC), SchemeStyles.contrast(0xFF14161B.toInt(), bgC))
    }

    /** Soft warnings of the brief, each with a fix; [describe] blocks sending. */
    fun warnings(b: EditBrief, kind: String, ctx: InsertCtx, speechWords: Int, videoLum: Float?): List<InsertWarn> {
        val out = mutableListOf<InsertWarn>()
        val emptyContent = speechWords == 0 && b.what.isBlank() && b.items.isEmpty() && b.fields.values.all { it.isBlank() }
        if (emptyContent) out += InsertWarn("describe", t("Нет ни речи, ни данных, ни описания — опишите, что показать", "No speech, data or description — describe what to show"),
            "describe", t("Описать", "Describe"), blocking = true)
        val need = readMs(b, speechWords)
        if (ctx.durMs + 300 < need) out += InsertWarn("short", t("Блок короче времени чтения: нужно ≈ ", "The block is shorter than the reading time: needs ≈ ") + (need / 100) / 10.0 + t(" с", " s"),
            "longer", t("Удлинить до ", "Make it ") + (need / 100) / 10.0 + t(" с", " s"))
        val n = if (b.count > 0) b.count else b.items.size
        if ((kind == "scheme" && n > StudioScheme.MAX_NODES) || (n > 5 && ctx.box.w < 0.6f)) out += InsertWarn("many",
            t("Элементов $n — на телефоне будет мелко", "$n elements — too small on a phone"), "fewer", t("Оставить 5", "Keep 5"))
        if (b.opacity < 0.6f) out += InsertWarn("opacity", t("Прозрачность блока ", "Block opacity ") + pct(b.opacity) + t(" — текст может не читаться", " — the text may not read"),
            "opaque", t("Сделать 100 %", "Make it 100 %"))
        if (videoLum != null && b.fill != "full") {
            val alpha = if (b.fill == "none") 0f else if (b.bgAlpha >= 0f) b.bgAlpha else defaultAlpha(b.fill.ifEmpty { "semi" })
            val c = contrastOver(ctx.card, alpha, videoLum, b.dim)
            if (c < 4.5) out += InsertWarn("contrast", t("Текст может плохо читаться на этом кадре (контраст ", "The text may read badly on this frame (contrast ") +
                String.format(Locale.US, "%.1f", c) + ":1)", if (alpha < 0.8f) "denser" else "dim",
                if (alpha < 0.8f) t("Плотнее подложка", "Denser plate") else t("Затемнить видео 40 %", "Darken the video 40 %"))
        }
        val zones = avoidZones(b, ctx)
        if (zones.isNotEmpty() && b.place != "full" && b.place != "cover" && b.type != "pointer") {
            val big = zones.filter { (_, r) -> r[2] * r[3] > 0.04f }
            if (big.isNotEmpty()) out += InsertWarn("zone", t("Блок заходит на: ", "The block covers: ") + big.joinToString(", ") { it.first }, "move", t("Сдвинуть", "Move it"))
        }
        if (b.textSize == "s" && ctx.fh > ctx.fw && ctx.box.w < 0.7f) out += InsertWarn("small", t("Мелкий текст на узком блоке — на телефоне не прочитать", "Small text on a narrow block — unreadable on a phone"),
            "bigger", t("Крупнее", "Bigger"))
        return out
    }

    /** The look of a block from its brief (applied by Lumen, see [BlockLook]). */
    fun lookOf(b: EditBrief): BlockLook? {
        val l = BlockLook(
            fill = b.fill, bgAlpha = b.bgAlpha, accent = b.accent, font = b.font, upper = b.upper,
            textScale = when (b.textSize) {
                "s" -> 0.85f
                "l" -> 1.2f
                else -> 1f
            },
            radius = when (b.radius) {
                "none" -> 0f
                "s" -> 0.08f
                "m" -> 0.18f
                "l" -> 0.32f
                else -> -1f
            },
            border = b.border, shadow = b.shadow
        )
        return if (l == BlockLook()) null else l
    }

    // ---------- JSON of the brief ----------

    fun briefJson(b: EditBrief): JSONObject = JSONObject().apply {
        val d = EditBrief()
        put("type", b.type)
        if (b.kind.isNotEmpty()) put("kind", b.kind)
        if (b.what.isNotEmpty()) put("what", b.what)
        if (b.source.isNotEmpty()) put("source", b.source)
        if (b.items.isNotEmpty()) put("items", JSONArray().apply {
            b.items.forEach { i -> put(JSONObject().put("label", i.label).put("value", i.value).put("unit", i.unit).put("icon", i.icon).put("main", i.main)) }
        })
        if (b.fields.isNotEmpty()) put("fields", JSONObject(b.fields as Map<*, *>))
        if (!b.onlySaid) put("onlySaid", false)
        fun s(k: String, v: String) { if (v.isNotEmpty()) put(k, v) }
        s("title", b.title); s("labels", b.labels); s("icons", b.icons); s("lang", b.lang); s("tone", b.tone); s("layout", b.layout)
        s("style", b.style); s("palette", b.palette); s("fill", b.fill); s("radius", b.radius); s("font", b.font); s("textSize", b.textSize)
        s("like", b.like); s("refImage", b.refImage); s("dont", b.dont); s("enter", b.enter); s("reveal", b.reveal); s("pace", b.pace)
        s("exit", b.exit); s("sfx", b.sfx); s("place", b.place); s("size", b.size); s("shape", b.shape); s("layer", b.layer)
        if (b.count > 0) put("count", b.count)
        b.accent?.let { put("accent", it) }
        if (b.bgAlpha >= 0f) put("bgAlpha", b.bgAlpha.toDouble())
        b.border?.let { put("border", it) }
        b.shadow?.let { put("shadow", it) }
        b.upper?.let { put("upper", it) }
        if (b.avoidStyle.isNotEmpty()) put("avoidStyle", JSONArray(b.avoidStyle))
        if (b.opacity != 1f) put("opacity", b.opacity.toDouble())
        if (b.dim > 0f) put("dim", b.dim.toDouble())
        if (b.dimAll) put("dimAll", true)
        if (b.durMs > 0) put("dur", b.durMs)
        if (!b.emphasis) put("emphasis", false)
        if (b.holdMs >= 0) put("hold", b.holdMs)
        if (b.loopMs > 0) put("loop", b.loopMs)
        if (b.avoid != d.avoid) put("avoid", JSONArray(b.avoid))
        if (b.face.size >= 4) put("face", JSONArray(b.face.map { it.toDouble() }))
        if (b.target.size >= 2) put("target", JSONArray(b.target.map { it.toDouble() }))
    }

    fun briefFrom(s: String?): EditBrief? = runCatching { briefFrom(JSONObject(s ?: return null)) }.getOrNull()

    fun briefFrom(o: JSONObject): EditBrief {
        fun str(k: String) = o.optString(k)
        fun floats(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optDouble(it).toFloat() } }.orEmpty()
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
        fun bool(k: String): Boolean? = if (o.has(k)) o.optBoolean(k) else null
        return EditBrief(
            type = str("type").ifEmpty { "custom" }, kind = str("kind"), what = str("what"), source = str("source"),
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { i -> BriefItem(i.optString("label"), i.optString("value"), i.optString("unit"), i.optString("icon"), i.optBoolean("main")) }
            }.orEmpty(),
            fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } }.orEmpty(),
            onlySaid = o.optBoolean("onlySaid", true), title = str("title"), count = o.optInt("count"), labels = str("labels"), icons = str("icons"),
            lang = str("lang"), tone = str("tone"), layout = str("layout"), style = str("style"),
            accent = if (o.has("accent")) o.optInt("accent") else null, palette = str("palette"), fill = str("fill"),
            bgAlpha = o.optDouble("bgAlpha", -1.0).toFloat(), radius = str("radius"), border = bool("border"), shadow = bool("shadow"),
            font = str("font"), upper = bool("upper"), textSize = str("textSize"), like = str("like"), refImage = str("refImage"),
            avoidStyle = strs("avoidStyle").orEmpty(), dont = str("dont"), opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1f),
            dim = o.optDouble("dim", 0.0).toFloat().coerceIn(0f, 0.8f), dimAll = o.optBoolean("dimAll"), durMs = o.optLong("dur"),
            enter = str("enter"), reveal = str("reveal"), pace = str("pace"), emphasis = o.optBoolean("emphasis", true), holdMs = o.optLong("hold", -1),
            exit = str("exit"), loopMs = o.optLong("loop"), sfx = str("sfx"), place = str("place"), size = str("size"), shape = str("shape"),
            avoid = strs("avoid") ?: EditBrief().avoid, face = floats("face"), target = floats("target"), layer = str("layer")
        )
    }

    /** One line of the brief for its summary: type, plate, accent, pace, length, place. */
    fun summary(b: EditBrief, durMs: Long): String = listOf(
        type(b.type).let { it.icon + " " + t(it.ru, it.en) },
        when (b.fill) {
            "none" -> t("без подложки", "no plate")
            "glass" -> t("стекло", "glass")
            "semi" -> t("полупрозрачная", "semi")
            "solid" -> t("плотная", "solid")
            "full" -> t("перебивка", "cutaway")
            else -> ""
        } + if (b.bgAlpha >= 0f && b.fill != "none" && b.fill.isNotEmpty()) " " + pct(b.bgAlpha) else "",
        if (b.opacity < 0.99f) "◐ " + pct(b.opacity) else "",
        if (b.dim > 0.01f) t("затемнение ", "dim ") + pct(b.dim) else "",
        when (b.pace) {
            "calm" -> t("спокойно", "calm")
            "drive" -> t("драйв", "drive")
            else -> ""
        },
        String.format(Locale.US, "%.1f", durMs / 1000.0) + t(" с", " s"),
        placeName(b.place)
    ).filter { it.isNotBlank() }.joinToString(" · ")

    fun placeName(p: String): String = when (p) {
        "full" -> t("весь кадр", "full frame")
        "cover" -> t("перебивка", "cutaway")
        "top" -> t("сверху", "top")
        "center" -> t("центр", "centre")
        "bottom" -> t("снизу", "bottom")
        "corner" -> t("угол", "corner")
        "custom" -> t("своё место", "own place")
        else -> ""
    }

    /** The lumen.anim v1 protocol for the prompts. */
    val ANIM_PROTOCOL = """
$ANIM — анимационный блок поверх видео, который рисует твой JavaScript. Это вариант lumen.canvas v1, но в рамках одного блока.
Lumen вызывает frame(t, ctx, W, H, data) для каждого кадра (30 в секунду) на прозрачном canvas W×H и накладывает картинку на видео там, где стоит блок.
• t — секунды от НАЧАЛА БЛОКА (0…data.dur). W×H — размер БЛОКА, не всего кадра. Функция ЧИСТАЯ: картинка зависит только от t (никакого накопленного состояния).
• Canvas очищается перед каждым кадром, трансформации и прозрачность сброшены. Фон прозрачный: под блоком видео; подложку рисуй сам, если её просит data.brief.bg (none — без подложки, текст с тенью/обводкой; glass/semi/solid — скруглённая плашка с прозрачностью data.brief.bgAlpha; full — залей весь блок).
• data = {dur, w, h, fps, lang, accent, ink, card, font, wish, title, words:[{w,s,e}] — слова речи внутри блока, секунды от начала блока, place:{fw,fh,x,y,w,ratio} — где блок в кадре, brief:{type, items:[{label,value,unit,icon,main}], fields, title, pace, reveal, hold, bg, bgAlpha, radius, font, upper, minFont, palette, dimmed, tone, icons, labels, count, emphasis, enter, exit, avoid:[{x,y,w,h}] в долях блока, target:{x,y}}}.
• Тексты и числа бери из data.brief (items, fields, title) и data.words, а не вписывай руками — тогда блок пересчитается при правке. Цвета: data.accent — акцент, data.ink — текст, data.card — подложка.
• Читаемость: текст не мельче data.brief.minFont px; ничего важного в зонах data.brief.avoid; при data.brief.upper — капс.
• Синхрон: при reveal = "words" элемент появляется в момент своего слова (ищи слово в data.words), "steps" — по очереди равномерно, "all" — сразу. Готовый вид держи data.brief.hold секунд в конце.
• Вход/выход: если brief.enter = "code" — нарисуй появление сам (первые 0.3–0.6 с), иначе с первого кадра блок виден целиком; то же для brief.exit в конце.
• Помощники (уже объявлены): clamp(x,a,b), lerp(a,b,x), prog(t,a,b) → 0..1, ease.in/out/inOut/back/elastic(x), rand(i) → 0..1, roundRect(ctx,x,y,w,h,r) (только путь), wordAt(t) → индекс слова в data.words или -1, fitFont(ctx,text,maxW,size,weight) → размер шрифта, чтобы текст влез.
• Необязательно: setup(data) — один раз до первого кадра. Верни из frame false, если в кадре рисовать нечего.
• Нельзя: сеть, картинки и шрифты по URL, DOM, таймеры и requestAnimationFrame. Math.random детерминирован в кадре, Date.now() = t·1000.
• Шрифты системные: 'sans-serif', 'sans-serif-condensed', 'serif', 'monospace'; жирность 400–900; эмодзи можно.
• Скорость: до 15 мс на кадр; без getImageData, без больших shadowBlur, без тысяч фигур.
• Хорошо получается: счётчик числа, растущие столбцы и линии, кольцо прогресса, сравнение, список с галочками, стрелка и обводка, лента времени, маршрут, кинетический текст, плашки и призывы.
""".trim()
}

/** Animation blocks of the editor (kind "anim"): JavaScript by lumen.anim v1 rendered into PNG frames inside the project. */
object EditAnim {
    const val KIND = "anim"
    const val META = "meta.json"
    const val CODE = "code.js"
    /** Long side of a rendered block at most, px; short side at most [MAX_SHORT]. */
    const val MAX_LONG = 1920
    const val MAX_SHORT = 1080

    fun frameName(i: Int): String = String.format(Locale.US, "f%05d.png", i)

    /** Pixel size the animation of block [b] is rendered at in a [fw]×[fh] frame (even, within the limits). */
    fun renderSize(b: EBlock, fw: Int, fh: Int): Pair<Int, Int> {
        val w0 = max(16f, b.w * fw)
        val h0 = max(16f, w0 * b.ratio)
        val k = min(1f, min(MAX_SHORT / min(w0, h0), MAX_LONG / max(w0, h0)))
        return max(16, (w0 * k).roundToInt() / 2 * 2) to max(16, (h0 * k).roundToInt() / 2 * 2)
    }

    /** The folder of the code of an animation block ("anim/<hash>") from its [EBlock.file] ("anim/<hash>/<render>"). */
    fun codeDirOf(file: String): String = file.split('/').take(2).joinToString("/")

    /** Meta of a render: size, rate, length, frames, loop, empty frames. */
    fun metaJson(w: Int, h: Int, fps: Int, durMs: Long, count: Int, loopMs: Long, empty: List<Int>): JSONObject = JSONObject()
        .put("protocol", EditInsert.ANIM).put("w", w).put("h", h).put("fps", fps).put("dur", durMs).put("count", count).put("loop", loopMs)
        .put("empty", JSONArray(empty))
}

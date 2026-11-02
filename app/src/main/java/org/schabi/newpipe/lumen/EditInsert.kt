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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One row of the data of a brief: label, value, unit, emoji, main (★). */
data class BriefRow(val label: String, val value: String = "", val unit: String = "", val icon: String = "", val main: Boolean = false)

/**
 * Look of a scheme / animation block over its style ("" / -1 / null = as in the style): [fill] none|glass|semi|solid|full,
 * [bgAlpha] 0..100, [radius] none|s|m|l, [font] sans|condensed|serif|mono|rounded, [textScale] 0 = style.
 */
data class BlockLook(
    val fill: String = "", val bgAlpha: Int = -1, val accent: Int? = null, val font: String = "", val upper: Boolean? = null,
    val textScale: Float = 0f, val radius: String = "", val border: Boolean? = null, val shadow: Boolean? = null
) {
    val isEmpty: Boolean get() = this == BlockLook()
}

/**
 * What the author asked of the insert wizard (sections A–Д of the plan). "" / -1 / null = «Авто»: the AI or the style
 * decides, and such fields never go into the request. Kept in the block ([EBlock.brief]) so «Переделать» opens the same form.
 */
data class EditBrief(
    // A. content
    val type: String = "own",
    /** "" = by the type, scheme or anim. */
    val draw: String = "",
    val what: String = "",
    /** speech | data | text, "" = speech when there are timed words. */
    val source: String = "",
    val rows: List<BriefRow> = emptyList(),
    /** Fields of the type ([EditInsert.TYPE_FIELDS]). */
    val fields: Map<String, String> = emptyMap(),
    val onlySaid: Boolean = true,
    /** "" = auto, "-" = no title, else the title. */
    val title: String = "",
    val items: Int = 0,
    /** short | mid | phrase. */
    val labels: String = "",
    /** emoji | none. */
    val icons: String = "",
    val lang: String = "",
    /** business | friendly | fun | drama | kids. */
    val tone: String = "",
    // Б. look
    val style: String = "",
    val accent: Int? = null,
    /** mono | bright | pastel. */
    val palette: String = "",
    /** none | glass | semi | solid | full. */
    val bg: String = "",
    val bgAlpha: Int = -1,
    val radius: String = "",
    val border: Boolean? = null,
    val shadow: Boolean? = null,
    val font: String = "",
    val upper: Boolean? = null,
    /** s | m | l. */
    val size: String = "",
    val like: String = "",
    /** Chips of «Не делать» ([EditInsert.DONTS]) and the author's own words. */
    val donts: List<String> = emptyList(),
    val dont: String = "",
    // В. transparency and the video under the block
    val opacity: Float = 1f,
    val dim: Float = 0f,
    val dimAll: Boolean = false,
    // Г. motion and time
    /** 0 = by the phrase / reading time. */
    val lenMs: Long = 0,
    /** none | fade | pop | slide | zoom | code. */
    val animIn: String = "",
    val animOut: String = "",
    /** words | steps | all. */
    val reveal: String = "",
    /** calm | normal | drive. */
    val pace: String = "",
    val accentMain: Boolean = true,
    /** -1 = auto (≥ 1.5 s to read). */
    val holdMs: Long = -1,
    val loopS: Int = 0,
    /** none | clicks | whoosh. */
    val sound: String = "",
    // Д. place and size
    /** full | cut | top | center | bottom | corner | custom. */
    val place: String = "",
    /** custom place, fractions of the frame. */
    val rect: FRect? = null,
    /** s | m | l | xl. */
    val scale: String = "",
    /** wide | square | tall. */
    val shape: String = "",
    /** null = auto. */
    val avoidFace: Boolean? = null,
    val avoidSubs: Boolean? = null,
    val avoidUi: Boolean? = null,
    val face: FRect? = null,
    /** top | under. */
    val layer: String = ""
)

/** A type of the wizard's first step: what the author wants to show; [draw] scheme | anim | both (first = default). */
data class InsertType(
    val id: String, val emoji: String, val ru: String, val en: String, val draws: List<String>,
    val layout: String = "", val items: Int = 4, val shape: String = "wide", val place: String = ""
) {
    val name: String get() = t(ru, en)
}

/** A zone the block must not cover: fractions (of the frame or of the block) and why. */
data class InsertZone(val r: FRect, val why: String)

/** Everything the request needs besides the brief: frame, place of the block, time, speech, style colours. */
data class InsertCtx(
    val fw: Int, val fh: Int,
    /** The block, fractions of the frame. */
    val block: FRect,
    val placeName: String,
    val startMs: Long, val durMs: Long,
    val words: List<SWord> = emptyList(),
    val lang: String = "русский",
    val wish: String = "",
    val req: String = "",
    val zones: List<InsertZone> = emptyList(),
    val accent: String = "#FFD60A", val ink: String = "#FFFFFF", val card: String = "#101318", val font: String = "sans-serif",
    val fps: Int = 30
) {
    val vertical: Boolean get() = fh > fw
    val endMs: Long get() = startMs + durMs
}

/** A soft warning of the brief with fixes in one tap ([EditInsert.fix]); [blocking] — nothing to send yet. */
data class InsertWarn(val id: String, val text: String, val fixes: List<Pair<String, String>>, val blocking: Boolean = false)

/** An answer found in the clipboard or pasted: a scheme or an animation, with the request mark it carries. */
sealed class InsertAnswer {
    abstract val req: String
    abstract val name: String
    abstract val kind: String

    data class Scheme(val scheme: SScheme, override val req: String, override val name: String) : InsertAnswer() {
        override val kind: String get() = "scheme"
    }

    data class Anim(val code: String, override val req: String, override val name: String, val loopMs: Long) : InsertAnswer() {
        override val kind: String get() = "anim"
    }
}

/** Rendered frames of an animation block: what they were made for ([key]) and which are empty. */
data class AnimMeta(val key: String, val fps: Int, val w: Int, val h: Int, val count: Int, val durMs: Long, val loopMs: Long, val empty: Set<Int> = emptySet()) {
    fun json(): JSONObject = JSONObject().put("key", key).put("fps", fps).put("w", w).put("h", h).put("count", count)
        .put("dur", durMs).put("loop", loopMs).put("empty", JSONArray().apply { empty.sorted().forEach { put(it) } })

    companion object {
        fun of(o: JSONObject): AnimMeta? {
            val count = o.optInt("count")
            if (count <= 0) return null
            val e = o.optJSONArray("empty")
            return AnimMeta(o.optString("key"), o.optInt("fps", 30).coerceIn(1, 60), o.optInt("w"), o.optInt("h"), count,
                o.optLong("dur"), o.optLong("loop"), if (e == null) emptySet() else (0 until e.length()).map { e.optInt(it) }.toSet())
        }
    }
}

/**
 * The insert wizard of the Studio editor (plan PLAN_EDITOR_INSERT): the brief, the request envelope lumen.insert v1 (one text
 * for the built-in AI and for another AI by copy / paste), the animation protocol lumen.anim v1, finding the answer, soft
 * warnings, the place of the block and the frames of an animation. Pure, no Android.
 */
object EditInsert {
    const val PROTOCOL = "lumen.insert v1"
    const val ANIM = "lumen.anim v1"
    const val KIND = "anim"
    const val MAX_ANIM_MS = 30_000L
    const val MAX_SIDE = 1080
    const val DEFAULT_FPS = 30

    val TYPES = listOf(
        InsertType("number", "🔢", "Число-факт", "Number fact", listOf("anim"), items = 1, shape = "wide"),
        InsertType("compare", "⚖️", "Сравнение A и B", "A versus B", listOf("anim", "scheme"), "matrix", 2, "wide"),
        InsertType("chart", "📊", "График / доли", "Chart / shares", listOf("anim"), items = 5, shape = "square"),
        InsertType("steps", "🪜", "Шаги / процесс", "Steps / process", listOf("scheme", "anim"), "chain", 4, "wide"),
        InsertType("list", "✅", "Список / чек-лист", "List / checklist", listOf("anim", "scheme"), "hub", 4, "tall"),
        InsertType("cause", "➡️", "Причина → следствие", "Cause → effect", listOf("scheme"), "chain", 3, "wide"),
        InsertType("timeline", "🕰", "Хронология", "Timeline", listOf("scheme"), "timeline", 4, "wide"),
        InsertType("funnel", "🔻", "Воронка / иерархия", "Funnel / hierarchy", listOf("scheme"), "funnel", 4, "square"),
        InsertType("proscons", "👍", "Плюсы и минусы", "Pros and cons", listOf("anim", "scheme"), "matrix", 4, "square"),
        InsertType("concept", "🧭", "Понятие и связи", "Concept and links", listOf("scheme"), "hub", 4, "square"),
        InsertType("quote", "💬", "Цитата", "Quote", listOf("anim"), items = 1, shape = "wide"),
        InsertType("lower", "🏷", "Подпись спикера", "Lower third", listOf("anim"), items = 1, shape = "wide", place = "bottom"),
        InsertType("pointer", "👉", "Указатель на кадре", "Pointer on the frame", listOf("anim"), items = 1, shape = "square", place = "full"),
        InsertType("progress", "⏳", "Прогресс / таймер", "Progress / timer", listOf("anim"), items = 1, shape = "wide"),
        InsertType("kinetic", "🔤", "Ключевая фраза", "Kinetic phrase", listOf("anim"), items = 1, shape = "wide"),
        InsertType("route", "🗺", "Маршрут / карта-схема", "Route / map", listOf("anim"), items = 4, shape = "square"),
        InsertType("cta", "📣", "Призыв", "Call to action", listOf("anim"), items = 1, shape = "wide", place = "bottom"),
        InsertType("own", "✨", "Своё", "Own idea", listOf("anim", "scheme"), items = 4, shape = "wide")
    )

    /** Fields of a type besides the data rows: key → (ru, en). */
    val TYPE_FIELDS: Map<String, List<Triple<String, String, String>>> = mapOf(
        "number" to listOf(Triple("value", "значение", "value"), Triple("unit", "единица", "unit"), Triple("from", "считать от", "count from"),
            Triple("caption", "подпись", "caption"), Triple("format", "формат", "format")),
        "compare" to listOf(Triple("winner", "кто выигрывает", "who wins")),
        "chart" to listOf(Triple("sort", "сортировка", "sorting"), Triple("kind", "вид графика", "chart kind")),
        "list" to listOf(Triple("mark", "отметка", "mark")),
        "funnel" to listOf(Triple("layout", "вид", "layout")),
        "concept" to listOf(Triple("center", "центр", "centre")),
        "quote" to listOf(Triple("text", "текст", "text"), Triple("author", "автор", "author")),
        "lower" to listOf(Triple("name", "имя", "name"), Triple("role", "роль", "role")),
        "pointer" to listOf(Triple("caption", "подпись", "caption"), Triple("mark", "вид указателя", "pointer kind")),
        "progress" to listOf(Triple("from", "от", "from"), Triple("to", "до", "to"), Triple("caption", "подпись", "caption")),
        "kinetic" to listOf(Triple("phrase", "фраза", "phrase"), Triple("accents", "слова-акценты", "accent words")),
        "cta" to listOf(Triple("text", "текст", "text"), Triple("icon", "значок", "icon"))
    )

    /** Chips of «Не делать». */
    val DONTS = listOf("emoji", "arrows", "two_colors", "gradients", "shadows")

    fun type(id: String): InsertType = TYPES.firstOrNull { it.id == id } ?: TYPES.last()

    /** Scheme or animation for this brief: the author's choice if the type allows it, else the type's first. */
    fun kindOf(b: EditBrief): String = type(b.type).draws.let { d -> b.draw.takeIf { it in d } ?: d.first() }

    // ---------- request marks ----------

    private const val B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val MARK = Regex("#([A-Z2-9]{4})\\b")

    /** A 4-letter mark of a request: time and a counter, without look-alike letters (I, O, 0, 1). */
    fun reqId(nowMs: Long, counter: Int): String {
        var v = ((nowMs / 1000) * 31 + counter).mod(32L * 32 * 32 * 32)
        val out = CharArray(4)
        for (i in 3 downTo 0) {
            out[i] = B32[(v % 32).toInt()]
            v /= 32
        }
        return String(out)
    }

    // ---------- pending blocks ----------

    /** A block waiting for the answer of another AI (or the built-in one): no content yet, a request mark. */
    fun isPending(b: EBlock): Boolean = (b.kind == "scheme" && b.scheme == null) || (b.kind == KIND && b.file.isEmpty())

    /**
     * The block an answer belongs to: by its mark (a pending block or one being redone keeps the mark of its request);
     * without a mark — the last pending block of the same kind; a strange mark — none.
     */
    fun match(blocks: List<EBlock>, a: InsertAnswer): EBlock? {
        if (a.req.isNotEmpty()) return blocks.firstOrNull { it.req == a.req && (it.kind == a.kind || isPending(it)) }
        return blocks.lastOrNull { isPending(it) && it.req.isNotEmpty() && it.kind == a.kind }
    }

    /** Blocks that wait for an answer of another AI (pending or being redone). */
    fun waiting(blocks: List<EBlock>): List<EBlock> = blocks.filter { it.req.isNotEmpty() }

    // ---------- reading time, font, data from the speech ----------

    /** Time to read [words] words on the screen: 200 words a minute and a moment to notice the block, at least 1.5 s. */
    fun readMs(words: Int): Long = max(1500L, 600L + words * 300L)

    /** How many words the block will likely show: title, rows (or items of the type) by the length of the captions. */
    fun screenWords(b: EditBrief): Int {
        val perLabel = when (b.labels) { "mid" -> 4; "phrase" -> 8; else -> 2 }
        val title = when (b.title) { "-" -> 0; "" -> 3; else -> words(b.title) }
        val rows = if (b.rows.isNotEmpty()) b.rows.sumOf { words(it.label) + (if (it.value.isNotEmpty()) 1 else 0) }
        else (if (b.items > 0) b.items else type(b.type).items) * perLabel
        val fields = b.fields.values.sumOf { words(it) }
        return title + max(rows, fields)
    }

    private fun words(s: String): Int = s.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    /** The smallest readable text in pixels of the exported frame: by its short side and the size S / M / L. */
    fun minFontPx(fw: Int, fh: Int, size: String): Int {
        val k = when (size) { "s" -> 0.044; "l" -> 0.08; else -> 0.059 }
        return max(12, (min(fw, fh) * k).roundToInt())
    }

    private val UNITS = mapOf(
        "%" to "%", "процентов" to "%", "процента" to "%", "процент" to "%", "percent" to "%",
        "млн" to "млн", "миллионов" to "млн", "миллиона" to "млн", "миллион" to "млн", "million" to "M", "m" to "M",
        "млрд" to "млрд", "миллиардов" to "млрд", "миллиарда" to "млрд", "миллиард" to "млрд", "billion" to "B", "bn" to "B",
        "тыс" to "тыс.", "тысяч" to "тыс.", "тысячи" to "тыс.", "тысяча" to "тыс.", "thousand" to "K", "k" to "K",
        "раз" to "раз", "раза" to "раз", "times" to "×", "x" to "×", "×" to "×",
        "₽" to "₽", "руб" to "₽", "рублей" to "₽", "рубля" to "₽", "$" to "$", "€" to "€", "долларов" to "$", "dollars" to "$"
    )
    private val STOP = setOf(
        "итак", "это", "этот", "эта", "так", "вот", "но", "а", "и", "или", "что", "как", "когда", "если", "уже", "ещё", "еще", "всего",
        "около", "почти", "более", "менее", "больше", "меньше", "целых", "только", "лишь", "the", "and", "but", "so", "that", "this", "about",
        "almost", "over", "more", "less", "than", "only", "just", "now", "today", "году", "год", "года", "лет", "year", "years"
    )
    private val TOKEN = Regex("""\d+(?:[.,]\d+)?%?|[\p{L}][\p{L}\p{N}'’-]*|[$€₽%×]|[.,;:!?]""")

    /**
     * Numbers and names of what is said ([text] — the words of the block): «72 %», «2,5 млн», «в 2020 году», each with the
     * nearest name of the same clause, else the counted noun after it, else a noun before it as its label; the largest number of several is the main one (★). At most 8 rows;
     * without numbers — the names (words with a capital letter not at a sentence start).
     */
    fun dataFromSpeech(text: String): List<BriefRow> {
        val tok = TOKEN.findAll(text).map { it.value }.toList()
        val rows = mutableListOf<BriefRow>()
        val used = mutableSetOf<Int>()
        val years = mutableSetOf<Int>()
        fun isPunct(s: String) = s.length == 1 && s[0] in ".,;:!?"
        fun isNum(s: String) = s[0].isDigit()
        fun sentenceStart(i: Int) = i == 0 || tok[i - 1] in setOf(".", "!", "?")
        fun nameLike(i: Int): Boolean {
            val s = tok[i]
            if (!s[0].isLetter() || s.none { it.isUpperCase() } || s.lowercase() in STOP || s.length < 2) return false
            // a capital at the start of a sentence is a name only when it is Latin or has capitals inside (Android, iOS, YouTube)
            return !sentenceStart(i) || (s.length >= 3 && (s.any { it in 'A'..'Z' || it in 'a'..'z' } || s.drop(1).any { it.isUpperCase() }))
        }
        fun nounLike(i: Int): Boolean = tok[i][0].isLetter() && tok[i].length >= 4 && tok[i].lowercase() !in STOP && tok[i].lowercase() !in UNITS
        var i = 0
        while (i < tok.size && rows.size < 8) {
            val s = tok[i]
            if (!isNum(s)) {
                i++
                continue
            }
            val value = s.removeSuffix("%")
            var unit = if (s.endsWith("%")) "%" else ""
            var j = i + 1
            if (unit.isEmpty() && j < tok.size) UNITS[tok[j].lowercase().removeSuffix(".")]?.let { unit = it; j++ }
            if (unit == "%" && j < tok.size && tok[j] == "%") j++
            if (unit.isEmpty() && i > 0 && tok[i - 1] in setOf("$", "€", "₽")) unit = tok[i - 1]
            val year = unit.isEmpty() && value.length == 4 && value.toIntOrNull() in 1800..2100
            // the label: back inside the clause — a name first, else a noun; else the next noun
            var label = ""
            var k = i - 1
            var noun = -1
            while (k >= 0 && !(isPunct(tok[k]) && tok[k] != ":") && !isNum(tok[k])) {
                if (nameLike(k) && k !in used) {
                    label = tok[k]
                    used += k
                    break
                }
                if (noun < 0 && nounLike(k)) noun = k
                k--
            }
            if (label.isEmpty() && !year && j < tok.size && nounLike(j)) label = tok[j].also { used += j }
            if (label.isEmpty() && noun >= 0 && !year) label = tok[noun].also { used += noun }
            if (year && label.isEmpty()) label = t("год", "year")
            rows += BriefRow(label, value, unit)
            if (year) years += rows.size - 1
            i = j
        }
        val vals = rows.indices.filter { it !in years }
        val nums = vals.map { rows[it].value.replace(',', '.').toDoubleOrNull() }
        if (vals.size >= 2 && nums.all { it != null } && vals.map { rows[it].unit }.toSet().size == 1) {
            val top = nums.indices.maxByOrNull { nums[it]!! }!!
            if (nums.count { it == nums[top] } == 1) rows[vals[top]] = rows[vals[top]].copy(main = true)
        }
        if (rows.isNotEmpty()) return rows
        return tok.indices.filter { !isPunct(tok[it]) && !isNum(tok[it]) && nameLike(it) && !sentenceStart(it) }
            .map { tok[it].trim('-', '’', '\'') }.distinct().take(8).map { BriefRow(it) }
    }

    // ---------- place of the block ----------

    /** Width of the block (fraction of the frame width) by the size S / M / L / XL. */
    fun widthOf(scale: String, vertical: Boolean): Float = when (scale) {
        "s" -> if (vertical) 0.5f else 0.35f
        "l" -> if (vertical) 0.86f else 0.65f
        "xl" -> if (vertical) 0.94f else 0.8f
        else -> if (vertical) 0.72f else 0.5f
    }

    /** Height / width in pixels by the shape. */
    fun ratioOf(shape: String): Float = when (shape) { "square" -> 1f; "tall" -> 1.4f; else -> 0.56f }

    /** Safe zones of the format: the interface of Shorts / Reels / TikTok in a vertical frame (bottom 18 %, right 12 %). */
    fun uiZones(fw: Int, fh: Int): List<InsertZone> = if (fh <= fw) emptyList() else listOf(
        InsertZone(FRect(0f, 0.82f, 1f, 1f), t("интерфейс Shorts", "Shorts interface")),
        InsertZone(FRect(0.88f, 0.45f, 1f, 0.82f), t("кнопки Shorts", "Shorts buttons"))
    )

    /** Where subtitles usually stand. */
    fun subsZone(fw: Int, fh: Int): InsertZone =
        InsertZone(if (fh > fw) FRect(0.05f, 0.66f, 0.95f, 0.82f) else FRect(0.1f, 0.8f, 0.9f, 0.95f), t("субтитры", "subtitles"))

    /** The place preset by default: centre; in a vertical frame with a face — the half without it; the type's own place first. */
    fun defaultPlace(b: EditBrief, fw: Int, fh: Int): String {
        type(b.type).place.takeIf { it.isNotEmpty() }?.let { return it }
        val face = b.face ?: return "center"
        if (fh <= fw) return "center"
        return if ((face.t + face.b) / 2 < 0.5f) "bottom" else "top"
    }

    /** Zones the block must not cover for this brief (fractions of the frame). */
    fun zones(b: EditBrief, fw: Int, fh: Int, subsInVideo: Boolean): List<InsertZone> {
        val out = mutableListOf<InsertZone>()
        if (b.avoidUi ?: (fh > fw)) out += uiZones(fw, fh)
        if (b.avoidSubs ?: subsInVideo) out += subsZone(fw, fh)
        if (b.avoidFace != false) b.face?.let { out += InsertZone(it, t("лицо", "face")) }
        return out
    }

    /**
     * The block for a place preset (fractions of the frame): full and cut — the whole frame; top / bottom — at the edge of
     * the safe zones; corner — a small block top-left; custom — [EditBrief.rect]. Never taller than 80 % of the frame.
     */
    fun place(b: EditBrief, fw: Int, fh: Int): FRect {
        val preset = b.place.ifEmpty { defaultPlace(b, fw, fh) }
        if (preset == "full" || preset == "cut") return FRect(0f, 0f, 1f, 1f)
        if (preset == "custom") b.rect?.let { return it }
        val vertical = fh > fw
        var w = if (preset == "corner") (if (vertical) 0.42f else 0.3f) else widthOf(b.scale, vertical)
        val ratio = ratioOf(b.shape.ifEmpty { type(b.type).shape })
        var h = w * ratio * fw / fh
        if (h > 0.8f) {
            w *= 0.8f / h
            h = 0.8f
        }
        val top = if (vertical) 0.07f else 0.06f
        val bottom = if (vertical) 0.8f else 0.94f
        val cx = 0.5f
        val cy = when (preset) {
            "top" -> top + h / 2
            "bottom" -> bottom - h / 2
            "corner" -> top + h / 2
            else -> if (vertical) min(0.46f, bottom - h / 2) else 0.5f
        }.coerceIn(h / 2, 1f - h / 2)
        val cxx = if (preset == "corner") 0.04f + w / 2 else cx
        return FRect(cxx - w / 2, cy - h / 2, cxx + w / 2, cy + h / 2)
    }

    /** Overlap of two rectangles, or null. */
    fun cross(a: FRect, z: FRect): FRect? {
        val l = max(a.l, z.l)
        val t = max(a.t, z.t)
        val r = min(a.r, z.r)
        val b = min(a.b, z.b)
        return if (r - l > 0.005f && b - t > 0.005f) FRect(l, t, r, b) else null
    }

    /** The zones inside the block, in fractions of the block (what the AI must keep free). */
    fun inBlock(block: FRect, zones: List<InsertZone>): List<InsertZone> = zones.mapNotNull { z ->
        cross(block, z.r)?.let { c ->
            InsertZone(FRect((c.l - block.l) / block.w, (c.t - block.t) / block.h, (c.r - block.l) / block.w, (c.b - block.t) / block.h), z.why)
        }
    }

    /** The block in pixels of the exported frame: [EditInsert.MAX_SIDE] at most by its short side, even numbers. */
    fun pixels(block: FRect, fw: Int, fh: Int): Pair<Int, Int> {
        var w = block.w * fw
        var h = block.h * fh
        val k = min(1f, MAX_SIDE / min(w, h).coerceAtLeast(1f))
        w *= k
        h *= k
        return max(2, w.roundToInt() / 2 * 2) to max(2, h.roundToInt() / 2 * 2)
    }

    /** Block of the editor from a place: centre, width, ratio (height / width in pixels). */
    fun toBlock(base: EBlock, r: FRect, fw: Int, fh: Int): EBlock =
        base.copy(x = (r.l + r.r) / 2, y = (r.t + r.b) / 2, w = r.w, ratio = (r.h * fh / (r.w * fw).coerceAtLeast(1e-3f)).coerceIn(0.05f, 20f))

    // ---------- contrast and warnings ----------

    /**
     * Contrast of the text [ink] (ARGB) to what is under it: the frame of relative luminance [frameLum] (0..1), dimmed by
     * [dim], under the plate [card] with opacity [alpha] (0..1).
     */
    fun contrastOn(frameLum: Double, ink: Int, card: Int, alpha: Float, dim: Float): Double {
        val under = frameLum * (1 - dim)
        val bg = under * (1 - alpha) + SchemeStyles.luminance(card) * alpha
        val fg = SchemeStyles.luminance(ink)
        return (max(bg, fg) + 0.05) / (min(bg, fg) + 0.05)
    }

    /** Opacity of the plate by default for a fill. */
    fun alphaOf(bg: String, bgAlpha: Int): Float = if (bgAlpha >= 0) bgAlpha / 100f else when (bg) {
        "none" -> 0f
        "glass" -> 0.35f
        "semi" -> 0.7f
        "solid" -> 0.95f
        "full" -> 1f
        else -> 0.7f
    }

    /**
     * Soft warnings of the brief, each with fixes. [frameLum] — mean luminance of the frame under the block (-1 = unknown),
     * [words] — the speech inside the block, [ink] / [card] — colours of the style.
     */
    fun warnings(b: EditBrief, ctx: InsertCtx, frameLum: Double = -1.0, ink: Int = 0xFFFFFFFF.toInt(), card: Int = 0xFF101318.toInt()): List<InsertWarn> {
        val out = mutableListOf<InsertWarn>()
        val inside = ctx.words.count { it.startMs >= ctx.startMs && it.startMs < ctx.endMs }
        if (inside == 0 && b.rows.isEmpty() && b.what.isBlank() && b.fields.values.all { it.isBlank() })
            out += InsertWarn("empty", t("Опишите, что показать: в этом месте нет ни речи, ни данных", "Describe what to show: no speech and no data here"), emptyList(), true)
        val need = readMs(screenWords(b))
        val len = if (b.lenMs > 0) b.lenMs else ctx.durMs
        if (len + 100 < need) out += InsertWarn("short", t("Блок короче, чем нужно на чтение (≈${StudioEdit.secs(need)} с)", "The block is shorter than reading takes (≈${StudioEdit.secs(need)} s)"),
            listOf("long" to t("Удлинить до ${StudioEdit.secs(roundHalf(need))} с", "Lengthen to ${StudioEdit.secs(roundHalf(need))} s")))
        val n = if (b.items > 0) b.items else b.rows.size
        val narrow = b.scale == "s" || b.place == "corner"
        if ((kindOf(b) == "scheme" && n > 7) || (narrow && n > 5))
            out += InsertWarn("many", t("Слишком много элементов: $n — не успеют прочитать", "Too many items: $n — hard to read"), listOf("five" to t("Оставить 5", "Keep 5")))
        val alpha = alphaOf(b.bg, b.bgAlpha)
        val faint = b.opacity < 0.6f
        val low = frameLum >= 0 && contrastOn(frameLum, ink, card, alpha, b.dim) < 4.5
        if (faint || low) out += InsertWarn("faint",
            if (faint) t("Блок слишком прозрачный — текст будет плохо видно", "The block is too transparent — text is hard to see")
            else t("Мало контраста с видео под блоком", "Low contrast with the video under the block"),
            listOfNotNull(if (faint) "opaque" to t("Непрозрачный блок", "Opaque block") else null,
                "plate" to t("Плотнее подложка", "Denser plate"), if (b.dim < 0.4f) "dim" to t("Затемнить видео на 40 %", "Dim the video 40 %") else null))
        val zones = zones(b, ctx.fw, ctx.fh, false) + ctx.zones
        val hit = zones.firstOrNull { cross(ctx.block, it.r) != null && ctx.block.w < 0.99f }
        if (hit != null) out += InsertWarn("zone", t("Блок заходит на зону «${hit.why}»", "The block covers «${hit.why}»"), listOf("move" to t("Сдвинуть", "Move")))
        if (b.size == "s" && (narrow || ctx.block.w < 0.45f))
            out += InsertWarn("font", t("Текст будет мельче, чем читается на телефоне", "Text will be smaller than readable on a phone"), listOf("bigger" to t("Крупнее", "Bigger")))
        return out
    }

    private fun roundHalf(ms: Long): Long = ceil(ms / 500.0).toLong() * 500

    /** The brief after a fix of a warning (see [warnings]). */
    fun fix(b: EditBrief, fixId: String, ctx: InsertCtx): EditBrief = when (fixId) {
        "long" -> b.copy(lenMs = roundHalf(readMs(screenWords(b))))
        "five" -> b.copy(items = 5, rows = b.rows.take(5))
        "opaque" -> b.copy(opacity = 1f)
        "plate" -> b.copy(bg = if (b.bg == "none" || b.bg == "glass" || b.bg.isEmpty()) "semi" else b.bg, bgAlpha = max(85, b.bgAlpha))
        "dim" -> b.copy(dim = 0.4f)
        "bigger" -> b.copy(size = "m")
        "move" -> {
            val zones = zones(b, ctx.fw, ctx.fh, false) + ctx.zones
            listOf("top", "bottom", "center", "corner").map { b.copy(place = it) }
                .firstOrNull { c -> zones.none { z -> cross(place(c, ctx.fw, ctx.fh), z.r) != null } } ?: b.copy(place = "top", scale = "s")
        }
        else -> b
    }

    // ---------- the brief in words ----------

    private fun pct(f: Float) = "${(f * 100).roundToInt()} %"
    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
    private fun f2(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun bgName(bg: String) = when (bg) {
        "none" -> t("без подложки", "no plate")
        "glass" -> t("подложка стекло", "glass plate")
        "semi" -> t("подложка полупрозрачная", "semi-transparent plate")
        "solid" -> t("подложка плотная", "solid plate")
        "full" -> t("перебивка на весь кадр", "full-frame cutaway")
        else -> ""
    }

    private fun fontName(f: String) = when (f) {
        "condensed" -> t("узкий", "condensed")
        "serif" -> t("с засечками", "serif")
        "mono" -> t("моноширинный", "monospace")
        "rounded" -> t("округлый", "rounded")
        "sans" -> t("обычный", "regular")
        else -> ""
    }

    private fun dontName(d: String) = when (d) {
        "emoji" -> t("без эмодзи", "no emoji")
        "arrows" -> t("без стрелок", "no arrows")
        "two_colors" -> t("не больше 2 цветов", "at most 2 colours")
        "gradients" -> t("без градиентов", "no gradients")
        "shadows" -> t("без теней", "no shadows")
        else -> d
    }

    fun rowText(r: BriefRow): String = buildString {
        if (r.icon.isNotEmpty()) append(r.icon).append(' ')
        append(r.label)
        if (r.value.isNotEmpty()) {
            if (r.label.isNotEmpty()) append(" — ")
            append(r.value)
            if (r.unit.isNotEmpty()) append(if (r.unit == "%") " %" else " ${r.unit}")
        }
        if (r.main) append(" ★")
    }.trim()

    private fun zoneText(z: InsertZone): String {
        val r = z.r
        val x = if (r.l <= 0.01f && r.r >= 0.99f) "" else "x ${f2(r.l)}–${f2(r.r)}, "
        return "${x}y ${f2(r.t)}–${f2(r.b)} (${z.why})"
    }

    /**
     * The brief as lines of the request: one line per section, only what the author set («Авто» is not written). For a
     * scheme only the content, the motion and the zones to keep free: its look is drawn by Lumen.
     */
    fun briefLines(b: EditBrief, kind: String, zonesInBlock: List<InsertZone> = emptyList(), minFont: Int = 0): List<String> {
        val ty = type(b.type)
        val anim = kind == KIND
        val out = mutableListOf<String>()
        out += buildString {
            append(t("тип: ", "type: ")).append(ty.name)
            if (b.rows.isNotEmpty()) append(t("; данные: ", "; data: ")).append(b.rows.joinToString("; ") { rowText(it) })
        }
        if (b.what.isNotBlank()) out += t("что показать: ", "what to show: ") + b.what.trim()
        val names = TYPE_FIELDS[ty.id].orEmpty().associate { it.first to t(it.second, it.third) }
        b.fields.filter { it.value.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { f ->
            out += f.entries.joinToString("; ") { "${names[it.key] ?: it.key}: ${it.value.trim()}" }
        }
        out += listOfNotNull(
            if (b.onlySaid) t("только сказанное: да — без новых фактов и чисел", "only what is said: yes — no new facts or numbers")
            else t("можно дополнить фактами по теме", "may add facts on the topic"),
            when (b.title) { "" -> null; "-" -> t("без заголовка", "no title"); else -> t("заголовок: «${b.title.trim()}»", "title: «${b.title.trim()}»") },
            if (b.items > 0) t("элементов: ${b.items}", "items: ${b.items}") else null,
            when (b.labels) { "short" -> t("подписи: 1–3 слова", "captions: 1–3 words"); "mid" -> t("подписи: до 6 слов", "captions: up to 6 words"); "phrase" -> t("подписи: фразой", "captions: a phrase"); else -> null },
            when (b.icons) { "emoji" -> t("иконки: эмодзи", "icons: emoji"); "none" -> t("без иконок", "no icons"); else -> null },
            if (b.lang.isNotBlank()) t("язык: ${b.lang}", "language: ${b.lang}") else null,
            when (b.tone) {
                "business" -> t("тон: деловой", "tone: business"); "friendly" -> t("тон: дружелюбный", "tone: friendly")
                "fun" -> t("тон: с юмором", "tone: humorous"); "drama" -> t("тон: драматично", "tone: dramatic"); "kids" -> t("тон: для детей", "tone: for kids")
                else -> null
            }
        ).joinToString("; ")
        if (anim) {
            val look = listOfNotNull(
                bgName(b.bg).takeIf { it.isNotEmpty() }?.let { if (b.bgAlpha >= 0 && b.bg != "none") "$it ${b.bgAlpha} %" else it }
                    ?: if (b.bgAlpha >= 0) t("подложка ${b.bgAlpha} %", "plate ${b.bgAlpha} %") else null,
                b.accent?.let { t("акцент ${hex(it)}", "accent ${hex(it)}") },
                when (b.palette) { "mono" -> t("монохром + акцент", "monochrome + accent"); "bright" -> t("яркая палитра", "bright palette"); "pastel" -> t("пастель", "pastel"); else -> null },
                listOfNotNull(fontName(b.font).takeIf { it.isNotEmpty() }?.let { t("шрифт $it", "$it font") }, if (b.upper == true) t("КАПС", "UPPERCASE") else null)
                    .takeIf { it.isNotEmpty() }?.joinToString(", "),
                if (minFont > 0) (if (b.size.isNotEmpty()) t("текст ${b.size.uppercase()} — ", "text ${b.size.uppercase()} — ") else "") + t("не мельче $minFont px", "not smaller than $minFont px") else null,
                when (b.radius) { "none" -> t("без скругления", "no rounding"); "s", "m", "l" -> t("скругление ${b.radius.uppercase()}", "rounding ${b.radius.uppercase()}"); else -> null },
                b.border?.let { if (it) t("с обводкой", "with a border") else t("без обводки", "no border") },
                b.shadow?.let { if (it) t("с тенью", "with a shadow") else t("без тени", "no shadow") }
            ) + b.donts.map { dontName(it) } + listOfNotNull(b.dont.trim().takeIf { it.isNotEmpty() })
            if (look.isNotEmpty()) out += t("вид: ", "look: ") + look.joinToString("; ")
            when {
                b.dim > 0f -> out += t("видео под блоком: затемнено на ${pct(b.dim)} — фон тёмный, текст светлый", "video under the block: dimmed by ${pct(b.dim)} — dark background, light text")
                b.bg == "none" -> out += t("видео под блоком видно как есть — у текста тень или обводка", "the video under the block is as it is — text with a shadow or outline")
                b.bg == "full" -> out += t("блок закрывает весь кадр — свой фон", "the block covers the whole frame — own background")
            }
        }
        val motion = listOfNotNull(
            when (b.reveal) { "words" -> t("раскрытие на словах речи", "reveal on the spoken words"); "steps" -> t("раскрытие по очереди", "reveal one by one"); "all" -> t("всё сразу", "all at once"); else -> null },
            if (anim) when (b.pace) { "calm" -> t("темп спокойный (движения 600–900 мс)", "calm pace (moves 600–900 ms)"); "normal" -> t("темп обычный (300–500 мс)", "normal pace (300–500 ms)"); "drive" -> t("темп драйв (150–300 мс, с отскоком)", "driving pace (150–300 ms, with overshoot)"); else -> null } else null,
            if (anim && b.accentMain) t("акцент на главном (подсветить на его слове)", "accent the main item (on its word)") else null,
            if (anim && b.holdMs >= 0) t("держать финал ${StudioEdit.secs(b.holdMs)} с", "hold the end ${StudioEdit.secs(b.holdMs)} s") else null,
            if (anim && (b.animIn == "code" || b.animOut == "code" || (b.animIn.isEmpty() && b.animOut.isEmpty()))) t("появление и исчезновение — в коде", "in and out — in the code") else null,
            if (anim && b.loopS > 0) t("цикл ${b.loopS} с (// loop: ${b.loopS})", "loop ${b.loopS} s (// loop: ${b.loopS})") else null
        )
        if (motion.isNotEmpty()) out += t("движение: ", "motion: ") + motion.joinToString("; ")
        if (zonesInBlock.isNotEmpty()) out += t("не закрывать (доли блока): ", "keep free (fractions of the block): ") + zonesInBlock.joinToString("; ") { zoneText(it) }
        if (b.like.isNotBlank()) out += t("похоже на: ", "looks like: ") + b.like.trim()
        return out
    }

    /** A line for the summary under the form: «Сравнение · полупрозрачная 70 % · драйв · 6 с · центр». */
    fun summary(b: EditBrief, ctx: InsertCtx): String = listOfNotNull(
        type(b.type).name,
        bgName(b.bg).takeIf { it.isNotEmpty() }?.let { if (b.bgAlpha >= 0) "$it ${b.bgAlpha} %" else it },
        b.accent?.let { t("акцент ", "accent ") + hex(it) },
        if (b.dim > 0) t("видео −${pct(b.dim)}", "video −${pct(b.dim)}") else null,
        when (b.pace) { "calm" -> t("спокойно", "calm"); "drive" -> t("драйв", "drive"); else -> null },
        StudioEdit.secs(if (b.lenMs > 0) b.lenMs else ctx.durMs) + t(" с", " s"),
        ctx.placeName
    ).joinToString(" · ")

    // ---------- the request ----------

    private fun frameText(ctx: InsertCtx): String {
        val (bw, bh) = pixels(ctx.block, ctx.fw, ctx.fh)
        val g = gcd(ctx.fw, ctx.fh)
        val shape = if (ctx.vertical) t("вертикальный", "vertical") else if (ctx.fw == ctx.fh) t("квадратный", "square") else t("горизонтальный", "horizontal")
        return buildString {
            append(t("КАДР: ", "FRAME: ")).append(shape).append(' ').append(ctx.fw / g).append(':').append(ctx.fh / g)
            append(", ").append(ctx.fw).append('×').append(ctx.fh)
            append(t("; блок ", "; block ")).append(bw).append('×').append(bh).append(" px (").append(f2(ctx.block.w)).append(t(" ширины)", " of the width)"))
            append(t(", место «", ", place «")).append(ctx.placeName).append('»')
            if (ctx.vertical) append(t("; безопасные зоны Shorts", "; Shorts safe zones"))
        }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) max(1, a) else gcd(b, a % b)

    /** Speech of the block: words inside it numbered [N] with seconds from its start, ±2 s of context before / after. */
    fun speechText(ctx: InsertCtx): String {
        val ws = ctx.words
        val inside = ws.indices.filter { ws[it].startMs >= ctx.startMs && ws[it].startMs < ctx.endMs }
        if (inside.isEmpty()) return t("РЕЧЬ: в блоке слов нет", "SPEECH: no words in the block")
        val before = ws.filter { it.startMs >= ctx.startMs - 2000 && it.startMs < ctx.startMs }.joinToString(" ") { it.text }
        val after = ws.filter { it.startMs >= ctx.endMs && it.startMs < ctx.endMs + 2000 }.joinToString(" ") { it.text }
        fun s(ms: Long) = String.format(Locale.US, "%.2f", max(0L, ms - ctx.startMs) / 1000.0)
        return buildString {
            append(t("РЕЧЬ (номер слова [N], секунды от начала блока):\n", "SPEECH (word number [N], seconds from the block start):\n"))
            if (before.isNotEmpty()) append(t("до: ", "before: ")).append(before).append('\n')
            append(inside.joinToString(" ") { "[$it]${ws[it].text} ${s(ws[it].startMs)}–${s(ws[it].endMs)}" })
            if (after.isNotEmpty()) append('\n').append(t("после: ", "after: ")).append(after)
        }
    }

    /** Text of the lumen.anim v1 protocol for the request. */
    val ANIM_PROTOCOL = """
lumen.anim v1 — анимационный блок поверх видео, который рисует твой JavaScript (как lumen.canvas v1, но в рамках блока).
Lumen вызывает frame(t, ctx, W, H, data) для каждого кадра (data.fps в секунду) на прозрачном canvas W×H — это размер БЛОКА, а не кадра.
• t — секунды от начала блока (0…data.dur). Функция ЧИСТАЯ: картинка зависит только от t.
• Фон прозрачный; появление и исчезновение блока рисуй сам (Lumen их не добавляет), к концу блока всё может остаться на экране.
• data = {dur, w, h, fps, lang, accent, ink, card, font, wish, title, words:[{w,s,e}] — слова внутри блока, секунды от его начала, place:{fw,fh,x,y} — где блок в кадре, brief}.
• data.brief — то, что задал автор: {type, items:[{label,value,unit,icon,main}], title, pace calm|normal|drive, reveal words|steps|all, holdMs, bg none|glass|semi|solid|full, bgAlpha, radius, font, upper, minFont (px), palette, dimmed (0..0.6), avoid:[{x,y,w,h} — доли блока], tone}. Соблюдай его: текст не мельче minFont, подложка с прозрачностью bgAlpha, ничего важного в avoid; если bg = none — у текста тень или обводка.
• Помощники (уже объявлены): clamp(x,a,b), lerp(a,b,x), prog(t,a,b) → 0..1, ease.in/out/inOut/back/elastic(x), rand(i), roundRect(ctx,x,y,w,h,r) (только путь), wordAt(t) → индекс слова data.words или -1, fitFont(ctx,text,maxW,size,weight).
• Необязательно: setup(data) — один раз до первого кадра. Верни из frame false, если в этом кадре рисовать нечего.
• Повтор: строка // loop: N — анимация периодическая с периодом N секунд (кадры рендерятся один раз и повторяются).
• Нельзя: сеть, внешние скрипты, картинки и шрифты по URL, DOM кроме ctx, таймеры. Шрифты системные ('sans-serif', 'serif', 'monospace'), эмодзи можно.
• Скорость: до 15 мс на кадр, до 60 000 символов; без getImageData и больших shadowBlur.
• Лучше всего получается: счётчик числа, растущие столбцы или линия, кольцо прогресса, сравнение двух величин, список с галочками, стрелка или выделение, пиктограммы, лента времени, маршрут, кинетический текст.
""".trim()

    /** JSON the animation gets as data (see [ANIM_PROTOCOL]). */
    fun animData(ctx: InsertCtx, b: EditBrief): JSONObject {
        val (bw, bh) = pixels(ctx.block, ctx.fw, ctx.fh)
        val ws = ctx.words.filter { it.startMs >= ctx.startMs && it.startMs < ctx.endMs }
        fun s(ms: Long) = Math.round(max(0L, ms - ctx.startMs) / 10.0) / 100.0
        val zones = inBlock(ctx.block, zones(b, ctx.fw, ctx.fh, false) + ctx.zones)
        val brief = JSONObject().put("type", b.type)
            .put("items", JSONArray().apply {
                b.rows.forEach { put(JSONObject().put("label", it.label).put("value", it.value).put("unit", it.unit).put("icon", it.icon).put("main", it.main)) }
            })
            .put("title", if (b.title == "-") "" else b.title).put("pace", b.pace.ifEmpty { "normal" }).put("reveal", b.reveal.ifEmpty { if (ws.isNotEmpty()) "words" else "steps" })
            .put("holdMs", if (b.holdMs >= 0) b.holdMs else 1500L).put("bg", b.bg.ifEmpty { "semi" }).put("bgAlpha", if (b.bgAlpha >= 0) b.bgAlpha else (alphaOf(b.bg, -1) * 100).roundToInt())
            .put("radius", b.radius.ifEmpty { "m" }).put("font", b.font.ifEmpty { "sans" }).put("upper", b.upper == true)
            .put("minFont", (minFontPx(ctx.fw, ctx.fh, b.size) * bw / (ctx.block.w * ctx.fw).coerceAtLeast(1f)).roundToInt())
            .put("palette", b.palette).put("dimmed", b.dim.toDouble()).put("tone", b.tone)
            .put("avoid", JSONArray().apply { zones.forEach { put(JSONObject().put("x", it.r.l.toDouble()).put("y", it.r.t.toDouble()).put("w", it.r.w.toDouble()).put("h", it.r.h.toDouble())) } })
        return JSONObject().put("dur", ctx.durMs / 1000.0).put("w", bw).put("h", bh).put("fps", ctx.fps).put("lang", ctx.lang)
            .put("accent", b.accent?.let { hex(it) } ?: ctx.accent).put("ink", ctx.ink).put("card", ctx.card).put("font", ctx.font)
            .put("wish", ctx.wish).put("title", if (b.title == "-") "" else b.title)
            .put("words", JSONArray().apply { ws.forEach { put(JSONObject().put("w", it.text).put("s", s(it.startMs)).put("e", s(it.endMs))) } })
            .put("place", JSONObject().put("fw", ctx.fw).put("fh", ctx.fh).put("x", ctx.block.l.toDouble()).put("y", ctx.block.t.toDouble()))
            .put("brief", brief)
    }

    /**
     * The request of the wizard (lumen.insert v1): one self-contained text for the built-in AI and for another AI. Starts
     * with the Relay head, so our own request in the clipboard is never taken for an answer.
     */
    fun request(ctx: InsertCtx, b: EditBrief, kind: String = kindOf(b)): String = buildString {
        val anim = kind == KIND
        val zonesIn = inBlock(ctx.block, zones(b, ctx.fw, ctx.fh, false) + ctx.zones)
        val (bw, _) = pixels(ctx.block, ctx.fw, ctx.fh)
        val minFont = if (anim) (minFontPx(ctx.fw, ctx.fh, b.size) * bw / (ctx.block.w * ctx.fw).coerceAtLeast(1f)).roundToInt() else 0
        append(RelayText.HEAD).append(" · ").append(PROTOCOL).append(" · #").append(ctx.req).append("⟧\n")
        append(t("РОЛЬ: ", "ROLE: ")).append(if (anim) t("моушн-дизайнер для видео.", "motion designer for video.") else t("дизайнер инфографики для видео.", "infographics designer for video.")).append('\n')
        append(t("ЗАДАНИЕ: ", "TASK: ")).append(if (anim) t("анимационный блок", "animation block") else t("инфосхема", "info-scheme"))
        append(t(" — ${type(b.type).name.lowercase()}.\n", " — ${type(b.type).name.lowercase()}.\n"))
        append(frameText(ctx)).append('\n')
        append(t("ВРЕМЯ: блок ${StudioEdit.secs(ctx.durMs)} с — с ${clockS(ctx.startMs)} по ${clockS(ctx.endMs)} ролика.\n",
            "TIME: the block is ${StudioEdit.secs(ctx.durMs)} s — from ${clockS(ctx.startMs)} to ${clockS(ctx.endMs)} of the video.\n"))
        append(t("ЯЗЫК ПОДПИСЕЙ: ", "CAPTION LANGUAGE: ")).append(b.lang.ifBlank { ctx.lang }).append('\n')
        append(speechText(ctx)).append('\n')
        append(t("ПОЖЕЛАНИЕ АВТОРА: ", "AUTHOR'S WISH: ")).append(ctx.wish.trim().ifEmpty { t("(нет)", "(none)") }).append('\n')
        append(t("БРИФ (только то, что задал автор; остальное выбери сам):\n", "BRIEF (only what the author set; choose the rest yourself):\n"))
        briefLines(b, kind, zonesIn, minFont).forEach { append(it).append('\n') }
        append('\n')
        if (anim) {
            append(ANIM_PROTOCOL).append("\n\n")
            append(t("DATA (то, что получит frame в data):\n", "DATA (what frame gets as data):\n")).append(animData(ctx, b).toString()).append("\n\n")
            append(t("ОБРАЗЕЦ КОДА (как читать data.brief и data.words; сделай своё под бриф, не копируй):\n", "CODE SAMPLE (how to read data.brief and data.words; make your own for the brief, do not copy):\n"))
            append("```javascript\n").append(AnimExamples.SAMPLE).append("\n```\n\n")
            append(t("ОТВЕТ: один блок ```javascript; первые строки — // $ANIM #${ctx.req} и // name: короткое название (2–4 слова); при повторе — // loop: N; дальше function frame(t, ctx, W, H, data) { … }. Без HTML и пояснений.",
                "ANSWER: one ```javascript block; first lines — // $ANIM #${ctx.req} and // name: a short name (2–4 words); for a loop — // loop: N; then function frame(t, ctx, W, H, data) { … }. No HTML, no explanations."))
        } else {
            append(StudioScheme.PROTOCOL).append(' ')
            val ty = type(b.type)
            if (ty.layout.isNotEmpty()) append(t("Подходит layout ${ty.layout}, если смысл не требует другого. ", "Layout ${ty.layout} fits unless the meaning needs another. "))
            append("\n\n")
            append(t("ОТВЕТ: один блок ```json одной схемы, первое поле — метка: {\"req\":\"${ctx.req}\",\"layout\":\"...\",\"title\":\"\",\"nodes\":[...]}. Без пояснений.",
                "ANSWER: one ```json block of one scheme, the first field is the mark: {\"req\":\"${ctx.req}\",\"layout\":\"...\",\"title\":\"\",\"nodes\":[...]}. No explanations."))
        }
    }

    private fun clockS(ms: Long): String = String.format(Locale.US, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000.0)

    /**
     * The request to fix an answer: the same request, the errors found, the author's edit and the previous answer. It still
     * starts with the Relay head.
     */
    fun fixRequest(request: String, kind: String, errors: List<String>, previous: String, edit: String = ""): String = buildString {
        append(request.trimEnd()).append("\n\n")
        if (errors.isNotEmpty()) append(t("ОШИБКИ ПРОШЛОГО ОТВЕТА:\n", "ERRORS OF THE PREVIOUS ANSWER:\n")).append(errors.joinToString("\n") { "- $it" }).append('\n')
        if (edit.isNotBlank()) append(t("ПРАВКА АВТОРА: ", "AUTHOR'S EDIT: ")).append(edit.trim()).append('\n')
        append(t("ПРОШЛЫЙ ОТВЕТ:\n", "PREVIOUS ANSWER:\n"))
        append(if (kind == KIND) "```javascript\n" else "```json\n").append(previous.trim()).append("\n```\n")
        append(t("Исправь и верни ответ целиком в том же формате, с той же меткой.", "Fix it and return the whole answer in the same format, with the same mark."))
    }

    // ---------- the answer ----------

    private val ANIM_HEAD = Regex("""//\s*lumen\.anim\s*v1\s*#([A-Z2-9]{4})""")
    private val LOOP = Regex("""^\s*//\s*loop\s*[:：]\s*(\d+(?:[.,]\d+)?)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val FRAME = Regex("""function\s+frame\s*\(|(?:const|let|var)\s+frame\s*=|window\.frame\s*=""")
    private val JSON_FENCE = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*```")

    /**
     * An answer of the AI in [text]: a scheme (JSON with nodes) or an animation (code with frame), with its request mark;
     * null for our own request, nothing recognisable or an empty text.
     */
    fun detect(text: String?): InsertAnswer? {
        val raw = text?.let { RelayText.clean(it, false) }.orEmpty()
        if (raw.length < 8 || RelayText.isOwnRequest(raw)) return null
        val json = JSON_FENCE.find(raw)?.groupValues?.get(1)
            ?: raw.indexOf('{').takeIf { it >= 0 && raw.lastIndexOf('}') > it && !FRAME.containsMatchIn(raw) }?.let { raw.substring(it, raw.lastIndexOf('}') + 1) }
        if (json != null) {
            val o = runCatching { JSONObject(RelayText.tidyJson(json)) }.getOrNull()
            if (o != null && o.has("nodes")) {
                val s = StudioScheme.fromJson(o)
                if (s != null && s.nodes.isNotEmpty()) {
                    val req = o.optString("req").trim().removePrefix("#").uppercase().takeIf { it.length == 4 } ?: MARK.find(raw)?.groupValues?.get(1).orEmpty()
                    return InsertAnswer.Scheme(s, req, s.title.ifEmpty { s.nodes.first().label })
                }
            }
        }
        val code = WebLayer.extract(raw)
        if (!FRAME.containsMatchIn(code)) return null
        val req = ANIM_HEAD.find(code)?.groupValues?.get(1) ?: MARK.find(code.lineSequence().take(4).joinToString("\n"))?.groupValues?.get(1).orEmpty()
        val loop = LOOP.find(code)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.coerceIn(0L, MAX_ANIM_MS) ?: 0L
        return InsertAnswer.Anim(code, req, WebLayer.nameOf(code.lines().filterNot { ANIM_HEAD.containsMatchIn(it) || LOOP.containsMatchIn(it) }.joinToString("\n"), t("Анимация", "Animation")), loop)
    }

    /** Problems of an animation answer worth fixing before the trial run (lumen.canvas checks plus the block's own). */
    fun animProblems(code: String): List<String> {
        val p = WebLayer.problems(code).toMutableList()
        if (Regex("""\bdata\.duration\b|\bd\.duration\b""").containsMatchIn(code))
            p += t("в lumen.anim длительность — data.dur (секунды блока), а не data.duration", "in lumen.anim the length is data.dur (block seconds), not data.duration")
        if (Regex("""new\s+Image\s*\(|drawImage\s*\(\s*['"]""").containsMatchIn(code))
            p += t("картинки по URL не загрузятся — рисуй фигурами или эмодзи", "pictures by URL do not load — draw shapes or emoji")
        return p
    }

    /** Problems of a trial run: [msPerFrame] of the slowest frame, [errors] of JS, [empty] / [opaque] — all frames empty / the whole block filled. */
    fun trialProblems(msPerFrame: Double, errors: List<String>, allEmpty: Boolean, opaqueFull: Boolean, full: Boolean): List<String> {
        val p = errors.distinct().take(3).toMutableList()
        if (msPerFrame > 40) p += t("кадр рисуется ${msPerFrame.roundToInt()} мс — нужно до 15 мс", "a frame takes ${msPerFrame.roundToInt()} ms — must be under 15 ms")
        if (allEmpty && errors.isEmpty()) p += t("все пробные кадры пустые — frame ничего не рисует", "all trial frames are empty — frame draws nothing")
        if (full && opaqueFull) p += t("блок на весь кадр залит непрозрачно — видео под ним не видно", "the full-frame block is opaque — the video under it is hidden")
        return p
    }

    // ---------- frames of an animation ----------

    /** Number of frames to render: the loop or the whole block. */
    fun frameCount(durMs: Long, fps: Int, loopMs: Long): Int = max(1, ceil((if (loopMs > 0) min(loopMs, MAX_ANIM_MS) else min(durMs, MAX_ANIM_MS)) * fps / 1000.0).toInt())

    /** Key of a render: the frames are for this size, length, fps, loop and data — any change needs new frames. */
    fun renderKey(w: Int, h: Int, durMs: Long, fps: Int, loopMs: Long, data: String): String {
        val d = if (loopMs > 0) 0L else durMs
        return String.format(Locale.US, "%dx%d_%d_%d_%d_%08x", w, h, d, fps, loopMs, data.hashCode())
    }

    /** Hash of the code: name of the folder anim/<hash>. */
    fun codeHash(code: String): String = String.format(Locale.US, "%08x%04x", code.trim().hashCode(), code.length and 0xFFFF)

    /** The frames do not fit the block any more (another key or none). */
    fun stale(meta: AnimMeta?, key: String): Boolean = meta == null || meta.key != key

    /**
     * Frame to show at [tMs] from the block start: in a loop — by its period; frames made for another length are
     * stretched over the block; null before the start or when there are no frames.
     */
    fun frameIndex(tMs: Long, durMs: Long, meta: AnimMeta?): Int? {
        if (meta == null || meta.count <= 0 || tMs < 0) return null
        if (meta.loopMs > 0) return (floor((tMs % meta.loopMs) * meta.fps / 1000.0).toInt()).coerceIn(0, meta.count - 1)
        val t = if (meta.durMs > 0 && durMs > 0 && abs(meta.durMs - durMs) > 1) tMs * meta.durMs.toDouble() / durMs else tMs.toDouble()
        return floor(t * meta.fps / 1000.0).toInt().coerceIn(0, meta.count - 1)
    }

    // ---------- JSON ----------

    private fun rect(r: FRect?): JSONArray? = r?.let { JSONArray().put(it.l.toDouble()).put(it.t.toDouble()).put(it.r.toDouble()).put(it.b.toDouble()) }

    private fun rectOf(a: JSONArray?): FRect? = if (a == null || a.length() < 4) null else
        FRect(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat(), a.optDouble(3).toFloat()).takeIf { it.w > 0f && it.h > 0f }

    private fun JSONObject.opt3(k: String, v: Boolean?): JSONObject = if (v == null) this else put(k, v)
    private fun JSONObject.bool3(k: String): Boolean? = if (has(k)) optBoolean(k) else null
    private fun JSONObject.color(k: String): Int? = if (has(k)) optLong(k).toInt() else null

    /** The brief as JSON: only fields that differ from «Авто». */
    fun briefJson(b: EditBrief): JSONObject {
        val d = EditBrief()
        val o = JSONObject().put("type", b.type)
        if (b.draw != d.draw) o.put("draw", b.draw)
        if (b.what.isNotEmpty()) o.put("what", b.what)
        if (b.source.isNotEmpty()) o.put("source", b.source)
        if (b.rows.isNotEmpty()) o.put("rows", JSONArray().apply {
            b.rows.forEach { r -> put(JSONObject().put("label", r.label).put("value", r.value).put("unit", r.unit).put("icon", r.icon).put("main", r.main)) }
        })
        if (b.fields.isNotEmpty()) o.put("fields", JSONObject(b.fields as Map<*, *>))
        if (!b.onlySaid) o.put("onlySaid", false)
        if (b.title.isNotEmpty()) o.put("title", b.title)
        if (b.items > 0) o.put("items", b.items)
        listOf("labels" to b.labels, "icons" to b.icons, "lang" to b.lang, "tone" to b.tone, "style" to b.style, "palette" to b.palette,
            "bg" to b.bg, "radius" to b.radius, "font" to b.font, "size" to b.size, "like" to b.like, "dont" to b.dont,
            "in" to b.animIn, "out" to b.animOut, "reveal" to b.reveal, "pace" to b.pace, "sound" to b.sound,
            "place" to b.place, "scale" to b.scale, "shape" to b.shape, "layer" to b.layer).forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, v) }
        b.accent?.let { o.put("accent", it.toLong() and 0xFFFFFFFFL) }
        if (b.bgAlpha >= 0) o.put("bgAlpha", b.bgAlpha)
        o.opt3("border", b.border).opt3("shadow", b.shadow).opt3("upper", b.upper).opt3("avoidFace", b.avoidFace).opt3("avoidSubs", b.avoidSubs).opt3("avoidUi", b.avoidUi)
        if (b.donts.isNotEmpty()) o.put("donts", JSONArray(b.donts))
        if (b.opacity != 1f) o.put("opacity", b.opacity.toDouble())
        if (b.dim > 0f) o.put("dim", b.dim.toDouble())
        if (b.dimAll) o.put("dimAll", true)
        if (b.lenMs > 0) o.put("len", b.lenMs)
        if (!b.accentMain) o.put("accentMain", false)
        if (b.holdMs >= 0) o.put("hold", b.holdMs)
        if (b.loopS > 0) o.put("loop", b.loopS)
        rect(b.rect)?.let { o.put("rect", it) }
        rect(b.face)?.let { o.put("face", it) }
        return o
    }

    fun briefFrom(o: JSONObject?): EditBrief? {
        if (o == null) return null
        fun s(k: String) = o.optString(k).trim()
        val rows = o.optJSONArray("rows")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { BriefRow(it.optString("label"), it.optString("value"), it.optString("unit"), it.optString("icon"), it.optBoolean("main")) } }
        }.orEmpty().take(8)
        val fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } }.orEmpty()
        val donts = o.optJSONArray("donts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return EditBrief(
            type = s("type").takeIf { id -> TYPES.any { it.id == id } } ?: "own", draw = s("draw"), what = o.optString("what"), source = s("source"),
            rows = rows, fields = fields, onlySaid = o.optBoolean("onlySaid", true), title = o.optString("title"), items = o.optInt("items").coerceIn(0, 12),
            labels = s("labels"), icons = s("icons"), lang = s("lang"), tone = s("tone"), style = s("style"), accent = o.color("accent"), palette = s("palette"),
            bg = s("bg"), bgAlpha = o.optInt("bgAlpha", -1).coerceIn(-1, 100), radius = s("radius"), border = o.bool3("border"), shadow = o.bool3("shadow"),
            font = s("font"), upper = o.bool3("upper"), size = s("size"), like = o.optString("like"), donts = donts, dont = o.optString("dont"),
            opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0.1f, 1f), dim = o.optDouble("dim", 0.0).toFloat().coerceIn(0f, 0.6f), dimAll = o.optBoolean("dimAll"),
            lenMs = o.optLong("len").coerceIn(0L, 120_000L), animIn = s("in"), animOut = s("out"), reveal = s("reveal"), pace = s("pace"),
            accentMain = o.optBoolean("accentMain", true), holdMs = o.optLong("hold", -1L).coerceIn(-1L, 10_000L), loopS = o.optInt("loop").coerceIn(0, 30), sound = s("sound"),
            place = s("place"), rect = rectOf(o.optJSONArray("rect")), scale = s("scale"), shape = s("shape"),
            avoidFace = o.bool3("avoidFace"), avoidSubs = o.bool3("avoidSubs"), avoidUi = o.bool3("avoidUi"), face = rectOf(o.optJSONArray("face")), layer = s("layer")
        )
    }

    fun briefFrom(s: String): EditBrief? = if (s.isBlank()) null else runCatching { briefFrom(JSONObject(s)) }.getOrNull()

    fun lookJson(l: BlockLook): JSONObject = JSONObject().apply {
        if (l.fill.isNotEmpty()) put("fill", l.fill)
        if (l.bgAlpha >= 0) put("bgAlpha", l.bgAlpha)
        l.accent?.let { put("accent", it.toLong() and 0xFFFFFFFFL) }
        if (l.font.isNotEmpty()) put("font", l.font)
        l.upper?.let { put("upper", it) }
        if (l.textScale > 0f) put("textScale", l.textScale.toDouble())
        if (l.radius.isNotEmpty()) put("radius", l.radius)
        l.border?.let { put("border", it) }
        l.shadow?.let { put("shadow", it) }
    }

    fun lookFrom(o: JSONObject?): BlockLook? = o?.let {
        BlockLook(it.optString("fill"), it.optInt("bgAlpha", -1).coerceIn(-1, 100), it.color("accent"), it.optString("font"), it.bool3("upper"),
            it.optDouble("textScale", 0.0).toFloat().coerceIn(0f, 3f), it.optString("radius"), it.bool3("border"), it.bool3("shadow")).takeIf { l -> !l.isEmpty }
    }

    // ---------- look over the style ----------

    /** Corner radius (fraction of the short side) of a radius name; "" = [default]. */
    fun radiusOf(r: String, default: Float): Float = when (r) { "none" -> 0f; "s" -> 0.03f; "m" -> 0.06f; "l" -> 0.1f; else -> default }

    /** The scheme style with the block's own look over it: plate, accent, font, case, text size, rounding, border, shadow. */
    fun overStyle(st: SchemeStyle, o: BlockLook?): SchemeStyle {
        if (o == null) return st
        return st.copy(
            fill = when (o.fill) { "none" -> "none"; "glass", "semi" -> "glass"; "solid", "full" -> "solid"; else -> st.fill },
            accent = o.accent ?: st.accent,
            font = o.font.takeIf { it in SchemeStyles.FONTS } ?: st.font,
            upper = o.upper ?: st.upper,
            textScale = if (o.textScale > 0f) st.textScale * o.textScale else st.textScale,
            radius = when (o.radius) { "none" -> 0f; "s" -> 0.08f; "m" -> 0.14f; "l" -> 0.24f; else -> st.radius },
            border = o.border?.let { if (it) max(1f, st.border) else 0f } ?: st.border,
            shadow = o.shadow ?: st.shadow
        )
    }

    // ---------- defaults, style of the video, recipes ----------

    /** The brief the wizard opens with: type, data from the speech, the face, the style of the video over it. */
    fun defaults(type: String, speech: String, brand: EditBrief?, face: FRect?): EditBrief {
        val rows = dataFromSpeech(speech)
        val ty = type(type)
        val content = EditBrief(
            type = ty.id, face = face,
            rows = when (ty.id) {
                "number", "progress" -> rows.filter { it.value.isNotEmpty() }.take(1)
                "quote", "kinetic", "lower", "cta", "pointer" -> emptyList()
                "compare" -> rows.take(2)
                else -> rows
            },
            fields = when (ty.id) {
                "kinetic" -> mapOf("phrase" to speech.split(Regex("\\s+")).filter { it.isNotBlank() }.take(8).joinToString(" "))
                "quote" -> mapOf("text" to speech.trim().take(160))
                else -> emptyMap()
            }
        )
        return if (brand == null) content else withBrand(content, brand)
    }

    /** The style of the video ([EProject.brand]): the look, the transparency and the pace of a brief, no content. */
    fun brandOf(b: EditBrief): EditBrief = EditBrief(
        style = b.style, accent = b.accent, palette = b.palette, bg = b.bg, bgAlpha = b.bgAlpha, radius = b.radius, border = b.border, shadow = b.shadow,
        font = b.font, upper = b.upper, size = b.size, donts = b.donts, dont = b.dont, opacity = b.opacity, dim = b.dim, dimAll = b.dimAll, pace = b.pace
    )

    fun withBrand(b: EditBrief, brand: EditBrief): EditBrief = b.copy(
        style = brand.style, accent = brand.accent, palette = brand.palette, bg = brand.bg, bgAlpha = brand.bgAlpha, radius = brand.radius,
        border = brand.border, shadow = brand.shadow, font = brand.font, upper = brand.upper, size = brand.size, donts = brand.donts, dont = brand.dont,
        opacity = brand.opacity, dim = brand.dim, dimAll = brand.dimAll, pace = brand.pace
    )

    /** A recipe: the brief without its content (type, look, transparency, motion, place). */
    fun recipeOf(b: EditBrief): EditBrief = b.copy(what = "", source = "", rows = emptyList(), fields = emptyMap(),
        title = if (b.title == "-") "-" else "", lenMs = 0, rect = null, face = null, lang = "")

    /** [content] (what to show, data, time, face) with everything else from [recipe]. */
    fun withRecipe(content: EditBrief, recipe: EditBrief): EditBrief = recipe.copy(
        what = content.what, source = content.source, rows = content.rows, fields = content.fields,
        title = if (recipe.title == "-") "-" else content.title, lenMs = content.lenMs, rect = content.rect, face = content.face, lang = content.lang
    )

    /** A line about a recipe for its card. */
    fun recipeName(b: EditBrief): String = listOfNotNull(type(b.type).let { it.emoji + " " + it.name }, bgName(b.bg).takeIf { it.isNotEmpty() },
        when (b.pace) { "calm" -> t("спокойно", "calm"); "drive" -> t("драйв", "drive"); else -> null }, b.place.takeIf { it.isNotEmpty() }?.let { placeName(it) }).joinToString(" · ")

    fun placeName(p: String): String = when (p) {
        "full" -> t("Полный кадр", "Full frame")
        "cut" -> t("Перебивка", "Cutaway")
        "top" -> t("Сверху", "Top")
        "bottom" -> t("Снизу", "Bottom")
        "corner" -> t("Угол", "Corner")
        "custom" -> t("Своё место", "Own place")
        else -> t("Центр", "Centre")
    }

    // ---------- Montage templates as an animation ----------

    /**
     * A lumen.canvas v1 template of the Montage as a lumen.anim block: it gets the block's seconds and size as if they
     * were the whole video (data.duration = data.dur, no scenes).
     */
    fun canvasAdapter(code: String): String = "// $ANIM\n" + code.trim() + "\n;(function(){var _f=frame,_s=(typeof setup==='function')?setup:null;" +
        "setup=function(d){d.duration=d.dur;d.scenes=d.scenes||[];if(_s)_s(d)};frame=function(t,c,W,H,d){return _f(t,c,W,H,d)}})();\n"

    /** The look the brief sets on the block itself (no AI needed: seen on the mock-up at once). */
    fun lookOf(b: EditBrief): BlockLook? = BlockLook(b.bg, b.bgAlpha, b.accent, b.font, b.upper,
        when (b.size) { "s" -> 0.85f; "l" -> 1.25f; else -> 0f }, b.radius, b.border, b.shadow).takeIf { !it.isEmpty }
}

/** Built-in lumen.anim v1 blocks: they work without AI (data from the brief and the speech) and show the AI how the code looks. */
object AnimExamples {
    data class Example(val id: String, val type: String, val ru: String, val en: String, val code: String) {
        val name: String get() = t(ru, en)
    }

    private val COUNTER = """
// lumen.anim v1
// name: Счётчик числа
function setup(d) {
  var b = d.brief || {}, it = (b.items || [])[0] || {}, v = String(it.value || '');
  d.at = 0.2;
  if (!v) { var ws = d.words || []; for (var i = 0; i < ws.length; i++) if (/\d/.test(ws[i].w)) { v = ws[i].w.replace(/[^\d.,]/g, ''); d.at = ws[i].s; break; } }
  d.num = parseFloat(v.replace(',', '.')) || 0;
  d.dec = (v.split(/[.,]/)[1] || '').length;
  d.sep = v.indexOf(',') >= 0 ? ',' : '.';
  d.unit = it.unit || '';
  d.cap = it.label || b.title || '';
}
function fmt(x, d) {
  var s = x.toFixed(d.dec); if (d.sep === ',') s = s.replace('.', ',');
  var p = s.split(d.sep); p[0] = p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ' '); return p.join(d.sep);
}
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 3, a = ease.out(prog(t, 0, 0.35)) * (1 - prog(t, dur - 0.35, dur));
  if (a <= 0) return false;
  var bg = ((d.brief && d.brief.bgAlpha != null) ? d.brief.bgAlpha : 70) / 100, mf = (d.brief && d.brief.minFont) || 24;
  ctx.globalAlpha = a;
  roundRect(ctx, 0, 0, W, H, Math.min(W, H) * 0.12); ctx.fillStyle = 'rgba(16,19,24,' + bg + ')'; ctx.fill();
  var k = ease.out(prog(t, d.at, d.at + Math.min(1.6, dur * 0.5)));
  var txt = fmt(d.num * k, d) + (d.unit ? (d.unit === '%' ? '%' : ' ' + d.unit) : '');
  var size = fitFont(ctx, txt, W * 0.86, H * (d.cap ? 0.45 : 0.6), 900);
  ctx.fillStyle = A; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.font = '900 ' + size + 'px sans-serif'; ctx.fillText(txt, W / 2, H * (d.cap ? 0.42 : 0.5));
  if (d.cap) {
    var cs = fitFont(ctx, d.cap, W * 0.86, Math.max(mf, H * 0.14), 700);
    ctx.fillStyle = d.ink || '#fff'; ctx.font = '700 ' + cs + 'px sans-serif'; ctx.fillText(d.cap, W / 2, H * 0.78);
  }
}
""".trim()

    private val BARS = """
// lumen.anim v1
// name: Растущие столбцы
function setup(d) {
  var it = (d.brief && d.brief.items) || [];
  d.rows = [];
  for (var i = 0; i < it.length && d.rows.length < 6; i++) {
    var v = parseFloat(String(it[i].value).replace(',', '.'));
    if (!isNaN(v)) d.rows.push({ l: it[i].label || '', v: v, s: String(it[i].value) + (it[i].unit ? (it[i].unit === '%' ? '%' : ' ' + it[i].unit) : ''), m: !!it[i].main });
  }
  if (!d.rows.length) d.rows = [{ l: 'A', v: 3, s: '3' }, { l: 'B', v: 5, s: '5', m: true }, { l: 'C', v: 2, s: '2' }];
  d.max = 0; for (var j = 0; j < d.rows.length; j++) d.max = Math.max(d.max, d.rows[j].v);
}
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 4, a = ease.out(prog(t, 0, 0.3)) * (1 - prog(t, dur - 0.3, dur)), n = d.rows.length;
  if (a <= 0) return false;
  ctx.globalAlpha = a;
  roundRect(ctx, 0, 0, W, H, Math.min(W, H) * 0.08); ctx.fillStyle = 'rgba(16,19,24,0.72)'; ctx.fill();
  var pad = W * 0.08, bw = (W - pad * 2) / n, base = H * 0.8, top = H * 0.16;
  var fs = Math.max((d.brief && d.brief.minFont) || 20, Math.min(bw * 0.28, H * 0.08));
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  for (var i = 0; i < n; i++) {
    var r = d.rows[i], g = ease.back(prog(t, 0.25 + i * 0.18, 0.85 + i * 0.18)), h = Math.max(0, (base - top) * (r.v / (d.max || 1)) * g), x = pad + bw * i;
    ctx.fillStyle = r.m ? A : 'rgba(255,255,255,0.85)';
    roundRect(ctx, x + bw * 0.18, base - h, bw * 0.64, h, bw * 0.08); ctx.fill();
    ctx.fillStyle = d.ink || '#fff';
    ctx.font = '800 ' + fs + 'px sans-serif'; if (g > 0.6) ctx.fillText(r.s, x + bw / 2, base - h - fs * 0.8);
    ctx.font = '600 ' + fs * 0.9 + 'px sans-serif'; ctx.fillText(r.l.slice(0, 14), x + bw / 2, base + fs * 0.9);
  }
}
""".trim()

    private val RING = """
// lumen.anim v1
// name: Кольцо прогресса
function setup(d) {
  var it = ((d.brief && d.brief.items) || [])[0] || {}, v = parseFloat(String(it.value || '').replace(',', '.'));
  if (isNaN(v)) { var ws = d.words || []; for (var i = 0; i < ws.length; i++) { var m = ws[i].w.match(/\d+([.,]\d+)?/); if (m) { v = parseFloat(m[0].replace(',', '.')); break; } } }
  d.p = isNaN(v) ? 0.75 : clamp(v > 1 ? v / 100 : v, 0, 1);
  d.cap = it.label || (d.brief && d.brief.title) || '';
}
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 4, a = ease.out(prog(t, 0, 0.3)) * (1 - prog(t, dur - 0.3, dur));
  if (a <= 0) return false;
  ctx.globalAlpha = a;
  var cx = W / 2, cy = H * (d.cap ? 0.44 : 0.5), r = Math.min(W, H) * (d.cap ? 0.3 : 0.36), lw = r * 0.22;
  ctx.fillStyle = 'rgba(16,19,24,0.7)'; ctx.beginPath(); ctx.arc(cx, cy, r + lw, 0, Math.PI * 2); ctx.fill();
  ctx.lineWidth = lw; ctx.lineCap = 'round'; ctx.strokeStyle = 'rgba(255,255,255,0.18)';
  ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
  var k = ease.inOut(prog(t, 0.3, 0.3 + Math.min(1.8, dur * 0.5))) * d.p;
  ctx.strokeStyle = A; ctx.beginPath(); ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * Math.max(0.001, k)); ctx.stroke();
  ctx.fillStyle = d.ink || '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  var txt = Math.round(k * 100) + '%';
  ctx.font = '900 ' + fitFont(ctx, txt, r * 1.3, r * 0.55, 900) + 'px sans-serif'; ctx.fillText(txt, cx, cy);
  if (d.cap) { ctx.font = '700 ' + fitFont(ctx, d.cap, W * 0.9, Math.max((d.brief && d.brief.minFont) || 22, H * 0.09), 700) + 'px sans-serif'; ctx.fillText(d.cap, cx, H * 0.88); }
}
""".trim()

    private val CHECKS = """
// lumen.anim v1
// name: Список с галочками
function setup(d) {
  var it = (d.brief && d.brief.items) || [], ws = d.words || [];
  d.rows = [];
  for (var i = 0; i < it.length && d.rows.length < 6; i++) d.rows.push({ l: (it[i].icon ? it[i].icon + ' ' : '') + (it[i].label || it[i].value), s: -1 });
  if (!d.rows.length) d.rows = [{ l: 'Пункт 1', s: -1 }, { l: 'Пункт 2', s: -1 }, { l: 'Пункт 3', s: -1 }];
  var step = Math.max(0.4, ((d.dur || 4) - 1.2) / d.rows.length);
  for (var j = 0; j < d.rows.length; j++) {
    var key = String(d.rows[j].l).toLowerCase().replace(/[^\p{L}\p{N}]/gu, '').slice(0, 5);
    for (var k = 0; k < ws.length && key.length >= 3; k++) if (ws[k].w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '').indexOf(key) === 0) { d.rows[j].s = ws[k].s; break; }
    if (d.rows[j].s < 0) d.rows[j].s = 0.3 + j * step;
  }
}
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 4, n = d.rows.length, a = ease.out(prog(t, 0, 0.3)) * (1 - prog(t, dur - 0.3, dur));
  if (a <= 0) return false;
  ctx.globalAlpha = a;
  roundRect(ctx, 0, 0, W, H, Math.min(W, H) * 0.08); ctx.fillStyle = 'rgba(16,19,24,0.74)'; ctx.fill();
  var rh = H / (n + 0.6), fs = Math.max((d.brief && d.brief.minFont) || 22, rh * 0.42), x0 = W * 0.08;
  ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
  for (var i = 0; i < n; i++) {
    var r = d.rows[i], p = clamp(ease.back(prog(t, r.s, r.s + 0.35)), 0, 1.2), y = rh * (i + 0.8);
    if (p <= 0) continue;
    ctx.save(); ctx.globalAlpha = a * clamp(p, 0, 1); ctx.translate((1 - clamp(p, 0, 1)) * W * 0.1, 0);
    ctx.fillStyle = A; ctx.beginPath(); ctx.arc(x0 + fs * 0.5, y, fs * 0.55, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = '#111'; ctx.lineWidth = fs * 0.14; ctx.lineCap = 'round'; ctx.beginPath();
    ctx.moveTo(x0 + fs * 0.25, y); ctx.lineTo(x0 + fs * 0.45, y + fs * 0.2); ctx.lineTo(x0 + fs * 0.78, y - fs * 0.2); ctx.stroke();
    ctx.fillStyle = d.ink || '#fff';
    var s = fitFont(ctx, r.l, W - x0 * 2 - fs * 1.6, fs, 700); ctx.font = '700 ' + s + 'px sans-serif';
    ctx.fillText(r.l, x0 + fs * 1.6, y); ctx.restore();
  }
}
""".trim()

    private val POINTER = """
// lumen.anim v1
// name: Стрелка-указатель
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 3, u = Math.min(W, H), out = 1 - prog(t, dur - 0.3, dur);
  if (out <= 0) return false;
  var cx = W * 0.5, cy = H * 0.55, p = ease.out(prog(t, 0.1, 0.6)), q = clamp(ease.back(prog(t, 0.5, 0.9)), 0, 1.2);
  ctx.globalAlpha = out;
  ctx.strokeStyle = A; ctx.lineWidth = u * 0.025; ctx.lineCap = 'round';
  ctx.beginPath(); ctx.arc(cx, cy, u * 0.18 * (0.6 + 0.4 * q), -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * Math.max(0.001, p)); ctx.stroke();
  var cap = (d.brief && d.brief.title) || ((((d.brief && d.brief.items) || [])[0] || {}).label) || '';
  var fs = Math.max((d.brief && d.brief.minFont) || 22, u * 0.07), bx = W * 0.05, by = H * 0.05, bh = fs * 1.5;
  var sx = bx + fs, sy = by + bh + fs * 0.4, ex = cx - u * 0.2, ey = cy - u * 0.2, k = ease.inOut(prog(t, 0.3, 0.8));
  ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(lerp(sx, ex, k), lerp(sy, ey, k)); ctx.stroke();
  if (k > 0.95) {
    var ang = Math.atan2(ey - sy, ex - sx), hl = u * 0.06;
    ctx.beginPath(); ctx.moveTo(ex, ey); ctx.lineTo(ex - hl * Math.cos(ang - 0.5), ey - hl * Math.sin(ang - 0.5));
    ctx.moveTo(ex, ey); ctx.lineTo(ex - hl * Math.cos(ang + 0.5), ey - hl * Math.sin(ang + 0.5)); ctx.stroke();
  }
  if (cap && q > 0) {
    ctx.font = '800 ' + fs + 'px sans-serif'; var tw = Math.min(W * 0.9, ctx.measureText(cap).width + fs);
    ctx.globalAlpha = out * clamp(q, 0, 1); roundRect(ctx, bx, by, tw, bh, fs * 0.3); ctx.fillStyle = A; ctx.fill();
    ctx.fillStyle = '#111'; ctx.textBaseline = 'middle'; ctx.textAlign = 'left'; ctx.fillText(cap, bx + fs * 0.5, by + bh / 2, tw - fs);
  }
}
""".trim()

    private val KINETIC = """
// lumen.anim v1
// name: Кинетический титр
function setup(d) {
  var ws = d.words || [], b = d.brief || {};
  var src = ws.length ? ws : String(b.title || d.title || 'КЛЮЧЕВАЯ ФРАЗА').split(/\s+/).map(function (w, i) { return { w: w, s: 0.2 + i * 0.35 }; });
  d.ws = [];
  // a sign without letters (%, —) sticks to the word before it
  for (var j = 0; j < src.length; j++) {
    if (!/[\p{L}\p{N}]/u.test(src[j].w) && d.ws.length) d.ws[d.ws.length - 1].w += src[j].w;
    else if (/[\p{L}\p{N}]/u.test(src[j].w)) d.ws.push({ w: src[j].w, s: src[j].s });
  }
  if (d.ws.length > 8) d.ws = d.ws.slice(0, 8);
  var best = 0; for (var i = 1; i < d.ws.length; i++) if (d.ws[i].w.length > d.ws[best].w.length) best = i; d.best = best;
}
function frame(t, ctx, W, H, d) {
  var A = d.accent || '#FFD60A', dur = d.dur || 3, out = 1 - prog(t, dur - 0.3, dur), n = d.ws.length;
  if (out <= 0 || !n || t < d.ws[0].s - 0.05) return false;
  var per = n > 4 ? 3 : 2, lines = Math.ceil(n / per), lh = H / (lines + 0.5), fs = Math.min(lh * 0.62, W * 0.16);
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  for (var l = 0; l < lines; l++) {
    var row = d.ws.slice(l * per, l * per + per), txt = row.map(function (x) { return x.w.toUpperCase(); }).join(' ');
    var size = fitFont(ctx, txt, W * 0.92, fs, 900); ctx.font = '900 ' + size + 'px sans-serif';
    var x = (W - ctx.measureText(txt).width) / 2, y = lh * (l + 0.75), gap = ctx.measureText(' ').width;
    for (var i = 0; i < row.length; i++) {
      var k = l * per + i, wd = row[i].w.toUpperCase(), ww = ctx.measureText(wd).width, p = ease.back(prog(t, row[i].s, row[i].s + 0.25));
      if (p > 0) {
        var sc = 0.6 + 0.4 * Math.min(p, 1.15);
        ctx.save(); ctx.globalAlpha = out * clamp(p, 0, 1); ctx.translate(x + ww / 2, y); ctx.scale(sc, sc);
        ctx.lineWidth = size * 0.12; ctx.strokeStyle = 'rgba(0,0,0,0.85)'; ctx.lineJoin = 'round'; ctx.strokeText(wd, 0, 0);
        ctx.fillStyle = k === d.best ? A : (d.ink || '#fff'); ctx.fillText(wd, 0, 0); ctx.restore();
      }
      x += ww + gap;
    }
  }
}
""".trim()

    val ALL = listOf(
        Example("counter", "number", "Счётчик числа", "Number counter", COUNTER),
        Example("bars", "chart", "Растущие столбцы", "Growing bars", BARS),
        Example("ring", "progress", "Кольцо прогресса", "Progress ring", RING),
        Example("checks", "list", "Список с галочками", "Checklist", CHECKS),
        Example("pointer", "pointer", "Стрелка-указатель", "Pointer arrow", POINTER),
        Example("kinetic", "kinetic", "Кинетический титр", "Kinetic title", KINETIC)
    )

    /** The sample put into the request: how a block reads data.brief and data.words. */
    val SAMPLE: String get() = COUNTER

    fun forType(type: String): Example? = ALL.firstOrNull { it.type == type }
}

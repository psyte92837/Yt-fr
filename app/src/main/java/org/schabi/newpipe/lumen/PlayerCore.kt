/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Quick filters of the Lumen player search (protocol lumen.player v1).
 * [period]: any | today | week | month | year | yYYYY (one calendar year).
 */
data class PFilter(
    val period: String = "any",
    /** At least this many views (0 = any). */
    val views: Long = 0,
    /** At least this many comments (0 = any); counted per video, so slower. */
    val comments: Int = 0,
    /** any | short (< 4 min) | medium (4–20 min) | long (> 20 min). */
    val length: String = "any",
    /** relevance | date | views. */
    val sort: String = "relevance",
    /** any | no (no Shorts) | only (Shorts only). */
    val shorts: String = "any",
    /** Domain preset id ("" = none); see [PlayerCore.DOMAINS]. */
    val domain: String = "",
    /** Criteria preset ids; see [PlayerCore.RULES]. */
    val rules: List<String> = emptyList(),
    /** Own words of the criteria for the AI filter. */
    val custom: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("period", period).put("views", views).put("comments", comments).put("length", length)
        .put("sort", sort).put("shorts", shorts).put("domain", domain).put("rules", JSONArray(rules)).put("custom", custom)

    companion object {
        fun from(o: JSONObject?): PFilter {
            if (o == null) return PFilter()
            val r = o.optJSONArray("rules")
            return PFilter(
                period = o.optString("period", "any"), views = o.optLong("views", 0), comments = o.optInt("comments", 0),
                length = o.optString("length", "any"), sort = o.optString("sort", "relevance"), shorts = o.optString("shorts", "any"),
                domain = o.optString("domain", ""), rules = if (r == null) emptyList() else (0 until r.length()).map { r.optString(it) }.filter { it.isNotBlank() },
                custom = o.optString("custom", "")
            )
        }
    }
}

/** A found video (only what the search page gives, plus the comment count when it was asked for). */
data class PItem(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelUrl: String = "",
    val uploadMs: Long = -1,
    val uploadText: String = "",
    val views: Long = -1,
    /** Seconds; ≤ 0 = not known (live). */
    val duration: Long = -1,
    val isShort: Boolean = false,
    val desc: String = "",
    /** −1 = not counted. */
    val comments: Int = -1
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val shortLike: Boolean get() = isShort || duration in 1..60

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("ch", channel).put("cu", channelUrl).put("up", uploadMs)
        .put("ut", uploadText).put("v", views).put("d", duration).put("s", isShort).put("desc", desc).put("c", comments)

    companion object {
        fun from(o: JSONObject): PItem? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            return PItem(id, o.optString("t"), o.optString("ch"), o.optString("cu"), o.optLong("up", -1), o.optString("ut"),
                o.optLong("v", -1), o.optLong("d", -1), o.optBoolean("s"), o.optString("desc"), o.optInt("c", -1))
        }
    }
}

/**
 * One search kept in the cache: what was asked, what was found, what the AI filter dropped ([dropped]: id → why) and
 * what the user brought back ([restored]). [more] = YouTube has more pages.
 */
data class PSearch(
    val query: String,
    val filter: PFilter,
    val at: Long,
    val items: List<PItem>,
    val dropped: Map<String, String> = emptyMap(),
    val restored: Set<String> = emptySet(),
    val more: Boolean = false
) {
    val key: String get() = PlayerCore.key(query, filter)

    /** What the list shows: everything not dropped by the AI (restored ones are back). */
    val shown: List<PItem> get() = items.filter { it.id !in dropped || it.id in restored }
    val hidden: List<PItem> get() = items.filter { it.id in dropped && it.id !in restored }

    fun toJson(): JSONObject = JSONObject().put("q", query).put("f", filter.toJson()).put("at", at)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("dropped", JSONObject().apply { dropped.forEach { (k, v) -> put(k, v) } })
        .put("restored", JSONArray(restored.toList())).put("more", more)

    companion object {
        fun from(o: JSONObject): PSearch? {
            val q = o.optString("q")
            if (q.isBlank()) return null
            val arr = o.optJSONArray("items") ?: JSONArray()
            val d = o.optJSONObject("dropped")
            val r = o.optJSONArray("restored")
            return PSearch(q, PFilter.from(o.optJSONObject("f")), o.optLong("at"),
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { PItem.from(it) } },
                d?.keys()?.asSequence()?.associateWith { d.optString(it) } ?: emptyMap(),
                if (r == null) emptySet() else (0 until r.length()).map { r.optString(it) }.toSet(),
                o.optBoolean("more"))
        }
    }
}

/** A watched video: where the user stopped (for "Continue watching"). */
data class PWatch(
    val id: String,
    val title: String,
    val channel: String = "",
    /** Seconds; ≤ 0 = not known. */
    val duration: Long = -1,
    val posMs: Long = 0,
    val at: Long = 0
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("ch", channel).put("d", duration).put("pos", posMs).put("at", at)

    companion object {
        fun from(o: JSONObject): PWatch? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            return PWatch(id, o.optString("t"), o.optString("ch"), o.optLong("d", -1), o.optLong("pos"), o.optLong("at"))
        }
    }
}

/** A ready domain or criterion of the AI filter: chip text and the rule the AI gets. */
data class PRule(val id: String, val emoji: String, val ru: String, val en: String, val ruRule: String, val enRule: String)

/** Pure logic of the Lumen player: filters, YouTube query, cache of searches, AI filter text, watch history. */
object PlayerCore {
    const val PROTOCOL = "lumen.player v1"
    const val DAY = 86_400_000L

    /** How long past searches are kept, days (0 = not kept). */
    val KEEP_DAYS = listOf(1, 3, 7, 30, 0)
    const val DEFAULT_KEEP = 7
    const val MAX_SEARCHES = 30
    const val MAX_ITEMS = 300
    const val MAX_HISTORY = 40

    val VIEWS = listOf(1_000L, 10_000L, 100_000L, 1_000_000L)
    val COMMENTS = listOf(10, 100, 1000)

    val DOMAINS = listOf(
        PRule("science", "🔬", "Наука", "Science", "наука и объяснение того, как устроен мир: физика, биология, химия, космос, научпоп",
            "science and explaining how the world works: physics, biology, chemistry, space, popular science"),
        PRule("tech", "💻", "Технологии", "Tech", "технологии, гаджеты, программирование, ИИ, обзоры техники",
            "technology, gadgets, programming, AI, tech reviews"),
        PRule("edu", "🎓", "Обучение", "Learning", "обучение и курсы: уроки, лекции, разборы тем, подготовка к экзаменам, языки",
            "learning: lessons, lectures, topic walkthroughs, exam prep, languages"),
        PRule("business", "💼", "Бизнес", "Business", "бизнес, маркетинг, продажи, финансы, инвестиции, карьера",
            "business, marketing, sales, finance, investing, career"),
        PRule("news", "📰", "Новости", "News", "новости и аналитика событий, интервью, политика и экономика",
            "news and analysis of events, interviews, politics and economy"),
        PRule("health", "💪", "Здоровье", "Health", "здоровье, спорт, тренировки, питание, психология",
            "health, sport, workouts, nutrition, psychology"),
        PRule("games", "🎮", "Игры", "Games", "видеоигры: прохождения, обзоры, новости игр, киберспорт",
            "video games: playthroughs, reviews, game news, esports"),
        PRule("music", "🎵", "Музыка", "Music", "музыка: клипы, концерты, альбомы, уроки музыки",
            "music: videos, concerts, albums, music lessons"),
        PRule("kids", "🧸", "Детям", "Kids", "видео для детей: мультфильмы, развивающие ролики, сказки",
            "videos for kids: cartoons, educational clips, stories"),
        PRule("food", "🍳", "Кулинария", "Cooking", "кулинария: рецепты, готовка, обзоры еды",
            "cooking: recipes, how to cook, food reviews"),
        PRule("diy", "🛠", "Своими руками", "DIY", "своими руками: ремонт, мастерская, рукоделие, сад и огород",
            "do it yourself: repairs, workshop, crafts, garden"),
        PRule("travel", "✈️", "Путешествия", "Travel", "путешествия: страны, города, маршруты, советы туристам",
            "travel: countries, cities, routes, tips for travellers")
    )

    val RULES = listOf(
        PRule("noclick", "🚫", "Без кликбейта", "No clickbait", "название честно описывает содержание, без кликбейта и обмана",
            "the title honestly describes the content, no clickbait"),
        PRule("expert", "🧠", "Экспертно", "Expert", "автор разбирается в теме: эксперт, практик, лекция, подробный разбор",
            "the author knows the topic: expert, practitioner, lecture, in-depth breakdown"),
        PRule("noreact", "🙅", "Без реакций и нарезок", "No reactions or clips", "не реакции, не нарезки чужих видео, не перезаливы",
            "no reactions, no clips cut from other videos, no reuploads"),
        PRule("fresh", "🆕", "Актуально", "Up to date", "информация актуальна сейчас, не устаревшие версии и события",
            "the information is current, no outdated versions or events"),
        PRule("calm", "🧘", "Спокойная подача", "Calm delivery", "спокойная подача без крика, драмы и шок-контента",
            "calm delivery without shouting, drama or shock content"),
        PRule("noads", "💸", "Без рекламы", "No ads", "не реклама, не обзор за деньги, не продажа курса",
            "not an ad, not a paid review, not a course sales pitch")
    )

    fun domain(id: String): PRule? = DOMAINS.firstOrNull { it.id == id }
    fun rule(id: String): PRule? = RULES.firstOrNull { it.id == id }

    // ---------- filters ----------

    /** The year of a yYYYY period, 0 otherwise. */
    fun year(f: PFilter): Int = if (f.period.length == 5 && f.period[0] == 'y') f.period.substring(1).toIntOrNull() ?: 0 else 0

    /** The last [n] years, newest first. */
    fun years(now: Long, n: Int = 8): List<Int> {
        val y = cal(now).get(Calendar.YEAR)
        return (0 until n).map { y - it }
    }

    private fun cal(ms: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }

    private fun yearStart(y: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(y, Calendar.JANUARY, 1, 0, 0, 0)
    }.timeInMillis

    /** Upload time bounds of the period as [start, end) in ms; null = any time. */
    fun range(f: PFilter, now: Long): LongRange? {
        val y = year(f)
        if (y > 0) return yearStart(y) until yearStart(y + 1)
        val start = when (f.period) {
            "today" -> now - DAY
            "week" -> now - 7 * DAY
            "month" -> now - 30 * DAY
            "year" -> now - 365 * DAY
            else -> return null
        }
        return start until now + DAY
    }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /**
     * Text sent to YouTube search: the query plus before:/after: operators of the period (YouTube narrows by date on its side;
     * the day before is taken so that time zones never cut a video off — the exact check is [passes]).
     */
    fun ytQuery(q: String, f: PFilter, now: Long): String {
        val r = range(f, now) ?: return q.trim()
        val sb = StringBuilder(q.trim())
        sb.append(" after:").append(iso(r.first - DAY))
        if (year(f) > 0) sb.append(" before:").append(iso(r.last + 1 + DAY))
        return sb.toString()
    }

    /** Local check of everything but the AI criteria and the comment count. */
    fun passes(it: PItem, f: PFilter, now: Long): Boolean {
        range(f, now)?.let { r -> if (it.uploadMs > 0 && it.uploadMs !in r) return false }
        if (f.views > 0 && it.views < f.views) return false
        when (f.length) {
            "short" -> if (it.duration !in 1 until 240) return false
            "medium" -> if (it.duration !in 240..1200) return false
            "long" -> if (it.duration <= 1200) return false
        }
        when (f.shorts) {
            "no" -> if (it.shortLike) return false
            "only" -> if (!it.shortLike) return false
        }
        return true
    }

    /** Comment count check: true / false, null = not counted yet. */
    fun commentsOk(it: PItem, f: PFilter): Boolean? = when {
        f.comments <= 0 -> true
        it.comments < 0 -> null
        else -> it.comments >= f.comments
    }

    fun sort(list: List<PItem>, sort: String): List<PItem> = when (sort) {
        "date" -> list.sortedByDescending { it.uploadMs }
        "views" -> list.sortedByDescending { it.views }
        else -> list
    }

    /** Filters that are not at their default (the AI criteria count once). */
    fun active(f: PFilter): Int = listOf(f.period != "any", f.views > 0, f.comments > 0, f.length != "any", f.sort != "relevance",
        f.shorts != "any", aiOn(f)).count { it }

    /** The same filters without the AI criteria. */
    fun plain(f: PFilter): PFilter = f.copy(domain = "", rules = emptyList(), custom = "")

    // ---------- AI filter ----------

    fun aiOn(f: PFilter): Boolean = criteria(f, false).isNotBlank()

    /** The criteria the AI checks, in one text ("" = AI filter off). */
    fun criteria(f: PFilter, en: Boolean): String {
        val parts = mutableListOf<String>()
        domain(f.domain)?.let { parts += (if (en) "Topic: " else "Тема: ") + if (en) it.enRule else it.ruRule }
        f.rules.mapNotNull { rule(it) }.forEach { parts += "• " + if (en) it.enRule else it.ruRule }
        f.custom.trim().takeIf { it.isNotEmpty() }?.let { parts += (if (en) "Wish: " else "Пожелание: ") + it.take(400) }
        return parts.joinToString("\n")
    }

    /** Short key of the criteria: verdicts of one set never mix with another. */
    fun criteriaKey(f: PFilter): String = Integer.toHexString(criteria(f, false).hashCode())

    /** Numbered lines for the AI: "n. title | channel | 12:34 | 1.2M views | description". */
    fun aiBlock(items: List<PItem>): String = items.mapIndexed { i, it ->
        val parts = mutableListOf(it.title.replace('\n', ' ').take(160), it.channel.take(60))
        if (it.duration > 0) parts += clock(it.duration)
        if (it.views >= 0) parts += "${it.views} views"
        it.desc.replace('\n', ' ').trim().take(140).takeIf { d -> d.isNotEmpty() }?.let { d -> parts += d }
        "${i + 1}. " + parts.filter { p -> p.isNotBlank() }.joinToString(" | ")
    }.joinToString("\n")

    /** Answer schema of the AI filter (Gemini responseSchema / OpenAI json_schema). */
    val SCHEMA: JSONObject get() = JSONObject("""{"type":"object","properties":{"keep":{"type":"array","items":{"type":"integer"}},""" +
        """"drop":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"},"why":{"type":"string"}},"required":["n","why"]}}},""" +
        """"required":["keep","drop"]}""")

    /**
     * Dropped numbers (1-based) with the reason from {"keep":[…],"drop":[{"n":3,"why":"…"}]}; numbers outside 1..[n] are ignored,
     * a number both kept and dropped stays kept, numbers the AI did not mention are kept. Null when it is not such an answer.
     */
    fun parseVerdicts(text: String, n: Int): Map<Int, String>? {
        val s = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(s.substring(s.indexOf('{').coerceAtLeast(0), s.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        if (!o.has("drop") && !o.has("keep")) return null
        val keep = HashSet<Int>()
        o.optJSONArray("keep")?.let { a -> for (i in 0 until a.length()) a.optInt(i, -1).takeIf { it in 1..n }?.let { keep += it } }
        val out = LinkedHashMap<Int, String>()
        val d = o.optJSONArray("drop") ?: JSONArray()
        for (i in 0 until d.length()) {
            val x = d.opt(i)
            val num = when (x) {
                is JSONObject -> x.optInt("n", -1)
                is Number -> x.toInt()
                else -> x?.toString()?.trim()?.toIntOrNull() ?: -1
            }
            if (num !in 1..n || num in keep) continue
            out[num] = (x as? JSONObject)?.optString("why").orEmpty().trim().take(140)
        }
        return out
    }

    // ---------- cache of searches ----------

    private fun norm(q: String) = q.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** The same query with the same filters is one cache entry. */
    fun key(q: String, f: PFilter): String = norm(q) + "|" + f.copy(rules = f.rules.sorted(), custom = f.custom.trim()).toJson().toString()

    /** Drops searches older than [days] (0 = keep none) and keeps the newest [max]. */
    fun prune(list: List<PSearch>, now: Long, days: Int, max: Int = MAX_SEARCHES): List<PSearch> {
        if (days <= 0) return emptyList()
        return list.filter { now - it.at < days * DAY }.sortedByDescending { it.at }.take(max)
    }

    /** Puts [s] first, replacing an older entry of the same query and filters; long lists are cut. */
    fun put(list: List<PSearch>, s: PSearch, max: Int = MAX_SEARCHES): List<PSearch> {
        val cut = if (s.items.size > MAX_ITEMS) s.copy(items = s.items.take(MAX_ITEMS)) else s
        return (listOf(cut) + list.filter { it.key != s.key }).take(max)
    }

    /** Past queries, newest first, without repeats; [prefix] narrows them. */
    fun recent(list: List<PSearch>, prefix: String = "", max: Int = 8): List<String> {
        val p = norm(prefix)
        val seen = HashSet<String>()
        return list.sortedByDescending { it.at }.map { it.query.trim() }
            .filter { q -> (p.isEmpty() || norm(q).contains(p)) && seen.add(norm(q)) }.take(max)
    }

    fun searchesJson(list: List<PSearch>): String = JSONObject().put("protocol", PROTOCOL)
        .put("searches", JSONArray().apply { list.forEach { put(it.toJson()) } }).toString()

    fun parseSearches(s: String?): List<PSearch> {
        if (s.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONObject(s).optJSONArray("searches") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { PSearch.from(it) } }
    }

    // ---------- watch history ----------

    /** Where to start a video again: the saved place, unless it is the very start or the very end. */
    fun resumeFrom(posMs: Long, durMs: Long): Long = when {
        posMs < 15_000 -> 0
        durMs > 0 && posMs > durMs - 20_000 -> 0
        else -> posMs
    }

    /** Share watched, 0..1 (0 when the length is not known). */
    fun progress(posMs: Long, durSec: Long): Float = if (durSec <= 0) 0f else (posMs / (durSec * 1000f)).coerceIn(0f, 1f)

    /** Puts [w] first (one entry per video), keeps [max]. */
    fun remember(list: List<PWatch>, w: PWatch, max: Int = MAX_HISTORY): List<PWatch> = (listOf(w) + list.filter { it.id != w.id }).take(max)

    /** "Continue watching": started and not finished. */
    fun unfinished(list: List<PWatch>): List<PWatch> = list.filter { resumeFrom(it.posMs, it.duration * 1000) > 0 }

    fun historyJson(list: List<PWatch>): String = JSONObject().put("protocol", PROTOCOL)
        .put("history", JSONArray().apply { list.forEach { put(it.toJson()) } }).toString()

    fun parseHistory(s: String?): List<PWatch> {
        if (s.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONObject(s).optJSONArray("history") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { PWatch.from(it) } }
    }

    // ---------- small formats ----------

    /** 754 → "12:34", 3723 → "1:02:03". */
    fun clock(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Age as (count, unit): unit is one of "min", "h", "d", "w", "mo", "y"; null when the time is not known. */
    fun ago(ms: Long, now: Long): Pair<Long, String>? {
        if (ms <= 0) return null
        val d = (now - ms).coerceAtLeast(0)
        return when {
            d < 3_600_000 -> maxOf(1, d / 60_000) to "min"
            d < DAY -> d / 3_600_000 to "h"
            d < 7 * DAY -> d / DAY to "d"
            d < 30 * DAY -> d / (7 * DAY) to "w"
            d < 365 * DAY -> d / (30 * DAY) to "mo"
            else -> d / (365 * DAY) to "y"
        }
    }

    /** 1234 → "1.2K", 12345 → "12K", 1234567 → "1.2M". */
    fun count(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> trim1(n / 1e9) + "B"
        n >= 1_000_000 -> trim1(n / 1e6) + "M"
        n >= 10_000 -> (n / 1000).toString() + "K"
        n >= 1_000 -> trim1(n / 1e3) + "K"
        else -> n.toString()
    }

    private fun trim1(x: Double): String = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
}

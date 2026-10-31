/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * Lumen Lens: pure logic of the floating button over the YouTube app (covered by LensTest).
 * The accessibility service reads the cards YouTube shows (their spoken descriptions),
 * this file turns them into videos, keeps the feed of the session and the analysis queue.
 */

/** One video card seen on the YouTube screen. [url] is empty until it is resolved by a search. */
data class FeedItem(
    val title: String,
    val channel: String = "",
    val views: Long = -1,
    val age: String = "",
    val duration: String = "",
    val short: Boolean = false,
    val live: Boolean = false,
    val url: String = ""
) {
    val key: String get() = FeedParse.key(title)

    fun meta(): String = listOfNotNull(
        channel.takeIf { it.isNotBlank() },
        if (views >= 0) Fmt.num(views) + t(" просм.", " views") else null,
        age.takeIf { it.isNotBlank() },
        duration.takeIf { it.isNotBlank() },
        if (short) "Shorts" else null,
        if (live) t("эфир", "live") else null
    ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("title", title).put("channel", channel).put("views", views).put("age", age)
        .put("duration", duration).put("short", short).put("live", live).put("url", url)

    companion object {
        fun from(o: JSONObject) = FeedItem(
            o.optString("title"), o.optString("channel"), o.optLong("views", -1), o.optString("age"),
            o.optString("duration"), o.optBoolean("short"), o.optBoolean("live"), o.optString("url")
        )
    }
}

/** Parses the content descriptions YouTube gives its video cards (English and Russian UI). */
object FeedParse {
    private val SEP = Regex("""\s+[-–—•·|]\s+""")
    private const val NUM = """(\d[\d\s\u00A0\u202F.,]*\s*(?:k|m|b|тыс\.?|млн|млрд|thousand|million|billion)?)"""
    private const val VIEWS_WORD = """(?:views?|просмотр\w*|watching|зрител\w*|смотрят)"""
    private val VIEWS = Regex("""(?iuU)(?:^|\s|,)$NUM\s*$VIEWS_WORD""")
    private val VIEWS_PART = Regex("""(?iuU)^$NUM\s*$VIEWS_WORD$""")
    private val NO_VIEWS = Regex("""(?iuU)^(no views|нет просмотров)$""")
    private val AGE = Regex("""(?iuU)^(\d+\s+\S+\s+(ago|назад)|(streamed|трансляция|премьера|premiered?|стрим)\b.*|вчера|yesterday|сегодня|today)$""")
    private val DURATION_WORDS = Regex("""(?iuU)^(\d+\s*(seconds?|minutes?|hours?|секунд\w*|минут\w*|час\w*)[\s,]*)+$""")
    private val TIMECODE = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val PLAY = Regex("""(?iuU)^(play( video| short\w*)?|воспроизвести.*|смотреть( видео)?|watch( video)?|play shorts?)$""")
    private val GOTO = Regex("""(?iuU)^(go to channel|перейти на канал|открыть канал)$""")
    private val LIVE = Regex("""(?iuU)^(live|в эфире|прямой эфир|live now|сейчас в эфире)$""")
    private val AD = Regex("""(?iuU)(^ad\b|^реклама\b|\bsponsored\b|спонсор\w*|^объявление\b)""")
    private val SHORT_MARK = Regex("""(?iuU)(play short|shorts?-ролик|воспроизвести shorts|#shorts)""")
    private val VERIFIED = Regex("""(?iuU)\s*(verified|подтвержд\w*|✓)\s*$""")

    private enum class Kind { TEXT, VIEWS, AGE, DURATION, PLAY, GOTO, LIVE }

    private fun kind(s: String): Kind = when {
        PLAY.matches(s) -> Kind.PLAY
        GOTO.matches(s) -> Kind.GOTO
        LIVE.matches(s) -> Kind.LIVE
        NO_VIEWS.matches(s) || VIEWS_PART.matches(s) -> Kind.VIEWS
        TIMECODE.matches(s) || DURATION_WORDS.matches(s) -> Kind.DURATION
        AGE.matches(s) -> Kind.AGE
        else -> Kind.TEXT
    }

    /** "1,2 млн просмотров", "1.2M views", "12 345 views", "No views" → count; -1 if there is no number. */
    fun views(s: String): Long {
        if (NO_VIEWS.containsMatchIn(s.trim())) return 0
        val m = VIEWS.find(s) ?: return -1
        val raw = m.groupValues[1].lowercase(Locale.ROOT).replace(Regex("""[\s  ]"""), "")
        val num = Regex("""^[\d.,]+""").find(raw)?.value ?: return -1
        val suffix = raw.substring(num.length).trimEnd('.')
        val mul = when (suffix) {
            "k", "тыс", "thousand" -> 1e3
            "m", "млн", "million" -> 1e6
            "b", "млрд", "billion" -> 1e9
            else -> 1.0
        }
        // "1,2" / "1.2" with a multiplier is a fraction; without one "," and "." group thousands
        val n = if (mul > 1.0) num.replace(',', '.').toDoubleOrNull() else num.replace(",", "").replace(".", "").toDoubleOrNull()
        return n?.let { (it * mul).toLong() } ?: -1
    }

    /** One card description → a video, or null for ads, buttons and anything that is not a video card. */
    fun parse(desc: String?): FeedItem? {
        val d = desc?.replace('\n', ' ')?.trim().orEmpty()
        if (d.length < 8 || AD.containsMatchIn(d)) return null
        val parts0 = d.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        // Shorts: "Title, 1.2M views - play Short" — the views are glued to the title by a comma
        val parts = parts0.flatMap { p ->
            val m = Regex("""^(.+),\s*([^,]*\d[^,]*(?:views?|просмотр\w*))$""", RegexOption.IGNORE_CASE).find(p)
            if (m != null && kind(p) == Kind.TEXT) listOf(m.groupValues[1].trim(), m.groupValues[2].trim()) else listOf(p)
        }
        val kinds = parts.map { kind(it) }
        if (Kind.VIEWS !in kinds && Kind.PLAY !in kinds && Kind.LIVE !in kinds) return null
        var channel = ""
        val gi = kinds.indexOf(Kind.GOTO)
        if (gi >= 0 && gi + 1 < parts.size && kinds[gi + 1] == Kind.TEXT) channel = parts[gi + 1]
        val firstMeta = kinds.indexOfFirst { it != Kind.TEXT }.let { if (it < 0) parts.size else it }
        var titleParts = parts.take(firstMeta)
        if (channel.isEmpty() && titleParts.size >= 2 && kinds.getOrNull(firstMeta) == Kind.VIEWS) {
            channel = titleParts.last()
            titleParts = titleParts.dropLast(1)
        }
        if (channel.isEmpty()) {
            // "Title - 10:05 - Channel - 1M views": a text part between other metadata is the channel
            channel = parts.indices.firstOrNull { it >= firstMeta && kinds[it] == Kind.TEXT && it != gi }?.let { parts[it] } ?: ""
        }
        val title = titleParts.joinToString(" - ").trim().trim('"', '«', '»')
        if (title.length < 2) return null
        val vi = kinds.indexOf(Kind.VIEWS)
        return FeedItem(
            title = title,
            channel = channel.replace(VERIFIED, "").trim(),
            views = if (vi >= 0) views(parts[vi]) else -1,
            age = kinds.indexOf(Kind.AGE).let { if (it >= 0) parts[it] else "" },
            duration = kinds.indexOf(Kind.DURATION).let { if (it >= 0) parts[it] else "" },
            short = SHORT_MARK.containsMatchIn(d),
            live = Kind.LIVE in kinds || Regex("""(?iuU)(watching|смотрят|зрител)""").containsMatchIn(d)
        )
    }

    /** Lower-case letters and digits only: the same title from two screens gives the same key. */
    fun norm(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    fun key(title: String): String = norm(title).take(90)
}

/** Picks the current video on the watch page from the texts under the player. */
object WatchPick {
    data class Node(val text: String, val top: Int, val bottom: Int = top, val clickable: Boolean = false)

    /** Button labels and counters of the watch page (whole short strings). */
    private val BUTTON = Regex(
        """(?iuU)^(subscribe|subscribed|подписаться|вы подписаны|share|поделиться|download|скачать|save|сохранить|remix|ремикс|clip|клип|thanks|спасибо|join|спонсировать|like|dislike|нравится|не нравится|comments?|комментари\w*|\.{3}\s*more|ещё|еще|далее|reply|ответить|live chat|чат|ad|реклама|more|другие|playlist|плейлист)$"""
    )
    private val COUNTS = Regex("""(?iuU)(\d.*(views?|просмотр\w*|subscribers?|подписчик\w*|comments?|коммент\w*)|\d.*\s(ago|назад)$)""")

    private fun isMeta(s: String) = (s.length <= 30 && BUTTON.matches(s)) || (s.length <= 70 && COUNTS.containsMatchIn(s)) ||
        Regex("""^[\d\s.,:KMBкмлнтыс]+$""").matches(s)

    /** Title: the first long, non-metadata text below the player. */
    fun title(nodes: List<Node>, playerBottom: Int): String? = nodes
        .filter { it.top >= playerBottom - 8 && it.text.trim().length >= 6 }
        .sortedBy { it.top }
        .firstOrNull { !isMeta(it.text.trim()) }
        ?.text?.trim()

    /** Channel: the text right before a "N subscribers" line. */
    fun channel(nodes: List<Node>, playerBottom: Int): String? {
        val below = nodes.filter { it.top >= playerBottom - 8 }.sortedBy { it.top }
        val i = below.indexOfFirst { Regex("""(?iuU)(subscribers?|подписчик)""").containsMatchIn(it.text) }
        if (i <= 0) return null
        return below.subList(0, i).lastOrNull { it.text.trim().length >= 2 && !isMeta(it.text.trim()) }?.text?.trim()
    }
}

/** Matches a feed title with search results. */
object FeedMatch {
    private fun tokens(s: String) = FeedParse.norm(s).split(' ').filter { it.length > 1 }.toSet()

    /** 0..1: share of common words (Jaccard), with a bonus when one title contains the other. */
    fun similarity(a: String, b: String): Double {
        val na = FeedParse.norm(a)
        val nb = FeedParse.norm(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val j = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        val contains = (na.length >= 12 && nb.contains(na)) || (nb.length >= 12 && na.contains(nb))
        return if (contains) maxOf(j, 0.85) else j
    }

    data class Cand(val title: String, val channel: String, val url: String)

    /** Best candidate for a card, or null when nothing is close enough. */
    fun best(title: String, channel: String, cands: List<Cand>): Cand? {
        val ch = FeedParse.norm(channel)
        return cands.map { c ->
            val s = similarity(title, c.title) * 0.8 + (if (ch.isNotEmpty() && FeedParse.norm(c.channel) == ch) 0.2 else if (ch.isEmpty()) 0.1 else 0.0)
            c to s
        }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }
}

/** Videos seen in the YouTube feed during a session (cleared by the user). */
class FeedSession(var startedAt: Long = 0L) {
    class Seen(var item: FeedItem, var count: Int, val first: Long, var last: Long)

    val items = LinkedHashMap<String, Seen>()

    /** Adds what is on screen now; returns how many videos are new. */
    fun add(list: List<FeedItem>, now: Long): Int {
        if (startedAt == 0L) startedAt = now
        var fresh = 0
        for (it in list) {
            val k = it.key
            if (k.isEmpty()) continue
            val s = items[k]
            if (s == null) {
                items[k] = Seen(it, 1, now, now)
                fresh++
            } else {
                // a screen seen again within a few seconds is the same sighting (scrolling back and forth)
                if (now - s.last > 30_000) s.count++
                s.last = now
                s.item = merge(s.item, it)
            }
        }
        while (items.size > MAX) items.remove(items.keys.first())
        return fresh
    }

    fun setUrl(key: String, url: String) {
        items[key]?.let { it.item = it.item.copy(url = url) }
    }

    fun list(): List<FeedItem> = items.values.map { it.item }

    data class Stats(val videos: Int, val channels: Int, val shorts: Int, val live: Int, val medianViews: Long, val repeated: Int)

    fun stats(): Stats {
        val l = items.values
        val v = l.map { it.item.views }.filter { it >= 0 }.sorted()
        return Stats(
            l.size, l.map { FeedParse.norm(it.item.channel) }.filter { it.isNotEmpty() }.toSet().size,
            l.count { it.item.short }, l.count { it.item.live }, if (v.isEmpty()) -1 else v[v.size / 2], l.count { it.count > 1 }
        )
    }

    fun topChannels(n: Int = 8): List<Pair<String, Int>> = items.values.map { it.item.channel }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    /** Data block for the AI: one line per video. */
    fun toPrompt(max: Int = 200): String = buildString {
        val st = stats()
        append("Videos: ${st.videos}, channels: ${st.channels}, shorts: ${st.shorts}, live: ${st.live}, shown again: ${st.repeated}\n")
        items.values.take(max).forEach { s ->
            val i = s.item
            append("- ").append(i.title.take(140)).append(" | ").append(i.channel.ifBlank { "?" }).append(" | ")
                .append(if (i.views >= 0) i.views.toString() else "?").append(" views | ").append(i.age.ifBlank { "?" })
            if (i.short) append(" | short")
            if (i.live) append(" | live")
            if (s.count > 1) append(" | shown ${s.count}×")
            append('\n')
        }
    }

    fun toJson(): JSONObject = JSONObject().put("startedAt", startedAt).put("items", JSONArray().also { a ->
        items.values.forEach { a.put(it.item.toJson().put("count", it.count).put("first", it.first).put("last", it.last)) }
    })

    companion object {
        const val MAX = 600

        fun merge(old: FeedItem, new: FeedItem) = old.copy(
            channel = old.channel.ifBlank { new.channel },
            views = if (new.views >= 0) new.views else old.views,
            age = old.age.ifBlank { new.age },
            duration = old.duration.ifBlank { new.duration },
            short = old.short || new.short,
            live = new.live,
            url = old.url.ifBlank { new.url }
        )

        fun from(o: JSONObject?): FeedSession {
            val s = FeedSession(o?.optLong("startedAt") ?: 0L)
            val a = o?.optJSONArray("items") ?: return s
            for (i in 0 until a.length()) {
                val j = a.optJSONObject(i) ?: continue
                val it = FeedItem.from(j)
                if (it.key.isEmpty()) continue
                s.items[it.key] = Seen(it, j.optInt("count", 1), j.optLong("first"), j.optLong("last"))
            }
            return s
        }
    }
}

/** One link waiting for a full analysis. mode: "" full, "pains", "style". status: wait | running | done | error. */
data class QItem(
    val url: String,
    val title: String = "",
    val mode: String = "",
    var status: String = "wait",
    var pid: String = "",
    val addedAt: Long = 0L,
    var note: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).put("mode", mode).put("status", status)
        .put("pid", pid).put("addedAt", addedAt).put("note", note)

    companion object {
        fun from(o: JSONObject) = QItem(
            o.optString("url"), o.optString("title"), o.optString("mode"), o.optString("status", "wait"),
            o.optString("pid"), o.optLong("addedAt"), o.optString("note")
        )
    }
}

/** Queue rules: no duplicates of waiting links, first in — first out, a stuck "running" item can be retried. */
object QueueLogic {
    const val MAX = 300

    fun add(list: MutableList<QItem>, item: QItem): Boolean {
        if (item.url.isBlank()) return false
        if (list.any { it.url == item.url && it.mode == item.mode && (it.status == "wait" || it.status == "running") }) return false
        list.add(item)
        // keep the newest finished ones when the list grows
        while (list.size > MAX) {
            val i = list.indexOfFirst { it.status == "done" || it.status == "error" }
            if (i < 0) break
            list.removeAt(i)
        }
        return true
    }

    fun next(list: List<QItem>): QItem? = list.firstOrNull { it.status == "wait" }

    /** After a restart nothing is running: "running" items go back to the queue. */
    fun recover(list: List<QItem>) = list.filter { it.status == "running" }.forEach { it.status = "wait" }

    fun counts(list: List<QItem>): Map<String, Int> = list.groupingBy { it.status }.eachCount()

    fun toJson(list: List<QItem>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

    fun fromJson(s: String?): MutableList<QItem> {
        val a = try {
            JSONArray(s ?: return mutableListOf())
        } catch (e: Exception) {
            return mutableListOf()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(QItem::from) }.filter { it.url.isNotBlank() }.toMutableList()
    }
}

/** Bubble geometry: it sticks to the nearest side edge and stays on screen. */
object LensPos {
    fun snapX(x: Int, size: Int, screenW: Int, margin: Int): Int = if (x + size / 2 < screenW / 2) margin else screenW - size - margin

    fun clampY(y: Int, size: Int, screenH: Int, margin: Int): Int = y.coerceIn(margin, maxOf(margin, screenH - size - margin))

    /** A finger that moved less than [slop] px is a tap, not a drag. */
    fun isTap(dx: Float, dy: Float, slop: Int): Boolean = dx * dx + dy * dy <= slop.toFloat() * slop
}

/** Apps the bubble shows over. */
object LensApps {
    val YOUTUBE = setOf(
        "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music", "com.vanced.android.youtube"
    )

    /** Windows that never hide the bubble: the system UI, keyboards and Lumen itself. */
    fun isTransient(pkg: String, own: String): Boolean = pkg == own || pkg == "com.android.systemui" ||
        pkg.contains("inputmethod") || pkg.contains(".keyboard") || pkg == "com.google.android.googlequicksearchbox"
}

val LENS_PROMPTS = linkedMapOf(
    "lens_feed" to (
        "Ниже лента YouTube, которую пользователь увидел за сессию: название | канал | просмотры | давность | формат.\n" +
            "Ответ на языке {lang}, в Markdown, кратко и по делу:\n" +
            "1) 5-8 тем, которые продвигает лента, с примерной долей;\n" +
            "2) кто доминирует: каналы, форматы (Shorts, эфиры, длинные), свежесть и популярность;\n" +
            "3) кликбейт и эмоциональные крючки в названиях — 3-5 примеров;\n" +
            "4) что алгоритм, похоже, думает об интересах зрителя;\n" +
            "5) 3 видео, которые стоит открыть, и 3, которые можно пропустить, — с причиной;\n" +
            "6) идеи для автора: какие темы и форматы сейчас в ленте.\n" +
            "Опирайся только на данные, не выдумывай цифры.\n\n{data}"
        ),
    "lens_screen" to (
        "Пользователь листает YouTube. Ниже видео, которые сейчас на экране: название | канал | просмотры | давность.\n" +
            "Ответ на языке {lang}, очень кратко (до 12 строк): о чём эти видео, что общего, какое выглядит самым полезным и почему, " +
            "где кликбейт. Не выдумывай.\n\n{data}"
        )
)

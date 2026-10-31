/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** A channel or group the bot posts to. [chat] is "@username" or a numeric id ("-100…"). */
data class PostTarget(val chat: String, var title: String, var type: String = "") {
    fun toJson(): JSONObject = JSONObject().put("chat", chat).put("title", title).put("type", type)
    val label: String get() = title.ifBlank { chat }

    companion object {
        fun from(o: JSONObject) = PostTarget(o.optString("chat"), o.optString("title"), o.optString("type"))
    }
}

/** A ready post: Telegram HTML text, optional picture and one link button. */
data class BotPost(
    val id: String,
    var text: String,
    var photo: String = "",
    var buttonText: String = "",
    var buttonUrl: String = "",
    var targets: List<String> = emptyList(),
    var at: Long = 0,
    var source: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("photo", photo)
        .put("buttonText", buttonText).put("buttonUrl", buttonUrl).put("targets", JSONArray(targets)).put("at", at).put("source", source)

    companion object {
        fun from(o: JSONObject) = BotPost(
            o.optString("id"), o.optString("text"), o.optString("photo"), o.optString("buttonText"), o.optString("buttonUrl"),
            PostCore.strings(o.optJSONArray("targets")), o.optLong("at"), o.optString("source")
        )

        fun newId(): String = "p" + System.currentTimeMillis().toString(36) + (100..999).random()
    }
}

/** Where a content plan takes material: an RSS/Atom feed, a YouTube search or an AI topic. */
data class PostSource(val type: String, val value: String) {
    companion object {
        const val RSS = "rss"
        const val YOUTUBE = "yt"
        const val AI = "ai"
    }
}

/** Auto-filling of channels: every [everyMin] minutes takes up to [perRun] new items and publishes them (or sends them for review). */
data class PostPlan(
    val id: String,
    var name: String,
    var sources: List<PostSource> = emptyList(),
    var targets: List<String> = emptyList(),
    var everyMin: Int = 120,
    var perRun: Int = 1,
    var fromHour: Int = 8,
    var toHour: Int = 23,
    var rewrite: Boolean = true,
    var style: String = "",
    var review: Boolean = false,
    var withImage: Boolean = true,
    var withButton: Boolean = true,
    var enabled: Boolean = true,
    var lastRun: Long = 0,
    var seen: List<String> = emptyList(),
    var recent: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("sources", JSONArray(sources.map { JSONObject().put("type", it.type).put("value", it.value) }))
        .put("targets", JSONArray(targets)).put("everyMin", everyMin).put("perRun", perRun).put("fromHour", fromHour).put("toHour", toHour)
        .put("rewrite", rewrite).put("style", style).put("review", review).put("withImage", withImage).put("withButton", withButton)
        .put("enabled", enabled).put("lastRun", lastRun).put("seen", JSONArray(seen)).put("recent", JSONArray(recent))

    companion object {
        fun from(o: JSONObject): PostPlan {
            val src = o.optJSONArray("sources") ?: JSONArray()
            return PostPlan(
                o.optString("id"), o.optString("name"),
                (0 until src.length()).mapNotNull { i -> src.optJSONObject(i)?.let { PostSource(it.optString("type"), it.optString("value")) } },
                PostCore.strings(o.optJSONArray("targets")), o.optInt("everyMin", 120), o.optInt("perRun", 1),
                o.optInt("fromHour", 8), o.optInt("toHour", 23), o.optBoolean("rewrite", true), o.optString("style"),
                o.optBoolean("review"), o.optBoolean("withImage", true), o.optBoolean("withButton", true), o.optBoolean("enabled", true),
                o.optLong("lastRun"), PostCore.strings(o.optJSONArray("seen")), PostCore.strings(o.optJSONArray("recent"))
            )
        }

        fun newId(): String = "pl" + System.currentTimeMillis().toString(36) + (100..999).random()
    }
}

/** One publication attempt. */
data class PostLog(val at: Long, val chat: String, val text: String, val ok: Boolean, val info: String) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("chat", chat).put("text", text).put("ok", ok).put("info", info)

    companion object {
        fun from(o: JSONObject) = PostLog(o.optLong("at"), o.optString("chat"), o.optString("text"), o.optBoolean("ok"), o.optString("info"))
    }
}

/** Everything the "Posts" tab keeps (files/lumen/bots/<id>/posts.json). */
class PostsData(
    val targets: MutableList<PostTarget> = ArrayList(),
    val plans: MutableList<PostPlan> = ArrayList(),
    val queue: MutableList<BotPost> = ArrayList(),
    val drafts: MutableList<BotPost> = ArrayList(),
    val history: MutableList<PostLog> = ArrayList()
) {
    fun target(chat: String) = targets.firstOrNull { PostCore.sameChat(it.chat, chat) }

    fun addHistory(e: PostLog) {
        history.add(0, e)
        while (history.size > 200) history.removeAt(history.lastIndex)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("targets", JSONArray(targets.map { it.toJson() }))
        .put("plans", JSONArray(plans.map { it.toJson() }))
        .put("queue", JSONArray(queue.map { it.toJson() }))
        .put("drafts", JSONArray(drafts.map { it.toJson() }))
        .put("history", JSONArray(history.map { it.toJson() }))

    companion object {
        private fun <T> list(a: JSONArray?, f: (JSONObject) -> T): MutableList<T> =
            if (a == null) ArrayList() else (0 until a.length()).mapNotNullTo(ArrayList()) { i -> a.optJSONObject(i)?.let(f) }

        fun from(o: JSONObject) = PostsData(
            list(o.optJSONArray("targets")) { PostTarget.from(it) }, list(o.optJSONArray("plans")) { PostPlan.from(it) },
            list(o.optJSONArray("queue")) { BotPost.from(it) }, list(o.optJSONArray("drafts")) { BotPost.from(it) }, list(o.optJSONArray("history")) { PostLog.from(it) }
        )
    }
}

/** One item of a feed or a search. */
data class FeedItem(val key: String, val title: String, val link: String, val summary: String, val image: String = "")

/** Pure logic of posting to channels and groups (tested in BotPostsTest). */
object PostCore {
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    private val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")

    /** "@name", "name", "https://t.me/name", "t.me/c/123/4", "-100123" → chat_id for the Bot API; null when it cannot be one. */
    fun parseChat(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (Regex("^-?\\d{5,20}$").matches(s)) return s
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (s.startsWith("t.me/") || s.startsWith("telegram.me/")) {
            val path = s.substringAfter('/').split('/')
            if (path.isEmpty() || path[0].startsWith("+") || path[0] == "joinchat") return null
            if (path[0] == "c" && path.size > 1 && path[1].all { it.isDigit() } && path[1].isNotEmpty()) return "-100" + path[1]
            s = path[0]
        }
        s = s.removePrefix("@").substringBefore('?')
        return if (USERNAME.matches(s)) "@$s" else null
    }

    fun sameChat(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** Chats from my_chat_member updates where the bot became a member/admin: (chat id, title, type). Left chats come with type "left". */
    fun chatsFromUpdates(body: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val a = try {
            JSONObject(body).optJSONArray("result")
        } catch (e: Exception) {
            null
        } ?: return out
        for (i in 0 until a.length()) {
            val u = a.optJSONObject(i) ?: continue
            val m = u.optJSONObject("my_chat_member")
            val chat = m?.optJSONObject("chat") ?: u.optJSONObject("message")?.optJSONObject("chat") ?: continue
            val type = chat.optString("type")
            if (type == "private") continue
            val status = m?.optJSONObject("new_chat_member")?.optString("status").orEmpty()
            val title = chat.optString("title").ifBlank { chat.optString("username") }
            val gone = status == "left" || status == "kicked"
            out.add(Triple(chat.optLong("id").toString(), title, if (gone) "left" else type))
        }
        return out
    }

    // ---------------- sources ----------------

    /** One source per line: an http(s) feed URL, "yt: query" or "ai: topic". */
    fun parseSources(text: String): List<PostSource> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { l ->
        val low = l.lowercase()
        when {
            low.startsWith("yt:") || low.startsWith("youtube:") -> PostSource(PostSource.YOUTUBE, l.substringAfter(':').trim()).takeIf { it.value.isNotEmpty() }
            low.startsWith("ai:") || low.startsWith("ии:") -> PostSource(PostSource.AI, l.substringAfter(':').trim()).takeIf { it.value.isNotEmpty() }
            low.startsWith("http://") || low.startsWith("https://") -> PostSource(PostSource.RSS, l)
            else -> null
        }
    }

    fun sourcesText(list: List<PostSource>): String = list.joinToString("\n") { s ->
        when (s.type) {
            PostSource.YOUTUBE -> "yt: " + s.value
            PostSource.AI -> "ai: " + s.value
            else -> s.value
        }
    }

    // ---------------- feeds ----------------

    private val ITEM = Regex("<(item|entry)(\\s[^>]*)?>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)

    private fun tag(block: String, name: String): String? =
        Regex("<$name(\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(2)?.let { cdata(it).trim() }

    private fun attr(tagText: String, name: String): String =
        Regex("\\s$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tagText)?.groupValues?.get(1)?.let { entities(it) }.orEmpty()

    private fun cdata(s: String): String = s.replace(Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>"), "$1")

    /** RSS 2.0 and Atom items, newest first as the feed gives them. */
    fun parseFeed(xml: String): List<FeedItem> = ITEM.findAll(xml).mapNotNull { m ->
        val b = m.groupValues[3]
        val title = plain(tag(b, "title").orEmpty())
        var link = tag(b, "link")?.takeIf { it.startsWith("http") }?.let { entities(it) }.orEmpty()
        if (link.isEmpty()) {
            val links = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(b).map { it.value }.toList()
            link = (links.firstOrNull { attr(it, "rel").let { r -> r.isEmpty() || r == "alternate" } } ?: links.firstOrNull())?.let { attr(it, "href") }.orEmpty()
        }
        val rawSummary = tag(b, "content:encoded") ?: tag(b, "description") ?: tag(b, "summary") ?: tag(b, "content") ?: tag(b, "media:description") ?: ""
        val summary = plain(rawSummary)
        val image = Regex("<(enclosure|media:content|media:thumbnail)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(b).map { it.value }
            .firstOrNull { t -> attr(t, "url").isNotEmpty() && (attr(t, "type").let { it.isEmpty() || it.startsWith("image") }) }?.let { attr(it, "url") }
            ?: Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).find(entities(cdata(rawSummary)))?.value?.let { attr(it, "src") }.orEmpty()
        val key = (tag(b, "guid") ?: tag(b, "id"))?.let { plain(it) }?.ifBlank { null } ?: link.ifBlank { title }
        if (title.isBlank() && summary.isBlank()) null else FeedItem(key.take(300), title, link, summary, image.takeIf { it.startsWith("http") }.orEmpty())
    }.toList()

    /** Items from BotAi.youtube JSON. */
    fun youtubeItems(json: String): List<FeedItem> {
        val a = try {
            JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) return@mapNotNull null
            val sub = listOf(o.optString("channel"), o.optLong("views").takeIf { it > 0 }?.let { "$it views" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            FeedItem(url, o.optString("title"), url, sub, ytThumb(url))
        }
    }

    fun ytThumb(url: String): String {
        val id = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: return ""
        return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }

    /** Items not yet published, oldest of the new ones first (feeds list newest first). */
    fun fresh(items: List<FeedItem>, seen: List<String>, n: Int): List<FeedItem> {
        val s = seen.toHashSet()
        return items.filter { it.key !in s }.distinctBy { it.key }.take(maxOf(n, 0)).reversed()
    }

    fun remember(seen: List<String>, keys: List<String>, keep: Int = 400): List<String> = (seen + keys).distinct().takeLast(keep)

    // ---------------- text ----------------

    fun entities(s: String): String = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    /** HTML/CDATA → plain text with paragraphs. */
    fun plain(html: String): String {
        var s = cdata(html)
        if (s.contains("&lt;")) s = entities(s)
        s = s.replace(Regex("<(script|style)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>|</p>|</div>|</li>|</h\\d>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
        s = entities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Cuts at a sentence or word end near [max]. */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val part = s.take(max)
        val dot = maxOf(part.lastIndexOf(". "), part.lastIndexOf("! "), part.lastIndexOf("? "), part.lastIndexOf('\n'))
        return if (dot > max / 2) part.take(dot + 1).trim() else part.substringBeforeLast(' ').trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    /** A post made from the item as is (no AI). */
    fun rawPost(item: FeedItem, withLink: Boolean): String {
        val sb = StringBuilder()
        if (item.title.isNotBlank()) sb.append("<b>").append(esc(item.title)).append("</b>")
        val sum = cut(item.summary.removePrefix(item.title).trimStart('.', ':', '—', '-', ' ', '\n').trim(), 700)
        if (sum.isNotBlank()) sb.append(if (sb.isEmpty()) "" else "\n\n").append(esc(sum))
        if (withLink && item.link.isNotBlank()) sb.append("\n\n").append("<a href=\"").append(esc(item.link)).append("\">")
            .append(t("Подробнее", "Read more")).append("</a>")
        return sb.toString()
    }

    private val ALLOWED = setOf("b", "strong", "i", "em", "u", "ins", "s", "strike", "del", "a", "code", "pre", "blockquote", "tg-spoiler")

    /** AI answer → Telegram HTML: no code fences or Markdown bold, only tags Telegram accepts, & escaped. */
    fun tgHtml(answer: String): String {
        var s = answer.trim()
        Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```$").find(s)?.let { s = it.groupValues[1].trim() }
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace(Regex("(?m)^#{1,6}\\s+(.+)$"), "<b>$1</b>")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</p>\\s*", RegexOption.IGNORE_CASE), "\n\n")
        val out = StringBuilder()
        var i = 0
        val tagRx = Regex("^</?([a-zA-Z][a-zA-Z0-9-]*)(\\s[^<>]*)?>")
        var droppedLinks = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '<') {
                val m = tagRx.find(s.substring(i, minOf(s.length, i + 500)))
                if (m != null) {
                    val name = m.groupValues[1].lowercase()
                    if (name in ALLOWED) {
                        val closing = m.value.startsWith("</")
                        when {
                            closing && name == "a" && droppedLinks > 0 -> droppedLinks--
                            closing -> out.append("</$name>")
                            name == "a" -> {
                                val href = attr(m.value, "href").takeIf { it.startsWith("http") || it.startsWith("tg:") }
                                if (href == null) droppedLinks++ else out.append("<a href=\"").append(esc(href)).append("\">")
                            }
                            else -> out.append("<$name>")
                        }
                    }
                    i += m.value.length
                    continue
                }
                out.append("&lt;")
            } else if (ch == '>') {
                out.append("&gt;")
            } else if (ch == '&') {
                val ent = Regex("^&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);").find(s.substring(i, minOf(s.length, i + 12)))
                if (ent != null) {
                    out.append(ent.value)
                    i += ent.value.length
                    continue
                }
                out.append("&amp;")
            } else {
                out.append(ch)
            }
            i++
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Plain text of a post (fallback when Telegram rejects the HTML). */
    fun stripTags(html: String): String = entities(html.replace(Regex("<[^>]+>"), ""))

    /** Visible length (Telegram counts text without tags). */
    fun visibleLength(html: String): Int = stripTags(html).length

    // ---------------- time ----------------

    /** The plan should run now: the interval passed and the hour is inside [from, to] (the window may wrap midnight). */
    fun due(p: PostPlan, now: Long, hour: Int): Boolean {
        if (!p.enabled || p.everyMin <= 0 || p.sources.isEmpty() || p.targets.isEmpty()) return false
        if (now - p.lastRun < p.everyMin * 60_000L) return false
        return inWindow(hour, p.fromHour, p.toHour)
    }

    fun inWindow(hour: Int, from: Int, to: Int): Boolean = if (from <= to) hour in from..to else hour >= from || hour <= to

    /** "18:30" (today, or tomorrow if passed), "25.12 09:00", "25.12.2026 09:00", "+30м", "+2ч", "+1д" → ms; null if unclear. */
    fun parseWhen(s: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val x = s.trim().lowercase().replace(',', ' ').replace(Regex("\\s+"), " ")
        Regex("^\\+\\s?(\\d{1,4})\\s?(м|мин|m|min|ч|h|д|d)?$").find(x)?.let { m ->
            val n = m.groupValues[1].toLong()
            val mul = when (m.groupValues[2]) {
                "ч", "h" -> 3_600_000L
                "д", "d" -> 86_400_000L
                else -> 60_000L
            }
            return now + n * mul
        }
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        val m = Regex("^(?:(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))? )?(\\d{1,2})[:.](\\d{2})$").find(x) ?: return null
        val h = m.groupValues[4].toInt()
        val min = m.groupValues[5].toInt()
        if (h > 23 || min > 59) return null
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, min)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (m.groupValues[1].isNotEmpty()) {
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            if (d !in 1..31 || mo !in 1..12) return null
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.MONTH, mo - 1)
            if (y != null) c.set(Calendar.YEAR, y)
            if (d > c.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
            c.set(Calendar.DAY_OF_MONTH, d)
            if (y == null && c.timeInMillis < now - 60_000L) c.add(Calendar.YEAR, 1)
        } else if (c.timeInMillis <= now) {
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    fun everyLabel(min: Int): String = when {
        min <= 0 -> t("вручную", "manual")
        min % 1440 == 0 -> t("раз в ${min / 1440} д", "every ${min / 1440} d")
        min % 60 == 0 -> t("раз в ${min / 60} ч", "every ${min / 60} h")
        else -> t("раз в $min мин", "every $min min")
    }
}

/** Prompts of posts (editable in Settings → prompts). */
val POST_PROMPTS = linkedMapOf(
    "bot_post" to (
        "Ты редактор Telegram-канала. Перепиши материал в готовый пост.\n\n" +
            "Правила:\n- Своими словами, без воды, факты не выдумывай.\n" +
            "- Цепляющий первый абзац; заголовок жирным через <b>…</b>.\n" +
            "- Разметка только Telegram HTML: <b>, <i>, <u>, <s>, <a href=\"…\">, <blockquote>, <code>. Никакого Markdown.\n" +
            "- Длина до {max} символов. Эмодзи умеренно. В конце 1–3 хэштега, если уместно.\n" +
            "- Ссылку на источник не вставляй — её добавят кнопкой.\n" +
            "- Язык поста: {lang}.\n" +
            "{style}\n\nМатериал:\nЗаголовок: {title}\nТекст: {text}\nСсылка: {link}\n\nОтвет — только текст поста."
        ),
    "bot_post_topic" to (
        "Ты автор Telegram-канала. Напиши новый пост на тему ниже.\n\n" +
            "Правила:\n- Полезно и конкретно: факт, совет, разбор, подборка или вопрос к подписчикам.\n" +
            "- Заголовок жирным через <b>…</b>. Разметка только Telegram HTML: <b>, <i>, <u>, <a href=\"…\">, <blockquote>. Никакого Markdown.\n" +
            "- Длина до {max} символов. Эмодзи умеренно. В конце 1–3 хэштега.\n" +
            "- Не повторяй недавние посты:\n{recent}\n" +
            "- Язык поста: {lang}.\n" +
            "{style}\n\nТема: {topic}\n\nОтвет — только текст поста."
        ),
    "bot_post_edit" to (
        "Отредактируй пост для Telegram-канала по просьбе. Разметка только Telegram HTML (<b>, <i>, <u>, <s>, <a href>, <blockquote>), без Markdown.\n\n" +
            "Пост:\n{text}\n\nЧто сделать: {request}\n\nОтвет — только новый текст поста."
        )
)

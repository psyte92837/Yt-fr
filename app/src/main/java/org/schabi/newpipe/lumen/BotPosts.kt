/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Posts of a bot: files/lumen/bots/<id>/posts.json. */
object BotPosts {
    private val lock = Any()

    private fun file(id: String) = File(File(Store.root, "bots"), "$id/posts.json")

    fun load(id: String): PostsData = synchronized(lock) {
        val f = file(id)
        if (!f.isFile) PostsData() else try {
            PostsData.from(JSONObject(f.readText()))
        } catch (e: Exception) {
            PostsData()
        }
    }

    fun <T> update(id: String, change: (PostsData) -> T): T = synchronized(lock) {
        val d = load(id)
        val r = change(d)
        val f = file(id)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(d.toJson().toString())
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
        r
    }

    /** Remembers groups/channels the bot was added to; removes those it left. Returns the new ones. */
    fun noteChats(id: String, chats: List<Triple<String, String, String>>): List<PostTarget> {
        if (chats.isEmpty()) return emptyList()
        return update(id) { d ->
            val added = ArrayList<PostTarget>()
            for ((chat, title, type) in chats) {
                val have = d.target(chat)
                if (type == "left") {
                    if (have != null) d.targets.remove(have)
                } else if (have == null) {
                    PostTarget(chat, title, type).also {
                        d.targets.add(it)
                        added.add(it)
                    }
                } else if (title.isNotBlank()) {
                    have.title = title
                    if (type.isNotBlank()) have.type = type
                }
            }
            added
        }
    }
}

/** Publishing and auto-filling (runs from the bot worker and from the Posts tab). */
object PostEngine {
    const val TASK_POST = "bot_post"
    const val TASK_TOPIC = "bot_post_topic"
    const val TASK_EDIT = "bot_post_edit"
    private const val MAX = 900

    fun api(botId: String): TgApi = TgApi(Bots.token(botId) ?: throw IllegalStateException(t("Нет токена бота", "No bot token")))

    fun botUserId(botId: String): Long = Bots.token(botId)?.substringBefore(':')?.toLongOrNull() ?: 0L

    /** Sends [post] to all its targets; results go to the history. Returns (ok, failed). */
    fun publish(botId: String, a: TgApi, post: BotPost): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        val logs = ArrayList<PostLog>()
        for (chat in post.targets) {
            try {
                val id = a.post(chat, post.text, post.photo, post.buttonText, post.buttonUrl)
                ok++
                logs.add(PostLog(System.currentTimeMillis(), chat, post.text.take(300), true, "#$id"))
            } catch (e: Exception) {
                bad++
                val msg = (e as? TgError)?.description ?: e.message ?: e.javaClass.simpleName
                logs.add(PostLog(System.currentTimeMillis(), chat, post.text.take(300), false, hint(msg)))
            }
        }
        BotPosts.update(botId) { d -> logs.forEach { d.addHistory(it) } }
        Bots.log(botId, BotLogEntry.OUT, 0, t("посты", "posts"), t("Публикация: ", "Posted: ") + "$ok/${post.targets.size}" + " · " + PostCore.stripTags(post.text).take(80))
        return ok to bad
    }

    /** Telegram's terse errors in plain words. */
    fun hint(msg: String): String = when {
        msg.contains("not enough rights", true) || msg.contains("need administrator", true) ->
            t("У бота нет прав на публикацию: сделайте его админом с правом «Публикация сообщений»", "The bot cannot post: make it an admin with “Post messages”") + " ($msg)"
        msg.contains("chat not found", true) -> t("Чат не найден: бот не добавлен или неверный адрес", "Chat not found: the bot is not added or the address is wrong") + " ($msg)"
        msg.contains("kicked", true) -> t("Бота удалили из чата", "The bot was removed from the chat") + " ($msg)"
        msg.contains("wrong file", true) || msg.contains("failed to get http url", true) -> t("Telegram не смог загрузить картинку", "Telegram could not load the picture") + " ($msg)"
        else -> msg
    }

    private fun provider(): Provider = Provider.active() ?: throw IllegalStateException(t("AI не подключён: Настройки → AI", "AI is not connected: Settings → AI"))

    private fun ai(botId: String, task: String, prompt: String): String {
        val p = provider()
        val sys = "You are a professional Telegram channel editor. Output only the post text in Telegram HTML."
        return PostCore.tgHtml(Llm.complete(p, p.fastModel.ifBlank { p.smartModel }, sys, prompt, maxTokens = 1500, task = task, pid = "bot:$botId").text)
    }

    private fun style(s: String) = if (s.isBlank()) "" else "- Особые требования: $s"

    fun rewrite(botId: String, item: FeedItem, style: String): String = ai(
        botId, TASK_POST, Prompts.fill(
            TASK_POST,
            mapOf("max" to MAX.toString(), "lang" to BotAi.lang(), "style" to style(style), "title" to item.title, "text" to PostCore.cut(item.summary, 4000), "link" to item.link)
        )
    )

    fun onTopic(botId: String, topic: String, style: String, recent: List<String>): String = ai(
        botId, TASK_TOPIC, Prompts.fill(
            TASK_TOPIC,
            mapOf(
                "max" to MAX.toString(), "lang" to BotAi.lang(), "style" to style(style), "topic" to topic,
                "recent" to recent.takeLast(10).joinToString("\n") { "  • " + it }.ifBlank { "  —" }
            )
        )
    )

    fun edit(botId: String, text: String, request: String): String =
        ai(botId, TASK_EDIT, Prompts.fill(TASK_EDIT, mapOf("text" to text, "request" to request)))

    /** Items of a feed or a YouTube search. */
    fun fetch(s: PostSource): List<FeedItem> = when (s.type) {
        PostSource.RSS -> {
            val r = JSONObject(BotAi.http("GET", s.value, "", "{\"Accept\":\"application/rss+xml, application/atom+xml, text/xml, */*\"}"))
            val st = r.optInt("status")
            if (st !in 200..299) throw IllegalStateException("HTTP $st: ${s.value}")
            PostCore.parseFeed(r.optString("text")).also { if (it.isEmpty()) throw IllegalStateException(t("В ленте нет записей (это точно RSS?): ", "No items in the feed (is it RSS?): ") + s.value) }
        }
        PostSource.YOUTUBE -> PostCore.youtubeItems(BotAi.youtube(s.value, 15))
        else -> emptyList()
    }

    /** Makes up to perRun posts from the plan's sources; publishes them or puts them for review. Returns a short report. */
    fun runPlan(ctx: Context?, botId: String, planId: String, a: TgApi?): String {
        val plan = BotPosts.update(botId) { d -> d.plans.firstOrNull { it.id == planId }?.also { it.lastRun = System.currentTimeMillis() } }
            ?: return t("План не найден", "Plan not found")
        val errors = ArrayList<String>()
        val made = ArrayList<Pair<String, BotPost>>() // key → post
        var budget = plan.perRun.coerceIn(1, 10)
        val seen = plan.seen.toMutableList()
        val recent = plan.recent.toMutableList()
        for (src in plan.sources.shuffled()) {
            if (budget <= 0) break
            try {
                if (src.type == PostSource.AI) {
                    val text = onTopic(botId, src.value, plan.style, recent)
                    if (text.isNotBlank()) {
                        made.add("" to BotPost(BotPost.newId(), text, targets = plan.targets, source = plan.name))
                        recent.add(PostCore.stripTags(text).lines().firstOrNull().orEmpty().take(100))
                        budget--
                    }
                    continue
                }
                for (item in PostCore.fresh(fetch(src), seen, budget)) {
                    val text = if (plan.rewrite) rewrite(botId, item, plan.style) else PostCore.rawPost(item, !plan.withButton)
                    seen.add(item.key)
                    if (text.isBlank()) continue
                    val btn = plan.withButton && item.link.startsWith("http")
                    made.add(
                        item.key to BotPost(
                            BotPost.newId(), text, if (plan.withImage) item.image else "",
                            if (btn) (if (src.type == PostSource.YOUTUBE) t("▶ Смотреть", "▶ Watch") else t("Читать полностью", "Read more")) else "",
                            if (btn) item.link else "", plan.targets, source = plan.name
                        )
                    )
                    budget--
                }
            } catch (e: Exception) {
                errors.add(e.message ?: e.javaClass.simpleName)
            }
        }
        var published = 0
        if (!plan.review && a != null) {
            for ((_, p) in made) if (publish(botId, a, p).first > 0) published++
        }
        BotPosts.update(botId) { d ->
            d.plans.firstOrNull { it.id == planId }?.let {
                it.seen = PostCore.remember(it.seen, seen.drop(plan.seen.size))
                it.recent = recent.takeLast(20)
            }
            if (plan.review || a == null) made.forEach { d.drafts.add(it.second) }
            errors.forEach { d.addHistory(PostLog(System.currentTimeMillis(), "", plan.name, false, it)) }
        }
        errors.forEach { Bots.log(botId, BotLogEntry.ERR, 0, t("посты", "posts"), plan.name + ": " + it) }
        if (ctx != null && made.isNotEmpty() && (plan.review || a == null)) {
            BotManager.event(ctx, botId, "📝 " + plan.name, t("Постов на проверку: ${made.size}", "Posts to review: ${made.size}"))
        }
        return when {
            made.isEmpty() && errors.isEmpty() -> t("Нового материала нет", "Nothing new")
            plan.review || a == null -> t("На проверку: ${made.size}", "To review: ${made.size}")
            else -> t("Опубликовано: $published", "Published: $published")
        } + if (errors.isNotEmpty()) " · " + t("ошибки: ", "errors: ") + errors.first() else ""
    }

    /** Called by the running bot on every loop: scheduled posts and due plans. */
    fun tick(ctx: Context, botId: String, a: TgApi) {
        val now = System.currentTimeMillis()
        val d = BotPosts.load(botId)
        val due = d.queue.filter { it.at <= now }
        if (due.isNotEmpty()) {
            BotPosts.update(botId) { x -> x.queue.removeAll { q -> due.any { it.id == q.id } } }
            due.forEach { publish(botId, a, it) }
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        d.plans.filter { PostCore.due(it, now, hour) }.forEach { p ->
            try {
                runPlan(ctx, botId, p.id, a)
            } catch (e: Exception) {
                Bots.log(botId, BotLogEntry.ERR, 0, t("посты", "posts"), p.name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}

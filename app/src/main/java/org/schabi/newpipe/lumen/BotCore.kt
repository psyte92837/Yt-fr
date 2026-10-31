/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Calendar
import java.util.TimeZone

/*
 * "My Telegram bots": pure logic (no Android), covered by BotCoreTest.
 * The phone is the bot server: it polls Telegram (getUpdates) and runs the bot's JavaScript (BotJs.kt).
 */

/** Who wrote to the bot. */
data class TgUser(val id: Long, val name: String, val username: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("first_name", name).put("username", username)

    companion object {
        fun from(o: JSONObject?): TgUser {
            if (o == null) return TgUser(0, "")
            val name = listOf(o.optString("first_name"), o.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
            return TgUser(o.optLong("id"), name.ifBlank { o.optString("username") }, o.optString("username"))
        }
    }
}

/** One Telegram update reduced to what a bot needs: a message or an inline-button press. */
data class TgUpdate(
    val updateId: Long,
    val kind: String, // "message" | "callback"
    val chatId: Long,
    val chatType: String,
    val user: TgUser,
    val text: String,
    val messageId: Long,
    val callbackId: String = "",
    val data: String = "",
    val photo: Boolean = false,
    val command: String = "",
    val args: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("updateId", updateId).put("kind", kind).put("chatId", chatId).put("chatType", chatType)
        .put("user", user.toJson()).put("text", text).put("messageId", messageId)
        .put("callbackId", callbackId).put("data", data).put("photo", photo)
        .put("command", command).put("args", args)
}

class TgError(val code: Int, val description: String, val retryAfter: Int = 0) : java.io.IOException("Telegram $code: $description")

object Tg {
    /** Telegram bot token as given by @BotFather: "123456789:AA…". */
    private val TOKEN = Regex("""^\d{5,12}:[A-Za-z0-9_-]{30,}$""")

    fun tokenOk(s: String) = TOKEN.matches(s.trim())

    /** Token hidden for logs and screens: "123456789:AA…xyz". */
    fun mask(token: String): String {
        val t = token.trim()
        val i = t.indexOf(':')
        return if (i < 0 || t.length < i + 6) "…" else t.substring(0, i + 3) + "…" + t.takeLast(3)
    }

    class Batch(val updates: List<TgUpdate>, val lastId: Long)

    /** getUpdates answer → updates the bot understands; [lastId] covers skipped kinds too (the offset must move past them). */
    fun parseUpdates(body: String, botUsername: String = ""): Batch {
        val o = JSONObject(body)
        if (!o.optBoolean("ok")) throw error(body)
        val a = o.optJSONArray("result") ?: JSONArray()
        var last = -1L
        val out = ArrayList<TgUpdate>()
        for (i in 0 until a.length()) {
            val u = a.optJSONObject(i) ?: continue
            val id = u.optLong("update_id", -1)
            if (id > last) last = id
            parseUpdate(u, botUsername)?.let { out.add(it) }
        }
        return Batch(out, last)
    }

    fun parseUpdate(u: JSONObject, botUsername: String = ""): TgUpdate? {
        val id = u.optLong("update_id", -1)
        val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
        if (m != null) {
            val chat = m.optJSONObject("chat") ?: return null
            val text = m.optString("text").ifEmpty { m.optString("caption") }
            val cmd = command(text, botUsername)
            return TgUpdate(
                id, "message", chat.optLong("id"), chat.optString("type", "private"), TgUser.from(m.optJSONObject("from")),
                text, m.optLong("message_id"), photo = m.has("photo"),
                command = cmd?.first.orEmpty(), args = cmd?.second.orEmpty()
            )
        }
        val q = u.optJSONObject("callback_query") ?: return null
        val msg = q.optJSONObject("message")
        val chat = msg?.optJSONObject("chat")
        val from = TgUser.from(q.optJSONObject("from"))
        return TgUpdate(
            id, "callback", chat?.optLong("id") ?: from.id, chat?.optString("type", "private") ?: "private", from,
            msg?.optString("text").orEmpty(), msg?.optLong("message_id") ?: 0, callbackId = q.optString("id"), data = q.optString("data")
        )
    }

    /**
     * "/start@MyBot  a b" → ("start", "a b"). A command addressed to another bot (in a group) is not ours: null.
     */
    fun command(text: String, botUsername: String = ""): Pair<String, String>? {
        val s = text.trim()
        if (!s.startsWith("/") || s.length < 2) return null
        val head = s.substring(1).substringBefore(' ').substringBefore('\n')
        val name = head.substringBefore('@').lowercase()
        val to = head.substringAfter('@', "")
        if (to.isNotEmpty() && botUsername.isNotEmpty() && !to.equals(botUsername, ignoreCase = true)) return null
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        return name to s.substring(1 + head.length).trim()
    }

    fun error(body: String): TgError = try {
        val o = JSONObject(body)
        TgError(o.optInt("error_code", 0), o.optString("description", body.take(200)), o.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0)
    } catch (e: Exception) {
        TgError(0, body.take(200))
    }

    /** Splits a long answer into Telegram-sized parts (4096), preferring line, then word boundaries. */
    fun split(text: String, max: Int = 4096): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf('\n', max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /**
     * sendMessage parameters from the bot's options:
     * {buttons:[["A", {text,data}, {text,url}]], keyboard:[["A","B"]], remove:true, html:true, markdown:true, silent:true, preview:false}.
     * Telegram-native "reply_markup" and "parse_mode" pass through.
     */
    fun sendParams(chatId: Long, text: String, opts: JSONObject): JSONObject {
        val p = JSONObject().put("chat_id", chatId).put("text", text)
        applyOpts(p, opts)
        return p
    }

    fun applyOpts(p: JSONObject, opts: JSONObject) {
        markup(opts)?.let { p.put("reply_markup", it) }
        when {
            opts.has("parse_mode") -> p.put("parse_mode", opts.optString("parse_mode"))
            opts.optBoolean("html") -> p.put("parse_mode", "HTML")
            opts.optBoolean("markdown") -> p.put("parse_mode", "Markdown")
        }
        if (opts.optBoolean("silent")) p.put("disable_notification", true)
        if (opts.has("preview") && !opts.optBoolean("preview", true)) p.put("link_preview_options", JSONObject().put("is_disabled", true))
    }

    fun markup(opts: JSONObject): JSONObject? {
        opts.optJSONObject("reply_markup")?.let { return it }
        opts.optJSONArray("buttons")?.let { rows ->
            val kb = JSONArray()
            rowsOf(rows).forEach { row ->
                val r = JSONArray()
                row.forEach { b ->
                    val btn = JSONObject()
                    when (b) {
                        is JSONObject -> {
                            val label = b.optString("text", b.optString("data", "?"))
                            btn.put("text", label)
                            if (b.optString("url").isNotBlank()) btn.put("url", b.optString("url"))
                            else btn.put("callback_data", b.optString("data", label).take(64))
                        }
                        else -> btn.put("text", b.toString()).put("callback_data", b.toString().take(64))
                    }
                    r.put(btn)
                }
                if (r.length() > 0) kb.put(r)
            }
            return JSONObject().put("inline_keyboard", kb)
        }
        opts.optJSONArray("keyboard")?.let { rows ->
            val kb = JSONArray()
            rowsOf(rows).forEach { row ->
                val r = JSONArray()
                row.forEach { b -> r.put(JSONObject().put("text", if (b is JSONObject) b.optString("text") else b.toString())) }
                if (r.length() > 0) kb.put(r)
            }
            return JSONObject().put("keyboard", kb).put("resize_keyboard", true).put("one_time_keyboard", opts.optBoolean("once"))
        }
        if (opts.optBoolean("remove")) return JSONObject().put("remove_keyboard", true)
        return null
    }

    /** Accepts [[a,b],[c]] and also a flat [a,b,c] (one button per row). */
    private fun rowsOf(a: JSONArray): List<List<Any>> = (0 until a.length()).map { i ->
        when (val v = a.get(i)) {
            is JSONArray -> (0 until v.length()).map { v.get(it) }
            else -> listOf(v)
        }
    }

    /** setMyCommands list: valid names only, description required by Telegram. */
    fun commandsJson(cmds: List<Pair<String, String>>): JSONArray {
        val a = JSONArray()
        val seen = HashSet<String>()
        for ((name, desc) in cmds) {
            val n = name.lowercase().removePrefix("/")
            if (!Regex("^[a-z0-9_]{1,32}$").matches(n) || !seen.add(n)) continue
            a.put(JSONObject().put("command", n).put("description", desc.ifBlank { n }.take(256)))
        }
        return a
    }
}

/** Bot settings kept in bots/<id>/bot.json; the token lives in KeyVault, the code in code.js. */
data class BotInfo(
    val id: String,
    var name: String,
    var username: String = "",
    var description: String = "",
    var enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var ownerChat: Long = 0,
    var ownerPin: String = "",
    var allow: String = "",
    var aiPerUserHour: Int = 20,
    var aiPerDay: Int = 300,
    var notifyNewUser: Boolean = true,
    var notifyMessages: Boolean = false,
    var notifyErrors: Boolean = true,
    var commands: List<Pair<String, String>> = emptyList(),
    var codeVersion: Long = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("username", username).put("description", description)
        .put("enabled", enabled).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("ownerChat", ownerChat).put("ownerPin", ownerPin).put("allow", allow)
        .put("aiPerUserHour", aiPerUserHour).put("aiPerDay", aiPerDay)
        .put("notifyNewUser", notifyNewUser).put("notifyMessages", notifyMessages).put("notifyErrors", notifyErrors)
        .put("commands", JSONArray().also { a -> commands.forEach { a.put(JSONObject().put("name", it.first).put("desc", it.second)) } })
        .put("codeVersion", codeVersion)

    companion object {
        fun from(o: JSONObject): BotInfo {
            val cmds = o.optJSONArray("commands")
            return BotInfo(
                o.optString("id"), o.optString("name"), o.optString("username"), o.optString("description"),
                o.optBoolean("enabled"), o.optLong("createdAt"), o.optLong("updatedAt"),
                o.optLong("ownerChat"), o.optString("ownerPin"), o.optString("allow"),
                o.optInt("aiPerUserHour", 20), o.optInt("aiPerDay", 300),
                o.optBoolean("notifyNewUser", true), o.optBoolean("notifyMessages", false), o.optBoolean("notifyErrors", true),
                if (cmds == null) emptyList() else (0 until cmds.length()).mapNotNull { i ->
                    cmds.optJSONObject(i)?.let { it.optString("name") to it.optString("desc") }
                },
                o.optLong("codeVersion")
            )
        }

        fun newPin(rnd: java.util.Random = java.security.SecureRandom()): String = (100000 + rnd.nextInt(900000)).toString()
    }
}

object BotAccess {
    /** Allow list: user ids or @usernames separated by commas/spaces/new lines. Empty = everyone. */
    fun parse(allow: String): Set<String> =
        allow.split(',', ' ', '\n', ';').map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun allowed(allow: String, user: TgUser, ownerChat: Long): Boolean {
        if (ownerChat != 0L && user.id == ownerChat) return true
        val set = parse(allow)
        if (set.isEmpty()) return true
        return user.id.toString() in set || (user.username.isNotEmpty() && user.username.lowercase() in set)
    }
}

/** Owner commands, handled by Lumen itself (never reach the bot code). */
object BotOwner {
    sealed class Cmd {
        data class Claim(val pin: String) : Cmd()
        object Stop : Cmd()
        object Stats : Cmd()
        object Logs : Cmd()
        object Help : Cmd()
    }

    fun parse(command: String, args: String): Cmd? = when (command) {
        "lumen_owner" -> Cmd.Claim(args.trim())
        "lumen_stop" -> Cmd.Stop
        "lumen_stats" -> Cmd.Stats
        "lumen_logs" -> Cmd.Logs
        "lumen" -> Cmd.Help
        else -> null
    }
}

/**
 * Limits for AI answers so strangers cannot burn the key: per user per hour and per bot per day.
 * Not thread-safe: one limiter per bot worker.
 */
class AiLimiter(var perUserHour: Int, var perDay: Int) {
    private val users = HashMap<Long, ArrayDeque<Long>>()
    private val day = ArrayDeque<Long>()

    fun take(userId: Long, now: Long): Boolean {
        while (day.isNotEmpty() && now - day.first() >= 86_400_000L) day.removeFirst()
        val q = users.getOrPut(userId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= 3_600_000L) q.removeFirst()
        if (perDay in 1..day.size) return false
        if (userId != 0L && perUserHour in 1..q.size) return false
        day.addLast(now)
        q.addLast(now)
        return true
    }

    val usedToday: Int get() = day.size
}

/** schedule("every 30m" | "every 2h" | "daily 09:00" | "hourly") → next run time. */
object BotSchedule {
    sealed class Spec {
        data class Every(val ms: Long) : Spec()
        data class Daily(val hour: Int, val minute: Int) : Spec()
    }

    fun parse(s: String): Spec? {
        val t = s.trim().lowercase()
        if (t == "hourly") return Spec.Every(3_600_000L)
        Regex("""^every\s+(\d{1,4})\s*(m|min|minutes?|h|hours?|d|days?)$""").find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2].first()) {
                'm' -> 60_000L
                'h' -> 3_600_000L
                else -> 86_400_000L
            }
            val ms = n * unit
            return if (ms >= 60_000L) Spec.Every(ms) else null
        }
        Regex("""^(?:daily|every\s+day|at)\s+(\d{1,2}):(\d{2})$""").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) Spec.Daily(h, min) else null
        }
        return null
    }

    /** First run strictly after [after]. "every" runs are anchored to [anchor] (the start of the bot). */
    fun next(spec: Spec, after: Long, anchor: Long, tz: TimeZone = TimeZone.getDefault()): Long = when (spec) {
        is Spec.Every -> {
            if (after < anchor) anchor + spec.ms
            else anchor + ((after - anchor) / spec.ms + 1) * spec.ms
        }
        is Spec.Daily -> {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = after
            c.set(Calendar.HOUR_OF_DAY, spec.hour)
            c.set(Calendar.MINUTE, spec.minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_MONTH, 1)
            c.timeInMillis
        }
    }
}

object BotCode {
    /** The JavaScript from an AI answer: the ```js block if there is one, else the whole text. */
    fun extract(reply: String): String {
        val fence = Regex("```(?:javascript|js|JavaScript|JS)?[ \t]*\\n([\\s\\S]*?)```").findAll(reply).map { it.groupValues[1] }.toList()
        val code = if (fence.isNotEmpty()) fence.maxByOrNull { it.length }!! else reply
        return sanitize(code.trim())
    }

    /** Small fixes for what the engine (Rhino, ES6 subset) does not accept but models often write. */
    fun sanitize(code: String): String = code
        .replace(Regex("""\bfor\s*\(\s*const\s+"""), "for (let ")
        .replace(Regex("""^\s*(?:export\s+default\s+|module\.exports\s*=.*$)""", RegexOption.MULTILINE), "")
        .replace(Regex("""^\s*bot\.launch\(\s*\)\s*;?\s*$""", RegexOption.MULTILINE), "")

    /** Syntax the engine cannot run, with a hint for the AI fixer. */
    fun unsupported(code: String): List<String> {
        val out = ArrayList<String>()
        if (Regex("""\basync\s+(function|\(|[A-Za-z_$][\w$]*\s*=>)""").containsMatchIn(code) || Regex("""\bawait\s""").containsMatchIn(code)) {
            out.add("async/await")
        }
        if (Regex("""^\s*class\s+[A-Za-z_$]""", RegexOption.MULTILINE).containsMatchIn(code)) out.add("class")
        if (Regex("""\.\.\.[A-Za-z_$\[{(]""").containsMatchIn(code)) out.add("spread ...")
        if (Regex("""^\s*(import\s|.*\brequire\s*\()""", RegexOption.MULTILINE).containsMatchIn(code)) out.add("import/require")
        return out
    }

    fun usesAi(code: String) = Regex("""\bai\s*\(""").containsMatchIn(code)
}

object BotNet {
    /** http.get/post from bot code: only public http(s) hosts (no phone, no home network). */
    fun allowed(url: String): Boolean {
        val u = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        if (u.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = u.host?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return false
        if (host == "::1" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")) return false
        val ip = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ip.size == 4 && host.count { it == '.' } == 3) {
            val (a, b) = ip[0] to ip[1]
            if (a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)) return false
        }
        return true
    }
}

/** One line of a bot's journal. */
data class BotLogEntry(val at: Long, val kind: String, val chat: Long, val who: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("kind", kind).put("chat", chat).put("who", who).put("text", text)

    companion object {
        const val IN = "in"
        const val OUT = "out"
        const val ERR = "err"
        const val SYS = "sys"
        const val AI = "ai"

        fun from(o: JSONObject) = BotLogEntry(o.optLong("at"), o.optString("kind"), o.optLong("chat"), o.optString("who"), o.optString("text"))

        fun parseLines(text: String): List<BotLogEntry> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull {
            try {
                from(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }.toList()
    }
}

/** Counters for the list screen and /lumen_stats. */
object BotStats {
    fun today(entries: List<BotLogEntry>, now: Long, tz: TimeZone = TimeZone.getDefault()): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        val day = entries.filter { it.at >= from }
        return Triple(day.count { it.kind == BotLogEntry.IN }, day.filter { it.kind == BotLogEntry.IN }.map { it.chat }.toSet().size, day.count { it.kind == BotLogEntry.ERR })
    }
}

/** API the bot code sees; shown to the AI in every bot prompt. */
val BOT_API = """Среда: JavaScript (движок Rhino, ES6 без class, async/await, spread "...", import/require). Можно: function, стрелки, let/const, шаблонные строки `${'$'}{x}`, деструктуризация, Map/Set, JSON, RegExp, Date, try/catch.
Все вызовы СИНХРОННЫЕ (ai(), http.get() сразу возвращают результат) — не используй Promise/then/await.

bot.start(fn)                         — команда /start
bot.help(fn)                          — команда /help
bot.command('name', 'описание', fn)   — команда /name (описание попадёт в меню Telegram)
bot.hears('текст' | /regex/i, fn)     — сообщение совпало (ctx.match — результат RegExp)
bot.on('text' | 'message' | 'photo', fn) — любое сообщение (если не сработали команды и hears)
bot.action('data' | /regex/, fn)      — нажата inline-кнопка (ctx.data, ctx.match)
bot.catch(fn(err, ctx))               — обработчик ошибок
schedule('every 30m' | 'every 2h' | 'daily 09:00', fn) — задачи по расписанию (время телефона)

ctx.text, ctx.command, ctx.args (текст после команды), ctx.chatId, ctx.user {id, name, username}, ctx.data, ctx.messageId
ctx.reply(text, opts)   — ответ; opts: {buttons: [['Да','Нет'], [{text:'Сайт', url:'https://…'}], [{text:'Ещё', data:'more'}]],
                          keyboard: [['Меню','Помощь']], remove: true, html: true, silent: true}
ctx.edit(text, opts)    — изменить сообщение с кнопкой (в bot.action)
ctx.answer('текст')     — всплывающая подсказка на нажатие кнопки
ctx.replyPhoto(url, caption)
ctx.ai(prompt, {system}) — ответ ИИ (строка) с учётом лимитов этого пользователя
ctx.store.get(key, def) / ctx.store.set(key, value) / ctx.store.del(key) — данные этого пользователя (JSON)
db.get(key, def) / db.set(key, value) / db.del(key) / db.keys(prefix) — общие данные бота (сохраняются)
ai(prompt, {system})     — ответ ИИ (строка)
send(chatId, text, opts) — сообщение любому чату (для рассылок по расписанию)
users()                  — все, кто писал боту: [{id, name, username}]
http.get(url) / http.post(url, body) → {status, ok, text, json()}
youtube('запрос', 5)     — поиск YouTube: [{title, url, channel, views, duration}]
notify('текст')          — уведомление владельцу на телефон
log(...)                 — запись в журнал бота"""

/** Prompts of the bot section (editable in Settings → prompts like the others). */
val BOT_PROMPTS = linkedMapOf(
    "bot_build" to (
        "Ты опытный разработчик Telegram-ботов. Напиши полный код бота по описанию пользователя.\n\n" +
            "API бота:\n{api}\n\n" +
            "Правила:\n- Только код, один блок ```js. Без пояснений вне кода.\n" +
            "- Обязательно bot.start с приветствием и кратким списком возможностей; bot.help.\n" +
            "- Каждой команде дай короткое описание в bot.command.\n" +
            "- Используй кнопки, где они удобнее команд. Храни состояние диалога в ctx.store.\n" +
            "- На непонятные сообщения отвечай подсказкой (bot.on('text')), если в описании не сказано иное.\n" +
            "- Язык сообщений бота — язык описания, если не указан другой.\n" +
            "- Не выдумывай внешние API и ключи; http только к публичным API без ключа или с ключом из описания.\n" +
            "- Пиши комментарии к блокам кода на языке {lang}.\n\n" +
            "Имя бота: {name}\nОписание:\n{desc}"
        ),
    "bot_edit" to (
        "Измени код Telegram-бота по просьбе пользователя. Сохрани всё, что просьба не затрагивает.\n\n" +
            "API бота:\n{api}\n\nТекущий код:\n```js\n{code}\n```\n\nЧто изменить:\n{request}\n\n" +
            "Верни ПОЛНЫЙ новый код одним блоком ```js, без пояснений."
        ),
    "bot_fix" to (
        "Код Telegram-бота не работает. Исправь ошибку, ничего лишнего не меняй.\n\n" +
            "API бота:\n{api}\n\nКод:\n```js\n{code}\n```\n\nОшибка:\n{error}\n\n" +
            "Верни ПОЛНЫЙ исправленный код одним блоком ```js, без пояснений."
        )
)

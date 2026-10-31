/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

class BotCoreTest {
    private val me = TgUser(42, "Ann", "ann")

    private fun msg(text: String, id: Long = 1): TgUpdate {
        val c = Tg.command(text)
        return TgUpdate(id, "message", 42, "private", me, text, id, command = c?.first.orEmpty(), args = c?.second.orEmpty())
    }

    private fun press(data: String) = TgUpdate(9, "callback", 42, "private", me, "old", 7, callbackId = "cb1", data = data)

    /** Records what the bot sends. */
    private class Rec : NoopBotHost() {
        val sent = ArrayList<Pair<String, JSONObject>>()
        val answered = ArrayList<String>()
        val edited = ArrayList<String>()
        var aiPrompts = ArrayList<String>()
        override fun send(chatId: Long, text: String, opts: String): Long {
            sent.add(text to JSONObject(opts.ifBlank { "{}" }))
            return sent.size.toLong()
        }
        override fun answer(callbackId: String, text: String) {
            answered.add(text)
        }
        override fun edit(chatId: Long, messageId: Long, text: String, opts: String) {
            edited.add(text)
        }
        override fun ai(prompt: String, system: String, userId: Long): String {
            aiPrompts.add("$userId:$prompt")
            return "AI says hi"
        }
    }

    @Test
    fun parsesUpdatesAndCommands() {
        val body = """{"ok":true,"result":[
            {"update_id":10,"message":{"message_id":5,"from":{"id":42,"first_name":"Ann","last_name":"B","username":"ann"},"chat":{"id":42,"type":"private"},"text":"/start@MyBot hi there"}},
            {"update_id":11,"callback_query":{"id":"q1","from":{"id":42,"first_name":"Ann"},"message":{"message_id":6,"chat":{"id":-100,"type":"group"},"text":"Pick"},"data":"yes"}},
            {"update_id":12,"my_chat_member":{}}
        ]}"""
        val b = Tg.parseUpdates(body, "mybot")
        assertEquals(12L, b.lastId)
        assertEquals(2, b.updates.size)
        val m = b.updates[0]
        assertEquals("start", m.command)
        assertEquals("hi there", m.args)
        assertEquals("Ann B", m.user.name)
        val q = b.updates[1]
        assertEquals("callback", q.kind)
        assertEquals(-100L, q.chatId)
        assertEquals("yes", q.data)
        assertNull(Tg.command("/start@OtherBot", "mybot"))
        assertEquals("help" to "", Tg.command("/HELP"))
        assertNull(Tg.command("hello"))
        assertNull(Tg.command("/"))
    }

    @Test
    fun telegramErrorsAndTokens() {
        val e = Tg.error("""{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":7}}""")
        assertEquals(429, e.code)
        assertEquals(7, e.retryAfter)
        try {
            Tg.parseUpdates("""{"ok":false,"error_code":409,"description":"Conflict"}""")
            fail()
        } catch (x: TgError) {
            assertEquals(409, x.code)
        }
        assertTrue(Tg.tokenOk("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
        assertFalse(Tg.tokenOk("hello"))
        assertEquals("123456789:AA…saw", Tg.mask("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
    }

    @Test
    fun splitsLongTextOnLines() {
        val text = (1..300).joinToString("\n") { "line number $it with some words" }
        val parts = Tg.split(text, 1000)
        assertTrue(parts.size > 5)
        assertTrue(parts.all { it.length <= 1000 })
        assertEquals(text.replace("\n", ""), parts.joinToString("") { it.replace("\n", "") })
        assertEquals(listOf("short"), Tg.split("short"))
    }

    @Test
    fun buildsKeyboards() {
        val inline = Tg.markup(JSONObject("""{"buttons":[["Yes",{"text":"Site","url":"https://x.org"}],[{"text":"More","data":"more"}]]}"""))!!
        val rows = inline.getJSONArray("inline_keyboard")
        assertEquals("Yes", rows.getJSONArray(0).getJSONObject(0).getString("callback_data"))
        assertEquals("https://x.org", rows.getJSONArray(0).getJSONObject(1).getString("url"))
        assertEquals("more", rows.getJSONArray(1).getJSONObject(0).getString("callback_data"))
        val flat = Tg.markup(JSONObject("""{"buttons":["A","B"]}"""))!!
        assertEquals(2, flat.getJSONArray("inline_keyboard").length())
        val kb = Tg.markup(JSONObject("""{"keyboard":[["Menu","Help"]]}"""))!!
        assertTrue(kb.getBoolean("resize_keyboard"))
        assertTrue(Tg.markup(JSONObject("""{"remove":true}"""))!!.getBoolean("remove_keyboard"))
        assertNull(Tg.markup(JSONObject()))
        val p = Tg.sendParams(1, "hi", JSONObject("""{"html":true,"silent":true}"""))
        assertEquals("HTML", p.getString("parse_mode"))
        assertTrue(p.getBoolean("disable_notification"))
        val cmds = Tg.commandsJson(listOf("start" to "", "Bad-Name" to "x", "help" to "Help", "start" to "dup"))
        assertEquals(2, cmds.length())
        assertEquals("start", cmds.getJSONObject(0).getString("description"))
    }

    @Test
    fun botInfoRoundTrip() {
        val b = BotInfo("b1", "Quiz", "quizbot", "desc", enabled = true, ownerChat = 42, ownerPin = "123456", commands = listOf("start" to "Go"))
        val c = BotInfo.from(JSONObject(b.toJson().toString()))
        assertEquals(b, c)
        assertEquals(6, BotInfo.newPin().length)
    }

    @Test
    fun accessListAndOwner() {
        assertTrue(BotAccess.allowed("", me, 0))
        assertTrue(BotAccess.allowed("@ann, 7", me, 0))
        assertTrue(BotAccess.allowed("42", me, 0))
        assertFalse(BotAccess.allowed("bob 7", me, 0))
        assertTrue(BotAccess.allowed("bob", me, 42))
        assertEquals(BotOwner.Cmd.Claim("123456"), BotOwner.parse("lumen_owner", " 123456 "))
        assertEquals(BotOwner.Cmd.Stop, BotOwner.parse("lumen_stop", ""))
        assertNull(BotOwner.parse("start", ""))
    }

    @Test
    fun limitsAiCalls() {
        val l = AiLimiter(2, 3)
        assertTrue(l.take(1, 0))
        assertTrue(l.take(1, 1000))
        assertFalse(l.take(1, 2000))
        assertTrue(l.take(2, 3000))
        assertFalse(l.take(3, 4000))
        // the hour is over for user 1, but the day limit still holds
        assertFalse(l.take(1, 3_600_000L + 1500))
        assertEquals(3, l.usedToday)
        assertTrue(l.take(1, 86_400_000L + 5000))
        // 0 = no limit
        val free = AiLimiter(0, 0)
        assertTrue((1..50).all { free.take(1, it.toLong()) })
    }

    @Test
    fun schedules() {
        val tz = TimeZone.getTimeZone("UTC")
        assertEquals(BotSchedule.Spec.Every(30 * 60_000L), BotSchedule.parse("every 30m"))
        assertEquals(BotSchedule.Spec.Every(2 * 3_600_000L), BotSchedule.parse("Every 2 hours"))
        assertEquals(BotSchedule.Spec.Daily(9, 0), BotSchedule.parse("daily 09:00"))
        assertNull(BotSchedule.parse("daily 25:00"))
        assertNull(BotSchedule.parse("every 10s"))
        assertNull(BotSchedule.parse("sometimes"))
        val every = BotSchedule.Spec.Every(60_000L)
        assertEquals(1000L + 60_000L, BotSchedule.next(every, 1000L, 1000L, tz))
        assertEquals(1000L + 120_000L, BotSchedule.next(every, 61_000L, 1000L, tz))
        val day = 86_400_000L
        assertEquals(9 * 3_600_000L, BotSchedule.next(BotSchedule.Spec.Daily(9, 0), 0L, 0L, tz))
        assertEquals(day + 9 * 3_600_000L, BotSchedule.next(BotSchedule.Spec.Daily(9, 0), 10 * 3_600_000L, 0L, tz))
    }

    @Test
    fun extractsAndChecksCode() {
        val reply = "Here:\n```js\nbot.start(ctx => ctx.reply('hi'));\nfor (const x of [1]) {}\nbot.launch();\n```\nDone"
        val code = BotCode.extract(reply)
        assertTrue(code.startsWith("bot.start"))
        assertTrue(code.contains("for (let x of"))
        assertFalse(code.contains("launch"))
        assertTrue(BotCode.unsupported("bot.on('text', async (ctx) => { await x() })").contains("async/await"))
        assertTrue(BotCode.unsupported("class A {}").contains("class"))
        assertTrue(BotCode.unsupported("f(...args)").contains("spread ..."))
        assertTrue(BotCode.unsupported("bot.start(ctx => ctx.reply('...'))").isEmpty())
        assertTrue(BotCode.usesAi("ctx.reply(ctx.ai(ctx.text))"))
    }

    @Test
    fun blocksLocalNetwork() {
        assertTrue(BotNet.allowed("https://api.github.com/repos"))
        assertFalse(BotNet.allowed("http://localhost:8080"))
        assertFalse(BotNet.allowed("http://192.168.1.1/admin"))
        assertFalse(BotNet.allowed("http://10.0.0.2"))
        assertFalse(BotNet.allowed("http://172.20.1.1"))
        assertFalse(BotNet.allowed("file:///sdcard/x"))
        assertFalse(BotNet.allowed("not a url"))
    }

    @Test
    fun logAndStats() {
        val now = 10 * 86_400_000L + 3_600_000L
        val e = listOf(
            BotLogEntry(now - 1000, BotLogEntry.IN, 1, "a", "hi"),
            BotLogEntry(now - 2000, BotLogEntry.IN, 2, "b", "yo"),
            BotLogEntry(now - 3000, BotLogEntry.ERR, 2, "", "boom"),
            BotLogEntry(now - 2 * 86_400_000L, BotLogEntry.IN, 3, "c", "old")
        )
        val text = e.joinToString("\n") { it.toJson().toString() } + "\nbroken"
        val back = BotLogEntry.parseLines(text)
        assertEquals(e, back)
        assertEquals(Triple(2, 2, 1), BotStats.today(back, now, TimeZone.getTimeZone("UTC")))
    }

    // ---- JavaScript engine ----

    private val sample = """
        bot.start(ctx => ctx.reply('Привет, ' + ctx.user.name + '!', { buttons: [['Да', 'Нет'], [{ text: 'Ещё', data: 'more:1' }]] }));
        bot.command('count', 'Счётчик', ctx => {
          const n = ctx.store.get('n', 0) + 1;
          ctx.store.set('n', n);
          ctx.reply(`Счёт: ${'$'}{n}`);
        });
        bot.action(/^more:(\d+)$/, ctx => ctx.edit('Страница ' + ctx.match[1]));
        bot.hears('Да', ctx => ctx.reply('Отлично'));
        bot.hears(/погода в (.+)/i, ctx => ctx.reply('Город: ' + ctx.match[1]));
        bot.on('text', ctx => ctx.reply(ctx.ai(ctx.text)));
        schedule('daily 09:00', () => users().forEach(u => send(u.id, 'Доброе утро')));
    """.trimIndent()

    @Test
    fun runsBotCode() {
        val h = Rec()
        val rt = BotRuntime(sample, h)
        val meta = rt.load()
        assertEquals(listOf("start", "count"), meta.commands.map { it.first })
        assertEquals("Счётчик", meta.commands[1].second)
        assertEquals(listOf("daily 09:00"), meta.schedules)

        assertEquals("command:start", rt.handle(msg("/start")))
        assertEquals("Привет, Ann!", h.sent.last().first)
        assertEquals(2, h.sent.last().second.getJSONArray("buttons").length())

        rt.handle(msg("/count"))
        rt.handle(msg("/count"))
        assertEquals("Счёт: 2", h.sent.last().first)

        assertEquals("action", rt.handle(press("more:3")))
        assertEquals("Страница 3", h.edited.last())
        assertEquals(listOf(""), h.answered)

        // a plain button works like typed text
        assertEquals("hears", rt.handle(press("Да")))
        assertEquals("Отлично", h.sent.last().first)

        rt.handle(msg("Погода в Москва"))
        assertEquals("Город: Москва", h.sent.last().first)

        assertEquals("on", rt.handle(msg("как дела?")))
        assertEquals("AI says hi", h.sent.last().first)
        assertEquals("42:как дела?", h.aiPrompts.last())

        rt.runSchedule(0)
    }

    @Test
    fun telegrafStyleWorks() {
        val h = Rec()
        val rt = BotRuntime(
            """
            bot.start((ctx) => { ctx.reply('Hi ' + ctx.from.first_name + ' ' + ctx.message.text) })
            bot.action('ok', (ctx) => { ctx.answerCbQuery('Done'); ctx.editMessageText('ok ' + ctx.callbackQuery.data) })
            bot.launch()
            """.trimIndent(),
            h
        )
        rt.load()
        rt.handle(msg("/start"))
        assertEquals("Hi Ann /start", h.sent.last().first)
        rt.handle(press("ok"))
        assertEquals(listOf("Done"), h.answered)
        assertEquals("ok ok", h.edited.last())
    }

    @Test
    fun sandboxHasNoJava() {
        val h = Rec()
        val rt = BotRuntime("bot.start(ctx => ctx.reply(typeof java + ' ' + typeof Packages + ' ' + typeof importPackage))", h)
        rt.load()
        rt.handle(msg("/start"))
        assertEquals("undefined undefined undefined", h.sent.last().first)
        try {
            BotRuntime("var s = new java.lang.String('x');", h).load()
            fail()
        } catch (e: BotJsError) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun stopsEndlessLoops() {
        val rt = BotRuntime("bot.start(ctx => { while (true) {} })", Rec(), 300)
        rt.load()
        val t0 = System.currentTimeMillis()
        try {
            rt.handle(msg("/start"))
            fail()
        } catch (e: BotJsError) {
            assertTrue(e.timeout)
        }
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }

    @Test
    fun reportsErrorsWithLine() {
        val rt = BotRuntime("bot.start(ctx => ctx.reply('a'));\nbot.command('x', ctx => {\n  nope.call();\n});", Rec())
        rt.load()
        try {
            rt.handle(msg("/x"))
            fail()
        } catch (e: BotJsError) {
            assertEquals(3, e.line)
            assertTrue(e.full.contains("nope"))
        }
        // bot.catch handles it instead
        val h = Rec()
        val ok = BotRuntime("bot.command('x', ctx => { nope.call() });\nbot.catch((e, ctx) => ctx.reply('sorry'));", h)
        ok.load()
        assertEquals("error", ok.handle(msg("/x")))
        assertEquals("sorry", h.sent.last().first)
        // host failures become catchable JS errors
        val h2 = object : NoopBotHost() {
            var got = ""
            override fun ai(prompt: String, system: String, userId: Long): String = throw IllegalStateException("limit")
            override fun send(chatId: Long, text: String, opts: String): Long {
                got = text
                return 1
            }
        }
        val rt2 = BotRuntime("bot.start(ctx => { try { ai('x') } catch (e) { ctx.reply('caught ' + e.message) } })", h2)
        rt2.load()
        rt2.handle(msg("/start"))
        assertEquals("caught limit", h2.got)
    }

    @Test
    fun checksGeneratedCode() {
        assertNull(BotRuntime.check(sample))
        assertNotNull(BotRuntime.check("bot.start(async ctx => { await ctx.reply('x') })"))
        assertNotNull(BotRuntime.check("var x = 1;"))
        assertNotNull(BotRuntime.check("bot.start(ctx => ctx.reply('a'"))
        assertNotNull(BotRuntime.check("bot.start(ctx => undefinedFn())"))
    }

    @Test
    fun dbKeepsJson() {
        val h = Rec()
        val rt = BotRuntime(
            "bot.start(ctx => { db.set('list', [1, {a: 'b'}]); db.set('x', 5); db.del('x'); " +
                "ctx.reply(JSON.stringify(db.get('list')) + ' ' + db.get('x', 'none') + ' ' + db.keys('l').join()) })",
            h
        )
        rt.load()
        rt.handle(msg("/start"))
        assertEquals("""[1,{"a":"b"}] none list""", h.sent.last().first)
        assertEquals(JSONArray("[1]").length(), 1)
    }
}

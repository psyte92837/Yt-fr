/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** One bot: test chat, code, journal, settings. */
class BotActivity : LumenBase() {
    private lateinit var botId: String
    private var tab = TAB_TEST
    private var busy: String? = null
    private var busyView: TextView? = null
    private var logFilter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        botId = intent.getStringExtra("id").orEmpty()
        tab = intent.getIntExtra("tab", TAB_TEST)
        if (Bots.get(botId) == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra("fresh", false)) {
            chat.add(Msg(false, t("Бот создан. Это тестовый чат: он работает на телефоне без Telegram. Напишите /start.\nКогда всё устроит — нажмите «▶ Запуск».",
                "The bot is created. This is a test chat: it runs on the phone without Telegram. Send /start.\nWhen you are happy, press \"▶ Start\"."), system = true))
        }
    }

    override fun onResume() {
        super.onResume()
        BotRun.listener = { if (tab != TAB_TEST) render() }
        render()
    }

    override fun onPause() {
        BotRun.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        sim.shutdownNow()
        super.onDestroy()
    }

    private fun info() = Bots.get(botId)

    private fun render() {
        val b = info() ?: run {
            finish()
            return
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = BotRun.of(botId)
        val (stateText, color) = BotsActivity.statusLine(b, st)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), dp(4))
            addView(text(context, stateText, 13f, bold = true, color = color).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(context, if (b.enabled) t("⏸ Стоп", "⏸ Stop") else t("▶ Запуск", "▶ Start"), style = if (b.enabled) BtnStyle.OUTLINED else BtnStyle.FILLED) {
                toggle(b)
            })
        }
        header.addView(row)
        header.addView(tabBar(this, listOf(t("Тест", "Test"), t("Код", "Code"), t("Журнал", "Journal"), t("Настройки", "Settings")), tab) {
            tab = it
            render()
        })
        val title = if (b.username.isNotBlank()) "@" + b.username else b.name
        when (tab) {
            TAB_TEST -> renderTest(title, header)
            TAB_CODE -> page(title, codeTab(b).root, header = header, keepScroll = true)
            TAB_LOG -> page(title, logTab(b).root, header = header)
            else -> page(title, settingsTab(b).root, header = header, keepScroll = true)
        }
    }

    private fun toggle(b: BotInfo) {
        if (!b.enabled && Bots.token(botId) == null) {
            toast(t("Нет токена: вкладка «Настройки»", "No token: see the Settings tab"))
            return
        }
        if (!b.enabled && BotCode.usesAi(Bots.code(botId))) {
            toast(t("Ответы ИИ этого бота будут отправляться без окна проверки", "This bot's AI answers will be sent without the review screen"))
        }
        BotManager.setEnabled(this, botId, !b.enabled)
        if (!b.enabled && !BotManager.batteryOk(this)) BotManager.askBattery(this)
        render()
    }

    // ---------------- test chat ----------------

    private class Msg(
        val fromBot: Boolean,
        var text: String,
        var buttons: List<List<Pair<String, String>>> = emptyList(),
        val system: Boolean = false,
        val id: Long = 0,
        val error: String? = null
    )

    private val chat = ArrayList<Msg>()
    private var keyboard: List<List<String>> = emptyList()
    private val sim = Executors.newSingleThreadExecutor()
    private var simRt: BotRuntime? = null
    private var simVersion = -1L
    private var nextId = 100L
    private val simDb = NoopBotHost()
    private val me = TgUser(1, t("Вы", "You"), "you")
    private var chatInput: EditText? = null
    private var draft = ""

    private fun parseButtons(opts: JSONObject): List<List<Pair<String, String>>> {
        val m = Tg.markup(opts) ?: return emptyList()
        val kb = m.optJSONArray("inline_keyboard") ?: return emptyList()
        return (0 until kb.length()).map { i ->
            val r = kb.getJSONArray(i)
            (0 until r.length()).map { j ->
                val o = r.getJSONObject(j)
                o.optString("text") to if (o.has("url")) "url:" + o.optString("url") else "cb:" + o.optString("callback_data")
            }
        }
    }

    private fun keyboardOf(opts: JSONObject) {
        val m = Tg.markup(opts) ?: return
        if (m.optBoolean("remove_keyboard")) keyboard = emptyList()
        val kb = m.optJSONArray("keyboard") ?: return
        keyboard = (0 until kb.length()).map { i ->
            val r = kb.getJSONArray(i)
            (0 until r.length()).map { r.getJSONObject(it).optString("text") }
        }
    }

    private inner class SimHost : BotHost {
        private fun post(m: Msg) = Lumen.ui {
            chat.add(m)
            if (tab == TAB_TEST) render()
        }

        private fun o(s: String) = try {
            JSONObject(s.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }

        override fun send(chatId: Long, text: String, opts: String): Long {
            val id = nextId++
            val oo = o(opts)
            Lumen.ui { keyboardOf(oo) }
            post(Msg(true, if (chatId == me.id) text else "→ $chatId: $text", parseButtons(oo), id = id))
            return id
        }

        override fun edit(chatId: Long, messageId: Long, text: String, opts: String) {
            val oo = o(opts)
            Lumen.ui {
                chat.firstOrNull { it.id == messageId }?.let {
                    it.text = text
                    it.buttons = parseButtons(oo)
                }
                if (tab == TAB_TEST) render()
            }
        }

        override fun answer(callbackId: String, text: String) {
            if (text.isNotBlank()) Lumen.ui { toast(text) }
        }

        override fun photo(chatId: Long, url: String, caption: String, opts: String): Long {
            val id = nextId++
            post(Msg(true, "🖼 $url\n$caption".trim(), parseButtons(o(opts)), id = id))
            return id
        }

        override fun ai(prompt: String, system: String, userId: Long): String {
            if (Provider.active() == null) return t("(AI не подключён)", "(AI is not connected)")
            return BotAi.reply(botId, info()?.name.orEmpty(), prompt, system)
        }

        override fun dbGet(key: String) = simDb.dbGet(key)
        override fun dbSet(key: String, json: String?) = simDb.dbSet(key, json)
        override fun dbKeys(prefix: String) = simDb.dbKeys(prefix)
        override fun notify(text: String) = post(Msg(false, "🔔 " + t("Уведомление: ", "Notification: ") + text, system = true))
        override fun log(text: String) = post(Msg(false, "📝 $text", system = true))
        override fun http(method: String, url: String, body: String, headers: String) = BotAi.http(method, url, body, headers)
        override fun youtube(query: String, n: Int) = BotAi.youtube(query, n)
        override fun users(): String = JSONArray().put(me.toJson()).toString()
    }

    /** Runs [work] on the test runtime (reloaded when the code changed). */
    private fun simulate(work: (BotRuntime) -> Unit) {
        sim.execute {
            try {
                val v = info()?.codeVersion ?: return@execute
                var rt = simRt
                if (rt == null || v != simVersion) {
                    rt = BotRuntime(Bots.code(botId), SimHost(), 20_000)
                    simRt = null
                    rt.load()
                    simRt = rt
                    simVersion = v
                }
                work(rt)
            } catch (e: BotJsError) {
                Lumen.ui {
                    chat.add(Msg(false, "⚠️ " + e.full, system = true, error = e.full))
                    if (tab == TAB_TEST) render()
                }
            } catch (e: Exception) {
                Lumen.ui {
                    chat.add(Msg(false, "⚠️ " + (e.message ?: e.javaClass.simpleName), system = true))
                    if (tab == TAB_TEST) render()
                }
            }
        }
    }

    private fun sendTest(text: String) {
        if (text.isBlank()) return
        chat.add(Msg(false, text))
        draft = ""
        render()
        val cmd = Tg.command(text)
        val u = TgUpdate(nextId++, "message", me.id, "private", me, text, nextId++, command = cmd?.first.orEmpty(), args = cmd?.second.orEmpty())
        simulate { it.handle(u) }
    }

    private fun pressTest(m: Msg, label: String, data: String) {
        if (data.startsWith("url:")) {
            openUrl(data.removePrefix("url:"))
            return
        }
        chat.add(Msg(false, "[$label]", system = true))
        render()
        val u = TgUpdate(nextId++, "callback", me.id, "private", me, m.text, m.id, callbackId = "sim" + nextId, data = data.removePrefix("cb:"))
        simulate { it.handle(u) }
    }

    private fun renderTest(title: String, header: View) {
        chatInput?.let { draft = it.text.toString() }
        val c = Col(this, 12)
        if (chat.isEmpty()) {
            c.muted(t("Тестовый чат работает прямо на телефоне, без Telegram. Данные теста отдельные от настоящих.",
                "The test chat runs right on the phone, without Telegram. Test data is separate from the real one."))
        }
        for (m in chat) bubble(c, m)
        if (busy != null) busyView = c.working(busy!!).first
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(c.root)
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.CARD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        if (keyboard.isNotEmpty()) {
            for (r in keyboard) {
                footer.addView(chipRow(this, r.map { label -> chip(this, label) { sendTest(label) } }))
            }
        }
        val input = edt(this, t("Сообщение боту", "Message to the bot"), draft)
        chatInput = input
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(context, "➤", style = BtnStyle.TONAL) { sendTest(input.text.toString()) })
        }
        footer.addView(inputRow)
        footer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn(context, "/start", style = BtnStyle.TEXT) { sendTest("/start") })
            addView(btn(context, t("↺ Сначала", "↺ Restart"), style = BtnStyle.TEXT) {
                chat.clear()
                keyboard = emptyList()
                simRt = null
                simVersion = -1
                simDb.dbKeys("").forEach { simDb.dbSet(it, null) }
                render()
            })
        })
        page(title, scroll, header = header, footer = footer)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(c: Col, m: Msg) {
        val tv = text(this, m.text, if (m.system) 13f else Type.BODY, color = when {
            m.error != null -> Palette.BAD
            m.system -> Palette.MUTED
            m.fromBot -> Palette.ON_SURFACE
            else -> Palette.ON_FAB
        }).apply {
            setTextIsSelectable(true)
            if (!m.system) {
                background = roundBg(if (m.fromBot) Palette.CARD else Palette.FAB, 16f, context)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.fromBot || m.system) Gravity.START else Gravity.END
            addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (r in m.buttons) {
                addView(rowOf(context, *r.map { (label, data) ->
                    btn(context, if (data.startsWith("url:")) "$label ↗" else label, style = BtnStyle.OUTLINED) { pressTest(m, label, data) }
                }.toTypedArray()))
            }
            if (m.error != null) addView(btn(context, t("🛠 Исправить с ИИ", "🛠 Fix with AI"), style = BtnStyle.TONAL) { fixWithAi(m.error) })
        }
        c.add(wrap, 6)
    }

    // ---------------- code ----------------

    private fun codeTab(b: BotInfo): Col {
        val c = Col(this)
        val code = Bots.code(botId)
        val v = Bots.versions(botId).firstOrNull()
        c.muted(t("Версия от ", "Version of ") + Fmt.dateTime(v?.at ?: b.updatedAt) + " · " + t("строк: ", "lines: ") + code.lines().size +
            (v?.note?.takeIf { it.isNotBlank() }?.let { " · " + it.take(80) } ?: ""))
        if (b.commands.isNotEmpty()) {
            c.muted(t("Команды: ", "Commands: ") + b.commands.joinToString(" ") { "/" + it.first })
        }
        val busyNow = busy
        if (busyNow != null) {
            busyView = c.working(busyNow).first
        } else {
            c.button(t("✏️ Изменить словами (ИИ)", "✏️ Change in words (AI)"), primary = true) { editWithAi() }
            c.row(
                btn(this, t("Править вручную", "Edit by hand"), style = BtnStyle.TONAL) { editByHand(code) },
                btn(this, t("Версии", "Versions"), style = BtnStyle.TEXT) { versions() },
                btn(this, "⧉", style = BtnStyle.TEXT) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("bot.js", code))
                    toast(t("Код скопирован", "Code copied"))
                }
            )
        }
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Palette.ON_SURFACE)
            setTextIsSelectable(true)
            text = highlight(code)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val hs = HorizontalScrollView(this).apply {
            background = roundBg(Palette.CARD, 12f, context)
            addView(tv)
        }
        c.add(hs, 12)
        return c
    }

    private fun startBusy(text: String) {
        busy = text
        render()
    }

    private fun stopBusy() {
        busy = null
        busyView = null
        render()
    }

    private fun applyResult(r: BotAi.Result, note: String) {
        val save = {
            Bots.saveCode(botId, r.code, note)
            simRt = null
            stopBusy()
            toast(t("Код обновлён. Работающий бот перезагрузится сам.", "Code updated. A running bot reloads by itself."))
        }
        if (r.error == null) {
            save()
            return
        }
        stopBusy()
        AlertDialog.Builder(this).setTitle(t("В новом коде осталась ошибка", "The new code still has an error"))
            .setMessage(r.error)
            .setPositiveButton(t("Сохранить всё равно", "Save anyway")) { _, _ -> save() }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .show()
    }

    private fun editWithAi() {
        if (Provider.active() == null) {
            toast(t("Подключите AI в настройках", "Connect AI in settings"))
            return
        }
        prompt(t("Что изменить в боте?", "What should change?"), t("Например: добавь команду /price с кнопками тарифов", "E.g. add a /price command with plan buttons"), multiline = true) { req ->
            if (req.isBlank()) return@prompt
            startBusy(t("ИИ меняет код…", "AI is changing the code…"))
            val code = Bots.code(botId)
            Lumen.async({ BotAi.edit(code, req, "bot:$botId") { s -> Lumen.ui { busyView?.text = s } } }, { e ->
                stopBusy()
                err(e)
            }) { r -> applyResult(r, req) }
        }
    }

    private fun fixWithAi(error: String) {
        if (Provider.active() == null) {
            toast(t("Подключите AI в настройках", "Connect AI in settings"))
            return
        }
        tab = TAB_CODE
        startBusy(t("ИИ исправляет ошибку…", "AI is fixing the error…"))
        val code = Bots.code(botId)
        Lumen.async({ BotAi.fix(code, error, "bot:$botId") { s -> Lumen.ui { busyView?.text = s } } }, { e ->
            stopBusy()
            err(e)
        }) { r -> applyResult(r, t("Исправление: ", "Fix: ") + error.take(60)) }
    }

    private fun editByHand(code: String) {
        val e = edt(this, "", code, multiline = true).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setHorizontallyScrolling(true)
            minLines = 12
        }
        val box = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(e)
        }
        AlertDialog.Builder(this).setTitle(t("Код бота", "Bot code")).setView(box)
            .setPositiveButton(t("Сохранить", "Save")) { _, _ ->
                val newCode = BotCode.sanitize(e.text.toString())
                Lumen.async({ BotRuntime.check(newCode) }) { problem ->
                    applyResult(BotAi.Result(newCode, problem), t("Правка вручную", "Edited by hand"))
                }
            }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .show()
    }

    private fun versions() {
        val list = Bots.versions(botId)
        if (list.size < 2) {
            toast(t("Других версий пока нет", "No other versions yet"))
            return
        }
        val labels = list.map { Fmt.dateTime(it.at) + (if (it.note.isNotBlank()) " — " + it.note.take(60) else "") }
        AlertDialog.Builder(this).setTitle(t("Версии кода", "Code versions")).setItems(labels.toTypedArray()) { _, i ->
            val v = list[i]
            confirm(t("Вернуть эту версию?", "Restore this version?"), labels[i], t("Вернуть", "Restore")) {
                Bots.saveCode(botId, v.file.readText(), t("Возврат к версии от ", "Restored the version of ") + Fmt.dateTime(v.at))
                simRt = null
                render()
            }
        }.show()
    }

    // ---------------- journal ----------------

    private fun logTab(b: BotInfo): Col {
        val c = Col(this)
        val all = Bots.logs(botId)
        val (msgs, chats, errs) = BotStats.today(all, System.currentTimeMillis())
        val users = Bots.users(botId)
        c.muted(t("Сегодня: сообщений $msgs · собеседников $chats · ошибок $errs · всего пользователей ${users.length()}",
            "Today: $msgs messages · $chats chats · $errs errors · ${users.length()} users in total"))
        c.add(choiceChips(this, listOf(0 to t("Все", "All"), 1 to t("Ошибки", "Errors"), 2 to t("Пользователи", "Users")), logFilter) {
            logFilter = it
            render()
        }, 8)
        if (logFilter == 2) {
            if (users.length() == 0) c.muted(t("Пока никто не писал боту.", "Nobody has written to the bot yet."))
            val sorted = (0 until users.length()).map { users.getJSONObject(it) }.sortedByDescending { it.optLong("last") }
            for (u in sorted) {
                c.card {
                    add(text(ctx, u.optString("name") + if (u.optString("username").isNotBlank()) " @" + u.optString("username") else "", 14f, bold = true), 0)
                    muted("id ${u.optLong("id")} · " + t("сообщений ", "messages ") + u.optInt("n") + " · " + Fmt.dateTime(u.optLong("last")))
                }
            }
            return c
        }
        val list = all.filter { logFilter == 0 || it.kind == BotLogEntry.ERR }.takeLast(300).reversed()
        if (list.isEmpty()) c.muted(t("Журнал пуст. Запустите бота и напишите ему в Telegram.", "The journal is empty. Start the bot and write to it in Telegram."))
        for (e in list) {
            val arrow = when (e.kind) {
                BotLogEntry.IN -> "→"
                BotLogEntry.OUT -> "←"
                BotLogEntry.ERR -> "⚠️"
                BotLogEntry.AI -> "✨"
                else -> "•"
            }
            val line = Fmt.dateTime(e.at).substringAfter(' ') + " " + arrow + " " + (if (e.who.isNotBlank()) e.who + ": " else "") + e.text
            val tv = c.add(text(this, line, 13f, color = when (e.kind) {
                BotLogEntry.ERR -> Palette.BAD
                BotLogEntry.OUT, BotLogEntry.AI -> Palette.MUTED
                else -> Palette.ON_SURFACE
            }).apply { maxLines = 6 }, 6)
            tv.setOnClickListener {
                val ab = AlertDialog.Builder(this).setTitle(Fmt.dateTime(e.at)).setMessage(e.text).setPositiveButton("OK", null)
                if (e.kind == BotLogEntry.ERR) ab.setNeutralButton(t("Исправить с ИИ", "Fix with AI")) { _, _ -> fixWithAi(e.text) }
                ab.show()
            }
        }
        if (all.isNotEmpty()) {
            c.button(t("Очистить журнал", "Clear the journal"), style = BtnStyle.TEXT) {
                confirm(t("Очистить журнал?", "Clear the journal?"), "", t("Очистить", "Clear")) {
                    Bots.clearLogs(botId)
                    render()
                }
            }
        }
        return c
    }

    // ---------------- settings ----------------

    private fun settingsTab(b: BotInfo): Col {
        val c = Col(this)
        c.h2(t("Бот", "Bot"))
        val name = c.edit(t("Название", "Name"), b.name).apply { isSingleLine = true }
        c.button(t("Сохранить название", "Save the name"), style = BtnStyle.TEXT) {
            Bots.update(botId) { it.name = name.text.toString().trim().ifBlank { it.name } }
            render()
        }
        c.muted(t("Токен: ", "Token: ") + (Bots.token(botId)?.let { Tg.mask(it) } ?: t("нет", "none")))
        c.button(t("Заменить токен", "Replace the token"), style = BtnStyle.TONAL) { replaceToken() }

        c.h2(t("Владелец", "Owner"))
        if (b.ownerChat == 0L) {
            val cmd = "/lumen_owner ${b.ownerPin}"
            c.muted(t("Отправьте боту в Telegram команду ниже — бот запомнит вас как владельца. Тогда можно управлять им из Telegram: /lumen_stats, /lumen_logs, /lumen_stop.",
                "Send the command below to the bot in Telegram — it will remember you as the owner. Then you can manage it from Telegram: /lumen_stats, /lumen_logs, /lumen_stop."))
            c.row(text(this, cmd, 16f, bold = true).apply { setTextIsSelectable(true) }, btn(this, "⧉", style = BtnStyle.TEXT) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                toast(t("Скопировано", "Copied"))
            })
            if (b.username.isNotBlank()) c.button(t("Открыть бота в Telegram", "Open the bot in Telegram"), style = BtnStyle.TEXT) { openUrl("https://t.me/${b.username}") }
        } else {
            c.muted(t("Владелец: id ", "Owner: id ") + b.ownerChat)
            c.button(t("Сбросить владельца", "Reset the owner"), style = BtnStyle.TEXT) {
                Bots.update(botId) {
                    it.ownerChat = 0
                    it.ownerPin = BotInfo.newPin()
                }
                render()
            }
        }

        c.h2(t("Доступ", "Access"))
        c.muted(t("Пусто — бот отвечает всем. Иначе только этим людям: id или @username через запятую.",
            "Empty — the bot answers everyone. Otherwise only these people: ids or @usernames separated by commas."))
        val allow = c.edit("@ann, 123456789", b.allow)
        c.button(t("Сохранить список", "Save the list"), style = BtnStyle.TEXT) {
            Bots.update(botId) { it.allow = allow.text.toString().trim() }
            toast(t("Сохранено", "Saved"))
        }

        c.h2(t("Лимиты ИИ", "AI limits"))
        c.muted(t("Чтобы незнакомцы не израсходовали ваш ключ. 0 — без ограничений.", "So strangers do not burn your key. 0 — no limit."))
        val perUser = c.edit(t("Запросов в час на человека", "Requests per hour per person"), b.aiPerUserHour.toString(), number = true)
        val perDay = c.edit(t("Запросов в сутки на бота", "Requests per day per bot"), b.aiPerDay.toString(), number = true)
        c.button(t("Сохранить лимиты", "Save the limits"), style = BtnStyle.TEXT) {
            Bots.update(botId) {
                it.aiPerUserHour = perUser.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: it.aiPerUserHour
                it.aiPerDay = perDay.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: it.aiPerDay
            }
            toast(t("Сохранено", "Saved"))
        }

        c.h2(t("Уведомления на телефон", "Phone notifications"))
        c.check(t("Новый пользователь", "New user"), b.notifyNewUser) { v -> Bots.update(botId) { it.notifyNewUser = v } }
        c.check(t("Каждое сообщение", "Every message"), b.notifyMessages) { v -> Bots.update(botId) { it.notifyMessages = v } }
        c.check(t("Ошибки бота", "Bot errors"), b.notifyErrors) { v -> Bots.update(botId) { it.notifyErrors = v } }
        c.muted(t("Из кода бот может прислать уведомление сам: notify('текст').", "The code can send a notification itself: notify('text')."))

        c.h2(t("Работа в фоне", "Background work"))
        c.muted(if (BotManager.batteryOk(this)) t("✓ Ограничения батареи сняты", "✓ Battery restrictions are off")
        else t("Android может усыплять ботов при выключенном экране. Разрешите работу в фоне.", "Android may put bots to sleep when the screen is off. Allow background work."))
        if (!BotManager.batteryOk(this)) c.button(t("Разрешить", "Allow"), style = BtnStyle.TONAL) { BotManager.askBattery(this) }

        c.h2(t("Описание", "Description"))
        c.p(b.description.ifBlank { "—" }, 14f)
        c.button(t("Пересоздать код по описанию", "Rewrite the code from the description"), style = BtnStyle.TEXT) { rebuild(b) }

        c.h2(t("Данные", "Data"))
        c.muted(t("Записей в хранилище бота: ", "Records in the bot storage: ") + Bots.db(botId).size())
        c.button(t("Очистить данные бота", "Clear the bot data"), style = BtnStyle.TEXT) {
            confirm(t("Очистить данные?", "Clear the data?"), t("Всё, что бот сохранил через db/ctx.store, будет удалено.", "Everything the bot saved with db/ctx.store will be deleted."), t("Очистить", "Clear")) {
                Bots.dropDb(botId)
                render()
            }
        }
        c.button(t("Удалить бота", "Delete the bot"), style = BtnStyle.TEXT) {
            confirm(t("Удалить бота?", "Delete the bot?"), t("Код, журнал и данные будут удалены с телефона. Сам бот в Telegram останется (удалить его можно в @BotFather).",
                "The code, journal and data will be deleted from the phone. The bot itself stays in Telegram (delete it in @BotFather)."), t("Удалить", "Delete")) {
                BotManager.setEnabled(this, botId, false)
                Bots.delete(botId)
                BotManager.sync(this)
                finish()
            }
        }
        return c
    }

    private fun replaceToken() {
        prompt(t("Новый токен", "New token"), "123456789:AA…") { tk ->
            val token = tk.trim()
            if (!Tg.tokenOk(token)) {
                toast(t("Это не похоже на токен", "This does not look like a token"))
                return@prompt
            }
            Lumen.async({ TgApi(token).getMe() }, { e -> err(e) }) { me ->
                Bots.setToken(botId, token)
                Bots.update(botId) {
                    it.username = me.second
                    it.ownerChat = 0
                    it.ownerPin = BotInfo.newPin()
                }
                Bots.setOffset(botId, 0)
                if (info()?.enabled == true) {
                    BotManager.setEnabled(this, botId, false)
                    BotManager.setEnabled(this, botId, true)
                }
                toast("✓ @${me.second}")
                render()
            }
        }
    }

    private fun rebuild(b: BotInfo) {
        if (Provider.active() == null) {
            toast(t("Подключите AI в настройках", "Connect AI in settings"))
            return
        }
        prompt(t("Описание бота", "Bot description"), "", b.description, multiline = true) { d ->
            if (d.isBlank()) return@prompt
            Bots.update(botId) { it.description = d }
            tab = TAB_CODE
            startBusy(t("ИИ пишет код бота…", "AI is writing the bot code…"))
            Lumen.async({ BotAi.build(b.name, d, "bot:$botId") { s -> Lumen.ui { busyView?.text = s } } }, { e ->
                stopBusy()
                err(e)
            }) { r -> applyResult(r, t("Пересоздан по описанию", "Rewritten from the description")) }
        }
    }

    companion object {
        const val TAB_TEST = 0
        const val TAB_CODE = 1
        const val TAB_LOG = 2
        const val TAB_SETTINGS = 3

        fun open(ctx: Context, id: String, tab: Int = TAB_TEST, fresh: Boolean = false) {
            ctx.startActivity(Intent(ctx, BotActivity::class.java).putExtra("id", id).putExtra("tab", tab).putExtra("fresh", fresh))
        }

        private val KEYWORDS = Regex("""\b(function|return|const|let|var|if|else|for|of|in|while|true|false|null|undefined|new|try|catch|throw|typeof)\b""")
        private val STRINGS = Regex("""'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*"|`(?:\\.|[^`\\])*`""")
        private val COMMENTS = Regex("""//[^\n]*""")
        private val API = Regex("""\b(bot|ctx|db|ai|send|schedule|http|notify|users|youtube|log)\b""")

        /** Light JavaScript colouring for the code tab. */
        fun highlight(code: String): CharSequence {
            val sb = SpannableStringBuilder(code)
            val taken = BooleanArray(code.length)
            fun paint(rx: Regex, color: Int) {
                rx.findAll(code).forEach { m ->
                    if (m.range.any { taken[it] }) return@forEach
                    m.range.forEach { taken[it] = true }
                    sb.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            paint(STRINGS, Palette.OK)
            paint(COMMENTS, Palette.MUTED)
            paint(KEYWORDS, Palette.ACCENT)
            paint(API, Palette.WARN)
            return sb
        }
    }
}

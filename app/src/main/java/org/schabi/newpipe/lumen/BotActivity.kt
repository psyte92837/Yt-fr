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
        header.addView(tabBar(this, listOf(t("Тест", "Test"), t("Код", "Code"), t("Мини-апп", "Mini app"), t("Журнал", "Journal"), t("Настройки", "Settings")), tab) {
            tab = it
            render()
        })
        val title = if (b.username.isNotBlank()) "@" + b.username else b.name
        when (tab) {
            TAB_TEST -> renderTest(title, header)
            TAB_CODE -> page(title, codeTab(b).root, header = header, keepScroll = true)
            TAB_APP -> page(title, appTab(b).root, header = header, keepScroll = true)
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
    /** Reply keyboard of the test chat: label → mini app link ("" for a plain button). */
    private var keyboard: List<List<Pair<String, String>>> = emptyList()
    private val sim = Executors.newSingleThreadExecutor()
    private var simRt: BotRuntime? = null
    private var simVersion = -1L
    private var nextId = 100L
    private val simDb = NoopBotHost()
    private val me = TgUser(1, t("Вы", "You"), "you")
    private var chatInput: EditText? = null
    private var draft = ""

    /** Mini app address in the test chat: the published one, or a local stand-in while it is not published. */
    private fun simApp(): String {
        val b = info() ?: return ""
        if (b.miniAppUrl.isNotBlank()) return b.appLink
        return if (Bots.miniApp(botId).isNotBlank()) "https://lumen.preview/" else ""
    }

    private fun parseButtons(opts: JSONObject): List<List<Pair<String, String>>> {
        val m = Tg.markup(opts, simApp()) ?: return emptyList()
        val kb = m.optJSONArray("inline_keyboard") ?: return emptyList()
        return (0 until kb.length()).map { i ->
            val r = kb.getJSONArray(i)
            (0 until r.length()).map { j ->
                val o = r.getJSONObject(j)
                o.optString("text") to when {
                    o.has("web_app") -> "app:" + o.getJSONObject("web_app").optString("url")
                    o.has("url") -> "url:" + o.optString("url")
                    else -> "cb:" + o.optString("callback_data")
                }
            }
        }
    }

    private fun keyboardOf(opts: JSONObject) {
        val m = Tg.markup(opts, simApp()) ?: return
        if (m.optBoolean("remove_keyboard")) keyboard = emptyList()
        val kb = m.optJSONArray("keyboard") ?: return
        keyboard = (0 until kb.length()).map { i ->
            val r = kb.getJSONArray(i)
            (0 until r.length()).map {
                val o = r.getJSONObject(it)
                o.optString("text") to (o.optJSONObject("web_app")?.optString("url").orEmpty())
            }
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

        override fun appUrl(params: String): String {
            val l = simApp()
            return if (l.isBlank() || params.isBlank()) l else MiniApp.withParams(l, params)
        }
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
        if (data.startsWith("app:")) {
            previewApp(data.removePrefix("app:"), fromKeyboard = false)
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
                footer.addView(chipRow(this, r.map { (label, app) ->
                    chip(this, if (app.isNotBlank()) "📱 $label" else label) {
                        if (app.isNotBlank()) previewApp(app, fromKeyboard = true) else sendTest(label)
                    }
                }))
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
                    btn(context, when {
                        data.startsWith("url:") -> "$label ↗"
                        data.startsWith("app:") -> "📱 $label"
                        else -> label
                    }, style = BtnStyle.OUTLINED) { pressTest(m, label, data) }
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

    // ---------------- mini app ----------------

    /** Opens the mini app like Telegram would; its sendData goes to the test chat. */
    private fun previewApp(link: String, fromKeyboard: Boolean) {
        val html = Bots.miniApp(botId)
        if (html.isBlank()) {
            if (MiniApp.httpsOk(link)) openUrl(link) else toast(t("Мини-апп ещё не создан: вкладка «Мини-апп»", "No mini app yet: see the Mini app tab"))
            return
        }
        MiniAppPreview.show(this, html, MiniApp.paramsOf(link)) { data -> appDataToTest(data, fromKeyboard) }
    }

    private fun appDataToTest(data: String, fromKeyboard: Boolean) {
        tab = TAB_TEST
        chat.add(Msg(false, "📱 " + t("Мини-апп отправил: ", "The mini app sent: ") + data, system = true))
        if (!fromKeyboard) {
            chat.add(Msg(false, t("В Telegram данные дойдут до бота, только если мини-апп открыт keyboard-кнопкой (app: true в keyboard).",
                "In Telegram the data reaches the bot only when the mini app is opened from a keyboard button (app: true in keyboard)."), system = true))
        }
        render()
        val u = TgUpdate(nextId++, "message", me.id, "private", me, "", nextId++, appData = data)
        simulate { it.handle(u) }
    }

    private var appDraft = ""
    private var appDescEdit: EditText? = null
    private var showHtml = false

    private fun appTab(b: BotInfo): Col {
        appDescEdit?.let { appDraft = it.text.toString() }
        appDescEdit = null
        val c = Col(this)
        val html = Bots.miniApp(botId)
        c.muted(t("Мини-апп — веб-страница внутри Telegram: форма заказа, каталог с корзиной, анкета, калькулятор, игра. Открывается кнопкой бота или из меню рядом с полем ввода.",
            "A mini app is a web page inside Telegram: an order form, a catalog with a cart, a survey, a calculator, a game. It opens from a bot button or the menu next to the input."))
        val busyNow = busy
        if (busyNow != null) {
            busyView = c.working(busyNow).first
            return c
        }
        if (html.isBlank()) {
            c.h2(t("Что должен делать мини-апп", "What the mini app should do"))
            appDescEdit = c.edit(t("Например: каталог из 6 товаров с фото-эмодзи, корзина, кнопка «Оформить» отправляет заказ боту", "E.g. a catalog of 6 products with emoji pictures, a cart, \"Checkout\" sends the order to the bot"),
                appDraft, multiline = true).apply { minLines = 4 }
            c.chips(MINIAPP_EXAMPLES.map { (label, text) ->
                chip(this, label) {
                    appDraft = text()
                    render()
                }
            })
            c.button(t("✨ Создать мини-апп (ИИ)", "✨ Create the mini app (AI)"), primary = true) { createApp(b) }
            c.button(t("Вставить свой HTML", "Paste my own HTML"), style = BtnStyle.TEXT) { editAppByHand("") }
            return c
        }
        c.card {
            if (b.miniAppUrl.isNotBlank()) {
                add(text(ctx, "🌐 " + t("Опубликован", "Published"), Type.TITLE_M, bold = true, color = Palette.OK), 0)
                add(text(ctx, b.miniAppUrl, 13f, color = Palette.ACCENT).apply { setOnClickListener { openUrl(b.appLink) } }, 4)
            } else {
                add(text(ctx, t("Не опубликован", "Not published"), Type.TITLE_M, bold = true, color = Palette.WARN), 0)
                muted(t("Telegram откроет мини-апп только после публикации. В тест-чате и предпросмотре он работает уже сейчас.",
                    "Telegram opens the mini app only after publishing. In the test chat and the preview it works already."))
            }
            muted((if (b.miniAppDesc.isNotBlank()) b.miniAppDesc.take(120) + " · " else "") + "%.1f KB".format(html.length / 1024.0))
        }
        c.button(t("👁 Предпросмотр", "👁 Preview"), primary = true) { previewApp(simApp(), fromKeyboard = true) }
        c.row(
            btn(this, t("✏️ Изменить словами", "✏️ Change in words"), style = BtnStyle.TONAL) { editApp(b, html) },
            btn(this, t("Править HTML", "Edit HTML"), style = BtnStyle.TEXT) { editAppByHand(html) }
        )
        c.button(t("🔗 Подключить к боту (ИИ)", "🔗 Connect to the bot (AI)"), style = BtnStyle.TONAL) { connectApp(b, html) }
        c.muted(t("ИИ добавит в код бота кнопку открытия и обработку данных, которые присылает мини-апп.", "AI adds an opening button and the handling of the mini app's data to the bot code."))

        c.h2(t("Публикация", "Publishing"))
        c.muted(t("Telegram открывает мини-аппы только по адресу https. Lumen бесплатно публикует страницу на GitHub Pages вашего аккаунта (публичный репозиторий «${GhPages.repo}»).",
            "Telegram opens mini apps only from an https address. Lumen publishes the page for free to GitHub Pages of your account (public repository \"${GhPages.repo}\")."))
        if (GhPages.token() == null) {
            c.muted(t("1) Войдите на github.com (или зарегистрируйтесь). 2) Создайте токен: «Получить токен» → Generate token (право public_repo уже отмечено). 3) Вставьте его сюда.",
                "1) Sign in to github.com (or sign up). 2) Make a token: \"Get a token\" → Generate token (public_repo is already ticked). 3) Paste it here."))
            c.row(
                btn(this, t("Получить токен", "Get a token"), style = BtnStyle.TEXT) { openUrl(GhPages.TOKEN_PAGE) },
                btn(this, t("Ввести токен", "Enter the token"), style = BtnStyle.TONAL) { connectGitHub() }
            )
        } else {
            c.muted("GitHub: @${GhPages.login} · ${GhPages.repo}")
            c.button(if (b.miniAppUrl.isBlank() || !b.miniAppUrl.contains("github.io")) t("🌐 Опубликовать", "🌐 Publish") else t("🌐 Обновить публикацию", "🌐 Update the publication"), primary = true) {
                publishApp(b)
            }
            c.button(t("Отключить GitHub / сменить репозиторий", "Disconnect GitHub / change the repository"), style = BtnStyle.TEXT) { gitHubMenu() }
        }
        c.button(t("Свой адрес (https)…", "Own address (https)…"), style = BtnStyle.TEXT) {
            prompt(t("Адрес мини-аппа", "Mini app address"), "https://…", b.miniAppUrl) { url ->
                if (url.isNotBlank() && !MiniApp.httpsOk(url)) {
                    toast(t("Нужен адрес https://", "An https:// address is needed"))
                    return@prompt
                }
                Bots.update(botId) {
                    it.miniAppUrl = url.trim()
                    it.miniAppVersion = System.currentTimeMillis()
                }
                pushMenu()
                render()
            }
        }
        c.button(t("Поделиться HTML", "Share the HTML"), style = BtnStyle.TEXT) { shareText("index.html", html) }

        c.h2(t("В Telegram", "In Telegram"))
        c.check(t("Кнопка мини-аппа в меню бота (рядом с полем ввода)", "Mini app button in the bot menu (next to the input)"), b.miniAppMenu) { v ->
            Bots.update(botId) { it.miniAppMenu = v }
            pushMenu()
        }
        val title = c.edit(t("Надпись на кнопке меню", "Menu button label"), b.miniAppTitle.ifBlank { t("Открыть", "Open") }).apply { isSingleLine = true }
        c.button(t("Сохранить надпись", "Save the label"), style = BtnStyle.TEXT) {
            Bots.update(botId) { it.miniAppTitle = title.text.toString().trim().take(64) }
            pushMenu()
            toast(t("Сохранено", "Saved"))
        }
        c.muted(t("Из меню мини-апп открывается для просмотра; вернуть данные боту он может только при открытии keyboard-кнопкой.",
            "From the menu the mini app opens for viewing; it can send data back only when opened from a keyboard button."))

        c.h2("HTML")
        c.button(if (showHtml) t("Скрыть код", "Hide the code") else t("Показать код", "Show the code"), style = BtnStyle.TEXT) {
            showHtml = !showHtml
            render()
        }
        if (showHtml) {
            val tv = TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Palette.ON_SURFACE)
                setTextIsSelectable(true)
                text = highlight(html)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            c.add(HorizontalScrollView(this).apply {
                background = roundBg(Palette.CARD, 12f, context)
                addView(tv)
            }, 8)
        }
        if (Bots.hasPrevMiniApp(botId)) {
            c.button(t("↩ Вернуть прошлую версию", "↩ Restore the previous version"), style = BtnStyle.TEXT) {
                Bots.undoMiniApp(botId)
                render()
            }
        }
        c.button(t("Удалить мини-апп", "Delete the mini app"), style = BtnStyle.TEXT) {
            confirm(t("Удалить мини-апп?", "Delete the mini app?"), t("Страница удалится с телефона, кнопка меню в Telegram вернётся к списку команд. Опубликованная копия на GitHub останется.",
                "The page is deleted from the phone, the Telegram menu button goes back to the command list. The published copy on GitHub stays."), t("Удалить", "Delete")) {
                Bots.deleteMiniApp(botId)
                Bots.update(botId) {
                    it.miniAppUrl = ""
                    it.miniAppDesc = ""
                }
                pushMenu()
                render()
            }
        }
        return c
    }

    /** Applies the menu button right away (a running bot also does it on its next poll). */
    private fun pushMenu() {
        val b = info() ?: return
        val token = Bots.token(botId) ?: return
        val url = if (b.miniAppMenu && b.miniAppUrl.isNotBlank()) b.appLink else ""
        Lumen.async({ TgApi(token).setMenuButton(url, b.miniAppTitle.ifBlank { t("Открыть", "Open") }) }, { e -> L.w("bots", "menu button: ${e.message}") }) { }
    }

    private fun needAi(): Boolean {
        if (Provider.active() != null) return true
        toast(t("Подключите AI в настройках", "Connect AI in settings"))
        return false
    }

    private fun createApp(b: BotInfo) {
        appDescEdit?.let { appDraft = it.text.toString() }
        val desc = appDraft.trim()
        if (desc.isBlank()) {
            toast(t("Опишите мини-апп", "Describe the mini app"))
            return
        }
        if (!needAi()) return
        startBusy(t("ИИ делает мини-апп…", "AI is making the mini app…"))
        Lumen.async({ MiniAppAi.build(b, desc) { s -> Lumen.ui { busyView?.text = s } } }, { e ->
            stopBusy()
            err(e)
        }) { html ->
            Bots.saveMiniApp(botId, html)
            Bots.update(botId) { it.miniAppDesc = desc }
            appDraft = ""
            stopBusy()
            confirm(t("Мини-апп готов", "The mini app is ready"),
                t("Подключить его к боту? ИИ добавит в код кнопку открытия и обработку данных.", "Connect it to the bot? AI adds an opening button and data handling to the code."),
                t("Подключить", "Connect")) { info()?.let { connectApp(it, html) } }
        }
    }

    private fun editApp(b: BotInfo, html: String) {
        if (!needAi()) return
        prompt(t("Что изменить в мини-аппе?", "What should change in the mini app?"), t("Например: добавь поле «Комментарий» и тёмную шапку", "E.g. add a Comment field and a dark header"), multiline = true) { req ->
            if (req.isBlank()) return@prompt
            startBusy(t("ИИ меняет мини-апп…", "AI is changing the mini app…"))
            Lumen.async({ MiniAppAi.edit(b, html, req) { s -> Lumen.ui { busyView?.text = s } } }, { e ->
                stopBusy()
                err(e)
            }) { newHtml -> savedApp(newHtml) }
        }
    }

    private fun savedApp(html: String) {
        Bots.saveMiniApp(botId, html)
        stopBusy()
        val b = info() ?: return
        if (b.miniAppUrl.contains("github.io") && GhPages.token() != null) {
            confirm(t("Мини-апп изменён", "The mini app changed"), t("Обновить публикацию, чтобы изменения увидели в Telegram?", "Update the publication so Telegram shows the changes?"), t("Обновить", "Update")) { publishApp(b) }
        } else {
            toast(t("Сохранено", "Saved"))
        }
    }

    private fun editAppByHand(html: String) {
        val e = edt(this, "<!DOCTYPE html>…", html, multiline = true).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setHorizontallyScrolling(true)
            minLines = 12
        }
        val box = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(e)
        }
        AlertDialog.Builder(this).setTitle("HTML").setView(box)
            .setPositiveButton(t("Сохранить", "Save")) { _, _ ->
                val h = MiniApp.ensureSdk(e.text.toString().trim())
                val bad = MiniApp.problems(h)
                if (bad.isNotEmpty()) toast(bad.joinToString()) else savedApp(h)
            }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .show()
    }

    private fun connectApp(b: BotInfo, html: String) {
        if (!needAi()) return
        tab = TAB_CODE
        startBusy(t("ИИ подключает мини-апп к боту…", "AI is connecting the mini app to the bot…"))
        Lumen.async({ MiniAppAi.connect(b, html) { s -> Lumen.ui { busyView?.text = s } } }, { e ->
            stopBusy()
            err(e)
        }) { r -> applyResult(r, t("Подключён мини-апп", "Mini app connected")) }
    }

    private fun connectGitHub() {
        prompt(t("Токен GitHub", "GitHub token"), "ghp_…") { tk ->
            if (tk.isBlank()) return@prompt
            Lumen.async({ GhPages.connect(tk) }, { e -> err(e) }) { login ->
                toast("✓ @$login")
                render()
            }
        }
    }

    private fun gitHubMenu() {
        val items = listOf<Pair<String, () -> Unit>>(
            t("Имя репозитория…", "Repository name…") to {
                prompt(t("Репозиторий для мини-аппов", "Repository for mini apps"), "lumen-miniapps", GhPages.repo) { r ->
                    if (Regex("^[A-Za-z0-9._-]{1,100}$").matches(r.trim())) GhPages.repo = r else toast(t("Недопустимое имя", "Invalid name"))
                    render()
                }
            },
            t("Отключить GitHub", "Disconnect GitHub") to {
                GhPages.disconnect()
                render()
            }
        )
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun publishApp(b: BotInfo) {
        val html = Bots.miniApp(botId)
        if (html.isBlank()) return
        startBusy(t("Публикую…", "Publishing…"))
        Lumen.async({ GhPages.publish(botId, html) { s -> Lumen.ui { busyView?.text = s } } }, { e ->
            stopBusy()
            err(e)
        }) { url ->
            Bots.update(botId) {
                it.miniAppUrl = url
                it.miniAppVersion = System.currentTimeMillis()
            }
            pushMenu()
            stopBusy()
            showText(t("Опубликовано", "Published"), url + "\n\n" + t(
                "GitHub Pages обновляет страницу 1–2 минуты. Кнопки бота уже ведут на новую версию.",
                "GitHub Pages updates the page in 1–2 minutes. The bot buttons already point to the new version."
            ))
        }
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
        const val TAB_APP = 2
        const val TAB_LOG = 3
        const val TAB_SETTINGS = 4

        fun open(ctx: Context, id: String, tab: Int = TAB_TEST, fresh: Boolean = false) {
            ctx.startActivity(Intent(ctx, BotActivity::class.java).putExtra("id", id).putExtra("tab", tab).putExtra("fresh", fresh))
        }

        private val MINIAPP_EXAMPLES: List<Pair<String, () -> String>> = listOf(
            t("🛒 Каталог", "🛒 Catalog") to {
                t("Каталог из 6–8 товаров (эмодзи вместо фото, название, цена), корзина с количеством, итог. Кнопка «Оформить» отправляет боту список товаров, сумму, имя и телефон.",
                    "A catalog of 6–8 products (emoji instead of photos, name, price), a cart with quantities, a total. \"Checkout\" sends the items, sum, name and phone to the bot.")
            },
            t("📝 Заявка", "📝 Request") to {
                t("Форма заявки: имя, телефон, услуга (выбор из списка), дата и время, комментарий. Проверка полей, кнопка «Отправить» отправляет данные боту.",
                    "A request form: name, phone, service (from a list), date and time, comment. Field validation, \"Send\" sends the data to the bot.")
            },
            t("📊 Опрос", "📊 Survey") to {
                t("Опрос из 5 вопросов с вариантами ответа, прогресс-бар, в конце — результат и отправка ответов боту.",
                    "A 5-question survey with answer options, a progress bar, at the end — the result and sending the answers to the bot.")
            },
            t("🧮 Калькулятор", "🧮 Calculator") to {
                t("Калькулятор стоимости: ползунки площади и сроков, переключатели опций, итоговая цена пересчитывается сразу. «Отправить расчёт» — данные боту.",
                    "A price calculator: sliders for area and terms, option switches, the total updates live. \"Send the estimate\" sends the data to the bot.")
            },
            t("🎮 Игра", "🎮 Game") to {
                t("Простая игра «Поймай звезду» на 30 секунд: звёзды появляются в случайных местах, тап — очко. В конце результат отправляется боту для таблицы лидеров.",
                    "A simple 30-second \"Catch the star\" game: stars appear at random spots, a tap scores. At the end the score is sent to the bot for a leaderboard.")
            }
        )

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

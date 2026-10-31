/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** "Lumen Bots": the list of the user's Telegram bots. Has its own launcher icon. */
class BotsActivity : LumenBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BotManager.channels(this)
        Notify.channels(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }

    override fun onResume() {
        super.onResume()
        BotRun.listener = { render() }
        // the system may have killed the service: bring the bots back when the user looks at them
        if (BotManager.anyEnabled() && !BotRun.serviceRunning) BotManager.sync(this)
        render()
    }

    override fun onPause() {
        BotRun.listener = null
        super.onPause()
    }

    private fun render() {
        val c = Col(this)
        val bots = Bots.list()
        if (Provider.active() == null) {
            c.card {
                add(text(ctx, t("AI не подключён", "AI is not connected"), Type.TITLE_M, bold = true), 0)
                muted(t("ИИ пишет и меняет код ботов. Без него можно начать с шаблона.", "AI writes and changes the bot code. Without it you can start from a template."))
                button(t("Подключить AI", "Connect AI"), style = BtnStyle.TONAL) {
                    startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true))
                }
            }
        }
        if (bots.any { it.enabled } && !BotManager.batteryOk(this)) {
            c.card {
                add(text(ctx, t("Разрешите работу в фоне", "Allow background work"), Type.TITLE_M, bold = true, color = Palette.WARN), 0)
                muted(t("Иначе Android может усыпить ботов, когда экран выключен.", "Otherwise Android may put the bots to sleep when the screen is off."))
                button(t("Разрешить", "Allow"), style = BtnStyle.TONAL) { BotManager.askBattery(this@BotsActivity) }
            }
        }
        c.h2(t("Мои боты", "My bots") + " (${bots.size})")
        if (bots.isEmpty()) {
            c.empty(
                t("Пока нет ботов", "No bots yet"),
                t("Опишите словами, что должен делать бот и какие у него команды. ИИ напишет код, а телефон станет сервером бота.",
                    "Describe what the bot should do and its commands. AI writes the code, and the phone becomes the bot's server."),
                t("Создать бота", "Create a bot")
            ) { BotNewActivity.open(this) }
        }
        val now = System.currentTimeMillis()
        for (b in bots) {
            val st = BotRun.of(b.id)
            val card = c.card {
                val title = text(ctx, b.name.ifBlank { "Bot" }, Type.TITLE_M, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val head = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(btn(ctx, if (b.enabled) t("⏸ Стоп", "⏸ Stop") else t("▶ Запуск", "▶ Start"), style = if (b.enabled) BtnStyle.OUTLINED else BtnStyle.TONAL) {
                        toggle(b)
                    })
                }
                add(head, 0)
                if (b.username.isNotBlank()) muted("@" + b.username)
                val (stateText, color) = statusLine(b, st)
                add(text(ctx, stateText, 13f, bold = true, color = color), 4)
                val (msgs, chats, errs) = BotStats.today(Bots.logs(b.id), now)
                muted(t("Сегодня: сообщений $msgs · собеседников $chats", "Today: $msgs messages · $chats chats") +
                    if (errs > 0) t(" · ошибок $errs", " · $errs errors") else "")
            }
            card.setOnClickListener { BotActivity.open(this, b.id) }
        }
        if (bots.any { it.enabled }) {
            c.button(t("Остановить все", "Stop all"), style = BtnStyle.TEXT) {
                confirm(t("Остановить всех ботов?", "Stop all bots?"), t("Они перестанут отвечать в Telegram.", "They will stop answering in Telegram."), t("Остановить", "Stop")) {
                    BotManager.stopAll(this)
                }
            }
        }
        if (bots.isNotEmpty()) {
            c.muted(t("Телефон работает сервером: боты отвечают, пока он включён и есть интернет. Сообщения, пришедшие офлайн, Telegram хранит до суток.",
                "The phone is the server: bots answer while it is on and online. Messages sent while offline are kept by Telegram for up to a day."))
        }
        page(
            t("Lumen Боты", "Lumen Bots"), c.root, back = !isTaskRoot,
            actions = listOf("⋮" to { menu() }),
            fab = t("＋  Новый бот", "＋  New bot") to { BotNewActivity.open(this) },
            keepScroll = true
        )
    }

    private fun toggle(b: BotInfo) {
        if (!b.enabled && Bots.token(b.id) == null) {
            toast(t("Нет токена: откройте бота → Настройки", "No token: open the bot → Settings"))
            return
        }
        BotManager.setEnabled(this, b.id, !b.enabled)
        if (!b.enabled && !BotManager.batteryOk(this)) BotManager.askBattery(this)
        render()
    }

    private fun menu() {
        val items = listOf<Pair<String, () -> Unit>>(
            t("Открыть Lumen", "Open Lumen") to { startActivity(Intent(this, LumenActivity::class.java)) },
            t("Работа в фоне (батарея)", "Background work (battery)") to { BotManager.askBattery(this) },
            t("Справка", "Help") to { HelpActivity.open(this, "bots") }
        )
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BotsActivity::class.java))

        fun statusLine(b: BotInfo, st: BotRun.Status): Pair<String, Int> = when {
            !b.enabled -> t("⏹ Остановлен", "⏹ Stopped") to Palette.MUTED
            st.state == BotRun.ERROR -> "⚠️ " + st.text to Palette.BAD
            st.state == BotRun.WAITING -> "⏳ " + st.text to Palette.WARN
            st.state == BotRun.RUNNING -> "🟢 " + t("Работает", "Running") to Palette.OK
            st.state == BotRun.STARTING -> "⏳ " + t("Запуск…", "Starting…") to Palette.WARN
            else -> "⏳ " + t("Ожидает запуска сервиса", "Waiting for the service") to Palette.WARN
        }
    }
}

/** New bot: token from @BotFather + a description → AI writes the code → test chat. */
class BotNewActivity : LumenBase() {
    private val id = Bots.newId()
    private var token = ""
    private var desc = ""
    private var checked: Triple<Long, String, String>? = null
    private var busy: String? = null
    private var tokenEdit: EditText? = null
    private var descEdit: EditText? = null
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun keep() {
        tokenEdit?.let { token = it.text.toString().trim() }
        descEdit?.let { desc = it.text.toString() }
    }

    private fun render() {
        keep()
        val c = Col(this)
        c.card {
            add(text(ctx, t("1. Токен бота", "1. Bot token"), Type.TITLE_M, bold = true), 0)
            muted(t("В Telegram откройте @BotFather → /newbot → имя и @username бота → скопируйте токен сюда.",
                "In Telegram open @BotFather → /newbot → bot name and @username → paste the token here."))
            button(t("Открыть @BotFather", "Open @BotFather"), style = BtnStyle.TEXT) { openUrl("https://t.me/BotFather") }
            tokenEdit = edit("123456789:AA…", token).apply { isSingleLine = true }
            val ch = checked
            if (ch != null) add(text(ctx, "✓ @${ch.second} (${ch.third})", 14f, bold = true, color = Palette.OK), 4)
            else button(t("Проверить токен", "Check the token"), style = BtnStyle.TONAL) { checkToken() }
        }
        c.card {
            add(text(ctx, t("2. Что должен делать бот", "2. What the bot should do"), Type.TITLE_M, bold = true), 0)
            muted(t("Опишите задачу, команды, кнопки, тексты. Чем подробнее, тем точнее код.",
                "Describe the job, commands, buttons and texts. The more detail, the better the code."))
            descEdit = edit(t("Например: бот-викторина по истории, 10 вопросов с кнопками, /top — таблица лидеров", "E.g. a history quiz bot, 10 questions with buttons, /top — leaderboard"), desc, multiline = true).apply {
                minLines = 5
            }
            chips(EXAMPLES.map { (label, text) ->
                chip(ctx, label) {
                    keep()
                    desc = text()
                    render()
                }
            })
        }
        val busyNow = busy
        if (busyNow != null) {
            status = c.working(busyNow).first
        } else {
            c.button(t("✨ Создать бота", "✨ Create the bot"), primary = true) { create() }
            c.button(t("Начать с шаблона (без ИИ)", "Start from a template (no AI)"), style = BtnStyle.TEXT) { fromTemplate() }
            c.muted(t("Код бота и данные хранятся только на телефоне. Токен зашифрован.", "The bot code and data stay on the phone. The token is encrypted."))
        }
        page(t("Новый бот", "New bot"), c.root)
    }

    private fun checkToken(then: (() -> Unit)? = null) {
        keep()
        if (!Tg.tokenOk(token)) {
            toast(t("Это не похоже на токен: нужен вид 123456789:AA…", "This does not look like a token: expected 123456789:AA…"))
            return
        }
        busy = t("Проверяю токен…", "Checking the token…")
        render()
        val tk = token
        Lumen.async({ TgApi(tk).getMe() }, { e ->
            busy = null
            render()
            err(if (e is TgError && e.code == 401) Exception(t("Telegram не принял токен", "Telegram rejected the token")) else e)
        }) { me ->
            checked = me
            busy = null
            render()
            then?.invoke()
        }
    }

    private fun create() {
        keep()
        if (checked == null) {
            checkToken { create() }
            return
        }
        if (desc.isBlank()) {
            toast(t("Опишите, что должен делать бот", "Describe what the bot should do"))
            return
        }
        if (Provider.active() == null) {
            toast(t("Подключите AI в настройках или начните с шаблона", "Connect AI in settings or start from a template"))
            return
        }
        val me = checked!!
        busy = t("ИИ пишет код бота…", "AI is writing the bot code…")
        render()
        val d = desc
        Lumen.async({
            BotAi.build(me.third.ifBlank { me.second }, d, "bot:$id") { step -> Lumen.ui { status?.text = step } }
        }, { e ->
            busy = null
            render()
            err(e)
        }) { r -> save(me, d, r.code, r.error) }
    }

    private fun fromTemplate() {
        keep()
        if (checked == null) {
            checkToken { fromTemplate() }
            return
        }
        val me = checked!!
        val items = TEMPLATES.map { it.first() }
        AlertDialog.Builder(this).setTitle(t("Шаблон", "Template")).setItems(items.toTypedArray()) { _, i ->
            save(me, desc.ifBlank { TEMPLATES[i].first() }, TEMPLATES[i].second(), null)
        }.show()
    }

    private fun save(me: Triple<Long, String, String>, d: String, code: String, error: String?) {
        val b = BotInfo(id, me.third.ifBlank { me.second }, me.second, d, ownerPin = BotInfo.newPin())
        Bots.save(b)
        Bots.setToken(id, token)
        Bots.saveCode(id, code, t("Создан", "Created"))
        if (error != null) Bots.log(id, BotLogEntry.ERR, 0, "", t("Проверка кода: ", "Code check: ") + error)
        BotActivity.open(this, id, if (error != null) BotActivity.TAB_CODE else BotActivity.TAB_TEST, fresh = true)
        finish()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BotNewActivity::class.java))

        private val EXAMPLES: List<Pair<String, () -> String>> = listOf(
            t("🤖 ИИ-консультант", "🤖 AI assistant") to {
                t("Бот-консультант по моему бизнесу (кофейня). Отвечает на вопросы через ИИ кратко и вежливо. /menu — меню с ценами кнопками, /contacts — адрес и телефон, /order — оформить заказ: спросить напиток, размер, время, прислать мне уведомление.",
                    "An assistant bot for my business (a coffee shop). Answers questions with AI, short and polite. /menu — price list as buttons, /contacts — address and phone, /order — take an order: drink, size, time, notify me.")
            },
            t("❓ Викторина", "❓ Quiz") to {
                t("Бот-викторина: 10 вопросов с 4 вариантами ответа кнопками, после ответа показывает правильный, в конце — счёт. /top — таблица лидеров, /quiz — начать заново.",
                    "A quiz bot: 10 questions with 4 answer buttons, shows the right answer after each, the score at the end. /top — leaderboard, /quiz — start again.")
            },
            t("⏰ Напоминания", "⏰ Reminders") to {
                t("Бот напоминаний: /add 18:30 текст — добавить ежедневное напоминание, /list — список с кнопками удаления. Каждую минуту проверяет и присылает напоминания.",
                    "A reminder bot: /add 18:30 text — add a daily reminder, /list — the list with delete buttons. Checks every minute and sends reminders.")
            },
            t("📝 Запись", "📝 Booking") to {
                t("Бот записи на услуги: выбор услуги кнопками, дня и времени, ввод имени и телефона, подтверждение; заявка приходит мне уведомлением. /my — мои записи, /cancel — отменить.",
                    "A booking bot: pick a service, day and time with buttons, enter name and phone, confirm; I get a notification. /my — my bookings, /cancel — cancel.")
            },
            t("📺 YouTube-дайджест", "📺 YouTube digest") to {
                t("Бот: /find запрос — 5 видео с YouTube по запросу (название, канал, просмотры, ссылка). /sub тема — подписка, каждый день в 9:00 присылает подписчикам свежие видео по их темам.",
                    "A bot: /find query — 5 YouTube videos (title, channel, views, link). /sub topic — subscribe, every day at 9:00 sends subscribers fresh videos on their topics.")
            }
        )

        val TEMPLATES: List<Pair<() -> String, () -> String>> = listOf(
            { t("Меню с кнопками", "Menu with buttons") } to {
                """
                // ${t("Простое меню с кнопками", "A simple menu with buttons")}
                bot.start(ctx => ctx.reply('${t("Привет", "Hi")}, ' + ctx.user.name + '!', {
                  buttons: [['${t("О нас", "About")}', '${t("Контакты", "Contacts")}'], [{ text: '${t("Сайт", "Website")}', url: 'https://telegram.org' }]]
                }));
                bot.help(ctx => ctx.reply('/start, /help'));
                bot.hears('${t("О нас", "About")}', ctx => ctx.reply('${t("Мы делаем полезные вещи.", "We make useful things.")}'));
                bot.hears('${t("Контакты", "Contacts")}', ctx => ctx.reply('${t("Пишите нам: hello@example.com", "Write to us: hello@example.com")}'));
                bot.on('text', ctx => ctx.reply('${t("Выберите пункт меню: /start", "Pick a menu item: /start")}'));
                """.trimIndent()
            },
            { t("Чат с ИИ", "AI chat") } to {
                """
                // ${t("Собеседник на ИИ с короткой памятью диалога", "An AI chat partner with a short dialogue memory")}
                bot.start(ctx => { ctx.store.set('history', []); ctx.reply('${t("Привет! Задайте любой вопрос.", "Hi! Ask me anything.")}'); });
                bot.command('reset', '${t("Забыть диалог", "Forget the dialogue")}', ctx => { ctx.store.set('history', []); ctx.reply('${t("Начнём сначала.", "Starting over.")}'); });
                bot.on('text', ctx => {
                  let h = ctx.store.get('history', []);
                  h.push('${t("Пользователь", "User")}: ' + ctx.text);
                  const answer = ctx.ai(h.slice(-10).join('\n') + '\n${t("Ассистент", "Assistant")}:');
                  h.push('${t("Ассистент", "Assistant")}: ' + answer);
                  ctx.store.set('history', h.slice(-10));
                  ctx.reply(answer);
                });
                """.trimIndent()
            },
            { t("Эхо", "Echo") } to {
                """
                bot.start(ctx => ctx.reply('${t("Я повторяю всё, что вы пишете.", "I repeat whatever you write.")}'));
                bot.on('text', ctx => ctx.reply(ctx.text));
                """.trimIndent()
            }
        )
    }
}

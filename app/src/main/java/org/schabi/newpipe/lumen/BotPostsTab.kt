/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** "Posts" tab of a bot: channels/groups, a post editor, review, schedule, auto-filling and history. */
class BotPostsTab(private val a: LumenBase, private val botId: String, private val rerender: () -> Unit) {
    // the editor survives re-renders
    private var body = ""
    private var photo = ""
    private var btnText = ""
    private var btnUrl = ""
    private var chosen: MutableSet<String>? = null
    private var busy: String? = null
    private var showHistory = false
    private var showGuide: Boolean? = null

    private fun EditText.bind(set: (String) -> Unit): EditText = apply {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = set(s?.toString().orEmpty())
        })
    }

    /** Title on the left, a small button on the right. */
    private fun head(c: Col, title: String, action: View) {
        val l = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(a, title, 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        c.add(l, 0)
    }

    private fun work(label: String, job: () -> String) {
        busy = label
        rerender()
        Lumen.async({ job() }, { e ->
            busy = null
            rerender()
            a.err(e)
        }) { msg ->
            busy = null
            if (msg.isNotBlank()) a.toast(msg)
            rerender()
        }
    }

    private fun needAi(): Boolean {
        if (Provider.active() != null) return true
        a.toast(t("Подключите AI: Настройки → AI", "Connect AI: Settings → AI"))
        return false
    }

    fun build(): Col {
        val c = Col(a)
        val d = BotPosts.load(botId)
        val b = Bots.get(botId)
        busy?.let { c.working(it) }
        guide(c, d)

        // ---- targets ----
        c.h2(t("Куда публиковать", "Where to post"))
        if (d.targets.isEmpty()) {
            c.muted(
                t(
                    "Добавьте бота в канал администратором (право «Публикация сообщений») или в группу участником, затем добавьте чат сюда. Пока бот запущен, группы и каналы, куда его добавили, появятся здесь сами.",
                    "Add the bot to a channel as an admin (“Post messages”) or to a group as a member, then add the chat here. While the bot runs, chats it is added to appear here by themselves."
                )
            )
        }
        d.targets.forEach { tg ->
            c.card {
                head(
                    this, (if (tg.type == "channel") "📢 " else "👥 ") + tg.label,
                    btn(a, "✕", style = BtnStyle.TEXT) {
                        a.confirm(t("Убрать чат?", "Remove the chat?"), tg.label, t("Убрать", "Remove")) {
                            BotPosts.update(botId) { x -> x.targets.removeAll { it.chat == tg.chat } }
                            chosen?.remove(tg.chat)
                            rerender()
                        }
                    }
                )
                muted(tg.chat)
            }
        }
        c.row(
            btn(a, t("＋ Канал или группа", "＋ Channel or group"), style = BtnStyle.TONAL) { addTarget() },
            btn(a, t("Проверить права", "Check rights"), style = BtnStyle.TEXT) { checkRights() }
        )

        // ---- editor ----
        c.h2(t("Новый пост", "New post"))
        val sel = chosen ?: d.targets.map { it.chat }.toMutableSet().also { chosen = it }
        sel.retainAll(d.targets.map { it.chat }.toSet())
        c.edit(t("Текст поста (можно <b>жирный</b>, <i>курсив</i>, <a href=\"…\">ссылка</a>)", "Post text (<b>bold</b>, <i>italic</i>, <a href=\"…\">link</a>)"), body, multiline = true).bind { body = it }
        c.edit(t("Картинка: ссылка https://… (необязательно)", "Picture: https://… link (optional)"), photo).apply { isSingleLine = true }.bind { photo = it.trim() }
        c.row(
            edt(a, t("Кнопка: текст", "Button: text"), btnText).apply { isSingleLine = true }.bind { btnText = it },
            edt(a, t("Кнопка: https://…", "Button: https://…"), btnUrl).apply { isSingleLine = true }.bind { btnUrl = it.trim() }
        )
        if (d.targets.isNotEmpty()) {
            c.chips(d.targets.map { tg ->
                chip(a, (if (tg.chat in sel) "✓ " else "") + tg.label, tg.chat in sel) {
                    if (!sel.remove(tg.chat)) sel.add(tg.chat)
                    rerender()
                }
            })
        }
        val count = PostCore.visibleLength(body)
        c.muted(t("Символов: ", "Characters: ") + count + if (photo.isNotBlank() && count > 1024) t(" · с картинкой длиннее 1024: картинка уйдёт отдельным сообщением", " · over 1024 with a picture: it goes as a separate message") else "")
        c.row(
            btn(a, t("✨ Написать ИИ", "✨ Write with AI"), style = BtnStyle.TONAL) { aiWrite() },
            btn(a, t("✨ Улучшить", "✨ Improve"), style = BtnStyle.TEXT) { aiEdit() }
        )
        c.row(
            btn(a, t("Опубликовать", "Publish"), primary = true) { publishNow(sel.toList()) },
            btn(a, t("⏰ Позже", "⏰ Later"), style = BtnStyle.OUTLINED) { schedule(sel.toList()) },
            btn(a, t("Тест мне", "Test to me"), style = BtnStyle.TEXT) { testToOwner(b) }
        )

        // ---- review ----
        if (d.drafts.isNotEmpty()) {
            c.h2(t("На проверку (${d.drafts.size})", "To review (${d.drafts.size})"))
            d.drafts.forEach { p -> postCard(c, d, p, draft = true) }
        }

        // ---- scheduled ----
        if (d.queue.isNotEmpty()) {
            c.h2(t("Запланировано (${d.queue.size})", "Scheduled (${d.queue.size})"))
            if (b?.enabled != true) c.muted(t("⚠️ Бот остановлен: запланированные посты уйдут после запуска.", "⚠️ The bot is stopped: scheduled posts go out once it runs."))
            d.queue.sortedBy { it.at }.forEach { p -> postCard(c, d, p, draft = false) }
        }

        // ---- auto-filling ----
        c.h2(t("Автонаполнение", "Auto-filling"))
        c.muted(
            t(
                "Бот сам берёт новости из RSS-лент, видео с YouTube или пишет посты ИИ на тему — и публикует по расписанию (или присылает на проверку). Работает, пока бот запущен.",
                "The bot takes news from RSS feeds, YouTube videos or AI posts on a topic and publishes on a schedule (or sends for review). Works while the bot runs."
            )
        )
        d.plans.forEach { p -> planCard(c, d, p) }
        c.button(t("＋ Новое автонаполнение", "＋ New auto-filling"), style = BtnStyle.TONAL) { editPlan(null) }

        // ---- history ----
        c.section(t("История", "History"), if (d.history.isEmpty()) "—" else t("записей: ", "entries: ") + d.history.size, showHistory, { showHistory = it; rerender() }) {
            d.history.take(40).forEach { h ->
                val where = d.target(h.chat)?.label ?: h.chat
                muted((if (h.ok) "✓ " else "✗ ") + Fmt.dateTime(h.at) + (if (where.isNotBlank()) " · $where" else "") + "\n" +
                    PostCore.stripTags(h.text).take(120) + if (!h.ok || h.info.isNotBlank()) "\n" + h.info else "")
            }
            if (d.history.isNotEmpty()) button(t("Очистить историю", "Clear history"), style = BtnStyle.TEXT) {
                BotPosts.update(botId) { it.history.clear() }
                rerender()
            }
        }
        return c
    }

    /** Short "how to start" inside the tab; open by itself until the first chat is added. */
    private fun guide(c: Col, d: PostsData) {
        val open = showGuide ?: d.targets.isEmpty()
        c.card {
            section(t("📖 С чего начать", "📖 Getting started"), t("4 шага и ответы на частые вопросы", "4 steps and common questions"), open, { showGuide = it; rerender() }) {
                listOf(
                    t("1️⃣ Добавьте бота в Telegram-канал администратором с правом «Публикация сообщений» (канал → Управление → Администраторы → Добавить → @имя бота). В группу — участником.",
                        "1️⃣ Add the bot to a Telegram channel as an admin with “Post messages” (channel → Manage → Administrators → Add → @bot name). To a group — as a member."),
                    t("2️⃣ Здесь: «＋ Канал или группа» → @имя канала или ссылка t.me/… Закрытый чат без @имени появится сам, если добавить бота, пока он запущен.",
                        "2️⃣ Here: “＋ Channel or group” → the channel @name or a t.me/… link. A private chat without an @name appears by itself if you add the bot while it runs."),
                    t("3️⃣ «Новый пост»: текст (или «✨ Написать ИИ»), картинка и кнопка по желанию → «Тест мне» для проверки → «Опубликовать» или «⏰ Позже».",
                        "3️⃣ New post: text (or “✨ Write with AI”), picture and button if you like → “Test to me” to check → “Publish” or “⏰ Later”."),
                    t("4️⃣ Чтобы канал наполнялся сам: «＋ Новое автонаполнение» → источники (RSS-ссылка, yt: запрос, ai: тема) → «Запустить сейчас» для проверки → ▶ Запуск бота.",
                        "4️⃣ To fill the channel automatically: “＋ New auto-filling” → sources (RSS link, yt: query, ai: topic) → “Run now” to check → ▶ Start the bot.")
                ).forEach { p(it, 14f) }
                muted(t("Время для «Позже»: 18:30 · 25.12 09:00 · +30м · +2ч. Разметка: <b>жирный</b>, <i>курсив</i>, <a href=\"https://…\">ссылка</a>.",
                    "Time for “Later”: 18:30 · 25.12 09:00 · +30m · +2h. Markup: <b>bold</b>, <i>italic</i>, <a href=\"https://…\">link</a>."))
                muted(t("⚠️ Отложенные посты и автонаполнение работают, только пока бот запущен и телефон в сети.", "⚠️ Scheduled posts and auto-filling work only while the bot runs and the phone is online."))
                button(t("Подробная справка и решение проблем", "Full help and troubleshooting"), style = BtnStyle.TONAL) { HelpActivity.open(a, "bot_posts") }
            }
        }
    }

    private fun names(d: PostsData, chats: List<String>) = chats.joinToString(", ") { ch -> d.target(ch)?.label ?: ch }.ifBlank { t("чаты не выбраны", "no chats") }

    private fun postCard(c: Col, d: PostsData, p: BotPost, draft: Boolean) {
        c.card {
            muted((if (draft) "📝 " else "⏰ " + Fmt.dateTime(p.at) + " · ") + names(d, p.targets) + if (p.source.isNotBlank()) " · " + p.source else "")
            add(TextView(a).apply {
                text = PostCore.stripTags(p.text).take(600)
                textSize = 14f
                setTextColor(Palette.ON_SURFACE)
                setTextIsSelectable(true)
            })
            if (p.photo.isNotBlank()) muted("🖼 " + p.photo.take(80))
            if (p.buttonUrl.isNotBlank()) muted("🔘 " + p.buttonText + " → " + p.buttonUrl.take(60))
            val remove = {
                BotPosts.update(botId) { x ->
                    x.drafts.removeAll { it.id == p.id }
                    x.queue.removeAll { it.id == p.id }
                }
            }
            row(
                *listOfNotNull(
                    if (draft) btn(a, t("Опубликовать", "Publish"), style = BtnStyle.TONAL) {
                        work(t("Публикую…", "Posting…")) {
                            val (ok, bad) = PostEngine.publish(botId, PostEngine.api(botId), p)
                            if (ok > 0) remove()
                            report(ok, bad)
                        }
                    } else null,
                    btn(a, t("В редактор", "To editor"), style = BtnStyle.TEXT) {
                        body = p.text
                        photo = p.photo
                        btnText = p.buttonText
                        btnUrl = p.buttonUrl
                        chosen = p.targets.toMutableSet()
                        remove()
                        rerender()
                    },
                    btn(a, if (draft) t("Удалить", "Delete") else t("Отменить", "Cancel"), style = BtnStyle.TEXT) {
                        remove()
                        rerender()
                    }
                ).toTypedArray()
            )
        }
    }

    private fun planCard(c: Col, d: PostsData, p: PostPlan) {
        c.card {
            head(
                this, (if (p.enabled) "🟢 " else "⏸ ") + p.name,
                btn(a, if (p.enabled) "⏸" else "▶", style = BtnStyle.TEXT) {
                    BotPosts.update(botId) { x -> x.plans.firstOrNull { it.id == p.id }?.let { it.enabled = !p.enabled } }
                    rerender()
                }
            )
            muted(
                PostCore.everyLabel(p.everyMin) + " · " + t("по ${p.perRun}", "${p.perRun} each") + " · ${p.fromHour}:00–${p.toHour}:59" +
                    (if (p.review) t(" · с проверкой", " · with review") else "") + (if (p.rewrite) t(" · ИИ переписывает", " · AI rewrites") else "")
            )
            muted(t("Источники: ", "Sources: ") + p.sources.joinToString(", ") { s ->
                when (s.type) {
                    PostSource.YOUTUBE -> "▶ " + s.value
                    PostSource.AI -> "✨ " + s.value
                    else -> s.value.removePrefix("https://").removePrefix("http://").take(40)
                }
            })
            muted("→ " + names(d, p.targets) + if (p.lastRun > 0) "\n" + t("Последний запуск: ", "Last run: ") + Fmt.dateTime(p.lastRun) else "")
            row(
                btn(a, t("Запустить сейчас", "Run now"), style = BtnStyle.TONAL) {
                    if ((p.rewrite || p.sources.any { it.type == PostSource.AI }) && !needAi()) return@btn
                    work(t("Собираю материал…", "Collecting material…")) { PostEngine.runPlan(a.applicationContext, botId, p.id, PostEngine.api(botId)) }
                },
                btn(a, t("Изменить", "Edit"), style = BtnStyle.TEXT) { editPlan(p) },
                btn(a, "🗑", style = BtnStyle.TEXT) {
                    a.confirm(t("Удалить автонаполнение?", "Delete the auto-filling?"), p.name, t("Удалить", "Delete")) {
                        BotPosts.update(botId) { x -> x.plans.removeAll { it.id == p.id } }
                        rerender()
                    }
                }
            )
        }
    }

    private fun report(ok: Int, bad: Int): String = when {
        bad == 0 -> t("✓ Опубликовано: $ok", "✓ Published: $ok")
        ok == 0 -> t("✗ Не удалось — см. «История»", "✗ Failed — see History")
        else -> t("Опубликовано $ok, ошибок $bad — см. «История»", "Published $ok, failed $bad — see History")
    }

    private fun current(targets: List<String>) = BotPost(BotPost.newId(), body.trim(), photo, btnText.trim(), btnUrl, targets)

    private fun clearEditor() {
        body = ""
        photo = ""
        btnText = ""
        btnUrl = ""
    }

    private fun ready(targets: List<String>): Boolean {
        if (body.isBlank() && photo.isBlank()) {
            a.toast(t("Пост пустой", "The post is empty"))
            return false
        }
        if (targets.isEmpty()) {
            a.toast(t("Выберите хотя бы один чат", "Choose at least one chat"))
            return false
        }
        if (btnUrl.isNotBlank() && !btnUrl.startsWith("http")) {
            a.toast(t("Ссылка кнопки должна начинаться с https://", "The button link must start with https://"))
            return false
        }
        return true
    }

    private fun publishNow(targets: List<String>) {
        if (!ready(targets)) return
        val p = current(targets)
        a.confirm(t("Опубликовать?", "Publish?"), t("Чатов: ", "Chats: ") + targets.size, t("Опубликовать", "Publish")) {
            work(t("Публикую…", "Posting…")) {
                val (ok, bad) = PostEngine.publish(botId, PostEngine.api(botId), p)
                if (bad == 0) Lumen.ui { clearEditor() }
                report(ok, bad)
            }
        }
    }

    private fun schedule(targets: List<String>) {
        if (!ready(targets)) return
        a.prompt(t("Когда опубликовать?", "When to publish?"), t("18:30 · 25.12 09:00 · +2ч", "18:30 · 25.12 09:00 · +2h")) { s ->
            val at = PostCore.parseWhen(s, System.currentTimeMillis())
            if (at == null || at < System.currentTimeMillis()) {
                a.toast(t("Не понял время. Примеры: 18:30, 25.12 09:00, +30м", "Unclear time. E.g. 18:30, 25.12 09:00, +30m"))
                return@prompt
            }
            val p = current(targets).also { it.at = at }
            BotPosts.update(botId) { it.queue.add(p) }
            clearEditor()
            a.toast(t("Запланировано на ", "Scheduled for ") + Fmt.dateTime(at))
            rerender()
        }
    }

    private fun testToOwner(b: BotInfo?) {
        val owner = b?.ownerChat ?: 0L
        if (owner == 0L) {
            a.toast(t("Сначала станьте владельцем: вкладка «Настройки»", "Become the owner first: Settings tab"))
            return
        }
        if (body.isBlank() && photo.isBlank()) {
            a.toast(t("Пост пустой", "The post is empty"))
            return
        }
        val p = current(listOf(owner.toString()))
        work(t("Отправляю вам…", "Sending to you…")) {
            PostEngine.api(botId).post(owner.toString(), p.text, p.photo, p.buttonText, p.buttonUrl)
            t("✓ Отправлено вам в личку", "✓ Sent to your private chat")
        }
    }

    private fun aiWrite() {
        if (!needAi()) return
        a.prompt(t("О чём пост?", "What is the post about?"), t("Тема, факты, ссылка на новость…", "Topic, facts, a news link…"), multiline = true) { topic ->
            if (topic.isBlank()) return@prompt
            work(t("ИИ пишет пост…", "AI is writing the post…")) {
                val r = PostEngine.onTopic(botId, topic, "", emptyList())
                Lumen.ui { body = r }
                ""
            }
        }
    }

    private fun aiEdit() {
        if (!needAi()) return
        if (body.isBlank()) {
            a.toast(t("Сначала напишите текст", "Write some text first"))
            return
        }
        a.prompt(t("Что улучшить?", "What to improve?"), t("Короче, живее, добавь призыв к действию…", "Shorter, livelier, add a call to action…"), t("Сделай живее и понятнее, добавь эмодзи и хэштеги", "Make it livelier and clearer, add emoji and hashtags")) { req ->
            val src = body
            work(t("ИИ редактирует…", "AI is editing…")) {
                val r = PostEngine.edit(botId, src, req)
                Lumen.ui { body = r }
                ""
            }
        }
    }

    private fun addTarget() {
        a.prompt(t("Канал или группа", "Channel or group"), t("@mychannel, t.me/mychannel или -100…", "@mychannel, t.me/mychannel or -100…")) { s ->
            val chat = PostCore.parseChat(s)
            if (chat == null) {
                a.toast(t("Не похоже на адрес чата. Ссылки-приглашения (t.me/+…) не подходят: укажите @имя или id.", "Not a chat address. Invite links (t.me/+…) do not work: give @name or id."))
                return@prompt
            }
            work(t("Проверяю чат…", "Checking the chat…")) {
                val api = PostEngine.api(botId)
                val (id, title, type) = api.getChat(chat)
                val (status, can) = try {
                    api.botRights(id, PostEngine.botUserId(botId))
                } catch (e: TgError) {
                    "?" to true
                }
                BotPosts.update(botId) { d ->
                    if (d.target(id) == null && d.target(chat) == null) d.targets.add(PostTarget(id, title, type))
                    Unit
                }
                chosen?.add(id)
                if (!can || (type == "channel" && status != "administrator" && status != "creator")) {
                    t("Добавлен «$title», но бот не может там писать ($status). Сделайте его администратором.", "Added “$title”, but the bot cannot post there ($status). Make it an admin.")
                } else t("✓ Добавлен «$title»", "✓ Added “$title”")
            }
        }
    }

    private fun checkRights() {
        val d = BotPosts.load(botId)
        if (d.targets.isEmpty()) {
            a.toast(t("Чатов пока нет", "No chats yet"))
            return
        }
        work(t("Проверяю права…", "Checking rights…")) {
            val api = PostEngine.api(botId)
            val me = PostEngine.botUserId(botId)
            val lines = d.targets.map { tg ->
                try {
                    val (status, can) = api.botRights(tg.chat, me)
                    (if (can) "✓ " else "✗ ") + tg.label + " — " + status
                } catch (e: TgError) {
                    "✗ " + tg.label + " — " + PostEngine.hint(e.description)
                }
            }
            Lumen.ui { a.showText(t("Права бота", "Bot rights"), lines.joinToString("\n")) }
            ""
        }
    }

    private fun editPlan(src: PostPlan?) {
        val d = BotPosts.load(botId)
        val p = src?.copy() ?: PostPlan(PostPlan.newId(), t("Новости", "News"), targets = d.targets.map { it.chat })
        val targets = p.targets.toMutableSet()
        a.bottomSheet(if (src == null) t("Новое автонаполнение", "New auto-filling") else p.name) { dismiss ->
            val name = edit(t("Название", "Name"), p.name).apply { isSingleLine = true }
            muted(t("Источники — по одному в строке:\n• ссылка на RSS/Atom-ленту (сайт новостей, блог, Хабр, YouTube-канал: youtube.com/feeds/videos.xml?channel_id=…)\n• yt: поисковый запрос — свежие видео\n• ai: тема — ИИ пишет пост сам",
                "Sources, one per line:\n• an RSS/Atom feed link (news site, blog, YouTube channel: youtube.com/feeds/videos.xml?channel_id=…)\n• yt: search query — fresh videos\n• ai: topic — AI writes the post itself"))
            val sources = edit("https://lenta.ru/rss/news\nyt: обзор смартфонов\nai: советы по продуктивности", PostCore.sourcesText(p.sources), multiline = true)
            add(text(a, t("Куда", "Where"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            if (d.targets.isEmpty()) muted(t("Сначала добавьте канал или группу", "Add a channel or group first"))
            d.targets.forEach { tg -> check(tg.label, tg.chat in targets) { v -> if (v) targets.add(tg.chat) else targets.remove(tg.chat) } }
            spinner(t("Как часто", "How often"), listOf(30 to t("30 мин", "30 min"), 60 to t("1 ч", "1 h"), 120 to t("2 ч", "2 h"), 240 to t("4 ч", "4 h"), 480 to t("8 ч", "8 h"), 1440 to t("сутки", "day"), 0 to t("вручную", "manual")),
                if (p.everyMin in setOf(30, 60, 120, 240, 480, 1440, 0)) p.everyMin else 120) { p.everyMin = it }
            spinner(t("Постов за раз", "Posts per run"), (1..5).map { it to it.toString() }, p.perRun.coerceIn(1, 5)) { p.perRun = it }
            val hours = edit(t("Часы работы, например 8-23", "Active hours, e.g. 8-23"), "${p.fromHour}-${p.toHour}").apply { isSingleLine = true }
            check(t("ИИ переписывает материал в пост", "AI rewrites the material into a post"), p.rewrite) { p.rewrite = it }
            val style = edit(t("Стиль для ИИ: тон, длина, эмодзи, хэштеги, подпись…", "Style for AI: tone, length, emoji, hashtags, signature…"), p.style, multiline = true)
            check(t("Присылать на проверку перед публикацией", "Send for review before publishing"), p.review) { p.review = it }
            check(t("С картинкой из источника", "With the source picture"), p.withImage) { p.withImage = it }
            check(t("Кнопка со ссылкой на источник", "Button linking to the source"), p.withButton) { p.withButton = it }
            button(t("Сохранить", "Save"), primary = true) {
                val list = PostCore.parseSources(sources.text.toString())
                if (list.isEmpty()) {
                    a.toast(t("Добавьте хотя бы один источник", "Add at least one source"))
                    return@button
                }
                p.name = name.text.toString().trim().ifBlank { t("Автонаполнение", "Auto-filling") }
                p.sources = list
                p.targets = targets.toList()
                p.style = style.text.toString().trim()
                Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})").find(hours.text.toString())?.let { m ->
                    p.fromHour = m.groupValues[1].toInt().coerceIn(0, 23)
                    p.toHour = m.groupValues[2].toInt().coerceIn(0, 23)
                }
                BotPosts.update(botId) { x ->
                    val i = x.plans.indexOfFirst { it.id == p.id }
                    if (i >= 0) x.plans[i] = p else x.plans.add(p)
                    Unit
                }
                dismiss()
                rerender()
            }
        }
    }
}

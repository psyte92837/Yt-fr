/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText

/**
 * "Ideas for my business": the list of businesses; a business opens its stream of AI digests from fresh
 * videos and comments; "+" or "Edit" opens the short setup. Extra "id" opens one business directly.
 */
class IdeasActivity : LumenBase() {
    private var current = "" // "" = list of businesses
    private var editing = false
    private var busy = ""
    private var draft = IdeasConfig()
    private var businessEdit: EditText? = null
    private var queriesEdit: EditText? = null
    private var expanded = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = intent.getStringExtra("id").orEmpty()
        val all = Ideas.configs()
        if (current.isNotEmpty() && all.none { it.id == current }) current = ""
        // first visit: straight to the setup
        if (current.isEmpty() && all.isEmpty()) newBusiness()
    }

    private fun newBusiness() {
        draft = IdeasConfig()
        current = draft.id
        editing = true
    }

    private fun backToList() {
        current = ""
        editing = false
        expanded = 0
        render()
    }

    override fun navigateUp() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            editing && Ideas.config(current) != null -> {
                editing = false
                render()
            }
            (editing || current.isNotEmpty()) && Ideas.configs().isNotEmpty() -> backToList()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun readFields() {
        businessEdit?.let { draft.business = it.text.toString().trim() }
        queriesEdit?.let { draft.userQueries = IdeasLogic.splitUser(it.text.toString()).toMutableList() }
    }

    private fun render() {
        val c = Col(this)
        if (busy.isNotEmpty()) c.working(busy)
        val cfg = Ideas.config(current)
        when {
            editing -> setup(c)
            cfg != null -> dashboard(c, cfg)
            else -> list(c)
        }
        val title = if (cfg != null && !editing) cfg.business.take(40) else if (editing) (if (cfg == null) t("Новый бизнес", "New business") else t("Настройка", "Setup")) else t("💡 Идеи для бизнеса", "💡 Business ideas")
        val fab = if (!editing && cfg == null) t("＋  Добавить бизнес", "＋  Add business") to {
            newBusiness()
            render()
        } else null
        page(title, c.root, keepScroll = true, fab = fab)
    }

    private fun list(c: Col) {
        val all = Ideas.configs()
        c.p(t("Для каждого бизнеса Lumen отдельно следит за свежими видео и комментариями на своём рынке и пишет сводки идей.",
            "For each business Lumen watches fresh videos and comments in its market and writes idea digests."), color = Palette.MUTED)
        if (all.isEmpty()) {
            c.empty(t("Пока нет бизнесов", "No businesses yet"), t("Добавьте первый: пара слов о бизнесе и рынок.", "Add the first one: a few words and a market."),
                t("Добавить бизнес", "Add business")) {
                newBusiness()
                render()
            }
        }
        for (b in all) {
            val last = Ideas.digests(b.id).firstOrNull()
            val card = c.card {
                add(text(ctx, "💡 " + b.business, Type.TITLE_M, bold = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, 0)
                muted(Ideas.countryName(b.country) + " · " + freq(b.hours) + if (b.enabled) "" else t(" · на паузе", " · paused"))
                if (last != null) {
                    add(text(ctx, Fmt.dateTime(last.at), Type.LABEL, bold = true, color = Palette.ACCENT), 8)
                    add(text(ctx, IdeasLogic.headline(last.text), Type.BODY).apply { maxLines = 2 }, 2)
                } else muted(t("Сводок пока нет", "No digests yet"))
            }
            card.setOnClickListener {
                current = b.id
                editing = false
                expanded = 0
                render()
            }
        }
    }

    private fun setup(c: Col) {
        c.p(t("Lumen будет искать свежие видео и комментарии на выбранном рынке и присылать сводку: тренды, боли клиентов и идеи для вашего бизнеса.",
            "Lumen will look for fresh videos and comments in the chosen market and send a digest: trends, customer pains and ideas for your business."), color = Palette.MUTED)
        c.add(text(this, t("Чем занимается ваш бизнес?", "What does your business do?"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        businessEdit = c.edit(t("Например: кофейня с выпечкой, онлайн-школа английского, ремонт квартир", "E.g. a coffee shop with bakery, an online English school, home renovation"),
            draft.business, multiline = true)
        c.add(text(this, t("Рынок", "Market"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        val countries = Regions.countries.filter { it.first.isNotEmpty() }.sortedBy { if (it.first == "US") 0 else 1 }
        c.add(choiceChips(this, countries, draft.country) {
            readFields()
            draft.country = it
            render()
        }, 4)
        c.add(text(this, t("Язык поиска", "Search language"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        val market = IdeasLogic.langFor(draft.country)
        val auto = t("Как на рынке", "Market's") + if (market.isNotEmpty()) " · " + Regions.languageName(market) else ""
        c.add(choiceChips(this, listOf("" to auto) + Regions.languages.filter { it.first.isNotEmpty() }, draft.lang) {
            readFields()
            draft.lang = it
            render()
        }, 4)
        c.muted(t("Пишите о бизнесе и запросы на любом языке: Lumen ищет видео и читает комментарии на языке рынка, запросы переведёт ИИ.",
            "Describe the business and queries in any language: Lumen searches videos and reads comments in the market language, AI translates the queries."))
        c.add(text(this, t("Как часто проверять", "How often to check"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        val hours = listOf(6, 12, 24, 168)
        c.add(segBar(this, listOf(t("6 ч", "6 h"), t("12 ч", "12 h"), t("День", "Day"), t("Неделя", "Week")), hours.indexOf(draft.hours).coerceAtLeast(0)) {
            readFields()
            draft.hours = hours[it]
            render()
        }, 4)
        c.add(text(this, t("Поисковые запросы (необязательно)", "Search queries (optional)"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        queriesEdit = c.edit(t("По одному в строке, можно по-русски. Пусто — ИИ подберёт на языке рынка", "One per line, any language. Empty: AI picks them in the market language"),
            draft.userQueries.ifEmpty { draft.queries }.joinToString("\n"), multiline = true)
        filters(c)
        if (Provider.active() == null) {
            c.muted(t("AI не подключён: видео и комментарии будут собираться, но сводка и подбор запросов требуют API (Настройки → AI).",
                "AI is not connected: videos and comments will be collected, but digests and query suggestions need an API (Settings → AI)."))
        }
        c.add(btn(this, t("🚀  Начать поиск идей", "🚀  Start finding ideas"), style = BtnStyle.FILLED) { start() }, 20).apply {
            minimumHeight = dp(56)
        }
        val saved = Ideas.config(draft.id)
        if (saved != null || Ideas.configs().isNotEmpty()) c.button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) {
            if (saved != null) {
                draft = saved
                editing = false
                render()
            } else backToList()
        }
        if (saved != null) c.button(t("Удалить бизнес", "Delete business"), style = BtnStyle.TEXT) { delete(saved) }
    }

    private fun seg(c: Col, label: String, labels: List<String>, keys: List<String>, value: String, set: (String) -> Unit) {
        c.add(text(this, label, Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.add(segBar(this, labels, keys.indexOf(value).coerceAtLeast(0)) {
            readFields()
            set(keys[it])
            render()
        }, 4)
    }

    private fun filters(c: Col) {
        c.h2(t("Фильтры", "Filters"))
        seg(c, t("Период видео", "Video period"), listOf(t("Сутки", "Day"), t("Неделя", "Week"), t("Месяц", "Month")),
            listOf("today", "week", "month"), draft.period) { draft.period = it }
        seg(c, t("Сортировка", "Sort"), listOf(t("Новые", "Newest"), t("Популярные", "Popular"), t("По теме", "Relevant")),
            listOf("date", "views", "relevance"), draft.sort) { draft.sort = it }
        seg(c, t("Длительность", "Duration"), listOf(t("Любая", "Any"), t("< 4 мин", "< 4 min"), t("4–20 мин", "4–20 min"), t("> 20 мин", "> 20 min")),
            listOf("any", "short", "medium", "long"), draft.duration) { draft.duration = it }
        c.check(t("Без Shorts", "No Shorts"), draft.noShorts) {
            readFields()
            draft.noShorts = it
        }
        seg(c, t("Видео за проверку", "Videos per check"), listOf("15", "30", "50", "100"),
            listOf("15", "30", "50", "100"), draft.limit.toString()) { draft.limit = it.toInt() }
        seg(c, t("Комментариев к видео", "Comments per video"), listOf(t("Нет", "None"), "30", "60", "100"),
            listOf("0", "30", "60", "100"), draft.comments.toString()) { draft.comments = it.toInt() }
        val lang = draft.searchLang
        seg(c, t("Язык сводки", "Digest language"),
            listOf(t("Язык приложения", "App language"), t("Язык рынка", "Market language") + if (lang.isNotEmpty()) " · " + Regions.languageName(lang) else ""),
            listOf("app", "market"), draft.digestLang) { draft.digestLang = it }
    }

    private fun delete(b: IdeasConfig) {
        val items = arrayOf(t("Удалить, видео оставить в проектах", "Delete, keep videos in projects"), t("Удалить вместе с собранными видео", "Delete with collected videos"))
        android.app.AlertDialog.Builder(this).setTitle(t("Удалить «${b.business.take(40)}»?", "Delete \"${b.business.take(40)}\"?"))
            .setItems(items) { _, i ->
                Ideas.delete(this, b.id, withProject = i == 1)
                backToList()
            }.setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun start() {
        if (busy.isNotEmpty()) return
        readFields()
        if (draft.business.length < 3) {
            toast(t("Опишите бизнес хотя бы парой слов", "Describe the business in a few words"))
            return
        }
        val cfg = draft.copy(queries = draft.queries.toMutableList(), userQueries = draft.userQueries.toMutableList(), enabled = true)
        val saved = Ideas.config(cfg.id)
        // unchanged setup: keep the queries already picked/translated
        val keep = saved != null && saved.queries.isNotEmpty() && saved.business == cfg.business &&
            saved.searchLang == cfg.searchLang && saved.country == cfg.country &&
            (cfg.userQueries.isEmpty() && saved.userQueries.isEmpty() || cfg.userQueries == saved.userQueries || cfg.userQueries == saved.queries)
        if (keep && cfg.userQueries == saved?.queries) cfg.userQueries = saved.userQueries.toMutableList()
        busy = when {
            keep -> t("Запускаю…", "Starting…")
            cfg.userQueries.isEmpty() -> t("Подбираю запросы на языке рынка…", "Picking queries in the market language…")
            else -> t("Перевожу запросы на язык рынка…", "Translating queries into the market language…")
        }
        render()
        bgTask(t("Идеи: запуск", "Ideas: start"), {
            if (keep) cfg.queries = saved!!.queries.toMutableList() else Ideas.prepareQueries(cfg)
            Ideas.apply(Lumen.app, cfg)
            cfg
        }, onError = {
            busy = ""
            render()
            err(it)
        }) {
            draft = it
            current = it.id
            editing = false
            busy = ""
            render()
            refresh()
        }
    }

    private fun refresh() {
        if (busy.isNotEmpty()) return
        busy = t("Ищу свежие видео и читаю комментарии… Это займёт пару минут", "Looking for fresh videos and reading comments… This takes a couple of minutes")
        render()
        val id = current
        bgTask(t("Идеи: обновление", "Ideas: refresh"), { Ideas.refreshNow(Lumen.app, id) }, onError = {
            busy = ""
            render()
            err(it)
        }) { d ->
            busy = ""
            expanded = 0
            if (d == null) toast(if (Provider.active() == null) t("Видео собраны. Для сводки подключите AI.", "Videos collected. Connect AI for a digest.")
            else t("Новых видео пока нет", "No new videos yet"))
            render()
        }
    }

    private fun dashboard(c: Col, cfg: IdeasConfig) {
        val p = Store.project(cfg.projectId)
        c.card {
            add(text(ctx, cfg.business, Type.TITLE_M, bold = true).apply { maxLines = 3 }, 0)
            muted(t("Рынок: ", "Market: ") + Ideas.countryName(cfg.country) +
                (if (cfg.searchLang.isNotEmpty()) " · " + t("язык: ", "language: ") + Regions.languageName(cfg.searchLang) else "") +
                " · " + freq(cfg.hours) + if (cfg.enabled) "" else t(" · на паузе", " · paused"))
            muted(t("Фильтры: ", "Filters: ") + filtersLine(cfg))
            if (cfg.queries.isNotEmpty()) muted(t("Запросы: ", "Queries: ") + cfg.queries.joinToString(", "))
            if (cfg.userQueries.isNotEmpty() && cfg.userQueries != cfg.queries) muted(t("Ваши запросы: ", "Your queries: ") + cfg.userQueries.joinToString(", "))
            if (p != null) muted(t("Видео: ", "Videos: ") + p.videoCount + " · " + t("последняя проверка: ", "last check: ") +
                if (p.lastRunAt > 0) Fmt.dateTime(p.lastRunAt) else "—")
            add(btn(ctx, t("Обновить сейчас", "Update now"), style = BtnStyle.FILLED) { refresh() }, 12)
            row(
                btn(ctx, t("Настроить", "Edit"), style = BtnStyle.TONAL) {
                    draft = cfg
                    editing = true
                    render()
                },
                btn(ctx, if (cfg.enabled) t("Пауза", "Pause") else t("Возобновить", "Resume"), style = BtnStyle.OUTLINED) { toggle(cfg) }
            )
            if (p != null) button(t("Все собранные видео →", "All collected videos →"), style = BtnStyle.TEXT) { openProjectScreen(p) }
        }
        if (Provider.active() == null) {
            c.card {
                add(text(ctx, t("AI не подключён", "AI is not connected"), Type.TITLE_M, bold = true), 0)
                muted(t("Видео и комментарии собираются, но сводку пишет ИИ.", "Videos and comments are collected, but the AI writes the digest."))
                button(t("Подключить", "Connect"), style = BtnStyle.TONAL) {
                    startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true))
                }
            }
        }
        if (Ideas.configs().size > 1) c.button(t("← Все бизнесы", "← All businesses"), style = BtnStyle.TEXT) { backToList() }
        val list = Ideas.digests(cfg.id)
        c.h2(t("Сводки", "Digests") + if (list.isNotEmpty()) " (${list.size})" else "")
        if (list.isEmpty()) {
            c.muted(t("Первая сводка появится после проверки. Нажмите «Обновить сейчас», чтобы не ждать.",
                "The first digest appears after a check. Tap \"Update now\" not to wait."))
        }
        list.forEachIndexed { i, d ->
            val open = i == expanded
            val card = c.card {
                add(text(ctx, Fmt.dateTime(d.at), Type.LABEL, bold = true, color = Palette.ACCENT), 0)
                muted(t("Видео: ${d.videos} · комментариев: ${d.comments}", "Videos: ${d.videos} · comments: ${d.comments}"))
                if (open) {
                    add(text(ctx, d.text, Type.BODY).apply { setTextIsSelectable(true) }, 8)
                    row(
                        btn(ctx, t("Поделиться", "Share"), style = BtnStyle.TEXT) { shareText(t("Идеи для бизнеса", "Business ideas"), d.text) },
                        btn(ctx, t("Копировать", "Copy"), style = BtnStyle.TEXT) {
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("ideas", d.text))
                            toast(t("Скопировано", "Copied"))
                        }
                    )
                } else {
                    add(text(ctx, IdeasLogic.headline(d.text), Type.BODY).apply { maxLines = 2 }, 6)
                }
            }
            if (!open) card.setOnClickListener {
                expanded = i
                render()
            }
        }
    }

    private fun toggle(cfg: IdeasConfig) {
        if (cfg.enabled) {
            Ideas.stop(this, cfg.id)
            render()
            return
        }
        cfg.enabled = true
        bgTask(t("Идеи: создание проекта", "Ideas: creating the project"), { Ideas.apply(Lumen.app, cfg) }, onError = { err(it) }) { render() }
    }

    companion object {
        fun open(ctx: Context, id: String = "") = ctx.startActivity(intent(ctx, id))

        fun intent(ctx: Context, id: String = ""): Intent = Intent(ctx, IdeasActivity::class.java).apply { if (id.isNotEmpty()) putExtra("id", id) }

        fun filtersLine(c: IdeasConfig): String = listOfNotNull(
            when (c.period) { "today" -> t("за сутки", "last day"); "month" -> t("за месяц", "last month"); else -> t("за неделю", "last week") },
            when (c.sort) { "views" -> t("популярные", "popular"); "relevance" -> t("по теме", "relevant"); else -> t("новые", "newest") },
            when (c.duration) { "short" -> t("< 4 мин", "< 4 min"); "medium" -> t("4–20 мин", "4–20 min"); "long" -> t("> 20 мин", "> 20 min"); else -> null },
            if (c.noShorts) t("без Shorts", "no Shorts") else null,
            t("до ${c.limit} видео", "up to ${c.limit} videos"),
            if (c.comments > 0) t("${c.comments} комм.", "${c.comments} comments") else t("без комментариев", "no comments"),
            if (c.digestLang == "market") t("сводка на языке рынка", "digest in market language") else null
        ).joinToString(" · ")

        fun freq(hours: Int): String = when (hours) {
            168 -> t("раз в неделю", "weekly")
            24 -> t("раз в день", "daily")
            else -> t("каждые $hours ч", "every $hours h")
        }
    }
}

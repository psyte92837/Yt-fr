/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/** Shared editor for search filters (used on project creation and in project settings). */
fun Col.filtersEditor(f: SearchFilters) {
    spinner(
        t("Сортировка", "Sort"),
        listOf(
            "relevance" to t("По релевантности", "Relevance"), "date" to t("Сначала новые", "Newest first"),
            "views" to t("По просмотрам", "Most viewed"), "rating" to t("По рейтингу (лайки/просмотры)", "Rating (likes/views)")
        ),
        f.sort
    ) { f.sort = it }
    val from = edt(ctx, t("С (ГГГГ-ММ-ДД)", "From (YYYY-MM-DD)"), f.from)
    val to = edt(ctx, t("По (ГГГГ-ММ-ДД)", "To (YYYY-MM-DD)"), f.to)
    val custom = rowOf(ctx, from, to)
    spinner(
        t("Период", "Period"),
        listOf(
            "any" to t("За всё время", "Any time"), "hour" to t("Последний час", "Last hour"),
            "today" to t("Сегодня (24 ч)", "Today (24h)"), "week" to t("Неделя", "Week"),
            "month" to t("Месяц", "Month"), "year" to t("Год", "Year"), "custom" to t("Свой диапазон", "Custom range")
        ),
        f.period
    ) {
        f.period = it
        custom.visibility = if (it == "custom") android.view.View.VISIBLE else android.view.View.GONE
    }
    add(custom, 4)
    custom.visibility = if (f.period == "custom") android.view.View.VISIBLE else android.view.View.GONE
    from.addTextChangedListener(SimpleWatcher { f.from = it.trim() })
    to.addTextChangedListener(SimpleWatcher { f.to = it.trim() })
    spinner(
        t("Длительность", "Duration"),
        listOf(
            "any" to t("Любая", "Any"), "short" to t("Короткие (< 4 мин)", "Short (< 4 min)"),
            "medium" to t("Средние (4–20 мин)", "Medium (4–20 min)"), "long" to t("Длинные (> 20 мин)", "Long (> 20 min)")
        ),
        f.duration
    ) { f.duration = it }
    check(t("Исключить Shorts", "Exclude Shorts"), f.excludeShorts) { f.excludeShorts = it }
    spinner(
        t("Лимит результатов", "Result limit"),
        listOf(20, 50, 100, 200, 500).map { it to it.toString() },
        listOf(20, 50, 100, 200, 500).minByOrNull { kotlin.math.abs(it - f.limit) } ?: 50
    ) { f.limit = it }
}

/** One-line summary of non-default search filters, e.g. "Сначала новые · Неделя · без Shorts · до 100". */
fun filtersSummary(f: SearchFilters): String {
    val parts = mutableListOf<String>()
    when (f.sort) {
        "date" -> parts.add(t("Сначала новые", "Newest first"))
        "views" -> parts.add(t("По просмотрам", "Most viewed"))
        "rating" -> parts.add(t("По рейтингу", "By rating"))
    }
    when (f.period) {
        "hour" -> parts.add(t("Последний час", "Last hour"))
        "today" -> parts.add(t("Сегодня", "Today"))
        "week" -> parts.add(t("Неделя", "Week"))
        "month" -> parts.add(t("Месяц", "Month"))
        "year" -> parts.add(t("Год", "Year"))
        "custom" -> parts.add(listOf(f.from, f.to).joinToString("…") { it.ifBlank { "?" } })
    }
    when (f.duration) {
        "short" -> parts.add(t("короткие", "short"))
        "medium" -> parts.add(t("средние", "medium"))
        "long" -> parts.add(t("длинные", "long"))
    }
    if (f.excludeShorts) parts.add(t("без Shorts", "no Shorts"))
    parts.add(t("до ", "up to ") + f.limit)
    return parts.joinToString(" · ")
}

class SimpleWatcher(val on: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = on(s?.toString() ?: "")
}

/** Launcher screen: list of projects (topics). */
class LumenActivity : LumenBase() {
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.channels(this)
        if (!Settings.wizardDone) {
            startActivity(Intent(this, WizardActivity::class.java))
        }
        val crash = L.lastCrashAt()
        if (crash > Lumen.prefs.getLong("crash_seen_at", 0)) {
            Lumen.prefs.edit().putLong("crash_seen_at", crash).apply()
            confirm(t("Приложение закрылось с ошибкой", "The app crashed"),
                t("Отправить отчёт разработчику? В нём нет ключей и имён.", "Send a report to the developer? It contains no keys or names."),
                t("Отправить", "Send")) {
                shareText("Lumen crash report", L.report(this, context = "crash at ${Fmt.dateTime(crash)}", errorsOnly = true, maxLines = 200))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = Col(this)
        c.muted(t("Наблюдайте за темой на YouTube и получайте сводки. Все данные — только на устройстве.",
            "Watch a topic on YouTube and get summaries. All data stays on this device."))
        val shortcuts = mutableListOf<android.view.View>()
        if (Settings.mode == "player") {
            shortcuts.add(chip(this, t("▶ Плеер NewPipe", "▶ NewPipe player")) {
                startActivity(Intent().setClassName(packageName, "org.schabi.newpipe.MainActivity"))
            })
        }
        shortcuts.add(chip(this, t("🌐 Браузер", "🌐 Browser")) { BrowserActivity.open(this) })
        c.chips(shortcuts)
        if (Provider.active() == null) {
            c.card {
                p(t("AI не подключён — поиск, сбор и локальная аналитика работают. Для сводок подключите API.",
                    "AI is not connected — search, collection and local analytics work. Connect an API for summaries."), 13f)
                button(t("Подключить AI", "Connect AI")) { startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true)) }
            }
        }
        val all = Store.projects()
        val list = all.filter { it.archived == showArchived }
        c.h2(if (showArchived) t("Архив", "Archive") + " (${list.size})" else t("Проекты", "Projects") + " (${list.size})")
        if (list.isEmpty()) {
            if (showArchived) c.muted(t("В архиве пусто.", "The archive is empty."))
            else c.empty(t("Пока нет проектов", "No projects yet"),
                t("Проект — это тема на YouTube: слово, фраза или ссылка на видео, канал, плейлист.",
                    "A project is a YouTube topic: a word, a phrase or a link to a video, channel or playlist."),
                t("Создать проект", "Create project")) { startActivity(Intent(this, NewProjectActivity::class.java)) }
        }
        for (p in list) {
            val card = c.card {
                val title = text(ctx, p.name.ifBlank { p.phrase }, Type.TITLE_M, bold = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val head = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                    if (p.newCount > 0) addView(text(ctx, t("новых: ", "new: ") + p.newCount, 12f, bold = true, color = Palette.ON_ACCENT).apply {
                        background = roundBg(Palette.ACCENT, 10f, ctx)
                        setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
                    })
                    addView(iconBtn(ctx, "⋮", Palette.MUTED) { projectMenu(p) }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply {
                        marginEnd = -ctx.dp(8)
                    })
                }
                add(head, 0)
                muted("«${p.phrase}»" + if (p.domain.isNotBlank()) " · ${p.domain}" else "")
                muted(t("Видео: ", "Videos: ") + p.videoCount + " · " + t("последний запуск: ", "last run: ") + Fmt.dateTime(p.lastRunAt) +
                    if (p.monitor.enabled && Settings.monitoringAllowed) " · ⏱" else "")
            }
            card.contentDescription = p.name
            card.setOnClickListener { startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", p.id)) }
            card.setOnLongClickListener {
                projectMenu(p)
                true
            }
        }
        val archived = all.count { it.archived }
        if (showArchived || archived > 0) {
            c.button(if (showArchived) t("← К проектам", "← Projects") else t("Архив ($archived)", "Archive ($archived)"), style = BtnStyle.TEXT) {
                showArchived = !showArchived
                render()
            }
        }
        page(
            "Lumen", c.root, back = false,
            actions = listOf(
                "🌐" to { BrowserActivity.open(this) },
                "🐞" to { startActivity(Intent(this, SettingsActivity::class.java).putExtra("logs", true)) },
                "⚙" to { startActivity(Intent(this, SettingsActivity::class.java)) }
            ),
            fab = t("＋  Новый проект", "＋  New project") to { startActivity(Intent(this, NewProjectActivity::class.java)) }
        )
    }

    private fun projectMenu(p: Project) {
        val items = arrayOf(
            t("Открыть", "Open"), t("Дублировать", "Duplicate"),
            if (p.archived) t("Вернуть из архива", "Unarchive") else t("В архив", "Archive"),
            t("Удалить", "Delete")
        )
        AlertDialog.Builder(this).setTitle(p.name).setItems(items) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", p.id))
                1 -> {
                    Store.duplicate(p.id)
                    render()
                }
                2 -> {
                    p.archived = !p.archived
                    Store.save(p)
                    Monitoring.apply(this, p)
                    render()
                }
                3 -> confirm(t("Удалить проект?", "Delete project?"), p.name, t("Удалить", "Delete")) {
                    Monitoring.cancel(this, p.id)
                    Store.delete(p.id)
                    render()
                }
            }
        }.show()
    }
}

/** First-run wizard. Every step is optional and can be changed later in settings. */
class WizardActivity : LumenBase() {
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun next() {
        step++
        if (step > 5) {
            Settings.wizardDone = true
            finish()
        } else {
            render()
        }
    }

    private fun render() {
        val c = Col(this)
        c.steps(step, 6)
        c.muted(t("Шаг ${step + 1} из 6 · всё можно изменить позже", "Step ${step + 1} of 6 · everything can be changed later"))
        when (step) {
            0 -> {
                c.title("Lumen")
                c.p(t("Язык интерфейса", "Interface language"))
                c.button("Русский", Settings.lang == "ru") {
                    Settings.lang = "ru"
                    next()
                }
                c.button("English", Settings.lang == "en") {
                    Settings.lang = "en"
                    next()
                }
            }
            1 -> {
                c.title(t("Режим", "Mode"))
                c.p(t("Оставить плеер NewPipe (просмотр, фон, скачивание) или только аналитику?",
                    "Keep the NewPipe player (watching, background, downloads) or analytics only?"))
                c.button(t("Только аналитика", "Analytics only"), Settings.mode == "analytics") {
                    Settings.mode = "analytics"
                    next()
                }
                c.button(t("Аналитика + плеер NewPipe", "Analytics + NewPipe player"), Settings.mode == "player") {
                    Settings.mode = "player"
                    next()
                }
            }
            2 -> {
                c.title(t("AI-провайдер", "AI provider"))
                c.p(t("Для уточнения темы и сводок нужен ваш API-ключ (Gemini, OpenRouter, OpenAI, Claude, DeepSeek, Groq или локальная модель). Ключ хранится зашифрованно только на устройстве.",
                    "Domain refinement and summaries need your own API key (Gemini, OpenRouter, OpenAI, Claude, DeepSeek, Groq or a local model). The key is stored encrypted on this device only."))
                c.button(t("Подключить сейчас", "Connect now"), true) {
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
                    next()
                }
                c.button(t("Позже", "Later")) { next() }
            }
            3 -> {
                c.title(t("Мониторинг тем", "Topic monitoring"))
                c.p(t("Приложение может само проверять новые видео по расписанию и присылать уведомление. Включается отдельно для каждого проекта.",
                    "The app can check for new videos on a schedule and notify you. It is enabled per project."))
                var hours = Settings.defaultMonitorHours
                c.spinner(t("Частота по умолчанию", "Default frequency"),
                    listOf(6 to t("каждые 6 ч", "every 6 h"), 12 to t("каждые 12 ч", "every 12 h"), 24 to t("ежедневно", "daily"), 168 to t("еженедельно", "weekly")),
                    hours) { hours = it }
                c.button(t("Разрешить мониторинг", "Allow monitoring"), true) {
                    Settings.monitoringAllowed = true
                    Settings.defaultMonitorHours = hours
                    next()
                }
                c.button(t("Нет", "No")) {
                    Settings.monitoringAllowed = false
                    next()
                }
            }
            4 -> {
                c.title(t("Транскрибация через AI", "AI transcription"))
                c.p(t("Если у видео нет субтитров, его можно расшифровать через Gemini (по ссылке на видео). Это расходует токены вашего ключа — перед запуском покажем оценку.",
                    "If a video has no subtitles it can be transcribed with Gemini (via the video link). This uses tokens of your key — an estimate is shown before running."))
                c.button(t("Разрешить", "Allow"), true) {
                    Settings.aiTranscriptionAllowed = true
                    next()
                }
                c.button(t("Не использовать", "Don't use")) {
                    Settings.aiTranscriptionAllowed = false
                    next()
                }
            }
            5 -> {
                c.title(t("Резервные копии", "Backups"))
                c.p(t("По умолчанию всё хранится только в приложении, экспорт — вручную. Можно выбрать папку для резервных копий в Настройках → Резервная копия.",
                    "By default everything stays inside the app and export is manual. You can choose a backup folder in Settings → Backup."))
                c.button(t("Готово", "Done"), true) { next() }
            }
        }
        if (step > 0) c.button(t("← Назад", "← Back"), style = BtnStyle.TEXT) {
            step--
            render()
        }
        page("Lumen", c.root, back = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Settings.wizardDone = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

/** Enter a phrase → optional AI domain refinement → filters → create project and search. */
class NewProjectActivity : LumenBase() {
    private val filters = SearchFilters()
    private val chosen = linkedSetOf<String>()
    private var domain = ""
    private var domains: List<Triple<String, String, List<String>>> = emptyList()
    private var phrase = ""
    private var extraText = ""
    private var suggestions: List<String> = emptyList()
    private var busy = false
    private var filtersOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val c = Col(this)
        c.title(t("Что исследуем?", "What to research?"))
        c.card {
            add(text(ctx, t("Есть ссылка на видео, канал или плейлист? Вставьте — анализ запустится сразу (или поделитесь ей из приложения YouTube → Lumen).",
                "Have a video, channel or playlist link? Paste it — the analysis starts at once (or share it from the YouTube app → Lumen)."), 13f, color = Palette.MUTED), 0)
            val link = edt(ctx, "https://youtube.com/…")
            add(link, 8)
            add(btn(ctx, t("Анализировать ссылку", "Analyse link"), primary = true) {
                if (QuickAnalysis.start(this@NewProjectActivity, link.text.toString())) finish()
            }, 8)
        }
        val input = c.edit(t("Слово или фраза, напр. «электромобили»", "Word or phrase, e.g. \"electric cars\""), phrase)
        input.addTextChangedListener(SimpleWatcher { phrase = it })
        val ai = Provider.active()
        c.button(if (busy) t("AI думает…", "AI is thinking…") else t("✨ Уточнить доменную область через AI", "✨ Refine domain with AI")) {
            if (phrase.isBlank()) return@button toast(t("Введите фразу", "Enter a phrase"))
            if (ai == null) {
                confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ сейчас?", "Connect an API key now?")) {
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
                }
                return@button
            }
            busy = true
            render()
            Lumen.async({ AiTasks.domains(phrase.trim()) }, {
                busy = false
                render()
                err(it)
            }) {
                busy = false
                domains = it
                render()
            }
        }
        c.button(t("🔎 Подсказки YouTube (как ищут люди)", "🔎 YouTube suggestions (how people search)"), style = BtnStyle.OUTLINED) {
            if (phrase.isBlank()) return@button toast(t("Введите фразу", "Enter a phrase"))
            Lumen.async({ RecEngine.suggestions(phrase.trim()) }, ::err) { list ->
                suggestions = list
                render()
            }
        }
        if (suggestions.isNotEmpty()) {
            c.card {
                add(text(ctx, t("Подсказки поиска YouTube — отметьте, чтобы добавить в запросы", "YouTube search suggestions — check to add as queries"), 13f, bold = true, color = Palette.MUTED), 0)
                suggestions.forEach { q -> add(chk(ctx, q, q in chosen) { on -> if (on) chosen.add(q) else chosen.remove(q) }, 0) }
            }
        }
        if (domains.isNotEmpty()) {
            c.h2(t("Варианты доменных областей", "Domain options"))
            c.muted(t("Отметьте запросы, которые войдут в проект", "Check the queries to include in the project"))
            for ((name, desc, queries) in domains) {
                c.card {
                    val head = chk(ctx, name, domain == name) {}.apply { typeface = Type.medium }
                    add(head, 0)
                    muted(desc)
                    val boxes = queries.map { q ->
                        chk(ctx, q, q in chosen) { on -> if (on) chosen.add(q) else chosen.remove(q) }.also { add(it, 0) }
                    }
                    head.setOnCheckedChangeListener { _, on ->
                        if (on) domain = name else if (domain == name) domain = ""
                        boxes.forEach { it.isChecked = on }
                    }
                }
            }
        }
        c.h2(t("Дополнительные запросы", "Extra queries"))
        val extra = c.edit(t("По одному в строке (необязательно)", "One per line (optional)"), extraText, multiline = true)
        extra.addTextChangedListener(SimpleWatcher { extraText = it })
        c.section(t("Фильтры поиска", "Search filters"), filtersSummary(filters), filtersOpen, { open ->
            filtersOpen = open
            render()
        }) {
            filtersEditor(filters)
        }
        val create: () -> Unit = create@{
            if (phrase.isBlank()) return@create toast(t("Введите фразу", "Enter a phrase"))
            val qs = (chosen + extra.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }).toMutableList()
            if (qs.isEmpty()) qs.add(phrase.trim())
            val p = Project(name = phrase.trim().replaceFirstChar { it.uppercase() }, phrase = phrase.trim(), domain = domain,
                queries = qs, filters = filters)
            p.monitor.hours = Settings.defaultMonitorHours
            p.collect.aiTranscribe = false
            Store.save(p)
            startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", p.id).putExtra("autoSearch", true))
            finish()
        }
        page(t("Новый проект", "New project"), c.root, fab = t("Создать и искать", "Create and search") to create)
    }
}

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
import android.view.ViewGroup
import android.widget.LinearLayout

/** Shared editor for search filters (used on project creation and in project settings). */
fun Col.filtersEditor(f: SearchFilters, regions: Boolean = true) {
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
    if (regions) {
        spinner(t("Страна поиска (необязательно)", "Search country (optional)"), Regions.countries, f.country) { f.country = it }
        spinner(t("Язык оригинала видео (необязательно)", "Original language of videos (optional)"), Regions.languages, f.origLang) { f.origLang = it }
        muted(t("Страна меняет выдачу YouTube как для зрителя из неё. Язык определяется по названию и описанию; если язык не ясен, видео остаётся.",
            "The country changes YouTube results as for a viewer there. The language is guessed from the title and description; unclear videos are kept."))
    }
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
    if (f.country.isNotBlank()) parts.add(Regions.countryName(f.country))
    if (f.origLang.isNotBlank()) parts.add(Regions.languageName(f.origLang))
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
        AutoBackup.maybe(this)
        try {
            Updates.schedule(this)
        } catch (e: Exception) {
            L.w("update", "cannot schedule update check: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && uri != null) restoreFrom(uri) { render() }
    }

    private fun render() {
        val c = Col(this)
        Updates.available?.let { u ->
            val broken = System.currentTimeMillis() - Updates.brokenAt < 3 * 86_400_000L
            val card = c.card {
                add(text(ctx, if (broken) t("Готово исправление для YouTube", "A YouTube fix is ready") else t("Доступно обновление", "Update available"), Type.TITLE_M, bold = true), 0)
                muted(t("Сборка ${u.build}. Нажмите, чтобы установить", "Build ${u.build}. Tap to install"))
            }
            card.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra("update", true)) }
        }
        if (Provider.active() == null) {
            c.card {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val msg = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(ctx, t("AI не подключён", "AI is not connected"), Type.TITLE_M, bold = true))
                    addView(text(ctx, t("Поиск, сбор и аналитика работают. Сводки нужен API.", "Search, collection and analytics work. Summaries need an API."), 13f, color = Palette.MUTED))
                }
                row.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(btn(ctx, t("Подключить", "Connect"), style = BtnStyle.TONAL) {
                    startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true))
                })
                add(row, 0)
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
            if (!showArchived) {
                c.button(t("Восстановить из резервной копии…", "Restore from a backup…"), style = BtnStyle.TEXT) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(AutoBackup.pickIntent(), REQ_RESTORE)
                }
                c.muted(t("Lumen сам сохраняет копию в «Загрузки/Lumen/${AutoBackup.FILE_NAME}». После переустановки выберите этот файл.",
                    "Lumen keeps a copy in Downloads/Lumen/${AutoBackup.FILE_NAME}. After reinstalling, pick that file."))
            }
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
                val added = p.runs.lastOrNull()?.added ?: 0
                if (added > 0) add(text(ctx, t("В последнем запуске добавлено: +$added", "Added in the last run: +$added"), 13f, bold = true, color = Palette.ACCENT), 4)
                add(kmapButton(ctx, p.id), 8).layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) }
            }
            card.contentDescription = p.name
            card.setOnClickListener { openProjectScreen(p) }
            card.setOnLongClickListener {
                projectMenu(p)
                true
            }
        }
        val archived = all.count { it.archived }
        page(
            "Lumen", c.root, back = false,
            actions = listOf("⋮" to { homeMenu(archived) }),
            fab = t("＋  Новый проект", "＋  New project") to { startActivity(Intent(this, NewProjectActivity::class.java)) },
            footer = bottomNav(this, listOf(
                Ic.HOME to t("Проекты", "Projects"), Ic.WEB to t("Браузер", "Browser"),
                Ic.SPEAKER to t("Озвучка", "Read aloud"), Ic.MOVIE to t("Студия", "Studio"),
                Ic.SETTINGS to t("Настройки", "Settings")
            ), 0) {
                when (it) {
                    1 -> BrowserActivity.open(this)
                    2 -> TtsActivity.open(this)
                    3 -> StudioActivity.open(this)
                    4 -> startActivity(Intent(this, SettingsActivity::class.java))
                    else -> Unit
                }
            }
        )
    }

    private fun homeMenu(archived: Int) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add(t("Задать домен для наблюдения", "Set a domain to watch") to {
            startActivity(Intent(this, NewProjectActivity::class.java).putExtra("watch", true))
        })
        if (Settings.mode == "player") {
            items.add(t("Плеер NewPipe", "NewPipe player") to {
                startActivity(Intent().setClassName(packageName, "org.schabi.newpipe.MainActivity"))
            })
        }
        if (showArchived || archived > 0) {
            items.add((if (showArchived) t("К проектам", "Projects") else t("Архив ($archived)", "Archive ($archived)")) to {
                showArchived = !showArchived
                render()
            })
        }
        items.add(t("Восстановить из резервной копии…", "Restore from a backup…") to {
            @Suppress("DEPRECATION")
            startActivityForResult(AutoBackup.pickIntent(), REQ_RESTORE)
        })
        items.add("🎬 Lumen Studio" to { StudioActivity.open(this) })
        items.add(t("Справка и о проекте", "Help and about") to { HelpActivity.open(this, "about") })
        items.add(t("Журнал и отчёт об ошибке", "Logs and bug report") to {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("logs", true))
        })
        AlertDialog.Builder(this).setTitle("Lumen").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun projectMenu(p: Project) {
        val items = arrayOf(
            t("Открыть", "Open"), t("🗺 Карта знаний", "🗺 Knowledge map"), t("Дублировать", "Duplicate"),
            if (p.archived) t("Вернуть из архива", "Unarchive") else t("В архив", "Archive"),
            t("Удалить", "Delete")
        )
        AlertDialog.Builder(this).setTitle(p.name).setItems(items) { _, which ->
            when (which) {
                0 -> openProjectScreen(p)
                1 -> KnowledgeMapActivity.open(this, p.id)
                2 -> {
                    Store.duplicate(p.id)
                    render()
                }
                3 -> {
                    p.archived = !p.archived
                    Store.save(p)
                    Monitoring.apply(this, p)
                    render()
                }
                4 -> confirm(t("Удалить проект?", "Delete project?"), p.name, t("Удалить", "Delete")) {
                    Monitoring.cancel(this, p.id)
                    Store.delete(p.id)
                    render()
                }
            }
        }.show()
    }
}

const val REQ_RESTORE = 61

/** Restores a picked backup, then reports and calls [done]. API keys are not in backups. */
fun LumenBase.restoreFrom(uri: android.net.Uri, done: () -> Unit) {
    Lumen.async({ AutoBackup.restore(this, uri); Store.projects().size }, ::err) { n ->
        toast(t("Восстановлено проектов: $n. API-ключ AI нужно ввести заново.", "Projects restored: $n. Re-enter the AI API key."))
        done()
    }
}

/** First-run wizard. Every step is optional and can be changed later in settings. */
class WizardActivity : LumenBase() {
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step", 0) ?: 0
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    private fun next() {
        step++
        if (step >= WizardSteps.KEYS.size) {
            Settings.wizardDone = true
            finish()
        } else {
            render()
        }
    }

    private fun render() {
        val c = Col(this)
        val total = WizardSteps.KEYS.size
        c.steps(step, total)
        c.muted(t("Шаг ${step + 1} из $total · всё можно изменить позже", "Step ${step + 1} of $total · everything can be changed later"))
        when (WizardSteps.KEYS[step]) {
            "lang" -> {
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
                c.h2(t("Уже пользовались Lumen?", "Used Lumen before?"))
                c.button(t("Восстановить из резервной копии…", "Restore from a backup…"), style = BtnStyle.OUTLINED) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(AutoBackup.pickIntent(), REQ_RESTORE)
                }
                c.muted(t("Файл ${AutoBackup.FILE_NAME} лежит в «Загрузки/Lumen» (или в папке копий, выбранной в настройках).",
                    "The file ${AutoBackup.FILE_NAME} is in Downloads/Lumen (or in the backup folder chosen in settings)."))
            }
            "look" -> {
                c.title(t("Оформление", "Appearance"))
                c.p(t("Нажмите на готовую градиентную тему — она применится сразу.", "Tap a ready-made gradient theme — it applies right away."))
                themeTiles(c)
                c.button(t("Далее", "Next"), true) { next() }
            }
            "mode" -> {
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
            "ai" -> {
                c.title(t("AI-провайдер", "AI provider"))
                c.p(t("Для уточнения темы и сводок нужен ваш API-ключ (Gemini, OpenRouter, OpenAI, Claude, DeepSeek, Groq или локальная модель). Ключ хранится зашифрованно только на устройстве.",
                    "Domain refinement and summaries need your own API key (Gemini, OpenRouter, OpenAI, Claude, DeepSeek, Groq or a local model). The key is stored encrypted on this device only."))
                c.button(t("Подключить сейчас", "Connect now"), true) {
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
                    next()
                }
                c.button(t("Позже", "Later")) { next() }
            }
            "monitor" -> {
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
            "transcribe" -> {
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
            "backup" -> {
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

    /** Two-column grid of gradient swatches; a tap saves the theme and repaints the wizard on the same step. */
    private fun themeTiles(c: Col) {
        Palette.gradientNames.chunked(2).forEach { pair ->
            val tiles = pair.map { (key, label) -> themeTile(key, label) }.toMutableList()
            if (tiles.size == 1) tiles += android.view.View(this)
            c.add(rowOf(this, *tiles.toTypedArray()), 10)
        }
    }

    private fun themeTile(key: String, label: String): android.view.View {
        val selected = Settings.style == key
        val prev = Palette.gradientPreview(key)
        val night = isNight()
        val bg = if (prev != null) {
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, prev.first)
        } else {
            android.graphics.drawable.GradientDrawable().apply { setColor(if (night) 0xFF1D1B20.toInt() else 0xFFF7F2FA.toInt()) }
        }
        bg.cornerRadius = dp(16).toFloat()
        bg.setStroke(dp(if (selected) 3 else 1), if (selected) Palette.ACCENT else Palette.OUTLINE)
        val dark = prev?.second ?: night
        val onTile = if (dark) 0xFFFFFFFF.toInt() else 0xFF1D1B20.toInt()
        return text(this, (if (selected) "✓ " else "") + label, Type.BODY, bold = true, color = onTile).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            minHeight = dp(88)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(this@WizardActivity, bg, onTile)
            isClickable = true
            contentDescription = label
            setOnClickListener {
                if (Settings.style != key) {
                    Settings.style = key
                    recreate()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && uri != null) restoreFrom(uri) { next() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Settings.wizardDone = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

/** Order of first-run wizard steps; the appearance step comes right after the language. */
object WizardSteps {
    val KEYS = listOf("lang", "look", "mode", "ai", "monitor", "transcribe", "backup")
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

    /** Started from "Set a domain to watch": the project is created with monitoring switched on. */
    private val watch by lazy { intent.getBooleanExtra("watch", false) }
    private var aiFilter = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val c = Col(this)
        if (watch) {
            c.title(t("Домен для наблюдения", "Domain to watch"))
            c.muted(t("Введите тему и уточните домен через AI. Lumen будет сам проверять новые видео " +
                "каждые ${Settings.defaultMonitorHours} ч и присылать уведомление" + (if (Provider.active() != null) " со сводкой." else "."),
                "Enter a topic and refine the domain with AI. Lumen will check for new videos " +
                    "every ${Settings.defaultMonitorHours} h and notify you" + (if (Provider.active() != null) " with a summary." else ".")))
        } else {
            c.title(t("Что исследуем?", "What to research?"))
        }
        if (!watch) c.card {
            add(text(ctx, t("Есть ссылка на видео, канал или плейлист? Вставьте — анализ запустится сразу (или поделитесь ей из приложения YouTube → Lumen). " +
                "Для видео можно определить стиль видеоряда и монтажа (тратит токены AI).",
                "Have a video, channel or playlist link? Paste it — the analysis starts at once (or share it from the YouTube app → Lumen). " +
                    "For a video you can detect its footage and editing style (uses AI tokens)."), 13f, color = Palette.MUTED), 0)
            val link = edt(ctx, "https://youtube.com/…")
            add(link, 8)
            add(btn(ctx, t("Анализировать ссылку", "Analyse link"), primary = true) {
                if (QuickAnalysis.start(this@NewProjectActivity, link.text.toString())) finish()
            }, 8)
            add(btn(ctx, t("🎨 Определить стиль видео", "🎨 Detect video style"), style = BtnStyle.TONAL) {
                if (QuickAnalysis.start(this@NewProjectActivity, link.text.toString(), "style")) finish()
            }, 4)
        }
        val input = c.edit(t("Слово или фраза, напр. «электромобили»", "Word or phrase, e.g. \"electric cars\""), phrase)
        input.addTextChangedListener(SimpleWatcher { phrase = it })
        val ai = Provider.active()
        if (busy) c.working(t("AI думает…", "AI is thinking…"))
        c.button(t("✨ Уточнить доменную область через AI", "✨ Refine domain with AI")) {
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
        if (watch) {
            c.h2(t("Наблюдение", "Watching"))
            c.check(t("AI-отсев: добавлять только видео по теме", "AI filter: add only on-topic videos"), aiFilter) { aiFilter = it }
            c.muted(if (Provider.active() != null) t("Новые видео проверяются AI на соответствие теме и домену, остальные отбрасываются.",
                "New videos are checked by AI against the topic and domain; the rest are dropped.")
                else t("AI не подключён — отсев будет по ключевым словам темы.", "AI is not connected — the filter will use the topic's keywords."))
            c.spinner(t("Страна поиска (необязательно)", "Search country (optional)"), Regions.countries, filters.country) { filters.country = it }
            c.spinner(t("Язык оригинала видео (необязательно)", "Original language of videos (optional)"), Regions.languages, filters.origLang) {
                filters.origLang = it
            }
        }
        c.section(t("Фильтры поиска", "Search filters"), filtersSummary(filters), filtersOpen, { open ->
            filtersOpen = open
            render()
        }) {
            filtersEditor(filters, regions = !watch)
        }
        val create: () -> Unit = create@{
            if (phrase.isBlank()) return@create toast(t("Введите фразу", "Enter a phrase"))
            val qs = (chosen + extra.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }).toMutableList()
            if (qs.isEmpty()) qs.add(phrase.trim())
            val p = Project(name = phrase.trim().replaceFirstChar { it.uppercase() }, phrase = phrase.trim(), domain = domain,
                queries = qs, filters = filters)
            p.monitor.hours = Settings.defaultMonitorHours
            p.collect.aiTranscribe = false
            if (watch) {
                Settings.monitoringAllowed = true
                p.monitor.enabled = true
                p.monitor.aiSummary = Provider.active() != null
                p.monitor.aiFilter = aiFilter
            }
            Store.save(p)
            if (watch) Monitoring.apply(this, p)
            startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", p.id).putExtra("autoSearch", true))
            finish()
        }
        page(
            if (watch) t("Наблюдение", "Watch") else t("Новый проект", "New project"), c.root,
            fab = (if (watch) t("Начать наблюдение", "Start watching") else t("Создать и искать", "Create and search")) to create
        )
    }
}

/** Focused collections open their own result screen, everything else the project. */
fun android.app.Activity.openProjectScreen(p: Project) {
    if (p.quickMode == "harvest") HarvestActivity.open(this, p.id) else startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", p.id))
}

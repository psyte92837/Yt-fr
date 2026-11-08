/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout

/** Global settings: language, mode, AI providers, prompts, monitoring, backups. */
class SettingsActivity : LumenBase() {
    private var section = -1 // -1 = list of sections
    private var direct = false // opened straight into a section (AI banner, logs): back leaves the screen
    private val aiUi = AiSettings(this) { render() }
    private var probeText = ""
    private var probeBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("general", false)) section = 0
        if (intent.getBooleanExtra("studio", false)) section = 4
        if (intent.getBooleanExtra("ai", false)) section = 1
        if (intent.getBooleanExtra("logs", false)) section = 2
        if (intent.getBooleanExtra("quick", false)) section = 3
        if (intent.getBooleanExtra("update", false)) section = 3
        direct = section >= 0
        render()
    }

    override fun navigateUp() {
        if (section >= 0 && !direct) {
            section = -1
            render()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = navigateUp()

    private val sections get() = listOf(
        Triple(t("Общие", "General"), t("язык, режим, мониторинг, сбор", "language, mode, monitoring, collection"), 0),
        Triple(t("AI и промпты", "AI and prompts"), t("провайдер, ключи, шаблоны запросов", "provider, keys, prompt templates"), 1),
        Triple(t("Данные", "Data"), t("резервная копия, журнал, статистика AI", "backup, logs, AI usage"), 2),
        Triple(t("О приложении", "About"), t("версия, проверка YouTube, быстрый анализ", "version, YouTube check, instant analysis"), 3),
        Triple("Lumen Studio", t("эффекты, B-roll, качество экспорта, другой ИИ, подсказки", "effects, B-roll, export quality, another AI, hints"), 4)
    )

    private fun render() {
        val c = Col(this)
        if (section < 0) {
            for ((name, summary, id) in sections) {
                val card = c.card {
                    add(text(ctx, name, Type.TITLE_M, bold = true), 0)
                    muted(summary)
                }
                card.contentDescription = name
                card.setOnClickListener {
                    section = id
                    render()
                }
            }
            page(t("Настройки", "Settings"), c.root)
            return
        }
        when (section) {
            0 -> general(c)
            1 -> {
                aiUi.ai(c)
                c.h2(t("Промпты", "Prompts"))
                aiUi.prompts(c, promptNames())
            }
            2 -> {
                backup(c)
                c.h2(t("Кадры анимаций Редактора", "Animation frames of the editor"))
                val root = StudioEditStore.root(applicationContext)
                val size = root.listFiles().orEmpty().sumOf { AnimRender.size(it) }
                c.muted(t("Занято: ", "Used: ") + (size / 1_048_576) + t(" МБ", " MB") + if (size > 200L * 1_048_576) t(" — много, очистите неиспользуемые", " — a lot, clean the unused ones") else "")
                c.button(t("🧹 Очистить неиспользуемые кадры", "🧹 Clean the unused frames"), style = BtnStyle.TONAL) {
                    root.listFiles().orEmpty().forEach { d -> StudioEditStore.load(d)?.let { p -> runCatching { AnimRender.clean(d, p.blocks) } } }
                    toast(t("Готово", "Done"))
                    render()
                }
                c.h2(t("Статистика AI", "AI usage"))
                aiUi.usage(c)
                c.h2(t("Журнал", "Logs"))
                logs(c)
            }
            3 -> {
                about(c)
                c.h2(t("Быстрый анализ", "Instant analysis"))
                quick(c)
            }
            else -> aiUi.studio(c)
        }
        page(sections[section].first, c.root)
    }

    /** Labels of the analysis prompts; the other prompts are listed by key. */
    private fun promptNames(): Map<String, String> {
        return mapOf(
            "domain" to t("Уточнение доменной области (JSON)", "Domain refinement (JSON)"),
            "video_summary" to t("Сводка по видео", "Video summary"),
            "chunk_summary" to t("Сводка фрагмента (map)", "Chunk summary (map)"),
            "project_summary" to t("Общая сводка проекта", "Project summary"),
            "clusters" to t("Кластеры", "Clusters"),
            "sentiment" to t("Тональность комментариев", "Comment sentiment"),
            "chat" to t("Чат с проектом", "Project chat"),
            "relevance" to t("Мониторинг · фильтр новых видео по теме", "Monitoring · topic filter of new videos"),
            "watch_translate" to t("Мониторинг · перевод темы на другие языки", "Monitoring · topic translation"),
            "watch_digest" to t("Мониторинг · сводка новых видео", "Monitoring · digest of new videos"),
            "p1_insights" to t("P1 · Извлечение аудитории", "P1 · Audience extraction"),
            "p2_clusters" to t("P2 · Кластеризация", "P2 · Clustering"),
            "p3_qualify" to t("P3 · SEO-квалификация темы", "P3 · SEO topic qualification"),
            "p4_brief" to t("P4 · SEO-бриф", "P4 · SEO brief"),
            "p5_article" to t("P5 · Статья", "P5 · Article"),
            "p6_quality" to t("P6 · Контроль качества", "P6 · Quality check"),
            "p7_ideas" to t("P7 · Идеи контента", "P7 · Content ideas"),
            "dossier_channel" to t("Досье · канал и его видео", "Dossier · channel and its videos"),
            "dossier_overview" to t("Досье · общая картина", "Dossier · overview"),
            "diagram_flow" to t("Схема · блок-схема процесса", "Diagram · process flowchart"),
            "diagram_mind" to t("Схема · карта идей", "Diagram · mind map"),
            "diagram_video" to t("Схема · структура видео", "Diagram · video structure"),
            "pains" to t("Боли и кейсы людей", "Pains and cases of people"),
            CommentDigest.KIND to t("Сводка комментариев видео", "Video comment digest"),
            VocAi.TASK to t("Голос аудитории · разбор комментариев (общий для сводки, тональности, чата)", "Audience voice · comment extraction (shared by digest, tone, chat)"),
            "kmap_aspect" to t("Карта знаний · сводка по ветке", "Knowledge map · branch summary"),
            KMap.GAPS to t("Карта знаний · пробелы в знаниях", "Knowledge map · knowledge gaps"),
            KMap.PATH to t("Карта знаний · путь обучения", "Knowledge map · learning path"),
            "ideas_queries" to t("Идеи для бизнеса · подбор запросов", "Business ideas · queries"),
            "ideas_digest" to t("Идеи для бизнеса · сводка", "Business ideas · digest"),
            "ideas_translate" to t("Идеи для бизнеса · перевод запросов", "Business ideas · query translation"),
        ) + algoPromptLabels()
    }


    private fun general(c: Col) {
        c.spinner(t("Язык интерфейса", "Interface language"), listOf("ru" to "Русский", "en" to "English"), Settings.lang) {
            if (it != Settings.lang) {
                Settings.lang = it
                render()
            }
        }
        c.h2(t("Оформление", "Appearance"))
        c.spinner(t("Тема", "Theme"), listOf("system" to t("Как в системе", "Follow system"), "light" to t("Светлая", "Light"),
            "dark" to t("Тёмная", "Dark"), "black" to t("Чёрная (AMOLED)", "Black (AMOLED)")), Settings.theme) {
            if (it != Settings.theme) {
                Settings.theme = it
                recreate()
            }
        }
        c.spinner(t("Готовая градиентная тема", "Gradient theme"), Palette.gradientNames, Settings.style) {
            if (it != Settings.style) {
                Settings.style = it
                recreate()
            }
        }
        c.spinner(t("Цвет акцента", "Accent colour"), Palette.accentNames, Settings.accent) {
            if (it != Settings.accent) {
                Settings.accent = it
                recreate()
            }
        }
        c.spinner(t("Размер текста", "Text size"), listOf(85, 100, 115, 130, 150, 175, 200).map { it to "$it%" }, Settings.textScale) {
            if (it != Settings.textScale) {
                Settings.textScale = it
                recreate()
            }
        }
        c.h2(t("Работа", "Behaviour"))
        c.spinner(t("Режим", "Mode"), listOf("analytics" to t("Только аналитика", "Analytics only"), "player" to t("Аналитика + плеер", "Analytics + player")), Settings.mode) {
            Settings.mode = it
        }
        c.h2(t("Отчёты", "Reports"))
        c.check(t("Ссылки на каналы в отчётах", "Channel links in reports"), Settings.reportChannelLinks) {
            Settings.reportChannelLinks = it
        }
        c.h2(t("ИИ-агенты на телефоне", "AI agents on the phone"))
        c.check(t("Разрешить ИИ-агентам (Gemini и др.) читать проекты", "Let AI agents (Gemini etc.) read projects"), Settings.agentAccess) {
            Settings.agentAccess = it
        }
        c.p(
            if (android.os.Build.VERSION.SDK_INT >= 36) t("Через Android AppFunctions: агент вызывает функции Lumen на устройстве (список проектов, отчёт, всё собранное, поиск видео, выводы ИИ). Только чтение.", "Via Android AppFunctions: the agent calls Lumen functions on the device (projects, report, collected data, video search, AI insights). Read-only.")
            else t("Нужен Android 16 или новее (Android AppFunctions).", "Needs Android 16 or newer (Android AppFunctions)."),
            Type.LABEL, Palette.MUTED,
        )
        c.h2(t("Уведомления", "Notifications"))
        c.button(t("⚙ Менеджер задач (зависшие, автоостановка)", "⚙ Task manager (hung tasks, auto-stop)"), style = BtnStyle.OUTLINED) { TasksActivity.open(this) }
        c.check(t("Уведомлять о завершении задач (сбор, анализ, граф, озвучка)", "Notify when tasks finish (collection, analysis, graph, speech)"), Settings.notifyDone) {
            Settings.notifyDone = it
        }
        c.check(t("Вибрация при завершении", "Vibrate when finished"), Settings.vibrateDone) {
            Settings.vibrateDone = it
            if (it) getSystemService(android.os.Vibrator::class.java)?.let { v ->
                if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(Notify.VIBRATION, -1))
                else @Suppress("DEPRECATION") v.vibrate(Notify.VIBRATION, -1)
            }
        }
        c.h2(t("Мониторинг", "Monitoring"))
        c.check(t("Разрешить мониторинг тем по расписанию", "Allow scheduled topic monitoring"), Settings.monitoringAllowed) {
            Settings.monitoringAllowed = it
            Monitoring.reapplyAll(this)
        }
        c.spinner(t("Частота по умолчанию для новых проектов", "Default frequency for new projects"),
            listOf(6 to t("каждые 6 ч", "every 6 h"), 12 to t("каждые 12 ч", "every 12 h"), 24 to t("ежедневно", "daily"), 168 to t("еженедельно", "weekly")),
            Settings.defaultMonitorHours) { Settings.defaultMonitorHours = it }
        c.h2(t("Сбор", "Collection"))
        c.check(t("Разрешить AI-транскрибацию видео без субтитров (Gemini)", "Allow AI transcription of videos without subtitles (Gemini)"), Settings.aiTranscriptionAllowed) {
            Settings.aiTranscriptionAllowed = it
        }
        c.check(t("Хранить авторов комментариев как псевдонимы (u_xxxx), без имён", "Store comment authors as pseudonyms (u_xxxx), not names"), Settings.anonymizeAuthors) {
            Settings.anonymizeAuthors = it
        }
        c.spinner(t("Пауза между запросами к YouTube", "Delay between YouTube requests"),
            listOf(300 to "0.3 s", 800 to "0.8 s", 1500 to "1.5 s", 3000 to "3 s", 6000 to "6 s"), Settings.requestDelayMs) {
            Settings.requestDelayMs = it
        }
        c.muted(t("Большая пауза снижает риск временной блокировки со стороны YouTube.", "A longer delay lowers the risk of temporary blocking by YouTube."))
        c.spinner(t("Параллельные запросы (каналы, видео, AI)", "Parallel requests (channels, videos, AI)"),
            listOf(1 to t("1 (последовательно)", "1 (sequential)"), 2 to "2", 3 to "3", 4 to "4", 6 to "6", 8 to "8"), Settings.parallel) {
            Settings.parallel = it
        }
        c.muted(t("Ускоряет сбор и анализ. Пауза между запросами к YouTube делится между потоками. При ошибках лимита уменьшите.", "Speeds up collection and analysis. The YouTube delay is shared across threads. Lower it if you hit rate limits."))
        c.button(t("Пройти мастер настройки заново", "Run the setup wizard again"), style = BtnStyle.TEXT) {
            Settings.wizardDone = false
            startActivity(Intent(this, WizardActivity::class.java))
        }
    }


    private fun backup(c: Col) {
        c.muted(t("Копия содержит проекты, видео, расшифровки, комментарии и AI-результаты. API-ключи не копируются.",
            "A backup contains projects, videos, transcripts, comments and AI results. API keys are not included."))
        c.card {
            add(chk(ctx, t("Автокопия каждые 3 ч (переживает удаление приложения)", "Auto backup every 3 h (survives uninstalling)"), AutoBackup.enabled) {
                AutoBackup.enabled = it
            }, 0)
            muted(if (AutoBackup.lastAt > 0) t("Последняя: ", "Last: ") + Fmt.dateTime(AutoBackup.lastAt) + " → " + AutoBackup.lastPlace
                else t("Ещё не делалась. Место: папка копий ниже или «Загрузки/Lumen».", "Not made yet. Place: the backup folder below or Downloads/Lumen."))
            add(btn(ctx, t("Сделать автокопию сейчас", "Auto backup now"), style = BtnStyle.TEXT) {
                bgTask(t("Резервная копия", "Backup"), { AutoBackup.runNow(this@SettingsActivity) }, ::err) {
                    toast(t("Сохранено: ", "Saved: ") + AutoBackup.lastPlace)
                    render()
                }
            }, 4)
        }
        val folder = Settings.backupTreeUri
        c.card {
            p(t("Папка для копий: ", "Backup folder: ") + (folder?.let { Uri.parse(it).lastPathSegment } ?: t("не выбрана", "not chosen")))
        }
        c.button(t("Выбрать папку", "Choose folder"), style = BtnStyle.TONAL) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        }
        c.button(t("Сделать копию сейчас", "Back up now"), primary = true) {
            val f = Settings.backupTreeUri
            if (f == null) {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE, "lumen-backup-${Fmt.isoDay(System.currentTimeMillis())}.zip"),
                    REQ_BACKUP_FILE
                )
            } else {
                backupToTree(Uri.parse(f))
            }
        }
        c.h2(t("Настройки и промпты", "Settings and prompts"))
        c.muted(t("Отдельный файл только с настройками и вашими промптами — перенести их на другой телефон. Ключей в нём нет.",
            "A separate file with settings and your prompts only — to move them to another phone. No keys inside."))
        c.row(
            btn(this, t("⬇ Экспорт", "⬇ Export"), style = BtnStyle.TONAL) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "lumen-settings-${Fmt.isoDay(System.currentTimeMillis())}.json"), REQ_PREFS_OUT)
            },
            btn(this, t("Импорт…", "Import…"), style = BtnStyle.OUTLINED) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PREFS_IN)
            }
        )
        c.h2(t("Восстановление", "Restore"))
        c.button(t("Восстановить из копии…", "Restore from backup…"), style = BtnStyle.OUTLINED) {
            confirm(t("Восстановить?", "Restore?"), t("Файлы из копии заменят совпадающие данные.", "Files from the backup will overwrite matching data.")) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE)
            }
        }
    }

    private fun backupToTree(tree: Uri) {
        Lumen.async({
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val name = "lumen-backup-${Fmt.isoDay(System.currentTimeMillis())}.zip"
            val doc = DocumentsContract.createDocument(contentResolver, parent, "application/zip", name)
                ?: throw IllegalStateException("Cannot create file")
            contentResolver.openOutputStream(doc)?.use { Store.backup(it) }
            name
        }, ::err) { toast(t("Сохранено: ", "Saved: ") + it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PREFS_OUT -> Lumen.async({
                contentResolver.openOutputStream(uri)?.use { it.write(PrefsIo.export(Lumen.prefs.all).toByteArray()) }
            }, ::err) { toast(t("Настройки сохранены", "Settings saved")) }
            REQ_PREFS_IN -> Lumen.async({
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                PrefsIo.parse(text) ?: throw IllegalArgumentException(t("Это не файл настроек Lumen", "This is not a Lumen settings file"))
            }, ::err) { values ->
                confirm(t("Импортировать настройки?", "Import the settings?"),
                    t("Параметров: ${values.size}. Совпадающие заменятся; ключи AI не меняются.", "Values: ${values.size}. Matching ones are replaced; AI keys stay."),
                    t("Импорт", "Import")) {
                    val before = PrefsIo.parse(PrefsIo.export(Lumen.prefs.all)).orEmpty()
                    fun apply(m: Map<String, Any>) {
                        val e = Lumen.prefs.edit()
                        m.forEach { (k, v) ->
                            @Suppress("UNCHECKED_CAST")
                            when (v) {
                                is Boolean -> e.putBoolean(k, v)
                                is Int -> e.putInt(k, v)
                                is Long -> e.putLong(k, v)
                                is Float -> e.putFloat(k, v)
                                is String -> e.putString(k, v)
                                is Set<*> -> e.putStringSet(k, v as Set<String>)
                            }
                        }
                        e.apply()
                    }
                    undoable(t("Настройки импортированы", "Settings imported"), apply = { apply(values); render() },
                        revert = {
                            val e = Lumen.prefs.edit()
                            values.keys.filter { it !in before }.forEach { e.remove(it) }
                            e.apply()
                            apply(before)
                            render()
                        })
                }
            }
            REQ_LOG_FILE -> Lumen.async({ contentResolver.openOutputStream(uri)?.use { it.write(L.report(this, maxLines = 2000).toByteArray()) } }, ::err) {
                toast(t("Сохранено", "Saved"))
            }
            REQ_TREE -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Settings.backupTreeUri = uri.toString()
                render()
            }
            REQ_BACKUP_FILE -> bgTask(t("Резервная копия", "Backup"), { contentResolver.openOutputStream(uri)?.use { Store.backup(it) } }, ::err) {
                toast(t("Копия сохранена", "Backup saved"))
            }
            REQ_RESTORE -> bgTask(t("Восстановление", "Restore"), { AutoBackup.restore(this, uri) }, ::err) {
                toast(t("Восстановлено", "Restored"))
            }
        }
    }

    private fun quick(c: Col) {
        val q = QuickConfig.load()
        val save = { QuickConfig.save(q) }
        c.muted(t("Что считается сразу, когда вы делитесь видео, каналом или плейлистом из YouTube или вставляете ссылку.",
            "What runs at once when you share a video, channel or playlist from YouTube or paste a link."))
        c.h2(t("Видео", "Video"))
        c.check(t("Метаданные, расшифровка, комментарии", "Metadata, transcript, comments"), q.collect) { q.collect = it; save() }
        c.check(t("AI-сводка видео", "AI video summary"), q.aiSummary) { q.aiSummary = it; save() }
        c.check(t("Статистика канала, ссылки, плейлисты", "Channel stats, links, playlists"), q.channelStats) { q.channelStats = it; save() }
        c.spinner(t("Видео канала для статистики", "Channel videos for stats"), listOf(15, 30, 50, 100, 200).map { it to it.toString() }, q.channelRecent) { q.channelRecent = it; save() }
        c.h2(t("Канал и плейлист", "Channel & playlist"))
        c.spinner(t("Импортировать видео канала", "Import channel videos"), listOf(20, 50, 100, 200, 500).map { it to it.toString() }, q.channelImport) { q.channelImport = it; save() }
        c.spinner(t("Видео плейлиста", "Playlist videos"), listOf(50, 100, 200, 500, 1000).map { it to it.toString() }, q.playlistLimit) { q.playlistLimit = it; save() }
        c.spinner(t("Собрать комментарии и расшифровки у топ-N видео", "Collect comments & transcripts of top N videos"), listOf(0, 5, 10, 20, 50).map { it to it.toString() }, q.collectTop) { q.collectTop = it; save() }
        c.check(t("AI-сводка по каналу", "AI channel summary"), q.aiChannelSummary) { q.aiChannelSummary = it; save() }
        c.check(t("AI-сводка по проекту", "AI project summary"), q.aiProjectSummary) { q.aiProjectSummary = it; save() }
        c.check(t("Фильтр по теме (AI): только подходящие видео и каналы", "Topic filter (AI): only on-topic videos and channels"), q.topicFilter) { q.topicFilter = it; save() }
        c.muted(t("AI определяет доменную область по видео и отбрасывает остальное. Без AI — по ключевым словам. Каждый ответ виден в «Ответах AI».",
            "AI states the domain from the videos and drops the rest. Keywords are used without AI. Every answer is in AI answers."))
        c.h2(t("Похожие и рекомендации", "Similar & recommended"))
        c.check(t("Искать похожие видео", "Find similar videos"), q.recs) { q.recs = it; save() }
        c.spinner(t("Сколько видео", "How many videos"), listOf(30, 50, 100, 200, 300, 500).map { it to it.toString() }, q.recVideos) { q.recVideos = it; save() }
        c.spinner(t("Глубина", "Depth"), (1..20).map { it to it.toString() }, q.recDepth) { q.recDepth = it; save() }
        c.h2(t("Граф каналов", "Channel graph"))
        c.check(t("Искать связанные каналы", "Find related channels"), q.channelGraph) { q.channelGraph = it; save() }
        c.spinner(t("Сколько каналов", "How many channels"), listOf(20, 50, 100, 200, 300).map { it to it.toString() }, q.graphChannels) { q.graphChannels = it; save() }
        c.spinner(t("Глубина", "Depth"), (1..6).map { it to it.toString() }, q.graphDepth) { q.graphDepth = it; save() }
        c.muted(t("100 видео + 100 каналов ≈ 400–600 запросов к YouTube (10–15 мин в фоне при паузе 0,8 с).",
            "100 videos + 100 channels ≈ 400–600 YouTube requests (10–15 min in background at 0.8 s delay)."))
    }

    private var errorsOnly = false

    private fun logs(c: Col) {
        c.button(t("🧯 Лог сбоя и зависаний", "🧯 Crash and freeze log"), style = BtnStyle.OUTLINED) { CrashLogActivity.open(this) }
        c.card {
            p(t("Отчёт для разработчика: версия, устройство, настройки без ключей, текущая задача и последние строки лога. Отправьте его в чат — по нему можно найти и исправить ошибку.",
                "Developer report: version, device, settings without keys, current task and recent log lines. Send it to the chat to get a bug fixed."))
            muted(t("API-ключи и токены вырезаются автоматически. Имена авторов комментариев в лог не пишутся.",
                "API keys and tokens are removed automatically. Comment author names are not logged."))
        }
        c.check(t("Только ошибки и предупреждения", "Errors and warnings only"), errorsOnly) {
            errorsOnly = it
            render()
        }
        c.row(
            btn(this, t("Поделиться отчётом", "Share report"), primary = true) {
                shareText("Lumen report", L.report(this, errorsOnly = errorsOnly))
            },
            btn(this, t("Копировать", "Copy"), style = BtnStyle.TONAL) {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Lumen report", L.report(this, errorsOnly = errorsOnly, maxLines = 200)))
                toast(t("Отчёт скопирован — вставьте его в чат", "Report copied — paste it into the chat"))
            }
        )
        c.row(
            btn(this, t("Сохранить файл", "Save file"), style = BtnStyle.OUTLINED) {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "lumen-report-${Fmt.isoDay(System.currentTimeMillis())}.txt"),
                    REQ_LOG_FILE
                )
            },
            btn(this, t("Очистить", "Clear"), style = BtnStyle.TEXT) {
                confirm(t("Очистить лог?", "Clear the log?"), "") {
                    L.clear()
                    render()
                }
            }
        )
        c.check(t("Подробный лог (запросы к YouTube и AI, без ключей)", "Verbose log (YouTube and AI requests, without keys)"), L.verbose) {
            L.verbose = it
        }
        c.muted(t("Размер лога: ", "Log size: ") + (L.sizeBytes() / 1024) + " KB" +
            (if (L.lastCrashAt() > 0) t(" · последний вылет: ", " · last crash: ") + Fmt.dateTime(L.lastCrashAt()) else ""))
        c.h2(t("Последние записи", "Latest entries"))
        val lines = L.tail(120, errorsOnly)
        c.card {
            add(text(ctx, if (lines.isEmpty()) "—" else lines.reversed().joinToString("\n"), 11f).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, 0)
        }
    }

    private fun about(c: Col) {
        c.title("Lumen")
        c.p(t("Мобильный инструмент анализа тем на YouTube: поиск, сбор метаданных, расшифровок и комментариев, локальная аналитика и AI-сводки через ваш собственный API-ключ.",
            "A mobile tool for YouTube topic research: search, metadata/transcript/comment collection, local analytics and AI summaries with your own API key."))
        c.p(t("Форк NewPipe. Лицензия GPL-3.0-or-later. Данные извлекаются через NewPipeExtractor без официального API. Все данные хранятся только на устройстве.",
            "A NewPipe fork. Licensed GPL-3.0-or-later. Data is extracted with NewPipeExtractor without the official API. All data stays on the device."))
        updates(c)
        c.h2(t("О проекте", "About the project"))
        c.button("NewPipe ↗", style = BtnStyle.TEXT) { openUrl("https://newpipe.net") }
        c.button(t("Лицензии библиотек NewPipe", "NewPipe library licenses"), style = BtnStyle.TEXT) {
            try {
                startActivity(Intent().setClassName(packageName, "org.schabi.newpipe.about.AboutActivity"))
            } catch (e: Exception) {
                openUrl("https://github.com/TeamNewPipe/NewPipe")
            }
        }
        c.add(View(this), 0).layoutParams = LinearLayout.LayoutParams(1, dp(24))
    }

    /** Version and live YouTube self-test. */
    private fun updates(c: Col) {
        c.h2(t("Версия", "Version"))
        val build = if (Updates.currentBuild > 0) Updates.currentBuild.toString() else t("локальная", "local")
        c.muted(org.schabi.newpipe.BuildConfig.VERSION_NAME + " · " + t("сборка ", "build ") + build +
            (if (Updates.currentCommit.isNotBlank()) " · " + UpdateLogic.shortSha(Updates.currentCommit) else "") +
            "\nNewPipeExtractor " + UpdateLogic.extractorLabel(Updates.currentExtractor))
        c.h2(t("Проверка YouTube", "YouTube check"))
        c.muted(t("Поиск, страница видео, потоки, комментарии и канал через NewPipeExtractor. Показывает, что сломалось, если YouTube что-то поменял.",
            "Search, video page, streams, comments and channel through NewPipeExtractor. Shows what broke if YouTube changed something."))
        if (probeText.isNotEmpty()) c.p(probeText, 13f)
        if (!probeBusy) c.button(t("Проверить работу YouTube", "Test YouTube now")) {
            probeBusy = true
            probeText = t("Проверка… (до минуты)", "Testing… (up to a minute)")
            render()
            Lumen.async({ YtProbe.run() }, onError = {
                probeBusy = false
                probeText = "✗ " + (it.message ?: "")
                render()
            }) { r ->
                probeBusy = false
                probeText = when (YtProbe.verdict(r)) {
                    "ok" -> t("✓ Всё работает", "✓ Everything works")
                    "blocked" -> t("YouTube блокирует запросы с этого IP (проверка на бота или сеть). Это не поломка Lumen.", "YouTube blocks requests from this IP (bot check or network). Lumen itself is fine.")
                    else -> t("✗ Есть поломка. Новая сборка с исправлением появится на странице релизов.", "✗ Something is broken. A build with a fix will appear on the releases page.")
                } + "\n\n" + YtProbe.report(r)
                render()
            }
        }
    }

    companion object {
        const val REQ_TREE = 51
        const val REQ_BACKUP_FILE = 52
        const val REQ_RESTORE = 53
        const val REQ_LOG_FILE = 54
        const val REQ_PREFS_OUT = 55
        const val REQ_PREFS_IN = 56
    }
}

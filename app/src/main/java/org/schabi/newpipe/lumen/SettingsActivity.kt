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
    private var section = 0
    private var testing = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("ai", false)) section = 1
        if (intent.getBooleanExtra("logs", false)) section = 4
        if (intent.getBooleanExtra("quick", false)) section = 6
        render()
    }

    private val sections get() = listOf(t("Общие", "General"), "AI", t("Промпты", "Prompts"), t("Резервная копия", "Backup"), t("Логи", "Logs"), t("О приложении", "About"), t("Быстрый анализ", "Instant analysis"), t("Статистика", "Usage"))

    private fun render() {
        val c = Col(this)
        when (section) {
            0 -> general(c)
            1 -> ai(c)
            2 -> prompts(c)
            3 -> backup(c)
            4 -> logs(c)
            5 -> about(c)
            6 -> quick(c)
            else -> usage(c)
        }
        page(t("Настройки", "Settings"), c.root, header = tabBar(this, sections, section) {
            section = it
            render()
        })
    }

    private var usageGroup = AiStats.Group.PROJECT
    private var usageDays = 30

    private fun usage(c: Col) {
        val recs = AiStats.all()
        c.muted(t("Все вызовы AI с самого начала: токены, запросы, время, ошибки. Только на устройстве; токены берутся из ответа провайдера.",
            "Every AI call since the beginning: tokens, requests, time, errors. On this device only; tokens come from the provider's reply."))
        c.chips(listOf(1 to t("сутки", "24 h"), 7 to t("7 дней", "7 days"), 30 to t("30 дней", "30 days"), 0 to t("всё время", "all time")).map { (d, l) ->
            chip(this, l, usageDays == d) { usageDays = d; render() }
        })
        c.chips(listOf(AiStats.Group.PROJECT to t("по проектам", "by project"), AiStats.Group.PROVIDER to t("по провайдерам", "by provider"),
            AiStats.Group.MODEL to t("по моделям", "by model"), AiStats.Group.TASK to t("по задачам", "by task"), AiStats.Group.DAY to t("по дням", "by day")).map { (g, l) ->
            chip(this, l, usageGroup == g) { usageGroup = g; render() }
        })
        val since = if (usageDays > 0) System.currentTimeMillis() - usageDays * 86_400_000L else 0L
        val names = Store.projects().associate { it.id to it.name.ifBlank { it.phrase } }
        val rows = AiStats.aggregate(recs, usageGroup, since, name = { names[it] ?: t("(удалён) ", "(deleted) ") + it.take(6) })
        val sum = AiStats.total(rows)
        fun cost(r: AiStats.Row) = Llm.estimateCost(r.inTokens.toInt(), r.outTokens.toInt())
        fun line(r: AiStats.Row) = t("запросов ", "calls ") + r.calls + " · in " + AiStats.fmtTokens(r.inTokens) + " / out " + AiStats.fmtTokens(r.outTokens) +
            (if (Settings.priceInPerM > 0 || Settings.priceOutPerM > 0) " · ≈$" + String.format(java.util.Locale.US, "%.3f", cost(r)) else "")
        c.card {
            add(text(ctx, t("Итого", "Total") + ": " + AiStats.fmtTokens(sum.tokens) + " " + t("токенов", "tokens"), Type.TITLE_M, bold = true), 0)
            muted(line(sum))
            muted("✓ ${sum.ok} · ✂ ${sum.truncated} · ✗ ${sum.errors} · " + t("среднее время ", "avg time ") + sum.avgMs + " ms")
        }
        if (rows.isEmpty()) c.muted(t("За этот период вызовов не было.", "No calls in this period."))
        val top = rows.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
        for (r in rows) c.card {
            add(rowOf(ctx, text(ctx, r.key, Type.BODY, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                text(ctx, AiStats.fmtTokens(r.tokens), Type.BODY, bold = true, color = Palette.ACCENT)), 0)
            add(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (r.tokens * 1000 / top).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
            }, 4)
            muted(line(r) + (if (r.errors + r.truncated > 0) " · ✂${r.truncated} ✗${r.errors}" else ""))
        }
        c.button(t("Журнал всех ответов AI", "Journal of all AI answers"), style = BtnStyle.OUTLINED) { AiJournalActivity.open(this) }
        c.button(t("Сбросить статистику", "Reset statistics"), style = BtnStyle.TEXT) {
            AlertDialog.Builder(this).setMessage(t("Удалить всю статистику использования?", "Delete all usage statistics?"))
                .setPositiveButton(t("Удалить", "Delete")) { _, _ -> AiStats.clear(); render() }
                .setNegativeButton(t("Отмена", "Cancel"), null).show()
        }
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
        c.spinner(t("Размер текста", "Text size"), listOf(85 to "85%", 100 to "100%", 115 to "115%", 130 to "130%"), Settings.textScale) {
            if (it != Settings.textScale) {
                Settings.textScale = it
                recreate()
            }
        }
        c.h2(t("Работа", "Behaviour"))
        c.spinner(t("Режим", "Mode"), listOf("analytics" to t("Только аналитика", "Analytics only"), "player" to t("Аналитика + плеер NewPipe", "Analytics + NewPipe player")), Settings.mode) {
            Settings.mode = it
        }
        c.h2(t("Уведомления", "Notifications"))
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

    private fun ai(c: Col) {
        val active = Provider.active()
        c.card {
            p(if (active != null) t("Активный: ", "Active: ") + active.title + " · " + active.fastModel + " / " + active.smartModel
            else t("Активный провайдер не выбран", "No active provider"))
            muted(t("Быстрая модель — уточнение темы и фрагменты; умная — итоговые сводки. Ключи шифруются ключом Android Keystore и не попадают в экспорт.",
                "Fast model — domain refinement and chunks; smart model — final summaries. Keys are encrypted with an Android Keystore key and never exported."))
        }
        if (testing.isNotEmpty()) c.p(testing, 13f, Palette.MUTED)
        c.button(t("Журнал всех ответов AI (", "Journal of all AI answers (") + AiJournal.list().size + ")", style = BtnStyle.OUTLINED) { AiJournalActivity.open(this) }
        for (p in Provider.entries) {
            val card = c.card {
                val configured = p.isConfigured
                add(rowOf(ctx,
                    text(ctx, p.title, Type.TITLE_M, bold = true),
                    text(ctx, if (Settings.activeProvider == p.name && configured) t("● активен", "● active") else if (configured) t("настроен", "configured") else "",
                        12f, bold = true, color = if (Settings.activeProvider == p.name) Palette.ACCENT else Palette.MUTED).apply { gravity = android.view.Gravity.END }
                ), 0)
                muted(p.baseUrl)
                if (configured) muted(p.fastModel + " / " + p.smartModel)
            }
            card.setOnClickListener { editProvider(p) }
        }
        c.h2(t("Стоимость (необязательно)", "Pricing (optional)"))
        c.muted(t("Укажите цену вашей модели за 1 млн токенов — тогда перед запуском покажем примерную стоимость.",
            "Enter your model's price per 1M tokens to see an estimated cost before running."))
        val pin = c.edit(t("Вход, $ за 1M", "Input, $ per 1M"), if (Settings.priceInPerM > 0) Settings.priceInPerM.toString() else "")
        val pout = c.edit(t("Выход, $ за 1M", "Output, $ per 1M"), if (Settings.priceOutPerM > 0) Settings.priceOutPerM.toString() else "")
        c.spinner(t("Размер фрагмента для длинных текстов", "Chunk size for long texts"),
            listOf(8000 to "8k", 16000 to "16k", 24000 to "24k", 48000 to "48k", 96000 to "96k"), Settings.chunkChars) { Settings.chunkChars = it }
        c.button(t("Сохранить", "Save"), primary = true) {
            Settings.priceInPerM = pin.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            Settings.priceOutPerM = pout.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            toast(t("Сохранено", "Saved"))
        }
    }

    private fun editProvider(p: Provider) {
        val c = Col(this, padding = 24)
        if (p.keyUrl.isNotEmpty()) {
            c.button(t("Получить ключ ↗", "Get a key ↗"), style = BtnStyle.TONAL) { openUrl(p.keyUrl) }
        }
        val key = if (p.needsKey || p == Provider.CUSTOM) {
            c.edit(if (KeyVault.has(p.name)) t("Ключ сохранён — введите новый, чтобы заменить", "Key saved — enter a new one to replace") else "API key").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        } else {
            null
        }
        c.muted("Base URL")
        val base = c.edit("https://…", p.baseUrl)
        if (p == Provider.OLLAMA) c.muted(t("Адрес компьютера в локальной сети, где запущен Ollama/LM Studio (OLLAMA_HOST=0.0.0.0).", "LAN address of the computer running Ollama/LM Studio (OLLAMA_HOST=0.0.0.0)."))
        c.muted(t("Быстрая модель", "Fast model"))
        val fast = c.edit("model id", p.fastModel)
        c.muted(t("Умная модель", "Smart model"))
        val smart = c.edit("model id", p.smartModel)
        fun saveFields() {
            key?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { KeyVault.put(p.name, it) }
            p.baseUrl = base.text.toString()
            p.fastModel = fast.text.toString()
            p.smartModel = smart.text.toString()
        }
        c.button(t("Загрузить список моделей", "Load model list"), style = BtnStyle.OUTLINED) {
            saveFields()
            Lumen.async({ Llm.listModels(p) }, ::err) { models ->
                if (models.isEmpty()) return@async toast(t("Список пуст", "Empty list"))
                val arr = models.toTypedArray()
                AlertDialog.Builder(this).setTitle(t("Модель", "Model")).setItems(arr) { _, i ->
                    AlertDialog.Builder(this).setTitle(arr[i])
                        .setPositiveButton(t("Быстрая", "Fast")) { _, _ -> fast.setText(arr[i]) }
                        .setNegativeButton(t("Умная", "Smart")) { _, _ -> smart.setText(arr[i]) }
                        .setNeutralButton(t("Обе", "Both")) { _, _ ->
                            fast.setText(arr[i])
                            smart.setText(arr[i])
                        }.show()
                }.show()
            }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(c.root) }
        val dlg = AlertDialog.Builder(this).setTitle(p.title).setView(scroll)
            .setPositiveButton(t("Сохранить и сделать активным", "Save & make active")) { _, _ ->
                saveFields()
                Settings.activeProvider = p.name
                testing = t("Проверка подключения…", "Testing connection…")
                render()
                Lumen.async({ Llm.ping(p) }, {
                    testing = "✗ ${p.title}: ${it.message}"
                    render()
                }) { r ->
                    testing = "✓ ${p.title}: $r"
                    render()
                }
            }
            .setNeutralButton(t("Сохранить", "Save")) { _, _ ->
                saveFields()
                render()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .create()
        dlg.show()
    }

    private fun prompts(c: Col) {
        c.muted(t("Переменные: {phrase} {domain} {lang} {data} {question}. Пустое поле — шаблон по умолчанию.",
            "Variables: {phrase} {domain} {lang} {data} {question}. Empty field = default template."))
        val names = mapOf(
            "domain" to t("Уточнение доменной области (JSON)", "Domain refinement (JSON)"),
            "video_summary" to t("Сводка по видео", "Video summary"),
            "chunk_summary" to t("Сводка фрагмента (map)", "Chunk summary (map)"),
            "project_summary" to t("Общая сводка проекта", "Project summary"),
            "clusters" to t("Кластеры", "Clusters"),
            "sentiment" to t("Тональность комментариев", "Comment sentiment"),
            "chat" to t("Чат с проектом", "Project chat"),
            "relevance" to t("Фильтр релевантности рекомендаций", "Recommendation relevance filter"),
            "relevance_channels" to t("Фильтр каналов по теме", "Channel topic filter"),
            "topic" to t("Подтверждение темы (домена)", "Topic (domain) confirmation"),
            "audit" to t("Аудит алгоритма: вывод", "Algorithm audit: verdict"),
            "p1_insights" to t("P1 · Извлечение аудитории", "P1 · Audience extraction"),
            "p2_clusters" to t("P2 · Кластеризация", "P2 · Clustering"),
            "p3_qualify" to t("P3 · SEO-квалификация темы", "P3 · SEO topic qualification"),
            "p4_brief" to t("P4 · SEO-бриф", "P4 · SEO brief"),
            "p5_article" to t("P5 · Статья", "P5 · Article"),
            "p6_quality" to t("P6 · Контроль качества", "P6 · Quality check"),
            "p7_ideas" to t("P7 · Идеи контента", "P7 · Content ideas"),
            "dossier_channel" to t("Досье · канал и его видео", "Dossier · channel and its videos"),
            "dossier_overview" to t("Досье · общая картина", "Dossier · overview")
        )
        for ((k, label) in names) {
            val card = c.card {
                add(text(ctx, label, Type.TITLE_M, bold = true), 0)
                val custom = Lumen.prefs.contains("prompt_$k")
                muted((if (custom) t("изменён · ", "custom · ") else "") + Prompts.get(k).take(120).replace('\n', ' ') + "…")
            }
            card.setOnClickListener {
                prompt(label, "", Prompts.get(k), multiline = true) { v ->
                    Prompts.set(k, v)
                    render()
                }
            }
        }
        c.button(t("Сбросить все промпты", "Reset all prompts"), style = BtnStyle.TEXT) {
            Prompts.defaults.keys.forEach { Prompts.set(it, "") }
            render()
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
                Lumen.async({ AutoBackup.runNow(this@SettingsActivity) }, ::err) {
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
            REQ_LOG_FILE -> Lumen.async({ contentResolver.openOutputStream(uri)?.use { it.write(L.report(this, maxLines = 2000).toByteArray()) } }, ::err) {
                toast(t("Сохранено", "Saved"))
            }
            REQ_TREE -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Settings.backupTreeUri = uri.toString()
                render()
            }
            REQ_BACKUP_FILE -> Lumen.async({ contentResolver.openOutputStream(uri)?.use { Store.backup(it) } }, ::err) {
                toast(t("Копия сохранена", "Backup saved"))
            }
            REQ_RESTORE -> Lumen.async({ AutoBackup.restore(this, uri) }, ::err) {
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

    companion object {
        const val REQ_TREE = 51
        const val REQ_BACKUP_FILE = 52
        const val REQ_RESTORE = 53
        const val REQ_LOG_FILE = 54
    }
}

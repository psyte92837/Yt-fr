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
        render()
    }

    private val sections get() = listOf(t("Общие", "General"), "AI", t("Промпты", "Prompts"), t("Резервная копия", "Backup"), t("Логи", "Logs"), t("О приложении", "About"))

    private fun render() {
        val c = Col(this)
        when (section) {
            0 -> general(c)
            1 -> ai(c)
            2 -> prompts(c)
            3 -> backup(c)
            4 -> logs(c)
            else -> about(c)
        }
        page(t("Настройки", "Settings"), c.root, header = tabBar(this, sections, section) {
            section = it
            render()
        })
    }

    private fun general(c: Col) {
        c.spinner(t("Язык интерфейса", "Interface language"), listOf("ru" to "Русский", "en" to "English"), Settings.lang) {
            if (it != Settings.lang) {
                Settings.lang = it
                render()
            }
        }
        c.spinner(t("Режим", "Mode"), listOf("analytics" to t("Только аналитика", "Analytics only"), "player" to t("Аналитика + плеер NewPipe", "Analytics + NewPipe player")), Settings.mode) {
            Settings.mode = it
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
            "p1_insights" to t("P1 · Извлечение аудитории", "P1 · Audience extraction"),
            "p2_clusters" to t("P2 · Кластеризация", "P2 · Clustering"),
            "p3_qualify" to t("P3 · SEO-квалификация темы", "P3 · SEO topic qualification"),
            "p4_brief" to t("P4 · SEO-бриф", "P4 · SEO brief"),
            "p5_article" to t("P5 · Статья", "P5 · Article"),
            "p6_quality" to t("P6 · Контроль качества", "P6 · Quality check"),
            "p7_ideas" to t("P7 · Идеи контента", "P7 · Content ideas")
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
            REQ_RESTORE -> Lumen.async({ contentResolver.openInputStream(uri)?.use { Store.restore(it) } }, ::err) {
                Monitoring.reapplyAll(this)
                toast(t("Восстановлено", "Restored"))
            }
        }
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

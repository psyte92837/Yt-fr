/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.os.Bundle

/**
 * Lumen Studio settings: look, AI (providers, keys, prompts of the Studio), Studio options, data and logs, about.
 * The AI part is the same as in Lumen ([AiSettings]).
 */
class SettingsActivity : LumenBase() {
    companion object {
        /** Public privacy policy (the same link goes to the Google Play listing). */
        const val PRIVACY_URL = "https://github.com/psyte92837/Yt-fr/blob/main/doc/PRIVACY_LUMEN_STUDIO.md"
    }

    private var section = -1
    private var direct = false
    private val aiUi = AiSettings(this) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("general", false)) section = 0
        if (intent.getBooleanExtra("ai", false)) section = 1
        if (intent.getBooleanExtra("studio", false)) section = 2
        if (intent.getBooleanExtra("logs", false)) section = 3
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
        t("Общие", "General") to t("язык, тема, уведомления", "language, theme, notifications"),
        t("ИИ и промпты", "AI and prompts") to t("провайдер, ключи, шаблоны запросов", "provider, keys, prompt templates"),
        "Lumen Studio" to t("эффекты, B-roll, качество экспорта, другой ИИ", "effects, B-roll, export quality, another AI"),
        t("Данные", "Data") to t("кадры анимаций, статистика ИИ, журнал", "animation frames, AI usage, logs"),
        t("О приложении", "About") to t("версия, лицензия, исходный код", "version, licence, source code")
    )

    private fun render() {
        val c = Col(this)
        if (section < 0) {
            sections.forEachIndexed { id, (name, summary) ->
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
                aiUi.prompts(c, only = { it.startsWith("studio") || it.startsWith("edit") })
            }
            2 -> aiUi.studio(c)
            3 -> data(c)
            else -> about(c)
        }
        page(sections[section].first, c.root)
    }

    private fun general(c: Col) {
        c.spinner(t("Язык интерфейса", "Interface language"), listOf("ru" to "Русский", "en" to "English"), Settings.lang) {
            if (it != Settings.lang) {
                Settings.lang = it
                recreate()
            }
        }
        c.spinner(t("Тема", "Theme"), listOf("system" to t("Как в системе", "Follow system"), "light" to t("Светлая", "Light"),
            "dark" to t("Тёмная", "Dark"), "black" to t("Чёрная (AMOLED)", "Black (AMOLED)")), Settings.theme) {
            if (it != Settings.theme) {
                Settings.theme = it
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
        c.check(t("Вибрация, когда ролик готов", "Vibrate when a video is ready"), Settings.vibrateDone) { Settings.vibrateDone = it }
        c.button(t("⚙ Задачи в фоне", "⚙ Background tasks"), style = BtnStyle.OUTLINED) { TasksActivity.open(this) }
    }

    private fun data(c: Col) {
        c.h2(t("Кадры анимаций Редактора", "Animation frames of the editor"))
        val root = StudioEditStore.root(applicationContext)
        val size = root.listFiles().orEmpty().sumOf { AnimRender.size(it) }
        c.muted(t("Занято: ", "Used: ") + (size / 1_048_576) + t(" МБ", " MB"))
        c.button(t("🧹 Очистить неиспользуемые кадры", "🧹 Clean the unused frames"), style = BtnStyle.TONAL) {
            root.listFiles().orEmpty().forEach { d -> StudioEditStore.load(d)?.let { p -> runCatching { AnimRender.clean(d, p.blocks) } } }
            toast(t("Готово", "Done"))
            render()
        }
        c.h2(t("Статистика ИИ", "AI usage"))
        aiUi.usage(c)
        c.h2(t("Журнал", "Logs"))
        c.button(t("🧯 Лог сбоя и зависаний", "🧯 Crash and freeze log"), style = BtnStyle.OUTLINED) { CrashLogActivity.open(this) }
        c.muted(t("Отчёт для разработчика: версия, устройство, настройки без ключей и последние строки журнала.",
            "Developer report: version, device, settings without keys and the recent log lines."))
        c.row(
            btn(this, t("Поделиться отчётом", "Share report"), primary = true) { shareText("Lumen Studio report", L.report(this)) },
            btn(this, t("Копировать", "Copy"), style = BtnStyle.TONAL) {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Lumen Studio report", L.report(this, maxLines = 200)))
                toast(t("Отчёт скопирован", "Report copied"))
            }
        )
    }

    private fun about(c: Col) {
        c.title("Lumen Studio")
        c.p(t("Монтаж видео на телефоне: ролики из текста и озвучки, анимация, редактор с субтитрами, схемами, вставками и музыкой. " +
            "ИИ — через ваш собственный ключ; проекты и ключи хранятся только на устройстве.",
            "Video editing on the phone: videos from a script or a voice-over, animation, an editor with subtitles, schemes, inserts and music. " +
                "AI works with your own key; projects and keys stay on the device."))
        c.muted(t("Версия ", "Version ") + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty())
        c.p(t("Свободная программа, лицензия GPL-3.0-or-later. Исходный код открыт.", "Free software, licensed GPL-3.0-or-later. The source code is open."))
        c.button(t("Политика конфиденциальности ↗", "Privacy policy ↗"), style = BtnStyle.TEXT) { openUrl(PRIVACY_URL) }
        c.button(t("Исходный код ↗", "Source code ↗"), style = BtnStyle.TEXT) { openUrl("https://github.com/psyte92837/Yt-fr") }
        c.button(t("Текст лицензии ↗", "Licence text ↗"), style = BtnStyle.TEXT) { openUrl("https://www.gnu.org/licenses/gpl-3.0.html") }
    }
}

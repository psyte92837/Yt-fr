/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Search across the app: projects, collected videos of every project, help topics, settings and screens.
 * The index is built in the background once per opening; matching is [SearchIndex] (pure, tested).
 */
class SearchActivity : LumenBase() {
    private var items: List<SearchIndex.Item> = emptyList()
    private var query = ""
    private lateinit var results: LinearLayout
    private lateinit var input: EditText
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = savedInstanceState?.getString("q") ?: intent.getStringExtra("q").orEmpty()
        val c = Col(this)
        input = c.edit(t("Проекты, видео, настройки, справка…", "Projects, videos, settings, help…"), query)
        input.onEnter { show(it) }
        input.addTextChangedListener(SimpleWatcher { s ->
            query = s
            input.removeCallbacks(refresh)
            input.postDelayed(refresh, 200)
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.add(results, 8)
        page(t("Поиск", "Search"), c.root)
        input.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        show(query)
        Lumen.async({ build(applicationContext) }, ::err) {
            items = it
            loading = false
            show(query)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("q", query)
    }

    private val refresh = Runnable { show(query) }

    private fun kindName(k: String) = when (k) {
        "project" -> t("Проект", "Project")
        "video" -> t("Видео", "Video")
        "help" -> t("Справка", "Help")
        "settings" -> t("Настройки", "Settings")
        else -> t("Раздел", "Screen")
    }

    private fun show(q: String) {
        results.removeAllViews()
        val c = Col(this, 0)
        if (q.isBlank()) {
            c.muted(t("Ищет по названиям проектов, видео всех проектов (название, канал, теги), справке, настройкам и разделам.",
                "Searches project names, videos of all projects (title, channel, tags), help, settings and screens."))
            destinations(this).take(8).let { d ->
                c.h2(t("Разделы", "Screens"))
                d.forEach { row(c, it) }
            }
        } else {
            val hits = SearchIndex.rank(q, items + destinations(this))
            if (hits.isEmpty()) {
                if (loading) c.muted(t("Собираю индекс…", "Building the index…"))
                else c.empty(t("Ничего не найдено", "Nothing found"), t("Попробуйте другое слово или начало слова.", "Try another word or the start of a word."))
            } else {
                c.muted(t("Найдено: ", "Found: ") + hits.size + if (loading) t(" · индекс ещё собирается", " · still indexing") else "")
                hits.groupBy { it.item.kind }.forEach { (kind, list) ->
                    c.h2(kindName(kind) + " · " + list.size)
                    list.take(20).forEach { row(c, it.item) }
                }
            }
        }
        results.addView(c.root)
    }

    private fun row(c: Col, it: SearchIndex.Item) {
        c.card {
            add(text(ctx, it.title, Type.TITLE_M, bold = true).apply { maxLines = 2 }, 0)
            if (it.sub.isNotBlank()) muted(it.sub)
        }.setOnClickListener { _ -> open(it) }
    }

    private fun open(it: SearchIndex.Item) {
        when (it.kind) {
            "project" -> Store.project(it.key)?.let { p -> openProjectScreen(p) }
            "video" -> {
                val (pid, vid) = it.key.split('|', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                startActivity(Intent(this, VideoActivity::class.java).putExtra("pid", pid).putExtra("vid", vid))
            }
            "help" -> HelpActivity.open(this, it.key)
            else -> destinationOpen(this, it.key)
        }
    }

    companion object {
        fun open(ctx: Context, q: String = "") = ctx.startActivity(Intent(ctx, SearchActivity::class.java).putExtra("q", q))

        /** Projects, videos and help topics (background thread). */
        fun build(ctx: Context): List<SearchIndex.Item> {
            val out = ArrayList<SearchIndex.Item>()
            val projects = runCatching { Store.projects() }.getOrDefault(emptyList())
            projects.forEach { p ->
                out.add(SearchIndex.Item("project", p.name.ifBlank { p.phrase }, "«${p.phrase}»" + if (p.domain.isNotBlank()) " · ${p.domain}" else "",
                    p.id, p.domain))
            }
            projects.forEach { p ->
                val name = p.name.ifBlank { p.phrase }
                runCatching { Store.videos(p.id).values }.getOrDefault(emptyList()).forEach { v ->
                    out.add(SearchIndex.Item("video", v.title.ifBlank { v.url }, v.channel + " · " + name, p.id + "|" + v.id,
                        (v.tags + v.labels).joinToString(" ")))
                }
            }
            runCatching { Help.topics() }.getOrDefault(emptyList()).forEach { h ->
                out.add(SearchIndex.Item("help", h.title, h.what.take(140), h.id, h.steps.joinToString(" ")))
            }
            return out
        }

        /** Screens and settings sections reachable from search (key → [destinationOpen]). */
        fun destinations(ctx: Context): List<SearchIndex.Item> = listOf(
            SearchIndex.Item("screen", t("🎬 Lumen Studio", "🎬 Lumen Studio"), t("ролики Shorts, видео, озвучка, нарезка", "Shorts, videos, voice-over, cuts"), "studio", "studio shorts видео ролик"),
            SearchIndex.Item("screen", t("✏️ Редактор видео", "✏️ Video editor"), t("монтаж, схемы, надписи, эмодзи", "editing, schemes, captions, emoji"), "editor", "монтаж edit editor capcut"),
            SearchIndex.Item("screen", t("🔀 Микс", "🔀 Mix"), t("нарезка чужих роликов под озвучку", "cuts of videos under a voice-over"), "mix", "mix микс нарезка"),
            SearchIndex.Item("screen", t("🔊 Озвучка текста", "🔊 Read aloud"), t("голоса, MP3", "voices, MP3"), "tts", "tts голос озвучка mp3 voice"),
            SearchIndex.Item("screen", t("🌐 Браузер", "🌐 Browser"), "", "browser", "browser браузер web"),
            SearchIndex.Item("screen", t("🤖 Мои ТГ боты", "🤖 My Telegram bots"), "Telegram", "bots", "telegram боты bot"),
            SearchIndex.Item("screen", t("⚙ Задачи", "⚙ Tasks"), t("идущие задачи, остановка, история", "running tasks, stop, history"), "tasks", "задачи tasks менеджер"),
            SearchIndex.Item("screen", t("🧾 Журнал ответов AI", "🧾 AI answer journal"), "", "journal", "журнал ai ответы journal"),
            SearchIndex.Item("screen", t("💡 Идеи для бизнеса", "💡 Business ideas"), "", "ideas", "идеи ideas бизнес"),
            SearchIndex.Item("screen", t("🗑 Корзина проектов", "🗑 Project trash"), t("восстановить удалённый проект", "restore a deleted project"), "home", "корзина trash удалённые восстановить"),
            SearchIndex.Item("settings", t("Настройки: общие", "Settings: general"), t("язык, тема, оформление, мониторинг, сбор, уведомления", "language, theme, look, monitoring, collection, notifications"), "set_general", "язык тема цвет шрифт размер текста theme language"),
            SearchIndex.Item("settings", t("Настройки: AI и промпты", "Settings: AI and prompts"), t("провайдер, ключ, модель, промпты", "provider, key, model, prompts"), "set_ai", "ai ключ key gemini claude openai промпт prompt модель"),
            SearchIndex.Item("settings", t("Настройки: данные", "Settings: data"), t("резервная копия, журнал, статистика", "backup, logs, statistics"), "set_data", "бэкап backup лог журнал статистика"),
            SearchIndex.Item("settings", t("Настройки: о приложении", "Settings: about"), t("версия, обновления, проверка YouTube, быстрый анализ", "version, updates, YouTube check, instant analysis"), "set_about", "версия обновление update youtube быстрый анализ"),
            SearchIndex.Item("settings", t("Настройки: Lumen Studio", "Settings: Lumen Studio"), t("эффекты, B-roll, качество экспорта, другой ИИ", "effects, B-roll, export quality, another AI"), "set_studio", "студия studio broll эффекты экспорт качество relay подсказки"),
            SearchIndex.Item("settings", t("Бесплатные ИИ", "Free AI"), t("ключи бесплатных моделей", "free model keys"), "freeai", "бесплатные free ai ключи")
        )

        fun destinationOpen(ctx: Context, key: String) {
            fun settings(extra: String?) = ctx.startActivity(Intent(ctx, SettingsActivity::class.java).also { i -> extra?.let { i.putExtra(it, true) } })
            when (key) {
                "studio" -> StudioActivity.open(ctx)
                "editor" -> StudioEditorActivity.open(ctx)
                "mix" -> StudioMixActivity.open(ctx)
                "tts" -> TtsActivity.open(ctx)
                "browser" -> BrowserActivity.open(ctx)
                "bots" -> BotsActivity.open(ctx)
                "tasks" -> TasksActivity.open(ctx)
                "journal" -> AiJournalActivity.open(ctx)
                "ideas" -> IdeasActivity.open(ctx)
                "home" -> Nav.home(ctx)
                "set_general" -> settings("general")
                "set_ai" -> settings("ai")
                "set_data" -> settings("logs")
                "set_about" -> settings("quick")
                "set_studio" -> settings("studio")
                "freeai" -> ctx.startActivity(Intent(ctx, FreeAiActivity::class.java))
            }
        }
    }
}

/** Launcher shortcuts (long press on the Lumen icon): built in code, Back from each leads to the home screen. */
object Shortcuts {
    fun publish(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 25) return
        runCatching {
            val sm = ctx.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            val home = Intent(ctx, LumenActivity::class.java).setAction(Intent.ACTION_MAIN)
            fun item(id: String, label: String, ic: Ic, target: Intent) = android.content.pm.ShortcutInfo.Builder(ctx, id)
                .setShortLabel(label).setLongLabel(label).setIcon(icon(ctx, ic))
                .setIntents(arrayOf(home, target.setAction(Intent.ACTION_VIEW))).build()
            val list = listOf(
                item("new_project", t("Новый проект", "New project"), Ic.ADD, Intent(ctx, NewProjectActivity::class.java)),
                item("search", t("Поиск", "Search"), Ic.SEARCH, Intent(ctx, SearchActivity::class.java)),
                item("studio", "Studio", Ic.MOVIE, Intent(ctx, StudioActivity::class.java)),
                item("tts", t("Озвучка", "Read aloud"), Ic.SPEAKER, Intent(ctx, TtsActivity::class.java))
            ).take((sm.maxShortcutCountPerActivity - 1).coerceAtLeast(1)) +
                // opens in its own process: works even when Lumen freezes on start
                android.content.pm.ShortcutInfo.Builder(ctx, "crash_log").setShortLabel(t("Лог сбоя", "Crash log"))
                    .setLongLabel(t("Отправить лог сбоя", "Send crash log")).setIcon(icon(ctx, Ic.BUG))
                    .setIntent(Intent(ctx, CrashLogActivity::class.java).setAction(Intent.ACTION_VIEW)).build()
            sm.dynamicShortcuts = list
        }.onFailure { L.w("shortcuts", it.message.orEmpty()) }
    }

    /** Our line icon on an accent circle (adaptive-icon safe zone). */
    private fun icon(ctx: Context, ic: Ic): android.graphics.drawable.Icon {
        val size = ctx.dp(108)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(Palette.ACCENT_SOFT)
        val d = IconDrawable(ic, Palette.ON_SOFT)
        val inset = size * 30 / 108
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(cv)
        return android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp)
    }
}

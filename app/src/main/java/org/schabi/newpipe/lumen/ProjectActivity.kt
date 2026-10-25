/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** Project screen with tabs: Videos · Collect · Analytics · AI · Export · Settings. */
class ProjectActivity : LumenBase() {
    lateinit var project: Project
    var videos = LinkedHashMap<String, Video>()
    var tab = "videos"
    var busy = ""
    val cancel = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    var progressView: TextView? = null

    // recommendations / audience / SEO tab state (see RecsTab.kt, AudienceTab.kt, SeoTab.kt)
    val ui = HashMap<String, Any>()

    // video list view state
    private var onlyMarked = false
    private var onlyUncollected = false
    private var labelFilter = ""
    private var listSort = "added"
    private var shown = 50

    // analytics state
    private var trendMetric = "count"
    private var channelsBy = "videos"
    private var wordsSource = "descriptions"
    private var videosBy = "views"
    private var compareWith = ""

    private val ticker = object : Runnable {
        override fun run() {
            val pv = progressView
            if (pv == null && CollectState.running && CollectState.projectId == project.id) {
                render()
            } else if (pv != null) {
                val running = CollectState.running && CollectState.projectId == project.id
                pv.text = if (running) CollectState.summary() else busy
                if (!running && pv.tag == "collecting") {
                    pv.tag = null
                    reload()
                    render()
                    when {
                        CollectState.lastError.isNotEmpty() -> err(IllegalStateException(CollectState.lastError))
                        CollectState.kind == "recs" -> toast(CollectState.lastResult)
                        else -> toast(t("Сбор завершён", "Collection finished"))
                    }
                }
                if (running) pv.tag = "collecting"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val p = id?.let { Store.project(it) }
        if (p == null) {
            finish()
            return
        }
        project = p
        if (project.newCount > 0) {
            project.newCount = 0
            Store.save(project)
        }
        reload()
        render()
        if (intent.getBooleanExtra("autoSearch", false) && savedInstanceState == null) runSearch()
    }

    override fun onResume() {
        super.onResume()
        if (::project.isInitialized) {
            reload()
            render()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel.set(true)
    }

    fun reload() {
        Store.project(project.id)?.let { project = it }
        videos = Store.videos(project.id)
    }

    private val tabs get() = listOf(
        "videos" to t("Видео", "Videos"), "collect" to t("Сбор", "Collect"),
        "recs" to t("Рекомендации", "Recommendations"), "analytics" to t("Анализ", "Analytics"),
        "audience" to t("Аудитория", "Audience"), "seo" to "SEO", "ai" to "AI",
        "export" to t("Экспорт", "Export"), "settings" to t("Настройки", "Settings")
    )

    fun render() {
        val c = Col(this)
        progressView = null
        if (busy.isNotEmpty() || (CollectState.running && CollectState.projectId == project.id)) {
            c.card {
                val pv = text(ctx, busy, 14f, bold = true, color = Palette.ON_SURFACE)
                progressView = pv
                add(pv, 0)
                button(t("Остановить", "Stop"), style = BtnStyle.OUTLINED) {
                    cancel.set(true)
                    if (CollectState.running) CollectService.stop(this@ProjectActivity)
                }
            }
        }
        when (tab) {
            "videos" -> videosTab(c)
            "collect" -> collectTab(c)
            "recs" -> recsTab(c)
            "analytics" -> analyticsTab(c)
            "audience" -> audienceTab(c)
            "seo" -> seoTab(c)
            "ai" -> aiTab(c)
            "export" -> exportTab(c)
            else -> settingsTab(c)
        }
        val fab: Pair<String, () -> Unit>? = when (tab) {
            "videos" -> t("Найти", "Search") to { runSearch() }
            "collect" -> t("Собрать", "Collect") to { startCollect(selectedForCollect()) }
            "recs" -> t("Запустить", "Run") to { startRecs() }
            else -> null
        }
        page(
            project.name, c.root,
            header = tabBar(this, tabs.map { it.second }, maxOf(0, tabs.indexOfFirst { it.first == tab })) {
                tab = tabs[it].first
                render()
            },
            fab = if (busy.isEmpty()) fab else null
        )
    }

    // ------------------------------------------------------------------ Videos
    private fun runSearch() {
        if (busy.isNotEmpty()) return
        cancel.set(false)
        busy = t("Поиск…", "Searching…")
        render()
        val qs = project.effectiveQueries()
        Lumen.async({
            val found = YouTube.search(qs, project.filters, cancel) { n, q ->
                Lumen.ui {
                    busy = t("Найдено: $n · «$q»", "Found: $n · \"$q\"")
                    progressView?.text = busy
                }
            }
            val added = Store.mergeVideos(project.id, found)
            Store.project(project.id)?.let {
                it.lastRunAt = System.currentTimeMillis()
                it.runs.add(RunRecord(it.lastRunAt, "search", found.size, added))
                Store.save(it)
            }
            found.size to added
        }, {
            busy = ""
            reload()
            render()
            err(it)
        }) { (found, added) ->
            busy = ""
            reload()
            tab = "videos"
            render()
            toast(t("Найдено $found, новых $added", "Found $found, new $added"))
        }
    }

    private fun importDialog() {
        val items = arrayOf(
            t("Канал (все или последние N видео)", "Channel (all or last N videos)"),
            t("Плейлист", "Playlist"),
            t("Отдельные ссылки на видео", "Single video links"),
            t("Тренды YouTube", "YouTube trending"),
            t("Файл TXT / CSV / JSON (транскрипт или комментарии)", "TXT / CSV / JSON file (transcript or comments)")
        )
        AlertDialog.Builder(this).setTitle(t("Импорт", "Import")).setItems(items) { _, which ->
            if (which == 3) {
                importTrending()
            } else if (which == 4) {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/csv", "application/json", "text/comma-separated-values", "text/vtt", "*/*"))
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    REQ_IMPORT
                )
            } else if (which == 2) {
                prompt(t("Ссылки на видео", "Video links"), t("По одной в строке", "One per line"), multiline = true) { s ->
                    importLinks(s.lines().map { it.trim() }.filter { it.isNotEmpty() })
                }
            } else {
                prompt(t("Ссылка", "Link"), "https://www.youtube.com/@channel  /  ...list=...") { url ->
                    prompt(t("Сколько видео взять (0 = все)", "How many videos (0 = all)"), "200", "200") { n ->
                        importList(url.trim(), n.trim().toIntOrNull()?.takeIf { it > 0 } ?: 5000)
                    }
                }
            }
        }.show()
    }

    private fun importTrending() {
        busy = t("Загрузка списка трендов…", "Loading trending list…")
        render()
        Lumen.async({ RecEngine.trendingKiosks() }, {
            busy = ""
            render()
            err(it)
        }) { kiosks ->
            busy = ""
            render()
            var onlyTopic = true
            AlertDialog.Builder(this).setTitle(t("Тренды YouTube", "YouTube trending"))
                .setItems(kiosks.toTypedArray()) { _, w ->
                    AlertDialog.Builder(this).setTitle(kiosks[w])
                        .setMultiChoiceItems(arrayOf(t("Только по теме проекта (ключевые слова)", "Only on-topic (keywords)")), booleanArrayOf(true)) { _, _, on -> onlyTopic = on }
                        .setPositiveButton("OK") { _, _ ->
                            busy = t("Тренды…", "Trending…")
                            render()
                            Lumen.async({ Store.mergeVideos(project.id, RecEngine.trending(project, kiosks[w], onlyTopic)) }, {
                                busy = ""
                                render()
                                err(it)
                            }) { n ->
                                busy = ""
                                reload()
                                render()
                                toast(t("Добавлено: $n", "Added: $n"))
                            }
                        }.setNegativeButton(t("Отмена", "Cancel"), null).show()
                }.show()
        }
    }

    private fun importFiles(uris: List<android.net.Uri>) {
        busy = t("Импорт файлов…", "Importing files…")
        render()
        Lumen.async({
            uris.map { uri ->
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
                }
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                Seo.importFile(project.id, name, text)
            }
        }, {
            busy = ""
            render()
            err(it)
        }) { list ->
            busy = ""
            reload()
            render()
            toast(t("Импортировано источников: ${list.size} (комментариев ${list.sumOf { it.commentsCount }})",
                "Imported sources: ${list.size} (comments ${list.sumOf { it.commentsCount }})"))
        }
    }

    private fun importList(url: String, limit: Int) {
        cancel.set(false)
        busy = t("Импорт…", "Importing…")
        render()
        // period/duration filters of the project apply to imports as well
        Lumen.async({
            val list = YouTube.importList(url, limit, project.filters, cancel) { n ->
                Lumen.ui { progressView?.text = t("Импортировано: $n", "Imported: $n") }
            }
            val added = Store.mergeVideos(project.id, list)
            Store.project(project.id)?.let {
                it.runs.add(RunRecord(System.currentTimeMillis(), "import", list.size, added, url.take(80)))
                Store.save(it)
            }
            list.size to added
        }, {
            busy = ""
            render()
            err(it)
        }) { (n, added) ->
            busy = ""
            reload()
            render()
            toast(t("Получено $n, новых $added", "Got $n, new $added"))
        }
    }

    private fun importLinks(urls: List<String>) {
        busy = t("Загрузка ссылок…", "Loading links…")
        render()
        Lumen.async({
            val list = urls.mapNotNull { u ->
                try {
                    YouTube.single(u).also { Thread.sleep(Settings.requestDelayMs.toLong()) }
                } catch (e: Exception) {
                    null
                }
            }
            Store.mergeVideos(project.id, list)
        }, {
            busy = ""
            render()
            err(it)
        }) { added ->
            busy = ""
            reload()
            render()
            toast(t("Добавлено: $added", "Added: $added"))
        }
    }

    private fun filteredVideos(): List<Video> {
        var list = videos.values.toList()
        if (onlyMarked) list = list.filter { it.marked }
        if (onlyUncollected) list = list.filter { !it.collected }
        if (labelFilter.isNotEmpty()) list = list.filter { labelFilter in it.labels }
        return when (listSort) {
            "date" -> list.sortedByDescending { it.uploadMs }
            "views" -> list.sortedByDescending { it.views }
            "velocity" -> list.sortedByDescending { Analytics.velocity(it) }
            else -> list.sortedByDescending { it.addedAt }
        }
    }

    private fun setMarked(ids: Collection<String>, value: Boolean) {
        ids.forEach { videos[it]?.marked = value }
        Store.saveVideos(project.id, videos.values)
        render()
    }

    private fun videosTab(c: Col) {
        val total = videos.size
        val marked = videos.values.count { it.marked }
        c.muted(t("Запросы: ", "Queries: ") + project.effectiveQueries().joinToString(" · "))
        c.row(
            btn(this, t("Импорт", "Import"), style = BtnStyle.TONAL) { importDialog() },
            btn(this, t("Фильтры", "Filters"), style = BtnStyle.OUTLINED) {
                tab = "settings"
                render()
            }
        )
        val list = filteredVideos()
        c.h2(t("Видео: $total · отмечено: $marked", "Videos: $total · marked: $marked"))
        c.chips(
            listOf(
                chip(this, t("Отмеченные", "Marked"), onlyMarked) {
                    onlyMarked = !onlyMarked
                    render()
                },
                chip(this, t("Не собранные", "Not collected"), onlyUncollected) {
                    onlyUncollected = !onlyUncollected
                    render()
                },
                chip(this, t("Выбрать все", "Select all")) { setMarked(list.map { it.id }, true) },
                chip(this, t("Снять все", "Clear all")) { setMarked(list.map { it.id }, false) }
            )
        )
        val labels = videos.values.flatMap { it.labels }.distinct().sorted()
        if (labels.isNotEmpty()) {
            c.chips(labels.map { l ->
                chip(this, "#$l", labelFilter == l) {
                    labelFilter = if (labelFilter == l) "" else l
                    render()
                }
            })
        }
        c.spinner(
            t("Порядок", "Order"),
            listOf(
                "added" to t("Недавно добавленные", "Recently added"), "date" to t("Дата публикации", "Upload date"),
                "views" to t("Просмотры", "Views"), "velocity" to t("Быстрорастущие", "Fastest growing")
            ),
            listSort
        ) {
            if (it != listSort) {
                listSort = it
                render()
            }
        }
        if (list.isEmpty()) {
            c.muted(if (total == 0) t("Пока нет видео. Нажмите «Найти» или импортируйте канал/плейлист.", "No videos yet. Tap Search or import a channel/playlist.")
            else t("Нет видео под выбранный фильтр.", "No videos match the filter."))
        }
        for (v in list.take(shown)) c.add(videoRow(v), 8)
        if (list.size > shown) c.button(t("Показать ещё (${list.size - shown})", "Show more (${list.size - shown})"), style = BtnStyle.TEXT) {
            shown += 100
            render()
        }
    }

    private fun videoRow(v: Video): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(this@ProjectActivity, roundBg(Palette.CARD, 12f, this@ProjectActivity), Palette.ON_SURFACE)
            setPadding(dp(4), dp(8), dp(12), dp(8))
        }
        val cb = CheckBox(this).apply {
            isChecked = v.marked
            buttonTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
            contentDescription = t("Отметить", "Mark")
            setOnCheckedChangeListener { _, on ->
                v.marked = on
                Store.updateVideo(project.id, v.id) { it.marked = on }
            }
        }
        row.addView(cb, LinearLayout.LayoutParams(dp(48), dp(48)))
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(Palette.ACCENT_SOFT, 8f, this@ProjectActivity)
            clipToOutline = true
        }
        row.addView(img, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(12) })
        Thumbs.load(img, v.thumb)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(this, v.title, 14f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(text(this, v.channel, 12f, color = Palette.MUTED).apply { isSingleLine = true })
        val status = buildString {
            append(Fmt.num(v.views)).append(" · ").append(if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText)
            append(" · ").append(Fmt.duration(v.duration))
            if (v.collected) append("  ✓")
            if (v.hasTranscript) append(" 📝")
            if (v.commentsCount > 0) append(" 💬").append(v.commentsCount)
            if (v.collectError.isNotEmpty()) append(" ⚠")
            if (v.labels.isNotEmpty()) append("  #").append(v.labels.joinToString(" #"))
        }
        info.addView(text(this, status, 12f, color = if (v.collectError.isNotEmpty()) Palette.BAD else Palette.MUTED))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java).putExtra("pid", project.id).putExtra("vid", v.id))
        }
        return row
    }

    // ------------------------------------------------------------------ Collect
    private fun selectedForCollect(): List<String> {
        val marked = videos.values.filter { it.marked }
        val base = if (marked.isNotEmpty()) marked else videos.values.toList()
        return base.filter { !it.collected && !it.id.startsWith(LOCAL_PREFIX) }.map { it.id }
    }

    fun startCollect(ids: List<String>) {
        if (ids.isEmpty()) return toast(t("Нечего собирать: все выбранные видео уже собраны.", "Nothing to collect: selected videos are already collected."))
        if (CollectState.running) return toast(t("Сбор уже идёт", "Collection is already running"))
        Store.save(project)
        CollectService.start(this, project.id, ids)
        toast(t("Сбор запущен: ${ids.size} видео", "Collection started: ${ids.size} videos"))
    }

    private fun collectTab(c: Col) {
        val cfg = project.collect
        val save = { Store.save(project) }
        val marked = videos.values.count { it.marked }
        val pending = selectedForCollect().size
        c.card {
            p(if (marked > 0) t("Будут собраны отмеченные: $pending из $marked", "Marked videos to collect: $pending of $marked")
            else t("Ничего не отмечено — будут собраны все несобранные: $pending", "Nothing marked — all uncollected will be collected: $pending"))
            muted(t("Сбор идёт в фоне с уведомлением. Остановленный сбор продолжится с места остановки.",
                "Collection runs in the background with a notification. A stopped run resumes where it stopped."))
        }
        c.h2(t("Что собирать", "What to collect"))
        c.check(t("Метаданные (описание, теги, лайки, категория, подписчики)", "Metadata (description, tags, likes, category, subscribers)"), cfg.meta) {
            cfg.meta = it
            save()
        }
        c.check(t("Расшифровка (субтитры, ручные приоритетнее авто)", "Transcript (subtitles, manual preferred over auto)"), cfg.subtitles) {
            cfg.subtitles = it
            save()
        }
        c.spinner(t("Язык субтитров", "Subtitle language"),
            listOf("auto" to t("Любой (как в видео)", "Any (video language)"), "ru" to "Русский", "en" to "English",
                "uk" to "Українська", "kk" to "Қазақша", "ky" to "Кыргызча", "de" to "Deutsch", "es" to "Español"),
            cfg.subLang) {
            cfg.subLang = it
            save()
        }
        if (Settings.aiTranscriptionAllowed) {
            c.check(t("Если субтитров нет — расшифровать через Gemini (тратит токены)", "If no subtitles — transcribe via Gemini (uses tokens)"), cfg.aiTranscribe) {
                cfg.aiTranscribe = it
                save()
            }
        }
        c.check(t("Комментарии", "Comments"), cfg.comments) {
            cfg.comments = it
            save()
        }
        c.spinner(t("Лимит комментариев на видео", "Comments per video"), listOf(50, 100, 200, 500, 1000, 5000).map { it to it.toString() }, cfg.commentsLimit) {
            cfg.commentsLimit = it
            save()
        }
        c.spinner(t("Сортировка комментариев", "Comment order"), listOf("top" to t("Популярные", "Top"), "new" to t("Новые", "Newest")), cfg.commentsSort) {
            cfg.commentsSort = it
            save()
        }
        c.check(t("Включая ответы на комментарии", "Include replies"), cfg.replies) {
            cfg.replies = it
            save()
        }
        c.h2(t("Повторный сбор", "Re-collect"))
        c.button(t("Пересобрать отмеченные (заново)", "Re-collect marked (again)"), style = BtnStyle.OUTLINED) {
            val ids = videos.values.filter { it.marked }.map { it.id }
            ids.forEach { videos[it]?.collected = false }
            Store.saveVideos(project.id, videos.values)
            startCollect(ids)
        }
        c.button(t("Повторить видео с ошибками", "Retry failed videos"), style = BtnStyle.OUTLINED) {
            startCollect(videos.values.filter { it.collectError.isNotEmpty() }.map { it.id })
        }
        c.h2(t("История запусков", "Run history"))
        if (project.runs.isEmpty()) c.muted("—")
        project.runs.takeLast(20).reversed().forEach { r ->
            c.muted("${Fmt.dateTime(r.at)} · ${r.kind} · ${t("найдено", "found")} ${r.found} · ${t("добавлено", "added")} ${r.added} ${r.note}")
        }
    }

    // ------------------------------------------------------------------ Analytics
    private fun analyticsTab(c: Col) {
        val all = videos.values.toList()
        if (all.isEmpty()) {
            c.muted(t("Нет данных. Сначала найдите видео.", "No data. Search for videos first."))
            return
        }
        val s = Analytics.stats(all)
        c.card {
            add(text(ctx, Fmt.num(s.totalViews.toLong()) + t(" просмотров", " views"), Type.TITLE_L, bold = true), 0)
            muted(t("Видео: ${s.total} · собрано: ${s.collected} · с расшифровкой: ${s.withTranscript} · комментариев: ${s.comments}",
                "Videos: ${s.total} · collected: ${s.collected} · transcripts: ${s.withTranscript} · comments: ${s.comments}"))
            muted(t("Средние просмотры: ", "Average views: ") + Fmt.num(s.avgViews) + " · " + Fmt.date(s.firstMs) + " — " + Fmt.date(s.lastMs))
        }

        c.h2(t("Тренд по времени", "Trend over time"))
        c.chips(listOf(
            chip(this, t("Число видео", "Video count"), trendMetric == "count") {
                trendMetric = "count"
                render()
            },
            chip(this, t("Просмотры", "Views"), trendMetric == "views") {
                trendMetric = "views"
                render()
            }
        ))
        val trend = Analytics.trend(all)
        c.add(BarChartView(this).apply {
            values = trend.map { if (trendMetric == "count") it.count.toDouble() else it.views.toDouble() }
            labels = trend.map { it.label }
            contentDescription = t("График тренда", "Trend chart")
        }, 8)

        c.h2(t("Топ каналов", "Top channels"))
        c.chips(listOf("videos" to t("по числу видео", "by videos"), "views" to t("по просмотрам", "by views"), "engagement" to t("по вовлечённости", "by engagement")).map { (k, l) ->
            chip(this, l, channelsBy == k) {
                channelsBy = k
                render()
            }
        })
        val chans = Analytics.topChannels(all, channelsBy, 10)
        c.add(BarChartView(this).apply {
            horizontal = chans.map { ch ->
                ch.name to when (channelsBy) {
                    "views" -> ch.views.toDouble()
                    "engagement" -> ch.engagement * 10000
                    else -> ch.videos.toDouble()
                }
            }
        }, 8)

        c.h2(t("Частые слова и темы", "Frequent words & topics"))
        c.chips(listOf("titles" to t("заголовки", "titles"), "descriptions" to t("описания", "descriptions"),
            "transcripts" to t("расшифровки", "transcripts"), "comments" to t("комментарии", "comments")).map { (k, l) ->
            chip(this, l, wordsSource == k) {
                wordsSource = k
                render()
            }
        })
        val texts = Analytics.textsOf(project.id, all, wordsSource).toList()
        val words = Words.top(texts.asSequence(), 30)
        if (words.isEmpty()) c.muted(t("Нет текста для анализа (соберите данные).", "No text to analyse (collect data)."))
        else c.add(wordCloud(words), 8)
        val bi = Words.topBigrams(texts.asSequence(), 12)
        if (bi.isNotEmpty()) c.muted(t("Словосочетания: ", "Phrases: ") + bi.joinToString(" · ") { "${it.first} (${it.second})" })

        c.h2(t("Топ видео", "Top videos"))
        c.chips(listOf("views" to t("просмотры", "views"), "engagement" to t("вовлечённость", "engagement"), "velocity" to t("быстрорастущие", "fastest growing")).map { (k, l) ->
            chip(this, l, videosBy == k) {
                videosBy = k
                render()
            }
        })
        Analytics.topVideos(all, videosBy, 10).forEachIndexed { i, v ->
            val metric = when (videosBy) {
                "engagement" -> Analytics.engagementPct(v)
                "velocity" -> Fmt.num(Analytics.velocity(v)) + t("/день", "/day")
                else -> Fmt.num(v.views)
            }
            c.add(text(this, "${i + 1}. ${v.title} — $metric", 14f).apply {
                setOnClickListener { startActivity(Intent(this@ProjectActivity, VideoActivity::class.java).putExtra("pid", project.id).putExtra("vid", v.id)) }
                minHeight = dp(40)
            }, 4)
        }

        c.h2(t("Сравнение", "Comparison"))
        val others = Store.projects().filter { it.id != project.id }
        val opts = listOf("" to t("— выберите —", "— choose —"), "period" to t("Первая половина периода vs вторая", "First half of period vs second")) +
            others.map { it.id to t("Проект: ", "Project: ") + it.name }
        c.spinner(t("Сравнить с", "Compare with"), opts, compareWith) {
            if (it != compareWith) {
                compareWith = it
                render()
            }
        }
        if (compareWith.isNotEmpty()) {
            val cmp = if (compareWith == "period") {
                val dated = all.filter { it.uploadMs > 0 }.sortedBy { it.uploadMs }
                val mid = dated.size / 2
                Analytics.compare(t("Ранние", "Earlier"), project.id, dated.take(mid), t("Поздние", "Later"), project.id, dated.drop(mid))
            } else {
                val other = others.first { it.id == compareWith }
                Analytics.compare(project.name, project.id, all, other.name, other.id, Store.videos(other.id).values)
            }
            c.card {
                fun line(label: String, a: String, b: String) = add(rowOf(ctx, text(ctx, label, 13f, color = Palette.MUTED), text(ctx, a, 14f, bold = true), text(ctx, b, 14f, bold = true)), 4)
                line("", cmp.nameA, cmp.nameB)
                line(t("Видео", "Videos"), cmp.a.total.toString(), cmp.b.total.toString())
                line(t("Просмотры", "Views"), Fmt.num(cmp.a.totalViews), Fmt.num(cmp.b.totalViews))
                line(t("Средние", "Average"), Fmt.num(cmp.a.avgViews), Fmt.num(cmp.b.avgViews))
                line(t("Комментарии", "Comments"), cmp.a.comments.toString(), cmp.b.comments.toString())
                val onlyA = cmp.wordsA.map { it.first }.filter { w -> cmp.wordsB.none { it.first == w } }.take(10)
                val onlyB = cmp.wordsB.map { it.first }.filter { w -> cmp.wordsA.none { it.first == w } }.take(10)
                muted(t("Уникальные темы ", "Unique topics ") + cmp.nameA + ": " + onlyA.joinToString(", "))
                muted(t("Уникальные темы ", "Unique topics ") + cmp.nameB + ": " + onlyB.joinToString(", "))
            }
        }
    }

    private fun wordCloud(words: List<Pair<String, Int>>): View {
        val max = words.first().second.toFloat()
        // word cloud as wrapped text with sized spans
        val sb = android.text.SpannableStringBuilder()
        words.shuffled(java.util.Random(words.size.toLong())).forEach { (w, n) ->
            val start = sb.length
            sb.append(w)
            val scale = 0.85f + 1.1f * (n / max)
            sb.setSpan(android.text.style.RelativeSizeSpan(scale), start, sb.length, 0)
            if (n / max > 0.5f) sb.setSpan(android.text.style.ForegroundColorSpan(Palette.ACCENT), start, sb.length, 0)
            sb.append("   ")
        }
        return text(this, "", 14f).apply {
            text = sb
            setLineSpacing(dp(4).toFloat(), 1.1f)
            background = roundBg(Palette.CARD, 12f, this@ProjectActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
    }

    // ------------------------------------------------------------------ AI
    fun aiRun(label: String, estimateTokens: Int, work: () -> AiResult) {
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        val cost = Llm.estimateCost(estimateTokens, estimateTokens / 5)
        val msg = t("Примерно ~$estimateTokens входных токенов", "About ~$estimateTokens input tokens") +
            (if (cost > 0) String.format(" · ≈ $%.4f", cost) else "") + "\n" +
            t("Повторный запуск с теми же данными берётся из кэша бесплатно.", "Re-running with the same data uses the free cache.")
        confirm(label, msg, t("Запустить", "Run")) {
            busy = "$label…"
            render()
            Lumen.async(work, {
                busy = ""
                render()
                err(it)
            }) { r ->
                busy = ""
                render()
                showText(label, r.text + "\n\n— ${r.model} · in ${r.inTokens} / out ${r.outTokens}")
            }
        }
    }

    private fun aiTab(c: Col) {
        val prov = Provider.active()
        c.card {
            p(if (prov != null) "${prov.title} · ${prov.fastModel} / ${prov.smartModel}" else t("AI не подключён", "AI not connected"))
            button(t("Настройки AI и промптов", "AI & prompt settings"), style = BtnStyle.TEXT) {
                startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true))
            }
        }
        val marked = videos.values.filter { it.marked }
        val scope = (if (marked.isNotEmpty()) marked else videos.values.toList())
        c.muted(t("Область: ", "Scope: ") + (if (marked.isNotEmpty()) t("отмеченные", "marked") else t("все", "all")) + " (${scope.size})")

        c.h2(t("Анализ", "Analysis"))
        c.button(t("Сводки по видео (с расшифровкой)", "Per-video summaries (with transcript)"), primary = true) {
            val target = scope.filter { it.hasTranscript }.ifEmpty { scope }.take(50)
            aiRun(t("Сводки по видео", "Video summaries"), AiTasks.estimateVideoSummaries(project.id, target)) {
                var last: AiResult? = null
                target.forEachIndexed { i, v ->
                    if (cancel.get()) return@forEachIndexed
                    Lumen.ui { progressView?.text = t("Сводка ${i + 1}/${target.size}: ", "Summary ${i + 1}/${target.size}: ") + v.title }
                    last = AiTasks.videoSummary(project, v)
                }
                val l = last ?: throw LlmException("—")
                l.copy(text = t("Готово: ${target.size} сводок. Откройте видео, чтобы прочитать сводку. Последняя:\n\n", "Done: ${target.size} summaries. Open a video to read its summary. Last one:\n\n") + l.text)
            }
        }
        c.button(t("Общая сводка по проекту", "Project summary")) {
            aiRun(t("Общая сводка", "Project summary"), AiTasks.estimateProject(project, scope)) { AiTasks.projectSummary(project, scope) }
        }
        c.button(t("Тематические кластеры", "Topic clusters")) {
            aiRun(t("Кластеры", "Clusters"), Llm.estimateTokens(scope.joinToString { it.title + it.description.take(200) }) + 2000) {
                AiTasks.clusters(project, scope)
            }
        }
        c.button(t("Тональность комментариев", "Comment sentiment")) {
            aiRun(t("Тональность", "Sentiment"), minOf(Settings.chunkChars * 3, scope.sumOf { it.commentsCount } * 60) / 3 + 2000) {
                AiTasks.sentiment(project, scope)
            }
        }

        c.h2(t("Чат с проектом", "Chat with project"))
        val q = c.edit(t("Вопрос по собранным данным…", "Ask about the collected data…"), multiline = true)
        c.button(t("Спросить", "Ask"), style = BtnStyle.TONAL) {
            val question = q.text.toString().trim()
            if (question.isEmpty()) return@button
            aiRun(t("Ответ", "Answer"), AiTasks.estimateProject(project, scope)) { AiTasks.chat(project, scope, question) }
        }

        c.h2(t("Результаты", "Results"))
        val results = Store.aiAll(project.id).filter { it.kind != "video_summary" }
        if (results.isEmpty()) c.muted("—")
        results.take(30).forEach { r ->
            val kind = when (r.kind) {
                "project_summary" -> t("Сводка", "Summary")
                "clusters" -> t("Кластеры", "Clusters")
                "sentiment" -> t("Тональность", "Sentiment")
                "chat" -> "💬 " + r.target
                else -> r.kind
            }
            val card = c.card {
                add(text(ctx, kind, 14f, bold = true), 0)
                muted(Fmt.dateTime(r.at) + " · " + r.model)
                add(text(ctx, r.text.take(220) + if (r.text.length > 220) "…" else "", 13f), 4)
            }
            card.setOnClickListener { showText(kind, r.text) }
        }
        val vs = Store.aiAll(project.id).count { it.kind == "video_summary" }
        if (vs > 0) c.muted(t("Сводок по видео: $vs (в карточках видео)", "Video summaries: $vs (inside video cards)"))
    }

    // ------------------------------------------------------------------ Export
    private var pendingExport: String = ""

    private fun exportTab(c: Col) {
        c.muted(t("Файл сохраняется в выбранную вами папку. API-ключи никогда не попадают в экспорт.",
            "The file is saved to a folder you choose. API keys are never exported."))
        val base = project.name.replace(Regex("[^\\p{L}\\p{Nd}_-]+"), "_").take(40)
        listOf(
            Triple("csv_videos", t("CSV — видео", "CSV — videos"), "$base-videos.csv"),
            Triple("csv_comments", t("CSV — комментарии", "CSV — comments"), "$base-comments.csv"),
            Triple("json", t("JSON — весь проект", "JSON — full project"), "$base.json"),
            Triple("md", t("Markdown — отчёт", "Markdown — report"), "$base-report.md"),
            Triple("pdf", t("PDF — отчёт с графиком", "PDF — report with chart"), "$base-report.pdf"),
            Triple("voc_csv", t("CSV — боли, вопросы, запросы, возражения", "CSV — pains, questions, requests, objections"), "$base-voc.csv"),
            Triple("insights_json", t("JSON — AI-инсайты с цитатами", "JSON — AI insights with quotes"), "$base-insights.json"),
            Triple("clusters_json", t("JSON — темы, брифы, статьи", "JSON — topics, briefs, articles"), "$base-topics.json"),
            Triple("gexf", t("GEXF — граф рекомендаций (Gephi)", "GEXF — recommendation graph (Gephi)"), "$base-recs.gexf"),
            Triple("recs_nodes", t("CSV — узлы графа", "CSV — graph nodes"), "$base-recs-nodes.csv"),
            Triple("recs_edges", t("CSV — связи графа", "CSV — graph edges"), "$base-recs-edges.csv")
        ).forEach { (kind, label, file) ->
            c.button(label, style = BtnStyle.TONAL) {
                pendingExport = kind
                val mime = when (kind) {
                    "json" -> "application/json"
                    "md" -> "text/markdown"
                    "pdf" -> "application/pdf"
                    "insights_json", "clusters_json" -> "application/json"
                    "gexf" -> "application/xml"
                    else -> "text/csv"
                }
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, file)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_EXPORT)
            }
        }
        c.button(t("Поделиться отчётом (текст)", "Share report (text)"), style = BtnStyle.OUTLINED) {
            Lumen.async({ Export.markdown(project.id) }, ::err) { shareText(project.name, it) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<android.net.Uri>()
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importFiles(uris)
            return
        }
        val uri = data?.data ?: return
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val kind = pendingExport
        busy = t("Экспорт…", "Exporting…")
        render()
        Lumen.async({
            contentResolver.openOutputStream(uri)?.use { out -> Export.write(this, project.id, kind, out) }
        }, {
            busy = ""
            render()
            err(it)
        }) {
            busy = ""
            render()
            toast(t("Сохранено", "Saved"))
        }
    }

    // ------------------------------------------------------------------ Settings
    private fun settingsTab(c: Col) {
        val name = c.edit(t("Название", "Name"), project.name)
        val phrase = c.edit(t("Фраза", "Phrase"), project.phrase)
        val domain = c.edit(t("Доменная область", "Domain"), project.domain)
        c.muted(t("Поисковые запросы (по одному в строке)", "Search queries (one per line)"))
        val queries = c.edit("", project.queries.joinToString("\n"), multiline = true)
        c.h2(t("Фильтры поиска", "Search filters"))
        val f = SearchFilters.from(project.filters.toJson())
        c.filtersEditor(f)

        c.h2(t("Мониторинг", "Monitoring"))
        val m = MonitorConfig.from(project.monitor.toJson())
        if (!Settings.monitoringAllowed) {
            c.muted(t("Мониторинг выключен в общих настройках.", "Monitoring is disabled in global settings."))
            c.button(t("Включить в настройках", "Enable in settings"), style = BtnStyle.TEXT) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        } else {
            c.check(t("Проверять новые видео по расписанию", "Check for new videos on a schedule"), m.enabled) { m.enabled = it }
            c.spinner(t("Частота", "Frequency"),
                listOf(1 to t("каждый час", "hourly"), 3 to t("каждые 3 ч", "every 3 h"), 6 to t("каждые 6 ч", "every 6 h"),
                    12 to t("каждые 12 ч", "every 12 h"), 24 to t("ежедневно", "daily"), 168 to t("еженедельно", "weekly")),
                m.hours) { m.hours = it }
            c.check(t("Только по Wi-Fi", "Wi-Fi only"), m.wifiOnly) { m.wifiOnly = it }
            c.check(t("Только при зарядке", "Only while charging"), m.charging) { m.charging = it }
            c.check(t("AI-сводка новых видео в уведомлении", "AI summary of new videos in notification"), m.aiSummary) { m.aiSummary = it }
            c.spinner(t("Капельный режим: добавлять рекомендованных за запуск", "Drip mode: recommended videos added per run"),
                listOf(0 to t("выкл", "off"), 1 to "1", 3 to "3", 5 to "5", 10 to "10"), m.recDrip) { m.recDrip = it }
            c.muted(t("Берёт лучшие видео из графа рекомендаций, которых ещё нет в проекте, и расширяет граф от них.",
                "Takes the best videos from the recommendation graph not yet in the project and grows the graph from them."))
            c.button(t("Проверить сейчас", "Check now"), style = BtnStyle.OUTLINED) {
                busy = t("Проверка новых видео…", "Checking for new videos…")
                render()
                Lumen.async({ Monitoring.runOnce(this, project.id) }, {
                    busy = ""
                    render()
                    err(it)
                }) { n ->
                    busy = ""
                    reload()
                    render()
                    toast(t("Новых видео: $n", "New videos: $n"))
                }
            }
        }

        c.button(t("Сохранить", "Save"), primary = true) {
            project.name = name.text.toString().trim().ifBlank { project.phrase }
            project.phrase = phrase.text.toString().trim().ifBlank { project.phrase }
            project.domain = domain.text.toString().trim()
            project.queries = queries.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            project.filters = f
            project.monitor = m
            Store.save(project)
            Monitoring.apply(this, project)
            toast(t("Сохранено", "Saved"))
            render()
        }
        c.divider()
        c.button(if (project.archived) t("Вернуть из архива", "Unarchive") else t("В архив", "Archive"), style = BtnStyle.OUTLINED) {
            project.archived = !project.archived
            Store.save(project)
            Monitoring.apply(this, project)
            finish()
        }
        c.button(t("Удалить проект", "Delete project"), style = BtnStyle.TEXT) {
            confirm(t("Удалить проект?", "Delete project?"), t("Все собранные данные будут удалены.", "All collected data will be deleted."), t("Удалить", "Delete")) {
                Monitoring.cancel(this, project.id)
                Store.delete(project.id)
                finish()
            }
        }
    }

    companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
    }
}

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
import android.view.ViewGroup
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
    var progressBar: android.widget.ProgressBar? = null

    // recommendations / audience / SEO tab state (see RecsTab.kt, AudienceTab.kt, SeoTab.kt)
    val ui = HashMap<String, Any>()

    // video list view state
    private var listMode = "all" // all | marked | uncollected
    private var searchQuery = ""
    private var keepScrollOnce = false
    private var onMarkChanged: (() -> Unit)? = null
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
                        CollectState.kind == "recs" || CollectState.kind == "audit" -> toast(CollectState.lastResult)
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
        tab = intent.getStringExtra("tab") ?: if (p.source.isNotBlank()) "overview" else "videos"
        if (project.newCount > 0) {
            project.newCount = 0
            Store.save(project)
        }
        Lumen.prefs.getString("vl_" + project.id, null)?.split("|", limit = 3)?.takeIf { it.size == 3 }?.let {
            listMode = it[0].ifBlank { "all" }
            listSort = it[1].ifBlank { "added" }
            labelFilter = it[2]
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
        AutoBackup.maybe(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel.set(true)
    }

    fun reload() {
        Store.project(project.id)?.let { project = it }
        videos = Store.videos(project.id)
    }

    private class NavGroup(val icon: Ic, val name: String, val tabs: List<String>)

    private val tabNames get() = mapOf(
        "overview" to t("Обзор", "Overview"), "videos" to t("Видео", "Videos"), "collect" to t("Сбор", "Collect"),
        "channels" to t("Каналы", "Channels"), "analytics" to t("Анализ", "Analytics"), "recs" to t("Рекомендации", "Recommendations"),
        "audience" to t("Аудитория", "Audience"), "seo" to "SEO", "ai" to "AI",
        "export" to t("Экспорт", "Export"), "schemes" to t("Схемы", "Diagrams"), "settings" to t("Настройки", "Settings")
    )

    /** Bottom navigation: related sections share one destination and switch with a segmented bar. */
    private val groups get() = listOfNotNull(
        if (project.source.isNotBlank()) NavGroup(Ic.HOME, t("Обзор", "Overview"), listOf("overview")) else null,
        NavGroup(Ic.LIST, t("Данные", "Data"), listOf("videos", "collect", "channels")),
        NavGroup(Ic.CHART, t("Анализ", "Analysis"), listOf("analytics", "recs", "audience", "seo")),
        NavGroup(Ic.SPARK, "AI", listOf("ai")),
        NavGroup(Ic.MORE, t("Ещё", "More"), listOf("export", "schemes", "settings"))
    )
    private val groupTab = HashMap<Int, String>()

    fun render() {
        Lumen.prefs.edit().putString("vl_" + project.id, "$listMode|$listSort|$labelFilter").apply()
        val c = Col(this)
        progressView = null
        progressBar = null
        if (busy.isNotEmpty() || (CollectState.running && CollectState.projectId == project.id)) {
            c.card {
                val (pv, bar) = working(busy)
                progressView = pv
                progressBar = bar
                pv.addTextChangedListener(SimpleWatcher { bar.showProgress(it) })
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
            "overview" -> overviewTab(c)
            "channels" -> channelsTab(c)
            "analytics" -> analyticsTab(c)
            "audience" -> audienceTab(c)
            "seo" -> seoTab(c)
            "ai" -> aiTab(c)
            "export" -> exportTab(c)
            "schemes" -> schemesTab(c)
            else -> settingsTab(c)
        }
        val running = busy.isNotEmpty() || (CollectState.running && CollectState.projectId == project.id)
        val markedCount = videos.values.count { it.marked }
        val fab: Pair<String, () -> Unit>? = when {
            running -> t("Остановить", "Stop") to {
                cancel.set(true)
                if (CollectState.running) CollectService.stop(this@ProjectActivity)
            }
            tab == "videos" && markedCount > 0 -> t("Собрать ($markedCount)", "Collect ($markedCount)") to { startCollect(selectedForCollect()) }
            tab == "videos" -> t("Найти", "Search") to { runSearch() }
            tab == "collect" -> t("Собрать", "Collect") to { startCollect(selectedForCollect()) }
            tab == "recs" -> t("Запустить", "Run") to { startRecs() }
            else -> null
        }
        val gl = groups
        val gi = maxOf(0, gl.indexOfFirst { tab in it.tabs })
        val group = gl[gi]
        groupTab[gi] = tab
        val header: View? = if (group.tabs.size > 1) {
            LinearLayout(this).apply {
                setPadding(dp(16), dp(4), dp(16), dp(8))
                addView(segBar(this@ProjectActivity, group.tabs.map { tabNames[it] ?: it }, group.tabs.indexOf(tab)) {
                    tab = group.tabs[it]
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else null
        val keep = keepScrollOnce
        keepScrollOnce = false
        page(
            project.name, c.root,
            header = header,
            actions = listOf("⋮" to { projectMenu() }),
            fab = fab,
            footer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val scope = { videos.values.filter { it.marked }.ifEmpty { videos.values.toList() } }
                addView(askAiBar(this@ProjectActivity, AskAi.hint(tab), { askAiSheet(project, scope(), tab) }, { askAiSheet(project, scope(), tab, startVoice = true) }))
                addView(bottomNav(this@ProjectActivity, gl.map { it.icon to it.name }, gi) {
                    tab = groupTab[it]?.takeIf { id -> id in gl[it].tabs } ?: gl[it].tabs[0]
                    render()
                })
            },
            footerExtra = dp(56),
            keepScroll = keep
        )
    }

    private fun projectMenu() {
        val items = arrayOf(t("Импорт видео", "Import videos"), t("Фильтры поиска проекта", "Project search filters"))
        AlertDialog.Builder(this).setTitle(project.name).setItems(items) { _, i ->
            if (i == 0) importDialog() else {
                tab = "settings"
                render()
            }
        }.show()
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
            t("Файл TXT / CSV / JSON (транскрипт или комментарии)", "TXT / CSV / JSON file (transcript or comments)"),
            t("Быстрый анализ ссылки (отдельный проект)", "Instant analysis of a link (new project)")
        )
        AlertDialog.Builder(this).setTitle(t("Импорт", "Import")).setItems(items) { _, which ->
            if (which == 5) {
                prompt(t("Ссылка на видео, канал или плейлист", "Video, channel or playlist link"), "https://youtube.com/…") { chooseLinkAction(it) }
            } else if (which == 3) {
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
            val list = Par.map(urls) { _, u ->
                Par.gate()
                YouTube.single(u)
            }.filterNotNull()
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
        if (listMode == "marked") list = list.filter { it.marked }
        if (listMode == "uncollected") list = list.filter { !it.collected }
        val q = searchQuery.trim().lowercase()
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.channel.lowercase().contains(q) }
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

    private fun sortName() = when (listSort) {
        "date" -> t("дата публикации", "upload date")
        "views" -> t("просмотры", "views")
        "velocity" -> t("быстрорастущие", "fastest growing")
        else -> t("недавно добавленные", "recently added")
    }

    private fun filtersSheet() {
        val labels = videos.values.flatMap { it.labels }.distinct().sorted()
        bottomSheet(t("Фильтры", "Filters"), onDismiss = {
            shown = 50
            render()
        }) { dismiss ->
            lateinit var showBtn: android.widget.Button
            fun upd() {
                showBtn.text = t("Показать ${filteredVideos().size}", "Show ${filteredVideos().size}")
            }
            add(text(ctx, t("Сортировка", "Sort"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            add(choiceChips(ctx, listOf(
                "added" to t("Недавние", "Recent"), "date" to t("Дата", "Date"),
                "views" to t("Просмотры", "Views"), "velocity" to t("Быстрорастущие", "Fastest growing")
            ), listSort) {
                listSort = it
                upd()
            }, 8)
            if (labels.isNotEmpty()) {
                add(text(ctx, t("Метки", "Labels"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
                add(choiceChips(ctx, listOf("" to t("Все", "All")) + labels.map { it to "#$it" }, labelFilter) {
                    labelFilter = it
                    upd()
                }, 8)
            }
            showBtn = btn(ctx, "", primary = true) { dismiss() }
            row(
                btn(ctx, t("Сбросить", "Reset"), style = BtnStyle.OUTLINED) {
                    listSort = "added"
                    labelFilter = ""
                    dismiss()
                },
                showBtn
            )
            upd()
        }
    }

    private fun videosTab(c: Col) {
        val total = videos.size
        val marked = videos.values.count { it.marked }
        val uncollected = videos.values.count { !it.collected }

        // selection bar: marked videos are the ones collected next
        val selText = text(this, "", 14f, bold = true, color = Palette.ON_SOFT)
        val selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Palette.ACCENT_SOFT, 12f, this@ProjectActivity)
            setPadding(dp(16), 0, dp(4), 0)
            addView(selText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(this@ProjectActivity, t("Все", "All"), style = BtnStyle.TEXT) { setMarked(filteredVideos().map { it.id }, true) })
            addView(btn(this@ProjectActivity, t("Снять", "Clear"), style = BtnStyle.TEXT) { setMarked(videos.keys, false) })
        }
        var markedChip: TextView? = null
        fun refreshSel() {
            val m = videos.values.count { it.marked }
            selText.text = t("Выбрано: $m", "Selected: $m")
            markedChip?.text = t("Отмеченные $m", "Marked $m")
            selBar.visibility = if (m > 0) View.VISIBLE else View.GONE
        }
        refreshSel()
        c.add(selBar, 0)
        onMarkChanged = {
            val m = videos.values.count { it.marked }
            if ((m > 0) != (marked > 0)) {
                keepScrollOnce = true
                render()
            } else {
                refreshSel()
            }
        }

        val search = edt(this, t("Поиск по названию или каналу", "Search title or channel"), searchQuery)
        val active = (if (labelFilter.isNotEmpty()) 1 else 0) + (if (listSort != "added") 1 else 0)
        val filterBtn = btn(this, if (active > 0) t("Фильтры · $active", "Filters · $active") else t("Фильтры", "Filters"), style = BtnStyle.TONAL) { filtersSheet() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(filterBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        c.add(bar, 8)

        c.chips(
            listOf(
                "all" to t("Все $total", "All $total"), "marked" to t("Отмеченные $marked", "Marked $marked"),
                "uncollected" to t("Не собранные $uncollected", "Not collected $uncollected")
            ).map { (k, label) ->
                chip(this, label, listMode == k) {
                    listMode = k
                    shown = 50
                    render()
                }.also { if (k == "marked") markedChip = it }
            }
        )
        val summary = text(this, "", 13f, bold = true, color = Palette.MUTED).apply {
            minimumHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { filtersSheet() }
        }
        c.add(summary, 4)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.add(box, 0)

        fun fill() {
            box.removeAllViews()
            val list = filteredVideos()
            summary.text = t("Сначала: ${sortName()} ▾ · ${list.size} видео", "Sorted by ${sortName()} ▾ · ${list.size} videos")
            if (list.isEmpty()) {
                if (total == 0) {
                    val e = Col(this, 0)
                    e.empty(t("Пока нет видео", "No videos yet"),
                        t("Нажмите «Найти» или импортируйте канал или плейлист через ⋮.", "Tap Search or import a channel or playlist via ⋮."),
                        t("Найти видео", "Search videos")) { runSearch() }
                    box.addView(e.root)
                } else {
                    box.addView(text(this, t("Нет видео под выбранный фильтр.", "No videos match the filter."), 13f, color = Palette.MUTED))
                }
            }
            for (v in list.take(shown)) {
                box.addView(videoRow(v), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            if (list.size > shown) {
                box.addView(btn(this, t("Показать ещё (${list.size - shown})", "Show more (${list.size - shown})"), style = BtnStyle.TEXT) {
                    shown += 100
                    fill()
                })
            }
        }
        search.addTextChangedListener(SimpleWatcher {
            searchQuery = it
            shown = 50
            fill()
        })
        fill()
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
                onMarkChanged?.invoke()
            }
        }
        row.addView(cb, LinearLayout.LayoutParams(dp(48), dp(48)))
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(Palette.ACCENT_SOFT, 8f, this@ProjectActivity)
            clipToOutline = true
        }
        row.addView(img, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(12) })
        Thumbs.load(img, ThumbUrl.mini(v.id, v.thumb))
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
        if (v.url.isNotBlank()) {
            row.setPadding(dp(4), dp(8), 0, dp(8))
            row.addView(mediaBtn(this, v.url, v.title), LinearLayout.LayoutParams(dp(44), dp(48)))
        }
        row.setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java).putExtra("pid", project.id).putExtra("vid", v.id))
        }
        row.setOnLongClickListener {
            cb.isChecked = !cb.isChecked
            true
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
            c.add(videoListRow(this, ThumbUrl.mini(v.id, v.thumb), "${i + 1}. ${v.title}", v.channel, metric, url = v.url) {
                startActivity(Intent(this@ProjectActivity, VideoActivity::class.java).putExtra("pid", project.id).putExtra("vid", v.id))
            }, 6)
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
                val started = java.util.concurrent.atomic.AtomicInteger(0)
                val firstError = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
                val results = Par.map(target, cancel = cancel) { _, v ->
                    val i = started.incrementAndGet()
                    Lumen.ui { progressView?.text = t("Сводка $i/${target.size}: ", "Summary $i/${target.size}: ") + v.title }
                    try { AiTasks.videoSummary(project, v) } catch (e: Exception) { firstError.compareAndSet(null, e); throw e }
                }
                val l = results.lastOrNull { it != null } ?: throw LlmException(
                    if (cancel.get()) t("Отменено", "Cancelled")
                    else t("Не удалось получить ни одной сводки: ", "No summaries could be produced: ") + (firstError.get()?.message ?: "?"),
                    (firstError.get() as? LlmException)?.raw ?: ""
                )
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

        c.button(t("Все ответы AI проекта (", "All AI answers of the project (") + AiJournal.list(project.id).size + ")", style = BtnStyle.OUTLINED) {
            AiJournalActivity.open(this, project.id)
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
        reportsBlock(c, base)
        c.h2(t("Файлы данных", "Data files"))
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
                    "pdf", "graph_pdf", "chgraph_pdf", "dossier_pdf" -> "application/pdf"
                    "graph_png", "chgraph_png", "dossier_png" -> "image/png"
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

    /** Phone-format reports: AI dossier of channels and videos, graph reports. Share or save as PDF / PNG. */
    private fun reportsBlock(c: Col, base: String) {
        c.h2(t("Отчёты для телефона", "Phone reports"))
        c.muted(t("PDF и картинки под экран телефона: описания, графики, связи. Удобно отправить в мессенджер.",
            "PDFs and images sized for a phone screen: descriptions, charts, links. Easy to send in a messenger."))
        fun shareRow(col: Col, kind: String, file: String) {
            col.row(
                btn(col.ctx, t("PDF ↗", "PDF ↗"), style = BtnStyle.TONAL) { shareReport(kind + "_pdf", "$file.pdf") },
                btn(col.ctx, t("Картинка ↗", "Image ↗"), style = BtnStyle.TONAL) { shareReport(kind + "_png", "$file.png") },
                btn(col.ctx, t("Сохранить", "Save"), style = BtnStyle.TEXT) { saveReport(kind, file) }
            )
        }
        val day = Fmt.isoDay(System.currentTimeMillis())
        val cfg = Dossier.Config.load()
        c.card {
            add(text(ctx, t("🗂 AI-досье: каналы и видео", "🗂 AI dossier: channels & videos"), Type.TITLE_M, bold = true), 0)
            muted(t("О чём каждый канал и каждое видео, ключевые тезисы, о чём говорят в комментариях и их настроение, общая картина ниши.",
                "What every channel and video is about, key points, what viewers say in the comments and their mood, the big picture of the niche."))
            val status = muted(t("Считаю…", "Counting…"))
            Lumen.async({ DossierReport.status(project.id) }) { (total, ready) ->
                status.text = t("Каналов: $total (до ${cfg.videos} видео на каждый) · AI-описание готово: $ready", "Channels: $total (up to ${cfg.videos} videos each) · AI description ready: $ready")
            }
            button(t("Составить досье (AI)", "Build dossier (AI)"), style = BtnStyle.FILLED) { dossierDialog() }
            shareRow(this, "dossier", "$base-dossier-$day")
        }
        c.card {
            add(text(ctx, t("✍️ Свой отчёт (AI)", "✍️ Custom report (AI)"), Type.TITLE_M, bold = true), 0)
            muted(t("Напишите, что должно быть в отчёте, и выберите формат: AI соберёт содержание по данным проекта, приложение оформит файл.",
                "Describe what the report should contain and pick a format: the AI writes the content from the project data, the app lays out the file."))
            val req = edit(t("Например: сравни топ-5 каналов и дай 3 идеи для видео", "E.g. compare the top 5 channels and give 3 video ideas"),
                Lumen.prefs.getString("custom_report_req", "").orEmpty(), multiline = true)
            spinner(t("Формат", "Format"), CustomReport.Format.values().map { it to t(it.ru, it.en) }, customFormat) {
                customFormat = it
                Lumen.prefs.edit().putString("custom_report_fmt", it.name).apply()
            }
            button(t("Составить отчёт (AI)", "Build report (AI)"), style = BtnStyle.FILLED) { customReport(req.text.toString()) }
            CustomReport.latest(project.id)?.let { r ->
                muted(t("Последний: ", "Last: ") + Fmt.dateTime(r.at) + " · " + r.target)
                row(
                    btn(ctx, t("Поделиться ↗", "Share ↗"), style = BtnStyle.TONAL) { shareCustom() },
                    btn(ctx, t("Сохранить", "Save"), style = BtnStyle.TEXT) { saveCustom() },
                    btn(ctx, t("Читать", "Read"), style = BtnStyle.TEXT) { showText(r.target, CustomReport.plain(CustomReport.parse(r.text))) }
                )
            }
        }
        if (RecGraph.load(project.id).nodes.isNotEmpty()) c.card {
            add(text(ctx, t("🕸 Граф рекомендаций", "🕸 Recommendation graph"), Type.TITLE_M, bold = true), 0)
            muted(t("Карта связей, выводы простым языком, графики по шагам и теме, центральные узлы и ключевые связи.",
                "Map of links, plain-language findings, charts by step and topic, central nodes and key links."))
            shareRow(this, "graph", "$base-graph-$day")
        }
        if (RecGraph.load(project.id, RecGraph.CHANNELS_FILE).nodes.isNotEmpty()) c.card {
            add(text(ctx, t("📡 Граф каналов", "📡 Channel graph"), Type.TITLE_M, bold = true), 0)
            muted(t("Какие каналы YouTube связывает между собой: карта, лидеры, взаимные связи, подписчики.",
                "Which channels YouTube links together: map, leaders, mutual links, subscribers."))
            shareRow(this, "chgraph", "$base-channels-$day")
        }
    }

    private var customFormatSet: CustomReport.Format? = null
    private var customFormat: CustomReport.Format
        get() = customFormatSet ?: (CustomReport.Format.values().firstOrNull { it.name == Lumen.prefs.getString("custom_report_fmt", "") } ?: CustomReport.Format.PDF)
        set(v) { customFormatSet = v }

    /** One AI request from the user's wording, then offers to share, save or read the result in the chosen format. */
    private fun customReport(request: String) {
        if (request.isBlank()) return toast(t("Опишите, что должно быть в отчёте", "Describe what the report should contain"))
        Lumen.prefs.edit().putString("custom_report_req", request.trim()).apply()
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        if (busy.isNotEmpty()) return
        busy = t("AI составляет отчёт…", "The AI is writing the report…")
        render()
        Lumen.async({ CustomReport.generate(project.id, request) }, {
            busy = ""
            render()
            err(it)
        }) {
            busy = ""
            render()
            val items = arrayOf(t("Поделиться ↗", "Share ↗"), t("Сохранить в файл", "Save to file"), t("Читать", "Read"))
            android.app.AlertDialog.Builder(this).setTitle(t("Отчёт готов", "Report is ready")).setItems(items) { _, w ->
                when (w) {
                    0 -> shareCustom()
                    1 -> saveCustom()
                    else -> showText(project.name, CustomReport.plain(CustomReport.parse(it)))
                }
            }.setNegativeButton(t("Закрыть", "Close"), null).show()
        }
    }

    private fun shareCustom() {
        val f = customFormat
        busy = t("Готовлю отчёт…", "Preparing the report…")
        render()
        Lumen.async({
            val file = ReportKit.shareFile(this, ReportKit.safeName(project.name) + "-report-" + Fmt.isoDay(System.currentTimeMillis()) + "." + f.ext)
            file.outputStream().use { CustomReport.write(project.id, f, it) }
            file
        }, {
            busy = ""
            render()
            err(it)
        }) { file ->
            busy = ""
            render()
            ReportKit.share(this, file, f == CustomReport.Format.PDF, project.name, f.mime)
        }
    }

    private fun saveCustom() {
        val f = customFormat
        pendingExport = f.kind
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.mime)
            .putExtra(Intent.EXTRA_TITLE, ReportKit.safeName(project.name) + "-report." + f.ext)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun shareReport(kind: String, file: String) {
        busy = t("Готовлю отчёт…", "Preparing the report…")
        render()
        Lumen.async({
            val f = ReportKit.shareFile(this, file)
            f.outputStream().use { Export.write(this, project.id, kind, it) }
            f
        }, {
            busy = ""
            render()
            err(it)
        }) { f ->
            busy = ""
            render()
            ReportKit.share(this, f, kind.endsWith("pdf"), project.name)
        }
    }

    private fun saveReport(kind: String, file: String) {
        val items = arrayOf("PDF", t("Картинка PNG", "PNG image"))
        android.app.AlertDialog.Builder(this).setTitle(t("Сохранить как", "Save as")).setItems(items) { _, w ->
            val pdf = w == 0
            pendingExport = kind + if (pdf) "_pdf" else "_png"
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (pdf) "application/pdf" else "image/png").putExtra(Intent.EXTRA_TITLE, file + if (pdf) ".pdf" else ".png")
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_EXPORT)
        }.show()
    }

    /** Scope of the AI dossier, cost estimate, then the background job. */
    private fun dossierDialog() {
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        if (CollectState.running) return toast(t("Уже идёт другая задача — дождитесь её конца", "Another job is running — wait for it to finish"))
        val presets = listOf(
            Dossier.Config(5, 4) to t("Быстро: 5 каналов × 4 видео", "Quick: 5 channels × 4 videos"),
            Dossier.Config(10, 6) to t("Стандарт: 10 каналов × 6 видео", "Standard: 10 channels × 6 videos"),
            Dossier.Config(25, 8) to t("Подробно: 25 каналов × 8 видео", "Detailed: 25 channels × 8 videos"),
            Dossier.Config(60, 10) to t("Максимум: 60 каналов × 10 видео", "Maximum: 60 channels × 10 videos")
        )
        android.app.AlertDialog.Builder(this).setTitle(t("Объём досье", "Dossier scope"))
            .setItems(presets.map { it.second }.toTypedArray()) { _, w ->
                val cfg = presets[w].first
                Dossier.Config.save(cfg)
                Lumen.async({ Dossier.estimate(project.id, cfg) }, ::err) { est ->
                    val cost = Llm.estimateCost(est, est / 3)
                    val msg = t("Один AI-запрос на канал и один на общую картину. Примерно ~$est входных токенов", "One AI request per channel plus one for the overview. About ~$est input tokens") +
                        (if (cost > 0) String.format(" · ≈ $%.4f", cost) else "") + "\n" +
                        t("Готовые каналы берутся из кэша бесплатно. Задача идёт в фоне, прогресс — в уведомлении.",
                            "Ready channels come from the free cache. The job runs in the background, progress is in the notification.")
                    confirm(t("Составить досье", "Build dossier"), msg, t("Запустить", "Run")) {
                        CollectService.startKind(this, project.id, "dossier")
                        toast(t("Досье составляется в фоне", "The dossier is being built in the background"))
                    }
                }
            }.show()
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
            c.check(t("AI-отсев: добавлять только видео по теме", "AI filter: add only on-topic videos"), m.aiFilter) { m.aiFilter = it }
            c.muted(t("Без AI-ключа отсев идёт по ключевым словам темы. Отсеянные видео не проверяются повторно.",
                "Without an AI key the filter uses the topic's keywords. Rejected videos are not checked again."))
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

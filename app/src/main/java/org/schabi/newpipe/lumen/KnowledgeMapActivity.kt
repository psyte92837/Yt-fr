/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Knowledge map of a project: a tree of aspects (or a radial map of the same tree), each branch with its
 * own AI summary. Any branch can be put into focus; new videos since the last visit are marked.
 */
class KnowledgeMapActivity : LumenBase() {
    private lateinit var project: Project
    private var root: KNode? = null
    private var input: KInput? = null
    private val open = LinkedHashSet<String>()
    private var summaries = HashMap<String, AiResult>() // node id → latest summary
    private var busy = ""
    private var query = ""
    private var focus = "map"
    private var radial = false
    private var since = 0L
    private var mapView: KnowledgeMapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra("id")?.let { Store.project(it) }
        if (p == null) {
            finish()
            return
        }
        project = p
        Lumen.prefs.getString("kmap_open_" + p.id, null)?.split('\n')?.filter { it.isNotBlank() }?.let { open.addAll(it) }
        open.add("map")
        radial = Lumen.prefs.getBoolean("kmap_radial", false)
        focus = Lumen.prefs.getString("kmap_focus_" + p.id, null) ?: "map"
        since = Lumen.prefs.getLong("kmap_seen_" + p.id, 0L)
        load()
    }

    override fun onPause() {
        super.onPause()
        if (::project.isInitialized) Lumen.prefs.edit()
            .putString("kmap_open_" + project.id, open.take(200).joinToString("\n"))
            .putString("kmap_focus_" + project.id, focus)
            .putLong("kmap_seen_" + project.id, System.currentTimeMillis())
            .apply()
    }

    private fun load() {
        busy = t("Строю карту…", "Building the map…")
        render()
        val pid = project.id
        Lumen.async({
            val ai = Store.aiAll(pid)
            val sums = HashMap<String, String>()
            ai.filter { it.kind == "video_summary" }.forEach { sums.putIfAbsent(it.target, it.text) }
            val insights = try {
                Seo.insights(pid).map { KInsight(it.optString("type"), it.optString("statement"), it.optString("video_title")) }
            } catch (e: Exception) { emptyList() }
            val recs = try { RecEngine.ranked(RecGraph.load(pid), "hits").take(40) } catch (e: Exception) { emptyList() }
            val inp = KInput(Store.project(pid) ?: project, Store.videos(pid).values.toList(), sums, { Store.comments(pid, it) }, insights, recs, ai, since)
            val tree = KMap.build(inp)
            tree.children.size // aspects are cheap: build them off the main thread
            val maps = HashMap<String, AiResult>()
            ai.filter { it.kind == KMap.KIND }.forEach { maps.putIfAbsent(it.target, it) }
            Triple(inp, tree, maps)
        }, {
            busy = ""
            render()
            err(it)
        }) { (inp, tree, maps) ->
            project = inp.project
            input = inp
            root = tree
            summaries = maps
            if (KMap.find(tree, focus) == null) focus = "map"
            busy = ""
            render()
        }
    }

    private fun render(keep: Boolean = false) {
        val tree = root
        val f = tree?.let { KMap.find(it, focus) } ?: tree
        if (tree != null && f != null && radial && busy.isEmpty()) return renderMap(tree, f)
        val c = Col(this)
        c.card {
            add(text(ctx, project.name.ifBlank { project.phrase }, Type.TITLE_M, bold = true), 0)
            muted("«${project.phrase}»" + if (project.domain.isNotBlank()) " · ${project.domain}" else "")
            if (tree != null) muted(t("Аспектов: ", "Aspects: ") + tree.children.size + " · " + tree.sub +
                if (tree.fresh > 0) " · " + t("новых: ", "new: ") + tree.fresh else "")
            muted(t("Нажмите на узел, чтобы раскрыть. ✨ — AI-сводка по ветке. Долгое нажатие — фокус на ветке, открыть видео или канал.",
                "Tap a node to open it. ✨ makes an AI summary of the branch. Long press: focus on the branch, open the video or channel."))
        }
        if (busy.isNotEmpty()) c.card { working(busy) }
        if (tree == null || f == null) {
            page(t("Карта знаний", "Knowledge map"), c.root)
            return
        }
        val search = c.edit(t("Найти на карте…", "Find on the map…"), query)
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        search.setSingleLine()
        search.setOnEditorActionListener { v, _, _ ->
            query = v.text.toString().trim()
            render()
            true
        }
        c.row(
            btn(this, if (f === tree) t("✨ Сводка по проекту", "✨ Project summary") else t("✨ Сводка по ветке", "✨ Branch summary"),
                style = BtnStyle.TONAL) { summary(f) },
            btn(this, t("Свернуть", "Collapse"), style = BtnStyle.TEXT) {
                open.clear()
                open.add("map")
                query = ""
                render()
            }
        )
        if (f === tree) c.row(
            btn(this, t("❓ Пробелы", "❓ Gaps"), style = BtnStyle.TEXT) { mapTask(KMap.GAPS) },
            btn(this, t("🧭 Путь обучения", "🧭 Learning path"), style = BtnStyle.TEXT) { mapTask(KMap.PATH) }
        )
        summaries[f.id]?.let { summaryCard(c, f, it) }
        if (query.isNotBlank()) {
            searchResults(c, tree)
        } else {
            f.children.forEach { drawNode(c, it, f) }
        }
        page(t("Карта знаний", "Knowledge map"), c.root, header = header(tree), actions = listOf("⋮" to { menu(tree) }), keepScroll = keep)
    }

    /** Fixed header: tree/map switch and, in focus, the way back to the root. */
    private fun header(tree: KNode): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        col.addView(chipScroller(this, listOf(
            chip(this, "🌳 " + t("Дерево", "Tree"), !radial) { setRadial(false) },
            chip(this, "🪐 " + t("Карта", "Map"), radial) { setRadial(true) }
        )))
        if (focus != tree.id) {
            val ids = mutableListOf(focus)
            while (ids.last().contains('/')) ids += ids.last().substringBeforeLast('/')
            val crumbs = ids.reversed().mapNotNull { id -> KMap.find(tree, id)?.let { id to it.title } }
            val chips = crumbs.mapIndexed { i, (id, title) ->
                val last = i == crumbs.size - 1
                val label = (if (i == 0) "🏠 " else "› ") + if (title.length > 24) title.take(23) + "…" else title
                chip(this, label, last) { if (!last) setFocus(id) }
            }
            col.addView(chipScroller(this, chips), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        return col
    }

    private fun setRadial(on: Boolean) {
        if (radial == on) return
        radial = on
        Lumen.prefs.edit().putBoolean("kmap_radial", on).apply()
        render()
    }

    private fun setFocus(id: String) {
        focus = id
        open.add(id)
        mapView?.selected = ""
        render()
    }

    /** Radial map of the focused branch. */
    private fun renderMap(tree: KNode, f: KNode) {
        val v = mapView ?: KnowledgeMapView(this).also { mapView = it }
        v.onTap = { n ->
            if (n.children.isEmpty()) {
                v.selected = n.id
                showText(n.title.take(60), n.info.ifBlank { n.title })
            } else if (n.id == f.id) {
                v.selected = ""
            } else {
                if (n.id in open && v.selected == n.id) open.removeAll { it == n.id || it.startsWith(n.id + "/") } else open.add(n.id)
                v.selected = n.id
                v.setLayout(KRadial.layout(f, open))
            }
        }
        v.onLong = { nodeMenu(it) }
        v.summarized = summaries.keys.toSet()
        v.setLayout(KRadial.layout(f, open))
        page(t("Карта знаний", "Knowledge map"), v, header = header(tree), scroll = false,
            actions = listOf("⤢" to { v.fit() }, "⋮" to { menu(tree) }))
    }

    /** Long press on a node: focus, summary, open. */
    private fun nodeMenu(n: KNode) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (n.children.isNotEmpty() && n.id != focus) items += t("🔍 Фокус на ветке", "🔍 Focus on the branch") to { setFocus(n.id) }
        if (n.children.isNotEmpty()) items += t("✨ Сводка по ветке", "✨ Branch summary") to { summary(n) }
        if (n.url.isNotBlank()) items += t("▶ Открыть", "▶ Open") to { openTarget(n) }
        if (n.info.isNotBlank()) items += t("📄 Подробнее", "📄 Details") to { showText(n.title.take(60), n.info) }
        if (items.isEmpty()) return
        AlertDialog.Builder(this).setTitle(n.title.take(80)).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /** Map-wide AI: knowledge gaps or a learning path; the answer becomes a branch of the map. */
    private fun mapTask(task: String) {
        if (busy.isNotEmpty()) return toast(t("Подождите, идёт работа", "Please wait, work in progress"))
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        val tree = root ?: return
        val inp = input ?: return
        val gaps = task == KMap.GAPS
        val title = if (gaps) t("❓ Пробелы в знаниях", "❓ Knowledge gaps") else t("🧭 Путь обучения", "🧭 Learning path")
        busy = t("Собираю данные…", "Collecting data…")
        render(keep = true)
        Lumen.async({
            if (gaps) KMap.context(tree, KMapAi.limit(), 3) else KMap.pathData(inp.videos, inp.summaries, KMapAi.limit())
        }, {
            busy = ""
            render(keep = true)
            err(it)
        }) { data ->
            busy = ""
            render(keep = true)
            if (data.isBlank()) return@async toast(t("Пока нет данных", "No data yet"))
            val tokens = KMapAi.estimate(data)
            val cost = Llm.estimateCost(tokens, 1500)
            confirm(title,
                (if (gaps) t("AI найдёт, о чём аудитория спрашивает, а видео не раскрывают. Ответ станет веткой карты.",
                    "The AI finds what the audience asks about but the videos do not cover. The answer becomes a branch of the map.")
                else t("AI выстроит порядок просмотра видео: от основ к продвинутому. Ответ станет веткой карты.",
                    "The AI orders the videos from basics to advanced. The answer becomes a branch of the map.")) + "\n\n" +
                    t("Примерно ~$tokens входных токенов", "About ~$tokens input tokens") +
                    (if (cost > 0) String.format(" · ≈ $%.4f", cost) else ""),
                t("Запустить", "Run")) {
                busy = title + "…"
                render(keep = true)
                Lumen.async({ KMapAi.mapTask(project, task, data, false) }, {
                    busy = ""
                    render(keep = true)
                    err(it)
                }) { r ->
                    busy = ""
                    val id = if (gaps) "map/gaps" else "map/path"
                    open.add(id)
                    load()
                    if ((if (gaps) KMap.parseGaps(r.text) else KMap.parsePath(r.text, inp.videos)).isEmpty()) {
                        showText(title, r.text)
                    } else {
                        toast(t("Готово: ветка «", "Done: branch \"") + title + t("» на карте", "\" is on the map"))
                    }
                }
            }
        }
    }

    private fun drawNode(c: Col, n: KNode, parent: KNode) {
        val level = n.depth - parent.depth - 1
        val leaf = n.children.isEmpty()
        val opened = n.id in open
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = ripple(this@KnowledgeMapActivity, roundBg(if (level == 0) Palette.CARD else 0x00000000, 12f, this@KnowledgeMapActivity), Palette.ON_SURFACE)
            setPadding(dp(8 + 16 * level.coerceAtMost(4)), dp(4), 0, dp(4))
            addView(text(context, if (leaf) "•" else if (opened) "▾" else "▸", 16f, color = Palette.MUTED).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(24), -2))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, n.title, if (level == 0) Type.TITLE_M else 14f, bold = level <= 1).apply {
                    maxLines = if (leaf) 3 else 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (n.sub.isNotBlank()) addView(text(context, n.sub, 12f, color = Palette.MUTED).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                // share of the parent's videos: a thin bar shows which branch weighs more
                if (!leaf && n.count > 0 && parent.count > 0) addView(weightBar(context, n.count, parent.count),
                    LinearLayout.LayoutParams(-1, dp(3)).apply {
                        topMargin = dp(4)
                        marginEnd = dp(8)
                    })
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (n.fresh > 0) addView(text(context, "+" + n.fresh, 12f, bold = true, color = Palette.ON_ACCENT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = roundBg(Palette.OK, 10f, context)
                contentDescription = t("Новых видео: ", "New videos: ") + n.fresh
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            if (!leaf) {
                val has = summaries.containsKey(n.id)
                addView(text(context, if (has) "✨✓" else "✨", 16f, color = if (has) Palette.ACCENT else Palette.ON_SURFACE).apply {
                    gravity = Gravity.CENTER
                    contentDescription = t("AI-сводка: ", "AI summary: ") + n.title
                    background = ripple(context, roundBg(0x00000000, 20f, context), Palette.ON_SURFACE)
                    setOnClickListener { summary(n) }
                }, LinearLayout.LayoutParams(dp(48), dp(44)))
            }
            contentDescription = n.title + (if (n.sub.isNotBlank()) ". " + n.sub else "") +
                if (leaf) "" else ". " + if (opened) t("Свернуть", "Collapse") else t("Развернуть", "Expand")
            setOnClickListener {
                when {
                    !leaf -> {
                        if (opened) open.removeAll { it == n.id || it.startsWith(n.id + "/") } else open.add(n.id)
                        render(keep = true)
                    }
                    else -> showText(n.title.take(60), n.info.ifBlank { n.title })
                }
            }
            setOnLongClickListener {
                nodeMenu(n)
                true
            }
        }
        c.add(row, if (level == 0) 8 else 2)
        if (!opened) return
        summaries[n.id]?.let { summaryCard(c, n, it, level + 1) }
        if (n.info.isNotBlank() && n.depth >= 2 && n.url.isNotBlank() && n.id.substringAfterLast('/').startsWith("v:")) {
            c.add(text(this, n.info.substringAfter('\n').take(240), 13f, color = Palette.MUTED).apply {
                setPadding(dp(32 + 16 * level.coerceAtMost(4)), 0, dp(8), 0)
            }, 0)
        }
        val kids = n.children
        val limit = (Lumen.prefs.getInt("kmap_more_" + n.id.hashCode(), 30)).coerceAtLeast(30)
        kids.take(limit).forEach { drawNode(c, it, parent) }
        if (kids.size > limit) {
            c.add(btn(this, t("Показать ещё (", "Show more (") + (kids.size - limit) + ")", style = BtnStyle.TEXT) {
                Lumen.prefs.edit().putInt("kmap_more_" + n.id.hashCode(), limit + 50).apply()
                render(keep = true)
            }, 0)
        }
    }

    private fun weightBar(ctx: Context, part: Int, whole: Int): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundBg(Palette.CARD, 2f, ctx)
        addView(View(ctx).apply { background = roundBg(Palette.ACCENT, 2f, ctx) },
            LinearLayout.LayoutParams(0, -1, part.coerceIn(1, whole).toFloat()))
        addView(View(ctx), LinearLayout.LayoutParams(0, -1, (whole - part).coerceAtLeast(0).toFloat()))
    }

    private fun summaryCard(c: Col, n: KNode, r: AiResult, level: Int = 0) {
        val card = c.card {
            add(text(ctx, "✨ " + t("Сводка AI", "AI summary") + " · " + Fmt.dateTime(r.at), 13f, bold = true, color = Palette.ACCENT), 0)
            add(text(ctx, r.text.take(320) + if (r.text.length > 320) "…" else "", 13f), 4)
        }
        (card.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(16 * level.coerceAtMost(4))
        card.setOnClickListener { showText(n.title, r.text) }
    }

    private fun searchResults(c: Col, tree: KNode) {
        val q = query.lowercase()
        val found = mutableListOf<KNode>()
        fun walk(n: KNode, d: Int) {
            if (found.size >= 100) return
            if (n.id != tree.id && (n.title.lowercase().contains(q) || n.sub.lowercase().contains(q))) found += n
            if (d < 3) n.children.forEach { walk(it, d + 1) }
        }
        walk(tree, 0)
        c.h2(t("Найдено: ", "Found: ") + found.size)
        if (found.isEmpty()) c.muted(t("Ничего. Поиск идёт по аспектам, группам и видео.", "Nothing. Search covers aspects, groups and videos."))
        found.distinctBy { it.title + it.sub }.forEach { n ->
            val card = c.card {
                add(text(ctx, n.title, 14f, bold = true), 0)
                muted(KMap.path(tree, n.id).drop(1).dropLast(1).joinToString(" › ").ifBlank { n.sub })
            }
            card.setOnClickListener {
                // open every branch on the way and leave search
                var id = n.id
                while (id.contains('/')) {
                    id = id.substringBeforeLast('/')
                    open.add(id)
                }
                open.add(n.id)
                query = ""
                focus = "map"
                render()
            }
        }
    }

    private fun openTarget(n: KNode) {
        if (n.url.isBlank()) return
        if (n.id.substringAfterLast('/').startsWith("c:")) {
            ChannelActivity.open(this, project.id, n.url)
        } else {
            openVideo(n.url)
        }
    }

    private fun summary(n: KNode) {
        if (busy.isNotEmpty()) return toast(t("Подождите, идёт работа", "Please wait, work in progress"))
        val have = summaries[n.id]
        if (have != null) {
            AlertDialog.Builder(this).setTitle(n.title).setItems(arrayOf(t("Открыть сводку", "Open the summary"), t("Обновить сводку", "Refresh the summary"))) { _, i ->
                if (i == 0) showText(n.title, have.text) else runSummary(n, force = true)
            }.show()
        } else {
            runSummary(n, force = false)
        }
    }

    private fun runSummary(n: KNode, force: Boolean) {
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        val tree = root ?: return
        busy = t("Собираю ветку «", "Collecting the branch \"") + n.title.take(40) + t("»…", "\"…")
        render(keep = true)
        Lumen.async({
            val path = KMap.path(tree, n.id).joinToString(" › ")
            path to KMap.context(n, KMapAi.limit())
        }, {
            busy = ""
            render(keep = true)
            err(it)
        }) { (path, data) ->
            busy = ""
            render(keep = true)
            if (data.isBlank()) return@async toast(t("В этой ветке нет данных", "This branch has no data"))
            val tokens = KMapAi.estimate(data)
            val cost = Llm.estimateCost(tokens, 1500)
            confirm(t("AI-сводка: ", "AI summary: ") + n.title.take(50),
                path + "\n\n" + t("Примерно ~$tokens входных токенов", "About ~$tokens input tokens") +
                    (if (cost > 0) String.format(" · ≈ $%.4f", cost) else "") + "\n" +
                    t("Повтор с теми же данными берётся из кэша бесплатно.", "A repeat with the same data comes from the free cache."),
                t("Запустить", "Run")) {
                busy = t("AI-сводка: ", "AI summary: ") + n.title.take(50) + "…"
                render(keep = true)
                Lumen.async({ KMapAi.summarize(project, n.id, path, data, force) }, {
                    busy = ""
                    render(keep = true)
                    err(it)
                }) { r ->
                    busy = ""
                    summaries[n.id] = r
                    open.add(n.id)
                    render(keep = true)
                    showText(n.title, r.text + "\n\n— ${r.model} · in ${r.inTokens} / out ${r.outTokens}")
                }
            }
        }
    }

    private fun menu(tree: KNode) {
        val items = arrayOf(
            t("Раскрыть все аспекты", "Open all aspects"),
            t("❓ Пробелы в знаниях (AI)", "❓ Knowledge gaps (AI)"),
            t("🧭 Путь обучения (AI)", "🧭 Learning path (AI)"),
            t("Поделиться картой (текст)", "Share the map (text)"),
            t("Все сводки карты", "All map summaries"),
            t("Открыть проект", "Open the project"),
            t("Обновить данные", "Reload data")
        )
        AlertDialog.Builder(this).setTitle(t("Карта знаний", "Knowledge map")).setItems(items) { _, i ->
            when (i) {
                0 -> {
                    tree.children.forEach { open.add(it.id) }
                    (KMap.find(tree, focus) ?: tree).children.forEach { open.add(it.id) }
                    render()
                }
                1 -> mapTask(KMap.GAPS)
                2 -> mapTask(KMap.PATH)
                3 -> shareText(t("Карта знаний: ", "Knowledge map: ") + project.name, KMap.context(tree, 200_000, 2))
                4 -> {
                    val list = summaries.values.sortedByDescending { it.at }
                    if (list.isEmpty()) toast(t("Сводок пока нет — нажмите ✨ у любого узла", "No summaries yet — tap ✨ on any node"))
                    else showText(t("Сводки карты", "Map summaries"), list.joinToString("\n\n———\n\n") { r ->
                        "## " + KMap.path(tree, r.target).drop(1).joinToString(" › ").ifBlank { project.name } + "\n\n" + r.text
                    })
                }
                5 -> openProjectScreen(project)
                else -> load()
            }
        }.show()
    }

    companion object {
        fun open(ctx: Context, pid: String) {
            ctx.startActivity(Intent(ctx, KnowledgeMapActivity::class.java).putExtra("id", pid))
        }
    }
}

/** Quick "Knowledge map" pill for project cards and screens. */
fun kmapButton(ctx: Context, pid: String): View =
    btn(ctx, t("🗺 Карта знаний", "🗺 Knowledge map"), style = BtnStyle.TONAL) { KnowledgeMapActivity.open(ctx, pid) }

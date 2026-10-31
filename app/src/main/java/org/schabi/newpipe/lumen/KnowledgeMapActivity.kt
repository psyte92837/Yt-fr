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

/** Knowledge map of a project: an expandable tree of aspects, each with its own AI summary. */
class KnowledgeMapActivity : LumenBase() {
    private lateinit var project: Project
    private var root: KNode? = null
    private val open = LinkedHashSet<String>()
    private var summaries = HashMap<String, AiResult>() // node id → latest summary
    private var busy = ""
    private var query = ""

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
        load()
    }

    override fun onPause() {
        super.onPause()
        if (::project.isInitialized) Lumen.prefs.edit().putString("kmap_open_" + project.id, open.take(200).joinToString("\n")).apply()
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
            val inp = KInput(Store.project(pid) ?: project, Store.videos(pid).values.toList(), sums, { Store.comments(pid, it) }, insights, recs, ai)
            val tree = KMap.build(inp)
            tree.children.size // aspects are cheap: build them off the main thread
            val maps = HashMap<String, AiResult>()
            ai.filter { it.kind == KMap.KIND }.forEach { maps.putIfAbsent(it.target, it) }
            Triple(inp.project, tree, maps)
        }, {
            busy = ""
            render()
            err(it)
        }) { (p, tree, maps) ->
            project = p
            root = tree
            summaries = maps
            busy = ""
            render()
        }
    }

    private fun render(keep: Boolean = false) {
        val c = Col(this)
        val tree = root
        c.card {
            add(text(ctx, project.name.ifBlank { project.phrase }, Type.TITLE_M, bold = true), 0)
            muted("«${project.phrase}»" + if (project.domain.isNotBlank()) " · ${project.domain}" else "")
            if (tree != null) muted(t("Аспектов: ", "Aspects: ") + tree.children.size + " · " + tree.sub)
            muted(t("Нажмите на узел, чтобы раскрыть. ✨ — AI-сводка по ветке. Долгое нажатие — открыть видео или канал.",
                "Tap a node to open it. ✨ makes an AI summary of the branch. Long press opens the video or channel."))
        }
        if (busy.isNotEmpty()) c.card { working(busy) }
        if (tree == null) {
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
            btn(this, t("✨ Сводка по проекту", "✨ Project summary"), style = BtnStyle.TONAL) { summary(tree) },
            btn(this, t("Свернуть", "Collapse"), style = BtnStyle.TEXT) {
                open.clear()
                open.add("map")
                query = ""
                render()
            }
        )
        summaries[tree.id]?.let { summaryCard(c, tree, it) }
        if (query.isNotBlank()) {
            searchResults(c, tree)
        } else {
            tree.children.forEach { drawNode(c, it) }
        }
        page(t("Карта знаний", "Knowledge map"), c.root, actions = listOf("⋮" to { menu(tree) }), keepScroll = keep)
    }

    private fun drawNode(c: Col, n: KNode) {
        val level = n.depth - 1
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
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
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
                openTarget(n)
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
        kids.take(limit).forEach { drawNode(c, it) }
        if (kids.size > limit) {
            c.add(btn(this, t("Показать ещё (", "Show more (") + (kids.size - limit) + ")", style = BtnStyle.TEXT) {
                Lumen.prefs.edit().putInt("kmap_more_" + n.id.hashCode(), limit + 50).apply()
                render(keep = true)
            }, 0)
        }
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
            t("Поделиться картой (текст)", "Share the map (text)"),
            t("Все сводки карты", "All map summaries"),
            t("Открыть проект", "Open the project"),
            t("Обновить данные", "Reload data")
        )
        AlertDialog.Builder(this).setTitle(t("Карта знаний", "Knowledge map")).setItems(items) { _, i ->
            when (i) {
                0 -> {
                    tree.children.forEach { open.add(it.id) }
                    render()
                }
                1 -> shareText(t("Карта знаний: ", "Knowledge map: ") + project.name, KMap.context(tree, 200_000, 2))
                2 -> {
                    val list = summaries.values.sortedByDescending { it.at }
                    if (list.isEmpty()) toast(t("Сводок пока нет — нажмите ✨ у любого узла", "No summaries yet — tap ✨ on any node"))
                    else showText(t("Сводки карты", "Map summaries"), list.joinToString("\n\n———\n\n") { r ->
                        "## " + KMap.path(tree, r.target).drop(1).joinToString(" › ").ifBlank { project.name } + "\n\n" + r.text
                    })
                }
                3 -> openProjectScreen(project)
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

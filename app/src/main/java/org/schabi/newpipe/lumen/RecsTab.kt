/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout

/** "Recommendations" tab: what YouTube recommends next to the project's videos. */
fun ProjectActivity.startRecs() {
    if (CollectState.running) return toast(t("Уже идёт другая задача", "Another task is running"))
    val cfg = project.recs
    val seeds = RecEngine.seeds(project, cfg)
    if (seeds.isEmpty()) return toast(t("Нет стартовых видео: найдите или отметьте видео.", "No seed videos: search or mark videos first."))
    val req = RecEngine.estimateRequests(cfg, seeds.size)
    val minutes = maxOf(1, req * (Settings.requestDelayMs + 1500) / 60000)
    val ai = if (cfg.relevance == "ai") t("\nAI-фильтр: ~${maxOf(1, cfg.maxNodes / 30)} коротких запросов к модели.", "\nAI filter: ~${maxOf(1, cfg.maxNodes / 30)} short model calls.") else ""
    confirm(
        t("Запустить обход?", "Start exploring?"),
        t("Стартовых видео: ${seeds.size}\nДо $req запросов к YouTube, ≈ $minutes мин.\nРаботает в фоне, можно свернуть приложение.",
            "Seed videos: ${seeds.size}\nUp to $req YouTube requests, ≈ $minutes min.\nRuns in the background.") + ai,
        t("Запустить", "Start")
    ) {
        Store.save(project)
        CollectService.startRecs(this, project.id)
        toast(t("Обход рекомендаций запущен", "Recommendation exploring started"))
    }
}

private fun ProjectActivity.state(key: String, def: Any): Any = ui.getOrPut(key) { def }

fun ProjectActivity.recsTab(c: Col) {
    val cfg = project.recs
    val save = { Store.save(project) }
    val g = RecGraph.load(project.id)

    c.card {
        if (g.nodes.isEmpty()) {
            p(t("Граф пока пуст. Настройте обход ниже и нажмите «Запустить».", "The graph is empty. Configure below and tap Run."))
            muted(t("Lumen откроет каждое стартовое видео, возьмёт колонку «Следующее» YouTube и пойдёт дальше по похожим — без аккаунта и ключей.",
                "Lumen opens each seed video, takes YouTube's “Up next” column and follows similar videos — no account or keys."))
        } else {
            add(text(ctx, t("Узлов ${g.nodes.size} · связей ${g.edges.size}", "Nodes ${g.nodes.size} · edges ${g.edges.size}"), Type.TITLE_M, bold = true), 0)
            muted(t("Последний обход: ", "Last run: ") + Fmt.dateTime(g.updatedAt) + " · " + g.mode + " · " + t("запросов всего: ", "total requests: ") + g.requests)
            add(rowOf(ctx,
                btn(ctx, t("Открыть граф", "Open graph"), primary = true) {
                    startActivity(Intent(ctx, GraphActivity::class.java).putExtra("pid", project.id))
                },
                btn(ctx, t("Сбросить", "Reset"), style = BtnStyle.OUTLINED) {
                    confirm(t("Удалить граф?", "Delete the graph?"), t("Видео проекта не затрагиваются.", "Project videos are not affected.")) {
                        RecGraph.clear(project.id)
                        render()
                    }
                }
            ), 8)
        }
    }

    c.card {
        add(text(ctx, t("🧪 Аудит алгоритма", "🧪 Algorithm audit"), Type.TITLE_M, bold = true), 0)
        muted(t("Виртуальный зритель много раз идёт по «Следующему»: как быстро уходит тема, к каким каналам и видео сходится алгоритм.",
            "A virtual viewer follows “Up next” many times: how fast the topic fades, which channels and videos the algorithm converges to."))
        button(t("Открыть аудит", "Open audit"), style = BtnStyle.TONAL) { AuditActivity.open(ctx, project.id) }
    }

    // ---------------- settings: open for a new graph, folded once there are results to read
    val paramsOpen = state("recParams", g.nodes.isEmpty()) as Boolean
    c.section(t("Параметры обхода", "Walk settings"), t("режим, фильтр по теме, ограничения", "mode, topic filter, limits"), paramsOpen, {
        ui["recParams"] = it
        render()
    }) {
        c.h2(t("Режим", "Mode"))
        c.spinner(
            t("Как обходить", "How to explore"),
            listOf(
                "snowball" to t("Снежный ком: похожие видео по уровням", "Snowball: similar videos level by level"),
                "chain" to t("Цепочка автовоспроизведения (по одному)", "Autoplay chain (one by one)"),
                "mix" to t("YouTube Mix вокруг видео", "YouTube Mix around a video")
            ),
            cfg.mode
        ) {
            if (it != cfg.mode) {
                cfg.mode = it
                save()
                render()
            }
        }
        c.muted(
            when (cfg.mode) {
                "chain" -> t("Как зритель, который не трогает телефон: всегда берётся первое подходящее «Следующее». Видно, куда алгоритм уводит от темы.",
                    "Like a viewer who never touches the phone: always the first suitable “Up next”. Shows where the algorithm drifts.")
                "mix" -> t("Плейлист-радио, который YouTube строит вокруг видео (list=RD…). Один запрос на стартовое видео.",
                    "The radio playlist YouTube builds around a video (list=RD…). One request per seed.")
                else -> t("Бюджет запросов делится по уровням, и на каждом раскрываются видео, которые рекомендуют чаще всего. Поэтому большая глубина реально достигается.",
                    "The request budget is split across levels; each level expands the most recommended videos, so deep levels are reached.")
            }
        )
        c.spinner(
            t("Стартовые видео", "Seed videos"),
            listOf(
                "marked" to t("Отмеченные (иначе топ по просмотрам)", "Marked (else top by views)"),
                "top" to t("Топ проекта по просмотрам", "Project top by views"),
                "all" to t("Последние добавленные", "Most recently added"),
                "custom" to t("Свои ссылки", "Custom links")
            ),
            cfg.seeds
        ) {
            if (it != cfg.seeds) {
                cfg.seeds = it
                save()
                render()
            }
        }
        if (cfg.seeds == "custom") {
            val e = c.edit(t("Ссылки на видео, по одной в строке", "Video links, one per line"), cfg.customSeeds, multiline = true)
            e.addTextChangedListener(SimpleWatcher {
                cfg.customSeeds = it
                save()
            })
        } else {
            c.spinner(t("Сколько стартовых видео", "How many seeds"), listOf(1, 3, 5, 10, 20, 50).map { it to it.toString() }, cfg.seedCount) {
                cfg.seedCount = it
                save()
            }
        }
        if (cfg.mode != "mix") {
            c.spinner(t("Глубина (шагов)", "Depth (steps)"), (1..20).map { it to it.toString() } + listOf(30 to "30", 50 to "50"), cfg.depth) {
                cfg.depth = it
                save()
            }
        }
        c.spinner(
            if (cfg.mode == "mix") t("Видео из каждого Mix (минимум)", "Videos from each Mix (min)") else t("Похожих с каждого видео", "Similar per video"),
            listOf(1, 2, 3, 5, 8, 10, 15, 20).map { it to it.toString() }, cfg.fanout
        ) {
            cfg.fanout = it
            save()
        }
        c.spinner(t("Бюджет запросов к YouTube", "YouTube request budget"), listOf(20, 50, 100, 150, 200, 300, 500, 1000).map { it to it.toString() }, cfg.budget) {
            cfg.budget = it
            save()
        }
        c.spinner(t("Максимум узлов графа", "Max graph nodes"), listOf(100, 200, 300, 500, 1000, 2000).map { it to it.toString() }, cfg.maxNodes) {
            cfg.maxNodes = it
            save()
        }
        c.check(t("Дополнять существующий граф (иначе начать заново)", "Extend the existing graph (else start over)"), cfg.keepGraph) {
            cfg.keepGraph = it
            save()
        }

        c.h2(t("Фильтр по теме", "Topic filter"))
        c.spinner(
            t("Релевантность", "Relevance"),
            listOf(
                "keywords" to t("По ключевым словам (бесплатно)", "By keywords (free)"),
                "ai" to t("Через AI (быстрая модель)", "With AI (fast model)"),
                "off" to t("Выключен", "Off")
            ),
            cfg.relevance
        ) {
            if (it != cfg.relevance) {
                cfg.relevance = it
                save()
                render()
            }
        }
        if (cfg.relevance != "off") {
            c.muted(t("Ключевые слова берутся из фразы, запросов и домена проекта. Можно добавить свои:", "Keywords come from the project phrase, queries and domain. Add your own:"))
            val kw = c.edit(t("Доп. ключевые слова через пробел", "Extra keywords, space separated"), cfg.keywords)
            kw.addTextChangedListener(SimpleWatcher {
                cfg.keywords = it
                save()
            })
            c.check(t("Раскрывать и видео не по теме (видеть дрейф дальше)", "Expand off-topic videos too (follow the drift)"), cfg.expandOffTopic) {
                cfg.expandOffTopic = it
                save()
            }
        }

        c.h2(t("Фильтры", "Filters"))
        c.check(t("Исключить Shorts", "Exclude Shorts"), cfg.excludeShorts) {
            cfg.excludeShorts = it
            save()
        }
        c.spinner(t("Минимум просмотров", "Minimum views"), listOf(0L to "0", 1000L to "1K", 10_000L to "10K", 100_000L to "100K", 1_000_000L to "1M"), cfg.minViews) {
            cfg.minViews = it
            save()
        }
        c.spinner(t("Не больше видео с одного канала", "Max videos per channel"), listOf(0 to t("без ограничения", "unlimited"), 1 to "1", 2 to "2", 3 to "3", 5 to "5", 10 to "10"), cfg.maxPerChannel) {
            cfg.maxPerChannel = it
            save()
        }
        c.check(t("Применять период и длительность из фильтров проекта", "Apply period and duration from project filters"), cfg.useProjectFilters) {
            cfg.useProjectFilters = it
            save()
        }
        val bl = c.edit(t("Исключить каналы (через запятую)", "Exclude channels (comma separated)"), cfg.blocked)
        bl.addTextChangedListener(SimpleWatcher {
            cfg.blocked = it
            save()
        })
    }

    if (g.nodes.isEmpty()) return

    // ---------------- results

    c.h2(t("Что рекомендует YouTube", "What YouTube recommends"))
    val sort = state("recSort", "hits") as String
    val onlyTopic = state("recOnlyTopic", false) as Boolean
    val notInProject = state("recNotIn", true) as Boolean
    c.chips(listOf(
        "hits" to t("чаще рекомендуют", "most recommended"), "pr" to "PageRank",
        "views" to t("просмотры", "views"), "depth" to t("глубже всего", "deepest")
    ).map { (k, l) ->
        chip(this, l, sort == k) {
            ui["recSort"] = k
            render()
        }
    } + listOf(
        chip(this, t("по теме", "on topic"), onlyTopic) {
            ui["recOnlyTopic"] = !onlyTopic
            render()
        },
        chip(this, t("нет в проекте", "not in project"), notInProject) {
            ui["recNotIn"] = !notInProject
            render()
        }
    ))
    var list = RecEngine.ranked(g, sort)
    if (onlyTopic) list = list.filter { it.relevant == 1 }
    if (notInProject) list = list.filter { !videos.containsKey(it.id) }
    @Suppress("UNCHECKED_CAST")
    val picked = state("recPicked", HashSet<String>()) as HashSet<String>
    c.row(
        btn(this, t("В проект: отмеченные (${picked.size})", "Add marked (${picked.size})"), style = BtnStyle.TONAL) {
            val add = picked.mapNotNull { g.nodes[it]?.toVideo("rec") }
            val n = Store.mergeVideos(project.id, add)
            picked.clear()
            reload()
            render()
            toast(t("Добавлено: $n", "Added: $n"))
        },
        btn(this, t("В проект: топ-10", "Add top 10"), style = BtnStyle.OUTLINED) {
            val n = Store.mergeVideos(project.id, list.take(10).map { it.toVideo("rec") })
            reload()
            render()
            toast(t("Добавлено: $n", "Added: $n"))
        }
    )
    if (list.isEmpty()) c.muted("—")
    for (n in list.take(60)) c.add(recRow(n, picked), 8)

    // ---------------- drift and channels
    c.h2(t("Дрейф: доля видео по теме на каждом шаге", "Drift: on-topic share by step"))
    val drift = RecEngine.drift(g)
    c.add(BarChartView(this).apply {
        values = drift.map { if (it.nodes == 0) 0.0 else it.onTopic * 100.0 / it.nodes }
        labels = drift.map { if (it.depth == 0) t("старт", "seed") else it.depth.toString() }
        contentDescription = t("График дрейфа", "Drift chart")
    }, 8)
    c.muted(drift.joinToString(" · ") { "${it.depth}: ${it.onTopic}/${it.nodes}" })
    c.h2(t("Каналы в рекомендациях", "Channels in recommendations"))
    val chans = g.nodes.values.filter { !it.seed }.groupBy { it.channel.ifBlank { "?" } }
        .map { (ch, l) -> ch to l.sumOf { it.hits }.toDouble() }.sortedByDescending { it.second }.take(10)
    c.add(BarChartView(this).apply { horizontal = chans }, 8)
    c.muted(t("Длина полосы — сколько раз видео канала встречались в рекомендациях.", "Bar length = how many times the channel's videos were recommended."))
}

private fun ProjectActivity.recRow(n: RecNode, picked: HashSet<String>): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(this@recRow, roundBg(Palette.CARD, 12f, this@recRow), Palette.ON_SURFACE)
        setPadding(dp(4), dp(8), dp(12), dp(8))
    }
    val cb = CheckBox(this).apply {
        isChecked = n.id in picked || videos.containsKey(n.id)
        isEnabled = !videos.containsKey(n.id)
        buttonTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
        setOnCheckedChangeListener { _, on -> if (on) picked.add(n.id) else picked.remove(n.id) }
    }
    row.addView(cb, LinearLayout.LayoutParams(dp(48), dp(48)))
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundBg(Palette.ACCENT_SOFT, 8f, this@recRow)
        clipToOutline = true
    }
    row.addView(img, LinearLayout.LayoutParams(dp(96), dp(54)).apply { marginEnd = dp(12) })
    Thumbs.load(img, ThumbUrl.mini(n.id, n.thumb))
    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    info.addView(text(this, n.title.ifBlank { n.id }, 14f, bold = true).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    val topic = when (n.relevant) {
        1 -> "✓"
        0 -> "✗"
        else -> "?"
    }
    info.addView(text(this, n.channel, 12f, color = Palette.MUTED).apply { isSingleLine = true })
    info.addView(text(this, "×${n.hits} · PR ${String.format("%.1f", n.pr * 1000)} · ${t("шаг", "step")} ${n.depth} · $topic · ${Fmt.num(n.views)}",
        12f, color = if (n.relevant == 0) Palette.OUTLINE else Palette.MUTED))
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    if (n.url.isNotBlank()) {
        row.setPadding(dp(4), dp(8), 0, dp(8))
        row.addView(mediaBtn(this, n.url, n.title), LinearLayout.LayoutParams(dp(44), dp(48)))
    }
    row.setOnClickListener { openVideo(n.url) }
    return row
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent

/** "Overview" tab: result of the instant analysis of a shared link. */
fun ProjectActivity.overviewTab(c: Col) {
    val steps = QuickState.load(project.id)
    if (project.source.isNotBlank()) {
        c.card {
            add(text(ctx, when (project.sourceType) {
                "video" -> "▶ " + t("Видео", "Video")
                "channel" -> "👤 " + t("Канал", "Channel")
                else -> "▤ " + t("Плейлист", "Playlist")
            }, 13f, bold = true, color = Palette.MUTED), 0)
            add(text(ctx, project.source, 13f, color = Palette.ACCENT).apply { setOnClickListener { openVideo(project.source) } }, 4)
        }
    }
    if (steps.isNotEmpty()) {
        c.h2(t("Шаги анализа", "Analysis steps"))
        steps.forEach { s ->
            val mark = when (s.status) { "done" -> "✅"; "warn" -> "⚠️"; "running" -> "⏳"; "error" -> "❌"; "skipped" -> "➖"; else -> "⬜" }
            c.add(text(this, "$mark  ${QuickState.label(s.key)}" + if (s.note.isNotBlank()) " — ${s.note.take(240)}" else "", 14f,
                color = if (s.status == "error") Palette.BAD else Palette.ON_SURFACE), 4)
        }
    }
    c.row(
        btn(this, t("Перезапустить анализ", "Restart analysis"), style = BtnStyle.TONAL) {
            if (CollectState.running) return@btn toast(t("Уже идёт задача", "A task is running"))
            if (project.source.isBlank()) return@btn toast(t("У проекта нет исходной ссылки", "The project has no source link"))
            CollectService.startKind(this, project.id, "quick")
            toast(t("Анализ запущен", "Analysis started"))
        },
        btn(this, t("Настройки анализа", "Analysis settings"), style = BtnStyle.TEXT) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("quick", true))
        }
    )

    val vids = videos.values.toList()
    // ---- video style (only when requested: Share/paste → "Video style", or on the video screen)
    if (project.quickMode == "style" || (vids.size == 1 && StyleMetrics.load(project.id, vids.first().id) != null)) {
        vids.firstOrNull()?.let { v ->
            styleCard(c, project.id, v, full = false) {
                if (!isFinishing) {
                    reload()
                    render()
                }
            }
        }
    }

    // ---- pains and cases of people (Share → "Pains and cases")
    if (project.quickMode == "pains" || Pains.latest(project.id) != null) painsCard(c)

    // ---- AI summaries
    val summary = Store.latestAi(project.id, "project_summary", "project")
        ?: vids.firstNotNullOfOrNull { Store.latestAi(project.id, "video_summary", it.id) }
    if (summary != null) {
        c.h2(t("AI-сводка", "AI summary"))
        c.card {
            add(text(ctx, summary.text.take(1500) + if (summary.text.length > 1500) "…" else "", 14f).apply { setTextIsSelectable(true) }, 0)
            muted(summary.model + " · " + Fmt.dateTime(summary.at))
        }.setOnClickListener { showText(t("AI-сводка", "AI summary"), summary.text) }
    }

    // ---- statistics
    if (vids.isNotEmpty()) {
        val st = Analytics.stats(vids)
        c.h2(t("Статистика", "Statistics"))
        c.chips(listOfNotNull(
            chip(this, t("видео ", "videos ") + st.total),
            chip(this, "👁 " + Fmt.num(st.totalViews)),
            chip(this, "⌀ " + Fmt.num(st.avgViews)),
            if (st.comments > 0) chip(this, "💬 " + st.comments) else null,
            if (st.withTranscript > 0) chip(this, "📝 " + st.withTranscript) else null
        ))
        if (vids.size == 1) {
            val v = vids.first()
            c.muted(listOfNotNull(
                if (v.likes >= 0) "👍 " + Fmt.num(v.likes) else null,
                "⏱ " + Fmt.duration(v.duration),
                if (v.uploadMs > 0) "📅 " + Fmt.date(v.uploadMs) else null,
                Analytics.velocity(v).takeIf { it > 0 }?.let { "📈 " + Fmt.num(it) + t("/день", "/day") },
                t("вовлечённость ", "engagement ") + Analytics.engagementPct(v)
            ).joinToString(" · "))
            c.button(t("Открыть видео: расшифровка, комментарии", "Open video: transcript, comments"), style = BtnStyle.OUTLINED) {
                startActivity(Intent(this, VideoActivity::class.java).putExtra("pid", project.id).putExtra("vid", v.id))
            }
        }
    }

    // ---- channel card(s)
    val chans = ChannelStore.all(project.id).values.filter { it.error.isEmpty() }
    val main = vids.groupingBy { ChannelStore.key(it.channelUrl) }.eachCount().maxByOrNull { it.value }?.key
    val ordered = chans.sortedByDescending { if (ChannelStore.key(it.url) == main) Long.MAX_VALUE else it.subscribers }
    if (ordered.isNotEmpty()) {
        c.h2(t("Канал", "Channel"))
        val ch = ordered.first()
        c.card {
            add(text(ctx, ch.name + if (ch.verified) " ✓" else "", Type.TITLE_M, bold = true), 0)
            muted(ChannelStats.line(ch))
            muted(t("ссылок: ", "links: ") + ch.links.size + t(" · плейлистов: ", " · playlists: ") + ch.playlists.size +
                t(" · рекомендуемых каналов: ", " · featured: ") + ch.featured.size)
            ChannelAi.latest(project.id, ch.url)?.let { add(text(ctx, it.text.take(400) + "…", 13f), 6) }
        }.setOnClickListener { ChannelActivity.open(this, project.id, ch.url) }
    }

    // ---- recommendations & channel graph
    val g = RecGraph.load(project.id)
    if (g.nodes.size > 1) {
        c.h2(t("Похожие и рекомендации", "Similar & recommended") + " (${g.nodes.size - 1})")
        RecEngine.ranked(g, "hits").take(8).forEach { n ->
            c.add(videoListRow(this, ThumbUrl.mini(n.id, n.thumb), n.title.ifBlank { n.id }, n.channel, "×${n.hits} · ${Fmt.num(n.views)}", url = n.url) { openVideo(n.url) }, 6)
        }
        c.row(
            btn(this, t("Граф видео", "Video graph"), style = BtnStyle.TONAL) {
                startActivity(Intent(this, GraphActivity::class.java).putExtra("pid", project.id))
            },
            btn(this, t("Все рекомендации", "All recommendations"), style = BtnStyle.TEXT) {
                tab = "recs"
                render()
            }
        )
    }
    val cg = RecGraph.load(project.id, RecGraph.CHANNELS_FILE)
    if (cg.nodes.size > 1) {
        c.h2(t("Связанные каналы", "Related channels") + " (${cg.nodes.size - 1})")
        channelRows(c, cg, 8)
        c.row(
            btn(this, t("Граф каналов", "Channel graph"), style = BtnStyle.TONAL) {
                startActivity(Intent(this, GraphActivity::class.java).putExtra("pid", project.id).putExtra("file", RecGraph.CHANNELS_FILE))
            },
            btn(this, t("Все каналы", "All channels"), style = BtnStyle.TEXT) {
                tab = "channels"
                render()
            }
        )
    }
}

/** Rows of a channel graph with inline stats; tap = channel screen. */
fun ProjectActivity.channelRows(c: Col, g: RecGraph, limit: Int, sort: String = "hits") {
    val list = g.nodes.values.filter { !it.seed }.let { l ->
        when (sort) {
            "subs" -> l.sortedByDescending { it.views }
            "pr" -> l.sortedByDescending { it.pr }
            "depth" -> l.sortedWith(compareBy<RecNode> { it.depth }.thenByDescending { it.hits })
            else -> l.sortedWith(compareByDescending<RecNode> { it.weight }.thenByDescending { it.hits })
        }
    }
    list.take(limit).forEach { n ->
        val card = c.card {
            add(text(ctx, n.title.ifBlank { n.url.substringAfterLast('/') }, 14f, bold = true), 0)
            muted(n.uploadText.ifBlank { if (n.views >= 0) Fmt.num(n.views) + t(" подп.", " subs") else t("статистика не загружена", "no stats yet") })
            muted(t("связей ", "links ") + n.weight.toInt() + " · " + t("шаг ", "step ") + n.depth + " · PR " + String.format("%.1f", n.pr * 1000))
        }
        card.setOnClickListener { ChannelActivity.open(this, project.id, n.url) }
    }
}

/** "Channels" tab: graph search of channels by depth with stats in the list. */
fun ProjectActivity.channelsTab(c: Col) {
    val q = QuickConfig.load()
    c.muted(t("Поиск каналов по графу: Lumen берёт видео стартовых каналов, смотрит, чьи видео YouTube рекомендует рядом, и рекомендуемые каналы автора — и так на несколько уровней. Для каждого канала считается статистика.",
        "Channel graph search: Lumen takes the seed channels' videos, sees whose videos YouTube recommends next to them and the author's featured channels — level by level, with stats for every channel."))
    c.spinner(t("Глубина", "Depth"), (1..6).map { it to it.toString() }, q.graphDepth) {
        q.graphDepth = it
        QuickConfig.save(q)
    }
    c.spinner(t("Максимум каналов", "Max channels"), listOf(20, 50, 100, 200, 300, 500).map { it to it.toString() }, q.graphChannels) {
        q.graphChannels = it
        QuickConfig.save(q)
    }
    val projectChannels = videos.values.filter { it.channelUrl.isNotBlank() }.groupingBy { it.channelUrl }.eachCount().entries.sortedByDescending { it.value }
    val seedMode = ui.getOrPut("chSeed") { "top" } as String
    c.spinner(t("Стартовые каналы", "Seed channels"),
        listOf("top" to t("Самые частые в проекте (3)", "Most frequent in project (3)"), "marked" to t("Каналы отмеченных видео", "Channels of marked videos"),
            "all" to t("Все каналы проекта (до 20)", "All project channels (up to 20)")), seedMode) { ui["chSeed"] = it }
    c.row(
        btn(this, t("Найти каналы", "Find channels"), primary = true) {
            if (CollectState.running) return@btn toast(t("Уже идёт задача", "A task is running"))
            val seeds = when (ui["chSeed"]) {
                "marked" -> videos.values.filter { it.marked }.map { it.channelUrl }.filter { it.isNotBlank() }.distinct()
                "all" -> projectChannels.take(20).map { it.key }
                else -> projectChannels.take(3).map { it.key }
            }
            if (seeds.isEmpty()) return@btn toast(t("Нет каналов: сначала найдите видео", "No channels: search videos first"))
            Store.writeFile(project.id, "chgraph_seeds.txt", seeds.joinToString("\n"))
            CollectService.startKind(this, project.id, "chgraph")
            toast(t("Поиск каналов запущен", "Channel search started"))
        },
        btn(this, t("Статистика каналов проекта", "Stats of project channels"), style = BtnStyle.OUTLINED) {
            cancel.set(false)
            busy = t("Статистика каналов…", "Channel stats…")
            render()
            Lumen.async({ ChannelFetch.forVideos(project.id, videos.values, cancel, 50) { m -> Lumen.ui { progressView?.text = m } } }, {
                busy = ""
                render()
                err(it)
            }) { n ->
                busy = ""
                render()
                toast(t("Каналов: $n", "Channels: $n"))
            }
        }
    )

    val g = RecGraph.load(project.id, RecGraph.CHANNELS_FILE)
    if (g.nodes.size > 1) {
        c.h2(t("Найденные каналы", "Found channels") + " (${g.nodes.size - 1})")
        c.muted(t("Последний поиск: ", "Last search: ") + Fmt.dateTime(g.updatedAt))
        val sort = ui.getOrPut("chSort") { "hits" } as String
        c.chips(listOf("hits" to t("больше связей", "most links"), "subs" to t("подписчики", "subscribers"), "pr" to "PageRank", "depth" to t("по шагам", "by step")).map { (k, l) ->
            chip(this, l, sort == k) {
                ui["chSort"] = k
                render()
            }
        })
        c.row(
            btn(this, t("Граф каналов", "Channel graph"), style = BtnStyle.TONAL) {
                startActivity(Intent(this, GraphActivity::class.java).putExtra("pid", project.id).putExtra("file", RecGraph.CHANNELS_FILE))
            },
            btn(this, t("Сбросить", "Reset"), style = BtnStyle.TEXT) {
                confirm(t("Удалить граф каналов?", "Delete channel graph?"), "") {
                    RecGraph.clear(project.id, RecGraph.CHANNELS_FILE)
                    render()
                }
            }
        )
        channelRows(c, g, 100, sort)
    }

    val known = ChannelStore.all(project.id).values.filter { it.error.isEmpty() }.sortedByDescending { it.subscribers }
    if (known.isNotEmpty()) {
        c.h2(t("Каналы видео проекта", "Channels of project videos") + " (${known.size})")
        known.take(50).forEach { ch ->
            val card = c.card {
                add(text(ctx, ch.name + if (ch.verified) " ✓" else "", 14f, bold = true), 0)
                muted(ChannelStats.line(ch))
                val inProject = videos.values.count { ChannelStore.key(it.channelUrl) == ChannelStore.key(ch.url) }
                muted(t("видео в проекте: ", "videos in project: ") + inProject + t(" · ссылок: ", " · links: ") + ch.links.size + t(" · плейлистов: ", " · playlists: ") + ch.playlists.size)
            }
            card.setOnClickListener { ChannelActivity.open(this, project.id, ch.url) }
        }
    }
}

/** Report "Pains and cases of people": AI part with checked quotes, or keyword signals without AI. */
fun ProjectActivity.painsCard(c: Col) {
    c.h2("😣 " + t("Боли и кейсы людей", "Pains and cases of people"))
    val msgs = Pains.projectMessages(project.id)
    if (msgs.isEmpty()) {
        c.muted(if (CollectState.running && CollectState.projectId == project.id) t("Собираю комментарии…", "Collecting comments…")
        else t("Комментариев пока нет: дождитесь шага сбора или перезапустите анализ.", "No comments yet: wait for the collection step or restart."))
        return
    }
    val loc = Pains.local(msgs)
    val ai = Pains.latest(project.id)
    val r = ai?.let { Pains.parse(it.text, msgs) }
    c.chips(listOf(Voc.PAIN, Pains.CASE, Voc.QUESTION, Voc.OBJECTION, Voc.REQUEST).map { k ->
        chip(this, Pains.categoryLabel(k).lowercase() + " " + (loc.counts[k] ?: 0))
    })
    c.muted(t("комментариев: ", "comments: ") + loc.total + if (ai != null) " · " + ai.model + " · " + Fmt.dateTime(ai.at) else "")
    if (r != null) {
        if (r.summary.isNotBlank()) c.card { add(text(ctx, r.summary, 14f).apply { setTextIsSelectable(true) }, 0) }
        r.pains.take(6).forEachIndexed { i, p ->
            c.card {
                add(text(ctx, "${i + 1}. " + p.title, 15f, bold = true), 0)
                muted(listOfNotNull(
                    if (p.ids.isNotEmpty()) t("упоминаний ", "mentions ") + p.ids.size else null,
                    if (p.severity > 0) t("острота ", "severity ") + "●".repeat(p.severity) else null
                ).joinToString(" · "))
                if (p.detail.isNotBlank()) add(text(ctx, p.detail, 13f), 4)
                p.quotes.firstOrNull()?.let { add(text(ctx, "«" + it.text.take(240) + "»", 13f, color = Palette.MUTED), 4) }
            }
        }
        if (r.cases.isNotEmpty()) {
            c.add(text(this, t("Кейсы", "Cases"), 15f, bold = true), 12)
            r.cases.take(4).forEach { k ->
                c.card {
                    if (k.who.isNotBlank()) add(text(ctx, "👤 " + k.who, 14f, bold = true), 0)
                    add(text(ctx, k.situation, 13f), 2)
                    if (k.outcome.isNotBlank()) muted(t("Итог: ", "Outcome: ") + k.outcome)
                    if (k.need.isNotBlank()) muted(t("Нужно: ", "Needs: ") + k.need)
                }
            }
        }
    } else {
        if (ai != null) c.muted(t("AI-ответ не удалось разобрать — показаны сигналы по ключевым словам. Нажмите «Обновить через AI».",
            "Could not read the AI reply — keyword signals shown. Tap “Refresh with AI”."))
        listOf(Voc.PAIN, Voc.QUESTION, Voc.OBJECTION).forEach { k ->
            val items = loc.top[k].orEmpty().take(3)
            if (items.isNotEmpty()) c.card {
                add(text(ctx, Pains.categoryLabel(k), 14f, bold = true), 0)
                items.forEach { add(text(ctx, "• " + it.text.take(200) + "  ♥" + it.likes, 13f), 4) }
            }
        }
        if (loc.cases.isNotEmpty()) c.card {
            add(text(ctx, Pains.categoryLabel(Pains.CASE), 14f, bold = true), 0)
            loc.cases.take(3).forEach { add(text(ctx, "• " + it.text.take(260), 13f), 4) }
        }
    }
    c.button(t("Весь отчёт", "Full report"), style = BtnStyle.TONAL) { showText(t("Боли и кейсы людей", "Pains and cases of people"), Pains.text(project.id)) }
    c.row(
        btn(this, t("PDF ↗", "PDF ↗"), style = BtnStyle.TONAL) { sharePains(true) },
        btn(this, t("Картинка ↗", "Image ↗"), style = BtnStyle.TONAL) { sharePains(false) },
        btn(this, t("Текст ↗", "Text ↗"), style = BtnStyle.TEXT) { shareText(project.name, Pains.text(project.id)) }
    )
    if (Provider.active() != null && !CollectState.running) {
        c.button(if (ai == null) t("Разобрать через AI", "Analyse with AI") else t("Обновить через AI", "Refresh with AI"), style = BtnStyle.OUTLINED) {
            toast(t("AI ищет боли и кейсы…", "AI looks for pains and cases…"))
            Lumen.async({ Pains.ai(project, force = true) }, { err(it) }) {
                if (!isFinishing) {
                    reload()
                    render()
                }
            }
        }
    }
}

private fun ProjectActivity.sharePains(pdf: Boolean) {
    val name = ReportKit.safeName(t("боли_", "pains_") + project.name) + if (pdf) ".pdf" else ".png"
    Lumen.async({
        val f = ReportKit.shareFile(this, name)
        f.outputStream().use { Pains.write(project.id, pdf, it) }
        f
    }, { err(it) }) { f -> ReportKit.share(this, f, pdf, project.name) }
}

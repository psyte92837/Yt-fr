/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/*
 * "🧭 Algorithm" tab (key "recs"): sections on top of the recommendation graph — niches, channel flows,
 * the author's channel, opportunities, dynamics, audit, forecast and radar. See ALGO_PLAN.md.
 */

private fun pct(d: Double) = if (d.isNaN()) "—" else "${Math.round(d * 100)}%"

private fun f1(d: Double) = if (d.isNaN()) "—" else String.format(java.util.Locale.US, "%.1f", d)

private fun ProjectActivity.sec(c: Col, key: String, title: String, summary: String, defOpen: Boolean = false, build: Col.() -> Unit) {
    val open = (ui["algo_sec_$key"] as? Boolean) ?: defOpen
    c.section(title, summary, open, {
        ui["algo_sec_$key"] = it
        render()
    }, build)
}

private fun ProjectActivity.copy(s: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lumen", s))
    toast(t("Скопировано", "Copied"))
}

/** True when an AI provider is set; otherwise offers to connect one. */
private fun ProjectActivity.needAi(): Boolean {
    if (Provider.active() != null || Relay.mode != "off") return true
    confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
    }
    return false
}

/** Runs an AI call of the tab in the background and shows Markdown with [id] links turned into names. */
private fun ProjectActivity.aiMarkdown(title: String, work: () -> Pair<String, Map<String, String>>) {
    if (!needAi()) return
    toast(t("$title: ИИ думает…", "$title: AI is thinking…"))
    bgTask(title, work, { err(it) }) { (md, names) ->
        val text = AlgoParse.cite(md, names)
        AlertDialogs.markdown(this, title, text) { copy(text) }
    }
}

/** Plain dialog with a copy button (Markdown is shown as text). */
private object AlertDialogs {
    fun markdown(a: ProjectActivity, title: String, body: String, onCopy: () -> Unit) {
        android.app.AlertDialog.Builder(a).setTitle(title).setMessage(body)
            .setPositiveButton("OK", null)
            .setNeutralButton(t("Копировать", "Copy")) { _, _ -> onCopy() }
            .setNegativeButton(t("Поделиться", "Share")) { _, _ -> a.shareText(title, body) }
            .show()
    }
}

/** Analysis of the graph, computed in the background on first use; null while computing. */
fun ProjectActivity.algoResult(g: RecGraph): AlgoResult? {
    AlgoAnalysis.peek(project.id, g)?.let { return it }
    if (ui["algoBusy"] != true && ui["algoFailed"] != g.updatedAt) {
        ui["algoBusy"] = true
        Lumen.async({ AlgoAnalysis.get(project.id) }, {
            ui["algoBusy"] = false
            ui["algoFailed"] = g.updatedAt
            L.w("algo", "analysis: ${it.message}")
        }) {
            ui["algoBusy"] = false
            render()
        }
    }
    return null
}

fun ProjectActivity.algoHeader(c: Col, g: RecGraph) {
    c.card {
        add(text(ctx, "🧭 " + t("Алгоритм", "Algorithm"), Type.TITLE_M, bold = true), 0)
        muted(t("Как YouTube связывает видео в нише: что стоит рядом с темой в «Следующем», из каких ниш она состоит, кто кому отдаёт зрителя, куда он уходит и что снять.",
            "How YouTube links videos in the niche: what sits next to the topic in “Up next”, its niches, who passes viewers to whom, where they leave and what to film."))
        if (g.nodes.isEmpty()) {
            p(t("Граф пока пуст. Настройте обход ниже и нажмите «Запустить».", "The graph is empty. Configure below and tap Run."))
            muted(t("Lumen откроет каждое стартовое видео, возьмёт колонку «Следующее» YouTube и пойдёт дальше по похожим — без аккаунта и ключей.",
                "Lumen opens each seed video, takes YouTube's “Up next” column and follows similar videos — no account or keys."))
        } else {
            add(text(ctx, t("Узлов ${g.nodes.size} · связей ${g.edges.size}", "Nodes ${g.nodes.size} · edges ${g.edges.size}"), Type.TITLE_M, bold = true), 8)
            muted(t("Последний обход: ", "Last run: ") + Fmt.dateTime(g.updatedAt) + " · " + AlgoMethod.strategyName(g.strategy.ifBlank { g.mode }) +
                " · " + t("регион ", "region ") + AlgoMethod.region(g.gl) + " · " + t("запросов всего: ", "total requests: ") + g.requests)
            muted(t("Это слой «Следующего» без входа в аккаунт — одинаковый для всех, не персональные рекомендации.",
                "This is the logged-out “Up next” layer — the same for everyone, not personal recommendations."))
            add(rowOf(ctx,
                btn(ctx, t("Открыть граф", "Open graph"), primary = true) {
                    startActivity(Intent(ctx, GraphActivity::class.java).putExtra("pid", project.id))
                },
                btn(ctx, t("Сбросить", "Reset"), style = BtnStyle.OUTLINED) {
                    val old = RecGraph.load(project.id)
                    undoable(t("Граф рекомендаций сброшен (видео проекта не тронуты)", "Recommendation graph reset (project videos untouched)"), apply = {
                        RecGraph.clear(project.id)
                        AlgoAnalysis.invalidate(project.id)
                        render()
                    }, revert = {
                        RecGraph.save(project.id, old)
                        AlgoAnalysis.invalidate(project.id)
                        render()
                    })
                }
            ), 8)
        }
    }
}

fun ProjectActivity.algoAuditCard(c: Col) {
    c.card {
        add(text(ctx, t("🧪 Аудит алгоритма", "🧪 Algorithm audit"), Type.TITLE_M, bold = true), 0)
        muted(t("Виртуальный зритель много раз идёт по «Следующему»: как быстро уходит тема, к каким каналам и видео сходится алгоритм. С интервалами по бутстрэпу.",
            "A virtual viewer follows “Up next” many times: how fast the topic fades, which channels and videos the algorithm converges to. With bootstrap intervals."))
        button(t("Открыть аудит", "Open audit"), style = BtnStyle.TONAL) { AuditActivity.open(ctx, project.id) }
    }
}

/** Leak per hop with its interval, by depth, and the filter's quality against the user's marks. */
fun ProjectActivity.algoLeakCard(c: Col, g: RecGraph, res: AlgoResult?) {
    c.h2(t("Утечка с темы за шаг", "Topic leak per hop"))
    if (res == null) {
        c.muted(t("Считаю ниши и метрики графа…", "Computing niches and graph metrics…"))
        return
    }
    val l = res.leak
    if (l.judged == 0) {
        c.muted(t("Нужен фильтр по теме: без оценок «по теме / не по теме» утечку не посчитать.", "The topic filter is needed: without on/off-topic grades the leak cannot be computed."))
    } else {
        c.p(t("${pct(l.rate)} рекомендаций рядом с тематическими видео — не по теме (90%: ${pct(l.lo)}–${pct(l.hi)}, ${l.judged} рекомендаций от ${l.parents} видео).",
            "${pct(l.rate)} of recommendations next to on-topic videos are off topic (90%: ${pct(l.lo)}–${pct(l.hi)}, ${l.judged} recommendations from ${l.parents} videos)."))
        if (res.leakByDepth.size > 1) {
            c.add(BarChartView(this).apply {
                values = res.leakByDepth.map { it.second.rate * 100 }
                labels = res.leakByDepth.map { if (it.first == 0) t("старт", "seed") else it.first.toString() }
                contentDescription = t("Утечка по шагам", "Leak by step")
            }, 8)
        }
        if (!project.recs.expandOffTopic) c.muted(t("Видео не по теме не раскрываются, поэтому «долю по теме по шагам» ниже читайте как оценку сверху; утечка за шаг считается по связям и от этого не зависит.",
            "Off-topic videos are not opened, so read the “on-topic share by step” below as an upper bound; the leak per hop is computed from links and does not depend on it."))
    }
    // the user's marks: how good is the filter
    val marks = AlgoLabels.load(project.id)
    val marked = marks.mapNotNull { (id, m) -> g.nodes[id]?.let { it to m.v } }
    if (marked.isEmpty()) {
        c.muted(t("Долгое нажатие на видео в списке — отметить «по теме / не по теме». Отметки проверяют фильтр и подстраивают его порог.",
            "Long-press a video in the list to mark it on/off topic. Marks check the filter and tune its threshold."))
    } else {
        val truth = marked.map { it.second }
        val fin = AlgoQuality.prf(marked.map { it.first.relevant.takeIf { r -> r != -1 } }, truth)
        val cut = AlgoQuality.bestCut(marked.map { it.first.score }, truth) ?: AlgoJudge.CUT
        val txt = AlgoQuality.prf(marked.map { n -> n.first.score.takeIf { it >= 0 }?.let { if (it >= cut) 1 else 0 } }, truth)
        val ai = AlgoQuality.prf(marked.map { n -> when (n.first.tier) { 3, 2 -> 1; 1, 0 -> 0; else -> null } }, truth)
        c.muted(t("Ваша разметка: ${marked.size}. Фильтр: точность ${pct(fin.precision)}, полнота ${pct(fin.recall)}", "Your marks: ${marked.size}. Filter: precision ${pct(fin.precision)}, recall ${pct(fin.recall)}") +
            (if (txt.n > 0) t(" · по тексту ${pct(txt.precision)}/${pct(txt.recall)}", " · text ${pct(txt.precision)}/${pct(txt.recall)}") else "") +
            (if (ai.n > 0) t(" · ИИ ${pct(ai.precision)}/${pct(ai.recall)} (${ai.n})", " · AI ${pct(ai.precision)}/${pct(ai.recall)} (${ai.n})") else "") +
            (if (marked.size >= 12) t(" · порог по разметке ${Math.round(cut * 100)}%", " · threshold from marks ${Math.round(cut * 100)}%") else t(" · порог подстроится с 12 отметок", " · the threshold adapts from 12 marks")))
    }
}

/** Long-press on a recommended video: the user's on/off-topic mark. */
fun ProjectActivity.labelSheet(n: RecNode) {
    bottomSheet(n.title.ifBlank { n.id }.take(80)) { dismiss ->
        muted(t("По теме ли это видео? Ваши отметки проверяют фильтр (точность и полнота) и подстраивают его порог.",
            "Is this video on topic? Your marks check the filter (precision and recall) and tune its threshold."))
        if (n.label.isNotBlank()) muted(t("Метка ИИ: ", "AI label: ") + n.label)
        if (n.score >= 0) muted(t("Близость к теме по тексту: ", "Text closeness to the topic: ") + Math.round(n.score * 100) + "%")
        button("✓ " + t("По теме", "On topic"), primary = true) {
            AlgoLabels.set(project.id, n, 1)
            dismiss()
            render()
        }
        button("✗ " + t("Не по теме", "Off topic"), style = BtnStyle.TONAL) {
            AlgoLabels.set(project.id, n, 0)
            dismiss()
            render()
        }
        button(t("Убрать отметку", "Clear mark"), style = BtnStyle.TEXT) {
            AlgoLabels.set(project.id, n, null)
            dismiss()
            render()
        }
    }
}

fun ProjectActivity.algoSections(c: Col, g: RecGraph, res: AlgoResult?) {
    if (res == null) {
        c.muted(t("Ниши, потоки и прогноз появятся, когда закончится расчёт.", "Niches, flows and the forecast appear when the computation finishes."))
        algoAuditCard(c)
        return
    }
    nichesSection(c, res)
    flowsSection(c, res)
    myChannelSection(c, res)
    oppsSection(c, res)
    dynamicsSection(c, g)
    algoAuditCard(c)
    predictSection(c, res)
}

// ---------------------------------------------------------------- niches

private fun ProjectActivity.nichesSection(c: Col, r: AlgoResult) {
    sec(c, "niches", "🧩 " + t("Ниши", "Niches"), t("${r.niches.size} групп · куда уходит зритель", "${r.niches.size} groups · where viewers go"), defOpen = true) {
        if (r.niches.isEmpty()) {
            muted(t("Ниши появятся, когда в графе будет больше связей (группы от 3 видео).", "Niches appear when the graph has more links (groups of 3+ videos)."))
        }
        for (ni in r.niches) {
            val card = card {
                add(text(ctx, "${ni.key} · ${ni.label}", Type.TITLE_M, bold = true), 0)
                if (ni.about.isNotBlank()) muted(ni.about)
                muted(t("${ni.members.size} видео · по теме ${pct(ni.onShare)} · авторитет ${pct(ni.authShare)} · свежих ${pct(ni.freshShare)} · каналов за 90 дн ${ni.channels90}",
                    "${ni.members.size} videos · on topic ${pct(ni.onShare)} · authority ${pct(ni.authShare)} · fresh ${pct(ni.freshShare)} · channels in 90 d ${ni.channels90}"))
                if (ni.terms.isNotEmpty()) muted(t("Слова: ", "Words: ") + ni.terms.joinToString(", "))
                if (ni.channels.isNotEmpty()) muted(t("Каналы: ", "Channels: ") + ni.channels.take(3).joinToString(", ") { "${it.first} (${it.second})" })
            }
            card.setOnClickListener { nicheSheet(r, ni) }
        }
        if (r.nicheFlows.isNotEmpty()) {
            h2(t("Потоки между нишами", "Flows between niches"))
            muted(r.nicheFlows.entries.sortedByDescending { it.value }.take(8).joinToString("\n") { (k, w) ->
                val a = r.niches.first { it.index == k.first }
                val b = r.niches.first { it.index == k.second }
                "${a.key} ${a.label.take(24)} → ${b.key} ${b.label.take(24)} · ${f1(w)}"
            })
        }
        val chExits = RecGraph.load(project.id, RecGraph.CHANNELS_FILE).exits
        if (r.exits.isNotEmpty() || chExits.isNotEmpty()) {
            h2(t("Выходы: куда уводит от темы", "Exits: where it leads off topic"))
            if (r.exits.isNotEmpty()) muted(t("Видео (метки ИИ): ", "Videos (AI labels): ") + r.exits.joinToString(", ") { "${it.first} ×${it.second}" })
            if (chExits.isNotEmpty()) muted(t("Каналы: ", "Channels: ") + chExits.take(10).joinToString(", ") { it.title + (if (it.label.isNotBlank()) " (${it.label})" else "") })
        }
        row(
            btn(ctx, t("✨ Назвать ниши", "✨ Name niches"), style = BtnStyle.TONAL) {
                if (!needAi()) return@btn
                bgTask(t("Имена ниш", "Niche names"), { AlgoAi.nameNiches(project, r) }, { err(it) }) { k ->
                    toast(t("Названо ниш: $k", "Niches named: $k"))
                    render()
                }
            },
            btn(ctx, t("✨ Разбор графа", "✨ Graph review"), style = BtnStyle.OUTLINED) {
                aiMarkdown(t("Разбор графа", "Graph review")) { AlgoAi.report(project, r) }
            }
        )
    }
}

private fun ProjectActivity.nicheSheet(r: AlgoResult, ni: AlgoNiche) {
    bottomSheet("${ni.key} · ${ni.label}") { _ ->
        if (ni.about.isNotBlank()) muted(ni.about)
        muted(t("Длительность рекомендованных: ", "Duration of recommended: ") + (if (ni.durLo > 0) Fmt.duration(ni.durLo) + "–" + Fmt.duration(ni.durHi) else "—") +
            t(" · просмотров в день (медиана): ", " · views per day (median): ") + (if (ni.velocity.isNaN()) "—" else Fmt.num(ni.velocity.toLong())) +
            t(" · HHI каналов ", " · channel HHI ") + String.format(java.util.Locale.US, "%.2f", ni.hhi))
        add(text(ctx, t("Авторитеты ниши", "Authorities of the niche"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        for (i in ni.members.sortedByDescending { r.auth[it] }.take(12)) {
            val n = r.nodes[i]
            add(videoListRow(ctx, n.thumb, n.title.ifBlank { n.id }, n.channel, "×${n.hits} · ${Fmt.num(n.views)}", n.url) { openVideo(n.url) }, 8)
        }
        val out = r.nicheFlows.filter { it.key.first == ni.index }.entries.sortedByDescending { it.value }.take(4)
        val inn = r.nicheFlows.filter { it.key.second == ni.index }.entries.sortedByDescending { it.value }.take(4)
        if (out.isNotEmpty()) muted(t("Ведёт в: ", "Leads to: ") + out.joinToString(", ") { e -> r.niches.first { it.index == e.key.second }.label })
        if (inn.isNotEmpty()) muted(t("Приходят из: ", "Comes from: ") + inn.joinToString(", ") { e -> r.niches.first { it.index == e.key.first }.label })
    }
}

// ---------------------------------------------------------------- channel flows

private fun ProjectActivity.flowsSection(c: Col, r: AlgoResult) {
    sec(c, "flows", "🔀 " + t("Потоки каналов", "Channel flows"), t("удержание на своём канале ${pct(r.retention)}", "same-channel retention ${pct(r.retention)}")) {
        p(t("Удержание на своём канале: ${pct(r.retention)} — такая доля рекомендаций рядом с видео ведёт на тот же канал.",
            "Same-channel retention: ${pct(r.retention)} — this share of recommendations next to a video leads to the same channel."))
        muted(t("Открытость темы: к свежим видео (<90 дн) ведут ${pct(r.fresh)} рекомендаций, к каналам вне топ-10 — ${pct(r.tail)}.",
            "Openness: ${pct(r.fresh)} of recommendations lead to fresh videos (<90 d), ${pct(r.tail)} to channels outside the top 10."))
        h2(t("Каналы", "Channels"))
        for (ch in r.channels.take(12)) {
            muted("${ch.name} · ${ch.videos} " + t("видео", "videos") + " · " + t("приходит", "in") + " ${f1(ch.inflow)} · " + t("уходит", "out") + " ${f1(ch.outflow)} · " +
                t("своё", "self") + " ${pct(ch.retention)} · " + (if (ch.net >= 0) t("импортёр", "importer") else t("экспортёр", "exporter")))
        }
        if (r.channelFlows.isNotEmpty()) {
            h2(t("Кто кому отдаёт зрителя", "Who passes viewers to whom"))
            muted(r.channelFlows.take(15).joinToString("\n") { "${it.first} → ${it.second} · ${f1(it.third)}" })
        }
        muted(t("Сила потока — сумма весов рекомендаций с учётом места в «Следующем» (первая карточка весит больше).",
            "Flow strength = sum of recommendation weights by place in “Up next” (the first card weighs more)."))
    }
}

// ---------------------------------------------------------------- my channel

private fun ProjectActivity.myChannelSection(c: Col, r: AlgoResult) {
    val (url, name) = AlgoAnalysis.me(project.id)
    sec(c, "me", "👤 " + t("Мой канал", "My channel"), name.ifBlank { url.ifBlank { t("не задан", "not set") } }) {
        val e = edit(t("Ссылка на ваш канал", "Your channel link"), url)
        button(t("Сохранить канал", "Save channel"), style = BtnStyle.TONAL) {
            val u = e.text.toString().trim()
            AlgoAnalysis.setMe(project.id, u, ChannelStore.get(project.id, u)?.name.orEmpty())
            render()
        }
        if (url.isBlank()) {
            muted(t("Вставьте ссылку на свой канал: Lumen покажет, кто ведёт к вашим видео, куда уходят зрители и с кем вас показывают.",
                "Paste your channel link: Lumen shows who leads to your videos, where viewers go and who you are shown with."))
            return@sec
        }
        val ego = AlgoAnalysis.ego(r, url, name)
        if (ego == null) {
            p(t("Ваших видео пока нет в графе.", "Your videos are not in the graph yet."))
            muted(t("Сделайте свои последние видео стартовыми и запустите обход — тогда граф вырастет от них.",
                "Make your latest videos the seeds and run the crawl — the graph will grow from them."))
            button(t("Мои видео — стартовыми", "Use my videos as seeds"), style = BtnStyle.OUTLINED) {
                bgTask(t("Видео канала", "Channel videos"), { ChannelFetch.fetch(project.id, url, recentLimit = 10, withPlaylists = false) }, { err(it) }) { ch ->
                    AlgoAnalysis.setMe(project.id, url, ch.name)
                    project.recs.seeds = "custom"
                    project.recs.customSeeds = ch.recent.filter { !it.isShort }.take(10).joinToString("\n") { it.url }
                    Store.save(project)
                    toast(t("Стартовые видео заданы — нажмите «Запустить»", "Seeds set — tap Run"))
                    render()
                }
            }
            return@sec
        }
        p(t("В графе ${ego.mine.size} ваших видео; лучшее место по близости к стартовым — ${if (ego.bestPprRank < 0) "—" else (ego.bestPprRank + 1).toString()} из ${r.central.size}; удержание на своём канале ${pct(ego.selfShare)}.",
            "${ego.mine.size} of your videos are in the graph; best place by closeness to the seeds — ${if (ego.bestPprRank < 0) "—" else (ego.bestPprRank + 1).toString()} of ${r.central.size}; same-channel retention ${pct(ego.selfShare)}."))
        if (ego.sourceChannels.isNotEmpty()) muted(t("Кто ведёт к вам: ", "Who leads to you: ") + ego.sourceChannels.take(5).joinToString(", ") { "${it.first} (${f1(it.second)})" })
        if (ego.sources.isNotEmpty()) muted(ego.sources.take(5).joinToString("\n") { (i, _) -> "→ «" + r.nodes[i].title.take(60) + "» — " + r.nodes[i].channel })
        if (ego.leakChannels.isNotEmpty()) muted(t("Куда уходят ваши зрители: ", "Where your viewers go: ") + ego.leakChannels.take(5).joinToString(", ") { "${it.first} (${f1(it.second)})" })
        if (ego.competitors.isNotEmpty()) muted(t("С кем вас показывают (общие соседи): ", "Shown with (shared neighbours): ") + ego.competitors.take(5).joinToString(", ") { "${it.first} ${Math.round(it.second * 100)}%" })
        if (ego.nicheShare.isNotEmpty()) muted(t("Ваши ниши: ", "Your niches: ") + ego.nicheShare.joinToString(", ") { "${it.first} ×${it.second}" })
        button(t("✨ Советы по каналу", "✨ Channel advice"), style = BtnStyle.TONAL) {
            aiMarkdown(t("Мой канал", "My channel")) { AlgoAi.myChannel(project, r, ego) }
        }
    }
}

// ---------------------------------------------------------------- opportunities

private fun ProjectActivity.oppsSection(c: Col, r: AlgoResult) {
    sec(c, "opps", "💡 " + t("Возможности", "Opportunities"), t("спрос против предложения по нишам", "demand vs supply by niche")) {
        muted(t("Оценка ниши = спрос (просмотры в день × доля авторитета) − предложение (каналы с видео за 90 дней) + старость ядра. Выше — свободнее.",
            "Niche score = demand (views per day × authority share) − supply (channels publishing in 90 days) + age of the core. Higher = more room."))
        for (ni in r.niches.sortedByDescending { it.opportunity }) {
            muted("${ni.key} · ${ni.label} · " + t("оценка", "score") + " ${if (ni.opportunity >= 0) "+" else ""}${f1(ni.opportunity)} · " +
                t("просмотров/день", "views/day") + " ${if (ni.velocity.isNaN()) "—" else Fmt.num(ni.velocity.toLong())} · " + t("каналов за 90 дн", "channels in 90 d") + " ${ni.channels90} · " +
                t("старые в ядре", "old in core") + " ${pct(ni.oldAuthShare)}")
        }
        val (searchOnly, recsOnly) = AlgoAnalysis.searchOverlap(r, videos)
        if (searchOnly.isNotEmpty() || recsOnly.isNotEmpty()) {
            h2(t("Поиск ↔ рекомендации", "Search ↔ recommendations"))
            if (searchOnly.isNotEmpty()) muted(t("Сильны в поиске, но не в «Следующем»: ", "Strong in search, not in Up next: ") + searchOnly.take(5).joinToString("; ") { "«${it.title.take(50)}»" })
            if (recsOnly.isNotEmpty()) muted(t("В центре «Следующего», но не в поиске проекта: ", "Central in Up next, not in the project's search: ") + recsOnly.take(5).joinToString("; ") { "«${it.title.take(50)}»" })
        }
        val (opps, names) = AlgoAi.savedOpportunities(project.id)
        for (o in opps) {
            card {
                add(text(ctx, o.title, Type.TITLE_M, bold = true), 0)
                if (o.angle.isNotBlank()) p(o.angle)
                if (o.why.isNotBlank()) muted(AlgoParse.cite(o.why, names))
                muted(listOfNotNull(
                    r.nicheByKey(o.niche)?.let { "${it.key} · ${it.label}" }, o.format, t("уверенность: ", "confidence: ") + o.confidence,
                    o.keywords.takeIf { it.isNotEmpty() }?.joinToString(", "), o.risk.takeIf { it.isNotBlank() }?.let { t("риск: ", "risk: ") + it }
                ).joinToString(" · "))
                if (o.nextTo.isNotEmpty()) muted(t("Рядом с: ", "Next to: ") + o.nextTo.joinToString("; ") { names[it]?.let { n -> "«$n»" } ?: it })
                add(chipRow(ctx, listOf(
                    chip(ctx, "🎬 Studio") { StudioActivity.openWith(ctx, "video", "", o.title + " — " + o.angle) },
                    chip(ctx, "🗣 LangGame") {
                        copy(o.title + "\n" + o.angle)
                        LangGameActivity.open(ctx, project.id)
                    },
                    chip(ctx, "🔎 SEO") {
                        copy((listOf(o.title) + o.keywords).joinToString(", "))
                        tab = "seo"
                        render()
                    },
                    chip(ctx, "🎯 " + t("Проверить", "Check")) {
                        ui["ideaTitle"] = o.title
                        ui["algo_sec_predict"] = true
                        render()
                    }
                )), 8)
            }
        }
        button(if (opps.isEmpty()) t("✨ Найти возможности", "✨ Find opportunities") else t("✨ Обновить возможности", "✨ Refresh opportunities"), style = BtnStyle.TONAL) {
            if (!needAi()) return@button
            bgTask(t("Возможности", "Opportunities"), { AlgoAi.opportunities(project, r, force = true) }, { err(it) }) { (list, _) ->
                toast(t("Возможностей: ${list.size}", "Opportunities: ${list.size}"))
                render()
            }
        }
    }
}

// ---------------------------------------------------------------- dynamics

private fun ProjectActivity.dynamicsSection(c: Col, g: RecGraph) {
    val snaps = RecGraph.snapshots(project.id)
    sec(c, "dyn", "📈 " + t("Динамика", "Dynamics"), t("снимков: ${snaps.size}", "snapshots: ${snaps.size}")) {
        if (snaps.size < 2) {
            muted(t("Снимок графа сохраняется после каждого обхода и подкачки в мониторинге (раз в день). Сравнение появится со второго снимка. Включите «Подкачку рекомендаций» в мониторинге проекта, чтобы видеть изменения сами.",
                "A snapshot is saved after every crawl and monitoring drip (once a day). The comparison appears from the second one. Turn on the recommendation drip in the project's monitoring to follow changes."))
        } else {
            val d = AlgoAnalysis.diff(snaps[snaps.size - 2], snaps.last())
            muted(Fmt.dateTime(d.from) + " → " + Fmt.dateTime(d.to))
            p(AlgoAnalysis.diffText(d))
            if (d.rising.isNotEmpty()) muted(t("Растут: ", "Rising: ") + d.rising.take(5).joinToString("; ") { "«${it.first.title.take(40)}» +${it.second}" })
            muted(t("RBO — насколько совпадает верх списка «ближе всего к стартовым» (100% — тот же порядок). Связи считаются обновлёнными, если появились или пропали.",
                "RBO — how much the top of the “closest to the seeds” list matches (100% = same order). Links count as changed if they appeared or disappeared."))
            button(t("✨ Сводка изменений", "✨ Change summary"), style = BtnStyle.TONAL) {
                aiMarkdown(t("Что изменилось", "What changed")) { AlgoAi.changes(project, d) to emptyMap() }
            }
        }
        if (g.runs.isNotEmpty()) {
            h2(t("Прогоны", "Runs"))
            muted(g.runs.takeLast(6).reversed().joinToString("\n") { o ->
                Fmt.dateTime(o.optLong("at")) + " · " + AlgoMethod.strategyName(o.optString("strategy").ifBlank { o.optString("mode") }) + " · " +
                    t("запросов", "requests") + " " + o.optInt("requests") + " · +" + o.optInt("added") + t(" узлов", " nodes") + " · +" + o.optInt("edges") + t(" связей", " links")
            })
        }
    }
}

// ---------------------------------------------------------------- forecast and radar

private fun ProjectActivity.modelLine(m: AlgoPredict.Model): String = when {
    m.logit == null -> t("Для модели мало данных: ${m.n} видео, из них попали в «Следующее» ${m.pos}. Соберите больше видео темы (поиск, каналы) и сделайте обход.",
        "Not enough data for the model: ${m.n} videos, ${m.pos} of them got into Up next. Collect more topic videos (search, channels) and crawl.")
    else -> t("Модель: ${m.n} видео, попали ${m.pos}. Качество на новых каналах AUC ${f1(m.auc * 100)}% (только канал: ${f1(m.aucBase * 100)}%; содержание добавляет ${if (m.uplift >= 0) "+" else ""}${f1(m.uplift * 100)} п.п.). Калибровка ±${pct(m.calError)}.",
        "Model: ${m.n} videos, ${m.pos} got in. Quality on unseen channels AUC ${f1(m.auc * 100)}% (channel only: ${f1(m.aucBase * 100)}%; content adds ${if (m.uplift >= 0) "+" else ""}${f1(m.uplift * 100)} pp). Calibration ±${pct(m.calError)}.") +
        if (!m.usable) t(" Этого мало для честного процента — показываю только соответствие нише.", " Not enough for an honest percentage — only the niche fit is shown.") else ""
}

private fun ProjectActivity.predictSection(c: Col, r: AlgoResult) {
    sec(c, "predict", "🎯 " + t("Прогноз и радар", "Forecast and radar"), t("шанс попасть в «Следующее»", "chance to get into Up next")) {
        muted(t("Главный сигнал YouTube — поведение и удовлетворённость зрителей — здесь не виден, поэтому прогноз вероятностный. Надёжнее — радар после публикации и тест названий в YouTube Studio (Test & Compare).",
            "YouTube's main signal — viewer behaviour and satisfaction — is not visible here, so the forecast is probabilistic. More reliable: the radar after publishing and title tests in YouTube Studio (Test & Compare)."))
        val key = "algoModel_" + r.g.updatedAt
        val m = ui[key] as? AlgoPredict.Model
        if (m == null) {
            muted(t("Обучаю модель на видео темы…", "Training the model on the topic's videos…"))
            if (ui["algoModelBusy"] != true) {
                ui["algoModelBusy"] = true
                Lumen.async({ AlgoPredict.model(project.id, r) }, {
                    ui["algoModelBusy"] = false
                    L.w("algo", "model: ${it.message}")
                }) {
                    ui["algoModelBusy"] = false
                    ui[key] = it
                    render()
                }
            }
        } else {
            muted(modelLine(m))
        }
        // ---- idea check
        h2(t("Проверить идею", "Check an idea"))
        val titleE = edit(t("Название ролика", "Video title"), ui["ideaTitle"] as? String ?: "")
        val descE = edit(t("Описание или о чём ролик (необязательно)", "Description or what it is about (optional)"), ui["ideaDesc"] as? String ?: "", multiline = true)
        val durE = edit(t("Длительность, минут", "Duration, minutes"), ui["ideaDur"] as? String ?: "", number = true)
        fun remember() {
            ui["ideaTitle"] = titleE.text.toString()
            ui["ideaDesc"] = descE.text.toString()
            ui["ideaDur"] = durE.text.toString()
        }
        row(
            btn(ctx, t("Проверить", "Check"), primary = true) {
                remember()
                val title = titleE.text.toString().trim()
                if (title.isBlank()) return@btn toast(t("Введите название", "Enter a title"))
                val dur = ((durE.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0) * 60).toLong()
                val (url, _) = AlgoAnalysis.me(project.id)
                val subs = if (url.isBlank()) -1L else ChannelStore.get(project.id, url)?.subscribers ?: -1L
                bgTask(t("Проверка идеи", "Idea check"), { AlgoPredict.check(project.id, r, title, descE.text.toString(), dur, url, subs) }, { err(it) }) {
                    ui["ideaCheck"] = it
                    render()
                }
            },
            btn(ctx, t("✨ Названия", "✨ Titles"), style = BtnStyle.TONAL) {
                remember()
                if (!needAi()) return@btn
                val check = ui["ideaCheck"] as? AlgoPredict.Check
                val idea = (titleE.text.toString() + "\n" + descE.text.toString()).trim()
                bgTask(t("Названия под нишу", "Titles for the niche"), { AlgoPredict.pitch(project, r, check?.niche, idea) }, { err(it) }) {
                    ui["ideaPitch"] = it
                    render()
                }
            }
        )
        (ui["ideaCheck"] as? AlgoPredict.Check)?.let { ch ->
            card {
                add(text(ctx, t("Результат", "Result"), Type.TITLE_M, bold = true), 0)
                ch.niche?.let { muted(t("Ниша: ", "Niche: ") + "${it.key} · ${it.label} (" + t("сходство", "similarity") + " ${Math.round(ch.nicheSim * 100)}%)") }
                p(t("Соответствие теме: ${Math.round(ch.fit * 100)}%", "Fit to the topic: ${Math.round(ch.fit * 100)}%"))
                if (ch.covered.isNotEmpty()) muted(t("Слова ниши в названии: ", "Niche words in the title: ") + ch.covered.joinToString(", "))
                if (ch.missing.isNotEmpty()) muted(t("Частые слова ниши, которых нет: ", "Common niche words missing: ") + ch.missing.joinToString(", "))
                if (ch.durationNote.isNotBlank()) muted(ch.durationNote)
                if (!ch.prob.isNaN()) {
                    p(t("Шанс попасть в «Следующее» рядом с темой: ${ch.band} (${Math.round(ch.prob * 100)}%, при возрасте ролика 30 дней)",
                        "Chance to get into Up next next to the topic: ${ch.band} (${Math.round(ch.prob * 100)}%, at 30 days of age)"))
                    if (ch.reasons.isNotEmpty()) muted(t("Почему: ", "Why: ") + ch.reasons.joinToString("; ") { (n, v) -> (if (v > 0) "↑ " else "↓ ") + n })
                } else {
                    muted(modelLine(ch.model))
                }
            }
        }
        (ui["ideaPitch"] as? AlgoPredict.Pitch)?.let { pi ->
            card {
                add(text(ctx, t("Названия под нишу", "Titles for the niche"), Type.TITLE_M, bold = true), 0)
                if (pi.pitch.angle.isNotBlank()) p(AlgoParse.cite(pi.pitch.angle, pi.names))
                for ((title, fit, sim) in pi.scored) {
                    muted("• $title — " + t("соответствие", "fit") + " ${Math.round(fit * 100)}%" + (if (sim > 0.8) t(" · ⚠ похоже на существующее", " · ⚠ similar to an existing one") else ""))
                }
                if (pi.pitch.duration.isNotBlank()) muted(t("Длительность: ", "Duration: ") + pi.pitch.duration)
                if (pi.pitch.hook.isNotBlank()) muted(t("Первые 30 секунд: ", "First 30 seconds: ") + pi.pitch.hook)
                if (pi.pitch.nextTo.isNotEmpty()) muted(t("Рядом с: ", "Next to: ") + pi.pitch.nextTo.joinToString("; ") { (id, why) -> "«${pi.names[id] ?: id}»" + (if (why.isNotBlank()) " — $why" else "") })
                if (pi.pitch.description.isNotBlank()) muted(t("Описание: ", "Description: ") + pi.pitch.description)
                if (pi.pitch.tags.isNotEmpty()) muted(t("Теги (почти не влияют): ", "Tags (minor effect): ") + pi.pitch.tags.joinToString(", "))
                add(chipRow(ctx, listOf(
                    chip(ctx, t("⧉ 3 названия для Test & Compare", "⧉ 3 titles for Test & Compare")) { copy(pi.scored.take(3).joinToString("\n") { it.first }) },
                    chip(ctx, "🎬 Studio") { StudioActivity.openWith(ctx, "video", "", pi.scored.firstOrNull()?.first.orEmpty()) }
                )), 8)
            }
        }
        // ---- radar
        h2("📡 " + t("Радар ролика", "Video radar"))
        muted(t("После публикации: раз в сутки (при включённом мониторинге проекта) Lumen смотрит, к какой нише YouTube отнёс ролик, у каких хабов он стоит в «Следующем» и на каком месте, и позиции в поиске. 30 дней.",
            "After publishing: once a day (with the project's monitoring on) Lumen checks which niche YouTube filed the video under, next to which hubs it stands in Up next and where, and search positions. 30 days."))
        val linkE = edit(t("Ссылка на ваш ролик", "Link to your video"), "")
        button(t("Отслеживать", "Track"), style = BtnStyle.TONAL) {
            val u = linkE.text.toString().trim()
            if (u.isBlank()) return@button
            bgTask(t("Радар ролика", "Video radar"), { AlgoRadar.add(project.id, u, r) }, { err(it) }) {
                toast(t("Ролик добавлен в радар", "Video added to the radar"))
                render()
            }
        }
        val items = AlgoRadar.list(project.id)
        for (it in items.sortedByDescending { x -> x.addedAt }) {
            card {
                add(text(ctx, it.title.ifBlank { it.id }, Type.TITLE_M, bold = true), 0)
                val days = (System.currentTimeMillis() - it.addedAt) / 86_400_000L
                muted(t("День $days из ${AlgoRadar.DAYS}", "Day $days of ${AlgoRadar.DAYS}") + (if (!it.prob.isNaN()) t(" · прогноз ${Math.round(it.prob * 100)}%", " · forecast ${Math.round(it.prob * 100)}%") else "") +
                    t(" · проверок ${it.checks.size}", " · checks ${it.checks.size}"))
                it.checks.lastOrNull()?.let { ck ->
                    if (ck.niches.isNotEmpty()) muted(t("YouTube относит к: ", "YouTube files it under: ") + ck.niches.take(3).joinToString(", ") { (n, s) -> "$n ${Math.round(s * 100)}%" })
                    muted(if (ck.hubs.isEmpty()) t("Пока не стоит рядом с хабами темы.", "Not next to the topic's hubs yet.")
                    else t("Рядом с хабами: ", "Next to hubs: ") + ck.hubs.joinToString("; ") { (h, pos) -> "«" + (r.ix[h]?.let { i -> r.nodes[i].title.take(40) } ?: h) + "» #" + (pos + 1) })
                    if (ck.search.isNotEmpty()) muted(t("Поиск: ", "Search: ") + ck.search.joinToString("; ") { (q, pos) -> "«$q» #$pos" })
                }
                add(rowOf(ctx,
                    btn(ctx, t("Проверить сейчас", "Check now"), style = BtnStyle.OUTLINED) {
                        bgTask(t("Радар ролика", "Video radar"), {
                            val list = AlgoRadar.list(project.id)
                            list.firstOrNull { x -> x.id == it.id }?.let { x -> AlgoRadar.checkOne(project.id, x, r) }
                            AlgoRadar.save(project.id, list)
                        }, { e -> err(e) }) { render() }
                    },
                    btn(ctx, "🗑", style = BtnStyle.TEXT) {
                        val id = it.id
                        val before = AlgoRadar.list(project.id)
                        undoable(t("Ролик убран из радара", "Video removed from the radar"), apply = {
                            AlgoRadar.remove(project.id, id)
                            render()
                        }, revert = {
                            AlgoRadar.save(project.id, before)
                            render()
                        })
                    }
                ), 8)
            }
        }
        val (n, hit, appeared) = AlgoRadar.outcomes(project.id)
        if (n > 0) muted(t("Прогнозы по $n роликам (≥14 дней): сбылось $hit, попали рядом с хабами $appeared.", "Forecasts for $n videos (≥14 days): came true $hit, got next to hubs $appeared."))
    }
}

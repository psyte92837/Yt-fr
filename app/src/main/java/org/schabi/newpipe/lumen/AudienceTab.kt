/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import org.json.JSONObject

private fun vocLabel(cat: String) = when (cat) {
    Voc.QUESTION, "question" -> t("Вопросы", "Questions")
    Voc.PAIN, "pain" -> t("Боли", "Pains")
    Voc.REQUEST -> t("Запросы на контент", "Content requests")
    Voc.OBJECTION, "objection" -> t("Возражения", "Objections")
    "jtbd" -> "JTBD"
    "voc" -> t("Голос аудитории", "Voice of customer")
    "unmet_need" -> t("Неудовл. потребности", "Unmet needs")
    "content_gap" -> t("Пробелы контента", "Content gaps")
    "search_intent" -> "Search intent"
    else -> cat
}

private fun confidenceLabel(c: String) = when (c) {
    "high" -> t("высокая", "high")
    "medium" -> t("средняя", "medium")
    else -> t("низкая", "low")
}

/** "Audience" tab: pains, questions, requests, objections; AI insights with verified quotes; clusters. */
fun ProjectActivity.audienceTab(c: Col) {
    val all = videos.values.toList()
    if (all.none { it.commentsCount > 0 || it.hasTranscript }) {
        c.muted(t("Нет собранных комментариев или расшифровок. Соберите их на вкладке «Сбор» или импортируйте файл TXT/CSV/JSON на вкладке «Видео» → «Импорт».",
            "No collected comments or transcripts. Collect them on the Collect tab or import a TXT/CSV/JSON file (Videos → Import)."))
        return
    }
    val marked = all.filter { it.marked }
    val scope = if (marked.isNotEmpty()) marked else all
    c.muted(t("Область: ", "Scope: ") + (if (marked.isNotEmpty()) t("отмеченные", "marked") else t("все", "all")) + " (${scope.size})")
    c.card {
        muted(t("Комментарии — источник гипотез и языка аудитории, а не доказательство поискового спроса. Число сообщений ≠ число людей.",
            "Comments are a source of hypotheses and audience language, not proof of search demand. Messages ≠ people."))
    }

    // ---------------- local, free
    c.h2(t("Сигналы аудитории (без AI)", "Audience signals (no AI)"))
    val withTr = ui.getOrPut("vocTr") { false } as Boolean
    val cat = ui.getOrPut("vocCat") { Voc.PAIN } as String
    val cacheKey = "${scope.size}|${scope.sumOf { it.commentsCount }}|$withTr|${scope.count { it.hasTranscript }}"
    @Suppress("UNCHECKED_CAST")
    val items = (ui["vocCache"] as? Pair<String, List<Voc.Item>>)?.takeIf { it.first == cacheKey }?.second
        ?: Seo.localVoc(project.id, scope, withTr).also { ui["vocCache"] = cacheKey to it }
    val counts = items.groupingBy { it.category }.eachCount()
    c.chips(Voc.categories.map { k ->
        chip(this, vocLabel(k) + " ${counts[k] ?: 0}", cat == k) {
            ui["vocCat"] = k
            render()
        }
    } + chip(this, t("+ расшифровки", "+ transcripts"), withTr) {
        ui["vocTr"] = !withTr
        render()
    })
    val shown = items.filter { it.category == cat }
    if (shown.isEmpty()) c.muted("—")
    val byVid = videos
    shown.take(40).forEach { item ->
        val card = c.card {
            add(text(ctx, item.text.take(400), 14f), 0)
            val src = byVid[item.sourceId]
            muted(buildString {
                if (item.count > 1) append("×").append(item.count).append(t(" сообщений", " messages"))
                if (item.authors.size > 1) append(" · ").append(item.authors.size).append(t(" авторов", " authors"))
                if (item.likes > 0) append(" · ♥").append(item.likes)
                if (item.commentIndex < 0) append(" · ").append(t("автор видео", "video author")).append(item.timestamp?.let { ts -> " [$ts]" } ?: "")
                append(" · ").append(src?.title?.take(50) ?: item.sourceId)
            })
        }
        card.setOnClickListener {
            val v = byVid[item.sourceId]
            val cm = if (item.commentIndex >= 0) Store.comments(project.id, item.sourceId).getOrNull(item.commentIndex) else null
            val url = Seo.sourceUrl(v, cm?.id?.takeIf { s -> s.isNotBlank() }, item.timestamp)
            if (url.isNotBlank()) openVideo(url) else showText(vocLabel(item.category), item.text)
        }
    }
    if (shown.size > 40) c.muted(t("…и ещё ${shown.size - 40}. Полный список — в экспорте CSV.", "…and ${shown.size - 40} more. Full list in CSV export."))

    // ---------------- AI insights
    c.h2(t("AI-инсайты с цитатами (P1)", "AI insights with quotes (P1)"))
    AiJournal.list(project.id).firstOrNull { it.task == "p1_insights" || it.task == "p2_clusters" }?.let { last ->
        c.button(t("Последний ответ AI: ", "Last AI answer: ") + AiJournal.statusLabel(last.status) + " · " + Fmt.dateTime(last.at), style = BtnStyle.TEXT) {
            AiJournalActivity.open(this, id = last.id)
        }
    }
    val ins = Seo.insights(project.id)
    val pending = scope.filter { (it.commentsCount > 0 || it.hasTranscript) && !Seo.hasInsights(project.id, it.id) }
    c.muted(t("Pain, JTBD, вопросы, VOC-цитаты, возражения, неудовлетворённые потребности, пробелы контента. Каждая цитата проверяется на наличие в источнике.",
        "Pain, JTBD, questions, VOC quotes, objections, unmet needs, content gaps. Every quote is checked against its source."))
    c.row(
        btn(this, t("Извлечь (${pending.size} новых)", "Extract (${pending.size} new)"), primary = true) {
            val target = pending.take(30)
            if (target.isEmpty()) return@btn toast(t("Все выбранные источники уже обработаны", "All selected sources are processed"))
            aiRun(t("Извлечение инсайтов", "Insight extraction"), target.sumOf { v -> Seo.estimateP1(project.id, v) }) {
                val started = java.util.concurrent.atomic.AtomicInteger(0)
                val n = Par.map(target, cancel = cancel) { _, v ->
                    val i = started.incrementAndGet()
                    Lumen.ui { progressView?.text = "P1 $i/${target.size}: ${v.title}" }
                    Seo.extractInsights(project, v)
                }.sumOf { it ?: 0 }
                AiResult("", "p1", "project", "", t("Извлечено инсайтов: $n из ${target.size} источников.", "Extracted $n insights from ${target.size} sources."), 0, 0)
            }
        },
        btn(this, t("Кластеры (P2)", "Clusters (P2)"), style = BtnStyle.TONAL) {
            aiRun(t("Кластеризация", "Clustering"), Llm.estimateTokens(ins.joinToString { it.optString("statement") }) + 3000) {
                val n = Seo.clusterInsights(project)
                AiResult("", "p2", "project", "", t("Тем: $n. Откройте вкладку SEO.", "Topics: $n. Open the SEO tab."), 0, 0)
            }
        }
    )
    if (ins.isNotEmpty()) {
        val verified = ins.count { i -> i.optJSONArray("quotes")?.optJSONObject(0)?.optBoolean("verified") == true }
        c.muted(t("Инсайтов: ${ins.size} · цитат найдено в источнике: $verified (${verified * 100 / ins.size}%) · принято: ${ins.count { it.optString("status") == "accepted" }} · отклонено: ${ins.count { it.optString("status") == "rejected" }}",
            "Insights: ${ins.size} · quotes found in source: $verified (${verified * 100 / ins.size}%) · accepted: ${ins.count { it.optString("status") == "accepted" }} · rejected: ${ins.count { it.optString("status") == "rejected" }}"))
        val types = ins.groupingBy { it.optString("type") }.eachCount().entries.sortedByDescending { it.value }
        val type = ui.getOrPut("insType") { types.first().key } as String
        val status = ui.getOrPut("insStatus") { "active" } as String
        c.chips(types.map { (k, n) ->
            chip(this, vocLabel(k) + " $n", type == k) {
                ui["insType"] = k
                render()
            }
        })
        c.chips(listOf("active" to t("активные", "active"), "accepted" to t("принятые", "accepted"), "rejected" to t("отклонённые", "rejected")).map { (k, l) ->
            chip(this, l, status == k) {
                ui["insStatus"] = k
                render()
            }
        })
        ins.filter { it.optString("type") == type }
            .filter { when (status) { "active" -> it.optString("status") != "rejected"; else -> it.optString("status") == status } }
            .take(60).forEach { i -> c.add(insightCard(i), 10) }
    }

    // ---------------- clusters overview
    val clusters = Seo.clusters(project.id)
    if (clusters.isNotEmpty()) {
        c.h2(t("Темы (кластеры)", "Topics (clusters)"))
        clusters.sortedByDescending { it.optInt("mention_count") }.forEach { cl ->
            val card = c.card {
                add(text(ctx, cl.optString("name"), Type.TITLE_M, bold = true), 0)
                muted(cl.optString("need"))
                muted(t("сообщений: ", "messages: ") + cl.optInt("mention_count") +
                    (if (cl.isNull("unique_author_count")) "" else t(" · авторов: ", " · authors: ") + cl.optInt("unique_author_count")) +
                    " · " + t("источников: ", "sources: ") + (cl.optJSONArray("source_ids")?.length() ?: 0) +
                    " · " + t("уверенность ", "confidence ") + confidenceLabel(cl.optString("confidence")) +
                    " · " + cl.optString("intent_hypothesis"))
            }
            card.setOnClickListener {
                startActivity(android.content.Intent(this, TopicActivity::class.java).putExtra("pid", project.id).putExtra("cid", cl.optString("id")))
            }
        }
    }
}

private fun ProjectActivity.insightCard(i: JSONObject): android.widget.LinearLayout {
    val c = Col(this)
    c.root.background = ripple(this, roundBg(Palette.CARD, 12f, this), Palette.ON_SURFACE)
    val q = i.optJSONArray("quotes")?.optJSONObject(0)
    val st = i.optString("status")
    c.add(text(this, (if (st == "accepted") "✓ " else if (st == "rejected") "✗ " else "") + i.optString("statement"), 14f, bold = true), 0)
    if (q != null && q.optString("text").isNotBlank()) {
        c.add(text(this, "«" + q.optString("text").take(300) + "»", 13f, color = Palette.ON_SURFACE), 6)
        c.muted((if (q.optBoolean("verified")) t("✓ цитата найдена", "✓ quote found") else t("⚠ цитата не найдена в источнике", "⚠ quote not found in source")) +
            " · " + (if (q.optString("speaker_type") == "commenter") t("комментатор", "commenter") else t("автор видео", "video author")) +
            (q.optString("timestamp").takeIf { it.isNotBlank() && it != "null" }?.let { " [$it]" } ?: ""))
    }
    c.muted(t("уверенность ", "confidence ") + confidenceLabel(i.optString("confidence")) + " · ×" + i.optInt("mention_count", 1) + " · " + i.optString("video_title").take(40))
    c.root.setOnClickListener {
        val msg = buildString {
            append(i.optString("statement")).append("\n\n")
            if (q != null) append("«").append(q.optString("text")).append("»\n").append(q.optString("url")).append("\n\n")
            if (i.optString("interpretation").isNotBlank()) append(t("Интерпретация: ", "Interpretation: ")).append(i.optString("interpretation")).append('\n')
            if (i.optString("hypothesis").isNotBlank()) append(t("Гипотеза: ", "Hypothesis: ")).append(i.optString("hypothesis")).append('\n')
            append(t("\nТип доказательства: ", "\nEvidence: ")).append(i.optString("evidence_type"))
            i.optJSONObject("_meta")?.let { m -> append("\n").append(m.optString("model")).append(" · prompt ").append(m.optString("prompt_version")) }
        }
        val b = AlertDialog.Builder(this).setTitle(vocLabel(i.optString("type"))).setMessage(msg)
            .setPositiveButton(t("Принять", "Accept")) { _, _ ->
                Seo.setInsightStatus(project.id, i.optString("id"), "accepted")
                render()
            }
            .setNegativeButton(t("Отклонить", "Reject")) { _, _ ->
                Seo.setInsightStatus(project.id, i.optString("id"), "rejected")
                render()
            }
        val url = q?.optString("url").orEmpty()
        if (url.isNotBlank()) b.setNeutralButton(t("Источник", "Source")) { _, _ -> openVideo(url) }
        b.show()
    }
    return c.root
}

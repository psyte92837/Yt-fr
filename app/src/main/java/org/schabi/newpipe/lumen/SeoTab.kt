/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent

/** "SEO" tab: context, keyword research (autocomplete), topics pipeline, ideas, usage. */
fun ProjectActivity.seoTab(c: Col) {
    val seo = project.seo
    val save = { Store.save(project) }
    c.card {
        muted(t("Цель — полезная, оригинальная и технически доступная страница. Индексация и позиции Google не гарантируются; одна статья на тему и намерение, а не на каждое видео. Публикация — только после одобрения редактором.",
            "Goal: a helpful, original, technically accessible page. Indexing and rankings are not guaranteed; one article per topic and intent, not per video. Publishing only after editor approval."))
    }

    // ---------------- context
    c.h2(t("Контекст", "Context"))
    fun field(hint: String, value: String, multiline: Boolean = false, set: (String) -> Unit) {
        c.add(text(this, hint, Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.edit(hint, value, multiline).addTextChangedListener(SimpleWatcher {
            set(it)
            save()
        })
    }
    field(t("Продукт / сайт о чём", "Product / what the site is about"), seo.product) { seo.product = it }
    field(t("Аудитория", "Audience"), seo.audience) { seo.audience = it }
    c.row(
        edt(this, t("Страна (KG, RU, US…)", "Country (US, GB…)"), seo.country).also { e ->
            e.addTextChangedListener(SimpleWatcher {
                seo.country = it.trim().uppercase()
                save()
            })
        },
        edt(this, t("Язык (ru, en…)", "Language (en, ru…)"), seo.language).also { e ->
            e.addTextChangedListener(SimpleWatcher {
                seo.language = it.trim().lowercase()
                save()
            })
        }
    )
    field(t("Адрес сайта", "Site URL"), seo.site) { seo.site = it }
    field(t("Существующие страницы сайта — по одной в строке «URL | заголовок» (для проверки дублей и каннибализации)",
        "Existing site pages — one per line “URL | title” (duplicate & cannibalisation check)"), seo.existingPages, multiline = true) { seo.existingPages = it }

    // ---------------- keyword research
    c.h2(t("Формулировки запросов (автодополнение)", "Query phrasings (autocomplete)"))
    c.muted(t("Подсказки показывают, как люди формулируют запросы, но НЕ дают частотности. Объём и сложность остаются «неизвестно», пока не подключён источник keyword-данных.",
        "Suggestions show how people phrase queries but give NO volumes. Volume and difficulty stay “unknown” until a keyword data source is connected."))
    c.check(t("Использовать также автодополнение Google (неофициальный endpoint, по желанию)", "Also use Google autocomplete (unofficial endpoint, optional)"), Settings.googleSuggest) {
        Settings.googleSuggest = it
    }
    val extended = ui.getOrPut("kwExt") { false } as Boolean
    c.check(t("Расширенно: вопросительные префиксы + алфавит (≈40 запросов на фразу)", "Extended: question prefixes + alphabet (≈40 requests per phrase)"), extended) {
        ui["kwExt"] = it
    }
    val clusters = Seo.clusters(project.id)
    c.button(t("Собрать формулировки", "Collect phrasings"), style = BtnStyle.TONAL) {
        val seeds = (listOf(project.phrase) + project.queries.take(3) + clusters.take(5).map { it.optString("name") })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
        cancel.set(false)
        busy = t("Подсказки…", "Suggestions…")
        render()
        Lumen.async({
            Seo.keywordResearch(project, seeds, ui["kwExt"] == true, cancel) { msg -> Lumen.ui { progressView?.text = msg } }
        }, {
            busy = ""
            render()
            err(it)
        }) { n ->
            busy = ""
            render()
            toast(t("Новых формулировок: $n", "New phrasings: $n"))
        }
    }
    val kws = Seo.keywords(project.id)
    if (kws.isNotEmpty()) {
        val bySrc = kws.groupingBy { it.optString("source") }.eachCount()
        c.muted(t("Формулировок: ${kws.size} · ", "Phrasings: ${kws.size} · ") + bySrc.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
        val qWords = Words.top(kws.asSequence().map { it.optString("q") }, 20)
        c.muted(t("Частые слова в запросах: ", "Frequent query words: ") + qWords.joinToString(", ") { it.first })
        c.card {
            add(text(ctx, kws.takeLast(40).reversed().joinToString("\n") { "• " + it.optString("q") }, 13f).apply { setTextIsSelectable(true) }, 0)
        }
        c.row(
            btn(this, t("Добавить в запросы проекта", "Add to project queries"), style = BtnStyle.OUTLINED) {
                val options = kws.map { it.optString("q") }.distinct()
                val checked = BooleanArray(options.size)
                android.app.AlertDialog.Builder(this).setTitle(t("Какие добавить?", "Which to add?"))
                    .setMultiChoiceItems(options.toTypedArray(), checked) { _, w, on -> checked[w] = on }
                    .setPositiveButton("OK") { _, _ ->
                        options.filterIndexed { i, _ -> checked[i] }.forEach { if (it !in project.queries) project.queries.add(it) }
                        save()
                        toast(t("Запросы обновлены", "Queries updated"))
                    }.setNegativeButton(t("Отмена", "Cancel"), null).show()
            },
            btn(this, t("Очистить", "Clear"), style = BtnStyle.TEXT) {
                confirm(t("Удалить формулировки?", "Delete phrasings?"), "") {
                    Seo.saveKeywords(project.id, emptyList())
                    render()
                }
            }
        )
    }

    // ---------------- topics pipeline
    c.h2(t("Темы → бриф → статья", "Topics → brief → article"))
    if (clusters.isEmpty()) {
        c.muted(t("Темы появятся после шага «Кластеры (P2)» на вкладке «Аудитория».", "Topics appear after “Clusters (P2)” on the Audience tab."))
    }
    clusters.sortedByDescending { it.optInt("mention_count") }.forEach { cl ->
        val q = Seo.pipeline(cl).optJSONObject("qualify")
        val card = c.card {
            add(text(ctx, cl.optString("name"), Type.TITLE_M, bold = true), 0)
            q?.optString("primary_question")?.takeIf { it.isNotBlank() }?.let { muted("❓ $it") }
            add(chipRow(ctx, listOfNotNull(
                chip(ctx, Seo.stage(cl), true),
                chip(ctx, "×" + cl.optInt("mention_count")),
                chip(ctx, cl.optString("intent_hypothesis").ifBlank { "intent ?" }),
                chip(ctx, when (q?.optJSONObject("keyword_evidence")?.optString("status")) {
                    "autocomplete_seen" -> t("запросы встречаются", "queries seen")
                    else -> t("спрос не проверен", "demand unverified")
                }),
                if (cl.has("possible_duplicate_of")) chip(ctx, t("возможный дубль", "possible duplicate")) else null
            )), 6)
        }
        card.setOnClickListener {
            startActivity(Intent(this, TopicActivity::class.java).putExtra("pid", project.id).putExtra("cid", cl.optString("id")))
        }
    }
    if (clusters.isNotEmpty()) {
        c.button(t("Идеи контента (P7)", "Content ideas (P7)"), style = BtnStyle.TONAL) {
            aiRun(t("Идеи контента", "Content ideas"), 3000 + clusters.size * 300) { Seo.ideas(project) }
        }
    }

    // ---------------- usage
    val usage = Seo.usage(project.id)
    if (usage.isNotEmpty()) {
        c.h2(t("Учёт AI", "AI usage"))
        val inTok = usage.sumOf { it.optInt("in") }
        val outTok = usage.sumOf { it.optInt("out") }
        val errors = usage.count { !it.isNull("error") }
        val cost = Llm.estimateCost(inTok, outTok)
        c.muted(t("Вызовов: ${usage.size} · токены in/out: $inTok / $outTok · ошибок: $errors", "Calls: ${usage.size} · tokens in/out: $inTok / $outTok · errors: $errors") +
            (if (cost > 0) String.format(" · ≈ $%.3f", cost) else ""))
        usage.groupBy { it.optString("task") }.forEach { (task, l) ->
            c.muted("$task: ${l.size} · ${l.sumOf { it.optInt("in") }}/${l.sumOf { it.optInt("out") }}")
        }
    }
}

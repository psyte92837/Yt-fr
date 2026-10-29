/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** One topic (cluster) through the pipeline: evidence → P3 → P4 → P5 → P6 → checklist → approval → metrics. */
class TopicActivity : LumenBase() {
    private lateinit var pid: String
    private lateinit var cid: String
    private var busy = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid") ?: return finish()
        cid = intent.getStringExtra("cid") ?: return finish()
        render()
    }

    private fun project() = Store.project(pid)!!

    private fun list(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun showList(c: Col, title: String, a: JSONArray?) {
        val l = list(a).filter { it.isNotBlank() }
        if (l.isEmpty()) return
        c.add(text(this, title, 13f, bold = true, color = Palette.MUTED), 8)
        c.add(text(this, l.joinToString("\n") { "• $it" }, 14f).apply { setTextIsSelectable(true) }, 2)
    }

    private fun run(label: String, est: Int, work: () -> Unit) {
        if (Provider.active() == null) return toast(t("Подключите AI в настройках", "Connect AI in settings"))
        val cost = Llm.estimateCost(est, est / 3)
        confirm(label, t("≈ $est входных токенов", "≈ $est input tokens") + (if (cost > 0) String.format(" · ≈ $%.4f", cost) else ""), t("Запустить", "Run")) {
            busy = "$label…"
            render()
            Lumen.async(work, {
                busy = ""
                render()
                err(it)
            }) {
                busy = ""
                render()
            }
        }
    }

    private fun metaLine(m: JSONObject?) = if (m == null) "" else
        "${m.optString("model")} · prompt ${m.optString("prompt_version")} · ${Fmt.dateTime(m.optLong("at"))} · ${m.optInt("in_tokens")}/${m.optInt("out_tokens")}"

    /** Link to the full raw AI answer of a pipeline step (also for cut-off or failed answers). */
    private fun answerLink(c: Col, task: String) {
        val e = AiJournal.list(pid).firstOrNull { it.task == task } ?: return
        c.add(btn(this, t("Ответ AI целиком · ", "Full AI answer · ") + AiJournal.statusLabel(e.status), style = BtnStyle.TEXT) {
            AiJournalActivity.open(this, id = e.id)
        }, 4)
    }

    private fun render() {
        val cl = Seo.cluster(pid, cid) ?: return finish()
        val p = project()
        val pl = Seo.pipeline(cl)
        val c = Col(this)
        if (busy.isNotEmpty()) c.card { working(busy) }

        // ---- topic & evidence
        c.add(text(this, cl.optString("name"), Type.TITLE_L), 0)
        c.p(cl.optString("summary"))
        c.chips(listOfNotNull(
            chip(this, Seo.stage(cl), true),
            chip(this, t("сообщений ", "messages ") + cl.optInt("mention_count")),
            if (cl.isNull("unique_author_count")) chip(this, t("авторов: не подсчитано", "authors: not counted")) else chip(this, t("авторов ", "authors ") + cl.optInt("unique_author_count")),
            chip(this, t("источников ", "sources ") + (cl.optJSONArray("source_ids")?.length() ?: 0)),
            chip(this, "intent: " + cl.optString("intent_hypothesis")),
            chip(this, t("уверенность ", "confidence ") + cl.optString("confidence"))
        ))
        if (cl.has("possible_duplicate_of")) c.p(t("⚠ Похоже на другую тему — возможно, стоит объединить.", "⚠ Looks similar to another topic — consider merging."), 13f, Palette.WARN)
        c.add(text(this, t("Потребность: ", "Need: ") + cl.optString("need"), 14f), 8)
        showList(c, t("Нерешённые вопросы", "Unresolved questions"), cl.optJSONArray("unresolved_questions"))
        c.h2(t("Свидетельства", "Evidence"))
        val ins = Seo.insights(pid).associateBy { it.optString("id") }
        list(cl.optJSONArray("insight_ids")).mapNotNull { ins[it] }.take(20).forEach { i ->
            val q = i.optJSONArray("quotes")?.optJSONObject(0)
            val card = c.card {
                add(text(ctx, "[${i.optString("type")}] " + i.optString("statement"), 14f, bold = true), 0)
                if (q != null && q.optString("text").isNotBlank()) {
                    add(text(ctx, "«" + q.optString("text").take(250) + "»", 13f), 4)
                    muted((if (q.optBoolean("verified")) "✓ " else "⚠ ") + i.optString("video_title").take(50))
                }
            }
            val url = q?.optString("url").orEmpty()
            if (url.isNotBlank()) card.setOnClickListener { openVideo(url) }
        }

        // ---- P3 qualification
        c.h2("1. " + t("SEO-квалификация (P3)", "SEO qualification (P3)"))
        val qf = pl.optJSONObject("qualify")
        if (qf != null) {
            c.card {
                add(text(ctx, "❓ " + qf.optString("primary_question"), 15f, bold = true), 0)
                muted("intent: ${qf.optString("search_intent")} · ${t("читатель", "reader")}: ${qf.optString("reader")}")
                val ke = qf.optJSONObject("keyword_evidence")
                p(t("Спрос: ", "Demand: ") + when (ke?.optString("status")) {
                    "autocomplete_seen" -> t("формулировки встречаются в автодополнении (${ke.optString("source")}, ${ke.optString("checked_at")}); объём неизвестен",
                        "phrasings seen in autocomplete (${ke.optString("source")}, ${ke.optString("checked_at")}); volume unknown")
                    else -> t("не проверен — гипотеза", "unverified — hypothesis")
                }, 13f, Palette.MUTED)
                p(t("Ценность: ", "Added value: ") + qf.optString("added_value"), 13f)
                showList(this, t("Нужная информация", "Needed info"), qf.optJSONArray("needed_info"))
                showList(this, t("Пересечения", "Overlaps"), qf.optJSONArray("overlaps"))
                showList(this, t("Пересечения с вашими страницами (локально)", "Overlaps with your pages (local)"), qf.optJSONArray("overlaps_local"))
                showList(this, t("Неизвестно", "Unknowns"), qf.optJSONArray("unknowns"))
                add(text(ctx, t("Решение: ", "Decision: ") + qf.optString("decision").uppercase() + " — " + qf.optString("decision_reason"), 14f, bold = true,
                    color = when (qf.optString("decision")) { "reject" -> Palette.BAD; "merge" -> Palette.WARN; else -> Palette.OK }), 8)
                muted(metaLine(pl.optJSONObject("qualify_meta")))
                answerLink(this, "p3_qualify")
            }
        }
        c.button(if (qf == null) t("Квалифицировать", "Qualify") else t("Пересчитать", "Re-run"), primary = qf == null) {
            run(t("Квалификация", "Qualification"), 4000) { Seo.qualify(project(), cid) }
        }

        // ---- P4 brief
        c.h2("2. " + t("SEO-бриф (P4)", "SEO brief (P4)"))
        val br = pl.optJSONObject("brief")
        if (br != null) {
            c.card {
                add(text(ctx, br.optString("primary_question"), 15f, bold = true), 0)
                p(t("Задача читателя: ", "Reader task: ") + br.optString("reader_task"), 13f)
                p(t("Отличие: ", "Differentiation: ") + br.optString("differentiation"), 13f)
                showList(this, t("Обязательные подтемы", "Required subtopics"), br.optJSONArray("required_subtopics"))
                showList(this, t("Примеры", "Examples"), br.optJSONArray("practical_examples"))
                showList(this, t("Проверить по источникам", "Sources to verify"), br.optJSONArray("sources_to_verify"))
                showList(this, t("Внутренние ссылки", "Internal links"), br.optJSONArray("internal_link_candidates"))
                showList(this, t("Вопросы без надёжного ответа", "Open questions"), br.optJSONArray("open_questions"))
                muted(metaLine(pl.optJSONObject("brief_meta")))
                answerLink(this, "p4_brief")
            }
        }
        c.button(if (br == null) t("Создать бриф", "Create brief") else t("Пересоздать бриф", "Re-create brief"), primary = qf != null && br == null) {
            if (qf?.optString("decision") == "reject") {
                confirm(t("Тема отклонена", "Topic rejected"), t("Квалификация рекомендует отклонить тему. Всё равно создать бриф?", "Qualification recommends rejecting. Create a brief anyway?")) {
                    run(t("Бриф", "Brief"), 5000) { Seo.brief(project(), cid) }
                }
            } else {
                run(t("Бриф", "Brief"), 5000) { Seo.brief(project(), cid) }
            }
        }

        // ---- P5 article
        c.h2("3. " + t("Черновик статьи (P5)", "Article draft (P5)"))
        val art = pl.optString("article")
        if (art.isNotBlank()) {
            val am = pl.optJSONObject("article_meta") ?: JSONObject()
            val marks = Regex("""\[ПРОВЕРИТЬ]|\[CHECK]""").findAll(art).count()
            c.card {
                add(text(ctx, am.optString("title").ifBlank { t("Без заголовка", "Untitled") }, 15f, bold = true), 0)
                muted("/" + am.optString("slug") + " · " + am.optString("meta_description").take(160))
                muted(t("Слов: ", "Words: ") + art.split(Regex("\\s+")).size + t(" · пометок [ПРОВЕРИТЬ]: ", " · [CHECK] marks: ") + marks)
                add(text(ctx, art.take(1200) + if (art.length > 1200) "…" else "", 13f), 8)
                showList(this, t("Вопросы редактору", "Questions for the editor"), am.optJSONArray("editor_questions"))
                muted(metaLine(pl.optJSONObject("article_ai")))
                answerLink(this, "p5_article")
            }
            c.row(
                btn(this, t("Читать", "Read"), style = BtnStyle.TONAL) { showText(am.optString("title"), art) },
                btn(this, t("Править", "Edit"), style = BtnStyle.OUTLINED) {
                    prompt(t("Статья (Markdown)", "Article (Markdown)"), "", art, multiline = true) { v ->
                        Seo.updateCluster(pid, cid) { Seo.pipeline(it).put("article", v).put("editor_approval", false) }
                        render()
                    }
                }
            )
        }
        c.button(if (art.isBlank()) t("Написать черновик", "Write draft") else t("Переписать черновик", "Re-write draft"), primary = br != null && art.isBlank()) {
            if (br == null) return@button toast(t("Сначала создайте бриф", "Create the brief first"))
            run(t("Статья", "Article"), 6000) { Seo.article(project(), cid) }
        }

        // ---- P6 QA
        if (art.isNotBlank()) {
            c.h2("4. " + t("Контроль качества (P6)", "Quality check (P6)"))
            val qa = pl.optJSONObject("qa")
            if (qa != null) {
                c.card {
                    val v = qa.optString("verdict")
                    add(text(ctx, t("Итог: ", "Verdict: ") + v.uppercase(), 15f, bold = true,
                        color = when (v) { "pass" -> Palette.OK; "reject" -> Palette.BAD; else -> Palette.WARN }), 0)
                    if (qa.optString("summary").isNotBlank()) p(qa.optString("summary"), 13f)
                    val issues = qa.optJSONArray("issues") ?: JSONArray()
                    for (i in 0 until issues.length()) {
                        val it = issues.optJSONObject(i) ?: continue
                        add(text(ctx, "[${it.optString("severity")}] ${it.optString("place")}: ${it.optString("problem")}\n→ ${it.optString("fix")}", 13f,
                            color = if (it.optString("severity") == "critical") Palette.BAD else Palette.ON_SURFACE), 6)
                    }
                    muted(metaLine(pl.optJSONObject("qa_meta")))
                answerLink(this, "p6_quality")
                }
            }
            c.button(if (qa == null) t("Проверить", "Check") else t("Проверить снова", "Check again"), primary = qa == null) {
                run(t("Проверка качества", "Quality check"), Llm.estimateTokens(art) + 3000) { Seo.quality(project(), cid) }
            }

            // ---- checklist
            c.h2("5. " + t("Чек-лист SEO-статьи", "SEO article checklist"))
            val cl2 = pl.optJSONObject("checklist") ?: JSONObject()
            val done = SeoChecklist.sections.sumOf { (_, items) -> items.count { cl2.optString(it).isNotEmpty() } }
            val fails = SeoChecklist.sections.sumOf { (_, items) -> items.count { cl2.optString(it) == "fail" } }
            c.muted(t("Отмечено $done из ${SeoChecklist.total} · fail: $fails · нажмите пункт, чтобы сменить статус", "Marked $done of ${SeoChecklist.total} · fail: $fails · tap to change status"))
            for ((section, items) in SeoChecklist.sections) {
                c.add(text(this, section, 13f, bold = true, color = Palette.MUTED), 12)
                items.forEach { item ->
                    val st = cl2.optString(item)
                    val mark = when (st) { "pass" -> "✅"; "revise" -> "✏️"; "fail" -> "❌"; "not_applicable" -> "➖"; else -> "⬜" }
                    c.add(text(this, "$mark  $item", 14f).apply {
                        minHeight = dp(40)
                        setOnClickListener {
                            val next = SeoChecklist.states[(SeoChecklist.states.indexOf(st) + 1) % SeoChecklist.states.size]
                            Seo.updateCluster(pid, cid) { c3 ->
                                val o = Seo.pipeline(c3).optJSONObject("checklist") ?: JSONObject().also { Seo.pipeline(c3).put("checklist", it) }
                                if (next.isEmpty()) o.remove(item) else o.put(item, next)
                            }
                            render()
                        }
                    }, 0)
                }
            }

            // ---- approval & publishing
            c.h2("6. " + t("Одобрение редактором", "Editor approval"))
            val approved = pl.optBoolean("editor_approval")
            c.check(t("Статья проверена человеком и одобрена к публикации", "Reviewed by a human and approved for publishing"), approved) { on ->
                if (on) {
                    val warn = mutableListOf<String>()
                    if (fails > 0) warn += t("в чек-листе есть «fail»: $fails", "checklist has “fail”: $fails")
                    if (pl.optJSONObject("qa")?.optString("verdict") == "reject") warn += t("проверка качества: reject", "quality check: reject")
                    if (Regex("""\[ПРОВЕРИТЬ]|\[CHECK]""").containsMatchIn(art)) warn += t("остались пометки [ПРОВЕРИТЬ]", "[CHECK] marks remain")
                    if (warn.isNotEmpty()) {
                        AlertDialog.Builder(this).setTitle(t("Одобрить несмотря на это?", "Approve anyway?")).setMessage(warn.joinToString("\n"))
                            .setPositiveButton(t("Одобрить", "Approve")) { _, _ -> setApproval(true) }
                            .setNegativeButton(t("Отмена", "Cancel")) { _, _ -> render() }.show()
                        return@check
                    }
                }
                setApproval(on)
            }
            c.row(
                btn(this, t("Сохранить .md", "Save .md"), style = BtnStyle.TONAL) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/markdown")
                            .putExtra(Intent.EXTRA_TITLE, (pl.optJSONObject("article_meta")?.optString("slug")?.ifBlank { null } ?: "article") + ".md"), REQ_MD
                    )
                },
                btn(this, t("Поделиться", "Share"), style = BtnStyle.OUTLINED) { shareText(cl.optString("name"), exportMarkdown(cl)) }
            )
            if (!approved) c.muted(t("Экспорт черновика возможен, но он помечается как DRAFT — не одобрено.", "Draft export is allowed but marked DRAFT — not approved."))

            // ---- publishing & metrics (manual)
            c.h2("7. " + t("Публикация и результаты", "Publishing & results"))
            c.muted(t("Lumen ничего не публикует сам. Внесите URL после публикации и данные из Google Search Console вручную.",
                "Lumen never publishes by itself. Enter the URL after publishing and Search Console numbers manually."))
            val metrics = pl.optJSONObject("metrics") ?: JSONObject()
            val url = c.edit(t("Опубликованный URL", "Published URL"), pl.optString("published_url"))
            val period = c.edit(t("Период (напр. 2026-11-01…2026-11-30)", "Period (e.g. 2026-11-01…2026-11-30)"), metrics.optString("period"))
            val fields = listOf("impressions" to t("Показы", "Impressions"), "clicks" to t("Клики", "Clicks"),
                "average_position" to t("Ср. позиция", "Avg position"), "conversions" to t("Конверсии", "Conversions"))
            val edits = fields.map { (k, l) -> k to edt(this, l, if (metrics.isNull(k) || !metrics.has(k)) "" else metrics.opt(k).toString()) }
            c.row(edits[0].second, edits[1].second)
            c.row(edits[2].second, edits[3].second)
            c.button(t("Сохранить", "Save"), style = BtnStyle.TONAL) {
                Seo.updateCluster(pid, cid) { c3 ->
                    val o = JSONObject().put("source", "google_search_console_manual").put("period", period.text.toString().trim())
                    edits.forEach { (k, e) ->
                        val v = e.text.toString().replace(',', '.').trim().toDoubleOrNull()
                        o.put(k, v ?: JSONObject.NULL)
                    }
                    val clicks = o.optDouble("clicks", Double.NaN)
                    val imp = o.optDouble("impressions", Double.NaN)
                    o.put("ctr", if (!clicks.isNaN() && !imp.isNaN() && imp > 0) clicks / imp else JSONObject.NULL)
                    Seo.pipeline(c3).put("published_url", url.text.toString().trim()).put("metrics", o)
                }
                toast(t("Сохранено", "Saved"))
                render()
            }
            if (!metrics.isNull("ctr") && metrics.has("ctr")) c.muted("CTR: " + String.format("%.2f%%", metrics.optDouble("ctr") * 100))
        }
        page(t("Тема", "Topic"), c.root, actions = listOf("🧾" to { AiJournalActivity.open(this, pid) }))
    }

    private fun setApproval(on: Boolean) {
        Seo.updateCluster(pid, cid) {
            Seo.pipeline(it).put("editor_approval", on).put("status", if (on) "approved" else "draft")
                .put("approved_at", if (on) System.currentTimeMillis() else JSONObject.NULL)
        }
        render()
    }

    private fun exportMarkdown(cl: JSONObject): String {
        val pl = Seo.pipeline(cl)
        val m = pl.optJSONObject("article_meta") ?: JSONObject()
        val approved = pl.optBoolean("editor_approval")
        return buildString {
            append("---\n")
            append("title: \"").append(m.optString("title").replace("\"", "'")).append("\"\n")
            append("description: \"").append(m.optString("meta_description").replace("\"", "'")).append("\"\n")
            append("slug: ").append(m.optString("slug")).append('\n')
            append("status: ").append(if (approved) "approved" else "DRAFT").append('\n')
            append("topic_cluster_id: ").append(cl.optString("id")).append('\n')
            append("keyword_evidence: ").append(pl.optJSONObject("brief")?.optJSONObject("keyword_evidence")?.optString("status") ?: "unverified").append('\n')
            append("---\n\n")
            append(pl.optString("article"))
            val src = m.optJSONArray("sources")
            if (src != null && src.length() > 0) {
                append("\n\n## Источники / Sources\n\n")
                for (i in 0 until src.length()) append("- ").append(src.optString(i)).append('\n')
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_MD || resultCode != RESULT_OK) return
        val cl = Seo.cluster(pid, cid) ?: return
        Lumen.async({ contentResolver.openOutputStream(uri)?.use { it.write(exportMarkdown(cl).toByteArray()) } }, ::err) {
            toast(t("Сохранено", "Saved"))
        }
    }

    companion object {
        const val REQ_MD = 61
    }
}

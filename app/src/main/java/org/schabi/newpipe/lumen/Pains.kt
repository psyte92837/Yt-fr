/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * "Pains and cases of people" report from the comments of a shared video or channel.
 * Local part (no AI): [Voc] signals plus first-person stories. AI part: grouped pains and real cases,
 * every quote is checked against the comment it cites. Pure functions are covered by PainsTest.
 */
object Pains {
    const val KIND = "pains"

    /** A comment with a short id ("c12") that the AI cites. */
    data class Msg(val id: String, val videoId: String, val text: String, val likes: Int, val author: String)

    data class Quote(val id: String, val text: String)
    data class Pain(val title: String, val detail: String, val severity: Int, val ids: List<String>, val quotes: List<Quote>)
    data class Case(val who: String, val situation: String, val tried: String, val outcome: String, val need: String, val quote: Quote?)
    data class Report(
        val summary: String,
        val pains: List<Pain>,
        val cases: List<Case>,
        val questions: List<String>,
        val objections: List<String>,
        val ideas: List<String>,
        val dropped: Int // quotes not found in the cited comment
    )

    /** Local counts without AI. */
    data class Local(val total: Int, val counts: Map<String, Int>, val top: Map<String, List<Voc.Item>>, val cases: List<Msg>)

    private val caseMarkers = listOf(
        "у меня ", "у меня.", "у меня,", "у нас ", "я ", "мне ", "мой ", "моя ", "моё ", "мое ", "мои ", "моего ", "моей ",
        "когда я", "в моем случае", "лично я", "сам ", "сама ", "со мной", "по опыту", "из опыта", "был случай", "была ситуация",
        "i ", "i'm", "i've", "i had", "my ", "me ", "in my case", "happened to me", "personally"
    )
    private val storyVerbs = listOf(
        "был", "была", "было", "были", "пробовал", "пробовала", "купил", "купила", "сделал", "сделала", "пошел", "пошла",
        "обратил", "обратилась", "потратил", "потратила", "лечил", "лечила", "работал", "работала", "столкнул", "случил",
        "получил", "получила", "пришлось", "решил", "решила", "начал", "начала", "перестал", "перестала", "уже ", "год", "месяц",
        "tried", "bought", "had ", "was ", "went", "spent", "started", "stopped", "years", "months", "ended up"
    )

    /** First-person story ("I tried…, it didn't help"): a case, not just an opinion. */
    fun isCase(text: String): Boolean {
        val s = " " + text.lowercase().replace('ё', 'е').replace('\n', ' ') + " "
        if (s.length < 60 || Voc.isSpam(text)) return false
        val first = caseMarkers.count { s.contains(" $it") || s.contains(" " + it.trim() + ",") }
        val story = storyVerbs.count { s.contains(it) }
        return first >= 1 && story >= 2
    }

    /** Numbers comments as c1, c2… (the order is stable: by video, then as collected). */
    fun messages(byVideo: List<Pair<String, List<Comment>>>): List<Msg> {
        val out = ArrayList<Msg>()
        for ((vid, list) in byVideo) {
            for (c in list) {
                if (c.text.isBlank()) continue
                out += Msg("c" + (out.size + 1), vid, c.text.trim(), c.likes, c.author)
            }
        }
        return out
    }

    fun local(msgs: List<Msg>, perCategory: Int = 5): Local {
        val items = Voc.extract(msgs.map { Voc.Msg(it.videoId, it.text, it.likes, it.author, it.id.drop(1).toIntOrNull() ?: -1, null) })
        val by = items.groupBy { it.category }
        val counts = Voc.categories.associateWith { c -> by[c].orEmpty().sumOf { it.count } }
        val top = Voc.categories.associateWith { c -> by[c].orEmpty().sortedWith(compareByDescending<Voc.Item> { it.count }.thenByDescending { it.likes }).take(perCategory) }
        val cases = msgs.filter { isCase(it.text) }.sortedByDescending { it.likes }
        return Local(msgs.size, counts + (CASE to cases.size), top, cases)
    }

    const val CASE = "case"

    /**
     * Text for the model: signals (pains, questions, objections, requests, stories) first, then the most liked rest.
     * Each line is "[c12 ♥34] text", trimmed to [lineMax]; the whole block stays within [limit] characters.
     */
    fun aiInput(msgs: List<Msg>, limit: Int, lineMax: Int = 500): String {
        fun signal(m: Msg) = Voc.classify(m.text).isNotEmpty() || isCase(m.text)
        val clean = msgs.filter { !Voc.isSpam(it.text) }
        val seen = HashSet<String>()
        val ordered = (clean.filter { signal(it) }.sortedByDescending { it.likes } + clean.filter { !signal(it) }.sortedByDescending { it.likes })
            .filter { seen.add(Voc.normalize(it.text)) }
        val sb = StringBuilder()
        for (m in ordered) {
            val line = "[" + m.id + " ♥" + m.likes + "] " + m.text.replace('\n', ' ').take(lineMax) + "\n"
            if (sb.length + line.length > limit) break
            sb.append(line)
        }
        return sb.toString()
    }

    /** Parses the AI reply; broken elements are skipped, quotes not found in the cited comment are dropped. Null when nothing usable. */
    fun parse(reply: String, msgs: List<Msg>): Report? {
        val byId = msgs.associateBy { it.id }
        val obj = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val painsArr = obj?.optJSONArray("pains") ?: salvage(reply, "pains")
        val casesArr = obj?.optJSONArray("cases") ?: salvage(reply, "cases")
        if (painsArr == null && casesArr == null) return null
        var dropped = 0
        fun ids(o: JSONObject): List<String> {
            val a = o.optJSONArray("ids") ?: return emptyList()
            return (0 until a.length()).map { a.optString(it).trim() }.filter { it in byId }.distinct()
        }
        fun quote(id: String, text: String): Quote? {
            if (text.isBlank()) return null
            val src = byId[id.trim()]
            if (src != null && Voc.quoteFound(text, src.text)) return Quote(src.id, text.trim())
            // the model sometimes cites a neighbour: look for the quote anywhere
            msgs.firstOrNull { Voc.quoteFound(text, it.text) }?.let { return Quote(it.id, text.trim()) }
            dropped++
            return null
        }
        val pains = objects(painsArr).mapNotNull { o ->
            val title = o.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            val qa = o.optJSONArray("quotes")
            val quotes = (0 until (qa?.length() ?: 0)).mapNotNull { i ->
                val q = qa!!.optJSONObject(i) ?: return@mapNotNull null
                quote(q.optString("id"), q.optString("text"))
            }
            val ids = (ids(o) + quotes.map { it.id }).distinct()
            Pain(title, o.optString("detail").trim(), o.optInt("severity", 0).coerceIn(0, 5), ids, quotes)
        }.sortedWith(compareByDescending<Pain> { it.ids.size }.thenByDescending { it.severity })
        val cases = objects(casesArr).mapNotNull { o ->
            val q = o.optJSONObject("quote")?.let { quote(it.optString("id"), it.optString("text")) }
            val situation = o.optString("situation").trim()
            if (situation.isEmpty() && q == null) return@mapNotNull null
            Case(o.optString("who").trim(), situation, o.optString("tried").trim(), o.optString("outcome").trim(), o.optString("need").trim(), q)
        }
        return Report(
            obj?.optString("summary")?.trim().orEmpty(), pains, cases,
            strings(obj?.optJSONArray("questions")), strings(obj?.optJSONArray("objections")), strings(obj?.optJSONArray("ideas")), dropped
        )
    }

    private fun salvage(text: String, key: String): JSONArray? =
        JsonSalvage.array(text, key)?.let { runCatching { JSONObject(it).optJSONArray(key) }.getOrNull() }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun short(s: String, n: Int = 220) = s.replace('\n', ' ').let { if (it.length > n) it.take(n).trimEnd() + "…" else it }

    fun categoryLabel(c: String) = when (c) {
        Voc.PAIN -> t("Боли", "Pains")
        Voc.QUESTION -> t("Вопросы", "Questions")
        Voc.REQUEST -> t("Запросы", "Requests")
        Voc.OBJECTION -> t("Возражения", "Objections")
        CASE -> t("Личные истории", "Personal stories")
        else -> c
    }

    /** Plain-text report for sharing and reading. [r] is null when there is no AI part. */
    fun markdown(title: String, url: String, local: Local, r: Report?, model: String = ""): String = buildString {
        append("# ").append(t("Боли и кейсы людей", "Pains and cases of people")).append('\n')
        append(title).append('\n')
        if (url.isNotBlank()) append(url).append('\n')
        append(t("Комментариев: ", "Comments: ")).append(local.total)
        listOf(Voc.PAIN, CASE, Voc.QUESTION, Voc.OBJECTION, Voc.REQUEST).forEach { c ->
            append(" · ").append(categoryLabel(c).lowercase()).append(' ').append(local.counts[c] ?: 0)
        }
        append("\n\n")
        if (r != null) {
            if (r.summary.isNotBlank()) append("## ").append(t("Главное", "Key points")).append('\n').append(r.summary).append("\n\n")
            if (r.pains.isNotEmpty()) {
                append("## ").append(t("Боли", "Pains")).append('\n')
                r.pains.forEachIndexed { i, p ->
                    append(i + 1).append(". **").append(p.title).append("**")
                    if (p.ids.isNotEmpty()) append(t(" — упоминаний: ", " — mentions: ")).append(p.ids.size)
                    if (p.severity > 0) append(t(", острота ", ", severity ")).append(p.severity).append("/5")
                    append('\n')
                    if (p.detail.isNotBlank()) append("   ").append(p.detail).append('\n')
                    p.quotes.take(3).forEach { q -> append("   > «").append(short(q.text)).append("» [").append(q.id).append("]\n") }
                }
                append('\n')
            }
            if (r.cases.isNotEmpty()) {
                append("## ").append(t("Кейсы людей", "Cases of people")).append('\n')
                r.cases.forEachIndexed { i, c ->
                    append(i + 1).append(". ")
                    if (c.who.isNotBlank()) append("**").append(c.who).append("** — ")
                    append(c.situation).append('\n')
                    if (c.tried.isNotBlank()) append(t("   Пробовали: ", "   Tried: ")).append(c.tried).append('\n')
                    if (c.outcome.isNotBlank()) append(t("   Итог: ", "   Outcome: ")).append(c.outcome).append('\n')
                    if (c.need.isNotBlank()) append(t("   Что нужно: ", "   Needs: ")).append(c.need).append('\n')
                    c.quote?.let { q -> append("   > «").append(short(q.text)).append("» [").append(q.id).append("]\n") }
                }
                append('\n')
            }
            listOf(t("Частые вопросы", "Frequent questions") to r.questions, t("Возражения и недоверие", "Objections and distrust") to r.objections,
                t("Что можно сделать (идеи)", "What to do (ideas)") to r.ideas).forEach { (h, list) ->
                if (list.isNotEmpty()) {
                    append("## ").append(h).append('\n')
                    list.forEach { append("• ").append(it).append('\n') }
                    append('\n')
                }
            }
        } else {
            append(t("AI-разбор не выполнен — ниже локальные сигналы по ключевым словам.\n\n", "No AI analysis — local keyword signals below.\n\n"))
        }
        append("## ").append(t("Сигналы из комментариев (без AI)", "Signals from comments (no AI)")).append('\n')
        listOf(Voc.PAIN, Voc.QUESTION, Voc.OBJECTION, Voc.REQUEST).forEach { c ->
            val items = local.top[c].orEmpty()
            if (items.isEmpty()) return@forEach
            append("### ").append(categoryLabel(c)).append(" (").append(local.counts[c] ?: 0).append(")\n")
            items.forEach { append("• ").append(short(it.text, 200)).append(if (it.count > 1) " ×${it.count}" else "").append(" ♥").append(it.likes).append('\n') }
        }
        if (local.cases.isNotEmpty()) {
            append("### ").append(categoryLabel(CASE)).append(" (").append(local.cases.size).append(")\n")
            local.cases.take(5).forEach { append("• ").append(short(it.text, 260)).append(" ♥").append(it.likes).append('\n') }
        }
        append('\n')
        if (r != null && r.dropped > 0) append(t("Отброшено цитат, которых нет в комментариях: ", "Quotes dropped (not found in comments): ")).append(r.dropped).append('\n')
        if (model.isNotBlank()) append("AI: ").append(model).append('\n')
        append(t("Сигналы — это гипотезы по комментариям, а не измерение спроса. Авторы скрыты.", "Signals are hypotheses from comments, not a demand measurement. Authors are hidden."))
        append('\n')
    }

    // ---- Android side: data of a project, AI call, PDF ----

    fun projectMessages(pid: String): List<Msg> {
        val vids = Store.videos(pid).values.sortedByDescending { it.views }
        return messages(vids.map { it.id to Store.comments(pid, it.id) })
    }

    fun latest(pid: String): AiResult? = Store.latestAi(pid, KIND, "project")

    /** AI step of the instant analysis. Retries once when the reply has no usable JSON. */
    fun ai(p: Project, force: Boolean = false): AiResult {
        val prov = AiTasks.requireProvider()
        val msgs = projectMessages(p.id)
        if (msgs.isEmpty()) throw LlmException(t("Нет собранных комментариев.", "No collected comments."))
        val title = Store.videos(p.id).values.maxByOrNull { it.views }?.let { it.title + " — " + it.channel } ?: p.name
        val data = "Source: " + title + "\n" + aiInput(msgs, Settings.chunkChars * 3)
        val prompt = Prompts.get(KIND).replace("{lang}", if (Settings.lang == "en") "English" else "русский").replace("{data}", data)
        val key = sha1("pains|${prov.smartModel}|${prompt.hashCode()}")
        if (!force) Store.ai(p.id, key)?.let { return it }
        var last: LlmReply? = null
        for (attempt in 0..1) {
            val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, json = true, maxTokens = if (attempt == 0) 8000 else 16000, task = KIND, pid = p.id)
            last = r
            if (parse(r.text, msgs) != null) break
            L.w(KIND, "unusable JSON, attempt ${attempt + 1}")
        }
        val r = last!!
        return AiResult(key, KIND, "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    fun text(pid: String): String {
        val p = Store.project(pid)
        val msgs = projectMessages(pid)
        val ai = latest(pid)
        val v = Store.videos(pid).values.maxByOrNull { it.views }
        return markdown(v?.title ?: p?.name.orEmpty(), p?.source.orEmpty(), local(msgs), ai?.let { parse(it.text, msgs) }, ai?.model.orEmpty())
    }

    fun write(pid: String, pdf: Boolean, out: OutputStream) {
        val p = Store.project(pid)
        val msgs = projectMessages(pid)
        val loc = local(msgs)
        val ai = latest(pid)
        val r = ai?.let { parse(it.text, msgs) }
        val v = Store.videos(pid).values.maxByOrNull { it.views }
        val b = mutableListOf<ReportKit.Block>()
        b += ReportKit.Hero(t("Голос аудитории", "Voice of the audience"), t("Боли и кейсы людей", "Pains and cases of people"),
            v?.title ?: p?.name.orEmpty(), t("комментариев: ", "comments: ") + loc.total + (ai?.let { " · " + it.model } ?: ""))
        b += ReportKit.Kpis(listOf(
            Triple("${loc.counts[Voc.PAIN] ?: 0}", t("боли", "pains"), ReportKit.BAD),
            Triple("${loc.counts[CASE] ?: 0}", t("личные истории", "stories"), ReportKit.ACCENT),
            Triple("${loc.counts[Voc.QUESTION] ?: 0}", t("вопросы", "questions"), ReportKit.OK),
            Triple("${loc.counts[Voc.OBJECTION] ?: 0}", t("возражения", "objections"), ReportKit.WARN)
        ))
        if (r != null) {
            if (r.summary.isNotBlank()) {
                b += ReportKit.Section(t("Главное", "Key points"))
                b += ReportKit.Paragraph(r.summary, 13.5f)
            }
            if (r.pains.isNotEmpty()) {
                b += ReportKit.Section(t("Боли", "Pains"), t("По числу комментариев, где боль упомянута.", "By the number of comments mentioning it."))
                r.pains.forEach { pn ->
                    val sb = StringBuilder("**").append(pn.title).append("**")
                    if (pn.ids.isNotEmpty()) sb.append(" · ").append(pn.ids.size).append(t(" упом.", " mentions"))
                    if (pn.severity > 0) sb.append(" · ").append("●".repeat(pn.severity))
                    if (pn.detail.isNotBlank()) sb.append('\n').append(pn.detail)
                    pn.quotes.take(2).forEach { sb.append("\n«").append(short(it.text, 180)).append("»") }
                    b += ReportKit.Insight("😣", sb.toString(), 0xFFFFDAD6.toInt())
                }
            }
            if (r.cases.isNotEmpty()) {
                b += ReportKit.Section(t("Кейсы людей", "Cases of people"), t("Реальные истории из комментариев.", "Real stories from the comments."))
                r.cases.forEach { c ->
                    val sb = StringBuilder()
                    if (c.who.isNotBlank()) sb.append("**").append(c.who).append("**\n")
                    sb.append(c.situation)
                    if (c.tried.isNotBlank()) sb.append('\n').append(t("Пробовали: ", "Tried: ")).append(c.tried)
                    if (c.outcome.isNotBlank()) sb.append('\n').append(t("Итог: ", "Outcome: ")).append(c.outcome)
                    if (c.need.isNotBlank()) sb.append('\n').append(t("Нужно: ", "Needs: ")).append(c.need)
                    c.quote?.let { sb.append("\n«").append(short(it.text, 180)).append("»") }
                    b += ReportKit.Insight("👤", sb.toString())
                }
            }
            if (r.questions.isNotEmpty()) b += ReportKit.Insight("❓", t("**Частые вопросы:**\n", "**Frequent questions:**\n") + r.questions.joinToString("\n") { "• $it" })
            if (r.objections.isNotEmpty()) b += ReportKit.Insight("🛑", t("**Возражения:**\n", "**Objections:**\n") + r.objections.joinToString("\n") { "• $it" })
            if (r.ideas.isNotEmpty()) b += ReportKit.Insight("💡", t("**Что можно сделать:**\n", "**What to do:**\n") + r.ideas.joinToString("\n") { "• $it" }, 0xFFFFE0B2.toInt())
        } else {
            b += ReportKit.Section(t("Сигналы без AI", "Signals without AI"), t("Поиск по ключевым словам.", "Keyword search."))
            listOf(Voc.PAIN, Voc.QUESTION, Voc.OBJECTION).forEach { c ->
                val items = loc.top[c].orEmpty()
                if (items.isNotEmpty()) b += ReportKit.Paragraph("**" + categoryLabel(c) + "**\n" + items.joinToString("\n") { "• " + short(it.text, 160) + " ♥" + it.likes }, 12.5f)
            }
            if (loc.cases.isNotEmpty()) b += ReportKit.Paragraph("**" + categoryLabel(CASE) + "**\n" + loc.cases.take(4).joinToString("\n") { "• " + short(it.text, 200) }, 12.5f)
        }
        b += ReportKit.Footer(t("Сигналы — гипотезы по комментариям, а не измерение спроса. Цитаты проверены по исходным комментариям.",
            "Signals are hypotheses from comments, not demand. Quotes are checked against the source comments."))
        ReportKit.write(b, pdf, out, "Lumen · " + t("боли и кейсы", "pains & cases"))
    }
}

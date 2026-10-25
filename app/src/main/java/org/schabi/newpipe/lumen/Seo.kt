/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audience-to-SEO pipeline (see doc/lumen/seo/):
 * transcripts + comments → Voice of Customer insights with quotes → topic clusters →
 * keyword evidence (autocomplete) → qualification → SEO brief → Markdown draft → QA → checklist → editor approval.
 * Rules: never invent quotes, volumes or sources; unverified demand is a hypothesis; nothing is published automatically.
 */
val SEO_PROMPTS = linkedMapOf(
    "p1_insights" to (
        "Исследуй транскрипт и комментарии.\n" +
            "Извлеки вопросы, pain, JTBD, дословные VOC-цитаты, возражения и неудовлетворённые потребности.\n" +
            "Различай автора видео (speaker_type=author, цитата из транскрипта) и комментаторов (speaker_type=commenter).\n" +
            "Не выводи поисковую частотность. Цитата должна быть ДОСЛОВНОЙ копией фрагмента исходного текста.\n" +
            "Для каждого элемента: type (question|pain|jtbd|voc|objection|unmet_need|content_gap), statement (кратко), quote, " +
            "speaker_type, comment_id (например c12) или null, timestamp (mm:ss из транскрипта) или null, " +
            "confidence (high|medium|low), interpretation, hypothesis. JTBD формулируй: «Когда [ситуация], я хочу [действие], чтобы [результат]».\n" +
            "Объединяй точные дубли, не смешивай разные задачи. Язык формулировок: {lang}.\n" +
            "Цитаты — до 200 символов, statement — одно предложение. Верни компактный JSON {\"items\":[...]} без пробелов-отступов. Максимум 15 элементов.\n\n" +
            "PROJECT: {project_context}\nTRANSCRIPT:\n{transcript}\nCOMMENTS:\n{comments}"
        ),
    "p2_clusters" to (
        "Объедини инсайты по смыслу и поисковой задаче. Не смешивай информационные и транзакционные намерения, " +
            "разные аудитории и сценарии с разными решениями.\n" +
            "Покажи устойчивые темы, различия между сегментами и противоречия.\n" +
            "Для каждого кластера: name, need, summary, insight_ids (из переданных id), intent_hypothesis " +
            "(informational|commercial|transactional|navigational), confidence (high|medium|low), unresolved_questions, segments_or_contradictions.\n" +
            "Не считай повторные сообщения одного человека независимыми свидетельствами. Язык: {lang}.\n" +
            "Верни JSON {\"clusters\":[...]}.\n\nINSIGHTS:\n{insights}"
        ),
    "p3_qualify" to (
        "Оцени тему как гипотезу для статьи.\n" +
            "Укажи primary_question, search_intent, reader, needed_info (список), added_value, overlaps (пересечения с другими темами/страницами), unknowns.\n" +
            "Используй переданные keyword/SERP данные с источником и датой. Если их нет — пометь спрос и конкуренцию как непроверенные. " +
            "Подсказки автодополнения подтверждают только формулировки, но не объём спроса.\n" +
            "decision: investigate | merge | reject, decision_reason. Язык: {lang}. Верни JSON.\n\n" +
            "TOPIC: {topic}\nEVIDENCE:\n{evidence}\nEXISTING_PAGES:\n{existing_pages}\nKEYWORD_DATA:\n{keyword_data}\nSERP_DATA: {serp_data}"
        ),
    "p4_brief" to (
        "Создай SEO-бриф: reader_task, primary_question, search_intent, required_subtopics, practical_examples, " +
            "sources_to_verify, differentiation (уникальная ценность), internal_link_candidates, open_questions (вопросы без надёжного ответа).\n" +
            "Не выдумывай объём поиска и плотность ключевых слов. Язык: {lang}. Верни JSON.\n\n" +
            "TOPIC: {topic}\nRESEARCH:\n{research}\nSERP_NOTES: {serp_notes}\nSITE_CONTEXT:\n{site_context}"
        ),
    "p5_article" to (
        "Напиши оригинальную статью на языке {lang} по брифу.\n" +
            "Сразу ответь на главный вопрос. Используй понятные H1/H2/H3, инструкции и примеры, когда полезны.\n" +
            "Не выдумывай тесты, цифры, цитаты, характеристики и источники. Неподтверждённые важные факты пометь [ПРОВЕРИТЬ].\n" +
            "Не копируй конкурентов и не растягивай текст ради длины.\n" +
            "Формат ответа: сначала Markdown статьи, затем отдельная строка ---META--- и JSON " +
            "{\"title\",\"meta_description\",\"slug\",\"sources\":[],\"editor_questions\":[]}.\n\n" +
            "BRIEF:\n{brief}\nVERIFIED_SOURCES:\n{sources}\nPRODUCT_CONTEXT:\n{product_context}"
        ),
    "p6_quality" to (
        "Проверь статью: полноту ответа, точность и источники, самостоятельную ценность, intent, дублирование, " +
            "неподтверждённые утверждения, искусственные повторы ключевых слов, лишние абзацы, необходимость экспертной проверки.\n" +
            "Не переписывай статью. Верни JSON {\"issues\":[{\"place\",\"severity\":\"critical|major|minor\",\"problem\",\"fix\"}]," +
            "\"verdict\":\"pass|revise|reject\",\"summary\"}. Язык: {lang}.\n\n" +
            "ARTICLE:\n{article}\nBRIEF:\n{brief}\nSOURCES:\n{sources}\nEXISTING_PAGES:\n{existing_pages}"
        ),
    "p7_ideas" to (
        "На основе подтверждённых кластеров предложи до 5 идей контента.\n" +
            "Для каждой: задача читателя, формат, заголовок, свидетельство из переданных данных, отличительная ценность и следующий шаг.\n" +
            "Не выдавай гипотезу за подтверждённый запрос. Не создавай дубликаты. Ответ в Markdown на языке {lang}.\n\n" +
            "CLUSTERS:\n{clusters}\nPRODUCT:\n{product}"
        )
)

const val SEO_SYSTEM = "You are a careful audience researcher and SEO editor. Use only the provided data. " +
    "Never invent quotes, statistics, search volumes, sources or product features. Separate observation, interpretation and hypothesis."

/** SEO quality checklist (06_SEO_QUALITY_CHECKLIST). */
object SeoChecklist {
    val sections: List<Pair<String, List<String>>> = listOf(
        "Намерение и читатель / Intent" to listOf(
            "Главный вопрос сформулирован конкретно и получает ответ в начале",
            "Тип материала соответствует поисковому намерению",
            "Определены читатель и практическая задача",
            "Нет разделов, не помогающих решить задачу"
        ),
        "Доказательства / Evidence" to listOf(
            "Цитаты связаны с исходниками",
            "Функции продуктов и другие проверяемые утверждения подтверждены",
            "Статистика имеет источник и дату",
            "Нет выдуманных ссылок, тестов, отзывов или экспертных заявлений",
            "Неизвестные факты отмечены редактору",
            "Для высокорисковых тем привлечён компетентный эксперт"
        ),
        "Самостоятельная ценность / Added value" to listOf(
            "Есть инструкции, примеры, сравнения или собственный анализ",
            "Материал добавляет ценность относительно изученных страниц",
            "Это не простой пересказ видео или конкурирующих страниц",
            "Проверено дублирование существующих страниц",
            "Выводы соответствуют свидетельствам"
        ),
        "Редактура / Editing" to listOf(
            "Нет повторов и искусственного увеличения объёма",
            "Термины объяснены аудитории",
            "Заголовки отражают содержание",
            "Ссылки работают",
            "Ключевые слова не вставлены механически"
        ),
        "Техническая публикация / Technical" to listOf(
            "Уникальный URL и корректный HTTP-статус",
            "Нет случайного noindex или блокировки в robots.txt",
            "Canonical корректен",
            "Страница доступна через внутренние ссылки",
            "Sitemap обновляется",
            "Title и meta description уникальны и осмысленны",
            "Мобильная версия и скорость приемлемы",
            "Structured data соответствует содержимому и правилам Google"
        ),
        "После публикации / After publishing" to listOf(
            "URL проверен в Google Search Console",
            "Отслеживаются индексирование, показы, клики и запросы",
            "Измеряются конверсии, если они важны",
            "Обновления основаны на изменениях и данных"
        )
    )
    val states = listOf("", "pass", "revise", "fail", "not_applicable")
    val total get() = sections.sumOf { it.second.size }
}

object Seo {
    // ------------------------------------------------------------------ storage
    private fun arr(pid: String, file: String, key: String): JSONArray =
        Store.readFile(pid, file)?.let { try { JSONObject(it).optJSONArray(key) } catch (e: Exception) { null } } ?: JSONArray()

    private fun saveArr(pid: String, file: String, key: String, a: JSONArray, meta: JSONObject? = null) =
        Store.writeFile(pid, file, JSONObject().put(key, a).also { if (meta != null) it.put("_meta", meta) }.toString(1))

    fun insights(pid: String): List<JSONObject> = arr(pid, "insights.json", "insights").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun saveInsights(pid: String, list: List<JSONObject>) = saveArr(pid, "insights.json", "insights", JSONArray(list))

    fun clusters(pid: String): List<JSONObject> = arr(pid, "clusters.json", "clusters").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun saveClusters(pid: String, list: List<JSONObject>) = saveArr(pid, "clusters.json", "clusters", JSONArray(list))
    fun cluster(pid: String, id: String): JSONObject? = clusters(pid).firstOrNull { it.optString("id") == id }

    fun updateCluster(pid: String, id: String, change: (JSONObject) -> Unit) {
        val list = clusters(pid)
        list.firstOrNull { it.optString("id") == id }?.let(change)
        saveClusters(pid, list)
    }

    fun pipeline(c: JSONObject): JSONObject = c.optJSONObject("pipeline") ?: JSONObject().also { c.put("pipeline", it) }

    fun keywords(pid: String): List<JSONObject> = arr(pid, "keywords.json", "keywords").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun saveKeywords(pid: String, list: List<JSONObject>) = saveArr(pid, "keywords.json", "keywords", JSONArray(list))

    fun usage(pid: String): List<JSONObject> = arr(pid, "usage.json", "usage").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun logUsage(pid: String, task: String, model: String, inTok: Int, outTok: Int, error: String?) {
        val list = usage(pid).toMutableList()
        list.add(JSONObject().put("at", System.currentTimeMillis()).put("task", task).put("model", model)
            .put("in", inTok).put("out", outTok).put("error", error ?: JSONObject.NULL))
        saveArr(pid, "usage.json", "usage", JSONArray(list.takeLast(2000)))
    }

    private fun meta(key: String, r: LlmReply) = JSONObject().put("model", r.model).put("prompt_key", key)
        .put("prompt_version", sha1(Prompts.get(key)).take(8)).put("at", System.currentTimeMillis())
        .put("in_tokens", r.inTokens).put("out_tokens", r.outTokens)

    private fun lang() = if (Settings.lang == "en") "English" else "русский"

    private fun ctx(p: Project) = p.seo.summary(p)

    /** JSON call with schema check and one retry, logged to usage.json. */
    private fun aiJson(p: Project, key: String, vars: Map<String, String>, required: String, maxTokens: Int = 8000): Pair<JSONObject, JSONObject> {
        val prov = AiTasks.requireProvider()
        val base = Prompts.fill(key, vars + ("lang" to lang()))
        var prompt = base
        var limit = maxTokens
        var last = ""
        var salvaged: Pair<JSONObject, JSONObject>? = null
        for (attempt in 0..2) {
            val r = try {
                Llm.complete(prov, prov.smartModel, SEO_SYSTEM, prompt, json = true, maxTokens = limit, task = key, pid = p.id)
            } catch (e: Exception) {
                logUsage(p.id, key, prov.smartModel, 0, 0, e.message)
                throw e
            }
            val o = parseJson(r.text, required)
            val ok = o != null && !r.truncated
            logUsage(p.id, key, r.model, r.inTokens, r.outTokens, if (ok) null else if (r.truncated) "truncated" else "invalid json")
            L.i("seo", "$key attempt ${attempt + 1}: ${if (ok) "ok" else if (r.truncated) "truncated at limit $limit" else "invalid json: " + r.text.take(200)} in=${r.inTokens} out=${r.outTokens}")
            if (ok) return o!! to meta(key, r)
            // keep the complete part of a cut-off answer as a fallback
            if (o == null) JsonSalvage.array(r.text, required)?.let { s -> salvaged = JSONObject(s).put("_partial", true) to meta(key, r) }
            else if (salvaged == null) salvaged = o.put("_partial", true) to meta(key, r)
            last = r.text.take(200)
            if (r.truncated || o == null && looksCut(r.text)) {
                limit = minOf(limit * 2, 32000)
                prompt = base + "\n\nПредыдущий ответ оборвался из-за лимита длины. Отвечай компактнее: короткие statement, цитаты до 200 символов, не больше 12 элементов. Верни только JSON."
            } else {
                prompt = base + "\n\nПредыдущий ответ не был валидным JSON с ключом \"$required\". Верни только JSON."
            }
        }
        salvaged?.let {
            L.w("seo", "$key: using partial answer with ${it.first.optJSONArray(required)?.length() ?: 0} items")
            return it
        }
        throw LlmException(t("Модель вернула невалидный JSON: ", "The model returned invalid JSON: ") + last)
    }

    private fun parseJson(text: String, required: String): JSONObject? = try {
        JsonText.firstObject(text)?.let { JSONObject(it) }?.takeIf { it.has(required) }
    } catch (e: Exception) {
        null
    }

    /** Opened but never closed JSON = cut off by the output limit. */
    private fun looksCut(text: String) = text.trim().let { it.startsWith("{") && !it.endsWith("}") }

    private fun aiText(p: Project, key: String, vars: Map<String, String>, maxTokens: Int = 8000): Pair<String, JSONObject> {
        val prov = AiTasks.requireProvider()
        val r = try {
            Llm.complete(prov, prov.smartModel, SEO_SYSTEM, Prompts.fill(key, vars + ("lang" to lang())), maxTokens = maxTokens, task = key, pid = p.id)
        } catch (e: Exception) {
            logUsage(p.id, key, prov.smartModel, 0, 0, e.message)
            throw e
        }
        logUsage(p.id, key, r.model, r.inTokens, r.outTokens, null)
        return r.text to meta(key, r)
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    // ------------------------------------------------------------------ local VoC (free)
    fun localVoc(pid: String, videos: Collection<Video>, includeTranscripts: Boolean): List<Voc.Item> {
        val msgs = mutableListOf<Voc.Msg>()
        for (v in videos) {
            Store.comments(pid, v.id).forEachIndexed { i, c -> msgs.add(Voc.Msg(v.id, c.text, c.likes, c.author, i, null)) }
            if (includeTranscripts) Store.transcript(pid, v.id)?.let { msgs.addAll(Voc.transcriptMessages(v.id, it)) }
        }
        return Voc.extract(msgs)
    }

    fun sourceUrl(v: Video?, commentId: String?, timestamp: String?): String {
        if (v == null || isLocalSource(v.id) || v.url.isBlank()) return ""
        if (!commentId.isNullOrBlank()) return "https://www.youtube.com/watch?v=${v.id}&lc=$commentId"
        val sec = Voc.timestampSeconds(timestamp)
        return if (sec != null) "https://www.youtube.com/watch?v=${v.id}&t=${sec}s" else v.url
    }

    // ------------------------------------------------------------------ P1 insights
    fun hasInsights(pid: String, vid: String) = Store.readFile(pid, "voc_ai/$vid.json") != null

    fun estimateP1(pid: String, v: Video): Int {
        val tr = (Store.transcript(pid, v.id) ?: "").take(Settings.chunkChars)
        val cm = Store.comments(pid, v.id).take(150).sumOf { it.text.length.coerceAtMost(400) }
        return Llm.estimateTokens(tr) + cm / 3 + 1500
    }

    /** Extracts insights from one video/source; returns the number of insights. */
    fun extractInsights(p: Project, v: Video, force: Boolean = false): Int {
        if (!force && hasInsights(p.id, v.id)) return insights(p.id).count { it.optString("video_id") == v.id }
        val transcript = Store.transcript(p.id, v.id) ?: ""
        val comments = Store.comments(p.id, v.id)
        if (transcript.isBlank() && comments.isEmpty()) return 0
        val order = comments.indices.sortedByDescending { comments[it].likes }
        val sb = StringBuilder()
        for (i in order) {
            val c = comments[i]
            if (Voc.isSpam(c.text)) continue
            val line = "c${i + 1} (♥${c.likes}${if (c.isReply) ", reply" else ""}) ${c.text.replace('\n', ' ').take(400)}\n"
            if (sb.length + line.length > Settings.chunkChars) break
            sb.append(line)
        }
        val tr = if (transcript.length > Settings.chunkChars) transcript.take(Settings.chunkChars) + "\n…(truncated)" else transcript
        val (o, m) = aiJson(
            p, "p1_insights",
            mapOf(
                "project_context" to ctx(p) + "\nVideo: ${v.title} — ${v.channel}",
                "transcript" to tr.ifBlank { "(нет)" },
                "comments" to sb.toString().ifBlank { "(нет)" }
            ),
            "items", 12000
        )
        val items = o.optJSONArray("items") ?: JSONArray()
        val norms = comments.map { Voc.normalize(it.text) }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until minOf(items.length(), 30)) {
            val it = items.optJSONObject(i) ?: continue
            val type = it.optString("type").lowercase().let { t -> if (t in INSIGHT_TYPES) t else "hypothesis" }
            val quote = it.optString("quote")
            val cid = it.optString("comment_id").takeIf { s -> s.isNotBlank() && s != "null" }
            val idx = cid?.removePrefix("c")?.toIntOrNull()?.minus(1)
            val comment = idx?.let { j -> comments.getOrNull(j) }
            val ts = it.optString("timestamp").takeIf { s -> s.isNotBlank() && s != "null" }
            val speaker = if (comment != null) "commenter" else it.optString("speaker_type", "author").ifBlank { "author" }
            val verified = when {
                comment != null -> Voc.quoteFound(quote, comment.text)
                quote.isNotBlank() -> Voc.quoteFound(quote, transcript)
                else -> false
            }
            val dup = if (comment != null) norms.count { n -> n == norms[idx] } else 1
            val q = JSONObject().put("text", quote).put("source_id", v.id)
                .put("comment_id", comment?.let { c -> c.id.ifBlank { "c${idx + 1}" } } ?: JSONObject.NULL)
                .put("timestamp", ts ?: JSONObject.NULL)
                .put("url", sourceUrl(v, comment?.id?.takeIf { s -> s.isNotBlank() }, if (comment == null) ts else null))
                .put("speaker_type", speaker)
                .put("author_ref", comment?.author?.takeIf { a -> a.isNotBlank() } ?: JSONObject.NULL)
                .put("likes", comment?.likes ?: JSONObject.NULL)
                .put("verified", verified)
            out.add(
                JSONObject().put("id", "ins_${v.id}_${i + 1}").put("project_id", p.id).put("video_id", v.id)
                    .put("video_title", v.title).put("type", type).put("statement", it.optString("statement"))
                    .put("quotes", JSONArray().put(q)).put("mention_count", dup)
                    .put("unique_author_count", JSONObject.NULL)
                    .put("confidence", it.optString("confidence", "low").lowercase().let { c -> if (c in listOf("high", "medium", "low")) c else "low" })
                    .put("evidence_type", if (verified) "direct_quote" else "unverified_quote")
                    .put("interpretation", it.optString("interpretation")).put("hypothesis", it.optString("hypothesis"))
                    .put("status", "new").put("_meta", m)
            )
        }
        Store.writeFile(p.id, "voc_ai/${v.id}.json", JSONObject().put("items", JSONArray(out)).put("_meta", m).toString())
        val all = insights(p.id).filter { it.optString("video_id") != v.id } + out
        saveInsights(p.id, all)
        return out.size
    }

    val INSIGHT_TYPES = setOf("question", "pain", "jtbd", "voc", "search_intent", "objection", "unmet_need", "content_gap", "topic", "evidence", "hypothesis")

    fun setInsightStatus(pid: String, id: String, status: String) {
        val list = insights(pid)
        list.firstOrNull { it.optString("id") == id }?.put("status", status)
        saveInsights(pid, list)
    }

    // ------------------------------------------------------------------ P2 clusters
    fun clusterInsights(p: Project): Int {
        val ins = insights(p.id).filter { it.optString("status") != "rejected" }
        if (ins.isEmpty()) throw LlmException(t("Сначала извлеките инсайты.", "Extract insights first."))
        val sb = StringBuilder()
        for (i in ins) {
            val q = i.optJSONArray("quotes")?.optJSONObject(0)
            val line = "${i.optString("id")} | ${i.optString("type")} | ${i.optString("statement")} | src=${i.optString("video_id")}" +
                (q?.optString("text")?.takeIf { it.isNotBlank() }?.let { " | «${it.take(160)}»" } ?: "") + "\n"
            if (sb.length + line.length > Settings.chunkChars * 3) break
            sb.append(line)
        }
        val (o, m) = aiJson(p, "p2_clusters", mapOf("insights" to sb.toString()), "clusters", 8000)
        val byId = ins.associateBy { it.optString("id") }
        val old = clusters(p.id)
        val arr = o.optJSONArray("clusters") ?: JSONArray()
        val out = mutableListOf<JSONObject>()
        for (k in 0 until arr.length()) {
            val c = arr.optJSONObject(k) ?: continue
            val name = c.optString("name")
            if (name.isBlank()) continue
            val ids = strings(c.optJSONArray("insight_ids")).filter { it in byId }
            val members = ids.mapNotNull { byId[it] }
            val quotes = members.flatMap { mi -> mi.optJSONArray("quotes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
            val commenterQuotes = quotes.filter { it.optString("speaker_type") == "commenter" }
            val authors = commenterQuotes.mapNotNull { it.optString("author_ref").takeIf { a -> a.isNotBlank() && a != "null" } }.toSet()
            // unique authors only when every commenter quote carries an author reference
            val reliable = commenterQuotes.isNotEmpty() && commenterQuotes.all { !it.isNull("author_ref") && it.optString("author_ref").isNotBlank() }
            val id = "cluster_" + sha1(name.lowercase()).take(8)
            val prev = old.firstOrNull { it.optString("id") == id } ?: old.firstOrNull { Voc.similarity(it.optString("name"), name) > 0.8 }
            out.add(
                JSONObject().put("id", id).put("name", name).put("need", c.optString("need"))
                    .put("summary", c.optString("summary")).put("insight_ids", JSONArray(ids))
                    .put("source_ids", JSONArray(members.map { it.optString("video_id") }.distinct()))
                    .put("mention_count", members.sumOf { it.optInt("mention_count", 1) })
                    .put("unique_author_count", if (reliable) authors.size else JSONObject.NULL)
                    .put("intent_hypothesis", c.optString("intent_hypothesis"))
                    .put("confidence", c.optString("confidence", "low"))
                    .put("unresolved_questions", c.optJSONArray("unresolved_questions") ?: JSONArray())
                    .put("segments_or_contradictions", c.opt("segments_or_contradictions") ?: JSONObject.NULL)
                    .put("pipeline", prev?.optJSONObject("pipeline") ?: JSONObject())
                    .put("_meta", m)
            )
        }
        // duplicate topics inside the set
        for (a in out.indices) for (b in a + 1 until out.size) {
            if (Voc.similarity(out[a].optString("name") + " " + out[a].optString("need"), out[b].optString("name") + " " + out[b].optString("need")) > 0.5) {
                out[b].put("possible_duplicate_of", out[a].optString("id"))
            }
        }
        saveClusters(p.id, out)
        return out.size
    }

    // ------------------------------------------------------------------ keyword research (autocomplete, no volumes)
    fun googleSuggest(q: String, hl: String, gl: String): List<String> {
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&hl=" +
            URLEncoder.encode(hl.ifBlank { Settings.lang }, "UTF-8") +
            (if (gl.isNotBlank()) "&gl=" + URLEncoder.encode(gl, "UTF-8") else "") +
            "&q=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val text = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val a = JSONArray(text)
        return strings(a.optJSONArray(1))
    }

    private fun prefixes(lang: String): List<String> = if (lang.startsWith("en"))
        listOf("how", "why", "what", "best", "vs", "can", "should", "problem") else
        listOf("как", "почему", "что", "лучший", "или", "можно ли", "стоит ли", "проблема")

    private fun alphabet(lang: String): List<String> = if (lang.startsWith("en"))
        ('a'..'z').map { it.toString() } else "абвгдежзиклмнопрстуфхцчшэюя".map { it.toString() }

    /** Collects real query phrasings from autocomplete. Returns how many new phrases were stored. */
    fun keywordResearch(p: Project, seeds: List<String>, extended: Boolean, cancel: AtomicBoolean, progress: (String) -> Unit): Int {
        val lang = p.seo.language.ifBlank { Settings.lang }
        val gl = p.seo.country
        val list = keywords(p.id).toMutableList()
        val seen = list.map { it.optString("source") + "|" + it.optString("q").lowercase() }.toHashSet()
        var added = 0
        val now = System.currentTimeMillis()
        val queries = seeds.flatMap { s ->
            listOf(s) + if (extended) prefixes(lang).map { "$it $s" } + alphabet(lang).map { "$s $it" } else emptyList()
        }.distinct()
        for ((i, q) in queries.withIndex()) {
            if (cancel.get()) break
            progress("${i + 1}/${queries.size}: $q")
            val sources = mutableListOf<Pair<String, () -> List<String>>>("youtube_suggest" to { RecEngine.suggestions(q) })
            if (Settings.googleSuggest) sources.add("google_suggest" to { googleSuggest(q, lang, gl) })
            for ((src, f) in sources) {
                val res = try {
                    f()
                } catch (e: Exception) {
                    L.w("seo", "$src failed for \"$q\": ${e.message}")
                    emptyList()
                }
                for (s in res) {
                    if (seen.add("$src|${s.lowercase()}")) {
                        list.add(JSONObject().put("q", s).put("source", src).put("seed", q).put("checked_at", now)
                            .put("lang", lang).put("country", gl).put("volume", JSONObject.NULL).put("difficulty", JSONObject.NULL))
                        added++
                    }
                }
                Thread.sleep(maxOf(250L, Settings.requestDelayMs.toLong() / 2))
            }
        }
        saveKeywords(p.id, list)
        return added
    }

    fun keywordsFor(pid: String, topic: String): List<JSONObject> {
        val kw = Relevance.keywords(topic)
        return keywords(pid).filter { Relevance.matches(kw, it.optString("q")) }
    }

    // ------------------------------------------------------------------ evidence helpers
    fun evidence(pid: String, c: JSONObject, limit: Int = 15): String {
        val byId = insights(pid).associateBy { it.optString("id") }
        val sb = StringBuilder()
        strings(c.optJSONArray("insight_ids")).mapNotNull { byId[it] }.take(limit).forEach { i ->
            sb.append("- [").append(i.optString("type")).append("] ").append(i.optString("statement"))
            val q = i.optJSONArray("quotes")?.optJSONObject(0)
            if (q != null && q.optString("text").isNotBlank()) {
                sb.append(" — «").append(q.optString("text").take(200)).append("» (")
                    .append(q.optString("speaker_type")).append(", ").append(if (q.optBoolean("verified")) "verified" else "unverified")
                    .append(q.optString("url").takeIf { it.isNotBlank() }?.let { ", $it" } ?: "").append(")")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun keywordData(pid: String, c: JSONObject): String {
        val list = keywordsFor(pid, c.optString("name") + " " + c.optString("need") + " " + c.optString("summary"))
        if (list.isEmpty()) return "нет данных (спрос не проверен)"
        return list.take(40).joinToString("\n") {
            "- \"${it.optString("q")}\" — ${it.optString("source")}, ${Fmt.isoDay(it.optLong("checked_at"))}, volume: unknown"
        }
    }

    fun existingPages(p: Project): String = p.seo.existingPages.ifBlank { "нет" }

    /** Local overlap check of a question/title against the site's existing pages and other topics. */
    fun overlaps(p: Project, text: String, selfId: String): List<String> {
        val out = mutableListOf<String>()
        p.seo.existingPages.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val s = Voc.similarity(text, line)
            if (s >= 0.3) out.add("${(s * 100).toInt()}% · $line")
        }
        clusters(p.id).filter { it.optString("id") != selfId }.forEach { c ->
            val other = Seo.pipeline(c).optJSONObject("qualify")?.optString("primary_question") ?: c.optString("name")
            val s = Voc.similarity(text, other)
            if (s >= 0.4) out.add("${(s * 100).toInt()}% · ${t("тема", "topic")}: ${c.optString("name")}")
        }
        return out
    }

    // ------------------------------------------------------------------ P3–P6 per topic, P7 ideas
    fun qualify(p: Project, id: String): JSONObject {
        val c = cluster(p.id, id) ?: throw IllegalStateException("cluster")
        val topic = c.optString("name") + " — " + c.optString("summary") + " (need: " + c.optString("need") + ")"
        val (o, m) = aiJson(
            p, "p3_qualify",
            mapOf("topic" to topic, "evidence" to evidence(p.id, c), "existing_pages" to existingPages(p),
                "keyword_data" to keywordData(p.id, c), "serp_data" to "нет данных (SERP-источник не подключён)"),
            "decision"
        )
        val kws = keywordsFor(p.id, c.optString("name") + " " + c.optString("need") + " " + c.optString("summary"))
        o.put("keyword_evidence", JSONObject()
            .put("status", if (kws.isEmpty()) "unverified" else "autocomplete_seen")
            .put("source", if (kws.isEmpty()) JSONObject.NULL else kws.map { it.optString("source") }.distinct().joinToString(","))
            .put("checked_at", kws.maxOfOrNull { it.optLong("checked_at") }?.let { Fmt.isoDay(it) } ?: JSONObject.NULL)
            .put("examples", JSONArray(kws.take(10).map { it.optString("q") }))
            .put("volume", JSONObject.NULL).put("difficulty", JSONObject.NULL))
        o.put("overlaps_local", JSONArray(overlaps(p, o.optString("primary_question", c.optString("name")), id)))
        updateCluster(p.id, id) { pipeline(it).put("qualify", o).put("qualify_meta", m) }
        return o
    }

    fun brief(p: Project, id: String): JSONObject {
        val c = cluster(p.id, id) ?: throw IllegalStateException("cluster")
        val q = pipeline(c).optJSONObject("qualify")
        val (o, m) = aiJson(
            p, "p4_brief",
            mapOf("topic" to c.optString("name"),
                "research" to "QUALIFICATION: ${q?.toString() ?: "нет"}\nEVIDENCE:\n${evidence(p.id, c)}\nKEYWORDS:\n${keywordData(p.id, c)}",
                "serp_notes" to "нет данных",
                "site_context" to ctx(p) + "\nEXISTING_PAGES:\n" + existingPages(p)),
            "primary_question"
        )
        o.put("topic_cluster_id", id)
        o.put("keyword_evidence", q?.optJSONObject("keyword_evidence") ?: JSONObject().put("status", "unverified"))
        o.put("decision", q?.optString("decision") ?: "investigate")
        updateCluster(p.id, id) { pipeline(it).put("brief", o).put("brief_meta", m) }
        return o
    }

    fun verifiedSources(pid: String, c: JSONObject): String {
        val byId = insights(pid).associateBy { it.optString("id") }
        val lines = strings(c.optJSONArray("insight_ids")).mapNotNull { byId[it] }.flatMap { i ->
            i.optJSONArray("quotes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        }.filter { it.optBoolean("verified") }.map { q ->
            "- «${q.optString("text").take(200)}» — ${q.optString("speaker_type")}${q.optString("url").takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}"
        }
        return lines.take(25).joinToString("\n").ifBlank { "нет проверенных источников" }
    }

    fun article(p: Project, id: String): String {
        val c = cluster(p.id, id) ?: throw IllegalStateException("cluster")
        val b = pipeline(c).optJSONObject("brief") ?: throw IllegalStateException(t("Сначала создайте бриф.", "Create the brief first."))
        val (text, m) = aiText(p, "p5_article", mapOf("brief" to b.toString(1), "sources" to verifiedSources(p.id, c), "product_context" to ctx(p)), 9000)
        val md = text.substringBefore("---META---").trim().removePrefix("```markdown").removePrefix("```md").removeSuffix("```").trim()
        val meta = try {
            JSONObject(JsonText.firstObject(text.substringAfter("---META---", "")) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        updateCluster(p.id, id) {
            val pl = pipeline(it)
            pl.put("article", md).put("article_meta", meta).put("article_ai", m).put("status", "draft").put("editor_approval", false)
            pl.remove("qa")
        }
        return md
    }

    fun quality(p: Project, id: String): JSONObject {
        val c = cluster(p.id, id) ?: throw IllegalStateException("cluster")
        val pl = pipeline(c)
        val art = pl.optString("article").ifBlank { throw IllegalStateException(t("Сначала создайте статью.", "Create the article first.")) }
        val (o, m) = aiJson(
            p, "p6_quality",
            mapOf("article" to art, "brief" to (pl.optJSONObject("brief")?.toString(1) ?: "нет"),
                "sources" to verifiedSources(p.id, c), "existing_pages" to existingPages(p)),
            "verdict"
        )
        o.put("check_markers", Regex("""\[ПРОВЕРИТЬ]|\[CHECK]""").findAll(art).count())
        updateCluster(p.id, id) { pipeline(it).put("qa", o).put("qa_meta", m) }
        return o
    }

    fun ideas(p: Project): AiResult {
        val list = clusters(p.id).filter { it.optString("confidence") != "low" }.ifEmpty { clusters(p.id) }
        if (list.isEmpty()) throw LlmException(t("Сначала соберите кластеры.", "Build clusters first."))
        val data = list.joinToString("\n") {
            "- ${it.optString("name")}: ${it.optString("need")} | mentions=${it.optInt("mention_count")} | confidence=${it.optString("confidence")} | " +
                "keywords=${Seo.pipeline(it).optJSONObject("qualify")?.optJSONObject("keyword_evidence")?.optString("status") ?: "unverified"}\n  " +
                evidence(p.id, it, 3).replace("\n", "\n  ")
        }
        val (text, m) = aiText(p, "p7_ideas", mapOf("clusters" to data, "product" to ctx(p)), 4000)
        val r = AiResult(sha1("ideas|${System.nanoTime()}"), "ideas", "project", m.optString("model"), text, m.optInt("in_tokens"), m.optInt("out_tokens"))
        Store.saveAi(p.id, r)
        return r
    }

    /** Stage of a topic in the pipeline (for badges). */
    fun stage(c: JSONObject): String {
        val pl = pipeline(c)
        return when {
            pl.optString("published_url").isNotBlank() -> t("опубликовано", "published")
            pl.optBoolean("editor_approval") -> t("одобрено", "approved")
            pl.has("qa") -> t("проверено: ", "QA: ") + pl.optJSONObject("qa")?.optString("verdict")
            pl.optString("article").isNotBlank() -> t("черновик", "draft")
            pl.has("brief") -> t("бриф", "brief")
            pl.has("qualify") -> pl.optJSONObject("qualify")?.optString("decision") ?: ""
            else -> t("кандидат", "candidate")
        }
    }

    // ------------------------------------------------------------------ manual import (TXT / CSV / JSON)
    /** Imports a transcript or comments file as a local source. Returns the created source. */
    fun importFile(pid: String, name: String, text: String): Video {
        val parsed = ImportParser.parse(name, text, Settings.anonymizeAuthors)
        val id = LOCAL_PREFIX + newId()
        val v = Video(id = id, url = "", title = parsed.title, channel = t("файл", "file"), query = "import", collected = true)
        if (parsed.transcript.isNotBlank()) {
            Store.saveTranscript(pid, id, parsed.transcript)
            v.hasTranscript = true
            v.transcriptSource = "import"
        }
        if (parsed.comments.isNotEmpty()) {
            Store.saveComments(pid, id, parsed.comments)
            v.commentsCount = parsed.comments.size
        }
        Store.mergeVideos(pid, listOf(v))
        L.i("import", "$name → transcript=${parsed.transcript.length}ch comments=${parsed.comments.size}")
        return v
    }
}

/** Pure parser of imported files (covered by unit tests). */
object ImportParser {
    data class Parsed(val title: String, val transcript: String, val comments: List<Comment>)

    private fun pseudo(name: String, anonymize: Boolean) = if (anonymize && name.isNotBlank()) "u_" + sha1(name).take(8) else name

    private fun commentOf(x: Any?, anonymize: Boolean): Comment? = when (x) {
        is String -> if (x.isBlank()) null else Comment(author = "", text = x, likes = 0, dateMs = -1, dateText = "", replyCount = 0, isReply = false)
        is JSONObject -> {
            val text = x.optString("text").ifBlank { x.optString("comment").ifBlank { x.optString("body") } }
            if (text.isBlank()) null else Comment(id = x.optString("id"), author = pseudo(x.optString("author"), anonymize), text = text,
                likes = x.optInt("likes", x.optInt("like_count")), dateMs = -1, dateText = x.optString("date"), replyCount = 0, isReply = false)
        }
        else -> null
    }

    /**
     * TXT/VTT → transcript; CSV/TSV → comments (column text/comment/body/…; author; likes);
     * JSON → array of comments (strings or objects) or {title, transcript, comments}.
     */
    fun parse(name: String, text: String, anonymize: Boolean): Parsed {
        val lower = name.lowercase()
        var title = name
        val comments = mutableListOf<Comment>()
        var transcript = ""
        when {
            lower.endsWith(".json") -> {
                val trimmed = text.trim().removePrefix("\uFEFF")
                if (trimmed.startsWith("[")) {
                    val a = JSONArray(trimmed)
                    for (i in 0 until a.length()) commentOf(a.opt(i), anonymize)?.let { comments.add(it) }
                } else {
                    val o = JSONObject(trimmed)
                    transcript = o.optString("transcript")
                    o.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                    val a = o.optJSONArray("comments") ?: JSONArray()
                    for (i in 0 until a.length()) commentOf(a.opt(i), anonymize)?.let { comments.add(it) }
                }
            }
            lower.endsWith(".csv") || lower.endsWith(".tsv") -> {
                val rows = Csv.parse(text, if (lower.endsWith(".tsv")) '\t' else ',')
                if (rows.isNotEmpty()) {
                    val head = rows[0].map { it.trim().lowercase() }
                    val ti = head.indexOfFirst { it in listOf("text", "comment", "body", "content", "message", "текст", "комментарий") }
                    val ai = head.indexOfFirst { it in listOf("author", "user", "name", "автор") }
                    val li = head.indexOfFirst { it in listOf("likes", "like_count", "лайки") }
                    val body = if (ti >= 0) rows.drop(1) else rows
                    val col = maxOf(0, ti)
                    for (r in body) {
                        val tx = r.getOrNull(col)?.trim().orEmpty()
                        if (tx.isEmpty()) continue
                        val author = if (ai >= 0) r.getOrNull(ai).orEmpty() else ""
                        comments.add(Comment(author = pseudo(author, anonymize), text = tx,
                            likes = if (li >= 0) r.getOrNull(li)?.trim()?.toIntOrNull() ?: 0 else 0,
                            dateMs = -1, dateText = "", replyCount = 0, isReply = false))
                    }
                }
            }
            else -> transcript = text
        }
        if (transcript.trimStart().startsWith("WEBVTT")) transcript = Vtt.toText(transcript)
        return Parsed(title, transcript.trim(), comments)
    }
}

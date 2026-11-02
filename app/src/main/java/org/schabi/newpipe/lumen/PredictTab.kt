/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Prompts of the "Predictions" tab: each one fills a gap the local data cannot close (names, unknown videos, low confidence, ideas, plan). */
val PREDICT_PROMPTS = linkedMapOf(
    "predict_names" to (
        "Ты аналитик рекомендаций YouTube. Тема проекта: \"{phrase}\" ({domain}).\n" +
            "Ниже сообщества графа «Следующее»: группы роликов, которые YouTube рекомендует рядом друг с другом. " +
            "Для каждого даны номер, отличительные слова названий, главные каналы и самые рекомендуемые ролики (название — канал).\n" +
            "Дай каждому сообществу короткое имя (2–4 слова) по интересу зрителя, а не по каналу, и одну фразу about: кто это смотрит и зачем. " +
            "Если сообщество не про тему проекта, поставь on_topic=false и скажи в about, куда оно уводит.\n" +
            "Опирайся только на данные, ничего не придумывай. Язык: {lang}.\n" +
            "Верни строго компактный JSON без отступов: {\"groups\":[{\"n\":1,\"name\":\"…\",\"about\":\"…\",\"on_topic\":true}]}\n\n{data}"
        ),
    "predict_classify" to (
        "Тема проекта: \"{phrase}\" ({domain}). Ролик автора: «{title}».\n" +
            "Ниже сообщества темы (номер — имя — отличительные слова) и пронумерованный список роликов, " +
            "которые YouTube показывает в «Следующее» рядом с роликом автора. По этим роликам видно, с чем YouTube связал ролик автора.\n" +
            "Для каждого ролика из списка выбери номер сообщества, к которому он ближе всего по смыслу; " +
            "0 — ролик по теме проекта, но ни одно сообщество не подходит; -1 — ролик не по теме проекта.\n" +
            "Не решай по одному совпавшему слову. Если по названию и каналу связь с темой неочевидна, ставь -1.\n" +
            "Верни строго компактный JSON: {\"a\":[[1,2],[2,-1]]} — пары [номер ролика, решение] для всех роликов списка.\n\n" +
            "Сообщества:\n{groups}\n\nРолики:\n{data}"
        ),
    "predict_review" to (
        "Ты редактор YouTube. Оцени, есть ли у черновика шанс попасть в «Следующее» рядом с роликами целевого сообщества. Тема: \"{phrase}\" ({domain}).\n" +
            "Как это работает: «Следующее» строится в основном на совместных просмотрах (одни и те же зрители смотрят оба ролика). " +
            "Название, описание и речь определяют, рядом с чем YouTube попробует показать новый ролик в первые дни. " +
            "Дальше решают кликабельность и удержание. Теги почти не влияют.\n" +
            "Данные ниже: целевое сообщество (слова, каналы, центральные ролики); «двери» — ролики, рядом с которыми YouTube уже показывает " +
            "маленькие и свежие каналы; профиль рекомендуемых роликов против найденных поиском, но не рекомендованных (при равных просмотрах); " +
            "локальная оценка черновика с проваленными проверками; сам черновик.\n" +
            "Оцени:\n" +
            "- topic_fit 0–100: насколько обещание ролика совпадает с интересом зрителей сообщества;\n" +
            "- continuation 0–100: насколько это естественное «следующее видео» после конкретной двери или центрального ролика; " +
            "door — номер этой двери (0, если такой нет);\n" +
            "- click 0–100: понятность и сила названия для этих зрителей, без кликбейта, обещание выполнимо;\n" +
            "- promise: что зритель ожидает получить, одной фразой;\n" +
            "- risks: до 4 причин, почему ролик не попадёт; fixes: до 5 конкретных правок;\n" +
            "- titles: 3 улучшенных названия на языке {lang} с теми же фактами;\n" +
            "- confidence: low|medium|high — насколько уверенно можно судить по этим данным; why — одна фраза почему.\n" +
            "Не придумывай цифры, просмотры и факты, которых нет в данных. Язык текста: {lang}.\n" +
            "Верни строго компактный JSON: {\"topic_fit\":0,\"continuation\":0,\"door\":0,\"click\":0,\"promise\":\"…\",\"risks\":[\"…\"]," +
            "\"fixes\":[\"…\"],\"titles\":[\"…\"],\"confidence\":\"low\",\"why\":\"…\"}\n\n{data}"
        ),
    "predict_ideas" to (
        "Ты продюсер YouTube. Придумай ролики, которые YouTube захочет показывать в «Следующее» рядом с роликами целевого сообщества темы \"{phrase}\" ({domain}).\n" +
            "Как это работает: «Следующее» строится в основном на совместных просмотрах. Лучший путь — ролик, который естественно продолжает " +
            "конкретную «дверь» (ролик, рядом с которым YouTube уже показывает маленькие и свежие каналы): отвечает на вопрос, который та оставила, " +
            "идёт на шаг глубже, сравнивает, разбирает ошибку, даёт практику.\n" +
            "Данные ниже: сообщество (слова, центральные ролики, каналы), двери с номерами, открытость ниши, профиль рекомендуемых роликов " +
            "против невошедших (при равных просмотрах), коридоры длительности и длины названия, факторы модели, существующие названия, пожелание автора.\n" +
            "Дай {count} идей. Для каждой:\n" +
            "- door: номер двери, которую ролик продолжает (0, если ролик опирается на всё сообщество);\n" +
            "- title: название на языке {lang}: с отличительными словами сообщества, в коридоре длины, без кликбейта и выдуманных цифр;\n" +
            "- angle: чем ролик отличается от уже рекомендуемых, одной фразой;\n" +
            "- hook: первая фраза ролика, до 20 слов;\n" +
            "- description: 2–3 предложения описания с ключевыми словами;\n" +
            "- tags: до 6 тегов;\n" +
            "- length_sec: длительность в секундах, внутри коридора;\n" +
            "- shorts: true или false;\n" +
            "- why: почему зритель двери досмотрит этот ролик следующим.\n" +
            "Не повторяй существующие названия. Язык текста: {lang}.\n" +
            "Верни строго компактный JSON: {\"ideas\":[{\"door\":1,\"title\":\"…\",\"angle\":\"…\",\"hook\":\"…\",\"description\":\"…\"," +
            "\"tags\":[\"…\"],\"length_sec\":480,\"shorts\":false,\"why\":\"…\"}]}\n\n{data}"
        ),
    "predict_plan" to (
        "Ты аналитик рекомендаций YouTube. Ниже результаты локального анализа графа «Следующее» по теме \"{phrase}\" ({domain}): " +
            "надёжность данных и её слабые места, сообщества, открытость новичкам, двери, профиль рекомендуемых роликов против невошедших, " +
            "модель с AUC, черновик автора с локальной оценкой и проверки ролика после публикации, если они есть.\n" +
            "Напиши план для автора на языке {lang}, в Markdown:\n" +
            "1) Куда целиться: 1–2 сообщества и почему (цифры из данных).\n" +
            "2) Насколько ниша открыта новичкам и что это значит на практике.\n" +
            "3) Какие двери продолжить и какими роликами (3–5 пунктов).\n" +
            "4) Оформление: длительность, название, первые слова, формат. Только то, что подтверждает разница в профиле; помни, что это корреляции.\n" +
            "5) Что проверить после публикации и когда.\n" +
            "6) Где данных мало и как их добрать (повторный обход, больше стартовых видео, поиск, фильтр по теме).\n" +
            "У каждого вывода поставь пометку: [данные] — прямо из цифр, [гипотеза] — вывод из корреляций, [мало данных]. " +
            "Не придумывай цифры.\n\n{data}"
        )
)

/** predict.json of a project: target community, AI names, draft, ideas, AI review, plan and the "My video" track. */
object PredictStore {
    const val FILE = "predict.json"
    private val lock = Any()

    fun load(pid: String): JSONObject = Store.readFile(pid, FILE)?.let {
        try {
            JSONObject(it)
        } catch (e: Exception) {
            null
        }
    } ?: JSONObject().put("protocol", Predict.PROTOCOL)

    fun edit(pid: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val o = load(pid)
        f(o)
        Store.writeFile(pid, FILE, o.toString())
    }

    /** AI names are stored by hub video id, so they survive a re-run that renumbers communities. */
    fun applyNames(a: Predict.Analysis, o: JSONObject) {
        val names = o.optJSONObject("names") ?: return
        a.communities.forEach { c ->
            val hit = c.hubs.firstNotNullOfOrNull { names.optJSONObject(it) } ?: return@forEach
            c.name = hit.optString("name")
            c.about = hit.optString("about")
        }
    }

    fun saveNames(pid: String, a: Predict.Analysis, names: Map<Int, Triple<String, String, Boolean>>) = edit(pid) { o ->
        val m = o.optJSONObject("names") ?: JSONObject().also { o.put("names", it) }
        names.forEach { (n, v) ->
            val c = a.community(n) ?: return@forEach
            val e = JSONObject().put("name", v.first).put("about", v.second).put("on_topic", v.third)
            c.hubs.take(3).forEach { m.put(it, e) }
            c.name = v.first
            c.about = v.second
        }
    }

    /** Target community: the one holding most of the saved hubs, else the biggest mostly on-topic one. */
    fun target(a: Predict.Analysis, o: JSONObject): Int {
        val arr = o.optJSONObject("target")?.optJSONArray("hubs")
        val hubs = if (arr == null) emptySet() else (0 until arr.length()).map { arr.optString(it) }.toSet()
        if (hubs.isNotEmpty()) {
            a.communities.maxByOrNull { c -> c.ids.count { it in hubs } }?.takeIf { c -> c.ids.any { it in hubs } }?.let { return it.id }
        }
        return (a.communities.firstOrNull { it.onTopic >= 0.5 } ?: a.communities.firstOrNull())?.id ?: 0
    }

    fun setTarget(pid: String, c: Predict.Community) = edit(pid) { it.put("target", JSONObject().put("hubs", JSONArray(c.hubs)).put("name", c.label)) }

    fun track(o: JSONObject): JSONObject = o.optJSONObject("track") ?: JSONObject().also { o.put("track", it) }

    fun points(o: JSONObject): List<Predict.Point> = o.optJSONObject("track")?.optJSONArray("points")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Predict.Point::from) }
    }.orEmpty()

    fun ideas(o: JSONObject): List<Predict.Idea> = o.optJSONArray("ideas")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Predict.Idea::from) }
    }.orEmpty()

    /** Our video's last "Up next" and how it was placed (for the AI refinement). */
    fun last(o: JSONObject): Pair<List<Predict.Rel>, Predict.Placement>? {
        val l = o.optJSONObject("track")?.optJSONObject("last") ?: return null
        val rel = l.optJSONArray("rel") ?: return null
        val codes = l.optJSONArray("codes") ?: return null
        if (rel.length() != codes.length()) return null
        val list = (0 until rel.length()).map { i ->
            val r = rel.optJSONObject(i) ?: JSONObject()
            Predict.Rel(r.optString("id"), r.optString("title"), r.optString("channel"))
        }
        return list to Predict.Placement((0 until codes.length()).map { codes.optInt(it, -2) })
    }
}

/** Builds the analysis from the project's files. */
object PredictData {
    /** Project videos found by search or import (not added from recommendations). */
    fun search(videos: Collection<Video>): List<Predict.Item> = videos.filter { !isLocalSource(it.id) && it.query != "rec" }
        .map { Predict.Item(it.id, it.title, it.channel, it.views, it.uploadMs, it.duration, it.isShort) }

    fun analyse(pid: String): Predict.Analysis? {
        val g = RecGraph.load(pid)
        if (g.nodes.isEmpty()) return null
        return Predict.analyse(g.nodes.values.toList(), g.edges.toList(), search(Store.videos(pid).values), System.currentTimeMillis())
            .also { PredictStore.applyNames(it, PredictStore.load(pid)) }
    }

    /** Compact text of everything the tab knows (for the plan and the "Ask AI" bar). */
    fun brief(pid: String): String {
        val a = analyse(pid) ?: return ""
        val o = PredictStore.load(pid)
        return Predict.brief(a, PredictStore.target(a, o), Predict.Draft.from(o.optJSONObject("draft")), PredictStore.points(o))
    }
}

/** AI calls of the tab, all through Llm.complete (task predict_*). */
object PredictAi {
    private fun lang() = if (Settings.lang == "en") "English" else "русский"
    private fun prov() = Provider.active() ?: throw LlmException(t("AI не подключён", "AI not connected"))

    private fun fill(key: String, p: Project, data: String, extra: Map<String, String> = emptyMap()): String {
        val vars = LinkedHashMap<String, String>()
        vars["phrase"] = p.phrase.ifBlank { p.name }
        vars["domain"] = p.domain.ifBlank { "-" }
        vars["lang"] = lang()
        vars.putAll(extra)
        vars["data"] = data
        return Prompts.fill(key, vars)
    }

    fun names(p: Project, a: Predict.Analysis): Int {
        val pr = prov()
        val r = Llm.complete(pr, pr.fastModel, Prompts.SYSTEM, fill("predict_names", p, Predict.groupsBlock(a)), json = true, maxTokens = 1500, task = "predict_names", pid = p.id)
        val names = Predict.parseNames(r.text)
        if (names.isEmpty()) throw LlmException(t("ИИ не вернул имена сообществ", "AI returned no community names"), r.text)
        PredictStore.saveNames(p.id, a, names)
        return names.size
    }

    /** Asks only about the related videos the local data could not place. */
    fun classify(p: Project, a: Predict.Analysis, rel: List<Predict.Rel>, pl: Predict.Placement, title: String): Predict.Placement {
        val idx = pl.unknown
        if (idx.isEmpty()) return pl
        val pr = prov()
        val groups = a.communities.joinToString("\n") { "${it.id} — ${it.label} — ${it.terms.take(6).joinToString(", ")}" }
        val data = idx.mapIndexed { i, k -> "${i + 1}. ${rel[k].title} — ${rel[k].channel}" }.joinToString("\n")
        val r = Llm.complete(
            pr, pr.fastModel, Prompts.SYSTEM, fill("predict_classify", p, data, mapOf("groups" to groups, "title" to title.ifBlank { "-" })),
            json = true, maxTokens = 800, task = "predict_classify", pid = p.id
        )
        val ans = Predict.parseClassify(r.text, idx.size, a.communities.map { it.id }.toSet())
        if (ans.isEmpty()) throw LlmException(t("ИИ не разобрал список", "AI did not sort the list"), r.text)
        return pl.with(ans.mapKeys { idx[it.key] })
    }

    private fun community(a: Predict.Analysis, target: Int) = a.community(target)?.let { Predict.groupBlock(a, it, 6) } ?: "-"

    fun review(p: Project, a: Predict.Analysis, d: Predict.Draft, target: Int): Predict.Review {
        val pr = prov()
        val doors = a.doorsOf(target).take(6)
        val data = "TARGET COMMUNITY\n" + community(a, target) +
            "\n\nDOORS\n" + Predict.doorsBlock(a, doors).ifBlank { "-" } +
            "\n\nPROFILE\n" + Predict.profileBlock(a.profile) + "\n" + Predict.modelBlock(a.model) +
            "\n\nLOCAL ESTIMATE\n" + Predict.fitBlock(Predict.fit(a, d, target)) +
            "\n\nDRAFT\n" + Predict.draftBlock(d)
        val r = Llm.complete(pr, pr.smartModel, Prompts.SYSTEM, fill("predict_review", p, data), json = true, maxTokens = 1500, task = "predict_review", pid = p.id)
        val rev = Predict.parseReview(r.text) ?: throw LlmException(t("ИИ не вернул оценку", "AI returned no estimate"), r.text)
        PredictStore.edit(p.id) { it.put("review", rev.toJson().put("for", d.toJson().toString().hashCode()).put("doors", JSONArray(doors.map { door -> door.id }))) }
        return rev
    }

    fun ideas(p: Project, a: Predict.Analysis, target: Int, wish: String, count: Int = 10): List<Predict.Idea> {
        val pr = prov()
        val c = a.community(target)
        val doors = a.doorsOf(target).take(8)
        val o = a.community(target)?.open ?: a.open
        val data = "TARGET COMMUNITY\n" + community(a, target) +
            "\n\nOPENNESS: newcomers get ${(o.share * 100).roundToInt()}% of recommendations, fresh ${(o.freshShare * 100).roundToInt()}%, " +
            "median age ${o.medianAgeDays} days" +
            "\n\nDOORS\n" + Predict.doorsBlock(a, doors).ifBlank { "-" } +
            "\n\nPROFILE\n" + Predict.profileBlock(a.profile) + "\n" + Predict.modelBlock(a.model) +
            "\n\nEXISTING TITLES\n" + (c?.ids.orEmpty().take(25).mapNotNull { a.nodes[it]?.title }.joinToString("\n").ifBlank { "-" }) +
            "\n\nAUTHOR'S WISH\n" + wish.ifBlank { "-" }
        val r = Llm.complete(
            pr, pr.smartModel, Prompts.SYSTEM, fill("predict_ideas", p, data, mapOf("count" to count.toString())),
            json = true, maxTokens = 5000, task = "predict_ideas", pid = p.id
        )
        val list = Predict.parseIdeas(r.text, doors.size)
        if (list.isEmpty()) throw LlmException(t("ИИ не вернул идей", "AI returned no ideas"), r.text)
        PredictStore.edit(p.id) { s ->
            s.put("ideas", JSONArray(list.map { it.toJson() })).put("ideasDoors", JSONArray(doors.map { it.id }))
                .put("ideasAt", System.currentTimeMillis()).put("wish", wish)
        }
        return list
    }

    fun plan(p: Project, a: Predict.Analysis): String {
        val pr = prov()
        val o = PredictStore.load(p.id)
        val data = Predict.brief(a, PredictStore.target(a, o), Predict.Draft.from(o.optJSONObject("draft")), PredictStore.points(o))
        val r = Llm.complete(pr, pr.smartModel, Prompts.SYSTEM, fill("predict_plan", p, data), maxTokens = 3000, task = "predict_plan", pid = p.id)
        PredictStore.edit(p.id) { it.put("plan", r.text).put("planAt", System.currentTimeMillis()) }
        return r.text
    }
}

/** "My video": where YouTube placed our published video (network through WatchNext; the job variant uses no AI). */
object PredictTrack {
    class Fetched(val title: String, val views: Long, val rel: List<Predict.Rel>)
    class Result(val point: Predict.Point, val placement: Predict.Placement, val rel: List<Predict.Rel>, val title: String)

    /** Fresh watch page of [id] in the project region (WatchNext: one reader for all "Up next" features) and its list. */
    fun fetch(id: String, gl: String): Fetched {
        val page = WatchNext.get(id, gl, fresh = true)
        return Fetched(page.title, page.views, page.items.map { Predict.Rel(it.id, it.title, it.channel) })
    }

    /** One check: our video's "Up next" placed on the communities, then up to 8 hubs/doors of the target searched for our video. */
    fun check(p: Project, a: Predict.Analysis, url: String, target: Int, cancel: AtomicBoolean, progress: (String) -> Unit): Result {
        val id = YtQuery.videoIdFromUrl(url.trim()) ?: throw IllegalArgumentException(t("Это не ссылка на видео YouTube", "This is not a YouTube video link"))
        progress(t("Открываю ваш ролик…", "Opening your video…"))
        val gl = p.filters.country
        val own = fetch(id, gl)
        val placement = Predict.place(a, own.rel, TopicFilter.keywordsOf(p))
        val look = (a.community(target)?.hubs.orEmpty().take(4) + a.doorsOf(target).take(4).map { it.id }).distinct().filter { it != id }.take(8)
        val where = ArrayList<String>()
        var checked = 0
        for ((i, v) in look.withIndex()) {
            if (cancel.get()) break
            progress(t("Ищу ваш ролик в «Следующее»: ${i + 1} из ${look.size}", "Looking for your video in Up next: ${i + 1} of ${look.size}"))
            try {
                val f = fetch(v, gl)
                checked++
                val pos = f.rel.indexOfFirst { it.id == id }
                if (pos >= 0) where.add("$v:${pos + 1}")
            } catch (e: Exception) {
                L.w("predict", "check $v failed: ${e.message}")
            }
        }
        val point = Predict.point(a, placement, System.currentTimeMillis(), own.views, target, checked, where)
        PredictStore.edit(p.id) { o ->
            val tr = PredictStore.track(o)
            if (tr.optString("video") != id) tr.put("points", JSONArray())
            tr.put("video", id).put("url", "https://www.youtube.com/watch?v=$id").put("title", own.title)
            val pts = tr.optJSONArray("points") ?: JSONArray().also { tr.put("points", it) }
            pts.put(point.toJson())
            while (pts.length() > 120) pts.remove(0)
            tr.put("last", lastJson(own.rel, placement))
        }
        return Result(point, placement, own.rel, own.title)
    }

    private fun lastJson(rel: List<Predict.Rel>, pl: Predict.Placement) = JSONObject()
        .put("rel", JSONArray(rel.map { JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel) }))
        .put("codes", JSONArray(pl.codes))

    /** Replaces the last point after AI placed the unknown videos (same time, views and sightings). */
    fun refine(pid: String, a: Predict.Analysis, rel: List<Predict.Rel>, pl: Predict.Placement, target: Int) = PredictStore.edit(pid) { o ->
        val tr = PredictStore.track(o)
        val pts = tr.optJSONArray("points") ?: return@edit
        if (pts.length() == 0) return@edit
        val old = Predict.Point.from(pts.getJSONObject(pts.length() - 1))
        val np = Predict.point(a, pl, old.at, old.views, target, old.checked, old.where)
        pts.put(pts.length() - 1, np.toJson())
        tr.put("last", lastJson(rel, pl))
    }

    /** Daily background check (MonitorJob, kind "predict"); no AI. Notifies when the video is first seen next to hubs/doors. */
    fun runJob(ctx: Context, pid: String) {
        val p = Store.project(pid) ?: return PredictJob.cancel(ctx, pid)
        val o = PredictStore.load(pid)
        val tr = o.optJSONObject("track")
        val url = tr?.optString("url").orEmpty()
        if (tr?.optBoolean("daily") != true || url.isBlank() || p.archived || !Settings.monitoringAllowed) return PredictJob.cancel(ctx, pid)
        val a = PredictData.analyse(pid) ?: return
        val before = PredictStore.points(o).lastOrNull()
        val r = check(p, a, url, PredictStore.target(a, o), AtomicBoolean(false)) { }
        L.i("predict", "project $pid: related ${r.point.related}, target ${r.point.targetShare}, seen ${r.point.found}/${r.point.checked}")
        if (r.point.found > 0 && (before == null || before.found == 0)) {
            Notify.post(
                ctx, PredictJob.jobId(pid), t("🎯 Ролик в «Следующее»", "🎯 Your video is in Up next"),
                t("«${r.title}» виден в «Следующее» у ${r.point.found} из ${r.point.checked} центральных роликов и дверей «${r.point.target}».",
                    "“${r.title}” shows in Up next of ${r.point.found} of ${r.point.checked} hubs and doors of “${r.point.target}”."),
                pid
            )
        }
    }
}

/** Daily "My video" check through the monitoring JobService (extras kind = predict). */
object PredictJob {
    fun jobId(pid: String) = 100_000 + (pid.hashCode() and 0x7FFF)

    fun apply(ctx: Context, pid: String, on: Boolean) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.cancel(jobId(pid))
        if (!on || !Settings.monitoringAllowed) return
        val extras = PersistableBundle().apply {
            putString("id", pid)
            putString("kind", "predict")
        }
        js.schedule(
            JobInfo.Builder(jobId(pid), ComponentName(ctx, MonitorJob::class.java))
                .setPeriodic(24 * 3_600_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setExtras(extras)
                .build()
        )
    }

    fun cancel(ctx: Context, pid: String) = ctx.getSystemService(JobScheduler::class.java).cancel(jobId(pid))
}

// ====================================================================== UI

private fun pc(d: Double) = "${(d * 100).roundToInt()}%"

private fun whyText(k: String) = when (k) {
    "few_nodes" -> t("в графе мало видео по теме (<60): запустите обход с бо́льшим бюджетом", "few on-topic videos in the graph (<60): run the walk with a bigger budget")
    "no_communities" -> t("сообщества не выделились: граф слишком редкий", "no communities: the graph is too sparse")
    "topic_unchecked" -> t("у половины узлов тема не проверена: включите фильтр по теме (AI) в параметрах обхода", "half of the nodes are not checked for topic: turn on the AI topic filter in the walk settings")
    "few_samples" -> t("мало примеров для сравнения: нужно ≥15 рекомендованных и ≥15 найденных поиском", "few samples to compare: need ≥15 recommended and ≥15 found by search")
    "unmatched" -> t("рекомендованные и найденные поиском почти не совпадают по просмотрам: сравнение смещено", "recommended and searched videos barely overlap in views: the comparison is biased")
    "weak_model" -> t("модель не отличает попавшие от непопавших (AUC < 0,6) или не обучена", "the model cannot tell recommended from not (AUC < 0.6) or is not trained")
    "no_target" -> t("не выбрано целевое сообщество", "no target community")
    "small_target" -> t("целевое сообщество маленькое (<8 видео)", "the target community is small (<8 videos)")
    "no_title" -> t("у черновика нет названия", "the draft has no title")
    else -> k
}

private fun featLabel(k: String) = when (k) {
    "number" -> t("число в названии", "a number in the title")
    "year" -> t("год в названии", "a year in the title")
    "question" -> t("вопрос (?)", "a question (?)")
    "exclaim" -> t("восклицание (!)", "an exclamation (!)")
    "colon" -> t("двоеточие, тире или |", "a colon, dash or |")
    "brackets" -> t("скобки", "brackets")
    "caps" -> t("СЛОВО КАПСОМ", "a CAPS word")
    "emoji" -> t("эмодзи", "emoji")
    "you" -> t("обращение «ты/вы»", "addressing “you”")
    "i" -> t("от первого лица", "first person")
    "short" -> "Shorts"
    "fresh" -> t("свежие (≤30 дн.)", "fresh (≤30 d)")
    "duration" -> t("длительность", "duration")
    "title_len" -> t("длина названия", "title length")
    "age" -> t("возраст, дн.", "age, days")
    "views" -> t("просмотры", "views")
    "topic" -> t("близость к теме", "closeness to the topic")
    else -> k
}

private fun confText(level: Int) = when (level) {
    2 -> t("высокая", "high")
    1 -> t("средняя", "medium")
    else -> t("низкая", "low")
}

private fun dur(sec: Long) = if (sec > 0) Fmt.duration(sec) else "—"

private fun rangeText(detail: String): String {
    val p = detail.split('–')
    val a = p.getOrNull(0)?.toLongOrNull() ?: return detail
    val b = p.getOrNull(1)?.toLongOrNull() ?: return detail
    return dur(a) + "–" + dur(b)
}

private fun checkText(c: Predict.Check, d: Predict.Draft): String {
    val mark = if (c.ok) "✓ " else "✗ "
    return mark + when {
        c.key == "terms" -> if (c.ok) t("В названии есть слова сообщества: ${c.detail}", "The title uses the community's words: ${c.detail}")
        else t("В названии нет отличительных слов сообщества", "The title has none of the community's distinctive words")
        c.key == "duration" -> if (c.ok) t("Длительность в коридоре сообщества (${rangeText(c.detail)})", "Duration within the community corridor (${rangeText(c.detail)})")
        else t("Длительность ${dur(d.durationSec)} вне коридора сообщества (${rangeText(c.detail)})", "Duration ${dur(d.durationSec)} is outside the community corridor (${rangeText(c.detail)})")
        c.key == "format" -> if (c.ok) t("Формат подходит (Shorts в сообществе: ${c.detail}%)", "The format fits (Shorts in the community: ${c.detail}%)")
        else t("Формат не тот: Shorts в сообществе ${c.detail}%", "Wrong format: Shorts in the community ${c.detail}%")
        c.key == "title_len" -> if (c.ok) t("Длина названия ${d.title.length} в коридоре (${c.detail} симв.)", "Title length ${d.title.length} within the corridor (${c.detail} chars)")
        else t("Длина названия ${d.title.length} вне коридора (${c.detail} симв.)", "Title length ${d.title.length} outside the corridor (${c.detail} chars)")
        c.key.startsWith("shape_") -> {
            val f = featLabel(c.key.removePrefix("shape_"))
            val more = c.detail == "+"
            when {
                more && c.ok -> t("Есть «$f»: у рекомендуемых это чаще", "Has “$f”: more common among recommended")
                more -> t("Нет «$f», а у рекомендуемых это чаще", "No “$f”, though it is more common among recommended")
                c.ok -> t("Нет «$f»: у рекомендуемых это реже", "No “$f”: less common among recommended")
                else -> t("Есть «$f», а у рекомендуемых это реже", "Has “$f”, though it is less common among recommended")
            }
        }
        else -> c.key
    }
}

private fun ProjectActivity.copyText(s: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lumen", s))
    toast(t("Скопировано", "Copied"))
}

/** AI step of the tab: cost estimate, busy line, task in the task manager. */
private fun <T> ProjectActivity.predictAi(label: String, tokens: Int, work: () -> T, done: (T) -> Unit) {
    if (Provider.active() == null) {
        confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
        }
        return
    }
    val cost = Llm.estimateCost(tokens, tokens / 4)
    confirm(label, t("Примерно ~$tokens входных токенов", "About ~$tokens input tokens") + (if (cost > 0) String.format(" · ≈ $%.4f", cost) else ""), t("Запустить", "Run")) {
        busy = "$label…"
        render()
        bgTask(label, work, {
            busy = ""
            render()
            err(it) { predictAi(label, tokens, work, done) }
        }) { r ->
            busy = ""
            done(r)
            render()
        }
    }
}

private fun ProjectActivity.predictRow(id: String, meta: String): View {
    val a = ui["predA"] as? Predict.Analysis
    val n = a?.nodes?.get(id)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(this@predictRow, roundBg(Palette.CARD, 12f, this@predictRow), Palette.ON_SURFACE)
        setPadding(dp(8), dp(8), 0, dp(8))
    }
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundBg(Palette.ACCENT_SOFT, 8f, this@predictRow)
        clipToOutline = true
    }
    row.addView(img, LinearLayout.LayoutParams(dp(96), dp(54)).apply { marginEnd = dp(12) })
    Thumbs.load(img, ThumbUrl.mini(id, n?.thumb.orEmpty()))
    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    info.addView(text(this, n?.title?.ifBlank { null } ?: id, 14f, bold = true).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    if (!n?.channel.isNullOrBlank()) info.addView(text(this, n?.channel.orEmpty(), 12f, color = Palette.MUTED).apply { isSingleLine = true })
    if (meta.isNotBlank()) info.addView(text(this, meta, 12f, color = Palette.MUTED))
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    val url = n?.url?.ifBlank { null } ?: "https://www.youtube.com/watch?v=$id"
    row.addView(mediaBtn(this, url, n?.title.orEmpty()), LinearLayout.LayoutParams(dp(44), dp(48)))
    row.setOnClickListener { openVideo(url) }
    return row
}

private fun ProjectActivity.predictOpen(key: String, def: Boolean): Boolean = ui.getOrPut("predSec_$key") { def } as Boolean

private fun ProjectActivity.predictToggle(key: String): (Boolean) -> Unit = {
    ui["predSec_$key"] = it
    render()
}

/** "Predictions" tab: where to aim in "Up next", how to package a video for it, and whether a published one got there. */
fun ProjectActivity.predictTab(c: Col) {
    val g = RecGraph.load(project.id)
    if (g.nodes.isEmpty()) {
        c.empty(
            t("Нужен граф рекомендаций", "A recommendation graph is needed"),
            t("Предсказания строятся по графу «Следующее» и найденным поиском видео. Запустите обход во вкладке «Рекомендации».",
                "Predictions are built from the “Up next” graph and the videos found by search. Run the walk on the Recommendations tab."),
            t("К рекомендациям", "To recommendations")
        ) {
            tab = "recs"
            render()
        }
        return
    }
    val key = "${g.updatedAt}|${g.nodes.size}|${g.edges.size}|${videos.size}|${ui["predVer"] ?: 0}"
    val cached = ui["predA"] as? Predict.Analysis
    if (cached == null || ui["predKey"] != key) {
        if (ui["predBusy"] != key) {
            ui["predBusy"] = key
            val nodes = g.nodes.values.toList()
            val edges = g.edges.toList()
            val search = PredictData.search(videos.values)
            val pid = project.id
            Lumen.async({
                Predict.analyse(nodes, edges, search, System.currentTimeMillis()).also { PredictStore.applyNames(it, PredictStore.load(pid)) }
            }, {
                ui.remove("predBusy")
                err(it)
            }) { r ->
                ui["predA"] = r
                ui["predKey"] = key
                ui.remove("predBusy")
                render()
            }
        }
        c.working(t("Считаю сообщества, двери, профиль и модель…", "Computing communities, doors, profile and model…"))
        if (cached == null) return
    }
    val a: Predict.Analysis = cached
    val act = this
    val store = PredictStore.load(project.id)
    val target = PredictStore.target(a, store)
    val tc = a.community(target)

    // ---------------- data and confidence
    c.card {
        add(text(ctx, t("🔮 Предсказания", "🔮 Predictions"), Type.TITLE_M, bold = true), 0)
        muted(t("Оценки по графу «Следующее» и видео, найденным поиском. Это корреляции на выборке без аккаунта: они показывают, куда целиться и как оформить ролик, но не гарантируют попадания.",
            "Estimates from the “Up next” graph and the videos found by search. These are correlations on a logged-out sample: they show where to aim and how to package a video, not a guarantee."))
        p(t("Граф: ${a.nodes.size} видео (по теме ${a.onTopic}, не проверено ${a.unknown}), раскрыто ${a.expanded} · найдено поиском: ${a.search}",
            "Graph: ${a.nodes.size} videos (on topic ${a.onTopic}, unchecked ${a.unknown}), expanded ${a.expanded} · found by search: ${a.search}"), 13f)
        p(t("Надёжность: ", "Confidence: ") + confText(a.confidence), Type.BODY,
            color = when (a.confidence) {
                2 -> Palette.OK
                1 -> Palette.ON_SURFACE
                else -> Palette.BAD
            })
        a.why.forEach { muted("• " + whyText(it)) }
        row(
            btn(ctx, t("📋 План (ИИ)", "📋 Plan (AI)"), style = BtnStyle.TONAL) {
                predictAi(t("План для автора", "Plan for the author"), 4000, { PredictAi.plan(project, a) }) { showText(t("План для автора", "Plan for the author"), it) }
            },
            btn(ctx, t("Добрать данные", "Get more data"), style = BtnStyle.OUTLINED) {
                tab = "recs"
                render()
            }
        )
        val plan = store.optString("plan")
        if (plan.isNotBlank()) button(t("Прошлый план · ", "Previous plan · ") + Fmt.dateTime(store.optLong("planAt")), style = BtnStyle.TEXT) {
            showText(t("План для автора", "Plan for the author"), plan)
        }
    }

    // ---------------- communities
    c.section(t("🗺 Сообщества темы", "🗺 Topic communities"),
        if (a.communities.isEmpty()) "—" else t("${a.communities.size} шт. · цель: ", "${a.communities.size} · target: ") + (tc?.label ?: "—"),
        predictOpen("comm", true), predictToggle("comm")) {
        c.muted(t("Ролики, которые YouTube чаще рекомендует рядом друг с другом (Louvain по графу). Цель 🎯 — сообщество, в «Следующее» которого вы хотите попасть.",
            "Videos YouTube tends to recommend next to each other (Louvain on the graph). Target 🎯 is the community whose “Up next” you want to enter."))
        if (a.communities.isEmpty()) {
            c.muted(t("Сообщества не выделились: раскройте больше видео (бюджет, глубина).", "No communities: expand more videos (budget, depth)."))
        } else {
            c.button(t("🤖 Назвать сообщества (ИИ)", "🤖 Name communities (AI)"), style = BtnStyle.TONAL) {
                predictAi(t("Имена сообществ", "Community names"), 300 + a.communities.size * 250, { PredictAi.names(project, a) }) {
                    ui["predVer"] = ((ui["predVer"] as? Int) ?: 0) + 1
                    toast(t("Названо сообществ: $it", "Communities named: $it"))
                }
            }
        }
        for (cm in a.communities.take(8)) c.card {
            val isT = cm.id == target
            add(text(ctx, (if (isT) "🎯 " else "") + "#${cm.id} ${cm.label}", Type.TITLE_M, bold = true), 0)
            if (cm.about.isNotBlank()) muted(cm.about)
            muted(t("${cm.ids.size} видео · рекомендаций ${cm.hits} · по теме ${pc(cm.onTopic)} · новичкам ${pc(cm.open.share)} · Shorts ${pc(cm.shortsShare)}",
                "${cm.ids.size} videos · ${cm.hits} recs · on topic ${pc(cm.onTopic)} · newcomers ${pc(cm.open.share)} · Shorts ${pc(cm.shortsShare)}") +
                (cm.durIqr?.let { " · " + dur(it.first) + "–" + dur(it.last) } ?: ""))
            if (cm.terms.isNotEmpty()) chips(cm.terms.map { chip(ctx, it) })
            muted(t("Каналы: ", "Channels: ") + cm.channels.take(4).joinToString(", ") { "${it.first} (${it.second})" })
            for (h in cm.hubs.take(3)) add(predictRow(h, "×${a.nodes[h]?.hits ?: 0} · ${Fmt.num(a.nodes[h]?.views ?: -1)}"), 6)
            if (!isT) button(t("🎯 Сделать целью", "🎯 Make it the target"), style = BtnStyle.TEXT) {
                PredictStore.setTarget(project.id, cm)
                render()
            }
        }
    }

    // ---------------- openness
    c.section(t("🚪 Открытость новичкам", "🚪 Openness to newcomers"), pc(a.open.share) + t(" рекомендаций — новичкам", " of recommendations go to newcomers"),
        predictOpen("open", false), predictToggle("open")) {
        val o = tc?.open ?: a.open
        c.p(t("Новички — свежие ролики (≤30 дней) или с просмотрами меньше 10 тыс.", "Newcomers are fresh videos (≤30 days) or with fewer than 10k views."), 13f)
        c.p(t("${if (tc != null) "В «${tc.label}»" else "В графе"} новичкам достаётся ${pc(o.share)} рекомендаций; свежих ${pc(o.freshShare)}; медианный возраст рекомендуемого — ${if (o.medianAgeDays >= 0) "${o.medianAgeDays} дн." else "—"}.",
            "${if (tc != null) "In “${tc.label}”" else "In the graph"} newcomers get ${pc(o.share)} of recommendations; fresh ${pc(o.freshShare)}; median age of a recommended video ${if (o.medianAgeDays >= 0) "${o.medianAgeDays} d" else "—"}."))
        c.p(when (o.level) {
            2 -> t("Ниша открыта: YouTube охотно ставит маленькие и новые ролики рядом с лидерами.", "Open niche: YouTube readily puts small and new videos next to the leaders.")
            1 -> t("Средне: новичков пускают, но чаще через конкретные двери (ниже).", "Medium: newcomers get in, mostly through specific doors (below).")
            0 -> t("Закрыто: рекомендации держат старые и крупные ролики. Заходите через двери или соседнее, более открытое сообщество.", "Closed: recommendations are held by old and big videos. Enter through doors or a neighbouring, more open community.")
            else -> t("Мало данных, чтобы судить.", "Too little data to judge.")
        })
        if (a.communities.isNotEmpty()) c.add(BarChartView(act).apply {
            horizontal = a.communities.take(8).map { it.label.take(24) to it.open.share * 100 }
            contentDescription = t("Доля рекомендаций новичкам по сообществам", "Newcomer share by community")
        }, 8)
    }

    // ---------------- doors
    val doors = a.doorsOf(target)
    c.section(t("🚪 Двери", "🚪 Doors"), t("${doors.size} роликов пускают новичков", "${doors.size} videos let newcomers in"), predictOpen("doors", true), predictToggle("doors")) {
        c.muted(t("Ролики по теме, в «Следующее» которых YouTube уже показывает маленькие и свежие каналы. Снимайте продолжение или ответ на них: их зрители ищут следующее видео.",
            "On-topic videos whose “Up next” already shows small and fresh channels. Film a follow-up or a reply: their viewers look for the next video."))
        if (doors.isEmpty()) c.muted(t("Дверей не нашлось: в раскрытых роликах нет новичков. Раскройте больше видео.", "No doors: expanded videos show no newcomers. Expand more videos."))
        for (d in doors.take(10)) {
            val ex = d.newcomers.firstOrNull()?.let { a.nodes[it]?.title }.orEmpty()
            c.add(predictRow(d.id, t("в «Следующее» новичков ${d.newcomers.size} из ${d.children}", "${d.newcomers.size} newcomers of ${d.children} in Up next") +
                " · ${Fmt.num(a.nodes[d.id]?.views ?: -1)}" + (if (ex.isNotBlank()) "\n↳ $ex" else "")), 8)
        }
    }

    // ---------------- profile
    val pf = a.profile
    c.section(t("📐 Что отличает рекомендуемые", "📐 What recommended videos have"),
        t("${pf.pos} рекомендованных против ${pf.neg} найденных поиском", "${pf.pos} recommended vs ${pf.neg} found by search"),
        predictOpen("profile", false), predictToggle("profile")) {
        c.muted(t("Сравнение рекомендованных YouTube роликов с найденными поиском, но ни разу не рекомендованными. ",
            "Videos YouTube recommended compared with videos found by search but never recommended. ") +
            if (pf.matched) t("Найденные поиском перевзвешены к тем же уровням просмотров, чтобы популярность не исказила вывод.", "The search side is re-weighted to the same view levels, so popularity does not skew it.")
            else t("Уровни просмотров не удалось выровнять: часть разницы — просто популярность.", "View levels could not be matched: part of the difference is just popularity."))
        if (!pf.enough) c.muted("⚠ " + whyText("few_samples") + t(" (сейчас ${pf.pos}/${pf.neg})", " (now ${pf.pos}/${pf.neg})"))
        val rates = pf.feats.filter { it.rate && kotlin.math.abs(it.diff) >= 0.05 }.sortedByDescending { kotlin.math.abs(it.diff) }
        for (f in rates.take(8)) {
            val pp = (f.diff * 100).roundToInt()
            c.p("${featLabel(f.key)}: ${pc(f.pos)} " + t("против", "vs") + " ${pc(f.neg)} (${if (pp > 0) "+" else ""}$pp " + t("п.п.)", "pp)"), 13f)
        }
        pf.feat("duration")?.let { f -> if (!f.pos.isNaN()) c.p(featLabel("duration") + ": " + dur(f.pos.toLong()) + " " + t("против", "vs") + " " + (if (f.neg.isNaN()) "—" else dur(f.neg.toLong())), 13f) }
        pf.feat("age")?.let { f -> if (!f.pos.isNaN()) c.p(featLabel("age") + ": " + f.pos.toLong() + " " + t("против", "vs") + " " + (if (f.neg.isNaN()) "—" else f.neg.toLong().toString()), 13f) }
        pf.durIqr?.let { c.p(t("Коридор длительности рекомендуемых: ", "Duration corridor of recommended: ") + dur(it.first) + "–" + dur(it.last), 13f) }
        pf.titleIqr?.let { c.p(t("Коридор длины названия: ${it.first}–${it.last} символов", "Title length corridor: ${it.first}–${it.last} chars"), 13f) }
        if (pf.plus.isNotEmpty()) {
            c.muted(t("Слова рекомендуемых:", "Words of recommended:"))
            c.chips(pf.plus.map { chip(act, it.first) })
        }
        if (pf.minus.isNotEmpty()) {
            c.muted(t("Слова невошедших:", "Words of the rest:"))
            c.chips(pf.minus.map { chip(act, it.first) })
        }
        if (pf.firstWords.isNotEmpty()) c.muted(t("Первые слова названий: ", "First words of titles: ") + pf.firstWords.joinToString(", ") { "${it.first} (${it.second})" })
        c.muted(t("Это корреляции: проверяйте их A/B-тестом обложек и названий, а не принимайте как правила.", "These are correlations: test them with thumbnail and title A/B tests rather than taking them as rules."))
    }

    // ---------------- model
    val m = a.model
    c.section(t("🧮 Модель", "🧮 Model"), if (m == null) t("мало данных", "too little data") else "AUC " + String.format(java.util.Locale.US, "%.2f", m.auc),
        predictOpen("model", false), predictToggle("model")) {
        if (m == null) {
            c.p(t("Модель не обучена: нужно ≥15 рекомендованных и ≥15 найденных поиском (сейчас ${pf.pos}/${pf.neg}). Найдите больше видео во вкладке «Видео» и раскройте граф глубже.",
                "No model: needs ≥15 recommended and ≥15 found by search (now ${pf.pos}/${pf.neg}). Find more videos on the Videos tab and expand the graph deeper."))
        } else {
            c.p(t("Логистическая регрессия, проверка на 5 частях: AUC ${String.format(java.util.Locale.US, "%.2f", m.auc)} — ", "Logistic regression, 5-fold check: AUC ${String.format(java.util.Locale.US, "%.2f", m.auc)} — ") +
                when {
                    m.auc >= 0.75 -> t("уверенно отличает попавшие от непопавших.", "tells recommended from not with confidence.")
                    m.auc >= 0.6 -> t("отличает лучше монетки, но с ошибками.", "better than a coin, with errors.")
                    else -> t("почти как монетка: на её оценку не опирайтесь.", "close to a coin: do not rely on it.")
                })
            c.muted(t("Чем сильнее влияет (при прочих равных):", "Strongest factors (other things equal):"))
            for ((k, w) in m.factors().take(6)) c.p((if (w >= 0) "↑ " else "↓ ") + featLabel(k) + " (" + String.format(java.util.Locale.US, "%+.2f", w) + ")", 13f)
            c.muted(t("Просмотры в модели нужны, чтобы учесть популярность. Черновик оценивается как новый ролик маленького канала (1000 просмотров).",
                "Views are in the model to account for popularity. A draft is scored as a new video of a small channel (1,000 views)."))
        }
    }

    // ---------------- draft
    val saved = Predict.Draft.from(store.optJSONObject("draft"))
    val draft = (ui["predDraft"] as? Predict.Draft) ?: saved.also { ui["predDraft"] = it }
    c.section(t("✍️ Оценить черновик", "✍️ Score a draft"), draft.title.ifBlank { t("название, описание, сценарий", "title, description, script") },
        predictOpen("draft", true), predictToggle("draft")) {
        fun upd(f: (Predict.Draft) -> Predict.Draft) {
            ui["predDraft"] = f(ui["predDraft"] as? Predict.Draft ?: Predict.Draft())
        }
        c.edit(t("Название ролика", "Video title"), draft.title).addTextChangedListener(SimpleWatcher { s -> upd { it.copy(title = s) } })
        c.edit(t("Описание ролика", "Video description"), draft.description, multiline = true).addTextChangedListener(SimpleWatcher { s -> upd { it.copy(description = s) } })
        c.edit(t("Сценарий или первые фразы", "Script or the opening lines"), draft.script, multiline = true).addTextChangedListener(SimpleWatcher { s -> upd { it.copy(script = s) } })
        c.edit(t("Длительность (8:30)", "Duration (8:30)"), if (draft.durationSec > 0) Fmt.duration(draft.durationSec) else "")
            .addTextChangedListener(SimpleWatcher { s -> upd { it.copy(durationSec = Predict.parseDuration(s)) } })
        c.check("Shorts", draft.isShort) { on -> upd { it.copy(isShort = on) } }
        c.row(
            btn(act, t("Оценить", "Score"), primary = true) {
                val d = ui["predDraft"] as? Predict.Draft ?: Predict.Draft()
                PredictStore.edit(project.id) { it.put("draft", d.toJson()) }
                ui["predScored"] = true
                render()
            },
            btn(act, t("Очистить", "Clear"), style = BtnStyle.TEXT) {
                val old = ui["predDraft"] as? Predict.Draft ?: Predict.Draft()
                undoable(t("Черновик очищен", "Draft cleared"), apply = {
                    ui["predDraft"] = Predict.Draft()
                    PredictStore.edit(project.id) { it.remove("draft") }
                    render()
                }, revert = {
                    ui["predDraft"] = old
                    PredictStore.edit(project.id) { it.put("draft", old.toJson()) }
                    render()
                })
            }
        )
        val shown = if (ui["predScored"] == true) draft else saved
        if (!shown.isEmpty) c.card { fitCard(this, a, shown, target, store) }
    }

    // ---------------- ideas
    val ideas = PredictStore.ideas(store)
    c.section(t("💡 Варианты от ИИ", "💡 Ideas from AI"), if (ideas.isEmpty()) t("ИИ придумывает, Lumen оценивает", "AI suggests, Lumen scores") else t("${ideas.size} идей", "${ideas.size} ideas"),
        predictOpen("ideas", false), predictToggle("ideas")) {
        c.muted(t("ИИ получает сообщество-цель, двери, профиль и модель и придумывает ролики-продолжения. Оценку каждому ставит Lumen локально, по тем же правилам, что и черновику.",
            "AI gets the target community, doors, profile and model and suggests follow-up videos. Lumen scores each one locally with the same rules as the draft."))
        val wishE = c.edit(t("Пожелание к идеям (необязательно)", "Wish for the ideas (optional)"), store.optString("wish"))
        c.button(t("🤖 Придумать 10 вариантов", "🤖 Suggest 10 ideas"), style = BtnStyle.TONAL) {
            if (tc == null) return@button toast(whyText("no_target"))
            predictAi(t("Варианты роликов", "Video ideas"), 4500, { PredictAi.ideas(project, a, target, wishE.text.toString()) }) {
                toast(t("Идей: ${it.size}", "Ideas: ${it.size}"))
            }
        }
        val dIds = store.optJSONArray("ideasDoors")
        val scored = ideas.map { it to Predict.fit(a, it.draft(), target) }.sortedByDescending { it.second.score }
        for ((idea, f) in scored) c.card {
            add(text(ctx, "${f.score}/100 · ${idea.title}", Type.TITLE_M, bold = true), 0)
            if (idea.angle.isNotBlank()) p(idea.angle, 13f)
            val doorId = if (idea.door > 0) dIds?.optString(idea.door - 1).orEmpty() else ""
            if (doorId.isNotBlank()) muted(t("Продолжает: ", "Follows: ") + (a.nodes[doorId]?.title ?: doorId))
            if (idea.hook.isNotBlank()) muted(t("Хук: ", "Hook: ") + idea.hook)
            muted(listOfNotNull(
                if (idea.lengthSec > 0) dur(idea.lengthSec) else null,
                if (idea.shorts) "Shorts" else null,
                if (idea.tags.isNotEmpty()) idea.tags.joinToString(", ") else null
            ).joinToString(" · "))
            if (idea.why.isNotBlank()) muted(t("Почему: ", "Why: ") + idea.why)
            row(
                btn(ctx, t("В черновик", "To draft"), style = BtnStyle.TONAL) {
                    val d = idea.draft()
                    ui["predDraft"] = d
                    ui["predScored"] = true
                    ui["predSec_draft"] = true
                    PredictStore.edit(project.id) { it.put("draft", d.toJson()) }
                    render()
                },
                btn(ctx, "⧉", style = BtnStyle.TEXT) {
                    copyText(idea.title + "\n\n" + idea.description + (if (idea.tags.isNotEmpty()) "\n\n" + idea.tags.joinToString(", ") else ""))
                }
            )
        }
    }

    // ---------------- my video
    val points = PredictStore.points(store)
    val tr = store.optJSONObject("track")
    c.section(t("📡 Мой ролик после публикации", "📡 My video after publishing"),
        points.lastOrNull()?.let { t("цель ${pc(it.targetShare)} · виден у ${it.found} из ${it.checked}", "target ${pc(it.targetShare)} · seen at ${it.found} of ${it.checked}") }
            ?: t("куда YouTube отнёс ваш ролик", "where YouTube placed your video"),
        predictOpen("track", points.isNotEmpty()), predictToggle("track")) {
        c.muted(t("Через 1–3 дня после публикации «Следующее» вашего ролика показывает, с чем YouTube его связал. Если ролик появился в «Следующее» центральных роликов и дверей цели, это прямое подтверждение попадания.",
            "1–3 days after publishing, your video's “Up next” shows what YouTube linked it with. Showing up in “Up next” of the target's hubs and doors is direct confirmation."))
        val urlE = c.edit(t("Ссылка на ваш ролик", "Link to your video"), tr?.optString("url").orEmpty())
        c.button(t("Проверить сейчас (до 9 запросов)", "Check now (up to 9 requests)"), primary = true) {
            val url = urlE.text.toString()
            if (YtQuery.videoIdFromUrl(url.trim()) == null) return@button toast(t("Вставьте ссылку на видео YouTube", "Paste a YouTube video link"))
            cancel.set(false)
            busy = t("Проверяю ролик…", "Checking the video…")
            render()
            Lumen.async({
                PredictTrack.check(project, a, url, target, cancel) { msg -> Lumen.ui { progressView?.text = msg } }
            }, {
                busy = ""
                render()
                err(it)
            }) { r ->
                busy = ""
                render()
                toast(t("«Следующее»: по теме ${r.point.onTopic} из ${r.point.related}; виден у ${r.point.found} из ${r.point.checked}",
                    "Up next: on topic ${r.point.onTopic} of ${r.point.related}; seen at ${r.point.found} of ${r.point.checked}"))
            }
        }
        val daily = tr?.optBoolean("daily") == true
        c.check(t("Проверять раз в сутки в фоне (без ИИ, уведомит о первом попадании)", "Check daily in the background (no AI, notifies on the first hit)"), daily) { on ->
            if (on && tr?.optString("url").isNullOrBlank()) {
                toast(t("Сначала проверьте ролик вручную", "Check the video by hand first"))
                render()
                return@check
            }
            PredictStore.edit(project.id) { PredictStore.track(it).put("daily", on) }
            PredictJob.apply(act, project.id, on)
            if (on && !Settings.monitoringAllowed) toast(t("Фоновые проверки выключены в настройках приложения", "Background checks are off in the app settings"))
            render()
        }
        val last = PredictStore.last(store)
        if (last != null && last.second.unknown.isNotEmpty()) {
            c.button(t("🤖 Уточнить ИИ: непонятных роликов ${last.second.unknown.size}", "🤖 Refine with AI: ${last.second.unknown.size} unclear videos"), style = BtnStyle.TONAL) {
                predictAi(t("Разбор «Следующее» вашего ролика", "Sorting your video's Up next"), 200 + last.second.unknown.size * 40, {
                    val pl = PredictAi.classify(project, a, last.first, last.second, tr?.optString("title").orEmpty())
                    PredictTrack.refine(project.id, a, last.first, pl, target)
                    pl
                }) { pl -> toast(t("По теме: ${pl.onTopic} из ${pl.total}", "On topic: ${pl.onTopic} of ${pl.total}")) }
            }
        }
        if (points.isNotEmpty()) {
            val title = tr?.optString("title").orEmpty()
            if (title.isNotBlank()) c.p("«$title»", 13f)
            c.p(when (Predict.trend(points)) {
                1 -> t("Динамика: YouTube всё чаще связывает ролик с целью.", "Trend: YouTube links the video with the target more and more.")
                -1 -> t("Динамика: связь с целью слабеет.", "Trend: the link with the target is weakening.")
                else -> if (points.size < 2) t("Нужна хотя бы ещё одна проверка, чтобы увидеть динамику.", "One more check is needed to see a trend.")
                else t("Динамика: без заметных изменений.", "Trend: no notable change.")
            })
            if (points.size >= 2) c.add(BarChartView(act).apply {
                values = points.takeLast(14).map { it.targetShare * 100 }
                labels = points.takeLast(14).map { Fmt.date(it.at).take(5) }
                contentDescription = t("Доля цели в «Следующее» по дням", "Target share in Up next by day")
            }, 8)
            for (pt in points.takeLast(6).reversed()) {
                c.p(Fmt.dateTime(pt.at) + (if (pt.views >= 0) " · ${Fmt.num(pt.views)} " + t("просм.", "views") else ""), 13f)
                c.muted(t("«Следующее»: по теме ${pt.onTopic} из ${pt.related}, не по теме ${pt.off}", "Up next: on topic ${pt.onTopic} of ${pt.related}, off topic ${pt.off}") +
                    (if (pt.top.isNotBlank()) t(" · больше всего «${pt.top}» ${pc(pt.topShare)}", " · mostly “${pt.top}” ${pc(pt.topShare)}") else "") +
                    t(" · цель ${pc(pt.targetShare)} · виден у ${pt.found} из ${pt.checked}", " · target ${pc(pt.targetShare)} · seen at ${pt.found} of ${pt.checked}"))
                for (w in pt.where.take(3)) {
                    val vid = w.substringBefore(':')
                    c.muted("  ↳ " + (a.nodes[vid]?.title ?: vid) + t(", место ", ", position ") + w.substringAfter(':'))
                }
            }
        }
    }
}

/** Draft score card: local score, checks, model, confidence; AI estimate where the data is thin. */
private fun ProjectActivity.fitCard(c: Col, a: Predict.Analysis, d: Predict.Draft, target: Int, store: JSONObject) {
    val f = Predict.fit(a, d, target)
    val tc = a.community(f.target)
    c.add(text(c.ctx, "${f.score}/100", Type.HEADLINE, bold = true), 0)
    c.p(when {
        f.score >= 70 -> t("Хорошее совпадение с целью", "Good match with the target")
        f.score >= 45 -> t("Частичное совпадение", "Partial match")
        else -> t("Слабое совпадение", "Weak match")
    } + (tc?.let { " · 🎯 ${it.label}" } ?: ""))
    c.muted(t("Тема: ближе к центру сообщества, чем ${pc(f.topic)} его роликов.", "Topic: closer to the community centre than ${pc(f.topic)} of its videos."))
    if (f.best != f.target && f.sims.isNotEmpty()) {
        val b = a.community(f.best)
        if (b != null) c.p(t("⚠ Черновик ближе к «${b.label}», чем к цели. YouTube может связать его с этим сообществом.", "⚠ The draft is closer to “${b.label}” than to the target. YouTube may link it there."), 13f)
    }
    f.checks.forEach { c.p(checkText(it, d), 13f) }
    if (f.missing.isNotEmpty()) {
        c.muted(t("Слова сообщества, которых нет в черновике:", "Community words missing from the draft:"))
        c.chips(f.missing.map { chip(c.ctx, it) })
    }
    val m = a.model
    if (f.modelP != null && m != null) {
        c.muted(t("Модель: ${pc(f.modelP)}, выше, чем у ${pc(f.modelRank ?: 0.0)} попавших в рекомендации", "Model: ${pc(f.modelP)}, above ${pc(f.modelRank ?: 0.0)} of recommended videos") +
            (if (m.reliable) "" else t(" (модель ненадёжна)", " (the model is unreliable)")))
    }
    c.p(t("Уверенность: ", "Confidence: ") + confText(f.confidence), 13f, color = if (f.confidence == 0) Palette.BAD else null)
    f.why.filter { it !in a.why }.forEach { c.muted("• " + whyText(it)) }
    // AI where the data is thin; a second opinion otherwise
    val label = if (f.confidence <= 1) t("🤖 Оценка ИИ (данных мало)", "🤖 AI estimate (thin data)") else t("🤖 Второе мнение ИИ", "🤖 AI second opinion")
    c.button(label, style = if (f.confidence <= 1) BtnStyle.TONAL else BtnStyle.OUTLINED) {
        predictAi(t("Оценка черновика", "Draft estimate"), 3500, { PredictAi.review(project, a, d, target) }) {}
    }
    val rv = store.optJSONObject("review")
    if (rv != null && rv.optInt("for") == d.toJson().toString().hashCode()) {
        val r = Predict.Review.from(rv)
        c.divider()
        c.p(t("Оценка ИИ (не данные, а суждение модели)", "AI estimate (a model's judgement, not data)"), Type.BODY, color = Palette.MUTED)
        c.p(t("Совпадение с интересом: ${r.topicFit}/100 · продолжение: ${r.continuation}/100 · кликабельность: ${r.click}/100",
            "Interest match: ${r.topicFit}/100 · follow-up: ${r.continuation}/100 · clickability: ${r.click}/100"), 13f)
        val dId = rv.optJSONArray("doors")?.optString(r.door - 1).orEmpty()
        if (r.door > 0 && dId.isNotBlank()) c.muted(t("Продолжает: ", "Follows: ") + (a.nodes[dId]?.title ?: dId))
        if (r.promise.isNotBlank()) c.muted(t("Обещание: ", "Promise: ") + r.promise)
        r.risks.forEach { c.muted("⚠ $it") }
        r.fixes.forEach { c.p("→ $it", 13f) }
        for (ti in r.titles) c.add(chip(c.ctx, ti) {
            val nd = d.copy(title = ti)
            ui["predDraft"] = nd
            ui["predScored"] = true
            PredictStore.edit(project.id) { it.put("draft", nd.toJson()) }
            render()
        }, 6)
        c.muted(t("Уверенность ИИ: ", "AI confidence: ") + when (r.confidence) {
            "high" -> t("высокая", "high")
            "medium" -> t("средняя", "medium")
            else -> t("низкая", "low")
        } + (if (r.why.isNotBlank()) " — ${r.why}" else ""))
    }
}

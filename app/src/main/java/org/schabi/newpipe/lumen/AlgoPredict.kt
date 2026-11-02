/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/**
 * "Chance to get into Up next" (ALGO_PREDICT.md): a logistic model on what is known at publication
 * (channel, fit to the topic, niche vocabulary, packaging, duration), trained on the topic's videos that did and
 * did not appear next to on-topic videos of the graph, validated by whole channels.
 */
object AlgoPredict {
    private const val DAY = 86_400_000L
    val FEATURES = listOf("log_subs", "subs_known", "channel_in_graph", "fit", "vocab", "log_duration", "short", "log_age") + TitleFeatures.NAMES
    private val CHANNEL_ONLY = listOf(0, 1, 2, 7)

    class Row(val id: String, val title: String, val channel: String, val x: DoubleArray, val y: Int)

    class Model(
        val logit: Logit?,
        val auc: Double,
        val aucBase: Double,
        val n: Int,
        val pos: Int,
        val calError: Double,
        val vocab: List<String>,
        val medians: DoubleArray
    ) {
        val usable get() = logit != null && n >= 60 && pos >= 10 && n - pos >= 10 && !auc.isNaN() && auc >= 0.65
        val uplift get() = if (auc.isNaN() || aucBase.isNaN()) Double.NaN else auc - aucBase
    }

    private val cache = HashMap<String, Pair<Long, Model>>()

    fun featureName(i: Int): String = when (FEATURES[i]) {
        "log_subs" -> t("размер канала", "channel size")
        "subs_known" -> t("известен размер канала", "channel size known")
        "channel_in_graph" -> t("канал уже в графе каналов темы", "channel already in the topic's channel graph")
        "fit" -> t("близость к теме", "closeness to the topic")
        "vocab" -> t("слова ниши в названии", "niche words in the title")
        "log_duration" -> t("длительность", "duration")
        "short" -> "Shorts"
        "log_age" -> t("возраст ролика", "video age")
        "title_len" -> t("длина названия", "title length")
        "number" -> t("число в названии", "number in the title")
        "question" -> t("вопрос в названии", "question in the title")
        "caps" -> t("доля КАПСА", "CAPS share")
        "emoji" -> t("эмодзи", "emoji")
        "brackets" -> t("скобки/кавычки", "brackets/quotes")
        "cyrillic" -> t("кириллица", "Cyrillic")
        else -> FEATURES[i]
    }

    /** Words typical of the core (central on-topic videos) against the whole graph. */
    fun vocab(r: AlgoResult, k: Int = 30): List<String> {
        val core = r.central.filter { r.nodes[it].relevant == 1 }.take(60).map { r.nodes[it].title }
        return TextSim.liftTerms(core, r.nodes.map { it.title }, k).map { it.first }
    }

    private fun chGraphOn(pid: String): Set<String> = try {
        RecGraph.load(pid, RecGraph.CHANNELS_FILE).nodes.values.filter { it.relevant == 1 }.map { it.id }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Raw features; NaN where unknown (filled with medians by [fill]). */
    fun raw(title: String, channelUrl: String, subs: Long, duration: Long, isShort: Boolean, ageDays: Double, fit: Double, vocab: List<String>, chOn: Set<String>): DoubleArray {
        val covered = TitleFeatures.covered(title, vocab).size
        return doubleArrayOf(
            if (subs >= 0) ln(1.0 + subs) else Double.NaN,
            if (subs >= 0) 1.0 else 0.0,
            if (channelUrl.isNotBlank() && ChannelStore.key(channelUrl) in chOn) 1.0 else 0.0,
            fit,
            minOf(covered, 5) / 5.0,
            if (duration > 0) ln(1.0 + duration) else Double.NaN,
            if (isShort) 1.0 else 0.0,
            if (ageDays.isNaN()) Double.NaN else ln(1.0 + max(0.0, ageDays))
        ) + TitleFeatures.of(title)
    }

    private fun fill(x: DoubleArray, med: DoubleArray) = DoubleArray(x.size) { if (x[it].isNaN()) med[it] else x[it] }

    /** Candidates: project videos, recent videos of known channels and graph nodes; label = shown next to an on-topic video. */
    fun dataset(pid: String, r: AlgoResult): Pair<List<Row>, DoubleArray> {
        val p = Store.project(pid) ?: return emptyList<Row>() to DoubleArray(FEATURES.size)
        val now = System.currentTimeMillis()
        val chans = ChannelStore.all(pid)
        val cands = LinkedHashMap<String, Video>()
        Store.videos(pid).values.filter { !isLocalSource(it.id) }.forEach { cands[it.id] = it }
        chans.values.forEach { c -> c.recent.forEach { v -> cands.putIfAbsent(v.id, v.copy(channelUrl = v.channelUrl.ifBlank { c.url }, channel = v.channel.ifBlank { c.name })) } }
        r.nodes.forEach { n -> cands.putIfAbsent(n.id, n.toVideo("rec")) }
        val positive = HashSet<String>()
        for (e in r.edges) if (r.nodes[e.a].relevant == 1 && r.nodes[e.a].expanded) positive.add(r.nodes[e.b].id)
        val seeds = r.nodes.filter { it.seed }.map { it.id }.toSet()
        val sc = AlgoJudge.scorer(p, TopicFilter.load(pid), cands.values.map { it.title })
        val voc = vocab(r)
        val chOn = chGraphOn(pid)
        val rows = cands.values.filter { it.id !in seeds && it.title.isNotBlank() && !(p.recs.excludeShorts && it.isShort) }.map { v ->
            val subs = v.subscribers.takeIf { it >= 0 } ?: chans[ChannelStore.key(v.channelUrl)]?.subscribers ?: -1L
            val age = if (v.uploadMs > 0) (now - v.uploadMs) / DAY.toDouble() else Double.NaN
            Row(v.id, v.title, v.channel.ifBlank { v.channelUrl }, raw(v.title, v.channelUrl, subs, v.duration, v.isShort, age, sc.score(v.title + " — " + v.channel), voc, chOn),
                if (v.id in positive) 1 else 0)
        }
        val med = DoubleArray(FEATURES.size) { j -> AlgoCore.median(rows.map { it.x[j] }.filter { !it.isNaN() }).let { m -> if (m.isNaN()) 0.0 else m } }
        return rows.map { Row(it.id, it.title, it.channel, fill(it.x, med), it.y) } to med
    }

    /** Trains (or returns the cached) model of the project. */
    fun model(pid: String, r: AlgoResult): Model {
        synchronized(cache) { cache[pid]?.takeIf { it.first == r.g.updatedAt }?.let { return it.second } }
        val (rows, med) = dataset(pid, r)
        val y = rows.map { it.y }.toIntArray()
        val x = rows.map { it.x }
        val groups = rows.map { it.channel }
        val pos = y.count { it == 1 }
        val m = if (rows.size < 30 || pos < 5 || rows.size - pos < 5) {
            Model(null, Double.NaN, Double.NaN, rows.size, pos, Double.NaN, vocab(r), med)
        } else {
            val oof = Logit.groupCv(x, y, groups)
            val base = Logit.groupCv(x.map { r2 -> DoubleArray(CHANNEL_ONLY.size) { r2[CHANNEL_ONLY[it]] } }, y, groups)
            Model(Logit.fit(x, y), Logit.auc(oof, y), Logit.auc(base, y), rows.size, pos, Logit.calibrationError(Logit.calibration(oof, y)), vocab(r), med)
        }
        synchronized(cache) { cache[pid] = r.g.updatedAt to m }
        return m
    }

    class Check(
        val niche: AlgoNiche?,
        val nicheSim: Double,
        val fit: Double,
        val covered: List<String>,
        val missing: List<String>,
        val durationNote: String,
        val prob: Double,
        val band: String,
        val reasons: List<Pair<String, Double>>,
        val model: Model
    )

    /** Checks an idea: niche, fit, vocabulary, duration and (when the model is good enough) the chance with reasons. */
    fun check(pid: String, r: AlgoResult, title: String, description: String, durationSec: Long, channelUrl: String, subs: Long): Check {
        val p = Store.project(pid) ?: throw IllegalStateException("no project")
        val text = "$title $description"
        val ts = TextSim(r.nodes.map { it.title } + text)
        val v = ts.vector(text)
        val best = r.niches.map { ni -> ni to TextSim.cosine(TextSim.centroid(ni.members.map { ts.vector(r.nodes[it].title) }), v) }.maxByOrNull { it.second }
        val niche = best?.first
        val m = model(pid, r)
        val nicheVocab = niche?.let { ni -> TextSim.liftTerms(ni.members.sortedByDescending { r.auth[it] }.take(40).map { r.nodes[it].title }, r.nodes.map { it.title }, 15).map { it.first } }.orEmpty()
        val voc = (nicheVocab + m.vocab).distinct()
        val covered = TitleFeatures.covered(title, voc)
        val sc = AlgoJudge.scorer(p, TopicFilter.load(pid), listOf(text))
        val fit = sc.score(text)
        val durNote = if (niche == null || niche.durLo <= 0 || durationSec <= 0) "" else when {
            durationSec < niche.durLo -> t("Короче обычного для ниши (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).", "Shorter than usual for the niche (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).")
            durationSec > niche.durHi -> t("Длиннее обычного для ниши (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).", "Longer than usual for the niche (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).")
            else -> t("Длительность как у ниши (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).", "Duration fits the niche (${Fmt.duration(niche.durLo)}–${Fmt.duration(niche.durHi)}).")
        }
        var prob = Double.NaN
        var reasons = emptyList<Pair<String, Double>>()
        if (m.usable && m.logit != null) {
            val x = fill(raw(title, channelUrl, subs, durationSec, durationSec in 1..60, 30.0, fit, m.vocab, chGraphOn(pid)), m.medians)
            prob = m.logit.predict(x)
            val c = m.logit.contributions(x)
            reasons = c.indices.filter { kotlin.math.abs(c[it]) > 0.15 }.sortedByDescending { kotlin.math.abs(c[it]) }.take(5).map { featureName(it) to c[it] }
        }
        val band = when {
            prob.isNaN() -> ""
            prob < 0.2 -> t("низкий", "low")
            prob < 0.5 -> t("средний", "medium")
            else -> t("высокий", "high")
        }
        return Check(niche, best?.second ?: 0.0, fit, covered, voc.filter { it !in covered }.take(8), durNote, prob, band, reasons, m)
    }

    /** Recommendation shares of fresh videos and of channels outside the top 10, per expanded on-topic hub (cached pages). */
    fun permeability(r: AlgoResult, k: Int = 8): List<Pair<Int, Double>> {
        val now = System.currentTimeMillis()
        val top10 = r.channels.sortedByDescending { it.inflow }.take(10).map { it.name }.toSet()
        return r.central.filter { r.nodes[it].expanded && r.nodes[it].relevant == 1 }.take(40).mapNotNull { i ->
            val page = WatchNext.last(r.nodes[i].id, r.g.gl) ?: return@mapNotNull null
            val items = page.items.take(20)
            if (items.size < 5) return@mapNotNull null
            val open = items.count { (it.uploadMs > 0 && now - it.uploadMs < 90 * DAY) || it.channel !in top10 }.toDouble() / items.size
            i to open
        }.sortedByDescending { it.second }.take(k)
    }

    class Pitch(val pitch: AlgoParse.Pitch, val names: Map<String, String>, val scored: List<Triple<String, Double, Double>>)

    /** AI pitch (algo_pitch) for [niche]; every title is scored by fit and originality (max similarity to existing titles). */
    fun pitch(p: Project, r: AlgoResult, niche: AlgoNiche?, idea: String): Pitch {
        val ids = AlgoData.Companion.Ids(r)
        val members = niche?.members ?: r.central.take(80)
        val voc = TextSim.liftTerms(members.sortedByDescending { r.auth[it] }.take(40).map { r.nodes[it].title }, r.nodes.map { it.title }, 20).map { it.first }
        val sb = StringBuilder()
        sb.append(t("СЛОВАРЬ НИШИ: ", "NICHE VOCABULARY: ")).append(voc.joinToString(", ")).append('\n')
        sb.append(t("ЦЕНТРАЛЬНЫЕ ВИДЕО НИШИ:", "CENTRAL VIDEOS OF THE NICHE:")).append('\n')
        members.sortedByDescending { r.auth[it] }.take(10).forEach { sb.append(ids.line(it)).append('\n') }
        niche?.let { sb.append(t("ДЛИТЕЛЬНОСТЬ РЕКОМЕНДОВАННЫХ (25–75%): ", "DURATION OF RECOMMENDED (25–75%): ")).append(Fmt.duration(it.durLo)).append("–").append(Fmt.duration(it.durHi)).append('\n') }
        val pains = Seo.insights(p.id).filter { it.optString("type") in setOf("pain", "question", "request", "objection") }.map { it.optString("statement") }.filter { it.isNotBlank() }
        if (pains.isNotEmpty()) sb.append(t("ВОПРОСЫ И БОЛИ ЗРИТЕЛЕЙ:", "VIEWERS' QUESTIONS AND PAINS:")).append('\n').append(pains.take(15).joinToString("\n") { "- $it" }).append('\n')
        val perm = permeability(r).filter { niche == null || r.comm[it.first] == niche.index }.ifEmpty { permeability(r) }
        if (perm.isNotEmpty()) {
            sb.append(t("ПРОНИЦАЕМЫЕ ХАБЫ (доля свежих и небольших каналов в их «Следующем»):", "PERMEABLE HUBS (share of fresh and small channels in their Up next):")).append('\n')
            perm.forEach { (i, s) -> sb.append(ids.line(i)).append(" · " + t("проницаемость", "permeability") + " ${Math.round(s * 100)}%").append('\n') }
        }
        val nodeIds = ids.names.keys.filter { it.startsWith("v") }.toSet()
        val prompt = Prompts.fill("algo_pitch", AlgoText.topicVars(p) + mapOf(
            "niche" to (niche?.label ?: p.phrase), "method" to AlgoMethod.graph(r.g), "idea" to idea.ifBlank { "-" }, "data" to sb.toString()
        ))
        val prov = AiTasks.requireProvider()
        val model = prov.smartModel.ifBlank { prov.fastModel }
        var res = Llm.complete(prov, model, ALGO_SYSTEM, prompt, json = true, maxTokens = 2500, task = "algo_pitch", pid = p.id)
        var parsed = AlgoParse.pitch(res.text, nodeIds)
        if (parsed.value == null) {
            res = Llm.complete(prov, model, ALGO_SYSTEM, prompt + AlgoParse.fixNote(parsed.problem), json = true, maxTokens = 2500, task = "algo_pitch", pid = p.id)
            parsed = AlgoParse.pitch(res.text, nodeIds)
        }
        val pitch = parsed.value ?: throw IllegalStateException(t("Ответ ИИ не прошёл проверку: ", "The AI answer failed the check: ") + parsed.problem)
        val sc = AlgoJudge.scorer(p, TopicFilter.load(p.id), pitch.titles)
        val existing = r.nodes.map { it.title }
        val ts = TextSim(existing + pitch.titles)
        val vex = existing.map(ts::vector)
        val scored = pitch.titles.map { title ->
            val v = ts.vector(title)
            Triple(title, sc.score(title), vex.maxOfOrNull { TextSim.cosine(it, v) } ?: 0.0)
        }.sortedByDescending { it.second - max(0.0, it.third - 0.7) }
        return Pitch(pitch, ids.names, scored)
    }
}

/**
 * Radar of the author's published video (algo_radar.json): once a day during monitoring, how YouTube files the
 * video (niche shares of its own "Up next"), next to which hubs it appears and at which place, and search positions.
 */
object AlgoRadar {
    private const val FILE = "algo_radar.json"
    private const val DAY = 86_400_000L
    const val DAYS = 30

    class Check(val at: Long, val niches: List<Pair<String, Double>>, val hubs: List<Pair<String, Int>>, val search: List<Pair<String, Int>>) {
        fun toJson(): JSONObject = JSONObject().put("at", at)
            .put("niches", JSONArray(niches.map { JSONArray().put(it.first).put(it.second) }))
            .put("hubs", JSONArray(hubs.map { JSONArray().put(it.first).put(it.second) }))
            .put("search", JSONArray(search.map { JSONArray().put(it.first).put(it.second) }))

        companion object {
            private fun pairsD(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.optDouble(1) } }
            private fun pairsI(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.optInt(1) } }
            fun from(o: JSONObject) = Check(o.optLong("at"), pairsD(o.optJSONArray("niches")), pairsI(o.optJSONArray("hubs")), pairsI(o.optJSONArray("search")))
        }
    }

    class Item(
        val id: String,
        var title: String,
        val addedAt: Long,
        val hubs: List<String>,
        val queries: List<String>,
        val prob: Double,
        val fit: Double,
        val checks: MutableList<Check> = mutableListOf()
    ) {
        val active get() = System.currentTimeMillis() - addedAt < DAYS * DAY
        val appeared get() = checks.any { it.hubs.isNotEmpty() }

        fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("addedAt", addedAt).put("hubs", JSONArray(hubs))
            .put("queries", JSONArray(queries)).put("prob", if (prob.isNaN()) -1.0 else prob).put("fit", fit)
            .put("checks", JSONArray(checks.takeLast(40).map { it.toJson() }))

        companion object {
            fun from(o: JSONObject): Item {
                val c = o.optJSONArray("checks") ?: JSONArray()
                return Item(
                    o.getString("id"), o.optString("title"), o.optLong("addedAt"), AlgoParse.strings(o.optJSONArray("hubs")),
                    AlgoParse.strings(o.optJSONArray("queries")), o.optDouble("prob", -1.0).let { if (it < 0) Double.NaN else it }, o.optDouble("fit"),
                    (0 until c.length()).map { Check.from(c.getJSONObject(it)) }.toMutableList()
                )
            }
        }
    }

    fun list(pid: String): List<Item> = try {
        val a = JSONArray(Store.readFile(pid, FILE) ?: "[]")
        (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(pid: String, items: List<Item>) = Store.writeFile(pid, FILE, JSONArray(items.map { it.toJson() }).toString())

    fun remove(pid: String, id: String) = save(pid, list(pid).filter { it.id != id })

    /** Starts tracking video [url]: hubs = strongest on-topic hubs + the most permeable ones; first check right away. */
    fun add(pid: String, url: String, r: AlgoResult): Item {
        val id = YtQuery.videoIdFromUrl(url) ?: throw IllegalArgumentException(t("Это не ссылка на видео", "Not a video link"))
        val p = Store.project(pid) ?: throw IllegalStateException("no project")
        val page = WatchNext.get(id, p.filters.country)
        val hubs = (r.central.filter { r.nodes[it].expanded && r.nodes[it].relevant == 1 }.take(10) + AlgoPredict.permeability(r, 5).map { it.first })
            .distinct().map { r.nodes[it].id }
        val check = try {
            AlgoPredict.check(pid, r, page.title, "", page.duration, page.channelUrl, page.subs)
        } catch (e: Exception) {
            null
        }
        val item = Item(id, page.title, System.currentTimeMillis(), hubs, p.effectiveQueries().take(3), check?.prob ?: Double.NaN, check?.fit ?: 0.0)
        save(pid, list(pid).filter { it.id != id } + item)
        checkOne(pid, item, r)
        save(pid, list(pid).map { if (it.id == id) item else it })
        return item
    }

    /** One check of [item]; returns hub ids where the video appeared for the first time. */
    fun checkOne(pid: String, item: Item, r: AlgoResult): List<String> {
        val p = Store.project(pid) ?: return emptyList()
        val gl = p.filters.country
        val own = WatchNext.get(item.id, gl, fresh = item.checks.lastOrNull()?.let { System.currentTimeMillis() - it.at > 20 * 3_600_000L } ?: true)
        if (own.title.isNotBlank()) item.title = own.title
        val shares = HashMap<String, Double>()
        own.items.take(20).forEach { w ->
            val label = r.ix[w.id]?.let { r.niche(it)?.label } ?: t("вне графа", "outside the graph")
            shares[label] = (shares[label] ?: 0.0) + 1.0
        }
        val total = shares.values.sum().takeIf { it > 0 } ?: 1.0
        val hubs = item.hubs.mapNotNull { h ->
            try {
                WatchNext.get(h, gl).items.firstOrNull { it.id == item.id }?.let { h to it.pos }
            } catch (e: Exception) {
                null
            }
        }
        val search = item.queries.mapNotNull { q ->
            try {
                val f = SearchFilters(limit = 20, country = gl)
                YouTube.search(listOf(q), f).indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { q to it + 1 }
            } catch (e: Exception) {
                null
            }
        }
        val before = item.checks.flatMap { c -> c.hubs.map { it.first } }.toSet()
        item.checks.add(Check(System.currentTimeMillis(), shares.entries.sortedByDescending { it.value }.map { it.key to it.value / total }, hubs, search))
        return hubs.map { it.first }.filter { it !in before }
    }

    /** Monitoring: checks every active item once a day and notifies about new appearances next to hubs. */
    fun monitor(ctx: Context, pid: String) {
        val items = list(pid)
        if (items.none { it.active }) return
        val r = AlgoAnalysis.get(pid)
        val now = System.currentTimeMillis()
        for (it in items.filter { it.active && (it.checks.lastOrNull()?.at ?: 0) < now - 20 * 3_600_000L }) {
            val fresh = try { checkOne(pid, it, r) } catch (e: Exception) { L.w("algo", "radar ${it.id}: ${e.message}"); emptyList() }
            if (fresh.isNotEmpty()) {
                val names = fresh.mapNotNull { h -> r.ix[h]?.let { i -> "«" + r.nodes[i].title.take(50) + "»" } }
                Notify.post(ctx, ("radar" + pid + it.id).hashCode(), t("🎯 Ролик в «Следующем»", "🎯 Video in Up next"),
                    t("«${it.title.take(60)}» теперь рядом с: ", "“${it.title.take(60)}” now next to: ") + names.joinToString(", "), pid)
            }
        }
        save(pid, items)
    }

    /** How the forecasts came true: (videos tracked ≥ 14 days, of them predicted ≥ 50% and appeared, appeared). */
    fun outcomes(pid: String): Triple<Int, Int, Int> {
        val done = list(pid).filter { System.currentTimeMillis() - it.addedAt >= 14 * DAY && !it.prob.isNaN() }
        val hit = done.count { (it.prob >= 0.5) == it.appeared }
        return Triple(done.size, hit, done.count { it.appeared })
    }
}

/** Monitoring follow-up of the Algorithm tab: notify when the graph core changed noticeably. */
object AlgoWatch {
    fun afterDrip(ctx: Context, pid: String) {
        val p = Store.project(pid) ?: return
        val d = AlgoAnalysis.lastDiff(pid) ?: return
        if (!d.significant) return
        val key = "algo_notified_$pid"
        if (Lumen.prefs.getLong(key, 0) >= d.to) return
        Lumen.prefs.edit().putLong(key, d.to).apply()
        Notify.post(ctx, ("algo" + pid).hashCode(), t("🧭 Что изменилось: ", "🧭 What changed: ") + p.name, AlgoAi.changes(p, d), pid)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/** Shared strings of the algo_* prompts. */
object AlgoText {
    fun lang() = if (Settings.lang == "en") "English" else "русский"

    /** Languages of the project's videos: the search filter, else by script of the titles. */
    fun langsOf(titles: List<String>, orig: String = ""): String {
        if (orig.isNotBlank()) return orig
        var cyr = 0
        var lat = 0
        titles.forEach { t ->
            if (t.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) cyr++ else if (t.any { it in 'a'..'z' || it in 'A'..'Z' }) lat++
        }
        return listOfNotNull("ru".takeIf { cyr > 0 }, "en".takeIf { lat > 0 }).ifEmpty { listOf("ru", "en") }.joinToString(", ")
    }

    fun join(l: List<String>) = l.joinToString("; ").ifBlank { "-" }

    /** {phrase} {core} {include} {exclude} {adjacent} {lang} from the project and its topic profile. */
    fun topicVars(p: Project, tp: TopicProfile? = TopicFilter.load(p.id)): Map<String, String> = mapOf(
        "phrase" to p.phrase.ifBlank { tp?.phrase.orEmpty() }.ifBlank { "-" },
        "core" to (tp?.domain ?: p.domain).ifBlank { p.domain }.ifBlank { "-" },
        "include" to join(tp?.include.orEmpty()),
        "exclude" to join(tp?.exclude.orEmpty()),
        "adjacent" to join(tp?.adjacent.orEmpty()),
        "lang" to lang()
    )
}

/** The user's own ✓/✗ marks on recommended videos (algo_labels.json: id → {v: 1|0, t: text}). */
object AlgoLabels {
    const val FILE = "algo_labels.json"

    class Mark(val v: Int, val text: String)

    fun load(pid: String): Map<String, Mark> = try {
        val o = JSONObject(Store.readFile(pid, FILE) ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Mark(it.optInt("v"), it.optString("t")) } }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Sets (1/0) or removes (null) the mark of node [n]. */
    fun set(pid: String, n: RecNode, v: Int?) {
        val o = try { JSONObject(Store.readFile(pid, FILE) ?: "{}") } catch (e: Exception) { JSONObject() }
        if (v == null) o.remove(n.id) else o.put(n.id, JSONObject().put("v", v).put("t", AlgoJudge.text(n)))
        Store.writeFile(pid, FILE, o.toString())
    }
}

/**
 * Topic filter of the Algorithm tab: every video gets a text score (TF-IDF against the topic profile); clear cases
 * are decided by the score, the doubtful zone by the channel's verdict in the channel graph and then by AI
 * (algo_judge_videos, grades 3..-1 with niche labels). Thresholds follow the user's ✓/✗ marks when there are enough.
 */
object AlgoJudge {
    const val LO = 0.25
    const val HI = 0.65
    const val CUT = 0.45

    fun text(n: RecNode) = n.title + " — " + n.channel

    class Cuts(val lo: Double, val hi: Double, val cut: Double, val fromLabels: Boolean)

    /** Builds the scorer of project [p] over [extra] texts (the ones about to be judged). */
    fun scorer(p: Project, tp: TopicProfile?, extra: List<String>): TopicScorer {
        val vids = Store.videos(p.id).values.filter { !isLocalSource(it.id) }
        val seeds = (vids.filter { it.marked }.ifEmpty { vids.sortedByDescending { it.views } }).take(40).map { it.title + " — " + it.channel }
        val profile = listOfNotNull(p.phrase.ifBlank { null }, p.domain.ifBlank { null }, tp?.domain) + tp?.include.orEmpty() + tp?.keywords.orEmpty() +
            p.queries + p.recs.keywords.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val anti = tp?.exclude.orEmpty() + tp?.negative.orEmpty()
        return TopicScorer(vids.take(400).map { it.title } + extra, profile, anti, seeds)
    }

    /** Thresholds: from the user's marks (best F1 ± 0.2) when there are enough, else the defaults. */
    fun cuts(pid: String, sc: TopicScorer): Cuts {
        val marks = AlgoLabels.load(pid).values.toList()
        val c = AlgoQuality.bestCut(marks.map { sc.score(it.text) }, marks.map { it.v }) ?: return Cuts(LO, HI, CUT, false)
        return Cuts((c - 0.2).coerceAtLeast(0.0), (c + 0.2).coerceAtMost(1.0), c, true)
    }

    /** Channel key → on topic (1) / off (0) from the channel graph and its exits. */
    fun channelPrior(pid: String): Map<String, Int> {
        val g = RecGraph.load(pid, RecGraph.CHANNELS_FILE)
        val out = HashMap<String, Int>()
        g.nodes.values.filter { !it.seed && it.relevant != -1 }.forEach { out[it.id] = it.relevant }
        g.exits.forEach { out[it.id] = 0 }
        return out
    }

    /** Judges the nodes of [list] whose relevance is unknown (mode off | keywords | ai). */
    fun videos(p: Project, list: List<RecNode>, mode: String, keywords: Set<String>, progress: (String) -> Unit = {}) {
        val todo = list.filter { it.relevant == -1 }
        if (todo.isEmpty()) return
        if (mode == "off") {
            todo.forEach { it.relevant = 1 }
            return
        }
        val tp = TopicFilter.load(p.id)
        val sc = scorer(p, tp, todo.map(::text))
        val cuts = cuts(p.id, sc)
        val kw = HashMap<String, Boolean>()
        todo.forEach {
            it.score = sc.score(text(it))
            kw[it.id] = Relevance.matches(keywords, text(it))
        }
        fun byScore(n: RecNode) = if (n.score >= cuts.cut || (kw[n.id] == true && n.score >= cuts.lo)) 1 else 0
        val prov = if (mode == "ai") Provider.active() else null
        if (prov == null) {
            todo.forEach { it.relevant = byScore(it) }
            return
        }
        val prior = try { channelPrior(p.id) } catch (e: Exception) { emptyMap() }
        val doubt = ArrayList<RecNode>()
        for (n in todo) {
            when {
                n.score >= cuts.hi -> n.relevant = 1
                n.score < cuts.lo && kw[n.id] != true -> n.relevant = 0
                else -> {
                    val pr = if (n.channelUrl.isBlank()) null else prior[ChannelStore.key(n.channelUrl)]
                    if (pr != null) n.relevant = pr else doubt.add(n)
                }
            }
        }
        if (doubt.isEmpty()) return
        val vars = AlgoText.topicVars(p, tp)
        val examples = Store.videos(p.id).values.filter { it.marked }.ifEmpty { Store.videos(p.id).values.sortedByDescending { it.views } }
            .take(5).joinToString("\n") { "- " + it.title + " — " + it.channel }.ifBlank { "-" }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        Par.each(doubt.chunked(30)) { _, batch ->
            try {
                val data = batch.mapIndexed { i, n -> "${i + 1}. ${n.title} — ${n.channel}" + (if (n.duration > 0) " · " + Fmt.duration(n.duration) else "") }.joinToString("\n")
                val prompt = Prompts.fill("algo_judge_videos", vars + mapOf("examples" to examples, "count" to batch.size.toString(), "data" to data))
                var r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt, json = true, maxTokens = 700, task = "algo_judge_videos", pid = p.id)
                var g = AlgoParse.grades(r.text, batch.size)
                if (g.value == null) {
                    r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt + AlgoParse.fixNote(g.problem), json = true, maxTokens = 700, task = "algo_judge_videos", pid = p.id)
                    g = AlgoParse.grades(r.text, batch.size)
                }
                val grades = g.value ?: throw IllegalStateException(g.problem)
                batch.forEachIndexed { i, n ->
                    n.tier = grades.s[i]
                    n.label = grades.labels[i + 1].orEmpty()
                    n.relevant = when {
                        n.tier >= 2 -> 1
                        n.tier >= 0 -> 0
                        else -> byScore(n)
                    }
                }
            } catch (e: Exception) {
                L.w("recs", "judge: ${e.message}")
                batch.forEach { it.relevant = byScore(it) }
            }
            progress(t("Фильтр по теме: ", "Topic filter: ") + minOf(doubt.size, done.incrementAndGet() * 30) + "/" + doubt.size)
        }
    }
}

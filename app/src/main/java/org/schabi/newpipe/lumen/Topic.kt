/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Domain of a project, confirmed by AI (or derived from keywords when AI is off). Stored in topic.json.
 * v2 adds the boundaries used by the Algorithm tab: core, include, exclude (looks alike but is not the topic),
 * adjacent niches and negative words; [key] ties the profile to the phrase and domain it was made for.
 */
data class TopicProfile(
    val phrase: String,
    val domain: String,
    val keywords: List<String>,
    val confirmed: Boolean,
    val at: Long = System.currentTimeMillis(),
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val adjacent: List<String> = emptyList(),
    val negative: List<String> = emptyList(),
    val key: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("phrase", phrase).put("domain", domain)
        .put("keywords", JSONArray(keywords)).put("confirmed", confirmed).put("at", at)
        .put("include", JSONArray(include)).put("exclude", JSONArray(exclude)).put("adjacent", JSONArray(adjacent))
        .put("negative", JSONArray(negative)).put("key", key)

    companion object {
        private fun list(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

        fun from(o: JSONObject) = TopicProfile(
            o.optString("phrase"), o.optString("domain"), list(o, "keywords"), o.optBoolean("confirmed"), o.optLong("at"),
            list(o, "include"), list(o, "exclude"), list(o, "adjacent"), list(o, "negative"), o.optString("key")
        )

        /** Which phrase and domain a profile belongs to. */
        fun keyOf(p: Project) = (p.phrase.trim() + "|" + p.domain.trim()).lowercase().hashCode().toString(16)
    }
}

/** Secondary topic filter for recommendation graphs, similar videos and related channels. */
object TopicFilter {
    // ---- pure helpers (unit-tested)

    /** Parses {"phrase","domain","keywords":[..]} from an AI reply; null when unusable. */
    fun parseTopic(text: String): TopicProfile? {
        val o = try { JSONObject(JsonText.firstObject(text) ?: return null) } catch (e: Exception) { return null }
        val domain = o.optString("domain").trim()
        if (domain.isBlank()) return null
        val kw = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        return TopicProfile(o.optString("phrase").trim(), domain, kw.take(25), true)
    }

    /** Numbers listed in {"relevant":[1,2]}; empty when the reply is not parseable. */
    fun parseIndexes(text: String): Set<Int> {
        val o = try { JSONObject(JsonText.firstObject(text) ?: return emptySet()) } catch (e: Exception) { return emptySet() }
        val a = o.optJSONArray("relevant") ?: return emptySet()
        return (0 until a.length()).map { a.optInt(it, -1) }.filter { it > 0 }.toSet()
    }

    /** True when the reply carried a "relevant" array at all (an empty array is a valid "none"). */
    fun hasVerdict(text: String): Boolean {
        val raw = JsonText.firstObject(text) ?: return false
        return try { JSONObject(raw).has("relevant") } catch (e: Exception) { false }
    }

    fun channelLine(i: Int, c: ChannelData): String {
        val titles = c.recent.take(4).joinToString("; ") { it.title.take(70) }
        return "$i. ${c.name} — ${c.description.replace('\n', ' ').take(160)} — $titles"
    }

    /** Keyword fallback: matches name, description, tags and recent titles. */
    fun channelMatches(keywords: Set<String>, c: ChannelData): Boolean =
        Relevance.matches(keywords, c.name + " " + c.description + " " + c.tags.joinToString(" ") + " " + c.recent.take(10).joinToString(" ") { it.title })

    /** Removes off-topic (relevant == 0) non-seed nodes and every edge touching them. Returns how many nodes were removed. */
    fun prune(g: RecGraph): Int {
        val drop = g.nodes.values.filter { it.relevant == 0 && !it.seed }.map { it.id }.toSet()
        if (drop.isEmpty()) return 0
        drop.forEach { g.nodes.remove(it) }
        g.edges.removeAll { it.from in drop || it.to in drop }
        g.rebuildKeys()
        g.recompute()
        return drop.size
    }

    // ---- profile (AI)

    fun load(pid: String): TopicProfile? = Store.readFile(pid, "topic.json")?.let { try { TopicProfile.from(JSONObject(it)) } catch (e: Exception) { null } }

    fun keywordsOf(p: Project, extra: String = ""): Set<String> {
        val t = load(p.id)
        return Relevance.keywords(p.phrase + " " + p.queries.joinToString(" ") + " " + p.domain + " " + extra + " " + (t?.keywords?.joinToString(" ") ?: "") + " " + (t?.domain ?: ""))
    }

    /**
     * Makes sure the project has a topic: AI reads the phrase/domain and the project's top videos and states the domain.
     * Fills Project.phrase / domain when empty. Without AI the topic stays unconfirmed (keyword filter is used).
     */
    fun ensure(pid: String): TopicProfile? {
        val p = Store.project(pid) ?: return null
        val old = load(pid)
        // a confirmed profile stays while the phrase and domain stay (old profiles without a key are adopted)
        if (old != null && old.confirmed && (old.key.isBlank() || old.key == TopicProfile.keyOf(p))) return old
        val prov = Provider.active() ?: return old
        val vids = Store.videos(pid).values.sortedByDescending { it.views }.take(12)
        if (p.phrase.isBlank() && p.domain.isBlank() && vids.isEmpty()) return null
        val data = vids.mapIndexed { i, v -> "${i + 1}. ${v.title} — ${v.channel}" + (if (v.tags.isNotEmpty()) " [" + v.tags.take(6).joinToString(", ") + "]" else "") }.joinToString("\n")
        return try {
            val prompt = Prompts.fill(
                "algo_topic", mapOf(
                    "phrase" to p.phrase.ifBlank { "-" }, "domain" to p.domain.ifBlank { "-" }, "data" to data.ifBlank { "-" },
                    "langs" to AlgoText.langsOf(vids.map { it.title }, p.filters.origLang), "lang" to AlgoText.lang()
                )
            )
            var r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt, json = true, maxTokens = 1500, task = "algo_topic", pid = pid)
            var parsed = AlgoParse.topic(r.text)
            if (parsed.value == null) {
                r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt + AlgoParse.fixNote(parsed.problem), json = true, maxTokens = 1500, task = "algo_topic", pid = pid)
                parsed = AlgoParse.topic(r.text)
            }
            val t = parsed.value ?: return old
            val tp = TopicProfile(t.phrase, t.core, t.keywords, true, System.currentTimeMillis(), t.include, t.exclude, t.adjacent, t.negative, TopicProfile.keyOf(p))
            Store.writeFile(pid, "topic.json", tp.toJson().toString())
            var changed = false
            if (p.phrase.isBlank() && tp.phrase.isNotBlank()) { p.phrase = tp.phrase; changed = true }
            if (p.domain.isBlank()) { p.domain = tp.domain; changed = true }
            if (changed) {
                Store.save(p)
                // the profile was made before phrase/domain were filled in: tie it to the saved values
                Store.writeFile(pid, "topic.json", tp.copy(key = TopicProfile.keyOf(p)).toJson().toString())
            }
            L.i("topic", "confirmed: ${tp.domain} kw=${tp.keywords.size} exclude=${tp.exclude.size}")
            tp
        } catch (e: Exception) {
            L.w("topic", "ensure failed: ${e.message}")
            old
        }
    }

    /** Grade of a channel: on topic or not, AI grade (3..-1, -2 = keywords) and a niche label for off-topic ones. */
    class ChVerdict(val on: Boolean, val tier: Int, val label: String)

    /** Judges channels against the project's topic. AI in batches (algo_judge_channels); keywords when AI is off or fails. */
    fun judgeChannelsGraded(p: Project, items: List<Pair<String, ChannelData>>, progress: (String) -> Unit = {}): Map<String, ChVerdict> {
        val out = HashMap<String, ChVerdict>()
        if (items.isEmpty()) return out
        val kw = keywordsOf(p)
        val prov = Provider.active()
        val topic = load(p.id)
        fun byKeywords(list: List<Pair<String, ChannelData>>, into: MutableMap<String, ChVerdict>) =
            list.forEach { (id, c) -> into[id] = ChVerdict(channelMatches(kw, c), -2, "") }
        if (prov == null) {
            byKeywords(items, out)
            return out
        }
        val batches = items.chunked(15)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val verdicts = Par.map(batches) { _, batch ->
            val res = HashMap<String, ChVerdict>()
            try {
                val data = batch.mapIndexed { i, (_, c) -> channelLine(i + 1, c) }.joinToString("\n")
                val prompt = Prompts.fill("algo_judge_channels", AlgoText.topicVars(p, topic) + mapOf("count" to batch.size.toString(), "data" to data))
                var r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt, json = true, maxTokens = 900, task = "algo_judge_channels", pid = p.id)
                var g = AlgoParse.grades(r.text, batch.size)
                if (g.value == null) {
                    r = Llm.complete(prov, prov.fastModel, ALGO_SYSTEM, prompt + AlgoParse.fixNote(g.problem), json = true, maxTokens = 900, task = "algo_judge_channels", pid = p.id)
                    g = AlgoParse.grades(r.text, batch.size)
                }
                val grades = g.value ?: throw IllegalStateException(g.problem)
                batch.forEachIndexed { i, (id, c) ->
                    val tier = grades.s[i]
                    val on = if (tier == -1) channelMatches(kw, c) else tier >= 2
                    res[id] = ChVerdict(on, tier, grades.labels[i + 1].orEmpty())
                }
            } catch (e: Exception) {
                L.w("topic", "channel judge: ${e.message}")
                byKeywords(batch, res)
            }
            progress(t("Фильтр по теме: ", "Topic filter: ") + minOf(items.size, finished.incrementAndGet() * 15) + "/" + items.size)
            res
        }
        verdicts.forEach { if (it != null) out.putAll(it) }
        return out
    }

    /** On-topic verdicts only (compatibility). */
    fun judgeChannels(p: Project, items: List<Pair<String, ChannelData>>, progress: (String) -> Unit = {}): Map<String, Boolean> =
        judgeChannelsGraded(p, items, progress).mapValues { it.value.on }
}

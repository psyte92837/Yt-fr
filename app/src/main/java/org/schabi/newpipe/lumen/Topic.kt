/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/** Domain of a project, confirmed by AI (or derived from keywords when AI is off). Stored in topic.json. */
data class TopicProfile(
    val phrase: String,
    val domain: String,
    val keywords: List<String>,
    val confirmed: Boolean,
    val at: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().put("phrase", phrase).put("domain", domain)
        .put("keywords", JSONArray(keywords)).put("confirmed", confirmed).put("at", at)

    companion object {
        fun from(o: JSONObject) = TopicProfile(
            o.optString("phrase"), o.optString("domain"),
            o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            o.optBoolean("confirmed"), o.optLong("at")
        )
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
        load(pid)?.takeIf { it.confirmed }?.let { return it }
        val p = Store.project(pid) ?: return null
        val prov = Provider.active() ?: return null
        val vids = Store.videos(pid).values.sortedByDescending { it.views }.take(12)
        if (p.phrase.isBlank() && p.domain.isBlank() && vids.isEmpty()) return null
        val data = vids.mapIndexed { i, v -> "${i + 1}. ${v.title} — ${v.channel}" + (if (v.tags.isNotEmpty()) " [" + v.tags.take(6).joinToString(", ") + "]" else "") }.joinToString("\n")
        return try {
            val prompt = Prompts.get("topic").replace("{phrase}", p.phrase.ifBlank { "-" }).replace("{domain}", p.domain.ifBlank { "-" })
                .replace("{data}", data.ifBlank { "-" }).replace("{lang}", if (Settings.lang == "en") "English" else "русский")
            val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 1200, task = "topic", pid = pid)
            val tp = parseTopic(r.text) ?: return null
            Store.writeFile(pid, "topic.json", tp.toJson().toString())
            var changed = false
            if (p.phrase.isBlank() && tp.phrase.isNotBlank()) { p.phrase = tp.phrase; changed = true }
            if (p.domain.isBlank()) { p.domain = tp.domain; changed = true }
            if (changed) Store.save(p)
            L.i("topic", "confirmed: ${tp.domain} kw=${tp.keywords.size}")
            tp
        } catch (e: Exception) {
            L.w("topic", "ensure failed: ${e.message}")
            null
        }
    }

    /** Judges channels against the project's topic. AI in batches; keywords when AI is off or fails. Returns id -> on topic. */
    fun judgeChannels(p: Project, items: List<Pair<String, ChannelData>>, progress: (String) -> Unit = {}): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        if (items.isEmpty()) return out
        val kw = keywordsOf(p)
        val prov = Provider.active()
        val topic = load(p.id)
        fun byKeywords(list: List<Pair<String, ChannelData>>) = list.forEach { (id, c) -> out[id] = channelMatches(kw, c) }
        if (prov == null) {
            byKeywords(items)
            return out
        }
        items.chunked(15).forEachIndexed { bi, batch ->
            progress(t("Фильтр по теме: ", "Topic filter: ") + (bi * 15 + batch.size) + "/" + items.size)
            try {
                val data = batch.mapIndexed { i, (_, c) -> channelLine(i + 1, c) }.joinToString("\n")
                val prompt = Prompts.get("relevance_channels").replace("{phrase}", p.phrase.ifBlank { topic?.phrase.orEmpty() })
                    .replace("{domain}", p.domain.ifBlank { topic?.domain ?: "-" }).replace("{data}", data)
                val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 800, task = "relevance_channels", pid = p.id)
                if (!hasVerdict(r.text)) throw IllegalStateException("no verdict")
                val yes = parseIndexes(r.text)
                batch.forEachIndexed { i, (id, _) -> out[id] = (i + 1) in yes }
            } catch (e: Exception) {
                L.w("topic", "channel judge: ${e.message}")
                byKeywords(batch)
            }
        }
        return out
    }
}

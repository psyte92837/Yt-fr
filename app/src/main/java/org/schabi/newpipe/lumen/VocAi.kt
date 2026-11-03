/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/**
 * Comments of a project read by the AI once ([VocCore], prompt voc_extract, fast model, minimal thinking) and kept
 * per batch in voc_items.json: the comment digest, the tone and the chat about comments take these items instead of
 * sending the raw comments again. New comments add only new batches.
 */
object VocAi {
    const val FILE = "voc_items.json"
    const val TASK = "voc_extract"
    private const val BATCH = 20_000

    fun lines(msgs: List<Pains.Msg>, limit: Int): List<VocCore.Line> {
        val out = ArrayList<VocCore.Line>()
        var size = 0
        for (m in Pains.ordered(msgs)) {
            val l = VocCore.Line(m.id, m.text, m.likes)
            size += VocCore.line(l).length + 1
            if (size > limit) break
            out += l
        }
        return out
    }

    /** Items of all comments within [limit] characters (the same comments the digest used to read raw). */
    fun items(pid: String, msgs: List<Pains.Msg>, limit: Int = Settings.chunkChars * 3, force: Boolean = false): List<VocCore.Item> {
        val prov = AiTasks.requireProvider()
        val batches = VocCore.batches(lines(msgs, limit), BATCH)
        val cache = (if (force) null else Store.readFile(pid, FILE))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val got = Par.mapAll(batches) { _, b ->
            val key = VocCore.key(b)
            cache.optJSONArray(key)?.let { return@mapAll key to VocCore.fromJson(it) }
            key to extract(prov, pid, b)
        }
        val keep = JSONObject()
        got.forEach { (k, list) -> keep.put(k, VocCore.toJson(list)) }
        Store.writeFile(pid, FILE, keep.toString())
        return got.flatMap { it.second }
    }

    private fun extract(prov: Provider, pid: String, b: List<VocCore.Line>): List<VocCore.Item> {
        val ids = b.map { it.id }.toSet()
        val lang = if (Settings.lang == "en") "English" else "русский"
        val prompt = Prompts.get(TASK).replace("{lang}", lang).replace("{data}", VocCore.input(b))
        return LlmJson.call(prov, prov.fastModel, Prompts.SYSTEM, prompt, TASK, pid, (1500 + b.size * 40).coerceAtMost(16_000),
            { VocCore.parse(it, ids) }, arrayKey = "i", hint = "{\"i\":[{\"t\",\"s\",\"ids\",\"sev\"}]}", schema = VocCore.SCHEMA, allowPartial = true).value
    }

    /** The items as a prompt block (grouped by type, the most supported first). */
    fun block(items: List<VocCore.Item>, msgs: List<Pains.Msg>, maxChars: Int): String =
        VocCore.block(items, msgs.associate { it.id to it.likes }, maxChars)

    fun byId(msgs: List<Pains.Msg>): Map<String, VocCore.Line> = msgs.associate { it.id to VocCore.Line(it.id, it.text, it.likes) }

    /** Items already extracted for these comments (no AI call); empty when the digest was never made. */
    fun cached(pid: String): List<VocCore.Item> =
        Store.readFile(pid, FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o -> o.keys().asSequence().flatMap { VocCore.fromJson(o.optJSONArray(it)) }.toList() }
            ?: emptyList()
}

val VOC_PROMPTS = linkedMapOf(
    VocAi.TASK to (
        "Below are YouTube viewer comments, one per line: [id ♥likes] text. Extract everything of value as short items.\n" +
            "Types: pain (problem, fear, irritation, unmet need), case (first-person story: who, what happened, what was tried, outcome), " +
            "question, objection (disagreement with the author or with each other), idea (request or suggestion for the author, product, next videos), " +
            "tip (advice, life hack, fact, link from a viewer), hypothesis (what people assume or believe explains things), " +
            "opinion (a position with its argument), praise, joke (humour, memes).\n" +
            "One item = one thought. Comments that say the same thing go into one item with all their ids. " +
            "s — the essence in at most 15 words in {lang}, do not copy the comment. sev — 1-5 for pain and case, otherwise 0. " +
            "Skip greetings, bare emotions without content and spam.\n" +
            "JSON: {\"i\":[{\"t\":\"pain\",\"s\":\"...\",\"ids\":[\"c3\",\"c17\"],\"sev\":4}]}\n\n{data}"
        )
)

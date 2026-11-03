/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/**
 * One JSON call for every feature (PROMPTS_PLAN 1A). A broken answer used to cost the whole input again (transcripts,
 * comments) up to three times. Now, in order: local repair (fences, comments, trailing commas) → the answer alone is
 * sent back to be fixed (no source data) → an answer cut by the output limit is continued from where it stopped →
 * only then the full request again. Another AI by hand ([Relay]) gets only full retries: every step there is the user's.
 */
object LlmJson {
    class Out<T>(val value: T, val text: String, val model: String, val inTokens: Int, val outTokens: Int, val partial: Boolean = false)

    fun <T : Any> call(
        p: Provider,
        model: String,
        system: String,
        prompt: String,
        task: String,
        pid: String,
        maxTokens: Int,
        parse: (String) -> T?,
        /** Array field that can be continued after a cut ("items", "scenes"…); null = no continuation. */
        arrayKey: String? = null,
        /** Short description of the expected object for the repair request ("{\"items\":[…]}"). */
        hint: String = "",
        schema: JSONObject? = null,
        /** Full requests after the first one. */
        fullRetries: Int = 1,
        /** Added to the prompt for a full retry; by default a request for compact valid JSON. */
        retryNote: (truncated: Boolean) -> String = { cut -> if (cut) NOTE_CUT else NOTE_BAD },
        /** When everything failed: the complete items of [arrayKey] as {"key":[…]} instead of an error. */
        allowPartial: Boolean = false,
        maxLimit: Int = 32_000
    ): Out<T> {
        val manual = p == Provider.MANUAL || Relay.routes(task)
        var inT = 0
        var outT = 0
        var lastModel = model
        fun ask(text: String, limit: Int, t: String = task, sys: String = system): LlmReply =
            Llm.complete(p, model, sys, text, json = true, maxTokens = limit, task = t, pid = pid, schema = schema).also {
                inT += it.inTokens
                outT += it.outTokens
                lastModel = it.model
            }
        fun ok(text: String): T? = runCatching { parse(text) }.getOrNull() ?: runCatching { parse(JsonFix.tidy(text)) }.getOrNull()
        fun done(v: T, text: String, partial: Boolean = false) = Out(v, text, lastModel, inT, outT, partial)

        var limit = maxTokens
        var request = prompt
        var lastText = ""
        for (round in 0..fullRetries) {
            val r = ask(request, limit)
            lastText = r.text
            ok(r.text)?.let { if (!r.truncated) return done(it, r.text) }
            if (!manual) {
                if (r.truncated && arrayKey != null) {
                    val got = JsonFix.items(r.text, arrayKey)
                    if (got.length() > 0) {
                        val more = runCatching { ask(JsonFix.continuePrompt(prompt, arrayKey, got), limit) }
                            .onFailure { if (it is java.util.concurrent.CancellationException) throw it }.getOrNull()
                        val merged = more?.let { JsonFix.merge(r.text, it.text, arrayKey) }
                        if (merged != null) {
                            lastText = merged
                            ok(merged)?.let { return done(it, merged, partial = more.truncated) }
                        }
                    }
                } else if (!r.truncated && !JsonFix.parses(r.text) && r.text.isNotBlank()) {
                    val fixed = runCatching {
                        ask(JsonFix.repairPrompt(r.text, "it is not valid JSON", hint), JsonFix.repairLimit(r.text), "json_fix",
                            "You repair broken JSON. Answer with JSON only.")
                    }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }.getOrNull()
                    fixed?.let { f -> ok(f.text)?.let { return done(it, f.text) } }
                }
            }
            // an answer cut by the limit that still parses is better than nothing on the last round
            if (round == fullRetries) ok(r.text)?.let { return done(it, r.text, partial = true) }
            L.i("llm", "$task: full retry ${round + 1} (${if (r.truncated) "cut at $limit" else "invalid JSON"})")
            if (r.truncated) limit = minOf(limit * 2, maxLimit)
            request = prompt + retryNote(r.truncated)
        }
        if (allowPartial && arrayKey != null) {
            JsonSalvage.array(lastText, arrayKey)?.let { s -> ok(s)?.let { return done(it, s, partial = true) } }
        }
        throw LlmException(t("ИИ не вернул ответ в нужном формате. Откройте «Ответы ИИ».", "AI did not answer in the expected format. See \"AI answers\"."), lastText)
    }

    const val NOTE_CUT = "\n\nThe previous answer was cut by the length limit. Answer more compactly: shorter texts, fewer items. Only JSON."
    const val NOTE_BAD = "\n\nThe previous answer was not valid JSON in the asked format. Return only JSON."

    /** Parser for "an object that has [key]". */
    fun withKey(key: String): (String) -> JSONObject? = { text ->
        JsonText.firstObject(text)?.let { JSONObject(it) }?.takeIf { it.has(key) }
    }
}

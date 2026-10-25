/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/** Pure parsers of provider responses (covered by unit tests). */
object LlmParse {
    fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optString("message").takeIf { it.isNotBlank() }
            ?: body
    } catch (e: Exception) {
        body
    }

    /** OpenAI-compatible /chat/completions (OpenAI, OpenRouter, DeepSeek, Groq, Ollama, LM Studio). */
    fun openAi(body: String, model: String, promptTokensEstimate: Int): LlmReply {
        val o = JSONObject(body)
        o.optJSONObject("error")?.let { throw LlmException(it.optString("message", "error")) }
        val choices = o.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw LlmException("empty response")
        val first = choices.getJSONObject(0)
        val msg = first.optJSONObject("message")
        val text = when (val c = msg?.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
            else -> ""
        }
        val u = o.optJSONObject("usage")
        return LlmReply(
            text,
            u?.optInt("prompt_tokens") ?: promptTokensEstimate,
            u?.optInt("completion_tokens") ?: Llm.estimateTokens(text),
            o.optString("model", model).ifBlank { model },
            first.optString("finish_reason") == "length"
        )
    }

    /** Anthropic Messages API: concatenates text blocks. */
    fun anthropic(body: String, model: String): LlmReply {
        val o = JSONObject(body)
        if (o.optString("type") == "error") throw LlmException(o.optJSONObject("error")?.optString("message") ?: "error")
        val content = o.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        val u = o.optJSONObject("usage")
        return LlmReply(sb.toString(), u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0, o.optString("model", model).ifBlank { model },
            o.optString("stop_reason") == "max_tokens")
    }

    /** Gemini generateContent: first candidate's text parts; blocked or empty → exception. */
    fun gemini(body: String, model: String): LlmReply {
        val o = JSONObject(body)
        val cands = o.optJSONArray("candidates")
        val sb = StringBuilder()
        if (cands != null && cands.length() > 0) {
            val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            if (ps != null) {
                for (i in 0 until ps.length()) {
                    val part = ps.getJSONObject(i)
                    if (!part.optBoolean("thought")) sb.append(part.optString("text"))
                }
            }
        }
        if (sb.isEmpty()) {
            val reason = o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
                ?: cands?.optJSONObject(0)?.optString("finishReason")?.takeIf { it.isNotBlank() }
                ?: "empty response"
            throw LlmException("Gemini: $reason")
        }
        val u = o.optJSONObject("usageMetadata")
        return LlmReply(sb.toString(), u?.optInt("promptTokenCount") ?: 0, u?.optInt("candidatesTokenCount") ?: 0, model,
            cands?.optJSONObject(0)?.optString("finishReason") == "MAX_TOKENS")
    }

    /** Model list: Gemini {models:[{name, supportedGenerationMethods}]}, others {data:[{id}]}. */
    fun models(body: String, gemini: Boolean): List<String> {
        val o = JSONObject(body)
        val out = mutableListOf<String>()
        if (gemini) {
            val a = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent")) out.add(m.optString("name").removePrefix("models/"))
            }
        } else {
            val a = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                out.add(m.optString("id").ifBlank { m.optString("name") })
            }
        }
        return out.filter { it.isNotBlank() }.distinct().sorted()
    }
}

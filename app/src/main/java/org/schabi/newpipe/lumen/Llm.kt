/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ApiKind { OPENAI_COMPAT, GEMINI, ANTHROPIC }

enum class Provider(
    val title: String,
    val kind: ApiKind,
    val defaultBase: String,
    val keyUrl: String,
    val defaultFast: String,
    val defaultSmart: String,
    val needsKey: Boolean = true
) {
    GEMINI("Google Gemini", ApiKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
        "https://aistudio.google.com/apikey", "gemini-3.5-flash-lite", "gemini-3.5-flash-lite"),
    OPENROUTER("OpenRouter", ApiKind.OPENAI_COMPAT, "https://openrouter.ai/api/v1",
        "https://openrouter.ai/keys", "openrouter/auto", "openrouter/auto"),
    OPENAI("OpenAI", ApiKind.OPENAI_COMPAT, "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys", "gpt-4o-mini", "gpt-4o"),
    ANTHROPIC("Anthropic Claude", ApiKind.ANTHROPIC, "https://api.anthropic.com/v1",
        "https://console.anthropic.com/settings/keys", "claude-haiku-5-5", "claude-sonnet-5-5"),
    DEEPSEEK("DeepSeek", ApiKind.OPENAI_COMPAT, "https://api.deepseek.com/v1",
        "https://platform.deepseek.com/api_keys", "deepseek-chat", "deepseek-chat"),
    GROQ("Groq", ApiKind.OPENAI_COMPAT, "https://api.groq.com/openai/v1",
        "https://console.groq.com/keys", "llama-3.1-8b-instant", "llama-3.3-70b-versatile"),
    OLLAMA("Ollama / LM Studio", ApiKind.OPENAI_COMPAT, "http://192.168.1.10:11434/v1",
        "https://ollama.com/download", "llama3.1", "llama3.1", needsKey = false),
    CUSTOM("Custom / Свой (OpenAI-compatible)", ApiKind.OPENAI_COMPAT, "https://",
        "", "", "", needsKey = false),

    /** Free tiers of many providers ([FreeAi]): keys in «Бесплатные ИИ», the best available model per call, failover on limits. */
    FREE("Бесплатные ИИ (авто) / Free AI (auto)", ApiKind.OPENAI_COMPAT, "free://auto", "", FreeAi.AUTO_FAST, FreeAi.AUTO_SMART, needsKey = false),

    /** No API: every request is copied to Gemini, ChatGPT, Claude… by hand and the answer is pasted back ([Relay]). */
    MANUAL("Другой ИИ вручную / Any AI by hand (Gemini, ChatGPT…)", ApiKind.OPENAI_COMPAT, "manual://copy-paste",
        "", "manual", "manual", needsKey = false);

    private fun pref(k: String) = "prov_${name}_$k"

    var baseUrl: String
        get() = Lumen.prefs.getString(pref("base"), defaultBase) ?: defaultBase
        set(v) = Lumen.prefs.edit().putString(pref("base"), v.trim().trimEnd('/')).apply()

    var fastModel: String
        get() = Lumen.prefs.getString(pref("fast"), defaultFast) ?: defaultFast
        set(v) = Lumen.prefs.edit().putString(pref("fast"), v.trim()).apply()

    var smartModel: String
        get() = Lumen.prefs.getString(pref("smart"), defaultSmart) ?: defaultSmart
        set(v) = Lumen.prefs.edit().putString(pref("smart"), v.trim()).apply()

    val apiKey: String? get() = KeyVault.get(name)

    val isConfigured: Boolean get() = (!needsKey || KeyVault.has(name)) && baseUrl.length > 10 && fastModel.isNotBlank()

    companion object {
        fun active(): Provider? {
            val p = entries.firstOrNull { it.name == Settings.activeProvider } ?: return null
            return if (p.isConfigured) p else null
        }
    }
}

/** [truncated] = the provider stopped because of the output token limit. */
data class LlmReply(
    val text: String, val inTokens: Int, val outTokens: Int, val model: String, val truncated: Boolean = false,
    /** Hidden reasoning tokens (already counted in [outTokens]) and prompt tokens served from the provider's cache. */
    val thoughtTokens: Int = 0, val cachedTokens: Int = 0
)

/** [raw] keeps the provider response body (shown in the AI journal). */
class LlmException(msg: String, val raw: String = "", val code: Int = 0) : IOException(msg) {
    /** Not fixable by a retry (region blocked, bad key, daily quota): callers stop instead of repeating or splitting. */
    val fatal: LlmErrors.Kind? get() = if (code == 0) null else LlmErrors.fatal(code, raw.ifBlank { message.orEmpty() })
}

/** Where a call goes: a configured [Provider] or one free model ([FreeAi]). */
data class LlmEndpoint(
    val kind: ApiKind,
    val base: String,
    val key: String,
    val openAiTokens: Boolean = false,
    val jsonMode: Boolean = true,
    val extra: Map<String, String> = emptyMap()
)

object Llm {
    fun estimateTokens(text: String): Int = TokenEstimate.of(text)

    fun estimateCost(inTok: Int, outTok: Int): Double =
        inTok / 1e6 * Settings.priceInPerM + outTok / 1e6 * Settings.priceOutPerM

    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val started = System.currentTimeMillis()
        val where = try {
            URL(url).let { it.host + it.path }
        } catch (e: Exception) {
            "?"
        }
        L.d("llm", "$method $where body=${body?.length ?: 0}ch")
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 300_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = try {
            c.responseCode
        } catch (e: Exception) {
            L.e("llm", "$method $where network error after ${System.currentTimeMillis() - started}ms: ${e.message}")
            throw e
        }
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        L.i("llm", "$method $where → $code in ${System.currentTimeMillis() - started}ms, ${text.length}ch")
        if (code !in 200..299) {
            L.e("llm", "$where error $code: ${text.take(500)}")
            val hint = when (LlmErrors.fatal(code, text)) {
                LlmErrors.Kind.REGION -> t(" — API недоступен из вашей страны: включите VPN (США/Европа) или выберите другого провайдера в Настройках → ИИ",
                    " — the API is not available in your country: use a VPN (US/Europe) or pick another provider in Settings → AI")
                LlmErrors.Kind.KEY -> t(" — ключ не принят: проверьте его в Настройках → ИИ", " — the key was rejected: check it in Settings → AI")
                LlmErrors.Kind.DAILY -> t(" — дневной лимит исчерпан, до завтра", " — daily limit used up until tomorrow")
                null -> ""
            }
            throw LlmException("HTTP $code: ${LlmParse.errorMessage(text).take(400)}$hint", text, code)
        }
        return text
    }

    private val openRouterHeaders = mapOf("HTTP-Referer" to "https://github.com/lumen-analyzer", "X-Title" to "Lumen")

    private fun endpoint(p: Provider) = LlmEndpoint(p.kind, p.baseUrl, p.apiKey.orEmpty(), openAiTokens = p == Provider.OPENAI,
        jsonMode = p != Provider.OLLAMA && p != Provider.CUSTOM, extra = if (p == Provider.OPENROUTER) openRouterHeaders else emptyMap())

    private fun authHeaders(e: LlmEndpoint): Map<String, String> = when (e.kind) {
        ApiKind.GEMINI -> mapOf("x-goog-api-key" to e.key)
        ApiKind.ANTHROPIC -> mapOf("x-api-key" to e.key, "anthropic-version" to "2023-06-01")
        ApiKind.OPENAI_COMPAT -> buildMap {
            if (e.key.isNotBlank()) put("Authorization", "Bearer ${e.key}")
            putAll(e.extra)
        }
    }

    /**
     * Single completion. [fileUri] is used only by Gemini (e.g. a YouTube URL for transcription); several links,
     * one per line, go as several videos of one request. [media]: fps, answer schema, no thinking (Gemini only).
     */
    fun complete(
        p: Provider,
        model: String,
        system: String,
        user: String,
        json: Boolean = false,
        fileUri: String? = null,
        maxTokens: Int = 4096,
        task: String = "",
        pid: String = "",
        lowMedia: Boolean = false,
        /** A picture sent with the message (base64 of a JPEG or PNG), for models that see images. */
        image: String? = null,
        imageMime: String = "image/jpeg",
        media: MediaOpts? = null,
        /** JSON schema of the answer (OpenAPI subset): Gemini responseSchema, OpenAI json_schema; others keep the prompt's format. */
        schema: JSONObject? = null,
        /** Minimal thinking (extraction, classification); null = by [LlmTasks.isLight]. */
        light: Boolean? = null
    ): LlmReply {
        val started = System.currentTimeMillis()
        val opts = (media ?: MediaOpts()).let { m ->
            m.copy(schema = m.schema ?: if (json) schema?.toString() else null, noThinking = m.noThinking || (light ?: LlmTasks.isLight(task)))
        }
        // another AI by hand: the request is copied out and the answer pasted back (no API, no key)
        if (task != "ping" && (p == Provider.MANUAL || Relay.routes(task))) return relay(task, pid, system, user, json, fileUri, image, imageMime, started)
        var raw: String? = null
        // the user sees the data that goes out and picks what to keep (SendReviewActivity)
        BgTasks.checkpoint()
        val out = try {
            BgTasks.waitingUser { SendGate.review(SendGate.Request(task.ifBlank { "ai" }, pid, p, model, system, user, fileUri, maxTokens)) }
        } catch (e: SendCancelled) {
            AiJournal.record(pid, task, p.name, model, system, user, "", "", 0, 0, "blocked", e.message, 0)
            throw e
        }
        val sent = out.user
        val file = out.fileUri
        try {
            val r = if (p == Provider.FREE) free(model, system, sent, json, file, maxTokens, lowMedia, image, imageMime, opts) { raw = it }
            else Par.retrying { completeRaw(endpoint(p), model, system, sent, json, file, maxTokens, lowMedia, image, imageMime, opts) { raw = it } }
            AiJournal.record(pid, task, p.name, r.model, system, sent, r.text, raw ?: "", r.inTokens, r.outTokens,
                if (r.truncated) "truncated" else "ok", null, System.currentTimeMillis() - started, file, r.thoughtTokens, r.cachedTokens)
            // stopped while waiting for the answer: the answer stays in the journal, the task ends here
            BgTasks.checkpoint()
            return r
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            AiJournal.record(pid, task, p.name, model, system, sent, "", raw ?: (e as? LlmException)?.raw ?: "", 0, 0,
                "error", e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started, file)
            throw e
        }
    }

    private fun relay(
        task: String, pid: String, system: String, user: String, json: Boolean, fileUri: String?, image: String?, imageMime: String, started: Long
    ): LlmReply {
        BgTasks.checkpoint()
        val text = try {
            BgTasks.waitingUser { Relay.ask(Relay.Request(task.ifBlank { "ai" }, pid, system, user, json, fileUri, image, imageMime)) }
        } catch (e: SendCancelled) {
            AiJournal.record(pid, task, Provider.MANUAL.name, "manual", system, user, "", "", 0, 0, "blocked", e.message, 0)
            throw e
        }
        val r = LlmReply(text, estimateTokens(system + user), estimateTokens(text), "manual")
        AiJournal.record(pid, task, Provider.MANUAL.name, "manual", system, user, text, text, r.inTokens, r.outTokens, "ok", null,
            System.currentTimeMillis() - started, fileUri)
        BgTasks.checkpoint()
        return r
    }

    /** Free AI: the best model with a key, then the next one on a limit, a key error or a failure ([FreeAi.MAX_TRIES] models). */
    private fun free(
        model: String, system: String, user: String, json: Boolean, fileUri: String?, maxTokens: Int, lowMedia: Boolean,
        image: String?, imageMime: String, media: MediaOpts?, onRaw: (String) -> Unit
    ): LlmReply {
        FreeAiStore.refreshIfStale()
        val cat = FreeAiStore.catalog()
        val need = FreeAi.Need(smart = model != FreeAi.AUTO_FAST, vision = image != null, video = fileUri != null,
            inTokens = estimateTokens(system + user), outTokens = maxTokens)
        val tries = mutableListOf<Pair<String, String>>()
        for (m in FreeAiStore.candidates(cat, need)) {
            BgTasks.checkpoint()
            val prov = cat.prov(m.p) ?: continue
            val e = FreeAiStore.endpoint(prov)
            try {
                val r = try {
                    completeRaw(e, m.id, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, media, onRaw)
                } catch (x: LlmException) {
                    // some free endpoints reject response_format: once more without it, the parsers still read JSON from text
                    if (json && x.code == 400 && e.kind == ApiKind.OPENAI_COMPAT) {
                        completeRaw(e.copy(jsonMode = false), m.id, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, media, onRaw)
                    } else throw x
                }
                FreeAiStore.ok(m, r.inTokens + r.outTokens)
                return r.copy(model = m.key)
            } catch (x: Exception) {
                if (x is java.util.concurrent.CancellationException) throw x
                val code = (x as? LlmException)?.code?.takeIf { it > 0 }
                val msg = x.message ?: x.javaClass.simpleName
                val f = FreeAi.classify(code, msg + " " + (x as? LlmException)?.raw.orEmpty().take(600))
                FreeAiStore.fail(m, f, msg)
                L.w("llm", "free ${m.key} → $f: ${msg.take(200)}")
                tries += m.key to msg
            }
        }
        throw LlmException(t("Бесплатные ИИ не ответили: ", "Free AI did not answer: ") + FreeAi.summary(tries, cat, FreeAiStore::has))
    }

    /** Checks one free provider with its fastest model (no project data goes out). */
    fun freeTest(provId: String): String {
        val cat = FreeAiStore.catalog()
        val prov = cat.prov(provId) ?: throw LlmException("?")
        val m = cat.models.filter { it.p == provId }.minByOrNull { it.speed * 3 + it.intel } ?: throw LlmException(t("нет моделей", "no models"))
        val r = completeRaw(FreeAiStore.endpoint(prov), m.id, "You are a test.", "Reply with the single word: OK", false, null, 16, false, null, "image/jpeg") {}
        FreeAiStore.ok(m, r.inTokens + r.outTokens)
        return m.name + ": " + r.text.trim().take(40)
    }

    private fun completeRaw(
        p: LlmEndpoint,
        model: String,
        system: String,
        user: String,
        json: Boolean,
        fileUri: String?,
        maxTokens: Int,
        lowMedia: Boolean,
        image: String?,
        imageMime: String,
        media: MediaOpts? = null,
        onRaw: (String) -> Unit
    ): LlmReply = try {
        completeRaw0(p, model, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, media, onRaw)
    } catch (e: LlmException) {
        // a model that does not take the schema or the thinking setting: once more without them (the prompt has the format)
        val extra = media != null && (media.schema != null || media.noThinking)
        if (e.code != 400 || !extra || e.fatal != null) throw e
        L.w("llm", "400 with schema/thinking options, retry without: ${e.message?.take(200)}")
        completeRaw0(p, model, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, media!!.copy(schema = null, noThinking = false), onRaw)
    }

    private fun completeRaw0(
        p: LlmEndpoint,
        model: String,
        system: String,
        user: String,
        json: Boolean,
        fileUri: String?,
        maxTokens: Int,
        lowMedia: Boolean,
        image: String?,
        imageMime: String,
        media: MediaOpts?,
        onRaw: (String) -> Unit
    ): LlmReply {
        val headers = authHeaders(p)
        return when (p.kind) {
            ApiKind.OPENAI_COMPAT -> {
                val body = JSONObject().put("model", model).put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", if (image == null) user else JSONArray()
                            .put(JSONObject().put("type", "text").put("text", user))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$imageMime;base64,$image")))))
                ).put(if (p.openAiTokens) "max_completion_tokens" else "max_tokens", maxTokens)
                if (json && p.jsonMode) {
                    val sch = media?.schema?.takeIf { p.openAiTokens }
                    body.put("response_format", if (sch != null) JSONObject().put("type", "json_schema")
                        .put("json_schema", JSONObject().put("name", "answer").put("schema", JSONObject(sch)).put("strict", false))
                    else JSONObject().put("type", "json_object"))
                }
                if (p.openAiTokens) LlmTasks.openAiEffort(model, media?.noThinking == true)?.let { body.put("reasoning_effort", it) }
                LlmParse.openAi(http("${p.base}/chat/completions", "POST", headers, body.toString()).also(onRaw), model, estimateTokens(system + user))
            }
            ApiKind.ANTHROPIC -> {
                // the system part repeats across a task's calls: long ones are cached by the provider (Anthropic needs the mark)
                val sys = JSONArray().put(JSONObject().put("type", "text").put("text", system).apply {
                    if (system.length > 3000) put("cache_control", JSONObject().put("type", "ephemeral"))
                })
                val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", sys)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", if (image == null) user else JSONArray()
                        .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", imageMime).put("data", image)))
                        .put(JSONObject().put("type", "text").put("text", user)))))
                LlmParse.anthropic(http("${p.base}/messages", "POST", headers, body.toString()).also(onRaw), model)
            }
            ApiKind.GEMINI -> {
                val parts = JSONArray()
                for (u in MediaOpts.links(fileUri)) {
                    val part = JSONObject().put("file_data", JSONObject().put("file_uri", u))
                    media?.fps?.takeIf { it > 0 }?.let { part.put("video_metadata", JSONObject().put("fps", it)) }
                    parts.put(part)
                }
                if (image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", imageMime).put("data", image)))
                parts.put(JSONObject().put("text", user))
                val gen = JSONObject().put("maxOutputTokens", maxTokens)
                if (json) gen.put("responseMimeType", "application/json")
                // ~3× fewer tokens per video frame
                if (lowMedia && fileUri != null) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                media?.schema?.let { gen.put("responseSchema", JSONObject(it)) }
                if (media?.noThinking == true) MediaOpts.thinking(model)?.let { gen.put("thinkingConfig", it) }
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", gen)
                LlmParse.gemini(http("${p.base}/models/$model:generateContent", "POST", headers, body.toString()).also(onRaw), model)
            }
        }
    }

    /** Gemini key for speech: the Gemini provider's, else the free-AI one. */
    fun geminiKey(): String? = Provider.GEMINI.apiKey?.takeIf { it.isNotBlank() } ?: FreeAiStore.key("google")?.takeIf { it.isNotBlank() }

    /**
     * Gemini text-to-speech: the raw answer with base64 audio ([GeminiVoice.audio] reads it).
     * Journaled like a completion; not sent through SendGate (the author's own script, read aloud).
     */
    fun speech(text: String, voice: String, model: String, style: String, task: String = "studio_tts_gemini", pid: String = ""): String {
        val key = geminiKey() ?: throw LlmException(t("Нужен ключ Gemini: Настройки → ИИ → Google Gemini.", "A Gemini key is needed: Settings → AI → Google Gemini."))
        val started = System.currentTimeMillis()
        BgTasks.checkpoint()
        val e = LlmEndpoint(ApiKind.GEMINI, Provider.GEMINI.baseUrl, key)
        // "Auto": the model that answered last time first, then the others of the chain
        val chain = GeminiVoice.chain(model).let { l -> speechOk?.takeIf { it in l }?.let { listOf(it) + (l - it) } ?: l }
        var last: Exception? = null
        for ((i, m) in chain.withIndex()) {
            try {
                return speechOne(e, m, text, voice, style, task, pid, started).also { if (chain.size > 1) speechOk = m }
            } catch (x: LlmException) {
                last = x
                if (i == chain.lastIndex || !GeminiVoice.tryNext(x.code, x.raw.ifBlank { x.message.orEmpty() })) throw x
                L.w("llm", "gemini tts $m → ${x.code}, next model")
            }
        }
        throw last ?: LlmException("Gemini returned no audio")
    }

    /** Voice model that answered last ("Auto" starts with it). */
    @Volatile private var speechOk: String? = null

    private fun speechOne(e: LlmEndpoint, model: String, text: String, voice: String, style: String, task: String, pid: String, started: Long): String {
        return try {
            val raw = Par.retrying { http("${e.base}/models/$model:generateContent", "POST", authHeaders(e), GeminiVoice.body(text, voice, style)) }
            val usage = runCatching { JSONObject(raw).optJSONObject("usageMetadata") }.getOrNull()
            AiJournal.record(pid, task, Provider.GEMINI.name, model, style, text, "♪ $voice", "", usage?.optInt("promptTokenCount") ?: 0,
                usage?.optInt("candidatesTokenCount") ?: 0, "ok", null, System.currentTimeMillis() - started)
            BgTasks.checkpoint()
            raw
        } catch (x: Exception) {
            if (x is java.util.concurrent.CancellationException) throw x
            AiJournal.record(pid, task, Provider.GEMINI.name, model, style, text, "", (x as? LlmException)?.raw ?: "", 0, 0,
                "error", x.message ?: x.javaClass.simpleName, System.currentTimeMillis() - started)
            throw x
        }
    }

    /** Azure Speech key and region (Studio → Voice → Azure). */
    fun azureKey(): String? = KeyVault.get(AzureSpeech.KEY)?.takeIf { it.isNotBlank() }

    private fun azureBytes(path: String, region: String, body: String?, extra: Map<String, String>): ByteArray {
        val key = azureKey() ?: throw LlmException(t("Нужен ключ Azure Speech.", "An Azure Speech key is needed."))
        val c = URL("https://${AzureSpeech.host(region)}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.setRequestProperty("Ocp-Apim-Subscription-Key", key)
        c.setRequestProperty("User-Agent", "Lumen")
        extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        if (code !in 200..299) {
            val msg = String(bytes).take(300)
            L.e("llm", "azure $path → $code: $msg")
            val hint = when (code) {
                401 -> t(" — ключ не принят: проверьте ключ и регион ресурса", " — the key was rejected: check the key and the resource's region")
                429 -> t(" — лимит Azure: бесплатный уровень даёт 20 запросов в минуту и 0,5 млн символов в месяц", " — Azure limit: the free tier allows 20 requests a minute and 0.5 million characters a month")
                400 -> t(" — голос не поддерживает этот стиль или язык", " — the voice does not support this style or language")
                else -> ""
            }
            throw LlmException("HTTP $code (Azure): $msg$hint", msg, code)
        }
        return bytes
    }

    /** Azure voices of the region (JSON array with styles); also checks the key. */
    fun azureVoices(region: String): String = String(Par.retrying { azureBytes("/cognitiveservices/voices/list", region, null, emptyMap()) })

    /** Azure text-to-speech: a 16-bit mono WAV of the [ssml]; journaled like an AI call. */
    fun azureSpeech(ssml: String, region: String, voice: String, chars: Int, task: String = "studio_tts_azure", pid: String = ""): ByteArray {
        val started = System.currentTimeMillis()
        BgTasks.checkpoint()
        return try {
            val wav = Par.retrying { azureBytes("/cognitiveservices/v1", region, ssml,
                mapOf("Content-Type" to "application/ssml+xml", "X-Microsoft-OutputFormat" to AzureSpeech.FORMAT)) }
            AiJournal.record(pid, task, "AZURE", voice, "", ssml, "♪ $chars", "", chars, 0, "ok", null, System.currentTimeMillis() - started)
            BgTasks.checkpoint()
            wav
        } catch (x: Exception) {
            if (x is java.util.concurrent.CancellationException) throw x
            AiJournal.record(pid, task, "AZURE", voice, "", ssml, "", (x as? LlmException)?.raw ?: "", 0, 0,
                "error", x.message ?: x.javaClass.simpleName, System.currentTimeMillis() - started)
            throw x
        }
    }

    /** Lists models available for the provider, where its API supports it. */
    fun listModels(p: Provider): List<String> =
        LlmParse.models(http("${p.baseUrl}/models", "GET", authHeaders(endpoint(p)), null), p.kind == ApiKind.GEMINI)

    fun ping(p: Provider): String {
        if (p == Provider.MANUAL) return t("OK — запросы будут копироваться в другой ИИ", "OK — requests will be copied to another AI")
        val r = complete(p, p.fastModel, "You are a test.", "Reply with the single word: OK", maxTokens = 16, task = "ping")
        return r.text.trim().take(60)
    }
}

/** Editable prompt templates. Placeholders: {phrase}, {domain}, {lang}, {data}, {question}. */
object Prompts {
    val defaults = linkedMapOf(
        "domain" to (
            "Пользователь хочет исследовать тему на YouTube: \"{phrase}\".\n" +
                "Определи 2-6 возможных доменных областей (значений) этой фразы. Для каждой дай краткое описание " +
                "и 3-5 уточнённых поисковых запросов для YouTube (включая синонимы и 1-2 запроса на английском).\n" +
                "Верни строго JSON: {\"domains\":[{\"name\":\"...\",\"description\":\"...\",\"queries\":[\"...\"]}]}"
            ),
        "video_summary" to (
            "Тема исследования: \"{phrase}\" ({domain}). Ниже расшифровка/данные видео.\n" +
                "Сделай сводку на языке {lang}: 3-7 ключевых тезисов, важные факты и цифры, тон автора, " +
                "насколько видео релевантно теме (0-10).\n\n{data}"
            ),
        "chunk_summary" to "Кратко перескажи этот фрагмент расшифровки на языке {lang}, сохрани факты и цифры:\n\n{data}",
        "project_summary" to (
            "Тема исследования: \"{phrase}\" ({domain}). Ниже сводки и метаданные собранных видео.\n" +
                "Сделай аналитический отчёт на языке {lang}: главные темы и нарративы, ключевые мнения и спикеры/каналы, " +
                "противоречия между источниками, новые тренды, что стоит изучить дальше. " +
                "У видео: возраст в днях, просмотры, /day — просмотров в день, ×N ch — во сколько раз быстрее обычного для своего канала: " +
                "что «заходит», суди по /day и ×ch, а не по сырым просмотрам. До 6 пунктов в разделе, пункт — до 30 слов.\n\n{data}"
            ),
        "clusters" to (
            "Сгруппируй видео ниже в 3-8 тематических кластеров по теме \"{phrase}\". Для каждого кластера: название, " +
                "описание в 1-2 предложения и список номеров видео. Ответ на языке {lang}, в Markdown.\n\n{data}"
            ),
        "sentiment" to (
            "Проанализируй тональность комментариев к видео по теме \"{phrase}\". Ниже мысли из комментариев по типам " +
                "(«- [×сколько комментариев ♥лайки] суть (id)») и число комментариев каждого типа. Дай: доли позитив/негатив/нейтрально " +
                "в процентах (считай по числам комментариев), основные претензии, основные похвалы, частые вопросы зрителей, " +
                "примеры формулировок своими словами. До 6 пунктов в разделе. Ответ на языке {lang}.\n\n{data}"
            ),
        "relevance" to (
            "Тема исследования: \"{phrase}\" ({domain}). Ниже пронумерованный список видео (название — канал).\n" +
                "Оставь только видео, которые прямо относятся к этой теме и доменной области. Смежные, развлекательные и " +
                "просто популярные видео без связи с темой НЕ включай. Если сомневаешься — не включай. " +
                "Верни строго JSON: {\"relevant\":[номера]}\n\n{data}"
            ),
        "watch_translate" to (
            "Тема мониторинга YouTube: \"{phrase}\" ({domain}). Исходные поисковые запросы:\n{data}\n\n" +
                "Для каждого языка из списка ({langs}) дай 1-2 коротких поисковых запроса так, как эту тему ищут носители языка. " +
                "Используй принятые в языке термины, бренды и имена не переводи. " +
                "Верни строго JSON: {\"queries\":{\"en\":[\"...\"],\"es\":[\"...\"]}}"
            ),
        "watch_digest" to (
            "Тема мониторинга: \"{phrase}\" ({domain}). Ниже {count} новых видео на YouTube (номер. [язык] название — канал. описание), " +
                "видео могут быть на разных языках.\nНапиши на языке {lang} краткую сводку для уведомления, без вступлений: " +
                "одна строка — о чём в целом новые видео; затем 2-5 строк вида «• подтема — N видео (языки)». " +
                "Отметь заметные новости или события, если они есть. Только факты из списка, не больше 600 символов.\n\n{data}"
            ),
        "channel_summary" to (
            "Проанализируй YouTube-канал по данным ниже. Язык ответа: {lang}.\n" +
                "Дай: о чём канал и для кого; основные рубрики и форматы; что заходит лучше всего (по просмотрам) и почему — как гипотеза; " +
                "частота и регулярность выпусков; монетизация и ссылки (по описанию); сильные и слабые стороны; 3–5 идей контента. " +
                "Не выдумывай цифры, используй только переданные. До 6 пунктов в разделе.\n\n{data}"
            ),
        "video_style" to (
            "Определи стиль видеоряда и монтажа YouTube-видео. {mode}\n" +
                "Ответ на языке {lang}, в Markdown, кратко и конкретно, с таймкодами где можно:\n" +
                "1. Формат и жанр (говорящая голова, скринкаст, влог, обзор, анимация, нарезка…).\n" +
                "2. Структура по времени: хук, вступление, основные блоки, призыв к действию, концовка.\n" +
                "3. Монтаж и видеоряд: планы, частота склеек, перебивки и b-roll, графика и текст на экране, переходы, цвет.\n" +
                "4. Звук и подача: темп речи, интонация, музыка и эффекты.\n" +
                "5. Обложка и заголовок: приём и формула.\n" +
                "6. Приёмы удержания внимания.\n" +
                "7. Шаблон для повторения: монтажный лист по секундам для видео такой же длины.\n" +
                "8. Чек-лист из 5–10 пунктов для своего видео.\n" +
                "Каждый раздел — до 6 пунктов по одной строке. Измеренные метрики приблизительны; не выдумывай цифры.\n\n{data}"
            ),
        "pains" to (
            "Ниже комментарии зрителей YouTube, формат строки: [id ♥лайки] текст. Найди боли людей и их реальные кейсы.\n" +
                "Боль — повторяющаяся проблема, страх, раздражение или неудовлетворённая потребность. Кейс — конкретная история " +
                "человека от первого лица: кто он, что случилось, что пробовал, чем кончилось, что ему нужно.\n" +
                "Пиши на языке {lang}. Цитаты не переписывай: в quotes и quote укажи только id самых показательных комментариев, " +
                "текст приложение возьмёт само. В ids перечисли все комментарии, где встречается боль.\n" +
                "Верни JSON:\n" +
                "{\"summary\":\"3-5 предложений: кто эти люди и что их болит\"," +
                "\"pains\":[{\"title\":\"коротко\",\"detail\":\"суть в 1-2 предложениях\",\"severity\":1-5," +
                "\"ids\":[\"c1\"],\"quotes\":[\"c1\"]}]," +
                "\"cases\":[{\"who\":\"кто (без имени)\",\"situation\":\"что случилось\",\"tried\":\"что пробовал\"," +
                "\"outcome\":\"чем кончилось\",\"need\":\"что нужно\",\"quote\":\"c2\"}]," +
                "\"questions\":[\"частый вопрос\"],\"objections\":[\"возражение\"],\"ideas\":[\"что сделать автору или продукту\"]}\n" +
                "До 12 болей (самые частые и острые первыми) и до 10 кейсов.\n\n{data}"
            ),
        "chat" to (
            "Ты аналитик. Отвечай на вопрос только на основе данных проекта по теме \"{phrase}\". " +
                "Если данных недостаточно — скажи об этом. Язык ответа: {lang}.\n\nДанные:\n{data}\n\nВопрос: {question}"
            )
    )

    init {
        defaults.putAll(SEO_PROMPTS)
        defaults.putAll(DOSSIER_PROMPTS)
        defaults.putAll(CUSTOM_REPORT_PROMPTS)
        defaults.putAll(DIAGRAM_PROMPTS)
        defaults.putAll(STUDIO_PROMPTS)
        defaults.putAll(KMAP_PROMPTS)
        defaults.putAll(BOT_PROMPTS)
        defaults.putAll(MINIAPP_PROMPTS)
        defaults.putAll(POST_PROMPTS)
        defaults.putAll(IDEAS_PROMPTS)
        defaults.putAll(LANGGAME_PROMPTS)
        defaults.putAll(ALGO_PROMPTS)
        defaults.putAll(COMMENT_DIGEST_PROMPTS)
        defaults.putAll(PREDICT_PROMPTS)
        defaults.putAll(VOC_PROMPTS)
        defaults.putAll(PLAYER_PROMPTS)
    }

    fun get(key: String): String = Lumen.prefs.getString("prompt_$key", null) ?: defaults[key].orEmpty()

    fun set(key: String, value: String) {
        if (value.isBlank() || value == defaults[key]) {
            Lumen.prefs.edit().remove("prompt_$key").apply()
        } else {
            Lumen.prefs.edit().putString("prompt_$key", value).apply()
        }
    }

    fun fill(key: String, vars: Map<String, String>): String {
        var s = get(key)
        vars.forEach { (k, v) -> s = s.replace("{$k}", v) }
        return s
    }

    const val SYSTEM = "You are a precise research analyst. Use only the provided data. Never invent facts."
}

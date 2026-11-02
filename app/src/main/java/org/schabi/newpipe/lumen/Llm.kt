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
data class LlmReply(val text: String, val inTokens: Int, val outTokens: Int, val model: String, val truncated: Boolean = false)

/** [raw] keeps the provider response body (shown in the AI journal). */
class LlmException(msg: String, val raw: String = "", val code: Int = 0) : IOException(msg)

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
    fun estimateTokens(text: String): Int = (text.length / 3.6).toInt() + 1

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
            throw LlmException("HTTP $code: ${LlmParse.errorMessage(text).take(400)}", text, code)
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
     * Single completion. [fileUri] is used only by Gemini (e.g. a YouTube URL for transcription).
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
        imageMime: String = "image/jpeg"
    ): LlmReply {
        val started = System.currentTimeMillis()
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
            val r = if (p == Provider.FREE) free(model, system, sent, json, file, maxTokens, lowMedia, image, imageMime) { raw = it }
            else Par.retrying { completeRaw(endpoint(p), model, system, sent, json, file, maxTokens, lowMedia, image, imageMime) { raw = it } }
            AiJournal.record(pid, task, p.name, r.model, system, sent, r.text, raw ?: "", r.inTokens, r.outTokens,
                if (r.truncated) "truncated" else "ok", null, System.currentTimeMillis() - started, file)
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
        image: String?, imageMime: String, onRaw: (String) -> Unit
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
                    completeRaw(e, m.id, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, onRaw)
                } catch (x: LlmException) {
                    // some free endpoints reject response_format: once more without it, the parsers still read JSON from text
                    if (json && x.code == 400 && e.kind == ApiKind.OPENAI_COMPAT) {
                        completeRaw(e.copy(jsonMode = false), m.id, system, user, json, fileUri, maxTokens, lowMedia, image, imageMime, onRaw)
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
                    body.put("response_format", JSONObject().put("type", "json_object"))
                }
                LlmParse.openAi(http("${p.base}/chat/completions", "POST", headers, body.toString()).also(onRaw), model, estimateTokens(system + user))
            }
            ApiKind.ANTHROPIC -> {
                val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", if (image == null) user else JSONArray()
                        .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", imageMime).put("data", image)))
                        .put(JSONObject().put("type", "text").put("text", user)))))
                LlmParse.anthropic(http("${p.base}/messages", "POST", headers, body.toString()).also(onRaw), model)
            }
            ApiKind.GEMINI -> {
                val parts = JSONArray()
                if (fileUri != null) {
                    parts.put(JSONObject().put("file_data", JSONObject().put("file_uri", fileUri)))
                }
                if (image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", imageMime).put("data", image)))
                parts.put(JSONObject().put("text", user))
                val gen = JSONObject().put("maxOutputTokens", maxTokens)
                if (json) gen.put("responseMimeType", "application/json")
                // ~3× fewer tokens per video frame
                if (lowMedia && fileUri != null) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", gen)
                LlmParse.gemini(http("${p.base}/models/$model:generateContent", "POST", headers, body.toString()).also(onRaw), model)
            }
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
                "противоречия между источниками, новые тренды, что стоит изучить дальше.\n\n{data}"
            ),
        "clusters" to (
            "Сгруппируй видео ниже в 3-8 тематических кластеров по теме \"{phrase}\". Для каждого кластера: название, " +
                "описание в 1-2 предложения и список номеров видео. Ответ на языке {lang}, в Markdown.\n\n{data}"
            ),
        "sentiment" to (
            "Проанализируй тональность комментариев к видео по теме \"{phrase}\". Дай: доли позитив/негатив/нейтрально " +
                "в процентах, основные претензии, основные похвалы, частые вопросы зрителей, примеры формулировок " +
                "своими словами. Ответ на языке {lang}.\n\n{data}"
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
                "Не выдумывай цифры, используй только переданные.\n\n{data}"
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
                "Измеренные метрики приблизительны; не выдумывай цифры.\n\n{data}"
            ),
        "pains" to (
            "Ниже комментарии зрителей YouTube, формат строки: [id ♥лайки] текст. Найди боли людей и их реальные кейсы.\n" +
                "Боль — повторяющаяся проблема, страх, раздражение или неудовлетворённая потребность. Кейс — конкретная история " +
                "человека от первого лица: кто он, что случилось, что пробовал, чем кончилось, что ему нужно.\n" +
                "Пиши на языке {lang}. Цитаты (text) копируй дословно из комментария с указанным id, не перефразируй. " +
                "В ids перечисли все комментарии, где встречается боль. Не выдумывай того, чего нет в комментариях.\n" +
                "Верни строго JSON без пояснений:\n" +
                "{\"summary\":\"3-5 предложений: кто эти люди и что их болит\"," +
                "\"pains\":[{\"title\":\"коротко\",\"detail\":\"суть в 1-2 предложениях\",\"severity\":1-5," +
                "\"ids\":[\"c1\"],\"quotes\":[{\"id\":\"c1\",\"text\":\"дословно\"}]}]," +
                "\"cases\":[{\"who\":\"кто (без имени)\",\"situation\":\"что случилось\",\"tried\":\"что пробовал\"," +
                "\"outcome\":\"чем кончилось\",\"need\":\"что нужно\",\"quote\":{\"id\":\"c2\",\"text\":\"дословно\"}}]," +
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

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
        "https://aistudio.google.com/apikey", "gemini-2.5-flash", "gemini-2.5-pro"),
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
        "", "", "", needsKey = false);

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
class LlmException(msg: String, val raw: String = "") : IOException(msg)

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
            throw LlmException("HTTP $code: ${LlmParse.errorMessage(text).take(400)}", text)
        }
        return text
    }

    private fun authHeaders(p: Provider): Map<String, String> {
        val key = p.apiKey.orEmpty()
        return when (p.kind) {
            ApiKind.GEMINI -> mapOf("x-goog-api-key" to key)
            ApiKind.ANTHROPIC -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            ApiKind.OPENAI_COMPAT -> buildMap {
                if (key.isNotBlank()) put("Authorization", "Bearer $key")
                if (p == Provider.OPENROUTER) {
                    put("HTTP-Referer", "https://github.com/lumen-analyzer")
                    put("X-Title", "Lumen")
                }
            }
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
        lowMedia: Boolean = false
    ): LlmReply {
        val started = System.currentTimeMillis()
        var raw: String? = null
        try {
            val r = completeRaw(p, model, system, user, json, fileUri, maxTokens, lowMedia) { raw = it }
            AiJournal.record(pid, task, p.name, r.model, system, user, r.text, raw ?: "", r.inTokens, r.outTokens,
                if (r.truncated) "truncated" else "ok", null, System.currentTimeMillis() - started)
            return r
        } catch (e: Exception) {
            AiJournal.record(pid, task, p.name, model, system, user, "", raw ?: (e as? LlmException)?.raw ?: "", 0, 0,
                "error", e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
            throw e
        }
    }

    private fun completeRaw(
        p: Provider,
        model: String,
        system: String,
        user: String,
        json: Boolean,
        fileUri: String?,
        maxTokens: Int,
        lowMedia: Boolean,
        onRaw: (String) -> Unit
    ): LlmReply {
        val headers = authHeaders(p)
        return when (p.kind) {
            ApiKind.OPENAI_COMPAT -> {
                val body = JSONObject().put("model", model).put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                ).put(if (p == Provider.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
                if (json && p != Provider.OLLAMA && p != Provider.CUSTOM) {
                    body.put("response_format", JSONObject().put("type", "json_object"))
                }
                LlmParse.openAi(http("${p.baseUrl}/chat/completions", "POST", headers, body.toString()).also(onRaw), model, estimateTokens(system + user))
            }
            ApiKind.ANTHROPIC -> {
                val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                LlmParse.anthropic(http("${p.baseUrl}/messages", "POST", headers, body.toString()).also(onRaw), model)
            }
            ApiKind.GEMINI -> {
                val parts = JSONArray()
                if (fileUri != null) {
                    parts.put(JSONObject().put("file_data", JSONObject().put("file_uri", fileUri)))
                }
                parts.put(JSONObject().put("text", user))
                val gen = JSONObject().put("maxOutputTokens", maxTokens)
                if (json) gen.put("responseMimeType", "application/json")
                // ~3× fewer tokens per video frame
                if (lowMedia && fileUri != null) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", gen)
                LlmParse.gemini(http("${p.baseUrl}/models/$model:generateContent", "POST", headers, body.toString()).also(onRaw), model)
            }
        }
    }

    /** Lists models available for the provider, where its API supports it. */
    fun listModels(p: Provider): List<String> =
        LlmParse.models(http("${p.baseUrl}/models", "GET", authHeaders(p), null), p.kind == ApiKind.GEMINI)

    fun ping(p: Provider): String {
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
        "relevance_channels" to (
            "Тема исследования: \"{phrase}\" ({domain}). Ниже пронумерованный список YouTube-каналов " +
                "(название — описание — последние видео).\nОставь только каналы, которые систематически работают в этой теме " +
                "и доменной области. Общие и развлекательные каналы, у которых тема встречается случайно, НЕ включай. " +
                "Если сомневаешься — не включай. Верни строго JSON: {\"relevant\":[номера]}\n\n{data}"
            ),
        "topic" to (
            "Определи доменную область исследования YouTube. Фраза: \"{phrase}\"; указанная область: {domain}.\n" +
                "Ниже самые просматриваемые видео проекта (название — канал [теги]):\n{data}\n\n" +
                "Сформулируй тему по фактам из списка, ничего не выдумывай. Верни строго JSON: " +
                "{\"phrase\":\"короткая тема 2-6 слов\",\"domain\":\"доменная область одним предложением\"," +
                "\"keywords\":[\"10-20 ключевых слов и словоформ темы, RU и EN\"]}. Язык значений: {lang}."
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
        "chat" to (
            "Ты аналитик. Отвечай на вопрос только на основе данных проекта по теме \"{phrase}\". " +
                "Если данных недостаточно — скажи об этом. Язык ответа: {lang}.\n\nДанные:\n{data}\n\nВопрос: {question}"
            )
    )

    init {
        defaults.putAll(SEO_PROMPTS)
        defaults.putAll(DOSSIER_PROMPTS)
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

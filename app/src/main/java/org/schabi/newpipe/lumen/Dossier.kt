/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Editable prompts of the AI dossier (added to [Prompts.defaults]). */
val DOSSIER_PROMPTS = linkedMapOf(
    "dossier_channel" to (
        "Тема исследования: \"{phrase}\" ({domain}). Ниже данные одного YouTube-канала и его видео из проекта: " +
            "метаданные, сводки или фрагменты расшифровок, топ-комментарии зрителей.\n" +
            "Составь досье на языке {lang}. Пиши живо и конкретно, без воды, только по данным, ничего не выдумывай. " +
            "Верни строго JSON:\n" +
            "{\"channel\":{\"about\":\"о чём канал, 2-3 предложения\",\"audience\":\"для кого канал\",\"formats\":[\"рубрики и форматы\"]," +
            "\"strengths\":\"сильные стороны\",\"weaknesses\":\"слабые стороны\",\"tone\":\"подача и тон автора\"}," +
            "\"videos\":[{\"n\":номер видео,\"about\":\"о чём видео, 1-2 предложения\",\"points\":[\"3-5 ключевых тезисов\"]," +
            "\"comments\":\"о чём говорят зрители, 1-2 предложения; пустая строка, если комментариев нет\"," +
            "\"mood\":{\"pos\":0-100,\"neg\":0-100,\"neu\":0-100},\"questions\":[\"частые вопросы зрителей, до 3\"]," +
            "\"verdict\":\"чем видео полезно для темы, одно предложение\"}]}\n" +
            "Опиши каждое видео из списка.\n\n{data}"
        ),
    "dossier_overview" to (
        "Тема исследования: \"{phrase}\" ({domain}). Ниже досье каналов и видео проекта.\n" +
            "Сделай общую картину на языке {lang}, только по данным. Верни строго JSON:\n" +
            "{\"summary\":\"главное о нише, 3-5 предложений\",\"themes\":[\"3-6 главных тем и нарративов\"]," +
            "\"leaders\":\"кто задаёт тон и почему, 1-2 предложения\",\"audience\":\"что волнует зрителей по комментариям, 1-2 предложения\"," +
            "\"gaps\":[\"3-5 незакрытых тем и идей для контента\"]}\n\n{data}"
        )
)

/** AI dossier of a project: what every channel and every video is about and what viewers say. */
object Dossier {
    data class VideoCard(
        val n: Int,
        val about: String,
        val points: List<String>,
        val comments: String,
        val pos: Int,
        val neg: Int,
        val neu: Int,
        val questions: List<String>,
        val verdict: String
    ) {
        val hasMood get() = pos >= 0 && neg >= 0 && neu >= 0 && pos + neg + neu > 0
    }

    data class ChannelCard(
        val about: String,
        val audience: String,
        val formats: List<String>,
        val strengths: String,
        val weaknesses: String,
        val tone: String,
        val videos: List<VideoCard>
    ) {
        val empty get() = about.isBlank() && videos.isEmpty()
    }

    data class Overview(val summary: String, val themes: List<String>, val leaders: String, val audience: String, val gaps: List<String>) {
        val empty get() = summary.isBlank() && themes.isEmpty()
    }

    /** Channel of the project with its videos, strongest first. */
    data class Group(val key: String, val name: String, val url: String, val videos: List<Video>) {
        val views get() = videos.sumOf { maxOf(0, it.views) }
    }

    data class Config(val channels: Int = 10, val videos: Int = 6) {
        companion object {
            fun load() = Config(Lumen.prefs.getInt("dossier_channels", 10), Lumen.prefs.getInt("dossier_videos", 6))
            fun save(c: Config) = Lumen.prefs.edit().putInt("dossier_channels", c.channels).putInt("dossier_videos", c.videos).apply()
        }
    }

    // ------------------------------------------------------------------ pure parsing

    private fun JSONObject.str(k: String) = optString(k, "").let { if (it == "null") "" else it.trim() }

    private fun JSONObject.list(k: String): List<String> {
        val a = optJSONArray(k) ?: return optString(k, "").takeIf { it.isNotBlank() && it != "null" }?.let { listOf(it.trim()) } ?: emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, "").trim().takeIf { s -> s.isNotEmpty() && s != "null" } }
    }

    private fun obj(text: String): JSONObject? = try {
        JsonText.firstObject(text)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    /** Normalises a mood to percents that sum to 100; -1 when unknown. */
    fun mood(o: JSONObject?): Triple<Int, Int, Int> {
        if (o == null) return Triple(-1, -1, -1)
        val p = o.optDouble("pos", -1.0)
        val n = o.optDouble("neg", -1.0)
        val u = o.optDouble("neu", -1.0)
        if (p < 0 || n < 0 || u < 0 || p + n + u <= 0) return Triple(-1, -1, -1)
        val s = p + n + u
        val a = Math.round(p * 100 / s).toInt()
        val b = Math.round(n * 100 / s).toInt()
        return Triple(a, b, (100 - a - b).coerceAtLeast(0))
    }

    /** Parses a channel reply; broken video items are skipped, null when nothing usable came back. */
    fun parseChannel(text: String): ChannelCard? {
        val o = obj(text) ?: return null
        val ch = o.optJSONObject("channel") ?: JSONObject()
        val va = o.optJSONArray("videos") ?: JSONArray()
        val videos = ArrayList<VideoCard>()
        for (i in 0 until va.length()) {
            val v = va.optJSONObject(i) ?: continue
            val (p, n, u) = mood(v.optJSONObject("mood"))
            val card = VideoCard(
                n = v.optInt("n", i + 1), about = v.str("about"), points = v.list("points"), comments = v.str("comments"),
                pos = p, neg = n, neu = u, questions = v.list("questions"), verdict = v.str("verdict")
            )
            if (card.about.isNotBlank() || card.points.isNotEmpty()) videos += card
        }
        val c = ChannelCard(ch.str("about"), ch.str("audience"), ch.list("formats"), ch.str("strengths"), ch.str("weaknesses"), ch.str("tone"), videos)
        return if (c.empty) null else c
    }

    fun parseOverview(text: String): Overview? {
        val o = obj(text) ?: return null
        val r = Overview(o.str("summary"), o.list("themes"), o.str("leaders"), o.str("audience"), o.list("gaps"))
        return if (r.empty) null else r
    }

    /** Stored result: the video ids in the order sent to the AI plus its raw reply. */
    fun pack(ids: List<String>, reply: String): String = JSONObject().put("ids", JSONArray(ids)).put("reply", reply).toString()

    fun unpack(stored: String): Pair<List<String>, ChannelCard?> = try {
        val o = JSONObject(stored)
        val a = o.optJSONArray("ids") ?: JSONArray()
        (0 until a.length()).map { a.optString(it) } to parseChannel(o.optString("reply"))
    } catch (e: Exception) {
        emptyList<String>() to parseChannel(stored)
    }

    /** Videos grouped by channel: channels with more videos (then views) in the project first, videos by views. */
    fun group(videos: Collection<Video>, channels: Int, perChannel: Int): List<Group> =
        videos.filter { !isLocalSource(it.id) && (it.channel.isNotBlank() || it.channelUrl.isNotBlank()) }
            .groupBy { if (it.channelUrl.isNotBlank()) ChannelStore.key(it.channelUrl) else "name:" + it.channel.lowercase() }
            .values
            .sortedWith(compareByDescending<List<Video>> { it.size }.thenByDescending { l -> l.sumOf { maxOf(0, it.views) } })
            .take(channels)
            .map { list ->
                val sorted = list.sortedByDescending { it.views }
                val first = sorted.first()
                val key = if (first.channelUrl.isNotBlank()) ChannelStore.key(first.channelUrl) else "name:" + first.channel.lowercase()
                Group(key, first.channel.ifBlank { "?" }, first.channelUrl, sorted.take(perChannel))
            }

    // ------------------------------------------------------------------ AI

    private fun lang() = if (Settings.lang == "en") "English" else "русский"

    private fun vars(p: Project, data: String) = mapOf("phrase" to p.phrase, "domain" to p.domain.ifBlank { "-" }, "lang" to lang(), "data" to data)

    private fun channelData(pid: String, g: Group, scale: Double): String {
        val info = if (g.url.isNotBlank()) ChannelStore.get(pid, g.url) else null
        val sb = StringBuilder()
        sb.append("Channel: ").append(g.name)
        val subs = info?.subscribers ?: g.videos.firstOrNull()?.subscribers ?: -1
        if (subs > 0) sb.append(" | subscribers ").append(subs)
        sb.append('\n')
        info?.description?.takeIf { it.isNotBlank() }?.let { sb.append("Channel description: ").append(it.take((700 * scale).toInt()).replace('\n', ' ')).append('\n') }
        sb.append("Videos:\n")
        g.videos.forEachIndexed { i, v ->
            sb.append("\n#").append(i + 1).append(' ').append(v.title)
                .append(" | views ").append(v.views).append(" | ").append(Fmt.date(v.uploadMs)).append(" | ").append(Fmt.duration(v.duration))
            if (v.likes >= 0) sb.append(" | likes ").append(v.likes)
            sb.append('\n')
            if (v.description.isNotBlank()) sb.append("Description: ").append(v.description.take((300 * scale).toInt()).replace('\n', ' ')).append('\n')
            val sum = Store.latestAi(pid, "video_summary", v.id)?.text
            if (sum != null) {
                sb.append("Summary: ").append(sum.take((1000 * scale).toInt())).append('\n')
            } else {
                Store.transcript(pid, v.id)?.let(Vtt::stripTimecodes)?.takeIf { it.isNotBlank() }?.let {
                    sb.append("Transcript excerpt: ").append(it.take((1400 * scale).toInt()).replace('\n', ' ')).append('\n')
                }
            }
            val cs = Store.comments(pid, v.id).filter { !it.isReply && !Voc.isSpam(it.text) }.sortedByDescending { it.likes }.take((14 * scale).toInt().coerceAtLeast(3))
            if (cs.isNotEmpty()) {
                sb.append("Top comments (").append(v.commentsCount).append(" collected):\n")
                cs.forEach { c -> sb.append("- (").append(c.likes).append("♥) ").append(c.text.replace('\n', ' ').take((220 * scale).toInt())).append('\n') }
            }
        }
        return sb.toString()
    }

    private fun boundedData(pid: String, g: Group): String {
        val limit = Settings.chunkChars * 2
        var scale = 1.0
        repeat(4) {
            val d = channelData(pid, g, scale)
            if (d.length <= limit) return d
            scale *= 0.6
        }
        return channelData(pid, g, scale).take(limit)
    }

    fun estimate(pid: String, cfg: Config): Int {
        val groups = group(Store.videos(pid).values, cfg.channels, cfg.videos)
        return groups.sumOf { Llm.estimateTokens(boundedData(pid, it)) + 900 + 450 * it.videos.size } + 3000
    }

    /** Latest stored channel cards by channel key, read in one pass: key -> (video ids, card). */
    fun stored(pid: String): Map<String, Pair<List<String>, ChannelCard?>> {
        val out = HashMap<String, Pair<List<String>, ChannelCard?>>()
        for (r in Store.aiAll(pid)) if (r.kind == "dossier_channel" && !out.containsKey(r.target)) out[r.target] = unpack(r.text)
        return out
    }

    fun channel(pid: String, g: Group): ChannelCard? = Store.latestAi(pid, "dossier_channel", g.key)?.let { unpack(it.text).second }

    fun overview(pid: String): Overview? = Store.latestAi(pid, "dossier_overview", "project")?.text?.let { parseOverview(it) }

    private fun runChannel(p: Project, prov: Provider, g: Group): Boolean {
        val data = boundedData(p.id, g)
        val ids = g.videos.map { it.id }
        val key = sha1("dossier_channel|${prov.fastModel}|${Prompts.get("dossier_channel")}|${data.hashCode()}|${Settings.lang}")
        Store.ai(p.id, key)?.let { if (unpack(it.text).second != null) return false }
        val maxTok = (1200 + 520 * g.videos.size).coerceAtMost(7000)
        var prompt = Prompts.fill("dossier_channel", vars(p, data))
        var reply = ""
        for (attempt in 0..1) {
            val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = maxTok, task = "dossier_channel", pid = p.id)
            reply = r.text
            if (parseChannel(reply) != null) {
                Store.saveAi(p.id, AiResult(key, "dossier_channel", g.key, r.model, pack(ids, reply), r.inTokens, r.outTokens))
                return true
            }
            prompt += "\n\nВажно: верни только валидный JSON по схеме выше, без пояснений. / Return only valid JSON."
        }
        throw LlmException(t("AI вернул ответ, который не удалось разобрать", "The AI reply could not be parsed") + ": " + g.name, reply)
    }

    private fun runOverview(p: Project, prov: Provider, groups: List<Group>) {
        val sb = StringBuilder()
        val st = stored(p.id)
        for (g in groups) {
            val c = st[g.key]?.second ?: continue
            sb.append("## ").append(g.name).append(" (").append(g.videos.size).append(" videos, ").append(Fmt.num(g.views)).append(" views)\n")
            sb.append(c.about).append(' ').append(c.audience).append('\n')
            c.videos.forEach { v -> sb.append("- ").append(v.about).append(if (v.comments.isNotBlank()) " Comments: " + v.comments else "").append('\n') }
            sb.append('\n')
            if (sb.length > Settings.chunkChars * 3) break
        }
        if (sb.isEmpty()) return
        val data = sb.toString()
        val key = sha1("dossier_overview|${prov.smartModel}|${Prompts.get("dossier_overview")}|${data.hashCode()}|${Settings.lang}")
        if (Store.ai(p.id, key) != null) return
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("dossier_overview", vars(p, data)), json = true, maxTokens = 2500, task = "dossier_overview", pid = p.id)
        if (parseOverview(r.text) != null) Store.saveAi(p.id, AiResult(key, "dossier_overview", "project", r.model, r.text, r.inTokens, r.outTokens))
    }

    /** Background job (CollectService kind "dossier"): one AI call per channel, then the overview. Cached results are reused. */
    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val p = Store.project(pid) ?: return ""
        val prov = AiTasks.requireProvider()
        val cfg = Config.load()
        val groups = group(Store.videos(pid).values, cfg.channels, cfg.videos)
        if (groups.isEmpty()) throw LlmException(t("В проекте нет видео с каналами", "The project has no videos with channels"))
        val made = java.util.concurrent.atomic.AtomicInteger(0)
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        var lastErr = ""
        Par.each(groups, cancel = cancel) { _, g ->
            progress(t("Досье: канал ${started.incrementAndGet()}/${groups.size}\n", "Dossier: channel ${started.get()}/${groups.size}\n") + g.name)
            try {
                if (runChannel(p, prov, g)) made.incrementAndGet()
            } catch (e: Exception) {
                errors.incrementAndGet()
                lastErr = e.message ?: e.javaClass.simpleName
                L.w("dossier", "${g.name}: $lastErr")
            }
        }
        if (!cancel.get()) {
            progress(t("Досье: общая картина\n", "Dossier: overview\n") + p.name)
            try {
                runOverview(p, prov, groups)
            } catch (e: Exception) {
                errors.incrementAndGet()
                lastErr = e.message ?: e.javaClass.simpleName
                L.w("dossier", "overview: $lastErr")
            }
        }
        val st = stored(pid)
        val ready = groups.count { st[it.key]?.second != null }
        return t("Досье: готово каналов $ready из ${groups.size}, новых ${made.get()}", "Dossier: $ready of ${groups.size} channels ready, ${made.get()} new") +
            (if (errors.get() > 0) t(", ошибок ${errors.get()} ($lastErr)", ", errors ${errors.get()} ($lastErr)") else "") +
            t(". Экспорт — во вкладке «Экспорт».", ". Export it from the Export tab.")
    }
}

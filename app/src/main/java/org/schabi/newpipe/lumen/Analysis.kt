/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.util.Calendar

data class Bucket(val label: String, val start: Long, val count: Int, val views: Long)

data class ChannelStat(val name: String, val url: String, val videos: Int, val views: Long, val engagement: Double)

data class Stats(
    val total: Int,
    val collected: Int,
    val withTranscript: Int,
    val totalViews: Long,
    val avgViews: Long,
    val comments: Int,
    val firstMs: Long,
    val lastMs: Long
)

/** Local analytics: works without any API key. */
object Analytics {
    fun stats(videos: Collection<Video>): Stats {
        val dated = videos.filter { it.uploadMs > 0 }
        val viewsKnown = videos.filter { it.views >= 0 }
        val total = viewsKnown.sumOf { it.views }
        return Stats(
            videos.size,
            videos.count { it.collected },
            videos.count { it.hasTranscript },
            total,
            if (viewsKnown.isEmpty()) 0 else total / viewsKnown.size,
            videos.sumOf { it.commentsCount },
            dated.minOfOrNull { it.uploadMs } ?: -1,
            dated.maxOfOrNull { it.uploadMs } ?: -1
        )
    }

    /** Groups by upload day, week or month depending on the time span. */
    fun trend(videos: Collection<Video>, unit: String = "auto"): List<Bucket> {
        val dated = videos.filter { it.uploadMs > 0 }
        if (dated.isEmpty()) return emptyList()
        val span = dated.maxOf { it.uploadMs } - dated.minOf { it.uploadMs }
        val u = if (unit != "auto") unit else when {
            span <= 45L * 86_400_000L -> "day"
            span <= 400L * 86_400_000L -> "week"
            else -> "month"
        }
        val cal = Calendar.getInstance()
        val groups = dated.groupBy { v ->
            cal.timeInMillis = v.uploadMs
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            when (u) {
                "week" -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                "month" -> cal.set(Calendar.DAY_OF_MONTH, 1)
            }
            cal.timeInMillis
        }
        return groups.entries.sortedBy { it.key }.map { (start, list) ->
            cal.timeInMillis = start
            val label = when (u) {
                "month" -> String.format("%02d.%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100)
                else -> String.format("%02d.%02d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
            }
            Bucket(label, start, list.size, list.sumOf { maxOf(0, it.views) })
        }
    }

    private fun engagement(v: Video): Double {
        if (v.views <= 0) return 0.0
        val l = maxOf(0, v.likes)
        return (l + v.commentsCount).toDouble() / v.views
    }

    fun topChannels(videos: Collection<Video>, by: String = "videos", n: Int = 15): List<ChannelStat> {
        val stats = videos.groupBy { it.channel.ifBlank { "?" } }.map { (name, list) ->
            ChannelStat(
                name, list.first().channelUrl, list.size, list.sumOf { maxOf(0, it.views) },
                list.map { engagement(it) }.average()
            )
        }
        return when (by) {
            "views" -> stats.sortedByDescending { it.views }
            "engagement" -> stats.sortedByDescending { it.engagement }
            else -> stats.sortedWith(compareByDescending<ChannelStat> { it.videos }.thenByDescending { it.views })
        }.take(n)
    }

    fun topVideos(videos: Collection<Video>, by: String = "views", n: Int = 15): List<Video> {
        val now = System.currentTimeMillis()
        return when (by) {
            "engagement" -> videos.filter { it.views > 100 }.sortedByDescending { engagement(it) }
            "velocity" -> videos.filter { it.uploadMs > 0 && it.views > 0 }
                .sortedByDescending { it.views / maxOf(1.0, (now - it.uploadMs) / 86_400_000.0) }
            else -> videos.sortedByDescending { it.views }
        }.take(n)
    }

    fun velocity(v: Video): Long {
        if (v.uploadMs <= 0 || v.views <= 0) return -1
        return (v.views / maxOf(1.0, (System.currentTimeMillis() - v.uploadMs) / 86_400_000.0)).toLong()
    }

    fun engagementPct(v: Video): String =
        if (v.views <= 0 || v.likes < 0) "—" else String.format("%.2f%%", engagement(v) * 100)

    fun textsOf(projectId: String, videos: Collection<Video>, source: String): Sequence<String> = when (source) {
        "titles" -> videos.asSequence().map { it.title }
        "descriptions" -> videos.asSequence().map { it.title + " " + it.description + " " + it.tags.joinToString(" ") }
        "transcripts" -> videos.asSequence().mapNotNull { Store.transcript(projectId, it.id)?.let(Vtt::stripTimecodes) }
        "comments" -> videos.asSequence().flatMap { v -> Store.comments(projectId, v.id).asSequence().map { it.text } }
        else -> videos.asSequence().map { it.title }
    }

    data class Compare(val nameA: String, val nameB: String, val a: Stats, val b: Stats,
                       val wordsA: List<Pair<String, Int>>, val wordsB: List<Pair<String, Int>>)

    fun compare(nameA: String, pidA: String, a: Collection<Video>, nameB: String, pidB: String, b: Collection<Video>): Compare =
        Compare(
            nameA, nameB, stats(a), stats(b),
            Words.top(textsOf(pidA, a, "descriptions"), 20),
            Words.top(textsOf(pidB, b, "descriptions"), 20)
        )
}

/** AI analysis orchestration with caching and map-reduce for long transcripts. */
object AiTasks {
    private fun lang() = if (Settings.lang == "en") "English" else "русский"

    private fun vars(p: Project, data: String, question: String = "") = mapOf(
        "phrase" to p.phrase, "domain" to p.domain.ifBlank { "-" }, "lang" to lang(),
        "data" to data, "question" to question
    )

    fun requireProvider(): Provider = Provider.active()
        ?: Provider.MANUAL.takeIf { Relay.mode != "off" }
        ?: throw LlmException(t("AI-провайдер не подключён. Откройте Настройки → AI.", "No AI provider connected. Open Settings → AI."))

    private fun cached(pid: String, key: String, force: Boolean): AiResult? = if (force) null else Store.ai(pid, key)

    fun domains(phrase: String): List<Triple<String, String, List<String>>> {
        val p = requireProvider()
        val prompt = Prompts.get("domain").replace("{phrase}", phrase).replace("{lang}", lang())
        val r = Llm.complete(p, p.fastModel, Prompts.SYSTEM, prompt, json = true, task = "domain")
        val obj = org.json.JSONObject(JsonText.firstObject(r.text) ?: throw LlmException("Bad JSON: " + r.text.take(200)))
        val arr = obj.optJSONArray("domains") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val qs = d.optJSONArray("queries")
            Triple(d.optString("name"), d.optString("description"), (0 until (qs?.length() ?: 0)).map { qs!!.optString(it) })
        }
    }

    /** Characters one call may carry: a big-context model takes a long transcript whole, without map-reduce. */
    fun chunkFor(prov: Provider, model: String = prov.smartModel): Int =
        LlmTasks.contextChars(prov.kind.name, model, Settings.chunkChars, Settings.chunkUserSet)

    /** Per-video context of a list: speed per day, the jump over the channel's median, descriptions without boilerplate. */
    class Ctx(videos: Collection<Video>) {
        private val now = System.currentTimeMillis()
        private val medians = ViewStats.channelMedians(videos.map { it.channel to ViewStats.perDay(it.views, it.uploadMs, now) })
        private val boiler = videos.groupBy { it.channel }.mapValues { (_, l) -> DescClean.boilerplate(l.map { it.description }) }
        fun stats(v: Video): String {
            val age = ViewStats.ageDays(v.uploadMs, now)
            return (if (age >= 0) "${age}d, " else "") + ViewStats.short(maxOf(v.views, 0L).toDouble()) + " views" +
                ViewStats.tag(v.views, v.uploadMs, now, medians[v.channel])
        }
        fun desc(v: Video, max: Int): String = DescClean.clean(v.description, boiler[v.channel].orEmpty()).replace('\n', ' ').take(max)
    }

    private fun videoData(pid: String, v: Video): String {
        val tr = Store.transcript(pid, v.id)
        val now = System.currentTimeMillis()
        return buildString {
            append("Title: ").append(v.title).append('\n')
            append("Channel: ").append(v.channel).append(" | views ").append(v.views).append(" | date ").append(Fmt.date(v.uploadMs))
                .append(ViewStats.tag(v.views, v.uploadMs, now, null)).append('\n')
            DescClean.clean(v.description).takeIf { it.isNotBlank() }?.let { append("Description: ").append(it.take(2000)).append('\n') }
            // a timecode per ~30 s paragraph instead of per caption line: −38 % tokens, the same text
            if (!tr.isNullOrBlank()) append("Transcript:\n").append(Transcript.forAi(tr))
        }
    }

    /** Estimates tokens for summarising the given videos (for the confirmation dialog). */
    fun estimateVideoSummaries(pid: String, videos: List<Video>): Int =
        videos.sumOf { Llm.estimateTokens(videoData(pid, it)) + 600 }

    fun videoSummary(p: Project, v: Video, force: Boolean = false): AiResult {
        val prov = requireProvider()
        val data = videoData(p.id, v)
        val key = sha1("video_summary|${prov.smartModel}|${Prompts.get("video_summary")}|${data.hashCode()}|${Settings.lang}")
        cached(p.id, key, force)?.let { return it }
        val max = chunkFor(prov)
        val inAcc = java.util.concurrent.atomic.AtomicInteger()
        val outAcc = java.util.concurrent.atomic.AtomicInteger()
        val body = if (data.length > max) {
            // map: summarise chunks with the fast model, reduce with the smart model
            val parts = Par.mapAll(Chunks.split(data, max)) { _, chunk ->
                val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, Prompts.fill("chunk_summary", vars(p, chunk)), maxTokens = 1500, task = "chunk_summary", pid = p.id)
                inAcc.addAndGet(r.inTokens)
                outAcc.addAndGet(r.outTokens)
                r.text
            }
            "Title: ${v.title}\nChannel: ${v.channel}\nSummaries of transcript parts:\n" + parts.joinToString("\n---\n")
        } else {
            data
        }
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("video_summary", vars(p, body)), maxTokens = 2500, task = "video_summary", pid = p.id)
        val res = AiResult(key, "video_summary", v.id, r.model, r.text, inAcc.get() + r.inTokens, outAcc.get() + r.outTokens)
        Store.saveAi(p.id, res)
        return res
    }

    /** One document per video: "#n title — channel (age, views · per day · ×channel)" + its summary or description. */
    private fun corpusDocs(p: Project, videos: List<Video>): List<String> {
        val c = Ctx(videos)
        return videos.mapIndexed { i, v ->
            val sum = Store.latestAi(p.id, "video_summary", v.id)?.text
            "#" + (i + 1) + " " + v.title + " — " + v.channel + " (" + c.stats(v) + ")\n" + (sum?.take(1500) ?: c.desc(v, 400)) + "\n\n"
        }
    }

    private fun corpus(p: Project, videos: List<Video>): String = corpusDocs(p, videos).joinToString("")

    private fun boundedCorpus(p: Project, videos: List<Video>): String {
        val c = corpus(p, videos)
        val limit = Settings.chunkChars * 3
        return if (c.length > limit) c.take(limit) + "\n…(truncated)" else c
    }

    fun estimateProject(p: Project, videos: List<Video>): Int = Llm.estimateTokens(boundedCorpus(p, videos)) + 3000

    fun projectSummary(p: Project, videos: List<Video>, force: Boolean = false): AiResult {
        val prov = requireProvider()
        val data = boundedCorpus(p, videos)
        val previous = Store.latestAi(p.id, "project_summary", "project")?.text
        val full = if (previous != null) data + "\n\nPrevious report (to describe what changed):\n" + previous.take(3000) else data
        val key = sha1("project_summary|${prov.smartModel}|${Prompts.get("project_summary")}|${data.hashCode()}|${Settings.lang}")
        cached(p.id, key, force)?.let { return it }
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("project_summary", vars(p, full)), maxTokens = 4000, task = "project_summary", pid = p.id)
        return AiResult(key, "project_summary", "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    fun clusters(p: Project, videos: List<Video>, force: Boolean = false): AiResult {
        val prov = requireProvider()
        val c = Ctx(videos)
        val data = videos.mapIndexed { i, v -> "#${i + 1} ${v.title} — ${v.channel}. ${c.desc(v, 200)}" }
            .joinToString("\n").take(Settings.chunkChars * 3)
        val key = sha1("clusters|${prov.smartModel}|${Prompts.get("clusters")}|${data.hashCode()}|${Settings.lang}")
        cached(p.id, key, force)?.let { return it }
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("clusters", vars(p, data)), maxTokens = 3000, task = "clusters", pid = p.id)
        return AiResult(key, "clusters", "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    fun sentiment(p: Project, videos: List<Video>, force: Boolean = false): AiResult {
        val prov = requireProvider()
        val ids = videos.map { it.id }.toSet()
        val msgs = Pains.projectMessages(p.id).filter { it.videoId in ids }
        if (msgs.isEmpty()) throw LlmException(t("Нет собранных комментариев.", "No collected comments."))
        val key = sha1("sentiment|${prov.smartModel}|${Prompts.get("sentiment")}|${msgs.size}|${msgs.sumOf { it.text.length + it.likes }}|${Settings.lang}")
        cached(p.id, key, force)?.let { return it }
        // the same extracted items as the comment digest (read once, cached), not the raw comments again
        val items = VocAi.items(p.id, msgs)
        val counts = VocCore.counts(items).entries.joinToString(", ") { "${it.key} ${it.value}" }
        val data = "Comments: ${msgs.size}. Comments per type: $counts\n" + VocAi.block(items, msgs, Settings.chunkChars)
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, Prompts.fill("sentiment", vars(p, data)), maxTokens = 2500, task = "sentiment", pid = p.id)
        return AiResult(key, "sentiment", "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    /** Extra data block for the tab the user asks from; empty when the project corpus alone is enough. */
    private fun tabData(p: Project, videos: List<Video>, tab: String): String = when (tab) {
        "recs" -> try {
            val r = AlgoAnalysis.get(p.id)
            if (r.nodes.isEmpty()) "" else AlgoData.report(r, short = true).text
        } catch (e: Exception) {
            ""
        }
        "predict" -> PredictData.brief(p.id)
        "audience", "seo" -> Seo.insights(p.id).take(80).joinToString("\n") { "- [${it.optString("type")}] ${it.optString("statement")}" }
            .let { if (it.isBlank()) "" else "Audience insights:\n$it" }
        CommentDigest.TAB -> CommentDigest.chatContext(p.id)
        "channels", "analytics", "overview" -> videos.groupBy { it.channel }.entries.sortedByDescending { it.value.size }.take(20)
            .joinToString("\n") { (c, l) -> "- $c: ${l.size} videos, ${l.sumOf { maxOf(it.views, 0L) }} views" }
            .let { if (it.isBlank()) "" else "Top channels:\n$it" }
        else -> ""
    }

    /**
     * The videos a question is about (TF-IDF over summaries) in full and the rest by title, instead of the whole
     * corpus on every question (PROMPTS_PLAN 1E). A small project goes whole.
     */
    private fun chatCorpus(p: Project, videos: List<Video>, question: String): String {
        val docs = corpusDocs(p, videos)
        val budget = Settings.chunkChars
        if (docs.sumOf { it.length } <= budget) return docs.joinToString("")
        val pick = Retrieve.topK(question, docs, budget, minDocs = 5).toSet()
        val rest = videos.indices.filter { it !in pick }.joinToString("\n") { "#${it + 1} ${videos[it].title} — ${videos[it].channel}" }
        return pick.sorted().joinToString("") { docs[it] } + if (rest.isBlank()) "" else "Other videos of the project (titles only):\n" + rest.take(budget / 3) + "\n"
    }

    fun chat(p: Project, videos: List<Video>, question: String, tab: String = ""): AiResult {
        val prov = requireProvider()
        val data = if (tab == CommentDigest.TAB) CommentDigest.chatContext(p.id, question) else {
            val extra = tabData(p, videos, tab).take(Settings.chunkChars)
            val corpus = chatCorpus(p, videos, question)
            if (extra.isBlank()) corpus else extra + "\n\n" + corpus
        }
        val r = Llm.complete(
            prov, prov.smartModel, Prompts.SYSTEM,
            Prompts.fill("chat", vars(p, data, AskAi.framed(tab, question))), maxTokens = 2500, task = "chat", pid = p.id
        )
        val res = AiResult(sha1("chat|${System.nanoTime()}"), "chat", question.take(200), r.model, r.text, r.inTokens, r.outTokens)
        Store.saveAi(p.id, res)
        return res
    }
}

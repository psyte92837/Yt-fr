/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Pure classification of shared YouTube links (covered by unit tests). */
object YtLink {
    data class Link(val type: String, val url: String) // video | channel | playlist | unknown

    fun extractUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']')

    fun classify(text: String): Link {
        val url = extractUrl(text) ?: return Link("unknown", text.trim())
        val u = url.replace("m.youtube.com", "www.youtube.com").replace("music.youtube.com", "www.youtube.com")
        if (!Regex("""(youtube\.com|youtu\.be)""").containsMatchIn(u)) return Link("unknown", url)
        val list = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(u)?.groupValues?.get(1)
        val vid = YtQuery.videoIdFromUrl(u)
        return when {
            u.contains("/playlist") && list != null -> Link("playlist", "https://www.youtube.com/playlist?list=$list")
            vid != null -> Link("video", "https://www.youtube.com/watch?v=$vid")
            list != null && !list.startsWith("RD") -> Link("playlist", "https://www.youtube.com/playlist?list=$list")
            Regex("""youtube\.com/(@[^/?#]+|channel/[A-Za-z0-9_-]+|c/[^/?#]+|user/[^/?#]+)""").containsMatchIn(u) -> {
                val m = Regex("""youtube\.com/(@[^/?#]+|channel/[A-Za-z0-9_-]+|c/[^/?#]+|user/[^/?#]+)""").find(u)!!
                Link("channel", "https://www.youtube.com/" + m.groupValues[1])
            }
            else -> Link("unknown", stripQuery(url))
        }
    }

    /** Drops everything after "?" or "#" (si=, feature=, t=, utm_…); watch/playlist links are rebuilt from v= / list= by [classify]. */
    fun stripQuery(url: String): String = url.substringBefore('#').substringBefore('?')
}

/** What the instant analysis runs automatically; edited in Settings → Instant analysis. */
data class QuickConfig(
    var collect: Boolean = true,
    var aiSummary: Boolean = true,
    var channelStats: Boolean = true,
    var channelRecent: Int = 50,
    var channelImport: Int = 100,
    var collectTop: Int = 10,
    var playlistLimit: Int = 200,
    var recs: Boolean = true,
    var recVideos: Int = 100,
    var recDepth: Int = 10,
    var channelGraph: Boolean = true,
    var graphChannels: Int = 100,
    var graphDepth: Int = 2,
    var aiChannelSummary: Boolean = true,
    var aiProjectSummary: Boolean = false,
    var topicFilter: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("collect", collect).put("aiSummary", aiSummary).put("channelStats", channelStats)
        .put("channelRecent", channelRecent).put("channelImport", channelImport).put("collectTop", collectTop)
        .put("playlistLimit", playlistLimit).put("recs", recs).put("recVideos", recVideos).put("recDepth", recDepth)
        .put("channelGraph", channelGraph).put("graphChannels", graphChannels).put("graphDepth", graphDepth)
        .put("aiChannelSummary", aiChannelSummary).put("aiProjectSummary", aiProjectSummary).put("topicFilter", topicFilter)

    companion object {
        fun from(o: JSONObject?) = QuickConfig().apply {
            if (o == null) return@apply
            collect = o.optBoolean("collect", collect)
            aiSummary = o.optBoolean("aiSummary", aiSummary)
            channelStats = o.optBoolean("channelStats", channelStats)
            channelRecent = o.optInt("channelRecent", channelRecent)
            channelImport = o.optInt("channelImport", channelImport)
            collectTop = o.optInt("collectTop", collectTop)
            playlistLimit = o.optInt("playlistLimit", playlistLimit)
            recs = o.optBoolean("recs", recs)
            recVideos = o.optInt("recVideos", recVideos)
            recDepth = o.optInt("recDepth", recDepth)
            channelGraph = o.optBoolean("channelGraph", channelGraph)
            graphChannels = o.optInt("graphChannels", graphChannels)
            graphDepth = o.optInt("graphDepth", graphDepth)
            aiChannelSummary = o.optBoolean("aiChannelSummary", aiChannelSummary)
            aiProjectSummary = o.optBoolean("aiProjectSummary", aiProjectSummary)
            topicFilter = o.optBoolean("topicFilter", topicFilter)
        }

        fun load(): QuickConfig = from(Lumen.prefs.getString("quick_cfg", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } })
        fun save(c: QuickConfig) = Lumen.prefs.edit().putString("quick_cfg", c.toJson().toString()).apply()
    }
}

/** Step status of an instant analysis, stored in quick.json. */
object QuickState {
    data class Step(val key: String, var status: String = "pending", var note: String = "") // pending | running | done | error | skipped

    fun load(pid: String): List<Step> {
        val a = Store.readFile(pid, "quick.json")?.let { try { JSONObject(it).optJSONArray("steps") } catch (e: Exception) { null } } ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Step(o.optString("key"), o.optString("status"), o.optString("note")) } }
    }

    fun save(pid: String, steps: List<Step>) = Store.writeFile(pid, "quick.json",
        JSONObject().put("steps", JSONArray().also { a -> steps.forEach { a.put(JSONObject().put("key", it.key).put("status", it.status).put("note", it.note)) } }).toString())

    fun label(key: String) = when (key) {
        "source" -> t("Загрузка ссылки", "Loading link")
        "collect" -> t("Метаданные, расшифровка, комментарии", "Metadata, transcript, comments")
        "summary" -> t("AI-сводка", "AI summary")
        "channel" -> t("Статистика канала, ссылки, плейлисты", "Channel stats, links, playlists")
        "channels" -> t("Статистика каналов", "Channel stats")
        "recs" -> t("Похожие и рекомендации", "Similar & recommended")
        "chgraph" -> t("Граф каналов", "Channel graph")
        "channel_ai" -> t("AI-сводка по каналу", "AI channel summary")
        "project_ai" -> t("AI-сводка по проекту", "AI project summary")
        else -> key
    }
}

/** AI summary of a channel from its recent uploads. */
object ChannelAi {
    fun summary(pid: String, c: ChannelData, force: Boolean = false): AiResult {
        val prov = AiTasks.requireProvider()
        val s = c.stats
        val data = buildString {
            append("Channel: ").append(c.name).append(" · subscribers ").append(c.subscribers).append('\n')
            append("Description: ").append(c.description.take(1500)).append('\n')
            append("Links: ").append(c.links.take(15).joinToString(" ")).append('\n')
            append("Stats: videos=${s.videos} avg=${s.avgViews} median=${s.medianViews} perWeek=${String.format("%.2f", s.perWeek)} shortsShare=${String.format("%.2f", s.shortsShare)}\n")
            append("Playlists: ").append(c.playlists.take(30).joinToString("; ") { "${it.name} (${it.count})" }).append('\n')
            append("Recent videos:\n")
            c.recent.take(60).forEach { v -> append("- ").append(v.title).append(" | ").append(v.views).append(" views | ").append(Fmt.date(v.uploadMs)).append('\n') }
        }
        val prompt = Prompts.get("channel_summary").replace("{data}", data).replace("{lang}", if (Settings.lang == "en") "English" else "русский")
        val key = sha1("channel_summary|${prov.smartModel}|${prompt.hashCode()}")
        if (!force) Store.ai(pid, key)?.let { return it }
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, maxTokens = 3000, task = "channel_summary", pid = pid)
        return AiResult(key, "channel_summary", ChannelStore.key(c.url), r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(pid, it) }
    }

    fun latest(pid: String, url: String): AiResult? = Store.latestAi(pid, "channel_summary", ChannelStore.key(url))
}

/** Instant analysis of a shared video, channel or playlist: every step runs automatically in the background. */
object QuickAnalysis {
    private val yt get() = ServiceList.YouTube

    /** Creates a project for the link and returns its id (the service then runs [run]). */
    fun createProject(link: YtLink.Link): Project {
        val p = Project(name = t("Анализ: ", "Analysis: ") + link.type, phrase = "", queries = mutableListOf())
        p.source = link.url
        p.sourceType = link.type
        p.recs.seeds = "all"
        p.recs.relevance = if (QuickConfig.load().topicFilter) "ai" else "off"
        Store.save(p)
        return p
    }

    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val cfg = QuickConfig.load()
        val p0 = Store.project(pid) ?: return ""
        val type = p0.sourceType
        val keys = mutableListOf("source")
        when (type) {
            "video" -> {
                if (cfg.collect) keys += "collect"
                if (cfg.aiSummary) keys += "summary"
                if (cfg.channelStats) keys += "channel"
            }
            "channel" -> {
                keys += "channel"
                if (cfg.collect && cfg.collectTop > 0) keys += "collect"
            }
            "playlist" -> {
                if (cfg.collect && cfg.collectTop > 0) keys += "collect"
                if (cfg.channelStats) keys += "channels"
            }
        }
        if (cfg.recs) keys += "recs"
        if (cfg.channelGraph) keys += "chgraph"
        if (cfg.aiChannelSummary && type != "playlist") keys += "channel_ai"
        if (cfg.aiProjectSummary) keys += "project_ai"
        val steps = keys.map { QuickState.Step(it) }
        QuickState.save(pid, steps)
        L.i("quick", "start $type ${p0.source} steps=$keys")
        var channelUrl = ""
        for (st in steps) {
            if (cancel.get()) {
                st.status = "skipped"
                continue
            }
            st.status = "running"
            QuickState.save(pid, steps)
            progress(QuickState.label(st.key))
            try {
                val p = Store.project(pid) ?: break
                st.note = when (st.key) {
                    "source" -> {
                        when (type) {
                            "video" -> {
                                val v = YouTube.single(p.source)
                                Store.mergeVideos(pid, listOf(v.also { it.marked = true }))
                                channelUrl = v.channelUrl
                                rename(pid, v.title, v.channel)
                                v.title
                            }
                            "channel" -> {
                                val c = ChannelFetch.fetch(pid, p.source, recentLimit = maxOf(cfg.channelImport, cfg.channelRecent), force = true)
                                channelUrl = c.url
                                val vids = c.recent.take(cfg.channelImport).map { Video.from(it.toJson()) }
                                vids.sortedByDescending { it.views }.take(5).forEach { it.marked = true }
                                Store.mergeVideos(pid, vids)
                                rename(pid, c.name, c.name)
                                t("видео: ${vids.size}", "videos: ${vids.size}")
                            }
                            "playlist" -> {
                                val f = SearchFilters(limit = cfg.playlistLimit)
                                val vids = YouTube.importList(p.source, cfg.playlistLimit, f, cancel)
                                vids.sortedByDescending { it.views }.take(5).forEach { it.marked = true }
                                Store.mergeVideos(pid, vids)
                                val name = try {
                                    PlaylistInfo.getInfo(yt, p.source).name
                                } catch (e: Exception) {
                                    "playlist"
                                }
                                rename(pid, name, vids.groupingBy { it.channel }.eachCount().maxByOrNull { it.value }?.key ?: "")
                                channelUrl = vids.groupingBy { it.channelUrl }.eachCount().maxByOrNull { it.value }?.key ?: ""
                                t("видео: ${vids.size}", "videos: ${vids.size}")
                            }
                            else -> throw IllegalStateException(t("Не YouTube-ссылка", "Not a YouTube link"))
                        }
                    }
                    "collect" -> {
                        val all = Store.videos(pid).values.toList()
                        val target = if (type == "video") all else all.sortedByDescending { it.views }.take(cfg.collectTop)
                        var ok = 0
                        for ((i, v) in target.withIndex()) {
                            if (cancel.get()) break
                            progress(QuickState.label("collect") + " ${i + 1}/${target.size}")
                            try {
                                YouTube.collect(pid, p.phrase, v, p.collect)
                                Store.updateVideo(pid, v.id) { s -> copyCollected(v, s) }
                                ok++
                            } catch (e: Exception) {
                                L.w("quick", "collect ${v.id}: ${e.message}")
                            }
                        }
                        "$ok/${target.size}"
                    }
                    "summary" -> {
                        if (Provider.active() == null) {
                            st.status = "skipped"
                            t("AI не подключён", "AI not connected")
                        } else {
                            Store.videos(pid).values.forEach { v -> AiTasks.videoSummary(p, v) }
                            "OK"
                        }
                    }
                    "channel" -> {
                        if (channelUrl.isBlank()) channelUrl = Store.videos(pid).values.firstOrNull()?.channelUrl ?: ""
                        val c = ChannelFetch.fetch(pid, channelUrl, recentLimit = cfg.channelRecent)
                        if (c.error.isNotEmpty()) throw IllegalStateException(c.error)
                        ChannelStats.line(c)
                    }
                    "channels" -> t("каналов: ", "channels: ") + ChannelFetch.forVideos(pid, Store.videos(pid).values, cancel, 20) { progress(it) }
                    "recs" -> {
                        val pr = Store.project(pid)!!
                        pr.recs.maxNodes = cfg.recVideos
                        pr.recs.depth = cfg.recDepth
                        pr.recs.budget = maxOf(20, cfg.recVideos)
                        pr.recs.seeds = "marked"
                        pr.recs.seedCount = 5
                        pr.recs.relevance = if (cfg.topicFilter) "ai" else "off"
                        Store.save(pr)
                        RecEngine.run(pid, cancel) { progress(it) }
                    }
                    "chgraph" -> {
                        val seeds = listOf(channelUrl).filter { it.isNotBlank() }.ifEmpty {
                            Store.videos(pid).values.groupingBy { it.channelUrl }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }.filter { it.isNotBlank() }
                        }
                        ChannelExplorer.run(pid, seeds, ChannelExplorer.Config(depth = cfg.graphDepth, maxChannels = cfg.graphChannels, budget = cfg.graphChannels * 3, topic = cfg.topicFilter), cancel) { progress(it) }
                    }
                    "channel_ai" -> {
                        val c = ChannelStore.get(pid, channelUrl)
                        if (Provider.active() == null || c == null) {
                            st.status = "skipped"
                            t("нет AI или канала", "no AI or channel")
                        } else {
                            ChannelAi.summary(pid, c)
                            "OK"
                        }
                    }
                    "project_ai" -> {
                        if (Provider.active() == null) {
                            st.status = "skipped"
                            t("AI не подключён", "AI not connected")
                        } else {
                            AiTasks.projectSummary(p, Store.videos(pid).values.toList())
                            "OK"
                        }
                    }
                    else -> ""
                }
                if (st.status == "running") st.status = "done"
            } catch (e: Exception) {
                st.status = "error"
                st.note = (e.message ?: e.javaClass.simpleName).take(200)
                L.e("quick", "step ${st.key} failed", e)
            }
            QuickState.save(pid, steps)
        }
        Store.project(pid)?.let {
            it.lastRunAt = System.currentTimeMillis()
            it.runs.add(RunRecord(it.lastRunAt, "quick:$type", steps.count { s -> s.status == "done" }, steps.size, steps.filter { s -> s.status == "error" }.joinToString(",") { s -> s.key }))
            Store.save(it)
        }
        return t("Готово: ${steps.count { it.status == "done" }}/${steps.size} шагов", "Done: ${steps.count { it.status == "done" }}/${steps.size} steps")
    }

    private fun rename(pid: String, title: String, channel: String) {
        Store.project(pid)?.let {
            if (title.isNotBlank()) {
                it.name = title.take(80)
                it.phrase = title.take(120)
            }
            if (channel.isNotBlank()) it.domain = channel
            Store.save(it)
        }
    }

    fun copyCollected(v: Video, s: Video) {
        s.collected = true
        s.collectError = ""
        s.description = v.description
        s.tags = v.tags
        s.likes = v.likes
        s.category = v.category
        s.subscribers = v.subscribers
        s.hasTranscript = v.hasTranscript
        s.transcriptSource = v.transcriptSource
        s.commentsCount = v.commentsCount
        if (v.views >= 0) s.views = v.views
        if (v.uploadMs > 0) s.uploadMs = v.uploadMs
        if (v.duration > 0) s.duration = v.duration
        if (v.title.isNotBlank()) s.title = v.title
    }

    /** Creates the project, starts the background analysis and opens the project. */
    fun start(a: Activity, text: String): Boolean {
        val link = YtLink.classify(text)
        if (link.type == "unknown") {
            a.toast(t("Это не ссылка на видео, канал или плейлист YouTube", "Not a YouTube video, channel or playlist link"))
            return false
        }
        if (CollectState.running) {
            a.toast(t("Идёт другая задача — анализ начнётся, когда вы нажмёте «Перезапустить» в проекте", "Another task is running — tap Restart in the project later"))
        }
        val p = createProject(link)
        if (!CollectState.running) CollectService.startKind(a, p.id, "quick")
        a.startActivity(Intent(a, ProjectActivity::class.java).putExtra("id", p.id).putExtra("tab", "overview"))
        return true
    }
}

/** Entry from YouTube's "Share" sheet (text/plain with a link). */
class LinkActivity : LumenBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        L.i("link", "shared: ${text.take(200)}")
        if (!Settings.wizardDone) Settings.wizardDone = true
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        // a shared .txt file or plain text without a link: read it aloud
        if (stream != null) startActivity(Intent(this, TtsActivity::class.java).putExtra(Intent.EXTRA_STREAM, stream)
            .setClipData(android.content.ClipData.newRawUri("", stream)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        else if (text.isNotBlank() && YtLink.extractUrl(text) == null) TtsActivity.open(this, text)
        else QuickAnalysis.start(this, text)
        finish()
    }
}

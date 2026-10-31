/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.AlertDialog
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
        "style_data" -> t("Стиль: раскадровка, главы, речь, обложка", "Style: storyboard, chapters, speech, thumbnail")
        "style_ai" -> t("Стиль: описание и шаблон через AI", "Style: AI description and template")
        "pains" -> t("Боли и кейсы людей", "Pains and cases of people")
        "digest" -> t("AI-сводка комментариев", "AI comment digest")
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
    fun createProject(link: YtLink.Link, mode: String = ""): Project {
        val p = Project(name = when (mode) {
            "style" -> t("Стиль: ", "Style: ")
            "pains" -> t("Боли: ", "Pains: ")
            CommentDigest.MODE -> t("Комментарии: ", "Comments: ")
            else -> t("Анализ: ", "Analysis: ")
        } + link.type, phrase = "", queries = mutableListOf())
        if (mode == "pains" || mode == CommentDigest.MODE) {
            // more comments and the replies: stories are often in the threads
            p.collect.commentsLimit = if (link.type == "video") 1000 else 300
            p.collect.replies = true
            p.collect.commentsSort = "top"
            p.collect.subtitles = false
        }
        p.source = link.url
        p.sourceType = link.type
        p.quickMode = mode
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
        val style = p0.quickMode == "style" && type == "video"
        val pains = p0.quickMode == "pains"
        val digest = p0.quickMode == CommentDigest.MODE
        if (style) keys += listOf("style_data", "style_ai") else if (digest) {
            keys += listOf("collect", "digest")
        } else if (pains) {
            keys += listOf("collect", "pains")
        } else when (type) {
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
        if (!style && !pains && !digest) {
            if (cfg.recs) keys += "recs"
            if (cfg.channelGraph) keys += "chgraph"
            if (cfg.aiChannelSummary && type != "playlist") keys += "channel_ai"
            if (cfg.aiProjectSummary) keys += "project_ai"
        }
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
                                var blocked = false
                                val v = try {
                                    YouTube.single(p.source)
                                } catch (e: Exception) {
                                    // Bot check: take basic data from oEmbed so the next steps still have a video and a channel.
                                    val id = YtQuery.videoIdFromUrl(p.source)
                                    if (!YtBlock.isBotCheck(e) || id == null) throw e
                                    L.w("quick", "bot check, oEmbed fallback: ${e.message}")
                                    blocked = true
                                    try { OEmbed.fetch(id) } catch (x: Exception) { throw IllegalStateException(YtBlock.hint(), e) }
                                }
                                Store.mergeVideos(pid, listOf(v.also { it.marked = true }))
                                channelUrl = v.channelUrl
                                rename(pid, v.title, v.channel)
                                if (blocked) {
                                    st.status = "warn"
                                    v.title + t(" · только название и канал: ", " · title and channel only: ") + YtBlock.hint()
                                } else v.title
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
                        val top = if (p.quickMode == "pains") maxOf(cfg.collectTop, 10) else cfg.collectTop
                        val target = if (type == "video") all else all.sortedByDescending { it.views }.take(top)
                        if (target.isEmpty()) throw IllegalStateException(t("Нет видео для сбора", "No videos to collect"))
                        val ok = java.util.concurrent.atomic.AtomicInteger(0)
                        var lastError: Exception? = null
                        val started = java.util.concurrent.atomic.AtomicInteger(0)
                        Par.each(target, cancel = cancel) { _, v ->
                            progress(QuickState.label("collect") + " ${started.incrementAndGet()}/${target.size}")
                            try {
                                YouTube.collect(pid, p.phrase, v, p.collect)
                                Store.updateVideo(pid, v.id) { s -> copyCollected(v, s) }
                                ok.incrementAndGet()
                            } catch (e: Exception) {
                                lastError = e
                                L.w("quick", "collect ${v.id}: ${e.message}")
                            }
                        }
                        if (ok.get() == 0) throw IllegalStateException("0/${target.size}: " + (lastError?.let { YtBlock.message(it) } ?: ""), lastError)
                        "${ok.get()}/${target.size}"
                    }
                    "summary" -> {
                        if (Provider.active() == null) {
                            st.status = "skipped"
                            t("AI не подключён", "AI not connected")
                        } else {
                            val vids = Store.videos(pid).values.toList()
                            if (vids.isEmpty()) throw IllegalStateException(t("Нет видео", "No videos"))
                            val started = java.util.concurrent.atomic.AtomicInteger(0)
                            Par.each(vids, cancel = cancel) { _, v ->
                                progress(QuickState.label("summary") + " ${started.incrementAndGet()}/${vids.size}")
                                AiTasks.videoSummary(p, v)
                            }
                            "OK"
                        }
                    }
                    "channel" -> {
                        if (channelUrl.isBlank()) channelUrl = Store.videos(pid).values.firstOrNull()?.channelUrl ?: ""
                        if (channelUrl.isBlank()) throw IllegalStateException(t("Канал не определён: исходное видео не загрузилось", "Channel unknown: the source video did not load"))
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
                        if (seeds.isEmpty()) throw IllegalStateException(t("Нет каналов для старта графа", "No channels to start the graph"))
                        ChannelExplorer.run(pid, seeds, ChannelExplorer.Config(depth = cfg.graphDepth, maxChannels = cfg.graphChannels, budget = cfg.graphChannels * 3, topic = cfg.topicFilter), cancel) { progress(it) }
                    }
                    "channel_ai" -> {
                        val c = if (channelUrl.isBlank()) null else ChannelStore.get(pid, channelUrl)
                        if (Provider.active() == null || c == null) {
                            st.status = "skipped"
                            t("нет AI или канала", "no AI or channel")
                        } else {
                            ChannelAi.summary(pid, c)
                            "OK"
                        }
                    }
                    "style_data" -> {
                        val v = Store.videos(pid).values.firstOrNull() ?: throw IllegalStateException(t("Нет видео", "No video"))
                        val m = VideoStyle.measure(pid, v, p.collect.subLang) { progress(it) }
                        t("кадров ", "frames ") + m.frames + t(" · смена сцен ≈", " · scene changes ≈") + String.format("%.1f", m.cutsPerMin) + t("/мин", "/min") +
                            if (m.wpm > 0) " · " + m.wpm + t(" слов/мин", " wpm") else ""
                    }
                    "style_ai" -> {
                        val v = Store.videos(pid).values.firstOrNull()
                        val m = v?.let { StyleMetrics.load(pid, it.id) }
                        if (Provider.active() == null || v == null || m == null) {
                            st.status = "skipped"
                            t("AI не подключён — есть только метрики", "AI not connected — metrics only")
                        } else {
                            VideoStyle.ai(p, v, m, force = true).let { r -> r.model + " · " + Fmt.num((r.inTokens + r.outTokens).toLong()) + t(" ток.", " tok") }
                        }
                    }
                    "pains" -> {
                        val n = Pains.projectMessages(pid).size
                        if (n == 0) throw IllegalStateException(t("Комментарии не собраны (отключены или недоступны)", "No comments collected (disabled or unavailable)"))
                        if (Provider.active() == null) {
                            st.status = "warn"
                            t("комментариев: $n · AI не подключён — только сигналы по словам", "comments: $n · AI not connected — keyword signals only")
                        } else {
                            progress(t("AI ищет боли и кейсы…", "AI looks for pains and cases…"))
                            val r = Pains.ai(p, force = true)
                            val rep = Pains.parse(r.text, Pains.projectMessages(pid))
                            if (rep == null) {
                                st.status = "warn"
                                t("комментариев: $n · AI вернул нечитаемый ответ, показаны сигналы по словам", "comments: $n · unreadable AI reply, keyword signals shown")
                            } else {
                                t("комментариев: $n · болей ${rep.pains.size} · кейсов ${rep.cases.size}", "comments: $n · pains ${rep.pains.size} · cases ${rep.cases.size}")
                            }
                        }
                    }
                    "digest" -> {
                        val n = Pains.projectMessages(pid).size
                        if (n == 0) throw IllegalStateException(t("Комментарии не собраны (отключены или недоступны)", "No comments collected (disabled or unavailable)"))
                        if (Provider.active() == null) {
                            st.status = "warn"
                            t("комментариев: $n · AI не подключён — сводки нет", "comments: $n · AI not connected — no digest")
                        } else {
                            progress(t("AI делает сводку комментариев…", "AI summarises the comments…"))
                            val r = CommentDigest.ai(p, force = true)
                            t("комментариев: $n · ", "comments: $n · ") + r.model
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
                st.note = YtBlock.message(e).take(240)
                L.e("quick", "step ${st.key} failed", e)
            }
            QuickState.save(pid, steps)
        }
        Store.project(pid)?.let {
            it.lastRunAt = System.currentTimeMillis()
            it.runs.add(RunRecord(it.lastRunAt, "quick:$type", steps.count { s -> s.status == "done" || s.status == "warn" }, steps.size, steps.filter { s -> s.status == "error" }.joinToString(",") { s -> s.key }))
            Store.save(it)
        }
        val ok = steps.count { it.status == "done" || it.status == "warn" }
        return t("Готово: $ok/${steps.size} шагов", "Done: $ok/${steps.size} steps")
    }

    private fun rename(pid: String, title: String, channel: String) {
        Store.project(pid)?.let {
            if (title.isNotBlank()) {
                it.name = (when (it.quickMode) { "style" -> "🎨 "; "pains" -> "😣 "; CommentDigest.MODE -> "💬 "; else -> "" }) + title.take(80)
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

    /** Creates the project, starts the background analysis and opens the project. [mode] "style" = video style only. */
    fun start(a: Activity, text: String, mode: String = ""): Boolean {
        val link = YtLink.classify(text)
        if (link.type == "unknown") {
            a.toast(t("Это не ссылка на видео, канал или плейлист YouTube", "Not a YouTube video, channel or playlist link"))
            return false
        }
        if ((mode == "style" || mode == CommentDigest.MODE) && link.type != "video") {
            a.toast(if (mode == "style") t("Стиль определяется только для видео", "Style works for videos only")
            else t("Сводка комментариев — только для ссылки на видео", "Comment digest works for video links only"))
            return false
        }
        if (CollectState.running) {
            a.toast(t("Идёт другая задача — анализ начнётся, когда вы нажмёте «Перезапустить» в проекте", "Another task is running — tap Restart in the project later"))
        }
        val p = createProject(link, mode)
        if (!CollectState.running) CollectService.startKind(a, p.id, "quick")
        a.startActivity(Intent(a, ProjectActivity::class.java).putExtra("id", p.id).putExtra("tab", "overview"))
        return true
    }
}

/**
 * For a video link asks what to do: the usual analysis, video style, or one focused collection
 * (similar channels / similar videos / by period / by filters / by AI filter) that opens its own result screen.
 * A channel link offers the analysis or similar channels; playlists go straight to the analysis. [onDone] runs after a choice or cancel.
 */
fun Activity.chooseLinkAction(text: String, onDone: () -> Unit = {}) {
    val link = YtLink.classify(text)
    if (link.type != "video" && link.type != "channel") {
        QuickAnalysis.start(this, text)
        onDone()
        return
    }
    var chosen = false
    val theme = if (isNight()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    val actions: List<Pair<String, () -> Unit>> = if (link.type == "channel") listOf(
        t("📊 Полный анализ канала", "📊 Full channel analysis") to { QuickAnalysis.start(this, link.url); onDone() },
        t("😣 Боли и кейсы зрителей канала", "😣 Pains and cases of the channel's viewers") to { QuickAnalysis.start(this, link.url, "pains"); onDone() },
        t("📺 Только похожие каналы по теме", "📺 Only similar channels on the topic") to { harvestSetup(link.url, "channels", onDone) }
    ) else listOf(
        t("💬 Сводка комментариев + чат с AI", "💬 Comment digest + AI chat") to { QuickAnalysis.start(this, link.url, CommentDigest.MODE); onDone() },
        t("📊 Полный анализ", "📊 Full analysis") to { QuickAnalysis.start(this, link.url); onDone() },
        t("😣 Боли и кейсы людей (отчёт)", "😣 Pains and cases of people (report)") to { QuickAnalysis.start(this, link.url, "pains"); onDone() },
        t("🎨 Определить стиль видео", "🎨 Detect video style") to { QuickAnalysis.start(this, link.url, "style"); onDone() },
        t("🧪 Шаблон монтажа по этому видео (Studio, AI)", "🧪 Editing template from this video (Studio, AI)") to { StudioTemplatesActivity.openFromVideo(this, link.url); onDone() },
        t("📺 Только похожие каналы по теме", "📺 Only similar channels on the topic") to { harvestSetup(link.url, "channels", onDone) },
        t("▶ Только похожие и рекомендованные видео", "▶ Only similar & recommended videos") to { harvestSetup(link.url, "related", onDone) },
        t("📅 Похожие видео за период…", "📅 Similar videos by period…") to { harvestSetup(link.url, "period", onDone) },
        t("🎚 Похожие видео по параметрам…", "🎚 Similar videos by filters…") to { harvestSetup(link.url, "params", onDone) },
        t("🤖 Похожие видео по AI-фильтру…", "🤖 Similar videos by AI filter…") to { harvestSetup(link.url, "ai", onDone) },
        MediaLink.label(MediaAction.BACKGROUND) to { runMedia(link.url, MediaAction.BACKGROUND); onDone() },
        MediaLink.label(MediaAction.AUDIO) to { runMedia(link.url, MediaAction.AUDIO); onDone() },
        MediaLink.label(MediaAction.VIDEO) to { runMedia(link.url, MediaAction.VIDEO); onDone() }
    )
    AlertDialog.Builder(this, theme).setTitle(if (link.type == "channel") t("Что сделать с каналом?", "What to do with the channel?") else t("Что сделать с видео?", "What to do with the video?"))
        .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
            chosen = true
            actions[which].second()
        }
        .setOnDismissListener { if (!chosen) onDone() }
        .show()
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
        if (stream != null) {
            startActivity(Intent(this, TtsActivity::class.java).putExtra(Intent.EXTRA_STREAM, stream).apply {
                clipData = android.content.ClipData.newRawUri("", stream)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            finish()
        } else if (text.isNotBlank() && YtLink.extractUrl(text) == null) {
            TtsActivity.open(this, text)
            finish()
        } else if (intent.component?.className?.endsWith(".CommentsLink") == true) {
            // share target "Lumen: comments": collect and summarise this video's comments right away, no menu
            QuickAnalysis.start(this, text, CommentDigest.MODE)
            finish()
        } else {
            // a video link: choose analysis or video style
            chooseLinkAction(text) { finish() }
        }
    }
}

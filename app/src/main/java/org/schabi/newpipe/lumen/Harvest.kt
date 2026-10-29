/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a focused collection from the "Share" sheet gathers.
 * mode: channels | related | period | params | ai. Times in ms (-1 = no bound), durations in seconds (0 = no bound).
 */
data class HarvestSpec(
    var mode: String = "related",
    var target: Int = 20,
    var fromMs: Long = -1,
    var toMs: Long = -1,
    var periodLabel: String = "",
    var minViews: Long = 0,
    var minSubs: Long = 0,
    var minDur: Long = 0,
    var maxDur: Long = 0,
    var noShorts: Boolean = false,
    var prompt: String = "",
    var seed: String = "",
    var seedTitle: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("target", target).put("fromMs", fromMs).put("toMs", toMs)
        .put("periodLabel", periodLabel).put("minViews", minViews).put("minSubs", minSubs).put("minDur", minDur)
        .put("maxDur", maxDur).put("noShorts", noShorts).put("prompt", prompt).put("seed", seed).put("seedTitle", seedTitle)

    companion object {
        fun from(o: JSONObject?) = HarvestSpec().apply {
            if (o == null) return@apply
            mode = o.optString("mode", mode)
            target = o.optInt("target", target)
            fromMs = o.optLong("fromMs", fromMs)
            toMs = o.optLong("toMs", toMs)
            periodLabel = o.optString("periodLabel")
            minViews = o.optLong("minViews")
            minSubs = o.optLong("minSubs")
            minDur = o.optLong("minDur")
            maxDur = o.optLong("maxDur")
            noShorts = o.optBoolean("noShorts")
            prompt = o.optString("prompt")
            seed = o.optString("seed")
            seedTitle = o.optString("seedTitle")
        }

        /** Last used settings of a mode (count, period, thresholds, AI filter). */
        fun last(mode: String): HarvestSpec = from(Lumen.prefs.getString("harvest_$mode", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } })
            .also { it.mode = mode; it.seed = ""; it.seedTitle = "" }

        fun remember(s: HarvestSpec) = Lumen.prefs.edit().putString("harvest_${s.mode}", s.toJson().toString()).apply()
    }
}

/** One found video or channel (for channels: views = subscribers, url = channel url). */
data class HarvestItem(
    val id: String,
    var url: String = "",
    var title: String = "",
    var channel: String = "",
    var channelUrl: String = "",
    var views: Long = -1,
    var subs: Long = -1,
    var duration: Long = -1,
    var uploadMs: Long = -1,
    var uploadText: String = "",
    var thumb: String = "",
    var isShort: Boolean = false,
    var depth: Int = 0,
    var note: String = "",
    var order: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title).put("channel", channel)
        .put("channelUrl", channelUrl).put("views", views).put("subs", subs).put("duration", duration).put("uploadMs", uploadMs)
        .put("uploadText", uploadText).put("thumb", thumb).put("isShort", isShort).put("depth", depth).put("note", note).put("order", order)

    fun toVideo() = Video(
        id = id, url = url, title = title, channel = channel, channelUrl = channelUrl, uploadMs = uploadMs, uploadText = uploadText,
        views = views, duration = duration, thumb = thumb, isShort = isShort, query = "harvest", subscribers = subs
    )

    companion object {
        fun from(o: JSONObject) = HarvestItem(
            id = o.getString("id"), url = o.optString("url"), title = o.optString("title"), channel = o.optString("channel"),
            channelUrl = o.optString("channelUrl"), views = o.optLong("views", -1), subs = o.optLong("subs", -1),
            duration = o.optLong("duration", -1), uploadMs = o.optLong("uploadMs", -1), uploadText = o.optString("uploadText"),
            thumb = o.optString("thumb"), isShort = o.optBoolean("isShort"), depth = o.optInt("depth"), note = o.optString("note"),
            order = o.optInt("order")
        )
    }
}

/** Progress and results of a collection, stored in harvest.json of its project. */
class HarvestState(var spec: HarvestSpec = HarvestSpec()) {
    val items = LinkedHashMap<String, HarvestItem>()
    var status = "pending" // pending | running | done | stopped | error
    var note = ""
    var requests = 0
    var checked = 0
    var updatedAt = 0L

    fun toJson(): JSONObject = JSONObject().put("spec", spec.toJson()).put("status", status).put("note", note)
        .put("requests", requests).put("checked", checked).put("updatedAt", updatedAt)
        .put("items", JSONArray().also { a -> items.values.forEach { a.put(it.toJson()) } })

    companion object {
        const val FILE = "harvest.json"

        fun parse(text: String?): HarvestState? {
            text ?: return null
            return try {
                val o = JSONObject(text)
                HarvestState(HarvestSpec.from(o.optJSONObject("spec"))).apply {
                    status = o.optString("status", status)
                    note = o.optString("note")
                    requests = o.optInt("requests")
                    checked = o.optInt("checked")
                    updatedAt = o.optLong("updatedAt")
                    val a = o.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        try {
                            HarvestItem.from(a.getJSONObject(i)).let { items[it.id] = it }
                        } catch (e: Exception) {
                            // skip a broken item, keep the rest
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun load(pid: String): HarvestState? = parse(Store.readFile(pid, FILE))
        fun save(pid: String, s: HarvestState) {
            s.updatedAt = System.currentTimeMillis()
            Store.writeFile(pid, FILE, s.toJson().toString())
        }
    }
}

/** Pure rules of a collection (covered by unit tests). */
object HarvestRules {
    const val DAY = 86_400_000L
    const val AI_BATCH = 15

    fun label(mode: String) = when (mode) {
        "channels" -> t("Похожие каналы", "Similar channels")
        "related" -> t("Похожие видео", "Similar videos")
        "period" -> t("Похожие за период", "Similar by period")
        "params" -> t("Похожие по параметрам", "Similar by filters")
        "ai" -> t("Похожие по AI-фильтру", "Similar by AI filter")
        else -> mode
    }

    fun isVideoMode(mode: String) = mode != "channels"

    /** Cheap checks on data that comes with a recommendation (no extra requests). Unknown values fail an active filter. */
    fun cheapOk(s: HarvestSpec, it: HarvestItem): Boolean {
        if (s.noShorts && it.isShort) return false
        if (s.minViews > 0 && it.views < s.minViews) return false
        if (s.minDur > 0 && (it.duration <= 0 || it.duration < s.minDur)) return false
        if (s.maxDur > 0 && (it.duration <= 0 || it.duration > s.maxDur)) return false
        if (s.fromMs > 0 && (it.uploadMs <= 0 || it.uploadMs < s.fromMs)) return false
        if (s.toMs > 0 && (it.uploadMs <= 0 || it.uploadMs >= s.toMs)) return false
        return true
    }

    fun needsSubs(s: HarvestSpec) = s.minSubs > 0

    fun subsOk(s: HarvestSpec, subs: Long) = s.minSubs <= 0 || subs >= s.minSubs

    /** [start, end) of a calendar year in UTC. */
    fun yearRange(year: Int): Pair<Long, Long> {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(year, Calendar.JANUARY, 1)
        val from = c.timeInMillis
        c.set(year + 1, Calendar.JANUARY, 1)
        return from to c.timeInMillis
    }

    /** Preset "last N": 1m 3m 6m 1y 2y 5y; returns (from, -1). */
    fun preset(key: String, now: Long): Pair<Long, Long> {
        val days = when (key) {
            "1w" -> 7L
            "1m" -> 30L
            "3m" -> 91L
            "6m" -> 182L
            "1y" -> 365L
            "2y" -> 730L
            "5y" -> 1826L
            else -> return -1L to -1L
        }
        return (now - days * DAY) to -1L
    }

    /** "10k", "10 000", "1,5 млн", "2M", "300к" → number; blank or junk → 0. */
    fun parseCount(raw: String): Long {
        val s = raw.trim().lowercase(Locale.ROOT).replace(" ", "").replace(" ", "").replace(',', '.')
        if (s.isEmpty()) return 0
        val m = Regex("""^(\d+(?:\.\d+)?)(k|к|тыс\.?|m|м|млн|b|млрд)?$""").find(s) ?: return 0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0
        val mul = when (m.groupValues[2].trimEnd('.')) {
            "k", "к", "тыс" -> 1e3
            "m", "м", "млн" -> 1e6
            "b", "млрд" -> 1e9
            else -> 1.0
        }
        return (n * mul).toLong()
    }

    /** Minutes as typed ("10", "1:30" = 1 h 30 min) → seconds; blank → 0. */
    fun parseMinutes(raw: String): Long {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty()) return 0
        if (s.contains(':')) {
            val (h, m) = s.split(':', limit = 2)
            return ((h.toLongOrNull() ?: 0) * 60 + (m.toLongOrNull() ?: 0)) * 60
        }
        return ((s.toDoubleOrNull() ?: 0.0) * 60).toLong()
    }

    fun minutesText(sec: Long): String = if (sec <= 0) "" else if (sec % 60 == 0L) (sec / 60).toString() else String.format(Locale.US, "%.1f", sec / 60.0)

    /** Short human summary of the conditions (chips on the result screen). */
    fun conditions(s: HarvestSpec): List<String> = buildList {
        if (s.periodLabel.isNotBlank()) add("📅 " + s.periodLabel.substringAfter('|'))
        if (s.minViews > 0) add(t("👁 от ", "👁 from ") + Fmt.num(s.minViews))
        if (s.minSubs > 0) add(t("👥 от ", "👥 from ") + Fmt.num(s.minSubs))
        if (s.minDur > 0 || s.maxDur > 0) add("⏱ " + (if (s.minDur > 0) Fmt.duration(s.minDur) else "0") + "–" + (if (s.maxDur > 0) Fmt.duration(s.maxDur) else "∞"))
        if (s.noShorts) add(t("без Shorts", "no Shorts"))
        if (s.mode == "ai" && s.prompt.isNotBlank()) add("🤖 " + s.prompt.take(40) + if (s.prompt.length > 40) "…" else "")
        add(t("цель: ", "target: ") + s.target)
    }

    /** Request budget: enough to dig for rare matches, bounded so a run cannot go on forever. */
    fun budget(s: HarvestSpec): Int = when (s.mode) {
        "related" -> maxOf(5, s.target / 8 + 3)
        "period", "params", "ai" -> (s.target * 6).coerceIn(40, 600)
        else -> s.target * 3
    }

    fun aiPrompt(filter: String, lines: List<String>, english: Boolean): String = buildString {
        append("You check YouTube videos against the user's filter.\n")
        append("Filter: ").append(filter.trim()).append("\n\n")
        append("Videos:\n")
        lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
        append("\nReturn JSON only: {\"relevant\":[numbers of the videos that match the filter]}. ")
        append("Be strict: include a video only if it clearly matches. An empty array is fine. ")
        append("Language of any text: ").append(if (english) "English" else "русский").append('.')
    }

    fun aiLine(it: HarvestItem): String = it.title + " — " + it.channel +
        (if (it.views >= 0) " · ${Fmt.num(it.views)} views" else "") +
        (if (it.duration > 0) " · ${Fmt.duration(it.duration)}" else "") +
        (if (it.uploadMs > 0) " · ${Fmt.isoDay(it.uploadMs)}" else if (it.uploadText.isNotBlank()) " · ${it.uploadText}" else "")

    fun sorted(items: Collection<HarvestItem>, by: String): List<HarvestItem> = when (by) {
        "views" -> items.sortedByDescending { it.views }
        "date" -> items.sortedByDescending { it.uploadMs }
        "duration" -> items.sortedByDescending { it.duration }
        "subs" -> items.sortedByDescending { it.subs }
        else -> items.sortedBy { it.order }
    }

    /** Plain text list to share or copy. */
    fun listText(state: HarvestState, by: String = ""): String = buildString {
        append(label(state.spec.mode))
        if (state.spec.seedTitle.isNotBlank()) append(" — ").append(state.spec.seedTitle)
        append('\n')
        sorted(state.items.values, by).forEachIndexed { i, it ->
            append(i + 1).append(". ").append(it.title)
            if (state.spec.mode != "channels" && it.channel.isNotBlank()) append(" — ").append(it.channel)
            append('\n').append(it.url).append('\n')
        }
    }
}

/** Runs a focused collection in the background (CollectService kind "harvest"). */
object Harvest {
    private val yt get() = ServiceList.YouTube

    private fun pause() {
        val d = Settings.requestDelayMs
        if (d > 0) Thread.sleep(d.toLong())
    }

    private fun item(it: StreamInfoItem, depth: Int): HarvestItem? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        return HarvestItem(
            id = id, url = "https://www.youtube.com/watch?v=$id", title = it.name ?: "", channel = it.uploaderName ?: "",
            channelUrl = it.uploaderUrl ?: "", views = it.viewCount, duration = it.duration,
            uploadMs = it.uploadDate?.instant?.toEpochMilli() ?: -1L, uploadText = it.textualUploadDate ?: "",
            thumb = it.thumbnails.maxByOrNull { img -> img.width }?.url ?: "",
            isShort = it.isShortFormContent || it.url.contains("/shorts/") || it.duration in 1..60, depth = depth
        )
    }

    private class Page(val title: String, val channel: String, val channelUrl: String, val subs: Long, val related: List<StreamInfoItem>)

    /** Watch page of a video: cheap getters only, no stream deciphering. */
    private fun page(url: String): Page {
        pause()
        val ex = yt.getStreamExtractor(url)
        ex.fetchPage()
        fun <T> safe(f: () -> T): T? = try {
            f()
        } catch (e: Exception) {
            null
        }
        return Page(
            safe { ex.name } ?: "", safe { ex.uploaderName } ?: "", safe { ex.uploaderUrl } ?: "",
            safe { ex.uploaderSubscriberCount } ?: -1L,
            safe { ex.relatedItems?.items }?.filterIsInstance<StreamInfoItem>() ?: emptyList()
        )
    }

    /** Creates the project + harvest.json; the service then runs [run]. */
    fun create(link: YtLink.Link, spec: HarvestSpec): Project {
        val p = Project(name = HarvestRules.label(spec.mode), phrase = "", queries = mutableListOf())
        p.source = link.url
        p.sourceType = link.type
        p.quickMode = "harvest"
        p.recs.relevance = "ai"
        Store.save(p)
        spec.seed = link.url
        HarvestState.save(p.id, HarvestState(spec))
        return p
    }

    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val st = HarvestState.load(pid) ?: throw IllegalStateException(t("Нет условий сбора", "No collection settings"))
        st.status = "running"
        st.note = ""
        HarvestState.save(pid, st)
        L.i("harvest", "start ${st.spec.mode} target=${st.spec.target} seed=${st.spec.seed}")
        try {
            if (st.spec.mode == "channels") channels(pid, st, cancel, progress) else videos(pid, st, cancel, progress)
            st.status = if (cancel.get()) "stopped" else "done"
        } catch (e: Exception) {
            st.status = "error"
            st.note = (e.message ?: e.javaClass.simpleName).take(200)
            L.e("harvest", "failed", e)
        }
        HarvestState.save(pid, st)
        if (HarvestRules.isVideoMode(st.spec.mode)) Store.mergeVideos(pid, st.items.values.map { it.toVideo() })
        Store.project(pid)?.let {
            it.lastRunAt = System.currentTimeMillis()
            it.runs.add(RunRecord(it.lastRunAt, "harvest:${st.spec.mode}", st.requests, st.items.size, st.status))
            Store.save(it)
        }
        return t("Найдено: ${st.items.size}/${st.spec.target} · запросов: ${st.requests}", "Found: ${st.items.size}/${st.spec.target} · requests: ${st.requests}")
    }

    private fun rename(pid: String, st: HarvestState, title: String, channel: String) {
        if (title.isBlank()) return
        st.spec.seedTitle = title
        Store.project(pid)?.let {
            it.name = HarvestRules.label(st.spec.mode) + ": " + title.take(70)
            if (it.phrase.isBlank()) it.phrase = title.take(120)
            if (it.domain.isBlank()) it.domain = channel
            Store.save(it)
        }
    }

    /** Breadth-first walk over "Up next" from the seed; matches are expanded first, so the walk stays near the topic. */
    private fun videos(pid: String, st: HarvestState, cancel: AtomicBoolean, progress: (String) -> Unit) {
        val s = st.spec
        val seedId = YtQuery.videoIdFromUrl(s.seed) ?: throw IllegalStateException(t("Нужна ссылка на видео", "A video link is needed"))
        val budget = HarvestRules.budget(s) + st.requests
        val seen = HashSet<String>().apply { add(seedId); addAll(st.items.keys) }
        val expanded = HashSet<String>()
        val good = ArrayDeque<HarvestItem>()
        val rest = ArrayDeque<HarvestItem>()
        good.add(HarvestItem(seedId, url = "https://www.youtube.com/watch?v=$seedId"))
        st.items.values.sortedBy { it.order }.forEach { good.add(it) } // "collect more" continues from the found ones
        val subsCache = HashMap<String, Long>()
        val pendingAi = ArrayList<HarvestItem>()
        val prov = if (s.mode == "ai") Provider.active() ?: throw IllegalStateException(t("Подключите AI в настройках", "Connect AI in settings")) else null
        var order = (st.items.values.maxOfOrNull { it.order } ?: 0) + 1

        fun full() = st.items.size >= s.target
        fun accept(h: HarvestItem) {
            if (full()) return
            h.order = order++
            st.items[h.id] = h
            good.add(h)
        }
        fun subs(url: String): Long = subsCache.getOrPut(ChannelStore.key(url)) {
            if (url.isBlank()) return -1
            try {
                pause()
                st.requests++
                ChannelInfo.getInfo(yt, url).subscriberCount
            } catch (e: Exception) {
                L.w("harvest", "subs of $url: ${e.message}")
                -1L
            }
        }
        fun judgeAi(force: Boolean) {
            while (prov != null && pendingAi.isNotEmpty() && (force || pendingAi.size >= HarvestRules.AI_BATCH) && !full() && !cancel.get()) {
                val batch = pendingAi.take(HarvestRules.AI_BATCH)
                pendingAi.subList(0, batch.size).clear()
                progress(t("AI проверяет ${batch.size} видео · найдено ${st.items.size}/${s.target}", "AI checks ${batch.size} videos · found ${st.items.size}/${s.target}"))
                val prompt = HarvestRules.aiPrompt(s.prompt, batch.map { HarvestRules.aiLine(it) }, Settings.lang == "en")
                var yes: Set<Int>? = null
                for (attempt in 0..1) {
                    try {
                        val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 500, task = "harvest_ai", pid = pid)
                        if (TopicFilter.hasVerdict(r.text)) {
                            yes = TopicFilter.parseIndexes(r.text)
                            break
                        }
                    } catch (e: Exception) {
                        L.w("harvest", "ai batch: ${e.message}")
                    }
                }
                batch.forEachIndexed { i, h -> if (yes?.contains(i + 1) == true) accept(h.also { it.note = "AI ✓" }) }
                HarvestState.save(pid, st)
            }
        }

        while (!full() && !cancel.get() && st.requests < budget) {
            val n = good.removeFirstOrNull()?.takeIf { it.id !in expanded } ?: rest.removeFirstOrNull()?.takeIf { it.id !in expanded }
            if (n == null) {
                if (good.isEmpty() && rest.isEmpty()) {
                    if (pendingAi.isEmpty()) break
                    judgeAi(true)
                    continue
                }
                continue
            }
            expanded.add(n.id)
            progress(t("Найдено ${st.items.size}/${s.target} · проверено ${st.checked} · запросов ${st.requests}/$budget\n",
                "Found ${st.items.size}/${s.target} · checked ${st.checked} · requests ${st.requests}/$budget\n") + n.title.ifBlank { n.url })
            val pg = try {
                page(n.url).also { st.requests++ }
            } catch (e: Exception) {
                st.requests++
                L.w("harvest", "page ${n.id}: ${e.message}")
                continue
            }
            if (n.id == seedId) {
                if (s.seedTitle.isBlank()) rename(pid, st, pg.title, pg.channel)
                if (pg.channelUrl.isNotBlank() && pg.subs >= 0) subsCache[ChannelStore.key(pg.channelUrl)] = pg.subs
            } else if (pg.channelUrl.isNotBlank() && pg.subs >= 0) {
                subsCache[ChannelStore.key(pg.channelUrl)] = pg.subs
            }
            for (ri in pg.related) {
                if (full() || cancel.get()) break
                val h = item(ri, n.depth + 1) ?: continue
                if (!seen.add(h.id)) continue
                st.checked++
                rest.add(h)
                if (s.mode == "related") {
                    accept(h)
                    continue
                }
                if (!HarvestRules.cheapOk(s, h)) continue
                if (HarvestRules.needsSubs(s)) {
                    h.subs = subs(h.channelUrl)
                    if (!HarvestRules.subsOk(s, h.subs)) continue
                }
                if (s.mode == "ai") pendingAi.add(h) else accept(h)
            }
            judgeAi(false)
            HarvestState.save(pid, st)
        }
        if (!cancel.get()) judgeAi(true)
        if (!full() && !cancel.get()) {
            st.note = if (st.requests >= budget) t("Лимит запросов исчерпан — нажмите «Собрать ещё»", "Request limit reached — tap “Collect more”")
            else t("Рекомендации закончились", "No more recommendations")
        }
    }

    /** Channel graph around the seed's channel with the topic filter; results = on-topic channels by link strength. */
    private fun channels(pid: String, st: HarvestState, cancel: AtomicBoolean, progress: (String) -> Unit) {
        val s = st.spec
        val link = YtLink.classify(s.seed)
        val channelUrl = if (link.type == "channel") link.url else {
            progress(t("Загрузка видео…", "Loading video…"))
            val pg = page(link.url)
            st.requests++
            rename(pid, st, pg.title, pg.channel)
            YtQuery.videoIdFromUrl(link.url)?.let { id ->
                Store.mergeVideos(pid, listOf(Video(id = id, url = link.url, title = pg.title, channel = pg.channel, channelUrl = pg.channelUrl, marked = true)))
            }
            pg.channelUrl
        }
        if (channelUrl.isBlank()) throw IllegalStateException(t("Не удалось определить канал", "Could not find the channel"))
        val q = QuickConfig.load()
        val summary = ChannelExplorer.run(pid, listOf(channelUrl),
            ChannelExplorer.Config(depth = maxOf(2, q.graphDepth), maxChannels = s.target + 1, budget = s.target * 3, topic = true), cancel) { progress(it) }
        val g = RecGraph.load(pid, RecGraph.CHANNELS_FILE)
        if (link.type == "channel") g.nodes.values.firstOrNull { it.seed }?.let { rename(pid, st, it.title, it.title) }
        st.items.clear()
        g.nodes.values.filter { !it.seed && it.relevant != 0 }.sortedWith(compareByDescending<RecNode> { it.weight }.thenByDescending { it.pr })
            .take(s.target).forEachIndexed { i, n ->
                st.items[n.id] = HarvestItem(n.id, url = n.url, title = n.title, channel = n.title, channelUrl = n.url, views = n.views, subs = n.views,
                    thumb = n.thumb, depth = n.depth, note = n.uploadText, order = i + 1)
            }
        st.requests += g.requests
        st.checked = g.nodes.size
        st.note = summary
    }
}

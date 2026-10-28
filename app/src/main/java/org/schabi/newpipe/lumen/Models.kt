/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.strings(): MutableList<String> {
    val out = mutableListOf<String>()
    if (this == null) return out
    for (i in 0 until length()) out.add(optString(i))
    return out
}

internal fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

data class SearchFilters(
    var sort: String = "relevance", // relevance | date | views | rating
    var period: String = "any", // any | hour | today | week | month | year | custom
    var from: String = "", // yyyy-MM-dd for custom
    var to: String = "",
    var duration: String = "any", // any | short | medium | long
    var excludeShorts: Boolean = false,
    var limit: Int = 50,
    var country: String = "", // ISO 3166 region of YouTube search ("" = app default)
    var origLang: String = "" // ISO 639-1 language of the video itself ("" = any)
) {
    fun toJson() = JSONObject().put("sort", sort).put("period", period).put("from", from).put("to", to)
        .put("duration", duration).put("excludeShorts", excludeShorts).put("limit", limit)
        .put("country", country).put("origLang", origLang)

    companion object {
        fun from(o: JSONObject?) = SearchFilters().apply {
            if (o == null) return@apply
            sort = o.optString("sort", sort)
            period = o.optString("period", period)
            from = o.optString("from", "")
            to = o.optString("to", "")
            duration = o.optString("duration", duration)
            excludeShorts = o.optBoolean("excludeShorts", false)
            limit = o.optInt("limit", 50)
            country = o.optString("country", "")
            origLang = o.optString("origLang", "")
        }
    }
}

data class CollectConfig(
    var meta: Boolean = true,
    var subtitles: Boolean = true,
    var subLang: String = "auto", // auto | ru | en | ...
    var comments: Boolean = true,
    var commentsLimit: Int = 200,
    var commentsSort: String = "top", // top | new
    var replies: Boolean = false,
    var aiTranscribe: Boolean = false,
    var channelStats: Boolean = true
) {
    fun toJson() = JSONObject().put("meta", meta).put("subtitles", subtitles).put("subLang", subLang)
        .put("comments", comments).put("commentsLimit", commentsLimit).put("commentsSort", commentsSort)
        .put("replies", replies).put("aiTranscribe", aiTranscribe).put("channelStats", channelStats)

    companion object {
        fun from(o: JSONObject?) = CollectConfig().apply {
            if (o == null) return@apply
            meta = o.optBoolean("meta", true)
            subtitles = o.optBoolean("subtitles", true)
            subLang = o.optString("subLang", "auto")
            comments = o.optBoolean("comments", true)
            commentsLimit = o.optInt("commentsLimit", 200)
            commentsSort = o.optString("commentsSort", "top")
            replies = o.optBoolean("replies", false)
            aiTranscribe = o.optBoolean("aiTranscribe", false)
            channelStats = o.optBoolean("channelStats", true)
        }
    }
}

data class MonitorConfig(
    var enabled: Boolean = false,
    var hours: Int = 24,
    var wifiOnly: Boolean = true,
    var charging: Boolean = false,
    var aiSummary: Boolean = false,
    var aiFilter: Boolean = false, // judge new videos against the topic before adding them (WatchFilter)
    var recDrip: Int = 0 // recommended videos added to the project per monitoring run
) {
    fun toJson() = JSONObject().put("enabled", enabled).put("hours", hours).put("wifiOnly", wifiOnly)
        .put("charging", charging).put("aiSummary", aiSummary).put("aiFilter", aiFilter).put("recDrip", recDrip)

    companion object {
        fun from(o: JSONObject?) = MonitorConfig().apply {
            if (o == null) return@apply
            enabled = o.optBoolean("enabled", false)
            hours = o.optInt("hours", 24)
            wifiOnly = o.optBoolean("wifiOnly", true)
            charging = o.optBoolean("charging", false)
            aiSummary = o.optBoolean("aiSummary", false)
            aiFilter = o.optBoolean("aiFilter", false)
            recDrip = o.optInt("recDrip", 0)
        }
    }
}

/** Product / audience / site context for the Audience-to-SEO pipeline. */
data class SeoContext(
    var product: String = "",
    var audience: String = "",
    var country: String = "",
    var language: String = "",
    var site: String = "",
    var existingPages: String = "" // one per line: "URL | title"
) {
    fun toJson(): JSONObject = JSONObject().put("product", product).put("audience", audience).put("country", country)
        .put("language", language).put("site", site).put("existingPages", existingPages)

    fun summary(p: Project): String = buildString {
        append("Тема/phrase: ").append(p.phrase)
        if (p.domain.isNotBlank()) append(" (").append(p.domain).append(")")
        if (product.isNotBlank()) append("\nПродукт/product: ").append(product)
        if (audience.isNotBlank()) append("\nАудитория/audience: ").append(audience)
        if (country.isNotBlank() || language.isNotBlank()) append("\nРынок/market: ").append(country).append(" ").append(language)
        if (site.isNotBlank()) append("\nСайт/site: ").append(site)
    }

    companion object {
        fun from(o: JSONObject?) = SeoContext().apply {
            if (o == null) return@apply
            product = o.optString("product")
            audience = o.optString("audience")
            country = o.optString("country")
            language = o.optString("language")
            site = o.optString("site")
            existingPages = o.optString("existingPages")
        }
    }
}

/** Settings of the recommendation explorer ("Recommendations" tab). */
data class RecConfig(
    var mode: String = "snowball", // snowball | chain | mix
    var seeds: String = "marked", // marked | top | all | custom
    var seedCount: Int = 5,
    var customSeeds: String = "",
    var depth: Int = 10,
    var fanout: Int = 5,
    var budget: Int = 150, // max YouTube requests per run
    var maxNodes: Int = 500,
    var relevance: String = "ai", // off | keywords | ai (ai falls back to keywords without a provider)
    var keywords: String = "",
    var expandOffTopic: Boolean = false,
    var excludeShorts: Boolean = true,
    var minViews: Long = 0,
    var maxPerChannel: Int = 0,
    var useProjectFilters: Boolean = false,
    var blocked: String = "",
    var keepGraph: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("seeds", seeds).put("seedCount", seedCount)
        .put("customSeeds", customSeeds).put("depth", depth).put("fanout", fanout).put("budget", budget)
        .put("maxNodes", maxNodes).put("relevance", relevance).put("keywords", keywords)
        .put("expandOffTopic", expandOffTopic).put("excludeShorts", excludeShorts).put("minViews", minViews)
        .put("maxPerChannel", maxPerChannel).put("useProjectFilters", useProjectFilters).put("blocked", blocked)
        .put("keepGraph", keepGraph)

    companion object {
        fun from(o: JSONObject?) = RecConfig().apply {
            if (o == null) return@apply
            mode = o.optString("mode", mode)
            seeds = o.optString("seeds", seeds)
            seedCount = o.optInt("seedCount", seedCount)
            customSeeds = o.optString("customSeeds", "")
            depth = o.optInt("depth", depth)
            fanout = o.optInt("fanout", fanout)
            budget = o.optInt("budget", budget)
            maxNodes = o.optInt("maxNodes", maxNodes)
            relevance = o.optString("relevance", relevance)
            keywords = o.optString("keywords", "")
            expandOffTopic = o.optBoolean("expandOffTopic", false)
            excludeShorts = o.optBoolean("excludeShorts", true)
            minViews = o.optLong("minViews", 0)
            maxPerChannel = o.optInt("maxPerChannel", 0)
            useProjectFilters = o.optBoolean("useProjectFilters", false)
            blocked = o.optString("blocked", "")
            keepGraph = o.optBoolean("keepGraph", true)
        }
    }
}

data class Project(
    val id: String = newId(),
    var name: String = "",
    var phrase: String = "",
    var domain: String = "",
    var queries: MutableList<String> = mutableListOf(),
    var filters: SearchFilters = SearchFilters(),
    var collect: CollectConfig = CollectConfig(),
    var monitor: MonitorConfig = MonitorConfig(),
    var recs: RecConfig = RecConfig(),
    var seo: SeoContext = SeoContext(),
    var source: String = "", // shared link for instant analysis
    var sourceType: String = "", // video | channel | playlist
    var quickMode: String = "", // "" = instant analysis | "style" = video style only
    var createdAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long = 0,
    var archived: Boolean = false,
    var newCount: Int = 0,
    var videoCount: Int = 0,
    var runs: MutableList<RunRecord> = mutableListOf()
) {
    fun effectiveQueries(): List<String> = queries.filter { it.isNotBlank() }.ifEmpty { listOf(phrase) }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("phrase", phrase)
        .put("domain", domain).put("queries", queries.toJsonArray()).put("filters", filters.toJson())
        .put("collect", collect.toJson()).put("monitor", monitor.toJson()).put("recs", recs.toJson()).put("seo", seo.toJson()).put("source", source).put("sourceType", sourceType).put("quickMode", quickMode).put("createdAt", createdAt)
        .put("lastRunAt", lastRunAt).put("archived", archived).put("newCount", newCount)
        .put("videoCount", videoCount)
        .put("runs", JSONArray().also { a -> runs.takeLast(100).forEach { a.put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject) = Project(
            id = o.getString("id"),
            name = o.optString("name"),
            phrase = o.optString("phrase"),
            domain = o.optString("domain"),
            queries = o.optJSONArray("queries").strings(),
            filters = SearchFilters.from(o.optJSONObject("filters")),
            collect = CollectConfig.from(o.optJSONObject("collect")),
            monitor = MonitorConfig.from(o.optJSONObject("monitor")),
            recs = RecConfig.from(o.optJSONObject("recs")),
            seo = SeoContext.from(o.optJSONObject("seo")),
            source = o.optString("source"),
            sourceType = o.optString("sourceType"),
            quickMode = o.optString("quickMode"),
            createdAt = o.optLong("createdAt"),
            lastRunAt = o.optLong("lastRunAt"),
            archived = o.optBoolean("archived"),
            newCount = o.optInt("newCount"),
            videoCount = o.optInt("videoCount"),
            runs = mutableListOf<RunRecord>().also { l ->
                val a = o.optJSONArray("runs") ?: return@also
                for (i in 0 until a.length()) l.add(RunRecord.from(a.getJSONObject(i)))
            }
        )
    }
}

data class RunRecord(val at: Long, val kind: String, val found: Int, val added: Int, val note: String = "") {
    fun toJson() = JSONObject().put("at", at).put("kind", kind).put("found", found).put("added", added).put("note", note)

    companion object {
        fun from(o: JSONObject) = RunRecord(o.optLong("at"), o.optString("kind"), o.optInt("found"), o.optInt("added"), o.optString("note"))
    }
}

data class Video(
    val id: String,
    var url: String,
    var title: String = "",
    var channel: String = "",
    var channelUrl: String = "",
    var uploadMs: Long = -1,
    var uploadText: String = "",
    var views: Long = -1,
    var duration: Long = -1,
    var thumb: String = "",
    var isShort: Boolean = false,
    var query: String = "",
    var addedAt: Long = System.currentTimeMillis(),
    // user state
    var marked: Boolean = false,
    var labels: MutableList<String> = mutableListOf(),
    var collected: Boolean = false,
    var collectError: String = "",
    // collected metadata
    var description: String = "",
    var tags: MutableList<String> = mutableListOf(),
    var likes: Long = -1,
    var category: String = "",
    var subscribers: Long = -1,
    var hasTranscript: Boolean = false,
    var transcriptSource: String = "", // manual | auto | ai
    var commentsCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title)
        .put("channel", channel).put("channelUrl", channelUrl).put("uploadMs", uploadMs)
        .put("uploadText", uploadText).put("views", views).put("duration", duration).put("thumb", thumb)
        .put("isShort", isShort).put("query", query).put("addedAt", addedAt).put("marked", marked)
        .put("labels", labels.toJsonArray()).put("collected", collected).put("collectError", collectError)
        .put("description", description).put("tags", tags.toJsonArray()).put("likes", likes)
        .put("category", category).put("subscribers", subscribers).put("hasTranscript", hasTranscript)
        .put("transcriptSource", transcriptSource).put("commentsCount", commentsCount)

    companion object {
        fun from(o: JSONObject) = Video(
            id = o.getString("id"),
            url = o.optString("url"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            channelUrl = o.optString("channelUrl"),
            uploadMs = o.optLong("uploadMs", -1),
            uploadText = o.optString("uploadText"),
            views = o.optLong("views", -1),
            duration = o.optLong("duration", -1),
            thumb = o.optString("thumb"),
            isShort = o.optBoolean("isShort"),
            query = o.optString("query"),
            addedAt = o.optLong("addedAt"),
            marked = o.optBoolean("marked"),
            labels = o.optJSONArray("labels").strings(),
            collected = o.optBoolean("collected"),
            collectError = o.optString("collectError"),
            description = o.optString("description"),
            tags = o.optJSONArray("tags").strings(),
            likes = o.optLong("likes", -1),
            category = o.optString("category"),
            subscribers = o.optLong("subscribers", -1),
            hasTranscript = o.optBoolean("hasTranscript"),
            transcriptSource = o.optString("transcriptSource"),
            commentsCount = o.optInt("commentsCount")
        )
    }
}

data class Comment(
    val id: String = "",
    val author: String,
    val text: String,
    val likes: Int,
    val dateMs: Long,
    val dateText: String,
    val replyCount: Int,
    val isReply: Boolean
) {
    fun toJson() = JSONObject().put("id", id).put("author", author).put("text", text).put("likes", likes)
        .put("dateMs", dateMs).put("dateText", dateText).put("replyCount", replyCount).put("isReply", isReply)

    companion object {
        fun from(o: JSONObject) = Comment(
            o.optString("id"), o.optString("author"), o.optString("text"), o.optInt("likes"), o.optLong("dateMs", -1),
            o.optString("dateText"), o.optInt("replyCount"), o.optBoolean("isReply")
        )
    }
}

data class AiResult(
    val key: String,
    val kind: String, // video_summary | project_summary | clusters | sentiment | domain | chat
    val target: String, // video id or "project"
    val model: String,
    val text: String,
    val inTokens: Int,
    val outTokens: Int,
    val at: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().put("key", key).put("kind", kind).put("target", target).put("model", model)
        .put("text", text).put("inTokens", inTokens).put("outTokens", outTokens).put("at", at)

    companion object {
        fun from(o: JSONObject) = AiResult(
            o.optString("key"), o.optString("kind"), o.optString("target"), o.optString("model"),
            o.optString("text"), o.optInt("inTokens"), o.optInt("outTokens"), o.optLong("at")
        )
    }
}

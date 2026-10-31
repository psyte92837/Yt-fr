/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject

/**
 * Self-update: CI publishes every build of main to the rolling GitHub release "lumen-latest"
 * (APK + update.json). The app compares the build number and offers to install.
 * A newer NewPipeExtractor (YouTube fixes) arrives the same way: the extractor-watch workflow bumps it,
 * checks it against live YouTube and publishes a new build.
 */
class UpdateInfo(
    val build: Int,
    val commit: String,
    val extractor: String,
    val appId: String,
    val apk: String,
    val sha256: String,
    val size: Long,
    val notes: String,
    val date: String
) {
    fun toJson(): String = JSONObject().put("build", build).put("commit", commit).put("extractor", extractor)
        .put("application_id", appId).put("apk", apk).put("sha256", sha256).put("size", size)
        .put("notes", notes).put("date", date).toString()
}

object UpdateLogic {
    const val REPO = "psyte92837/yt-fr"
    const val TAG = "lumen-latest"
    const val FEED = "https://github.com/$REPO/releases/download/$TAG/update.json"
    const val PAGE = "https://github.com/$REPO/releases/tag/$TAG"

    /** Parses update.json; null when it is broken or has no APK. */
    fun parse(json: String): UpdateInfo? = try {
        val o = JSONObject(json)
        val apk = o.optString("apk").trim()
        val build = o.optInt("build", 0)
        if (apk.startsWith("https://") && build > 0) UpdateInfo(
            build = build,
            commit = o.optString("commit"),
            extractor = o.optString("extractor"),
            appId = o.optString("application_id"),
            apk = apk,
            sha256 = o.optString("sha256").lowercase(),
            size = o.optLong("size", 0),
            notes = o.optString("notes"),
            date = o.optString("date")
        ) else null
    } catch (e: Exception) {
        null
    }

    /** An update is offered only for the same app id (debug and release are different apps) and a higher build. */
    fun isNewer(currentBuild: Int, currentAppId: String, info: UpdateInfo?): Boolean =
        info != null && (info.appId.isEmpty() || info.appId == currentAppId) && info.build > currentBuild

    fun shortSha(s: String): String = if (Regex("[0-9a-fA-F]{12,40}").matches(s)) s.take(10) else s

    /** "a1b2c3" or "v0.25.2" → readable extractor version. */
    fun extractorLabel(s: String): String = if (s.isBlank()) "?" else shortSha(s)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun mb(bytes: Long): String = if (bytes <= 0) "" else "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
}

/**
 * Notices when YouTube changes break NewPipeExtractor (parsing errors, not network or bot checks)
 * so the app can look for a fixed build right away instead of waiting for the next scheduled check.
 */
object ExtractorHealth {
    enum class Kind { BOT, NETWORK, BROKEN, CONTENT, OTHER }

    private val BROKEN_CLASSES = setOf("ParsingException", "RegexException", "ExtractionException", "DeobfuscateException",
        "JsonParserException", "ScriptException")
    private val CONTENT_CLASSES = setOf("ContentNotAvailableException", "AgeRestrictedContentException", "PrivateContentException",
        "GeographicRestrictionException", "PaidContentException", "YoutubeMusicPremiumContentException",
        "ContentNotSupportedException", "AccountTerminatedException", "SoundCloudGoPlusContentException")
    private val NETWORK_CLASSES = setOf("UnknownHostException", "SocketTimeoutException", "ConnectException",
        "SSLException", "SSLHandshakeException", "NoRouteToHostException", "InterruptedIOException")
    private val BROKEN_TEXT = listOf("could not parse", "could not get", "could not find", "deobfusc", "decrypt",
        "throttling", "signature", "initial data", "ytInitial", "player response", "n param")

    fun classify(e: Throwable?): Kind {
        if (e == null) return Kind.OTHER
        if (YtBlock.isBotCheck(e)) return Kind.BOT
        var c: Throwable? = e
        var depth = 0
        var broken = false
        while (c != null && depth++ < 8) {
            val cur: Throwable = c
            val n = cur.javaClass.simpleName
            val msg = cur.message ?: ""
            if (n in CONTENT_CLASSES) return Kind.CONTENT
            if (n in NETWORK_CLASSES) return Kind.NETWORK
            if (n in BROKEN_CLASSES) broken = true
            if (cur.javaClass.name.startsWith("org.schabi.newpipe.extractor") && BROKEN_TEXT.any { msg.contains(it, true) }) broken = true
            c = cur.cause
        }
        return if (broken) Kind.BROKEN else Kind.OTHER
    }

    /** Several parsing failures within a short window mean "YouTube changed something". */
    fun shouldAlarm(times: List<Long>, now: Long, threshold: Int = 3, windowMs: Long = 6 * 3_600_000L): Boolean =
        times.count { now - it in 0..windowMs } >= threshold

    private val recent = ArrayList<Long>()
    @Volatile var lastAlarm = 0L
        private set

    /** Called from the log for every logged error; cheap and never throws. */
    fun note(e: Throwable?) {
        try {
            if (classify(e) != Kind.BROKEN) return
            val now = System.currentTimeMillis()
            val alarm = synchronized(recent) {
                recent.add(now)
                recent.removeAll { now - it > 24 * 3_600_000L }
                shouldAlarm(recent, now) && now - lastAlarm > 6 * 3_600_000L
            }
            if (alarm) {
                lastAlarm = now
                if (LumenEnv.ready) Updates.onExtractorBroken(Lumen.app)
            }
        } catch (t: Throwable) {
            // diagnostics must never break the caller
        }
    }

    fun hint(): String = t(
        "Похоже, YouTube что-то поменял: несколько запросов подряд не удалось разобрать. Lumen проверяет, есть ли исправленная сборка (Настройки → О приложении)",
        "YouTube seems to have changed something: several requests in a row could not be parsed. Lumen is checking for a fixed build (Settings → About)"
    )
}

/** One live check of what Lumen needs from YouTube. */
class ProbeResult(val id: String, val ok: Boolean, val kind: ExtractorHealth.Kind?, val detail: String, val ms: Long)

/**
 * Live YouTube self-test through NewPipeExtractor: search, video page, streams, comments, channel.
 * Runs on the device (Settings → About) and in CI (LiveYouTubeTest) with the same code.
 */
object YtProbe {
    const val VIDEO = "https://www.youtube.com/watch?v=jNQXAC9IVRw" // "Me at the zoo", the first YouTube video
    const val CHANNEL = "https://www.youtube.com/@jawed"
    val IDS = listOf("search", "video", "streams", "comments", "channel")

    private fun check(id: String, block: () -> String): ProbeResult {
        val t0 = System.currentTimeMillis()
        return try {
            ProbeResult(id, true, null, block(), System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            ProbeResult(id, false, ExtractorHealth.classify(e), (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300),
                System.currentTimeMillis() - t0)
        }
    }

    fun run(only: Set<String> = IDS.toSet()): List<ProbeResult> {
        val yt = org.schabi.newpipe.extractor.ServiceList.YouTube
        val out = ArrayList<ProbeResult>()
        if ("search" in only) out += check("search") {
            val ex = yt.getSearchExtractor(yt.searchQHFactory.fromQuery("lofi hip hop", listOf("videos"), ""))
            ex.fetchPage()
            val n = ex.initialPage.items.count { it is org.schabi.newpipe.extractor.stream.StreamInfoItem }
            require(n >= 3) { "search returned $n videos" }
            "$n videos"
        }
        var info: org.schabi.newpipe.extractor.stream.StreamInfo? = null
        if ("video" in only || "streams" in only) out += check("video") {
            val i = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(yt, VIDEO)
            info = i
            require(i.name.isNotBlank()) { "empty title" }
            require(i.duration > 0) { "no duration" }
            "\"${i.name}\" · ${i.uploaderName} · ${i.viewCount} views"
        }
        if ("streams" in only) out += check("streams") {
            val i = info ?: error("video page failed")
            val n = i.videoStreams.size + i.videoOnlyStreams.size + i.audioStreams.size
            require(n > 0) { "no playable streams" }
            val url = (i.audioStreams.firstOrNull() ?: i.videoStreams.firstOrNull())?.content ?: ""
            require(url.startsWith("http")) { "stream URL not deciphered" }
            "$n streams"
        }
        if ("comments" in only) out += check("comments") {
            val c = org.schabi.newpipe.extractor.comments.CommentsInfo.getInfo(yt, VIDEO) ?: error("no comments info")
            require(c.relatedItems.isNotEmpty()) { "no comments" }
            "${c.relatedItems.size} comments"
        }
        if ("channel" in only) out += check("channel") {
            val ch = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(yt, CHANNEL)
            require(ch.name.isNotBlank()) { "empty channel name" }
            ch.name + " · " + ch.subscriberCount + " subs"
        }
        return out
    }

    /** "ok" — all passed; "blocked" — only bot checks / network (not the extractor's fault); "broken" — otherwise. */
    fun verdict(results: List<ProbeResult>): String {
        val failed = results.filter { !it.ok }
        return when {
            failed.isEmpty() -> "ok"
            failed.all { it.kind == ExtractorHealth.Kind.BOT || it.kind == ExtractorHealth.Kind.NETWORK } -> "blocked"
            else -> "broken"
        }
    }

    fun report(results: List<ProbeResult>): String = results.joinToString("\n") {
        (if (it.ok) "✓ " else "✗ ") + it.id + " (" + it.ms + " ms): " + it.detail + (if (!it.ok && it.kind != null) " [" + it.kind + "]" else "")
    }
}

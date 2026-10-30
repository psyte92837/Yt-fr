/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import java.net.URLEncoder

/** YouTube's "Sign in to confirm you're not a bot" block (LOGIN_REQUIRED) for anonymous watch requests. */
object YtBlock {
    private val MARKERS = listOf("LOGIN_REQUIRED", "sign in to confirm", "temporarily blocked anonymous", "not a bot")

    fun isBotCheck(e: Throwable?): Boolean {
        var c = e
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c.javaClass.simpleName == "SignInConfirmNotBotException") return true
            val m = c.message ?: ""
            if (MARKERS.any { m.contains(it, ignoreCase = true) }) return true
            c = c.cause
        }
        return false
    }

    fun hint(): String = t(
        "YouTube закрыл анонимный доступ с этого IP (часто из-за VPN или прокси). Отключите VPN или смените сервер и перезапустите анализ",
        "YouTube blocked anonymous access from this IP (often due to a VPN or proxy). Turn off the VPN or switch server and restart the analysis"
    )

    /** Short, human message for a failed step. */
    fun message(e: Throwable): String = if (isBotCheck(e)) hint() else (e.message ?: e.javaClass.simpleName)
}

/** Basic video data from the public oEmbed endpoint: works when the watch/player request is blocked. */
object OEmbed {
    fun url(videoId: String): String =
        "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")

    fun parse(videoId: String, json: String): Video {
        val o = JSONObject(json)
        val title = o.optString("title").trim()
        require(title.isNotEmpty()) { "oEmbed: no title" }
        return Video(
            id = videoId,
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            channel = o.optString("author_name").trim(),
            channelUrl = o.optString("author_url").trim(),
            thumb = o.optString("thumbnail_url").trim(),
            query = "link"
        )
    }

    fun fetch(videoId: String): Video = parse(videoId, NewPipe.getDownloader().get(url(videoId)).responseBody())
}

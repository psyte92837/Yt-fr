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

/** Telegram Bot API over HTTPS (long polling: the phone needs no public address). */
class TgApi(private val token: String) {
    @Volatile private var current: HttpURLConnection? = null

    /** Breaks a waiting long poll (stopping the bot). */
    fun abort() {
        try {
            current?.disconnect()
        } catch (e: Exception) {
            // already closed
        }
    }

    private fun call(method: String, params: JSONObject, readTimeoutMs: Int = 30_000): JSONObject {
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        current = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(params.toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val o = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw TgError(code, "HTTP $code")
            }
            if (!o.optBoolean("ok")) throw Tg.error(body)
            return o
        } finally {
            if (current === c) current = null
            c.disconnect()
        }
    }

    /** Bot's own account: (id, username, first_name). */
    fun getMe(): Triple<Long, String, String> {
        val r = call("getMe", JSONObject()).getJSONObject("result")
        return Triple(r.optLong("id"), r.optString("username"), r.optString("first_name"))
    }

    fun deleteWebhook() {
        call("deleteWebhook", JSONObject().put("drop_pending_updates", false))
    }

    /** Waits up to [timeoutSec] for new updates. */
    fun getUpdates(offset: Long, timeoutSec: Int, botUsername: String): Tg.Batch {
        val p = JSONObject().put("timeout", timeoutSec).put("allowed_updates", JSONArray(listOf("message", "edited_message", "callback_query")))
        if (offset > 0) p.put("offset", offset)
        val o = call("getUpdates", p, (timeoutSec + 15) * 1000)
        return Tg.parseUpdates(o.toString(), botUsername)
    }

    /** Sends [text] (split into parts when long); a broken HTML/Markdown is re-sent as plain text. Returns the last message id. */
    fun send(chatId: Long, text: String, opts: JSONObject, appUrl: String = ""): Long {
        val parts = Tg.split(text.ifEmpty { "…" })
        var last = 0L
        parts.forEachIndexed { i, part ->
            // buttons go with the last part only
            val o = if (i == parts.lastIndex) opts else JSONObject(opts.toString()).apply {
                remove("buttons")
                remove("keyboard")
                remove("reply_markup")
            }
            last = try {
                call("sendMessage", Tg.sendParams(chatId, part, o, appUrl)).getJSONObject("result").optLong("message_id")
            } catch (e: TgError) {
                if (e.code != 400 || !e.description.contains("parse", true)) throw e
                val plain = Tg.sendParams(chatId, part, o, appUrl).apply { remove("parse_mode") }
                call("sendMessage", plain).getJSONObject("result").optLong("message_id")
            }
        }
        return last
    }

    fun edit(chatId: Long, messageId: Long, text: String, opts: JSONObject, appUrl: String = "") {
        val p = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", Tg.split(text.ifEmpty { "…" }).first())
        Tg.applyOpts(p, opts, appUrl)
        try {
            call("editMessageText", p)
        } catch (e: TgError) {
            // pressing the same button twice: Telegram refuses an identical edit
            if (!e.description.contains("not modified", true)) throw e
        }
    }

    fun answer(callbackId: String, text: String) {
        val p = JSONObject().put("callback_query_id", callbackId)
        if (text.isNotBlank()) p.put("text", text.take(200))
        try {
            call("answerCallbackQuery", p)
        } catch (e: TgError) {
            // too old to answer: harmless
        }
    }

    fun photo(chatId: Long, url: String, caption: String, opts: JSONObject, appUrl: String = ""): Long {
        val p = JSONObject().put("chat_id", chatId).put("photo", url)
        if (caption.isNotBlank()) p.put("caption", caption.take(1024))
        Tg.applyOpts(p, opts, appUrl)
        return call("sendPhoto", p).getJSONObject("result").optLong("message_id")
    }

    fun setCommands(cmds: List<Pair<String, String>>) {
        call("setMyCommands", JSONObject().put("commands", Tg.commandsJson(cmds)))
    }

    /** The menu button next to the input: opens the mini app, or the command list when [url] is empty. */
    fun setMenuButton(url: String, title: String) {
        val mb = if (url.isBlank()) JSONObject().put("type", "commands")
        else JSONObject().put("type", "web_app").put("text", title.ifBlank { "App" }.take(64)).put("web_app", JSONObject().put("url", url))
        call("setChatMenuButton", JSONObject().put("menu_button", mb))
    }

    companion object {
        /** True for errors worth retrying later (network, Telegram busy). */
        fun transient(e: Throwable): Boolean = e is IOException && (e !is TgError || e.code == 429 || e.code >= 500 || e.code == 0)
    }
}

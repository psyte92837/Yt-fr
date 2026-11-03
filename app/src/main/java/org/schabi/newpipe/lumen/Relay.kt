/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * "Another AI by hand" (protocol lumen.relay v1): any AI call of Lumen can go through Gemini, ChatGPT, Claude or any
 * chat the user opens. The request is copied (or shared) as one self-contained text; the answer comes back from the
 * clipboard, by pasting, or by "Share → Lumen: AI answer" right in the AI app, and goes through the same parsers,
 * checks and repairs as an API answer — so the Studio director, info-schemes, templates and every other task work
 * without an API key. Pure text helpers here; the waiting is in [Relay], the screen is [RelayActivity].
 */
object RelayText {
    const val PROTOCOL = "lumen.relay v1"
    const val HEAD = "⟦Lumen → AI"

    /** The text the user pastes into another AI: what to answer, the role, the task, the video/picture, the wish. */
    fun request(task: String, system: String, user: String, json: Boolean, fileUri: String?, image: Boolean, wish: String): String = buildString {
        append(HEAD).append(" · ").append(task).append(" · ").append(PROTOCOL).append("⟧\n")
        append(
            if (json) t("Ответь на задание ниже. Верни ТОЛЬКО JSON одним блоком ```json … ``` — без пояснений до и после: ответ вставят в приложение Lumen и проверят автоматически.",
                "Answer the task below. Return ONLY JSON in one ```json … ``` block — no text before or after: the answer is pasted into the Lumen app and checked automatically.")
            else t("Ответь на задание ниже. Верни только сам результат, без вступления и вопросов: ответ вставят в приложение Lumen.",
                "Answer the task below. Return only the result itself, no intro and no questions: the answer is pasted into the Lumen app.")
        ).append("\n\n")
        if (system.isNotBlank()) append(t("=== РОЛЬ ===\n", "=== ROLE ===\n")).append(system.trim()).append("\n\n")
        append(t("=== ЗАДАНИЕ ===\n", "=== TASK ===\n")).append(user.trim()).append("\n\n")
        val links = MediaOpts.links(fileUri)
        if (links.size == 1) append(t("=== ВИДЕО ===\nПосмотри видео: ", "=== VIDEO ===\nWatch the video: ")).append(links[0]).append("\n\n")
        else if (links.size > 1) {
            append(t("=== ВИДЕО ===\nПосмотри видео, номера — по порядку:\n", "=== VIDEOS ===\nWatch the videos, numbers in this order:\n"))
            links.forEachIndexed { i, u -> append(i + 1).append(" — ").append(u).append('\n') }
            append('\n')
        }
        if (image) append(t("=== КАРТИНКА ===\nКартинка приложена к сообщению — посмотри её.\n\n", "=== PICTURE ===\nThe picture is attached to the message — look at it.\n\n"))
        append(t("=== ПОЖЕЛАНИЕ АВТОРА ===\n", "=== AUTHOR'S WISH ===\n"))
        append(wish.trim().ifEmpty { t("(нет — действуй по заданию)", "(none — follow the task)") })
    }

    /** Our own request (it is in the clipboard right after copying): never taken for an answer. */
    fun isOwnRequest(s: String): Boolean = s.trimStart().startsWith(HEAD) || s.contains("$PROTOCOL⟧")

    /** Clipboard text that may be the answer: not empty, not our request, not the text we already took. */
    fun looksLikeAnswer(s: String?, taken: String?): Boolean {
        val v = s?.trim().orEmpty()
        return v.length >= 2 && !isOwnRequest(v) && v != taken?.trim()
    }

    private val INVISIBLE = Regex("[​-‍⁠﻿]")

    /** What chat apps add when copying: invisible characters, non-breaking spaces, CRLF; JSON answers are also tidied. */
    fun clean(s: String, json: Boolean): String {
        val v = s.replace(INVISIBLE, "").replace(' ', ' ').replace("\r\n", "\n").trim()
        return if (json) tidyJson(v) else v
    }

    /**
     * JSON as chat AIs sometimes write it: “smart quotes” instead of quotes (only when there are no plain ones),
     * line and block comments, commas before } or ]. Strings are left untouched.
     */
    fun tidyJson(s0: String): String {
        val s = if (!s0.contains('"') && (s0.contains('“') || s0.contains('”'))) s0.replace('“', '"').replace('”', '"') else s0
        val out = StringBuilder(s.length)
        var i = 0
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    out.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> {
                    inStr = true
                    out.append(c)
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '/' && insideJson(out) -> {
                    while (i < s.length && s[i] != '\n') i++
                    continue
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' && insideJson(out) -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    continue
                }
                c == ',' -> {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        i++
                        continue
                    }
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Comments are only removed inside a JSON object or array, never in the prose around it (links!). */
    private fun insideJson(out: CharSequence): Boolean {
        var depth = 0
        var inStr = false
        var esc = false
        for (c in out) {
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> depth = maxOf(0, depth - 1)
            }
        }
        return depth > 0
    }

    /** A JSON answer without a JSON object in it: worth a warning before it is accepted. */
    fun missingJson(s: String): Boolean = JsonText.firstObject(s) == null && !s.trimStart().startsWith("[")

    /** Which AI calls go to another AI: none, the Studio ones, all. */
    val MODES = listOf("off", "studio", "all")

    fun routes(mode: String, task: String): Boolean = task != "ping" && when (mode) {
        "all" -> true
        "studio" -> task.startsWith("studio") || task.startsWith("langgame")
        else -> false
    }
}

/** The waiting side: the worker thread of an AI call waits here until [RelayActivity] gives the answer. */
object Relay {
    class Request(
        val task: String, val pid: String, val system: String, val user: String, val json: Boolean,
        val fileUri: String?, val image: String?, val imageMime: String
    )

    class Pending(val req: Request) {
        val latch = CountDownLatch(1)
        @Volatile var result: String? = null
        @Volatile var wish: String = ""
        fun answer(r: String?) {
            result = r
            latch.countDown()
        }
    }

    private const val NOTE_ID = 7302
    private val lock = ReentrantLock(true)

    @Volatile var current: Pending? = null
        private set

    /** The last answer taken, so the same clipboard text is not offered twice. */
    @Volatile var lastTaken: String? = null

    var mode: String
        get() = Lumen.prefs.getString("relay_mode", "off") ?: "off"
        set(v) = Lumen.prefs.edit().putString("relay_mode", v).apply()

    fun routes(task: String): Boolean = RelayText.routes(mode, task)

    /** Waits (up to an hour) for the answer of another AI; throws [SendCancelled] when the user cancels. */
    fun ask(req: Request): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Relay.ask on the main thread" }
        lock.lock()
        try {
            val p = Pending(req)
            current = p
            show()
            val answered = p.latch.await(60, TimeUnit.MINUTES)
            current = null
            hideNote()
            val r = p.result
            if (!answered) throw SendCancelled(t("Ответа другого ИИ нет час — запрос «${req.task}» отменён.",
                "No answer from the other AI for an hour — request \"${req.task}\" cancelled."))
            if (r == null) throw SendCancelled(t("Запрос к другому ИИ отменён", "Request to the other AI cancelled"))
            lastTaken = r
            return RelayText.clean(r, req.json)
        } finally {
            current = null
            lock.unlock()
        }
    }

    /** An answer shared from an AI app; false when nothing waits for it. */
    fun deliver(text: String): Boolean {
        val p = current ?: return false
        p.answer(text)
        return true
    }

    private fun show() {
        if (LumenBase.visible > 0) {
            try {
                Nav.show(Lumen.app, Intent(Lumen.app, RelayActivity::class.java))
            } catch (e: Exception) {
                L.w("relay", "screen: ${e.message}")
            }
        }
        try {
            Notify.channels(Lumen.app)
            val req = current?.req ?: return
            val pi = Nav.pending(Lumen.app, NOTE_ID, Intent(Lumen.app, RelayActivity::class.java))
            val n = Notify.builder(Lumen.app, Notify.CH_DONE_QUIET)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(t("Ждём ответ другого ИИ", "Waiting for the other AI"))
                .setContentText(req.task + t(" · скопируйте запрос в Gemini/ChatGPT и верните ответ", " · copy the request to Gemini/ChatGPT and bring the answer back"))
                .setContentIntent(pi).setOngoing(true).setAutoCancel(true).build()
            Lumen.app.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
        } catch (e: Exception) {
            L.w("relay", "notification: ${e.message}")
        }
    }

    private fun hideNote() = try {
        Lumen.app.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    } catch (e: Exception) {
        // nothing to hide
    }
}

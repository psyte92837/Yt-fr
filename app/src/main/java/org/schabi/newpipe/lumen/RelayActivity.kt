/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import java.io.File

/**
 * "Another AI": the request of a waiting AI call ([Relay]) as one text to copy or share into Gemini, ChatGPT, Claude…,
 * plus the ways back — the clipboard (picked up when the screen gets focus), pasting, or "Share → Lumen: AI answer"
 * from the AI app (this screen is that share target too).
 */
class RelayActivity : LumenBase() {
    private var pending: Relay.Pending? = null
    private var wishBox: EditText? = null
    private var answerBox: EditText? = null
    private var copied: String? = null
    private var found: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shared(intent)) return
        attach()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shared(intent)) return
        if (pending !== Relay.current) attach()
    }

    /** An answer shared from an AI app: given to the waiting call, then back to Lumen. */
    private fun shared(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) {
            toast(t("Пустой текст", "Empty text"))
            finish()
            return true
        }
        if (Relay.deliver(text)) {
            Relay.lastTaken = text
            toast(t("Ответ передан в Lumen", "Answer handed to Lumen"))
            backToLumen()
        } else {
            copy(text)
            toast(t("Lumen сейчас не ждёт ответа ИИ — текст скопирован в буфер", "Lumen is not waiting for an AI answer — the text is copied"))
        }
        finish()
        return true
    }

    /** The screen that waits for the answer lives in Lumen's own task: bring it to the front. */
    @Suppress("DEPRECATION")
    private fun backToLumen() {
        try {
            getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo?.persistentId != taskId }?.moveToFront()
        } catch (e: Exception) {
            L.w("relay", "front: ${e.message}")
        }
    }

    private fun attach() {
        val p = Relay.current
        if (p == null) {
            finish()
            return
        }
        pending = p
        found = null
        render()
    }

    private fun request(p: Relay.Pending): String {
        val r = p.req
        return RelayText.request(r.task, r.system, r.user, r.json, r.fileUri, r.image != null, wishBox?.text?.toString() ?: p.wish)
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lumen", s))
    }

    private fun clip(): String? = try {
        getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }

    private fun copyRequest(): String? {
        val p = pending ?: return null
        p.wish = wishBox?.text?.toString().orEmpty()
        val s = request(p)
        copy(s)
        copied = s
        return s
    }

    /** Opens a chat site with the request already in the clipboard. */
    private fun openChat(url: String) {
        copyRequest() ?: return
        toast(t("Запрос скопирован — вставьте его в чат", "Request copied — paste it into the chat"))
        openUrl(url)
    }

    /** The share sheet with the request text (and the picture, if the call has one). */
    private fun shareRequest() {
        val p = pending ?: return
        val s = copyRequest() ?: return
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, s).putExtra(Intent.EXTRA_SUBJECT, "Lumen → AI: " + p.req.task)
        val pic = p.req.image?.let { b64 ->
            runCatching {
                val f = File(cacheDir, "relay." + if (p.req.imageMime.contains("png")) "png" else "jpg")
                f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            }.getOrNull()
        }
        if (pic != null) {
            i.setType(p.req.imageMime).putExtra(Intent.EXTRA_STREAM, pic).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("lumen", pic)
        } else {
            i.setType("text/plain")
        }
        startActivity(Intent.createChooser(i, t("Отправить запрос в ИИ", "Send the request to an AI")))
    }

    private fun accept(text0: String) {
        val p = pending ?: return finish()
        val text = text0.trim()
        if (text.isEmpty()) return toast(t("Ответ пуст", "The answer is empty"))
        if (RelayText.isOwnRequest(text)) return toast(t("Это сам запрос, а не ответ ИИ", "This is the request itself, not the AI answer"))
        if (p.req.json && RelayText.missingJson(RelayText.clean(text, true))) {
            AlertDialog.Builder(this).setTitle(t("В ответе нет JSON", "No JSON in the answer"))
                .setMessage(t("Lumen ждёт JSON. Попросите ИИ: «Верни только JSON». Всё равно передать?", "Lumen expects JSON. Ask the AI: \"Return JSON only\". Hand it over anyway?"))
                .setPositiveButton(t("Передать", "Hand over")) { _, _ -> give(p, text) }
                .setNegativeButton(t("Отмена", "Cancel"), null).show()
            return
        }
        give(p, text)
    }

    private fun give(p: Relay.Pending, text: String) {
        p.answer(text)
        pending = null
        toast(t("Ответ передан", "Answer handed over"))
        // the next request may already wait (e.g. a repair round)
        window.decorView.postDelayed({
            if (Relay.current != null && !isFinishing) attach() else finish()
        }, 300)
    }

    private fun cancel() {
        pending?.answer(null)
        pending = null
        finish()
    }

    override fun navigateUp() = cancel()

    override fun onBackPressed() = cancel()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets an app read the clipboard only while it has focus
        if (!hasFocus || pending == null) return
        val c = clip()
        if (c != found && c != copied && RelayText.looksLikeAnswer(c, Relay.lastTaken)) {
            found = c
            render()
        }
    }

    private fun render() {
        val p = pending ?: return
        val req = p.req
        val c = Col(this)
        c.card {
            add(text(ctx, req.task, Type.TITLE_M, bold = true), 0)
            muted(t("1. Скопируйте запрос или отправьте его в приложение ИИ.\n" +
                "2. Вставьте в Gemini, ChatGPT, Claude или любой чат; свою просьбу можно дописать там или в поле ниже.\n" +
                "3. Скопируйте ответ и вернитесь: Lumen сам найдёт его в буфере. Или в приложении ИИ: «Поделиться → Lumen: ответ ИИ».",
                "1. Copy the request or send it to an AI app.\n" +
                    "2. Paste it into Gemini, ChatGPT, Claude or any chat; add your own wish there or in the field below.\n" +
                    "3. Copy the answer and come back: Lumen finds it in the clipboard. Or in the AI app: \"Share → Lumen: AI answer\"."))
            val tok = Llm.estimateTokens(req.system + req.user)
            muted(t("Запрос: ", "Request: ") + Fmt.num((req.system.length + req.user.length).toLong()) + t(" симв. · ≈", " chars · ≈") + Fmt.num(tok.toLong()) + t(" ток.", " tok.") +
                (if (req.json) " · JSON" else "") + (if (req.fileUri != null) t(" · видео по ссылке", " · video link") else "") +
                (if (req.image != null) t(" · картинка", " · picture") else ""))
        }

        found?.let { f ->
            c.card {
                add(text(ctx, t("📋 В буфере ответ ИИ", "📋 AI answer in the clipboard"), Type.TITLE_M, bold = true), 0)
                muted(f.take(400) + if (f.length > 400) "…" else "")
                row(
                    btn(ctx, t("✓ Передать в Lumen", "✓ Hand to Lumen"), primary = true) { accept(f) },
                    btn(ctx, t("Не то", "Not this"), style = BtnStyle.TEXT) {
                        found = null
                        Relay.lastTaken = f
                        render()
                    }
                )
            }
        }

        c.h2(t("Запрос", "Request"))
        wishBox = c.edit(t("Ваше пожелание к ИИ (добавится в конец запроса)", "Your wish to the AI (added at the end)"), p.wish, multiline = true)
        c.row(
            btn(this, t("⧉ Скопировать", "⧉ Copy"), primary = true) {
                copyRequest()
                toast(t("Запрос скопирован", "Request copied"))
            },
            btn(this, t("↗ Отправить в…", "↗ Send to…"), style = BtnStyle.TONAL) { shareRequest() }
        )
        c.row(
            btn(this, "Gemini", style = BtnStyle.OUTLINED) { openChat("https://gemini.google.com/app") },
            btn(this, "ChatGPT", style = BtnStyle.OUTLINED) { openChat("https://chatgpt.com/") },
            btn(this, "Claude", style = BtnStyle.OUTLINED) { openChat("https://claude.ai/new") }
        )
        c.button(t("Показать текст запроса", "Show the request text"), style = BtnStyle.TEXT) { showText(req.task, request(p)) }

        c.h2(t("Ответ ИИ", "AI answer"))
        answerBox = c.edit(t("Вставьте ответ сюда", "Paste the answer here"), "", multiline = true)
        c.row(
            btn(this, t("📥 Из буфера", "📥 From clipboard"), style = BtnStyle.TONAL) {
                val s = clip()
                if (s.isNullOrBlank()) toast(t("Буфер пуст", "The clipboard is empty")) else answerBox?.setText(s)
            },
            btn(this, t("✓ Передать", "✓ Hand over"), primary = true) { accept(answerBox?.text?.toString().orEmpty()) }
        )
        c.muted(t("Ответ проходит те же проверки, что и ответ по API: если ИИ ошибся, Lumen попросит исправить — появится новый запрос.",
            "The answer gets the same checks as an API answer: if the AI made a mistake, Lumen asks for a fix — a new request appears."))

        val foot = Col(this, 12)
        foot.root.setBackgroundColor(Palette.SURFACE)
        foot.row(btn(this, t("Отменить запрос", "Cancel the request"), style = BtnStyle.OUTLINED) { cancel() })
        page(t("Другой ИИ", "Another AI"), c.root, footer = foot.root, keepScroll = true)
    }
}

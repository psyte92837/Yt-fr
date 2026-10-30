/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Pure helpers of the "Ask AI" bar (kept Android-free so they can be unit-tested). */
object AskAi {
    const val REQ_VOICE = 7731

    /** First non-blank hypothesis of the speech recogniser, capitalised; null when nothing was heard. */
    fun spoken(results: List<String>?): String? =
        results?.firstOrNull { it.isNotBlank() }?.trim()?.replaceFirstChar { it.uppercase() }

    /** Dictation is appended to what is already typed, separated by one space. */
    fun append(current: String, heard: String): String =
        if (current.isBlank()) heard else current.trimEnd() + " " + heard
}

/** Static bar pinned above the bottom navigation: tap to type a question, mic to dictate it. */
fun askAiBar(a: LumenBase, onAsk: () -> Unit, onMic: () -> Unit): View {
    val bar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Palette.CARD)
        setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6))
    }
    val field = text(a, t("Спросить AI о проекте…", "Ask AI about the project…"), 15f, color = Palette.MUTED).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = a.dp(44)
        setPadding(a.dp(16), 0, a.dp(16), 0)
        background = ripple(a, roundBg(Palette.SURFACE, 22f, a, Palette.OUTLINE), Palette.ON_SURFACE)
        contentDescription = t("Спросить AI", "Ask AI")
        setOnClickListener { onAsk() }
    }
    val mic = text(a, "🎤", 20f).apply {
        gravity = Gravity.CENTER
        background = ripple(a, roundBg(Palette.ACCENT_SOFT, 22f, a), Palette.ON_SOFT)
        contentDescription = t("Спросить голосом", "Ask by voice")
        setOnClickListener { onMic() }
    }
    bar.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    bar.addView(mic, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { leftMargin = a.dp(8) })
    return bar
}

private fun voiceIntent(lang: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "en") "en-US" else "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, t("Говорите…", "Speak…"))

/** Dictation through the system recogniser (no microphone permission needed: the recogniser app owns it). */
fun LumenBase.listen(onText: (String) -> Unit) {
    voiceCallback = onText
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(voiceIntent(Settings.lang), AskAi.REQ_VOICE)
    } catch (e: ActivityNotFoundException) {
        voiceCallback = null
        toast(t("Голосовой ввод недоступен на устройстве", "Voice input is not available on this device"))
    }
}

/** Bottom sheet: question field with a mic, answer shown below; the answer is also saved in the project's AI results. */
fun LumenBase.askAiSheet(project: Project, scope: List<Video>, startVoice: Boolean = false) {
    if (Provider.active() == null) {
        confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
        }
        return
    }
    var answerView: TextView? = null
    var busy = false
    bottomSheet(t("Спросить AI", "Ask AI")) { dismiss ->
        val q = edit(t("Ваш вопрос…", "Your question…"), multiline = true)
        val mic = button(t("🎤 Сказать голосом", "🎤 Speak"), style = BtnStyle.TONAL) {
            listen { heard -> q.setText(AskAi.append(q.text.toString(), heard)); q.setSelection(q.text.length) }
        }
        val answer = add(text(ctx, "", 14f).apply { setTextIsSelectable(true) }, 8)
        answerView = answer
        mic.contentDescription = t("Спросить голосом", "Ask by voice")
        button(t("Спросить", "Ask"), primary = true) {
            val question = q.text.toString().trim()
            if (question.isEmpty() || busy) return@button
            busy = true
            answer.text = t("Думаю…", "Thinking…")
            Lumen.async({ AiTasks.chat(project, scope, question) }, {
                busy = false
                answer.text = ""
                err(it)
            }) { r ->
                busy = false
                answer.text = r.text + "\n\n— ${r.model}"
            }
        }
        button(t("Прочитать вслух", "Read aloud"), style = BtnStyle.TEXT) {
            val s = answerView?.text?.toString().orEmpty()
            if (s.isNotBlank()) TtsActivity.open(this@askAiSheet, s, t("Ответ AI", "AI answer"))
        }
        button(t("Закрыть", "Close"), style = BtnStyle.TEXT) { dismiss() }
        if (startVoice) q.post { mic.performClick() }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

// Lumen Studio: the parts of Lumen the shared Studio files touch but this app does not have
// (YouTube, collection, analysis). The same names as in app/…/lumen, without NewPipe.

object Notify {
    const val CH_PROGRESS = "lumen_progress"
    const val CH_DONE = "lumen_done"
    const val CH_DONE_QUIET = "lumen_done_quiet"
    val VIBRATION = longArrayOf(0, 180, 90, 180, 90, 320)

    fun doneChannel() = if (Settings.vibrateDone) CH_DONE else CH_DONE_QUIET

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, t("Ход работы", "Progress"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, t("Готово (вибро)", "Done (vibrate)"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = VIBRATION
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE_QUIET, t("Готово (тихо)", "Done (quiet)"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
        })
    }

    @Suppress("DEPRECATION")
    fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)
}

/** No collection in Lumen Studio: never running. */
object CollectState {
    @Volatile var running = false
    @Volatile var projectId = ""
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var kind = "collect"
    @Volatile var lastError = ""
    fun summary(): String = ""
}

object CollectService {
    fun stop(ctx: Context) {}
}

/** Never opened (no collection runs); present for the task manager's intent. */
class ProjectActivity : Activity()

object YouTube {
    @Suppress("UNUSED_PARAMETER")
    fun search(queries: List<String>, f: SearchFilters, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Int, String) -> Unit = { _, _ -> }): List<Video> =
        throw UnsupportedOperationException(t("В Lumen Studio нет поиска YouTube", "Lumen Studio has no YouTube search"))
}

object AiTasks {
    fun requireProvider(): Provider = Provider.active()
        ?: Provider.MANUAL.takeIf { Relay.mode != "off" }
        ?: throw LlmException(t("ИИ не подключён. Откройте ⚙ Настройки → ИИ.", "No AI connected. Open ⚙ Settings → AI."))

    @Suppress("UNUSED_PARAMETER")
    fun chat(p: Project, videos: List<Video>, question: String, tab: String = ""): AiResult =
        throw UnsupportedOperationException(t("Чат по проекту есть только в Lumen", "The project chat is only in Lumen"))
}

object CommentDigest {
    const val KIND = "comment_digest"
    const val TAB = "comments"
}

/** Errors of the YouTube extractor are not counted here. */
object ExtractorHealth {
    @Suppress("UNUSED_PARAMETER")
    fun note(t: Throwable) {}
}

/** Lumen Studio has no in-app player: a video link opens outside. */
class PlayerActivity : Activity() {
    companion object {
        fun open(ctx: Context, url: String = "", query: String = "") = ctx.openUrl(url)
    }
}

/** A row of a found video gets an "open" button instead of the Lumen video menu. */
fun mediaBtn(ctx: Context, urlOrId: String, title: String = ""): View =
    iconBtn(ctx, "↗", Palette.MUTED) { ctx.openUrl(if (urlOrId.startsWith("http")) urlOrId else "https://www.youtube.com/watch?v=$urlOrId") }.apply {
        contentDescription = title.ifBlank { t("Открыть", "Open") }
    }

class SimpleWatcher(val on: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = on(s?.toString().orEmpty())
}

// prompt groups of the analysis screens: none in Lumen Studio
val SEO_PROMPTS = emptyMap<String, String>()
val DOSSIER_PROMPTS = emptyMap<String, String>()
val CUSTOM_REPORT_PROMPTS = emptyMap<String, String>()
val DIAGRAM_PROMPTS = emptyMap<String, String>()
val KMAP_PROMPTS = emptyMap<String, String>()
val BOT_PROMPTS = emptyMap<String, String>()
val MINIAPP_PROMPTS = emptyMap<String, String>()
val POST_PROMPTS = emptyMap<String, String>()
val IDEAS_PROMPTS = emptyMap<String, String>()
val LANGGAME_PROMPTS = emptyMap<String, String>()
val ALGO_PROMPTS = emptyMap<String, String>()
val COMMENT_DIGEST_PROMPTS = emptyMap<String, String>()
val PREDICT_PROMPTS = emptyMap<String, String>()
val VOC_PROMPTS = emptyMap<String, String>()
val PLAYER_PROMPTS = emptyMap<String, String>()

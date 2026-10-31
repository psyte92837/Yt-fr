/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * One list of everything that runs in the app: [BgTasks] work (AI, imports, exports, Studio steps,
 * short [Lumen.async] work) and the jobs of own services (collection/analysis, Studio render, speech).
 * Stops tasks by hand or by the watchdog and warns about hung ones with a notification.
 */
object TaskHub {
    class Row(
        val id: String,
        val title: String,
        val message: String,
        val startedAt: Long,
        val lastActive: Long,
        val waiting: Boolean,
        /** Asked to stop, still running. */
        val stopping: Boolean,
        val cancelAt: Long,
        val open: Intent?,
        val stop: (Context) -> Unit,
        val force: (Context) -> Unit
    )

    /** Service jobs have no progress clock of their own: the last time their status line changed. */
    private class Seen(var message: String, var changed: Long, val started: Long, var cancelAt: Long = 0L)
    private val seen = ConcurrentHashMap<String, Seen>()
    /** Hung tasks already reported by a notification. */
    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    const val NOTIF_BASE = 7900

    /** Quiet work shorter than this is not listed (it is just a quick load). */
    private const val QUIET_MS = 2000L

    fun rows(ctx: Context): List<Row> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Row>()
        BgTasks.all().filter { !it.quiet || now - it.startedAt >= QUIET_MS }.forEach { k ->
            out += Row("bg:${k.id}", k.title, k.message, k.startedAt, k.lastActive, k.waiting.get() > 0, k.cancelled, k.cancelAt,
                k.open, { BgTasks.stop(k) }, { BgTasks.kill(k) })
        }
        if (CollectState.running) out += service("collect", collectTitle(), CollectState.summary(),
            ctx, Intent(ctx, ProjectActivity::class.java).putExtra("id", CollectState.projectId), { CollectService.stop(it) }) {
            CollectState.running = false
            CollectService.stop(it)
        }
        if (StudioJob.running && !BgTasks.has("studio:render")) out += service("studio", t("Lumen Studio: сборка ролика", "Lumen Studio: building the video"),
            StudioJob.message, ctx, Intent(ctx, StudioActivity::class.java), { StudioService.cancel(it) }) {
            StudioJob.running = false
            StudioJob.request = null
            StudioJob.changed()
            runCatching { it.stopService(Intent(it, StudioService::class.java)) }
        }
        if (TtsState.synth || TtsState.saving) out += service("tts", t("Озвучка", "Read aloud") + TtsState.title.take(40).let { if (it.isBlank()) "" else ": $it" },
            if (TtsState.saving) t("Сохранение…", "Saving…") else "${TtsState.done}/${TtsState.total}", ctx, Intent(ctx, TtsActivity::class.java),
            { stopTts(it) }) {
            TtsState.synth = false
            TtsState.saving = false
            TtsState.changed()
            stopTts(it)
        }
        listOf("collect", "studio", "tts").filter { id -> out.none { it.id == id } }.forEach { seen.remove(it) }
        return out
    }

    private fun collectTitle() = when (CollectState.kind) {
        "recs" -> t("Рекомендации", "Recommendations")
        "quick" -> t("Быстрый анализ", "Instant analysis")
        "chgraph" -> t("Граф каналов", "Channel graph")
        "audit" -> t("Аудит алгоритма", "Algorithm audit")
        "style" -> t("Стиль видео", "Video style")
        "dossier" -> t("Досье", "Dossier")
        "harvest" -> t("Сбор данных", "Harvest")
        else -> t("Сбор данных", "Collection")
    } + (Store.project(CollectState.projectId)?.name?.let { " · $it" } ?: "")

    private fun stopTts(ctx: Context) {
        runCatching { ctx.startService(Intent(ctx, TtsService::class.java).setAction(TtsService.ACTION_STOP)) }
    }

    private fun service(id: String, title: String, message: String, ctx: Context, open: Intent,
                        stop: (Context) -> Unit, force: (Context) -> Unit): Row {
        val now = System.currentTimeMillis()
        val s = seen.getOrPut(id) { Seen(message, now, if (id == "studio" && StudioJob.startedAt > 0) StudioJob.startedAt else now) }
        if (s.message != message) {
            s.message = message
            s.changed = now
        }
        return Row(id, title, message, s.started, s.changed, false, s.cancelAt > 0, s.cancelAt, open,
            { c -> s.cancelAt = System.currentTimeMillis(); stop(c) }, { c -> force(c); seen.remove(id) })
    }

    fun count(ctx: Context) = rows(ctx).size

    fun stop(ctx: Context, id: String) {
        rows(ctx).firstOrNull { it.id == id }?.let { r -> if (r.stopping) r.force(ctx) else r.stop(ctx) }
        dismiss(ctx, id)
    }

    fun stopAll(ctx: Context) = rows(ctx).forEach { stop(ctx, it.id) }

    /** "Wait more": the task counts as alive again. */
    fun waitMore(ctx: Context, id: String) {
        if (id.startsWith("bg:")) BgTasks.all().firstOrNull { "bg:${it.id}" == id }?.beat()
        else seen[id]?.changed = System.currentTimeMillis()
        dismiss(ctx, id)
    }

    private fun dismiss(ctx: Context, id: String) {
        warned.remove(id)
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(notifId(id)) }
    }

    private fun notifId(id: String) = NOTIF_BASE + (id.hashCode() and 0x7fffffff) % 90

    /** Every 30 s: auto-stop of hung tasks, warnings about them, drop of stopped ones that do not end. */
    fun watchdog(ctx: Context) {
        val now = System.currentTimeMillis()
        val limit = Settings.taskStuckMin
        val rows = rows(ctx)
        for (r in rows) {
            when {
                r.stopping -> if (TaskHealth.force(now, r.cancelAt) && !r.id.startsWith("bg:")) r.force(ctx)
                TaskHealth.autoStop(now, r.lastActive, limit, r.waiting) -> {
                    L.w("tasks", "${r.title}: no progress for $limit min, stopping")
                    BgTasks.all().firstOrNull { "bg:${it.id}" == r.id }?.let { BgTasks.stop(it, auto = true) } ?: run {
                        r.stop(ctx)
                        BgTasks.record(r.title, TaskHealth.AUTO, now - r.startedAt)
                    }
                    dismiss(ctx, r.id)
                }
                TaskHealth.warn(now, r.lastActive, limit, r.waiting) -> if (warned.add(r.id)) warn(ctx, r, now)
                else -> if (warned.contains(r.id)) dismiss(ctx, r.id)
            }
        }
        synchronized(warned) { warned.toList() }.filter { id -> rows.none { it.id == id } }.forEach { dismiss(ctx, it) }
    }

    private fun warn(ctx: Context, r: Row, now: Long) {
        Notify.channels(ctx)
        val limit = Settings.taskStuckMin
        val idle = TaskText.elapsed(now - r.lastActive)
        val body = t("Нет прогресса $idle", "No progress for $idle") +
            (if (limit > 0) t(". Будет остановлена через ", ". Will be stopped in ") + TaskText.elapsed(limit * 60_000L - (now - r.lastActive)) else "") +
            (if (r.message.isNotBlank()) "\n" + r.message.take(200) else "")
        fun action(act: String, code: Int) = PendingIntent.getBroadcast(ctx, notifId(r.id) * 4 + code,
            Intent(ctx, TaskStopReceiver::class.java).setAction(act).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notify.builder(ctx, Notify.doneChannel())
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠ " + t("Похоже, зависла: ", "Looks hung: ") + r.title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(Nav.pending(ctx, notifId(r.id), Intent(ctx, TasksActivity::class.java)))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, t("Завершить", "Stop"), action(TaskStopReceiver.STOP, 1)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_recent_history, t("Подождать", "Wait"), action(TaskStopReceiver.WAIT, 2)).build())
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(notifId(r.id), b.build())
        } catch (x: SecurityException) {
            // notifications permission not granted
        }
    }
}

/** Buttons of the "task looks hung" notification. */
class TaskStopReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "lumen.TASK_STOP"
        const val WAIT = "lumen.TASK_WAIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Lumen.init(context)
        val id = intent.getStringExtra("id") ?: return
        when (intent.action) {
            STOP -> TaskHub.stop(context, id)
            WAIT -> TaskHub.waitMore(context, id)
        }
    }
}

/** Task manager: everything running, stop / force stop, hung tasks, auto-stop setting, history. */
class TasksActivity : LumenBase() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val limit = Settings.taskStuckMin
        val rows = TaskHub.rows(this)
        val c = Col(this)
        val stuck = rows.filter { !it.stopping && TaskHealth.state(now, it.lastActive, limit, it.waiting) == TaskHealth.State.STUCK }
        if (rows.isEmpty()) c.muted(t("Сейчас ничего не выполняется.", "Nothing is running now."))
        else c.row(
            btn(this, t("⏹ Остановить все (${rows.size})", "⏹ Stop all (${rows.size})"), style = BtnStyle.OUTLINED) {
                confirm(t("Остановить все задачи?", "Stop all tasks?"), "") { TaskHub.stopAll(this); render() }
            },
            *(if (stuck.isEmpty()) emptyArray<android.view.View>() else arrayOf<android.view.View>(btn(this, t("Зависшие (${stuck.size})", "Hung (${stuck.size})"), style = BtnStyle.TEXT) {
                stuck.forEach { TaskHub.stop(this, it.id) }
                render()
            }))
        )
        rows.forEach { r ->
            val st = TaskHealth.state(now, r.lastActive, limit, r.waiting)
            c.card {
                add(text(ctx, r.title, 14f, bold = true), 0)
                if (r.message.isNotBlank()) add(text(ctx, r.message.take(300), 13f), 4)
                val status = when {
                    r.stopping -> t("останавливается…", "stopping…")
                    r.waiting -> t("ждёт вашего подтверждения", "waits for you")
                    st == TaskHealth.State.STUCK -> "⚠ " + t("похоже, зависла: нет прогресса ", "looks hung: no progress for ") + TaskText.elapsed(now - r.lastActive)
                    st == TaskHealth.State.SLOW -> t("нет прогресса ", "no progress for ") + TaskText.elapsed(now - r.lastActive)
                    else -> t("работает", "running")
                }
                add(text(ctx, t("идёт ", "running for ") + TaskText.elapsed(now - r.startedAt) + " · " + status, 12f,
                    color = when { st == TaskHealth.State.STUCK && !r.stopping -> Palette.BAD; st == TaskHealth.State.SLOW || r.stopping -> Palette.WARN; else -> Palette.MUTED }), 4)
                val btns = mutableListOf<android.view.View>()
                r.open?.let { o -> btns += btn(ctx, t("Открыть", "Open"), style = BtnStyle.TEXT) { runCatching { startActivity(Intent(o).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
                btns += btn(ctx, if (r.stopping) t("Принудительно", "Force") else t("Остановить", "Stop"), style = BtnStyle.TEXT) {
                    TaskHub.stop(this@TasksActivity, r.id)
                    render()
                }
                if (st != TaskHealth.State.OK && !r.stopping) btns += btn(ctx, t("Подождать", "Wait"), style = BtnStyle.TEXT) {
                    TaskHub.waitMore(this@TasksActivity, r.id)
                    render()
                }
                row(*btns.toTypedArray())
            }
        }
        c.h2(t("Зависшие задачи", "Hung tasks"))
        c.muted(t("Задача без прогресса дольше лимита останавливается сама; на половине лимита приходит уведомление с кнопкой «Завершить».",
            "A task without progress longer than the limit is stopped; at half the limit a notification offers \"Stop\"."))
        c.add(choiceChips(this, TaskHealth.CHOICES.map { it to if (it == 0) t("не останавливать", "never") else "$it ${t("мин", "min")}" }, limit) {
            Settings.taskStuckMin = it
        })
        val h = BgTasks.history
        c.h2(t("Недавние", "Recent"))
        if (h.isEmpty()) c.muted("—")
        h.forEach { e ->
            c.muted(Fmt.dateTime(e.at) + " · " + TaskHealth.label(e.outcome) + " · " + TaskText.elapsed(e.ms) + "\n" + e.title +
                if (e.error.isNotBlank()) "\n" + e.error.take(200) else "")
        }
        page(t("Задачи", "Tasks"), c.root, keepScroll = true,
            actions = if (h.isEmpty()) emptyList() else listOf("🗑" to { BgTasks.clearHistory(); render() }))
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, TasksActivity::class.java))
    }
}

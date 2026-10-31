/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Text of the "running tasks" notification (pure logic, tested). */
object TaskText {
    /** Title and body for the running tasks given as (title, message) pairs. */
    fun summary(tasks: List<Pair<String, String>>): Pair<String, String> = when (tasks.size) {
        0 -> "Lumen" to t("Завершение…", "Finishing…")
        1 -> tasks[0].first to tasks[0].second.ifBlank { t("Выполняется…", "Running…") }
        else -> t("Lumen: задач в работе — ${tasks.size}", "Lumen: ${tasks.size} tasks running") to
            tasks.joinToString("\n") { (title, msg) -> if (msg.isBlank()) title else "$title: $msg" }
    }

    /** Progress 0..1 of the only task with a "done/total" counter; null = indeterminate. */
    fun fraction(tasks: List<Pair<String, String>>): Float? =
        if (tasks.size == 1) ProgressText.fraction(tasks[0].second) else null

    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "$s ${t("с", "s")}" else "${s / 60} ${t("мин", "min")} ${s % 60} ${t("с", "s")}"
    }
}

/**
 * Long tasks started from screens (AI, imports, downloads…). Each runs on its own thread while
 * [TaskService] shows its progress in a notification and keeps the process alive, so the work
 * goes on when the app is minimised or the screen is closed. When the app is not visible,
 * the end of the work is reported by a notification that opens the screen again.
 */
object BgTasks {
    class Task(val id: Int, val key: String, val title: String, val open: Intent?, val onCancel: (() -> Unit)?) {
        @Volatile var message = ""
        val startedAt = System.currentTimeMillis()
    }

    private val list = CopyOnWriteArrayList<Task>()
    private val seq = AtomicInteger(1)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val started = AtomicInteger(0)
    private var hooked = false

    /** Screens that want to know about progress (main thread). */
    val watchers = CopyOnWriteArrayList<() -> Unit>()

    fun running(): List<Task> = list.toList()
    fun has(prefix: String) = list.any { it.key.startsWith(prefix) }
    fun find(prefix: String) = list.firstOrNull { it.key.startsWith(prefix) }

    /** True while at least one screen of the app is on screen. */
    val visible get() = started.get() > 0

    /** Counts visible activities; called once from [Lumen.init]. */
    fun hook(app: Context) {
        if (hooked) return
        val a = app as? Application ?: return
        hooked = true
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) { if (started.decrementAndGet() < 0) started.set(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Intent that brings [a] back with its extras (for the notification tap). */
    fun reopen(a: Activity): Intent = Intent(a.intent ?: Intent()).setClass(a, a.javaClass)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Runs [work] in the background with a progress notification; [onDone] / [onError] come on
     * the main thread (also when the screen was closed meanwhile, so results saved there still land).
     * [key] groups tasks of one screen; [onCancel] adds "Cancel" to the notification.
     */
    fun <T> run(
        ctx: Context, key: String, title: String, open: Intent?,
        work: (Task) -> T, onError: (Throwable) -> Unit = {}, onCancel: (() -> Unit)? = null, onDone: (T) -> Unit
    ): Task {
        val task = Task(seq.getAndIncrement(), key, title, open, onCancel)
        list += task
        TaskService.ensure(ctx.applicationContext)
        changed()
        Thread {
            try {
                val r = work(task)
                handler.post {
                    runCatching { onDone(r) }.onFailure { L.w("tasks", "$key done: ${it.message}") }
                    finished(task, null)
                }
            } catch (e: Throwable) {
                if (e !is java.util.concurrent.CancellationException) L.e("tasks", "$key: ${e.message ?: e.javaClass.simpleName}", e)
                handler.post {
                    runCatching { onError(e) }.onFailure { L.w("tasks", "$key error: ${it.message}") }
                    finished(task, e)
                }
            }
        }.apply { name = "lumen-task-$key"; isDaemon = true; start() }
        return task
    }

    /** Status line of the tasks of [key]. */
    fun progress(key: String, msg: String) {
        var hit = false
        list.forEach { if (it.key == key) { it.message = msg; hit = true } }
        if (hit) changed()
    }

    fun cancelAll() = list.forEach { runCatching { it.onCancel?.invoke() } }

    private fun changed() = handler.post {
        TaskService.refresh()
        watchers.forEach { runCatching { it() } }
    }

    private fun finished(task: Task, e: Throwable?) {
        list.remove(task)
        changed()
        if (visible || e is java.util.concurrent.CancellationException) return
        // in a chain of steps only the last one (or a failure) is reported
        if (e == null && list.any { it.key == task.key }) return
        report(task, e)
    }

    private fun report(task: Task, e: Throwable?) {
        val ctx = Lumen.app
        if (e == null && !Settings.notifyDone) return
        Notify.channels(ctx)
        val body = if (e == null) t("Готово за ", "Done in ") + TaskText.elapsed(System.currentTimeMillis() - task.startedAt)
        else (e.message ?: e.javaClass.simpleName)
        val b = Notify.builder(ctx, Notify.doneChannel())
            .setSmallIcon(if (e == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle((if (e == null) "✓ " else "✗ ") + task.title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        task.open?.let {
            b.setContentIntent(PendingIntent.getActivity(ctx, 7500 + task.id % 400, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(7500 + task.id % 400, b.build())
        } catch (x: SecurityException) {
            // notifications permission not granted
        }
    }
}

/** Foreground service that holds the process while [BgTasks] has work; one notification for all tasks. */
class TaskService : Service() {
    companion object {
        const val NOTIF_ID = 7420
        const val ACTION_CANCEL = "lumen.TASKS_CANCEL"
        @Volatile private var instance: TaskService? = null

        fun ensure(ctx: Context) {
            if (instance != null) return
            try {
                val i = Intent(ctx, TaskService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // started from the background on Android 12+: the work still runs, only without the notification
                L.w("tasks", "service: ${e.message}")
            }
        }

        fun refresh() { instance?.update() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    private var wake: PowerManager.WakeLock? = null
    private val stopCheck = Runnable { if (BgTasks.running().isEmpty()) finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lumen.init(this)
        Notify.channels(this)
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        instance = this
        if (intent?.action == ACTION_CANCEL) BgTasks.cancelAll()
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:tasks").apply { acquire(2 * 60 * 60_000L) }
        }
        update()
        return START_NOT_STICKY
    }

    fun update() {
        handler.removeCallbacks(stopCheck)
        if (BgTasks.running().isEmpty()) {
            // a short pause: the next step of a chain starts right after the previous one ends
            handler.postDelayed(stopCheck, 4000)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700) return
        lastNotify = now
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
    }

    private fun build(): Notification {
        val tasks = BgTasks.running()
        val pairs = tasks.map { it.title to it.message }
        val (title, body) = TaskText.summary(pairs)
        val f = TaskText.fraction(pairs)
        @Suppress("DEPRECATION")
        val b = Notify.builder(this, Notify.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, ((f ?: 0f) * 1000).toInt(), f == null)
        tasks.firstOrNull { it.open != null }?.open?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 7421, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (tasks.any { it.onCancel != null }) {
            val cancel = PendingIntent.getService(this, 7422, Intent(this, TaskService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, t("Отмена", "Cancel"), cancel).build())
        }
        return b.build()
    }

    private fun finish() {
        instance = null
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        wake = null
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(stopCheck)
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        super.onDestroy()
    }
}

/** [Lumen.async] for long work: progress notification, goes on with the screen closed. */
fun <T> Activity.bgTask(title: String, work: () -> T, onError: (Throwable) -> Unit = {}, onDone: (T) -> Unit) {
    BgTasks.run(this, javaClass.simpleName, title, BgTasks.reopen(this), { work() }, onError, null, onDone)
}

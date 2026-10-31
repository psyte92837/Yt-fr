/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicBoolean

object Notify {
    const val CH_PROGRESS = "lumen_progress"
    const val CH_MONITOR = "lumen_monitor"
    const val CH_DONE = "lumen_done"
    const val CH_DONE_QUIET = "lumen_done_quiet"
    val VIBRATION = longArrayOf(0, 180, 90, 180, 90, 320)

    /** Channel for "task finished" alerts, with or without vibration (channel settings cannot change later). */
    fun doneChannel() = if (Settings.vibrateDone) CH_DONE else CH_DONE_QUIET

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Lumen: сбор / collection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Lumen: мониторинг / monitoring", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Lumen: готово (вибро) / done (vibrate)", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = VIBRATION
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE_QUIET, "Lumen: готово (тихо) / done (quiet)", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
        })
    }

    @Suppress("DEPRECATION")
    fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    fun openProject(ctx: Context, projectId: String): PendingIntent {
        val harvest = Store.project(projectId)?.quickMode == "harvest"
        val i = (if (harvest) Intent(ctx, HarvestActivity::class.java).setData(android.net.Uri.parse("lumen://harvest/$projectId")) else Intent(ctx, ProjectActivity::class.java))
            .putExtra("id", projectId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, projectId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "Task finished" alert with vibration (unless turned off in settings). */
    fun done(ctx: Context, id: Int, title: String, body: String, projectId: String, ok: Boolean = true) {
        if (!Settings.notifyDone) return
        channels(ctx)
        val b = builder(ctx, doneChannel())
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openProject(ctx, projectId)).setAutoCancel(true)
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH)
            if (Settings.vibrateDone) b.setVibrate(VIBRATION)
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }

    fun post(ctx: Context, id: Int, title: String, body: String, projectId: String) {
        channels(ctx)
        val n = builder(ctx, CH_MONITOR).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openProject(ctx, projectId)).setAutoCancel(true).build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }
}

/** Shared collection progress, observed by the project screen. */
object CollectState {
    @Volatile var running = false
    @Volatile var projectId = ""
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var errors = 0
    @Volatile var current = ""
    @Volatile var kind = "collect" // collect | recs
    @Volatile var lastResult = ""
    @Volatile var lastError = ""
    val cancel = AtomicBoolean(false)

    fun summary(): String = when {
        !running -> ""
        kind == "recs" -> t("Рекомендации: ", "Recommendations: ") + current
        kind == "quick" -> t("Быстрый анализ: ", "Instant analysis: ") + current
        kind == "chgraph" -> current
        kind == "audit" -> t("Аудит алгоритма: ", "Algorithm audit: ") + current
        kind == "style" -> t("Стиль видео: ", "Video style: ") + current
        kind == "dossier" -> current
        else -> "$done / $total · ${t("ошибок", "errors")}: $errors\n$current"
    }
}

/**
 * Foreground service that collects metadata, transcripts and comments.
 * Progress is saved per video, so stopping and starting again continues where it stopped.
 */
class CollectService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lumen.init(this)
        if (intent?.action == ACTION_STOP) {
            CollectState.cancel.set(true)
            return START_NOT_STICKY
        }
        Notify.channels(this)
        // startForeground must be called for every startForegroundService() call, even if we ignore it
        if (CollectState.running) {
            startFg(CollectState.current, CollectState.done, CollectState.total)
            return START_NOT_STICKY
        }
        val pid = intent?.getStringExtra("id")
        val ids = intent?.getStringArrayListExtra("videos") ?: arrayListOf()
        val kind = intent?.getStringExtra("kind") ?: "collect"
        startFg(t("Подготовка…", "Preparing…"), 0, ids.size)
        if (pid == null) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        CollectState.cancel.set(false)
        CollectState.running = true
        CollectState.projectId = pid
        CollectState.done = 0
        CollectState.errors = 0
        CollectState.total = ids.size
        CollectState.kind = kind
        L.i("task", "$kind started: project=$pid videos=${ids.size}")
        CollectState.current = ""
        CollectState.lastError = ""
        Thread {
            try {
                if (kind == "harvest") {
                    CollectState.lastResult = Harvest.run(pid, CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringBefore('\n'), 0, 0)
                    }
                } else if (kind == "quick") {
                    CollectState.lastResult = QuickAnalysis.run(pid, CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringBefore('\n'), 0, 0)
                    }
                } else if (kind == "style") {
                    CollectState.lastResult = VideoStyle.run(pid, ids.firstOrNull() ?: "") { msg ->
                        CollectState.current = msg
                        startFg(msg, 0, 0)
                    }
                } else if (kind == "chgraph") {
                    val seeds = (Store.readFile(pid, "chgraph_seeds.txt") ?: "").lines().filter { it.isNotBlank() }
                    val q = QuickConfig.load()
                    CollectState.lastResult = ChannelExplorer.run(pid, seeds,
                        ChannelExplorer.Config(depth = q.graphDepth, maxChannels = q.graphChannels, budget = q.graphChannels * 3, topic = q.topicFilter), CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringAfter('\n'), 0, 0)
                    }
                } else if (kind == "dossier") {
                    CollectState.lastResult = Dossier.run(pid, CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringAfter('\n'), 0, 0)
                    }
                } else if (kind == "audit") {
                    CollectState.lastResult = AuditEngine.run(pid, CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringBefore('\n'), 0, 0)
                    }
                } else if (kind == "recs") {
                    CollectState.lastResult = RecEngine.run(pid, CollectState.cancel) { msg ->
                        CollectState.current = msg
                        startFg(msg.substringAfter('\n'), 0, 0)
                    }
                } else {
                    run(pid, ids)
                }
            } catch (e: Exception) {
                CollectState.lastError = e.message ?: e.javaClass.simpleName
                L.e("task", "$kind failed for project $pid", e)
            } finally {
                L.i("task", "$kind finished: done=${CollectState.done}/${CollectState.total} errors=${CollectState.errors} " +
                    "cancelled=${CollectState.cancel.get()} ${CollectState.lastResult.take(200)}")
                CollectState.running = false
                if (!CollectState.cancel.get()) {
                    val failed = CollectState.lastError.isNotEmpty()
                    val label = when (kind) {
                        "quick" -> t("Быстрый анализ", "Instant analysis")
                        "style" -> t("Стиль видео", "Video style")
                        "chgraph" -> t("Граф каналов", "Channel graph")
                        "dossier" -> t("AI-досье", "AI dossier")
                        "audit" -> t("Аудит алгоритма", "Algorithm audit")
                        "recs" -> t("Рекомендации", "Recommendations")
                        else -> t("Сбор", "Collection")
                    }
                    val name = Store.project(pid)?.name.orEmpty()
                    val body = if (failed) CollectState.lastError else CollectState.lastResult.ifBlank {
                        t("Готово: ${CollectState.done}/${CollectState.total}, ошибок ${CollectState.errors}", "Done: ${CollectState.done}/${CollectState.total}, errors ${CollectState.errors}")
                    }
                    Notify.done(this, NOTIF_ID + 10, (if (failed) "⚠️ " else "✅ ") + label + if (name.isNotBlank()) " · $name" else "", body.take(300), pid, !failed)
                }
                stopForegroundCompat()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun run(pid: String, ids: List<String>) {
        val project = Store.project(pid) ?: return
        val added = java.util.concurrent.atomic.AtomicInteger(0)
        Par.each(ids, cancel = CollectState.cancel) { _, id ->
            val v = Store.videos(pid)[id] ?: return@each
            CollectState.current = v.title
            startFg(v.title, CollectState.done, CollectState.total)
            var ok = false
            var lastError = ""
            for (attempt in 0..2) {
                if (CollectState.cancel.get()) break
                try {
                    YouTube.collect(pid, project.phrase, v, project.collect)
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (attempt == 2) L.e("collect", "video ${v.id} failed after 3 attempts", e) else L.w("collect", "video ${v.id} attempt ${attempt + 1}: $lastError")
                    Thread.sleep(2000L * (attempt + 1))
                }
            }
            if (ok) {
                added.incrementAndGet()
            } else {
                synchronized(CollectState) { CollectState.errors++ }
                v.collectError = lastError.take(200)
            }
            Store.updateVideo(pid, id) { saved ->
                saved.collected = v.collected
                saved.collectError = v.collectError
                saved.description = v.description
                saved.tags = v.tags
                saved.likes = v.likes
                saved.category = v.category
                saved.subscribers = v.subscribers
                saved.hasTranscript = v.hasTranscript
                saved.transcriptSource = v.transcriptSource
                saved.commentsCount = v.commentsCount
                if (v.views >= 0) saved.views = v.views
                if (v.uploadMs > 0) saved.uploadMs = v.uploadMs
                if (v.duration > 0) saved.duration = v.duration
                if (v.title.isNotBlank()) saved.title = v.title
            }
            synchronized(CollectState) { CollectState.done++ }
        }
        // channel statistics and links for the collected videos (cached for 12 h)
        if (project.collect.channelStats && !CollectState.cancel.get()) {
            val vids = Store.videos(pid).values.filter { it.id in ids }
            ChannelFetch.forVideos(pid, vids, CollectState.cancel, 50) { msg ->
                CollectState.current = msg
                startFg(msg, CollectState.done, CollectState.total)
            }
        }
        Store.project(pid)?.let {
            it.runs.add(RunRecord(System.currentTimeMillis(), "collect", ids.size, added.get(),
                if (CollectState.cancel.get()) "stopped" else "errors=${CollectState.errors}"))
            Store.save(it)
        }
    }

    private fun startFg(text: String, done: Int, total: Int) {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CollectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notify.builder(this, Notify.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (CollectState.kind == "recs") "Lumen: " + t("рекомендации", "recommendations")
                else if (CollectState.kind == "harvest") "Lumen: " + t("сбор похожих", "collecting similar")
                else if (CollectState.kind == "quick") "Lumen: " + t("быстрый анализ", "instant analysis")
                else if (CollectState.kind == "chgraph") "Lumen: " + t("граф каналов", "channel graph")
                else if (CollectState.kind == "audit") "Lumen: " + t("аудит алгоритма", "algorithm audit")
                else if (CollectState.kind == "style") "Lumen: " + t("стиль видео", "video style")
                else if (CollectState.kind == "dossier") "Lumen: " + t("AI-досье", "AI dossier")
                else "Lumen: ${t("сбор", "collecting")} $done/$total"
            )
            .setContentText(text)
            .setProgress(maxOf(1, total), done, total == 0)
            .setOngoing(true)
            .setContentIntent(Notify.openProject(this, CollectState.projectId))
            .addAction(Notification.Action.Builder(null, t("Стоп", "Stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    companion object {
        const val ACTION_STOP = "lumen.STOP"
        const val NOTIF_ID = 7301

        fun start(ctx: Context, projectId: String, videoIds: List<String>) {
            val i = Intent(ctx, CollectService::class.java).putExtra("id", projectId)
                .putStringArrayListExtra("videos", ArrayList(videoIds))
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun startKind(ctx: Context, projectId: String, kind: String) {
            val i = Intent(ctx, CollectService::class.java).putExtra("id", projectId).putExtra("kind", kind)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Video style detection for one video of a project (only on the user's request). */
        fun startStyle(ctx: Context, projectId: String, videoId: String) {
            val i = Intent(ctx, CollectService::class.java).putExtra("id", projectId).putExtra("kind", "style")
                .putStringArrayListExtra("videos", arrayListOf(videoId))
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun startRecs(ctx: Context, projectId: String) {
            val i = Intent(ctx, CollectService::class.java).putExtra("id", projectId).putExtra("kind", "recs")
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            CollectState.cancel.set(true)
            ctx.startService(Intent(ctx, CollectService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Optional scheduled monitoring of a project (JobScheduler, survives reboots). */
object Monitoring {
    private fun jobId(pid: String) = 50_000 + (pid.hashCode() and 0x7FFF)

    fun apply(ctx: Context, p: Project) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.cancel(jobId(p.id))
        if (!p.monitor.enabled || !Settings.monitoringAllowed || p.archived) return
        val extras = PersistableBundle().apply { putString("id", p.id) }
        val job = JobInfo.Builder(jobId(p.id), ComponentName(ctx, MonitorJob::class.java))
            .setPeriodic(maxOf(1, p.monitor.hours) * 3_600_000L)
            .setRequiredNetworkType(if (p.monitor.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(p.monitor.charging)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        js.schedule(job)
    }

    fun cancel(ctx: Context, pid: String) = ctx.getSystemService(JobScheduler::class.java).cancel(jobId(pid))

    fun reapplyAll(ctx: Context) = Store.projects().forEach { apply(ctx, it) }

    /** Finds new videos since the last run, collects them and optionally summarises. Returns added count. */
    fun runOnce(ctx: Context, pid: String): Int {
        val p = Store.project(pid) ?: return 0
        val f = SearchFilters.from(p.filters.toJson())
        if (p.lastRunAt > 0) {
            f.period = "custom"
            f.from = Fmt.isoDay(p.lastRunAt - 86_400_000L)
            f.to = ""
        }
        f.sort = "date"
        f.limit = minOf(60, maxOf(20, p.filters.limit))
        var queries = p.effectiveQueries()
        var extra = emptyList<String>()
        if (p.monitor.anyLang) {
            // any language: no region or original-language filter, the topic is also searched in translation
            f.country = ""
            f.origLang = ""
            queries = WatchLangs.merge(queries, WatchLangs.translated(p))
            extra = queries - p.effectiveQueries().toSet()
            f.limit = minOf(200, maxOf(f.limit, queries.size * 10))
        }
        val existing = Store.videos(pid).keys
        val rejected = WatchFilter.rejected(pid)
        val found = YouTube.search(queries, f).filter { it.id !in existing && it.id !in rejected }.toMutableList()
        var dropped = 0
        if (p.monitor.aiFilter && found.isNotEmpty()) {
            val (keep, drop) = WatchFilter.apply(p, found, extra)
            dropped = drop.size
            found.retainAll(keep.toSet())
            L.i("monitor", "project $pid: filter kept ${keep.size}, rejected $dropped")
        }
        var added = Store.mergeVideos(pid, found)
        if (p.monitor.recDrip > 0) {
            try {
                val dripped = RecEngine.drip(pid, p.monitor.recDrip)
                found.addAll(dripped)
                added += dripped.size
            } catch (e: Exception) {
                // recommendations are optional
            }
        }
        Par.each(found.toList()) { _, v ->
            try {
                YouTube.collect(pid, p.phrase, v, p.collect)
                Store.updateVideo(pid, v.id) { s ->
                    s.collected = true
                    s.description = v.description
                    s.tags = v.tags
                    s.likes = v.likes
                    s.category = v.category
                    s.subscribers = v.subscribers
                    s.hasTranscript = v.hasTranscript
                    s.transcriptSource = v.transcriptSource
                    s.commentsCount = v.commentsCount
                }
            } catch (e: Exception) {
                // keep going, the user can re-collect later
            }
        }
        val ids = found.map { it.id }.toSet()
        val fresh = Store.videos(pid).values.filter { it.id in ids }.ifEmpty { found }
        val summary = if (added > 0 && p.monitor.aiSummary) WatchDigest.ai(p, fresh) else ""
        Store.project(pid)?.let {
            it.lastRunAt = System.currentTimeMillis()
            it.newCount += added
            it.runs.add(RunRecord(it.lastRunAt, "monitor", found.size, added))
            Store.save(it)
        }
        if (added > 0 && Ideas.owns(pid)) {
            Ideas.afterRun(ctx, pid, fresh)
        } else if (added > 0) {
            val title = WatchDigest.title(p.name, added, fresh)
            val body = WatchDigest.body(fresh, summary, dropped)
            WatchDigest.remember(pid, WatchDigest.Entry(System.currentTimeMillis(), title, body))
            Notify.post(ctx, jobId(pid), title, body, pid)
        }
        if (added > 0) AutoBackup.maybe(ctx)
        return added
    }
}

class MonitorJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Lumen.init(this)
        val pid = params.extras.getString("id") ?: return false
        Thread {
            var retry = false
            try {
                val n = Monitoring.runOnce(this, pid)
                L.i("monitor", "project $pid: new videos $n")
            } catch (e: Exception) {
                L.e("monitor", "project $pid failed, will retry", e)
                retry = true
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

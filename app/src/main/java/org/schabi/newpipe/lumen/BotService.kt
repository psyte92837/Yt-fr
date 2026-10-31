/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Live state of the bots for the screens and the notification. */
object BotRun {
    class Status(@Volatile var state: String = STOPPED, @Volatile var text: String = "", @Volatile var at: Long = 0)

    const val STOPPED = "stopped"
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val ERROR = "error"

    private val status = ConcurrentHashMap<String, Status>()

    fun of(id: String): Status = status.getOrPut(id) { Status() }

    fun set(id: String, state: String, text: String) {
        val s = of(id)
        val changed = s.state != state || s.text != text
        s.state = state
        s.text = text
        s.at = System.currentTimeMillis()
        if (changed) changed()
    }

    @Volatile var serviceRunning = false

    /** The visible bots screen refreshes itself through this. */
    @Volatile var listener: (() -> Unit)? = null

    fun changed() {
        Lumen.ui { listener?.invoke() }
        BotService.instance?.updateNotification()
    }
}

object BotManager {
    const val CH_SERVICE = "lumen_bots"
    const val CH_EVENTS = "lumen_bot_events"
    private const val JOB_WATCH = 49_101

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Lumen: боты работают / bots running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Lumen: события ботов / bot events", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun anyEnabled(): Boolean = Bots.list().any { it.enabled }

    /** Starts the service when some bot is on, stops it when none is. */
    fun sync(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!anyEnabled()) {
            if (BotRun.serviceRunning) app.stopService(Intent(app, BotService::class.java))
            cancelWatch(app)
            return true
        }
        scheduleWatch(app)
        return try {
            val i = Intent(app, BotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            true
        } catch (e: Exception) {
            // Android 12+ refuses to start it from the background without the battery exemption
            L.w("bots", "cannot start bot service: ${e.message}")
            stoppedNotice(app)
            false
        }
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        Bots.update(id) { it.enabled = on }
        // a running bot answers in the background: no review screen can be waited for there
        if (on && BotCode.usesAi(Bots.code(id))) SendGate.setStored(TASK_REPLY, Outgoing.Rule.SEND)
        if (!on) BotRun.set(id, BotRun.STOPPED, "")
        sync(ctx)
        BotRun.changed()
    }

    fun stopAll(ctx: Context) {
        Bots.list().filter { it.enabled }.forEach { b -> Bots.update(b.id) { it.enabled = false } }
        sync(ctx)
        BotRun.changed()
    }

    const val TASK_REPLY = "bot_reply"

    fun batteryOk(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        true
    }

    /** System dialog "let the app run in the background"; falls back to the battery settings list. */
    @Suppress("BatteryLife")
    fun askBattery(a: Activity) {
        try {
            a.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName)))
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                a.toast(t("Откройте настройки батареи и снимите ограничения для Lumen", "Open battery settings and remove restrictions for Lumen"))
            }
        }
    }

    fun openBots(ctx: Context, botId: String = ""): PendingIntent {
        val i = (if (botId.isEmpty()) Intent(ctx, BotsActivity::class.java) else Intent(ctx, BotActivity::class.java).putExtra("id", botId))
        return Nav.pending(ctx, ("bots" + botId).hashCode(), i)
    }

    /** A phone notification from a bot (new user, error, notify() from the code). */
    fun event(ctx: Context, botId: String, title: String, body: String) {
        channels(ctx)
        val n = Notify.builder(ctx, CH_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openBots(ctx, botId)).setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(("ev$botId" + System.nanoTime() % 50).hashCode(), n)
        } catch (e: SecurityException) {
            // notifications are not allowed
        }
    }

    private fun stoppedNotice(ctx: Context) {
        channels(ctx)
        val n = Notify.builder(ctx, CH_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(t("Боты остановлены системой", "Bots were stopped by the system"))
            .setContentText(t("Нажмите, чтобы запустить снова", "Tap to start them again"))
            .setContentIntent(openBots(ctx)).setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(49_102, n)
        } catch (e: SecurityException) {
            // notifications are not allowed
        }
    }

    /** Every 15 min: brings the service back if the system killed it. */
    fun scheduleWatch(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_WATCH) != null) return
        js.schedule(
            JobInfo.Builder(JOB_WATCH, ComponentName(ctx, BotWatchJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build()
        )
    }

    private fun cancelWatch(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_WATCH)
    }
}

/** Keeps the enabled bots running: one worker thread per bot polls Telegram and runs its code. */
class BotService : Service() {
    private val workers = HashMap<String, BotWorker>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Lumen.init(this)
        instance = this
        BotRun.serviceRunning = true
        BotManager.channels(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must follow every startForegroundService call
        startForegroundCompat()
        if (intent?.action == ACTION_STOP_ALL) {
            Bots.list().filter { it.enabled }.forEach { b -> Bots.update(b.id) { it.enabled = false } }
        }
        refresh()
        return START_STICKY
    }

    @Synchronized
    fun refresh() {
        val on = Bots.list().filter { it.enabled }.map { it.id }.toSet()
        workers.entries.removeAll { (id, w) ->
            if (id !in on) w.shutdown()
            !w.isAlive || id !in on
        }
        for (id in on) {
            val w = workers[id]
            if (w == null || !w.isAlive) {
                val nw = BotWorker(applicationContext, id)
                workers[id] = nw
                nw.start()
            }
        }
        if (on.isEmpty()) {
            stopSelf()
            return
        }
        holdWake()
        updateNotification()
    }

    private fun holdWake() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:bots").apply {
            setReferenceCounted(false)
            acquire(6 * 3_600_000L)
        }
    }

    private fun buildNotification(): Notification {
        val bots = Bots.list().filter { it.enabled }
        val names = bots.joinToString(", ") { if (it.username.isNotBlank()) "@" + it.username else it.name }
        val errors = bots.count { BotRun.of(it.id).state == BotRun.ERROR }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = t("Lumen Боты: работают ${bots.size}", "Lumen Bots: ${bots.size} running") +
            if (errors > 0) t(" · ошибок: $errors", " · errors: $errors") else ""
        return Notify.builder(this, BotManager.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(names.ifBlank { t("Запуск…", "Starting…") })
            .setOngoing(true)
            .setContentIntent(BotManager.openBots(this))
            .addAction(Notification.Action.Builder(null, t("Остановить все", "Stop all"), stop).build())
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    private var lastNotif = 0L

    fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 1500) return
        lastNotif = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // notifications are not allowed: the service still runs
        }
    }

    override fun onDestroy() {
        synchronized(this) {
            workers.values.forEach { it.shutdown() }
            workers.clear()
        }
        wake?.let { if (it.isHeld) it.release() }
        instance = null
        BotRun.serviceRunning = false
        BotRun.changed()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4711
        const val ACTION_STOP_ALL = "lumen.bots.STOP_ALL"

        @Volatile var instance: BotService? = null
    }
}

/** Restarts the bots after a reboot or an app update. */
class BotBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Lumen.init(context)
        if (BotManager.anyEnabled()) BotManager.sync(context)
    }
}

/** Watchdog: if the system killed the service, start it again. */
class BotWatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Lumen.init(this)
        if (BotManager.anyEnabled() && !BotRun.serviceRunning) BotManager.sync(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/** One bot: long-polls Telegram, routes updates to the bot code, runs its schedules. */
class BotWorker(private val ctx: Context, private val botId: String) : Thread("bot-$botId") {
    @Volatile private var stopping = false
    private var api: TgApi? = null
    private var runtime: BotRuntime? = null
    private var loadedVersion = -1L
    private var username = ""
    private val limiter = AiLimiter(20, 300)
    private var sched = LongArray(0)
    private var schedSpecs = emptyList<BotSchedule.Spec?>()
    private var anchor = 0L
    private var lastErrorNotice = 0L
    private var wrongPins = 0

    fun shutdown() {
        stopping = true
        api?.abort()
        interrupt()
    }

    override fun run() {
        var backoff = 5_000L
        BotRun.set(botId, BotRun.STARTING, t("Подключение…", "Connecting…"))
        try {
            while (!stopping) {
                val info = Bots.get(botId)
                if (info == null || !info.enabled) break
                try {
                    if (runtime == null || info.codeVersion != loadedVersion) load(info)
                    api?.let { applyMenu(it, info) }
                    limiter.perUserHour = info.aiPerUserHour
                    limiter.perDay = info.aiPerDay
                    runSchedules()
                    val a = api ?: break
                    runPosts(a)
                    val batch = a.getUpdates(Bots.offset(botId) + 0, 20, username)
                    BotPosts.noteChats(botId, a.chats).forEach { c ->
                        Bots.log(botId, BotLogEntry.SYS, 0, "", t("Бот добавлен в чат: ", "The bot was added to: ") + c.label + " (${c.chat})")
                    }
                    for (u in batch.updates) {
                        if (stopping) break
                        // at most once: a message that crashes the bot must not come back forever
                        Bots.setOffset(botId, u.updateId + 1)
                        handle(u)
                    }
                    if (batch.lastId >= 0 && batch.lastId + 1 > Bots.offset(botId)) Bots.setOffset(botId, batch.lastId + 1)
                    backoff = 5_000L
                    BotRun.set(botId, BotRun.RUNNING, t("Работает", "Running") + if (username.isNotBlank()) " · @$username" else "")
                } catch (e: TgError) {
                    if (stopping) break
                    when (e.code) {
                        401, 404 -> {
                            fail(t("Telegram не принял токен. Проверьте его в настройках бота.", "Telegram rejected the token. Check it in the bot settings."))
                            Bots.update(botId) { it.enabled = false }
                            break
                        }
                        409 -> {
                            BotRun.set(botId, BotRun.WAITING, t("Токен занят другой программой или вебхуком. Повтор через 30 с", "The token is used by another program or a webhook. Retrying in 30 s"))
                            pause(30_000L)
                        }
                        429 -> pause(maxOf(e.retryAfter, 5) * 1000L)
                        else -> {
                            Bots.log(botId, BotLogEntry.ERR, 0, "", "Telegram: ${e.description}")
                            pause(backoff)
                            backoff = minOf(backoff * 2, 60_000L)
                        }
                    }
                } catch (e: BotJsError) {
                    if (stopping) break
                    // broken code: do not read updates, they wait in Telegram (up to 24 h) until the code is fixed
                    fail(t("Ошибка в коде: ", "Code error: ") + e.full)
                    waitForNewCode()
                } catch (e: Exception) {
                    if (stopping) break
                    val msg = e.message ?: e.javaClass.simpleName
                    if (TgApi.transient(e)) {
                        BotRun.set(botId, BotRun.WAITING, t("Нет связи с Telegram, повтор через ${backoff / 1000} с", "No connection to Telegram, retrying in ${backoff / 1000} s"))
                    } else {
                        Bots.log(botId, BotLogEntry.ERR, 0, "", msg)
                        L.w("bots", "$botId: $msg", e)
                    }
                    pause(backoff)
                    backoff = minOf(backoff * 2, 60_000L)
                }
            }
        } finally {
            BotRun.set(botId, BotRun.STOPPED, "")
        }
    }

    private fun pause(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            // shutdown
        }
    }

    private fun waitForNewCode() {
        val v = loadedVersion
        while (!stopping) {
            pause(3_000L)
            val b = Bots.get(botId) ?: return
            if (!b.enabled || b.codeVersion != v) return
        }
    }

    private fun fail(text: String) {
        BotRun.set(botId, BotRun.ERROR, text)
        Bots.log(botId, BotLogEntry.ERR, 0, "", text)
        val b = Bots.get(botId)
        if (b?.notifyErrors == true) notifyError(b, text)
    }

    private fun notifyError(b: BotInfo, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorNotice < 10 * 60_000L) return
        lastErrorNotice = now
        BotManager.event(ctx, botId, "⚠️ " + label(b), text)
    }

    private fun label(b: BotInfo) = if (b.username.isNotBlank()) "@" + b.username else b.name

    private fun load(info: BotInfo) {
        val token = Bots.token(botId) ?: throw TgError(401, "no token")
        val a = api ?: TgApi(token).also { api = it }
        if (username.isEmpty()) {
            val me = a.getMe()
            username = me.second
            if (info.username != username) Bots.update(botId) { it.username = username }
            a.deleteWebhook()
        }
        loadedVersion = info.codeVersion
        runtime = null
        val rt = BotRuntime(Bots.code(botId), Host(), 20_000)
        val meta = rt.load()
        runtime = rt
        anchor = System.currentTimeMillis()
        schedSpecs = meta.schedules.map { BotSchedule.parse(it) }
        sched = LongArray(schedSpecs.size) { i -> schedSpecs[i]?.let { BotSchedule.next(it, anchor, anchor) } ?: Long.MAX_VALUE }
        meta.schedules.forEachIndexed { i, s ->
            if (schedSpecs[i] == null) Bots.log(botId, BotLogEntry.ERR, 0, "", "schedule('$s'): " + t("непонятное расписание", "unknown schedule"))
        }
        Bots.update(botId) { it.commands = meta.commands }
        try {
            a.setCommands(meta.commands.map { (n, d) ->
                n to d.ifBlank { if (n == "start") t("Начать", "Start") else if (n == "help") t("Помощь", "Help") else n }
            })
        } catch (e: TgError) {
            Bots.log(botId, BotLogEntry.ERR, 0, "", "setMyCommands: ${e.description}")
        }
        applyMenu(a, info)
        Bots.log(botId, BotLogEntry.SYS, 0, "", t("Код загружен, команд: ${meta.commands.size}", "Code loaded, commands: ${meta.commands.size}"))
    }

    private var menuShown = ""

    /** Mini app in the menu next to the input (or the command list). */
    private fun applyMenu(a: TgApi, b: BotInfo) {
        val want = if (b.miniAppMenu && b.miniAppUrl.isNotBlank()) b.appLink else ""
        if (want == menuShown) return
        try {
            a.setMenuButton(want, b.miniAppTitle.ifBlank { t("Открыть", "Open") })
            menuShown = want
        } catch (e: TgError) {
            Bots.log(botId, BotLogEntry.ERR, 0, "", "setChatMenuButton: ${e.description}")
        }
    }

    private var postsAt = 0L

    /** Scheduled posts and auto-filling plans (checked at most every 30 s). */
    private fun runPosts(a: TgApi) {
        val now = System.currentTimeMillis()
        if (now - postsAt < 30_000L) return
        postsAt = now
        try {
            PostEngine.tick(ctx, botId, a)
        } catch (e: Exception) {
            Bots.log(botId, BotLogEntry.ERR, 0, "", "posts: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun runSchedules() {
        val rt = runtime ?: return
        val now = System.currentTimeMillis()
        for (i in sched.indices) {
            if (now < sched[i]) continue
            val spec = schedSpecs[i] ?: continue
            sched[i] = BotSchedule.next(spec, now, anchor)
            try {
                rt.runSchedule(i)
            } catch (e: BotJsError) {
                Bots.log(botId, BotLogEntry.ERR, 0, "", "schedule: " + e.full)
                Bots.get(botId)?.let { if (it.notifyErrors) notifyError(it, e.full) }
            }
        }
    }

    private fun handle(u: TgUpdate) {
        val b = Bots.get(botId) ?: return
        val a = api ?: return
        val who = u.user.name + if (u.user.username.isNotBlank()) " @" + u.user.username else ""
        val shown = when {
            u.kind == "callback" -> "[${t("кнопка", "button")}] ${u.data}"
            u.appData.isNotBlank() -> "[${t("мини-апп", "mini app")}] ${u.appData}"
            u.photo -> "[${t("фото", "photo")}] ${u.text}"
            else -> u.text
        }
        Bots.log(botId, BotLogEntry.IN, u.chatId, who, shown)
        if (Bots.touchUser(botId, u.user) && b.notifyNewUser) {
            BotManager.event(ctx, botId, label(b) + ": " + t("новый пользователь", "new user"), who)
        } else if (b.notifyMessages) {
            BotManager.event(ctx, botId, label(b) + ": " + who, shown)
        }
        BotOwner.parse(u.command, u.args)?.let { cmd ->
            if (cmd is BotOwner.Cmd.Claim || u.user.id == b.ownerChat) {
                owner(cmd, u, b, a)
                return
            }
        }
        if (!BotAccess.allowed(b.allow, u.user, b.ownerChat)) {
            if (u.kind == "message") a.send(u.chatId, t("⛔ Это закрытый бот.", "⛔ This bot is private."), JSONObject())
            else a.answer(u.callbackId, "⛔")
            return
        }
        val rt = runtime ?: return
        try {
            rt.handle(u)
        } catch (e: BotJsError) {
            Bots.log(botId, BotLogEntry.ERR, u.chatId, who, e.full)
            if (b.notifyErrors) notifyError(b, e.full)
            try {
                a.send(u.chatId, t("⚠️ В боте произошла ошибка, владелец уже знает о ней.", "⚠️ The bot hit an error, its owner has been told."), JSONObject())
            } catch (x: Exception) {
                // nothing more to do
            }
        }
    }

    private fun owner(cmd: BotOwner.Cmd, u: TgUpdate, b: BotInfo, a: TgApi) {
        val reply = when (cmd) {
            is BotOwner.Cmd.Claim -> when {
                wrongPins >= 5 -> null
                b.ownerPin.isNotEmpty() && cmd.pin == b.ownerPin -> {
                    Bots.update(botId) { it.ownerChat = u.user.id }
                    BotRun.changed()
                    t("✅ Теперь вы владелец бота.\n/lumen_stats — статистика\n/lumen_logs — журнал\n/lumen_stop — остановить бота",
                        "✅ You are now the bot owner.\n/lumen_stats — statistics\n/lumen_logs — journal\n/lumen_stop — stop the bot")
                }
                else -> {
                    wrongPins++
                    t("Неверный код.", "Wrong code.")
                }
            }
            BotOwner.Cmd.Stop -> {
                Bots.update(botId) { it.enabled = false }
                stopping = true
                Lumen.ui { BotManager.sync(ctx) }
                t("⏹ Бот остановлен. Запустить снова можно в приложении Lumen Боты.", "⏹ The bot is stopped. Start it again in the Lumen Bots app.")
            }
            BotOwner.Cmd.Stats -> {
                val logs = Bots.logs(botId)
                val (msgs, chats, errs) = BotStats.today(logs, System.currentTimeMillis())
                t("📊 Сегодня: сообщений $msgs, собеседников $chats, ошибок $errs\nВсего пользователей: ${Bots.users(botId).length()}\nЗапросов к ИИ за сутки: ${limiter.usedToday}",
                    "📊 Today: $msgs messages, $chats chats, $errs errors\nUsers in total: ${Bots.users(botId).length()}\nAI requests in 24 h: ${limiter.usedToday}")
            }
            BotOwner.Cmd.Logs -> Bots.logs(botId).takeLast(15).joinToString("\n") {
                Fmt.dateTime(it.at).substringAfter(' ') + " " + it.kind + " " + it.who + ": " + it.text.take(120)
            }.ifBlank { t("Журнал пуст.", "The journal is empty.") }
            BotOwner.Cmd.Help -> t("Команды владельца: /lumen_stats, /lumen_logs, /lumen_stop", "Owner commands: /lumen_stats, /lumen_logs, /lumen_stop")
        } ?: return
        a.send(u.chatId, reply, JSONObject())
    }

    /** What the bot code can reach: Telegram, AI, its storage, the phone. */
    private inner class Host : BotHost {
        private fun opts(s: String) = try {
            JSONObject(s.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }

        private fun api(): TgApi = api ?: throw IllegalStateException("no connection")

        private fun app(): String = Bots.get(botId)?.appLink.orEmpty()

        override fun appUrl(params: String): String {
            val l = app()
            return if (l.isBlank() || params.isBlank()) l else MiniApp.withParams(l, params)
        }

        override fun send(chatId: Long, text: String, opts: String): Long {
            val id = api().send(chatId, text, opts(opts), app())
            Bots.log(botId, BotLogEntry.OUT, chatId, "", text)
            return id
        }

        override fun edit(chatId: Long, messageId: Long, text: String, opts: String) {
            api().edit(chatId, messageId, text, opts(opts), app())
            Bots.log(botId, BotLogEntry.OUT, chatId, "", "✎ $text")
        }

        override fun answer(callbackId: String, text: String) = api().answer(callbackId, text)

        override fun photo(chatId: Long, url: String, caption: String, opts: String): Long {
            val id = api().photo(chatId, url, caption, opts(opts), app())
            Bots.log(botId, BotLogEntry.OUT, chatId, "", "[${t("фото", "photo")}] $caption")
            return id
        }

        override fun ai(prompt: String, system: String, userId: Long): String {
            if (!limiter.take(userId, System.currentTimeMillis())) {
                return t("⏳ Слишком много запросов к ИИ, попробуйте позже.", "⏳ Too many AI requests, try again later.")
            }
            val name = Bots.get(botId)?.name.orEmpty()
            val r = BotAi.reply(botId, name, prompt, system)
            Bots.log(botId, BotLogEntry.AI, userId, "", r.take(300))
            return r
        }

        override fun dbGet(key: String) = Bots.db(botId).get(key)
        override fun dbSet(key: String, json: String?) = Bots.db(botId).set(key, json)
        override fun dbKeys(prefix: String) = Bots.db(botId).keys(prefix)

        override fun notify(text: String) {
            val b = Bots.get(botId) ?: return
            BotManager.event(ctx, botId, label(b), text)
        }

        override fun log(text: String) = Bots.log(botId, BotLogEntry.SYS, 0, "", text)
        override fun http(method: String, url: String, body: String, headers: String) = BotAi.http(method, url, body, headers)
        override fun youtube(query: String, n: Int) = BotAi.youtube(query, n)

        override fun users(): String {
            val a = Bots.users(botId)
            val out = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.put(JSONObject().put("id", o.optLong("id")).put("name", o.optString("name")).put("username", o.optString("username")))
            }
            return out.toString()
        }
    }
}

/** AI and network helpers shared by the running bots and the test chat. */
object BotAi {
    class Result(val code: String, val error: String?)

    private fun provider(): Provider =
        Provider.active() ?: throw IllegalStateException(t("AI не подключён: Настройки → AI", "AI is not connected: Settings → AI"))

    private const val SYSTEM = "You are a senior Telegram bot developer. Output only working JavaScript for the described runtime."

    fun ask(task: String, prompt: String, pid: String): String {
        val p = provider()
        return Llm.complete(p, p.smartModel.ifBlank { p.fastModel }, SYSTEM, prompt, maxTokens = 8000, task = task, pid = pid).text
    }

    fun lang() = if (Settings.lang == "en") "English" else "русском"

    /** Writes the bot code from a description, then checks and repairs it (up to 2 times). */
    fun build(name: String, desc: String, pid: String, onStep: (String) -> Unit): Result {
        onStep(t("ИИ пишет код бота…", "AI is writing the bot code…"))
        val reply = ask("bot_build", Prompts.fill("bot_build", mapOf("api" to BOT_API, "name" to name, "desc" to desc, "lang" to lang())), pid)
        return repair(BotCode.extract(reply), pid, onStep)
    }

    fun edit(code: String, request: String, pid: String, onStep: (String) -> Unit): Result {
        onStep(t("ИИ меняет код…", "AI is changing the code…"))
        val reply = ask("bot_edit", Prompts.fill("bot_edit", mapOf("api" to BOT_API, "code" to code, "request" to request, "lang" to lang())), pid)
        return repair(BotCode.extract(reply), pid, onStep)
    }

    fun fix(code: String, error: String, pid: String, onStep: (String) -> Unit): Result {
        onStep(t("ИИ исправляет ошибку…", "AI is fixing the error…"))
        val reply = ask("bot_fix", Prompts.fill("bot_fix", mapOf("api" to BOT_API, "code" to code, "error" to error, "lang" to lang())), pid)
        return repair(BotCode.extract(reply), pid, onStep)
    }

    private fun repair(first: String, pid: String, onStep: (String) -> Unit): Result {
        var code = first
        var err = BotRuntime.check(code)
        var n = 0
        while (err != null && n < 2) {
            n++
            onStep(t("Проверка нашла ошибку, исправляю ($n/2)…", "The check found an error, fixing ($n/2)…"))
            val reply = ask("bot_fix", Prompts.fill("bot_fix", mapOf("api" to BOT_API, "code" to code, "error" to err, "lang" to lang())), pid)
            code = BotCode.extract(reply)
            err = BotRuntime.check(code)
        }
        return Result(code, err)
    }

    /** ai() from the bot code: the fast model, short answers. */
    fun reply(botId: String, botName: String, prompt: String, system: String): String {
        val p = provider()
        val sys = system.ifBlank {
            "Ты помощник в Telegram-боте «$botName». Отвечай кратко, по делу, на языке пользователя, без Markdown-разметки."
        }
        return Llm.complete(p, p.fastModel, sys, prompt, maxTokens = 1500, task = BotManager.TASK_REPLY, pid = "bot:$botId").text.trim()
    }

    fun http(method: String, url: String, body: String, headers: String): String {
        if (!BotNet.allowed(url)) throw IllegalArgumentException("URL is not allowed: $url")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (method.equals("POST", true)) "POST" else "GET"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "LumenBot/1.0")
            try {
                val h = JSONObject(headers.ifBlank { "{}" })
                h.keys().forEach { k -> c.setRequestProperty(k, h.optString(k)) }
            } catch (e: Exception) {
                // bad headers are ignored
            }
            if (c.requestMethod == "POST") {
                c.doOutput = true
                if (c.getRequestProperty("Content-Type") == null) {
                    c.setRequestProperty("Content-Type", if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) "application/json" else "text/plain")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.use { s ->
                val buf = ByteArray(1_000_000)
                var total = 0
                while (total < buf.size) {
                    val r = s.read(buf, total, buf.size - total)
                    if (r < 0) break
                    total += r
                }
                String(buf, 0, total)
            } ?: ""
            return JSONObject().put("status", code).put("text", text).toString()
        } finally {
            c.disconnect()
        }
    }

    fun youtube(query: String, n: Int): String {
        val out = JSONArray()
        YouTube.search(listOf(query), SearchFilters(limit = n)).take(n).forEach { v ->
            out.put(JSONObject().put("title", v.title).put("url", v.url).put("channel", v.channel).put("views", v.views).put("duration", v.duration))
        }
        return out.toString()
    }
}

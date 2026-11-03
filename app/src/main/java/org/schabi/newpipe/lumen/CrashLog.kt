/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure text helpers for the crash/freeze log (tested in CrashTextTest). */
object CrashText {
    const val LUMEN_PKG = "org.schabi.newpipe.lumen"

    /**
     * ANR trace digest: the "main" thread block plus up to [maxOther] other thread blocks
     * that run Lumen code or wait on a lock (deadlocks), each cut to [maxLines] lines.
     */
    fun anrDigest(lines: Sequence<String>, maxLines: Int = 80, maxOther: Int = 6): String {
        val blocks = mutableListOf<List<String>>()
        var cur: MutableList<String>? = null
        for (l in lines) {
            if (l.startsWith("\"")) {
                cur?.let { blocks += it }
                cur = mutableListOf(l)
            } else if (cur != null) {
                if (l.isBlank()) {
                    blocks += cur
                    cur = null
                } else {
                    cur.add(l)
                }
            }
        }
        cur?.let { blocks += it }
        val main = blocks.firstOrNull { it.first().startsWith("\"main\"") }
        val others = blocks.filter { it !== main }
            .filter { b -> b.any { it.contains(LUMEN_PKG) || it.contains("waiting to lock") || it.contains("- locked") && it.contains("lumen") } }
            .take(maxOther)
        return (listOfNotNull(main) + others).joinToString("\n\n") { b ->
            b.take(maxLines).joinToString("\n") + if (b.size > maxLines) "\n  … +${b.size - maxLines}" else ""
        }
    }

    /** Stack of one thread as text, Lumen frames marked with ▶. */
    fun stack(name: String, state: String, frames: List<String>, max: Int = 60): String = buildString {
        append('"').append(name).append("\" ").append(state).append('\n')
        frames.take(max).forEach { f -> append(if (f.contains(LUMEN_PKG)) "  ▶ at " else "    at ").append(f).append('\n') }
        if (frames.size > max) append("    … +").append(frames.size - max).append('\n')
    }

    /** Keeps the end of [s] within [max] chars, cutting at a line start. */
    fun tail(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.length - max
        val nl = s.indexOf('\n', cut)
        return "…\n" + s.substring(if (nl in cut until s.length) nl + 1 else cut)
    }

    fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }

    /** Reasons worth telling the user about. */
    fun isFailure(r: Int) = r == 4 || r == 5 || r == 6 || r == 7
}

/**
 * Freeze watchdog: pings the main thread; when it does not answer for 5 s the main stack
 * (and Lumen threads) go to lumen_logs/hang.txt, after 10 s a notification offers the log.
 * Works without the main thread, so it catches the "app isn't responding" case.
 */
object Hang {
    private const val DUMP_MS = 5_000L
    private const val NOTIFY_MS = 10_000L
    @Volatile private var answered = 0L
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            var asked = 0L
            var dumped = false
            var notified = false
            while (true) {
                try {
                    if (asked == 0L || answered >= asked) {
                        asked = SystemClock.uptimeMillis()
                        dumped = false
                        notified = false
                        main.post { answered = SystemClock.uptimeMillis() }
                    }
                    Thread.sleep(1000)
                    val wait = SystemClock.uptimeMillis() - asked
                    if (answered < asked) {
                        if (!dumped && wait >= DUMP_MS) {
                            dumped = true
                            dump(app, wait)
                        }
                        if (!notified && wait >= NOTIFY_MS) {
                            notified = true
                            dump(app, wait)
                            CrashLog.notify(app, CrashLog.tr(app, "Lumen не отвечает", "Lumen is not responding"),
                                CrashLog.tr(app, "Нажмите, чтобы отправить лог зависания", "Tap to send the freeze log"))
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (ignored: Throwable) {
                }
            }
        }, "lumen-hang").apply {
            isDaemon = true
            start()
        }
    }

    private fun dump(ctx: Context, waitMs: Long) {
        val sb = StringBuilder()
        sb.append("=== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append(" · main thread blocked ").append(waitMs / 1000).append(" s ===\n")
        val mainThread = Looper.getMainLooper().thread
        val all = runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap())
        val mainFrames = all[mainThread] ?: mainThread.stackTrace
        sb.append(CrashText.stack("main", mainThread.state.name, mainFrames.map { it.toString() }, 80))
        all.entries.filter { (th, fr) -> th !== mainThread && fr.any { it.className.startsWith(CrashText.LUMEN_PKG) } }
            .take(12).forEach { (th, fr) -> sb.append('\n').append(CrashText.stack(th.name, th.state.name, fr.map { it.toString() }, 25)) }
        sb.append('\n')
        CrashLog.appendHang(ctx, sb.toString())
    }
}

object CrashLog {
    private const val CH = "lumen_crash"
    private const val HANG_MAX = 96 * 1024

    fun dir(ctx: Context) = File(ctx.filesDir, "lumen_logs").apply { mkdirs() }

    /** Works in any process: reads the language straight from prefs, no Lumen.init needed. */
    fun tr(ctx: Context, ru: String, en: String): String =
        if (runCatching { ctx.getSharedPreferences("lumen_prefs", Context.MODE_PRIVATE).getString("lang", "ru") }.getOrNull() == "en") en else ru

    @Synchronized
    fun appendHang(ctx: Context, text: String) {
        runCatching {
            val f = File(dir(ctx), "hang.txt")
            val old = if (f.exists()) f.readText() else ""
            f.writeText(CrashText.tail(old + text, HANG_MAX))
        }
    }

    fun notify(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH, tr(ctx, "Lumen: сбои и зависания", "Lumen: crashes and freezes"), NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(ctx, 7301, Intent(ctx, CrashLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CH) else android.app.Notification.Builder(ctx)
            b.setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
            nm.notify(7301, b.build())
        }
    }

    /** On start: a crash or freeze since the last check → notification with the log (Android 11+). */
    fun checkLastExit(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        Thread({
            runCatching {
                val p = ctx.getSharedPreferences("lumen_prefs", Context.MODE_PRIVATE)
                val seen = p.getLong("exit_seen", 0)
                val am = ctx.getSystemService(ActivityManager::class.java) ?: return@runCatching
                val list = am.getHistoricalProcessExitReasons(null, 0, 5)
                val newest = list.maxOfOrNull { it.timestamp } ?: return@runCatching
                p.edit().putLong("exit_seen", newest).apply()
                if (seen == 0L) return@runCatching // first run of this feature: do not report old history
                val bad = list.firstOrNull { it.timestamp > seen && CrashText.isFailure(it.reason) } ?: return@runCatching
                val what = if (bad.reason == ApplicationExitInfo.REASON_ANR) tr(ctx, "завис", "froze") else tr(ctx, "закрылся с ошибкой", "crashed")
                L.w("exit", "last exit: ${CrashText.reasonName(bad.reason)} ${bad.description.orEmpty()}")
                notify(ctx, tr(ctx, "В прошлый раз Lumen $what", "Last time Lumen $what"), tr(ctx, "Нажмите, чтобы отправить лог", "Tap to send the log"))
            }
        }, "lumen-exit").apply { isDaemon = true }.start()
    }

    /** Android's record of recent exits, with the ANR trace digest. */
    private fun exits(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "(Android < 11: нет / not available)\n"
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return ""
        val list = runCatching { am.getHistoricalProcessExitReasons(null, 0, 8) }.getOrDefault(emptyList())
        if (list.isEmpty()) return "(нет записей / none)\n"
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        var traces = 0
        return buildString {
            for (e in list) {
                append("- ").append(fmt.format(Date(e.timestamp))).append(" · ").append(e.processName).append(" · ")
                    .append(CrashText.reasonName(e.reason)).append(" · importance ").append(e.importance)
                    .append(" · pss ").append(e.pss / 1024).append(" MB")
                if (!e.description.isNullOrBlank()) append(" · ").append(e.description)
                append('\n')
                if (e.reason == ApplicationExitInfo.REASON_ANR && traces < 2) {
                    val d = runCatching { e.traceInputStream?.bufferedReader()?.useLines { CrashText.anrDigest(it) } }.getOrNull()
                    if (!d.isNullOrBlank()) {
                        traces++
                        append("```\n").append(d).append("\n```\n")
                    }
                }
            }
        }
    }

    private fun logLines(ctx: Context): List<String> {
        val d = dir(ctx)
        return (listOf("lumen.log.2", "lumen.log.1", "lumen.log")).map { File(d, it) }.filter { it.exists() }
            .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
    }

    private fun errorsOf(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var keep = false
        for (l in lines) {
            if (!l.startsWith(" ")) keep = Regex("""^\S+ \S+ [EW]/""").containsMatchIn(l)
            if (keep) out.add(l)
        }
        return out
    }

    /** The whole crash/freeze report; reads files only, so it works in a separate process. */
    fun report(ctx: Context): String {
        val ver = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${ctx.packageName} ${pi.versionName}"
        }.getOrDefault(ctx.packageName)
        val lines = logLines(ctx)
        val hang = File(dir(ctx), "hang.txt").let { if (it.exists()) runCatching { it.readText() }.getOrDefault("") else "" }
        val text = buildString {
            append("## Lumen — лог сбоя / crash log\n\n")
            append("- time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
            append("- app: ").append(ver).append('\n')
            append("- android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("\n### Завершения по данным Android / exits\n\n").append(exits(ctx))
            append("\n### Зависания (сторож Lumen) / freezes\n\n")
            append(if (hang.isBlank()) "(нет / none)\n" else "```\n" + CrashText.tail(hang, 40_000) + "```\n")
            append("\n### Ошибки журнала / log errors\n\n```\n").append(CrashText.tail(errorsOf(lines).takeLast(250).joinToString("\n"), 40_000)).append("\n```\n")
            append("\n### Последние строки журнала / last log lines\n\n```\n").append(CrashText.tail(lines.takeLast(150).joinToString("\n"), 25_000)).append("\n```\n")
        }
        return L.redact(text)
    }
}

/**
 * "Send the crash log": a plain screen in its own process (:crashlog), so it opens even
 * while the main app is frozen. No Lumen.init, no Lumen UI kit.
 */
class CrashLogActivity : Activity() {
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.rgb(20, 20, 24) else Color.rgb(250, 250, 252)
        val fg = if (night) Color.rgb(230, 230, 235) else Color.rgb(25, 25, 30)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(pad + ins.systemWindowInsetLeft, pad + ins.systemWindowInsetTop, pad + ins.systemWindowInsetRight, pad + ins.systemWindowInsetBottom)
            ins
        }
        fun tr(ru: String, en: String) = CrashLog.tr(this, ru, en)
        root.addView(TextView(this).apply {
            text = tr("Лог сбоя", "Crash log")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
        })
        root.addView(TextView(this).apply {
            text = tr("Причины последних закрытий от Android, зависания и ошибки журнала. Ключи и токены вырезаны. Отправьте файл или текст в чат.",
                "Android's reasons for recent exits, freezes and log errors. Keys and tokens are removed. Send the file or text to the chat.")
            setTextColor(fg)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            this.text = label
            isAllCaps = false
            setOnClickListener { if (this@CrashLogActivity.text.isNotEmpty()) onClick() }
            buttons.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button(tr("Файлом", "As file")) { shareFile() }
        button(tr("Текстом", "As text")) { shareText() }
        button(tr("Копировать", "Copy")) { copy() }
        root.addView(buttons)
        val body = TextView(this).apply {
            text = tr("Собираю…", "Collecting…")
            setTextColor(fg)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Thread {
            val r = runCatching { CrashLog.report(this) }.getOrElse { "report failed: $it" }
            runOnUiThread {
                text = r
                body.text = r
            }
        }.start()
    }

    private fun shareFile() {
        runCatching {
            val dir = File(externalCacheDir ?: cacheDir, "reports").apply { mkdirs() }
            val f = File(dir, "lumen-crash-log.txt").apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Lumen crash log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Lumen crash log"))
        }.onFailure { shareText() }
    }

    private fun shareText() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Lumen crash log")
            .putExtra(Intent.EXTRA_TEXT, CrashText.tail(text, 90_000))
        runCatching { startActivity(Intent.createChooser(i, "Lumen crash log")) }
    }

    private fun copy() {
        runCatching {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lumen crash log", CrashText.tail(text, 150_000)))
            Toast.makeText(this, CrashLog.tr(this, "Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CrashLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

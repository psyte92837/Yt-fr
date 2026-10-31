/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lumen log: one rotating file on the device (lumen/logs/lumen.log, 512 KB × 3), no secrets.
 * Every screen error, network call, background task and crash goes here, so a single
 * "Developer report" can be copied or shared from Settings → Logs.
 */
object L {
    private const val MAX_BYTES = 512 * 1024L
    private const val FILES = 3
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var dir: File? = null

    /** Verbose mode also logs URLs and request details (still without keys). */
    var verbose: Boolean
        get() = try {
            Lumen.prefs.getBoolean("log_verbose", false)
        } catch (e: Exception) {
            false
        }
        set(v) = Lumen.prefs.edit().putBoolean("log_verbose", v).apply()

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "lumen_logs").also { it.mkdirs() }
        installCrashHandler()
        i("app", "start ${appVersion(ctx)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private val file: File? get() = dir?.let { File(it, "lumen.log") }

    // ---------------------------------------------------------------- redaction
    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|x-goog-api-key|x-api-key|authorization|bearer|token|password|secret)(["'=:\s]+)([^\s"'&,}]+)"""),
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""), // Google keys
        Regex("""sk-[A-Za-z0-9_\-]{16,}"""), // OpenAI / OpenRouter / DeepSeek style
        Regex("""sk-ant-[A-Za-z0-9_\-]{16,}"""),
        Regex("""gsk_[A-Za-z0-9]{16,}""") // Groq
    )

    fun redact(s: String): String {
        var out = s
        secretPatterns.forEachIndexed { i, r ->
            out = if (i == 0) r.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "***" } else r.replace(out, "***")
        }
        return out
    }

    // ---------------------------------------------------------------- writing
    fun d(tag: String, msg: String) {
        if (verbose) write("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lineSequence().take(40).joinToString("\n")
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (t != null && tag != "update") ExtractorHealth.note(t)
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg.replace('\n', ' ').take(2000))
            if (t != null) append('\n').append(stack(t).prependIndent("    "))
            append('\n')
        }
        val safe = redact(line)
        if (level == "E" || level == "W") android.util.Log.w("Lumen", safe.trim()) else android.util.Log.i("Lumen", safe.trim())
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText(safe)
            } catch (e: Exception) {
                // logging must never crash the app
            }
        }
    }

    private fun rotate(f: File) {
        val d = f.parentFile ?: return
        File(d, "lumen.log.${FILES - 1}").delete()
        for (n in FILES - 2 downTo 1) File(d, "lumen.log.$n").takeIf { it.exists() }?.renameTo(File(d, "lumen.log.${n + 1}"))
        f.renameTo(File(d, "lumen.log.1"))
    }

    /** Last [maxLines] lines across rotated files, newest last. */
    fun tail(maxLines: Int = 400, errorsOnly: Boolean = false): List<String> = synchronized(lock) {
        val d = dir ?: return emptyList()
        val files = (FILES - 1 downTo 1).map { File(d, "lumen.log.$it") } + File(d, "lumen.log")
        val lines = files.filter { it.exists() }.flatMap { it.readLines() }
        val list = if (errorsOnly) {
            // keep error/warning lines with their indented stack traces
            val out = mutableListOf<String>()
            var keep = false
            for (l in lines) {
                if (!l.startsWith(" ")) keep = Regex("""^\S+ \S+ [EW]/""").containsMatchIn(l)
                if (keep) out.add(l)
            }
            out
        } else {
            lines
        }
        list.takeLast(maxLines)
    }

    fun clear() = synchronized(lock) {
        dir?.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0

    // ---------------------------------------------------------------- crashes
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is LumenCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(LumenCrashHandler(previous))
    }

    private class LumenCrashHandler(val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(th: Thread, ex: Throwable) {
            try {
                e("crash", "uncaught in thread ${th.name}", ex)
                Lumen.prefs.edit().putLong("last_crash_at", System.currentTimeMillis()).commit()
            } catch (ignored: Throwable) {
            }
            next?.uncaughtException(th, ex)
        }
    }

    fun lastCrashAt(): Long = try {
        Lumen.prefs.getLong("last_crash_at", 0)
    } catch (e: Exception) {
        0
    }

    // ---------------------------------------------------------------- report
    fun appVersion(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${ctx.packageName} ${pi.versionName}"
    } catch (e: Exception) {
        ctx.packageName
    }

    /**
     * Plain-text report to paste into a chat or an issue: environment, settings (no keys),
     * project stats, the optional current error, and recent log lines.
     */
    fun report(ctx: Context, error: Throwable? = null, context: String = "", errorsOnly: Boolean = false, maxLines: Int = 300): String {
        val prov = try {
            Provider.active()
        } catch (e: Exception) {
            null
        }
        val projects = try {
            Store.projects()
        } catch (e: Exception) {
            emptyList()
        }
        return redact(buildString {
            append("## Lumen — отчёт для разработчика / developer report\n\n")
            append("- time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
            append("- app: ").append(appVersion(ctx)).append('\n')
            append("- android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("- locale: ").append(Locale.getDefault()).append(" · app lang: ").append(Settings.lang).append('\n')
            append("- mode: ").append(Settings.mode).append(" · monitoring: ").append(Settings.monitoringAllowed)
                .append(" · delay: ").append(Settings.requestDelayMs).append("ms · anonymize: ").append(Settings.anonymizeAuthors).append('\n')
            append("- ai: ").append(prov?.let { "${it.name} fast=${it.fastModel} smart=${it.smartModel} base=${it.baseUrl}" } ?: "none").append('\n')
            append("- projects: ").append(projects.size).append(" · videos: ").append(projects.sumOf { it.videoCount }).append('\n')
            append("- task: ").append(if (CollectState.running) "${CollectState.kind} ${CollectState.done}/${CollectState.total}" else "idle")
            if (CollectState.lastError.isNotEmpty()) append(" · last task error: ").append(CollectState.lastError)
            append('\n')
            val crash = lastCrashAt()
            if (crash > 0) append("- last crash: ").append(Fmt.dateTime(crash)).append('\n')
            append("- log size: ").append(sizeBytes() / 1024).append(" KB · verbose: ").append(verbose).append('\n')
            if (error != null || context.isNotBlank()) {
                append("\n### Ошибка / error\n\n```\n")
                if (context.isNotBlank()) append(context).append('\n')
                if (error != null) append(stack(error)).append('\n')
                append("```\n")
            }
            append("\n### Лог / log").append(if (errorsOnly) " (errors)" else "").append("\n\n```\n")
            tail(maxLines, errorsOnly).forEach { append(it).append('\n') }
            append("```\n")
        })
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Global app state for the Lumen analysis module. All data stays on the device. */
object Lumen {
    lateinit var app: Context
        private set
    lateinit var prefs: SharedPreferences
        private set

    val io: ExecutorService = Executors.newFixedThreadPool(3)
    private val main by lazy { Handler(Looper.getMainLooper()) }



    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("lumen_prefs", Context.MODE_PRIVATE)
        LumenEnv.ready = true
        L.init(app)
    }

    fun ui(block: () -> Unit) {
        main.post(block)
    }

    /** Runs [work] in background and delivers the result (or error) on the main thread. */
    fun <T> async(work: () -> T, onError: (Throwable) -> Unit = {}, onDone: (T) -> Unit) {
        io.execute {
            try {
                val r = work()
                ui { onDone(r) }
            } catch (t: Throwable) {
                L.e("async", t.message ?: t.javaClass.simpleName, t)
                ui { onError(t) }
            }
        }
    }
}

/** Settings asked in the first-run wizard and editable later. */
object Settings {
    private val p get() = Lumen.prefs

    var lang: String
        get() = p.getString("lang", "ru") ?: "ru"
        set(v) = p.edit().putString("lang", v).apply()

    var wizardDone: Boolean
        get() = p.getBoolean("wizard_done", false)
        set(v) = p.edit().putBoolean("wizard_done", v).apply()

    /** "analytics" = only analysis UI, "player" = analysis + NewPipe player. */
    var mode: String
        get() = p.getString("mode", "analytics") ?: "analytics"
        set(v) = p.edit().putString("mode", v).apply()

    var monitoringAllowed: Boolean
        get() = p.getBoolean("monitoring_allowed", false)
        set(v) = p.edit().putBoolean("monitoring_allowed", v).apply()

    var defaultMonitorHours: Int
        get() = p.getInt("monitor_hours", 24)
        set(v) = p.edit().putInt("monitor_hours", v).apply()

    var aiTranscriptionAllowed: Boolean
        get() = p.getBoolean("ai_transcription", false)
        set(v) = p.edit().putBoolean("ai_transcription", v).apply()

    var backupTreeUri: String?
        get() = p.getString("backup_tree", null)
        set(v) = p.edit().putString("backup_tree", v).apply()

    /** Delay between YouTube requests, protects from rate limiting. */
    var requestDelayMs: Int
        get() = p.getInt("request_delay", 800)
        set(v) = p.edit().putInt("request_delay", v).apply()

    /** Store comment authors as stable pseudonyms (u_xxxxxxxx) instead of names. */
    var anonymizeAuthors: Boolean
        get() = p.getBoolean("anonymize_authors", true)
        set(v) = p.edit().putBoolean("anonymize_authors", v).apply()

    /** Opt-in: use Google autocomplete for keyword research. */
    var googleSuggest: Boolean
        get() = p.getBoolean("google_suggest", false)
        set(v) = p.edit().putBoolean("google_suggest", v).apply()

    var activeProvider: String
        get() = p.getString("active_provider", "") ?: ""
        set(v) = p.edit().putString("active_provider", v).apply()

    /** Max characters per chunk for map-reduce summarisation. */
    var chunkChars: Int
        get() = p.getInt("chunk_chars", 24000)
        set(v) = p.edit().putInt("chunk_chars", v).apply()

    var priceInPerM: Double
        get() = p.getFloat("price_in", 0f).toDouble()
        set(v) = p.edit().putFloat("price_in", v.toFloat()).apply()

    var priceOutPerM: Double
        get() = p.getFloat("price_out", 0f).toDouble()
        set(v) = p.edit().putFloat("price_out", v.toFloat()).apply()
}

/** Inline RU/EN localisation, switchable inside the app without changing the system locale. */
/** Android-free flag: false in plain JVM unit tests, where no app context exists. */
object LumenEnv {
    @Volatile var ready = false
}

fun t(ru: String, en: String): String = if (LumenEnv.ready && Settings.lang == "en") en else ru

object Fmt {
    fun date(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ms))

    fun dateTime(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(ms))

    fun isoDay(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    fun parseIsoDay(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.trim())?.time ?: -1L
    } catch (e: Exception) {
        -1L
    }

    fun num(n: Long): String = when {
        n < 0 -> "—"
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 10_000 -> String.format(Locale.US, "%.0fK", n / 1e3)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }

    fun duration(sec: Long): String {
        if (sec <= 0) return "—"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun sha1(s: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return d.joinToString("") { String.format("%02x", it) }
}

fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) +
    java.lang.Integer.toString((Math.random() * 1296).toInt(), 36)

/** Id prefix of sources imported from files (TXT/CSV/JSON), not YouTube videos. */
const val LOCAL_PREFIX = "src_"

fun isLocalSource(id: String) = id.startsWith(LOCAL_PREFIX)

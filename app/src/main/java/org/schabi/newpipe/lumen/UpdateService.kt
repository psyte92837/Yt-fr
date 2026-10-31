/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import org.schabi.newpipe.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Background update check, download with checksum, install through PackageInstaller. */
object Updates {
    private const val JOB_PERIODIC = 49_001
    private const val JOB_NOW = 49_002
    private const val NOTIFY_ID = 49_003
    const val CH_UPDATE = "lumen_update"

    val currentBuild: Int get() = BuildConfig.LUMEN_BUILD
    val currentCommit: String get() = BuildConfig.LUMEN_COMMIT
    val currentExtractor: String get() = BuildConfig.LUMEN_EXTRACTOR
    val appId: String get() = BuildConfig.APPLICATION_ID

    var enabled: Boolean
        get() = Lumen.prefs.getBoolean("update_check", true)
        set(v) = Lumen.prefs.edit().putBoolean("update_check", v).apply()

    val lastCheckAt: Long get() = Lumen.prefs.getLong("update_checked_at", 0)
    val lastError: String get() = Lumen.prefs.getString("update_error", "") ?: ""
    val brokenAt: Long get() = Lumen.prefs.getLong("extractor_broken_at", 0)

    /** Last known published build (may be the one we run). */
    val latest: UpdateInfo? get() = UpdateLogic.parse(Lumen.prefs.getString("update_info", "") ?: "")
    val available: UpdateInfo? get() = latest?.takeIf { UpdateLogic.isNewer(currentBuild, appId, it) }

    /** Keeps the periodic check (every 12 h, any network) in sync with the setting. Cheap to call often. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            js.cancel(JOB_PERIODIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_PERIODIC) != null) return
        js.schedule(JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, UpdateJob::class.java))
            .setPeriodic(12 * 3_600_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build())
    }

    /** One check as soon as there is network (used when the extractor starts failing). */
    fun checkSoon(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        js.schedule(JobInfo.Builder(JOB_NOW, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(60_000L)
            .build())
    }

    fun onExtractorBroken(ctx: Context) {
        Lumen.prefs.edit().putLong("extractor_broken_at", System.currentTimeMillis()).apply()
        L.i("update", "extractor parsing errors repeat, checking for a fixed build")
        checkSoon(ctx)
    }

    private fun get(url: String, timeoutMs: Int = 20_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Lumen/${BuildConfig.VERSION_NAME} build $currentBuild")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    /** Fetches update.json. Returns the info when a newer build exists. Blocking. */
    fun check(ctx: Context, notify: Boolean): UpdateInfo? {
        val p = Lumen.prefs
        try {
            val c = get(UpdateLogic.FEED + "?t=" + System.currentTimeMillis() / 60_000)
            val code = c.responseCode
            if (code == 404) error(t("сборка ещё не опубликована", "no build published yet"))
            if (code !in 200..299) error("HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val info = UpdateLogic.parse(body) ?: error(t("неверный update.json", "invalid update.json"))
            p.edit().putString("update_info", info.toJson()).putLong("update_checked_at", System.currentTimeMillis())
                .putString("update_error", "").apply()
            val newer = UpdateLogic.isNewer(currentBuild, appId, info)
            L.i("update", "latest build ${info.build} (we run $currentBuild), extractor ${info.extractor}, newer=$newer")
            if (newer && notify && p.getInt("update_notified", 0) != info.build) {
                p.edit().putInt("update_notified", info.build).apply()
                notifyAvailable(ctx, info)
            }
            return if (newer) info else null
        } catch (e: Exception) {
            p.edit().putLong("update_checked_at", System.currentTimeMillis()).putString("update_error", e.message ?: e.javaClass.simpleName).apply()
            L.w("update", "update check failed: ${e.message}")
            throw e
        }
    }

    private fun notifyAvailable(ctx: Context, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH_UPDATE, "Lumen: обновления / updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val broken = System.currentTimeMillis() - brokenAt < 3 * 86_400_000L
        val title = if (broken) t("Исправление для YouTube готово", "A YouTube fix is ready") else t("Доступно обновление Lumen", "Lumen update available")
        val body = t("Сборка ${info.build}", "Build ${info.build}") + (if (info.extractor != currentExtractor && info.extractor.isNotBlank())
            t(", новый NewPipeExtractor ", ", new NewPipeExtractor ") + UpdateLogic.extractorLabel(info.extractor) else "") +
            (if (info.notes.isNotBlank()) "\n" + info.notes.take(300) else "")
        val open = PendingIntent.getActivity(ctx, NOTIFY_ID,
            Intent(ctx, SettingsActivity::class.java).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notify.builder(ctx, CH_UPDATE).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            // notifications permission not granted
        }
    }

    /** Downloads the APK into the cache and verifies size and SHA-256. Blocking. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "lumen-${info.build}.apk")
        val c = get(info.apk, 60_000)
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: info.size
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastPct = -1
        c.inputStream.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
        }
        if (info.size > 0 && done != info.size) {
            f.delete()
            error(t("файл скачан не полностью", "download incomplete"))
        }
        if (info.sha256.isNotBlank() && UpdateLogic.hex(md.digest()) != info.sha256) {
            f.delete()
            error(t("контрольная сумма не совпала", "checksum mismatch"))
        }
        return f
    }

    /** Android 8+: the user must allow "install unknown apps" for Lumen once. */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
        }
    }

    /** Hands the APK to the system installer; Android asks the user to confirm and checks the signature. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { inp ->
                s.openWrite("lumen.apk", 0, apk.length()).use { out ->
                    inp.copyTo(out)
                    s.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateInstallReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Lumen.init(this)
        if (!Updates.enabled) return false
        Thread {
            var retry = false
            try {
                Updates.check(this, notify = true)
            } catch (e: Exception) {
                retry = params.jobId != 49_001
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Result of the PackageInstaller session: asks the user to confirm, reports errors. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Lumen.init(ctx)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(confirm)
                } catch (e: Exception) {
                    L.e("update", "cannot open installer", e)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> L.i("update", "installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                L.w("update", "install failed: $msg")
                Lumen.prefs.edit().putString("update_error", t("Установка не удалась: ", "Install failed: ") + msg).apply()
                Lumen.ui { ctx.toast(t("Установка не удалась: ", "Install failed: ") + msg) }
            }
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.download.DownloadDialog
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.PermissionHelper
import org.schabi.newpipe.util.ThemeHelper

/** What can be done with a video from any Lumen list besides opening it. */
enum class MediaAction(val key: String) {
    BACKGROUND("bg"), AUDIO("audio"), VIDEO("video");

    companion object {
        fun of(key: String?): MediaAction? = entries.firstOrNull { it.key == key }
    }
}

/** Pure helpers for the media actions (covered by MediaTest). */
object MediaLink {
    /** Canonical watch link for a video (URL, shorts link or bare 11-char id), null for channels, playlists and junk. */
    fun playable(urlOrId: String): String? {
        val s = urlOrId.trim()
        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(s)) return "https://www.youtube.com/watch?v=$s"
        val link = YtLink.classify(s)
        return if (link.type == "video") link.url else null
    }

    /** Menu entries in display order: open (optional), background, audio, video. */
    fun menu(withOpen: Boolean): List<MediaAction?> = (if (withOpen) listOf<MediaAction?>(null) else emptyList()) +
        listOf(MediaAction.BACKGROUND, MediaAction.AUDIO, MediaAction.VIDEO)

    fun label(a: MediaAction?): String = when (a) {
        null -> t("▶ Открыть", "▶ Open")
        MediaAction.BACKGROUND -> t("🎧 Слушать в фоне", "🎧 Listen in background")
        MediaAction.AUDIO -> t("⬇ Скачать аудио", "⬇ Download audio")
        MediaAction.VIDEO -> t("⬇ Скачать видео", "⬇ Download video")
    }
}

/** Runs a media action through NewPipe's background player or download dialog. */
fun Context.runMedia(urlOrId: String, action: MediaAction) {
    val url = MediaLink.playable(urlOrId)
    if (url == null) {
        toast(t("Это не ссылка на видео", "Not a video link"))
        return
    }
    L.d("media", "${action.key} $url")
    startActivity(Intent(this, MediaActivity::class.java).putExtra("url", url).putExtra("action", action.key)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Menu of a video: open, listen in background, download audio or video. */
fun Context.videoMenu(urlOrId: String, title: String = "", onOpen: (() -> Unit)? = null) {
    if (MediaLink.playable(urlOrId) == null) {
        toast(t("Это не ссылка на видео", "Not a video link"))
        return
    }
    val items = MediaLink.menu(onOpen != null)
    val theme = if (isNight()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    AlertDialog.Builder(this, theme).apply { if (title.isNotBlank()) setTitle(title.take(80)) }
        .setItems(items.map { MediaLink.label(it) }.toTypedArray()) { _, w ->
            val a = items[w]
            if (a == null) onOpen?.invoke() else runMedia(urlOrId, a)
        }.show()
}

/** Trailing "⋮" button of a video row that opens [videoMenu]. */
fun mediaBtn(ctx: Context, urlOrId: String, title: String = ""): View = iconBtn(ctx, "⋮", Palette.MUTED) { ctx.videoMenu(urlOrId, title) }.apply {
    contentDescription = t("Слушать в фоне или скачать", "Listen in background or download")
}

/**
 * Transparent host for NewPipe's background player and download dialog (they need an AppCompat activity,
 * Lumen screens are plain activities). Loads the stream info, then plays or shows the dialog and closes.
 */
class MediaActivity : AppCompatActivity() {
    private var url = ""
    private var action: MediaAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setDayNightMode(this)
        setTheme(if (ThemeHelper.isLightThemeSelected(this)) R.style.RouterActivityThemeLight else R.style.RouterActivityThemeDark)
        // like RouterActivity: the invisible window passes touches through, dialogs live in their own windows
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.attributes = window.attributes.apply { alpha = 0f }
        super.onCreate(savedInstanceState)
        // closes the activity once the download dialog is gone
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment && fm.fragments.isEmpty()) finish()
            }
        }, false)
        if (savedInstanceState != null) return // dialog is restored by FragmentManager
        url = intent.getStringExtra("url").orEmpty()
        action = MediaAction.of(intent.getStringExtra("action"))
        val a = action
        if (url.isBlank() || a == null) {
            finish()
            return
        }
        if (a != MediaAction.BACKGROUND &&
            !PermissionHelper.checkStoragePermissions(this, PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)
        ) return // continues in onRequestPermissionsResult
        load(a)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val a = action
        if (a != null && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            PermissionHelper.checkStoragePermissions(this, PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)
        ) {
            load(a)
        } else {
            finish()
        }
    }

    private fun load(a: MediaAction) {
        Toast.makeText(this, t("Загружаю данные видео…", "Loading video info…"), Toast.LENGTH_SHORT).show()
        Thread {
            val info: Result<StreamInfo> = runCatching {
                val sid = runCatching { NewPipe.getServiceByUrl(url).serviceId }.getOrDefault(ServiceList.YouTube.serviceId)
                ExtractorHelper.getStreamInfo(sid, url, false).blockingGet()
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) done(a, info) }
        }.apply { start() }
    }

    private fun done(a: MediaAction, info: Result<StreamInfo>) {
        val si = info.getOrElse {
            L.e("media", "stream info $url", it)
            Toast.makeText(this, t("Не удалось загрузить видео: ", "Could not load the video: ") + (it.message ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            if (a == MediaAction.BACKGROUND) {
                NavigationHelper.playOnBackgroundPlayer(applicationContext, SinglePlayQueue(si), true)
                finish()
                return
            }
            // preselects the audio or video tab of the download dialog
            val type = if (a == MediaAction.AUDIO) R.string.last_download_type_audio_key else R.string.last_download_type_video_key
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(getString(R.string.last_used_download_type), getString(type)).apply()
            DownloadDialog(this, si).show(supportFragmentManager, "downloadDialog")
        } catch (e: Exception) {
            L.e("media", "${a.key} $url", e)
            Toast.makeText(this, t("Ошибка: ", "Error: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

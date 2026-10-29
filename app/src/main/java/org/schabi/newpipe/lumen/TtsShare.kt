/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent
import android.os.Bundle

/**
 * Entry points for text from other apps ("Share" and the text-selection menu):
 * [TtsShareListen] opens the reader and starts at once, [TtsShareSave] makes an MP3 in the background with no screen.
 */
abstract class TtsShareBase(private val save: Boolean) : LumenBase() {
    private var pending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = (intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: "")
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (stream != null) {
            Lumen.async({ TtsText.load(this, stream, 30 * 1024 * 1024).second }, { e ->
                toast(e.message ?: e.javaClass.simpleName)
                finish()
            }) { go(it) }
        } else {
            go(text)
        }
    }

    private fun go(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            toast(t("Нет текста для озвучки", "No text to speak"))
            finish()
            return
        }
        if (!save) {
            TtsActivity.open(this, text, autoStart = TtsMode.LISTEN)
            finish()
            return
        }
        if (TtsState.synth || TtsState.saving) {
            toast(t("Озвучка уже идёт — дождитесь конца", "A job is already running — wait for it"))
            finish()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // ask once so the progress notification can show; the job starts either way
            pending = text
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
            return
        }
        startSave(text)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pending?.let { startSave(it) }
        pending = null
    }

    private fun startSave(text: String) {
        TtsState.error = null
        TtsService.startJob(this, text, TtsService.options(TtsService.voiceFor(text)), TtsMode.SAVE)
        toast(t("Озвучка запущена в фоне: прогресс в уведомлении, файл будет в Музыка/Lumen", "Started in the background: progress is in the notification, the file will be in Music/Lumen"))
        finish()
    }
}

class TtsShareListen : TtsShareBase(false)

class TtsShareSave : TtsShareBase(true)

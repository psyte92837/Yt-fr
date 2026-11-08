/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Interface texts without a hand translation are translated on the phone by ML Kit (English → language).
 * The model (~30 MB) is downloaded once; the texts never leave the device. Found by I18n by name.
 */
object MlKitTranslator : I18n.Machine {
    override fun supports(code: String): Boolean = TranslateLanguage.fromLanguageTag(code) != null

    override fun translate(code: String, texts: List<String>, progress: (Int) -> Unit): List<String?> {
        val target = TranslateLanguage.fromLanguageTag(code) ?: return texts.map { null }
        val tr = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(target).build())
        try {
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
            return texts.mapIndexed { i, text ->
                progress(i)
                try {
                    // spaces around the text are kept, the placeholders {0} are checked by the caller
                    val core = text.trim()
                    if (core.isEmpty() || core.none { it.isLetter() }) null
                    else text.substringBefore(core) + Tasks.await(tr.translate(core), 30, TimeUnit.SECONDS) + text.substringAfterLast(core)
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            tr.close()
        }
    }
}

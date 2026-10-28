/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Automatic backup that survives uninstalling the app: a ZIP of all projects (no API keys) kept outside the
 * app's private storage — in the backup folder chosen in settings, otherwise in Downloads/Lumen (Android 10+).
 * After a reinstall the user picks that file once (system file picker, no permissions) to restore everything.
 */
object AutoBackup {
    const val FILE_NAME = "lumen-autobackup.zip"
    const val EVERY_MS = 3 * 3_600_000L

    @Volatile private var running = false

    var enabled: Boolean
        get() = Lumen.prefs.getBoolean("autobackup", true)
        set(v) = Lumen.prefs.edit().putBoolean("autobackup", v).apply()

    var lastAt: Long
        get() = Lumen.prefs.getLong("autobackup_at", 0)
        private set(v) = Lumen.prefs.edit().putLong("autobackup_at", v).apply()

    /** Where the last automatic backup went, shown in settings. */
    var lastPlace: String
        get() = Lumen.prefs.getString("autobackup_place", "") ?: ""
        private set(v) = Lumen.prefs.edit().putString("autobackup_place", v).apply()

    private var downloadsUri: String?
        get() = Lumen.prefs.getString("autobackup_uri", null)
        set(v) = Lumen.prefs.edit().putString("autobackup_uri", v).apply()

    /** Pure: a backup is due when enabled, there is data, and the last one is older than [every]. */
    fun due(enabled: Boolean, hasData: Boolean, lastAt: Long, now: Long, every: Long = EVERY_MS): Boolean =
        enabled && hasData && (lastAt <= 0 || now - lastAt >= every || now < lastAt)

    /** Runs a backup in the background when one is due; [force] ignores the interval. */
    fun maybe(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val hasData = try { Store.projects().isNotEmpty() } catch (e: Exception) { false }
        if (!force && !due(enabled, hasData, lastAt, System.currentTimeMillis())) return
        if (!hasData || running) return
        running = true
        Lumen.io.execute {
            try {
                runNow(app)
            } catch (e: Throwable) {
                L.w("backup", "auto backup failed: ${e.message}", e)
            } finally {
                running = false
            }
        }
    }

    /** Writes the ZIP to a temp file first, then copies it over the previous backup (a crash never leaves half a ZIP). */
    fun runNow(ctx: Context) {
        val tmp = File(ctx.cacheDir, "autobackup.tmp")
        tmp.outputStream().use { Store.backup(it) }
        try {
            val place = writeToTree(ctx, tmp) ?: writeToDownloads(ctx, tmp)
            if (place != null) {
                lastAt = System.currentTimeMillis()
                lastPlace = place
                L.i("backup", "auto backup → $place (${tmp.length() / 1024} KB)")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun writeToTree(ctx: Context, src: File): String? {
        val tree = Settings.backupTreeUri?.let { Uri.parse(it) } ?: return null
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val existing = findChild(ctx, tree, FILE_NAME)
            val doc = existing ?: DocumentsContract.createDocument(ctx.contentResolver, parent, "application/zip", FILE_NAME)
                ?: return null
            ctx.contentResolver.openOutputStream(doc, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return null
            (tree.lastPathSegment ?: "").substringAfter(':') + "/" + FILE_NAME
        } catch (e: Exception) {
            L.w("backup", "backup folder not writable: ${e.message}")
            null
        }
    }

    private fun findChild(ctx: Context, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    private fun writeToDownloads(ctx: Context, src: File): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val place = Environment.DIRECTORY_DOWNLOADS + "/Lumen/" + FILE_NAME
        // overwrite our previous file; if it was deleted or belongs to an older install, create a new one
        downloadsUri?.let { Uri.parse(it) }?.let { u ->
            try {
                val ok = ctx.contentResolver.openOutputStream(u, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
                if (ok) return place
            } catch (e: Exception) {
                // fall through to a new file
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lumen")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return null
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        downloadsUri = uri.toString()
        return place
    }

    /** System file picker for a backup ZIP (no storage permission needed). */
    fun pickIntent(): android.content.Intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))

    /** Restores a backup ZIP picked by the user and re-schedules monitoring. */
    fun restore(ctx: Context, uri: Uri) {
        ctx.contentResolver.openInputStream(uri)?.use { Store.restore(it) } ?: throw IllegalStateException("cannot open the file")
        Monitoring.reapplyAll(ctx)
    }
}

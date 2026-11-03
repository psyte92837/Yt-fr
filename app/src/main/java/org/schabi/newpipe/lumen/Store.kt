/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local file storage: filesDir/lumen/projects/<id>/{project.json, videos.json,
 * comments/<vid>.json, transcripts/<vid>.txt, ai/<key>.json}.
 * API keys are never stored here (see [KeyVault]), so backups and exports never contain them.
 */
object Store {
    private val lock = Any()
    val root: File get() = File(Lumen.app.filesDir, "lumen").also { it.mkdirs() }
    private val projectsDir get() = File(root, "projects").also { it.mkdirs() }

    private fun dir(projectId: String) = File(projectsDir, projectId).also { it.mkdirs() }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    // ---- projects ----
    fun projects(): List<Project> = synchronized(lock) {
        projectsDir.listFiles()?.mapNotNull { d ->
            val f = File(d, "project.json")
            if (!f.exists()) null else try {
                Project.from(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
        }?.sortedByDescending { maxOf(it.lastRunAt, it.createdAt) } ?: emptyList()
    }

    fun project(id: String): Project? = synchronized(lock) {
        val f = File(dir(id), "project.json")
        if (!f.exists()) null else Project.from(JSONObject(f.readText()))
    }

    fun save(p: Project) = synchronized(lock) {
        writeAtomic(File(dir(p.id), "project.json"), p.toJson().toString(1))
    }

    fun delete(id: String) = synchronized(lock) {
        File(projectsDir, id).deleteRecursively()
    }

    // ---- trash: deleted projects wait TrashLogic.KEEP_DAYS days ----
    private val trashDir get() = File(root, "trash").also { it.mkdirs() }

    /** Moves the project to the trash (undoable); false if it is not there. */
    fun trash(id: String): Boolean = synchronized(lock) {
        val src = File(projectsDir, id)
        if (!src.exists()) return false
        val dst = File(trashDir, id)
        dst.deleteRecursively()
        val ok = src.renameTo(dst) || (src.copyRecursively(dst, overwrite = true) && src.deleteRecursively())
        if (ok) File(dst, ".deleted_at").writeText(System.currentTimeMillis().toString())
        ok
    }

    /** Brings a trashed project back. */
    fun restore(id: String): Boolean = synchronized(lock) {
        val src = File(trashDir, id)
        if (!src.exists()) return false
        File(src, ".deleted_at").delete()
        val dst = File(projectsDir, id)
        if (dst.exists()) return false
        src.renameTo(dst) || (src.copyRecursively(dst, overwrite = true) && src.deleteRecursively())
    }

    /** Trashed projects with the time they were deleted, newest first. */
    fun trashed(): List<Pair<Project, Long>> = synchronized(lock) {
        trashDir.listFiles()?.mapNotNull { d ->
            val p = runCatching { Project.from(JSONObject(File(d, "project.json").readText())) }.getOrNull() ?: return@mapNotNull null
            p to (runCatching { File(d, ".deleted_at").readText().trim().toLong() }.getOrNull() ?: d.lastModified())
        }?.sortedByDescending { it.second } ?: emptyList()
    }

    fun purge(id: String) = synchronized(lock) { File(trashDir, id).deleteRecursively() }

    /** Removes for good what waited long enough; returns how many. */
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int =
        trashed().count { (p, at) -> TrashLogic.expired(at, now) && purge(p.id) }

    fun duplicate(id: String): Project? = synchronized(lock) {
        val src = project(id) ?: return null
        val copy = Project.from(src.toJson()).copy(id = newId(), createdAt = System.currentTimeMillis())
        copy.name = src.name + " (copy)"
        File(projectsDir, id).copyRecursively(dir(copy.id), overwrite = true)
        save(copy)
        copy
    }

    // ---- videos ----
    fun videos(projectId: String): LinkedHashMap<String, Video> = synchronized(lock) {
        val out = LinkedHashMap<String, Video>()
        val f = File(dir(projectId), "videos.json")
        if (!f.exists()) return out
        val a = JSONArray(f.readText())
        for (i in 0 until a.length()) {
            val v = Video.from(a.getJSONObject(i))
            out[v.id] = v
        }
        out
    }

    fun saveVideos(projectId: String, videos: Collection<Video>) = synchronized(lock) {
        val a = JSONArray()
        videos.forEach { a.put(it.toJson()) }
        writeAtomic(File(dir(projectId), "videos.json"), a.toString())
        project(projectId)?.let {
            it.videoCount = videos.size
            save(it)
        }
    }

    /** Updates a single video under the lock (read-modify-write). */
    fun updateVideo(projectId: String, id: String, change: (Video) -> Unit) = synchronized(lock) {
        val all = videos(projectId)
        all[id]?.let(change)
        saveVideos(projectId, all.values)
    }

    /** Adds videos that are not yet present; returns the number of new ones. */
    fun mergeVideos(projectId: String, found: List<Video>): Int = synchronized(lock) {
        val all = videos(projectId)
        var added = 0
        for (v in found) {
            val old = all[v.id]
            if (old == null) {
                all[v.id] = v
                added++
            } else {
                // refresh volatile numbers, keep user state
                if (v.views >= 0) old.views = v.views
                if (old.uploadMs <= 0) old.uploadMs = v.uploadMs
                if (old.thumb.isEmpty()) old.thumb = v.thumb
            }
        }
        saveVideos(projectId, all.values)
        added
    }

    // ---- generic project files ----
    fun readFile(projectId: String, name: String): String? = synchronized(lock) {
        val f = File(dir(projectId), name)
        if (f.exists()) f.readText() else null
    }

    fun writeFile(projectId: String, name: String, text: String) = synchronized(lock) {
        writeAtomic(File(dir(projectId), name), text)
    }

    /** Every file of the project as a path relative to its folder ("ai/x.json"). */
    fun listFiles(projectId: String): List<String> = synchronized(lock) {
        val d = dir(projectId)
        d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path.replace('\\', '/') }.toList()
    }

    // ---- comments / transcripts ----
    fun saveComments(projectId: String, videoId: String, list: List<Comment>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        writeAtomic(File(dir(projectId), "comments/$videoId.json"), a.toString())
    }

    fun comments(projectId: String, videoId: String): List<Comment> {
        val f = File(dir(projectId), "comments/$videoId.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { Comment.from(a.getJSONObject(it)) }
    }

    fun saveTranscript(projectId: String, videoId: String, text: String) =
        writeAtomic(File(dir(projectId), "transcripts/$videoId.txt"), text)

    fun transcript(projectId: String, videoId: String): String? {
        val f = File(dir(projectId), "transcripts/$videoId.txt")
        return if (f.exists()) f.readText() else null
    }

    // ---- AI results cache ----
    fun ai(projectId: String, key: String): AiResult? {
        val f = File(dir(projectId), "ai/$key.json")
        return if (f.exists()) AiResult.from(JSONObject(f.readText())) else null
    }

    fun saveAi(projectId: String, r: AiResult) =
        writeAtomic(File(dir(projectId), "ai/${r.key}.json"), r.toJson().toString())

    fun aiAll(projectId: String): List<AiResult> =
        File(dir(projectId), "ai").listFiles()?.mapNotNull {
            try {
                AiResult.from(JSONObject(it.readText()))
            } catch (e: Exception) {
                null
            }
        }?.sortedByDescending { it.at } ?: emptyList()

    fun latestAi(projectId: String, kind: String, target: String): AiResult? =
        aiAll(projectId).firstOrNull { it.kind == kind && it.target == target }

    // ---- full project JSON (export) ----
    fun projectJson(projectId: String): JSONObject {
        val p = project(projectId) ?: return JSONObject()
        val vids = videos(projectId)
        val va = JSONArray()
        for (v in vids.values) {
            val o = v.toJson()
            transcript(projectId, v.id)?.let { o.put("transcript", it) }
            val ca = JSONArray()
            comments(projectId, v.id).forEach { ca.put(it.toJson()) }
            o.put("comments", ca)
            va.put(o)
        }
        val aa = JSONArray()
        aiAll(projectId).forEach { aa.put(it.toJson()) }
        return JSONObject().put("project", p.toJson()).put("videos", va).put("ai", aa)
            .put("exportedAt", System.currentTimeMillis()).put("app", "Lumen")
    }

    // ---- backup ----
    /**
     * Zips everything under [root]. The store lock is held only to list files and to read each small file
     * (project JSON and the like), never for the whole archive: a backup of hundreds of MB used to hold it for
     * minutes, and any screen touching [Store] (e.g. the home menu → [trashed]) froze into an ANR.
     */
    fun backup(out: OutputStream) {
        val base = root
        val files = synchronized(lock) {
            base.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(base).invariantSeparatorsPath }
                .filterNot { backupSkip(it.second) }.toList()
        }
        val zip = ZipOutputStream(out)
        try {
            for ((f, rel) in files) backupEntry(zip, f, rel)
        } finally {
            zip.close()
        }
    }

    private fun backupEntry(zip: ZipOutputStream, f: File, rel: String) {
        val small = if (f.length() <= BACKUP_LOCKED_MAX) {
            synchronized(lock) { runCatching { f.readBytes() }.getOrNull() } ?: return // deleted meanwhile
        } else null
        val input = if (small == null) runCatching { f.inputStream() }.getOrNull() ?: return else null
        zip.setLevel(if (backupStored(rel)) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(rel))
        if (small != null) zip.write(small) else input?.use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** Files up to this size are read under the lock (consistent JSON); bigger ones are media, streamed without it. */
    private const val BACKUP_LOCKED_MAX = 2L * 1024 * 1024

    /** Pure: caches that rebuild themselves are left out of backups (path relative to [root], '/' separated). */
    fun backupSkip(rel: String): Boolean =
        rel.startsWith("watchnext/") || rel.endsWith(".tmp") ||
            Regex("""(^|/)anim/[^/]+/r_[^/]+/""").containsMatchIn(rel)

    /** Pure: already compressed media is stored as is — deflating it only burns CPU. */
    fun backupStored(rel: String): Boolean =
        rel.substringAfterLast('.', "").lowercase() in setOf(
            "mp4", "webm", "m4a", "mp3", "aac", "ogg", "opus", "jpg", "jpeg", "png", "webp", "gif", "zip", "gz", "lottie"
        )

    fun restore(input: InputStream) = synchronized(lock) {
        val base = root.canonicalFile
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val target = File(base, e.name).canonicalFile
                // zip-slip protection
                if (target.path.startsWith(base.path + File.separator) && !e.isDirectory) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                e = zip.nextEntry
            }
        }
    }
}

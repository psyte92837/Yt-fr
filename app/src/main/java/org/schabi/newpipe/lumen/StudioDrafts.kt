/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import java.io.File

/** A put-aside Studio work: its script, topic and files live in drafts/<task>/<id>/. */
data class SDraft(val id: String, val task: String, val title: String, val at: Long, val script: String, val topic: String, val bytes: Long = 0)

/**
 * Drafts of the Studio tasks: opening a task starts a clean one, the previous work is moved (not copied) here
 * and can be opened again or deleted. Plain file logic, no Android.
 */
object StudioDrafts {
    /** Files of one task's work inside its folder (Shorts shares the folder with music, caches and templates). */
    val FILES = listOf("take.wav", "source.mp4", "clips.json", "clips", "analysis.json", "voice.wav", "broll", "prompter.json")
    private const val META = "meta.json"

    /** Name for the list: the topic, else the first line of the script without a [visual] mark. */
    fun title(topic: String, script: String): String {
        val tp = topic.trim()
        if (tp.isNotEmpty()) return tp.take(60)
        val line = script.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line.replace(Regex("^\\[[^\\]]*]\\s*"), "").replace(Regex("^@[^:]{1,30}:\\s*"), "").trim().take(60)
    }

    private fun used(f: File): Boolean = f.exists() && (f.isFile && f.length() > 0 || f.isDirectory && !f.list().isNullOrEmpty())

    /** Anything worth keeping in the task: text or a file of its work. */
    fun hasWork(script: String, topic: String, tdir: File): Boolean =
        script.isNotBlank() || topic.isNotBlank() || FILES.any { used(File(tdir, it)) }

    fun toJson(d: SDraft): String = JSONObject().put("id", d.id).put("task", d.task).put("title", d.title).put("at", d.at)
        .put("script", d.script).put("topic", d.topic).toString()

    fun fromJson(s: String): SDraft? = runCatching {
        val o = JSONObject(s)
        val id = o.optString("id").trim()
        if (id.isEmpty()) null
        else SDraft(id, o.optString("task"), o.optString("title"), o.optLong("at"), o.optString("script"), o.optString("topic"))
    }.getOrNull()

    private fun move(from: File, to: File) {
        if (!from.exists()) return
        to.parentFile?.mkdirs()
        if (to.exists()) to.deleteRecursively()
        if (from.renameTo(to)) return
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }

    private fun size(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0 else f.length()

    /** Moves the current work of [task] out of [tdir] into a new draft; null when there is nothing to keep. */
    fun stash(root: File, task: String, tdir: File, script: String, topic: String, now: Long): SDraft? {
        if (!hasWork(script, topic, tdir)) return null
        var id = now.toString(36)
        while (File(root, "$task/$id").exists()) id += "x"
        val d = File(root, "$task/$id").apply { mkdirs() }
        FILES.forEach { name -> File(tdir, name).takeIf { used(it) }?.let { move(it, File(d, name)) } }
        val draft = SDraft(id, task, title(topic, script), now, script, topic)
        File(d, META).writeText(toJson(draft))
        return draft
    }

    /** Drafts of [task], newest first. */
    fun list(root: File, task: String): List<SDraft> =
        File(root, task).listFiles()?.mapNotNull { dir ->
            val m = File(dir, META)
            if (!m.exists()) return@mapNotNull null
            fromJson(m.readText())?.copy(id = dir.name, bytes = size(dir) - m.length())
        }.orEmpty().sortedByDescending { it.at }

    fun count(root: File, task: String): Int = File(root, task).listFiles()?.count { File(it, META).exists() } ?: 0

    /** Puts the draft's files back into [tdir] (replacing what is there) and removes the draft. */
    fun restore(root: File, d: SDraft, tdir: File) {
        val dir = File(root, "${d.task}/${d.id}")
        FILES.forEach { name ->
            val target = File(tdir, name)
            val src = File(dir, name)
            if (src.exists()) move(src, target) else if (target.exists()) target.deleteRecursively()
        }
        dir.deleteRecursively()
    }

    fun delete(root: File, d: SDraft) {
        File(root, "${d.task}/${d.id}").deleteRecursively()
    }

    fun deleteAll(root: File, task: String) {
        File(root, task).deleteRecursively()
    }

    /** "12.4 MB" style size for the list. */
    fun mb(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }
}

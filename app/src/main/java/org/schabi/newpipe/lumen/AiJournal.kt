/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.io.File

/**
 * Journal of every AI call in every feature: the full answer text, the raw provider body,
 * the prompt, tokens, status (ok / truncated / error). Kept on the device, last [KEEP] calls.
 * API keys are never part of requests' text, and everything passes through [L.redact].
 */
object AiJournal {
    private const val KEEP = 400
    private val lock = Any()
    private val dir: File? get() = if (LumenEnv.ready) File(Store.root, "ai_journal").also { it.mkdirs() } else null

    data class Entry(
        val id: String,
        val at: Long,
        val pid: String,
        val task: String,
        val provider: String,
        val model: String,
        val status: String, // ok | truncated | error | blocked (not sent)
        val error: String,
        val text: String,
        val raw: String,
        val system: String,
        val prompt: String,
        val inTokens: Int,
        val outTokens: Int,
        val ms: Long,
        val fileUri: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("pid", pid).put("task", task)
            .put("provider", provider).put("model", model).put("status", status).put("error", error)
            .put("text", text).put("raw", raw).put("system", system).put("prompt", prompt)
            .put("in", inTokens).put("out", outTokens).put("ms", ms).put("file", fileUri)

        companion object {
            fun from(o: JSONObject) = Entry(
                o.optString("id"), o.optLong("at"), o.optString("pid"), o.optString("task"), o.optString("provider"),
                o.optString("model"), o.optString("status"), o.optString("error"), o.optString("text"), o.optString("raw"),
                o.optString("system"), o.optString("prompt"), o.optInt("in"), o.optInt("out"), o.optLong("ms"), o.optString("file")
            )
        }
    }

    fun record(
        pid: String, task: String, provider: String, model: String, system: String, prompt: String,
        text: String, raw: String, inTokens: Int, outTokens: Int, status: String, error: String?, ms: Long,
        fileUri: String? = null
    ) {
        val d = dir ?: return
        try {
            val at = System.currentTimeMillis()
            val e = Entry(
                at.toString() + "_" + newId(), at, pid, task.ifBlank { "ai" }, provider, model, status, L.redact(error ?: ""),
                L.redact(text), L.redact(raw.take(200_000)), L.redact(system.take(4000)), L.redact(prompt.take(60_000)),
                inTokens, outTokens, ms, fileUri.orEmpty()
            )
            synchronized(lock) {
                File(d, e.id + ".json").writeText(e.toJson().toString())
                val files = d.listFiles()?.sortedBy { it.name } ?: return
                if (files.size > KEEP) files.take(files.size - KEEP).forEach { it.delete() }
            }
            if (status != "blocked") AiStats.record(AiStats.Rec(at, pid, e.task, provider, model, status, inTokens, outTokens, ms))
            L.i("ai", "$task ${provider}/$model → $status in=$inTokens out=$outTokens ${ms}ms" + (error?.let { " · ${it.take(200)}" } ?: ""))
        } catch (ex: Exception) {
            // journal must never break an AI call
        }
    }

    fun list(pid: String? = null): List<Entry> = synchronized(lock) {
        val d = dir ?: return emptyList()
        (d.listFiles()?.sortedByDescending { it.name } ?: emptyList()).mapNotNull {
            try {
                Entry.from(JSONObject(it.readText()))
            } catch (e: Exception) {
                null
            }
        }.filter { pid == null || it.pid == pid }
    }

    fun get(id: String): Entry? = synchronized(lock) {
        val f = File(dir ?: return null, "$id.json")
        if (f.exists()) Entry.from(JSONObject(f.readText())) else null
    }

    fun latest(): Entry? = list().firstOrNull()

    fun clear(pid: String? = null): Unit = synchronized(lock) {
        val d = dir ?: return
        if (pid == null) {
            d.listFiles()?.forEach { it.delete() }
        } else {
            list(pid).forEach { File(d, it.id + ".json").delete() }
        }
    }

    fun statusLabel(s: String) = when (s) {
        "ok" -> t("✓ ответ", "✓ answer")
        "truncated" -> t("✂ обрезан лимитом", "✂ cut by limit")
        "blocked" -> t("⊘ не отправлено", "⊘ not sent")
        else -> t("✗ ошибка", "✗ error")
    }

    fun full(e: Entry): String = buildString {
        append(Fmt.dateTime(e.at)).append(" · ").append(e.task).append(" · ").append(e.provider).append('/').append(e.model).append('\n')
        append(statusLabel(e.status)).append(" · in ").append(e.inTokens).append(" / out ").append(e.outTokens).append(" · ").append(e.ms).append(" ms\n")
        if (e.error.isNotBlank()) append(t("\nОшибка: ", "\nError: ")).append(e.error).append('\n')
        append(t("\n=== Ответ модели ===\n", "\n=== Model answer ===\n")).append(e.text.ifBlank { t("(пусто)", "(empty)") }).append('\n')
        if (e.raw.isNotBlank() && e.raw != e.text) append(t("\n=== Сырой ответ провайдера ===\n", "\n=== Raw provider response ===\n")).append(e.raw.take(30_000)).append('\n')
        append(if (e.status == "blocked") t("\n=== Запрос (не отправлен) ===\n", "\n=== Prompt (not sent) ===\n") else t("\n=== Отправленный запрос ===\n", "\n=== Sent prompt ===\n"))
            .append(e.prompt.take(20_000)).append('\n')
        if (e.fileUri.isNotBlank()) append(t("\n=== Видео ===\n", "\n=== Video ===\n")).append(e.fileUri).append('\n')
        if (e.system.isNotBlank()) append(t("\n=== Системная инструкция ===\n", "\n=== System instruction ===\n")).append(e.system).append('\n')
    }
}

/** Screen with every AI answer (optionally only for one project). */
class AiJournalActivity : LumenBase() {
    private var pid: String? = null
    private var status = "all"
    private var task = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid")
        intent.getStringExtra("id")?.let { id -> AiJournal.get(id)?.let { open(it) } }
        render()
    }

    private fun open(e: AiJournal.Entry) = showText(e.task + " · " + AiJournal.statusLabel(e.status), AiJournal.full(e))

    private fun render() {
        val all = AiJournal.list(pid)
        val c = Col(this)
        c.muted(t("Все ответы AI: сохраняются даже обрезанные и с ошибкой. Хранятся последние 400 вызовов, только на устройстве.",
            "All AI answers, including cut-off and failed ones. The last 400 calls are kept, on this device only."))
        c.chips(listOf("all" to t("все", "all"), "ok" to "✓", "truncated" to "✂", "error" to "✗", "blocked" to "⊘").map { (k, l) ->
            chip(this, "$l ${if (k == "all") all.size else all.count { it.status == k }}", status == k) {
                status = k
                render()
            }
        })
        val tasks = all.map { it.task }.distinct().sorted()
        if (tasks.size > 1) c.chips(tasks.map { k ->
            chip(this, k, task == k) {
                task = if (task == k) "" else k
                render()
            }
        })
        val list = all.filter { (status == "all" || it.status == status) && (task.isEmpty() || it.task == task) }
        if (list.isEmpty()) c.muted("—")
        list.take(150).forEach { e ->
            val card = c.card {
                add(text(ctx, "${e.task} · ${AiJournal.statusLabel(e.status)}", 14f, bold = true,
                    color = when (e.status) { "ok" -> Palette.ON_SURFACE; "truncated", "blocked" -> Palette.WARN; else -> Palette.BAD }), 0)
                muted(Fmt.dateTime(e.at) + " · " + e.model + " · " + e.inTokens + "/" + e.outTokens + " · " + e.ms + " ms")
                val preview = e.text.ifBlank { e.error.ifBlank { e.raw } }
                add(text(ctx, preview.take(240).replace('\n', ' ') + if (preview.length > 240) "…" else "", 13f), 4)
            }
            card.setOnClickListener { open(e) }
        }
        val actions = listOf(
            "⧉" to {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val text = list.take(10).joinToString("\n\n----------\n\n") { AiJournal.full(it).take(8000) }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Lumen AI", text))
                toast(t("Скопированы последние ${minOf(10, list.size)} ответов", "Copied the last ${minOf(10, list.size)} answers"))
            },
            "🗑" to {
                confirm(t("Очистить журнал?", "Clear the journal?"), "") {
                    AiJournal.clear(pid)
                    render()
                }
            }
        )
        page(t("Ответы AI", "AI answers"), c.root, actions = actions)
    }

    companion object {
        fun open(ctx: android.content.Context, pid: String? = null, id: String? = null) {
            ctx.startActivity(Intent(ctx, AiJournalActivity::class.java).apply {
                pid?.let { putExtra("pid", it) }
                id?.let { putExtra("id", it) }
            })
        }
    }
}

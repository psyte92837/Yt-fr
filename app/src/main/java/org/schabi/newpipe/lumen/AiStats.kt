/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import java.io.File

/**
 * Lightweight usage log of every AI call (no texts): kept forever, unlike the 400-call [AiJournal].
 * Aggregation is pure and unit-tested; the log lives on the device only.
 */
object AiStats {
    data class Rec(
        val at: Long, val pid: String, val task: String, val provider: String, val model: String,
        val status: String, val inTokens: Int, val outTokens: Int, val ms: Long
    )

    data class Row(
        val key: String, val calls: Int, val ok: Int, val truncated: Int, val errors: Int,
        val inTokens: Long, val outTokens: Long, val ms: Long
    ) {
        val tokens get() = inTokens + outTokens
        val avgMs get() = if (calls == 0) 0L else ms / calls
    }

    enum class Group { PROJECT, PROVIDER, MODEL, TASK, DAY }

    private val lock = Any()
    private val file: File? get() = if (LumenEnv.ready) File(Store.root, "ai_stats.jsonl") else null

    fun record(r: Rec) {
        val f = file ?: return
        try {
            synchronized(lock) {
                if (!f.exists()) importJournal(f)
                f.appendText(toJson(r).toString() + "\n")
            }
        } catch (e: Exception) {
            // statistics must never break an AI call
        }
    }

    /** First start: seed the log from the journal that already exists. */
    private fun importJournal(f: File) {
        f.writeText("")
        AiJournal.list().reversed().forEach {
            f.appendText(toJson(Rec(it.at, it.pid, it.task, it.provider, it.model, it.status, it.inTokens, it.outTokens, it.ms)).toString() + "\n")
        }
    }

    fun all(): List<Rec> = synchronized(lock) {
        val f = file ?: return emptyList()
        if (!f.exists()) importJournal(f)
        f.readLines().mapNotNull { parse(it) }
    }

    fun clear() = synchronized(lock) { file?.delete(); Unit }

    fun toJson(r: Rec): JSONObject = JSONObject().put("at", r.at).put("pid", r.pid).put("task", r.task).put("pr", r.provider)
        .put("m", r.model).put("s", r.status).put("in", r.inTokens).put("out", r.outTokens).put("ms", r.ms)

    fun parse(line: String): Rec? = try {
        val o = JSONObject(line)
        Rec(o.optLong("at"), o.optString("pid"), o.optString("task"), o.optString("pr"), o.optString("m"),
            o.optString("s"), o.optInt("in"), o.optInt("out"), o.optLong("ms"))
    } catch (e: Exception) {
        null
    }

    /** [sinceMs] ≤ 0 means the whole history. Rows come sorted by tokens (days: newest first). */
    fun aggregate(recs: List<Rec>, group: Group, sinceMs: Long = 0, name: (String) -> String = { it }, day: (Long) -> String = { Fmt.isoDay(it) }): List<Row> {
        fun keyOf(r: Rec) = when (group) {
            Group.PROJECT -> if (r.pid.isBlank()) "—" else name(r.pid)
            Group.PROVIDER -> r.provider.ifBlank { "—" }
            Group.MODEL -> r.provider + "/" + r.model
            Group.TASK -> r.task.ifBlank { "ai" }
            Group.DAY -> day(r.at)
        }
        val rows = recs.filter { sinceMs <= 0 || it.at >= sinceMs }.groupBy(::keyOf).map { (k, l) ->
            Row(k, l.size, l.count { it.status == "ok" }, l.count { it.status == "truncated" }, l.count { it.status == "error" },
                l.sumOf { it.inTokens.toLong() }, l.sumOf { it.outTokens.toLong() }, l.sumOf { it.ms })
        }
        return if (group == Group.DAY) rows.sortedByDescending { it.key } else rows.sortedByDescending { it.tokens }
    }

    fun total(rows: List<Row>) = Row("Σ", rows.sumOf { it.calls }, rows.sumOf { it.ok }, rows.sumOf { it.truncated }, rows.sumOf { it.errors },
        rows.sumOf { it.inTokens }, rows.sumOf { it.outTokens }, rows.sumOf { it.ms })

    fun fmtTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", n / 1e6)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1e3)
        else -> n.toString()
    }
}

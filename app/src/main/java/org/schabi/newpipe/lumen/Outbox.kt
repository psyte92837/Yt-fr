/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Pure part of the "what we send" review: an outgoing prompt is split into blocks (paragraphs)
 * and lines; the user switches pieces off, and the prompt is rebuilt without them.
 */
object Outgoing {
    data class Block(val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    /** Paragraphs separated by blank lines; leading/trailing empty lines are dropped. */
    fun blocks(s: String): List<Block> =
        s.replace("\r\n", "\n").split(Regex("\n[ \t]*\n+"))
            .map { it.trim('\n') }
            .filter { it.isNotBlank() }
            .map { Block(it.split('\n')) }

    /**
     * What the user switched off: whole blocks ([offBlocks], block index) and single lines
     * ([offLines], "block.line"). Copies are cheap, the screen keeps one mutable instance.
     */
    class Selection(val offBlocks: MutableSet<Int> = mutableSetOf(), val offLines: MutableSet<String> = mutableSetOf()) {
        fun lineOn(b: Int, l: Int) = b !in offBlocks && "$b.$l" !in offLines
        fun blockOn(b: Int) = b !in offBlocks
        val isEmpty get() = offBlocks.isEmpty() && offLines.isEmpty()
    }

    /** Rebuilds the prompt from the blocks still switched on. */
    fun assemble(blocks: List<Block>, sel: Selection): String =
        blocks.mapIndexedNotNull { b, block ->
            if (!sel.blockOn(b)) return@mapIndexedNotNull null
            val kept = block.lines.filterIndexed { l, _ -> sel.lineOn(b, l) }
            if (kept.isEmpty()) null else kept.joinToString("\n")
        }.joinToString("\n\n")

    /** "kept / total" counts for the summary line: blocks, lines, characters. */
    data class Stats(val blocks: Int, val blocksTotal: Int, val lines: Int, val linesTotal: Int, val chars: Int, val charsTotal: Int)

    fun stats(blocks: List<Block>, sel: Selection): Stats {
        var b = 0
        var l = 0
        var lt = 0
        blocks.forEachIndexed { i, block ->
            lt += block.lines.size
            val k = block.lines.indices.count { sel.lineOn(i, it) }
            if (k > 0) b++
            l += k
        }
        val total = blocks.joinToString("\n\n") { it.text }.length
        return Stats(b, blocks.size, l, lt, assemble(blocks, sel).length, total)
    }

    /**
     * Carries the previous choice over to a new request of the same task (a retry, the next batch):
     * every block whose text is unchanged keeps its switches, new blocks start switched on.
     */
    fun carry(prev: List<Block>, prevSel: Selection, next: List<Block>): Selection {
        val out = Selection()
        val byText = HashMap<String, Int>()
        prev.forEachIndexed { i, b -> byText.putIfAbsent(b.text, i) }
        next.forEachIndexed { j, b ->
            val i = byText[b.text] ?: return@forEachIndexed
            if (i in prevSel.offBlocks) out.offBlocks.add(j)
            b.lines.indices.forEach { l -> if ("$i.$l" in prevSel.offLines) out.offLines.add("$j.$l") }
        }
        return out
    }

    enum class Rule { ASK, SEND, BLOCK }

    /** Per-task rule from its stored name; unknown values mean "ask". */
    fun rule(s: String?): Rule = when (s) {
        "send" -> Rule.SEND
        "block" -> Rule.BLOCK
        else -> Rule.ASK
    }

    fun ruleName(r: Rule) = when (r) {
        Rule.SEND -> "send"
        Rule.BLOCK -> "block"
        Rule.ASK -> "ask"
    }

    /**
     * What to do with a call: a session choice beats the stored rule; with review switched off
     * everything is sent unless blocked. Service calls (ping) and calls from the main thread
     * (no way to wait for a screen there) are never held.
     */
    fun decide(reviewOn: Boolean, stored: Rule, session: Rule?, task: String, mainThread: Boolean): Rule {
        val r = session ?: stored
        if (r == Rule.BLOCK) return Rule.BLOCK
        if (task == "ping" || mainThread || !reviewOn) return Rule.SEND
        return r
    }
}

/** The call was not sent: the user cancelled it or the task is blocked. */
class SendCancelled(msg: String) : IOException(msg)

/**
 * Holds every AI call until the user has seen the data that goes out and chosen what to keep.
 * One review at a time; parallel calls wait in line. Runs only on worker threads.
 */
object SendGate {
    class Request(
        val task: String,
        val pid: String,
        val provider: Provider,
        val model: String,
        val system: String,
        val user: String,
        val fileUri: String?,
        val maxTokens: Int
    ) {
        val blocks: List<Outgoing.Block> = Outgoing.blocks(user)
    }

    class Result(val user: String, val fileUri: String?)

    class Pending(val req: Request) {
        val latch = CountDownLatch(1)
        @Volatile var result: Result? = null
        fun answer(r: Result?) {
            result = r
            latch.countDown()
        }
    }

    private const val NOTE_ID = 7301
    private val lock = ReentrantLock(true)
    private val session = HashMap<String, Outgoing.Rule>()
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    // the same prompt again within a few minutes (retry, ×2 limit) reuses the choice made for it
    private val recent = LinkedHashMap<String, Pair<Long, Result>>()

    @Volatile var current: Pending? = null
        private set

    private val lastChoice = HashMap<String, Pair<List<Outgoing.Block>, Outgoing.Selection>>()

    /** The switches the user set for this task last time, applied to the new request. */
    fun previous(req: Request): Outgoing.Selection? = synchronized(lastChoice) {
        lastChoice[req.task]?.let { (b, s) -> Outgoing.carry(b, s, req.blocks) }
    }

    fun remember(req: Request, sel: Outgoing.Selection) = synchronized(lastChoice) {
        lastChoice[req.task] = req.blocks to Outgoing.Selection(sel.offBlocks.toMutableSet(), sel.offLines.toMutableSet())
    }

    val queued: Int get() = maxOf(0, waiting.get() - 1)

    var reviewOn: Boolean
        get() = Lumen.prefs.getBoolean("send_review", true)
        set(v) = Lumen.prefs.edit().putBoolean("send_review", v).apply()

    fun stored(task: String): Outgoing.Rule = Outgoing.rule(Lumen.prefs.getString("send_rule_$task", null))

    fun setStored(task: String, r: Outgoing.Rule) {
        val e = Lumen.prefs.edit()
        if (r == Outgoing.Rule.ASK) e.remove("send_rule_$task") else e.putString("send_rule_$task", Outgoing.ruleName(r))
        e.apply()
    }

    fun storedTasks(): List<String> = Lumen.prefs.all.keys.filter { it.startsWith("send_rule_") }.map { it.removePrefix("send_rule_") }

    fun setSession(task: String, r: Outgoing.Rule) = synchronized(session) { session[task] = r }

    fun clearSession() = synchronized(session) { session.clear() }

    private fun decision(task: String) = Outgoing.decide(
        reviewOn, stored(task), synchronized(session) { session[task] }, task,
        Looper.myLooper() == Looper.getMainLooper()
    )

    /** Returns what to send, or throws [SendCancelled]. */
    fun review(req: Request): Result {
        when (decision(req.task)) {
            Outgoing.Rule.SEND -> return Result(req.user, req.fileUri)
            Outgoing.Rule.BLOCK -> throw SendCancelled(t("Отправка «${req.task}» запрещена в настройках", "Sending \"${req.task}\" is blocked in settings"))
            Outgoing.Rule.ASK -> Unit
        }
        val key = sha1(req.task + "\u0000" + req.user + "\u0000" + req.fileUri)
        waiting.incrementAndGet()
        lock.lock()
        try {
            // the previous dialog may have changed the rule for this task
            when (decision(req.task)) {
                Outgoing.Rule.SEND -> return Result(req.user, req.fileUri)
                Outgoing.Rule.BLOCK -> throw SendCancelled(t("Отправка отменена", "Sending cancelled"))
                Outgoing.Rule.ASK -> Unit
            }
            synchronized(recent) {
                val now = System.currentTimeMillis()
                recent.entries.removeAll { now - it.value.first > 10 * 60_000 }
                recent[key]?.let { return it.second }
            }
            val p = Pending(req)
            current = p
            show()
            val answered = p.latch.await(30, TimeUnit.MINUTES)
            current = null
            hideNote()
            val r = p.result
            if (!answered || r == null) throw SendCancelled(t("Отправка отменена пользователем", "Sending cancelled by the user"))
            synchronized(recent) { recent[key] = System.currentTimeMillis() to r }
            return r
        } finally {
            current = null
            waiting.decrementAndGet()
            lock.unlock()
        }
    }

    private fun intent() = Intent(Lumen.app, SendReviewActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun show() {
        // the screen opens by itself while the app is visible; the notification covers background jobs
        if (LumenBase.visible > 0 || LensState.panelOpen) {
            // the panel over YouTube steps aside so the review screen can be seen
            LensState.service?.stepAside()
            try {
                Lumen.app.startActivity(intent())
            } catch (e: Exception) {
                L.w("send", "review screen: ${e.message}")
            }
        }
        try {
            Notify.channels(Lumen.app)
            val req = current?.req ?: return
            val pi = PendingIntent.getActivity(Lumen.app, NOTE_ID, intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notify.builder(Lumen.app, Notify.CH_DONE_QUIET)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(t("AI: проверьте, что отправляем", "AI: check what is sent"))
                .setContentText(req.task + " · " + req.provider.title + " · " + Fmt.num(req.user.length.toLong()) + t(" симв.", " chars"))
                .setContentIntent(pi).setOngoing(true).setAutoCancel(true).build()
            Lumen.app.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
        } catch (e: Exception) {
            L.w("send", "notification: ${e.message}")
        }
    }

    private fun hideNote() = try {
        Lumen.app.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    } catch (e: Exception) {
        // nothing to hide
    }
}

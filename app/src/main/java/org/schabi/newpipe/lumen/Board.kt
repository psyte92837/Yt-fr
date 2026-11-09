/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/*
 * "📋 Tasks" of the Studio: a small Jira-like tracker kept on the phone (protocol lumen.board v1).
 * Pure logic: model, JSON, quick-add syntax, ordering, filters, due groups, plans (sprints) and stats.
 * Days are epoch days in local time (0 = no date), so the logic needs no calendar classes.
 */

enum class BStatus(val id: String) {
    BACKLOG("backlog"), TODO("todo"), DOING("doing"), REVIEW("review"), DONE("done");
    companion object { fun of(s: String?) = entries.firstOrNull { it.id == s } ?: TODO }
}

/** Highest first: [rank] 0 is the most urgent. */
enum class BPrio(val id: String, val rank: Int) {
    HIGHEST("highest", 0), HIGH("high", 1), MEDIUM("medium", 2), LOW("low", 3), LOWEST("lowest", 4);
    companion object { fun of(s: String?) = entries.firstOrNull { it.id == s } ?: MEDIUM }
}

enum class BType(val id: String) {
    TASK("task"), VIDEO("video"), BUG("bug"), IDEA("idea"), EPIC("epic");
    companion object { fun of(s: String?) = entries.firstOrNull { it.id == s } ?: TASK }
}

data class BCheck(val text: String, val done: Boolean = false)

/** A comment ([auto] = an automatic line of the history, e.g. a status change). */
data class BNote(val at: Long, val text: String, val auto: Boolean = false)

data class BItem(
    val id: Int,
    val title: String,
    val desc: String = "",
    val type: BType = BType.TASK,
    val status: BStatus = BStatus.TODO,
    val prio: BPrio = BPrio.MEDIUM,
    val labels: List<String> = emptyList(),
    /** Epoch day of the deadline, 0 = none. */
    val due: Int = 0,
    /** Story points / effort, 0 = not set. */
    val points: Int = 0,
    /** Id of the epic this item belongs to, 0 = none. */
    val epic: Int = 0,
    /** Id of the plan (sprint), 0 = backlog. */
    val plan: Int = 0,
    val checks: List<BCheck> = emptyList(),
    val notes: List<BNote> = emptyList(),
    val created: Long = 0,
    val updated: Long = 0,
    /** When it went to DONE (0 = not done). */
    val doneAt: Long = 0,
    /** Place inside its column; smaller is higher. */
    val order: Double = 0.0
) {
    val checksDone get() = checks.count { it.done }
}

/** A plan = a sprint: a time box with a goal. */
data class BPlan(
    val id: Int,
    val name: String,
    val goal: String = "",
    val start: Int = 0,
    val end: Int = 0,
    /** planned | active | closed */
    val state: String = "planned"
)

data class BoardData(
    val prefix: String = "LUM",
    val nextId: Int = 1,
    val nextPlan: Int = 1,
    val items: List<BItem> = emptyList(),
    val plans: List<BPlan> = emptyList()
) {
    fun item(id: Int) = items.firstOrNull { it.id == id }
    fun plan(id: Int) = plans.firstOrNull { it.id == id }
    val activePlan get() = plans.firstOrNull { it.state == "active" }
    val epics get() = items.filter { it.type == BType.EPIC }
}

/** What the quick-add line understood. */
data class BQuick(
    val title: String,
    val prio: BPrio? = null,
    val type: BType? = null,
    val labels: List<String> = emptyList(),
    val due: Int = 0,
    val points: Int = 0
)

data class BFilter(
    val query: String = "",
    val prio: BPrio? = null,
    val type: BType? = null,
    val label: String = "",
    /** Only the active plan (with "Plan" scope). */
    val planOnly: Boolean = false,
    val hideDone: Boolean = false
)

/** Deadline groups of the "Dates" view. */
enum class BDue { OVERDUE, TODAY, TOMORROW, WEEK, LATER, NONE }

data class BStats(val total: Int, val byStatus: Map<BStatus, Int>, val overdue: Int, val done: Int, val points: Int, val pointsDone: Int) {
    val percent get() = if (total == 0) 0 else done * 100 / total
}

object Board {
    const val PROTOCOL = "lumen.board v1"
    const val MAX_NOTES = 60

    // ---------- keys ----------

    fun key(b: BoardData, it: BItem) = "${b.prefix}-${it.id}"

    /** "LUM-12" or "12" → 12 (any prefix is fine, the number decides). */
    fun parseKey(s: String): Int? = Regex("""(?:[A-Za-zА-Яа-яЁё]+-)?(\d{1,6})""").matchEntire(s.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun cleanPrefix(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase().take(6).ifEmpty { "LUM" }

    // ---------- days ----------

    private const val DAY = 86_400_000L

    /** Local epoch day of [ms] with the time-zone offset [tzMs] (TimeZone.getOffset). */
    fun dayOf(ms: Long, tzMs: Long): Int = Math.floorDiv(ms + tzMs, DAY).toInt()

    /** 0 = Monday … 6 = Sunday (1970-01-01 was a Thursday). */
    fun weekday(day: Int): Int = Math.floorMod(day + 3, 7)

    /** Epoch day → (year, month 1-12, day 1-31). */
    fun civil(day: Int): Triple<Int, Int, Int> {
        val z = day + 719468L
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    /** (year, month, day) → epoch day. */
    fun epochDay(y: Int, m: Int, d: Int): Int {
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = Math.floorDiv(yy, 400L)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097 + doe - 719468).toInt()
    }

    /** "12.11" (this year, or next if already past by more than a month), "12.11.2026", "2026-11-12". */
    fun parseDate(s: String, today: Int): Int? {
        val t = s.trim()
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").matchEntire(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return valid(y.toInt(), mo.toInt(), d.toInt())
        }
        Regex("""(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?""").matchEntire(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val yRaw = m.groupValues[3]
            if (yRaw.isNotEmpty()) return valid(yRaw.toInt().let { if (it < 100) 2000 + it else it }, mo, d)
            val y = civil(today).first
            val day = valid(y, mo, d) ?: return null
            return if (day < today - 31) valid(y + 1, mo, d) else day
        }
        return null
    }

    private fun valid(y: Int, m: Int, d: Int): Int? {
        if (m !in 1..12 || d !in 1..31) return null
        val day = epochDay(y, m, d)
        return if (civil(day) == Triple(y, m, d)) day else null
    }

    fun dueGroup(due: Int, today: Int): BDue = when {
        due <= 0 -> BDue.NONE
        due < today -> BDue.OVERDUE
        due == today -> BDue.TODAY
        due == today + 1 -> BDue.TOMORROW
        due <= today + 7 -> BDue.WEEK
        else -> BDue.LATER
    }

    fun overdue(it: BItem, today: Int) = it.due in 1 until today && it.status != BStatus.DONE

    // ---------- quick add ----------

    private val WEEKDAYS = listOf(
        listOf("пн", "понедельник", "mon", "monday"), listOf("вт", "вторник", "tue", "tuesday"), listOf("ср", "среда", "wed", "wednesday"),
        listOf("чт", "четверг", "thu", "thursday"), listOf("пт", "пятница", "fri", "friday"), listOf("сб", "суббота", "sat", "saturday"),
        listOf("вс", "воскресенье", "sun", "sunday")
    )

    /** Word after "@" → epoch day: сегодня/завтра/послезавтра, a weekday (the next one), +3 (days), +2w, 12.11. */
    fun parseDue(word: String, today: Int): Int? {
        val w = word.lowercase().trim()
        when (w) {
            "сегодня", "today", "сег" -> return today
            "завтра", "tomorrow", "зав" -> return today + 1
            "послезавтра" -> return today + 2
            "неделя", "week" -> return today + 7
        }
        WEEKDAYS.indexOfFirst { w in it }.takeIf { it >= 0 }?.let { wd ->
            val diff = Math.floorMod(wd - weekday(today), 7)
            return today + if (diff == 0) 7 else diff
        }
        Regex("""\+(\d{1,3})([dдwн]?)""").matchEntire(w)?.let { m ->
            val n = m.groupValues[1].toInt()
            return today + if (m.groupValues[2] in setOf("w", "н")) n * 7 else n
        }
        return parseDate(w, today)
    }

    private val PRIO_WORDS = mapOf(
        "!!!" to BPrio.HIGHEST, "!!" to BPrio.HIGH, "!" to BPrio.HIGH,
        "!1" to BPrio.HIGHEST, "!2" to BPrio.HIGH, "!3" to BPrio.MEDIUM, "!4" to BPrio.LOW, "!5" to BPrio.LOWEST,
        "!highest" to BPrio.HIGHEST, "!high" to BPrio.HIGH, "!medium" to BPrio.MEDIUM, "!low" to BPrio.LOW, "!lowest" to BPrio.LOWEST,
        "!срочно" to BPrio.HIGHEST, "!высокий" to BPrio.HIGH, "!средний" to BPrio.MEDIUM, "!низкий" to BPrio.LOW
    )

    private val TYPE_WORDS = mapOf(
        "баг" to BType.BUG, "bug" to BType.BUG, "ошибка" to BType.BUG, "идея" to BType.IDEA, "idea" to BType.IDEA,
        "ролик" to BType.VIDEO, "видео" to BType.VIDEO, "video" to BType.VIDEO, "эпик" to BType.EPIC, "epic" to BType.EPIC,
        "задача" to BType.TASK, "task" to BType.TASK
    )

    /**
     * "Снять интро !high #монтаж @пт ~3" → title, priority, labels, due, points.
     * A leading "баг:"/"идея:"/"ролик:"/"эпик:" sets the type. Unknown tokens stay in the title.
     */
    fun quick(line: String, today: Int): BQuick {
        var s = line.trim()
        var type: BType? = null
        Regex("""^([\p{L}]+)\s*:\s*""").find(s)?.let { m ->
            TYPE_WORDS[m.groupValues[1].lowercase()]?.let { type = it; s = s.substring(m.range.last + 1) }
        }
        var prio: BPrio? = null
        val labels = mutableListOf<String>()
        var due = 0
        var points = 0
        val words = mutableListOf<String>()
        for (tok in s.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val low = tok.lowercase()
            when {
                PRIO_WORDS.containsKey(low) -> prio = PRIO_WORDS[low]
                tok.length > 1 && tok.startsWith("#") -> cleanLabel(tok.substring(1)).takeIf { it.isNotEmpty() && it !in labels }?.let { labels += it }
                tok.length > 1 && tok.startsWith("@") && parseDue(tok.substring(1), today) != null -> due = parseDue(tok.substring(1), today)!!
                Regex("""~(\d{1,3})""").matches(tok) -> points = tok.substring(1).toInt()
                else -> words += tok
            }
        }
        return BQuick(words.joinToString(" ").trim(), prio, type, labels, due, points)
    }

    fun cleanLabel(s: String): String = s.trim().trimStart('#').replace(Regex("\\s+"), "_").lowercase().take(24)

    fun parseLabels(s: String): List<String> = s.split(',', ';', ' ').map { cleanLabel(it) }.filter { it.isNotEmpty() }.distinct()

    // ---------- changes ----------

    fun add(b: BoardData, q: BQuick, now: Long, status: BStatus = BStatus.TODO, plan: Int = 0, epic: Int = 0, desc: String = "", checks: List<BCheck> = emptyList()): Pair<BoardData, BItem> {
        val it = BItem(
            id = b.nextId, title = q.title.ifBlank { "…" }.take(200), desc = desc, type = q.type ?: BType.TASK, status = status,
            prio = q.prio ?: BPrio.MEDIUM, labels = q.labels, due = q.due, points = q.points, epic = epic, plan = plan, checks = checks,
            created = now, updated = now, doneAt = if (status == BStatus.DONE) now else 0, order = topOrder(b, status)
        )
        return b.copy(nextId = b.nextId + 1, items = b.items + it) to it
    }

    private fun topOrder(b: BoardData, s: BStatus) = (b.items.filter { it.status == s }.minOfOrNull { it.order } ?: 1.0) - 1.0

    private fun bottomOrder(b: BoardData, s: BStatus) = (b.items.filter { it.status == s }.maxOfOrNull { it.order } ?: -1.0) + 1.0

    /** Replaces the item; status, plan and other important changes are written to its history. */
    fun update(b: BoardData, new: BItem, now: Long, names: (BItem, BItem) -> List<String> = ::changes): BoardData {
        val old = b.item(new.id) ?: return b
        var it = new.copy(updated = now)
        if (old.status != it.status) {
            it = it.copy(doneAt = if (it.status == BStatus.DONE) now else 0, order = bottomOrder(b.copy(items = b.items - old), it.status))
        }
        val lines = names(old, it)
        if (lines.isNotEmpty()) it = it.copy(notes = (it.notes + lines.map { l -> BNote(now, l, auto = true) }).takeLast(MAX_NOTES))
        return b.copy(items = b.items.map { x -> if (x.id == it.id) it else x })
    }

    /** History lines in protocol words (the screen translates them): "status:todo>doing", "prio:…", "plan:3", "due:20123". */
    fun changes(old: BItem, new: BItem): List<String> = buildList {
        if (old.status != new.status) add("status:${old.status.id}>${new.status.id}")
        if (old.prio != new.prio) add("prio:${old.prio.id}>${new.prio.id}")
        if (old.plan != new.plan) add("plan:${new.plan}")
        if (old.due != new.due) add("due:${new.due}")
        if (old.title != new.title) add("title")
    }

    fun move(b: BoardData, id: Int, to: BStatus, now: Long): BoardData = b.item(id)?.let { update(b, it.copy(status = to), now) } ?: b

    /** Next / previous column (Backlog ↔ … ↔ Done). */
    fun step(s: BStatus, dir: Int): BStatus = BStatus.entries[(s.ordinal + dir).coerceIn(0, BStatus.entries.size - 1)]

    /** Moves the item up/down inside its column. */
    fun shift(b: BoardData, id: Int, dir: Int): BoardData {
        val it = b.item(id) ?: return b
        val col = column(b, it.status)
        val j = col.indexOfFirst { x -> x.id == id } + dir
        if (j !in col.indices) return b
        return place(b, it.status, id, j)
    }

    /** Lays the column [s] out again with [id] at index [at]. */
    fun place(b: BoardData, s: BStatus, id: Int, at: Int): BoardData {
        val col = column(b, s).filter { it.id != id }.toMutableList()
        b.item(id)?.let { col.add(at.coerceIn(0, col.size), it) }
        val pos = col.mapIndexed { i, x -> x.id to i.toDouble() }.toMap()
        return b.copy(items = b.items.map { x -> pos[x.id]?.let { x.copy(order = it) } ?: x })
    }

    fun remove(b: BoardData, id: Int): BoardData =
        b.copy(items = b.items.filter { it.id != id }.map { if (it.epic == id) it.copy(epic = 0) else it })

    fun duplicate(b: BoardData, id: Int, now: Long, copyMark: String): Pair<BoardData, BItem>? {
        val src = b.item(id) ?: return null
        val it = src.copy(id = b.nextId, title = (src.title + " " + copyMark).take(200), status = if (src.status == BStatus.DONE) BStatus.TODO else src.status,
            checks = src.checks.map { c -> c.copy(done = false) }, notes = emptyList(), created = now, updated = now, doneAt = 0, order = src.order + 0.5)
        return b.copy(nextId = b.nextId + 1, items = b.items + it) to it
    }

    fun comment(b: BoardData, id: Int, text: String, now: Long): BoardData {
        val s = text.trim()
        if (s.isEmpty()) return b
        return b.copy(items = b.items.map { if (it.id == id) it.copy(notes = (it.notes + BNote(now, s)).takeLast(MAX_NOTES), updated = now) else it })
    }

    fun toggleCheck(it: BItem, i: Int): BItem = it.copy(checks = it.checks.mapIndexed { k, c -> if (k == i) c.copy(done = !c.done) else c })

    // ---------- views ----------

    /** A column in hand order ([BItem.order]), then newest first. */
    fun column(b: BoardData, s: BStatus, f: BFilter = BFilter()): List<BItem> =
        filter(b, f).filter { it.status == s }.sortedWith(compareBy<BItem> { it.order }.thenByDescending { it.updated })

    fun matches(b: BoardData, it: BItem, f: BFilter): Boolean {
        if (f.prio != null && it.prio != f.prio) return false
        if (f.type != null && it.type != f.type) return false
        if (f.label.isNotEmpty() && f.label !in it.labels) return false
        if (f.hideDone && it.status == BStatus.DONE) return false
        if (f.planOnly) {
            val p = b.activePlan ?: return false
            if (it.plan != p.id) return false
        }
        val q = f.query.trim().lowercase()
        if (q.isEmpty()) return true
        parseKey(q)?.let { n -> if (q.any { c -> c.isLetter() } || q.length <= 6) if (it.id == n) return true }
        val hay = (it.title + " " + it.desc + " " + it.labels.joinToString(" ") + " " + it.checks.joinToString(" ") { c -> c.text } + " " + key(b, it)).lowercase()
        return q.split(Regex("\\s+")).all { w -> hay.contains(w.trimStart('#')) }
    }

    fun filter(b: BoardData, f: BFilter): List<BItem> = b.items.filter { matches(b, it, f) }

    /** The list view: open items by priority, then deadline, then newest. */
    fun ranked(items: List<BItem>): List<BItem> = items.sortedWith(
        compareBy<BItem> { it.status == BStatus.DONE }.thenBy { it.prio.rank }.thenBy { if (it.due > 0) it.due else Int.MAX_VALUE }.thenByDescending { it.updated })

    fun byDue(items: List<BItem>, today: Int): Map<BDue, List<BItem>> =
        items.filter { it.status != BStatus.DONE }.groupBy { dueGroup(it.due, today) }
            .mapValues { (_, l) -> l.sortedWith(compareBy<BItem> { if (it.due > 0) it.due else Int.MAX_VALUE }.thenBy { it.prio.rank }) }
            .toSortedMap(compareBy { it.ordinal })

    fun labels(b: BoardData): List<String> = b.items.flatMap { it.labels }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    fun stats(items: List<BItem>, today: Int): BStats = BStats(
        total = items.size,
        byStatus = BStatus.entries.associateWith { s -> items.count { it.status == s } },
        overdue = items.count { overdue(it, today) },
        done = items.count { it.status == BStatus.DONE },
        points = items.sumOf { it.points },
        pointsDone = items.filter { it.status == BStatus.DONE }.sumOf { it.points }
    )

    /** Share of an epic's children that are done (0..100), or null without children. */
    fun epicProgress(b: BoardData, epic: Int): Int? {
        val kids = b.items.filter { it.epic == epic }
        if (kids.isEmpty()) return null
        return kids.count { it.status == BStatus.DONE } * 100 / kids.size
    }

    // ---------- plans ----------

    fun addPlan(b: BoardData, name: String, goal: String, start: Int, days: Int): Pair<BoardData, BPlan> {
        val p = BPlan(b.nextPlan, name.trim().ifEmpty { "Plan ${b.nextPlan}" }.take(80), goal.trim().take(300), start, start + maxOf(1, days) - 1)
        return b.copy(nextPlan = b.nextPlan + 1, plans = b.plans + p) to p
    }

    fun updatePlan(b: BoardData, p: BPlan) = b.copy(plans = b.plans.map { if (it.id == p.id) p else it })

    /** Only one plan runs at a time: starting one puts a running one back to "planned". */
    fun startPlan(b: BoardData, id: Int, today: Int): BoardData = b.copy(plans = b.plans.map {
        when {
            it.id == id -> { val len = maxOf(1, it.end - it.start + 1); it.copy(state = "active", start = today, end = today + len - 1) }
            it.state == "active" -> it.copy(state = "planned")
            else -> it
        }
    })

    /**
     * Closes a plan: unfinished items go to [moveTo] (another plan id) or the backlog (0);
     * finished ones keep it so the plan shows what it delivered.
     */
    fun closePlan(b: BoardData, id: Int, moveTo: Int, now: Long): BoardData {
        val kept = b.copy(plans = b.plans.map { if (it.id == id) it.copy(state = "closed") else it })
        return kept.copy(items = kept.items.map {
            if (it.plan == id && it.status != BStatus.DONE) it.copy(plan = moveTo, updated = now,
                notes = (it.notes + BNote(now, "plan:$moveTo", auto = true)).takeLast(MAX_NOTES)) else it
        })
    }

    fun removePlan(b: BoardData, id: Int): BoardData =
        b.copy(plans = b.plans.filter { it.id != id }, items = b.items.map { if (it.plan == id) it.copy(plan = 0) else it })

    fun planItems(b: BoardData, id: Int) = b.items.filter { it.plan == id }

    /** Days left in a plan (0 on its last day, negative after). */
    fun daysLeft(p: BPlan, today: Int) = p.end - today

    /**
     * Ideal vs real remaining work of a plan per day (points, or items when nothing is estimated):
     * pairs (ideal, actual) from start to [today] (or end).
     */
    fun burndown(b: BoardData, p: BPlan, today: Int, tzMs: Long): List<Pair<Float, Int>> {
        val items = planItems(b, p.id)
        val usePoints = items.any { it.points > 0 }
        fun w(it: BItem) = if (usePoints) maxOf(it.points, 0) else 1
        val total = items.sumOf { w(it) }
        val days = maxOf(1, p.end - p.start)
        val last = minOf(today, p.end)
        if (last < p.start) return emptyList()
        return (p.start..last).map { d ->
            val left = total - items.filter { it.status == BStatus.DONE && it.doneAt > 0 && dayOf(it.doneAt, tzMs) <= d }.sumOf { w(it) }
            (total * (1f - (d - p.start).toFloat() / days)).coerceAtLeast(0f) to left
        }
    }

    // ---------- AI breakdown ----------

    /** A plan from the AI: {"tasks":[{"title","type","prio","points","due_days","checks":[…]}]} or a bare array. */
    fun parseAi(text: String, today: Int): List<Pair<BQuick, List<BCheck>>> {
        val s = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = try {
            if (s.startsWith("[")) JSONArray(s) else JSONObject(s.substring(s.indexOf('{').coerceAtLeast(0))).optJSONArray("tasks")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { BQuick(it.take(200)) to emptyList() }
            val title = o.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            val dd = o.optInt("due_days", -1)
            val checks = o.optJSONArray("checks")?.let { c -> (0 until c.length()).map { c.optString(it).trim() }.filter { it.isNotEmpty() }.take(12).map { BCheck(it.take(200)) } }.orEmpty()
            BQuick(
                title.take(200),
                prio = o.optString("prio").takeIf { it.isNotEmpty() }?.let { BPrio.of(it.lowercase()) },
                type = o.optString("type").takeIf { it.isNotEmpty() }?.let { BType.of(it.lowercase()) },
                labels = o.optJSONArray("labels")?.let { l -> (0 until l.length()).map { cleanLabel(l.optString(it)) }.filter { it.isNotEmpty() } }.orEmpty(),
                due = if (dd >= 0) today + dd.coerceAtMost(365) else 0,
                points = o.optInt("points", 0).coerceIn(0, 100)
            ) to checks
        }.take(30)
    }

    /** Short board summary for the AI prompt (open items only). */
    fun brief(b: BoardData, max: Int = 40): String = ranked(b.items.filter { it.status != BStatus.DONE }).take(max)
        .joinToString("\n") { "${key(b, it)} [${it.status.id}/${it.prio.id}/${it.type.id}] ${it.title}" }

    // ---------- JSON ----------

    fun toJson(b: BoardData): JSONObject = JSONObject()
        .put("protocol", PROTOCOL).put("prefix", b.prefix).put("next", b.nextId).put("nextPlan", b.nextPlan)
        .put("items", JSONArray().apply { b.items.forEach { put(itemJson(it)) } })
        .put("plans", JSONArray().apply {
            b.plans.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("goal", p.goal).put("start", p.start).put("end", p.end).put("state", p.state)) }
        })

    private fun itemJson(it: BItem) = JSONObject().apply {
        put("id", it.id); put("title", it.title)
        if (it.desc.isNotEmpty()) put("desc", it.desc)
        put("type", it.type.id); put("status", it.status.id); put("prio", it.prio.id)
        if (it.labels.isNotEmpty()) put("labels", JSONArray(it.labels))
        if (it.due > 0) put("due", it.due)
        if (it.points > 0) put("points", it.points)
        if (it.epic > 0) put("epic", it.epic)
        if (it.plan > 0) put("plan", it.plan)
        if (it.checks.isNotEmpty()) put("checks", JSONArray().apply { it.checks.forEach { c -> put(JSONObject().put("t", c.text).put("d", c.done)) } })
        if (it.notes.isNotEmpty()) put("notes", JSONArray().apply { it.notes.forEach { n -> put(JSONObject().put("at", n.at).put("t", n.text).apply { if (n.auto) put("a", true) }) } })
        put("created", it.created); put("updated", it.updated)
        if (it.doneAt > 0) put("doneAt", it.doneAt)
        put("order", it.order)
    }

    fun fromJson(o: JSONObject): BoardData {
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::itemOf) } }.orEmpty()
        val plans = o.optJSONArray("plans")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { p -> BPlan(p.optInt("id"), p.optString("name"), p.optString("goal"), p.optInt("start"), p.optInt("end"), p.optString("state", "planned")) }
            }
        }.orEmpty()
        return BoardData(
            prefix = cleanPrefix(o.optString("prefix", "LUM")),
            nextId = maxOf(o.optInt("next", 1), (items.maxOfOrNull { it.id } ?: 0) + 1),
            nextPlan = maxOf(o.optInt("nextPlan", 1), (plans.maxOfOrNull { it.id } ?: 0) + 1),
            items = items, plans = plans
        )
    }

    private fun itemOf(o: JSONObject): BItem? {
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        return BItem(
            id = id, title = o.optString("title"), desc = o.optString("desc"), type = BType.of(o.optString("type")),
            status = BStatus.of(o.optString("status")), prio = BPrio.of(o.optString("prio")), labels = strs("labels"),
            due = o.optInt("due", 0), points = o.optInt("points", 0), epic = o.optInt("epic", 0), plan = o.optInt("plan", 0),
            checks = o.optJSONArray("checks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { c -> BCheck(c.optString("t"), c.optBoolean("d")) } } }.orEmpty(),
            notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { n -> BNote(n.optLong("at"), n.optString("t"), n.optBoolean("a")) } } }.orEmpty(),
            created = o.optLong("created"), updated = o.optLong("updated"), doneAt = o.optLong("doneAt"), order = o.optDouble("order", 0.0)
        )
    }

    /** First run: a short example so the board is not empty and shows how it works (localised by the caller). */
    fun starter(today: Int, now: Long, texts: List<String>, labels: List<String>): BoardData {
        var b = BoardData()
        val statuses = listOf(BStatus.DONE, BStatus.DOING, BStatus.TODO, BStatus.TODO, BStatus.BACKLOG)
        val prios = listOf(BPrio.MEDIUM, BPrio.HIGH, BPrio.HIGHEST, BPrio.MEDIUM, BPrio.LOW)
        val dues = listOf(0, today + 1, today + 3, 0, 0)
        val types = listOf(BType.TASK, BType.VIDEO, BType.VIDEO, BType.BUG, BType.IDEA)
        texts.take(5).forEachIndexed { i, s ->
            b = add(b, BQuick(s, prios[i], types[i], listOfNotNull(labels.getOrNull(i)), dues[i]), now, statuses[i]).first
        }
        return b
    }
}

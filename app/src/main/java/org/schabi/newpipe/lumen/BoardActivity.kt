/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * "📋 Tasks" of the Studio: a Jira-like tracker for the author's work — board by status, list, deadlines,
 * plans (sprints) with a burndown, quick add "text !high #label @fri ~3", checklists, comments, history,
 * epics and AI breakdown of a goal (through [Llm.complete]). Kept on the phone in files/lumen/studio/board.json.
 */
class BoardActivity : LumenBase() {
    companion object {
        private const val VIEW_BOARD = 0
        private const val VIEW_LIST = 1
        private const val VIEW_DATES = 2
        private const val VIEW_PLANS = 3

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, BoardActivity::class.java)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private val file get() = File(Store.root, "studio/board.json")

        fun load(): BoardData? = try {
            file.takeIf { it.exists() }?.let { Board.fromJson(org.json.JSONObject(it.readText())) }
        } catch (e: Exception) {
            L.w("board", "load: ${e.message}")
            null
        }

        // status / priority colours: (light theme text, dark theme text)
        private val STATUS_COLORS = mapOf(
            BStatus.BACKLOG to 0xFF8A8A93.toInt(), BStatus.TODO to 0xFF3B82F6.toInt(), BStatus.DOING to 0xFFF59E0B.toInt(),
            BStatus.REVIEW to 0xFF8B5CF6.toInt(), BStatus.DONE to 0xFF22C55E.toInt()
        )
        private val PRIO_COLORS = mapOf(
            BPrio.HIGHEST to 0xFFE53935.toInt(), BPrio.HIGH to 0xFFFB8C00.toInt(), BPrio.MEDIUM to 0xFFF2B705.toInt(),
            BPrio.LOW to 0xFF42A5F5.toInt(), BPrio.LOWEST to 0xFF90A4AE.toInt()
        )
        private val LABEL_COLORS = intArrayOf(
            0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(),
            0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(), 0xFF6366F1.toInt()
        )
    }

    private var board = BoardData()
    private var viewMode = VIEW_BOARD
    private var filter = BFilter()
    private var showDone = false
    private var showClosed = false

    private val now get() = System.currentTimeMillis()
    private val tz get() = TimeZone.getDefault().getOffset(now).toLong()
    private val today get() = Board.dayOf(now, tz)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewMode = Lumen.prefs.getInt("board_view", VIEW_BOARD).coerceIn(0, VIEW_PLANS)
        board = load() ?: starter().also { save(it) }
        render()
    }

    private fun starter(): BoardData = Board.starter(today, now, listOf(
        t("Придумать тему следующего ролика", "Pick the topic of the next video"),
        t("Записать озвучку для Shorts", "Record the voice-over for a Short"),
        t("Смонтировать выпуск и сделать обложку", "Edit the episode and make the thumbnail"),
        t("Субтитры съезжают в конце ролика", "Subtitles drift at the end of the video"),
        t("Серия роликов «за кадром»", "A behind-the-scenes series")
    ), listOf("", "shorts", t("монтаж", "editing"), "", t("идеи", "ideas")))

    private fun save(b: BoardData = board) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Board.toJson(b).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            L.e("board", "save: ${e.message}", e)
            toast(t("Не удалось сохранить задачи", "Could not save the tasks"))
        }
    }

    private fun set(b: BoardData) {
        board = b
        save()
        render()
    }

    // ---------- words ----------

    private fun statusName(s: BStatus) = when (s) {
        BStatus.BACKLOG -> t("Бэклог", "Backlog")
        BStatus.TODO -> t("К работе", "To do")
        BStatus.DOING -> t("В работе", "In progress")
        BStatus.REVIEW -> t("Проверка", "Review")
        BStatus.DONE -> t("Готово", "Done")
    }

    private fun prioName(p: BPrio) = when (p) {
        BPrio.HIGHEST -> t("Срочно", "Highest")
        BPrio.HIGH -> t("Высокий", "High")
        BPrio.MEDIUM -> t("Средний", "Medium")
        BPrio.LOW -> t("Низкий", "Low")
        BPrio.LOWEST -> t("Когда-нибудь", "Lowest")
    }

    private fun prioGlyph(p: BPrio) = when (p) {
        BPrio.HIGHEST -> "⏫"
        BPrio.HIGH -> "▲"
        BPrio.MEDIUM -> "◆"
        BPrio.LOW -> "▼"
        BPrio.LOWEST -> "⏬"
    }

    private fun typeIcon(ty: BType) = when (ty) {
        BType.TASK -> "☑"
        BType.VIDEO -> "🎬"
        BType.BUG -> "🐞"
        BType.IDEA -> "💡"
        BType.EPIC -> "⚡"
    }

    private fun typeName(ty: BType) = when (ty) {
        BType.TASK -> t("Задача", "Task")
        BType.VIDEO -> t("Ролик", "Video")
        BType.BUG -> t("Ошибка", "Bug")
        BType.IDEA -> t("Идея", "Idea")
        BType.EPIC -> t("Эпик", "Epic")
    }

    private fun monthShort(m: Int) = listOf(
        t("янв", "Jan"), t("фев", "Feb"), t("мар", "Mar"), t("апр", "Apr"), t("мая", "May"), t("июн", "Jun"),
        t("июл", "Jul"), t("авг", "Aug"), t("сен", "Sep"), t("окт", "Oct"), t("ноя", "Nov"), t("дек", "Dec")
    )[m - 1]

    private fun weekdayShort(d: Int) = listOf(t("пн", "Mon"), t("вт", "Tue"), t("ср", "Wed"), t("чт", "Thu"), t("пт", "Fri"), t("сб", "Sat"), t("вс", "Sun"))[Board.weekday(d)]

    private fun dayLabel(d: Int): String {
        if (d <= 0) return t("без срока", "no date")
        return when (d - today) {
            0 -> t("сегодня", "today")
            1 -> t("завтра", "tomorrow")
            -1 -> t("вчера", "yesterday")
            in 2..6 -> weekdayShort(d) + ", " + dateShort(d)
            else -> dateShort(d)
        }
    }

    private fun dateShort(d: Int): String {
        val (y, m, day) = Board.civil(d)
        val ty = Board.civil(today).first
        return "$day ${monthShort(m)}" + if (y != ty) " $y" else ""
    }

    private fun dueName(g: BDue) = when (g) {
        BDue.OVERDUE -> t("🔥 Просрочено", "🔥 Overdue")
        BDue.TODAY -> t("☀ Сегодня", "☀ Today")
        BDue.TOMORROW -> t("🌙 Завтра", "🌙 Tomorrow")
        BDue.WEEK -> t("📆 На этой неделе", "📆 This week")
        BDue.LATER -> t("🗓 Позже", "🗓 Later")
        BDue.NONE -> t("○ Без срока", "○ No date")
    }

    /** History line ("status:todo>doing") in words. */
    private fun historyText(n: BNote): String {
        val s = n.text
        fun pair(x: String) = x.substringAfter(':').split('>')
        return when {
            s.startsWith("status:") -> pair(s).let { p -> t("Статус", "Status") + ": " + statusName(BStatus.of(p[0])) + " → " + statusName(BStatus.of(p.getOrNull(1))) }
            s.startsWith("prio:") -> pair(s).let { p -> t("Приоритет", "Priority") + ": " + prioName(BPrio.of(p[0])) + " → " + prioName(BPrio.of(p.getOrNull(1))) }
            s.startsWith("plan:") -> s.substringAfter(':').toIntOrNull().let { id ->
                t("План", "Plan") + ": " + (board.plan(id ?: 0)?.name ?: t("бэклог", "backlog"))
            }
            s.startsWith("due:") -> t("Срок", "Due") + ": " + dayLabel(s.substringAfter(':').toIntOrNull() ?: 0)
            s == "title" -> t("Название изменено", "Title changed")
            else -> s
        }
    }

    private fun whenText(ms: Long): String {
        val d = Board.dayOf(ms, tz)
        val mins = (ms + tz) % 86_400_000L / 60_000L
        return dayLabel(d) + String.format(Locale.US, ", %02d:%02d", mins / 60, mins % 60)
    }

    // ---------- small views ----------

    /** A colour readable as text on the screen's background. */
    private fun ink(c: Int): Int {
        val night = isNight()
        val k = if (night) 0.35f else 0.3f
        val target = if (night) 255 else 0
        fun mix(v: Int) = (v + (target - v) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix((c shr 16) and 0xFF) shl 16) or (mix((c shr 8) and 0xFF) shl 8) or mix(c and 0xFF)
    }

    private fun soft(c: Int, alpha: Int = 0x2E) = (c and 0x00FFFFFF) or (alpha shl 24)

    private fun pill(s: String, color: Int, filled: Boolean = true): TextView = text(this, s, 12f, bold = true, color = ink(color)).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = if (filled) roundBg(soft(color), 10f, this@BoardActivity) else roundBg(0, 10f, this@BoardActivity, soft(color, 0x80))
    }

    private fun labelColor(l: String) = LABEL_COLORS[Math.floorMod(l.hashCode(), LABEL_COLORS.size)]

    private fun progressBar(percent: Int, color: Int, heightDp: Int = 6): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundBg(soft(Palette.OUTLINE, 0x30), heightDp / 2f, this@BoardActivity)
        val p = percent.coerceIn(0, 100)
        if (p > 0) addView(View(this@BoardActivity).apply { background = roundBg(color, heightDp / 2f, this@BoardActivity) },
            LinearLayout.LayoutParams(0, dp(heightDp), p.toFloat()))
        if (p < 100) addView(View(this@BoardActivity), LinearLayout.LayoutParams(0, dp(heightDp), (100 - p).toFloat()))
    }

    private fun wrap(vararg v: View): FlowLayout = FlowLayout(this, dp(6), dp(6)).apply { v.forEach { addView(it) } }

    private fun hRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    // ---------- page ----------

    private fun render() {
        val c = Col(this)
        hero(c)
        quickAdd(c)
        filters(c)
        when (viewMode) {
            VIEW_BOARD -> boardView(c)
            VIEW_LIST -> listView(c)
            VIEW_DATES -> datesView(c)
            else -> plansView(c)
        }
        val tabs = tabBar(this, listOf(t("▦ Доска", "▦ Board"), t("☰ Список", "☰ List"), t("🗓 Сроки", "🗓 Dates"), t("🏁 Планы", "🏁 Plans")), viewMode) {
            viewMode = it
            Lumen.prefs.edit().putInt("board_view", it).apply()
            render()
        }
        page(t("📋 Задачи", "📋 Tasks"), c.root, actions = listOf("🔍" to { search() }, "⋮" to { menu() }), header = tabs,
            fab = t("＋ Задача", "＋ Task") to { openTask(null) }, keepScroll = true)
    }

    /** Top summary: the running plan with its progress, or counts of the whole board. */
    private fun hero(c: Col) {
        val p = board.activePlan
        val box = Col(this, 16)
        box.root.background = roundBg(Palette.ACCENT_SOFT, 20f, this)
        if (p != null) {
            val items = Board.planItems(board, p.id)
            val st = Board.stats(items, today)
            val left = Board.daysLeft(p, today)
            val head = hRow()
            head.addView(text(this, "🏁 " + p.name, Type.TITLE_M, bold = true, color = Palette.ON_SOFT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, weight = 1f))
            head.addView(text(this, when {
                left < 0 -> t("срок вышел", "time is up")
                left == 0 -> t("последний день", "last day")
                else -> t("осталось $left дн.", "$left d left")
            }, 13f, bold = true, color = if (left < 0) Palette.BAD else Palette.ON_SOFT))
            box.add(head, 0)
            if (p.goal.isNotBlank()) box.add(text(this, p.goal, 13f, color = Palette.ON_SOFT), 4)
            box.add(progressBar(st.percent, Palette.ACCENT, 8), 12)
            box.add(text(this, t("Готово ${st.done} из ${st.total}", "${st.done} of ${st.total} done") +
                (if (st.points > 0) " · ◆ ${st.pointsDone}/${st.points}" else "") +
                (if (st.overdue > 0) " · " + t("🔥 просрочено ${st.overdue}", "🔥 ${st.overdue} overdue") else ""), 13f, color = Palette.ON_SOFT), 6)
            box.root.setOnClickListener { if (viewMode != VIEW_PLANS) { viewMode = VIEW_PLANS; render() } }
        } else {
            val st = Board.stats(board.items, today)
            val open = st.total - st.done
            box.add(text(this, t("Открыто $open", "$open open"), Type.TITLE_L, bold = true, color = Palette.ON_SOFT), 0)
            val chips = BStatus.entries.filter { it != BStatus.DONE }.map { s -> pill("${statusName(s)} ${st.byStatus[s] ?: 0}", STATUS_COLORS.getValue(s)) } +
                listOfNotNull(st.overdue.takeIf { it > 0 }?.let { pill(t("🔥 просрочено $it", "🔥 $it overdue"), Palette.BAD) })
            box.add(wrap(*chips.toTypedArray()), 8)
            box.add(progressBar(st.percent, STATUS_COLORS.getValue(BStatus.DONE), 6), 12)
            box.add(text(this, t("Готово ${st.done} из ${st.total} · план не запущен — «🏁 Планы»", "${st.done} of ${st.total} done · no plan running — \"🏁 Plans\""), 12f, color = Palette.ON_SOFT), 6)
        }
        c.add(box.root, 0)
    }

    private fun quickAdd(c: Col) {
        val row = hRow()
        val e = edt(this, t("Быстро: Текст !high #метка @пт ~3", "Quick: Text !high #label @fri ~3")).apply { isSingleLine = true }
        fun go() {
            val s = e.text.toString().trim()
            if (s.isEmpty()) return
            val q = Board.quick(s, today)
            if (q.title.isBlank()) return toast(t("Напишите, что сделать", "Write what to do"))
            val status = if (viewMode == VIEW_PLANS && board.activePlan == null) BStatus.BACKLOG else BStatus.TODO
            val plan = if (filter.planOnly || viewMode == VIEW_PLANS) board.activePlan?.id ?: 0 else 0
            val (b, it) = Board.add(board, q, now, status, plan)
            e.setText("")
            set(b)
            snack(t("Добавлено ${Board.key(b, it)}", "Added ${Board.key(b, it)}"), t("Открыть", "Open")) { openTask(b.item(it.id)) }
        }
        e.onEnter(search = false) { go() }
        row.addView(e, lp(0, weight = 1f))
        row.addView(iconBtn(this, "＋") { go() }, lp(dp(48), dp(48)))
        c.add(row, 12)
        c.add(text(this, t("! приоритет · # метка · @завтра/@пт/@12.11 · ~очки · «баг:» в начале — тип", "! priority · # label · @tomorrow/@fri/@12.11 · ~points · \"bug:\" first — type"), 11f, color = Palette.MUTED), 2)
    }

    private fun filters(c: Col) {
        val chips = mutableListOf<View>()
        if (filter.query.isNotEmpty()) chips += chip(this, "🔍 ${filter.query} ✕", true) { filter = filter.copy(query = ""); render() }
        if (board.activePlan != null) chips += chip(this, t("🏁 Текущий план", "🏁 Current plan"), filter.planOnly) { filter = filter.copy(planOnly = !filter.planOnly); render() }
        chips += chip(this, filter.prio?.let { prioGlyph(it) + " " + prioName(it) } ?: t("Приоритет ▾", "Priority ▾"), filter.prio != null) {
            pickOne(t("Приоритет", "Priority"), listOf<BPrio?>(null) + BPrio.entries, { it?.let { p -> prioGlyph(p) + " " + prioName(p) } ?: t("Все", "All") }) {
                filter = filter.copy(prio = it); render()
            }
        }
        chips += chip(this, filter.type?.let { typeIcon(it) + " " + typeName(it) } ?: t("Тип ▾", "Type ▾"), filter.type != null) {
            pickOne(t("Тип", "Type"), listOf<BType?>(null) + BType.entries, { it?.let { ty -> typeIcon(ty) + " " + typeName(ty) } ?: t("Все", "All") }) {
                filter = filter.copy(type = it); render()
            }
        }
        Board.labels(board).take(12).forEach { l ->
            chips += chip(this, "#$l", filter.label == l) { filter = filter.copy(label = if (filter.label == l) "" else l); render() }
        }
        if (filter != BFilter()) chips += chip(this, t("Сбросить", "Clear")) { filter = BFilter(); render() }
        c.add(chipScroller(this, chips), 12)
    }

    private fun <T> pickOne(title: String, options: List<T>, name: (T) -> String, onPick: (T) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(options.map(name).toTypedArray()) { _, i -> onPick(options[i]) }.show()
    }

    private fun search() {
        prompt(t("Найти задачу", "Find a task"), t("Слова или ключ, например ${board.prefix}-12", "Words or a key, e.g. ${board.prefix}-12"), filter.query) {
            filter = filter.copy(query = it.trim())
            render()
        }
    }

    // ---------- board ----------

    private fun boardView(c: Col) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(16), 0) }
        val width = (resources.displayMetrics.widthPixels * 0.8f).toInt().coerceAtMost(dp(320))
        for (s in BStatus.entries) {
            val items = Board.column(board, s, filter)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(12))
                background = roundBg(soft(STATUS_COLORS.getValue(s), 0x14), 18f, this@BoardActivity)
            }
            val head = hRow()
            head.addView(View(this).apply { background = roundBg(STATUS_COLORS.getValue(s), 5f, this@BoardActivity) }, lp(dp(10), dp(10)))
            head.addView(text(this, statusName(s).uppercase(), 13f, bold = true, color = Palette.ON_SURFACE).apply {
                setPadding(dp(8), 0, dp(6), 0); letterSpacing = 0.04f
            })
            head.addView(pill("${items.size}", STATUS_COLORS.getValue(s)))
            head.addView(View(this), lp(0, 1, 1f))
            head.addView(iconBtn(this, "＋", Palette.MUTED) {
                openTask(null, BItem(0, "", status = s, plan = if (filter.planOnly) board.activePlan?.id ?: 0 else 0))
            }, lp(dp(40), dp(40)))
            col.addView(head)
            val shown = if (s == BStatus.DONE && !showDone) items.take(5) else items
            shown.forEach { col.addView(card(it, onBoard = true), lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(8) }) }
            if (shown.size < items.size) col.addView(btn(this, t("Показать все ${items.size}", "Show all ${items.size}"), style = BtnStyle.TEXT) { showDone = true; render() })
            if (items.isEmpty()) col.addView(text(this, t("Пусто — нажмите ＋", "Empty — tap ＋"), 13f, color = Palette.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(col, lp(width).apply { marginEnd = dp(10) })
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }
        c.add(scroller, 16)
        c.add(text(this, t("Листайте колонки вбок. ‹ › — сдвинуть по статусу, долгое нажатие — действия.", "Swipe the columns sideways. ‹ › move by status, long press — actions."), 11f, color = Palette.MUTED), 6)
    }

    /** The task card: type · key · priority, title, chips (labels, date, checklist, points, epic, comments). */
    private fun card(it: BItem, onBoard: Boolean): View {
        val done = it.status == BStatus.DONE
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ripple(this@BoardActivity, roundBg(if (onBoard) Palette.SURFACE else Palette.CARD, 14f, this@BoardActivity,
                soft(Palette.OUTLINE, 0x30)), Palette.ON_SURFACE)
            elevation = if (onBoard) dp(1).toFloat() else 0f
            contentDescription = "${Board.key(board, it)}. ${it.title}. ${statusName(it.status)}. ${prioName(it.prio)}"
            setOnClickListener { _ -> openTask(board.item(it.id)) }
            setOnLongClickListener { _ -> actions(it); true }
        }
        val top = hRow()
        top.addView(text(this, typeIcon(it.type), 13f))
        top.addView(text(this, Board.key(board, it), 12f, bold = true, color = Palette.MUTED).apply { setPadding(dp(6), 0, 0, 0) }, lp(0, weight = 1f))
        top.addView(text(this, prioGlyph(it.prio), 12f, bold = true, color = ink(PRIO_COLORS.getValue(it.prio))).apply { contentDescription = prioName(it.prio) })
        box.addView(top)
        box.addView(text(this, it.title, 15f, color = if (done) Palette.MUTED else Palette.ON_SURFACE).apply {
            typeface = Type.medium
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setPadding(0, dp(4), 0, 0)
        })
        val meta = mutableListOf<View>()
        if (!onBoard) meta += pill(statusName(it.status), STATUS_COLORS.getValue(it.status))
        it.labels.forEach { l -> meta += pill("#$l", labelColor(l)) }
        if (it.due > 0) {
            val color = when {
                done -> Palette.OUTLINE
                Board.overdue(it, today) -> 0xFFE53935.toInt()
                it.due == today -> 0xFFF59E0B.toInt()
                else -> Palette.OUTLINE
            }
            meta += pill("🗓 " + dayLabel(it.due), color, filled = Board.overdue(it, today) || it.due == today)
        }
        if (it.checks.isNotEmpty()) meta += pill("☑ ${it.checksDone}/${it.checks.size}", if (it.checksDone == it.checks.size) STATUS_COLORS.getValue(BStatus.DONE) else Palette.OUTLINE, filled = false)
        if (it.points > 0) meta += pill("◆ ${it.points}", Palette.OUTLINE, filled = false)
        board.item(it.epic)?.let { e -> meta += pill("⚡ " + e.title.take(18), 0xFF8B5CF6.toInt()) }
        if (it.type == BType.EPIC) Board.epicProgress(board, it.id)?.let { p -> meta += pill("⚡ $p %", 0xFF8B5CF6.toInt(), filled = false) }
        val talk = it.notes.count { n -> !n.auto }
        if (talk > 0) meta += pill("💬 $talk", Palette.OUTLINE, filled = false)
        if (!onBoard && it.plan > 0) board.plan(it.plan)?.takeIf { p -> p.state != "closed" }?.let { p -> meta += pill("🏁 " + p.name.take(16), Palette.ACCENT, filled = false) }
        if (meta.isNotEmpty()) box.addView(wrap(*meta.toTypedArray()), lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(8) })
        if (it.checks.isNotEmpty() && !done) box.addView(progressBar(it.checksDone * 100 / it.checks.size, STATUS_COLORS.getValue(BStatus.DONE), 4),
            lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(8) })
        if (onBoard) {
            val nav = hRow()
            nav.addView(View(this), lp(0, 1, 1f))
            if (it.status != BStatus.BACKLOG) nav.addView(miniBtn("‹", t("Назад: ", "Back: ") + statusName(Board.step(it.status, -1))) { set(Board.move(board, it.id, Board.step(it.status, -1), now)) })
            if (it.status != BStatus.DONE) nav.addView(miniBtn(if (Board.step(it.status, 1) == BStatus.DONE) "✓" else "›",
                t("Дальше: ", "Next: ") + statusName(Board.step(it.status, 1))) { moveForward(it) })
            box.addView(nav, lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(4) })
        }
        return box
    }

    private fun miniBtn(s: String, desc: String, onClick: () -> Unit): View = text(this, s, 16f, bold = true, color = Palette.ACCENT).apply {
        gravity = Gravity.CENTER
        contentDescription = desc
        background = ripple(this@BoardActivity, roundBg(soft(Palette.ACCENT, 0x1A), 16f, this@BoardActivity), Palette.ON_SURFACE)
        minWidth = dp(44)
        minHeight = dp(36)
        setOnClickListener { onClick() }
    }.also { v -> v.layoutParams = lp(dp(44), dp(36)).apply { marginStart = dp(6) } }

    private fun moveForward(it: BItem) {
        val to = Board.step(it.status, 1)
        val open = it.checks.count { c -> !c.done }
        if (to == BStatus.DONE && open > 0) {
            confirm(t("Закрыть задачу?", "Close the task?"), t("Не отмечено шагов: $open.", "$open steps are not checked."), t("Готово", "Done")) {
                set(Board.move(board, it.id, to, now))
            }
            return
        }
        set(Board.move(board, it.id, to, now))
        if (to == BStatus.DONE) snack(t("✓ ${Board.key(board, it)} готово", "✓ ${Board.key(board, it)} done"))
    }

    /** Long press: move anywhere, order, plan, duplicate, delete. */
    private fun actions(it: BItem) {
        bottomSheet(Board.key(board, it) + " · " + it.title.take(40)) { dismiss ->
            add(text(ctx, t("Перенести в", "Move to"), Type.LABEL, bold = true, color = Palette.MUTED), 4)
            add(choiceChips(ctx, BStatus.entries.map { s -> s to statusName(s) }, it.status) { s -> dismiss(); set(Board.move(board, it.id, s, now)) }, 4)
            row(btn(ctx, t("↑ Выше", "↑ Up"), style = BtnStyle.TONAL) { set(Board.shift(board, it.id, -1)) },
                btn(ctx, t("↓ Ниже", "↓ Down"), style = BtnStyle.TONAL) { set(Board.shift(board, it.id, 1)) })
            val plans = board.plans.filter { p -> p.state != "closed" }
            if (plans.isNotEmpty()) {
                add(text(ctx, t("План", "Plan"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
                add(choiceChips(ctx, listOf(0 to t("Бэклог", "Backlog")) + plans.map { p -> p.id to p.name }, it.plan) { id ->
                    dismiss(); board.item(it.id)?.let { x -> set(Board.update(board, x.copy(plan = id), now)) }
                }, 4)
            }
            button(t("✎ Открыть", "✎ Open"), style = BtnStyle.TONAL) { dismiss(); openTask(board.item(it.id)) }
            button(t("⧉ Дублировать", "⧉ Duplicate"), style = BtnStyle.TONAL) {
                dismiss()
                Board.duplicate(board, it.id, now, t("(копия)", "(copy)"))?.let { (b, x) -> set(b); snack(t("Создано ${Board.key(b, x)}", "Created ${Board.key(b, x)}")) }
            }
            button(t("🗑 Удалить", "🗑 Delete"), style = BtnStyle.TEXT) { dismiss(); delete(it.id) }
        }
    }

    private fun delete(id: Int) {
        val before = board
        val it = before.item(id) ?: return
        undoable(t("Удалено ${Board.key(before, it)}", "Deleted ${Board.key(before, it)}"),
            apply = { set(Board.remove(before, id)) },
            revert = { set(before) })
    }

    // ---------- list ----------

    private fun listView(c: Col) {
        val all = Board.filter(board, filter)
        if (all.isEmpty()) return emptyState(c)
        for (s in BStatus.entries) {
            val items = Board.ranked(all.filter { it.status == s })
            if (items.isEmpty()) continue
            groupHead(c, statusName(s), items.size, STATUS_COLORS.getValue(s))
            val shown = if (s == BStatus.DONE && !showDone) items.take(3) else items
            shown.forEach { c.add(row(it), 6) }
            if (shown.size < items.size) c.add(btn(this, t("Показать все ${items.size}", "Show all ${items.size}"), style = BtnStyle.TEXT) { showDone = true; render() }, 0)
        }
    }

    private fun groupHead(c: Col, title: String, count: Int, color: Int) {
        val h = hRow()
        h.addView(View(this).apply { background = roundBg(color, 5f, this@BoardActivity) }, lp(dp(10), dp(10)))
        h.addView(text(this, title, Type.TITLE_M, bold = true).apply { setPadding(dp(8), 0, dp(8), 0); heading() })
        h.addView(pill("$count", color))
        c.add(h, 20)
    }

    /** Compact list row: priority, key, title, status / date. */
    private fun row(it: BItem): View {
        val done = it.status == BStatus.DONE
        val r = hRow().apply {
            setPadding(dp(12), dp(10), dp(8), dp(10))
            minimumHeight = dp(56)
            background = ripple(this@BoardActivity, roundBg(Palette.CARD, 14f, this@BoardActivity), Palette.ON_SURFACE)
            contentDescription = "${Board.key(board, it)}. ${it.title}. ${statusName(it.status)}"
            setOnClickListener { _ -> openTask(board.item(it.id)) }
            setOnLongClickListener { _ -> actions(it); true }
        }
        val check = CheckBox(this).apply {
            isChecked = done
            contentDescription = t("Готово", "Done")
            setOnClickListener { _ -> if (done) set(Board.move(board, it.id, BStatus.TODO, now)) else moveForwardTo(it, BStatus.DONE) }
        }
        r.addView(check)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = hRow()
        top.addView(text(this, typeIcon(it.type) + " " + Board.key(board, it), 12f, bold = true, color = Palette.MUTED))
        top.addView(text(this, "  " + prioGlyph(it.prio), 12f, bold = true, color = ink(PRIO_COLORS.getValue(it.prio))))
        mid.addView(top)
        mid.addView(text(this, it.title, 15f, color = if (done) Palette.MUTED else Palette.ON_SURFACE).apply {
            typeface = Type.medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })
        val meta = mutableListOf<View>()
        it.labels.take(3).forEach { l -> meta += pill("#$l", labelColor(l)) }
        if (it.checks.isNotEmpty()) meta += pill("☑ ${it.checksDone}/${it.checks.size}", Palette.OUTLINE, filled = false)
        if (meta.isNotEmpty()) mid.addView(wrap(*meta.toTypedArray()), lp(ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(4) })
        r.addView(mid, lp(0, weight = 1f).apply { marginStart = dp(4) })
        if (it.due > 0 && !done) {
            val od = Board.overdue(it, today)
            r.addView(text(this, dayLabel(it.due), 12f, bold = od, color = if (od) Palette.BAD else Palette.MUTED).apply { setPadding(dp(6), 0, 0, 0) })
        }
        return r
    }

    private fun moveForwardTo(it: BItem, to: BStatus) {
        set(Board.move(board, it.id, to, now))
        if (to == BStatus.DONE) snack(t("✓ ${Board.key(board, it)} готово", "✓ ${Board.key(board, it)} done"), t("Отменить", "Undo")) {
            set(Board.move(board, it.id, it.status, now))
        }
    }

    private fun emptyState(c: Col) {
        if (filter != BFilter()) c.empty(t("Ничего не найдено", "Nothing found"), t("Под эти фильтры задач нет.", "No tasks match these filters."), t("Сбросить фильтры", "Clear filters")) { filter = BFilter(); render() }
        else c.empty(t("Задач пока нет", "No tasks yet"), t("Добавьте первую строкой выше или кнопкой «＋ Задача».", "Add the first one in the line above or with \"＋ Task\"."), t("＋ Задача", "＋ Task")) { openTask(null) }
    }

    // ---------- dates ----------

    private fun datesView(c: Col) {
        val groups = Board.byDue(Board.filter(board, filter), today)
        if (groups.isEmpty()) return emptyState(c)
        // a strip of the next 7 days with the number of tasks on each
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val open = Board.filter(board, filter).filter { it.status != BStatus.DONE }
        for (d in today until today + 7) {
            val n = open.count { it.due == d }
            val on = d == today
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                background = roundBg(if (on) Palette.ACCENT else Palette.CARD, 14f, this@BoardActivity)
                addView(text(this@BoardActivity, weekdayShort(d), 11f, color = if (on) Palette.ON_ACCENT else Palette.MUTED).apply { gravity = Gravity.CENTER })
                addView(text(this@BoardActivity, Board.civil(d).third.toString(), Type.TITLE_M, bold = true, color = if (on) Palette.ON_ACCENT else Palette.ON_SURFACE).apply { gravity = Gravity.CENTER })
                addView(text(this@BoardActivity, if (n > 0) "•".repeat(minOf(n, 4)) else " ", 12f, bold = true, color = if (on) Palette.ON_ACCENT else Palette.ACCENT).apply { gravity = Gravity.CENTER })
                contentDescription = dayLabel(d) + ": " + n
            }
            strip.addView(cell, lp(0, weight = 1f).apply { if (d > today) marginStart = dp(4) })
        }
        c.add(strip, 16)
        groups.forEach { (g, items) ->
            groupHead(c, dueName(g), items.size, when (g) {
                BDue.OVERDUE -> 0xFFE53935.toInt()
                BDue.TODAY -> 0xFFF59E0B.toInt()
                BDue.NONE -> Palette.OUTLINE
                else -> Palette.ACCENT
            })
            items.forEach { c.add(row(it), 6) }
        }
    }

    // ---------- plans ----------

    private fun plansView(c: Col) {
        val active = board.activePlan
        if (active != null) {
            c.h2(t("Идёт сейчас", "Running now"))
            planCard(c, active)
        }
        val planned = board.plans.filter { it.state == "planned" }
        c.h2(t("Следующие планы", "Next plans"))
        if (planned.isEmpty()) c.muted(t("План — отрезок времени (обычно 1–2 недели) с целью. В него берут задачи из бэклога, а в конце закрывают: недоделанное возвращается.",
            "A plan is a time box (usually 1–2 weeks) with a goal. It takes tasks from the backlog; when closed, unfinished work goes back."))
        planned.forEach { planCard(c, it) }
        c.row(btn(this, t("＋ Новый план", "＋ New plan"), style = BtnStyle.TONAL) { editPlan(null) },
            btn(this, t("✨ План с ИИ", "✨ Plan with AI"), style = BtnStyle.TONAL) { aiPlan() })

        val backlog = Board.ranked(Board.filter(board, filter).filter { it.plan == 0 && it.status != BStatus.DONE })
        groupHead(c, t("Бэклог — без плана", "Backlog — no plan"), backlog.size, STATUS_COLORS.getValue(BStatus.BACKLOG))
        val target = active ?: planned.firstOrNull()
        if (backlog.isEmpty()) c.muted(t("Все открытые задачи уже в планах.", "All open tasks are in plans."))
        backlog.forEach { x ->
            val r = row(x)
            if (target != null && r is LinearLayout) r.addView(miniBtn("→", t("В план ", "To plan ") + target.name) {
                set(Board.update(board, x.copy(plan = target.id, status = if (x.status == BStatus.BACKLOG) BStatus.TODO else x.status), now))
                snack(t("${Board.key(board, x)} → ${target.name}", "${Board.key(board, x)} → ${target.name}"))
            })
            c.add(r, 6)
        }
        val closed = board.plans.filter { it.state == "closed" }.reversed()
        if (closed.isNotEmpty()) c.section(t("Закрытые планы", "Closed plans"), "${closed.size}", showClosed, { showClosed = it; render() }) {
            closed.forEach { p ->
                val items = Board.planItems(board, p.id)
                val st = Board.stats(items, today)
                card {
                    add(text(ctx, "🏁 " + p.name, Type.TITLE_M, bold = true), 0)
                    add(text(ctx, "${dateShort(p.start)} — ${dateShort(p.end)} · " + t("сделано ${st.done}", "${st.done} done"), 13f, color = Palette.MUTED), 4)
                    if (p.goal.isNotBlank()) add(text(ctx, p.goal, 13f), 4)
                    root.setOnLongClickListener { confirm(t("Удалить план?", "Delete the plan?"), p.name, t("Удалить", "Delete"), danger = true) { set(Board.removePlan(board, p.id)) }; true }
                }
            }
        }
    }

    private fun planCard(c: Col, p: BPlan) {
        val items = Board.planItems(board, p.id)
        val st = Board.stats(items, today)
        val running = p.state == "active"
        c.card {
            val head = hRow()
            head.addView(text(ctx, "🏁 " + p.name, Type.TITLE_M, bold = true), lp(0, weight = 1f))
            head.addView(pill(if (running) t("идёт", "running") else t("в очереди", "queued"), if (running) STATUS_COLORS.getValue(BStatus.DONE) else Palette.OUTLINE))
            add(head, 0)
            add(text(ctx, "${dateShort(p.start)} — ${dateShort(p.end)} · " + t("${p.end - p.start + 1} дн.", "${p.end - p.start + 1} d"), 13f, color = Palette.MUTED), 4)
            if (p.goal.isNotBlank()) add(text(ctx, "🎯 " + p.goal, 14f), 8)
            add(progressBar(st.percent, Palette.ACCENT, 8), 12)
            add(text(ctx, t("Готово ${st.done} из ${st.total}", "${st.done} of ${st.total} done") + (if (st.points > 0) " · ◆ ${st.pointsDone}/${st.points}" else ""), 13f, color = Palette.MUTED), 4)
            if (running) {
                val burn = Board.burndown(board, p, today, tz)
                if (burn.size >= 2 || (burn.isNotEmpty() && st.total > 0)) {
                    add(text(ctx, t("Сгорание: пунктир — идеально, линия — как идёт", "Burndown: dashed — ideal, line — actual"), 11f, color = Palette.MUTED), 12)
                    add(BurnView(ctx, burn, p.end - p.start, Palette.ACCENT, Palette.OUTLINE), 4).layoutParams.height = dp(110)
                }
                val chips = BStatus.entries.map { s -> pill("${statusName(s)} ${st.byStatus[s] ?: 0}", STATUS_COLORS.getValue(s)) }
                add(wrap(*chips.toTypedArray()), 8)
            }
            val row = hRow()
            if (running) row.addView(btn(ctx, t("✓ Завершить", "✓ Complete"), primary = true) { closePlan(p) }, lp(0, weight = 1f))
            else row.addView(btn(ctx, t("▶ Начать", "▶ Start"), primary = true) { startPlan(p) }, lp(0, weight = 1f))
            row.addView(btn(ctx, t("Задачи", "Tasks"), style = BtnStyle.TONAL) {
                filter = BFilter(planOnly = running); if (!running) toast(t("Задачи плана — в списке с меткой 🏁", "Plan tasks are marked 🏁 in the list"))
                viewMode = if (running) VIEW_BOARD else VIEW_LIST; render()
            }, lp(0, weight = 1f).apply { marginStart = dp(8) })
            row.addView(iconBtn(ctx, "✎") { editPlan(p) }, lp(dp(48), dp(48)))
            add(row, 12)
        }
    }

    private fun startPlan(p: BPlan) {
        val running = board.activePlan
        val go = {
            set(Board.startPlan(board, p.id, today))
            snack(t("План «${p.name}» запущен", "Plan \"${p.name}\" started"))
        }
        if (running != null) confirm(t("Запустить «${p.name}»?", "Start \"${p.name}\"?"), t("«${running.name}» вернётся в очередь (не закроется).", "\"${running.name}\" goes back to the queue (not closed)."), t("Запустить", "Start")) { go() }
        else go()
    }

    private fun closePlan(p: BPlan) {
        val open = Board.planItems(board, p.id).filter { it.status != BStatus.DONE }
        if (open.isEmpty()) {
            set(Board.closePlan(board, p.id, 0, now)); snack(t("🎉 План закрыт — всё сделано", "🎉 Plan closed — all done")); return
        }
        val next = board.plans.filter { it.state == "planned" && it.id != p.id }
        bottomSheet(t("Завершить «${p.name}»", "Complete \"${p.name}\"")) { dismiss ->
            muted(t("Не доделано: ${open.size}. Куда их перенести?", "Unfinished: ${open.size}. Where to move them?"))
            button(t("↩ В бэклог", "↩ To the backlog"), primary = true) { dismiss(); set(Board.closePlan(board, p.id, 0, now)) }
            next.forEach { n -> button("→ " + n.name, style = BtnStyle.TONAL) { dismiss(); set(Board.closePlan(board, p.id, n.id, now)) } }
        }
    }

    private fun editPlan(old: BPlan?) {
        bottomSheet(if (old == null) t("Новый план", "New plan") else t("План", "Plan")) { dismiss ->
            val n = board.plans.size + 1
            val name = edit(t("Название", "Name"), old?.name ?: t("Неделя $n", "Week $n"))
            val goal = edit(t("Цель: что будет готово к концу", "Goal: what is done by the end"), old?.goal.orEmpty(), multiline = true)
            var days = old?.let { it.end - it.start + 1 } ?: 7
            add(text(ctx, t("Длина", "Length"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val opts = listOf(3, 7, 14, 21, 28).let { if (days in it) it else (it + days).sorted() }
            add(choiceChips(ctx, opts.map { d -> d to (if (d % 7 == 0) t("${d / 7} нед.", "${d / 7} wk") else t("$d дн.", "$d d")) }, days) { days = it }, 4)
            button(t("Сохранить", "Save"), primary = true) {
                dismiss()
                if (old == null) {
                    val start = board.plans.filter { it.state != "closed" }.maxOfOrNull { it.end + 1 }?.coerceAtLeast(today) ?: today
                    set(Board.addPlan(board, name.text.toString(), goal.text.toString(), start, days).first)
                } else set(Board.updatePlan(board, old.copy(name = name.text.toString().trim().ifEmpty { old.name }, goal = goal.text.toString().trim(), end = old.start + days - 1)))
            }
            if (old != null) button(t("🗑 Удалить план", "🗑 Delete the plan"), style = BtnStyle.TEXT) {
                dismiss()
                val before = board
                undoable(t("План удалён, задачи — в бэклоге", "Plan deleted, tasks are in the backlog"), apply = { set(Board.removePlan(before, old.id)) }, revert = { set(before) })
            }
        }
    }

    // ---------- the task sheet ----------

    /** Opens a task ([old]) or a new one with the [preset] fields. */
    private fun openTask(old: BItem?, preset: BItem? = null) {
        val base = old ?: preset ?: BItem(0, "", plan = if (filter.planOnly) board.activePlan?.id ?: 0 else 0)
        var status = base.status
        var type = base.type
        var prio = base.prio
        var due = base.due
        var points = base.points
        var plan = base.plan
        var epic = base.epic
        val checks = base.checks.toMutableList()
        bottomSheet(if (old == null) t("Новая задача", "New task") else typeIcon(old.type) + " " + Board.key(board, old)) { dismiss ->
            val title = edit(t("Что сделать", "What to do"), base.title, multiline = true)

            add(text(ctx, t("Статус", "Status"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, BStatus.entries.map { it to statusName(it) }, status) { status = it }, 4)
            add(text(ctx, t("Тип", "Type"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, BType.entries.map { it to typeIcon(it) + " " + typeName(it) }, type) { type = it }, 4)
            add(text(ctx, t("Приоритет", "Priority"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, BPrio.entries.map { it to prioGlyph(it) + " " + prioName(it) }, prio) { prio = it }, 4)

            add(text(ctx, t("Срок", "Due"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val dueBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun fillDue() {
                dueBox.removeAllViews()
                val opts = listOf(0 to t("Нет", "None"), today to t("Сегодня", "Today"), today + 1 to t("Завтра", "Tomorrow"),
                    today + Math.floorMod(4 - Board.weekday(today), 7).let { if (it == 0) 7 else it } to t("Пятница", "Friday"), today + 7 to t("+ неделя", "+ week"))
                val chips = opts.map { (d, l) -> chip(ctx, l, due == d) { due = d; fillDue() } }.toMutableList()
                chips += chip(ctx, if (due > 0 && opts.none { it.first == due }) "🗓 " + dateShort(due) else t("🗓 Дата…", "🗓 Date…"), due > 0 && opts.none { it.first == due }) {
                    val (y, m, d) = Board.civil(if (due > 0) due else today)
                    DatePickerDialog(this@BoardActivity, { _, yy, mm, dd -> due = Board.epochDay(yy, mm + 1, dd); fillDue() }, y, m - 1, d).show()
                }
                dueBox.addView(chipRow(ctx, chips, 99))
            }
            fillDue()
            add(dueBox, 4)

            add(text(ctx, t("Оценка (очки)", "Estimate (points)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(0, 1, 2, 3, 5, 8, 13).let { if (points in it) it else (it + points).sorted() }.map { it to if (it == 0) "—" else "◆ $it" }, points) { points = it }, 4)

            val labels = edit(t("Метки через запятую", "Labels, comma separated"), base.labels.joinToString(", "))
            Board.labels(board).filter { it !in base.labels }.take(10).takeIf { it.isNotEmpty() }?.let { known ->
                add(chipRow(ctx, known.map { l -> chip(ctx, "#$l") {
                    val cur = Board.parseLabels(labels.text.toString())
                    if (l !in cur) labels.setText((cur + l).joinToString(", "))
                } }, 99), 6)
            }

            val plans = board.plans.filter { it.state != "closed" || it.id == plan }
            if (plans.isNotEmpty()) {
                add(text(ctx, t("План", "Plan"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
                add(choiceChips(ctx, listOf(0 to t("Бэклог", "Backlog")) + plans.map { it.id to "🏁 " + it.name }, plan) { plan = it }, 4)
            }
            val epics = board.epics.filter { it.id != base.id }
            if (epics.isNotEmpty()) {
                add(text(ctx, t("Эпик", "Epic"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
                add(choiceChips(ctx, listOf(0 to t("Нет", "None")) + epics.map { it.id to "⚡ " + it.title.take(24) }, epic) { epic = it }, 4)
            }

            val desc = edit(t("Описание, ссылки, заметки", "Description, links, notes"), base.desc, multiline = true)

            // checklist
            add(text(ctx, t("Шаги", "Steps"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun fillChecks() {
                list.removeAllViews()
                checks.forEachIndexed { i, ch ->
                    val r = hRow()
                    r.addView(CheckBox(ctx).apply {
                        text = ch.text
                        isChecked = ch.done
                        setTextColor(if (ch.done) Palette.MUTED else Palette.ON_SURFACE)
                        setOnCheckedChangeListener { _, v -> checks[i] = checks[i].copy(done = v); setTextColor(if (v) Palette.MUTED else Palette.ON_SURFACE) }
                    }, lp(0, weight = 1f))
                    r.addView(iconBtn(ctx, "✕", Palette.MUTED) { checks.removeAt(i); fillChecks() }, lp(dp(40), dp(40)))
                    list.addView(r)
                }
            }
            fillChecks()
            add(list, 4)
            val stepRow = hRow()
            val step = edt(ctx, t("＋ Шаг", "＋ Step")).apply { isSingleLine = true }
            fun addStep() {
                val s = step.text.toString().trim()
                if (s.isEmpty()) return
                checks += BCheck(s.take(200)); step.setText(""); fillChecks()
            }
            step.onEnter(search = false) { addStep() }
            stepRow.addView(step, lp(0, weight = 1f))
            stepRow.addView(iconBtn(ctx, "＋") { addStep() }, lp(dp(48), dp(48)))
            add(stepRow, 4)
            val aiBtn = button(t("✨ Разбить на шаги с ИИ", "✨ Break into steps with AI"), style = BtnStyle.TEXT) {}
            aiBtn.setOnClickListener {
                val what = title.text.toString().trim()
                if (what.isEmpty()) return@setOnClickListener toast(t("Сначала напишите задачу", "Write the task first"))
                aiBtn.isEnabled = false
                aiBtn.text = t("ИИ думает…", "The AI is thinking…")
                aiSteps(what, desc.text.toString(), { ex -> aiBtn.isEnabled = true; aiBtn.text = t("✨ Разбить на шаги с ИИ", "✨ Break into steps with AI"); if (!isDestroyed) err(ex) }) { steps ->
                    aiBtn.isEnabled = true
                    aiBtn.text = t("✨ Ещё шаги с ИИ", "✨ More steps with AI")
                    steps.filter { s -> checks.none { it.text.equals(s, true) } }.forEach { checks += BCheck(it) }
                    fillChecks()
                }
            }

            fun collect(): BItem? {
                val s = title.text.toString().trim().replace(Regex("\\s+"), " ")
                if (s.isEmpty()) { toast(t("Напишите, что сделать", "Write what to do")); return null }
                return base.copy(title = s.take(200), desc = desc.text.toString().trim(), status = status, type = type, prio = prio, due = due,
                    points = points, plan = plan, epic = if (type == BType.EPIC) 0 else epic, labels = Board.parseLabels(labels.text.toString()), checks = checks.toList())
            }

            if (old != null) {
                // comments and history
                add(text(ctx, t("Комментарии", "Comments"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
                old.notes.filter { !it.auto }.forEach { n ->
                    val b = Col(ctx, 12)
                    b.root.background = roundBg(Palette.CARD, 12f, ctx)
                    b.add(text(ctx, whenText(n.at), 11f, color = Palette.MUTED), 0)
                    b.add(text(ctx, n.text, 14f).apply { setTextIsSelectable(true) }, 2)
                    add(b.root, 6)
                }
                val cRow = hRow()
                val cEdit = edt(ctx, t("Написать комментарий…", "Write a comment…"))
                cRow.addView(cEdit, lp(0, weight = 1f))
                cRow.addView(iconBtn(ctx, "➤") {
                    val s = cEdit.text.toString()
                    if (s.isBlank()) return@iconBtn
                    val cur = collect() ?: return@iconBtn
                    dismiss()
                    set(Board.comment(Board.update(board, cur, now), old.id, s, now))
                    openTask(board.item(old.id))
                }, lp(dp(48), dp(48)))
                add(cRow, 4)
                val hist = old.notes.filter { it.auto }
                if (hist.isNotEmpty()) {
                    add(text(ctx, t("История", "History"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
                    hist.takeLast(8).reversed().forEach { n -> add(text(ctx, "• " + whenText(n.at) + " — " + historyText(n), 12f, color = Palette.MUTED), 2) }
                }
                add(text(ctx, t("Создано ", "Created ") + whenText(old.created) + " · " + t("изменено ", "updated ") + whenText(old.updated), 11f, color = Palette.MUTED), 12)
            }

            button(if (old == null) t("Создать", "Create") else t("Сохранить", "Save"), primary = true) {
                val cur = collect() ?: return@button
                dismiss()
                if (old == null) {
                    val (b, x) = Board.add(board, BQuick(cur.title, cur.prio, cur.type, cur.labels, cur.due, cur.points), now, cur.status, cur.plan, cur.epic, cur.desc, cur.checks)
                    set(b)
                    snack(t("Создано ${Board.key(b, x)}", "Created ${Board.key(b, x)}"))
                } else set(Board.update(board, cur, now))
            }
            if (old != null) row(
                btn(ctx, t("⧉ Дубль", "⧉ Duplicate"), style = BtnStyle.TONAL) {
                    dismiss(); Board.duplicate(board, old.id, now, t("(копия)", "(copy)"))?.let { (b, x) -> set(b); openTask(b.item(x.id)) }
                },
                btn(ctx, t("⤴ Поделиться", "⤴ Share"), style = BtnStyle.TONAL) { shareText(Board.key(board, old), itemText(collect() ?: old)) },
                btn(ctx, t("🗑", "🗑"), style = BtnStyle.TEXT) { dismiss(); delete(old.id) }.apply { contentDescription = t("Удалить", "Delete") }
            )
        }
    }

    private fun itemText(it: BItem): String = buildString {
        append("${Board.key(board, it)} ${typeIcon(it.type)} ${it.title}\n")
        append("${statusName(it.status)} · ${prioName(it.prio)}")
        if (it.due > 0) append(" · 🗓 ${dateShort(it.due)}")
        if (it.labels.isNotEmpty()) append(" · " + it.labels.joinToString(" ") { "#$it" })
        append('\n')
        if (it.desc.isNotBlank()) append("\n${it.desc}\n")
        if (it.checks.isNotEmpty()) append("\n" + it.checks.joinToString("\n") { c -> (if (c.done) "☑ " else "☐ ") + c.text } + "\n")
    }

    // ---------- AI ----------

    private fun aiLang() = t("русский", Locale.getDefault().getDisplayLanguage(Locale.ENGLISH))

    private fun aiSteps(task: String, desc: String, onError: (Throwable) -> Unit, onDone: (List<String>) -> Unit) {
        Lumen.async({
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.fastModel, "You plan practical work for a video creator. Answer with JSON only.",
                Prompts.fill("studio_board_steps", mapOf("lang" to aiLang(), "task" to task, "desc" to desc.take(1500))),
                json = true, maxTokens = 800, task = "studio_board_steps", pid = "studio")
            Board.parseAi(r.text, today).flatMap { (q, c) -> if (c.isNotEmpty()) c.map { it.text } else listOf(q.title) }
                .ifEmpty { StudioEdit.parseField(r.text, "steps") }.take(12)
        }, onError, onDone)
    }

    /** A goal → a list of tasks from the AI; the author ticks which to add (to the backlog or a plan). */
    private fun aiPlan() {
        bottomSheet(t("✨ План с ИИ", "✨ Plan with AI")) { dismiss ->
            muted(t("Опишите цель — ИИ разобьёт её на задачи со сроками и шагами. Вы выберете, что добавить.",
                "Describe the goal — the AI splits it into tasks with dates and steps. You choose what to add."))
            val goal = edit(t("Цель, например: выпустить 3 Shorts про кофе за неделю", "Goal, e.g.: publish 3 Shorts about coffee this week"),
                Lumen.prefs.getString("board_ai_goal", "").orEmpty(), multiline = true)
            val out = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val go = button(t("✨ Составить", "✨ Make the plan"), primary = true) {}
            add(out, 8)
            go.setOnClickListener {
                val g = goal.text.toString().trim()
                if (g.isEmpty()) return@setOnClickListener toast(t("Опишите цель", "Describe the goal"))
                Lumen.prefs.edit().putString("board_ai_goal", g).apply()
                go.isEnabled = false
                out.removeAllViews()
                out.addView(text(ctx, t("ИИ думает…", "The AI is thinking…"), 13f, color = Palette.MUTED))
                Lumen.async({
                    val prov = AiTasks.requireProvider()
                    val r = Llm.complete(prov, prov.smartModel, "You plan practical work for a video creator. Answer with JSON only.",
                        Prompts.fill("studio_board_plan", mapOf("lang" to aiLang(), "goal" to g, "board" to Board.brief(board).ifEmpty { "—" })),
                        json = true, maxTokens = 3000, task = "studio_board_plan", pid = "studio")
                    Board.parseAi(r.text, today)
                }, { ex ->
                    go.isEnabled = true
                    out.removeAllViews()
                    if (!isDestroyed) err(ex)
                }) { tasks ->
                    go.isEnabled = true
                    out.removeAllViews()
                    if (tasks.isEmpty()) { out.addView(text(ctx, t("ИИ не вернул задач — попробуйте ещё раз.", "The AI returned no tasks — try again."), 13f, color = Palette.BAD)); return@async }
                    val picked = tasks.indices.toMutableSet()
                    tasks.forEachIndexed { i, (q, steps) ->
                        out.addView(CheckBox(ctx).apply {
                            text = buildString {
                                append(typeIcon(q.type ?: BType.TASK) + " " + q.title)
                                q.prio?.let { append("  " + prioGlyph(it)) }
                                if (q.due > 0) append("  🗓 " + dayLabel(q.due))
                                if (steps.isNotEmpty()) append("  ☑ ${steps.size}")
                            }
                            isChecked = true
                            setTextColor(Palette.ON_SURFACE)
                            setOnCheckedChangeListener { _, v -> if (v) picked += i else picked -= i }
                        })
                    }
                    val target = board.activePlan ?: board.plans.firstOrNull { it.state == "planned" }
                    fun addAll(plan: Int) {
                        var b = board
                        tasks.filterIndexed { i, _ -> i in picked }.forEach { (q, steps) ->
                            b = Board.add(b, q, now, if (plan > 0) BStatus.TODO else BStatus.BACKLOG, plan, checks = steps).first
                        }
                        dismiss()
                        set(b)
                        snack(t("Добавлено задач: ${picked.size}", "Tasks added: ${picked.size}"))
                    }
                    if (target != null) out.addView(btn(ctx, t("Добавить в «${target.name}»", "Add to \"${target.name}\""), primary = true) { addAll(target.id) })
                    out.addView(btn(ctx, t("Добавить в бэклог", "Add to the backlog"), style = if (target == null) BtnStyle.FILLED else BtnStyle.TONAL) { addAll(0) })
                }
            }
        }
    }

    // ---------- menu ----------

    private fun menu() {
        bottomSheet(t("📋 Задачи", "📋 Tasks")) { dismiss ->
            button(t("✨ Разбить цель на задачи с ИИ", "✨ Split a goal into tasks with AI"), style = BtnStyle.TONAL) { dismiss(); aiPlan() }
            button(t("🏁 Новый план", "🏁 New plan"), style = BtnStyle.TONAL) { dismiss(); editPlan(null) }
            button(t("⤴ Поделиться списком задач", "⤴ Share the task list"), style = BtnStyle.TONAL) { dismiss(); shareText(t("Задачи", "Tasks"), markdown()) }
            button(t("🔑 Префикс ключей: ${board.prefix}", "🔑 Key prefix: ${board.prefix}"), style = BtnStyle.TONAL) {
                dismiss()
                prompt(t("Префикс ключей", "Key prefix"), t("Латиница или кириллица, до 6 букв", "Letters, up to 6"), board.prefix) { set(board.copy(prefix = Board.cleanPrefix(it))) }
            }
            val oldDone = board.items.filter { it.status == BStatus.DONE && it.doneAt in 1 until now - 30L * 86_400_000L }
            if (oldDone.isNotEmpty()) button(t("🧹 Убрать готовые старше 30 дней (${oldDone.size})", "🧹 Remove done older than 30 days (${oldDone.size})"), style = BtnStyle.TEXT) {
                dismiss()
                val before = board
                val ids = oldDone.map { it.id }.toSet()
                undoable(t("Убрано: ${ids.size}", "Removed: ${ids.size}"), apply = { set(before.copy(items = before.items.filter { it.id !in ids })) }, revert = { set(before) })
            }
        }
    }

    private fun markdown(): String = buildString {
        append("# " + t("Задачи", "Tasks") + "\n")
        board.activePlan?.let { p -> append("\n🏁 ${p.name} (${dateShort(p.start)} — ${dateShort(p.end)})" + (if (p.goal.isNotBlank()) ": ${p.goal}" else "") + "\n") }
        for (s in BStatus.entries) {
            val items = Board.ranked(Board.filter(board, filter).filter { it.status == s })
            if (items.isEmpty()) continue
            append("\n## ${statusName(s)} (${items.size})\n")
            items.forEach { x ->
                append("- ${if (s == BStatus.DONE) "[x]" else "[ ]"} ${Board.key(board, x)} ${x.title}")
                if (x.prio.rank <= 1) append(" ${prioGlyph(x.prio)}")
                if (x.due > 0) append(" · ${dateShort(x.due)}")
                if (x.labels.isNotEmpty()) append(" · " + x.labels.joinToString(" ") { "#$it" })
                append('\n')
            }
        }
    }
}

/** Small burndown chart: ideal (dashed) and actual remaining work of a plan by day. */
private class BurnView(ctx: Context, private val data: List<Pair<Float, Int>>, private val days: Int, private val color: Int, private val grid: Int) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ideal = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); this.color = grid; pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val axis = Paint().apply { this.color = (grid and 0x00FFFFFF) or 0x40000000; strokeWidth = 2f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = grid; textSize = ctx.dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD }

    init {
        contentDescription = data.lastOrNull()?.let { t("Осталось ${it.second}", "${it.second} left") }
    }

    override fun onDraw(c: Canvas) {
        if (data.isEmpty()) return
        val pad = context.dp(6).toFloat()
        val w = width - pad * 2
        val h = height - pad * 2 - label.textSize
        val max = maxOf(1f, data.maxOf { maxOf(it.first, it.second.toFloat()) })
        val span = maxOf(1, days).toFloat()
        fun x(i: Int) = pad + w * i / span
        fun y(v: Float) = pad + h * (1f - v / max)
        c.drawLine(pad, pad + h, pad + w, pad + h, axis)
        c.drawLine(x(0), y(max), x(days), y(0f), ideal)
        val p = Path()
        data.forEachIndexed { i, (_, left) -> if (i == 0) p.moveTo(x(i), y(left.toFloat())) else p.lineTo(x(i), y(left.toFloat())) }
        c.drawPath(p, line)
        val last = data.size - 1
        c.drawCircle(x(last), y(data[last].second.toFloat()), context.dp(4).toFloat(), dot)
        c.drawText("${data[last].second}", minOf(x(last) + context.dp(6), width - context.dp(16).toFloat()), y(data[last].second.toFloat()) - context.dp(4), label)
    }
}

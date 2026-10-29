/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import java.util.Calendar

/** Starts a focused collection: project + background task + its own result screen (a separate task in Recents). */
fun Activity.startHarvest(text: String, spec: HarvestSpec): Boolean {
    val link = YtLink.classify(text)
    if (link.type != "video" && !(link.type == "channel" && spec.mode == "channels")) {
        toast(t("Нужна ссылка на видео YouTube", "A YouTube video link is needed"))
        return false
    }
    if (spec.mode == "ai" && Provider.active() == null) {
        toast(t("Подключите AI в настройках", "Connect AI in settings"))
        return false
    }
    HarvestSpec.remember(spec)
    val p = Harvest.create(link, spec)
    if (CollectState.running) {
        toast(t("Идёт другая задача — нажмите «Запустить» на экране сбора позже", "Another task is running — tap Start on the collection screen later"))
    } else {
        CollectService.startKind(this, p.id, "harvest")
    }
    HarvestActivity.open(this, p.id)
    return true
}

private fun Activity.dialogTheme() =
    if (isNight()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

/**
 * Settings dialog of a mode before the collection starts. "related" and "channels" need only a count;
 * period / params / ai open their own choices. [onDone] runs after start or cancel.
 */
fun Activity.harvestSetup(text: String, mode: String, onDone: () -> Unit = {}) {
    val s = HarvestSpec.last(mode)
    val c = Col(this, 20)
    c.muted(when (mode) {
        "channels" -> t("Каналы, которые YouTube связывает с каналом этого видео, только по той же теме (AI-фильтр темы).",
            "Channels YouTube links to this video's channel, on the same topic only (AI topic filter).")
        "related" -> t("Видео из колонки «Далее», начиная с этого видео и дальше по цепочке.", "Videos from the “Up next” column, starting from this video and going further.")
        "period" -> t("Похожие видео, опубликованные в выбранный период.", "Similar videos published within the chosen period.")
        "params" -> t("Похожие видео, которые проходят пороги. Пустое поле — без ограничения. Можно писать 10к, 1,5 млн.",
            "Similar videos that pass the thresholds. Empty = no limit. You can type 10k, 1.5M.")
        else -> t("Каждое похожее видео проверяет AI на соответствие вашему фильтру. Тратит токены: примерно 1 запрос на 15 видео.",
            "AI checks every similar video against your filter. Uses tokens: about 1 request per 15 videos.")
    })
    var prompt: android.widget.EditText? = null
    if (mode == "ai") {
        c.add(text(this, t("Фильтр для AI", "Filter for AI"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val pe = c.edit(t("Например: обучающие разборы для новичков, без новостей и стримов", "E.g. beginner tutorials, no news or streams"), s.prompt, multiline = true)
        prompt = pe
        c.chips(listOf(
            t("только обучающие", "tutorials only"), t("без новостей и реакций", "no news or reactions"),
            t("на русском языке", "in Russian"), t("обзоры и сравнения", "reviews and comparisons")
        ).map { hint -> chip(this, hint) { pe.setText(pe.text.toString().trim() + (if (pe.text.isBlank()) "" else ", ") + hint) } })
    }

    // ---- period
    var periodKey = s.periodLabel.let { lbl -> if (lbl.startsWith("year:")) "year" else lbl.substringBefore('|').ifBlank { "1y" } }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    var year = s.periodLabel.substringAfter("year:", "").substringBefore('|').toIntOrNull() ?: thisYear
    var from: android.widget.EditText? = null
    var to: android.widget.EditText? = null
    var yearSpin: View? = null
    var customRow: View? = null
    if (mode == "period") {
        val presets = listOf(
            "1w" to t("За неделю", "Last week"), "1m" to t("За месяц", "Last month"), "3m" to t("За 3 месяца", "Last 3 months"),
            "6m" to t("За полгода", "Last 6 months"), "1y" to t("За год", "Last year"), "2y" to t("За 2 года", "Last 2 years"),
            "5y" to t("За 5 лет", "Last 5 years"), "year" to t("Конкретный год…", "Specific year…"), "custom" to t("Свой диапазон…", "Custom range…")
        )
        if (presets.none { it.first == periodKey }) periodKey = "1y"
        fun sync() {
            yearSpin?.visibility = if (periodKey == "year") View.VISIBLE else View.GONE
            customRow?.visibility = if (periodKey == "custom") View.VISIBLE else View.GONE
        }
        c.spinner(t("Период", "Period"), presets, periodKey) {
            periodKey = it
            sync()
        }
        yearSpin = c.add(spin(this, (thisYear downTo 2005).map { it to it.toString() }, year) { year = it }, 8)
        from = edt(this, t("с ГГГГ-ММ-ДД", "from YYYY-MM-DD"), if (s.fromMs > 0) Fmt.isoDay(s.fromMs) else "")
        to = edt(this, t("по ГГГГ-ММ-ДД", "to YYYY-MM-DD"), if (s.toMs > 0) Fmt.isoDay(s.toMs - HarvestRules.DAY) else "")
        customRow = c.row(from, to)
        sync()
    }

    // ---- thresholds
    var minViews: android.widget.EditText? = null
    var minSubs: android.widget.EditText? = null
    var minDur: android.widget.EditText? = null
    var maxDur: android.widget.EditText? = null
    var noShorts = s.noShorts
    if (mode == "params") {
        c.add(text(this, t("Просмотры от", "Views from"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        minViews = c.edit(t("например 10к", "e.g. 10k"), if (s.minViews > 0) s.minViews.toString() else "")
        c.add(text(this, t("Подписчики канала от", "Channel subscribers from"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        minSubs = c.edit(t("например 100к", "e.g. 100k"), if (s.minSubs > 0) s.minSubs.toString() else "")
        c.add(text(this, t("Длительность, минут: от и до", "Duration, minutes: from and to"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        minDur = edt(this, t("от", "from"), HarvestRules.minutesText(s.minDur))
        maxDur = edt(this, t("до", "to"), HarvestRules.minutesText(s.maxDur))
        c.row(minDur, maxDur)
    }
    if (mode == "params" || mode == "period" || mode == "ai") c.check(t("Без Shorts", "No Shorts"), noShorts) { noShorts = it }

    // ---- count
    c.add(text(this, if (mode == "channels") t("Сколько каналов собрать", "How many channels") else t("Сколько видео собрать", "How many videos"),
        Type.LABEL, bold = true, color = Palette.MUTED), 12)
    val count = c.edit("20", s.target.toString(), number = true)
    c.chips(listOf(10, 20, 50, 100, 200).map { n -> chip(this, n.toString()) { count.setText(n.toString()) } })

    var started = false
    val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle(HarvestRules.label(mode))
        .setView(ScrollView(this).apply { addView(c.root) })
        .setPositiveButton(t("Собрать", "Collect"), null)
        .setNegativeButton(t("Отмена", "Cancel"), null)
        .setOnDismissListener { if (!started) onDone() }
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            s.target = (count.text.toString().toIntOrNull() ?: 20).coerceIn(1, 1000)
            s.noShorts = noShorts
            s.fromMs = -1
            s.toMs = -1
            s.periodLabel = ""
            when (mode) {
                "period" -> {
                    val now = System.currentTimeMillis()
                    when (periodKey) {
                        "year" -> {
                            val r = HarvestRules.yearRange(year)
                            s.fromMs = r.first
                            s.toMs = r.second
                            s.periodLabel = "year:$year|$year"
                        }
                        "custom" -> {
                            s.fromMs = Fmt.parseIsoDay(from?.text.toString())
                            val end = Fmt.parseIsoDay(to?.text.toString())
                            s.toMs = if (end > 0) end + HarvestRules.DAY else -1
                            if (s.fromMs <= 0 && s.toMs <= 0) {
                                toast(t("Укажите даты в формате ГГГГ-ММ-ДД", "Enter dates as YYYY-MM-DD"))
                                return@setOnClickListener
                            }
                            s.periodLabel = "custom|" + (if (s.fromMs > 0) Fmt.date(s.fromMs) else "…") + " – " + (if (end > 0) Fmt.date(end) else "…")
                        }
                        else -> {
                            val r = HarvestRules.preset(periodKey, now)
                            s.fromMs = r.first
                            s.periodLabel = periodKey + "|" + Fmt.date(r.first) + " – " + t("сегодня", "today")
                        }
                    }
                }
                "params" -> {
                    s.minViews = HarvestRules.parseCount(minViews?.text.toString())
                    s.minSubs = HarvestRules.parseCount(minSubs?.text.toString())
                    s.minDur = HarvestRules.parseMinutes(minDur?.text.toString())
                    s.maxDur = HarvestRules.parseMinutes(maxDur?.text.toString())
                    if (s.maxDur in 1 until s.minDur) {
                        toast(t("«До» меньше, чем «от»", "“To” is less than “from”"))
                        return@setOnClickListener
                    }
                }
                "ai" -> {
                    s.prompt = prompt?.text.toString().trim()
                    if (s.prompt.length < 3) {
                        toast(t("Напишите фильтр", "Write a filter"))
                        return@setOnClickListener
                    }
                }
            }
            if (mode != "params") {
                s.minViews = 0
                s.minSubs = 0
                s.minDur = 0
                s.maxDur = 0
            }
            if (startHarvest(text, s)) {
                started = true
                dlg.dismiss()
                onDone()
            }
        }
    }
    dlg.show()
}

/** Result screen of a focused collection: only the found videos or channels, live while the task runs. */
class HarvestActivity : LumenBase() {
    private lateinit var pid: String
    private var sort = ""
    private var shown = 50
    private var scroller: ScrollView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (isMine()) handler.postDelayed(this, 1500)
        }
    }

    private fun isMine() = CollectState.running && CollectState.projectId == pid && CollectState.kind == "harvest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("id") ?: intent.data?.lastPathSegment ?: return finish()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val st = HarvestState.load(pid)
        val p = Store.project(pid)
        if (st == null || p == null) {
            page(t("Сбор", "Collection"), Col(this).apply { empty(t("Сбор не найден", "Collection not found"), t("Возможно, проект удалён.", "The project may have been deleted.")) }.root)
            return
        }
        val s = st.spec
        val title = HarvestRules.label(s.mode)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(title + if (s.seedTitle.isNotBlank()) ": " + s.seedTitle else ""))
        val running = isMine()
        val c = Col(this)
        if (s.seedTitle.isNotBlank()) {
            c.add(text(this, t("От: ", "From: ") + s.seedTitle, 14f, bold = true).apply {
                maxLines = 2
                setOnClickListener { openVideo(s.seed) }
            }, 0)
        }
        c.chips(HarvestRules.conditions(s).map { chip(this, it) })
        c.card {
            val head = when {
                running -> "⏳ " + t("Идёт сбор…", "Collecting…")
                st.status == "done" -> "✓ " + t("Готово", "Done")
                st.status == "stopped" -> "■ " + t("Остановлено", "Stopped")
                st.status == "error" -> "⚠ " + t("Ошибка", "Error")
                else -> t("Ожидает запуска", "Waiting to start")
            }
            add(text(ctx, head + " · " + (if (s.mode == "channels") t("каналов ", "channels ") else t("видео ", "videos ")) + "${st.items.size}/${s.target}", 15f, bold = true), 0)
            if (running && CollectState.current.isNotBlank()) muted(CollectState.current)
            else muted(t("проверено ", "checked ") + st.checked + t(" · запросов ", " · requests ") + st.requests)
            if (!running && st.note.isNotBlank()) muted(st.note)
            add(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = maxOf(1, s.target)
                progress = st.items.size
                isIndeterminate = running && st.items.isEmpty()
            }, 8)
        }
        if (running) {
            c.button(t("Остановить", "Stop"), style = BtnStyle.OUTLINED) { CollectService.stop(this) }
        } else {
            c.row(
                btn(this, if (st.status == "pending") t("Запустить", "Start") else t("Собрать ещё +${moreStep(s)}", "Collect +${moreStep(s)} more"), primary = true) {
                    if (CollectState.running) return@btn toast(t("Уже идёт другая задача", "Another task is running"))
                    if (st.status != "pending") {
                        s.target = st.items.size + moreStep(s)
                        HarvestState.save(pid, st)
                    }
                    CollectService.startKind(this, pid, "harvest")
                    handler.postDelayed(tick, 400)
                },
                btn(this, t("Новые условия", "New settings"), style = BtnStyle.TONAL) { harvestSetup(s.seed, s.mode) }
            )
        }

        if (st.items.isNotEmpty()) {
            val opts = if (s.mode == "channels") listOf("" to t("По связи", "By link"), "subs" to t("Подписчики", "Subscribers"))
            else listOf("" to t("По порядку", "In order"), "views" to t("Просмотры", "Views"), "date" to t("Новые", "Newest"), "duration" to t("Длинные", "Longest"))
            c.chips(opts.map { (k, l) -> chip(this, l, sort == k) {
                sort = k
                render()
            } })
            val list = HarvestRules.sorted(st.items.values, sort)
            list.take(shown).forEachIndexed { i, it -> c.add(if (s.mode == "channels") channelRow(i, it) else videoRow(i, it), 6) }
            if (list.size > shown) c.button(t("Показать ещё", "Show more"), style = BtnStyle.TEXT) {
                shown += 50
                render()
            }
        } else if (!running && st.status != "pending") {
            c.empty(t("Ничего не найдено", "Nothing found"), t("Ослабьте условия или увеличьте количество.", "Relax the conditions or raise the count."))
        }
        val y = scroller?.scrollY ?: 0
        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(c.root)
            post { scrollTo(0, y) }
        }
        scroller = sv
        page(title, sv, actions = listOf(
            "⤴" to { shareText(title, HarvestRules.listText(st, sort)) },
            "⋮" to { menu(st) }
        ))
    }

    private fun moreStep(s: HarvestSpec) = if (s.target >= 50) 50 else 20

    private fun videoRow(i: Int, v: HarvestItem) = videoListRow(this, ThumbUrl.mini(v.id, v.thumb), "${i + 1}. " + v.title, v.channel,
        listOf(
            Fmt.num(v.views) + t(" просм.", " views"),
            if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText,
            Fmt.duration(v.duration),
            if (v.subs >= 0) "👥 " + Fmt.num(v.subs) else "",
            v.note
        ).filter { it.isNotBlank() }.joinToString(" · ")) { openVideo(v.url) }.apply {
        setOnLongClickListener {
            itemMenu(v)
            true
        }
    }

    private fun channelRow(i: Int, ch: HarvestItem) = videoListRow(this, ch.thumb, "${i + 1}. " + ch.title,
        if (ch.subs >= 0) Fmt.num(ch.subs) + t(" подписчиков", " subscribers") else "", ch.note) {
        ChannelActivity.open(this, pid, ch.url)
    }.apply {
        setOnLongClickListener {
            openUrl(ch.url)
            true
        }
    }

    private fun itemMenu(v: HarvestItem) {
        val items = arrayOf(t("Открыть видео", "Open video"), t("Что сделать с видео…", "What to do with the video…"), t("Канал", "Channel"), t("Копировать ссылку", "Copy link"))
        AlertDialog.Builder(this).setTitle(v.title).setItems(items) { _, w ->
            when (w) {
                0 -> openVideo(v.url)
                1 -> chooseLinkAction(v.url)
                2 -> if (v.channelUrl.isNotBlank()) ChannelActivity.open(this, pid, v.channelUrl)
                3 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("url", v.url))
                    toast(t("Скопировано", "Copied"))
                }
            }
        }.show()
    }

    private fun menu(st: HarvestState) {
        val items = arrayOf(t("Открыть как проект", "Open as project"), t("Копировать список", "Copy list"), t("Все проекты", "All projects"))
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", pid))
                1 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("list", HarvestRules.listText(st, sort)))
                    toast(t("Скопировано", "Copied"))
                }
                2 -> startActivity(Intent(this, LumenActivity::class.java))
            }
        }.show()
    }

    companion object {
        /** Each collection opens as its own document task, so several results live side by side in Recents. */
        fun open(ctx: Context, pid: String) {
            ctx.startActivity(Intent(ctx, HarvestActivity::class.java).putExtra("id", pid)
                .setData(Uri.parse("lumen://harvest/$pid"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

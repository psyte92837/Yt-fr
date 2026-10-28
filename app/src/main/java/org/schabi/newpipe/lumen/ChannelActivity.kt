/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

/** Channel screen: stats, links, recent videos, playlists (tap = instant analysis), featured channels, AI summary. */
class ChannelActivity : LumenBase() {
    private lateinit var pid: String
    private lateinit var url: String
    private var data: ChannelData? = null
    private var busy = ""
    private var fullDesc = false
    private var shown = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid") ?: return finish()
        url = intent.getStringExtra("url") ?: return finish()
        data = ChannelStore.get(pid, url)
        if (data == null || data!!.recent.isEmpty()) load(false) else render()
    }

    private fun load(force: Boolean) {
        busy = t("Загрузка канала…", "Loading channel…")
        render()
        Lumen.async({ ChannelFetch.fetch(pid, url, recentLimit = QuickConfig.load().channelRecent, force = force) }, {
            busy = ""
            render()
            err(it)
        }) {
            data = it
            busy = ""
            render()
            if (it.error.isNotEmpty()) toast(it.error)
        }
    }

    private fun render() {
        val c = Col(this)
        if (busy.isNotEmpty()) c.card { add(text(ctx, busy, 14f, bold = true), 0) }
        val d = data
        if (d == null) {
            page(t("Канал", "Channel"), c.root)
            return
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(Palette.ACCENT_SOFT, 28f, this@ChannelActivity)
            clipToOutline = true
        }
        head.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) })
        Thumbs.load(img, d.avatar)
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(this, d.name.ifBlank { url } + if (d.verified) " ✓" else "", Type.TITLE_L))
        names.addView(text(this, (if (d.subscribers >= 0) Fmt.num(d.subscribers) + t(" подписчиков", " subscribers") else "") +
            " · " + t("обновлено ", "updated ") + Fmt.dateTime(d.fetchedAt), 12f, color = Palette.MUTED))
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        c.add(head, 0)
        if (d.error.isNotEmpty()) c.p("⚠ " + d.error, 13f, Palette.BAD)

        // ---- stats
        val s = d.stats
        c.chips(listOfNotNull(
            chip(this, t("видео: ", "videos: ") + s.videos),
            if (s.avgViews >= 0) chip(this, "⌀ " + Fmt.num(s.avgViews)) else null,
            if (s.medianViews >= 0) chip(this, t("медиана ", "median ") + Fmt.num(s.medianViews)) else null,
            if (s.maxViews >= 0) chip(this, "max " + Fmt.num(s.maxViews)) else null,
            if (s.perWeek > 0) chip(this, String.format("%.1f", s.perWeek) + t(" видео/нед", " videos/wk")) else null,
            if (s.lastUploadMs > 0) chip(this, t("последнее: ", "last: ") + Fmt.date(s.lastUploadMs)) else null,
            if (s.avgDurationSec > 0) chip(this, "⏱ " + Fmt.duration(s.avgDurationSec)) else null,
            if (s.shortsShare > 0) chip(this, "Shorts " + (s.shortsShare * 100).toInt() + "%") else null
        ))
        c.row(
            btn(this, t("В проект: видео (${d.recent.size})", "Add videos (${d.recent.size})"), primary = true) {
                val n = Store.mergeVideos(pid, d.recent.map { Video.from(it.toJson()) })
                toast(t("Добавлено: $n", "Added: $n"))
            },
            btn(this, t("Обновить", "Refresh"), style = BtnStyle.OUTLINED) { load(true) }
        )
        c.row(
            btn(this, t("Граф каналов", "Channel graph"), style = BtnStyle.TONAL) {
                if (CollectState.running) return@btn toast(t("Уже идёт другая задача", "Another task is running"))
                Store.writeFile(pid, "chgraph_seeds.txt", d.url)
                val q = QuickConfig.load()
                confirm(t("Найти связанные каналы?", "Find related channels?"),
                    t("Глубина ${q.graphDepth}, до ${q.graphChannels} каналов, со статистикой каждого. Меняется в Настройках → Быстрый анализ.",
                        "Depth ${q.graphDepth}, up to ${q.graphChannels} channels with stats. Change in Settings → Instant analysis."), t("Запустить", "Start")) {
                    CollectService.startKind(this, pid, "chgraph")
                    startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", pid).putExtra("tab", "channels"))
                }
            },
            btn(this, t("Анализ канала", "Analyse channel"), style = BtnStyle.TONAL) {
                confirm(t("Полный быстрый анализ канала?", "Full instant analysis of the channel?"), t("Будет создан отдельный проект.", "A separate project will be created.")) {
                    QuickAnalysis.start(this, d.url)
                }
            }
        )

        // ---- AI summary
        c.h2(t("AI-сводка по каналу", "AI channel summary"))
        val ai = ChannelAi.latest(pid, d.url)
        if (ai != null) c.card {
            add(text(ctx, ai.text, 14f).apply { setTextIsSelectable(true) }, 0)
            muted(ai.model + " · " + Fmt.dateTime(ai.at))
        }
        c.row(
            btn(this, if (ai == null) t("Сделать сводку", "Summarise") else t("Обновить сводку", "Refresh summary"), style = BtnStyle.TONAL) {
                if (Provider.active() == null) return@btn toast(t("Подключите AI в настройках", "Connect AI in settings"))
                busy = t("AI думает…", "AI is thinking…")
                render()
                Lumen.async({ ChannelAi.summary(pid, d, force = ai != null) }, {
                    busy = ""
                    render()
                    err(it)
                }) {
                    busy = ""
                    render()
                }
            },
            btn(this, t("Ответы AI", "AI answers"), style = BtnStyle.TEXT) { AiJournalActivity.open(this, pid) }
        )

        // ---- links & description
        if (d.links.isNotEmpty()) {
            c.h2(t("Ссылки", "Links") + " (${d.links.size})")
            d.links.forEach { l -> c.add(text(this, "🔗 $l", 14f, color = Palette.ACCENT).apply {
                minHeight = dp(40)
                setOnClickListener { openUrl(l) }
            }, 0) }
        }
        if (d.tags.isNotEmpty()) c.chips(d.tags.take(30).map { chip(this, it) })
        if (d.description.isNotBlank()) {
            c.h2(t("Описание", "Description"))
            c.add(text(this, if (fullDesc || d.description.length < 400) d.description else d.description.take(400) + "…", 14f).apply {
                setTextIsSelectable(true)
                setOnClickListener {
                    fullDesc = !fullDesc
                    render()
                }
            }, 8)
        }

        // ---- playlists
        if (d.playlists.isNotEmpty()) {
            c.h2(t("Плейлисты", "Playlists") + " (${d.playlists.size})")
            c.muted(t("Нажмите на плейлист — будет запущен полный анализ: видео, статистика, похожие, граф каналов.",
                "Tap a playlist to run the full analysis: videos, stats, similar, channel graph."))
            d.playlists.forEach { pl ->
                val card = c.card {
                    add(text(ctx, "▤ " + pl.name, 14f, bold = true), 0)
                    if (pl.count >= 0) muted(t("видео: ", "videos: ") + pl.count)
                }
                card.setOnClickListener {
                    confirm(pl.name, t("Запустить анализ плейлиста?", "Analyse this playlist?"), t("Запустить", "Start")) { QuickAnalysis.start(this, pl.url) }
                }
            }
        }

        // ---- top & recent videos
        if (s.top.isNotEmpty()) {
            c.h2(t("Лучшие по просмотрам", "Top by views"))
            s.top.forEach { v -> c.add(videoLine(v), 6) }
        }
        c.h2(t("Последние видео", "Recent videos") + " (${d.recent.size})")
        d.recent.take(shown).forEach { v -> c.add(videoLine(v), 6) }
        if (d.recent.size > shown) c.button(t("Ещё", "More"), style = BtnStyle.TEXT) {
            shown += 30
            render()
        }

        // ---- featured channels
        if (d.featured.isNotEmpty()) {
            c.h2(t("Рекомендуемые каналы автора", "Featured channels") + " (${d.featured.size})")
            d.featured.forEach { u ->
                val known = ChannelStore.get(pid, u)
                val card = c.card {
                    add(text(ctx, known?.name?.ifBlank { null } ?: u.substringAfterLast('/'), 14f, bold = true), 0)
                    if (known != null) muted(ChannelStats.line(known))
                }
                card.setOnClickListener { open(this, pid, u) }
            }
        }
        page(d.name.ifBlank { t("Канал", "Channel") }, c.root, actions = listOf("↗" to { openUrl(d.url) }))
    }

    private fun videoLine(v: Video) = videoListRow(this, ThumbUrl.mini(v.id, v.thumb), v.title, "",
        Fmt.num(v.views) + " · " + (if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText) + " · " + Fmt.duration(v.duration)) {
        chooseLinkAction(v.url)
    }.apply {
        setOnLongClickListener {
            openVideo(v.url)
            true
        }
    }

    companion object {
        fun open(ctx: Context, pid: String, url: String) {
            ctx.startActivity(Intent(ctx, ChannelActivity::class.java).putExtra("pid", pid).putExtra("url", url))
        }
    }
}

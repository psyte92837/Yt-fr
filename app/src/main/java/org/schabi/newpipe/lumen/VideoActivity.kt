/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/** Video card: metadata, labels, AI summary, transcript and comments. */
class VideoActivity : LumenBase() {
    private lateinit var pid: String
    private lateinit var video: Video
    private var busy = false
    private var fullTranscript = false
    private var commentsShown = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid") ?: return finish()
        val v = Store.videos(pid)[intent.getStringExtra("vid")]
        if (v == null) {
            finish()
            return
        }
        video = v
        render()
    }

    private val folds = HashMap<String, Boolean>()

    private fun folded(key: String, default: Boolean) = folds[key] ?: default

    private fun fold(key: String, open: Boolean) {
        folds[key] = open
        render()
    }

    private fun reloadAndRender() {
        if (isFinishing || isDestroyed) return
        Store.videos(pid)[video.id]?.let { video = it }
        render()
    }

    private fun save() = Store.updateVideo(pid, video.id) {
        it.marked = video.marked
        it.labels = video.labels
    }

    private fun render() {
        val v = video
        val c = Col(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(Palette.ACCENT_SOFT, 12f, this@VideoActivity)
            clipToOutline = true
            adjustViewBounds = true
            contentDescription = v.title
        }
        c.add(img, 0).layoutParams = LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels - dp(32)) * 9 / 16)
        Thumbs.load(img, v.thumb)
        c.add(text(this, v.title, Type.TITLE_L), 16)
        val ch = if (v.channelUrl.isNotBlank()) ChannelStore.get(pid, v.channelUrl) else null
        c.add(text(this, "👤 " + v.channel + (if (v.subscribers > 0) " · " + Fmt.num(v.subscribers) + t(" подписчиков", " subscribers") else "") +
            (ch?.let { "\n" + ChannelStats.line(it) + t(" · ссылок ", " · links ") + it.links.size + t(" · плейлистов ", " · playlists ") + it.playlists.size } ?: ""),
            13f, color = Palette.ACCENT).apply {
            minHeight = dp(40)
            setOnClickListener { if (v.channelUrl.isNotBlank()) ChannelActivity.open(this@VideoActivity, pid, v.channelUrl) }
        }, 4)
        c.chips(listOfNotNull(
            chip(this, "👁 " + Fmt.num(v.views)),
            if (v.likes >= 0) chip(this, "👍 " + Fmt.num(v.likes)) else null,
            chip(this, "⏱ " + Fmt.duration(v.duration)),
            chip(this, "📅 " + if (v.uploadMs > 0) Fmt.date(v.uploadMs) else v.uploadText),
            if (Analytics.velocity(v) > 0) chip(this, "📈 " + Fmt.num(Analytics.velocity(v)) + t("/день", "/day")) else null,
            if (v.category.isNotBlank()) chip(this, v.category) else null
        ))
        c.row(
            btn(this, if (Settings.mode == "player") t("Смотреть", "Watch") else "YouTube", primary = true) {
                if (v.url.isBlank()) toast(t("Импортированный источник без ссылки", "Imported source without a link")) else openVideo(v.url)
            },
            btn(this, if (v.marked) t("Снять отметку", "Unmark") else t("Отметить", "Mark"), style = BtnStyle.TONAL) {
                v.marked = !v.marked
                save()
                render()
            }
        )
        c.row(
            btn(this, t("Метки", "Labels"), style = BtnStyle.OUTLINED) {
                prompt(t("Метки через запятую", "Labels, comma separated"), "важное, обзор", v.labels.joinToString(", ")) { s ->
                    v.labels = s.split(',').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct().toMutableList()
                    save()
                    render()
                }
            },
            btn(this, t("Собрать", "Collect"), style = BtnStyle.OUTLINED) {
                if (isLocalSource(v.id)) toast(t("Это импортированный файл", "This is an imported file")) else collectNow()
            }
        )
        if (MediaLink.playable(v.url) != null) c.row(
            btn(this, t("🎧 В фоне", "🎧 Background"), style = BtnStyle.TONAL) { runMedia(v.url, MediaAction.BACKGROUND) },
            btn(this, t("⬇ Аудио", "⬇ Audio"), style = BtnStyle.OUTLINED) { runMedia(v.url, MediaAction.AUDIO) },
            btn(this, t("⬇ Видео", "⬇ Video"), style = BtnStyle.OUTLINED) { runMedia(v.url, MediaAction.VIDEO) }
        )
        if (v.collectError.isNotEmpty()) c.p("⚠ " + v.collectError, 13f, Palette.BAD)

        // AI summary
        c.h2(t("AI-сводка", "AI summary"))
        AiJournal.list(pid).firstOrNull { it.task == "video_summary" && it.status != "ok" }?.let { bad ->
            c.button(t("Последний неудачный ответ AI: ", "Last failed AI answer: ") + AiJournal.statusLabel(bad.status), style = BtnStyle.TEXT) {
                AiJournalActivity.open(this, id = bad.id)
            }
        }
        val summary = Store.latestAi(pid, "video_summary", v.id)
        if (summary != null) {
            c.card {
                add(text(ctx, summary.text, 14f).apply { setTextIsSelectable(true) }, 0)
                muted(summary.model + " · " + Fmt.dateTime(summary.at))
            }
        }
        if (busy) c.working(t("AI думает…", "AI is thinking…"))
        c.button(
            if (summary == null) t("Сделать сводку", "Summarise") else t("Обновить сводку", "Refresh summary"),
            style = BtnStyle.TONAL
        ) { summarise(summary != null) }

        styleCard(c, pid, v, full = true) { reloadAndRender() }

        if (v.tags.isNotEmpty() || v.description.isNotBlank()) {
            c.section(t("Описание и теги", "Description and tags"), v.description.take(90).replace('\n', ' '), folded("desc", false), { fold("desc", it) }) {
                if (v.tags.isNotEmpty()) c.chips(v.tags.take(30).map { chip(this@VideoActivity, it) })
                if (v.description.isNotBlank()) c.add(text(this@VideoActivity, v.description, 14f).apply { setTextIsSelectable(true) }, 8)
            }
        }

        val tr = Store.transcript(pid, v.id)
        c.section(
            t("Расшифровка", "Transcript") + if (v.transcriptSource.isNotEmpty()) " · ${v.transcriptSource}" else "",
            if (tr.isNullOrBlank()) t("нет", "none") else t("${tr.length} симв.", "${tr.length} chars"),
            folded("tr", false), { fold("tr", it) }
        ) {
            if (tr.isNullOrBlank()) {
                c.muted(t("Нет. Включите «Расшифровка» на вкладке «Сбор» и нажмите «Собрать».", "None. Enable Transcript on the Collect tab and tap Collect."))
            } else {
                val shown = if (fullTranscript || tr.length <= 3000) tr else tr.take(3000) + "…"
                c.card { add(text(ctx, shown, 13f).apply { setTextIsSelectable(true) }, 0) }
                c.row(
                    btn(this@VideoActivity, if (fullTranscript) t("Свернуть", "Collapse") else t("Показать всё", "Show all"), style = BtnStyle.TEXT) {
                        fullTranscript = !fullTranscript
                        render()
                    },
                    btn(this@VideoActivity, t("Поделиться", "Share"), style = BtnStyle.TEXT) { shareText(v.title, tr) }
                )
            }
        }

        val comments = Store.comments(pid, v.id)
        c.section(t("Комментарии", "Comments"), if (comments.isEmpty()) "—" else comments.size.toString(), folded("cm", false), { fold("cm", it) }) {
            if (comments.isEmpty()) c.muted("—")
            comments.take(commentsShown).forEach { cm ->
                c.card {
                    add(text(ctx, (if (cm.isReply) "↳ " else "") + cm.author, 13f, bold = true), 0)
                    add(text(ctx, cm.text, 14f).apply { setTextIsSelectable(true) }, 4)
                    muted("♥ ${cm.likes} · ${if (cm.dateMs > 0) Fmt.date(cm.dateMs) else cm.dateText}" +
                        if (cm.replyCount > 0) " · " + t("ответов: ", "replies: ") + cm.replyCount else "")
                }
            }
            if (comments.size > commentsShown) c.button(t("Ещё комментарии", "More comments"), style = BtnStyle.TEXT) {
                commentsShown += 50
                render()
            }
        }
        page(t("Видео", "Video"), c.root, actions = listOf("⤴" to { shareText(v.title, v.url) }))
    }

    private fun collectNow() {
        val p = Store.project(pid) ?: return
        busy = true
        toast(t("Сбор…", "Collecting…"))
        Lumen.async({
            YouTube.collect(pid, p.phrase, video, p.collect)
            Store.updateVideo(pid, video.id) { s ->
                s.collected = true
                s.collectError = ""
                s.description = video.description
                s.tags = video.tags
                s.likes = video.likes
                s.category = video.category
                s.subscribers = video.subscribers
                s.hasTranscript = video.hasTranscript
                s.transcriptSource = video.transcriptSource
                s.commentsCount = video.commentsCount
                if (video.views >= 0) s.views = video.views
            }
        }, {
            busy = false
            err(it)
        }) {
            busy = false
            render()
        }
    }

    private fun summarise(force: Boolean) {
        val p = Store.project(pid) ?: return
        if (Provider.active() == null) return toast(t("Подключите AI в настройках", "Connect AI in settings"))
        busy = true
        render()
        bgTask(t("AI-сводка видео", "Video AI summary"), { AiTasks.videoSummary(p, video, force) }, {
            busy = false
            render()
            err(it)
        }) {
            busy = false
            render()
        }
    }
}

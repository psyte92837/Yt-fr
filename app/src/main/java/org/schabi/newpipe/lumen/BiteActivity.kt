/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * "✂ Bite a video or audio": find a YouTube video (search or link), mark a piece with the player and save it on the
 * phone — the video with sound (Movies/Lumen) or the sound only (Music/Lumen). Only the needed part is downloaded.
 */
class BiteActivity : LumenBase() {
    companion object {
        private const val TASK = BiteJob.PREFIX
        private const val REQ_SAVE = 7451

        fun open(ctx: Context, url: String = "") = ctx.startActivity(Intent(ctx, BiteActivity::class.java).apply {
            if (url.isNotBlank()) putExtra("url", url)
        })
    }

    private val dir get() = BiteJob.dir

    private var hits: List<CutHit> = emptyList()
    private var searching = false
    private var searchInfo = ""
    private var query = ""

    // the chosen video
    private var url = ""
    private var title = ""
    private var durMs = 0L
    private var look: StudioCutsLoad.Look? = null
    private var lookErr = ""
    private var fromText = "0:00"
    private var toText = "0:10"

    private var audio: Boolean
        get() = BiteJob.audio
        set(v) { BiteJob.audio = v }
    private var quality: Int
        get() = BiteJob.quality
        set(v) { BiteJob.quality = v }
    private var audioQ: String
        get() = BiteJob.audioQ
        set(v) { BiteJob.audioQ = v }

    private var done: List<Bite.Done> = emptyList()
    private var status: TextView? = null
    private var pendingCopy: File? = null

    // ---- player (kept between renders) ----
    private var player: com.google.android.exoplayer2.ExoPlayer? = null
    private var stopAtMs = -1L
    private val surface by lazy { android.view.TextureView(this) }
    private val frame by lazy {
        FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            setOnClickListener { player?.let { p -> p.playWhenReady = !p.playWhenReady } }
        }
    }
    private val posText by lazy { text(this, "", 13f, color = Palette.MUTED) }
    private var fromEdit: EditText? = null
    private var toEdit: EditText? = null
    private var lenText: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val pos = p.currentPosition
                if (stopAtMs >= 0 && pos >= stopAtMs) {
                    p.playWhenReady = false
                    stopAtMs = -1
                }
                posText.text = StudioCuts.stamp(pos) + (if (durMs > 0) " / " + StudioCuts.stamp(durMs) else "") + if (p.playWhenReady) "  ▶" else "  ⏸"
            }
            frame.postDelayed(this, 200)
        }
    }

    private val watcher: () -> Unit = {
        val task = BgTasks.find(TASK)
        if (task == null && status != null && !isDestroyed) {
            status = null
            load()
            render()
        } else if (task != null && task.message.isNotBlank()) status?.text = task.message
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        BgTasks.watchers += watcher
        val given = intent.getStringExtra("url").orEmpty()
        render()
        if (given.isNotBlank()) choose(given, "", 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let { choose(it, "", 0) }
    }

    override fun onPause() {
        player?.playWhenReady = false
        super.onPause()
    }

    override fun onDestroy() {
        BgTasks.watchers -= watcher
        frame.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun load() {
        done = BiteJob.list()
    }

    // ---------- screen ----------

    private fun render() {
        val c = Col(this)
        c.muted(t("Найдите видео на YouTube или вставьте ссылку, отметьте кусок и сохраните его на телефон — видео со звуком или только звук. Качается только нужный кусок, без пережатия.",
            "Find a YouTube video or paste a link, mark a piece and save it on the phone — a video with sound or the sound only. Only the needed piece is downloaded, without re-encoding."))
        searchCard(c)
        if (url.isNotEmpty()) {
            pieceCard(c)
            saveCard(c)
        }
        if (done.isNotEmpty()) doneCard(c)
        page(t("✂ Откусить видео или аудио", "✂ Bite a video or audio"), c.root, keepScroll = true)
    }

    private fun searchCard(c: Col) = c.card {
        h2(t("1. Видео", "1. Video"))
        val q = edit(t("Ссылка на видео или что искать", "Video link or what to search"), query) { go(it) }
        button(t("🔎 Найти", "🔎 Find"), style = BtnStyle.TONAL) { go(q.text.toString()) }
        if (searching) muted(t("Поиск…", "Searching…"))
        else if (searchInfo.isNotEmpty()) muted(searchInfo)
        for (h in hits) {
            val sub = listOf(h.channel, if (h.durationMs > 0) StudioCuts.stamp(h.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                if (h.url == url) background = roundBg(Palette.ACCENT_SOFT, 8f, ctx)
                addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF202020.toInt())
                    Thumbs.load(this, h.thumb)
                }, LinearLayout.LayoutParams(dp(96), dp(54)).apply { rightMargin = dp(8) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(ctx, h.title, 14f, bold = h.url == url).apply { maxLines = 2 })
                    if (sub.isNotEmpty()) addView(text(ctx, sub, 12f, color = Palette.MUTED))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                contentDescription = h.title
                setOnClickListener { choose(h.url, h.title, h.durationMs) }
            }
            add(line, 6)
        }
    }

    private fun go(s: String) {
        val v = s.trim()
        query = v
        if (v.isEmpty()) return
        if (YtQuery.videoIdFromUrl(v) != null) {
            hits = emptyList()
            searchInfo = ""
            return choose(v, "", 0)
        }
        searching = true
        render()
        Lumen.async({
            YouTube.search(listOf(v), SearchFilters(limit = 20)).map {
                CutHit(it.url, it.title, it.channel, if (it.duration > 0) it.duration * 1000 else 0,
                    YtQuery.videoIdFromUrl(it.url)?.let { id -> "https://i.ytimg.com/vi/$id/mqdefault.jpg" } ?: it.thumb)
            }
        }, { e ->
            searching = false
            searchInfo = (e.message ?: e.javaClass.simpleName).take(200)
            if (!isDestroyed) render()
        }) { found ->
            searching = false
            hits = found
            searchInfo = if (found.isEmpty()) t("Ничего не нашлось. Попробуйте другие слова.", "Nothing found. Try other words.")
            else t("Найдено: ${found.size}. Нажмите на видео.", "Found: ${found.size}. Tap a video.")
            if (!isDestroyed) render()
        }
    }

    /** Picks the video: frames and a low-quality stream for the player; a time in the link becomes the start. */
    private fun choose(u: String, name: String, dur: Long) {
        url = u
        title = name
        durMs = dur
        look = null
        lookErr = ""
        val start = StudioCuts.startOf(u) ?: 0L
        fromText = StudioCuts.stamp(start)
        toText = StudioCuts.stamp(Bite.endFor(start, 10, dur))
        player?.stop()
        render()
        Lumen.async({
            val lk = StudioCutsLoad.look(u)
            val info = if (name.isBlank() || dur <= 0) runCatching { org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, u) }.getOrNull() else null
            Triple(lk, info?.name.orEmpty(), (info?.duration ?: 0L) * 1000)
        }, { e ->
            if (url != u) return@async
            lookErr = (e.message ?: e.javaClass.simpleName).take(200)
            if (!isDestroyed) render()
        }) { (lk, n, d) ->
            if (url != u || isDestroyed) return@async
            look = lk
            if (title.isBlank()) title = n
            if (durMs <= 0 && d > 0) durMs = d
            render()
        }
    }

    private fun pieceCard(c: Col) = c.card {
        h2(t("2. Кусок", "2. Piece"))
        if (title.isNotBlank()) p(title, 14f)
        val lk = look
        when {
            lookErr.isNotEmpty() -> muted(t("Просмотр недоступен: ", "No preview: ") + lookErr + t(". Время можно ввести вручную.", ". You can type the times."))
            lk == null -> muted(t("Загрузка кадров…", "Loading frames…"))
            else -> {
                if (lk.frames.isNotEmpty()) add(strip(lk), 4)
                if (lk.stream.isNotEmpty()) {
                    (frame.parent as? ViewGroup)?.removeView(frame)
                    add(frame, 8).layoutParams.height = dp(200)
                    (posText.parent as? ViewGroup)?.removeView(posText)
                    row(
                        btn(ctx, "⏪ 5", style = BtnStyle.TEXT) { seek(-5_000) },
                        btn(ctx, "▶ ⏸", style = BtnStyle.TONAL) { toggle() },
                        btn(ctx, "5 ⏩", style = BtnStyle.TEXT) { seek(5_000) }
                    )
                    add(posText, 2)
                    row(
                        btn(ctx, t("⟦ Начало здесь", "⟦ Start here"), style = BtnStyle.TONAL) { markHere(true) },
                        btn(ctx, t("Конец здесь ⟧", "End here ⟧"), style = BtnStyle.TONAL) { markHere(false) }
                    )
                } else muted(t("Поток для просмотра не найден — только кадры.", "No stream to watch — frames only."))
            }
        }
        val fe = edit(t("Начало: 1:23 или 83", "Start: 1:23 or 83"), fromText)
        val te = edit(t("Конец (пусто — до конца видео)", "End (empty — to the end)"), toText)
        fromEdit = fe
        toEdit = te
        fe.addTextChangedListener(changed { fromText = it; showLen() })
        te.addTextChangedListener(changed { toText = it; showLen() })
        add(text(ctx, t("Длина от начала:", "Length from the start:"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        chips(Bite.LENGTHS_S.map { s ->
            chip(ctx, if (s < 60) "$s с" else "${s / 60} мин") {
                val a = StudioCuts.parseTime(fromText) ?: 0
                toText = StudioCuts.stamp(Bite.endFor(a, s, durMs))
                toEdit?.setText(toText)
            }
        })
        lenText = add(text(ctx, "", 13f, color = Palette.MUTED), 6)
        showLen()
        if (lk?.stream?.isNotEmpty() == true) button(t("▶ Проверить кусок", "▶ Check the piece"), style = BtnStyle.TEXT) { checkPiece() }
    }

    private fun changed(f: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = f(s?.toString().orEmpty())
    }

    private fun showLen() {
        val r = Bite.range(fromText, toText, durMs)
        lenText?.text = if (!r.ok) "⚠ " + r.error
        else t("Длина: ", "Length: ") + StudioCuts.stamp(r.lenMs) + " · ≈ " + "%.1f".format(Bite.sizeMb(r.lenMs, audio, quality, audioQ)) + t(" МБ", " MB")
    }

    private fun strip(lk: StudioCutsLoad.Look): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val h = dp(60)
        for ((ms, bmp) in lk.frames) {
            val w = (h.toLong() * bmp.width / bmp.height.coerceAtLeast(1)).toInt()
            row.addView(FrameLayout(this).apply {
                addView(ImageView(this@BiteActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(w, h))
                addView(text(this@BiteActivity, StudioCuts.stamp(ms), 10f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                    setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
                    setPadding(dp(3), 0, dp(3), dp(1))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                contentDescription = t("Смотреть с ", "Watch from ") + StudioCuts.stamp(ms)
                setOnClickListener { play(ms) }
            }, LinearLayout.LayoutParams(w, h).apply { rightMargin = dp(3) })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun exo(): com.google.android.exoplayer2.ExoPlayer = player ?: com.google.android.exoplayer2.ExoPlayer.Builder(this).build().also { p ->
        player = p
        p.setVideoTextureView(surface)
        p.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
                if (size.width <= 0 || size.height <= 0) return
                val fw = frame.width.takeIf { it > 0 } ?: return
                val fh = frame.height
                val vh = size.height / size.pixelWidthHeightRatio.coerceAtLeast(0.1f)
                val k = minOf(fw.toFloat() / size.width, fh.toFloat() / vh)
                surface.layoutParams = FrameLayout.LayoutParams((size.width * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                posText.text = t("Не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName)
            }
        })
        frame.removeCallbacks(tick)
        frame.post(tick)
    }

    private var loaded = ""

    /** Plays from [ms]; [untilMs] ≥ 0 pauses there. */
    private fun play(ms: Long, untilMs: Long = -1) {
        val lk = look?.takeIf { it.stream.isNotEmpty() } ?: return
        val p = exo()
        if (loaded != lk.stream || p.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE) {
            val src = com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                org.schabi.newpipe.player.datasource.YoutubeHttpDataSource.Factory().setRangeParameterEnabled(false).setRnParameterEnabled(true)
            ).createMediaSource(com.google.android.exoplayer2.MediaItem.fromUri(lk.stream))
            p.setMediaSource(src)
            p.prepare()
            loaded = lk.stream
        }
        p.seekTo(ms.coerceAtLeast(0))
        stopAtMs = untilMs
        p.playWhenReady = true
    }

    private fun toggle() {
        val p = player
        if (p == null || p.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE) play(StudioCuts.parseTime(fromText) ?: 0)
        else p.playWhenReady = !p.playWhenReady
    }

    private fun seek(d: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + d).coerceIn(0, if (durMs > 0) durMs else Long.MAX_VALUE))
    }

    private fun markHere(start: Boolean) {
        val p = player ?: return toast(t("Сначала включите видео", "Play the video first"))
        val pos = p.currentPosition
        if (start) {
            fromText = StudioCuts.stamp(pos)
            fromEdit?.setText(fromText)
            // keep the piece after the start
            val end = StudioCuts.parseTime(toText)
            if (end != null && end <= pos) {
                toText = StudioCuts.stamp(Bite.endFor(pos, 10, durMs))
                toEdit?.setText(toText)
            }
        } else {
            toText = StudioCuts.stamp(pos)
            toEdit?.setText(toText)
        }
    }

    private fun checkPiece() {
        val r = Bite.range(fromText, toText, durMs)
        if (!r.ok) return toast(r.error)
        play(r.fromMs, r.toMs)
    }

    private fun saveCard(c: Col) = c.card {
        h2(t("3. Сохранить", "3. Save"))
        add(segBar(ctx, listOf(t("🎬 Видео со звуком", "🎬 Video with sound"), t("🎵 Только аудио", "🎵 Sound only")), if (audio) 1 else 0) {
            audio = it == 1
            render()
        }, 4)
        if (!audio) {
            spinner(t("Качество видео", "Video quality"), Bite.QUALITIES.map { it to Bite.videoLabel(it) }, quality) {
                quality = it
                showLen()
            }
            muted(t("Видео начинается с ближайшего ключевого кадра — бывает на 1–3 с раньше начала. Сохраняется в Галерею → Фильмы/Lumen.",
                "The video starts at the nearest key frame — sometimes 1–3 s before the start. Saved to Gallery → Movies/Lumen."))
        } else {
            muted(t("Звук AAC (.m4a), точно по времени. Сохраняется в Музыка/Lumen.", "AAC sound (.m4a), exactly on time. Saved to Music/Lumen."))
        }
        spinner(t("Качество звука", "Sound quality"), Bite.AUDIO_QUALITIES.map { it to Bite.audioLabel(it) }, audioQ) {
            audioQ = it
            showLen()
        }
        val tasks = BgTasks.running().filter { it.key.startsWith(TASK) }
        status = null
        for (task in tasks) {
            muted(task.title)
            val (tv, _) = working(task.message.ifBlank { t("Откусываю…", "Biting…") })
            if (status == null) status = tv
            button(t("■ Остановить", "■ Stop"), style = BtnStyle.TEXT) { BgTasks.stop(task) }
        }
        button(if (audio) t("✂ Откусить аудио и сохранить", "✂ Bite the sound and save") else t("✂ Откусить видео и сохранить", "✂ Bite the video and save"), primary = true) { start() }
    }

    private fun start() {
        val r = Bite.range(fromText, toText, durMs)
        if (!r.ok) return toast(r.error)
        player?.playWhenReady = false
        BiteJob.start(this, url, title, r.fromMs, r.toMs, audio, quality, audioQ) { d, _ ->
            if (isDestroyed) return@start
            status = null
            load()
            render()
            if (d != null) toast(if (d.savedAs.isNotEmpty()) t("Сохранено: ", "Saved: ") + d.savedAs else t("Готово, но в общую папку не сохранилось — «💾 В папку…»", "Done, but not saved to the shared folder — use \"💾 To a folder…\""))
        }
        render()
    }

    private fun doneCard(c: Col) = c.card {
        h2(t("Откушенное", "Bitten"))
        for (d in done) {
            val f = File(dir, d.file)
            p((if (d.audio) "🎵 " else "🎬 ") + d.title.ifBlank { d.file }, 14f)
            muted(StudioCuts.stamp(d.fromMs) + "–" + StudioCuts.stamp(d.toMs) + " · " + "%.1f".format(f.length() / 1_048_576.0) + t(" МБ", " MB") +
                if (d.savedAs.isNotEmpty()) " · " + d.savedAs else "")
            row(
                btn(ctx, "▶", style = BtnStyle.TEXT) { view(f, d.audio) }.apply { contentDescription = t("Открыть", "Open") },
                btn(ctx, "↗", style = BtnStyle.TEXT) { ReportKit.share(this@BiteActivity, f, false, f.name, mime(d.audio)) }.apply { contentDescription = t("Поделиться", "Share") },
                btn(ctx, t("💾 В папку…", "💾 To a folder…"), style = BtnStyle.TEXT) { copyTo(f, d.audio) },
                btn(ctx, "🗑", style = BtnStyle.TEXT) { remove(d) }.apply { contentDescription = t("Удалить", "Delete") }
            )
        }
        muted(t("Здесь — копии внутри Lumen (последние ${Bite.KEEP}). Сохранённые в Фильмы/Музыку файлы не удаляются.",
            "These are copies inside Lumen (the last ${Bite.KEEP}). Files saved to Movies/Music are not deleted."))
    }

    private fun mime(a: Boolean) = if (a) "audio/mp4" else "video/mp4"

    private fun view(f: File, a: Boolean) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(a)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            ReportKit.share(this, f, false, f.name, mime(a))
        }
    }

    private fun copyTo(f: File, a: Boolean) {
        pendingCopy = f
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime(a))
                .putExtra(Intent.EXTRA_TITLE, f.name), REQ_SAVE)
        } catch (e: Exception) {
            err(e)
        }
    }

    private fun remove(d: Bite.Done) {
        val before = done
        undoable(t("Удалено из списка", "Removed from the list"), {
            done = before.filter { it.file != d.file }
            render()
        }, {
            done = before
            render()
        }) { BiteJob.remove(d.file) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE) return
        val src = pendingCopy
        pendingCopy = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || src == null) return
        Lumen.async({
            contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error(t("Файл не открылся для записи", "The file cannot be opened for writing"))
        }, { e -> err(e) }) { toast(t("Копия сохранена", "Copy saved")) }
    }
}

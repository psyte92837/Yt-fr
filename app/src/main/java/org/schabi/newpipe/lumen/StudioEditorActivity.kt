/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SeekParameters
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Studio editor: the finished video plays with a timeline under it; at any moment the author pauses and
 * inserts an info-scheme (by the AI from the words of that moment, or from own lines), a picture (gallery or
 * a photo found by a search the AI can suggest), a caption or an emoji. Blocks are moved, scaled and turned on
 * the frame, moved and trimmed on the timeline, split, duplicated, animated; every change can be undone.
 * Export draws the blocks into a new MP4 with the original sound ([StudioEditRender]).
 */
class StudioEditorActivity : LumenBase() {
    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_PROJECT = "project"
        private const val REQ_IMAGE = 7711
        private const val REQ_VIDEO = 7712
        private const val TASK = "studioedit:"
        private const val TASK_EXPORT = TASK + "export"
        private const val TASK_AI = TASK + "ai"

        /** Words of the voice handed over with the video until its project is made. */
        @Volatile private var handoff: Pair<String, List<SWord>>? = null

        /** Opens the editor for a finished video; [words] — its voice with times (else read from [sidecar]). */
        fun open(ctx: Context, video: File, words: List<SWord> = emptyList()) {
            handoff = if (words.isNotEmpty()) video.absolutePath to words else null
            ctx.startActivity(Intent(ctx, StudioEditorActivity::class.java).putExtra(EXTRA_FILE, video.absolutePath))
        }

        /** The list of editor projects. */
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, StudioEditorActivity::class.java))

        /** Intent that opens [video] in the editor (for a notification). */
        fun intentFor(ctx: Context, video: File): Intent = Intent(ctx, StudioEditorActivity::class.java).putExtra(EXTRA_FILE, video.absolutePath)

        /** Words of the voice written next to a Studio video when it is built ([StudioJob.build]). */
        fun sidecar(video: File) = File(video.path + ".words.json")

        private val EMOJI = listOf(
            "🔥", "✅", "❌", "⚡", "💡", "🚀", "📈", "📉", "💰", "⭐", "❤️", "👍", "👎", "👉", "👀", "🤔", "😮", "😂", "😱", "🎯",
            "⚠️", "❗", "❓", "🏆", "🎉", "🧠", "⏰", "📌", "🔑", "💬", "📊", "🛒", "🔍", "💪", "🙌", "✨", "🎁", "📣", "🧩", "🌍"
        )
    }

    private var project: EProject? = null
    private var words: List<SWord> = emptyList()
    private var selected: String? = null
    private val history = EditHistory()
    /** Blocks before the gesture now going on (for undo). */
    private var gestureBase: String? = null
    private var player: ExoPlayer? = null
    private var curMs = 0L
    private var lastSeekAt = 0L
    private var resumed = false
    private var pickFor: String? = null
    private var pickAt = 0L
    private var lastStyle = SchemeStyles.AUTO
    @Volatile private var cancelExport = false

    private var stage: EditStage? = null
    private var timeline: EditTimeline? = null
    private var texture: TextureView? = null
    private var timeText: TextView? = null
    private var playBtn: TextView? = null
    private var toolsRow: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var statusText: TextView? = null
    private var statusBar: ProgressBar? = null
    private var statusStop: TextView? = null

    private val blocks: List<EBlock> get() = project?.blocks.orEmpty()
    private val dir: File get() = StudioEditStore.dir(applicationContext, project?.id ?: "none")
    private val styleDir: File get() = StudioEditStore.styleDir(applicationContext)
    private val total: Long get() = project?.durMs ?: 1L
    private val watcher: () -> Unit = { watch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pid = intent.getStringExtra(EXTRA_PROJECT)
        val file = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
        val stored = pid?.let { StudioEditStore.load(StudioEditStore.dir(this, it)) }
        when {
            stored != null -> start(stored)
            file != null && file.exists() -> openFile(file)
            else -> chooser()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        BgTasks.watchers += watcher
        window.decorView.removeCallbacks(tick)
        window.decorView.postOnAnimation(tick)
        watch()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        BgTasks.watchers -= watcher
        player?.pause()
        save()
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected != null) select(null) else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- opening ----------

    private fun loading(msg: String) {
        val c = Col(this)
        c.working(msg)
        page(t("Редактор", "Editor"), c.root)
    }

    private fun openFile(f: File) {
        StudioEditStore.bySource(this, f)?.let { return start(it) }
        val ws = handoff?.takeIf { it.first == f.absolutePath }?.second
            ?: sidecar(f).takeIf { it.exists() }?.let { runCatching { StudioMix.wordsFromJson(it.readText()) }.getOrNull() }.orEmpty()
        handoff = null
        loading(t("Готовлю проект…", "Preparing the project…"))
        val app = applicationContext
        Lumen.async({ StudioEditStore.create(app, f, ws) }, { e ->
            if (isDestroyed) return@async
            chooser()
            err(e)
        }) { p -> if (!isDestroyed) start(p) }
    }

    private fun importVideo(uri: Uri) {
        loading(t("Копирую видео…", "Copying the video…"))
        val app = applicationContext
        Lumen.async({ StudioEditStore.create(app, uri) }, { e ->
            if (isDestroyed) return@async
            chooser()
            err(e)
        }) { p -> if (!isDestroyed) start(p) }
    }

    private fun pickVideo() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_VIDEO)
    }

    /** Start screen: the last Studio video, a video from the phone and the saved projects. */
    private fun chooser() {
        stopPlayer()
        project = null
        stage = null
        timeline = null
        statusRow = null
        val c = Col(this)
        c.p(t("Откройте готовый ролик: в нужные моменты можно вставить инфосхемы, картинки, надписи и эмодзи, двигать их и менять размер и длительность.",
            "Open a finished video: insert info-schemes, pictures, captions and emoji at any moment, move them and change their size and length."), color = Palette.MUTED)
        StudioJob.result?.takeIf { it.exists() }?.let { f ->
            c.button(t("🎬 Последний ролик Студии", "🎬 Last Studio video"), primary = true) { openFile(f) }
            c.muted(f.name)
        }
        c.button(t("📂 Видео с телефона", "📂 A video from the phone"), style = BtnStyle.TONAL) { pickVideo() }
        val list = StudioEditStore.list(this)
        if (list.isNotEmpty()) {
            c.h2(t("Проекты", "Projects"))
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (pr in list) c.card {
                p(df.format(Date(pr.created)) + " · " + StudioEdit.clock(pr.durMs).substringBefore('.') + " · ${pr.w}×${pr.h}")
                muted(summary(pr))
                row(
                    btn(ctx, t("Открыть", "Open"), style = BtnStyle.TONAL) { start(pr) },
                    btn(ctx, t("🗑 Удалить", "🗑 Delete"), style = BtnStyle.TEXT) {
                        confirm(t("Удалить проект?", "Delete the project?"), t("Блоки и копия видео удалятся; готовые ролики в галерее останутся.",
                            "The blocks and the copy of the video are deleted; exported videos in the gallery stay.")) {
                            StudioEditStore.delete(this@StudioEditorActivity, pr.id)
                            chooser()
                        }
                    }
                )
            }
        }
        page(t("Редактор Студии", "Studio editor"), c.root)
    }

    private fun summary(p: EProject): String {
        if (p.blocks.isEmpty()) return t("Пока без блоков", "No blocks yet")
        val n = p.blocks.groupingBy { it.kind }.eachCount()
        return listOfNotNull(
            n["scheme"]?.let { t("схем: $it", "schemes: $it") }, n["image"]?.let { t("картинок: $it", "pictures: $it") },
            n["text"]?.let { t("надписей: $it", "captions: $it") }, n["emoji"]?.let { t("эмодзи: $it", "emoji: $it") }
        ).joinToString(" · ") + if (p.exported.isNotEmpty()) t(" · экспортирован", " · exported") else ""
    }

    private fun start(p: EProject) {
        stopPlayer()
        project = p
        intent = Intent(intent).putExtra(EXTRA_PROJECT, p.id)
        words = StudioEditStore.words(this, p.id)
        history.clear()
        gestureBase = null
        selected = null
        curMs = 0
        buildEditor()
        loadThumbs(p)
    }

    // ---------- layout ----------

    private fun buildEditor() {
        val p = project ?: return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val frame = FrameLayout(this)
        val tv = TextureView(this)
        val st = EditStage(this) { w, h -> StudioEditPainter(w, h, dir, words, StudioPrefs.accent, styleDir) }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(st, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(frame, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> box.post { fitFrame(box, frame) } }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status())
        root.addView(transport())
        val tl = EditTimeline(this).apply {
            totalMs = p.durMs
            aspect = p.w.toFloat() / p.h
            blocks = p.blocks
        }
        root.addView(FrameLayout(this).apply {
            setBackgroundColor(0xFF15171B.toInt())
            setPadding(0, dp(6), 0, dp(4))
            addView(tl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        stage = st
        timeline = tl
        texture = tv
        toolsRow = row
        st.blocks = p.blocks
        st.onSelect = { id ->
            player?.pause()
            select(id, seek = false)
        }
        st.onChange = { b, done -> live(b, done) }
        st.onCorner = { id, k ->
            when (k) {
                0 -> delete(id)
                1 -> StudioEdit.find(blocks, id)?.let { edit(it) }
                3 -> duplicate(id)
            }
        }
        st.onEmptyTap = { toggle() }
        st.onDoubleTap = { id -> StudioEdit.find(blocks, id)?.let { edit(it) } }
        tl.onScrub = { ms, dragging ->
            player?.takeIf { it.isPlaying }?.pause()
            seekTo(ms, !dragging, fromTimeline = true)
        }
        tl.onSelect = { id ->
            player?.pause()
            select(id, seek = true)
        }
        tl.onChange = { b, done -> live(b, done) }
        page(t("Редактор", "Editor"), root, actions = listOf("↶" to { undo() }, "↷" to { redo() }), scroll = false)
        tl.post {
            tl.fitAll()
            tl.setPlayhead(curMs)
        }
        ensurePlayer()
        sync()
        tools()
        watch()
    }

    /** The video frame keeps the shape of the video inside the black area. */
    private fun fitFrame(box: View, frame: View) {
        val p = project ?: return
        if (box.width <= 0 || box.height <= 0) return
        val k = min(box.width.toFloat() / p.w, box.height.toFloat() / p.h)
        val w = max(1, (p.w * k).toInt())
        val h = max(1, (p.h * k).toInt())
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) frame.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun roundBtn(glyph: String, big: Boolean = false, onClick: () -> Unit): TextView = text(this, glyph, if (big) 20f else 17f, bold = true,
        color = if (big) Palette.ON_ACCENT else Palette.ON_SURFACE).apply {
        gravity = Gravity.CENTER
        val s = dp(if (big) 48 else 40)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(4) }
        background = ripple(this@StudioEditorActivity, roundBg(if (big) Palette.ACCENT else 0x00000000, if (big) 24f else 20f, this@StudioEditorActivity),
            Palette.ON_SURFACE)
        setOnClickListener { onClick() }
    }

    private fun transport(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        val tt = text(this, "", 12f, bold = true).apply {
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        row.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(roundBtn("⏪") { step(-100) }.apply { contentDescription = t("Назад на 0,1 с", "Back 0.1 s") })
        val play = roundBtn("▶", big = true) { toggle() }.apply { contentDescription = t("Играть / пауза", "Play / pause") }
        row.addView(play)
        row.addView(roundBtn("⏩") { step(100) }.apply { contentDescription = t("Вперёд на 0,1 с", "Forward 0.1 s") })
        row.addView(text(this, "＋", 22f, bold = true, color = Palette.ON_FAB).apply {
            gravity = Gravity.CENTER
            contentDescription = t("Вставить", "Insert")
            background = ripple(this@StudioEditorActivity, roundBg(Palette.FAB, 14f, this@StudioEditorActivity), Palette.ON_FAB)
            setOnClickListener { insertSheet() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(8) })
        row.addView(btn(this, t("⬆ Экспорт", "⬆ Export"), primary = true) { export() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        timeText = tt
        playBtn = play
        return row
    }

    /** A line with the progress of the export or of the AI, with "Stop". */
    private fun status(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(2))
            visibility = View.GONE
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = text(this, "", 13f).apply { isSingleLine = true }
        line.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val stop = text(this, t("Стоп", "Stop"), 13f, bold = true, color = Palette.ACCENT).apply { setPadding(dp(12), dp(4), dp(4), dp(4)) }
        line.addView(stop)
        row.addView(line)
        val bar = busyBar(this)
        row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        statusRow = row
        statusText = tv
        statusBar = bar
        statusStop = stop
        return row
    }

    private fun watch() {
        val row = statusRow ?: return
        val ex = BgTasks.find(TASK_EXPORT)
        val task = ex ?: BgTasks.find(TASK_AI)
        if (task == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        statusText?.text = (if (ex != null) "⬆ " else "🤖 ") + task.message.ifBlank { task.title }
        statusBar?.showProgress(task.message)
        statusStop?.setOnClickListener { BgTasks.stop(task) }
    }

    /** Bottom bar: insert actions, or the actions of the selected block. */
    private fun tools() {
        val row = toolsRow ?: return
        row.removeAllViews()
        val b = StudioEdit.find(blocks, selected)
        val items: List<Pair<String, () -> Unit>> = if (b == null) listOf(
            t("＋ Вставить", "＋ Insert") to { insertSheet() },
            t("🧩 Схема ИИ", "🧩 AI scheme") to { aiSchemeSheet(null) },
            t("✍️ Схема текстом", "✍️ Scheme from text") to { textSchemeSheet(null) },
            t("🖼 Картинка", "🖼 Picture") to { pickImage(null) },
            t("🌐 Фото по запросу", "🌐 Photo search") to { photoSheet(null) },
            t("T Надпись", "T Caption") to { captionSheet(null) },
            t("😀 Эмодзи", "😀 Emoji") to { emojiSheet(null) },
            t("📂 Проекты", "📂 Projects") to { chooser() }
        ) else buildList {
            add(t("✓ Готово", "✓ Done") to { select(null) })
            add(t("✎ Изменить", "✎ Edit") to { edit(b) })
            if (b.kind != "emoji") add(t("🎨 Вид", "🎨 Look") to { lookSheet(b) })
            add(t("⏱ Время", "⏱ Timing") to { timingSheet(b) })
            add(t("✂ Разрезать", "✂ Split") to { split(b) })
            add(t("⧉ Дубль", "⧉ Duplicate") to { duplicate(b.id) })
            add(t("⇤ Начало сюда", "⇤ Start here") to { change(b.id) { StudioEdit.startAt(it, curMs, total) } })
            add(t("⇥ Конец сюда", "⇥ End here") to { change(b.id) { StudioEdit.endAt(it, curMs, total) } })
            add(t("🎞 Анимация", "🎞 Animation") to { animSheet(b) })
            add(t("◐ Прозрачность", "◐ Opacity") to { opacitySheet(b) })
            add(t("⬆ Выше", "⬆ Forward") to { commit(StudioEdit.reorder(blocks, b.id, 1)) })
            add(t("⬇ Ниже", "⬇ Backward") to { commit(StudioEdit.reorder(blocks, b.id, -1)) })
            add(t("⊙ В центр", "⊙ Centre") to { change(b.id) { it.copy(x = 0.5f, y = 0.5f, rot = 0f) } })
            if (b.kind == "scheme") add(t("🤖 Переделать ИИ", "🤖 Redo with AI") to { aiSchemeSheet(b) })
            add(t("🗑 Удалить", "🗑 Delete") to { delete(b.id) })
        }
        for ((label, f) in items) row.addView(chip(this, label, selected != null && label == items.first().first) { f() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        (row.parent as? HorizontalScrollView)?.scrollTo(0, 0)
    }

    // ---------- player ----------

    private fun ensurePlayer() {
        val p = project ?: return
        val tv = texture ?: return
        val ex = player ?: ExoPlayer.Builder(this).build().also { ex ->
            player = ex
            ex.setSeekParameters(SeekParameters.EXACT)
            ex.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlay()

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        curMs = total
                        sync()
                        updatePlay()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    toast(t("Видео не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName))
                }
            })
            ex.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(StudioEditStore.dir(this, p.id), StudioEditStore.BASE))))
            ex.prepare()
            ex.seekTo(curMs)
        }
        ex.setVideoTextureView(tv)
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed || isDestroyed) return
            val ex = player
            if (ex != null && ex.isPlaying) {
                curMs = ex.currentPosition.coerceIn(0, total)
                sync()
            }
            window.decorView.postOnAnimation(this)
        }
    }

    private fun sync(fromTimeline: Boolean = false) {
        stage?.timeMs = curMs
        if (!fromTimeline) timeline?.setPlayhead(curMs)
        timeText?.text = StudioEdit.clock(curMs) + " / " + StudioEdit.clock(total)
    }

    private fun updatePlay() {
        playBtn?.text = if (player?.isPlaying == true) "⏸" else "▶"
    }

    private fun pause() {
        player?.pause()
    }

    private fun toggle() {
        val ex = player ?: return
        if (ex.isPlaying) ex.pause() else {
            if (curMs >= total - 100) seekTo(0)
            ex.play()
        }
    }

    private fun step(d: Long) {
        pause()
        seekTo(curMs + d)
    }

    /** Moves to [ms]; while the finger still moves ([final] false) the player seeks at most every 40 ms. */
    private fun seekTo(ms: Long, final: Boolean = true, fromTimeline: Boolean = false) {
        curMs = ms.coerceIn(0, total)
        sync(fromTimeline)
        val ex = player ?: return
        val now = SystemClock.uptimeMillis()
        if (final || now - lastSeekAt > 40) {
            lastSeekAt = now
            ex.seekTo(curMs)
        }
    }

    private fun loadThumbs(p: EProject) {
        val f = File(StudioEditStore.dir(this, p.id), StudioEditStore.BASE)
        val th = dp(46)
        val tw = max(8, (th * p.w / p.h.toFloat()).toInt())
        Lumen.async({
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val n = (p.durMs / 1000).toInt().coerceIn(8, 48)
                (0 until n).mapNotNull { i ->
                    val ms = p.durMs * i / n + p.durMs / (2 * n)
                    val bmp = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw * 2, th * 2)
                    else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        Bitmap.createScaledBitmap(full, tw * 2, th * 2, true).also { if (it !== full) full.recycle() }
                    }
                    bmp?.let { ms to it }
                }
            } finally {
                runCatching { r.release() }
            }
        }) { list -> if (project?.id == p.id) timeline?.thumbs = list }
    }

    // ---------- changes ----------

    private fun snapshot(): String = StudioEdit.blocksJson(blocks).toString()

    private fun setBlocks(list: List<EBlock>) {
        val p = project ?: return
        project = p.copy(blocks = list)
        stage?.blocks = list
        timeline?.blocks = list
    }

    private fun save() {
        val p = project ?: return
        runCatching { StudioEditStore.save(applicationContext, p) }.onFailure { L.w("studio", "edit save: ${it.message}") }
    }

    /** A finished change: remembered for undo, saved, the selection kept when the block is still there. */
    private fun commit(list: List<EBlock>, sel: String? = selected) {
        history.push(snapshot())
        setBlocks(list)
        save()
        select(sel?.takeIf { id -> list.any { it.id == id } }, seek = false)
    }

    private fun change(id: String, f: (EBlock) -> EBlock) {
        val b = StudioEdit.find(blocks, id) ?: return
        commit(StudioEdit.replace(blocks, StudioEdit.clampTime(f(b), total)))
    }

    /** A block changed by a gesture on the frame or the timeline; [done] at its end. */
    private fun live(b: EBlock, done: Boolean) {
        if (gestureBase == null) {
            gestureBase = snapshot()
            pause()
        }
        setBlocks(StudioEdit.replace(blocks, StudioEdit.clampTime(b, total)))
        if (done) {
            gestureBase?.let { history.push(it) }
            gestureBase = null
            save()
        }
    }

    private fun select(id: String?, seek: Boolean = true) {
        selected = id
        stage?.selected = id
        timeline?.selected = id
        if (seek && id != null) StudioEdit.find(blocks, id)?.let { b ->
            if (curMs < b.startMs || curMs >= b.endMs) seekTo(b.startMs + min(StudioEdit.animMs(b) + 60, b.durMs / 2))
        }
        tools()
    }

    private fun undo() {
        val s = history.undo(snapshot()) ?: return toast(t("Нечего отменять", "Nothing to undo"))
        restore(s)
    }

    private fun redo() {
        val s = history.redo(snapshot()) ?: return toast(t("Нечего возвращать", "Nothing to redo"))
        restore(s)
    }

    private fun restore(s: String) {
        setBlocks(StudioEdit.blocksFrom(runCatching { JSONArray(s) }.getOrNull()))
        save()
        stage?.refresh()
        select(selected?.takeIf { id -> blocks.any { it.id == id } }, seek = false)
    }

    private fun delete(id: String) = commit(StudioEdit.remove(blocks, id), null)

    private fun duplicate(id: String) {
        val (list, nid) = StudioEdit.duplicate(blocks, id, total) ?: return
        commit(list, nid)
        select(nid, seek = true)
    }

    private fun split(b: EBlock) {
        val list = StudioEdit.split(blocks, b.id, curMs)
            ?: return toast(t("Поставьте курсор внутрь блока, не у самого края", "Put the playhead inside the block, not at its very edge"))
        commit(list, b.id)
    }

    /** A new block at [at] of project [p] (list [list]); its place and size by kind. */
    private fun newBlock(p: EProject, list: List<EBlock>, kind: String, at: Long, durMs: Long, ratio: Float, build: (EBlock) -> EBlock): EBlock {
        val (s, d) = StudioEdit.placeAt(at, p.durMs, durMs)
        val portrait = p.h >= p.w
        val w = StudioEdit.fitWidth(StudioEdit.defaultWidth(kind, portrait), ratio, p.w, p.h)
        val y = when (kind) {
            "text" -> 0.24f
            "emoji" -> 0.2f
            "image" -> 0.45f
            else -> 0.5f
        }
        return build(EBlock(StudioEdit.nextId(list), kind, s, d, x = if (kind == "emoji") 0.8f else 0.5f, y = y, w = w, ratio = ratio))
    }

    /**
     * Puts the result of background work into project [pid]: on screen when it is open, else straight into its
     * file (the screen may have been closed while the AI worked).
     */
    private fun deliver(pid: String, mutate: (EProject, List<EBlock>) -> List<EBlock>) {
        val p = project
        if (!isDestroyed && p != null && p.id == pid) {
            val old = p.blocks.map { it.id }.toSet()
            val list = mutate(p, p.blocks)
            val added = list.lastOrNull { it.id !in old }?.id
            commit(list, added ?: selected)
            stage?.refresh()
            if (added != null) select(added, seek = true)
        } else {
            val app = applicationContext
            val stored = StudioEditStore.load(StudioEditStore.dir(app, pid)) ?: return
            StudioEditStore.save(app, stored.copy(blocks = mutate(stored, stored.blocks)))
        }
    }

    private fun addNew(kind: String, at: Long, durMs: Long, ratio: Float, build: (EBlock) -> EBlock) {
        val p = project ?: return
        deliver(p.id) { pr, list -> list + newBlock(pr, list, kind, at, durMs, ratio, build) }
    }

    // ---------- names ----------

    private fun layoutName(l: String) = when (l) {
        "chain" -> t("Цепочка", "Chain")
        "hub" -> t("Звезда", "Hub")
        "tree" -> t("Дерево", "Tree")
        "timeline" -> t("Лента времени", "Timeline")
        "funnel" -> t("Воронка", "Funnel")
        "pyramid" -> t("Пирамида", "Pyramid")
        "matrix" -> t("Матрица 2×2", "2×2 matrix")
        "venn" -> t("Круги Венна", "Venn")
        else -> t("Авто", "Auto")
    }

    private fun lookName(l: String) = when (l) {
        "plate" -> t("Плашка", "Plate")
        "shadow" -> t("Контур", "Outline")
        "accent" -> t("Акцент", "Accent")
        "light" -> t("Светлая", "Light")
        "plain" -> t("Как есть", "As is")
        "round" -> t("Скругление", "Rounded")
        "card" -> t("Карточка", "Card")
        "circle" -> t("Круг", "Circle")
        else -> l
    }

    private fun animName(a: String) = when (a) {
        "none" -> t("Нет", "None")
        "fade" -> t("Плавно", "Fade")
        "pop" -> t("Пружина", "Pop")
        "slide" -> t("Снизу", "Slide")
        else -> t("Наезд", "Zoom")
    }

    private fun styles(): List<Pair<String, String>> = SchemeStyles.list(styleDir).filter { it.place == "overlay" || it.place == "panel" || it.id == SchemeStyles.AUTO }
        .map { it.id to if (it.id == SchemeStyles.AUTO) t("Авто (светлая)", "Auto (light)") else it.name }

    private fun label(c: Col, s: String) = c.add(text(this, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)

    private fun lang(): String = Studio.langName(Studio.voiceLang(StudioPrefs.voiceLang,
        EdgeTts.detectLang(words.take(400).joinToString(" ") { it.text }), Settings.lang == "en"))

    private fun formatText(p: EProject) = when {
        p.h > p.w * 1.1f -> t("вертикального ролика 9:16 (Shorts)", "vertical 9:16 video (Shorts)")
        p.w > p.h * 1.1f -> t("горизонтального ролика 16:9", "horizontal 16:9 video")
        else -> t("квадратного ролика 1:1", "square 1:1 video")
    }

    private fun aspect(p: EProject) = when {
        p.h > p.w * 1.1f -> StudioAspect.PORTRAIT
        p.w > p.h * 1.1f -> StudioAspect.LANDSCAPE
        else -> StudioAspect.SQUARE
    }

    // ---------- insert ----------

    private fun insertSheet() {
        pause()
        val at = curMs
        bottomSheet(t("Вставить в ", "Insert at ") + StudioEdit.clock(at)) { dismiss ->
            StudioEdit.momentText(words, at, 1000, 5000).takeIf { it.isNotBlank() }?.let { muted("«" + it.take(160) + "»") }
            fun item(label: String, sub: String, f: () -> Unit) {
                add(btn(ctx, label, style = BtnStyle.TONAL) {
                    dismiss()
                    f()
                }, 12)
                muted(sub)
            }
            item(t("🧩 Инфосхема от ИИ", "🧩 AI info-scheme"), t("по словам этого момента и вашему пожеланию", "from the words of this moment and your wish")) { aiSchemeSheet(null) }
            item(t("✍️ Схема своим текстом", "✍️ Scheme from your text"), t("строки «эмодзи Подпись — значение», без ИИ", "lines \"emoji Label — value\", no AI")) { textSchemeSheet(null) }
            item(t("🖼 Картинка из галереи", "🖼 Picture from the gallery"), t("фото, скриншот, логотип (PNG с прозрачностью тоже)", "a photo, screenshot or logo (transparent PNG too)")) { pickImage(null) }
            item(t("🌐 Фото по запросу", "🌐 Photo search"), t("бесплатные фото Openverse; запрос может подобрать ИИ", "free Openverse photos; the AI can suggest the query")) { photoSheet(null) }
            item(t("T Надпись", "T Caption"), t("свой текст или варианты от ИИ", "your text or AI suggestions")) { captionSheet(null) }
            item(t("😀 Эмодзи", "😀 Emoji"), t("крупный значок поверх кадра", "a big icon over the frame")) { emojiSheet(null) }
        }
    }

    private fun aiSchemeSheet(old: EBlock?) {
        pause()
        val p = project ?: return
        if (BgTasks.has(TASK_AI)) return toast(t("ИИ уже работает — дождитесь или остановите", "The AI is already working — wait or stop it"))
        val at = old?.startMs ?: curMs
        var layout = old?.scheme?.layout ?: "auto"
        var durMs = 5000L
        var style = old?.look?.ifEmpty { null } ?: lastStyle
        bottomSheet(if (old == null) t("🧩 Инфосхема от ИИ", "🧩 AI info-scheme") else t("🤖 Переделать схему", "🤖 Redo the scheme")) { dismiss ->
            val said = StudioEdit.momentText(words, at, 2000, 10_000)
            if (said.isNotBlank()) muted(t("Звучит с ", "Spoken from ") + StudioEdit.clock(at) + ": «" + said.take(260) + "»")
            else muted(t("У этого видео нет слов с таймингом — опишите, какую схему нарисовать.", "This video has no timed words — describe the scheme to draw."))
            val wish = edit(t("Что показать (необязательно)", "What to show (optional)"), old?.ask.orEmpty(), multiline = true)
            label(this, t("Тип схемы", "Scheme type"))
            add(choiceChips(ctx, listOf("auto" to t("Авто", "Auto")) + StudioScheme.LAYOUTS.map { it to layoutName(it) }, layout) { layout = it })
            if (old == null) {
                label(this, t("Длительность", "Length"))
                add(choiceChips(ctx, listOf(3000L to "3 с", 5000L to "5 с", 8000L to "8 с", 12_000L to "12 с"), durMs) { durMs = it })
            }
            label(this, t("Стиль", "Style"))
            add(choiceChips(ctx, styles(), style) { style = it })
            button(t("🤖 Нарисовать", "🤖 Draw"), primary = true) {
                val w = wish.text.toString().trim()
                if (said.isBlank() && w.isBlank()) return@button toast(t("Опишите схему", "Describe the scheme"))
                dismiss()
                lastStyle = style
                runAiScheme(p, at, w, layout, durMs, style, old?.id)
            }
        }
    }

    private fun runAiScheme(p: EProject, at: Long, wish: String, layout: String, durMs: Long, style: String, replaceId: String?) {
        val (numbered, range) = StudioEdit.momentNumbered(words, at)
        val vars = mapOf(
            "format" to formatText(p), "lang" to lang(), "at" to StudioEdit.clock(at),
            "moment" to numbered.ifEmpty { t("(речи с таймингом нет — опирайся на пожелание автора)", "(no timed speech — follow the author's wish)") },
            "wish" to if (wish.isNotEmpty()) "Пожелание автора: $wish" else "",
            "layout" to if (layout != "auto") "Тип схемы (layout): $layout." else "Тип схемы (layout) выбери сам по смыслу.",
            "protocol" to StudioScheme.PROTOCOL
        )
        val system = "You are an infographic designer for videos. Answer with JSON only."
        BgTasks.run(this, TASK_AI, t("Редактор: ИИ рисует схему", "Editor: the AI draws a scheme"), BgTasks.reopen(this), {
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.smartModel, system, Prompts.fill("studio_edit_scheme", vars), json = true, maxTokens = 1800,
                task = "studio_edit_scheme", pid = "studio")
            var s = StudioEdit.parseScheme(r.text)
            var pr = StudioScheme.problems(s)
            if (pr.isNotEmpty()) {
                BgTasks.progress(TASK_AI, t("Исправляю схему", "Fixing the scheme"))
                val json = s?.let { StudioScheme.toJson(it).put("scene", 1).toString() } ?: r.text.take(4000)
                val r2 = Llm.complete(prov, prov.smartModel, system, Prompts.fill("studio_scheme_fix", mapOf(
                    "errors" to pr.joinToString("; "), "protocol" to StudioScheme.PROTOCOL, "json" to "[$json]"
                )), json = true, maxTokens = 1800, task = "studio_scheme_fix", pid = "studio")
                s = StudioEdit.parseScheme(r2.text) ?: s
                pr = StudioScheme.problems(s)
            }
            if (s == null || pr.isNotEmpty()) throw LlmException(t("ИИ не смог составить схему: ", "The AI could not make the scheme: ") + pr.joinToString("; "), r.text)
            StudioScheme.repair(s, range.takeIf { !it.isEmpty() })
        }, { e -> if (!isDestroyed) err(e) }) { s ->
            val ask = wish
            if (replaceId != null) deliver(p.id) { _, list ->
                list.map { if (it.id == replaceId) it.copy(scheme = s, look = style, ask = ask) else it }
            } else deliver(p.id) { pr, list ->
                list + newBlock(pr, list, "scheme", at, durMs, StudioEdit.schemeRatio(pr.w, pr.h)) {
                    it.copy(scheme = s, look = style, ask = ask, animIn = "fade", animOut = "fade")
                }
            }
        }
    }

    private fun textSchemeSheet(old: EBlock?) {
        pause()
        val at = old?.startMs ?: curMs
        var layout = old?.scheme?.layout ?: "auto"
        var style = old?.look?.ifEmpty { null } ?: lastStyle
        bottomSheet(if (old == null) t("✍️ Схема своим текстом", "✍️ Scheme from your text") else t("✎ Схема", "✎ Scheme")) { dismiss ->
            muted(t("Каждый узел — строка: «эмодзи Подпись — значение». Первая строка с двоеточием в конце — заголовок. 2–7 строк.",
                "One node per line: \"emoji Label — value\". A first line ending with a colon is the title. 2–7 lines."))
            val e = edit(t("Заголовок:\n🔍 Поиск — 100%\n🛒 Покупка — 5%", "Title:\n🔍 Search — 100%\n🛒 Purchase — 5%"),
                old?.scheme?.let { StudioEdit.schemeLines(it) }.orEmpty(), multiline = true)
            label(this, t("Тип схемы", "Scheme type"))
            add(choiceChips(ctx, listOf("auto" to t("Авто", "Auto")) + StudioScheme.LAYOUTS.map { it to layoutName(it) }, layout) { layout = it })
            label(this, t("Стиль", "Style"))
            add(choiceChips(ctx, styles(), style) { style = it })
            button(if (old == null) t("Вставить", "Insert") else t("Применить", "Apply"), primary = true) {
                val s = StudioEdit.schemeFromLines(e.text.toString(), layout, old?.scheme?.takeIf { it.layout == layout || layout == "auto" })
                    ?: return@button toast(when (layout) {
                        "matrix" -> t("Матрице нужны ровно 4 строки и оси — выберите другой тип или ИИ", "A matrix needs exactly 4 lines and axes — pick another type or the AI")
                        "venn" -> t("Кругам Венна нужно 2–3 строки", "Venn needs 2–3 lines")
                        "funnel", "pyramid", "timeline" -> t("Для этого типа нужно 3–6 строк", "This type needs 3–6 lines")
                        "hub" -> t("Звезде нужно 3–7 строк", "A hub needs 3–7 lines")
                        else -> t("Нужно 2–7 строк", "2–7 lines are needed")
                    })
                dismiss()
                lastStyle = style
                if (old != null) change(old.id) { it.copy(scheme = s, look = style) }
                else project?.let { p -> addNew("scheme", at, 5000L, StudioEdit.schemeRatio(p.w, p.h)) { it.copy(scheme = s, look = style, animIn = "fade", animOut = "fade") } }
                stage?.refresh()
            }
            if (old != null) button(t("🤖 Переделать ИИ", "🤖 Redo with AI"), style = BtnStyle.TEXT) {
                dismiss()
                aiSchemeSheet(old)
            }
        }
    }

    private fun pickImage(replace: String?) {
        pause()
        pickFor = replace
        pickAt = curMs
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMAGE -> importImage(uri)
            REQ_VIDEO -> importVideo(uri)
        }
    }

    /** A picture from the phone: scaled to 2048 px at most, turned upright by its EXIF, kept as PNG when it has transparency. */
    private fun saveImage(uri: Uri, d: File): Pair<String, Float> {
        val cr = applicationContext.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) error(t("Это не картинка", "This is not a picture"))
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 2048) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error(t("Картинка не читается", "The picture cannot be read"))
        val orient = if (Build.VERSION.SDK_INT >= 24) runCatching {
            cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1 else 1
        val deg = when (orient) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val png = bmp.hasAlpha()
        val name = "img/i${System.currentTimeMillis()}." + if (png) "png" else "jpg"
        val f = File(d, name).apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        val ratio = bmp.height.toFloat() / bmp.width
        bmp.recycle()
        return name to ratio
    }

    private fun importImage(uri: Uri) {
        val p = project ?: return
        val replace = pickFor
        val at = pickAt
        val d = dir
        Lumen.async({ saveImage(uri, d) }, { e -> if (!isDestroyed) err(e) }) { (file, ratio) ->
            if (project?.id != p.id) return@async
            if (replace != null) change(replace) { it.copy(file = file, ratio = ratio, text = "") }
            else addNew("image", at, StudioEdit.DEFAULT_MS, ratio) { it.copy(file = file, look = "round") }
            stage?.refresh()
        }
    }

    private fun imageSheet(b: EBlock) {
        bottomSheet(t("🖼 Картинка", "🖼 Picture")) { dismiss ->
            if (b.text.isNotBlank()) muted(b.text)
            button(t("Заменить из галереи", "Replace from the gallery"), style = BtnStyle.TONAL) {
                dismiss()
                pickImage(b.id)
            }
            button(t("Найти другое фото", "Find another photo"), style = BtnStyle.TONAL) {
                dismiss()
                photoSheet(b)
            }
        }
    }

    private fun photoSheet(old: EBlock?) {
        pause()
        val p = project ?: return
        val at = old?.startMs ?: curMs
        bottomSheet(t("🌐 Фото по запросу", "🌐 Photo search")) { dismiss ->
            muted(t("Бесплатные фото Openverse (Wikimedia, Flickr и др.). С телефона уходит только запрос; автор и лицензия сохраняются для описания.",
                "Free Openverse photos (Wikimedia, Flickr and more). Only the query leaves the phone; the author and licence are kept for the description."))
            val q = edit(t("Что на фото (лучше по-английски)", "What is in the photo (English works best)"), old?.ask?.ifEmpty { null } ?: StudioEdit.keyWords(words, at))
            val alts = FlowLayout(ctx, dp(6), dp(6))
            add(alts)
            button(t("🤖 Подобрать запрос ИИ", "🤖 Suggest a query with AI"), style = BtnStyle.TONAL) {
                val moment = StudioEdit.momentText(words, at, 2000, 6000)
                val wish = q.text.toString().trim()
                if (moment.isBlank() && wish.isBlank()) return@button toast(t("Напишите, что нужно на фото", "Write what the photo should show"))
                alts.removeAllViews()
                alts.addView(text(ctx, t("ИИ думает…", "The AI is thinking…"), 13f, color = Palette.MUTED))
                Lumen.async({
                    val prov = AiTasks.requireProvider()
                    val r = Llm.complete(prov, prov.fastModel, "You pick stock photo search queries. Answer with JSON only.",
                        Prompts.fill("studio_edit_query", mapOf("moment" to moment.ifBlank { wish }, "wish" to if (wish.isNotBlank() && moment.isNotBlank()) "Пожелание автора: $wish" else "")),
                        json = true, maxTokens = 300, task = "studio_edit_query", pid = "studio")
                    StudioEdit.parseField(r.text, "query", "alt")
                }, { e ->
                    alts.removeAllViews()
                    if (!isDestroyed) err(e)
                }) { list ->
                    alts.removeAllViews()
                    list.firstOrNull()?.let { q.setText(it) }
                    list.forEach { v -> alts.addView(chip(ctx, v) { q.setText(v) }) }
                }
            }
            button(t("🔍 Найти и вставить", "🔍 Find and insert"), primary = true) {
                val s = q.text.toString().trim()
                if (s.isEmpty()) return@button toast(t("Введите запрос", "Enter a query"))
                dismiss()
                fetchPhoto(p, at, s, old?.id)
            }
        }
    }

    private fun fetchPhoto(p: EProject, at: Long, query: String, replace: String?) {
        if (BgTasks.has(TASK_AI)) return toast(t("Уже идёт поиск или работа ИИ", "A search or AI work is already running"))
        val d = StudioEditStore.dir(applicationContext, p.id)
        val asp = aspect(p)
        BgTasks.run(this, TASK_AI, t("Редактор: поиск фото", "Editor: photo search"), BgTasks.reopen(this), { task ->
            val out = File(d, "img/p${System.currentTimeMillis()}.jpg")
            val hit = StudioBroll.fetch(query, asp, false, out, 1600) { task.cancelled }
                ?: throw IllegalStateException(t("Фото по запросу «$query» не нашлось — попробуйте другие слова", "No photo for \"$query\" — try other words"))
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.absolutePath, o)
            val credit = listOf(hit.title.take(60), hit.creator, ("CC " + hit.license.uppercase() + " " + hit.licenseVersion).trim(), hit.landing)
                .filter { it.isNotBlank() }.joinToString(" · ")
            Triple("img/" + out.name, o.outHeight.toFloat() / max(1, o.outWidth), credit)
        }, { e -> if (!isDestroyed) err(e) }) { (file, ratio, credit) ->
            if (replace != null) deliver(p.id) { _, list -> list.map { if (it.id == replace) it.copy(file = file, ratio = ratio, text = credit, ask = query) else it } }
            else deliver(p.id) { pr, list -> list + newBlock(pr, list, "image", at, StudioEdit.DEFAULT_MS, ratio) { it.copy(file = file, look = "round", text = credit, ask = query) } }
            if (!isDestroyed) {
                stage?.refresh()
                toast(t("Фото: ", "Photo: ") + credit)
            }
        }
    }

    private fun captionSheet(old: EBlock?) {
        pause()
        val p = project ?: return
        val at = old?.startMs ?: curMs
        var look = old?.look?.ifEmpty { null } ?: "plate"
        bottomSheet(if (old == null) t("T Надпись", "T Caption") else t("✎ Надпись", "✎ Caption")) { dismiss ->
            val e = edit(t("Текст надписи", "Caption text"), old?.text.orEmpty(), multiline = true)
            label(this, t("Вид", "Look"))
            add(choiceChips(ctx, StudioEdit.TEXT_LOOKS.map { it to lookName(it) }, look) { look = it })
            val variants = FlowLayout(ctx, dp(6), dp(6))
            add(variants)
            val moment = StudioEdit.momentText(words, at, 1000, 6000)
            button(t("🤖 Варианты от ИИ", "🤖 AI suggestions"), style = BtnStyle.TONAL) {
                val wish = e.text.toString().trim()
                if (moment.isBlank() && wish.isBlank()) return@button toast(t("Напишите, о чём надпись", "Write what the caption is about"))
                variants.removeAllViews()
                variants.addView(text(ctx, t("ИИ думает…", "The AI is thinking…"), 13f, color = Palette.MUTED))
                Lumen.async({
                    val prov = AiTasks.requireProvider()
                    val r = Llm.complete(prov, prov.fastModel, "You write short on-screen captions for videos. Answer with JSON only.",
                        Prompts.fill("studio_edit_title", mapOf("format" to formatText(p), "lang" to lang(), "moment" to moment.ifBlank { wish },
                            "wish" to if (wish.isNotBlank() && moment.isNotBlank()) "Пожелание автора: $wish" else "")),
                        json = true, maxTokens = 300, task = "studio_edit_title", pid = "studio")
                    StudioEdit.parseField(r.text, "text")
                }, { ex ->
                    variants.removeAllViews()
                    if (!isDestroyed) err(ex)
                }) { list ->
                    variants.removeAllViews()
                    list.forEach { v -> variants.addView(chip(ctx, v) { e.setText(v) }) }
                }
            }
            button(if (old == null) t("Вставить", "Insert") else t("Применить", "Apply"), primary = true) {
                val s = e.text.toString().trim()
                if (s.isEmpty()) return@button toast(t("Введите текст", "Enter the text"))
                dismiss()
                val ratio = StudioEdit.textRatio(s)
                if (old != null) change(old.id) { it.copy(text = s, look = look, ratio = ratio) }
                else addNew("text", at, 3000L, ratio) { it.copy(text = s, look = look) }
            }
        }
    }

    private fun emojiSheet(old: EBlock?) {
        pause()
        val at = old?.startMs ?: curMs
        bottomSheet(t("😀 Эмодзи", "😀 Emoji")) { dismiss ->
            fun pick(e: String) {
                dismiss()
                if (old != null) change(old.id) { it.copy(text = e) }
                else addNew("emoji", at, 2000L, 1f) { it.copy(text = e) }
            }
            val flow = FlowLayout(ctx, dp(6), dp(6))
            EMOJI.forEach { e -> flow.addView(text(ctx, e, 28f).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = ripple(ctx, roundBg(0x00000000, 10f, ctx), Palette.ON_SURFACE)
                setOnClickListener { pick(e) }
            }) }
            add(flow)
            val own = edit(t("Своё эмодзи или символ", "Your own emoji or symbol"))
            button(t("Вставить", "Insert"), style = BtnStyle.TONAL) {
                val s = own.text.toString().trim()
                if (s.isNotEmpty()) pick(s.take(16))
            }
        }
    }

    // ---------- block settings ----------

    private fun edit(b: EBlock) = when (b.kind) {
        "scheme" -> textSchemeSheet(b)
        "image" -> imageSheet(b)
        "text" -> captionSheet(b)
        else -> emojiSheet(b)
    }

    private fun lookSheet(b: EBlock) {
        val opts = when (b.kind) {
            "text" -> StudioEdit.TEXT_LOOKS.map { it to lookName(it) }
            "image" -> StudioEdit.IMAGE_LOOKS.map { it to lookName(it) }
            "scheme" -> styles()
            else -> emptyList()
        }
        if (opts.isEmpty()) return
        bottomSheet(t("🎨 Вид", "🎨 Look")) { _ ->
            add(choiceChips(ctx, opts, b.look.takeIf { l -> opts.any { it.first == l } } ?: opts.first().first) { v ->
                change(b.id) { it.copy(look = v) }
                if (b.kind == "scheme") lastStyle = v
                stage?.refresh()
            })
        }
    }

    private fun timingSheet(b: EBlock) {
        pause()
        bottomSheet(t("⏱ Время блока", "⏱ Block timing")) { dismiss ->
            muted(t("Секунды, можно «0:12.5». Ролик длится ", "Seconds, \"0:12.5\" works too. The video lasts ") + StudioEdit.clock(total))
            val s = edit(t("Начало, с", "Start, s"), StudioEdit.secs(b.startMs))
            val d = edit(t("Длительность, с", "Length, s"), StudioEdit.secs(b.durMs))
            add(chipRow(ctx, listOf(1000L, 2000L, 3000L, 5000L, 8000L, 12_000L).map { ms -> chip(ctx, StudioEdit.secs(ms).removeSuffix(".0") + t(" с", " s")) { d.setText(StudioEdit.secs(ms)) } }))
            button(t("Применить", "Apply"), primary = true) {
                val st = StudioEdit.parseTime(s.text.toString()) ?: return@button toast(t("Начало — число секунд", "Start must be seconds"))
                val du = StudioEdit.parseSeconds(d.text.toString()) ?: return@button toast(t("Длительность — число секунд", "Length must be seconds"))
                dismiss()
                change(b.id) { it.copy(startMs = st, durMs = du) }
            }
        }
    }

    private fun animSheet(b: EBlock) {
        bottomSheet(t("🎞 Анимация", "🎞 Animation")) { dismiss ->
            label(this, t("Появление", "In"))
            add(choiceChips(ctx, StudioEdit.ANIMS.map { it to animName(it) }, b.animIn) { v -> change(b.id) { it.copy(animIn = v) } })
            label(this, t("Исчезновение", "Out"))
            add(choiceChips(ctx, StudioEdit.ANIMS.map { it to animName(it) }, b.animOut) { v -> change(b.id) { it.copy(animOut = v) } })
            button(t("▶ Посмотреть", "▶ Preview"), style = BtnStyle.TONAL) {
                dismiss()
                val cur = StudioEdit.find(blocks, b.id) ?: return@button
                seekTo(max(0L, cur.startMs - 300))
                player?.play()
            }
        }
    }

    private fun opacitySheet(b: EBlock) {
        bottomSheet(t("◐ Прозрачность", "◐ Opacity")) { _ ->
            val value = text(ctx, "${(b.opacity * 100).toInt()}%", 15f, bold = true)
            add(value)
            add(SeekBar(ctx).apply {
                max = 95
                progress = ((b.opacity * 100).toInt() - 5).coerceIn(0, 95)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        value.text = "${v + 5}%"
                        StudioEdit.find(blocks, b.id)?.let { live(it.copy(opacity = (v + 5) / 100f), false) }
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        StudioEdit.find(blocks, b.id)?.let { live(it, true) }
                    }
                })
            })
        }
    }

    // ---------- export ----------

    private fun export() {
        val p = project ?: return
        if (BgTasks.has(TASK_EXPORT)) return toast(t("Экспорт уже идёт", "The export is already running"))
        if (p.blocks.isEmpty()) return toast(t("Добавьте хотя бы один блок: «＋»", "Add at least one block: \"＋\""))
        pause()
        save()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
        }
        val name = "Lumen_Edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val out = ReportKit.shareFile(this, name)
        val app = applicationContext
        val d = StudioEditStore.dir(app, p.id)
        val ws = words
        val acc = StudioPrefs.accent
        val sd = styleDir
        cancelExport = false
        BgTasks.run(this, TASK_EXPORT, t("Редактор: экспорт ролика", "Editor: exporting the video"), BgTasks.reopen(this), { task ->
            StudioEditRender.render(p, d, ws, acc, sd, out, { cancelExport || task.cancelled }) { m -> BgTasks.progress(TASK_EXPORT, m) }
            BgTasks.progress(TASK_EXPORT, t("Сохранение в галерею…", "Saving to the gallery…"))
            out to StudioJob.saveToMovies(app, out, name)
        }, { e -> if (!isDestroyed) err(e) }, { cancelExport = true }) { (f, saved) ->
            StudioEditStore.load(d)?.let { StudioEditStore.save(app, it.copy(exported = f.absolutePath)) }
            if (project?.id == p.id) project = project?.copy(exported = f.absolutePath)
            L.i("studio", "edit export ${f.length() / 1024} KB, ${p.blocks.size} blocks")
            if (!isDestroyed) exported(f, saved, p.blocks)
        }
        watch()
    }

    private fun exported(f: File, saved: String, done: List<EBlock>) {
        bottomSheet(t("✅ Ролик готов", "✅ The video is ready")) { _ ->
            p(t("Сохранено: ", "Saved: ") + saved)
            row(
                btn(ctx, t("▶ Открыть", "▶ Open"), style = BtnStyle.TONAL) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(StudioJob.uriOf(this@StudioEditorActivity, f), "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (e: Exception) {
                        ReportKit.share(this@StudioEditorActivity, f, false, f.name, "video/mp4")
                    }
                },
                btn(ctx, t("↗ Поделиться", "↗ Share"), style = BtnStyle.TONAL) { ReportKit.share(this@StudioEditorActivity, f, false, f.name, "video/mp4") }
            )
            val credits = done.filter { it.kind == "image" && it.text.isNotBlank() }.map { it.text }.distinct()
            if (credits.isNotEmpty()) button(t("⧉ Авторы фото для описания", "⧉ Photo credits for the description"), style = BtnStyle.TEXT) {
                runCatching {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("credits", credits.joinToString("\n")))
                }
                toast(t("Скопировано", "Copied"))
            }
        }
    }
}

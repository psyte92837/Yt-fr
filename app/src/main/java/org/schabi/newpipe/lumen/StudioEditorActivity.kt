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
    private var undoBtn: TextView? = null
    private var redoBtn: TextView? = null
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

    /** Small first frames (and lengths) of the videos on the start screen. */
    private val thumbCache = HashMap<String, Pair<Bitmap?, Long>>()

    private fun loadThumb(iv: android.widget.ImageView, f: File, length: TextView?, prefix: String) {
        val key = f.absolutePath + ":" + f.lastModified()
        fun show(v: Pair<Bitmap?, Long>) {
            v.first?.let { iv.setImageBitmap(it) }
            if (length != null && v.second > 0) length.text = prefix + " · " + StudioEdit.clock(v.second).substringBefore('.')
        }
        thumbCache[key]?.let { return show(it) }
        val side = dp(96)
        Lumen.async({
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val at = min(1_000_000L, dur * 500)
                val full = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val small = full?.let {
                    val k = side.toFloat() / max(it.width, it.height)
                    Bitmap.createScaledBitmap(it, max(1, (it.width * k).toInt()), max(1, (it.height * k).toInt()), true).also { s -> if (s !== it) it.recycle() }
                }
                small to dur
            } finally {
                runCatching { r.release() }
            }
        }) { v ->
            thumbCache[key] = v
            if (!isDestroyed) show(v)
        }
    }

    /** A row with the first frame of [f], a title and a line under it; a tap opens, 🗑 deletes. */
    private fun videoRow(c: Col, f: File, title: String, sub: String, onOpen: () -> Unit, onDelete: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            background = ripple(this@StudioEditorActivity, roundBg(Palette.CARD, 16f, this@StudioEditorActivity), Palette.ON_SURFACE)
            setOnClickListener { onOpen() }
        }
        val iv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundBg(0xFF000000.toInt(), 10f, this@StudioEditorActivity)
            clipToOutline = true
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(60), dp(80)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(this, title, 15f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val under = text(this, sub, 13f, color = Palette.MUTED)
        texts.addView(under)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        if (onDelete != null) row.addView(iconBtn(this, "🗑") { onDelete() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        c.add(row, 8)
        loadThumb(iv, f, under, sub)
    }

    /**
     * The Editor section (a tab of the Studio): how it works, the latest finished videos of the Studio and the Mix,
     * a video from the phone and the saved projects.
     */
    private fun chooser() {
        stopPlayer()
        project = null
        stage = null
        timeline = null
        statusRow = null
        val c = Col(this)
        c.p(t("Готовый ролик → схемы, картинки, надписи и эмодзи в нужные моменты. Остановите видео, нажмите «＋», двигайте блоки на кадре и на таймлайне, затем «⬆ Экспорт».",
            "A finished video → schemes, pictures, captions and emoji at the right moments. Stop the video, tap \"＋\", move the blocks on the frame and the timeline, then \"⬆ Export\"."), color = Palette.MUTED)
        c.row(
            btn(this, t("📂 Видео с телефона", "📂 Video from the phone"), style = BtnStyle.TONAL) { pickVideo() },
            btn(this, t("❓ Как пользоваться", "❓ How to use"), style = BtnStyle.TEXT) { HelpActivity.open(this, helpTopic) }
        )
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val list = StudioEditStore.list(this)
        val inProjects = list.map { it.source }.toSet()
        // finished videos of the Studio, the Mix and this editor (they all go to the shared folder)
        val recent = ReportKit.shareFile(this, "x.mp4").parentFile?.listFiles { f -> f.name.endsWith(".mp4") && f.length() > 0 }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(6)
        if (recent.isNotEmpty()) {
            c.h2(t("Готовые ролики", "Finished videos"))
            for (f in recent) {
                val speech = sidecar(f).exists()
                val sub = df.format(Date(f.lastModified())) + (if (speech) t(" · 🗣 речь с таймингом", " · 🗣 timed speech") else "") +
                    if (f.absolutePath in inProjects) t(" · есть проект", " · has a project") else ""
                videoRow(c, f, f.name.removeSuffix(".mp4"), sub, { openFile(f) })
            }
        }
        if (list.isNotEmpty()) {
            c.h2(t("Проекты", "Projects") + " (${list.size})")
            for (pr in list) {
                val base = File(StudioEditStore.dir(this, pr.id), StudioEditStore.BASE)
                videoRow(c, base, summary(pr), df.format(Date(pr.created)) + " · ${pr.w}×${pr.h}", { start(pr) }) {
                    confirm(t("Удалить проект?", "Delete the project?"), t("Блоки и копия видео удалятся; готовые ролики в галерее останутся.",
                        "The blocks and the copy of the video are deleted; exported videos in the gallery stay.")) {
                        StudioEditStore.delete(this@StudioEditorActivity, pr.id)
                        chooser()
                    }
                }
            }
        }
        if (recent.isEmpty() && list.isEmpty()) c.empty(t("Пока нет роликов", "No videos yet"),
            t("Соберите ролик в Студии или Миксе — он появится здесь. Или откройте любое видео с телефона.",
                "Build a video in the Studio or the Mix — it shows up here. Or open any video from the phone."),
            t("📂 Видео с телефона", "📂 Video from the phone")) { pickVideo() }
        page(t("✏️ Редактор", "✏️ Editor"), c.root)
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
            words = this@StudioEditorActivity.words
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
        st.portrait = p.h > p.w * 1.1f
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
        page(t("Редактор", "Editor"), root, actions = listOf("☰" to { blocksSheet() }), scroll = false)
        tl.post {
            tl.fitAll()
            tl.setPlayhead(curMs)
        }
        ensurePlayer()
        sync()
        tools()
        watch()
        refreshUndo()
        hintOnce()
    }

    /** The first time: how the editor works, in four lines. */
    private fun hintOnce() {
        val prefs = Lumen.prefs
        if (prefs.getBoolean("studio_edit_hint", false)) return
        prefs.edit().putBoolean("studio_edit_hint", true).apply()
        bottomSheet(t("Как работает редактор", "How the editor works")) { dismiss ->
            p(t("1. Остановите видео в нужном месте (тап по кадру или прокрутка таймлайна) и нажмите «＋».", "1. Stop the video at a moment (tap the frame or scroll the timeline) and tap \"＋\"."))
            p(t("2. Блок на кадре: тяните пальцем, угол ⤡ или два пальца — размер и поворот, ↔ — ширина текста.", "2. A block on the frame: drag it; the ⤡ corner or two fingers scale and turn it, ↔ sets the text width."))
            p(t("3. На таймлайне: выбранный блок тянется целиком, края — длительность; края прилипают к словам и курсору.", "3. On the timeline: drag the selected block, its edges set the length and stick to words and the playhead."))
            if (words.isNotEmpty()) p(t("4. «📝 Текст» — вся речь: тап по слову ставит курсор, выделите фразу — и схема или надпись встанет ровно на неё.",
                "4. \"📝 Text\" is the whole speech: tap a word to go there, select a phrase to put a scheme or caption exactly on it."))
            button(t("Понятно", "Got it"), primary = true) { dismiss() }
        }
    }

    private fun refreshUndo() {
        undoBtn?.alpha = if (history.canUndo) 1f else 0.35f
        redoBtn?.alpha = if (history.canRedo) 1f else 0.35f
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
        tt.setOnClickListener { goToTime() }
        tt.contentDescription = t("Время; тап — перейти к моменту", "Time; tap to go to a moment")
        row.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val un = roundBtn("↶") { undo() }.apply { contentDescription = t("Отменить", "Undo") }
        val re = roundBtn("↷") { redo() }.apply { contentDescription = t("Вернуть", "Redo") }
        row.addView(un)
        row.addView(re)
        val play = roundBtn("▶", big = true) { toggle() }.apply { contentDescription = t("Играть / пауза", "Play / pause") }
        row.addView(play)
        undoBtn = un
        redoBtn = re
        row.addView(text(this, "＋", 22f, bold = true, color = Palette.ON_FAB).apply {
            gravity = Gravity.CENTER
            contentDescription = t("Вставить", "Insert")
            background = ripple(this@StudioEditorActivity, roundBg(Palette.FAB, 14f, this@StudioEditorActivity), Palette.ON_FAB)
            setOnClickListener { insertSheet() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(8) })
        row.addView(btn(this, t("⬆ Экспорт", "⬆ Export"), primary = true) { exportSheet() },
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
        val left = ProgressText.fraction(task.message)?.let { StudioEdit.eta(System.currentTimeMillis() - task.startedAt, it) }
        statusText?.text = (if (ex != null) "⬆ " else "🤖 ") + task.message.ifBlank { task.title } +
            (left?.let { t(" · осталось ~", " · ~") + StudioEdit.clock(it).substringBefore('.') + t("", " left") } ?: "")
        statusBar?.showProgress(task.message)
        statusStop?.setOnClickListener { BgTasks.stop(task) }
    }

    /** Bottom bar: insert actions, or the actions of the selected block. */
    private fun tools() {
        val row = toolsRow ?: return
        row.removeAllViews()
        val b = StudioEdit.find(blocks, selected)
        val items: List<Pair<String, () -> Unit>> = if (b == null) listOfNotNull(
            t("＋ Вставить", "＋ Insert") to { insertSheet() },
            if (words.isNotEmpty()) t("📝 Текст", "📝 Text") to { textSheet() } else null,
            if (words.isNotEmpty()) t("🤖 Схемы на весь ролик", "🤖 Schemes for all") to { autoSchemes() } else null,
            t("🧩 Схема ИИ", "🧩 AI scheme") to { aiSchemeSheet(null) },
            t("✍️ Схема текстом", "✍️ Scheme from text") to { textSchemeSheet(null) },
            t("🖼 Картинка", "🖼 Picture") to { pickImage(null) },
            t("🌐 Фото по запросу", "🌐 Photo search") to { photoSheet(null) },
            t("T Надпись", "T Caption") to { captionSheet(null) },
            t("😀 Эмодзи", "😀 Emoji") to { emojiSheet(null) },
            t("☰ Блоки", "☰ Blocks") to { blocksSheet() },
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

    private fun goToTime() {
        pause()
        prompt(t("Перейти к моменту", "Go to a moment"), t("Секунды или 0:12.5", "Seconds or 0:12.5"), StudioEdit.clock(curMs)) { v ->
            StudioEdit.parseTime(v)?.let { seekTo(it) } ?: toast(t("Не понял время", "Not a time"))
        }
    }

    /** Moves to [ms]; while the finger still moves ([final] false) the player seeks at most every 40 ms. */
    private fun seekTo(ms: Long, final: Boolean = true, fromTimeline: Boolean = false) {
        curMs = ms.coerceIn(0, total)
        sync(fromTimeline)
        val ex = player ?: return
        val now = SystemClock.uptimeMillis()
        if (final || now - lastSeekAt > 40) {
            lastSeekAt = now
            // while the finger moves: the nearest key frame (fast); at the end: the exact frame
            ex.setSeekParameters(if (final) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
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
                val got = ArrayList<Pair<Long, Bitmap>>()
                (0 until n).forEach { i ->
                    val ms = p.durMs * i / n + p.durMs / (2 * n)
                    val bmp = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw * 2, th * 2)
                    else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        Bitmap.createScaledBitmap(full, tw * 2, th * 2, true).also { if (it !== full) full.recycle() }
                    }
                    if (bmp != null) got += ms to bmp
                    // the strip fills in as frames come, not after the last one
                    if (got.size % 8 == 0 && got.isNotEmpty()) {
                        val part = got.toList()
                        Lumen.ui { if (project?.id == p.id) timeline?.thumbs = part }
                    }
                }
                got.toList()
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
        refreshUndo()
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
            refreshUndo()
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
        refreshUndo()
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
    private fun deliver(pid: String, mutate: (EProject, List<EBlock>) -> List<EBlock>) = deliverTo(pid) { pr, list, _ -> mutate(pr, list) }

    /** The same; [mutate] learns whether the project is on screen (true) or only in its file (false). */
    private fun deliverTo(pid: String, mutate: (EProject, List<EBlock>, Boolean) -> List<EBlock>) {
        val p = project
        if (!isDestroyed && p != null && p.id == pid) {
            val old = p.blocks.map { it.id }.toSet()
            val list = mutate(p, p.blocks, true)
            if (list == p.blocks) return
            val added = list.lastOrNull { it.id !in old }?.id
            commit(list, added ?: selected)
            stage?.refresh()
            if (added != null) select(added, seek = true)
        } else {
            val app = applicationContext
            val stored = StudioEditStore.load(StudioEditStore.dir(app, pid)) ?: return
            StudioEditStore.save(app, stored.copy(blocks = mutate(stored, stored.blocks, false)))
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
            if (words.isNotEmpty()) item(t("📝 По тексту речи", "📝 From the speech text"), t("выделите фразу — блок встанет ровно на неё", "select a phrase — the block goes exactly on it")) { textSheet() }
            item(t("🧩 Инфосхема от ИИ", "🧩 AI info-scheme"), t("по словам этого момента и вашему пожеланию", "from the words of this moment and your wish")) { aiSchemeSheet(null) }
            if (words.isNotEmpty()) item(t("🤖 Схемы на весь ролик", "🤖 Schemes for the whole video"), t("ИИ сам выберет места и поставит схемы", "the AI picks the places and puts schemes there")) { autoSchemes() }
            item(t("✍️ Схема своим текстом", "✍️ Scheme from your text"), t("строки «эмодзи Подпись — значение», без ИИ", "lines \"emoji Label — value\", no AI")) { textSchemeSheet(null) }
            item(t("🖼 Картинка из галереи", "🖼 Picture from the gallery"), t("фото, скриншот, логотип (PNG с прозрачностью тоже)", "a photo, screenshot or logo (transparent PNG too)")) { pickImage(null) }
            item(t("🌐 Фото по запросу", "🌐 Photo search"), t("бесплатные фото Openverse; запрос может подобрать ИИ", "free Openverse photos; the AI can suggest the query")) { photoSheet(null) }
            item(t("T Надпись", "T Caption"), t("свой текст или варианты от ИИ", "your text or AI suggestions")) { captionSheet(null) }
            item(t("😀 Эмодзи", "😀 Emoji"), t("крупный значок поверх кадра", "a big icon over the frame")) { emojiSheet(null) }
        }
    }

    private fun aiSchemeSheet(old: EBlock?, span: IntRange? = null) {
        pause()
        val p = project ?: return
        if (BgTasks.has(TASK_AI)) return toast(t("ИИ уже работает — дождитесь или остановите", "The AI is already working — wait or stop it"))
        val at = old?.startMs ?: span?.let { StudioEdit.spanOf(words, it, p.durMs).first } ?: curMs
        var layout = old?.scheme?.layout ?: "auto"
        var durMs = 5000L
        var style = old?.look?.ifEmpty { null } ?: lastStyle
        bottomSheet(if (old == null) t("🧩 Инфосхема от ИИ", "🧩 AI info-scheme") else t("🤖 Переделать схему", "🤖 Redo the scheme")) { dismiss ->
            val said = span?.joinToString(" ") { words[it].text } ?: StudioEdit.momentText(words, at, 2000, 10_000)
            if (span != null) muted(t("Схема встанет на фразу: ", "The scheme goes on the phrase: ") + "«" + said.take(260) + "»")
            else if (said.isNotBlank()) muted(t("Звучит с ", "Spoken from ") + StudioEdit.clock(at) + ": «" + said.take(260) + "»")
            else muted(t("У этого видео нет слов с таймингом — опишите, какую схему нарисовать.", "This video has no timed words — describe the scheme to draw."))
            val wish = edit(t("Что показать (необязательно)", "What to show (optional)"), old?.ask.orEmpty(), multiline = true)
            label(this, t("Тип схемы", "Scheme type"))
            add(choiceChips(ctx, listOf("auto" to t("Авто", "Auto")) + StudioScheme.LAYOUTS.map { it to layoutName(it) }, layout) { layout = it })
            if (old == null && span == null) {
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
                runAiScheme(p, at, w, layout, durMs, style, old?.id, span)
            }
        }
    }

    private fun runAiScheme(p: EProject, at: Long, wish: String, layout: String, durMs: Long, style: String, replaceId: String?, span: IntRange? = null) {
        val (numbered, range) = if (span != null) StudioEdit.numbered(words, span) to span else StudioEdit.momentNumbered(words, at)
        val vars = mapOf(
            "format" to formatText(p), "lang" to lang(), "at" to StudioEdit.clock(at),
            "moment" to numbered.ifEmpty { t("(речи с таймингом нет — опирайся на пожелание автора)", "(no timed speech — follow the author's wish)") },
            "wish" to if (wish.isNotEmpty()) "Пожелание автора: $wish" else "",
            "layout" to if (layout != "auto") "Тип схемы (layout): $layout." else "Тип схемы (layout) выбери сам по смыслу.",
            "protocol" to StudioScheme.PROTOCOL
        )
        val system = "You are an infographic designer for videos. Answer with JSON only."
        // a placeholder shows at once where the scheme goes; it can be moved and trimmed while the AI works
        val ph: EBlock? = if (replaceId == null) {
            val (st, du) = span?.let { StudioEdit.spanOf(words, it, p.durMs) } ?: (at to durMs)
            newBlock(p, blocks, "scheme", st, du, StudioEdit.schemeRatio(p.w, p.h)) { it.copy(look = style, ask = wish, animIn = "fade", animOut = "fade") }
                .also { commit(blocks + it, it.id) }
        } else null
        val target = replaceId ?: ph?.id ?: return
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
        }, { e ->
            // the placeholder goes away (not an undo step)
            if (ph != null && !isDestroyed && project?.id == p.id) {
                setBlocks(StudioEdit.remove(blocks, ph.id))
                save()
                if (selected == ph.id) select(null, seek = false)
            }
            if (!isDestroyed) err(e)
        }) { s ->
            deliverTo(p.id) { _, list, onScreen ->
                when {
                    list.any { it.id == target } -> list.map { if (it.id == target) it.copy(scheme = s, look = if (replaceId != null) style else it.look, ask = wish) else it }
                    // the screen was closed: placeholders are not kept in the file, the scheme goes where it was
                    ph != null && !onScreen -> list + ph.copy(id = StudioEdit.nextId(list), scheme = s)
                    // deleted while the AI worked
                    else -> list
                }
            }
            if (!isDestroyed) stage?.refresh()
        }
    }

    /** "🤖 Schemes for the whole video": the AI reads the speech piece by piece and puts schemes where they explain best. */
    private fun autoSchemes() {
        val p = project ?: return
        pause()
        if (words.isEmpty()) return toast(t("Для этого нужна речь с таймингом (ролик Студии или Микса с озвучкой)", "This needs timed speech (a Studio or Mix video with a voice)"))
        if (BgTasks.has(TASK_AI)) return toast(t("ИИ уже работает — дождитесь или остановите", "The AI is already working — wait or stop it"))
        val segs = StudioEdit.segments(words)
        var style = lastStyle
        bottomSheet(t("🤖 Схемы по всему ролику", "🤖 Schemes for the whole video")) { dismiss ->
            muted(t("ИИ прочитает речь (${segs.size} фрагм.) и поставит инфосхемы туда, где они объяснят лучше слов, — до ${StudioScheme.maxSchemes(segs.size)} шт. Каждая встанет ровно на свою фразу; потом их можно двигать и менять, а «↶» уберёт все сразу.",
                "The AI reads the speech (${segs.size} pieces) and puts info-schemes where they explain better than words — up to ${StudioScheme.maxSchemes(segs.size)}. Each goes exactly on its phrase; move or change them later, \"↶\" removes them all at once."))
            val wish = edit(t("Пожелание (необязательно)", "Wish (optional)"), "", multiline = true)
            label(this, t("Стиль", "Style"))
            add(choiceChips(ctx, styles(), style) { style = it })
            button(t("🤖 Расставить", "🤖 Place them"), primary = true) {
                dismiss()
                lastStyle = style
                runAutoSchemes(p, segs, wish.text.toString().trim(), style)
            }
        }
    }

    private fun runAutoSchemes(p: EProject, segs: List<IntRange>, wish: String, style: String) {
        val ws = words
        val scenes = segs.map { r -> SScene(r.joinToString(" ") { ws[it].text }) }
        val vars = mapOf(
            "format" to formatText(p), "lang" to lang(), "max" to StudioScheme.maxSchemes(segs.size).toString(),
            "wish" to if (wish.isNotEmpty()) "Пожелание автора: $wish" else "", "protocol" to StudioScheme.PROTOCOL,
            "scenes" to StudioScheme.scenesBlock(scenes, ws, segs)
        )
        val system = "You are an infographic designer for videos. Answer with JSON only."
        BgTasks.run(this, TASK_AI, t("Редактор: ИИ расставляет схемы", "Editor: the AI places schemes"), BgTasks.reopen(this), {
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.smartModel, system, Prompts.fill("studio_schemes", vars), json = true,
                maxTokens = (1200 + segs.size * 250).coerceAtMost(12_000), task = "studio_schemes", pid = "studio")
            val found = HashMap<Int, SScheme>()
            val bad = mutableListOf<Pair<org.json.JSONObject, List<String>>>()
            fun take(list: List<Pair<Int, org.json.JSONObject>>) {
                bad.clear()
                for ((n, o) in list) {
                    val i = n - 1
                    if (i !in segs.indices) continue
                    val sc = StudioScheme.fromJson(o)
                    val pr = StudioScheme.problems(sc, segs[i])
                    if (sc != null && pr.isEmpty()) found[i] = StudioScheme.repair(sc, segs[i]) else bad += o to pr
                }
            }
            take(StudioScheme.parseAnswer(r.text) ?: throw LlmException(t("ИИ не вернул схемы в нужном формате", "The AI did not return schemes in the expected format"), r.text))
            if (bad.isNotEmpty()) {
                BgTasks.progress(TASK_AI, t("Исправляю схемы: ${bad.size}", "Fixing schemes: ${bad.size}"))
                val errors = bad.joinToString("\n") { (o, pr) -> "Сцена ${o.optInt("scene")}: " + pr.joinToString("; ") }
                val json = org.json.JSONArray().apply { bad.forEach { put(it.first) } }.toString()
                runCatching {
                    val r2 = Llm.complete(prov, prov.smartModel, system, Prompts.fill("studio_scheme_fix", mapOf("errors" to errors, "protocol" to StudioScheme.PROTOCOL, "json" to json)),
                        json = true, maxTokens = 600 + bad.size * 400, task = "studio_scheme_fix", pid = "studio")
                    StudioScheme.parseAnswer(r2.text)?.let { take(it) }
                }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
            }
            found.toSortedMap().toList()
        }, { e -> if (!isDestroyed) err(e) }) { found ->
            if (found.isEmpty()) return@run toast(t("ИИ не нашёл мест для схем", "The AI found no places for schemes"))
            deliver(p.id) { pr, list ->
                var cur = list
                for ((i, sc) in found) {
                    val (st, du) = StudioEdit.spanOf(ws, segs[i], pr.durMs)
                    cur = cur + newBlock(pr, cur, "scheme", st, du, StudioEdit.schemeRatio(pr.w, pr.h)) {
                        it.copy(scheme = sc, look = style, animIn = "fade", animOut = "fade")
                    }
                }
                cur
            }
            if (!isDestroyed) toast(t("Схем: ${found.size} — ↶ уберёт все", "Schemes: ${found.size} — ↶ removes them all"))
        }
    }

    /** The whole speech as text: a tap on a word goes there; a second tap ends a phrase to put a block exactly on it. */
    private fun textSheet() {
        val p = project ?: return
        if (words.isEmpty()) return toast(t("У этого видео нет речи с таймингом", "This video has no timed speech"))
        pause()
        var a = -1
        var b = -1
        val now = words.indexOfLast { it.startMs <= curMs }
        bottomSheet(t("📝 Текст ролика", "📝 Video text")) { dismiss ->
            muted(t("Тап по слову — перейти к нему. Второй тап — конец фразы: схема, надпись или фото встанут ровно на неё.",
                "Tap a word to go there. A second tap ends a phrase: a scheme, caption or photo goes exactly on it."))
            val info = text(ctx, "", 13f, bold = true, color = Palette.ACCENT)
            add(info)
            val acts = FlowLayout(ctx, dp(6), dp(6))
            add(acts)
            val tv = TextView(ctx).apply {
                textSize = sp(16f)
                setLineSpacing(0f, 1.3f)
                setTextColor(Palette.ON_SURFACE)
                highlightColor = 0
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
            add(tv, 8)
            fun range() = if (a < 0) null else a..(if (b < 0) a else b)
            lateinit var paint: () -> Unit
            fun tap(i: Int) {
                if (a < 0 || b >= 0) {
                    a = i
                    b = -1
                    seekTo(words[i].startMs)
                } else if (i < a) {
                    b = a
                    a = i
                } else b = i
                paint()
            }
            paint = {
                val sb = android.text.SpannableStringBuilder()
                val r = range()
                for (para in StudioEdit.paragraphs(words)) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    val ts = sb.length
                    sb.append(StudioEdit.clock(words[para.first].startMs).substringBefore('.')).append("  ")
                    sb.setSpan(android.text.style.ForegroundColorSpan(Palette.MUTED), ts, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), ts, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    for (i in para) {
                        val st = sb.length
                        sb.append(words[i].text)
                        val k = i
                        sb.setSpan(object : android.text.style.ClickableSpan() {
                            override fun onClick(widget: View) = tap(k)
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.color = Palette.ON_SURFACE
                                ds.isUnderlineText = false
                            }
                        }, st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (r != null && i in r) sb.setSpan(android.text.style.BackgroundColorSpan(Palette.ACCENT_SOFT), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        else if (i == now) sb.setSpan(android.text.style.UnderlineSpan(), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (i != para.last) sb.append(' ')
                    }
                }
                tv.text = sb
                acts.removeAllViews()
                if (r == null) info.text = "" else {
                    val (st, du) = StudioEdit.spanOf(words, r, p.durMs)
                    info.text = StudioEdit.clock(st) + " – " + StudioEdit.clock(st + du) + "  ·  " + r.joinToString(" ") { words[it].text }.take(90)
                    acts.addView(chip(ctx, t("🧩 Схема ИИ", "🧩 AI scheme")) {
                        dismiss()
                        aiSchemeSheet(null, r)
                    })
                    acts.addView(chip(ctx, t("T Надпись", "T Caption")) {
                        dismiss()
                        captionSheet(null, r)
                    })
                    acts.addView(chip(ctx, t("🌐 Фото", "🌐 Photo")) {
                        dismiss()
                        photoSheet(null, r)
                    })
                    acts.addView(chip(ctx, t("▶ Отсюда", "▶ From here")) {
                        dismiss()
                        seekTo(st)
                        player?.play()
                    })
                    acts.addView(chip(ctx, "✕") {
                        a = -1
                        b = -1
                        paint()
                    })
                }
            }
            paint()
        }
    }

    private fun blockName(b: EBlock): String = when (b.kind) {
        "scheme" -> if (b.scheme == null) "🧩 ⏳ " + t("ИИ рисует…", "AI drawing…")
        else "🧩 " + (b.scheme.title.takeIf { it.isNotBlank() } ?: b.scheme.nodes.joinToString(" → ") { it.label }.take(40))
        "image" -> "🖼 " + (b.ask.ifBlank { t("Картинка", "Picture") })
        "text" -> "T " + b.text.replace('\n', ' ').take(40)
        else -> b.text + " " + t("эмодзи", "emoji")
    }

    /** All blocks by time: a tap selects one and goes to it. */
    private fun blocksSheet() {
        if (project == null) return
        pause()
        bottomSheet(t("☰ Блоки", "☰ Blocks") + " (${blocks.size})") { dismiss ->
            if (blocks.isEmpty()) muted(t("Пока пусто: остановите видео в нужном месте и нажмите «＋».", "Empty so far: stop the video at a moment and tap \"＋\"."))
            for (b in blocks.sortedBy { it.startMs }) add(text(ctx, blockName(b) + "\n" + StudioEdit.clock(b.startMs) + " – " + StudioEdit.clock(b.endMs), 15f).apply {
                setPadding(dp(8), dp(10), dp(8), dp(10))
                if (b.id == selected) setTextColor(Palette.ACCENT)
                background = ripple(ctx, roundBg(0x00000000, 12f, ctx), Palette.ON_SURFACE)
                setOnClickListener {
                    dismiss()
                    select(b.id, seek = true)
                }
            }, 2)
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

    private fun photoSheet(old: EBlock?, span: IntRange? = null) {
        pause()
        val p = project ?: return
        val placed = span?.let { StudioEdit.spanOf(words, it, p.durMs) }
        val at = old?.startMs ?: placed?.first ?: curMs
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
                fetchPhoto(p, at, s, old?.id, placed?.second ?: StudioEdit.DEFAULT_MS)
            }
        }
    }

    private fun fetchPhoto(p: EProject, at: Long, query: String, replace: String?, durMs: Long = StudioEdit.DEFAULT_MS) {
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
            else deliver(p.id) { pr, list -> list + newBlock(pr, list, "image", at, durMs, ratio) { it.copy(file = file, look = "round", text = credit, ask = query) } }
            if (!isDestroyed) {
                stage?.refresh()
                toast(t("Фото: ", "Photo: ") + credit)
            }
        }
    }

    private fun captionSheet(old: EBlock?, span: IntRange? = null) {
        pause()
        val p = project ?: return
        val placed = span?.let { StudioEdit.spanOf(words, it, p.durMs) }
        val at = old?.startMs ?: placed?.first ?: curMs
        var look = old?.look?.ifEmpty { null } ?: "plate"
        bottomSheet(if (old == null) t("T Надпись", "T Caption") else t("✎ Надпись", "✎ Caption")) { dismiss ->
            val e = edit(t("Текст надписи", "Caption text"), old?.text ?: span?.joinToString(" ") { words[it].text }.orEmpty(), multiline = true)
            label(this, t("Вид", "Look"))
            add(choiceChips(ctx, StudioEdit.TEXT_LOOKS.map { it to lookName(it) }, look) { look = it })
            val variants = FlowLayout(ctx, dp(6), dp(6))
            add(variants)
            val moment = span?.joinToString(" ") { words[it].text } ?: StudioEdit.momentText(words, at, 1000, 6000)
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
                else addNew("text", at, placed?.second ?: 3000L, ratio) { it.copy(text = s, look = look) }
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
        var chosen = b.look.takeIf { l -> opts.any { it.first == l } } ?: opts.first().first
        bottomSheet(t("🎨 Вид", "🎨 Look")) { dismiss ->
            add(choiceChips(ctx, opts, chosen) { v ->
                chosen = v
                change(b.id) { it.copy(look = v) }
                if (b.kind == "scheme") lastStyle = v
                stage?.refresh()
            })
            if (blocks.count { it.kind == b.kind } > 1) button(t("Применить ко всем таким блокам", "Apply to all blocks of this kind"), style = BtnStyle.TEXT) {
                dismiss()
                commit(blocks.map { if (it.kind == b.kind) it.copy(look = chosen) else it })
                stage?.refresh()
            }
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
            if (blocks.size > 1) button(t("Применить ко всем блокам", "Apply to all blocks"), style = BtnStyle.TEXT) {
                dismiss()
                val cur = StudioEdit.find(blocks, b.id) ?: return@button
                commit(blocks.map { it.copy(animIn = cur.animIn, animOut = cur.animOut) })
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

    /** Quality and a word on how the export works, then the export. */
    private fun exportSheet() {
        val p = project ?: return
        if (BgTasks.has(TASK_EXPORT)) return toast(t("Экспорт уже идёт", "The export is already running"))
        if (p.blocks.none { it.kind != "scheme" || it.scheme != null }) return toast(t("Добавьте хотя бы один блок: «＋»", "Add at least one block: \"＋\""))
        pause()
        val prefs = Lumen.prefs
        var side = prefs.getInt("studio_edit_side", 1920)
        val (ow, oh) = StudioEdit.outSize(p.w, p.h)
        bottomSheet(t("⬆ Экспорт ролика", "⬆ Export the video")) { dismiss ->
            muted(t("Блоков: ${p.blocks.size} · длина ${StudioEdit.clock(p.durMs).substringBefore('.')} · звук оригинала копируется без потерь.",
                "Blocks: ${p.blocks.size} · length ${StudioEdit.clock(p.durMs).substringBefore('.')} · the original sound is copied without loss."))
            label(this, t("Качество", "Quality"))
            add(choiceChips(ctx, listOf(1920 to t("Как оригинал ($ow×$oh)", "As the original ($ow×$oh)"), 1280 to t("720p — быстрее и легче", "720p — faster and smaller")), side) { side = it })
            muted(t("Ролик собирается на видеокарте телефона (обычно быстрее реального времени); если она откажет — на процессоре, медленнее. Экран можно закрыть: прогресс в уведомлении.",
                "The video is built on the phone's GPU (usually faster than real time); if it refuses, on the CPU, slower. You can close the screen: progress is in the notification."))
            button(t("⬆ Экспортировать", "⬆ Export"), primary = true) {
                prefs.edit().putInt("studio_edit_side", side).apply()
                dismiss()
                export(side)
            }
        }
    }

    private fun export(maxSide: Int) {
        val p = project ?: return
        if (BgTasks.has(TASK_EXPORT)) return toast(t("Экспорт уже идёт", "The export is already running"))
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
            StudioEditRender.render(p, d, ws, acc, sd, out, maxSide, { cancelExport || task.cancelled }) { m -> BgTasks.progress(TASK_EXPORT, m) }
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

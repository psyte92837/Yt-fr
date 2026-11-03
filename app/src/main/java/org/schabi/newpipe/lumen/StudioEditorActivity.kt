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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SeekParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        private const val REQ_SPLIT = 7713
        private const val REQ_AUDIO = 7714
        private const val REQ_CLIP = 7715
        private const val REQ_REF = 7716

        private const val TASK = "studioedit:"
        private const val TASK_EXPORT = TASK + "export"
        internal const val TASK_AI = TASK + "ai"
        private const val TASK_REV = TASK + "reverse"

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

    internal var project: EProject? = null
    /** Words of the voice in output time (after cuts and speed changes of the main track). */
    internal var words: List<SWord> = emptyList()
    /** The same words in the time of the original video. */
    private var srcWords: List<SWord> = emptyList()
    private var selected: String? = null
    /** Selected piece of the main track, -1 = none. */
    private var clipSel = -1
    /** Playing as the user sees it (during a freeze frame the player itself stands still). */
    private var playing = false
    private var curClip = -1
    private var holdT0 = 0L
    private val history = EditHistory()
    /** Blocks before the gesture now going on (for undo). */
    private var gestureBase: String? = null
    private var player: ExoPlayer? = null
    internal var curMs = 0L
    private var lastSeekAt = 0L
    private var resumed = false
    private var pickFor: String? = null
    private var pickAt = 0L
    internal var lastStyle = SchemeStyles.AUTO
    @Volatile private var cancelExport = false
    private var staleOk = false
    /** Animation blocks: renders, the answer of another AI from the clipboard (see [EditAnims]). */
    internal val anims by lazy { EditAnims(this) }

    internal var stage: EditStage? = null
    private var timeline: EditTimeline? = null
    internal var texture: TextureView? = null
    // split screen preview: the main video and a second player each in its half of the frame
    private var frameView: FrameLayout? = null
    private var mainBox: FrameLayout? = null
    private var sideBox: FrameLayout? = null
    private var texture2: TextureView? = null
    private var player2: ExoPlayer? = null
    private var side2File = ""
    private var splitKey = ""
    private var side2SeekAt = 0L
    private val sideSizes = HashMap<String, Pair<Int, Int>>()
    // music on the preview
    private var player3: ExoPlayer? = null
    private var musicFile = ""
    private var musicSeekAt = 0L
    /** Videos in the player's playlist, in order ("" = the base video). */
    private var srcList: List<String> = listOf("")
    private val thumbMap = HashMap<String, List<Pair<Long, Bitmap>>>()
    private var fadeView: View? = null
    private var blurView: android.widget.ImageView? = null
    private var stillView: android.widget.ImageView? = null
    private var stillOf = -1
    internal var previewBox: FrameLayout? = null
    private var topBarView: View? = null
    private var ratioChip: TextView? = null
    private var fullBtn: TextView? = null
    private var toolsLine: TextView? = null
    /** Views hidden in full screen. */
    private var chrome: List<View> = emptyList()
    private var fullscreen = false
    /** Open section of the bottom bar (audio, text, overlay, ai), null = the main sections. */
    private var cat: String? = null
    private var savedBars: Int? = null
    private var thumbList: List<Pair<Long, Bitmap>> = emptyList()
    // colours of the editor: the app's light or dark theme (Settings), set by [editorColors]
    private var ED_BG = 0
    private var ED_PANEL = 0
    private var ED_BAR = 0
    private var ED_CARD = 0
    private var ED_CARD_HI = 0
    private var ED_TEXT = 0
    private var ED_MUTED = 0
    private var ED_ON_ACCENT = 0
    private var edDark = true
    /** Accent of the editor: readable on its background. */
    private val edAccent: Int get() = if (Palette.contrast(Palette.ACCENT or 0xFF000000.toInt(), ED_BG or 0xFF000000.toInt()) >= 3.0) Palette.ACCENT
        else if (edDark) Palette.FAB else Palette.ON_FAB

    /** The editor follows the theme chosen in the settings: video-editor dark, or the light Material surface. */
    private fun editorColors() {
        edDark = isNight()
        if (edDark) {
            ED_BG = 0xFF0E0F12.toInt()
            ED_PANEL = 0xFF15171B.toInt()
            ED_BAR = 0xFF121418.toInt()
            ED_CARD = 0xFF23262C.toInt()
            ED_CARD_HI = 0xFF33373F.toInt()
            ED_TEXT = 0xFFF2F3F5.toInt()
            ED_MUTED = 0xFFA4A8B0.toInt()
        } else {
            ED_BG = Palette.SURFACE or 0xFF000000.toInt()
            ED_PANEL = 0xFFE9EAEE.toInt()
            ED_BAR = Palette.SURFACE or 0xFF000000.toInt()
            ED_CARD = Palette.CARD or 0xFF000000.toInt()
            ED_CARD_HI = Palette.ACCENT_SOFT or 0xFF000000.toInt()
            ED_TEXT = Palette.ON_SURFACE
            ED_MUTED = Palette.MUTED
        }
        ED_ON_ACCENT = if (Palette.contrast(0xFFFFFFFF.toInt(), edAccent) >= Palette.contrast(0xFF14161B.toInt(), edAccent)) 0xFFFFFFFF.toInt() else 0xFF14161B.toInt()
    }
    private var timeText: TextView? = null
    private var playBtn: TextView? = null
    private var undoBtn: TextView? = null
    private var redoBtn: TextView? = null
    private var toolsRow: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var statusText: TextView? = null
    private var statusBar: ProgressBar? = null
    private var statusStop: TextView? = null

    internal val blocks: List<EBlock> get() = project?.blocks.orEmpty()
    internal val dir: File get() = StudioEditStore.dir(applicationContext, project?.id ?: "none")
    internal val styleDir: File get() = StudioEditStore.styleDir(applicationContext)
    private val clips: List<VClip> get() = project?.let { EditTrack.of(it) } ?: emptyList()
    private val total: Long get() = project?.let { outLen(it) } ?: 1L
    internal fun outLen(p: EProject): Long = EditTrack.total(EditTrack.of(p))
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
        pause()
        player2?.pause()
        player3?.pause()
        save()
    }

    override fun onDestroy() {
        stopPlayer()
        anims.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets an app read the clipboard only while it has focus
        if (hasFocus && project != null) anims.onFocus()
    }

    /** A sample picture for the insert wizard's brief. */
    internal fun pickRefImage() {
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_REF) }
            .onFailure { toast(t("Нет приложения для выбора картинки", "No app to pick a picture")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false)
        else if (selected != null) select(null) else if (clipSel >= 0) selectClip(-1) else if (cat != null) openCat(null) else {
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
        if (onDelete != null) row.addView(iconBtn(this, "🗑") { onDelete() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        c.add(row, 8)
        loadThumb(iv, f, under, sub)
    }

    /**
     * The Editor section (a tab of the Studio): how it works, the latest finished videos of the Studio and the Mix,
     * a video from the phone and the saved projects.
     */
    private fun chooser() {
        stopPlayer()
        darkBars(false)
        project = null
        stage = null
        timeline = null
        statusRow = null
        toolsRow = null
        fullscreen = false
        cat = null
        val c = Col(this)
        // a big "new project" tile on top, like the home screen of CapCut / YouCut
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(22), dp(16), dp(20))
            background = ripple(this@StudioEditorActivity, roundBg(Palette.FAB, 24f, this@StudioEditorActivity), Palette.ON_FAB)
            contentDescription = t("Новый проект: видео с телефона", "New project: a video from the phone")
            setOnClickListener { pickVideo() }
        }
        hero.addView(text(this, "＋", 34f, bold = true, color = Palette.ON_FAB).apply { gravity = Gravity.CENTER })
        hero.addView(text(this, t("Новый проект", "New project"), 18f, bold = true, color = Palette.ON_FAB).apply { gravity = Gravity.CENTER })
        hero.addView(text(this, t("Видео с телефона → монтаж, музыка, надписи, фильтры, переходы, схемы ИИ",
            "A video from the phone → cuts, music, captions, filters, transitions, AI schemes"), 13f, color = Palette.ON_FAB).apply {
            gravity = Gravity.CENTER
            alpha = 0.85f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        c.add(hero, 4)
        c.row(btn(this, t("❓ Как пользоваться", "❓ How to use"), style = BtnStyle.TEXT) { HelpActivity.open(this, helpTopic) })
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
            n["text"]?.let { t("надписей: $it", "captions: $it") }, n["emoji"]?.let { t("эмодзи: $it", "emoji: $it") },
            n[EditMusic.KIND]?.let { t("звуков: $it", "sounds: $it") }
        ).joinToString(" · ") + if (p.exported.isNotEmpty()) t(" · экспортирован", " · exported") else ""
    }

    private fun start(p: EProject) {
        stopPlayer()
        project = p
        intent = Intent(intent).putExtra(EXTRA_PROJECT, p.id)
        srcWords = StudioEditStore.words(this, p.id)
        words = EditTrack.words(srcWords, EditTrack.of(p), p.durMs)
        history.clear()
        gestureBase = null
        selected = null
        clipSel = -1
        playing = false
        curClip = -1
        curMs = 0
        cat = null
        fullscreen = false
        thumbList = emptyList()
        thumbMap.clear()
        srcList = listOf("")
        buildEditor()
        loadThumbs(p)
    }

    // ---------- layout ----------

    private fun buildEditor() {
        val p = project ?: return
        editorColors()
        darkBars(edDark)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ED_BG)
        }
        root.addView(topBar())
        val box = FrameLayout(this).apply { setBackgroundColor(ED_BG) }
        val frame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val tv = TextureView(this)
        val st = EditStage(this) { w, h -> StudioEditPainter(w, h, dir, words, StudioPrefs.accent, styleDir) }
        val mb = FrameLayout(this)
        mb.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // dissolves on the preview: the last picture of the piece before, fading out over the video
        val still = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        mb.addView(still, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stillView = still
        val sb = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }
        val tv2 = TextureView(this)
        sb.addView(tv2, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // transitions: a colour over the whole frame at the seams
        val fv = View(this).apply {
            visibility = View.GONE
            isClickable = false
        }
        // the blurred background of a fitted video (format "blur"), behind it
        val bv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            colorFilter = android.graphics.PorterDuffColorFilter(0x40000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        frame.addView(bv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        blurView = bv
        frame.addView(mb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(sb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(st, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(fv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addOnLayoutChangeListener { _, x0, y0, x1, y1, ox0, oy0, ox1, oy1 ->
            if (x1 - x0 != ox1 - ox0 || y1 - y0 != oy1 - oy0) frame.post {
                splitKey = ""
                layoutSplit()
            }
        }
        frameView = frame
        mainBox = mb
        sideBox = sb
        texture2 = tv2
        fadeView = fv
        previewBox = box
        box.addView(frame, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> box.post { fitFrame(box, frame) } }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status())
        root.addView(transport())
        val tl = EditTimeline(this).apply {
            totalMs = outLen(p)
            clips = p.clips
            aspect = p.w.toFloat() / p.h
            blocks = p.blocks
            words = this@StudioEditorActivity.words
            light(!edDark)
        }
        val tlBox = FrameLayout(this).apply {
            setBackgroundColor(ED_PANEL)
            setPadding(0, dp(6), 0, dp(4))
            addView(tl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(tlBox)
        // the bottom bar: a line on what is being edited, then the tools (two levels, like CapCut)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ED_BAR)
        }
        val ctxLine = text(this, "", 12f, bold = true, color = ED_MUTED).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(8), dp(16), 0)
            visibility = View.GONE
        }
        bottom.addView(ctxLine)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        bottom.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        root.addView(bottom)
        chrome = listOfNotNull(tlBox, bottom, topBarView)
        stage = st
        timeline = tl
        timelineA11y(tl)
        texture = tv
        toolsRow = row
        toolsLine = ctxLine
        st.blocks = EditSplit.overlays(p.blocks)
        // animations whose frames are missing (a render stopped, a new size) are rendered while the editor is open
        if (p.blocks.any { it.kind == EditAnim.KIND }) anims.checkLater()
        st.portrait = EditCanvas.frame(p).let { (w, h) -> h > w * 1.1f }
        st.onSelect = { id ->
            pause()
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
            if (playing) pause()
            seekTo(ms, !dragging, fromTimeline = true)
        }
        tl.onSelect = { id ->
            pause()
            select(id, seek = true)
        }
        tl.onClip = { i ->
            pause()
            selectClip(if (i == clipSel) -1 else i)
        }
        tl.onSeam = { k ->
            pause()
            transitionSheet(k)
        }
        tl.onClipDrop = { from, to ->
            pause()
            EditTrack.moveTo(clips, from, to)?.let { (list, j) -> commitClips(list, j) }
        }
        tl.onChange = { b, done -> live(b, done) }
        editorScreen(root)
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

    /** The editor fills the screen itself (its own top bar), dark like video editors, padded by the system bars. */
    private fun editorScreen(root: View) {
        val frame = FrameLayout(this).apply { setBackgroundColor(ED_BG) }
        frame.addView(root)
        frame.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(frame)
        frame.requestApplyInsets()
    }

    /** Light icons of the status and navigation bars over the dark editor; the app's own look back on the list. */
    private fun darkBars(on: Boolean) {
        val v = window.decorView
        @Suppress("DEPRECATION")
        if (on) {
            if (savedBars == null) savedBars = v.systemUiVisibility
            var f = v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) f = f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            v.systemUiVisibility = f
        } else savedBars?.let {
            v.systemUiVisibility = it
            savedBars = null
        }
    }

    /** ✕ to the projects, the format of the frame, help and the export, like the top of CapCut. */
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(10), dp(4))
            setBackgroundColor(ED_BG)
        }
        bar.addView(roundBtn("✕") { closeEditor() }.apply { contentDescription = t("Закрыть: к проектам", "Close: to the projects") })
        val ratio = text(this, "", 13f, bold = true, color = ED_TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = ripple(this@StudioEditorActivity, roundBg(ED_CARD, 16f, this@StudioEditorActivity), ED_TEXT)
            contentDescription = t("Формат кадра", "Frame format")
            setOnClickListener { canvasSheet() }
        }
        bar.addView(ratio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(roundBtn("?") { HelpActivity.open(this, helpTopic) }.apply { contentDescription = t("Справка", "Help") })
        bar.addView(text(this, t("Экспорт", "Export"), 14f, bold = true, color = ED_ON_ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = ripple(this@StudioEditorActivity, roundBg(edAccent, 20f, this@StudioEditorActivity), ED_ON_ACCENT)
            contentDescription = t("Экспорт ролика", "Export the video")
            setOnClickListener { exportSheet() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        ratioChip = ratio
        topBarView = bar
        refreshRatio()
        return bar
    }

    private fun refreshRatio() {
        val p = project ?: return
        ratioChip?.text = "▭ " + (p.ratio.ifEmpty { t("Оригинал", "Original") }) + " ▾"
    }

    private fun closeEditor() {
        if (fullscreen) return setFullscreen(false)
        pause()
        save()
        chooser()
    }

    /** Only the picture and the play bar: to watch the video as it will look. */
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        for (v in chrome) v.visibility = if (on) View.GONE else View.VISIBLE
        fullBtn?.text = if (on) "⤡" else "⛶"
    }

    /** The first time: how the editor works, in four lines. */
    private fun hintOnce() {
        val prefs = Lumen.prefs
        if (prefs.getBoolean("studio_edit_hint2", false)) return
        prefs.edit().putBoolean("studio_edit_hint2", true).apply()
        bottomSheet(t("Как работает редактор", "How the editor works")) { dismiss ->
            p(t("1. Тап по кадру — пауза. Таймлайн прокручивается пальцем, щипок — масштаб; белая линия — курсор.",
                "1. Tap the frame to pause. Scroll the timeline with a finger, pinch to zoom; the white line is the playhead."))
            p(t("2. Внизу разделы: ✂ Монтаж, 🎵 Аудио, T Текст, 😀 Стикеры, 🖼 Наложение, 🎨 Фильтры, ☀ Настройка, ⇆ Переходы, ▭ Формат, 🧩 ИИ. «‹ Назад» — к разделам.",
                "2. Sections below: ✂ Edit, 🎵 Audio, T Text, 😀 Stickers, 🖼 Overlay, 🎨 Filters, ☀ Adjust, ⇆ Transitions, ▭ Format, 🧩 AI. \"‹ Back\" returns."))
            p(t("3. Тап по кадрам ролика на таймлайне выделяет кусок: разрезать, скорость, громкость, фильтр, переход, удалить; удержание и перетаскивание — переставить. Белый квадрат на шве — переход.",
                "3. Tap the frames on the timeline to select a piece: split, speed, volume, filter, transition, delete; hold and drag to reorder. The white square at a seam is a transition."))
            p(t("4. Надписи, картинки, схемы и музыка — цветные полоски под кадрами: тяните целиком или за края. На кадре блок двигается пальцем, два пальца — размер и поворот.",
                "4. Captions, pictures, schemes and music are coloured bars under the frames: drag them or their edges. On the frame drag a block; two fingers scale and turn it."))
            if (words.isNotEmpty()) p(t("5. «T Текст → 📝 Текст речи» — вся речь: тап по слову ставит курсор, выделите фразу — и схема или надпись встанет ровно на неё.",
                "5. \"T Text → 📝 Speech text\" is the whole speech: tap a word to go there, select a phrase to put a scheme or caption exactly on it."))
            p(t("↶ ↷ — отмена любого шага, ⛶ — смотреть во весь экран, «Экспорт» — готовый ролик в галерею.",
                "↶ ↷ undo any step, ⛶ watch full screen, \"Export\" saves the video to the gallery."))
            button(t("Понятно", "Got it"), primary = true) { dismiss() }
        }
    }

    private fun refreshUndo() {
        undoBtn?.alpha = if (history.canUndo) 1f else 0.35f
        redoBtn?.alpha = if (history.canRedo) 1f else 0.35f
    }

    /** The frame keeps the shape of the canvas (the format of the project) inside the dark area. */
    private fun fitFrame(box: View, frame: View) {
        val p = project ?: return
        if (box.width <= 0 || box.height <= 0) return
        val (cw, ch) = EditCanvas.frame(p)
        val pad = dp(8)
        val k = min((box.width - pad * 2).toFloat() / cw, (box.height - pad * 2).toFloat() / ch).coerceAtLeast(0.01f)
        val w = max(1, (cw * k).toInt())
        val h = max(1, (ch * k).toInt())
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) frame.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun roundBtn(glyph: String, big: Boolean = false, onClick: () -> Unit): TextView = text(this, glyph, if (big) 22f else 18f, bold = true,
        color = if (big) ED_BG else ED_TEXT).apply {
        gravity = Gravity.CENTER
        val s = dp(if (big) 52 else 44)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(2) }
        background = ripple(this@StudioEditorActivity, roundBg(if (big) ED_TEXT else 0x00000000, if (big) 26f else 22f, this@StudioEditorActivity), if (big) ED_BG else ED_TEXT)
        setOnClickListener { onClick() }
    }

    /** Time on the left, ▶ in the middle, undo / redo and full screen on the right. */
    private fun transport(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(6), dp(2))
            setBackgroundColor(ED_BG)
        }
        val tt = text(this, "", 12f, bold = true, color = ED_TEXT).apply {
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        tt.setOnClickListener { goToTime() }
        tt.contentDescription = t("Время; тап — перейти к моменту", "Time; tap to go to a moment")
        row.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = roundBtn("▶", big = true) { toggle() }.apply { contentDescription = t("Играть / пауза", "Play / pause") }
        row.addView(play)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        val un = roundBtn("↶") { undo() }.apply { contentDescription = t("Отменить", "Undo") }
        val re = roundBtn("↷") { redo() }.apply { contentDescription = t("Вернуть", "Redo") }
        val full = roundBtn("⛶") { setFullscreen(!fullscreen) }.apply { contentDescription = t("Во весь экран", "Full screen") }
        right.addView(un)
        right.addView(re)
        right.addView(full)
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBtn = un
        redoBtn = re
        fullBtn = full
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
        val tv = text(this, "", 13f, color = ED_TEXT).apply { isSingleLine = true }
        line.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val stop = text(this, t("Стоп", "Stop"), 13f, bold = true, color = edAccent).apply { setPadding(dp(12), dp(4), dp(4), dp(4)) }
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
        val task = ex ?: BgTasks.find(TASK_AI) ?: BgTasks.find(TASK_REV)
        if (task == null) {
            // speech recognition runs on the main thread's callbacks, not as a background task
            if (recogMsg != null) {
                row.visibility = View.VISIBLE
                statusText?.text = "💬 " + recogMsg
                statusBar?.showProgress(recogMsg.orEmpty())
                statusStop?.setOnClickListener { recogStop = true }
                return
            }
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

    /** A button of the bottom bar like in CapCut: an icon in a round tile over a short name; [on] marks a setting that is on. */
    private fun tool(row: LinearLayout, a: Act) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = ripple(this@StudioEditorActivity, roundBg(0x00000000, 14f, this@StudioEditorActivity), ED_TEXT)
            contentDescription = a.label
            setOnClickListener { a.f() }
        }
        val tile = when {
            a.kind == ActKind.PRIMARY -> edAccent
            a.kind == ActKind.BACK || a.on -> ED_CARD_HI
            else -> ED_CARD
        }
        v.addView(text(this, a.icon, 19f, bold = true, color = when {
            a.kind == ActKind.PRIMARY -> ED_ON_ACCENT
            a.kind == ActKind.DANGER -> if (edDark) 0xFFFF8A80.toInt() else Palette.BAD
            a.on -> edAccent
            else -> ED_TEXT
        }).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            background = roundBg(tile, 14f, this@StudioEditorActivity, stroke = if (a.on) edAccent else 0)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        v.addView(text(this, a.label, 11f, color = if (a.on) edAccent else ED_MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        row.addView(v, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private enum class ActKind { NORMAL, PRIMARY, BACK, DANGER }

    private class Act(val icon: String, val label: String, val kind: ActKind = ActKind.NORMAL, val on: Boolean = false, val f: () -> Unit)

    private fun act(icon: String, label: String, on: Boolean = false, f: () -> Unit) = Act(icon, label, ActKind.NORMAL, on, f)
    private fun done(f: () -> Unit) = Act("✓", t("Готово", "Done"), ActKind.PRIMARY, f = f)
    private fun back() = Act("‹", t("Назад", "Back"), ActKind.BACK) { openCat(null) }
    private fun danger(icon: String, label: String, f: () -> Unit) = Act(icon, label, ActKind.DANGER, f = f)

    private fun openCat(c: String?) {
        cat = c
        tools()
    }

    private fun catName(c: String) = when (c) {
        "audio" -> t("🎵 Аудио", "🎵 Audio")
        "text" -> t("T Текст", "T Text")
        "overlay" -> t("🖼 Наложение", "🖼 Overlay")
        "ai" -> t("🧩 ИИ-помощник", "🧩 AI helper")
        else -> c
    }

    /** The piece of the main track under the playhead. */
    private fun clipHere(): Int = EditTrack.locate(clips, curMs).index

    /** The seam nearest to the playhead (1..size-1), or 0 (the start of the video) when there is one piece. */
    private fun seamHere(): Int {
        val list = clips
        if (list.size < 2) return 0
        val st = EditTrack.starts(list)
        return (1 until list.size).minByOrNull { kotlin.math.abs(st[it] - curMs) } ?: 1
    }

    /**
     * The bottom bar: main sections (Edit, Audio, Text, Stickers, Overlay, Filters, Adjust, Transitions, Format, AI,
     * Layers), a section's tools with ‹ back, the tools of the selected piece of the video or of the selected block.
     */
    private fun tools() {
        val row = toolsRow ?: return
        row.removeAllViews()
        val b = StudioEdit.find(blocks, selected)
        val ci = clipSel.takeIf { b == null && it in clips.indices }
        var line = ""
        val items: List<Act> = when {
            b != null && b.kind == EditSplit.KIND -> {
                line = "⊟ " + t("Разделённый экран", "Split screen") + " · " + StudioEdit.secs(b.durMs) + t(" с", " s")
                listOf(
                    done { select(null) },
                    act("⊟", t("Раскладка", "Layout")) { splitSheet(b) },
                    act("⏮", t("Начало 2-го видео", "2nd video from")) { splitFrom(b) },
                    act(if (b.volume > 0f) "🔊" else "🔇", t("Звук", "Sound")) { splitSoundSheet(b) },
                    act("⏱", t("Время", "Timing")) { timingSheet(b) },
                    act("✂", t("Разделить", "Split")) { split(b) },
                    act("⧉", t("Дубль", "Duplicate")) { duplicate(b.id) },
                    act("⇤", t("Начало сюда", "Start here")) { change(b.id) { StudioEdit.startAt(it, curMs, total) } },
                    act("⇥", t("Конец сюда", "End here")) { change(b.id) { StudioEdit.endAt(it, curMs, total) } },
                    act("🔁", t("Заменить видео", "Replace video")) { pickSplit(b.id) },
                    danger("🗑", t("Удалить", "Delete")) { delete(b.id) }
                )
            }
            b != null && b.pending -> {
                line = blockName(b)
                listOfNotNull(
                    done { select(null) },
                    if (b.req.isNotEmpty()) act("📥", t("Ответ ИИ", "AI answer")) { InsertWizard.open(this, b) } else null,
                    act("⏱", t("Время", "Timing")) { timingSheet(b) },
                    act("📐", t("Положение", "Position")) { positionSheet(b.id) },
                    danger("🗑", t("Удалить", "Delete")) { delete(b.id) }
                )
            }
            b != null && b.kind == EditAnim.KIND -> {
                line = blockName(b) + " · " + StudioEdit.secs(b.durMs) + t(" с", " s")
                listOf(
                    done { select(null) },
                    act("✨", t("Помощник", "Wizard")) { InsertWizard.open(this, b) },
                    act("◐", t("Прозрач­ность", "Opacity")) { opacitySheet(b) },
                    act("◑", t("Затемнение", "Darken"), on = b.dim > 0f) { dimSheet(b) },
                    act("⏱", t("Время", "Timing")) { timingSheet(b) },
                    act("🎞", t("Анимация", "Animation")) { animSheet(b) },
                    act("⧉", t("Дубль", "Duplicate")) { duplicate(b.id) },
                    act("⇤", t("Начало сюда", "Start here")) { change(b.id) { StudioEdit.startAt(it, curMs, total) } },
                    act("⇥", t("Конец сюда", "End here")) { change(b.id) { StudioEdit.endAt(it, curMs, total) } },
                    act("◆", t("Ключ", "Keyframe"), on = b.keys.isNotEmpty()) { toggleKey(b) },
                    act("⬆", t("Выше", "Forward")) { commit(StudioEdit.reorder(blocks, b.id, 1)) },
                    act("⬇", t("Ниже", "Backward")) { commit(StudioEdit.reorder(blocks, b.id, -1)) },
                    act("📐", t("Положение", "Position")) { positionSheet(b.id) },
                    act("⟳", t("Перерисовать", "Re-render")) { anims.enqueue(b.id) },
                    danger("🗑", t("Удалить", "Delete")) { delete(b.id) }
                )
            }
            b != null && b.kind == EditMusic.KIND -> {
                line = blockName(b) + " · " + StudioEdit.secs(b.durMs) + t(" с", " s")
                listOf(
                    done { select(null) },
                    act(if (b.volume > 0f) "🔊" else "🔇", t("Громкость", "Volume")) { musicSheet(b) },
                    act("〰", t("Затухание", "Fade"), on = b.animIn == "fade" || b.animOut == "fade") { musicFadeSheet(b) },
                    act("⏮", t("Начало трека", "Track from")) { musicFrom(b) },
                    act("⏱", t("Время", "Timing")) { timingSheet(b) },
                    act("✂", t("Разделить", "Split")) { split(b) },
                    act("⧉", t("Дубль", "Duplicate")) { duplicate(b.id) },
                    act("⇤", t("Начало сюда", "Start here")) { change(b.id) { StudioEdit.startAt(it, curMs, total) } },
                    act("⇥", t("Конец сюда", "End here")) { change(b.id) { StudioEdit.endAt(it, curMs, total) } },
                    act("🔁", t("Заменить", "Replace")) { pickAudio(b.id) },
                    danger("🗑", t("Удалить", "Delete")) { delete(b.id) }
                )
            }
            b != null -> {
                line = blockName(b) + " · " + StudioEdit.secs(b.durMs) + t(" с", " s")
                buildList {
                    add(done { select(null) })
                    add(act("✎", t("Изменить", "Edit")) { edit(b) })
                    if (b.kind != "emoji") add(act("🎨", t("Вид", "Look")) { lookSheet(b) })
                    add(act("🎞", t("Анимация", "Animation")) { animSheet(b) })
                    add(act("⏱", t("Время", "Timing")) { timingSheet(b) })
                    add(act("✂", t("Разделить", "Split")) { split(b) })
                    add(act("⧉", t("Дубль", "Duplicate")) { duplicate(b.id) })
                    add(act("⇤", t("Начало сюда", "Start here")) { change(b.id) { StudioEdit.startAt(it, curMs, total) } })
                    add(act("⇥", t("Конец сюда", "End here")) { change(b.id) { StudioEdit.endAt(it, curMs, total) } })
                    add(act("◆", t("Ключ", "Keyframe"), on = b.keys.isNotEmpty()) { toggleKey(b) })
                    add(act("◐", t("Прозрач­ность", "Opacity")) { opacitySheet(b) })
                    add(act("⬆", t("Выше", "Forward")) { commit(StudioEdit.reorder(blocks, b.id, 1)) })
                    add(act("⬇", t("Ниже", "Backward")) { commit(StudioEdit.reorder(blocks, b.id, -1)) })
                    add(act("⊙", t("В центр", "Centre")) { change(b.id) { it.copy(x = 0.5f, y = 0.5f, rot = 0f) } })
                    add(act("📐", t("Положение", "Position")) { positionSheet(b.id) })
                    if (b.kind == "scheme") add(act("✨", t("Переделать ИИ", "Redo with AI")) { InsertWizard.open(this@StudioEditorActivity, b) })
                    if (b.kind == "scheme") add(act("◑", t("Затемнение", "Darken"), on = b.dim > 0f) { dimSheet(b) })
                    if (EditCaptions.isSub(b)) add(act("💬", t("Все субтитры", "All subtitles")) { subsSheet() })
                    add(danger("🗑", t("Удалить", "Delete")) { delete(b.id) })
                }
            }
            ci != null -> {
                val c = clips[ci]
                line = t("Кусок ${ci + 1} из ${clips.size}", "Piece ${ci + 1} of ${clips.size}") + " · " + StudioEdit.secs(c.outMs) + t(" с", " s") +
                    (EditTrack.badges(c).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                buildList {
                    add(done { selectClip(-1) })
                    add(act("✂", t("Разделить", "Split")) { clipSplit() })
                    if (c.isHold) add(act("⏱", t("Длина", "Length")) { holdSheet(ci) })
                    else {
                        add(act("⏩", t("Скорость", "Speed"), on = c.speed != 1f) { speedSheet(ci) })
                        add(act(if (c.volume > 0f) "🔊" else "🔇", t("Громкость", "Volume"), on = c.volume != 1f) { volumeSheet(ci) })
                    }
                    add(act("🎨", t("Фильтры", "Filters"), on = c.filter.isNotEmpty()) { filterSheet(ci) })
                    add(act("☀", t("Настройка", "Adjust"), on = c.bright != 0f || c.contrast != 0f || c.sat != 0f || c.warm != 0f) { adjustSheet(ci) })
                    add(act("⇆", t("Переход", "Transition"), on = c.trans.isNotEmpty()) { transitionSheet(ci) })
                    add(danger("🗑", t("Удалить", "Delete")) { clipDelete(ci) })
                    add(act("⧉", t("Дубль", "Duplicate")) { clipDuplicate(ci) })
                    if (!c.isHold) add(act("❄", t("Стоп-кадр", "Freeze")) { clipFreeze() })
                    if (!c.isHold) add(act("⏪", t("Реверс", "Reverse")) { reverseClip(ci) })
                    add(act("⟳", t("Повернуть", "Rotate"), on = c.rot != 0) { clipUpdate(ci) { it.copy(rot = (it.rot + 90) % 360) } })
                    add(act("⇋", t("Зеркало", "Mirror"), on = c.mirror) { clipUpdate(ci) { it.copy(mirror = !it.mirror) } })
                    add(act("🔍", t("Масштаб", "Zoom"), on = c.zoom > 1f) { zoomSheet(ci) })
                    add(act("◀", t("Раньше", "Earlier")) { clipMove(ci, -1) })
                    add(act("▶", t("Позже", "Later")) { clipMove(ci, 1) })
                    add(act("⇤", t("Обрезать начало", "Trim start")) { clipTrim(ci, true) })
                    add(act("⇥", t("Обрезать конец", "Trim end")) { clipTrim(ci, false) })
                    if (!c.looksPlain || c.isHold) add(act("↺", t("Сбросить", "Reset")) {
                        clipUpdate(ci) { VClip(it.id, it.fromMs, it.toMs, volume = if (it.isHold) 0f else 1f, holdMs = it.holdMs, trans = it.trans, transMs = it.transMs, src = it.src) }
                    })
                }
            }
            cat == "audio" -> {
                line = catName("audio")
                val muted = clips.all { it.isHold || it.volume == 0f }
                listOf(
                    back(),
                    act("🎵", t("Музыка", "Music")) { pickAudio(null) },
                    act("🎙", t("Озвучка", "Voice-over")) { voiceSheet() },
                    act("🔊", t("Громкость видео", "Video volume")) { allVolumeSheet() },
                    act(if (muted) "🔈" else "🔇", if (muted) t("Вернуть звук", "Unmute video") else t("Без звука", "Mute video"), on = muted) {
                        commitClips(clips.map { if (it.isHold) it else it.copy(volume = if (muted) 1f else 0f) })
                    },
                    act("☰", t("Звуки", "Sounds")) { blocksSheet(EditMusic.KIND) }
                )
            }
            cat == "text" -> {
                line = catName("text")
                listOfNotNull(
                    back(),
                    act("T", t("Надпись", "Caption")) { captionSheet(null) },
                    act("💬", t("Автосубтитры", "Auto captions")) { subsSheet() },
                    act("✍️", t("Схема текстом", "Text scheme")) { textSchemeSheet(null) },
                    if (words.isNotEmpty()) act("📝", t("Текст речи", "Speech text")) { textSheet() } else null
                )
            }
            cat == "overlay" -> {
                line = catName("overlay")
                listOf(
                    back(),
                    act("🖼", t("Картинка", "Picture")) { pickImage(null) },
                    act("🌐", t("Фото из сети", "Web photo")) { photoSheet(null) },
                    act("⊟", t("Разделить экран", "Split screen")) { pickSplit(null) }
                )
            }
            cat == "ai" -> {
                line = catName("ai")
                listOfNotNull(
                    back(),
                    act("✨", t("Помощник", "Wizard")) { InsertWizard.open(this) },
                    if (words.isNotEmpty()) act("🤖", t("Схемы на всё", "Schemes for all")) { autoSchemes() } else null,
                    act("T", t("Надпись ИИ", "AI caption")) { captionSheet(null) },
                    act("🌐", t("Фото ИИ", "AI photo")) { photoSheet(null) }
                )
            }
            else -> listOf(
                Act("＋", t("Вставить", "Insert"), ActKind.PRIMARY) { insertSheet() },
                act("✂", t("Монтаж", "Edit")) { selectClip(clipHere()) },
                act("🎞", t("Добавить видео", "Add video")) { addClipSheet() },
                act("🎵", t("Аудио", "Audio")) { openCat("audio") },
                act("T", t("Текст", "Text")) { openCat("text") },
                act("😀", t("Стикеры", "Stickers")) { emojiSheet(null) },
                act("🖼", t("Наложение", "Overlay")) { openCat("overlay") },
                act("🎨", t("Фильтры", "Filters")) { filterSheet(clipHere()) },
                act("☀", t("Настройка", "Adjust")) { adjustSheet(clipHere()) },
                act("⇆", t("Переходы", "Transitions")) { transitionSheet(seamHere()) },
                act("▭", t("Формат", "Format"), on = project?.ratio?.isNotEmpty() == true) { canvasSheet() },
                act("🧩", t("ИИ", "AI")) { openCat("ai") },
                act("☰", t("Слои", "Layers")) { blocksSheet() }
            )
        }
        for (a in items) tool(row, a)
        toolsLine?.let {
            it.text = line
            it.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        }
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

                override fun onPlayerError(error: PlaybackException) {
                    toast(t("Видео не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName))
                }
            })
            // every video of the project is one item of the playlist; a piece plays inside its own item
            ex.pauseAtEndOfMediaItems = true
            loadSources(ex)
            curClip = -1
            place(true)
        }
        ex.setVideoTextureView(tv)
    }

    /** The videos of the track in the player (the base one first). */
    private fun loadSources(ex: ExoPlayer) {
        srcList = EditTrack.sources(clips)
        ex.setMediaItems(srcList.map { MediaItem.fromUri(Uri.fromFile(srcFile(it))) })
        ex.prepare()
    }

    /** A video of the project: "" — the base one. */
    private fun srcFile(src: String): File = if (src.isEmpty()) File(dir, StudioEditStore.BASE) else File(dir, src)

    private fun srcIndex(src: String): Int = srcList.indexOf(src).coerceAtLeast(0)

    /** Display size of a video of the project. */
    private fun srcSize(src: String): Pair<Int, Int> = project?.takeIf { src.isEmpty() }?.let { it.w to it.h } ?: sideSize(src)

    /** After a change of the track: a new video in it goes into the player. */
    private fun refreshSources() {
        val ex = player ?: return
        if (EditTrack.sources(clips) == srcList) return
        val was = playing
        pause()
        loadSources(ex)
        curClip = -1
        place(true)
        if (was) play()
    }

    /** Speed, sound and look of piece [i] on the player and the picture. */
    private fun applyClip(i: Int) {
        val c = clips.getOrNull(i) ?: return
        curClip = i
        player?.let { ex ->
            if (ex.playbackParameters.speed != c.speed) ex.setPlaybackParameters(PlaybackParameters(c.speed))
            ex.volume = c.volume.coerceIn(0f, 1f)
        }
        texture?.let { tv ->
            tv.scaleX = if (c.mirror) -c.zoom else c.zoom
            tv.scaleY = c.zoom
            tv.rotation = c.rot.toFloat()
        }
        previewColor(c)
        // a piece of another video may have another shape
        layoutSplit()
    }

    /** Puts the player on the moment of the original under [curMs]; while the finger moves ([final] false) at most every 40 ms. */
    private fun place(final: Boolean) {
        val ex = player ?: return
        val list = clips
        if (list.isEmpty()) return
        val pos = EditTrack.locate(list, curMs)
        if (pos.index != curClip) applyClip(pos.index)
        val c = list[pos.index]
        val now = SystemClock.uptimeMillis()
        if (final || now - lastSeekAt > 40) {
            lastSeekAt = now
            // while the finger moves: the nearest key frame (fast); at the end: the exact frame
            ex.setSeekParameters(if (final) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            ex.seekTo(srcIndex(c.src), pos.srcMs)
        }
        if (c.isHold) {
            holdT0 = now - pos.offMs
            if (ex.playWhenReady) ex.pause()
        } else if (playing && !ex.playWhenReady) ex.play()
    }

    /** While playing: the output time from the player (or the clock of a freeze frame), the next piece at the end of this one. */
    private fun follow(ex: ExoPlayer) {
        val list = clips
        val c = list.getOrNull(curClip) ?: return place(true)
        val start = EditTrack.starts(list)[curClip]
        val done = if (c.isHold) {
            val off = SystemClock.uptimeMillis() - holdT0
            curMs = start + off.coerceIn(0, c.outMs)
            off >= c.outMs
        } else {
            val pos = ex.currentPosition
            curMs = start + ((pos - c.fromMs).coerceAtLeast(0) / c.speed.toDouble()).toLong().coerceAtMost(c.outMs)
            // the end of the piece, of its video, or the player stopped at the end of its playlist item
            pos >= c.toMs - 20 || ex.playbackState == Player.STATE_ENDED || ex.currentMediaItemIndex != srcIndex(c.src) || !ex.playWhenReady
        }
        if (done) nextClip(ex)
        sync()
    }

    private fun nextClip(ex: ExoPlayer) {
        val list = clips
        val i = curClip + 1
        if (i >= list.size) {
            playing = false
            ex.pause()
            curMs = total
            updatePlay()
            return
        }
        val prev = list[curClip]
        val n = list[i]
        // a dissolve: the last picture of this piece is kept on top and fades out over the next one
        if (n.trans == "dissolve") runCatching {
            val tv = texture ?: return@runCatching
            stillView?.setImageBitmap(tv.getBitmap(max(1, tv.width / 2), max(1, tv.height / 2)))
            stillOf = curClip
        }
        curMs = EditTrack.starts(list)[i]
        applyClip(i)
        // the next piece right after this one in the original plays on without a seek
        val seamless = !prev.isHold && !n.isHold && prev.toMs == n.fromMs && prev.src == n.src && ex.playWhenReady
        if (!seamless) {
            ex.setSeekParameters(SeekParameters.EXACT)
            ex.seekTo(srcIndex(n.src), n.fromMs)
        }
        if (n.isHold) {
            ex.pause()
            holdT0 = SystemClock.uptimeMillis()
        } else ex.play()
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.release() } }
        player = null
        player2?.let { runCatching { it.release() } }
        player2 = null
        player3?.let { runCatching { it.release() } }
        player3 = null
        musicFile = ""
        side2File = ""
        splitKey = ""
    }

    private fun splitName(l: String) = when (l) {
        "rl" -> t("◨ Основное справа", "◨ Main on the right")
        "tb" -> t("⬒ Основное сверху", "⬒ Main on top")
        "bt" -> t("⬓ Основное снизу", "⬓ Main at the bottom")
        else -> t("◧ Основное слева", "◧ Main on the left")
    }

    /** Display size of a second video (cached). */
    private fun sideSize(file: String): Pair<Int, Int> = sideSizes.getOrPut(file) {
        runCatching { StudioEditRender.probe(File(dir, file)).let { it.w to it.h } }.getOrDefault(1 to 1)
    }

    /** Split screen on the preview: the halves of the frame for the main and the second video, the second player in step. */
    private fun layoutSplit() {
        val fr = frameView ?: return
        val mb = mainBox ?: return
        val sb = sideBox ?: return
        val tv = texture ?: return
        val tv2 = texture2 ?: return
        val fw = fr.width.toFloat()
        val fh = fr.height.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val p = project ?: return
        val sp = EditSplit.active(blocks, curMs)
        val cc = clips.getOrNull(curClip)
        val src = cc?.src ?: ""
        val rot = cc?.rot ?: 0
        val (vw0, vh0) = srcSize(src).let { (a, b) -> EditCanvas.turned(a, b, rot) }
        val key = (sp?.let { "${it.id}|${it.look}|${it.file}" } ?: "-") + "|$fw|$fh|${p.ratio}|${p.fill}|$src|$rot|${p.blur}|${if (p.blur) curClip else 0}"
        if (key != splitKey) {
            splitKey = key
            if (sp == null) {
                // the video on the canvas: a rectangle on the background, or covering the frame (format of the project)
                val r = EditCanvas.videoRect(vw0.toFloat(), vh0.toFloat(), fw, fh, p.fill)
                fun at(v: View, rect: FRect) {
                    v.layoutParams = FrameLayout.LayoutParams(max(1, rect.w.roundToInt()), max(1, rect.h.roundToInt())).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = rect.l.roundToInt()
                        topMargin = rect.t.roundToInt()
                    }
                }
                if (p.fill) {
                    mb.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    placeTurned(tv, r, 0f, 0f, rot)
                } else {
                    at(mb, r)
                    placeTurned(tv, r, r.l, r.t, rot)
                }
                sb.visibility = View.GONE
                showBlur(cc, !p.fill && p.blur)
            } else {
                val (mh, sh) = EditSplit.halves(sp.look)
                fun fit(box: View, inner: View, half: FRect, vw: Float, vh: Float) {
                    box.layoutParams = FrameLayout.LayoutParams((half.w * fw).toInt(), (half.h * fh).toInt()).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = (half.l * fw).toInt()
                        topMargin = (half.t * fh).toInt()
                    }
                    // the video covers its half, centred; the rest is cut off by the half
                    val c = EditSplit.cover(vw, vh, half, fw, fh)
                    placeTurned(inner, c, half.l * fw, half.t * fh, if (inner === tv) rot else 0)
                }
                showBlur(null, false)
                fit(mb, tv, mh, vw0.toFloat(), vh0.toFloat())
                val (vw, vh) = sideSize(sp.file)
                fit(sb, tv2, sh, vw.toFloat(), vh.toFloat())
                sb.visibility = View.VISIBLE
            }
        }
        syncSide(sp)
    }

    /** [v] showing a video turned by [rot] so that it fills [rect] (pixels of the frame, minus the parent's offset). */
    private fun placeTurned(v: View, rect: FRect, dx: Float, dy: Float, rot: Int) {
        val odd = (rot / 90) % 2 != 0
        val w = if (odd) rect.h else rect.w
        val h = if (odd) rect.w else rect.h
        val cx = (rect.l + rect.r) / 2 - dx
        val cy = (rect.t + rect.b) / 2 - dy
        v.layoutParams = FrameLayout.LayoutParams(max(1, w.roundToInt()), max(1, h.roundToInt())).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (cx - w / 2).roundToInt()
            topMargin = (cy - h / 2).roundToInt()
        }
        v.rotation = rot.toFloat()
    }

    /** A blurred, enlarged frame of the piece behind a fitted video (the "blur" background of the format). */
    private fun showBlur(c: VClip?, on: Boolean) {
        val iv = blurView ?: return
        val bmp = if (on && c != null) thumbOf(c) else null
        if (bmp == null) {
            iv.visibility = View.GONE
            return
        }
        val small = Bitmap.createScaledBitmap(bmp, 16, max(1, 16 * bmp.height / max(1, bmp.width)), true)
        iv.setImageBitmap(small)
        iv.rotation = c?.rot?.toFloat() ?: 0f
        if (Build.VERSION.SDK_INT >= 31) iv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(40f, 40f, android.graphics.Shader.TileMode.CLAMP))
        iv.visibility = View.VISIBLE
    }

    private fun syncSide(sp: EBlock?) {
        // the main video quieter during a split screen (above 100 % is heard in the export only)
        player?.let { ex ->
            val v = if (recording && recMute) 0f
            else (EditSplit.mainVolume(blocks, curMs, clips.getOrNull(curClip)?.volume ?: 1f) * EditMusic.mainVolume(blocks, curMs)).coerceIn(0f, 1f)
            if (ex.volume != v) ex.volume = v
        }
        if (sp == null) {
            player2?.takeIf { it.playWhenReady }?.pause()
            return
        }
        val ex2 = player2 ?: ExoPlayer.Builder(this).build().also { ex ->
            player2 = ex
            ex.setVideoTextureView(texture2)
        }
        val v2 = sp.volume.coerceIn(0f, 1f)
        if (ex2.volume != v2) ex2.volume = v2
        if (side2File != sp.file) {
            side2File = sp.file
            ex2.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(dir, sp.file))))
            ex2.prepare()
            side2SeekAt = 0L
        }
        val want = EditSplit.srcAt(sp, curMs)
        val now = SystemClock.uptimeMillis()
        if (playing) {
            if (!ex2.playWhenReady) {
                ex2.seekTo(want)
                ex2.play()
            } else if (kotlin.math.abs(ex2.currentPosition - want) > 300 && now - side2SeekAt > 500) {
                side2SeekAt = now
                ex2.seekTo(want)
            }
        } else {
            if (ex2.playWhenReady) ex2.pause()
            if (kotlin.math.abs(ex2.currentPosition - want) > 30 && now - side2SeekAt > 50) {
                side2SeekAt = now
                ex2.seekTo(want)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed || isDestroyed) return
            val ex = player
            if (ex != null && playing) follow(ex)
            window.decorView.postOnAnimation(this)
        }
    }

    private fun sync(fromTimeline: Boolean = false) {
        stage?.timeMs = curMs
        if (!fromTimeline) timeline?.setPlayhead(curMs)
        timeText?.text = StudioEdit.clock(curMs) + " / " + StudioEdit.clock(total)
        layoutSplit()
        syncMusic()
        stillView?.let { sv ->
            val d = EditColor.dissolveAt(clips, curMs)
            if (d == null || d.second != stillOf || !playing) {
                if (sv.visibility != View.GONE) sv.visibility = View.GONE
            } else {
                sv.alpha = d.first
                sv.visibility = View.VISIBLE
            }
        }
        fadeView?.let { fv ->
            val f = EditColor.fadeAt(clips, curMs)
            if (f == null) {
                if (fv.visibility != View.GONE) fv.visibility = View.GONE
            } else {
                fv.setBackgroundColor(f.color)
                fv.alpha = f.alpha
                fv.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePlay() {
        playBtn?.text = if (playing) "⏸" else "▶"
    }

    internal fun pause() {
        playing = false
        player?.pause()
        player3?.pause()
        updatePlay()
    }

    private fun play() {
        if (player == null) return
        if (curMs >= total - 100) curMs = 0
        playing = true
        sync()
        place(true)
        updatePlay()
    }

    private fun toggle() {
        if (playing) pause() else play()
    }

    private fun goToTime() {
        pause()
        prompt(t("Перейти к моменту", "Go to a moment"), t("Секунды или 0:12.5", "Seconds or 0:12.5"), StudioEdit.clock(curMs)) { v ->
            StudioEdit.parseTime(v)?.let { seekTo(it) } ?: toast(t("Не понял время", "Not a time"))
        }
    }

    /** Moves to [ms]; while the finger still moves ([final] false) the player seeks at most every 40 ms. */
    /**
     * TalkBack on the timeline canvas: a spoken summary and actions instead of drags
     * (±1 s, previous/next seam, select the piece under the cursor, the list of blocks).
     */
    private fun timelineA11y(tl: View) {
        val ids = intArrayOf(0x7f0f0a01, 0x7f0f0a02, 0x7f0f0a03, 0x7f0f0a04, 0x7f0f0a05, 0x7f0f0a06)
        tl.isFocusable = true
        tl.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        tl.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.contentDescription = t("Таймлайн", "Timeline") + ": " + StudioEdit.clock(curMs) + t(" из ", " of ") + StudioEdit.clock(total) +
                    t(", кусков ", ", pieces ") + maxOf(1, clips.size) + t(", блоков ", ", blocks ") + blocks.size
                val labels = listOf(t("Назад на секунду", "Back one second"), t("Вперёд на секунду", "Forward one second"),
                    t("К предыдущему шву", "To the previous seam"), t("К следующему шву", "To the next seam"),
                    t("Выбрать кусок под курсором", "Select the piece under the cursor"), t("Список блоков", "List of blocks"))
                ids.forEachIndexed { i, id -> info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(id, labels[i])) }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val starts = if (clips.size > 1) EditTrack.starts(clips).toList() else emptyList()
                when (action) {
                    ids[0] -> seekTo((curMs - 1000).coerceAtLeast(0))
                    ids[1] -> seekTo((curMs + 1000).coerceAtMost(total))
                    ids[2] -> seekTo(starts.lastOrNull { it < curMs - 50 } ?: 0L)
                    ids[3] -> seekTo(starts.firstOrNull { it > curMs + 50 } ?: total)
                    ids[4] -> if (clips.isNotEmpty()) selectClip(maxOf(0, starts.indexOfLast { it <= curMs }))
                    ids[5] -> blocksSheet()
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                host.announceForAccessibility(StudioEdit.clock(curMs))
                return true
            }
        }
    }

    private fun seekTo(ms: Long, final: Boolean = true, fromTimeline: Boolean = false) {
        curMs = ms.coerceIn(0, total)
        sync(fromTimeline)
        place(final)
    }

    /** Frames of every video of the track for the timeline (the base one first), each strip filled in as it comes. */
    private fun loadThumbs(p: EProject) {
        for (src in EditTrack.sources(EditTrack.of(p))) if (src !in thumbMap) loadThumbs(p, src)
    }

    private fun loadThumbs(p: EProject, src: String) {
        val f = if (src.isEmpty()) File(StudioEditStore.dir(this, p.id), StudioEditStore.BASE) else File(StudioEditStore.dir(this, p.id), src)
        thumbMap[src] = emptyList()
        val th = dp(46)
        val (vw, vh) = if (src.isEmpty()) p.w to p.h else sideSize(src)
        val tw = max(8, (th * vw / max(1, vh).toFloat()).toInt())
        fun show(list: List<Pair<Long, Bitmap>>) {
            if (project?.id != p.id) return
            thumbMap[src] = list
            if (src.isEmpty()) {
                thumbList = list
                timeline?.thumbs = list
            }
            timeline?.srcThumbs = HashMap(thumbMap)
            if (project?.blur == true) {
                splitKey = ""
                layoutSplit()
            }
        }
        Lumen.async({
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: p.durMs
                val n = (dur / 1000).toInt().coerceIn(if (src.isEmpty()) 8 else 4, if (src.isEmpty()) 48 else 24)
                val got = ArrayList<Pair<Long, Bitmap>>()
                (0 until n).forEach { i ->
                    val ms = dur * i / n + dur / (2 * n)
                    val bmp = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw * 2, th * 2)
                    else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        Bitmap.createScaledBitmap(full, tw * 2, th * 2, true).also { if (it !== full) full.recycle() }
                    }
                    if (bmp != null) got += ms to bmp
                    // the strip fills in as frames come, not after the last one
                    if (got.size % 8 == 0 && got.isNotEmpty()) {
                        val part = got.toList()
                        Lumen.ui { show(part) }
                    }
                }
                got.toList()
            } finally {
                runCatching { r.release() }
            }
        }) { list -> show(list) }
    }

    // ---------- changes ----------

    private fun snapshot(): String = JSONObject().put("b", StudioEdit.blocksJson(blocks))
        .put("c", EditTrack.json(project?.clips.orEmpty()))
        .put("r", project?.ratio.orEmpty()).put("f", project?.fill == true).put("bg", project?.bg ?: 0xFF000000.toInt())
        .put("bl", project?.blur == true).toString()

    internal fun setBlocks(list: List<EBlock>) {
        val p = project ?: return
        project = p.copy(blocks = list)
        stage?.blocks = EditSplit.overlays(list)
        timeline?.blocks = list
        splitKey = ""
        layoutSplit()
        if (list.any { it.kind == EditAnim.KIND }) anims.checkLater()
    }

    internal fun save() {
        val p = project ?: return
        runCatching { StudioEditStore.save(applicationContext, p) }.onFailure { L.w("studio", "edit save: ${it.message}") }
    }

    /** A finished change: remembered for undo, saved, the selection kept when the block is still there. */
    internal fun commit(list: List<EBlock>, sel: String? = selected) {
        history.push(snapshot())
        setBlocks(list)
        save()
        refreshUndo()
        select(sel?.takeIf { id -> list.any { it.id == id } }, seek = false)
    }

    private fun change(id: String, f: (EBlock) -> EBlock) {
        val b = StudioEdit.find(blocks, id) ?: return
        commit(StudioEdit.replace(blocks, StudioEdit.clampTime(keyed(b, f(b)), total)))
    }

    /** With keyframes a new place, size, turn or opacity goes into the key at the playhead (made when there is none). */
    private fun keyed(old: EBlock, new: EBlock): EBlock {
        // moving or trimming on the timeline is not a change of the look
        if (old.keys.isEmpty() || new.keys != old.keys || new.startMs != old.startMs || new.durMs != old.durMs) return new
        val now = EditKeys.at(old, curMs)
        // a field the change left as stored keeps its look at this moment
        fun pick(n: Float, o: Float, i: Float) = if (n == o) i else n
        val pose = new.copy(x = pick(new.x, old.x, now.x), y = pick(new.y, old.y, now.y), w = pick(new.w, old.w, now.w),
            rot = pick(new.rot, old.rot, now.rot), opacity = pick(new.opacity, old.opacity, now.opacity))
        val moved = now.x != pose.x || now.y != pose.y || now.w != pose.w || now.rot != pose.rot || now.opacity != pose.opacity
        return if (moved && curMs >= new.startMs && curMs < new.endMs) EditKeys.set(new, curMs, pose) else new
    }

    /** ◆ at the playhead: a keyframe of the block as it looks now, or away when one is there. */
    private fun toggleKey(b: EBlock) {
        if (curMs < b.startMs || curMs >= b.endMs) return toast(t("Поставьте курсор на время блока", "Put the playhead on the block's time"))
        if (EditKeys.near(b, curMs) != null) {
            commit(StudioEdit.replace(blocks, EditKeys.remove(b, curMs)))
            return toast(t("Ключ убран", "Keyframe removed"))
        }
        val first = b.keys.isEmpty()
        commit(StudioEdit.replace(blocks, EditKeys.set(b, curMs, EditKeys.at(b, curMs))))
        toast(if (first) t("◆ Ключ поставлен. Передвиньте курсор и измените блок на кадре — появится второй ключ, блок поедет между ними.",
            "◆ Keyframe set. Move the playhead and change the block on the frame — a second key appears and the block moves between them.")
        else t("◆ Ключ поставлен", "◆ Keyframe set"))
    }

    /** A block changed by a gesture on the frame or the timeline; [done] at its end. */
    private fun live(b: EBlock, done: Boolean) {
        if (gestureBase == null) {
            gestureBase = snapshot()
            pause()
        }
        val old = StudioEdit.find(blocks, b.id)
        setBlocks(StudioEdit.replace(blocks, StudioEdit.clampTime(if (old != null) keyed(old, b) else b, total)))
        if (done) {
            gestureBase?.let { history.push(it) }
            gestureBase = null
            save()
            refreshUndo()
        }
    }

    internal fun select(id: String?, seek: Boolean = true) {
        selected = id
        if (id != null && clipSel >= 0) {
            clipSel = -1
            timeline?.clipSel = -1
        }
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
        val p = project ?: return
        val o = runCatching { JSONObject(s) }.getOrNull()
        val list = StudioEdit.blocksFrom(o?.optJSONArray("b") ?: runCatching { JSONArray(s) }.getOrNull())
        setTrack(EditTrack.from(o?.optJSONArray("c")).ifEmpty { EditTrack.initial(p.durMs) }, list)
        if (o != null && o.has("r")) project?.let { cur ->
            val n = cur.copy(ratio = o.optString("r").takeIf { it in EditCanvas.RATIOS } ?: "", fill = o.optBoolean("f"), bg = o.optInt("bg", cur.bg) or 0xFF000000.toInt(),
                blur = o.optBoolean("bl"))
            if (n != cur) {
                project = n
                applyCanvas()
            }
        }
        save()
        refreshUndo()
        if (clipSel >= clips.size) clipSel = -1
        timeline?.clipSel = clipSel
        seekTo(curMs)
        select(selected?.takeIf { id -> blocks.any { it.id == id } }, seek = false)
    }

    /** New pieces of the main track [new] with blocks [list]: words, timeline and picture follow. */
    private fun setTrack(new: List<VClip>, list: List<EBlock>) {
        val p = project ?: return
        val keep = if (EditTrack.plain(new, p.durMs)) emptyList() else new
        val np = p.copy(clips = keep, blocks = list)
        project = np
        words = EditTrack.words(srcWords, EditTrack.of(np), p.durMs)
        curClip = -1
        timeline?.let { tl ->
            tl.totalMs = outLen(np)
            tl.clips = keep
            tl.words = words
            tl.blocks = list
        }
        stage?.blocks = EditSplit.overlays(list)
        stage?.refresh()
        splitKey = ""
        layoutSplit()
        refreshSources()
    }

    /** A finished change of the main track: blocks move with their pieces, the same picture stays under the playhead. */
    private fun commitClips(new: List<VClip>, sel: Int = clipSel) {
        val p = project ?: return
        val old = EditTrack.of(p)
        history.push(snapshot())
        val at = EditTrack.remap(old, new, curMs)
        setTrack(new, EditTrack.remapBlocks(p.blocks, old, new))
        save()
        refreshUndo()
        clipSel = if (sel in new.indices) sel else -1
        timeline?.clipSel = clipSel
        seekTo(at)
        tools()
    }

    private fun selectClip(i: Int) {
        clipSel = if (i in clips.indices) i else -1
        if (clipSel >= 0) focusClip(clipSel)
        if (clipSel >= 0) {
            selected = null
            stage?.selected = null
            timeline?.selected = null
        }
        timeline?.clipSel = clipSel
        tools()
    }

    private fun clipSplit() {
        val (list, right) = EditTrack.split(clips, curMs)
            ?: return toast(t("Поставьте курсор внутрь куска, не у самого шва", "Put the playhead inside a piece, not at a seam"))
        commitClips(list, if (clipSel >= 0) right - 1 else -1)
        toast(t("✂ Разрезано", "✂ Split"))
    }

    private fun clipDelete(i: Int) {
        val list = EditTrack.remove(clips, i) ?: return toast(t("Это единственный кусок ролика", "This is the only piece of the video"))
        commitClips(list, -1)
    }

    private fun clipDuplicate(i: Int) {
        val (list, j) = EditTrack.duplicate(clips, i) ?: return
        commitClips(list, j)
    }

    private fun clipMove(i: Int, dir: Int) {
        val (list, j) = EditTrack.move(clips, i, dir) ?: return toast(if (dir < 0) t("Это уже первый кусок", "This is already the first piece") else t("Это уже последний кусок", "This is already the last piece"))
        commitClips(list, j)
    }

    private fun clipFreeze() {
        val (list, j) = EditTrack.freeze(clips, curMs) ?: return toast(t("Курсор уже на стоп-кадре", "The playhead is already on a freeze frame"))
        commitClips(list, j)
        toast(t("❄ Стоп-кадр на ${EditTrack.HOLD_MS / 1000} с", "❄ Freeze frame for ${EditTrack.HOLD_MS / 1000} s"))
    }

    private fun clipTrim(i: Int, start: Boolean) {
        val list = (if (start) EditTrack.trimStart(clips, i, curMs) else EditTrack.trimEnd(clips, i, curMs))
            ?: return toast(t("Поставьте курсор внутрь выбранного куска", "Put the playhead inside the selected piece"))
        commitClips(list, i)
    }

    /** Piece [i] backwards: a reversed copy of it is made once (silent), and the piece plays that copy. */
    private fun reverseClip(i: Int) {
        val c = clips.getOrNull(i) ?: return
        if (c.isHold) return
        if (BgTasks.has(TASK_REV)) return toast(t("Реверс уже идёт", "A reverse is already running"))
        if (c.toMs - c.fromMs > 60_000) return toast(t("Реверс — для кусков до минуты: сначала разрежьте (✂)", "Reverse works on pieces up to a minute: split first (✂)"))
        val p = project ?: return
        pause()
        val d = dir
        val srcF = srcFile(c.src)
        val name = "img/rev${System.currentTimeMillis()}.mp4"
        BgTasks.run(this, TASK_REV, t("Редактор: реверс куска", "Editor: reversing a piece"), BgTasks.reopen(this), { task ->
            val len = StudioEditRender.reverse(srcF, c.fromMs, c.toMs, File(d, name), 1280, { task.cancelled }) { m -> BgTasks.progress(TASK_REV, m) }
            len to StudioEditRender.probe(File(d, name))
        }, { e -> if (!isDestroyed) err(e) }) { (len, pr) ->
            if (project?.id != p.id) return@run
            val j = clips.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: return@run
            sideSizes[name] = pr.w to pr.h
            commitClips(EditTrack.update(clips, j) { it.copy(src = name, fromMs = 0, toMs = max(EditTrack.MIN_MS + 1, min(len, pr.durMs)), volume = 0f) }, j)
            project?.let { loadThumbs(it, name) }
            toast(t("⏪ Кусок задом наперёд (без звука)", "⏪ The piece plays backwards (silent)"))
        }
        watch()
    }

    private fun clipUpdate(i: Int, f: (VClip) -> VClip) {
        if (i !in clips.indices) return
        commitClips(EditTrack.update(clips, i, f), i)
    }

    private fun speedSheet(i: Int) {
        val c = clips.getOrNull(i) ?: return
        bottomSheet(t("⏩ Скорость куска", "⏩ Speed of the piece")) { dismiss ->
            muted(t("Длина сейчас ${StudioEdit.clock(c.outMs).substringBefore('.')} с. Голос ускоряется вместе с картинкой, тон меняется.",
                "Now ${StudioEdit.clock(c.outMs).substringBefore('.')} long. The voice speeds up with the picture, its pitch changes."))
            add(choiceChips(ctx, EditTrack.SPEEDS.map { it to EditTrack.speedLabel(it) }, c.speed) { v ->
                clipUpdate(i) { it.copy(speed = v) }
                dismiss()
            })
            if (clips.size > 1) button(t("Эту скорость — всем кускам", "This speed for all pieces"), style = BtnStyle.TEXT) {
                dismiss()
                val sp = clips.getOrNull(i)?.speed ?: return@button
                commitClips(clips.map { if (it.isHold) it else it.copy(speed = sp) }, i)
            }
        }
    }

    private fun volumeSheet(i: Int) {
        val c = clips.getOrNull(i) ?: return
        bottomSheet(t("🔊 Громкость куска", "🔊 Volume of the piece")) { dismiss ->
            muted(t("Больше 100 % слышно только в готовом ролике.", "Above 100 % is heard in the exported video only."))
            add(choiceChips(ctx, EditTrack.VOLUMES.map { it to "$it %" }, (c.volume * 100).toInt()) { v ->
                clipUpdate(i) { it.copy(volume = v / 100f) }
                dismiss()
            })
        }
    }

    private fun zoomSheet(i: Int) {
        val c = clips.getOrNull(i) ?: return
        bottomSheet(t("🔍 Масштаб кадра", "🔍 Frame zoom")) { dismiss ->
            muted(t("Кадр увеличивается от центра, края обрезаются — как приближение в CapCut.", "The frame is enlarged around its centre, the edges are cut off."))
            add(choiceChips(ctx, EditTrack.ZOOMS.map { it to "$it %" }, (c.zoom * 100).toInt()) { v ->
                clipUpdate(i) { it.copy(zoom = v / 100f) }
                dismiss()
            })
        }
    }

    private fun holdSheet(i: Int) {
        val c = clips.getOrNull(i) ?: return
        bottomSheet(t("❄ Длина стоп-кадра", "❄ Freeze frame length")) { dismiss ->
            add(choiceChips(ctx, listOf(500L, 1000L, 2000L, 3000L, 5000L, 8000L).map { it to StudioEdit.secs(it) + t(" с", " s") }, c.holdMs) { v ->
                clipUpdate(i) { it.copy(holdMs = v) }
                dismiss()
            })
        }
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
    internal fun newBlock(p: EProject, list: List<EBlock>, kind: String, at: Long, durMs: Long, ratio: Float, build: (EBlock) -> EBlock): EBlock {
        val (s, d) = StudioEdit.placeAt(at, outLen(p), durMs)
        val (fw, fh) = EditCanvas.frame(p)
        val portrait = fh >= fw
        val w = StudioEdit.fitWidth(StudioEdit.defaultWidth(kind, portrait), ratio, fw, fh)
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

    internal fun lang(): String = Studio.langName(Studio.voiceLang(StudioPrefs.voiceLang,
        EdgeTts.detectLang(words.take(400).joinToString(" ") { it.text }), Settings.lang == "en"))

    /** Shape of a scheme block in the frame of [p] (its format). */
    private fun schemeRatio(p: EProject): Float = EditCanvas.frame(p).let { (w, h) -> StudioEdit.schemeRatio(w, h) }

    private fun formatText(p0: EProject) = EditCanvas.frame(p0).let { (w, h) -> p0.copy(w = w, h = h) }.let { p -> formatTextOf(p) }

    private fun formatTextOf(p: EProject) = when {
        p.h > p.w * 1.1f -> t("вертикального ролика 9:16 (Shorts)", "vertical 9:16 video (Shorts)")
        p.w > p.h * 1.1f -> t("горизонтального ролика 16:9", "horizontal 16:9 video")
        else -> t("квадратного ролика 1:1", "square 1:1 video")
    }

    private fun aspect(p0: EProject) = EditCanvas.frame(p0).let { (w, h) -> aspectOf(p0.copy(w = w, h = h)) }

    private fun aspectOf(p: EProject) = when {
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
            item(t("✨ Схема или анимация от ИИ", "✨ A scheme or an animation by AI"), t("помощник: что показать, как выглядит, прозрачность, движение — встроенный или другой ИИ", "the wizard: what to show, the look, transparency, motion — the built-in or another AI")) { InsertWizard.open(this@StudioEditorActivity) }
            if (words.isNotEmpty()) item(t("🤖 Схемы на весь ролик", "🤖 Schemes for the whole video"), t("ИИ сам выберет места и поставит схемы", "the AI picks the places and puts schemes there")) { autoSchemes() }
            item(t("✍️ Схема своим текстом", "✍️ Scheme from your text"), t("строки «эмодзи Подпись — значение», без ИИ", "lines \"emoji Label — value\", no AI")) { textSchemeSheet(null) }
            item(t("🖼 Картинка из галереи", "🖼 Picture from the gallery"), t("фото, скриншот, логотип (PNG с прозрачностью тоже)", "a photo, screenshot or logo (transparent PNG too)")) { pickImage(null) }
            item(t("🌐 Фото по запросу", "🌐 Photo search"), t("бесплатные фото Openverse; запрос может подобрать ИИ", "free Openverse photos; the AI can suggest the query")) { photoSheet(null) }
            item(t("T Надпись", "T Caption"), t("свой текст или варианты от ИИ", "your text or AI suggestions")) { captionSheet(null) }
            item(t("😀 Эмодзи", "😀 Emoji"), t("крупный значок поверх кадра", "a big icon over the frame")) { emojiSheet(null) }
            item(t("💬 Автосубтитры", "💬 Auto subtitles"), t("речь → подписи по словам, стиль на все сразу", "speech → captions by the words, one style for all")) { subsSheet() }
            item(t("🎞 Ещё видео в ролик", "🎞 Another video into the track"), t("клип с телефона: в конец или у курсора, как в CapCut", "a clip from the phone: at the end or at the playhead, like CapCut")) { addClipSheet() }
            item(t("🎵 Музыка", "🎵 Music"), t("трек с телефона с этого места; громкость и затухание", "a track from the phone from here; volume and fades")) { pickAudio(null) }
            item(t("⊟ Второе видео рядом", "⊟ A second video beside"), t("разделённый экран: реакция, сравнение", "split screen: a reaction, a comparison")) { pickSplit(null) }
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
                    val (st, du) = StudioEdit.spanOf(ws, segs[i], outLen(pr))
                    cur = cur + newBlock(pr, cur, "scheme", st, du, schemeRatio(pr)) {
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
                    val (st, du) = StudioEdit.spanOf(words, r, outLen(p))
                    info.text = StudioEdit.clock(st) + " – " + StudioEdit.clock(st + du) + "  ·  " + r.joinToString(" ") { words[it].text }.take(90)
                    acts.addView(chip(ctx, t("✨ Схема или анимация", "✨ Scheme or animation")) {
                        dismiss()
                        InsertWizard.open(this@StudioEditorActivity, null, r)
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
                        play()
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

    internal fun blockName(b: EBlock): String = when (b.kind) {
        "scheme" -> if (b.scheme == null) "🧩 ⏳ " + if (b.req.isNotEmpty()) t("ждёт ответ ИИ #", "waits for the AI #") + b.req else t("ИИ рисует…", "AI drawing…")
        else "🧩 " + (b.scheme.title.takeIf { it.isNotBlank() } ?: b.scheme.nodes.joinToString(" → ") { it.label }.take(40))
        "image" -> "🖼 " + (b.ask.ifBlank { t("Картинка", "Picture") })
        EditAnim.KIND -> "🎞 " + if (b.file.isEmpty()) "⏳ " + if (b.req.isNotEmpty()) t("ждёт ответ ИИ #", "waits for the AI #") + b.req else t("ИИ пишет…", "AI writing…")
        else b.text.ifBlank { t("Анимация", "Animation") }
        "text" -> "T " + b.text.replace('\n', ' ').take(40)
        EditSplit.KIND -> "⊟ " + t("Разделённый экран", "Split screen")
        EditMusic.KIND -> (if (b.text.startsWith("🎙")) "" else "🎵 ") + b.text.ifBlank { t("Музыка", "Music") }
        else -> b.text + " " + t("эмодзи", "emoji")
    }

    /** All blocks by time: a tap selects one and goes to it. */
    private fun blocksSheet(kind: String? = null) {
        if (project == null) return
        pause()
        val list = blocks.filter { kind == null || it.kind == kind }
        bottomSheet((if (kind == EditMusic.KIND) t("🎵 Звуки", "🎵 Sounds") else t("☰ Слои", "☰ Layers")) + " (${list.size})") { dismiss ->
            if (list.isEmpty()) muted(if (kind == EditMusic.KIND) t("Пока нет музыки: «🎵 Музыка» добавит трек с телефона с места курсора.",
                "No music yet: \"🎵 Music\" adds a track from the phone at the playhead.")
            else t("Пока пусто: остановите видео в нужном месте и нажмите «＋ Вставить».", "Empty so far: stop the video at a moment and tap \"＋ Insert\"."))
            // several blocks at once: tick them, then delete or shift together (one ↶ step each)
            val picked = linkedSetOf<String>()
            var picking = false
            val bar = Col(ctx, 0)
            val rows = Col(ctx, 0)
            fun fill() {
                bar.clear()
                rows.clear()
                if (list.size > 1) bar.add(chipScroller(ctx, if (!picking) listOf(chip(ctx, t("☑ Выбрать несколько", "☑ Select several")) { picking = true; fill() })
                else listOf(
                    chip(ctx, t("Выбрано: ", "Picked: ") + picked.size, true),
                    chip(ctx, t("Все", "All")) { picked.addAll(list.map { it.id }); fill() },
                    chip(ctx, t("◀ 0,5 с", "◀ 0.5 s")) { shiftPicked(picked, -500); fill() },
                    chip(ctx, t("0,5 с ▶", "0.5 s ▶")) { shiftPicked(picked, 500); fill() },
                    chip(ctx, t("🗑 Удалить", "🗑 Delete")) {
                        if (picked.isEmpty()) return@chip
                        val n = picked.size
                        commit(blocks.filter { it.id !in picked }, null)
                        toast(t("Удалено блоков: $n (↶ — вернуть)", "Blocks deleted: $n (↶ brings them back)"))
                        dismiss()
                    },
                    chip(ctx, t("Готово", "Done")) { picking = false; picked.clear(); fill() }
                )), 0)
                for (b0 in list.sortedBy { it.startMs }) {
                    val b = StudioEdit.find(blocks, b0.id) ?: continue
                    val mark = if (picking) (if (b.id in picked) "☑ " else "☐ ") else ""
                    rows.add(text(ctx, mark + blockName(b) + "\n" + StudioEdit.clock(b.startMs) + " – " + StudioEdit.clock(b.endMs), 15f).apply {
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        if (b.id == selected || b.id in picked) setTextColor(Palette.ACCENT)
                        background = ripple(ctx, roundBg(0x00000000, 12f, ctx), Palette.ON_SURFACE)
                        setOnClickListener {
                            if (picking) {
                                if (!picked.add(b.id)) picked.remove(b.id)
                                fill()
                            } else {
                                dismiss()
                                select(b.id, seek = true)
                            }
                        }
                        setOnLongClickListener {
                            picking = true
                            picked.add(b.id)
                            fill()
                            true
                        }
                    }, 2)
                }
            }
            add(bar.root, 4)
            add(rows.root, 4)
            fill()
        }
    }

    /** Moves the picked blocks together by [ms] (one undo step). */
    private fun shiftPicked(ids: Set<String>, ms: Long) {
        if (ids.isEmpty()) return
        commit(blocks.map { b -> if (b.id in ids) StudioEdit.clampTime(b.copy(startMs = (b.startMs + ms).coerceAtLeast(0)), total) else b })
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
                else project?.let { p -> addNew("scheme", at, 5000L, schemeRatio(p)) { it.copy(scheme = s, look = style, animIn = "fade", animOut = "fade") } }
                stage?.refresh()
            }
            if (old != null) button(t("🤖 Переделать ИИ", "🤖 Redo with AI"), style = BtnStyle.TEXT) {
                dismiss()
                InsertWizard.open(this@StudioEditorActivity, old)
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

    private fun pickSplit(replace: String?) {
        pause()
        pickFor = replace
        pickAt = curMs
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_SPLIT)
    }

    /** A second video for the split screen: copied into the project, put at the playhead for its length (or to the end). */
    private fun importSplit(uri: Uri) {
        val p = project ?: return
        val replace = pickFor
        val at = pickAt
        val d = dir
        val cr = applicationContext.contentResolver
        toast(t("Копирую второе видео…", "Copying the second video…"))
        Lumen.async({
            val name = "img/v${System.currentTimeMillis()}.mp4"
            val f = File(d, name).apply { parentFile?.mkdirs() }
            (cr.openInputStream(uri) ?: error(t("Файл не открывается", "The file cannot be opened"))).use { i -> f.outputStream().use { i.copyTo(it) } }
            val pr = runCatching { StudioEditRender.probe(f) }.getOrElse {
                f.delete()
                throw it
            }
            name to pr.durMs
        }, { e -> if (!isDestroyed) err(e) }) { (file, dur) ->
            if (project?.id != p.id) return@async
            if (replace != null) change(replace) { it.copy(file = file, srcMs = 0) }
            else {
                val len = min(dur, max(StudioEdit.MIN_MS, outLen(p) - at)).coerceAtLeast(StudioEdit.MIN_MS)
                addNew(EditSplit.KIND, at, len, 1f) {
                    it.copy(file = file, look = EditSplit.defaultLayout(p.w, p.h), x = 0.5f, y = 0.5f, w = 1f, animIn = "none", animOut = "none")
                }
            }
            splitKey = ""
            layoutSplit()
        }
    }

    private fun splitSheet(b: EBlock) {
        bottomSheet(t("⊟ Разделённый экран", "⊟ Split screen")) { dismiss ->
            muted(t("Основное видео и второе делят кадр пополам, каждое обрезается по своей половине. Звук обоих смешивается — громкость в «🔊 Звук».",
                "The main and the second video share the frame, each cropped to its half. Both sounds are mixed — loudness in \"🔊 Sound\"."))
            add(choiceChips(ctx, EditSplit.LAYOUTS.map { it to splitName(it) }, b.look) { v ->
                change(b.id) { it.copy(look = v) }
                dismiss()
            })
            button(t("⏮ Второе видео с ", "⏮ Second video from ") + StudioEdit.clock(b.srcMs), style = BtnStyle.TONAL) {
                dismiss()
                splitFrom(b)
            }
            button(t("🔁 Другое видео", "🔁 Another video"), style = BtnStyle.TEXT) {
                dismiss()
                pickSplit(b.id)
            }
        }
    }

    private fun splitSoundSheet(b: EBlock) {
        bottomSheet(t("🔊 Звук разделённого экрана", "🔊 Split screen sound")) { dismiss ->
            label(this, t("Второе видео", "Second video"))
            add(choiceChips(ctx, EditTrack.VOLUMES.map { it to "$it %" }, (b.volume * 100).roundToInt()) { v ->
                change(b.id) { it.copy(volume = v / 100f) }
            })
            label(this, t("Основное видео в это время", "Main video meanwhile"))
            add(choiceChips(ctx, listOf(0, 25, 50, 75, 100, 150).map { it to "$it %" }, (b.mainVolume * 100).roundToInt()) { v ->
                change(b.id) { it.copy(mainVolume = v / 100f) }
            })
            muted(t("Реакция: второе 100 %, основное 0–25 %. Больше 100 % слышно только в готовом ролике.",
                "Reaction: second 100 %, main 0–25 %. Above 100 % is heard in the exported video only."))
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    private fun splitFrom(b: EBlock) {
        pause()
        prompt(t("С какого момента второе видео", "Second video from"), t("Секунды или 0:12.5", "Seconds or 0:12.5"), StudioEdit.clock(b.srcMs)) { v ->
            StudioEdit.parseTime(v)?.let { ms -> change(b.id) { it.copy(srcMs = max(0L, ms)) } } ?: toast(t("Не понял время", "Not a time"))
        }
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
            REQ_SPLIT -> importSplit(uri)
            REQ_AUDIO -> importAudio(uri)
            REQ_CLIP -> importClip(uri)
            REQ_REF -> InsertWizard.current?.refPicked(uri)
        }
    }

    /** A picture from the phone: scaled to 2048 px at most, turned upright by its EXIF, kept as PNG when it has transparency. */
    internal fun saveImage(uri: Uri, d: File): Pair<String, Float> {
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
        val placed = span?.let { StudioEdit.spanOf(words, it, outLen(p)) }
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
        val placed = span?.let { StudioEdit.spanOf(words, it, outLen(p)) }
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
        "scheme" -> if (b.brief.isNotEmpty()) InsertWizard.open(this, b) else textSchemeSheet(b)
        EditAnim.KIND -> InsertWizard.open(this, b)
        "image" -> imageSheet(b)
        "text" -> captionSheet(b)
        EditSplit.KIND -> splitSheet(b)
        EditMusic.KIND -> musicSheet(b)
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
                play()
            }
            if (blocks.size > 1) button(t("Применить ко всем блокам", "Apply to all blocks"), style = BtnStyle.TEXT) {
                dismiss()
                val cur = StudioEdit.find(blocks, b.id) ?: return@button
                commit(blocks.map { it.copy(animIn = cur.animIn, animOut = cur.animOut) })
            }
        }
    }

    /** Exact position, size and angle by buttons: the same as dragging, without gestures (TalkBack, precision). */
    private fun positionSheet(id: String) {
        bottomSheet(t("📐 Положение", "📐 Position")) { _ ->
            muted(t("Каждое нажатие — шаг; ↶ отменяет.", "Each tap is one step; ↶ undoes."))
            fun row(label: String, value: (EBlock) -> String, step: (EBlock, Int) -> EBlock) {
                val v = text(ctx, "", 15f, bold = true).apply {
                    gravity = android.view.Gravity.CENTER
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                fun show() { StudioEdit.find(blocks, id)?.let { v.text = value(it) } }
                show()
                fun tap(dir: Int) {
                    StudioEdit.find(blocks, id)?.let { b -> change(id) { step(b, dir) } }
                    show()
                }
                add(text(ctx, label, Type.LABEL, bold = true, color = Palette.MUTED), 12)
                add(rowOf(ctx,
                    btn(ctx, "−", style = BtnStyle.TONAL) { tap(-1) }.apply { contentDescription = t("Меньше: ", "Less: ") + label },
                    v,
                    btn(ctx, "＋", style = BtnStyle.TONAL) { tap(1) }.apply { contentDescription = t("Больше: ", "More: ") + label }
                ), 4)
            }
            row(t("По горизонтали", "Horizontal"), { "${(it.x * 100).roundToInt()}%" }) { b, d -> b.copy(x = (b.x + d * 0.01f).coerceIn(0f, 1f)) }
            row(t("По вертикали", "Vertical"), { "${(it.y * 100).roundToInt()}%" }) { b, d -> b.copy(y = (b.y + d * 0.01f).coerceIn(0f, 1f)) }
            row(t("Ширина", "Width"), { "${(it.w * 100).roundToInt()}%" }) { b, d -> b.copy(w = (b.w + d * 0.02f).coerceIn(0.05f, 1.5f)) }
            row(t("Угол", "Angle"), { "${it.rot.roundToInt()}°" }) { b, d -> b.copy(rot = ((b.rot + d * 5f + 540f) % 360f) - 180f) }
            row(t("Начало", "Start"), { StudioEdit.clock(it.startMs) }) { b, d -> b.copy(startMs = (b.startMs + d * 100L).coerceAtLeast(0)) }
            row(t("Длительность", "Duration"), { StudioEdit.clock(it.durMs) }) { b, d -> b.copy(durMs = (b.durMs + d * 100L).coerceAtLeast(300)) }
        }
    }

    /** ◑ The video darkened under the block (or over the whole frame) while it is shown, fading with it. */
    private fun dimSheet(b: EBlock) {
        bottomSheet(t("◑ Затемнение видео", "◑ Darken the video")) { _ ->
            muted(t("Видео под блоком темнеет, пока блок на экране, — текст читается на любом кадре.", "The video under the block darkens while the block is shown — the text reads on any frame."))
            slider(t("Затемнение", "Darkness"), b.dim, 0f, 0.8f, { "${(it * 100).roundToInt()}%" }) { v, up ->
                StudioEdit.find(blocks, b.id)?.let { live(it.copy(dim = v), up) }
            }
            add(choiceChips(ctx, listOf(false to t("Под блоком", "Under the block"), true to t("Весь кадр", "The whole frame")), b.dimAll) { v ->
                change(b.id) { it.copy(dimAll = v) }
            }, 12)
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

    // ---------- colour, transitions, format, music ----------

    /** A slider row: name, value on the right, a bar; [onMove] gets the value and whether the finger is up. */
    private fun Col.slider(name: String, value: Float, from: Float, to: Float, fmt: (Float) -> String, onMove: (Float, Boolean) -> Unit): SeekBar {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(ctx, name, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(ctx, fmt(value), 14f, bold = true, color = Palette.ACCENT)
        head.addView(v)
        add(head, 14)
        fun at(p: Int) = from + (to - from) * p / 1000f
        val bar = SeekBar(ctx).apply {
            max = 1000
            progress = ((value - from) / (to - from) * 1000).roundToInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    v.text = fmt(at(p))
                    if (fromUser) onMove(at(p), false)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onMove(at(s?.progress ?: 0), true)
                }
            })
        }
        add(bar, 2)
        return bar
    }

    private fun pct(v: Float): String = (if (v > 0.005f) "+" else "") + (v * 100).roundToInt()

    /** The colour of a piece on the picture of the preview (a colour filter on the video layer). */
    private fun previewColor(c: VClip?) {
        val tv = texture ?: return
        val m = c?.let { EditColor.matrix(it) }
        tv.setLayerPaint(m?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) } })
    }

    private fun sameColor(a: VClip, b: VClip) =
        a.filter == b.filter && a.filterK == b.filterK && a.bright == b.bright && a.contrast == b.contrast && a.sat == b.sat && a.warm == b.warm

    private fun withColor(c: VClip, from: VClip) =
        c.copy(filter = from.filter, filterK = from.filterK, bright = from.bright, contrast = from.contrast, sat = from.sat, warm = from.warm)

    /** The playhead into piece [i] so that its picture is seen while its colour is set. */
    private fun focusClip(i: Int) {
        val list = clips
        if (i !in list.indices) return
        val st = EditTrack.starts(list)[i]
        if (curMs < st || curMs >= st + list[i].outMs) seekTo(st + min(list[i].outMs / 2, 600L))
    }

    /** A change of piece [i] without selecting it. */
    private fun clipSet(i: Int, f: (VClip) -> VClip) {
        if (i !in clips.indices) return
        commitClips(EditTrack.update(clips, i, f))
    }

    /** The colour tried in a sheet is kept when the sheet closes (one step of undo), else the picture goes back. */
    private fun keepColor(i: Int, tried: VClip) {
        val c = clips.getOrNull(i)
        if (c != null && c.id == tried.id && !sameColor(c, tried)) clipSet(i) { withColor(it, tried) }
        else previewColor(clips.getOrNull(curClip))
    }

    /** A small frame of the piece for the filter tiles. */
    private fun thumbOf(c: VClip): Bitmap? {
        val list = thumbMap[c.src].orEmpty()
        if (list.isEmpty()) return null
        val at = if (c.isHold) c.fromMs else (c.fromMs + c.toMs) / 2
        return list.minByOrNull { kotlin.math.abs(it.first - at) }?.second
    }

    private fun filterSheet(i: Int) {
        val c0 = clips.getOrNull(i) ?: return
        pause()
        focusClip(i)
        var cur = c0
        previewColor(cur)
        bottomSheet(t("🎨 Фильтры", "🎨 Filters"), onDismiss = { keepColor(i, cur) }) { dismiss ->
            if (clips.size > 1) muted(t("Кусок ${i + 1} из ${clips.size}. Изменения видны на кадре сразу.", "Piece ${i + 1} of ${clips.size}. Changes show on the frame at once."))
            val thumb = thumbOf(c0)
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val tiles = ArrayList<Pair<String, View>>()
            var power: SeekBar? = null
            fun mark() = tiles.forEach { (id, v) -> v.background = roundBg(0x00000000, 12f, ctx, if (id == cur.filter) Palette.ACCENT else 0) }
            for (id in EditColor.FILTERS) {
                val tile = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    contentDescription = EditColor.filterName(id)
                    setOnClickListener {
                        cur = cur.copy(filter = id, filterK = if (id.isEmpty()) 1f else cur.filterK.takeIf { it > 0f } ?: 1f)
                        power?.progress = (cur.filterK * 1000).roundToInt()
                        previewColor(cur)
                        mark()
                    }
                }
                val iv = android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = roundBg(0xFF5A6270.toInt(), 10f, ctx)
                    if (thumb != null) setImageBitmap(thumb)
                    if (id.isNotEmpty()) colorFilter = ColorMatrixColorFilter(ColorMatrix(EditColor.filter(id)))
                }
                tile.addView(iv, LinearLayout.LayoutParams(dp(60), dp(60)))
                tile.addView(text(ctx, EditColor.filterName(id), 12f).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                strip.addView(tile)
                tiles += id to tile
            }
            mark()
            add(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }, 12)
            power = slider(t("Сила", "Strength"), cur.filterK, 0f, 1f, { "${(it * 100).roundToInt()} %" }) { v, _ ->
                cur = cur.copy(filterK = v)
                previewColor(cur)
            }
            if (clips.size > 1) button(t("Применить ко всем кускам", "Apply to all pieces"), style = BtnStyle.TEXT) {
                val tried = cur
                commitClips(clips.map { it.copy(filter = tried.filter, filterK = tried.filterK) })
                cur = clips.getOrNull(i) ?: tried
                dismiss()
                toast(t("Фильтр на всём ролике", "Filter on the whole video"))
            }
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    private fun adjustSheet(i: Int) {
        val c0 = clips.getOrNull(i) ?: return
        pause()
        focusClip(i)
        var cur = c0
        previewColor(cur)
        bottomSheet(t("☀ Настройка", "☀ Adjust"), onDismiss = { keepColor(i, cur) }) { dismiss ->
            if (clips.size > 1) muted(t("Кусок ${i + 1} из ${clips.size}.", "Piece ${i + 1} of ${clips.size}."))
            val bars = ArrayList<SeekBar>()
            bars += slider(t("☀ Яркость", "☀ Brightness"), cur.bright, -1f, 1f, { pct(it) }) { v, _ -> cur = cur.copy(bright = v); previewColor(cur) }
            bars += slider(t("◑ Контраст", "◑ Contrast"), cur.contrast, -1f, 1f, { pct(it) }) { v, _ -> cur = cur.copy(contrast = v); previewColor(cur) }
            bars += slider(t("🎨 Насыщенность", "🎨 Saturation"), cur.sat, -1f, 1f, { pct(it) }) { v, _ -> cur = cur.copy(sat = v); previewColor(cur) }
            bars += slider(t("🌡 Температура", "🌡 Warmth"), cur.warm, -1f, 1f, { pct(it) }) { v, _ -> cur = cur.copy(warm = v); previewColor(cur) }
            row(
                btn(ctx, t("↺ Сбросить", "↺ Reset"), style = BtnStyle.TEXT) {
                    cur = cur.copy(bright = 0f, contrast = 0f, sat = 0f, warm = 0f)
                    bars.forEach { it.progress = 500 }
                    previewColor(cur)
                },
                btn(ctx, t("Ко всем кускам", "To all pieces"), style = BtnStyle.TEXT) {
                    val tried = cur
                    commitClips(clips.map { it.copy(bright = tried.bright, contrast = tried.contrast, sat = tried.sat, warm = tried.warm) })
                    cur = clips.getOrNull(i) ?: tried
                    dismiss()
                }
            )
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    /** The transition into piece [k] (at the seam before it; [k] 0 — the very start of the video). */
    private fun transitionSheet(k: Int) {
        val c = clips.getOrNull(k) ?: return
        pause()
        val st = EditTrack.starts(clips)[k]
        bottomSheet(t("⇆ Переход", "⇆ Transition")) { dismiss ->
            muted(if (k == 0) t("Начало ролика: картинка появляется из цвета. Чтобы поставить переход между кусками, сначала разрежьте ролик (✂).",
                "The start of the video: the picture comes in from a colour. To put a transition between pieces, split the video first (✂).")
            else t("Шов между кусками $k и ${k + 1} (${StudioEdit.clock(st)}). Тап по белому квадратику на шве таймлайна открывает это же окно.",
                "The seam between pieces $k and ${k + 1} (${StudioEdit.clock(st)}). A tap on the white square at a seam of the timeline opens this too."))
            label(this, t("Вид", "Kind"))
            add(choiceChips(ctx, EditColor.TRANSITIONS.map { it to EditColor.transName(it) }, c.trans) { v -> clipSet(k) { it.copy(trans = v) } })
            label(this, t("Длительность", "Length"))
            add(choiceChips(ctx, EditColor.TRANS_MS.map { it to StudioEdit.secs(it) + t(" с", " s") }, c.transMs.takeIf { it in EditColor.TRANS_MS } ?: EditColor.DEFAULT_TRANS_MS) { v ->
                clipSet(k) { it.copy(transMs = v) }
            })
            row(
                btn(ctx, t("▶ Посмотреть", "▶ Preview"), style = BtnStyle.TONAL) {
                    dismiss()
                    seekTo(max(0L, st - 1200))
                    play()
                },
                btn(ctx, t("Ко всем швам", "To all seams"), style = BtnStyle.TEXT) {
                    val now = clips.getOrNull(k) ?: return@btn
                    if (clips.size < 2) return@btn toast(t("В ролике один кусок — швов нет", "One piece — no seams"))
                    commitClips(clips.mapIndexed { j, it -> if (j == 0) it else it.copy(trans = now.trans, transMs = now.transMs) })
                    dismiss()
                    toast(t("Переход на всех швах", "Transition on all seams"))
                }
            )
        }
    }

    /** The format of the frame (Shorts, YouTube, square…), the video fitted or filling it, the background colour. */
    private fun canvasSheet() {
        val p = project ?: return
        pause()
        bottomSheet(t("▭ Формат кадра", "▭ Frame format")) { dismiss ->
            muted(t("Форма готового ролика. Видео вписывается целиком на фон или заполняет кадр — тогда края обрезаются. Надписи и схемы остаются на своих местах.",
                "The shape of the exported video. The video fits whole on a background or fills the frame, cutting the edges. Captions and schemes keep their places."))
            label(this, t("Соотношение сторон", "Aspect ratio"))
            add(choiceChips(ctx, EditCanvas.RATIOS.map { it to EditCanvas.ratioName(it) }, p.ratio) { v -> setCanvas { it.copy(ratio = v) } })
            label(this, t("Видео в кадре", "Video in the frame"))
            add(choiceChips(ctx, listOf(false to t("Вписать целиком", "Fit whole"), true to t("Заполнить кадр", "Fill the frame")), p.fill) { v -> setCanvas { it.copy(fill = v) } })
            label(this, t("Фон", "Background"))
            check(t("Размытое видео вместо цвета", "Blurred video instead of a colour"), p.blur) { on -> setCanvas { it.copy(blur = on) } }
            val sw = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val dots = ArrayList<Pair<Int, View>>()
            fun mark(sel: Int) = dots.forEach { (col, v) -> v.background = roundBg(col, 18f, ctx, if (col == sel) Palette.ACCENT else Palette.OUTLINE) }
            for (col in EditCanvas.BACKGROUNDS) {
                val d = View(ctx).apply {
                    contentDescription = t("Цвет фона", "Background colour")
                    setOnClickListener {
                        setCanvas { it.copy(bg = col) }
                        mark(col)
                    }
                }
                sw.addView(d, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
                dots += col to d
            }
            mark(p.bg)
            add(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(sw)
            }, 8)
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    /** A change of the canvas: one step of undo, saved, the preview reshaped. */
    private fun setCanvas(f: (EProject) -> EProject) {
        val p = project ?: return
        val n = f(p)
        if (n == p) return
        history.push(snapshot())
        project = n
        save()
        refreshUndo()
        applyCanvas()
    }

    private fun applyCanvas() {
        val p = project ?: return
        frameView?.setBackgroundColor(p.bg or 0xFF000000.toInt())
        stage?.portrait = EditCanvas.frame(p).let { (w, h) -> h > w * 1.1f }
        val box = previewBox
        val fr = frameView
        if (box != null && fr != null) fitFrame(box, fr)
        splitKey = ""
        layoutSplit()
        stage?.refresh()
        refreshRatio()
        tools()
    }

    private fun allVolumeSheet() {
        val heard = clips.filter { !it.isHold }
        val now = heard.map { (it.volume * 100).roundToInt() }.distinct().singleOrNull() ?: -1
        bottomSheet(t("🔊 Громкость видео", "🔊 Video volume")) { dismiss ->
            muted(t("Для всех кусков сразу. Громкость одного куска — тап по нему на таймлайне. Больше 100 % слышно только в готовом ролике.",
                "For all pieces at once. One piece's volume — tap it on the timeline. Above 100 % is heard in the exported video only."))
            add(choiceChips(ctx, EditTrack.VOLUMES.map { it to "$it %" }, now) { v ->
                commitClips(clips.map { if (it.isHold) it else it.copy(volume = v / 100f) })
                dismiss()
            })
        }
    }

    private var clipAtEnd = true
    // voice-over: the microphone records while the video plays from the playhead
    private var recorder: StudioRecorder? = null
    private var recording = false
    private var recMute = true

    private fun voiceSheet() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 3) }
            return toast(t("Разрешите микрофон и нажмите «🎙 Озвучка» ещё раз", "Allow the microphone and tap \"🎙 Voice-over\" again"))
        }
        if (project == null) return
        pause()
        val at0 = curMs
        var keep = true
        recMute = Lumen.prefs.getBoolean("studio_rec_mute", true)
        bottomSheet(t("🎙 Озвучка", "🎙 Voice-over"), onDismiss = { finishVoice(at0, keep) }) { dismiss ->
            muted(t("Видео играет с ${StudioEdit.clock(at0)}, вы говорите в микрофон. «⏹ Готово» — запись встанет звуковой дорожкой с этого места. Лучше в наушниках.",
                "The video plays from ${StudioEdit.clock(at0)} while you speak. \"⏹ Done\" puts the recording in as a sound track from there. Headphones work best."))
            check(t("Без звука видео во время записи", "Mute the video while recording"), recMute) {
                recMute = it
                Lumen.prefs.edit().putBoolean("studio_rec_mute", it).apply()
            }
            val level = text(ctx, "", 18f, bold = true, color = Palette.ACCENT).apply { typeface = Typeface.MONOSPACE }
            add(level, 12)
            lateinit var go: android.widget.Button
            go = btn(ctx, t("● Начать запись", "● Start recording"), primary = true) {
                if (recorder == null) {
                    val rec = StudioRecorder { lvl, ms ->
                        val bars = (lvl.coerceIn(0f, 1f) * 12).roundToInt()
                        level.text = "● " + StudioEdit.clock(ms) + "  " + "▮".repeat(bars) + "▯".repeat(12 - bars)
                    }
                    try {
                        rec.start()
                    } catch (e: Exception) {
                        return@btn toast(t("Микрофон недоступен: ", "The microphone is unavailable: ") + e.message)
                    }
                    recorder = rec
                    recording = true
                    play()
                    go.text = t("⏹ Готово", "⏹ Done")
                } else dismiss()
            }
            add(go, 8)
            button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) {
                keep = false
                dismiss()
            }
        }
    }

    /** The end of a voice-over: the recording as a sound block from [at] (or thrown away). */
    private fun finishVoice(at: Long, keep: Boolean) {
        val rec = recorder ?: return
        recorder = null
        recording = false
        val pcm = runCatching { rec.stop() }.getOrDefault(ShortArray(0))
        pause()
        val rate = rec.rate
        if (!keep || pcm.size < rate / 4) return
        val p = project ?: return
        val d = dir
        Lumen.async({
            val name = "img/r${System.currentTimeMillis()}.wav"
            val f = File(d, name).apply { parentFile?.mkdirs() }
            val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            data.asShortBuffer().put(pcm)
            f.outputStream().use {
                it.write(Studio.wavHeader(pcm.size * 2, rate))
                it.write(data.array())
            }
            name
        }, { e -> if (!isDestroyed) err(e) }) { file ->
            if (project?.id != p.id) return@async
            val len = Studio.durationMs(pcm.size, rate)
            addNew(EditMusic.KIND, at, max(StudioEdit.MIN_MS, len), 1f) {
                it.copy(file = file, text = t("🎙 Озвучка", "🎙 Voice-over"), x = 0.5f, y = 0.5f, w = 1f, animIn = "none", animOut = "none")
            }
            musicFile = ""
            toast(t("Озвучка добавлена", "Voice-over added"))
        }
    }
    /** Speech recognition for subtitles: its progress line (null = not running) and the stop flag. */
    private var recogMsg: String? = null
    private var recogStop = false

    /** Auto subtitles: the voice as captions of a few words, in one style, all at once (one step of undo). */
    private fun subsSheet() {
        val p = project ?: return
        pause()
        val prefs = Lumen.prefs
        var size = prefs.getInt("studio_subs_words", 3).takeIf { it in EditCaptions.SIZES } ?: 3
        var look = prefs.getString("studio_subs_look", "plate").takeIf { it in StudioEdit.TEXT_LOOKS } ?: "plate"
        var place = prefs.getInt("studio_subs_place", 0).coerceIn(0, EditCaptions.PLACES.lastIndex)
        var anim = prefs.getString("studio_subs_anim", "pop").takeIf { it in StudioEdit.ANIMS } ?: "pop"
        val has = blocks.count { EditCaptions.isSub(it) }
        bottomSheet(t("💬 Автосубтитры", "💬 Auto subtitles")) { dismiss ->
            muted(if (words.isEmpty()) t("Речь ролика ещё не распознана: распознаю на телефоне (Android 13+), затем поставлю субтитры.",
                "The speech is not recognised yet: it is recognised on the phone (Android 13+), then the subtitles are put in.")
            else t("Речь уже есть (${words.size} слов): субтитры встанут точно по словам. Каждый — обычная надпись, её можно править.",
                "The speech is there (${words.size} words): subtitles follow the words exactly. Each one is an ordinary caption you can edit."))
            label(this, t("Слов в строке", "Words per caption"))
            add(choiceChips(ctx, listOf(1 to t("1 — крупно, как в TikTok", "1 — big, TikTok style"), 3 to t("до 3", "up to 3"), 6 to t("до 6", "up to 6")), size) { size = it })
            label(this, t("Стиль", "Style"))
            add(choiceChips(ctx, StudioEdit.TEXT_LOOKS.map { it to lookName(it) }, look) { look = it })
            label(this, t("Где", "Where"))
            add(choiceChips(ctx, listOf(0 to t("Внизу", "Bottom"), 1 to t("По центру", "Middle"), 2 to t("Вверху", "Top")), place) { place = it })
            label(this, t("Появление", "Entrance"))
            add(choiceChips(ctx, listOf("pop" to animName("pop"), "fade" to animName("fade"), "none" to animName("none")), anim) { anim = it })
            button(if (has > 0) t("Пересоздать субтитры", "Re-make the subtitles") else t("Создать субтитры", "Make subtitles"), primary = true) {
                prefs.edit().putInt("studio_subs_words", size).putString("studio_subs_look", look).putInt("studio_subs_place", place)
                    .putString("studio_subs_anim", anim).apply()
                dismiss()
                val go = { makeSubs(size, look, EditCaptions.PLACES[place], anim) }
                if (words.isEmpty()) recognizeSpeech(p, go) else go()
            }
            if (has > 0) button(t("Убрать субтитры ($has)", "Remove the subtitles ($has)"), style = BtnStyle.TEXT) {
                dismiss()
                commit(blocks.filterNot { EditCaptions.isSub(it) }, null)
                toast(t("Субтитры убраны", "Subtitles removed"))
            }
            if (words.isNotEmpty()) button(t("🎙 Распознать речь заново", "🎙 Recognise the speech again"), style = BtnStyle.TEXT) {
                dismiss()
                recognizeSpeech(p) { toast(t("Речь обновлена: создайте субтитры", "Speech updated: make the subtitles")) }
            }
        }
    }

    private fun makeSubs(size: Int, look: String, y: Float, anim: String) {
        val p = project ?: return
        val cues = EditCaptions.cues(words, size)
        if (cues.isEmpty()) return toast(t("В ролике не нашлось слов", "No words in the video"))
        val (fw, fh) = EditCanvas.frame(p)
        val portrait = fh >= fw
        val list = blocks.filterNot { EditCaptions.isSub(it) }.toMutableList()
        var next = (list.mapNotNull { it.id.removePrefix("b").toIntOrNull() }.maxOrNull() ?: 0) + 1
        for (c in cues) {
            val text = if (size == 1) c.text.uppercase() else c.text
            val ratio = StudioEdit.textRatio(text)
            val w = StudioEdit.fitWidth(StudioEdit.defaultWidth("text", portrait), ratio, fw, fh)
            list += StudioEdit.clampTime(EBlock("b" + next++, "text", c.startMs, max(StudioEdit.MIN_MS, c.endMs - c.startMs), x = 0.5f, y = y, w = w,
                ratio = ratio, animIn = anim, animOut = "none", look = look, text = text, ask = EditCaptions.TAG), total)
        }
        commit(list, null)
        stage?.refresh()
        toast(t("Субтитров: ${cues.size}", "Subtitles: ${cues.size}"))
    }

    /** The voice of the base video recognised on the phone: words saved with the project, then [then]. */
    private fun recognizeSpeech(p: EProject, then: () -> Unit) {
        if (!StudioRecognizer.available(this)) return toast(t("Распознавание речи нужно Android 13+ с сервисом распознавания", "Speech recognition needs Android 13+ with a recognition service"))
        if (recogMsg != null) return toast(t("Распознавание уже идёт", "Recognition is already running"))
        val base = File(dir, StudioEditStore.BASE)
        val wordsFile = File(dir, StudioEditStore.WORDS)
        recogStop = false
        recogMsg = t("Читаю звук…", "Reading the sound…")
        watch()
        fun end(msg: String?) {
            recogMsg = null
            if (isDestroyed) return
            watch()
            msg?.let { toast(it) }
        }
        Lumen.async({ StudioAudio.decode(base) }, { e -> end(e.message ?: t("Звук не читается", "The sound cannot be read")) }) { pcm ->
            val speech = Studio.speechSpans(pcm.data, pcm.rate)
            if (speech.isEmpty()) return@async end(t("В ролике не слышно речи", "No speech is heard in the video"))
            StudioRecognizer.recognize(this, pcm.data, pcm.rate, Studio.groupSpans(speech), Studio.recogLang(lang()),
                { i, n ->
                    recogMsg = t("Распознавание речи $i/$n", "Recognising speech $i/$n")
                    watch()
                }, { recogStop || isDestroyed },
                { msg ->
                    recogMsg = msg
                    watch()
                }) { res ->
                if (recogStop) return@recognize end(t("Распознавание остановлено", "Recognition stopped"))
                val w = Studio.alignPieces(res, speech)
                if (w.isEmpty()) return@recognize end(t("Речь не распозналась", "The speech was not recognised"))
                runCatching { wordsFile.writeText(StudioMix.wordsJson(w)) }
                end(null)
                if (project?.id != p.id) return@recognize
                srcWords = w
                words = EditTrack.words(w, clips, p.durMs)
                timeline?.words = words
                then()
            }
        }
    }

    /** Another video into the main track: at the end or at the playhead (the piece there is cut in two). */
    private fun addClipSheet() {
        pause()
        bottomSheet(t("🎞 Добавить видео", "🎞 Add a video")) { dismiss ->
            muted(t("Клип с телефона станет куском основной дорожки: его можно резать, ускорять, красить и переставлять как остальные.",
                "A clip from the phone becomes a piece of the main track: cut, speed up, colour and reorder it like the others."))
            button(t("В конец ролика", "At the end"), primary = true) {
                dismiss()
                pickClip(true)
            }
            button(t("У курсора · ", "At the playhead · ") + StudioEdit.clock(curMs), style = BtnStyle.TONAL) {
                dismiss()
                pickClip(false)
            }
        }
    }

    private fun pickClip(atEnd: Boolean) {
        clipAtEnd = atEnd
        pickAt = curMs
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_CLIP)
    }

    /** A clip from the phone: copied into the project, its whole length put into the track. */
    private fun importClip(uri: Uri) {
        val p = project ?: return
        val atEnd = clipAtEnd
        val at = pickAt
        val d = dir
        val cr = applicationContext.contentResolver
        toast(t("Копирую видео…", "Copying the video…"))
        Lumen.async({
            val name = "img/c${System.currentTimeMillis()}.mp4"
            val f = File(d, name).apply { parentFile?.mkdirs() }
            (cr.openInputStream(uri) ?: error(t("Файл не открывается", "The file cannot be opened"))).use { i -> f.outputStream().use { i.copyTo(it) } }
            val pr = runCatching { StudioEditRender.probe(f) }.getOrElse {
                f.delete()
                throw it
            }
            name to pr
        }, { e -> if (!isDestroyed) err(e) }) { (file, pr) ->
            if (project?.id != p.id) return@async
            sideSizes[file] = pr.w to pr.h
            val (list, j) = EditTrack.insert(clips, at, VClip("n", 0, max(EditTrack.MIN_MS + 1, pr.durMs), src = file), atEnd)
            commitClips(list, j)
            project?.let { loadThumbs(it, file) }
            val st = EditTrack.starts(clips).getOrNull(j) ?: 0L
            seekTo(st)
            timeline?.setPlayhead(st)
            toast(t("Видео добавлено: кусок ${j + 1}", "Video added: piece ${j + 1}"))
        }
    }

    private fun pickAudio(replace: String?) {
        pause()
        pickFor = replace
        pickAt = curMs
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_AUDIO)
    }

    /** Music or a sound from the phone: copied into the project, put at the playhead for its length (or to the end). */
    private fun importAudio(uri: Uri) {
        val p = project ?: return
        val replace = pickFor
        val at = pickAt
        val d = dir
        val cr = applicationContext.contentResolver
        toast(t("Копирую звук…", "Copying the sound…"))
        Lumen.async({
            val title = runCatching {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull().orEmpty()
            val ext = title.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all { ch -> ch.isLetterOrDigit() } } ?: "m4a"
            val name = "img/a${System.currentTimeMillis()}.$ext"
            val f = File(d, name).apply { parentFile?.mkdirs() }
            (cr.openInputStream(uri) ?: error(t("Файл не открывается", "The file cannot be opened"))).use { i -> f.outputStream().use { i.copyTo(it) } }
            val r = MediaMetadataRetriever()
            val dur = try {
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                runCatching { r.release() }
            }
            if (dur <= 0) {
                f.delete()
                error(t("Это не звук или формат не поддерживается", "Not a sound, or the format is not supported"))
            }
            Triple(name, dur, title.substringBeforeLast('.').take(60))
        }, { e -> if (!isDestroyed) err(e) }) { (file, dur, title) ->
            if (project?.id != p.id) return@async
            if (replace != null) change(replace) { it.copy(file = file, srcMs = 0, text = title) }
            else {
                val len = min(dur, max(StudioEdit.MIN_MS, outLen(p) - at)).coerceAtLeast(StudioEdit.MIN_MS)
                addNew(EditMusic.KIND, at, len, 1f) {
                    it.copy(file = file, text = title, x = 0.5f, y = 0.5f, w = 1f, animIn = "none", animOut = "fade")
                }
            }
            musicFile = ""
            syncMusic()
        }
    }

    private fun musicSheet(b: EBlock) {
        bottomSheet(t("🔊 Громкость музыки", "🔊 Music volume")) { dismiss ->
            label(this, t("Музыка", "Music"))
            add(choiceChips(ctx, EditTrack.VOLUMES.map { it to "$it %" }, (b.volume * 100).roundToInt()) { v -> change(b.id) { it.copy(volume = v / 100f) } })
            label(this, t("Звук видео под музыкой", "Video sound under the music"))
            add(choiceChips(ctx, listOf(0, 25, 50, 75, 100).map { it to "$it %" }, (b.mainVolume * 100).roundToInt()) { v -> change(b.id) { it.copy(mainVolume = v / 100f) } })
            muted(t("Фоновая музыка под голос: музыка 25–50 %, видео 100 %. Больше 100 % слышно только в готовом ролике.",
                "Background music under a voice: music 25–50 %, video 100 %. Above 100 % is heard in the exported video only."))
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    private fun musicFadeSheet(b: EBlock) {
        bottomSheet(t("〰 Затухание", "〰 Fade")) { dismiss ->
            muted(t("Музыка плавно нарастает в начале и стихает в конце (до ${EditMusic.FADE_MS / 1000.0} с).",
                "The music rises at the start and dies away at the end (up to ${EditMusic.FADE_MS / 1000.0} s)."))
            check(t("Плавное начало", "Fade in"), b.animIn == "fade") { on -> change(b.id) { it.copy(animIn = if (on) "fade" else "none") } }
            check(t("Плавный конец", "Fade out"), b.animOut == "fade") { on -> change(b.id) { it.copy(animOut = if (on) "fade" else "none") } }
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    private fun musicFrom(b: EBlock) {
        pause()
        prompt(t("С какого момента трека", "Track from"), t("Секунды или 0:12.5", "Seconds or 0:12.5"), StudioEdit.clock(b.srcMs)) { v ->
            StudioEdit.parseTime(v)?.let { ms -> change(b.id) { it.copy(srcMs = max(0L, ms)) } } ?: toast(t("Не понял время", "Not a time"))
        }
    }

    /** Music on the preview: the top sound block under the playhead plays along at its loudness and fades. */
    private fun syncMusic() {
        val m = EditMusic.active(blocks, curMs).lastOrNull()
        if (m == null) {
            player3?.takeIf { it.playWhenReady }?.pause()
            return
        }
        val ex3 = player3 ?: ExoPlayer.Builder(this).build().also { player3 = it }
        if (musicFile != m.file) {
            musicFile = m.file
            ex3.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(dir, m.file))))
            ex3.prepare()
            musicSeekAt = 0L
        }
        val g = if (recording && recMute) 0f else EditMusic.gain(m, curMs).coerceIn(0f, 1f)
        if (ex3.volume != g) ex3.volume = g
        val want = m.srcMs + (curMs - m.startMs).coerceAtLeast(0)
        val now = SystemClock.uptimeMillis()
        if (playing) {
            if (!ex3.playWhenReady) {
                ex3.seekTo(want)
                ex3.play()
            } else if (kotlin.math.abs(ex3.currentPosition - want) > 300 && now - musicSeekAt > 500) {
                musicSeekAt = now
                ex3.seekTo(want)
            }
        } else {
            if (ex3.playWhenReady) ex3.pause()
            if (kotlin.math.abs(ex3.currentPosition - want) > 60 && now - musicSeekAt > 120) {
                musicSeekAt = now
                ex3.seekTo(want)
            }
        }
    }

    // ---------- export ----------

    /** Quality and a word on how the export works, then the export. */
    private fun exportSheet() {
        val p = project ?: return
        if (BgTasks.has(TASK_EXPORT)) return toast(t("Экспорт уже идёт", "The export is already running"))
        val track = EditTrack.of(p)
        val cut = !EditTrack.plain(track, p.durMs) || EditCanvas.reshaped(p) || p.fill
        val rebuilt = !EditTrack.soundPlain(track, p.durMs) || EditSplit.mixes(p.blocks) || EditMusic.mixes(p.blocks)
        if (!cut && !rebuilt && p.blocks.none { !it.pending })
            return toast(t("Пока нечего сохранять: добавьте надпись, музыку, фильтр или разрежьте ролик (✂).", "Nothing to save yet: add a caption, music, a filter or split the video (✂)."))
        pause()
        val stale = anims.stale(p)
        if (stale.isNotEmpty() && !staleOk) return confirm(t("Анимации не перерисованы", "Animations are not rendered"),
            t("${stale.size} анимац. ещё рисуются или не подходят к новой длине/размеру. Перерисовать сейчас (экран должен быть открыт) или экспортировать как есть — растянутыми, а без кадров — без них?",
                "${stale.size} animation(s) are still rendering or do not fit the new length/size. Re-render now (keep the screen open) or export as they are — stretched, and without frames — left out?"),
            t("Экспорт как есть", "Export as is")) {
            staleOk = true
            exportSheet()
            staleOk = false
        }.also { stale.forEach { anims.enqueue(it.id) } }
        val prefs = Lumen.prefs
        var side = prefs.getInt("studio_edit_side", 1920)
        val (ow, oh) = EditCanvas.outSize(p.w, p.h, p.ratio, 1920)
        bottomSheet(t("⬆ Экспорт ролика", "⬆ Export the video")) { dismiss ->
            val len = StudioEdit.clock(outLen(p)).substringBefore('.')
            val pieces = EditTrack.of(p).size
            muted(t("Слоёв: ${p.blocks.size} · кусков: $pieces · длина $len · кадр $ow×$oh", "Layers: ${p.blocks.size} · pieces: $pieces · length $len · frame $ow×$oh") + " · " +
                if (rebuilt) t("звук собирается заново (куски, музыка, второе видео).", "the sound is rebuilt (pieces, music, second video).")
                else t("звук оригинала копируется без потерь.", "the original sound is copied without loss."))
            label(this, t("Качество", "Quality"))
            add(choiceChips(ctx, listOf(1920 to t("1080p · как оригинал", "1080p · as the original"), 1280 to t("720p — быстрее и легче", "720p — faster and smaller")), side) { side = it })
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

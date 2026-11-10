/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lumen Studio "Mix": find YouTube videos, mark pieces of them, download only those seconds, shuffle the pieces
 * (two pieces of one clip never in a row), edit the storyboard and build the video — as it is, with a voice-over
 * recorded over it, with info-schemes and/or subtitles. A video with a voice-over can go to an AI app with a
 * ready editing prompt. The frame (9:16 or 16:9) is chosen at the start of every mix.
 */
class StudioMixActivity : LumenBase() {
    companion object {
        private const val TASK = "studiomix:"
        private const val REQ_MIC = 7431
        private const val REQ_SAVE = 7432
        private const val FILE_PREFIX = "Lumen_Mix_"
        private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val thumbPool = Executors.newSingleThreadExecutor()
        /** Video and voice-over lengths closer than this count as equal. */
        private const val FIT_TOL_MS = 300L

        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, StudioMixActivity::class.java))

        /** Adds a YouTube video to the mix clips from elsewhere (e.g. a project video); false if it is there already or the mix is full. */
        fun addVideo(ctx: Context, url: String, title: String): Boolean {
            val f = File(ctx.filesDir, "lumen/studio/mix/clips.json").apply { parentFile?.mkdirs() }
            val clips = if (f.exists()) StudioCuts.fromJson(f.readText()) else emptyList()
            val list = StudioCuts.addHits(clips, listOf(CutHit(url, title)))
            if (list.size == clips.size) return false
            f.writeText(StudioCuts.toJson(list))
            return true
        }
    }

    /** Settings of the mix kept between visits. */
    private object Prefs {
        private val p get() = Lumen.prefs
        /** Frame: [StudioAspect.id] of 9:16 or 16:9; empty = not chosen yet (a new mix). */
        var format: String
            get() = p.getString("studio_mix_format", "") ?: ""
            set(v) = p.edit().putString("studio_mix_format", v).apply()
        var pieceMs: Long
            get() = p.getLong("studio_mix_piece", StudioMix.DEFAULT_PIECE_MS)
            set(v) = p.edit().putLong("studio_mix_piece", v).apply()
        var onlyMarked: Boolean
            get() = p.getBoolean("studio_mix_only_marked", false)
            set(v) = p.edit().putBoolean("studio_mix_only_marked", v).apply()
        var subs: Boolean
            get() = p.getBoolean("studio_mix_subs", false)
            set(v) = p.edit().putBoolean("studio_mix_subs", v).apply()
        /** What goes over the video: subtitles or info-schemes with options ([StudioOverlay]); null = not chosen yet. */
        var over: StudioOverlay?
            get() = StudioOverlay.parse(p.getString("studio_mix_over", null))
            set(v) = p.edit().putString("studio_mix_over", v?.toJson()?.toString()).apply()
        /** Storyboard the AI was last asked to compose schemes for (it is not asked again by itself). */
        var schemesTried: String
            get() = p.getString("studio_mix_schemes_tried", "") ?: ""
            set(v) = p.edit().putString("studio_mix_schemes_tried", v).apply()
        var seed: Long
            get() = p.getLong("studio_mix_seed", 1)
            set(v) = p.edit().putLong("studio_mix_seed", v).apply()
        /** Where the clips are cut: [MixCut.EVEN] or [MixCut.RANDOM] (own moments are set per clip). */
        var mode: MixCut
            get() = MixCut.of(p.getString("studio_mix_mode", "")) ?: MixCut.EVEN
            set(v) = p.edit().putString("studio_mix_mode", v.id).apply()
        /** Seed of the random moments: a new one cuts the clips at other places. */
        var cutSeed: Long
            get() = p.getLong("studio_mix_cut_seed", 1)
            set(v) = p.edit().putLong("studio_mix_cut_seed", v).apply()
        /** The last built video, what it showed ([StudioMix.signature]) and whether it had the voice-over. */
        var video: String
            get() = p.getString("studio_mix_video", "") ?: ""
            set(v) = p.edit().putString("studio_mix_video", v).apply()
        var videoSig: String
            get() = p.getString("studio_mix_video_sig", "") ?: ""
            set(v) = p.edit().putString("studio_mix_video_sig", v).apply()
        var videoVoice: Boolean
            get() = p.getBoolean("studio_mix_video_voice", false)
            set(v) = p.edit().putBoolean("studio_mix_video_voice", v).apply()
        var savedAs: String
            get() = p.getString("studio_mix_saved", "") ?: ""
            set(v) = p.edit().putString("studio_mix_saved", v).apply()
        /** Length of the video set before cutting (0 = as the pieces come); the voice-over, when there is one, sets it instead. */
        var targetMs: Long
            get() = p.getLong("studio_mix_target", 0)
            set(v) = p.edit().putLong("studio_mix_target", v).apply()
        /** What fills the set length when the pieces are not enough: new pieces (default) or stretching and repeats. */
        var fill: MixFill
            get() = MixFill.of(p.getString("studio_mix_fill", null))
            set(v) = p.edit().putString("studio_mix_fill", v.id).apply()
        /** Settings of the AI search of moments (⚙ in the assistant): what the app does with the timecodes. */
        var bounds: StudioMixAssist.Bounds
            get() = StudioMixAssist.Bounds(
                fit = p.getBoolean("studio_mix_b_fit", true),
                minMs = p.getLong("studio_mix_b_min", StudioMixAssist.MIN_MOMENT_MS),
                maxMs = p.getLong("studio_mix_b_max", StudioCuts.MAX_CLIP_MS),
                padMs = p.getLong("studio_mix_b_pad", 0),
                toPhrase = p.getBoolean("studio_mix_b_phrase", true),
                similar = StudioMixAssist.Similar.of(p.getString("studio_mix_b_similar", null)),
                batch = p.getInt("studio_mix_b_batch", StudioMixAssist.DEFAULT_BATCH),
                parallel = p.getInt("studio_mix_b_parallel", 1),
                fps = p.getFloat("studio_mix_b_fps", StudioMixAssist.DEFAULT_FPS.toFloat()).toDouble(),
                perVideo = p.getInt("studio_mix_b_per_video", 6))
            set(b) = p.edit().putBoolean("studio_mix_b_fit", b.fit).putLong("studio_mix_b_min", b.minMs).putLong("studio_mix_b_max", b.maxMs)
                .putLong("studio_mix_b_pad", b.padMs).putBoolean("studio_mix_b_phrase", b.toPhrase).putString("studio_mix_b_similar", b.similar.id)
                .putInt("studio_mix_b_batch", b.batch).putInt("studio_mix_b_parallel", b.parallel).putFloat("studio_mix_b_fps", b.fps.toFloat())
                .putInt("studio_mix_b_per_video", b.perVideo).apply()
        /** The assistant looks for videos on YouTube itself: videos per plan line (0 = only the given ones). */
        var autoVideos: Int
            get() = p.getInt("studio_mix_auto", 3)
            set(v) = p.edit().putInt("studio_mix_auto", v).apply()
        /** Longest video the assistant takes from the search, minutes. */
        var autoMaxMin: Int
            get() = p.getInt("studio_mix_auto_max", 20)
            set(v) = p.edit().putInt("studio_mix_auto_max", v).apply()
        /** Topic and wishes of the last "✨ Compose with AI" of the voice-over text. */
        var scriptTopic: String
            get() = p.getString("studio_mix_script_topic", "") ?: ""
            set(v) = p.edit().putString("studio_mix_script_topic", v).apply()
        var scriptWish: String
            get() = p.getString("studio_mix_script_wish", "") ?: ""
            set(v) = p.edit().putString("studio_mix_script_wish", v).apply()
        /** What the build that is running shows (taken over when it ends, also by a reopened screen). */
        var pending: String
            get() = p.getString("studio_mix_pending", "") ?: ""
            set(v) = p.edit().putString("studio_mix_pending", v).apply()
    }

    private val dir by lazy { File(filesDir, "lumen/studio/mix").apply { mkdirs() } }
    private val clipsFile get() = File(dir, "clips.json")
    private val clipsDir get() = File(dir, "clips").apply { mkdirs() }
    private val mixFile get() = File(dir, "mix.json")
    private val cutsFile get() = File(dir, "cuts.json")
    private val voiceFile get() = File(dir, "voice.wav")
    private val wordsFile get() = File(dir, "words.json")
    private val assistFile get() = File(dir, "assist.json")
    /** Text of the voice-over: shown on the teleprompter while recording. */
    private val scriptFile get() = File(dir, "script.txt")
    /** Parts of the videos taken before: "🎲 New pieces" does not take them again. */
    private val usedFile get() = File(dir, "used.json")

    private var clips: List<SClip> = emptyList()
    private var items: List<MixItem> = emptyList()
    private var words: List<SWord> = emptyList()
    /** Own cutting of clips, by id (see [ClipCut]). */
    private var cuts: Map<String, ClipCut> = emptyMap()
    private var assist = StudioMixAssist.State()
    /** Storyboard states for ↶ and ↷ (this visit only). */
    private val undoStack = ArrayDeque<List<MixItem>>()
    private val redoStack = ArrayDeque<List<MixItem>>()
    /** Storyboard grid and its scroll, kept over a re-render. */
    private var boardRv: RecyclerView? = null
    private var boardState: android.os.Parcelable? = null
    /** Actions for the ticked elements under the storyboard (refilled on every tick). */
    private var selBox: LinearLayout? = null
    /** Clips ticked in the list (for the range and removal). */
    private val clipMarks = LinkedHashSet<String>()

    /** Build with the recorded voice-over (on) or the picture only (off); asked only when there is a voice-over. */
    private var withVoice = true

    /** Running work of this screen: "dl", "schemes", "subs", "script" or "". */
    private var busy = ""
    @Volatile private var cancel = false
    /** Length the downloading new parts must fill (0 = no top-up running) and how many rounds it took. */
    private var topUpTarget = 0L
    private var topUpRound = 0
    private var status: Pair<TextView, ProgressBar>? = null
    private var renderBar: Pair<TextView, ProgressBar>? = null
    private var boardInfo: TextView? = null
    /** The built video waiting for a place picked by the user ("Save a copy"). */
    private var pendingCopy: File? = null
    /** Open the recording as soon as the video being built is ready. */
    private var recordAfter = false
    private var recorder: StudioRecorder? = null

    private val jobListener: () -> Unit = { onJob() }
    private val watcher: () -> Unit = {
        val task = BgTasks.find(TASK)
        if (task == null && (busy == "dl" || busy == "schemes" || busy == "script") && !isDestroyed) {
            busy = ""
            load()
            render()
        } else if (task != null && task.message.isNotBlank()) status?.first?.text = task.message
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        BgTasks.find(TASK)?.let { busy = it.key.removePrefix(TASK) }
        BgTasks.watchers += watcher
        render()
    }

    override fun onDestroy() {
        BgTasks.watchers -= watcher
        recorder?.stop()
        recorder = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        StudioJob.listener = jobListener
        if (!StudioJob.running) adoptResult()
        if (busy.isEmpty()) render()
    }

    override fun onStop() {
        if (StudioJob.listener === jobListener) StudioJob.listener = null
        super.onStop()
    }

    // ---------- files ----------

    private fun load() {
        clips = if (clipsFile.exists()) StudioCuts.fromJson(clipsFile.readText()) else emptyList()
        val ready = readyIds()
        items = if (mixFile.exists()) StudioMix.keepClips(StudioMix.fromJson(mixFile.readText()), ready) else emptyList()
        words = if (wordsFile.exists() && voiceFile.exists()) StudioMix.wordsFromJson(wordsFile.readText()) else emptyList()
        cuts = if (cutsFile.exists()) StudioMix.cutsFromJson(cutsFile.readText()) else emptyMap()
        assist = if (assistFile.exists()) StudioMixAssist.fromJson(assistFile.readText()) else StudioMixAssist.State()
        clipMarks.retainAll(clips.map { it.id }.toSet())
    }

    private fun saveClips(list: List<SClip>) {
        clips = list
        runCatching { clipsFile.writeText(StudioCuts.toJson(list)) }.onFailure { L.w("studio", "mix clips: ${it.message}") }
        val ready = readyIds()
        if (items.any { it.clipId !in ready }) saveItems(StudioMix.keepClips(items, ready))
    }

    private fun saveItems(list: List<MixItem>) {
        items = list
        runCatching { mixFile.writeText(StudioMix.toJson(list)) }.onFailure { L.w("studio", "mix: ${it.message}") }
    }

    private fun saveCuts(map: Map<String, ClipCut>) {
        cuts = map.filterValues { !it.isDefault || it.marks.isNotEmpty() }
        runCatching { cutsFile.writeText(StudioMix.cutsJson(cuts)) }.onFailure { L.w("studio", "mix cuts: ${it.message}") }
    }

    /** A hand change of the storyboard: kept for ↶, saved and shown; [msg] shows a snackbar with "Undo". */
    private fun change(list: List<MixItem>, msg: String? = null) {
        val next = StudioMix.unique(list)
        if (next == items) return
        pushUndo(items)
        saveItems(next)
        render()
        if (msg != null) snack(msg, t("Отменить", "Undo")) { undo() }
    }

    private fun pushUndo(state: List<MixItem>) {
        undoStack.addLast(state)
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(items)
        saveItems(prev)
        render()
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(items)
        saveItems(next)
        render()
    }

    /** A ready clip as the mix cuts it. */
    private fun specs(): List<MixSpec> = readyClips().map { MixSpec(it.id, it.lenMs, cuts[it.id] ?: ClipCut()) }

    /** The whole storyboard cut again by the current settings (hand edits are gone; ↶ brings them back). */
    private fun recut(msg: String?) {
        if (readyClips().isEmpty()) return
        change(fitted(StudioMix.build(specs(), Prefs.pieceMs, Prefs.mode, Prefs.cutSeed, Prefs.seed)), msg)
    }

    /** The storyboard made as long as set in "2. Cutting" (not when a voice-over sets the length). */
    private fun fitted(list: List<MixItem>): List<MixItem> =
        if (Prefs.targetMs <= 0 || voiceFile.exists() || list.isEmpty()) list
        else if (Prefs.fill == MixFill.PIECES) filled(list, Prefs.targetMs)
        else StudioMix.fitTo(list, Prefs.targetMs, clipCaps())

    /** [list] filled to [target] by whole pieces: unused places of the downloaded clips join, nothing is stretched. */
    private fun filled(list: List<MixItem>, target: Long): List<MixItem> =
        StudioMix.fillTo(list, target, StudioMix.spare(list, specs(), Prefs.pieceMs), Prefs.seed)

    /**
     * The storyboard filled to [target] by new pieces: first unused places of the clips, then, when they are not
     * enough, new parts of the same videos are downloaded (fresh moments) and cut — up to 3 rounds on its own.
     */
    private fun topUp(target: Long, round: Int = 1) {
        if (target <= 0 || readyClips().isEmpty()) return
        if (busy.isNotEmpty() || rendering()) return toast(t("Дождитесь конца задачи", "Wait for the task to end"))
        val next = filled(items, target)
        if (next != items) change(next)
        val lack = StudioMix.lackMs(next, target)
        if (lack <= FIT_TOL_MS) {
            topUpTarget = 0
            if (round > 1) toast(t("Ролик заполнен новыми кусками: ${StudioCuts.stamp(StudioMix.totalMs(next))}",
                "The video is filled with new pieces: ${StudioCuts.stamp(StudioMix.totalMs(next))}"))
            return
        }
        val used = if (usedFile.exists()) StudioMix.usedFromJson(usedFile.readText()) else emptyMap()
        val extra = StudioMix.extraParts(readyClips(), lack, StudioCuts.MAX_CLIPS - clips.size, used)
        if (extra.isEmpty()) {
            topUpTarget = 0
            return toast(t("Не хватает ${StudioCuts.stamp(lack)}: больше нечего добрать (видео закончились или ${StudioCuts.MAX_CLIPS} клипов). Добавьте видео в «1. Клипы»",
                "${StudioCuts.stamp(lack)} short: nothing more to take (the videos ended or ${StudioCuts.MAX_CLIPS} clips). Add videos in \"1. Clips\""))
        }
        topUpTarget = target
        topUpRound = round
        saveClips(clips + extra)
        toast(t("Не хватает ${StudioCuts.stamp(lack)}: качаю новые отрезки (${extra.size}) из тех же видео…",
            "${StudioCuts.stamp(lack)} short: downloading new parts (${extra.size}) of the same videos…"))
        download(extra.map { it.id }.toSet())
    }

    /** After a cut to the set length: what the downloads lack is taken from the videos on its own. */
    private fun autoTopUp() {
        val target = Prefs.targetMs
        if (target > 0 && Prefs.fill == MixFill.PIECES && !voiceFile.exists() && items.isNotEmpty() &&
            StudioMix.lackMs(items, target) > FIT_TOL_MS) topUp(target)
    }

    /** Pieces of clip [id] cut again by its settings and put back at random places. */
    private fun recutClip(id: String): List<MixItem> {
        val spec = specs().firstOrNull { it.id == id } ?: return items
        return StudioMix.replaceClip(items, id, StudioMix.cut(spec, Prefs.pieceMs, Prefs.mode, Prefs.cutSeed), System.nanoTime())
    }

    private fun cutLabel(c: ClipCut): String = listOfNotNull(
        when (c.cut) {
            MixCut.EVEN -> t("▦ ровно", "▦ even")
            MixCut.RANDOM -> t("🎲 случайно", "🎲 random")
            MixCut.MARKS -> t("✂ свои моменты: ${c.marks.size}", "✂ own moments: ${c.marks.size}")
            null -> null
        },
        c.pieceMs.takeIf { it > 0 }?.let { StudioMix.pieceLabel(it) }
    ).joinToString(" · ")

    private fun readyClips(): List<SClip> = clips.filter { it.file.isNotEmpty() && it.lenMs > 0 && File(clipsDir, it.file).exists() }

    private fun readyIds(): Set<String> = readyClips().map { it.id }.toSet()

    private fun aspect() = StudioAspect.of(Prefs.format)

    private fun lang() = Studio.voiceLang(StudioPrefs.voiceLang, null, Settings.lang)

    private fun notes(): Map<String, String> = clips.associate { it.id to it.note.ifBlank { it.title } }

    private fun lastVideo(): File? = Prefs.video.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }

    /** Starts a new mix: everything is cleared and the frame is asked again. */
    private fun newMix() {
        if (busy.isNotEmpty() || StudioJob.running && Prefs.pending.isNotEmpty()) {
            toast(t("Дождитесь конца задачи", "Wait for the task to end"))
            return
        }
        confirm(t("Новый микс?", "New mix?"), t("Клипы, раскадровка и озвучка этого микса удалятся. Готовые видео остаются в галерее.",
            "The clips, storyboard and voice-over of this mix are deleted. Built videos stay in the gallery."), t("Начать", "Start")) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            Prefs.format = ""
            Prefs.subs = false
            Prefs.video = ""
            Prefs.videoSig = ""
            Prefs.savedAs = ""
            clipMarks.clear()
            undoStack.clear()
            redoStack.clear()
            load()
            render()
        }
    }

    // ---------- layout ----------

    private fun render() {
        status = null
        renderBar = null
        boardInfo = null
        boardState = boardRv?.layoutManager?.onSaveInstanceState() ?: boardState
        boardRv = null
        selBox = null
        val c = Col(this)
        c.add(StudioActivity.tabs(this, StudioActivity.MIX_TAB) { i ->
            if (i == StudioActivity.TASKS_TAB) return@tabs BoardActivity.open(this)
            if (i == StudioActivity.AUDIO_TAB) return@tabs AudioEditorActivity.open(this)
            StudioActivity.openTask(this, StudioTask.entries[i])
            finish()
        }, 0)
        if (Prefs.format.isEmpty()) {
            formatCard(c)
            page(t("🔀 Микс", "🔀 Mix"), c.root)
            return
        }
        c.row(
            chip(this, (if (aspect() == StudioAspect.PORTRAIT) "📱 9:16 ⇄" else "🖥 16:9 ⇄"), true) { switchFormat() }.apply {
                contentDescription = t("Формат кадра, нажмите, чтобы сменить", "Frame format, tap to switch")
            },
            btn(this, t("＋ Новый микс", "＋ New mix"), style = BtnStyle.TONAL) { newMix() }
        )
        clipsCard(c)
        if (readyClips().isNotEmpty()) mixCard(c)
        if (items.isNotEmpty()) {
            boardCard(c)
            finalCard(c)
        }
        page(t("🔀 Микс", "🔀 Mix"), c.root, keepScroll = true)
    }

    /** 9:16 ⇄ 16:9: the clips stay, only the frame of the storyboard and of the next build changes. */
    private fun switchFormat() {
        if (busy.isNotEmpty() || rendering()) return toast(t("Дождитесь конца задачи", "Wait for the task to end"))
        Prefs.format = if (aspect() == StudioAspect.PORTRAIT) StudioAspect.LANDSCAPE.id else StudioAspect.PORTRAIT.id
        boardState = null
        render()
        toast(if (aspect() == StudioAspect.PORTRAIT) t("Формат 9:16 (Shorts / Stories)", "Format 9:16 (Shorts / Stories)")
        else t("Формат 16:9 (обычное видео)", "Format 16:9 (regular video)"))
    }

    private fun formatCard(c: Col) = c.card {
        add(text(ctx, t("Формат ролика", "Video format"), Type.TITLE_M, bold = true), 0)
        muted(t("Выберите кадр для этого микса: под него скачиваются клипы и собирается видео.",
            "Pick the frame of this mix: the clips are downloaded and the video is built for it."))
        button(t("📱 Shorts YouTube / Stories Instagram (9:16)", "📱 YouTube Shorts / Instagram Stories (9:16)"), primary = true) {
            Prefs.format = StudioAspect.PORTRAIT.id
            render()
        }
        button(t("🖥 Обычное горизонтальное видео (16:9)", "🖥 Regular horizontal video (16:9)"), style = BtnStyle.TONAL) {
            Prefs.format = StudioAspect.LANDSCAPE.id
            render()
        }
    }

    /** Status line of a running job of this screen, with "Cancel". */
    private fun Col.progress(key: String, initial: String) {
        if (busy != key) return
        status = working(BgTasks.find(TASK)?.message?.takeIf { it.isNotBlank() } ?: initial)
        button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) {
            cancel = true
            BgTasks.find(TASK)?.let { BgTasks.stop(it) }
        }
    }

    private fun clipsCard(c: Col) = c.card {
        add(text(ctx, t("1. Клипы", "1. Clips"), Type.TITLE_M, bold = true), 0)
        muted(t("Найдите видео: скачиваются только нужные секунды. ⋮ у клипа — момент по кадрам, ещё отрезок, своя нарезка.",
            "Find videos: only the needed seconds are downloaded. ⋮ at a clip — the moment by frames, one more part, own cutting."))
        clips.forEach { cl ->
            val ready = cl.file.isNotEmpty() && File(clipsDir, cl.file).exists()
            val onBoard = items.count { it.clipId == cl.id }
            val extra = listOfNotNull(
                if (ready && items.isNotEmpty()) t("кусков в раскадровке: $onBoard", "pieces on the storyboard: $onBoard") else null,
                cuts[cl.id]?.let { cutLabel(it) }?.takeIf { it.isNotEmpty() }
            ).joinToString(" · ")
            val label = (if (ready) "✅ " else "⬇ ") + "${cl.id} · ${StudioCuts.stamp(cl.fromMs)} +${(cl.ms + 500) / 1000} с" +
                (if (cl.durMs > 0) t(" из ", " of ") + StudioCuts.stamp(cl.durMs) else "") + "\n" +
                cl.title.ifBlank { cl.note }.ifBlank { cl.url }.take(80) + if (extra.isNotEmpty()) "\n$extra" else ""
            add(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chk(ctx, label, cl.id in clipMarks) { on ->
                    if (on) clipMarks += cl.id else clipMarks -= cl.id
                    if (busy.isEmpty()) render()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconBtn(ctx, "⋮") { if (busy.isEmpty()) clipSheet(cl.id) }.apply {
                    contentDescription = t("Настроить клип ${cl.id}", "Set up clip ${cl.id}")
                })
            }, 4)
        }
        progress("dl", t("Загрузка клипов…", "Downloading clips…"))
        progress("assist", t("Помощник смотрит видео…", "The assistant watches the videos…"))
        if (busy.isNotEmpty()) return@card
        val views = mutableListOf<View>(btn(ctx, t("🔎 Найти видео", "🔎 Find videos"), style = BtnStyle.TONAL) { findSheet() },
            btn(ctx, t("🧭 Подобрать с ИИ", "🧭 Pick with AI"), style = BtnStyle.TONAL) { assistSheet() })
        if (clips.isNotEmpty()) views += iconBtn(ctx, "▶") {
            feedSheet(clips.distinctBy { it.url }.map { FeedItem(it.url, it.title.ifBlank { it.note }, it.fromMs) }) { item, at ->
                takeFrom(item.url, at)
            }
        }.apply { contentDescription = t("Пролистать видео в мини-плеере", "Flip through the videos in a mini player") }
        if (clips.size > 1) {
            val all = clips.all { it.id in clipMarks }
            views += btn(ctx, if (all) t("☐ Снять выбор", "☐ Clear selection") else t("☑ Выбрать все", "☑ Select all"), style = BtnStyle.TEXT) {
                if (all) clipMarks.clear() else clipMarks += clips.map { it.id }
                render()
            }
        }
        row(*views.toTypedArray())
        if (assist.pending.isNotEmpty() && assist.pending.all { it.first in readyIds() }) {
            button(t("🧭 Раскадровка по плану помощника", "🧭 Storyboard by the assistant's plan"), style = BtnStyle.TEXT) { applyPlan() }
        }
        val marked = clips.filter { it.id in clipMarks }
        if (marked.isNotEmpty()) {
            row(
                btn(ctx, t("⏱ Отрезок (${marked.size})", "⏱ Piece (${marked.size})"), style = BtnStyle.TONAL) { rangeDialog(marked) },
                btn(ctx, t("✕ Убрать (${marked.size})", "✕ Remove (${marked.size})"), style = BtnStyle.TEXT) { removeClips(marked) }
            )
        }
        val todo = clips.count { it !in readyClips() }
        if (busy.isEmpty()) clipQuality(this, readyClips(), { render() }) { download(again = it) }
        if (todo > 0) button(t("⬇ Скачать ($todo)", "⬇ Download ($todo)"), primary = true) { download() }
    }

    private fun mixCard(c: Col) = c.card {
        add(text(ctx, t("2. Нарезка", "2. Cutting"), Type.TITLE_M, bold = true), 0)
        add(text(ctx, t("Длина куска", "Piece length"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        val now = Prefs.pieceMs
        chips((StudioMix.PIECES + now).distinct().sorted().map { ms ->
            chip(ctx, StudioMix.pieceLabel(ms), ms == now) { if (busy.isEmpty() && ms != now) applyPiece(ms) }
        } + chip(ctx, t("✎ Своя", "✎ Own")) {
            if (busy.isEmpty()) prompt(t("Длина куска, секунд", "Piece length, seconds"), t("напр. 0.8 или 4", "e.g. 0.8 or 4"), (now / 1000.0).toString()) { v ->
                val ms = StudioMix.parsePiece(v)
                if (ms == null) toast(t("Нужно число секунд", "A number of seconds is needed")) else applyPiece(ms)
            }
        })
        add(text(ctx, t("Длина ролика", "Video length"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        if (voiceFile.exists()) muted(t("Задаёт озвучка (${StudioCuts.stamp(voiceMs())}) — подгонка в шаге 4.", "Set by the voice-over (${StudioCuts.stamp(voiceMs())}) — fitting is in step 4."))
        else {
            val target = Prefs.targetMs
            chips(listOf(0L, 15_000L, 30_000L, 60_000L).let { if (target in it) it else it + target }.map { ms ->
                chip(ctx, if (ms == 0L) t("Авто", "Auto") else StudioCuts.stamp(ms), ms == target) { if (busy.isEmpty() && ms != target) applyTarget(ms) }
            } + chip(ctx, t("✎ Своя", "✎ Own")) {
                if (busy.isEmpty()) prompt(t("Длина ролика", "Video length"), t("напр. 0:45 или 45", "e.g. 0:45 or 45"), if (target > 0) StudioCuts.stamp(target) else "") { v ->
                    val ms = StudioCuts.parseTime(v)?.takeIf { it >= 1_000 }
                    if (ms == null) toast(t("Нужно время: 45 или 0:45", "A time is needed: 45 or 0:45")) else applyTarget(ms)
                }
            })
            if (target > 0) {
                add(choiceChips(ctx, listOf(MixFill.PIECES to t("🧩 Добирать новые куски", "🧩 Take new pieces"),
                    MixFill.STRETCH to t("⇔ Растягивать и повторять", "⇔ Stretch and repeat")), Prefs.fill) { m ->
                    if (busy.isEmpty() && m != Prefs.fill) {
                        Prefs.fill = m
                        if (items.isEmpty()) render() else {
                            recut(if (m == MixFill.PIECES) t("Ролик заполняется новыми кусками", "The video is filled with new pieces")
                            else t("Куски растянуты до ${StudioCuts.stamp(target)}", "Pieces stretched to ${StudioCuts.stamp(target)}"))
                            autoTopUp()
                        }
                    }
                }, 4)
                muted(if (Prefs.fill == MixFill.PIECES) t("Куски берутся из клипов, пока не заполнят ${StudioCuts.stamp(target)}: лишние убираются, не хватает — режутся другие места клипов и докачиваются новые отрезки тех же видео. Длина куска не меняется.",
                    "Pieces are taken from the clips until they fill ${StudioCuts.stamp(target)}: extra ones go, when short — other places of the clips are cut and new parts of the same videos are downloaded. The piece length stays.")
                else t("Куски прореживаются или растягиваются ровно до ${StudioCuts.stamp(target)}; не хватает — повторяются.",
                    "Pieces are thinned out or stretched to exactly ${StudioCuts.stamp(target)}; when short, they repeat."))
                val lack = StudioMix.lackMs(items, target)
                if (busy.isEmpty() && items.isNotEmpty() && lack > FIT_TOL_MS) {
                    add(text(ctx, t("⚠ Не хватает ${StudioCuts.stamp(lack)} до ${StudioCuts.stamp(target)}", "⚠ ${StudioCuts.stamp(lack)} short of ${StudioCuts.stamp(target)}"),
                        14f, bold = true, color = Palette.ACCENT), 6)
                    button(t("🧩 Добрать кусков из видео", "🧩 Take more pieces of the videos"), style = BtnStyle.TONAL) { topUp(target) }
                }
            } else muted(t("Ролик длиной со все куски. Задайте длину — куски будут подбираться, пока не заполнят её.",
                "The video is as long as all the pieces. Set a length — pieces are picked until they fill it."))
        }
        add(text(ctx, t("Где резать", "Where to cut"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(MixCut.EVEN to t("▦ Подряд", "▦ In a row"), MixCut.RANDOM to t("🎲 Случайно", "🎲 Random")),
            Prefs.mode) { m -> if (busy.isEmpty()) setMode(m) }, 4)
        val own = readyClips().filter { cuts[it.id]?.isDefault == false }.map { it.id }
        if (own.isNotEmpty()) muted(t("Своя нарезка у клипов: ", "Own cutting of clips: ") + own.joinToString(", "))
        if (busy.isNotEmpty()) return@card
        if (items.isEmpty()) button(t("✂ Нарезать", "✂ Cut"), primary = true) { shuffle() }
        else {
            val missing = StudioMix.missing(items, readyClips().map { it.id })
            if (missing.isNotEmpty()) button(t("＋ Вернуть в раскадровку: ", "＋ Back to the storyboard: ") + missing.joinToString(", "),
                style = BtnStyle.TEXT) {
                var list = items
                val specs = specs().associateBy { it.id }
                for (id in missing) specs[id]?.let { list = StudioMix.addPieces(list, StudioMix.cut(it, Prefs.pieceMs, Prefs.mode, Prefs.cutSeed), System.nanoTime()) }
                change(list, t("Добавлено кусков: ${list.size - items.size}", "Pieces added: ${list.size - items.size}"))
            }
        }
    }

    /** A new video length: the storyboard is cut again to it (↶ brings the old one back). */
    private fun applyTarget(ms: Long) {
        Prefs.targetMs = ms
        if (items.isEmpty()) return render()
        recut(if (ms > 0) t("Ролик ${StudioCuts.stamp(ms)}", "Video ${StudioCuts.stamp(ms)}") else t("Длина — по кускам", "Length by the pieces"))
        autoTopUp()
    }

    /** New piece length: every clip is cut again at once; ↶ or "Undo" brings the old storyboard back. */
    private fun applyPiece(ms: Long) {
        Prefs.pieceMs = ms
        if (items.isEmpty()) return render()
        recut(t("Куски по ${StudioMix.pieceLabel(ms)}", "Pieces of ${StudioMix.pieceLabel(ms)}"))
    }

    private fun setMode(m: MixCut) {
        Prefs.mode = m
        if (m == MixCut.RANDOM) Prefs.cutSeed = System.nanoTime()
        if (items.isEmpty()) return render()
        recut(if (m == MixCut.RANDOM) t("Нарезано в случайных местах", "Cut at random places") else t("Нарезано ровно, подряд", "Cut evenly, in a row"))
    }

    /** The first mix of the downloaded clips. */
    private fun shuffle() {
        if (readyClips().isEmpty()) return
        Prefs.seed = System.nanoTime()
        recut(null)
        autoTopUp()
    }

    /** The same pieces in a new row; pinned ones keep their places. */
    private fun reorder(order: MixOrder) {
        Prefs.seed = System.nanoTime()
        val msg = when (order) {
            MixOrder.SHUFFLE -> t("Перемешано", "Shuffled")
            MixOrder.ORDER -> t("По порядку клипов", "In clip order")
            MixOrder.ROUND -> t("Клипы по кругу", "Clips in turn")
        }
        val next = StudioMix.rearrange(items, order, Prefs.seed)
        if (next == items) toast(t("Порядок не изменился: всё закреплено или кусок один", "The order is the same: everything is pinned or there is one piece"))
        else change(next, msg)
    }

    /** All the ways to shuffle and to cut again, explained; the quick "🔀 Shuffle" is on the card. */
    // only what the cards have not: a random shuffle is on the storyboard, even/random cutting in step 2,
    // shuffling the ticked ones on their panel, fitting to the voice-over in step 4
    private fun shuffleSheet() = bottomSheet(t("⋯ Ещё варианты", "⋯ More options")) { dismiss ->
        val pinned = items.count { it.locked }
        if (pinned > 0) muted(t("📌 Закреплённые куски ($pinned) остаются на своих местах.", "📌 Pinned pieces ($pinned) stay in their places."))
        add(text(ctx, t("Те же куски — другой порядок", "Same pieces — another order"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        button(t("▤ Клип за клипом", "▤ Clip after clip"), style = BtnStyle.TONAL) { dismiss(); reorder(MixOrder.ORDER) }
        button(t("🔁 По кругу: c1, c2, c3, c1…", "🔁 In turn: c1, c2, c3, c1…"), style = BtnStyle.TEXT) { dismiss(); reorder(MixOrder.ROUND) }
        add(text(ctx, t("Новые куски из уже скачанного", "New pieces of what is downloaded"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        button(t("✂ Нарезать скачанное в других местах", "✂ Cut the downloads at other places"), style = BtnStyle.TONAL) {
            dismiss()
            Prefs.mode = MixCut.RANDOM
            Prefs.cutSeed = System.nanoTime()
            Prefs.seed = System.nanoTime()
            recut(t("Новые места нарезки", "New cutting places"))
        }
        val ticked = readyClips().filter { it.id in clipMarks && it.id !in lockedClips() }
        if (ticked.isNotEmpty()) {
            add(text(ctx, t("Новые моменты из видео", "New moments of the videos"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            button(t("🎲 Только у выбранных клипов: ", "🎲 Only for the selected clips: ") + ticked.joinToString(", ") { it.id }, style = BtnStyle.TONAL) {
                dismiss()
                newPieces(ticked)
            }
        }
    }

    /** Clips with pinned pieces keep their part of the video. */
    private fun lockedClips(): Set<String> = items.filter { it.locked }.map { it.clipId }.toSet()

    /**
     * "🎲 New pieces": new random parts of the videos of [list] (not taken before, no intro or end screen),
     * downloaded again and cut at new places in a new order; clips with pinned pieces stay as they are.
     */
    private fun newPieces(list: List<SClip> = readyClips().filter { it.id !in lockedClips() }) {
        if (busy.isNotEmpty() || rendering()) return toast(t("Дождитесь конца задачи", "Wait for the task to end"))
        if (list.isEmpty()) return toast(t("Нет клипов без закреплённых кусков", "No clips without pinned pieces"))
        Prefs.cutSeed = System.nanoTime()
        Prefs.seed = System.nanoTime()
        toast(t("Новые куски: качаю ${list.size} клип(ов)…", "New pieces: downloading ${list.size} clip(s)…"))
        download(list.map { it.id }.toSet())
    }

    // ---------- storyboard ----------

    private fun boardCard(c: Col) = c.card {
        add(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(ctx, t("3. Раскадровка", "3. Storyboard"), Type.TITLE_M, bold = true).apply { heading() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (busy.isEmpty()) {
                addView(iconBtn(ctx, "↶") { undo() }.apply {
                    contentDescription = t("Отменить", "Undo")
                    isEnabled = undoStack.isNotEmpty()
                    alpha = if (undoStack.isNotEmpty()) 1f else 0.35f
                })
                addView(iconBtn(ctx, "↷") { redo() }.apply {
                    contentDescription = t("Повторить", "Redo")
                    isEnabled = redoStack.isNotEmpty()
                    alpha = if (redoStack.isNotEmpty()) 1f else 0.35f
                })
            }
        }, 0)
        muted(t("Тап — настроить кусок, ○ — отметить, удержать и тащить — переставить, 📌 — не двигается.",
            "Tap — set up a piece, ○ — tick, hold and drag — move, 📌 — stays in place."))
        boardInfo = add(text(ctx, "", 14f, bold = true), 8)
        updateInfo()
        val (rv, height) = board()
        add(rv, 8).layoutParams.height = height
        if (busy.isNotEmpty()) return@card
        selBox = add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }, 4)
        fillSelection()
        row(
            btn(ctx, t("🔀 Перемешать", "🔀 Shuffle"), style = BtnStyle.TONAL) { reorder(MixOrder.SHUFFLE) },
            btn(ctx, t("🎲 Новые куски", "🎲 New pieces"), style = BtnStyle.TONAL) { newPieces() }.apply {
                contentDescription = t("Новые случайные куски из видео: скачать заново", "New random pieces of the videos: download again")
            },
            iconBtn(ctx, "⋯") { shuffleSheet() }.apply { contentDescription = t("Ещё варианты", "More options") }
        )
        muted(t("🔀 — те же куски в другом порядке; 🎲 — новые моменты из тех же видео (скачиваются заново).",
            "🔀 — the same pieces in another order; 🎲 — new moments of the same videos (downloaded again)."))
        button(t("▶ Просмотр без сборки", "▶ Preview without building"), style = BtnStyle.TEXT) { previewSheet(0) }
    }

    /** Under the storyboard: "Tick all", or the actions for the ticked elements. */
    private fun fillSelection() {
        val box = selBox ?: return
        box.removeAllViews()
        val n = items.count { it.marked }
        if (n == 0) {
            box.addView(btn(this, t("☑ Отметить все", "☑ Tick all"), style = BtnStyle.TEXT) { change(items.map { it.copy(marked = true) }) })
            return
        }
        box.addView(text(this, t("Отмечено: $n из ${items.size}", "Ticked: $n of ${items.size}"), Type.LABEL, bold = true, color = Palette.MUTED))
        val pinAll = items.filter { it.marked }.all { it.locked }
        val actions = listOfNotNull(
            chip(this, t("☐ Снять", "☐ Untick")) { change(items.map { it.copy(marked = false) }) },
            if (n < items.size) chip(this, t("☑ Все", "☑ All")) { change(items.map { it.copy(marked = true) }) } else null,
            chip(this, if (pinAll) t("Открепить", "Unpin") else t("📌 Закрепить", "📌 Pin")) {
                change(items.map { if (it.marked) it.copy(locked = !pinAll) else it },
                    if (pinAll) t("Откреплено: $n", "Unpinned: $n") else t("Закреплено: $n — не двигаются при перемешивании", "Pinned: $n — they stay when shuffled"))
            },
            if (n >= 2) chip(this, t("🔀 Перемешать их", "🔀 Shuffle them")) {
                change(StudioMix.shuffleMarked(items, System.nanoTime()), t("Отмеченные перемешаны", "Ticked ones shuffled"))
            } else null,
            chip(this, t("⧉ Дубль", "⧉ Duplicate")) { change(StudioMix.duplicateMarked(items), t("Дубли добавлены: $n", "Duplicates added: $n")) },
            chip(this, t("✕ Удалить", "✕ Delete")) { change(StudioMix.removeMarked(items), t("Удалено кадров: $n", "Frames deleted: $n")) }
        )
        box.addView(chipRow(this, actions, collapseAfter = 99), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun updateInfo() {
        val on = items.count { it.marked }
        val pins = items.count { it.locked }
        val voice = voiceMs()
        boardInfo?.text = t("Кадров: ${items.size} · ${StudioCuts.stamp(StudioMix.totalMs(items))}", "Frames: ${items.size} · ${StudioCuts.stamp(StudioMix.totalMs(items))}") +
            (if (on > 0) t(" · отмечено: $on", " · ticked: $on") else "") +
            (if (pins > 0) " · 📌 $pins" else "") +
            (if (voice > 0) t(" · озвучка ", " · voice-over ") + StudioCuts.stamp(voice) else "") +
            StudioMix.repeats(items).let { if (it > 0) t(" · повторов подряд: $it", " · repeats in a row: $it") else "" }
    }

    /** Tick of element [p] switched without rebuilding the page. */
    private fun toggleMark(p: Int, adapter: RecyclerView.Adapter<*>) {
        if (p !in items.indices || busy.isNotEmpty()) return
        saveItems(items.mapIndexed { i, it -> if (i == p) it.copy(marked = !it.marked) else it })
        adapter.notifyItemChanged(p)
        updateInfo()
        fillSelection()
    }

    /** The storyboard: a grid of frames with a fixed height, so dragging scrolls it inside the page. */
    private fun board(): Pair<RecyclerView, Int> {
        val portrait = aspect() == StudioAspect.PORTRAIT
        val span = if (portrait) 4 else 3
        val width = resources.displayMetrics.widthPixels - dp(64)
        val tileW = width / span
        val tileH = if (portrait) tileW * 16 / 9 else tileW * 9 / 16
        val rowH = tileH + dp(8)
        val rows = (items.size + span - 1) / span
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, span)
        val adapter = BoardAdapter(tileW, tileH)
        rv.adapter = adapter
        rv.isNestedScrollingEnabled = true
        boardState?.let { rv.layoutManager?.onRestoreInstanceState(it) }
        boardRv = rv
        var dragFrom: List<MixItem>? = null
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = busy.isEmpty()

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragFrom = items
            }

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                items = StudioMix.move(items, a, b)
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val before = dragFrom
                dragFrom = null
                if (before != null && before != items) {
                    // a drag is one step of ↶
                    pushUndo(before)
                    saveItems(items)
                    render()
                    return
                }
                updateInfo()
            }
        }).attachToRecyclerView(rv)
        return rv to minOf(rows * rowH + dp(4), (resources.displayMetrics.heightPixels * 0.55f).toInt())
    }

    private inner class Tile(val root: FrameLayout, val image: ImageView, val label: TextView, val mark: TextView, val del: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class BoardAdapter(private val tileW: Int, private val tileH: Int) : RecyclerView.Adapter<Tile>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Tile {
            val ctx = parent.context
            val root = FrameLayout(ctx)
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH + dp(8))
            root.setPadding(dp(2), dp(2), dp(2), dp(2))
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF202020.toInt())
            }
            root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH, Gravity.TOP))
            val label = text(ctx, "", 11f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
                setPadding(dp(4), 0, dp(4), dp(2))
                maxLines = 2
            }
            root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            // the tick circle: always there, so several frames can be ticked without a long press
            val mark = text(ctx, "", 14f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            root.addView(mark, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
            val del = text(ctx, "✕", 13f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                background = roundBg(0x99000000.toInt(), 12f, ctx)
                contentDescription = t("Удалить", "Delete")
            }
            root.addView(del, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START).apply { setMargins(dp(2), dp(2), 0, 0) })
            val h = Tile(root, image, label, mark, del)
            root.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0 || busy.isNotEmpty()) return@setOnClickListener
                // while something is ticked a tap ticks; otherwise it opens the piece
                if (items.any { it.marked }) toggleMark(p, this) else pieceSheet(items[p].key)
            }
            mark.setOnClickListener { toggleMark(h.bindingAdapterPosition, this) }
            del.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0 || busy.isNotEmpty()) return@setOnClickListener
                change(items.filterIndexed { i, _ -> i != p }, t("Кадр удалён", "Frame deleted"))
            }
            return h
        }

        override fun onBindViewHolder(h: Tile, position: Int) {
            val it = items[position]
            h.label.text = "${position + 1} · ${it.clipId} · ${"%.1f".format(Locale.US, it.lenMs / 1000f)} с" +
                (if (it.scheme != null) " 🧩" else "") + if (it.locked) " 📌" else ""
            h.mark.text = if (it.marked) "✓" else ""
            h.mark.background = android.graphics.drawable.InsetDrawable(
                if (it.marked) roundBg(Palette.ACCENT, 14f, this@StudioMixActivity) else roundBg(0x55000000, 14f, this@StudioMixActivity, 0xFFFFFFFF.toInt()),
                dp(4)
            )
            h.mark.contentDescription = if (it.marked) t("Снять отметку", "Untick") else t("Отметить", "Tick")
            h.root.background = if (it.marked) roundBg(0x00000000, 6f, this@StudioMixActivity, Palette.ACCENT) else null
            h.root.contentDescription = t("Кадр ${position + 1}, клип ${it.clipId}", "Frame ${position + 1}, clip ${it.clipId}") +
                (if (it.locked) t(", закреплён", ", pinned") else "")
            thumb(h.image, it)
        }
    }

    /** Frame of the element at its start, from the downloaded clip (cached; made on a worker thread). */
    private fun thumb(iv: ImageView, item: MixItem) {
        val clip = clips.firstOrNull { it.id == item.clipId } ?: return
        // the clip id is reused by a new mix: the key names the video and the piece
        val key = clip.url + "|" + clip.fromMs + "|" + item.fromMs
        iv.tag = key
        thumbs.get(key)?.let {
            iv.setImageBitmap(it)
            return
        }
        iv.setImageDrawable(null)
        val f = File(clipsDir, clip.file)
        thumbPool.execute {
            val bmp = runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    val us = (clip.leadMs + item.fromMs + minOf(300L, item.lenMs / 2)) * 1000
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        val w = 240
                        val small = Bitmap.createScaledBitmap(b, w, (w.toLong() * b.height / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true)
                        if (small !== b) b.recycle()
                        small
                    }
                } finally {
                    runCatching { r.release() }
                }
            }.getOrNull() ?: return@execute
            thumbs.put(key, bmp)
            Lumen.ui { if (iv.tag == key) iv.setImageBitmap(bmp) }
        }
    }

    // ---------- one piece, preview ----------

    /**
     * Player of storyboard pieces straight from the downloaded clips, cropped to the frame of the mix like the built
     * video: one piece in a loop or the whole storyboard in a row. No build is needed.
     */
    private inner class MixPlayer {
        private val surface = android.view.TextureView(this@StudioMixActivity)
        private val box = FrameLayout(this@StudioMixActivity).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { toggle() }
            contentDescription = t("Пауза или воспроизведение", "Pause or play")
        }
        private var player: com.google.android.exoplayer2.ExoPlayer? = null
        private var vw = 0
        private var vh = 0
        /** What plays now, so a refreshed sheet does not restart the same piece. */
        var playing = ""
        var onIndex: (Int) -> Unit = {}
        var onPlaying: (Boolean) -> Unit = {}

        /** The player in a frame of the mix shape, centred. */
        fun view(): View {
            val w = resources.displayMetrics.widthPixels - dp(40)
            val (bw, bh) = if (aspect() == StudioAspect.PORTRAIT) {
                val h = (resources.displayMetrics.heightPixels * 0.36f).toInt()
                h * 9 / 16 to h
            } else w to w * 9 / 16
            box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
            (box.parent as? ViewGroup)?.removeView(box)
            return FrameLayout(this@StudioMixActivity).apply {
                addView(box, FrameLayout.LayoutParams(bw, bh, Gravity.CENTER))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh)
            }
        }

        /** Centre crop of the video into the frame, as in the built mix. */
        private fun fit() {
            val fw = box.width.toFloat()
            val fh = box.height.toFloat()
            if (vw <= 0 || vh <= 0 || fw <= 0 || fh <= 0) return
            val k = maxOf(fw / vw, fh / vh)
            surface.setTransform(android.graphics.Matrix().apply { setScale(vw * k / fw, vh * k / fh, fw / 2, fh / 2) })
        }

        private fun exo(): com.google.android.exoplayer2.ExoPlayer = player ?: com.google.android.exoplayer2.ExoPlayer.Builder(this@StudioMixActivity).build().also { p ->
            player = p
            p.setVideoTextureView(surface)
            p.addListener(object : com.google.android.exoplayer2.Player.Listener {
                override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
                    if (size.width <= 0 || size.height <= 0) return
                    vw = (size.width * size.pixelWidthHeightRatio).toInt()
                    vh = size.height
                    fit()
                }

                override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) = onIndex(p.currentMediaItemIndex)

                override fun onIsPlayingChanged(isPlaying: Boolean) = onPlaying(isPlaying)
            })
        }

        /** Plays [parts] from element [start]: in a loop, cropped like the mix. */
        fun play(parts: List<MixItem>, start: Int = 0) {
            val ready = readyClips().associateBy { it.id }
            val list = parts.mapNotNull { item ->
                val c = ready[item.clipId] ?: return@mapNotNull null
                val a = c.leadMs + item.fromMs
                com.google.android.exoplayer2.MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(File(clipsDir, c.file)))
                    .setClippingConfiguration(com.google.android.exoplayer2.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(a).setEndPositionMs(a + item.lenMs).build())
                    .build()
            }
            if (list.isEmpty()) return
            playing = parts.joinToString(";") { "${it.key}+${it.lenMs}" }
            val p = exo()
            p.setMediaItems(list, start.coerceIn(0, list.size - 1), 0L)
            p.repeatMode = com.google.android.exoplayer2.Player.REPEAT_MODE_ALL
            p.prepare()
            p.playWhenReady = true
            onIndex(p.currentMediaItemIndex)
        }

        fun toggle() {
            val p = player ?: return
            p.playWhenReady = !p.playWhenReady
        }

        fun next() = player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekTo(0, 0L) }

        fun prev() = player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L) }

        fun index(): Int = player?.currentMediaItemIndex ?: 0

        fun release() {
            player?.release()
            player = null
        }
    }

    /** Start of element [i] in the video. */
    private fun timeAt(i: Int): Long = items.take(i).sumOf { it.lenMs }

    /** The whole storyboard in a row, as in the built video (without schemes, subtitles and voice-over). */
    private fun previewSheet(from: Int) {
        if (items.isEmpty()) return
        val player = MixPlayer()
        bottomSheet(t("▶ Просмотр раскадровки", "▶ Storyboard preview"), onDismiss = { player.release() }) { dismiss ->
            muted(t("Куски идут подряд, как в готовом видео. Инфоблоков, субтитров и озвучки здесь нет — их добавит сборка.",
                "The pieces go one after another as in the built video. Info-blocks, subtitles and the voice-over come with the build."))
            add(player.view(), 8)
            val info = add(text(ctx, "", 14f, bold = true), 8)
            lateinit var playB: Button
            row(
                btn(ctx, "⏮", style = BtnStyle.TEXT) { player.prev() }.apply { contentDescription = t("Предыдущий кусок", "Previous piece") },
                btn(ctx, "⏸", style = BtnStyle.TONAL) { player.toggle() }.also { playB = it },
                btn(ctx, "⏭", style = BtnStyle.TEXT) { player.next() }.apply { contentDescription = t("Следующий кусок", "Next piece") }
            )
            button(t("✎ Настроить этот кусок", "✎ Set up this piece"), style = BtnStyle.TEXT) {
                val k = items.getOrNull(player.index())?.key
                dismiss()
                k?.let { pieceSheet(it) }
            }
            player.onIndex = { i ->
                items.getOrNull(i)?.let { info.text = "${i + 1} / ${items.size} · ${it.clipId} · ${StudioCuts.stamp(timeAt(i))} / ${StudioCuts.stamp(StudioMix.totalMs(items))}" }
            }
            player.onPlaying = { on ->
                playB.text = if (on) "⏸" else "▶"
                playB.contentDescription = if (on) t("Пауза", "Pause") else t("Играть", "Play")
            }
            player.play(items, from)
        }
    }

    /**
     * One piece: plays in a loop; its start and length inside the clip, another random moment, its place on the
     * storyboard, pin, duplicate, halves, delete. Every change is one step of ↶.
     */
    private fun pieceSheet(key0: String) {
        var key = key0
        val player = MixPlayer()
        bottomSheet(t("Кусок раскадровки", "Storyboard piece"), onDismiss = { player.release() }) { dismiss ->
            add(player.view(), 8)
            val body = Col(ctx, 0)
            add(body.root, 4)
            lateinit var refill: () -> Unit

            /** The storyboard changed by the sheet; the piece is followed to its place [at]. */
            fun applyEdit(list: List<MixItem>, at: Int) {
                change(list)
                val k = items.getOrNull(at)?.key
                if (k == null) dismiss() else {
                    key = k
                    refill()
                }
            }

            fun label(s: String) = body.add(text(ctx, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)

            refill = fill@{
                body.clear()
                val i = items.indexOfFirst { it.key == key }
                val it = items.getOrNull(i)
                val clip = it?.let { x -> readyClips().firstOrNull { c -> c.id == x.clipId } }
                if (it == null || clip == null) {
                    dismiss()
                    return@fill
                }
                if (player.playing != "${it.key}+${it.lenMs}") player.play(listOf(it))
                val clipMs = clip.lenMs
                fun put(x: MixItem) = applyEdit(items.mapIndexed { k, y -> if (k == i) x else y }, i)
                body.add(text(ctx, "${i + 1} / ${items.size} · ${clip.id} · ${StudioMix.pieceLabel(it.lenMs)}" + (if (it.locked) " · 📌" else ""), Type.TITLE_M, bold = true), 0)
                body.muted(clip.title.ifBlank { clip.note }.ifBlank { clip.url }.take(90) + "\n" +
                    t("В видео: ", "In the video: ") + StudioCuts.stamp(clip.fromMs + it.fromMs) + "–" + StudioCuts.stamp(clip.fromMs + it.fromMs + it.lenMs) +
                    t(" · отрезок клипа ", " · clip part ") + StudioCuts.stamp(clip.fromMs) + "–" + StudioCuts.stamp(clip.fromMs + clipMs))
                label(t("Начало куска", "Piece start"))
                body.chips(listOf(-1_000L to "−1 с", -200L to "−0.2", 200L to "+0.2", 1_000L to "+1 с").map { (d, s) ->
                    chip(ctx, s) { put(StudioMix.shift(it, d, clipMs)) }.apply {
                        contentDescription = t("Сдвинуть начало на $s", "Move the start by $s")
                    }
                } + chip(ctx, t("🎲 Другой момент", "🎲 Another moment")) { put(StudioMix.moment(it, clipMs, System.nanoTime())) })
                label(t("Длина куска", "Piece length"))
                body.chips((StudioMix.PIECES.filter { ms -> ms <= clipMs } + it.lenMs).distinct().sorted().map { ms ->
                    chip(ctx, StudioMix.pieceLabel(ms), ms == it.lenMs) { if (ms != it.lenMs) put(StudioMix.withLen(it, ms, clipMs)) }
                } + chip(ctx, t("✎ Своя", "✎ Own")) {
                    prompt(t("Длина куска, секунд", "Piece length, seconds"), t("до ", "up to ") + StudioMix.pieceLabel(clipMs), (it.lenMs / 1000.0).toString()) { v ->
                        StudioMix.parsePiece(v)?.let { ms -> put(StudioMix.withLen(it, ms, clipMs)) } ?: toast(t("Нужно число секунд", "A number of seconds is needed"))
                    }
                })
                label(t("Место в раскадровке", "Place on the storyboard"))
                body.chips(listOfNotNull(
                    if (i > 0) chip(ctx, t("⏮ В начало", "⏮ To start")) { applyEdit(StudioMix.move(items, i, 0), 0) } else null,
                    if (i > 0) chip(ctx, t("◀ Раньше", "◀ Earlier")) { applyEdit(StudioMix.move(items, i, i - 1), i - 1) } else null,
                    if (i < items.lastIndex) chip(ctx, t("Позже ▶", "Later ▶")) { applyEdit(StudioMix.move(items, i, i + 1), i + 1) } else null,
                    if (i < items.lastIndex) chip(ctx, t("⏭ В конец", "⏭ To end")) { applyEdit(StudioMix.move(items, i, items.lastIndex), items.lastIndex) } else null
                ))
                label(t("Ещё", "More"))
                body.chips(listOfNotNull(
                    chip(ctx, if (it.locked) t("Открепить", "Unpin") else t("📌 Закрепить", "📌 Pin"), it.locked) { put(it.copy(locked = !it.locked)) },
                    chip(ctx, t("⧉ Дубль", "⧉ Duplicate")) { applyEdit(StudioMix.duplicate(items, i), i) },
                    StudioMix.halves(items, i)?.let { h -> chip(ctx, t("✂ Пополам", "✂ In halves")) { applyEdit(h, i) } },
                    chip(ctx, t("✕ Удалить", "✕ Delete")) {
                        dismiss()
                        change(items.filterIndexed { k, _ -> k != i }, t("Кадр удалён", "Frame deleted"))
                    }
                ))
                val back = undoStack.isNotEmpty()
                body.row(
                    btn(ctx, t("↶ Отменить", "↶ Undo"), style = BtnStyle.TEXT) {
                        undo()
                        if (items.none { x -> x.key == key }) items.getOrNull(i)?.let { x -> key = x.key }
                        refill()
                    }.apply { isEnabled = back },
                    btn(ctx, t("Готово", "Done"), style = BtnStyle.TONAL) { dismiss() }
                )
            }
            refill()
        }
    }

    /**
     * One clip: frames across its video and a mini player to pick the moment, its part (start and length),
     * a random part, one more part of the same video, and its own cutting into pieces.
     */
    private fun clipSheet(id: String) {
        val cl = clips.firstOrNull { it.id == id } ?: return
        val look = MiniLook(this)
        bottomSheet(t("Клип ${cl.id}", "Clip ${cl.id}"), onDismiss = { look.release() }) { dismiss ->
            p(cl.title.ifBlank { cl.note }.ifBlank { cl.url }.take(120))
            muted(t("Отрезок ", "Part ") + StudioCuts.stamp(cl.fromMs) + "–" + StudioCuts.stamp(cl.toMs) +
                (if (cl.durMs > 0) t(" из ", " of ") + StudioCuts.stamp(cl.durMs) else "") +
                if (cl.file.isEmpty()) t(" · не скачан", " · not downloaded") else "")
            add(text(ctx, t("Найти момент в видео", "Find the moment in the video"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            muted(t("Кадры по всему видео; тап по кадру — видео с этого места. На нужном месте нажмите «⏱ Начало = сейчас».",
                "Frames across the video; tap a frame to watch from there. At the right place tap \"⏱ Start = now\"."))
            add(look.view, 4)
            look.open(cl.url)

            add(text(ctx, t("Отрезок для скачивания", "Part to download"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            val fromE = edit(t("Начало: 1:23 или 83", "Start: 1:23 or 83"), StudioCuts.stamp(cl.fromMs))
            var lenS = ((cl.ms + 500) / 1000).coerceIn(1, StudioCuts.MAX_CLIP_MS / 1000)
            add(choiceChips(ctx, (listOf(3L, 5L, 8L, 10L, 15L, 20L, 30L) + lenS).distinct().sorted().map { it to "$it с" }, lenS) { lenS = it }, 4)
            row(
                btn(ctx, t("⏱ Начало = сейчас", "⏱ Start = now"), style = BtnStyle.TONAL) {
                    val at = look.posMs()
                    if (at < 0) toast(t("Включите видео выше: тап по кадру", "Play the video above: tap a frame"))
                    else fromE.setText(StudioCuts.stamp(at / 100 * 100))
                },
                btn(ctx, t("🎲 Случайно", "🎲 Random"), style = BtnStyle.TEXT) {
                    val dur = cl.durMs.takeIf { it > 0 } ?: look.durMs()
                    if (dur <= 0) toast(t("Длина видео ещё не известна: включите его выше", "The video length is not known yet: play it above"))
                    else fromE.setText(StudioCuts.stamp(StudioMix.randomStart(dur, lenS * 1000, StudioCuts.parseTime(fromE.text.toString()) ?: 0, System.nanoTime())))
                }
            )
            button(t("⬇ Применить и скачать", "⬇ Apply and download"), primary = true) {
                val from = StudioCuts.parseTime(fromE.text.toString())
                    ?: return@button toast(t("Начало: время вида 1:23 или 83", "Start: a time like 1:23 or 83"))
                val (a, b) = StudioCuts.span(from, from + lenS * 1000)
                val now = clips.firstOrNull { it.id == id } ?: return@button dismiss()
                dismiss()
                if (now.fromMs == a && now.toMs == b && now.file.isNotEmpty()) return@button toast(t("Отрезок тот же", "The part is the same"))
                if (now.file.isNotEmpty()) File(clipsDir, now.file).delete()
                saveClips(clips.map { if (it.id == id) it.copy(fromMs = a, toMs = b, file = "", leadMs = 0, lenMs = 0) else it })
                render()
                download()
            }
            button(t("⧉ Ещё отрезок из этого видео", "⧉ One more part of this video"), style = BtnStyle.TEXT) {
                if (clips.size >= StudioCuts.MAX_CLIPS) return@button toast(t("Не больше ${StudioCuts.MAX_CLIPS} клипов", "At most ${StudioCuts.MAX_CLIPS} clips"))
                val (a, b) = StudioCuts.span(cl.toMs, cl.toMs + cl.ms)
                val add = SClip(StudioCuts.newId(clips), cl.url, a, b, cl.note, cl.title, durMs = cl.durMs)
                saveClips(clips + add)
                dismiss()
                render()
                toast(t("Добавлен ${add.id}: отрезок сразу после ${cl.id}. Задайте его и скачайте", "Added ${add.id}: the part right after ${cl.id}. Set it and download"))
                clipSheet(add.id)
            }

            add(text(ctx, t("Нарезка этого клипа", "Cutting of this clip"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            var cut = cuts[id] ?: ClipCut()
            val marksBox = Col(ctx, 0)
            add(choiceChips(ctx, listOf(null to t("Как у всех", "As for all"), MixCut.EVEN to t("▦ Ровно", "▦ Even"),
                MixCut.RANDOM to t("🎲 Случайно", "🎲 Random"), MixCut.MARKS to t("✂ Свои моменты", "✂ Own moments")), cut.cut) { m ->
                cut = cut.copy(cut = m)
                marksBox.root.visibility = if (m == MixCut.MARKS) View.VISIBLE else View.GONE
            }, 4)
            muted(t("Длина куска", "Piece length"))
            add(choiceChips(ctx, listOf(0L to t("Как у всех (${StudioMix.pieceLabel(Prefs.pieceMs)})", "As for all (${StudioMix.pieceLabel(Prefs.pieceMs)})")) +
                (StudioMix.PIECES + cut.pieceMs).filter { it > 0 }.distinct().sorted().map { it to StudioMix.pieceLabel(it) }, cut.pieceMs) { ms ->
                cut = cut.copy(pieceMs = ms)
            }, 4)
            add(marksBox.root, 4)
            marksBox.root.visibility = if (cut.cut == MixCut.MARKS) View.VISIBLE else View.GONE
            marksBox.muted(t("С каких секунд отрезка брать куски: 0 — его начало. Напр. 0:01, 3.5, 7. Кусок длится до следующего момента, но не дольше длины куска.",
                "From which seconds of the part to take pieces: 0 is its start. E.g. 0:01, 3.5, 7. A piece lasts until the next moment, at most the piece length."))
            val marksE = marksBox.edit(t("Моменты", "Moments"), StudioMix.marksText(cut.marks))
            marksBox.button(t("＋ Момент = сейчас (из видео выше)", "＋ Moment = now (from the video above)"), style = BtnStyle.TEXT) {
                val at = look.posMs()
                val rel = at - cl.fromMs
                when {
                    at < 0 -> toast(t("Включите видео выше: тап по кадру", "Play the video above: tap a frame"))
                    rel < 0 || rel > cl.ms -> toast(t("Момент вне отрезка ", "The moment is outside the part ") + StudioCuts.stamp(cl.fromMs) + "–" + StudioCuts.stamp(cl.toMs))
                    else -> {
                        val list = (StudioMix.parseMarks(marksE.text.toString()).orEmpty() + rel / 100 * 100).distinct().sorted()
                        marksE.setText(StudioMix.marksText(list))
                    }
                }
            }
            button(t("✓ Применить нарезку", "✓ Apply cutting"), style = BtnStyle.TONAL) {
                val marks = if (cut.cut == MixCut.MARKS) StudioMix.parseMarks(marksE.text.toString())
                    ?: return@button toast(t("Моменты: времена через запятую, напр. 0:01, 3.5", "Moments: times separated by commas, e.g. 0:01, 3.5"))
                else cut.marks
                if (cut.cut == MixCut.MARKS && marks.isEmpty()) return@button toast(t("Добавьте хотя бы один момент", "Add at least one moment"))
                saveCuts(cuts + (id to cut.copy(marks = marks)))
                dismiss()
                if (items.isNotEmpty() && readyClips().any { it.id == id }) {
                    val list = recutClip(id)
                    change(list, t("${cl.id}: кусков ${list.count { it.clipId == id }}", "${cl.id}: ${list.count { it.clipId == id }} pieces"))
                } else render()
            }
            button(t("✕ Убрать клип", "✕ Remove clip"), style = BtnStyle.TEXT) {
                dismiss()
                removeClips(listOf(cl))
            }
        }
    }

    // ---------- step 4: the video ----------

    private fun finalCard(c: Col) = c.card {
        add(text(ctx, t("4. Озвучка и сборка", "4. Voice-over and saving"), Type.TITLE_M, bold = true), 0)
        val voice = voiceFile.exists()
        if (voice) {
            val ms = voiceMs()
            add(text(ctx, t("🎙 Своя озвучка ", "🎙 Own voice-over ") + StudioCuts.stamp(ms), 14f, bold = true), 8)
            if (busy.isEmpty() && !rendering()) fitVoiceBlock(this, ms)
            if (busy.isEmpty() && !rendering()) row(
                btn(ctx, t("🎙 Перезаписать", "🎙 Record again"), style = BtnStyle.TEXT) { startVoice() },
                btn(ctx, t("✕ Убрать", "✕ Remove"), style = BtnStyle.TEXT) {
                    voiceFile.delete()
                    wordsFile.delete()
                    words = emptyList()
                    Prefs.subs = false
                    render()
                }
            )
        }
        val ov = over()
        if (busy.isEmpty() && !rendering()) StudioOverlayUi.block(this, ov, mixWhere(), { setOver(it) }) { overSheet() }
        else muted(StudioOverlayUi.summary(ov, mixWhere()))
        progress("schemes", t("ИИ составляет инфоблоки…", "AI composes info-blocks…"))
        progress("subs", t("Распознавание речи…", "Recognising speech…"))
        progress("script", t("ИИ пишет текст озвучки…", "AI writes the voice-over text…"))
        if (rendering()) {
            renderBar = working(StudioJob.message)
            button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { StudioService.cancel(this@StudioMixActivity) }
        }
        if (busy.isNotEmpty() || rendering()) return@card
        // the order of the work: voice-over → language, subtitles, schemes → build
        scriptBlock(this)
        if (!voice) button(t("🎙 Записать озвучку (необязательно)", "🎙 Record a voice-over (optional)"), style = if (script().isNotEmpty()) BtnStyle.FILLED else BtnStyle.TONAL) { startVoice() }
        spinner(t("Язык озвучки, субтитров и схем (общий со Студией)", "Language of the voice-over, subtitles and schemes (shared with the Studio)"),
            Studio.VOICE_LANGS.map { it to Studio.langName(it) }, lang()) { StudioPrefs.voiceLang = it }
        // subtitles follow the voice-over: recognised once, by a tap (the recogniser may need the network)
        if (ov.subs && words.isEmpty()) {
            if (voice) button(t("💬 Распознать речь для субтитров", "💬 Recognise the speech for subtitles"), style = BtnStyle.TONAL) { addSubs() }
            else muted(t("Субтитры появятся по вашей озвучке — запишите её выше.", "Subtitles come from your voice-over — record it above."))
        }
        muted(t("Сохраняется в Галерею → Фильмы/Lumen. Озвучка не обязательна.", "Saved to Gallery → Movies/Lumen. A voice-over is optional."))
        val video = lastVideo()
        val fresh = video != null && Prefs.videoSig == StudioMix.signature(items, Prefs.format)
        if (voice) check(t("🎙 Со своей озвучкой (иначе только картинка)", "🎙 With my voice-over (otherwise the picture only)"), withVoice) {
            withVoice = it
            render()
        }
        if (fresh) button(t("🎬 Сохранить заново", "🎬 Save again"), style = BtnStyle.TONAL) { build(false, mute = voice && !withVoice) }
        else button(t("🎬 Сохранить видео", "🎬 Save the video"), primary = true) { build(false, mute = voice && !withVoice) }
        if (video != null) {
            add(text(ctx, "✅ " + t("Сохранено в галерею: ", "Saved to the gallery: ") + Prefs.savedAs.ifBlank { video.name } +
                if (Prefs.videoVoice) t(" · с озвучкой", " · with the voice-over") else t(" · без озвучки", " · without a voice-over"), 14f, bold = true), 12)
            if (!fresh) muted(t("Раскадровка менялась после сохранения — сохраните видео заново.",
                "The storyboard changed after saving — save the video again."))
            row(
                btn(ctx, t("▶ Открыть", "▶ Open"), primary = fresh) { openVideo(video) },
                btn(ctx, t("↗ Поделиться", "↗ Share"), style = BtnStyle.TONAL) { ReportKit.share(this@StudioMixActivity, video, false, video.name, "video/mp4") },
                iconBtn(ctx, "⋯") { videoMore(video) }.apply { contentDescription = t("Ещё: копия в папку, другой ИИ", "More: a copy to a folder, another AI") }
            )
            row(
                btn(ctx, t("✨ Оформить", "✨ Style it"), style = BtnStyle.TONAL) { lookSheet(video) }.apply {
                    contentDescription = t("Оформить как в Редакторе: тема, субтитры, плашки", "Style it like the Editor: theme, subtitles, plates")
                },
                btn(ctx, t("✏️ В Редактор", "✏️ To the Editor"), style = BtnStyle.TEXT) { StudioEditorActivity.open(this@StudioMixActivity, video, words) }
            )
        }
    }

    /**
     * "✨ Style it": the look of the Editor in one step — a theme (colours, font, plates), subtitles by the words of the
     * voice-over and the plate opacity; the video opens as an Editor project with all of it, ready to export or edit.
     */
    private fun lookSheet(video: File) = bottomSheet(t("✨ Оформить как в Редакторе", "✨ Style it like the Editor")) { dismiss ->
        val p = Lumen.prefs
        var theme = p.getString("studio_mix_look_theme", "glass") ?: "glass"
        var subs = p.getInt("studio_mix_look_subs", if (words.isNotEmpty()) 3 else 0)
        var place = p.getInt("studio_mix_look_place", 0).coerceIn(0, EditCaptions.PLACES.lastIndex)
        var plate = p.getInt("studio_mix_look_plate", -1)
        var font = p.getString("studio_mix_look_font", "") ?: ""
        var motion = p.getString("studio_mix_look_motion", "karaoke") ?: ""
        // the same built-in caption style as «Поверх видео» unless one was chosen here
        var tstyle = p.getString("studio_mix_look_tpl", null)?.takeIf { it.isEmpty() || TextStyles.of(it) != null } ?: over().subStyle
        add(text(ctx, t("Тема", "Theme"), Type.LABEL, bold = true, color = Palette.MUTED), 0)
        add(choiceChips(ctx, EditThemes.PRESETS.map { it.id to it.name }, theme) { theme = it }, 4)
        add(text(ctx, t("Субтитры", "Subtitles"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        if (words.isEmpty()) muted(t("Нужна озвучка с расшифровкой («💬 Субтитры» выше) — иначе без субтитров.", "A voice-over with recognised words is needed (\"💬 Subtitles\" above) — otherwise none."))
        else {
            add(choiceChips(ctx, listOf(0 to t("Нет", "None"), 1 to t("По слову", "Word by word"), 3 to t("3 слова", "3 words"), 6 to t("Строка", "A line")), subs) { subs = it }, 4)
            add(choiceChips(ctx, listOf(0 to t("Внизу", "Bottom"), 1 to t("Центр", "Centre"), 2 to t("Вверху", "Top")), place) { place = it }, 4)
            add(text(ctx, t("Движение по словам", "Motion by the words"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, LumenFonts.motions(), motion) { motion = it }, 4)
            fun styleText() = TextStyles.of(tstyle)?.let { t("Готовый стиль: ", "Ready style: ") + TextStyles.name(it, I18n.ru) + t(" (его шрифт и движение)", " (its font and motion)") }
                ?: t("Готовый стиль: нет — тема, шрифт и движение выше", "Ready style: none — the theme, font and motion above")
            val info = add(text(ctx, styleText(), 13f, color = Palette.MUTED), 8)
            add(rowOf(ctx, btn(ctx, t("🎨 Готовые стили (100)", "🎨 Ready styles (100)"), false, BtnStyle.TONAL) {
                TplPicker.texts(this@StudioMixActivity, cacheDir, StudioEditStore.styleDir(this@StudioMixActivity), tstyle) { st ->
                    tstyle = st.id
                    info.text = styleText()
                }
            }, btn(ctx, "✕", false, BtnStyle.TEXT) {
                tstyle = ""
                info.text = styleText()
            }), 4)
        }
        add(text(ctx, t("Шрифт", "Font"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(LumenFonts.chips(ctx, font) { font = it }, 4)
        add(text(ctx, t("Плашки под текстом", "Plates under the text"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(-1 to t("Как в теме", "As the theme"), 0 to t("Нет", "None"), 40 to t("Лёгкие", "Light"), 70 to t("Средние", "Medium"), 100 to t("Плотные", "Solid")), plate) { plate = it }, 4)
        button(t("✨ Открыть оформленным", "✨ Open styled"), primary = true) {
            p.edit().putString("studio_mix_look_theme", theme).putInt("studio_mix_look_subs", subs).putInt("studio_mix_look_place", place)
                .putInt("studio_mix_look_plate", plate).putString("studio_mix_look_font", font).putString("studio_mix_look_motion", motion)
                .putString("studio_mix_look_tpl", tstyle).apply()
            dismiss()
            val ws = words
            val app = applicationContext
            toast(t("Готовлю проект Редактора…", "Preparing the Editor project…"))
            Lumen.async({
                var pr = StudioEditStore.create(app, video, ws)
                val th0 = EditThemes.preset(theme) ?: EditThemes.PRESETS.first()
                val th1 = if (font.isNotEmpty()) th0.copy(font = font) else th0
                val th = if (plate >= 0) th1.copy(plateAlpha = plate, plate = if (plate == 0) "none" else th1.plate.takeIf { it != "none" } ?: "solid") else th1
                val (fw, fh) = EditCanvas.frame(pr)
                var blocks = pr.blocks
                if (subs > 0 && ws.isNotEmpty()) blocks = EditCaptions.withSubs(blocks, ws, subs, if (th.plate == "none") "shadow" else "plate",
                    EditCaptions.PLACES[place], if (subs == 1) "pop" else "fade", fw, fh, pr.durMs, motion, th.font.takeIf { LumenFonts.isBuiltIn(it) }.orEmpty())
                blocks = EditThemes.apply(blocks, th)
                // a built-in caption style goes over the theme on the subtitles (its own font, colours and motion)
                TextStyles.of(tstyle)?.let { st ->
                    blocks = blocks.map { b -> if (EditCaptions.isSub(b)) b.copy(look = st.look, over = null, animIn = st.anim, motion = if (b.wordMs.isNotEmpty()) st.motion else b.motion) else b }
                }
                pr = pr.copy(blocks = blocks, theme = EditThemes.json(th).toString())
                StudioEditStore.save(app, pr)
                pr
            }, { e -> if (!isDestroyed) err(e) }) { pr -> if (!isDestroyed) StudioEditorActivity.openProject(this@StudioMixActivity, pr.id) }
        }
    }

    /** Rarely needed actions with the saved video. */
    private fun videoMore(video: File) = bottomSheet(t("Видео", "Video")) { dismiss ->
        button(t("💾 Сохранить копию в папку…", "💾 Save a copy to a folder…"), style = BtnStyle.TONAL) { dismiss(); saveCopy(video) }
        if (Prefs.videoVoice) button(t("🤖 Отдать другому ИИ на монтаж (видео + промпт)", "🤖 Give it to another AI to edit (video + prompt)"),
            style = BtnStyle.TEXT) { dismiss(); sendToAi(video) }
    }

    private fun rendering() = StudioJob.running && Prefs.pending.isNotEmpty()

    /** Length of the recorded voice-over from its WAV header; 0 when there is none. */
    private fun voiceMs(): Long = runCatching {
        if (!voiceFile.exists()) return 0
        val h = ByteArray(44)
        voiceFile.inputStream().use { if (it.read(h) < 44) return 0 }
        StudioMix.wavMs(h, voiceFile.length())
    }.getOrDefault(0)

    private fun clipCaps(): Map<String, Long> = readyClips().associate { it.id to it.lenMs }

    /**
     * Fitting the storyboard to the voice-over: shown when their lengths differ (pieces stretched or squeezed, or
     * cut / continued by repeats) and, with recognised words, seams moved onto words.
     */
    private fun fitVoiceBlock(c: Col, voice: Long) = with(c) {
        if (items.isEmpty() || voice <= 0) return@with
        val total = StudioMix.totalMs(items)
        val diff = voice - total
        if (kotlin.math.abs(diff) > FIT_TOL_MS) {
            add(text(ctx, t("⚠ Ролик ", "⚠ Video ") + StudioCuts.stamp(total) + t(", озвучка ", ", voice-over ") + StudioCuts.stamp(voice), 14f, bold = true,
                color = Palette.ACCENT), 8)
            muted(if (diff < 0) t("Ролик длиннее: в конце будет картинка без голоса. Подогнать под озвучку:", "The video is longer: the end has no voice. Fit to the voice-over:")
            else t("Озвучка длиннее: её конец обрежется. Подогнать под озвучку:", "The voice-over is longer: its end is cut. Fit to the voice-over:"))
            row(
                btn(ctx, if (diff < 0) t("⇔ Сжать куски", "⇔ Squeeze pieces") else t("⇔ Растянуть куски", "⇔ Stretch pieces"), style = BtnStyle.TONAL) { fitVoice(true) },
                btn(ctx, if (diff < 0) t("✂ Обрезать конец", "✂ Cut the end") else t("＋ Дополнить повтором", "＋ Continue by repeats"), style = BtnStyle.TEXT) { fitVoice(false) }
            )
            if (diff > 0) button(t("🧩 Добрать новыми кусками до ${StudioCuts.stamp(voice)}", "🧩 Fill with new pieces up to ${StudioCuts.stamp(voice)}"),
                style = BtnStyle.TONAL) { topUp(voice) }
        } else muted(t("✓ Длина ролика совпадает с озвучкой", "✓ The video length matches the voice-over"))
        if (items.size > 1 && words.isNotEmpty()) {
            val on = StudioMix.seamsOnWords(items, words)
            button(t("🎯 Склейки на словах", "🎯 Cuts on words") + " ($on/${items.size - 1})", style = BtnStyle.TEXT) {
                run {
                    val next = StudioMix.snapToWords(items, words, clipCaps())
                    if (next == items) toast(t("Ближе к словам склейки не сдвинуть", "The cuts cannot get closer to words"))
                    else change(next, t("Склеек на словах: ${StudioMix.seamsOnWords(next, words)}", "Cuts on words: ${StudioMix.seamsOnWords(next, words)}"))
                }
            }
        }
    }

    /** The storyboard made as long as the voice-over: pieces scaled ([scale]) or cut / continued by repeats. */
    private fun fitVoice(scale: Boolean) {
        val voice = voiceMs()
        if (voice <= 0 || items.isEmpty()) return
        var next = if (scale) StudioMix.scaleTo(items, voice, clipCaps()) else StudioMix.loopTo(items, voice)
        // clips too short to stretch that far: the rest is continued by repeats
        if (scale && StudioMix.totalMs(next) < voice - FIT_TOL_MS) next = StudioMix.loopTo(next, voice)
        if (words.isNotEmpty()) next = StudioMix.snapToWords(next, words, clipCaps())
        change(next, t("Ролик подогнан под озвучку: ", "Video fitted to the voice-over: ") + StudioCuts.stamp(StudioMix.totalMs(next)))
    }

    /** A copy of the built video where the user picks (Downloads, a card, a cloud folder). */
    private fun saveCopy(f: File) {
        pendingCopy = f
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE, f.name), REQ_SAVE)
        } catch (e: Exception) {
            err(e)
        }
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

    /** The plan of the video: every element is a clip scene; schemes and subtitles where they were added. */
    private fun plan(): StudioPlan {
        val times0 = StudioMix.times(items)
        val ranges = if (words.isEmpty()) items.map { IntRange.EMPTY } else StudioMix.wordsIn(words, times0)
        val times = StudioMix.times(items, ranges)
        val chosen = StudioMix.chosen(items, Prefs.onlyMarked).toSet()
        val ov = over()
        val subs = ov.subs && words.isNotEmpty()
        val words1 = TextStyles.of(ov.subStyle)?.words ?: 1
        val caps = ranges.mapIndexed { i, r -> if (subs && i in chosen && !r.isEmpty()) Studio.captions(words, r, maxWords = words1) else emptyList() }
        val notes = notes()
        val scenes = items.mapIndexed { i, it ->
            val say = if (!ranges[i].isEmpty()) ranges[i].joinToString(" ") { k -> words[k].text } else notes[it.clipId].orEmpty()
            if (it.scheme != null) SScene(say, kind = "scheme", scheme = it.scheme, clip = it.clipId) else SScene(say, clip = it.clipId)
        }
        return StudioPlan(
            script = SScript("", scenes), words = words, times = times, captions = caps, totalMs = StudioMix.totalMs(items),
            style = StudioStyle.VIDEO, accent = StudioPrefs.accent, overlay = ov.overlay(), zooms = false, transitions = false,
            clipScenes = items.indices.toSet(), layers = if (ov.schemes) StudioLayers.ALL else StudioLayers.SUBS, overScale = ov.overScale(),
            schemeStyle = StudioOverlay.schemeLook(ov),
            schemePlace = ov.schemePlace(), blockBg = ov.bg, fullMode = ov.fullMode(), captionStyle = TextStyles.of(ov.subStyle)
        )
    }

    private fun readVoice(): StudioAudio.Pcm? = voiceFile.takeIf { it.exists() }?.let { f ->
        Studio.readWav(f.readBytes())?.let { (data, rate) -> StudioAudio.Pcm(data, rate) }
    }

    /** Builds the video in the background service; it is saved to the gallery when done. */
    private fun build(forRecording: Boolean, mute: Boolean = false) {
        if (StudioJob.running) {
            toast(t("Уже собирается другое видео Студии", "Another Studio video is being built"))
            return
        }
        // «🧩 Инфосхемы» chosen and none made for this storyboard yet: the AI composes them, then the build goes on
        if (!forRecording && composeIfNeeded(false) { build(false, mute) }) return
        val ready = readyClips()
        if (items.isEmpty() || ready.isEmpty()) return
        val plan = plan()
        val voice = (if (forRecording || mute) null else readVoice())
            ?: StudioAudio.Pcm(ShortArray((plan.totalMs * 44_100 / 1000).toInt() + 1), 44_100)
        val (w, h) = aspect().size(StudioPrefs.quality)
        val day = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "$FILE_PREFIX$day.mp4"
        val bg = StudioBg(null, null, emptyList(), StudioMix.slots(items, ready.associateBy { it.id }), ready.associate { it.id to File(clipsDir, it.file) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
        }
        Prefs.pending = (if (voiceFile.exists() && !forRecording && !mute) "v" else "-") + StudioMix.signature(items, Prefs.format)
        recordAfter = forRecording
        StudioService.start(this, StudioJob.Request(plan, voice, ReportKit.shareFile(this, name), name, w, h, bg, null, 0f, false, sfx = false))
        render()
    }

    /** Progress and the end of the build (main thread). */
    private fun onJob() {
        if (StudioJob.running) {
            renderBar?.let { (tv, bar) ->
                tv.text = StudioJob.message
                bar.showProgress(StudioJob.message)
            }
            return
        }
        if (Prefs.pending.isEmpty()) return
        val ok = adoptResult()
        render()
        StudioJob.error?.let { e ->
            StudioJob.error = null
            err(IllegalStateException(e))
        }
        if (ok && recordAfter) {
            recordAfter = false
            lastVideo()?.let { record(it) }
        }
    }

    /** Takes over the video of a mix build that has ended; false when there is none. */
    private fun adoptResult(): Boolean {
        val pending = Prefs.pending
        if (pending.isEmpty()) return false
        val r = StudioJob.result?.takeIf { it.exists() && it.name.startsWith(FILE_PREFIX) }
        Prefs.pending = ""
        if (r == null) return false
        Prefs.video = r.absolutePath
        Prefs.savedAs = StudioJob.savedAs
        Prefs.videoVoice = pending.startsWith("v")
        Prefs.videoSig = pending.drop(1)
        return true
    }

    private fun openVideo(f: File) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(StudioJob.uriOf(this, f), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            ReportKit.share(this, f, false, f.name, "video/mp4")
        }
    }

    /** The video with a ready editing prompt to an AI app on the phone (Claude or another); the prompt is also copied. */
    private fun sendToAi(f: File) {
        val prompt = StudioMix.montagePrompt(Prefs.format, items, notes(), StudioMix.totalMs(items), lang() == "ru")
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", prompt))
        }
        val uri = StudioJob.uriOf(this, f)
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, prompt)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("video", uri)
        try {
            startActivity(Intent.createChooser(send, t("Отправить в ИИ", "Send to AI")))
            toast(t("Промпт для монтажа ещё и скопирован: вставьте, если приложение его не подставило", "The editing prompt is also copied: paste it if the app did not fill it in"))
        } catch (e: Exception) {
            err(e)
        }
    }


    // ---------- voice-over text and teleprompter ----------

    private fun script(): String = runCatching { scriptFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull().orEmpty()

    private fun saveScript(text: String) {
        runCatching { if (text.isBlank()) scriptFile.delete() else scriptFile.writeText(text.trim()) }.onFailure { err(it) }
    }

    /** Length the voice-over should take: the storyboard, else the length chosen before cutting, else 30 s. */
    private fun scriptMs(): Long = StudioMix.totalMs(items).takeIf { it > 0 } ?: Prefs.targetMs.takeIf { it > 0 } ?: 30_000L

    /** "📝 Voice-over text": write it or let the AI compose it by the storyboard; it is on the teleprompter while recording. */
    private fun scriptBlock(c: Col) = with(c) {
        add(text(ctx, t("📝 Текст озвучки", "📝 Voice-over text"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val sc = script()
        if (sc.isEmpty()) {
            muted(t("Напишите, что скажете, или пусть ИИ составит текст по раскадровке — при записи он будет на суфлёре.",
                "Write what you will say or let the AI compose it by the storyboard — it is on the teleprompter while recording."))
            row(
                btn(ctx, t("✨ Составить с ИИ", "✨ Compose with AI"), style = BtnStyle.TONAL) { aiScriptSheet() },
                btn(ctx, t("✎ Написать", "✎ Write"), style = BtnStyle.TEXT) { scriptSheet() }
            )
            return@with
        }
        add(text(ctx, if (sc.length > 240) sc.take(240).trimEnd() + "…" else sc, 14f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg(Palette.SURFACE, 12f, this@StudioMixActivity)
            setOnClickListener { scriptSheet() }
        }, 4)
        val n = StudioMix.scriptLines(sc).sumOf { it.size }
        val readMs = n * 60_000L / StudioPrefs.prompterWpm.coerceAtLeast(1)
        val need = scriptMs()
        muted(t("$n слов · ≈${StudioCuts.stamp(readMs)} при ${StudioPrefs.prompterWpm} сл/мин · микс ${StudioCuts.stamp(need)}",
            "$n words · ≈${StudioCuts.stamp(readMs)} at ${StudioPrefs.prompterWpm} wpm · the mix is ${StudioCuts.stamp(need)}") +
            if (readMs > need * 6 / 5) t(" — длинновато, сократите или ускорьте суфлёр", " — rather long: shorten it or speed up the teleprompter") else "")
        row(
            btn(ctx, t("✎ Править", "✎ Edit"), style = BtnStyle.TONAL) { scriptSheet() },
            btn(ctx, t("✨ Заново с ИИ", "✨ Again with AI"), style = BtnStyle.TEXT) { aiScriptSheet() },
            iconBtn(ctx, "✕") {
                val old = sc
                undoable(t("Текст озвучки убран", "Voice-over text removed"), apply = { saveScript(""); render() }, revert = { saveScript(old); render() })
            }.apply { contentDescription = t("Убрать текст озвучки", "Remove the voice-over text") }
        )
        check(t("Суфлёр: показывать текст при записи", "Teleprompter: show the text while recording"), StudioPrefs.prompter) {
            StudioPrefs.prompter = it
            render()
        }
        if (StudioPrefs.prompter) spinner(t("Скорость подсветки слов, слов в минуту", "Word highlight speed, words per minute"),
            StudioPrompter.SPEEDS.map { it to it.toString() }.let { l -> if (StudioPrefs.prompterWpm in StudioPrompter.SPEEDS) l else l + (StudioPrefs.prompterWpm to StudioPrefs.prompterWpm.toString()) },
            StudioPrefs.prompterWpm) {
            StudioPrefs.prompterWpm = it
            render()
        }
    }

    private fun scriptSheet() = bottomSheet(t("📝 Текст озвучки", "📝 Voice-over text")) { dismiss ->
        muted(t("Абзац на план. Что и когда подсветил суфлёр, станет таймингом субтитров — распознавать речь не нужно.",
            "A paragraph per shot. What the teleprompter lit up and when times the subtitles — no speech recognition needed."))
        val e = edit(t("Текст, который вы прочитаете", "The text you will read"), script(), multiline = true)
        e.minLines = 6
        e.maxLines = 14
        button(t("💾 Сохранить", "💾 Save"), primary = true) {
            saveScript(e.text.toString())
            dismiss()
            render()
        }
        button(t("✨ Составить с ИИ", "✨ Compose with AI"), style = BtnStyle.TEXT) {
            saveScript(e.text.toString())
            dismiss()
            aiScriptSheet()
        }
    }

    private fun aiScriptSheet() {
        if (!hasAi()) {
            toast(t("Подключите ИИ в настройках", "Connect an AI in the settings"))
            return
        }
        bottomSheet(t("✨ Текст озвучки с ИИ", "✨ Voice-over text with AI")) { dismiss ->
            muted(t("ИИ напишет текст на ${StudioCuts.stamp(scriptMs())} по планам раскадровки (по заметкам и названиям клипов), язык — ${Studio.langName(lang())}.",
                "The AI writes ${StudioCuts.stamp(scriptMs())} of text by the storyboard shots (clip notes and titles), language — ${Studio.langName(lang())}."))
            val topicE = edit(t("О чём ролик (тема, идея)", "What the video is about (topic, idea)"), Prefs.scriptTopic, multiline = true)
            val wishE = edit(t("Пожелание: тон, призыв, что упомянуть", "Wish: tone, call to action, what to mention"), Prefs.scriptWish, multiline = true)
            val old = script()
            if (old.isNotEmpty()) muted(t("Текущий текст будет учтён как черновик.", "The current text is used as a draft."))
            button(t("✨ Составить", "✨ Compose"), primary = true) {
                Prefs.scriptTopic = topicE.text.toString().trim()
                Prefs.scriptWish = wishE.text.toString().trim()
                dismiss()
                writeScript(old)
            }
        }
    }

    /** The AI writes the voice-over text by the storyboard shots; the old text (if any) is its draft. */
    private fun writeScript(draft: String) {
        val total = scriptMs()
        val sec = ((total + 500) / 1000).toInt().coerceAtLeast(3)
        val shots = if (items.isEmpty()) notes().values.filter { it.isNotBlank() }.mapIndexed { i, s -> "${i + 1}. ${s.take(160)}" }.joinToString("\n")
        else StudioMix.scriptShots(items, notes())
        val format = if (aspect() == StudioAspect.PORTRAIT) t("видео-микса 9:16 (Shorts / Stories)", "a 9:16 video mix (Shorts / Stories)")
        else t("горизонтального видео-микса 16:9", "a horizontal 16:9 video mix")
        val vars = mapOf(
            "lang" to Studio.langName(lang()), "format" to format, "seconds" to sec.toString(),
            "words" to (sec * StudioPrefs.prompterWpm / 60).coerceAtLeast(5).toString(),
            "shots" to shots.ifBlank { "—" },
            "topic" to Prefs.scriptTopic.let { if (it.isNotEmpty()) "Тема: $it\n" else "" },
            "wish" to (Prefs.scriptWish.let { if (it.isNotEmpty()) "Пожелание автора: $it\n" else "" } +
                if (draft.isNotEmpty()) "Черновик автора, сохрани его мысли:\n${draft.take(3000)}\n" else "")
        )
        busy = "script"
        cancel = false
        render()
        BgTasks.run(this, TASK + "script", t("Микс: ИИ пишет текст озвучки", "Mix: AI writes the voice-over text"), BgTasks.reopen(this), {
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.smartModel, "You write voice-over texts for short videos. Answer with the text only.",
                Prompts.fill("studio_mix_script", vars), maxTokens = 400 + sec * 12, task = "studio_mix_script", pid = "studio")
            if (cancel) throw java.util.concurrent.CancellationException()
            StudioMix.cleanScript(r.text).ifBlank { throw LlmException(t("ИИ не вернул текст", "AI returned no text"), r.text) }
        }, { e ->
            busy = ""
            if (isDestroyed) return@run
            render()
            err(e)
        }, { cancel = true }) { text ->
            saveScript(text)
            busy = ""
            if (isDestroyed) return@run
            render()
            toast(t("Текст готов: проверьте и запишите озвучку с суфлёром", "The text is ready: check it and record with the teleprompter"))
        }
    }

    // ---------- voice-over ----------

    /** Recording over the mix: the built video plays without sound while the microphone records. */
    private fun startVoice() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val v = lastVideo()
        if (v != null && Prefs.videoSig == StudioMix.signature(items, Prefs.format)) record(v)
        else {
            toast(t("Сначала соберу микс, затем откроется запись", "Building the mix first, then the recording opens"))
            build(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
            else toast(t("Без доступа к микрофону запись невозможна", "Recording needs microphone access"))
        }
    }

    private fun record(video: File) {
        if (isFinishing || isDestroyed) return
        val total = StudioMix.totalMs(items)
        var done = false
        lateinit var state: TextView
        lateinit var go: Button
        lateinit var player: VideoView
        var close: () -> Unit = {}
        // teleprompter: the script lit word by word by the recorder clock; what lit up when times the subtitles
        val lines = if (StudioPrefs.prompter) StudioMix.scriptLines(script()) else emptyList()
        val clock = lines.flatten().takeIf { it.isNotEmpty() }?.let { PrompterClock(it, StudioPrefs.prompterWpm) }
        val (pBody, pRanges) = StudioPrompter.layout(lines)
        var recMs = 0L
        var pText: TextView? = null
        var pScroll: ScrollView? = null
        var pSpeed: TextView? = null

        fun paint() {
            val c = clock ?: return
            pSpeed?.text = t("${c.wpm} сл/мин", "${c.wpm} wpm") + if (c.paused) t(" · пауза", " · paused") else ""
            val tv = pText ?: return
            val r = pRanges.getOrNull(c.index)
            if (r == null) {
                tv.text = pBody
                return
            }
            val sp = android.text.SpannableString(pBody)
            if (r.first > 0) sp.setSpan(android.text.style.ForegroundColorSpan(Palette.MUTED), 0, r.first, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.BackgroundColorSpan(Palette.ACCENT), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.ForegroundColorSpan(Palette.ON_ACCENT), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sp
            val sv = pScroll ?: return
            tv.post {
                val lay = tv.layout ?: return@post
                val y = lay.getLineTop(lay.getLineForOffset(r.first)) + tv.paddingTop
                sv.smoothScrollTo(0, (y - sv.height / 3).coerceAtLeast(0))
            }
        }

        fun finishTake(keep: Boolean) {
            val rec = recorder ?: return
            recorder = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { player.pause() }
            val data = rec.stop()
            if (!keep || data.size < rec.rate / 2) return
            runCatching {
                val body = java.nio.ByteBuffer.allocate(data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                body.asShortBuffer().put(data)
                voiceFile.outputStream().use {
                    it.write(Studio.wavHeader(data.size * 2, rec.rate))
                    it.write(body.array())
                }
            }.onFailure { err(it); return }
            // the old subtitles belong to the old take
            wordsFile.delete()
            words = emptyList()
            Prefs.subs = false
            done = true
            close()
            render()
            // read by the teleprompter: its log times the words, the subtitles need no recognition
            val log = clock?.log?.toList().orEmpty()
            if (clock != null && log.isNotEmpty()) Lumen.async({
                StudioPrompter.words(clock.tokens, log, Studio.speechSpans(data, rec.rate), Studio.durationMs(data.size, rec.rate))
            }, { L.w("studio", "mix prompter words: ${it.message}") }) { w ->
                if (w.isEmpty() || isDestroyed) return@async
                words = w
                runCatching { wordsFile.writeText(StudioMix.wordsJson(w)) }
                render()
            }
            val diff = voiceMs() - StudioMix.totalMs(items)
            toast(if (kotlin.math.abs(diff) > FIT_TOL_MS) t("Озвучка записана. Длина ролика другая — подгоните его в «4. Озвучка и сборка»", "Voice-over recorded. The video length differs — fit it in \"4. Video\"")
            else t("Озвучка записана. Нажмите «Собрать видео с озвучкой»", "Voice-over recorded. Tap \"Build the video with the voice-over\""))
        }

        bottomSheet(t("🎙 Озвучка поверх микса", "🎙 Voice-over over the mix"), onDismiss = { if (!done) finishTake(false) }) { dismiss ->
            close = dismiss
            muted(t("Видео играет без звука, микрофон пишет. Запись остановится в конце микса (${StudioCuts.stamp(total)}).",
                "The video plays without sound while the microphone records. Recording stops at the end of the mix (${StudioCuts.stamp(total)})."))
            player = VideoView(ctx)
            val hgt = (resources.displayMetrics.heightPixels * (if (clock != null) 0.26f else 0.45f)).toInt()
            add(FrameLayout(ctx).apply {
                addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }, 8).layoutParams.height = hgt
            player.setVideoPath(video.absolutePath)
            player.setOnPreparedListener { mp ->
                mp.setVolume(0f, 0f)
                player.seekTo(1)
            }
            player.setOnCompletionListener { finishTake(true) }
            state = add(text(ctx, t("Готово к записи", "Ready to record"), 14f, bold = true), 8)
            if (clock != null) {
                pSpeed = text(ctx, "", 14f, bold = true).apply { gravity = Gravity.CENTER }
                fun speed(d: Int) {
                    clock.tick(recMs)
                    clock.wpm += d
                    StudioPrefs.prompterWpm = clock.wpm
                    paint()
                }
                val pause = btn(ctx, "⏸", style = BtnStyle.TONAL) {}
                pause.setOnClickListener {
                    clock.togglePause(recMs)
                    pause.text = if (clock.paused) "▶" else "⏸"
                    paint()
                }
                pause.contentDescription = t("Пауза суфлёра", "Pause the teleprompter")
                row(
                    btn(ctx, "−", style = BtnStyle.TONAL) { speed(-StudioPrompter.STEP_WPM) }.apply { contentDescription = t("Медленнее", "Slower") },
                    pSpeed!!,
                    btn(ctx, "＋", style = BtnStyle.TONAL) { speed(StudioPrompter.STEP_WPM) }.apply { contentDescription = t("Быстрее", "Faster") },
                    pause
                )
                val tv = text(ctx, pBody, 22f, bold = true).apply { setPadding(dp(16), dp(40), dp(16), dp(160)) }
                // tap on a word: the reader is there now
                tv.setOnTouchListener { v, e ->
                    if (e.action == android.view.MotionEvent.ACTION_UP && recorder != null) {
                        val i = StudioPrompter.wordAt(pRanges, (v as TextView).getOffsetForPosition(e.x, e.y))
                        if (i >= 0 && clock.jump(i, recMs)) paint()
                        v.performClick()
                    }
                    true
                }
                pText = tv
                val sv = ScrollView(ctx).apply {
                    addView(tv)
                    background = roundBg(Palette.SURFACE, 12f, this@StudioMixActivity)
                }
                pScroll = sv
                add(sv, 8).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
                paint()
            } else if (StudioPrefs.prompter) muted(t("Суфлёр пуст: добавьте «📝 Текст озвучки» в карточке 4.", "The teleprompter is empty: add the \"📝 Voice-over text\" in card 4."))
            go = button(t("⏺ Начать запись", "⏺ Start recording"), primary = true) {
                if (recorder != null) {
                    finishTake(true)
                    return@button
                }
                val rec = StudioRecorder { level, ms ->
                    val bars = "▮".repeat((level * 12).toInt().coerceIn(0, 12))
                    state.text = "⏺ ${StudioCuts.stamp(ms)} / ${StudioCuts.stamp(total)}  $bars"
                    recMs = ms
                    if (clock?.tick(ms) == true) paint()
                    if (ms >= total + 300) finishTake(true)
                }
                try {
                    rec.start()
                } catch (e: Exception) {
                    err(e)
                    return@button
                }
                recorder = rec
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                player.seekTo(0)
                player.start()
                go.text = t("⏹ Стоп", "⏹ Stop")
            }
            button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { dismiss() }
        }
    }

    // ---------- subtitles and schemes ----------

    private fun addSubs() {
        if (!voiceFile.exists()) {
            toast(t("Субтитры делаются по своей озвучке: сначала «🎙 Начать озвучку»", "Subtitles follow your voice-over: tap \"🎙 Start the voice-over\" first"))
            return
        }
        if (words.isNotEmpty()) {
            Prefs.subs = true
            render()
            toast(t("Субтитры добавлены: соберите видео", "Subtitles added: build the video"))
            return
        }
        if (!StudioRecognizer.available(this)) {
            toast(t("Распознавание речи нужно Android 13+ с сервисом распознавания", "Speech recognition needs Android 13+ with a recognition service"))
            return
        }
        busy = "subs"
        cancel = false
        render()
        fun end(msg: String?) {
            busy = ""
            if (isDestroyed) return
            render()
            msg?.let { toast(it) }
        }
        Lumen.async({ readVoice() ?: error(t("Озвучка не читается", "The voice-over cannot be read")) }, { e -> end(e.message) }) { pcm ->
            val speech = Studio.speechSpans(pcm.data, pcm.rate)
            if (speech.isEmpty()) return@async end(t("В озвучке не слышно речи", "No speech is heard in the voice-over"))
            StudioRecognizer.recognize(this, pcm.data, pcm.rate, Studio.groupSpans(speech), Studio.recogLang(lang()),
                { i, n -> status?.first?.text = t("Распознавание $i/$n", "Recognising $i/$n") }, { cancel || isDestroyed },
                { msg -> status?.first?.text = msg }) { res ->
                if (cancel) return@recognize end(null)
                val w = Studio.alignPieces(res, speech)
                if (w.isEmpty()) return@recognize end(t("Речь не распозналась", "The speech was not recognised"))
                words = w
                runCatching { wordsFile.writeText(StudioMix.wordsJson(w)) }
                Prefs.subs = true
                end(t("Субтитры добавлены: соберите видео", "Subtitles added: build the video"))
            }
        }
    }

    /** The AI writes info-schemes for the chosen elements (by the voice-over words or the clip notes). */
    private fun addSchemes(then: (() -> Unit)? = null) {
        val sel = StudioMix.chosen(items, Prefs.onlyMarked)
        if (sel.isEmpty()) {
            toast(t("Отметьте элементы на раскадровке", "Tick elements on the storyboard"))
            then?.invoke()
            return
        }
        val ranges = if (words.isEmpty()) items.map { IntRange.EMPTY } else StudioMix.wordsIn(words, StudioMix.times(items))
        val notes = notes()
        val keys = sel.map { items[it].key }
        val selRanges = sel.map { ranges[it] }
        val scenes = sel.map { i -> SScene(if (!ranges[i].isEmpty()) ranges[i].joinToString(" ") { words[it].text } else notes[items[i].clipId].orEmpty()) }
        val format = if (aspect() == StudioAspect.PORTRAIT) t("видео-микса 9:16 (Shorts / Stories)", "a 9:16 video mix (Shorts / Stories)")
        else t("горизонтального видео-микса 16:9", "a horizontal 16:9 video mix")
        val vars = mapOf(
            "format" to format, "lang" to Studio.langName(lang()),
            "max" to (if (Prefs.onlyMarked) sel.size.coerceAtMost(12) else over().maxSchemes(sel.size)).toString(),
            "wish" to StudioPrefs.schemeWish.let { if (it.isNotEmpty()) "Пожелание автора: $it" else "" }, "protocol" to StudioScheme.PROTOCOL,
            "scenes" to StudioScheme.scenesBlock(scenes, words, selRanges)
        )
        busy = "schemes"
        cancel = false
        render()
        BgTasks.run(this, TASK + "schemes", t("Микс: ИИ составляет инфоблоки", "Mix: AI composes info-blocks"), BgTasks.reopen(this), {
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.smartModel, "You are an infographic designer for videos. Answer with JSON only.",
                Prompts.fill("studio_schemes", vars), json = true, maxTokens = 900 + sel.size * 300, task = "studio_schemes", pid = "studio")
            if (cancel) throw java.util.concurrent.CancellationException()
            val list = StudioScheme.parseAnswer(r.text)
                ?: throw LlmException(t("ИИ не вернул схемы в нужном формате", "AI did not return schemes in the expected format"), r.text)
            val found = HashMap<String, SScheme>()
            for ((n, o) in list) {
                val k = n - 1
                if (k !in sel.indices) continue
                val range = selRanges[k].takeIf { !it.isEmpty() }
                val s = StudioScheme.fromJson(o) ?: continue
                if (StudioScheme.problems(s, range).isEmpty()) found[keys[k]] = StudioScheme.repair(s, range)
            }
            found
        }, { e ->
            busy = ""
            if (isDestroyed) return@run
            render()
            if (then != null && e !is java.util.concurrent.CancellationException) then() else err(e)
        }, { cancel = true }) { found ->
            // the storyboard may have changed meanwhile: schemes go to their elements by key
            val now = if (mixFile.exists()) StudioMix.fromJson(mixFile.readText()) else items
            saveItems(now.map { found[it.key]?.let { s -> it.copy(scheme = s) } ?: it })
            busy = ""
            if (isDestroyed) return@run
            render()
            toast(if (found.isEmpty()) t("ИИ не нашёл, что показать инфоблоком", "AI found nothing to show as an info-block")
            else if (then != null) t("Инфоблоков: ${found.size}", "Info-blocks: ${found.size}") else t("Инфоблоков: ${found.size}. Соберите видео", "Info-blocks: ${found.size}. Build the video"))
            then?.invoke()
        }
    }

    // ---------- what goes over the video: subtitles or info-schemes (lumen.overlay) ----------

    /** The choice of this mix; before it was made — as the old buttons left it (schemes if there are any). */
    private fun over(): StudioOverlay = Prefs.over ?: items.any { it.scheme != null }.let { has ->
        StudioOverlay(mode = if (has) StudioOverlay.SCHEMES else StudioOverlay.SUBS, subsToo = !has || Prefs.subs)
    }

    private fun mixWhere() = StudioOverlayUi.Where(video = true, cartoon = false, schemes = items.count { it.scheme != null }, working = busy.isNotEmpty())

    private fun setOver(n: StudioOverlay) {
        val was = over()
        Prefs.over = n
        render()
        if (n.anySchemes() && !was.anySchemes()) composeIfNeeded(true)
    }

    private fun hasAi() = Provider.active() != null || Relay.mode != "off"

    /** Schemes chosen and the storyboard has none: the AI composes them (at once on the choice, before a build only with a connected AI). */
    private fun composeIfNeeded(explicit: Boolean, then: (() -> Unit)? = null): Boolean {
        val key = StudioMix.signature(items, Prefs.format)
        val ai = if (explicit) hasAi() else Provider.active() != null
        if (busy.isNotEmpty() || items.isEmpty() || !StudioOverlay.needCompose(over(), ai, items.count { it.scheme != null }, Prefs.schemesTried, key)) return false
        Prefs.schemesTried = key
        addSchemes(then)
        return true
    }

    private fun overSheet() {
        val have = items.count { it.scheme != null }
        StudioOverlayUi.sheet(this, over(), mixWhere(), { n ->
            Prefs.over = n
            render()
        }) { dismiss ->
            if (items.any { it.marked }) check(t("Инфоблоки и субтитры — только на отмеченных кусках", "Info-blocks and subtitles only on ticked pieces"), Prefs.onlyMarked) { Prefs.onlyMarked = it }
            add(text(ctx, t("Пожелание к инфоблокам (необязательно)", "A wish for the info-blocks (optional)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val wish = edit(t("например: больше чисел и сравнений, без пирамид", "e.g. more numbers and comparisons, no pyramids"), StudioPrefs.schemeWish)
            StudioOverlayUi.templateWish(this, this@StudioMixActivity, wish)
            if (hasAi() && items.isNotEmpty()) button(if (have > 0) t("🔄 Составить инфоблоки заново", "🔄 Compose the info-blocks again") else t("🧩 Составить инфоблоки", "🧩 Compose the info-blocks"),
                style = BtnStyle.TONAL) {
                StudioPrefs.schemeWish = wish.text.toString().trim()
                dismiss()
                Prefs.schemesTried = StudioMix.signature(items, Prefs.format)
                addSchemes()
            }
            if (have > 0) button(t("Убрать инфоблоки ИИ ($have)", "Remove the AI info-blocks ($have)"), style = BtnStyle.TEXT) {
                StudioPrefs.schemeWish = wish.text.toString().trim()
                dismiss()
                change(items.map { it.copy(scheme = null) }, t("Инфоблоки убраны", "Info-blocks removed"))
            }
        }
    }

    // ---------- composer assistant ----------

    private fun saveAssist(s: StudioMixAssist.State) {
        assist = s
        runCatching { assistFile.writeText(StudioMixAssist.toJson(s)) }.onFailure { L.w("studio", "mix assist: ${it.message}") }
    }

    /** The plan lines: by the voice-over words (with their times) or by the brief. */
    private fun assistBeats(s: StudioMixAssist.State = assist): List<MixBeat> =
        if (s.byVoice && words.isNotEmpty()) StudioMixAssist.beatsFromWords(words) else StudioMixAssist.beats(s.brief)

    /** The storyboard of the waiting plan when its clips are downloaded (null = no plan or nothing ready). */
    private fun planItems(): List<MixItem>? {
        if (assist.pending.isEmpty()) return null
        return StudioMixAssist.storyboard(assist.pending, clipCaps()).takeIf { it.isNotEmpty() }
    }

    private fun applyPlan() {
        val list = planItems() ?: return toast(t("Клипы плана ещё не скачаны", "The plan's clips are not downloaded yet"))
        saveAssist(assist.copy(pending = emptyList()))
        change(list, t("Раскадровка по плану: ${list.size} кусков", "Storyboard by the plan: ${list.size} pieces"))
    }

    /** Who can watch a YouTube video by link: Gemini with a key, the free AIs (they send video to Gemini), or another AI by hand. */
    private fun videoAi(): Provider? = when {
        Provider.GEMINI.isConfigured -> Provider.GEMINI
        Provider.active() == Provider.FREE -> Provider.FREE
        Provider.active() == Provider.MANUAL || Relay.mode != "off" -> Provider.MANUAL
        else -> null
    }

    /**
     * The assistant: the author writes what must be on screen (or takes the voice-over), the AI watches every source
     * video by link and names the moments that fit each line; the picked moments become clips in the order of the plan.
     */
    private fun assistSheet() {
        if (busy.isNotEmpty()) return
        bottomSheet(t("🧭 Подобрать с ИИ", "🧭 Pick with AI")) { dismiss ->
            muted(t("Напишите, что должно быть на экране — по пункту в строке (или возьмите текст озвучки). Gemini посмотрит видео по ссылке и найдёт таймкоды подходящих кадров, а Lumen соберёт из них видеоряд по порядку плана.",
                "Write what must be on screen — one point per line (or take the voice-over text). Gemini watches the videos by link and finds timecodes of fitting shots, and Lumen builds the video in the order of the plan."))
            val prov = videoAi()
            if (prov == null) muted(t("⚠ Нужен ключ Gemini (Настройки → AI): только он смотрит видео YouTube по ссылке. Или включите «Другой ИИ вручную» — запрос со ссылкой можно отправить в приложение Gemini.",
                "⚠ A Gemini key is needed (Settings → AI): only it watches YouTube videos by link. Or turn on \"Any AI by hand\" — the request with the link can go to the Gemini app."))
            else if (prov == Provider.MANUAL) muted(t("Ключа Gemini нет: запрос по каждому видео откроется для ручной отправки в Gemini.", "No Gemini key: the request for each video opens to be sent to Gemini by hand."))

            add(text(ctx, t("Что показать", "What to show"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            var byVoice = assist.byVoice && words.isNotEmpty()
            val briefE = edit(t("1. Утро в горах, туман\n2. Человек варит кофе на костре\n3. Вид с вершины", "1. Morning in the mountains, fog\n2. A man makes coffee on a fire\n3. View from the top"),
                assist.brief, multiline = true)
            if (words.isNotEmpty()) {
                add(choiceChips(ctx, listOf(false to t("📝 По плану", "📝 By the plan"), true to t("🎙 По озвучке", "🎙 By the voice-over")), byVoice) { v ->
                    byVoice = v
                    briefE.visibility = if (v) View.GONE else View.VISIBLE
                }, 4)
                briefE.visibility = if (byVoice) View.GONE else View.VISIBLE
                muted(t("По озвучке: фразы речи становятся пунктами, длина кусков — по времени фраз.", "By the voice-over: speech phrases become points, piece lengths follow the phrase times."))
            }
            val wishE = edit(t("Пожелание (необязательно): напр. только крупные планы, без людей", "Wish (optional): e.g. close-ups only, no people"), assist.wish)
            var perBeat = assist.perBeat
            add(text(ctx, t("Кусков на пункт", "Pieces per point"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, (1..3).map { it to it.toString() }, perBeat) { perBeat = it }, 4)

            add(text(ctx, t("Где искать", "Where to look"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val urls = clips.map { it.url }.distinct()
            val titles = clips.groupBy { it.url }.mapValues { (_, l) -> l.firstNotNullOfOrNull { it.title.takeIf { t -> t.isNotBlank() } } ?: l.first().url }
            val chosen = LinkedHashSet(urls)
            if (urls.isEmpty()) muted(t("Видео пока нет: добавьте их через «🔎 Найти видео» или вставьте ссылки ниже.", "No videos yet: add them with \"🔎 Find videos\" or paste links below."))
            urls.forEach { u ->
                val n = assist.moments.count { it.url == u }
                check(titles[u].orEmpty().take(70) + if (n > 0) t(" · найдено: $n", " · found: $n") else "", true) { on -> if (on) chosen += u else chosen -= u }
            }
            val linksE = edit(t("Ещё ссылки на YouTube, по одной в строке", "More YouTube links, one per line"), multiline = true)
            var auto = Prefs.autoVideos
            add(text(ctx, t("🔎 Сам найти ролики на YouTube — на каждый пункт", "🔎 Find videos on YouTube itself — per point"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(0 to t("Нет", "No"), 1 to "1", 3 to "3", 5 to "5"), auto) { auto = it; Prefs.autoVideos = it }, 4)
            muted(t("ИИ составит запрос по каждому пункту, Lumen найдёт видео (до ${Prefs.autoMaxMin} мин) и отдаст их на поиск кадров вместе с выбранными.",
                "The AI writes a query for each point, Lumen finds videos (up to ${Prefs.autoMaxMin} min) and passes them to the shot search with the chosen ones."))
            val bs = Prefs.bounds
            muted(t("Ссылок в запросе: ${bs.batch} · кадр в ${fpsLabel(bs.fps)} · границы: ", "Links per request: ${bs.batch} · a frame every ${fpsLabel(bs.fps)} · bounds: ") +
                if (bs.fit) t("подгоняет Lumen", "fitted by Lumen") else t("как дал ИИ", "as the AI gave"))
            button(t("⚙ Настройки поиска", "⚙ Search settings"), style = BtnStyle.TEXT) { boundsSheet() }
            fun state() = assist.copy(brief = briefE.text.toString().trim(), wish = wishE.text.toString().trim(), byVoice = byVoice, perBeat = perBeat)
            button(t("🔎 Найти моменты", "🔎 Find moments"), primary = true) {
                val s = state()
                val beats = assistBeats(s)
                if (beats.isEmpty()) return@button toast(t("Напишите, что показать", "Write what to show"))
                val extra = linksE.text.toString().lines().map { it.trim() }.filter { it.startsWith("http") }
                val list = (chosen.toList() + extra).distinct()
                if (list.isEmpty() && auto == 0) return@button toast(t("Выберите видео, вставьте ссылку или включите «Сам найти»", "Pick a video, paste a link or turn on \"Find itself\""))
                val p = videoAi() ?: return@button toast(t("Нужен ключ Gemini: Настройки → AI", "A Gemini key is needed: Settings → AI"))
                dismiss()
                saveAssist(s)
                findMoments(p, beats, list, titles, auto)
            }

            val beats = assistBeats()
            if (assist.moments.isEmpty() || beats.isEmpty()) return@bottomSheet
            if (assist.planKey != StudioMixAssist.planKey(beats)) muted(t("⚠ План изменился после поиска: номера пунктов могут не совпадать — найдите моменты заново.",
                "⚠ The plan changed after the search: point numbers may not match — find the moments again."))
            add(text(ctx, t("Найденные моменты", "Found moments"), Type.TITLE_M, bold = true), 16)
            muted(t("Отметьте, что брать. ≈ — похожее, не точно то. Кусков на пункт — лучшие из отмеченных.", "Tick what to take. ≈ — similar, not exactly it. Pieces per point — the best of the ticked ones."))
            var moments = assist.moments
            fun line(m: MixMoment) = (if (m.score < StudioMixAssist.EXACT) "≈ " else "") + "${StudioCuts.stamp(m.fromMs)}–${StudioCuts.stamp(m.toMs)}" +
                (if (m.what.isNotBlank()) " · ${m.what}" else "") + "\n" + m.title.ifBlank { m.url }.take(60)
            fun pick(m: MixMoment, on: Boolean) {
                moments = moments.map { if (it.key == m.key) it.copy(picked = on) else it }
            }
            for (b in beats) {
                val ms = moments.filter { it.beat == b.n }
                add(text(ctx, "${b.n}. ${b.text.take(140)}", Type.BODY, bold = true), 12)
                if (ms.isEmpty()) muted(t("нет подходящих кадров", "no fitting shots"))
                ms.forEach { m -> check(line(m), m.picked) { on -> pick(m, on) } }
            }
            val loose = moments.filter { it.beat == 0 || beats.none { b -> b.n == it.beat } }
            if (loose.isNotEmpty()) {
                add(text(ctx, t("Без пункта", "Without a point"), Type.BODY, bold = true), 12)
                loose.forEach { m -> check(line(m), m.picked) { on -> pick(m, on) } }
            }
            button(t("＋ Собрать видеоряд: в клипы и скачать", "＋ Compose: to clips and download"), primary = true) {
                saveAssist(state().copy(moments = moments))
                dismiss()
                compose()
            }
            button(t("🗑 Очистить найденное", "🗑 Clear the found moments"), style = BtnStyle.TEXT) {
                saveAssist(state().copy(moments = emptyList(), watched = emptyList(), planKey = ""))
                dismiss()
                toast(t("Найденное очищено", "Cleared"))
            }
        }
    }

    private fun fpsLabel(fps: Double): String = if (fps <= 0) t("1 с", "1 s") else StudioMix.pieceLabel((1000 / fps).toLong())

    /** Settings of the AI search: batches, frames, what the app does with the timecodes ([StudioMixAssist.Bounds]). */
    private fun boundsSheet() = bottomSheet(t("⚙ Настройки поиска", "⚙ Search settings")) { _ ->
        var b = Prefs.bounds
        fun save(n: StudioMixAssist.Bounds) {
            b = n
            Prefs.bounds = n
        }
        muted(t("Промпт поиска (studio_mix_find) меняется в Настройки → AI → Промпты.", "The search prompt (studio_mix_find) is edited in Settings → AI → Prompts."))
        add(text(ctx, t("Ссылок в одном запросе", "Links per request"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(1, 3, 5, 10, 15).let { if (b.batch in it) it else it + b.batch }.map { it to it.toString() }, b.batch) { save(b.copy(batch = it)) }, 4)
        add(text(ctx, t("Запросов одновременно", "Requests at once"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, (1..3).map { it to it.toString() }, b.parallel) { save(b.copy(parallel = it)) }, 4)
        add(text(ctx, t("Кадр видео для ИИ — каждые", "A video frame for the AI — every"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(1.0, 0.5, 0.25, 0.1).map { it to fpsLabel(it) }, b.fps) { save(b.copy(fps = it)) }, 4)
        muted(t("Реже кадры — меньше нагрузка и токенов, но короткие планы могут пропасть.", "Fewer frames — less load and tokens, but short shots may be missed."))
        add(text(ctx, t("Моментов с одного видео, не больше", "Moments of one video, at most"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(2, 4, 6, 12, 20).map { it to it.toString() }, b.perVideo) { save(b.copy(perVideo = it)) }, 4)
        add(text(ctx, t("Границы моментов", "Bounds of the moments"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(true to t("Подгоняет Lumen", "Fitted by Lumen"), false to t("Как дал ИИ", "As the AI gave")), b.fit) { save(b.copy(fit = it)) }, 4)
        check(t("Под длину фразы озвучки", "To the length of the voice-over phrase"), b.toPhrase) { save(b.copy(toPhrase = it)) }
        add(text(ctx, t("Запас с каждой стороны", "Margin on each side"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(0L, 300L, 500L, 1000L).map { it to if (it > 0) StudioMix.pieceLabel(it) else "0" }, b.padMs) { save(b.copy(padMs = it)) }, 4)
        add(text(ctx, t("Длина момента", "Moment length"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        row(
            btn(ctx, t("от ", "from ") + StudioMix.pieceLabel(b.minMs), style = BtnStyle.TONAL) {
                prompt(t("Самый короткий момент, секунд", "Shortest moment, seconds"), "", (b.minMs / 1000.0).toString()) { v ->
                    StudioMix.parsePiece(v)?.let { save(b.copy(minMs = it, maxMs = maxOf(it, b.maxMs))); toast(t("Сохранено", "Saved")) }
                }
            },
            btn(ctx, t("до ", "to ") + StudioMix.pieceLabel(b.maxMs), style = BtnStyle.TONAL) {
                prompt(t("Самый длинный момент, секунд", "Longest moment, seconds"), "", (b.maxMs / 1000.0).toString()) { v ->
                    (v.trim().replace(',', '.').toDoubleOrNull()?.times(1000)?.toLong())?.takeIf { it >= b.minMs }?.let { save(b.copy(maxMs = it)); toast(t("Сохранено", "Saved")) }
                }
            }
        )
        add(text(ctx, t("Видео из поиска YouTube — не длиннее", "Videos from the YouTube search — not longer than"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(5, 10, 20, 60).map { it to t("$it мин", "$it min") }, Prefs.autoMaxMin) { Prefs.autoMaxMin = it }, 4)
        add(text(ctx, t("Похожие кадры (≈)", "Similar shots (≈)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(choiceChips(ctx, listOf(StudioMixAssist.Similar.ALL to t("Брать", "Take"), StudioMixAssist.Similar.FALLBACK to t("Если нет точных", "If no exact"),
            StudioMixAssist.Similar.NONE to t("Не брать", "Skip")), b.similar) { save(b.copy(similar = it)) }, 4)
        button(t("↺ По умолчанию", "↺ Defaults"), style = BtnStyle.TEXT) {
            save(StudioMixAssist.Bounds())
            toast(t("Настройки поиска сброшены", "Search settings reset"))
        }
    }

    /**
     * The AI looks through [urls] in batches (several links in one request, one array of moments for all of them)
     * and names the moments for [beats]; results join the saved ones. A failed batch is asked again in halves.
     */
    private fun findMoments(prov: Provider, beats: List<MixBeat>, given: List<String>, titles0: Map<String, String>, auto: Int = 0) {
        busy = "assist"
        cancel = false
        render()
        val key = TASK + "assist"
        val s0 = assist
        val b = Prefs.bounds
        val known = clips.groupBy { it.url }.mapValues { (_, l) -> l.maxOf { it.durMs } }
        val maxVideoMs = Prefs.autoMaxMin * 60_000L
        val qLang = Studio.langName(lang())
        val vars = mapOf(
            "needs" to StudioMixAssist.needs(beats), "k" to b.perVideo.toString(),
            "wish" to s0.wish.takeIf { it.isNotBlank() }?.let { " $it." }.orEmpty(),
            // older templates
            "format" to if (aspect() == StudioAspect.PORTRAIT) "9:16" else "16:9", "beats" to StudioMixAssist.beatsBlock(beats),
            "min" to (b.minMs / 1000).toString(), "max" to (b.maxMs / 1000).toString(), "count" to b.perVideo.toString(), "lang" to Studio.langName(lang()),
            "title" to "", "dur" to ""
        )
        BgTasks.run(this, key, t("Микс: ИИ ищет кадры", "Mix: the AI looks for shots"), BgTasks.reopen(this), {
            val found = java.util.concurrent.ConcurrentHashMap<String, List<MixMoment>>()
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val durs = java.util.concurrent.ConcurrentHashMap(known.filterValues { it > 0 })
            val done = java.util.concurrent.atomic.AtomicInteger()
            val titles = java.util.concurrent.ConcurrentHashMap(titles0)
            // the assistant looks for videos itself: a query per line (AI, or the line's words), YouTube search, best few
            var urls = given
            if (auto > 0) {
                BgTasks.progress(key, t("Составляю запросы для поиска…", "Writing search queries…"))
                val queries = if (prov == Provider.MANUAL) StudioMixAssist.parseQueries("", beats)
                else runCatching {
                    val q = Llm.complete(prov, prov.fastModel, "Answer with JSON only.",
                        Prompts.fill("studio_mix_query", mapOf("needs" to StudioMixAssist.needs(beats), "lang" to qLang)),
                        json = true, maxTokens = 60 * beats.size + 200, task = "studio_mix_query", pid = "studio")
                    StudioMixAssist.parseQueries(q.text, beats)
                }.getOrElse { if (it is java.util.concurrent.CancellationException) throw it else StudioMixAssist.parseQueries("", beats) }
                val hits = LinkedHashMap<Int, List<Pair<String, Long>>>()
                for ((n, q) in queries) {
                    if (cancel) throw java.util.concurrent.CancellationException()
                    BgTasks.progress(key, t("Ищу на YouTube: ", "Searching YouTube: ") + q)
                    val found = runCatching { YouTube.search(listOf(q), SearchFilters(limit = 12, excludeShorts = aspect() != StudioAspect.PORTRAIT)) }.getOrDefault(emptyList())
                    found.forEach { v ->
                        titles.putIfAbsent(v.url, v.title)
                        if (v.duration > 0) durs.putIfAbsent(v.url, v.duration * 1000)
                    }
                    hits[n] = found.map { it.url to if (it.duration > 0) it.duration * 1000 else 0L }
                }
                urls = (given + StudioMixAssist.pickVideos(hits, auto, maxVideoMs, given.toSet())).distinct()
            }
            val groups = StudioMixAssist.batches(urls, b.batch)
            val stop = java.util.concurrent.atomic.AtomicReference<String?>(null)
            fun ask(g: List<String>, depth: Int) {
                if (cancel) throw java.util.concurrent.CancellationException()
                if (stop.get() != null) return
                try {
                    val r = Llm.complete(prov, prov.fastModel, "Answer with JSON only.", Prompts.fill("studio_mix_find", vars + ("n" to g.size.toString())),
                        json = true, fileUri = g.joinToString("\n"), maxTokens = (40 * g.size * b.perVideo).coerceIn(400, 8000),
                        task = "studio_mix_find", pid = "studio", lowMedia = true, media = MediaOpts(b.fps, StudioMixAssist.BATCH_SCHEMA, true))
                    val ms = StudioMixAssist.parseBatch(r.text, g, titles)
                    for (u in g) {
                        if (durs[u] == null) runCatching { StudioCutsLoad.durationMs(u) }.getOrNull()?.takeIf { it > 0 }?.let { durs[u] = it }
                        found[u] = StudioMixAssist.bound(ms.filter { it.url == u }, b, durs, beats)
                        if (found[u].isNullOrEmpty()) errors += "${(titles[u] ?: u).take(50)}: " + t("подходящих кадров не нашлось", "no fitting shots found")
                    }
                    val n = done.addAndGet(g.size)
                    BgTasks.progress(key, t("Видео просмотрено: $n/${urls.size}", "Videos looked through: $n/${urls.size}"))
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "mix assist batch of ${g.size}: ${e.message}")
                    // region blocked, bad key, daily quota: the other batches would fail the same way
                    if ((e as? LlmException)?.fatal != null) {
                        if (stop.compareAndSet(null, e.message)) errors += e.message.orEmpty()
                        return
                    }
                    // a batch too big for the model or a stuck answer: the halves separately
                    if (g.size > 1 && depth < 4) {
                        ask(g.subList(0, g.size / 2), depth + 1)
                        ask(g.subList(g.size / 2, g.size), depth + 1)
                    } else errors += "${(titles[g[0]] ?: g[0]).take(50)}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            BgTasks.progress(key, t("Запросов: ${groups.size}, видео: ${urls.size}", "Requests: ${groups.size}, videos: ${urls.size}"))
            Par.mapAll(groups, b.parallel) { _, g -> ask(g, 0) }
            HashMap(found) to errors.toList()
        }, { e ->
            busy = ""
            if (isDestroyed) return@run
            render()
            err(e)
        }, { cancel = true }) { (found, errors) ->
            val now = if (assistFile.exists()) StudioMixAssist.fromJson(assistFile.readText()) else s0
            val pk = StudioMixAssist.planKey(beats)
            var moments = if (now.planKey == pk) now.moments else emptyList()
            for ((u, ms) in found) moments = StudioMixAssist.merge(moments, u, ms)
            saveAssist(now.copy(moments = moments, planKey = pk, watched = ((if (now.planKey == pk) now.watched else emptyList()) + found.keys).distinct()))
            busy = ""
            if (isDestroyed) return@run
            render()
            if (errors.isNotEmpty()) showText(t("Не по всем видео", "Not for every video"), errors.joinToString("\n"))
            val gaps = StudioMixAssist.uncovered(beats, moments)
            toast(t("Моментов: ${moments.size}", "Moments: ${moments.size}") +
                if (gaps.isNotEmpty()) t(" · без кадров пункты ${gaps.joinToString(", ") { it.n.toString() }}", " · no shots for points ${gaps.joinToString(", ") { it.n.toString() }}") else "")
            assistSheet()
        }
    }

    /** Picked moments in the plan order become clips (each kept whole), placeholders of the watched videos go, then download. */
    private fun compose() {
        val beats = assistBeats()
        val steps = StudioMixAssist.plan(beats, assist.moments, assist.perBeat)
        if (steps.isEmpty()) return toast(t("Отметьте хотя бы один момент", "Tick at least one moment"))
        // a not downloaded clip of a watched video was only a way to find it
        val watched = assist.moments.map { it.url }.toSet()
        val keep = clips.filter { !(it.url in watched && it.file.isEmpty() && steps.none { s -> s.moment.url == it.url && StudioCuts.span(s.moment.fromMs, s.moment.toMs) == (it.fromMs to it.toMs) }) }
        val (list, order) = StudioMixAssist.toClips(keep, steps)
        if (order.size < steps.size) toast(t("Не больше ${StudioCuts.MAX_CLIPS} клипов: часть моментов не вошла", "At most ${StudioCuts.MAX_CLIPS} clips: some moments did not fit"))
        clips.filter { it !in keep && it.file.isNotEmpty() }.forEach { File(clipsDir, it.file).delete() }
        saveClips(list)
        val ids = order.map { it.first }.toSet()
        saveCuts(cuts + list.filter { it.id in ids }.associate { it.id to ClipCut(MixCut.EVEN, it.ms) })
        saveAssist(assist.copy(pending = order))
        if (clips.any { it !in readyClips() }) download() else applyPlan()
    }

    // ---------- clips ----------

    /**
     * Quick look at a found video: first a strip of frames from the YouTube storyboard (how the video goes, before
     * playing it); a tap on a frame plays the lowest quality stream from there in a mini player (with sound when
     * there is one), through the NewPipe YouTube data source. Tap on the picture pauses; ⏪/⏩ jump 10 s.
     */
    /** One video of the feed: [fromMs] — where to start (−1 = the middle). */
    private data class FeedItem(val url: String, val title: String, val fromMs: Long)

    /**
     * The feed: the videos one after another in the low-quality mini player, playing at once from the clip's start
     * (or the middle), ×1 / ×1.5 / ×2, ⏮ ⏭ to flip; "✓ Take from here" gives the video and the moment to [take].
     */
    private fun feedSheet(items: List<FeedItem>, take: (FeedItem, Long) -> Unit) {
        if (items.isEmpty()) return
        val look = MiniLook(this)
        var i = 0
        lateinit var title: TextView
        lateinit var count: TextView
        fun show() {
            val it = items[i]
            title.text = it.title.ifBlank { it.url }.take(90)
            count.text = "${i + 1} / ${items.size}"
            look.open(it.url, auto = true, fromMs = it.fromMs)
        }
        bottomSheet(t("▶ Пролистать", "▶ Flip through"), onDismiss = { look.release() }) { _ ->
            count = add(text(ctx, "", Type.LABEL, bold = true, color = Palette.MUTED), 0)
            title = add(text(ctx, "", 14f, bold = true), 2)
            add(look.view, 4)
            add(choiceChips(ctx, listOf(1f to "×1", 1.5f to "×1.5", 2f to "×2"), look.speed) { look.speed = it }, 6)
            row(
                btn(ctx, t("⏮ Назад", "⏮ Back"), style = BtnStyle.TEXT) { if (i > 0) { i--; show() } },
                btn(ctx, t("✓ Взять отсюда", "✓ Take from here"), primary = true) {
                    val at = look.posMs()
                    if (at < 0) return@btn toast(t("Видео ещё не играет", "The video does not play yet"))
                    take(items[i], at / 100 * 100)
                    if (i < items.lastIndex) { i++; show() }
                },
                btn(ctx, t("Дальше ⏭", "Next ⏭"), style = BtnStyle.TONAL) { if (i < items.lastIndex) { i++; show() } else toast(t("Это последнее", "This is the last one")) }
            )
            muted(t("«Взять отсюда» ставит начало клипа на текущий момент (длина — как у клипа или 5 с) и переходит к следующему.",
                "\"Take from here\" sets the clip start to the current moment (length as the clip's or 5 s) and goes to the next one."))
            show()
        }
    }

    /** The clip of [url] starts at [atMs] now (a new clip when there is none, from [hit]); download it later with ⬇. */
    private fun takeFrom(url: String, atMs: Long, hit: CutHit? = null) {
        val cl = clips.firstOrNull { it.url == url }
        if (cl == null) {
            val list = StudioCuts.addHits(clips, listOfNotNull(hit ?: CutHit(url, url)))
            val added = list.drop(clips.size).firstOrNull() ?: return toast(t("Не больше ${StudioCuts.MAX_CLIPS} клипов", "At most ${StudioCuts.MAX_CLIPS} clips"))
            val (a, b) = StudioCuts.span(atMs, atMs + added.ms)
            saveClips(list.map { if (it.id == added.id) it.copy(fromMs = a, toMs = b) else it })
            clipMarks += added.id
            toast(t("Добавлен ${added.id} с ${StudioCuts.stamp(a)}", "Added ${added.id} from ${StudioCuts.stamp(a)}"))
        } else {
            val (a, b) = StudioCuts.span(atMs, atMs + cl.ms)
            if (cl.fromMs == a && cl.toMs == b) return toast(t("Отрезок тот же", "The part is the same"))
            if (cl.file.isNotEmpty()) File(clipsDir, cl.file).delete()
            saveClips(clips.map { if (it.id == cl.id) it.copy(fromMs = a, toMs = b, file = "", leadMs = 0, lenMs = 0) else it })
            toast(t("${cl.id}: начало ${StudioCuts.stamp(a)} — скачайте ⬇", "${cl.id}: start ${StudioCuts.stamp(a)} — download ⬇"))
        }
        if (busy.isEmpty()) render()
    }

    /** Search on YouTube by title: tick results in bulk and add them; the sheet stays open for more searches. */
    private fun findSheet() {
        var hits: List<CutHit> = emptyList()
        val marked = LinkedHashSet<String>()
        lateinit var box: LinearLayout
        lateinit var info: TextView
        lateinit var addBtn: Button
        lateinit var allBtn: Button

        fun updateAdd() {
            addBtn.text = t("＋ Добавить отмеченные (${marked.size})", "＋ Add ticked (${marked.size})")
            addBtn.isEnabled = marked.isNotEmpty()
            allBtn.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
            allBtn.text = if (hits.isNotEmpty() && hits.all { it.url in marked }) t("☐ Снять все", "☐ Untick all") else t("☑ Отметить все", "☑ Tick all")
        }

        // ---- quick look: one mini player, under the video being watched ----
        val look = MiniLook(this)
        var watching = ""
        lateinit var fillRef: () -> Unit

        fun watch(h: CutHit) {
            if (watching == h.url) {
                watching = ""
                look.stop()
            } else {
                watching = h.url
                look.open(h.url)
            }
            fillRef()
        }

        fun fill() {
            box.removeAllViews()
            (look.view.parent as? ViewGroup)?.removeView(look.view)
            val have = clips.map { it.url }.toSet()
            for (h in hits) {
                val sub = listOf(if (h.topic.isNotEmpty()) "🔎 ${h.topic}" else "", h.channel, if (h.durationMs > 0) StudioCuts.stamp(h.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
                val label = (if (h.url in have) "✅ " else "") + h.title + if (sub.isNotEmpty()) "\n$sub" else ""
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // a small picture of the video; a tap opens the frames strip
                    addView(ImageView(this@StudioMixActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFF202020.toInt())
                        Thumbs.load(this, h.thumb)
                        contentDescription = t("Кадры видео", "Video frames")
                        setOnClickListener { watch(h) }
                    }, LinearLayout.LayoutParams(dp(96), dp(54)).apply { rightMargin = dp(6) })
                    addView(chk(this@StudioMixActivity, label, h.url in marked) { on ->
                        if (on) marked += h.url else marked -= h.url
                        updateAdd()
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(btn(this@StudioMixActivity, if (watching == h.url) "▴" else "▾", style = BtnStyle.TEXT) { watch(h) }.apply {
                        contentDescription = t("Кадры и просмотр", "Frames and watching")
                    })
                }
                box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                if (watching == h.url) box.addView(look.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            updateAdd()
        }
        fillRef = { fill() }

        fun search(text: String) {
            val topics = StudioCuts.topics(text)
            if (topics.isEmpty()) return
            val each = StudioCuts.perTopic(topics.size)
            info.text = t("Поиск…", "Searching…")
            Lumen.async({
                val groups = topics.mapIndexed { i, q ->
                    if (topics.size > 1) runOnUiThread { info.text = t("Поиск ${i + 1}/${topics.size}: $q…", "Searching ${i + 1}/${topics.size}: $q…") }
                    runCatching {
                        YouTube.search(listOf(q), SearchFilters(limit = each)).take(each).map {
                            CutHit(it.url, it.title, it.channel, if (it.duration > 0) it.duration * 1000 else 0,
                                // a light 320×180 picture is enough for the list
                                YtQuery.videoIdFromUrl(it.url)?.let { id -> "https://i.ytimg.com/vi/$id/mqdefault.jpg" } ?: it.thumb,
                                topic = if (topics.size > 1) q else "")
                        }
                    }.getOrElse { e -> if (topics.size == 1) throw e else emptyList() }
                }
                Pair(StudioCuts.mergeHits(groups), topics.zip(groups).filter { it.second.isEmpty() }.map { it.first })
            }, { e -> info.text = (e.message ?: e.javaClass.simpleName).take(200) }) { (found, empty) ->
                hits = found
                marked.clear()
                watching = ""
                look.stop()
                info.text = if (found.isEmpty()) t("Ничего не нашлось. Попробуйте другие слова.", "Nothing found. Try other words.")
                else t("Найдено: ${found.size}", "Found: ${found.size}") +
                    (if (topics.size > 1) t(" по темам: ${topics.size}", " for ${topics.size} topics") else "") +
                    (if (empty.isNotEmpty()) t(". Пусто: ${empty.joinToString(", ")}", ". Nothing for: ${empty.joinToString(", ")}") else "") +
                    t(". Отметьте нужные.", ". Tick the ones you need.")
                fill()
            }
        }

        bottomSheet(t("Найти видео для микса", "Find videos for the mix"), onDismiss = { look.release() }) { _ ->
            muted(t("Поиск по названию на YouTube. Несколько тем — каждая с новой строки: Lumen найдёт видео по каждой и покажет вперемешку. Картинка или ▾ — кадры по всему видео; тап по кадру — видео с этого места в низком качестве. Отмеченные добавятся с 0:00 на 5 с; отрезок задайте кнопкой «⏱ Отрезок». Можно искать снова и добавлять ещё.",
                "Search YouTube by title. Several topics — one per line: Lumen searches each and shows the results together. The picture or ▾ shows frames across the video; tap a frame to watch from there in low quality. Ticked ones are added from 0:00 for 5 s; set the piece with \"⏱ Piece\". You can search again and add more."))
            val query = edit(t("Что искать (темы — с новой строки)", "What to search (topics — one per line)"), multiline = true)
            button(t("🔎 Искать", "🔎 Search"), style = BtnStyle.TONAL) { search(query.text.toString().trim()) }
            info = add(text(ctx, "", 13f, color = Palette.MUTED), 4)
            button(t("▶ Пролистать найденное", "▶ Flip through the results"), style = BtnStyle.TEXT) {
                if (hits.isEmpty()) return@button toast(t("Сначала найдите видео", "Find videos first"))
                look.stop()
                feedSheet(hits.map { FeedItem(it.url, it.title, -1) }) { item, at ->
                    takeFrom(item.url, at, hits.firstOrNull { it.url == item.url })
                    fill()
                }
            }
            allBtn = button(t("☑ Отметить все", "☑ Tick all"), style = BtnStyle.TEXT) {
                if (hits.all { it.url in marked }) marked.clear() else marked += hits.map { it.url }
                fill()
            }
            box = add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL })
            addBtn = button(t("＋ Добавить отмеченные", "＋ Add ticked"), primary = true) {
                val chosen = hits.filter { it.url in marked }
                if (chosen.isEmpty()) return@button
                val before = clips.size
                val list = StudioCuts.addHits(clips, chosen)
                val added = list.drop(before)
                saveClips(list)
                // the new ones are ticked, so a piece can be set for all of them at once
                clipMarks += added.map { it.id }
                toast(t("Добавлено: ${added.size}", "Added: ${added.size}") +
                    if (added.size < chosen.size) t(" (повторы пропущены, максимум ${StudioCuts.MAX_CLIPS})", " (repeats skipped, at most ${StudioCuts.MAX_CLIPS})") else "")
                marked.clear()
                fill()
                render()
            }
            updateAdd()
        }
    }

    /** One piece (start and length) for all the ticked clips; a changed piece drops the downloaded file. */
    private fun rangeDialog(marked: List<SClip>) {
        val first = marked.first()
        val fromE = edt(this, t("Начало: 1:23 или 83", "Start: 1:23 or 83"), StudioCuts.stamp(first.fromMs))
        val lenE = edt(this, t("Длина, с (до 30)", "Length, s (up to 30)"), ((first.ms + 500) / 1000).toString(), number = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            listOf(fromE, lenE).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        android.app.AlertDialog.Builder(this).setTitle(t("Отрезок для ${marked.size} клип(ов)", "Piece for ${marked.size} clip(s)")).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val from = StudioCuts.parseTime(fromE.text.toString()) ?: 0L
                val len = (lenE.text.toString().trim().toLongOrNull() ?: 5L).coerceIn(1, StudioCuts.MAX_CLIP_MS / 1000) * 1000
                val (a, b) = StudioCuts.span(from, from + len)
                val ids = marked.map { it.id }.toSet()
                saveClips(clips.map { cl ->
                    if (cl.id !in ids || cl.fromMs == a && cl.toMs == b) cl
                    else {
                        if (cl.file.isNotEmpty()) File(clipsDir, cl.file).delete()
                        cl.copy(fromMs = a, toMs = b, file = "", leadMs = 0, lenMs = 0)
                    }
                })
                render()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun removeClips(marked: List<SClip>) =
        confirm(t("Убрать клипы: ${marked.size}?", "Remove clips: ${marked.size}?"), marked.joinToString("\n") { it.title.ifBlank { it.url }.take(60) }, t("Убрать", "Remove")) {
            marked.forEach { if (it.file.isNotEmpty()) File(clipsDir, it.file).delete() }
            val ids = marked.map { it.id }.toSet()
            clipMarks -= ids
            saveCuts(cuts - ids)
            saveClips(clips.filter { it.id !in ids })
            render()
        }

    /**
     * Downloads the clips that are not ready, two at a time; a failed one is reported and skipped. Clips in
     * [randomize] first get a new random part of their video (same length). Pieces of new and re-downloaded clips
     * go into an existing storyboard at random places.
     */
    private fun download(randomize: Set<String> = emptySet(), again: Set<String> = emptySet()) {
        val todo = clips.filter { it !in readyClips() || it.id in randomize || it.id in again }
        if (todo.isEmpty()) return
        busy = "dl"
        cancel = false
        render()
        val dirNow = clipsDir
        val maxH = StudioPrefs.clipCap
        val used = if (usedFile.exists()) StudioMix.usedFromJson(usedFile.readText()) else emptyMap()
        // parts already picked (old ones, all clips, and those picked in this run), so parallel parts never overlap
        val picked = HashMap<String, List<Pair<Long, Long>>>(used)
        for (cl in clips) if (cl.id !in randomize) picked[cl.url] = picked[cl.url].orEmpty() + (cl.fromMs to cl.toMs)
        val key = TASK + "dl"
        BgTasks.run(this, key, t("Микс: загрузка клипов", "Mix: downloading clips"), BgTasks.reopen(this), {
            val done = java.util.concurrent.ConcurrentHashMap<String, SClip>()
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            Par.mapAll(todo, 2) { i, cl0 ->
                if (cancel) throw java.util.concurrent.CancellationException()
                BgTasks.progress(key, t("Клип ${i + 1}/${todo.size}", "Clip ${i + 1}/${todo.size}"))
                try {
                    var cl = cl0
                    if (cl.id in randomize) {
                        val dur = cl.durMs.takeIf { it > 0 } ?: StudioCutsLoad.durationMs(cl.url)
                        val (a, b) = synchronized(picked) {
                            val at = StudioMix.freshStart(dur, cl.ms, picked[cl.url].orEmpty() + (cl.fromMs to cl.toMs), System.nanoTime() + i)
                            StudioCuts.span(at, at + cl.ms).also { picked[cl.url] = picked[cl.url].orEmpty() + it }
                        }
                        cl = cl.copy(fromMs = a, toMs = b, durMs = dur)
                    }
                    done[cl.id] = StudioCutsLoad.fetch(cl, dirNow, maxH, { cancel }) { BgTasks.progress(key, it) }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "mix clip ${cl0.id}: ${e.message}")
                    errors += "${cl0.id}: ${e.message ?: e.javaClass.simpleName}"
                }
                i
            }
            HashMap(done) to errors.toList()
        }, { e ->
            busy = ""
            topUpTarget = 0
            if (isDestroyed) return@run
            render()
            err(e)
        }, { cancel = true }) { (done, errors) ->
            // the list on disk may have changed while downloading
            val now = if (clipsFile.exists()) StudioCuts.fromJson(clipsFile.readText()) else clips
            saveClips(now.map { cl ->
                done[cl.id]?.takeIf { it.url == cl.url && (cl.id in randomize || it.fromMs == cl.fromMs && it.toMs == cl.toMs) } ?: cl
            })
            // remember the parts taken, so the next "🎲 New pieces" takes others
            val taken = HashMap(used)
            for (cl in done.values) taken[cl.url] = taken[cl.url].orEmpty().filter { it != cl.fromMs to cl.toMs } + (cl.fromMs to cl.toMs)
            runCatching { usedFile.writeText(StudioMix.usedJson(taken)) }
            busy = ""
            // the new pieces join the storyboard (one step of ↶); clips of the assistant's plan make the storyboard in its order
            var list = items
            val planned = planItems()
            if (planned != null) {
                list = planned
                saveAssist(assist.copy(pending = emptyList()))
            } else if (list.isNotEmpty()) {
                val specs = specs().associateBy { it.id }
                for (id in done.keys) {
                    val spec = specs[id] ?: continue
                    if (id in randomize || list.none { it.clipId == id }) {
                        list = StudioMix.replaceClip(list, id, StudioMix.cut(spec, Prefs.pieceMs, Prefs.mode, Prefs.cutSeed), System.nanoTime())
                    }
                }
                if (randomize.isNotEmpty() && topUpTarget <= 0) list = StudioMix.rearrange(list, MixOrder.SHUFFLE, Prefs.seed)
                if (list != items) list = if (topUpTarget > 0) filled(list, topUpTarget) else fitted(list)
            }
            if (isDestroyed) {
                if (list != items) saveItems(StudioMix.unique(list))
                return@run
            }
            val joined = list != items
            if (joined) change(list) else render()
            // filling the set length: one more round while the new parts still fall short (at most 3 in all)
            val target = topUpTarget
            topUpTarget = 0
            if (target > 0 && planned == null) {
                val lack = StudioMix.lackMs(items, target)
                if (lack > FIT_TOL_MS && done.isNotEmpty() && topUpRound < 3) return@run topUp(target, topUpRound + 1)
                if (errors.isEmpty()) return@run toast(if (lack > FIT_TOL_MS)
                    t("Добрано, но не хватает ${StudioCuts.stamp(lack)} — нажмите «🧩 Добрать» ещё раз или добавьте видео",
                        "Filled up, but ${StudioCuts.stamp(lack)} short — tap \"🧩 Take more\" again or add videos")
                else t("Ролик заполнен: ${StudioCuts.stamp(StudioMix.totalMs(items))}, кусков ${items.size}",
                    "The video is filled: ${StudioCuts.stamp(StudioMix.totalMs(items))}, ${items.size} pieces"))
            }
            if (errors.isNotEmpty()) showText(t("Не все клипы скачались", "Some clips failed"), errors.joinToString("\n"))
            else if (planned != null) toast(t("Раскадровка по плану: ${planned.size} кусков, ${StudioCuts.stamp(StudioMix.totalMs(planned))}",
                "Storyboard by the plan: ${planned.size} pieces, ${StudioCuts.stamp(StudioMix.totalMs(planned))}"))
            else toast(t("Клипов скачано: ${done.size}", "Clips downloaded: ${done.size}") +
                if (joined) t(" · их куски добавлены в раскадровку", " · their pieces are on the storyboard") else "")
        }
    }
}

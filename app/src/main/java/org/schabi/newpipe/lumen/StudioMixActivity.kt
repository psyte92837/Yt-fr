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
        private const val FILE_PREFIX = "Lumen_Mix_"
        private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val thumbPool = Executors.newSingleThreadExecutor()

        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, StudioMixActivity::class.java))
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
        var seed: Long
            get() = p.getLong("studio_mix_seed", 1)
            set(v) = p.edit().putLong("studio_mix_seed", v).apply()
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
        /** What the build that is running shows (taken over when it ends, also by a reopened screen). */
        var pending: String
            get() = p.getString("studio_mix_pending", "") ?: ""
            set(v) = p.edit().putString("studio_mix_pending", v).apply()
    }

    private val dir by lazy { File(filesDir, "lumen/studio/mix").apply { mkdirs() } }
    private val clipsFile get() = File(dir, "clips.json")
    private val clipsDir get() = File(dir, "clips").apply { mkdirs() }
    private val mixFile get() = File(dir, "mix.json")
    private val voiceFile get() = File(dir, "voice.wav")
    private val wordsFile get() = File(dir, "words.json")

    private var clips: List<SClip> = emptyList()
    private var items: List<MixItem> = emptyList()
    private var words: List<SWord> = emptyList()
    /** Clips ticked in the list (for the range and removal). */
    private val clipMarks = LinkedHashSet<String>()

    /** Running work of this screen: "dl", "schemes", "subs" or "". */
    private var busy = ""
    @Volatile private var cancel = false
    private var status: Pair<TextView, ProgressBar>? = null
    private var renderBar: Pair<TextView, ProgressBar>? = null
    private var boardInfo: TextView? = null
    /** Open the recording as soon as the video being built is ready. */
    private var recordAfter = false
    private var recorder: StudioRecorder? = null

    private val jobListener: () -> Unit = { onJob() }
    private val watcher: () -> Unit = {
        val task = BgTasks.find(TASK)
        if (task == null && (busy == "dl" || busy == "schemes") && !isDestroyed) {
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

    private fun readyClips(): List<SClip> = clips.filter { it.file.isNotEmpty() && it.lenMs > 0 && File(clipsDir, it.file).exists() }

    private fun readyIds(): Set<String> = readyClips().map { it.id }.toSet()

    private fun aspect() = StudioAspect.of(Prefs.format)

    private fun lang() = Studio.voiceLang(StudioPrefs.voiceLang, null, Settings.lang == "en")

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
            load()
            render()
        }
    }

    // ---------- layout ----------

    private fun render() {
        status = null
        renderBar = null
        boardInfo = null
        val c = Col(this)
        if (Prefs.format.isEmpty()) {
            formatCard(c)
            page(t("🔀 Микс", "🔀 Mix"), c.root)
            return
        }
        c.row(
            chip(this, (if (aspect() == StudioAspect.PORTRAIT) "📱 Shorts / Stories 9:16" else t("🖥 Горизонтальное 16:9", "🖥 Horizontal 16:9")), true),
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
        add(text(ctx, t("1. Видео", "1. Videos"), Type.TITLE_M, bold = true), 0)
        muted(t("Найдите видео, отметьте нужные и задайте им отрезок: начало и длину (до 30 с). Скачиваются только эти секунды, без звука.",
            "Find videos, tick the ones you need and set their piece: start and length (up to 30 s). Only those seconds are downloaded, without sound."))
        clips.forEach { cl ->
            val ready = cl.file.isNotEmpty() && File(clipsDir, cl.file).exists()
            val label = (if (ready) "✅ " else "⬇ ") + "${cl.id} · ${StudioCuts.stamp(cl.fromMs)} +${(cl.ms + 500) / 1000} с\n" +
                cl.title.ifBlank { cl.note }.ifBlank { cl.url }.take(80)
            add(chk(ctx, label, cl.id in clipMarks) { on ->
                if (on) clipMarks += cl.id else clipMarks -= cl.id
                if (busy.isEmpty()) render()
            }, 4)
        }
        progress("dl", t("Загрузка клипов…", "Downloading clips…"))
        if (busy.isNotEmpty()) return@card
        val views = mutableListOf<View>(btn(ctx, t("🔎 Найти видео", "🔎 Find videos"), style = BtnStyle.TONAL) { findSheet() })
        if (clips.isNotEmpty()) {
            val all = clips.all { it.id in clipMarks }
            views += btn(ctx, if (all) t("☐ Снять все", "☐ Untick all") else t("☑ Отметить все", "☑ Tick all"), style = BtnStyle.TEXT) {
                if (all) clipMarks.clear() else clipMarks += clips.map { it.id }
                render()
            }
        }
        row(*views.toTypedArray())
        val marked = clips.filter { it.id in clipMarks }
        if (marked.isNotEmpty()) {
            row(
                btn(ctx, t("⏱ Отрезок (${marked.size})", "⏱ Piece (${marked.size})"), style = BtnStyle.TONAL) { rangeDialog(marked) },
                btn(ctx, t("✕ Убрать (${marked.size})", "✕ Remove (${marked.size})"), style = BtnStyle.TEXT) { removeClips(marked) }
            )
        }
        val todo = clips.count { it !in readyClips() }
        if (todo > 0) button(t("⬇ Скачать ($todo)", "⬇ Download ($todo)"), primary = true) { download() }
    }

    private fun mixCard(c: Col) = c.card {
        add(text(ctx, t("2. Микс", "2. Mix"), Type.TITLE_M, bold = true), 0)
        muted(t("Клипы режутся на куски и перемешиваются: два куска одного клипа не идут подряд.",
            "The clips are cut into pieces and shuffled: two pieces of one clip never go in a row."))
        add(text(ctx, t("Длина куска — сразу для всех клипов", "Piece length — for all clips at once"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val now = Prefs.pieceMs
        val chips = (StudioMix.PIECES + now).distinct().sorted().map { ms ->
            chip(ctx, StudioMix.pieceLabel(ms), ms == now) { if (busy.isEmpty() && ms != now) applyPiece(ms) }
        } + chip(ctx, t("✎ Своя", "✎ Own")) {
            if (busy.isEmpty()) prompt(t("Длина куска, секунд", "Piece length, seconds"), t("напр. 0.8 или 4", "e.g. 0.8 or 4"), (now / 1000.0).toString()) { v ->
                val ms = StudioMix.parsePiece(v)
                if (ms == null) toast(t("Нужно число секунд", "A number of seconds is needed")) else applyPiece(ms)
            }
        }
        chips(chips)
        if (busy.isEmpty() && items.isEmpty()) button(t("🔀 Смешать", "🔀 Mix"), primary = true) { shuffle() }
    }

    /** New piece length: every clip is cut again at once (same order of shuffle); hand edits are confirmed first. */
    private fun applyPiece(ms: Long) {
        val ready = readyClips().map { it.id to it.lenMs }
        val old = Prefs.pieceMs
        fun go() {
            Prefs.pieceMs = ms
            if (items.isNotEmpty() && ready.isNotEmpty()) saveItems(StudioMix.mix(ready, ms, Prefs.seed))
            render()
            if (items.isNotEmpty()) toast(t("Куски по ${StudioMix.pieceLabel(ms)}: кадров ${items.size}", "Pieces of ${StudioMix.pieceLabel(ms)}: ${items.size} frames"))
        }
        if (items.isNotEmpty() && StudioMix.edited(items, ready, old, Prefs.seed)) {
            confirm(t("Перерезать все куски?", "Cut all pieces again?"),
                t("Раскадровка соберётся заново по ${StudioMix.pieceLabel(ms)}: перестановки, удаления, отметки и схемы пропадут.",
                    "The storyboard is rebuilt with ${StudioMix.pieceLabel(ms)} pieces: moves, deletions, ticks and schemes are lost."), t("Перерезать", "Cut")) { go() }
        } else go()
    }

    private fun shuffle() {
        val ready = readyClips()
        if (ready.isEmpty()) return
        Prefs.seed = System.nanoTime()
        saveItems(StudioMix.mix(ready.map { it.id to it.lenMs }, Prefs.pieceMs, Prefs.seed))
        render()
    }

    // ---------- storyboard ----------

    private fun boardCard(c: Col) = c.card {
        add(text(ctx, t("3. Раскадровка", "3. Storyboard"), Type.TITLE_M, bold = true), 0)
        muted(t("Нажмите на кадр — отметить, удерживайте и тащите — переставить, ✕ — удалить.",
            "Tap a frame to tick it, hold and drag to move it, ✕ to delete it."))
        boardInfo = add(text(ctx, "", 14f, bold = true), 8)
        updateInfo()
        val (rv, height) = board()
        add(rv, 8).layoutParams.height = height
        if (busy.isNotEmpty()) return@card
        val all = items.all { it.marked }
        row(
            btn(ctx, if (all) t("☐ Снять все", "☐ Untick all") else t("☑ Отметить все", "☑ Tick all"), style = BtnStyle.TEXT) {
                saveItems(items.map { it.copy(marked = !all) })
                render()
            },
            btn(ctx, t("✕ Удалить отмеченные", "✕ Delete ticked"), style = BtnStyle.TEXT) {
                if (items.none { it.marked }) toast(t("Ничего не отмечено", "Nothing is ticked"))
                else {
                    saveItems(StudioMix.removeMarked(items))
                    render()
                }
            }
        )
        button(t("🔀 Перемешать заново", "🔀 Shuffle again"), style = BtnStyle.TONAL) { shuffle() }
    }

    private fun updateInfo() {
        val on = items.count { it.marked }
        boardInfo?.text = t("Кадров: ${items.size} · ${StudioCuts.stamp(StudioMix.totalMs(items))}", "Frames: ${items.size} · ${StudioCuts.stamp(StudioMix.totalMs(items))}") +
            (if (on > 0) t(" · отмечено: $on", " · ticked: $on") else "") +
            StudioMix.repeats(items).let { if (it > 0) t(" · повторов подряд: $it", " · repeats in a row: $it") else "" }
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
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = busy.isEmpty()

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
                saveItems(items)
                updateInfo()
                // numbers follow the new order
                rv.post { adapter.notifyItemRangeChanged(0, items.size) }
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
            val mark = text(ctx, "✓", 14f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                background = roundBg(Palette.ACCENT, 12f, ctx)
            }
            root.addView(mark, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            val del = text(ctx, "✕", 13f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                background = roundBg(0x99000000.toInt(), 12f, ctx)
                contentDescription = t("Удалить", "Delete")
            }
            root.addView(del, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
            val h = Tile(root, image, label, mark, del)
            root.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0 || busy.isNotEmpty()) return@setOnClickListener
                saveItems(items.mapIndexed { i, it -> if (i == p) it.copy(marked = !it.marked) else it })
                notifyItemChanged(p)
                updateInfo()
            }
            del.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0 || busy.isNotEmpty()) return@setOnClickListener
                saveItems(items.filterIndexed { i, _ -> i != p })
                notifyItemRemoved(p)
                notifyItemRangeChanged(p, items.size - p)
                updateInfo()
                if (items.isEmpty()) render()
            }
            return h
        }

        override fun onBindViewHolder(h: Tile, position: Int) {
            val it = items[position]
            h.label.text = "${position + 1} · ${it.clipId} · ${"%.1f".format(Locale.US, it.lenMs / 1000f)} с" + if (it.scheme != null) " 🧩" else ""
            h.mark.visibility = if (it.marked) View.VISIBLE else View.GONE
            h.root.background = if (it.marked) roundBg(0x00000000, 6f, this@StudioMixActivity, Palette.ACCENT) else null
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

    // ---------- step 4: the video ----------

    private fun finalCard(c: Col) = c.card {
        add(text(ctx, t("4. Видео", "4. Video"), Type.TITLE_M, bold = true), 0)
        val voice = voiceFile.exists()
        if (voice) {
            val ms = (voiceFile.length() - 44).coerceAtLeast(0) / 2 * 1000 / 44_100
            add(text(ctx, t("🎙 Своя озвучка ", "🎙 Own voice-over ") + StudioCuts.stamp(ms), 14f, bold = true), 8)
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
        val schemes = items.count { it.scheme != null }
        val extras = listOfNotNull(
            if (schemes > 0) t("🧩 схем: $schemes", "🧩 schemes: $schemes") else null,
            if (Prefs.subs && words.isNotEmpty()) t("💬 субтитры", "💬 subtitles") else null
        )
        if (extras.isNotEmpty()) muted(t("Добавлено: ", "Added: ") + extras.joinToString(" · "))
        progress("schemes", t("AI составляет схемы…", "AI composes schemes…"))
        progress("subs", t("Распознавание речи…", "Recognising speech…"))
        if (rendering()) {
            renderBar = working(StudioJob.message)
            button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { StudioService.cancel(this@StudioMixActivity) }
        }
        if (busy.isNotEmpty() || rendering()) return@card
        button(t("🎬 Получить видео-микс", "🎬 Get the video mix"), primary = true) { build(false) }
        if (!voice) button(t("🎙 Начать озвучку", "🎙 Start the voice-over"), style = BtnStyle.TONAL) { startVoice() }
        spinner(t("Язык озвучки, субтитров и схем", "Language of the voice-over, subtitles and schemes"),
            Studio.VOICE_LANGS.map { it to Studio.langName(it) }, lang()) { StudioPrefs.voiceLang = it }
        check(t("Только на отмеченных элементах", "Only on ticked elements"), Prefs.onlyMarked) { Prefs.onlyMarked = it }
        row(
            btn(ctx, if (schemes > 0) t("🧩 Схемы заново", "🧩 Schemes again") else t("🧩 Добавить схемы", "🧩 Add schemes"), style = BtnStyle.TONAL) { addSchemes() },
            btn(ctx, t("💬 Добавить субтитры", "💬 Add subtitles"), style = BtnStyle.TONAL) { addSubs() }
        )
        if (schemes > 0 || Prefs.subs) {
            val views = mutableListOf<View>()
            if (schemes > 0) views += btn(ctx, t("✕ Убрать схемы", "✕ Remove schemes"), style = BtnStyle.TEXT) {
                saveItems(items.map { it.copy(scheme = null) })
                render()
            }
            if (Prefs.subs) views += btn(ctx, t("✕ Убрать субтитры", "✕ Remove subtitles"), style = BtnStyle.TEXT) {
                Prefs.subs = false
                render()
            }
            row(*views.toTypedArray())
        }
        val video = lastVideo()
        if (video != null) {
            add(text(ctx, "✅ " + t("Видео сохранено: ", "Video saved: ") + Prefs.savedAs.ifBlank { video.name }, 14f, bold = true), 12)
            if (Prefs.videoSig != StudioMix.signature(items, Prefs.format)) muted(t("Раскадровка менялась после сборки — соберите видео заново.",
                "The storyboard changed after the build — build the video again."))
            row(
                btn(ctx, t("▶ Открыть", "▶ Open"), style = BtnStyle.TONAL) { openVideo(video) },
                btn(ctx, t("↗ Поделиться", "↗ Share"), style = BtnStyle.TEXT) { ReportKit.share(this@StudioMixActivity, video, false, video.name, "video/mp4") }
            )
            if (Prefs.videoVoice) button(t("🤖 Отправить в ИИ", "🤖 Send to AI"), primary = true) { sendToAi(video) }
        }
    }

    private fun rendering() = StudioJob.running && Prefs.pending.isNotEmpty()

    /** The plan of the video: every element is a clip scene; schemes and subtitles where they were added. */
    private fun plan(): StudioPlan {
        val times0 = StudioMix.times(items)
        val ranges = if (words.isEmpty()) items.map { IntRange.EMPTY } else StudioMix.wordsIn(words, times0)
        val times = StudioMix.times(items, ranges)
        val chosen = StudioMix.chosen(items, Prefs.onlyMarked).toSet()
        val subs = Prefs.subs && words.isNotEmpty()
        val caps = ranges.mapIndexed { i, r -> if (subs && i in chosen && !r.isEmpty()) Studio.captions(words, r, maxWords = 1) else emptyList() }
        val notes = notes()
        val scenes = items.mapIndexed { i, it ->
            val say = if (!ranges[i].isEmpty()) ranges[i].joinToString(" ") { k -> words[k].text } else notes[it.clipId].orEmpty()
            if (it.scheme != null) SScene(say, kind = "scheme", scheme = it.scheme, clip = it.clipId) else SScene(say, clip = it.clipId)
        }
        return StudioPlan(
            script = SScript("", scenes), words = words, times = times, captions = caps, totalMs = StudioMix.totalMs(items),
            style = StudioStyle.VIDEO, accent = StudioPrefs.accent, overlay = true, zooms = false, transitions = false,
            clipScenes = items.indices.toSet(), layers = StudioLayers.ALL
        )
    }

    private fun readVoice(): StudioAudio.Pcm? = voiceFile.takeIf { it.exists() }?.let { f ->
        Studio.readWav(f.readBytes())?.let { (data, rate) -> StudioAudio.Pcm(data, rate) }
    }

    /** Builds the video in the background service; it is saved to the gallery when done. */
    private fun build(forRecording: Boolean) {
        if (StudioJob.running) {
            toast(t("Уже собирается другое видео Студии", "Another Studio video is being built"))
            return
        }
        val ready = readyClips()
        if (items.isEmpty() || ready.isEmpty()) return
        val plan = plan()
        val voice = (if (forRecording) null else readVoice())
            ?: StudioAudio.Pcm(ShortArray((plan.totalMs * 44_100 / 1000).toInt() + 1), 44_100)
        val (w, h) = aspect().size(StudioPrefs.quality)
        val day = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "$FILE_PREFIX$day.mp4"
        val bg = StudioBg(null, null, emptyList(), StudioMix.slots(items, ready.associateBy { it.id }), ready.associate { it.id to File(clipsDir, it.file) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
        }
        Prefs.pending = (if (voiceFile.exists() && !forRecording) "v" else "-") + StudioMix.signature(items, Prefs.format)
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
            toast(t("Озвучка записана. Нажмите «Получить видео-микс»", "Voice-over recorded. Tap \"Get the video mix\""))
        }

        bottomSheet(t("🎙 Озвучка поверх микса", "🎙 Voice-over over the mix"), onDismiss = { if (!done) finishTake(false) }) { dismiss ->
            close = dismiss
            muted(t("Видео играет без звука, микрофон пишет. Запись остановится в конце микса (${StudioCuts.stamp(total)}).",
                "The video plays without sound while the microphone records. Recording stops at the end of the mix (${StudioCuts.stamp(total)})."))
            player = VideoView(ctx)
            val hgt = (resources.displayMetrics.heightPixels * 0.45f).toInt()
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
            go = button(t("⏺ Начать запись", "⏺ Start recording"), primary = true) {
                if (recorder != null) {
                    finishTake(true)
                    return@button
                }
                val rec = StudioRecorder { level, ms ->
                    val bars = "▮".repeat((level * 12).toInt().coerceIn(0, 12))
                    state.text = "⏺ ${StudioCuts.stamp(ms)} / ${StudioCuts.stamp(total)}  $bars"
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
    private fun addSchemes() {
        val sel = StudioMix.chosen(items, Prefs.onlyMarked)
        if (sel.isEmpty()) {
            toast(t("Отметьте элементы на раскадровке", "Tick elements on the storyboard"))
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
            "max" to (if (Prefs.onlyMarked) sel.size.coerceAtMost(12) else StudioScheme.maxSchemes(sel.size)).toString(),
            "wish" to "", "protocol" to StudioScheme.PROTOCOL,
            "scenes" to StudioScheme.scenesBlock(scenes, words, selRanges)
        )
        busy = "schemes"
        cancel = false
        render()
        BgTasks.run(this, TASK + "schemes", t("Микс: AI составляет схемы", "Mix: AI composes schemes"), BgTasks.reopen(this), {
            val prov = AiTasks.requireProvider()
            val r = Llm.complete(prov, prov.smartModel, "You are an infographic designer for videos. Answer with JSON only.",
                Prompts.fill("studio_schemes", vars), json = true, maxTokens = 900 + sel.size * 300, task = "studio_schemes", pid = "studio")
            if (cancel) throw java.util.concurrent.CancellationException()
            val list = StudioScheme.parseAnswer(r.text)
                ?: throw LlmException(t("AI не вернул схемы в нужном формате", "AI did not return schemes in the expected format"), r.text)
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
            err(e)
        }, { cancel = true }) { found ->
            // the storyboard may have changed meanwhile: schemes go to their elements by key
            val now = if (mixFile.exists()) StudioMix.fromJson(mixFile.readText()) else items
            saveItems(now.map { found[it.key]?.let { s -> it.copy(scheme = s) } ?: it })
            busy = ""
            if (isDestroyed) return@run
            render()
            toast(if (found.isEmpty()) t("AI не нашёл, что показать схемой", "AI found nothing to show as a scheme") else t("Схем: ${found.size}. Соберите видео", "Schemes: ${found.size}. Build the video"))
        }
    }

    // ---------- clips ----------

    /**
     * Quick look at a found video: first a strip of frames from the YouTube storyboard (how the video goes, before
     * playing it); a tap on a frame plays the lowest quality stream from there in a mini player (with sound when
     * there is one), through the NewPipe YouTube data source. Tap on the picture pauses; ⏪/⏩ jump 10 s.
     */
    private inner class MiniLook {
        private val looks = HashMap<String, StudioCutsLoad.Look>()
        private var player: com.google.android.exoplayer2.ExoPlayer? = null
        private var current = ""
        private var started = false
        private val surface = android.view.TextureView(this@StudioMixActivity)
        private val frame = FrameLayout(this@StudioMixActivity).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            setOnClickListener { player?.let { p -> p.playWhenReady = !p.playWhenReady } }
        }
        private val strip = LinearLayout(this@StudioMixActivity).apply { orientation = LinearLayout.HORIZONTAL }
        private val stripScroll = android.widget.HorizontalScrollView(this@StudioMixActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        private val state = text(this@StudioMixActivity, "", 12f, color = Palette.MUTED)
        private val playBtn = btn(this@StudioMixActivity, "▶", style = BtnStyle.TONAL) { toggle() }
        private val tick = object : Runnable {
            override fun run() {
                val p = player ?: return
                if (current.isNotEmpty() && started && p.duration > 0) {
                    state.text = StudioCuts.stamp(p.currentPosition) + " / " + StudioCuts.stamp(p.duration)
                    playBtn.text = if (p.playWhenReady) "⏸" else "▶"
                }
                frame.postDelayed(this, 500)
            }
        }
        val view: LinearLayout = LinearLayout(this@StudioMixActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(stripScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(4) })
            addView(LinearLayout(this@StudioMixActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(playBtn)
                addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
                addView(btn(this@StudioMixActivity, "⏪ 10", style = BtnStyle.TEXT) { seek(-10_000) })
                addView(btn(this@StudioMixActivity, "10 ⏩", style = BtnStyle.TEXT) { seek(10_000) })
            })
        }

        private fun seek(d: Long) {
            val p = player ?: return
            if (!started) return
            p.seekTo((p.currentPosition + d).coerceIn(0, if (p.duration > 0) maxOf(0, p.duration - 500) else Long.MAX_VALUE))
        }

        private fun toggle() {
            val p = player
            if (started && p != null) p.playWhenReady = !p.playWhenReady else play(0)
        }

        private fun exo(): com.google.android.exoplayer2.ExoPlayer = player ?: com.google.android.exoplayer2.ExoPlayer.Builder(this@StudioMixActivity).build().also { p ->
            player = p
            p.setVideoTextureView(surface)
            p.addListener(object : com.google.android.exoplayer2.Player.Listener {
                override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
                    // keep the shape of the video inside the frame
                    if (size.width <= 0 || size.height <= 0) return
                    val fw = frame.width.takeIf { it > 0 } ?: return
                    val fh = frame.height
                    val vh = size.height / size.pixelWidthHeightRatio.coerceAtLeast(0.1f)
                    val k = minOf(fw.toFloat() / size.width, fh.toFloat() / vh)
                    surface.layoutParams = FrameLayout.LayoutParams((size.width * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    state.text = t("Не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName)
                }
            })
            frame.post(tick)
        }

        /** Opens the look at [url]: the frames strip first, the video only on a tap. */
        fun open(url: String) {
            stop()
            current = url
            strip.removeAllViews()
            playBtn.text = "▶"
            looks[url]?.let { return show(it) }
            state.text = t("Загрузка кадров…", "Loading frames…")
            Lumen.async({ StudioCutsLoad.look(url) }, { e ->
                if (current == url) state.text = (e.message ?: e.javaClass.simpleName).take(160)
            }) { lk ->
                looks[url] = lk
                if (current == url && !isDestroyed) show(lk)
            }
        }

        private fun show(lk: StudioCutsLoad.Look) {
            strip.removeAllViews()
            val h = dp(64)
            for ((ms, bmp) in lk.frames) {
                val w = (h.toLong() * bmp.width / bmp.height.coerceAtLeast(1)).toInt()
                strip.addView(FrameLayout(this@StudioMixActivity).apply {
                    addView(ImageView(this@StudioMixActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }, FrameLayout.LayoutParams(w, h))
                    addView(text(this@StudioMixActivity, StudioCuts.stamp(ms), 10f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                        setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
                        setPadding(dp(3), 0, dp(3), dp(1))
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                    contentDescription = t("Смотреть с ", "Watch from ") + StudioCuts.stamp(ms)
                    setOnClickListener { play(ms) }
                }, LinearLayout.LayoutParams(w, h).apply { rightMargin = dp(3) })
            }
            stripScroll.visibility = if (lk.frames.isEmpty()) View.GONE else View.VISIBLE
            state.text = when {
                lk.stream.isEmpty() -> t("Только кадры: поток для просмотра не найден", "Frames only: no stream to watch")
                lk.frames.isEmpty() -> t("Кадров нет — ▶ включит видео", "No frames — ▶ plays the video")
                else -> t("Кадры по всему видео. Нажмите на кадр — видео с этого места", "Frames across the video. Tap a frame to watch from there")
            }
        }

        /** Plays the video from [fromMs] in the mini player. */
        fun play(fromMs: Long) {
            val lk = looks[current]?.takeIf { it.stream.isNotEmpty() } ?: return
            val src = com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                org.schabi.newpipe.player.datasource.YoutubeHttpDataSource.Factory().setRangeParameterEnabled(false).setRnParameterEnabled(true)
            ).createMediaSource(com.google.android.exoplayer2.MediaItem.fromUri(lk.stream))
            frame.visibility = View.VISIBLE
            state.text = t("Загрузка…", "Loading…")
            val p = exo()
            p.setMediaSource(src)
            p.prepare()
            if (fromMs > 0) p.seekTo(fromMs)
            p.playWhenReady = true
            started = true
            playBtn.text = "⏸"
        }

        fun stop() {
            current = ""
            started = false
            frame.visibility = View.GONE
            player?.stop()
        }

        fun release() {
            stop()
            frame.removeCallbacks(tick)
            player?.release()
            player = null
        }
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
        val look = MiniLook()
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
                val sub = listOf(h.channel, if (h.durationMs > 0) StudioCuts.stamp(h.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
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

        fun search(q: String) {
            if (q.isBlank()) return
            info.text = t("Поиск…", "Searching…")
            Lumen.async({
                YouTube.search(listOf(q), SearchFilters(limit = 30)).map {
                    CutHit(it.url, it.title, it.channel, if (it.duration > 0) it.duration * 1000 else 0,
                        // a light 320×180 picture is enough for the list
                        YtQuery.videoIdFromUrl(it.url)?.let { id -> "https://i.ytimg.com/vi/$id/mqdefault.jpg" } ?: it.thumb)
                }
            }, { e -> info.text = (e.message ?: e.javaClass.simpleName).take(200) }) { found ->
                hits = found
                marked.clear()
                watching = ""
                look.stop()
                info.text = if (found.isEmpty()) t("Ничего не нашлось. Попробуйте другие слова.", "Nothing found. Try other words.")
                else t("Найдено: ${found.size}. Отметьте нужные.", "Found: ${found.size}. Tick the ones you need.")
                fill()
            }
        }

        bottomSheet(t("Найти видео для микса", "Find videos for the mix"), onDismiss = { look.release() }) { _ ->
            muted(t("Поиск по названию на YouTube. Картинка или ▾ — кадры по всему видео; тап по кадру — видео с этого места в низком качестве. Отмеченные добавятся с 0:00 на 5 с; отрезок задайте кнопкой «⏱ Отрезок». Можно искать снова и добавлять ещё.",
                "Search YouTube by title. The picture or ▾ shows frames across the video; tap a frame to watch from there in low quality. Ticked ones are added from 0:00 for 5 s; set the piece with \"⏱ Piece\". You can search again and add more."))
            val query = edit(t("Что искать", "What to search"))
            button(t("🔎 Искать", "🔎 Search"), style = BtnStyle.TONAL) { search(query.text.toString().trim()) }
            info = add(text(ctx, "", 13f, color = Palette.MUTED), 4)
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
            saveClips(clips.filter { it.id !in ids })
            render()
        }

    /** Downloads the clips that are not ready, two at a time; a failed one is reported and skipped. */
    private fun download() {
        val todo = clips.filter { it !in readyClips() }
        if (todo.isEmpty()) return
        busy = "dl"
        cancel = false
        render()
        val dirNow = clipsDir
        val maxH = StudioPrefs.quality
        val key = TASK + "dl"
        BgTasks.run(this, key, t("Микс: загрузка клипов", "Mix: downloading clips"), BgTasks.reopen(this), {
            val done = java.util.concurrent.ConcurrentHashMap<String, SClip>()
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            Par.mapAll(todo, 2) { i, cl ->
                if (cancel) throw java.util.concurrent.CancellationException()
                BgTasks.progress(key, t("Клип ${i + 1}/${todo.size}", "Clip ${i + 1}/${todo.size}"))
                try {
                    done[cl.id] = StudioCutsLoad.fetch(cl, dirNow, maxH, { cancel }) { BgTasks.progress(key, it) }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "mix clip ${cl.id}: ${e.message}")
                    errors += "${cl.id}: ${e.message ?: e.javaClass.simpleName}"
                }
                i
            }
            HashMap(done) to errors.toList()
        }, { e ->
            busy = ""
            if (isDestroyed) return@run
            render()
            err(e)
        }, { cancel = true }) { (done, errors) ->
            // the list on disk may have changed while downloading
            val now = if (clipsFile.exists()) StudioCuts.fromJson(clipsFile.readText()) else clips
            saveClips(now.map { cl -> done[cl.id]?.takeIf { it.url == cl.url && it.fromMs == cl.fromMs && it.toMs == cl.toMs } ?: cl })
            busy = ""
            if (isDestroyed) return@run
            render()
            if (errors.isNotEmpty()) showText(t("Не все клипы скачались", "Some clips failed"), errors.joinToString("\n"))
            else toast(t("Клипов скачано: ${done.size}", "Clips downloaded: ${done.size}"))
        }
    }
}

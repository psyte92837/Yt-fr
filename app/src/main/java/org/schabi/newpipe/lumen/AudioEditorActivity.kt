/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * "🎧 Audio editor" of the Studio (both apps; also "Share → Audio editor" for audio files): projects of several
 * tracks made of pieces of files ([AudioEdit], nothing is destroyed), a waveform with zoom, cursor, selection and
 * dragging of pieces; cut / delete / crop / copy-paste / silence / pauses removal, joining and mixing files,
 * fades, normalising and loudness, crossfades, noise removal, EQ, speed and pitch, reverse, 50 effects of
 * [EditSound], a library of sounds, tones and noise, bleeping, recording from the microphone; saved as M4A or WAV
 * to Music/Lumen or shared.
 */
class AudioEditorActivity : LumenBase() {
    companion object {
        const val EXTRA_PROJECT = "audio_project"
        const val EXTRA_URIS = "audio_uris"
        private const val REQ_IMPORT = 7811
        private const val REQ_MIC = 7812
        private const val REQ_SAVE_AS = 7813
        private const val MAX_UNDO = 60

        /** What was copied, kept between projects. */
        private var clipboard: ASnippet? = null
        private var clipboardSrc: List<ASource> = emptyList()
        private var clipboardPid = ""

        fun open(ctx: Context, projectId: String? = null) {
            ctx.startActivity(Intent(ctx, AudioEditorActivity::class.java).apply { projectId?.let { putExtra(EXTRA_PROJECT, it) } }
                .addFlags(if (ctx is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private val TRACK_COLORS = intArrayOf(
            0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(),
            0xFF8B5CF6.toInt(), 0xFF14B8A6.toInt(), 0xFFEF4444.toInt(), 0xFF6366F1.toInt()
        )
    }

    private var p: AProject? = null
    private val undo = ArrayList<String>()
    private val redo = ArrayList<String>()
    private val player = AudioPlayer()
    private var wave: WaveView? = null
    private var timeText: TextView? = null
    private var playBtn: TextView? = null
    private var cursor = 0L
    private var sel: LongRange? = null
    private var selTrack = -1
    private var selClip = -1
    private var loopOn = false
    private var selectMode = false
    private var tool = 0
    private var busy = ""
    private var busyText: TextView? = null
    private var busyProg: ProgressBar? = null
    @Volatile private var cancelFlag = false
    private val peaks = HashMap<String, Peaks>()
    private var fromHome = false
    private var importMode = "append"
    private var importTarget = 0
    private var lastExport: File? = null
    private var pendingSound = "ding"
    private var recorder: StudioRecorder? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!player.playing) { updateTime(); return }
            val ms = player.posMs
            wave?.let { w -> w.playMs = ms; w.follow(ms); w.invalidate() }
            updateTime(ms)
            wave?.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tool = Lumen.prefs.getInt("audio_tool", 0).coerceIn(0, 5)
        player.onEnd = { playBtn?.text = "▶"; wave?.let { it.playMs = -1; it.invalidate() }; updateTime() }
        handle(intent, savedInstanceState?.getString("pid"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        p?.let { outState.putString("pid", it.id) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, null)
    }

    private fun handle(i: Intent?, saved: String?) {
        @Suppress("DEPRECATION")
        val uris: List<Uri> = i?.getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty()
        val pid = saved ?: i?.getStringExtra(EXTRA_PROJECT)
        when {
            uris.isNotEmpty() && saved == null -> {
                i?.removeExtra(EXTRA_URIS)
                val first = AudioImport.name(this, uris[0]).substringBeforeLast('.')
                val q = AudioStore.create(if (uris.size > 1) t("Склейка: ", "Joined: ") + first else first, t("Дорожка 1", "Track 1"))
                fromHome = false
                openProject(q)
                importMode = "append"
                importUris(uris)
            }
            pid != null -> {
                val q = AudioStore.load(pid)
                if (q != null) openProject(q) else { p = null; render() }
            }
            else -> {
                fromHome = true
                p = null
                render()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (player.playing) stopPlay()
        AudioPreview.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
        recorder?.let { runCatching { it.stop() } }
        recorder = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (p != null && fromHome && busy.isEmpty()) {
            stopPlay()
            p = null
            render()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------- project state ----------

    private fun openProject(q0: AProject) {
        stopPlay()
        // no undo history yet: files no piece uses (e.g. the originals after noise removal) can go
        val q = if (AudioEdit.unused(q0).isEmpty()) q0 else runCatching { AudioStore.save(AudioEdit.dropUnused(q0)) }.getOrDefault(q0)
        AudioStore.cleanup(q)
        p = q
        undo.clear()
        redo.clear()
        peaks.clear()
        cursor = 0
        sel = null
        selTrack = -1
        selClip = -1
        wave = null
        render()
    }

    private fun peaksOf(sid: String): Peaks = peaks.getOrPut(sid) {
        val pid = p?.id ?: return Peaks(ByteArray(0))
        Peaks(runCatching { AudioStore.peaks(pid, sid).readBytes() }.getOrDefault(ByteArray(0)))
    }

    /** A new state of the project: one undo step, saved at once, heard at once. */
    private fun commit(n: AProject, keepView: Boolean = false) {
        val old = p ?: return
        undo += AudioEdit.toJson(old).toString()
        while (undo.size > MAX_UNDO) undo.removeAt(0)
        redo.clear()
        set(n, keepView)
    }

    private fun set(n: AProject, keepView: Boolean = false) {
        val saved = try {
            AudioStore.save(n)
        } catch (e: Exception) {
            L.e("audio", "save: ${e.message}", e)
            toast(t("Не удалось сохранить проект", "Could not save the project"))
            n
        }
        p = saved
        player.project = saved
        if (selClip >= 0 && saved.clip(selClip) == null) selClip = -1
        if (selTrack >= 0 && saved.track(selTrack) == null) selTrack = -1
        cursor = cursor.coerceIn(0, max(0L, saved.length))
        if (keepView) wave?.invalidate() else render()
    }

    private fun undoStep() {
        val q = p ?: return
        if (undo.isEmpty()) return toast(t("Нечего отменять", "Nothing to undo"))
        redo += AudioEdit.toJson(q).toString()
        set(AudioEdit.fromJson(org.json.JSONObject(undo.removeAt(undo.size - 1))))
    }

    private fun redoStep() {
        val q = p ?: return
        if (redo.isEmpty()) return toast(t("Нечего повторять", "Nothing to redo"))
        undo += AudioEdit.toJson(q).toString()
        set(AudioEdit.fromJson(org.json.JSONObject(redo.removeAt(redo.size - 1))))
    }

    private fun scope(): Set<Int>? = if (selTrack < 0) null else setOf(selTrack)

    /** The selection, else the selected piece, else the whole project. */
    private fun targetName(): String {
        val q = p ?: return ""
        val r = sel
        val c = q.clip(selClip)
        return when {
            r != null -> t("выделенный участок", "the selection") + " " + AudioEdit.time(r.first) + "–" + AudioEdit.time(r.last) +
                (if (selTrack >= 0) " · " + (q.track(selTrack)?.name ?: "") else "")
            c != null -> t("кусок «${c.name}»", "the piece \"${c.name}\"")
            else -> t("весь проект", "the whole project")
        }
    }

    private fun applyTo(q: AProject, f: (AClip) -> AClip): AProject {
        val r = sel
        return when {
            r != null -> AudioEdit.mapRange(q, r.first, r.last, scope(), f)
            q.clip(selClip) != null -> AudioEdit.updateClip(q, selClip, f)
            else -> AudioEdit.mapAll(q, f)
        }
    }

    private fun targetRange(q: AProject): LongRange {
        sel?.let { return it }
        q.clip(selClip)?.let { return it.at..it.end }
        return 0L..max(1L, q.length)
    }

    /** Clips the target touches (for work on their source files). */
    private fun targetClips(q: AProject): List<AClip> {
        val r = sel
        return when {
            r != null -> q.tracks.filter { selTrack < 0 || it.id == selTrack }.flatMap { t -> t.clips.filter { it.at < r.last && it.end > r.first } }
            q.clip(selClip) != null -> listOf(q.clip(selClip)!!)
            else -> q.clips
        }
    }

    // ---------- playback ----------

    private fun play(fromMs: Long, loop: LongRange?) {
        val q = p ?: return
        if (q.length <= 0) return toast(t("Добавьте звук: «🎚 Дорожки» → «📂 Добавить файл» или запись", "Add sound: \"🎚 Tracks\" → \"📂 Add a file\" or a recording"))
        player.loop = loop
        player.play(q, fromMs.coerceIn(0, max(0L, q.length - 10)))
        playBtn?.text = "⏸"
        wave?.removeCallbacks(tick)
        wave?.postOnAnimation(tick)
    }

    private fun togglePlay() {
        if (player.playing) {
            val at = player.posMs
            stopPlay()
            cursor = at.coerceIn(0, p?.length ?: 0)
            wave?.invalidate()
            updateTime()
            return
        }
        val r = sel
        val loop = if (loopOn && r != null && r.last - r.first > 50) r else null
        val from = if (loop != null && cursor !in r!!) r.first else if (cursor >= (p?.length ?: 0) - 20) 0L else cursor
        play(from, loop)
    }

    private fun stopPlay() {
        player.stop()
        playBtn?.text = "▶"
        wave?.let { it.playMs = -1; it.invalidate() }
    }

    private fun playRange(r: LongRange) = play(r.first, r)

    private fun updateTime(ms: Long = cursor) {
        val q = p ?: return
        timeText?.text = AudioEdit.time(ms) + " / " + AudioEdit.time(q.length)
    }

    // ---------- background work ----------

    private fun <T> job(label: String, work: (cancelled: () -> Boolean, progress: (Int) -> Unit) -> T, done: (T) -> Unit) {
        if (busy.isNotEmpty()) return toast(t("Подождите: идёт «$busy»", "Wait: \"$busy\" is running"))
        stopPlay()
        busy = label
        cancelFlag = false
        render()
        Lumen.async({
            work({ cancelFlag || isFinishing }) { pct -> Lumen.ui { busyProg?.progress = pct; busyText?.text = "$label · $pct %" } }
        }, onError = { e ->
            busy = ""
            render()
            if (e is CancellationException) toast(t("Остановлено", "Stopped")) else err(e)
        }) { r ->
            busy = ""
            done(r)
            if (busy.isEmpty()) render()
        }
    }

    // ---------- page ----------

    private fun render() {
        val q = p
        if (q == null) return home()
        val c = Col(this)
        if (busy.isNotEmpty()) busyRow(c)
        context(c, q)
        c.add(toolTabs(listOf(
            t("✂ Правка", "✂ Edit"), t("🔊 Громкость", "🔊 Volume"), t("✨ Эффекты", "✨ Effects"),
            t("🎵 Звуки", "🎵 Sounds"), t("🎚 Дорожки", "🎚 Tracks"), t("💾 Сохранить", "💾 Save")
        ), tool) {
            tool = it
            Lumen.prefs.edit().putInt("audio_tool", it).apply()
            render()
        }, 16)
        when (tool) {
            0 -> editTools(c, q)
            1 -> volumeTools(c, q)
            2 -> effectTools(c, q)
            3 -> soundTools(c, q)
            4 -> trackTools(c, q)
            else -> saveTools(c, q)
        }
        page(q.name, c.root, actions = listOf("↶" to { undoStep() }, "↷" to { redoStep() }, "⋮" to { projectMenu() }), header = header(q), keepScroll = true)
        updateTime()
    }

    /** The six tool groups in two rows of three. */
    private fun toolTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        labels.chunked(3).forEachIndexed { r, row ->
            addView(segBar(this@AudioEditorActivity, row, if (selected / 3 == r) selected % 3 else -1) { onSelect(r * 3 + it) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = dp(6) })
        }
    }

    private fun busyRow(c: Col) {
        val box = Col(this, 12)
        box.root.background = roundBg(Palette.ACCENT_SOFT, 16f, this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        busyText = text(this, busy, 14f, bold = true, color = Palette.ON_SOFT)
        row.addView(busyText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconBtn(this, "✕", Palette.ON_SOFT) { cancelFlag = true }, LinearLayout.LayoutParams(dp(44), dp(44)))
        box.add(row, 0)
        busyProg = busyBar(this).also { box.add(it, 6) }
        c.add(box.root, 0)
    }

    private fun header(q: AProject): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val w = wave ?: WaveView(this).also { wave = it }
        (w.parent as? ViewGroup)?.removeView(w)
        col.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w.wantedHeight(q)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        row.addView(iconBtn(this, "⏮") { cursor = 0; wave?.scrollTo(0); wave?.invalidate(); if (player.playing) play(0, player.loop) else updateTime() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        playBtn = text(this, if (player.playing) "⏸" else "▶", 22f, bold = true, color = Palette.ON_ACCENT).apply {
            gravity = Gravity.CENTER
            background = ripple(this@AudioEditorActivity, roundBg(Palette.ACCENT, 24f, this@AudioEditorActivity), Palette.ON_ACCENT)
            contentDescription = t("Играть или пауза", "Play or pause")
            setOnClickListener { togglePlay() }
        }
        row.addView(playBtn, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginStart = dp(4); marginEnd = dp(8) })
        timeText = text(this, "", 14f, bold = true).apply { maxLines = 1; setOnClickListener { goTo() } }
        row.addView(timeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(this, "🔁", loopOn) { loopOn = !loopOn; render() }.apply { contentDescription = t("Повторять выделенное", "Loop the selection") })
        row.addView(chip(this, "⟦⟧", selectMode) { selectMode = !selectMode; render() }.apply {
            contentDescription = t("Режим выделения: проведите по волне", "Selection mode: drag over the wave")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        row.addView(iconBtn(this, "⤢") { wave?.fit() }.apply { contentDescription = t("Весь проект на экране", "Fit the project") }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(row)
        return col
    }

    /** What is selected now and the most used actions on it. */
    private fun context(c: Col, q: AProject) {
        val r = sel
        val cl = q.clip(selClip)
        val box = Col(this, 12)
        box.root.background = roundBg(Palette.CARD, 16f, this)
        when {
            r != null -> {
                box.add(text(this, "⟦ " + AudioEdit.time(r.first) + " – " + AudioEdit.time(r.last) + " ⟧  " +
                    String.format(java.util.Locale.US, "%.1f", (r.last - r.first) / 1000f) + t(" с", " s"), 15f, bold = true), 0)
                if (q.tracks.size > 1) box.add(chipScroller(this, listOf(chip(this, t("Все дорожки", "All tracks"), selTrack < 0) { selTrack = -1; render() }) +
                    q.tracks.map { tr -> chip(this, tr.name, selTrack == tr.id) { selTrack = tr.id; render() } }), 6)
                box.add(FlowLayout(this, dp(6), dp(6)).apply {
                    addView(chip(this@AudioEditorActivity, t("▶ Слушать", "▶ Listen")) { playRange(r) })
                    addView(chip(this@AudioEditorActivity, t("⌫ Удалить", "⌫ Delete")) { deleteSel(true) })
                    addView(chip(this@AudioEditorActivity, t("⬚ Оставить", "⬚ Keep only")) { cropSel() })
                    addView(chip(this@AudioEditorActivity, t("⧉ Копировать", "⧉ Copy")) { copySel(false) })
                    addView(chip(this@AudioEditorActivity, t("✂ Вырезать", "✂ Cut")) { copySel(true) })
                    addView(chip(this@AudioEditorActivity, t("🔇 Тишина", "🔇 Mute")) { commit(AudioEdit.gainRange(q, r.first, r.last, 0f, scope())) })
                    addView(chip(this@AudioEditorActivity, "✕") { sel = null; render() })
                }, 8)
            }
            cl != null -> {
                val tr = q.trackOf(cl.id)
                box.add(text(this, "🎵 " + cl.name, 15f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 0)
                box.add(text(this, AudioEdit.time(cl.at) + " – " + AudioEdit.time(cl.end) + " · " + (tr?.name ?: "") + clipBadges(cl), 12f, color = Palette.MUTED), 2)
                box.add(FlowLayout(this, dp(6), dp(6)).apply {
                    addView(chip(this@AudioEditorActivity, t("▶ Слушать", "▶ Listen")) { playRange(cl.at..cl.end) })
                    addView(chip(this@AudioEditorActivity, t("⟦⟧ Выделить", "⟦⟧ Select")) { sel = cl.at..cl.end; selTrack = if (q.tracks.size > 1) tr?.id ?: -1 else -1; render() })
                    addView(chip(this@AudioEditorActivity, t("◢◣ Края", "◢◣ Fades")) { fadesSheet(cl) })
                    addView(chip(this@AudioEditorActivity, t("⧉ Дубль", "⧉ Duplicate")) { commit(AudioEdit.duplicateClip(q, cl.id)) })
                    addView(chip(this@AudioEditorActivity, t("↕ Дорожка", "↕ Track")) { moveToTrack(cl) })
                    addView(chip(this@AudioEditorActivity, t("⇤⇥ Сброс", "⇤⇥ Reset")) {
                        commit(AudioEdit.updateClip(q, cl.id) { AClip(it.id, it.src, it.from, it.to, it.at, name = it.name) })
                    })
                    addView(chip(this@AudioEditorActivity, t("🗑 Удалить", "🗑 Delete")) {
                        selClip = -1
                        commit(AudioEdit.removeClip(q, cl.id, ripple = q.tracks.size == 1))
                    })
                    addView(chip(this@AudioEditorActivity, "✕") { selClip = -1; render() })
                }, 8)
                box.add(text(this, t("Удерживайте кусок на волне, чтобы перетащить его.", "Hold a piece on the wave to drag it."), 11f, color = Palette.MUTED), 6)
            }
            else -> {
                box.add(text(this, if (q.length == 0L) t("Проект пуст", "The project is empty") else t("Курсор ", "Cursor ") + AudioEdit.time(cursor), 15f, bold = true), 0)
                box.add(text(this, if (q.length == 0L)
                    t("Добавьте файл, запись или звук — «🎚 Дорожки» и «🎵 Звуки».", "Add a file, a recording or a sound — \"🎚 Tracks\" and \"🎵 Sounds\".")
                else t("Тап — курсор и кусок · двойной тап — выделить кусок · удержание на пустом месте или ⟦⟧ — выделение · щипок — масштаб.",
                    "Tap — cursor and piece · double tap — select a piece · hold on an empty spot or ⟦⟧ — selection · pinch — zoom."), 12f, color = Palette.MUTED), 4)
            }
        }
        c.add(box.root, 0)
    }

    private fun clipBadges(c: AClip): String = buildString {
        if (c.gain != 1f) append(" · ").append(dbText(c.gain))
        if (c.speed != 1f) append(" · ×").append(trim(c.speed))
        if (abs(c.pitch) > 0.01f) append(" · ♪").append(if (c.pitch > 0) "+" else "").append(trim(c.pitch))
        if (c.reverse) append(" · ↺")
        if (c.fx.isNotEmpty()) append(" · ").append(EditSound.label(c.fx))
        if (c.hasEq) append(" · 🎛")
    }

    private fun trim(f: Float): String = if (f == f.roundToInt().toFloat()) f.roundToInt().toString() else String.format(java.util.Locale.US, "%.2f", f).trimEnd('0').trimEnd('.')

    private fun dbText(g: Float): String = if (g <= 0.0001f) "−∞ dB" else String.format(java.util.Locale.US, "%+.1f dB", 20 * kotlin.math.log10(g)).replace('-', '−')

    /** A grid of big tool buttons, three in a row. */
    private fun grid(c: Col, items: List<Triple<String, String, () -> Unit>>) {
        items.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, (emoji, label, act) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = ripple(this@AudioEditorActivity, roundBg(Palette.CARD, 14f, this@AudioEditorActivity), Palette.ON_SURFACE)
                    addView(text(this@AudioEditorActivity, emoji, 20f).apply { gravity = Gravity.CENTER })
                    addView(text(this@AudioEditorActivity, label, 12f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    contentDescription = label
                    setOnClickListener { act() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(76), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            repeat(3 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginStart = dp(8) }) }
            c.add(row, 8)
        }
    }

    // ---------- ✂ edit ----------

    private fun editTools(c: Col, q: AProject) {
        grid(c, listOf(
            Triple("✂", t("Разрезать в курсоре", "Split at cursor")) { commit(AudioEdit.splitAt(q, cursor, scope())); toast(t("Разрезано в ", "Split at ") + AudioEdit.time(cursor)) },
            Triple("⟦", t("Начало выделения", "Selection start")) { setEdge(true) },
            Triple("⟧", t("Конец выделения", "Selection end")) { setEdge(false) },
            Triple("⬚", t("Выделить всё", "Select all")) { if (q.length > 0) { sel = 0L..q.length; render() } },
            Triple("⌫", t("Удалить выделенное", "Delete selection")) { deleteSel(true) },
            Triple("⌧", t("Удалить, оставив паузу", "Delete, keep a gap")) { deleteSel(false) },
            Triple("⧉", t("Копировать", "Copy")) { copySel(false) },
            Triple("📋", t("Вставить в курсор", "Paste at cursor")) { paste(true) },
            Triple("≡", t("Вставить поверх", "Paste over")) { paste(false) },
            Triple("⬚", t("Оставить только выделенное", "Crop to selection")) { cropSel() },
            Triple("⏸", t("Вставить тишину", "Insert silence")) { silenceSheet() },
            Triple("🧹", t("Убрать паузы", "Remove pauses")) { pausesSheet() },
            Triple("📍", t("Метка в курсоре", "Marker at cursor")) { commit(AudioEdit.addMarker(q, cursor, "${q.markers.size + 1}"), keepView = false) },
            Triple("⏱", t("Перейти ко времени", "Go to time")) { goTo() },
            Triple("🔀", t("Плавные стыки", "Crossfades")) { crossfadeSheet() }
        ))
        if (q.markers.isNotEmpty()) {
            c.add(text(this, t("Метки", "Markers"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            c.add(FlowLayout(this, dp(6), dp(6)).apply {
                q.markers.forEach { m ->
                    addView(chip(this@AudioEditorActivity, "📍 ${m.name} · ${AudioEdit.time(m.at)}") {
                        cursor = m.at
                        wave?.show(m.at)
                        render()
                    }.apply {
                        setOnLongClickListener { commit(AudioEdit.removeMarker(q, m.at)); toast(t("Метка убрана", "Marker removed")); true }
                    })
                }
            }, 6)
            c.muted(t("Тап — перейти, удержание — убрать.", "Tap — go, hold — remove."))
        }
    }

    private fun setEdge(start: Boolean) {
        val q = p ?: return
        val r = sel
        sel = if (start) {
            val b = r?.last ?: q.length
            if (b - cursor < 20) null else cursor..b
        } else {
            val a = r?.first ?: 0L
            if (cursor - a < 20) null else a..cursor
        }
        if (sel == null) toast(t("Курсор должен быть внутри: начало раньше конца", "Put the cursor so the start is before the end"))
        render()
    }

    private fun needSel(): LongRange? = sel ?: run { toast(t("Сначала выделите участок: ⟦⟧ или удержание на волне", "Select a part first: ⟦⟧ or hold on the wave")); null }

    private fun deleteSel(ripple: Boolean) {
        val q = p ?: return
        val r = needSel() ?: return
        sel = null
        cursor = r.first
        commit(AudioEdit.deleteRange(q, r.first, r.last, scope(), ripple))
    }

    private fun cropSel() {
        val q = p ?: return
        val r = needSel() ?: return
        sel = null
        cursor = 0
        commit(AudioEdit.cropTo(q, r.first, r.last))
        wave?.fit()
    }

    private fun copySel(cut: Boolean) {
        val q = p ?: return
        val r = needSel() ?: return
        val s = AudioEdit.copy(q, r.first, r.last, scope())
        clipboard = s
        clipboardPid = q.id
        clipboardSrc = q.sources.filter { src -> s.tracks.any { tr -> tr.any { it.src == src.id } } }
        if (cut) {
            sel = null
            cursor = r.first
            commit(AudioEdit.deleteRange(q, r.first, r.last, scope(), true))
        } else snack(t("Скопировано ${AudioEdit.time(s.len)}", "Copied ${AudioEdit.time(s.len)}"))
    }

    private fun paste(ripple: Boolean) {
        var q = p ?: return
        val s = clipboard ?: return toast(t("Сначала скопируйте участок", "Copy a part first"))
        // pieces copied in another project: bring their files along
        if (clipboardPid != q.id) {
            for (src in clipboardSrc) {
                if (q.source(src.id) != null) continue
                val from = AudioStore.pcm(clipboardPid, src.id)
                if (!from.exists()) return toast(t("Скопированный звук уже удалён", "The copied sound is gone"))
                from.copyTo(AudioStore.pcm(q.id, src.id), overwrite = true)
                AudioStore.peaks(clipboardPid, src.id).takeIf { it.exists() }?.copyTo(AudioStore.peaks(q.id, src.id), overwrite = true)
                q = AudioEdit.addSource(q, src)
            }
        }
        val track = if (selTrack >= 0) selTrack else q.tracks.first().id
        val n = if (ripple) AudioEdit.paste(q, s, cursor, track, true) else {
            val (q2, tid) = AudioEdit.freeTrack(q, cursor, cursor + s.len, t("Дорожка ${q.tracks.size + 1}", "Track ${q.tracks.size + 1}"))
            AudioEdit.paste(q2, s, cursor, tid, false)
        }
        commit(n)
    }

    private fun goTo() {
        val q = p ?: return
        prompt(t("Перейти ко времени", "Go to time"), t("например 1:05.5 или 65", "e.g. 1:05.5 or 65"), AudioEdit.time(cursor)) { s ->
            val ms = AudioEdit.parseTime(s) ?: return@prompt toast(t("Не понял время", "Cannot read the time"))
            cursor = ms.coerceIn(0, q.length)
            wave?.show(cursor)
            render()
        }
    }

    private fun silenceSheet() {
        val q = p ?: return
        bottomSheet(t("⏸ Вставить тишину в курсор", "⏸ Insert silence at the cursor")) { dismiss ->
            muted(t("Всё после курсора сдвинется вправо", "Everything after the cursor moves right") + if (selTrack >= 0) " · " + (q.track(selTrack)?.name ?: "") else "")
            add(FlowLayout(ctx, dp(6), dp(6)).apply {
                listOf(250L, 500L, 1000L, 2000L, 3000L, 5000L, 10_000L).forEach { ms ->
                    addView(chip(ctx, trim(ms / 1000f) + t(" с", " s")) {
                        dismiss()
                        commit(AudioEdit.insertGap(q, cursor, ms, scope()))
                    })
                }
            })
        }
    }

    private fun pausesSheet() {
        val q = p ?: return
        if (q.length <= 0) return
        var thr = Lumen.prefs.getInt("audio_pause_thr", -45)
        var minMs = Lumen.prefs.getLong("audio_pause_min", 600L)
        var pad = Lumen.prefs.getLong("audio_pause_pad", 120L)
        bottomSheet(t("🧹 Убрать паузы", "🧹 Remove pauses")) { dismiss ->
            muted(t("Lumen найдёт тихие места во всём проекте и вырежет их, сдвинув остальное. У речи остаётся небольшой запас.",
                "Lumen finds quiet places in the whole project and cuts them out, closing the gaps. A little margin stays around speech."))
            add(text(ctx, t("Что считать тишиной", "What counts as silence"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(-55 to t("Очень тихо (−55 дБ)", "Very quiet (−55 dB)"), -45 to t("Тихо (−45)", "Quiet (−45)"), -38 to t("Фон, шум (−38)", "Background (−38)")), thr) {
                thr = it; Lumen.prefs.edit().putInt("audio_pause_thr", it).apply()
            }, 4)
            add(text(ctx, t("Паузы длиннее", "Pauses longer than"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(300L to "0.3 s", 600L to "0.6 s", 1000L to "1 s", 2000L to "2 s"), minMs) {
                minMs = it; Lumen.prefs.edit().putLong("audio_pause_min", it).apply()
            }, 4)
            add(text(ctx, t("Оставить у речи", "Keep next to speech"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(60L to "0.06 s", 120L to "0.12 s", 250L to "0.25 s"), pad) {
                pad = it; Lumen.prefs.edit().putLong("audio_pause_pad", it).apply()
            }, 4)
            button(t("🧹 Найти и убрать", "🧹 Find and remove"), primary = true) {
                dismiss()
                job(t("Ищу паузы", "Finding pauses"), { cancelled, progress ->
                    AudioRender.measure(q, 0, q.length, 20, cancelled, progress)
                }) { m ->
                    val ranges = AudioEdit.pauses(m.db.toFloatArray(), 20, thr.toFloat(), minMs, pad)
                    if (ranges.isEmpty()) return@job toast(t("Пауз не найдено — попробуйте порог «Фон»", "No pauses found — try the \"Background\" level"))
                    val cut = ranges.sumOf { it.last - it.first }
                    sel = null
                    selClip = -1
                    commit(AudioEdit.removeRanges(q, ranges))
                    snack(t("Убрано пауз: ${ranges.size}, короче на ${AudioEdit.time(cut)}", "Removed ${ranges.size} pauses, ${AudioEdit.time(cut)} shorter"), t("Отменить", "Undo")) { undoStep() }
                }
            }
        }
    }

    private fun crossfadeSheet() {
        val q = p ?: return
        bottomSheet(t("🔀 Плавные стыки", "🔀 Crossfades")) { dismiss ->
            muted(t("Куски, стоящие встык, заходят друг на друга: первый затихает, второй нарастает. Всё после стыка сдвигается.",
                "Pieces that touch overlap: the first fades out while the second fades in. Everything after moves left."))
            add(FlowLayout(ctx, dp(6), dp(6)).apply {
                listOf(100L, 300L, 500L, 1000L, 2000L).forEach { ms ->
                    addView(chip(ctx, String.format(java.util.Locale.US, "%.1f", ms / 1000f) + t(" с", " s")) {
                        dismiss()
                        val ids = if (selTrack >= 0) listOf(selTrack) else q.tracks.map { it.id }
                        commit(ids.fold(q) { acc, id -> AudioEdit.crossfade(acc, id, ms) })
                    })
                }
            })
        }
    }

    // ---------- 🔊 volume ----------

    private fun volumeTools(c: Col, q: AProject) {
        c.muted(t("Применяется к: ", "Applies to: ") + targetName())
        grid(c, listOf(
            Triple("🔊", t("Громкость", "Volume")) { volumeSheet() },
            Triple("◢", t("Нарастание", "Fade in")) { fadeRange(true) },
            Triple("◣", t("Затухание", "Fade out")) { fadeRange(false) },
            Triple("📈", t("Нормализовать", "Normalize")) { normalize(null) },
            Triple("📣", t("Громкость для сетей", "Loudness for apps")) { loudSheet() },
            Triple("🔇", t("Заглушить", "Mute")) { val r = needSel(); if (r != null) commit(AudioEdit.gainRange(q, r.first, r.last, 0f, scope())) },
            Triple("🎚", t("Общая громкость", "Master volume")) { masterSheet() },
            Triple("🔀", t("Плавные стыки", "Crossfades")) { crossfadeSheet() },
            Triple("🦆", t("Приглушать под голосом", "Duck under voice")) { duckSheet() }
        ))
    }

    /** A slider in a sheet: label with the value, then the bar. */
    private fun Col.slider(name: String, value: Float, from: Float, to: Float, fmt: (Float) -> String, onMove: (Float) -> Unit): SeekBar {
        val label = text(ctx, "$name: ${fmt(value)}", 13f, bold = true)
        add(label, 12)
        val steps = 200
        val bar = SeekBar(ctx).apply {
            max = steps
            progress = (((value - from) / (to - from)) * steps).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, pr: Int, fromUser: Boolean) {
                    val v = from + (to - from) * pr / steps
                    label.text = "$name: ${fmt(v)}"
                    if (fromUser) onMove(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        add(bar, 2)
        return bar
    }

    /**
     * A sheet that changes the target live while it plays in a loop; "✓ Apply" keeps it as one undo step,
     * closing without it puts everything back.
     */
    private fun tryOut(title: String, body: Col.(set: ((AClip) -> AClip) -> Unit) -> Unit) {
        val base = p ?: return
        if (base.length <= 0) return toast(t("Проект пуст", "The project is empty"))
        val range = targetRange(base)
        var cur: ((AClip) -> AClip)? = null
        var kept = false
        bottomSheet(title, onDismiss = {
            if (!kept) {
                stopPlay()
                p = base
                player.project = base
                wave?.invalidate()
            }
        }) { dismiss ->
            muted(t("Применится к: ", "Applies to: ") + targetName())
            body { f ->
                cur = f
                val n = applyTo(base, f)
                p = n
                player.project = n
                wave?.invalidate()
                if (!player.playing) playRange(range)
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn(ctx, t("▶ Слушать", "▶ Listen"), style = BtnStyle.OUTLINED) { if (player.playing) stopPlay() else playRange(range) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn(ctx, t("✓ Применить", "✓ Apply"), primary = true) {
                kept = true
                stopPlay()
                val f = cur
                p = base
                if (f != null) commit(applyTo(base, f)) else render()
                dismiss()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            add(row, 16)
        }
    }

    private fun volumeSheet() = tryOut(t("🔊 Громкость", "🔊 Volume")) { set ->
        slider(t("Изменить на", "Change by"), 0f, -24f, 12f, { String.format(java.util.Locale.US, "%+.1f dB", it).replace('-', '−') }) { db ->
            val k = EditSound.dbToGain(db)
            set { c -> c.copy(gain = (c.gain * k).coerceIn(0f, AudioEdit.MAX_GAIN)) }
        }
    }

    private fun fadeRange(fadeIn: Boolean) {
        val q = p ?: return
        val r = sel
        val cl = q.clip(selClip)
        when {
            r != null -> commit(if (fadeIn) AudioEdit.fadeInRange(q, r.first, r.last, scope()) else AudioEdit.fadeOutRange(q, r.first, r.last, scope()))
            cl != null -> fadesSheet(cl)
            else -> {
                // the whole project: the first or last 3 s
                val len = min(3000L, q.length / 2)
                if (len < 50) return
                commit(if (fadeIn) AudioEdit.fadeInRange(q, 0, len) else AudioEdit.fadeOutRange(q, q.length - len, q.length))
                toast(if (fadeIn) t("Нарастание на первых ${len / 1000f} с", "Fade in over the first ${len / 1000f} s") else t("Затухание на последних ${len / 1000f} с", "Fade out over the last ${len / 1000f} s"))
            }
        }
    }

    private fun fadesSheet(cl: AClip) {
        val q = p ?: return
        bottomSheet(t("◢◣ Края куска", "◢◣ Edges of the piece")) { dismiss ->
            val opts = listOf(0L, 100L, 500L, 1000L, 2000L, 3000L, 5000L).filter { it <= cl.len }
            fun lbl(ms: Long) = if (ms == 0L) t("нет", "none") else String.format(java.util.Locale.US, "%.1f", ms / 1000f) + t(" с", " s")
            add(text(ctx, t("◢ Нарастание в начале", "◢ Fade in at the start"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
            add(choiceChips(ctx, opts.map { it to lbl(it) }, opts.minByOrNull { abs(it - cl.fadeIn) } ?: 0L) { ms ->
                val n = AudioEdit.updateClip(p ?: q, cl.id) { it.copy(fadeIn = ms) }
                commit(n, keepView = true)
            }, 4)
            add(text(ctx, t("◣ Затухание в конце", "◣ Fade out at the end"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, opts.map { it to lbl(it) }, opts.minByOrNull { abs(it - cl.fadeOut) } ?: 0L) { ms ->
                val n = AudioEdit.updateClip(p ?: q, cl.id) { it.copy(fadeOut = ms) }
                commit(n, keepView = true)
            }, 4)
            button(t("Готово", "Done"), primary = true) { dismiss(); render() }
        }
    }

    /** Brings the target's peak to −1 dB ([lufs] null) or its loudness to [lufs]. */
    private fun normalize(lufs: Float?, push: Boolean = true) {
        val q = p ?: return
        if (q.length <= 0) return
        val r = targetRange(q)
        val clips = targetClips(q).map { it.id }.toSet()
        // measure only the target: a copy of the project where only its clips sound, at their own gain
        val probe = when {
            sel != null -> AudioEdit.mapRange(q, r.first, r.last, scope()) { it }.let { s ->
                val inside = s.tracks.filter { selTrack < 0 || it.id == selTrack }.flatMap { t -> t.clips.filter { it.at >= r.first - 1 && it.end <= r.last + 1 } }.map { it.id }.toSet()
                s.copy(tracks = s.tracks.map { tr -> tr.copy(clips = tr.clips.filter { it.id in inside }) })
            }
            else -> q.copy(tracks = q.tracks.map { tr -> tr.copy(clips = tr.clips.filter { it.id in clips }) })
        }.copy(gain = 1f)
        job(t("Измеряю громкость", "Measuring loudness"), { cancelled, progress -> AudioRender.measure(probe, r.first, r.last, 400, cancelled, progress) }) { m ->
            if (m.peak < 1e-4f) return@job toast(t("Там тишина", "It is silent there"))
            val g = if (lufs == null) AudioEdit.peakGain(m.peak, -1f) else AudioEdit.loudGain(m.gatedDb, m.peak, lufs)
            val cur = p ?: return@job
            val n = applyTo(cur) { c -> c.copy(gain = (c.gain * g).coerceIn(0f, AudioEdit.MAX_GAIN)) }
            if (push) commit(n) else set(n)
            snack((if (lufs == null) t("Пик −1 дБ: ", "Peak −1 dB: ") else t("Громкость ${lufs.roundToInt()} LUFS: ", "Loudness ${lufs.roundToInt()} LUFS: ")) + dbText(g))
        }
    }

    private fun loudSheet() {
        bottomSheet(t("📣 Громкость для приложений", "📣 Loudness for apps")) { dismiss ->
            muted(t("Площадки выравнивают громкость сами: если сделать тише нормы, ролик прозвучит тише соседей. Lumen измерит громкость (как LUFS) и подгонит её, не давая пикам выйти за −1 дБ.",
                "Apps level loudness themselves: below their target your sound plays quieter than others. Lumen measures loudness (like LUFS) and adjusts it, keeping peaks under −1 dB."))
            listOf(
                -14f to t("−14 · YouTube, Spotify, TikTok, Reels", "−14 · YouTube, Spotify, TikTok, Reels"),
                -16f to t("−16 · подкасты, Apple", "−16 · podcasts, Apple"),
                -19f to t("−19 · голосовые, спокойно", "−19 · voice notes, calm"),
                -23f to t("−23 · ТВ, радио (EBU R128)", "−23 · TV, radio (EBU R128)")
            ).forEach { (v, s) -> button(s, style = BtnStyle.TONAL) { dismiss(); normalize(v) } }
        }
    }

    private fun masterSheet() {
        val q = p ?: return
        val base = q.gain
        var v = base
        bottomSheet(t("🎚 Общая громкость", "🎚 Master volume"), onDismiss = {
            if (v != base) liveDone { it.copy(gain = v) } else liveBase = null
        }) { _ ->
            muted(t("Громкость всего проекта на выходе (мягкий ограничитель не даёт хрипеть).", "Output volume of the whole project (a soft limiter keeps it clean)."))
            slider(t("Мастер", "Master"), 20 * kotlin.math.log10(max(base, 0.01f)), -24f, 12f, { String.format(java.util.Locale.US, "%+.1f dB", it).replace('-', '−') }) { db ->
                v = EditSound.dbToGain(db)
                live { it.copy(gain = v) }
                if (!player.playing) play(cursor, null)
            }
        }
    }

    /** Ducking: every other track gets quieter where track [voice] sounds (cuts at voice clips' edges). */
    private fun duckSheet() {
        val q = p ?: return
        if (q.tracks.size < 2) return toast(t("Нужны хотя бы две дорожки: голос и музыка", "Needs at least two tracks: voice and music"))
        var voice = if (selTrack >= 0) selTrack else q.tracks.first().id
        var db = -12f
        bottomSheet(t("🦆 Приглушать под голосом", "🦆 Duck under the voice")) { dismiss ->
            muted(t("Там, где звучит дорожка голоса, остальные дорожки станут тише (с плавным переходом 0.3 с).",
                "Where the voice track sounds, the other tracks get quieter (with 0.3 s ramps)."))
            add(text(ctx, t("Дорожка голоса", "Voice track"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, q.tracks.map { it.id to it.name }, voice) { voice = it }, 4)
            add(choiceChips(ctx, listOf(-6f to "−6 dB", -12f to "−12 dB", -18f to "−18 dB"), db) { db = it }, 12)
            button(t("🦆 Приглушить", "🦆 Duck"), primary = true) {
                dismiss()
                val vt = q.track(voice) ?: return@button
                val k = EditSound.dbToGain(db)
                // spans where the voice sounds, joined over short gaps
                val spans = ArrayList<LongRange>()
                vt.clips.sortedBy { it.at }.forEach { c ->
                    val last = spans.lastOrNull()
                    if (last != null && c.at - last.last < 700) spans[spans.size - 1] = last.first..max(last.last, c.end) else spans += c.at..c.end
                }
                val others = q.tracks.map { it.id }.filter { it != voice }.toSet()
                var n = q
                for (s in spans) {
                    val ramp = 300L
                    val a = max(0L, s.first - ramp)
                    val b = s.last + ramp
                    n = AudioEdit.gainRange(n, s.first, s.last, k, others)
                    n = AudioEdit.mapRange(n, a, s.first, others) { c ->
                        val r0 = (c.at - a).toFloat() / ramp
                        val r1 = (c.end - a).toFloat() / ramp
                        c.copy(g0 = c.g0 * (1 + (k - 1) * r0.coerceIn(0f, 1f)), g1 = c.g1 * (1 + (k - 1) * r1.coerceIn(0f, 1f)))
                    }
                    n = AudioEdit.mapRange(n, s.last, b, others) { c ->
                        val r0 = (c.at - s.last).toFloat() / ramp
                        val r1 = (c.end - s.last).toFloat() / ramp
                        c.copy(g0 = c.g0 * (k + (1 - k) * r0.coerceIn(0f, 1f)), g1 = c.g1 * (k + (1 - k) * r1.coerceIn(0f, 1f)))
                    }
                }
                commit(n)
                snack(t("Приглушено мест: ${spans.size}", "Ducked ${spans.size} places"), t("Отменить", "Undo")) { undoStep() }
            }
        }
    }

    // ---------- ✨ effects ----------

    private fun effectTools(c: Col, q: AProject) {
        c.muted(t("Звук-эффект — отдельная дорожка «✨» под волной, как в видеоредакторе: он действует на всё, что звучит в это время. Тяните её за края, чтобы растянуть или сжать, или за середину — чтобы сдвинуть.",
            "A sound effect is a track of its own \"✨\" under the wave, as in the video editor: it changes everything heard while it lasts. Drag its edges to stretch or shrink it, its middle to move it."))
        grid(c, listOf(
            Triple("🎚", t("Звук-эффект", "Sound FX")) { addLayer("") },
            Triple("☰", t("Все эффекты (${q.fx.size})", "All effects (${q.fx.size})")) { layersSheet() },
            Triple("🪄", t("Улучшить голос", "Enhance voice")) { enhanceVoice() }
        ))
        c.add(text(this, t("Быстро: эффект на ", "Quick: an effect on ") + layerSpanName(q), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        var group = Lumen.prefs.getString("audio_fx_group", EditSound.GROUPS[0].id) ?: EditSound.GROUPS[0].id
        c.add(chipRow(this, EditSound.GROUPS.map { gr ->
            chip(this, gr.name, gr.id == group) { Lumen.prefs.edit().putString("audio_fx_group", gr.id).apply(); render() }
        }, collapseAfter = 20), 6)
        c.add(chipRow(this, EditSound.of(group).map { f -> chip(this, "${f.emoji} ${f.name}") { addLayer(f.id) } }, collapseAfter = 20), 8)
        c.add(text(this, t("Обработка: ", "Processing: ") + targetName(), Type.LABEL, bold = true, color = Palette.MUTED), 20)
        grid(c, listOf(
            Triple("🧹", t("Убрать шум", "Remove noise")) { denoiseSheet() },
            Triple("🎛", t("Эквалайзер", "Equalizer")) { eqSheet() },
            Triple("⏩", t("Скорость", "Speed")) { speedSheet() },
            Triple("🎵", t("Высота тона", "Pitch")) { pitchSheet() },
            Triple("↺", t("Задом наперёд", "Reverse")) { commit(applyTo(q) { it.copy(reverse = !it.reverse) }) },
            Triple("✕", t("Сбросить обработку", "Reset processing")) { commit(applyTo(q) { it.copy(fx = "", lo = 0f, mid = 0f, hi = 0f, pitch = 0f) }) }
        ))
    }

    /** Where a new effect layer goes: the selection, else the chosen piece, else 3 s from the cursor. */
    private fun layerSpan(q: AProject): Pair<Long, Long> {
        sel?.let { return it.first to (it.last - it.first) }
        q.clip(selClip)?.let { return it.at to it.len }
        val len = min(AudioEdit.FX_DEFAULT_MS, max(AudioEdit.FX_MIN_MS, q.length))
        return cursor.coerceIn(0L, max(0L, q.length - len)) to len
    }

    private fun layerSpanName(q: AProject): String = when {
        sel != null -> t("выделенное", "the selection")
        q.clip(selClip) != null -> t("кусок", "the piece")
        else -> t("3 с от курсора", "3 s from the cursor")
    }

    private fun addLayer(fx: String) {
        val q = p ?: return
        if (q.length <= 0) return toast(t("Сначала добавьте звук", "Add some sound first"))
        val (at, len) = layerSpan(q)
        val (n, id) = AudioEdit.addFx(q, fx, at, len)
        live { n }
        layerSheet(id, q)
    }

    /**
     * Effect layer [id], like the editor's effect sheet: groups and the 50 effects, strength, the layer's time;
     * heard at once in a loop; one undo step on closing ([before] — the state before a new layer was added).
     */
    private fun layerSheet(id: Int, before: AProject? = null) {
        val base = before ?: p ?: return
        if (before == null) liveBase = base
        val f0 = AudioEdit.fxOf(p ?: return, id) ?: return
        var cur = f0
        fun show(n: AFx) {
            live { q -> AudioEdit.updateFx(q, id) { n } }
            cur = AudioEdit.fxOf(p ?: return, id) ?: n
            wave?.invalidate()
        }
        fun playIt() = playRange(cur.at..cur.end)
        bottomSheet(t("🎚 Звук-эффект", "🎚 Sound effect"), onDismiss = {
            stopPlay()
            val live = p
            val now = live?.let { AudioEdit.fxOf(it, id) }
            val empty = now == null || AudioEdit.fxEmpty(now)
            val b = liveBase ?: base
            liveBase = null
            if (live != null) {
                p = b
                // an empty layer goes away; nothing changed — no undo step
                if ((before == null && now == f0) || (before != null && empty)) set(if (empty) AudioEdit.removeFx(b, id) else b)
                else commit(if (empty) AudioEdit.removeFx(live, id) else live)
            }
        }) { dismiss ->
            val span = text(ctx, "", 13f, color = Palette.MUTED)
            fun showSpan() { span.text = t("Дорожка эффекта: ", "Effect track: ") + AudioEdit.time(cur.at) + " – " + AudioEdit.time(cur.end) + " (" + trim(cur.len / 1000f) + t(" с)", " s)") }
            showSpan()
            add(span, 0)
            var group = EditSound.find(cur.fx)?.group ?: Lumen.prefs.getString("audio_fx_group", EditSound.GROUPS[0].id) ?: EditSound.GROUPS[0].id
            val now = text(ctx, "", 14f)
            val groups = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun showNow() {
                now.text = EditSound.find(cur.fx)?.let { "${it.emoji} ${it.name} — ${it.hint}" } ?: t("Выберите эффект", "Pick an effect")
            }
            lateinit var fill: () -> Unit
            fill = {
                groups.removeAllViews()
                groups.addView(chipRow(ctx, EditSound.GROUPS.map { gr ->
                    chip(ctx, gr.name + (if (EditSound.find(cur.fx)?.group == gr.id) " •" else ""), gr.id == group) { group = gr.id; fill() }
                }, collapseAfter = 20))
                list.removeAllViews()
                list.addView(chipRow(ctx, EditSound.of(group).map { f ->
                    chip(ctx, "${f.emoji} ${f.name}", f.id == cur.fx) {
                        show(cur.copy(fx = f.id))
                        showNow()
                        fill()
                        playIt()
                    }
                }, collapseAfter = 20))
            }
            add(groups, 8)
            add(list, 8)
            fill()
            showNow()
            add(now, 8)
            slider(t("Сила", "Strength"), cur.k, 0f, 1f, { "${(it * 100).roundToInt()} %" }) { v -> show(cur.copy(k = v)) }
            add(text(ctx, t("Длина дорожки", "Track length"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val q0 = p ?: return@bottomSheet
            add(FlowLayout(ctx, dp(6), dp(6)).apply {
                addView(chip(ctx, t("↔ На весь проект", "↔ Whole project")) { show(cur.copy(at = 0, len = (p ?: q0).length)); showSpan() })
                sel?.let { r -> addView(chip(ctx, t("⟦⟧ По выделению", "⟦⟧ To the selection")) { show(cur.copy(at = r.first, len = r.last - r.first)); showSpan() }) }
                q0.clip(selClip)?.let { c -> addView(chip(ctx, t("▭ По куску", "▭ To the piece")) { show(cur.copy(at = c.at, len = c.len)); showSpan() }) }
                addView(chip(ctx, "⇤ " + t("с курсора", "from cursor")) { show(cur.copy(at = cursor, len = max(AudioEdit.FX_MIN_MS, cur.end - cursor))); showSpan() })
                addView(chip(ctx, "−1 " + t("с", "s")) { show(cur.copy(len = cur.len - 1000)); showSpan() })
                addView(chip(ctx, "＋1 " + t("с", "s")) { show(cur.copy(len = cur.len + 1000)); showSpan() })
            }, 6)
            button(t("▶ Послушать с эффектом", "▶ Listen with the effect"), style = BtnStyle.TONAL) { if (player.playing) stopPlay() else playIt() }
            row(btn(ctx, t("🗑 Убрать", "🗑 Remove"), style = BtnStyle.TEXT) { show(cur.copy(k = 0f)); dismiss() },
                btn(ctx, t("Готово", "Done"), primary = true) { dismiss() })
        }
    }

    private fun layersSheet() {
        val q = p ?: return
        bottomSheet(t("☰ Звук-эффекты", "☰ Sound effects")) { dismiss ->
            if (q.fx.isEmpty()) muted(t("Эффектов пока нет: «🎚 Звук-эффект» поставит первый.", "No effects yet: \"🎚 Sound FX\" adds the first one."))
            q.fx.sortedBy { it.at }.forEach { f ->
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(text(ctx, EditSound.label(f.fx).ifEmpty { "?" } + " · " + AudioEdit.time(f.at) + "–" + AudioEdit.time(f.end) + " · " + (f.k * 100).roundToInt() + "%", 14f).apply {
                    setOnClickListener { dismiss(); layerSheet(f.id) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(iconBtn(ctx, "▶") { playRange(f.at..f.end) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                r.addView(iconBtn(ctx, "🗑") { dismiss(); commit(AudioEdit.removeFx(p ?: q, f.id)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                add(r, 4)
            }
            button(t("＋ Звук-эффект", "＋ Sound FX"), style = BtnStyle.TONAL) { dismiss(); addLayer("") }
        }
    }

    private fun eqSheet() {
        var lo = 0f
        var mid = 0f
        var hi = 0f
        val q = p
        targetClips(q ?: return).firstOrNull()?.let { lo = it.lo; mid = it.mid; hi = it.hi }
        tryOut(t("🎛 Эквалайзер", "🎛 Equalizer")) { set ->
            fun go() = set { it.copy(lo = lo, mid = mid, hi = hi) }
            val bars = ArrayList<SeekBar>()
            fun fmt(v: Float) = String.format(java.util.Locale.US, "%+.1f dB", v).replace('-', '−')
            fun preset(a: Float, b: Float, c: Float) {
                lo = a; mid = b; hi = c
                listOf(a, b, c).forEachIndexed { i, v -> bars[i].progress = ((v + 12f) / 24f * 200).roundToInt() }
                go()
            }
            add(FlowLayout(ctx, dp(6), dp(6)).apply {
                addView(chip(ctx, t("🗣 Голос", "🗣 Voice")) { preset(-3f, 3f, 2f) })
                addView(chip(ctx, t("🔈 Бас", "🔈 Bass")) { preset(6f, 0f, 0f) })
                addView(chip(ctx, t("✨ Ярче", "✨ Brighter")) { preset(0f, 0f, 5f) })
                addView(chip(ctx, t("🌙 Мягче", "🌙 Softer")) { preset(1f, -2f, -5f) })
                addView(chip(ctx, t("📻 Середина", "📻 Mids")) { preset(-6f, 5f, -4f) })
                addView(chip(ctx, t("0 Ровно", "0 Flat")) { preset(0f, 0f, 0f) })
            }, 8)
            bars += slider(t("Низы 150 Гц", "Lows 150 Hz"), lo, -12f, 12f, ::fmt) { lo = it; go() }
            bars += slider(t("Середина 1.2 кГц", "Mids 1.2 kHz"), mid, -12f, 12f, ::fmt) { mid = it; go() }
            bars += slider(t("Верха 6 кГц", "Highs 6 kHz"), hi, -12f, 12f, ::fmt) { hi = it; go() }
        }
    }

    private fun speedSheet() {
        var keep = Lumen.prefs.getBoolean("audio_keep_pitch", true)
        var sp = 1f
        tryOut(t("⏩ Скорость", "⏩ Speed")) { set ->
            fun go() = set { AudioEdit.setSpeed(it, sp).copy(keepPitch = keep) }
            add(choiceChips(ctx, listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f, 3f).map { it to "×" + trim(it) }, 1f) { sp = it; go() }, 8)
            check(t("Сохранять высоту голоса (без «бурундука»)", "Keep the pitch (no chipmunk)"), keep) {
                keep = it
                Lumen.prefs.edit().putBoolean("audio_keep_pitch", it).apply()
                go()
            }
            muted(t("Длина куска меняется, всё после него сдвигается.", "The piece gets longer or shorter, everything after it moves."))
        }
    }

    private fun pitchSheet() = tryOut(t("🎵 Высота тона", "🎵 Pitch")) { set ->
        add(FlowLayout(ctx, dp(6), dp(6)).apply {
            listOf(-12f, -7f, -5f, -3f, 0f, 3f, 5f, 7f, 12f).forEach { v ->
                addView(chip(ctx, (if (v > 0) "+" else "") + trim(v)) { set { it.copy(pitch = v) } })
            }
        }, 8)
        slider(t("Полутона", "Semitones"), 0f, -12f, 12f, { (if (it > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", it) }) { v -> set { it.copy(pitch = v) } }
        muted(t("Скорость не меняется. ±12 — октава.", "The speed stays. ±12 is an octave."))
    }

    private fun denoiseSheet() {
        val q = p ?: return
        val srcs = targetClips(q).map { it.src }.distinct()
        if (srcs.isEmpty()) return toast(t("Нет звука", "No sound"))
        bottomSheet(t("🧹 Убрать шум", "🧹 Remove noise")) { dismiss ->
            muted(t("Lumen изучит самые тихие места файла (фон, гул, шипение) и вычтет этот шум из всего файла. Обрабатывается файл целиком — все куски из него (файлов: ${srcs.size}).",
                "Lumen learns the quietest places of the file (background, hum, hiss) and removes that noise from the whole file — all pieces made of it (files: ${srcs.size})."))
            listOf(0.4f to t("Слабо — осторожно", "Light — careful"), 0.65f to t("Средне", "Medium"), 0.9f to t("Сильно — много шума", "Strong — a lot of noise")).forEach { (k, s) ->
                button("🧹 $s", style = BtnStyle.TONAL) { dismiss(); denoise(srcs, k, then = null) }
            }
        }
    }

    private fun denoise(srcs: List<String>, k: Float, then: (() -> Unit)?) {
        val q = p ?: return
        job(t("Убираю шум", "Removing noise"), { cancelled, progress ->
            srcs.mapIndexed { i, sid ->
                sid to AudioRender.denoise(q, sid, k, cancelled) { pct -> progress((i * 100 + pct) / srcs.size) }
            }
        }) { done ->
            var n = p ?: return@job
            for ((old, src) in done) n = AudioEdit.swapSource(n, old, src)
            commit(n)
            if (then != null) then() else snack(t("Шум убран", "Noise removed"), t("Отменить", "Undo")) { undoStep() }
        }
    }

    /** One tap for speech: noise removal, the "clear voice" chain, then −16 LUFS. */
    private fun enhanceVoice() {
        val q = p ?: return
        val srcs = targetClips(q).map { it.src }.distinct()
        if (srcs.isEmpty()) return toast(t("Нет звука", "No sound"))
        confirm(t("🪄 Улучшить голос", "🪄 Enhance voice"),
            t("Убрать шум (средне), выровнять тембр и динамику («🎙 Чистый голос»), привести громкость к −16 LUFS. Всё можно отменить ↶.",
                "Remove noise (medium), even out tone and dynamics (\"🎙 Clear voice\"), bring loudness to −16 LUFS. Everything can be undone ↶."), t("Улучшить", "Enhance")) {
            // one undo step for all three: the noise removal pushes it, the rest only changes the state
            denoise(srcs, 0.6f) {
                val cur = p ?: return@denoise
                set(applyTo(cur) { it.copy(fx = "clear", fxK = 0.9f) }, keepView = true)
                normalize(-16f, push = false)
            }
        }
    }

    // ---------- 🎵 sounds ----------

    private fun soundTools(c: Col, q: AProject) {
        val tab = Lumen.prefs.getInt("audio_snd_tab", 0)
        c.add(segBar(this, listOf(t("🌿 Атмосфера (${AudioAmb.SOUNDS.size})", "🌿 Ambience (${AudioAmb.SOUNDS.size})"), t("🔔 Короткие звуки", "🔔 Short sounds")), tab) {
            Lumen.prefs.edit().putInt("audio_snd_tab", it).apply()
            render()
        }, 0)
        if (tab == 0) return ambTools(c, q)
        c.muted(t("Тап — послушать. Звук ляжет в курсор на свободную дорожку и смешается с остальным.", "Tap — listen. The sound goes to the cursor on a free track and mixes in."))
        AudioGen.GROUPS.forEach { g ->
            c.add(text(this, g.name, Type.LABEL, bold = true, color = Palette.MUTED), 12)
            c.add(FlowLayout(this, dp(6), dp(6)).apply {
                AudioGen.SOUNDS.filter { it.group == g.id }.forEach { s ->
                    addView(chip(this@AudioEditorActivity, s.emoji + " " + s.name, pendingSound == s.id) {
                        pendingSound = s.id
                        AudioPreview.play(AudioImport.sound(s.id, q.rate), q.rate)
                        render()
                    })
                }
            }, 4)
        }
        val s = AudioGen.find(pendingSound)
        if (s != null) c.button(t("＋ Добавить «${s.name}» в ", "＋ Add \"${s.name}\" at ") + AudioEdit.time(cursor), primary = true) { addSound(s.id, s.emoji + " " + s.name) }
        c.add(text(this, t("Генераторы", "Generators"), Type.TITLE_M, bold = true), 20)
        grid(c, listOf(
            Triple("〰", t("Тон", "Tone")) { toneSheet() },
            Triple("🌫", t("Шум", "Noise")) { noiseSheet() },
            Triple("🤐", t("Запикать выделенное", "Bleep the selection")) { bleep() }
        ))
    }

    private var pendingAmb = ""

    /** 100 ambience sounds: a group, a sound (tap — listen 4 s), how long, then onto a free track at the cursor. */
    private fun ambTools(c: Col, q: AProject) {
        c.muted(t("Фоны и события: океан, дождь, гроза, туман, лес, костёр, город, кафе, космос… Тап — послушать 4 с. Звук ляжет поверх, на свободную дорожку, и смешается с остальным; громкость — в «🎚 Дорожки».",
            "Beds and events: ocean, rain, thunderstorm, fog, forest, campfire, city, café, space… Tap — listen for 4 s. The sound goes over, on a free track, mixed with the rest; its volume is in \"🎚 Tracks\"."))
        val group = Lumen.prefs.getString("audio_amb_group", AudioAmb.GROUPS[0].id) ?: AudioAmb.GROUPS[0].id
        c.add(chipRow(this, AudioAmb.GROUPS.map { g ->
            chip(this, g.name, g.id == group) { Lumen.prefs.edit().putString("audio_amb_group", g.id).apply(); render() }
        }, collapseAfter = 20), 10)
        c.add(FlowLayout(this, dp(6), dp(6)).apply {
            AudioAmb.of(group).forEach { s ->
                addView(chip(this@AudioEditorActivity, s.emoji + " " + s.name + if (s.oneShotMs > 0) " ·" else "", pendingAmb == s.id) {
                    pendingAmb = s.id
                    previewAmb(s.id, q.rate)
                    render()
                })
            }
        }, 10)
        c.add(text(this, t("· — одиночное событие своей длины; остальные — фон любой длины.", "· — a single event of its own length; the rest are beds of any length."), 11f, color = Palette.MUTED), 4)
        val s = AudioAmb.find(pendingAmb) ?: return
        val at = sel?.first ?: cursor
        if (s.oneShotMs <= 0) {
            val opts = ArrayList<Pair<Long, String>>()
            listOf(5_000L, 10_000L, 30_000L, 60_000L, 180_000L, 600_000L).forEach { opts += it to (if (it < 60_000) "${it / 1000} " + t("с", "s") else "${it / 60_000} " + t("мин", "min")) }
            sel?.let { r -> opts += -1L to t("⟦⟧ По выделению", "⟦⟧ The selection") + " " + AudioEdit.time(r.last - r.first) }
            if (q.length - at > 1000) opts += -2L to t("⇥ До конца", "⇥ To the end") + " " + AudioEdit.time(q.length - at)
            val cur = Lumen.prefs.getLong("audio_amb_len", 30_000L).let { v -> if (opts.any { it.first == v }) v else 30_000L }
            c.add(text(this, t("Длина", "Length"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            c.add(choiceChips(this, opts, cur) { Lumen.prefs.edit().putLong("audio_amb_len", it).apply() }, 4)
        }
        c.button(t("＋ Добавить «${s.name}» в ", "＋ Add \"${s.name}\" at ") + AudioEdit.time(at), primary = true) { addAmb(s) }
    }

    private fun previewAmb(id: String, rate: Int) {
        AudioPreview.stop()
        Thread {
            runCatching { AudioAmb.render(id, rate, 4000, seed = System.nanoTime() % 1000) }.getOrNull()?.let { AudioPreview.playStereo(it, rate) }
        }.start()
    }

    private fun addAmb(s: AudioAmb.Sound) {
        val q = p ?: return
        val at = sel?.first ?: cursor
        val pick = Lumen.prefs.getLong("audio_amb_len", 30_000L)
        val ms = when {
            s.oneShotMs > 0 -> s.oneShotMs
            pick == -1L -> sel?.let { it.last - it.first } ?: 30_000L
            pick == -2L -> (q.length - at).takeIf { it > 1000 } ?: 30_000L
            else -> pick
        }.coerceIn(1000L, 600_000L)
        val name = s.emoji + " " + s.name
        AudioPreview.stop()
        job(t("Создаю «${s.name}»", "Making \"${s.name}\""), { cancelled, progress -> AudioImport.writeAmbient(q, s.id, name, ms, cancelled, progress) }) { src ->
            val cur = p ?: return@job
            commit(AudioEdit.overlay(cur, src, at, t("Атмосфера", "Ambience"), name))
            snack(t("Добавлено: ${s.name}, ${AudioEdit.time(src.ms)}", "Added: ${s.name}, ${AudioEdit.time(src.ms)}"))
        }
    }

    private fun placeGenerated(name: String, mono: FloatArray, insert: Boolean = false) {
        val q = p ?: return
        job(t("Добавляю звук", "Adding the sound"), { _, _ -> AudioImport.write(q, name, AudioGen.stereo(mono)) }) { src ->
            val cur = p ?: return@job
            val n = if (insert) AudioEdit.insert(cur, if (selTrack >= 0) selTrack else cur.tracks.first().id, src, cursor, name)
            else AudioEdit.overlay(cur, src, cursor, t("Звуки", "Sounds"), name)
            commit(n)
        }
    }

    private fun addSound(id: String, name: String) {
        val q = p ?: return
        placeGenerated(name, AudioImport.sound(id, q.rate))
    }

    private fun toneSheet() {
        val q = p ?: return
        var hz = 440.0
        var wave = "sine"
        var ms = 1000L
        bottomSheet(t("〰 Тон", "〰 Tone")) { dismiss ->
            add(choiceChips(ctx, listOf(60.0 to "60 Hz", 220.0 to "220", 440.0 to "440 (A)", 1000.0 to "1 kHz", 2000.0 to "2 kHz", 8000.0 to "8 kHz"), hz) { hz = it }, 8)
            add(choiceChips(ctx, listOf("sine" to t("Синус", "Sine"), "square" to t("Квадрат", "Square"), "saw" to t("Пила", "Saw"), "triangle" to t("Треугольник", "Triangle")), wave) { wave = it }, 8)
            add(choiceChips(ctx, listOf(250L to "0.25 s", 1000L to "1 s", 3000L to "3 s", 10_000L to "10 s"), ms) { ms = it }, 8)
            row(btn(ctx, t("▶ Слушать", "▶ Listen"), style = BtnStyle.OUTLINED) { AudioPreview.play(AudioGen.tone(q.rate, min(ms, 3000L), hz, wave), q.rate) },
                btn(ctx, t("＋ В курсор", "＋ At cursor"), primary = true) {
                    dismiss()
                    placeGenerated(t("Тон ", "Tone ") + hz.roundToInt() + " Hz", AudioGen.tone(q.rate, ms, hz, wave))
                })
        }
    }

    private fun noiseSheet() {
        val q = p ?: return
        var color = "pink"
        var ms = 5000L
        bottomSheet(t("🌫 Шум", "🌫 Noise")) { dismiss ->
            add(choiceChips(ctx, listOf("white" to t("Белый", "White"), "pink" to t("Розовый", "Pink"), "brown" to t("Коричневый (мягкий)", "Brown (soft)")), color) { color = it }, 8)
            add(choiceChips(ctx, listOf(1000L to "1 s", 5000L to "5 s", 30_000L to "30 s", 60_000L to "1 min"), ms) { ms = it }, 8)
            row(btn(ctx, t("▶ Слушать", "▶ Listen"), style = BtnStyle.OUTLINED) { AudioPreview.play(AudioGen.noise(q.rate, 2000, color), q.rate) },
                btn(ctx, t("＋ В курсор", "＋ At cursor"), primary = true) {
                    dismiss()
                    placeGenerated(t("Шум", "Noise") + " ($color)", AudioGen.noise(q.rate, ms, color))
                })
        }
    }

    /** Censoring: the selection goes silent and a 1 kHz beep of the same length plays over it. */
    private fun bleep() {
        val q = p ?: return
        val r = needSel() ?: return
        val len = r.last - r.first
        job(t("Запикиваю", "Bleeping"), { _, _ -> AudioImport.write(q, t("Пик", "Bleep"), AudioGen.stereo(AudioGen.tone(q.rate, len, 1000.0, level = 0.35f))) }) { src ->
            val cur = p ?: return@job
            val muted = AudioEdit.gainRange(cur, r.first, r.last, 0f, scope())
            commit(AudioEdit.overlay(muted, src, r.first, t("Звуки", "Sounds"), "🤐 " + t("Пик", "Bleep")))
        }
    }

    // ---------- 🎚 tracks ----------

    private fun trackTools(c: Col, q: AProject) {
        grid(c, listOf(
            Triple("📂", t("Добавить аудиофайл", "Add an audio file")) { pick("audio/*") },
            Triple("🎞", t("Звук из видео", "Sound of a video")) { pick("video/*") },
            Triple("🎙", t("Записать с микрофона", "Record from the mic")) { recordSheet() },
            Triple("＋", t("Пустая дорожка", "Empty track")) {
                if (q.tracks.size >= AudioEdit.MAX_TRACKS) toast(t("Не больше ${AudioEdit.MAX_TRACKS} дорожек", "No more than ${AudioEdit.MAX_TRACKS} tracks"))
                else commit(AudioEdit.addTrack(q, t("Дорожка ${q.tracks.size + 1}", "Track ${q.tracks.size + 1}")))
            }
        ))
        c.add(text(this, t("Куда класть новый звук", "Where new sound goes"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        c.add(choiceChips(this, listOf(
            "append" to t("⇥ В конец — склеить", "⇥ At the end — join"),
            "insert" to t("⤵ В курсор — вставить", "⤵ At the cursor — insert"),
            "overlay" to t("≡ Поверх — смешать", "≡ Over — mix")
        ), importMode) { importMode = it }, 4)
        c.muted(t("Можно выбрать сразу несколько файлов — они встанут друг за другом.", "Pick several files at once — they line up one after another."))
        c.add(text(this, t("Дорожки", "Tracks"), Type.TITLE_M, bold = true), 20)
        q.tracks.forEachIndexed { i, tr ->
            val box = Col(this, 12)
            box.root.background = roundBg(Palette.CARD, 16f, this)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(this).apply { background = roundBg(TRACK_COLORS[i % TRACK_COLORS.size], 6f, this@AudioEditorActivity) }, LinearLayout.LayoutParams(dp(12), dp(12)))
            head.addView(text(this, tr.name + " · " + t("кусков ${tr.clips.size}", "${tr.clips.size} pieces"), 14f, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { prompt(t("Имя дорожки", "Track name"), "", tr.name) { s -> if (s.isNotBlank()) commit(AudioEdit.updateTrack(q, tr.id) { it.copy(name = s.trim()) }) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            head.addView(chip(this, "🔇", tr.mute) { commit(AudioEdit.updateTrack(q, tr.id) { it.copy(mute = !it.mute) }) }.apply { contentDescription = t("Без звука", "Mute") })
            head.addView(chip(this, "🎧", tr.solo) { commit(AudioEdit.updateTrack(q, tr.id) { it.copy(solo = !it.solo) }) }.apply { contentDescription = t("Соло", "Solo") },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            head.addView(iconBtn(this, "🗑") {
                confirm(t("Удалить дорожку?", "Delete the track?"), tr.name, t("Удалить", "Delete"), danger = true) { commit(AudioEdit.removeTrack(q, tr.id)) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            box.add(head, 0)
            var vol = tr.gain
            var pan = tr.pan
            box.slider(t("Громкость", "Volume"), 20 * kotlin.math.log10(max(tr.gain, 0.01f)), -30f, 12f, { String.format(java.util.Locale.US, "%+.1f dB", it).replace('-', '−') }) { db ->
                vol = EditSound.dbToGain(db)
                live { it.copy(tracks = it.tracks.map { x -> if (x.id == tr.id) x.copy(gain = vol) else x }) }
            }.setOnTouchListener(saveOnRelease { liveDone { AudioEdit.updateTrack(it, tr.id) { x -> x.copy(gain = vol) } } })
            box.slider(t("Панорама", "Pan"), tr.pan, -1f, 1f, { v ->
                when {
                    abs(v) < 0.03f -> t("центр", "centre")
                    v < 0 -> t("левее ", "left ") + (abs(v) * 100).roundToInt() + "%"
                    else -> t("правее ", "right ") + (v * 100).roundToInt() + "%"
                }
            }) { v ->
                pan = if (abs(v) < 0.03f) 0f else v
                live { it.copy(tracks = it.tracks.map { x -> if (x.id == tr.id) x.copy(pan = pan) else x }) }
            }.setOnTouchListener(saveOnRelease { liveDone { AudioEdit.updateTrack(it, tr.id) { x -> x.copy(pan = pan) } } })
            c.add(box.root, 10)
        }
    }

    private var liveBase: AProject? = null

    /** A change heard at once without an undo step (sliders while moving); [liveDone] keeps it as one step. */
    private fun live(f: (AProject) -> AProject) {
        val cur = p ?: return
        if (liveBase == null) liveBase = cur
        val n = f(cur)
        p = n
        player.project = n
        wave?.invalidate()
    }

    private fun liveDone(f: (AProject) -> AProject) {
        val b = liveBase ?: p ?: return
        liveBase = null
        p = b
        commit(f(b), keepView = true)
    }

    private fun saveOnRelease(act: () -> Unit) = View.OnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            // let the bar take the last move first
            wave?.post { act() }
        }
        false
    }

    private fun pick(mime: String) {
        importTarget = if (selTrack >= 0) selTrack else (p?.tracks?.firstOrNull()?.id ?: 0)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (mime == "audio/*") i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg"))
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        }.onFailure { toast(t("Нет приложения для выбора файлов", "No app to pick files")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                if (uris.isEmpty()) return
                if (p == null) {
                    val first = AudioImport.name(this, uris[0]).substringBeforeLast('.')
                    val q = AudioStore.create(first, t("Дорожка 1", "Track 1"))
                    openProject(q)
                    importMode = "append"
                    importTarget = q.tracks.first().id
                }
                importUris(uris)
            }
            REQ_SAVE_AS -> {
                val f = lastExport ?: return
                val uri = data.data ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    toast(t("Сохранено", "Saved"))
                }.onFailure { err(it) }
            }
        }
    }

    /** Decodes [uris] in order and puts them where [importMode] says (several files line up one after another). */
    private fun importUris(uris: List<Uri>) {
        val q0 = p ?: return
        val mode = importMode
        val target = q0.track(importTarget)?.id ?: q0.tracks.first().id
        val at0 = cursor
        job(t("Открываю файлы", "Opening files"), { cancelled, progress ->
            var q = q0
            var at = at0
            var cut = false
            uris.forEachIndexed { i, u ->
                val r = AudioImport.decode(this, q, u, cancelled) { pct -> progress((i * 100 + pct) / uris.size) }
                if (q.sources.isEmpty() && q.clips.isEmpty()) q = q.copy(rate = r.rate)
                cut = cut || r.cut
                val name = r.src.name.substringBeforeLast('.')
                q = when (mode) {
                    "insert" -> AudioEdit.insert(q, target, r.src, at, name)
                    "overlay" -> AudioEdit.overlay(q, r.src, at, t("Дорожка ${q.tracks.size + 1}", "Track ${q.tracks.size + 1}"), name)
                    else -> AudioEdit.append(q, target, r.src, name)
                }
                at += r.src.ms
            }
            q to cut
        }) { (n, cut) ->
            val first = p?.length == 0L
            commit(n)
            if (first) wave?.fit()
            if (cut) toast(t("Длинный файл обрезан до 30 минут", "A long file was cut to 30 minutes"))
        }
    }

    private fun moveToTrack(cl: AClip) {
        val q = p ?: return
        val cur = q.trackOf(cl.id)?.id
        bottomSheet(t("↕ На дорожку", "↕ To track")) { dismiss ->
            q.tracks.forEach { tr ->
                button((if (tr.id == cur) "● " else "") + tr.name, style = BtnStyle.TONAL) { dismiss(); commit(AudioEdit.moveClip(q, cl.id, cl.at, tr.id)) }
            }
            if (q.tracks.size < AudioEdit.MAX_TRACKS) button(t("＋ Новая дорожка", "＋ New track"), style = BtnStyle.TEXT) {
                dismiss()
                val n = AudioEdit.addTrack(q, t("Дорожка ${q.tracks.size + 1}", "Track ${q.tracks.size + 1}"))
                commit(AudioEdit.moveClip(n, cl.id, cl.at, n.tracks.last().id))
            }
        }
    }

    // ---------- 🎙 recording ----------

    private fun recordSheet() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val q = p ?: return
        stopPlay()
        var withPlay = Lumen.prefs.getBoolean("audio_rec_play", true) && q.length > 0
        bottomSheet(t("🎙 Запись", "🎙 Recording"), onDismiss = {
            recorder?.let { r -> runCatching { r.stop() } }
            recorder = null
            stopPlay()
        }) { dismiss ->
            val mode = if (importMode == "append") t("в конец дорожки", "at the end of the track") else t("в курсор ", "at the cursor ") + AudioEdit.time(cursor) +
                if (importMode == "overlay") t(" (поверх)", " (over)") else t(" (со сдвигом)", " (pushing the rest)")
            muted(t("Запись ляжет ", "The take goes ") + mode + t(". Меняется в «Куда класть новый звук».", ". Change it in \"Where new sound goes\"."))
            if (q.length > 0) check(t("Играть проект во время записи (в наушниках)", "Play the project while recording (headphones)"), withPlay) {
                withPlay = it
                Lumen.prefs.edit().putBoolean("audio_rec_play", it).apply()
            }
            val time = text(ctx, "0:00.0", 32f, bold = true).apply { gravity = Gravity.CENTER }
            add(time, 16)
            val meter = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            add(meter, 8)
            lateinit var go: TextView
            go = btn(ctx, t("● Начать запись", "● Start recording"), primary = true) {
                val r = recorder
                if (r == null) {
                    val rec = StudioRecorder { lvl, ms ->
                        meter.progress = (sqrt(lvl.coerceIn(0f, 1f)) * 100).roundToInt()
                        time.text = AudioEdit.time(ms)
                    }
                    try {
                        rec.start()
                    } catch (e: Exception) {
                        return@btn err(e)
                    }
                    recorder = rec
                    if (withPlay) play(if (importMode == "append") 0 else cursor, null)
                    go.text = t("■ Стоп и добавить", "■ Stop and add")
                } else {
                    recorder = null
                    stopPlay()
                    val pcm = r.stop()
                    dismiss()
                    if (pcm.size < r.rate / 5) return@btn toast(t("Слишком короткая запись", "The take is too short"))
                    addRecording(pcm, r.rate)
                }
            }
            add(go, 16)
        }
    }

    private fun addRecording(mono: ShortArray, rate: Int) {
        val q = p ?: return
        val name = t("Запись ", "Take ") + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        job(t("Сохраняю запись", "Saving the take"), { _, _ -> AudioImport.write(q, name, AudioGen.resampleToStereo(mono, rate, q.rate)) }) { src ->
            val cur = p ?: return@job
            val target = cur.track(if (selTrack >= 0) selTrack else -1)?.id ?: cur.tracks.first().id
            commit(when (importMode) {
                "insert" -> AudioEdit.insert(cur, target, src, cursor, name)
                "overlay" -> AudioEdit.overlay(cur, src, cursor, t("Голос", "Voice"), name)
                else -> AudioEdit.append(cur, target, src, name)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordSheet()
            else toast(t("Без доступа к микрофону запись невозможна", "Recording needs the microphone"))
        }
    }

    // ---------- 💾 save ----------

    private fun saveTools(c: Col, q: AProject) {
        val fmt = runCatching { AudioRender.Fmt.valueOf(Lumen.prefs.getString("audio_fmt", "M4A") ?: "M4A") }.getOrDefault(AudioRender.Fmt.M4A)
        val norm = Lumen.prefs.getString("audio_norm", "none") ?: "none"
        c.add(text(this, t("Формат", "Format"), Type.LABEL, bold = true, color = Palette.MUTED), 0)
        c.add(choiceChips(this, listOf(AudioRender.Fmt.M4A to t("M4A (AAC) — компактно", "M4A (AAC) — small"), AudioRender.Fmt.WAV to t("WAV — без потерь", "WAV — lossless")), fmt) {
            Lumen.prefs.edit().putString("audio_fmt", it.name).apply()
        }, 4)
        c.add(text(this, t("Выровнять громкость", "Level the loudness"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.add(choiceChips(this, listOf("none" to t("Как есть", "As is"), "peak" to t("Пик −1 дБ", "Peak −1 dB"), "-14" to "−14 LUFS", "-16" to "−16 LUFS"), norm) {
            Lumen.prefs.edit().putString("audio_norm", it).apply()
        }, 4)
        val r = sel
        if (r != null) c.muted(t("Есть выделение: можно сохранить только его.", "There is a selection: you can save just it."))
        c.button(t("💾 Сохранить в Музыка/Lumen", "💾 Save to Music/Lumen"), primary = true) { export(q, null, then = "music") }
        if (r != null) c.button(t("💾 Сохранить только выделенное ", "💾 Save only the selection ") + AudioEdit.time(r.last - r.first), style = BtnStyle.TONAL) { export(q, r, then = "music") }
        c.button(t("↗ Поделиться файлом", "↗ Share the file"), style = BtnStyle.TONAL) { export(q, null, then = "share") }
        c.button(t("📁 Сохранить в папку…", "📁 Save to a folder…"), style = BtnStyle.TONAL) { export(q, null, then = "saf") }
        c.add(text(this, t("Проект", "Project"), Type.TITLE_M, bold = true), 24)
        c.muted(t("Частота ${q.rate} Гц · стерео · ${AudioEdit.time(q.length)} · дорожек ${q.tracks.size} · файлов ${q.sources.size} · ",
            "${q.rate} Hz · stereo · ${AudioEdit.time(q.length)} · ${q.tracks.size} tracks · ${q.sources.size} files · ") +
            String.format(java.util.Locale.US, "%.1f MB", AudioStore.sizeMb(q)))
        c.muted(t("Исходные файлы не меняются: правки хранятся в проекте, его можно открыть и доделать позже.", "The original files stay untouched: edits live in the project, open it any time later."))
    }

    private fun export(q: AProject, range: LongRange?, then: String) {
        if (q.length <= 0) return toast(t("Проект пуст", "The project is empty"))
        val fmt = runCatching { AudioRender.Fmt.valueOf(Lumen.prefs.getString("audio_fmt", "M4A") ?: "M4A") }.getOrDefault(AudioRender.Fmt.M4A)
        val norm = Lumen.prefs.getString("audio_norm", "none") ?: "none"
        val a = range?.first ?: 0L
        val b = range?.last ?: q.length
        val name = ReportKit.safeName(q.name) + "_" + java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + "." + fmt.ext
        val out = ReportKit.shareFile(this, name)
        job(t("Сохраняю ", "Saving ") + fmt.ext.uppercase(), { cancelled, progress ->
            val gain = when (norm) {
                "none" -> 1f
                else -> {
                    val m = AudioRender.measure(q, a, b, 400, cancelled) { progress(it / 3) }
                    if (norm == "peak") AudioEdit.peakGain(m.peak, -1f) else AudioEdit.loudGain(m.gatedDb, m.peak, norm.toFloat())
                }
            }
            AudioRender.write(q, a, b, fmt, out, gain, cancelled) { pct -> progress(if (norm == "none") pct else 33 + pct * 2 / 3) }
            if (then == "music") AudioRender.saveToMusic(this, out, fmt.mime) else out.name
        }) { where ->
            lastExport = out
            when (then) {
                "share" -> ReportKit.share(this, out, false, q.name, fmt.mime)
                "saf" -> runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(fmt.mime).putExtra(Intent.EXTRA_TITLE, out.name), REQ_SAVE_AS)
                }.onFailure { err(it) }
                else -> snack(t("Сохранено: ", "Saved: ") + where, t("Поделиться", "Share")) { ReportKit.share(this, out, false, q.name, fmt.mime) }
            }
        }
    }

    private fun projectMenu() {
        val q = p ?: return
        bottomSheet(q.name) { dismiss ->
            button(t("✎ Переименовать", "✎ Rename"), style = BtnStyle.TONAL) {
                dismiss()
                prompt(t("Имя проекта", "Project name"), "", q.name) { s -> if (s.isNotBlank()) commit(q.copy(name = s.trim())) }
            }
            button(t("⧉ Копия проекта", "⧉ Copy of the project"), style = BtnStyle.TONAL) {
                dismiss()
                job(t("Копирую", "Copying"), { _, _ -> AudioStore.duplicate(q, q.name + t(" (копия)", " (copy)")) }) { n -> openProject(n) }
            }
            button(t("📂 Все проекты", "📂 All projects"), style = BtnStyle.TONAL) { dismiss(); stopPlay(); fromHome = true; p = null; render() }
            button(t("❓ Как пользоваться", "❓ How to use"), style = BtnStyle.TONAL) { dismiss(); HelpActivity.open(this@AudioEditorActivity, "audio") }
            button(t("🗑 Удалить проект", "🗑 Delete the project"), style = BtnStyle.TEXT) {
                dismiss()
                confirm(t("Удалить проект?", "Delete the project?"), q.name, t("Удалить", "Delete"), danger = true) {
                    stopPlay()
                    AudioStore.delete(q.id)
                    fromHome = true
                    p = null
                    render()
                }
            }
        }
    }

    // ---------- home ----------

    private fun home() {
        val c = Col(this)
        if (busy.isNotEmpty()) busyRow(c)
        c.add(StudioActivity.tabs(this, StudioActivity.AUDIO_TAB) { i -> StudioActivity.openTab(this, i) }, 0)
        val hero = Col(this, 16)
        hero.root.background = roundBg(Palette.ACCENT_SOFT, 20f, this)
        hero.add(text(this, t("🎧 Аудиоредактор", "🎧 Audio editor"), Type.TITLE_L, bold = true, color = Palette.ON_SOFT), 0)
        hero.add(text(this, t("Обрезка, склейка нескольких файлов, дорожки и микширование, затухания, громкость и нормализация, шумоподавление, эквалайзер, скорость и тон, 50 эффектов, библиотека звуков, запись с микрофона. Сохранение в M4A или WAV.",
            "Trim, join several files, tracks and mixing, fades, volume and normalising, noise removal, EQ, speed and pitch, 50 effects, a sound library, microphone recording. Saves M4A or WAV."), 13f, color = Palette.ON_SOFT), 6)
        c.add(hero.root, 16)
        c.button(t("📂 Открыть аудиофайлы", "📂 Open audio files"), primary = true) { importMode = "append"; pick("audio/*") }
        c.button(t("🎞 Звук из видео", "🎞 Sound of a video"), style = BtnStyle.TONAL) { importMode = "append"; pick("video/*") }
        c.button(t("🎙 Записать с микрофона", "🎙 Record from the microphone"), style = BtnStyle.TONAL) {
            val q = AudioStore.create(t("Запись", "Recording") + " " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date()), t("Дорожка 1", "Track 1"))
            openProject(q)
            importMode = "append"
            recordSheet()
        }
        c.button(t("＋ Пустой проект", "＋ Empty project"), style = BtnStyle.TEXT) {
            openProject(AudioStore.create(t("Новый проект", "New project"), t("Дорожка 1", "Track 1")))
            tool = 4
        }
        c.muted(t("Файл можно прислать сюда из любого приложения: «Поделиться» → «Аудиоредактор». Несколько файлов склеятся по порядку.",
            "Send a file here from any app: \"Share\" → \"Audio editor\". Several files are joined in order."))
        val list = AudioStore.list()
        if (list.isNotEmpty()) {
            c.h2(t("Проекты", "Projects"))
            list.forEach { q ->
                val box = Col(this, 12)
                box.root.background = ripple(this, roundBg(Palette.CARD, 16f, this), Palette.ON_SURFACE)
                box.add(text(this, "🎧 " + q.name, 15f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 0)
                box.add(text(this, AudioEdit.time(q.length) + " · " + t("дорожек ${q.tracks.size}", "${q.tracks.size} tracks") + " · " +
                    android.text.format.DateUtils.getRelativeTimeSpanString(q.updated, System.currentTimeMillis(), 60_000L), 12f, color = Palette.MUTED), 2)
                box.root.setOnClickListener { openProject(q) }
                box.root.setOnLongClickListener {
                    confirm(t("Удалить проект?", "Delete the project?"), q.name, t("Удалить", "Delete"), danger = true) { AudioStore.delete(q.id); render() }
                    true
                }
                c.add(box.root, 8)
            }
            c.muted(t("Удержание — удалить.", "Hold — delete."))
        }
        page(t("🎧 Аудиоредактор", "🎧 Audio editor"), c.root, actions = listOf("?" to { HelpActivity.open(this, "audio") }), keepScroll = true)
    }

    // ---------- the waveform ----------

    /**
     * Tracks as lanes with the pieces drawn as waveforms; ruler, markers, selection, cursor and playhead.
     * Tap: cursor and piece; double tap: select a piece; hold a piece: drag it; hold an empty spot (or ⟦⟧): select;
     * drag the selection's edges; pinch: zoom.
     */
    inner class WaveView(ctx: Context) : View(ctx) {
        var pxPerMs = 0f
        private var scrollMs = 0f
        var playMs = -1L
        private val ruler = dp(24)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11).toFloat() }
        private val rect = RectF()
        private val path = Path()
        private val dash = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
        private var anchor = -1L
        private var selecting = false
        private var edge = 0 // 1 start, 2 end
        private var dragClip = -1
        private var dragDx = 0f
        private var dragLane = -1
        private var scaling = false
        // effect layers: the one under the finger, how it is dragged (1 move, 2 left edge, 3 right edge) and how far
        private var fxDrag = -1
        private var fxMode = 0
        private var fxDx = 0f
        private val fxRowH = dp(26)

        fun wantedHeight(q: AProject): Int = ruler + q.tracks.size.coerceAtLeast(1) * laneH(q) + fxLaneH(q) + dp(4)

        private fun fxLaneH(q: AProject): Int = if (q.fx.isEmpty()) 0 else ((AudioEdit.fxRows(q.fx).values.maxOrNull() ?: 0) + 1) * fxRowH + dp(6)

        private fun fxTop(q: AProject): Int = ruler + q.tracks.size.coerceAtLeast(1) * laneH(q)

        private fun inFx(y: Float): Boolean = p?.let { q -> q.fx.isNotEmpty() && y >= fxTop(q) } ?: false

        /** The layer under (x, y) in the effects lane. */
        private fun fxAt(x: Float, y: Float): AFx? {
            val q = p ?: return null
            if (!inFx(y)) return null
            val rows = AudioEdit.fxRows(q.fx)
            val row = ((y - fxTop(q) - dp(3)) / fxRowH).toInt()
            val ms = msAt(x)
            return q.fx.lastOrNull { rows[it.id] == row && ms >= it.at - dp(12) / pxPerMs && ms <= it.end + dp(12) / pxPerMs }
        }

        private fun laneH(q: AProject): Int = when (q.tracks.size) {
            0, 1 -> dp(120)
            2 -> dp(84)
            3 -> dp(66)
            else -> dp(52)
        }

        private fun msAt(x: Float): Long = (scrollMs + x / pxPerMs).roundToLong().coerceAtLeast(0)
        private fun xOf(ms: Long): Float = (ms - scrollMs) * pxPerMs

        private fun laneAt(y: Float): Int {
            val q = p ?: return -1
            if (y < ruler || inFx(y)) return -1
            return ((y - ruler) / laneH(q)).toInt().coerceIn(0, q.tracks.size - 1)
        }

        private fun clipAt(lane: Int, ms: Long): AClip? = p?.tracks?.getOrNull(lane)?.clips?.lastOrNull { ms >= it.at && ms <= it.end }

        fun fit() {
            val q = p ?: return
            if (width == 0) { post { fit() }; return }
            pxPerMs = (width - dp(16)).toFloat() / max(5_000L, q.length)
            scrollMs = 0f
            invalidate()
        }

        fun scrollTo(ms: Long) {
            scrollMs = ms.toFloat()
            clampScroll()
            invalidate()
        }

        /** Brings [ms] into view if it is off screen. */
        fun show(ms: Long) {
            if (pxPerMs <= 0f) return
            val x = xOf(ms)
            if (x < 0 || x > width) scrollTo((ms - width * 0.3f / pxPerMs).roundToLong())
        }

        /** While playing: the page turns when the playhead nears the right edge. */
        fun follow(ms: Long) {
            if (pxPerMs <= 0f || scaling || selecting || dragClip >= 0) return
            val x = xOf(ms)
            if (x > width * 0.9f || x < 0) scrollTo((ms - width * 0.1f / pxPerMs).roundToLong())
        }

        private fun clampScroll() {
            val q = p ?: return
            val maxMs = max(0f, q.length - width * 0.5f / max(pxPerMs, 1e-6f))
            scrollMs = scrollMs.coerceIn(0f, max(0f, maxMs))
        }

        private fun snapTol(): Long = (dp(10) / max(pxPerMs, 1e-6f)).roundToLong()

        private fun snapped(ms: Long): Long = p?.let { AudioEdit.snap(ms, AudioEdit.snapPoints(it) + cursor, snapTol()) } ?: ms

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val focus = scrollMs + d.focusX / pxPerMs
                pxPerMs = (pxPerMs * d.scaleFactor).coerceIn(0.0005f, 2f)
                scrollMs = focus - d.focusX / pxPerMs
                clampScroll()
                invalidate()
                return true
            }
        })

        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                edge = 0
                fxDrag = -1
                fxDx = 0f
                fxAt(e.x, e.y)?.let { f ->
                    fxDrag = f.id
                    val x0 = xOf(f.at)
                    val x1 = xOf(f.end)
                    fxMode = when {
                        abs(e.x - x0) < dp(16) && x1 - x0 > dp(40) -> 2
                        abs(e.x - x1) < dp(16) && x1 - x0 > dp(40) -> 3
                        e.x < x0 -> 2
                        e.x > x1 -> 3
                        else -> 1
                    }
                    return true
                }
                val r = sel
                if (r != null && e.y >= ruler) {
                    if (abs(e.x - xOf(r.first)) < dp(20)) edge = 1 else if (abs(e.x - xOf(r.last)) < dp(20)) edge = 2
                }
                if (selectMode && edge == 0 && e.y >= ruler) {
                    selecting = true
                    anchor = snapped(msAt(e.x))
                    pickLane()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaling) return true
                val ms = msAt(e2.x)
                when {
                    fxDrag >= 0 -> {
                        fxDx -= dx
                        edgeScroll(e2.x)
                    }
                    edge != 0 -> {
                        val r = sel ?: return true
                        val v = snapped(ms)
                        sel = if (edge == 1) min(v, r.last - 20)..r.last else r.first..max(v, r.first + 20)
                        edgeScroll(e2.x)
                    }
                    dragClip >= 0 -> {
                        dragDx -= dx
                        dragLane = laneAt(e2.y).let { if (it < 0) dragLane else it }
                        edgeScroll(e2.x)
                    }
                    selecting -> {
                        val v = snapped(ms)
                        sel = min(anchor, v)..max(anchor, v)
                        edgeScroll(e2.x)
                    }
                    else -> {
                        scrollMs += dx / pxPerMs
                        clampScroll()
                    }
                }
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val q = p ?: return true
                if (fxDrag >= 0) {
                    val id = fxDrag
                    fxDrag = -1
                    layerSheet(id)
                    return true
                }
                if (inFx(e.y)) return true
                val ms = msAt(e.x).coerceAtMost(q.length)
                cursor = if (e.y < ruler) ms else snapped(ms)
                if (e.y >= ruler) {
                    val lane = laneAt(e.y)
                    selClip = clipAt(lane, ms)?.id ?: -1
                    if (sel?.let { ms in it } != true) sel = null
                }
                if (player.playing) play(cursor, player.loop)
                render()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val q = p ?: return true
                if (inFx(e.y)) return true
                val lane = laneAt(e.y)
                val c = clipAt(lane, msAt(e.x))
                if (c != null) {
                    sel = c.at..c.end
                    selTrack = if (q.tracks.size > 1) q.tracks[lane].id else -1
                    selClip = c.id
                } else if (q.length > 0) {
                    sel = 0L..q.length
                    selTrack = -1
                }
                render()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (scaling || edge != 0 || selecting || e.y < ruler || fxDrag >= 0 || inFx(e.y)) return
                val lane = laneAt(e.y)
                val c = clipAt(lane, msAt(e.x))
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (c != null && !selectMode) {
                    dragClip = c.id
                    selClip = c.id
                    dragDx = 0f
                    dragLane = lane
                } else {
                    selecting = true
                    anchor = snapped(msAt(e.x))
                    sel = anchor..anchor
                    pickLane()
                }
                invalidate()
            }
        })

        /** A new selection covers all tracks; the chips above it narrow it to one. */
        private fun pickLane() {
            selTrack = -1
        }

        private fun edgeScroll(x: Float) {
            val zone = dp(32)
            if (x < zone) scrollMs -= (zone - x) / pxPerMs * 0.3f else if (x > width - zone) scrollMs += (x - (width - zone)) / pxPerMs * 0.3f
            clampScroll()
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (pxPerMs <= 0f) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            scaler.onTouchEvent(e)
            gestures.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                val q = p
                if (fxDrag >= 0 && q != null) {
                    val f = AudioEdit.fxOf(q, fxDrag)
                    if (f != null && abs(fxDx) > dp(3)) {
                        val d = (fxDx / pxPerMs).roundToLong()
                        val pts = AudioEdit.snapPoints(q.copy(fx = q.fx.filter { it.id != f.id })) + cursor
                        val n = when (fxMode) {
                            2 -> { val a = AudioEdit.snap(f.at + d, pts, snapTol()).coerceIn(0L, f.end - AudioEdit.FX_MIN_MS); f.copy(at = a, len = f.end - a) }
                            3 -> { val b = AudioEdit.snap(f.end + d, pts, snapTol()).coerceAtLeast(f.at + AudioEdit.FX_MIN_MS); f.copy(len = b - f.at) }
                            else -> {
                                val a1 = AudioEdit.snap(f.at + d, pts, snapTol())
                                val a2 = AudioEdit.snap(f.end + d, pts, snapTol()) - f.len
                                f.copy(at = if (a1 != f.at + d) a1 else if (a2 != f.at + d) a2 else f.at + d)
                            }
                        }
                        commit(AudioEdit.updateFx(q, f.id) { n })
                    }
                    fxDrag = -1
                    fxDx = 0f
                    invalidate()
                } else if (dragClip >= 0 && q != null) {
                    val c = q.clip(dragClip)
                    val lane = dragLane.coerceIn(0, q.tracks.size - 1)
                    if (c != null && (abs(dragDx) > dp(4) || q.trackOf(c.id)?.id != q.tracks[lane].id)) {
                        val raw = (c.at + dragDx / pxPerMs).roundToLong().coerceAtLeast(0)
                        // stick the start (or the end) to edges and markers
                        val pts = AudioEdit.snapPoints(q.copy(tracks = q.tracks.map { tr -> tr.copy(clips = tr.clips.filter { it.id != c.id }) })) + cursor
                        val s1 = AudioEdit.snap(raw, pts, snapTol())
                        val s2 = AudioEdit.snap(raw + c.len, pts, snapTol()) - c.len
                        val at = if (s1 != raw) s1 else if (s2 != raw) s2 else raw
                        commit(AudioEdit.moveClip(q, c.id, max(0L, at), q.tracks[lane].id))
                    }
                    dragClip = -1
                    dragDx = 0f
                    render()
                } else if (selecting || edge != 0) {
                    sel = sel?.takeIf { it.last - it.first >= 20 }
                    selecting = false
                    edge = 0
                    render()
                }
                scaling = false
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (pxPerMs <= 0f && w > 0) fit()
        }

        override fun onDraw(cv: Canvas) {
            val q = p ?: return
            val night = context.isNight()
            cv.drawColor(if (night) 0xFF15161A.toInt() else 0xFFF3F4F7.toInt())
            if (pxPerMs <= 0f) return
            val lh = laneH(q)
            val w = width.toFloat()
            // lanes
            q.tracks.forEachIndexed { i, tr ->
                val y0 = (ruler + i * lh).toFloat()
                if (i % 2 == 1) { paint.color = if (night) 0xFF1B1C21.toInt() else 0xFFEBECF0.toInt(); cv.drawRect(0f, y0, w, y0 + lh, paint) }
                if (selTrack == tr.id && sel != null) { paint.color = (Palette.ACCENT and 0x00FFFFFF) or 0x14000000; cv.drawRect(0f, y0, w, y0 + lh, paint) }
            }
            // clips
            val solo = q.tracks.any { it.solo }
            q.tracks.forEachIndexed { i, tr ->
                val color = TRACK_COLORS[i % TRACK_COLORS.size]
                val dim = tr.mute || (solo && !tr.solo)
                for (c in tr.clips) if (c.id != dragClip) drawClip(cv, q, c, tr, i, color, dim, 0f, i)
            }
            if (dragClip >= 0) {
                val c = q.clip(dragClip)
                val i = q.tracks.indexOfFirst { t -> t.clips.any { it.id == dragClip } }
                if (c != null && i >= 0) drawClip(cv, q, c, q.tracks[i], i, TRACK_COLORS[i % TRACK_COLORS.size], false, dragDx, dragLane.coerceIn(0, q.tracks.size - 1), lifted = true)
            }
            drawFx(cv, q, night)
            // selection
            sel?.let { r ->
                val x0 = xOf(r.first)
                val x1 = xOf(r.last)
                val lanes = if (selTrack < 0) 0 until q.tracks.size else listOf(q.tracks.indexOfFirst { it.id == selTrack }.coerceAtLeast(0))
                paint.style = Paint.Style.FILL
                paint.color = (Palette.ACCENT and 0x00FFFFFF) or 0x40000000
                for (l in lanes) cv.drawRect(x0, (ruler + l * lh).toFloat(), x1, (ruler + (l + 1) * lh).toFloat(), paint)
                paint.color = Palette.ACCENT
                paint.strokeWidth = dp(2).toFloat()
                cv.drawLine(x0, ruler.toFloat(), x0, height.toFloat(), paint)
                cv.drawLine(x1, ruler.toFloat(), x1, height.toFloat(), paint)
                cv.drawCircle(x0, (ruler + dp(6)).toFloat(), dp(6).toFloat(), paint)
                cv.drawCircle(x1, (ruler + dp(6)).toFloat(), dp(6).toFloat(), paint)
            }
            drawRuler(cv, q, w, night)
            // markers
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.pathEffect = dash
            paint.color = 0xFFF59E0B.toInt()
            for (m in q.markers) {
                val x = xOf(m.at)
                if (x < -dp(10) || x > w + dp(10)) continue
                cv.drawLine(x, ruler.toFloat(), x, height.toFloat(), paint)
            }
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            for (m in q.markers) {
                val x = xOf(m.at)
                if (x < -dp(10) || x > w + dp(10)) continue
                path.reset()
                path.moveTo(x, ruler.toFloat())
                path.lineTo(x - dp(5), ruler - dp(9).toFloat())
                path.lineTo(x + dp(5), ruler - dp(9).toFloat())
                path.close()
                cv.drawPath(path, paint)
            }
            // cursor and playhead
            paint.strokeWidth = dp(2).toFloat()
            paint.color = if (night) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
            val cx = xOf(cursor)
            cv.drawLine(cx, 0f, cx, height.toFloat(), paint)
            if (playMs >= 0) {
                paint.color = 0xFFEF4444.toInt()
                val px = xOf(playMs)
                cv.drawLine(px, 0f, px, height.toFloat(), paint)
            }
        }

        private fun drawFx(cv: Canvas, q: AProject, night: Boolean) {
            if (q.fx.isEmpty()) return
            val top = fxTop(q).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (night) 0xFF1E1A26.toInt() else 0xFFF0ECF8.toInt()
            cv.drawRect(0f, top, width.toFloat(), top + fxLaneH(q), paint)
            val rows = AudioEdit.fxRows(q.fx)
            val violet = 0xFF8B5CF6.toInt()
            for (f in q.fx) {
                var a = f.at.toFloat()
                var b = f.end.toFloat()
                if (f.id == fxDrag) {
                    val d = fxDx / pxPerMs
                    when (fxMode) { 2 -> a += d; 3 -> b += d; else -> { a += d; b += d } }
                }
                val x0 = (a - scrollMs) * pxPerMs
                val x1 = (b - scrollMs) * pxPerMs
                if (x1 < 0 || x0 > width) continue
                val y0 = top + dp(3) + (rows[f.id] ?: 0) * fxRowH
                rect.set(x0, y0, max(x0 + dp(4), x1), y0 + fxRowH - dp(4))
                val empty = AudioEdit.fxEmpty(f)
                paint.style = Paint.Style.FILL
                paint.color = (violet and 0x00FFFFFF) or ((if (empty) 0x30 else 0x30 + (0x70 * f.k).toInt()) shl 24)
                cv.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                if (f.id == fxDrag) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2).toFloat()
                    paint.color = violet
                    cv.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                    paint.style = Paint.Style.FILL
                }
                // edge grips
                paint.color = if (night) 0xCCFFFFFF.toInt() else 0x99000000.toInt()
                cv.drawRect(rect.left + dp(2), rect.top + dp(6), rect.left + dp(4), rect.bottom - dp(6), paint)
                cv.drawRect(rect.right - dp(4), rect.top + dp(6), rect.right - dp(2), rect.bottom - dp(6), paint)
                txt.color = if (night) 0xFFEDE9FE.toInt() else 0xFF3B2A6B.toInt()
                cv.save()
                cv.clipRect(max(rect.left, 0f), rect.top, min(rect.right, width.toFloat()), rect.bottom)
                cv.drawText(EditSound.label(f.fx).ifEmpty { "🎚 " + t("эффект?", "effect?") }, max(rect.left, 0f) + dp(7), rect.top + dp(14), txt)
                cv.restore()
            }
        }

        private fun drawRuler(cv: Canvas, q: AProject, w: Float, night: Boolean) {
            paint.style = Paint.Style.FILL
            paint.color = if (night) 0xFF202127.toInt() else 0xFFFFFFFF.toInt()
            cv.drawRect(0f, 0f, w, ruler.toFloat(), paint)
            val step = StudioEdit.tickStep(pxPerMs, dp(64).toFloat())
            txt.color = if (night) 0xFFB8BAC3.toInt() else 0xFF5B5E68.toInt()
            paint.color = txt.color
            paint.strokeWidth = 1f
            var ms = (scrollMs.toLong() / step) * step
            val end = msAt(w)
            while (ms <= end) {
                val x = xOf(ms)
                cv.drawLine(x, ruler - dp(6).toFloat(), x, ruler.toFloat(), paint)
                cv.drawText(AudioEdit.time(ms, tenths = step < 1000), x + dp(3), ruler - dp(9).toFloat(), txt)
                val half = x + step * pxPerMs / 2
                cv.drawLine(half, ruler - dp(3).toFloat(), half, ruler.toFloat(), paint)
                ms += step
            }
            if (q.length > 0) {
                // the end of the project
                val x = xOf(q.length)
                if (x in 0f..w) { paint.color = 0x66888888; cv.drawRect(x, ruler.toFloat(), w, height.toFloat(), paint) }
            }
        }

        private fun drawClip(cv: Canvas, q: AProject, c: AClip, tr: ATrack, ti: Int, color: Int, dim: Boolean, dx: Float, lane: Int, lifted: Boolean = false) {
            val lh = laneH(q)
            val x0 = xOf(c.at) + dx
            val x1 = xOf(c.end) + dx
            if (x1 < 0 || x0 > width) return
            val y0 = (ruler + lane * lh + dp(3)).toFloat()
            val y1 = (ruler + (lane + 1) * lh - dp(3)).toFloat()
            rect.set(x0, y0, max(x0 + 2, x1), y1)
            paint.style = Paint.Style.FILL
            paint.color = (color and 0x00FFFFFF) or ((if (dim) 0x18 else if (lifted) 0x60 else 0x30) shl 24)
            cv.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
            // waveform
            val pk = peaksOf(c.src)
            val mid = (y0 + y1) / 2 + dp(5)
            val half = (y1 - y0) / 2 - dp(7)
            paint.color = (color and 0x00FFFFFF) or ((if (dim) 0x50 else 0xE0) shl 24)
            paint.strokeWidth = dp(1).toFloat().coerceAtLeast(1.5f)
            val stepPx = 2f
            var x = max(x0, 0f)
            val xe = min(x1, width.toFloat())
            val fi = c.fadeIn
            val fo = c.fadeOut
            while (x < xe) {
                val ta = (scrollMs + (x - dx) / pxPerMs).toLong()
                val tb = (scrollMs + (x + stepPx - dx) / pxPerMs).toLong()
                val sa = AudioEdit.srcAt(c, ta) * q.rate / 1000
                val sb = AudioEdit.srcAt(c, tb) * q.rate / 1000
                var v = pk.max(sa, sb) * AudioEdit.envAt(c, ta) * tr.gain
                val u = ta - c.at
                if (fi > 0 && u < fi) v *= u.toFloat() / fi
                if (fo > 0 && c.len - u < fo) v *= (c.len - u).toFloat() / fo
                val h = (sqrt(v.coerceIn(0f, 1f)) * half).coerceAtLeast(0.5f)
                cv.drawLine(x, mid - h, x, mid + h, paint)
                x += stepPx
            }
            // fade shapes
            paint.color = 0x55000000
            if (fi > 0) {
                path.reset(); path.moveTo(x0, y0); path.lineTo(x0 + fi * pxPerMs, y0); path.lineTo(x0, y1); path.close(); cv.drawPath(path, paint)
            }
            if (fo > 0) {
                path.reset(); path.moveTo(x1, y0); path.lineTo(x1 - fo * pxPerMs, y0); path.lineTo(x1, y1); path.close(); cv.drawPath(path, paint)
            }
            // label
            if (x1 - x0 > dp(36)) {
                txt.color = if (context.isNight()) 0xFFE8E9EE.toInt() else 0xFF22242A.toInt()
                val label = c.name + clipBadges(c)
                cv.save()
                cv.clipRect(max(x0, 0f), y0, min(x1, width.toFloat()), y1)
                cv.drawText(label, max(x0, 0f) + dp(5), y0 + dp(12), txt)
                cv.restore()
            }
            if (c.id == selClip || lifted) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Palette.ACCENT
                cv.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}

/** "Share → Audio editor" (one or several audio files, or "Open with"): a new project with them joined in order. */
class AudioShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        val i = intent
        when (i?.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> i.data?.let { uris += it }
        }
        if (uris.isEmpty()) i?.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris += it } }
        if (uris.isNotEmpty()) runCatching {
            val out = Intent(this, AudioEditorActivity::class.java)
                .putParcelableArrayListExtra(AudioEditorActivity.EXTRA_URIS, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            out.clipData = android.content.ClipData.newRawUri("audio", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }
            startActivity(out)
        }.onFailure { android.widget.Toast.makeText(this, it.message ?: "Lumen", android.widget.Toast.LENGTH_LONG).show() }
        else android.widget.Toast.makeText(this, if (I18n.ru) "Аудио не передано" else "No audio was shared", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun EditText.changed(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s: Editable?) = f(s?.toString().orEmpty())
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
})

/**
 * Animation blocks of an open editor: renders their frames one at a time in a WebView shown in a small card over
 * the preview (again when a block changes size, length or its words), picks the answer of another AI from the
 * clipboard when the app gets focus back, and checks what still needs a render before an export.
 */
internal class EditAnims(private val a: StudioEditorActivity) {
    val host by lazy { CanvasHost(a) }
    private val queue = ArrayDeque<String>()
    var running: String? = null
        private set
    /** The wizard is taking preview shots with the same WebView: renders wait. */
    var shooting = false
        set(v) {
            field = v
            if (!v) next()
        }
    private var task: BgTasks.Task? = null
    private var card: LinearLayout? = null
    private var cardText: TextView? = null
    private var webBox: FrameLayout? = null
    private val codes = HashMap<String, String?>()
    private var lastClip: String? = null
    private val checkRun = Runnable { check() }

    fun checkLater() {
        val v = a.window?.decorView ?: return
        v.removeCallbacks(checkRun)
        v.postDelayed(checkRun, 1500)
    }

    fun codeOf(codeDir: String): String? = codes.getOrPut(codeDir) { File(a.dir, "$codeDir/${EditAnim.CODE}").takeIf { it.exists() }?.readText() }

    fun forget(codeDir: String) = codes.remove(codeDir)

    /** Pixel size of the frames of [b] (the export frame). */
    fun size(p: EProject, b: EBlock): Pair<Int, Int> = EditCanvas.frame(p).let { (fw, fh) -> EditAnim.renderSize(b, fw, fh) }

    /** Colours the animation gets: accent, text, plate — like the panel of a scheme of the same style. */
    fun colors(style: String, accent: Int?): Triple<Int, Int, Int> {
        val st = (if (style.isBlank() || style == SchemeStyles.AUTO) null else SchemeStyles.find(a.styleDir, style))
        val ink = st?.ink ?: 0xFFFFFFFF.toInt()
        fun light(c: Int) = SchemeStyles.luminance(c) > 0.4
        val card = if (st == null) 0xE6101318.toInt() else if (light(ink)) (st.bg.takeIf { !light(it) } ?: 0xE6101318.toInt()) else 0xF2F4F6FA.toInt()
        return Triple(accent ?: st?.accent ?: StudioPrefs.accent, ink, card)
    }

    /** What the request and the animation know of block [b] of project [p]. */
    fun ctxOf(p: EProject, startMs: Long, durMs: Long, box: PlaceBox, brief: EditBrief, others: List<String> = emptyList()): InsertCtx {
        val (fw, fh) = EditCanvas.frame(p)
        val (acc, ink, card) = colors(brief.style.ifEmpty { a.lastStyle }, brief.accent)
        return InsertCtx(fw, fh, box, startMs, durMs, a.words, a.lang(), subs = p.blocks.any { EditCaptions.isSub(it) } || File(p.source + ".words.json").exists(),
            others = others, accent = acc, ink = ink, card = card, font = brief.font.ifEmpty { "sans" })
    }

    /** The data of [b]'s animation: lumen.anim v1 data, or for a Montage template the data of the whole video. */
    fun data(p: EProject, b: EBlock, w: Int, h: Int): JSONObject {
        val brief = EditInsert.briefFrom(b.brief) ?: EditBrief()
        val ctx = ctxOf(p, b.startMs, b.durMs, PlaceBox(b.x, b.y, b.w, b.ratio), brief)
        if (brief.type == "montage") {
            val ws = a.words.map { Triple(it.text, it.startMs, it.endMs) }
            return WebLayer.withVideo(WebLayer.dataJson(ws, emptyList(), "", ctx.lang), w, h, a.outLen(p), String.format("#%06X", ctx.accent and 0xFFFFFF))
        }
        return EditInsert.animData(brief, ctx, w, h, b.fps)
    }

    /** codeDir/renderKey the block should show now; null when its code is missing. */
    fun wantFile(p: EProject, b: EBlock): String? {
        val cd = EditAnim.codeDirOf(b.file)
        val code = codeOf(cd) ?: return null
        val (w, h) = size(p, b)
        return cd + "/" + EditInsert.renderKey(code, w, h, b.durMs, b.fps, b.loopMs, data(p, b, w, h).toString())
    }

    /** Animation blocks whose frames are missing or do not fit them any more. */
    fun stale(p: EProject): List<EBlock> = p.blocks.filter { b ->
        b.kind == EditAnim.KIND && b.file.isNotEmpty() && (wantFile(p, b)?.let { it != b.file || !File(a.dir, "$it/${EditAnim.META}").exists() } ?: false)
    }

    /** Every animation block whose frames do not fit it is rendered again. */
    fun check() {
        val p = a.project ?: return
        stale(p).forEach { enqueue(it.id) }
    }

    fun enqueue(id: String) {
        if (id != running && id !in queue) queue.addLast(id)
        next()
    }

    private fun next() {
        if (running != null || shooting || a.isFinishing || a.isDestroyed) return
        val id = queue.removeFirstOrNull() ?: return hideCard()
        val p = a.project ?: return
        val b = StudioEdit.find(p.blocks, id)
        if (b == null || b.kind != EditAnim.KIND || b.file.isEmpty()) return next()
        val want = wantFile(p, b) ?: return next()
        val out = File(a.dir, want)
        if (File(out, EditAnim.META).exists()) {
            setFile(id, want)
            return next()
        }
        val code = codeOf(EditAnim.codeDirOf(b.file)) ?: return next()
        val (w, h) = size(p, b)
        val data = data(p, b, w, h)
        running = id
        showCard(b.text.ifBlank { t("Анимация", "Animation") }, w, h)
        task = BgTasks.run(a, "studioedit:anim:$id", t("Редактор: рисую анимацию", "Editor: rendering an animation"), BgTasks.reopen(a), { _ ->
            AnimRender.run(host, code, data, w, h, b.fps, b.durMs, b.loopMs, out, { a.isDestroyed }) { done, n ->
                a.runOnUiThread { cardText?.text = "🎞 " + b.text.take(24) + " · " + (done * 100 / max(1, n)) + "%" }
            }
        }, { e ->
            running = null
            task = null
            hideCard()
            if (!a.isDestroyed && e !is java.util.concurrent.CancellationException) {
                val msg = (e as? CanvasHost.LayerError)?.message ?: e.message.orEmpty()
                a.snack(t("Ошибка в анимации: ", "Error in the animation: ") + msg.take(120), t("Исправить", "Fix")) {
                    StudioEdit.find(a.blocks, id)?.let { blk -> InsertWizard.openFix(a, blk, msg) }
                }
            }
            next()
        }) { r ->
            running = null
            task = null
            setFile(id, want)
            a.stage?.refresh()
            if (r.empty == r.count) a.toast(t("Анимация ничего не нарисовала — проверьте её в помощнике", "The animation drew nothing — check it in the wizard"))
            next()
        }
    }

    private fun setFile(id: String, file: String) {
        val p = a.project ?: return
        if (StudioEdit.find(p.blocks, id)?.file == file) return
        a.setBlocks(p.blocks.map { if (it.id == id) it.copy(file = file) else it })
        a.save()
        a.stage?.refresh()
    }

    private fun showCard(name: String, w: Int, h: Int) {
        val box = a.previewBox ?: return
        val c = card ?: LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(0xE6101318.toInt(), 14f, a)
            setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
            val wb = FrameLayout(a).apply { background = roundBg(0xFF2A2D34.toInt(), 8f, a) }
            webBox = wb
            addView(wb, LinearLayout.LayoutParams(a.dp(52), a.dp(52)))
            val tv = text(a, "", 12f, bold = true, color = Color.WHITE).apply {
                maxLines = 2
                setPadding(a.dp(8), 0, a.dp(8), 0)
            }
            cardText = tv
            addView(tv, LinearLayout.LayoutParams(a.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text(a, "✕", 16f, bold = true, color = Color.WHITE).apply {
                contentDescription = t("Остановить", "Stop")
                setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4))
                setOnClickListener { task?.let { BgTasks.stop(it) } }
            })
            card = this
            box.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(a.dp(8), a.dp(8), 0, 0)
            })
        }
        c.visibility = View.VISIBLE
        cardText?.text = "🎞 " + name.take(24) + " · 0%"
        val k = min(a.dp(52).toFloat() / w, a.dp(52).toFloat() / h)
        webBox?.let { host.attach(it, max(8, (w * k).toInt()), max(8, (h * k).toInt())) }
    }

    private fun hideCard() {
        card?.visibility = View.GONE
    }

    /** The app is back in front: an answer of another AI in the clipboard for a block that waits for it. */
    fun onFocus() {
        val p = a.project ?: return
        val waiting = p.blocks.filter { it.req.isNotEmpty() }
        if (waiting.isEmpty()) return
        val clip = clipText(a) ?: return
        if (clip == lastClip) return
        val ans = EditInsert.detect(clip) ?: return
        val b = waiting.firstOrNull { it.req == ans.req } ?: waiting.singleOrNull()?.takeIf { ans.req.isEmpty() } ?: return
        lastClip = clip
        val w = InsertWizard.current
        if (w != null && w.waitsFor(b.id)) return w.takeAnswer(ans)
        a.snack(t("Ответ ИИ: «", "AI answer: \"") + ans.name.ifBlank { t("без названия", "untitled") }.take(30) + t("» — для блока #", "\" — for block #") + b.req,
            t("Проверить", "Check")) { InsertWizard.openAnswer(a, b, ans) }
    }

    fun destroy() {
        a.window?.decorView?.removeCallbacks(checkRun)
        host.destroy()
    }

    companion object {
        fun clipText(ctx: Context): String? = try {
            ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (e: Exception) {
            null
        }

        fun copy(ctx: Context, s: String) {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("lumen", s))
        }
    }
}

/**
 * «✨ Помощник вставки» of the Studio editor (plan PLAN_EDITOR_INSERT, protocol lumen.insert v1): 1 what — a type
 * by meaning, 2 the brief — a mock-up on the frame and sections A–E with "auto" everywhere, 3 who makes it — the
 * built-in AI or another one (copy → paste, a block waits with the request mark), 4 the check — frames of the
 * result, changes without AI and through it, insert.
 */
internal class InsertWizard private constructor(private val a: StudioEditorActivity, private var blockId: String?, private val span: IntRange?) {
    companion object {
        private const val PREF_LAST = "studio_ins_last"
        @Volatile private var seq = 0

        /** The wizard on screen (to hand it the answer found in the clipboard). */
        var current: InsertWizard? = null
            private set

        fun open(a: StudioEditorActivity, old: EBlock? = null, span: IntRange? = null) {
            if (a.project == null) return
            val w = InsertWizard(a, old?.id, span)
            when {
                old == null -> w.show(1)
                old.pending && old.req.isNotEmpty() -> {
                    w.who = "other"
                    w.show(3)
                }
                else -> {
                    w.answer = w.answerOf(old)
                    w.show(if (w.answer != null) 4 else 2)
                }
            }
        }

        fun openAnswer(a: StudioEditorActivity, b: EBlock, ans: InsertAnswer) {
            if (a.project == null) return
            InsertWizard(a, b.id, null).apply { answer = ans }.show(4)
        }

        fun openFix(a: StudioEditorActivity, b: EBlock, error: String) {
            if (a.project == null) return
            InsertWizard(a, b.id, null).apply {
                answer = answerOf(b)
                issues = listOf(error)
            }.show(4)
        }
    }

    private val p0: EProject = a.project!!
    private val fw: Int
    private val fh: Int
    private var brief: EditBrief
    private var kind: String
    private var startMs: Long
    private var durMs: Long
    private var step = 1
    private var col: Col? = null
    private var close: (() -> Unit)? = null
    private var answer: InsertAnswer? = null
    private var issues: List<String> = emptyList()
    private var shots: List<Bitmap> = emptyList()
    private var slowMs = 0L
    private var checking = false
    private var working = false
    private var who = ""
    private var req = ""
    private var keepBox: PlaceBox? = null
    private val opened = mutableSetOf("A")
    private var frameBmp: Bitmap? = null
    private var videoLum: Float? = null
    private var pick = ""
    private var mock: View? = null
    private var summaryView: TextView? = null
    private var warnBox: LinearLayout? = null
    private var correction = ""
    private var fromLibrary = false
    /** The face found on the frame under the playhead (x, y, w, h fractions), empty when none. */
    private var face: List<Float> = emptyList()

    init {
        val (w, h) = EditCanvas.frame(p0)
        fw = w
        fh = h
        val old = blockId?.let { StudioEdit.find(p0.blocks, it) }
        val sp = span?.let { StudioEdit.spanOf(a.words, it, a.outLen(p0)) }
        startMs = old?.startMs ?: sp?.first ?: a.curMs
        durMs = old?.durMs ?: sp?.second ?: 5000L
        brief = EditInsert.briefFrom(old?.brief) ?: EditInsert.defaults("custom", blockWords(), brand(), fh > fw).let { d ->
            if (old == null) d else d.copy(kind = old.kind.takeIf { it == "scheme" || it == EditAnim.KIND } ?: "", layout = old.scheme?.layout.orEmpty(), what = old.ask,
                opacity = old.opacity, dim = old.dim, dimAll = old.dimAll, style = old.look.takeIf { old.kind == "scheme" }.orEmpty())
        }
        kind = old?.kind?.takeIf { it == "scheme" || it == EditAnim.KIND } ?: EditInsert.kindOf(brief)
        req = old?.req.orEmpty()
        if (old != null) keepBox = PlaceBox(old.x, old.y, old.w, old.ratio)
        grabFrame()
    }

    // ---------- context ----------

    private fun blockWords(): List<String> = a.words.filter { it.startMs >= startMs && it.startMs < startMs + durMs }.map { it.text }

    private fun brand(): EditBrief? = EditInsert.briefFrom(a.project?.brand?.takeIf { it.isNotEmpty() })

    private fun box(): PlaceBox = keepBox ?: EditInsert.place(brief, kind, fw, fh)

    private fun ctxNow(): InsertCtx {
        val others = a.blocks.filter { it.id != blockId && it.endMs > startMs && it.startMs < startMs + durMs && it.kind != EditMusic.KIND }
            .take(4).map { b -> a.blockName(b) + String.format(java.util.Locale.US, " (x %.2f, y %.2f, w %.2f)", b.x, b.y, b.w) }
        return a.anims.ctxOf(a.project ?: p0, startMs, durMs, box(), brief, others)
    }

    /** The frame under the playhead for the mock-up, the face on it and its brightness under the block. */
    private fun grabFrame() {
        val tv = a.texture ?: return
        val k = min(1f, 540f / max(fw, fh))
        val bmp = runCatching { tv.getBitmap(max(2, (fw * k).toInt()), max(2, (fh * k).toInt())) }.getOrNull() ?: return
        frameBmp = bmp
        findFace(bmp)?.let { face = it }
        if (brief.face.isEmpty() && face.isNotEmpty()) brief = brief.copy(face = face)
        lum()
    }

    private fun findFace(bmp: Bitmap): List<Float>? = runCatching {
        val w = bmp.width / 2 * 2
        val small = Bitmap.createScaledBitmap(bmp, w, bmp.height, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<android.media.FaceDetector.Face>(2)
        val n = android.media.FaceDetector(small.width, small.height, 2).findFaces(small, faces)
        val f = faces.take(n).filterNotNull().maxByOrNull { it.eyesDistance() } ?: return@runCatching null
        val m = PointF()
        f.getMidPoint(m)
        val e = f.eyesDistance()
        val bw = e * 2.8f
        val bh = e * 3.4f
        listOf(((m.x - bw / 2) / small.width).coerceIn(0f, 1f), ((m.y - bh * 0.4f) / small.height).coerceIn(0f, 1f),
            (bw / small.width).coerceIn(0.05f, 1f), (bh / small.height).coerceIn(0.05f, 1f))
    }.getOrNull()

    /** Mean brightness of the frame under the block (0..1). */
    private fun lum() {
        val bmp = frameBmp ?: return
        val b = box()
        val hw = b.w / 2
        val hh = b.w * fw * b.ratio / 2f / fh
        var sum = 0.0
        var n = 0
        for (i in 0 until 8) for (j in 0 until 8) {
            val x = ((b.x - hw + b.w * (i + 0.5f) / 8) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = ((b.y - hh + 2 * hh * (j + 0.5f) / 8) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            sum += SchemeStyles.luminance(bmp.getPixel(x, y) or 0xFF000000.toInt())
            n++
        }
        videoLum = if (n > 0) (sum / n).toFloat() else null
    }

    fun waitsFor(id: String): Boolean = step == 3 && blockId == id && close != null

    /** A result kept in block [b]: its scheme, or its animation code. */
    fun answerOf(b: EBlock): InsertAnswer? = when {
        b.kind == "scheme" && b.scheme != null -> InsertAnswer.Scheme(b.scheme, "", b.scheme.title)
        b.kind == EditAnim.KIND && b.file.isNotEmpty() -> a.anims.codeOf(EditAnim.codeDirOf(b.file))?.let { InsertAnswer.Anim(it, "", b.text, b.loopMs) }
        else -> null
    }

    private fun payload(ans: InsertAnswer?): String = when (ans) {
        is InsertAnswer.Scheme -> StudioScheme.toJson(ans.scheme).toString()
        is InsertAnswer.Anim -> ans.code
        null -> ""
    }

    // ---------- the sheet ----------

    fun show(s: Int) {
        a.pause()
        step = s
        current = this
        a.bottomSheet(t("✨ Помощник вставки", "✨ Insert wizard"), onDismiss = {
            if (current === this) current = null
            close = null
            col = null
        }) { dismiss ->
            col = this
            close = dismiss
            render()
        }
    }

    private fun go(s: Int) {
        step = s
        render()
        if (s == 4 && answer != null && shots.isEmpty() && !checking) check()
    }

    private fun render() {
        val c = col ?: return
        c.clear()
        val ctx = c.ctx
        c.add(text(ctx, t("✨ Помощник вставки", "✨ Insert wizard"), Type.TITLE_L), 0)
        c.steps(step - 1, 4)
        val names = listOf(t("Что", "What"), t("Бриф", "Brief"), t("ИИ", "AI"), t("Проверка", "Check"))
        c.add(text(ctx, names.mapIndexed { i, n -> if (i == step - 1) "● $n" else n }.joinToString("  ·  "), 12f, bold = true, color = Palette.MUTED), 4)
        StudioEdit.momentText(a.words, startMs, 500, durMs).takeIf { it.isNotBlank() && step <= 2 }?.let { c.muted(StudioEdit.clock(startMs) + " «" + it.take(160) + "»") }
        when (step) {
            1 -> stepWhat(c)
            2 -> stepBrief(c)
            3 -> stepWho(c)
            else -> stepCheck(c)
        }
    }

    private fun label(c: Col, s: String) = c.add(text(c.ctx, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)

    private fun <T> chips(c: Col, name: String, opts: List<Pair<T, String>>, sel: T, f: (T) -> Unit) {
        label(c, name)
        c.add(choiceChips(c.ctx, opts, sel) {
            f(it)
            refresh()
        }, 4)
    }

    private fun tri(c: Col, name: String, v: Boolean?, f: (Boolean?) -> Unit) =
        chips(c, name, listOf(0 to t("Авто", "Auto"), 1 to t("Да", "Yes"), 2 to t("Нет", "No")), when (v) {
            null -> 0
            true -> 1
            false -> 2
        }) { f(if (it == 0) null else it == 1) }

    private fun field(c: Col, hint: String, v: String, multiline: Boolean = false, f: (String) -> Unit): EditText =
        c.edit(hint, v, multiline = multiline).also { e -> e.changed { f(it.trim()); refresh() } }

    private fun slider(c: Col, name: String, v: Float, from: Float, to: Float, fmt: (Float) -> String, f: (Float) -> Unit) {
        val head = text(c.ctx, name + ": " + fmt(v), 14f)
        c.add(head, 12)
        c.add(SeekBar(c.ctx).apply {
            max = 100
            progress = (((v - from) / (to - from)) * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val x = from + (to - from) * p / 100f
                    head.text = name + ": " + fmt(x)
                    if (fromUser) f(x)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) = refresh()
            })
        }, 2)
    }

    private fun pct(v: Float) = (v * 100).roundToInt().toString() + " %"

    // ---------- 1. what ----------

    private fun stepWhat(c: Col) {
        val ctx = c.ctx
        val quick = mutableListOf<View>()
        Lumen.prefs.getString(PREF_LAST, null)?.let { EditInsert.briefFrom(it) }?.let { last ->
            quick += chip(ctx, "↺ " + t("Как в прошлый раз", "Like last time")) { choose(last.type, last) }
        }
        val lib = InsertStore.list(a)
        val layers = runCatching { LayerStore(File(WebLayerActivity.dir(a), "layers")).list() }.getOrDefault(emptyList())
        if (lib.isNotEmpty() || layers.isNotEmpty()) quick += chip(ctx, "📚 " + t("Моя библиотека", "My library")) { librarySheet() }
        val recipes = InsertStore.recipes(a)
        if (recipes.isNotEmpty()) quick += chip(ctx, "⭐ " + t("Мои рецепты", "My recipes") + " (${recipes.size})") { recipesSheet() }
        quick += chip(ctx, "🧪 " + t("Примеры без ИИ", "Samples without AI")) { examplesSheet() }
        c.chips(quick)
        label(c, t("Что показать — выберите по смыслу", "What to show — pick by meaning"))
        val types = EditInsert.GALLERY
        var row: LinearLayout? = null
        types.forEachIndexed { i, ty ->
            if (i % 2 == 0) row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }.also { c.add(it, 6) }
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                background = ripple(ctx, roundBg(if (ty.id == brief.type && blockId != null) Palette.ACCENT_SOFT else Palette.CARD, 14f, ctx), Palette.ON_SURFACE)
                minimumHeight = ctx.dp(64)
                contentDescription = t(ty.ru, ty.en)
                addView(text(ctx, ty.icon + " " + t(ty.ru, ty.en), 14f, bold = true))
                addView(text(ctx, t(ty.sampleRu, ty.sampleEn) + when (ty.kind) {
                    "scheme" -> t(" · схема", " · scheme")
                    "anim" -> t(" · анимация", " · animation")
                    else -> ""
                }, 12f, color = Palette.MUTED))
                setOnClickListener { choose(ty.id, null) }
            }
            row?.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 2 == 1) marginStart = ctx.dp(8)
            })
        }
        if (types.size % 2 == 1) row?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(8) })
    }

    /** A type chosen: the brief from what is known (speech, style of the video), or from [from] (last time, a recipe). */
    private fun choose(type: String, from: EditBrief?) {
        val d = EditInsert.defaults(type, blockWords(), brand(), fh > fw)
        brief = if (from == null) d else EditInsert.withLook(d, from).copy(
            place = from.place, size = from.size, shape = from.shape, avoid = from.avoid, enter = from.enter.ifEmpty { d.enter },
            exit = from.exit.ifEmpty { d.exit }, reveal = from.reveal.ifEmpty { d.reveal }, emphasis = from.emphasis, holdMs = from.holdMs, sfx = from.sfx,
            labels = from.labels, icons = from.icons, kind = from.kind
        )
        if (brief.face.isEmpty()) brief = brief.copy(face = face)
        kind = EditInsert.kindOf(brief)
        if (span == null && blockId == null) durMs = max(durMs, EditInsert.readMs(brief, blockWords().size))
        if (keepBox != null && blockId == null) keepBox = null
        answer = null
        fromLibrary = false
        go(2)
    }

    private fun examplesSheet(): Unit = a.bottomSheet(t("🧪 Примеры без ИИ", "🧪 Samples without AI")) { dismiss ->
        muted(t("Готовые анимации: сразу работают, берут числа и слова из брифа. Можно поправить ИИ на шаге проверки.",
            "Ready animations: work at once, take numbers and words from the brief. The AI can change them at the check step."))
        AnimExamples.ALL.forEach { ex ->
            button(EditInsert.type(ex.type).icon + " " + t(ex.ru, ex.en), style = BtnStyle.TONAL) {
                dismiss()
                choose(ex.type, null)
                kind = EditAnim.KIND
                brief = brief.copy(kind = EditAnim.KIND)
                answer = InsertAnswer.Anim(ex.code, "", t(ex.ru, ex.en), 0)
                fromLibrary = true
                render()
            }
        }
    }

    private fun librarySheet(): Unit = a.bottomSheet(t("📚 Моя библиотека", "📚 My library")) { dismiss ->
        val lib = InsertStore.list(a)
        if (lib.isEmpty()) muted(t("Здесь будут схемы и анимации, сохранённые кнопкой «💾 В библиотеку».", "Schemes and animations saved with \"💾 To the library\" appear here."))
        lib.forEach { s ->
            row(btn(ctx, (if (s.kind == "scheme") "🧩 " else "🎞 ") + s.name, style = BtnStyle.TONAL) {
                dismiss()
                val br = EditInsert.briefFrom(s.brief) ?: EditBrief()
                choose(br.type, br)
                kind = s.kind
                brief = brief.copy(kind = s.kind, what = br.what, items = br.items, fields = br.fields, title = br.title)
                answer = if (s.kind == "scheme") StudioScheme.fromJson(JSONObject(s.payload))?.let { InsertAnswer.Scheme(it, "", s.name) }
                else InsertAnswer.Anim(s.payload, "", s.name, EditInsert.loopOf(s.payload))
                fromLibrary = answer != null
                render()
            }, iconBtn(ctx, "🗑") {
                a.confirm(t("Удалить из библиотеки?", "Delete from the library?"), s.name, t("Удалить", "Delete"), danger = true) {
                    InsertStore.delete(a, s.id)
                    dismiss()
                    librarySheet()
                }
            })
        }
        val layers = runCatching { LayerStore(File(WebLayerActivity.dir(a), "layers")).list() }.getOrDefault(emptyList())
        if (layers.isNotEmpty()) {
            h2(t("🎛 Шаблоны Монтажа", "🎛 Montage templates"))
            muted(t("JS-слой на весь кадр — встанет на отрезок блока.", "A full-frame JS layer — goes on the block's span."))
            layers.forEach { l ->
                button("🎛 " + l.name, style = BtnStyle.TONAL) {
                    dismiss()
                    choose("custom", null)
                    kind = EditAnim.KIND
                    brief = brief.copy(type = "montage", kind = EditAnim.KIND, place = "full", enter = "code", exit = "code")
                    keepBox = null
                    answer = InsertAnswer.Anim(EditInsert.adaptCanvas(l.code, startMs), "", l.name, 0)
                    fromLibrary = true
                    render()
                }
            }
        }
    }

    private fun recipesSheet(): Unit = a.bottomSheet(t("⭐ Мои рецепты", "⭐ My recipes")) { dismiss ->
        muted(t("Рецепт — тип, вид, прозрачность, движение и место без содержания.", "A recipe is the type, look, transparency, motion and place without content."))
        InsertStore.recipes(a).forEach { (id, name, br) ->
            row(btn(ctx, EditInsert.type(br.type).icon + " " + name, style = BtnStyle.TONAL) {
                dismiss()
                choose(br.type, br)
            }, iconBtn(ctx, "🗑") {
                InsertStore.deleteRecipe(a, id)
                dismiss()
                recipesSheet()
            })
        }
    }

    // ---------- 2. the brief ----------

    private fun stepBrief(c: Col) {
        val ctx = c.ctx
        val ty = EditInsert.type(brief.type)
        c.add(text(ctx, ty.icon + " " + t(ty.ru, ty.en), Type.TITLE_M, bold = true), 8)
        if (ty.kind == "both" && brief.type != "montage") c.add(segBar(ctx, listOf(t("🧩 Схема", "🧩 Scheme"), t("🎞 Анимация", "🎞 Animation")), if (kind == "scheme") 0 else 1) { i ->
            kind = if (i == 0) "scheme" else EditAnim.KIND
            brief = brief.copy(kind = kind, enter = if (kind == "scheme") "" else brief.enter.ifEmpty { "code" }, exit = if (kind == "scheme") "" else brief.exit.ifEmpty { "code" })
            answer = answer?.takeIf { (it is InsertAnswer.Scheme) == (kind == "scheme") }
            render()
        }, 8)
        // the mock-up: where, how big, how see-through — before any AI
        val m = MockView(ctx)
        mock = m
        c.add(m, 8)
        val picks = mutableListOf<View>()
        picks += chip(ctx, "📍 " + t("Место пальцем", "Place by finger"), pick == "place") { pick = if (pick == "place") "" else "place"; render() }
        if (brief.type == "pointer") picks += chip(ctx, "🎯 " + t("Куда указать", "Point at"), pick == "target") { pick = if (pick == "target") "" else "target"; render() }
        picks += chip(ctx, "🙂 " + t("Отметить лицо", "Mark the face"), pick == "face") { pick = if (pick == "face") "" else "face"; render() }
        if (frameBmp != null) picks += chip(ctx, "💧 " + t("Цвет с кадра", "Colour from frame"), pick == "color") { pick = if (pick == "color") "" else "color"; render() }
        c.chips(picks)
        if (pick.isNotEmpty()) c.muted(when (pick) {
            "place" -> t("Коснитесь макета — там будет центр блока.", "Tap the mock-up — the block's centre goes there.")
            "target" -> t("Коснитесь точки, на которую укажет стрелка.", "Tap the point the arrow will point at.")
            "face" -> t("Коснитесь лица — его блок не закроет.", "Tap the face — the block will keep it clear.")
            else -> t("Коснитесь цвета на кадре — он станет акцентом.", "Tap a colour on the frame — it becomes the accent.")
        })
        sectionA(c)
        sectionB(c)
        sectionC(c)
        sectionD(c)
        sectionE(c)
        sectionF(c)
        // summary, the request, warnings
        summaryView = c.add(text(ctx, "", 13f, bold = true, color = Palette.ON_SURFACE), 16) as TextView
        warnBox = c.add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }, 4)
        c.add(rowOf(ctx, btn(ctx, "‹ " + t("Назад", "Back"), style = BtnStyle.TEXT) { go(1) },
            btn(ctx, "👁 " + t("Запрос", "Request"), style = BtnStyle.OUTLINED) { requestSheet() },
            btn(ctx, (if (fromLibrary && answer != null) t("Проверить", "Check") else t("Дальше", "Next")) + " ›", primary = true) {
                val block = warnings().firstOrNull { it.blocking }
                if (block != null) return@btn a.toast(block.text)
                remember()
                go(if (fromLibrary && answer != null) 4 else 3)
            }), 8)
        refresh()
    }

    private fun sum(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")

    private fun sectionA(c: Col) = c.section(t("А. Содержание", "A. Content"), sum(brief.what.take(40), if (brief.items.isNotEmpty()) t("данных: ", "rows: ") + brief.items.size else "",
        if (brief.onlySaid) t("только сказанное", "only what is said") else ""), "A" in opened, { toggle("A", it) }) {
        val ty = EditInsert.type(brief.type)
        field(this, t("Что показать (можно пусто — по речи)", "What to show (may be empty — by the speech)"), brief.what, multiline = true) { brief = brief.copy(what = it) }
        ty.fields.forEach { id ->
            val ch = EditInsert.fieldChoices(id)
            if (ch.isNotEmpty()) chips(this, EditInsert.fieldName(id), listOf("" to t("Авто", "Auto")) + ch, brief.fields[id].orEmpty()) { v ->
                brief = brief.copy(fields = (brief.fields + (id to v)).filterValues { it.isNotEmpty() })
            }
            else field(this, EditInsert.fieldName(id), brief.fields[id].orEmpty(), multiline = id == "pros" || id == "cons" || id == "quote") { v ->
                brief = brief.copy(fields = (brief.fields + (id to v)).filterValues { it.isNotEmpty() })
            }
        }
        if (ty.items || brief.items.isNotEmpty() || ty.id == "custom") itemsTable(this)
        chips(this, t("Источник", "Source"), listOf("" to t("Авто", "Auto"), "speech" to t("Речь", "Speech"), "data" to t("Мои данные", "My data"), "wish" to t("Описание", "Description")), brief.source) {
            brief = brief.copy(source = it)
        }
        check(t("Только сказанное — без новых фактов и чисел", "Only what is said — no new facts or numbers"), brief.onlySaid) { brief = brief.copy(onlySaid = it); refresh() }
        chips(this, t("Заголовок", "Title"), listOf("" to t("Авто", "Auto"), "-" to t("Без", "None"), "*" to t("Свой", "Own")), when (brief.title) {
            "", "-" -> brief.title
            else -> "*"
        }) { v ->
            brief = brief.copy(title = if (v == "*") brief.title.takeIf { it.length > 1 } ?: " " else v)
            render()
        }
        if (brief.title.isNotEmpty() && brief.title != "-") field(this, t("Текст заголовка", "Title text"), brief.title.trim()) { brief = brief.copy(title = it.ifEmpty { " " }) }
        chips(this, t("Элементов", "Elements"), listOf(0 to t("Авто", "Auto"), 3 to "3", 5 to "5", 7 to "7"), brief.count) { brief = brief.copy(count = it) }
        chips(this, t("Подписи", "Labels"), listOf("" to t("Авто", "Auto"), "short" to t("1–3 слова", "1–3 words"), "medium" to t("до 6 слов", "up to 6 words"), "phrase" to t("фраза", "a phrase")), brief.labels) {
            brief = brief.copy(labels = it)
        }
        chips(this, t("Иконки", "Icons"), listOf("" to t("Авто", "Auto"), "emoji" to t("Эмодзи", "Emoji"), "none" to t("Без", "None")), brief.icons) { brief = brief.copy(icons = it) }
        if (kind == "scheme") chips(this, t("Тип схемы", "Scheme type"), listOf("" to t("Авто", "Auto")) + StudioScheme.LAYOUTS.map { it to layoutName(it) }, brief.layout) {
            brief = brief.copy(layout = it)
        }
        chips(this, t("Тон", "Tone"), listOf("" to t("Авто", "Auto"), "business" to t("Деловой", "Business"), "friendly" to t("Дружелюбный", "Friendly"),
            "fun" to t("С юмором", "Fun"), "drama" to t("Драматично", "Dramatic"), "kids" to t("Для детей", "For kids")), brief.tone) { brief = brief.copy(tone = it) }
        field(this, t("Язык подписей (пусто — как озвучка: ", "Language of labels (empty — as the voice: ") + a.lang() + ")", brief.lang) { brief = brief.copy(lang = it) }
    }

    private fun layoutName(l: String) = when (l) {
        "chain" -> t("Цепочка", "Chain")
        "hub" -> t("Звезда", "Hub")
        "tree" -> t("Дерево", "Tree")
        "timeline" -> t("Лента времени", "Timeline")
        "funnel" -> t("Воронка", "Funnel")
        "pyramid" -> t("Пирамида", "Pyramid")
        "matrix" -> t("Матрица 2×2", "2×2 matrix")
        "venn" -> t("Круги Венна", "Venn")
        else -> l
    }

    /** The rows of data: label · value · unit · emoji · ★, add, from the speech, remove. */
    private fun itemsTable(c: Col) {
        val ctx = c.ctx
        label(c, t("Данные (подпись · значение · ед. · эмодзи)", "Data (label · value · unit · emoji)"))
        val box = c.add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }, 4)
        fun fill() {
            box.removeAllViews()
            brief.items.forEachIndexed { i, it0 ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun cell(hint: String, v: String, weight: Float, set: (BriefItem, String) -> BriefItem) = edt(ctx, hint, v).also { e ->
                    e.textSize = 13f
                    e.changed { s -> brief = brief.copy(items = brief.items.mapIndexed { j, x -> if (j == i) set(x, s.trim()) else x }); refresh() }
                    row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
                }
                cell(t("Подпись", "Label"), it0.label, 3f) { x, s -> x.copy(label = s) }
                cell(t("Знач.", "Value"), it0.value, 1.6f) { x, s -> x.copy(value = s) }
                cell(t("Ед.", "Unit"), it0.unit, 1.2f) { x, s -> x.copy(unit = s) }
                cell("🙂", it0.icon, 0.9f) { x, s -> x.copy(icon = s) }
                row.addView(text(ctx, if (it0.main) "★" else "☆", 20f, color = if (it0.main) Palette.ACCENT else Palette.MUTED).apply {
                    contentDescription = t("Главное", "Main")
                    setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
                    setOnClickListener {
                        brief = brief.copy(items = brief.items.mapIndexed { j, x -> x.copy(main = j == i && !x.main) })
                        fill()
                        refresh()
                    }
                })
                row.addView(text(ctx, "✕", 18f, color = Palette.MUTED).apply {
                    contentDescription = t("Удалить строку", "Delete the row")
                    setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
                    setOnClickListener {
                        brief = brief.copy(items = brief.items.filterIndexed { j, _ -> j != i })
                        fill()
                        refresh()
                    }
                })
                box.addView(row)
            }
        }
        fill()
        c.chips(listOf(
            chip(ctx, "＋ " + t("Строка", "Row")) {
                if (brief.items.size < 8) brief = brief.copy(items = brief.items + BriefItem(""))
                fill()
                refresh()
            },
            chip(ctx, "↻ " + t("Взять из речи", "Take from the speech")) {
                val got = EditInsert.dataFromSpeech(blockWords())
                if (got.isEmpty()) a.toast(t("В речи блока нет чисел и имён", "No numbers or names in the block's speech"))
                brief = brief.copy(items = (brief.items.filter { it.label.isNotBlank() || it.value.isNotBlank() } + got).distinct().take(8))
                fill()
                refresh()
            }
        ))
    }

    private fun sectionB(c: Col) = c.section(t("Б. Вид", "B. Look"), sum(
        styleName(brief.style), brief.accent?.let { String.format("#%06X", it and 0xFFFFFF) }.orEmpty(), fillName(brief.fill),
        when (brief.font) {
            "condensed" -> t("узкий", "condensed")
            "serif" -> t("с засечками", "serif")
            "mono" -> t("моно", "mono")
            "rounded" -> t("округлый", "rounded")
            else -> ""
        }, if (brief.upper == true) t("КАПС", "CAPS") else "", brief.textSize.uppercase()
    ), "B" in opened, { toggle("B", it) }) {
        val styles = SchemeStyles.list(a.styleDir).filter { it.place == "overlay" || it.place == "panel" || it.id == SchemeStyles.AUTO }
        chips(this, t("Стиль", "Style"), listOf("" to t("Стиль ролика", "Video's style")) + styles.map { it.id to styleName(it.id) }, brief.style) { brief = brief.copy(style = it) }
        val accents = listOf(0xFFFFD60A.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt(), 0xFFFFFFFF.toInt())
        label(this, t("Акцентный цвет", "Accent colour"))
        chips(listOf(chip(ctx, t("Как в ролике", "As in the video"), brief.accent == null) { brief = brief.copy(accent = null); render() }) + accents.map { col ->
            chip(ctx, "●", brief.accent == col) { brief = brief.copy(accent = col); render() }.apply {
                setTextColor(col)
                contentDescription = String.format("#%06X", col and 0xFFFFFF)
            }
        } + listOf(chip(ctx, "💧 " + t("С кадра", "From frame"), pick == "color") { pick = "color"; render() }))
        field(this, "HEX (#FFD60A)", brief.accent?.let { String.format("#%06X", it and 0xFFFFFF) }.orEmpty()) { s ->
            runCatching { Color.parseColor(if (s.startsWith("#")) s else "#$s") }.getOrNull()?.let { brief = brief.copy(accent = it or 0xFF000000.toInt()) }
        }
        if (kind == EditAnim.KIND) chips(this, t("Палитра", "Palette"), listOf("" to t("Из стиля", "From style"), "mono" to t("Монохром + акцент", "Mono + accent"),
            "bright" to t("Яркая", "Bright"), "pastel" to t("Пастель", "Pastel")), brief.palette) { brief = brief.copy(palette = it) }
        plateControls(this)
        chips(this, t("Скругление", "Rounding"), listOf("" to t("Авто", "Auto"), "none" to t("Нет", "None"), "s" to "S", "m" to "M", "l" to "L"), brief.radius) { brief = brief.copy(radius = it) }
        tri(this, t("Обводка", "Outline"), brief.border) { brief = brief.copy(border = it) }
        tri(this, t("Тень", "Shadow"), brief.shadow) { brief = brief.copy(shadow = it) }
        chips(this, t("Шрифт", "Font"), listOf("" to t("Авто", "Auto"), "sans" to t("Обычный", "Plain"), "condensed" to t("Узкий", "Condensed"), "serif" to t("С засечками", "Serif"),
            "mono" to t("Моно", "Mono"), "rounded" to t("Округлый", "Rounded")), brief.font) { brief = brief.copy(font = it) }
        tri(this, t("КАПС", "ALL CAPS"), brief.upper) { brief = brief.copy(upper = it) }
        chips(this, t("Размер текста", "Text size"), listOf("" to t("Авто", "Auto"), "s" to "S", "m" to "M", "l" to "L"), brief.textSize) { brief = brief.copy(textSize = it) }
        muted(t("Не мельче ", "Not smaller than ") + EditInsert.minFontPx(fw, fh, brief.textSize) + t(" px в кадре ", " px in a frame of ") + "$fw×$fh")
        field(this, t("Похоже на… (стиль словами)", "Looks like… (style in words)"), brief.like) { brief = brief.copy(like = it) }
        val refBtns = mutableListOf<View>(btn(ctx, if (brief.refImage.isEmpty()) "🖼 " + t("Образец-картинка", "Sample picture") else "🖼 " + t("Образец выбран", "Sample chosen"),
            style = BtnStyle.OUTLINED) { a.pickRefImage() })
        if (brief.refImage.isNotEmpty()) refBtns += btn(ctx, "✕", style = BtnStyle.TEXT) { brief = brief.copy(refImage = ""); render() }
        row(*refBtns.toTypedArray())
        label(this, t("Не делать", "Do not"))
        chips(listOf("emoji" to t("без эмодзи", "no emoji"), "arrows" to t("без стрелок", "no arrows"), "colors2" to t("≤ 2 цветов", "≤ 2 colours"),
            "gradients" to t("без градиентов", "no gradients"), "shadows" to t("без теней", "no shadows")).map { (id, n) ->
            chip(ctx, n, id in brief.avoidStyle) {
                brief = brief.copy(avoidStyle = if (id in brief.avoidStyle) brief.avoidStyle - id else brief.avoidStyle + id)
                render()
            }
        })
        field(this, t("Ещё запреты своими словами", "More don'ts in your words"), brief.dont) { brief = brief.copy(dont = it) }
    }

    private fun plateControls(c: Col) {
        chips(c, t("Подложка", "Plate"), listOf("" to t("По стилю", "By style"), "none" to t("Нет", "None"), "glass" to t("Стекло", "Glass"), "semi" to t("Полупрозрачная", "Semi"),
            "solid" to t("Плотная", "Solid"), "full" to t("Перебивка", "Cutaway")), brief.fill) { v ->
            brief = brief.copy(fill = v, bgAlpha = -1f, place = if (v == "full") "cover" else brief.place.takeIf { it != "cover" } ?: "")
            if (v == "full") keepBox = null
            render()
        }
        if (brief.fill.isNotEmpty() && brief.fill != "none" && brief.fill != "full") {
            val cur = if (brief.bgAlpha >= 0f) brief.bgAlpha else EditInsert.defaultAlpha(brief.fill)
            slider(c, t("Прозрачность подложки — плотность", "Plate density"), cur, 0f, 1f, ::pct) { brief = brief.copy(bgAlpha = it); mock?.invalidate() }
        }
    }

    private fun styleName(id: String): String = when {
        id.isEmpty() -> ""
        id == SchemeStyles.AUTO -> t("Авто (светлая)", "Auto (light)")
        else -> SchemeStyles.find(a.styleDir, id)?.name ?: id
    }

    private fun fillName(f: String) = when (f) {
        "none" -> t("без подложки", "no plate")
        "glass" -> t("стекло", "glass")
        "semi" -> t("полупрозрачная", "semi")
        "solid" -> t("плотная", "solid")
        "full" -> t("перебивка", "cutaway")
        else -> ""
    }

    private fun sectionC(c: Col) = c.section(t("В. Прозрачность и видео под блоком", "C. Transparency and the video under the block"), sum(
        "◐ " + pct(brief.opacity), fillName(brief.fill) + if (brief.bgAlpha >= 0f) " " + pct(brief.bgAlpha) else "",
        if (brief.dim > 0.01f) t("затемнение ", "darkened ") + pct(brief.dim) + if (brief.dimAll) t(" (весь кадр)", " (whole frame)") else "" else ""
    ), "C" in opened, { toggle("C", it) }) {
        slider(this, t("Прозрачность блока (весь блок)", "Block opacity (the whole block)"), brief.opacity, 0.1f, 1f, ::pct) { brief = brief.copy(opacity = it); mock?.invalidate() }
        plateControls(this)
        chips(this, t("Затемнить видео", "Darken the video"), listOf(0f to "0", 0.2f to "20 %", 0.4f to "40 %", 0.6f to "60 %"),
            listOf(0f, 0.2f, 0.4f, 0.6f).minByOrNull { kotlin.math.abs(it - brief.dim) } ?: 0f) { brief = brief.copy(dim = it) }
        chips(this, t("Где затемнить", "Where"), listOf(false to t("Под блоком", "Under the block"), true to t("Весь кадр", "Whole frame")), brief.dimAll) { brief = brief.copy(dimAll = it) }
        muted(t("Затемнение плавно появляется и уходит вместе с блоком. Lumen проверит, читается ли текст на этом кадре.",
            "The darkening fades in and out with the block. Lumen checks whether the text reads on this frame."))
    }

    private fun sectionD(c: Col) = c.section(t("Г. Движение и время", "D. Motion and time"), sum(
        String.format("%.1f", durMs / 1000.0) + t(" с", " s"), when (brief.reveal) {
            "words" -> t("на словах", "on words")
            "steps" -> t("по очереди", "one by one")
            "all" -> t("сразу", "at once")
            else -> ""
        }, when (brief.pace) {
            "calm" -> t("спокойно", "calm")
            "drive" -> t("драйв", "drive")
            "normal" -> t("обычно", "normal")
            else -> ""
        }
    ), "D" in opened, { toggle("D", it) }) {
        val need = EditInsert.readMs(brief, blockWords().size)
        muted(t("Начало ", "Starts at ") + StudioEdit.clock(startMs) + t(" · для чтения нужно ≈ ", " · reading needs ≈ ") + String.format("%.1f", need / 1000.0) + t(" с", " s"))
        val lens = listOfNotNull(span?.let { StudioEdit.spanOf(a.words, it, a.outLen(p0)).second }, 3000L, 5000L, 8000L, 12_000L, need).distinct().sorted()
        chips(this, t("Длина", "Length"), lens.map { it to String.format("%.1f", it / 1000.0) + t(" с", " s") + if (it == need) " ✓" else "" },
            lens.minByOrNull { kotlin.math.abs(it - durMs) } ?: durMs) { durMs = it.coerceAtMost(a.outLen(p0) - startMs).coerceAtLeast(StudioEdit.MIN_MS) }
        val ins = listOf("none" to t("Нет", "None"), "fade" to t("Плавно", "Fade"), "pop" to t("Пружина", "Pop"), "slide" to t("Снизу", "Slide"), "zoom" to t("Наезд", "Zoom"))
        val code = if (kind == EditAnim.KIND) listOf("code" to t("В коде ИИ", "In the AI's code")) else emptyList()
        chips(this, t("Появление блока", "Block entrance"), listOf("" to t("Авто", "Auto")) + code + ins, brief.enter) { brief = brief.copy(enter = it) }
        chips(this, t("Раскрытие элементов", "Elements appear"), listOf("" to t("Авто", "Auto"), "words" to t("На словах речи", "On the spoken words"), "steps" to t("По очереди", "One by one"),
            "all" to t("Всё сразу", "All at once")), brief.reveal) { brief = brief.copy(reveal = it) }
        chips(this, t("Темп", "Pace"), listOf("" to t("Авто", "Auto"), "calm" to t("Спокойно", "Calm"), "normal" to t("Обычно", "Normal"), "drive" to t("Драйв", "Drive")), brief.pace) {
            brief = brief.copy(pace = it)
        }
        check(t("Подсветить главное на его слове", "Stress the main thing on its word"), brief.emphasis) { brief = brief.copy(emphasis = it); refresh() }
        chips(this, t("Держать финал", "Hold the end"), listOf(-1L to t("Авто", "Auto"), 0L to "0", 1000L to "1 с", 2000L to "2 с", 3000L to "3 с"), brief.holdMs) { brief = brief.copy(holdMs = it) }
        chips(this, t("Исчезновение", "Exit"), listOf("" to t("Авто", "Auto")) + code + ins, brief.exit) { brief = brief.copy(exit = it) }
        if (kind == EditAnim.KIND) chips(this, t("Повтор", "Loop"), listOf(0L to t("Нет", "No"), 1000L to "1 с", 2000L to "2 с", 3000L to "3 с"), brief.loopMs) { brief = brief.copy(loopMs = it) }
        chips(this, t("Звук", "Sound"), listOf("" to t("Нет", "None"), "soft" to t("Щелчки на появлении", "Clicks as things appear"), "whoosh" to t("Свист на входе", "A whoosh on entry")), brief.sfx) {
            brief = brief.copy(sfx = it)
        }
    }

    private fun sectionE(c: Col) = c.section(t("Д. Место и размер", "E. Place and size"), sum(EditInsert.placeName(brief.place), brief.size.uppercase(),
        if ("face" in brief.avoid && brief.face.isNotEmpty()) t("лицо не закрывать", "face kept clear") else ""), "E" in opened, { toggle("E", it) }) {
        chips(this, t("Место", "Place"), listOf("" to t("Авто", "Auto"), "full" to t("Весь кадр", "Full frame"), "cover" to t("Перебивка", "Cutaway"), "top" to t("Сверху", "Top"),
            "center" to t("Центр", "Centre"), "bottom" to t("Снизу", "Bottom"), "corner" to t("Угол", "Corner"), "custom" to "📍 " + t("Своё", "Own")), brief.place) { v ->
            brief = brief.copy(place = v, fill = if (v == "cover") "full" else brief.fill)
            keepBox = null
            if (v == "custom") pick = "place"
            render()
        }
        chips(this, t("Размер", "Size"), listOf("" to t("Авто", "Auto"), "s" to "S", "m" to "M", "l" to "L", "xl" to "XL"), brief.size) { brief = brief.copy(size = it); keepBox = null }
        chips(this, t("Форма", "Shape"), listOf("" to t("Авто", "Auto"), "wide" to t("Широкая", "Wide"), "square" to t("Квадрат", "Square"), "tall" to t("Высокая", "Tall")), brief.shape) {
            brief = brief.copy(shape = it)
            keepBox = null
        }
        label(this, t("Не закрывать", "Keep clear"))
        chips(listOf("face" to t("Лицо", "The face") + if (brief.face.isEmpty()) t(" (не найдено)", " (not found)") else "", "subs" to t("Субтитры", "Subtitles"),
            "shorts" to t("Интерфейс Shorts", "Shorts UI"), "blocks" to t("Другие блоки", "Other blocks")).map { (id, n) ->
            chip(ctx, n, id in brief.avoid) {
                brief = brief.copy(avoid = if (id in brief.avoid) brief.avoid - id else brief.avoid + id)
                keepBox = null
                render()
            }
        })
        chips(this, t("Слой", "Layer"), listOf("" to t("Поверх всего", "On top"), "under" to t("Под надписями", "Under captions")), brief.layer) { brief = brief.copy(layer = it) }
    }

    private fun sectionF(c: Col) = c.section(t("Е. Стиль ролика и рецепты", "F. Video style and recipes"), if (brand() != null) t("стиль ролика задан", "the video style is set") else "",
        "F" in opened, { toggle("F", it) }) {
        muted(t("Стиль ролика — вид, прозрачность и темп для всех новых блоков этого проекта.", "The video style — look, transparency and pace for every new block of this project."))
        button("📌 " + t("Запомнить как стиль ролика", "Keep as the video style"), style = BtnStyle.TONAL) {
            val p = a.project ?: return@button
            a.project = p.copy(brand = EditInsert.briefJson(EditInsert.withLook(EditBrief(), brief)).toString())
            a.save()
            a.toast(t("Стиль ролика запомнен", "The video style is kept"))
            render()
        }
        val same = a.blocks.count { it.brief.isNotEmpty() }
        if (same > 0) button("🎨 " + t("Применить вид ко всем блокам помощника ($same)", "Apply the look to all wizard blocks ($same)"), style = BtnStyle.TEXT) {
            val look = EditInsert.lookOf(brief)
            a.commit(a.blocks.map { b ->
                if (b.brief.isEmpty()) b else {
                    val br = EditInsert.withLook(EditInsert.briefFrom(b.brief) ?: EditBrief(), brief)
                    b.copy(brief = EditInsert.briefJson(br).toString(), over = if (b.kind == "scheme") look else b.over, opacity = brief.opacity, dim = brief.dim, dimAll = brief.dimAll,
                        look = if (b.kind == "scheme" && brief.style.isNotEmpty()) brief.style else b.look)
                }
            })
            a.stage?.refresh()
            a.anims.checkLater()
            a.toast(t("Вид применён", "The look is applied"))
        }
        button("⭐ " + t("Сохранить как рецепт", "Save as a recipe"), style = BtnStyle.TEXT) {
            val e = edt(a, t("Название рецепта", "Recipe name"), EditInsert.type(brief.type).let { t(it.ru, it.en) })
            a.bottomSheet(t("⭐ Рецепт", "⭐ Recipe")) { d ->
                add(e)
                button(t("Сохранить", "Save"), primary = true) {
                    InsertStore.addRecipe(a, e.text.toString(), brief)
                    d()
                    a.toast(t("Рецепт сохранён", "Recipe saved"))
                }
            }
        }
    }

    private fun toggle(id: String, open: Boolean) {
        if (open) opened += id else opened -= id
        render()
    }

    /** Mock-up, summary and warnings follow every change of the brief. */
    private fun refresh() {
        lum()
        mock?.invalidate()
        summaryView?.text = EditInsert.summary(brief, durMs)
        val box = warnBox ?: return
        box.removeAllViews()
        for (w in warnings()) {
            val row = LinearLayout(box.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg(if (w.blocking) 0x22B3261E else 0x22B26A00, 10f, box.context)
                setPadding(box.context.dp(10), box.context.dp(4), box.context.dp(4), box.context.dp(4))
            }
            row.addView(text(box.context, "⚠ " + w.text, 13f, color = if (w.blocking) Palette.BAD else Palette.WARN), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn(box.context, w.fixLabel, style = BtnStyle.TEXT) { fix(w) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = box.context.dp(4) })
        }
    }

    private fun warnings(): List<InsertWarn> = EditInsert.warnings(brief, kind, ctxNow(), blockWords().size, videoLum)

    private fun fix(w: InsertWarn) {
        when (w.fix) {
            "longer" -> durMs = EditInsert.readMs(brief, blockWords().size).coerceAtMost(a.outLen(p0) - startMs)
            "fewer" -> brief = brief.copy(count = 5, items = brief.items.take(5))
            "opaque" -> brief = brief.copy(opacity = 1f)
            "denser" -> brief = brief.copy(fill = brief.fill.takeIf { it == "solid" } ?: "semi", bgAlpha = 0.85f)
            "dim" -> brief = brief.copy(dim = 0.4f)
            "move" -> {
                keepBox = null
                brief = brief.copy(place = if (brief.place == "bottom") "top" else if (brief.place == "top") "center" else "top", pos = emptyList())
            }
            "bigger" -> brief = brief.copy(textSize = "l")
            "describe" -> opened += "A"
        }
        render()
    }

    private fun remember() = Lumen.prefs.edit().putString(PREF_LAST, EditInsert.briefJson(brief).toString()).apply()

    fun refPicked(uri: Uri) {
        val d = a.dir
        Lumen.async({ a.saveImage(uri, d) }, { e -> if (!a.isDestroyed) a.err(e) }) { (name, _) ->
            brief = brief.copy(refImage = name)
            if (col != null) render()
        }
    }

    private fun requestText(): String = EditInsert.request(kind, brief, ctxNow(), req.ifEmpty { "XXXX" })

    private fun requestSheet(): Unit = a.bottomSheet(t("👁 Запрос для ИИ", "👁 The request for the AI")) { _ ->
        muted(t("Ровно этот текст получит встроенный ИИ; «Другой ИИ» копирует его с оформлением lumen.relay.", "The built-in AI gets exactly this text; \"Another AI\" copies it wrapped as lumen.relay."))
        val s = requestText()
        add(text(ctx, s, 12f).apply { setTextIsSelectable(true) }, 8)
        button("⧉ " + t("Копировать", "Copy"), style = BtnStyle.OUTLINED) {
            EditAnims.copy(a, s)
            a.toast(t("Скопировано", "Copied"))
        }
    }

    // ---------- 3. who makes it ----------

    private fun stepWho(c: Col) {
        val ctx = c.ctx
        if (req.isEmpty()) req = EditInsert.reqId(System.currentTimeMillis(), ++seq)
        c.add(text(ctx, EditInsert.summary(brief, durMs), 13f, bold = true), 8)
        if (working) {
            c.working(t("ИИ делает ", "The AI makes ") + (if (kind == "scheme") t("схему…", "the scheme…") else t("анимацию…", "the animation…")))
            c.muted(t("Можно закрыть помощник — блок уже стоит на таймлайне и заполнится сам.", "You can close the wizard — the block is on the timeline and fills in by itself."))
            return
        }
        val prov = Provider.active()
        val manual = Relay.routes(if (kind == "scheme") "studio_edit_scheme" else "studio_edit_anim")
        c.add(btn(ctx, "🤖 " + t("Встроенный ИИ", "Built-in AI") + (prov?.let { " · " + it.title } ?: ""), primary = who != "other") {
            if (prov == null && Relay.mode == "off") return@btn a.toast(t("ИИ не подключён: Настройки → AI. Или «Другой ИИ».", "No AI connected: Settings → AI. Or \"Another AI\"."))
            runBuiltIn(emptyList(), "")
        }, 12)
        c.muted(when {
            manual -> t("Сейчас включён «Другой ИИ вручную» (настройки AI): запрос откроется для копирования.", "\"Another AI by hand\" is on (AI settings): the request opens for copying.")
            prov == null -> t("Встроенный ИИ не настроен — Настройки → AI.", "The built-in AI is not set — Settings → AI.")
            else -> t("Lumen отправит бриф, проверит ответ и сам исправит ошибки (до 2 кругов).", "Lumen sends the brief, checks the answer and fixes errors itself (up to 2 rounds).")
        })
        c.add(btn(ctx, "⧉ " + t("Другой ИИ — копировать запрос", "Another AI — copy the request"), style = if (who == "other") BtnStyle.FILLED else BtnStyle.OUTLINED) { other() }, 16)
        c.muted(t("Gemini, ChatGPT, Claude… Запрос с меткой #$req — блок «ждёт ответ ИИ» встанет на таймлайн; вернитесь в Lumen со скопированным ответом — он подхватится сам.",
            "Gemini, ChatGPT, Claude… The request has the mark #$req — a block \"waiting for the AI\" goes on the timeline; come back to Lumen with the answer copied — it is picked up by itself."))
        if (who == "other") {
            c.row(btn(ctx, "Gemini", style = BtnStyle.OUTLINED) { openChat("https://gemini.google.com/app") },
                btn(ctx, "ChatGPT", style = BtnStyle.OUTLINED) { openChat("https://chatgpt.com/") },
                btn(ctx, "Claude", style = BtnStyle.OUTLINED) { openChat("https://claude.ai/new") })
            c.button("↗ " + t("Поделиться запросом", "Share the request"), style = BtnStyle.TEXT) { share() }
            val e = c.edit(t("Вставьте ответ ИИ сюда", "Paste the AI's answer here"), multiline = true)
            c.row(btn(ctx, "📥 " + t("Из буфера", "From the clipboard"), style = BtnStyle.TONAL) {
                val s = EditAnims.clipText(a)
                if (s == null) return@btn a.toast(t("Буфер пуст", "The clipboard is empty"))
                e.setText(s)
                pasted(s)
            }, btn(ctx, t("Проверить ответ", "Check the answer"), primary = true) { pasted(e.text.toString()) })
        }
        c.button("‹ " + t("Назад к брифу", "Back to the brief"), style = BtnStyle.TEXT) { go(2) }
    }

    private fun pasted(s: String) {
        val ans = EditInsert.detect(s) ?: return a.toast(t("Не нашёл в ответе ни схемы (JSON), ни кода анимации (function frame)", "Found neither a scheme (JSON) nor animation code (function frame) in the answer"))
        if (ans.req.isNotEmpty() && ans.req != req) a.toast(t("Это ответ на другой запрос (#${ans.req}) — беру всё равно", "This answers another request (#${ans.req}) — taking it anyway"))
        takeAnswer(ans)
    }

    private fun openChat(url: String) = runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { a.toast(t("Нет браузера", "No browser")) }

    private fun relayText(): String = RelayText.request(if (kind == "scheme") "studio_edit_scheme" else "studio_edit_anim", EditInsert.role(kind), requestText(),
        kind == "scheme", null, brief.refImage.isNotEmpty(), "")

    private fun share() {
        val s = relayText()
        val ref = brief.refImage.takeIf { it.isNotEmpty() }?.let { File(a.dir, it) }?.takeIf { it.exists() }
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, s).putExtra(Intent.EXTRA_SUBJECT, "Lumen → AI #$req")
        if (ref != null) {
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.provider", ref)
            i.setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else i.setType("text/plain")
        runCatching { a.startActivity(Intent.createChooser(i, t("Отправить запрос в ИИ", "Send the request to an AI"))) }
    }

    /** The request goes out by hand: copied, a block waits for the answer with its mark. */
    private fun other() {
        who = "other"
        remember()
        ensurePending(req)
        EditAnims.copy(a, relayText())
        a.toast(t("Запрос #$req скопирован — вставьте его в ИИ", "Request #$req copied — paste it into an AI"))
        render()
    }

    /** The block on the timeline that waits for the AI ([mark] for another AI); made once, kept when redoing. */
    private fun ensurePending(mark: String) {
        val p = a.project ?: return
        val old = blockId?.let { StudioEdit.find(p.blocks, it) }
        val b0 = old ?: a.newBlock(p, p.blocks, kind, startMs, durMs, box().ratio) { it }
        val bx = box()
        val waiting = b0.copy(kind = kind, startMs = startMs, durMs = durMs, x = bx.x, y = bx.y, w = bx.w, ratio = bx.ratio, req = if (old?.pending == false) old.req else mark,
            brief = EditInsert.briefJson(brief).toString(), ask = brief.what, opacity = brief.opacity, dim = brief.dim, dimAll = brief.dimAll,
            scheme = if (old?.pending == false) old.scheme else null, file = if (old?.pending == false) old.file else "")
        blockId = waiting.id
        if (old == null) a.commit(p.blocks + waiting, waiting.id)
        else if (old.pending) a.commit(StudioEdit.replace(p.blocks, waiting), waiting.id)
        else if (old.req != mark && mark.isNotEmpty()) a.commit(StudioEdit.replace(p.blocks, old.copy(req = mark)), old.id)
        a.stage?.refresh()
    }

    private fun refB64(): String? {
        val f = brief.refImage.takeIf { it.isNotEmpty() }?.let { File(a.dir, it) }?.takeIf { it.exists() } ?: return null
        return runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Problems found in an answer without running it. */
    private fun problemsOf(ans: InsertAnswer): List<String> = when (ans) {
        is InsertAnswer.Scheme -> StudioScheme.problems(ans.scheme, StudioEdit.wordRange(a.words, startMs, startMs + durMs).takeIf { !it.isEmpty() })
        is InsertAnswer.Anim -> EditInsert.animProblems(ans.code)
    }

    /** The built-in AI: the request, one more round when the answer has problems, a correction or errors of the check ([fix]). */
    private fun runBuiltIn(fix: List<String>, corr: String) {
        if (BgTasks.has(StudioEditorActivity.TASK_AI)) return a.toast(t("ИИ уже работает — дождитесь или остановите", "The AI is already working — wait or stop it"))
        val prov = runCatching { AiTasks.requireProvider() }.getOrElse { return a.err(it) }
        who = "built-in"
        remember()
        val k = kind
        val br = brief
        val ctx = ctxNow()
        val mark = req.ifEmpty { EditInsert.reqId(System.currentTimeMillis(), ++seq) }
        req = mark
        val prev = payload(answer)
        ensurePending("")
        val id = blockId
        val task = if (k == "scheme") "studio_edit_scheme" else "studio_edit_anim"
        val prompt = if (fix.isEmpty() && corr.isBlank()) EditInsert.request(k, br, ctx, mark) else EditInsert.fixRequest(k, fix, corr, prev, br, ctx, mark)
        val img = refB64()
        working = true
        step = 3
        render()
        BgTasks.run(a, StudioEditorActivity.TASK_AI, t("Редактор: ИИ делает блок", "Editor: the AI makes a block"), BgTasks.reopen(a), { _ ->
            val r = Llm.complete(prov, prov.smartModel, EditInsert.role(k), prompt, json = k == "scheme", maxTokens = if (k == "scheme") 2200 else 9000,
                task = task, pid = "studio", image = img)
            var ans = EditInsert.detect(r.text) ?: throw LlmException(t("ИИ ответил не по протоколу: нет ни схемы, ни кода", "The AI did not follow the protocol: neither a scheme nor code"), r.text)
            val pr = problemsOf(ans)
            if (pr.isNotEmpty()) {
                BgTasks.progress(StudioEditorActivity.TASK_AI, t("Исправляю: ", "Fixing: ") + pr.first().take(60))
                runCatching {
                    val r2 = Llm.complete(prov, prov.smartModel, EditInsert.role(k), EditInsert.fixRequest(k, pr, "", payload(ans), br, ctx, mark), json = k == "scheme",
                        maxTokens = if (k == "scheme") 2200 else 9000, task = task + "_fix", pid = "studio")
                    EditInsert.detect(r2.text)?.let { ans = it }
                }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
            }
            ans
        }, { e ->
            working = false
            // the placeholder goes away when nothing was there before
            if (!a.isDestroyed && id != null) StudioEdit.find(a.blocks, id)?.takeIf { it.pending && it.req.isEmpty() }?.let {
                a.setBlocks(StudioEdit.remove(a.blocks, it.id))
                a.save()
                blockId = null
            }
            if (col != null) render()
            if (!a.isDestroyed) a.err(e)
        }) { ans ->
            working = false
            takeAnswer(ans)
        }
    }

    /** An answer arrived: the check on screen, or straight into the block when the wizard was closed. */
    fun takeAnswer(ans: InsertAnswer) {
        answer = ans
        kind = if (ans is InsertAnswer.Scheme) "scheme" else EditAnim.KIND
        shots = emptyList()
        issues = emptyList()
        if (col != null) go(4) else if (!a.isDestroyed) {
            apply(ans, quiet = true)
            a.toast(t("Блок от ИИ готов: ", "The AI block is ready: ") + ans.name.ifBlank { if (kind == "scheme") t("схема", "scheme") else t("анимация", "animation") })
        }
    }

    // ---------- 4. the check ----------

    private fun stepCheck(c: Col) {
        val ctx = c.ctx
        val ans = answer
        if (ans == null) {
            c.muted(t("Ответа пока нет.", "No answer yet."))
            c.button("‹ " + t("Назад", "Back"), style = BtnStyle.TEXT) { go(3) }
            return
        }
        c.add(text(ctx, (if (ans is InsertAnswer.Scheme) "🧩 " else "🎞 ") + ans.name.ifBlank { t("Без названия", "Untitled") }, Type.TITLE_M, bold = true), 8)
        if (checking) {
            c.working(t("Проверяю: рисую кадры…", "Checking: drawing frames…"))
            if (ans is InsertAnswer.Anim) {
                val wb = FrameLayout(ctx).apply { background = roundBg(0xFF202024.toInt(), 8f, ctx) }
                c.add(wb, 4)
                a.anims.host.attach(wb, ctx.dp(90), ctx.dp(90))
            }
        } else if (shots.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            shots.forEach { b -> row.addView(ImageView(ctx).apply { setImageBitmap(b); adjustViewBounds = true },
                LinearLayout.LayoutParams(ctx.dp(if (fh > fw) 96 else 150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) }) }
            c.add(HorizontalScrollView(ctx).apply { addView(row) }, 8)
            if (slowMs > 0) c.muted(t("Самый долгий кадр: $slowMs мс", "Slowest frame: $slowMs ms") + if (slowMs > 40) t(" — рендер будет долгим", " — the render will be slow") else "")
        }
        val all = (issues + problemsOf(ans)).distinct()
        all.forEach { c.add(text(ctx, "⚠ $it", 13f, color = Palette.BAD), 4) }
        // changes without the AI
        label(c, t("Правки без ИИ", "Changes without AI"))
        slider(c, t("Прозрачность блока", "Block opacity"), brief.opacity, 0.1f, 1f, ::pct) { brief = brief.copy(opacity = it) }
        plateControls(c)
        chips(c, t("Затемнить видео", "Darken the video"), listOf(0f to "0", 0.2f to "20 %", 0.4f to "40 %", 0.6f to "60 %"),
            listOf(0f, 0.2f, 0.4f, 0.6f).minByOrNull { kotlin.math.abs(it - brief.dim) } ?: 0f) { brief = brief.copy(dim = it) }
        chips(c, t("Место", "Place"), listOf("" to t("Авто", "Auto"), "top" to t("Сверху", "Top"), "center" to t("Центр", "Centre"), "bottom" to t("Снизу", "Bottom"),
            "full" to t("Весь кадр", "Full")), brief.place) { brief = brief.copy(place = it); keepBox = null }
        chips(c, t("Размер", "Size"), listOf("" to t("Авто", "Auto"), "s" to "S", "m" to "M", "l" to "L", "xl" to "XL"), brief.size) { brief = brief.copy(size = it); keepBox = null }
        if (ans is InsertAnswer.Scheme) {
            chips(c, t("Размер текста", "Text size"), listOf("" to "M", "s" to "S", "l" to "L"), brief.textSize) { brief = brief.copy(textSize = it) }
            tri(c, t("КАПС", "ALL CAPS"), brief.upper) { brief = brief.copy(upper = it) }
        }
        // changes through the AI
        label(c, t("Правка через ИИ", "A change through the AI"))
        c.chips(EditInsert.CORRECTIONS.map { (id, pair) ->
            chip(c.ctx, pair.first, correction.contains(pair.second)) {
                correction = if (correction.contains(pair.second)) correction.replace(pair.second, "").replace(Regex(";\\s*;"), ";").trim(' ', ';')
                else listOf(correction, pair.second).filter { it.isNotBlank() }.joinToString("; ")
                render()
            }.apply { tag = id }
        })
        val own = c.edit(t("Своя правка (необязательно)", "Your own correction (optional)"), "", multiline = true)
        c.row(btn(ctx, "🤖 " + t("Переделать", "Redo"), style = BtnStyle.TONAL) {
            val corr = listOf(correction, own.text.toString().trim()).filter { it.isNotBlank() }.joinToString("; ")
            if (corr.isBlank() && all.isEmpty()) return@btn a.toast(t("Выберите правку или опишите её", "Pick a correction or describe it"))
            runBuiltIn(all, corr)
        }, btn(ctx, "⧉ " + t("Запрос правки", "Correction request"), style = BtnStyle.OUTLINED) {
            val corr = listOf(correction, own.text.toString().trim()).filter { it.isNotBlank() }.joinToString("; ")
            if (req.isEmpty()) req = EditInsert.reqId(System.currentTimeMillis(), ++seq)
            ensurePending(req)
            val s = RelayText.request(if (kind == "scheme") "studio_scheme_fix" else "studio_edit_anim_fix", EditInsert.role(kind),
                EditInsert.fixRequest(kind, all, corr, payload(ans), brief, ctxNow(), req), kind == "scheme", null, false, "")
            EditAnims.copy(a, s)
            who = "other"
            a.toast(t("Запрос правки #$req скопирован", "Correction request #$req copied"))
            go(3)
        })
        val tools = mutableListOf(
            chip(ctx, "👁 " + t("Проверить ещё раз", "Check again")) { check() },
            chip(ctx, "💾 " + t("В библиотеку", "To the library")) {
                InsertStore.add(a, kind, ans.name.ifBlank { EditInsert.type(brief.type).let { t(it.ru, it.en) } }, payload(ans), EditInsert.briefJson(brief).toString(), "wizard")
                a.toast(t("Сохранено в библиотеку", "Saved to the library"))
            }
        )
        if (ans is InsertAnswer.Anim) tools += chip(ctx, "✎ " + t("Код", "Code")) { codeSheet(ans) }
        val old = blockId?.let { StudioEdit.find(a.blocks, it) }
        if (old != null && old.past.isNotEmpty()) tools += chip(ctx, "← " + t("Прошлая версия", "Previous version")) { previous(old) }
        c.chips(tools)
        c.row(btn(ctx, "‹ " + t("Бриф", "Brief"), style = BtnStyle.TEXT) { go(2) },
            btn(ctx, "✅ " + t("Вставить", "Insert"), primary = true) {
                apply(ans, quiet = false)
                close?.invoke()
            })
    }

    private fun previous(b: EBlock) {
        val prev = b.past.first()
        answer = if (prev.trimStart().startsWith("{")) StudioScheme.fromJson(JSONObject(prev))?.let { InsertAnswer.Scheme(it, "", it.title) }
        else a.anims.codeOf(EditAnim.codeDirOf(prev))?.let { InsertAnswer.Anim(it, "", b.text, b.loopMs) }
        shots = emptyList()
        go(4)
    }

    private fun codeSheet(ans: InsertAnswer.Anim): Unit = a.bottomSheet(t("✎ Код анимации", "✎ Animation code")) { d ->
        muted(t("JavaScript по протоколу lumen.anim v1. После правки — проверка заново.", "JavaScript by lumen.anim v1. After a change it is checked again."))
        val e = edit(t("Код", "Code"), ans.code, multiline = true).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row(btn(ctx, "⧉ " + t("Копировать", "Copy"), style = BtnStyle.OUTLINED) { EditAnims.copy(a, e.text.toString()) },
            btn(ctx, t("Сохранить", "Save"), primary = true) {
                val code = e.text.toString().trim()
                d()
                answer = InsertAnswer.Anim(code, "", EditInsert.nameOf(code, ans.name), EditInsert.loopOf(code))
                shots = emptyList()
                go(4)
            })
    }

    /** Frames of the answer over the frame of the video: a scheme by the painter, an animation in the WebView. */
    private fun check() {
        val ans = answer ?: return
        val p = a.project ?: return
        val base = frameBmp
        val bx = box()
        val times = listOf(0.12, 0.4, 0.68, 0.92).map { durMs * it }
        val pw = 360
        val ph = (pw * fh / fw.toFloat()).roundToInt()
        fun canvasOf(): Pair<Bitmap, Canvas> {
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (base != null) c.drawBitmap(base, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) else c.drawColor(0xFF33363D.toInt())
            return out to c
        }
        when (ans) {
            is InsertAnswer.Scheme -> {
                val tmp = blockFor(ans, bx)
                val painter = StudioEditPainter(pw, ph, a.dir, a.words, StudioPrefs.accent, a.styleDir)
                shots = times.map { ms ->
                    val (out, c) = canvasOf()
                    painter.draw(c, listOf(tmp), startMs + ms.toLong())
                    out
                }
                painter.close()
                slowMs = 0
                render()
            }
            is InsertAnswer.Anim -> {
                if (a.anims.running != null) return a.toast(t("Сейчас рисуется другая анимация — проверка после неё", "Another animation is being rendered — check after it"))
                checking = true
                a.anims.shooting = true
                render()
                val tmp = blockFor(ans, bx)
                val (rw, rh) = EditAnim.renderSize(tmp, fw, fh)
                val k = min(1f, 480f / max(rw, rh))
                val sw = max(16, (rw * k).toInt())
                val sh = max(16, (rh * k).toInt())
                val data = if (brief.type == "montage") a.anims.data(p, tmp, sw, sh) else EditInsert.animData(brief, ctxNow(), sw, sh, EditInsert.FPS)
                val secs = times.map { it / 1000.0 }
                Lumen.async({ AnimRender.shots(a.anims.host, ans.code, data, secs) }, { e ->
                    checking = false
                    a.anims.shooting = false
                    issues = listOf((e as? CanvasHost.LayerError)?.message ?: e.message.orEmpty())
                    shots = emptyList()
                    if (col != null) render()
                }) { (list, slow) ->
                    checking = false
                    a.anims.shooting = false
                    slowMs = slow
                    issues = if (list.all { it == null }) listOf(t("анимация ничего не нарисовала в этих кадрах", "the animation drew nothing in these frames")) else emptyList()
                    shots = list.map { o ->
                        val (out, c) = canvasOf()
                        val r = RectF((bx.x - bx.w / 2) * pw, bx.y * ph - bx.w * pw * bx.ratio / 2, (bx.x + bx.w / 2) * pw, bx.y * ph + bx.w * pw * bx.ratio / 2)
                        if (brief.dim > 0f) c.drawRect(if (brief.dimAll) RectF(0f, 0f, pw.toFloat(), ph.toFloat()) else r, Paint().apply { color = Color.argb((brief.dim * 255).toInt(), 0, 0, 0) })
                        if (o != null) c.drawBitmap(o, null, r, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (brief.opacity * 255).toInt() })
                        out
                    }
                    if (col != null) render()
                }
            }
        }
    }

    /** The block the answer makes (not yet in the project). */
    private fun blockFor(ans: InsertAnswer, bx: PlaceBox): EBlock {
        val k = if (ans is InsertAnswer.Scheme) "scheme" else EditAnim.KIND
        val inAnim = when {
            k == EditAnim.KIND && (brief.enter == "code" || brief.enter.isEmpty()) -> "none"
            brief.enter in StudioEdit.ANIMS -> brief.enter
            else -> "pop"
        }
        val outAnim = when {
            k == EditAnim.KIND && (brief.exit == "code" || brief.exit.isEmpty()) -> "none"
            brief.exit in StudioEdit.ANIMS -> brief.exit
            else -> "fade"
        }
        val base = EBlock(blockId ?: "tmp", k, startMs, durMs, bx.x, bx.y, bx.w, bx.ratio, opacity = brief.opacity, animIn = inAnim, animOut = outAnim,
            brief = EditInsert.briefJson(brief).toString(), ask = brief.what, dim = brief.dim, dimAll = brief.dimAll)
        return when (ans) {
            is InsertAnswer.Scheme -> base.copy(scheme = StudioScheme.repair(ans.scheme, StudioEdit.wordRange(a.words, startMs, startMs + durMs).takeIf { !it.isEmpty() }),
                look = brief.style.ifEmpty { a.lastStyle }, over = EditInsert.lookOf(brief))
            is InsertAnswer.Anim -> base.copy(text = ans.name.ifBlank { t("Анимация", "Animation") }, loopMs = if (ans.loopMs > 0) ans.loopMs else brief.loopMs)
        }
    }

    /** The answer into the project: the block replaces the waiting one (or a new one), the animation gets its frames rendered. */
    private fun apply(ans: InsertAnswer, quiet: Boolean) {
        val p = a.project ?: return
        val bx = box()
        var b = blockFor(ans, bx)
        val old = blockId?.let { StudioEdit.find(p.blocks, it) }
        val past = if (old != null && !old.pending) listOfNotNull(
            when {
                old.kind == "scheme" && old.scheme != null -> StudioScheme.toJson(old.scheme).toString()
                old.kind == EditAnim.KIND && old.file.isNotEmpty() -> old.file
                else -> null
            }) + old.past else old?.past.orEmpty()
        if (old != null) b = b.copy(id = old.id, keys = old.keys, rot = old.rot)
        else b = b.copy(id = StudioEdit.nextId(p.blocks))
        b = b.copy(past = past.take(3), req = "")
        if (ans is InsertAnswer.Anim) {
            val cd = EditInsert.codeDir(ans.code)
            File(a.dir, cd).mkdirs()
            File(a.dir, "$cd/${EditAnim.CODE}").writeText(ans.code.trim())
            a.anims.forget(cd)
            b = b.copy(file = "$cd/new")
        }
        val list = if (old != null) StudioEdit.replace(p.blocks, b) else p.blocks + b
        a.commit(sfxFor(p, b, list), b.id)
        if (!quiet) a.select(b.id)
        if (b.kind == "scheme" && brief.style.isNotEmpty()) a.lastStyle = brief.style
        a.stage?.refresh()
        if (ans is InsertAnswer.Anim) a.anims.enqueue(b.id)
        remember()
        blockId = b.id
    }

    /** Sound effects of the brief as short sound blocks: clicks as the elements appear, a whoosh on entry. */
    private fun sfxFor(p: EProject, b: EBlock, list: List<EBlock>): List<EBlock> {
        val name = when (brief.sfx) {
            "soft" -> "pop"
            "whoosh" -> "whoosh"
            else -> return list
        }
        val file = "sfx/$name.wav"
        val f = File(a.dir, file)
        if (!f.exists()) runCatching { writeWav(f, StudioSfx.synth(name, 44_100), 44_100) }.onFailure { return list }
        val times = when {
            name == "whoosh" -> listOf(b.startMs)
            b.kind == "scheme" && b.scheme != null -> StudioEdit.schemeTimes(b.scheme, a.words, b)
            else -> {
                val n = max(1, brief.items.size.takeIf { it > 0 } ?: brief.count.takeIf { it > 0 } ?: 3)
                val step = ((b.durMs - 1800).coerceAtLeast(300L) / n).coerceIn(250L, 700L)
                (0 until n).map { b.startMs + 200 + it * step }
            }
        }.filter { it < b.endMs }.take(8)
        var cur = list
        for (ms in times) {
            val len = max(StudioEdit.MIN_MS, StudioSfx.lengthMs(name))
            cur = cur + EBlock(StudioEdit.nextId(cur), EditMusic.KIND, StudioSfx.startOf(name, ms), len, 0.5f, 0.5f, 1f, 0.6f,
                animIn = "none", animOut = "none", text = "🔊 $name", file = file, volume = 0.7f)
        }
        return cur
    }

    private fun writeWav(f: File, pcm: FloatArray, rate: Int) {
        f.parentFile?.mkdirs()
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
        FileOutputStream(f).use {
            it.write(h.array())
            it.write(data.array())
        }
    }

    // ---------- the mock-up ----------

    /** The frame under the playhead with the block as the brief puts it: place, size, plate, opacity, darkening, zones. */
    private inner class MockView(ctx: Context) : View(ctx) {
        private val pnt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private var frame = RectF()

        init {
            contentDescription = t("Макет блока на кадре", "Mock-up of the block on the frame")
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = min(context.dp(260), (w * fh / fw.toFloat()).roundToInt())
            setMeasuredDimension(w, h)
        }

        override fun onDraw(c: Canvas) {
            val k = min(width / fw.toFloat(), height / fh.toFloat())
            val l = (width - fw * k) / 2
            val tp = (height - fh * k) / 2
            frame = RectF(l, tp, l + fw * k, tp + fh * k)
            fun fx(x: Float) = frame.left + x * frame.width()
            fun fy(y: Float) = frame.top + y * frame.height()
            val bmp = frameBmp
            if (bmp != null) c.drawBitmap(bmp, null, frame, pnt) else {
                pnt.color = 0xFF33363D.toInt()
                c.drawRect(frame, pnt)
            }
            // zones to keep clear
            pnt.style = Paint.Style.FILL
            pnt.color = 0x33FF5252
            if ("shorts" in brief.avoid && fh > fw) {
                c.drawRect(fx(0f), fy(0.82f), fx(1f), fy(1f), pnt)
                c.drawRect(fx(0.88f), fy(0.45f), fx(1f), fy(0.82f), pnt)
            }
            if (brief.face.size >= 4) {
                pnt.style = Paint.Style.STROKE
                pnt.strokeWidth = context.dp(2).toFloat()
                pnt.color = if ("face" in brief.avoid) 0xCCFF5252.toInt() else 0x88FFFFFF.toInt()
                pnt.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                c.drawOval(RectF(fx(brief.face[0]), fy(brief.face[1]), fx(brief.face[0] + brief.face[2]), fy(brief.face[1] + brief.face[3])), pnt)
                pnt.pathEffect = null
                pnt.style = Paint.Style.FILL
            }
            val b = box()
            val hw = b.w * frame.width() / 2
            val hh = b.w * fw * b.ratio * k / 2
            val r = RectF(fx(b.x) - hw, fy(b.y) - hh, fx(b.x) + hw, fy(b.y) + hh)
            if (brief.dim > 0.01f) {
                pnt.color = Color.argb((brief.dim * 255).toInt(), 0, 0, 0)
                if (brief.dimAll) c.drawRect(frame, pnt) else c.drawRoundRect(RectF(r).apply { inset(-12f, -12f) }, 18f, 18f, pnt)
            }
            val (acc, ink, card) = a.anims.colors(brief.style.ifEmpty { a.lastStyle }, brief.accent)
            val fill = brief.fill.ifEmpty { if (kind == "scheme") "solid" else "semi" }
            val alpha = if (fill == "none") 0f else if (brief.bgAlpha >= 0f) brief.bgAlpha else EditInsert.defaultAlpha(fill)
            pnt.color = card
            pnt.alpha = (255 * alpha * brief.opacity).toInt().coerceIn(0, 255)
            val rad = if (fill == "full") 0f else min(r.width(), r.height()) * 0.1f
            c.drawRoundRect(r, rad, rad, pnt)
            pnt.style = Paint.Style.STROKE
            pnt.strokeWidth = context.dp(2).toFloat()
            pnt.color = acc
            pnt.alpha = (255 * max(0.35f, brief.opacity)).toInt()
            c.drawRoundRect(r, rad, rad, pnt)
            pnt.style = Paint.Style.FILL
            val ty = EditInsert.type(brief.type)
            txt.color = if (fill == "none") Color.WHITE else ink
            txt.alpha = (255 * brief.opacity).toInt().coerceIn(40, 255)
            txt.textSize = min(r.height() * 0.22f, context.dp(16).toFloat())
            txt.setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
            c.drawText(ty.icon + " " + t(ty.ru, ty.en), r.centerX(), r.centerY() + txt.textSize * 0.35f, txt)
            if (brief.type == "pointer" && brief.target.size >= 2) {
                pnt.color = acc
                c.drawCircle(fx(brief.target[0]), fy(brief.target[1]), context.dp(7).toFloat(), pnt)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (pick.isEmpty()) return false
            if (e.action == MotionEvent.ACTION_DOWN) return true
            if (e.action != MotionEvent.ACTION_UP || frame.width() <= 0f) return true
            val x = ((e.x - frame.left) / frame.width()).coerceIn(0f, 1f)
            val y = ((e.y - frame.top) / frame.height()).coerceIn(0f, 1f)
            when (pick) {
                "place" -> {
                    brief = brief.copy(place = "custom", pos = listOf(x, y))
                    keepBox = null
                }
                "target" -> brief = brief.copy(target = listOf(x, y))
                "face" -> {
                    val w = 0.22f
                    val h = w * fw / fh * 1.25f
                    brief = brief.copy(face = listOf((x - w / 2).coerceIn(0f, 1f), (y - h / 2).coerceIn(0f, 1f), w, h), avoid = (brief.avoid + "face").distinct())
                    keepBox = null
                }
                "color" -> frameBmp?.let { bmp ->
                    val px = bmp.getPixel((x * (bmp.width - 1)).toInt(), (y * (bmp.height - 1)).toInt())
                    brief = brief.copy(accent = px or 0xFF000000.toInt())
                    pick = ""
                    render()
                    return true
                }
            }
            refresh()
            return true
        }
    }
}

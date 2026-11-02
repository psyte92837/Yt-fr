/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the insert wizard needs from the editor screen. */
interface InsertHost {
    val ui: LumenBase
    fun project(): EProject?
    fun blocks(): List<EBlock>
    /** Words of the voice in output time. */
    fun words(): List<SWord>
    fun curMs(): Long
    fun outLen(): Long
    fun dir(): File
    fun styleDir(): File
    fun lang(): String
    /** The picture of the preview now (null before the first frame). */
    fun frame(): Bitmap?
    /** The WebView host in the editor window (for trial runs). */
    fun canvas(): CanvasHost
    /** A finished change: one undo step. */
    fun commit(list: List<EBlock>, sel: String?)
    fun deliverTo(pid: String, mutate: (EProject, List<EBlock>, Boolean) -> List<EBlock>)
    fun setBrand(json: String)
    /** The code or the size of an animation block changed: render its frames. */
    fun animChanged(id: String)
    fun pause()
    /** Frames a second of the video (≤ 60). */
    fun fps(): Int
    /** shorts | reels | tiktok | youtube */
    fun platform(): String
    /** The folder of the user's themes. */
    fun themesDir(): File
    /** Seams of the main track, output ms (cues of the animations). */
    fun cuts(): List<Long>
    /** A record for the local statistics of the inserts. */
    fun stat(s: InsertStat)
}

/**
 * «✨ Схема или анимация от ИИ» — the insert wizard of the editor (PLAN_EDITOR_INSERT §6): 1 What (a type by meaning) ·
 * 2 Brief (mock-up on the frame, sections A–Е, warnings, the request) · 3 Who makes it (the built-in AI or another one by
 * copy / paste with a pending block on the timeline) · 4 Check and edits (frames of the result, edits without AI and
 * through the AI, versions, library). One sheet that is rebuilt on every change.
 */
class InsertWizard(private val h: InsertHost) {
    companion object {
        private const val TASK = "studioedit:insert"
        private const val SYSTEM = "You are a designer of info-graphics and motion graphics for videos. Follow the request exactly; answer only in the asked format."
        private val SWATCHES = listOf(0xFFFFD60A, 0xFFFF3B30, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF9F0A, 0xFF64D2FF, 0xFFFFFFFF).map { it.toInt() }
        private const val PREF_LAST = "edit_insert_last"
        private const val PREF_RECIPES = "edit_insert_recipes"
        private const val PREF_DRAFT = "edit_insert_draft"

        fun library(ctx: Context) = InsertLibrary(File(ctx.filesDir, "lumen/studio/edit/anims"))
    }

    private val ui get() = h.ui
    private val words get() = h.words()

    private var dialog: Dialog? = null
    private var col: Col? = null
    private var footer: LinearLayout? = null
    private var scroll: ScrollView? = null

    private var step = 0
    private var brief = EditBrief()
    private var wish = ""
    private var startMs = 0L
    private var baseDur = 5000L
    private var span: IntRange? = null
    /** The block being redone or checked (null = a new one). */
    private var target: String? = null
    private var req = ""
    private var request = ""
    private var answer: InsertAnswer? = null
    private var trial: AnimRender.Trial? = null
    private var trialFor = ""
    private var busy = ""
    private var problems: List<String> = emptyList()
    private var rounds = 0
    private var copied = false
    private var openSec = setOf<String>()
    private var frameBmp: Bitmap? = null
    private var frameLum = -1.0
    private var counter = 0
    private var aiEdit = ""
    private var provider = ""
    /** Several answers of one request (variants), [answer] is the chosen one. */
    private var answers: List<InsertAnswer> = emptyList()
    private var variants = 1
    private var inserted = false
    private var source = "ai"
    private var firstPass = true
    private var flipFrames: List<Bitmap> = emptyList()
    private var flipView: ImageView? = null
    private var flipIndex = 0
    private val flipTick = object : Runnable {
        override fun run() {
            val v = flipView ?: return
            if (flipFrames.isEmpty() || !isOpen) return
            flipIndex = (flipIndex + 1) % flipFrames.size
            v.setImageBitmap(flipFrames[flipIndex])
            v.postDelayed(this, 1000L / 12)
        }
    }

    val isOpen: Boolean get() = dialog?.isShowing == true

    /** The request mark this wizard waits an answer for ("" = none). */
    val waitingFor: String get() = if (isOpen && step >= 2) req else ""

    // ---------- entries ----------

    /** A new block at the playhead or on the phrase [span] of the speech. */
    fun start(span0: IntRange? = null) {
        val p = h.project() ?: return
        h.pause()
        reset()
        span = span0
        startMs = span0?.let { StudioEdit.spanOf(words, it, h.outLen()).first } ?: h.curMs()
        grabFrame()
        open()
    }

    /** «🤖 Переделать»: the same form with the brief of [b] (its place and time kept). */
    fun redo(b: EBlock) {
        h.pause()
        reset()
        target = b.id
        startMs = b.startMs
        baseDur = b.durMs
        val p = h.project() ?: return
        val (fw, fh) = frameSize(p)
        val r = rectOf(b, fw, fh)
        brief = (EditInsert.briefFrom(b.brief) ?: EditBrief(type = if (b.kind == "scheme") "steps" else "own")).let {
            it.copy(place = "custom", rect = r, draw = b.kind, lenMs = 0)
        }
        wish = b.ask
        grabFrame()
        step = 1
        open()
    }

    /** «✎ / 🛠 Исправить»: step 4 with what the block has now ([error] — of its render). */
    fun check(b: EBlock, error: String = "") {
        val p = h.project() ?: return
        h.pause()
        reset()
        target = b.id
        startMs = b.startMs
        baseDur = b.durMs
        val (fw, fh) = frameSize(p)
        brief = (EditInsert.briefFrom(b.brief) ?: EditBrief(type = if (b.kind == "scheme") "steps" else "own")).copy(place = "custom", rect = rectOf(b, fw, fh), draw = b.kind)
        wish = b.ask
        req = b.req.ifEmpty { newReq() }
        request = EditInsert.request(ctx(p), brief, b.kind)
        answer = when {
            b.kind == "scheme" && b.scheme != null -> InsertAnswer.Scheme(b.scheme, req, b.text)
            b.kind == EditInsert.KIND -> AnimRender.code(h.dir(), b)?.let { InsertAnswer.Anim(it, req, b.text, b.loopMs) }
            else -> null
        }
        if (error.isNotEmpty()) problems = listOf(error)
        grabFrame()
        step = if (answer != null) 3 else 2
        open()
        if (answer is InsertAnswer.Anim && error.isEmpty()) runTrial()
    }

    /**
     * An answer that came from the clipboard or was pasted: shown in the open wizard if it waits for it, else put
     * into its block straight away. False — it belongs to no block.
     */
    fun deliver(a: InsertAnswer): Boolean {
        val p = h.project() ?: return false
        if (isOpen && step >= 2 && (a.req.isEmpty() || a.req == req)) {
            onAnswer(a, false)
            return true
        }
        val b = EditInsert.match(p.blocks, a) ?: return false
        apply(p, b, a)
        ui.snack(t("Ответ ИИ: «", "AI answer: «") + a.name.take(30) + "»", t("Проверить", "Check")) {
            h.blocks().firstOrNull { it.id == b.id }?.let { check(it) }
        }
        return true
    }

    private fun reset() {
        step = 0
        brief = EditBrief()
        wish = ""
        span = null
        target = null
        req = ""
        request = ""
        answer = null
        trial = null
        trialFor = ""
        busy = ""
        problems = emptyList()
        rounds = 0
        copied = false
        openSec = setOf()
        aiEdit = ""
        baseDur = 5000L
        answers = emptyList()
        variants = 1
        inserted = false
        source = "ai"
        firstPass = true
        flipFrames = emptyList()
    }

    private fun newReq(): String = EditInsert.reqId(System.currentTimeMillis(), ++counter)

    /** A message: over the sheet while it is open (a snack of the screen would hide behind it). */
    private fun say(s: String) {
        if (isOpen) android.widget.Toast.makeText(ui, s, android.widget.Toast.LENGTH_SHORT).show() else ui.toast(s)
    }

    // ---------- context ----------

    private fun frameSize(p: EProject): Pair<Int, Int> = EditCanvas.outSize(p.w, p.h, p.ratio, Lumen.prefs.getInt("studio_edit_side", 1920))

    private fun rectOf(b: EBlock, fw: Int, fh: Int): FRect {
        val hw = b.w / 2
        val hh = b.w * b.ratio * fw / fh / 2
        return FRect(b.x - hw, b.y - hh, b.x + hw, b.y + hh)
    }

    private fun kind(): String = EditInsert.kindOf(brief)

    private fun timing(): Pair<Long, Long> {
        val total = h.outLen()
        span?.takeIf { brief.lenMs <= 0 }?.let { return StudioEdit.spanOf(words, it, total) }
        val want = if (brief.lenMs > 0) brief.lenMs else baseDur
        return StudioEdit.placeAt(startMs, total, want)
    }

    private fun subsInVideo(p: EProject) = p.blocks.any { EditCaptions.isSub(it) }

    private fun ctx(p: EProject): InsertCtx {
        val (fw, fh) = frameSize(p)
        val rect = EditInsert.place(brief, fw, fh)
        val (s, d) = timing()
        val st = SchemeStyles.find(h.styleDir(), brief.style.ifEmpty { SchemeStyles.AUTO })
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        val zones = mutableListOf<InsertZone>()
        // the subtitles where they really stand, else where they usually do
        if (brief.avoidSubs != false && subsInVideo(p)) {
            val subs = p.blocks.filter { EditCaptions.isSub(it) && it.startMs < s + d && it.endMs > s }.map { rectOf(it, fw, fh) }
            zones += if (subs.isEmpty()) EditInsert.subsZone(fw, fh)
            else InsertZone(FRect(0f, subs.minOf { it.t }, 1f, subs.maxOf { it.b }), t("субтитры", "subtitles"))
        }
        // other blocks shown at the same time
        p.blocks.filter { it.id != target && it.startMs < s + d && it.endMs > s && !EditCaptions.isSub(it) &&
            it.kind in setOf("scheme", "image", "text", "emoji", EditInsert.KIND, EditMotion.KIND, EditLottie.KIND) && !EditInsert.isPending(it) }
            .take(4).forEach { zones += InsertZone(rectOf(it, fw, fh), t("блок «", "block «") + EditSuggest.name(it).take(14) + "»") }
        val th = theme(p)
        val labels = brief.rows.map { it.label to it.main }
        return InsertCtx(fw, fh, rect, EditInsert.placeName(brief.place.ifEmpty { EditInsert.defaultPlace(brief, fw, fh) }), s, d, words,
            brief.lang.ifBlank { h.lang() }, wish, req, zones,
            accent = hex(brief.accent ?: th?.accent ?: st?.accent ?: StudioPrefs.accent), ink = hex(th?.ink ?: st?.ink ?: 0xFFFFFFFF.toInt()),
            card = hex(th?.surface ?: st?.bg ?: 0xFF101318.toInt()), font = th?.let { EditThemes.cssFont(it.font) } ?: when (brief.font) { "serif" -> "serif"; "mono" -> "monospace"; else -> "sans-serif" },
            fps = h.fps(), platform = h.platform(), totalMs = h.outLen(), theme = th, cues = AnimV11.cues(words, s, s + d, labels, h.cuts()))
    }

    /** The theme of the brief: its own, else the video's. */
    private fun theme(p: EProject): EditTheme? = EditThemes.resolve(brief.theme, p.theme, h.themesDir())

    /** A native way to draw this brief without AI: a motion template or a scheme of the data (null — only the AI). */
    private fun native(): Pair<String, Any>? {
        val k = kind()
        if (k == EditInsert.KIND) EditMotion.forType(brief.type)?.let { return EditMotion.KIND to it }
        EditSuggest.schemeFromRows(brief.type, brief.rows, brief.title)?.let { return "scheme" to it }
        if (k == "scheme") EditMotion.forType(brief.type)?.let { return EditMotion.KIND to it }
        return null
    }

    private fun speech(p: EProject): String {
        span?.let { r -> return r.joinToString(" ") { words[it].text } }
        val (s, d) = StudioEdit.placeAt(startMs, h.outLen(), max(baseDur, 6000))
        return words.filter { it.startMs >= s && it.startMs < s + d }.joinToString(" ") { it.text }
    }

    /** The current frame, its face (android.media.FaceDetector) and its brightness under the block. */
    private fun grabFrame() {
        frameBmp = runCatching { h.frame() }.getOrNull()
        brief = brief.copy(face = brief.face ?: frameBmp?.let { face(it) })
        lum()
    }

    private fun face(b: Bitmap): FRect? = runCatching {
        val w = 320
        val hh = (b.height * w / b.width.toFloat()).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(b, w, hh, true).copy(Bitmap.Config.RGB_565, false)
        val found = arrayOfNulls<android.media.FaceDetector.Face>(2)
        val n = android.media.FaceDetector(w, hh, 2).findFaces(small, found)
        small.recycle()
        val f = found.firstOrNull { it != null && it.confidence() >= 0.3f }?.takeIf { n > 0 } ?: return@runCatching null
        val mid = android.graphics.PointF()
        f.getMidPoint(mid)
        val e = f.eyesDistance()
        FRect(((mid.x - e * 1.4f) / w).coerceIn(0f, 1f), ((mid.y - e * 1.6f) / hh).coerceIn(0f, 1f),
            ((mid.x + e * 1.4f) / w).coerceIn(0f, 1f), ((mid.y + e * 2.4f) / hh).coerceIn(0f, 1f))
    }.getOrNull()

    private fun lum() {
        val b = frameBmp ?: return
        val p = h.project() ?: return
        val (fw, fh) = frameSize(p)
        val r = EditInsert.place(brief, fw, fh)
        var sum = 0.0
        var n = 0
        for (i in 0 until 10) for (j in 0 until 10) {
            val x = ((r.l + r.w * (i + 0.5f) / 10) * b.width).toInt().coerceIn(0, b.width - 1)
            val y = ((r.t + r.h * (j + 0.5f) / 10) * b.height).toInt().coerceIn(0, b.height - 1)
            sum += SchemeStyles.luminance(b.getPixel(x, y))
            n++
        }
        frameLum = if (n > 0) sum / n else -1.0
    }

    // ---------- the sheet ----------

    private fun open() {
        if (dialog?.isShowing == true) return redraw()
        val a = ui
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Palette.SURFACE, 28f, a)
        }
        val c = Col(a, 20)
        val sc = ScrollView(a).apply { addView(c.root) }
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val f = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(12))
        }
        root.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.setContentView(root)
        // back from another AI with its answer copied: the sheet has the focus, so it reads the clipboard itself
        root.viewTreeObserver.addOnWindowFocusChangeListener { focus -> if (focus) clipCheck() }
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.9f).toInt())
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.setOnDismissListener {
            dialog = null
            col = null
            flipView = null
            if (busy.isEmpty()) frameBmp = null
            // a brief left without inserting waits for «↺ Продолжить бриф»
            if (!inserted && step >= 1) runCatching {
                Lumen.prefs.edit().putString(PREF_DRAFT, JSONObject().put("brief", EditInsert.briefJson(brief)).put("wish", wish).toString()).apply()
            }
        }
        dialog = d
        col = c
        footer = f
        scroll = sc
        redraw()
        d.show()
    }

    private fun close() {
        dialog?.dismiss()
        dialog = null
    }

    private fun redraw(keepScroll: Boolean = true) {
        val c = col ?: return
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        c.clear()
        val f = footer ?: return
        f.removeAllViews()
        val head = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(ui, if (target == null) t("✨ Схема или анимация от ИИ", "✨ AI scheme or animation") else t("🤖 Переделать блок", "🤖 Redo the block"), Type.TITLE_L),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconBtn(ui, "?") { HelpActivity.open(ui, "insert") })
        c.add(head, 0)
        val names = listOf(t("1 Что", "1 What"), t("2 Бриф", "2 Brief"), t("3 ИИ", "3 AI"), t("4 Проверка", "4 Check"))
        c.add(text(ui, names.mapIndexed { i, s -> if (i == step) "● $s" else s }.joinToString("  ·  "), 13f, bold = true, color = Palette.MUTED), 6)
        c.steps(step, 4)
        when (step) {
            0 -> stepWhat(c)
            1 -> stepBrief(c)
            2 -> stepWho(c)
            else -> stepCheck(c)
        }
        footerButtons(f)
        if (keepScroll) scroll?.post { scroll?.scrollTo(0, y) }
    }

    private fun footerButtons(f: LinearLayout) {
        fun add(v: View, weight: Float) = f.addView(v, LinearLayout.LayoutParams(0, ui.dp(48), weight).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
        if (step > 0 && busy.isEmpty()) add(btn(ui, t("‹ Назад", "‹ Back"), style = BtnStyle.TEXT) {
            step = if (step == 3 && answer != null) 2 else step - 1
            redraw(false)
        }, 1f)
        when (step) {
            0 -> add(btn(ui, t("Закрыть", "Close"), style = BtnStyle.TEXT) { close() }, 1f)
            1 -> {
                val blocking = warnings().any { it.blocking }
                val nat = native()
                if (nat != null) {
                    add(btn(ui, t("ИИ ›", "AI ›"), style = BtnStyle.TONAL) {
                        if (blocking) return@btn say(t("Опишите, что показать", "Describe what to show"))
                        toWho()
                    }, 1.2f)
                    add(btn(ui, t("⚡ Вставить без ИИ", "⚡ Insert without AI"), primary = true) { insertNative() }, 2f)
                } else add(btn(ui, t("Дальше ›", "Next ›"), primary = true) {
                    if (blocking) return@btn say(t("Опишите, что показать", "Describe what to show"))
                    toWho()
                }, 2f)
            }
            2 -> if (busy.isNotEmpty()) add(btn(ui, t("Остановить", "Stop"), style = BtnStyle.OUTLINED) {
                BgTasks.find(TASK)?.let { BgTasks.stop(it) }
            }, 2f)
            else -> if (answer != null && busy.isEmpty()) add(btn(ui, t("✅ Вставить", "✅ Insert"), primary = true) { insert() }, 2f)
        }
    }

    // ---------- step 1: what ----------

    private fun stepWhat(c: Col) {
        val p = h.project() ?: return
        val said = speech(p)
        if (said.isNotBlank()) c.muted("«" + said.take(200) + "»")
        else c.muted(t("В этом месте нет речи с таймингом — опишите, что показать, на следующем шаге.", "No timed speech here — describe what to show on the next step."))
        val quick = mutableListOf<View>()
        draft()?.let { (b0, w0) ->
            quick += chip(ui, t("↺ Продолжить бриф", "↺ Continue the brief")) {
                brief = b0.copy(face = brief.face)
                wish = w0
                if (span == null) baseDur = max(5000L, EditInsert.readMs(EditInsert.screenWords(brief)))
                lum()
                step = 1
                redraw(false)
            }
        }
        lastBrief()?.let { last -> quick += chip(ui, t("↺ Как в прошлый раз", "↺ Like last time")) { pickType(last.type, last) } }
        if (recipes().isNotEmpty()) quick += chip(ui, t("⭐ Мои рецепты", "⭐ My recipes")) { recipesSheet() }
        quick += chip(ui, t("📚 Моя библиотека", "📚 My library")) { librarySheet() }
        quick += chip(ui, t("🧪 Примеры (без ИИ)", "🧪 Examples (no AI)")) { examplesSheet() }
        quick += chip(ui, t("🎛 Шаблон Монтажа", "🎛 Montage template")) { montageSheet() }
        c.chips(quick, 9)
        c.add(text(ui, t("Что показать?", "What to show?"), Type.TITLE_M, bold = true), 16)
        val grid = FlowLayout(ui, ui.dp(8), ui.dp(8))
        for (ty in EditInsert.TYPES) {
            val card = LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                background = ripple(ui, roundBg(Palette.CARD, 14f, ui), Palette.ON_SURFACE)
                minimumWidth = ui.dp(100)
                contentDescription = ty.name
                addView(text(ui, ty.emoji, 24f).apply { gravity = Gravity.CENTER })
                addView(text(ui, ty.name, 13f, bold = true).apply {
                    gravity = Gravity.CENTER
                    maxWidth = ui.dp(110)
                })
                addView(text(ui, ty.draws.joinToString(" / ") { if (it == "scheme") t("схема", "scheme") else t("анимация", "animation") }, 11f, color = Palette.MUTED).apply { gravity = Gravity.CENTER })
                setOnClickListener { pickType(ty.id, null) }
            }
            grid.addView(card, ViewGroup.LayoutParams(ui.dp(108), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        c.add(grid, 8)
    }

    private fun brand(p: EProject): EditBrief? = EditInsert.briefFrom(p.brand)

    private fun pickType(type: String, recipe: EditBrief?) {
        val p = h.project() ?: return
        val face = brief.face
        val content = EditInsert.defaults(type, speech(p), brand(p), face)
        brief = if (recipe != null) EditInsert.withRecipe(content, recipe).copy(type = recipe.type, face = face) else content
        if (span == null) baseDur = max(5000L, EditInsert.readMs(EditInsert.screenWords(brief)))
        else baseDur = StudioEdit.spanOf(words, span!!, h.outLen()).second
        lum()
        step = 1
        redraw(false)
    }

    // ---------- step 2: brief ----------

    private fun set(f: (EditBrief) -> EditBrief) {
        brief = f(brief)
        lum()
        redraw()
    }

    private fun label(c: Col, s: String) = c.add(text(ui, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)

    private fun <T> choice(c: Col, name: String, options: List<Pair<T, String>>, value: T, on: (T) -> Unit) {
        label(c, name)
        c.add(choiceChips(ui, options, value) { v -> on(v); redraw() }, 4)
    }

    private fun stepBrief(c: Col) {
        val p = h.project() ?: return
        val ty = EditInsert.type(brief.type)
        val k = kind()
        val cx = ctx(p)
        c.add(text(ui, ty.emoji + " " + ty.name + " · " + (if (k == "scheme") t("схема", "scheme") else t("анимация", "animation")) + " · " +
            StudioEdit.clock(cx.startMs) + "–" + StudioEdit.clock(cx.endMs), Type.TITLE_M, bold = true), 12)
        c.add(mockView(p, cx), 8)
        themeRow(c, p)
        native()?.let { nat ->
            label(c, t("⚡ Без ИИ — так будет сразу", "⚡ Without AI — this is it at once"))
            c.add(flipbook(p, cx, nat), 4)
        }
        if (ty.draws.size > 1) {
            label(c, t("Как нарисовать", "How to draw"))
            c.add(segBar(ui, ty.draws.map { if (it == "scheme") t("Как схема (без кода)", "As a scheme (no code)") else t("Как анимация (код ИИ)", "As an animation (AI code)") },
                ty.draws.indexOf(k).coerceAtLeast(0)) { i -> set { it.copy(draw = ty.draws[i]) } }, 4)
        }
        // the quick path: what to show is all that is needed
        label(c, t("Что показать", "What to show"))
        val said = speech(p)
        if (said.isNotBlank()) c.muted("«" + said.take(180) + "»")
        val what = edt(ui, t("например: сравнить долю Android и iOS", "e.g. compare the shares of Android and iOS"), brief.what, multiline = true)
        what.watch { brief = brief.copy(what = it) }
        c.add(what, 4)
        label(c, t("Пожелание к виду (необязательно)", "Wish for the look (optional)"))
        val w = edt(ui, t("например: как в научпоп-роликах, спокойно", "e.g. like science videos, calm"), wish)
        w.watch { wish = it }
        c.add(w, 4)

        c.section(t("А. Содержание", "A. Content"), contentSummary(), "a" in openSec, { toggle("a", it) }) { sectionContent(this) }
        c.section(t("Б. Вид", "B. Look"), lookSummary(), "b" in openSec, { toggle("b", it) }) { sectionLook(this, k) }
        c.section(t("В. Прозрачность и видео под блоком", "C. Transparency and the video under it"), alphaSummary(), "c" in openSec, { toggle("c", it) }) { sectionAlpha(this) }
        c.section(t("Г. Движение и время", "D. Motion and time"), motionSummary(cx), "d" in openSec, { toggle("d", it) }) { sectionMotion(this, k, cx) }
        c.section(t("Д. Место и размер", "E. Place and size"), placeSummary(p), "e" in openSec, { toggle("e", it) }) { sectionPlace(this, p) }
        c.section(t("Е. Стиль ролика и рецепты", "F. Style of the video and recipes"), if (p.brand.isNotEmpty()) t("стиль ролика задан", "video style set") else "", "f" in openSec, { toggle("f", it) }) { sectionBrand(this, p) }

        warningsView(c, cx)
        c.add(text(ui, EditInsert.summary(brief, cx), 13f, bold = true, color = Palette.MUTED), 16)
        c.add(btn(ui, t("👁 Запрос, который уйдёт в ИИ", "👁 The request sent to the AI"), style = BtnStyle.TEXT) {
            if (req.isEmpty()) req = newReq()
            ui.showText(t("Запрос", "Request"), EditInsert.request(ctx(p), brief, k))
        }, 4)
    }

    private fun toggle(id: String, on: Boolean) {
        openSec = if (on) openSec + id else openSec - id
        redraw()
    }

    private fun EditText.watch(on: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = on(s?.toString().orEmpty())
    })

    private fun contentSummary(): String = listOfNotNull(
        if (brief.rows.isNotEmpty()) t("данных: ${brief.rows.size}", "data: ${brief.rows.size}") else null,
        when (brief.title) { "" -> null; "-" -> t("без заголовка", "no title"); else -> "«${brief.title}»" },
        if (brief.items > 0) t("элементов ${brief.items}", "${brief.items} items") else null,
        if (!brief.onlySaid) t("можно дополнять", "may add facts") else t("только сказанное", "only what is said"),
        brief.tone.takeIf { it.isNotEmpty() }?.let { toneName(it) }
    ).joinToString(" · ")

    private fun toneName(s: String) = when (s) {
        "business" -> t("деловой", "business"); "friendly" -> t("дружелюбный", "friendly"); "fun" -> t("с юмором", "humorous")
        "drama" -> t("драматично", "dramatic"); "kids" -> t("для детей", "for kids"); else -> t("Авто", "Auto")
    }

    private fun sectionContent(c: Col) {
        val p = h.project() ?: return
        choice(c, t("Источник", "Source"), listOf("" to t("Авто", "Auto"), "speech" to t("Речь", "Speech"), "data" to t("Мои данные", "My data"), "text" to t("Описание", "Description")),
            brief.source) { v -> brief = brief.copy(source = v) }
        label(c, t("Данные (★ — главное)", "Data (★ — the main one)"))
        brief.rows.forEachIndexed { i, r ->
            val row = LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(ui, EditInsert.rowText(r).ifBlank { "—" }, 15f).apply {
                setOnClickListener { editRow(i) }
                setPadding(0, ui.dp(10), 0, ui.dp(10))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chip(ui, if (r.main) "★" else "☆", r.main) { set { b -> b.copy(rows = b.rows.mapIndexed { j, x -> x.copy(main = j == i && !x.main) }) } })
            row.addView(chip(ui, "✕") { set { b -> b.copy(rows = b.rows.filterIndexed { j, _ -> j != i }) } })
            c.add(row, 2)
        }
        c.chips(listOfNotNull(
            if (brief.rows.size < 8) chip(ui, t("＋ Строка", "＋ Row")) { editRow(-1) } else null,
            chip(ui, t("↻ Взять из речи", "↻ From the speech")) {
                val rows = EditInsert.dataFromSpeech(speech(p))
                if (rows.isEmpty()) say(t("В речи нет чисел и имён", "No numbers or names in the speech")) else set { it.copy(rows = rows) }
            }
        ))
        EditInsert.TYPE_FIELDS[brief.type]?.forEach { (key, ru, en) ->
            label(c, t(ru, en))
            val e = edt(ui, t(ru, en), brief.fields[key].orEmpty())
            e.watch { v -> brief = brief.copy(fields = brief.fields + (key to v)) }
            c.add(e, 2)
        }
        c.check(t("Только сказанное — без новых фактов", "Only what is said — no new facts"), brief.onlySaid) { v -> brief = brief.copy(onlySaid = v) }
        choice(c, t("Заголовок", "Title"), listOf("" to t("Авто", "Auto"), "-" to t("Без", "None"), "own" to t("✎ Свой", "✎ Own")),
            if (brief.title.isEmpty() || brief.title == "-") brief.title else "own") { v ->
            if (v == "own") ui.prompt(t("Заголовок", "Title"), t("до 5 слов", "up to 5 words"), brief.title.takeIf { it != "-" }.orEmpty()) { s -> set { it.copy(title = s.trim()) } }
            else brief = brief.copy(title = v)
        }
        choice(c, t("Элементов", "Items"), listOf(0 to t("Авто", "Auto"), 3 to "3", 5 to "5", 7 to "7"), brief.items) { v -> brief = brief.copy(items = v) }
        choice(c, t("Длина подписей", "Caption length"), listOf("" to t("Авто", "Auto"), "short" to t("1–3 слова", "1–3 words"), "mid" to t("до 6 слов", "up to 6 words"), "phrase" to t("фраза", "a phrase")),
            brief.labels) { v -> brief = brief.copy(labels = v) }
        choice(c, t("Иконки", "Icons"), listOf("" to t("Авто", "Auto"), "emoji" to t("Эмодзи", "Emoji"), "none" to t("Без", "None")), brief.icons) { v -> brief = brief.copy(icons = v) }
        choice(c, t("Язык", "Language"), listOf("" to t("Как озвучка", "As the voice"), "русский" to "Русский", "English" to "English"), brief.lang) { v -> brief = brief.copy(lang = v) }
        choice(c, t("Тон", "Tone"), listOf("", "business", "friendly", "fun", "drama", "kids").map { it to toneName(it) }, brief.tone) { v -> brief = brief.copy(tone = v) }
    }

    private fun editRow(i: Int) {
        val r = brief.rows.getOrNull(i)
        ui.prompt(t("Строка данных", "Data row"), t("Подпись — значение единица, эмодзи в начале", "Label — value unit, emoji first"),
            r?.let { EditInsert.rowText(it.copy(main = false)) }.orEmpty()) { s ->
            val row = parseRow(s, r?.main == true) ?: return@prompt
            set { b -> b.copy(rows = if (i < 0) b.rows + row else b.rows.mapIndexed { j, x -> if (j == i) row else x }) }
        }
    }

    private fun parseRow(s0: String, main: Boolean): BriefRow? {
        val s = s0.trim()
        if (s.isEmpty()) return null
        val icon = s.takeWhile { !it.isLetterOrDigit() && !it.isWhitespace() && it != '—' && it != '-' }.trim()
        val rest = s.removePrefix(icon).trim()
        val parts = rest.split(Regex("\\s+[—–-]\\s+|:\\s+"), 2)
        val label = parts[0].trim()
        val v = parts.getOrNull(1)?.trim().orEmpty()
        val value = Regex("^[\\d.,]+").find(v)?.value.orEmpty()
        val unit = v.removePrefix(value).trim()
        return BriefRow(label, value.ifEmpty { v.takeIf { value.isEmpty() }.orEmpty() }, if (value.isEmpty()) "" else unit, icon, main)
    }

    private fun bgName(bg: String) = when (bg) {
        "none" -> t("Нет", "None"); "glass" -> t("Стекло", "Glass"); "semi" -> t("Полупрозрачная", "Semi"); "solid" -> t("Плотная", "Solid")
        "full" -> t("Перебивка", "Cutaway"); else -> t("Авто", "Auto")
    }

    private fun hexOf(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun lookSummary(): String = listOfNotNull(
        brief.style.takeIf { it.isNotEmpty() }?.let { id -> SchemeStyles.find(h.styleDir(), id)?.name },
        brief.bg.takeIf { it.isNotEmpty() }?.let { bgName(it) + if (brief.bgAlpha >= 0) " ${brief.bgAlpha} %" else "" },
        brief.accent?.let { t("акцент ", "accent ") + hexOf(it) },
        brief.font.takeIf { it.isNotEmpty() }, if (brief.upper == true) t("КАПС", "CAPS") else null,
        brief.size.takeIf { it.isNotEmpty() }?.uppercase()
    ).joinToString(" · ").ifEmpty { t("Авто — по стилю", "Auto — by the style") }

    private fun sectionLook(c: Col, k: String) {
        val p = h.project() ?: return
        val styles = SchemeStyles.list(h.styleDir()).filter { it.place == "overlay" || it.place == "panel" || it.id == SchemeStyles.AUTO }
        c.muted(t("Тема — над разделами, под макетом; здесь — точечные правки поверх неё.", "The theme is above the sections, under the mock-up; here — point edits over it."))
        choice(c, t("Стиль схемы", "Scheme style"), listOf("" to (if (p.brand.isNotEmpty()) t("Стиль ролика", "Video style") else t("Авто", "Auto"))) + styles.filter { it.id != SchemeStyles.AUTO }.map { it.id to it.name },
            brief.style) { v -> brief = brief.copy(style = v) }
        accentPicker(c)
        if (k == EditInsert.KIND) choice(c, t("Палитра", "Palette"), listOf("" to t("Из стиля", "From the style"), "mono" to t("Монохром + акцент", "Mono + accent"), "bright" to t("Яркая", "Bright"), "pastel" to t("Пастель", "Pastel")),
            brief.palette) { v -> brief = brief.copy(palette = v) }
        plate(c)
        choice(c, t("Скругление", "Rounding"), listOf("" to t("Авто", "Auto"), "none" to t("Нет", "None"), "s" to "S", "m" to "M", "l" to "L"), brief.radius) { v -> brief = brief.copy(radius = v) }
        choice(c, t("Обводка", "Border"), listOf<Boolean?>(null, true, false).map { it to tri(it) }, brief.border) { v -> brief = brief.copy(border = v) }
        choice(c, t("Тень", "Shadow"), listOf<Boolean?>(null, true, false).map { it to tri(it) }, brief.shadow) { v -> brief = brief.copy(shadow = v) }
        choice(c, t("Шрифт", "Font"), listOf("" to t("Авто", "Auto"), "sans" to t("Обычный", "Regular"), "condensed" to t("Узкий", "Condensed"), "serif" to t("С засечками", "Serif"),
            "mono" to t("Моно", "Mono"), "rounded" to t("Округлый", "Rounded")), brief.font) { v -> brief = brief.copy(font = v) }
        choice(c, t("Регистр", "Case"), listOf<Boolean?>(null, false, true).map { it to when (it) { null -> t("Авто", "Auto"); false -> t("Как есть", "As is"); true -> t("КАПС", "CAPS") } },
            brief.upper) { v -> brief = brief.copy(upper = v) }
        val (fw, fh) = frameSize(p)
        choice(c, t("Размер текста", "Text size") + " · " + t("не мельче ", "at least ") + EditInsert.minFontPx(fw, fh, brief.size) + " px",
            listOf("" to "M", "s" to "S", "l" to "L"), brief.size) { v -> brief = brief.copy(size = v) }
        if (k == EditInsert.KIND) {
            label(c, t("Похоже на…", "Looks like…"))
            val like = edt(ui, t("например: чистая научпоп-инфографика", "e.g. clean science infographics"), brief.like)
            like.watch { v -> brief = brief.copy(like = v) }
            c.add(like, 2)
            label(c, t("Не делать", "Don't"))
            c.chips(EditInsert.DONTS.map { d ->
                chip(ui, dontName(d), d in brief.donts) { set { b -> b.copy(donts = if (d in b.donts) b.donts - d else b.donts + d) } }
            })
            val dont = edt(ui, t("своё: например, без красного", "own: e.g. no red"), brief.dont)
            dont.watch { v -> brief = brief.copy(dont = v) }
            c.add(dont, 2)
        } else c.muted(t("Вид схемы рисует Lumen: его можно менять и после вставки, без ИИ.", "Lumen draws the scheme's look: it can be changed after inserting too, without AI."))
    }

    private fun tri(v: Boolean?) = when (v) { null -> t("Авто", "Auto"); true -> t("Да", "Yes"); false -> t("Нет", "No") }

    private fun dontName(d: String) = when (d) {
        "emoji" -> t("без эмодзи", "no emoji"); "arrows" -> t("без стрелок", "no arrows"); "two_colors" -> t("≤ 2 цветов", "≤ 2 colours")
        "gradients" -> t("без градиентов", "no gradients"); else -> t("без теней", "no shadows")
    }

    private fun accentPicker(c: Col) {
        label(c, t("Акцентный цвет", "Accent colour"))
        val chips = mutableListOf<View>()
        chips += chip(ui, t("Как в ролике", "As the video"), brief.accent == null) { set { it.copy(accent = null) } }
        for (s in SWATCHES) chips += swatch(s, brief.accent == s) { set { it.copy(accent = s) } }
        chips += chip(ui, t("💧 С кадра", "💧 From the frame")) { eyedropper { col -> set { it.copy(accent = col) } } }
        chips += chip(ui, "HEX") {
            ui.prompt(t("Цвет", "Colour"), "#FFD60A", brief.accent?.let { hexOf(it) }.orEmpty()) { s ->
                runCatching { Color.parseColor(if (s.trim().startsWith("#")) s.trim() else "#" + s.trim()) }.getOrNull()?.let { col -> set { it.copy(accent = col or 0xFF000000.toInt()) } }
                    ?: say(t("Не цвет", "Not a colour"))
            }
        }
        c.chips(chips, 20)
    }

    private fun swatch(color: Int, selected: Boolean, on: () -> Unit): View = View(ui).apply {
        background = roundBg(color, 16f, ui, if (selected) Palette.ON_SURFACE else Palette.OUTLINE)
        minimumWidth = ui.dp(40)
        minimumHeight = ui.dp(40)
        contentDescription = hexOf(color) + if (selected) t(", выбрано", ", selected") else ""
        setOnClickListener { on() }
        layoutParams = ViewGroup.LayoutParams(ui.dp(40), ui.dp(40))
    }

    /** «💧»: a tap on the frame picks its colour. */
    @SuppressLint("ClickableViewAccessibility")
    private fun eyedropper(on: (Int) -> Unit) {
        val b = frameBmp ?: return say(t("Кадра пока нет — включите видео и остановите на нужном месте", "No frame yet — play the video and stop where needed"))
        ui.bottomSheet(t("💧 Коснитесь цвета на кадре", "💧 Tap a colour on the frame")) { dismiss ->
            val iv = ImageView(ctx).apply {
                setImageBitmap(b)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        val k = min(v.width / b.width.toFloat(), v.height / b.height.toFloat())
                        val ox = (v.width - b.width * k) / 2
                        val oy = (v.height - b.height * k) / 2
                        val x = ((e.x - ox) / k).toInt().coerceIn(0, b.width - 1)
                        val y = ((e.y - oy) / k).toInt().coerceIn(0, b.height - 1)
                        on(b.getPixel(x, y) or 0xFF000000.toInt())
                        dismiss()
                    }
                    true
                }
            }
            add(iv, 8).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(360))
        }
    }

    private fun plate(c: Col) {
        choice(c, t("Подложка", "Plate"), listOf("", "none", "glass", "semi", "solid", "full").map { it to bgName(it) }, brief.bg) { v -> brief = brief.copy(bg = v) }
        slider(c, t("Прозрачность подложки", "Plate opacity"), if (brief.bgAlpha >= 0) brief.bgAlpha.toFloat() else EditInsert.alphaOf(brief.bg, -1) * 100, 0f, 100f,
            { "${it.roundToInt()} %" }) { v, done -> if (done) set { it.copy(bgAlpha = v.roundToInt()) } }
        if (brief.bgAlpha >= 0) c.add(chip(ui, t("↺ Прозрачность по стилю", "↺ Opacity by the style")) { set { it.copy(bgAlpha = -1) } }, 4)
    }

    private fun slider(c: Col, name: String, value: Float, from: Float, to: Float, fmt: (Float) -> String, onMove: (Float, Boolean) -> Unit): SeekBar {
        val head = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(ui, name, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(ui, fmt(value), 14f, bold = true, color = Palette.ACCENT)
        head.addView(v)
        c.add(head, 14)
        fun at(p: Int) = from + (to - from) * p / 1000f
        val bar = SeekBar(ui).apply {
            max = 1000
            progress = ((value - from) / (to - from) * 1000).roundToInt().coerceIn(0, 1000)
            contentDescription = name
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    v.text = fmt(at(p))
                    if (fromUser) onMove(at(p), false)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) = onMove(at(s?.progress ?: 0), true)
            })
        }
        c.add(bar, 2)
        return bar
    }

    private fun alphaSummary(): String = listOfNotNull(
        if (brief.opacity < 0.999f) t("блок ", "block ") + "${(brief.opacity * 100).roundToInt()} %" else null,
        brief.bg.takeIf { it.isNotEmpty() }?.let { bgName(it) },
        if (brief.dim > 0) t("видео −", "video −") + "${(brief.dim * 100).roundToInt()} %" + (if (brief.dimAll) t(" (весь кадр)", " (whole frame)") else "") else null
    ).joinToString(" · ").ifEmpty { t("блок непрозрачный, видео как есть", "opaque block, the video as it is") }

    private fun sectionAlpha(c: Col) {
        slider(c, t("Прозрачность блока", "Block opacity"), brief.opacity * 100, 10f, 100f, { "${it.roundToInt()} %" }) { v, done -> if (done) set { it.copy(opacity = v / 100f) } }
        plate(c)
        choice(c, t("Затемнить видео", "Dim the video"), listOf(0f to "0", 0.2f to "20 %", 0.4f to "40 %", 0.6f to "60 %"), brief.dim) { v -> brief = brief.copy(dim = v) }
        if (brief.dim > 0) choice(c, t("Где", "Where"), listOf(false to t("Под блоком", "Under the block"), true to t("Весь кадр", "Whole frame")), brief.dimAll) { v -> brief = brief.copy(dimAll = v) }
        c.muted(t("Затемнение плавно появляется и уходит вместе с блоком.", "The dimming fades in and out with the block."))
        readability(c)
    }

    private fun readability(c: Col) {
        if (frameLum < 0) return
        val st = SchemeStyles.find(h.styleDir(), brief.style.ifEmpty { SchemeStyles.AUTO })
        val ink = st?.ink ?: 0xFFFFFFFF.toInt()
        val card = st?.bg ?: 0xFF101318.toInt()
        val k = EditInsert.contrastOn(frameLum, ink, card, EditInsert.alphaOf(brief.bg, brief.bgAlpha), brief.dim)
        c.add(text(ui, (if (k >= 4.5) "✅ " else "⚠ ") + t("Читаемость на этом кадре: ", "Readability on this frame: ") +
            String.format("%.1f:1", k) + if (k >= 4.5) t(" — хорошо", " — good") else t(" — мало (нужно 4.5:1)", " — low (4.5:1 needed)"), 14f), 10)
    }

    private fun motionSummary(cx: InsertCtx): String = listOfNotNull(
        StudioEdit.secs(cx.durMs) + t(" с", " s"),
        when (brief.pace) { "calm" -> t("спокойно", "calm"); "normal" -> t("обычно", "normal"); "drive" -> t("драйв", "drive"); else -> null },
        when (brief.reveal) { "words" -> t("на словах", "on words"); "steps" -> t("по очереди", "one by one"); "all" -> t("всё сразу", "all at once"); else -> null },
        if (brief.loopS > 0) t("цикл ${brief.loopS} с", "loop ${brief.loopS} s") else null,
        when (brief.sound) { "clicks" -> t("щелчки", "clicks"); "whoosh" -> t("свист", "whoosh"); else -> null }
    ).joinToString(" · ")

    private fun inName(s: String) = when (s) {
        "none" -> t("Нет", "None"); "fade" -> t("Плавно", "Fade"); "pop" -> t("Пружина", "Pop"); "slide" -> t("Снизу", "Slide"); "zoom" -> t("Наезд", "Zoom")
        "code" -> t("В коде ИИ", "In the AI code"); else -> t("Авто", "Auto")
    }

    private fun sectionMotion(c: Col, k: String, cx: InsertCtx) {
        val need = EditInsert.readMs(EditInsert.screenWords(brief))
        choice(c, t("Длина", "Length") + " · " + t("для чтения нужно ≈ ", "reading takes ≈ ") + StudioEdit.secs(need) + t(" с", " s"),
            listOf(0L to (if (span != null) t("По фразе", "By the phrase") else t("Авто", "Auto")), 3000L to "3 с", 5000L to "5 с", 8000L to "8 с", 12_000L to "12 с", -1L to t("✎ Своя", "✎ Own")),
            if (brief.lenMs in setOf(0L, 3000L, 5000L, 8000L, 12_000L)) brief.lenMs else -1L) { v ->
            if (v == -1L) ui.prompt(t("Длина, секунд", "Length, seconds"), "6.5", StudioEdit.secs(cx.durMs)) { s -> StudioEdit.parseSeconds(s)?.let { ms -> set { it.copy(lenMs = ms) } } }
            else brief = brief.copy(lenMs = v)
        }
        val ins = if (k == EditInsert.KIND) listOf("", "none", "fade", "pop", "slide", "zoom") else listOf("", "none", "fade", "pop", "slide", "zoom")
        choice(c, t("Появление блока", "Block in"), ins.map { it to if (it.isEmpty() && k == EditInsert.KIND) inName("code") else if (it.isEmpty()) inName("pop") + " (" + t("авто", "auto") + ")" else inName(it) },
            brief.animIn) { v -> brief = brief.copy(animIn = v) }
        choice(c, t("Раскрытие элементов", "Reveal of the items"), listOf("" to t("Авто", "Auto"), "words" to t("На словах речи", "On the spoken words"), "steps" to t("По очереди", "One by one"), "all" to t("Всё сразу", "All at once")),
            brief.reveal) { v -> brief = brief.copy(reveal = v) }
        if (k == EditInsert.KIND) {
            choice(c, t("Темп", "Pace"), listOf("" to t("Авто", "Auto"), "calm" to t("Спокойно", "Calm"), "normal" to t("Обычно", "Normal"), "drive" to t("Драйв", "Drive")), brief.pace) { v -> brief = brief.copy(pace = v) }
            c.check(t("Акцент на главном (на его слове)", "Accent the main item (on its word)"), brief.accentMain) { v -> brief = brief.copy(accentMain = v) }
            choice(c, t("Держать финал", "Hold the end"), listOf(-1L to t("Авто", "Auto"), 0L to "0", 1000L to "1 с", 2000L to "2 с", 3000L to "3 с"), brief.holdMs) { v -> brief = brief.copy(holdMs = v) }
            choice(c, t("Повтор", "Loop"), listOf(0 to t("Нет", "No"), 1 to "1 с", 2 to "2 с", 3 to "3 с", 4 to "4 с"), brief.loopS) { v -> brief = brief.copy(loopS = v) }
        }
        choice(c, t("Исчезновение", "Block out"), ins.map { it to if (it.isEmpty() && k == EditInsert.KIND) inName("code") else if (it.isEmpty()) inName("fade") + " (" + t("авто", "auto") + ")" else inName(it) },
            brief.animOut) { v -> brief = brief.copy(animOut = v) }
        choice(c, t("Звук", "Sound"), listOf("" to t("Нет", "None"), "clicks" to t("Щелчки на появлении", "Clicks on reveal"), "whoosh" to t("Свист на входе", "Whoosh on enter")), brief.sound) { v -> brief = brief.copy(sound = v) }
    }

    private fun placeSummary(p: EProject): String {
        val (fw, fh) = frameSize(p)
        val r = EditInsert.place(brief, fw, fh)
        val (bw, bh) = EditInsert.pixels(r, fw, fh)
        return EditInsert.placeName(brief.place.ifEmpty { EditInsert.defaultPlace(brief, fw, fh) }) + " · " + bw + "×" + bh +
            (if (brief.face != null && brief.avoidFace != false) t(" · не закрывает лицо", " · keeps off the face") else "")
    }

    private fun sectionPlace(c: Col, p: EProject) {
        val (fw, fh) = frameSize(p)
        choice(c, t("Место", "Place"), listOf("" to t("Авто", "Auto"), "full" to t("Полный кадр", "Full frame"), "cut" to t("Перебивка", "Cutaway"), "top" to t("Сверху", "Top"),
            "center" to t("Центр", "Centre"), "bottom" to t("Снизу", "Bottom"), "corner" to t("Угол", "Corner"), "custom" to t("📍 Указать на макете", "📍 Point on the mock-up")), brief.place) { v ->
            brief = if (v == "custom") brief.copy(place = v, rect = brief.rect ?: EditInsert.place(brief, fw, fh)) else brief.copy(place = v)
        }
        if (brief.place == "custom") c.muted(t("Двигайте рамку пальцем на макете вверху.", "Drag the frame on the mock-up above."))
        choice(c, t("Размер", "Size"), listOf("" to "M", "s" to "S", "l" to "L", "xl" to "XL"), brief.scale) { v -> brief = brief.copy(scale = v, rect = if (brief.place == "custom") null else brief.rect, place = if (brief.place == "custom") "" else brief.place) }
        choice(c, t("Форма", "Shape"), listOf("" to t("Авто", "Auto"), "wide" to t("Широкая", "Wide"), "square" to t("Квадрат", "Square"), "tall" to t("Высокая", "Tall")), brief.shape) { v -> brief = brief.copy(shape = v) }
        label(c, t("Не закрывать", "Keep free"))
        c.check(t("Лицо", "Face") + if (brief.face != null) t(" (найдено на кадре)", " (found on the frame)") else t(" (не найдено)", " (not found)"), brief.avoidFace != false) { v -> set { it.copy(avoidFace = v) } }
        c.check(t("Субтитры снизу", "Subtitles at the bottom"), brief.avoidSubs ?: subsInVideo(p)) { v -> set { it.copy(avoidSubs = v) } }
        c.check(t("Интерфейс Shorts", "Shorts interface"), brief.avoidUi ?: (fh > fw)) { v -> set { it.copy(avoidUi = v) } }
        choice(c, t("Слой", "Layer"), listOf("" to t("Поверх всего", "On top"), "under" to t("Под надписями", "Under captions")), brief.layer) { v -> brief = brief.copy(layer = v) }
    }

    private fun sectionBrand(c: Col, p: EProject) {
        c.muted(t("Вид, прозрачность и темп этого брифа можно сделать стилем всего ролика: он подставится во все новые схемы и анимации.",
            "The look, transparency and pace of this brief can become the style of the whole video: new schemes and animations start from it."))
        c.row(
            btn(ui, t("Запомнить как стиль ролика", "Keep as the video style"), style = BtnStyle.TONAL) {
                h.setBrand(EditInsert.briefJson(EditInsert.brandOf(brief)).toString())
                say(t("Стиль ролика сохранён", "Video style saved"))
                redraw()
            }
        )
        if (p.brand.isNotEmpty()) c.row(btn(ui, t("Сбросить стиль ролика", "Clear the video style"), style = BtnStyle.TEXT) {
            h.setBrand("")
            redraw()
        })
        c.row(btn(ui, t("Применить ко всем схемам и анимациям", "Apply to all schemes and animations"), style = BtnStyle.TEXT) { applyAll() })
        c.row(btn(ui, t("⭐ Сохранить как рецепт", "⭐ Save as a recipe"), style = BtnStyle.TEXT) {
            ui.prompt(t("Название рецепта", "Recipe name"), EditInsert.recipeName(brief), EditInsert.recipeName(brief)) { name ->
                saveRecipe(name.ifBlank { EditInsert.recipeName(brief) }, EditInsert.recipeOf(brief))
                say(t("Рецепт сохранён", "Recipe saved"))
            }
        })
    }

    private fun applyAll() {
        val look = EditInsert.lookOf(brief)
        val list = h.blocks().map { b ->
            if (b.kind == "scheme" || b.kind == EditInsert.KIND) b.copy(over = look, dim = brief.dim, dimAll = brief.dimAll, opacity = brief.opacity,
                look = if (b.kind == "scheme" && brief.style.isNotEmpty()) brief.style else b.look) else b
        }
        h.commit(list, null)
        say(t("Применено", "Applied"))
    }

    // ---------- theme, flipbook, parameters ----------

    private fun themeRow(c: Col, p: EProject) {
        val list = EditThemes.list(h.themesDir())
        label(c, t("🎨 Тема", "🎨 Theme"))
        c.add(choiceChips(ui, listOf("" to (if (p.theme.isNotEmpty()) t("Тема ролика", "Video theme") else t("Без темы", "No theme"))) + list.map { it.id to it.name },
            brief.theme) { v -> set { it.copy(theme = v) } }, 4)
    }

    private fun draft(): Pair<EditBrief, String>? = runCatching {
        val o = JSONObject(Lumen.prefs.getString(PREF_DRAFT, "") ?: return null)
        EditInsert.briefFrom(o.optJSONObject("brief"))?.let { it to o.optString("wish") }
    }.getOrNull()

    /** A small picture of the current frame to draw over. */
    private fun base(pw: Int, ph: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(0xFF202024.toInt())
        frameBmp?.let { cv.drawBitmap(it, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return bmp to cv
    }

    /** The native way of this brief, played: 12 frames by the editor painter over the current frame. */
    private fun flipbook(p: EProject, cx: InsertCtx, nat: Pair<String, Any>): View {
        val (fw, fh) = frameSize(p)
        val pw = 320
        val ph = (pw * fh / fw.toFloat()).roundToInt().coerceAtLeast(2)
        val b0 = blockFor(p, null, null).copy(id = "preview", req = "")
        val draft = if (nat.first == EditMotion.KIND) b0.copy(kind = EditMotion.KIND, look = nat.second as String, animIn = "none", animOut = "none")
            else b0.copy(kind = "scheme", scheme = nat.second as SScheme)
        val painter = StudioEditPainter(pw, ph, h.dir(), words, StudioPrefs.accent, h.styleDir())
        val frames = (0 until 12).map { i ->
            val (bmp, cv) = base(pw, ph)
            runCatching { painter.draw(cv, listOf(draft), draft.startMs + (draft.durMs * (i + 0.5) / 12).toLong()) }
            bmp
        }
        painter.close()
        return flipView(frames, pw, ph)
    }

    /** The trial frames of an animation in their place over the current frame, played. */
    private fun flipOf(p: EProject, cx: InsertCtx, fr: List<Bitmap>): View {
        val (fw, fh) = frameSize(p)
        val pw = 320
        val ph = (pw * fh / fw.toFloat()).roundToInt().coerceAtLeast(2)
        val r = cx.block
        val frames = fr.map { f ->
            val (bmp, cv) = base(pw, ph)
            if (brief.dim > 0) {
                val dp = Paint().apply { color = Color.argb((brief.dim * 255).toInt(), 0, 0, 0) }
                if (brief.dimAll) cv.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), dp) else cv.drawRect(r.l * pw, r.t * ph, r.r * pw, r.b * ph, dp)
            }
            cv.drawBitmap(f, null, RectF(r.l * pw, r.t * ph, r.r * pw, r.b * ph), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (brief.opacity * 255).toInt() })
            bmp
        }
        return flipView(frames, pw, ph)
    }

    private fun flipView(frames: List<Bitmap>, pw: Int, ph: Int): View {
        val maxW = ui.resources.displayMetrics.widthPixels - ui.dp(40)
        val maxH = ui.dp(260)
        val k = min(maxW / pw.toFloat(), maxH / ph.toFloat())
        val iv = ImageView(ui).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = t("Превью движения", "Motion preview")
            frames.firstOrNull()?.let { setImageBitmap(it) }
        }
        flipView?.removeCallbacks(flipTick)
        flipFrames = frames
        flipIndex = 0
        flipView = iv
        iv.postDelayed(flipTick, 1000L / 12)
        return android.widget.FrameLayout(ui).apply {
            addView(iv, android.widget.FrameLayout.LayoutParams((pw * k).toInt(), (ph * k).toInt(), Gravity.CENTER_HORIZONTAL))
        }
    }

    /** «🎛 Параметры»: what the animation lets change without AI (lumen.anim v1.1 `// param`). */
    private fun paramsPanel(c: Col, a: InsertAnswer.Anim) {
        val hd = AnimV11.header(a.code)
        if (hd.params.isEmpty()) return
        label(c, t("🎛 Параметры (без ИИ)", "🎛 Parameters (no AI)"))
        for (pr in hd.params) {
            val name = pr.label.ifBlank { pr.name }
            val v = brief.params[pr.name] ?: pr.def
            fun put(x: String) {
                brief = brief.copy(params = brief.params + (pr.name to x))
            }
            when (pr.kind) {
                "text" -> {
                    label(c, name)
                    val e = edt(ui, name, v)
                    e.watch { put(it) }
                    c.add(e, 2)
                }
                "bool" -> c.check(name, v == "true") { put(if (it) "true" else "false") }
                "range" -> slider(c, name, v.toFloatOrNull() ?: pr.min.toFloat(), pr.min.toFloat(), pr.max.toFloat(), { String.format("%.2f", it) }) { x, done -> if (done) put(x.toString()) }
                "choice" -> choice(c, name, pr.options.map { it to it }, v) { put(it) }
                "color" -> {
                    label(c, name)
                    c.chips(listOf(chip(ui, t("Тема", "Theme"), v.startsWith("theme.")) { put(pr.def.takeIf { it.startsWith("theme.") } ?: "theme.accent"); redraw() }) +
                        SWATCHES.map { col -> swatch(col, v.equals(hexOf(col), true)) { put(hexOf(col)); redraw() } }, 20)
                }
            }
        }
        c.add(btn(ui, t("↻ Обновить кадры с параметрами", "↻ Refresh the frames with the parameters"), style = BtnStyle.TONAL) { runTrial() }, 8)
    }

    // ---------- the mock-up ----------

    private fun mockView(p: EProject, cx: InsertCtx): View {
        val (fw, fh) = frameSize(p)
        val v = MockView(ui, frameBmp, fw, fh)
        v.block = cx.block
        v.zones = EditInsert.zones(brief, fw, fh, false) + cx.zones
        v.plate = EditInsert.alphaOf(brief.bg, brief.bgAlpha)
        v.dim = brief.dim
        v.dimAll = brief.dimAll
        v.opacity = brief.opacity
        v.accent = brief.accent ?: StudioPrefs.accent
        v.label = EditInsert.pixels(cx.block, fw, fh).let { (w, hh) -> "$w×$hh" }
        if (brief.place == "custom") v.onMove = { r ->
            brief = brief.copy(rect = r)
        }
        v.onMoved = {
            lum()
            redraw()
        }
        val maxW = ui.resources.displayMetrics.widthPixels - ui.dp(40)
        val maxH = ui.dp(300)
        val k = min(maxW / fw.toFloat(), maxH / fh.toFloat())
        v.contentDescription = t("Макет: ", "Mock-up: ") + placeSummary(p)
        // Col.add gives its child the full width: the mock-up keeps the shape of the frame inside a centring box
        return android.widget.FrameLayout(ui).apply {
            addView(v, android.widget.FrameLayout.LayoutParams((fw * k).toInt(), (fh * k).toInt(), Gravity.CENTER_HORIZONTAL))
        }
    }

    // ---------- warnings ----------

    private fun warnings(): List<InsertWarn> {
        val p = h.project() ?: return emptyList()
        val st = SchemeStyles.find(h.styleDir(), brief.style.ifEmpty { SchemeStyles.AUTO })
        return EditInsert.warnings(brief, ctx(p), frameLum, st?.ink ?: 0xFFFFFFFF.toInt(), st?.bg ?: 0xFF101318.toInt())
    }

    private fun warningsView(c: Col, cx: InsertCtx) {
        for (w in warnings()) {
            c.add(text(ui, (if (w.blocking) "⛔ " else "⚠ ") + w.text, 14f, bold = w.blocking), 12)
            if (w.fixes.isNotEmpty()) c.chips(w.fixes.map { (id, name) ->
                chip(ui, name) { set { EditInsert.fix(it, id, cx) } }
            })
        }
    }

    // ---------- step 3: who makes it ----------

    private fun toWho() {
        val p = h.project() ?: return
        if (req.isEmpty()) req = newReq()
        request = EditInsert.request(ctx(p), brief, kind(), variants)
        saveRequest()
        saveLast()
        step = 2
        redraw(false)
    }

    /** The request in insert/<mark>.txt: «⧉ Запрос ещё раз» from the «⏳» block after the sheet is gone. */
    private fun saveRequest() = runCatching {
        File(h.dir(), "insert").apply { mkdirs() }.let { File(it, "$req.txt").writeText(request) }
    }

    private fun taskKey(fix: Boolean) = if (kind() == EditInsert.KIND) (if (fix) "studio_edit_anim_fix" else "studio_edit_anim") else (if (fix) "studio_edit_insert_fix" else "studio_edit_insert")

    private fun stepWho(c: Col) {
        if (busy.isNotEmpty()) {
            c.working(busy)
            c.muted(t("Можно закрыть лист: результат встанет в блок «⏳» на таймлайне.", "You can close the sheet: the result goes into the «⏳» block on the timeline."))
            return
        }
        val prov = Provider.active()
        val manual = prov == Provider.MANUAL || Relay.routes(taskKey(false))
        c.add(text(ui, t("Кто сделает?", "Who makes it?"), Type.TITLE_M, bold = true), 12)
        label(c, t("Вариантов", "Variants"))
        c.add(choiceChips(ui, listOf(1 to "1", 3 to t("🎲 3 сразу", "🎲 3 at once")), variants) { v ->
            variants = v
            h.project()?.let { request = EditInsert.request(ctx(it), brief, kind(), variants) }
            saveRequest()
            redraw()
        }, 4)
        if (prov != null) {
            val name = prov.title.let { if (Settings.lang == "en") it.substringAfter(" / ") else it.substringBefore(" / ") }
            c.add(btn(ui, t("🤖 Встроенный ИИ", "🤖 Built-in AI") + " (" + name + ")", primary = true) { runAi(null, "") }, 8)
            if (manual) c.muted(t("Запрос уйдёт через «другой ИИ вручную» — так настроено в разделе AI.", "The request goes through \"another AI by hand\" — as set in the AI settings."))
        } else {
            c.muted(t("Встроенный ИИ не настроен.", "No built-in AI is set up."))
            c.add(btn(ui, t("Настроить ИИ", "Set up the AI"), style = BtnStyle.TEXT) { ui.startActivity(Intent(ui, SettingsActivity::class.java)) }, 4)
        }
        c.divider()
        c.add(text(ui, t("⧉ Другой ИИ (копировать → вставить)", "⧉ Another AI (copy → paste)"), Type.TITLE_M, bold = true), 12)
        c.muted(t("Запрос самодостаточный: вставьте его в Gemini, ChatGPT или Claude, скопируйте ответ и вернитесь — Lumen возьмёт его из буфера сам. На таймлайне сразу появится блок «⏳ ждёт ответ ИИ #$req» — можно уйти из приложения.",
            "The request is self-contained: paste it into Gemini, ChatGPT or Claude, copy the answer and come back — Lumen takes it from the clipboard. A «⏳ awaits AI answer #$req» block appears on the timeline at once — you may leave the app."))
        c.add(btn(ui, if (copied) t("✓ Скопировано — ещё раз", "✓ Copied — again") else t("⧉ Скопировать запрос", "⧉ Copy the request"), style = BtnStyle.TONAL) {
            copyRequest()
            redraw()
        }, 8)
        c.row(
            btn(ui, "Gemini", style = BtnStyle.OUTLINED) { chat("https://gemini.google.com/app") },
            btn(ui, "ChatGPT", style = BtnStyle.OUTLINED) { chat("https://chatgpt.com/") },
            btn(ui, "Claude", style = BtnStyle.OUTLINED) { chat("https://claude.ai/new") }
        )
        c.add(btn(ui, t("↗ Поделиться запросом", "↗ Share the request"), style = BtnStyle.TEXT) {
            copyRequest()
            ui.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, request), t("Запрос в ИИ", "Request to an AI")))
        }, 4)
        label(c, t("📥 Ответ ИИ", "📥 AI answer"))
        val paste = edt(ui, t("вставьте ответ сюда", "paste the answer here"), "", multiline = true)
        c.add(paste, 4)
        c.row(
            btn(ui, t("Из буфера", "From the clipboard"), style = BtnStyle.TONAL) { clip()?.let { take(it) } ?: say(t("Буфер пуст", "The clipboard is empty")) },
            btn(ui, t("Принять", "Accept"), style = BtnStyle.TONAL) { take(paste.text.toString()) }
        )
    }

    private fun clipCheck() {
        if (step != 2 || busy.isNotEmpty() || !copied) return
        val c = clip() ?: return
        if (c == Relay.lastTaken || RelayText.isOwnRequest(c)) return
        val all = AnimV11.detectAll(c)
        val a = all.firstOrNull() ?: return
        if (a.req.isNotEmpty() && a.req != req) return
        Relay.lastTaken = c
        source = "paste"
        if (all.size > 1) onAnswers(all, false) else onAnswer(a, false)
    }

    private fun chat(url: String) {
        copyRequest()
        say(t("Запрос скопирован — вставьте его в чат", "Request copied — paste it into the chat"))
        ui.openUrl(url)
    }

    private fun clipboard() = ui.getSystemService(ClipboardManager::class.java)

    private fun clip(): String? = try {
        clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ui)?.toString()
    } catch (e: Exception) {
        null
    }

    private fun copyRequest() {
        saveRequest()
        runCatching { clipboard()?.setPrimaryClip(ClipData.newPlainText("lumen", request)) }
        copied = true
        ensurePending()
    }

    /** The «⏳» block on the timeline: a new block waits with the mark; a block being redone keeps its content and gets the mark. */
    private fun ensurePending() {
        val p = h.project() ?: return
        val id = target
        if (id != null) {
            val b = p.blocks.firstOrNull { it.id == id } ?: return
            if (b.req != req) h.commit(p.blocks.map { if (it.id == id) it.copy(req = req) else it }, id)
            return
        }
        if (p.blocks.any { it.req == req }) return
        val b = blockFor(p, null, null)
        target = b.id
        h.commit(placed(p.blocks, b), b.id)
    }

    private fun take(text: String) {
        val all = AnimV11.detectAll(text)
        if (all.size > 1 && all.all { it.req.isEmpty() || it.req == req }) {
            Relay.lastTaken = text
            source = "paste"
            onAnswers(all, false)
            return
        }
        val a = all.firstOrNull()
        if (a == null) {
            if (RelayText.isOwnRequest(text)) say(t("Это ваш запрос — скопируйте ответ ИИ", "That is your request — copy the AI answer"))
            else say(t("Не похоже на схему или код анимации", "Does not look like a scheme or animation code"))
            return
        }
        if (a.req.isNotEmpty() && a.req != req) return say(t("Это ответ на другой запрос (#${a.req})", "This answers another request (#${a.req})"))
        Relay.lastTaken = text
        source = "paste"
        onAnswer(a, false)
    }

    // ---------- the AI ----------

    private fun runAi(previous: String?, edit: String) {
        val p = h.project() ?: return
        if (BgTasks.has(TASK)) return say(t("ИИ уже работает", "The AI is already working"))
        ensurePending()
        val k = kind()
        val fix = previous != null
        val prompt = if (fix) EditInsert.fixRequest(request, k, problems, previous!!, edit) else request
        val range = StudioEdit.wordRange(words, timing().first, timing().first + timing().second)
        val pid = p.id
        val blockId = target
        val myReq = req
        busy = if (fix) t("ИИ правит…", "The AI is editing…") else t("ИИ рисует…", "The AI is drawing…")
        step = 2
        redraw(false)
        val many = !fix && variants > 1
        BgTasks.run(ui, TASK, t("Редактор: ИИ делает вставку", "Editor: the AI makes an insert"), BgTasks.reopen(ui), {
            val prov = AiTasks.requireProvider()
            fun ask(text: String, task: String): String = Llm.complete(prov, prov.smartModel, SYSTEM, Prompts.fill(task, mapOf("request" to text)),
                json = k == "scheme" && !many, maxTokens = (if (k == "scheme") 1800 else 9000) * (if (many) 2 else 1), task = task, pid = "studio").text
            fun fixed(x: InsertAnswer): InsertAnswer = if (x is InsertAnswer.Scheme) x.copy(scheme = StudioScheme.repair(x.scheme, range.takeIf { !it.isEmpty() })) else x
            var raw = ask(prompt, taskKey(fix))
            val all = if (many) AnimV11.detectAll(raw).filter { it.kind == k } else emptyList()
            if (all.size > 1) return@run Triple(all.map { fixed(it) }, all.all { check(it, range).isEmpty() }, prov.name)
            var a = parse(raw, k, myReq)
            var pr = check(a, range)
            val first = pr.isEmpty()
            if (pr.isNotEmpty()) {
                BgTasks.progress(TASK, t("Исправляю", "Fixing"))
                raw = ask(EditInsert.fixRequest(request, k, pr, raw.take(60_000), edit), taskKey(true))
                a = parse(raw, k, myReq) ?: a
                pr = check(a, range)
            }
            if (a == null || (a is InsertAnswer.Scheme && pr.isNotEmpty())) throw LlmException(t("ИИ не смог: ", "The AI could not: ") + pr.joinToString("; "), raw)
            Triple(listOf(fixed(a)), first, prov.name)
        }, { e ->
            busy = ""
            if (isOpen) redraw()
            if (!ui.isDestroyed) ui.err(e)
        }) { (list, first, provName) ->
            busy = ""
            source = "ai"
            provider = provName
            if (!first) {
                firstPass = false
                rounds++
            }
            val a = list.first()
            if (isOpen && req == myReq) onAnswers(list, true)
            else {
                // the sheet was closed: the result goes into its block
                h.deliverTo(pid) { pr, list, _ ->
                    val b = list.firstOrNull { it.id == blockId } ?: return@deliverTo list
                    list.map { if (it.id == b.id) withAnswer(pr, it, a) else it }
                }
                if (a is InsertAnswer.Anim && blockId != null) h.animChanged(blockId)
                if (!ui.isDestroyed) ui.snack(t("Готово: «", "Done: «") + a.name.take(30) + "»", t("Проверить", "Check")) {
                    h.blocks().firstOrNull { it.id == blockId }?.let { check(it) }
                }
            }
        }
    }

    /** The answer of the built-in AI: by the protocol, else the JSON of a scheme or the code of an animation as it is. */
    private fun parse(raw: String, k: String, mark: String): InsertAnswer? = EditInsert.detect(raw)?.takeIf { it.kind == k } ?: when (k) {
        "scheme" -> StudioEdit.parseScheme(raw)?.let { InsertAnswer.Scheme(it, mark, it.title) }
        else -> WebLayer.extract(raw).takeIf { it.contains("frame") }?.let { InsertAnswer.Anim(it, mark, WebLayer.nameOf(it, t("Анимация", "Animation")), 0) }
    }

    private fun check(a: InsertAnswer?, range: IntRange): List<String> = when (a) {
        null -> listOf(t("ответ не в формате протокола", "the answer is not in the protocol format"))
        is InsertAnswer.Scheme -> StudioScheme.problems(a.scheme, range.takeIf { !it.isEmpty() })
        is InsertAnswer.Anim -> EditInsert.animProblems(a.code)
    }

    /** Several variants: the first is shown, the others are a tap away. */
    private fun onAnswers(list: List<InsertAnswer>, fromAi: Boolean) {
        answers = list
        list.firstOrNull()?.let { onAnswer(it, fromAi, keepVariants = true) }
    }

    private fun onAnswer(a: InsertAnswer, fromAi: Boolean, keepVariants: Boolean = false) {
        if (!keepVariants) answers = emptyList()
        answer = a
        trial = null
        val range = StudioEdit.wordRange(words, timing().first, timing().first + timing().second)
        problems = check(a, range)
        if (a is InsertAnswer.Scheme && problems.isNotEmpty()) answer = a.copy(scheme = StudioScheme.repair(a.scheme, range.takeIf { !it.isEmpty() }))
        if (kind() != a.kind) brief = brief.copy(draw = a.kind)
        step = 3
        if (!isOpen) open() else redraw(false)
        if (a is InsertAnswer.Anim) runTrial(autoFix = fromAi)
    }

    /** Four frames of the animation through the WebView of the editor: errors, speed, empty, opaque. */
    private fun runTrial(autoFix: Boolean = false) {
        val p = h.project() ?: return
        val a = answer as? InsertAnswer.Anim ?: return
        val cx = ctx(p)
        val hd = AnimV11.header(a.code)
        val data = EditInsert.animData(cx, brief, hd).put("fps", EditInsert.DEFAULT_FPS)
        val dur = data.optDouble("dur", cx.durMs / 1000.0)
        // 8 moments: the checks and a flipbook of the motion
        val times = (0 until 8).map { Math.round(dur * (0.06 + it * 0.125) * 100) / 100.0 }
        val zones = EditInsert.inBlock(cx.block, EditInsert.zones(brief, cx.fw, cx.fh, false, cx.platform) + cx.zones).filter { cx.block.w < 0.99f }
        busy = t("Пробный прогон…", "Trial run…")
        redraw()
        val host = h.canvas()
        val code = a.code
        val minFont = (EditInsert.minFontPx(cx.fw, cx.fh, brief.size) * data.optInt("w") / (cx.block.w * cx.fw).coerceAtLeast(1f)).toInt()
        val themed = cx.theme != null
        val font = themeFont(h.dir(), cx.theme)
        Lumen.async({ synchronized(host) { AnimRender.trial(host, code, data, times, zones.map { it.r }, font) } }, { e ->
            busy = ""
            problems = listOf(e.message ?: e.javaClass.simpleName)
            if (isOpen) redraw()
        }) { tr ->
            busy = ""
            trial = tr
            trialFor = code + "|" + data.toString().hashCode()
            problems = EditInsert.animProblems(code) + EditInsert.trialProblems(tr.msPerFrame, tr.errors, tr.allEmpty, tr.opaque, cx.block.w >= 0.99f && !hd.opaque) +
                AnimV11.reportProblems(tr.probe, minFont) +
                zones.mapIndexedNotNull { i, z -> tr.covered.getOrNull(i)?.takeIf { it > 0.08f }?.let { k -> t("закрывает «${z.why}» на ${(k * 100).toInt()} %", "covers «${z.why}» by ${(k * 100).toInt()} %") } } +
                (if (themed) EditInsert.themeProblems(code) else emptyList())
            if (autoFix && problems.isNotEmpty() && rounds < 2 && Provider.active() != null) {
                rounds++
                runAi(code, "")
                return@async
            }
            if (isOpen) redraw()
        }
    }

    // ---------- step 4: check and edits ----------

    private fun stepCheck(c: Col) {
        val p = h.project() ?: return
        val a = answer ?: run {
            c.muted(t("Ответа пока нет.", "No answer yet."))
            return
        }
        val cx = ctx(p)
        c.add(text(ui, (if (a is InsertAnswer.Scheme) "🧩 " else "🎞 ") + a.name.ifBlank { EditInsert.type(brief.type).name }, Type.TITLE_M, bold = true), 12)
        if (answers.size > 1) {
            label(c, t("Варианты", "Variants"))
            c.add(choiceChips(ui, answers.mapIndexed { i, x -> i to "${i + 1}. " + x.name.take(18) }, answers.indexOf(a).coerceAtLeast(0)) { i ->
                onAnswer(answers[i], false, keepVariants = true)
            }, 4)
        }
        if (busy.isNotEmpty()) c.working(busy)
        // frames of the result over the video, and the motion as a flipbook
        c.add(resultFrames(p, cx, a), 8)
        if (a is InsertAnswer.Anim) trial?.frames?.takeIf { it.size >= 4 }?.let { fr -> c.add(flipOf(p, cx, fr), 6) }
        if (a is InsertAnswer.Anim) paramsPanel(c, a)
        trial?.let { tr -> c.muted(t("Пробный прогон: ", "Trial run: ") + String.format("%.0f", tr.msPerFrame) + t(" мс/кадр", " ms/frame")) }
        if (problems.isNotEmpty()) {
            c.add(text(ui, "⚠ " + problems.joinToString("\n⚠ "), 14f), 10)
            c.row(
                btn(ui, t("🛠 Исправить (ИИ)", "🛠 Fix (AI)"), style = BtnStyle.TONAL) { runAi(body(a), "") },
                btn(ui, t("⧉ Ошибку — в другой ИИ", "⧉ Error — to another AI"), style = BtnStyle.TEXT) { copyFix(a, "") }
            )
        }
        warningsView(c, cx)

        label(c, t("Правки без ИИ", "Edits without AI"))
        slider(c, t("Прозрачность блока", "Block opacity"), brief.opacity * 100, 10f, 100f, { "${it.roundToInt()} %" }) { v, done -> if (done) set { it.copy(opacity = v / 100f) } }
        choice(c, t("Подложка", "Plate"), listOf("", "none", "glass", "semi", "solid", "full").map { it to bgName(it) }, brief.bg) { v -> brief = brief.copy(bg = v) }
        choice(c, t("Затемнить видео", "Dim the video"), listOf(0f to "0", 0.2f to "20 %", 0.4f to "40 %", 0.6f to "60 %"), brief.dim) { v -> brief = brief.copy(dim = v) }
        choice(c, t("Место", "Place"), listOf("" to t("Авто", "Auto"), "full" to t("Полный", "Full"), "top" to t("Сверху", "Top"), "center" to t("Центр", "Centre"),
            "bottom" to t("Снизу", "Bottom"), "corner" to t("Угол", "Corner")), if (brief.place == "custom") "custom" else brief.place) { v -> brief = brief.copy(place = v, rect = null) }
        choice(c, t("Размер", "Size"), listOf("" to "M", "s" to "S", "l" to "L", "xl" to "XL"), brief.scale) { v -> brief = brief.copy(scale = v, place = if (brief.place == "custom") "" else brief.place, rect = null) }
        choice(c, t("Длина", "Length"), listOf(0L to t("Авто", "Auto"), 3000L to "3 с", 5000L to "5 с", 8000L to "8 с", 12_000L to "12 с"), brief.lenMs) { v -> brief = brief.copy(lenMs = v) }
        if (a is InsertAnswer.Scheme) {
            choice(c, t("Размер текста", "Text size"), listOf("" to "M", "s" to "S", "l" to "L"), brief.size) { v -> brief = brief.copy(size = v) }
            accentPicker(c)
            choice(c, t("Шрифт", "Font"), listOf("" to t("Авто", "Auto"), "sans" to t("Обычный", "Regular"), "condensed" to t("Узкий", "Condensed"), "serif" to t("С засечками", "Serif"), "rounded" to t("Округлый", "Rounded")),
                brief.font) { v -> brief = brief.copy(font = v) }
            choice(c, t("Регистр", "Case"), listOf<Boolean?>(null, true).map { it to if (it == true) t("КАПС", "CAPS") else t("Как есть", "As is") }, brief.upper) { v -> brief = brief.copy(upper = v) }
            choice(c, t("Появление", "In"), listOf("", "none", "fade", "pop", "slide", "zoom").map { it to inName(it) }, brief.animIn) { v -> brief = brief.copy(animIn = v) }
        } else if (trial != null && trialFor != (a as? InsertAnswer.Anim)?.code + "|" + EditInsert.animData(cx, brief).put("fps", EditInsert.DEFAULT_FPS).toString().hashCode()) {
            c.add(btn(ui, t("↻ Обновить кадры под новый размер", "↻ Refresh the frames for the new size"), style = BtnStyle.TEXT) { runTrial() }, 4)
        }

        label(c, t("Правки через ИИ", "Edits through the AI"))
        val asks = listOf(t("Крупнее текст", "Bigger text"), t("Меньше слов", "Fewer words"), t("Медленнее", "Slower"), t("Быстрее", "Faster"),
            t("Другие цвета", "Other colours"), t("Без иконок", "No icons"), t("Проще", "Simpler"), t("Ярче акцент", "Stronger accent"))
        c.chips(asks.map { s -> chip(ui, s, aiEdit.contains(s)) { aiEdit = if (aiEdit.contains(s)) aiEdit.replace(s, "").replace(Regex("^[;\\s]+|[;\\s]+$"), "") else listOf(aiEdit, s).filter { it.isNotBlank() }.joinToString("; "); redraw() } }, 12)
        val own = edt(ui, t("Своя правка", "Own edit"), "")
        c.add(own, 4)
        fun editText() = listOf(aiEdit, own.text.toString().trim()).filter { it.isNotBlank() }.joinToString("; ")
        c.row(
            btn(ui, t("🤖 Переделать", "🤖 Redo"), style = BtnStyle.TONAL) {
                val e = editText()
                if (e.isBlank()) return@btn say(t("Выберите или напишите правку", "Pick or write an edit"))
                runAi(body(a), e)
            },
            btn(ui, t("⧉ Правку — в другой ИИ", "⧉ Edit — to another AI"), style = BtnStyle.TEXT) { copyFix(a, editText()) }
        )

        val more = mutableListOf<View>()
        if (a is InsertAnswer.Anim) more += chip(ui, t("✎ Код", "✎ Code")) {
            ui.prompt(t("Код анимации", "Animation code"), "function frame(t, ctx, W, H, data) { … }", a.code, multiline = true) { s ->
                if (s.isNotBlank()) onAnswer(a.copy(code = s.trim()), false)
            }
        }
        more += chip(ui, t("💾 В библиотеку", "💾 To the library")) {
            val lib = library(ui)
            val saved = lib.add(a.kind, body(a), a.name, wish, source, System.currentTimeMillis())
            thumbFor(p, cx, a)?.let { lib.saveThumb(saved.id, it) }
            say(t("Сохранено в библиотеку", "Saved to the library"))
        }
        val tb = target?.let { id -> h.blocks().firstOrNull { it.id == id } }
        if (tb != null && tb.history.isNotEmpty()) more += chip(ui, t("← Прошлая версия", "← Previous version")) {
            val old = tb.history.first()
            val prev = if (a is InsertAnswer.Scheme) StudioScheme.fromJson(old)?.let { InsertAnswer.Scheme(it, req, it.title) }
            else InsertAnswer.Anim(old, req, WebLayer.nameOf(old, a.name), 0)
            if (prev != null) onAnswer(prev, false)
        }
        c.chips(more, 9)
    }

    /** A picture of the result for the library: the middle of a scheme or of the trial frames over the current frame. */
    private fun thumbFor(p: EProject, cx: InsertCtx, a: InsertAnswer): Bitmap? = runCatching {
        val (fw, fh) = frameSize(p)
        val pw = 240
        val ph = (pw * fh / fw.toFloat()).roundToInt().coerceAtLeast(2)
        val (bmp, cv) = base(pw, ph)
        if (a is InsertAnswer.Scheme) {
            val draft = blockFor(p, null, a).copy(id = "thumb")
            val painter = StudioEditPainter(pw, ph, h.dir(), words, StudioPrefs.accent, h.styleDir())
            painter.draw(cv, listOf(draft), draft.startMs + draft.durMs * 9 / 10)
            painter.close()
        } else {
            val f = trial?.frames?.let { it.getOrNull(it.size / 2) } ?: return@runCatching null
            val r = cx.block
            cv.drawBitmap(f, null, RectF(r.l * pw, r.t * ph, r.r * pw, r.b * ph), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        bmp
    }.getOrNull()

    private fun body(a: InsertAnswer): String = when (a) {
        is InsertAnswer.Scheme -> StudioScheme.toJson(a.scheme).toString()
        is InsertAnswer.Anim -> a.code
    }

    private fun copyFix(a: InsertAnswer, edit: String) {
        val s = EditInsert.fixRequest(request.ifEmpty { h.project()?.let { EditInsert.request(ctx(it), brief, a.kind) }.orEmpty() }, a.kind, problems, body(a), edit)
        runCatching { clipboard()?.setPrimaryClip(ClipData.newPlainText("lumen", s)) }
        ensurePending()
        say(t("Запрос правки скопирован — вставьте его в ИИ и скопируйте ответ", "Edit request copied — paste it into the AI and copy the answer"))
    }

    /** The result over the current frame: a scheme at 3 moments through the editor painter, an animation — its trial frames. */
    private fun resultFrames(p: EProject, cx: InsertCtx, a: InsertAnswer): View {
        val row = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        val (fw, fh) = frameSize(p)
        val pw = 360
        val ph = (pw * fh / fw.toFloat()).roundToInt()
        val shots = mutableListOf<Bitmap>()
        val base = frameBmp
        fun canvasFor(): Pair<Bitmap, Canvas> {
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(0xFF202024.toInt())
            base?.let { cv.drawBitmap(it, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
            return bmp to cv
        }
        val draft = blockFor(p, null, a).copy(id = "preview")
        if (a is InsertAnswer.Scheme) {
            val painter = StudioEditPainter(pw, ph, h.dir(), words, StudioPrefs.accent, h.styleDir())
            for (k in listOf(0.3f, 0.6f, 0.95f)) {
                val (bmp, cv) = canvasFor()
                painter.draw(cv, listOf(draft), draft.startMs + (draft.durMs * k).toLong())
                shots += bmp
            }
            painter.close()
        } else {
            val tr = trial
            val r = cx.block
            for (fr in tr?.frames.orEmpty()) {
                val (bmp, cv) = canvasFor()
                if (brief.dim > 0) {
                    val dp = Paint().apply { color = Color.argb((brief.dim * 255).toInt(), 0, 0, 0) }
                    if (brief.dimAll) cv.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), dp) else cv.drawRect(r.l * pw, r.t * ph, r.r * pw, r.b * ph, dp)
                }
                cv.drawBitmap(fr, null, RectF(r.l * pw, r.t * ph, r.r * pw, r.b * ph), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (brief.opacity * 255).toInt() })
                shots += bmp
            }
            if (tr == null) return text(ui, t("Кадры появятся после пробного прогона…", "Frames appear after the trial run…"), 14f, color = Palette.MUTED)
        }
        val w = (ui.resources.displayMetrics.widthPixels - ui.dp(48)) / max(1, shots.size)
        for (b in shots) row.addView(ImageView(ui).apply {
            setImageBitmap(b)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(w, (w * ph / pw.toFloat()).toInt()).apply { marginEnd = ui.dp(4) })
        return android.widget.HorizontalScrollView(ui).apply { addView(row) }
    }

    // ---------- the block ----------

    private fun mapIn(v: String, k: String, default: String): String = when {
        v == "code" -> "none"
        v.isEmpty() -> if (k == EditInsert.KIND) "none" else default
        else -> v
    }

    /** The block of this brief: place, time, look, transparency; with [a] — its content. */
    private fun blockFor(p: EProject, base: EBlock?, a: InsertAnswer?): EBlock {
        val (fw, fh) = frameSize(p)
        val k = a?.kind ?: kind()
        val cx = ctx(p)
        var b = EBlock(base?.id ?: StudioEdit.nextId(p.blocks), k, cx.startMs, cx.durMs,
            opacity = brief.opacity, animIn = mapIn(brief.animIn, k, "pop"), animOut = mapIn(brief.animOut, k, "fade"),
            look = if (k == "scheme") brief.style else "", ask = wish, req = if (a == null) req else "",
            brief = EditInsert.briefJson(brief).toString(), over = EditInsert.lookOf(brief), dim = brief.dim, dimAll = brief.dimAll,
            history = base?.history.orEmpty())
        b = EditInsert.toBlock(b, cx.block, fw, fh)
        // the theme travels with the block: its frames and its look follow it
        cx.theme?.let { th -> b = b.copy(theme = EditThemes.json(th).toString(), over = if (k == "scheme") EditThemes.toLook(th) else b.over) }
        return if (a == null) b else withContent(p, b, a)
    }

    private fun withContent(p: EProject, b: EBlock, a: InsertAnswer): EBlock = when (a) {
        is InsertAnswer.Scheme -> b.copy(kind = "scheme", scheme = a.scheme, text = a.name, file = "", req = "", seg = "")
        is InsertAnswer.Anim -> {
            val hd = AnimV11.header(a.code)
            b.copy(kind = EditInsert.KIND, scheme = null, file = AnimRender.saveCode(h.dir(), a.code), text = a.name,
                loopMs = if (hd.hasSegments) 0 else if (a.loopMs > 0) a.loopMs else brief.loopS * 1000L, req = "",
                seg = AnimV11.segString(hd), fps = (if (hd.fps > 0) hd.fps else h.fps()).coerceIn(12, 60))
        }
    }

    /** The answer into a block already on the timeline (pending or being redone): the previous content goes to its history. */
    private fun withAnswer(p: EProject, b: EBlock, a: InsertAnswer): EBlock {
        val old = when {
            b.kind == "scheme" && b.scheme != null -> StudioScheme.toJson(b.scheme).toString()
            b.kind == EditInsert.KIND -> AnimRender.code(h.dir(), b)
            else -> null
        }
        val hist = (listOfNotNull(old) + b.history).distinct().take(3)
        return withContent(p, b, a).copy(history = hist)
    }

    private fun apply(p: EProject, b: EBlock, a: InsertAnswer) {
        val list = p.blocks.map { if (it.id == b.id) withAnswer(p, it, a) else it }
        h.commit(list, b.id)
        if (a is InsertAnswer.Anim) h.animChanged(b.id)
    }

    /** Where a new block goes: on top, or under the captions. */
    private fun placed(list: List<EBlock>, b: EBlock): List<EBlock> {
        if (brief.layer != "under") return list + b
        val i = list.indexOfFirst { it.kind == "text" }
        return if (i < 0) list + b else list.subList(0, i) + b + list.subList(i, list.size)
    }

    /** «✅ Вставить»: one undo step; the animation is rendered on the block itself, the sounds are added. */
    private fun insert() {
        val p = h.project() ?: return
        val a = answer ?: return
        val id = target
        val old = id?.let { i -> p.blocks.firstOrNull { it.id == i } }
        val fresh = blockFor(p, old, a)
        val made = if (old != null) withAnswer(p, old, a).let { w ->
            fresh.copy(history = w.history)
        } else fresh
        var list = if (old != null) p.blocks.map { if (it.id == old.id) made else it } else placed(p.blocks, made)
        list = withSounds(p, list, made)
        h.commit(list, made.id)
        saveLast()
        inserted = true
        Lumen.prefs.edit().remove(PREF_DRAFT).apply()
        h.stat(InsertStat(made.kind, source, provider, firstPass, rounds, 0, System.currentTimeMillis()))
        if (a is InsertAnswer.Anim) h.animChanged(made.id)
        close()
    }

    /** «✨ Где нужны вставки» → this place: the type and the time of the hint, its data. */
    fun startHint(hn: InsertHint) {
        start(null)
        startMs = hn.startMs
        pickType(hn.type, null)
        baseDur = max(StudioEdit.MIN_MS, max(hn.endMs - hn.startMs, EditInsert.readMs(EditInsert.screenWords(brief))))
        if (hn.rows.isNotEmpty()) brief = brief.copy(rows = hn.rows)
        lum()
        redraw(false)
    }

    /** «⚡ Вставить без ИИ»: a native motion block or a scheme of the data, at once. */
    private fun insertNative() {
        val p = h.project() ?: return
        val (k, what) = native() ?: return
        val base = blockFor(p, target?.let { i -> p.blocks.firstOrNull { it.id == i } }, null).copy(req = "")
        val made = when (k) {
            EditMotion.KIND -> {
                val id = what as String
                base.copy(kind = EditMotion.KIND, look = id, text = EditMotion.preset(id)?.name.orEmpty(), animIn = "none", animOut = "none",
                    over = theme(p)?.let { EditThemes.toLook(it) } ?: base.over)
            }
            else -> base.copy(kind = "scheme", scheme = what as SScheme, text = what.title, file = "")
        }
        val old = target?.let { i -> p.blocks.firstOrNull { it.id == i } }
        var list = if (old != null) p.blocks.map { if (it.id == old.id) made.copy(history = old.history) else it } else placed(p.blocks, made)
        list = withSounds(p, list, made)
        h.commit(list, made.id)
        saveLast()
        inserted = true
        Lumen.prefs.edit().remove(PREF_DRAFT).apply()
        h.stat(InsertStat(made.kind, "native", "", true, 0, 0, System.currentTimeMillis()))
        close()
    }

    /** «Звук» of the brief: a whoosh at the start, soft clicks when the scheme's items come in (Studio effects). */
    private fun withSounds(p: EProject, list0: List<EBlock>, b: EBlock): List<EBlock> {
        if (brief.sound.isEmpty() || brief.sound == "none") return list0
        var list = list0
        val times = when {
            brief.sound == "whoosh" -> listOf(b.startMs)
            b.kind == "scheme" -> b.scheme?.let { StudioEdit.schemeTimes(it, words, b) }?.takeIf { it.isNotEmpty() } ?: listOf(b.startMs)
            b.kind == EditMotion.KIND -> {
                val ws = words.filter { it.startMs >= b.startMs && it.startMs < b.endMs }.map { it.text to (it.startMs - b.startMs) / 1000.0 }
                EditMotion.beats(b.look, EditMotion.content(brief, ws), b.durMs / 1000.0).map { b.startMs + (it * 1000).toLong() }
            }
            // an animation: the items of its brief on their words, else its start
            else -> AnimV11.cues(words, b.startMs, b.endMs, brief.rows.map { it.label to it.main }, emptyList()).filter { it.kind == "item" }
                .map { b.startMs + (it.t * 1000).toLong() }.ifEmpty { listOf(b.startMs) }
        }
        val name = if (brief.sound == "whoosh") "whoosh" else "pop"
        val file = InsertSfx.file(h.dir(), name) ?: return list0
        val len = StudioSfx.lengthMs(name)
        for (at in times.distinct().take(8)) {
            val s = StudioSfx.startOf(name, at)
            list = list + EBlock(StudioEdit.nextId(list), EditMusic.KIND, s, max(StudioEdit.MIN_MS, len + 60), x = 0.5f, y = 0.5f, w = 1f,
                animIn = "none", animOut = "none", text = "✨ " + name, file = file, volume = 0.8f)
        }
        return list
    }

    // ---------- memory: last brief, recipes, library, examples ----------

    private fun saveLast() = runCatching { Lumen.prefs.edit().putString(PREF_LAST, EditInsert.briefJson(EditInsert.recipeOf(brief)).toString()).apply() }

    private fun lastBrief(): EditBrief? = EditInsert.briefFrom(Lumen.prefs.getString(PREF_LAST, "") ?: "")

    private fun recipes(): List<Pair<String, EditBrief>> = runCatching {
        val a = JSONArray(Lumen.prefs.getString(PREF_RECIPES, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> EditInsert.briefFrom(o.optJSONObject("brief"))?.let { o.optString("name") to it } } }
    }.getOrDefault(emptyList())

    private fun saveRecipe(name: String, b: EditBrief) {
        val list = (listOf(name to b) + recipes().filter { it.first != name }).take(20)
        val a = JSONArray()
        list.forEach { (n, r) -> a.put(JSONObject().put("name", n).put("brief", EditInsert.briefJson(r))) }
        Lumen.prefs.edit().putString(PREF_RECIPES, a.toString()).apply()
    }

    private fun recipesSheet() {
        ui.bottomSheet(t("⭐ Мои рецепты", "⭐ My recipes")) { dismiss ->
            muted(t("Рецепт — тип, вид, прозрачность, движение и место без содержания: содержание возьмётся из этого момента.",
                "A recipe is the type, look, transparency, motion and place without content: the content comes from this moment."))
            for ((name, r) in recipes()) {
                row(
                    btn(ctx, name, style = BtnStyle.TONAL) {
                        dismiss()
                        pickType(r.type, r)
                    },
                    btn(ctx, "🗑", style = BtnStyle.TEXT) {
                        dismiss()
                        val left = recipes().filter { it.first != name }
                        val a = JSONArray()
                        left.forEach { (n, b) -> a.put(JSONObject().put("name", n).put("brief", EditInsert.briefJson(b))) }
                        Lumen.prefs.edit().putString(PREF_RECIPES, a.toString()).apply()
                    }
                )
            }
        }
    }

    private var libQuery = ""
    private var libKind = ""

    private fun librarySheet() {
        val lib = library(ui)
        ui.bottomSheet(t("📚 Моя библиотека", "📚 My library")) { dismiss ->
            val saved = lib.list()
            val q = edt(ui, t("🔍 Поиск по названию", "🔍 Search by name"), libQuery)
            add(q, 8)
            add(choiceChips(ui, listOf("" to t("Все", "All"), "scheme" to t("Схемы", "Schemes"), EditInsert.KIND to t("Анимации", "Animations")), libKind) { v ->
                libKind = v
                libQuery = q.text.toString()
                dismiss()
                librarySheet()
            }, 4)
            q.onEnter {
                libQuery = it
                dismiss()
                librarySheet()
            }
            val shown = saved.filter { (libKind.isEmpty() || it.kind == libKind) && (libQuery.isBlank() || it.name.contains(libQuery.trim(), true)) }
            if (saved.isEmpty()) muted(t("Пусто: в шаге «Проверка» нажмите «💾 В библиотеку».", "Empty: tap \"💾 To the library\" on the Check step."))
            else if (shown.isEmpty()) muted(t("Ничего не найдено", "Nothing found"))
            for (s in shown.take(40)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ripple(ctx, roundBg(Palette.CARD, 12f, ctx), Palette.ON_SURFACE)
                    setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                    setOnClickListener {
                        dismiss()
                        fromSaved(s)
                    }
                }
                val th = lib.thumb(s.id)
                row.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (th != null) setImageBitmap(th) else setImageDrawable(android.graphics.drawable.ColorDrawable(0xFF2A2D33.toInt()))
                }, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))
                row.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(10), 0, 0, 0)
                    addView(text(ctx, (if (s.kind == "scheme") "🧩 " else "🎞 ") + s.name, 15f, bold = true))
                    addView(text(ctx, (if (s.kind == "scheme") t("схема", "scheme") else t("анимация", "animation")) + " · " +
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.updated)), 12f, color = Palette.MUTED))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(iconBtn(ctx, "✎") {
                    dismiss()
                    ui.prompt(t("Название", "Name"), "", s.name) { n -> lib.rename(s.id, n) }
                })
                row.addView(iconBtn(ctx, "🗑") {
                    dismiss()
                    lib.delete(s.id)
                    say(t("Удалено", "Deleted"))
                })
                add(row, 6)
            }
            divider()
            row(
                btn(ctx, t("↗ Экспорт пакета", "↗ Export a pack"), style = BtnStyle.TONAL) {
                    val f = File(ui.cacheDir, "lumen_pack.json")
                    f.writeText(EditSuggest.pack(lib.list(), EditThemes.list(h.themesDir()), recipes()).toString())
                    ReportKit.share(ui, f, false, "Lumen pack", "application/json")
                },
                btn(ctx, t("📥 Пакет из буфера", "📥 Pack from clipboard"), style = BtnStyle.TEXT) {
                    val pk = clip()?.let { EditSuggest.unpack(it) } ?: return@btn say(t("В буфере нет пакета lumen.pack", "No lumen.pack in the clipboard"))
                    val now = System.currentTimeMillis()
                    pk.inserts.forEach { lib.add(it.kind, it.body, it.name, it.ask, "pack", now) }
                    pk.themes.forEach { EditThemes.save(h.themesDir(), it) }
                    pk.recipes.forEach { (n, r) -> saveRecipe(n, r) }
                    dismiss()
                    say(t("Добавлено: вставок ${pk.inserts.size}, тем ${pk.themes.size}, рецептов ${pk.recipes.size}", "Added: inserts ${pk.inserts.size}, themes ${pk.themes.size}, recipes ${pk.recipes.size}"))
                }
            )
            muted(t("Пакет — файл lumen.pack v1: вставки, свои темы и рецепты. Откройте его текст и скопируйте, чтобы добавить на другом телефоне.",
                "A pack is a lumen.pack v1 file: inserts, own themes and recipes. Open its text and copy it to add it on another phone."))
        }
    }

    private fun fromSaved(s: SavedInsert) {
        val a = if (s.kind == "scheme") StudioScheme.fromJson(s.body)?.let { InsertAnswer.Scheme(it, "", s.name) } else InsertAnswer.Anim(s.body, "", s.name, 0)
        a ?: return say(t("Запись повреждена", "The entry is broken"))
        val p = h.project() ?: return
        if (brief.type == "own" && step == 0) brief = EditInsert.defaults(if (s.kind == "scheme") "steps" else "own", speech(p), brand(p), brief.face).copy(draw = s.kind)
        if (req.isEmpty()) req = newReq()
        request = EditInsert.request(ctx(p), brief, s.kind)
        source = "library"
        onAnswer(a, false)
    }

    private fun examplesSheet() {
        ui.bottomSheet(t("🧪 Примеры анимаций", "🧪 Animation examples")) { dismiss ->
            muted(t("Готовые блоки без ИИ: берут числа и пункты из брифа и слова речи этого места.", "Ready blocks without AI: they take numbers and items from the brief and the words spoken here."))
            for (e in AnimExamples.ALL) add(btn(ctx, EditInsert.type(e.type).emoji + " " + e.name, style = BtnStyle.TONAL) {
                dismiss()
                val p = h.project() ?: return@btn
                brief = EditInsert.defaults(e.type, speech(p), brand(p), brief.face).copy(draw = EditInsert.KIND)
                if (span == null) baseDur = max(4000L, EditInsert.readMs(EditInsert.screenWords(brief)))
                if (req.isEmpty()) req = newReq()
                request = EditInsert.request(ctx(p), brief, EditInsert.KIND)
                source = "example"
                onAnswer(InsertAnswer.Anim(e.code, "", e.name, 0), false)
            }, 8)
        }
    }

    /** Templates of the Montage (lumen.canvas) as an animation on this piece of the video. */
    private fun montageSheet() {
        val layers = LayerStore(File(WebLayerActivity.dir(ui), "layers")).list()
        ui.bottomSheet(t("🎛 Шаблон Монтажа на отрезок", "🎛 Montage template on a piece")) { dismiss ->
            if (layers.isEmpty()) muted(t("Шаблонов пока нет: они появляются во вкладке «🎛 Монтаж» Студии.", "No templates yet: they appear in the Studio's \"🎛 Montage\" tab."))
            else muted(t("Шаблон получит время и слова только этого отрезка, как будто это весь ролик.", "The template gets the time and words of this piece only, as if it were the whole video."))
            for (l in layers) add(btn(ctx, "🎛 " + l.name, style = BtnStyle.TONAL) {
                dismiss()
                val p = h.project() ?: return@btn
                brief = EditInsert.defaults("own", speech(p), brand(p), brief.face).copy(draw = EditInsert.KIND, place = "full")
                if (req.isEmpty()) req = newReq()
                request = EditInsert.request(ctx(p), brief, EditInsert.KIND)
                onAnswer(InsertAnswer.Anim(EditInsert.canvasAdapter(l.code), "", l.name, 0), false)
            }, 8)
        }
    }
}

/** The mock-up of the brief on the current frame: the block, its plate, the dimming and the zones to keep free. */
@SuppressLint("ViewConstructor")
class MockView(ctx: Context, private val frame: Bitmap?, private val fw: Int, private val fh: Int) : View(ctx) {
    var block = FRect(0.1f, 0.3f, 0.9f, 0.6f)
    var zones: List<InsertZone> = emptyList()
    var plate = 0.7f
    var dim = 0f
    var dimAll = false
    var opacity = 1f
    var accent = 0xFFFFD60A.toInt()
    var label = ""
    var onMove: ((FRect) -> Unit)? = null
    var onMoved: (() -> Unit)? = null
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (frame != null) c.drawBitmap(frame, null, RectF(0f, 0f, w, h), img) else c.drawColor(0xFF202024.toInt())
        val r = RectF(block.l * w, block.t * h, block.r * w, block.b * h)
        if (dim > 0f) {
            fill.color = Color.argb((dim * 255).toInt(), 0, 0, 0)
            if (dimAll) c.drawRect(0f, 0f, w, h, fill) else c.drawRoundRect(RectF(r).apply { inset(-w * 0.03f, -w * 0.03f) }, w * 0.04f, w * 0.04f, fill)
        }
        for (z in zones) {
            fill.color = 0x40FF3B30
            val zr = RectF(z.r.l * w, z.r.t * h, z.r.r * w, z.r.b * h)
            c.drawRect(zr, fill)
            textP.textSize = w * 0.035f
            textP.color = 0xCCFFFFFF.toInt()
            c.drawText(z.why, zr.centerX(), zr.centerY() + textP.textSize / 3, textP)
        }
        fill.color = Color.argb((plate * opacity * 255).toInt().coerceIn(0, 255), 16, 19, 24)
        val rad = min(r.width(), r.height()) * 0.08f
        c.drawRoundRect(r, rad, rad, fill)
        line.color = accent
        line.strokeWidth = max(2f, w * 0.008f)
        line.pathEffect = DashPathEffect(floatArrayOf(w * 0.02f, w * 0.012f), 0f)
        c.drawRoundRect(r, rad, rad, line)
        textP.textSize = w * 0.04f
        textP.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + textP.textSize / 3, textP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val mv = onMove ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / width
                val dy = (e.y - lastY) / height
                lastX = e.x
                lastY = e.y
                val bw = block.w
                val bh = block.h
                val l = (block.l + dx).coerceIn(0f, 1f - bw)
                val t = (block.t + dy).coerceIn(0f, 1f - bh)
                block = FRect(l, t, l + bw, t + bh)
                mv(block)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onMoved?.invoke()
            }
        }
        return true
    }
}

/** Sound effects of the Studio as WAV files of the project (for the «Звук» of the insert wizard). */
object InsertSfx {
    private const val RATE = 44_100

    fun file(dir: File, name: String): String? = runCatching {
        val rel = "sfx_$name.wav"
        val f = File(dir, rel)
        if (!f.exists()) {
            val pcm = StudioSfx.synth(name, RATE)
            if (pcm.isEmpty()) return@runCatching null
            val data = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = (pcm[i].coerceIn(-1f, 1f) * 32767).toInt()
                data[2 * i] = (v and 0xFF).toByte()
                data[2 * i + 1] = (v shr 8 and 0xFF).toByte()
            }
            val head = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            head.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(data.size)
            f.outputStream().use {
                it.write(head.array())
                it.write(data)
            }
        }
        rel
    }.getOrNull()
}

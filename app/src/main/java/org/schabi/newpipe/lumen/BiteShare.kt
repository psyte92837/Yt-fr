/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A range bar with two handles: the start and the end of the piece. Dragging inside the piece moves it whole.
 * [onChange] gets the new ends, which end moved (true = start) and whether the finger is up.
 */
@SuppressLint("ViewConstructor")
class BiteRangeBar(ctx: Context, private val onChange: (Long, Long, Boolean, Boolean) -> Unit) : View(ctx) {
    var durMs = 0L
        set(v) { field = v; invalidate() }
    var fromMs = 0L
        private set
    var toMs = 0L
        private set

    private val r = ctx.dp(11).toFloat()
    private val pad = ctx.dp(16).toFloat()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drag = 0 // 1 start, 2 end, 3 whole
    private var downX = 0f
    private var downFrom = 0L
    private var downTo = 0L

    init {
        minimumHeight = ctx.dp(48)
        contentDescription = t("Отрезок: начало и конец", "Piece: start and end")
    }

    fun set(a: Long, b: Long) {
        fromMs = a
        toMs = b
        invalidate()
    }

    private val span get() = (width - 2 * pad).coerceAtLeast(1f)
    private fun x(ms: Long) = pad + if (durMs > 0) span * ms / durMs else 0f
    private fun ms(x: Float): Long = if (durMs <= 0) 0 else ((x - pad) / span * durMs).toLong().coerceIn(0, durMs)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(48))
    }

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        line.color = Palette.ACCENT_SOFT
        c.drawLine(pad, y, width - pad, y, line)
        if (durMs <= 0) return
        line.color = Palette.ACCENT
        c.drawLine(x(fromMs), y, x(toMs), y, line)
        knob.color = Palette.ACCENT
        c.drawCircle(x(fromMs), y, r, knob)
        c.drawCircle(x(toMs), y, r, knob)
        knob.color = Palette.SURFACE
        c.drawCircle(x(fromMs), y, r * 0.4f, knob)
        c.drawCircle(x(toMs), y, r * 0.4f, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durMs <= 0) return false
        val step = Bite.stepMs(durMs)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val da = kotlin.math.abs(e.x - x(fromMs))
                val db = kotlin.math.abs(e.x - x(toMs))
                val near = r * 2.5f
                drag = when {
                    da <= near && da <= db -> 1
                    db <= near -> 2
                    e.x > x(fromMs) && e.x < x(toMs) -> 3
                    e.x < x(fromMs) -> 1
                    else -> 2
                }
                downX = e.x
                downFrom = fromMs
                downTo = toMs
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e.x, step, false)
            }
            MotionEvent.ACTION_MOVE -> move(e.x, step, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                move(e.x, step, true)
                drag = 0
            }
        }
        return true
    }

    private fun move(px: Float, step: Long, up: Boolean) {
        val (a, b) = when (drag) {
            1 -> Bite.moveEnd(fromMs, toMs, Bite.snap(ms(px), step, durMs), true, durMs)
            2 -> Bite.moveEnd(fromMs, toMs, Bite.snap(ms(px), step, durMs), false, durMs)
            3 -> {
                val d = Bite.snap(((px - downX) / span * durMs).toLong(), step, Long.MAX_VALUE).let { if (px < downX) -Bite.snap(((downX - px) / span * durMs).toLong(), step, Long.MAX_VALUE) else it }
                Bite.shift(downFrom, downTo, d, durMs)
            }
            else -> return
        }
        set(a, b)
        onChange(a, b, drag != 2, up)
    }
}

/**
 * "✂ Bite (Lumen)" in the Share menu of YouTube: a sheet over the app that shared the link — sound or video, the
 * piece by a range bar or exact seconds — then it closes and the bite runs in the background with notifications.
 */
class BiteShareActivity : LumenBase() {
    private var url = ""
    private var title = ""
    private var durMs = 0L
    private var fromMs = 0L
    private var toMs = 15_000L
    private var frames: List<Pair<Long, android.graphics.Bitmap>> = emptyList()
    private var started = false

    private var audio: Boolean
        get() = BiteJob.audio
        set(v) { BiteJob.audio = v }
    private var quality: Int
        get() = BiteJob.quality
        set(v) { BiteJob.quality = v }
    private var audioQ: String
        get() = BiteJob.audioQ
        set(v) { BiteJob.audioQ = v }

    private lateinit var titleView: TextView
    private lateinit var bar: BiteRangeBar
    private lateinit var picture: ImageView
    private lateinit var pictureTime: TextView
    private lateinit var fromE: EditText
    private lateinit var toE: EditText
    private lateinit var info: TextView
    private lateinit var qualityRow: View
    private var typing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString.orEmpty()
        url = Regex("""https?://\S+""").findAll(text).map { it.value }.firstOrNull { YtQuery.videoIdFromUrl(it) != null }.orEmpty()
        if (url.isEmpty()) {
            toast(t("Нет ссылки на видео YouTube", "No YouTube video link"))
            return finish()
        }
        title = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        Bite.initial(url, 0).let { (a, b) -> fromMs = a; toMs = b }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
        sheet()
        load()
    }

    private fun load() {
        Lumen.async({
            val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, url)
            val look = runCatching { StudioCutsLoad.look(url, 40) }.getOrNull()
            Triple(info.name.orEmpty(), info.duration.coerceAtLeast(0L) * 1000L, look?.frames.orEmpty())
        }, { e ->
            if (!isDestroyed) info.text = t("Не удалось открыть видео: ", "Cannot open the video: ") + (e.message ?: e.javaClass.simpleName).take(160) +
                t(". Время можно ввести вручную.", ". You can type the times.")
        }) { (n, d, fr) ->
            if (isDestroyed) return@async
            if (n.isNotBlank()) title = n
            titleView.text = title
            durMs = d
            frames = fr
            if (d > 0) Bite.initial(url, d).let { (a, b) -> if (toMs > d || fromMs >= d) { fromMs = a; toMs = b } }
            bar.durMs = d
            bar.set(fromMs, toMs)
            bar.visibility = if (d > 0) View.VISIBLE else View.GONE
            showFields()
            showPicture(fromMs)
            showInfo()
        }
    }

    private fun sheet() = bottomSheet(t("✂ Откусить и сохранить отрезок", "✂ Bite and save a piece"), onDismiss = { finish() }) { dismiss ->
        titleView = add(text(ctx, title.ifBlank { t("Загрузка…", "Loading…") }, 14f, bold = true), 4).apply { maxLines = 2 }
        add(segBar(ctx, listOf(t("🎬 Видео", "🎬 Video"), t("🎵 Аудио", "🎵 Sound")), if (audio) 1 else 0) {
            audio = it == 1
            qualityRow.visibility = if (audio) View.GONE else View.VISIBLE
            showInfo()
        }, 8)
        add(text(ctx, t("Качество", "Quality"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        qualityRow = add(choiceChips(ctx, Bite.QUALITIES.map { it to Bite.videoLabel(it) }, quality) {
            quality = it
            showInfo()
        }, 4)
        qualityRow.visibility = if (audio) View.GONE else View.VISIBLE
        add(choiceChips(ctx, Bite.AUDIO_QUALITIES.map { it to (if (it == Bite.AUDIO_SMALL) "🔉 " else "🔊 ") + Bite.audioLabel(it) }, audioQ) {
            audioQ = it
            showInfo()
        }, 4)
        // the frame at the handle being moved
        val pic = FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()) }
        picture = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pictureTime = text(ctx, "", 12f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
            setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }
        pic.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pic.addView(pictureTime, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        add(pic, 12).layoutParams.height = dp(150)
        bar = add(BiteRangeBar(ctx) { a, b, start, _ ->
            fromMs = a
            toMs = b
            showFields()
            showPicture(if (start) a else b)
            showInfo()
        }, 4)
        bar.visibility = View.GONE
        bar.set(fromMs, toMs)
        fromE = edt(ctx, t("Начало, с или 1:23.5", "Start, s or 1:23.5"), "")
        toE = edt(ctx, t("Конец, с или 1:35", "End, s or 1:35"), "")
        add(fine(fromE, true), 4)
        add(fine(toE, false), 4)
        showFields()
        info = add(text(ctx, "", 13f, color = Palette.MUTED), 6)
        showInfo()
        button(t("✂ Откусить и сохранить отрезок", "✂ Bite and save the piece"), primary = true) {
            if (go()) dismiss()
        }
        button(t("Открыть в Lumen…", "Open in Lumen…"), style = BtnStyle.TEXT) {
            BiteActivity.open(this@BiteShareActivity, url)
            dismiss()
        }
    }

    /** A time field with −/＋ buttons that move it by one step of the bar. */
    private fun fine(e: EditText, start: Boolean): View {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (typing) return
                val v = StudioCuts.parseTime(s?.toString().orEmpty()) ?: return
                if (start) fromMs = v else toMs = v
                if (durMs > 0) bar.set(fromMs.coerceAtMost(durMs), toMs.coerceAtMost(durMs))
                showPicture(v)
                showInfo()
            }
        })
        fun nudge(d: Long) {
            val step = if (durMs > 0) maxOf(100L, Bite.stepMs(durMs)) else 500L
            val (a, b) = Bite.moveEnd(fromMs, toMs, (if (start) fromMs else toMs) + d * step, start, durMs)
            fromMs = a
            toMs = b
            bar.set(a, b)
            showFields()
            showPicture(if (start) a else b)
            showInfo()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(this@BiteShareActivity, if (start) "⟦" else "⟧", 18f, bold = true, color = Palette.ACCENT), LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(btn(this@BiteShareActivity, "−", style = BtnStyle.TEXT) { nudge(-1) }.apply { contentDescription = t("Раньше", "Earlier") })
            addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(this@BiteShareActivity, "＋", style = BtnStyle.TEXT) { nudge(1) }.apply { contentDescription = t("Позже", "Later") })
        }
    }

    private fun showFields() {
        if (!::fromE.isInitialized) return
        typing = true
        fromE.setText(StudioCuts.stamp(fromMs))
        toE.setText(StudioCuts.stamp(toMs))
        typing = false
    }

    private fun showPicture(ms: Long) {
        if (!::picture.isInitialized) return
        val i = Bite.nearest(frames.map { it.first }, ms)
        (picture.parent as? View)?.visibility = if (i < 0) View.GONE else View.VISIBLE
        if (i >= 0) picture.setImageBitmap(frames[i].second)
        pictureTime.text = StudioCuts.stamp(ms)
    }

    private fun showInfo() {
        if (!::info.isInitialized) return
        val r = Bite.range(StudioCuts.stamp(fromMs), StudioCuts.stamp(toMs), durMs)
        info.text = if (!r.ok) "⚠ " + r.error
        else t("Длина ", "Length ") + StudioCuts.stamp(r.lenMs) + " · ≈ " + "%.1f".format(Bite.sizeMb(r.lenMs, audio, quality, audioQ)) + t(" МБ", " MB") +
            if (audio) t(" · .m4a → Музыка/Lumen", " · .m4a → Music/Lumen") else t(" · .mp4 → Фильмы/Lumen", " · .mp4 → Movies/Lumen")
    }

    /** Starts the bite; false when the times are not a piece. */
    private fun go(): Boolean {
        val r = Bite.range(fromE.text.toString(), toE.text.toString(), durMs)
        if (!r.ok) {
            toast(r.error)
            return false
        }
        started = true
        BiteJob.start(applicationContext, url, title, r.fromMs, r.toMs, audio, quality, audioQ)
        toast(t("Вырезаю в фоне — прогресс в уведомлении", "Cutting in the background — progress is in the notification"))
        return true
    }
}

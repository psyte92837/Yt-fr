/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Material Design 3 colour roles. Uses Material You dynamic colours on Android 12+,
 * and the M3 baseline palette otherwise. Light and dark schemes follow the system.
 */
object Palette {
    var ACCENT = 0xFF6750A4.toInt() // primary
    var ON_ACCENT = 0xFFFFFFFF.toInt() // onPrimary
    var ACCENT_SOFT = 0xFFE8DEF8.toInt() // secondaryContainer
    var ON_SOFT = 0xFF1D192B.toInt() // onSecondaryContainer
    var FAB = 0xFFEADDFF.toInt() // primaryContainer
    var ON_FAB = 0xFF21005D.toInt() // onPrimaryContainer
    var SURFACE = 0xFFFEF7FF.toInt()
    var CARD = 0xFFF3EDF7.toInt() // surfaceContainer
    var ON_SURFACE = 0xFF1D1B20.toInt()
    var MUTED = 0xFF49454F.toInt() // onSurfaceVariant
    var OUTLINE = 0xFF79747E.toInt()
    var BAD = 0xFFB3261E.toInt() // error
    var OK = 0xFF2E7D32.toInt()
    var WARN = 0xFFB26A00.toInt()

    fun init(ctx: Context) {
        val night = ctx.isNight()
        if (Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int) = ctx.resources.getColor(id, ctx.theme)
            if (night) {
                ACCENT = c(android.R.color.system_accent1_200)
                ON_ACCENT = c(android.R.color.system_accent1_800)
                ACCENT_SOFT = c(android.R.color.system_accent2_700)
                ON_SOFT = c(android.R.color.system_accent2_100)
                FAB = c(android.R.color.system_accent1_700)
                ON_FAB = c(android.R.color.system_accent1_100)
                SURFACE = c(android.R.color.system_neutral1_900)
                CARD = c(android.R.color.system_neutral1_800)
                ON_SURFACE = c(android.R.color.system_neutral1_100)
                MUTED = c(android.R.color.system_neutral2_200)
                OUTLINE = c(android.R.color.system_neutral2_400)
            } else {
                ACCENT = c(android.R.color.system_accent1_600)
                ON_ACCENT = c(android.R.color.system_accent1_0)
                ACCENT_SOFT = c(android.R.color.system_accent2_100)
                ON_SOFT = c(android.R.color.system_accent2_900)
                FAB = c(android.R.color.system_accent1_100)
                ON_FAB = c(android.R.color.system_accent1_900)
                SURFACE = c(android.R.color.system_neutral1_10)
                CARD = c(android.R.color.system_neutral1_50)
                ON_SURFACE = c(android.R.color.system_neutral1_900)
                MUTED = c(android.R.color.system_neutral2_700)
                OUTLINE = c(android.R.color.system_neutral2_500)
            }
        } else if (night) {
            ACCENT = 0xFFD0BCFF.toInt()
            ON_ACCENT = 0xFF381E72.toInt()
            ACCENT_SOFT = 0xFF4A4458.toInt()
            ON_SOFT = 0xFFE8DEF8.toInt()
            FAB = 0xFF4F378B.toInt()
            ON_FAB = 0xFFEADDFF.toInt()
            SURFACE = 0xFF141218.toInt()
            CARD = 0xFF211F26.toInt()
            ON_SURFACE = 0xFFE6E0E9.toInt()
            MUTED = 0xFFCAC4D0.toInt()
            OUTLINE = 0xFF938F99.toInt()
        } else {
            ACCENT = 0xFF6750A4.toInt()
            ON_ACCENT = 0xFFFFFFFF.toInt()
            ACCENT_SOFT = 0xFFE8DEF8.toInt()
            ON_SOFT = 0xFF1D192B.toInt()
            FAB = 0xFFEADDFF.toInt()
            ON_FAB = 0xFF21005D.toInt()
            SURFACE = 0xFFFEF7FF.toInt()
            CARD = 0xFFF3EDF7.toInt()
            ON_SURFACE = 0xFF1D1B20.toInt()
            MUTED = 0xFF49454F.toInt()
            OUTLINE = 0xFF79747E.toInt()
        }
        BAD = if (night) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        OK = if (night) 0xFF81C995.toInt() else 0xFF2E7D32.toInt()
        WARN = if (night) 0xFFFFCC80.toInt() else 0xFFB26A00.toInt()
    }
}

/** M3 type scale. */
object Type {
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    const val HEADLINE = 28f
    const val TITLE_L = 22f
    const val TITLE_M = 16f
    const val BODY = 14f
    const val LABEL = 12f
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(url)
    }
}

/** Opens a video in the NewPipe player (player mode) or in an external app. */
fun Context.openVideo(url: String) {
    if (Settings.mode == "player") {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClassName(packageName, "org.schabi.newpipe.RouterActivity")
            startActivity(i)
            return
        } catch (e: Exception) {
            // fall back to external app
        }
    }
    openUrl(url)
}

fun roundBg(color: Int, radiusDp: Float = 12f, ctx: Context, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(1) * radiusDp
    if (stroke != 0) setStroke(ctx.dp(1), stroke)
}

/** Background with ripple touch feedback (M3 state layer). */
fun ripple(ctx: Context, content: Drawable, onColor: Int): RippleDrawable {
    val stateLayer = (onColor and 0x00FFFFFF) or 0x29000000
    return RippleDrawable(ColorStateList.valueOf(stateLayer), content, null)
}

/** Line icons for the top app bar, drawn in code on a 24-unit grid (Material Symbols Outlined look). */
enum class Ic { BACK, MORE, ADD, SETTINGS, BUG, SHARE, OPEN, FIT, COPY, DELETE, JOURNAL, WEB, SPEAKER }

/** App-bar actions are passed as short glyphs; this maps them to an icon (null = draw the glyph as text). */
fun glyphIcon(glyph: String): Ic? = when (glyph) {
    "←" -> Ic.BACK
    "⋮" -> Ic.MORE
    "＋", "+" -> Ic.ADD
    "⚙" -> Ic.SETTINGS
    "🐞" -> Ic.BUG
    "⤴" -> Ic.SHARE
    "↗" -> Ic.OPEN
    "⤢" -> Ic.FIT
    "⧉" -> Ic.COPY
    "🗑" -> Ic.DELETE
    "🧾" -> Ic.JOURNAL
    "🌐" -> Ic.WEB
    "🔊" -> Ic.SPEAKER
    else -> null
}

/** Spoken label for an app-bar action (TalkBack), falls back to the glyph itself. */
fun glyphLabel(glyph: String): String = when (glyphIcon(glyph)) {
    Ic.BACK -> t("Назад", "Back")
    Ic.MORE -> t("Ещё", "More")
    Ic.ADD -> t("Добавить", "Add")
    Ic.SETTINGS -> t("Настройки", "Settings")
    Ic.BUG -> t("Журнал и отчёт об ошибке", "Logs and bug report")
    Ic.SHARE -> t("Поделиться", "Share")
    Ic.OPEN -> t("Открыть в браузере", "Open in browser")
    Ic.FIT -> t("Вписать в экран", "Fit to screen")
    Ic.COPY -> t("Копировать", "Copy")
    Ic.DELETE -> t("Очистить", "Clear")
    Ic.JOURNAL -> t("Ответы AI", "AI answers")
    Ic.WEB -> t("Браузер", "Browser")
    Ic.SPEAKER -> t("Озвучить текст", "Read text aloud")
    null -> glyph
}

class IconDrawable(private val ic: Ic, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val k = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * k) / 2, b.top + (b.height() - 24 * k) / 2)
        canvas.scale(k, k)
        fun line(vararg p: Float) {
            val path = Path().apply {
                moveTo(p[0], p[1])
                var i = 2
                while (i < p.size) {
                    lineTo(p[i], p[i + 1])
                    i += 2
                }
            }
            canvas.drawPath(path, stroke)
        }
        when (ic) {
            Ic.BACK -> {
                line(20f, 12f, 4f, 12f)
                line(11f, 5f, 4f, 12f, 11f, 19f)
            }
            Ic.MORE -> floatArrayOf(5f, 12f, 19f).forEach { canvas.drawCircle(12f, it, 1.8f, fill) }
            Ic.ADD -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }
            Ic.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, stroke)
                canvas.drawCircle(12f, 12f, 7f, stroke)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    val c = Math.cos(a).toFloat()
                    val s = Math.sin(a).toFloat()
                    line(12 + 7 * c, 12 + 7 * s, 12 + 9.5f * c, 12 + 9.5f * s)
                }
            }
            Ic.BUG -> {
                canvas.drawRoundRect(RectF(7f, 8f, 17f, 21f), 5f, 5f, stroke)
                line(12f, 12f, 12f, 21f)
                line(9f, 8f, 7f, 4f)
                line(15f, 8f, 17f, 4f)
                line(7f, 13f, 3f, 12f)
                line(17f, 13f, 21f, 12f)
                line(7f, 17f, 3f, 18f)
                line(17f, 17f, 21f, 18f)
            }
            Ic.SHARE -> {
                canvas.drawCircle(18f, 5f, 2.5f, stroke)
                canvas.drawCircle(6f, 12f, 2.5f, stroke)
                canvas.drawCircle(18f, 19f, 2.5f, stroke)
                line(8.2f, 10.8f, 15.8f, 6.2f)
                line(8.2f, 13.2f, 15.8f, 17.8f)
            }
            Ic.OPEN -> {
                line(11f, 4f, 4f, 4f, 4f, 20f, 20f, 20f, 20f, 13f)
                line(10f, 14f, 20f, 4f)
                line(14f, 4f, 20f, 4f, 20f, 10f)
            }
            Ic.FIT -> {
                line(4f, 9f, 4f, 4f, 9f, 4f)
                line(15f, 4f, 20f, 4f, 20f, 9f)
                line(20f, 15f, 20f, 20f, 15f, 20f)
                line(9f, 20f, 4f, 20f, 4f, 15f)
            }
            Ic.COPY -> {
                canvas.drawRoundRect(RectF(8f, 8f, 20f, 20f), 2f, 2f, stroke)
                line(16f, 4f, 6f, 4f, 4f, 6f, 4f, 16f)
            }
            Ic.DELETE -> {
                line(4f, 6f, 20f, 6f)
                line(9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f)
                line(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f)
                line(14f, 10f, 14f, 17f)
            }
            Ic.JOURNAL -> {
                line(6f, 3f, 18f, 3f, 18f, 21f, 15f, 19f, 12f, 21f, 9f, 19f, 6f, 21f, 6f, 3f)
                line(9f, 8f, 15f, 8f)
                line(9f, 12f, 15f, 12f)
            }
            Ic.WEB -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawOval(RectF(8f, 3f, 16f, 21f), stroke)
                line(3f, 12f, 21f, 12f)
            }
            Ic.SPEAKER -> {
                line(4f, 9f, 8f, 9f, 13f, 4f, 13f, 20f, 8f, 15f, 4f, 15f, 4f, 9f)
                canvas.drawArc(RectF(11f, 8f, 19f, 16f), -50f, 100f, false, stroke)
                canvas.drawArc(RectF(9f, 4f, 23f, 20f), -50f, 100f, false, stroke)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 48dp icon button with a circular state layer; glyphs without an icon are drawn as text. */
fun iconBtn(ctx: Context, glyph: String, color: Int = Palette.ON_SURFACE, onClick: () -> Unit): View {
    val ic = glyphIcon(glyph)
    val v: View = if (ic != null) {
        ImageView(ctx).apply {
            setImageDrawable(IconDrawable(ic, color))
            val p = ctx.dp(12)
            setPadding(p, p, p, p)
        }
    } else {
        text(ctx, glyph, 22f, color = color).apply { gravity = Gravity.CENTER }
    }
    return v.apply {
        contentDescription = glyphLabel(glyph)
        background = ripple(ctx, roundBg(0x00000000, 24f, ctx), Palette.ON_SURFACE)
        setOnClickListener { onClick() }
    }
}

enum class BtnStyle { FILLED, TONAL, OUTLINED, TEXT }

fun text(ctx: Context, s: String, size: Float = Type.BODY, bold: Boolean = false, color: Int = Palette.ON_SURFACE) = TextView(ctx).apply {
    text = s
    textSize = size
    setTextColor(color)
    setLineSpacing(0f, 1.15f)
    if (bold) typeface = Type.medium
}

/** M3 common button: 40dp visual height inside a 48dp touch target, pill shape. */
fun btn(ctx: Context, s: String, primary: Boolean = false, style: BtnStyle? = null, onClick: () -> Unit) = Button(ctx).apply {
    val st = style ?: if (primary) BtnStyle.FILLED else BtnStyle.TONAL
    text = s
    isAllCaps = false
    textSize = 14f
    typeface = Type.medium
    stateListAnimator = null
    minHeight = ctx.dp(48)
    minimumHeight = ctx.dp(48)
    val (bg, fg, stroke) = when (st) {
        BtnStyle.FILLED -> Triple(Palette.ACCENT, Palette.ON_ACCENT, 0)
        BtnStyle.TONAL -> Triple(Palette.ACCENT_SOFT, Palette.ON_SOFT, 0)
        BtnStyle.OUTLINED -> Triple(0x00000000, Palette.ACCENT, Palette.OUTLINE)
        BtnStyle.TEXT -> Triple(0x00000000, Palette.ACCENT, 0)
    }
    val shape = roundBg(bg, 20f, ctx, stroke)
    val inset = android.graphics.drawable.InsetDrawable(shape, 0, ctx.dp(4), 0, ctx.dp(4))
    background = ripple(ctx, inset, fg)
    setTextColor(fg)
    val p = ctx.dp(24)
    setPadding(p, 0, p, 0)
    setOnClickListener { onClick() }
}

fun chk(ctx: Context, s: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(ctx).apply {
    text = s
    textSize = Type.BODY
    setTextColor(Palette.ON_SURFACE)
    buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
    minHeight = ctx.dp(48)
    isChecked = value
    setOnCheckedChangeListener { _, b -> onChange(b) }
}

/** M3 outlined text field look. */
fun edt(ctx: Context, hint: String, value: String = "", multiline: Boolean = false, number: Boolean = false) = EditText(ctx).apply {
    this.hint = hint
    setText(value)
    textSize = 16f
    setTextColor(Palette.ON_SURFACE)
    setHintTextColor(Palette.MUTED)
    background = roundBg(0x00000000, 4f, ctx, Palette.OUTLINE)
    val h = ctx.dp(16)
    setPadding(h, ctx.dp(14), h, ctx.dp(14))
    minHeight = ctx.dp(56)
    inputType = when {
        number -> InputType.TYPE_CLASS_NUMBER
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else -> InputType.TYPE_CLASS_TEXT
    }
    if (multiline) {
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
    } else {
        isSingleLine = true
    }
    setOnFocusChangeListener { _, focused ->
        background = if (focused) roundBg(0x00000000, 4f, ctx, Palette.ACCENT).apply { setStroke(ctx.dp(2), Palette.ACCENT) }
        else roundBg(0x00000000, 4f, ctx, Palette.OUTLINE)
    }
}

fun <T> spin(ctx: Context, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) = Spinner(ctx).apply {
    adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, options.map { it.second }) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(Palette.ON_SURFACE) }
    }
    background = roundBg(0x00000000, 4f, ctx, Palette.OUTLINE)
    minimumHeight = ctx.dp(56)
    setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
    setSelection(maxOf(0, options.indexOfFirst { it.first == selected }))
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(options[position].first)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

/** M3 filter/assist chip. */
fun chip(ctx: Context, s: String, selected: Boolean = false, onClick: (() -> Unit)? = null) = TextView(ctx).apply {
    text = s
    textSize = 14f
    typeface = Type.medium
    gravity = Gravity.CENTER
    minHeight = ctx.dp(32)
    val h = ctx.dp(12)
    setPadding(h, ctx.dp(6), h, ctx.dp(6))
    setTextColor(if (selected) Palette.ON_SOFT else Palette.MUTED)
    val shape = if (selected) roundBg(Palette.ACCENT_SOFT, 8f, ctx) else roundBg(0x00000000, 8f, ctx, Palette.OUTLINE)
    background = if (onClick != null) ripple(ctx, shape, Palette.ON_SURFACE) else shape
    onClick?.let { cb -> setOnClickListener { cb() } }
}

fun rowOf(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { i, v ->
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (i > 0) lp.marginStart = ctx.dp(8)
        addView(v, lp)
    }
}

/** Wrapping-free horizontal chip row (scrollable). */
fun chipRow(ctx: Context, chips: List<View>): HorizontalScrollView {
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    chips.forEach {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = ctx.dp(8)
        row.addView(it, lp)
    }
    return HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

/** Tiny view DSL so screens can be built in code without XML or extra libraries (8dp grid). */
class Col(val ctx: Context, padding: Int = 16) {
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = ctx.dp(padding)
        setPadding(p, p, p, p)
    }

    fun <T : View> add(v: T, marginTop: Int = 8): T {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ctx.dp(marginTop)
        root.addView(v, lp)
        return v
    }

    fun title(s: String) = add(text(ctx, s, Type.HEADLINE), 4)
    fun h2(s: String) = add(text(ctx, s, Type.TITLE_M, bold = true), 24)
    fun p(s: String, size: Float = Type.BODY, color: Int? = null) = add(text(ctx, s, size, color = color ?: Palette.ON_SURFACE))
    fun muted(s: String) = add(text(ctx, s, 13f, color = Palette.MUTED), 4)
    fun button(s: String, primary: Boolean = false, style: BtnStyle? = null, onClick: () -> Unit) = add(btn(ctx, s, primary, style, onClick), 4)
    fun check(s: String, value: Boolean, onChange: (Boolean) -> Unit) = add(chk(ctx, s, value, onChange), 0)
    fun edit(hint: String, value: String = "", multiline: Boolean = false, number: Boolean = false) =
        add(edt(ctx, hint, value, multiline, number))

    fun <T> spinner(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit): Spinner {
        add(text(ctx, label, Type.LABEL, bold = true, color = Palette.MUTED), 12)
        return add(spin(ctx, options, selected, onSelect), 4)
    }

    fun chips(chips: List<View>) = add(chipRow(ctx, chips), 8)
    fun row(vararg views: View): LinearLayout = add(rowOf(ctx, *views), 4)

    /** M3 filled card, 12dp corners, 16dp inner padding. */
    fun card(build: Col.() -> Unit): LinearLayout {
        val c = Col(ctx)
        c.root.background = ripple(ctx, roundBg(Palette.CARD, 12f, ctx), Palette.ON_SURFACE)
        c.build()
        return add(c.root, 12)
    }

    /** Expandable section: a header row with a one-line summary; the body is built only when opened. */
    fun section(title: String, summary: String, expanded: Boolean, onToggle: (Boolean) -> Unit, build: Col.() -> Unit) {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            background = ripple(ctx, roundBg(0x00000000, 12f, ctx), Palette.ON_SURFACE)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, title, Type.TITLE_M, bold = true))
                if (!expanded && summary.isNotBlank()) addView(text(ctx, summary, 13f, color = Palette.MUTED))
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(ctx, if (expanded) "▴" else "▾", 18f, color = Palette.MUTED))
            contentDescription = title + ". " + if (expanded) t("Свернуть", "Collapse") else t("Развернуть", "Expand")
            setOnClickListener { onToggle(!expanded) }
        }
        add(head, 16)
        if (expanded) build()
    }

    /** Segmented step indicator (M3 linear progress look). */
    fun steps(current: Int, total: Int) {
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until total) {
            val seg = View(ctx).apply { background = roundBg(if (i <= current) Palette.ACCENT else Palette.ACCENT_SOFT, 2f, ctx) }
            val lp = LinearLayout.LayoutParams(0, ctx.dp(4), 1f)
            if (i > 0) lp.marginStart = ctx.dp(4)
            bar.addView(seg, lp)
        }
        add(bar, 4)
    }

    /** Empty state: centred message and an optional call to action. */
    fun empty(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val box = Col(ctx, 24)
        box.root.gravity = Gravity.CENTER_HORIZONTAL
        box.root.background = roundBg(Palette.CARD, 16f, ctx)
        box.add(text(ctx, title, Type.TITLE_M, bold = true).apply { gravity = Gravity.CENTER }, 0)
        box.add(text(ctx, body, Type.BODY, color = Palette.MUTED).apply { gravity = Gravity.CENTER }, 8)
        if (action != null && onAction != null) {
            box.add(btn(ctx, action, primary = true, onClick = onAction), 16).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(16) }
        }
        add(box.root, 16)
    }

    fun divider() = add(View(ctx).apply { setBackgroundColor((Palette.OUTLINE and 0x00FFFFFF) or 0x40000000) }, 16).also {
        it.layoutParams.height = ctx.dp(1)
    }

    fun clear() = root.removeAllViews()
}

/**
 * M3 primary tabs (scrollable): label + 3dp active indicator, bottom divider.
 * The selected tab is scrolled into view, so tabs far to the right are never hidden.
 */
fun tabBar(ctx: Context, tabs: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    val labels = mutableListOf<TextView>()
    val marks = mutableListOf<View>()
    val scroller = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        clipToPadding = false
        addView(row)
    }
    fun paint(sel: Int) {
        labels.forEachIndexed { i, l -> l.setTextColor(if (i == sel) Palette.ACCENT else Palette.MUTED) }
        marks.forEachIndexed { i, m -> m.background = if (i == sel) roundBg(Palette.ACCENT, 3f, ctx) else null }
    }
    fun reveal(i: Int) = scroller.post {
        val v = row.getChildAt(i) ?: return@post
        scroller.smoothScrollTo(maxOf(0, v.left - (scroller.width - v.width) / 2), 0)
    }
    tabs.forEachIndexed { i, s ->
        val label = text(ctx, s, 14f, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        val mark = View(ctx)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = ctx.dp(48)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            background = ripple(ctx, roundBg(0x00000000, 0f, ctx), Palette.ON_SURFACE)
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f))
            addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(3)))
            contentDescription = s
            setOnClickListener {
                paint(i)
                reveal(i)
                onSelect(i)
            }
        }
        labels.add(label)
        marks.add(mark)
        row.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(48)))
    }
    paint(selected)
    reveal(selected)
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(scroller)
        addView(View(ctx).apply { setBackgroundColor((Palette.OUTLINE and 0x00FFFFFF) or 0x40000000) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))
    }
}

fun Activity.scrollPage(col: Col): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(col.root)
}

fun Activity.confirm(title: String, message: String, ok: String = "OK", onOk: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton(t("Отмена", "Cancel"), null).show()
}

fun Activity.prompt(title: String, hint: String, value: String = "", multiline: Boolean = false, onOk: (String) -> Unit) {
    val e = edt(this, hint, value, multiline)
    val box = LinearLayout(this).apply {
        val p = dp(24)
        setPadding(p, dp(8), p, 0)
        addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }
        .setNegativeButton(t("Отмена", "Cancel"), null).show()
}

fun Activity.showText(title: String, body: String) {
    val tv = text(this, body, 14f).apply {
        setTextIsSelectable(true)
        val p = dp(24)
        setPadding(p, dp(8), p, dp(8))
    }
    AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("OK", null)
        .setNeutralButton(t("Поделиться", "Share")) { _, _ -> shareText(title, body) }
        .setNegativeButton(t("🔊 Озвучить", "🔊 Read aloud")) { _, _ -> TtsActivity.open(this, body, title) }
        .show()
}

fun Context.shareText(subject: String, body: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
    startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Base activity: M3 colours, top app bar, edge-to-edge insets (required for targetSdk 35),
 * optional extended FAB for the primary action of the screen.
 */
abstract class LumenBase : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Lumen.init(this)
        Palette.init(this)
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.SURFACE
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.SURFACE
        if (!isNight()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }

    /** M3 small top app bar: navigation icon, title, trailing action icons. */
    private fun appBar(title: String, back: Boolean, actions: List<Pair<String, () -> Unit>>): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(Palette.SURFACE)
        }
        val size = dp(48)
        if (back) bar.addView(iconBtn(this, "←") { finish() }, LinearLayout.LayoutParams(size, size))
        val tv = text(this, title, Type.TITLE_L).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(if (back) dp(4) else dp(12), 0, 0, 0)
        }
        bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { (glyph, cb) -> bar.addView(iconBtn(this, glyph, onClick = cb), LinearLayout.LayoutParams(size, size)) }
        return bar
    }

    /**
     * Sets the screen: top app bar + optional fixed header (e.g. tabs) + scrolling content + optional FAB.
     */
    fun page(
        title: String,
        content: View,
        back: Boolean = true,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        header: View? = null,
        fab: Pair<String, () -> Unit>? = null,
        scroll: Boolean = true
    ) {
        // views may be reused between re-renders (e.g. the graph view): detach them from the old layout first
        (content.parent as? ViewGroup)?.removeView(content)
        header?.let { h -> (h.parent as? ViewGroup)?.removeView(h) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
        }
        column.addView(appBar(title, back, actions))
        header?.let { column.addView(it) }
        val body = if (!scroll || content is ScrollView) content else ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val frame = FrameLayout(this).apply { setBackgroundColor(Palette.SURFACE) }
        frame.addView(column)
        if (fab != null) {
            val f = text(this, fab.first, 16f, bold = true, color = Palette.ON_FAB).apply {
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setPadding(dp(20), 0, dp(20), 0)
                background = ripple(this@LumenBase, roundBg(Palette.FAB, 16f, this@LumenBase), Palette.ON_FAB)
                elevation = dp(6).toFloat()
                setOnClickListener { fab.second() }
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.END)
            lp.setMargins(dp(16), dp(16), dp(16), dp(16))
            frame.addView(f, lp)
            // leave room so the FAB never covers the last item
            if (scroll) (content as? ViewGroup)?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom + dp(88)) }
        }
        // edge-to-edge: pad by system bar insets
        frame.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(frame)
        frame.requestApplyInsets()
    }

    /** Shows an error, logs it, and offers to share a developer report. */
    fun err(t: Throwable) {
        val msg = (t.message ?: t.javaClass.simpleName).take(500)
        L.e("ui", "${javaClass.simpleName}: $msg", t)
        AlertDialog.Builder(this).setTitle(t("Ошибка", "Error")).setMessage(msg).setPositiveButton("OK", null)
            .setNeutralButton(t("Отчёт", "Report")) { _, _ ->
                shareText("Lumen report", L.report(this, t, "screen: ${javaClass.simpleName}", errorsOnly = true, maxLines = 150))
            }
            .also { b ->
                // any recent AI answer (incl. cut-off / invalid) can be opened right from the error
                val last = AiJournal.latest()?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }
                if (last != null) b.setNegativeButton(t("Ответ AI", "AI answer")) { _, _ -> AiJournalActivity.open(this, id = last.id) }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        L.d("ui", "open ${javaClass.simpleName}")
    }
}

/** Minimal async thumbnail loader with a memory cache. */
object Thumbs {
    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(iv: ImageView, url: String) {
        iv.tag = url
        if (url.isEmpty()) {
            iv.setImageDrawable(null)
            return
        }
        cache.get(url)?.let {
            iv.setImageBitmap(it)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    Lumen.ui { if (iv.tag == url) iv.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
                // thumbnails are optional
            }
        }
    }
}

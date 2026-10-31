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
import android.widget.ProgressBar
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

    /** Fixed accent presets: light (primary, onPrimary, container, onContainer), dark (same). */
    private val accents = mapOf(
        "purple" to listOf(0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D, 0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF),
        "blue" to listOf(0xFF0B57D0, 0xFFFFFFFF, 0xFFD3E3FD, 0xFF041E49, 0xFFA8C7FA, 0xFF062E6F, 0xFF0842A0, 0xFFD3E3FD),
        "green" to listOf(0xFF1B6C2F, 0xFFFFFFFF, 0xFFA6F5B0, 0xFF002108, 0xFF8AD896, 0xFF00391A, 0xFF005226, 0xFFA6F5B0),
        "red" to listOf(0xFFB3261E, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002, 0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6),
        "orange" to listOf(0xFF9A4600, 0xFFFFFFFF, 0xFFFFDBC8, 0xFF321300, 0xFFFFB68A, 0xFF512400, 0xFF743400, 0xFFFFDBC8),
        "teal" to listOf(0xFF006B5E, 0xFFFFFFFF, 0xFF9EF2E0, 0xFF00201B, 0xFF82D5C4, 0xFF00382F, 0xFF005046, 0xFF9EF2E0)
    )
    val accentNames get() = listOf("dynamic" to t("Динамический (Android 12+)", "Dynamic (Android 12+)"), "purple" to t("Фиолетовый", "Purple"),
        "blue" to t("Синий", "Blue"), "green" to t("Зелёный", "Green"), "red" to t("Красный", "Red"),
        "orange" to t("Оранжевый", "Orange"), "teal" to t("Бирюзовый", "Teal"))

    /** Ready-made gradient themes: (dark?, gradient colours, accent, onAccent, container, onContainer). */
    private class Grad(val dark: Boolean, val bg: IntArray, val accent: Long, val onAccent: Long, val cont: Long, val onCont: Long)
    private val gradients = mapOf(
        "aurora" to Grad(true, intArrayOf(0xFF0B1026.toInt(), 0xFF1B2A6B.toInt(), 0xFF0E7C86.toInt()), 0xFF5EEAD4, 0xFF00201B, 0xFF134E4A, 0xFFCCFBF1),
        "sunset" to Grad(true, intArrayOf(0xFF2B0B3F.toInt(), 0xFF8E1A8C.toInt(), 0xFFE8603C.toInt()), 0xFFFFB38A, 0xFF4A1500, 0xFF7A2A55, 0xFFFFD9E6),
        "neon" to Grad(true, intArrayOf(0xFF0F0C29.toInt(), 0xFF302B63.toInt(), 0xFF5B1F7A.toInt()), 0xFFFF6AD5, 0xFF3A0030, 0xFF4B2C8C, 0xFFE6DAFF),
        "ocean" to Grad(true, intArrayOf(0xFF001B3A.toInt(), 0xFF05508F.toInt(), 0xFF1291B8.toInt()), 0xFF7FDBFF, 0xFF00263B, 0xFF0B4A73, 0xFFD2EFFF),
        "candy" to Grad(false, intArrayOf(0xFFFFE3F1.toInt(), 0xFFE6DDFF.toInt(), 0xFFD0F0FF.toInt()), 0xFF8A3FFC, 0xFFFFFFFF, 0xFFEADCFF, 0xFF2A0A5E),
        "peach" to Grad(false, intArrayOf(0xFFFFF1E0.toInt(), 0xFFFFD9C2.toInt(), 0xFFFFC6D3.toInt()), 0xFFC2410C, 0xFFFFFFFF, 0xFFFFDCC8, 0xFF431407),
        "mint" to Grad(false, intArrayOf(0xFFE6FFF5.toInt(), 0xFFC9F4E4.toInt(), 0xFFD6EEFF.toInt()), 0xFF00796B, 0xFFFFFFFF, 0xFFB2F1E4, 0xFF00201B),
        "sky" to Grad(false, intArrayOf(0xFFE8F4FF.toInt(), 0xFFD0E4FF.toInt(), 0xFFE6E0FF.toInt()), 0xFF0B57D0, 0xFFFFFFFF, 0xFFD3E3FD, 0xFF041E49),
        "lavender" to Grad(false, intArrayOf(0xFFF5EDFF.toInt(), 0xFFE5D8FF.toInt(), 0xFFFFE2F4.toInt()), 0xFF6A3EC2, 0xFFFFFFFF, 0xFFE9DDFF, 0xFF23005C),
        "sunrise" to Grad(false, intArrayOf(0xFFFFFBE3.toInt(), 0xFFFFEDC2.toInt(), 0xFFFFD8C6.toInt()), 0xFF8A5100, 0xFFFFFFFF, 0xFFFFDDB3, 0xFF2B1700),
        "rose" to Grad(false, intArrayOf(0xFFFFEEF1.toInt(), 0xFFFFD8E2.toInt(), 0xFFF3E2FF.toInt()), 0xFFB0285A, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001D),
        "pearl" to Grad(false, intArrayOf(0xFFFAFAFC.toInt(), 0xFFEDF0F5.toInt(), 0xFFE0E7F1.toInt()), 0xFF3D5A80, 0xFFFFFFFF, 0xFFD6E3F7, 0xFF0B1D33),
        "forest" to Grad(true, intArrayOf(0xFF0B2116.toInt(), 0xFF14532D.toInt(), 0xFF3F6212.toInt()), 0xFF86EFAC, 0xFF003915, 0xFF1E5631, 0xFFD7F8DF),
        "ember" to Grad(true, intArrayOf(0xFF1A0A0A.toInt(), 0xFF5B1313.toInt(), 0xFFB33A0B.toInt()), 0xFFFFB59A, 0xFF5A1A00, 0xFF6E2410, 0xFFFFDBCF)
    )
    val gradientNames get() = listOf("none" to t("Без градиента", "None"),
        "candy" to t("Конфетти (светлая)", "Candy (light)"), "peach" to t("Персик (светлая)", "Peach (light)"),
        "mint" to t("Мята (светлая)", "Mint (light)"), "sky" to t("Небо (светлая)", "Sky (light)"),
        "lavender" to t("Лаванда (светлая)", "Lavender (light)"), "sunrise" to t("Рассвет (светлая)", "Sunrise (light)"),
        "rose" to t("Роза (светлая)", "Rose (light)"), "pearl" to t("Жемчуг (светлая)", "Pearl (light)"),
        "aurora" to t("Аврора", "Aurora"), "sunset" to t("Закат", "Sunset"), "neon" to t("Неон", "Neon"),
        "ocean" to t("Океан", "Ocean"), "forest" to t("Лес", "Forest"), "ember" to t("Угли", "Ember"))

    /** Keys of light / dark gradient themes, in menu order; "none" goes with the light group. */
    val lightGradients get() = gradientNames.map { it.first }.filter { gradients[it]?.dark != true }
    val darkGradients get() = gradientNames.map { it.first }.filter { gradients[it]?.dark == true }

    /** Every menu key except "none" has a theme behind it (checked by tests). */
    fun hasGradient(key: String) = key in gradients

    /** Tile preview for a gradient theme: colours and whether it is dark; null for "none". */
    fun gradientPreview(name: String): Pair<IntArray, Boolean>? = gradients[name]?.let { it.bg to it.dark }

    /** Active gradient colours or null; windows and pages paint this behind transparent content. */
    val gradient: IntArray? get() = if (LumenEnv.ready) gradients[Settings.style]?.bg else null

    val gradientDark: Boolean? get() = if (LumenEnv.ready) gradients[Settings.style]?.dark else null

    fun background(): android.graphics.drawable.Drawable =
        gradient?.let { android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, it) }
            ?: android.graphics.drawable.ColorDrawable(SURFACE)

    fun init(ctx: Context) {
        val night = ctx.isNight()
        initBase(ctx, night)
        gradients[Settings.style]?.let { g ->
            val a = g.accent.toInt()
            ACCENT = a; ON_ACCENT = g.onAccent.toInt(); FAB = g.cont.toInt(); ON_FAB = g.onCont.toInt()
            ACCENT_SOFT = FAB; ON_SOFT = ON_FAB
            if (g.dark) {
                SURFACE = g.bg[0]
                CARD = 0x33FFFFFF // translucent glass over the gradient
                ON_SURFACE = 0xFFF4F1FA.toInt(); MUTED = 0xFFD0CBDD.toInt(); OUTLINE = 0xFFA9A3B8.toInt()
                BAD = 0xFFFFB4AB.toInt(); OK = 0xFF81C995.toInt(); WARN = 0xFFFFCC80.toInt()
            } else {
                SURFACE = g.bg[0]
                CARD = 0x80FFFFFF.toInt()
                ON_SURFACE = 0xFF1D1B20.toInt(); MUTED = 0xFF49454F.toInt(); OUTLINE = 0xFF79747E.toInt()
                BAD = 0xFFB3261E.toInt(); OK = 0xFF2E7D32.toInt(); WARN = 0xFFB26A00.toInt()
            }
            return
        }
        accents[Settings.accent]?.let { a ->
            val o = if (night) 4 else 0
            ACCENT = a[o].toInt(); ON_ACCENT = a[o + 1].toInt()
            FAB = if (night) a[6].toInt() else a[2].toInt(); ON_FAB = if (night) a[7].toInt() else a[3].toInt()
            ACCENT_SOFT = FAB; ON_SOFT = ON_FAB
        }
        if (night && Settings.theme == "black") {
            SURFACE = 0xFF000000.toInt()
            CARD = 0xFF121212.toInt()
        }
    }

    private fun initBase(ctx: Context, night: Boolean) {
        if (Build.VERSION.SDK_INT >= 31 && Settings.accent == "dynamic") {
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

fun Context.isNight(): Boolean = if (LumenEnv.ready && Palette.gradientDark != null) Palette.gradientDark == true else when (if (LumenEnv.ready) Settings.theme else "system") {
    "light" -> false
    "dark", "black" -> true
    else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

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
enum class Ic { BACK, MORE, ADD, SETTINGS, BUG, SHARE, OPEN, FIT, COPY, DELETE, JOURNAL, WEB, SPEAKER, HOME, LIST, CHART, SPARK, SEARCH, HELP, MOVIE }

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
    "?" -> Ic.HELP
    "🎬" -> Ic.MOVIE
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
    Ic.HELP -> t("Справка", "Help")
    Ic.MOVIE -> "Lumen Studio"
    Ic.HOME, Ic.LIST, Ic.CHART, Ic.SPARK, Ic.SEARCH -> glyph
    null -> if (glyph == "🗺") t("Карта знаний", "Knowledge map") else glyph
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
            Ic.HOME -> {
                line(3f, 11f, 12f, 4f, 21f, 11f)
                line(6f, 9f, 6f, 20f, 18f, 20f, 18f, 9f)
            }
            Ic.LIST -> {
                floatArrayOf(6f, 12f, 18f).forEach {
                    canvas.drawCircle(4.5f, it, 1.3f, fill)
                    line(9f, it, 20f, it)
                }
            }
            Ic.CHART -> {
                line(4f, 20f, 20f, 20f)
                line(7f, 17f, 7f, 11f)
                line(12f, 17f, 12f, 5f)
                line(17f, 17f, 17f, 13f)
            }
            Ic.SPARK -> line(12f, 3f, 14f, 10f, 21f, 12f, 14f, 14f, 12f, 21f, 10f, 14f, 3f, 12f, 10f, 10f, 12f, 3f)
            Ic.SEARCH -> {
                canvas.drawCircle(11f, 11f, 6f, stroke)
                line(15.5f, 15.5f, 20f, 20f)
            }
            Ic.HELP -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawArc(RectF(9f, 6.5f, 15f, 12.5f), 180f, 270f, false, stroke)
                line(12f, 12.5f, 12f, 14f)
                canvas.drawCircle(12f, 17.2f, 1.1f, fill)
            }
            Ic.MOVIE -> {
                canvas.drawRoundRect(RectF(3f, 8f, 21f, 20f), 2f, 2f, stroke)
                line(3f, 8f, 20f, 4f)
                line(8f, 6.9f, 10f, 9.5f)
                line(14f, 5.5f, 16f, 8.2f)
                line(10f, 11f, 10f, 17f, 15f, 14f, 10f, 11f)
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
    textSize = size * (if (LumenEnv.ready) Settings.textScale / 100f else 1f)
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

/** Lays children out left to right and wraps to the next line when the row is full. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                y += rowH + vGap
                x = 0
                rowH = 0
            }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(resolveSize(maxW + paddingLeft + paddingRight, widthSpec), resolveSize(y + rowH + paddingTop + paddingBottom, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                y += rowH + vGap
                x = 0
                rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** Chips that wrap onto several lines; past [collapseAfter] chips the rest hides behind a "+ N more" chip. */
fun chipRow(ctx: Context, chips: List<View>, collapseAfter: Int = 9): ViewGroup {
    val flow = FlowLayout(ctx, ctx.dp(8), ctx.dp(8))
    if (chips.size <= collapseAfter + 1) {
        chips.forEach { flow.addView(it) }
        return flow
    }
    chips.take(collapseAfter).forEach { flow.addView(it) }
    val rest = chips.drop(collapseAfter)
    lateinit var more: View
    more = chip(ctx, t("+ ещё ${rest.size}", "+ ${rest.size} more"), true) {
        flow.removeView(more)
        rest.forEach { flow.addView(it) }
    }
    flow.addView(more)
    return flow
}

/** One horizontal scrolling line of chips, for a fixed header that must stay a single row. */
fun chipScroller(ctx: Context, chips: List<View>): HorizontalScrollView {
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

/** Single-choice group as wrapping chips: one tap, every option visible. */
fun <T> choiceChips(ctx: Context, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit): ViewGroup {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun fill(sel: T) {
        box.removeAllViews()
        box.addView(chipRow(ctx, options.map { (v, label) ->
            chip(ctx, label, v == sel) {
                if (v != sel) {
                    fill(v)
                    onSelect(v)
                }
            }
        }, collapseAfter = 99))
    }
    fill(selected)
    return box
}

/** M3 segmented button: 2-4 short options in one row, no scrolling. */
fun segBar(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundBg(0x00000000, 20f, ctx, Palette.OUTLINE)
    }
    labels.forEachIndexed { i, s ->
        val on = i == selected
        val tv = text(ctx, s, if (s.length > 10) 12f else 14f, bold = true, color = if (on) Palette.ON_SOFT else Palette.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            background = ripple(ctx, roundBg(if (on) Palette.ACCENT_SOFT else 0x00000000, 20f, ctx), Palette.ON_SURFACE)
            contentDescription = s
            setOnClickListener { if (!on) onSelect(i) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
    }
    return row
}

/** M3 navigation bar: icon in a pill, label under it; the active destination is bold. */
fun bottomNav(ctx: Context, items: List<Pair<Ic, String>>, selected: Int, onSelect: (Int) -> Unit): View {
    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Palette.CARD)
        setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
    }
    items.forEachIndexed { i, (ic, label) ->
        val on = i == selected
        val color = if (on) Palette.ON_SURFACE else Palette.MUTED
        val pill = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(ic, if (on) Palette.ON_SOFT else color))
            background = roundBg(if (on) Palette.ACCENT_SOFT else 0x00000000, 16f, ctx)
            val p = ctx.dp(4)
            setPadding(ctx.dp(20), p, ctx.dp(20), p)
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = ctx.dp(56)
            contentDescription = label
            addView(pill, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(32)))
            addView(text(ctx, label, 12f, bold = on, color = color).apply { maxLines = 1 })
            setOnClickListener { onSelect(i) }
        }
        bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    return bar
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

    /** "Something is happening" line: status text plus a moving bar (determinate when the text carries "i/n"). */
    fun working(s: String, bold: Boolean = true): Pair<TextView, ProgressBar> {
        val tv = add(text(ctx, s, 14f, bold = bold, color = Palette.ON_SURFACE), 0) as TextView
        val bar = add(busyBar(ctx), 8) as ProgressBar
        bar.layoutParams.height = ctx.dp(4)
        bar.showProgress(s)
        return tv to bar
    }
    fun button(s: String, primary: Boolean = false, style: BtnStyle? = null, onClick: () -> Unit) = add(btn(ctx, s, primary, style, onClick), 4)
    fun check(s: String, value: Boolean, onChange: (Boolean) -> Unit) = add(chk(ctx, s, value, onChange), 0)
    fun edit(hint: String, value: String = "", multiline: Boolean = false, number: Boolean = false) =
        add(edt(ctx, hint, value, multiline, number))

    /** Up to 7 short options show as chips (one tap); long or numeric lists stay a dropdown. */
    fun <T> spinner(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit): View {
        add(text(ctx, label, Type.LABEL, bold = true, color = Palette.MUTED), 12)
        if (options.size in 2..7 && options.all { it.second.length <= 26 } && options.any { it.first == selected }) {
            return add(choiceChips(ctx, options, selected, onSelect), 4)
        }
        return add(spin(ctx, options, selected, onSelect), 4)
    }

    fun chips(chips: List<View>, collapseAfter: Int = 9) = add(chipRow(ctx, chips, collapseAfter), 8)
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
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.SURFACE
        window.setBackgroundDrawable(Palette.background())
        if (!isNight()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }

    private var pageScroll: ScrollView? = null

    /** Receiver of the next dictation result (see [listen] in AskAi.kt). */
    var voiceCallback: ((String) -> Unit)? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AskAi.REQ_VOICE) return
        val cb = voiceCallback
        voiceCallback = null
        if (resultCode == RESULT_OK) {
            AskAi.spoken(data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS))?.let { cb?.invoke(it) }
        }
    }

    /** The app-bar arrow; screens with inner levels override it. */
    open fun navigateUp() = finish()

    /** Help section opened by the "?" in the top bar of this screen. */
    open val helpTopic: String get() = HelpMap.topicFor(javaClass.simpleName)

    /** M3 small top app bar: navigation icon, title, trailing action icons. */
    private fun appBar(title: String, back: Boolean, actions: List<Pair<String, () -> Unit>>): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(4), 0)
            if (Palette.gradient == null) setBackgroundColor(Palette.SURFACE)
        }
        val size = dp(48)
        if (back) bar.addView(iconBtn(this, "←") { navigateUp() }, LinearLayout.LayoutParams(size, size))
        val tv = text(this, title, Type.TITLE_L).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(if (back) dp(4) else dp(12), 0, 0, 0)
        }
        bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { (glyph, cb) -> bar.addView(iconBtn(this, glyph, onClick = cb), LinearLayout.LayoutParams(size, size)) }
        // every screen gets its help; the help screen itself does not need one
        if (this !is HelpActivity && actions.none { it.first == "?" }) {
            bar.addView(iconBtn(this, "?") { HelpActivity.open(this, helpTopic) }, LinearLayout.LayoutParams(size, size))
        }
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
        scroll: Boolean = true,
        footer: View? = null,
        keepScroll: Boolean = false,
        footerExtra: Int = 0
    ) {
        // views may be reused between re-renders (e.g. the graph view): detach them from the old layout first
        (content.parent as? ViewGroup)?.removeView(content)
        header?.let { h -> (h.parent as? ViewGroup)?.removeView(h) }
        footer?.let { f -> (f.parent as? ViewGroup)?.removeView(f) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (Palette.gradient == null) setBackgroundColor(Palette.SURFACE)
        }
        column.addView(appBar(title, back, actions))
        header?.let { column.addView(it) }
        val prevY = if (keepScroll) pageScroll?.scrollY ?: 0 else 0
        val body = if (!scroll || content is ScrollView) content else ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        pageScroll = body as? ScrollView
        if (prevY > 0) pageScroll?.let { sv -> sv.post { sv.scrollTo(0, prevY) } }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        footer?.let { column.addView(it) }
        val frame = FrameLayout(this).apply { background = Palette.background() }
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
            lp.setMargins(dp(16), dp(16), dp(16), dp(16) + if (footer != null) dp(80) + footerExtra else 0)
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

    /** M3 bottom sheet: a titled panel pinned to the bottom edge; [build] fills it and gets a dismiss callback. */
    fun bottomSheet(title: String, onDismiss: () -> Unit = {}, build: Col.(dismiss: () -> Unit) -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val c = Col(this, 20)
        c.root.background = roundBg(Palette.SURFACE, 28f, this)
        c.add(text(this, title, Type.TITLE_L), 0)
        c.build { dialog.dismiss() }
        val scroll = ScrollView(this).apply { addView(c.root) }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    /** Shows an error, logs it, and offers to share a developer report. */
    fun err(t: Throwable) {
        val msg = (t.message ?: t.javaClass.simpleName).take(500)
        L.e("ui", "${javaClass.simpleName}: $msg", t)
        // the screen may be gone by the time a background job fails: no window to attach a dialog to
        if (isFinishing || isDestroyed) return
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
        visible++
        L.d("ui", "open ${javaClass.simpleName}")
    }

    override fun onPause() {
        visible = maxOf(0, visible - 1)
        super.onPause()
    }

    companion object {
        /** Lumen screens currently on top; the AI send review opens by itself only while one is visible. */
        @Volatile var visible = 0
    }
}

/** List row of a video: small thumbnail, title (2 lines), channel and a metrics line; with [url] a "⋮" button (background / download). */
fun videoListRow(ctx: Context, thumb: String, title: String, sub: String, meta: String, url: String = "", onClick: () -> Unit): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(ctx, roundBg(Palette.CARD, 12f, ctx), Palette.ON_SURFACE)
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        setOnClickListener { onClick() }
    }
    val img = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundBg(Palette.ACCENT_SOFT, 8f, ctx)
        clipToOutline = true
        contentDescription = title
    }
    row.addView(img, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(54)).apply { marginEnd = ctx.dp(12) })
    Thumbs.load(img, thumb)
    val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    info.addView(text(ctx, title, 14f, bold = true).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    if (sub.isNotBlank()) info.addView(text(ctx, sub, 12f, color = Palette.MUTED).apply { isSingleLine = true })
    if (meta.isNotBlank()) info.addView(text(ctx, meta, 12f, color = Palette.MUTED))
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    if (url.isNotBlank()) {
        row.setPadding(ctx.dp(8), ctx.dp(8), 0, ctx.dp(8))
        row.addView(mediaBtn(ctx, url, title), LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(48)))
    }
    return row
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
                val opts = BitmapFactory.Options().apply { inSampleSize = if (url.contains("mqdefault")) 1 else 2 }
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


/** Parses "3/10"-style counters out of progress messages. */
object ProgressText {
    private val rx = Regex("""(\d+)\s*/\s*(\d+)""")

    /** Fraction 0..1 from the last "done/total" in [s]; null when absent or nonsensical. */
    fun fraction(s: String): Float? {
        val m = rx.findAll(s).lastOrNull() ?: return null
        val done = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[2].toLongOrNull() ?: return null
        if (total <= 0 || done > total) return null
        return done.toFloat() / total
    }
}

/** Thin M3-style linear progress; indeterminate until [showProgress] finds a counter. */
fun busyBar(ctx: Context): ProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 1000
    isIndeterminate = true
    indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
    progressTintList = ColorStateList.valueOf(Palette.ACCENT)
    progressBackgroundTintList = ColorStateList.valueOf(Palette.ACCENT_SOFT)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Switches the bar between determinate (message has "i/n") and indeterminate. */
fun ProgressBar.showProgress(message: String) {
    val f = ProgressText.fraction(message)
    if (f == null) {
        if (!isIndeterminate) isIndeterminate = true
    } else {
        if (isIndeterminate) isIndeterminate = false
        progress = (f * max).toInt()
    }
}

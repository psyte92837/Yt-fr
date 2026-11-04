/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Built-in fonts for captions, subtitles and themes (assets/lumen_fonts, SIL OFL 1.1, all with Cyrillic).
 * A font value "lf:<id>" (see [EditTheme.font], [BlockLook.font]) names one of them; the generic ones
 * (sans, condensed, serif, mono, rounded) and "file:<name>" of a project stay as they were.
 */
object LumenFonts {
    const val PREFIX = "lf:"

    /** [caps] — the font is all capitals by design; [variable] — a wght axis to set (variable file). */
    data class LFont(val id: String, val name: String, val kind: String, val kindEn: String, val caps: Boolean = false, val variable: Int = 0) {
        val value: String get() = PREFIX + id
        val asset: String get() = "lumen_fonts/$id.ttf"
    }

    val ALL = listOf(
        LFont("montserrat", "Montserrat", "жирный гротеск", "bold sans"),
        LFont("oswald", "Oswald", "узкий", "condensed"),
        LFont("unbounded", "Unbounded", "широкий", "wide"),
        LFont("russoone", "Russo One", "спортивный", "sport"),
        LFont("rubikmonoone", "Rubik Mono One", "плакатный", "poster", caps = true),
        LFont("daysone", "Days One", "мягкий жирный", "soft bold"),
        LFont("comfortaa", "Comfortaa", "округлый", "rounded", variable = 700),
        LFont("yesevaone", "Yeseva One", "антиква", "display serif"),
        LFont("caveat", "Caveat", "рукописный", "handwritten"),
        LFont("amaticsc", "Amatic SC", "маркер", "marker", caps = true),
        LFont("pressstart2p", "Press Start 2P", "пиксельный", "pixel")
    )

    fun isBuiltIn(font: String): Boolean = font.startsWith(PREFIX)

    fun of(font: String): LFont? = if (isBuiltIn(font)) ALL.firstOrNull { it.id == font.removePrefix(PREFIX) } else null

    /** A font value is known: generic, built-in or a project file. */
    fun valid(font: String): Boolean = font in SchemeStyles.FONTS || of(font) != null || font.startsWith("file:")

    /** Name to show: the built-in font's name and kind, or the generic one. */
    fun label(font: String, ru: Boolean): String = of(font)?.let { "${it.name} · ${if (ru) it.kind else it.kindEn}" } ?: when (font) {
        "condensed" -> if (ru) "Узкий системный" else "System condensed"
        "serif" -> if (ru) "С засечками" else "Serif"
        "mono" -> if (ru) "Моноширинный" else "Monospace"
        "rounded" -> if (ru) "Округлый системный" else "System rounded"
        "", "sans" -> if (ru) "Системный" else "System"
        else -> font.removePrefix("file:")
    }

    /** Generic family close to a built-in font, for places that cannot load it (CSS of animations). */
    fun generic(font: String): String = when (of(font)?.id) {
        "oswald", "amaticsc" -> "condensed"
        "comfortaa", "daysone" -> "rounded"
        "yesevaone" -> "serif"
        "pressstart2p" -> "mono"
        null -> font
        else -> "sans"
    }

    /** Chips of the fonts, each name drawn in its own font ("" = the system / theme one first). */
    fun chips(ctx: Context, selected: String, onSelect: (String) -> Unit): ViewGroup {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val values = listOf("") + ALL.map { it.value }
        fun fill(sel: String) {
            box.removeAllViews()
            box.addView(chipRow(ctx, values.map { v ->
                chip(ctx, if (v.isEmpty()) t("Обычный", "Default") else of(v)!!.name, v == sel) {
                    if (v != sel) {
                        fill(v)
                        onSelect(v)
                    }
                }.also { c -> typeface(v)?.let { (c as? TextView)?.typeface = it } }
            }, collapseAfter = 99))
        }
        fill(selected)
        return box
    }

    /** Motion styles of captions with names, for chips. */
    fun motions(): List<Pair<String, String>> = listOf(
        "" to t("Нет", "None"), "karaoke" to t("🎤 Караоке", "🎤 Karaoke"), "word" to t("По слову", "Word by word"),
        "bounce" to t("⤴ Прыжок", "⤴ Bounce"), "box" to t("▮ Плашка под словом", "▮ Box under the word"), "wave" to t("〰 Волна", "〰 Wave"),
        "pop" to t("✨ Выпрыгивание", "✨ Pop in"), "stamp" to t("💥 Удар", "💥 Stamp"), "marker" to t("🖍 Маркер", "🖍 Highlighter"),
        "type" to t("⌨ Печать", "⌨ Typing"), "slide" to t("➡ Выезд", "➡ Slide in"), "glow" to t("💡 Свечение", "💡 Glow"),
        "shake" to t("〽 Тряска", "〽 Shake"), "underline" to t("▁ Подчёркивание", "▁ Underline"), "focus" to t("🔍 Фокус", "🔍 Focus")
    )

    private val cache = HashMap<String, Typeface?>()

    /** The typeface of built-in [font] (null when it is not one or cannot be loaded). */
    @Synchronized
    fun typeface(font: String): Typeface? {
        val f = of(font) ?: return null
        return cache.getOrPut(f.id) {
            runCatching {
                val am = Lumen.app.assets
                if (f.variable > 0 && Build.VERSION.SDK_INT >= 26) {
                    Typeface.Builder(am, f.asset).setFontVariationSettings("'wght' ${f.variable}").build()
                } else Typeface.createFromAsset(am, f.asset)
            }.getOrNull()
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.io.File

/**
 * A live preview of a block drawn by the Editor's own painter ([StudioEditPainter]) over a video-like gradient, looping
 * every [loopMs]: what is seen is what the block will look like in the video. [make] builds the block for the view size.
 */
@SuppressLint("ViewConstructor")
class TplPreview(
    ctx: Context, private val dir: File, private val styleDir: File, private val loopMs: Long,
    private val make: (w: Int, h: Int) -> EBlock
) : View(ctx) {
    private var painter: StudioEditPainter? = null
    private var block: EBlock? = null

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2B3A55.toInt(), 0xFF4A3B5E.toInt(), 0xFF7A4A52.toInt())).apply {
            cornerRadius = ctx.dp(12).toFloat()
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun release() {
        runCatching { painter?.close() }
        painter = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        release()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        val p = painter ?: StudioEditPainter(width, height, dir, emptyList(), StudioPrefs.accent, styleDir).also {
            painter = it
            block = make(width, height)
        }
        val b = block ?: return
        runCatching { p.draw(c, listOf(b), SystemClock.uptimeMillis() % loopMs) }
        if (isAttachedToWindow) postInvalidateDelayed(33)
    }
}

/** Sheets to pick a built-in caption style ([TextStyles]) or a scheme template ([SchemeTemplates]) by their live previews. */
object TplPicker {
    private const val TEXT_LOOP = 2800L
    private const val SCHEME_LOOP = 4200L

    private val ru: Boolean get() = I18n.ru

    /** A caption block that shows [st] on a [w]×[h] preview: a sample phrase whose words are said one by one. */
    fun textBlock(st: TextStyle, w: Int, h: Int, sample: String = ""): EBlock {
        val text = sample.ifBlank {
            when (st.words) {
                1 -> t("Вау", "Wow")
                2 -> t("Смотри сюда", "Look here")
                else -> t("Это просто работает", "This just works")
            }
        }
        val n = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val bw = 0.92f
        return EBlock("p", "text", 0, TEXT_LOOP - 200, x = 0.5f, y = 0.5f, w = bw, ratio = (h * 0.66f) / (w * bw),
            animIn = st.anim, animOut = "fade", look = st.look, text = text, motion = st.motion,
            wordMs = (0 until n).map { 300L + it * 430L })
    }

    /** A scheme block that shows [tpl] on a [w]×[h] preview. */
    fun schemeBlock(tpl: SchemeTpl, w: Int, h: Int): EBlock {
        val bw = 0.95f
        return EBlock("p", "scheme", 0, SCHEME_LOOP - 150, x = 0.5f, y = 0.5f, w = bw, ratio = (h * 0.92f) / (w * bw),
            animIn = "fade", animOut = "fade", look = tpl.look, scheme = SchemeTemplates.scheme(tpl, ru))
    }

    private fun card(ctx: Context, preview: View, previewDp: Int, title: String, sub: String, selected: Boolean, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = ctx.dp(6)
            setPadding(p, p, p, p)
            background = ripple(ctx, roundBg(if (selected) Palette.ACCENT_SOFT else Palette.CARD, 14f, ctx, if (selected) Palette.ACCENT else 0), Palette.ON_SURFACE)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(previewDp)))
            addView(text(ctx, title, 13f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            if (sub.isNotBlank()) addView(text(ctx, sub, 11f, color = Palette.MUTED).apply { maxLines = 2 })
            contentDescription = "$title. $sub"
            setOnClickListener { onClick() }
        }

    /** [cards] two in a row. */
    private fun grid(ctx: Context, box: LinearLayout, cards: List<View>) {
        box.removeAllViews()
        cards.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) marginStart = ctx.dp(8) })
            }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(8) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        }
        if (cards.isEmpty()) box.addView(text(ctx, t("Ничего не нашлось", "Nothing found"), 13f, color = Palette.MUTED))
    }

    private fun motionName(m: String): String = LumenFonts.motions().firstOrNull { it.first == m }?.second ?: m

    /**
     * «🎨 Готовые стили»: 100 animated caption styles by area with a search; a tap picks one ([onPick]) and closes the
     * sheet. [selected] — the id of the style now in use.
     */
    fun texts(host: LumenBase, dir: File, styleDir: File, selected: String, title: String = t("🎨 Готовые стили надписей", "🎨 Ready caption styles"),
              onPick: (TextStyle) -> Unit) {
        val ctx: Context = host
        val prefs = Lumen.prefs
        var area = TextStyles.of(selected)?.area ?: prefs.getString("tpl_text_area", null)?.takeIf { a -> TextStyles.AREAS.any { it.id == a } }
            ?: TextStyles.AREAS.first().id
        host.bottomSheet(title) { dismiss ->
            muted(t("100 стилей: шрифт, цвета, плашка и движение по словам. Превью — как будет в ролике; шрифт и акцент потом можно поменять.",
                "100 styles: font, colours, plate and motion by the words. The preview is how it looks in the video; font and accent can be changed later."))
            val q = edit(t("🔍 Поиск: неон, маркер, новости…", "🔍 Search: neon, marker, news…"))
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun fill() {
                val query = q.text.toString().trim()
                val list = if (query.isNotEmpty()) TextStyles.search(query).take(24) else TextStyles.inArea(area)
                grid(ctx, box, list.map { st ->
                    val pv = TplPreview(ctx, dir, styleDir, TEXT_LOOP) { w, h -> textBlock(st, w, h) }
                    card(ctx, pv, 84, TextStyles.name(st, ru), motionName(st.motion).let { if (st.motion.isEmpty()) t("без движения", "no motion") else it },
                        st.id == selected) {
                        prefs.edit().putString("tpl_text_area", st.area).apply()
                        dismiss()
                        onPick(st)
                    }
                })
            }
            add(choiceChips(ctx, TextStyles.AREAS.map { it.id to TextStyles.areaName(it.id, ru) }, area) {
                area = it
                q.setText("")
                fill()
            }, 4)
            q.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = fill()
            })
            add(box, 4)
            fill()
        }
    }

    /** «📚 Готовые схемы»: 100 scheme templates of known frameworks by field with a search; a tap picks one. */
    fun schemes(host: LumenBase, dir: File, styleDir: File, onPick: (SchemeTpl) -> Unit) {
        val ctx: Context = host
        val prefs = Lumen.prefs
        var area = prefs.getString("tpl_scheme_area", null)?.takeIf { a -> SchemeTemplates.AREAS.any { it.id == a } } ?: SchemeTemplates.AREAS.first().id
        host.bottomSheet(t("📚 Готовые схемы", "📚 Ready schemes")) { dismiss ->
            muted(t("100 схем-фреймворков из разных областей (SWOT, AIDA, Маслоу, PDCA…) в современных стилях. Вставьте как есть, поправьте текстом или дайте ИИ заполнить по речи.",
                "100 framework schemes from different fields (SWOT, AIDA, Maslow, PDCA…) in modern looks. Insert as is, edit as text or let the AI fill it from the speech."))
            val q = edit(t("🔍 Поиск: воронка, цикл, матрица…", "🔍 Search: funnel, cycle, matrix…"))
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun fill() {
                val query = q.text.toString().trim()
                val list = if (query.isNotEmpty()) SchemeTemplates.search(query).take(16) else SchemeTemplates.inArea(area)
                grid(ctx, box, list.map { tpl ->
                    val pv = TplPreview(ctx, dir, styleDir, SCHEME_LOOP) { w, h -> schemeBlock(tpl, w, h) }
                    card(ctx, pv, 112, SchemeTemplates.name(tpl, ru), SchemeTemplates.use(tpl, ru), false) {
                        prefs.edit().putString("tpl_scheme_area", tpl.area).apply()
                        dismiss()
                        onPick(tpl)
                    }
                })
            }
            add(choiceChips(ctx, SchemeTemplates.AREAS.map { it.id to SchemeTemplates.areaName(it.id, ru) }, area) {
                area = it
                q.setText("")
                fill()
            }, 4)
            q.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = fill()
            })
            add(box, 4)
            fill()
        }
    }
}

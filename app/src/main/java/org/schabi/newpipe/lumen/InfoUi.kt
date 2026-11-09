/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import java.io.File

/**
 * The one way to choose and tune info-blocks everywhere (Studio, Mix, Editor), built like «🎨 Готовые стили» of the
 * captions: a card with the live preview of the current style and two buttons — «🎨 Стиль (100)» (areas, search,
 * previews that move as in the video) and «🎛 Настроить» (opacity, plate, entrance, life on screen, speed, cascade,
 * links, text, corners, capitals, shadow, accent, font). The style and its options are one string — the spec
 * "ib:<id>?…" ([InfoStyles]) — kept as the look of a block or of the Studio overlay.
 */
object InfoUi {
    const val LOOP = 4200L

    private val ru: Boolean get() = I18n.ru

    /** An info-block that shows [spec] (auto when it is not a spec) on a [w]×[h] preview. */
    fun previewBlock(spec: String, w: Int, h: Int, scheme: SScheme? = null): EBlock {
        val st = InfoStyles.of(spec)
        val s = scheme ?: st?.let { InfoStyles.sample(it, ru) } ?: InfoStyles.sample(InfoStyles.ALL.first(), ru, 0)
        val bw = 0.95f
        return EBlock("p", "scheme", 0, LOOP - 150, x = 0.5f, y = 0.5f, w = bw, ratio = (h * 0.9f) / (w * bw),
            animIn = "none", animOut = "fade", look = if (st != null) spec else SchemeStyles.AUTO, scheme = s)
    }

    private fun preview(ctx: Context, spec: String, scheme: SScheme? = null): View =
        TplPreview(ctx, ctx.cacheDir, StudioEditStore.styleDir(ctx), LOOP) { w, h -> previewBlock(spec, w, h, scheme) }

    /** Name of the style of [spec] ("Авто" for auto). */
    fun name(spec: String): String = InfoStyles.of(spec)?.let { InfoStyles.name(it, ru) } ?: t("Авто — цвета ролика", "Auto — the video's colours")

    /** One line under the preview: the style, its motion and what the options change. */
    fun line(spec: String): String {
        val st = InfoStyles.of(spec) ?: return t("Цвета берутся из стиля ролика; выберите стиль, чтобы настроить движение и прозрачность.",
            "The colours come from the video style; pick a style to tune the motion and the opacity.")
        val o = InfoStyles.opts(spec)
        return listOf(InfoStyles.areaName(st.area, ru), InfoStyles.about(st, ru), InfoStyles.optsSummary(o, ru)).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * The card of the current style: preview, name, line and «🎨 Стиль (100)» / «🎛 Настроить». [auto] — auto is a choice
     * (Studio, Mix); [extra] — buttons of the screen under it.
     */
    fun card(c: Col, host: LumenBase, spec: String, auto: Boolean, onChange: (String) -> Unit): Unit = with(c) {
        add(preview(ctx, spec), 6).layoutParams.height = ctx.dp(132)
        add(text(ctx, "🧩 " + name(spec), 14f, bold = true), 6)
        muted(line(spec))
        val pick = btn(ctx, t("🎨 Стиль (100)", "🎨 Style (100)"), false, BtnStyle.TONAL) { picker(host, spec, auto, onPick = onChange) }
        if (InfoStyles.isSpec(spec)) row(pick, btn(ctx, t("🎛 Настроить", "🎛 Tune"), false, BtnStyle.TONAL) { options(host, spec, onChange) })
        else add(pick, 4)
    }

    private fun tile(ctx: Context, preview: View, title: String, sub: String, selected: Boolean, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = ctx.dp(6)
            setPadding(p, p, p, p)
            background = ripple(ctx, roundBg(if (selected) Palette.ACCENT_SOFT else Palette.CARD, 14f, ctx, if (selected) Palette.ACCENT else 0), Palette.ON_SURFACE)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(104)))
            addView(text(ctx, title, 13f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            if (sub.isNotBlank()) addView(text(ctx, sub, 11f, color = Palette.MUTED).apply { maxLines = 2 })
            contentDescription = "$title. $sub"
            setOnClickListener { onClick() }
        }

    private fun grid(ctx: Context, box: LinearLayout, tiles: List<View>) {
        box.removeAllViews()
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v -> row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) marginStart = ctx.dp(8) }) }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(8) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        }
        if (tiles.isEmpty()) box.addView(text(ctx, t("Ничего не нашлось", "Nothing found"), 13f, color = Palette.MUTED))
    }

    /**
     * «🎨 Стиль инфоблоков»: 100 motion styles by area with a search. A tap picks the style and keeps the options the
     * user has set ([spec] → [onPick] with the new id). [scheme] — what the previews show (the real block when there is
     * one).
     */
    fun picker(host: LumenBase, spec: String, auto: Boolean, scheme: SScheme? = null, title: String = t("🎨 Стиль инфоблоков", "🎨 Info-block style"),
               onPick: (String) -> Unit) {
        val ctx: Context = host
        val prefs = Lumen.prefs
        val cur = InfoStyles.of(spec)
        var area = cur?.area ?: prefs.getString("info_area", null)?.takeIf { a -> InfoStyles.AREAS.any { it.id == a } } ?: InfoStyles.AREAS.first().id
        host.bottomSheet(title) { dismiss ->
            muted(t("100 современных стилей: цвета, подложка, появление блоков, «жизнь» на экране и связи. Превью — как в ролике. Ваши настройки (прозрачность, скорость…) сохранятся.",
                "100 modern styles: colours, plate, how the blocks come in, how they live on screen and the links. The preview is as in the video. Your options (opacity, speed…) are kept."))
            val q = edit(t("🔍 Поиск: неон, стекло, печать, пружина…", "🔍 Search: neon, glass, typewriter, elastic…"))
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun choose(id: String?) {
                dismiss()
                onPick(if (id == null) SchemeStyles.AUTO else if (InfoStyles.isSpec(spec)) InfoStyles.withStyle(spec, id) else InfoStyles.spec(id))
            }
            fun fill() {
                val query = q.text.toString().trim()
                val list = if (query.isNotEmpty()) InfoStyles.search(query).take(24) else InfoStyles.inArea(area)
                val tiles = list.map { st ->
                    val s = if (InfoStyles.isSpec(spec)) InfoStyles.withStyle(spec, st.id) else InfoStyles.spec(st.id)
                    tile(ctx, preview(ctx, s, scheme), InfoStyles.name(st, ru), InfoStyles.about(st, ru), st.id == cur?.id) {
                        prefs.edit().putString("info_area", st.area).apply()
                        choose(st.id)
                    }
                }
                val autoTile = if (auto && query.isEmpty() && area == InfoStyles.AREAS.first().id)
                    listOf(tile(ctx, preview(ctx, SchemeStyles.AUTO, scheme), t("Авто", "Auto"), t("цвета стиля ролика", "the video style's colours"), cur == null) { choose(null) })
                else emptyList()
                grid(ctx, box, autoTile + tiles)
            }
            add(choiceChips(ctx, InfoStyles.AREAS.map { it.id to InfoStyles.areaName(it.id, ru) }, area) {
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

    private fun speedLabel(v: Float) = "×" + (if (v % 1f == 0f) v.toInt().toString() else v.toString())

    /**
     * «🎛 Настроить»: everything on top of the style, with a live preview at the top. Each change goes to [onChange]
     * at once (the screen redraws), «↺ Как в стиле» drops all the options.
     */
    fun options(host: LumenBase, spec0: String, onChange: (String) -> Unit, scheme: SScheme? = null) {
        val st = InfoStyles.of(spec0) ?: return
        var spec = spec0
        host.bottomSheet(t("🎛 Настроить: ", "🎛 Tune: ") + InfoStyles.name(st, ru)) { dismiss ->
            val holder = FrameLayout(ctx)
            add(holder, 4).layoutParams.height = ctx.dp(150)
            fun showPreview() {
                holder.removeAllViews()
                holder.addView(preview(ctx, spec, scheme), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            showPreview()
            val box = Col(ctx, 0)
            add(box.root, 0)
            fun set(o: InfoOpts) {
                spec = InfoStyles.spec(st.id, o)
                onChange(spec)
                showPreview()
            }
            fun fill() {
                box.clear()
                val o = InfoStyles.opts(spec)
                with(box) {
                    fun label(s: String) = add(text(ctx, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)
                    fun <T> pick(title: String, opts: List<Pair<T, String>>, sel: T, upd: (T) -> InfoOpts) {
                        label(title)
                        add(choiceChips(ctx, opts, sel) { v -> set(upd(v)); fill() }, 4)
                    }
                    val value = label(t("Прозрачность: ", "Opacity: ") + "${o.alpha}%")
                    add(SeekBar(ctx).apply {
                        max = 90
                        progress = o.alpha - 10
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                                if (fromUser) value.text = t("Прозрачность: ", "Opacity: ") + "${v + 10}%"
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {
                                set(InfoStyles.opts(spec).copy(alpha = (s?.progress ?: 90) + 10))
                                fill()
                            }
                        })
                    }, 2)
                    add(choiceChips(ctx, listOf(100, 85, 70, 50, 30).map { it to "$it%" }, o.alpha) { v -> set(InfoStyles.opts(spec).copy(alpha = v)); fill() }, 2)
                    pick(t("Подложка блоков", "Block plate"), listOf("" to t("Как в стиле", "As the style"), "solid" to t("▣ Плотная", "▣ Solid"),
                        "glass" to t("◩ Стекло", "◩ Glass"), "clear" to t("◻ Без подложки", "◻ None"), "outline" to t("▢ Контур", "▢ Outline")), o.bg) { o.copy(bg = it) }
                    pick(t("Появление блоков", "How blocks come in"), listOf("" to t("Как в стиле", "As the style") + " (" + InfoStyles.motionName(st.look.motion, ru) + ")") +
                        InfoStyles.MOTIONS.map { it to InfoStyles.motionName(it, ru) }, o.motion) { o.copy(motion = it) }
                    pick(t("Жизнь на экране", "Life on screen"), listOf("" to t("Как в стиле", "As the style")) +
                        InfoStyles.IDLES.map { it to InfoStyles.idleName(it, ru) }, o.idle) { o.copy(idle = it) }
                    pick(t("Скорость", "Speed"), InfoStyles.SPEEDS.map { it to speedLabel(it) }, o.speed) { o.copy(speed = it) }
                    pick(t("Каскад блоков", "Cascade"), listOf("" to t("Как в стиле", "As the style"), "none" to t("По словам", "By the words"),
                        "slow" to t("Один за другим", "One by one")), o.stagger) { o.copy(stagger = it) }
                    pick(t("Связи", "Links"), listOf("" to t("Как в стиле", "As the style")) + InfoStyles.LINKS.map { it to InfoStyles.linkName(it, ru) }, o.link) { o.copy(link = it) }
                    pick(t("Текст", "Text"), listOf("s" to t("Мельче", "Smaller"), "m" to t("Обычный", "Normal"), "l" to t("Крупнее", "Bigger")), o.text) { o.copy(text = it) }
                    pick(t("Углы", "Corners"), listOf("" to t("Как в стиле", "As the style"), "square" to t("Острые", "Square"),
                        "round" to t("Скруглённые", "Rounded"), "pill" to t("Таблетка", "Pill")), o.corners) { o.copy(corners = it) }
                    pick(t("Заглавные", "Capitals"), listOf("" to t("Как в стиле", "As the style"), "on" to "ABC", "off" to "Abc"), o.caps) { o.copy(caps = it) }
                    pick(t("Тень", "Shadow"), listOf("" to t("Как в стиле", "As the style"), "on" to t("Есть", "On"), "off" to t("Нет", "Off")), o.shadow) { o.copy(shadow = it) }
                    label(t("Акцент", "Accent"))
                    chips(listOf(chip(ctx, t("Как в стиле", "As the style"), o.accent == null) { set(InfoStyles.opts(spec).copy(accent = null)); fill() }) +
                        InfoStyles.ACCENTS.map { col ->
                            chip(ctx, "●", o.accent == col) { set(InfoStyles.opts(spec).copy(accent = col)); fill() }.also { v ->
                                (v as? android.widget.TextView)?.setTextColor(col)
                                v.contentDescription = SchemeStyles.hex(col)
                            }
                        }, collapseAfter = 99)
                    label(t("Шрифт", "Font"))
                    add(LumenFonts.chips(ctx, o.font) { v -> set(InfoStyles.opts(spec).copy(font = v)) }, 4)
                    if (!o.isDefault) button(t("↺ Всё как в стиле", "↺ Everything as the style"), style = BtnStyle.TEXT) {
                        set(InfoOpts())
                        fill()
                    }
                }
            }
            fill()
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }

    /** Directory of the user's own scheme looks (kept for the old looks; the previews draw with it). */
    fun styleDir(ctx: Context): File = StudioEditStore.styleDir(ctx)
}

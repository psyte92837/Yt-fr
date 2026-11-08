/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * The one control of every Studio section (and the Mix) for what goes over the picture: «💬 Субтитры» or
 * «🧩 Инфосхемы». With schemes the background of the blocks is chosen right there (solid / glass / clear, as in the
 * Editor); the rest sits in «⚙ Опции» with good defaults. The AI composes the schemes by itself when they are chosen
 * (and before the build), there are no separate scheme buttons any more.
 */
object StudioOverlayUi {
    /**
     * Where the control is: [video] — the picture is a video / clips (look, size and placement matter), [cartoon] —
     * the animation with characters, [schemes] — how many schemes the scenes have, [working] — the AI composes them,
     * [inserts] — the montage has full-screen moments by itself (a cut montage: scenes with no clip of their own).
     */
    class Where(val video: Boolean, val cartoon: Boolean, val schemes: Int, val working: Boolean, val inserts: Boolean = false)

    /** Are there moments where a scheme would cover the whole video: then the choice [StudioOverlay.full] matters. */
    fun hasFull(ov: StudioOverlay, w: Where) = w.video && ov.schemes && (ov.place == "full" || w.inserts)

    fun fullLabel(f: String) = when (f) {
        StudioOverlay.BLOCKS_ONLY -> t("🧩 Только инфоблоки", "🧩 Blocks only")
        StudioOverlay.SUBS_ONLY -> t("💬 Только субтитры", "💬 Subtitles only")
        StudioOverlay.OFF -> t("✕ Выключить", "✕ Off")
        else -> t("🖼 На весь экран", "🖼 Full screen")
    }

    fun fullHint(f: String) = when (f) {
        StudioOverlay.BLOCKS_ONLY -> t("Видео идёт дальше, поверх — только блоки схемы на прозрачном фоне (текст и значки с тенью), без субтитров.",
            "The video plays on, with only the blocks of the scheme over it on a transparent background (text and icons with a shadow), no subtitles.")
        StudioOverlay.SUBS_ONLY -> t("Схема не вставляется: видео идёт дальше, только субтитры.", "No scheme insert: the video plays on, subtitles only.")
        StudioOverlay.OFF -> t("Ни схемы, ни субтитров: в эти моменты просто видео.", "Neither the scheme nor subtitles: just the video at these moments.")
        else -> t("Схема вставкой на весь экран, видео под ней не видно.", "The scheme as a full-screen insert, the video is hidden under it.")
    }

    fun bgLabel(bg: String) = when (bg) {
        "glass" -> t("◩ Полупрозрачный", "◩ Semi-transparent")
        "clear" -> t("◻ Прозрачный", "◻ Transparent")
        else -> t("▣ Плотный", "▣ Solid")
    }

    private fun sizeLabel(s: String) = when (s) {
        "s" -> t("Компактно", "Compact")
        "l" -> t("Крупно", "Large")
        else -> t("Средне", "Medium")
    }

    private fun placeLabel(p: String) = when (p) {
        "side" -> t("Сбоку от спикера", "Beside the speaker")
        "pip" -> t("Спикер в углу", "Speaker in a corner")
        "full" -> t("На весь экран", "Full screen")
        else -> t("Поверх видео", "Over the video")
    }

    private fun amountLabel(a: String) = when (a) {
        "few" -> t("Меньше", "Fewer")
        "many" -> t("Больше", "More")
        else -> t("Обычно", "Normal")
    }

    private fun lookName(id: String): String = SchemeStyles.BUILT_IN.firstOrNull { it.id == id }?.let { p ->
        if (t("ru", "en") == "ru") p.name else when (p.id) {
            SchemeStyles.AUTO -> "Auto"
            "clean" -> "Clean"
            "glass" -> "Glass"
            "paper" -> "Paper"
            "neon" -> "Neon"
            "bold" -> "Bold"
            "soft" -> "Soft"
            "news" -> "News"
            "minimal" -> "Minimal"
            else -> p.id.replaceFirstChar { it.uppercase() }
        }
    } ?: id

    private fun subName(ov: StudioOverlay): String = TextStyles.of(ov.subStyle)?.let { TextStyles.name(it, I18n.ru) }
        ?: t("как в стиле ролика", "as the video style")

    /** One line under the choice: what the viewer will see. */
    fun summary(ov: StudioOverlay, w: Where): String {
        if (!ov.schemes) return t("Только субтитры под голос: без схем, заголовков и стикеров. Стиль: ", "Subtitles with the voice only: no schemes, headlines or stickers. Style: ") + subName(ov) + "."
        val parts = mutableListOf<String>()
        parts += if (w.schemes > 0) t("схем: ${w.schemes}", "schemes: ${w.schemes}") else t("ИИ составит схемы сам", "the AI composes the schemes")
        parts += if (ov.subsToo) t("субтитры: ", "subtitles: ") + subName(ov) else t("без субтитров", "no subtitles")
        if (w.video) {
            parts += lookName(ov.look)
            parts += placeLabel(ov.place).lowercase()
        }
        if (hasFull(ov, w) && ov.full != StudioOverlay.FULL) parts += t("на весь экран: ", "full screen: ") + fullLabel(ov.full).substringAfter(' ').lowercase()
        if (w.cartoon) parts += if (ov.toon == "big") t("крупно", "big") else t("на доске", "on a board")
        parts += sizeLabel(ov.size).lowercase()
        return parts.joinToString(" · ")
    }

    /** The choice, the background of the blocks and «⚙ Опции». */
    fun block(c: Col, ov: StudioOverlay, w: Where, set: (StudioOverlay) -> Unit, options: () -> Unit): Unit = with(c) {
        add(text(ctx, t("Поверх видео", "Over the picture"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(segBar(ctx, listOf(t("💬 Субтитры", "💬 Subtitles"), t("🧩 Инфосхемы", "🧩 Info-schemes")), if (ov.schemes) 1 else 0) {
            set(ov.copy(mode = StudioOverlay.MODES[it]))
        }, 4)
        muted(summary(ov, w))
        if (ov.subs) (ctx as? LumenBase)?.let { host -> subStyleRow(this, host, ov, set) }
        if (!ov.schemes) return@with
        add(text(ctx, t("Фон блоков", "Block background"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        chips(StudioOverlay.BGS.map { b -> chip(ctx, bgLabel(b), ov.bg == b) { if (ov.bg != b) set(ov.copy(bg = b)) } })
        if (ov.bg == "clear") muted(t("Без подложки: только текст и значки с мягкой тенью — видео видно целиком.",
            "No plate: text and icons with a soft shadow only — the whole video stays visible."))
        if (hasFull(ov, w)) {
            add(text(ctx, t("Где схема на весь экран", "Where a scheme is full screen"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
            chips(StudioOverlay.FULLS.map { f -> chip(ctx, fullLabel(f), ov.full == f) { if (ov.full != f) set(ov.copy(full = f)) } })
            muted(fullHint(ov.full))
        }
        if (!w.working) button(t("⚙ Опции схем", "⚙ Scheme options"), style = BtnStyle.TEXT) { options() }
    }

    /**
     * «🎨 Стиль субтитров»: one of the 100 built-in caption styles (the same as in the Editor) with its live preview, or
     * the subtitles of the video style.
     */
    fun subStyleRow(c: Col, host: LumenBase, ov: StudioOverlay, set: (StudioOverlay) -> Unit): Unit = with(c) {
        val cur = TextStyles.of(ov.subStyle)
        if (cur != null) add(TplPreview(ctx, ctx.cacheDir, StudioEditStore.styleDir(ctx), 2800L) { pw, ph -> TplPicker.textBlock(cur, pw, ph) }, 6)
            .layoutParams.height = ctx.dp(72)
        val pick = btn(ctx, if (cur != null) t("🎨 Сменить стиль субтитров", "🎨 Change the subtitle style") else t("🎨 Стиль субтитров (100 готовых)", "🎨 Subtitle style (100 ready)"),
            false, BtnStyle.TONAL) {
            TplPicker.texts(host, ctx.cacheDir, StudioEditStore.styleDir(ctx), ov.subStyle, t("🎨 Стиль субтитров", "🎨 Subtitle style")) { st -> set(ov.copy(subStyle = st.id)) }
        }
        if (cur == null) add(pick, 4)
        else row(pick, btn(ctx, t("✕ Как в стиле", "✕ As the style"), false, BtnStyle.TEXT) { set(ov.copy(subStyle = "")) })
    }

    /** «📚 Готовая схема»: a framework template turned into the wish for the AI that composes the schemes ([wish] field). */
    fun templateWish(c: Col, host: LumenBase, wish: android.widget.EditText): Unit = with(c) {
        button(t("📚 Взять за основу готовую схему (100)", "📚 Base it on a ready scheme (100)"), style = BtnStyle.TEXT) {
            TplPicker.schemes(host, host.cacheDir, StudioEditStore.styleDir(host)) { tpl ->
                wish.setText(SchemeTemplates.studioWish(tpl, I18n.ru))
                host.toast(t("Шаблон «${tpl.name}» в пожелании — составьте схемы", "Template \"${tpl.nameEn}\" is in the wish — compose the schemes"))
            }
        }
    }

    /**
     * «⚙ Опции»: look, size, placement, subtitles together, how many schemes; [actions] adds the screen's own lines
     * (wish, compose again, remove, only ticked pieces).
     */
    fun sheet(host: LumenBase, ov0: StudioOverlay, w: Where, set: (StudioOverlay) -> Unit, actions: Col.(dismiss: () -> Unit) -> Unit) {
        var ov = ov0
        host.bottomSheet(t("⚙ Опции инфосхем", "⚙ Info-scheme options")) { dismiss ->
            val box = Col(ctx, 0)
            add(box.root, 0)
            fun change(n: StudioOverlay, refill: () -> Unit) {
                ov = n
                set(n)
                refill()
            }
            fun fill() {
                box.clear()
                with(box) {
                    fun label(s: String) = add(text(ctx, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)
                    label(t("Фон блоков", "Block background"))
                    chips(StudioOverlay.BGS.map { b -> chip(ctx, bgLabel(b), ov.bg == b) { change(ov.copy(bg = b)) { fill() } } })
                    if (w.video) {
                        label(t("Оформление", "Look"))
                        chips(SchemeStyles.BUILT_IN.map { p -> chip(ctx, lookName(p.id), ov.look == p.id) { change(ov.copy(look = p.id)) { fill() } } })
                        muted(t("Авто — цвета ролика. Остальные — готовые наборы цветов, шрифта и появления; фон блоков выбирается выше.",
                            "Auto — the colours of the video. The rest are ready sets of colours, font and entrance; the background is chosen above."))
                        label(t("Где", "Where"))
                        chips(StudioOverlay.PLACES.map { p -> chip(ctx, placeLabel(p), ov.place == p) { change(ov.copy(place = p)) { fill() } } })
                        muted(when (ov.place) {
                            "side" -> t("Кадр со спикером сдвигается, схема рядом — для подкастов и говорящих голов.", "The speaker's frame slides aside, the scheme beside it — for podcasts and talking heads.")
                            "pip" -> t("Спикер уходит в угол, схема занимает кадр.", "The speaker goes into a corner, the scheme takes the frame.")
                            "full" -> t("Схема на весь экран вместо видео; что показывать в эти моменты — ниже.", "The scheme full screen instead of the video; what these moments show — below.")
                            else -> t("Схема поверх видео, видео остаётся видно.", "The scheme over the video, the video stays visible.")
                        })
                    }
                    if (hasFull(ov, w)) {
                        label(t("Где схема на весь экран", "Where a scheme is full screen"))
                        chips(StudioOverlay.FULLS.map { f -> chip(ctx, fullLabel(f), ov.full == f) { change(ov.copy(full = f)) { fill() } } })
                        muted(fullHint(ov.full) + if (w.inserts && ov.full != StudioOverlay.FULL)
                            t(" В нарезке на этих местах продолжается соседний клип.", " In the cut montage the clip next to them plays on there.") else "")
                    }
                    if (w.cartoon) {
                        label(t("В анимации", "In the animation"))
                        chips(StudioOverlay.TOONS.map { p ->
                            chip(ctx, if (p == "big") t("Крупно, герои меньше", "Big, characters smaller") else t("На доске над героями", "On a board above the characters"), ov.toon == p) {
                                change(ov.copy(toon = p)) { fill() }
                            }
                        })
                    }
                    label(t("Размер", "Size"))
                    chips(StudioOverlay.SIZES.map { s -> chip(ctx, sizeLabel(s), ov.size == s) { change(ov.copy(size = s)) { fill() } } })
                    label(t("Сколько схем", "How many schemes"))
                    chips(StudioOverlay.AMOUNTS.map { a -> chip(ctx, amountLabel(a), ov.amount == a) { change(ov.copy(amount = a)) { fill() } } })
                    check(t("💬 Субтитры вместе со схемами", "💬 Subtitles together with the schemes"), ov.subsToo) { v -> change(ov.copy(subsToo = v)) { fill() } }
                }
            }
            fill()
            actions(dismiss)
            button(t("Готово", "Done"), primary = true) { dismiss() }
        }
    }
}

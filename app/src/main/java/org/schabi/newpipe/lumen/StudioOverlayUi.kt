/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * The one control of every Studio section (and the Mix) for what goes over the picture: «💬 Субтитры» or
 * «🧩 Инфоблоки» (cards, numbers, lists and schemes are one thing now). With info-blocks there is
 * one entry row that opens the one info-block sheet of every screen ([InfoUi.entry], [InfoUi.hub]). The AI composes
 * the info-blocks by itself when they are chosen (and before the build), there are no separate buttons.
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
        StudioOverlay.BLOCKS_ONLY -> t("Видео идёт дальше, поверх — только блоки на прозрачном фоне (текст и значки с тенью), без субтитров.",
            "The video plays on, with only the blocks over it on a transparent background (text and icons with a shadow), no subtitles.")
        StudioOverlay.SUBS_ONLY -> t("Инфоблок не вставляется: видео идёт дальше, только субтитры.", "No info-block insert: the video plays on, subtitles only.")
        StudioOverlay.OFF -> t("Ни инфоблока, ни субтитров: в эти моменты просто видео.", "Neither the info-block nor subtitles: just the video at these moments.")
        else -> t("Инфоблок вставкой на весь экран, видео под ним не видно.", "The info-block as a full-screen insert, the video is hidden under it.")
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

    private fun subName(ov: StudioOverlay): String = TextStyles.of(ov.subStyle)?.let { TextStyles.name(it, I18n.ru) }
        ?: t("как в стиле ролика", "as the video style")

    /** One line under the choice: what the viewer will see. */
    fun summary(ov: StudioOverlay, w: Where): String {
        if (!ov.schemes) return t("Только субтитры под голос: без инфоблоков, заголовков и стикеров. Стиль: ", "Subtitles with the voice only: no info-blocks, headlines or stickers. Style: ") + subName(ov) + "."
        val parts = mutableListOf<String>()
        parts += if (w.schemes > 0) t("инфоблоков: ${w.schemes}", "info-blocks: ${w.schemes}") else t("ИИ составит инфоблоки сам", "the AI composes the info-blocks")
        parts += if (ov.subsToo) t("субтитры: ", "subtitles: ") + subName(ov) else t("без субтитров", "no subtitles")
        if (w.video) {
            parts += InfoUi.name(ov.look)
            parts += placeLabel(ov.place).lowercase()
        }
        if (hasFull(ov, w) && ov.full != StudioOverlay.FULL) parts += t("на весь экран: ", "full screen: ") + fullLabel(ov.full).substringAfter(' ').lowercase()
        if (w.cartoon) parts += if (ov.toon == "big") t("крупно", "big") else t("на доске", "on a board")
        parts += sizeLabel(ov.size).lowercase()
        return parts.joinToString(" · ")
    }

    /**
     * The choice «💬 Субтитры | 🧩 Инфоблоки»; with info-blocks — the entry row that opens [options] (the one sheet:
     * style, where and how many, the AI), the same as in the Editor.
     */
    fun block(c: Col, ov: StudioOverlay, w: Where, set: (StudioOverlay) -> Unit, options: () -> Unit): Unit = with(c) {
        add(text(ctx, t("Поверх видео", "Over the picture"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        add(segBar(ctx, listOf(t("💬 Субтитры", "💬 Subtitles"), t("🧩 Инфоблоки", "🧩 Info-blocks")), if (ov.schemes) 1 else 0) {
            set(ov.copy(mode = StudioOverlay.MODES[it]))
        }, 4)
        if (!ov.schemes) muted(summary(ov, w))
        if (ov.subs) (ctx as? LumenBase)?.let { host -> subStyleRow(this, host, ov, set) }
        if (!ov.schemes) return@with
        // the same entry as in the Editor: a tap opens the one info-block sheet (style, AI, where and how many)
        InfoUi.entry(this, ov.look, if (w.working) t("ИИ составляет инфоблоки…", "The AI composes the info-blocks…") else summary(ov, w)) {
            if (!w.working) options()
        }
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

    /** «📚 Готовая схема»: a framework template turned into the wish for the AI that composes the info-blocks ([wish] field). */
    fun templateWish(c: Col, host: LumenBase, wish: android.widget.EditText): Unit = with(c) {
        button(t("📚 Взять за основу готовую схему (100)", "📚 Base it on a ready scheme (100)"), style = BtnStyle.TEXT) {
            TplPicker.schemes(host, host.cacheDir, StudioEditStore.styleDir(host)) { tpl ->
                wish.setText(SchemeTemplates.studioWish(tpl, I18n.ru))
                host.toast(t("Шаблон «${tpl.name}» в пожелании — составьте инфоблоки", "Template \"${tpl.nameEn}\" is in the wish — compose the info-blocks"))
            }
        }
    }

    /**
     * «🧩 Инфоблоки» of the Studio and the Mix — the same sheet as in the Editor ([InfoUi.hub]): the style, where and
     * how many (placement, full-screen moments, the animation, size, amount, subtitles together), then [actions] of
     * the screen (wish, compose again, remove, only ticked pieces).
     */
    fun sheet(host: LumenBase, ov0: StudioOverlay, w: Where, set: (StudioOverlay) -> Unit, actions: Col.(dismiss: () -> Unit) -> Unit) {
        var ov = ov0
        fun change(n: StudioOverlay) {
            ov = n
            set(n)
        }
        InfoUi.hub(host, spec = { ov.look }, auto = true, setSpec = { change(ov.withInfo(it)) },
            note = summary(ov, w),
            place = { refresh ->
                fun label(s: String) = add(text(ctx, s, Type.LABEL, bold = true, color = Palette.MUTED), 10)
                fun pick(n: StudioOverlay) {
                    change(n)
                    refresh()
                }
                if (w.video) {
                    label(t("Где", "Where"))
                    chips(StudioOverlay.PLACES.map { p -> chip(ctx, placeLabel(p), ov.place == p) { pick(ov.copy(place = p)) } })
                    muted(when (ov.place) {
                        "side" -> t("Кадр со спикером сдвигается, инфоблок рядом — для подкастов и говорящих голов.", "The speaker's frame slides aside, the info-block beside it — for podcasts and talking heads.")
                        "pip" -> t("Спикер уходит в угол, инфоблок занимает кадр.", "The speaker goes into a corner, the info-block takes the frame.")
                        "full" -> t("Инфоблок на весь экран вместо видео; что показывать в эти моменты — ниже.", "The info-block full screen instead of the video; what these moments show — below.")
                        else -> t("Инфоблок поверх видео, видео остаётся видно.", "The info-block over the video, the video stays visible.")
                    })
                }
                if (hasFull(ov, w)) {
                    label(t("Где инфоблок на весь экран", "Where an info-block is full screen"))
                    chips(StudioOverlay.FULLS.map { f -> chip(ctx, fullLabel(f), ov.full == f) { pick(ov.copy(full = f)) } })
                    muted(fullHint(ov.full) + if (w.inserts && ov.full != StudioOverlay.FULL)
                        t(" В нарезке на этих местах продолжается соседний клип.", " In the cut montage the clip next to them plays on there.") else "")
                }
                if (w.cartoon) {
                    label(t("В анимации", "In the animation"))
                    chips(StudioOverlay.TOONS.map { p ->
                        chip(ctx, if (p == "big") t("Крупно, герои меньше", "Big, characters smaller") else t("На доске над героями", "On a board above the characters"), ov.toon == p) {
                            pick(ov.copy(toon = p))
                        }
                    })
                }
                label(t("Размер", "Size"))
                chips(StudioOverlay.SIZES.map { s -> chip(ctx, sizeLabel(s), ov.size == s) { pick(ov.copy(size = s)) } })
                label(t("Сколько инфоблоков", "How many info-blocks"))
                chips(StudioOverlay.AMOUNTS.map { a -> chip(ctx, amountLabel(a), ov.amount == a) { pick(ov.copy(amount = a)) } })
                check(t("💬 Субтитры вместе с инфоблоками", "💬 Subtitles together with the info-blocks"), ov.subsToo) { v -> pick(ov.copy(subsToo = v)) }
            },
            extra = { dismiss ->
                add(text(ctx, t("ИИ", "AI"), Type.LABEL, bold = true, color = Palette.MUTED), 14)
                actions(dismiss)
            })
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the blocks of the Studio editor on a Canvas of a [w]×[h] frame: the same code for the live preview
 * over the player and for the export, so what is seen is what gets into the file.
 * Pictures are read from the project folder [dir]; schemes reveal their nodes on the [words] of the voice.
 */
class StudioEditPainter(
    private val w: Int, private val h: Int, private val dir: File, private val words: List<SWord>,
    private val accent: Int, private val styleDir: File
) : java.io.Closeable {
    private val bitmaps = HashMap<String, Bitmap?>()
    private val painters = HashMap<String, StudioPainter>()
    private val reveal = HashMap<String, List<Long>>()
    private val fits = HashMap<String, Pair<Float, List<String>>>()
    private val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * Draws every block of [shown] (bottom first) at [tMs]. [under] — the picture of the video now (any size, lying over
     * [underAt] of the canvas, the whole frame when null): needed only for blocks that blur the video under them.
     */
    fun draw(c: Canvas, shown: List<EBlock>, tMs: Long, under: Bitmap? = null, underAt: RectF? = null) {
        // the video is blurred and darkens under the blocks first, then the blocks are drawn over it
        if (under != null && shown.any { it.blur > 0f }) {
            val made = HashMap<Int, Bitmap?>()
            val at = underAt ?: RectF(0f, 0f, w.toFloat(), h.toFloat())
            for (b0 in shown) if (b0.blur > 0f) runCatching {
                val b = EditKeys.at(b0, tMs)
                val r = StudioEdit.blurRect(b, w.toFloat(), h.toFloat()) ?: return@runCatching
                val tw = (at.width() / w * StudioEdit.blurWidth(b.blur)).toInt().coerceAtLeast(2)
                val small = made.getOrPut(tw) { shrink(under, tw) } ?: return@runCatching
                blurInto(c, small, at, r, StudioEdit.dimAlpha(b, tMs), img, fill)
            }
            made.values.forEach { if (it !== under) it?.recycle() }
        }
        for (b in shown) if (b.dim > 0f) runCatching { dim(c, EditKeys.at(b, tMs), tMs) }
        for (b in shown) runCatching { block(c, EditKeys.at(b, tMs), tMs) }.onFailure { L.w("studio", "edit block ${b.kind}: ${it.message}") }
    }

    companion object {
        /** [src] made [tw] px wide in halving steps (no shimmer); stretched back with filtering it is the blur. [src] when not wider. */
        fun shrink(src: Bitmap, tw0: Int): Bitmap? = runCatching {
            if (src.width <= tw0) return@runCatching src
            val tw = max(2, tw0)
            val th = max(2, (src.height.toLong() * tw / src.width).toInt())
            var cur = src
            while (cur.width / 2 >= tw && cur.height / 2 >= th) {
                val n = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
                if (cur !== src) cur.recycle()
                cur = n
            }
            if (cur.width == tw && cur.height == th) cur
            else Bitmap.createScaledBitmap(cur, tw, th, true).also { if (cur !== src && it !== cur) cur.recycle() }
        }.getOrNull()

        /**
         * The blurred video [small] stretched over [at] but seen only inside [clip] (px of the canvas), at [alpha]; a quarter darker,
         * like the blur of the GPU export.
         */
        fun blurInto(c: Canvas, small: Bitmap, at: RectF, clip: FRect, alpha: Float, img: Paint, fill: Paint) {
            if (alpha <= 0.01f) return
            c.save()
            c.clipRect(clip.l, clip.t, clip.r, clip.b)
            val a0 = img.alpha
            img.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            c.drawBitmap(small, null, at, img)
            img.alpha = a0
            fill.clearShadowLayer()
            fill.color = Color.argb((alpha * 64).toInt().coerceIn(0, 255), 0, 0, 0)
            c.drawRect(clip.l, clip.t, clip.r, clip.b, fill)
            c.restore()
        }
    }

    /** Darkens the video while [b] is shown: the whole frame or a rounded area under the block, fading in and out with it. */
    private fun dim(c: Canvas, b: EBlock, tMs: Long) {
        val a = b.dim * StudioEdit.dimAlpha(b, tMs)
        if (a <= 0.005f) return
        fill.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        if (b.dimAll) {
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
            return
        }
        val (hw, hh) = StudioEdit.halfSize(b, w.toFloat(), h.toFloat())
        val m = min(w, h) * 0.04f
        c.save()
        c.translate(b.x * w, b.y * h)
        if (b.rot != 0f) c.rotate(b.rot)
        c.drawRoundRect(RectF(-hw - m, -hh - m, hw + m, hh + m), m * 1.5f, m * 1.5f, fill)
        c.restore()
    }

    /** An animation block: its rendered frame at [tMs] (frames of another length are stretched), else a placeholder. */
    private fun anim(c: Canvas, b: EBlock, box: RectF, tMs: Long) {
        val meta = AnimRender.meta(dir, b) ?: return pending(c, box, "🎞",
            if (b.file.isEmpty()) waitText(b) else t("Анимация рисуется…", "Rendering the animation…"))
        val rd = File(dir, b.file)
        val i = EditInsert.frameAt(tMs - b.startMs, b, meta) ?: return
        val bmp = AnimFrames.frame(rd, i, meta)
        AnimFrames.prefetch(rd, i, meta)
        if (bmp == null) return
        // the code did not draw its own exit (the last frame is not empty): a soft fade at the very end
        val fade = EditInsert.tailFade(tMs - b.startMs, b.durMs, b.animOut == "none" && b.seg.isEmpty() && (meta.count - 1) !in meta.empty)
        img.alpha = (255 * fade).toInt()
        c.drawBitmap(bmp, null, box, img)
        img.alpha = 255
    }

    // ---------- native motion blocks and Lottie ----------

    private val briefs = HashMap<String, EditBrief>()
    private val themes = HashMap<String, EditTheme?>()
    private val faces2 = HashMap<String, Typeface>()
    private val primFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val primText = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun briefOf(b: EBlock): EditBrief = briefs.also { if (it.size > 200) it.clear() }.getOrPut(b.brief) { EditInsert.briefFrom(b.brief) ?: EditBrief() }

    /** The theme of a block: its own, else one in the accent of the block or of the video. */
    private fun themeOf(b: EBlock): EditTheme {
        val own = if (b.theme.isEmpty()) null else themes.also { if (it.size > 100) it.clear() }.getOrPut(b.theme) { EditThemes.parse(b.theme) }
        if (own != null) return own
        val base = EditThemes.preset("science")!!
        return base.copy(accent = b.over?.accent ?: accent)
    }

    /** A typeface of a theme font ("file:<name>" — a font file of the project in fonts/). */
    private fun face(font: String, weight: Int): Typeface = faces2.getOrPut("$font|$weight") {
        val style = if (weight >= 700) Typeface.BOLD else Typeface.NORMAL
        when {
            font.startsWith("file:") -> runCatching { Typeface.createFromFile(File(dir, "fonts/" + font.removePrefix("file:"))) }.getOrNull()?.let { Typeface.create(it, style) }
                ?: Typeface.create(Typeface.SANS_SERIF, style)
            // a built-in font is drawn as designed (no fake bold)
            LumenFonts.isBuiltIn(font) -> LumenFonts.typeface(font) ?: Typeface.create(Typeface.SANS_SERIF, style)
            font == "serif" -> Typeface.create(Typeface.SERIF, style)
            font == "mono" -> Typeface.create(Typeface.MONOSPACE, style)
            font == "condensed" -> Typeface.create("sans-serif-condensed", style)
            font == "rounded" -> Typeface.create("sans-serif-rounded", style)
            weight >= 900 -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            else -> Typeface.create(Typeface.SANS_SERIF, style)
        }
    }

    private fun motion(c: Canvas, b: EBlock, box: RectF, tMs: Long) {
        val br = briefOf(b)
        val th = themeOf(b)
        val ws = words.filter { it.startMs >= b.startMs && it.startMs < b.endMs }.map { it.text to (it.startMs - b.startMs) / 1000.0 }
        val prims = EditMotion.frame(b.look, EditMotion.content(br, ws), th, (tMs - b.startMs) / 1000.0, b.durMs / 1000.0, box.width(), box.height(),
            EditInsert.minFontPx(w, h, br.size).toFloat())
        c.save()
        c.translate(box.left, box.top)
        for (p in prims) prim(c, p, th)
        c.restore()
    }

    private fun alphaOf(color: Int, a: Float) = ((color ushr 24) * a).toInt().coerceIn(0, 255)

    private fun prim(c: Canvas, p: MPrim, th: EditTheme) {
        val m = min(w, h) / 1080f
        when (p) {
            is MPrim.Box -> {
                primFill.color = p.color
                primFill.alpha = alphaOf(p.color, p.a)
                if (p.glow > 0f) primFill.setShadowLayer(24f * m * p.glow + 1f, 0f, 0f, p.color) else primFill.clearShadowLayer()
                c.drawRoundRect(RectF(p.l, p.t, p.r, p.b), p.radius, p.radius, primFill)
                primFill.clearShadowLayer()
            }
            is MPrim.Arc -> {
                primLine.color = p.color
                primLine.alpha = alphaOf(p.color, p.a)
                primLine.strokeWidth = p.width
                if (p.glow > 0f) primLine.setShadowLayer(24f * m * p.glow + 1f, 0f, 0f, p.color) else primLine.clearShadowLayer()
                c.drawArc(RectF(p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r), p.start, p.sweep, false, primLine)
                primLine.clearShadowLayer()
            }
            is MPrim.Line -> {
                primLine.color = p.color
                primLine.alpha = alphaOf(p.color, p.a)
                primLine.strokeWidth = p.width
                c.drawLine(p.x1, p.y1, p.x2, p.y2, primLine)
            }
            is MPrim.Icon -> {
                emojiP.textSize = p.size
                emojiP.alpha = (255 * p.a).toInt().coerceIn(0, 255)
                val fm = emojiP.fontMetrics
                c.drawText(p.text, p.cx, p.cy - (fm.ascent + fm.descent) / 2, emojiP)
                emojiP.alpha = 255
            }
            is MPrim.Label -> {
                if (p.text.isBlank()) return
                primText.typeface = face(th.font, p.weight)
                primText.textSize = p.size
                primText.textAlign = when (p.align) { -1 -> Paint.Align.LEFT; 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                if (th.tracking != 0f) primText.letterSpacing = th.tracking
                if (p.maxW > 0f) {
                    val tw = primText.measureText(p.text)
                    if (tw > p.maxW) primText.textSize = p.size * p.maxW / tw
                }
                val fm = primText.fontMetrics
                val y = p.y - (fm.ascent + fm.descent) / 2
                if (p.outline || (th.plate == "none" && th.shadow)) {
                    primText.style = Paint.Style.STROKE
                    primText.strokeWidth = primText.textSize * 0.12f
                    primText.strokeJoin = Paint.Join.ROUND
                    primText.color = 0xE6000000.toInt()
                    primText.alpha = (230 * p.a).toInt().coerceIn(0, 255)
                    c.drawText(p.text, p.x, y, primText)
                    primText.style = Paint.Style.FILL
                }
                primText.color = p.color
                primText.alpha = alphaOf(p.color, p.a)
                if (p.glow > 0f) primText.setShadowLayer(18f * m * p.glow + 1f, 0f, 0f, p.color) else primText.clearShadowLayer()
                c.drawText(p.text, p.x, y, primText)
                primText.clearShadowLayer()
                primText.letterSpacing = 0f
            }
        }
    }

    private val lotties = HashMap<String, Pair<com.airbnb.lottie.LottieDrawable, EditLottie.Info>?>()

    private fun lottie(c: Canvas, b: EBlock, box: RectF, tMs: Long) {
        val br = briefOf(b)
        val key = b.file + "|" + br.fields.hashCode() + "|" + b.theme.hashCode()
        val got = lotties.getOrPut(key) {
            runCatching {
                val f = File(dir, b.file)
                val json = f.readText()
                val info = EditLottie.info(json) ?: return@runCatching null
                val comp = com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(json, key).value ?: return@runCatching null
                val d = com.airbnb.lottie.LottieDrawable()
                d.composition = comp
                val texts = br.fields.filterKeys { it.startsWith("lottie:") }
                if (texts.isNotEmpty()) {
                    val td = com.airbnb.lottie.TextDelegate(d)
                    texts.forEach { (k, v) -> td.setText(k.removePrefix("lottie:"), v) }
                    d.setTextDelegate(td)
                }
                if (br.fields["lottie_tint"] == "theme") {
                    val col = themeOf(b).accent
                    d.addValueCallback(com.airbnb.lottie.model.KeyPath("**"), com.airbnb.lottie.LottieProperty.COLOR_FILTER,
                        com.airbnb.lottie.value.LottieValueCallback<android.graphics.ColorFilter>(com.airbnb.lottie.SimpleColorFilter(col)))
                }
                d to info
            }.onFailure { L.w("studio", "lottie: ${it.message}") }.getOrNull()
        } ?: return pending(c, box, "✨", t("Lottie не открылся", "Lottie did not open"))
        val (d, info) = got
        d.progress = EditLottie.progress(tMs - b.startMs, b.durMs, info, b.look)
        val cw = max(1, info.w).toFloat()
        val ch = max(1, info.h).toFloat()
        val k = min(box.width() / cw, box.height() / ch)
        c.save()
        c.translate(box.centerX() - cw * k / 2, box.centerY() - ch * k / 2)
        c.scale(k, k)
        d.setBounds(0, 0, cw.toInt(), ch.toInt())
        d.draw(c)
        c.restore()
    }

    private fun waitText(b: EBlock): String =
        if (b.req.isNotEmpty()) t("Ждёт ответ ИИ #", "Waiting for the AI #") + b.req else t("ИИ рисует…", "The AI is drawing…")

    private fun block(c: Canvas, b: EBlock, tMs: Long) {
        // a split screen is a second video, not a picture over the frame
        if (b.kind == EditSplit.KIND) return
        val pose = StudioEdit.pose(b, tMs)
        val alpha = pose.alpha * b.opacity
        if (alpha <= 0.01f) return
        val (hw, hh) = StudioEdit.halfSize(b, w.toFloat(), h.toFloat())
        if (hw < 1f || hh < 1f) return
        c.save()
        c.translate((b.x + pose.dx) * w, (b.y + pose.dy) * h)
        if (b.rot + pose.rot != 0f) c.rotate(b.rot + pose.rot)
        if (pose.scale != 1f) c.scale(pose.scale, pose.scale)
        val box = RectF(-hw, -hh, hw, hh)
        // a wipe shows the block from the left
        if (pose.clip < 0.999f) c.clipRect(box.left - hw, box.top - hh, box.left + box.width() * pose.clip, box.bottom + hh)
        val layered = alpha < 0.995f
        if (layered) c.saveLayerAlpha(RectF(box).apply { inset(-hw * 0.25f - 8f, -hh * 0.25f - 8f) }, (alpha * 255).toInt().coerceIn(0, 255))
        when (b.kind) {
            "image" -> picture(c, b, box)
            "scheme" -> scheme(c, b, box, tMs)
            "text" -> caption(c, b, box, pose.reveal, tMs)
            EditInsert.KIND -> anim(c, b, box, tMs)
            EditMotion.KIND -> motion(c, b, box, tMs)
            EditLottie.KIND -> lottie(c, b, box, tMs)
            else -> emoji(c, b.text, box)
        }
        if (layered) c.restore()
        c.restore()
    }

    // ---------- pictures ----------

    private fun bitmap(file: String): Bitmap? = bitmaps.getOrPut(file) {
        val f = File(dir, file)
        if (!f.exists()) return@getOrPut null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return@getOrPut null
        var sample = 1
        val need = max(w, h)
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= need) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun picture(c: Canvas, b: EBlock, box: RectF) {
        val bmp = bitmap(b.file) ?: return placeholder(c, box, "🖼")
        val m = min(box.width(), box.height())
        when (b.look) {
            "round", "card" -> {
                val r = m * 0.08f
                var inner = box
                if (b.look == "card") {
                    fill.color = Color.WHITE
                    fill.setShadowLayer(m * 0.04f, 0f, m * 0.012f, 0x66000000)
                    c.drawRoundRect(box, r, r, fill)
                    fill.clearShadowLayer()
                    val bw = m * 0.03f
                    inner = RectF(box).apply { inset(bw, bw) }
                }
                c.save()
                c.clipPath(Path().apply { addRoundRect(inner, r * 0.8f, r * 0.8f, Path.Direction.CW) })
                c.drawBitmap(bmp, crop(bmp, inner.width() / inner.height()), inner, img)
                c.restore()
            }
            "circle" -> {
                val rad = m / 2
                val sq = RectF(box.centerX() - rad, box.centerY() - rad, box.centerX() + rad, box.centerY() + rad)
                c.save()
                c.clipPath(Path().apply { addCircle(box.centerX(), box.centerY(), rad, Path.Direction.CW) })
                c.drawBitmap(bmp, crop(bmp, 1f), sq, img)
                c.restore()
                line.color = Color.WHITE
                line.strokeWidth = m * 0.025f
                c.drawCircle(box.centerX(), box.centerY(), rad - line.strokeWidth / 2, line)
            }
            else -> c.drawBitmap(bmp, crop(bmp, box.width() / box.height()), box, img)
        }
    }

    /** The middle part of [bmp] with the shape [ratio] (width / height). */
    private fun crop(bmp: Bitmap, ratio: Float): Rect {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        return if (bw / bh > ratio) {
            val cw = bh * ratio
            Rect(((bw - cw) / 2).toInt(), 0, ((bw + cw) / 2).toInt(), bh.toInt())
        } else {
            val ch = bw / ratio
            Rect(0, ((bh - ch) / 2).toInt(), bw.toInt(), ((bh + ch) / 2).toInt())
        }
    }

    private fun placeholder(c: Canvas, box: RectF, icon: String) {
        fill.color = 0x66000000
        val r = min(box.width(), box.height()) * 0.08f
        c.drawRoundRect(box, r, r, fill)
        emoji(c, icon, RectF(box).apply { inset(box.width() * 0.3f, box.height() * 0.3f) })
    }

    // ---------- schemes ----------

    /** A scheme the AI is still drawing: a dashed frame with a note (seen in the editor, never exported). */
    private fun pending(c: Canvas, box: RectF, icon: String, label: String) {
        val m = min(box.width(), box.height())
        fill.color = 0x73000000
        c.drawRoundRect(box, m * 0.06f, m * 0.06f, fill)
        line.color = 0xCCFFFFFF.toInt()
        line.strokeWidth = max(2f, m * 0.012f)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(m * 0.04f, m * 0.03f), 0f)
        c.drawRoundRect(box, m * 0.06f, m * 0.06f, line)
        line.pathEffect = null
        emoji(c, icon, RectF(box.centerX() - m * 0.12f, box.centerY() - m * 0.3f, box.centerX() + m * 0.12f, box.centerY() - m * 0.06f))
        textP.textSize = m * 0.08f
        textP.color = Color.WHITE
        val tw = textP.measureText(label)
        if (tw > box.width() * 0.9f) textP.textSize *= box.width() * 0.9f / tw
        c.drawText(label, box.centerX(), box.centerY() + m * 0.12f, textP)
    }

    private fun style(id: String): SchemeStyle =
        (if (id.isBlank() || id == SchemeStyles.AUTO) null else SchemeStyles.find(styleDir, id))
            ?: SchemeStyle(SchemeStyles.AUTO, "", card = 0xFFFFFFFF.toInt(), ink = 0xFF14161B.toInt(), muted = 0xFF6B7280.toInt(), accent = accent,
                line = 0xFFE2E5EC.toInt(), bg = 0xF2F4F6FA.toInt(), fill = "solid", radius = 0.18f, border = 1f, shadow = true)

    private fun painter(id: String, over: BlockLook? = null): StudioPainter = painters.getOrPut(if (over == null) id else id + "|" + over.hashCode()) {
        val st = EditInsert.overStyle(style(id), over)
        StudioPainter(w, h, StudioPlan(SScript("", emptyList()), emptyList(), emptyList(), emptyList(), 0L, StudioStyle.VIDEO, accent, schemeStyle = st)).apply { live = false }
    }

    private fun light(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255 > 0.5

    private fun scheme(c: Canvas, b: EBlock, box: RectF, tMs: Long) {
        val s = b.scheme ?: return pending(c, box, "🧩", waitText(b))
        val p = painter(b.look, b.over)
        val st = p.schemeLook()
        // a panel behind the scheme so its text reads on any video: light under dark text, dark under light text
        val m = min(box.width(), box.height())
        val panel = if (light(st.ink)) (st.bg.takeIf { !light(it) } ?: 0xE6101318.toInt()) else 0xF2F4F6FA.toInt()
        // the look of the block may set the plate: none, its opacity or its kind
        val o = b.over
        val alpha = when {
            o == null -> Color.alpha(panel) / 255f
            o.bgAlpha >= 0 -> o.bgAlpha / 100f
            o.fill.isNotEmpty() -> EditInsert.alphaOf(o.fill, -1)
            else -> Color.alpha(panel) / 255f
        }
        if (alpha > 0.01f) {
            fill.color = panel
            fill.alpha = (alpha * 255 * Studio.appear(tMs, b.startMs, 250)).toInt().coerceIn(0, 255)
            fill.setShadowLayer(m * 0.03f, 0f, m * 0.01f, 0x55000000)
            val r = m * EditInsert.radiusOf(o?.radius.orEmpty(), 0.06f)
            c.drawRoundRect(box, r, r, fill)
            fill.clearShadowLayer()
        }
        val key = "${b.id}:${b.startMs}:${b.durMs}:${s.hashCode()}"
        if (reveal.size > 300) reveal.clear()
        val times = reveal.getOrPut(key) { StudioEdit.schemeTimes(s, words, b) }
        val inner = RectF(box).apply { inset(box.width() * 0.04f, box.height() * 0.05f) }
        p.schemeIn(c, s, inner, tMs, b.startMs, times)
    }

    // ---------- captions and emoji ----------

    /** Largest text size whose wrapped lines fit [maxW]×[maxH]. */
    private fun fit(text: String, maxW: Float, maxH: Float): Pair<Float, List<String>> = fits.also { if (it.size > 300) it.clear() }.getOrPut("$text|${maxW.toInt()}|${maxH.toInt()}") {
        var lo = 6f
        var hi = max(8f, maxH)
        var best = lo to wrap(text, maxW, lo)
        repeat(18) {
            val mid = (lo + hi) / 2
            val lines = wrap(text, maxW, mid)
            textP.textSize = mid
            val ok = lines.size * mid * 1.18f <= maxH && lines.all { textP.measureText(it) <= maxW }
            if (ok) {
                best = mid to lines
                lo = mid
            } else hi = mid
        }
        best
    }

    private fun wrap(text: String, maxW: Float, size: Float): List<String> {
        textP.textSize = size
        val out = mutableListOf<String>()
        for (para in text.lines()) {
            var cur = ""
            for (word in para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && textP.measureText(next) > maxW) {
                    out += cur
                    cur = word
                } else cur = next
            }
            out += cur
        }
        return out.ifEmpty { listOf("") }
    }

    private fun caption(c: Canvas, b: EBlock, box: RectF, reveal: Float = 1f, tMs: Long = -1) {
        if (b.text.isBlank()) return
        val o = b.over
        val accent = o?.accent ?: this.accent
        val oldFace = textP.typeface
        if (o != null && o.font.isNotEmpty()) textP.typeface = face(o.font, 900)
        try {
            captionText(c, b, box, reveal, accent, if (o?.upper == true) b.text.uppercase() else b.text, tMs)
        } finally {
            textP.typeface = oldFace
        }
    }

    /**
     * A caption drawn word by word by the times its words are said ([EBlock.motion], [EditCaptions.MOTIONS]):
     * karaoke, word, bounce, box, wave. The words stand where the whole caption would stand.
     */
    private fun captionWords(c: Canvas, b: EBlock, box: RectF, lines: List<String>, size: Float, ink: Int, accent: Int, tMs: Long) {
        textP.textSize = size
        val step = size * 1.18f
        val fm = textP.fontMetrics
        var y = box.centerY() - step * lines.size / 2 + step / 2 - (fm.ascent + fm.descent) / 2
        val words = lines.map { l -> l.split(' ').filter { it.isNotEmpty() } }
        val total = words.sumOf { it.size }
        val n = b.wordMs.size
        val cur = EditCaptions.wordAt(b, tMs)
        val space = textP.measureText(" ")
        // the said words of an accent plate stay in the ink (the accent would vanish on it)
        val hot = if (b.look == "accent") ink else accent
        textP.textAlign = Paint.Align.LEFT
        var k = 0
        for (ws in words) {
            val widths = ws.map { textP.measureText(it) }
            var x = box.centerX() - (widths.sum() + space * (ws.size - 1).coerceAtLeast(0)) / 2
            for ((j, w) in ws.withIndex()) {
                // the words of the text and the said words may differ in number (an edited caption): spread evenly
                val i = if (total == n) k else (k.toLong() * n / total.coerceAtLeast(1)).toInt()
                k++
                val said = i <= cur
                val p = if (said) EditCaptions.wordIn(b, i, tMs) else 0f
                val ww = widths[j]
                var color = ink
                var alpha = 1f
                c.save()
                when (b.motion) {
                    "karaoke" -> if (said) color = hot else if (b.look == "accent") alpha = 0.55f
                    "word" -> alpha = if (said) 0.25f + 0.75f * p else 0f
                    "wave" -> if (said) {
                        alpha = p
                        c.translate(0f, size * 0.4f * (1f - p))
                    } else alpha = 0f
                    "bounce" -> if (i == cur) {
                        val s = kotlin.math.sin(Math.PI * p).toFloat()
                        c.translate(0f, -size * 0.16f * s)
                        val k2 = 1.12f + 0.12f * s
                        c.scale(k2, k2, x + ww / 2, y + (fm.ascent + fm.descent) / 2)
                        color = hot
                    }
                    "box" -> if (i == cur) {
                        val pad = size * 0.14f
                        fill.color = accent
                        c.drawRoundRect(x - pad, y + fm.ascent * 0.92f, x + ww + pad, y + fm.descent * 1.1f, size * 0.2f, size * 0.2f, fill)
                        color = if (light(accent)) 0xFF14161B.toInt() else Color.WHITE
                    }
                }
                if (alpha > 0.01f) {
                    val a = (alpha * 255).roundToInt().coerceIn(0, 255)
                    if (b.look == "shadow" && !(b.motion == "box" && i == cur)) {
                        textP.style = Paint.Style.STROKE
                        textP.strokeWidth = size * 0.14f
                        textP.strokeJoin = Paint.Join.ROUND
                        textP.color = 0xE6000000.toInt()
                        textP.alpha = (0xE6 * alpha).roundToInt()
                        c.drawText(w, x, y, textP)
                        textP.style = Paint.Style.FILL
                    }
                    textP.color = color
                    textP.alpha = a
                    c.drawText(w, x, y, textP)
                }
                c.restore()
                x += ww + space
            }
            y += step
        }
        textP.alpha = 255
        textP.textAlign = Paint.Align.CENTER
    }

    private fun captionText(c: Canvas, b: EBlock, box: RectF, reveal: Float, accent: Int, text: String, tMs: Long = -1) {
        val m = min(box.width(), box.height())
        val pad = min(m * 0.22f, box.width() * 0.06f)
        val (size, lines) = fit(text.trim(), box.width() - pad * 2, box.height() - pad * 2)
        // the typewriter shows the letters one by one over the laid-out lines
        var left = if (reveal >= 0.999f) Int.MAX_VALUE else kotlin.math.ceil(lines.sumOf { it.length } * reveal).toInt()
        val ink: Int
        when (b.look) {
            "shadow" -> ink = Color.WHITE
            "accent", "light" -> {
                fill.color = if (b.look == "accent") accent else 0xF7FFFFFF.toInt()
                c.drawRoundRect(box, m * 0.22f, m * 0.22f, fill)
                ink = if (b.look == "light" || light(accent)) 0xFF14161B.toInt() else Color.WHITE
            }
            else -> {
                fill.color = 0xD9101318.toInt()
                c.drawRoundRect(box, m * 0.22f, m * 0.22f, fill)
                ink = Color.WHITE
            }
        }
        if (b.motion.isNotEmpty() && b.wordMs.isNotEmpty() && tMs >= 0) return captionWords(c, b, box, lines, size, ink, accent, tMs)
        textP.textSize = size
        val step = size * 1.18f
        val fm = textP.fontMetrics
        var y = box.centerY() - step * lines.size / 2 + step / 2 - (fm.ascent + fm.descent) / 2
        for (l0 in lines) {
            if (left <= 0) break
            val l = if (l0.length <= left) l0 else l0.take(left)
            left -= l0.length
            // a line being typed stays where the whole line will stand
            val partial = l.length < l0.length
            val x = if (partial) box.centerX() - textP.measureText(l0) / 2 else box.centerX()
            if (partial) textP.textAlign = Paint.Align.LEFT
            if (b.look == "shadow") {
                textP.style = Paint.Style.STROKE
                textP.strokeWidth = size * 0.14f
                textP.strokeJoin = Paint.Join.ROUND
                textP.color = 0xE6000000.toInt()
                c.drawText(l, x, y, textP)
                textP.style = Paint.Style.FILL
            }
            textP.color = ink
            c.drawText(l, x, y, textP)
            textP.textAlign = Paint.Align.CENTER
            y += step
        }
    }

    private fun emoji(c: Canvas, e: String, box: RectF) {
        if (e.isBlank()) return
        emojiP.textSize = min(box.width(), box.height()) * 0.86f
        val fm = emojiP.fontMetrics
        c.drawText(e.trim(), box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, emojiP)
    }

    override fun close() {
        bitmaps.values.forEach { it?.recycle() }
        bitmaps.clear()
        painters.values.forEach { runCatching { it.close() } }
        painters.clear()
    }
}

/** Editor projects in files/lumen/studio/edit/<id>/: project.json, base.mp4 (the video), words.json, pictures. */
object StudioEditStore {
    const val BASE = "base.mp4"
    const val WORDS = "words.json"
    private const val FILE = "project.json"

    fun root(ctx: Context): File = File(ctx.filesDir, "lumen/studio/edit").apply { mkdirs() }
    fun dir(ctx: Context, id: String): File = File(root(ctx), id)
    fun styleDir(ctx: Context): File = File(ctx.filesDir, "lumen/studio/templates/style")

    fun load(d: File): EProject? = File(d, FILE).takeIf { it.exists() }?.let { StudioEdit.fromJson(it.readText()) }
        ?.takeIf { File(d, BASE).exists() }

    fun list(ctx: Context): List<EProject> = root(ctx).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { load(it) }.sortedByDescending { it.created }

    fun save(ctx: Context, p: EProject) {
        val d = dir(ctx, p.id).apply { mkdirs() }
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(StudioEdit.toJson(p).toString())
        if (!tmp.renameTo(File(d, FILE))) {
            File(d, FILE).writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun words(ctx: Context, id: String): List<SWord> =
        File(dir(ctx, id), WORDS).takeIf { it.exists() }?.let { StudioMix.wordsFromJson(it.readText()) }.orEmpty()

    /** The project already made from [src] (same path and size), if any. */
    fun bySource(ctx: Context, src: File): EProject? =
        list(ctx).firstOrNull { it.source == src.absolutePath && File(dir(ctx, it.id), BASE).length() == src.length() }

    private fun newId(): String = "e" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** A new project from video [src] (copied in) with the [words] of its voice (worker thread). */
    fun create(ctx: Context, src: File, words: List<SWord>): EProject = make(ctx, src.absolutePath, words) { out -> src.copyTo(out, overwrite = true) }

    /** A new project from a video picked on the phone (worker thread). */
    fun create(ctx: Context, uri: Uri): EProject = make(ctx, uri.toString(), emptyList()) { out ->
        (ctx.contentResolver.openInputStream(uri) ?: error(t("Видео не открывается", "The video cannot be opened"))).use { i -> out.outputStream().use { i.copyTo(it) } }
    }

    private fun make(ctx: Context, source: String, words: List<SWord>, copy: (File) -> Unit): EProject {
        var id = newId()
        while (dir(ctx, id).exists()) id += "x"
        val d = dir(ctx, id).apply { mkdirs() }
        try {
            val base = File(d, BASE)
            copy(base)
            val pr = StudioEditRender.probe(base)
            if (words.isNotEmpty()) File(d, WORDS).writeText(StudioMix.wordsJson(words))
            val p = EProject(id, source, pr.w, pr.h, pr.durMs, emptyList(), System.currentTimeMillis())
            save(ctx, p)
            return p
        } catch (e: Exception) {
            d.deleteRecursively()
            throw e
        }
    }

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }
}

/** Export of the editor: frames of the video with the blocks drawn over them, the sound copied as it is. */
object StudioEditRender {

    /** Displayed size, length and frame rate of a video file. */
    data class Probe(val w: Int, val h: Int, val durMs: Long, val fps: Int, val rotation: Int = 0)

    fun probe(f: File): Probe {
        val r = MediaMetadataRetriever()
        var rot = 0
        val (w, h, dur) = try {
            r.setDataSource(f.absolutePath)
            val rw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 360 + 360) % 360
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val (dw, dh) = Studio.displaySize(rw, rh, rot)
            Triple(dw, dh, d)
        } finally {
            runCatching { r.release() }
        }
        if (w <= 0 || h <= 0 || dur <= 0) throw IllegalStateException(t("В файле нет видео", "The file has no video"))
        val fps = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }
            } finally {
                ex.release()
            }
        }.getOrDefault(30).let { if (it in 15..60) it else 30 }
        return Probe(w, h, dur, fps, rot)
    }

    /**
     * Writes the video of project [p] (folder [dir]) with its blocks into [out], the long side at most [maxSide];
     * [progress] gets "done/total". On the GPU first (decoder → texture → OpenGL with the layer of blocks → encoder
     * surface, several times faster); when this phone's codecs or GL refuse, the same picture on the CPU.
     * Worker thread; throws CancellationException when [cancelled].
     */
    fun render(
        p: EProject, dir: File, words: List<SWord>, accent: Int, styleDir: File, out: File, maxSide: Int,
        cancelled: () -> Boolean, progress: (String) -> Unit
    ) {
        // a scheme still being drawn by the AI is not exported
        // a block still waiting for the AI, or an animation without frames, is not exported
        val job = p.copy(blocks = p.blocks.filter { !EditInsert.isPending(it) && (it.kind != EditInsert.KIND || AnimRender.meta(dir, it) != null) })
        val started = System.currentTimeMillis()
        val clips = EditTrack.of(p)
        // a cut track: the sound is put together once, for the GPU and (if needed) the CPU way
        val splits = job.blocks.filter { it.kind == EditSplit.KIND }
        val music = job.blocks.filter { EditMusic.isSound(it) }
        // the sound is rebuilt when the track is cut, a split screen is heard (or makes the main video quieter) or music plays
        val sound = if (EditTrack.soundPlain(clips, p.durMs) && !EditSplit.mixes(splits) && !EditMusic.mixes(music)) null
        else EditAudio.build(File(dir, StudioEditStore.BASE), clips, splits + music, dir, cancelled, progress)
        try {
            val n = StudioEditGl.render(job, dir, words, accent, styleDir, out, maxSide, sound, cancelled, progress)
            L.i("studio", "edit export on GPU: $n frames in ${(System.currentTimeMillis() - started) / 1000} s")
            return
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } catch (e: Throwable) {
            L.w("studio", "edit export on GPU failed, CPU instead: ${e.message}", e)
            out.delete()
            progress(t("Медленный режим: кадры на процессоре", "Slow mode: frames on the CPU"))
        }
        renderCpu(job, dir, words, accent, styleDir, out, maxSide, sound, cancelled, progress)
        L.i("studio", "edit export on CPU in ${(System.currentTimeMillis() - started) / 1000} s")
    }

    /** The slow way: every frame decoded into pixels, the blocks drawn with Canvas, the pixels encoded. */
    private fun renderCpu(
        p: EProject, dir: File, words: List<SWord>, accent: Int, styleDir: File, out: File, maxSide: Int, sound: EditAudio?,
        cancelled: () -> Boolean, progress: (String) -> Unit
    ) {
        val base = File(dir, StudioEditStore.BASE)
        val pr = probe(base)
        val (w, h) = EditCanvas.outSize(pr.w, pr.h, p.ratio, maxSide)
        val fps = pr.fps
        val clips = EditTrack.of(p)
        val starts = EditTrack.starts(clips)
        val plain = EditTrack.plain(clips, pr.durMs)
        val totalMs = if (plain) pr.durMs else EditTrack.total(clips)
        val frames = Studio.frameCount(totalMs, fps)
        // the video on the canvas (format of the project) and the size its frames are decoded at
        // (each video of the project has its own; the base one first)
        fun rectOf(sw: Int, sh: Int): FRect = EditCanvas.videoRect(sw.toFloat(), sh.toFloat(), w.toFloat(), h.toFloat(), p.fill)
        fun decodeSize(r: FRect): Pair<Int, Int> {
            val k = min(1f, max(w, h).toFloat() / max(r.w, r.h))
            return max(2, (r.w * k).roundToInt() / 2 * 2) to max(2, (r.h * k).roundToInt() / 2 * 2)
        }
        var vRect = rectOf(pr.w, pr.h)
        var vw = decodeSize(vRect).first
        var vh = decodeSize(vRect).second
        // the video and turn decoded now ("src|rot"), its turned size and place
        var curSrc = "|0"
        var curW = pr.w.toFloat()
        var curH = pr.h.toFloat()
        var blurRect = EditCanvas.videoRect(curW, curH, w.toFloat(), h.toFloat(), true)
        val sizes = HashMap<String, Pair<Int, Int>>()
        out.parentFile?.mkdirs()
        val enc = MediaCodec.createEncoderByType(MIME)
        enc.configure(videoFormat(w, h, fps, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val audio: AudioOut = sound?.out() ?: if (EditTrack.soundPlain(clips, pr.durMs)) AudioCopy(base, totalMs * 1000) else NoAudio
        val sink = VideoSink(enc, muxer, audio)
        var ok = false
        var src: StudioFrames? = null
        val painter = StudioEditPainter(w, h, dir, words, accent, styleDir)
        try {
            src = StudioFrames(base, vw, vh)
            var frame = IntArray(vw * vh) { 0xFF000000.toInt() }
            var tinted = IntArray(vw * vh)
            val mixed = IntArray(w * h)
            // dissolves: the last picture of the piece before (and which piece it was)
            val dissolves = clips.any { it.trans == "dissolve" }
            val still = IntArray(if (dissolves) w * h else 0)
            var stillOf = -1
            val tail = IntArray(if (dissolves) w * h else 0)
            var tailOf = -1
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // the video frame as a picture, drawn onto the canvas (turned, enlarged, into its half of a split screen)
            var vidBmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            val vidPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dst = RectF()
            var lastSrc = Long.MIN_VALUE
            // split screen: frames of the second video, already cut to the size of its half
            val over = EditSplit.overlays(p.blocks)
            val splits = p.blocks.filter { it.kind == EditSplit.KIND }
            var sideId = ""
            var side: StudioFrames? = null
            var sidePix = IntArray(0)
            var sideBmp: Bitmap? = null
            val y = ByteArray(w * h)
            val u = ByteArray(w * h / 4)
            val v = ByteArray(w * h / 4)
            val size = w * h * 3 / 2
            var colorKey: VClip? = null
            var colorM: FloatArray? = null
            for (f in 0 until frames) {
                if (cancelled()) throw CancellationException()
                val pts = Studio.frameTimeUs(f, fps)
                val tMs = pts / 1000
                // the moment of the original shown now (pieces of the main track: cut, reordered, sped up, frozen)
                val pos = EditTrack.locate(clips, tMs)
                val clip = clips[pos.index]
                val srcUs = if (plain) pts else if (clip.isHold) clip.fromMs * 1000
                else clip.fromMs * 1000 + ((pts - starts[pos.index] * 1000) * clip.speed.toDouble()).toLong()
                val file = if (clip.src.isEmpty()) base else File(dir, clip.src)
                val key = clip.src + "|" + clip.rot
                if (key != curSrc) {
                    // a piece of another video of the project (or turned): its own decoder, size and place on the canvas
                    val sameFile = curSrc.substringBeforeLast('|') == clip.src
                    curSrc = key
                    val sp0 = sizes.getOrPut(clip.src) { if (clip.src.isEmpty()) pr.w to pr.h else probe(file).let { it.w to it.h } }
                    val (tw0, th0) = EditCanvas.turned(sp0.first, sp0.second, clip.rot)
                    curW = tw0.toFloat()
                    curH = th0.toFloat()
                    vRect = rectOf(tw0, th0)
                    blurRect = EditCanvas.videoRect(curW, curH, w.toFloat(), h.toFloat(), true)
                    // frames are decoded upright: a quarter turn swaps the sides of the decoded picture
                    val (dw, dh) = decodeSize(vRect)
                    val odd = (clip.rot / 90) % 2 != 0
                    val nw = if (odd) dh else dw
                    val nh = if (odd) dw else dh
                    if (!sameFile || nw != vw || nh != vh) {
                        if (nw != vw || nh != vh) {
                            vw = nw
                            vh = nh
                            frame = IntArray(vw * vh) { 0xFF000000.toInt() }
                            tinted = IntArray(vw * vh)
                            vidBmp.recycle()
                            vidBmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                        }
                        src?.let { runCatching { it.close() } }
                        src = null
                        lastSrc = Long.MIN_VALUE
                    }
                }
                var dec: StudioFrames = src ?: StudioFrames(file, vw, vh)
                src = dec
                if (srcUs < lastSrc - 1000) {
                    // back in time (another order, a copy): the decoder starts over
                    dec.close()
                    dec = StudioFrames(file, vw, vh)
                    src = dec
                }
                lastSrc = srcUs
                dec.advance(srcUs, frame, true)
                // the colour of the piece on a copy: the next frame may keep this picture when the source has fewer frames
                if (colorKey !== clip) {
                    colorKey = clip
                    colorM = EditColor.matrix(clip)
                }
                val cm = colorM
                val pix = if (cm == null) frame else {
                    System.arraycopy(frame, 0, tinted, 0, frame.size)
                    EditColor.applyTo(tinted, cm)
                    tinted
                }
                vidBmp.setPixels(pix, 0, vw, 0, 0, vw, vh)
                canvas.drawColor(p.bg or 0xFF000000.toInt())
                val sp = EditSplit.active(splits, tMs)
                /** The picture [b] of the piece into [r] (turned size), cut by [clip0]: turned, mirrored, enlarged around its middle. */
                fun drawVideo(b: Bitmap, r: FRect, clip0: FRect, paint: Paint, look: Boolean) {
                    canvas.save()
                    canvas.clipRect(clip0.l, clip0.t, clip0.r, clip0.b)
                    val cx = (r.l + r.r) / 2
                    val cy = (r.t + r.b) / 2
                    if (clip.rot != 0) canvas.rotate(clip.rot.toFloat(), cx, cy)
                    if (look && (clip.mirror || clip.zoom > 1.001f)) canvas.scale(if (clip.mirror) -clip.zoom else clip.zoom, clip.zoom, cx, cy)
                    val odd = (clip.rot / 90) % 2 != 0
                    val hw = (if (odd) r.h else r.w) / 2
                    val hh = (if (odd) r.w else r.h) / 2
                    dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    canvas.drawBitmap(b, null, dst, paint)
                    canvas.restore()
                }
                val pip = sp != null && EditSplit.isPip(sp)
                if (sp == null || pip) {
                    if (p.blur && !p.fill) {
                        // the background: the same frame tiny, stretched over the whole canvas (soft), darkened
                        val tiny = Bitmap.createScaledBitmap(vidBmp, 24, max(1, 24 * vh / vw), true)
                        drawVideo(tiny, blurRect, FRect(0f, 0f, w.toFloat(), h.toFloat()), vidPaint, false)
                        tiny.recycle()
                        canvas.drawColor(0x40000000)
                    }
                    drawVideo(vidBmp, vRect, vRect, vidPaint, true)
                }
                if (sp != null) {
                    val (mh, sh) = EditSplit.boxes(sp, w.toFloat(), h.toFloat())
                    val sx0 = (sh.l * w).roundToInt()
                    val sy0 = (sh.t * h).roundToInt()
                    val sw = (sh.r * w).roundToInt() - sx0
                    val shh = (sh.b * h).roundToInt() - sy0
                    if (sp.id != sideId) {
                        side?.let { runCatching { it.close() } }
                        sideBmp?.recycle()
                        sideId = sp.id
                        sidePix = IntArray(sw * shh) { 0xFF000000.toInt() }
                        sideBmp = Bitmap.createBitmap(max(1, sw), max(1, shh), Bitmap.Config.ARGB_8888)
                        side = runCatching { StudioFrames(File(dir, sp.file), sw, shh) }
                            .onFailure { L.w("studio", "split screen: ${it.message}") }.getOrNull()
                    }
                    side?.advance(EditSplit.srcAt(sp, tMs) * 1000, sidePix, true)
                    // the main video covers its half, centred (the rest of it is cut off); under a window it is drawn as usual above
                    if (!pip) {
                        val mc = EditSplit.cover(curW, curH, mh, w.toFloat(), h.toFloat())
                        drawVideo(vidBmp, mc, FRect(mh.l * w, mh.t * h, mh.r * w, mh.b * h), vidPaint, true)
                    }
                    val sb = sideBmp
                    if (sb != null && sidePix.size == sw * shh && sw > 0 && shh > 0) {
                        sb.setPixels(sidePix, 0, sw, 0, 0, sw, shh)
                        val rad = if (pip) EditSplit.pipRadius(sp.look, sw.toFloat(), shh.toFloat()) else 0f
                        if (rad <= 0f) canvas.drawBitmap(sb, sx0.toFloat(), sy0.toFloat(), null)
                        else {
                            // the window's shape: the picture as a shader of a rounded rectangle (smooth edges)
                            val sh2 = android.graphics.BitmapShader(sb, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                            sh2.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(sx0.toFloat(), sy0.toFloat()) })
                            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sh2 }
                            canvas.drawRoundRect(sx0.toFloat(), sy0.toFloat(), (sx0 + sw).toFloat(), (sy0 + shh).toFloat(), rad, rad, pp)
                        }
                    }
                }
                val shown = StudioEdit.active(over, tMs)
                if (shown.isNotEmpty()) painter.draw(canvas, shown, tMs, bmp)
                bmp.getPixels(mixed, 0, w, 0, 0, w, h)
                // a transition at a seam: the whole frame dips to a colour and back
                EditColor.fadeAt(clips, tMs)?.let { EditColor.blend(mixed, it) }
                // a dissolve: the kept last picture of the piece before fades out over this one
                EditColor.dissolveAt(clips, tMs)?.let { (a, k0) ->
                    if (tailOf == k0 && stillOf != k0) {
                        System.arraycopy(tail, 0, still, 0, tail.size)
                        stillOf = k0
                    }
                    if (k0 == stillOf) EditColor.mixInto(mixed, still, a)
                }
                if (EditColor.dissolvesAfter(clips, pos.index)) {
                    System.arraycopy(mixed, 0, tail, 0, mixed.size)
                    tailOf = pos.index
                }
                Studio.argbToYuv420(mixed, w, h, y, u, v)
                var idx: Int
                while (true) {
                    idx = enc.dequeueInputBuffer(TIMEOUT)
                    if (idx >= 0) break
                    sink.drain(false)
                    if (cancelled()) throw CancellationException()
                }
                val image = enc.getInputImage(idx)
                if (image != null) StudioVideo.writeImage(image, w, h, y, u, v) else StudioVideo.writeNv12(enc.getInputBuffer(idx)!!, w, h, y, u, v)
                enc.queueInputBuffer(idx, 0, size, pts, 0)
                sink.drain(false)
                if (f % 15 == 0 || f == frames - 1) progress(t("Кадры ${f + 1}/$frames", "Frames ${f + 1}/$frames"))
            }
            while (true) {
                val idx = enc.dequeueInputBuffer(TIMEOUT)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, Studio.frameTimeUs(frames, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                sink.drain(false)
            }
            sink.drain(true)
            audio.write(muxer, Long.MAX_VALUE)
            bmp.recycle()
            vidBmp.recycle()
            sideBmp?.recycle()
            side?.let { runCatching { it.close() } }
            ok = true
        } finally {
            painter.close()
            src?.let { s -> runCatching { s.close() } }
            audio.close()
            runCatching { enc.stop() }
            enc.release()
            if (sink.started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!ok) out.delete()
        }
    }

    /**
     * Piece [fromMs]..[toMs] of [src] played backwards, as a new silent MP4 [out] (long side at most [maxSide]):
     * every frame of the piece taken from the end to the start. Worker thread; returns the length in ms.
     */
    fun reverse(src: File, fromMs: Long, toMs: Long, out: File, maxSide: Int, cancelled: () -> Boolean, progress: (String) -> Unit): Long {
        val pr = probe(src)
        val (w, h) = StudioEdit.outSize(pr.w, pr.h, maxSide)
        val fps = pr.fps
        val n = max(1, ((toMs - fromMs) * fps / 1000).toInt())
        out.parentFile?.mkdirs()
        val enc = MediaCodec.createEncoderByType(MIME)
        enc.configure(videoFormat(w, h, fps, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sink = VideoSink(enc, muxer, NoAudio)
        val r = MediaMetadataRetriever()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val pix = IntArray(w * h)
        val y = ByteArray(w * h)
        val u = ByteArray(w * h / 4)
        val v = ByteArray(w * h / 4)
        val size = w * h * 3 / 2
        var ok = false
        try {
            r.setDataSource(src.absolutePath)
            for (i in 0 until n) {
                if (cancelled()) throw CancellationException()
                val tUs = max(fromMs * 1000, toMs * 1000 - ((i + 0.5) * 1_000_000 / fps).toLong())
                val fr = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (fr != null) {
                    // some phones give the frame as stored, not turned upright: turn it when its shape is the other one
                    val upright = (fr.width >= fr.height) == (w >= h)
                    canvas.drawColor(Color.BLACK)
                    canvas.save()
                    if (!upright && pr.rotation != 0) canvas.rotate(pr.rotation.toFloat(), w / 2f, h / 2f)
                    val (dw, dh) = if (!upright && pr.rotation != 0) h to w else w to h
                    canvas.drawBitmap(fr, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), paint)
                    canvas.restore()
                    fr.recycle()
                    bmp.getPixels(pix, 0, w, 0, 0, w, h)
                    Studio.argbToYuv420(pix, w, h, y, u, v)
                }
                var idx: Int
                while (true) {
                    idx = enc.dequeueInputBuffer(TIMEOUT)
                    if (idx >= 0) break
                    sink.drain(false)
                    if (cancelled()) throw CancellationException()
                }
                val image = enc.getInputImage(idx)
                if (image != null) StudioVideo.writeImage(image, w, h, y, u, v) else StudioVideo.writeNv12(enc.getInputBuffer(idx)!!, w, h, y, u, v)
                enc.queueInputBuffer(idx, 0, size, Studio.frameTimeUs(i, fps), 0)
                sink.drain(false)
                if (i % 10 == 0 || i == n - 1) progress(t("Реверс: кадры ${i + 1}/$n", "Reverse: frames ${i + 1}/$n"))
            }
            while (true) {
                val idx = enc.dequeueInputBuffer(TIMEOUT)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, Studio.frameTimeUs(n, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                sink.drain(false)
            }
            sink.drain(true)
            ok = true
        } finally {
            runCatching { r.release() }
            bmp.recycle()
            runCatching { enc.stop() }
            enc.release()
            if (sink.started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!ok) out.delete()
        }
        return n * 1000L / fps
    }

    internal const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    internal const val TIMEOUT = 10_000L

    internal fun videoFormat(w: Int, h: Int, fps: Int, color: Int): MediaFormat = MediaFormat.createVideoFormat(MIME, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
        setInteger(MediaFormat.KEY_BIT_RATE, Studio.bitrate(w, h, fps))
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
}

/** The sound track of an export: added to the muxer before it starts, then written along with the video. */
internal interface AudioOut : java.io.Closeable {
    fun add(m: MediaMuxer)
    fun write(m: MediaMuxer, untilUs: Long)
}

/** A video without sound. */
internal object NoAudio : AudioOut {
    override fun add(m: MediaMuxer) {}
    override fun write(m: MediaMuxer, untilUs: Long) {}
    override fun close() {}
}

/** The sound of the original copied sample by sample into the new file (no re-encoding). */
internal class AudioCopy(base: File, private val endUs: Long) : AudioOut {
    private val ex = MediaExtractor()
    private val format: MediaFormat?
    private var done: Boolean
    private val buf: ByteBuffer
    private val info = MediaCodec.BufferInfo()
    private var track = -1

    init {
        ex.setDataSource(base.absolutePath)
        val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        format = idx?.let {
            ex.selectTrack(it)
            ex.getTrackFormat(it)
        }
        done = format == null
        buf = ByteBuffer.allocateDirect(max(256 * 1024, format?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0))
    }

    /** Adds the audio track to [m] (before it starts); a codec the muxer cannot take is left out. */
    override fun add(m: MediaMuxer) {
        track = format?.let { f ->
            runCatching { m.addTrack(f) }.getOrElse {
                L.w("studio", "edit export: audio ${f.getString(MediaFormat.KEY_MIME)} cannot be copied: ${it.message}")
                -1
            }
        } ?: -1
    }

    /** Writes the samples up to [untilUs]. */
    override fun write(m: MediaMuxer, untilUs: Long) {
        if (track < 0) return
        while (!done) {
            val pts = ex.sampleTime
            if (pts < 0 || pts > endUs) {
                done = true
                break
            }
            if (pts > untilUs) break
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) {
                done = true
                break
            }
            info.set(0, n, pts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            m.writeSampleData(track, buf, info)
            ex.advance()
        }
    }

    override fun close() {
        runCatching { ex.release() }
    }
}

/** Moves encoded video to the muxer (starting it with the audio track) and the sound along with it. */
internal class VideoSink(private val enc: MediaCodec, private val muxer: MediaMuxer, private val audio: AudioOut) {
    var started = false
        private set
    var ended = false
        private set
    private var track = -1
    private val info = MediaCodec.BufferInfo()

    /** Takes what the encoder has; with [end] waits for its end of stream. */
    fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val o = enc.dequeueOutputBuffer(info, if (end) StudioEditRender.TIMEOUT else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (++idle > 300) error("video encoder stalled")
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(enc.outputFormat)
                    audio.add(muxer)
                    muxer.start()
                    started = true
                }
                o >= 0 -> {
                    val b = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        audio.write(muxer, info.presentationTimeUs)
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        ended = true
                        return
                    }
                }
            }
        }
    }
}

/**
 * GPU export: the decoder draws every frame into a SurfaceTexture, OpenGL puts it on the encoder's input
 * surface and the layer of blocks over it (drawn with Canvas only when it changes, see [StudioEdit.overlayKey]).
 * The way Media3 Transformer and the CTS DecodeEditEncode test work, without the extra library.
 */
private object StudioEditGl {
    private const val RECORDABLE = 0x3142 // EGL_RECORDABLE_ANDROID

    private val VS = """
        uniform mat4 uTex;
        attribute vec4 aPos;
        attribute vec4 aUv;
        varying vec2 vUv;
        void main() {
            gl_Position = aPos;
            vUv = (uTex * aUv).xy;
        }
    """.trimIndent()

    // the video with the colour of its piece (filter and adjust, see [EditColor]): rgb' = uC * rgb + uO
    private val FS_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uS;
        uniform mat3 uC;
        uniform vec3 uO;
        void main() {
            vec3 c = texture2D(uS, vUv).rgb;
            gl_FragColor = vec4(clamp(uC * c + uO, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // the second video in a window with rounded corners: outside the rounded box (window pixels) nothing is drawn,
    // the edge is smoothed over a pixel by blending
    private val FS_ROUND = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uS;
        uniform vec4 uBox;
        uniform float uRad;
        void main() {
            vec2 p = gl_FragCoord.xy;
            vec2 c = clamp(p, uBox.xy + uRad, uBox.zw - uRad);
            float a = 1.0 - smoothstep(uRad - 1.0, uRad + 0.5, length(p - c));
            if (uRad <= 0.0) a = 1.0;
            gl_FragColor = vec4(texture2D(uS, vUv).rgb, a);
        }
    """.trimIndent()

    // the video enlarged and blurred behind itself (the "blur" background of the format): a ring of samples, darkened
    private val FS_BLUR = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uS;
        uniform float uR;
        void main() {
            vec3 c = texture2D(uS, vUv).rgb;
            for (int i = 0; i < 12; i++) {
                float a = float(i) * 0.5236;
                vec2 d = vec2(cos(a), sin(a)) * uR;
                c += texture2D(uS, vUv + d).rgb + texture2D(uS, vUv + d * 0.5).rgb;
            }
            gl_FragColor = vec4(c / 25.0 * 0.75, 1.0);
        }
    """.trimIndent()

    // a plain colour over the frame (transitions)
    private val FS_FILL = """
        precision mediump float;
        uniform vec4 uF;
        void main() {
            gl_FragColor = uF;
        }
    """.trimIndent()

    private val ID3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val ZERO3 = floatArrayOf(0f, 0f, 0f)

    private val FS_2D = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uS;
        void main() {
            gl_FragColor = texture2D(uS, vUv);
        }
    """.trimIndent()

    private fun floats(vararg v: Float): java.nio.FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private fun shader(type: Int, src: String): Int {
        val s = android.opengl.GLES20.glCreateShader(type)
        android.opengl.GLES20.glShaderSource(s, src)
        android.opengl.GLES20.glCompileShader(s)
        val ok = IntArray(1)
        android.opengl.GLES20.glGetShaderiv(s, android.opengl.GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("shader: " + android.opengl.GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun program(fs: String): Int {
        val p = android.opengl.GLES20.glCreateProgram()
        android.opengl.GLES20.glAttachShader(p, shader(android.opengl.GLES20.GL_VERTEX_SHADER, VS))
        android.opengl.GLES20.glAttachShader(p, shader(android.opengl.GLES20.GL_FRAGMENT_SHADER, fs))
        android.opengl.GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        android.opengl.GLES20.glGetProgramiv(p, android.opengl.GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program: " + android.opengl.GLES20.glGetProgramInfoLog(p))
        return p
    }

    /** Waits for the decoder's next frame in the SurfaceTexture. */
    /** The second video of a split screen: decoded in order into its own texture, the frame of a moment on request. */
    private class GlTrack(file: File, startUs: Long, handler: android.os.Handler) : java.io.Closeable {
        private val ex = MediaExtractor()
        private val frames = Frames()
        private val info = MediaCodec.BufferInfo()
        private var inEos = false
        private var outEos = false
        private var pendIdx = -1
        private var pendPts = 0L
        val tex: Int
        val m = FloatArray(16)
        val w: Float
        val h: Float
        var shown = false
            private set
        private val st: android.graphics.SurfaceTexture
        private val surface: android.view.Surface
        private val dec: MediaCodec

        init {
            val pr = StudioEditRender.probe(file)
            w = pr.w.toFloat()
            h = pr.h.toFloat()
            android.opengl.Matrix.setIdentityM(m, 0)
            val ids = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, ids, 0)
            tex = ids[0]
            val oes = android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            android.opengl.GLES20.glBindTexture(oes, tex)
            for ((k, v) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE)) {
                android.opengl.GLES20.glTexParameteri(oes, k, v)
            }
            st = android.graphics.SurfaceTexture(tex)
            st.setOnFrameAvailableListener(frames, handler)
            surface = android.view.Surface(st)
            ex.setDataSource(file.absolutePath)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error(t("Во втором файле нет видео", "The second file has no video"))
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            if (pr.rotation != 0 && !fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.setInteger(MediaFormat.KEY_ROTATION, pr.rotation)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, surface, null, 0)
            dec.start()
        }

        /** The next decoded frame into pendIdx; false at the end. */
        private fun next(): Boolean {
            var idle = 0
            while (true) {
                if (!inEos) {
                    val i = dec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 2000)
                if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) outEos = true
                    if (info.size > 0) {
                        pendIdx = o
                        pendPts = info.presentationTimeUs
                        return true
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (eos) return false
                } else if (++idle > 2500) error("second video decoder stalled")
            }
        }

        /** The texture shows the last frame at or before [tUs] (after its end: the last one). */
        fun advance(tUs: Long) {
            var cand = -1
            while (true) {
                if (pendIdx < 0 && (outEos || !next())) break
                if (pendPts <= tUs) {
                    if (cand >= 0) dec.releaseOutputBuffer(cand, false)
                    cand = pendIdx
                    pendIdx = -1
                } else break
            }
            if (cand < 0 && !shown && pendIdx >= 0) {
                cand = pendIdx
                pendIdx = -1
            }
            if (cand >= 0) {
                dec.releaseOutputBuffer(cand, true)
                frames.await(st)
                st.getTransformMatrix(m)
                shown = true
            }
        }

        override fun close() {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            runCatching { ex.release() }
            runCatching { st.release() }
            runCatching { surface.release() }
            runCatching { android.opengl.GLES20.glDeleteTextures(1, intArrayOf(tex), 0) }
        }
    }

    private class Frames : android.graphics.SurfaceTexture.OnFrameAvailableListener {
        private val lock = Object()
        private var ready = false

        override fun onFrameAvailable(st: android.graphics.SurfaceTexture) {
            synchronized(lock) {
                ready = true
                lock.notifyAll()
            }
        }

        fun await(st: android.graphics.SurfaceTexture) {
            synchronized(lock) {
                val until = System.currentTimeMillis() + 3000
                while (!ready) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) error("no frame from the decoder")
                    lock.wait(left)
                }
                ready = false
            }
            st.updateTexImage()
        }
    }

    /** Returns the number of frames written. */
    fun render(
        p: EProject, dir: File, words: List<SWord>, accent: Int, styleDir: File, out: File, maxSide: Int, sound: EditAudio?,
        cancelled: () -> Boolean, progress: (String) -> Unit
    ): Int {
        val base = File(dir, StudioEditStore.BASE)
        val pr = StudioEditRender.probe(base)
        val (w, h) = EditCanvas.outSize(pr.w, pr.h, p.ratio, maxSide)
        // the video on the canvas: the whole frame, or a rectangle on the background colour (format of the project)
        var vRect = EditCanvas.videoRect(pr.w.toFloat(), pr.h.toFloat(), w.toFloat(), h.toFloat(), p.fill)
        // the video being decoded now ("" = the base one), its size and length
        var curSrc = ""
        var curW = pr.w.toFloat()
        var curH = pr.h.toFloat()
        var curDur = pr.durMs
        var srcW = pr.w
        var srcH = pr.h
        // the blurred background: the video covering the frame, behind the fitted one
        var blurRect = vRect
        var blurPos: java.nio.FloatBuffer? = null
        val bgR = ((p.bg shr 16) and 0xFF) / 255f
        val bgG = ((p.bg shr 8) and 0xFF) / 255f
        val bgB = (p.bg and 0xFF) / 255f
        val clips = EditTrack.of(p)
        val starts = EditTrack.starts(clips)
        val plain = EditTrack.plain(clips, pr.durMs)
        val soundPlain = EditTrack.soundPlain(clips, pr.durMs)
        val totalMs = if (plain) pr.durMs else EditTrack.total(clips)
        val expected = max(1, (totalMs * pr.fps / 1000).toInt())
        out.parentFile?.mkdirs()

        var ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audio: AudioOut? = null
        var inputSurface: android.view.Surface? = null
        var decSurface: android.view.Surface? = null
        var st: android.graphics.SurfaceTexture? = null
        val thread = android.os.HandlerThread("edit-gl-frames").apply { start() }
        // split screen: the second video, decoded into its own texture
        val over = EditSplit.overlays(p.blocks)
        val splits = p.blocks.filter { it.kind == EditSplit.KIND }
        var track: GlTrack? = null
        var trackId = ""
        var dpy = android.opengl.EGL14.EGL_NO_DISPLAY
        var ctx = android.opengl.EGL14.EGL_NO_CONTEXT
        var surf = android.opengl.EGL14.EGL_NO_SURFACE
        val painter = StudioEditPainter(w, h, dir, words, accent, styleDir)
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var sink: VideoSink? = null
        var ok = false
        var written = 0
        try {
            ex.setDataSource(base.absolutePath)
            val vIdx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error(t("В файле нет видео", "The file has no video"))
            ex.selectTrack(vIdx)
            val inFmt = ex.getTrackFormat(vIdx)
            if (pr.rotation != 0 && !inFmt.containsKey(MediaFormat.KEY_ROTATION)) inFmt.setInteger(MediaFormat.KEY_ROTATION, pr.rotation)

            val e = MediaCodec.createEncoderByType(StudioEditRender.MIME)
            enc = e
            e.configure(StudioEditRender.videoFormat(w, h, pr.fps, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = e.createInputSurface()
            e.start()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val a = sound?.out() ?: if (soundPlain) AudioCopy(base, totalMs * 1000) else NoAudio
            audio = a
            val sk = VideoSink(e, m, a)
            sink = sk

            // EGL on the encoder surface
            dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            check(dpy != android.opengl.EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val ver = IntArray(2)
            check(android.opengl.EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "EGL init" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val num = IntArray(1)
            android.opengl.EGL14.eglChooseConfig(dpy, intArrayOf(
                android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8, android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                android.opengl.EGL14.EGL_ALPHA_SIZE, 8, android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                RECORDABLE, 1, android.opengl.EGL14.EGL_NONE
            ), 0, configs, 0, 1, num, 0)
            val cfg = configs[0]
            check(num[0] > 0 && cfg != null) { "no recordable EGL config" }
            ctx = android.opengl.EGL14.eglCreateContext(dpy, cfg, android.opengl.EGL14.EGL_NO_CONTEXT,
                intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
            check(ctx != android.opengl.EGL14.EGL_NO_CONTEXT) { "no EGL context" }
            surf = android.opengl.EGL14.eglCreateWindowSurface(dpy, cfg, inputSurface, intArrayOf(android.opengl.EGL14.EGL_NONE), 0)
            check(surf != android.opengl.EGL14.EGL_NO_SURFACE) { "no EGL surface" }
            check(android.opengl.EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "EGL make current" }

            val progOes = program(FS_OES)
            val prog2d = program(FS_2D)
            val progFill = program(FS_FILL)
            val progBlur = program(FS_BLUR)
            val progRound = program(FS_ROUND)
            val tex = IntArray(2)
            android.opengl.GLES20.glGenTextures(2, tex, 0)
            val oes = tex[0]
            val ov = tex[1]
            val OES = android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            android.opengl.GLES20.glBindTexture(OES, oes)
            for ((k, v) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE)) {
                android.opengl.GLES20.glTexParameteri(OES, k, v)
            }
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, ov)
            for ((k, v) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE)) {
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, k, v)
            }
            android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, layer, 0)
            // dissolves: the last pictures of a piece are copied here and fade out over the next one
            val stillTex = IntArray(1).also { android.opengl.GLES20.glGenTextures(1, it, 0) }[0]
            var stillReady = false
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, stillTex)
            for ((k, v) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE)) {
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, k, v)
            }
            android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA, w, h, 0,
                android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null)
            val ident = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
            // the piece being written: its index and whether its last pictures are kept for a dissolve
            var curK = 0
            var keepTail = false
            var tailFromUs = Long.MAX_VALUE

            val texture = android.graphics.SurfaceTexture(oes)
            st = texture
            val frames = Frames()
            texture.setOnFrameAvailableListener(frames, android.os.Handler(thread.looper))
            decSurface = android.view.Surface(texture)
            var d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(inFmt, decSurface, null, 0)
            d.start()

            val pos = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            val texM = FloatArray(16)
            // the layer bitmap has its first row at the top: v is turned over
            val flip = FloatArray(16).also {
                android.opengl.Matrix.setIdentityM(it, 0)
                android.opengl.Matrix.translateM(it, 0, 0f, 1f, 0f)
                android.opengl.Matrix.scaleM(it, 0, 1f, -1f, 1f)
            }
            var vidPos = pos
            // colour of the piece being written (3×3 and offsets), identity for none
            var colM = ID3
            var colO = ZERO3
            // radius of the blur shader: the blurred background, or a block that blurs the video under it
            var blurRadius = 0.04f
            fun draw(prog: Int, target: Int, id: Int, mat: FloatArray, at: java.nio.FloatBuffer = pos, cm: FloatArray = ID3, co: FloatArray = ZERO3) {
                android.opengl.GLES20.glUseProgram(prog)
                if (prog == progBlur) android.opengl.GLES20.glUniform1f(android.opengl.GLES20.glGetUniformLocation(prog, "uR"), blurRadius)
                if (prog == progOes) {
                    android.opengl.GLES20.glUniformMatrix3fv(android.opengl.GLES20.glGetUniformLocation(prog, "uC"), 1, false, cm, 0)
                    android.opengl.GLES20.glUniform3fv(android.opengl.GLES20.glGetUniformLocation(prog, "uO"), 1, co, 0)
                }
                android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                android.opengl.GLES20.glBindTexture(target, id)
                android.opengl.GLES20.glUniform1i(android.opengl.GLES20.glGetUniformLocation(prog, "uS"), 0)
                android.opengl.GLES20.glUniformMatrix4fv(android.opengl.GLES20.glGetUniformLocation(prog, "uTex"), 1, false, mat, 0)
                val aPos = android.opengl.GLES20.glGetAttribLocation(prog, "aPos")
                val aUv = android.opengl.GLES20.glGetAttribLocation(prog, "aUv")
                android.opengl.GLES20.glEnableVertexAttribArray(aPos)
                android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 8, at)
                android.opengl.GLES20.glEnableVertexAttribArray(aUv)
                android.opengl.GLES20.glVertexAttribPointer(aUv, 2, android.opengl.GLES20.GL_FLOAT, false, 8, uv)
                android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            /** A colour with [alpha] over the whole frame. */
            fun fill(color: Int, alpha: Float) {
                android.opengl.GLES20.glUseProgram(progFill)
                android.opengl.GLES20.glUniform4f(android.opengl.GLES20.glGetUniformLocation(progFill, "uF"),
                    ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, alpha)
                val aPos = android.opengl.GLES20.glGetAttribLocation(progFill, "aPos")
                android.opengl.GLES20.glEnableVertexAttribArray(aPos)
                android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 8, pos)
                android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
                android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_SRC_ALPHA, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA)
                android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
                android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_BLEND)
            }

            val canvas = Canvas(layer)
            val settles = HashMap<String, Long>()
            val settle: (EBlock) -> Long = { b ->
                settles.getOrPut(b.id + b.hashCode()) {
                    val times = b.scheme?.let { StudioEdit.schemeTimes(it, words, b) }.orEmpty()
                    max(b.startMs, times.maxOrNull() ?: b.startMs) + 900
                }
            }
            var lastKey = ""
            val dInfo = MediaCodec.BufferInfo()
            val stepUs = 1_000_000L / max(1, pr.fps)
            var lastOut = -1L
            /** The picture now in the texture, with the blocks of output moment [outUs], to the encoder. */
            fun present(outUs: Long) {
                android.opengl.GLES20.glViewport(0, 0, w, h)
                android.opengl.GLES20.glClearColor(bgR, bgG, bgB, 1f)
                android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
                val tMs = outUs / 1000
                val sp = EditSplit.active(splits, tMs)
                fun viewport(r: FRect) = android.opengl.GLES20.glViewport(r.l.roundToInt(), (h - r.b).roundToInt(), r.w.roundToInt(), r.h.roundToInt())
                val pip = sp != null && EditSplit.isPip(sp)
                if (sp == null || pip) {
                    if (p.blur && !p.fill) blurPos?.let { bp ->
                        viewport(blurRect)
                        draw(progBlur, OES, oes, texM, bp)
                    }
                    viewport(vRect)
                    draw(progOes, OES, oes, texM, vidPos, colM, colO)
                    android.opengl.GLES20.glViewport(0, 0, w, h)
                }
                if (sp != null) {
                    if (sp.id != trackId) {
                        track?.close()
                        trackId = sp.id
                        track = runCatching { GlTrack(File(dir, sp.file), sp.srcMs * 1000, android.os.Handler(thread.looper)) }
                            .onFailure { L.w("studio", "split screen on GPU: ${it.message}") }.getOrNull()
                    }
                    val tr = track
                    tr?.advance(EditSplit.srcAt(sp, tMs) * 1000)
                    val (mh, sh) = EditSplit.boxes(sp, w.toFloat(), h.toFloat())
                    fun scissor(r: FRect) = android.opengl.GLES20.glScissor((r.l * w).roundToInt(), ((1f - r.b) * h).roundToInt(),
                        (r.w * w).roundToInt(), (r.h * h).roundToInt())
                    android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
                    if (!pip) {
                        scissor(mh)
                        viewport(EditSplit.cover(curW, curH, mh, w.toFloat(), h.toFloat()))
                        draw(progOes, OES, oes, texM, vidPos, colM, colO)
                    }
                    if (tr != null && tr.shown) {
                        scissor(sh)
                        viewport(EditSplit.cover(tr.w, tr.h, sh, w.toFloat(), h.toFloat()))
                        if (pip) {
                            // the window's shape: rounded corners or a circle, cut in the shader
                            val bl = sh.l * w
                            val bb = (1f - sh.b) * h
                            val br = sh.r * w
                            val bt = (1f - sh.t) * h
                            android.opengl.GLES20.glUseProgram(progRound)
                            android.opengl.GLES20.glUniform4f(android.opengl.GLES20.glGetUniformLocation(progRound, "uBox"), bl, bb, br, bt)
                            android.opengl.GLES20.glUniform1f(android.opengl.GLES20.glGetUniformLocation(progRound, "uRad"), EditSplit.pipRadius(sp.look, br - bl, bt - bb))
                            android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
                            android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_SRC_ALPHA, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA)
                            draw(progRound, OES, tr.tex, tr.m)
                            android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_BLEND)
                        } else draw(progOes, OES, tr.tex, tr.m)
                    }
                    android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
                    android.opengl.GLES20.glViewport(0, 0, w, h)
                }
                // blocks that blur the video under them: the blur shader again over the video, cut to the box of the block
                if (sp == null) {
                    val blurring = StudioEdit.active(over, tMs).filter { it.blur > 0f && StudioEdit.dimAlpha(it, tMs) >= 0.5f }
                    if (blurring.isNotEmpty()) {
                        android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
                        for (b0 in blurring) {
                            val b = EditKeys.at(b0, tMs)
                            val r = StudioEdit.blurRect(b, w.toFloat(), h.toFloat()) ?: continue
                            android.opengl.GLES20.glScissor(r.l.roundToInt(), (h - r.b).roundToInt(), (r.r - r.l).roundToInt(), (r.b - r.t).roundToInt())
                            viewport(vRect)
                            blurRadius = 0.012f + 0.028f * b.blur
                            draw(progBlur, OES, oes, texM, vidPos)
                        }
                        blurRadius = 0.04f
                        android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
                        android.opengl.GLES20.glViewport(0, 0, w, h)
                    }
                }
                val shown = StudioEdit.active(over, tMs)
                if (shown.isNotEmpty()) {
                    val key = StudioEdit.overlayKey(shown, tMs, settle)
                    if (key != lastKey) {
                        layer.eraseColor(0)
                        painter.draw(canvas, shown, tMs)
                        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, ov)
                        android.opengl.GLUtils.texSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, layer)
                        lastKey = key
                    }
                    android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
                    // Android bitmaps keep premultiplied colours
                    android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_ONE, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    draw(prog2d, android.opengl.GLES20.GL_TEXTURE_2D, ov, flip)
                    android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_BLEND)
                }
                // a transition at a seam: the whole frame dips to a colour and back
                EditColor.fadeAt(clips, tMs)?.let { fd -> fill(fd.color, fd.alpha) }
                // a dissolve: the kept last picture of the piece before, fading out
                val dz = EditColor.dissolveAt(clips, tMs)
                if (dz != null && stillReady && dz.second == curK - 1) {
                    android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
                    android.opengl.GLES20.glBlendColor(0f, 0f, 0f, dz.first)
                    android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_CONSTANT_ALPHA, android.opengl.GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
                    draw(prog2d, android.opengl.GLES20.GL_TEXTURE_2D, stillTex, ident)
                    android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_BLEND)
                }
                // the last pictures of a piece that dissolves into the next: copied before the swap
                if (keepTail && outUs >= tailFromUs) {
                    android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, stillTex)
                    android.opengl.GLES20.glCopyTexSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
                    stillReady = true
                }
                val err = android.opengl.GLES20.glGetError()
                if (err != android.opengl.GLES20.GL_NO_ERROR) error("GL error $err")
                android.opengl.EGLExt.eglPresentationTimeANDROID(dpy, surf, outUs * 1000)
                check(android.opengl.EGL14.eglSwapBuffers(dpy, surf)) { "EGL swap" }
                lastOut = outUs
                written++
                if (written % 15 == 0) progress(t("Кадры ${min(written, expected - 1)}/$expected", "Frames ${min(written, expected - 1)}/$expected"))
                sk.drain(false)
            }
            // the pieces of the main track one after another; the whole video untouched is one piece from 0 to the end
            for ((k, c) in clips.withIndex()) {
                if (cancelled()) throw CancellationException()
                val switched = c.src != curSrc
                if (switched) {
                    // a piece of another video of the project: its own extractor and decoder on the same surface
                    runCatching { d.stop() }
                    runCatching { d.release() }
                    runCatching { ex.release() }
                    val f = if (c.src.isEmpty()) base else File(dir, c.src)
                    val sp = StudioEditRender.probe(f)
                    val nex = MediaExtractor()
                    ex = nex
                    nex.setDataSource(f.absolutePath)
                    val vi = (0 until nex.trackCount).firstOrNull { nex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        ?: error(t("В файле нет видео", "The file has no video"))
                    nex.selectTrack(vi)
                    val fmt = nex.getTrackFormat(vi)
                    if (sp.rotation != 0 && !fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.setInteger(MediaFormat.KEY_ROTATION, sp.rotation)
                    val nd = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                    dec = nd
                    d = nd
                    nd.configure(fmt, decSurface, null, 0)
                    nd.start()
                    curSrc = c.src
                    curDur = sp.durMs
                    srcW = sp.w
                    srcH = sp.h
                }
                // the shape of this piece on the canvas: its video, turned
                val (tw0, th0) = EditCanvas.turned(srcW, srcH, c.rot)
                curW = tw0.toFloat()
                curH = th0.toFloat()
                vRect = EditCanvas.videoRect(curW, curH, w.toFloat(), h.toFloat(), p.fill)
                blurRect = EditCanvas.videoRect(curW, curH, w.toFloat(), h.toFloat(), true)
                blurPos = floats(*EditCanvas.quad(false, 1f, c.rot))
                val segStart = starts[k] * 1000
                val segEnd = segStart + c.outMs * 1000
                curK = k
                if (!EditColor.dissolvesAfter(clips, k - 1)) stillReady = false
                keepTail = EditColor.dissolvesAfter(clips, k)
                tailFromUs = segEnd - 400_000
                val fromUs = if (plain) 0L else c.fromMs * 1000
                val toUs = when {
                    plain || (!c.isHold && c.toMs >= curDur - 1) -> Long.MAX_VALUE
                    c.isHold -> fromUs + stepUs
                    else -> c.toMs * 1000
                }
                vidPos = if (!c.mirror && c.zoom == 1f && c.rot == 0) pos else floats(*EditCanvas.quad(c.mirror, c.zoom, c.rot))
                val cm = EditColor.matrix(c)?.let { EditColor.gl(it) }
                colM = cm?.first ?: ID3
                colO = cm?.second ?: ZERO3
                if (k > 0 && !switched) d.flush()
                ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var inDone = false
                var got = false
                var idle = 0
                while (true) {
                    if (cancelled()) throw CancellationException()
                    // the decoder gives nothing for ~15 s: this phone's path does not work, the CPU one takes over
                    if (++idle > 3000) error("decoder stalled")
                    if (!inDone) {
                        val i = d.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val tUs = ex.sampleTime
                            // a little past the end of the piece: frames shown earlier may be decoded later
                            val past = tUs < 0 || (toUs != Long.MAX_VALUE && tUs > toUs + 600_000) || (c.isHold && got)
                            val n = if (past) -1 else ex.readSampleData(d.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                d.queueInputBuffer(i, 0, n, tUs, 0)
                                ex.advance()
                            }
                        }
                    }
                    sk.drain(false)
                    val o = d.dequeueOutputBuffer(dInfo, 5000)
                    if (o < 0) continue
                    idle = 0
                    val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val src = dInfo.presentationTimeUs
                    var outUs = -1L
                    val inPiece = dInfo.size != 0 && (if (c.isHold) !got && src >= fromUs - stepUs else src >= fromUs && src < toUs)
                    if (inPiece) {
                        val at = if (c.isHold || plain) (if (plain) src else segStart) else segStart + ((src - fromUs) / c.speed.toDouble()).toLong()
                        // faster than recorded: frames closer than ~a frame apart are skipped
                        val crowded = c.speed > 1f && lastOut >= 0 && at - lastOut < stepUs * 3 / 4
                        if ((plain || at < segEnd) && at > lastOut && !crowded) outUs = at
                    }
                    // slower than recorded: the previous picture again until this one is due
                    if (outUs >= 0 && got && c.speed < 1f) while (lastOut + stepUs <= outUs - stepUs / 2) present(lastOut + stepUs)
                    d.releaseOutputBuffer(o, outUs >= 0)
                    if (outUs >= 0) {
                        frames.await(texture)
                        texture.getTransformMatrix(texM)
                        present(outUs)
                        got = true
                    }
                    if (eos) break
                }
                // a still picture or slow motion: the last frame stays until the piece ends
                if (got && (c.isHold || c.speed < 1f)) while (lastOut + stepUs < segEnd - stepUs / 2) present(lastOut + stepUs)
            }
            e.signalEndOfInputStream()
            sk.drain(true)
            track?.close()
            track = null
            if (written == 0) error("no frames")
            a.write(m, Long.MAX_VALUE)
            progress(t("Кадры $expected/$expected", "Frames $expected/$expected"))
            ok = true
        } finally {
            dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            enc?.let { runCatching { it.stop() }; runCatching { it.release() } }
            if (sink?.started == true) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            audio?.close()
            runCatching { ex.release() }
            track?.let { runCatching { it.close() } }
            if (dpy != android.opengl.EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
                    if (surf != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(dpy, surf)
                    if (ctx != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(dpy, ctx)
                    android.opengl.EGL14.eglReleaseThread()
                    android.opengl.EGL14.eglTerminate(dpy)
                }
            }
            st?.release()
            decSurface?.release()
            inputSurface?.release()
            thread.quitSafely()
            painter.close()
            layer.recycle()
            if (!ok) out.delete()
        }
        return written
    }
}

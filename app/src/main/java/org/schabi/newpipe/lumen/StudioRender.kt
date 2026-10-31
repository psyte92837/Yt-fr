/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Look of the video. DARK: kinetic word groups on a dark screen; LIGHT: headline + one big word at a time
 * on a light screen; VIDEO: the speaker's own video with one word at a time, graphics as light inserts or cards on top.
 */
enum class StudioStyle { DARK, LIGHT, VIDEO }

/** Everything needed to draw any frame of the video. */
class StudioPlan(
    val script: SScript,
    val words: List<SWord>,
    val times: List<SceneTime>,
    val captions: List<List<IntRange>>,
    val totalMs: Long,
    val style: StudioStyle,
    val accent: Int,
    /** VIDEO style: graphics drawn over the video instead of full-screen light inserts. */
    val overlay: Boolean = false,
    /** VIDEO style: camera zooms and push-ins on the video. */
    val zooms: Boolean = true,
    /** VIDEO style: inserts slide in instead of a plain fade. */
    val transitions: Boolean = true,
    /** Voice loudness per 20 ms (0..1) when the picture pulses with the voice. */
    val pulse: FloatArray? = null,
    /** Folder of animated Noto emoji (Lottie) when emoji are animated; null = still emoji. */
    val emojiDir: File? = null
) {
    val steps = Studio.stepNumbers(script.scenes)

    /** Time of cue [c] of the scene at [st]: the start of its word. */
    private fun cueMs(st: SceneTime, c: SCue): Long = words.getOrNull(st.words.first + c.at)?.takeIf { st.words.first + c.at in st.words }?.startMs ?: st.startMs

    /** Words highlighted by the AI director (indices into [words]). */
    val emph: Set<Int> = times.flatMap { st ->
        val sc = script.scenes.getOrNull(st.scene)
        if (sc == null || sc.emph.isEmpty()) emptyList() else StudioDirector.emphWords(words.map { it.text }, st.words, sc.emph).toList()
    }.toSet()

    /** Camera punch-ins of every scene, ms. */
    val zoomMs: List<List<Long>> = times.map { st -> script.scenes.getOrNull(st.scene)?.cues.orEmpty().filter { it.type == "zoom" }.map { cueMs(st, it) }.sorted() }

    /** Animated shapes of every scene with the times of their key frames. */
    val shapes: List<List<Pair<SShape, List<Long>>>> = times.map { st ->
        script.scenes.getOrNull(st.scene)?.shapes.orEmpty().map { sh ->
            sh to StudioKeys.times(sh.keys) { k -> words.getOrNull(st.words.first + k)?.takeIf { st.words.first + k in st.words }?.startMs ?: st.startMs }
        }
    }

    /** Stickers of every scene with their time. */
    val pops: List<List<Pair<Long, SCue>>> = times.map { st -> script.scenes.getOrNull(st.scene)?.cues.orEmpty().filter { it.type == "pop" }.map { cueMs(st, it) to it } }

    companion object {
        /** [ranges] = words of every scene when known exactly (Edge voice per scene); otherwise split by word counts. */
        fun build(
            script: SScript, words: List<SWord>, totalMs: Long, style: StudioStyle, accent: Int,
            ranges: List<IntRange>? = null, overlay: Boolean = false, zooms: Boolean = true, transitions: Boolean = true,
            pulse: FloatArray? = null, emojiDir: File? = null
        ): StudioPlan {
            val ranges = ranges?.takeIf { it.size == script.scenes.size }
                ?: Studio.assignScenes(words.size, script.scenes.map { Studio.tokens(it.say).size })
            val times = Studio.sceneTimes(words, ranges, totalMs)
            val single = style != StudioStyle.DARK
            val caps = ranges.map { Studio.captions(words, it, maxWords = if (single) 1 else 4) }
            return StudioPlan(script, words, times, caps, totalMs, style, accent, overlay, zooms, transitions, pulse, emojiDir)
        }
    }
}

/** Draws frames of a [StudioPlan] on a Canvas (software canvas, so blur and shadows work). */
class StudioPainter(private val w: Int, private val h: Int, private val plan: StudioPlan) {
    private var dark = false
    private var bg = 0
    private var card = 0
    private var stroke = 0
    private var ink = 0
    private var muted = 0

    /** Dark or light colours; the VIDEO style switches between them per scene. */
    private fun palette(isDark: Boolean) {
        dark = isDark
        bg = if (dark) 0xFF131417.toInt() else 0xFFF1F3F8.toInt()
        card = if (dark) 0xFF1D1F24.toInt() else 0xFFFFFFFF.toInt()
        stroke = if (dark) 0xFF2E3138.toInt() else 0xFFE2E5EC.toInt()
        ink = if (dark) 0xFFF3F4F6.toInt() else 0xFF14161B.toInt()
        muted = if (dark) 0xFF9AA0A9.toInt() else 0xFF6B7280.toInt()
    }

    init {
        palette(plan.style == StudioStyle.DARK)
    }
    private val green = 0xFF34C759.toInt()
    private val accent = plan.accent
    // 1 % of the width of a vertical frame; on wide frames it follows the height so nothing gets too big
    private val u = min(w.toFloat(), h * 0.8f) / 100f
    private val wide = w > h

    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val bigWordP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.CENTER
    }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private fun glowPaint(forDark: Boolean) = Paint().apply {
        shader = RadialGradient(w / 2f, h * 0.56f, w * 0.75f, (accent and 0x00FFFFFF) or (if (forDark) 0x26000000 else 0x14000000), 0, Shader.TileMode.CLAMP)
    }
    private val glowDark = glowPaint(true)
    private val glowLight = glowPaint(false)
    private val shade = Paint()
    private val bottomShade = Paint().apply {
        shader = android.graphics.LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
    }
    private val topShade = Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.7f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP)
    }
    private val layouts = HashMap<IntRange, List<Pair<Int, Pair<Float, Float>>>>()

    private fun alphaAt(i: Int, tMs: Long): Float {
        val st = plan.times[i]
        val last = i == plan.times.lastIndex
        val tr = plan.script.scenes[i].transition
        val nextTr = plan.script.scenes.getOrNull(i + 1)?.transition ?: ""
        val fadeIn = if (tr == "none" || tr == "whip") 1f else Studio.appear(tMs, st.startMs - 1, 260)
        val fadeOut = if (last || nextTr == "none" || nextTr == "whip") 1f else 1f - Studio.appear(tMs, st.endMs - 200, 200)
        return fadeIn * fadeOut
    }

    /** VIDEO style: is scene [i] a full-screen graphic insert (instead of the speaker)? */
    private fun insert(i: Int): Boolean =
        plan.style == StudioStyle.VIDEO && ((!plan.overlay && plan.script.scenes[i].kind != "text") || fullPhoto(i) != null)

    /** B-roll photo of scene [i] (null when it has none or it cannot be read). */
    private fun photo(i: Int): Bitmap? = plan.script.scenes.getOrNull(i)?.media?.takeIf { it.isNotBlank() }?.let { StudioPhotos.get(it, max(w, h)) }

    /** The photo when it fills the whole frame of scene [i]. */
    private fun fullPhoto(i: Int): Bitmap? = if (plan.script.scenes.getOrNull(i)?.brollMode == "card") null else photo(i)

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** A photo filling [r] with a slow Ken Burns move: zoom in or out and a small pan, alternating per scene. */
    private fun drawPhoto(c: Canvas, bmp: Bitmap, r: RectF, i: Int, local: Long, sceneMs: Long, alpha: Float, radius: Float = 0f) {
        if (alpha <= 0f) return
        val p = if (sceneMs > 0) (local.toFloat() / sceneMs).coerceIn(0f, 1f) else 0f
        val zoom = if (i % 2 == 0) 1.03f + 0.10f * p else 1.13f - 0.10f * p
        val dir = when (i % 3) {
            0 -> -1f
            1 -> 1f
            else -> 0f
        }
        val k = max(r.width() / bmp.width, r.height() / bmp.height) * zoom
        val m = Matrix()
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        m.postScale(k, k)
        m.postTranslate(r.centerX() + dir * r.width() * 0.012f * (2 * p - 1), r.centerY())
        c.save()
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }) else c.clipRect(r)
        photoPaint.alpha = (255 * alpha).toInt()
        c.drawBitmap(bmp, m, photoPaint)
        c.restore()
    }

    /** B-roll photo on a card where the visual goes. */
    private fun photoCard(c: Canvas, bmp: Bitmap, i: Int, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val cw = min(w * 0.86f, 86f * u)
        val ch = min(cw * bmp.height / max(1, bmp.width), 64f * u).coerceAtLeast(cw * 0.5f)
        val pa = StudioMotion.at(local, 0, 420) { StudioMotion.backOut(it, 1.2f) }
        c.save()
        val k = 0.85f + 0.15f * pa
        c.scale(k, k, cx, cy)
        val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        roundCard(c, RectF(r.left - 0.8f * u, r.top - 0.8f * u, r.right + 0.8f * u, r.bottom + 0.8f * u), 3.4f * u, a * StudioMotion.clamp(pa))
        drawPhoto(c, bmp, r, i, local, sceneMs, a * StudioMotion.clamp(pa), 2.8f * u)
        c.restore()
    }

    private val shapeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /** The first [d] (0..1) of the length of [p], over all its strokes in order. */
    private fun partial(p: Path, d: Float): Path {
        if (d >= 0.999f) return p
        val out = Path()
        if (d <= 0f) return out
        var total = 0f
        val pm = PathMeasure(p, false)
        do {
            total += pm.length
        } while (pm.nextContour())
        var left = total * d
        val pm2 = PathMeasure(p, false)
        do {
            if (left <= 0f) break
            val len = pm2.length
            pm2.getSegment(0f, min(len, left), out, true)
            left -= len
        } while (pm2.nextContour())
        return out
    }

    /** Shapes of the AI director animated on their key frames (arrows draw themselves, rings circle, a spotlight dims the rest). */
    private fun shapes(c: Canvas, i: Int, tMs: Long, a: Float) {
        val list = plan.shapes.getOrNull(i) ?: return
        if (a <= 0f) return
        val base = min(w, h).toFloat()
        for ((sh, times) in list) {
            val st = StudioKeys.state(sh, times, tMs) ?: continue
            val al = st.o.coerceIn(0f, 1f) * a
            if (al <= 0.01f) continue
            val d = st.draw.coerceIn(0f, 1f)
            val col = StudioKeys.color(sh.color, accent, ink)
            val pw = sh.w * base
            val ph = sh.h * base
            val stroke = max(2f, 0.013f * base)
            shapeLine.color = col
            shapeLine.alpha = (255 * al).toInt()
            shapeLine.strokeWidth = stroke
            shapeFill.color = col
            shapeFill.alpha = (255 * al).toInt()
            if (sh.el == "spotlight") {
                val cx = st.x * w
                val cy = st.y * h
                val r = max(1f, pw / 2 * st.s)
                shapeFill.color = 0xFF000000.toInt()
                shapeFill.alpha = (170 * al).toInt()
                c.drawPath(Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                    addCircle(cx, cy, r, Path.Direction.CW)
                }, shapeFill)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * d, false, shapeLine)
                continue
            }
            c.save()
            c.translate(st.x * w, st.y * h)
            c.rotate(st.r)
            if (st.s != 1f) c.scale(st.s, st.s)
            val box = RectF(-pw / 2, -ph / 2, pw / 2, ph / 2)
            when (sh.el) {
                "rect" -> {
                    val rad = min(pw, ph) * 0.18f
                    if (sh.fill) {
                        shapeFill.alpha = (210 * al).toInt()
                        c.drawRoundRect(box, rad, rad, shapeFill)
                    } else c.drawPath(partial(Path().apply { addRoundRect(box, rad, rad, Path.Direction.CW) }, d), shapeLine)
                }
                "circle", "ring" -> if (sh.fill) {
                    shapeFill.alpha = (210 * al).toInt()
                    c.drawOval(box, shapeFill)
                } else c.drawArc(box, -90f, 360f * d, false, shapeLine)
                "line" -> c.drawPath(partial(Path().apply { moveTo(-pw / 2, 0f); lineTo(pw / 2, 0f) }, d), shapeLine)
                "underline" -> {
                    shapeLine.strokeWidth = max(stroke * 2.4f, min(ph, 0.05f * base))
                    shapeLine.alpha = (165 * al).toInt()
                    c.drawPath(partial(Path().apply { moveTo(-pw / 2, stroke * 0.4f); quadTo(0f, -stroke * 0.8f, pw / 2, 0f) }, d), shapeLine)
                }
                "arrow" -> {
                    c.drawPath(partial(Path().apply { moveTo(-pw / 2, 0f); lineTo(pw / 2, 0f) }, d), shapeLine)
                    if (d > 0.05f) {
                        val tip = -pw / 2 + pw * d
                        val hs = max(stroke * 2.6f, pw * 0.11f)
                        c.drawPath(Path().apply {
                            moveTo(tip - hs, -hs * 0.7f)
                            lineTo(tip, 0f)
                            lineTo(tip - hs, hs * 0.7f)
                        }, shapeLine)
                    }
                }
                "check" -> {
                    shapeLine.strokeWidth = stroke * 1.6f
                    c.drawPath(partial(Path().apply {
                        moveTo(-pw * 0.36f, 0f)
                        lineTo(-pw * 0.1f, ph * 0.28f)
                        lineTo(pw * 0.38f, -ph * 0.3f)
                    }, d), shapeLine)
                }
                "cross" -> {
                    shapeLine.strokeWidth = stroke * 1.6f
                    c.drawPath(partial(Path().apply {
                        moveTo(-pw * 0.32f, -ph * 0.32f)
                        lineTo(pw * 0.32f, ph * 0.32f)
                        moveTo(pw * 0.32f, -ph * 0.32f)
                        lineTo(-pw * 0.32f, ph * 0.32f)
                    }, d), shapeLine)
                }
                "text" -> {
                    shapeText.textSize = ph
                    while (shapeText.measureText(sh.text) > w * 0.9f / max(0.2f, st.s) && shapeText.textSize > 2 * u) shapeText.textSize *= 0.9f
                    val tw = shapeText.measureText(sh.text)
                    val sz = shapeText.textSize
                    if (sh.fill) {
                        shapeFill.alpha = (235 * al).toInt()
                        c.drawRoundRect(RectF(-tw / 2 - sz * 0.45f, -sz * 0.75f, tw / 2 + sz * 0.45f, sz * 0.45f), sz * 0.3f, sz * 0.3f, shapeFill)
                        shapeText.color = 0xFFFFFFFF.toInt()
                    } else {
                        shapeText.color = col
                        shapeText.setShadowLayer(sz * 0.12f, 0f, sz * 0.04f, 0x99000000.toInt())
                    }
                    shapeText.alpha = (255 * al).toInt()
                    c.drawText(sh.text, 0f, sz * 0.33f, shapeText)
                    shapeText.clearShadowLayer()
                }
                "emoji" -> {
                    emoji(c, sh.text, 0f, ph * 0.35f, ph, al)
                }
            }
            c.restore()
        }
    }

    /** True when the caller must put the video frame on the canvas before [draw] (hidden under a full insert). */
    fun needsVideo(tMs: Long): Boolean {
        if (plan.style != StudioStyle.VIDEO) return false
        val i = Studio.sceneAt(plan.times, tMs)
        // keep the video under an insert until its slide / fade has finished
        return i < 0 || !insert(i) || alphaAt(i, tMs) < 1f || tMs - plan.times[i].startMs < 400
    }

    /** Zoom of the video at [tMs] (1 = none); the centre is [focusY] of the height, where faces usually are. */
    fun camera(tMs: Long): Float {
        if (plan.style != StudioStyle.VIDEO) return 1f
        val i = Studio.sceneAt(plan.times, tMs)
        if (i < 0) return 1f
        val st = plan.times[i]
        val base = if (plan.zooms) Studio.cameraZoom(i, tMs - st.startMs, st.endMs - st.startMs) else 1f
        return base * StudioMotion.punch(tMs, plan.zoomMs.getOrElse(i) { emptyList() }, 0.14f) * (1f + 0.02f * pulseAt(tMs))
    }

    /** Voice loudness at [tMs] for the pulse (0 when it is off). */
    private fun pulseAt(tMs: Long): Float = plan.pulse?.let { p -> if (p.isEmpty()) 0f else p[(tMs / 20).toInt().coerceIn(0, p.lastIndex)] } ?: 0f

    /** Time of the frame being drawn (animated emoji follow it). */
    private var nowMs = 0L
    private val lottie = HashMap<String, com.airbnb.lottie.LottieDrawable>()

    /** Animated emoji [e] when it is downloaded; null for a still one. */
    private fun live(e: String): com.airbnb.lottie.LottieDrawable? {
        val dir = plan.emojiDir ?: return null
        lottie[e]?.let { return it }
        val comp = StudioLottie.composition(dir, e) ?: return null
        val d = runCatching {
            com.airbnb.lottie.LottieDrawable().apply {
                setAsyncUpdates(com.airbnb.lottie.AsyncUpdates.DISABLED)
                setComposition(comp)
            }
        }.getOrNull() ?: return null
        lottie[e] = d
        return d
    }

    /** Emoji [e] centred at [cx] with its text baseline at [baseline] and size [size]: animated when possible, else text. */
    private fun emoji(c: Canvas, e: String, cx: Float, baseline: Float, size: Float, alpha: Float) {
        if (e.isBlank() || alpha <= 0f) return
        val d = live(e.trim())
        val comp = d?.composition
        if (d != null && comp != null) {
            try {
                val dur = max(1f, comp.duration)
                d.progress = (nowMs % dur.toLong()).toFloat() / dur
                val half = size * 0.58f
                val cy = baseline - size * 0.36f
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                d.draw(c)
                return
            } catch (ex: Exception) {
                lottie.remove(e.trim())
            }
        }
        emojiP.textSize = size
        emojiP.alpha = (255 * alpha).toInt()
        c.drawText(e, cx, baseline, emojiP)
    }

    val focusY = 0.38f

    /** Puts a video frame on the canvas with the camera zoom of [tMs]. */
    fun drawVideo(c: Canvas, frame: Bitmap, tMs: Long, paint: Paint) {
        val z = camera(tMs)
        c.save()
        if (z != 1f) c.scale(z, z, w / 2f, h * focusY)
        c.drawBitmap(frame, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        c.restore()
    }

    fun draw(c: Canvas, tMs: Long) {
        nowMs = tMs
        val i = Studio.sceneAt(plan.times, tMs)
        if (plan.style == StudioStyle.VIDEO) {
            if (i >= 0) drawOnVideo(c, i, tMs)
            return
        }
        c.drawColor(bg)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), if (dark) glowDark else glowLight)
        if (i < 0) return
        c.save()
        val z = StudioMotion.punch(tMs, plan.zoomMs.getOrElse(i) { emptyList() }, 0.07f) * (1f + 0.035f * pulseAt(tMs))
        if (z != 1f) c.scale(z, z, w / 2f, h / 2f)
        val st = plan.times[i]
        enter(c, plan.script.scenes[i].transition, i, tMs - st.startMs)
        val full = fullPhoto(i)
        val a = alphaAt(i, tMs)
        if (full != null) {
            // B-roll on the whole frame: the photo, darkened for the captions, with light text
            drawPhoto(c, full, RectF(0f, 0f, w.toFloat(), h.toFloat()), i, tMs - st.startMs, st.endMs - st.startMs, a)
            shade.color = 0xFF000000.toInt()
            shade.alpha = (105 * a).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
            bottomShade.alpha = (255 * a).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bottomShade)
            bottomShade.alpha = 255
            palette(true)
        }
        drawScene(c, i, tMs, full != null)
        shapes(c, i, tMs, a)
        pops(c, i, tMs, a)
        if (full != null) palette(plan.style == StudioStyle.DARK)
        c.restore()
    }

    /** Entry of a scene set by the AI director: slide, whip (fast push from the side) or zoom; fade is the default. */
    private fun enter(c: Canvas, type: String, i: Int, local: Long) {
        if (i == 0) return
        val dir = if (i % 2 == 0) 1f else -1f
        when (type) {
            "slide" -> c.translate((1 - StudioMotion.at(local, 0, 380) { StudioMotion.backOut(it, 1.2f) }) * w * 0.4f * dir, 0f)
            "whip" -> c.translate((1 - StudioMotion.at(local, 0, 200) { StudioMotion.inOut(it) }) * w * dir, 0f)
            "zoom" -> {
                val k = 1.35f - 0.35f * StudioMotion.at(local, 0, 360) { StudioMotion.backOut(it) }
                c.scale(k, k, w / 2f, h / 2f)
            }
        }
    }

    /** Stickers of the AI director: a tilted pill with an emoji and a few words that springs in on its word. */
    private fun pops(c: Canvas, i: Int, tMs: Long, a: Float) {
        val list = plan.pops.getOrNull(i) ?: return
        if (a <= 0f) return
        list.forEachIndexed { k, (ms, cue) ->
            if (tMs < ms) return@forEachIndexed
            val label = (cue.icon + " " + cue.text.uppercase()).trim()
            if (label.isEmpty()) return@forEachIndexed
            val p = StudioMotion.at(tMs, ms, 460) { StudioMotion.spring(it) }
            val cx = if (wide) w * 0.80f else w / 2f
            val cy = if (wide) h * (0.17f + 0.16f * k) else h * (0.31f + 0.075f * k)
            headP.textAlign = Paint.Align.CENTER
            headP.textSize = 5.4f * u
            while (headP.measureText(label) > (if (wide) w * 0.34f else w * 0.8f) && headP.textSize > 2.5f * u) headP.textSize *= 0.9f
            val tw = headP.measureText(label)
            c.save()
            c.rotate(if (k % 2 == 0) -4f else 4f, cx, cy)
            val sc = 0.3f + 0.7f * p
            c.scale(sc, sc, cx, cy)
            fill.color = accent
            fill.alpha = (255 * a * StudioMotion.clamp(p * 2)).toInt()
            fill.setShadowLayer(1.6f * u, 0f, 0.6f * u, 0x55000000)
            val r = RectF(cx - tw / 2 - 3.2f * u, cy - headP.textSize * 0.95f, cx + tw / 2 + 3.2f * u, cy + headP.textSize * 0.55f)
            c.drawRoundRect(r, 3f * u, 3f * u, fill)
            fill.clearShadowLayer()
            headP.color = 0xFFFFFFFF.toInt()
            headP.alpha = fill.alpha
            c.drawText(label, cx, cy + headP.textSize * 0.1f, headP)
            c.restore()
        }
    }

    /** Captions and the visual of scene [i]; over a full-frame photo ([onPhoto]) only the words. */
    private fun drawScene(c: Canvas, i: Int, tMs: Long, onPhoto: Boolean) {
        val st = plan.times[i]
        val sc = plan.script.scenes[i]
        val a = alphaAt(i, tMs)
        if (a <= 0f) return
        val local = tMs - st.startMs
        val sceneMs = st.endMs - st.startMs
        val card = if (onPhoto) null else photo(i)
        if (plan.style == StudioStyle.DARK) {
            val textOnly = onPhoto || (card == null && (sc.kind == "text" || (sc.items.isEmpty() && sc.kind !in listOf("step", "stat", "big", "quote"))))
            caption(c, i, tMs, a, if (onPhoto) h * 0.62f else if (textOnly) h * 0.40f else h * 0.15f, if (textOnly) 7.6f * u else 6.2f * u)
            if (card != null) photoCard(c, card, i, w / 2f, h * 0.57f, local, sceneMs, a)
            else if (!textOnly) visual(c, sc, i, w / 2f, h * 0.57f, local, sceneMs, a)
        } else {
            val head = sc.title.takeIf { it.isNotBlank() && sc.kind in listOf("step", "text", "icons", "list", "flow", "cycle", "chart", "compare") }
            if (head != null) headline(c, head, h * 0.15f, a, local)
            if (card != null) photoCard(c, card, i, w / 2f, h * 0.48f, local, sceneMs, a)
            else if (sc.kind != "text" && !onPhoto) visual(c, sc, i, w / 2f, h * 0.48f, local, sceneMs, a)
            singleWord(c, i, tMs, a, h * 0.80f)
        }
    }

    /** VIDEO style: the frame is already on the canvas. */
    private fun drawOnVideo(c: Canvas, i: Int, tMs: Long) {
        val st = plan.times[i]
        val sc = plan.script.scenes[i]
        val local = tMs - st.startMs
        val sceneMs = st.endMs - st.startMs
        val a = alphaAt(i, tMs)
        palette(false)
        if (insert(i)) {
            // light insert fades in over the speaker, slides or whips in from alternating sides, or zooms in
            val tr = sc.transition
            val slide = plan.transitions && tr !in listOf("fade", "zoom", "none")
            val fade = if (tr == "none") 1f else Studio.appear(tMs, st.startMs - 1, if (tr == "whip") 200 else if (plan.transitions) 320 else 260)
            val dx = if (slide) (1 - fade) * w * (if (i % 2 == 0) 1f else -1f) else 0f
            c.save()
            c.translate(dx, 0f)
            if (tr == "zoom") {
                val k = 1.3f - 0.3f * fade
                c.scale(k, k, w / 2f, h / 2f)
            }
            val full = fullPhoto(i)
            if (full != null) {
                // B-roll photo instead of the speaker, the spoken word below
                drawPhoto(c, full, RectF(0f, 0f, w.toFloat(), h.toFloat()), i, local, sceneMs, if (slide) 1f else fade)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bottomShade)
                if (a > 0f) {
                    spokenWord(c, i, tMs, a, h * 0.80f)
                    shapes(c, i, tMs, a)
                    pops(c, i, tMs, a)
                }
                c.restore()
                return
            }
            shade.color = bg
            shade.alpha = (255 * (if (slide) 1f else fade)).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
            glowLight.alpha = shade.alpha
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowLight)
            glowLight.alpha = 255
            if (a > 0f) {
                sc.title.takeIf { it.isNotBlank() && sc.kind !in listOf("big", "stat", "quote") }?.let { headline(c, it, h * 0.15f, a, local) }
                visual(c, sc, i, w / 2f, h * 0.48f, local, sceneMs, a)
                singleWord(c, i, tMs, a, h * 0.80f)
                shapes(c, i, tMs, a)
                pops(c, i, tMs, a)
            }
            c.restore()
            return
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bottomShade)
        val graphic = plan.overlay && sc.kind != "text"
        if (graphic || sc.title.isNotBlank()) {
            topShade.alpha = (255 * a).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h * 0.7f, topShade)
            topShade.alpha = 255
        }
        if (a <= 0f) return
        if (sc.title.isNotBlank() && sc.kind !in listOf("stat", "quote")) headline(c, sc.title, h * 0.12f, a, local, 0xFFFFFFFF.toInt())
        val card = photo(i)
        if (card != null) photoCard(c, card, i, w / 2f, h * 0.36f, local, sceneMs, a)
        else if (graphic) visual(c, sc, i, w / 2f, h * 0.36f, local, sceneMs, a)
        spokenWord(c, i, tMs, a, h * 0.80f)
        shapes(c, i, tMs, a)
        pops(c, i, tMs, a)
    }

    /** One word on the video: white bold with a shadow and an accent underline. */
    private fun spokenWord(c: Canvas, scene: Int, tMs: Long, a: Float, y: Float) {
        val groups = plan.captions.getOrNull(scene) ?: return
        val g = groups.lastOrNull { plan.words[it.first].startMs - 60 <= tMs } ?: return
        val word = plan.words[g.first]
        val p = Studio.appear(tMs, word.startMs - 60, 160)
        val txt = word.text.trim { !it.isLetterOrDigit() }
        val hot = g.first in plan.emph
        textP.textAlign = Paint.Align.CENTER
        textP.maskFilter = null
        textP.textSize = (if (hot) 8.8f else 7.4f) * u
        while (textP.measureText(txt) > w * 0.9f && textP.textSize > 3 * u) textP.textSize *= 0.9f
        textP.color = if (hot) 0xFFFFE14D.toInt() else 0xFFFFFFFF.toInt()
        textP.alpha = (255 * p * a).toInt()
        textP.setShadowLayer(1.6f * u, 0f, 0.4f * u, 0xB0000000.toInt())
        c.save()
        val k = 1.1f - 0.1f * p
        c.scale(k, k, w / 2f, y)
        c.drawText(txt, w / 2f, y, textP)
        c.restore()
        textP.clearShadowLayer()
        val tw = textP.measureText(txt)
        fill.color = accent
        fill.alpha = (255 * p * a).toInt()
        val lw = tw * Studio.appear(tMs, word.startMs, 220)
        c.drawRoundRect(RectF(w / 2f - lw / 2, y + 2f * u, w / 2f + lw / 2, y + 2.8f * u), 0.4f * u, 0.4f * u, fill)
        textP.textAlign = Paint.Align.LEFT
    }

    // ---------- captions ----------

    /** Word group with each word blurring in when it is spoken (DARK style). */
    private fun caption(c: Canvas, scene: Int, tMs: Long, a: Float, top: Float, size: Float) {
        val groups = plan.captions.getOrNull(scene) ?: return
        val g = groups.lastOrNull { plan.words[it.first].startMs - 90 <= tMs } ?: return
        textP.textSize = size
        val pos = layouts.getOrPut(g) {
            val ws = g.map { textP.measureText(plan.words[it].text) }
            val space = textP.measureText(" ")
            val lines = Studio.lines(ws, space, w * 0.84f)
            val out = mutableListOf<Pair<Int, Pair<Float, Float>>>()
            lines.forEachIndexed { li, r ->
                val lineW = r.sumOf { ws[it].toDouble() }.toFloat() + space * (r.count() - 1)
                var x = (w - lineW) / 2
                for (k in r) {
                    out += (g.first + k) to (x to top + size * 1.25f * li)
                    x += ws[k] + space
                }
            }
            out
        }
        for ((wi, xy) in pos) {
            val wa = Studio.appear(tMs, plan.words[wi].startMs - 90, 240)
            if (wa <= 0f) continue
            textP.color = if (wi in plan.emph) accent else ink
            textP.alpha = (255 * wa * a).toInt()
            textP.maskFilter = if (wa < 0.98f) BlurMaskFilter(max(0.5f, (1 - wa) * size * 0.28f), BlurMaskFilter.Blur.NORMAL) else null
            c.drawText(plan.words[wi].text, xy.first, xy.second + size + (1 - wa) * size * 0.35f, textP)
        }
        textP.maskFilter = null
    }

    /** One big word at a time, italic serif in capitals (LIGHT style). */
    private fun singleWord(c: Canvas, scene: Int, tMs: Long, a: Float, y: Float) {
        val groups = plan.captions.getOrNull(scene) ?: return
        val g = groups.lastOrNull { plan.words[it.first].startMs - 60 <= tMs } ?: return
        val word = plan.words[g.first]
        val p = Studio.appear(tMs, word.startMs - 60, 160)
        val txt = word.text.trim { !it.isLetterOrDigit() }.uppercase()
        bigWordP.textSize = 8.4f * u
        while (bigWordP.measureText(txt) > w * 0.9f && bigWordP.textSize > 3 * u) bigWordP.textSize *= 0.9f
        val accentWord = if (plan.emph.isNotEmpty()) g.first in plan.emph else Studio.norm(word.text).length >= 7 && (g.first % 3 == 0)
        bigWordP.color = if (accentWord) accent else ink
        bigWordP.alpha = (255 * p * a).toInt()
        c.save()
        val k = 1.12f - 0.12f * p
        c.scale(k, k, w / 2f, y)
        c.drawText(txt, w / 2f, y, bigWordP)
        c.restore()
    }

    /** Condensed capitals; the last word in the accent colour (LIGHT style). */
    private fun headline(c: Canvas, s: String, y: Float, a: Float, local: Long, color: Int = ink) {
        val words = s.uppercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return
        headP.textSize = 8.6f * u
        val space = headP.measureText(" ")
        val widths = words.map { headP.measureText(it) }
        val lines = Studio.lines(widths, space, w * 0.88f)
        val p = Studio.appear(local, 0, 320)
        lines.forEachIndexed { li, r ->
            val lineW = r.sumOf { widths[it].toDouble() }.toFloat() + space * (r.count() - 1)
            var x = (w - lineW) / 2
            for (k in r) {
                headP.textAlign = Paint.Align.LEFT
                headP.color = if (k == words.lastIndex && words.size > 1) accent else color
                headP.alpha = (255 * a * p).toInt()
                c.drawText(words[k], x, y + headP.textSize * 1.05f * li + (1 - p) * 3 * u, headP)
                x += widths[k] + space
            }
        }
    }

    // ---------- visuals ----------

    private fun visual(c: Canvas, sc: SScene, index: Int, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        when (sc.kind) {
            "step" -> step(c, plan.steps[index], if (dark) sc.title else "", cx, cy, local, a)
            "icons" -> grid(c, sc.items, cx, cy, local, sceneMs, a)
            "list" -> list(c, sc, cx, cy, local, sceneMs, a)
            "flow" -> flow(c, sc.items, cx, cy, local, sceneMs, a)
            "cycle" -> cycle(c, sc.items, cx, cy, local, sceneMs, a)
            "big" -> big(c, sc.items.firstOrNull() ?: SItem("💡", sc.title), cx, cy, local, a)
            "stat" -> stat(c, sc, cx, cy, local, a)
            "quote" -> quote(c, sc, cx, cy, local, a)
            "chart" -> chart(c, sc.items, cx, cy, local, sceneMs, a)
            "compare" -> compare(c, sc.items, cx, cy, local, a)
            else -> if (sc.items.isNotEmpty()) grid(c, sc.items, cx, cy, local, sceneMs, a)
        }
    }

    private fun cardPaint(alpha: Float) = fill.apply {
        color = card
        this.alpha = (255 * alpha).toInt()
        if (!dark) setShadowLayer(2.4f * u, 0f, 0.8f * u, ((0x22 * alpha).toInt() shl 24)) else clearShadowLayer()
    }

    private fun roundCard(c: Canvas, r: RectF, radius: Float, alpha: Float, border: Int = stroke) {
        c.drawRoundRect(r, radius, radius, cardPaint(alpha))
        fill.clearShadowLayer()
        line.color = border
        line.alpha = (255 * alpha).toInt()
        line.strokeWidth = max(1f, 0.25f * u)
        c.drawRoundRect(r, radius, radius, line)
    }

    /** Emoji tile with a label under the emoji; pops in with [p]. */
    private fun tile(c: Canvas, x: Float, y: Float, size: Float, item: SItem, p: Float, a: Float, highlight: Boolean = false) {
        if (p <= 0f) return
        val al = p * a
        c.save()
        val k = 0.86f + 0.14f * p
        c.scale(k, k, x, y)
        val r = RectF(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
        roundCard(c, r, size * 0.2f, al, if (highlight) accent else stroke)
        val hasLabel = item.label.isNotBlank()
        if (item.icon.isNotBlank()) {
            emoji(c, item.icon, x, y + (if (hasLabel) -size * 0.04f else size * 0.17f), size * (if (hasLabel) 0.36f else 0.5f), al)
        }
        if (hasLabel) {
            labelP.color = ink
            labelP.alpha = (255 * al).toInt()
            labelP.textSize = size * 0.12f
            val lines = wrap(item.label, labelP, size * 0.88f, 2)
            val base = if (item.icon.isNotBlank()) y + size * 0.24f else y - (lines.size - 1) * labelP.textSize * 0.6f + labelP.textSize * 0.35f
            lines.forEachIndexed { li, s -> c.drawText(s, x, base + li * labelP.textSize * 1.15f, labelP) }
        }
        c.restore()
    }

    private fun wrap(s: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
        val words = s.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val ranges = Studio.lines(words.map { p.measureText(it) }, p.measureText(" "), maxW)
        val out = ranges.take(maxLines).map { r -> r.joinToString(" ") { words[it] } }.toMutableList()
        if (ranges.size > maxLines) out[out.lastIndex] = out.last().trimEnd() + "…"
        return out.map { line ->
            var t = line
            while (p.measureText(t) > maxW && t.length > 2) t = t.dropLast(2) + "…"
            t
        }
    }

    private fun itemP(i: Int, n: Int, local: Long, sceneMs: Long) = Studio.appear(local, Studio.itemDelay(i, n, sceneMs), 300)

    private fun grid(c: Canvas, items: List<SItem>, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val n = items.size
        if (n == 0) return
        val cols = if (n <= 3) n else if (n == 4) 2 else 3
        val rows = (n + cols - 1) / cols
        val gap = 3f * u
        val size = min((w * 0.86f - gap * (cols - 1)) / cols, 26f * u)
        val top = cy - (rows * size + (rows - 1) * gap) / 2 + size / 2
        items.forEachIndexed { i, it ->
            val r = i / cols
            val inRow = if (r == rows - 1) n - r * cols else cols
            val left = cx - (inRow * size + (inRow - 1) * gap) / 2 + size / 2
            tile(c, left + (i % cols) * (size + gap), top + r * (size + gap), size, it, itemP(i, n, local, sceneMs), a)
        }
    }

    private fun flow(c: Canvas, items: List<SItem>, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val n = items.size
        if (n == 0) return
        val arrow = 5f * u
        val size = min((w * 0.9f - arrow * (n - 1)) / n, 24f * u)
        val total = n * size + (n - 1) * arrow
        var x = cx - total / 2 + size / 2
        items.forEachIndexed { i, it ->
            val p = itemP(i, n, local, sceneMs)
            tile(c, x, cy, size, it, p, a)
            if (i < n - 1) {
                val pa = Studio.appear(local, Studio.itemDelay(i, n, sceneMs) + 150, 250)
                if (pa > 0) {
                    line.color = muted
                    line.alpha = (255 * pa * a).toInt()
                    line.strokeWidth = 0.6f * u
                    val ax = x + size / 2 + arrow / 2
                    c.drawPath(Path().apply {
                        moveTo(ax - 0.8f * u, cy - 1.4f * u)
                        lineTo(ax + 0.8f * u, cy)
                        lineTo(ax - 0.8f * u, cy + 1.4f * u)
                    }, line)
                }
            }
            x += size + arrow
        }
    }

    private fun cycle(c: Canvas, items: List<SItem>, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val n = items.size
        if (n == 0) return
        val radius = 27f * u
        val size = min(20f * u, radius * 1.9f * sin(PI / max(n, 3)).toFloat() * 0.8f)
        val ring = Studio.appear(local, 100, 600)
        line.color = accent
        line.alpha = (180 * a * ring).toInt()
        line.strokeWidth = 0.5f * u
        val step = 360f / n
        val gapDeg = (size * 0.75f / radius) * (180f / PI.toFloat())
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        for (i in 0 until n) {
            val from = -90f + i * step + gapDeg
            val sweep = (step - 2 * gapDeg) * ring
            if (sweep <= 0) continue
            c.drawArc(oval, from, sweep, false, line)
            // arrow head at the end of the arc
            val end = Math.toRadians((from + sweep).toDouble())
            val ex = cx + radius * cos(end).toFloat()
            val ey = cy + radius * sin(end).toFloat()
            val dir = end + PI / 2
            val hx = cos(dir).toFloat()
            val hy = sin(dir).toFloat()
            val nx = -hy
            val ny = hx
            c.drawPath(Path().apply {
                moveTo(ex - hx * 1.6f * u + nx * 1.1f * u, ey - hy * 1.6f * u + ny * 1.1f * u)
                lineTo(ex, ey)
                lineTo(ex - hx * 1.6f * u - nx * 1.1f * u, ey - hy * 1.6f * u - ny * 1.1f * u)
            }, line)
        }
        val active = if (local > 900) ((local - 900) / 700 % n).toInt() else -1
        items.forEachIndexed { i, it ->
            val ang = Math.toRadians((-90.0 + i * step))
            tile(c, cx + radius * cos(ang).toFloat(), cy + radius * sin(ang).toFloat(), size, it, itemP(i, n, local, sceneMs), a, i == active)
        }
    }

    private fun list(c: Canvas, sc: SScene, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val items = sc.items
        val n = items.size
        if (n == 0) return
        val rowH = 9.5f * u
        val head = if (dark && sc.title.isNotBlank()) 8f * u else 0f
        val cw = min(w * 0.84f, 84f * u)
        val ch = head + n * rowH + 3f * u
        val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        val pa = Studio.appear(local, 0, 300)
        roundCard(c, r, 3f * u, pa * a)
        if (head > 0) {
            labelP.textAlign = Paint.Align.LEFT
            labelP.color = muted
            labelP.alpha = (255 * pa * a).toInt()
            labelP.textSize = 3.4f * u
            c.drawText(sc.title, r.left + 4f * u, r.top + 6f * u, labelP)
        }
        items.forEachIndexed { i, it ->
            val p = itemP(i, n, local, sceneMs)
            if (p <= 0f) return@forEachIndexed
            val y = r.top + 1.5f * u + head + rowH * i + rowH / 2
            val al = (255 * p * a).toInt()
            val dx = (1 - p) * 4 * u
            if (it.icon.isNotBlank()) emoji(c, it.icon, r.left + 7f * u + dx, y + 1.6f * u, 4.6f * u, al / 255f)
            labelP.textAlign = Paint.Align.LEFT
            labelP.color = ink
            labelP.alpha = al
            labelP.textSize = 4.1f * u
            val tx = r.left + (if (it.icon.isNotBlank()) 13f else 5f) * u + dx
            val lbl = wrap(it.label, labelP, r.right - 12f * u - tx, 1).firstOrNull().orEmpty()
            c.drawText(lbl, tx, y + 1.4f * u, labelP)
            val pc = Studio.appear(local, Studio.itemDelay(i, n, sceneMs) + 260, 260)
            if (pc > 0) {
                fill.color = green
                fill.alpha = (255 * pc * a).toInt()
                val ccx = r.right - 6f * u
                c.drawCircle(ccx, y, 2.6f * u * (0.6f + 0.4f * pc), fill)
                line.color = 0xFFFFFFFF.toInt()
                line.alpha = (255 * pc * a).toInt()
                line.strokeWidth = 0.6f * u
                c.drawPath(Path().apply {
                    moveTo(ccx - 1.2f * u, y)
                    lineTo(ccx - 0.3f * u, y + 1f * u)
                    lineTo(ccx + 1.3f * u, y - 1f * u)
                }, line)
            }
        }
        labelP.textAlign = Paint.Align.CENTER
    }

    private fun step(c: Canvas, n: Int, title: String, cx: Float, cy: Float, local: Long, a: Float) {
        val p = Studio.appear(local, 0, 380)
        val size = 16f * u
        val y = if (title.isBlank()) cy else cy - 6f * u
        c.save()
        val k = 0.7f + 0.3f * p
        c.scale(k, k, cx, y)
        fill.color = accent
        fill.alpha = (255 * p * a).toInt()
        c.drawRoundRect(RectF(cx - size / 2, y - size / 2, cx + size / 2, y + size / 2), size * 0.22f, size * 0.22f, fill)
        headP.textAlign = Paint.Align.CENTER
        headP.color = 0xFFFFFFFF.toInt()
        headP.alpha = (255 * p * a).toInt()
        headP.textSize = size * 0.62f
        c.drawText(if (n > 0) n.toString() else "•", cx, y + size * 0.22f, headP)
        c.restore()
        if (title.isNotBlank()) {
            textP.maskFilter = null
            textP.textAlign = Paint.Align.CENTER
            textP.color = ink
            textP.alpha = (255 * a * Studio.appear(local, 150, 300)).toInt()
            textP.textSize = 6.4f * u
            wrap(title, textP, w * 0.84f, 2).forEachIndexed { i, s -> c.drawText(s, cx, y + size * 0.5f + 9f * u + i * 7.6f * u, textP) }
            textP.textAlign = Paint.Align.LEFT
        }
    }

    private fun big(c: Canvas, item: SItem, cx: Float, cy: Float, local: Long, a: Float) {
        val p = Studio.appear(local, 0, 420)
        val pulse = 1f + 0.03f * sin(local / 380.0).toFloat()
        fill.color = accent
        fill.alpha = (46 * p * a).toInt()
        c.drawCircle(cx, cy - 4f * u, 22f * u * pulse * (0.7f + 0.3f * p), fill)
        emoji(c, item.icon.ifBlank { "💡" }, cx, cy + 3.5f * u, 22f * u * (0.8f + 0.2f * p), p * a)
        if (item.label.isNotBlank()) {
            labelP.color = ink
            labelP.alpha = (255 * a * Studio.appear(local, 200, 300)).toInt()
            labelP.textSize = 5f * u
            c.drawText(item.label, cx, cy + 27f * u, labelP)
        }
    }

    private fun stat(c: Canvas, sc: SScene, cx: Float, cy: Float, local: Long, a: Float) {
        val p = Studio.appear(local, 0, 300)
        val v = Studio.countUp(sc.title.ifBlank { sc.items.firstOrNull()?.icon ?: "" }, Studio.appear(local, 100, 900))
        headP.textAlign = Paint.Align.CENTER
        headP.color = accent
        headP.alpha = (255 * p * a).toInt()
        headP.textSize = 24f * u
        while (headP.measureText(v) > w * 0.86f && headP.textSize > 8 * u) headP.textSize *= 0.9f
        c.drawText(v, cx, cy + 4f * u, headP)
        val label = sc.items.firstOrNull()?.label.orEmpty()
        if (label.isNotBlank()) {
            labelP.color = muted
            labelP.alpha = (255 * a * Studio.appear(local, 250, 300)).toInt()
            labelP.textSize = 4.6f * u
            wrap(label, labelP, w * 0.8f, 2).forEachIndexed { i, s -> c.drawText(s, cx, cy + 14f * u + i * 5.6f * u, labelP) }
        }
    }

    /** A quote on a card; a marker sweeps over it line by line; the source below. */
    private fun quote(c: Canvas, sc: SScene, cx: Float, cy: Float, local: Long, a: Float) {
        val text = sc.title.ifBlank { sc.say }
        val cw = min(w * 0.86f, 86f * u)
        textP.maskFilter = null
        textP.textAlign = Paint.Align.LEFT
        textP.textSize = 5.4f * u
        val lines = wrap(text, textP, cw - 12f * u, 4)
        val lh = textP.textSize * 1.35f
        val src = sc.items.firstOrNull()?.label.orEmpty()
        val ch = 12f * u + lines.size * lh + (if (src.isNotBlank()) 7f * u else 0f)
        val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        val pa = Studio.appear(local, 0, 300)
        roundCard(c, r, 3f * u, pa * a)
        headP.textAlign = Paint.Align.LEFT
        headP.color = accent
        headP.alpha = (255 * pa * a).toInt()
        headP.textSize = 16f * u
        c.drawText("\u201C", r.left + 2.5f * u, r.top + 11f * u, headP)
        var y = r.top + 8f * u
        lines.forEachIndexed { li, s ->
            val lw = textP.measureText(s)
            val mk = StudioMotion.at(local, 350L + li * 260, 420) { StudioMotion.inOut(it) }
            if (mk > 0f) {
                fill.color = accent
                fill.alpha = (80 * a * mk).toInt()
                c.drawRoundRect(RectF(r.left + 6f * u - 0.8f * u, y + lh * 0.18f, r.left + 6f * u + lw * mk + 0.8f * u, y + lh * 0.98f), 0.8f * u, 0.8f * u, fill)
            }
            textP.color = ink
            textP.alpha = (255 * a * Studio.appear(local, 120L + li * 90, 300)).toInt()
            c.drawText(s, r.left + 6f * u, y + textP.textSize * 1.05f, textP)
            y += lh
        }
        if (src.isNotBlank()) {
            labelP.textAlign = Paint.Align.LEFT
            labelP.color = muted
            labelP.alpha = (255 * a * Studio.appear(local, 500, 300)).toInt()
            labelP.textSize = 3.6f * u
            c.drawText("\u2014 " + src, r.left + 6f * u, y + 5f * u, labelP)
            labelP.textAlign = Paint.Align.CENTER
        }
    }

    /** Bar chart: bars grow one after another, the biggest in full accent, numbers count up. */
    private fun chart(c: Canvas, items: List<SItem>, cx: Float, cy: Float, local: Long, sceneMs: Long, a: Float) {
        val bars = items.mapNotNull { it0 -> StudioDirector.number(it0.value)?.let { v -> it0 to v } }.take(6)
        if (bars.isEmpty()) return
        val top = bars.maxOf { it.second }.coerceAtLeast(0.0001f)
        val cw = min(w * 0.86f, 86f * u)
        val ch = 44f * u
        val n = bars.size
        val gap = 3f * u
        val bw = min((cw - gap * (n - 1)) / n, 16f * u)
        val total = n * bw + (n - 1) * gap
        val base = cy + ch / 2
        line.color = stroke
        line.alpha = (255 * a).toInt()
        line.strokeWidth = max(1f, 0.3f * u)
        c.drawLine(cx - cw / 2, base, cx + cw / 2, base, line)
        bars.forEachIndexed { i, (item, v) ->
            val p = StudioMotion.at(local, Studio.itemDelay(i, n, sceneMs), 560) { StudioMotion.backOut(it, 1.1f) }
            val x = cx - total / 2 + i * (bw + gap)
            val bh = ch * 0.82f * (v / top).coerceIn(0f, 1f) * p
            fill.color = accent
            fill.alpha = (255 * a * (if (v == top) 1f else 0.55f) * StudioMotion.clamp(p * 3)).toInt()
            if (bh > 0.5f) c.drawRoundRect(RectF(x, base - bh, x + bw, base), 1.4f * u, 1.4f * u, fill)
            if (p > 0f) {
                headP.textAlign = Paint.Align.CENTER
                headP.color = ink
                headP.alpha = (255 * a * StudioMotion.clamp(p)).toInt()
                headP.textSize = min(5.2f * u, bw * 0.5f)
                c.drawText(Studio.countUp(item.value, StudioMotion.clamp(p)), x + bw / 2, base - bh - 1.5f * u, headP)
                labelP.color = muted
                labelP.alpha = (255 * a * StudioMotion.clamp(p)).toInt()
                labelP.textSize = 3.3f * u
                val lbl = (item.icon + " " + item.label).trim()
                wrap(lbl, labelP, bw + gap * 0.8f, 1).firstOrNull()?.let { c.drawText(it, x + bw / 2, base + 5f * u, labelP) }
            }
        }
    }

    /** Two sides (before/after, myth/fact): the first card, then "VS", then the second one slides in. */
    private fun compare(c: Canvas, items: List<SItem>, cx: Float, cy: Float, local: Long, a: Float) {
        if (items.size < 2) return
        val cw = min(w * 0.88f, 90f * u)
        val gap = 6f * u
        val bw = (cw - gap) / 2
        val bh = min(bw * 1.1f, 44f * u)
        listOf(items[0], items[1]).forEachIndexed { k, item ->
            val p = StudioMotion.at(local, if (k == 0) 0L else 420L, 420) { StudioMotion.backOut(it, 1.3f) }
            if (p <= 0f) return@forEachIndexed
            val left = cx - cw / 2 + k * (bw + gap)
            val dx = (1 - p) * 8f * u * (if (k == 0) -1 else 1)
            val r = RectF(left + dx, cy - bh / 2, left + bw + dx, cy + bh / 2)
            roundCard(c, r, 3f * u, StudioMotion.clamp(p) * a, if (k == 1) accent else stroke)
            if (item.icon.isNotBlank()) {
                emoji(c, item.icon, r.centerX(), r.top + bh * 0.42f, bh * 0.3f, a * StudioMotion.clamp(p))
            }
            labelP.color = ink
            labelP.alpha = (255 * a * StudioMotion.clamp(p)).toInt()
            labelP.textSize = min(4.4f * u, bw * 0.11f)
            wrap(item.label, labelP, bw * 0.86f, 3).forEachIndexed { li, s ->
                c.drawText(s, r.centerX(), r.top + bh * (if (item.icon.isNotBlank()) 0.66f else 0.42f) + li * labelP.textSize * 1.2f, labelP)
            }
        }
        val pv = StudioMotion.at(local, 260, 360) { StudioMotion.spring(it) }
        if (pv > 0f) {
            val rr = 5.5f * u * pv
            fill.color = accent
            fill.alpha = (255 * a).toInt()
            c.drawCircle(cx, cy, rr, fill)
            headP.textAlign = Paint.Align.CENTER
            headP.color = 0xFFFFFFFF.toInt()
            headP.alpha = (255 * a).toInt()
            headP.textSize = 4.4f * u * pv
            c.drawText("VS", cx, cy + headP.textSize * 0.36f, headP)
        }
    }
}

/** Animated Noto emoji read once from their Lottie files. */
object StudioLottie {
    private val cache = HashMap<String, com.airbnb.lottie.LottieComposition>()

    /** File of emoji [e] in [dir] (may not exist yet). */
    fun file(dir: File, e: String) = File(dir, StudioLiveEmoji.code(e) + ".json")

    /** Marker of an emoji that has no animated version. */
    fun missing(dir: File, e: String) = File(dir, StudioLiveEmoji.code(e) + ".none")

    @Synchronized
    fun composition(dir: File, e: String): com.airbnb.lottie.LottieComposition? {
        val code = StudioLiveEmoji.code(e)
        cache[code]?.let { return it }
        val f = file(dir, e)
        if (!f.exists() || f.length() == 0L) return null
        val comp = runCatching { com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(f.readText(), "noto_$code").value }.getOrNull() ?: return null
        cache[code] = comp
        return comp
    }
}

/** B-roll photos read from disk once and kept for the frames that follow (a few at a time). */
object StudioPhotos {
    private val cache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)

    @Synchronized
    fun get(path: String, maxSide: Int): Bitmap? {
        val key = "$path@$maxSide"
        cache[key]?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache[key] = b
        while (cache.size > 6) cache.remove(cache.keys.first())
        return b
    }
}

/** Audio: decoding any file Android can read, and encoding the final AAC track. */
object StudioAudio {
    private const val TIMEOUT = 10_000L

    class Pcm(val data: ShortArray, val rate: Int) {
        val ms: Long get() = Studio.durationMs(data.size, rate)
    }

    fun decode(file: File, maxMs: Long = 15 * 60_000L): Pcm = decodeWith(maxMs) { it.setDataSource(file.absolutePath) }

    fun decode(ctx: Context, uri: Uri, maxMs: Long = 15 * 60_000L): Pcm = decodeWith(maxMs) { it.setDataSource(ctx, uri, null) }

    private fun decodeWith(maxMs: Long, open: (MediaExtractor) -> Unit): Pcm {
        val ex = MediaExtractor()
        try {
            open(ex)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("no audio track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()
            val out = ShortBuilder()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            try {
                while (true) {
                    if (!inDone) {
                        val i = dec.dequeueInputBuffer(TIMEOUT)
                        if (i >= 0) {
                            val buf = dec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > maxMs * 1000) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, TIMEOUT)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val b = dec.getOutputBuffer(o)!!
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            out.add(b.slice().order(ByteOrder.nativeOrder()).asShortBuffer())
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { dec.stop() }
                dec.release()
            }
            return Pcm(Studio.downmix(out.build(), channels), rate)
        } finally {
            ex.release()
        }
    }

    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** Mono PCM → AAC-LC samples and the track format for the muxer. */
    fun encodeAac(pcm: ShortArray, rate: Int, cancelled: () -> Boolean, progress: (Float) -> Unit): Pair<MediaFormat, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = mutableListOf<Sample>()
        var outFmt: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inDone = false
        try {
            while (true) {
                if (cancelled()) throw CancellationException()
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!
                        buf.clear()
                        val n = min(buf.capacity() / 2, pcm.size - pos)
                        val pts = pos * 1_000_000L / rate
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(i, 0, n * 2, pts, 0)
                            pos += n
                            progress(pos.toFloat() / max(1, pcm.size))
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, TIMEOUT)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = enc.outputFormat
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = enc.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        b.position(info.offset)
                        b.get(bytes)
                        out += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
        }
        return (outFmt ?: error("AAC encoder gave no format")) to out
    }

    /** Growable short array (decoded audio of unknown length). */
    private class ShortBuilder {
        private var a = ShortArray(1 shl 16)
        private var n = 0
        fun add(b: java.nio.ShortBuffer) {
            val k = b.remaining()
            if (n + k > a.size) a = a.copyOf(max(a.size * 2, n + k))
            b.get(a, n, k)
            n += k
        }
        fun build(): ShortArray = a.copyOf(n)
    }
}

/** Renders a [StudioPlan] and a voice track into an MP4 (H.264 + AAC) with the platform encoders. */
object StudioVideo {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT = 10_000L

    /** [progress] gets a message with "done/total" (drives the determinate progress bars). */
    fun render(
        plan: StudioPlan, voice: StudioAudio.Pcm, out: File, w: Int, h: Int, fps: Int,
        cancelled: () -> Boolean, background: StudioBg? = null, progress: (String) -> Unit
    ) {
        out.parentFile?.mkdirs()
        progress(t("Звук: кодирование 0/100", "Audio: encoding 0/100"))
        val (audioFmt, audio) = StudioAudio.encodeAac(voice.data, voice.rate, cancelled) { f ->
            progress(t("Звук: кодирование ${(f * 100).toInt()}/100", "Audio: encoding ${(f * 100).toInt()}/100"))
        }
        val frames = Studio.frameCount(plan.totalMs, fps)
        val fmt = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, Studio.bitrate(w, h, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MIME)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var ok = false
        var started = false
        var vTrack = -1
        var aTrack = -1
        var aNext = 0
        val info = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()

        fun writeAudio(untilUs: Long) {
            while (started && aNext < audio.size && audio[aNext].ptsUs <= untilUs) {
                val s = audio[aNext++]
                aInfo.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), aInfo)
            }
        }

        /** Moves encoded frames to the file; with [end] waits for the end of stream. */
        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (end) TIMEOUT else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++idle > 300) error("video encoder stalled")
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        vTrack = muxer.addTrack(enc.outputFormat)
                        aTrack = muxer.addTrack(audioFmt)
                        muxer.start()
                        started = true
                    }
                    o >= 0 -> {
                        val b = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            writeAudio(info.presentationTimeUs)
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            muxer.writeSampleData(vTrack, b, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val video = if (background != null && plan.style == StudioStyle.VIDEO) background.open(w, h) else null
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val painter = StudioPainter(w, h, plan)
            val argb = IntArray(w * h)
            val frameArgb = if (video != null) IntArray(w * h) { 0xFF000000.toInt() } else null
            val videoBmp = if (video != null) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
            val videoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val y = ByteArray(w * h)
            val u = ByteArray(w * h / 4)
            val v = ByteArray(w * h / 4)
            val size = w * h * 3 / 2
            for (f in 0 until frames) {
                if (cancelled()) throw CancellationException()
                val pts = Studio.frameTimeUs(f, fps)
                if (video == null && plan.style == StudioStyle.VIDEO) canvas.drawColor(0xFF000000.toInt())
                if (video != null && frameArgb != null && videoBmp != null) {
                    val need = painter.needsVideo(pts / 1000)
                    if (video.advance(pts, frameArgb, need)) videoBmp.setPixels(frameArgb, 0, w, 0, 0, w, h)
                    if (need) {
                        canvas.drawColor(0xFF000000.toInt())
                        painter.drawVideo(canvas, videoBmp, pts / 1000, videoPaint)
                    }
                }
                painter.draw(canvas, pts / 1000)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                Studio.argbToYuv420(argb, w, h, y, u, v)
                var idx: Int
                while (true) {
                    idx = enc.dequeueInputBuffer(TIMEOUT)
                    if (idx >= 0) break
                    drain(false)
                    if (cancelled()) throw CancellationException()
                }
                val img = enc.getInputImage(idx)
                if (img != null) writeImage(img, w, h, y, u, v) else writeNv12(enc.getInputBuffer(idx)!!, w, h, y, u, v)
                enc.queueInputBuffer(idx, 0, size, pts, 0)
                drain(false)
                if (f % 15 == 0 || f == frames - 1) progress(t("Кадры ${f + 1}/$frames", "Frames ${f + 1}/$frames"))
            }
            while (true) {
                val idx = enc.dequeueInputBuffer(TIMEOUT)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, Studio.frameTimeUs(frames, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            writeAudio(Long.MAX_VALUE)
            bmp.recycle()
            ok = true
        } finally {
            video?.let { v -> runCatching { v.close() } }
            runCatching { enc.stop() }
            enc.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!ok) out.delete()
        }
    }

    private fun writeImage(img: Image, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        val planes = img.planes
        fun plane(i: Int, src: ByteArray, pw: Int, ph: Int) {
            val p = planes[i]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            for (row in 0 until ph) {
                if (ps == 1) {
                    buf.position(row * rs)
                    buf.put(src, row * pw, pw)
                } else {
                    val base = row * rs
                    val s = row * pw
                    for (col in 0 until pw) buf.put(base + col * ps, src[s + col])
                }
            }
        }
        plane(0, y, w, h)
        plane(1, u, w / 2, h / 2)
        plane(2, v, w / 2, h / 2)
    }

    /** Fallback when the encoder gives no Image: semi-planar NV12, the most common layout. */
    private fun writeNv12(buf: ByteBuffer, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        buf.clear()
        buf.put(y)
        for (i in u.indices) {
            buf.put(u[i])
            buf.put(v[i])
        }
    }

    /** A still frame for the preview on screen; [background] is the speaker's video for the VIDEO style. */
    fun preview(plan: StudioPlan, tMs: Long, w: Int, h: Int, background: StudioBg? = null): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val painter = StudioPainter(w, h, plan)
        val at = background?.frameAt(tMs)
        if (plan.style == StudioStyle.VIDEO) c.drawColor(0xFF000000.toInt())
        if (at != null && painter.needsVideo(tMs)) {
            c.drawColor(0xFF000000.toInt())
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(at.first.absolutePath)
                r.getFrameAtTime(at.second * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                    val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val s = max(w.toFloat() / f.width, h.toFloat() / f.height)
                    val sw = w / s
                    val sh = h / s
                    val src = android.graphics.Rect(((f.width - sw) / 2).toInt(), ((f.height - sh) / 2).toInt(), ((f.width + sw) / 2).toInt(), ((f.height + sh) / 2).toInt())
                    Canvas(crop).drawBitmap(f, src, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                    f.recycle()
                    painter.drawVideo(c, crop, tMs, Paint(Paint.FILTER_BITMAP_FLAG))
                    crop.recycle()
                }
            } catch (e: Exception) {
                L.w("studio", "preview frame: ${e.message}")
            } finally {
                runCatching { r.release() }
            }
        }
        painter.draw(c, tMs)
        return bmp
    }
}

/** Something that fills [out] with the background picture for an output time (frames of one video, clips, cut video). */
interface StudioFrameSource : java.io.Closeable {
    /** Moves to [tUs]; with [need] the picture goes into [out]. Returns true when [out] changed. */
    fun advance(tUs: Long, out: IntArray, need: Boolean): Boolean
}

/**
 * Background of a VIDEO-style video: the speaker's own video (optionally with pauses cut out, [cuts]),
 * or background [clips] that play one after another and repeat for the whole voice-over.
 */
class StudioBg(val talking: File?, val cuts: List<Span>?, val clips: List<Pair<File, Long>>) {
    val empty: Boolean get() = talking == null && clips.isEmpty()

    /** File and time inside it for output time [tMs] (for the preview). */
    fun frameAt(tMs: Long): Pair<File, Long>? {
        talking?.let { return it to Studio.sourceTime(tMs, cuts) }
        if (clips.isEmpty()) return null
        val (i, local) = Studio.clipAt(tMs, clips.map { it.second })
        return clips[i].first to local
    }

    fun open(w: Int, h: Int): StudioFrameSource? = when {
        talking != null -> {
            val f = StudioFrames(talking, w, h)
            if (cuts.isNullOrEmpty()) f else object : StudioFrameSource {
                override fun advance(tUs: Long, out: IntArray, need: Boolean) = f.advance(Studio.sourceTime(tUs / 1000, cuts) * 1000, out, need)
                override fun close() = f.close()
            }
        }
        clips.isNotEmpty() -> StudioClipFrames(clips, w, h)
        else -> null
    }
}

/** Background clips in a loop: a new decoder opens when the next clip (or the next round) starts. */
class StudioClipFrames(private val clips: List<Pair<File, Long>>, private val w: Int, private val h: Int) : StudioFrameSource {
    private var cur: StudioFrames? = null
    private var curIdx = -1
    private var lastLocal = Long.MAX_VALUE

    override fun advance(tUs: Long, out: IntArray, need: Boolean): Boolean {
        val (i, localMs) = Studio.clipAt(tUs / 1000, clips.map { it.second })
        val local = localMs * 1000
        if (i != curIdx || local < lastLocal && cur != null && local + 50_000 < lastLocal) {
            cur?.close()
            cur = StudioFrames(clips[i].first, w, h)
            curIdx = i
        }
        lastLocal = local
        return cur?.advance(local, out, need) ?: false
    }

    override fun close() {
        cur?.close()
        cur = null
    }
}

/**
 * Frames of the speaker's video, decoded in order and centre-cropped to the output size
 * (rotation of phone videos is taken into account). Pixels are converted only when they are shown.
 */
class StudioFrames(private val file: File, private val w: Int, private val h: Int) : StudioFrameSource {
    private val ex = MediaExtractor()
    // fallback for decoders that give no YUV image: slower frame-by-frame reading through MediaMetadataRetriever
    private var retriever: android.media.MediaMetadataRetriever? = null
    private var useRetriever = false
    private var lastFetchUs = Long.MIN_VALUE
    private var scratch: Bitmap? = null
    private val dec: MediaCodec
    private val rotation: Int
    private val info = MediaCodec.BufferInfo()
    private var inEos = false
    private var outEos = false
    private var pendIdx = -1
    private var pendPts = 0L
    private var shown = false
    private var yA = ByteArray(0)
    private var uA = ByteArray(0)
    private var vA = ByteArray(0)
    private var axes: Pair<IntArray, IntArray>? = null
    private var axesKey = ""

    init {
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IllegalStateException(t("В файле нет видео", "The file has no video"))
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        rotation = (if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else retrieverRotation(file)).let { ((it % 360) + 360) % 360 }
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
    }

    private fun retrieverRotation(file: File): Int {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { r.release() }
        }
    }

    /** Dequeues the next decoded frame into pendIdx; false at the end of the video. */
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
            val o = dec.dequeueOutputBuffer(info, 5000)
            if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    dec.releaseOutputBuffer(o, false)
                    outEos = true
                    return false
                }
                pendIdx = o
                pendPts = info.presentationTimeUs
                return true
            }
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER && ++idle > 400) {
                outEos = true
                return false
            }
        }
    }

    /**
     * Moves to the last frame at or before [tUs]; with [need] its pixels go into [out]
     * (after the video ends, the last shown frame stays). Returns true when [out] changed.
     */
    override fun advance(tUs: Long, out: IntArray, need: Boolean): Boolean {
        if (useRetriever) return need && fallback(tUs, out)
        var cand = -1
        while (!outEos) {
            if (pendIdx < 0 && !next()) break
            if (pendPts <= tUs) {
                if (cand >= 0) dec.releaseOutputBuffer(cand, false)
                cand = pendIdx
                pendIdx = -1
            } else break
        }
        if (cand < 0) {
            // the first frame may start a few ms after zero: show it instead of black
            if (need && !shown && pendIdx >= 0) dec.getOutputImage(pendIdx)?.let { convert(it, out); shown = true; return true }
            return false
        }
        var changed = false
        if (need) {
            val img = dec.getOutputImage(cand)
            if (img == null) {
                L.w("studio", "decoder gives no image, falling back to MediaMetadataRetriever")
                dec.releaseOutputBuffer(cand, false)
                useRetriever = true
                return fallback(tUs, out)
            }
            convert(img, out)
            shown = true
            changed = true
        }
        dec.releaseOutputBuffer(cand, false)
        return changed
    }

    /** About 15 new pictures per second from the retriever (each one is a separate seek). */
    private fun fallback(tUs: Long, out: IntArray): Boolean {
        if (lastFetchUs != Long.MIN_VALUE && tUs - lastFetchUs < 66_000) return false
        lastFetchUs = tUs
        val r = retriever ?: android.media.MediaMetadataRetriever().also {
            it.setDataSource(file.absolutePath)
            retriever = it
        }
        val f = r.getFrameAtTime(tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: return false
        val bmp = scratch ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch = it }
        val s = max(w.toFloat() / f.width, h.toFloat() / f.height)
        val sw = w / s
        val sh = h / s
        val src = android.graphics.Rect(((f.width - sw) / 2).toInt(), ((f.height - sh) / 2).toInt(), ((f.width + sw) / 2).toInt(), ((f.height + sh) / 2).toInt())
        Canvas(bmp).drawBitmap(f, src, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        f.recycle()
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        shown = true
        return true
    }

    private fun copy(p: Image.Plane, old: ByteArray): ByteArray {
        val b = p.buffer.duplicate()
        b.rewind()
        val arr = if (old.size == b.remaining()) old else ByteArray(b.remaining())
        b.get(arr)
        return arr
    }

    private fun convert(img: Image, out: IntArray) {
        val crop = img.cropRect
        val rw = crop.width()
        val rh = crop.height()
        val (dw, dh) = Studio.displaySize(rw, rh, rotation)
        val key = "$rw:$rh:$rotation"
        if (key != axesKey) {
            axes = Studio.cropAxes(w, h, dw, dh)
            axesKey = key
        }
        val (xs, ys) = axes!!
        val pl = img.planes
        yA = copy(pl[0], yA)
        uA = copy(pl[1], uA)
        vA = copy(pl[2], vA)
        val rsY = pl[0].rowStride
        val psY = pl[0].pixelStride
        val rsU = pl[1].rowStride
        val psU = pl[1].pixelStride
        val rsV = pl[2].rowStride
        val psV = pl[2].pixelStride
        val ox = crop.left
        val oy = crop.top
        for (y in 0 until h) {
            val v = ys[y]
            val row = y * w
            for (x in 0 until w) {
                val u = xs[x]
                val rx = Studio.rawX(u, v, rotation, rw) + ox
                val ry = Studio.rawY(u, v, rotation, rh) + oy
                val yy = yA.getOrElse(ry * rsY + rx * psY) { 16 }.toInt() and 0xFF
                val cu = uA.getOrElse((ry / 2) * rsU + (rx / 2) * psU) { -128 }.toInt() and 0xFF
                val cv = vA.getOrElse((ry / 2) * rsV + (rx / 2) * psV) { -128 }.toInt() and 0xFF
                out[row + x] = Studio.yuvToArgb(yy, cu, cv)
            }
        }
    }

    override fun close() {
        retriever?.let { r -> runCatching { r.release() } }
        scratch?.recycle()
        if (pendIdx >= 0) runCatching { dec.releaseOutputBuffer(pendIdx, false) }
        runCatching { dec.stop() }
        dec.release()
        ex.release()
    }
}

/** Microphone recording into memory (16-bit mono PCM). */
class StudioRecorder(private val onLevel: (Float, Long) -> Unit) {
    val rate = 44_100
    @Volatile private var running = false
    private var thread: Thread? = null
    private val chunks = mutableListOf<ShortArray>()
    private var samples = 0

    @Volatile var failure: Throwable? = null
        private set

    val recording: Boolean get() = running

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException(t("Микрофон занят другим приложением", "The microphone is busy"))
        }
        synchronized(chunks) {
            chunks.clear()
            samples = 0
        }
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(rate / 10)
            var last = 0L
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(chunks) {
                        chunks += buf.copyOf(n)
                        samples += n
                    }
                    val now = System.currentTimeMillis()
                    if (now - last > 80) {
                        last = now
                        val lvl = Studio.frameRms(buf.copyOf(n), rate, 100).maxOrNull() ?: 0f
                        val ms = Studio.durationMs(samples, rate)
                        Lumen.ui { onLevel(lvl, ms) }
                    }
                    if (samples > rate * 60 * MAX_MIN) running = false
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }.apply { name = "studio-mic"; start() }
    }

    /** Stops and returns the take. */
    fun stop(): ShortArray {
        running = false
        thread?.join(2000)
        thread = null
        return synchronized(chunks) {
            val out = ShortArray(samples)
            var p = 0
            for (c in chunks) {
                c.copyInto(out, p)
                p += c.size
            }
            out
        }
    }

    companion object {
        const val MAX_MIN = 10
    }
}

/**
 * Speech recognition of a recorded take, piece by piece (Android 13+: the recogniser reads audio from a pipe).
 * Prefers the on-device recogniser. All callbacks come on the main thread.
 */
object StudioRecognizer {
    const val RATE = 16_000

    fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(ctx)

    fun recognize(
        ctx: Context, pcm: ShortArray, rate: Int, pieces: List<Span>, lang: String,
        progress: (Int, Int) -> Unit, cancelled: () -> Boolean, done: (List<Pair<Span, String>>) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            done(emptyList())
            return
        }
        val main = Handler(Looper.getMainLooper())
        val results = mutableListOf<Pair<Span, String>>()
        fun next(i: Int) {
            if (i >= pieces.size || cancelled()) {
                done(results)
                return
            }
            progress(i, pieces.size)
            val piece = pieces[i]
            val from = (piece.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
            val to = (piece.endMs * rate / 1000).toInt().coerceIn(from, pcm.size)
            // a little silence around the piece helps the recogniser to start and stop
            val slice = Studio.silence(300, RATE) + Studio.resample(pcm.copyOfRange(from, to), rate, RATE) + Studio.silence(600, RATE)
            val onDevice = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
            val sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            val pipe = ParcelFileDescriptor.createPipe()
            var finished = false
            fun finish(text: String?) {
                if (finished) return
                finished = true
                runCatching { sr.destroy() }
                runCatching { pipe[0].close() }
                if (!text.isNullOrBlank()) results += piece to text
                L.i("studio", "recognised piece ${i + 1}/${pieces.size}: ${text?.length ?: -1} chars")
                main.post { next(i + 1) }
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: android.os.Bundle?) =
                    finish(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(error: Int) {
                    L.w("studio", "recogniser error $error on piece ${i + 1}")
                    finish(null)
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            try {
                sr.startListening(intent)
            } catch (e: Exception) {
                L.w("studio", "recogniser start: ${e.message}")
                runCatching { pipe[1].close() }
                finish(null)
                return
            }
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { os ->
                        val bytes = ByteBuffer.allocate(slice.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.asShortBuffer().put(slice)
                        os.write(bytes.array())
                    }
                } catch (e: Exception) {
                    L.w("studio", "pipe: ${e.message}")
                }
            }.start()
            main.postDelayed({ finish(null) }, 15_000 + piece.ms * 2)
        }
        next(0)
    }
}

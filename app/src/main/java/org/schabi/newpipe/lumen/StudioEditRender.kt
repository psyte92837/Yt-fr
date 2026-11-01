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

    /** Draws every block of [shown] (bottom first) at [tMs]. */
    fun draw(c: Canvas, shown: List<EBlock>, tMs: Long) {
        for (b in shown) runCatching { block(c, b, tMs) }.onFailure { L.w("studio", "edit block ${b.kind}: ${it.message}") }
    }

    private fun block(c: Canvas, b: EBlock, tMs: Long) {
        val pose = StudioEdit.pose(b, tMs)
        val alpha = pose.alpha * b.opacity
        if (alpha <= 0.01f) return
        val (hw, hh) = StudioEdit.halfSize(b, w.toFloat(), h.toFloat())
        if (hw < 1f || hh < 1f) return
        c.save()
        c.translate(b.x * w, (b.y + pose.dy) * h)
        if (b.rot != 0f) c.rotate(b.rot)
        if (pose.scale != 1f) c.scale(pose.scale, pose.scale)
        val box = RectF(-hw, -hh, hw, hh)
        val layered = alpha < 0.995f
        if (layered) c.saveLayerAlpha(RectF(box).apply { inset(-hw * 0.25f - 8f, -hh * 0.25f - 8f) }, (alpha * 255).toInt().coerceIn(0, 255))
        when (b.kind) {
            "image" -> picture(c, b, box)
            "scheme" -> scheme(c, b, box, tMs)
            "text" -> caption(c, b, box)
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

    private fun style(id: String): SchemeStyle =
        (if (id.isBlank() || id == SchemeStyles.AUTO) null else SchemeStyles.find(styleDir, id))
            ?: SchemeStyle(SchemeStyles.AUTO, "", card = 0xFFFFFFFF.toInt(), ink = 0xFF14161B.toInt(), muted = 0xFF6B7280.toInt(), accent = accent,
                line = 0xFFE2E5EC.toInt(), bg = 0xF2F4F6FA.toInt(), fill = "solid", radius = 0.18f, border = 1f, shadow = true)

    private fun painter(id: String): StudioPainter = painters.getOrPut(id) {
        val st = style(id)
        StudioPainter(w, h, StudioPlan(SScript("", emptyList()), emptyList(), emptyList(), emptyList(), 0L, StudioStyle.VIDEO, accent, schemeStyle = st)).apply { live = false }
    }

    private fun light(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255 > 0.5

    private fun scheme(c: Canvas, b: EBlock, box: RectF, tMs: Long) {
        val s = b.scheme ?: return placeholder(c, box, "🧩")
        val p = painter(b.look)
        val st = p.schemeLook()
        // a panel behind the scheme so its text reads on any video: light under dark text, dark under light text
        val m = min(box.width(), box.height())
        val panel = if (light(st.ink)) (st.bg.takeIf { !light(it) } ?: 0xE6101318.toInt()) else 0xF2F4F6FA.toInt()
        fill.color = panel
        fill.alpha = (Color.alpha(panel) * Studio.appear(tMs, b.startMs, 250)).toInt()
        fill.setShadowLayer(m * 0.03f, 0f, m * 0.01f, 0x55000000)
        c.drawRoundRect(box, m * 0.06f, m * 0.06f, fill)
        fill.clearShadowLayer()
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

    private fun caption(c: Canvas, b: EBlock, box: RectF) {
        if (b.text.isBlank()) return
        val m = min(box.width(), box.height())
        val pad = min(m * 0.22f, box.width() * 0.06f)
        val (size, lines) = fit(b.text.trim(), box.width() - pad * 2, box.height() - pad * 2)
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
        textP.textSize = size
        val step = size * 1.18f
        val fm = textP.fontMetrics
        var y = box.centerY() - step * lines.size / 2 + step / 2 - (fm.ascent + fm.descent) / 2
        for (l in lines) {
            if (b.look == "shadow") {
                textP.style = Paint.Style.STROKE
                textP.strokeWidth = size * 0.14f
                textP.strokeJoin = Paint.Join.ROUND
                textP.color = 0xE6000000.toInt()
                c.drawText(l, box.centerX(), y, textP)
                textP.style = Paint.Style.FILL
            }
            textP.color = ink
            c.drawText(l, box.centerX(), y, textP)
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
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT = 10_000L

    /** Displayed size, length and frame rate of a video file. */
    data class Probe(val w: Int, val h: Int, val durMs: Long, val fps: Int)

    fun probe(f: File): Probe {
        val r = MediaMetadataRetriever()
        val (w, h, dur) = try {
            r.setDataSource(f.absolutePath)
            val rw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val (dw, dh) = Studio.displaySize(rw, rh, ((rot % 360) + 360) % 360)
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
        return Probe(w, h, dur, fps)
    }

    /**
     * Writes the video of project [p] (folder [dir]) with its blocks into [out]; [progress] gets "done/total".
     * Worker thread; throws CancellationException when [cancelled].
     */
    fun render(p: EProject, dir: File, words: List<SWord>, accent: Int, styleDir: File, out: File, cancelled: () -> Boolean, progress: (String) -> Unit) {
        val base = File(dir, StudioEditStore.BASE)
        val pr = probe(base)
        val (w, h) = StudioEdit.outSize(pr.w, pr.h)
        val fps = pr.fps
        val totalMs = pr.durMs
        val frames = Studio.frameCount(totalMs, fps)
        out.parentFile?.mkdirs()
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
        // the sound goes over untouched: samples of the original audio track
        val aex = MediaExtractor()
        aex.setDataSource(base.absolutePath)
        val aIdx = (0 until aex.trackCount).firstOrNull { aex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val aFmt = aIdx?.let {
            aex.selectTrack(it)
            aex.getTrackFormat(it)
        }
        val aBuf = ByteBuffer.allocateDirect(max(256 * 1024, aFmt?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0))
        var aDone = aFmt == null
        var ok = false
        var started = false
        var vTrack = -1
        var aTrack = -1
        val info = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()

        fun writeAudio(untilUs: Long) {
            if (!started || aTrack < 0) return
            while (!aDone) {
                val pts = aex.sampleTime
                if (pts < 0 || pts > totalMs * 1000) {
                    aDone = true
                    break
                }
                if (pts > untilUs) break
                aBuf.clear()
                val n = aex.readSampleData(aBuf, 0)
                if (n < 0) {
                    aDone = true
                    break
                }
                aInfo.set(0, n, pts, if (aex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(aTrack, aBuf, aInfo)
                aex.advance()
            }
        }

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
                        aTrack = aFmt?.let { f ->
                            runCatching { muxer.addTrack(f) }.getOrElse {
                                L.w("studio", "edit export: audio ${f.getString(MediaFormat.KEY_MIME)} cannot be copied: ${it.message}")
                                -1
                            }
                        } ?: -1
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

        var src: StudioFrames? = null
        val painter = StudioEditPainter(w, h, dir, words, accent, styleDir)
        try {
            src = StudioFrames(base, w, h)
            val frame = IntArray(w * h) { 0xFF000000.toInt() }
            val mixed = IntArray(w * h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val y = ByteArray(w * h)
            val u = ByteArray(w * h / 4)
            val v = ByteArray(w * h / 4)
            val size = w * h * 3 / 2
            for (f in 0 until frames) {
                if (cancelled()) throw CancellationException()
                val pts = Studio.frameTimeUs(f, fps)
                val tMs = pts / 1000
                src.advance(pts, frame, true)
                val shown = StudioEdit.active(p.blocks, tMs)
                if (shown.isEmpty()) Studio.argbToYuv420(frame, w, h, y, u, v)
                else {
                    // drawn on a copy: the next frame may keep this picture of the video when the source has fewer frames
                    bmp.setPixels(frame, 0, w, 0, 0, w, h)
                    painter.draw(canvas, shown, tMs)
                    bmp.getPixels(mixed, 0, w, 0, 0, w, h)
                    Studio.argbToYuv420(mixed, w, h, y, u, v)
                }
                var idx: Int
                while (true) {
                    idx = enc.dequeueInputBuffer(TIMEOUT)
                    if (idx >= 0) break
                    drain(false)
                    if (cancelled()) throw CancellationException()
                }
                val image = enc.getInputImage(idx)
                if (image != null) StudioVideo.writeImage(image, w, h, y, u, v) else StudioVideo.writeNv12(enc.getInputBuffer(idx)!!, w, h, y, u, v)
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
            painter.close()
            src?.let { s -> runCatching { s.close() } }
            runCatching { aex.release() }
            runCatching { enc.stop() }
            enc.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!ok) out.delete()
        }
    }
}

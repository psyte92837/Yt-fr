/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Downloads a marked piece of a YouTube video for the Cuts task: only the fragments of the video-only DASH stream
 * that cover the piece (found through the stream index), then cuts it into a plain MP4 without re-encoding.
 * Requests are made like the NewPipe player does (POST, range in the URL).
 */
object StudioCutsLoad {
    private val POST_BODY = byteArrayOf(0x78, 0)
    private var rn = 0

    /** Video-only MP4 (H.264) progressive stream of the highest quality up to [maxHeight]. */
    fun pickStream(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val ok = streams.filter {
            it.isVideoOnly && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == NpFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc")
        }
        return ok.filter { it.height in 1..maxHeight }.maxByOrNull { it.height } ?: ok.minByOrNull { it.height }
    }

    /** A quick look at a found video: the stream to play ("" when there is none) and frames of its storyboard with their times. */
    class Look(val stream: String, val frames: List<Pair<Long, android.graphics.Bitmap>>)

    /**
     * Quick look at a found video: the lowest quality stream and [want] frames of the YouTube storyboard spread over
     * the video (a frameset with frames at least 100 px wide when there is one; only the pages that hold them are
     * downloaded). Network; worker thread.
     */
    fun look(url: String, want: Int = 10): Look {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        fun low(list: List<VideoStream>) = list.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }
        val stream = (low(info.videoStreams.filter { !it.isVideoOnly }) ?: low(info.videoOnlyStreams))?.content.orEmpty()
        val sets = runCatching { info.previewFrames }.getOrNull().orEmpty().filter { it.urls.isNotEmpty() && it.frameWidth > 0 && it.frameHeight > 0 }
        val fs = sets.filter { it.frameWidth >= 100 }.minByOrNull { it.frameWidth * it.frameHeight } ?: sets.maxByOrNull { it.frameWidth * it.frameHeight }
        val frames = mutableListOf<Pair<Long, android.graphics.Bitmap>>()
        if (fs != null) {
            val pages = HashMap<Int, android.graphics.Bitmap?>()
            for (k in StudioMix.stripFrames(fs.totalCount, want)) {
                val (page, left, top) = StudioMix.frameAt(k, fs.framesPerPageX, fs.framesPerPageY, fs.frameWidth, fs.frameHeight)
                val src = pages.getOrPut(page) { fs.urls.getOrNull(page)?.let { u -> runCatching { image(u) }.getOrNull() } } ?: continue
                val x = left.coerceIn(0, maxOf(0, src.width - fs.frameWidth))
                val y = top.coerceIn(0, maxOf(0, src.height - fs.frameHeight))
                val w = minOf(fs.frameWidth, src.width - x)
                val h = minOf(fs.frameHeight, src.height - y)
                if (w <= 0 || h <= 0) continue
                frames += k.toLong() * fs.durationPerFrame to android.graphics.Bitmap.createBitmap(src, x, y, w, h)
            }
        }
        if (stream.isEmpty() && frames.isEmpty()) error(t("Нет потока и кадров для просмотра", "No stream or frames to look at"))
        return Look(stream, frames)
    }

    private fun image(url: String): android.graphics.Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            return c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }

    /** Length of a YouTube video in ms (0 when not known). Network; worker thread. */
    fun durationMs(url: String): Long = StreamInfo.getInfo(ServiceList.YouTube, url).duration.coerceAtLeast(0) * 1000

    /** Downloads and cuts [c] into [dir]; returns it with the file, the lead and the length. */
    fun fetch(c: SClip, dir: File, maxHeight: Int, cancelled: () -> Boolean, progress: (String) -> Unit): SClip {
        progress(t("${c.id}: ссылка на поток…", "${c.id}: getting the stream…"))
        val info = StreamInfo.getInfo(ServiceList.YouTube, c.url)
        if (cancelled()) throw CancellationException()
        val stream = pickStream(info.videoOnlyStreams, maxHeight)
            ?: error(t("Нет видеопотока MP4/H.264 для «${info.name}»", "No MP4/H.264 video stream for \"${info.name}\""))
        val url = stream.content
        val fromUs = c.fromMs * 1000
        val toUs = c.toMs * 1000
        dir.mkdirs()
        val part = File(dir, c.id + ".part.mp4")
        var segStartUs = 0L
        val segs = if (stream.initEnd > 0 && stream.indexEnd > stream.indexStart) {
            runCatching { StudioCuts.parseSidx(get(url, stream.indexStart.toLong(), stream.indexEnd.toLong()), stream.indexStart.toLong()) }
                .onFailure { L.w("studio", "cuts index: ${it.message}") }.getOrNull()
        } else {
            null
        }
        part.outputStream().use { o ->
            if (!segs.isNullOrEmpty()) {
                val pick = StudioCuts.pickSegs(segs, fromUs, toUs)
                if (pick.isEmpty()) error(t("${c.id}: отрезок за пределами видео", "${c.id}: the piece is outside the video"))
                segStartUs = pick.first().startUs
                o.write(get(url, stream.initStart.toLong(), stream.initEnd.toLong()))
                copy(url, pick.first().offset, pick.last().offset + pick.last().size - 1, o, cancelled) { p ->
                    progress(t("${c.id}: загрузка $p/100", "${c.id}: downloading $p/100"))
                }
            } else {
                // no index: the whole stream, if it is not too big
                val len = stream.itagItem?.contentLength ?: -1L
                if (len > StudioCuts.MAX_FULL_BYTES) error(t("${c.id}: видео слишком большое для загрузки целиком", "${c.id}: the video is too big to download whole"))
                copy(url, 0, if (len > 0) len - 1 else -1, o, cancelled) { p ->
                    progress(t("${c.id}: загрузка $p/100", "${c.id}: downloading $p/100"))
                }
            }
        }
        progress(t("${c.id}: нарезка…", "${c.id}: cutting…"))
        val out = File(dir, c.id + ".mp4")
        try {
            val (leadUs, lenUs) = cut(part, out, fromUs, toUs, segStartUs)
            if (lenUs <= 0) error(t("${c.id}: в отрезке нет кадров", "${c.id}: no frames in the piece"))
            return c.copy(file = out.name, leadMs = leadUs / 1000, lenMs = lenUs / 1000, title = c.title.ifBlank { info.name.orEmpty() },
                durMs = if (info.duration > 0) info.duration * 1000 else c.durMs)
        } finally {
            part.delete()
        }
    }

    internal fun open(url: String, from: Long, to: Long): HttpURLConnection {
        val range = if (to >= from && to >= 0) "&range=$from-$to" else if (from > 0) "&range=$from-" else ""
        val c = URL(url + range + "&rn=" + (++rn)).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            c.setRequestProperty("Origin", "https://www.youtube.com")
            c.setRequestProperty("Referer", "https://www.youtube.com")
        }
        c.setRequestProperty("User-Agent",
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else DownloaderImpl.USER_AGENT)
        c.setRequestProperty("Accept-Encoding", "identity")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(POST_BODY.size)
        c.outputStream.use { it.write(POST_BODY) }
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            error(t("YouTube ответил HTTP $code", "YouTube answered HTTP $code"))
        }
        return c
    }

    internal fun get(url: String, from: Long, to: Long): ByteArray {
        val c = open(url, from, to)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    internal fun copy(url: String, from: Long, to: Long, o: OutputStream, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val c = open(url, from, to)
        try {
            val total = if (to >= from) to - from + 1 else c.contentLengthLong
            c.inputStream.use { inp ->
                val buf = ByteArray(1 shl 15)
                var done = 0L
                var last = -1
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt().coerceAtMost(100)
                        if (p != last) progress(p)
                        last = p
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Copies the video samples of [src] from the key frame at or before [fromUs] to [toUs] into [dst], times from zero.
     * [segStartUs] is the time of the first downloaded fragment. Returns (lead, length) in µs: where [fromUs] is in
     * [dst] and how long the piece from there is.
     */
    private fun cut(src: File, dst: File, fromUs: Long, toUs: Long, segStartUs: Long): Pair<Long, Long> {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error(t("В загрузке нет видео", "The download has no video"))
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val shift = StudioCuts.shiftUs(ex.sampleTime.coerceAtLeast(0), segStartUs)
            ex.seekTo((fromUs - shift).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val out = m.addTrack(fmt)
            m.start()
            val cap = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            var buf = ByteBuffer.allocate(maxOf(cap, 1 shl 20))
            val info = MediaCodec.BufferInfo()
            var startUs = -1L
            var lastUs = 0L
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                val abs = t + shift
                if (abs > toUs) break
                if (startUs < 0) {
                    // the first sample written must be a key frame
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                        ex.advance()
                        continue
                    }
                    startUs = abs
                }
                if (ex.sampleSize > buf.capacity()) buf = ByteBuffer.allocate(ex.sampleSize.toInt() + (1 shl 16))
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, abs - startUs, flags)
                m.writeSampleData(out, buf, info)
                lastUs = abs
                ex.advance()
            }
            if (startUs < 0) return 0L to 0L
            val lead = (fromUs - startUs).coerceAtLeast(0)
            return lead to (lastUs - startUs - lead).coerceAtLeast(0)
        } finally {
            runCatching { mux?.stop() }
            runCatching { mux?.release() }
            ex.release()
        }
    }
}

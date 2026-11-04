/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Downloads only the part of a YouTube video that covers the piece (DASH fragments through the stream index, like
 * [StudioCutsLoad]) and puts it into a file without re-encoding: the video track with the sound (MP4) or the sound
 * only (AAC in M4A). The video starts at the key frame at or before the start; the sound alone starts on time.
 */
object BiteLoad {
    class Result(val file: File, val title: String, val lenMs: Long, val leadMs: Long)

    /** Original (not dubbed) AAC sound of the best quality. */
    fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val ok = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == NpFormat.M4A }
        val orig = ok.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { ok }
        return orig.maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }

    /** Stream bytes to fetch: the whole URL and the init/index ranges of its DASH layout (−1 when not known). */
    private class Src(val url: String, val initStart: Long, val initEnd: Long, val indexStart: Long, val indexEnd: Long, val length: Long)

    /** Downloads the fragments of [s] over [fromUs]..[toUs] into [part]; returns the time of the first fragment, µs. */
    private fun download(s: Src, fromUs: Long, toUs: Long, part: File, cancelled: () -> Boolean, progress: (Int) -> Unit): Long {
        val segs = if (s.initEnd > 0 && s.indexEnd > s.indexStart) {
            runCatching { StudioCuts.parseSidx(StudioCutsLoad.get(s.url, s.indexStart, s.indexEnd), s.indexStart) }
                .onFailure { L.w("bite", "index: ${it.message}") }.getOrNull()
        } else {
            null
        }
        var segStartUs = 0L
        part.outputStream().use { o ->
            if (!segs.isNullOrEmpty()) {
                val pick = StudioCuts.pickSegs(segs, fromUs, toUs)
                if (pick.isEmpty()) error(t("Отрезок за пределами видео", "The piece is outside the video"))
                segStartUs = pick.first().startUs
                o.write(StudioCutsLoad.get(s.url, s.initStart, s.initEnd))
                StudioCutsLoad.copy(s.url, pick.first().offset, pick.last().offset + pick.last().size - 1, o, cancelled, progress)
            } else {
                if (s.length > StudioCuts.MAX_FULL_BYTES) error(t("Видео слишком большое для загрузки целиком", "The video is too big to download whole"))
                StudioCutsLoad.copy(s.url, 0, if (s.length > 0) s.length - 1 else -1, o, cancelled, progress)
            }
        }
        return segStartUs
    }

    /**
     * Cuts [fromMs]..[toMs] of [url]: [audio] — the sound only (M4A), otherwise the video up to [maxHeight] with its
     * sound (MP4). Network; worker thread.
     */
    fun bite(url: String, fromMs: Long, toMs: Long, audio: Boolean, maxHeight: Int, dir: File, cancelled: () -> Boolean, progress: (String) -> Unit): Result {
        progress(t("Ссылка на поток…", "Getting the stream…"))
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        if (cancelled()) throw CancellationException()
        val title = info.name.orEmpty()
        val fromUs = fromMs * 1000
        val toUs = toMs * 1000
        dir.mkdirs()
        val a = pickAudio(info.audioStreams)
        val v = if (audio) null else StudioCutsLoad.pickStream(info.videoOnlyStreams, maxHeight)
            ?: error(t("Нет видеопотока MP4/H.264 для «$title»", "No MP4/H.264 video stream for \"$title\""))
        if (audio && a == null) error(t("Нет звуковой дорожки AAC для «$title»", "No AAC sound track for \"$title\""))
        val stamp = System.currentTimeMillis()
        val vPart = File(dir, "v_$stamp.part")
        val aPart = File(dir, "a_$stamp.part")
        val out = File(dir, Bite.fileName(title, fromMs, toMs, audio))
        val tmp = File(dir, out.name + ".tmp")
        try {
            val parts = ArrayList<Triple<File, Long, String>>()
            if (v != null) {
                val s = Src(v.content, v.initStart.toLong(), v.initEnd.toLong(), v.indexStart.toLong(), v.indexEnd.toLong(), v.itagItem?.contentLength ?: -1L)
                val seg = download(s, fromUs, toUs, vPart, cancelled) { p -> progress(t("Видео ${v.height}p: $p %", "Video ${v.height}p: $p %")) }
                parts += Triple(vPart, seg, "video/")
            }
            if (a != null) {
                val s = Src(a.content, a.initStart.toLong(), a.initEnd.toLong(), a.indexStart.toLong(), a.indexEnd.toLong(), a.itagItem?.contentLength ?: -1L)
                val seg = runCatching { download(s, fromUs, toUs, aPart, cancelled) { p -> progress(t("Звук: $p %", "Sound: $p %")) } }
                    .onFailure { if (it is CancellationException || audio) throw it else L.w("bite", "sound: ${it.message}") }.getOrNull()
                if (seg != null) parts += Triple(aPart, seg, "audio/")
            }
            if (cancelled()) throw CancellationException()
            progress(t("Склейка без пережатия…", "Muxing without re-encoding…"))
            val (lead, len) = mux(parts, tmp, fromUs, toUs)
            if (len <= 0) error(t("В отрезке нет данных", "The piece has no data"))
            out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return Result(out, title, len / 1000, lead / 1000)
        } finally {
            vPart.delete()
            aPart.delete()
            tmp.delete()
        }
    }

    private class Tr(val ex: MediaExtractor, val shift: Long, val video: Boolean, val fmt: MediaFormat, var out: Int = -1, var done: Boolean = false)

    /**
     * Puts the tracks of [parts] (file, time of its first fragment, mime prefix) into one MP4 [dst] over [fromUs]..[toUs],
     * times from zero. With a video track everything starts at its key frame at or before [fromUs]. Returns (lead, length), µs.
     */
    private fun mux(parts: List<Triple<File, Long, String>>, dst: File, fromUs: Long, toUs: Long): Pair<Long, Long> {
        val trs = ArrayList<Tr>()
        var mux: MediaMuxer? = null
        try {
            for ((f, seg, prefix) in parts) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.absolutePath)
                    val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
                        ?: error(t("В загрузке нет дорожки $prefix", "The download has no $prefix track"))
                    ex.selectTrack(track)
                    trs += Tr(ex, StudioCuts.shiftUs(ex.sampleTime.coerceAtLeast(0), seg), prefix == "video/", ex.getTrackFormat(track))
                } catch (e: Exception) {
                    ex.release()
                    throw e
                }
            }
            // where the file begins: the key frame of the video, or the start itself for the sound only
            var startUs = fromUs
            trs.firstOrNull { it.video }?.let { vt ->
                vt.ex.seekTo((fromUs - vt.shift).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (vt.ex.sampleTime >= 0 && vt.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) vt.ex.advance()
                if (vt.ex.sampleTime < 0) return 0L to 0L
                startUs = minOf(fromUs, vt.ex.sampleTime + vt.shift)
            }
            for (tr in trs.filter { !it.video }) {
                tr.ex.seekTo((startUs - tr.shift).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (tr.ex.sampleTime >= 0 && tr.ex.sampleTime + tr.shift < startUs) tr.ex.advance()
            }
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            for (tr in trs) tr.out = m.addTrack(tr.fmt)
            m.start()
            var buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var lastUs = 0L
            var wrote = false
            while (true) {
                // the track that is behind goes next, so the file is interleaved
                val tr = trs.filter { !it.done }.minByOrNull { it.ex.sampleTime + it.shift } ?: break
                val st = tr.ex.sampleTime
                val abs = st + tr.shift
                if (st < 0 || abs > toUs) {
                    tr.done = true
                    continue
                }
                val size = tr.ex.sampleSize
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() + (1 shl 16))
                buf.clear()
                val n = tr.ex.readSampleData(buf, 0)
                if (n < 0) {
                    tr.done = true
                    continue
                }
                val flags = if (tr.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, (abs - startUs).coerceAtLeast(0), flags)
                m.writeSampleData(tr.out, buf, info)
                wrote = true
                lastUs = maxOf(lastUs, abs)
                tr.ex.advance()
            }
            if (!wrote) return 0L to 0L
            val lead = (fromUs - startUs).coerceAtLeast(0)
            return lead to (lastUs - startUs - lead).coerceAtLeast(0)
        } finally {
            runCatching { mux?.stop() }
            runCatching { mux?.release() }
            trs.forEach { runCatching { it.ex.release() } }
        }
    }

    /** Music/Lumen through MediaStore on Android 10+; older versions keep it in the app's Music folder. */
    fun saveToMusic(ctx: Context, f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: error(t("Не удалось создать файл в «Музыке»", "Cannot create the file in Music"))
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            return "Music/Lumen/${f.name}"
        }
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return f.name
        d.mkdirs()
        val copy = File(d, f.name)
        f.copyTo(copy, overwrite = true)
        return copy.absolutePath
    }

    /** Saves a bite where the phone keeps such files: video → Movies/Lumen, sound → Music/Lumen. */
    fun save(ctx: Context, f: File, audio: Boolean): String = if (audio) saveToMusic(ctx, f) else StudioJob.saveToMovies(ctx, f, f.name)
}

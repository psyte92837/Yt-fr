/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Projects of the audio editor: files/lumen/studio/audio/<id>/project.json + src/<source>.pcm (16-bit stereo) and .pk (peaks). */
object AudioStore {
    /** Frames per byte of the peaks file. */
    const val PEAK = 256
    /** Longest file kept from one import or recording. */
    const val MAX_MS = 30 * 60_000L

    val root: File get() = File(Store.root, "studio/audio").also { it.mkdirs() }
    fun dir(id: String) = File(root, id)
    fun pcm(pid: String, sid: String) = File(dir(pid), "src/$sid.pcm")
    fun peaks(pid: String, sid: String) = File(dir(pid), "src/$sid.pk")

    fun newId(prefix: String): String = prefix + System.currentTimeMillis().toString(36) + (100 + (Math.random() * 800).toInt()).toString(36)

    fun list(): List<AProject> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(it.name) }.sortedByDescending { it.updated }

    fun load(id: String): AProject? = try {
        File(dir(id), "project.json").takeIf { it.exists() }?.let { AudioEdit.fromJson(org.json.JSONObject(it.readText())) }
    } catch (e: Exception) {
        L.w("audio", "load $id: ${e.message}")
        null
    }

    fun save(p: AProject): AProject {
        val q = p.copy(updated = System.currentTimeMillis())
        val f = File(dir(p.id), "project.json")
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(AudioEdit.toJson(q).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return q
    }

    fun create(name: String, trackName: String): AProject {
        val now = System.currentTimeMillis()
        return save(AudioEdit.empty(newId("a"), name, now, trackName))
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun duplicate(p: AProject, name: String): AProject {
        val q = p.copy(id = newId("a"), name = name, created = System.currentTimeMillis())
        File(dir(p.id), "src").copyRecursively(File(dir(q.id), "src"), overwrite = true)
        return save(q)
    }

    /** Deletes source files nothing in [p] uses (on opening, before any undo history exists). */
    fun cleanup(p: AProject) {
        val keep = p.sources.map { it.id }.toSet()
        File(dir(p.id), "src").listFiles()?.forEach { f -> if (f.nameWithoutExtension !in keep && !f.name.endsWith(".tmp")) f.delete() }
    }

    fun sizeMb(p: AProject): Float = (File(dir(p.id), "src").listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576f
}

/** A source being written: 16-bit stereo frames into .pcm and a byte of the peak per [AudioStore.PEAK] frames into .pk. */
class SrcWriter(private val pcm: File, private val pk: File) {
    private val out = BufferedOutputStream(FileOutputStream(pcm.also { it.parentFile?.mkdirs() }), 1 shl 16)
    private val peaks = java.io.ByteArrayOutputStream()
    private var peak = 0
    private var inPeak = 0
    var frames = 0L
        private set
    private val bb = ByteArray(4)

    fun frame(l: Float, r: Float) {
        val a = (l * 32767f).roundToInt().coerceIn(-32768, 32767)
        val b = (r * 32767f).roundToInt().coerceIn(-32768, 32767)
        bb[0] = a.toByte(); bb[1] = (a shr 8).toByte(); bb[2] = b.toByte(); bb[3] = (b shr 8).toByte()
        out.write(bb)
        peak = max(peak, max(abs(a), abs(b)))
        if (++inPeak == AudioStore.PEAK) flushPeak()
        frames++
    }

    fun shorts(s: ShortArray, n: Int = s.size) {
        var i = 0
        while (i + 1 < n) {
            frame(s[i] / 32768f, s[i + 1] / 32768f)
            i += 2
        }
    }

    private fun flushPeak() {
        peaks.write(min(255, peak * 255 / 32767))
        peak = 0
        inPeak = 0
    }

    fun finish(): Long {
        if (inPeak > 0) flushPeak()
        out.close()
        pk.writeBytes(peaks.toByteArray())
        return frames
    }

    fun abort() {
        runCatching { out.close() }
        pcm.delete()
        pk.delete()
    }
}

/** A source file read back for the mixer. */
class FileSrc(file: File) : ASrc, java.io.Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val frames: Long = raf.length() / 4
    private var bytes = ByteArray(0)

    @Synchronized
    override fun read(pos: Long, dst: ShortArray, frames: Int) {
        java.util.Arrays.fill(dst, 0, frames * 2, 0)
        val a = max(0L, pos)
        val b = min(this.frames, pos + frames)
        if (b <= a) return
        val n = (b - a).toInt()
        if (bytes.size < n * 4) bytes = ByteArray(n * 4)
        raf.seek(a * 4)
        raf.readFully(bytes, 0, n * 4)
        ByteBuffer.wrap(bytes, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(dst, ((a - pos) * 2).toInt(), n * 2)
    }

    override fun close() = runCatching { raf.close() }.let { }
}

/** Peaks of a source for drawing: one byte (0..255 of full scale) per [AudioStore.PEAK] frames. */
class Peaks(val data: ByteArray) {
    /** Highest peak between frames [a] and [b], 0..1. */
    fun max(a: Long, b: Long): Float {
        if (data.isEmpty()) return 0f
        val i0 = (min(a, b) / AudioStore.PEAK).toInt().coerceIn(0, data.size - 1)
        val i1 = (max(a, b) / AudioStore.PEAK).toInt().coerceIn(i0, data.size - 1)
        var m = 0
        for (i in i0..i1) m = max(m, data[i].toInt() and 0xFF)
        return m / 255f
    }
}

/** Reading audio files of any kind Android knows (and the sound of videos) into sources of a project. */
object AudioImport {
    private const val TIMEOUT = 10_000L

    class Result(val src: ASource, val rate: Int, val cut: Boolean)

    fun name(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"

    /** Streaming linear resampler of stereo frames. */
    private class Resampler(private val inRate: Int, private val outRate: Int, private val emit: (Float, Float) -> Unit) {
        private var pos = 0.0
        private var pl = 0f
        private var pr = 0f
        private var have = false
        private val step = inRate.toDouble() / outRate

        fun frame(l: Float, r: Float) {
            if (inRate == outRate) return emit(l, r)
            if (!have) { pl = l; pr = r; have = true; pos = 0.0; emit(l, r); pos += step; return }
            // emit every output point that falls between the previous frame (0) and this one (1)
            while (pos <= 1.0) {
                val f = pos.toFloat()
                emit(pl + (l - pl) * f, pr + (r - pr) * f)
                pos += step
            }
            pos -= 1.0
            pl = l
            pr = r
        }
    }

    /**
     * Decodes [uri] into a new source of project [p] (its rate; the first file of an empty project sets the rate).
     * Worker thread; [progress] gets 0..100.
     */
    fun decode(ctx: Context, p: AProject, uri: Uri, cancelled: () -> Boolean, progress: (Int) -> Unit): Result {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        return decodeWith(ex, p, name(ctx, uri), cancelled, progress)
    }

    fun decode(file: File, p: AProject, name: String, cancelled: () -> Boolean, progress: (Int) -> Unit): Result {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        return decodeWith(ex, p, name, cancelled, progress)
    }

    private fun decodeWith(ex: MediaExtractor, p: AProject, name: String, cancelled: () -> Boolean, progress: (Int) -> Unit): Result {
        var dec: MediaCodec? = null
        val sid = AudioStore.newId("s")
        val w = SrcWriter(AudioStore.pcm(p.id, sid), AudioStore.peaks(p.id, sid))
        var ok = false
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException(t("В файле нет звука", "The file has no sound"))
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val rate = if (p.sources.isEmpty() && p.clips.isEmpty()) inRate.coerceIn(8000, 48_000) else p.rate
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()
            var enc = 2 // AudioFormat.ENCODING_PCM_16BIT
            var rs = Resampler(inRate, rate) { l, r -> w.frame(l, r) }
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var cut = false
            val maxFrames = AudioStore.MAX_MS * rate / 1000
            var lastPct = -1
            loop@ while (true) {
                if (cancelled()) throw CancellationException()
                if (!inDone) {
                    val i = d.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val buf = d.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) {
                                val pct = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                            ex.advance()
                        }
                    }
                }
                val o = d.dequeueOutputBuffer(info, TIMEOUT)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = d.outputFormat
                    val nr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (nr != inRate) { inRate = nr; rs = Resampler(inRate, rate) { l, r -> w.frame(l, r) } }
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val b = d.getOutputBuffer(o)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val bb = b.slice().order(ByteOrder.nativeOrder())
                        val c = max(1, ch)
                        when (enc) {
                            4 -> { // float
                                val fb = bb.asFloatBuffer()
                                val frames = fb.remaining() / c
                                for (f in 0 until frames) {
                                    val l = fb.get(f * c)
                                    rs.frame(l, if (c > 1) fb.get(f * c + 1) else l)
                                }
                            }
                            3 -> { // 8-bit unsigned
                                val frames = bb.remaining() / c
                                for (f in 0 until frames) {
                                    val l = ((bb.get(f * c).toInt() and 0xFF) - 128) / 128f
                                    rs.frame(l, if (c > 1) ((bb.get(f * c + 1).toInt() and 0xFF) - 128) / 128f else l)
                                }
                            }
                            else -> {
                                val sb = bb.asShortBuffer()
                                val frames = sb.remaining() / c
                                for (f in 0 until frames) {
                                    val l = sb.get(f * c) / 32768f
                                    rs.frame(l, if (c > 1) sb.get(f * c + 1) / 32768f else l)
                                }
                            }
                        }
                    }
                    d.releaseOutputBuffer(o, false)
                    if (w.frames >= maxFrames) { cut = true; break@loop }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
            val frames = w.finish()
            if (frames < rate / 50) throw IllegalStateException(t("Файл пустой или не читается", "The file is empty or unreadable"))
            ok = true
            progress(100)
            return Result(ASource(sid, name, frames * 1000 / rate), rate, cut)
        } finally {
            if (!ok) w.abort()
            dec?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
    }

    /** Writes generated or recorded sound ([stereo] 16-bit at the project's rate) as a new source. */
    fun write(p: AProject, name: String, stereo: ShortArray): ASource {
        val sid = AudioStore.newId("s")
        val w = SrcWriter(AudioStore.pcm(p.id, sid), AudioStore.peaks(p.id, sid))
        try {
            w.shorts(stereo)
            val frames = w.finish()
            return ASource(sid, name, frames * 1000 / p.rate)
        } catch (e: Exception) {
            w.abort()
            throw e
        }
    }

    /**
     * Ambience [id] of [ms] as a new source: made in pieces of [AudioAmb.CHUNK_MS] (each with its own randomness,
     * overlapped by [AudioAmb.JOIN_MS] with an equal-power crossfade), so 10 minutes need no more memory than 20 s.
     */
    fun writeAmbient(p: AProject, id: String, name: String, ms: Long, cancelled: () -> Boolean, progress: (Int) -> Unit): ASource {
        val rate = p.rate
        val total = AudioAmb.lengthOf(id, ms)
        val sid = AudioStore.newId("s")
        val w = SrcWriter(AudioStore.pcm(p.id, sid), AudioStore.peaks(p.id, sid))
        try {
            if ((AudioAmb.find(id)?.oneShotMs ?: 0L) > 0L || total <= AudioAmb.CHUNK_MS + AudioAmb.JOIN_MS) {
                val x = AudioAmb.render(id, rate, total) ?: error("unknown sound $id")
                for (i in 0 until x.size / 2) w.frame(x[2 * i], x[2 * i + 1])
            } else {
                val totalF = total * rate / 1000
                val joinF = (AudioAmb.JOIN_MS * rate / 1000).toInt()
                val edgeF = (rate * 0.4).toInt()
                var tail: FloatArray? = null
                var done = 0L
                var k = 0L
                while (done < totalF) {
                    if (cancelled()) throw CancellationException()
                    val x = AudioAmb.render(id, rate, AudioAmb.CHUNK_MS + AudioAmb.JOIN_MS, seed = k + 1, edges = false) ?: error("unknown sound $id")
                    val n = x.size / 2
                    val keep = n - joinF
                    for (i in 0 until keep) {
                        if (done >= totalF) break
                        var l = x[2 * i]
                        var r = x[2 * i + 1]
                        val prev = tail
                        if (prev != null && i < joinF) {
                            val a = (i + 0.5f) / joinF * (Math.PI.toFloat() / 2)
                            l = l * kotlin.math.sin(a) + prev[2 * i] * kotlin.math.cos(a)
                            r = r * kotlin.math.sin(a) + prev[2 * i + 1] * kotlin.math.cos(a)
                        }
                        // soft start and end of the whole
                        val g = min(1f, min(done.toFloat() / edgeF, (totalF - 1 - done).toFloat() / edgeF)).coerceAtLeast(0f)
                        w.frame(l * g, r * g)
                        done++
                    }
                    tail = x.copyOfRange(keep * 2, n * 2)
                    k++
                    progress((done * 100 / totalF).toInt())
                }
            }
            val frames = w.finish()
            return ASource(sid, name, frames * 1000 / rate)
        } catch (e: Throwable) {
            w.abort()
            throw e
        }
    }

    /** A library sound at the project's rate. */
    fun sound(id: String, rate: Int): FloatArray = AudioGen.synth(id, rate) ?: StudioSfx.synth(id, rate)
}

/** Opens the sources of one project for one mixer; [close] closes them all. */
class SrcPool(private val pid: String) : java.io.Closeable {
    private val open = ArrayList<FileSrc>()

    @Synchronized
    fun get(sid: String): ASrc? = AudioStore.pcm(pid, sid).takeIf { it.exists() }?.let { f -> FileSrc(f).also { open += it } }

    @Synchronized
    override fun close() {
        open.forEach { it.close() }
        open.clear()
    }
}

/**
 * Playback of a project through AudioTrack on its own thread. [project] can change while playing (edits are heard
 * at once). The heard position follows the speaker, not the writer.
 */
class AudioPlayer {
    @Volatile var project: AProject? = null
    @Volatile var playing = false
        private set
    /** Loop range in ms (null = play to the end). */
    @Volatile var loop: LongRange? = null
    var onEnd: (() -> Unit)? = null

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    // (frames written so far, timeline frame at that point) for mapping the speaker's head back to the timeline
    private val marks = ArrayDeque<LongArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var startMs = 0L
    @Volatile private var rate = 44_100

    /** Heard position in ms. */
    val posMs: Long
        get() {
            val tr = track ?: return startMs
            val head = runCatching { tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            val m = synchronized(marks) { marks.lastOrNull { it[0] <= head } ?: marks.firstOrNull() } ?: return startMs
            return (m[1] + (head - m[0])) * 1000 / rate
        }

    fun play(p: AProject, fromMs: Long) {
        stop()
        project = p
        rate = p.rate
        startMs = fromMs
        stopFlag = false
        playing = true
        synchronized(marks) { marks.clear() }
        thread = Thread { run(p.id, fromMs) }.apply { name = "lumen-audio-play"; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun run(pid: String, fromMs: Long) {
        val pool = SrcPool(pid)
        val mixer = AMixer { pool.get(it) }
        val block = 2048
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minBuf, block * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            L.e("audio", "track: ${e.message}", e)
            playing = false
            pool.close()
            Lumen.ui { onEnd?.invoke() }
            return
        }
        track = tr
        val fb = FloatArray(block * 2)
        val sb = ShortArray(block * 2)
        var cur = fromMs * rate / 1000
        var written = 0L
        var ended = false
        try {
            tr.play()
            while (!stopFlag) {
                val p = project ?: break
                val end = p.length * rate / 1000
                val lp = loop?.let { (it.first * rate / 1000)..(it.last * rate / 1000) }?.takeIf { it.last - it.first > rate / 20 }
                if (lp != null && (cur >= lp.last || cur < lp.first)) cur = lp.first
                if (lp == null && cur >= end) { ended = true; break }
                val n = (if (lp != null) min(block.toLong(), lp.last - cur) else min(block.toLong(), end - cur)).toInt().coerceAtLeast(1)
                mixer.render(p, cur, fb, n)
                for (i in 0 until n * 2) sb[i] = (fb[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                synchronized(marks) {
                    marks.addLast(longArrayOf(written, cur))
                    while (marks.size > 64) marks.removeFirst()
                }
                var off = 0
                while (off < n * 2 && !stopFlag) {
                    val w = tr.write(sb, off, n * 2 - off)
                    if (w <= 0) break
                    off += w
                }
                written += n
                cur += n
            }
            if (ended && !stopFlag) {
                // let the speaker play what is queued
                val until = System.currentTimeMillis() + 3000
                while (!stopFlag && System.currentTimeMillis() < until && (tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) Thread.sleep(20)
            }
        } catch (e: Exception) {
            L.w("audio", "play: ${e.message}")
        } finally {
            startMs = posMs
            track = null
            runCatching { tr.pause(); tr.flush() }
            tr.release()
            pool.close()
            playing = false
            if (!stopFlag) Lumen.ui { onEnd?.invoke() }
        }
    }

    fun stop() {
        stopFlag = true
        thread?.join(1500)
        thread = null
        playing = false
    }

    /** Sets the position shown when nothing plays. */
    fun park(ms: Long) {
        if (!playing) startMs = ms
    }
}

/** Short one-off sounds (the library, a tone) straight to the speaker. */
object AudioPreview {
    @Volatile private var track: AudioTrack? = null

    fun play(mono: FloatArray, rate: Int) = playShorts(AudioGen.stereo(mono), rate)

    /** Interleaved stereo floats. */
    fun playStereo(st: FloatArray, rate: Int) = playShorts(ShortArray(st.size) { (st[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }, rate)

    private fun playShorts(sh: ShortArray, rate: Int) {
        stop()
        if (sh.isEmpty()) return
        Thread {
            try {
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(sh.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                tr.write(sh, 0, sh.size)
                track = tr
                tr.play()
                Thread.sleep(sh.size / 2L * 1000 / rate + 200)
                if (track === tr) { track = null; tr.release() }
            } catch (e: Exception) {
                L.w("audio", "preview: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        track?.let { tr -> track = null; runCatching { tr.stop() }; runCatching { tr.release() } }
    }
}

/** Rendering a project: measuring it, writing WAV / M4A, saving to Music/Lumen, and processing a source (noise removal). */
object AudioRender {
    enum class Fmt(val ext: String, val mime: String) { WAV("wav", "audio/wav"), M4A("m4a", "audio/mp4") }

    private const val BLOCK = 4096
    private const val TIMEOUT = 10_000L

    /** Plays [a]..[b] ms of [p] silently through [each] block (stereo floats). */
    private fun pass(p: AProject, a: Long, b: Long, cancelled: () -> Boolean, progress: (Int) -> Unit, each: (FloatArray, Int) -> Unit) {
        SrcPool(p.id).use { pool ->
            val mixer = AMixer { pool.get(it) }
            val r = p.rate
            val f0 = a * r / 1000
            val f1 = max(f0 + 1, b * r / 1000)
            val buf = FloatArray(BLOCK * 2)
            var cur = f0
            var last = -1
            while (cur < f1) {
                if (cancelled()) throw CancellationException()
                val n = min(BLOCK.toLong(), f1 - cur).toInt()
                mixer.render(p, cur, buf, n)
                each(buf, n)
                cur += n
                val pct = ((cur - f0) * 100 / (f1 - f0)).toInt()
                if (pct != last) { last = pct; progress(pct) }
            }
        }
    }

    /** Loudness of [a]..[b] (per [stepMs] levels, peak, gated loudness). */
    fun measure(p: AProject, a: Long, b: Long, stepMs: Long, cancelled: () -> Boolean, progress: (Int) -> Unit): AMeter {
        val m = AMeter(max(1, (stepMs * p.rate / 1000).toInt()))
        pass(p, a, b, cancelled, progress) { buf, n -> m.add(buf, n) }
        return m.finish()
    }

    /**
     * Writes [a]..[b] of [p] as [fmt] into [out]; [gain] scales everything (normalising). Progress 0..100.
     */
    fun write(p: AProject, a: Long, b: Long, fmt: Fmt, out: File, gain: Float, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        out.parentFile?.mkdirs()
        if (fmt == Fmt.WAV) {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val bytes = ByteArray(BLOCK * 4)
                var total = 0L
                pass(p, a, b, cancelled, progress) { buf, n ->
                    for (i in 0 until n * 2) {
                        val v = (EditSound.soft(buf[i] * gain) * 32767f).roundToInt().coerceIn(-32768, 32767)
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    raf.write(bytes, 0, n * 4)
                    total += n * 4L
                }
                raf.seek(0)
                raf.write(Studio.wavHeader(total.coerceAtMost(Int.MAX_VALUE.toLong() - 64).toInt(), p.rate, 2))
            }
            return
        }
        val rate = p.rate
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        try {
            enc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK * 4)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val info = MediaCodec.BufferInfo()
            var framesIn = 0L
            fun drain(end: Boolean) {
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, if (end) TIMEOUT else 0)
                    when {
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                            started = true
                        }
                        o >= 0 -> {
                            val b = enc.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && started) {
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                muxer.writeSampleData(track, b, info)
                            }
                            enc.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
            fun feed(sh: ShortArray, n: Int, eos: Boolean) {
                var off = 0
                while (off < n || eos) {
                    if (cancelled()) throw CancellationException()
                    val i = enc.dequeueInputBuffer(TIMEOUT)
                    if (i < 0) { drain(false); continue }
                    val ib = enc.getInputBuffer(i)!!
                    ib.clear()
                    val k = min((n - off), ib.capacity() / 2) / 2 * 2
                    ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(sh, off, k)
                    val pts = framesIn * 1_000_000L / rate
                    framesIn += k / 2
                    off += k
                    if (eos && off >= n) {
                        enc.queueInputBuffer(i, 0, k * 2, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        return
                    }
                    enc.queueInputBuffer(i, 0, k * 2, pts, 0)
                    drain(false)
                }
            }
            val sh = ShortArray(BLOCK * 2)
            pass(p, a, b, cancelled, progress) { buf, n ->
                for (i in 0 until n * 2) sh[i] = (EditSound.soft(buf[i] * gain) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                feed(sh, n * 2, false)
            }
            feed(sh, 0, true)
            drain(true)
        } finally {
            runCatching { enc.stop() }
            enc.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** Music/Lumen through MediaStore on Android 10+; older phones keep it in the app's Music folder. */
    fun saveToMusic(ctx: Context, f: File, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                ?: error(t("Не удалось создать файл в «Музыке»", "Cannot create the file in Music"))
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            return "Music/Lumen/${f.name}"
        }
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return f.name
        d.mkdirs()
        val copy = File(d, f.name)
        f.copyTo(copy, overwrite = true)
        return copy.absolutePath
    }

    /**
     * A noise-free copy of source [sid]: the noise floor is learned from the quietest moments of the whole file,
     * then each channel goes through a [SpectralGate] of [strength]. Same length as the original.
     */
    fun denoise(p: AProject, sid: String, strength: Float, cancelled: () -> Boolean, progress: (Int) -> Unit): ASource {
        val old = p.source(sid) ?: error("no source")
        val src = FileSrc(AudioStore.pcm(p.id, sid))
        val nid = AudioStore.newId("s")
        val w = SrcWriter(AudioStore.pcm(p.id, nid), AudioStore.peaks(p.id, nid))
        try {
            val total = src.frames
            val nn = SpectralGate.N
            // up to 400 frames spread over the file, mono
            val count = min(400L, max(1L, total / nn)).toInt()
            val sh = ShortArray(nn * 2)
            val frames = (0 until count).map { k ->
                if (cancelled()) throw CancellationException()
                src.read(total * k / count, sh, nn)
                SpectralGate.magnitudes(FloatArray(nn) { (sh[2 * it] + sh[2 * it + 1]) / 65536f })
            }
            val prof = SpectralGate.profile(frames)
            val gl = SpectralGate(prof, strength)
            val gr = SpectralGate(prof, strength)
            val block = 32_768
            val ib = ShortArray(block * 2)
            val l = FloatArray(block)
            val r = FloatArray(block)
            val outL = FloatArray(block + SpectralGate.HOP * 2)
            val outR = FloatArray(block + SpectralGate.HOP * 2)
            var skip = SpectralGate.LATENCY.toLong()
            var done = 0L
            var pos = 0L
            fun flush(nl: Int) {
                for (i in 0 until nl) {
                    if (skip > 0) { skip--; continue }
                    if (done >= total) return
                    w.frame(outL[i], outR[i])
                    done++
                }
            }
            while (done < total) {
                if (cancelled()) throw CancellationException()
                val n = block
                if (pos < total) src.read(pos, ib, n) else java.util.Arrays.fill(ib, 0)
                for (i in 0 until n) { l[i] = ib[2 * i] / 32768f; r[i] = ib[2 * i + 1] / 32768f }
                var nl = 0
                var nr = 0
                gl.process(l, n) { b, k -> System.arraycopy(b, 0, outL, nl, k); nl += k }
                gr.process(r, n) { b, k -> System.arraycopy(b, 0, outR, nr, k); nr += k }
                flush(min(nl, nr))
                pos += n
                progress((min(done, total) * 100 / max(1L, total)).toInt())
            }
            w.finish()
            return ASource(nid, old.name.substringBeforeLast('.') + t(" (без шума)", " (denoised)"), old.ms)
        } catch (e: Throwable) {
            w.abort()
            throw e
        } finally {
            src.close()
        }
    }
}

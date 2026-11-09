/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The sound of a cut main track (see [EditTrack]), encoded once into AAC packets: the pieces in their order, each
 * at its speed (the pitch follows the speed, like a tape), volume and sound effect ([EditSound]); freeze frames are silent.
 * Several exports of the same track (GPU, then CPU) can write it through [out].
 */
internal class EditAudio private constructor(private val format: MediaFormat, private val packets: List<Packet>) {
    private class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** A fresh writer of these packets into one muxer. */
    fun out(): AudioOut = object : AudioOut {
        private var track = -1
        private var next = 0
        private val info = MediaCodec.BufferInfo()

        override fun add(m: MediaMuxer) {
            track = runCatching { m.addTrack(format) }.getOrElse {
                L.w("studio", "edit export: the new sound cannot be added: ${it.message}")
                -1
            }
        }

        override fun write(m: MediaMuxer, untilUs: Long) {
            if (track < 0) return
            while (next < packets.size && packets[next].ptsUs <= untilUs) {
                val pk = packets[next++]
                info.set(0, pk.data.size, pk.ptsUs, pk.flags)
                m.writeSampleData(track, ByteBuffer.wrap(pk.data), info)
            }
        }

        override fun close() {}
    }

    companion object {
        private const val TIMEOUT = 10_000L
        private const val MIME_OUT = MediaFormat.MIMETYPE_AUDIO_AAC

        /**
         * The sound of [clips] with the second videos of split screens and the music blocks [splits] (folder [dir]) mixed in.
         * Null when there is no sound at all. Worker thread; throws CancellationException when [cancelled].
         */
        fun build(
            base: File, clips: List<VClip>, splits: List<EBlock>, dir: File, cancelled: () -> Boolean, progress: (String) -> Unit
        ): EditAudio? {
            val ex = MediaExtractor()
            var dec: MediaCodec? = null
            var enc: MediaCodec? = null
            val temps = ArrayList<File>()
            val sides = ArrayList<SideMix>()
            val extractors = ArrayList<MediaExtractor>()
            val decoders = ArrayList<MediaCodec>()
            try {
                ex.setDataSource(base.absolutePath)
                val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                val heard = splits.filter { (it.kind == EditSplit.KIND || it.kind == EditMusic.KIND) && it.file.isNotEmpty() && it.volume > 0f }
                // a main video without sound: silence at 44.1 kHz, unless nothing is heard at all
                if (idx == null && heard.isEmpty() && clips.none { it.src.isNotEmpty() } && !EditFx.mixes(splits)) return null
                var r: Remix? = null
                var rate = 44_100
                var outCh = 2
                if (idx != null) {
                    ex.selectTrack(idx)
                    val inFmt = ex.getTrackFormat(idx)
                    val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
                    dec = d
                    d.configure(inFmt, null, null, 0)
                    d.start()
                    val rm = Remix(ex, d, inFmt)
                    rm.probe()
                    r = rm
                    rate = rm.rate
                    outCh = rm.outCh
                }
                // the second videos first: each one's sound at this rate into a file, read back while the main sound is encoded
                heard.forEachIndexed { i, b ->
                    if (cancelled()) throw CancellationException()
                    progress(t("Звук дорожек ${i + 1}/${heard.size}", "Extra sound tracks ${i + 1}/${heard.size}"))
                    val f = File(dir, "side_${b.id}.pcm")
                    temps += f
                    val need = b.durMs * rate / 1000
                    if (side(File(dir, b.file), b, need, rate, outCh, f, cancelled)) {
                        val fade = if (b.kind == EditMusic.KIND) minOf(EditMusic.FADE_MS, b.durMs / 2) * rate / 1000 else 0L
                        val ducks = b.kind == EditMusic.KIND && !EditMusic.isSfx(b) && splits.any { EditMusic.isSfx(it) }
                        sides += SideMix(b.startMs * rate / 1000, b.startMs * rate / 1000 + need, b.volume, b.mainVolume, f, outCh,
                            if (b.animIn == "fade") fade else 0L, if (b.animOut == "fade") fade else 0L,
                            if (ducks) { fr: Long -> EditMusic.duck(splits, b, fr * 1000 / rate) } else null)
                    }
                }
                // a quieter main video without a heard second one
                splits.filter { (it.kind == EditSplit.KIND || it.kind == EditMusic.KIND) && it.file.isNotEmpty() && it.volume <= 0f && it.mainVolume != 1f }.forEach { b ->
                    sides += SideMix(b.startMs * rate / 1000, (b.startMs + b.durMs) * rate / 1000, 0f, b.mainVolume, null, outCh)
                }
                val e = MediaCodec.createEncoderByType(MIME_OUT)
                enc = e
                e.configure(MediaFormat.createAudioFormat(MIME_OUT, rate, outCh).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                e.start()
                // effect layers (EditFx) over the main sound, in output time
                val layers = EditFx.sounds(splits).mapNotNull { b ->
                    EditSound.chain(EditFx.soundId(b), rate, outCh, 1f, b.id.hashCode().toLong())?.let { FxMix(b, it, rate) }
                }
                val w = Writer(e, rate, outCh, sides, layers)
                // pieces of the other videos of the project: each video's sound decoded on its own
                val others = HashMap<String, Remix?>()
                fun remixOf(src: String): Remix? = if (src.isEmpty()) r else if (others.containsKey(src)) others[src] else run {
                    val made = runCatching {
                        val oex = MediaExtractor()
                        extractors += oex
                        oex.setDataSource(File(dir, src).absolutePath)
                        val oi = (0 until oex.trackCount).firstOrNull { oex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                            ?: return@runCatching null
                        oex.selectTrack(oi)
                        val f = oex.getTrackFormat(oi)
                        val od = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
                        decoders += od
                        od.configure(f, null, null, 0)
                        od.start()
                        Remix(oex, od, f).also { it.probe() }
                    }.onFailure { L.w("studio", "sound of $src: ${it.message}") }.getOrNull()
                    others[src] = made
                    made
                }
                clips.forEachIndexed { i, c ->
                    if (cancelled()) throw CancellationException()
                    progress(t("Звук: кусок ${i + 1}/${clips.size}", "Sound: piece ${i + 1}/${clips.size}"))
                    val need = (c.outMs * rate / 1000.0).roundToLong()
                    val rm = if (c.isHold || c.volume <= 0f) null else remixOf(c.src)
                    // the sound effect of the piece (EditSound), at the output rate and the piece's own channels
                    val fx = if (rm == null) null else EditSound.chain(c.sfx, rate, rm.outCh, c.sfxK, i + 1L)
                    if (rm == null) w.silence(need)
                    else if (rm.outCh == outCh) rm.piece(c, need, rate, { a, n ->
                        fx?.process(a, n)
                        w.feed(a, n)
                    }, cancelled)
                    else rm.piece(c, need, rate, { a, n ->
                        fx?.process(a, n)
                        val m = channels(a, n, rm.outCh, outCh)
                        w.feed(m, m.size)
                    }, cancelled)
                }
                w.finish()
                val fmt = w.format ?: error("no AAC format")
                return EditAudio(fmt, w.packets.map { Packet(it.first, it.second, it.third) })
            } finally {
                sides.forEach { it.close() }
                temps.forEach { it.delete() }
                decoders.forEach { d -> runCatching { d.stop() }; runCatching { d.release() } }
                extractors.forEach { x -> runCatching { x.release() } }
                dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                enc?.let { runCatching { it.stop() }; runCatching { it.release() } }
                runCatching { ex.release() }
            }
        }

        /** [n] interleaved shorts of [a] with [inCh] channels as [outCh] channels: mono doubled, stereo averaged. */
        fun channels(a: ShortArray, n: Int, inCh: Int, outCh: Int): ShortArray {
            if (inCh == outCh) return a.copyOf(n)
            val frames = n / max(1, inCh)
            val out = ShortArray(frames * outCh)
            for (f in 0 until frames) {
                if (inCh == 1) for (c in 0 until outCh) out[f * outCh + c] = a[f]
                else {
                    var sum = 0
                    for (c in 0 until inCh) sum += a[f * inCh + c]
                    val v = (sum / inCh).toShort()
                    for (c in 0 until outCh) out[f * outCh + c] = v
                }
            }
            return out
        }

        /** The sound of split screen [b] (second video [file]) as [need] frames of [rate] / [outCh] into [out]; false when it has none. */
        private fun side(file: File, b: EBlock, need: Long, rate: Int, outCh: Int, out: File, cancelled: () -> Boolean): Boolean {
            val ex = MediaExtractor()
            var dec: MediaCodec? = null
            try {
                ex.setDataSource(file.absolutePath)
                val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return false
                ex.selectTrack(idx)
                val inFmt = ex.getTrackFormat(idx)
                val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
                dec = d
                d.configure(inFmt, null, null, 0)
                d.start()
                val r = Remix(ex, d, inFmt)
                r.probe()
                val inCh = r.outCh
                java.io.BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
                    val bytes = ByteBuffer.allocate(8192 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                    r.piece(VClip("side", b.srcMs, b.srcMs + max(1L, b.durMs)), need, rate, { a, n ->
                        // channels as in the main sound: mono doubled, stereo averaged
                        var i = 0
                        while (i < n) {
                            bytes.clear()
                            while (i < n && bytes.remaining() >= 4) {
                                if (inCh == outCh) {
                                    bytes.putShort(a[i])
                                    i++
                                } else if (inCh == 1) {
                                    bytes.putShort(a[i]).putShort(a[i])
                                    i++
                                } else {
                                    bytes.putShort(((a[i] + a[i + 1]) / 2).toShort())
                                    i += 2
                                }
                            }
                            os.write(bytes.array(), 0, bytes.position())
                        }
                    }, cancelled)
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w("studio", "split screen sound: ${e.message}")
                return false
            } finally {
                dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                runCatching { ex.release() }
            }
        }
    }

    /** A second video's sound (raw PCM file) heard over output frames [startF, endF); the main one at [mainVol] meanwhile. */
    private class SideMix(
        val startF: Long, val endF: Long, val vol: Float, val mainVol: Float, file: File?, private val ch: Int,
        private val fadeInF: Long = 0, private val fadeOutF: Long = 0,
        /** Ducking of music under the sounds of inserts, by output frame. */
        private val duck: ((Long) -> Float)? = null
    ) : java.io.Closeable {
        /** Loudness of output frame [f]: [vol] with the fades in and out. */
        fun gain(f: Long): Float {
            var g = vol
            if (fadeInF > 0) g *= ((f - startF).toFloat() / fadeInF).coerceIn(0f, 1f)
            if (fadeOutF > 0) g *= ((endF - f).toFloat() / fadeOutF).coerceIn(0f, 1f)
            duck?.let { g *= it(f) }
            return g
        }

        private val input = file?.let { java.io.DataInputStream(java.io.BufferedInputStream(it.inputStream(), 1 shl 16)) }
        private var bytes = ByteArray(0)

        /** The next [frames] frames (silence past the end of the file). */
        fun read(frames: Int): ShortArray {
            val out = ShortArray(frames * ch)
            val inp = input ?: return out
            val need = frames * ch * 2
            if (bytes.size < need) bytes = ByteArray(need)
            var got = 0
            while (got < need) {
                val k = inp.read(bytes, got, need - got)
                if (k < 0) break
                got += k
            }
            ByteBuffer.wrap(bytes, 0, got - got % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, got / 2)
            return out
        }

        override fun close() {
            runCatching { input?.close() }
        }
    }

    /** Reads the original sound piece by piece and turns it into frames of the new speed and volume. */
    private class Remix(private val ex: MediaExtractor, private val dec: MediaCodec, inFmt: MediaFormat) {
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val outCh: Int get() = if (ch >= 2) 2 else 1
        private val info = MediaCodec.BufferInfo()

        /** Decodes the beginning only to learn the real rate and channels (HE-AAC doubles the rate). */
        fun probe() {
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var tries = 0
            while (tries++ < 400) {
                val i = dec.dequeueInputBuffer(TIMEOUT)
                if (i >= 0) {
                    val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (n < 0) dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    learn(dec.outputFormat)
                    break
                }
                if (o >= 0) {
                    dec.releaseOutputBuffer(o, false)
                    break
                }
            }
            dec.flush()
        }

        private fun learn(f: MediaFormat) {
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }

        /** [need] output frames of piece [c] into [w]: linear resampling by speed, missing sound as silence. */
        fun piece(c: VClip, need: Long, outRate: Int, sink: (ShortArray, Int) -> Unit, cancelled: () -> Boolean) {
            val chOut = outCh
            dec.flush()
            ex.seekTo(c.fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val srcStart = c.fromMs * rate / 1000.0
            // source frames per output frame: the speed, and the other rate of a second video
            val step = c.speed.toDouble() * rate / outRate
            val stopUs = c.toMs * 1000 + 300_000
            // decoded frames (interleaved, outCh channels) from frame index winStart
            var win = ShortArray(0)
            var winStart = -1L
            var winLen = 0
            var j = 0L
            val out = ShortArray(4096 * chOut)
            var outN = 0
            val vol = c.volume
            fun produce(final: Boolean) {
                while (j < need) {
                    val pos = srcStart + j * step
                    val i0 = floor(pos).toLong()
                    if (!final && (winStart < 0 || i0 + 1 >= winStart + winLen)) break
                    val f = (pos - i0).toFloat()
                    for (k in 0 until chOut) {
                        val a = sample(win, winStart, winLen, i0, k, chOut)
                        val b = sample(win, winStart, winLen, i0 + 1, k, chOut)
                        val v = ((a + (b - a) * f) * vol).roundToInt().coerceIn(-32768, 32767)
                        out[outN++] = v.toShort()
                    }
                    j++
                    if (outN == out.size) {
                        sink(out, outN)
                        outN = 0
                    }
                }
                // forget what is behind
                if (winStart >= 0) {
                    val keepFrom = floor(srcStart + j * step).toLong() - 1
                    val drop = (keepFrom - winStart).coerceIn(0, winLen.toLong()).toInt()
                    if (drop > 0) {
                        System.arraycopy(win, drop * chOut, win, 0, (winLen - drop) * chOut)
                        winLen -= drop
                        winStart += drop
                    }
                }
            }
            var inDone = false
            var idle = 0
            loop@ while (j < need) {
                if (cancelled()) throw CancellationException()
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val tUs = ex.sampleTime
                        val n = if (tUs < 0 || tUs > stopUs) -1 else ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, tUs, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> learn(dec.outputFormat)
                    o >= 0 -> {
                        idle = 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            val b = dec.getOutputBuffer(o)!!
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = sb.remaining() / ch
                            if (winStart < 0) winStart = (info.presentationTimeUs * rate / 1_000_000.0).roundToLong()
                            if (win.size < (winLen + frames) * chOut) win = win.copyOf(max(win.size * 2, (winLen + frames) * chOut))
                            val tmp = ShortArray(frames * ch)
                            sb.get(tmp)
                            for (fi in 0 until frames) for (k in 0 until chOut) win[(winLen + fi) * chOut + k] = tmp[fi * ch + k]
                            winLen += frames
                        }
                        dec.releaseOutputBuffer(o, false)
                        produce(false)
                        if (eos) break@loop
                    }
                    else -> if (inDone && ++idle > 300) break@loop
                }
            }
            produce(true)
            if (outN > 0) sink(out, outN)
        }

        private fun sample(win: ShortArray, start: Long, len: Int, idx: Long, k: Int, chOut: Int): Float {
            if (start < 0) return 0f
            val p = idx - start
            return if (p < 0 || p >= len) 0f else win[(p * chOut).toInt() + k].toFloat()
        }
    }

    /** A sound effect layer ([EditFx]) over output frames of block [b]; [chain] runs at full strength, mixed by the layer's weight. */
    private class FxMix(val b: EBlock, val chain: EditSound.Chain, private val rate: Int) {
        val startF: Long = b.startMs * rate / 1000
        val endF: Long = b.endMs * rate / 1000
        fun weight(f: Long): Float = EditFx.weight(b, f * 1000 / rate)
    }

    /** Feeds PCM to the AAC encoder and keeps what comes out. */
    private class Writer(
        private val enc: MediaCodec, private val rate: Int, private val ch: Int, private val sides: List<SideMix> = emptyList(),
        private val layers: List<FxMix> = emptyList()
    ) {
        val packets = ArrayList<Triple<ByteArray, Long, Int>>()
        var format: MediaFormat? = null
        private var frames = 0L
        private val info = MediaCodec.BufferInfo()
        private val zeros = ShortArray(2048 * 2)

        fun silence(n: Long) {
            var left = n
            while (left > 0) {
                val k = min(left, (zeros.size / ch).toLong()).toInt()
                feed(zeros, k * ch)
                left -= k
            }
        }

        /** [count] shorts of [pcm] (interleaved); the second videos' sound mixed in where it is heard. */
        fun feed(pcm0: ShortArray, count: Int) {
            var pcm = pcm0
            val f0 = frames
            val n = count / ch
            // effect layers change the main sound while they last (before the other tracks are mixed in)
            for (fx in layers) {
                val a = max(f0, fx.startF)
                val b = min(f0 + n, fx.endF)
                if (b <= a) continue
                if (pcm === pcm0) pcm = pcm0.copyOf(count)
                val base = ((a - f0) * ch).toInt()
                val len = ((b - a) * ch).toInt()
                val wet = pcm.copyOfRange(base, base + len)
                fx.chain.process(wet, len)
                for (i in 0 until len) {
                    val g = fx.weight(a + i / ch)
                    val dry = pcm[base + i]
                    pcm[base + i] = (dry + (wet[i] - dry) * g).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
            for (sd in sides) {
                val a = max(f0, sd.startF)
                val b = min(f0 + n, sd.endF)
                if (b <= a) continue
                if (pcm === pcm0) pcm = pcm0.copyOf(count)
                val len = (b - a).toInt()
                val add = sd.read(len)
                val base = ((a - f0) * ch).toInt()
                for (i in 0 until len * ch) {
                    val v = pcm[base + i] * sd.mainVol + add[i] * sd.gain(a + i / ch)
                    pcm[base + i] = v.roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
            var off = 0
            while (off < count) {
                val i = enc.dequeueInputBuffer(TIMEOUT)
                if (i < 0) {
                    drain(false)
                    continue
                }
                val buf = enc.getInputBuffer(i)!!
                buf.clear()
                val room = (buf.capacity() / 2 / ch) * ch
                val k = min(room, count - off)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, k)
                enc.queueInputBuffer(i, 0, k * 2, frames * 1_000_000L / rate, 0)
                frames += k / ch
                off += k
                drain(false)
            }
        }

        fun finish() {
            while (true) {
                val i = enc.dequeueInputBuffer(TIMEOUT)
                if (i >= 0) {
                    enc.queueInputBuffer(i, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        }

        private fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (end) TIMEOUT else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++idle > 300) error("audio encoder stalled")
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = enc.outputFormat
                    o >= 0 -> {
                        val b = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            b.get(data)
                            packets += Triple(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}

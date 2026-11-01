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
 * at its speed (the pitch follows the speed, like a tape) and volume; freeze frames are silent.
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

        /** Null when the video has no sound. Worker thread; throws CancellationException when [cancelled]. */
        fun build(base: File, clips: List<VClip>, cancelled: () -> Boolean, progress: (String) -> Unit): EditAudio? {
            val ex = MediaExtractor()
            var dec: MediaCodec? = null
            var enc: MediaCodec? = null
            try {
                ex.setDataSource(base.absolutePath)
                val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return null
                ex.selectTrack(idx)
                val inFmt = ex.getTrackFormat(idx)
                val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
                dec = d
                d.configure(inFmt, null, null, 0)
                d.start()
                val r = Remix(ex, d, inFmt)
                r.probe()
                val e = MediaCodec.createEncoderByType(MIME_OUT)
                enc = e
                e.configure(MediaFormat.createAudioFormat(MIME_OUT, r.rate, r.outCh).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                e.start()
                val w = Writer(e, r.rate, r.outCh)
                clips.forEachIndexed { i, c ->
                    if (cancelled()) throw CancellationException()
                    progress(t("Звук: кусок ${i + 1}/${clips.size}", "Sound: piece ${i + 1}/${clips.size}"))
                    val need = (c.outMs * r.rate / 1000.0).roundToLong()
                    if (c.isHold || c.volume <= 0f) w.silence(need) else r.piece(c, need, w, cancelled)
                }
                w.finish()
                val fmt = w.format ?: error("no AAC format")
                return EditAudio(fmt, w.packets.map { Packet(it.first, it.second, it.third) })
            } finally {
                dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                enc?.let { runCatching { it.stop() }; runCatching { it.release() } }
                runCatching { ex.release() }
            }
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
        fun piece(c: VClip, need: Long, w: Writer, cancelled: () -> Boolean) {
            val chOut = outCh
            dec.flush()
            ex.seekTo(c.fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val srcStart = c.fromMs * rate / 1000.0
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
                    val pos = srcStart + j * c.speed.toDouble()
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
                        w.feed(out, outN)
                        outN = 0
                    }
                }
                // forget what is behind
                if (winStart >= 0) {
                    val keepFrom = floor(srcStart + j * c.speed.toDouble()).toLong() - 1
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
            if (outN > 0) w.feed(out, outN)
        }

        private fun sample(win: ShortArray, start: Long, len: Int, idx: Long, k: Int, chOut: Int): Float {
            if (start < 0) return 0f
            val p = idx - start
            return if (p < 0 || p >= len) 0f else win[(p * chOut).toInt() + k].toFloat()
        }
    }

    /** Feeds PCM to the AAC encoder and keeps what comes out. */
    private class Writer(private val enc: MediaCodec, private val rate: Int, private val ch: Int) {
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

        /** [count] shorts of [pcm] (interleaved). */
        fun feed(pcm: ShortArray, count: Int) {
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

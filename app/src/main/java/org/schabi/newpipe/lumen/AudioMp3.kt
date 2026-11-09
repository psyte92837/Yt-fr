/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * MP3 encoding with jump3r (a pure-Java port of LAME 3.98, LGPL 2.1+): Android has no MP3 encoder of its own.
 * The library's own wrapper (lowlevel.LameEncoder) needs javax.sound, which Android lacks, so the LAME modules are
 * wired here the same way it does. Constant bit rate [kbps], joint stereo for two channels, LAME [quality] 0 (best,
 * slow) … 9 (fast). Feed interleaved floats −1..1 with [write], then [finish]. Pure JVM (tested on the desktop JVM).
 */
class Mp3Writer(rate: Int, private val channels: Int, kbps: Int, quality: Int, private val out: OutputStream) {
    companion object {
        val BITRATES = listOf(128, 192, 256, 320)
        const val DEFAULT_KBPS = 192
        /** LAME quality: 2 is "high", 5 the usual default; the Java port is slow, so the editor uses 5. */
        const val DEFAULT_QUALITY = 5
    }

    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var buf = ByteArray(0)

    init {
        require(channels in 1..2) { "1 or 2 channels" }
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val gaud = GetAudio()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)
        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = rate
        gfp.mode = if (channels == 2) MPEGMode.JOINT_STEREO else MPEGMode.MONO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = kbps
        gfp.quality = quality.coerceIn(0, 9)
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        gfp.bWriteVbrTag = false
        val r = lame.lame_init_params(gfp)
        check(r >= 0) { "MP3 encoder setup failed ($r)" }
    }

    /** [frames] frames of interleaved [x] (−1..1). */
    fun write(x: FloatArray, frames: Int) {
        if (frames <= 0) return
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
            buf = ByteArray((frames * 1.25).toInt() + 7200)
        }
        for (f in 0 until frames) {
            left[f] = toInt(x[f * channels])
            right[f] = if (channels == 2) toInt(x[f * channels + 1]) else left[f]
        }
        val n = lame.lame_encode_buffer_int(gfp, left, right, frames, buf, 0, buf.size)
        check(n >= 0) { "MP3 encoding failed ($n)" }
        if (n > 0) out.write(buf, 0, n)
    }

    private fun toInt(v: Float): Int = (v.coerceIn(-1f, 1f) * 2.147483E9f).roundToInt()

    /** The last frames; the stream itself is not closed. */
    fun finish() {
        if (buf.size < 7200) buf = ByteArray(7200)
        val n = lame.lame_encode_flush(gfp, buf, 0, buf.size)
        if (n > 0) out.write(buf, 0, n)
        out.flush()
        lame.lame_close(gfp)
    }
}

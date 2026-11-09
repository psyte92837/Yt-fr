/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import com.google.android.exoplayer2.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound effect of the current piece ([EditSound]) heard in the editor's preview: an audio processor in front of
 * ExoPlayer's own ones (so before the speed change). [set] switches the effect at once; a seek starts it afresh.
 */
internal class EditSoundProcessor : BaseAudioProcessor() {
    @Volatile
    private var want: Pair<String, Float> = "" to 1f
    private var have: Pair<String, Float>? = null
    private var chain: EditSound.Chain? = null
    private var fmt: AudioProcessor.AudioFormat? = null
    private var pcm = ShortArray(0)
    private var seed = 1L

    /** The effect to hear from now on ("" = none). */
    fun set(id: String, k: Float) {
        want = id to k
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // other encodings pass by untouched (the processor is then inactive)
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        fmt = inputAudioFormat
        have = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val f = fmt
        val w = want
        if (w != have) {
            have = w
            chain = if (f == null) null else EditSound.chain(w.first, f.sampleRate, f.channelCount, w.second, seed++)
        }
        val out = replaceOutputBuffer(n)
        val c = chain
        if (c == null) {
            out.put(inputBuffer)
        } else {
            val shorts = n / 2
            if (pcm.size < shorts) pcm = ShortArray(shorts)
            inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, shorts)
            inputBuffer.position(inputBuffer.position() + shorts * 2)
            runCatching { c.process(pcm, shorts) }.onFailure { L.w("studio", "sound effect preview: ${it.message}") }
            out.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, shorts)
            out.position(out.position() + shorts * 2)
            // an odd last byte, if any
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }

    override fun onFlush() {
        // after a seek the tail of the old place must not ring on
        have = null
    }

    override fun onReset() {
        have = null
        chain = null
        fmt = null
    }
}

/** Renderers whose audio goes through [fx]. */
internal class EditSoundRenderers(ctx: Context, private val fx: EditSoundProcessor) : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(
        context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean, enableOffload: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(fx))
        .build()
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw sound of a source: 16-bit interleaved stereo frames; frames outside 0 until [frames] read as silence. */
interface ASrc {
    val frames: Long
    fun read(pos: Long, dst: ShortArray, frames: Int)
}

/** A source in memory (tests, generated sounds). */
class MemSrc(private val pcm: ShortArray) : ASrc {
    override val frames: Long get() = pcm.size / 2L
    override fun read(pos: Long, dst: ShortArray, frames: Int) {
        for (k in 0 until frames) {
            val f = pos + k
            if (f < 0 || f >= this.frames) { dst[2 * k] = 0; dst[2 * k + 1] = 0 } else {
                dst[2 * k] = pcm[(2 * f).toInt()]
                dst[2 * k + 1] = pcm[(2 * f + 1).toInt()]
            }
        }
    }
}

/**
 * Plays an [AProject] into stereo floats, block by block (the same for the preview and the export): every clip of
 * every heard track is read from its source at its speed (linear interpolation, backwards when reversed), goes
 * through its EQ, pitch and [EditSound] effect, gets its envelope, fades and de-click ramps, then the track's
 * volume and pan; the sum gets the master volume and a soft limiter. Effect state follows a clip while blocks
 * come in order and starts fresh after a jump.
 */
class AMixer(private val open: (String) -> ASrc?) {
    private class Run(val clip: AClip, val stages: List<EditSound.Stage>, val chain: EditSound.Chain?, var next: Long)

    private val runs = HashMap<Int, Run>()
    private class FxRun(val f: AFx, val chain: EditSound.Chain, var next: Long)
    private val fxRuns = HashMap<Int, FxRun>()
    private var wet = FloatArray(0)
    private val srcs = HashMap<String, ASrc?>()
    private var lastP: AProject? = null
    private var seamBefore = HashSet<Int>()
    private var seamAfter = HashSet<Int>()
    private var tmp = ShortArray(0)
    private var buf = FloatArray(0)

    /** Forget effect states (a jump in time does it by itself). */
    fun reset() {
        runs.clear()
        fxRuns.clear()
    }

    private fun prepare(p: AProject) {
        lastP = p
        val before = HashSet<Int>()
        val after = HashSet<Int>()
        for (t in p.tracks) {
            val cs = t.clips.sortedBy { it.at }
            for (i in 1 until cs.size) if (AudioEdit.seamless(cs[i - 1], cs[i])) { after += cs[i - 1].id; before += cs[i].id }
        }
        seamBefore = before
        seamAfter = after
        val ids = p.clips.map { it.id }.toSet()
        runs.keys.retainAll(ids)
        fxRuns.keys.retainAll(p.fx.map { it.id }.toSet())
        val used = p.sources.map { it.id }.toSet()
        srcs.keys.retainAll(used)
    }

    private fun source(id: String): ASrc? = srcs.getOrPut(id) { open(id) }

    /** Frames [start] until [start]+[frames] of the timeline into [out] (interleaved stereo, overwritten). */
    fun render(p: AProject, start: Long, out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (p !== lastP) prepare(p)
        for (t in AudioEdit.audible(p)) {
            val pl = (if (t.pan > 0f) 1f - t.pan else 1f) * t.gain
            val pr = (if (t.pan < 0f) 1f + t.pan else 1f) * t.gain
            for (c in t.clips) clip(p.rate, c, start, out, frames, pl, pr)
        }
        for (f in p.fx) layer(p.rate, f, start, out, frames)
        val g = p.gain
        for (i in 0 until frames * 2) out[i] = EditSound.soft(out[i] * g)
    }

    private fun fr(ms: Long, rate: Int): Long = ms * rate / 1000

    private fun newRun(c: AClip, rate: Int, at: Long): Run {
        val r = rate.toFloat()
        val st = ArrayList<EditSound.Stage>()
        if (c.lo != 0f) st += EditSound.Biq.lowShelf(r, 2, 150f, c.lo)
        if (c.mid != 0f) st += EditSound.Biq.peak(r, 2, 1200f, 0.8f, c.mid)
        if (c.hi != 0f) st += EditSound.Biq.highShelf(r, 2, 6000f, c.hi)
        if (abs(c.semis) > 0.01f) st += EditSound.Pitch(r, 2, c.semis)
        val chain = if (c.fx.isNotEmpty()) EditSound.chain(c.fx, rate, 2, c.fxK, c.id.toLong()) else null
        return Run(c, st, chain, at)
    }

    /** Effect layer [f] over the mix: the processed sound blends in by [AudioEdit.fxWeight] (30 ms ramps at its edges). */
    private fun layer(rate: Int, f: AFx, start: Long, out: FloatArray, frames: Int) {
        if (AudioEdit.fxEmpty(f)) return
        val i0 = max(start, fr(f.at, rate))
        val i1 = min(start + frames, fr(f.end, rate))
        if (i1 <= i0) return
        var run = fxRuns[f.id]
        if (run == null || run.f != f || run.next != i0) {
            val ch = EditSound.chain(f.fx, rate, 2, f.k, f.id.toLong()) ?: return
            run = FxRun(f, ch, i0)
            fxRuns[f.id] = run
        }
        val m = (i1 - i0).toInt()
        val o = ((i0 - start) * 2).toInt()
        if (wet.size < m * 2) wet = FloatArray(m * 2)
        System.arraycopy(out, o, wet, 0, m * 2)
        run.chain.process(wet, m * 2)
        for (k in 0 until m) {
            val w = AudioEdit.fxWeight(f, (i0 + k) * 1000.0 / rate)
            if (w <= 0f) continue
            val a = o + 2 * k
            out[a] += (wet[2 * k] - out[a]) * w
            out[a + 1] += (wet[2 * k + 1] - out[a + 1]) * w
        }
        run.next = i1
    }

    private fun clip(rate: Int, c: AClip, start: Long, out: FloatArray, frames: Int, pl: Float, pr: Float) {
        val cs = fr(c.at, rate)
        val lenF = max(1L, fr(c.len, rate))
        val ce = cs + lenF
        val i0 = max(start, cs)
        val i1 = min(start + frames, ce)
        if (i1 <= i0) return
        val src = source(c.src) ?: return
        var run = runs[c.id]
        if (run == null || run.clip != c || run.next != i0) {
            run = newRun(c, rate, i0)
            runs[c.id] = run
        }
        val m = (i1 - i0).toInt()
        if (buf.size < m * 2) buf = FloatArray(m * 2)
        val sp = c.speed.toDouble()
        val fromF = fr(c.from, rate).toDouble()
        val toF = fr(c.to, rate).toDouble()
        val u0 = (i0 - cs).toDouble()
        if (sp == 1.0 && !c.reverse) {
            if (tmp.size < m * 2) tmp = ShortArray(m * 2)
            src.read(fromF.toLong() + (i0 - cs), tmp, m)
            for (i in 0 until m * 2) buf[i] = tmp[i] / 32768f
        } else {
            fun pos(k: Int): Double = if (!c.reverse) fromF + (u0 + k) * sp else toF - 1 - (u0 + k) * sp
            val pa = pos(0)
            val pb = pos(m - 1)
            val lo = floor(min(pa, pb)).toLong()
            val n = (floor(max(pa, pb)).toLong() - lo + 2).toInt()
            if (tmp.size < n * 2) tmp = ShortArray(n * 2)
            src.read(lo, tmp, n)
            for (k in 0 until m) {
                val x = pos(k)
                val ix = floor(x).toLong()
                val f = (x - ix).toFloat()
                val j = (ix - lo).toInt().coerceIn(0, n - 2)
                buf[2 * k] = (tmp[2 * j] * (1 - f) + tmp[2 * j + 2] * f) / 32768f
                buf[2 * k + 1] = (tmp[2 * j + 1] * (1 - f) + tmp[2 * j + 3] * f) / 32768f
            }
        }
        for (s in run.stages) s.run(buf, m, 2)
        run.chain?.process(buf, m * 2)
        val fi = fr(c.fadeIn, rate)
        val fo = fr(c.fadeOut, rate)
        val dc = max(1L, rate / 400L)
        val clickIn = c.fadeIn == 0L && c.id !in seamBefore
        val clickOut = c.fadeOut == 0L && c.id !in seamAfter
        val o = ((i0 - start) * 2).toInt()
        for (k in 0 until m) {
            val u = i0 - cs + k
            var g = c.gain * (c.g0 + (c.g1 - c.g0) * (u.toFloat() / lenF))
            val left = lenF - u
            if (fi > 0 && u < fi) g *= sin(u.toFloat() / fi * PI.toFloat() / 2)
            if (fo > 0 && left < fo) g *= sin(left.toFloat() / fo * PI.toFloat() / 2)
            if (clickIn && u < dc) g *= u.toFloat() / dc
            if (clickOut && left < dc) g *= left.toFloat() / dc
            out[o + 2 * k] += buf[2 * k] * g * pl
            out[o + 2 * k + 1] += buf[2 * k + 1] * g * pr
        }
        run.next = i1
    }
}

/** Loudness of a stream of stereo floats: level per step (dB), peak, overall and gated (≈ LUFS) loudness. */
class AMeter(private val step: Int) {
    val db = ArrayList<Float>()
    var peak = 0f
        private set
    private var sum = 0.0
    private var n = 0L
    private var acc = 0.0
    private var cnt = 0
    private val blocks = ArrayList<Double>()

    fun add(b: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val l = b[2 * f]
            val r = b[2 * f + 1]
            val a = max(abs(l), abs(r))
            if (a > peak) peak = a
            val e = (l * l + r * r) / 2.0
            acc += e
            sum += e
            n++
            if (++cnt == step) flushStep()
        }
    }

    private fun flushStep() {
        if (cnt == 0) return
        val e = acc / cnt
        db += (10 * log10(e + 1e-12)).toFloat()
        blocks += e
        acc = 0.0
        cnt = 0
    }

    fun finish(): AMeter {
        flushStep()
        return this
    }

    val rmsDb: Float get() = if (n == 0L) -120f else (10 * log10(sum / n + 1e-12)).toFloat()

    /** Mean energy of the steps louder than −50 dB and than 10 dB under their mean (two gates, like EBU R128). */
    val gatedDb: Float
        get() {
            val loud = blocks.filter { 10 * log10(it + 1e-12) > -50 }
            if (loud.isEmpty()) return rmsDb
            val rel = 10 * log10(loud.average() + 1e-12) - 10
            val kept = loud.filter { 10 * log10(it + 1e-12) > rel }.ifEmpty { loud }
            return (10 * log10(kept.average() + 1e-12)).toFloat()
        }
}

/**
 * Noise removal by spectral gating: 1024-point STFT with 75 % overlap (Hann in and out), each bin pulled down where
 * it is near the learned noise floor ([profile]) — over-subtraction and a floor by [strength] 0..1, gains smoothed
 * over time and neighbouring bins against "musical" noise. Streaming per channel; output lags input by [LATENCY].
 */
class SpectralGate(private val profile: FloatArray, strength: Float) {
    companion object {
        const val N = 1024
        const val HOP = 256
        const val LATENCY = N - HOP
        val WIN = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        private const val NORM = 1.5f // Σ hann² at 75 % overlap

        /** In-place radix-2 FFT of [re]/[im] (size a power of two); [inverse] without the 1/N. */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat()
                val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** Magnitudes (N/2+1 bins) of one windowed frame of [N] samples. */
        fun magnitudes(frame: FloatArray): FloatArray {
            val re = FloatArray(N) { frame[it] * WIN[it] }
            val im = FloatArray(N)
            fft(re, im, false)
            return FloatArray(N / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        }

        /** Noise floor: the mean spectrum of the quietest 15 % of [frames] (magnitude arrays). */
        fun profile(frames: List<FloatArray>): FloatArray {
            if (frames.isEmpty()) return FloatArray(N / 2 + 1)
            val quiet = frames.sortedBy { f -> f.sumOf { (it * it).toDouble() } }.take(max(1, frames.size * 15 / 100))
            return FloatArray(N / 2 + 1) { k -> quiet.map { it[k] }.average().toFloat() }
        }
    }

    private val over = 1f + 2f * strength.coerceIn(0f, 1f)
    private val floorG = 0.3f - 0.26f * strength.coerceIn(0f, 1f)
    private val hist = FloatArray(N)
    private val pend = FloatArray(HOP)
    private var np = 0
    private val ola = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val prevG = FloatArray(N / 2 + 1) { 1f }
    private val g = FloatArray(N / 2 + 1)
    private val outHop = FloatArray(HOP)

    /** Feeds [n] samples of [x]; every full hop hands [HOP] output samples to [emit]. */
    fun process(x: FloatArray, n: Int, emit: (FloatArray, Int) -> Unit) {
        for (i in 0 until n) {
            pend[np++] = x[i]
            if (np == HOP) {
                hop()
                emit(outHop, HOP)
                np = 0
            }
        }
    }

    private fun hop() {
        System.arraycopy(hist, HOP, hist, 0, N - HOP)
        System.arraycopy(pend, 0, hist, N - HOP, HOP)
        for (i in 0 until N) { re[i] = hist[i] * WIN[i]; im[i] = 0f }
        fft(re, im, false)
        val bins = N / 2 + 1
        for (k in 0 until bins) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            val raw = max(floorG, 1f - over * profile[k] / (m + 1e-9f))
            g[k] = 0.55f * raw + 0.45f * prevG[k]
        }
        for (k in 0 until bins) {
            val s = (g[max(0, k - 1)] + 2 * g[k] + g[min(bins - 1, k + 1)]) / 4f
            prevG[k] = g[k]
            re[k] *= s; im[k] *= s
            if (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
        }
        fft(re, im, true)
        for (i in 0 until N) ola[i] += re[i] / N * WIN[i] / NORM
        System.arraycopy(ola, 0, outHop, 0, HOP)
        System.arraycopy(ola, HOP, ola, 0, N - HOP)
        java.util.Arrays.fill(ola, N - HOP, N, 0f)
    }
}

/** Sounds made from math for the library of the audio editor (no files): drums, interface, ambience, tones, noise. */
object AudioGen {
    class Sound(val id: String, val group: String, val emoji: String, val ru: String, val en: String) {
        val name: String get() = t(ru, en)
    }

    class Group(val id: String, val ru: String, val en: String) {
        val name: String get() = t(ru, en)
    }

    val GROUPS = listOf(
        Group("trans", "💨 Переходы", "💨 Transitions"), Group("ui", "📱 Интерфейс", "📱 Interface"),
        Group("drums", "🥁 Ударные", "🥁 Drums"), Group("amb", "🌧 Атмосфера", "🌧 Ambience"), Group("fun", "🎉 Забавные", "🎉 Fun")
    )

    /** "studio" sounds come from [StudioSfx] (the same as in the Studio's videos), the rest from [synth]. */
    val SOUNDS = listOf(
        Sound("whoosh", "trans", "💨", "Свист перехода", "Whoosh"), Sound("riser", "trans", "📈", "Нарастание", "Riser"),
        Sound("sweep_down", "trans", "📉", "Спуск", "Down sweep"), Sound("boom", "trans", "💥", "Бум", "Boom"),
        Sound("click", "ui", "🖱", "Щелчок", "Click"), Sound("pop", "ui", "🫧", "Поп", "Pop"), Sound("ding", "ui", "🔔", "Динь", "Ding"),
        Sound("coin", "ui", "🪙", "Монетка", "Coin"), Sound("notify", "ui", "📳", "Уведомление", "Notification"),
        Sound("buzz", "ui", "❌", "Ошибка", "Wrong buzz"), Sound("camera", "ui", "📸", "Затвор", "Camera shutter"),
        Sound("kick", "drums", "🥁", "Бочка", "Kick"), Sound("snare", "drums", "🪘", "Малый барабан", "Snare"),
        Sound("clap", "drums", "👏", "Хлопок", "Clap"), Sound("hat", "drums", "🎩", "Хай-хэт", "Hi-hat"),
        Sound("rain", "amb", "🌧", "Дождь", "Rain"), Sound("wind", "amb", "🌬", "Ветер", "Wind"), Sound("applause", "amb", "🙌", "Аплодисменты", "Applause"),
        Sound("heart", "amb", "❤", "Сердцебиение", "Heartbeat"), Sound("clock", "amb", "⏱", "Часы", "Clock"), Sound("typing", "amb", "⌨", "Клавиатура", "Typing"),
        Sound("laser", "fun", "🔫", "Лазер", "Laser"), Sound("bell", "fun", "🛎", "Колокольчик", "Bell"), Sound("boing", "fun", "🦘", "Пружина", "Boing"),
        Sound("tada", "fun", "🎉", "Та-да", "Ta-da"), Sound("beep", "fun", "🤐", "Пик (цензура)", "Bleep (censor)")
    )

    val STUDIO = setOf("whoosh", "riser", "boom", "click", "pop", "ding")

    fun find(id: String): Sound? = SOUNDS.firstOrNull { it.id == id }

    private class Osc(val rate: Int) {
        var ph = 0.0
        fun sine(hz: Double): Double { ph += 2 * PI * hz / rate; return sin(ph) }
        fun square(hz: Double): Double = if (sine(hz) >= 0) 1.0 else -1.0
    }

    /** One-pole low-pass. */
    private class Lp(var k: Double) {
        var y = 0.0
        fun run(x: Double): Double { y += k * (x - y); return y }
    }

    private fun len(rate: Int, sec: Double) = max(1, (rate * sec).toInt())

    /** Envelope: quick attack, exponential decay [d] per second. */
    private fun env(t: Double, d: Double, att: Double = 0.002) = min(1.0, t / att) * exp(-t * d)

    /** A sound of the library at [rate] (mono −1..1); null for the "studio" ones and unknown ids. */
    fun synth(id: String, rate: Int): FloatArray? {
        val rnd = java.util.Random(id.hashCode().toLong())
        fun noise() = rnd.nextDouble() * 2 - 1
        val o = Osc(rate)
        val o2 = Osc(rate)
        val out: DoubleArray = when (id) {
            "sweep_down" -> DoubleArray(len(rate, 0.7)) { i -> val x = i.toDouble() / len(rate, 0.7); o.sine(1400 * exp(-x * 3.0)) * sin(PI * x) * 0.6 }
            "coin" -> DoubleArray(len(rate, 0.5)) { i ->
                val t = i.toDouble() / rate
                (if (t < 0.08) o.square(988.0) else o.square(1319.0) * exp(-(t - 0.08) * 7)) * 0.25
            }
            "notify" -> DoubleArray(len(rate, 0.45)) { i ->
                val t = i.toDouble() / rate
                val f = if (t < 0.15) 880.0 else 1320.0
                val tt = if (t < 0.15) t else t - 0.15
                o.sine(f) * env(tt, 12.0) * 0.5
            }
            "buzz" -> DoubleArray(len(rate, 0.5)) { i -> val t = i.toDouble() / rate; (o.square(110.0) * 0.5 + o2.square(116.0) * 0.5) * 0.3 * (if (t % 0.25 < 0.2) 1.0 else 0.0) * min(1.0, (0.5 - t) * 40) }
            "camera" -> DoubleArray(len(rate, 0.25)) { i ->
                val t = i.toDouble() / rate
                noise() * (env(t, 80.0) + if (t > 0.09) env(t - 0.09, 60.0) * 0.8 else 0.0) * 0.7
            }
            "kick" -> DoubleArray(len(rate, 0.5)) { i -> val t = i.toDouble() / rate; o.sine(45 + 110 * exp(-t * 25)) * env(t, 7.0) * 0.95 }
            "snare" -> {
                val hp = Lp(0.3)
                DoubleArray(len(rate, 0.3)) { i ->
                    val t = i.toDouble() / rate
                    val n = noise()
                    ((n - hp.run(n)) * env(t, 14.0) * 0.7 + o.sine(185.0) * env(t, 25.0) * 0.5)
                }
            }
            "clap" -> {
                val bp = Lp(0.35)
                DoubleArray(len(rate, 0.35)) { i ->
                    val t = i.toDouble() / rate
                    val n = noise()
                    val b = n - bp.run(n)
                    var e = 0.0
                    for (k in 0..2) { val tk = t - k * 0.011; if (tk >= 0) e = max(e, env(tk, 120.0)) }
                    if (t > 0.022) e = max(e, env(t - 0.022, 12.0) * 0.7)
                    b * e * 0.9
                }
            }
            "hat" -> {
                val lp = Lp(0.6)
                DoubleArray(len(rate, 0.12)) { i -> val t = i.toDouble() / rate; val n = noise(); (n - lp.run(n)) * env(t, 40.0) * 0.6 }
            }
            "rain" -> {
                val lp = Lp(0.08)
                DoubleArray(len(rate, 5.0)) { i ->
                    val t = i.toDouble() / rate
                    val drop = if (rnd.nextDouble() < 18.0 / rate) 0.6 * (rnd.nextDouble() * 2 - 1) else 0.0
                    (lp.run(noise()) * 1.6 + drop) * min(1.0, t * 2) * min(1.0, (5.0 - t) * 2) * 0.6
                }
            }
            "wind" -> {
                val lp = Lp(0.02)
                DoubleArray(len(rate, 5.0)) { i ->
                    val t = i.toDouble() / rate
                    lp.k = 0.01 + 0.03 * (0.5 + 0.5 * sin(t * 1.3) * sin(t * 0.37))
                    lp.run(noise()) * 4.0 * min(1.0, t) * min(1.0, 5.0 - t) * 0.7
                }
            }
            "applause" -> {
                val bp = Lp(0.4)
                val n = len(rate, 3.5)
                val claps = DoubleArray(n)
                repeat(260) {
                    val at = (rnd.nextDouble() * (n - rate * 0.05)).toInt()
                    val g = 0.2 + rnd.nextDouble() * 0.3
                    for (k in 0 until (rate * 0.03).toInt()) claps[at + k] += g * exp(-k / (rate * 0.006))
                }
                DoubleArray(n) { i -> val t = i.toDouble() / rate; val x = noise(); (x - bp.run(x)) * claps[i] * min(1.0, t * 3) * min(1.0, (3.5 - t) * 1.5) }
            }
            "heart" -> DoubleArray(len(rate, 1.6)) { i ->
                val t = (i.toDouble() / rate) % 0.8
                o.sine(55.0) * (env(t, 18.0, 0.01) + if (t > 0.18) env(t - 0.18, 22.0, 0.01) * 0.7 else 0.0) * 0.9
            }
            "clock" -> DoubleArray(len(rate, 2.0)) { i ->
                val t = i.toDouble() / rate
                val k = (t / 0.5).toInt()
                val tt = t - k * 0.5
                (o.sine(if (k % 2 == 0) 2600.0 else 2000.0) * 0.5 + noise() * 0.5) * env(tt, 120.0) * 0.6
            }
            "typing" -> {
                val n = len(rate, 2.5)
                val hits = DoubleArray(n)
                var at = 0.0
                while (at < 2.4) {
                    val s = (at * rate).toInt()
                    val g = 0.4 + rnd.nextDouble() * 0.4
                    for (k in 0 until (rate * 0.02).toInt()) if (s + k < n) hits[s + k] += g * exp(-k / (rate * 0.003))
                    at += 0.07 + rnd.nextDouble() * 0.16
                }
                DoubleArray(n) { i -> (noise() * 0.6 + o.sine(1800.0) * 0.4) * hits[i] * 0.7 }
            }
            "laser" -> DoubleArray(len(rate, 0.35)) { i -> val t = i.toDouble() / rate; o.square(1800 * exp(-t * 6) + 150) * env(t, 6.0) * 0.3 }
            "bell" -> DoubleArray(len(rate, 2.0)) { i ->
                val t = i.toDouble() / rate
                (sin(2 * PI * 880 * t) * exp(-t * 2.5) + sin(2 * PI * 880 * 2.76 * t) * 0.5 * exp(-t * 4.0) + sin(2 * PI * 880 * 5.4 * t) * 0.25 * exp(-t * 7.0)) *
                    min(1.0, t / 0.002) * 0.45
            }
            "boing" -> DoubleArray(len(rate, 0.8)) { i -> val t = i.toDouble() / rate; o.sine(220 + 140 * sin(t * 38) * exp(-t * 3)) * env(t, 4.0) * 0.7 }
            "tada" -> DoubleArray(len(rate, 1.3)) { i ->
                val t = i.toDouble() / rate
                val notes = if (t < 0.12) listOf(523.3, 659.3) else listOf(523.3, 659.3, 784.0, 1046.5)
                val tt = if (t < 0.12) t else t - 0.12
                notes.sumOf { f -> sin(2 * PI * f * tt) + 0.3 * sin(4 * PI * f * tt) } / notes.size * env(tt, 2.2, 0.004) * 0.6
            }
            "beep" -> return tone(rate, 1000, 1000.0)
            else -> return null
        }
        return FloatArray(out.size) { out[it].toFloat().coerceIn(-1f, 1f) }
    }

    /** A tone of [ms] at [hz]: wave sine | square | saw | triangle, 5 ms soft edges. */
    fun tone(rate: Int, ms: Long, hz: Double, wave: String = "sine", level: Float = 0.5f): FloatArray {
        val n = max(1, (ms * rate / 1000).toInt())
        val edge = max(1, rate / 200)
        return FloatArray(n) { i ->
            val ph = (i * hz / rate) % 1.0
            val v = when (wave) {
                "square" -> if (ph < 0.5) 0.6 else -0.6
                "saw" -> 2 * ph - 1
                "triangle" -> 1 - 4 * abs(ph - 0.5)
                else -> sin(2 * PI * ph)
            }
            (v * level * min(1.0, min(i, n - 1 - i).toDouble() / edge)).toFloat()
        }
    }

    /** Noise of [ms]: white | pink | brown. */
    fun noise(rate: Int, ms: Long, color: String = "white", level: Float = 0.3f): FloatArray {
        val n = max(1, (ms * rate / 1000).toInt())
        val rnd = java.util.Random(color.hashCode().toLong() + ms)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var br = 0.0
        val edge = max(1, rate / 100)
        return FloatArray(n) { i ->
            val w = rnd.nextDouble() * 2 - 1
            val v = when (color) {
                "pink" -> {
                    b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913
                    (b0 + b1 + b2 + w * 0.1848) * 0.2
                }
                "brown" -> { br = (br + w * 0.02) / 1.02; br * 3.5 }
                else -> w
            }
            (v * level * min(1.0, min(i, n - 1 - i).toDouble() / edge)).toFloat().coerceIn(-1f, 1f)
        }
    }

    /** Mono floats → interleaved 16-bit stereo. */
    fun stereo(mono: FloatArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            val v = (mono[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        return out
    }

    /** Mono 16-bit at [inRate] → interleaved stereo at [outRate] (linear interpolation). */
    fun resampleToStereo(mono: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        val n = max(1L, mono.size.toLong() * outRate / inRate).toInt()
        val out = ShortArray(n * 2)
        val step = inRate.toDouble() / outRate
        for (i in 0 until n) {
            val x = i * step
            val j = x.toInt().coerceAtMost(mono.size - 1)
            val f = x - j
            val a = mono[j]
            val b = mono[min(j + 1, mono.size - 1)]
            val v = (a + (b - a) * f).roundToInt().toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        return out
    }
}

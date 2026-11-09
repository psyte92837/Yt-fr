package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EditSoundTest {
    private val rate = 44_100

    /** A voice-like test signal: 150 Hz + 1 kHz + 3 kHz with a syllable envelope, stereo interleaved. */
    private fun voice(secs: Float = 2f): ShortArray {
        val n = (secs * rate).toInt()
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = 0.5 + 0.5 * sin(2 * PI * 3 * t)
            val v = env * (0.25 * sin(2 * PI * 150 * t) + 0.15 * sin(2 * PI * 1000 * t) + 0.08 * sin(2 * PI * 3000 * t))
            val s = (v * 32767).toInt().toShort()
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
        return out
    }

    private fun run(id: String, input: ShortArray, k: Float = 1f, ch: Int = 2): ShortArray {
        val a = input.copyOf()
        val c = EditSound.chain(id, rate, ch, k)!!
        var off = 0
        while (off < a.size) {
            val n = minOf(4096, a.size - off)
            val part = a.copyOfRange(off, off + n)
            c.process(part, n)
            System.arraycopy(part, 0, a, off, n)
            off += n
        }
        return a
    }

    private fun rms(a: ShortArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += (a[i] / 32768.0) * (a[i] / 32768.0)
        return kotlin.math.sqrt(s / maxOf(1, to - from))
    }

    @Test
    fun fiftyEffectsInKnownGroups() {
        assertEquals(50, EditSound.ALL.size)
        assertEquals(50, EditSound.ALL.map { it.id }.toSet().size)
        val groups = EditSound.GROUPS.map { it.id }.toSet()
        EditSound.ALL.forEach { assertTrue(it.id, it.group in groups) }
        EditSound.GROUPS.forEach { assertTrue(it.id, EditSound.of(it.id).isNotEmpty()) }
        assertTrue(EditSound.isKnown("radio_old") && EditSound.isKnown("film_old") && EditSound.isKnown("distance"))
    }

    @Test
    fun everyEffectIsAudibleFiniteAndBounded() {
        val inp = voice()
        for (f in EditSound.ALL) {
            val out = run(f.id, inp)
            val r = rms(out)
            assertTrue("${f.id} silent: $r", r > 0.004)
            assertTrue("${f.id} too loud: $r", r < 0.6)
        }
        // mono input works as well
        val mono = ShortArray(rate) { (8000 * sin(2 * PI * 440 * it / rate)).toInt().toShort() }
        for (f in EditSound.ALL) assertTrue(f.id, rms(run(f.id, mono, ch = 1)) > 0.003)
    }

    @Test
    fun noEffectOrZeroStrengthIsNull() {
        assertNull(EditSound.chain("", rate, 2))
        assertNull(EditSound.chain("nope", rate, 2))
        assertNull(EditSound.chain("radio_old", rate, 2, 0f))
        assertNotNull(EditSound.chain("radio_old", 48_000, 1, 0.5f))
    }

    @Test
    fun phoneCutsTheBassAndKeepsTheMiddle() {
        val bass = ShortArray(rate * 2) { (10000 * sin(2 * PI * 80 * (it / 2) / rate)).toInt().toShort() }
        val mid = ShortArray(rate * 2) { (10000 * sin(2 * PI * 1200 * (it / 2) / rate)).toInt().toShort() }
        val b = rms(run("phone", bass), rate, rate * 2) / rms(bass)
        val m = rms(run("phone", mid), rate, rate * 2) / rms(mid)
        assertTrue("bass $b mid $m", b < 0.2 && m > 0.5)
    }

    @Test
    fun distanceIsQuieterAndHalfStrengthIsBetween() {
        val inp = voice()
        val full = rms(run("distance", inp))
        val half = rms(run("distance", inp, 0.5f))
        val dry = rms(inp)
        assertTrue("$full $half $dry", full < dry && half < dry && half > full)
    }

    @Test
    fun churchRingsAfterTheSoundStops() {
        val a = ShortArray(rate * 2 * 2)
        for (i in 0 until rate / 10) a[i * 2] = (12000 * sin(2 * PI * 500 * i / rate)).toInt().toShort().also { a[i * 2 + 1] = it }
        val out = run("church", a)
        // half a second after the burst the hall still sounds
        assertTrue(rms(out, rate, (rate * 1.5).toInt()) > 0.002)
        assertTrue(rms(run("small_room", a), rate * 2, rate * 4) < rms(out, rate * 2, rate * 4))
    }

    @Test
    fun pitchShiftMovesTheTone() {
        val inp = ShortArray(rate) { (12000 * sin(2 * PI * 300 * it / rate)).toInt().toShort() }
        fun crossings(a: ShortArray): Int {
            var n = 0
            for (i in rate / 4 + 1 until a.size) if ((a[i - 1] < 0) != (a[i] < 0)) n++
            return n
        }
        val dry = crossings(inp).toDouble()
        val up = crossings(run("chipmunk", inp, ch = 1)) / dry
        val down = crossings(run("deeper", inp, ch = 1)) / dry
        assertTrue("up $up", up > 1.3)
        assertTrue("down $down", down < 0.95 && down > 0.7)
    }

    @Test
    fun sameSeedSameSound() {
        val inp = voice(1f)
        val a = run("radio_old", inp)
        val b = run("radio_old", inp)
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(inp))
    }

    @Test
    fun softLimiterNeverClips() {
        for (x in listOf(-5f, -1f, -0.5f, 0f, 0.7f, 0.9f, 3f)) assertTrue(abs(EditSound.soft(x)) <= 1f)
        assertEquals(0.5f, EditSound.soft(0.5f), 1e-6f)
    }

    @Test
    fun pieceKeepsItsEffectInJson() {
        val c = VClip("c1", 0, 4000, sfx = "radio_old", sfxK = 0.6f)
        val back = EditTrack.from(EditTrack.json(listOf(c)))[0]
        assertEquals("radio_old", back.sfx)
        assertEquals(0.6f, back.sfxK, 1e-4f)
        assertFalse(back.soundPlain)
        assertTrue(EditTrack.badges(back).contains("📻"))
        assertEquals("", EditTrack.clean(c.copy(sfx = "unknown")).sfx)
        assertTrue(EditTrack.from(EditTrack.json(listOf(VClip("c2", 0, 10)))).single().soundPlain)
    }

    /** Speech-like: a 120 Hz glottal pulse train through three formants, syllables at 3.5 Hz, a pause every 3 s; −18 dBFS. */
    private fun speech(secs: Float): FloatArray {
        val n = (rate * secs).toInt()
        val out = FloatArray(n * 2)
        val rnd = java.util.Random(3)
        class Res(f: Double, bw: Double) {
            val r = kotlin.math.exp(-PI * bw / rate)
            val c = 2 * r * kotlin.math.cos(2 * PI * f / rate)
            var y1 = 0.0
            var y2 = 0.0
            fun run(x: Double): Double { val y = x + c * y1 - r * r * y2; y2 = y1; y1 = y; return y * (1 - r) }
        }
        val f1 = Res(700.0, 110.0)
        val f2 = Res(1220.0, 120.0)
        val f3 = Res(2600.0, 160.0)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            ph += (120 + 15 * sin(2 * PI * 0.7 * t)) / rate
            val src = (if (ph >= 1) { ph -= 1; 1.0 } else 0.0) + rnd.nextGaussian() * 0.02
            val env = maxOf(0.0, sin(2 * PI * 3.5 * t)) * (if (t % 3.0 < 2.2) 1.0 else 0.0)
            val v = ((f1.run(src) + f2.run(src) * 0.6 + f3.run(src) * 0.3) * env).toFloat()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        val g = 0.126f / EditSound.rms(out)
        for (i in out.indices) out[i] *= g
        return out
    }

    @Test
    fun presetsKeepTheLevelAndHaveNoOffset() {
        val inp = speech(4f)
        val dry = EditSound.rms(inp)
        val want = mapOf("distance" to -3.0, "next_room" to -6.0)
        for (f in EditSound.ALL) {
            val a = inp.copyOf()
            val c = EditSound.chain(f.id, rate, 2, 1f, 1L)!!
            var off = 0
            while (off < a.size) {
                val n = minOf(8192, a.size - off)
                val part = a.copyOfRange(off, off + n)
                c.process(part, n)
                System.arraycopy(part, 0, a, off, n)
                off += n
            }
            val db = 20 * kotlin.math.log10(EditSound.rms(a) / dry.toDouble())
            assertEquals(f.id, want[f.id] ?: 0.0, db, 1.5)
            assertTrue(f.id + " offset", abs(a.average()) < 0.002)
        }
    }
}

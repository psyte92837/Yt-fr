/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEditTest {
    private val a = ASource("a", "a.mp3", 10_000)
    private val b = ASource("b", "b.wav", 4_000)

    private fun joined(): AProject {
        val p = AudioEdit.empty("p", "Test")
        return AudioEdit.append(AudioEdit.append(p, 1, a), 1, b)
    }

    @Test
    fun joinPlacesFilesOneAfterAnother() {
        val p = joined()
        val cs = p.track(1)!!.clips
        assertEquals(2, cs.size)
        assertEquals(0L, cs[0].at)
        assertEquals(10_000L, cs[1].at)
        assertEquals(14_000L, p.length)
        assertEquals(2, p.sources.size)
    }

    @Test
    fun splitKeepsSourceAndEnvelopeContinuous() {
        val p0 = AudioEdit.updateClip(joined(), joined().track(1)!!.clips[0].id) { it.copy(g0 = 0f, g1 = 1f) }
        val p = AudioEdit.splitAt(p0, 4_000)
        val cs = p.track(1)!!.clips
        assertEquals(3, cs.size)
        assertEquals(0L to 4_000L, cs[0].from to cs[0].to)
        assertEquals(4_000L to 10_000L, cs[1].from to cs[1].to)
        assertEquals(4_000L, cs[1].at)
        assertEquals(0.4f, cs[0].g1, 1e-4f)
        assertEquals(0.4f, cs[1].g0, 1e-4f)
        assertTrue(AudioEdit.seamless(cs[0], cs[1]))
        assertEquals(p0.length, p.length)
    }

    @Test
    fun deleteWithAndWithoutRipple() {
        val p = AudioEdit.addMarker(AudioEdit.addMarker(joined(), 3_000, "m1"), 12_000, "m2")
        val r = AudioEdit.deleteRange(p, 2_000, 11_000)
        assertEquals(5_000L, r.length)
        val cs = r.track(1)!!.clips
        assertEquals(2, cs.size)
        assertEquals(0L to 2_000L, cs[0].from to cs[0].to)
        assertEquals(2_000L, cs[1].at)
        assertEquals(1_000L, cs[1].from) // b from 1 s
        assertEquals(listOf(3_000L), r.markers.map { it.at }) // m1 gone, m2 moved by 9 s
        val g = AudioEdit.deleteRange(p, 2_000, 11_000, ripple = false)
        assertEquals(14_000L, g.length)
        assertEquals(11_000L, g.track(1)!!.clips[1].at)
    }

    @Test
    fun reversedAndFastClipsMapSourceCorrectly() {
        val c = AClip(1, "a", 1_000, 9_000, 0, speed = 2f, reverse = true)
        assertEquals(4_000L, c.len)
        val first = AudioEdit.cut(c, 0, 1_000)!!
        // reversed: the first second of the timeline plays the last 2 s of the source range
        assertEquals(7_000L to 9_000L, first.from to first.to)
        assertEquals(9_000L, AudioEdit.srcAt(c, 0))
        assertEquals(5_000L, AudioEdit.srcAt(c, 2_000))
        val second = AudioEdit.cut(c, 1_000, 4_000)!!
        assertEquals(1_000L to 7_000L, second.from to second.to)
        assertEquals(1_000L, second.at)
    }

    @Test
    fun cropCopyPasteAndGap() {
        val p = joined()
        val crop = AudioEdit.cropTo(p, 9_000, 12_000)
        assertEquals(3_000L, crop.length)
        assertEquals(2, crop.track(1)!!.clips.size)
        val s = AudioEdit.copy(p, 0, 1_000)
        val pasted = AudioEdit.paste(p, s, 5_000, 1)
        assertEquals(15_000L, pasted.length)
        assertTrue(pasted.track(1)!!.clips.any { it.at == 5_000L && it.from == 0L && it.to == 1_000L })
        val gap = AudioEdit.insertGap(p, 10_000, 500)
        assertEquals(14_500L, gap.length)
        // ids stay unique
        val ids = pasted.clips.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun rangeFadesSpeedAndCrossfade() {
        val p = joined()
        val f = AudioEdit.fadeOutRange(p, 12_000, 14_000)
        val last = f.track(1)!!.clips.last()
        assertEquals(12_000L, last.at)
        assertEquals(1f, last.g0, 1e-4f)
        assertEquals(0f, last.g1, 1e-4f)
        // doubling the speed of 0..4 s makes the project 2 s shorter, the later clips follow
        val sp = AudioEdit.mapRange(p, 0, 4_000) { AudioEdit.setSpeed(it, 2f) }
        assertEquals(12_000L, sp.length)
        assertEquals(8_000L, sp.track(1)!!.clips.last().at)
        val x = AudioEdit.crossfade(p, 1, 500)
        val cs = x.track(1)!!.clips
        assertEquals(500L, cs[0].fadeOut)
        assertEquals(500L, cs[1].fadeIn)
        assertEquals(9_500L, cs[1].at)
    }

    @Test
    fun pausesAndRemovingThem() {
        // 100 ms steps: speech 0–1 s, pause 1–2.5 s, speech 2.5–3 s, pause to the end
        val db = FloatArray(40) { i -> if (i < 10 || i in 25 until 30) -20f else -60f }
        val r = AudioEdit.pauses(db, 100, -45f, 400, 100)
        assertEquals(listOf(1_100L..2_400L, 3_100L..4_000L), r)
        val p = AudioEdit.append(AudioEdit.empty("p", "x"), 1, ASource("s", "s", 4_000))
        val q = AudioEdit.removeRanges(p, r)
        assertEquals(4_000L - 1_300L - 900L, q.length)
    }

    @Test
    fun overlayUsesAFreeTrackAndJsonRoundTrip() {
        var p = joined()
        p = AudioEdit.overlay(p, ASource("s", "sfx", 800), 1_000, "2")
        assertEquals(2, p.tracks.size)
        assertEquals(1_000L, p.tracks[1].clips[0].at)
        p = AudioEdit.updateClip(p, p.tracks[1].clips[0].id) { it.copy(fx = "radio_old", fxK = 0.7f, lo = 3f, keepPitch = true, speed = 1.5f) }
        p = AudioEdit.addMarker(p, 5_000, "chorus")
        val back = AudioEdit.fromJson(JSONObject(AudioEdit.toJson(p).toString()))
        assertEquals(p.copy(), back)
        assertEquals(p.nextId, back.nextId)
        assertEquals("1:05.3", AudioEdit.time(65_300))
        assertEquals(65_300L, AudioEdit.parseTime("1:05,3"))
        assertNull(AudioEdit.parseTime("abc"))
    }

    @Test
    fun mixerPlaysClipsWithGainReverseAndMute() {
        val rate = 1000
        // source: a ramp 0..999 on both channels
        val pcm = ShortArray(2000) { (it / 2 * 10).toShort() }
        var p = AudioEdit.empty("p", "x").copy(rate = rate)
        p = AudioEdit.append(p, 1, ASource("r", "ramp", 1000))
        val m = AMixer { if (it == "r") MemSrc(pcm) else null }
        val out = FloatArray(200)
        m.render(p, 500, out, 100)
        assertEquals(5000 / 32768f, out[0], 1e-3f)
        p = AudioEdit.updateClip(p, p.clips[0].id) { it.copy(reverse = true, gain = 0.5f) }
        m.render(p, 500, out, 100)
        assertEquals(499 * 10 / 32768f * 0.5f, out[0], 2e-3f)
        p = AudioEdit.updateTrack(p, 1) { it.copy(mute = true) }
        m.render(p, 500, out, 100)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun meterAndGains() {
        val rate = 8000
        val tone = AudioGen.tone(rate, 1000, 440.0, level = 0.5f)
        val st = FloatArray(tone.size * 2) { tone[it / 2] }
        val m = AMeter(rate / 10).also { it.add(st, tone.size) }.finish()
        assertEquals(0.5f, m.peak, 0.01f)
        assertEquals(-9.03f, m.rmsDb, 0.3f) // 0.5 / √2
        assertEquals(10, m.db.size)
        assertEquals(EditSound.dbToGain(-1f) / 0.5f, AudioEdit.peakGain(0.5f), 1e-4f)
        assertTrue(AudioEdit.loudGain(-30f, 0.9f, -14f) < 1.2f) // limited by the peak
    }

    @Test
    fun spectralGateLowersNoiseAndKeepsTone() {
        val rate = 16000
        val rnd = java.util.Random(7)
        val n = rate * 2
        val noise = FloatArray(n) { (rnd.nextGaussian() * 0.05).toFloat() }
        val sig = FloatArray(n) { i -> noise[i] + if (i >= n / 2) (0.5 * sin(2 * PI * 500 * i / rate)).toFloat() else 0f }
        val frames = (0 until n / 2 - SpectralGate.N step SpectralGate.HOP).map { s -> SpectralGate.magnitudes(sig.copyOfRange(s, s + SpectralGate.N)) }
        val gate = SpectralGate(SpectralGate.profile(frames), 0.8f)
        val out = ArrayList<Float>()
        gate.process(sig, n) { b, k -> for (i in 0 until k) out += b[i] }
        val y = out.drop(SpectralGate.LATENCY)
        fun rms(x: List<Float>) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)
        val noiseIn = rms(sig.toList().subList(2000, n / 2 - 2000))
        val noiseOut = rms(y.subList(2000, n / 2 - 2000))
        assertTrue("noise $noiseIn → $noiseOut", noiseOut < noiseIn * 0.4)
        val toneIn = rms(sig.toList().subList(n / 2 + 2000, n / 2 + 8000))
        val toneOut = rms(y.subList(n / 2 + 2000, n / 2 + 8000))
        assertTrue("tone $toneIn → $toneOut", abs(toneOut - toneIn) / toneIn < 0.15)
    }

    @Test
    fun librarySoundsAreMadeAndBounded() {
        for (s in AudioGen.SOUNDS) {
            if (s.id in AudioGen.STUDIO) continue
            val x = AudioGen.synth(s.id, 22050)
            assertNotNull(s.id, x)
            assertTrue(s.id, x!!.isNotEmpty() && x.all { it in -1f..1f } && x.any { abs(it) > 0.01f })
        }
        assertEquals(40, AudioGen.resampleToStereo(ShortArray(10) { 100 }, 1000, 2000).size)
    }

    @Test
    fun effectLayersFollowTheEditsAndFadeAtTheirEdges() {
        var p = joined()
        val (q, id) = AudioEdit.addFx(p, "radio_old", 9_000, 3_000, 0.8f)
        p = q
        assertEquals(9_000L, AudioEdit.fxOf(p, id)!!.at)
        // cutting 2..4 s closes the gap: the layer moves left
        assertEquals(7_000L, AudioEdit.fxOf(AudioEdit.deleteRange(p, 2_000, 4_000), id)!!.at)
        // cutting across it shortens it
        val cut = AudioEdit.fxOf(AudioEdit.deleteRange(p, 10_000, 11_000), id)!!
        assertEquals(9_000L to 2_000L, cut.at to cut.len)
        // a gap inside stretches it, a gap before moves it
        assertEquals(3_500L, AudioEdit.fxOf(AudioEdit.insertGap(p, 10_000, 500), id)!!.len)
        assertEquals(9_500L, AudioEdit.fxOf(AudioEdit.insertGap(p, 1_000, 500), id)!!.at)
        // crop keeps the part inside
        assertEquals(0L to 1_000L, AudioEdit.fxOf(AudioEdit.cropTo(p, 11_000, 13_000), id)!!.let { it.at to it.len })
        // kept inside the project, never shorter than the minimum
        val moved = AudioEdit.fxOf(AudioEdit.updateFx(p, id) { it.copy(at = 20_000, len = 10) }, id)!!
        assertEquals(AudioEdit.FX_MIN_MS, moved.len)
        assertEquals(14_000L - AudioEdit.FX_MIN_MS, moved.at)
        val f = AudioEdit.fxOf(p, id)!!
        assertEquals(0f, AudioEdit.fxWeight(f, 8_999.0), 0f)
        assertEquals(0.5f, AudioEdit.fxWeight(f, 9_015.0), 1e-3f)
        assertEquals(1f, AudioEdit.fxWeight(f, 10_000.0), 0f)
        assertTrue(AudioEdit.fxEmpty(f.copy(fx = "nope")) && AudioEdit.fxEmpty(f.copy(k = 0f)))
        // overlapping layers get rows; JSON keeps them
        val (p2, id2) = AudioEdit.addFx(p, "echo", 10_000, 1_000)
        assertEquals(setOf(0, 1), AudioEdit.fxRows(p2.fx).values.toSet())
        val back = AudioEdit.fromJson(JSONObject(AudioEdit.toJson(p2).toString()))
        assertEquals(p2.fx, back.fx)
        assertTrue(back.nextId > id2)
    }

    @Test
    fun mixerAppliesALayerOnlyWhereItLasts() {
        val rate = 8000
        val pcm = ShortArray(rate * 2 * 2) { (8000 * sin(2 * PI * 80 * (it / 2) / rate)).toInt().toShort() }
        var p = AudioEdit.empty("p", "x").copy(rate = rate)
        p = AudioEdit.append(p, 1, ASource("t", "tone", 2000))
        val dry = FloatArray(rate * 2 * 2)
        AMixer { MemSrc(pcm) }.render(p, 0, dry, rate * 2)
        p = AudioEdit.addFx(p, "phone", 1000, 1000).first
        val wet = FloatArray(rate * 2 * 2)
        AMixer { MemSrc(pcm) }.render(p, 0, wet, rate * 2)
        // before the layer: identical; inside it the phone band cuts the 80 Hz tone
        for (i in 0 until rate) assertEquals(dry[i], wet[i], 1e-6f)
        fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { (a[it] * a[it]).toDouble() } / (to - from))
        assertTrue(rms(wet, rate * 2 + 2000, rate * 4) < rms(dry, rate * 2 + 2000, rate * 4) * 0.7)
    }

    @Test
    fun hundredAmbiencesAreMadeLevelledAndWide() {
        assertEquals(100, AudioAmb.SOUNDS.size)
        assertEquals(100, AudioAmb.SOUNDS.map { it.id }.toSet().size)
        AudioAmb.GROUPS.forEach { assertEquals(it.id, 10, AudioAmb.of(it.id).size) }
        var wide = 0
        for (s in AudioAmb.SOUNDS) {
            val x = AudioAmb.render(s.id, 16_000, 3_000)!!
            assertTrue(s.id, x.isNotEmpty() && x.none { it.isNaN() } && x.all { it in -1f..1f })
            val frames = x.size / 2
            if (s.oneShotMs == 0L) assertEquals(s.id, 3 * 16_000, frames)
            var e = 0.0
            var lr = 0.0
            for (i in 0 until frames) { e += (x[2 * i] * x[2 * i] + x[2 * i + 1] * x[2 * i + 1]) / 2.0; lr += x[2 * i] * x[2 * i + 1] }
            val db = 10 * kotlin.math.log10(e / frames + 1e-12)
            assertTrue("${s.id} $db dB", db > -40 && db < -8)
            if (lr / e < 0.9) wide++
        }
        assertTrue("wide $wide", wide > 50)
        // different pieces of a long bed are not copies of each other
        val a = AudioAmb.render("rain", 16_000, 2_000, seed = 1)!!
        val b = AudioAmb.render("rain", 16_000, 2_000, seed = 2)!!
        assertTrue(a.indices.count { a[it] != b[it] } > a.size / 2)
        assertEquals(6_000L, AudioAmb.lengthOf("car_pass", 60_000))
    }
}

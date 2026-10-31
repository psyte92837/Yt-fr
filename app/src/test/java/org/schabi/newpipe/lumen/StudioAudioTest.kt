/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The audio path behind the recorder / Studio buttons, as pure logic:
 * take (PCM) → loudness → speech spans → jump cuts → WAV → music mix.
 * Android classes (AudioRecord, MediaCodec) are not available in unit tests, so [StudioRecorder] itself is not run here.
 */
class StudioAudioTest {
    private val rate = 16_000

    private fun tone(ms: Long, amp: Int = 12_000, hz: Double = 220.0): ShortArray =
        ShortArray((ms * rate / 1000).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun quiet(ms: Long): ShortArray = ShortArray((ms * rate / 1000).toInt())

    private fun take(vararg parts: ShortArray): ShortArray = parts.fold(ShortArray(0)) { a, b -> a + b }

    // ---------- basics used by the recorder timer ----------

    @Test
    fun durationAndSilenceAreConsistent() {
        assertEquals(1000L, Studio.durationMs(rate, rate))
        assertEquals(0L, Studio.durationMs(1234, 0))
        assertEquals(0L, Studio.durationMs(0, rate))
        assertEquals(500, Studio.silence(500, rate).size / (rate / 1000) )
        assertTrue(Studio.silence(300, rate).all { it == 0.toShort() })
        assertEquals(0, Studio.silence(0, rate).size)
    }

    @Test
    fun clockFormatsRecorderTime() {
        assertEquals("0:00", Studio.clock(0))
        assertEquals("0:00", Studio.clock(-5))
        assertEquals("0:59", Studio.clock(59_999))
        assertEquals("1:05", Studio.clock(65_000))
        assertEquals("10:00", Studio.clock(600_000))
    }

    @Test
    fun recorderLimitIsTenMinutesAndFitsInt() {
        assertEquals(10, StudioRecorder.MAX_MIN)
        // the recorder compares sample counts with rate*60*MAX_MIN as Int: 44.1 kHz must not overflow
        assertTrue(44_100L * 60 * StudioRecorder.MAX_MIN < Int.MAX_VALUE)
    }

    // ---------- level meter ----------

    @Test
    fun frameRmsOfSilenceToneAndEmpty() {
        assertEquals(0, Studio.frameRms(ShortArray(0), rate).size)
        assertTrue(Studio.frameRms(quiet(200), rate).all { it == 0f })
        val loud = Studio.frameRms(tone(200, 16_384), rate)
        assertEquals(10, loud.size)
        // RMS of a sine with amplitude 0.5 is ~0.354
        assertTrue(loud.all { abs(it - 0.354f) < 0.03f })
    }

    @Test
    fun frameRmsKeepsPartialLastFrameAndSmallRate() {
        assertEquals(3, Studio.frameRms(tone(50), rate, 20).size) // 20 + 20 + 10 ms
        assertEquals(5, Studio.frameRms(ShortArray(5) { 1000 }, 100, 5).size) // frame shorter than one sample: one sample per frame
        assertEquals(0.03f, Studio.frameRms(ShortArray(7) { 1000 }, 1, 1)[0], 0.001f)
    }

    // ---------- speech detection (what "Next" after recording relies on) ----------

    @Test
    fun speechSpansFindsSpeechBetweenPauses() {
        val pcm = take(quiet(500), tone(800), quiet(900), tone(600), quiet(400))
        val spans = Studio.speechSpans(pcm, rate)
        assertEquals(2, spans.size)
        assertTrue(abs(spans[0].startMs - 500) <= 40 && abs(spans[0].endMs - 1300) <= 40)
        assertTrue(abs(spans[1].startMs - 2200) <= 40 && abs(spans[1].endMs - 2800) <= 40)
    }

    @Test
    fun speechSpansBridgesShortPausesAndDropsBlips() {
        val bridged = Studio.speechSpans(take(quiet(200), tone(400), quiet(100), tone(400), quiet(200)), rate)
        assertEquals(1, bridged.size)
        val blip = Studio.speechSpans(take(quiet(600), tone(40), quiet(600)), rate)
        assertTrue(blip.isEmpty())
    }

    @Test
    fun speechSpansEmptyAndSilentTakes() {
        assertTrue(Studio.speechSpans(ShortArray(0), rate).isEmpty())
        assertTrue(Studio.speechSpans(quiet(2000), rate).isEmpty())
    }

    @Test
    fun speechSpansNeverPastTheEndOfTheTake() {
        val pcm = take(quiet(100), tone(333))
        val s = Studio.speechSpans(pcm, rate)
        assertEquals(1, s.size)
        assertTrue(s.last().endMs <= Studio.durationMs(pcm.size, rate))
    }

    @Test
    fun speechSpansWorkWithNoisyFloor() {
        val noise = ShortArray(rate) { if (it % 2 == 0) 150 else (-150).toShort() }
        val pcm = take(noise, tone(700), noise)
        val s = Studio.speechSpans(pcm, rate)
        assertEquals(1, s.size)
        assertTrue(s[0].startMs in 900..1100)
    }

    // ---------- whole chain: record → trim pauses → export ----------

    @Test
    fun recordingToJumpCutChain() {
        val pcm = take(quiet(1500), tone(700), quiet(2000), tone(700), quiet(1500))
        val total = Studio.durationMs(pcm.size, rate)
        val speech = Studio.speechSpans(pcm, rate)
        val keep = Studio.keepSegments(speech, total)
        val cut = Studio.cutPcm(pcm, rate, keep)
        // long pauses are gone, speech is kept
        assertTrue(cut.size < pcm.size / 2)
        assertTrue(Studio.durationMs(cut.size, rate) >= 1400)
        // times map back to the original take
        assertTrue(Studio.sourceTime(0, keep) <= speech.first().startMs)
        val outMs = Studio.durationMs(cut.size, rate)
        assertEquals(keep.last().endMs, Studio.sourceTime(outMs + 5000, keep))
        // cut take still has speech at both ends of the kept parts
        assertEquals(2, Studio.speechSpans(cut, rate, minPauseMs = 100).size)
    }

    @Test
    fun cutPcmClampsOutOfRangeSegmentsAndEmptyList() {
        val pcm = tone(100)
        assertEquals(0, Studio.cutPcm(pcm, rate, emptyList()).size)
        val all = Studio.cutPcm(pcm, rate, listOf(Span(-500, 10_000)))
        assertEquals(pcm.size, all.size)
        assertEquals(0, Studio.cutPcm(pcm, rate, listOf(Span(5000, 6000))).size)
    }

    @Test
    fun keepSegmentsDoesNotCrossTheTakeBounds() {
        val keep = Studio.keepSegments(listOf(Span(10, 200), Span(2950, 3000)), 3000)
        assertEquals(0L, keep.first().startMs)
        assertEquals(3000L, keep.last().endMs)
        assertTrue(Studio.keepSegments(emptyList(), 3000).isEmpty())
    }

    // ---------- file formats used by "audio file" and share ----------

    @Test
    fun wavRoundTripMonoAndStereo() {
        val mono = take(tone(100), quiet(20))
        val bytes = Studio.wavHeader(mono.size * 2, rate) + java.nio.ByteBuffer.allocate(mono.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> mono.forEach { b.putShort(it) } }.array()
        val (back, r) = Studio.readWav(bytes)!!
        assertEquals(rate, r)
        assertArrayEquals(mono, back)

        val st = ShortArray(8) { if (it % 2 == 0) 1000 else 3000 }
        val sb = Studio.wavHeader(st.size * 2, 8000, 2) + java.nio.ByteBuffer.allocate(st.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> st.forEach { b.putShort(it) } }.array()
        val (m, r2) = Studio.readWav(sb)!!
        assertEquals(8000, r2)
        assertArrayEquals(ShortArray(4) { 2000 }, m)
    }

    @Test
    fun readWavRejectsGarbageAndTruncatedData() {
        assertNull(Studio.readWav(ByteArray(0)))
        assertNull(Studio.readWav(ByteArray(100)))
        assertNull(Studio.readWav("RIFF....WAVE".toByteArray() + ByteArray(40)))
        // header claims more data than the file holds: reads what is there, no crash
        val h = Studio.wavHeader(1_000_000, rate) + ByteArray(20)
        val (pcm, _) = Studio.readWav(h)!!
        assertEquals(10, pcm.size)
    }

    // ---------- conversions ----------

    @Test
    fun downmixResampleNormalizeEdgeCases() {
        assertArrayEquals(shortArrayOf(1, 2), Studio.downmix(shortArrayOf(1, 2), 1))
        assertArrayEquals(shortArrayOf(5), Studio.downmix(shortArrayOf(0, 10, 99), 2)) // odd tail dropped
        assertEquals(0, Studio.resample(ShortArray(0), 16000, 44100).size)
        assertEquals(2000, Studio.resample(ShortArray(1000), 8000, 16000).size)
        assertEquals(500, Studio.resample(ShortArray(1000), 16000, 8000).size)
        val same = ShortArray(10) { it.toShort() }
        assertTrue(Studio.resample(same, 16000, 16000) === same)
        // silence stays silence, quiet take is boosted but never more than x8
        val z = ShortArray(10)
        assertTrue(Studio.normalize(z) === z)
        val quietTake = ShortArray(10) { 100 }
        assertEquals(800, Studio.normalize(quietTake)[0].toInt())
        val loud = ShortArray(10) { 32767 }
        assertTrue(Studio.normalize(loud).all { it <= 32767 })
    }

    @Test
    fun fitPadsAndCuts() {
        assertEquals(5, Studio.fit(shortArrayOf(1, 2, 3), 5).size)
        assertEquals(0.toShort(), Studio.fit(shortArrayOf(1, 2, 3), 5)[4])
        assertEquals(2, Studio.fit(shortArrayOf(1, 2, 3), 2).size)
    }

    // ---------- music under the voice ----------

    @Test
    fun duckEnvelopeDipsUnderSpeechAndRecovers() {
        val env = Studio.duckEnvelope(6000, listOf(Span(2000, 3000)))
        assertEquals(601, env.size)
        assertEquals(1f, env[0], 0.001f)
        assertEquals(0.35f, env[250], 0.02f) // inside speech
        assertEquals(1f, env[600], 0.001f)
        assertTrue(env.all { it in 0.34f..1.001f })
        assertTrue(Studio.duckEnvelope(1000, emptyList()).all { it == 1f })
    }

    @Test
    fun mixMusicNoOpsAndLoops() {
        val voice = tone(2000, 4000)
        assertTrue(Studio.mixMusic(voice, ShortArray(0), rate, 0.5f, emptyList()) === voice)
        assertTrue(Studio.mixMusic(voice, tone(100), rate, 0f, emptyList()) === voice)
        val mixed = Studio.mixMusic(voice, tone(300, 8000, 440.0), rate, 0.5f, emptyList())
        assertEquals(voice.size, mixed.size)
        // fade-in: very first sample is the bare voice, middle has music added
        assertEquals(voice[0], mixed[0])
        assertFalse(mixed.slice(rate / 2 until rate).toShortArray().contentEquals(voice.slice(rate / 2 until rate).toShortArray()))
    }

    @Test
    fun mixMusicNeverClips() {
        val voice = ShortArray(rate) { 32767 }
        val music = ShortArray(rate) { 32767 }
        val out = Studio.mixMusic(voice, music, rate, 1f, emptyList())
        assertTrue(out.all { it in -32768..32767 })
    }

    @Test
    fun mixMusicIsQuieterUnderSpeech() {
        val voice = quiet(4000)
        val music = ShortArray(rate) { 10_000 }
        val speech = listOf(Span(1000, 2500))
        val out = Studio.mixMusic(voice, music, rate, 1f, speech)
        val under = out[(1_700L * rate / 1000).toInt()].toInt()
        val free = out[(3_000L * rate / 1000).toInt()].toInt()
        assertTrue("under=$under free=$free", under < free)
    }

    // ---------- output sizes ----------

    @Test
    fun videoSizingHelpers() {
        assertEquals(1, Studio.frameCount(0, 30))
        assertEquals(30, Studio.frameCount(1000, 30))
        assertEquals(31, Studio.frameCount(1001, 30))
        assertEquals(0L, Studio.frameTimeUs(0, 30))
        assertEquals(1_000_000L, Studio.frameTimeUs(30, 30))
        assertEquals(2_000_000, Studio.bitrate(320, 240, 24))
        assertTrue(Studio.bitrate(1080, 1920, 30) > 2_000_000)
        assertEquals(0, Studio.wordsFor(0))
        assertEquals(144, Studio.wordsFor(60))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("Lumen_Studio_video_2026-10-31.mp4", Studio.fileName("   ", "2026-10-31"))
        assertEquals("Lumen_Studio_video_d.mp4", Studio.fileName("???", "d"))
        val n = Studio.fileName("Как это работает: a/b\\c?*", "d")
        assertTrue(n.endsWith("_d.mp4"))
        assertFalse(n.drop(13).dropLast(6).contains(Regex("[/\\\\:*?]")))
        assertTrue(Studio.fileName("x".repeat(200), "d").length < 80)
    }

    // ---------- music search buttons ----------

    @Test
    fun musicUrlsAreEncoded() {
        val u = StudioMusicCatalog.openverseUrl("lofi chill & beats", commercial = true, page = 3)
        assertTrue(u.startsWith("https://api.openverse.org/v1/audio/?q=lofi+chill+%26+beats"))
        assertTrue(u.contains("page=3") && u.contains("license_type=commercial"))
        assertFalse(StudioMusicCatalog.openverseUrl("a", commercial = false).contains("license_type"))
        val f = StudioMusicCatalog.freesoundUrl("calm", "tok en", 20, 600)
        assertTrue(f.contains("token=tok+en") && f.contains("duration%3A%5B20+TO+600%5D"))
        assertTrue(StudioMusicCatalog.MOODS.isNotEmpty() && StudioMusicCatalog.MOODS.all { it.second.isNotBlank() })
    }

    @Test
    fun musicParsersSurviveBadInput() {
        assertTrue(StudioMusicCatalog.parseOpenverse("").isEmpty())
        assertTrue(StudioMusicCatalog.parseOpenverse("not json").isEmpty())
        assertTrue(StudioMusicCatalog.parseOpenverse("{\"results\":[null,{\"url\":\"ftp://x\"},5]}").isEmpty())
        assertTrue(StudioMusicCatalog.parseFreesound("{\"results\":[{\"name\":\"a\"}]}").isEmpty())
        assertTrue(StudioMusicCatalog.parseFreesound("[]").isEmpty())
    }

    @Test
    fun freesoundParsingAndLicenses() {
        val json = "{\"results\":[{\"id\":7,\"name\":\"Calm\",\"username\":\"u\",\"duration\":61.5," +
            "\"license\":\"http://creativecommons.org/licenses/by/4.0/\",\"url\":\"https://freesound.org/s/7/\"," +
            "\"previews\":{\"preview-lq-mp3\":\"https://cdn/x-lq.mp3\"}}]}"
        val h = StudioMusicCatalog.parseFreesound(json).single()
        assertEquals("by", h.license)
        assertEquals("4.0", h.licenseVersion)
        assertEquals(61_500L, h.durationMs)
        assertEquals("https://cdn/x-lq.mp3", h.url)
        assertTrue(StudioMusicCatalog.commercialOk(h))
        assertTrue(StudioMusicCatalog.needsCredit(h))
        assertEquals("CC BY 4.0", StudioMusicCatalog.licenseLabel(h))
        assertEquals("Music: \"Calm\" by u (CC BY 4.0) https://freesound.org/s/7/", StudioMusicCatalog.credit(h))

        assertEquals("cc0", StudioMusicCatalog.licenseCode("Creative Commons 0"))
        assertEquals("by-nc-sa", StudioMusicCatalog.licenseCode("https://creativecommons.org/licenses/by-nc-sa/3.0/"))
        assertEquals("by-nc", StudioMusicCatalog.licenseCode("Attribution NonCommercial"))
        assertEquals("", StudioMusicCatalog.licenseCode("  "))
        val nc = h.copy(license = "by-nc")
        assertFalse(StudioMusicCatalog.commercialOk(nc))
        val jam = h.copy(source = "openverse/jamendo")
        assertFalse(StudioMusicCatalog.commercialOk(jam))
        val zero = h.copy(license = "cc0")
        assertFalse(StudioMusicCatalog.needsCredit(zero))
        assertEquals("CC0", StudioMusicCatalog.licenseLabel(zero))
        assertEquals("?", StudioMusicCatalog.licenseLabel(h.copy(license = "")))
        assertNotNull(StudioMusicCatalog.credit(h.copy(creator = "", landing = "", licenseUrl = "")))
    }
}

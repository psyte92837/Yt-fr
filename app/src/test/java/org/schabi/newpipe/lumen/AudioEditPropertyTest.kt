/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Stateful property test of the audio editor model: thousands of random edit sequences (the way a user taps through
 * the editor), and after every step the invariants that the mixer, the waveform view and the saved file rely on.
 * Example-based tests (AudioEditTest) pin exact results; this one finds the sequences nobody thought of.
 * Seeds are fixed: a failure prints the seed and the step history to replay it.
 */
class AudioEditPropertyTest {

    private val sources = listOf(ASource("s1", "voice", 12_000), ASource("s2", "music", 30_000), ASource("s3", "sfx", 900))

    private class Run(val seed: Int) {
        val log = ArrayList<String>()
        fun fail(msg: String): Nothing = throw AssertionError("seed $seed after ${log.joinToString(" → ")}: $msg")
    }

    private fun step(p: AProject, r: Random, run: Run): AProject {
        val len = maxOf(p.length, 1000L)
        val a = r.nextLong(0, len)
        val b = a + r.nextLong(1, 8000)
        val track = p.tracks.random(r).id
        val clip = p.clips.randomOrNull(r)
        val op = r.nextInt(16)
        run.log += "$op@$a-$b/t$track/c${clip?.id}"
        return when (op) {
            0 -> AudioEdit.append(p, track, sources.random(r))
            1 -> AudioEdit.overlay(p, sources.random(r), a, "o")
            2 -> AudioEdit.insert(p, track, sources.random(r), a)
            3 -> AudioEdit.splitAt(p, a)
            4 -> AudioEdit.deleteRange(p, a, b, if (r.nextBoolean()) null else setOf(track), r.nextBoolean())
            5 -> AudioEdit.insertGap(p, a, b - a, if (r.nextBoolean()) null else setOf(track))
            6 -> AudioEdit.cropTo(p, a, b)
            7 -> AudioEdit.paste(p, AudioEdit.copy(p, a, b), r.nextLong(0, len), track, r.nextBoolean())
            8 -> AudioEdit.fadeInRange(p, a, b)
            9 -> AudioEdit.gainRange(p, a, b, r.nextFloat() * 2f)
            10 -> AudioEdit.addFx(p, EditSound.ALL.random(r).id, a, b - a, r.nextFloat()).first
            11 -> clip?.let { AudioEdit.moveClip(p, it.id, r.nextLong(0, len), p.tracks.random(r).id) } ?: p
            12 -> clip?.let { AudioEdit.duplicateClip(p, it.id) } ?: p
            13 -> clip?.let { AudioEdit.removeClip(p, it.id, r.nextBoolean()) } ?: p
            14 -> clip?.let { c -> AudioEdit.updateClip(p, c.id) { it.copy(speed = listOf(0.5f, 1f, 1.5f, 2f).random(r), reverse = r.nextBoolean()) } } ?: p
            else -> AudioEdit.addMarker(p, a, "m")
        }
    }

    private fun check(p: AProject, run: Run) {
        val ids = p.clips.map { it.id }
        if (ids.size != ids.toSet().size) run.fail("duplicate clip ids $ids")
        val all = ids + p.tracks.map { it.id } + p.fx.map { it.id }
        if (all.any { it >= p.nextId }) run.fail("nextId ${p.nextId} is not above every id $all")
        if (p.tracks.isEmpty()) run.fail("no tracks")
        for (t in p.tracks) {
            if (t.clips != t.clips.sortedBy { it.at }) run.fail("track ${t.id} clips are not in time order")
            for (c in t.clips) {
                if (c.at < 0) run.fail("clip ${c.id} starts before 0: ${c.at}")
                if (c.from < 0 || c.to <= c.from) run.fail("clip ${c.id} has an empty or negative source range ${c.from}..${c.to}")
                val src = p.source(c.src) ?: run.fail("clip ${c.id} refers to an unknown source ${c.src}")
                if (c.to > src.ms) run.fail("clip ${c.id} reads past the end of ${src.id}: ${c.to} > ${src.ms}")
                if (c.speed !in AudioEdit.MIN_SPEED..AudioEdit.MAX_SPEED) run.fail("clip ${c.id} speed ${c.speed}")
            }
        }
        for (f in p.fx) if (f.at < 0 || f.len <= 0) run.fail("effect layer ${f.id} at ${f.at} len ${f.len}")
        for (m in p.markers) if (m.at < 0) run.fail("marker at ${m.at}")
        // what is saved is read back as the same project (the editor's undo stack goes through JSON too)
        val back = AudioEdit.fromJson(JSONObject(AudioEdit.toJson(p).toString()))
        if (back != p) run.fail("JSON round trip changed the project:\n$p\n$back")
    }

    @Test
    fun randomEditSequencesKeepTheModelValid() {
        for (seed in 1..300) {
            val r = Random(seed)
            val run = Run(seed)
            var p = AudioEdit.append(AudioEdit.empty("a$seed", "p"), 1, sources[0])
            check(p, run)
            repeat(25) {
                p = step(p, r, run)
                check(p, run)
            }
        }
    }

    @Test
    fun aGapAndItsDeletionCancelOut() {
        // insertGap then deleteRange of the same span on all tracks gives back the same timeline (ids may differ)
        for (seed in 1..200) {
            val r = Random(seed)
            var p = AudioEdit.append(AudioEdit.empty("g", "p"), 1, sources[1])
            p = AudioEdit.overlay(p, sources[0], r.nextLong(0, 20_000), "2")
            val at = r.nextLong(0, p.length)
            val len = r.nextLong(1, 5000)
            val q = AudioEdit.deleteRange(AudioEdit.insertGap(p, at, len), at, at + len)
            assertEquals("seed $seed length", p.length, q.length)
            fun shape(x: AProject) = x.tracks.map { t -> t.clips.sumOf { it.len } }
            assertEquals("seed $seed audible material per track", shape(p), shape(q))
        }
    }

    @Test
    fun rippleDeleteShortensByTheRemovedSpan() {
        for (seed in 1..200) {
            val r = Random(seed)
            val p = AudioEdit.append(AudioEdit.append(AudioEdit.empty("d", "p"), 1, sources[0]), 1, sources[1])
            val a = r.nextLong(0, p.length)
            val b = a + r.nextLong(1, 10_000)
            val q = AudioEdit.deleteRange(p, a, b)
            assertEquals("seed $seed: $a..$b of ${p.length}", p.length - (minOf(b, p.length) - a), q.length)
            assertTrue(q.clips.all { it.end <= q.length })
        }
    }
}

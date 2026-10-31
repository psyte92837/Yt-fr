/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/*
 * Lumen Studio "Cuts": a voice-over over pieces of YouTube videos (B-roll and meme reactions).
 * The pieces are marked by link and time, downloaded as a small fragment of the video-only DASH stream,
 * cut on the phone and put on the scenes by the AI director. Pure logic here; network and media in StudioCutsLoad.kt.
 */

/** A marked piece of a YouTube video. */
data class SClip(
    val id: String,
    val url: String,
    val fromMs: Long,
    val toMs: Long,
    /** What is in the piece, for the AI director and for matching by words. */
    val note: String = "",
    /** Title of the video (filled in on download). */
    val title: String = "",
    /** Name of the cut file in the clips folder; empty until downloaded. */
    val file: String = "",
    /** Where [fromMs] is inside the file (the file starts at the key frame before it). */
    val leadMs: Long = 0,
    /** Usable length of the file from [leadMs]. */
    val lenMs: Long = 0
) {
    val ms: Long get() = toMs - fromMs
}

/** Part of the output where clip [id] plays; the file time is [leadMs] + ([offsetMs] + t − [startMs]) mod [lenMs]. */
data class ClipSlot(val startMs: Long, val endMs: Long, val id: String, val leadMs: Long, val lenMs: Long, val offsetMs: Long = 0)

/** A video found by title in the Cuts search. */
data class CutHit(val url: String, val title: String, val channel: String = "", val durationMs: Long = 0)

/** A fragment of a DASH stream from its sidx box: where its bytes are and what time it covers. */
data class DashSeg(val offset: Long, val size: Long, val startUs: Long, val durUs: Long)

object StudioCuts {
    const val DEFAULT_CLIP_MS = 5_000L
    const val MAX_CLIP_MS = 30_000L
    const val MAX_CLIPS = 40
    /** Without a stream index the whole stream is downloaded, but only up to this size. */
    const val MAX_FULL_BYTES = 80L * 1024 * 1024

    // ---------- time ----------

    /** "83", "83.5", "1:23", "1:02:03", "1m23s", "2h" → milliseconds; null when it is not a time. */
    fun parseTime(s: String): Long? {
        val v = s.trim().lowercase().replace(',', '.')
        if (v.isEmpty()) return null
        if (':' in v) {
            val parts = v.split(':')
            if (parts.size > 3 || parts.any { it.isEmpty() }) return null
            val sec = parts.last().toDoubleOrNull() ?: return null
            if (sec < 0 || sec >= 60) return null
            var min = 0L
            for (p in parts.dropLast(1)) {
                val n = p.toLongOrNull() ?: return null
                if (n < 0) return null
                min = min * 60 + n
            }
            return min * 60_000 + (sec * 1000).roundToLong()
        }
        val m = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+(?:\.\d+)?)s?)?""").matchEntire(v) ?: return null
        if (m.value.isEmpty()) return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val mi = m.groupValues[2].toLongOrNull() ?: 0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return (h * 3600 + mi * 60) * 1000 + (sec * 1000).roundToLong()
    }

    /** 65_500 → "1:05.5", 3_725_000 → "1:02:05". */
    fun stamp(ms: Long): String {
        val s = ms / 1000
        val tenth = (ms % 1000) / 100
        val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        return if (tenth > 0) "$base.$tenth" else base
    }

    /** Start time from a link: "?t=83", "&t=1m23s", "#t=83s", "&start=83". */
    fun startOf(url: String): Long? =
        Regex("""[?&#](?:t|start)=([0-9hms.:]+)""").find(url)?.groupValues?.get(1)?.let { parseTime(it) }

    /** Piece [fromMs]..[toMs] made valid: end after start, at most [MAX_CLIP_MS]; a missing end gives [DEFAULT_CLIP_MS]. */
    fun span(fromMs: Long, toMs: Long?): Pair<Long, Long> {
        val a = fromMs.coerceAtLeast(0)
        val b = (toMs?.takeIf { it > a } ?: (a + DEFAULT_CLIP_MS)).coerceAtMost(a + MAX_CLIP_MS)
        return a to b
    }

    /** Next free id: c1, c2, … */
    fun newId(clips: List<SClip>): String = "c" + ((clips.mapNotNull { it.id.removePrefix("c").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    // ---------- saved list ----------

    fun toJson(clips: List<SClip>): String = JSONArray().apply {
        clips.forEach {
            put(JSONObject().put("id", it.id).put("url", it.url).put("from", it.fromMs).put("to", it.toMs).put("note", it.note)
                .put("title", it.title).put("file", it.file).put("lead", it.leadMs).put("len", it.lenMs))
        }
    }.toString()

    fun fromJson(s: String): List<SClip> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val url = o.optString("url").trim()
            if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
            SClip(id, url, o.optLong("from"), o.optLong("to"), o.optString("note"), o.optString("title"), o.optString("file"),
                o.optLong("lead"), o.optLong("len"))
        }
    }.getOrDefault(emptyList())

    // ---------- DASH index ----------

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    /**
     * Fragments listed in the sidx box of [b] (the bytes of the stream from [startByte]). Offsets are bytes of the
     * whole stream. Null when there is no valid sidx.
     */
    fun parseSidx(b: ByteArray, startByte: Long): List<DashSeg>? {
        var p = 0
        while (p + 8 <= b.size) {
            val size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "sidx") break
            if (size < 8) return null
            p += size.toInt()
        }
        if (p + 32 > b.size) return null
        val boxSize = u32(b, p)
        val version = b[p + 8].toInt()
        var q = p + 12 + 4 // full box header, reference_ID
        val timescale = u32(b, q)
        q += 4
        if (timescale <= 0) return null
        val earliest: Long
        val firstOffset: Long
        if (version == 0) {
            earliest = u32(b, q)
            firstOffset = u32(b, q + 4)
            q += 8
        } else {
            if (q + 16 > b.size) return null
            earliest = u64(b, q)
            firstOffset = u64(b, q + 8)
            q += 16
        }
        q += 2 // reserved
        if (q + 2 > b.size) return null
        val count = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
        q += 2
        var offset = startByte + p + boxSize + firstOffset
        var t = earliest
        val out = mutableListOf<DashSeg>()
        for (i in 0 until count) {
            if (q + 12 > b.size) break
            val size = u32(b, q) and 0x7FFFFFFF
            val dur = u32(b, q + 4)
            q += 12
            out += DashSeg(offset, size, t * 1_000_000 / timescale, dur * 1_000_000 / timescale)
            offset += size
            t += dur
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Fragments that cover [fromUs]..[toUs] (each one starts with a key frame). */
    fun pickSegs(segs: List<DashSeg>, fromUs: Long, toUs: Long): List<DashSeg> =
        segs.filter { it.startUs + it.durUs > fromUs && it.startUs < toUs }

    /**
     * Shift that turns sample times of a downloaded fragment into times of the video: some extractors count from
     * zero instead of the fragment time ([firstSegUs]).
     */
    fun shiftUs(firstSampleUs: Long, firstSegUs: Long): Long =
        if (firstSegUs > 500_000 && firstSampleUs + 500_000 < firstSegUs) firstSegUs - firstSampleUs else 0

    // ---------- montage ----------

    /**
     * Where the clips play: every scene with a downloaded clip gets a slot over its time. The same clip on the next
     * scene goes on instead of starting again; a scene longer than its clip loops it.
     */
    fun slots(times: List<SceneTime>, sceneClips: List<String>, clips: Map<String, SClip>): List<ClipSlot> {
        val out = mutableListOf<ClipSlot>()
        for (st in times) {
            val id = sceneClips.getOrNull(st.scene)?.takeIf { it.isNotEmpty() } ?: continue
            val c = clips[id]?.takeIf { it.lenMs > 0 && it.file.isNotEmpty() } ?: continue
            val prev = out.lastOrNull()
            val offset = if (prev != null && prev.id == id && prev.endMs >= st.startMs - 50) (prev.offsetMs + prev.endMs - prev.startMs) % c.lenMs else 0L
            out += ClipSlot(st.startMs, st.endMs, id, c.leadMs, c.lenMs, offset)
        }
        return out
    }

    /** Index of the slot playing at [tMs], or −1. */
    fun slotAt(slots: List<ClipSlot>, tMs: Long): Int {
        var lo = 0
        var hi = slots.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val s = slots[mid]
            when {
                tMs < s.startMs -> hi = mid - 1
                tMs >= s.endMs -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** Time inside the clip file at output time [tMs]. */
    fun fileTime(s: ClipSlot, tMs: Long): Long =
        s.leadMs + if (s.lenMs <= 0) 0 else (s.offsetMs + (tMs - s.startMs).coerceAtLeast(0)) % s.lenMs

    /** Word stems (first 5 letters of words of 4+ letters) to match a scene with a clip note. */
    fun stems(s: String): Set<String> = Studio.tokens(s).map { Studio.norm(it) }.filter { it.length >= 4 }.map { it.take(5) }.toSet()

    /**
     * Clip of every scene without the AI: a clip goes to the free scene that shares most words with its note,
     * the rest are spread evenly over the free scenes. Every clip is used once while there are free scenes.
     */
    fun autoAssign(scenes: List<SScene>, clips: List<SClip>): List<String> {
        val out = MutableList(scenes.size) { "" }
        if (scenes.isEmpty() || clips.isEmpty()) return out
        val sceneStems = scenes.map { stems(it.say + " " + it.title) }
        val rest = mutableListOf<SClip>()
        for (c in clips) {
            val cs = stems(c.note + " " + c.title)
            val best = scenes.indices.filter { out[it].isEmpty() }
                .map { it to sceneStems[it].count { s -> s in cs } }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            if (best != null) out[best.first] = c.id else rest += c
        }
        val free = scenes.indices.filter { out[it].isEmpty() }
        if (rest.isNotEmpty() && free.isNotEmpty()) {
            val n = minOf(rest.size, free.size)
            // the middle of n equal parts of the free scenes
            for (k in 0 until n) out[free[(2 * k + 1) * free.size / (2 * n)]] = rest[k].id
        }
        return out
    }

    /** Part of the director prompt about the downloaded clips (goes after the visuals); empty without clips. */
    fun promptBlock(clips: List<SClip>): String {
        val ready = clips.filter { it.file.isNotEmpty() && it.lenMs > 0 }
        if (ready.isEmpty()) return ""
        return " Ещё есть видеоклипы для вставок (B-roll и мемы-реакции): " +
            ready.joinToString("; ") { c ->
                val about = c.note.ifBlank { c.title }.replace('\n', ' ').trim().take(100)
                "${c.id} — «$about», ${(c.lenMs + 500) / 1000} с"
            } +
            ". Сцене можно дать поле \"clip\" с id клипа: тогда вместо графики на экране идёт этот клип, а голос продолжает рассказ. " +
            "Ставь клип туда, где он по смыслу иллюстрирует речь или смешно реагирует на неё; каждый клип 1-2 раза; " +
            "сцена с клипом обычно не длиннее клипа; между клипами — сцены с графикой."
    }

    // ---------- search by title ----------

    /** Numbered list of found videos for the AI filter prompt: "[1] title — channel, 3:20". */
    fun hitsBlock(hits: List<CutHit>): String = hits.withIndex().joinToString("\n") { (i, h) ->
        "[${i + 1}] " + h.title.replace('\n', ' ').trim().take(140) +
            (if (h.channel.isNotBlank()) " — " + h.channel.take(60) else "") +
            (if (h.durationMs > 0) ", " + stamp(h.durationMs) else "")
    }

    /**
     * The AI filter answer {"picks":[{"n":3,"note":"..."}]} → index in the list (0-based) to what is in the shot.
     * Numbers out of 1..[count] and repeats are dropped; null when it is not that JSON.
     */
    fun parsePicks(s: String, count: Int): Map<Int, String>? = runCatching {
        val t = s.trim().let { v -> v.substring(v.indexOf('{').coerceAtLeast(0), (v.lastIndexOf('}') + 1).coerceAtLeast(1)) }
        val arr = JSONObject(t).getJSONArray("picks")
        val out = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val n = o?.optInt("n", 0) ?: arr.optInt(i, 0)
            if (n !in 1..count || out.containsKey(n - 1)) continue
            out[n - 1] = o?.optString("note", "").orEmpty().replace('\n', ' ').trim().take(200)
        }
        out
    }.getOrNull()

    /**
     * Adds the marked videos as clips from [fromMs] (default length); a video already in the list is skipped,
     * at most [MAX_CLIPS] in all. The note is the AI note or the title.
     */
    fun addHits(clips: List<SClip>, hits: List<CutHit>, notes: Map<String, String> = emptyMap(), fromMs: Long = 0): List<SClip> {
        val out = clips.toMutableList()
        val have = clips.map { it.url }.toMutableSet()
        for (h in hits) {
            if (out.size >= MAX_CLIPS) break
            if (h.url in have) continue
            have += h.url
            val (a, b) = span(fromMs, null)
            out += SClip(newId(out), h.url, a, b, notes[h.url].orEmpty().ifBlank { h.title.take(200) }, h.title)
        }
        return out
    }
}

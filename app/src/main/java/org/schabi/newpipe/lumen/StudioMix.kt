/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/*
 * Lumen Studio "Mix": pieces of downloaded YouTube clips shuffled so that two pieces of one clip never follow
 * each other, then edited on a storyboard and built as a video (optionally with a voice-over, schemes and subtitles).
 * Pure logic here; the screen is StudioMixActivity.
 */

/**
 * One element of the mix: [lenMs] of clip [clipId] from [fromMs] (time inside the downloaded piece, from its lead).
 * [marked] — ticked on the storyboard; [scheme] — info-scheme drawn over this element.
 */
data class MixItem(
    val clipId: String,
    val fromMs: Long,
    val lenMs: Long,
    val marked: Boolean = false,
    val scheme: SScheme? = null
) {
    /** Stable key for the storyboard: a clip piece is unique by clip and start. */
    val key: String get() = "$clipId@$fromMs"
}

object StudioMix {
    val PIECES = listOf(1_000L, 2_000L, 3_000L, 5_000L)
    const val DEFAULT_PIECE_MS = 2_000L
    /** A piece shorter than this is not worth a cut. */
    const val MIN_PIECE_MS = 500L

    /** Pieces of a clip of usable length [clipMs]: equal parts close to [pieceMs]. */
    fun pieces(clipId: String, clipMs: Long, pieceMs: Long): List<MixItem> {
        if (clipMs < MIN_PIECE_MS || pieceMs <= 0) return emptyList()
        val n = ((clipMs + pieceMs / 2) / pieceMs).coerceAtLeast(1)
        val len = clipMs / n
        return (0 until n).map { k -> MixItem(clipId, k * len, len) }
    }

    /**
     * All pieces of [clips] (id to usable length) in a random order where two pieces of one clip are never
     * neighbours, while that is possible: a clip with more than half of what is left goes first.
     */
    fun mix(clips: List<Pair<String, Long>>, pieceMs: Long, seed: Long): List<MixItem> {
        val rnd = Random(seed)
        val left = clips.associate { (id, ms) -> id to pieces(id, ms, pieceMs).shuffled(rnd).toMutableList() }
            .filterValues { it.isNotEmpty() }.toMutableMap()
        val out = mutableListOf<MixItem>()
        var last = ""
        while (left.isNotEmpty()) {
            val total = left.values.sumOf { it.size }
            val must = left.entries.firstOrNull { it.key != last && it.value.size * 2 > total }
            val pick = must?.key ?: left.keys.filter { it != last }.let { ok ->
                if (ok.isEmpty()) last
                else {
                    // weighted by what is left, so big clips do not pile up at the end
                    var r = rnd.nextInt(ok.sumOf { left.getValue(it).size })
                    ok.first { id -> r -= left.getValue(id).size; r < 0 }
                }
            }
            val list = left.getValue(pick)
            out += list.removeAt(list.lastIndex)
            if (list.isEmpty()) left.remove(pick)
            last = pick
        }
        return out
    }

    /** How many neighbours are pieces of the same clip. */
    fun repeats(items: List<MixItem>): Int = items.zipWithNext().count { (a, b) -> a.clipId == b.clipId }

    /** [items] with the element at [from] moved to [to]. */
    fun move(items: List<MixItem>, from: Int, to: Int): List<MixItem> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val out = items.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    fun removeMarked(items: List<MixItem>): List<MixItem> = items.filter { !it.marked }

    /** Elements of clips that are gone are dropped. */
    fun keepClips(items: List<MixItem>, ids: Set<String>): List<MixItem> = items.filter { it.clipId in ids }

    fun totalMs(items: List<MixItem>): Long = items.sumOf { it.lenMs }

    /** Elements one after another, as scenes of the video. */
    fun times(items: List<MixItem>, words: List<IntRange> = emptyList()): List<SceneTime> {
        var at = 0L
        return items.mapIndexed { i, item ->
            val st = SceneTime(i, at, at + item.lenMs, words.getOrElse(i) { IntRange.EMPTY })
            at += item.lenMs
            st
        }
    }

    /** Where each element's clip plays in the video. */
    fun slots(items: List<MixItem>, clips: Map<String, SClip>): List<ClipSlot> {
        var at = 0L
        return items.mapNotNull { item ->
            val start = at
            at += item.lenMs
            val c = clips[item.clipId]?.takeIf { it.lenMs > 0 } ?: return@mapNotNull null
            ClipSlot(start, start + item.lenMs, item.clipId, c.leadMs, c.lenMs, item.fromMs.coerceIn(0, c.lenMs - 1))
        }
    }

    /** Words of the voice-over on every element: the words that start inside it (the last one takes the rest). */
    fun wordsIn(words: List<SWord>, times: List<SceneTime>): List<IntRange> = times.mapIndexed { k, st ->
        val last = k == times.lastIndex
        val a = words.indexOfFirst { it.startMs >= st.startMs && (last || it.startMs < st.endMs) }
        if (a < 0) IntRange.EMPTY
        else {
            var b = a
            while (b + 1 < words.size && (last || words[b + 1].startMs < st.endMs)) b++
            a..b
        }
    }

    /** Elements that get subtitles or schemes: the marked ones when [onlyMarked], else all. */
    fun chosen(items: List<MixItem>, onlyMarked: Boolean): List<Int> = items.indices.filter { !onlyMarked || items[it].marked }

    /** Signature of what the video shows (order, pieces and frame): a built video is reused while it matches. */
    fun signature(items: List<MixItem>, format: String): String =
        format + "|" + items.joinToString(";") { "${it.key}+${it.lenMs}" }

    // ---------- prompt for another AI ----------

    /**
     * A ready prompt for an AI app on the phone that gets the built video: what it is and how to edit it.
     * [notes] — what is in each clip, by id.
     */
    fun montagePrompt(format: String, items: List<MixItem>, notes: Map<String, String>, totalMs: Long, ru: Boolean): String {
        val vertical = format == StudioAspect.PORTRAIT.id
        val fmt = if (ru) {
            if (vertical) "вертикальный 9:16 (YouTube Shorts / Instagram Stories / Reels)" else "горизонтальный 16:9 (YouTube)"
        } else {
            if (vertical) "vertical 9:16 (YouTube Shorts / Instagram Stories / Reels)" else "horizontal 16:9 (YouTube)"
        }
        var at = 0L
        val lines = items.take(60).map { item ->
            val line = StudioCuts.stamp(at) + "–" + StudioCuts.stamp(at + item.lenMs) + " " + item.clipId +
                (notes[item.clipId]?.takeIf { it.isNotBlank() }?.let { " «" + it.replace('\n', ' ').take(60) + "»" } ?: "")
            at += item.lenMs
            line
        }
        val more = if (items.size > 60) "\n…" else ""
        return if (ru) {
            "Это мой видео-микс: формат $fmt, длина ${StudioCuts.stamp(totalMs)}, ${items.size} кусков из чужих клипов, поверх моя озвучка.\n" +
                "Помоги смонтировать финальную версию:\n" +
                "1. Посмотри видео и послушай озвучку: где картинка не совпадает со смыслом речи, предложи переставить или заменить куски (таймкоды).\n" +
                "2. Предложи хук на первые 3 секунды и что вырезать (паузы, оговорки, затянутые места).\n" +
                "3. Дай план монтажа по таймкодам: склейки, зумы, переходы, текст на экране, звуки, музыка и её громкость под голосом.\n" +
                "4. Напиши субтитры с таймкодами (SRT), название, описание и 5–10 хэштегов.\n" +
                "Сохраняй формат кадра. Отвечай по-русски.\n\nРаскадровка:\n" + lines.joinToString("\n") + more
        } else {
            "This is my video mix: $fmt, ${StudioCuts.stamp(totalMs)} long, ${items.size} pieces of other people's clips with my voice-over on top.\n" +
                "Help me edit the final version:\n" +
                "1. Watch the video and listen to the voice-over: where the picture does not match the speech, suggest moving or replacing pieces (timecodes).\n" +
                "2. Suggest a hook for the first 3 seconds and what to cut (pauses, slips, slow parts).\n" +
                "3. Give an edit plan by timecodes: cuts, zooms, transitions, on-screen text, sounds, music and its level under the voice.\n" +
                "4. Write subtitles with timecodes (SRT), a title, a description and 5–10 hashtags.\n" +
                "Keep the frame format.\n\nStoryboard:\n" + lines.joinToString("\n") + more
        }
    }

    // ---------- saved state ----------

    fun toJson(items: List<MixItem>): String = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("clip", it.clipId).put("from", it.fromMs).put("len", it.lenMs).put("on", it.marked).apply {
                it.scheme?.let { s -> put("scheme", StudioScheme.toJson(s)) }
            })
        }
    }.toString()

    fun fromJson(s: String): List<MixItem> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("clip").trim()
            val len = o.optLong("len")
            if (id.isEmpty() || len <= 0) return@mapNotNull null
            MixItem(id, o.optLong("from").coerceAtLeast(0), len, o.optBoolean("on"), o.optJSONObject("scheme")?.let { StudioScheme.fromJson(it) })
        }
    }.getOrDefault(emptyList())

    fun wordsJson(words: List<SWord>): String = JSONArray().apply {
        words.forEach { put(JSONArray().put(it.text).put(it.startMs).put(it.endMs)) }
    }.toString()

    fun wordsFromJson(s: String): List<SWord> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val w = a.optJSONArray(i) ?: return@mapNotNull null
            SWord(w.optString(0), w.optLong(1), w.optLong(2)).takeIf { it.text.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

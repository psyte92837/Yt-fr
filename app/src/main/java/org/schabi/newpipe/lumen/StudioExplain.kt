/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Style "Разбор" (StudioStyle.EXPLAIN): a modern explainer — graphite screen, schemes on cards, the voice-over's
 * questions become numbered chapters with a progress tag on top, short captions (1–3 words) with the spoken word
 * in the accent colour, a whoosh on every cut and a low hit on every chapter. Pure logic; drawing is in StudioPainter.
 */
object StudioExplain {
    const val MAX_CHAPTERS = 5
    /** Chapters closer than this to each other (or to the start) are not opened. */
    const val MIN_GAP_MS = 12_000L
    const val MIN_SCENE_MS = 1_200L
    /** Words of a caption group. */
    const val CAPTION_WORDS = 3
    const val CAPTION_CHARS = 18

    /**
     * Scenes that open a chapter: questions of the voice-over ("Откуда берутся такие авто?") after the hook,
     * long enough to read and spread over the video; fewer than two questions make no chapters at all.
     */
    fun chapters(scenes: List<SScene>, times: List<SceneTime>): List<Int> {
        val out = mutableListOf<Int>()
        var last = 0L
        for (st in times) {
            val i = st.scene
            if (i <= 0 || out.size >= MAX_CHAPTERS) continue
            val say = scenes.getOrNull(i)?.say?.trim() ?: continue
            if (!say.endsWith("?") || Studio.tokens(say).size < 2) continue
            if (st.endMs - st.startMs < MIN_SCENE_MS || st.startMs - last < MIN_GAP_MS) continue
            out += i
            last = st.startMs
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** Number of the chapter (0-based) going on at [tMs], or −1 before the first one. */
    fun chapterAt(chapters: List<Int>, times: List<SceneTime>, tMs: Long): Int =
        chapters.indexOfLast { c -> times.firstOrNull { it.scene == c }?.let { it.startMs <= tMs } == true }

    /** Title of a chapter card in capitals: at most [maxWords] words in up to 3 lines of [lineChars]. */
    fun title(say: String, maxWords: Int = 7, lineChars: Int = 14): List<String> {
        val all = Studio.tokens(say).map { it.trim('.', ',', ';', ':', '—', '-', '!', '?', '«', '»', '"') }.filter { it.isNotEmpty() }
        if (all.isEmpty()) return emptyList()
        var words = all.take(maxWords)
        var lines = wrap(words, lineChars)
        while (lines.size > 3 && words.size > 2) {
            words = words.dropLast(1)
            lines = wrap(words, lineChars)
        }
        // a cut title does not end on «и», «в», «они»…
        if (words.size < all.size) {
            val kept = words.dropLastWhile { StudioWords.isStop(it) }
            if (kept.isNotEmpty() && kept.size < words.size) {
                words = kept
                lines = wrap(words, lineChars)
            }
        }
        val q = if (say.trim().endsWith("?")) "?" else if (words.size < all.size) "…" else ""
        return lines.mapIndexed { i, l -> if (i == lines.lastIndex) l + q else l }.map { it.uppercase() }
    }

    private fun wrap(words: List<String>, lineChars: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            cur = if (cur.isEmpty()) w else if (cur.length + 1 + w.length <= lineChars) "$cur $w" else {
                out += cur
                w
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Short name of a chapter for the tag on top: its first two meaningful words in capitals. */
    fun label(say: String): String = Studio.tokens(say).map { StudioWords.clean(it) }.filter { it.isNotEmpty() && !StudioWords.isStop(it) }
        .take(2).joinToString(" ").uppercase().take(22)

    /**
     * Big words of a scene without a scheme (instead of an empty screen): the key word and the meaningful word next
     * to it, one per line, in speech order; second = the line of the key word (drawn in the accent colour).
     */
    fun headline(words: List<SWord>, range: IntRange): Pair<List<String>, Int> {
        if (range.isEmpty()) return emptyList<String>() to -1
        val key = StudioWords.keyWord(words, range) ?: return emptyList<String>() to -1
        fun ok(i: Int) = i in range && !StudioWords.isStop(words[i].text) && StudioWords.clean(words[i].text).isNotEmpty()
        val before = (key - 1 downTo range.first).firstOrNull { ok(it) && key - it <= 2 }
        val after = (key + 1..range.last).firstOrNull { ok(it) && it - key <= 2 }
        val pair = when {
            before != null -> listOf(before, key)
            after != null -> listOf(key, after)
            else -> listOf(key)
        }
        val keep = pair.filter { StudioWords.clean(words[it].text).isNotEmpty() }
        return keep.map { StudioWords.clean(words[it].text).uppercase() } to keep.indexOf(key)
    }

    /** Sounds of the style as (start ms, name): a whoosh on every cut, a boom when a chapter opens. */
    fun sfx(times: List<SceneTime>, chapters: List<Int>): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        times.forEachIndexed { i, st ->
            if (i == 0) return@forEachIndexed
            out += StudioSfx.startOf("whoosh", st.startMs) to "whoosh"
            if (st.scene in chapters) out += StudioSfx.startOf("boom", st.startMs + 60) to "boom"
        }
        return out
    }

    /** [own] effects plus the style's [extra] ones, without two effects closer than [gapMs]. */
    fun merge(own: List<Pair<Long, String>>, extra: List<Pair<Long, String>>, gapMs: Long = 150): List<Pair<Long, String>> =
        (own + extra.filter { (ms, name) -> own.none { kotlin.math.abs(it.first - ms) < gapMs && (it.second == name || name == "whoosh") } })
            .sortedBy { it.first }
}

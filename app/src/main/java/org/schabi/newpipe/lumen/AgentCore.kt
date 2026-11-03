/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Pure logic behind the Android AppFunctions entry point ([LumenAgentServiceBase]):
 * how much text an on-device agent (Gemini etc.) gets and which videos match its query.
 */
object AgentCore {
    /** Default and hard caps for a text answer: agents pass it to a model with a finite context. */
    const val TEXT_DEFAULT = 60_000
    const val TEXT_MAX = 400_000
    const val TRANSCRIPT_MAX = 20_000
    const val INSIGHT_MAX = 8_000

    /** [n] or [def] when absent/non-positive, never above [max]. */
    fun limit(n: Int?, def: Int, max: Int): Int = if (n == null || n <= 0) def else n.coerceAtMost(max)

    /** Cuts [text] to [max] chars on a line (or word) boundary and says how much was dropped. */
    fun clip(text: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        var cut = text.lastIndexOf('\n', max)
        if (cut < max / 2) cut = text.lastIndexOf(' ', max)
        if (cut < max / 2) cut = max
        val dropped = text.length - cut
        return text.substring(0, cut).trimEnd() + "\n\n… [truncated: $dropped more characters]"
    }

    private val splitter = Regex("[^\\p{L}\\p{N}#]+")

    fun terms(query: String?): List<String> =
        query.orEmpty().lowercase().split(splitter).filter { it.length >= 2 }.distinct()

    /** Relevance of a video to the query terms: title 3, tags 2, channel 1, description 1 per term. */
    fun score(v: Video, terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val title = v.title.lowercase()
        val channel = v.channel.lowercase()
        val tags = v.tags.joinToString(" ").lowercase()
        val desc = v.description.lowercase()
        var s = 0.0
        for (t in terms) {
            if (t in title) s += 3
            if (t in tags) s += 2
            if (t in channel) s += 1
            if (t in desc) s += 1
        }
        return s
    }

    /** Videos for an agent: by relevance to [query] (ties by views), or the most viewed without one. */
    fun findVideos(videos: Collection<Video>, query: String?, limit: Int): List<Video> {
        val terms = terms(query)
        val n = limit.coerceIn(1, 100)
        if (terms.isEmpty()) return videos.sortedByDescending { it.views }.take(n)
        return videos.map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Video, Double>> { it.second }.thenByDescending { it.first.views })
            .take(n)
            .map { it.first }
    }

    /** The most liked top-level comments as "text (N likes)". */
    fun topComments(list: List<Comment>, n: Int = 10, maxChars: Int = 500): List<String> =
        list.filter { !it.isReply && it.text.isNotBlank() }
            .sortedByDescending { it.likes }
            .take(n)
            .map { clip(it.text.trim(), maxChars) + if (it.likes > 0) " (${it.likes} likes)" else "" }
}

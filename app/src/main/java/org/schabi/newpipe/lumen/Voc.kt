/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Local (free, no AI) Voice-of-Customer detection in comments and transcripts.
 * Pure functions, covered by unit tests. These are signals for hypotheses, not proof of search demand.
 */
object Voc {
    const val QUESTION = "question"
    const val PAIN = "pain"
    const val REQUEST = "request"
    const val OBJECTION = "objection"
    val categories = listOf(QUESTION, PAIN, REQUEST, OBJECTION)

    private val questionStart = listOf(
        "как ", "как?", "что ", "почему", "зачем", "где ", "когда ", "сколько", "какой", "какая", "какие", "каким",
        "можно ли", "стоит ли", "подскажите", "кто знает", "кто-нибудь", "а если", "есть ли", "нужно ли", "чем ",
        "how ", "what ", "why ", "where ", "when ", "which ", "is it", "is there", "can i", "can you", "should i",
        "does ", "do you", "anyone know", "any tips"
    )
    private val pain = listOf(
        "проблем", "не работает", "не могу", "не получается", "не получилось", "ошибк", "сломал", "сломан", "глюч",
        "тормоз", "бесит", "надоел", "ужасн", "кошмар", "не помог", "разочар", "мучаюсь", "страдаю", "отвалил",
        "не включается", "не запускается", "вылетает", "зависает", "потерял", "обманул", "болит", "трудно", "сложно",
        "problem", "issue", "doesn't work", "does not work", "not working", "broken", "bug", "error", "hate",
        "annoying", "frustrat", "struggl", "can't", "cannot", "crash", "stuck", "failed"
    )
    private val request = listOf(
        "сделайте", "сделай видео", "сними", "снимите", "расскажите", "расскажи про", "хотелось бы", "хочу увидеть",
        "было бы круто", "было бы здорово", "ждём", "жду видео", "можно обзор", "сделайте обзор", "покажите", "добавьте",
        "please make", "can you make", "do a video", "would love", "next video", "tutorial on", "please do", "make a video"
    )
    private val objection = listOf(
        "дорого", "не стоит", "развод", "не верю", "сомневаюсь", "обман", "переплат", "зачем платить", "не доверяю",
        "лохотрон", "реклама", "проплачен", "лучше купить", "не окупится", "scam", "overpriced", "not worth",
        "too expensive", "don't trust", "sponsored", "rip off", "ripoff"
    )

    fun classify(text: String): Set<String> {
        val s = " " + text.lowercase().replace('ё', 'е') + " "
        val out = HashSet<String>()
        val trimmed = s.trim()
        if (trimmed.endsWith("?") || questionStart.any { trimmed.startsWith(it) } || Regex("""[^.!?]{8,}\?""").containsMatchIn(s)) out.add(QUESTION)
        if (pain.any { s.contains(it) }) out.add(PAIN)
        if (request.any { s.contains(it) }) out.add(REQUEST)
        if (objection.any { s.contains(it) }) out.add(OBJECTION)
        return out
    }

    /** Normalisation for exact-duplicate detection. */
    fun normalize(text: String): String = text.lowercase().replace('ё', 'е')
        .replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ").trim()

    /** Spam / noise: links, very short, mostly non-letters, repeated characters. */
    fun isSpam(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8) return true
        if (Regex("""https?://|t\.me/|wa\.me/|telegram|@\w+bot""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        val letters = t.count { it.isLetter() }
        if (letters < t.length * 0.4) return true
        if (Regex("""(.)\1{6,}""").containsMatchIn(t)) return true
        return false
    }

    data class Item(
        val category: String,
        val text: String,
        val sourceId: String,
        val commentIndex: Int, // -1 for transcript
        val timestamp: String?,
        val likes: Int,
        val author: String,
        var count: Int = 1,
        val authors: MutableSet<String> = mutableSetOf()
    )

    data class Msg(val sourceId: String, val text: String, val likes: Int, val author: String, val commentIndex: Int, val timestamp: String?)

    /** Groups exact duplicates; the representative is the most liked message. */
    fun extract(messages: List<Msg>): List<Item> {
        val groups = LinkedHashMap<String, Item>()
        for (m in messages) {
            if (isSpam(m.text)) continue
            val norm = normalize(m.text)
            if (norm.isEmpty()) continue
            for (cat in classify(m.text)) {
                val key = "$cat|$norm"
                val g = groups[key]
                if (g == null) {
                    groups[key] = Item(cat, m.text.trim(), m.sourceId, m.commentIndex, m.timestamp, m.likes, m.author).also {
                        if (m.author.isNotEmpty()) it.authors.add(m.author)
                    }
                } else {
                    g.count++
                    if (m.author.isNotEmpty()) g.authors.add(m.author)
                    if (m.likes > g.likes) {
                        groups[key] = g.copy(text = m.text.trim(), sourceId = m.sourceId, commentIndex = m.commentIndex, likes = m.likes, timestamp = m.timestamp)
                    }
                }
            }
        }
        return groups.values.sortedWith(compareByDescending<Item> { it.count }.thenByDescending { it.likes })
    }

    /** Splits a "[mm:ss] text" transcript into sentence messages with timestamps. */
    fun transcriptMessages(sourceId: String, transcript: String): List<Msg> {
        val out = mutableListOf<Msg>()
        var ts: String? = null
        val sb = StringBuilder()
        fun flush() {
            Regex("""(?<=[.!?])\s+""").split(sb.toString()).map { it.trim() }.filter { it.length > 12 }.forEach {
                out.add(Msg(sourceId, it, 0, "", -1, ts))
            }
            sb.clear()
        }
        for (line in transcript.lineSequence()) {
            val m = Regex("""^\[([0-9:]+)]\s*(.*)$""").find(line.trim())
            if (m != null) {
                if (sb.length > 400) flush()
                if (sb.isEmpty()) ts = m.groupValues[1]
                sb.append(m.groupValues[2]).append(' ')
            } else {
                sb.append(line).append(' ')
            }
        }
        flush()
        return out
    }

    /** Is the quote (as returned by an LLM) really present in the source text? */
    fun quoteFound(quote: String, source: String): Boolean {
        val q = normalize(quote)
        if (q.length < 4) return false
        val s = normalize(source)
        if (s.contains(q)) return true
        // tolerate small edits: 80 % of the quote's words appear in order-insensitive window
        val qw = q.split(' ').filter { it.length > 2 }
        if (qw.size < 3) return false
        val sw = s.split(' ').toHashSet()
        return qw.count { it in sw } >= qw.size * 0.8
    }

    /** Word-set Jaccard similarity (for duplicate topics and cannibalisation checks). */
    fun similarity(a: String, b: String): Double {
        val x = Relevance.keywords(a)
        val y = Relevance.keywords(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = x.count { it in y }
        return inter.toDouble() / (x.size + y.size - inter)
    }

    fun timestampSeconds(ts: String?): Int? {
        if (ts.isNullOrBlank()) return null
        val parts = ts.trim().split(':').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return parts.fold(0) { acc, p -> acc * 60 + p }
    }
}

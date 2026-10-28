/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/** Pure text helpers (no Android dependencies, covered by unit tests). */
object JsonText {
    /** Extracts the first top-level JSON object from an LLM reply (handles ```json fences and prose). */
    fun firstObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

/** Recovers the complete objects of an array from a JSON reply cut off by the token limit. */
object JsonSalvage {
    /**
     * Returns {"<key>": [complete objects…]} or null when nothing valid was found.
     * Malformed elements (e.g. `{"statement","x"}`) are skipped, so the result always parses.
     */
    fun array(text: String, key: String): String? {
        val k = text.indexOf("\"$key\"")
        if (k < 0) return null
        val open = text.indexOf('[', k)
        if (open < 0) return null
        val items = mutableListOf<String>()
        var i = open + 1
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            val obj = JsonText.firstObject(text.substring(start)) ?: break
            if (runCatching { org.json.JSONObject(obj) }.isSuccess) items.add(obj)
            i = start + obj.length
        }
        if (items.isEmpty()) return null
        return "{\"$key\":[" + items.joinToString(",") + "]}"
    }
}

object Vtt {
    private val timeLine = Regex("""^(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s+-->\s+.*$""")
    private val tag = Regex("<[^>]+>")

    /**
     * Converts WebVTT to readable lines "[mm:ss] text". Removes styling tags and the
     * rolling duplicates typical for YouTube auto-captions.
     */
    fun toText(vtt: String, withTimecodes: Boolean = true): String {
        val out = StringBuilder()
        var currentTime = ""
        var last = ""
        val seen = ArrayDeque<String>()
        for (raw in vtt.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line == "WEBVTT" || line.startsWith("Kind:") ||
                line.startsWith("Language:") || line.startsWith("NOTE") || line.startsWith("STYLE")
            ) {
                continue
            }
            if (timeLine.matches(line)) {
                currentTime = line.substringBefore("-->").trim().let { tc ->
                    val parts = tc.substringBefore('.').substringBefore(',').split(':')
                    if (parts.size == 3 && parts[0] != "00") "${parts[0].toInt()}:${parts[1]}:${parts[2]}" else "${parts[parts.size - 2]}:${parts.last()}"
                }
                continue
            }
            if (line.all { it.isDigit() }) continue // cue number
            val text = tag.replace(line, "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"").trim()
            if (text.isEmpty() || text == last || seen.contains(text)) continue
            seen.addLast(text)
            if (seen.size > 4) seen.removeFirst()
            last = text
            if (withTimecodes && currentTime.isNotEmpty()) out.append('[').append(currentTime).append("] ")
            out.append(text).append('\n')
        }
        return out.toString().trim()
    }

    fun stripTimecodes(s: String): String = s.lineSequence()
        .map { it.replace(Regex("""^\[[0-9:]+]\s*"""), "") }
        .joinToString(" ")
}

object Words {
    private val token = Regex("""[\p{L}\p{Nd}][\p{L}\p{Nd}'’-]*""")

    val stop: Set<String> = (
        "и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было вот от " +
            "меня еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь опять уж вам " +
            "ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего " +
            "раз тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь этом один почти мой тем чтобы " +
            "нее сейчас были куда зачем всех никогда можно при наконец два об другой хоть после над больше тот через эти нас " +
            "про всего них какая много разве три эту моя впрочем хорошо свою этой перед иногда лучше чуть том нельзя такой им " +
            "более всегда конечно всю между это эта которые который которая также очень просто свой своей своих наш наши " +
            "the a an and or but if then so of to in on at by for with from as is are was were be been being it its this that " +
            "these those i you he she we they them his her our your my me us not no yes do does did done have has had will would " +
            "can could should may might must about into over after before more most some any all just than too very also how what " +
            "when where who why which there here out up down only own same such both each few other new video videos youtube " +
            "http https www com ru subscribe channel shorts"
        ).split(' ').filter { it.isNotBlank() }.toSet()

    fun tokens(text: String, minLen: Int = 3): List<String> =
        token.findAll(text.lowercase()).map { it.value.trim('\'', '’', '-') }
            .filter { it.length >= minLen && it !in stop && !it.all { c -> c.isDigit() } }
            .toList()

    fun top(texts: Sequence<String>, n: Int = 40): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        texts.forEach { t -> tokens(t).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        return freq.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    fun topBigrams(texts: Sequence<String>, n: Int = 25): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        texts.forEach { t ->
            val tk = tokens(t)
            for (i in 0 until tk.size - 1) {
                if (tk[i] == tk[i + 1]) continue
                val b = tk[i] + " " + tk[i + 1]
                freq[b] = (freq[b] ?: 0) + 1
            }
        }
        return freq.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }
}

object Chunks {
    /** Splits text into chunks of at most [max] chars, preferring line/sentence boundaries. */
    fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + max, text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                val dot = text.lastIndexOf(". ", end)
                val cut = maxOf(nl, dot)
                if (cut > i + max / 2) end = cut + 1
            }
            out.add(text.substring(i, end).trim())
            i = end
        }
        return out.filter { it.isNotEmpty() }
    }
}

object Csv {
    fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    fun row(vararg v: Any?): String = v.joinToString(",") { cell(it) } + "\r\n"

    /** RFC 4180 parser: quoted fields, doubled quotes, newlines inside quotes. */
    fun parse(text: String, sep: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        val s = text.removePrefix("\uFEFF")
        while (i < s.length) {
            val ch = s[i]
            if (q) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        q = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> q = true
                    sep -> {
                        row.add(cell.toString())
                        cell.clear()
                    }
                    '\r' -> {}
                    '\n' -> {
                        row.add(cell.toString())
                        cell.clear()
                        if (row.any { it.isNotEmpty() }) rows.add(row)
                        row = mutableListOf()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }
}

object YtQuery {
    private const val DAY = 86_400_000L

    /** Earliest upload time allowed by a period filter, or -1 for "any"/custom. */
    fun periodStart(period: String, now: Long): Long = when (period) {
        "hour" -> now - 3_600_000L
        "today" -> now - DAY
        "week" -> now - 7 * DAY
        "month" -> now - 30 * DAY
        "year" -> now - 365 * DAY
        else -> -1L
    }

    fun videoIdFromUrl(url: String): String? {
        Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(url)?.let { return it.groupValues[1] }
        Regex("""(?:youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    fun durationOk(filter: String, sec: Long): Boolean = when (filter) {
        "short" -> sec in 1..239
        "medium" -> sec in 240..1200
        "long" -> sec > 1200
        else -> true
    }
}

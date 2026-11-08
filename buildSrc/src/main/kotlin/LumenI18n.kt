/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Lumen Studio localisation at build time (protocol lumen.i18n v1).
 *
 * The shared sources write their strings as t("русский", "English"). For Lumen Studio the copy task
 * ([rewrite]) turns every call whose English text has templates ("$x", "${expr}") into
 * tf("русский", "English {0}", x): the key is the English text with {N} placeholders, so a catalogue
 * (studio/src/main/assets/lumen_i18n/<lang>.json, made by tools/i18n) can hold one translation for it.
 * Plain calls stay t(ru, en): the runtime looks the English text up as is.
 * [keys] gives the same keys for the extractor (tools/i18n/Extract.kt), so both always agree.
 * Calls it cannot read (concatenation, raw strings) are left alone and listed by [scan] as skipped.
 */
object LumenI18n {
    /** [raw] is the source of the argument, quotes included. */
    class Lit(val raw: String, val key: String, val args: List<String>)
    class Call(val start: Int, val end: Int, val ru: Lit, val en: Lit)
    class Scan(val calls: List<Call>, val skipped: List<Int>)

    /** All t("…", "…") calls of [src]. */
    fun scan(src: String): Scan {
        val calls = ArrayList<Call>()
        val skipped = ArrayList<Int>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) n else it }
                src.startsWith("/*", i) -> i = skipBlock(src, i)
                src.startsWith("\"\"\"", i) -> i = skipRaw(src, i)
                c == '"' -> i = literal(src, i)?.second ?: n
                c == '\'' -> i = skipChar(src, i)
                c == 't' && isCall(src, i) -> {
                    val call = call(src, i)
                    if (call == null) {
                        skipped.add(i)
                        i++
                    } else {
                        calls.add(call)
                        i = call.end
                    }
                }
                isIdent(c) -> {
                    var j = i
                    while (j < n && isIdent(src[j])) j++
                    i = j
                }
                else -> i++
            }
        }
        return Scan(calls, skipped)
    }

    /** The source with templated calls rewritten to tf(ru, "key", args…). */
    fun rewrite(src: String): String {
        val calls = scan(src).calls.filter { it.en.args.isNotEmpty() }
        if (calls.isEmpty()) return src
        val sb = StringBuilder(src.length + calls.size * 16)
        var last = 0
        for (c in calls) {
            sb.append(src, last, c.start)
            sb.append("tf(").append(c.ru.raw).append(", \"").append(escape(c.en.key)).append('"')
            for (a in c.en.args) sb.append(", (").append(a).append(')')
            sb.append(')')
            last = c.end
        }
        sb.append(src, last, src.length)
        return sb.toString()
    }

    /** Keys (English with {N}) and the Russian text of each readable call, in source order. */
    fun keys(src: String): List<Pair<String, String>> = scan(src).calls.map { it.en.key to it.ru.key }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun isCall(src: String, i: Int): Boolean {
        if (i > 0 && (isIdent(src[i - 1]) || src[i - 1] == '.' || src[i - 1] == '`')) return false
        if (i + 1 < src.length && isIdent(src[i + 1])) return false
        var j = i + 1
        while (j < src.length && src[j] == ' ') j++
        if (j >= src.length || src[j] != '(') return false
        // not the definition "fun t("
        val before = src.substring(maxOf(0, i - 4), i)
        return before != "fun "
    }

    private fun ws(src: String, from: Int): Int {
        var j = from
        while (j < src.length && src[j].isWhitespace()) j++
        return j
    }

    private fun call(src: String, at: Int): Call? {
        var j = ws(src, src.indexOf('(', at) + 1)
        val (ru, a) = arg(src, j) ?: return null
        j = ws(src, a)
        if (j >= src.length || src[j] != ',') return null
        val (en, b) = arg(src, ws(src, j + 1)) ?: return null
        j = ws(src, b)
        if (j < src.length && src[j] == ',') j = ws(src, j + 1)
        if (j >= src.length || src[j] != ')') return null
        return Call(at, j + 1, ru, en)
    }

    /** "…" or "…" + "…" + …: one key, the arguments numbered across the parts; raw = the source text. */
    private fun arg(src: String, at: Int): Pair<Lit, Int>? {
        var j = at
        val key = StringBuilder()
        val args = ArrayList<String>()
        while (true) {
            if (j >= src.length || src[j] != '"' || src.startsWith("\"\"\"", j)) return null
            val (lit, e) = literal(src, j, args.size) ?: return null
            key.append(lit.key)
            args.addAll(lit.args)
            val k = ws(src, e)
            if (k < src.length && src[k] == '+') j = ws(src, k + 1)
            else return Lit(src.substring(at, e), key.toString(), args) to e
        }
    }

    /** Reads the "…" literal starting at [at]; returns it and the index after its closing quote. */
    fun literal(src: String, at: Int, base: Int = 0): Pair<Lit, Int>? {
        val key = StringBuilder()
        val args = ArrayList<String>()
        var j = at + 1
        while (j < src.length) {
            val c = src[j]
            when {
                c == '"' -> return Lit(src.substring(at + 1, j), key.toString(), args) to j + 1
                c == '\n' -> return null
                c == '\\' && j + 1 < src.length -> {
                    val e = src[j + 1]
                    when (e) {
                        'n' -> key.append('\n')
                        't' -> key.append('\t')
                        'r' -> key.append('\r')
                        'b' -> key.append('\b')
                        'u' -> {
                            val hex = src.substring(j + 2, minOf(src.length, j + 6))
                            key.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            j += 4
                        }
                        else -> key.append(e)
                    }
                    j += 2
                }
                c == '$' && j + 1 < src.length && src[j + 1] == '{' -> {
                    val end = exprEnd(src, j + 2) ?: return null
                    key.append('{').append(base + args.size).append('}')
                    args.add(src.substring(j + 2, end).trim())
                    j = end + 1
                }
                c == '$' && j + 1 < src.length && (src[j + 1].isLetter() || src[j + 1] == '_') -> {
                    var k = j + 1
                    while (k < src.length && isIdent(src[k])) k++
                    key.append('{').append(base + args.size).append('}')
                    args.add(src.substring(j + 1, k))
                    j = k
                }
                else -> {
                    key.append(c)
                    j++
                }
            }
        }
        return null
    }

    /** Index of the '}' closing a ${…} template whose body starts at [from]. */
    private fun exprEnd(src: String, from: Int): Int? {
        var depth = 0
        var j = from
        while (j < src.length) {
            val c = src[j]
            when {
                src.startsWith("\"\"\"", j) -> j = skipRaw(src, j)
                c == '"' -> j = literal(src, j)?.second ?: return null
                c == '\'' -> j = skipChar(src, j)
                c == '{' -> { depth++; j++ }
                c == '}' -> if (depth == 0) return j else { depth--; j++ }
                else -> j++
            }
        }
        return null
    }

    private fun skipBlock(src: String, at: Int): Int {
        var depth = 0
        var j = at
        while (j < src.length) {
            if (src.startsWith("/*", j)) { depth++; j += 2 }
            else if (src.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) return j }
            else j++
        }
        return src.length
    }

    private fun skipRaw(src: String, at: Int): Int {
        val end = src.indexOf("\"\"\"", at + 3)
        if (end < 0) return src.length
        var j = end + 3
        while (j < src.length && src[j] == '"') j++
        return j
    }

    private fun skipChar(src: String, at: Int): Int {
        var j = at + 1
        if (j < src.length && src[j] == '\\') j += 2 else j++
        while (j < src.length && src[j] != '\'' && src[j] != '\n') j++
        return j + 1
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '\r' -> sb.append("\\r")
            '\b' -> sb.append("\\b")
            '$' -> sb.append("\\$")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}

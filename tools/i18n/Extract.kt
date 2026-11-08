/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Keys of the Lumen Studio catalogue: reads studio/shared-files.txt (+ studio/src) with the build's own
// LumenI18n and writes tools/i18n/keys.json ({"key": "русский"}). Run by tools/i18n/kt.sh.
import java.io.File

fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "." })
    val lumen = File(root, "app/src/main/java/org/schabi/newpipe/lumen")
    val names = File(root, "studio/shared-files.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.endsWith(".kt") }
    val files = names.map { File(lumen, it) } + (File(root, "studio/src/main/java").walk().filter { it.name.endsWith(".kt") }.toList())
    val keys = LinkedHashMap<String, String>()
    var skipped = 0
    val skipList = StringBuilder()
    for (f in files) {
        val src = f.readText()
        val s = LumenI18n.scan(src)
        skipped += s.skipped.size
        for (at in s.skipped) skipList.append(f.name).append(':').append(src.substring(0, at).count { it == '\n' } + 1).append(' ')
            .append(src.substring(at, minOf(src.length, at + 70)).replace('\n', ' ')).append('\n')
        for (c in s.calls) keys.putIfAbsent(c.en.key, c.ru.key)
    }
    val out = StringBuilder("{\n")
    keys.entries.forEachIndexed { i, (k, v) ->
        out.append("  ").append(json(k)).append(": ").append(json(v)).append(if (i < keys.size - 1) ",\n" else "\n")
    }
    out.append("}\n")
    File(root, "tools/i18n/keys.json").writeText(out.toString())
    File(args.getOrElse(1) { "/dev/null" }).writeText(skipList.toString())
    println("keys ${keys.size}, skipped calls $skipped")
}

fun json(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\t' -> sb.append("\\t")
        c == '\r' -> sb.append("\\r")
        c < ' ' -> sb.append(String.format("\\u%04x", c.code))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}

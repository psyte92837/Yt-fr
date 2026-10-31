/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/** Cleans what the user pasted into an API-key field (a bare key, a header line or a whole curl example). */
object KeyText {
    private val header = Regex("""(?i)(?:x-goog-api-key|x-api-key|api-key|authorization)\s*:\s*(?:bearer\s+)?([^\s'"\\]+)""")
    private val query = Regex("""[?&]key=([^\s&'"\\]+)""")

    fun clean(raw: String): String {
        val s = raw.trim().trim('\'', '"')
        header.find(s)?.let { return it.groupValues[1] }
        query.find(s)?.let { return it.groupValues[1] }
        return s.removePrefix("Bearer ").filterNot { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' }
    }
}

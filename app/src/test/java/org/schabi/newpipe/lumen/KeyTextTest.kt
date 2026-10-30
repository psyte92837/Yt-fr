/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTextTest {
    @Test
    fun bareKeyIsKept() {
        assertEquals("AIzaSyABC-123_x", KeyText.clean("  AIzaSyABC-123_x\n"))
    }

    @Test
    fun curlExampleYieldsTheKeyFromHeader() {
        val curl = "curl \"https://generativelanguage.googleapis.com/v1beta/models/m:generateContent\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n  -H 'X-goog-api-key: AQ.Ab8RN6ITkSr9' \\\n  -X POST"
        assertEquals("AQ.Ab8RN6ITkSr9", KeyText.clean(curl))
    }

    @Test
    fun bearerAndQueryForms() {
        assertEquals("sk-123", KeyText.clean("Authorization: Bearer sk-123"))
        assertEquals("sk-123", KeyText.clean("Bearer sk-123"))
        assertEquals("K1", KeyText.clean("https://x/y?alt=sse&key=K1&z=2"))
    }

    @Test
    fun wrappedKeyLosesWhitespaceAndQuotes() {
        assertEquals("abcdef", KeyText.clean("\"abc\n def\""))
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUiTest {
    @Test
    fun addressKeepsLinksAndCompletesHosts() {
        assertEquals("https://www.youtube.com/watch?v=x", BrowserText.normalize(" https://www.youtube.com/watch?v=x "))
        assertEquals("http://192.168.1.5:11434", BrowserText.normalize("http://192.168.1.5:11434"))
        assertEquals("https://m.youtube.com/@channel", BrowserText.normalize("m.youtube.com/@channel"))
        assertEquals("https://example.org", BrowserText.normalize("example.org"))
        assertEquals(BrowserText.HOME, BrowserText.normalize("   "))
    }

    @Test
    fun addressSearchesPlainText() {
        assertEquals("https://duckduckgo.com/?q=%D1%8D%D0%BB%D0%B5%D0%BA%D1%82%D1%80%D0%BE%D0%BC%D0%BE%D0%B1%D0%B8%D0%BB%D0%B8+2026",
            BrowserText.normalize("электромобили 2026"))
        assertTrue(BrowserText.normalize("lumen").startsWith("https://duckduckgo.com/?q="))
        assertTrue(BrowserText.normalize("what is example.org").startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun durationFormat() {
        assertEquals("00:00", BrowserText.duration(-5))
        assertEquals("01:15", BrowserText.duration(75_000))
        assertEquals("1:02:05", BrowserText.duration(3_725_000))
    }

    @Test
    fun recordingFileNameIsSafe() {
        val at = 1_790_000_000_000L
        val n = BrowserText.fileName("A/B: \"C\"?*\n" + "x".repeat(100), at)
        assertTrue(n.startsWith("Lumen 20"))
        assertTrue(n.endsWith(".webm"))
        assertFalse(Regex("[\\\\/:*?\"<>|\n]").containsMatchIn(n.removeSuffix(".webm").substringAfter(" ").substringAfter(" ").substringAfter(" ")))
        assertTrue(n.length <= "Lumen 2026-10-28 14-05 ".length + 60 + 5)
        assertTrue(BrowserText.fileName("   ", at).matches(Regex("Lumen \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}\\.webm")))
        assertTrue(BrowserText.fileName("x", at, "m4a").endsWith(" x.m4a"))
    }

    @Test
    fun pageScriptResultIsUnquoted() {
        assertEquals("ok", TabRecJs.result("\"ok\""))
        assertEquals("none", TabRecJs.result(" \"none\" "))
        assertEquals("", TabRecJs.result(null))
        assertTrue(TabRecJs.START.contains("captureStream") && TabRecJs.START.contains("LumenRec.chunk"))
    }

    @Test
    fun appBarGlyphsHaveIconsAndLabels() {
        listOf("←", "⋮", "⚙", "🐞", "⤴", "↗", "⤢", "⧉", "🗑", "🧾", "🌐").forEach { g ->
            val ic = glyphIcon(g)
            assertTrue("no icon for $g", ic != null)
            assertFalse("label is the glyph for $g", glyphLabel(g) == g)
        }
        assertNull(glyphIcon("✨"))
        assertEquals("✨", glyphLabel("✨"))
    }

    @Test
    fun filtersSummaryListsOnlyNonDefaults() {
        assertEquals("до 50", filtersSummary(SearchFilters()))
        val f = SearchFilters(sort = "date", period = "week", duration = "long", excludeShorts = true, limit = 100)
        assertEquals("Сначала новые · Неделя · длинные · без Shorts · до 100", filtersSummary(f))
        assertEquals("2026-01-01…? · до 50", filtersSummary(SearchFilters(period = "custom", from = "2026-01-01")))
    }
}

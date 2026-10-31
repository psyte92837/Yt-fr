/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelpMapTest {
    @Test
    fun everyScreenHasAHelpTopic() {
        // every Lumen screen (subclass of LumenBase) must be listed, so its "?" opens its own section
        val dir = listOf("src/main/java/org/schabi/newpipe/lumen", "app/src/main/java/org/schabi/newpipe/lumen")
            .map(::File).first { it.isDirectory }
        val rx = Regex("""class (\w+)\s*(?:\([^)]*\))?\s*:\s*(?:LumenBase|TtsShareBase)\(""")
        val screens = dir.listFiles()!!.filter { it.name.endsWith(".kt") }
            .flatMap { f -> rx.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .filter { it != "TtsShareBase" }
        assertTrue(screens.size > 10)
        val missing = screens.filter { it !in HelpMap.knownScreens }
        assertTrue("no help topic for: $missing", missing.isEmpty())
        for (s in screens) assertTrue(HelpMap.topicFor(s) in HelpMap.ids)
    }

    @Test
    fun unknownScreenFallsBackToAbout() {
        assertEquals("about", HelpMap.topicFor("Nope"))
        assertEquals("studio", HelpMap.topicFor("StudioActivity"))
        assertEquals(HelpMap.ids.size, HelpMap.ids.toSet().size)
    }

    @Test
    fun everyTopicIdHasContent() {
        val ids = Help.topics().map { it.id }
        assertEquals(HelpMap.ids.toSet(), ids.toSet())
        assertTrue(Help.topic("bot_posts").steps.size >= 8)
    }
}

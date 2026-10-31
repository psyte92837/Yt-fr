/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StudioDraftsTest {

    @Test
    fun titleFromTopicOrScript() {
        assertEquals("Тема", StudioDrafts.title("  Тема ", "текст"))
        assertEquals("Первая строка", StudioDrafts.title("", "\n[icons] Первая строка\nвторая"))
        assertEquals("Привет", StudioDrafts.title("", "@Лиса(happy): Привет"))
        assertEquals("", StudioDrafts.title("", "  \n "))
    }

    @Test
    fun jsonRoundTrip() {
        val d = SDraft("abc", "cuts", "Тема", 123L, "сцена 1\n\nсцена 2", "Тема")
        assertEquals(d, StudioDrafts.fromJson(StudioDrafts.toJson(d)))
        assertNull(StudioDrafts.fromJson("{}"))
        assertNull(StudioDrafts.fromJson("oops"))
    }

    @Test
    fun stashListRestoreDelete() {
        val base = Files.createTempDirectory("drafts").toFile()
        val tdir = java.io.File(base, "cuts").apply { mkdirs() }
        val root = java.io.File(base, "drafts")
        // nothing to keep: an empty clips folder and no text
        java.io.File(tdir, "clips").mkdirs()
        assertFalse(StudioDrafts.hasWork("", " ", tdir))
        assertNull(StudioDrafts.stash(root, "cuts", tdir, "", "", 1L))

        java.io.File(tdir, "clips.json").writeText("[]")
        java.io.File(tdir, "clips/a.mp4").writeBytes(ByteArray(10))
        java.io.File(tdir, "music.wav").writeText("shared")
        val d = StudioDrafts.stash(root, "cuts", tdir, "сценарий", "", 1000L)
        assertNotNull(d)
        assertFalse(java.io.File(tdir, "clips.json").exists())
        assertFalse(java.io.File(tdir, "clips/a.mp4").exists())
        assertTrue("shared files stay", java.io.File(tdir, "music.wav").exists())
        assertFalse(StudioDrafts.hasWork("", "", tdir))

        val d2 = StudioDrafts.stash(root, "cuts", tdir, "второй", "Тема 2", 2000L)!!
        val list = StudioDrafts.list(root, "cuts")
        assertEquals(listOf(d2.id, d!!.id), list.map { it.id })
        assertEquals(2, StudioDrafts.count(root, "cuts"))
        assertTrue(list[1].bytes >= 10)
        assertEquals(0, StudioDrafts.count(root, "shorts"))

        StudioDrafts.restore(root, list[1], tdir)
        assertTrue(java.io.File(tdir, "clips.json").exists())
        assertEquals(10L, java.io.File(tdir, "clips/a.mp4").length())
        assertEquals(1, StudioDrafts.count(root, "cuts"))

        StudioDrafts.delete(root, list[0])
        assertEquals(0, StudioDrafts.count(root, "cuts"))
        base.deleteRecursively()
    }

    @Test
    fun sizeLabel() {
        assertEquals("1 KB", StudioDrafts.mb(10))
        assertEquals("2.0 MB", StudioDrafts.mb(2L * 1024 * 1024))
    }
}

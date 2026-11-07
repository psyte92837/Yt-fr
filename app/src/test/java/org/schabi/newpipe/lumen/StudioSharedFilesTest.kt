/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lumen Studio (module :studio) compiles the files listed in studio/shared-files.txt: they must exist and stay free of NewPipe. */
class StudioSharedFilesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "studio/shared-files.txt").exists() }
    private val dir = File(root, "app/src/main/java/org/schabi/newpipe/lumen")
    private val names = File(root, "studio/shared-files.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    private val own = File(root, "studio/src/main/java/org/schabi/newpipe/lumen").listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun listedFilesExistAndAreNotReplaced() {
        assertTrue(names.size > 50)
        for (n in names) {
            assertTrue("missing $n", File(dir, n).isFile)
            assertTrue("$n is both shared and replaced in studio/src", n !in own)
        }
    }

    @Test
    fun sharedFilesDoNotUseNewPipe() {
        val newPipe = Regex("""org\.schabi\.newpipe\.(?!lumen\b)[a-z]""")
        for (n in names) {
            val bad = File(dir, n).readLines().withIndex().firstOrNull { (_, l) -> newPipe.containsMatchIn(l) && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") }
            assertTrue("$n:${(bad?.index ?: 0) + 1} uses NewPipe: ${bad?.value?.trim()}", bad == null)
        }
    }
}

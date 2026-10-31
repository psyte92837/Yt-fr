/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentDigestTest {
    private fun c(text: String, likes: Int = 0) = Comment("", "u_1", text, likes, -1, "", 0, false)

    private val msgs = Pains.messages(listOf(
        "v1" to listOf(
            c("Я пробовал этот метод три месяца, итог — минус 5 кг", 30),
            c("Думаю, всё дело в генетике", 12),
            c("Сделайте видео про сон", 4)
        )
    ))

    @Test
    fun inputHasSourceCountAndIds() {
        val s = CommentDigest.input("Видео — Канал", msgs, 10_000)
        assertTrue(s.startsWith("Source: Видео — Канал\nComments: 3\n"))
        assertTrue(s.contains("[c1 ♥30]"))
        assertTrue(s.contains("c3"))
    }

    @Test
    fun chatDataPutsDigestFirstAndFallsBackWithoutIt() {
        val withDigest = CommentDigest.chatData("## Главное\n- суть", "V", msgs, 9000)
        assertTrue(withDigest.startsWith("Digest of the comments:\n## Главное"))
        assertTrue(withDigest.contains("[c1 ♥30]"))
        val without = CommentDigest.chatData(null, "V", msgs, 9000)
        assertFalse(without.contains("Digest"))
        assertTrue(without.startsWith("Source: V"))
    }

    @Test
    fun plainStripsMarkdown() {
        val md = "## главное\n**Важно**: всё\n\n\n\n- пункт (c1)\n* ещё"
        assertEquals("Главное\nВажно: всё\n\n• пункт (c1)\n• ещё", CommentDigest.plain(md))
    }
}

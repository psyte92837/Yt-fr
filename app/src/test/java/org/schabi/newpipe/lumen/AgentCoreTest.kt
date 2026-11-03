/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoreTest {
    private fun v(id: String, title: String, views: Long, channel: String = "", tags: List<String> = emptyList(), desc: String = "") =
        Video(id = id, url = "https://youtu.be/$id", title = title, channel = channel, views = views).apply {
            this.tags = tags.toMutableList()
            this.description = desc
        }

    @Test
    fun limitDefaultsAndCaps() {
        assertEquals(20, AgentCore.limit(null, 20, 100))
        assertEquals(20, AgentCore.limit(0, 20, 100))
        assertEquals(5, AgentCore.limit(5, 20, 100))
        assertEquals(100, AgentCore.limit(1000, 20, 100))
    }

    @Test
    fun clipKeepsShortAndCutsOnLine() {
        assertEquals("abc", AgentCore.clip("abc", 10))
        val text = "line one\nline two\nline three"
        val out = AgentCore.clip(text, 15)
        assertTrue(out.startsWith("line one\n") || out.startsWith("line one"))
        assertTrue(!out.contains("line three"))
        assertTrue(out.contains("truncated: "))
    }

    @Test
    fun findByRelevanceThenViews() {
        val list = listOf(
            v("a", "Cooking pasta at home", 100),
            v("b", "Random vlog", 9000, tags = listOf("pasta")),
            v("c", "Pasta carbonara recipe", 500),
            v("d", "Gardening", 50000),
        )
        assertEquals(listOf("c", "a", "b"), AgentCore.findVideos(list, "pasta", 10).map { it.id })
        assertEquals(listOf("d", "b"), AgentCore.findVideos(list, "", 2).map { it.id })
        assertEquals(listOf("c"), AgentCore.findVideos(list, "pasta", 1).map { it.id })
    }

    @Test
    fun termsIgnoreShortAndCase() {
        assertEquals(listOf("рецепт", "пасты"), AgentCore.terms("Рецепт пасты, а"))
        assertTrue(AgentCore.terms(null).isEmpty())
    }

    @Test
    fun topCommentsSkipRepliesAndSortByLikes() {
        val c = listOf(
            Comment(author = "x", text = "first", likes = 2, dateMs = 0, dateText = "", replyCount = 0, isReply = false),
            Comment(author = "y", text = "reply", likes = 99, dateMs = 0, dateText = "", replyCount = 0, isReply = true),
            Comment(author = "z", text = "best", likes = 10, dateMs = 0, dateText = "", replyCount = 0, isReply = false),
        )
        assertEquals(listOf("best (10 likes)", "first (2 likes)"), AgentCore.topComments(c))
    }
}

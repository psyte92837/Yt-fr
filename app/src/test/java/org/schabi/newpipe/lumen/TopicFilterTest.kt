/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

class TopicFilterTest {
    @org.junit.Test
    fun parsesTopicAndIndexes() {
        val tp = TopicFilter.parseTopic("```json\n{\"phrase\":\"SEO\",\"domain\":\"Поисковая оптимизация\",\"keywords\":[\"seo\",\"ключи\"]}\n```")
        org.junit.Assert.assertEquals("Поисковая оптимизация", tp!!.domain)
        org.junit.Assert.assertEquals(2, tp.keywords.size)
        org.junit.Assert.assertNull(TopicFilter.parseTopic("{\"phrase\":\"x\"}"))
        org.junit.Assert.assertEquals(setOf(1, 3), TopicFilter.parseIndexes("{\"relevant\":[1,3,0,-2]}"))
        org.junit.Assert.assertTrue(TopicFilter.hasVerdict("{\"relevant\":[]}"))
        org.junit.Assert.assertFalse(TopicFilter.hasVerdict("{\"x\":1}"))
        org.junit.Assert.assertFalse(TopicFilter.hasVerdict("не json"))
    }

    @org.junit.Test
    fun prunesOffTopicNodesAndEdges() {
        val g = RecGraph()
        listOf("a" to 1, "b" to 0, "c" to 1, "d" to 0).forEach { (id, r) -> g.nodes[id] = RecNode(id, relevant = r) }
        g.nodes["a"]!!.seed = true
        g.nodes["d"]!!.seed = true // seeds are never removed
        g.addEdge("a", "b", 0)
        g.addEdge("a", "c", 1)
        g.addEdge("b", "c", 0)
        org.junit.Assert.assertEquals(1, TopicFilter.prune(g))
        org.junit.Assert.assertEquals(setOf("a", "c", "d"), g.nodes.keys)
        org.junit.Assert.assertEquals(1, g.edges.size)
        org.junit.Assert.assertTrue(g.addEdge("a", "b", 0)) // key set was rebuilt
    }

    @org.junit.Test
    fun channelKeywordFallback() {
        val c = ChannelData("u", name = "Кухня по-быстрому", description = "рецепты и кулинария")
        org.junit.Assert.assertTrue(TopicFilter.channelMatches(Relevance.keywords("рецепт кулинария"), c))
        org.junit.Assert.assertFalse(TopicFilter.channelMatches(Relevance.keywords("квантовая физика"), c))
    }

    @org.junit.Test
    fun linksLoseQueryParameters() {
        org.junit.Assert.assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YtLink.classify("https://youtu.be/dQw4w9WgXcQ?si=abc&t=30").url)
        org.junit.Assert.assertEquals("https://www.youtube.com/@mkbhd", YtLink.classify("https://youtube.com/@mkbhd/videos?si=x").url)
        org.junit.Assert.assertEquals("https://www.youtube.com/results", YtLink.classify("https://www.youtube.com/results?search_query=a&si=1").url)
        org.junit.Assert.assertEquals("https://www.youtube.com/playlist?list=PLx_1", YtLink.classify("https://youtube.com/playlist?list=PLx_1&si=q#t").url)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeMapTest {
    private val day = 86_400_000L
    private val jan = 1_704_067_200_000L + 10 * day // 2024-01-11
    private val mar = jan + 60 * day // 2024-03

    private fun v(id: String, ch: String, views: Long, ms: Long = jan, tags: List<String> = emptyList(), comments: Int = 0,
                  short: Boolean = false, dur: Long = 600, labels: List<String> = emptyList(), marked: Boolean = false) =
        Video(id, "https://www.youtube.com/watch?v=$id", title = "Title $id", channel = ch, views = views, uploadMs = ms,
            tags = tags.toMutableList(), commentsCount = comments, isShort = short, duration = dur,
            labels = labels.toMutableList(), marked = marked, description = "About $id")

    private val videos = listOf(
        v("a", "Alpha", 1000, tags = listOf("Spine", "back"), comments = 3, labels = listOf("good"), marked = true),
        v("b", "Alpha", 500, mar, tags = listOf("spine"), dur = 1500),
        v("c", "Beta/Two", 9000, mar, tags = listOf("Back", "neck"), short = true, dur = 40),
        v("d", "Gamma", 10, -1)
    )
    private val project = Project(id = "p", name = "Back pain", phrase = "боль в спине", domain = "медицина", queries = mutableListOf("боль в спине", "back pain"))
    private var loads = 0
    private val input = KInput(
        project, videos,
        summaries = mapOf("a" to "Summary of a"),
        comments = { id ->
            loads++
            if (id == "a") listOf(Comment("", "u1", "low", 1, -1, "", 0, false), Comment("", "u2", "top comment", 9, -1, "", 0, false),
                Comment("", "u3", "reply", 50, -1, "", 0, true)) else emptyList()
        },
        insights = listOf(KInsight("pain", "Hurts every morning", "Title a"), KInsight("question", "Is it a hernia?"), KInsight("pain", "Costly")),
        recs = listOf(RecNode("r1", title = "Rec one", channel = "Delta", hits = 4)),
        ai = listOf(AiResult("k1", "clusters", "project", "m", "clusters text", 1, 1), AiResult("k2", "video_summary", "a", "m", "x", 1, 1))
    )
    private val root = KMap.build(input)

    private fun ids(n: KNode) = n.children.map { it.id }

    @Test
    fun aspectsCoverTheProject() {
        assertEquals(
            listOf("map/topic", "map/channels", "map/labels", "map/tags", "map/time", "map/format", "map/audience", "map/comments", "map/recs", "map/ai"),
            ids(root)
        )
        assertEquals(0, root.depth)
        assertEquals(1, root.children[0].depth)
        assertEquals(listOf("боль в спине", "back pain"), root.children[0].children.map { it.title })
    }

    @Test
    fun channelsAreSortedAndSlashSafe() {
        val ch = KMap.find(root, "map/channels")!!
        assertEquals(listOf("Alpha", "Beta/Two", "Gamma"), ch.children.map { it.title })
        val beta = ch.children[1]
        assertFalse(beta.id.removePrefix("map/channels/").contains('/'))
        // videos of a channel: most viewed first
        assertEquals(listOf("Title a", "Title b"), ch.children[0].children.map { it.title })
    }

    @Test
    fun tagsMergeCaseAndNeedTwoVideos() {
        val tags = KMap.topTags(videos, 10)
        assertEquals(listOf("Spine", "back"), tags.map { it.first })
        assertEquals(setOf("a", "b"), tags[0].second.map { it.id }.toSet())
        assertEquals(setOf("a", "c"), tags[1].second.map { it.id }.toSet())
    }

    @Test
    fun timelineNewestFirstUndatedLast() {
        val m = KMap.byMonth(videos)
        assertEquals(listOf("2024-03", "2024-01"), m.take(2).map { it.first })
        assertEquals(listOf("d"), m.last().second.map { it.id })
    }

    @Test
    fun formatsSplitByLength() {
        val f = KMap.formats(videos).associate { it.first to it.second.map { v -> v.id } }
        assertEquals(listOf("c"), f["Shorts"])
        assertEquals(listOf("b"), f.values.first { "b" in it })
        assertEquals(4, f.values.sumOf { it.size })
    }

    @Test
    fun videoOpensIntoSummaryAndComments() {
        val a = KMap.find(root, "map/channels/c:Alpha/v:a")!!
        assertEquals(listOf("sum", "desc", "tags", "comm"), a.children.map { it.id.substringAfterLast('/') })
        val comm = a.children.last()
        // replies are skipped, the most liked comment goes first
        assertEquals(listOf("top comment", "low"), comm.children.map { it.title })
    }

    @Test
    fun commentsLoadOnlyWhenOpened() {
        loads = 0
        val fresh = KMap.build(input)
        KMap.find(fresh, "map/channels/c:Alpha/v:a")
        assertEquals(0, loads)
        KMap.find(fresh, "map/channels/c:Alpha/v:a/comm/0")
        assertEquals(1, loads)
    }

    @Test
    fun audienceGroupsByType() {
        val au = KMap.find(root, "map/audience")!!
        assertEquals(listOf("map/audience/i:pain", "map/audience/i:question"), ids(au))
        assertEquals(2, au.children[0].children.size)
    }

    @Test
    fun aiFindingsSkipVideoSummaries() {
        assertEquals(listOf("clusters text"), KMap.find(root, "map/ai")!!.children.map { it.info })
    }

    @Test
    fun findAndPath() {
        assertNull(KMap.find(root, "map/nope"))
        assertNotNull(KMap.find(root, "map/labels/marked"))
        assertEquals(listOf("Back pain", "Каналы", "Alpha", "Title a"), KMap.path(root, "map/channels/c:Alpha/v:a"))
    }

    @Test
    fun contextPrefersOverviewWhenLimited() {
        val ch = KMap.find(root, "map/channels")!!
        val wide = KMap.context(ch, 100_000)
        assertTrue(wide.contains("Summary of a"))
        assertTrue(wide.contains("top comment"))
        // tight limit: all three channels are listed even though their videos do not fit
        val tight = KMap.context(ch, 200)
        assertTrue(tight.contains("Alpha") && tight.contains("Beta/Two") && tight.contains("Gamma"))
        assertFalse(tight.contains("top comment"))
        val leaf = KMap.context(KMap.find(root, "map/topic/q:back pain")!!, 1000)
        assertTrue(leaf.startsWith("- back pain"))
    }

    @Test
    fun emptyProjectHasOnlyTopic() {
        val r = KMap.build(KInput(project, emptyList()))
        assertEquals(listOf("map/topic"), ids(r))
    }

    @Test
    fun summaryKeyDependsOnData() {
        val a = KMap.summaryKey("map/tags", "m", "p", "data", "ru")
        assertEquals(a, KMap.summaryKey("map/tags", "m", "p", "data", "ru"))
        assertNotEquals(a, KMap.summaryKey("map/tags", "m", "p", "data2", "ru"))
        assertNotEquals(a, KMap.summaryKey("map/time", "m", "p", "data", "ru"))
    }
}

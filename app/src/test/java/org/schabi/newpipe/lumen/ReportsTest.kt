/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphStatsTest {
    // s -> a, s -> b, a -> b, b -> a (mutual), c isolated, d -> e separate group
    private fun nodes() = listOf(
        RecNode("s", title = "Seed", seed = true, depth = 0, relevant = 1, pr = 0.1),
        RecNode("a", title = "A", channel = "Ch1", depth = 1, relevant = 1, pr = 0.3),
        RecNode("b", title = "B", channel = "Ch1", depth = 1, relevant = 0, pr = 0.4),
        RecNode("c", title = "C", channel = "Ch2", depth = 2, relevant = -1, pr = 0.05),
        RecNode("d", title = "D", channel = "Ch2", depth = 2, relevant = 1, pr = 0.1),
        RecNode("e", title = "E", channel = "Ch3", depth = 2, relevant = 0, pr = 0.05)
    )

    private val edges = listOf(0 to 1, 0 to 2, 1 to 2, 2 to 1, 3 to 3, 4 to 5, 0 to 1)

    @Test
    fun countsDegreesAndComponents() {
        val s = GraphStats.of(nodes(), edges)
        assertEquals(6, s.n)
        assertEquals(5, s.m) // self-loop and duplicate dropped
        assertEquals(1, s.seeds)
        assertEquals(3, s.onTopic)
        assertEquals(2, s.offTopic)
        assertEquals(1, s.unknown)
        assertEquals(listOf(1, 2, 3), s.depthCounts.toList())
        assertEquals(listOf(1, 1, 1), s.depthOn.toList())
        assertEquals(listOf(1, 2, 2), s.depthJudged.toList())
        assertEquals(2, s.inDeg[2])
        assertEquals(2, s.outDeg[0])
        assertEquals(3, s.components) // {s,a,b}, {c}, {d,e}
        assertEquals(3, s.largest)
        assertEquals(1, s.isolated)
        assertEquals(listOf(1 to 2), s.mutual)
        assertEquals(3, s.reach)
        assertEquals(2, s.topPr.first()) // B has the highest PageRank, seed excluded
        assertTrue(0 !in s.topPr)
        assertEquals(0, s.topHubs.first())
        assertEquals(0.4, s.reciprocity, 1e-9)
        assertEquals("Ch1" to 2, s.groups.first())
    }

    @Test
    fun storyMentionsKeyFacts() {
        val n = nodes()
        val story = GraphStats.story(GraphStats.of(n, edges), n, channels = false)
        val all = story.joinToString("\n") { it.second }
        assertTrue(all.contains("6 видео"))
        assertTrue(all.contains("«B»"))
        assertTrue(all.contains("3 группы"))
        assertTrue(all.contains("Взаимных пар: 1"))
        assertTrue(all.contains("«Ch1»"))
        assertTrue(all.contains("достижимо 3 из 6"))
        assertTrue(GraphStats.story(GraphStats.of(emptyList(), emptyList()), emptyList(), true).isEmpty())
    }

    @Test
    fun russianPlurals() {
        assertEquals("канал", Plural.ru(1, "канал", "канала", "каналов"))
        assertEquals("канала", Plural.ru(3, "канал", "канала", "каналов"))
        assertEquals("каналов", Plural.ru(11, "канал", "канала", "каналов"))
        assertEquals("канал", Plural.ru(21, "канал", "канала", "каналов"))
        assertEquals("каналов", Plural.ru(112, "канал", "канала", "каналов"))
        assertEquals("links", Plural.en(2, "link", "links"))
    }
}

class DossierTest {
    @Test
    fun parsesChannelAndSkipsBrokenVideos() {
        val reply = "```json\n" + """
            {"channel":{"about":"Обзоры электромобилей","audience":"водители","formats":["обзор","тест-драйв"],"strengths":"цифры","weaknesses":null,"tone":"спокойный"},
             "videos":[{"n":1,"about":"Тест зимой","points":["запас хода падает"],"comments":"спорят о цене","mood":{"pos":3,"neg":1,"neu":1},"questions":"где заряжать?","verdict":"полезно"},
                       "битый элемент",
                       {"n":2,"about":"","points":[]},
                       {"n":3,"about":"Зарядки","points":["быстрые станции"],"mood":{"pos":-1}}]}
        """.trimIndent() + "\n```"
        val c = Dossier.parseChannel(reply)
        assertNotNull(c)
        c!!
        assertEquals("Обзоры электромобилей", c.about)
        assertEquals("", c.weaknesses)
        assertEquals(listOf("обзор", "тест-драйв"), c.formats)
        assertEquals(2, c.videos.size)
        val v = c.videos[0]
        assertEquals(listOf("где заряжать?"), v.questions)
        assertEquals(60, v.pos)
        assertEquals(20, v.neg)
        assertEquals(20, v.neu)
        assertTrue(v.hasMood)
        assertEquals(3, c.videos[1].n)
        assertTrue(!c.videos[1].hasMood)
    }

    @Test
    fun garbageIsNull() {
        assertNull(Dossier.parseChannel("Извините, не могу"))
        assertNull(Dossier.parseChannel("{\"channel\":{\"about\":\"\"},\"videos\":[]}"))
        assertNull(Dossier.parseChannel("{\"channel\": {\"about\": \"обрыв"))
        assertNull(Dossier.parseOverview("{}"))
        val o = Dossier.parseOverview("{\"summary\":\"Ниша растёт\",\"themes\":[\"цены\"],\"gaps\":[\"ремонт\"]}")
        assertEquals("Ниша растёт", o!!.summary)
        assertEquals(listOf("ремонт"), o.gaps)
    }

    @Test
    fun packRoundTrip() {
        val reply = "{\"channel\":{\"about\":\"X\"},\"videos\":[{\"n\":1,\"about\":\"Y\"}]}"
        val (ids, card) = Dossier.unpack(Dossier.pack(listOf("v1", "v2"), reply))
        assertEquals(listOf("v1", "v2"), ids)
        assertEquals("Y", card!!.videos[0].about)
    }

    @Test
    fun groupsByChannelStrongestFirst() {
        val vids = listOf(
            Video("1", "u1", title = "a", channel = "A", channelUrl = "https://www.youtube.com/@a", views = 10),
            Video("2", "u2", title = "b", channel = "A", channelUrl = "https://www.youtube.com/@A/", views = 500),
            Video("3", "u3", title = "c", channel = "B", channelUrl = "https://www.youtube.com/@b", views = 9000),
            Video(LOCAL_PREFIX + "x", "", title = "file", channel = "File"),
            Video("4", "u4", title = "d", channel = "C", views = 1)
        )
        val g = Dossier.group(vids, channels = 10, perChannel = 1)
        assertEquals(listOf("A", "B", "C"), g.map { it.name })
        assertEquals(listOf("2"), g[0].videos.map { it.id })
        assertEquals(2, Dossier.group(vids, channels = 2, perChannel = 5).size)
    }
}

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

class PredictTest {
    private val now = 1_800_000_000_000L
    private val day = Predict.DAY

    private val catWords = listOf("кошки", "корм", "уход", "котёнок", "лоток", "ветеринар", "шерсть", "породы")
    private val guitarWords = listOf("гитара", "аккорды", "урок", "струны", "бой", "перебор", "медиатор", "лад")

    private fun node(id: String, title: String, hits: Int = 1, views: Long = 50_000, ageDays: Int = 200, dur: Long = 600, rel: Int = 1) =
        RecNode(id = id, title = title, channel = "ch_$id", views = views, uploadMs = now - ageDays * day, duration = dur, relevant = rel, hits = hits)

    /** Two rooms of 8 videos, densely linked inside, one bridge; cats are short and fresh, guitars are long and old. */
    private fun graph(): Pair<List<RecNode>, List<RecEdge>> {
        val nodes = ArrayList<RecNode>()
        for (i in 0 until 8) nodes.add(node("c$i", "${catWords[i]} ${catWords[(i + 1) % 8]} кошки уход", hits = 3, views = if (i < 4) 2_000 else 80_000, ageDays = if (i < 4) 5 else 300, dur = 300))
        for (i in 0 until 8) nodes.add(node("g$i", "${guitarWords[i]} ${guitarWords[(i + 2) % 8]} гитара урок", hits = 3, views = 500_000, ageDays = 900, dur = 1200))
        val edges = ArrayList<RecEdge>()
        for (p in listOf("c", "g")) for (i in 0 until 8) for (j in 0 until 8) if (i != j && (j - i + 8) % 8 <= 3) edges.add(RecEdge("$p$i", "$p$j", (j - i + 8) % 8 - 1))
        edges.add(RecEdge("c0", "g0", 9))
        return nodes to edges
    }

    @Test
    fun shapeFlags() {
        val s = Predict.shape("Как ВЫБРАТЬ корм в 2026: 5 ошибок (твой кот) ?! 🐱")
        assertTrue(s["number"]!!)
        assertTrue(s["year"]!!)
        assertTrue(s["question"]!!)
        assertTrue(s["exclaim"]!!)
        assertTrue(s["colon"]!!)
        assertTrue(s["brackets"]!!)
        assertTrue(s["caps"]!!)
        assertTrue(s["emoji"]!!)
        assertTrue(s["you"]!!)
        assertFalse(s["i"]!!)
        val plain = Predict.shape("уход за кошкой")
        assertTrue(Predict.SHAPES.none { plain[it] == true })
    }

    @Test
    fun durations() {
        assertEquals(510L, Predict.parseDuration("8:30"))
        assertEquals(3723L, Predict.parseDuration("1:02:03"))
        assertEquals(45L, Predict.parseDuration("45"))
        assertEquals(45L, Predict.parseDuration("45 сек"))
        assertEquals(480L, Predict.parseDuration("8 мин"))
        assertEquals(-1L, Predict.parseDuration("восемь"))
        assertEquals(-1L, Predict.parseDuration(""))
    }

    @Test
    fun louvainSplitsTwoCliques() {
        val e = ArrayList<Triple<Int, Int, Double>>()
        for (i in 0 until 5) for (j in i + 1 until 5) e.add(Triple(i, j, 1.0))
        for (i in 5 until 10) for (j in i + 1 until 10) e.add(Triple(i, j, 1.0))
        e.add(Triple(0, 5, 1.0))
        val c = Predict.louvain(10, e)
        assertTrue((0 until 5).all { c[it] == c[0] })
        assertTrue((5 until 10).all { c[it] == c[5] })
        assertTrue(c[0] != c[5])
    }

    @Test
    fun linkedLookalikeGroupsMerge() {
        val titles = listOf("кошки корм уход", "корм для кошки", "уход кошки", "кошки уход корм", "кошки корм", "гитара аккорды", "гитара урок")
        val nodes = titles.mapIndexed { i, s -> node("v$i", s) }
        val owner = intArrayOf(0, 0, 0, 1, 1, 2, 2)
        val edges = listOf(Triple(2, 3, 1.0), Triple(4, 5, 1.0))
        val m = Predict.mergeSimilar(nodes, edges, owner)
        assertEquals(m[0], m[3])
        assertTrue(m[0] != m[5])
        assertEquals(m[5], m[6])
    }

    @Test
    fun communitiesWithTermsHubsAndOpenness() {
        val (nodes, edges) = graph()
        val cs = Predict.communities(nodes, edges, now)
        assertEquals(2, cs.size)
        val cats = cs.first { "c0" in it.ids }
        val guitars = cs.first { "g0" in it.ids }
        assertTrue(cats.ids.all { it.startsWith("c") })
        assertTrue(cats.terms.isNotEmpty())
        assertTrue(cats.terms.none { it in guitarWords || it == "гитара" })
        assertTrue(guitars.terms.any { it == "гитара" || it in guitarWords })
        assertEquals(5, cats.hubs.size)
        assertTrue(cats.open.share > 0.4)
        assertEquals(0.0, guitars.open.share, 1e-9)
        assertNotNull(cats.durIqr)
        assertTrue(cats.label.isNotBlank())
    }

    @Test
    fun doorsLetNewcomersIn() {
        val (nodes, edges) = graph()
        val doors = Predict.doors(nodes, edges, now)
        assertTrue(doors.isNotEmpty())
        assertTrue(doors.all { it.id.startsWith("c") })
        assertTrue(doors.first().newcomers.all { it in setOf("c0", "c1", "c2", "c3") })
    }

    @Test
    fun distinctiveWords() {
        val a = listOf("гитара для начинающих", "гитара аккорды", "простая гитара", "кот")
        val b = listOf("кот ест", "кот спит", "кот играет", "кот и гитара")
        val d = Predict.distinctive(a, b, minZ = 0.5)
        assertEquals("гитара", d.first().first)
        assertTrue(d.none { it.first == "кот" })
    }

    @Test
    fun aucAndModel() {
        assertEquals(1.0, Predict.auc(listOf(0.1, 0.2, 0.8, 0.9), listOf(0, 0, 1, 1)), 1e-9)
        assertEquals(0.5, Predict.auc(listOf(0.5, 0.5), listOf(0, 1)), 1e-9)
        assertEquals(0.0, Predict.auc(listOf(0.9, 0.1), listOf(0, 1)), 1e-9)
        // recommended titles have a number, the others do not; views equal
        val items = (0 until 40).map { i ->
            val rec = i % 2 == 0
            Predict.Item("v$i", if (rec) "топ $i способов уход" else "уход за котом номер", views = 10_000L + i, uploadMs = now - 100 * day, durationSec = 600, inRecs = rec)
        }
        val m = Predict.model(items, now, { 0.0 })
        assertNotNull(m)
        assertTrue(m!!.auc > 0.9)
        assertTrue(m.reliable)
        assertEquals("number", m.factors().first().first)
        assertNull(Predict.model(items.take(10), now, { 0.0 }))
    }

    @Test
    fun profileMatchesViewLevels() {
        val pos = (0 until 20).map { Predict.Item("p$it", "5 ошибок $it", views = 100_000, durationSec = 300, inRecs = true) }
        val neg = (0 until 20).map { Predict.Item("n$it", "ошибки новичка", views = if (it < 10) 100_000 else 100, durationSec = 1500) }
        val p = Predict.profile(pos + neg, now)
        assertTrue(p.matched)
        assertTrue(p.enough)
        assertEquals(1.0, p.feat("number")!!.pos, 1e-9)
        assertEquals(0.0, p.feat("number")!!.neg, 1e-9)
        assertEquals(300.0, p.feat("duration")!!.pos, 1e-9)
        assertEquals("number", p.strongShapes().first().key)
        val w = Predict.matchWeights(pos, neg)!!
        assertEquals(0.0, w[15], 1e-9)
        assertTrue(w[0] > 1.0)
    }

    @Test
    fun fitPrefersTheCommunityLanguage() {
        val (nodes, edges) = graph()
        val search = (0 until 16).map { Predict.Item("s$it", "рецепт пирога $it", views = 50_000, durationSec = 600) }
        val a = Predict.analyse(nodes, edges, search, now)
        val cats = a.communities.first { "c0" in it.ids }
        val good = Predict.fit(a, Predict.Draft("кошки уход корм котёнок", durationSec = 300), cats.id)
        val bad = Predict.fit(a, Predict.Draft("рецепт пирога с вишней", durationSec = 3000), cats.id)
        assertTrue(good.score > bad.score)
        assertEquals(cats.id, good.best)
        assertTrue(good.checks.first { it.key == "duration" }.ok)
        assertFalse(bad.checks.first { it.key == "duration" }.ok)
        assertTrue(bad.missing.isNotEmpty())
        assertTrue(bad.confidence <= good.confidence)
    }

    @Test
    fun placementAndPoints() {
        val (nodes, edges) = graph()
        val a = Predict.analyse(nodes, edges, emptyList(), now)
        val cats = a.communities.first { "c0" in it.ids }
        val rel = listOf(
            Predict.Rel("c1", "что угодно"),
            Predict.Rel("x1", "кошки уход корм лоток"),
            Predict.Rel("x2", "футбол обзор матча"),
            Predict.Rel("x3", "новый кошачий домик")
        )
        val p = Predict.place(a, rel, setOf("кошк", "кошач"))
        assertEquals(cats.id, p.codes[0])
        assertEquals(cats.id, p.codes[1])
        assertEquals(-1, p.codes[2])
        assertEquals(0, p.codes[3])
        val q = Predict.Placement(listOf(cats.id, -2)).with(mapOf(1 to cats.id))
        assertEquals(1.0, q.share(cats.id), 1e-9)
        val pt = Predict.point(a, p, now, 120, cats.id, 4, listOf("c0:3"))
        assertEquals(0.5, pt.targetShare, 1e-9)
        val back = Predict.Point.from(pt.toJson())
        assertEquals(pt, back)
        assertEquals(1, back.found)
        assertEquals(1, Predict.trend(listOf(pt.copy(targetShare = 0.0, where = emptyList()), pt)))
        assertEquals(0, Predict.trend(listOf(pt)))
    }

    @Test
    fun aiAnswers() {
        val names = Predict.parseNames("Вот: ```json\n{\"groups\":[{\"n\":1,\"name\":\"Уход за котом\",\"about\":\"хозяева\",\"on_topic\":true},{\"n\":0,\"name\":\"x\"}]}\n```")
        assertEquals(1, names.size)
        assertEquals("Уход за котом", names[1]!!.first)
        val cls = Predict.parseClassify("{\"a\":[[1,2],[2,-1],[3,0],[4,7],[9,1]]}", 4, setOf(1, 2))
        assertEquals(mapOf(0 to 2, 1 to -1, 2 to 0, 3 to -1), cls)
        val ideas = Predict.parseIdeas(
            "{\"ideas\":[{\"door\":2,\"title\":\"Корм для котёнка\",\"tags\":[\"кот\"],\"length_sec\":420},{\"door\":9,\"title\":\"корм для котёнка\"},{\"door\":1,\"title\":\"Лоток\"}]}", 3
        )
        assertEquals(listOf("Корм для котёнка", "Лоток"), ideas.map { it.title })
        assertEquals(2, ideas[0].door)
        assertEquals(420L, ideas[0].draft().durationSec)
        val r = Predict.parseReview("{\"topic_fit\":\"72%\",\"continuation\":0.6,\"click\":55,\"fixes\":[\"короче\"],\"confidence\":\"HIGH\"}")!!
        assertEquals(72, r.topicFit)
        assertEquals(60, r.continuation)
        assertEquals(55, r.click)
        assertEquals("high", r.confidence)
        assertNull(Predict.parseReview("нет json"))
        assertEquals(Predict.Draft("a", "b", "c", 60, true), Predict.Draft.from(Predict.Draft("a", "b", "c", 60, true).toJson()))
    }

    @Test
    fun promptsHavePlaceholders() {
        assertEquals(setOf("predict_names", "predict_classify", "predict_review", "predict_ideas", "predict_plan"), PREDICT_PROMPTS.keys)
        PREDICT_PROMPTS.forEach { (k, v) ->
            assertTrue("$k: {data}", v.contains("{data}"))
            assertTrue("$k: {phrase}", v.contains("{phrase}"))
        }
        listOf("predict_names", "predict_review", "predict_ideas", "predict_plan").forEach { assertTrue(it, PREDICT_PROMPTS[it]!!.contains("{lang}")) }
        assertTrue(PREDICT_PROMPTS["predict_classify"]!!.contains("{groups}"))
        assertTrue(PREDICT_PROMPTS["predict_classify"]!!.contains("{title}"))
        assertTrue(PREDICT_PROMPTS["predict_ideas"]!!.contains("{count}"))
    }

    @Test
    fun briefMentionsEverything() {
        val (nodes, edges) = graph()
        val a = Predict.analyse(nodes, edges, emptyList(), now)
        val target = a.communities.first().id
        val pt = Predict.point(a, Predict.Placement(listOf(target)), now, 10, target, 2, emptyList())
        val b = Predict.brief(a, target, Predict.Draft("кошки уход"), listOf(pt))
        listOf("COMMUNITIES", "TARGET", "DOORS", "PROFILE", "DRAFT", "AFTER PUBLISHING", "model").forEach { assertTrue(it, b.contains(it)) }
    }

    @Test
    fun confidenceLevels() {
        val p = Predict.Profile(20, 20, true, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertEquals(0, Predict.confidence(10, 0, 10, 2, p, null).first)
        val (lvl, why) = Predict.confidence(200, 0, 200, 3, p, null)
        assertEquals(1, lvl)
        assertEquals(listOf("weak_model"), why)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AuditTest {
    private val topic = AuditMath.topicOf(listOf("Гитара для начинающих аккорды"))

    private fun item(id: String, title: String, ch: String, views: Long = 100, up: Long = -1, short: Boolean = false) =
        AuditItem(id, title, ch, views = views, uploadMs = up, isShort = short)

    @Test
    fun similarityCountsTopicWords() {
        assertEquals(1.0, AuditMath.similarity(topic, "Аккорды на гитаре"), 1e-9)
        assertEquals(0.0, AuditMath.similarity(topic, "Смешные котики"), 1e-9)
        assertEquals(0.5, AuditMath.similarity(topic, "Гитара котики"), 1e-9)
        assertEquals(0.0, AuditMath.similarity(emptySet(), "Гитара"), 1e-9)
    }

    @Test
    fun strategiesPickExpectedCandidate() {
        val c = listOf(
            item("a", "Котики", "x", views = 10, up = 1000),
            item("b", "Гитара аккорды", "y", views = 500, up = 10),
            item("c", "Новости", "z", views = 50, up = 5000)
        )
        val r = Random(1)
        assertEquals(0, AuditMath.choose("first", c, topic, r))
        assertEquals(1, AuditMath.choose("popular", c, topic, r))
        assertEquals(2, AuditMath.choose("fresh", c, topic, r))
        assertEquals(1, AuditMath.choose("stay", c, topic, r))
        assertEquals(0, AuditMath.choose("away", c, topic, r))
        repeat(20) { assertTrue(AuditMath.choose("random", c, topic, r) in c.indices) }
    }

    @Test
    fun concentrationAndDiversity() {
        assertEquals(1.0, AuditMath.hhi(listOf(5)), 1e-9)
        assertEquals(0.25, AuditMath.hhi(listOf(1, 1, 1, 1)), 1e-9)
        assertEquals(0.0, AuditMath.hhi(emptyList()), 1e-9)
        assertEquals(1.0, AuditMath.entropy(listOf(3, 3, 3)), 1e-9)
        assertEquals(0.0, AuditMath.entropy(listOf(7)), 1e-9)
        assertEquals(1.0 / 3, AuditMath.jaccard(setOf("a", "b"), setOf("b", "c")), 1e-9)
        assertEquals(15L, AuditMath.median(listOf(10, 20, -1)))
        assertEquals(-1L, AuditMath.median(listOf(-1)))
        assertEquals("1.5K", AuditMath.short(1500))
    }

    private fun sampleRun(): AuditRun {
        val seed = item("s", "Гитара аккорды", "Seed", views = 1000)
        val run = AuditRun(at = 100L * 86_400_000L, topic = topic)
        repeat(3) { w ->
            val walk = AuditWalk(seed.copy())
            walk.steps.add(AuditStep(item("g$w", "Гитара урок", "Guitar", views = 2000), 0, listOf(item("g$w", "Гитара урок", "Guitar"), item("hit", "Хит", "Pop"))))
            walk.steps.add(AuditStep(item("hit", "Хит недели", "Pop", views = 1_000_000), 1, listOf(item("hit", "Хит", "Pop"))))
            walk.steps.add(AuditStep(item("cat$w", "Смешные котики", "Pop", views = 5_000_000, short = true), 0, emptyList()))
            run.walks.add(walk)
        }
        run.walks.add(AuditWalk(seed.copy(), end = "loop"))
        return run
    }

    @Test
    fun reportMeasuresDriftAttractorsAndConcentration() {
        val r = AuditMath.report(sampleRun())
        assertEquals(3, r.walks)
        assertEquals(3, r.steps)
        assertEquals(4, r.perStep.size)
        assertEquals(1.0, r.perStep[0].onTopic, 1e-9)
        assertEquals(0.0, r.perStep[3].onTopic, 1e-9)
        assertEquals(2, r.halfLife)
        assertEquals(2500.0, r.popularityLift, 1e-9)
        assertEquals("hit", r.attractors.first().key)
        assertEquals(3, r.attractors.first().count)
        assertEquals("Pop", r.topChannels.first().key)
        assertEquals(1.0, r.convergence, 1e-9)
        assertEquals(1, r.deadEnds)
        assertEquals(1.0, r.perStep[3].shorts, 1e-9)
        assertEquals(0.0, r.perStep[3].newChannels, 1e-9) // Pop was already seen at step 2
        assertTrue(r.findings.size >= 3)
        assertNull(AuditMath.report(AuditRun()).halfLife)
    }

    @Test
    fun runSurvivesJsonAndBrokenEntries() {
        val run = sampleRun()
        val back = AuditRun.from(org.json.JSONObject(run.toJson().toString()))
        assertEquals(run.walks.size, back.walks.size)
        assertEquals("hit", back.walks[0].steps[1].item.id)
        assertEquals(1, back.walks[0].steps[1].rank)
        assertEquals("loop", back.walks[3].end)
        assertEquals(run.topic, back.topic)
        val text = "{\"runs\":[" + run.toJson() + ",{\"walks\":[{\"bad\":1}]}]}"
        assertEquals(1, AuditStore.parse(text).size)
        assertTrue(AuditStore.parse("не json").isEmpty())
    }

    @Test
    fun graphAndMarkdown() {
        val run = sampleRun()
        val g = AuditMath.graph(run)
        assertTrue(g.nodes.getValue("s").seed)
        assertEquals(2, g.nodes.getValue("hit").depth)
        assertEquals(3, g.nodes.getValue("hit").hits) // reached from g0, g1, g2
        val md = AuditMath.markdown(run, AuditMath.report(run), "Гитара")
        assertTrue(md.contains("Хит недели"))
        assertTrue(md.contains("| 3 |"))
        assertTrue(AuditMath.compare(AuditMath.report(run), AuditMath.report(run), "A", "B").contains("HHI"))
        assertEquals(1, AuditConfig(strategy = "first", walks = 5).effectiveWalks())
        assertEquals(30, AuditConfig(walks = 5, steps = 3).estimate(2))
    }
}

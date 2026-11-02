/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgoCoreTest {
    private fun e(vararg p: Pair<Int, Int>) = p.map { IEdge(it.first, it.second) }

    /** Two 4-cliques (0..3, 4..7) joined by the bridge 3 – 4. */
    private fun twoCliques(): List<IEdge> {
        val out = ArrayList<IEdge>()
        for (base in listOf(0, 4)) for (i in base until base + 4) for (j in base until base + 4) if (i != j) out += IEdge(i, j)
        out += IEdge(3, 4)
        out += IEdge(4, 3)
        return out
    }

    @Test
    fun positionWeightDecays() {
        assertEquals(1.0, AlgoCore.posWeight(0), 1e-9)
        assertEquals(0.5, AlgoCore.posWeight(2), 1e-9)
        assertTrue(AlgoCore.posWeight(1) > AlgoCore.posWeight(5))
    }

    @Test
    fun pageRankSumsToOneAndFavoursTargets() {
        val pr = AlgoCore.pageRank(4, e(0 to 3, 1 to 3, 2 to 3, 3 to 0))
        assertEquals(1.0, pr.sum(), 1e-9)
        assertTrue(pr[3] > pr[0] && pr[0] > pr[1])
    }

    @Test
    fun personalisedPageRankStaysNearTheSeed() {
        // chain 0 -> 1 -> 2 and a separate pair 3 -> 4
        val edges = e(0 to 1, 1 to 2, 3 to 4)
        val ppr = AlgoCore.pageRank(5, edges, restart = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(0.0, ppr[3], 1e-9)
        assertEquals(0.0, ppr[4], 1e-9)
        assertTrue(ppr[1] > ppr[2])
        // weights: a heavier edge gets more rank
        val w = AlgoCore.pageRank(3, listOf(IEdge(0, 1, 3.0), IEdge(0, 2, 1.0)), restart = doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue(w[1] > w[2])
    }

    @Test
    fun hitsSeparatesHubsAndAuthorities() {
        val (auth, hub) = AlgoCore.hits(4, e(0 to 2, 0 to 3, 1 to 2))
        assertEquals(1.0, auth[2], 1e-9)
        assertEquals(1.0, hub[0], 1e-9)
        assertEquals(0.0, auth[0], 1e-9)
        assertTrue(hub[0] > hub[1])
    }

    @Test
    fun betweennessFindsTheBridge() {
        val b = AlgoCore.betweenness(8, twoCliques())
        val top = b.indices.sortedByDescending { b[it] }.take(2).toSet()
        assertEquals(setOf(3, 4), top)
        assertEquals(1.0, b[3], 1e-9)
        assertEquals(0.0, b[0], 1e-9)
    }

    @Test
    fun louvainSplitsTwoCliques() {
        val edges = twoCliques()
        val c = AlgoCore.louvain(8, edges)
        assertEquals(setOf(c[0]), (0..3).map { c[it] }.toSet())
        assertEquals(setOf(c[4]), (4..7).map { c[it] }.toSet())
        assertTrue(c[0] != c[4])
        assertTrue(AlgoCore.modularity(8, edges, c) > 0.3)
        // isolated node stays alone, communities are numbered by size
        val c2 = AlgoCore.louvain(9, edges)
        assertEquals(2, c2[8])
    }

    @Test
    fun shortestPathFromSeeds() {
        val edges = e(0 to 1, 1 to 2, 2 to 3, 0 to 4, 4 to 3)
        assertEquals(listOf(0, 4, 3), AlgoCore.path(5, edges, listOf(0), 3))
        assertEquals(emptyList<Int>(), AlgoCore.path(5, edges, listOf(3), 0))
        assertEquals(listOf(2), AlgoCore.path(5, edges, listOf(2), 2))
    }

    @Test
    fun leakCountsOnlyChildrenOfOnTopicParents() {
        // 0 (on) -> 1 on, 2 off, 3 unknown; 2 (off) -> 4 off (ignored: parent off topic)
        val rel = intArrayOf(1, 1, 0, -1, 0)
        val l = AlgoCore.leak(5, e(0 to 1, 0 to 2, 0 to 3, 2 to 4), rel)
        assertEquals(0.5, l.rate, 1e-9)
        assertEquals(2, l.judged)
        assertEquals(1, l.parents)
        assertTrue(l.lo <= l.rate && l.rate <= l.hi)
    }

    @Test
    fun bootstrapIntervalContainsTheMean() {
        val v = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        val (lo, hi) = AlgoCore.bootstrap(v.size) { idx -> idx.map { v[it] }.average() }
        assertTrue(lo < 4.5 && 4.5 < hi)
        assertTrue(hi - lo < 4)
    }

    @Test
    fun rankBiasedOverlap() {
        assertEquals(1.0, AlgoCore.rbo(listOf("a", "b", "c"), listOf("a", "b", "c")), 1e-9)
        assertEquals(0.0, AlgoCore.rbo(listOf("a", "b"), listOf("c", "d")), 1e-9)
        val swapped = AlgoCore.rbo(listOf("a", "b", "c", "d"), listOf("b", "a", "c", "d"))
        val tail = AlgoCore.rbo(listOf("a", "b", "c", "d"), listOf("a", "b", "d", "c"))
        assertTrue(swapped < 1.0 && tail > swapped)
    }

    @Test
    fun textSimilarityHandlesWordForms() {
        val docs = listOf("Ремонт квартиры своими руками", "ремонту квартир: ошибки", "Рецепт борща", "Игры на ПК 2026")
        val ts = TextSim(docs)
        val a = ts.vector(docs[0])
        assertTrue(TextSim.cosine(a, ts.vector(docs[1])) > TextSim.cosine(a, ts.vector(docs[2])))
        assertEquals(1.0, TextSim.cosine(a, a), 1e-9)
        val terms = TextSim.liftTerms(listOf("ремонт кухни", "ремонт ванной", "ремонт пола"), docs + listOf("ремонт кухни", "ремонт ванной", "ремонт пола"), 3)
        assertEquals("ремонт", terms.first().first)
    }

    @Test
    fun logisticRegressionLearnsAndValidatesByGroup() {
        val rnd = java.util.Random(1)
        val x = ArrayList<DoubleArray>()
        val y = ArrayList<Int>()
        val groups = ArrayList<String>()
        for (i in 0 until 300) {
            val a = rnd.nextGaussian()
            val noise = rnd.nextGaussian()
            x += doubleArrayOf(a, noise)
            y += if (a + 0.3 * rnd.nextGaussian() > 0) 1 else 0
            groups += "ch${i % 30}"
        }
        val yy = y.toIntArray()
        val m = Logit.fit(x, yy)
        assertTrue(m.w[0] > 1.0 && kotlin.math.abs(m.w[1]) < 0.5)
        val oof = Logit.groupCv(x, yy, groups)
        assertTrue(Logit.auc(oof, yy) > 0.9)
        val cal = Logit.calibration(oof, yy)
        assertTrue(Logit.calibrationError(cal) < 0.15)
        assertEquals(1.0, Logit.auc(doubleArrayOf(0.1, 0.9), intArrayOf(0, 1)), 1e-9)
        assertEquals(0.5, Logit.auc(doubleArrayOf(0.5, 0.5), intArrayOf(0, 1)), 1e-9)
        assertTrue(m.contributions(doubleArrayOf(2.0, 0.0))[0] > 0)
    }
}

class AlgoQualityTest {
    @Test
    fun topicScorerRanksOnTopicTitlesHigher() {
        val seeds = listOf("Ремонт ванной своими руками", "Укладка плитки в ванной", "Ремонт кухни: ошибки", "Штукатурка стен под покраску")
        val corpus = seeds + listOf("Лучшие игры 2026", "Рецепт пиццы", "Новости футбола", "Ремонт квартиры за неделю")
        val s = TopicScorer(corpus, listOf("ремонт", "плитка", "штукатурка"), listOf("ремонт автомобиля"), seeds)
        val on = s.score("Как положить плитку на кухне")
        val off = s.score("Рецепт пиццы за 10 минут")
        val car = s.score("Ремонт автомобиля своими руками")
        assertTrue(on > off)
        assertTrue(on > car)
        assertTrue(on in 0.0..1.0 && off in 0.0..1.0)
    }

    @Test
    fun bestCutAndPrecisionRecall() {
        val scores = listOf(0.9, 0.8, 0.75, 0.7, 0.6, 0.55, 0.3, 0.25, 0.2, 0.15, 0.1, 0.05)
        val labels = listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1)
        assertEquals(0.6, AlgoQuality.bestCut(scores, labels)!!, 1e-9)
        assertEquals(null, AlgoQuality.bestCut(scores.take(5), labels.take(5)))
        val q = AlgoQuality.prf(listOf(1, 1, 0, null), listOf(1, 0, 1, 1))
        assertEquals(3, q.n)
        assertEquals(0.5, q.precision, 1e-9)
        assertEquals(0.5, q.recall, 1e-9)
    }
}

class TitleFeaturesTest {
    @Test
    fun packagingFeatures() {
        val f = TitleFeatures.of("КАК положить плитку? (5 ошибок) 🔥")
        assertEquals(TitleFeatures.NAMES.size, f.size)
        assertEquals(1.0, f[1], 0.0) // number
        assertEquals(1.0, f[2], 0.0) // question
        assertEquals(1.0, f[4], 0.0) // emoji
        assertEquals(1.0, f[5], 0.0) // brackets
        assertEquals(1.0, f[6], 0.0) // cyrillic
        assertTrue(f[3] > 0.1)
        assertEquals(listOf("плитка", "ошибки"), TitleFeatures.covered("Как положить плитку: 5 ошибок", listOf("плитка", "ошибки", "ванная")))
    }
}

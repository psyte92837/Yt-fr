/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure graph algorithms (no Android dependencies, covered by unit tests). */
object GraphAlgo {
    /**
     * PageRank over a directed graph given as edges (from index -> to index).
     * Dangling nodes distribute their rank uniformly. Result sums to 1.
     */
    fun pageRank(n: Int, edges: List<Pair<Int, Int>>, damping: Double = 0.85, iterations: Int = 40): DoubleArray {
        if (n == 0) return DoubleArray(0)
        val out = IntArray(n)
        edges.forEach { out[it.first]++ }
        var pr = DoubleArray(n) { 1.0 / n }
        repeat(iterations) {
            val next = DoubleArray(n) { (1 - damping) / n }
            var dangling = 0.0
            for (i in 0 until n) if (out[i] == 0) dangling += pr[i]
            val share = damping * dangling / n
            for (i in 0 until n) next[i] += share
            for ((a, b) in edges) next[b] += damping * pr[a] / out[a]
            pr = next
        }
        return pr
    }

    /**
     * Fruchterman–Reingold force-directed layout in the unit square.
     * Returns x and y arrays in [0, 1]. Deterministic for a given [seed].
     */
    fun layout(n: Int, edges: List<Pair<Int, Int>>, iterations: Int = if (n > 600) 120 else 260, seed: Long = 7L): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        if (n == 0) return x to y
        val rnd = java.util.Random(seed)
        for (i in 0 until n) {
            x[i] = rnd.nextDouble()
            y[i] = rnd.nextDouble()
        }
        if (n == 1) {
            x[0] = 0.5
            y[0] = 0.5
            return x to y
        }
        val k = sqrt(1.0 / n)
        val k2 = k * k
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        var temp = 0.1
        val cool = temp / (iterations + 1)
        repeat(iterations) {
            java.util.Arrays.fill(dx, 0.0)
            java.util.Arrays.fill(dy, 0.0)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ddx = x[i] - x[j]
                    var ddy = y[i] - y[j]
                    var d2 = ddx * ddx + ddy * ddy
                    if (d2 < 1e-9) {
                        ddx = (rnd.nextDouble() - 0.5) * 1e-3
                        ddy = (rnd.nextDouble() - 0.5) * 1e-3
                        d2 = ddx * ddx + ddy * ddy
                    }
                    val f = k2 / d2 // (k^2 / d) / d -> unit vector scaling
                    dx[i] += ddx * f
                    dy[i] += ddy * f
                    dx[j] -= ddx * f
                    dy[j] -= ddy * f
                }
            }
            for ((a, b) in edges) {
                if (a == b) continue
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val d = sqrt(ddx * ddx + ddy * ddy) + 1e-9
                val f = d / k // (d^2 / k) / d
                dx[a] -= ddx * f
                dy[a] -= ddy * f
                dx[b] += ddx * f
                dy[b] += ddy * f
            }
            for (i in 0 until n) {
                // weak gravity keeps disconnected components on screen
                dx[i] += (0.5 - x[i]) * 0.05
                dy[i] += (0.5 - y[i]) * 0.05
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i]) + 1e-9
                val step = min(len, temp)
                x[i] += dx[i] / len * step
                y[i] += dy[i] / len * step
            }
            temp = max(0.002, temp - cool)
        }
        // normalise to [0, 1]
        val minX = x.min()
        val maxX = x.max()
        val minY = y.min()
        val maxY = y.max()
        val w = max(1e-9, maxX - minX)
        val h = max(1e-9, maxY - minY)
        for (i in 0 until n) {
            x[i] = (x[i] - minX) / w
            y[i] = (y[i] - minY) / h
        }
        return x to y
    }
}

/** Keyword relevance with a crude prefix stemmer that works for Russian and English. */
object Relevance {
    fun stem(w: String): String = if (w.length <= 4) w else w.take(max(4, (w.length * 0.7).toInt()))

    fun keywords(text: String): Set<String> = Words.tokens(text, 3).map { stem(it) }.toSet()

    fun matches(keywords: Set<String>, text: String): Boolean {
        if (keywords.isEmpty()) return true
        return Words.tokens(text, 3).any { tok -> keywords.any { tok.startsWith(it) } }
    }
}

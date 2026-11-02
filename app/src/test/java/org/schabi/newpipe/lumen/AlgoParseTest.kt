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

class AlgoParseTest {
    @Test
    fun gradesNeedExactlyCountValues() {
        val ok = AlgoParse.grades("```json\n{\"s\":[3,0,1,-1],\"n\":{\"2\":\"музыка\",\"3\":\"финансы\",\"9\":\"лишнее\"}}\n```", 4)
        assertNotNull(ok.value)
        assertEquals(listOf(3, 0, 1, -1), ok.value!!.s.toList())
        assertEquals(mapOf(2 to "музыка", 3 to "финансы"), ok.value!!.labels)
        val short = AlgoParse.grades("{\"s\":[3,0]}", 4)
        assertNull(short.value)
        assertTrue(short.problem.contains("ровно 4"))
        assertNull(AlgoParse.grades("{\"s\":[3,0,7,1]}", 4).value)
        assertNull(AlgoParse.grades("нет ответа", 2).value)
        assertEquals(listOf(2, 1), AlgoParse.grades("{\"s\":[\"2\",1]}", 2).value!!.s.toList())
    }

    @Test
    fun legacyRelevantListIsAccepted() {
        val g = AlgoParse.grades("{\"relevant\":[1,3]}", 3).value!!
        assertEquals(listOf(2, 0, 2), g.s.toList())
    }

    @Test
    fun topicNeedsCoreAndKeywords() {
        val t = AlgoParse.topic("{\"phrase\":\"ремонт\",\"core\":\"ремонт квартир своими руками\",\"include\":[\"плитка\"],\"exclude\":[\"ремонт авто\"]," +
            "\"adjacent\":[\"дизайн\"],\"keywords\":[\"ремонт\",\"штукатурка\"],\"negative\":[\"авто\"]}").value!!
        assertEquals("ремонт квартир своими руками", t.core)
        assertEquals(listOf("ремонт авто"), t.exclude)
        assertEquals(listOf("авто"), t.negative)
        assertNull(AlgoParse.topic("{\"core\":\"x\"}").value)
        // legacy "domain" works as core
        assertEquals("d", AlgoParse.topic("{\"domain\":\"d\",\"keywords\":[\"k\"]}").value!!.core)
    }

    @Test
    fun nichesKeepOnlyKnownIds() {
        val r = AlgoParse.niches("{\"niches\":[{\"id\":\"g1\",\"name\":\"Плитка\",\"about\":\"a\"},{\"id\":\"g9\",\"name\":\"X\"}]}", setOf("g1", "g2"))
        assertEquals(setOf("g1"), r.value!!.keys)
        assertNull(AlgoParse.niches("{\"niches\":[]}", setOf("g1")).value)
    }

    @Test
    fun opportunitiesAndPitchDropUnknownIds() {
        val o = AlgoParse.opportunities(
            "{\"items\":[{\"niche\":\"g1\",\"title\":\"Как класть плитку\",\"next_to\":[\"v1\",\"v99\"],\"format\":\"tiktok\",\"confidence\":\"high\"},{\"title\":\"\"}]}",
            setOf("g1"), setOf("v1")
        ).value!!
        assertEquals(1, o.size)
        assertEquals(listOf("v1"), o[0].nextTo)
        assertEquals("long", o[0].format)
        assertEquals("high", o[0].confidence)
        val p = AlgoParse.pitch("{\"titles\":[\"A\",\"B\"],\"next_to\":[{\"id\":\"v1\",\"why\":\"w\"},{\"id\":\"v7\"}],\"answers\":[\"[v1]\"],\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}", setOf("v1")).value!!
        assertEquals(listOf("v1" to "w"), p.nextTo)
        assertEquals(listOf("v1"), p.answers)
        assertEquals(5, p.tags.size)
        assertNull(AlgoParse.pitch("{\"titles\":[]}", emptySet()).value)
    }

    @Test
    fun citedIdsAreChecked() {
        val md = "Центр — [v1], мост [c2], и [v77]."
        assertEquals(listOf("v77"), AlgoParse.unknownIds(md, setOf("v1", "c2")))
        assertEquals("Центр — «Видео», мост [c2], и [v77].", AlgoParse.cite(md, mapOf("v1" to "Видео")))
    }
}

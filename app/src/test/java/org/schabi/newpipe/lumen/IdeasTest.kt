/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeasTest {
    private fun c(text: String, likes: Int, reply: Boolean = false) = Comment("", "u", text, likes, 0, "", 0, reply)

    @Test
    fun marketLanguage() {
        assertEquals("en", IdeasLogic.langFor("US"))
        assertEquals("en", IdeasLogic.langFor("gb"))
        assertEquals("es", IdeasLogic.langFor("MX"))
        assertEquals("pt", IdeasLogic.langFor("BR"))
        assertEquals("", IdeasLogic.langFor("ZZ"))
    }

    @Test
    fun parsesQueriesFromJsonArrayAndLines() {
        assertEquals(listOf("coffee shop trends", "latte art"),
            IdeasLogic.parseQueries("```json\n{\"queries\":[\"coffee shop trends\",\"Latte art\",\"latte art\"]}\n```").map { it.lowercase() })
        assertEquals(listOf("a b", "c d"), IdeasLogic.parseQueries("[\"a b\", \"c d\"]"))
        assertEquals(listOf("small bakery ideas", "cafe marketing"), IdeasLogic.parseQueries("1. small bakery ideas\n- \"cafe marketing\""))
    }

    @Test
    fun userQueriesSplitAndCap() {
        assertEquals(listOf("one", "two", "three"), IdeasLogic.splitUser("one, two;\nthree\n\n"))
        assertEquals(IdeasLogic.MAX_QUERIES, IdeasLogic.splitUser((1..10).joinToString("\n") { "query $it" }).size)
    }

    @Test
    fun topCommentsSkipRepliesAndShortSortByLikes() {
        val out = IdeasLogic.topComments(listOf(c("short", 99), c("a reply that is long enough", 50, true),
            c("I wish there was a vegan option", 3), c("Prices went up so much this year", 10)), n = 5)
        assertEquals(2, out.size)
        assertTrue(out[0].startsWith("Prices went up"))
        assertTrue(out[0].endsWith("(👍10)"))
    }

    @Test
    fun dataBlockRespectsLimit() {
        val v = Video(id = "x", url = "u", title = "Title", channel = "Ch", views = 1200)
        val s = IdeasLogic.dataBlock(listOf(v to listOf("good"), v to listOf("bad")))
        assertTrue(s.startsWith("1. Title — Ch · 1200 views\n   > good"))
        assertTrue(s.contains("2. Title"))
        assertEquals(1, IdeasLogic.dataBlock(listOf(v to emptyList(), v to emptyList()), maxChars = 40).lines().size)
    }

    @Test
    fun digestsNewestFirstAndCapped() {
        var list = emptyList<IdeasLogic.Digest>()
        for (i in 1..5) list = IdeasLogic.addDigest(list, IdeasLogic.Digest(i * 10L, i, i, "t$i"), keep = 3)
        assertEquals(listOf(50L, 40L, 30L), list.map { it.at })
        assertEquals("t5", IdeasLogic.Digest.from(JSONObject(list[0].toJson().toString())).text)
    }

    @Test
    fun headlineSkipsMarkdown() {
        assertEquals("Главное", IdeasLogic.headline("## 1. **Главное**\nтекст").removePrefix("1. "))
        assertEquals("abc…", IdeasLogic.headline("abcdef", max = 4))
    }

    @Test
    fun configRoundTrip() {
        val cfg = IdeasConfig("bakery", "DE", mutableListOf("bäckerei"), 12, false, true, "p1")
        assertEquals(cfg, IdeasConfig.from(JSONObject(cfg.toJson().toString())))
    }

    @Test
    fun severalBusinessesUpsertKeepsOrderAndIds() {
        val a = IdeasConfig("cafe", id = "a")
        val b = IdeasConfig("school", "GB", id = "b")
        var list = IdeasLogic.upsert(IdeasLogic.upsert(emptyList(), a), b)
        list = IdeasLogic.upsert(list, a.copy(business = "coffee shop"))
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals("coffee shop", list[0].business)
        val back = IdeasLogic.configsFrom(org.json.JSONArray(IdeasLogic.configsJson(list + a).toString()))
        assertEquals(list, back)
    }

    @Test
    fun configWithoutIdGetsOne() {
        assertTrue(IdeasConfig.from(JSONObject().put("business", "x")).id.isNotEmpty())
    }
}

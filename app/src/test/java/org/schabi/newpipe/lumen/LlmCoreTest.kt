/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCoreTest {
    @Test
    fun tokensDependOnScript() {
        val ru = "Самое частое и залайканное — первым. Не выдумывай того, чего нет в комментариях."
        val en = "Most frequent and most liked first. Do not invent anything that is not in the comments."
        // Cyrillic ~3 characters per token, Latin ~4
        assertTrue(TokenEstimate.of(ru) > ru.length / 3.6)
        assertTrue(TokenEstimate.of(en) < en.length / 3.6)
        assertEquals(1, TokenEstimate.of(""))
    }

    @Test
    fun lightTasksAndEffort() {
        assertTrue(LlmTasks.isLight("algo_judge_videos"))
        assertTrue(LlmTasks.isLight("voc_extract"))
        assertFalse(LlmTasks.isLight("project_summary"))
        assertEquals("low", LlmTasks.openAiEffort("o4-mini", true))
        assertEquals("low", LlmTasks.openAiEffort("gpt-5-mini", true))
        assertNull(LlmTasks.openAiEffort("gpt-4o", true))
        assertNull(LlmTasks.openAiEffort("o4-mini", false))
    }

    @Test
    fun contextCharsForBigModels() {
        assertEquals(120_000, LlmTasks.contextChars("GEMINI", "gemini-3.5-flash-lite", 24_000, false))
        assertEquals(24_000, LlmTasks.contextChars("OPENAI_COMPAT", "llama-3.1-8b-instant", 24_000, false))
        // the user's own setting wins
        assertEquals(24_000, LlmTasks.contextChars("GEMINI", "gemini-3.5-flash-lite", 24_000, true))
    }

    @Test
    fun transcriptParagraphs() {
        val tr = (0 until 40).joinToString("\n") { i -> val s = i * 3; "[${s / 60}:${"%02d".format(s % 60)}] слово$i" + (if (i % 4 == 3) "." else "") }
        val out = Transcript.forAi(tr)
        val lines = out.lines()
        assertTrue(lines.size in 3..6)
        assertTrue(lines.all { it.startsWith("[") })
        assertTrue(out.length < tr.length)
        assertTrue(out.contains("слово0 слово1"))
        assertEquals("[0:00]", lines.first().substringBefore(' '))
        // plain: no timecodes at all
        val plain = Transcript.forAi(tr, timecodes = false)
        assertFalse(plain.contains("["))
        assertTrue(plain.startsWith("слово0 слово1"))
        // text without timecodes stays
        assertEquals("a\nb", Transcript.forAi("a\n\n b"))
    }

    @Test
    fun transcriptHourTimecodes() {
        val out = Transcript.forAi("[1:00:00] a.\n[1:00:10] b.\n[1:00:40] c.")
        assertEquals("[1:00:00] a. b.\n[1:00:40] c.", out)
    }

    @Test
    fun sampleKeepsStartMiddleEnd() {
        val text = (1..400).joinToString("\n") { "line $it of the transcript" }
        val s = Transcript.sample(text, 3000)
        assertTrue(s.length <= 3000)
        assertTrue(s.startsWith("line 1 "))
        assertTrue(s.contains("line 400 "))
        assertTrue(s.contains("line 200 ") || s.contains("line 199 ") || s.contains("line 201 "))
        assertEquals(2, Regex("""\[…]""").findAll(s).count())
        assertEquals("short", Transcript.sample("short", 3000))
    }

    @Test
    fun descriptionsWithoutBoilerplate() {
        val boiler = "Подписывайтесь на наш телеграм канал"
        val descs = listOf(
            "Как выбрать велосипед.\n$boiler\nhttps://t.me/x\n#велосипед #спорт",
            "Ремонт втулки своими руками.\n$boiler\nhttps://t.me/x",
            "Обзор шлема.\n$boiler"
        )
        val b = DescClean.boilerplate(descs)
        assertEquals(1, b.size)
        assertEquals("Как выбрать велосипед.", DescClean.clean(descs[0], b))
        assertEquals("Таймкоды: 0:00 начало", DescClean.clean("Таймкоды: 0:00 начало\nСсылка: https://a.b/c\n#a #b"))
        assertTrue(DescClean.boilerplate(listOf("один")).isEmpty())
    }

    @Test
    fun viewStats() {
        val now = 100L * ViewStats.DAY
        assertEquals(1000.0, ViewStats.perDay(10_000, now - 10 * ViewStats.DAY, now), 0.01)
        assertTrue(ViewStats.perDay(0, now - ViewStats.DAY, now).isNaN())
        val med = ViewStats.channelMedians(listOf("a" to 10.0, "a" to 30.0, "a" to 20.0, "b" to 5.0))
        assertEquals(20.0, med["a"]!!, 0.0)
        assertNull(med["b"])
        assertEquals("1.2k", ViewStats.short(1234.0))
        assertEquals("3.4M", ViewStats.short(3_400_000.0))
        assertEquals("123k", ViewStats.short(123_456.0))
        assertEquals(" · 1k/day · ×5 ch", ViewStats.tag(10_000, now - 10 * ViewStats.DAY, now, 200.0))
        assertEquals("", ViewStats.tag(0, 0, now, null))
    }

    @Test
    fun retrievePicksRelevantDocs() {
        val docs = listOf(
            "#1 Как чинить велосипедную цепь — разбор звеньев",
            "#2 Рецепт борща на говяжьем бульоне",
            "#3 Смазка цепи велосипеда зимой",
            "#4 Обзор смартфона",
            "#5 Новости футбола"
        )
        val pick = Retrieve.topK("как смазать цепь велосипеда", docs, 120, minDocs = 1)
        assertTrue(0 in pick && 2 in pick)
        assertFalse(1 in pick)
        assertEquals(pick.sorted(), pick)
    }

    @Test
    fun jsonTidyAndRepairPrompts() {
        val broken = "```json\n{\"a\":[1,2,],// note\n\"b\":\"x\"}\n```"
        assertFalse(runCatching { JSONObject(broken) }.isSuccess)
        assertTrue(JsonFix.parses(broken))
        assertEquals(2, JSONObject(JsonFix.tidy(broken)).getJSONArray("a").length())
        val rp = JsonFix.repairPrompt("{oops", "it is not valid JSON", "{\"items\":[…]}")
        assertTrue(rp.contains("{oops") && rp.contains("items"))
        assertTrue(JsonFix.repairLimit("{}") >= 256)
    }

    @Test
    fun continuationMergesItems() {
        val cut = """{"title":"Ролик","scenes":[{"from":0,"to":5},{"from":6,"to":9},{"from":10,"""
        val got = JsonFix.items(cut, "scenes")
        assertEquals(2, got.length())
        val ask = JsonFix.continuePrompt("BASE", "scenes", got)
        assertTrue(ask.startsWith("BASE") && ask.contains("after 2 items"))
        val merged = JsonFix.merge(cut, """{"scenes":[{"from":10,"to":14}]}""", "scenes")!!
        val o = JSONObject(merged)
        assertEquals("Ролик", o.getString("title"))
        assertEquals(3, o.getJSONArray("scenes").length())
        assertEquals(10, o.getJSONArray("scenes").getJSONObject(2).getInt("from"))
        assertNull(JsonFix.merge(cut, "{}", "scenes"))
    }

    @Test
    fun vocBatchesParseBlock() {
        val lines = (1..10).map { VocCore.Line("c$it", "комментарий номер $it про цепь", it * 10) }
        val b = VocCore.batches(lines, 120)
        assertTrue(b.size > 1)
        assertEquals(lines, b.flatten())
        assertEquals(VocCore.key(b[0]), VocCore.key(b[0].toList()))
        val reply = """{"i":[{"t":"pain","s":"Цепь слетает","ids":["c1","c2","c99"],"sev":4},
            {"t":"weird","s":"Мнение","ids":["c3"]},{"t":"question","s":"","ids":["c4"]},{"t":"idea","s":"Без id","ids":["c77"]}]}"""
        val items = VocCore.parse(reply, lines.map { it.id }.toSet())!!
        assertEquals(2, items.size)
        assertEquals(listOf("c1", "c2"), items[0].ids)
        assertEquals("opinion", items[1].type)
        assertNull(VocCore.parse("не JSON", setOf("c1")))
        val again = VocCore.fromJson(VocCore.toJson(items))
        assertEquals(items, again)
        assertEquals(mapOf("pain" to 2, "opinion" to 1), VocCore.counts(items))
        val block = VocCore.block(items, lines.associate { it.id to it.likes }, 1000)
        assertTrue(block.startsWith("## pain (2 comments)\n- [×2 ♥30 sev4] Цепь слетает (c1,c2)"))
        assertTrue(VocCore.SCHEMA.getJSONObject("properties").has("i"))
    }

    @Test
    fun digestQuotesById() {
        val byId = mapOf("c3" to VocCore.Line("c3", "Цепь слетает каждый день", 12))
        val md = "## Цитаты\n- c3\n- c99\n- уже с текстом (c3)\n## Выводы"
        val out = VocCore.expandQuotes(md, byId)
        assertEquals("## Цитаты\n- «Цепь слетает каждый день» ♥12 (c3)\n- c99\n- уже с текстом (c3)\n## Выводы", out)
    }

    @Test
    fun salvageStillWorks() {
        assertEquals(JSONArray().length(), JsonFix.items("{}", "x").length())
        assertNotNull(JsonFix.items("""{"x":[{"a":1}""", "x").optJSONObject(0))
    }

    @Test
    fun overloadedGeminiMovesToNeighbourModels() {
        val busy = """{"error":{"code":503,"message":"This model is currently experiencing high demand.","status":"UNAVAILABLE"}}"""
        assertTrue(LlmBusy.overloaded(503, busy))
        assertTrue(LlmBusy.overloaded(529, "overloaded_error"))
        assertTrue(LlmBusy.overloaded(429, "Generate content requests per minute limit exceeded"))
        assertFalse(LlmBusy.overloaded(429, "Generate content requests per day limit exceeded on Free Tier"))
        assertFalse(LlmBusy.overloaded(400, "User location is not supported for the API use."))
        assertFalse(LlmBusy.overloaded(500, "Internal error"))

        val alt = LlmBusy.alternates("gemini-3.5-flash-lite")
        assertEquals("gemini-3.1-flash-lite", alt.first())
        assertFalse("gemini-3.5-flash-lite" in alt)
        assertFalse(alt.any { it.startsWith("gemini-2.5") })
        assertEquals("gemini-flash-latest", LlmBusy.alternates("gemini-3.5-flash").first())
        assertTrue(LlmBusy.alternates("gpt-4o").isEmpty())
        assertTrue(LlmBusy.alternates("gemini-3.8-flash-tts").isEmpty())

        val now = 1_000L
        assertEquals("gemini-3.5-flash-lite", LlmBusy.order("gemini-3.5-flash-lite", emptyMap(), now).first())
        val o = LlmBusy.order("gemini-3.5-flash-lite", mapOf("gemini-3.5-flash-lite" to now + LlmBusy.REST_MS), now)
        assertEquals("gemini-3.1-flash-lite", o.first())
        assertEquals("gemini-3.5-flash-lite", o.last())
        assertEquals("gemini-3.5-flash-lite", LlmBusy.order("gemini-3.5-flash-lite", mapOf("gemini-3.5-flash-lite" to now - 1), now).first())
    }

    @Test
    fun fatalApiErrorsAreNotRetried() {
        val region = """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}"""
        assertEquals(LlmErrors.Kind.REGION, LlmErrors.fatal(400, region))
        assertEquals(LlmErrors.Kind.KEY, LlmErrors.fatal(400, """{"error":{"message":"API key not valid","details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertEquals(LlmErrors.Kind.DAILY, LlmErrors.fatal(429, "Generate content requests per day limit exceeded on Free Tier"))
        assertNull(LlmErrors.fatal(429, "Generate content requests per minute limit exceeded on Free Tier"))
        assertNull(LlmErrors.fatal(400, "Invalid JSON payload: unknown field responseSchema"))
        assertNull(LlmErrors.fatal(503, "overloaded"))
    }
}

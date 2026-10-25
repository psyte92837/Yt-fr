/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val DAY = 86_400_000L

private fun video(id: String, channel: String = "A", views: Long = 100, uploadMs: Long = -1, likes: Long = -1, comments: Int = 0) =
    Video(id = id, url = "https://www.youtube.com/watch?v=$id", title = "title $id", channel = channel, views = views,
        uploadMs = uploadMs, likes = likes, commentsCount = comments)

class ModelsTest {
    @Test
    fun projectRoundTripKeepsAllSettings() {
        val p = Project(name = "EV", phrase = "электромобили", domain = "авто", queries = mutableListOf("ev", "tesla"))
        p.filters.period = "custom"
        p.filters.from = "2026-01-01"
        p.filters.excludeShorts = true
        p.collect.commentsLimit = 500
        p.monitor.enabled = true
        p.monitor.recDrip = 3
        p.recs.depth = 12
        p.recs.relevance = "ai"
        p.recs.blocked = "spam channel"
        p.seo.country = "KG"
        p.seo.existingPages = "https://x/a | A"
        p.runs.add(RunRecord(1L, "search", 10, 5, "n"))
        val back = Project.from(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
    }

    @Test
    fun missingFieldsGetDefaults() {
        val p = Project.from(JSONObject("""{"id":"x","phrase":"q"}"""))
        assertEquals(10, p.recs.depth)
        assertEquals("keywords", p.recs.relevance)
        assertEquals(0, p.monitor.recDrip)
        assertEquals(listOf("q"), p.effectiveQueries())
    }

    @Test
    fun videoAndCommentRoundTrip() {
        val v = video("abcdefghijk", likes = 7, comments = 3).apply {
            labels = mutableListOf("важное")
            tags = mutableListOf("t1")
            hasTranscript = true
            transcriptSource = "auto"
        }
        assertEquals(v, Video.from(JSONObject(v.toJson().toString())))
        val c = Comment("cid", "u_1234", "текст", 5, 10L, "1 day ago", 2, true)
        assertEquals(c, Comment.from(JSONObject(c.toJson().toString())))
        // old comment files without id still load
        assertEquals("", Comment.from(JSONObject("""{"author":"a","text":"t"}""")).id)
    }

    @Test
    fun recNodeRoundTripAndVideoConversion() {
        val n = RecNode("abcdefghijk", title = "T", channel = "C", depth = 3, hits = 4, weight = 1.5, pr = 0.01, relevant = 1, seed = true)
        assertEquals(n, RecNode.from(JSONObject(n.toJson().toString())))
        val v = n.toVideo("rec")
        assertEquals("rec", v.query)
        assertEquals(n.url, v.url)
    }
}

class RecGraphTest {
    private fun graph(): RecGraph {
        val g = RecGraph()
        listOf("s", "a", "b", "c").forEach { g.nodes[it] = RecNode(it, depth = if (it == "s") 0 else 1, seed = it == "s") }
        g.addEdge("s", "a", 0)
        g.addEdge("s", "b", 1)
        g.addEdge("a", "b", 0)
        g.addEdge("c", "b", 2)
        return g
    }

    @Test
    fun edgesAreDedupedAndCountHits() {
        val g = graph()
        assertFalse(g.addEdge("s", "a", 3))
        assertFalse(g.addEdge("a", "a", 0))
        assertEquals(4, g.edges.size)
        assertEquals(3, g.nodes["b"]!!.hits)
        assertEquals(1.0 / 2 + 1.0 + 1.0 / 3, g.nodes["b"]!!.weight, 1e-9)
        assertEquals(listOf("s", "a", "c"), g.parents("b").map { it.id })
        assertEquals(listOf("a", "b"), g.children("s").map { it.id })
    }

    @Test
    fun rankingAndPageRank() {
        val g = graph()
        g.computePageRank()
        assertEquals(1.0, g.nodes.values.sumOf { it.pr }, 1e-6)
        assertEquals("b", RecEngine.ranked(g, "hits").first().id)
        assertEquals("b", RecEngine.ranked(g, "pr").first().id)
        assertTrue(RecEngine.ranked(g, "hits").none { it.seed })
    }

    @Test
    fun driftByDepth() {
        val g = graph()
        g.nodes["a"]!!.relevant = 1
        g.nodes["b"]!!.relevant = 0
        g.nodes["s"]!!.relevant = 1
        val d = RecEngine.drift(g)
        assertEquals(listOf(0, 1), d.map { it.depth })
        assertEquals(3, d[1].nodes)
        assertEquals(1, d[1].onTopic)
    }

    @Test
    fun jsonShape() {
        val o = graph().toJson()
        assertEquals(4, o.getJSONArray("nodes").length())
        assertEquals(4, o.getJSONArray("edges").length())
        assertEquals("s", o.getJSONArray("edges").getJSONArray(0).getString(0))
    }

    @Test
    fun requestEstimate() {
        val c = RecConfig(budget = 150, depth = 10)
        assertEquals(150, RecEngine.estimateRequests(c, 5))
        assertEquals(50, RecEngine.estimateRequests(c.copy(mode = "chain"), 5))
        assertEquals(5, RecEngine.estimateRequests(c.copy(mode = "mix"), 5))
    }
}

class AnalyticsTest {
    private val now = System.currentTimeMillis()

    @Test
    fun statsAndTopChannels() {
        val vids = listOf(video("1", "A", 100, now - DAY), video("2", "A", 300, now - 2 * DAY), video("3", "B", 1000, now - DAY, comments = 4), video("4", "C", -1))
        val s = Analytics.stats(vids)
        assertEquals(4, s.total)
        assertEquals(1400, s.totalViews)
        assertEquals(466, s.avgViews)
        assertEquals(4, s.comments)
        assertEquals("A", Analytics.topChannels(vids, "videos").first().name)
        assertEquals("B", Analytics.topChannels(vids, "views").first().name)
    }

    @Test
    fun trendGroupsByDay() {
        val base = now - 10 * DAY
        val vids = listOf(video("1", uploadMs = base), video("2", uploadMs = base + 1000), video("3", uploadMs = base + 3 * DAY))
        val t = Analytics.trend(vids, "day")
        assertEquals(2, t.size)
        assertEquals(2, t[0].count)
        assertEquals(200, t[0].views)
        assertTrue(Analytics.trend(emptyList()).isEmpty())
    }

    @Test
    fun velocityAndEngagement() {
        val fresh = video("1", views = 10_000, uploadMs = now - DAY)
        val old = video("2", views = 50_000, uploadMs = now - 100 * DAY)
        assertEquals("1", Analytics.topVideos(listOf(old, fresh), "velocity").first().id)
        assertEquals("2", Analytics.topVideos(listOf(old, fresh), "views").first().id)
        assertEquals(-1, Analytics.velocity(video("3")))
        assertEquals("—", Analytics.engagementPct(video("4", likes = -1)))
        assertEquals("2.00%", Analytics.engagementPct(video("5", views = 1000, likes = 15, comments = 5)))
    }

    @Test
    fun youtubeClientSideSort() {
        val list = listOf(video("1", views = 5, uploadMs = 3), video("2", views = 50, uploadMs = 1), video("3", views = 1, uploadMs = 9))
        assertEquals(listOf("2", "1", "3"), YouTube.sort(list, "views").map { it.id })
        assertEquals(listOf("3", "1", "2"), YouTube.sort(list, "date").map { it.id })
        assertEquals(listOf("1", "2", "3"), YouTube.sort(list, "relevance").map { it.id })
    }
}

class LlmParseTest {
    @Test
    fun openAiCompatible() {
        val r = LlmParse.openAi("""{"model":"gpt-x","choices":[{"message":{"content":"Привет"}}],"usage":{"prompt_tokens":12,"completion_tokens":3}}""", "m", 99)
        assertEquals("Привет", r.text)
        assertEquals(12, r.inTokens)
        assertEquals(3, r.outTokens)
        assertEquals("gpt-x", r.model)
        // no usage → estimates; array content (some gateways)
        val r2 = LlmParse.openAi("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""", "m", 42)
        assertEquals("ab", r2.text)
        assertEquals(42, r2.inTokens)
        assertEquals("m", r2.model)
    }

    @Test
    fun openAiErrorsThrow() {
        try {
            LlmParse.openAi("""{"error":{"message":"quota"}}""", "m", 1)
            fail()
        } catch (e: LlmException) {
            assertEquals("quota", e.message)
        }
        try {
            LlmParse.openAi("""{"choices":[]}""", "m", 1)
            fail()
        } catch (e: LlmException) {
            assertEquals("empty response", e.message)
        }
    }

    @Test
    fun anthropicConcatenatesTextBlocks() {
        val r = LlmParse.anthropic("""{"model":"claude","content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"A"},{"type":"text","text":"B"}],"usage":{"input_tokens":5,"output_tokens":2}}""", "m")
        assertEquals("AB", r.text)
        assertEquals(5, r.inTokens)
        assertEquals("claude", r.model)
    }

    @Test
    fun geminiSkipsThoughtsAndReportsBlocks() {
        val r = LlmParse.gemini("""{"candidates":[{"content":{"parts":[{"text":"t","thought":true},{"text":"ok"}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":1}}""", "g")
        assertEquals("ok", r.text)
        assertEquals(7, r.inTokens)
        try {
            LlmParse.gemini("""{"promptFeedback":{"blockReason":"SAFETY"}}""", "g")
            fail()
        } catch (e: LlmException) {
            assertEquals("Gemini: SAFETY", e.message)
        }
        try {
            LlmParse.gemini("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{}}]}""", "g")
            fail()
        } catch (e: LlmException) {
            assertEquals("Gemini: MAX_TOKENS", e.message)
        }
    }

    @Test
    fun modelLists() {
        assertEquals(listOf("gemini-a"), LlmParse.models("""{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/emb","supportedGenerationMethods":["embedContent"]}]}""", true))
        assertEquals(listOf("a", "b"), LlmParse.models("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""", false))
    }

    @Test
    fun errorMessages() {
        assertEquals("bad key", LlmParse.errorMessage("""{"error":{"message":"bad key"}}"""))
        assertEquals("nope", LlmParse.errorMessage("""{"message":"nope"}"""))
        assertEquals("<html>", LlmParse.errorMessage("<html>"))
    }
}

class ImportParserTest {
    @Test
    fun csvWithHeaderAndAnonymisation() {
        val p = ImportParser.parse("c.csv", "author,text,likes\nИван,\"Не работает, помогите\",5\n,Без автора,\n", true)
        assertEquals(2, p.comments.size)
        assertEquals("Не работает, помогите", p.comments[0].text)
        assertEquals(5, p.comments[0].likes)
        assertTrue(p.comments[0].author.startsWith("u_"))
        assertEquals("", p.comments[1].author)
        assertEquals("", p.transcript)
    }

    @Test
    fun csvWithoutHeaderUsesFirstColumn() {
        val p = ImportParser.parse("c.csv", "первый\nвторой\n", false)
        assertEquals(listOf("первый", "второй"), p.comments.map { it.text })
    }

    @Test
    fun jsonFormats() {
        val a = ImportParser.parse("a.json", """["один", {"text":"два","author":"x","likes":3}, {"nope":1}]""", false)
        assertEquals(listOf("один", "два"), a.comments.map { it.text })
        assertEquals("x", a.comments[1].author)
        val o = ImportParser.parse("o.json", """{"title":"Видео","transcript":"[00:01] привет","comments":["к1"]}""", false)
        assertEquals("Видео", o.title)
        assertEquals("[00:01] привет", o.transcript)
        assertEquals(1, o.comments.size)
    }

    @Test
    fun textAndVtt() {
        assertEquals("просто текст", ImportParser.parse("t.txt", "  просто текст \n", false).transcript)
        val v = ImportParser.parse("s.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nстрока\n", false)
        assertEquals("[00:01] строка", v.transcript)
    }
}

class SeoPureTest {
    @Test
    fun sourceUrls() {
        val v = video("abcdefghijk")
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&lc=UgxABC", Seo.sourceUrl(v, "UgxABC", "01:00"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&t=65s", Seo.sourceUrl(v, null, "01:05"))
        assertEquals(v.url, Seo.sourceUrl(v, null, null))
        assertEquals("", Seo.sourceUrl(Video(id = LOCAL_PREFIX + "x", url = ""), null, "01:05"))
        assertEquals("", Seo.sourceUrl(null, null, null))
    }

    @Test
    fun checklistMatchesDocument() {
        assertEquals(6, SeoChecklist.sections.size)
        assertEquals(32, SeoChecklist.total)
        assertEquals(listOf("", "pass", "revise", "fail", "not_applicable"), SeoChecklist.states)
    }

    @Test
    fun promptsHaveTheirInputs() {
        val need = mapOf(
            "p1_insights" to listOf("{project_context}", "{transcript}", "{comments}"),
            "p2_clusters" to listOf("{insights}"),
            "p3_qualify" to listOf("{topic}", "{evidence}", "{existing_pages}", "{keyword_data}", "{serp_data}"),
            "p4_brief" to listOf("{topic}", "{research}", "{serp_notes}", "{site_context}"),
            "p5_article" to listOf("{brief}", "{sources}", "{product_context}", "---META---"),
            "p6_quality" to listOf("{article}", "{brief}", "{sources}", "{existing_pages}"),
            "p7_ideas" to listOf("{clusters}", "{product}")
        )
        assertEquals(need.keys, SEO_PROMPTS.keys)
        need.forEach { (k, list) -> list.forEach { ph -> assertTrue("$k misses $ph", SEO_PROMPTS[k]!!.contains(ph)) } }
        SEO_PROMPTS.values.forEach { assertTrue(it.contains("{lang}")) }
    }

    @Test
    fun insightTypesFollowSchema() {
        assertEquals(setOf("question", "pain", "jtbd", "voc", "search_intent", "objection", "unmet_need", "content_gap", "topic", "evidence", "hypothesis"), Seo.INSIGHT_TYPES)
    }
}

class FmtTest {
    @Test
    fun numbersAndDurations() {
        assertEquals("—", Fmt.num(-1))
        assertEquals("999", Fmt.num(999))
        assertEquals("1.5K", Fmt.num(1500))
        assertEquals("25K", Fmt.num(25_000))
        assertEquals("3.2M", Fmt.num(3_200_000))
        assertEquals("1:05", Fmt.duration(65))
        assertEquals("1:01:01", Fmt.duration(3661))
        assertEquals("—", Fmt.duration(0))
    }

    @Test
    fun isoDays() {
        val ms = Fmt.parseIsoDay("2026-10-26")
        assertTrue(ms > 0)
        assertEquals("2026-10-26", Fmt.isoDay(ms))
        assertEquals(-1L, Fmt.parseIsoDay("not a date"))
    }

    @Test
    fun translationFallsBackWithoutContext() {
        assertFalse(LumenEnv.ready)
        assertEquals("ру", t("ру", "en"))
    }
}

class TruncationTest {
    @Test
    fun salvageCompleteItemsFromCutJson() {
        val cut = """{"items":[{"type":"pain","statement":"a {x}","quote":"q1"},{"type":"voc","statement":"b","quote":"Бесплатно рассказали и объяснили то, что люди продают за"""
        val s = JsonSalvage.array(cut, "items")!!
        val o = JSONObject(s)
        assertEquals(1, o.getJSONArray("items").length())
        assertEquals("a {x}", o.getJSONArray("items").getJSONObject(0).getString("statement"))
        assertEquals(null, JsonSalvage.array("""{"items":[{"type":"pa""", "items"))
        assertEquals(null, JsonSalvage.array("no json", "items"))
    }

    @Test
    fun providersReportTruncation() {
        assertTrue(LlmParse.gemini("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\"items\":["}]}}]}""", "g").truncated)
        assertFalse(LlmParse.gemini("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""", "g").truncated)
        assertTrue(LlmParse.openAi("""{"choices":[{"finish_reason":"length","message":{"content":"x"}}]}""", "m", 1).truncated)
        assertTrue(LlmParse.anthropic("""{"stop_reason":"max_tokens","content":[{"type":"text","text":"x"}]}""", "m").truncated)
    }
}

class QuickAndChannelsTest {
    @Test
    fun sharedLinksAreClassified() {
        assertEquals(YtLink.Link("video", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            YtLink.classify("Смотри это видео https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("video", YtLink.classify("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123").type)
        assertEquals("video", YtLink.classify("https://youtube.com/shorts/abcdefghijk?feature=share").type)
        assertEquals(YtLink.Link("playlist", "https://www.youtube.com/playlist?list=PLx_1-2"),
            YtLink.classify("https://youtube.com/playlist?list=PLx_1-2&si=q"))
        assertEquals(YtLink.Link("channel", "https://www.youtube.com/@mkbhd"), YtLink.classify("https://youtube.com/@mkbhd?si=x"))
        assertEquals(YtLink.Link("channel", "https://www.youtube.com/channel/UC123_abc"), YtLink.classify("https://www.youtube.com/channel/UC123_abc/videos"))
        assertEquals("unknown", YtLink.classify("просто текст").type)
        assertEquals("unknown", YtLink.classify("https://example.com/watch?v=dQw4w9WgXcQ").type)
    }

    @Test
    fun channelStats() {
        val now = 100 * DAY
        val recent = listOf(
            video("1", views = 100, uploadMs = now - 1 * DAY).apply { duration = 600 },
            video("2", views = 300, uploadMs = now - 8 * DAY).apply { duration = 30; isShort = true },
            video("3", views = 1000, uploadMs = now - 15 * DAY).apply { duration = 1200 },
            video("4", views = 200, uploadMs = now - 22 * DAY).apply { duration = 600 }
        )
        val s = ChannelStats.of(recent, now)
        assertEquals(4, s.videos)
        assertEquals(400, s.avgViews)
        assertEquals(250, s.medianViews)
        assertEquals(1000, s.maxViews)
        assertEquals(now - DAY, s.lastUploadMs)
        assertEquals(4 / (22.0 / 7), s.perWeek, 1e-6)
        assertEquals(0.25, s.shortsShare, 1e-9)
        assertEquals("3", s.top.first().id)
        val empty = ChannelStats.of(emptyList(), now)
        assertEquals(-1, empty.avgViews)
        assertEquals(0.0, empty.perWeek, 0.0)
    }

    @Test
    fun channelLinksAndKeys() {
        assertEquals(listOf("https://t.me/chan", "https://site.ru/a?b=1"),
            ChannelStats.links("Пишите: https://t.me/chan. Сайт (https://site.ru/a?b=1), снова https://t.me/chan"))
        assertEquals(ChannelStore.key("https://m.youtube.com/@Chan/"), ChannelStore.key("https://www.youtube.com/@chan?si=1"))
    }

    @Test
    fun modelsRoundTrip() {
        val c = ChannelData("https://www.youtube.com/@x", name = "X", subscribers = 5, links = mutableListOf("https://a"),
            playlists = mutableListOf(PlaylistRef("P", "https://www.youtube.com/playlist?list=1", 7)), featured = mutableListOf("https://www.youtube.com/@y"),
            recent = mutableListOf(video("abcdefghijk")), fetchedAt = 9)
        assertEquals(c, ChannelData.from(JSONObject(c.toJson().toString())))
        val q = QuickConfig(recVideos = 50, graphChannels = 30, graphDepth = 3, aiSummary = false)
        assertEquals(q, QuickConfig.from(JSONObject(q.toJson().toString())))
        assertEquals(100, QuickConfig.from(null).recVideos)
        assertEquals(100, QuickConfig.from(null).graphChannels)
        val p = Project(name = "n", source = "https://youtu.be/x", sourceType = "video")
        assertEquals(p, Project.from(JSONObject(p.toJson().toString())))
    }
}

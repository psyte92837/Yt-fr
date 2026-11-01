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
import java.io.File

class FreeAiTest {
    private val json = """
        {"protocol":"lumen.freeai v1","version":"2026.11.01","upstream":"test","hash":"h1",
         "providers":[
          {"id":"groq","title":"Groq","base":"https://api.groq.com/openai/v1","api":"openai","key":"https://console.groq.com/keys","keyless":false},
          {"id":"google","title":"Google AI Studio","base":"https://generativelanguage.googleapis.com/v1beta","api":"gemini","key":"","keyless":false},
          {"id":"kilo","title":"Kilo","base":"https://api.kilo.ai/api/gateway/v1","api":"openai","key":"","keyless":true},
          {"id":"bad","title":"Bad","base":"http://insecure","api":"openai","key":"","keyless":true}],
         "models":[
          {"p":"groq","id":"big","name":"Big","intel":2,"speed":5,"ctx":131072,"rpm":30,"rpd":100,"tpm":0,"tpd":0},
          {"p":"groq","id":"fast","name":"Fast","intel":20,"speed":1,"ctx":131072,"rpm":30,"rpd":14400,"tpm":0,"tpd":0},
          {"p":"google","id":"gem","name":"Gem","intel":3,"speed":4,"ctx":1048576,"rpm":10,"rpd":20,"tpm":0,"tpd":0},
          {"p":"kilo","id":"k","name":"K","intel":10,"speed":9,"ctx":8000,"rpm":0,"rpd":0,"tpm":0,"tpd":0},
          {"p":"bad","id":"x","name":"X","intel":1,"speed":1,"ctx":0,"rpm":0,"rpd":0,"tpm":0,"tpd":0},
          {"p":"nope","id":"y","name":"Y","intel":1,"speed":1,"ctx":0,"rpm":0,"rpd":0,"tpm":0,"tpd":0}]}
    """.trimIndent()
    private val cat = FreeAi.parse(json)!!
    private val now = 1_790_000_000_000L
    private val keys = setOf("groq", "google")
    private val has: (String) -> Boolean = { it in keys }

    @Test
    fun parsesAndDropsBrokenRows() {
        assertEquals(listOf("groq", "google", "kilo"), cat.providers.map { it.id })
        assertEquals(4, cat.models.size)
        assertNull(FreeAi.parse(json.replace("lumen.freeai v1", "other v1")))
        assertNull(FreeAi.parse("not json"))
        assertEquals("gemini", cat.prov("google")!!.api)
    }

    @Test
    fun smartPrefersIntelligenceFastPrefersSpeedAndProvidersAlternate() {
        val smart = FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(smart = true))
        assertEquals(listOf("groq/big", "google/gem", "kilo/k", "groq/fast"), smart.map { it.key })
        val fast = FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(smart = false))
        // speed counts ×3, intelligence ×1: Gem (4, 3) beats Fast (1, 20)
        assertEquals(listOf("google/gem", "groq/big", "kilo/k", "groq/fast"), fast.map { it.key })
        // providers alternate: the second try is another provider
        assertTrue(fast[0].p != fast[1].p)
    }

    @Test
    fun skipsNoKeyCooldownDailyLimitContextAndOff() {
        val use = FreeUse(FreeAi.day(now))
        val noKeys = FreeAi.candidates(cat, { false }, use, now, FreeAi.Need(true))
        assertEquals(listOf("kilo/k"), noKeys.map { it.key })
        use.slot("groq/big").n = 100
        use.slot("google/gem").coolUntil = now + 1000
        val left = FreeAi.candidates(cat, has, use, now, FreeAi.Need(true)).map { it.key }
        assertFalse("groq/big" in left)
        assertFalse("google/gem" in left)
        // too long for the 8k model
        val long = FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(true, inTokens = 9000)).map { it.key }
        assertFalse("kilo/k" in long)
        val off = FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(true), off = setOf("groq")).map { it.p }
        assertFalse("groq" in off)
    }

    @Test
    fun videoAndImagesNeedGeminiOrVision() {
        assertEquals(listOf("google/gem"), FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(true, video = true)).map { it.key })
        assertEquals(listOf("google/gem"), FreeAi.candidates(cat, has, FreeUse(), now, FreeAi.Need(true, vision = true)).map { it.key })
    }

    @Test
    fun classifiesErrors() {
        assertEquals(FreeAi.Fail.RATE, FreeAi.classify(429, "Rate limit reached for requests per minute"))
        assertEquals(FreeAi.Fail.DAILY, FreeAi.classify(429, "You exceeded your current quota"))
        assertEquals(FreeAi.Fail.DAILY, FreeAi.classify(429, "Rate limit reached for requests per day (RPD)"))
        assertEquals(FreeAi.Fail.AUTH, FreeAi.classify(401, "Invalid API Key"))
        assertEquals(FreeAi.Fail.MODEL, FreeAi.classify(404, "Not found"))
        assertEquals(FreeAi.Fail.MODEL, FreeAi.classify(400, "The model `x` has been decommissioned"))
        assertEquals(FreeAi.Fail.REQUEST, FreeAi.classify(400, "max_tokens is too large"))
        assertEquals(FreeAi.Fail.SERVER, FreeAi.classify(503, "overloaded"))
        assertEquals(FreeAi.Fail.NETWORK, FreeAi.classify(null, "timeout"))
    }

    @Test
    fun cooldownsGrowAndResetOnSuccess() {
        val use = FreeUse(FreeAi.day(now))
        val m = cat.models.first { it.key == "groq/big" }
        FreeAi.failed(use, m, FreeAi.Fail.RATE, "429", now)
        assertEquals(now + 60_000, use.slots[m.key]!!.coolUntil)
        FreeAi.failed(use, m, FreeAi.Fail.RATE, "429", now)
        assertEquals(now + 120_000, use.slots[m.key]!!.coolUntil)
        repeat(10) { FreeAi.failed(use, m, FreeAi.Fail.RATE, "429", now) }
        assertEquals(now + 15 * 60_000, use.slots[m.key]!!.coolUntil)
        FreeAi.failed(use, m, FreeAi.Fail.DAILY, "quota", now)
        assertEquals(FreeAi.nextUtcMidnight(now), use.slots[m.key]!!.coolUntil)
        assertEquals(0L, FreeAi.nextUtcMidnight(now) % (24 * 3600_000L))
        // a key error rests the whole provider
        FreeAi.failed(use, m, FreeAi.Fail.AUTH, "401", now)
        assertEquals("key error", FreeAi.blocked(cat, cat.models.first { it.key == "groq/fast" }, has, use, now, FreeAi.Need(false), emptySet()))
        FreeAi.succeeded(use, m, 500)
        assertEquals(0, use.slots[m.key]!!.fails)
        assertEquals(0L, use.slots["groq/*"]!!.coolUntil)
        assertEquals(1, use.slots[m.key]!!.n)
    }

    @Test
    fun newDayResetsCountersButKeepsLongCooldowns() {
        val use = FreeUse(FreeAi.day(now))
        use.slot("groq/big").apply { n = 50; tok = 9000 }
        use.slot("google/gem").apply { coolUntil = now + 3 * 24 * 3600_000L; fails = 1 }
        FreeAi.roll(use, now + 24 * 3600_000L)
        assertNull(use.slots["groq/big"])
        assertEquals(0, use.slots["google/gem"]!!.n)
        assertTrue(use.slots["google/gem"]!!.coolUntil > now)
    }

    @Test
    fun useStateRoundTrips() {
        val use = FreeUse("2026-11-01")
        use.slot("groq/big").apply { n = 3; tok = 1200; coolUntil = 99; fails = 2; err = "HTTP 429" }
        val back = FreeAi.useOf(FreeAi.useJson(use))
        assertEquals("2026-11-01", back.day)
        assertEquals(FreeSlot(3, 1200, 99, 2, "HTTP 429"), back.slots["groq/big"])
        assertEquals(0, FreeAi.useOf("garbage").slots.size)
    }

    @Test
    fun newerCatalog() {
        assertTrue(FreeAi.newer(null, cat))
        assertFalse(FreeAi.newer(cat, cat))
        assertTrue(FreeAi.newer(cat, cat.copy(hash = "h2")))
        assertFalse(FreeAi.newer(cat, cat.copy(hash = "h2", version = "2026.10.01")))
    }

    @Test
    fun bundledCatalogIsValid() {
        val f = listOf(File("src/main/assets/lumen_freeai.json"), File("app/src/main/assets/lumen_freeai.json")).first { it.exists() }
        val c = FreeAi.parse(f.readText())
        assertNotNull(c)
        assertTrue(c!!.providers.size >= 3)
        assertTrue(c.models.size >= 10)
        assertTrue(c.providers.all { it.base.startsWith("https://") })
        assertTrue(c.models.all { c.prov(it.p) != null })
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Robustness (fuzz) tests for every parser that reads text Lumen does not control: AI answers, user input in time
 * fields, project files on disk that may be truncated or written by an older/newer version.
 *
 * Contract checked here: such a parser never throws — it returns null/empty/defaults instead, and numeric results stay
 * in their domain (times are never negative). The corpus is deterministic (fixed seed), so a failure is reproducible:
 * the message names the parser and the exact input.
 */
class ParserFuzzTest {

    /** Hand-picked hostile inputs: the cases that typically break number/JSON parsing. */
    private val nasty = listOf(
        "", " ", "\n\t", "\u0000", "﻿{}", "null", "true", "0", "-0", "-1", "-1:00", "1:-30",
        "NaN", "-NaN", "Infinity", "-Infinity", "1e309", "-1e309", "1e18", "9223372036854775807", "99999999999999999999:99",
        "9223372036854775807:00", "153722867280912931:00:00", "2562047788015216h", "153722867280912931m", "1:NaN", "0:Infinity",
        "1:2:3:4:5", ":", "::", "1:", ":5", "0,5", "1.2.3", "٣", "１２", "½", "1_000", "0x1F", "--5", "+5",
        "{", "}", "[", "]", "{]", "[}", "{\"a\":", "{\"a\":[1,2", "[{\"start\":", "```json\n{", "```", "``````",
        "{\"nodes\":\"x\"}", "{\"nodes\":[null,1,\"a\",{}]}", "{\"items\":{}}", "[null]", "[[[]]]", "{\"tracks\":5}",
        "[" .repeat(2000), "{\"a\":".repeat(500), "\"".repeat(101), "\\".repeat(33),
        "🎬".repeat(300), "\uD83D", "\uDE00 lone surrogate", "‮RTL override", "a".repeat(100_000),
    )

    /** Valid-looking samples of the formats Lumen expects, used as seeds for mutation. */
    private val seeds = listOf(
        """{"layout":"chain","title":"T","nodes":[{"label":"A","at":"word"},{"label":"B"}],"links":[[0,1]]}""",
        """[{"scene":1,"scheme":{"layout":"hub","nodes":[{"label":"x"}]}}]""",
        """[{"video":"https://youtu.be/abc","need":1,"start":"0:12","end":"0:18","exact":true}]""",
        """[["https://youtu.be/abc",2,12.5,17,true],["x",1,"1:00","1:05",false]]""",
        """{"items":[{"id":"c1","text":"good","q":["c1"]}]}""",
        """Do X !high #tag @fri ~3""",
        """{"name":"Neon","bg":"#101010","ink":"#FFFFFF","accent":"#FF00AA"}""",
        """{"v":1,"rate":48000,"tracks":[{"clips":[{"src":"s1","from":0,"to":1000,"at":0}]}]}""",
        "1:05.5", "12,3", "0.5",
    )

    private val rnd = Random(20261109)

    private fun mutate(s: String): String {
        if (s.isEmpty()) return rnd.nextInt(32, 0x2FF).toChar().toString()
        val b = StringBuilder(s)
        repeat(1 + rnd.nextInt(4)) {
            if (b.isEmpty()) return@repeat
            val i = rnd.nextInt(b.length)
            when (rnd.nextInt(6)) {
                0 -> b.deleteCharAt(i)
                1 -> b.insert(i, "{}[]\":,-0eE.9\\n".random(rnd))
                2 -> b.setLength(i) // truncation: the most common real failure (answer cut by the token limit)
                3 -> b.setCharAt(i, rnd.nextInt(0, 0xFFFF).toChar())
                4 -> b.insert(i, b.substring(rnd.nextInt(b.length)))
                else -> b.replace(i, i + 1, listOf("null", "-1", "1e400", "NaN", "\"\"", "[]", "{}").random(rnd))
            }
        }
        return b.toString()
    }

    private val corpus: List<String> by lazy {
        val out = ArrayList<String>(nasty + seeds)
        repeat(1500) { out += mutate(seeds.random(rnd)) }
        repeat(300) { out += mutate(nasty.random(rnd)) }
        repeat(200) { out += String(CharArray(rnd.nextInt(0, 64)) { rnd.nextInt(0, 0x3000).toChar() }) }
        out
    }

    /** Runs [f] over the whole corpus; collects every input that throws, so one run shows all failures of a parser. */
    private fun <T> survives(name: String, check: (String, T) -> Unit = { _, _ -> }, f: (String) -> T) {
        val failures = ArrayList<String>()
        for (s in corpus) {
            try {
                check(s, f(s))
            } catch (e: Throwable) {
                if (e is StackOverflowError || e is OutOfMemoryError || e is Exception || e is AssertionError) {
                    failures += "${e.javaClass.simpleName}: ${e.message?.take(80)} ← ${show(s)}"
                } else {
                    throw e
                }
            }
        }
        if (failures.isNotEmpty()) fail("$name failed on ${failures.size} inputs:\n" + failures.distinct().take(10).joinToString("\n"))
    }

    private fun show(s: String) = if (s.length > 60) "\"${s.take(60).escape()}…\" (${s.length} chars)" else "\"${s.escape()}\""

    private fun String.escape() = buildString { for (c in this@escape) if (c < ' ' || c in '\uD800'..'\uDFFF') append("\\u%04x".format(c.code)) else append(c) }

    private val nonNegative: (String, Long?) -> Unit = { s, v -> if (v != null && v < 0) throw AssertionError("negative $v for ${show(s)}") }

    // ---------- times typed by the user ----------

    @Test
    fun timeFieldsNeverThrowAndAreNeverNegative() {
        survives("AudioEdit.parseTime", nonNegative) { AudioEdit.parseTime(it) }
        survives("StudioEdit.parseTime", nonNegative) { StudioEdit.parseTime(it) }
        survives("StudioEdit.parseSeconds", nonNegative) { StudioEdit.parseSeconds(it) }
        survives("StudioCuts.parseTime", nonNegative) { StudioCuts.parseTime(it) }
        survives("StudioMix.parsePiece", nonNegative) { StudioMix.parsePiece(it) }
        survives("StudioMix.parseMarks", { s, v -> v?.forEach { nonNegative(s, it) } }) { StudioMix.parseMarks(it) }
    }

    @Test
    fun timeFieldsReadTheUsualForms() {
        assertEquals(65_500L, AudioEdit.parseTime("1:05.5"))
        assertEquals(12_300L, AudioEdit.parseTime("12,3"))
        assertEquals(null, AudioEdit.parseTime("NaN"))
        assertEquals(null, AudioEdit.parseTime("Infinity"))
        assertEquals(null, AudioEdit.parseTime("1e309"))
        assertEquals(65_500L, StudioCuts.parseTime("1:05.5"))
        assertEquals(90_000L, StudioCuts.parseTime("1m30s"))
        assertEquals(null, StudioCuts.parseTime("9223372036854775807:00"))
    }

    // ---------- AI answers ----------

    @Test
    fun aiAnswerParsersNeverThrow() {
        survives("StudioScheme.parseAnswer") { StudioScheme.parseAnswer(it) }
        survives("StudioScheme.fromJson") { StudioScheme.fromJson(it) }
        survives("JsonFix.tidy") { JsonFix.tidy(it) }
        survives("JsonFix.parses") { JsonFix.parses(it) }
        survives("VocCore.parse") { VocCore.parse(it, setOf("c1", "c2")) }
        survives("StudioMixAssist.parse") { StudioMixAssist.parse(it, "https://youtu.be/abc", "t") }
        survives("StudioMixAssist.parseBatch") { StudioMixAssist.parseBatch(it, listOf("https://youtu.be/abc", "x"), mapOf()) }
        survives("Board.quick") { Board.quick(it, 20_000) }
        survives("Board.parseAi") { Board.parseAi(it, 20_000) }
        survives("JsonText.firstObject") { JsonText.firstObject(it) }
        survives("AzureSpeech.parseVoices") { AzureSpeech.parseVoices(it) }
    }

    @Test
    fun jsonFixOutputIsStable() {
        // tidy is applied before every parse, possibly twice (repair on place, then again on the repaired answer)
        survives<Unit>("JsonFix.tidy idempotent") { s ->
            val once = JsonFix.tidy(s)
            assertEquals("tidy(tidy(x)) != tidy(x) for ${show(s)}", once, JsonFix.tidy(once))
        }
    }

    // ---------- files on disk (may be truncated by a crash or come from another version) ----------

    @Test
    fun storedFilesNeverThrow() {
        survives("StudioMixAssist.fromJson") { StudioMixAssist.fromJson(it) }
        survives("StudioEdit.fromJson") { StudioEdit.fromJson(it) }
        survives("StudioMix.fromJson") { StudioMix.fromJson(it) }
        survives("Bite.fromJson") { Bite.fromJson(it) }
        survives("StudioOverlay.parse") { StudioOverlay.parse(it) }
    }
}

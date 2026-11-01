/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTextTest {

    @Test
    fun requestHasRoleTaskWishAndIsOwn() {
        val r = RelayText.request("studio_direct", "You are an editor.", "Расшифровка: [0]Привет", true, "https://youtu.be/x", false, "  больше зумов ")
        assertTrue(r.startsWith("⟦Lumen → AI · studio_direct · lumen.relay v1⟧"))
        assertTrue(r.contains("ТОЛЬКО JSON"))
        assertTrue(r.contains("You are an editor."))
        assertTrue(r.contains("[0]Привет"))
        assertTrue(r.contains("https://youtu.be/x"))
        assertTrue(r.trimEnd().endsWith("больше зумов"))
        assertTrue(RelayText.isOwnRequest(r))
        assertFalse(RelayText.looksLikeAnswer(r, null))
    }

    @Test
    fun emptyWishSaysFollowTheTask() {
        val r = RelayText.request("t", "", "u", false, null, true, "")
        assertTrue(r.contains("действуй по заданию"))
        assertTrue(r.contains("Картинка приложена"))
        assertFalse(r.contains("=== РОЛЬ ==="))
    }

    @Test
    fun answerDetection() {
        assertTrue(RelayText.looksLikeAnswer("{\"a\":1}", null))
        assertFalse(RelayText.looksLikeAnswer("{\"a\":1}", " {\"a\":1} "))
        assertFalse(RelayText.looksLikeAnswer("", null))
        assertFalse(RelayText.looksLikeAnswer(null, null))
    }

    @Test
    fun tidyRemovesCommentsAndTrailingCommasButKeepsStrings() {
        val s = "Вот план: https://example.com\n```json\n{\n \"a\": [1, 2,], // числа\n \"u\": \"http://x.y/z, ]\", /* блок */\n \"b\": {\"c\": 1,},\n}\n```"
        val o = JSONObject(JsonText.firstObject(RelayText.clean(s, true))!!)
        assertEquals(2, o.getJSONArray("a").length())
        assertEquals("http://x.y/z, ]", o.getString("u"))
        assertEquals(1, o.getJSONObject("b").getInt("c"))
        // prose around JSON keeps its links
        assertTrue(RelayText.clean(s, true).contains("https://example.com"))
    }

    @Test
    fun smartQuotesOnlyWithoutPlainOnes() {
        assertEquals("{\"a\": \"b\"}", RelayText.tidyJson("{“a”: “b”}"))
        assertEquals("{\"a\": \"«b»\"}", RelayText.tidyJson("{\"a\": \"«b»\"}"))
    }

    @Test
    fun cleanDropsInvisibleAndCrLf() {
        assertEquals("a b\nc", RelayText.clean("﻿a b\r\nc​", false))
    }

    @Test
    fun missingJson() {
        assertTrue(RelayText.missingJson("Конечно! Вот ответ без json"))
        assertFalse(RelayText.missingJson("ok {\"a\":1}"))
    }

    @Test
    fun routes() {
        assertFalse(RelayText.routes("off", "studio_direct"))
        assertTrue(RelayText.routes("studio", "studio_direct"))
        assertTrue(RelayText.routes("studio", "langgame_write"))
        assertFalse(RelayText.routes("studio", "video_summary"))
        assertTrue(RelayText.routes("all", "video_summary"))
        assertFalse(RelayText.routes("all", "ping"))
    }
}

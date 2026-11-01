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

class WebLayerTest {

    @Test
    fun extractPicksJavascriptBlock() {
        val a = "Вот слой:\n```javascript\nfunction frame(t, ctx, W, H, d) { ctx.fillRect(0,0,1,1) }\n```\nГотово!"
        assertEquals("function frame(t, ctx, W, H, d) { ctx.fillRect(0,0,1,1) }", WebLayer.extract(a))
    }

    @Test
    fun extractTakesInlineScriptsOfHtml() {
        val a = "```html\n<html><script src=\"https://x\"></script><script>\nfunction frame(t){ return false }\n</script></html>\n```"
        assertEquals("function frame(t){ return false }", WebLayer.extract(a))
    }

    @Test
    fun extractKeepsPlainCode() {
        assertEquals("function frame(t){}", WebLayer.extract("  function frame(t){}  "))
    }

    @Test
    fun problems() {
        assertTrue(WebLayer.problems(WebLayer.EXAMPLE).isEmpty())
        assertTrue(WebLayer.problems("").isNotEmpty())
        assertTrue(WebLayer.problems("var a = 1;").any { it.contains("frame") })
        assertTrue(WebLayer.problems("function frame(t){ fetch('https://x') }").any { it.contains("сет") })
        assertTrue(WebLayer.problems("function frame(t){ setTimeout(f, 1) }").any { it.contains("таймер") })
        assertTrue(WebLayer.problems("function frame(t){ if (t) {").any { it.contains("скобки") })
        assertTrue(WebLayer.problems("const frame = (t, ctx) => { ctx.fill() }").isEmpty())
    }

    @Test
    fun pageEscapesScriptCloseAndKeepsCodeOnItsOwnLines() {
        val p = WebLayer.page("function frame(t){ var s='</script>'; }", 720, 1280)
        assertFalse(p.contains("'</script>'"))
        assertTrue(p.contains("<\\/script>"))
        assertTrue(p.contains("width=\"720\" height=\"1280\""))
        assertTrue(p.contains("Content-Security-Policy"))
        // everything before the code is line 1, so error line N in the page is line N-1 of the code
        val before = p.substringBefore("function frame")
        assertEquals(1, before.count { it == '\n' })
    }

    @Test
    fun layerSize() {
        assertEquals(720 to 1280, WebLayer.layerSize(1080, 1920, false))
        assertEquals(1080 to 1920, WebLayer.layerSize(1080, 1920, true))
        assertEquals(480 to 854, WebLayer.layerSize(480, 854, false))
        assertEquals(1280 to 720, WebLayer.layerSize(1920, 1080, false))
    }

    @Test
    fun dataAndBrief() {
        val d = WebLayer.dataJson(listOf(Triple("Привет", 120L, 480L), Triple("мир", 520L, 900L)), listOf(Triple("Хук", 0L, 900L)), "Тема", "Russian")
        val o = WebLayer.withVideo(d, 720, 1280, 9_050, "#FFD60A")
        assertEquals(0.12, o.getJSONArray("words").getJSONObject(0).getDouble("s"), 1e-9)
        assertEquals(9.05, o.getDouble("duration"), 1e-9)
        assertEquals(720, o.getInt("w"))
        val b = WebLayer.brief(o, "больше стрелок", true)
        assertTrue(b.startsWith("lumen.canvas v1"))
        assertTrue(b.contains("0.12 Привет"))
        assertTrue(b.contains("0.00–0.90 Хук"))
        assertTrue(b.contains("больше стрелок"))
        assertTrue(b.contains("уже есть субтитры"))
        // a picked video without the Studio: empty speech, still valid
        val e = WebLayer.withVideo(null, 480, 854, 1000, "#FFF")
        assertEquals(0, e.getJSONArray("words").length())
        assertFalse(WebLayer.brief(e, "", false).contains("Речь"))
    }

    @Test
    fun wordsTextIsCut() {
        val o = JSONObject().put("words", org.json.JSONArray().apply {
            repeat(2000) { put(JSONObject().put("w", "слово").put("s", it * 0.3)) }
        })
        val s = WebLayer.wordsText(o, 500)
        assertTrue(s.length <= 510)
        assertTrue(s.endsWith("…\n"))
    }

    @Test
    fun fixBriefHasErrorAndCode() {
        val f = WebLayer.fixBrief("function frame(t){ x() }", "x is not defined (t=1.00 с)", "")
        assertTrue(f.contains("Ошибка: x is not defined"))
        assertTrue(f.contains("function frame(t){ x() }"))
    }

    @Test
    fun frameResults() {
        assertTrue(WebLayer.frameOf("\"\"") is WebLayer.Frame.Empty)
        val png = WebLayer.frameOf("\"data:image/png;base64,iVBORw0KGgo=\"")
        assertTrue(png is WebLayer.Frame.Png)
        assertEquals("iVBORw0KGgo=", (png as WebLayer.Frame.Png).base64)
        val err = WebLayer.frameOf("\"ERR:x is not defined (t=2.00 \\u0441)\"")
        assertEquals("x is not defined (t=2.00 с)", (err as WebLayer.Frame.Error).message)
        assertTrue(WebLayer.frameOf(null) is WebLayer.Frame.Error)
        assertTrue(WebLayer.frameOf("null") is WebLayer.Frame.Error)
    }

    @Test
    fun previewTimes() {
        assertEquals(listOf(0.8, 3.3, 6.0, 8.6), WebLayer.previewTimes(10_000))
    }
}

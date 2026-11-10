/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in caption styles ([TextStyles]), scheme templates ([SchemeTemplates]) and the word motions. */
class TemplatesCatalogTest {
    @Test
    fun hundredCaptionStylesAllClean() {
        assertEquals(100, TextStyles.ALL.size)
        assertEquals(100, TextStyles.ALL.map { it.id }.toSet().size)
        for (a in TextStyles.AREAS) assertEquals(a.id, 10, TextStyles.inArea(a.id).size)
        for (s in TextStyles.ALL) assertTrue("${s.id}: ${TextStyles.problems(s)}", TextStyles.problems(s).isEmpty())
        // every motion is used by some style
        for (m in EditCaptions.MOTIONS.filter { it.isNotEmpty() }) assertTrue(m, TextStyles.ALL.any { it.motion == m })
        assertEquals("ts:viral_yellow", TextStyles.of("viral_yellow")!!.look)
        assertNotNull(TextStyles.ofLook("ts:game_neon"))
        assertFalse(TextStyles.isStyle("plate"))
        assertTrue(TextStyles.search("неон").any { it.id == "game_neon" })
        assertTrue(TextStyles.search("karaoke").size > 10)
    }

    @Test
    fun hundredSchemeTemplatesFollowTheProtocol() {
        assertEquals(100, SchemeTemplates.ALL.size)
        assertEquals(100, SchemeTemplates.ALL.map { it.id }.toSet().size)
        for (a in SchemeTemplates.AREAS) assertEquals(a.id, 10, SchemeTemplates.inArea(a.id).size)
        val looks = SchemeStyles.BUILT_IN.map { it.id }.toSet()
        for (t in SchemeTemplates.ALL) for (ru in listOf(true, false)) {
            val s = SchemeTemplates.scheme(t, ru)
            assertTrue("${t.id}/$ru: ${StudioScheme.problems(s)}", StudioScheme.problems(s).isEmpty())
            assertTrue(t.id + " look", t.look in looks)
            assertTrue(t.id + " use", SchemeTemplates.use(t, ru).isNotBlank())
            s.nodes.forEach { n ->
                assertTrue("${t.id}: ${n.label}", n.label.length <= 28 && n.value.length <= 16)
                assertTrue("${t.id}: no icon in ${n.label}", n.icon.isNotEmpty())
            }
            assertTrue(t.id, s.axes.all { it.length <= 20 } && s.center.length <= 20)
            // the same scheme can be edited as lines by hand
            assertNotNull(t.id, StudioEdit.schemeFromLines(SchemeTemplates.lines(t, ru), t.layout, s))
        }
        // trees keep their levels, loops close
        val okr = SchemeTemplates.scheme(SchemeTemplates.of("okr")!!)
        assertEquals("n2", okr.nodes[2].parent)
        assertEquals(2, StudioScheme.depths(okr)["n3"])
        val pdca = SchemeTemplates.scheme(SchemeTemplates.of("pdca")!!)
        assertEquals(SchLink("n4", "n1"), pdca.links.last())
        assertTrue(SchemeTemplates.scheme(SchemeTemplates.of("aida")!!).nodes.last().accent)
        assertEquals("Деньги", SchemeTemplates.scheme(SchemeTemplates.of("life_wheel")!!).nodes[2].label)
        assertTrue(SchemeTemplates.wish(SchemeTemplates.of("swot")!!).contains("Сильные стороны"))
        assertTrue(SchemeTemplates.search("swot").isNotEmpty())
    }

    @Test
    fun extraLooksFollowTheRules() {
        assertEquals(SchemeStyles.BUILT_IN.size, SchemeStyles.BUILT_IN.map { it.id }.toSet().size)
        for (p in SchemeStyles.EXTRA) {
            val (fixed, notes) = SchemeStyles.enforce(p)
            assertTrue("${p.id}: $notes", notes.isEmpty())
            assertTrue(p.id, SchemeStyles.contrast(fixed.ink, SchemeStyles.surface(fixed)) >= 4.5)
        }
    }

    @Test
    fun sharedChoiceKeepsTheStyles() {
        // the subtitle style and a new look survive the settings of every Studio section and the Mix
        val ov = StudioOverlay(subStyle = "game_neon", look = "bento")
        val back = StudioOverlay.fromJson(ov.toJson())
        assertEquals("game_neon", back.subStyle)
        // an old look comes back as the info-block style made of it (lumen.info)
        assertEquals(InfoStyles.spec("bento"), back.look)
        assertEquals("", StudioOverlay.fromJson(ov.toJson().put("sub", "nope")).subStyle)
        assertTrue(SchemeTemplates.studioWish(SchemeTemplates.of("aida")!!).contains("funnel"))
    }

    @Test
    fun wordMotions() {
        // nothing said yet: the words that appear as said are hidden, karaoke shows all in the ink
        assertEquals(0f, EditCaptions.fx("pop", 0, -1, 0f).alpha, 1e-4f)
        assertTrue(EditCaptions.fx("karaoke", 0, -1, 0f).pending)
        // the said word pops larger and settles at its size
        val mid = EditCaptions.fx("stamp", 1, 1, 0.2f)
        assertTrue(mid.scale > 1.3f && mid.hot)
        assertEquals(1f, EditCaptions.fx("stamp", 1, 1, 1f).scale, 1e-4f)
        assertEquals(1f, EditCaptions.fx("pop", 1, 1, 1f).scale, 1e-3f)
        // the marker runs behind the said words, the underline only under the current one
        assertEquals(1f, EditCaptions.fx("marker", 0, 2, 1f).marker, 1e-4f)
        assertEquals(0f, EditCaptions.fx("underline", 0, 2, 1f).underline, 1e-4f)
        assertEquals(0.5f, EditCaptions.fx("type", 2, 2, 0.5f).chars, 1e-4f)
        assertTrue(EditCaptions.fx("slide", 2, 2, 0f).dx < 0f)
        assertEquals(0f, EditCaptions.fx("shake", 2, 2, 1f).rot, 1e-3f)
        assertTrue(EditCaptions.fx("focus", 0, 2, 1f).alpha < 0.5f)
        assertTrue(EditCaptions.fx("glow", 2, 2, 0.5f).glow)
        // old motions keep their look
        assertTrue(EditCaptions.fx("box", 1, 1, 0.5f).box)
        assertEquals(EditCaptions.WordFx(), EditCaptions.fx("", 0, -1, 0f))
        assertTrue("stamp" in EditCaptions.SINGLE && "karaoke" !in EditCaptions.SINGLE)
    }
}

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

class InfoStylesTest {
    @Test
    fun hundredStylesTenPerArea() {
        assertEquals(100, InfoStyles.ALL.size)
        assertEquals(100, InfoStyles.ALL.map { it.id }.toSet().size)
        for (a in InfoStyles.AREAS) assertEquals(a.id, 10, InfoStyles.inArea(a.id).size)
        // the same palette never comes twice with the same motion: every style looks and moves differently
        assertEquals(100, InfoStyles.ALL.map { it.palette + "/" + it.look.motion + "/" + it.look.idle }.toSet().size)
        // every motion of the catalogue is used
        assertEquals(InfoStyles.MOTIONS.toSet(), InfoStyles.ALL.map { it.look.motion }.toSet())
    }

    @Test
    fun everyStyleFollowsTheProtocol() {
        for (st in InfoStyles.ALL) {
            val l = st.look
            assertTrue(st.id, l.motion in InfoStyles.MOTIONS)
            assertTrue(st.id, l.idle in InfoStyles.IDLES)
            assertTrue(st.id, l.link in InfoStyles.LINKS)
            assertTrue(st.id, l.fill in SchemeStyles.FILLS)
            assertTrue(st.id, l.dur in 150..1000)
            assertTrue(st.id, st.layout.isEmpty() || st.layout in StudioScheme.LAYOUTS)
            assertTrue(st.id, st.name.isNotBlank() && st.nameEn.isNotBlank())
            // text reads on its own plate
            if (l.fill == "solid") assertTrue(st.id + " ink", SchemeStyles.contrast(l.ink, l.card) >= 4.5)
            // the sample of the preview is a valid info-block
            val s = InfoStyles.sample(st, true)
            assertTrue(st.id + " sample " + StudioScheme.problems(s), StudioScheme.problems(s).isEmpty())
        }
    }

    @Test
    fun specKeepsTheOptions() {
        assertEquals("ib:smoke", InfoStyles.spec("smoke"))
        val o = InfoOpts(alpha = 60, bg = "glass", text = "l", speed = 1.5f, motion = "type", idle = "glow", link = "dots", corners = "pill",
            caps = "on", shadow = "off", accent = 0xFFFF3B30.toInt(), font = "lf:oswald", stagger = "slow")
        val spec = InfoStyles.spec("neonjump", o)
        assertTrue(InfoStyles.isSpec(spec))
        assertEquals(o, InfoStyles.opts(spec))
        assertEquals("neonjump", InfoStyles.of(spec)!!.id)
        // a new style keeps the options
        val other = InfoStyles.withStyle(spec, "chalk")
        assertEquals("chalk", InfoStyles.of(other)!!.id)
        assertEquals(o, InfoStyles.opts(other))
        // junk is ignored
        assertEquals(InfoOpts(), InfoStyles.opts("ib:smoke?a=999x&bg=wood&m=fly&f=<script>"))
        assertFalse(InfoStyles.isSpec("neon"))
        assertFalse(InfoStyles.isSpec("ib:nothing"))
        assertTrue(InfoOpts().isDefault)
    }

    @Test
    fun optionsChangeTheLook() {
        val spec = InfoStyles.spec("punch", InfoOpts(alpha = 40, bg = "clear", motion = "wipe", idle = "none", link = "none", corners = "square", speed = 2f))
        val l = InfoStyles.look(spec)!!
        assertEquals(0.4f, l.alpha, 0.001f)
        assertEquals("none", l.fill)
        assertEquals("wipe", l.move)
        assertEquals("none", l.idle)
        assertEquals("none", l.link)
        assertEquals(0.02f, l.radius, 0.001f)
        assertTrue(l.dur < 300)
        assertEquals(spec, l.id)
        // the old finder resolves a spec too (the Editor draws blocks by their look)
        assertNotNull(SchemeStyles.find(java.io.File("/nonexistent"), spec))
        assertNull(InfoStyles.look("clean"))
    }

    @Test
    fun oldLooksMoveToInfoStyles() {
        for (old in SchemeStyles.BUILT_IN.map { it.id }.filter { it != SchemeStyles.AUTO }) {
            assertTrue(old, InfoStyles.isSpec(InfoStyles.fromOld(old)))
        }
        assertEquals(SchemeStyles.AUTO, StudioOverlay.lookOf(""))
        assertTrue(InfoStyles.isSpec(StudioOverlay.lookOf("neon")))
        val ov = StudioOverlay().withInfo(InfoStyles.spec("smoke", InfoOpts(bg = "clear")))
        assertEquals("clear", ov.bg)
        assertEquals(ov, StudioOverlay.fromJson(ov.toJson()))
    }

    @Test
    fun newLayoutsLayOut() {
        val one = StudioEdit.schemeFromLines("💡 Главное — 3×")!!
        assertEquals("card", one.layout)
        val stat = StudioEdit.schemeFromLines("📈 рост — +42%\n⏱ минут — 15", "stat")!!
        assertEquals(listOf("stat", "stat"), StudioScheme.layout(stat, 0.6f).map { it.shape })
        val list = StudioEdit.schemeFromLines("a\nb\nc", "list")!!
        assertTrue(StudioScheme.layout(list, 0.6f).all { it.shape == "band" })
        val grid = StudioEdit.schemeFromLines("a\nb\nc\nd\ne", "grid")!!
        val boxes = StudioScheme.layout(grid, 0.6f)
        assertEquals(5, boxes.size)
        assertTrue(boxes.all { it.x - it.w / 2 >= -0.01f && it.x + it.w / 2 <= 1.01f })
        assertEquals("grid", StudioEdit.schemeFromLines("a\nb", "card")!!.layout)
    }
}

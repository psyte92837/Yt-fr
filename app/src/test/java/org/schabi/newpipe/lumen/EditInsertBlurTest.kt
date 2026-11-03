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

/** The last items of the insert wizard plan: the video blurred under a block and the sample picture of the look. */
class EditInsertBlurTest {

    @Test
    fun theBlurCoversTheBlockWithAMarginInsideTheFrame() {
        val b = EBlock("b1", "text", 0, 3000, x = 0.5f, y = 0.5f, w = 0.6f, ratio = 0.5f, text = "x")
        assertNull(StudioEdit.blurRect(b, 1080f, 1920f))
        val r = StudioEdit.blurRect(b.copy(blur = 1f), 1080f, 1920f)
        assertNotNull(r)
        // the block is 648×324 px around (540, 960); the margin is 4 % of the short side
        assertTrue(r!!.l < 540 - 324 && r.r > 540 + 324)
        assertTrue(r.t < 960 - 162 && r.b > 960 + 162)
        // a block at the edge: the area stays inside the frame
        val e = StudioEdit.blurRect(b.copy(blur = 1f, x = 0.05f), 1080f, 1920f)!!
        assertEquals(0f, e.l, 0.001f)
        // a turned block: the box around it is larger
        val turned = StudioEdit.blurRect(b.copy(blur = 1f, rot = 45f), 1080f, 1920f)!!
        assertTrue(turned.b - turned.t > r.b - r.t)
    }

    @Test
    fun theStrengthIsTheSameAtAnySize() {
        assertEquals(90, StudioEdit.blurWidth(0f))
        assertEquals(33, StudioEdit.blurWidth(1f))
        assertTrue(StudioEdit.blurWidth(0.4f) in 34..89)
        // out of range values are clamped
        assertEquals(StudioEdit.blurWidth(1f), StudioEdit.blurWidth(3f))
    }

    @Test
    fun theBlurSurvivesTheProject() {
        val b = EBlock("b1", "text", 0, 3000, text = "x", blur = 0.4f, dim = 0.2f)
        val back = StudioEdit.blockFrom(StudioEdit.blockJson(b))
        assertEquals(0.4f, back!!.blur, 0.001f)
        assertFalse(StudioEdit.blockJson(b.copy(blur = 0f)).has("blur"))
    }

    @Test
    fun theBriefKeepsTheBlurAndTheSamplePicture() {
        val b = EditBrief(type = "number", blur = 1f, ref = "img/ref_1.jpg", like = "чистая инфографика")
        val back = EditInsert.briefFrom(EditInsert.briefJson(b))!!
        assertEquals(1f, back.blur, 0.001f)
        assertEquals("img/ref_1.jpg", back.ref)
        // the style of the video keeps the blur; a recipe forgets the picture of this project
        assertEquals(1f, EditInsert.brandOf(b).blur, 0.001f)
        assertEquals("", EditInsert.recipeOf(b).ref)
        assertEquals(1f, EditInsert.withBrand(EditBrief(type = "list"), b).blur, 0.001f)
    }

    @Test
    fun theRequestMentionsTheBlurAndThePicture() {
        val lines = EditInsert.briefLines(EditBrief(type = "number", blur = 1f, ref = "img/ref_1.jpg"), EditInsert.KIND).joinToString("\n")
        assertTrue(lines.contains("размыто"))
        assertTrue(lines.contains("приложенной картинке"))
        // nothing set: neither is mentioned
        val plain = EditInsert.briefLines(EditBrief(type = "number"), EditInsert.KIND).joinToString("\n")
        assertFalse(plain.contains("размыто"))
        assertFalse(plain.contains("картинке"))
        // a scheme's look is drawn by Lumen: the picture is not sent for it
        assertFalse(EditInsert.briefLines(EditBrief(type = "steps", ref = "img/ref_1.jpg"), "scheme").joinToString("\n").contains("картинке"))
    }
}

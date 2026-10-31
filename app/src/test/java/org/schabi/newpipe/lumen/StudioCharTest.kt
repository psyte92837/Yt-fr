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
import kotlin.random.Random

class StudioCharTest {

    @Test
    fun everyLookIsAPresetOfKnownParts() {
        StudioToon.LOOKS.forEach { look ->
            val p = StudioParts.preset(look)
            assertEquals(look, p, StudioParts.normalize(p))
            assertEquals(StudioToon.defaultColor(look), p.main)
        }
        assertEquals("visor", StudioParts.preset("robot").eyes)
        assertEquals("clothes", StudioParts.preset("girl").front)
        assertEquals(StudioParts.preset("blob"), StudioParts.preset("dragon"))
        // the old colour of a character still paints it
        assertEquals(0xFF112233.toInt(), StudioParts.of(SChar("X", "owl", "", 0xFF112233.toInt())).main)
    }

    @Test
    fun normalizeKeepsValuesInRange() {
        val p = StudioParts.normalize(ToonParts(top = "wings", eyes = "laser", acc = listOf("hat", "hat", "sword", "bow", "crown", "tie", "scarf"),
            width = 9f, height = 0f, eyeSize = Float.NaN, main = 0x123456))
        assertEquals("none", p.top)
        assertEquals("round", p.eyes)
        assertEquals(listOf("hat", "bow", "crown", "tie"), p.acc)
        assertEquals(StudioParts.WIDTH.endInclusive, p.width, 0f)
        assertEquals(StudioParts.HEIGHT.start, p.height, 0f)
        assertEquals(1f, p.eyeSize, 0f)
        assertEquals(0xFF123456.toInt(), p.main)
    }

    @Test
    fun randomCharactersAreValid() {
        val rnd = Random(7)
        val all = (1..200).map { StudioParts.random(rnd) }
        all.forEach { assertEquals(it, StudioParts.normalize(it)) }
        assertTrue(all.distinct().size > 150)
        assertTrue(all.any { it.front == "clothes" } && all.any { it.front != "clothes" })
    }

    @Test
    fun colorsInManyForms() {
        assertEquals(0xFFFF8800.toInt(), StudioParts.color("#FF8800"))
        assertEquals(0xFFFF8800.toInt(), StudioParts.color("ff8800"))
        assertEquals(0xFFFF8800.toInt(), StudioParts.color("#F80"))
        assertEquals(0xFFFF8800.toInt(), StudioParts.color("#80FF8800"))
        assertEquals(0xFF6CC24A.toInt(), StudioParts.color("Green"))
        assertNull(StudioParts.color("#GG0000"))
        assertNull(StudioParts.color(""))
        assertEquals("#FF8800", StudioParts.hex(0xFFFF8800.toInt()))
    }

    @Test
    fun protocolRoundTrip() {
        val ch = SChar("Фокси", "fox", "f", 0, StudioParts.preset("fox").copy(acc = listOf("scarf", "glasses"), width = 1.1f, limbs = 0xFF26262B.toInt()),
            "хитрая", 12, -8)
        val back = StudioCharProto.parse(StudioCharProto.encode(ch), SChar("X", "blob"))
        assertNotNull(back)
        assertEquals(ch, back!!.char)
        assertTrue(back.notes.isEmpty())
        assertFalse(back.hasFace)
    }

    @Test
    fun parsesSloppyAiAnswers() {
        val base = SChar("Герой", "blob", "m")
        val answer = """Вот ваш персонаж:
```json
{"format":"lumen.char","v":1,"name":"Кот Учёный","base":"cat","persona":"ворчит, но объясняет",
 "voice":"male","pitch":99,"parts":{"top":"ears_pointy","acc":["glasses","monocle"],"eyes":"sparkly",
 "colors":{"main":"#E0533D","limbs":"","feet":"blue"},"width":"1.2"}}
```
Надеюсь, понравится!"""
        val p = StudioCharProto.parse(answer, base)!!
        assertEquals("Кот_Учёный", p.char.name)
        assertEquals("cat", p.char.look)
        assertEquals("m", p.char.voice)
        assertEquals(StudioCharProto.PITCH.last, p.char.pitch)
        assertEquals("ворчит, но объясняет", p.char.persona)
        val parts = p.char.parts!!
        assertEquals(listOf("glasses"), parts.acc)
        assertEquals("round", parts.eyes) // unknown value keeps the cat's
        assertTrue(parts.whiskers) // not mentioned: stays as the cat preset
        assertEquals(0xFFE0533D.toInt(), parts.main)
        assertEquals(0, parts.limbs)
        assertEquals(0xFF4F8FF7.toInt(), parts.feet)
        assertEquals(1.2f, parts.width, 0.001f)
        assertTrue(p.notes.any { it.contains("monocle") } && p.notes.any { it.contains("sparkly") })
        assertNull(StudioCharProto.parse("нет тут никакого JSON", base))
    }

    @Test
    fun partsAloneAreEnough() {
        val p = StudioCharProto.parse("""{"top":"horns","tail":"thin","colors":{"main":"purple"}}""", SChar("Бес", "blob"))!!
        assertEquals("Бес", p.char.name)
        assertEquals("horns", p.char.parts!!.top)
        assertEquals("thin", p.char.parts!!.tail)
    }

    @Test
    fun facePointsOnlyForOwnPicture() {
        val answer = """{"name":"Я","face":{"eyeL":[60,30],"eyeR":{"x":0.4,"y":0.31},"mouth":["50%",0.5],"mouthW":0.2}}"""
        val drawn = StudioCharProto.parse(answer, SChar("Я", "kid"))!!
        assertFalse(drawn.hasFace)
        assertNull(drawn.char.image)
        val pic = StudioCharProto.parse(answer, SChar("Я", "kid", image = ToonImage("/x.png")))!!
        assertTrue(pic.hasFace)
        val im = pic.char.image!!
        // left and right are put in order of the picture
        assertEquals(0.4f, im.eyeLx, 0.001f)
        assertEquals(0.6f, im.eyeRx, 0.001f)
        assertEquals(0.5f, im.mouthX, 0.001f)
        assertEquals(0.2f, im.mouthW, 0.001f)
        assertTrue(im.hasEyes && im.hasMouth)
        assertEquals("/x.png", im.path)
    }

    @Test
    fun castKeepsOwnCharacters() {
        val own = SChar("Мой", "dog", "", 0, StudioParts.random(Random(3)), "весёлый", -10, 15,
            ToonImage("/data/c.png", 0.4f, 0.3f, 0.6f, 0.3f, 0.5f, 0.55f, 0.15f, 0xFFAABBCC.toInt(), true))
        val cast = listOf(SChar("Лиса", "fox", "f"), own)
        assertEquals(cast, StudioToon.parseCast(StudioToon.castJson(cast)))
        assertEquals(-10, StudioToon.pitchOf(own))
        assertEquals(StudioToon.pitch("fox"), StudioToon.pitchOf(cast[0]))
        assertEquals(25, StudioToon.rateOf(own, 10))
        assertTrue(StudioToon.promptCast(cast).contains("весёлый"))
        assertTrue(StudioToon.kindLabel(own).startsWith("🖼"))
        assertTrue(StudioToon.kindLabel(own.copy(image = null)).startsWith("🎨"))
        assertEquals(StudioToon.emoji("fox") + " " + StudioToon.lookLabel("fox"), StudioToon.kindLabel(cast[0]))
    }

    @Test
    fun libraryReplacesByName() {
        var lib = emptyList<SChar>()
        lib = StudioToon.libraryWith(lib, SChar("A", "fox"))
        lib = StudioToon.libraryWith(lib, SChar("B", "cat"))
        lib = StudioToon.libraryWith(lib, SChar("a", "owl"))
        assertEquals(listOf("a", "B"), lib.map { it.name })
        repeat(60) { lib = StudioToon.libraryWith(lib, SChar("N$it", "blob")) }
        assertEquals(StudioToon.MAX_LIB, lib.size)
        assertEquals(StudioToon.MAX_LIB, StudioToon.parseCast(StudioToon.castJson(lib), StudioToon.MAX_LIB).size)
    }

    @Test
    fun externalPromptCarriesTheSchema() {
        val p = StudioCharProto.externalPrompt("кот-пират", image = true, face = true)
        assertTrue(p.contains("lumen.char"))
        assertTrue(p.contains("кот-пират"))
        assertTrue(p.contains("face"))
        StudioParts.TOPS.forEach { assertTrue(it, p.contains(it)) }
        StudioParts.ACCS.forEach { assertTrue(it, p.contains(it)) }
        // the example in the schema follows the protocol itself
        val example = StudioCharProto.schema().substringAfter("Пример: ")
        assertEquals("Фокси", StudioCharProto.parse(example, SChar("X", "blob"))!!.char.name)
    }

    @Test
    fun removesPlainBackground() {
        val w = 10
        val h = 8
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFE0533D.toInt()
        val px = IntArray(w * h) { white }
        for (y in 2..5) for (x in 3..6) px[y * w + x] = red
        // a white spot inside the red figure is not reached from the border
        px[3 * w + 4] = white
        val n = StudioCharImage.removeBg(px, w, h)
        assertEquals(w * h - 16, n)
        assertEquals(0, px[0])
        assertEquals(red, px[2 * w + 3])
        assertEquals(white, px[3 * w + 4])
        // already transparent: nothing to do
        assertEquals(0, StudioCharImage.removeBg(px, w, h))
        // a photo with different corners is left alone
        val photo = IntArray(w * h) { it * 0x030507 or (0xFF shl 24) }
        assertEquals(0, StudioCharImage.removeBg(photo, w, h))
    }

    @Test
    fun skinColourForEyelids() {
        val w = 4
        val h = 4
        val skin = 0xFFF0C090.toInt()
        val px = IntArray(w * h) { skin }
        px[0] = 0
        assertEquals(skin, StudioCharImage.skinAt(px, w, h, 0f, 0f, 1))
        val im = ToonImage("/x", 0.4f, 0.5f, 0.6f, 0.5f)
        val (x, y) = StudioCharImage.lidPoint(im)
        assertEquals(0.5f, x, 0.001f)
        assertTrue(y < 0.5f)
    }
}

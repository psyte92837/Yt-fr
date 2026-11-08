/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class I18nTest {
    @Test
    fun systemLocaleMatchesOurLanguages() {
        assertEquals("pt", I18n.match("pt-BR"))
        assertEquals("ja", I18n.match("ja-JP"))
        assertEquals("ar", I18n.match("ar-EG"))
        assertEquals("id", I18n.match("in-ID"))
        assertEquals("zh", I18n.match("zh-Hans-CN"))
        assertEquals("zh", I18n.match("zh-CN"))
        assertNull(I18n.match("zh-TW"))
        assertNull(I18n.match("zh-Hant-HK"))
        assertNull(I18n.match("sv-SE"))
        assertNull(I18n.match(""))
        assertTrue(I18n.lang("ar")!!.rtl)
        assertFalse(I18n.lang("ja")!!.rtl)
    }

    @Test
    fun placeholdersAreFilledAndChecked() {
        assertEquals("Scenes: 3 · words: 40", I18n.format("Scenes: {0} · words: {1}", arrayOf(3, 40)))
        assertEquals("40 語 · 3 シーン", I18n.format("{1} 語 · {0} シーン", arrayOf(3, 40)))
        assertEquals("{x} {5} a", I18n.format("{x} {5} {0}", arrayOf("a")))
        assertEquals("no args {0}", I18n.format("no args {0}", arrayOf()))
        assertTrue(I18n.samePlaces("Delete {0}?", "¿Eliminar {0}?"))
        assertFalse(I18n.samePlaces("Delete {0}?", "¿Eliminar?"))
        assertFalse(I18n.samePlaces("Delete", "Eliminar {1}"))
    }

    @Test
    fun lookupFindsTheCoreOfAText() {
        val m = mapOf("Save video" to "Guardar vídeo", "Save" to "Guardar", "Delete {0}?" to "¿Eliminar {0}?")
        assertEquals("Guardar", I18n.lookup(m, "Save"))
        assertEquals("🎬 Guardar vídeo…", I18n.lookup(m, "🎬 Save video…"))
        assertEquals("💾 Guardar: ", I18n.lookup(m, "💾 Save: "))
        assertEquals("¿Eliminar {0}?", I18n.lookup(m, "Delete {0}?"))
        assertNull(I18n.lookup(m, "{0} Save"))
        assertNull(I18n.lookup(m, "🎬 Open"))
        assertNull(I18n.lookup(m, "…"))
    }

    @Test
    fun every20thLanguageIsListed() {
        assertEquals(20, I18n.LANGS.size)
        assertEquals(20, I18n.LANGS.map { it.code }.toSet().size)
        assertTrue(I18n.lang("ur")!!.rtl && I18n.lang("ar")!!.rtl)
    }

    @Test
    fun withoutAppContextRussianIsKept() {
        assertEquals("Привет", t("Привет", "Hello"))
        assertEquals("Сцен: 3", tf("Сцен: 3", "Scenes: {0}", 3))
    }

    /** Every shipped catalogue is valid JSON and each translation keeps the {N} places of its key. */
    @Test
    fun catalogsKeepPlaceholders() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "studio/shared-files.txt").exists() }
        val dir = File(root, "studio/src/main/assets/lumen_i18n")
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") && !it.name.startsWith("_") }
        for (f in files) {
            val code = f.name.removeSuffix(".json")
            assertTrue("$code is not in I18n.LANGS", I18n.lang(code) != null)
            val o = JSONObject(f.readText())
            for (k in o.keys()) assertTrue("$code: $k", I18n.samePlaces(k, o.getString(k)))
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data shipped inside the APK: translation catalogues, fonts with their licences, the free-AI catalogue.
 * These files are written by scripts and other sessions (tools/i18n, tools/freeai, CI) — a broken one is read only
 * on a phone, in a language or a provider the author never opened. Pure JVM: files are read from the repository.
 */
class AssetsContractTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "studio/shared-files.txt").exists() }
    private val i18n = File(root, "studio/src/main/assets/lumen_i18n")
    private val fonts = File(root, "app/src/main/assets/lumen_fonts")
    private val placeholder = Regex("""\{\d+}""")

    /** Language codes of the picker, read from I18n.LANGS in the source (keeps this test free of Android). */
    private val langs: List<String> by lazy {
        val src = File(root, "app/src/main/java/org/schabi/newpipe/lumen/I18n.kt").readText()
        val block = src.substringAfter("val LANGS = listOf(").substringBefore("\n    )")
        Regex("""Lang\("([a-z]{2,3})"""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun catalogues() = i18n.listFiles { f -> f.extension == "json" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }

    // ---------- translations ----------

    @Test
    fun everyLanguageOfThePickerHasACatalogue() {
        assertEquals("I18n.LANGS changed: update this test and tools/i18n", 20, langs.size)
        val have = catalogues().map { it.nameWithoutExtension }.toSet()
        val missing = langs.filter { it !in setOf("en", "ru") && it !in have } // en = the keys, ru = t()'s first argument
        assertTrue("no catalogue for $missing", missing.isEmpty())
        val orphan = have - langs.toSet()
        assertTrue("catalogues for languages the picker does not offer: $orphan", orphan.isEmpty())
    }

    @Test
    fun cataloguesAreValidAndKeepPlaceholders() {
        for (f in catalogues()) {
            val o = runCatching { JSONObject(f.readText()) }.getOrElse { throw AssertionError("${f.name} is not a JSON object: ${it.message}") }
            assertTrue("${f.name} is almost empty", o.length() >= 100)
            for (k in o.keys()) {
                val v = o.opt(k) as? String ?: throw AssertionError("${f.name}: \"$k\" is not a string")
                assertTrue("${f.name}: empty translation of \"$k\"", v.isNotBlank())
                // tf(ru, "… {0}", x): a lost or invented {N} shows a raw "{0}" or drops a number on screen
                assertEquals("${f.name}: placeholders of \"$k\" → \"$v\"", placeholder.findAll(k).map { it.value }.sorted().toList(),
                    placeholder.findAll(v).map { it.value }.sorted().toList())
                assertTrue("${f.name}: \"$k\" translated into a Kotlin template \"$v\"", !v.contains("\${"))
            }
        }
    }

    @Test
    fun machineTranslationKeyListIsClean() {
        val a = JSONArray(File(i18n, "_keys.json").readText())
        val keys = (0 until a.length()).map { a.getString(it) }
        assertTrue("_keys.json has only ${keys.size} keys", keys.size > 500)
        assertEquals("duplicate keys in _keys.json", keys.size, keys.toSet().size)
        assertTrue("blank key in _keys.json", keys.none { it.isBlank() })
        // Extract.kt drops texts longer than 300 characters (ML Kit and the screen would cut them anyway)
        assertTrue("key over 300 characters in _keys.json", keys.none { it.length > 300 })
    }

    @Test
    fun rightToLeftLanguagesAreMarked() {
        val src = File(root, "app/src/main/java/org/schabi/newpipe/lumen/I18n.kt").readText()
        for (code in listOf("ar", "ur")) assertTrue("$code must be rtl = true", Regex("""Lang\("$code"[^)]*rtl = true""").containsMatchIn(src))
    }

    // ---------- fonts (SIL OFL: the licence must travel with the font) ----------

    @Test
    fun everyFontIsInTheCatalogueAndHasItsLicence() {
        val src = File(root, "app/src/main/java/org/schabi/newpipe/lumen/LumenFonts.kt").readText()
        val ids = Regex("""LFont\("(\w+)"""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue(ids.size >= 10)
        val files = fonts.listFiles { f -> f.extension == "ttf" }.orEmpty().map { it.nameWithoutExtension }.toSet()
        assertEquals("fonts in the catalogue vs assets/lumen_fonts", ids.toSet(), files)
        for (id in ids) {
            val lic = File(fonts, "licenses/$id-OFL.txt")
            assertTrue("no licence for $id", lic.isFile && lic.readText().contains("SIL Open Font License", ignoreCase = true))
            val ttf = File(fonts, "$id.ttf").readBytes()
            val tag = String(ttf, 0, 4, Charsets.ISO_8859_1)
            assertTrue("$id.ttf is not a TrueType/OpenType font", tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true")
        }
    }

    // ---------- free AI catalogue (synced by CI from FreeLLMAPI) ----------

    @Test
    fun freeAiCatalogueIsConsistent() {
        val o = JSONObject(File(root, "app/src/main/assets/lumen_freeai.json").readText())
        val providers = o.getJSONArray("providers")
        val ids = HashSet<String>()
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val id = p.getString("id")
            assertTrue("provider id \"$id\" twice", ids.add(id))
            assertTrue("$id: API base must be https (keys travel in headers): ${p.optString("base")}", p.getString("base").startsWith("https://"))
            assertTrue("$id: unknown api kind ${p.optString("api")}", p.getString("api").isNotBlank())
        }
        val models = o.getJSONArray("models")
        assertTrue(models.length() > 10)
        val seen = HashSet<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val p = m.getString("p")
            assertTrue("model ${m.optString("id")} of unknown provider $p", p in ids)
            assertTrue("model $p/${m.getString("id")} twice", seen.add(p + "/" + m.getString("id")))
            for (k in listOf("intel", "speed", "rpm", "rpd", "tpm", "tpd")) assertTrue("$p/${m.getString("id")}: $k < 0", m.optInt(k, 0) >= 0)
        }
    }
}

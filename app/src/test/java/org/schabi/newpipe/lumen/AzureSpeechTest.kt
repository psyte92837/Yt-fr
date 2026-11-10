/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSpeechTest {
    private val json = """[
      {"ShortName":"ru-RU-SvetlanaNeural","Locale":"ru-RU","Gender":"Female","LocalName":"Светлана","VoiceType":"Neural"},
      {"ShortName":"ru-RU-DmitryNeural","Locale":"ru-RU","Gender":"Male","LocalName":"Дмитрий","VoiceType":"Neural","StyleList":["cheerful"]},
      {"ShortName":"en-US-AvaMultilingualNeural","Locale":"en-US","Gender":"Female","LocalName":"Ava","VoiceType":"Neural",
       "StyleList":["cheerful","sad","whispering","newscast","documentary-narration"],"SecondaryLocaleList":["ru-RU"]},
      {"ShortName":"en-US-JennyNeural","Locale":"en-US","Gender":"Female","LocalName":"Jenny","VoiceType":"Neural","StyleList":["chat"]},
      {"ShortName":"xx-Old","Locale":"en-US","Gender":"Male","VoiceType":"Standard"}
    ]"""

    @Test
    fun voicesAndLanguage() {
        val all = AzureSpeech.parseVoices(json)
        assertEquals(4, all.size)
        assertTrue(AzureSpeech.parseVoices("not json").isEmpty())
        val ru = AzureSpeech.forLang(all, "ru")
        // native first, with styles first; then multilingual with emotions
        assertEquals(listOf("ru-RU-DmitryNeural", "ru-RU-SvetlanaNeural", "en-US-AvaMultilingualNeural"), ru.map { it.shortName })
        assertTrue(all.first { it.shortName.startsWith("en-US-Ava") }.multilingual)
    }

    @Test
    fun styleForTone() {
        val st = listOf("cheerful", "sad", "whispering", "newscast", "documentary-narration")
        assertEquals("documentary-narration", AzureSpeech.styleFor("documentary", st))
        assertEquals("newscast", AzureSpeech.styleFor("news", st))
        assertEquals("cheerful", AzureSpeech.styleFor("humor", st))
        assertEquals("whispering", AzureSpeech.styleFor("whisper", st))
        assertNull(AzureSpeech.styleFor("classic", st))
        assertNull(AzureSpeech.styleFor("ad", emptyList()))
        assertTrue(VoiceTone.ALL.all { AzureSpeech.TONE_STYLES.containsKey(it.id) })
    }

    @Test
    fun ssmlIsWellFormed() {
        val ava = AzureSpeech.parseVoices(json).first { it.shortName.startsWith("en-US-Ava") }
        val x = AzureSpeech.ssml("Кошки & <собаки>\n\nВторой абзац", ava, "ru", "cheerful", 1.3, 10, -2)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(x.byteInputStream())
        assertTrue(x, x.contains("<mstts:express-as style=\"cheerful\" styledegree=\"1.30\">"))
        assertTrue(x, x.contains("<lang xml:lang=\"ru-RU\">"))
        assertTrue(x, x.contains("rate=\"+10%\" pitch=\"-2%\""))
        assertTrue(x, x.contains("Кошки &amp; &lt;собаки&gt;<break time=\"350ms\"/>Второй абзац"))
        val sv = AzureSpeech.parseVoices(json).first()
        val y = AzureSpeech.ssml("Привет", sv, "ru", null, 1.0, 0, 0)
        assertEquals("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"ru-RU\"><voice name=\"ru-RU-SvetlanaNeural\">Привет</voice></speak>", y)
    }

    @Test
    fun prosodyOnlyWithoutStyle() {
        assertEquals(10 to 0, AzureSpeech.prosody("funny", 10, true))
        assertEquals(20 to 4, AzureSpeech.prosody("funny", 10, false))
        assertEquals("весёлый", AzureSpeech.styleLabel("cheerful", true))
        assertEquals("cheerful", AzureSpeech.styleLabel("cheerful", false))
    }
}

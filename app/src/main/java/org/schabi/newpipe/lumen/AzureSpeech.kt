/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray

/**
 * Azure AI Speech text-to-speech (pure logic): voices with their speaking styles (emotions),
 * the style for a [VoiceTone], SSML with mstts:express-as. Free tier F0: 0.5 million characters a month.
 * The HTTP calls are [Llm.azureSpeech] and [Llm.azureVoices]; the answer is a 16-bit mono WAV.
 */
object AzureSpeech {
    const val KEY = "AZURE_SPEECH"
    const val KEY_URL = "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"
    const val FREE_CHARS = 500_000
    const val FORMAT = "riff-24khz-16bit-mono-pcm"

    /** Regions of a Speech resource (the key works only in its own region). */
    val REGIONS = listOf("westeurope", "northeurope", "eastus", "eastus2", "westus", "westus2", "centralus", "uksouth",
        "francecentral", "germanywestcentral", "swedencentral", "switzerlandnorth", "southeastasia", "eastasia", "japaneast",
        "koreacentral", "australiaeast", "centralindia", "canadacentral", "brazilsouth", "uaenorth")

    data class Voice(
        val shortName: String, val locale: String, val female: Boolean, val name: String,
        val styles: List<String>, val multilingual: Boolean
    )

    fun host(region: String) = "${region.trim().lowercase().ifEmpty { "westeurope" }}.tts.speech.microsoft.com"

    /** Voices of the voices/list answer (neural only). */
    fun parseVoices(json: String): List<Voice> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val sn = o.optString("ShortName")
            if (sn.isBlank() || o.optString("VoiceType", "Neural") != "Neural") return@mapNotNull null
            val styles = o.optJSONArray("StyleList")?.let { s -> (0 until s.length()).map { s.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            val second = o.optJSONArray("SecondaryLocaleList")?.length() ?: 0
            Voice(sn, o.optString("Locale"), o.optString("Gender") == "Female", o.optString("LocalName").ifBlank { o.optString("DisplayName", sn) },
                styles, sn.contains("Multilingual") || second > 0)
        }
    }

    /**
     * Voices for a language: native ones first (with styles first), then multilingual voices that also speak it
     * (these are the ones with most emotions; they read Russian with a slight accent).
     */
    fun forLang(all: List<Voice>, lang: String): List<Voice> {
        val l = lang.substringBefore('-').lowercase()
        val native = all.filter { it.locale.substringBefore('-').equals(l, true) }.sortedByDescending { it.styles.size }
        val multi = all.filter { it.multilingual && it !in native && it.styles.isNotEmpty() }.sortedByDescending { it.styles.size }
        return native + multi
    }

    /** Azure styles that express a tone, best first. */
    val TONE_STYLES = mapOf(
        "classic" to emptyList(),
        "documentary" to listOf("documentary-narration", "narration-professional", "narration-relaxed", "serious"),
        "news" to listOf("newscast", "newscast-formal", "newscast-casual", "narration-professional"),
        "humor" to listOf("cheerful", "friendly", "excited"),
        "funny" to listOf("excited", "cheerful", "friendly"),
        "sarcastic" to listOf("unfriendly", "disgruntled", "serious"),
        "excited" to listOf("excited", "cheerful", "shouting"),
        "motivational" to listOf("hopeful", "excited", "cheerful"),
        "warm" to listOf("friendly", "affectionate", "gentle", "empathetic"),
        "calm" to listOf("calm", "gentle", "narration-relaxed"),
        "whisper" to listOf("whispering", "gentle"),
        "dramatic" to listOf("serious", "fearful", "angry"),
        "mystery" to listOf("whispering", "terrified", "serious"),
        "sad" to listOf("sad", "depressed", "empathetic"),
        "tale" to listOf("story", "narration-relaxed", "gentle", "cheerful"),
        "ad" to listOf("advertisement_upbeat", "excited", "cheerful"),
    )

    /** The voice's style for [tone], or null (then the tone is only rate and pitch). */
    fun styleFor(tone: String, styles: List<String>): String? =
        TONE_STYLES[VoiceTone.of(tone).id].orEmpty().firstOrNull { s -> styles.any { it.equals(s, true) } }

    fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /**
     * SSML of one request: [voice] reads [text] (paragraphs → breaks) in [style] with [degree] 0.01–2;
     * [rate] and [pitch] in percent; [lang] is the text's language (multilingual voices need it).
     */
    fun ssml(text: String, voice: Voice, lang: String, style: String?, degree: Double, rate: Int, pitch: Int): String {
        val loc = if (voice.locale.substringBefore('-').equals(lang.substringBefore('-'), true)) voice.locale else Studio.recogLang(lang)
        val body = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            .joinToString("<break time=\"350ms\"/>") { xml(it.trim().replace(Regex("\\s+"), " ")) }
        var inner = body
        if (rate != 0 || pitch != 0) inner = "<prosody rate=\"${sign(rate)}%\" pitch=\"${sign(pitch)}%\">$inner</prosody>"
        if (loc != voice.locale) inner = "<lang xml:lang=\"$loc\">$inner</lang>"
        if (style != null) inner = "<mstts:express-as style=\"$style\" styledegree=\"${"%.2f".format(java.util.Locale.ROOT, degree.coerceIn(0.01, 2.0))}\">$inner</mstts:express-as>"
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"${voice.locale}\">" +
            "<voice name=\"${xml(voice.shortName)}\">$inner</voice></speak>"
    }

    private fun sign(v: Int) = if (v >= 0) "+$v" else "$v"

    /** Edge-style shift of a tone as SSML percent: rate as is, pitch Hz → ~% (1 Hz ≈ 0.6 %). */
    fun prosody(tone: String, rate: Int, styled: Boolean): Pair<Int, Int> {
        val t = VoiceTone.of(tone)
        // with a real style the voice already sounds the mood: only the author's rate stays
        return if (styled) rate to 0 else (rate + t.rate) to (t.pitch * 6 / 10)
    }

    /** Russian style names for the chips. */
    fun styleLabel(s: String, ru: Boolean): String = if (!ru) s else when (s) {
        "cheerful" -> "весёлый"; "friendly" -> "дружелюбный"; "excited" -> "восторг"; "sad" -> "грусть"; "angry" -> "злость"
        "calm" -> "спокойный"; "gentle" -> "мягкий"; "whispering" -> "шёпот"; "shouting" -> "крик"; "serious" -> "серьёзный"
        "fearful" -> "страх"; "terrified" -> "ужас"; "hopeful" -> "надежда"; "empathetic" -> "сочувствие"; "affectionate" -> "нежность"
        "newscast", "newscast-formal", "newscast-casual" -> "новости"; "documentary-narration" -> "документальный"
        "narration-professional" -> "диктор"; "narration-relaxed" -> "рассказчик"; "advertisement_upbeat" -> "реклама"
        "unfriendly" -> "холодный"; "disgruntled" -> "недовольный"; "depressed" -> "уныние"; "story" -> "сказка"
        "chat" -> "беседа"; "assistant" -> "ассистент"; "customerservice" -> "поддержка"; "embarrassed" -> "смущение"
        "lyrical" -> "лирика"; "poetry-reading" -> "стихи"; "sports_commentary" -> "спорт"; "sports_commentary_excited" -> "спорт!"
        else -> s
    }
}

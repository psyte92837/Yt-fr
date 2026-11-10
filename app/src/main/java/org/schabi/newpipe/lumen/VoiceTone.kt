/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Tone of a synthesised voice-over (pure logic): Gemini gets a reading instruction in words,
 * Edge (no styles in its free service) gets rate and pitch shifts that hint the mood.
 */
object VoiceTone {
    data class Tone(
        val id: String, val emoji: String, val ru: String, val en: String,
        /** Instruction for Gemini, Russian and English. */
        val sayRu: String, val sayEn: String,
        /** Edge: added to the speech rate (%) and pitch (Hz). */
        val rate: Int, val pitch: Int
    )

    const val DEFAULT = "classic"

    val ALL = listOf(
        Tone("classic", "🎙", "Классика", "Classic", "ровно и уверенно, как ведущий ролика", "evenly and confidently, like a video host", 0, 0),
        Tone("documentary", "🎞", "Документальная", "Documentary", "как диктор документального фильма: весомо, неторопливо, с паузами", "like a documentary narrator: weighty, unhurried, with pauses", -8, -3),
        Tone("news", "📰", "Новости", "News", "как диктор новостей: чётко, деловито, без эмоций", "like a news anchor: crisp, matter-of-fact, neutral", 4, 0),
        Tone("humor", "😄", "С юмором", "Humorous", "с юмором и лёгкой иронией, с улыбкой в голосе", "with humour and light irony, a smile in the voice", 6, 3),
        Tone("funny", "🤪", "Забавно", "Funny", "забавно и игриво, преувеличенно, как в смешном ролике", "funny and playful, exaggerated, like a comedy clip", 10, 8),
        Tone("sarcastic", "😏", "Сарказм", "Sarcastic", "с сарказмом, иронично растягивая ключевые слова", "sarcastically, ironically stretching the key words", -2, -2),
        Tone("excited", "🔥", "Энергично", "Energetic", "энергично и восторженно, на подъёме", "energetically and excitedly, upbeat", 12, 5),
        Tone("motivational", "💪", "Мотивация", "Motivational", "вдохновляюще и уверенно, как мотивационный спикер", "inspiring and confident, like a motivational speaker", 4, 2),
        Tone("warm", "🤗", "Тепло", "Warm", "тепло и дружелюбно, как близкому другу", "warmly and kindly, like to a close friend", -3, 0),
        Tone("calm", "🧘", "Спокойно", "Calm", "спокойно и умиротворённо, мягко", "calmly and soothingly, softly", -10, -2),
        Tone("whisper", "🤫", "Шёпот", "Whisper", "шёпотом, доверительно, как секрет", "in a whisper, confidentially, like a secret", -8, -4),
        Tone("dramatic", "🎭", "Драма", "Dramatic", "драматично и напряжённо, как в трейлере фильма", "dramatically and intensely, like a movie trailer", -5, -5),
        Tone("mystery", "🕯", "Загадочно", "Mysterious", "загадочно и таинственно, нагнетая интригу", "mysteriously, building up suspense", -10, -6),
        Tone("sad", "😢", "Грустно", "Sad", "грустно и задумчиво", "sadly and thoughtfully", -12, -4),
        Tone("tale", "📖", "Сказка", "Fairy tale", "как сказочник для детей: выразительно, с интонациями", "like a storyteller for children: expressive, with lively intonation", -4, 4),
        Tone("ad", "📣", "Реклама", "Advert", "как в рекламе: продающе, бодро и чётко", "like an advert: persuasive, upbeat and crisp", 8, 3),
    )

    fun of(id: String): Tone = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun label(t: Tone, ru: Boolean) = t.emoji + " " + if (ru) t.ru else t.en

    /** Gemini reading instruction: tone, pace ([rate] −10 … 20) and the author's own [wish]; ends with a colon. */
    fun instruction(id: String, rate: Int, wish: String, ru: Boolean): String {
        val t = of(id)
        val pace = when {
            rate <= -5 -> if (ru) "медленно" else "slowly"
            rate >= 15 -> if (ru) "быстро" else "fast"
            rate >= 5 -> if (ru) "в бодром темпе" else "at a brisk pace"
            else -> ""
        }
        val parts = listOfNotNull(if (ru) t.sayRu else t.sayEn, pace.ifEmpty { null }, wish.trim().trimEnd('.', ':', ';').ifEmpty { null })
        return (if (ru) "Прочитай " else "Read ") + parts.joinToString(", ") + ":"
    }

    /** How emotions change through the text: one tone, by the meaning of every paragraph, or the author's own set. */
    const val MIX_ONE = "one"
    const val MIX_AUTO = "auto"

    /** Default Gemini voice-over prompt (key studio_tts_gemini): director's notes, then the text after «ТЕКСТ:». */
    const val PROMPT = "# ПРОФИЛЬ ГОЛОСА\n" +
        "Диктор ролика для YouTube и Shorts, язык: {lang}. Звучит живо, как человек, а не робот.\n\n" +
        "## ПОДАЧА\n" +
        "Тон: {tone}.\n" +
        "Темп: {pace}.\n" +
        "Эмоции: {emotions}\n" +
        "{wish}\n\n" +
        "## ПРАВИЛА\n" +
        "- Вслух читай только текст после строки «ТЕКСТ:», эти заметки не произноси.\n" +
        "- Каждый абзац — отдельная сцена: короткая пауза между абзацами.\n" +
        "- Выделяй голосом ключевые слова и числа, не тараторь, не глотай окончания.\n\n" +
        "ТЕКСТ:"

    fun pace(rate: Int): String = when {
        rate <= -5 -> "медленно, с паузами, около 120 слов в минуту"
        rate >= 15 -> "быстро и плотно, около 190 слов в минуту"
        rate >= 5 -> "бодро, около 170 слов в минуту"
        else -> "обычный разговорный, около 150 слов в минуту"
    }

    /** [mix]: [MIX_ONE], [MIX_AUTO] or tone ids separated by commas. */
    fun emotions(mix: String, tone: String): String = when (mix.trim()) {
        MIX_ONE, "" -> "держи один тон на весь текст, без резких перепадов."
        MIX_AUTO -> "меняй эмоцию по смыслу каждого абзаца: интрига в начале, улыбка на шутках, серьёзность на фактах и цифрах, " +
            "сочувствие на проблемах, энергия в призыве в конце; переходы плавные, основа — тон выше."
        else -> {
            val set = mix.split(',').map { it.trim() }.filter { id -> ALL.any { it.id == id } && id != of(tone).id }.distinct()
            if (set.isEmpty()) emotions(MIX_ONE, tone)
            else "основа — тон выше, а по смыслу абзацев смешивай: " + set.joinToString("; ") { of(it).let { t -> t.ru.lowercase() + " (" + t.sayRu + ")" } } +
                ". Переходы плавные, каждую эмоцию бери там, где она подходит по смыслу."
        }
    }

    /** The Gemini prompt [template] filled for this voice-over. */
    fun direction(template: String, tone: String, rate: Int, wish: String, mix: String, langName: String): String =
        template.replace("{lang}", langName).replace("{tone}", of(tone).sayRu).replace("{pace}", pace(rate))
            .replace("{emotions}", emotions(mix, tone))
            .replace("{wish}", wish.trim().let { if (it.isEmpty()) "" else "Пожелание автора: $it" })
            .replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Edge options with the tone's rate and pitch shift (rate stays −50…+50 %, pitch −20…+20 Hz). */
    fun edge(o: TtsOptions, id: String): TtsOptions {
        val t = of(id)
        return o.copy(rate = (o.rate + t.rate).coerceIn(-50, 50), pitch = (o.pitch + t.pitch).coerceIn(-20, 20))
    }
}

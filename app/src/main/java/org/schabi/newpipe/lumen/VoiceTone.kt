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

    /** Edge options with the tone's rate and pitch shift (rate stays −50…+50 %, pitch −20…+20 Hz). */
    fun edge(o: TtsOptions, id: String): TtsOptions {
        val t = of(id)
        return o.copy(rate = (o.rate + t.rate).coerceIn(-50, 50), pitch = (o.pitch + t.pitch).coerceIn(-20, 20))
    }
}

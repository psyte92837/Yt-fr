/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Sound effects of a piece of the editor (protocol lumen.soundfx v1): 50 presets such as an old radio, an old film,
 * a far-away voice, a church or a robot, built from small DSP stages (RBJ biquads, saturation, bit crushing,
 * compressor, noises, delay, Freeverb-style reverb, chorus/flanger/phaser, tremolo, ring modulation, wah, a two-tap
 * pitch shifter, stereo tools). Pure Kotlin: the same chain sounds in the preview (an ExoPlayer audio processor) and
 * in the export ([EditAudio]). [chain] works on interleaved 16-bit PCM in place; [VClip.sfxK] mixes the effect with
 * the dry sound.
 */
object EditSound {
    const val PROTOCOL = "lumen.soundfx v1"

    class Fx(val id: String, val group: String, val emoji: String, val ru: String, val en: String, val ruHint: String, val enHint: String) {
        val name: String get() = t(ru, en)
        val hint: String get() = t(ruHint, enHint)
    }

    class Group(val id: String, val ru: String, val en: String) {
        val name: String get() = t(ru, en)
    }

    val GROUPS = listOf(
        Group("retro", "📻 Ретро", "📻 Retro"),
        Group("space", "🏛 Пространство", "🏛 Space"),
        Group("comm", "📞 Связь", "📞 Devices"),
        Group("voice", "🗣 Голос", "🗣 Voice"),
        Group("mod", "🌀 Модуляция", "🌀 Modulation"),
        Group("mood", "✨ Атмосфера", "✨ Mood"),
        Group("tone", "🎚 Тон", "🎚 Tone")
    )

    private fun fx(id: String, g: String, e: String, ru: String, en: String, rh: String, eh: String) = Fx(id, g, e, ru, en, rh, eh)

    val ALL: List<Fx> = listOf(
        // retro
        fx("radio_old", "retro", "📻", "Старое радио", "Old radio", "Узкая полоса, ламповый перегруз, шипение и фон 50 Гц", "Narrow band, tube drive, hiss and 50 Hz hum"),
        fx("radio_war", "retro", "📡", "Коротковолновик", "Shortwave", "Плывущая громкость, свист гетеродина, треск эфира", "Fading level, heterodyne whistle, static"),
        fx("gramophone", "retro", "🎺", "Граммофон", "Gramophone", "Рупор, сильный треск, плавание скорости", "Horn tone, heavy crackle, speed wobble"),
        fx("vinyl", "retro", "💿", "Пластинка", "Vinyl", "Тёплый звук, щелчки и лёгкое шипение", "Warm, clicks and light hiss"),
        fx("cassette", "retro", "📼", "Кассета", "Cassette", "Детонация плёнки, завал верхов, шум ленты", "Wow and flutter, rolled-off highs, tape hiss"),
        fx("film_old", "retro", "🎞", "Старое кино", "Old film", "Оптическая дорожка 1930-х: моно, треск, дрожь проектора", "1930s optical track: mono, crackle, projector flutter"),
        fx("tv_old", "retro", "📺", "Старый телевизор", "Old TV", "Маленький динамик, гул строчной развёртки", "Tiny speaker, line-scan whine"),
        fx("lofi", "retro", "🌙", "Lo-fi", "Lo-fi", "Пониженная частота, мягкие верха, винил", "Lower sample rate, soft highs, vinyl"),
        fx("bit8", "retro", "👾", "8-бит", "8-bit", "Звук приставки: 4 бита и 8 кГц", "Console sound: 4 bits at 8 kHz"),
        // space
        fx("distance", "space", "🔭", "Вдалеке", "Far away", "Голос отдаляется: тише, глуше, больше отражений", "The voice moves away: quieter, duller, more reflections"),
        fx("next_room", "space", "🚪", "За стеной", "Behind a wall", "Глухо, только низы проходят сквозь стену", "Muffled, only the lows come through"),
        fx("small_room", "space", "🛋", "Комната", "Room", "Короткие отражения небольшой комнаты", "Short reflections of a small room"),
        fx("bathroom", "space", "🛁", "Ванная", "Bathroom", "Звонкая плитка, яркое короткое эхо", "Bright tiles, short ringing echo"),
        fx("hall", "space", "🎭", "Концертный зал", "Concert hall", "Широкий мягкий хвост", "Wide soft tail"),
        fx("church", "space", "⛪", "Собор", "Cathedral", "Очень длинный хвост каменного зала", "Very long tail of a stone hall"),
        fx("cave", "space", "🕳", "Пещера", "Cave", "Тёмная реверберация и повторы эха", "Dark reverb and repeating echoes"),
        fx("stadium", "space", "🏟", "Стадион", "Stadium", "Далёкие запаздывающие отражения трибун", "Late distant reflections of the stands"),
        fx("station", "space", "🚉", "Вокзал", "Station PA", "Громкоговоритель в большом гулком зале", "A loudspeaker in a big echoing hall"),
        fx("underwater", "space", "🌊", "Под водой", "Underwater", "Глухо, плывёт, пузырится", "Muffled, wobbly, bubbly"),
        // devices
        fx("phone", "comm", "📞", "Телефон", "Phone", "Полоса 300–3400 Гц и сжатие линии", "300–3400 Hz band and line compression"),
        fx("walkie", "comm", "📟", "Рация", "Walkie-talkie", "Узко, перегруз, шум эфира", "Narrow, overdriven, radio noise"),
        fx("megaphone", "comm", "📢", "Мегафон", "Megaphone", "Рупор с хрипом", "Horn with rasp"),
        fx("intercom", "comm", "🔔", "Домофон", "Intercom", "Металлический дешёвый динамик", "Cheap metallic speaker"),
        fx("broken", "comm", "🔊", "Сломанный динамик", "Broken speaker", "Хрип, дребезг и пропадания", "Rasp, rattle and dropouts"),
        fx("smartphone", "comm", "📱", "Динамик телефона", "Phone speaker", "Без баса, как видео с телефона на столе", "No bass, like a phone on the table"),
        // voice
        fx("clear", "voice", "🎙", "Чистый голос", "Clear voice", "Убрать гул, добавить разборчивость, выровнять", "Cut rumble, add presence, even out"),
        fx("podcast", "voice", "🎧", "Подкаст", "Podcast", "Плотный тёплый голос близко к микрофону", "Dense warm close-mic voice"),
        fx("radio_host", "voice", "📣", "Ведущий FM", "FM host", "Яркий громкий эфирный звук", "Bright loud broadcast sound"),
        fx("deeper", "voice", "⬇", "Ниже", "Lower", "Голос на 3 полутона ниже, скорость та же", "Three semitones lower, same speed"),
        fx("higher", "voice", "⬆", "Выше", "Higher", "Голос на 3 полутона выше, скорость та же", "Three semitones higher, same speed"),
        fx("chipmunk", "voice", "🐿", "Бурундук", "Chipmunk", "Высокий мультяшный голос", "High cartoon voice"),
        fx("monster", "voice", "👹", "Монстр", "Monster", "Низкий хриплый голос", "Low gritty voice"),
        fx("robot", "voice", "🤖", "Робот", "Robot", "Металлический гребёнчатый голос", "Metallic comb voice"),
        fx("alien", "voice", "👽", "Пришелец", "Alien", "Кольцевая модуляция и плавающий тон", "Ring modulation and drifting pitch"),
        fx("ghost", "voice", "👻", "Призрак", "Ghost", "Шёпот в пустоте с хором и хвостом", "Whisper in the void with chorus and tail"),
        // modulation
        fx("chorus", "mod", "🎶", "Хорус", "Chorus", "Несколько голосов в унисон", "Several voices in unison"),
        fx("flanger", "mod", "✈", "Флэнжер", "Flanger", "Реактивный свист", "Jet sweep"),
        fx("phaser", "mod", "🌀", "Фэйзер", "Phaser", "Плавающие провалы спектра", "Moving spectral notches"),
        fx("tremolo", "mod", "〰", "Тремоло", "Tremolo", "Пульсирующая громкость", "Pulsing volume"),
        fx("vibrato", "mod", "🎻", "Вибрато", "Vibrato", "Колебание высоты", "Pitch wobble"),
        fx("wah", "mod", "🎸", "Вау", "Wah", "Гуляющий фильтр", "Sweeping filter"),
        fx("pan8d", "mod", "🎧", "8D-звук", "8D audio", "Звук кружит вокруг головы (в наушниках)", "Sound circles around the head (headphones)"),
        fx("glitch", "mod", "⚡", "Глитч", "Glitch", "Заикания и повторы кусочков", "Stutters and repeated slices"),
        // mood
        fx("echo", "mood", "🏔", "Эхо", "Echo", "Повторы с затуханием", "Fading repeats"),
        fx("dream", "mood", "☁", "Сон", "Dream", "Мягко, широко, с хорусом и хвостом", "Soft, wide, with chorus and tail"),
        fx("horror", "mood", "🕯", "Хоррор", "Horror", "Ниже, дрожь и тёмный хвост", "Lower, trembling and dark tail"),
        fx("space", "mood", "🚀", "Космос", "Outer space", "Огромный холодный хвост и шлем", "Huge cold tail and a helmet"),
        // tone
        fx("bass", "tone", "🔈", "Больше баса", "Bass boost", "Подъём низов", "Low shelf lift"),
        fx("trailer", "tone", "🎬", "Кино-трейлер", "Movie trailer", "Мощный низ, сжатие, большой зал", "Big lows, compression, large hall"),
        fx("loud", "tone", "📶", "Громче и ровнее", "Louder & even", "Компрессор и лимитер", "Compressor and limiter")
    )

    private val byId = ALL.associateBy { it.id }

    fun find(id: String): Fx? = byId[id]

    fun isKnown(id: String): Boolean = id in byId

    fun of(group: String): List<Fx> = ALL.filter { it.group == group }

    /** Label for the timeline badge and the panel. */
    fun label(id: String): String = find(id)?.let { "${it.emoji} ${it.name}" } ?: ""

    /** A ready chain of effect [id] for PCM at [rate] Hz with [ch] channels; [k] 0..1 is the strength. Null for "" or an unknown id. */
    fun chain(id: String, rate: Int, ch: Int, k: Float = 1f, seed: Long = 1L): Chain? {
        if (k <= 0f) return null
        val r = rate.coerceAtLeast(8000).toFloat()
        val c = max(1, ch)
        val st = stages(id, r, c, seed) ?: return null
        // a DC blocker first (reverbs and drives pile up any offset), the level match last: the effect sounds as loud as the dry sound
        val trim = TRIM[id] ?: 0f
        return Chain(listOf(DcBlock(c)) + st + DcBlock(c) + (if (trim != 0f) listOf(Gain(trim)) else emptyList()), c, k.coerceIn(0f, 1f))
    }

    /**
     * Level match per preset in dB, measured on speech at −18 dBFS (EditSoundTest.presetsKeepTheLevel checks it):
     * every effect comes out within about ±1.5 dB of the dry sound, "far away" and "behind a wall" a little quieter on purpose.
     */
    internal val TRIM: Map<String, Float> = mapOf(
        "radio_old" to 1.6f, "radio_war" to 1.3f, "gramophone" to -1.9f, "vinyl" to 0.5f, "film_old" to 1.3f,
        "tv_old" to -3.0f, "next_room" to 7.0f, "small_room" to -0.9f, "bathroom" to -2.8f, "hall" to -3.4f,
        "church" to -5.6f, "cave" to -3.1f, "stadium" to -7.1f, "station" to -6.7f, "underwater" to 2.2f,
        "phone" to 4.1f, "walkie" to 4.1f, "megaphone" to -3.1f, "intercom" to -3.4f, "broken" to -0.8f,
        "smartphone" to 3.0f, "clear" to 2.3f, "podcast" to 0.8f, "radio_host" to 2.9f, "deeper" to 1.4f,
        "higher" to 1.4f, "chipmunk" to 1.5f, "robot" to 1.6f, "alien" to 1.1f, "ghost" to -3.7f, "chorus" to 3.1f,
        "flanger" to -1.7f, "phaser" to -1.0f, "tremolo" to 3.3f, "wah" to -4.8f, "pan8d" to -0.8f, "glitch" to 1.4f,
        "dream" to -2.6f, "horror" to -5.1f, "space" to -5.8f, "bass" to 2.7f, "trailer" to 2.6f
    )

    // ---------- presets ----------

    private fun stages(id: String, r: Float, ch: Int, seed: Long): List<Stage>? = when (id) {
        "radio_old" -> listOf(Mono(), Biq.hp(r, ch, 350f), Biq.lp(r, ch, 3800f), Biq.peak(r, ch, 1500f, 1.0f, 4f), Drive(2.6f, 0.15f),
            Noise(r, ch, seed, hiss = -40f, hum = -44f), Biq.hp(r, ch, 120f), Comp(r, -20f, 3f, 5f, 120f, 3f))
        "radio_war" -> listOf(Mono(), Biq.hp(r, ch, 500f), Biq.lp(r, ch, 2800f), Drive(3f, 0f), Fading(r, seed, 0.17f, 0.75f),
            Noise(r, ch, seed, hiss = -32f, crackle = 6f, tone = 1100f, toneDb = -40f), Comp(r, -24f, 4f, 5f, 200f, 4f), Gain(5f))
        "gramophone" -> listOf(Mono(), ModDelay(r, ch, 6f, 1.2f, 0.55f, 0f, wetOnly = true), Biq.hp(r, ch, 600f), Biq.lp(r, ch, 3000f),
            Biq.peak(r, ch, 1200f, 1.2f, 6f), Drive(2f, 0.1f), Noise(r, ch, seed, hiss = -34f, crackle = 28f, crackleDb = -14f), Gain(-3f))
        "vinyl" -> listOf(Biq.lowShelf(r, ch, 200f, 2f), Biq.lp(r, ch, 9000f), Drive(1.3f, 0f),
            Noise(r, ch, seed, hiss = -52f, crackle = 5f, crackleDb = -20f, rumble = -50f), Gain(-3f))
        "cassette" -> listOf(ModDelay(r, ch, 5f, 0.6f, 0.9f, 0f, wetOnly = true, rate2 = 7f, depth2 = 0.4f), Biq.lp(r, ch, 9500f),
            Biq.lowShelf(r, ch, 120f, 2f), Drive(1.6f, 0.05f), Noise(r, ch, seed, hiss = -46f), Gain(-3f))
        "film_old" -> listOf(Mono(), Biq.hp(r, ch, 160f), Biq.lp(r, ch, 5500f), Biq.peak(r, ch, 2500f, 1f, 2f),
            ModDelay(r, ch, 4f, 0.15f, 24f, 0f, wetOnly = true), Drive(1.8f, 0.05f),
            Noise(r, ch, seed, hiss = -40f, crackle = 10f, crackleDb = -18f, hum = -54f), Comp(r, -18f, 2.5f, 5f, 150f, 2f))
        "tv_old" -> listOf(Mono(), Biq.hp(r, ch, 400f), Biq.lp(r, ch, 6000f), Biq.peak(r, ch, 2000f, 2f, 5f), Drive(1.6f, 0f),
            Noise(r, ch, seed, hiss = -48f, tone = min(15625f, r * 0.45f), toneDb = -52f))
        "lofi" -> listOf(Hold(r, 11025f), Crush(10), Biq.lp(r, ch, 4200f), Biq.lowShelf(r, ch, 250f, 3f),
            ModDelay(r, ch, 5f, 0.3f, 0.5f, 0f, wetOnly = true), Noise(r, ch, seed, hiss = -50f, crackle = 4f, crackleDb = -24f))
        "bit8" -> listOf(Mono(), Hold(r, 8000f), Crush(4), Biq.hp(r, ch, 150f))
        "distance" -> listOf(Biq.lp(r, ch, 2600f), Biq.hp(r, ch, 180f), Gain(-9f),
            Reverb(r, ch, room = 0.72f, damp = 0.55f, wet = 0.55f, dry = 0.45f, preMs = 30f))
        "next_room" -> listOf(Mono(), Biq.lp(r, ch, 380f), Biq.lp(r, ch, 600f), Gain(-4f),
            Reverb(r, ch, room = 0.45f, damp = 0.7f, wet = 0.25f, dry = 0.9f))
        "small_room" -> listOf(Reverb(r, ch, room = 0.35f, damp = 0.4f, wet = 0.22f, dry = 0.95f, preMs = 4f))
        "bathroom" -> listOf(Biq.peak(r, ch, 3500f, 1f, 3f), Reverb(r, ch, room = 0.55f, damp = 0.1f, wet = 0.35f, dry = 0.85f, preMs = 3f))
        "hall" -> listOf(Reverb(r, ch, room = 0.82f, damp = 0.45f, wet = 0.3f, dry = 0.85f, preMs = 25f))
        "church" -> listOf(Reverb(r, ch, room = 0.95f, damp = 0.3f, wet = 0.45f, dry = 0.7f, preMs = 45f), Gain(-3f))
        "cave" -> listOf(Biq.lp(r, ch, 5000f), Delay(r, ch, 320f, 0.45f, 0.35f, 2500f),
            Reverb(r, ch, room = 0.9f, damp = 0.6f, wet = 0.4f, dry = 0.75f, preMs = 20f), Gain(-4f))
        "stadium" -> listOf(Biq.hp(r, ch, 150f), Delay(r, ch, 180f, 0.3f, 0.3f, 3500f),
            Reverb(r, ch, room = 0.9f, damp = 0.5f, wet = 0.42f, dry = 0.75f, preMs = 80f))
        "station" -> listOf(Mono(), Biq.hp(r, ch, 450f), Biq.lp(r, ch, 4500f), Biq.peak(r, ch, 2000f, 1.4f, 5f), Drive(1.8f, 0f),
            Delay(r, ch, 240f, 0.25f, 0.25f, 3000f), Reverb(r, ch, room = 0.93f, damp = 0.45f, wet = 0.45f, dry = 0.7f, preMs = 40f), Gain(-5f))
        "underwater" -> listOf(Biq.lp(r, ch, 450f), Biq.lp(r, ch, 700f), ModDelay(r, ch, 8f, 2.5f, 0.6f, 0f, wetOnly = true),
            Reverb(r, ch, room = 0.7f, damp = 0.85f, wet = 0.4f, dry = 0.8f), Noise(r, ch, seed, bubbles = 3f), Gain(2f))
        "phone" -> listOf(Mono(), Biq.hp(r, ch, 300f), Biq.hp(r, ch, 300f), Biq.lp(r, ch, 3400f), Biq.lp(r, ch, 3400f),
            Biq.peak(r, ch, 1700f, 1f, 4f), Drive(1.8f, 0f), Comp(r, -22f, 4f, 3f, 80f, 4f))
        "walkie" -> listOf(Mono(), Biq.hp(r, ch, 600f), Biq.lp(r, ch, 2600f), Drive(5f, 0.1f), Biq.hp(r, ch, 500f),
            Noise(r, ch, seed, hiss = -36f), Comp(r, -26f, 6f, 2f, 80f, 6f), Gain(3f))
        "megaphone" -> listOf(Mono(), Biq.hp(r, ch, 700f), Biq.lp(r, ch, 3800f), Biq.peak(r, ch, 1800f, 1.5f, 8f), Drive(4f, 0.2f),
            Biq.lp(r, ch, 4500f), Reverb(r, ch, room = 0.5f, damp = 0.5f, wet = 0.12f, dry = 1f), Gain(-3f))
        "intercom" -> listOf(Mono(), Biq.hp(r, ch, 500f), Biq.lp(r, ch, 3000f), Comb(r, ch, 2.3f, 0.55f, 0.4f), Drive(2.5f, 0f),
            Hold(r, 11025f), Noise(r, ch, seed, hiss = -42f, hum = -46f))
        "broken" -> listOf(Mono(), Biq.hp(r, ch, 350f), Fold(2.2f), Biq.peak(r, ch, 900f, 2f, 7f), Biq.lp(r, ch, 4000f),
            Dropouts(r, seed), Noise(r, ch, seed, crackle = 14f, crackleDb = -16f), Gain(-6f))
        "smartphone" -> listOf(Biq.hp(r, ch, 650f), Biq.hp(r, ch, 650f), Biq.lp(r, ch, 9000f), Biq.peak(r, ch, 3000f, 1f, 3f),
            Comp(r, -20f, 3f, 5f, 100f, 2f), Reverb(r, ch, room = 0.25f, damp = 0.3f, wet = 0.1f, dry = 1f), Gain(3f))
        "clear" -> listOf(Biq.hp(r, ch, 85f), Biq.peak(r, ch, 300f, 1f, -3f), Biq.peak(r, ch, 3200f, 0.9f, 3.5f),
            Biq.highShelf(r, ch, 9000f, 2f), Comp(r, -20f, 3f, 8f, 150f, 4f))
        "podcast" -> listOf(Biq.hp(r, ch, 70f), Biq.lowShelf(r, ch, 160f, 3f), Biq.peak(r, ch, 400f, 1.2f, -2.5f),
            Biq.peak(r, ch, 4000f, 1f, 2.5f), Comp(r, -24f, 4f, 5f, 120f, 7f), Drive(1.2f, 0f))
        "radio_host" -> listOf(Biq.hp(r, ch, 90f), Biq.lowShelf(r, ch, 120f, 4f), Biq.peak(r, ch, 3000f, 1f, 4f),
            Biq.highShelf(r, ch, 10000f, 4f), Comp(r, -28f, 6f, 2f, 80f, 10f), Gain(3f))
        "deeper" -> listOf(Pitch(r, ch, -3f))
        "higher" -> listOf(Pitch(r, ch, 3f))
        "chipmunk" -> listOf(Pitch(r, ch, 8f, grainMs = 40f), Biq.hp(r, ch, 200f))
        "monster" -> listOf(Pitch(r, ch, -7f), Biq.lowShelf(r, ch, 150f, 5f), Drive(2.2f, 0.1f), Reverb(r, ch, room = 0.5f, damp = 0.6f, wet = 0.15f, dry = 1f), Gain(-4f))
        "robot" -> listOf(Mono(), Comb(r, ch, 9f, 0.75f, 0.8f), Ring(r, 55f, 0.5f), Biq.hp(r, ch, 150f), Drive(1.5f, 0f))
        "alien" -> listOf(Ring(r, 32f, 0.8f, sweep = 0.25f), ModDelay(r, ch, 7f, 3f, 1.5f, 0.3f), Biq.highShelf(r, ch, 4000f, 3f), Gain(6f))
        "ghost" -> listOf(Pitch(r, ch, 2f), ModDelay(r, ch, 18f, 6f, 0.3f, 0.2f, mix = 0.6f), Biq.hp(r, ch, 300f),
            Reverb(r, ch, room = 0.92f, damp = 0.2f, wet = 0.65f, dry = 0.45f, preMs = 60f), Trem(r, 4f, 0.25f))
        "chorus" -> listOf(ModDelay(r, ch, 18f, 4f, 0.9f, 0f, mix = 0.5f, stereo = true))
        "flanger" -> listOf(ModDelay(r, ch, 2.5f, 2f, 0.25f, 0.7f, mix = 0.5f), Gain(4f))
        "phaser" -> listOf(Phaser(r, ch, 0.35f, 300f, 2500f, 0.6f), Gain(3f))
        "tremolo" -> listOf(Trem(r, 6f, 0.7f))
        "vibrato" -> listOf(ModDelay(r, ch, 5f, 2.5f, 5.5f, 0f, wetOnly = true))
        "wah" -> listOf(Wah(r, ch, 1.4f, 400f, 2400f, 5f), Gain(-4f))
        "pan8d" -> listOf(Mono(), Pan(r, 0.12f), Reverb(r, ch, room = 0.6f, damp = 0.4f, wet = 0.18f, dry = 1f, width = 1f))
        "glitch" -> listOf(Stutter(r, ch, seed))
        "echo" -> listOf(Delay(r, ch, 420f, 0.45f, 0.4f, 4500f, pingPong = true))
        "dream" -> listOf(Biq.lp(r, ch, 6000f), ModDelay(r, ch, 20f, 5f, 0.4f, 0.1f, mix = 0.5f, stereo = true),
            Reverb(r, ch, room = 0.88f, damp = 0.5f, wet = 0.45f, dry = 0.7f, preMs = 20f))
        "horror" -> listOf(Pitch(r, ch, -2f), Trem(r, 7f, 0.3f), Biq.lp(r, ch, 4500f),
            Reverb(r, ch, room = 0.93f, damp = 0.7f, wet = 0.5f, dry = 0.65f, preMs = 30f))
        "space" -> listOf(Biq.hp(r, ch, 200f), Biq.peak(r, ch, 2500f, 1f, 3f), Comb(r, ch, 4f, 0.4f, 0.25f),
            Reverb(r, ch, room = 0.97f, damp = 0.15f, wet = 0.55f, dry = 0.6f, preMs = 70f), Gain(-5f))
        "bass" -> listOf(Biq.lowShelf(r, ch, 140f, 7f), Biq.peak(r, ch, 60f, 1f, 2f), Gain(-3f))
        "trailer" -> listOf(Biq.lowShelf(r, ch, 120f, 6f), Biq.highShelf(r, ch, 8000f, 2f), Comp(r, -26f, 5f, 5f, 200f, 8f),
            Reverb(r, ch, room = 0.85f, damp = 0.5f, wet = 0.25f, dry = 0.9f, preMs = 20f))
        "loud" -> listOf(Biq.hp(r, ch, 60f), Comp(r, -30f, 5f, 3f, 120f, 17f))
        else -> null
    }

    // ---------- the chain ----------

    /** Effect stages over interleaved PCM: dry/wet by strength [k], a soft limiter at the end. */
    class Chain internal constructor(private val stages: List<Stage>, private val ch: Int, private val k: Float) {
        private var buf = FloatArray(0)
        private var dry = FloatArray(0)

        /** [n] shorts of [a] (interleaved, [ch] channels) processed in place. */
        fun process(a: ShortArray, n: Int) {
            val len = n / ch * ch
            if (len <= 0) return
            if (buf.size < len) buf = FloatArray(len)
            for (i in 0 until len) buf[i] = a[i] / 32768f
            process(buf, len)
            for (i in 0 until len) a[i] = (buf[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        /** Same on floats in -1..1, in place and without rounding to 16 bits (the audio editor's mixer, tests). */
        fun process(f: FloatArray, n: Int) {
            val frames = n / ch
            val len = frames * ch
            if (len <= 0) return
            if (dry.size < len) dry = FloatArray(len)
            System.arraycopy(f, 0, dry, 0, len)
            for (s in stages) s.run(f, frames, ch)
            for (i in 0 until len) {
                val v = f[i]
                f[i] = soft(dry[i] + ((if (v.isNaN()) 0f else v) - dry[i]) * k)
            }
        }
    }

    /** Soft limiter: transparent below 0.8, never above 1. */
    fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    /** One step of a chain over [frames] interleaved frames of [ch] channels. */
    abstract class Stage {
        abstract fun run(b: FloatArray, frames: Int, ch: Int)
    }

    /** Removes a constant offset (a one-pole high-pass at ~7 Hz). */
    class DcBlock(ch: Int) : Stage() {
        private val x1 = FloatArray(ch)
        private val y1 = FloatArray(ch)
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, x1.size)
            for (f in 0 until frames) for (c in 0 until cc) {
                val i = f * ch + c
                val x = b[i]
                val y = x - x1[c] + 0.999f * y1[c]
                x1[c] = x
                y1[c] = if (abs(y) < 1e-20f) 0f else y
                b[i] = y
            }
        }
    }

    class Gain(db: Float) : Stage() {
        private val g = dbToGain(db)
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (i in 0 until frames * ch) b[i] *= g
        }
    }

    /** All channels as their average. */
    class Mono : Stage() {
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            if (ch < 2) return
            for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += b[f * ch + c]
                s /= ch
                for (c in 0 until ch) b[f * ch + c] = s
            }
        }
    }

    /** RBJ cookbook biquad (Direct Form I), one state per channel. */
    class Biq private constructor(ch: Int, private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) : Stage() {
        private val x1 = FloatArray(ch)
        private val x2 = FloatArray(ch)
        private val y1 = FloatArray(ch)
        private val y2 = FloatArray(ch)

        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, x1.size)
            for (f in 0 until frames) for (c in 0 until cc) {
                val i = f * ch + c
                val x = b[i]
                var y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                if (abs(y) < 1e-20f) y = 0f
                x2[c] = x1[c]; x1[c] = x
                y2[c] = y1[c]; y1[c] = y
                b[i] = y
            }
        }

        companion object {
            private fun make(ch: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biq(ch, (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            private fun w(r: Float, f: Float) = 2 * PI * min(f, r * 0.45f) / r

            fun lp(r: Float, ch: Int, f: Float, q: Float = 0.707f): Biq {
                val w = w(r, f); val al = sin(w) / (2 * q); val c = cos(w)
                return make(ch, (1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
            }

            fun hp(r: Float, ch: Int, f: Float, q: Float = 0.707f): Biq {
                val w = w(r, f); val al = sin(w) / (2 * q); val c = cos(w)
                return make(ch, (1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            }

            fun bp(r: Float, ch: Int, f: Float, q: Float): Biq {
                val w = w(r, f); val al = sin(w) / (2 * q); val c = cos(w)
                return make(ch, al, 0.0, -al, 1 + al, -2 * c, 1 - al)
            }

            fun peak(r: Float, ch: Int, f: Float, q: Float, db: Float): Biq {
                val a = 10.0.pow(db / 40.0); val w = w(r, f); val al = sin(w) / (2 * q); val c = cos(w)
                return make(ch, 1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
            }

            fun lowShelf(r: Float, ch: Int, f: Float, db: Float): Biq {
                val a = 10.0.pow(db / 40.0); val w = w(r, f); val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
                return make(ch, a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                    (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
            }

            fun highShelf(r: Float, ch: Int, f: Float, db: Float): Biq {
                val a = 10.0.pow(db / 40.0); val w = w(r, f); val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
                return make(ch, a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                    (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
            }
        }
    }

    /** Tube-like saturation: tanh with an offset for even harmonics, level kept. */
    class Drive(private val amount: Float, private val bias: Float) : Stage() {
        private val norm = 1f / tanh(amount)
        private val off = tanh(amount * bias)
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (i in 0 until frames * ch) b[i] = (tanh(amount * (b[i] + bias)) - off) * norm
        }
    }

    /** Wavefolder: a torn, rattling sound. */
    class Fold(private val amount: Float) : Stage() {
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (i in 0 until frames * ch) b[i] = sin(b[i] * amount * PI.toFloat() / 2) * 0.7f
        }
    }

    /** Fewer bits. */
    class Crush(bits: Int) : Stage() {
        private val steps = (1 shl (bits - 1)).toFloat()
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (i in 0 until frames * ch) b[i] = floor(b[i] * steps + 0.5f) / steps
        }
    }

    /** Sample-and-hold to a lower rate [to] (aliasing included, as in old samplers). */
    class Hold(r: Float, to: Float) : Stage() {
        private val step = (r / to).coerceAtLeast(1f)
        private var pos = 0f
        private var held = FloatArray(0)
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            if (held.size != ch) held = FloatArray(ch)
            for (f in 0 until frames) {
                if (pos <= 0f) {
                    for (c in 0 until ch) held[c] = b[f * ch + c]
                    pos += step
                }
                pos -= 1f
                for (c in 0 until ch) b[f * ch + c] = held[c]
            }
        }
    }

    /** Feed-forward compressor, channels linked, with make-up gain. */
    class Comp(r: Float, private val thrDb: Float, private val ratio: Float, attMs: Float, relMs: Float, makeupDb: Float) : Stage() {
        private val att = exp(-1f / (r * attMs / 1000f))
        private val rel = exp(-1f / (r * relMs / 1000f))
        private val makeup = dbToGain(makeupDb)
        private var env = 0f
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (f in 0 until frames) {
                var peak = 0f
                for (c in 0 until ch) peak = max(peak, abs(b[f * ch + c]))
                env = if (peak > env) att * env + (1 - att) * peak else rel * env + (1 - rel) * peak
                val db = 20f * kotlin.math.log10(max(env, 1e-6f))
                val over = db - thrDb
                val g = if (over > 0) dbToGain(-over * (1 - 1 / ratio)) * makeup else makeup
                for (c in 0 until ch) b[f * ch + c] *= g
            }
        }
    }

    /** Small deterministic random numbers. */
    class Rnd(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f
        }
        fun bi(): Float = next() * 2f - 1f
    }

    /** Added noises: hiss (pinkish), crackle (clicks per second), mains hum, low rumble, a steady tone, bubbles. */
    class Noise(
        private val r: Float, ch: Int, seed: Long,
        hiss: Float = -200f, private val crackle: Float = 0f, crackleDb: Float = -18f, hum: Float = -200f, rumble: Float = -200f,
        private val tone: Float = 0f, toneDb: Float = -200f, private val bubbles: Float = 0f
    ) : Stage() {
        private val rnd = Rnd(seed)
        private val hissG = dbToGain(hiss)
        private val crG = dbToGain(crackleDb)
        private val humG = dbToGain(hum)
        private val rumG = dbToGain(rumble)
        private val toneG = dbToGain(toneDb)
        private val hissLp = Biq.lp(r, ch, 7000f)
        private val rumLp = Biq.lp(r, 1, 60f)
        private var t = 0L
        private var click = 0f
        private var bub = 0f
        private var bubF = 0f
        private var bubPh = 0f
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val n = FloatArray(frames * ch)
            if (hissG > 1e-5f) {
                for (i in n.indices) n[i] = rnd.bi() * hissG
                hissLp.run(n, frames, ch)
            }
            val one = FloatArray(1)
            for (f in 0 until frames) {
                val sec = t / r
                var add = 0f
                if (humG > 1e-5f) add += humG * (sin(2 * PI * 50 * sec).toFloat() + 0.5f * sin(2 * PI * 150 * sec).toFloat() + 0.25f * sin(2 * PI * 250 * sec).toFloat())
                if (toneG > 1e-5f) add += toneG * sin(2 * PI * tone * sec).toFloat()
                if (rumG > 1e-5f) {
                    one[0] = rnd.bi()
                    rumLp.run(one, 1, 1)
                    add += one[0] * rumG * 8f
                }
                if (crackle > 0f) {
                    if (rnd.next() < crackle / r) click = (if (rnd.next() < 0.5f) -1f else 1f) * crG * (0.3f + rnd.next())
                    add += click
                    click *= 0.55f
                }
                if (bubbles > 0f) {
                    if (bub < 0.001f && rnd.next() < bubbles / r) {
                        bub = 0.06f
                        bubF = 300f + rnd.next() * 900f
                    }
                    if (bub > 0.001f) {
                        bubPh += (2 * PI * bubF / r).toFloat()
                        bubF *= 1.0002f
                        add += sin(bubPh) * bub
                        bub *= 0.9993f
                    }
                }
                for (c in 0 until ch) b[f * ch + c] += add + n[f * ch + c]
                t++
            }
        }
    }

    /** Slow random fading of the level, as with shortwave radio. */
    class Fading(private val r: Float, seed: Long, private val rate: Float, private val depth: Float) : Stage() {
        private val rnd = Rnd(seed + 7)
        private var ph = rnd.next() * 6f
        private var ph2 = rnd.next() * 6f
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val d1 = (2 * PI * rate / r).toFloat()
            val d2 = (2 * PI * rate * 2.7f / r).toFloat()
            for (f in 0 until frames) {
                ph += d1; ph2 += d2
                val m = 0.5f + 0.3f * sin(ph) + 0.2f * sin(ph2)
                val g = 1f - depth * (1f - m)
                for (c in 0 until ch) b[f * ch + c] *= g
            }
        }
    }

    /** Short random silences (a loose contact). */
    class Dropouts(private val r: Float, seed: Long) : Stage() {
        private val rnd = Rnd(seed + 3)
        private var left = 0
        private var g = 1f
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (f in 0 until frames) {
                if (left <= 0 && rnd.next() < 0.6f / r) left = (r * (0.03f + rnd.next() * 0.12f)).toInt()
                val target = if (left > 0) 0.05f else 1f
                g += (target - g) * 0.01f
                if (left > 0) left--
                for (c in 0 until ch) b[f * ch + c] *= g
            }
        }
    }

    /** A delay line per channel (fractional read). */
    class Line(size: Int) {
        val a = FloatArray(max(4, size))
        var w = 0
        fun push(v: Float) {
            a[w] = v
            w = (w + 1) % a.size
        }
        /** The sample [d] frames ago (d ≥ 1). */
        fun read(d: Float): Float {
            val dd = d.coerceIn(1f, (a.size - 2).toFloat())
            val p = w - dd
            val i0 = floor(p).toInt()
            val fr = p - i0
            val x0 = a[Math.floorMod(i0, a.size)]
            val x1 = a[Math.floorMod(i0 + 1, a.size)]
            return x0 + (x1 - x0) * fr
        }
    }

    /** Echo with a darker feedback loop; [pingPong] alternates sides in stereo. */
    class Delay(r: Float, ch: Int, ms: Float, private val fb: Float, private val mix: Float, lpHz: Float, private val pingPong: Boolean = false) : Stage() {
        private val d = r * ms / 1000f
        private val lines = Array(ch) { Line((d + 8).toInt()) }
        private val lpA = exp(-2f * PI.toFloat() * lpHz / r)
        private val lp = FloatArray(ch)
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, lines.size)
            for (f in 0 until frames) {
                val outs = FloatArray(cc)
                for (c in 0 until cc) outs[c] = lines[c].read(d)
                for (c in 0 until cc) {
                    val i = f * ch + c
                    lp[c] = outs[c] + (lp[c] - outs[c]) * lpA
                    val back = if (pingPong && cc == 2) lp[1 - c] else lp[c]
                    val inp = if (pingPong && cc == 2) (if (c == 0) (b[f * ch] + b[f * ch + 1]) * 0.5f else 0f) else b[i]
                    lines[c].push(inp + back * fb)
                    b[i] = b[i] + outs[c] * mix
                }
            }
        }
    }

    /** Short feedback comb: metal and robots. */
    class Comb(r: Float, ch: Int, ms: Float, private val fb: Float, private val mix: Float) : Stage() {
        private val d = r * ms / 1000f
        private val lines = Array(ch) { Line((d + 4).toInt()) }
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, lines.size)
            for (f in 0 until frames) for (c in 0 until cc) {
                val i = f * ch + c
                val y = b[i] + lines[c].read(d) * fb
                lines[c].push(y)
                b[i] = b[i] * (1 - mix) + y * mix * (1 - fb * 0.5f)
            }
        }
    }

    /**
     * Modulated delay: chorus, flanger, vibrato, tape wow. Base [baseMs], sweep ±[depthMs] at [rateHz]; [rate2]/[depth2]
     * add a second faster flutter (ratio of depth). [wetOnly] = vibrato (only the moved copy is heard).
     */
    class ModDelay(
        private val r: Float, ch: Int, baseMs: Float, depthMs: Float, private val rateHz: Float, private val fb: Float,
        private val wetOnly: Boolean = false, private val mix: Float = 0.5f, private val stereo: Boolean = false,
        private val rate2: Float = 0f, private val depth2: Float = 0f
    ) : Stage() {
        private val base = r * baseMs / 1000f
        private val depth = r * depthMs / 1000f
        private val lines = Array(ch) { Line((base + depth * (1 + depth2) + 8).toInt()) }
        private var ph = 0.0
        private var ph2 = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, lines.size)
            val dp = 2 * PI * rateHz / r
            val dp2 = 2 * PI * rate2 / r
            for (f in 0 until frames) {
                ph += dp; ph2 += dp2
                if (ph > 2 * PI) ph -= 2 * PI
                if (ph2 > 2 * PI) ph2 -= 2 * PI
                for (c in 0 until cc) {
                    val off = if (stereo && c == 1) PI / 2 else 0.0
                    val m = sin(ph + off).toFloat() + depth2 * sin(ph2).toFloat()
                    val d = base + depth * m
                    val i = f * ch + c
                    val y = lines[c].read(max(1f, d))
                    lines[c].push(b[i] + y * fb)
                    b[i] = if (wetOnly) y else b[i] * (1 - mix) + y * mix
                }
            }
        }
    }

    /** Freeverb-style reverb (8 damped combs + 4 allpasses per channel, Jezar's public-domain design), with pre-delay. */
    class Reverb(
        r: Float, ch: Int, room: Float, damp: Float, private val wet: Float, private val dry: Float,
        preMs: Float = 0f, private val width: Float = 1f
    ) : Stage() {
        private val k = r / 44100f
        private val combLen = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val apLen = intArrayOf(556, 441, 341, 225)
        private val fb = 0.7f + 0.28f * room.coerceIn(0f, 1f)
        private val dmp = damp.coerceIn(0f, 1f) * 0.4f
        private val combs = Array(ch) { c -> Array(8) { FloatArray(max(8, ((combLen[it] + 23 * c) * k).toInt())) } }
        private val combPos = Array(ch) { IntArray(8) }
        private val combLp = Array(ch) { FloatArray(8) }
        private val aps = Array(ch) { c -> Array(4) { FloatArray(max(8, ((apLen[it] + 23 * c) * k).toInt())) } }
        private val apPos = Array(ch) { IntArray(4) }
        private val pre = r * preMs / 1000f
        private val preLines = Array(ch) { Line((pre + 4).toInt()) }

        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, combs.size)
            val outs = FloatArray(cc)
            for (f in 0 until frames) {
                var mono = 0f
                for (c in 0 until cc) mono += b[f * ch + c]
                mono /= cc
                for (c in 0 until cc) {
                    var inp = mono * 0.015f * 2f
                    if (pre >= 1f) {
                        preLines[c].push(inp)
                        inp = preLines[c].read(pre)
                    }
                    var o = 0f
                    for (j in 0 until 8) {
                        val buf = combs[c][j]
                        val p = combPos[c][j]
                        val y = buf[p]
                        combLp[c][j] = y * (1 - dmp) + combLp[c][j] * dmp
                        buf[p] = inp + combLp[c][j] * fb
                        combPos[c][j] = (p + 1) % buf.size
                        o += y
                    }
                    for (j in 0 until 4) {
                        val buf = aps[c][j]
                        val p = apPos[c][j]
                        val bo = buf[p]
                        buf[p] = o + bo * 0.5f
                        apPos[c][j] = (p + 1) % buf.size
                        o = bo - o
                    }
                    outs[c] = o
                }
                for (c in 0 until cc) {
                    val other = if (cc == 2) outs[1 - c] else outs[c]
                    val w1 = wet * (width / 2 + 0.5f)
                    val w2 = wet * ((1 - width) / 2)
                    val i = f * ch + c
                    b[i] = b[i] * dry + (outs[c] * w1 + other * w2) * 3f
                }
            }
        }
    }

    /** Six first-order allpasses swept by an LFO, with feedback. */
    class Phaser(private val r: Float, ch: Int, private val rate: Float, private val fMin: Float, private val fMax: Float, private val fb: Float) : Stage() {
        private val st = Array(ch) { FloatArray(6) }
        private val last = FloatArray(ch)
        private var ph = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, st.size)
            for (f in 0 until frames) {
                ph += 2 * PI * rate / r
                val m = (sin(ph) * 0.5 + 0.5).toFloat()
                val fc = fMin * (fMax / fMin).pow(m)
                val t = kotlin.math.tan(PI * fc / r).toFloat()
                val a = (t - 1) / (t + 1)
                for (c in 0 until cc) {
                    val i = f * ch + c
                    var x = b[i] + last[c] * fb
                    for (j in 0 until 6) {
                        val y = a * x + st[c][j]
                        st[c][j] = x - a * y
                        x = y
                    }
                    last[c] = x
                    b[i] = (b[i] + x) * 0.5f
                }
            }
        }
    }

    /** Volume LFO. */
    class Trem(private val r: Float, private val rate: Float, private val depth: Float) : Stage() {
        private var ph = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (f in 0 until frames) {
                ph += 2 * PI * rate / r
                val g = 1f - depth * (0.5f + 0.5f * sin(ph).toFloat())
                for (c in 0 until ch) b[f * ch + c] *= g
            }
        }
    }

    /** Ring modulation by a sine of [freq] (± [sweep] relative drift), mixed by [mix]. */
    class Ring(private val r: Float, private val freq: Float, private val mix: Float, private val sweep: Float = 0f) : Stage() {
        private var ph = 0.0
        private var lfo = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            for (f in 0 until frames) {
                lfo += 2 * PI * 0.3 / r
                ph += 2 * PI * freq * (1 + sweep * sin(lfo)) / r
                val m = sin(ph).toFloat()
                for (c in 0 until ch) {
                    val i = f * ch + c
                    b[i] = b[i] * (1 - mix) + b[i] * m * mix
                }
            }
        }
    }

    /** Auto-wah: state-variable band-pass swept by an LFO. */
    class Wah(private val r: Float, ch: Int, private val rate: Float, private val fMin: Float, private val fMax: Float, q: Float) : Stage() {
        private val damp = 1f / q
        private val low = FloatArray(ch)
        private val band = FloatArray(ch)
        private var ph = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, low.size)
            for (f in 0 until frames) {
                ph += 2 * PI * rate / r
                val m = (sin(ph) * 0.5 + 0.5).toFloat()
                val fc = fMin * (fMax / fMin).pow(m)
                val g = (2 * sin(PI * min(fc, r / 6) / r)).toFloat()
                for (c in 0 until cc) {
                    val i = f * ch + c
                    val hi = b[i] - low[c] - damp * band[c]
                    band[c] += g * hi
                    low[c] += g * band[c]
                    b[i] = b[i] * 0.3f + band[c] * 1.6f
                }
            }
        }
    }

    /** Equal-power auto-pan around the head (stereo only). */
    class Pan(private val r: Float, private val rate: Float) : Stage() {
        private var ph = 0.0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            if (ch != 2) return
            for (f in 0 until frames) {
                ph += 2 * PI * rate / r
                val p = (sin(ph) * 0.5 + 0.5) * PI / 2
                val m = (b[f * 2] + b[f * 2 + 1]) * 0.5f
                b[f * 2] = m * cos(p).toFloat() * 1.41f
                b[f * 2 + 1] = m * sin(p).toFloat() * 1.41f
            }
        }
    }

    /**
     * Pitch shift by [semis] semitones without changing the speed: two read heads move through a delay line at the
     * new rate and cross-fade (triangle windows) — the classic delay-line shifter.
     */
    class Pitch(r: Float, ch: Int, semis: Float, grainMs: Float = 60f) : Stage() {
        private val ratio = 2f.pow(semis / 12f)
        private val win = r * grainMs / 1000f
        private val lines = Array(ch) { Line((win * 2 + 16).toInt()) }
        private var ph = 0f
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, lines.size)
            val step = (1f - ratio) / win
            for (f in 0 until frames) {
                ph += step
                ph -= floor(ph)
                val p2 = (ph + 0.5f) - floor(ph + 0.5f)
                // sin² windows half a grain apart sum to exactly 1 and fade smoother than triangles
                val s1 = sin(PI.toFloat() * ph)
                val g1 = s1 * s1
                val g2 = 1f - g1
                for (c in 0 until cc) {
                    val i = f * ch + c
                    lines[c].push(b[i])
                    b[i] = lines[c].read(1f + ph * win) * g1 + lines[c].read(1f + p2 * win) * g2
                }
            }
        }
    }

    /** Glitch: now and then a short slice repeats a few times. */
    class Stutter(private val r: Float, ch: Int, seed: Long) : Stage() {
        private val rnd = Rnd(seed + 11)
        private val size = (r * 0.25f).toInt()
        private val mem = Array(ch) { FloatArray(size) }
        private var w = 0
        private var slice = 0
        private var reps = 0
        private var rp = 0
        private var start = 0
        override fun run(b: FloatArray, frames: Int, ch: Int) {
            val cc = min(ch, mem.size)
            for (f in 0 until frames) {
                for (c in 0 until cc) mem[c][w] = b[f * ch + c]
                if (reps <= 0 && rnd.next() < 1.2f / r) {
                    slice = (r * (0.04f + rnd.next() * 0.12f)).toInt().coerceAtMost(size - 1)
                    reps = 2 + (rnd.next() * 4).toInt()
                    start = Math.floorMod(w - slice, size)
                    rp = 0
                }
                if (reps > 0) {
                    val idx = (start + rp) % size
                    val edge = min(rp, slice - rp).coerceAtMost(64) / 64f
                    for (c in 0 until cc) b[f * ch + c] = mem[c][idx] * edge
                    if (++rp >= slice) {
                        rp = 0
                        reps--
                    }
                } else w = (w + 1) % size
            }
        }
    }

    // ---------- helpers for tests ----------

    /** RMS of a float signal. */
    fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Float {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / max(1, to - from)).toFloat()
    }

    /** A test sine of [hz] Hz, [secs] seconds at [rate], mono, amplitude [amp]. */
    fun sine(hz: Float, secs: Float, rate: Int, amp: Float = 0.5f): FloatArray =
        FloatArray((secs * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
}

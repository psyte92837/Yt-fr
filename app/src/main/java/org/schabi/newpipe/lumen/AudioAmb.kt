/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 100 ambience sounds made from math (no files, no licences): water, rain and storms, wind and weather, nature,
 * fire, city, home, people, sci-fi and mood beds. Each one is a recipe of noise beds (white/pink/brown through
 * filters, with slow swells and gusts) and random events (drops, crackles, chirps, steps, thunder…), wide stereo
 * from independent left/right noise. Beds play for any length; [Sound.oneShotMs] marks single events (a knock, a
 * passing car). Output is interleaved stereo, levelled: beds ≈ −23 dBFS RMS, single events peak −3 dBFS. Pure.
 */
object AudioAmb {
    class Sound(val id: String, val group: String, val emoji: String, val ru: String, val en: String, val oneShotMs: Long = 0) {
        val name: String get() = t(ru, en)
    }

    class Group(val id: String, val ru: String, val en: String) {
        val name: String get() = t(ru, en)
    }

    val GROUPS = listOf(
        Group("water", "🌊 Вода", "🌊 Water"), Group("rain", "⛈ Дождь и гроза", "⛈ Rain and storm"),
        Group("weather", "🌫 Ветер и погода", "🌫 Wind and weather"), Group("nature", "🌲 Природа", "🌲 Nature"),
        Group("fire", "🔥 Огонь", "🔥 Fire"), Group("city", "🏙 Город", "🏙 City"), Group("home", "🏠 Дом", "🏠 Home"),
        Group("people", "👥 Люди", "👥 People"), Group("scifi", "🚀 Фантастика", "🚀 Sci-fi"), Group("mood", "🎹 Настроение", "🎹 Mood")
    )

    private fun s(id: String, g: String, e: String, ru: String, en: String, one: Long = 0) = Sound(id, g, e, ru, en, one)

    val SOUNDS: List<Sound> = listOf(
        s("ocean", "water", "🌊", "Океан", "Ocean waves"), s("sea_calm", "water", "🏖", "Тихое море", "Calm sea"),
        s("surf", "water", "🪨", "Прибой о камни", "Surf on rocks"), s("seagulls", "water", "🐦", "Море и чайки", "Sea and gulls"),
        s("river", "water", "🏞", "Река", "River"), s("stream", "water", "💧", "Ручей", "Brook"),
        s("waterfall", "water", "🌁", "Водопад", "Waterfall"), s("lake", "water", "🛶", "Плеск у берега", "Lake shore"),
        s("underwater", "water", "🤿", "Под водой", "Underwater"), s("cave_drips", "water", "🕳", "Капель в пещере", "Cave drips"),

        s("drizzle", "rain", "🌦", "Морось", "Drizzle"), s("rain_light", "rain", "🌧", "Тихий дождь", "Light rain"),
        s("rain", "rain", "☔", "Дождь", "Rain"), s("rain_heavy", "rain", "🌧", "Ливень", "Downpour"),
        s("rain_roof", "rain", "🏚", "Дождь по крыше", "Rain on a roof"), s("rain_window", "rain", "🪟", "Дождь по стеклу", "Rain on a window"),
        s("rain_umbrella", "rain", "🌂", "Дождь по зонту", "Rain on an umbrella"), s("thunder", "rain", "🌩", "Раскат грома", "Thunder clap", 7000),
        s("thunderstorm", "rain", "⛈", "Гроза", "Thunderstorm"), s("hail", "rain", "🧊", "Град", "Hail"),

        s("wind_light", "weather", "🍃", "Лёгкий ветер", "Light breeze"), s("wind", "weather", "🌬", "Ветер", "Wind"),
        s("wind_howl", "weather", "🐺", "Вой ветра", "Howling wind"), s("hurricane", "weather", "🌀", "Ураган", "Hurricane"),
        s("blizzard", "weather", "🌨", "Метель", "Blizzard"), s("fog", "weather", "🌫", "Туман", "Fog"),
        s("snow_steps", "weather", "❄", "Шаги по снегу", "Steps on snow"), s("mountain", "weather", "🏔", "Горы", "Mountain top"),
        s("desert", "weather", "🏜", "Пустыня", "Desert wind"), s("arctic", "weather", "🧊", "Ледяная тишина", "Arctic ice"),

        s("forest_day", "nature", "🌳", "Лес днём", "Forest by day"), s("forest_night", "nature", "🌙", "Ночной лес", "Forest at night"),
        s("birds", "nature", "🐤", "Утренние птицы", "Morning birds"), s("crickets", "nature", "🦗", "Сверчки", "Crickets"),
        s("cicadas", "nature", "☀", "Цикады", "Cicadas"), s("frogs", "nature", "🐸", "Лягушки", "Frogs"),
        s("owl", "nature", "🦉", "Сова", "Owl"), s("jungle", "nature", "🦜", "Джунгли", "Jungle"),
        s("meadow", "nature", "🌼", "Луг и пчёлы", "Meadow and bees"), s("leaves", "nature", "🍂", "Шелест листвы", "Rustling leaves"),

        s("campfire", "fire", "🏕", "Костёр", "Campfire"), s("fireplace", "fire", "🪵", "Камин", "Fireplace"),
        s("bonfire", "fire", "🔥", "Большой костёр", "Bonfire"), s("crackle", "fire", "✨", "Треск дров", "Wood crackle"),
        s("torch", "fire", "🔦", "Факел", "Torch"), s("blaze", "fire", "🚒", "Пожар", "Blaze"),
        s("fireworks", "fire", "🎆", "Салют", "Fireworks"), s("sparkler", "fire", "🎇", "Бенгальский огонь", "Sparkler"),
        s("match", "fire", "🔥", "Спичка", "Match strike", 2500), s("embers", "fire", "🌋", "Тлеющие угли", "Embers"),

        s("street", "city", "🏙", "Улица", "City street"), s("traffic", "city", "🚗", "Пробка", "Traffic"),
        s("city_night", "city", "🌃", "Ночной город", "City at night"), s("car_pass", "city", "🚙", "Проезжает машина", "Car passing", 6000),
        s("horns", "city", "📯", "Гудки", "Car horns"), s("siren", "city", "🚨", "Сирена", "Siren", 8000),
        s("construction", "city", "🏗", "Стройка", "Construction"), s("subway", "city", "🚇", "Метро", "Subway"),
        s("train", "city", "🚆", "Поезд", "Train"), s("airplane", "city", "✈", "Самолёт", "Airplane", 12000),

        s("clock_room", "home", "🕰", "Часы в тишине", "Clock in a quiet room"), s("fridge", "home", "🧊", "Холодильник", "Fridge hum"),
        s("fan", "home", "🌀", "Вентилятор", "Fan"), s("kettle", "home", "🫖", "Чайник", "Kettle", 9000),
        s("tap", "home", "🚰", "Вода из крана", "Running tap"), s("shower", "home", "🚿", "Душ", "Shower"),
        s("frying", "home", "🍳", "Жарка", "Frying pan"), s("boiling", "home", "🍲", "Кипит кастрюля", "Boiling pot"),
        s("knock", "home", "🚪", "Стук в дверь", "Door knock", 2500), s("creak", "home", "🏚", "Скрип двери", "Door creak", 2500),

        s("crowd", "people", "👥", "Толпа", "Crowd"), s("cafe", "people", "☕", "Кафе", "Café"),
        s("office", "people", "🏢", "Офис", "Office"), s("applause", "people", "👏", "Аплодисменты", "Applause", 6000),
        s("cheer", "people", "🏟", "Ликование стадиона", "Stadium cheer", 6000), s("footsteps", "people", "👣", "Шаги", "Footsteps"),
        s("heartbeat", "people", "❤", "Сердцебиение", "Heartbeat"), s("breathing", "people", "😮‍💨", "Дыхание", "Breathing"),
        s("library", "people", "📚", "Библиотека", "Library"), s("typing", "people", "⌨", "Печать на клавиатуре", "Typing"),

        s("spaceship", "scifi", "🚀", "Космический корабль", "Spaceship"), s("engine_room", "scifi", "⚙", "Машинное отделение", "Engine room"),
        s("computer", "scifi", "💻", "Компьютер", "Computer room"), s("radar", "scifi", "📡", "Радар", "Radar"),
        s("sonar", "scifi", "🛰", "Сонар", "Sonar"), s("alarm", "scifi", "🚨", "Тревога", "Alarm"),
        s("teleport", "scifi", "🌀", "Телепорт", "Teleport", 3000), s("force_field", "scifi", "🛡", "Силовое поле", "Force field"),
        s("alien_world", "scifi", "👽", "Чужая планета", "Alien world"), s("black_hole", "scifi", "🕳", "Чёрная дыра", "Black hole"),

        s("dark_drone", "mood", "🌑", "Тёмный гул", "Dark drone"), s("calm_pad", "mood", "🌅", "Спокойный фон", "Calm pad"),
        s("singing_bowl", "mood", "🔔", "Поющая чаша", "Singing bowl"), s("om", "mood", "🧘", "Ом", "Om drone"),
        s("tension", "mood", "😰", "Напряжение", "Tension", 10_000), s("dream_pad", "mood", "☁", "Сон", "Dream pad"),
        s("chimes", "mood", "🎐", "Колокольчики ветра", "Wind chimes"), s("music_box", "mood", "🎶", "Музыкальная шкатулка", "Music box"),
        s("binaural", "mood", "🎧", "Бинауральный ритм", "Binaural beat"), s("sleep_noise", "mood", "😴", "Шум для сна", "Sleep noise")
    )

    fun find(id: String): Sound? = SOUNDS.firstOrNull { it.id == id }

    fun of(group: String): List<Sound> = SOUNDS.filter { it.group == group }

    /** The sound [id] for [ms] (single events: their own length) at [rate], interleaved stereo; null for an unknown id. */
    fun render(id: String, rate: Int, ms: Long, seed: Long = 1L, edges: Boolean = true): FloatArray? {
        val snd = find(id) ?: return null
        val len = if (snd.oneShotMs > 0) snd.oneShotMs else ms.coerceIn(500L, 120_000L)
        val g = G(rate, (len * rate / 1000).toInt().coerceAtLeast(1), seed * 7919 + id.hashCode())
        recipe(id, g)
        return g.finish(oneShot = snd.oneShotMs > 0, edges = edges || snd.oneShotMs > 0)
    }

    /** Length of a piece of [id] asked for [ms]: single events keep their own. */
    fun lengthOf(id: String, ms: Long): Long = find(id)?.oneShotMs?.takeIf { it > 0 } ?: ms

    /** Long beds are made in pieces of this length, overlapped by [JOIN_MS] and crossfaded. */
    const val CHUNK_MS = 20_000L
    const val JOIN_MS = 1_000L

    // ---------- the toolkit ----------

    /** Biquad (RBJ) over a mono array. */
    private class Bq(type: Char, f: Float, q: Float, rate: Int, db: Float = 0f) {
        private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        init {
            val w = 2 * PI * f.coerceIn(10f, rate * 0.45f) / rate
            val al = sin(w) / (2 * q)
            val cw = cos(w)
            val a = 10.0.pow(db / 40.0)
            val (nb0, nb1, nb2, na0, na1, na2) = when (type) {
                'h' -> listOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
                'b' -> listOf(al, 0.0, -al, 1 + al, -2 * cw, 1 - al)
                'p' -> listOf(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
                else -> listOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al)
            }
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        }
        fun run(x: Float): Float {
            var y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (abs(y) < 1e-20f) y = 0f
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun all(a: FloatArray): FloatArray { for (i in a.indices) a[i] = run(a[i]); return a }
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    /** State-variable filter with a cutoff that can move every sample. */
    private class Svf(private val rate: Int) {
        private var lo = 0f; private var bd = 0f
        fun run(x: Float, f: Float, q: Float, mode: Char): Float {
            val k = (2 * sin(PI * f.coerceIn(20f, rate * 0.2f) / rate)).toFloat()
            val damp = 1f / q
            lo += k * bd
            val hi = x - lo - damp * bd
            bd += k * hi
            if (abs(lo) > 1e6f || lo.isNaN()) { lo = 0f; bd = 0f }
            return when (mode) { 'b' -> bd; 'h' -> hi; else -> lo }
        }
    }

    /** One generator: stereo buffers, random numbers and the pieces recipes are made of. */
    private class G(val r: Int, val n: Int, seed: Long) {
        val L = FloatArray(n)
        val R = FloatArray(n)
        val rnd = java.util.Random(seed)
        val secs: Double get() = n.toDouble() / r

        fun u() = rnd.nextFloat()
        fun bi() = rnd.nextFloat() * 2f - 1f
        fun t(i: Int) = i.toDouble() / r

        /** Noise: 'w' white, 'p' pink, 'b' brown, 'v' violet-ish (white high-passed). */
        fun noise(color: Char, len: Int = n): FloatArray {
            val o = FloatArray(len)
            var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var br = 0f
            for (i in 0 until len) {
                val w = bi()
                o[i] = when (color) {
                    'p' -> {
                        b0 = 0.99886f * b0 + w * 0.0555179f; b1 = 0.99332f * b1 + w * 0.0750759f; b2 = 0.969f * b2 + w * 0.153852f
                        b3 = 0.8665f * b3 + w * 0.3104856f; b4 = 0.55f * b4 + w * 0.5329522f; b5 = -0.7616f * b5 - w * 0.016898f
                        (b0 + b1 + b2 + b3 + b4 + b5 + w * 0.5362f) * 0.11f
                    }
                    'b' -> { br = (br + w * 0.02f) / 1.02f; br * 3.5f }
                    else -> w
                }
            }
            if (color == 'v') Bq('h', 4000f, 0.7f, r).all(o)
            return o
        }

        /** Slow wandering value 0..1: a sum of sines at random phases ([hz] the fastest). */
        fun drift(hz: Double): (Double) -> Float {
            val ph = DoubleArray(4) { rnd.nextDouble() * 2 * PI }
            val fr = doubleArrayOf(hz, hz * 0.61, hz * 0.37, hz * 0.23)
            return { t -> (0.5 + 0.5 * (0 until 4).sumOf { sin(2 * PI * fr[it] * t + ph[it]) / 4 * 1.6 }).toFloat().coerceIn(0f, 1f) }
        }

        /**
         * A noise bed into both channels (independent noise left and right, [width] 0 = mono … 1 = fully wide),
         * shaped by [shape] (filters) and the gain envelope [env] over seconds.
         */
        fun bed(color: Char, gain: Float, width: Float = 1f, env: (Double) -> Float = { 1f }, shape: (FloatArray) -> FloatArray = { it }) {
            val a = shape(noise(color))
            val b = shape(noise(color))
            val wk = width.coerceIn(0f, 1f)
            for (i in 0 until n) {
                val e = env(t(i)) * gain
                val m = (a[i] + b[i]) * 0.5f
                L[i] += (m + (a[i] - m) * wk) * e
                R[i] += (m + (b[i] - m) * wk) * e
            }
        }

        /** Noise through a moving band-pass: [freq] and gain [env] over seconds (wind, waves, engines). */
        fun sweep(color: Char, gain: Float, q: Float, mode: Char = 'b', width: Float = 1f, freq: (Double) -> Float, env: (Double) -> Float = { 1f }) {
            val a = noise(color)
            val b = noise(color)
            val fa = Svf(r)
            val fb = Svf(r)
            for (i in 0 until n) {
                val tt = t(i)
                val f = freq(tt)
                val e = env(tt) * gain
                val x = fa.run(a[i], f, q, mode)
                val y = fb.run(b[i], f * (1f + 0.04f * width), q, mode)
                val m = (x + y) * 0.5f
                L[i] += (m + (x - m) * width) * e
                R[i] += (m + (y - m) * width) * e
            }
        }

        /** Random events, [perSec] on average (a Poisson process), from [from] seconds. */
        fun events(perSec: Double, from: Double = 0.0, until: Double = secs, f: (Int) -> Unit) {
            if (perSec <= 0) return
            var tt = from + -ln(1 - rnd.nextDouble()) / perSec
            while (tt < until) {
                f((tt * r).toInt())
                tt += -ln(1 - rnd.nextDouble()) / perSec
            }
        }

        /** Regular events every [every] s with [jitter] s of randomness. */
        fun beats(every: Double, jitter: Double = 0.0, from: Double = 0.0, f: (Int, Int) -> Unit) {
            var tt = from
            var k = 0
            while (tt < secs) {
                f(((tt + (rnd.nextDouble() * 2 - 1) * jitter).coerceAtLeast(0.0) * r).toInt(), k++)
                tt += every
            }
        }

        /** A mono sound [x] at sample [at], panned −1..1 (equal power). */
        fun put(at: Int, x: FloatArray, gain: Float, pan: Float = 0f) {
            val a = (pan.coerceIn(-1f, 1f) + 1) * PI.toFloat() / 4
            val gl = cos(a) * 1.414f * gain
            val gr = sin(a) * 1.414f * gain
            for (k in x.indices) {
                val i = at + k
                if (i < 0) continue
                if (i >= n) break
                L[i] += x[k] * gl
                R[i] += x[k] * gr
            }
        }

        /** A tone into both channels: frequency and amplitude over seconds, [wave] s/q/w/t, [detune] Hz on the right. */
        fun tone(freq: (Double) -> Double, amp: (Double) -> Double, wave: Char = 's', detune: Double = 0.0) {
            var pl = rnd.nextDouble()
            var pr = rnd.nextDouble()
            for (i in 0 until n) {
                val tt = t(i)
                val f = freq(tt)
                pl += f / r
                pr += (f + detune) / r
                pl -= kotlin.math.floor(pl)
                pr -= kotlin.math.floor(pr)
                val a = amp(tt).toFloat()
                L[i] += osc(wave, pl) * a
                R[i] += osc(wave, pr) * a
            }
        }

        fun osc(w: Char, ph: Double): Float = when (w) {
            'q' -> if (ph < 0.5) 0.7f else -0.7f
            'w' -> (2 * ph - 1).toFloat() * 0.8f
            't' -> (1 - 4 * abs(ph - 0.5)).toFloat()
            else -> sin(2 * PI * ph).toFloat()
        }

        // ---- short sounds (mono) ----

        fun len(sec: Double) = max(1, (sec * r).toInt())

        /** A water drop: a fast rising blip. */
        fun drop(f0: Float = 500f + u() * 900f, decay: Float = 70f): FloatArray {
            var ph = 0.0
            return FloatArray(len(0.06)) { k ->
                val tt = k.toDouble() / r
                val f = f0 * (1 + 1.6 * (1 - exp(-tt * 60)))
                ph += 2 * PI * f / r
                (sin(ph) * exp(-tt * decay) * min(1.0, tt * 3000)).toFloat()
            }
        }

        /** A bubble: a rising sine that fades. */
        fun bubble(f0: Float = 300f + u() * 500f): FloatArray {
            var ph = 0.0
            return FloatArray(len(0.08)) { k ->
                val tt = k.toDouble() / r
                ph += 2 * PI * f0 * (1 + tt * 6) / r
                (sin(ph) * exp(-tt * 45) * min(1.0, tt * 800)).toFloat()
            }
        }

        /** A short noise click (crackle, tick, raindrop on a hard surface); [tone] filters it. */
        fun click(sec: Double = 0.004, tone: Float = 3000f, q: Float = 0.8f): FloatArray {
            val bq = Bq('b', tone, q, r)
            return FloatArray(len(sec)) { k -> bq.run(bi()) * exp(-k.toDouble() / len(sec) * 5).toFloat() * 3f }
        }

        /** A low thump (step, knock, heartbeat). */
        fun thump(f: Float = 80f, sec: Double = 0.18, noise: Float = 0.3f): FloatArray {
            var ph = 0.0
            val lp = Bq('l', 900f, 0.7f, r)
            return FloatArray(len(sec)) { k ->
                val tt = k.toDouble() / r
                ph += 2 * PI * f * (1 + 0.6 * exp(-tt * 40)) / r
                ((sin(ph) + lp.run(bi()) * noise * exp(-tt * 60)) * exp(-tt / sec * 5) * min(1.0, tt * 2000)).toFloat()
            }
        }

        /** A bird chirp: a gliding whistle with a little trill. */
        fun chirp(f0: Float, f1: Float, sec: Double, trill: Float = 0f): FloatArray {
            var ph = 0.0
            val m = len(sec)
            return FloatArray(m) { k ->
                val x = k.toDouble() / m
                val f = f0 + (f1 - f0) * x + trill * sin(2 * PI * 35 * k / r)
                ph += 2 * PI * f / r
                (sin(ph) * sin(PI * x).pow(0.7)).toFloat()
            }
        }

        /** A little song: 2–7 chirps of one bird. */
        fun song(base: Float): FloatArray {
            val notes = 2 + rnd.nextInt(6)
            val parts = ArrayList<FloatArray>()
            for (k in 0 until notes) {
                val f0 = base * (0.8f + u() * 0.5f)
                parts += chirp(f0, f0 * (0.7f + u() * 0.8f), 0.05 + u() * 0.12, if (u() < 0.3f) 300f else 0f)
                parts += FloatArray(len(0.03 + u() * 0.08))
            }
            val out = FloatArray(parts.sumOf { it.size })
            var p = 0
            for (x in parts) { x.copyInto(out, p); p += x.size }
            return out
        }

        /** A bell or chime: inharmonic partials with long decays. */
        fun bell(f: Float, sec: Double = 2.5, bright: Float = 1f): FloatArray {
            val ratios = floatArrayOf(1f, 2.76f, 5.4f, 8.93f)
            val amps = floatArrayOf(1f, 0.5f * bright, 0.25f * bright, 0.12f * bright)
            val dec = floatArrayOf(1f, 1.6f, 2.6f, 4f)
            return FloatArray(len(sec)) { k ->
                val tt = k.toDouble() / r
                var v = 0.0
                for (j in 0 until 4) v += amps[j] * sin(2 * PI * f * ratios[j] * tt) * exp(-tt * dec[j] * 3 / sec)
                (v * min(1.0, tt * 1500) * 0.5).toFloat()
            }
        }

        /** A thunder clap: a crack, then a long rolling rumble. */
        fun thunder(sec: Double = 5.0): FloatArray {
            val m = len(sec)
            val lp = Bq('l', 220f, 0.7f, r)
            val lp2 = Bq('l', 1800f, 0.7f, r)
            val bumps = DoubleArray(6) { 0.3 + rnd.nextDouble() * sec * 0.6 }
            return FloatArray(m) { k ->
                val tt = k.toDouble() / r
                val crack = lp2.run(bi()) * exp(-tt * 9) * 0.8
                var e = exp(-tt * 0.9) * min(1.0, tt * 6)
                for (b in bumps) e += 0.5 * exp(-abs(tt - b) * 4)
                ((lp.run(bi()) * 6 * e) + crack).toFloat() * min(1.0, (sec - tt) * 2).toFloat()
            }
        }

        /** A rough creak: a jittery low buzz through a resonance. */
        fun creak(sec: Double = 1.2, f: Float = 90f): FloatArray {
            val bp = Bq('b', 900f, 3f, r)
            val bp2 = Bq('b', 2200f, 4f, r)
            var ph = 0.0
            val m = len(sec)
            return FloatArray(m) { k ->
                val x = k.toDouble() / m
                ph += (f * (1 + 0.6 * sin(x * 7) + 0.3 * rnd.nextGaussian() * 0.2)) / r
                val pulse = if (ph >= 1) { ph -= 1.0; 1f } else 0f
                ((bp.run(pulse) * 3 + bp2.run(pulse) * 2) * sin(PI * x).pow(0.5)).toFloat()
            }
        }

        /** A beep of [f] Hz. */
        fun beep(f: Float, sec: Double, wave: Char = 's'): FloatArray {
            val m = len(sec)
            return FloatArray(m) { k -> osc(wave, (k * f.toDouble() / r) % 1.0) * min(1f, min(k, m - k) / (r * 0.004f)) }
        }

        /** A burst of formant-shaped noise: a far voice in a crowd. */
        fun murmur(sec: Double): FloatArray {
            val f1 = 400f + u() * 500f
            val f2 = 1100f + u() * 900f
            val b1 = Bq('b', f1, 4f, r)
            val b2 = Bq('b', f2, 5f, r)
            val m = len(sec)
            val syl = 3.0 + rnd.nextDouble() * 3
            val f0 = 100f + u() * 140f
            var ph = 0.0
            return FloatArray(m) { k ->
                val tt = k.toDouble() / r
                ph += f0 * (1 + 0.1 * sin(tt * 3)) / r
                val src = (if (ph >= 1) { ph -= 1.0; 1f } else 0f) + bi() * 0.15f
                val env = max(0.0, sin(2 * PI * syl * tt)).pow(0.6) * sin(PI * k / m)
                ((b1.run(src) + b2.run(src) * 0.6f) * env * 6).toFloat()
            }
        }

        /** Transparent under 0.6, never above 0.95. */
        private fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.6f) return x
            val y = 0.6f + 0.35f * kotlin.math.tanh((a - 0.6f) / 0.35f)
            return if (x < 0) -y else y
        }

        fun finish(oneShot: Boolean, edges: Boolean = true): FloatArray {
            // level: beds by loudness, single events by peak
            var e = 0.0
            var pk = 0f
            for (i in 0 until n) {
                e += (L[i] * L[i] + R[i] * R[i]) / 2.0
                pk = max(pk, max(abs(L[i]), abs(R[i])))
            }
            val rms = sqrt(e / n).toFloat()
            // beds by loudness; sharp crackles may be squeezed by the soft limiter below by up to ~7 dB
            var g = if (rms < 1e-7f) 0.7f / max(pk, 1e-6f) else if (oneShot) min(0.7f / max(pk, 1e-6f), 0.14f / rms) else 0.07f / rms
            if (pk * g > 2f) g = 2f / max(pk, 1e-6f)
            val fade = if (!edges) 0 else min(n / 4, (r * if (oneShot) 0.02 else 0.4).toInt())
            val out = FloatArray(n * 2)
            for (i in 0 until n) {
                var k = g
                if (fade > 0) {
                    if (i < fade) k *= i.toFloat() / fade
                    if (n - 1 - i < fade) k *= (n - 1 - i).toFloat() / fade
                }
                out[2 * i] = limit(L[i] * k)
                out[2 * i + 1] = limit(R[i] * k)
            }
            return out
        }
    }

    // ---------- the recipes ----------

    private fun lp(g: G, f: Float) = { a: FloatArray -> Bq('l', f, 0.7f, g.r).all(a) }
    private fun hp(g: G, f: Float) = { a: FloatArray -> Bq('h', f, 0.7f, g.r).all(a) }
    private fun bp(g: G, f: Float, q: Float = 1f) = { a: FloatArray -> Bq('b', f, q, g.r).all(a) }
    private fun band(g: G, lo: Float, hi: Float) = { a: FloatArray -> Bq('l', hi, 0.7f, g.r).all(Bq('h', lo, 0.7f, g.r).all(a)) }

    /** Wave swells: 0..1 rising slowly and falling back, every [period] s with randomness. */
    private fun swells(g: G, period: Double): (Double) -> Float {
        val starts = ArrayList<Double>()
        var t = -g.rnd.nextDouble() * period
        while (t < g.secs + period) { starts += t; t += period * (0.75 + g.rnd.nextDouble() * 0.5) }
        val amps = starts.map { 0.6 + g.rnd.nextDouble() * 0.4 }
        return { x ->
            var v = 0.0
            starts.forEachIndexed { i, s ->
                val d = x - s
                if (d > 0 && d < period * 1.6) v += amps[i] * (if (d < period * 0.45) (d / (period * 0.45)).pow(2.0) else exp(-(d - period * 0.45) * 2.2 / period))
            }
            (0.15 + v).toFloat().coerceAtMost(1.3f)
        }
    }

    private fun rainBed(g: G, density: Double, hard: Float, soft: Float, tone: Float = 5000f) {
        g.bed('p', soft, env = { 1f }, shape = band(g, 400f, tone))
        g.events(density) { at -> g.put(at, g.click(0.003 + g.u() * 0.004, 2000f + g.u() * 5000f, 0.6f), hard * (0.2f + g.u()), g.bi()) }
    }

    private fun windBed(g: G, gain: Float, lo: Float, hi: Float, gusts: Double, q: Float = 1.2f) {
        val d = g.drift(gusts)
        val d2 = g.drift(gusts * 1.7)
        g.sweep('p', gain, q, freq = { t -> lo + (hi - lo) * d(t) }, env = { t -> 0.3f + 0.9f * d2(t) })
    }

    private fun fireBed(g: G, roar: Float, crackles: Double, pops: Double) {
        val d = g.drift(0.4)
        g.bed('b', roar, env = { t -> 0.6f + 0.5f * d(t) }, shape = lp(g, 900f))
        g.events(crackles) { at -> g.put(at, g.click(0.0015 + g.u() * 0.003, 1500f + g.u() * 4000f, 0.7f), 0.3f + g.u() * 0.7f, g.bi() * 0.7f) }
        g.events(pops) { at -> g.put(at, g.click(0.01 + g.u() * 0.02, 600f + g.u() * 1500f, 1.2f), 0.7f + g.u() * 0.6f, g.bi() * 0.5f) }
    }

    private fun birds(g: G, perSec: Double, kinds: Int, gain: Float) {
        val bases = FloatArray(kinds) { 2200f + g.u() * 3200f }
        g.events(perSec) { at -> g.put(at, g.song(bases[g.rnd.nextInt(kinds)]), gain * (0.3f + g.u() * 0.7f), g.bi() * 0.9f) }
    }

    private fun crickets(g: G, f: Float, rate: Double, gain: Float, voices: Int = 6) {
        for (v in 0 until voices) {
            val ff = f * (0.95f + g.u() * 0.1f)
            val pan = g.bi()
            val gg = gain * (0.3f + g.u() * 0.7f)
            g.beats(1.0 / rate * (0.9 + g.rnd.nextDouble() * 0.2), 0.02, g.rnd.nextDouble()) { at, _ ->
                if (g.u() < 0.85f) for (p in 0 until 3) g.put(at + p * g.len(0.022), g.beep(ff, 0.014), gg, pan)
            }
        }
    }

    private fun drone(g: G, root: Double, gain: Float, wave: Char = 's', spread: Double = 0.4) {
        listOf(1.0, 1.5, 2.0).forEachIndexed { i, k ->
            val d = g.drift(0.05 + i * 0.03)
            g.tone({ root * k }, { t -> gain / (i + 1) * (0.6 + 0.4 * d(t)) }, wave, detune = spread * (i + 1))
        }
    }

    private fun pad(g: G, notes: List<Double>, gain: Float) {
        notes.forEachIndexed { i, f ->
            val d = g.drift(0.08 + i * 0.02)
            g.tone({ f }, { t -> gain * (0.4 + 0.6 * d(t)) }, 't', detune = 0.6 + i * 0.3)
        }
    }

    private fun traffic(g: G, cars: Double, gain: Float) {
        g.bed('b', gain, shape = lp(g, 500f))
        g.events(cars) { at ->
            val dur = 3.0 + g.rnd.nextDouble() * 3
            val m = g.len(dur)
            val pan0 = if (g.u() < 0.5f) -1f else 1f
            val noise = g.noise('p', m)
            val bq = Bq('l', 700f + g.u() * 800f, 0.7f, g.r)
            val x = FloatArray(m) { k -> val p = k.toFloat() / m; bq.run(noise[k]) * (1f - abs(2 * p - 1)).pow(2f) * 3f }
            // a pass: louder in the middle, moving across
            val half = m / 2
            g.put(at, x.copyOfRange(0, half), 0.6f + g.u() * 0.6f, pan0 * 0.7f)
            g.put(at + half, x.copyOfRange(half, m), 0.6f + g.u() * 0.6f, -pan0 * 0.7f)
        }
    }

    private fun crowd(g: G, voices: Double, gain: Float, bright: Float = 1f) {
        g.bed('p', gain * 0.5f, shape = band(g, 250f, 2500f * bright))
        g.events(voices) { at -> g.put(at, g.murmur(0.6 + g.rnd.nextDouble() * 1.8), gain * (0.3f + g.u() * 0.6f), g.bi() * 0.9f) }
    }

    private fun recipe(id: String, g: G) {
        when (id) {
            // ---- water ----
            "ocean" -> {
                val sw = swells(g, 9.0)
                g.sweep('p', 1f, 0.7f, 'l', freq = { t -> 300f + 1400f * (sw(t) / 1.3f) }, env = { t -> sw(t) })
                g.bed('b', 0.5f, env = { t -> 0.5f + 0.5f * sw(t) }, shape = lp(g, 250f))
            }
            "sea_calm" -> {
                val sw = swells(g, 6.0)
                g.sweep('p', 0.6f, 0.7f, 'l', freq = { t -> 400f + 900f * sw(t) }, env = { t -> 0.3f + 0.5f * sw(t) })
                g.events(3.0) { at -> g.put(at, g.drop(800f + g.u() * 800f, 120f), 0.08f, g.bi()) }
            }
            "surf" -> {
                val sw = swells(g, 7.0)
                g.sweep('w', 0.8f, 0.6f, 'l', freq = { t -> 800f + 4000f * (sw(t) / 1.3f).pow(2f) }, env = { t -> sw(t).pow(1.5f) })
                g.bed('b', 0.4f, env = sw, shape = lp(g, 200f))
                g.events(6.0) { at -> g.put(at, g.click(0.02, 3000f, 0.5f), 0.15f * g.u(), g.bi()) }
            }
            "seagulls" -> {
                val sw = swells(g, 8.0)
                g.sweep('p', 0.7f, 0.7f, 'l', freq = { t -> 300f + 1200f * sw(t) }, env = sw)
                g.events(0.35) { at ->
                    var p = at
                    repeat(1 + g.rnd.nextInt(3)) {
                        val c = g.chirp(1500f + g.u() * 400f, 800f + g.u() * 200f, 0.35 + g.rnd.nextDouble() * 0.2, 60f)
                        g.put(p, c, 0.35f, g.bi())
                        p += g.len(0.45)
                    }
                }
            }
            "river" -> {
                g.bed('p', 1f, shape = band(g, 200f, 3000f))
                val d = g.drift(0.3)
                g.sweep('w', 0.3f, 2f, freq = { t -> 900f + 900f * d(t) })
                g.events(25.0) { at -> g.put(at, g.bubble(250f + g.u() * 500f), 0.1f, g.bi()) }
            }
            "stream" -> {
                g.bed('p', 0.4f, shape = band(g, 500f, 6000f))
                g.events(40.0) { at -> g.put(at, g.drop(700f + g.u() * 1400f, 90f), 0.2f + g.u() * 0.2f, g.bi()) }
                g.events(15.0) { at -> g.put(at, g.bubble(400f + g.u() * 600f), 0.15f, g.bi()) }
            }
            "waterfall" -> {
                g.bed('w', 0.6f, shape = band(g, 150f, 7000f))
                g.bed('b', 1f, shape = lp(g, 300f))
            }
            "lake" -> {
                val sw = swells(g, 3.5)
                g.sweep('p', 0.5f, 1.5f, freq = { t -> 500f + 700f * sw(t) }, env = { t -> sw(t).pow(2f) })
                g.events(2.5) { at -> g.put(at, g.drop(400f + g.u() * 600f, 50f), 0.4f, g.bi() * 0.6f) }
                g.bed('p', 0.05f, shape = band(g, 2000f, 6000f))
            }
            "underwater" -> {
                g.bed('b', 1f, shape = lp(g, 350f))
                val d = g.drift(0.15)
                g.tone({ 55.0 }, { t -> 0.05 * d(t) })
                g.events(4.0) { at -> repeat(1 + g.rnd.nextInt(5)) { k -> g.put(at + k * g.len(0.05), g.bubble(200f + g.u() * 500f), 0.3f, g.bi() * 0.5f) } }
            }
            "cave_drips" -> {
                g.bed('b', 0.2f, shape = lp(g, 200f))
                val echo = { x: FloatArray -> FloatArray(x.size + g.len(0.9)) { k -> (if (k < x.size) x[k] else 0f) + (if (k >= g.len(0.13) && k - g.len(0.13) < x.size) x[k - g.len(0.13)] * 0.4f else 0f) + (if (k >= g.len(0.31) && k - g.len(0.31) < x.size) x[k - g.len(0.31)] * 0.2f else 0f) } }
                g.events(1.2) { at -> g.put(at, echo(g.drop(900f + g.u() * 900f, 40f)), 0.8f, g.bi() * 0.8f) }
            }
            // ---- rain ----
            "drizzle" -> rainBed(g, 120.0, 0.06f, 0.35f, 7000f)
            "rain_light" -> rainBed(g, 250.0, 0.1f, 0.6f)
            "rain" -> { rainBed(g, 700.0, 0.12f, 1f); g.bed('b', 0.3f, shape = lp(g, 300f)) }
            "rain_heavy" -> { rainBed(g, 2000.0, 0.12f, 1.6f, 7000f); g.bed('b', 0.8f, shape = lp(g, 400f)) }
            "rain_roof" -> {
                g.bed('p', 0.5f, shape = band(g, 300f, 3000f))
                g.events(300.0) { at -> g.put(at, g.click(0.006, 900f + g.u() * 1500f, 2f), 0.2f + g.u() * 0.3f, g.bi()) }
                g.events(0.8) { at -> repeat(4) { k -> g.put(at + k * g.len(0.18 + g.rnd.nextDouble() * 0.1), g.thump(200f + g.u() * 200f, 0.05, 0.6f), 0.15f, g.bi() * 0.3f) } }
            }
            "rain_window" -> {
                g.bed('p', 0.4f, shape = band(g, 600f, 5000f))
                g.events(150.0) { at -> g.put(at, g.click(0.002, 4000f + g.u() * 3000f, 1f), 0.15f + g.u() * 0.3f, g.bi()) }
                g.events(1.5) { at -> g.put(at, g.drop(1500f + g.u() * 1000f, 80f), 0.15f, g.bi()) }
            }
            "rain_umbrella" -> {
                g.bed('p', 0.3f, shape = band(g, 300f, 4000f))
                g.events(250.0) { at -> g.put(at, g.click(0.008, 1200f + g.u() * 900f, 3f), 0.3f + g.u() * 0.3f, g.bi() * 0.3f) }
            }
            "thunder" -> g.put(0, g.thunder(6.5), 1f, g.bi() * 0.3f)
            "thunderstorm" -> {
                rainBed(g, 1500.0, 0.1f, 1.3f, 6000f)
                g.bed('b', 0.6f, shape = lp(g, 300f))
                val every = max(6.0, g.secs / 3)
                g.beats(every, every * 0.3, from = 1.5) { at, _ -> g.put(at, g.thunder(4.0 + g.rnd.nextDouble() * 3), 5f, g.bi() * 0.6f) }
            }
            "hail" -> {
                g.bed('p', 0.3f, shape = band(g, 500f, 6000f))
                g.events(250.0) { at -> g.put(at, g.click(0.003, 2500f + g.u() * 4000f, 3f), 0.4f + g.u() * 0.6f, g.bi()) }
                g.events(40.0) { at -> g.put(at, g.thump(500f + g.u() * 700f, 0.03, 1f), 0.2f, g.bi()) }
            }
            // ---- weather ----
            "wind_light" -> { windBed(g, 0.5f, 300f, 900f, 0.12); g.bed('p', 0.15f, shape = band(g, 2000f, 7000f), env = { 0.5f }) }
            "wind" -> windBed(g, 1f, 250f, 1400f, 0.18)
            "wind_howl" -> {
                windBed(g, 0.7f, 200f, 900f, 0.15)
                val d = g.drift(0.12)
                g.sweep('w', 0.25f, 18f, freq = { t -> 350f + 450f * d(t) }, env = { t -> d(t).pow(2f) })
            }
            "hurricane" -> {
                windBed(g, 1.3f, 300f, 2200f, 0.35, 0.8f)
                g.bed('b', 1f, shape = lp(g, 250f))
                g.events(2.0) { at -> g.put(at, g.click(0.05, 1500f, 0.5f), 0.3f, g.bi()) }
            }
            "blizzard" -> {
                windBed(g, 1f, 600f, 3000f, 0.3, 0.9f)
                g.bed('v', 0.25f, env = { 0.8f })
                windBed(g, 0.3f, 250f, 600f, 0.2, 4f)
            }
            "fog" -> {
                g.bed('b', 0.6f, shape = lp(g, 400f))
                val d = g.drift(0.06)
                g.bed('p', 0.12f, env = { t -> 0.4f + 0.6f * d(t) }, shape = band(g, 300f, 1500f))
                // a distant foghorn
                val every = max(12.0, g.secs / 2)
                g.beats(every, 2.0, from = 3.0) { at, _ ->
                    val m = g.len(3.0)
                    val lp = Bq('l', 400f, 0.7f, g.r)
                    var ph = 0.0
                    val horn = FloatArray(m) { k ->
                        val tt = k.toDouble() / g.r
                        ph += 110.0 / g.r
                        lp.run(g.osc('w', ph % 1.0)) * (min(1.0, tt * 2) * min(1.0, (3.0 - tt) * 1.5)).toFloat()
                    }
                    g.put(at, horn, 0.5f, g.bi() * 0.4f)
                }
            }
            "snow_steps" -> {
                g.bed('p', 0.1f, shape = band(g, 300f, 1500f))
                g.beats(0.55, 0.03) { at, k ->
                    val m = g.len(0.25)
                    val bp = Bq('b', 1400f, 0.8f, g.r)
                    val crunch = FloatArray(m) { j -> val x = j.toFloat() / m; bp.run(g.bi() * (if (g.u() < 0.3f) 1f else 0.2f)) * sin(PI.toFloat() * x).pow(0.5f) * 3f }
                    g.put(at, crunch, 0.7f, if (k % 2 == 0) -0.2f else 0.2f)
                }
            }
            "mountain" -> {
                windBed(g, 0.8f, 150f, 700f, 0.08, 1.5f)
                g.events(0.08) { at -> g.put(at, g.chirp(1800f, 1200f, 0.5, 0f), 0.08f, g.bi()) }
            }
            "desert" -> {
                windBed(g, 0.7f, 200f, 800f, 0.1)
                val d = g.drift(0.2)
                g.bed('v', 0.25f, env = { t -> d(t).pow(2f) })
            }
            "arctic" -> {
                windBed(g, 0.35f, 150f, 500f, 0.06, 2f)
                g.events(0.25) { at ->
                    val m = g.len(0.6)
                    val bp = Bq('b', 2500f, 6f, g.r)
                    g.put(at, FloatArray(m) { k -> bp.run(if (g.u() < 0.05f) g.bi() * 3 else 0f) * exp(-k.toDouble() / m * 4).toFloat() * 4 }, 0.4f, g.bi())
                }
            }
            // ---- nature ----
            "forest_day" -> { windBed(g, 0.25f, 800f, 3000f, 0.15, 0.8f); birds(g, 0.9, 4, 0.35f) }
            "forest_night" -> {
                windBed(g, 0.15f, 300f, 900f, 0.1)
                crickets(g, 4300f, 1.6, 0.12f, 5)
                g.events(0.07) { at -> repeat(2) { k -> g.put(at + k * g.len(0.55), g.chirp(380f, 360f, 0.4), 0.35f, 0.6f) } }
            }
            "birds" -> { g.bed('p', 0.05f, shape = band(g, 500f, 4000f)); birds(g, 2.5, 6, 0.5f) }
            "crickets" -> { g.bed('p', 0.03f, shape = band(g, 400f, 2000f)); crickets(g, 4500f, 2.0, 0.2f, 10) }
            "cicadas" -> {
                val d = g.drift(0.15)
                g.sweep('w', 0.6f, 12f, freq = { 5500f }, env = { t -> 0.4f + 0.6f * d(t) })
                g.tone({ 5500.0 }, { t -> 0.03 * (0.5 + 0.5 * sin(2 * PI * 90 * t)) * d(t) }, 's', 7.0)
            }
            "frogs" -> {
                g.bed('p', 0.05f, shape = band(g, 300f, 2000f))
                crickets(g, 3800f, 1.3, 0.05f, 3)
                g.events(3.0) { at ->
                    val m = g.len(0.25 + g.rnd.nextDouble() * 0.2)
                    val bp = Bq('b', 450f + g.u() * 400f, 3f, g.r)
                    var ph = 0.0
                    val f = 25.0 + g.rnd.nextDouble() * 20
                    val croak = FloatArray(m) { k -> ph += f / g.r; val p = if (ph >= 1) { ph -= 1; 1f } else 0f; bp.run(p) * sin(PI.toFloat() * k / m) * 6 }
                    repeat(1 + g.rnd.nextInt(3)) { k -> g.put(at + k * (m + g.len(0.08)), croak, 0.6f, g.bi()) }
                }
            }
            "owl" -> {
                windBed(g, 0.12f, 300f, 800f, 0.08)
                crickets(g, 4200f, 1.5, 0.05f, 3)
                g.beats(max(4.0, g.secs / 3), 1.0, from = 1.0) { at, _ ->
                    val hoot = { sec: Double -> g.chirp(400f, 370f, sec).also { x -> for (k in x.indices) x[k] *= 0.8f + 0.2f * sin(2 * PI.toFloat() * 6 * k / g.r) } }
                    g.put(at, hoot(0.35), 0.5f, 0.4f)
                    g.put(at + g.len(0.6), hoot(0.15), 0.35f, 0.4f)
                    g.put(at + g.len(0.85), hoot(0.5), 0.45f, 0.4f)
                }
            }
            "jungle" -> {
                g.bed('p', 0.3f, shape = band(g, 1500f, 7000f))
                crickets(g, 5200f, 2.5, 0.1f, 6)
                birds(g, 2.0, 8, 0.4f)
                g.events(0.3) { at -> g.put(at, g.chirp(900f, 2200f, 0.25, 120f), 0.3f, g.bi()) }
            }
            "meadow" -> {
                windBed(g, 0.2f, 600f, 2500f, 0.12)
                birds(g, 0.6, 3, 0.25f)
                g.events(0.4) { at ->
                    val m = g.len(2.0 + g.rnd.nextDouble() * 2)
                    var ph = 0.0
                    val f = 200.0 + g.rnd.nextDouble() * 60
                    val bp = Bq('b', 900f, 1f, g.r)
                    val bee = FloatArray(m) { k -> ph += f * (1 + 0.03 * sin(k * 0.0007)) / g.r; bp.run(g.osc('w', ph % 1.0)) * sin(PI.toFloat() * k / m) }
                    g.put(at, bee, 0.25f, g.bi())
                }
            }
            "leaves" -> {
                val d = g.drift(0.2)
                g.sweep('w', 0.8f, 0.7f, 'h', freq = { t -> 1500f + 2500f * d(t) }, env = { t -> d(t).pow(1.5f) })
                g.events(30.0) { at -> g.put(at, g.click(0.01, 3000f + g.u() * 3000f, 1.5f), 0.1f * g.u(), g.bi()) }
            }
            // ---- fire ----
            "campfire" -> fireBed(g, 0.6f, 40.0, 1.5)
            "fireplace" -> fireBed(g, 0.4f, 25.0, 0.8)
            "bonfire" -> { fireBed(g, 1.2f, 90.0, 4.0); windBed(g, 0.3f, 200f, 600f, 0.2) }
            "crackle" -> fireBed(g, 0.1f, 70.0, 3.0)
            "torch" -> {
                val d = g.drift(0.6)
                g.bed('b', 1f, env = { t -> 0.7f + 0.4f * d(t) }, shape = lp(g, 600f))
                g.bed('p', 0.2f, env = { t -> 0.5f + 0.5f * d(t) }, shape = band(g, 800f, 3000f))
                g.events(6.0) { at -> g.put(at, g.click(0.002, 3000f, 0.7f), 0.2f, g.bi() * 0.3f) }
            }
            "blaze" -> {
                fireBed(g, 1.5f, 150.0, 8.0)
                windBed(g, 0.6f, 150f, 600f, 0.3)
                g.events(0.2) { at -> g.put(at, g.thump(60f, 0.6, 1f), 0.6f, g.bi()) }
            }
            "fireworks" -> {
                g.bed('b', 0.05f, shape = lp(g, 300f))
                g.events(0.6, from = 0.3) { at ->
                    val whistle = g.chirp(900f, 2400f, 1.0, 40f)
                    val pan = g.bi() * 0.8f
                    g.put(at, whistle, 0.12f, pan)
                    val bang = g.len(1.05)
                    g.put(at + bang, g.thump(70f, 0.8, 1.5f), 1f, pan)
                    g.events(60.0, from = (at + bang).toDouble() / g.r + 0.05, until = min(g.secs, (at + bang).toDouble() / g.r + 1.6)) { p ->
                        g.put(p, g.click(0.002, 4000f, 0.7f), 0.3f, pan + g.bi() * 0.2f)
                    }
                }
            }
            "sparkler" -> {
                g.bed('v', 0.3f)
                g.events(300.0) { at -> g.put(at, g.click(0.0012, 5000f + g.u() * 3000f, 0.7f), 0.2f + g.u() * 0.5f, g.bi() * 0.4f) }
            }
            "match" -> {
                val m = g.len(0.25)
                val bp = Bq('b', 2500f, 0.7f, g.r)
                g.put(g.len(0.2), FloatArray(m) { k -> bp.run(g.bi()) * (k.toFloat() / m) * 2 }, 0.6f)
                val flare = g.len(1.6)
                val lp = Bq('l', 1200f, 0.7f, g.r)
                g.put(g.len(0.45), FloatArray(flare) { k -> lp.run(g.bi()) * exp(-k.toDouble() / flare * 2.5).toFloat() * min(1f, k / (g.r * 0.03f)) * 3 }, 0.6f)
            }
            "embers" -> {
                g.bed('b', 0.25f, shape = lp(g, 400f))
                g.events(8.0) { at -> g.put(at, g.click(0.002, 2500f + g.u() * 2000f, 0.7f), 0.2f + g.u() * 0.3f, g.bi() * 0.6f) }
            }
            // ---- city ----
            "street" -> { traffic(g, 0.35, 0.6f); crowd(g, 1.0, 0.15f); g.events(0.05) { at -> g.put(at, g.beep(420f, 0.4, 'q'), 0.05f, g.bi()) } }
            "traffic" -> {
                traffic(g, 0.9, 1f)
                g.tone({ 42.0 }, { t -> 0.05 * (0.6 + 0.4 * sin(t * 0.4)) }, 'w', 0.5)
                g.events(0.12) { at -> g.put(at, g.beep(390f, 0.3 + g.rnd.nextDouble() * 0.4, 'q'), 0.08f, g.bi()) }
            }
            "city_night" -> {
                g.bed('b', 0.5f, shape = lp(g, 300f))
                traffic(g, 0.08, 0.2f)
                g.events(0.03) { at ->
                    val m = g.len(5.0)
                    var ph = 0.0
                    val siren = FloatArray(m) { k -> val tt = k.toDouble() / g.r; ph += (700 + 250 * sin(tt * 2.2)) / g.r; (sin(2 * PI * ph) * sin(PI * k / m)).toFloat() }
                    g.put(at, siren, 0.03f, g.bi())
                }
            }
            "car_pass" -> {
                val m = g.n
                val noise = g.noise('p', m)
                val lp = Bq('l', 1200f, 0.7f, g.r)
                var ph = 0.0
                for (k in 0 until m) {
                    val x = k.toDouble() / m
                    val near = exp(-((x - 0.5) * 6).pow(2.0))
                    val f = 70 * (if (x < 0.5) 1.08 else 0.92)
                    ph += f / g.r
                    val v = (lp.run(noise[k]) * 2 + g.osc('w', ph % 1.0) * 0.4f) * (0.08f + near.toFloat())
                    val pan = ((x - 0.5) * 2.2).toFloat().coerceIn(-1f, 1f)
                    g.L[k] += v * (1 - pan) * 0.7f
                    g.R[k] += v * (1 + pan) * 0.7f
                }
            }
            "horns" -> {
                traffic(g, 0.7, 0.6f)
                g.events(0.8) { at ->
                    val f = 330f + g.u() * 200f
                    val sec = 0.25 + g.rnd.nextDouble() * 0.7
                    val a = g.beep(f, sec, 'q')
                    val b = g.beep(f * 1.26f, sec, 'q')
                    val h = FloatArray(min(a.size, b.size)) { k -> (a[k] + b[k]) * 0.5f }
                    g.put(at, Bq('l', 2500f, 0.7f, g.r).all(h), 0.25f, g.bi())
                }
            }
            "siren" -> {
                val m = g.n
                var ph = 0.0
                for (k in 0 until m) {
                    val x = k.toDouble() / m
                    val tt = k.toDouble() / g.r
                    ph += (650 + 300 * sin(2 * PI * tt / 1.6).let { if (it > 0) 1.0 else -1.0 } * 0.5 + 150) / g.r
                    val v = (g.osc('q', ph % 1.0) * 0.3f + sin(2 * PI * ph).toFloat() * 0.5f) * exp(-((x - 0.5) * 3).pow(2.0)).toFloat()
                    val pan = ((x - 0.5) * 1.6).toFloat()
                    g.L[k] += v * (1 - pan) * 0.7f
                    g.R[k] += v * (1 + pan) * 0.7f
                }
                g.bed('b', 0.08f, shape = lp(g, 300f))
            }
            "construction" -> {
                g.bed('b', 0.4f, shape = lp(g, 400f))
                g.events(0.6) { at -> repeat(3 + g.rnd.nextInt(5)) { k -> g.put(at + k * g.len(0.45), g.bell(900f + g.u() * 300f, 0.3, 2f), 0.4f, -0.3f) } }
                g.events(0.15) { at ->
                    val m = g.len(1.5 + g.rnd.nextDouble() * 2)
                    var ph = 0.0
                    val bp = Bq('b', 1500f, 2f, g.r)
                    g.put(at, FloatArray(m) { k -> ph += 120.0 / g.r; bp.run(g.osc('w', ph % 1.0) + g.bi() * 0.3f) * sin(PI.toFloat() * k / m).pow(0.2f) }, 0.3f, 0.4f)
                }
            }
            "subway" -> {
                val d = g.drift(0.07)
                g.bed('b', 1f, env = { t -> 0.5f + 0.6f * d(t) }, shape = lp(g, 350f))
                g.sweep('p', 0.4f, 3f, freq = { t -> 400f + 900f * d(t) }, env = { t -> d(t) })
                g.beats(0.9, 0.0) { at, k -> g.put(at, g.thump(110f, 0.06, 0.8f), 0.35f, if (k % 2 == 0) -0.4f else 0.4f) }
            }
            "train" -> {
                g.bed('b', 0.8f, shape = lp(g, 400f))
                g.beats(1.1, 0.0) { at, _ ->
                    g.put(at, g.thump(150f, 0.07, 1f), 0.6f, -0.2f)
                    g.put(at + g.len(0.16), g.thump(140f, 0.07, 1f), 0.5f, 0.2f)
                }
                g.events(0.04) { at -> g.put(at, Bq('l', 1800f, 0.7f, g.r).all(FloatArray(g.len(1.5)).also { x -> val a = g.beep(370f, 1.5, 'q'); val b = g.beep(466f, 1.5, 'q'); for (k in x.indices) x[k] = a[k] + b[k] }), 0.08f, 0f) }
            }
            "airplane" -> {
                val m = g.n
                val a = g.noise('p', m)
                val b = g.noise('b', m)
                val fa = Svf(g.r)
                for (k in 0 until m) {
                    val x = k.toDouble() / m
                    val near = sin(PI * x).pow(2.0)
                    val v = fa.run(a[k], (500 + 1800 * near).toFloat(), 0.8f, 'l') * near.toFloat() + b[k] * 0.4f * near.toFloat()
                    val pan = ((x - 0.5) * 1.8).toFloat()
                    g.L[k] += v * (1 - pan) * 0.7f
                    g.R[k] += v * (1 + pan) * 0.7f
                }
            }
            // ---- home ----
            "clock_room" -> {
                g.bed('p', 0.008f, shape = band(g, 100f, 3000f))
                g.beats(0.5) { at, k -> g.put(at, g.click(0.006, if (k % 2 == 0) 3200f else 2400f, 4f), 1f, -0.3f) }
            }
            "fridge" -> {
                g.tone({ 50.0 }, { 0.12 }, 's', 0.2)
                g.tone({ 100.0 }, { 0.08 }, 's', 0.3)
                g.tone({ 150.0 }, { 0.03 }, 's')
                g.bed('p', 0.05f, shape = band(g, 200f, 1200f))
            }
            "fan" -> {
                g.bed('p', 0.8f, env = { t -> (0.85 + 0.15 * sin(2 * PI * 18 * t)).toFloat() }, shape = band(g, 150f, 2500f))
                g.tone({ 36.0 }, { 0.04 }, 's')
            }
            "kettle" -> {
                val m = g.n
                val a = g.noise('p', m)
                val f = Svf(g.r)
                for (k in 0 until m) {
                    val x = k.toDouble() / m
                    val boil = x.pow(1.5)
                    val v = f.run(a[k], (200 + 2500 * boil).toFloat(), 1.5f, 'b') * boil.toFloat() * 2
                    g.L[k] += v; g.R[k] += v
                }
                g.events(20.0, from = g.secs * 0.4) { at -> g.put(at, g.bubble(250f + g.u() * 400f), 0.3f, g.bi() * 0.3f) }
                g.tone({ t -> 1800.0 + 200 * sin(t * 3) }, { t -> if (t > g.secs * 0.75) 0.15 * min(1.0, (t - g.secs * 0.75) * 2) else 0.0 }, 's', 3.0)
            }
            "tap" -> {
                g.bed('w', 0.6f, shape = band(g, 600f, 5000f))
                g.bed('p', 0.4f, shape = bp(g, 1300f, 1.5f))
                g.events(60.0) { at -> g.put(at, g.bubble(500f + g.u() * 700f), 0.08f, g.bi() * 0.3f) }
            }
            "shower" -> {
                g.bed('w', 0.7f, shape = band(g, 800f, 9000f))
                g.events(400.0) { at -> g.put(at, g.click(0.003, 3000f + g.u() * 4000f, 1f), 0.15f, g.bi()) }
                g.bed('b', 0.3f, shape = lp(g, 300f))
            }
            "frying" -> {
                g.bed('v', 0.5f)
                g.events(600.0) { at -> g.put(at, g.click(0.002, 4000f + g.u() * 4000f, 0.8f), 0.3f * g.u(), g.bi() * 0.5f) }
                g.events(3.0) { at -> g.put(at, g.click(0.01, 1500f, 1f), 0.5f, g.bi() * 0.5f) }
            }
            "boiling" -> {
                g.bed('b', 0.3f, shape = lp(g, 300f))
                g.events(40.0) { at -> g.put(at, g.bubble(150f + g.u() * 350f), 0.4f + g.u() * 0.4f, g.bi() * 0.4f) }
            }
            "knock" -> repeat(3) { k -> g.put(g.len(0.3 + k * 0.28), g.thump(130f, 0.12, 0.8f), 1f, 0f) }
            "creak" -> g.put(g.len(0.2), g.creak(1.8, 70f), 1f, 0.2f)
            // ---- people ----
            "crowd" -> crowd(g, 14.0, 0.6f)
            "cafe" -> {
                crowd(g, 6.0, 0.5f)
                g.events(0.8) { at -> g.put(at, g.bell(2400f + g.u() * 1500f, 0.25, 0.6f), 0.15f, g.bi()) }
                g.events(0.1) { at -> g.put(at, FloatArray(g.len(2.0)).also { x -> val b = Bq('b', 1500f, 0.8f, g.r); for (k in x.indices) x[k] = b.run(g.bi()) * sin(PI.toFloat() * k / x.size) }, 0.2f, 0.6f) }
            }
            "office" -> {
                g.tone({ 120.0 }, { 0.01 })
                g.bed('p', 0.1f, shape = band(g, 150f, 1200f))
                crowd(g, 1.0, 0.25f)
                g.events(0.3) { at -> var p = at; repeat(10 + g.rnd.nextInt(30)) { g.put(p, g.click(0.008, 2500f + g.u() * 1500f, 2f), 0.4f, -0.5f); p += g.len(0.07 + g.rnd.nextDouble() * 0.15) } }
                g.events(0.03) { at -> repeat(2) { k -> g.put(at + k * g.len(1.2), FloatArray(g.len(0.8)).also { x -> val a = g.beep(440f, 0.8); val b = g.beep(480f, 0.8); for (j in x.indices) x[j] = (a[j] + b[j]) * (0.5f + 0.5f * sin(2 * PI.toFloat() * 20 * j / g.r)) }, 0.1f, 0.7f) } }
            }
            "applause" -> {
                val m = g.n
                g.events(220.0, until = g.secs * 0.85) { at ->
                    val c = g.click(0.012, 1200f + g.u() * 1500f, 1.2f)
                    val x = at.toDouble() / m
                    g.put(at, c, (0.3f + g.u() * 0.5f) * sin(PI * min(1.0, x / 0.85)).toFloat().coerceAtLeast(0.1f), g.bi())
                }
            }
            "cheer" -> {
                crowd(g, 40.0, 0.7f, 1.4f)
                g.events(120.0, until = g.secs * 0.85) { at -> g.put(at, g.click(0.012, 1500f, 1.2f), 0.4f * g.u(), g.bi()) }
                g.bed('p', 0.4f, env = { t -> sin(PI * min(1.0, t / g.secs)).toFloat() }, shape = band(g, 500f, 3500f))
            }
            "footsteps" -> {
                g.bed('p', 0.01f, shape = band(g, 100f, 2000f))
                val echo = { x: FloatArray -> FloatArray(x.size + g.len(0.3)) { k -> (if (k < x.size) x[k] else 0f) + (if (k >= g.len(0.08) && k - g.len(0.08) < x.size) x[k - g.len(0.08)] * 0.3f else 0f) } }
                g.beats(0.55, 0.02) { at, k -> g.put(at, echo(g.thump(90f + g.u() * 20f, 0.09, 1.5f)), 0.8f, if (k % 2 == 0) -0.15f else 0.15f) }
            }
            "heartbeat" -> g.beats(0.86) { at, _ ->
                g.put(at, g.thump(55f, 0.15, 0.2f), 1f)
                g.put(at + g.len(0.22), g.thump(48f, 0.12, 0.15f), 0.7f)
            }
            "breathing" -> g.beats(4.0) { at, _ ->
                val inh = g.len(1.4)
                val exh = g.len(1.9)
                val f1 = Bq('b', 1300f, 1.2f, g.r)
                val f2 = Bq('b', 800f, 1.2f, g.r)
                g.put(at, FloatArray(inh) { k -> f1.run(g.bi()) * sin(PI.toFloat() * k / inh).pow(1.5f) }, 0.5f)
                g.put(at + inh + g.len(0.2), FloatArray(exh) { k -> f2.run(g.bi()) * sin(PI.toFloat() * k / exh).pow(1.5f) }, 0.6f)
            }
            "library" -> {
                g.bed('p', 0.04f, shape = band(g, 100f, 1500f))
                g.tone({ 60.0 }, { 0.006 })
                g.events(0.15) { at -> val m = g.len(0.35); val h = Bq('h', 2000f, 0.7f, g.r); g.put(at, FloatArray(m) { k -> h.run(g.bi()) * sin(PI.toFloat() * k / m) }, 0.25f, g.bi()) }
                g.events(0.05) { at -> g.put(at, g.murmur(0.8), 0.05f, g.bi()) }
            }
            "typing" -> {
                g.bed('p', 0.01f, shape = band(g, 200f, 2000f))
                var tt = 0.2
                while (tt < g.secs) {
                    g.put((tt * g.r).toInt(), g.click(0.01, 2200f + g.u() * 1800f, 2.5f), 0.5f + g.u() * 0.5f, -0.1f + g.bi() * 0.1f)
                    tt += if (g.u() < 0.08f) 0.5 + g.rnd.nextDouble() else 0.07 + g.rnd.nextDouble() * 0.12
                }
            }
            // ---- sci-fi ----
            "spaceship" -> {
                drone(g, 55.0, 0.15f, 'w', 0.3)
                g.bed('b', 0.6f, shape = lp(g, 200f))
                g.bed('p', 0.05f, shape = band(g, 2000f, 5000f))
                g.events(0.4) { at -> g.put(at, g.beep(1200f + g.u() * 1200f, 0.06), 0.06f, g.bi()) }
            }
            "engine_room" -> {
                g.bed('b', 0.8f, shape = lp(g, 300f))
                g.beats(0.45) { at, _ -> g.put(at, g.thump(70f, 0.2, 1f), 0.5f) }
                g.tone({ 98.0 }, { 0.04 }, 'w', 0.8)
                g.events(0.2) { at -> g.put(at, FloatArray(g.len(0.7)).also { x -> val h = Bq('h', 3000f, 0.7f, g.r); for (k in x.indices) x[k] = h.run(g.bi()) * exp(-k.toDouble() / x.size * 3).toFloat() }, 0.3f, g.bi()) }
            }
            "computer" -> {
                g.bed('p', 0.15f, shape = band(g, 200f, 2500f))
                g.tone({ 60.0 }, { 0.02 })
                g.events(3.0) { at -> g.put(at, g.beep(600f + g.rnd.nextInt(12) * 150f, 0.04 + g.rnd.nextDouble() * 0.08, if (g.u() < 0.5f) 'q' else 's'), 0.1f, g.bi()) }
            }
            "radar" -> {
                g.bed('b', 0.2f, shape = lp(g, 300f))
                g.beats(2.0) { at, _ -> g.put(at, g.beep(1300f, 0.08).also { x -> for (k in x.indices) x[k] *= exp(-k.toDouble() / x.size * 2).toFloat() }, 0.5f) }
                g.events(1.0) { at -> g.put(at, g.beep(2200f, 0.03), 0.1f, g.bi()) }
            }
            "sonar" -> {
                g.bed('b', 0.3f, shape = lp(g, 250f))
                g.beats(4.0) { at, _ ->
                    g.put(at, g.bell(1500f, 3.0, 0.1f), 0.8f)
                    g.put(at + g.len(1.1), g.bell(1500f, 2.0, 0.1f), 0.2f, 0.4f)
                }
            }
            "alarm" -> {
                g.tone({ t -> if (t % 1.0 < 0.5) 880.0 else 660.0 }, { t -> if (t % 0.5 < 0.42) 0.15 else 0.0 }, 'q')
                g.bed('b', 0.2f, shape = lp(g, 300f))
            }
            "teleport" -> {
                g.tone({ t -> 200 * 2.0.pow(t * 1.6) }, { t -> 0.2 * sin(PI * t / g.secs) }, 't', 3.0)
                g.sweep('w', 0.5f, 6f, freq = { t -> (300 * 2.0.pow(t * 1.8)).toFloat() }, env = { t -> sin(PI * t / g.secs).toFloat() })
            }
            "force_field" -> {
                g.tone({ 110.0 }, { t -> 0.12 * (0.7 + 0.3 * sin(2 * PI * 7 * t)) }, 'w', 1.5)
                g.tone({ 220.5 }, { 0.05 }, 's', 2.0)
                g.bed('p', 0.1f, shape = band(g, 2000f, 6000f))
                g.events(1.5) { at -> g.put(at, g.click(0.03, 3000f, 0.5f), 0.4f, g.bi()) }
            }
            "alien_world" -> {
                windBed(g, 0.4f, 150f, 500f, 0.07, 2f)
                g.events(0.6) { at -> g.put(at, g.chirp(300f + g.u() * 500f, 900f + g.u() * 1500f, 0.4 + g.rnd.nextDouble() * 0.8, 200f), 0.15f, g.bi()) }
                drone(g, 41.2, 0.06f, 't', 0.7)
            }
            "black_hole" -> {
                drone(g, 30.0, 0.25f, 's', 0.2)
                val d = g.drift(0.04)
                g.sweep('b', 0.6f, 4f, 'b', freq = { t -> 60f + 300f * d(t) })
            }
            // ---- mood ----
            "dark_drone" -> { drone(g, 36.7, 0.2f, 'w', 0.4); g.bed('b', 0.3f, shape = lp(g, 200f)) }
            "calm_pad" -> pad(g, listOf(130.8, 196.0, 261.6, 329.6), 0.08f)
            "singing_bowl" -> g.beats(max(6.0, g.secs / 3), 0.0) { at, _ -> g.put(at, g.bell(262f, 9.0, 0.5f).also { x -> for (k in x.indices) x[k] *= 0.85f + 0.15f * sin(2 * PI.toFloat() * 4.5f * k / g.r) }, 0.6f) }
            "om" -> {
                g.tone({ 136.1 }, { t -> 0.2 * (0.7 + 0.3 * sin(2 * PI * 0.1 * t)) }, 'w', 0.4)
                g.bed('p', 0.05f, shape = bp(g, 600f, 3f))
            }
            "tension" -> {
                g.tone({ t -> 55.0 + 10 * t }, { t -> 0.1 + 0.15 * t / g.secs }, 'w', 0.7)
                g.tone({ t -> 880.0 + 30 * t }, { t -> 0.04 * t / g.secs }, 's', 3.0)
                g.bed('p', 0.3f, env = { t -> (t / g.secs).toFloat().pow(2f) }, shape = band(g, 1000f, 6000f))
                g.beats(0.8) { at, _ -> g.put(at, g.thump(55f, 0.2, 0.3f), 0.3f + 0.7f * (at.toFloat() / g.n)) }
            }
            "dream_pad" -> {
                pad(g, listOf(220.0, 277.2, 329.6, 415.3), 0.06f)
                g.events(0.6) { at -> g.put(at, g.bell(listOf(880f, 1108.7f, 1318.5f, 1661.2f)[g.rnd.nextInt(4)], 3.0, 0.3f), 0.15f, g.bi()) }
            }
            "chimes" -> {
                windBed(g, 0.15f, 400f, 1200f, 0.1)
                val notes = listOf(1046.5f, 1174.7f, 1318.5f, 1568f, 1760f, 2093f)
                g.events(2.0) { at -> g.put(at, g.bell(notes[g.rnd.nextInt(notes.size)], 3.0, 0.6f), 0.4f, g.bi() * 0.7f) }
            }
            "music_box" -> {
                val notes = listOf(523.3f, 587.3f, 659.3f, 784f, 880f, 1046.5f, 1174.7f)
                g.beats(0.42, 0.0) { at, k -> if (g.u() < 0.85f) g.put(at, g.bell(notes[(k * 3 + g.rnd.nextInt(3)) % notes.size] * 2, 1.4, 0.4f), 0.5f, g.bi() * 0.3f) }
            }
            "binaural" -> {
                g.tone({ 200.0 }, { 0.15 }, 's', 10.0)
                g.bed('p', 0.02f, shape = lp(g, 800f))
            }
            "sleep_noise" -> g.bed('p', 1f, width = 0.7f, shape = lp(g, 2500f))
            else -> g.bed('p', 0.3f)
        }
    }
}

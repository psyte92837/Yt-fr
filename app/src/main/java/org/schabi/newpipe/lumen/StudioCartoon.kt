/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/*
 * Lumen Studio, Cartoon task: an informational cartoon. Characters drawn in code (no assets) say the lines of the
 * script in their own voices; the speaker's mouth follows the syllables of the spoken word, everybody blinks,
 * breathes, gestures and shows the mood of the line, and the usual Studio infographics play next to them.
 */

/**
 * A character of a cartoon: [name] used in the script ("@Name: line"), [look] from [StudioToon.LOOKS] (the base
 * for the voice and the label), [voice]: "", "m", "f" or an Edge voice. Own characters carry [parts] (null = the
 * look's), [persona] for the script writer, [pitch] (null = by the look) and [rate] (added to the Studio rate),
 * or the user's picture in [image].
 */
data class SChar(
    val name: String,
    val look: String,
    val voice: String = "",
    val color: Int = 0,
    val parts: ToonParts? = null,
    val persona: String = "",
    val pitch: Int? = null,
    val rate: Int = 0,
    val image: ToonImage? = null
)

/** What a character does in one frame. */
data class ToonPose(
    /** How open the mouth is, 0..1 (0 = the mood's resting mouth). */
    val mouth: Float = 0f,
    /** Eyelids, 0 = open, 1 = closed. */
    val blink: Float = 0f,
    val mood: String = "calm",
    val talking: Boolean = false,
    /** Where the pupils look, -1 (left) .. 1 (right). */
    val look: Float = 0f,
    /** Direction towards the middle of the frame: 1 = right, -1 = left. */
    val side: Float = 1f,
    /** Time in ms for idle motion. */
    val tMs: Long = 0,
    val seed: Int = 0,
    /** Accent colour (robot lamp). */
    val accent: Int = 0xFF7C5CFF.toInt()
)

object StudioToon {
    val LOOKS = listOf(
        "fox", "cat", "bear", "robot", "owl", "kid", "prof", "blob",
        "dog", "rabbit", "panda", "penguin", "frog", "alien", "dino", "girl"
    )
    val MOODS = listOf("calm", "happy", "explain", "think", "surprise", "sad", "angry")
    const val MAX_CAST = 4
    /** Characters kept in the library. */
    const val MAX_LIB = 40

    fun emoji(look: String): String = when (look) {
        "fox" -> "🦊"
        "cat" -> "🐱"
        "bear" -> "🐻"
        "robot" -> "🤖"
        "owl" -> "🦉"
        "kid" -> "🧒"
        "prof" -> "🧑‍🏫"
        "dog" -> "🐶"
        "rabbit" -> "🐰"
        "panda" -> "🐼"
        "penguin" -> "🐧"
        "frog" -> "🐸"
        "alien" -> "👽"
        "dino" -> "🦖"
        "girl" -> "👧"
        else -> "🫧"
    }

    fun lookLabel(look: String): String = when (look) {
        "fox" -> t("Лиса", "Fox")
        "cat" -> t("Кот", "Cat")
        "bear" -> t("Медведь", "Bear")
        "robot" -> t("Робот", "Robot")
        "owl" -> t("Сова", "Owl")
        "kid" -> t("Ребёнок", "Kid")
        "prof" -> t("Профессор", "Professor")
        "dog" -> t("Пёс", "Dog")
        "rabbit" -> t("Кролик", "Bunny")
        "panda" -> t("Панда", "Panda")
        "penguin" -> t("Пингвин", "Penguin")
        "frog" -> t("Лягушка", "Frog")
        "alien" -> t("Пришелец", "Alien")
        "dino" -> t("Динозавр", "Dino")
        "girl" -> t("Девочка", "Girl")
        else -> t("Капля", "Blob")
    }

    fun moodLabel(m: String): String = when (m) {
        "happy" -> t("радость", "happy")
        "explain" -> t("объясняет", "explains")
        "think" -> t("думает", "thinks")
        "surprise" -> t("удивление", "surprise")
        "sad" -> t("грусть", "sad")
        "angry" -> t("злость", "angry")
        else -> t("спокойно", "calm")
    }

    fun defaultColor(look: String): Int = when (look) {
        "fox" -> 0xFFF28C38
        "cat" -> 0xFF9AA5B8
        "bear" -> 0xFFA9743F
        "robot" -> 0xFF7FA7C9
        "owl" -> 0xFF8E6BBF
        "kid" -> 0xFFF6C49B
        "prof" -> 0xFFE8B58C
        "dog" -> 0xFFD9A066
        "rabbit" -> 0xFFE9E4F0
        "panda" -> 0xFFF4F4F2
        "penguin" -> 0xFF2F3A50
        "frog" -> 0xFF6CC24A
        "alien" -> 0xFF8BE36B
        "dino" -> 0xFF5DBB8A
        "girl" -> 0xFFF6C49B
        else -> 0xFF4FC3A1
    }.toInt()

    fun colorOf(ch: SChar): Int = ch.parts?.main ?: if (ch.color != 0) ch.color else defaultColor(ch.look)

    /** Voice pitch of a character: its own or by its look. */
    fun pitchOf(ch: SChar): Int = ch.pitch ?: pitch(ch.look)

    /** Speech rate of a character: the Studio rate plus its own shift. */
    fun rateOf(ch: SChar, studioRate: Int): Int = (studioRate + ch.rate).coerceIn(-50, 100)

    /** "🦊 Лиса", "🎨 Свой (лиса)" or "🖼 Картинка": what the character is, for lists and prompts. */
    fun kindLabel(ch: SChar): String = when {
        ch.image != null -> "🖼 " + t("своя картинка", "own picture")
        ch.parts != null && ch.parts != StudioParts.preset(ch.look).copy(main = ch.parts.main) -> "🎨 " + t("свой", "custom") + " (" + lookLabel(ch.look).lowercase() + ")"
        else -> emoji(ch.look) + " " + lookLabel(ch.look)
    }

    /** "Male" or "Female": the voice a look gets when none is chosen. */
    fun gender(look: String): String = if (look in listOf("bear", "robot", "prof", "dog", "penguin", "frog", "alien", "dino")) "Male" else "Female"

    /** Voice pitch shift in Hz, so characters sharing a voice still sound different. */
    fun pitch(look: String): Int = when (look) {
        "kid" -> 35
        "cat" -> 20
        "fox" -> 10
        "blob" -> 15
        "owl" -> 0
        "prof" -> -8
        "robot" -> -15
        "bear" -> -25
        "girl" -> 40
        "rabbit" -> 25
        "alien" -> 20
        "penguin" -> 10
        "panda" -> 5
        "dog" -> 0
        "frog" -> -5
        "dino" -> -20
        else -> 0
    }

    fun defaultCast(): List<SChar> = listOf(
        SChar(t("Лиса", "Foxy"), "fox", "f"),
        SChar(t("Робот", "Robo"), "robot", "m")
    )

    // ---------- cast: JSON in the settings ----------

    fun castJson(cast: List<SChar>): String = JSONArray().apply { cast.forEach { put(charJson(it)) } }.toString()

    fun charJson(ch: SChar): JSONObject = JSONObject().put("name", ch.name).put("look", ch.look).put("voice", ch.voice).put("color", ch.color).apply {
        ch.parts?.let { put("parts", StudioParts.toJson(it)) }
        if (ch.persona.isNotBlank()) put("persona", ch.persona)
        ch.pitch?.let { put("pitch", it) }
        if (ch.rate != 0) put("rate", ch.rate)
        ch.image?.let { im ->
            put("image", JSONObject().put("path", im.path).put("lx", im.eyeLx.toDouble()).put("ly", im.eyeLy.toDouble())
                .put("rx", im.eyeRx.toDouble()).put("ry", im.eyeRy.toDouble()).put("mx", im.mouthX.toDouble()).put("my", im.mouthY.toDouble())
                .put("mw", im.mouthW.toDouble()).put("lid", im.lid).put("flip", im.flip))
        }
    }

    fun parseChar(o: JSONObject): SChar? {
        val name = cleanName(o.optString("name"))
        if (name.isEmpty()) return null
        val look = o.optString("look").takeIf { it in LOOKS } ?: "blob"
        fun f(im: JSONObject, k: String, d: Float) = im.optDouble(k, d.toDouble()).toFloat()
        val image = o.optJSONObject("image")?.let { im ->
            val path = im.optString("path")
            if (path.isBlank()) null
            else ToonImage(path, f(im, "lx", -1f), f(im, "ly", -1f), f(im, "rx", -1f), f(im, "ry", -1f),
                f(im, "mx", -1f), f(im, "my", -1f), f(im, "mw", 0.12f), im.optInt("lid", 0), im.optBoolean("flip", false))
        }
        return SChar(
            name, look, o.optString("voice").trim(), o.optInt("color", 0),
            o.optJSONObject("parts")?.let { StudioParts.fromJson(it, StudioParts.preset(look)) },
            o.optString("persona").take(300),
            if (o.has("pitch")) o.optInt("pitch").coerceIn(StudioCharProto.PITCH.first, StudioCharProto.PITCH.last) else null,
            o.optInt("rate", 0).coerceIn(StudioCharProto.RATE.first, StudioCharProto.RATE.last),
            image
        )
    }

    fun parseCast(s: String?, limit: Int = MAX_CAST): List<SChar> {
        if (s.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { parseChar(it) } }
            .distinctBy { it.name.lowercase() }.take(limit)
    }

    /** The library with [ch] saved: the same name is replaced, the newest first. */
    fun libraryWith(lib: List<SChar>, ch: SChar): List<SChar> =
        (listOf(ch) + lib.filter { !it.name.equals(ch.name, true) }).take(MAX_LIB)

    /** A name usable after "@" in the script: letters, digits, "_" and "-", up to 20 characters. */
    fun cleanName(s: String): String = s.trim().replace(Regex("\\s+"), "_").filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(20)

    /** Index of the character [who] (by name, or by look as "fox"), -1 = narrator. */
    fun find(cast: List<SChar>, who: String): Int {
        val w = who.trim().removePrefix("@").lowercase()
        if (w.isEmpty()) return -1
        cast.indexOfFirst { it.name.lowercase() == w }.takeIf { it >= 0 }?.let { return it }
        cast.indexOfFirst { cleanName(it.name).lowercase() == cleanName(w).lowercase() }.takeIf { it >= 0 }?.let { return it }
        return cast.indexOfFirst { it.look == w }
    }

    fun mood(m: String): String = m.trim().lowercase().takeIf { it in MOODS } ?: "calm"

    /** Lines in the format of the script editor: who speaks it and the cast, for the AI prompt. */
    fun promptCast(cast: List<SChar>): String = cast.joinToString("; ") { ch ->
        val what = if (ch.image != null) t("герой с картинки", "a character from a picture") else "${lookLabel(ch.look).lowercase()} ${emoji(ch.look)}"
        "${ch.name} — $what" + if (ch.persona.isNotBlank()) " (${t("характер", "persona")}: ${ch.persona.trim()})" else ""
    }

    // ---------- voices ----------

    /**
     * Edge voice of every character: the chosen one when it exists for [lang], otherwise the next unused voice
     * of the look's gender, so two characters get different voices while there are enough. [fallback] when none.
     */
    fun pickVoices(cast: List<SChar>, voices: List<TtsVoice>, lang: String, fallback: String): List<String> {
        val ofLang = voices.filter { it.locale.substringBefore('-').equals(lang, true) }
        val used = mutableListOf<String>()
        return cast.map { ch ->
            val exact = ofLang.firstOrNull { it.shortName == ch.voice }?.shortName
            val v = exact ?: run {
                val g = when (ch.voice) {
                    "m" -> "Male"
                    "f" -> "Female"
                    else -> gender(ch.look)
                }
                val pool = ofLang.filter { it.gender == g }.ifEmpty { ofLang }
                (pool.firstOrNull { it.shortName !in used } ?: pool.getOrNull(used.count { u -> pool.any { it.shortName == u } } % max(1, pool.size)))?.shortName
            } ?: fallback
            used += v
            v
        }
    }

    // ---------- animation ----------

    private const val VOWELS = "aeiouyаеёиоуыэюяіїєáéíóúàèìòùäöü"

    /** Syllables of a word (vowels), at least 1. */
    fun syllables(word: String): Int = max(1, word.lowercase().count { it in VOWELS })

    /**
     * How open the speaker's mouth is at [tMs]: inside a word it opens once per syllable,
     * in a short gap between words it stays a little open, in a pause it is closed.
     */
    fun mouth(words: List<SWord>, range: IntRange, tMs: Long): Float {
        for (k in range) {
            val w = words.getOrNull(k) ?: break
            if (tMs < w.startMs - 30) return 0f
            if (tMs <= w.endMs) {
                val dur = max(60L, w.endMs - w.startMs)
                val f = ((tMs - w.startMs).toFloat() / dur).coerceIn(0f, 1f)
                val ph = f * syllables(w.text)
                val x = ph - floor(ph)
                return (0.15f + 0.85f * sin(PI * x).toFloat()).coerceIn(0f, 1f)
            }
            val next = words.getOrNull(k + 1)
            if (next != null && k + 1 in range && tMs < next.startMs && next.startMs - w.endMs < 90) return 0.15f
        }
        return 0f
    }

    /** Eyelids at [tMs]: a 140 ms blink every 2.6–4.8 s, different for every [seed]. */
    fun blink(tMs: Long, seed: Int): Float {
        val period = 2600L + (abs(seed) * 1237L) % 2200L
        val ph = (tMs + abs(seed) * 577L) % period
        return if (ph < 140) 1f - abs(ph - 70f) / 70f else 0f
    }

    /** Centres of the characters across the frame (parts of the width); wide frames keep the middle for the graphics. */
    fun slots(n: Int, wide: Boolean): List<Float> = when {
        n <= 0 -> emptyList()
        wide -> when (n) {
            1 -> listOf(0.14f)
            2 -> listOf(0.13f, 0.87f)
            3 -> listOf(0.10f, 0.25f, 0.88f)
            else -> listOf(0.09f, 0.22f, 0.78f, 0.91f)
        }
        else -> when (n) {
            1 -> listOf(0.5f)
            2 -> listOf(0.27f, 0.73f)
            3 -> listOf(0.18f, 0.5f, 0.82f)
            else -> listOf(0.14f, 0.38f, 0.62f, 0.86f)
        }
    }

    // ---------- speakers through the AI director ----------

    /** Speaker (index into the cast, -1 = narrator) and mood of every word, from the scenes and their word ranges. */
    fun wordRoles(scenes: List<SScene>, ranges: List<IntRange>, wordCount: Int, cast: List<SChar>): List<Pair<Int, String>> {
        val out = MutableList(wordCount) { -1 to "calm" }
        scenes.forEachIndexed { i, sc ->
            val r = ranges.getOrNull(i) ?: return@forEachIndexed
            val role = find(cast, sc.who) to mood(sc.mood)
            for (k in r) if (k in 0 until wordCount) out[k] = role
        }
        return out
    }

    /**
     * After the AI director re-cut the scenes: every scene gets the speaker of its words back; a scene spanning
     * two speakers is split where the speaker changes (the second part keeps the visual, without cues and shapes).
     */
    fun restore(scenes: List<SScene>, ranges: List<IntRange>, roles: List<Pair<Int, String>>, cast: List<SChar>, words: List<SWord>): Pair<List<SScene>, List<IntRange>> {
        val outS = mutableListOf<SScene>()
        val outR = mutableListOf<IntRange>()
        scenes.forEachIndexed { i, sc ->
            val r = ranges.getOrNull(i) ?: return@forEachIndexed
            if (r.isEmpty()) {
                outS += sc
                outR += r
                return@forEachIndexed
            }
            var start = r.first
            var first = true
            for (k in r.first..r.last + 1) {
                val change = k > r.last || roles.getOrNull(k)?.first != roles.getOrNull(start)?.first
                if (!change) continue
                val role = roles.getOrNull(start) ?: (-1 to "calm")
                val part = start until k
                val say = part.mapNotNull { words.getOrNull(it)?.text }.joinToString(" ").ifBlank { sc.say }
                val who = cast.getOrNull(role.first)?.name ?: ""
                outS += if (first) sc.copy(say = if (part == r) sc.say else say, who = who, mood = role.second)
                else sc.copy(say = say, who = who, mood = role.second, cues = emptyList(), shapes = emptyList(), transition = "none")
                outR += part
                first = false
                start = k
            }
        }
        return outS to outR
    }
}

/** Pictures of own characters, decoded once (≤ 768 px) and kept while the app runs. */
object StudioToonImages {
    private val cache = LinkedHashMap<String, android.graphics.Bitmap?>()

    @Synchronized
    fun get(path: String): android.graphics.Bitmap? {
        if (cache.containsKey(path)) return cache[path]
        val bmp = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 768) sample *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        if (cache.size >= 8) cache.remove(cache.keys.first())
        cache[path] = bmp
        return bmp
    }

    /** Forget a picture after it changed on disk. */
    @Synchronized
    fun drop(path: String) {
        cache.remove(path)
    }
}

/** Draws a cartoon character from its parts with Canvas paths; coordinates are in parts of its height, feet at (0, 0). */
class StudioToonArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - k) + ((b shr s) and 0xFF) * k).toInt().coerceIn(0, 255) shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
    private fun light(c: Int, k: Float) = mix(c, 0xFFFFFFFF.toInt(), k)
    private fun dark(c: Int, k: Float) = mix(c, 0xFF000000.toInt(), k)

    private fun oval(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, alpha: Float = 1f) {
        fill.color = color
        fill.alpha = (255 * alpha).toInt()
        c.drawOval(RectF(l, t, r, b), fill)
    }

    private fun path(c: Canvas, color: Int, a: Float, build: Path.() -> Unit) {
        fill.color = color
        fill.alpha = (255 * a).toInt()
        c.drawPath(Path().apply(build), fill)
    }

    private fun stroke(width: Float, color: Int, alpha: Float = 1f) = line.apply {
        strokeWidth = width
        this.color = color
        this.alpha = (255 * alpha).toInt()
    }

    /** One character at feet point ([x], [y]) with height [size]; [alpha] for the entrance. */
    fun draw(c: Canvas, ch: SChar, x: Float, y: Float, size: Float, p: ToonPose, alpha: Float = 1f) {
        if (alpha <= 0f || size <= 0f) return
        val im = ch.image
        val bmp = im?.let { StudioToonImages.get(it.path) }
        if (im != null && bmp != null) drawImage(c, im, bmp, x, y, size, p, alpha)
        else drawParts(c, StudioParts.of(ch), x, y, size, p, alpha)
    }

    private fun drawParts(c: Canvas, pt: ToonParts, x: Float, y: Float, size: Float, p: ToonPose, a: Float) {
        val col = pt.main
        val t = p.tMs.toFloat()
        val breath = 0.012f * sin(t / 620f + p.seed)
        val hop = if (p.talking) 0.025f * p.mouth else 0f
        val people = pt.front == "clothes"
        c.save()
        c.translate(x, y)
        c.scale(size, size)
        // shadow on the ground
        oval(c, -0.30f * pt.width, -0.035f, 0.30f * pt.width, 0.035f, 0xFF000000.toInt(), 0.18f * a)
        c.scale(pt.width, pt.height)
        c.translate(0f, -hop)
        c.scale(1f - breath * 0.5f, 1f + breath + hop * 0.6f)
        // feet
        val feet = if (pt.feet != 0) pt.feet else dark(col, 0.25f)
        oval(c, -0.22f, -0.07f, -0.04f, 0.01f, feet, a)
        oval(c, 0.04f, -0.07f, 0.22f, 0.01f, feet, a)
        tail(c, pt, p, a)
        topBack(c, pt, p, a)
        // body
        fill.color = col
        fill.alpha = (255 * a).toInt()
        if (pt.body == "box") c.drawRoundRect(RectF(-0.31f, -0.88f, 0.31f, -0.05f), 0.1f, 0.1f, fill)
        else c.drawOval(RectF(-0.33f, -0.9f, 0.33f, -0.04f), fill)
        val bodyClip = Path().apply {
            if (pt.body == "box") addRoundRect(RectF(-0.31f, -0.88f, 0.31f, -0.05f), 0.1f, 0.1f, Path.Direction.CW)
            else addOval(RectF(-0.33f, -0.9f, 0.33f, -0.04f), Path.Direction.CW)
        }
        when (pt.front) {
            "clothes" -> {
                fill.color = pt.clothes
                fill.alpha = (255 * a).toInt()
                c.save()
                c.clipPath(bodyClip)
                c.drawRect(-0.4f, -0.3f, 0.4f, 0f, fill)
                c.restore()
            }
            "screen" -> {
                fill.color = 0xFF1E2430.toInt()
                fill.alpha = (255 * a).toInt()
                c.drawRoundRect(RectF(-0.24f, -0.76f, 0.24f, -0.34f), 0.07f, 0.07f, fill)
            }
            "penguin" -> {
                oval(c, -0.25f, -0.78f, 0.25f, -0.3f, 0xFFFFFFFF.toInt(), a)
                oval(c, -0.24f, -0.5f, 0.24f, -0.07f, 0xFFFFFFFF.toInt(), a)
            }
            "belly" -> oval(c, -0.19f, -0.42f, 0.19f, -0.08f, light(col, 0.45f), a)
            "pale" -> oval(c, -0.19f, -0.42f, 0.19f, -0.08f, light(col, 0.8f), a)
        }
        if ("scarf" in pt.acc) scarf(c, bodyClip, p, a)
        if ("tie" in pt.acc) path(c, 0xFFD94848.toInt(), a) {
            moveTo(-0.03f, -0.33f); lineTo(0.03f, -0.33f); lineTo(0.02f, -0.29f); lineTo(0.045f, -0.13f)
            lineTo(0f, -0.09f); lineTo(-0.045f, -0.13f); lineTo(-0.02f, -0.29f); close()
        }
        hair(c, pt, a)
        // floppy ears hang in front of the head
        if (pt.top == "ears_floppy") for (s in listOf(-1f, 1f)) {
            c.save()
            c.rotate(s * -14f, s * 0.28f, -0.82f)
            oval(c, s * 0.3f - 0.08f, -0.86f, s * 0.3f + 0.08f, -0.54f, dark(col, 0.35f), a)
            c.restore()
        }
        if (pt.muzzle) oval(c, -0.15f, -0.56f, 0.15f, -0.33f, light(col, 0.6f), a)
        face(c, pt, p, a)
        hats(c, pt, p, a)
        arms(c, pt, people, p, a)
        moodMarks(c, p, a)
        c.restore()
    }

    private fun tail(c: Canvas, pt: ToonParts, p: ToonPose, a: Float) {
        val col = pt.main
        // behind the body, away from the middle of the frame
        val s = -p.side
        val wag = sin(p.tMs / 380f + p.seed) * 6f
        when (pt.tail) {
            "spikes" -> path(c, col, a) { moveTo(s * 0.2f, -0.32f); quadTo(s * 0.48f, -0.2f, s * 0.56f, -0.04f); lineTo(s * 0.18f, -0.1f); close() }
            "fluffy" -> {
                c.save()
                c.rotate(s * (35f + wag), s * 0.26f, -0.16f)
                oval(c, s * 0.26f - 0.09f, -0.5f, s * 0.26f + 0.09f, -0.12f, col, a)
                oval(c, s * 0.26f - 0.06f, -0.52f, s * 0.26f + 0.06f, -0.4f, light(col, 0.75f), a)
                c.restore()
            }
            "thin" -> {
                stroke(0.035f, dark(col, 0.1f), a)
                c.save()
                c.rotate(s * wag, s * 0.24f, -0.14f)
                c.drawPath(Path().apply { moveTo(s * 0.24f, -0.14f); quadTo(s * 0.52f, -0.22f, s * 0.44f, -0.52f) }, line)
                c.restore()
            }
            "puff" -> oval(c, s * 0.3f - 0.08f, -0.24f, s * 0.3f + 0.08f, -0.08f, light(col, 0.75f), a)
        }
    }

    /** Ears, horns, antennas and hair behind the head. */
    private fun topBack(c: Canvas, pt: ToonParts, p: ToonPose, a: Float) {
        val col = pt.main
        when (pt.top) {
            "ears_fox", "ears_pointy" -> {
                val fox = pt.top == "ears_fox"
                val tip = if (fox) 1.07f else 1.0f
                for (s in listOf(-1f, 1f)) {
                    path(c, col, a) { moveTo(s * 0.08f, -0.8f); lineTo(s * 0.28f, -tip); lineTo(s * 0.30f, -0.66f); close() }
                    path(c, if (fox) 0xFF3A2A22.toInt() else light(col, 0.45f), a) { moveTo(s * 0.15f, -0.8f); lineTo(s * 0.27f, -tip + 0.06f); lineTo(s * 0.27f, -0.72f); close() }
                }
            }
            "ears_round" -> for (s in listOf(-1f, 1f)) {
                oval(c, s * 0.22f - 0.1f, -0.93f, s * 0.22f + 0.1f, -0.73f, col, a)
                oval(c, s * 0.22f - 0.05f, -0.88f, s * 0.22f + 0.05f, -0.78f, light(col, 0.35f), a)
            }
            "ears_dark" -> for (s in listOf(-1f, 1f)) oval(c, s * 0.22f - 0.1f, -0.93f, s * 0.22f + 0.1f, -0.73f, 0xFF26262B.toInt(), a)
            "ears_mouse" -> for (s in listOf(-1f, 1f)) {
                oval(c, s * 0.27f - 0.15f, -1.0f, s * 0.27f + 0.15f, -0.7f, col, a)
                oval(c, s * 0.27f - 0.09f, -0.94f, s * 0.27f + 0.09f, -0.76f, 0xFFFFB3C6.toInt(), a)
            }
            "tufts" -> for (s in listOf(-1f, 1f)) {
                path(c, dark(col, 0.15f), a) { moveTo(s * 0.10f, -0.84f); lineTo(s * 0.27f, -1.0f); lineTo(s * 0.27f, -0.74f); close() }
            }
            "horns" -> for (s in listOf(-1f, 1f)) {
                path(c, 0xFFEDE3C8.toInt(), a) { moveTo(s * 0.1f, -0.84f); quadTo(s * 0.2f, -0.98f, s * 0.3f, -1.06f); quadTo(s * 0.26f, -0.9f, s * 0.22f, -0.78f); close() }
            }
            "antenna" -> {
                stroke(0.025f, dark(col, 0.35f), a)
                c.drawLine(0f, -0.84f, 0f, -1.0f, line)
                val on = ((p.tMs / 500) % 2 == 0L) || p.talking
                oval(c, -0.045f, -1.06f, 0.045f, -0.97f, if (on) p.accent else dark(col, 0.2f), a)
            }
            "antennae" -> for (s in listOf(-1f, 1f)) {
                stroke(0.022f, dark(col, 0.3f), a)
                c.drawLine(s * 0.1f, -0.86f, s * 0.2f, -1.04f, line)
                oval(c, s * 0.2f - 0.04f, -1.09f, s * 0.2f + 0.04f, -1.0f, if (p.talking) p.accent else light(col, 0.3f), a)
            }
            "spikes" -> for (x0 in listOf(-0.16f, 0f, 0.16f)) {
                path(c, 0xFFF2A541.toInt(), a) { moveTo(x0 - 0.07f, -0.84f); lineTo(x0, -1.0f + abs(x0) * 0.4f); lineTo(x0 + 0.07f, -0.84f); close() }
            }
            "bumps" -> for (s in listOf(-1f, 1f)) oval(c, s * 0.13f - 0.12f, -0.98f, s * 0.13f + 0.12f, -0.74f, col, a)
            "grey_sides" -> for (s in listOf(-1f, 1f)) oval(c, s * 0.29f - 0.09f, -0.78f, s * 0.29f + 0.09f, -0.6f, pt.hairColor, a)
            "pigtails" -> for (s in listOf(-1f, 1f)) {
                oval(c, s * 0.36f - 0.09f, -0.84f, s * 0.36f + 0.09f, -0.6f, pt.hairColor, a)
                oval(c, s * 0.3f - 0.035f, -0.8f, s * 0.3f + 0.035f, -0.73f, 0xFFFF6B9E.toInt(), a)
            }
        }
    }

    private fun hair(c: Canvas, pt: ToonParts, a: Float) {
        when (pt.hair) {
            "short" -> path(c, pt.hairColor, a) {
                moveTo(-0.3f, -0.66f)
                quadTo(-0.3f, -0.93f, 0f, -0.92f)
                quadTo(0.3f, -0.93f, 0.31f, -0.66f)
                quadTo(0.12f, -0.8f, -0.05f, -0.74f)
                quadTo(-0.18f, -0.72f, -0.3f, -0.66f)
                close()
                moveTo(0.02f, -0.9f)
                quadTo(0.05f, -1.02f, 0.13f, -1.0f)
                quadTo(0.07f, -0.95f, 0.08f, -0.89f)
                close()
            }
            "curly" -> for (k in 0..8) {
                // curls along the top of the head
                val ang = (PI * (1.08 + 0.84 * k / 8.0)).toFloat()
                val cx = 0.3f * cos(ang)
                val cy = -0.47f + 0.43f * sin(ang)
                oval(c, cx - 0.075f, cy - 0.075f, cx + 0.075f, cy + 0.075f, pt.hairColor, a)
            }
        }
    }

    private fun scarf(c: Canvas, bodyClip: Path, p: ToonPose, a: Float) {
        val red = 0xFFE0533D.toInt()
        fill.color = red
        fill.alpha = (255 * a).toInt()
        c.save()
        c.clipPath(bodyClip)
        c.drawRect(-0.4f, -0.37f, 0.4f, -0.29f, fill)
        c.restore()
        val s = -p.side
        path(c, dark(red, 0.1f), a) { moveTo(s * 0.12f, -0.31f); lineTo(s * 0.2f, -0.31f); lineTo(s * 0.22f, -0.14f); lineTo(s * 0.14f, -0.13f); close() }
    }

    /** Hats and other things on and around the head, in front of it. */
    private fun hats(c: Canvas, pt: ToonParts, p: ToonPose, a: Float) {
        val top = if (pt.top == "bumps") -0.98f else -0.88f
        if ("headphones" in pt.acc) {
            stroke(0.035f, 0xFF2A2F3A.toInt(), a)
            c.drawArc(RectF(-0.33f, top - 0.16f, 0.33f, -0.36f), 190f, 160f, false, line)
            for (s in listOf(-1f, 1f)) {
                fill.color = 0xFF2A2F3A.toInt()
                fill.alpha = (255 * a).toInt()
                c.drawRoundRect(RectF(s * 0.33f - 0.06f, -0.72f, s * 0.33f + 0.06f, -0.52f), 0.04f, 0.04f, fill)
                oval(c, s * 0.33f - 0.03f, -0.67f, s * 0.33f + 0.03f, -0.57f, p.accent, a)
            }
        }
        if ("hat" in pt.acc) {
            fill.color = 0xFF2A2A33.toInt()
            fill.alpha = (255 * a).toInt()
            c.drawRect(-0.16f, top - 0.3f, 0.16f, top + 0.02f, fill)
            c.drawRoundRect(RectF(-0.27f, top - 0.02f, 0.27f, top + 0.05f), 0.03f, 0.03f, fill)
            fill.color = p.accent
            fill.alpha = (255 * a).toInt()
            c.drawRect(-0.16f, top - 0.06f, 0.16f, top - 0.01f, fill)
        }
        if ("cap" in pt.acc) {
            val capCol = pt.clothes
            c.save()
            c.clipRect(-0.4f, -1.2f, 0.4f, top + 0.08f)
            oval(c, -0.29f, top - 0.12f, 0.29f, top + 0.24f, capCol, a)
            c.restore()
            val s = p.side
            oval(c, s * 0.12f - 0.2f, top + 0.04f, s * 0.12f + 0.2f, top + 0.11f, dark(capCol, 0.2f), a)
        }
        if ("crown" in pt.acc) path(c, 0xFFF2C94C.toInt(), a) {
            moveTo(-0.16f, top + 0.03f); lineTo(-0.18f, top - 0.14f); lineTo(-0.09f, top - 0.05f); lineTo(0f, top - 0.18f)
            lineTo(0.09f, top - 0.05f); lineTo(0.18f, top - 0.14f); lineTo(0.16f, top + 0.03f); close()
        }
        if ("bow" in pt.acc) {
            val bx = -p.side * 0.2f
            val by = top + 0.05f
            path(c, 0xFFFF5C8A.toInt(), a) {
                moveTo(bx, by); lineTo(bx - 0.1f, by - 0.07f); lineTo(bx - 0.1f, by + 0.07f); close()
                moveTo(bx, by); lineTo(bx + 0.1f, by - 0.07f); lineTo(bx + 0.1f, by + 0.07f); close()
            }
            oval(c, bx - 0.03f, by - 0.03f, bx + 0.03f, by + 0.03f, 0xFFE0436F.toInt(), a)
        }
    }

    private fun face(c: Canvas, pt: ToonParts, p: ToonPose, a: Float) {
        val col = pt.main
        val visor = pt.eyes == "visor"
        val high = pt.top == "bumps"
        val ey = when {
            visor -> -0.6f
            high -> -0.84f
            else -> -0.62f
        }
        val ex = if (high) 0.13f else 0.11f
        val big = pt.eyeSize
        val shut = (if (pt.eyes == "sleepy") maxOf(0.45f, p.blink) else p.blink).coerceIn(0f, 1f)
        val px = p.look.coerceIn(-1f, 1f) * 0.03f
        for (s in listOf(-1f, 1f)) {
            val cx = s * ex
            if (visor) {
                val hh = 0.07f * (1f - 0.85f * shut)
                fill.color = 0xFF7DF9FF.toInt()
                fill.alpha = (255 * a).toInt()
                c.drawRoundRect(RectF(cx - 0.055f + px, ey - hh, cx + 0.055f + px, ey + hh), 0.03f, 0.03f, fill)
                continue
            }
            if (pt.eyeMark == "disc") oval(c, cx - 0.12f, ey - 0.12f, cx + 0.12f, ey + 0.12f, light(col, 0.55f), a)
            if (pt.eyeMark == "patch") {
                c.save()
                c.rotate(s * -20f, cx, ey)
                oval(c, cx - 0.09f, ey - 0.12f, cx + 0.09f, ey + 0.11f, 0xFF26262B.toInt(), a)
                c.restore()
            }
            if (pt.eyes == "dot") {
                val pr = 0.04f * big
                if (shut < 0.6f) oval(c, cx + px - pr, ey - pr * (1f - shut), cx + px + pr, ey + pr * (1f - shut), 0xFF1B1B22.toInt(), a)
                else {
                    stroke(0.016f, 0xFF1B1B22.toInt(), a)
                    c.drawLine(cx - pr, ey, cx + pr, ey, line)
                }
                continue
            }
            val rx = 0.072f * big
            val ry = 0.088f * big
            oval(c, cx - rx, ey - ry * (1f - 0.9f * shut), cx + rx, ey + ry * (1f - 0.9f * shut), 0xFFFFFFFF.toInt(), a)
            if (shut < 0.6f) {
                val pr = 0.038f * big
                oval(c, cx + px - pr, ey + 0.01f - pr, cx + px + pr, ey + 0.01f + pr, 0xFF1B1B22.toInt(), a)
                oval(c, cx + px + pr * 0.15f, ey - pr * 0.55f, cx + px + pr * 0.65f, ey - pr * 0.05f, 0xFFFFFFFF.toInt(), a)
                if (pt.eyes == "sleepy") {
                    // heavy eyelid from above
                    fill.color = dark(col, 0.08f)
                    fill.alpha = (255 * a).toInt()
                    c.save()
                    c.clipPath(Path().apply { addOval(RectF(cx - rx, ey - ry, cx + rx, ey + ry), Path.Direction.CW) })
                    c.drawRect(cx - rx, ey - ry, cx + rx, ey - ry + 2f * ry * shut * 0.9f, fill)
                    c.restore()
                }
            } else {
                stroke(0.016f, 0xFF1B1B22.toInt(), a)
                c.drawLine(cx - rx, ey, cx + rx, ey, line)
            }
        }
        if ("glasses" in pt.acc) {
            stroke(0.018f, 0xFF2A2F3A.toInt(), a)
            val gr = 0.1f * maxOf(1f, big * 0.95f)
            for (s in listOf(-1f, 1f)) c.drawCircle(s * ex, ey, gr, line)
            c.drawLine(-ex + gr, ey, ex - gr, ey, line)
        }
        if (!visor) {
            stroke(0.022f, dark(col, 0.55f), a)
            val by = ey - 0.115f * big
            for (s in listOf(-1f, 1f)) {
                val inner = s * 0.05f
                val outer = s * 0.17f
                val (yi, yo) = when (p.mood) {
                    "angry" -> (by + 0.035f) to (by - 0.02f)
                    "sad" -> (by - 0.03f) to (by + 0.02f)
                    "surprise" -> (by - 0.04f) to (by - 0.04f)
                    "think" -> if (s > 0) (by - 0.045f) to (by - 0.05f) else by to by
                    "happy" -> (by - 0.01f) to (by + 0.005f)
                    else -> by to by
                }
                c.drawLine(inner, yi, outer, yo, line)
            }
        }
        if (pt.cheeks) {
            for (s in listOf(-1f, 1f)) oval(c, s * 0.2f - 0.04f, -0.5f - 0.03f, s * 0.2f + 0.04f, -0.5f + 0.03f, 0xFFFF7A90.toInt(), a * 0.35f)
        }
        when (pt.nose) {
            "dot" -> oval(c, -0.035f, -0.52f, 0.035f, -0.475f, 0xFF2B1D1A.toInt(), a)
            "pink" -> oval(c, -0.03f, -0.515f, 0.03f, -0.48f, 0xFFFF7A9C.toInt(), a)
            "beak" -> path(c, 0xFFF5A623.toInt(), a) { moveTo(-0.04f, -0.53f); lineTo(0.04f, -0.53f); lineTo(0f, -0.46f); close() }
        }
        if (pt.whiskers) {
            stroke(0.01f, 0xFF4A4A55.toInt(), a * 0.7f)
            for (s in listOf(-1f, 1f)) {
                c.drawLine(s * 0.08f, -0.47f, s * 0.24f, -0.5f, line)
                c.drawLine(s * 0.08f, -0.45f, s * 0.24f, -0.43f, line)
            }
        }
        mouth(c, visor, -0.42f - if (visor) 0.02f else 0f, p, a)
    }

    private fun mouth(c: Canvas, screen: Boolean, my: Float, p: ToonPose, a: Float) {
        val o = p.mouth.coerceIn(0f, 1f)
        if (p.talking && o > 0.05f) {
            val rx = 0.045f + 0.025f * o
            val ry = 0.012f + 0.055f * o
            if (screen) {
                fill.color = 0xFF7DF9FF.toInt()
                fill.alpha = (255 * a).toInt()
                c.drawRoundRect(RectF(-rx * 1.3f, my - ry * 0.6f, rx * 1.3f, my + ry * 0.6f), 0.015f, 0.015f, fill)
                return
            }
            openMouth(c, 0f, my, rx, ry, a)
            return
        }
        val ink = if (screen) 0xFF7DF9FF.toInt() else 0xFF3B1218.toInt()
        stroke(0.02f, ink, a)
        when (p.mood) {
            "sad" -> c.drawPath(Path().apply { moveTo(-0.055f, my + 0.02f); quadTo(0f, my - 0.03f, 0.055f, my + 0.02f) }, line)
            "surprise" -> c.drawCircle(0f, my, 0.025f, line)
            "think" -> c.drawLine(-0.03f, my, 0.05f, my - 0.012f, line)
            "angry" -> c.drawLine(-0.05f, my + 0.01f, 0.05f, my + 0.01f, line)
            else -> c.drawPath(Path().apply { moveTo(-0.06f, my - 0.01f); quadTo(0f, my + (if (p.mood == "happy") 0.06f else 0.04f), 0.06f, my - 0.01f) }, line)
        }
    }

    private fun openMouth(c: Canvas, mx: Float, my: Float, rx: Float, ry: Float, a: Float) {
        oval(c, mx - rx, my - ry, mx + rx, my + ry, 0xFF3B1218.toInt(), a)
        c.save()
        c.clipPath(Path().apply { addOval(RectF(mx - rx, my - ry, mx + rx, my + ry), Path.Direction.CW) })
        oval(c, mx - rx * 0.7f, my + ry * 0.15f, mx + rx * 0.7f, my + ry * 1.4f, 0xFFFF6B81.toInt(), a)
        c.restore()
    }

    private fun arms(c: Canvas, pt: ToonParts, people: Boolean, p: ToonPose, a: Float) {
        val col = pt.main
        val t = p.tMs.toFloat()
        val armCol = when {
            people -> pt.clothes
            pt.limbs != 0 -> pt.limbs
            else -> dark(col, 0.12f)
        }
        val hand = if (people) col else armCol
        stroke(0.07f, armCol, a)
        for (s in listOf(-1f, 1f)) {
            val front = s == p.side
            val sx = s * 0.29f
            val sy = -0.42f
            val swing = 0.02f * sin(t / 700f + p.seed + s)
            var hx = s * 0.37f + swing
            var hy = -0.2f
            if (p.talking && front) {
                when (p.mood) {
                    "explain" -> {
                        hx = s * 0.55f
                        hy = -0.58f + 0.03f * sin(t / 260f)
                    }
                    "think" -> {
                        hx = s * 0.06f
                        hy = -0.36f
                    }
                    else -> {
                        val ang = (-25f - 45f * (0.5f + 0.5f * sin(t / 230f + p.seed))) * (PI / 180f).toFloat()
                        hx = sx + s * 0.24f * cos(ang)
                        hy = sy + 0.24f * sin(ang)
                    }
                }
            } else if (p.mood == "surprise" || (p.mood == "happy" && p.talking)) {
                hx = s * 0.45f
                hy = -0.62f
            } else if (p.mood == "angry") {
                hx = s * 0.2f
                hy = -0.18f
            } else if (p.mood == "think" && front) {
                hx = s * 0.06f
                hy = -0.36f
            }
            stroke(0.07f, armCol, a)
            c.drawLine(sx, sy, hx, hy, line)
            oval(c, hx - 0.05f, hy - 0.05f, hx + 0.05f, hy + 0.05f, hand, a)
        }
    }

    /** "!" for surprise and a thought cloud above the head. */
    private fun moodMarks(c: Canvas, p: ToonPose, a: Float) {
        val t = p.tMs.toFloat()
        if (p.mood == "surprise") {
            stroke(0.03f, p.accent, a)
            c.drawLine(0.34f, -1.08f, 0.38f, -0.96f, line)
            oval(c, 0.375f, -0.935f, 0.405f, -0.905f, p.accent, a)
        } else if (p.mood == "think") {
            val k = 0.5f + 0.5f * sin(t / 300f)
            oval(c, 0.28f, -0.98f, 0.33f, -0.93f, 0xFFFFFFFF.toInt(), a * 0.9f)
            oval(c, 0.33f, -1.08f, 0.40f, -1.01f, 0xFFFFFFFF.toInt(), a * (0.6f + 0.3f * k))
            oval(c, 0.40f, -1.22f, 0.52f, -1.11f, 0xFFFFFFFF.toInt(), a * (0.4f + 0.5f * k))
        }
    }

    /**
     * The user's picture as a puppet: it breathes, hops while talking and leans with the mood; the mouth opens
     * over the picture's mouth by syllables and eyelids of the skin colour close the eyes.
     */
    private fun drawImage(c: Canvas, im: ToonImage, bmp: android.graphics.Bitmap, x: Float, y: Float, size: Float, p: ToonPose, a: Float) {
        val t = p.tMs.toFloat()
        val ratio = bmp.width.toFloat() / max(1, bmp.height)
        // fits a box one unit high and at most one wide
        val dh = if (ratio > 1f) 1f / ratio else 1f
        val dw = dh * ratio
        val breath = 0.012f * sin(t / 620f + p.seed)
        val hop = if (p.talking) 0.03f * p.mouth else 0f
        val tilt = when (p.mood) {
            "think" -> 5f * p.side
            "sad" -> -3f * p.side
            "angry" -> 1.5f * sin(t / 45f)
            "happy" -> 3f * sin(t / 240f)
            "explain" -> if (p.talking) 2.5f * sin(t / 300f) else 0f
            else -> 1.2f * sin(t / 900f + p.seed)
        }
        val jump = when (p.mood) {
            "surprise" -> 0.05f
            "happy" -> if (p.talking) 0.02f * abs(sin(t / 180f)) else 0f
            else -> 0f
        }
        c.save()
        c.translate(x, y)
        c.scale(size, size)
        oval(c, -0.3f * dw, -0.035f, 0.3f * dw, 0.035f, 0xFF000000.toInt(), 0.18f * a)
        c.translate(0f, -hop - jump)
        c.rotate(tilt, 0f, 0f)
        c.scale(1f - breath * 0.5f, 1f + breath + hop * 0.5f)
        // the picture faces the middle of the frame when it should
        if (im.flip && p.side < 0) c.scale(-1f, 1f)
        bmpPaint.alpha = (255 * a).toInt()
        val left = -dw / 2f
        val top = -dh
        c.drawBitmap(bmp, null, RectF(left, top, left + dw, 0f), bmpPaint)
        fun ix(f: Float) = left + f * dw
        fun iy(f: Float) = top + f * dh
        if (im.hasEyes && p.blink > 0.05f) {
            val d = kotlin.math.hypot((ix(im.eyeRx) - ix(im.eyeLx)).toDouble(), (iy(im.eyeRy) - iy(im.eyeLy)).toDouble()).toFloat()
            val r = d * 0.3f
            val lid = if (im.lid != 0) im.lid else 0xFFF6C49B.toInt()
            for ((ex, ey) in listOf(ix(im.eyeLx) to iy(im.eyeLy), ix(im.eyeRx) to iy(im.eyeRy))) {
                c.save()
                c.clipPath(Path().apply { addOval(RectF(ex - r, ey - r * 0.8f, ex + r, ey + r * 0.8f), Path.Direction.CW) })
                fill.color = lid
                fill.alpha = (255 * a).toInt()
                c.drawRect(ex - r, ey - r * 0.8f, ex + r, ey - r * 0.8f + 1.6f * r * p.blink.coerceIn(0f, 1f), fill)
                c.restore()
                if (p.blink > 0.6f) {
                    stroke(max(0.008f, r * 0.12f), dark(lid, 0.5f), a)
                    c.drawLine(ex - r * 0.8f, ey + r * 0.1f, ex + r * 0.8f, ey + r * 0.1f, line)
                }
            }
        }
        val o = p.mouth.coerceIn(0f, 1f)
        if (im.hasMouth && p.talking && o > 0.05f) {
            val rx = im.mouthW * dw / 2f * (0.6f + 0.4f * o)
            val ry = rx * (0.2f + 0.7f * o)
            openMouth(c, ix(im.mouthX), iy(im.mouthY), rx, ry, a)
        }
        if (im.flip && p.side < 0) c.scale(-1f, 1f)
        moodMarks(c, p, a)
        c.restore()
    }
}

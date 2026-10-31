/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/*
 * Lumen Studio, own characters: a character is built from parts (body, ears, hair, eyes, nose, tail, accessories,
 * colours, proportions) or is the user's own picture with face points. The "lumen.char" protocol is the JSON any AI
 * (the built-in one or a third-party chat bot, from a text or from a picture) returns to describe a character.
 * Pure logic, unit-tested.
 */

/** Parts of a drawn character; every value is one of the lists in [StudioParts]. Colours are ARGB, 0 = automatic. */
data class ToonParts(
    val body: String = "round",
    val top: String = "none",
    val hair: String = "none",
    val front: String = "belly",
    val eyes: String = "round",
    val eyeMark: String = "none",
    val nose: String = "none",
    val tail: String = "none",
    val acc: List<String> = emptyList(),
    val muzzle: Boolean = false,
    val whiskers: Boolean = false,
    val cheeks: Boolean = true,
    val main: Int = 0xFF4FC3A1.toInt(),
    val clothes: Int = 0xFF5B6475.toInt(),
    val hairColor: Int = 0xFF6B4226.toInt(),
    val limbs: Int = 0,
    val feet: Int = 0,
    val width: Float = 1f,
    val height: Float = 1f,
    val eyeSize: Float = 1f
)

/**
 * The user's own picture as a character: [path] to a PNG on the device; face points in parts of the picture
 * (0..1 from the top left corner), -1 = not set. [lid] is the skin colour used to close the eyes.
 */
data class ToonImage(
    val path: String,
    val eyeLx: Float = -1f,
    val eyeLy: Float = -1f,
    val eyeRx: Float = -1f,
    val eyeRy: Float = -1f,
    val mouthX: Float = -1f,
    val mouthY: Float = -1f,
    val mouthW: Float = 0.12f,
    val lid: Int = 0,
    val flip: Boolean = false
) {
    val hasEyes: Boolean get() = eyeLx >= 0f && eyeLy >= 0f && eyeRx >= 0f && eyeRy >= 0f
    val hasMouth: Boolean get() = mouthX >= 0f && mouthY >= 0f
}

object StudioParts {
    val BODIES = listOf("round", "box")
    val TOPS = listOf(
        "none", "ears_pointy", "ears_fox", "ears_round", "ears_dark", "ears_long", "ears_floppy", "ears_mouse",
        "tufts", "horns", "antenna", "antennae", "spikes", "bumps", "grey_sides", "pigtails"
    )
    val HAIRS = listOf("none", "short", "curly")
    val FRONTS = listOf("belly", "pale", "penguin", "screen", "clothes", "none")
    val EYES = listOf("round", "dot", "sleepy", "visor")
    val EYE_MARKS = listOf("none", "disc", "patch")
    val NOSES = listOf("none", "dot", "pink", "beak")
    val TAILS = listOf("none", "spikes", "fluffy", "thin", "puff")
    val ACCS = listOf("glasses", "hat", "cap", "bow", "crown", "headphones", "tie", "scarf")
    const val MAX_ACC = 4

    /** Swatches of the editor. */
    val COLORS = listOf(
        0xFFF28C38, 0xFFE0533D, 0xFFFF7AA8, 0xFF8E6BBF, 0xFF4F8FF7, 0xFF7FA7C9, 0xFF4FC3A1, 0xFF6CC24A,
        0xFFF2C94C, 0xFFA9743F, 0xFF6B4226, 0xFF9AA5B8, 0xFFF6C49B, 0xFFF4F4F2, 0xFF5B6475, 0xFF26262B
    ).map { it.toInt() }

    val WIDTH = 0.8f..1.25f
    val HEIGHT = 0.85f..1.2f
    val EYE_SIZE = 0.7f..1.5f

    fun label(v: String): String = when (v) {
        "none" -> t("нет", "none")
        "round" -> t("круглое", "round")
        "box" -> t("коробка", "box")
        "ears_pointy" -> t("острые уши", "pointy ears")
        "ears_fox" -> t("лисьи уши", "fox ears")
        "ears_round" -> t("круглые уши", "round ears")
        "ears_dark" -> t("тёмные уши", "dark ears")
        "ears_long" -> t("длинные уши", "long ears")
        "ears_floppy" -> t("висячие уши", "floppy ears")
        "ears_mouse" -> t("мышиные уши", "mouse ears")
        "tufts" -> t("пёрышки", "tufts")
        "horns" -> t("рожки", "horns")
        "antenna" -> t("антенна", "antenna")
        "antennae" -> t("усики", "antennae")
        "spikes" -> t("шипы", "spikes")
        "bumps" -> t("глаза сверху", "eye bumps")
        "grey_sides" -> t("седина", "grey sides")
        "pigtails" -> t("хвостики", "pigtails")
        "short" -> t("чёлка", "fringe")
        "curly" -> t("кудри", "curly")
        "belly" -> t("животик", "belly")
        "pale" -> t("светлый живот", "pale belly")
        "penguin" -> t("белая грудка", "white front")
        "screen" -> t("экран", "screen")
        "clothes" -> t("одежда", "clothes")
        "dot" -> t("точки", "dots")
        "sleepy" -> t("сонные", "sleepy")
        "visor" -> t("экранные", "visor")
        "disc" -> t("диски", "discs")
        "patch" -> t("пятна", "patches")
        "pink" -> t("розовый", "pink")
        "beak" -> t("клюв", "beak")
        "fluffy" -> t("пушистый", "fluffy")
        "thin" -> t("тонкий", "thin")
        "puff" -> t("помпон", "puff")
        "glasses" -> t("очки", "glasses")
        "hat" -> t("цилиндр", "top hat")
        "cap" -> t("кепка", "cap")
        "bow" -> t("бантик", "bow")
        "crown" -> t("корона", "crown")
        "headphones" -> t("наушники", "headphones")
        "tie" -> t("галстук", "tie")
        "scarf" -> t("шарф", "scarf")
        else -> v
    }

    /** The parts of one of the 16 ready characters ([StudioToon.LOOKS]); unknown looks are the blob. */
    fun preset(look: String): ToonParts {
        val p = ToonParts(main = StudioToon.defaultColor(look))
        return when (look) {
            "fox" -> p.copy(top = "ears_fox", muzzle = true, nose = "dot", tail = "fluffy")
            "cat" -> p.copy(top = "ears_pointy", muzzle = true, nose = "dot", whiskers = true)
            "bear" -> p.copy(top = "ears_round", muzzle = true, nose = "dot")
            "robot" -> p.copy(body = "box", top = "antenna", front = "screen", eyes = "visor", cheeks = false)
            "owl" -> p.copy(top = "tufts", eyeMark = "disc", eyeSize = 1.3f, nose = "beak")
            "kid" -> p.copy(front = "clothes", hair = "short", clothes = 0xFF4F8FF7.toInt(), hairColor = 0xFF6B4226.toInt())
            "prof" -> p.copy(front = "clothes", top = "grey_sides", hairColor = 0xFFDADDE2.toInt(), acc = listOf("glasses"))
            "dog" -> p.copy(top = "ears_floppy", muzzle = true, nose = "dot")
            "rabbit" -> p.copy(top = "ears_long", front = "pale", muzzle = true, nose = "pink", tail = "puff")
            "panda" -> p.copy(top = "ears_dark", front = "pale", muzzle = true, nose = "dot", eyeMark = "patch",
                limbs = 0xFF26262B.toInt(), feet = 0xFF26262B.toInt())
            "penguin" -> p.copy(front = "penguin", nose = "beak", feet = 0xFFF5A623.toInt())
            "frog" -> p.copy(top = "bumps", eyeSize = 1.15f)
            "alien" -> p.copy(top = "antennae", eyeSize = 1.3f)
            "dino" -> p.copy(top = "spikes", tail = "spikes")
            "girl" -> p.copy(front = "clothes", top = "pigtails", hair = "short", clothes = 0xFFE85D9E.toInt(), hairColor = 0xFF8B3A2E.toInt())
            else -> p
        }
    }

    /** The parts a character is drawn with: its own ones, or its look's with its colour. */
    fun of(ch: SChar): ToonParts = ch.parts ?: preset(ch.look).let { if (ch.color != 0) it.copy(main = ch.color) else it }

    /** Every value known, numbers within their ranges, accessories without repeats. */
    fun normalize(p: ToonParts): ToonParts = p.copy(
        body = p.body.takeIf { it in BODIES } ?: "round",
        top = p.top.takeIf { it in TOPS } ?: "none",
        hair = p.hair.takeIf { it in HAIRS } ?: "none",
        front = p.front.takeIf { it in FRONTS } ?: "belly",
        eyes = p.eyes.takeIf { it in EYES } ?: "round",
        eyeMark = p.eyeMark.takeIf { it in EYE_MARKS } ?: "none",
        nose = p.nose.takeIf { it in NOSES } ?: "none",
        tail = p.tail.takeIf { it in TAILS } ?: "none",
        acc = p.acc.filter { it in ACCS }.distinct().take(MAX_ACC),
        main = opaque(p.main),
        clothes = opaque(p.clothes),
        hairColor = opaque(p.hairColor),
        limbs = if (p.limbs == 0) 0 else opaque(p.limbs),
        feet = if (p.feet == 0) 0 else opaque(p.feet),
        width = clamp(p.width, WIDTH),
        height = clamp(p.height, HEIGHT),
        eyeSize = clamp(p.eyeSize, EYE_SIZE)
    )

    private fun clamp(v: Float, r: ClosedFloatingPointRange<Float>) = if (v.isNaN()) 1f else v.coerceIn(r.start, r.endInclusive)
    private fun opaque(c: Int) = c or (0xFF shl 24)

    /** A random character that still looks whole: animals get a muzzle and a nose, people get clothes. */
    fun random(rnd: Random = Random.Default): ToonParts {
        fun <T> pick(l: List<T>) = l[rnd.nextInt(l.size)]
        val person = rnd.nextInt(4) == 0
        val main = if (person) pick(listOf(0xFFF6C49B, 0xFFE8B58C, 0xFFC68E63, 0xFF8D5A3B).map { it.toInt() }) else pick(COLORS)
        val top = if (person) pick(listOf("none", "pigtails", "grey_sides", "none")) else pick(TOPS.filter { it != "grey_sides" && it != "pigtails" })
        val accs = ACCS.filter { rnd.nextInt(5) == 0 }.take(2)
        return normalize(ToonParts(
            body = if (!person && rnd.nextInt(6) == 0) "box" else "round",
            top = top,
            hair = if (person) pick(listOf("short", "curly", "short")) else "none",
            front = if (person) "clothes" else pick(listOf("belly", "belly", "pale", "penguin", "none")),
            eyes = pick(listOf("round", "round", "round", "dot", "sleepy")),
            eyeMark = if (!person && rnd.nextInt(5) == 0) pick(listOf("disc", "patch")) else "none",
            nose = if (person) "none" else pick(listOf("dot", "dot", "pink", "beak", "none")),
            tail = if (person) "none" else pick(TAILS),
            acc = accs,
            muzzle = !person && rnd.nextBoolean(),
            whiskers = !person && rnd.nextInt(4) == 0,
            cheeks = rnd.nextInt(5) != 0,
            main = main,
            clothes = pick(COLORS),
            hairColor = pick(listOf(0xFF6B4226, 0xFF2B2B33, 0xFFE8C26A, 0xFF8B3A2E, 0xFFDADDE2).map { it.toInt() }),
            width = 0.9f + rnd.nextInt(5) * 0.05f,
            height = 0.92f + rnd.nextInt(5) * 0.04f,
            eyeSize = 0.9f + rnd.nextInt(5) * 0.1f
        ))
    }

    // ---------- JSON ----------

    fun hex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    private val NAMED = mapOf(
        "red" to 0xFFE0533D, "orange" to 0xFFF28C38, "yellow" to 0xFFF2C94C, "green" to 0xFF6CC24A, "teal" to 0xFF4FC3A1,
        "blue" to 0xFF4F8FF7, "purple" to 0xFF8E6BBF, "pink" to 0xFFFF7AA8, "brown" to 0xFFA9743F, "grey" to 0xFF9AA5B8,
        "gray" to 0xFF9AA5B8, "white" to 0xFFF4F4F2, "black" to 0xFF26262B, "beige" to 0xFFF6C49B, "skin" to 0xFFF6C49B
    ).mapValues { it.value.toInt() }

    /** "#RRGGBB", "#AARRGGBB", "RRGGBB", "#RGB", a plain colour name or a number; null when it is none of them. */
    fun color(v: Any?): Int? {
        if (v is Number) return v.toInt() or (0xFF shl 24)
        val s = (v as? String)?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        NAMED[s]?.let { return it }
        val h = s.removePrefix("#").removePrefix("0x")
        if (!h.all { it in "0123456789abcdef" }) return null
        return when (h.length) {
            3 -> (0xFF shl 24) or h.map { "$it$it" }.joinToString("").toInt(16)
            6 -> (0xFF shl 24) or h.toInt(16)
            8 -> h.toLong(16).toInt() or (0xFF shl 24)
            else -> null
        }
    }

    fun toJson(p: ToonParts): JSONObject = JSONObject()
        .put("body", p.body).put("top", p.top).put("hair", p.hair).put("front", p.front)
        .put("eyes", p.eyes).put("eyeMark", p.eyeMark).put("nose", p.nose).put("tail", p.tail)
        .put("acc", JSONArray(p.acc))
        .put("muzzle", p.muzzle).put("whiskers", p.whiskers).put("cheeks", p.cheeks)
        .put("colors", JSONObject().put("main", hex(p.main)).put("clothes", hex(p.clothes)).put("hair", hex(p.hairColor))
            .put("limbs", if (p.limbs == 0) "" else hex(p.limbs)).put("feet", if (p.feet == 0) "" else hex(p.feet)))
        .put("width", p.width.toDouble()).put("height", p.height.toDouble()).put("eyeSize", p.eyeSize.toDouble())

    /** Parts from JSON over [base]: missing fields keep the base, unknown values are reported in [notes] and dropped. */
    fun fromJson(o: JSONObject, base: ToonParts, notes: MutableList<String> = mutableListOf()): ToonParts {
        fun str(key: String, list: List<String>, old: String): String {
            if (!o.has(key)) return old
            val v = o.optString(key).trim().lowercase().replace(' ', '_').replace('-', '_')
            if (v in list) return v
            if (v.isEmpty() || v == "null") return "none".takeIf { it in list } ?: old
            notes += "$key: $v?"
            return old
        }
        fun bool(key: String, old: Boolean) = if (o.has(key)) o.optBoolean(key, old) else old
        fun num(key: String, old: Float) = if (o.has(key)) o.optDouble(key, old.toDouble()).toFloat() else old
        val colors = o.optJSONObject("colors") ?: JSONObject()
        fun col(key: String, old: Int, auto: Boolean = false): Int {
            if (!colors.has(key)) return old
            val raw = colors.opt(key)
            val c = color(raw)
            if (c == null && raw is String && raw.isBlank() && auto) return 0
            if (c == null) {
                if (raw is String && raw.isNotBlank()) notes += "colors.$key: $raw?"
                return old
            }
            return c
        }
        val acc = if (o.has("acc")) {
            val a = o.optJSONArray("acc")
            val list = if (a != null) (0 until a.length()).map { a.optString(it) } else o.optString("acc").split(',', ' ')
            list.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.also { l -> l.filter { it !in ACCS }.forEach { notes += "acc: $it?" } }
        } else base.acc
        return normalize(base.copy(
            body = str("body", BODIES, base.body),
            top = str("top", TOPS, base.top),
            hair = str("hair", HAIRS, base.hair),
            front = str("front", FRONTS, base.front),
            eyes = str("eyes", EYES, base.eyes),
            eyeMark = str("eyeMark", EYE_MARKS, base.eyeMark),
            nose = str("nose", NOSES, base.nose),
            tail = str("tail", TAILS, base.tail),
            acc = acc,
            muzzle = bool("muzzle", base.muzzle),
            whiskers = bool("whiskers", base.whiskers),
            cheeks = bool("cheeks", base.cheeks),
            main = col("main", base.main),
            clothes = col("clothes", base.clothes),
            hairColor = col("hair", base.hairColor),
            limbs = col("limbs", base.limbs, auto = true),
            feet = col("feet", base.feet, auto = true),
            width = num("width", base.width),
            height = num("height", base.height),
            eyeSize = num("eyeSize", base.eyeSize)
        ))
    }
}

/**
 * The "lumen.char" protocol, version 1: one JSON object that describes a character. Written by the built-in AI
 * (from a description or a picture), by any other AI the user asks with [externalPrompt], or by Lumen itself
 * ("⧉ Copy code"); read back by [parse], which forgives Markdown fences, extra text, percentages and unknown values.
 *
 * {"format":"lumen.char","v":1,"name":"Foxy","base":"fox","persona":"…","voice":"f","pitch":10,"rate":0,
 *  "parts":{…[StudioParts.toJson]…},"face":{"eyeL":[0.42,0.35],"eyeR":[0.58,0.35],"mouth":[0.5,0.55],"mouthW":0.14}}
 */
object StudioCharProto {
    const val FORMAT = "lumen.char"
    const val VERSION = 1
    val PITCH = -30..40
    val RATE = -20..30

    data class Parsed(val char: SChar, val notes: List<String>, val hasFace: Boolean)

    fun toJson(ch: SChar): JSONObject = JSONObject().put("format", FORMAT).put("v", VERSION)
        .put("name", ch.name).put("base", ch.look).put("persona", ch.persona).put("voice", ch.voice)
        .apply { ch.pitch?.let { put("pitch", it) } }
        .put("rate", ch.rate)
        .put("parts", StudioParts.toJson(StudioParts.of(ch)))
        .apply {
            val im = ch.image ?: return@apply
            val face = JSONObject()
            if (im.hasEyes) face.put("eyeL", JSONArray().put(r3(im.eyeLx)).put(r3(im.eyeLy))).put("eyeR", JSONArray().put(r3(im.eyeRx)).put(r3(im.eyeRy)))
            if (im.hasMouth) face.put("mouth", JSONArray().put(r3(im.mouthX)).put(r3(im.mouthY))).put("mouthW", r3(im.mouthW))
            put("face", face)
        }

    private fun r3(f: Float) = Math.round(f * 1000) / 1000.0

    /** The code of a character to share or keep: compact JSON. */
    fun encode(ch: SChar): String = toJson(ch).toString()

    /** The first JSON object in [text] (AI answers come in ``` fences or with words around). */
    fun extract(text: String): JSONObject? {
        val s = text.trim()
        runCatching { return JSONObject(s) }
        var from = s.indexOf('{')
        while (from >= 0) {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in from until s.length) {
                val c = s[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            runCatching { return JSONObject(s.substring(from, i + 1)) }
                            break
                        }
                    }
                }
            }
            from = s.indexOf('{', from + 1)
        }
        return null
    }

    /**
     * A character from a "lumen.char" answer over [base] (the one being edited): what the answer leaves out stays.
     * Accepts the parts alone as well. Face points apply only when [base] is the user's picture. Null when there is no JSON.
     */
    fun parse(text: String, base: SChar): Parsed? {
        val o0 = extract(text) ?: return null
        val o = o0.optJSONObject("character") ?: o0.optJSONObject("char") ?: o0
        val notes = mutableListOf<String>()
        val partsObj = o.optJSONObject("parts") ?: o.takeIf { it.has("top") || it.has("body") || it.has("eyes") || it.has("colors") }
        val fmt = o.optString("format")
        if (fmt.isNotEmpty() && fmt != FORMAT) notes += "format: $fmt?"
        val v = o.optInt("v", VERSION)
        if (v > VERSION) notes += t("версия протокола $v новее приложения", "protocol version $v is newer than the app")
        val look = o.optString("base").trim().lowercase().takeIf { it in StudioToon.LOOKS }
            ?: base.look.takeIf { o.optString("base").isBlank() }
            ?: run { notes += "base: ${o.optString("base")}?"; base.look }
        // another base starts from its ready parts, the same one keeps the edited parts
        val start = if (look != base.look) StudioParts.preset(look) else StudioParts.of(base)
        val parts = if (partsObj != null) StudioParts.fromJson(partsObj, start, notes) else start
        val name = StudioToon.cleanName(o.optString("name")).ifEmpty { base.name }
        val voice = o.optString("voice", base.voice).trim().let { vv ->
            when {
                vv.isEmpty() || vv == "m" || vv == "f" -> vv
                vv.lowercase() in listOf("male", "man", "м", "муж", "мужской") -> "m"
                vv.lowercase() in listOf("female", "woman", "ж", "жен", "женский") -> "f"
                vv.endsWith("Neural") -> vv
                else -> { notes += "voice: $vv?"; base.voice }
            }
        }
        val pitch = if (o.has("pitch")) o.opt("pitch").let { p -> (p as? Number)?.toInt()?.coerceIn(PITCH.first, PITCH.last) } else base.pitch
        val rate = if (o.has("rate")) o.optInt("rate", base.rate).coerceIn(RATE.first, RATE.last) else base.rate
        val persona = if (o.has("persona")) o.optString("persona").trim().replace(Regex("\\s+"), " ").take(300) else base.persona
        var image = base.image
        val face = o.optJSONObject("face")
        var hasFace = false
        if (face != null && image != null) {
            val l = point(face.opt("eyeL") ?: face.opt("left_eye") ?: face.opt("leftEye"))
            val r = point(face.opt("eyeR") ?: face.opt("right_eye") ?: face.opt("rightEye"))
            val m = point(face.opt("mouth"))
            if (l != null && r != null) {
                // left is the one nearer the left edge of the picture
                val (a, b) = if (l.first <= r.first) l to r else r to l
                image = image.copy(eyeLx = a.first, eyeLy = a.second, eyeRx = b.first, eyeRy = b.second)
                hasFace = true
            }
            if (m != null) {
                val w = frac(face.opt("mouthW") ?: face.opt("mouth_width")) ?: image.mouthW
                image = image.copy(mouthX = m.first, mouthY = m.second, mouthW = w.coerceIn(0.03f, 0.5f))
                hasFace = true
            }
        }
        return Parsed(SChar(name, look, voice, 0, parts, persona, pitch, rate, image), notes.distinct(), hasFace)
    }

    /** A point [x, y] or {"x":…, "y":…} in parts of the picture; percentages (≤100) are accepted. */
    fun point(v: Any?): Pair<Float, Float>? {
        val (x, y) = when (v) {
            is JSONArray -> if (v.length() >= 2) v.opt(0) to v.opt(1) else return null
            is JSONObject -> v.opt("x") to v.opt("y")
            else -> return null
        }
        val fx = frac(x) ?: return null
        val fy = frac(y) ?: return null
        return fx to fy
    }

    private fun frac(v: Any?): Float? {
        val d = (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.removeSuffix("%")?.toDoubleOrNull() ?: return null
        val f = if (d > 1.0 && d <= 100.0) d / 100.0 else d
        return if (f in 0.0..1.0) f.toFloat() else null
    }

    /** Allowed values of every field, for the AI. */
    fun schema(): String = buildString {
        appendLine("Протокол lumen.char v1 — один JSON-объект, все поля необязательные:")
        appendLine("format: \"lumen.char\"; v: 1; name — имя одним словом (буквы, цифры, _); persona — характер и манера речи, до 200 символов;")
        appendLine("base — ближайшая готовая заготовка: ${StudioToon.LOOKS.joinToString(", ")};")
        appendLine("voice — \"f\" (женский), \"m\" (мужской) или \"\" (авто); pitch — тон голоса в Гц ${PITCH.first}..${PITCH.last}; rate — темп речи в % ${RATE.first}..${RATE.last}.")
        appendLine("parts — детали рисованного героя (голова и тело — одно целое):")
        appendLine("  body: ${StudioParts.BODIES.joinToString("|")} (box — робот-коробка);")
        appendLine("  top — что на макушке: ${StudioParts.TOPS.joinToString("|")} (bumps — глаза лягушки на макушке; grey_sides и pigtails окрашиваются цветом hair);")
        appendLine("  hair — волосы спереди: ${StudioParts.HAIRS.joinToString("|")};")
        appendLine("  front — живот или одежда: ${StudioParts.FRONTS.joinToString("|")} (clothes — человек: низ тела и руки цвета clothes, кисти цвета main);")
        appendLine("  eyes: ${StudioParts.EYES.joinToString("|")} (visor — экранные глаза и рот робота); eyeMark: ${StudioParts.EYE_MARKS.joinToString("|")};")
        appendLine("  nose: ${StudioParts.NOSES.joinToString("|")}; tail: ${StudioParts.TAILS.joinToString("|")};")
        appendLine("  acc — до ${StudioParts.MAX_ACC} аксессуаров: [${StudioParts.ACCS.joinToString(", ")}];")
        appendLine("  muzzle (светлая мордочка), whiskers (усы), cheeks (румянец) — true/false;")
        appendLine("  colors: main (тело или кожа), clothes, hair, limbs (руки), feet — \"#RRGGBB\", \"\" — авто;")
        appendLine("  width ${StudioParts.WIDTH.start}..${StudioParts.WIDTH.endInclusive}, height ${StudioParts.HEIGHT.start}..${StudioParts.HEIGHT.endInclusive}, eyeSize ${StudioParts.EYE_SIZE.start}..${StudioParts.EYE_SIZE.endInclusive} — пропорции, 1 — обычные.")
        appendLine("face — только когда героем служит своя картинка: eyeL, eyeR, mouth — [x, y] в долях ширины и высоты картинки от левого верхнего угла (0..1); mouthW — ширина рта в долях ширины картинки.")
        append("Пример: ")
        append(toJson(SChar("Фокси", "fox", "f", 0, StudioParts.preset("fox").copy(acc = listOf("scarf")), "хитрая, говорит коротко и с иронией", 10, 0)).toString())
    }

    /**
     * A prompt to paste into any other AI (ChatGPT, Gemini, Claude…): [desc] — what the author wants; [image] — the
     * author attaches a picture to copy the character from; [face] — the picture itself will be the character, so
     * the AI must find the eyes and the mouth on it.
     */
    fun externalPrompt(desc: String, image: Boolean, face: Boolean): String = buildString {
        appendLine("Ты помогаешь создать персонажа для мультфильма в приложении Lumen Studio. Персонаж рисуется из готовых деталей, говорит, моргает и жестикулирует.")
        if (image) appendLine("Я прикладываю картинку. Повтори её героя деталями протокола: животное или человек, уши, причёска, аксессуары, цвета точно по картинке (hex).")
        if (face) appendLine("Эта картинка сама будет героем: найди на ней центры глаз и рта и верни их в поле face.")
        if (desc.isNotBlank()) appendLine("Пожелания: ${desc.trim()}")
        appendLine("Придумай имя и характер, если я их не задал.")
        appendLine()
        appendLine(schema())
        appendLine()
        append("Ответь только одним JSON-объектом, без пояснений.")
    }

    /** The {mode} part of the built-in prompt studio_char. */
    fun mode(image: Boolean, face: Boolean): String = when {
        face -> "К сообщению приложена картинка — она сама будет героем. Найди центры глаз и рта и верни поле face; детали parts подбери похожими на картинку (их видно, если картинку уберут)."
        image -> "К сообщению приложена картинка. Повтори её героя деталями: животное или человек, уши, причёска, аксессуары, цвета точно по картинке (hex)."
        else -> "Собери героя по описанию; если описания мало — придумай яркого, запоминающегося героя."
    }
}

/** Pictures as characters: background removal and the skin colour for the eyelids (pixels as ARGB). */
object StudioCharImage {
    /** Biggest difference of the colour channels. */
    fun diff(a: Int, b: Int): Int = maxOf(
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        kotlin.math.abs((a and 0xFF) - (b and 0xFF))
    )

    /** True when the picture already has a transparent background (two corners or more). */
    fun hasAlphaBg(px: IntArray, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0 || px.size < w * h) return false
        return listOf(0, w - 1, (h - 1) * w, h * w - 1).count { (px[it] ushr 24) < 32 } >= 2
    }

    /**
     * Makes a plain background transparent: fills from the border through pixels close (≤ [tol]) to the colours
     * of the corners. Changes [px]; returns how many pixels became transparent (0 when the background is not plain).
     */
    fun removeBg(px: IntArray, w: Int, h: Int, tol: Int = 40): Int {
        if (w < 3 || h < 3 || px.size < w * h || hasAlphaBg(px, w, h)) return 0
        val corners = listOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        // the background is the colour most corners share
        val bg = corners.maxByOrNull { c -> corners.count { diff(it, c) <= tol } } ?: return 0
        if (corners.count { diff(it, bg) <= tol } < 2) return 0
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!seen[i] && (px[i] ushr 24) >= 32 && diff(px[i], bg) <= tol) {
                seen[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        // more than 97% means the "background" was the picture itself
        if (tail > w * h * 97 / 100) return 0
        for (k in 0 until tail) px[queue[k]] = 0
        return tail
    }

    /** Average opaque colour of a small square around ([x], [y]) in parts of the picture: the eyelid over the eye. */
    fun skinAt(px: IntArray, w: Int, h: Int, x: Float, y: Float, r: Int = 3): Int {
        if (w <= 0 || h <= 0) return 0xFFF6C49B.toInt()
        val cx = (x * (w - 1)).toInt().coerceIn(0, w - 1)
        val cy = (y * (h - 1)).toInt().coerceIn(0, h - 1)
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var n = 0
        for (yy in (cy - r)..(cy + r)) for (xx in (cx - r)..(cx + r)) {
            if (xx !in 0 until w || yy !in 0 until h) continue
            val c = px[yy * w + xx]
            if ((c ushr 24) < 128) continue
            rs += (c shr 16) and 0xFF
            gs += (c shr 8) and 0xFF
            bs += c and 0xFF
            n++
        }
        if (n == 0) return 0xFFF6C49B.toInt()
        return (0xFF shl 24) or ((rs / n).toInt() shl 16) or ((gs / n).toInt() shl 8) or (bs / n).toInt()
    }

    /** Where to take the eyelid colour: above the eyes, by a third of the distance between them. */
    fun lidPoint(im: ToonImage): Pair<Float, Float> {
        val d = kotlin.math.hypot((im.eyeRx - im.eyeLx).toDouble(), (im.eyeRy - im.eyeLy).toDouble()).toFloat()
        return ((im.eyeLx + im.eyeRx) / 2f) to (minOf(im.eyeLy, im.eyeRy) - d * 0.45f).coerceIn(0f, 1f)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Templates the built-in AI can invent on the fly (all pure, unit-tested):
 *  - "lumen.motion": an animated graphic with parameters (layers, groups, repeats over the items in sync with the words,
 *    key frames with small formulas, named positions); the painter draws it like the shapes of the AI director.
 *  - "lumen.edit": a way of editing (pace, camera, style and rules "if the scene has … then …") that directs a whole
 *    voice-over without AI.
 * The AI answers with JSON; [StudioTplCheck] lists the problems so the AI can fix them; [JsonPatch] applies edits.
 */
object StudioExpr {
    /** Named positions usable in formulas and as values: parts of the frame. */
    val NAMES = mapOf(
        "left" to 0.18, "center" to 0.5, "right" to 0.82, "top" to 0.16, "middle" to 0.5, "bottom" to 0.72,
        "third1" to 0.33, "third2" to 0.67, "safe" to 0.66
    )
    const val MAX_LEN = 120

    private class P(val s: String, val vars: Map<String, Double>) {
        var i = 0
        fun ws() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) {
                ws()
                if (i >= s.length) return v
                when (s[i]) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }
        fun term(): Double {
            var v = factor()
            while (true) {
                ws()
                if (i >= s.length) return v
                when (s[i]) {
                    '*' -> { i++; v *= factor() }
                    '/' -> { i++; val d = factor(); v = if (d == 0.0) 0.0 else v / d }
                    '%' -> { i++; val d = factor(); v = if (d == 0.0) 0.0 else v % d }
                    else -> return v
                }
            }
        }
        fun factor(): Double {
            ws()
            if (i >= s.length) error("end")
            val c = s[i]
            if (c == '-') { i++; return -factor() }
            if (c == '+') { i++; return factor() }
            if (c == '(') {
                i++
                val v = expr()
                ws()
                if (i >= s.length || s[i] != ')') error(")")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(st, i).toDouble()
            }
            if (c.isLetter() || c == '_') {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i).lowercase()
                ws()
                if (i < s.length && s[i] == '(') {
                    i++
                    val args = mutableListOf<Double>()
                    ws()
                    if (i < s.length && s[i] == ')') i++ else {
                        while (true) {
                            args += expr()
                            ws()
                            if (i < s.length && s[i] == ',') { i++; continue }
                            if (i < s.length && s[i] == ')') { i++; break }
                            error("args")
                        }
                    }
                    return call(name, args)
                }
                return vars[name] ?: NAMES[name] ?: error("var $name")
            }
            error("char $c")
        }
        fun call(n: String, a: List<Double>): Double = when (n) {
            "min" -> a.minOrNull() ?: 0.0
            "max" -> a.maxOrNull() ?: 0.0
            "abs" -> abs(a.getOrElse(0) { 0.0 })
            "sin" -> sin(a.getOrElse(0) { 0.0 })
            "cos" -> cos(a.getOrElse(0) { 0.0 })
            "clamp" -> a.getOrElse(0) { 0.0 }.coerceIn(a.getOrElse(1) { 0.0 }, max(a.getOrElse(1) { 0.0 }, a.getOrElse(2) { 1.0 }))
            "round" -> Math.round(a.getOrElse(0) { 0.0 }).toDouble()
            "odd" -> if (a.getOrElse(0) { 0.0 }.toLong() % 2 != 0L) 1.0 else 0.0
            else -> error("fn $n")
        }
    }

    /** Value of a number or a formula ("0.3+i*0.1", "top", "min(n,4)*0.05"); null when it cannot be read. */
    fun eval(v: Any?, vars: Map<String, Double> = emptyMap()): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> {
            val s = v.trim()
            if (s.isEmpty() || s.length > MAX_LEN) null
            else runCatching {
                val p = P(s, vars)
                val r = p.expr()
                p.ws()
                if (p.i != s.length) null else r
            }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        }
        else -> null
    }
}

/** One layer of a motion template; a "group" draws its [children] at its own place. */
data class MLayer(
    val el: String, val text: String = "", val color: String = "accent", val fill: Boolean = false, val align: String = "center",
    val w: Any? = null, val h: Any? = null, val dx: Any? = null, val dy: Any? = null,
    /** "items" repeats the layer for every item of the scene. */
    val each: String = "",
    /** With [each]: "word" — an item appears when its word is said; otherwise evenly. */
    val sync: String = "",
    val keys: List<MKey> = emptyList(), val children: List<MLayer> = emptyList()
)

/** Key frame of a layer: [t] ms after its anchor [at] (start, item, key, end); values are numbers or formulas. */
data class MKey(
    val t: Any? = 0, val at: String = "", val x: Any? = null, val y: Any? = null, val s: Any? = null, val r: Any? = null,
    val o: Any? = null, val draw: Any? = null, val ease: String = ""
)

data class MotionTpl(val id: String, val name: String, val use: String, val layers: List<MLayer>, val json: String = "")

data class ERule(val cond: List<String>, val kind: String = "", val tpl: String = "", val transition: String = "", val cues: List<String> = emptyList())

data class EditTpl(
    val id: String, val name: String, val use: String,
    val minWords: Int = 4, val maxWords: Int = 14, val pauseMs: Long = 650,
    /** Camera punch-in on the key word of every Nth scene (0 = only from rules). */
    val punchEvery: Int = 2,
    val style: String = "", val accent: String = "",
    val rules: List<ERule> = emptyList(), val json: String = ""
)

/** Problems of a template answer, for the AI to fix. */
class TplResult<T>(val value: T?, val errors: List<String>)

object StudioMotionTpl {
    const val MAX_LAYERS = 24
    const val MAX_KEYS = 12
    const val MAX_SHAPES = 40
    val ELEMENTS = StudioKeys.SHAPES + "group"

    fun id(s: String): String = s.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_').take(40)
        .ifEmpty { "tpl" }

    private fun layer(o: JSONObject, depth: Int, path: String, err: MutableList<String>): MLayer? {
        val el = o.optString("el").trim().lowercase()
        if (el !in ELEMENTS) {
            err += "$path.el: \"$el\" — нужно одно из ${ELEMENTS.joinToString(", ")}"
            return null
        }
        if (el == "group" && depth > 0) {
            err += "$path: группа внутри группы не поддерживается"
            return null
        }
        val keys = o.optJSONArray("keys")?.let { ka ->
            (0 until ka.length()).mapNotNull { ka.optJSONObject(it) }.take(MAX_KEYS).map { k ->
                MKey(k.opt("t") ?: 0, k.optString("at").trim().lowercase(), k.opt("x"), k.opt("y"), k.opt("s"), k.opt("r"), k.opt("o"),
                    k.opt("draw"), k.optString("ease").trim())
            }
        } ?: emptyList()
        if (keys.isEmpty() && depth == 0) err += "$path.keys: нужен хотя бы один ключевой кадр"
        keys.forEachIndexed { k, key ->
            for ((name, v) in listOf("t" to key.t, "x" to key.x, "y" to key.y, "s" to key.s, "r" to key.r, "o" to key.o, "draw" to key.draw)) {
                if (v != null && v != JSONObject.NULL && StudioExpr.eval(v, SAMPLE) == null) err += "$path.keys[$k].$name: не число и не формула («$v»)"
            }
        }
        val children = if (el == "group") o.optJSONArray("children")?.let { ca ->
            (0 until ca.length()).mapNotNull { j -> ca.optJSONObject(j)?.let { layer(it, depth + 1, "$path.children[$j]", err) } }
        } ?: emptyList() else emptyList()
        if (el == "group" && children.isEmpty()) err += "$path.children: у группы нет детей"
        return MLayer(el, o.optString("text").take(60), o.optString("color").ifBlank { "accent" }.take(9), o.optBoolean("fill", false),
            o.optString("align").ifBlank { "center" }.lowercase(), o.opt("w"), o.opt("h"), o.opt("dx"), o.opt("dy"),
            o.optString("each").trim().lowercase(), o.optString("sync").trim().lowercase(), keys, children)
    }

    /** Variables used to check formulas. */
    val SAMPLE = mapOf("i" to 1.0, "n" to 4.0, "num" to 2.0)

    fun parse(reply: String): TplResult<MotionTpl> {
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return TplResult(null, listOf("ответ не JSON-объект"))
        val err = mutableListOf<String>()
        if (o.optString("lumen").isNotEmpty() && o.optString("lumen") != "motion") err += "lumen: должно быть \"motion\""
        val la = o.optJSONArray("layers") ?: return TplResult(null, listOf("нет массива layers"))
        if (la.length() > MAX_LAYERS) err += "layers: не больше $MAX_LAYERS"
        val layers = (0 until min(la.length(), MAX_LAYERS)).mapNotNull { i -> la.optJSONObject(i)?.let { layer(it, 0, "layers[$i]", err) } }
        if (layers.isEmpty()) return TplResult(null, err.ifEmpty { listOf("layers пуст") })
        val name = o.optString("name").trim().ifBlank { o.optString("id") }.take(40).ifBlank { "Шаблон" }
        val t = MotionTpl(id(o.optString("id").ifBlank { name }), name, o.optString("use").trim().take(140), layers,
            o.put("lumen", "motion").put("v", 1).toString())
        return TplResult(t, err)
    }

    /** Text of a layer with the scene put in: $title, $value, $key, $say, $num, $it.icon, $it.label, $it.value. */
    fun fill(text: String, sc: SScene, item: SItem?, num: Int, key: String): String = text
        .replace("\$it.icon", item?.icon ?: "").replace("\$it.label", item?.label ?: "").replace("\$it.value", item?.value ?: "")
        .replace("\$title", sc.title).replace("\$value", sc.items.firstOrNull()?.value?.ifBlank { null } ?: sc.title)
        .replace("\$key", key).replace("\$say", sc.say.take(60)).replace("\$num", num.toString()).trim()

    /**
     * Shapes of [t] for scene [sc] spoken by words [range] of [words] from [startMs] to [endMs], with absolute key times —
     * the same form as the shapes of the AI director. [keyWord] is the index of the key word (null = none).
     */
    fun compile(t: MotionTpl, sc: SScene, words: List<SWord>, range: IntRange, startMs: Long, endMs: Long, keyWord: Int?): List<Pair<SShape, List<Long>>> {
        val out = mutableListOf<Pair<SShape, List<Long>>>()
        val dur = max(1L, endMs - startMs)
        val keyMs = keyWord?.let { words.getOrNull(it)?.startMs } ?: (startMs + min(300L, dur / 3))
        val keyText = keyWord?.let { StudioWords.clean(words.getOrNull(it)?.text ?: "") } ?: ""
        val n = sc.items.size
        fun itemMs(i: Int, sync: String): Long {
            val even = startMs + 200 + i * min(450L, dur * 6 / 10 / max(1, n))
            if (sync != "word" || range.isEmpty()) return even
            val label = sc.items.getOrNull(i)?.label ?: return even
            val w = Studio.tokens(label).firstOrNull { !StudioWords.isStop(it) } ?: Studio.tokens(label).firstOrNull() ?: return even
            return StudioCompact.find(words, range, w)?.let { words[it].startMs } ?: even
        }
        for (ly in t.layers) {
            val copies = if (ly.each == "items") n else 1
            for (i in 0 until copies) {
                if (out.size >= MAX_SHAPES) return out
                val item = if (ly.each == "items") sc.items.getOrNull(i) else null
                val vars = mapOf("i" to i.toDouble(), "n" to max(1, n).toDouble(), "num" to (i + 1).toDouble())
                val itemAt = if (ly.each == "items") itemMs(i, ly.sync) else startMs
                fun anchor(a: String) = when (a) {
                    "key" -> keyMs
                    "end" -> endMs
                    "item" -> itemAt
                    "start" -> startMs
                    else -> if (ly.each == "items") itemAt else startMs
                }
                val times = mutableListOf<Long>()
                var last = Long.MIN_VALUE
                val keys = ly.keys.map { k ->
                    val tm = anchor(k.at) + (StudioExpr.eval(k.t, vars) ?: 0.0).toLong()
                    last = max(last, tm.coerceIn(startMs, endMs + 500))
                    times += last
                    SKey(0, 0, ev(k.x, vars, -0.5, 1.5), ev(k.y, vars, -0.5, 1.5), ev(k.s, vars, 0.0, 6.0), ev(k.r, vars, -1080.0, 1080.0),
                        ev(k.o, vars, 0.0, 1.0), ev(k.draw, vars, 0.0, 1.0), StudioKeys.EASES.firstOrNull { it.equals(k.ease, true) } ?: "")
                }
                if (keys.isEmpty()) continue
                val parts = if (ly.el == "group") ly.children else listOf(ly.copy(dx = null, dy = null))
                for (ch in parts) {
                    val txt = fill(ch.text, sc, item, i + 1, keyText)
                    if ((ch.el == "text" || ch.el == "emoji") && txt.isEmpty()) continue
                    val dx = ev(ch.dx, vars, -1.0, 1.0) ?: 0f
                    val dy = ev(ch.dy, vars, -1.0, 1.0) ?: 0f
                    val ks = StudioKeys.resolve(keys).zip(keys).map { (st, k) -> k.copy(x = st.x + dx, y = st.y + dy) }
                    val isText = ch.el == "text" || ch.el == "emoji"
                    out += SShape(ch.el, txt, ch.color, ch.fill, ev(ch.w ?: ly.w, vars, 0.01, 1.5) ?: if (isText) 0.08f else 0.2f,
                        ev(ch.h ?: ly.h, vars, 0.005, 1.5) ?: if (isText) 0.08f else 0.2f, ks, ch.align) to times.toList()
                }
            }
        }
        return out
    }

    private fun ev(v: Any?, vars: Map<String, Double>, lo: Double, hi: Double): Float? = StudioExpr.eval(v, vars)?.coerceIn(lo, hi)?.toFloat()

    /** Sample scene for previews. */
    fun sample(): SScene = SScene("Вот четыре шага к цели: план, дело, проверка и отдых", "icons", "Четыре шага",
        listOf(SItem("🗺", "план", "40"), SItem("⚙️", "дело", "75"), SItem("✅", "проверка", "55"), SItem("😴", "отдых", "90")), listOf("цели"))
}

object StudioEditTpl {
    /** Conditions a rule may test. */
    val CONDS = listOf("first", "last", "number", "step", "compare", "list", "quote", "emoji", "question", "long", "short", "pause", "loud", "else")

    fun parse(reply: String): TplResult<EditTpl> {
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return TplResult(null, listOf("ответ не JSON-объект"))
        val err = mutableListOf<String>()
        if (o.optString("lumen").isNotEmpty() && o.optString("lumen") != "edit") err += "lumen: должно быть \"edit\""
        val pace = o.optJSONObject("pace") ?: JSONObject()
        val sw = pace.optJSONArray("sceneWords")
        val camera = o.optJSONObject("camera") ?: JSONObject()
        val rules = mutableListOf<ERule>()
        o.optJSONArray("rules")?.let { ra ->
            for (i in 0 until min(ra.length(), 30)) {
                val r = ra.optJSONObject(i) ?: continue
                val cond = r.optString("if").split(Regex("[,&]| and ")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (cond.isEmpty()) {
                    err += "rules[$i].if: пусто"
                    continue
                }
                cond.forEach { c ->
                    val base = c.removePrefix("!").substringBefore(':')
                    if (base !in CONDS && base != "keyword" && base != "every") err += "rules[$i].if: неизвестное условие «$c»"
                }
                val d = r.optJSONObject("do") ?: JSONObject()
                val kind = d.optString("kind").trim().lowercase()
                if (kind.isNotEmpty() && kind !in Studio.KINDS) err += "rules[$i].do.kind: «$kind» — нужно одно из ${Studio.KINDS.joinToString(", ")}"
                val tr = d.optString("transition").trim().lowercase()
                if (tr.isNotEmpty() && tr !in StudioDirector.TRANSITIONS) err += "rules[$i].do.transition: «$tr»"
                val cues = d.optJSONArray("cues")?.let { ca -> (0 until ca.length()).map { ca.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
                cues.forEach { c -> if (cue(c) == null) err += "rules[$i].do.cues: не понял «$c»" }
                rules += ERule(cond, kind.takeIf { it in Studio.KINDS } ?: "", d.optString("tpl").trim(), tr.takeIf { it in StudioDirector.TRANSITIONS } ?: "", cues)
            }
        }
        if (rules.isEmpty()) err += "rules: нужно хотя бы одно правило"
        val name = o.optString("name").trim().ifBlank { o.optString("id") }.take(40).ifBlank { "Подход" }
        val t = EditTpl(StudioMotionTpl.id(o.optString("id").ifBlank { name }), name, o.optString("use").trim().take(140),
            (sw?.optInt(0, 4) ?: 4).coerceIn(1, 20), (sw?.optInt(1, 14) ?: 14).coerceIn(2, 30), pace.optLong("cutOnPause", 650).coerceIn(150, 3000),
            camera.optInt("punchEvery", 2).coerceIn(0, 10), o.optString("style").trim().lowercase(), o.optString("accent").trim(),
            rules, o.put("lumen", "edit").put("v", 1).toString())
        return TplResult(if (rules.isEmpty()) null else t, err)
    }

    /** "zoom@key", "sfx:ding@start", "pop:ВАЖНО:⚠️@key" → (type, args, anchor); null when unknown. */
    fun cue(s: String): Triple<String, List<String>, String>? {
        val anchor = s.substringAfterLast('@', "key").trim().lowercase().ifEmpty { "key" }
        val body = s.substringBeforeLast('@', s)
        val parts = body.split(':').map { it.trim() }
        val type = parts[0].lowercase()
        if (anchor !in listOf("key", "start", "end")) return null
        return when (type) {
            "zoom" -> Triple(type, emptyList(), anchor)
            "sfx" -> parts.getOrNull(1)?.lowercase()?.takeIf { it in StudioDirector.SFX }?.let { Triple(type, listOf(it), anchor) }
            "pop" -> parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { Triple(type, parts.drop(1).take(2), anchor) }
            else -> null
        }
    }

    /** What a scene has: the kind the rules see in it and other marks. */
    fun features(words: List<SWord>, r: IntRange, si: Int, count: Int, base: StudioDirector.DScene, loud: FloatArray?): Set<String> {
        val f = mutableSetOf<String>()
        if (si == 0) f += "first"
        if (si == count - 1) f += "last"
        when (base.kind) {
            "stat", "chart" -> f += "number"
            "step" -> f += "step"
            "compare" -> f += "compare"
            "list" -> f += "list"
            "quote" -> f += "quote"
            "icons", "big" -> f += "emoji"
        }
        if (words[r.last].text.trimEnd().endsWith("?")) f += "question"
        val n = r.count()
        if (n >= 10) f += "long"
        if (n <= 4) f += "short"
        if (r.first > 0 && words[r.first].startMs - words[r.first - 1].endMs >= 700) f += "pause"
        if (loud != null && r.any { loud.getOrElse(it) { 0f } >= 0.85f }) f += "loud"
        return f
    }

    private fun matches(c: String, f: Set<String>, words: List<SWord>, r: IntRange, si: Int): Boolean {
        val neg = c.startsWith("!")
        val x = c.removePrefix("!")
        val ok = when {
            x.startsWith("keyword:") -> x.removePrefix("keyword:").split('|').map { Studio.norm(it) }.filter { it.isNotEmpty() }
                .any { k -> r.any { Studio.norm(words[it].text).startsWith(k) } }
            x.startsWith("every:") -> x.removePrefix("every:").toIntOrNull()?.let { it > 0 && si % it == 0 } ?: false
            else -> x in f
        }
        return ok != neg
    }

    /** A whole plan by the template, without AI: rules apply in order (later ones win); "else" when none matched. */
    fun direct(t: EditTpl, words: List<SWord>, loud: FloatArray? = null, title: String = ""): StudioDirector.Direction {
        if (words.isEmpty()) return StudioDirector.Direction(title, emptySet(), emptyList())
        val ranges = StudioWords.split(words, t.pauseMs, t.minWords, max(t.minWords + 1, t.maxWords))
        val scenes = ranges.mapIndexed { si, r ->
            var s = StudioRules.scene(words, r, si, loud)
            val f = features(words, r, si, ranges.size, s, loud)
            val key = StudioWords.keyWord(words, r) ?: r.first
            var hit = false
            var cues: MutableList<SCue>? = null
            fun apply(rule: ERule) {
                if (rule.kind.isNotEmpty()) s = s.copy(kind = StudioDirector.fitKind(rule.kind, s.items))
                if (rule.tpl.isNotEmpty()) s = s.copy(kind = "tpl:" + rule.tpl)
                if (rule.transition.isNotEmpty()) s = s.copy(transition = rule.transition)
                if (rule.cues.isNotEmpty()) {
                    val list = cues ?: mutableListOf<SCue>().also { cues = it }
                    for (c in rule.cues) {
                        val (type, args, anchor) = cue(c) ?: continue
                        val at = when (anchor) {
                            "start" -> r.first
                            "end" -> r.last
                            else -> key
                        }
                        list += when (type) {
                            "zoom" -> SCue("zoom", at)
                            "sfx" -> SCue("sfx", at, args[0])
                            else -> SCue("pop", at, args[0].take(32), args.getOrElse(1) { "" }.take(8))
                        }
                    }
                }
            }
            for (rule in t.rules) {
                if (rule.cond == listOf("else")) continue
                if (rule.cond.all { matches(it, f, words, r, si) }) {
                    apply(rule)
                    hit = true
                }
            }
            if (!hit) t.rules.filter { it.cond == listOf("else") }.forEach { apply(it) }
            val all = (cues ?: s.cues.toMutableList())
            if (t.punchEvery > 0 && si % t.punchEvery == 0 && all.none { it.type == "zoom" }) all += SCue("zoom", key)
            s.copy(cues = all)
        }
        return StudioDirector.Direction(title, emptySet(), StudioDirector.cover(scenes, words.size))
    }

    /** Short description of the rules for the screen. */
    fun summary(t: EditTpl): String = t.rules.joinToString("\n") { r ->
        "• " + r.cond.joinToString(" и ") + " → " + listOfNotNull(
            r.kind.ifEmpty { null }, r.tpl.ifEmpty { null }?.let { "tpl:$it" }, r.transition.ifEmpty { null },
            r.cues.joinToString(", ").ifEmpty { null }
        ).joinToString(" · ")
    }
}

/** Minimal RFC 6902 JSON Patch (add, replace, remove) for small AI edits of a template. */
object JsonPatch {
    private fun tokens(path: String): List<String> = path.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }

    /** [doc] changed by [ops]; null when an operation cannot be applied. */
    fun apply(doc: JSONObject, ops: JSONArray): JSONObject? {
        val root = JSONObject(doc.toString())
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: return null
            val kind = op.optString("op")
            val path = op.optString("path")
            if (!path.startsWith("/")) return null
            val tk = tokens(path)
            var cur: Any = root
            for (t in tk.dropLast(1)) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(t) ?: return null
                    is JSONArray -> t.toIntOrNull()?.let { cur.opt(it) } ?: return null
                    else -> return null
                }
            }
            val last = tk.last()
            val value = op.opt("value")
            when (cur) {
                is JSONObject -> when (kind) {
                    "add", "replace" -> cur.put(last, value ?: return null)
                    "remove" -> if (cur.has(last)) cur.remove(last) else return null
                    else -> return null
                }
                is JSONArray -> {
                    val idx = if (last == "-") cur.length() else last.toIntOrNull() ?: return null
                    when (kind) {
                        "add" -> {
                            if (idx > cur.length() || value == null) return null
                            val items = (0 until cur.length()).map { cur.get(it) }.toMutableList()
                            items.add(idx, value)
                            while (cur.length() > 0) cur.remove(cur.length() - 1)
                            items.forEach { cur.put(it) }
                        }
                        "replace" -> if (idx < cur.length() && value != null) cur.put(idx, value) else return null
                        "remove" -> if (idx < cur.length()) cur.remove(idx) else return null
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        return root
    }

    /** The reply as a patch (a JSON array, or {"patch":[…]}); null when it is not one. */
    fun ops(reply: String): JSONArray? {
        val s = reply.trim()
        val a = s.indexOf('[')
        val o = s.indexOf('{')
        if (a >= 0 && (o < 0 || a < o)) return runCatching { JSONArray(s.substring(a, s.lastIndexOf(']') + 1)) }.getOrNull()
        return JsonText.firstObject(s)?.let { runCatching { JSONObject(it).optJSONArray("patch") }.getOrNull() }
    }
}

/** Lines about the saved templates for the prompts of the directors (one per template, few tokens). */
object StudioTplCatalog {
    fun motionLine(t: MotionTpl) = "tpl:${t.id} — ${t.use.ifBlank { t.name }} (title, items)"

    /** Added to the visuals of the full director. */
    fun visuals(motions: List<MotionTpl>): String = if (motions.isEmpty()) "" else
        " Свои шаблоны (kind \"tpl:id\", данные в title и items): " + motions.take(20).joinToString("; ") { motionLine(it) } + "."

    /** Added to the kinds of the compact prompts. */
    fun kinds(motions: List<MotionTpl>): String = if (motions.isEmpty()) "" else " " + motions.take(20).joinToString(" ") { "tpl:" + it.id }
}

/** Saved templates: <dir>/motion/<id>.json and <dir>/edit/<id>.json (the JSON the AI sent, checked). */
class StudioTplStore(private val dir: java.io.File) {
    private fun sub(kind: String) = java.io.File(dir, kind).apply { mkdirs() }

    fun motions(): List<MotionTpl> = sub("motion").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { runCatching { StudioMotionTpl.parse(it.readText()).value }.getOrNull() }

    fun edits(): List<EditTpl> = sub("edit").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { runCatching { StudioEditTpl.parse(it.readText()).value }.getOrNull() }

    fun motionMap(): Map<String, MotionTpl> = motions().associateBy { it.id }

    fun edit(id: String): EditTpl? = edits().firstOrNull { it.id == id }

    /** Id not used yet: "name", "name_2", … ([own] keeps the id of the template being replaced). */
    fun freeId(kind: String, id: String, own: String? = null): String {
        if (id == own || !java.io.File(sub(kind), "$id.json").exists()) return id
        var k = 2
        while (java.io.File(sub(kind), "${id}_$k.json").exists()) k++
        return "${id}_$k"
    }

    fun saveMotion(t: MotionTpl) = java.io.File(sub("motion"), t.id + ".json").writeText(JSONObject(t.json).put("id", t.id).toString(2))
    fun saveEdit(t: EditTpl) = java.io.File(sub("edit"), t.id + ".json").writeText(JSONObject(t.json).put("id", t.id).toString(2))
    fun delete(kind: String, id: String) = java.io.File(sub(kind), "$id.json").delete()
}

/** A template from a video: what the AI gets about it and how its answer (a way of editing + motions) is split. */
object StudioVideoTpl {
    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Shot lengths in seconds from cut times (the last shot runs to [durationMs]). */
    fun shots(cutMs: List<Long>, durationMs: Long): List<Double> {
        val pts = (listOf(0L) + cutMs.filter { it in 1 until durationMs }.sorted() + durationMs).distinct()
        return pts.zipWithNext().map { (a, b) -> (b - a) / 1000.0 }.filter { it > 0 }
    }

    /**
     * Text about the video for the AI: title, measured metrics, shot lengths, the first lines of speech with times
     * (each piece of speech shorter than [maxSpeech] chars in total).
     */
    fun brief(title: String, channel: String, durationMs: Long, isShort: Boolean, metrics: List<String>, cutMs: List<Long>,
              cues: List<Pair<Long, String>>, maxSpeech: Int = 2500): String = buildString {
        append("Видео: «").append(title).append('»')
        if (channel.isNotBlank()) append(" · канал ").append(channel)
        append(" · ").append(clock(durationMs)).append(if (isShort) " · Shorts (вертикальное)" else "").append('\n')
        if (metrics.isNotEmpty()) {
            append("Замеры (приблизительно):\n")
            metrics.forEach { append("- ").append(it).append('\n') }
        }
        val sh = shots(cutMs, durationMs)
        if (sh.size > 1) {
            append("Длины планов по раскадровке, с (первые 40): ")
            append(sh.take(40).joinToString(" ") { String.format(java.util.Locale.US, "%.1f", it) }).append('\n')
            append("Средний план ").append(String.format(java.util.Locale.US, "%.1f", sh.average())).append(" с, самый короткий ")
                .append(String.format(java.util.Locale.US, "%.1f", sh.minOrNull() ?: 0.0)).append(" с\n")
        }
        if (cues.isNotEmpty()) {
            append("Речь с таймкодами:\n")
            var n = 0
            var lastText = ""
            for ((ms, text) in cues) {
                val tx = text.trim()
                if (tx.isEmpty() || tx == lastText) continue
                lastText = tx
                val line = "[${clock(ms)}] $tx\n"
                if (n + line.length > maxSpeech) {
                    append("…\n")
                    break
                }
                append(line)
                n += line.length
            }
        }
    }

    /** The parts of a reply: the edit template, the motion templates and the notes. */
    data class Parts(val edit: String?, val motions: List<String>, val notes: String)

    fun parse(reply: String): Parts? {
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val edit = o.optJSONObject("edit")?.toString()
        val motions = mutableListOf<String>()
        o.optJSONArray("motions")?.let { a -> for (i in 0 until min(a.length(), 3)) a.optJSONObject(i)?.let { motions += it.toString() } }
        o.optJSONObject("motion")?.let { motions += it.toString() }
        if (edit == null && motions.isEmpty()) return null
        return Parts(edit, motions, o.optString("notes").trim().take(1500))
    }

    /** Grid for a contact sheet of [n] frames: columns and rows. */
    fun grid(n: Int, wide: Boolean): Pair<Int, Int> {
        if (n <= 0) return 0 to 0
        val cols = if (wide) min(4, n) else min(6, n)
        return cols to (n + cols - 1) / cols
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Checks and parses the answers of the algo_* prompts (pure, covered by AlgoParseTest).
 * Every parser returns null (or reports a problem) instead of guessing: the caller retries once with
 * [fixNote] and then falls back to the text score.
 */
object AlgoParse {
    /** Grades of a batch: s[i] in -1..3 for item i+1; labels by 1-based number. */
    class Grades(val s: IntArray, val labels: Map<Int, String>)

    class Topic(
        val phrase: String,
        val core: String,
        val include: List<String>,
        val exclude: List<String>,
        val adjacent: List<String>,
        val keywords: List<String>,
        val negative: List<String>
    )

    class NicheName(val id: String, val name: String, val about: String, val intent: String)

    class Opp(
        val niche: String,
        val title: String,
        val angle: String,
        val why: String,
        val nextTo: List<String>,
        val format: String,
        val keywords: List<String>,
        val risk: String,
        val confidence: String
    ) {
        fun toJson(): JSONObject = JSONObject().put("niche", niche).put("title", title).put("angle", angle).put("why", why)
            .put("next_to", JSONArray(nextTo)).put("format", format).put("keywords", JSONArray(keywords)).put("risk", risk)
            .put("confidence", confidence)

        companion object {
            fun from(o: JSONObject) = Opp(
                o.optString("niche"), o.optString("title"), o.optString("angle"), o.optString("why"), strings(o.optJSONArray("next_to")),
                o.optString("format", "long"), strings(o.optJSONArray("keywords")), o.optString("risk"), o.optString("confidence", "low")
            )
        }
    }

    class Pitch(
        val angle: String,
        val answers: List<String>,
        val titles: List<String>,
        val duration: String,
        val hook: String,
        val nextTo: List<Pair<String, String>>,
        val description: String,
        val tags: List<String>
    )

    class Result<T>(val value: T?, val problem: String)

    /** Appendix for the one retry after a failed check. */
    fun fixNote(problem: String) = "\n\nПредыдущий ответ не прошёл проверку: $problem. Верни только JSON по схеме, без пояснений."

    private fun obj(text: String): JSONObject? = try {
        JsonText.firstObject(text)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }

    /**
     * {"s":[…],"n":{"номер":"метка"}} with exactly [count] grades in -1..3. The legacy {"relevant":[numbers]}
     * is accepted too (listed → 2, others → 0).
     */
    fun grades(text: String, count: Int): Result<Grades> {
        val o = obj(text) ?: return Result(null, "нет JSON-объекта")
        val s = o.optJSONArray("s")
        if (s == null) {
            val legacy = o.optJSONArray("relevant") ?: return Result(null, "нет массива s")
            val yes = (0 until legacy.length()).map { legacy.optInt(it, -1) }.toSet()
            return Result(Grades(IntArray(count) { if ((it + 1) in yes) 2 else 0 }, emptyMap()), "")
        }
        if (s.length() != count) return Result(null, "в массиве s ${s.length()} оценок, а нужно ровно $count")
        val grades = IntArray(count)
        for (i in 0 until count) {
            val v = s.opt(i)
            val g = when (v) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: return Result(null, "оценка №${i + 1} не число")
                else -> return Result(null, "оценка №${i + 1} не число")
            }
            if (g !in -1..3) return Result(null, "оценка №${i + 1} вне шкалы -1..3")
            grades[i] = g
        }
        val labels = HashMap<Int, String>()
        o.optJSONObject("n")?.let { n ->
            n.keys().forEach { k ->
                val i = k.trim().trimStart('#').toIntOrNull() ?: return@forEach
                val l = n.optString(k).trim()
                if (i in 1..count && l.isNotEmpty()) labels[i] = l.take(40)
            }
        }
        return Result(Grades(grades, labels), "")
    }

    fun topic(text: String): Result<Topic> {
        val o = obj(text) ?: return Result(null, "нет JSON-объекта")
        val core = o.optString("core").trim().ifBlank { o.optString("domain").trim() }
        if (core.isBlank()) return Result(null, "нет поля core")
        val kw = strings(o.optJSONArray("keywords"))
        if (kw.isEmpty()) return Result(null, "нет ключевых слов")
        return Result(
            Topic(
                o.optString("phrase").trim(), core, strings(o.optJSONArray("include")).take(10), strings(o.optJSONArray("exclude")).take(8),
                strings(o.optJSONArray("adjacent")).take(8), kw.take(40), strings(o.optJSONArray("negative")).take(20)
            ), ""
        )
    }

    /** Niche names for the known [ids]; unknown ids are ignored, missing ones reported. */
    fun niches(text: String, ids: Set<String>): Result<Map<String, NicheName>> {
        val o = obj(text) ?: return Result(null, "нет JSON-объекта")
        val a = o.optJSONArray("niches") ?: return Result(null, "нет массива niches")
        val out = LinkedHashMap<String, NicheName>()
        for (i in 0 until a.length()) {
            val n = a.optJSONObject(i) ?: continue
            val id = n.optString("id").trim().trim('[', ']')
            val name = n.optString("name").trim()
            if (id in ids && name.isNotEmpty()) out[id] = NicheName(id, name.take(48), n.optString("about").trim().take(240), n.optString("intent", "other"))
        }
        if (out.isEmpty()) return Result(null, "ни одна ниша не названа")
        return Result(out, "")
    }

    fun opportunities(text: String, niches: Set<String>, nodes: Set<String>): Result<List<Opp>> {
        val o = obj(text) ?: return Result(null, "нет JSON-объекта")
        val a = o.optJSONArray("items") ?: return Result(null, "нет массива items")
        val out = ArrayList<Opp>()
        for (i in 0 until a.length()) {
            val it = a.optJSONObject(i) ?: continue
            val opp = Opp.from(it)
            if (opp.title.isBlank()) continue
            out += Opp(
                opp.niche.trim('[', ']').takeIf { it in niches } ?: "", opp.title.take(120), opp.angle, opp.why,
                opp.nextTo.map { it.trim('[', ']') }.filter { it in nodes }, opp.format.takeIf { it in setOf("long", "short", "series") } ?: "long",
                opp.keywords.take(8), opp.risk, opp.confidence.takeIf { it in setOf("high", "medium", "low") } ?: "low"
            )
        }
        if (out.isEmpty()) return Result(null, "нет ни одной возможности с названием")
        return Result(out.take(7), "")
    }

    fun pitch(text: String, nodes: Set<String>): Result<Pitch> {
        val o = obj(text) ?: return Result(null, "нет JSON-объекта")
        val titles = strings(o.optJSONArray("titles")).map { it.take(100) }
        if (titles.isEmpty()) return Result(null, "нет названий")
        val next = o.optJSONArray("next_to")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("id").trim('[', ']') to it.optString("why") } }
        }.orEmpty().filter { it.first in nodes }
        return Result(
            Pitch(
                o.optString("angle"), strings(o.optJSONArray("answers")).map { it.trim('[', ']') }.filter { it in nodes }, titles.take(8),
                o.optString("duration"), o.optString("hook"), next, o.optString("description"), strings(o.optJSONArray("tags")).take(5)
            ), ""
        )
    }

    private val idRef = Regex("""\[([a-z]\d{1,4})]""")

    /** Ids cited in a Markdown answer ([v12], [c3], [g2]) that are not in the data. */
    fun unknownIds(text: String, ids: Set<String>): List<String> = idRef.findAll(text).map { it.groupValues[1] }.filter { it !in ids }.distinct().toList()

    /** Markdown with cited ids that exist in the data turned into plain names (for sharing). */
    fun cite(text: String, names: Map<String, String>): String = idRef.replace(text) { m -> names[m.groupValues[1]]?.let { "«$it»" } ?: m.value }
}

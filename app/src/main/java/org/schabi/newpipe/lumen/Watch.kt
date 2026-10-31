/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Guesses the language of a short text (video title + description) without a model:
 * the script first, then distinctive letters (Cyrillic) or common words and diacritics (Latin).
 * Returns an ISO 639-1 code, or "" when unsure — callers keep unsure videos.
 */
object LangGuess {
    private val word = Regex("""\p{L}+""")

    private val latinWords: Map<String, Set<String>> = mapOf(
        "en" to "the and of to in is you for how with what this your are why best new".split(" ").toSet(),
        "de" to "der die und das ist nicht mit ein eine für wie ich auf den dem zu im".split(" ").toSet(),
        "fr" to "le les des et est pour une dans du avec comment je ce qui sur pas".split(" ").toSet(),
        "es" to "el los las y que en es para con cómo por una qué del lo".split(" ").toSet(),
        "pt" to "o os as e que em é para com não uma como você do da".split(" ").toSet(),
        "it" to "il di che e per non con una come sono della gli nel".split(" ").toSet(),
        "pl" to "i w na nie się jak to z do jest co czy dla".split(" ").toSet(),
        "tr" to "ve bir bu için ile ne nasıl çok da de mi en".split(" ").toSet()
    )

    private val latinMarks: Map<String, String> = mapOf(
        "de" to "ßäöü", "es" to "ñ¿¡", "pt" to "ãõ", "pl" to "łąęśżźćń", "tr" to "ğış", "fr" to "èêëàùœç", "it" to "àèìòù"
    )

    fun guess(text: String): String {
        val s = text.lowercase()
        var cyr = 0; var lat = 0; var kana = 0; var hangul = 0; var han = 0; var arabic = 0; var deva = 0; var greek = 0; var hebrew = 0; var thai = 0
        for (ch in s) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.LATIN -> lat++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.GREEK -> greek++
                Character.UnicodeScript.HEBREW -> hebrew++
                Character.UnicodeScript.THAI -> thai++
                else -> {}
            }
        }
        val scripts = listOf("cyr" to cyr, "lat" to lat, "cjk" to kana + han, "ko" to hangul, "ar" to arabic, "hi" to deva,
            "el" to greek, "he" to hebrew, "th" to thai)
        val (top, n) = scripts.maxByOrNull { it.second } ?: return ""
        if (n < 3) return ""
        return when (top) {
            "cyr" -> cyrillic(s)
            "lat" -> latin(s)
            "cjk" -> if (kana > 0) "ja" else "zh"
            else -> top
        }
    }

    private fun cyrillic(s: String): String {
        val has = { chars: String -> chars.any { it in s } }
        return when {
            has("ђјљњћџ") -> "sr"
            has("әғқңөұүһ") -> "kk"
            // і appears with ы/э only in Belarusian; Ukrainian has і/ї/є/ґ and none of ы/э/ъ/ё
            has("ў") || (has("і") && has("ыэ")) -> "be"
            has("іїєґ") && !has("ыэъё") -> "uk"
            else -> "ru"
        }
    }

    private fun latin(s: String): String {
        val words = word.findAll(s).map { it.value }.toList()
        val score = latinWords.mapValues { (_, set) -> words.count { it in set } }.toMutableMap()
        latinMarks.forEach { (lang, marks) -> if (marks.any { it in s }) score[lang] = (score[lang] ?: 0) + 2 }
        val ranked = score.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: 0
        return if (best.value >= 2 && best.value > second) best.key else ""
    }
}

/** Search region and original language options (empty code = any). */
object Regions {
    val countries: List<Pair<String, String>> get() = listOf(
        "" to t("Любая страна", "Any country"),
        "RU" to t("Россия", "Russia"), "UA" to t("Украина", "Ukraine"), "BY" to t("Беларусь", "Belarus"),
        "KZ" to t("Казахстан", "Kazakhstan"), "US" to t("США", "USA"), "GB" to t("Великобритания", "UK"),
        "CA" to t("Канада", "Canada"), "AU" to t("Австралия", "Australia"), "DE" to t("Германия", "Germany"),
        "FR" to t("Франция", "France"), "ES" to t("Испания", "Spain"), "IT" to t("Италия", "Italy"),
        "PL" to t("Польша", "Poland"), "TR" to t("Турция", "Turkey"), "BR" to t("Бразилия", "Brazil"),
        "MX" to t("Мексика", "Mexico"), "IN" to t("Индия", "India"), "JP" to t("Япония", "Japan"),
        "KR" to t("Южная Корея", "South Korea")
    )

    val languages: List<Pair<String, String>> get() = listOf(
        "" to t("Любой язык", "Any language"),
        "ru" to t("Русский", "Russian"), "uk" to t("Украинский", "Ukrainian"), "be" to t("Белорусский", "Belarusian"),
        "kk" to t("Казахский", "Kazakh"), "en" to t("Английский", "English"), "de" to t("Немецкий", "German"),
        "fr" to t("Французский", "French"), "es" to t("Испанский", "Spanish"), "pt" to t("Португальский", "Portuguese"),
        "it" to t("Итальянский", "Italian"), "pl" to t("Польский", "Polish"), "tr" to t("Турецкий", "Turkish"),
        "ja" to t("Японский", "Japanese"), "ko" to t("Корейский", "Korean"), "zh" to t("Китайский", "Chinese"),
        "ar" to t("Арабский", "Arabic"), "hi" to t("Хинди", "Hindi"), "id" to t("Индонезийский", "Indonesian")
    )

    fun countryName(code: String) = countries.firstOrNull { it.first == code }?.second ?: code
    fun languageName(code: String) = languages.firstOrNull { it.first == code }?.second ?: code

    /** True when the video passes the "original language" filter; unsure guesses pass. */
    fun languageOk(want: String, text: String): Boolean {
        if (want.isBlank()) return true
        val g = LangGuess.guess(text)
        return g.isEmpty() || g == want
    }
}

/**
 * AI relevance filter for monitoring: new videos are judged against the project's topic in batches of 30
 * (prompt "relevance"); without AI, or when a reply is unusable, the keyword filter decides.
 * Rejected video ids are remembered (rejected.json) so they are not fetched and judged again.
 */
object WatchFilter {
    private const val FILE = "rejected.json"
    private const val KEEP = 5000

    fun rejected(pid: String): Set<String> = Store.readFile(pid, FILE)?.let { raw ->
        try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    } ?: emptySet()

    private fun remember(pid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val all = (rejected(pid).toList() + ids).distinct().takeLast(KEEP)
        Store.writeFile(pid, FILE, JSONArray(all).toString())
    }

    /** Batch line for the prompt: "3. Title — Channel. Short description". */
    fun line(i: Int, v: Video): String =
        "$i. ${v.title} — ${v.channel}" + (if (v.description.isNotBlank()) ". " + v.description.replace('\n', ' ').take(150) else "")

    /** Splits [list] by 1-based [relevant] numbers. */
    fun split(list: List<Video>, relevant: Set<Int>): Pair<List<Video>, List<Video>> {
        val keep = list.filterIndexed { i, _ -> (i + 1) in relevant }
        val drop = list.filterIndexed { i, _ -> (i + 1) !in relevant }
        return keep to drop
    }

    /** Returns (kept, rejected) and remembers the rejected ids. */
    fun apply(p: Project, list: List<Video>, extraQueries: List<String> = emptyList()): Pair<List<Video>, List<Video>> {
        if (list.isEmpty()) return list to emptyList()
        // translated queries join the keywords, so the keyword fallback keeps videos in other languages too
        val kw = TopicFilter.keywordsOf(p, extraQueries.joinToString(" "))
        fun byKeywords(batch: List<Video>) = batch.partition { Relevance.matches(kw, it.title + " " + it.channel + " " + it.description) }
        val prov = Provider.active()
        val keep = ArrayList<Video>()
        val drop = ArrayList<Video>()
        for (batch in list.chunked(30)) {
            val (k, d) = if (prov == null) {
                byKeywords(batch)
            } else {
                try {
                    val data = batch.mapIndexed { i, v -> line(i + 1, v) }.joinToString("\n")
                    val prompt = Prompts.get("relevance").replace("{phrase}", p.phrase)
                        .replace("{domain}", p.domain.ifBlank { "-" }).replace("{data}", data)
                        .replace("{lang}", if (Settings.lang == "en") "English" else "русский")
                    val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 600, task = "watch_filter", pid = p.id)
                    if (TopicFilter.hasVerdict(r.text)) split(batch, TopicFilter.parseIndexes(r.text)) else byKeywords(batch)
                } catch (e: Exception) {
                    L.w("watch", "AI filter failed, keywords used: ${e.message}")
                    byKeywords(batch)
                }
            }
            keep.addAll(k)
            drop.addAll(d)
        }
        remember(p.id, drop.map { it.id })
        return keep to drop
    }
}

/**
 * Monitoring in any language: the topic's queries are translated by AI into the main YouTube languages once
 * and cached (watch_langs.json, refreshed when the phrase, domain or queries change). Without AI only the original
 * queries are searched.
 */
object WatchLangs {
    const val FILE = "watch_langs.json"

    /** Languages searched by default: the largest YouTube audiences. */
    val DEFAULT = listOf("en", "es", "pt", "hi", "ar", "ja", "ko", "de", "fr", "ru", "tr", "id", "zh", "it", "pl", "uk")

    fun signature(p: Project): String = (p.phrase + "|" + p.domain + "|" + p.effectiveQueries().joinToString("|") + "|" + DEFAULT.joinToString(",")).hashCode().toString()

    /** {"queries":{"en":["…"],…}} → lang → queries; broken or missing parts are skipped. */
    fun parse(text: String): Map<String, List<String>> {
        val raw = JsonText.firstObject(text) ?: return emptyMap()
        val q = try { JSONObject(raw).optJSONObject("queries") } catch (e: Exception) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (lang in q.keys()) {
            val code = lang.trim().lowercase().take(5)
            val list = when (val v = q.opt(lang)) {
                is JSONArray -> (0 until v.length()).map { v.optString(it) }
                is String -> listOf(v)
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 120 }
            if (code.isNotEmpty() && list.isNotEmpty()) out[code] = list
        }
        return out
    }

    /** Original queries first, then up to [perLang] per language; duplicates (any case) dropped, at most [max]. */
    fun merge(original: List<String>, translated: Map<String, List<String>>, perLang: Int = 2, max: Int = 36): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(q: String) {
            val k = q.trim().lowercase()
            if (k.isNotEmpty() && seen.add(k) && out.size < max) out.add(q.trim())
        }
        original.forEach(::add)
        translated.values.forEach { list -> list.take(perLang).forEach(::add) }
        return out
    }

    fun cached(p: Project): Map<String, List<String>>? = Store.readFile(p.id, FILE)?.let { raw ->
        try {
            val o = JSONObject(raw)
            if (o.optString("sig") != signature(p)) null else parse(o.toString())
        } catch (e: Exception) {
            null
        }
    }

    /** Translated queries for the project (cached); empty without AI or when the reply is unusable. */
    fun translated(p: Project): Map<String, List<String>> {
        cached(p)?.let { return it }
        val prov = Provider.active() ?: return emptyMap()
        return try {
            val prompt = Prompts.get("watch_translate").replace("{phrase}", p.phrase).replace("{domain}", p.domain.ifBlank { "-" })
                .replace("{data}", p.effectiveQueries().joinToString("\n")).replace("{langs}", DEFAULT.joinToString(", "))
            val r = Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 1500, task = "watch_translate", pid = p.id)
            val map = parse(r.text)
            if (map.isNotEmpty()) {
                val q = JSONObject()
                map.forEach { (k, v) -> q.put(k, JSONArray(v)) }
                Store.writeFile(p.id, FILE, JSONObject().put("sig", signature(p)).put("at", System.currentTimeMillis()).put("queries", q).toString())
            }
            map
        } catch (e: Exception) {
            L.w("watch", "translate failed, original queries used: ${e.message}")
            emptyMap()
        }
    }
}

/**
 * Short digest of a monitoring run for the notification: how many, in which languages, from which channels and,
 * with AI, what about (prompt "watch_digest", answered in the interface language). The last digests are kept in digests.json.
 */
object WatchDigest {
    const val FILE = "digests.json"
    private const val KEEP = 30

    fun langOf(v: Video): String = LangGuess.guess(v.title + " " + v.description.take(300))

    /** Language code → count, most frequent first; "?" for videos whose language is unclear. */
    fun langCounts(list: List<Video>): List<Pair<String, Int>> =
        list.groupingBy { langOf(it).ifEmpty { "?" } }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { if (it.key == "?") 1 else 0 }.thenBy { it.key })
            .map { it.key to it.value }

    /** "EN 5 · RU 3 · ES 2 · +2" (unknown languages last, at most [max] shown). */
    fun langLine(list: List<Video>, max: Int = 5): String {
        val c = langCounts(list)
        val shown = c.take(max).joinToString(" · ") { (l, n) -> l.uppercase() + " " + n }
        return if (c.size > max) "$shown · +${c.size - max}" else shown
    }

    fun topChannels(list: List<Video>, max: Int = 3): List<Pair<String, Int>> =
        list.filter { it.channel.isNotBlank() }.groupingBy { it.channel }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(max).map { it.key to it.value }

    fun title(name: String, n: Int, list: List<Video>): String {
        val langs = langCounts(list).count { it.first != "?" }
        val base = t("«$name»: новых видео — $n", "\"$name\": $n new videos")
        return if (langs > 1) base + t(" на $langs языках", " in $langs languages") else base
    }

    /** Body without AI: languages, channels, a few titles. */
    fun plain(list: List<Video>, titles: Int = 4): String {
        val sb = StringBuilder()
        sb.append(t("Языки: ", "Languages: ")).append(langLine(list))
        val ch = topChannels(list)
        if (ch.isNotEmpty()) sb.append("\n").append(t("Каналы: ", "Channels: ")).append(ch.joinToString(", ") { (c, n) -> if (n > 1) "$c ($n)" else c })
        list.sortedByDescending { it.views }.take(titles).forEach { sb.append("\n• ").append(it.title.take(90)) }
        if (list.size > titles) sb.append("\n").append(t("…и ещё ${list.size - titles}", "…and ${list.size - titles} more"))
        return sb.toString()
    }

    /** Prompt line: "3. [es] Title — Channel. Description". */
    fun line(i: Int, v: Video): String {
        val l = langOf(v)
        return "$i. " + (if (l.isNotEmpty()) "[$l] " else "") + v.title + " — " + v.channel +
            (if (v.description.isNotBlank()) ". " + v.description.replace('\n', ' ').take(160) else "")
    }

    /** AI "what about" text; empty without AI or on failure. */
    fun ai(p: Project, list: List<Video>): String {
        val prov = Provider.active() ?: return ""
        return try {
            val data = list.take(60).mapIndexed { i, v -> line(i + 1, v) }.joinToString("\n")
            val prompt = Prompts.get("watch_digest").replace("{phrase}", p.phrase).replace("{domain}", p.domain.ifBlank { "-" })
                .replace("{count}", list.size.toString()).replace("{data}", data)
                .replace("{lang}", if (Settings.lang == "en") "English" else "русский")
            Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, maxTokens = 700, task = "watch_digest", pid = p.id).text.trim()
        } catch (e: Exception) {
            L.w("watch", "digest failed: ${e.message}")
            ""
        }
    }

    /** Notification body: AI text (or the plain digest) plus the language line and the filter note. */
    fun body(list: List<Video>, aiText: String, dropped: Int): String {
        val main = if (aiText.isNotBlank()) aiText.take(700) + "\n" + t("Языки: ", "Languages: ") + langLine(list) else plain(list)
        return main + if (dropped > 0) t("\nОтсеяно как не по теме: $dropped", "\nFiltered out as off-topic: $dropped") else ""
    }

    data class Entry(val at: Long, val title: String, val body: String)

    fun history(pid: String): List<Entry> = Store.readFile(pid, FILE)?.let { raw ->
        try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Entry(it.optLong("at"), it.optString("title"), it.optString("body")) }
        } catch (e: Exception) {
            emptyList()
        }
    } ?: emptyList()

    fun remember(pid: String, e: Entry) {
        val all = (history(pid) + e).takeLast(KEEP)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("at", it.at).put("title", it.title).put("body", it.body)) }
        Store.writeFile(pid, FILE, a.toString())
    }
}

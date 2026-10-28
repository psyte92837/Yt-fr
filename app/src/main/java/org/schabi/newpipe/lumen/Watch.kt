/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray

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
        "ar" to t("Арабский", "Arabic"), "hi" to t("Хинди", "Hindi")
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
    fun apply(p: Project, list: List<Video>): Pair<List<Video>, List<Video>> {
        if (list.isEmpty()) return list to emptyList()
        val kw = TopicFilter.keywordsOf(p)
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

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * "Language game" of a niche (Wittgenstein: meaning is use inside a form of life).
 * Everything a project collected — titles, descriptions, tags, channel names and descriptions, comments,
 * transcripts — is split into layers: what creators say (the official game) and what the audience says
 * (the live game). Locally and for free we measure the lexicon of each layer, the words only the audience uses
 * (shibboleths), set phrases, title formulas, forms of address and psycholinguistic markers
 * (I / we / they, modality, hedges, absolutism, affect). The result is the protocol lumen.langgame v1:
 * a compact JSON that the built-in AI (or any AI app on the phone, via Share) reads to write
 * a video / podcast script or SEO texts in the audience's own language.
 * Pure functions, covered by LangGameTest.
 */
object LangGame {
    const val PROTOCOL = "lumen.langgame"
    const val VERSION = 1
    const val FILE = "langgame.json"

    // layers of the corpus
    const val TITLE = "title"
    const val DESC = "desc"
    const val TAG = "tag"
    const val CHANNEL = "channel"
    const val COMMENT = "comment"
    const val SPEECH = "speech"
    val CREATOR = setOf(TITLE, DESC, TAG, CHANNEL, SPEECH)

    /** One text of the corpus. [weight] — likes of a comment, views of a title (log-scaled in [analyse]). */
    data class Doc(val layer: String, val text: String, val weight: Long = 0, val src: String = "")

    // ------------------------------------------------------------------ psycholinguistic markers
    /** Marker → word stems (prefix match on a lower-cased token, ё→е). Pennebaker/LIWC-style, RU + EN. */
    val MARKERS: Map<String, List<String>> = linkedMapOf(
        "i" to listOf("я", "меня", "мне", "мной", "мой", "моя", "мое", "мои", "моих", "i", "me", "my", "mine", "myself"),
        "we" to listOf("мы", "нас", "нам", "нами", "наш", "наша", "наше", "наши", "we", "us", "our", "ours"),
        "you" to listOf("ты", "тебя", "тебе", "тобой", "твой", "твоя", "твои", "вы", "вас", "вам", "вами", "ваш", "ваша", "ваши", "you", "your", "yours"),
        "they" to listOf("они", "их", "им", "ими", "них", "they", "them", "their", "theirs"),
        "neg" to listOf("не", "нет", "ни", "никак", "no", "not", "never", "nothing", "don't", "doesn't", "can't", "isn't"),
        "must" to listOf("надо", "нужно", "должен", "должна", "должны", "обязан", "необходимо", "придется", "must", "should", "have to", "need"),
        "can" to listOf("можно", "могу", "может", "можешь", "можем", "сможет", "получится", "can", "could", "able"),
        "hedge" to listOf("кажется", "вроде", "наверное", "возможно", "походу", "видимо", "пожалуй", "типа", "как бы", "maybe", "perhaps", "probably", "seems", "kinda", "sort of"),
        "certain" to listOf("точно", "однозначно", "определенно", "реально", "факт", "100%", "конечно", "безусловно", "definitely", "certainly", "clearly", "absolutely", "literally"),
        "absolute" to listOf("всегда", "никогда", "все", "всё", "никто", "ничего", "полностью", "совершенно", "каждый", "always", "never", "everyone", "nobody", "completely", "totally", "every"),
        "pos" to listOf("спасибо", "класс", "круто", "супер", "отлично", "люблю", "полезн", "лучш", "топ", "огонь", "красав", "помог", "thanks", "great", "love", "awesome", "best", "helpful", "amazing"),
        "negaff" to listOf("бесит", "ужас", "кошмар", "плохо", "отстой", "развод", "обман", "боюсь", "страх", "тревог", "злит", "ненавиж", "устал", "hate", "awful", "terrible", "scam", "afraid", "fear", "worst", "angry"),
        "cause" to listOf("потому", "поэтому", "так как", "из-за", "следовательно", "значит", "because", "therefore", "so that", "due to"),
        "insight" to listOf("понял", "поняла", "осознал", "думаю", "считаю", "знаю", "понимаю", "realize", "think", "know", "understand", "believe")
    )

    /** Group forms of address: how a creator calls the audience and how people call each other. */
    val ADDRESS = listOf(
        "ребят", "ребята", "друзья", "народ", "люди", "коллеги", "подписчики", "братцы", "девочки", "дамы", "мужики", "пацаны", "бро",
        "guys", "folks", "friends", "everyone", "fam", "bro", "y'all"
    )

    private val token = Regex("""[\p{L}\p{Nd}][\p{L}\p{Nd}'’%-]*""")
    private val emojiRx = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]""")

    fun norm(s: String) = s.lowercase().replace('ё', 'е')

    /** All lower-cased word tokens, stop words kept (needed for markers). */
    fun words(text: String): List<String> = token.findAll(norm(text)).map { it.value.trim('\'', '’', '-') }.filter { it.isNotEmpty() }.toList()

    /** Content tokens: no stop words, no numbers, at least 3 letters. */
    fun content(text: String): List<String> = Words.tokens(norm(text))

    /** Per 1000 words of each marker in [texts]. Multi-word markers are matched as phrases. */
    fun markers(texts: List<String>): JSONObject {
        val counts = LinkedHashMap<String, Int>()
        var total = 0
        for (t in texts) {
            val w = words(t)
            total += w.size
            val joined = " " + w.joinToString(" ") + " "
            for ((m, list) in MARKERS) {
                var n = 0
                for (s in list) {
                    n += if (' ' in s) Regex(Regex.escape(" $s ")).findAll(joined).count()
                    else if (s.length <= 3 || s == "все" || s == "всё") w.count { it == s }
                    else w.count { it.startsWith(s) }
                }
                counts[m] = (counts[m] ?: 0) + n
            }
        }
        val o = JSONObject().put("words", total)
        for ((m, n) in counts) o.put(m, if (total == 0) 0.0 else round1(n * 1000.0 / total))
        // ratios that read as a psychological stance
        val i = counts["i"] ?: 0
        val we = counts["we"] ?: 0
        val they = counts["they"] ?: 0
        o.put("we_vs_they", if (we + they == 0) 0.0 else round2(we.toDouble() / (we + they)))
        o.put("i_vs_we", if (i + we == 0) 0.0 else round2(i.toDouble() / (i + we)))
        val q = texts.count { it.contains('?') }
        val ex = texts.count { it.contains('!') }
        o.put("questions", if (texts.isEmpty()) 0.0 else round2(q.toDouble() / texts.size))
        o.put("exclaims", if (texts.isEmpty()) 0.0 else round2(ex.toDouble() / texts.size))
        return o
    }

    // ------------------------------------------------------------------ lexicon
    data class Term(val w: String, val n: Int, val score: Double)

    fun freq(texts: List<String>): Map<String, Int> {
        val f = HashMap<String, Int>()
        texts.forEach { t -> content(t).forEach { f[it] = (f[it] ?: 0) + 1 } }
        return f
    }

    /**
     * Words typical of [a] against [b]: smoothed log-ratio of relative frequencies times log frequency,
     * at least [minN] uses in [a]. Audience vs creators gives shibboleths; creators vs audience — jargon.
     */
    fun distinctive(a: Map<String, Int>, b: Map<String, Int>, n: Int = 25, minN: Int = 3): List<Term> {
        val ta = a.values.sum().coerceAtLeast(1).toDouble()
        val tb = b.values.sum().coerceAtLeast(1).toDouble()
        return a.entries.asSequence().filter { it.value >= minN }.map { (w, c) ->
            val ra = (c + 0.5) / ta
            val rb = ((b[w] ?: 0) + 0.5) / tb
            Term(w, c, round2(ln(ra / rb) * ln(1.0 + c)))
        }.filter { it.score > 0.5 }.sortedByDescending { it.score }.take(n).toList()
    }

    /** Words frequent in both layers: the shared core of the game. */
    fun shared(a: Map<String, Int>, b: Map<String, Int>, n: Int = 25): List<Term> =
        a.entries.filter { (b[it.key] ?: 0) >= 2 && it.value >= 2 }
            .map { Term(it.key, it.value + (b[it.key] ?: 0), 0.0) }
            .sortedByDescending { it.n }.take(n)

    /** Set phrases: n-grams (2..3 words) repeated in at least [minN] different texts. */
    fun formulas(texts: List<String>, n: Int = 20, minN: Int = 2): List<Term> {
        val f = HashMap<String, Int>()
        for (t in texts) {
            val tk = content(t)
            val seen = HashSet<String>()
            for (k in 2..3) for (i in 0..tk.size - k) {
                val g = tk.subList(i, i + k)
                if (g.toSet().size < k) continue
                seen += g.joinToString(" ")
            }
            seen.forEach { f[it] = (f[it] ?: 0) + 1 }
        }
        // a trigram makes its own bigrams redundant when it is as frequent
        val keep = f.filter { it.value >= minN }
        val tri = keep.filterKeys { it.count { c -> c == ' ' } == 2 }
        return keep.entries.filter { e ->
            e.key.count { it == ' ' } == 2 || tri.none { (k, v) -> v >= e.value && k.contains(e.key) }
        }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(n).map { Term(it.key, it.value, 0.0) }
    }

    // ------------------------------------------------------------------ titles
    /** Shape of a title: N for numbers, ? / ! / : / | / ( ) / CAPS / emoji marks, first two words kept. */
    fun titleShape(title: String): String {
        val w = words(title)
        val head = w.take(2).joinToString(" ") { if (it.any(Char::isDigit)) "N" else it }
        val marks = buildList {
            if (title.any(Char::isDigit)) add("N")
            if ('?' in title) add("?")
            if ('!' in title) add("!")
            if (':' in title || " - " in title || " — " in title) add(":")
            if ('|' in title) add("|")
            if ('(' in title || '[' in title) add("()")
            if (caps(title)) add("CAPS")
            if (emojiRx.containsMatchIn(title)) add("emoji")
        }
        return (head + " …" + if (marks.isEmpty()) "" else " [" + marks.joinToString(" ") + "]").trim()
    }

    private fun caps(s: String) = Regex("""(?<!\p{L})\p{Lu}{3,}(?!\p{L})""").containsMatchIn(s)

    fun titles(titles: List<String>): JSONObject {
        val n = titles.size.coerceAtLeast(1).toDouble()
        fun share(p: (String) -> Boolean) = round2(titles.count(p) / n)
        val firsts = HashMap<String, Int>()
        titles.forEach { t -> words(t).firstOrNull()?.takeIf { !it.all(Char::isDigit) }?.let { firsts[it] = (firsts[it] ?: 0) + 1 } }
        val shapes = HashMap<String, Int>()
        titles.forEach { shapes[titleShape(it)] = (shapes[titleShape(it)] ?: 0) + 1 }
        return JSONObject()
            .put("count", titles.size)
            .put("avg_words", if (titles.isEmpty()) 0.0 else round1(titles.sumOf { words(it).size } / n))
            .put("avg_chars", if (titles.isEmpty()) 0.0 else round1(titles.sumOf { it.length } / n))
            .put("numbers", share { it.any(Char::isDigit) })
            .put("questions", share { '?' in it })
            .put("exclaims", share { '!' in it })
            .put("colon", share { ':' in it || " - " in it || " — " in it || '|' in it })
            .put("brackets", share { '(' in it || '[' in it })
            .put("caps", share { caps(it) })
            .put("emoji", share { emojiRx.containsMatchIn(it) })
            .put("you", share { t -> words(t).any { it in MARKERS.getValue("you") } })
            .put("first_words", terms(firsts.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(12).map { Term(it.key, it.value, 0.0) }))
            .put("shapes", terms(shapes.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(10).map { Term(it.key, it.value, 0.0) }))
    }

    // ------------------------------------------------------------------ address, emoji, latin
    fun address(texts: List<String>): JSONObject {
        var ty = 0
        var vy = 0
        val group = HashMap<String, Int>()
        for (t in texts) {
            val w = words(t)
            ty += w.count { it in setOf("ты", "тебя", "тебе", "тобой", "твой", "твоя", "твои", "твоё", "твое") }
            vy += w.count { it in setOf("вы", "вас", "вам", "вами", "ваш", "ваша", "ваши", "ваше") }
            w.filter { it in ADDRESS }.forEach { group[it] = (group[it] ?: 0) + 1 }
        }
        return JSONObject().put("ty", ty).put("vy", vy)
            .put("register", if (ty + vy < 3) "unknown" else if (ty > vy * 1.5) "ty" else if (vy > ty * 1.5) "vy" else "mixed")
            .put("group", terms(group.entries.sortedByDescending { it.value }.take(8).map { Term(it.key, it.value, 0.0) }))
    }

    fun emoji(texts: List<String>, n: Int = 12): JSONArray {
        val f = HashMap<String, Int>()
        texts.forEach { t -> emojiRx.findAll(t).forEach { f[it.value] = (f[it.value] ?: 0) + 1 } }
        return terms(f.entries.sortedByDescending { it.value }.take(n).map { Term(it.key, it.value, 0.0) })
    }

    /** Latin words inside a mostly Cyrillic corpus: anglicisms, brands, slang. Empty for a Latin corpus. */
    fun latin(texts: List<String>, n: Int = 20): JSONArray {
        val all = texts.flatMap { content(it) }
        val lat = all.filter { w -> w.all { it in 'a'..'z' || it.isDigit() || it == '-' } }
        if (all.isEmpty() || lat.size * 2 > all.size) return JSONArray()
        val f = lat.groupingBy { it }.eachCount()
        return terms(f.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(n).map { Term(it.key, it.value, 0.0) })
    }

    // ------------------------------------------------------------------ the whole game
    /**
     * Builds the lumen.langgame v1 file. [meta] holds project name, phrase and domain.
     * Comments are weighted by likes when picking quotes; titles by views when picking examples.
     */
    fun analyse(docs: List<Doc>, meta: JSONObject = JSONObject(), now: Long = System.currentTimeMillis()): JSONObject {
        val by = docs.filter { it.text.isNotBlank() }.groupBy { it.layer }
        fun texts(vararg l: String) = l.flatMap { by[it].orEmpty() }.map { it.text }
        val creatorTexts = texts(*CREATOR.toTypedArray())
        val audience = texts(COMMENT)
        val fa = freq(audience)
        val fc = freq(creatorTexts)
        val corpus = JSONObject()
        for (l in listOf(TITLE, DESC, TAG, CHANNEL, COMMENT, SPEECH)) {
            val list = by[l].orEmpty()
            corpus.put(l, JSONObject().put("docs", list.size).put("words", list.sumOf { words(it.text).size }))
        }
        val comments = by[COMMENT].orEmpty()
        val voc = JSONObject()
        val vocCounts = HashMap<String, Int>()
        val vocSamples = HashMap<String, MutableList<Doc>>()
        comments.forEach { d ->
            Voc.classify(d.text).forEach {
                vocCounts[it] = (vocCounts[it] ?: 0) + 1
                vocSamples.getOrPut(it) { mutableListOf() } += d
            }
        }
        for (c in Voc.categories) {
            voc.put(c, JSONObject().put("share", if (comments.isEmpty()) 0.0 else round2((vocCounts[c] ?: 0).toDouble() / comments.size))
                .put("examples", JSONArray(vocSamples[c].orEmpty().sortedByDescending { it.weight }.map { clip(it.text, 160) }.distinct().take(5))))
        }
        val quotes = comments.filter { !Voc.isSpam(it.text) && words(it.text).size in 4..60 }
            .sortedByDescending { it.weight }.map { clip(it.text, 220) }.distinct().take(20)
        val topTitles = by[TITLE].orEmpty().sortedByDescending { it.weight }.map { it.text.trim() }.distinct().take(15)
        return JSONObject()
            .put("protocol", PROTOCOL).put("version", VERSION).put("at", now)
            .put("project", meta)
            .put("corpus", corpus)
            .put("lexicon", JSONObject()
                .put("audience", terms(Words.top(audience.asSequence().map(::norm), 30).map { Term(it.first, it.second, 0.0) }))
                .put("creators", terms(Words.top(creatorTexts.asSequence().map(::norm), 30).map { Term(it.first, it.second, 0.0) }))
                .put("shibboleths", terms(distinctive(fa, fc)))
                .put("jargon", terms(distinctive(fc, fa)))
                .put("shared", terms(shared(fa, fc))))
            .put("formulas", JSONObject()
                .put("audience", terms(formulas(audience)))
                .put("creators", terms(formulas(creatorTexts))))
            .put("titles", titles(texts(TITLE)).put("top", JSONArray(topTitles)))
            .put("address", JSONObject().put("audience", address(audience)).put("creators", address(creatorTexts)))
            .put("markers", JSONObject().put("audience", markers(audience)).put("creators", markers(creatorTexts)))
            .put("emoji", emoji(audience + texts(TITLE)))
            .put("latin", latin(audience + creatorTexts))
            .put("voc", voc)
            .put("quotes", JSONArray(quotes))
    }

    /** True when the file is a lumen.langgame of a version this app reads. */
    fun valid(o: JSONObject?): Boolean = o != null && o.optString("protocol") == PROTOCOL && o.optInt("version") in 1..VERSION

    // ------------------------------------------------------------------ AI reading (interpretation)
    /** Keys of the AI reading (prompt langgame_read); [reading] keeps only these. */
    val READING_KEYS = listOf(
        "form_of_life", "rules", "roles", "shibboleths", "taboo", "metaphors", "desire", "fear", "ideal",
        "defenses", "tone", "voice_rules", "seo_terms", "hooks"
    )

    /** Keeps the known fields of the AI answer: strings and arrays of strings or {word, meaning} objects. */
    fun reading(o: JSONObject): JSONObject {
        val src = o.optJSONObject("reading") ?: o
        val out = JSONObject()
        for (k in READING_KEYS) {
            val v = src.opt(k) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) out.put(k, v.trim())
                is JSONArray -> {
                    val a = JSONArray()
                    for (i in 0 until v.length()) {
                        when (val e = v.opt(i)) {
                            is String -> if (e.isNotBlank()) a.put(e.trim())
                            is JSONObject -> {
                                val w = e.optString("word", e.optString("term", e.optString("name"))).trim()
                                if (w.isNotEmpty()) a.put(JSONObject().put("word", w).put("meaning", e.optString("meaning", e.optString("why")).trim()))
                            }
                        }
                    }
                    if (a.length() > 0) out.put(k, a)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ intents (what to write)
    enum class Kind(val id: String, val ru: String, val en: String) {
        VIDEO("video", "Сценарий видео", "Video script"),
        SHORTS("shorts", "Сценарий Shorts", "Shorts script"),
        PODCAST("podcast", "Подкаст", "Podcast"),
        SEO("seo", "SEO: название, описание, теги", "SEO: title, description, tags");

        /** Studio task the script goes to, null for SEO. */
        val studio: String? get() = when (this) {
            VIDEO -> "video"
            SHORTS -> "shorts"
            PODCAST -> "voice"
            SEO -> null
        }

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id } ?: VIDEO
        }
    }

    /** What the AI must produce for every kind; the answer format is the same Markdown for all (see [sections]). */
    fun task(k: Kind): String = when (k) {
        Kind.VIDEO -> "Напиши сценарий YouTube-видео на 6-10 минут: хук в первые 15 секунд на языке аудитории, обещание, 4-7 смысловых блоков, " +
            "в каждом — вопрос или боль зрителя его словами и ответ, мини-итог, призыв в конце. Разделы: # Название, ## Хук, ## Сценарий, ## Названия, ## Описание, ## Теги, ## Словарь."
        Kind.SHORTS -> "Напиши сценарий Shorts на 30-50 секунд: первая фраза — крючок их словами, одна мысль, резкий поворот, короткий финал. " +
            "Разделы: # Название, ## Хук, ## Сценарий, ## Названия, ## Теги, ## Словарь."
        Kind.PODCAST -> "Напиши сценарий выпуска подкаста на 15-25 минут для одного ведущего: вступление-история, 3-5 тем из реальных вопросов и болей слушателей, " +
            "живые разговорные переходы, цитаты слушателей своими словами, вывод. Разделы: # Название, ## Хук, ## Сценарий, ## Названия, ## Описание, ## Словарь."
        Kind.SEO -> "Подготовь SEO-пакет для YouTube: 5 вариантов названия (до 70 символов, ключевое слово в начале, формулы названий ниши), " +
            "описание 150-250 слов (первые 2 строки — ключ и обещание), главы с таймкодами-заготовками, 15-25 тегов (слова аудитории и поисковые варианты), " +
            "3 идеи превью-текста до 4 слов. Разделы: # Название, ## Названия, ## Описание, ## Главы, ## Теги, ## Превью, ## Словарь."
    }

    /** Rules shared by every intent: how to speak inside the game. */
    const val RULES = "Правила языковой игры:\n" +
        "- Пиши словами аудитории: бери shibboleths, formulas.audience и цитаты; жаргон авторов (jargon) — только если он есть и у аудитории.\n" +
        "- Обращение как в нише: address.audience.register (ty — на «ты», vy — на «вы»), групповые обращения из address.\n" +
        "- Ритм и тон по markers: много вопросов — строй текст вопросами; высокий absolute или negaff — признай боль без осуждения, не спорь с чувством, давай опору; " +
        "высокий hedge — меньше категоричности; we_vs_they выше 0.5 — говори «мы», ниже — назови «их» и отстройся.\n" +
        "- Названия по формулам titles.shapes и first_words; не копируй чужие названия дословно.\n" +
        "- Опирайся на voc: вопросы, боли, просьбы и возражения зрителей — это темы блоков; цитаты пересказывай, не выдумывай фактов и цифр.\n" +
        "- Если есть reading — следуй voice_rules, избегай taboo, используй metaphors и hooks.\n" +
        "- В разделе ## Сценарий только произносимый текст, сцены через пустую строку, без ремарок и пометок времени.\n" +
        "- В разделе ## Словарь 5-10 строк «- слово — зачем оно здесь»."

    /** The game cut down for a prompt: no counts the AI does not need, lists shortened. */
    fun compact(game: JSONObject, maxChars: Int = 14000): String {
        val g = JSONObject(game.toString())
        g.remove("at")
        var s = g.toString()
        // shorten long lists until it fits
        var cap = 20
        while (s.length > maxChars && cap > 4) {
            cap -= 4
            shrink(g, cap)
            s = g.toString()
        }
        return s.take(maxChars)
    }

    private fun shrink(o: JSONObject, cap: Int) {
        for (k in o.keys().asSequence().toList()) {
            when (val v = o.opt(k)) {
                is JSONObject -> shrink(v, cap)
                is JSONArray -> if (v.length() > cap) o.put(k, JSONArray((0 until cap).map { v.opt(it) }))
            }
        }
    }

    /**
     * Self-contained request for any AI: protocol name, the task, the rules and the data.
     * The same text goes to [Llm] inside the app (prompt langgame_write wraps it) and to an AI app via Share.
     */
    fun brief(game: JSONObject, k: Kind, topic: String, wish: String, lang: String): String = buildString {
        append("Протокол $PROTOCOL v$VERSION: ниже языковая игра аудитории YouTube-ниши (по Витгенштейну: значение = употребление в форме жизни), ")
        append("собранная из названий, описаний, тегов, каналов, комментариев и расшифровок. Поля: lexicon.shibboleths — слова «своих» (аудитория говорит, авторы нет), ")
        append("lexicon.jargon — язык авторов, formulas — устойчивые обороты, titles — формулы названий, address — обращение, markers — психолингвистика на 1000 слов, ")
        append("voc — вопросы/боли/просьбы/возражения, quotes — живые цитаты, reading — толкование (если есть).\n\n")
        append("Задача: ").append(task(k)).append('\n')
        append("Тема: ").append(topic.ifBlank { game.optJSONObject("project")?.optString("phrase").orEmpty().ifBlank { "выбери сам самую горячую из voc" } }).append('\n')
        if (wish.isNotBlank()) append("Пожелание автора: ").append(wish.trim()).append('\n')
        append("Язык ответа: ").append(lang).append("\n\n")
        append(RULES).append("\n\n")
        append("Ответ — только Markdown с разделами из задачи.\n\n")
        append("```json\n").append(compact(game)).append("\n```")
    }

    // ------------------------------------------------------------------ answer
    /** Markdown answer → title (# line) and sections (## heading → body), headings lower-cased. */
    fun sections(md: String): Pair<String, LinkedHashMap<String, String>> {
        var title = ""
        val out = LinkedHashMap<String, String>()
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() {
            cur?.let { out[it] = buf.toString().trim() }
            buf.clear()
        }
        for (raw in md.replace("\r", "").lines()) {
            val l = raw.trimEnd()
            when {
                l.startsWith("## ") -> {
                    flush()
                    cur = norm(l.removePrefix("## ").trim().trim('#', ' ', '*'))
                }
                l.startsWith("# ") && title.isEmpty() && cur == null -> title = l.removePrefix("# ").trim().trim('*')
                else -> if (cur != null) buf.append(raw).append('\n')
            }
        }
        flush()
        return title to out
    }

    /** Spoken text of the answer for Lumen Studio: hook + script, markup removed, scenes split by blank lines. */
    fun script(md: String): String {
        val (_, s) = sections(md)
        fun find(vararg keys: String) = s.entries.firstOrNull { e -> keys.any { e.key.startsWith(it) } }?.value.orEmpty()
        val hook = find("хук", "hook")
        val body = find("сценарий", "script", "текст")
        val text = listOf(hook, body).filter { it.isNotBlank() }.joinToString("\n\n")
        return text.lines().map { l ->
            l.trim().replace(Regex("""^([-*•]|\d+[.)])\s+"""), "").replace(Regex("""^\[[^\]]*]\s*"""), "")
                .replace(Regex("""^\(?\d{1,2}:\d{2}(:\d{2})?\)?\s*[-—:]?\s*"""), "").replace("**", "").replace("__", "")
        }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Tags of the answer: lines or comma-separated, # removed, distinct, YouTube limit 500 chars. */
    fun tags(md: String): List<String> {
        val body = sections(md).second.entries.firstOrNull { it.key.startsWith("тег") || it.key.startsWith("tag") }?.value ?: return emptyList()
        val out = LinkedHashSet<String>()
        body.split('\n', ',').map { it.trim().removePrefix("-").removePrefix("•").trim().trimStart('#').trim() }.filter { it.isNotEmpty() && it.length <= 60 }
            .forEach { out += it }
        var len = 0
        return out.takeWhile { len += it.length + 1; len <= 500 }
    }

    // ------------------------------------------------------------------ helpers
    private fun terms(list: List<Term>) = JSONArray().also { a ->
        list.forEach { a.put(if (it.score != 0.0) JSONObject().put("w", it.w).put("n", it.n).put("score", it.score) else JSONObject().put("w", it.w).put("n", it.n)) }
    }

    fun clip(s: String, n: Int): String {
        val t = s.replace(Regex("""\s+"""), " ").trim()
        return if (t.length <= n) t else t.take(n - 1).substringBeforeLast(' ').ifBlank { t.take(n - 1) } + "…"
    }

    private fun round1(d: Double) = Math.round(d * 10) / 10.0
    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}

/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ways to animate a voice-over, from free to rich:
 * RULES and RHYTHM need no AI (0 tokens), LITE asks a fast model with a compact protocol,
 * HYBRID lets a smart model improve only the key scenes of a rules draft, FULL is the detailed protocol v2.
 */
enum class StudioApproach(val id: String) {
    RULES("rules"), RHYTHM("rhythm"), LITE("lite"), HYBRID("hybrid"), FULL("full");

    val usesAi: Boolean get() = this == LITE || this == HYBRID || this == FULL

    companion object {
        fun of(id: String): StudioApproach = entries.firstOrNull { it.id == id } ?: FULL
    }
}

/** Common pieces of the local directors: scene splitting, key words and emoji by meaning. */
object StudioWords {
    private val STOP = setOf(
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она", "так", "его", "но", "да", "ты",
        "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "её", "мне", "было", "вот", "от", "меня", "еще", "ещё", "нет", "о",
        "из", "ему", "теперь", "когда", "даже", "ну", "вдруг", "ли", "если", "уже", "или", "ни", "быть", "был", "него", "до",
        "вас", "нибудь", "опять", "уж", "вам", "ведь", "там", "потом", "себя", "ничего", "ей", "может", "они", "тут", "где",
        "есть", "надо", "ней", "для", "мы", "тебя", "их", "чем", "была", "сам", "чтоб", "без", "будто", "чего", "раз", "тоже",
        "себе", "под", "будет", "ж", "тогда", "кто", "этот", "того", "потому", "этого", "какой", "совсем", "ним", "здесь",
        "этом", "один", "почти", "мой", "тем", "чтобы", "нее", "сейчас", "были", "куда", "зачем", "всех", "никогда", "можно",
        "при", "наконец", "два", "об", "другой", "хоть", "после", "над", "больше", "тот", "через", "эти", "нас", "про", "всего",
        "них", "какая", "много", "разве", "три", "эту", "моя", "впрочем", "хорошо", "свою", "этой", "перед", "иногда",
        "лучше", "чуть", "том", "нельзя", "такой", "им", "более", "всегда", "конечно", "всю", "между", "это", "эта", "эм",
        "короче", "типа", "просто", "очень", "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "to", "of", "in",
        "on", "for", "with", "it", "this", "that", "you", "we", "they", "i", "be", "so", "just", "very", "at", "as", "by", "from",
        "have", "has", "do", "does", "not", "your", "our", "my", "can", "will", "what", "how", "if", "there", "about", "like"
    )

    fun isStop(w: String) = Studio.norm(w).let { it.length <= 2 || it in STOP }

    /** Emoji by the beginning of a word (Russian and English stems). */
    private val EMOJI = listOf(
        "деньг" to "💰", "денег" to "💰", "money" to "💰", "доход" to "💵", "зарпл" to "💵", "income" to "💵", "цен" to "🏷", "price" to "🏷",
        "врем" to "⏱", "time" to "⏱", "минут" to "⏱", "час" to "⏰", "hour" to "⏰", "дн" to "📅", "day" to "📅", "год" to "📆", "year" to "📆",
        "иде" to "💡", "idea" to "💡", "мысл" to "💭", "рост" to "📈", "растёт" to "📈", "растет" to "📈", "grow" to "📈", "паден" to "📉", "drop" to "📉",
        "ошиб" to "❌", "mistake" to "❌", "error" to "❌", "правил" to "✅", "верн" to "✅", "right" to "✅", "цел" to "🎯", "goal" to "🎯",
        "иссл" to "🔍", "поиск" to "🔍", "search" to "🔍", "research" to "🔍", "книг" to "📚", "book" to "📚", "учеб" to "🎓", "learn" to "🎓",
        "код" to "💻", "code" to "💻", "компьют" to "💻", "программ" to "👨‍💻", "телефон" to "📱", "phone" to "📱", "интернет" to "🌐", "web" to "🌐",
        "мир" to "🌍", "world" to "🌍", "город" to "🏙", "city" to "🏙", "дом" to "🏠", "home" to "🏠", "работ" to "💼", "work" to "💼", "job" to "💼",
        "люд" to "👥", "people" to "👥", "человек" to "🧑", "person" to "🧑", "команд" to "🤝", "team" to "🤝", "друг" to "🫂", "friend" to "🫂",
        "здоров" to "❤️", "health" to "❤️", "сердц" to "❤️", "сон" to "😴", "sleep" to "😴", "ед" to "🍽", "food" to "🍽", "вод" to "💧", "water" to "💧",
        "спорт" to "🏃", "sport" to "🏃", "мозг" to "🧠", "brain" to "🧠", "энерг" to "⚡", "energy" to "⚡", "огон" to "🔥", "fire" to "🔥", "hot" to "🔥",
        "опасн" to "⚠️", "danger" to "⚠️", "риск" to "⚠️", "risk" to "⚠️", "безопас" to "🛡", "secur" to "🛡", "ключ" to "🔑", "key" to "🔑",
        "секрет" to "🤫", "secret" to "🤫", "вопрос" to "❓", "question" to "❓", "ответ" to "💬", "answer" to "💬", "видео" to "🎬", "video" to "🎬",
        "музык" to "🎵", "music" to "🎵", "фото" to "📷", "photo" to "📷", "ии" to "🤖", "нейросет" to "🤖", "ai" to "🤖", "робот" to "🤖", "robot" to "🤖",
        "данн" to "📊", "data" to "📊", "график" to "📊", "chart" to "📊", "процесс" to "⚙️", "process" to "⚙️", "систем" to "⚙️", "system" to "⚙️",
        "успех" to "🏆", "success" to "🏆", "побед" to "🏆", "win" to "🏆", "старт" to "🚀", "start" to "🚀", "запуск" to "🚀", "launch" to "🚀",
        "звезд" to "⭐", "star" to "⭐", "любов" to "💖", "love" to "💖", "сил" to "💪", "power" to "💪", "strong" to "💪", "план" to "🗺", "plan" to "🗺",
        "машин" to "🚗", "car" to "🚗", "путешеств" to "✈️", "travel" to "✈️", "погод" to "🌤", "weather" to "🌤", "природ" to "🌿", "nature" to "🌿"
    )

    fun emojiFor(word: String): String? {
        val n = Studio.norm(word)
        if (n.length < 2) return null
        return EMOJI.firstOrNull { (stem, _) -> if (stem.length <= 3) n == stem || (n.startsWith(stem) && n.length <= stem.length + 3) else n.startsWith(stem) }?.second
    }

    /** Ends a sentence or a clause. */
    fun endsSentence(w: String) = w.trimEnd().lastOrNull()?.let { it in ".!?…" } ?: false
    fun endsClause(w: String) = w.trimEnd().lastOrNull()?.let { it in ".!?…;:,—" } ?: false

    /**
     * Scenes of [words]: a new scene after a pause of [pauseMs], after the end of a sentence (when the scene has
     * [minWords]), or at a comma once the scene is long; never longer than [maxWords].
     */
    fun split(words: List<SWord>, pauseMs: Long = 650, minWords: Int = 4, maxWords: Int = 14): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        for (i in words.indices) {
            val n = i - start + 1
            val gap = if (i + 1 < words.size) words[i + 1].startMs - words[i].endMs else Long.MAX_VALUE
            val cut = i == words.lastIndex ||
                n >= maxWords ||
                (gap >= pauseMs && n >= 2) || gap >= 1100 ||
                (endsSentence(words[i].text) && n >= minWords) ||
                (endsClause(words[i].text) && n >= maxWords * 2 / 3)
            if (cut) {
                out += start..i
                start = i + 1
            }
        }
        return out
    }

    /** The most telling word of [range]: a number, else the longest content word. */
    fun keyWord(words: List<SWord>, range: IntRange): Int? =
        range.firstOrNull { words[it].text.any(Char::isDigit) }
            ?: range.filter { !isStop(words[it].text) }.maxByOrNull { Studio.norm(words[it].text).length }

    fun clean(w: String) = w.trim { !it.isLetterOrDigit() && it != '%' && it != '×' }
}

/** Local director without AI: kinds of visuals from the words themselves (numbers, steps, comparisons, lists, emoji). */
object StudioRules {
    private val STEP = listOf("во-первых", "во-вторых", "в-третьих", "первое", "второе", "третье", "шаг", "этап", "first", "second", "third", "step")
    private val VS = listOf("а не", "вместо", "против", "миф", "на самом деле", "instead of", "rather than", "versus", "vs", "myth")

    fun direct(words: List<SWord>, title: String = "", loud: FloatArray? = null): StudioDirector.Direction {
        val ranges = StudioWords.split(words)
        val scenes = ranges.mapIndexed { si, r -> scene(words, r, si, loud) }
        return StudioDirector.Direction(title, emptySet(), StudioDirector.cover(scenes, words.size))
    }

    /** Scene [si] over words [r] of [words]; [loud] (per word, 0..1) picks the key word when given. */
    fun scene(words: List<SWord>, r: IntRange, si: Int, loud: FloatArray?): StudioDirector.DScene {
        val ws = r.map { words[it].text }
        val low = ws.joinToString(" ") { it.lowercase() }
        var kind = "text"
        var title = ""
        var items = emptyList<SItem>()
        var transition = if (si == 0) "none" else "fade"
        val cues = mutableListOf<SCue>()
        val key = loud?.let { l -> r.maxByOrNull { l.getOrElse(it) { 0f } } } ?: StudioWords.keyWord(words, r)
        val num = r.firstOrNull { words[it].text.any(Char::isDigit) }
        val stepAt = r.firstOrNull { k -> Studio.norm(words[k].text).let { n -> STEP.any { Studio.norm(it) == n } } }
        val vs = VS.firstOrNull { low.contains(" $it ") || low.startsWith("$it ") }
        val quoted = Regex("[«\"“]([^»\"”]{6,90})[»\"”]").find(ws.joinToString(" "))?.groupValues?.get(1)
        val commas = ws.dropLast(1).count { it.endsWith(",") }
        val emojis = r.mapNotNull { k -> StudioWords.emojiFor(words[k].text)?.let { e -> SItem(e, StudioWords.clean(words[k].text).lowercase()) } }
            .distinctBy { it.icon }
        when {
            num != null -> {
                kind = "stat"
                title = StudioWords.clean(words[num].text)
                val label = ((num + 1)..min(r.last, num + 2)).map { StudioWords.clean(words[it].text) }.filter { it.isNotEmpty() }.joinToString(" ")
                items = listOf(SItem("", label.ifBlank { ws.filter { !StudioWords.isStop(it) }.take(2).joinToString(" ") { StudioWords.clean(it) } }))
                transition = if (si == 0) "none" else "zoom"
                cues += SCue("sfx", num, "ding")
            }
            stepAt != null -> {
                kind = "step"
                title = ((stepAt + 1)..min(r.last, stepAt + 3)).map { StudioWords.clean(words[it].text) }.filter { it.isNotEmpty() }.joinToString(" ")
                    .ifBlank { StudioWords.clean(words[stepAt].text) }
                transition = if (si == 0) "none" else "whip"
            }
            vs != null -> {
                val cut = low.indexOf(vs)
                val before = low.substring(0, cut).split(' ').filter { it.isNotBlank() && !StudioWords.isStop(it) }.takeLast(3)
                val after = low.substring(cut + vs.length).split(' ').filter { it.isNotBlank() && !StudioWords.isStop(it) }.take(3)
                if (before.isNotEmpty() && after.isNotEmpty()) {
                    kind = "compare"
                    items = listOf(SItem("❌", before.joinToString(" ") { StudioWords.clean(it) }), SItem("✅", after.joinToString(" ") { StudioWords.clean(it) }))
                    transition = if (si == 0) "none" else "slide"
                }
            }
            quoted != null -> {
                kind = "quote"
                title = quoted.trim()
            }
            commas >= 2 && ws.size <= 18 -> {
                val parts = ws.joinToString(" ").split(',').map { it.trim().split(' ').filter { w -> w.isNotBlank() } }
                    .filter { it.isNotEmpty() && it.size <= 5 }
                if (parts.size >= 3) {
                    kind = "list"
                    items = parts.take(5).map { p -> SItem(p.firstNotNullOfOrNull { StudioWords.emojiFor(it) } ?: "", p.joinToString(" ") { StudioWords.clean(it) }) }
                    transition = if (si == 0) "none" else "slide"
                }
            }
            emojis.size >= 2 -> {
                kind = "icons"
                items = emojis.take(5)
            }
            emojis.size == 1 -> {
                kind = "big"
                items = emojis
            }
        }
        if (ws.lastOrNull()?.trimEnd()?.endsWith("?") == true && si == 0) transition = "none"
        // camera: punch-in on the key word of every other scene and of the hook
        if (key != null && (si == 0 || si % 2 == 1)) cues += SCue("zoom", key)
        if (si == 0 && key != null) cues += SCue("sfx", r.first, "whoosh")
        val emph = key?.let { listOf(StudioWords.clean(words[it].text)) }?.filter { it.isNotEmpty() } ?: emptyList()
        return StudioDirector.DScene(r.first, r.last, StudioDirector.fitKind(kind, items), title, items, emph, transition, cues)
    }
}

/** Sound-driven director: scenes by pauses, zooms on the loudest words and on the beats of the music, no AI. */
object StudioRhythm {
    /** Loudness of every word (0..1): the loudest 20 ms frame inside it, scaled to the loudest word. */
    fun wordLoudness(words: List<SWord>, rms: FloatArray, frameMs: Int = 20): FloatArray {
        val out = FloatArray(words.size) { i ->
            val a = (words[i].startMs / frameMs).toInt().coerceIn(0, max(0, rms.size - 1))
            val b = ((words[i].endMs - 1) / frameMs).toInt().coerceIn(a, max(0, rms.size - 1))
            var m = 0f
            for (k in a..b) if (k < rms.size) m = max(m, rms[k])
            m
        }
        val top = out.maxOrNull() ?: 0f
        if (top > 0f) for (i in out.indices) out[i] /= top
        return out
    }

    /**
     * Beats of a music track: frames where the energy jumps well above its recent average (simple onset detection).
     * At least [minGapMs] apart.
     */
    fun onsets(pcm: ShortArray, rate: Int, frameMs: Int = 20, minGapMs: Long = 280): List<Long> {
        if (pcm.isEmpty() || rate <= 0) return emptyList()
        val n = max(1, rate * frameMs / 1000)
        val frames = pcm.size / n
        if (frames < 8) return emptyList()
        val e = FloatArray(frames) { f ->
            var s = 0.0
            for (k in f * n until (f + 1) * n) s += pcm[k].toDouble() * pcm[k]
            sqrt(s / n).toFloat()
        }
        val out = mutableListOf<Long>()
        val win = 43 // ~0.9 s of history
        var last = -minGapMs
        for (f in 1 until frames) {
            val from = max(0, f - win)
            var avg = 0f
            for (k in from until f) avg += e[k]
            avg /= (f - from)
            val rise = e[f] - e[f - 1]
            val t = f.toLong() * frameMs
            if (e[f] > avg * 1.45f && rise > 0 && e[f] > 300 && t - last >= minGapMs) {
                out += t
                last = t
            }
        }
        return out
    }

    /** Index of the word nearest to [tMs] inside [range]. */
    fun nearestWord(words: List<SWord>, range: IntRange, tMs: Long): Int = range.minByOrNull { kotlin.math.abs(words[it].startMs - tMs) } ?: range.first

    fun direct(words: List<SWord>, loud: FloatArray, beats: List<Long> = emptyList(), title: String = ""): StudioDirector.Direction {
        val ranges = StudioWords.split(words, pauseMs = 550, minWords = 3, maxWords = 12)
        val top = loud.indices.maxByOrNull { loud[it] }
        val scenes = ranges.mapIndexed { si, r ->
            // visuals from the rules; timing from the sound
            val b = StudioRules.scene(words, r, si, loud)
            val loudest = r.maxByOrNull { loud.getOrElse(it) { 0f } } ?: r.first
            val cues = mutableListOf(SCue("zoom", loudest))
            beats.filter { it > words[r.first].startMs + 600 && it < words[r.last].endMs }.take(1)
                .forEach { bt -> nearestWord(words, r, bt).takeIf { kotlin.math.abs(it - loudest) >= 4 }?.let { cues += SCue("zoom", it) } }
            if (top != null && top in r) cues += SCue("sfx", top, "boom")
            val gapBefore = if (r.first > 0) words[r.first].startMs - words[r.first - 1].endMs else 0L
            val transition = when {
                si == 0 -> "none"
                gapBefore >= 1200 -> "whip"
                gapBefore >= 700 -> "slide"
                else -> "fade"
            }
            if (transition == "whip") cues += SCue("sfx", r.first, "whoosh")
            StudioDirector.DScene(r.first, r.last, b.kind, b.title, b.items,
                listOf(StudioWords.clean(words[loudest].text)).filter { it.isNotEmpty() }, transition, cues)
        }
        return StudioDirector.Direction(title, emptySet(), StudioDirector.cover(scenes, words.size))
    }

    /** Smoothed voice loudness for the "pulse" (fast attack, slow release), 0..1 per frame. */
    fun pulse(rms: FloatArray, attack: Float = 0.6f, release: Float = 0.12f): FloatArray {
        val top = rms.maxOrNull()?.takeIf { it > 0f } ?: return FloatArray(rms.size)
        val out = FloatArray(rms.size)
        var v = 0f
        for (i in rms.indices) {
            val x = rms[i] / top
            v += (x - v) * (if (x > v) attack else release)
            out[i] = v
        }
        return out
    }
}

/**
 * Compact protocol for cheap AI calls: the transcript is marked only at clause starts ("[12] text"), the answer is
 * arrays with short strings, and moments are named by a word of the scene instead of its number.
 *   {"t":"title","s":[[from,"kind","title","🔍 label|2024=55","emph;words","z@word;p@word:TEXT:⚠️;s@word:ding;s:whoosh;t:whip"]]}
 */
object StudioCompact {
    /** Indices where scenes may start: the first word, words after the end of a clause and after a pause of [pauseMs]. */
    fun marks(words: List<SWord>, pauseMs: Long = 400): List<Int> = words.indices.filter { i ->
        i == 0 || StudioWords.endsClause(words[i - 1].text) || words[i].startMs - words[i - 1].endMs >= pauseMs
    }

    /** "[0] Привет мир, [2] как дела. [5] …". */
    fun transcript(words: List<SWord>): String {
        val m = marks(words).toSet()
        return buildString {
            words.forEachIndexed { i, w ->
                if (i > 0) append(' ')
                if (i in m) append('[').append(i).append("] ")
                append(w.text)
            }
        }
    }

    /** Word of [range] that starts like [word] (by letters and digits); null when none. */
    fun find(words: List<SWord>, range: IntRange, word: String): Int? {
        val n = Studio.norm(word)
        if (n.isEmpty()) return null
        return range.firstOrNull { Studio.norm(words[it].text) == n }
            ?: range.firstOrNull { Studio.norm(words[it].text).startsWith(n) || (n.length >= 4 && n.startsWith(Studio.norm(words[it].text)) && Studio.norm(words[it].text).length >= 4) }
    }

    /** Cues and the transition from an effects string; moments are words of the scene ([range]). */
    fun effects(fx: String, words: List<SWord>, range: IntRange): Pair<List<SCue>, String> {
        val cues = mutableListOf<SCue>()
        var transition = ""
        for (raw in fx.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val type = raw.substringBefore(':').substringBefore('@').trim().lowercase()
            val at = raw.substringAfter('@', "").substringBefore(':').trim().let { if (it.isEmpty()) range.first else find(words, range, it) ?: range.first }
            val args = raw.substringAfter('@', raw).split(':').drop(1).map { it.trim() }
            when (type) {
                "t" -> transition = args.firstOrNull()?.lowercase()?.takeIf { it in StudioDirector.TRANSITIONS } ?: transition
                "z" -> cues += SCue("zoom", at)
                "p" -> args.firstOrNull()?.take(32)?.takeIf { it.isNotEmpty() }?.let { cues += SCue("pop", at, it, args.getOrElse(1) { "" }.take(8)) }
                "s" -> args.firstOrNull()?.lowercase()?.takeIf { it in StudioDirector.SFX }?.let { cues += SCue("sfx", at, it) }
            }
        }
        return cues to transition
    }

    private fun items(s: String): List<SItem> = s.split('|').map { Studio.itemOf(it) }.filter { it.icon.isNotEmpty() || it.label.isNotEmpty() }.take(Studio.MAX_ITEMS)

    private fun row(a: JSONArray, from: Int, to: Int, words: List<SWord>): StudioDirector.DScene {
        val kind = a.optString(1).trim().lowercase().takeIf { it in Studio.KINDS } ?: "text"
        val its = items(a.optString(3))
        val (cues, tr) = effects(a.optString(5), words, from..to)
        val emph = a.optString(4).split(';').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.take(3)
        return StudioDirector.DScene(from, to, StudioDirector.fitKind(kind, its), a.optString(2).trim().take(80), its, emph, tr, cues)
    }

    private fun obj(reply: String): JSONObject? = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JsonSalvage.array(reply, "s")?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Plan from a compact reply; scenes start at the given word numbers and run to the next one. */
    fun parse(reply: String, words: List<SWord>): StudioDirector.Direction? {
        if (words.isEmpty()) return null
        val o = obj(reply) ?: return null
        val arr = o.optJSONArray("s") ?: return null
        val rows = (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }
            .mapNotNull { a -> StudioDirector.wordIndex(a.opt(0))?.takeIf { it in words.indices }?.let { it to a } }
            .sortedBy { it.first }.distinctBy { it.first }
        if (rows.isEmpty()) return null
        val scenes = rows.mapIndexed { k, (from, a) ->
            val to = if (k + 1 < rows.size) rows[k + 1].first - 1 else words.lastIndex
            row(a, from, to, words)
        }
        val firstFrom = scenes.first().from
        val all = if (firstFrom > 0) listOf(scenes.first().copy(from = 0)) + scenes.drop(1) else scenes
        return StudioDirector.Direction(o.optString("t").trim(), emptySet(), StudioDirector.cover(all, words.size))
    }

    /** Lines of a draft for the AI to improve: "#3 stat | сорок процентов людей…". */
    fun listing(d: StudioDirector.Direction, words: List<SWord>, maxWords: Int = 14): String = d.scenes.mapIndexed { i, s ->
        val text = (s.from..s.to).take(maxWords).joinToString(" ") { words[it].text } + if (s.to - s.from + 1 > maxWords) "…" else ""
        "#$i ${s.kind} | $text"
    }.joinToString("\n")

    /** The draft with the scenes the AI changed: {"t":"…","s":[[sceneIndex,"kind","title","items","emph","fx"]]}. */
    fun patch(draft: StudioDirector.Direction, reply: String, words: List<SWord>): StudioDirector.Direction? {
        val o = obj(reply) ?: return null
        val arr = o.optJSONArray("s") ?: return null
        val out = draft.scenes.toMutableList()
        var changed = 0
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            val k = StudioDirector.wordIndex(a.opt(0)) ?: continue
            val s = out.getOrNull(k) ?: continue
            val n = row(a, s.from, s.to, words)
            out[k] = n.copy(
                cues = if (n.cues.isEmpty()) s.cues else n.cues,
                transition = n.transition.ifEmpty { s.transition },
                emph = n.emph.ifEmpty { s.emph }
            )
            changed++
        }
        if (changed == 0) return null
        return StudioDirector.Direction(o.optString("t").trim().ifEmpty { draft.title }, draft.drop, StudioDirector.cover(out, words.size))
    }
}

/** Animated Noto emoji (Google, CC BY 4.0) as Lottie files: names and addresses. */
object StudioLiveEmoji {
    /** "🔥" → "1f525", "👨‍💻" → "1f468_200d_1f4bb" (variation selectors dropped). */
    fun code(e: String): String {
        val cps = mutableListOf<Int>()
        var i = 0
        while (i < e.length) {
            val cp = e.codePointAt(i)
            if (cp != 0xFE0F && cp != 0xFE0E) cps += cp
            i += Character.charCount(cp)
        }
        return cps.joinToString("_") { Integer.toHexString(it) }
    }

    fun url(e: String) = "https://fonts.gstatic.com/s/e/notoemoji/latest/${code(e)}/lottie.json"

    /** Every emoji the video shows: tiles, big icons, stickers and emoji shapes. */
    fun used(s: SScript): Set<String> = s.scenes.flatMap { sc ->
        sc.items.map { it.icon } + sc.cues.filter { it.type == "pop" }.map { it.icon } + sc.shapes.filter { it.el == "emoji" }.map { it.text }
    }.map { it.trim() }.filter { it.isNotEmpty() && Studio.startsWithEmoji(it) }.toSet()
}

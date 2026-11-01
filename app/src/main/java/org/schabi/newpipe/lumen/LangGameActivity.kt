/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/** Editable prompts of the language game (added to [Prompts.defaults]). */
val LANGGAME_PROMPTS = linkedMapOf(
    "langgame_read" to (
        "Ты психолингвист и аналитик дискурса (Витгенштейн: языковые игры и формы жизни; психоанализ: желание, страх, идеал, защиты; " +
            "социолингвистика: свои и чужие, регистр). Тема ниши: \"{phrase}\" ({domain}). Ниже протокол lumen.langgame — измерения языка " +
            "авторов и аудитории YouTube в этой нише. Истолкуй его строго по данным, без выдумок; где данных мало, скажи коротко. Язык значений: {lang}.\n" +
            "Верни строго JSON: {\"form_of_life\":\"кто эти люди и в какой практике живут их слова, 2-3 предложения\"," +
            "\"rules\":[\"правила игры: какие ходы приняты, что считается сильным высказыванием\"],\"roles\":[\"роли говорящих\"]," +
            "\"shibboleths\":[{\"word\":\"...\",\"meaning\":\"что слово значит у своих\"}],\"taboo\":[\"чего говорить нельзя, что звучит чужим\"]," +
            "\"metaphors\":[\"образные поля\"],\"desire\":\"чего хотят\",\"fear\":\"чего боятся\",\"ideal\":\"образ идеального я\"," +
            "\"defenses\":[\"защиты: рационализация, обесценивание, ирония и т.п., с примерами\"],\"tone\":\"тон и ритм\"," +
            "\"voice_rules\":[\"5-8 правил, как автору звучать своим\"],\"seo_terms\":[\"10-20 слов и фраз, которыми аудитория ищет и называет тему\"]," +
            "\"hooks\":[\"5 первых фраз ролика на их языке\"]}\n\n{data}"
        ),
    "langgame_write" to "{brief}"
)

/** Android side of [LangGame]: the corpus of a project, the AI reading and the AI writing. */
object LangGameData {
    /** Every text of the project split into layers. Comments and transcripts are capped to keep it fast. */
    fun corpus(pid: String, maxComments: Int = 8000, maxTranscripts: Int = 80): List<LangGame.Doc> {
        val out = ArrayList<LangGame.Doc>()
        val videos = Store.videos(pid).values.toList()
        val seenCh = HashSet<String>()
        for (v in videos) {
            if (v.title.isNotBlank()) out += LangGame.Doc(LangGame.TITLE, v.title, maxOf(v.views, 0L), v.id)
            if (v.description.isNotBlank()) out += LangGame.Doc(LangGame.DESC, v.description.take(2000), 0, v.id)
            if (v.tags.isNotEmpty()) out += LangGame.Doc(LangGame.TAG, v.tags.joinToString(", "), 0, v.id)
            if (v.channel.isNotBlank() && seenCh.add(v.channel)) out += LangGame.Doc(LangGame.CHANNEL, v.channel, maxOf(v.subscribers, 0L))
        }
        for (c in runCatching { ChannelStore.all(pid).values }.getOrDefault(emptyList())) {
            if (c.name.isNotBlank() && seenCh.add(c.name)) out += LangGame.Doc(LangGame.CHANNEL, c.name, maxOf(c.subscribers, 0L))
            if (c.description.isNotBlank()) out += LangGame.Doc(LangGame.CHANNEL, c.description.take(1500), 0, c.url)
            c.recent.filter { r -> r.title.isNotBlank() && videos.none { it.id == r.id } }
                .forEach { out += LangGame.Doc(LangGame.TITLE, it.title, maxOf(it.views, 0L), it.id) }
        }
        var nc = 0
        for (v in videos.sortedByDescending { it.views }) {
            if (nc >= maxComments) break
            val list = runCatching { Store.comments(pid, v.id) }.getOrDefault(emptyList())
            for (c in list) {
                if (nc >= maxComments) break
                if (c.text.isBlank()) continue
                out += LangGame.Doc(LangGame.COMMENT, c.text, c.likes.toLong(), v.id)
                nc++
            }
        }
        var nt = 0
        for (v in videos.filter { it.hasTranscript }.sortedByDescending { it.views }) {
            if (nt >= maxTranscripts) break
            val tr = Store.transcript(pid, v.id)?.let { Vtt.stripTimecodes(it) }?.takeIf { it.isNotBlank() } ?: continue
            out += LangGame.Doc(LangGame.SPEECH, tr.take(30000), 0, v.id)
            nt++
        }
        return out
    }

    fun load(pid: String): JSONObject? = Store.readFile(pid, LangGame.FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { LangGame.valid(it) }

    /** Local, free: measures the corpus and keeps the earlier AI reading if there was one. */
    fun build(p: Project): JSONObject {
        val docs = corpus(p.id)
        if (docs.none { it.layer == LangGame.COMMENT } && docs.none { it.layer == LangGame.TITLE }) {
            throw LlmException(t("В проекте ещё нет видео и комментариев: сначала найдите и соберите видео.", "The project has no videos or comments yet: search and collect videos first."))
        }
        val meta = JSONObject().put("name", p.name).put("phrase", p.phrase).put("domain", p.domain)
        val game = LangGame.analyse(docs, meta)
        load(p.id)?.optJSONObject("reading")?.let { game.put("reading", it) }
        Store.writeFile(p.id, LangGame.FILE, game.toString())
        return game
    }

    private fun lang() = if (Settings.lang == "en") "English" else "русский"

    /** AI reading of the game (form of life, rules, desire and fear, voice rules); stored inside the same file. */
    fun read(p: Project): JSONObject {
        val game = load(p.id) ?: build(p)
        val prov = AiTasks.requireProvider()
        val prompt = Prompts.fill("langgame_read", mapOf("phrase" to p.phrase, "domain" to p.domain, "lang" to lang(),
            "data" to LangGame.compact(JSONObject(game.toString()).apply { remove("reading") })))
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 6000, task = "langgame_read", pid = p.id)
        val o = JsonText.firstObject(r.text)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw LlmException(t("Модель вернула невалидный JSON: ", "The model returned invalid JSON: ") + r.text.take(200))
        val reading = LangGame.reading(o)
        if (reading.length() == 0) throw LlmException(t("В ответе нет полей толкования", "The answer has no reading fields"))
        game.put("reading", reading.put("model", r.model).put("at", System.currentTimeMillis()))
        Store.writeFile(p.id, LangGame.FILE, game.toString())
        return game
    }

    fun brief(p: Project, k: LangGame.Kind, topic: String, wish: String): String {
        val game = load(p.id) ?: build(p)
        return Prompts.fill("langgame_write", mapOf("brief" to LangGame.brief(game, k, topic, wish, lang())))
    }

    /** Writes the script / SEO pack inside Lumen; the answer is kept as the last one of this project. */
    fun write(p: Project, k: LangGame.Kind, topic: String, wish: String): String {
        val prov = AiTasks.requireProvider()
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, brief(p, k, topic, wish), maxTokens = 8000, task = "langgame_write", pid = p.id)
        val text = r.text.trim()
        Store.writeFile(p.id, "langgame_last.json", JSONObject().put("kind", k.id).put("topic", topic).put("text", text)
            .put("model", r.model).put("at", System.currentTimeMillis()).toString())
        return text
    }

    fun last(pid: String): JSONObject? = Store.readFile(pid, "langgame_last.json")?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Human-readable summary of the game for the screen and for sharing. */
    fun summary(g: JSONObject): String = buildString {
        fun words(a: JSONArray?, n: Int = 15) = (0 until minOf(a?.length() ?: 0, n)).joinToString(", ") { a!!.optJSONObject(it)?.optString("w") ?: a.optString(it) }
        val c = g.optJSONObject("corpus")
        if (c != null) {
            append(t("Корпус: ", "Corpus: "))
            append(c.keys().asSequence().joinToString(" · ") { k -> k + " " + c.optJSONObject(k)?.optInt("docs") })
            append("\n\n")
        }
        val lex = g.optJSONObject("lexicon")
        append(t("🗝 Слова своих (аудитория): ", "🗝 Insider words (audience): ")).append(words(lex?.optJSONArray("shibboleths"))).append("\n\n")
        append(t("🎙 Язык авторов: ", "🎙 Creators' jargon: ")).append(words(lex?.optJSONArray("jargon"))).append("\n\n")
        append(t("🤝 Общее ядро: ", "🤝 Shared core: ")).append(words(lex?.optJSONArray("shared"))).append("\n\n")
        append(t("💬 Обороты аудитории: ", "💬 Audience phrases: ")).append(words(g.optJSONObject("formulas")?.optJSONArray("audience"), 10)).append("\n\n")
        val ti = g.optJSONObject("titles")
        if (ti != null) {
            append(t("🏷 Названия: ", "🏷 Titles: ")).append(ti.optDouble("avg_words")).append(t(" слов; цифры ", " words; numbers "))
                .append(pct(ti.optDouble("numbers"))).append(t(", вопрос ", ", question ")).append(pct(ti.optDouble("questions")))
                .append(t(", капс ", ", caps ")).append(pct(ti.optDouble("caps"))).append(t("; формулы: ", "; shapes: "))
                .append(words(ti.optJSONArray("shapes"), 5)).append("\n\n")
        }
        val ad = g.optJSONObject("address")?.optJSONObject("audience")
        if (ad != null) append(t("👋 Обращение: ", "👋 Address: ")).append(ad.optString("register")).append(" · ").append(words(ad.optJSONArray("group"), 5)).append("\n\n")
        val m = g.optJSONObject("markers")?.optJSONObject("audience")
        if (m != null) {
            append(t("🧠 Маркеры аудитории на 1000 слов: ", "🧠 Audience markers per 1000 words: "))
            append(listOf("i", "we", "they", "must", "hedge", "absolute", "pos", "negaff").joinToString(" · ") { "$it ${m.optDouble(it)}" })
            append(t("; мы/они ", "; we/they ")).append(m.optDouble("we_vs_they")).append(t("; вопросов ", "; questions ")).append(pct(m.optDouble("questions"))).append("\n\n")
        }
        val voc = g.optJSONObject("voc")
        if (voc != null) {
            append(t("❓ Голос аудитории: ", "❓ Voice of the audience: "))
            append(Voc.categories.joinToString(" · ") { it + " " + pct(voc.optJSONObject(it)?.optDouble("share") ?: 0.0) }).append("\n\n")
        }
        val r = g.optJSONObject("reading")
        if (r != null) {
            append(t("— AI-толкование —\n", "— AI reading —\n"))
            for (k in LangGame.READING_KEYS) {
                val v = r.opt(k) ?: continue
                append("• ").append(k).append(": ")
                if (v is JSONArray) append((0 until v.length()).joinToString("; ") { i ->
                    v.optJSONObject(i)?.let { it.optString("word") + " — " + it.optString("meaning") } ?: v.optString(i)
                }) else append(v.toString())
                append('\n')
            }
        }
    }.trim()

    private fun pct(d: Double) = "${Math.round(d * 100)}%"
}

/**
 * "🗣 Language game of the audience": local measurement of how the niche speaks, AI reading, then a script,
 * podcast or SEO pack written in that language — inside Lumen, in Lumen Studio or in any AI app via Share.
 */
class LangGameActivity : LumenBase() {
    private lateinit var project: Project
    private var game: JSONObject? = null
    private var busy = ""
    private var kind = LangGame.Kind.VIDEO
    private var topicEdit: EditText? = null
    private var wishEdit: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = Store.project(intent.getStringExtra("pid").orEmpty()) ?: return finish()
        kind = LangGame.Kind.of(Lumen.prefs.getString("langgame_kind", "").orEmpty())
        game = LangGameData.load(project.id)
        render()
        if (game == null) build()
    }

    private fun fields() {
        topicEdit?.let { Lumen.prefs.edit().putString("langgame_topic", it.text.toString().trim()).apply() }
        wishEdit?.let { Lumen.prefs.edit().putString("langgame_wish", it.text.toString().trim()).apply() }
    }

    private val topic get() = Lumen.prefs.getString("langgame_topic", "").orEmpty()
    private val wish get() = Lumen.prefs.getString("langgame_wish", "").orEmpty()

    private fun render() {
        val c = Col(this)
        c.p(t("Lumen собирает все тексты проекта — названия, описания, теги, каналы, комментарии и расшифровки — и измеряет, как говорит ниша: " +
            "слова «своих», обороты, формулы названий, обращение, психолингвистические маркеры. По этому протоколу ИИ пишет сценарий или SEO на языке аудитории.",
            "Lumen gathers every text of the project — titles, descriptions, tags, channels, comments and transcripts — and measures how the niche speaks: " +
                "insider words, phrases, title formulas, address, psycholinguistic markers. Using this protocol the AI writes a script or SEO in the audience's language."),
            color = Palette.MUTED)
        if (busy.isNotEmpty()) c.working(busy)
        val g = game
        c.card {
            add(text(ctx, t("① Языковая игра", "① Language game"), Type.TITLE_M, bold = true), 0)
            if (g == null) muted(t("Ещё не собрана", "Not built yet"))
            else {
                muted(Fmt.dateTime(g.optLong("at")) + (g.optJSONObject("reading")?.let { " · AI: " + it.optString("model") } ?: ""))
                p(LangGameData.summary(g).take(1600), 14f)
            }
            row(
                btn(ctx, t("↻ Пересобрать", "↻ Rebuild"), style = BtnStyle.TONAL) { build() },
                btn(ctx, t("🧠 Толкование AI", "🧠 AI reading"), style = BtnStyle.TONAL) { read() }
            )
            if (g != null) row(
                btn(ctx, t("Читать всё", "Read all"), style = BtnStyle.TEXT) { showText(t("Языковая игра", "Language game"), LangGameData.summary(g)) },
                btn(ctx, t("📄 Файл ↗", "📄 File ↗"), style = BtnStyle.TEXT) { shareFile() }
            )
            button(t("📦 Все артефакты файлом (без ИИ) ↗", "📦 All artifacts in a file (no AI) ↗"), style = BtnStyle.TEXT) { artifactsDialog(project) }
        }
        c.card {
            add(text(ctx, t("② Что написать", "② What to write"), Type.TITLE_M, bold = true), 0)
            add(choiceChips(ctx, LangGame.Kind.entries.map { it to t(it.ru, it.en) }, kind) {
                kind = it
                Lumen.prefs.edit().putString("langgame_kind", it.id).apply()
            }, 4)
            topicEdit = edit(t("Тема (пусто — AI выберет самую горячую из вопросов и болей)", "Topic (empty — the AI picks the hottest from questions and pains)"), topic)
            wishEdit = edit(t("Пожелание: тон, длина, оффер, призыв…", "Wish: tone, length, offer, call to action…"), wish, multiline = true)
            button(t("✍️ Написать в Lumen (AI)", "✍️ Write in Lumen (AI)"), style = BtnStyle.FILLED) { write() }
            button(t("📲 Отправить в ИИ на телефоне ↗", "📲 Send to an AI app ↗"), style = BtnStyle.TONAL) { handoff() }
            muted(t("Отправка передаёт готовый запрос по протоколу lumen.langgame (задача, правила игры и данные) в ChatGPT, Gemini, Claude или другое приложение.",
                "Sending passes a ready request in the lumen.langgame protocol (task, rules of the game and data) to ChatGPT, Gemini, Claude or another app."))
        }
        LangGameData.last(project.id)?.let { last ->
            val body = last.optString("text")
            val k = LangGame.Kind.of(last.optString("kind"))
            c.card {
                add(text(ctx, t("③ Последний текст", "③ Last text"), Type.TITLE_M, bold = true), 0)
                muted(t(k.ru, k.en) + " · " + Fmt.dateTime(last.optLong("at")) + " · " + last.optString("model"))
                p(LangGame.sections(body).first.ifBlank { body.take(120) }, 14f)
                row(
                    btn(ctx, t("Читать", "Read"), style = BtnStyle.TEXT) { showText(t(k.ru, k.en), body) },
                    btn(ctx, t("Поделиться ↗", "Share ↗"), style = BtnStyle.TEXT) { shareText(t(k.ru, k.en), body) }
                )
                if (k.studio != null) button(t("🎬 В Студию", "🎬 To Studio"), style = BtnStyle.TONAL) { toStudio(k, body, last.optString("topic")) }
                else LangGame.tags(body).takeIf { it.isNotEmpty() }?.let { tags ->
                    button(t("Копировать теги", "Copy tags"), style = BtnStyle.TEXT) { copy(tags.joinToString(", ")) }
                }
            }
        }
        page(t("🗣 Языковая игра", "🗣 Language game"), c.root, keepScroll = true)
    }

    private fun build() {
        if (busy.isNotEmpty()) return
        fields()
        busy = t("Собираю тексты и считаю…", "Gathering texts and measuring…")
        render()
        bgTask(t("Языковая игра", "Language game"), { LangGameData.build(project) }, {
            busy = ""
            render()
            err(it)
        }) {
            busy = ""
            game = it
            render()
        }
    }

    private fun needAi(): Boolean {
        if (Provider.active() != null) return true
        confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ? Или отправьте запрос в ИИ-приложение на телефоне.", "Connect an API key? Or send the request to an AI app on the phone.")) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
        }
        return false
    }

    private fun read() {
        if (busy.isNotEmpty() || !needAi()) return
        fields()
        busy = t("AI толкует языковую игру…", "The AI reads the language game…")
        render()
        bgTask(t("Толкование языковой игры (AI)", "Language game reading (AI)"), { LangGameData.read(project) }, {
            busy = ""
            render()
            err(it)
        }) {
            busy = ""
            game = it
            render()
        }
    }

    private fun write() {
        if (busy.isNotEmpty() || !needAi()) return
        fields()
        val k = kind
        busy = t("AI пишет на языке аудитории…", "The AI writes in the audience's language…")
        render()
        bgTask(t("Текст на языке аудитории (AI)", "Text in the audience's language (AI)"), { LangGameData.write(project, k, topic, wish) }, {
            busy = ""
            render()
            err(it)
        }) {
            busy = ""
            game = LangGameData.load(project.id)
            render()
            showText(t(k.ru, k.en), it)
        }
    }

    /** The whole request goes as text (most AI apps accept only text from Share); the file is in [shareFile]. */
    private fun handoff() {
        if (busy.isNotEmpty()) return
        fields()
        val k = kind
        Lumen.async({ LangGameData.brief(project, k, topic, wish) }, { err(it) }) { b ->
            game = LangGameData.load(project.id)
            render()
            copy(b)
            shareText(t(k.ru, k.en) + " · " + project.name, b)
        }
    }

    private fun shareFile() {
        val g = game ?: return
        val f = ReportKit.shareFile(this, ReportKit.safeName(project.name) + "-langgame.json")
        f.writeText(g.toString(2))
        ReportKit.share(this, f, false, project.name, "application/json")
    }

    private fun toStudio(k: LangGame.Kind, text: String, topic: String) {
        val s = LangGame.script(text)
        if (s.isBlank()) return toast(t("В ответе нет раздела «Сценарий»", "The answer has no Script section"))
        StudioActivity.openWith(this, k.studio ?: return, s, topic.ifBlank { LangGame.sections(text).first })
    }

    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Lumen", s))
        toast(t("Скопировано", "Copied"))
    }

    companion object {
        fun open(ctx: Context, pid: String) = ctx.startActivity(Intent(ctx, LangGameActivity::class.java).putExtra("pid", pid))
    }
}

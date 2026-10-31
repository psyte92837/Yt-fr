/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * "Comment digest" of one shared video: Share → comments are collected right away → AI writes a full digest
 * (cases, hypotheses, ideas, thoughts, questions, disputes, advice…) → the user asks the AI chat about these comments.
 * Pure functions are covered by CommentDigestTest.
 */
object CommentDigest {
    const val KIND = "comment_digest"
    const val MODE = "digest"
    const val TAB = "comments"

    /** Model input: source line, then comments as "[c12 ♥34] text" (signals first, then most liked), within [limit]. */
    fun input(title: String, msgs: List<Pains.Msg>, limit: Int): String =
        "Source: " + title + "\nComments: " + msgs.size + "\n" + Pains.aiInput(msgs, limit)

    /** Chat context: the digest first (short and dense), then the raw comments to quote from. */
    fun chatData(digest: String?, title: String, msgs: List<Pains.Msg>, limit: Int): String = buildString {
        if (!digest.isNullOrBlank()) {
            append("Digest of the comments:\n").append(digest.take(limit / 3)).append("\n\n")
        }
        append(input(title, msgs, (limit - length).coerceAtLeast(2000)))
    }

    /** Markdown → readable plain text for a TextView: no "#" headings, "**" bold or "* " bullets. */
    fun plain(md: String): String = md.lines().joinToString("\n") { l ->
        val s = l.trimEnd()
        when {
            s.trimStart().startsWith("#") -> s.trimStart().trimStart('#').trim().uppercaseFirst()
            s.trimStart().startsWith("* ") -> s.replaceFirst("* ", "• ")
            s.trimStart().startsWith("- ") -> s.replaceFirst("- ", "• ")
            else -> s
        }.replace("**", "").replace("__", "")
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun String.uppercaseFirst() = replaceFirstChar { it.uppercase() }

    // ---- Android side ----

    fun title(pid: String): String =
        Store.videos(pid).values.maxByOrNull { it.views }?.let { it.title + " — " + it.channel } ?: Store.project(pid)?.name.orEmpty()

    fun latest(pid: String): AiResult? = Store.latestAi(pid, KIND, "project")

    fun ai(p: Project, force: Boolean = false): AiResult {
        val prov = AiTasks.requireProvider()
        val msgs = Pains.projectMessages(p.id)
        if (msgs.isEmpty()) throw LlmException(t("Нет собранных комментариев.", "No collected comments."))
        val prompt = Prompts.get(KIND).replace("{lang}", if (Settings.lang == "en") "English" else "русский")
            .replace("{data}", input(title(p.id), msgs, Settings.chunkChars * 3))
        val key = sha1("$KIND|${prov.smartModel}|${prompt.hashCode()}")
        if (!force) Store.ai(p.id, key)?.let { return it }
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, maxTokens = 8000, task = KIND, pid = p.id)
        return AiResult(key, KIND, "project", r.model, r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    /** Chat context for [AiTasks.chat] on the comments tab. */
    fun chatContext(pid: String): String =
        chatData(latest(pid)?.text, title(pid), Pains.projectMessages(pid), Settings.chunkChars * 3)

    /** Full text to read, share or save. */
    fun text(pid: String): String {
        val ai = latest(pid) ?: return ""
        return title(pid) + "\n" + Store.project(pid)?.source.orEmpty() + "\n\n" + plain(ai.text) + "\n\n— " + ai.model
    }
}

val COMMENT_DIGEST_PROMPTS = linkedMapOf(
    CommentDigest.KIND to (
        "Ниже ВСЕ собранные комментарии к одному видео YouTube, формат строки: [id ♥лайки] текст.\n" +
            "Сделай полную сводку обсуждения на языке {lang}, ничего ценного не теряй. Разделы (пустые пропускай):\n" +
            "## Главное — 4-6 предложений: кто комментирует, о чём спорят, общий настрой.\n" +
            "## Кейсы и истории — каждая личная история: кто, что случилось, что пробовал, итог (с id).\n" +
            "## Гипотезы и предположения — что люди предполагают, объясняют, во что верят.\n" +
            "## Идеи и предложения — идеи для автора, продукта, следующих видео.\n" +
            "## Мысли и мнения — основные позиции и аргументы, за и против.\n" +
            "## Вопросы — что спрашивают (частые первыми).\n" +
            "## Споры и возражения — где не согласны с автором или друг с другом.\n" +
            "## Боли и проблемы — что беспокоит людей.\n" +
            "## Советы, лайфхаки, факты и ссылки — полезное от зрителей.\n" +
            "## Юмор и мемы — если есть.\n" +
            "## Цитаты — 5-8 самых показательных, дословно, с id.\n" +
            "## Выводы — 3-5 пунктов: что из этого следует.\n" +
            "Пункты списком «- », в конце пункта id комментариев в скобках, например (c3, c17). " +
            "Самое частое и залайканное — первым. Не выдумывай того, чего нет в комментариях.\n\n{data}"
        )
)

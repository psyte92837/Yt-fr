/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject

/*
 * Lumen Studio: AI script assistant. The current script goes to the AI with a request ("make it funnier",
 * "shorter", "check the facts" or the author's own words) and comes back rewritten in the same JSON format,
 * with notes on what was changed. Pure logic here; the dialog lives in StudioActivity.
 */

/** A ready request of the assistant: chip [label] and the [ask] sent to the AI. [cartoon] = only for the Animation task. */
data class AssistPreset(val id: String, val label: String, val ask: String, val cartoon: Boolean = false)

/** Rewritten script and what the AI says it changed. */
data class AssistResult(val script: SScript, val notes: List<String>)

object StudioAssist {
    fun presets(cartoon: Boolean): List<AssistPreset> = listOf(
        AssistPreset("hook", t("🎣 Сильнее хук", "🎣 Stronger hook"),
            "Сделай первую сцену цепляющей: неожиданный факт, вопрос или обещание пользы в первые 3 секунды. Остальное почти не меняй."),
        AssistPreset("short", t("✂ Короче", "✂ Shorter"),
            "Сократи текст примерно на треть: убери повторы, воду и лишние слова, сохрани главные мысли и порядок."),
        AssistPreset("simple", t("🧒 Проще", "🧒 Simpler"),
            "Перепиши проще, как для школьника 10-12 лет: короткие фразы, бытовые примеры и сравнения, без терминов (или объясни их)."),
        AssistPreset("fun", t("😄 Смешнее", "😄 Funnier"),
            "Добавь лёгкого юмора: забавные сравнения, неожиданные повороты, самоиронию. Смысл и факты сохрани."),
        AssistPreset("facts", t("🔎 Проверь факты", "🔎 Check facts"),
            "Проверь каждое утверждение. Исправь ошибки, неточные цифры и мифы; если факт спорный — смягчи формулировку. В notes перечисли, что исправлено и почему."),
        AssistPreset("more", t("📚 Больше фактов", "📚 More facts"),
            "Добавь 2-4 сцены с конкретными интересными фактами, цифрами или примерами по теме, без воды."),
        AssistPreset("visual", t("📊 Больше графики", "📊 More visuals"),
            "Подбери каждой сцене более наглядный визуал kind: числа — stat или chart, перечни — icons или list, процессы — flow, сравнения — compare. Текст почти не меняй."),
        AssistPreset("cta", t("📣 Концовка", "📣 Ending"),
            "Сделай сильную концовку: короткий вывод одной фразой и призыв подписаться или написать мнение в комментариях."),
        AssistPreset("dialog", t("💬 Живее диалог", "💬 Livelier dialogue"),
            "Сделай диалог живее: герои перебивают, переспрашивают, спорят и шутят, у каждого свой характер и манера речи; реплики короче.", cartoon = true),
        AssistPreset("balance", t("⚖ Поровну реплик", "⚖ Balance lines"),
            "Распредели реплики между всеми персонажами примерно поровну, каждый должен участвовать.", cartoon = true),
        AssistPreset("moods", t("🎭 Эмоции", "🎭 Moods"),
            "Расставь каждой реплике подходящую эмоцию mood так, чтобы они менялись и подчёркивали смысл.", cartoon = true)
    ).filter { cartoon || !it.cartoon }

    /** Extra rules of the prompt for the Animation task. */
    fun castRule(cast: List<SChar>): String = if (cast.isEmpty()) "" else
        "Это мультик-диалог. Персонажи: ${StudioToon.promptCast(cast)}. У каждой сцены who — имя точно из списка (пусто — рассказчик) " +
            "и mood — calm, happy, explain, think, surprise, sad, angry."

    /** The AI reply: the script (null if unusable) and up to 8 notes. */
    fun parse(reply: String): AssistResult? {
        val sc = Studio.parseScript(reply) ?: return null
        val o = JsonText.firstObject(reply)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val notes = when (val n = o?.opt("notes")) {
            is JSONArray -> (0 until n.length()).map { n.optString(it).trim() }
            is String -> n.split('\n').map { it.trim().removePrefix("-").trim() }
            else -> emptyList()
        }.filter { it.isNotEmpty() }.take(8)
        return AssistResult(sc, notes)
    }

    /** Short summary of a change: scenes and words before and after. */
    fun summary(before: SScript, after: SScript): String {
        fun words(s: SScript) = s.scenes.sumOf { Studio.tokens(it.say).size }
        return t("Сцен: ${before.scenes.size} → ${after.scenes.size} · слов: ${words(before)} → ${words(after)}",
            "Scenes: ${before.scenes.size} → ${after.scenes.size} · words: ${words(before)} → ${words(after)}")
    }
}

/** "AI makes everything" for the animation: the author's idea, scenes and draft turned into the script request. */
object StudioAuto {
    /** Topic for the prompt: the idea, else the start of the scenes or of the draft. */
    fun topic(idea: String, scenes: String, draft: String): String =
        idea.trim().ifBlank { scenes.trim().ifBlank { draft.trim() } }.replace(Regex("\\s+"), " ").take(300).trim()

    /** Extra block of the prompt with the scenes and the draft (empty when none). */
    fun extra(scenes: String, draft: String): String {
        val out = mutableListOf<String>()
        val sc = scenes.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }
        if (sc.isNotEmpty()) out += "Сцены, которые задумал автор (сохрани порядок и смысл, можно добавить связки):\n" +
            sc.take(40).mapIndexed { i, l -> "${i + 1}. ${l.take(300)}" }.joinToString("\n")
        if (draft.isNotBlank()) out += "Черновик сценария автора — сохрани его мысли и удачные реплики:\n" + draft.trim().take(3000)
        return out.joinToString("\n")
    }
}

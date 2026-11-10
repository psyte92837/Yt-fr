/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.widget.LinearLayout

/**
 * AI settings shared by the Settings screens of Lumen and Lumen Studio: providers and keys, what is sent,
 * another AI by hand, Studio options, prompt templates and AI usage. [rerender] redraws the host screen.
 */
class AiSettings(private val a: LumenBase, private val rerender: () -> Unit) {
    private var testing = ""

    private var usageGroup = AiStats.Group.PROJECT
    private var usageDays = 30

    /** «Reset all settings» button: options back to defaults with Undo; keys, prompts, language and data stay. */
    fun resetAll(c: Col, after: () -> Unit = {}) { with(a) {
        c.muted(t("Все параметры вернутся к значениям по умолчанию. Ключи ИИ, ваши промпты, язык, проекты и файлы не трогаются.",
            "All options return to their defaults. AI keys, your prompts, the language, projects and files stay."))
        c.button(t("↺ Сбросить все настройки", "↺ Reset all settings"), style = BtnStyle.OUTLINED) {
            val before = HashMap(Lumen.prefs.all)
            val gone = PrefsIo.resetKeys(before.keys)
            if (gone.isEmpty()) return@button toast(t("Все настройки уже по умолчанию", "All settings are already default"))
            confirm(t("Сбросить настройки?", "Reset the settings?"),
                t("Параметров: ${gone.size}. Ключи ИИ, промпты, язык и данные останутся.", "Values: ${gone.size}. AI keys, prompts, the language and data stay."),
                t("Сбросить", "Reset"), danger = true) {
                // the page is redrawn in place (recreate would drop the Undo bar); theme applies on the next screen
                fun reapply() {
                    runCatching { after() }
                    rerender()
                }
                undoable(t("Настройки сброшены", "Settings reset"), apply = {
                    val e = Lumen.prefs.edit()
                    gone.forEach { e.remove(it) }
                    e.commit()
                    reapply()
                }, revert = {
                    val e = Lumen.prefs.edit()
                    for (k in gone) {
                        @Suppress("UNCHECKED_CAST")
                        when (val v = before[k]) {
                            is Boolean -> e.putBoolean(k, v)
                            is Int -> e.putInt(k, v)
                            is Long -> e.putLong(k, v)
                            is Float -> e.putFloat(k, v)
                            is String -> e.putString(k, v)
                            is Set<*> -> e.putStringSet(k, v as Set<String>)
                        }
                    }
                    e.commit()
                    reapply()
                })
            }
        }
    } }

    fun usage(c: Col) { with(a) {
        val recs = AiStats.all()
        c.muted(t("Все вызовы AI с самого начала: токены, запросы, время, ошибки. Только на устройстве; токены берутся из ответа провайдера.",
            "Every AI call since the beginning: tokens, requests, time, errors. On this device only; tokens come from the provider's reply."))
        c.chips(listOf(1 to t("сутки", "24 h"), 7 to t("7 дней", "7 days"), 30 to t("30 дней", "30 days"), 0 to t("всё время", "all time")).map { (d, l) ->
            chip(this, l, usageDays == d) { usageDays = d; rerender() }
        })
        c.chips(listOf(AiStats.Group.PROJECT to t("по проектам", "by project"), AiStats.Group.PROVIDER to t("по провайдерам", "by provider"),
            AiStats.Group.MODEL to t("по моделям", "by model"), AiStats.Group.TASK to t("по задачам", "by task"), AiStats.Group.DAY to t("по дням", "by day")).map { (g, l) ->
            chip(this, l, usageGroup == g) { usageGroup = g; rerender() }
        })
        val since = if (usageDays > 0) System.currentTimeMillis() - usageDays * 86_400_000L else 0L
        val names = Store.projects().associate { it.id to it.name.ifBlank { it.phrase } }
        val rows = AiStats.aggregate(recs, usageGroup, since, name = { names[it] ?: t("(удалён) ", "(deleted) ") + it.take(6) })
        val sum = AiStats.total(rows)
        fun cost(r: AiStats.Row) = Llm.estimateCost(r.inTokens.toInt(), r.outTokens.toInt())
        fun line(r: AiStats.Row) = t("запросов ", "calls ") + r.calls + " · in " + AiStats.fmtTokens(r.inTokens) + " / out " + AiStats.fmtTokens(r.outTokens) +
            (if (Settings.priceInPerM > 0 || Settings.priceOutPerM > 0) " · ≈$" + String.format(java.util.Locale.US, "%.3f", cost(r)) else "")
        c.card {
            add(text(ctx, t("Итого", "Total") + ": " + AiStats.fmtTokens(sum.tokens) + " " + t("токенов", "tokens"), Type.TITLE_M, bold = true), 0)
            muted(line(sum))
            muted("✓ ${sum.ok} · ✂ ${sum.truncated} · ✗ ${sum.errors} · " + t("среднее время ", "avg time ") + sum.avgMs + " ms")
        }
        if (rows.isEmpty()) c.muted(t("За этот период вызовов не было.", "No calls in this period."))
        val top = rows.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
        for (r in rows) c.card {
            add(rowOf(ctx, text(ctx, r.key, Type.BODY, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                text(ctx, AiStats.fmtTokens(r.tokens), Type.BODY, bold = true, color = Palette.ACCENT)), 0)
            add(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (r.tokens * 1000 / top).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
            }, 4)
            muted(line(r) + (if (r.errors + r.truncated > 0) " · ✂${r.truncated} ✗${r.errors}" else ""))
        }
        c.button(t("Журнал всех ответов AI", "Journal of all AI answers"), style = BtnStyle.OUTLINED) { AiJournalActivity.open(this) }
        c.button(t("Сбросить статистику", "Reset statistics"), style = BtnStyle.TEXT) {
            confirm(t("Удалить всю статистику использования?", "Delete all usage statistics?"), "", t("Удалить", "Delete")) {
                AiStats.clear()
                rerender()
            }
        }
    }}

    fun ai(c: Col) { with(a) {
        val active = Provider.active()
        c.card {
            p(if (active != null) t("Активный: ", "Active: ") + active.title + " · " + active.fastModel + " / " + active.smartModel
            else t("Активный провайдер не выбран", "No active provider"))
            muted(t("Быстрая модель — уточнение темы и фрагменты; умная — итоговые сводки. Ключи шифруются ключом Android Keystore и не попадают в экспорт.",
                "Fast model — domain refinement and chunks; smart model — final summaries. Keys are encrypted with an Android Keystore key and never exported."))
        }
        if (testing.isNotEmpty()) c.p(testing, 13f, Palette.MUTED)
        c.button(t("Журнал всех ответов AI (", "Journal of all AI answers (") + AiJournal.list().size + ")", style = BtnStyle.OUTLINED) { AiJournalActivity.open(this) }
        sendRules(c)
        c.h2(t("Провайдеры", "Providers"))
        for (p in Provider.entries) {
            val card = c.card {
                val configured = p.isConfigured
                add(rowOf(ctx,
                    text(ctx, p.title, Type.TITLE_M, bold = true),
                    text(ctx, if (Settings.activeProvider == p.name && configured) t("● активен", "● active") else if (configured) t("настроен", "configured") else "",
                        12f, bold = true, color = if (Settings.activeProvider == p.name) Palette.ACCENT else Palette.MUTED).apply { gravity = android.view.Gravity.END }
                ), 0)
                if (p == Provider.FREE) muted(t("Ключей: ", "Keys: ") + FreeAiStore.usable().size + t(" · модель выбирается сама, при лимите — следующая", " · the model is picked per call, the next one on a limit"))
                else muted(p.baseUrl)
                if (configured && p != Provider.FREE) muted(p.fastModel + " / " + p.smartModel)
            }
            card.setOnClickListener {
                when (p) {
                    Provider.MANUAL -> manualProvider()
                    Provider.FREE -> FreeAiActivity.open(this)
                    else -> editProvider(p)
                }
            }
        }
        relayRules(c)
        c.h2(t("Стоимость (необязательно)", "Pricing (optional)"))
        c.muted(t("Укажите цену вашей модели за 1 млн токенов — тогда перед запуском покажем примерную стоимость.",
            "Enter your model's price per 1M tokens to see an estimated cost before running."))
        val pin = c.edit(t("Вход, $ за 1M", "Input, $ per 1M"), if (Settings.priceInPerM > 0) Settings.priceInPerM.toString() else "")
        val pout = c.edit(t("Выход, $ за 1M", "Output, $ per 1M"), if (Settings.priceOutPerM > 0) Settings.priceOutPerM.toString() else "")
        c.spinner(t("Размер фрагмента для длинных текстов", "Chunk size for long texts"),
            listOf(8000 to "8k", 16000 to "16k", 24000 to "24k", 48000 to "48k", 96000 to "96k"), Settings.chunkChars) { Settings.chunkChars = it }
        c.button(t("Сохранить", "Save"), primary = true) {
            Settings.priceInPerM = pin.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            Settings.priceOutPerM = pout.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            toast(t("Сохранено", "Saved"))
        }
    }}

    private var rulesOpen = false

    /** "What is sent": review before every AI call and a rule per task (ask / send / never). */
    private fun sendRules(c: Col) { with(a) {
        c.h2(t("Что отправляется в AI", "What is sent to AI"))
        c.check(t("Показывать данные перед каждой отправкой и давать выбрать", "Show the data before every call and let me choose"), SendGate.reviewOn) {
            SendGate.reviewOn = it
        }
        c.muted(t("Перед запросом откроется экран со всем, что уйдёт провайдеру: инструкция, блоки собранных данных, строки, видео. Лишнее можно снять галочкой или отредактировать. Отправленный текст сохраняется в журнале.",
            "Before a call a screen shows everything the provider receives: instruction, blocks of collected data, lines, video. Untick or edit what should not go out. The sent text is kept in the journal."))
        val tasks = (Prompts.defaults.keys + SendGate.storedTasks() + AiJournal.list().map { it.task })
            .filter { it.isNotBlank() && it != "ping" }.distinct().sorted()
        val special = tasks.count { SendGate.stored(it) != Outgoing.Rule.ASK }
        c.section(t("Правила по задачам", "Rules per task"), t("задач ", "tasks ") + tasks.size + t(", особых правил ", ", custom rules ") + special, rulesOpen, {
            rulesOpen = it
            rerender()
        }) {
            val opts = listOf(Outgoing.Rule.ASK to t("спрашивать", "ask"), Outgoing.Rule.SEND to t("без вопросов", "send"), Outgoing.Rule.BLOCK to t("никогда", "never"))
            tasks.forEach { task -> spinner(task, opts, SendGate.stored(task)) { SendGate.setStored(task, it) } }
            button(t("Сбросить выбор «до перезапуска»", "Reset \"until restart\" choices"), style = BtnStyle.TEXT) {
                SendGate.clearSession()
                toast(t("Сброшено", "Reset"))
            }
        }
    }}

    /** Another AI by hand: no key, every request is copied out and the answer pasted back. */
    private fun manualProvider() { with(a) {
        AlertDialog.Builder(this).setTitle(Provider.MANUAL.title)
            .setMessage(t("Без ключа API. Когда Lumen нужен ИИ, откроется экран запроса: скопируйте его в Gemini, ChatGPT, Claude или любой другой чат, " +
                "при желании допишите свою просьбу, а ответ скопируйте обратно — Lumen подхватит его из буфера. Можно и «Поделиться → Lumen: ответ ИИ» прямо в приложении ИИ. " +
                "Ответ проходит те же проверки и исправления, что и ответ по API.",
                "No API key. When Lumen needs AI, a request screen opens: copy it into Gemini, ChatGPT, Claude or any chat, add your own wish if you like, " +
                    "and copy the answer back — Lumen picks it up from the clipboard. Or use \"Share → Lumen: AI answer\" right in the AI app. " +
                    "The answer goes through the same checks and repairs as an API answer."))
            .setPositiveButton(t("Сделать активным", "Make active")) { _, _ ->
                Settings.activeProvider = Provider.MANUAL.name
                rerender()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }}

    /** Studio options that otherwise live on its steps: one place to look, the same values. */
    fun studio(c: Col) { with(a) {
        c.muted(t("Те же настройки есть на шагах Студии; здесь — все вместе.", "The same options are on the Studio steps; here they are all together."))
        c.h2(t("Ролик", "Video"))
        c.check(t("Звуковые эффекты (whoosh, boom)", "Sound effects (whoosh, boom)"), StudioPrefs.sfx) { StudioPrefs.sfx = it }
        c.check(t("Вырезать слова-паразиты", "Cut filler words"), StudioPrefs.dropFillers) { StudioPrefs.dropFillers = it }
        c.check(t("AI-режиссёр сам после разбора", "AI director right after the analysis"), StudioPrefs.autoDirect) { StudioPrefs.autoDirect = it }
        c.check(t("Пульсация на ударениях", "Pulse on stresses"), StudioPrefs.pulse) { StudioPrefs.pulse = it }
        c.check(t("Живые эмодзи", "Live emoji"), StudioPrefs.liveEmoji) { StudioPrefs.liveEmoji = it }
        c.h2("B-roll")
        c.check(t("Подбирать B-roll", "Pick B-roll"), StudioPrefs.broll) { StudioPrefs.broll = it }
        c.check(t("Видеоклипы вместо фото", "Video clips instead of photos"), StudioPrefs.brollVideo) { StudioPrefs.brollVideo = it }
        c.h2(t("Редактор", "Editor"))
        c.spinner(t("Качество экспорта", "Export quality"), listOf(1920 to t("как оригинал", "as the original"), 1280 to t("720p — быстрее", "720p — faster")),
            Lumen.prefs.getInt("studio_edit_side", 1920)) { Lumen.prefs.edit().putInt("studio_edit_side", it).apply() }
        c.button(t("Показать подсказки заново", "Show the hints again"), style = BtnStyle.TEXT) {
            Lumen.prefs.edit().remove("studio_edit_hint").apply()
            toast(t("Подсказка покажется при следующем входе в редактор", "The hint shows next time you open the editor"))
        }
        c.h2(t("Другой ИИ", "Another AI"))
        relayRules(c)
    }}

    /** Which calls go to another AI by hand while an API provider stays active. */
    private fun relayRules(c: Col) { with(a) {
        c.spinner(t("Через другой ИИ вручную", "Through another AI by hand"),
            listOf("off" to t("выкл", "off"), "studio" to t("Студия и Языковая игра", "Studio and Language game"), "all" to t("все задачи", "all tasks")),
            Relay.mode) { Relay.mode = it }
        c.muted(t("Запросы выбранных задач не уходят по API: открывается экран «Другой ИИ» — копируете запрос в Gemini/ChatGPT и возвращаете ответ.",
            "Requests of the chosen tasks skip the API: the \"Another AI\" screen opens — copy the request into Gemini/ChatGPT and bring the answer back."))
    }}

    private fun editProvider(p: Provider) { with(a) {
        val c = Col(this, padding = 24)
        if (p.keyUrl.isNotEmpty()) {
            c.button(t("Получить ключ ↗", "Get a key ↗"), style = BtnStyle.TONAL) { openUrl(p.keyUrl) }
        }
        val key = if (p.needsKey || p == Provider.CUSTOM) {
            c.edit(if (KeyVault.has(p.name)) t("Ключ сохранён — введите новый, чтобы заменить", "Key saved — enter a new one to replace") else "API key").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        } else {
            null
        }
        c.muted("Base URL")
        val base = c.edit("https://…", p.baseUrl)
        if (p == Provider.OLLAMA) c.muted(t("Адрес компьютера в локальной сети, где запущен Ollama/LM Studio (OLLAMA_HOST=0.0.0.0).", "LAN address of the computer running Ollama/LM Studio (OLLAMA_HOST=0.0.0.0)."))
        c.muted(t("Быстрая модель", "Fast model"))
        val fast = c.edit("model id", p.fastModel)
        c.muted(t("Умная модель", "Smart model"))
        val smart = c.edit("model id", p.smartModel)
        fun saveFields() {
            key?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { KeyVault.put(p.name, it) }
            p.baseUrl = base.text.toString()
            p.fastModel = fast.text.toString()
            p.smartModel = smart.text.toString()
        }
        c.button(t("Загрузить список моделей", "Load model list"), style = BtnStyle.OUTLINED) {
            saveFields()
            Lumen.async({ Llm.listModels(p) }, { err(it) }) { models ->
                if (models.isEmpty()) return@async toast(t("Список пуст", "Empty list"))
                val arr = models.toTypedArray()
                AlertDialog.Builder(this).setTitle(t("Модель", "Model")).setItems(arr) { _, i ->
                    AlertDialog.Builder(this).setTitle(arr[i])
                        .setPositiveButton(t("Быстрая", "Fast")) { _, _ -> fast.setText(arr[i]) }
                        .setNegativeButton(t("Умная", "Smart")) { _, _ -> smart.setText(arr[i]) }
                        .setNeutralButton(t("Обе", "Both")) { _, _ ->
                            fast.setText(arr[i])
                            smart.setText(arr[i])
                        }.show()
                }.show()
            }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(c.root) }
        val dlg = AlertDialog.Builder(this).setTitle(p.title).setView(scroll)
            .setPositiveButton(t("Сохранить и сделать активным", "Save & make active")) { _, _ ->
                saveFields()
                Settings.activeProvider = p.name
                testing = t("Проверка подключения…", "Testing connection…")
                rerender()
                Lumen.async({ Llm.ping(p) }, {
                    testing = "✗ ${p.title}: ${it.message}"
                    rerender()
                }) { r ->
                    testing = "✓ ${p.title}: $r"
                    rerender()
                }
            }
            .setNeutralButton(t("Сохранить", "Save")) { _, _ ->
                saveFields()
                rerender()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .create()
        dlg.show()
    }}

    /** A readable name of a prompt without its own label: its group by the key prefix and the key. */
    private fun promptLabel(k: String): String = when {
        k.startsWith("studio_mix") -> t("Микс · ", "Mix · ")
        k.startsWith("studio_edit") -> t("Редактор · ", "Editor · ")
        k.startsWith("studio") -> t("Студия · ", "Studio · ")
        k.startsWith("langgame") -> t("Языковая игра · ", "Language game · ")
        k.startsWith("predict") -> t("Предсказания · ", "Predictions · ")
        k.startsWith("bot") -> t("Боты · ", "Bots · ")
        k.startsWith("player") -> t("Плеер · ", "Player · ")
        else -> ""
    } + k

    fun prompts(c: Col, names: Map<String, String> = emptyMap(), only: (String) -> Boolean = { true }) { with(a) {
        c.muted(t("Переменные: {phrase} {domain} {lang} {data} {question}. Пустое поле — шаблон по умолчанию.",
            "Variables: {phrase} {domain} {lang} {data} {question}. Empty field = default template."))
        // every other prompt of the app (Studio, Mix, editor, bots…) is here too, under its key
        val all = names + Prompts.defaults.keys.filter { it !in names && only(it) }.associateWith { promptLabel(it) }
        for ((k, label) in all) {
            val card = c.card {
                add(text(ctx, label, Type.TITLE_M, bold = true), 0)
                val custom = Lumen.prefs.contains("prompt_$k")
                muted((if (custom) t("изменён · ", "custom · ") else "") + Prompts.get(k).take(120).replace('\n', ' ') + "…")
            }
            card.setOnClickListener {
                prompt(label, "", Prompts.get(k), multiline = true) { v ->
                    Prompts.set(k, v)
                    rerender()
                }
            }
        }
        c.button(t("Сбросить все промпты", "Reset all prompts"), style = BtnStyle.TEXT) {
            confirm(t("Сбросить все промпты?", "Reset all prompts?"),
                t("Ваши правки промптов заменятся стандартными.", "Your prompt edits are replaced with the defaults."), t("Сбросить", "Reset")) {
                val old = Prompts.defaults.keys.associateWith { Prompts.get(it) }
                undoable(t("Промпты сброшены", "Prompts reset"), apply = {
                    Prompts.defaults.keys.forEach { Prompts.set(it, "") }
                    rerender()
                }, revert = {
                    old.forEach { (k, v) -> Prompts.set(k, v) }
                    rerender()
                })
            }
        }
    }}
}

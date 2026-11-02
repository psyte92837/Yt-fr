/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType

/** «Бесплатные ИИ»: keys of free providers, the catalog from FreeLLMAPI, today's use and cooldowns. */
class FreeAiActivity : LumenBase() {
    private var status = ""
    private var modelsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val cat = FreeAiStore.catalog()
        val use = FreeAiStore.snapshot()
        val now = System.currentTimeMillis()
        val active = Settings.activeProvider == Provider.FREE.name
        val usable = FreeAiStore.usable()
        val c = Col(this)
        c.card {
            p(if (active) t("● Бесплатные ИИ — активный провайдер", "● Free AI is the active provider")
            else t("Бесплатные ИИ сейчас не используются", "Free AI is not in use now"), Type.TITLE_M)
            muted(t("Lumen сам выбирает лучшую бесплатную модель из тех, на которые у вас есть ключ. Упёрлась в лимит или ошиблась — " +
                "запрос сразу уходит следующей, а эта отдыхает. Ключи бесплатные (регистрация у провайдера, без карты), хранятся только на телефоне в Android Keystore.",
                "Lumen picks the best free model you have a key for. When it hits a limit or fails, the request goes to the next one and this one rests. " +
                    "Keys are free (sign up at the provider, no card) and stay on the phone in Android Keystore."))
            muted(t("Подключено: ", "Connected: ") + usable.size + t(" из ", " of ") + cat.providers.size +
                t(" · запросов сегодня: ", " · requests today: ") + use.slots.values.sumOf { it.n })
            val resting = use.slots.values.filter { it.coolUntil > now }
            if (resting.isNotEmpty()) {
                val next = resting.minOf { it.coolUntil }
                muted("⏸ " + t("Отдыхают: ${resting.size} · первая вернётся через ", "Resting: ${resting.size} · the first returns in ") + TaskText.elapsed(next - now))
                button(t("Снять все паузы", "Clear all cooldowns"), style = BtnStyle.TEXT) {
                    FreeAiStore.clearCooldowns()
                    render()
                }
            }
            if (!active) button(t("Сделать активным", "Make active"), primary = true) {
                if (usable.isEmpty()) toast(t("Сначала добавьте хотя бы один ключ ниже", "Add at least one key below first"))
                Settings.activeProvider = Provider.FREE.name
                render()
            }
        }
        if (status.isNotBlank()) c.p(status, 13f, Palette.MUTED)

        c.h2(t("Провайдеры", "Providers"))
        val sorted = cat.providers.sortedWith(compareBy<FreeProv>({ !(FreeAiStore.has(it.id) || it.keyless) }, { it.title.lowercase() }))
        sorted.forEach { p ->
            val models = cat.models.filter { it.p == p.id }
            val n = models.sumOf { use.slots[it.key]?.n ?: 0 }
            val keyErr = use.slots["${p.id}/*"]?.takeIf { it.coolUntil > now }
            val card = c.card {
                val state = when {
                    p.id in FreeAiStore.off -> t("выключен", "off")
                    keyErr != null -> "⚠ " + t("ошибка ключа", "key error")
                    FreeAiStore.has(p.id) -> "🔑 " + t("ключ есть", "key saved")
                    p.keyless -> t("без ключа", "no key needed")
                    else -> t("нет ключа", "no key")
                }
                add(rowOf(ctx, text(ctx, p.title, Type.TITLE_M, bold = true),
                    text(ctx, state, 12f, bold = true, color = if (keyErr != null) Palette.BAD else if (FreeAiStore.has(p.id)) Palette.ACCENT else Palette.MUTED)
                        .apply { gravity = android.view.Gravity.END }), 0)
                val best = models.minByOrNull { it.intel }
                muted(t("моделей ", "models ") + models.size + (best?.let { t(" · лучшая: ", " · best: ") + it.name } ?: "") +
                    if (n > 0) t(" · сегодня ", " · today ") + n else "")
                keyErr?.let { muted(it.err.take(160)) }
                val rest = models.mapNotNull { m -> use.slots[m.key]?.takeIf { it.coolUntil > now }?.let { m to it } }
                if (rest.isNotEmpty()) {
                    val until = rest.maxOf { it.second.coolUntil }
                    muted("⏸ " + t("отдыхают ${rest.size} из ${models.size} · до ", "resting ${rest.size} of ${models.size} · until ") +
                        java.text.SimpleDateFormat(if (until - now > 20 * 3600_000L) "dd.MM HH:mm" else "HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)) +
                        rest.first().second.err.takeIf { it.isNotBlank() }?.let { " — " + it.take(80) }.orEmpty())
                }
            }
            card.setOnClickListener { editProvider(p) }
        }

        c.section(t("Порядок моделей сейчас", "Model order now"), t("для быстрых и умных задач", "for fast and smart tasks"), modelsOpen, {
            modelsOpen = it
            render()
        }) {
            for (smart in listOf(false, true)) {
                p(if (smart) t("Умные задачи (итоговые сводки, Студия)", "Smart tasks (summaries, Studio)") else t("Быстрые задачи", "Fast tasks"), 14f)
                val list = FreeAiStore.candidates(cat, FreeAi.Need(smart))
                muted(if (list.isEmpty()) "—" else list.mapIndexed { i, m -> "${i + 1}. ${m.name} (${cat.prov(m.p)?.title ?: m.p})" }.joinToString("\n"))
            }
            val resting = use.slots.filter { it.value.coolUntil > now }
            if (resting.isNotEmpty()) {
                p(t("Отдыхают", "Resting"), 14f)
                muted(resting.entries.joinToString("\n") { (k, s) -> k + t(" ещё ", " for ") + TaskText.elapsed(s.coolUntil - now) + if (s.err.isNotBlank()) " — " + s.err.take(80) else "" })
                button(t("Снять все паузы", "Clear all cooldowns"), style = BtnStyle.TEXT) {
                    FreeAiStore.clearCooldowns()
                    render()
                }
            }
        }

        c.h2(t("Каталог", "Catalog"))
        c.muted(t("Список бесплатных моделей и лимитов берётся из открытого проекта FreeLLMAPI (MIT) и обновляется на GitHub дважды в неделю; " +
            "Lumen проверяет новую версию раз в 3 дня.",
            "The list of free models and limits comes from the open FreeLLMAPI project (MIT) and is rebuilt on GitHub twice a week; Lumen checks for a new version every 3 days."))
        c.muted(t("Версия ", "Version ") + cat.version.ifBlank { "—" } + " · " + cat.providers.size + t(" провайдеров, ", " providers, ") +
            cat.models.size + t(" моделей", " models") + "\n" + cat.upstream +
            if (FreeAiStore.lastCheck > 0) "\n" + t("проверено ", "checked ") + Fmt.dateTime(FreeAiStore.lastCheck) else "")
        c.button(t("🔄 Обновить каталог", "🔄 Update catalog"), style = BtnStyle.OUTLINED) {
            status = t("Проверяю каталог…", "Checking the catalog…")
            render()
            Lumen.async({ FreeAiStore.update() }, {
                status = "✗ " + it.message
                render()
            }) { fresh ->
                status = if (fresh) "✓ " + t("Каталог обновлён: ", "Catalog updated: ") + FreeAiStore.catalog().version else t("Каталог уже свежий", "The catalog is up to date")
                render()
            }
        }
        page(t("Бесплатные ИИ", "Free AI"), c.root, keepScroll = true)
    }

    private fun editProvider(p: FreeProv) {
        val c = Col(this, padding = 24)
        val models = FreeAiStore.catalog().models.filter { it.p == p.id }.sortedBy { it.intel }
        if (p.keyUrl.isNotBlank()) c.button(t("Получить ключ ↗", "Get a key ↗"), style = BtnStyle.TONAL) { openUrl(p.keyUrl) }
        if (p.keyless) c.muted(t("Работает и без ключа (с жёсткими общими лимитами); свой ключ поднимает лимиты.", "Works without a key (strict shared limits); your own key raises them."))
        val key = c.edit(if (FreeAiStore.has(p.id)) t("Ключ сохранён — введите новый, чтобы заменить", "Key saved — enter a new one to replace") else "API key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        var off = p.id in FreeAiStore.off
        c.check(t("Не использовать этого провайдера", "Do not use this provider"), off) { off = it }
        c.muted(models.take(12).joinToString("\n") { m ->
            "• " + m.name + listOfNotNull(
                m.rpd.takeIf { it > 0 }?.let { "$it/" + t("день", "day") },
                m.rpm.takeIf { it > 0 }?.let { "$it/" + t("мин", "min") },
                m.ctx.takeIf { it > 0 }?.let { "${it / 1000}k" }
            ).joinToString(", ", " (", ")").takeIf { it != " ()" }.orEmpty()
        } + if (models.size > 12) "\n…" else "")
        fun save() {
            key.text.toString().trim().takeIf { it.isNotEmpty() }?.let { FreeAiStore.setKey(p.id, it) }
            FreeAiStore.off = if (off) FreeAiStore.off + p.id else FreeAiStore.off - p.id
        }
        val dlg = AlertDialog.Builder(this).setTitle(p.title).setView(android.widget.ScrollView(this).apply { addView(c.root) })
            .setPositiveButton(t("Сохранить и проверить", "Save & test")) { _, _ ->
                save()
                status = t("Проверяю ", "Testing ") + p.title + "…"
                render()
                Lumen.async({ Llm.freeTest(p.id) }, {
                    status = "✗ ${p.title}: ${it.message}"
                    render()
                }) { r ->
                    status = "✓ ${p.title}: $r"
                    render()
                }
            }
            .setNeutralButton(if (FreeAiStore.has(p.id)) t("Удалить ключ", "Delete key") else t("Отмена", "Cancel")) { _, _ ->
                if (FreeAiStore.has(p.id)) FreeAiStore.setKey(p.id, "")
                render()
            }
            .setNegativeButton(t("Сохранить", "Save")) { _, _ ->
                save()
                render()
            }
            .create()
        dlg.show()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, FreeAiActivity::class.java))
    }
}

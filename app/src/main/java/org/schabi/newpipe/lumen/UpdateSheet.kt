/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Intent

/** "🔄 Check for updates" from any screen: checks GitHub, shows what is new and offers to update. */
object UpdateSheet {
    fun check(a: LumenBase) {
        a.toast(t("Проверяю обновления…", "Checking for updates…"))
        Lumen.async({ Updates.check(a, notify = false) }, onError = {
            a.toast("✗ " + t("Не удалось проверить: ", "Check failed: ") + (it.message ?: it.javaClass.simpleName))
        }) { show(a, it) }
    }

    /** [u] = newer build or null (the running one is the latest). */
    fun show(a: LumenBase, u: UpdateInfo?) {
        val latest = u ?: Updates.latest
        a.bottomSheet(if (u != null) t("Доступна сборка ${u.build}", "Build ${u.build} available")
            else t("У вас последняя сборка", "You have the latest build")) { dismiss ->
            val build = if (Updates.currentBuild > 0) Updates.currentBuild.toString() else t("локальная", "local")
            muted(t("Сейчас: сборка ", "Now: build ") + build +
                (if (u != null) t(" → новая ${u.build}", " → new ${u.build}") +
                    (if (u.date.length >= 10) " · " + u.date.take(10) else "") +
                    UpdateLogic.mb(u.size).let { if (it.isEmpty()) "" else " · $it" } else ""))
            if (u != null) {
                if (u.extractor.isNotBlank() && u.extractor != Updates.currentExtractor)
                    p(t("🛠 Новый NewPipeExtractor (исправления для YouTube): ", "🛠 New NewPipeExtractor (YouTube fixes): ") +
                        UpdateLogic.extractorLabel(u.extractor), 13f, Palette.WARN)
                h2(t("Что нового", "What's new"))
                val w = UpdateLogic.whatsNew(u, Updates.currentCommit)
                p(if (w.items.isEmpty()) t("Описания нет.", "No description.") else UpdateLogic.whatsNewText(w), 14f)
                if (!w.complete && w.items.isNotEmpty())
                    muted(t("Ваша сборка старая или локальная — показаны последние изменения.", "Your build is old or local: showing the latest changes."))
                button(t("⬇ Обновить", "⬇ Update"), primary = true) {
                    dismiss()
                    a.startActivity(Intent(a, SettingsActivity::class.java).putExtra("update", true).putExtra("install", true))
                }
                button(t("Страница сборки ↗", "Build page ↗"), style = BtnStyle.TEXT) { a.openUrl(UpdateLogic.PAGE) }
            } else if (latest != null) {
                val w = UpdateLogic.whatsNew(latest, "")
                if (w.items.isNotEmpty()) {
                    h2(t("Последние изменения", "Recent changes"))
                    p(UpdateLogic.whatsNewText(WhatsNew(w.items.take(10), false, 0)), 14f)
                }
            }
            button(t("Закрыть", "Close"), style = BtnStyle.TEXT) { dismiss() }
        }
    }
}

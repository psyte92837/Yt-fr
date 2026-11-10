/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Pure logic behind the shared controls (see UX_AUDIT): spoken labels of glyph buttons,
 * one pending undoable action, the project trash, remembered text fields, app-wide search, project order.
 */

/** Spoken labels for buttons whose text is a glyph ("🗑" → "Удалить"); words after a leading glyph are read alone. */
object A11yText {
    private val table: Map<String, Pair<String, String>> = mapOf(
        "🗑" to ("Удалить" to "Delete"), "⧉" to ("Копировать" to "Copy"), "✎" to ("Изменить" to "Edit"),
        "✏️" to ("Изменить" to "Edit"), "✕" to ("Убрать" to "Remove"), "✓" to ("Готово" to "Done"),
        "↶" to ("Отменить действие" to "Undo"), "↷" to ("Повторить действие" to "Redo"),
        "▶" to ("Воспроизвести" to "Play"), "⏸" to ("Пауза" to "Pause"), "■" to ("Остановить" to "Stop"),
        "⏪" to ("Перемотать назад" to "Rewind"), "⏩" to ("Перемотать вперёд" to "Fast forward"),
        "⏮" to ("В начало" to "To the start"), "⏭" to ("В конец" to "To the end"),
        "⬇" to ("Скачать" to "Download"), "⬆" to ("Экспорт" to "Export"), "＋" to ("Добавить" to "Add"), "+" to ("Добавить" to "Add"),
        "−" to ("Уменьшить" to "Decrease"), "-" to ("Уменьшить" to "Decrease"),
        "⋮" to ("Ещё" to "More"), "☰" to ("Список" to "List"), "🔍" to ("Найти" to "Search"), "🔎" to ("Найти" to "Search"),
        "🎤" to ("Сказать голосом" to "Speak"), "📋" to ("Вставить" to "Paste"), "📥" to ("Вставить ответ" to "Paste the answer"),
        "↗" to ("Открыть" to "Open"), "⤴" to ("Поделиться" to "Share"), "🔊" to ("Звук" to "Sound"), "🔇" to ("Без звука" to "Muted"),
        "⚙" to ("Настройки" to "Settings"), "?" to ("Справка" to "Help"), "←" to ("Назад" to "Back"), "↺" to ("Сбросить" to "Reset"),
        "🤖" to ("ИИ" to "AI"), "✨" to ("ИИ" to "AI"), "👁" to ("Предпросмотр" to "Preview"), "📂" to ("Открыть файл" to "Open a file"),
        "🎙" to ("Записать" to "Record"), "◀" to ("Раньше" to "Earlier"), "⤢" to ("Вписать в экран" to "Fit to screen"),
        "🗺" to ("Карта знаний" to "Knowledge map"), "🧾" to ("Ответы AI" to "AI answers"), "⇪" to ("Экспорт" to "Export"),
        "📌" to ("Закрепить" to "Pin"), "⟳" to ("Обновить" to "Refresh"), "↻" to ("Обновить" to "Refresh"), "▾" to ("Подробнее" to "More")
    )

    /** Label for TalkBack, or null when the visible text reads well by itself. */
    fun label(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val first = s.indexOfFirst { it.isLetterOrDigit() }
        if (first == 0) return null
        if (first > 0) return s.substring(first).trim().takeIf { it.isNotEmpty() }
        table[s]?.let { return t(it.first, it.second) }
        // several glyphs ("⏪ 10"), or a known glyph with a variation selector
        table[s.replace("️", "")]?.let { return t(it.first, it.second) }
        return null
    }

    /** True when the text has no letters or digits: a glyph-only control that needs a spoken label. */
    fun glyphOnly(text: String) = text.isNotBlank() && text.none { it.isLetterOrDigit() }
}

/**
 * One pending undoable action. A new action commits the previous one; [undo] reverts the pending action;
 * [commit] makes it final (the snackbar timed out or the screen went away).
 */
class UndoSlot {
    private class Item(val id: Long, val label: String, val revert: () -> Unit, val commit: () -> Unit)
    private var item: Item? = null
    private var seq = 0L

    val pending: String? get() = item?.label

    /** Registers an action that has already been applied; returns its id. */
    fun push(label: String, revert: () -> Unit, commit: () -> Unit = {}): Long {
        this.commit()
        val id = ++seq
        item = Item(id, label, revert, commit)
        return id
    }

    /** Reverts the pending action (only if it is still [id], when given). */
    fun undo(id: Long? = null): Boolean {
        val it = item ?: return false
        if (id != null && it.id != id) return false
        item = null
        it.revert()
        return true
    }

    /** Makes the pending action final (only if it is still [id], when given). */
    fun commit(id: Long? = null) {
        val it = item ?: return
        if (id != null && it.id != id) return
        item = null
        it.commit()
    }
}

/** Project trash: deleted projects wait [KEEP_DAYS] days before they are removed for good. */
object TrashLogic {
    const val KEEP_DAYS = 7
    private const val DAY = 24L * 3600_000

    fun expired(deletedAt: Long, now: Long, keepDays: Int = KEEP_DAYS) = deletedAt > 0 && now - deletedAt >= keepDays * DAY

    /** Whole days left before removal (at least 0). */
    fun daysLeft(deletedAt: Long, now: Long, keepDays: Int = KEEP_DAYS): Int =
        (((deletedAt + keepDays * DAY) - now + DAY - 1) / DAY).toInt().coerceAtLeast(0)
}

/** Text fields remembered across a screen re-creation: matched back by hint, in order. */
object FieldMemo {
    /** Max characters kept per field and in all, so the saved state stays far below the binder limit. */
    const val MAX_FIELD = 50_000
    const val MAX_TOTAL = 200_000
    private val secret = Regex("(?i)(ключ|key|token|токен|парол|password|secret|pin\\b)")

    /** Fields never kept: passwords, API keys, tokens. */
    fun sensitive(hint: String, password: Boolean) = password || secret.containsMatchIn(hint)

    /** What to keep: (hint, text) pairs, the longest texts cut, the total capped. */
    fun keep(fields: List<Pair<String, String>>): List<Pair<String, String>> {
        var total = 0
        val out = ArrayList<Pair<String, String>>()
        for ((h, v) in fields) {
            val s = v.take(MAX_FIELD)
            if (total + s.length > MAX_TOTAL) break
            total += s.length
            out.add(h to s)
        }
        return out
    }

    /** For each current field (by hint) the saved text to put back, or null. Same hints match in order. */
    fun match(saved: List<Pair<String, String>>, current: List<String>): List<String?> {
        val queues = HashMap<String, ArrayDeque<String>>()
        saved.forEach { (h, v) -> queues.getOrPut(h) { ArrayDeque() }.addLast(v) }
        return current.map { h -> queues[h]?.removeFirstOrNull() }
    }
}

/** App-wide search: one normalised matcher for projects, videos, channels, help and settings. */
object SearchIndex {
    data class Item(val kind: String, val title: String, val sub: String = "", val key: String = "", val extra: String = "")
    data class Hit(val item: Item, val score: Int)

    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun tokens(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() }

    /** 0 = no match. Every query word must occur; title matches and word starts weigh more. */
    fun score(query: String, item: Item): Int {
        val qs = tokens(query)
        if (qs.isEmpty()) return 0
        val title = " " + norm(item.title) + " "
        val rest = " " + norm(item.sub + " " + item.extra) + " "
        var score = 0
        for (q in qs) {
            score += when {
                title.contains(" $q ") -> 30
                title.contains(" $q") -> 20
                title.contains(q) -> 10
                rest.contains(" $q") -> 6
                rest.contains(q) -> 3
                else -> return 0
            }
        }
        if (norm(item.title).startsWith(norm(query))) score += 15
        return score
    }

    fun rank(query: String, items: List<Item>, limit: Int = 60): List<Hit> =
        items.asSequence().map { Hit(it, score(query, it)) }.filter { it.score > 0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.item.title.length }).take(limit).toList()
}

/** Order of projects on the home screen: pinned first, then by the chosen key. */
object ProjectOrder {
    val MODES = listOf("recent", "created", "name", "size")

    data class Row(val id: String, val name: String, val createdAt: Long, val lastAt: Long, val size: Int)

    fun sort(rows: List<Row>, mode: String, pinned: Set<String>): List<Row> {
        val base = when (mode) {
            "created" -> rows.sortedByDescending { it.createdAt }
            "name" -> rows.sortedBy { it.name.lowercase() }
            "size" -> rows.sortedByDescending { it.size }
            else -> rows.sortedByDescending { maxOf(it.lastAt, it.createdAt) }
        }
        return base.filter { it.id in pinned } + base.filter { it.id !in pinned }
    }

    fun parsePins(s: String?): Set<String> = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun pins(set: Set<String>): String = set.sorted().joinToString(",")
}

/** Selection over a list: all / none / invert, the count for the selection bar. */
object SelectionLogic {
    fun all(visible: Collection<String>, current: Set<String>) = current + visible
    fun none(visible: Collection<String>, current: Set<String>) = current - visible.toSet()
    fun invert(visible: Collection<String>, current: Set<String>): Set<String> {
        val v = visible.toSet()
        return (current - v) + v.filter { it !in current }
    }
}

/** Settings file: every preference of lumen_prefs except secrets, typed, as JSON (protocol lumen.settings v1). */
object PrefsIo {
    const val PROTOCOL = "lumen.settings"
    private val secret = Regex("(?i)(key|token|secret|password|pin|vault|crash_|_at$|^vl_|pending|last_)")

    fun keepKey(k: String) = !secret.containsMatchIn(k)

    /** Kept by «reset all settings»: interface language, own prompts (they have their own reset), pinned projects. */
    private val survivesReset = Regex("^(lang|prompt_.*|home_pins)$")

    /** Keys removed by «reset all settings»: user options only; keys, tokens and internal state stay. */
    fun resetKeys(all: Collection<String>): List<String> = all.filter { keepKey(it) && !survivesReset.matches(it) }.sorted()

    fun export(all: Map<String, *>): String {
        val o = org.json.JSONObject().put("protocol", PROTOCOL).put("v", 1)
        val v = org.json.JSONObject()
        for ((k, x) in all.toSortedMap()) {
            if (!keepKey(k)) continue
            val typed = when (x) {
                is Boolean -> org.json.JSONObject().put("b", x)
                is Int -> org.json.JSONObject().put("i", x)
                is Long -> org.json.JSONObject().put("l", x)
                is Float -> org.json.JSONObject().put("f", x.toDouble())
                is String -> org.json.JSONObject().put("s", x)
                is Set<*> -> org.json.JSONObject().put("ss", org.json.JSONArray(x.map { it.toString() }))
                else -> null
            } ?: continue
            v.put(k, typed)
        }
        return o.put("values", v).toString(1)
    }

    /** Parsed values (key → Boolean/Int/Long/Float/String/Set<String>); null if the file is not a settings file. */
    fun parse(text: String): Map<String, Any>? {
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("protocol") != PROTOCOL) return null
        val v = o.optJSONObject("values") ?: return null
        val out = LinkedHashMap<String, Any>()
        for (k in v.keys()) {
            if (!keepKey(k)) continue
            val x = v.optJSONObject(k) ?: continue
            out[k] = when {
                x.has("b") -> x.getBoolean("b")
                x.has("i") -> x.getInt("i")
                x.has("l") -> x.getLong("l")
                x.has("f") -> x.getDouble("f").toFloat()
                x.has("s") -> x.getString("s")
                x.has("ss") -> x.getJSONArray("ss").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> continue
            }
        }
        return out
    }
}

/**
 * Scroll position across re-renders of a screen. Screens rebuild their whole page on most taps; the page keeps
 * its place when it is the same view (title + selected tabs), and each tab comes back where it was left.
 */
class ScrollMemo(private val max: Int = 32) {
    private val ys = LinkedHashMap<String, Int>()

    /** Where the new page should scroll to: [curY] is the position of the page being replaced ([prevKey]). */
    fun target(prevKey: String?, newKey: String, curY: Int, keep: Boolean = false): Int {
        if (prevKey != null) {
            ys.remove(prevKey)
            ys[prevKey] = maxOf(0, curY)
            while (ys.size > max) ys.remove(ys.keys.first())
        }
        if (keep) return maxOf(0, curY)
        return ys[newKey] ?: 0
    }
}

package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UxLogicTest {
    @Test
    fun glyphButtonsGetSpokenLabels() {
        assertEquals("Удалить", A11yText.label("🗑"))
        assertEquals("Копировать", A11yText.label(" ⧉ "))
        assertEquals("Изменить", A11yText.label("✏️"))
        // a leading glyph before words: the words alone
        assertEquals("Новый проект", A11yText.label("＋  Новый проект"))
        assertEquals("Своя", A11yText.label("✎ Своя"))
        // plain text reads well by itself
        assertNull(A11yText.label("Сохранить"))
        assertNull(A11yText.label("3. Разбор"))
        assertNull(A11yText.label("⁂"))
        assertTrue(A11yText.glyphOnly("🗑"))
        assertFalse(A11yText.glyphOnly("🗑 Удалить"))
    }

    @Test
    fun undoSlotRevertsOnlyThePendingAction() {
        val log = mutableListOf<String>()
        val slot = UndoSlot()
        val a = slot.push("a", revert = { log += "revert a" }, commit = { log += "commit a" })
        assertEquals("a", slot.pending)
        // a new action makes the previous one final
        val b = slot.push("b", revert = { log += "revert b" }, commit = { log += "commit b" })
        assertEquals(listOf("commit a"), log)
        assertFalse(slot.undo(a))
        assertTrue(slot.undo(b))
        assertEquals(listOf("commit a", "revert b"), log)
        assertNull(slot.pending)
        assertFalse(slot.undo())
        // a timed-out snackbar of an old action does not commit the new one
        val c = slot.push("c", revert = { log += "revert c" }, commit = { log += "commit c" })
        slot.commit(a)
        assertEquals("c", slot.pending)
        slot.commit(c)
        assertEquals("commit c", log.last())
        assertNull(slot.pending)
    }

    @Test
    fun trashKeepsAWeek() {
        val day = 24L * 3600_000
        val at = 1_000_000_000L
        assertFalse(TrashLogic.expired(at, at + 6 * day))
        assertTrue(TrashLogic.expired(at, at + 7 * day))
        assertFalse(TrashLogic.expired(0, at))
        assertEquals(7, TrashLogic.daysLeft(at, at))
        assertEquals(1, TrashLogic.daysLeft(at, at + 6 * day + 1))
        assertEquals(0, TrashLogic.daysLeft(at, at + 30 * day))
    }

    @Test
    fun fieldsMatchBackByHintInOrder() {
        val saved = listOf("Тема" to "кошки", "Заметка" to "раз", "Заметка" to "два")
        assertEquals(listOf("раз", "кошки", "два", null), FieldMemo.match(saved, listOf("Заметка", "Тема", "Заметка", "Заметка")))
        assertTrue(FieldMemo.sensitive("API-ключ", false))
        assertTrue(FieldMemo.sensitive("Bot token", false))
        assertTrue(FieldMemo.sensitive("Тема", true))
        assertFalse(FieldMemo.sensitive("Тема", false))
        val big = "x".repeat(FieldMemo.MAX_FIELD + 10)
        val kept = FieldMemo.keep(List(10) { "h" to big })
        assertEquals(FieldMemo.MAX_FIELD, kept.first().second.length)
        assertTrue(kept.sumOf { it.second.length } <= FieldMemo.MAX_TOTAL)
    }

    @Test
    fun searchRanksTitleWordsFirst() {
        val items = listOf(
            SearchIndex.Item("video", "Как вырастить ёлку", "канал Сад", "1"),
            SearchIndex.Item("video", "Ёлочные игрушки своими руками", "", "2"),
            SearchIndex.Item("project", "Сад и огород", "про ёлки и розы", "3"),
            SearchIndex.Item("video", "Розы", "", "4")
        )
        val hits = SearchIndex.rank("елк", items)
        assertEquals(listOf("1", "3"), hits.map { it.item.key })
        assertTrue(hits.none { it.item.key == "4" })
        assertEquals("1", SearchIndex.rank("вырастить елку", items).first().item.key)
        // every word must occur
        assertTrue(SearchIndex.rank("елка тюльпан", items).isEmpty())
        assertTrue(SearchIndex.rank("  ", items).isEmpty())
        assertEquals("сад огород", SearchIndex.norm("Сад — огород!"))
    }

    @Test
    fun pinnedProjectsComeFirst() {
        val rows = listOf(
            ProjectOrder.Row("a", "Бета", 1, 50, 3),
            ProjectOrder.Row("b", "альфа", 2, 10, 9),
            ProjectOrder.Row("c", "Гамма", 3, 30, 1)
        )
        assertEquals(listOf("a", "c", "b"), ProjectOrder.sort(rows, "recent", emptySet()).map { it.id })
        assertEquals(listOf("b", "a", "c"), ProjectOrder.sort(rows, "name", emptySet()).map { it.id })
        assertEquals(listOf("b", "a", "c"), ProjectOrder.sort(rows, "size", emptySet()).map { it.id })
        assertEquals(listOf("c", "b", "a"), ProjectOrder.sort(rows, "created", emptySet()).map { it.id })
        assertEquals(listOf("c", "a", "b"), ProjectOrder.sort(rows, "recent", setOf("c")).map { it.id })
        assertEquals(setOf("a", "c"), ProjectOrder.parsePins(ProjectOrder.pins(setOf("c", "a"))))
        assertTrue(ProjectOrder.parsePins(null).isEmpty())
    }

    @Test
    fun selectionAllNoneInvert() {
        val visible = listOf("1", "2", "3")
        assertEquals(setOf("1", "2", "3", "9"), SelectionLogic.all(visible, setOf("9")))
        assertEquals(setOf("9"), SelectionLogic.none(visible, setOf("1", "9")))
        assertEquals(setOf("2", "3", "9"), SelectionLogic.invert(visible, setOf("1", "9")))
    }

    @Test
    fun settingsFileSkipsSecretsAndKeepsTypes() {
        val prefs = mapOf<String, Any>("lang" to "en", "text_scale" to 110, "seen" to 5L, "ratio" to 0.5f, "dark" to true,
            "tags" to setOf("a", "b"), "api_key_gemini" to "SECRET", "bot_token" to "123:abc", "crash_seen_at" to 1L)
        val text = PrefsIo.export(prefs)
        assertFalse(text.contains("SECRET") || text.contains("123:abc"))
        val back = PrefsIo.parse(text)!!
        assertEquals("en", back["lang"])
        assertEquals(110, back["text_scale"])
        assertEquals(5L, back["seen"])
        assertEquals(0.5f, back["ratio"])
        assertEquals(true, back["dark"])
        assertEquals(setOf("a", "b"), back["tags"])
        assertFalse(back.containsKey("crash_seen_at"))
        assertNull(PrefsIo.parse("{\"protocol\":\"other\"}"))
        assertNull(PrefsIo.parse("not json"))
    }

    @Test
    fun scrollStaysOnRerenderAndPerTab() {
        val m = ScrollMemo()
        // first render: top
        assertEquals(0, m.target(null, "P|a", 0))
        // tap on a button: same page, same place
        assertEquals(500, m.target("P|a", "P|a", 500))
        // another tab starts at the top, coming back restores the first one
        assertEquals(0, m.target("P|a", "P|b", 700))
        assertEquals(700, m.target("P|b", "P|a", 120))
        assertEquals(120, m.target("P|a", "P|b", 700))
        // explicit keep follows the current position even across keys
        assertEquals(300, m.target("P|b", "Q", 300, keep = true))
        assertEquals(0, m.target(null, "X", -5))
    }
}

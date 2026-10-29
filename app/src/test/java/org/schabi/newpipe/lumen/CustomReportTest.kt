/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.lumen.CustomReport.Item

class CustomReportTest {
    private val md = """
        ```markdown
        # Ниша вязания
        Короткий вывод по теме.

        [kpi] 1 200 | видео
        [kpi] 45% | позитив

        ## Каналы
        | Канал | Видео | Просмотры |
        |---|---|---|
        | Alpha | 5 | 12 000 |
        | Beta | 3 | 4,5K |

        ## Идеи
        - **Первая** идея
          с переносом
        - Вторая идея
        > Совет: начните с [Alpha](http://x.y)

        Обычный *абзац* с `кодом`.
        ```
    """.trimIndent()

    @Test
    fun parsesTitleSubKpisTableListsAndCallout() {
        val d = CustomReport.parse(md)
        assertEquals("Ниша вязания", d.title)
        assertEquals("Короткий вывод по теме.", d.sub)
        val kinds = d.items.map { it::class.simpleName }
        assertEquals(listOf("Kpis", "Section", "Table", "Section", "Bullets", "Callout", "Para"), kinds)
        assertEquals(listOf("1 200" to "видео", "45%" to "позитив"), (d.items[0] as Item.Kpis).items)
        val tb = d.items[2] as Item.Table
        assertEquals(listOf("Канал", "Видео", "Просмотры"), tb.header)
        assertEquals(listOf("Beta", "3", "4,5K"), tb.rows[1])
        val b = d.items[4] as Item.Bullets
        assertEquals(listOf("**Первая** идея с переносом", "Вторая идея"), b.items)
        assertEquals("Совет: начните с Alpha", (d.items[5] as Item.Callout).text)
        assertEquals("Обычный абзац с кодом.", (d.items[6] as Item.Para).text)
    }

    @Test
    fun brokenOrEmptyInputDoesNotThrow() {
        val d = CustomReport.parse("| a | b |\n| только строка таблицы\n#\n> \n[kpi]\n")
        assertEquals("", d.title)
        assertTrue(d.items.none { it is Item.Kpis })
        assertTrue(CustomReport.parse("").items.isEmpty())
    }

    @Test
    fun stripsThinkingAndFences() {
        assertEquals("# T", CustomReport.clean("<think>hm</think>\n```md\n# T\n```"))
    }

    @Test
    fun numbersWithSuffixes() {
        assertEquals(12000.0, CustomReport.number("12 000")!!, 0.0)
        assertEquals(4500.0, CustomReport.number("4,5K")!!, 0.0)
        assertEquals(2_000_000.0, CustomReport.number("2М")!!, 0.0)
        assertEquals(45.0, CustomReport.number("45%")!!, 0.0)
        assertNull(CustomReport.number("n/a"))
    }

    @Test
    fun plainAndHtmlOutputs() {
        val d = CustomReport.parse(md)
        val txt = CustomReport.plain(d)
        assertTrue(txt.startsWith("НИША ВЯЗАНИЯ"))
        assertTrue(txt.contains("• Первая идея с переносом"))
        assertTrue(!txt.contains("**"))
        val html = CustomReport.html(CustomReport.parse("# A <b>\n\n## S\n**x** & y"), "f")
        assertTrue(html.contains("<h1>A &lt;b&gt;</h1>"))
        assertTrue(html.contains("<b>x</b> &amp; y"))
    }

    @Test
    fun formatKinds() {
        assertEquals(CustomReport.Format.HTML, CustomReport.Format.ofKind("custom_html"))
        assertNull(CustomReport.Format.ofKind("custom_docx"))
    }
}

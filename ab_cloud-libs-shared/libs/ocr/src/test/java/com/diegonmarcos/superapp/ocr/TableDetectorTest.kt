package com.diegonmarcos.superapp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDetectorTest {
    @Test fun batteryTableIsThreeColumnsThreeRows() {
        val r = TableDetector.detect(Fixtures.battery())
        assertEquals(1, r.tables.size)
        val t = r.tables[0]
        assertEquals(3, t.columns)
        assertEquals(3, t.rows.size)
        assertEquals(listOf("App", "Screen time", "Usage"), t.rows[0].map { it.text })
        assertEquals(listOf("Chrome", "2 h 14 min", "12.5%"), t.rows[1].map { it.text })
        assertEquals(listOf("Maps", "45 min", "7%"), t.rows[2].map { it.text })
        assertEquals(setOf("Battery usage", "Since last full charge", "Tip: lower brightness", "to save power"), r.rest.map { it.text }.toSet())
    }

    @Test fun cellsCarryRowAndColumn() {
        val t = TableDetector.detect(Fixtures.battery()).tables[0]
        val c = t.rows.flatten().first { it.text == "45 min" }
        assertEquals(2, c.row); assertEquals(1, c.col)
    }

    @Test fun rightAlignedNumbersStayInTheirColumn() {
        val lines = listOf(
            Fixtures.line("Item", 40, 100, 200), Fixtures.line("Price", 800, 100, 900),
            Fixtures.line("Tea", 40, 150, 120), Fixtures.line("3.50 EUR", 740, 150, 900),
            Fixtures.line("Coffee", 40, 200, 190), Fixtures.line("12.00 EUR", 700, 200, 900),
        )
        val t = TableDetector.detect(lines).tables.single()
        assertEquals(2, t.columns)
        assertEquals("12.00 EUR", t.rows[2][1].text)
    }

    @Test fun plainParagraphIsNotATable() {
        val lines = listOf(Fixtures.line("one", 40, 100, 400), Fixtures.line("two", 40, 140, 400), Fixtures.line("three", 40, 180, 400))
        val r = TableDetector.detect(lines)
        assertTrue(r.tables.isEmpty()); assertEquals(3, r.rest.size)
    }

    @Test fun aWideCellDoesNotGlueTwoColumns() {
        val lines = listOf(
            Fixtures.line("A", 40, 100, 100), Fixtures.line("B", 500, 100, 560),
            Fixtures.line("a1", 40, 150, 100), Fixtures.line("b1", 500, 150, 560),
            Fixtures.line("a2 is quite wide and reaches over", 40, 200, 540), Fixtures.line("b2", 560, 200, 620),
        )
        val t = TableDetector.detect(lines).tables.singleOrNull()
        assertTrue(t == null || t.columns >= 2)
    }

    @Test fun farApartRowsAreNotOneTable() {
        val lines = listOf(
            Fixtures.line("A", 40, 100, 100), Fixtures.line("B", 500, 100, 560),
            Fixtures.line("a", 40, 900, 100), Fixtures.line("b", 500, 900, 560),
        )
        assertTrue(TableDetector.detect(lines).tables.isEmpty())
    }
}

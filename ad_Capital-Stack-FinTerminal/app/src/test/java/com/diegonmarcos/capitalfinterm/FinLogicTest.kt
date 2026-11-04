package com.diegonmarcos.capitalfinterm

import com.diegonmarcos.capitalfinterm.FinLogic.Col
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** UI spec 2.x rules behind the components: typed columns, sort, importance, ranges, ticks, heat, spread, flags, day headers, A/F/P. */
class FinLogicTest {
    @Test fun columnsAreTypedFromHeaderAndCellsUnlessDeclared() {
        val cols = listOf("Tenor", "Yield %", "1d bp", "Date")
        val rows = listOf(listOf("1M", "4.07", "-3", "2026-10-28"), listOf("10Y", "5.05", "+6", "2026-10-28"))
        assertEquals(listOf(Col.TEXT, Col.NUM, Col.CHG, Col.DATE), FinLogic.colTypes(emptyList(), cols, rows))
        assertEquals(listOf(Col.TEXT, Col.PCT, Col.CHG, Col.DATE), FinLogic.colTypes(listOf("text", "pct"), cols, rows))
    }

    @Test fun sortIsNumbersDescendingThenReversedThenSourceOrderWithMissingLast() {
        val rows = listOf(listOf("a", "2"), listOf("b", "10"), listOf("c", FinFormat.DASH), listOf("d", "5"))
        assertEquals(listOf(1, 3, 0, 2), FinLogic.sortOrder(rows, 1, Col.NUM, 1))
        assertEquals(listOf(0, 3, 1, 2), FinLogic.sortOrder(rows, 1, Col.NUM, 2))
        assertEquals(listOf(0, 1, 2, 3), FinLogic.sortOrder(rows, 1, Col.NUM, 0))
        assertEquals(listOf(3, 2, 1, 0), FinLogic.sortOrder(rows, 0, Col.TEXT, 2))
    }

    @Test fun importanceCountsFilledDotsOrReadsANumber() {
        assertEquals(2, FinLogic.importance("●●○"))
        assertEquals(3, FinLogic.importance("3"))
        assertEquals(3, FinLogic.importance("●●●●"))
        assertEquals(0, FinLogic.importance(null))
    }

    @Test fun ticksAreNiceSteps() {
        assertEquals(listOf(4.0, 4.5, 5.0, 5.5), FinLogic.ticks(4.0, 5.4, 4))
    }

    @Test fun heatIsClampedAtTheScale() {
        assertEquals(0.5f, FinLogic.heat(1.5, 3.0))
        assertEquals(-1f, FinLogic.heat(-9.0, 3.0))
        assertEquals(0f, FinLogic.heat(null))
    }

    @Test fun theDerivedSpreadIsTenMinusTwoInBasisPoints() {
        val rows = listOf(listOf("UST 2Y", "4.56"), listOf("UST 10Y", "4.94"))
        assertEquals(38.0, FinLogic.spread(rows, "UST 10Y", "UST 2Y")!!, 0.0)
        assertNull(FinLogic.spread(rows, "UST 30Y", "UST 2Y"))
    }

    @Test fun flagsAndDayHeaders() {
        assertEquals("🇬🇧", FinLogic.flag("GB"))
        assertEquals(FinLogic.flag("GB"), FinLogic.flag("uk"))
        assertNull(FinLogic.flag("USA"))
        assertEquals("Thu 05 Nov", FinLogic.dayHeader("2026-11-05"))
    }

    @Test fun actualForecastPreviousAreFoundByTheirColumnNames() {
        val cols = listOf("Time (UTC)", "Ctry", "Event", "Imp", "Cons.", "Actual", "Prev.")
        val a = FinLogic.afp(cols, listOf("2026-10-30T12:30", "US", "Nonfarm payrolls", "3", "158,900", "159,044", "-"))
        assertEquals("159,044", a.actual)
        assertEquals("158,900", a.forecast)
        assertNull(a.previous)
    }

    @Test fun cellsNeverShowABareDash() {
        assertEquals(FinFormat.DASH, FinLogic.cell("-"))
        assertEquals(FinFormat.DASH, FinLogic.change(null))
        assertEquals("+0.12", FinLogic.change("0.12"))
        assertEquals("-0.1", FinLogic.change("-0.1"))
        assertEquals("0.00", FinLogic.change("0.00"))
    }

    @Test fun pinchZoomKeepsTheLatestPointsAndClamps() {
        val pts = (0 until 100).map { ChartPoint("2026-01-01", it.toDouble(), it.toDouble()) }
        assertEquals(pts, FinLogic.zoomed(pts, 1f))
        assertEquals(50, FinLogic.zoomed(pts, 2f).size)
        assertEquals(99.0, FinLogic.zoomed(pts, 2f).last().y, 0.0)
        assertEquals(FinLogic.ZOOM_MIN_POINTS, FinLogic.zoomed(pts, 20f).size)
        assertEquals(pts.take(5), FinLogic.zoomed(pts.take(5), 4f))
    }

    @Test fun rangesFollowTheSeriesLength() {
        val start = LocalDate.parse("2025-01-01")
        val pts = (0 until 400).map { ChartPoint(start.plusDays(it.toLong()).toString(), it.toDouble(), it.toDouble()) }
        assertEquals(32, FinLogic.range(pts, "1M").size)
        assertEquals(pts, FinLogic.range(pts, "MAX"))
        assertEquals(listOf("1M", "6M", "1Y", "5Y"), FinLogic.ranges(pts, curve = false))
        assertEquals(listOf("1M", "6M"), FinLogic.ranges(pts.take(60), curve = false))
        assertEquals(emptyList<String>(), FinLogic.ranges(pts, curve = true))
    }

    @Test fun aCardTableShowsEightRowsUntilExpanded() {
        assertEquals(8, FinLogic.visibleRows(24, false))
        assertEquals(24, FinLogic.visibleRows(24, true))
        assertEquals(5, FinLogic.visibleRows(5, false))
    }

    @Test fun wideTablesSizeEveryColumnToItsLongestCellAndScrollWhenTheyDoNotFit() {
        val cols = listOf("Name", "Last", "WTD", "MTD", "YTD", "1M", "3M", "1Y", "3Y", "10Y")
        val rows = listOf(listOf("XLK Technology", "188.74", "+3.01%", "-3.58%", "+31.10%", "+1.00%", "+2.00%", "+12.82%", "+10.10%", "+14.50%"))
        val l = FinLogic.layout(cols, rows, 360f)
        assertEquals(cols.size, l.widths.size)
        assertFalse(l.fits)
        // "+12.82%" is 7 mono characters: its column is wider than the old fixed 64dp and holds every character
        for (i in 1 until cols.size) assertTrue("col $i ${l.widths[i]}", l.widths[i] >= rows[0][i].length * FinLogic.CHAR_NUM + FinLogic.CELL_PAD)
        assertTrue(l.widths[4] > 64f)
        // the pinned name holds its one-line name and stays within 45 % of the width
        assertTrue(l.widths[0] >= "XLK Technology".length * FinLogic.CHAR_TEXT + FinLogic.CELL_PAD && l.widths[0] <= 360f * 0.45f)
        // a narrow table fits and fills the width exactly
        val small = FinLogic.layout(listOf("Name", "Last"), listOf(listOf("DAX", "1.0")), 360f)
        assertTrue(small.fits)
        assertEquals(360f, small.widths.sum(), 0.01f)
    }
}

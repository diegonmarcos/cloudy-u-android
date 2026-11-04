package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.CalendarMath
import com.diegonmarcos.cloudagenda.data.MockTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** #1464 month paging, the year grid layout and the date math of the Calendar. */
@RunWith(RobolectricTestRunner::class)
class CalendarMathTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun `a pager page and its month map to each other across the whole range`() {
        assertEquals(0, CalendarMath.pageOf(YearMonth.of(CalendarMath.MIN_YEAR, 1)))
        assertEquals(CalendarMath.PAGES - 1, CalendarMath.pageOf(YearMonth.of(CalendarMath.MAX_YEAR, 12)))
        for (p in listOf(0, 1, 11, 12, 1520, CalendarMath.PAGES - 1)) assertEquals(p, CalendarMath.pageOf(CalendarMath.monthOf(p)))
        assertEquals(YearMonth.of(2026, 11), CalendarMath.monthOf(CalendarMath.pageOf(YearMonth.of(2026, 11))))
        // a swipe is page +- 1: December rolls to the next January
        assertEquals(YearMonth.of(2027, 1), CalendarMath.monthOf(CalendarMath.pageOf(YearMonth.of(2026, 12)) + 1))
        assertEquals(YearMonth.of(2025, 12), CalendarMath.monthOf(CalendarMath.pageOf(YearMonth.of(2026, 1)) - 1))
    }

    @Test fun `out of range pages and months are clamped, any year in range is reachable`() {
        assertEquals(YearMonth.of(CalendarMath.MIN_YEAR, 1), CalendarMath.monthOf(-5))
        assertEquals(YearMonth.of(CalendarMath.MAX_YEAR, 12), CalendarMath.monthOf(CalendarMath.PAGES + 40))
        assertEquals(0, CalendarMath.pageOf(YearMonth.of(1800, 6)))
        assertEquals(YearMonth.of(CalendarMath.MAX_YEAR, 12), CalendarMath.shiftYear(YearMonth.of(CalendarMath.MAX_YEAR, 3), 1))
        assertEquals(YearMonth.of(2024, 2), CalendarMath.shiftYear(YearMonth.of(2026, 2), -2))
        assertEquals(YearMonth.of(2027, 1), CalendarMath.shiftMonth(YearMonth.of(2026, 12), 1))
        for (y in listOf(CalendarMath.MIN_YEAR, 1999, 2026, 2100, CalendarMath.MAX_YEAR)) assertEquals(y, CalendarMath.monthOf(CalendarMath.pageOf(YearMonth.of(y, 7))).year)
    }

    @Test fun `the year grid is 12 months in 4 rows of 3, January first`() {
        val g = CalendarMath.yearGrid(2026)
        assertEquals(CalendarMath.YEAR_ROWS, g.size)
        assertTrue(g.all { it.size == CalendarMath.YEAR_COLUMNS })
        assertEquals((1..12).map { YearMonth.of(2026, it) }, g.flatten())
        assertEquals(YearMonth.of(2026, 1), g[0][0])
        assertEquals(YearMonth.of(2026, 12), g[3][2])
    }

    @Test fun `every mini month is 6 weeks of 7 with Monday first and all its days once`() {
        for (m in (1..12).map { YearMonth.of(2026, it) } + YearMonth.of(2024, 2)) {
            val w = CalendarMath.miniMonth(m)
            assertEquals(6, w.size)
            assertTrue(w.all { it.size == 7 })
            assertEquals((1..m.lengthOfMonth()).map { m.atDay(it) }, w.flatten().filterNotNull())
        }
        // 1 November 2026 is a Sunday: six padding cells, then the 1st in the last column of week one
        assertEquals(listOf(null, null, null, null, null, null, LocalDate.of(2026, 11, 1)), CalendarMath.miniMonth(YearMonth.of(2026, 11))[0])
    }

    @Test fun `task days are marked with their project ring in the year grid`() {
        val tasks = MockTasks.ofKind(MockTasks.parse(File("src/main/assets/mock-tasks.json").readText()), "tasks")
        val rings = MockTasks.rings(tasks)
        val marks = CalendarMath.markers(YearMonth.from(today), tasks, today, rings)
        assertTrue(marks.isNotEmpty())
        for ((day, idx) in marks) {
            assertEquals(YearMonth.from(today), YearMonth.from(day))
            assertTrue(idx.isNotEmpty() && idx.all { it in rings.indices })
            assertEquals(MockTasks.on(tasks, today, day).map { rings.indexOf(it.subject) }.distinct().take(4), idx)
        }
        assertNull(marks[LocalDate.of(2026, 11, 4).plusMonths(60)])
        assertTrue(CalendarMath.markers(YearMonth.of(1950, 3), tasks, today, rings).isEmpty())
    }

    @Test fun `the view modes are read from the build declaration`() {
        val modes = CalendarMath.parseModes(com.diegonmarcos.cloudagenda.BuildConfig.UI_CALENDAR_VIEWS)
        assertEquals(listOf(CalendarMath.MODE_MONTH, CalendarMath.MODE_YEAR), modes.map { it.id })
        assertEquals(listOf("Month", "Year"), modes.map { it.label })
        assertTrue(CalendarMath.parseModes("").isEmpty())
        assertEquals(listOf("a"), CalendarMath.parseModes("a:A,broken,:x,y:").map { it.id })
        val decl = org.json.JSONObject(File("build.json").readText()).getJSONObject("ui").getJSONArray("calendar_views")
        assertEquals(modes.map { it.id }, (0 until decl.length()).map { decl.getJSONObject(it).getString("id") })
    }
}

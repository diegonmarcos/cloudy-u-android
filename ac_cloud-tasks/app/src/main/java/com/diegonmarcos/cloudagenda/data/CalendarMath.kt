package com.diegonmarcos.cloudagenda.data

import java.time.LocalDate
import java.time.YearMonth

/** One declared view mode of the Calendar (build.json::ui.calendar_views). */
data class CalendarMode(val id: String, val label: String)

/** #1464 the pure date math behind the Calendar's month pager, year selector and Year view (no Compose, unit-tested). */
object CalendarMath {
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2200
    const val PAGES = (MAX_YEAR - MIN_YEAR + 1) * 12
    const val YEAR_COLUMNS = 3
    const val YEAR_ROWS = 4
    const val MODE_MONTH = "month"
    const val MODE_YEAR = "year"

    /** "month:Month,year:Year" (BuildConfig.UI_CALENDAR_VIEWS) to the declared modes; a malformed pair is skipped. */
    fun parseModes(raw: String): List<CalendarMode> =
        raw.split(',').mapNotNull { p -> val i = p.indexOf(':'); if (i <= 0 || i == p.lastIndex) null else CalendarMode(p.substring(0, i).trim(), p.substring(i + 1).trim()) }

    /** The pager page that shows [m]; months outside MIN_YEAR..MAX_YEAR are clamped. */
    fun pageOf(m: YearMonth): Int { val c = clamp(m); return (c.year - MIN_YEAR) * 12 + c.monthValue - 1 }

    fun monthOf(page: Int): YearMonth { val p = page.coerceIn(0, PAGES - 1); return YearMonth.of(MIN_YEAR + p / 12, p % 12 + 1) }

    fun clamp(m: YearMonth): YearMonth = when {
        m.year < MIN_YEAR -> YearMonth.of(MIN_YEAR, 1)
        m.year > MAX_YEAR -> YearMonth.of(MAX_YEAR, 12)
        else -> m
    }

    /** The prev / next year controls: same month, [years] away, clamped to the reachable range. */
    fun shiftYear(m: YearMonth, years: Int): YearMonth = clamp(m.plusYears(years.toLong()))

    fun shiftMonth(m: YearMonth, months: Int): YearMonth = clamp(m.plusMonths(months.toLong()))

    /** Year view layout: [YEAR_ROWS] rows of [YEAR_COLUMNS] months, January top-left, row-major. */
    fun yearGrid(year: Int): List<List<YearMonth>> =
        (1..12).map { YearMonth.of(year, it) }.chunked(YEAR_COLUMNS)

    /** A mini month: always 6 weeks of 7 (Monday first, null = padding) so every month in the year grid has the same height. */
    fun miniMonth(m: YearMonth): List<List<LocalDate?>> {
        val cells = MockTasks.monthGrid(m)
        return (cells + List(42 - cells.size) { null }).chunked(7)
    }

    /** The task-day markers of a month: for each day with tasks, the ring (project) indices on it, in ring order, at most [max]. */
    fun markers(m: YearMonth, tasks: List<MockTask>, today: LocalDate, rings: List<String>, max: Int = 4): Map<LocalDate, List<Int>> =
        (1..m.lengthOfMonth()).mapNotNull { d ->
            val day = m.atDay(d)
            val idx = MockTasks.on(tasks, today, day).map { rings.indexOf(it.subject) }.distinct().take(max)
            if (idx.isEmpty()) null else day to idx
        }.toMap()
}

package com.diegonmarcos.cloudagenda.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One dated event of the bar calendars: [start]..[end] inclusive, in the lane [lane] (its category). */
data class BarEvent(val id: String, val title: String, val lane: String, val start: LocalDate, val end: LocalDate, val status: MockStatus = MockStatus.TODO)

/**
 * One bar piece inside ONE week row: columns [col0]..[col1] (Monday = 0), on stacked row [row] of the week. A multi-day event is one
 * piece per week it touches, so it draws as one continuous rectangle across the days and wraps across the week rows; [fromPrev] / [toNext]
 * say the event goes on beyond this piece's left / right end (the end is then drawn square).
 */
data class BarSegment(val event: BarEvent, val col0: Int, val col1: Int, val row: Int, val fromPrev: Boolean, val toNext: Boolean)

/** A week row of the month grid: its Monday, the days (null = outside the month), the bar pieces, the stacked-row count and (Gantt) the rows each lane takes. */
data class WeekBars(val weekStart: LocalDate, val days: List<LocalDate?>, val segments: List<BarSegment>, val rows: Int, val laneRows: List<Pair<String, Int>>)

/** The pure half of the Linear Bars and Gantt Calendar modes (no Compose, unit-tested). */
object CalendarBars {
    fun events(items: List<MockTask>, today: LocalDate): List<BarEvent> = items.mapNotNull { t ->
        val s = t.start(today) ?: return@mapNotNull null
        val e = t.end(today) ?: return@mapNotNull null
        BarEvent(t.id, t.title, t.lane, s, e, t.status)
    }

    /** The month as week rows, Monday first, null = a day of the neighbouring months (so every row has 7 cells). */
    fun weeks(month: YearMonth): List<List<LocalDate?>> {
        val cells = MockTasks.monthGrid(month)
        return (cells + List((7 - cells.size % 7) % 7) { null }).chunked(7)
    }

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The lanes in display order: the configured ones that exist first (in config order), then every other category, alphabetically. */
    fun laneOrder(categories: Collection<String>, configured: List<String>): List<String> {
        val present = categories.distinct()
        val first = configured.filter { c -> present.any { it.equals(c, ignoreCase = true) } }.map { c -> present.first { it.equals(c, ignoreCase = true) } }.distinct()
        return first + present.filter { it !in first }.sortedBy { it.lowercase() }
    }

    /** The events that touch [month]. */
    fun inMonth(events: List<BarEvent>, month: YearMonth): List<BarEvent> =
        events.filter { !it.end.isBefore(month.atDay(1)) && !it.start.isAfter(month.atEndOfMonth()) }

    /**
     * Linear Bars ([lanes] null): the pieces of week [weekStart] packed on shared stacked rows, first fit, earliest start first.
     * Gantt Calendar ([lanes] given): every lane is its own strip of rows in that order, a lane holds at least one row, and a piece
     * stacks only inside its own lane; [BarSegment.row] is the absolute row either way.
     */
    fun week(weekStart: LocalDate, month: YearMonth, events: List<BarEvent>, lanes: List<String>? = null): WeekBars {
        val days = List(7) { weekStart.plusDays(it.toLong()).takeIf { d -> YearMonth.from(d) == month } }
        val shown = days.filterNotNull()
        if (shown.isEmpty()) return WeekBars(weekStart, days, emptyList(), 1, lanes?.map { it to 1 }.orEmpty())
        val from = shown.first()
        val to = shown.last()
        val touching = events.filter { !it.end.isBefore(from) && !it.start.isAfter(to) }
        fun piece(e: BarEvent, row: Int) = BarSegment(
            e, ChronoUnit.DAYS.between(weekStart, maxOf(e.start, from)).toInt(), ChronoUnit.DAYS.between(weekStart, minOf(e.end, to)).toInt(),
            row, fromPrev = e.start.isBefore(from), toNext = e.end.isAfter(to),
        )
        // first fit: a row is free for a piece when the last piece on it ended before the piece's first column
        fun pack(list: List<BarEvent>, base: Int): Pair<List<BarSegment>, Int> {
            val ends = ArrayList<Int>()
            val out = ArrayList<BarSegment>()
            for (e in list.sortedWith(compareBy<BarEvent>({ maxOf(it.start, from) }, { -ChronoUnit.DAYS.between(it.start, it.end) }, { it.title }, { it.id }))) {
                val seg = piece(e, 0)
                var r = ends.indexOfFirst { it < seg.col0 }
                if (r < 0) { r = ends.size; ends.add(seg.col1) } else ends[r] = seg.col1
                out += seg.copy(row = base + r)
            }
            return out to ends.size
        }
        if (lanes == null) {
            val (segs, n) = pack(touching, 0)
            return WeekBars(weekStart, days, segs, n.coerceAtLeast(1), emptyList())
        }
        val segs = ArrayList<BarSegment>()
        val laneRows = ArrayList<Pair<String, Int>>()
        var base = 0
        for (lane in lanes) {
            val (s, n) = pack(touching.filter { it.lane == lane }, base)
            segs += s
            val rows = n.coerceAtLeast(1)
            laneRows += lane to rows
            base += rows
        }
        return WeekBars(weekStart, days, segs, base.coerceAtLeast(1), laneRows)
    }

    /** Every week row of [month]. */
    fun month(month: YearMonth, events: List<BarEvent>, lanes: List<String>? = null): List<WeekBars> =
        weeks(month).map { w -> week(monday(w.first { it != null }!!), month, events, lanes) }
}

/** #1757 the Calendar's styles (build.json::ui.calendar_styles): Circles Day is the default, Linear Bars and Gantt Calendar draw events as bars across the month grid. */
object CalendarStyles {
    const val CIRCLES = "circles"
    const val LINEAR = "linear"
    const val GANTT = "gantt"
    val ALL = listOf(CalendarMode(CIRCLES, "Circles Day"), CalendarMode(LINEAR, "Linear Bars"), CalendarMode(GANTT, "Gantt Calendar"))

    /** An unknown id (a config written for a newer build) falls back to the default. */
    fun of(id: String?): String = ALL.firstOrNull { it.id == id }?.id ?: CIRCLES
}

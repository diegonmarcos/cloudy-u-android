package com.diegonmarcos.superapp.cal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One dated item of the Agenda table: an event of the cal engine, in UTC millis as the engine hands it over. */
data class TaskEvent(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean)

/** One item of the Todo table: a VTODO of the cal engine's mirror. */
data class TaskTodo(val summary: String, val status: String, val dueMs: Long?) {
    val open: Boolean get() = status != "COMPLETED" && status != "CANCELLED"
}

/** One row of the Agenda table: date, time, title. */
data class AgendaRow(val date: LocalDate, val time: String, val title: String)

/** What the Tasks popup reads, in two calls; both throw the sentence that says what is missing (engine not installed, too old). */
interface TasksSource {
    fun events(fromMs: Long, toMs: Long): List<TaskEvent>
    fun todos(): List<TaskTodo>
}

/** The popup's pure logic: no Android, so a plain JVM test drives it. */
object TasksModel {

    /**
     * #1738 the popup's max height: the screen height still free below the anchor (window insets already taken off by the caller),
     * so the whole Calendar view fits without scrolling. Falls back to the declared [declMaxDp] when the space is unknown (null/<=0);
     * it never goes under [minDp]. The popup scrolls only when its content truly exceeds this.
     */
    fun bubbleMaxHeightDp(availableDp: Int?, declMaxDp: Int, minDp: Int = 240): Int =
        if (availableDp == null || availableDp <= 0) declMaxDp else maxOf(availableDp, minDp)

    /** #1738 the day the Agenda is filtered to: the tapped day, else today in the Calendar view, else null (the full agenda, Agenda view). */
    fun agendaDay(view: String, picked: LocalDate?, today: LocalDate): LocalDate? =
        picked ?: if (view == "calendar") today else null

    /** The shown months in order: [before] months before [today]'s, its own, [after] months after: 3 for the declared 1 and 1. */
    fun months(today: LocalDate, before: Int, after: Int): List<YearMonth> =
        (-before..after).map { YearMonth.from(today).plusMonths(it.toLong()) }

    /** [month]'s cells week by week from [firstDow]: null pads the leading and trailing blanks; the size is a multiple of 7. */
    fun grid(month: YearMonth, firstDow: DayOfWeek): List<LocalDate?> {
        val lead = ((month.atDay(1).dayOfWeek.value - firstDow.value) + 7) % 7
        val cells = ArrayList<LocalDate?>()
        repeat(lead) { cells.add(null) }
        for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    /** The days any event touches (an event spanning days marks each; an all-day event is a date in UTC, a timed one in [zone]). */
    fun eventDays(events: List<TaskEvent>, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        for (e in events) {
            val z = if (e.allDay) ZoneOffset.UTC else zone
            val first = Instant.ofEpochMilli(e.startMs).atZone(z).toLocalDate()
            // An end at exactly midnight (every all-day event, and a timed one that ends at 00:00) belongs to the day before.
            val endInstant = Instant.ofEpochMilli(maxOf(e.endMs, e.startMs)).atZone(z)
            var last = endInstant.toLocalDate()
            if (e.endMs > e.startMs && endInstant.toLocalTime().toSecondOfDay() == 0) last = last.minusDays(1)
            var d = first
            var n = 0
            while (!d.isAfter(last) && n++ < 62) { out.add(d); d = d.plusDays(1) }
        }
        return out
    }

    /**
     * The Agenda table: items from [from] to [windowDays] later (or only the day [day], when the owner tapped one), date then
     * time, soonest first, at most [max]. An event already under way keeps its row, dated today; one that ended before
     * [from] goes.
     */
    fun agenda(
        events: List<TaskEvent>, from: LocalDate, day: LocalDate?, windowDays: Int, max: Int, zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgendaRow> {
        val tf = DateTimeFormatter.ofPattern("HH:mm")
        val until = from.plusDays(windowDays.toLong())
        val rows = ArrayList<Pair<LocalDateTime, AgendaRow>>()
        for (e in events) {
            val z = if (e.allDay) ZoneOffset.UTC else zone
            val start = Instant.ofEpochMilli(e.startMs).atZone(z)
            val days = eventDays(listOf(e), zone)
            val shown: LocalDate = when {
                days.isEmpty() -> continue
                day != null -> if (day in days) day else continue
                else -> maxOf(start.toLocalDate(), from).takeIf { d -> days.any { !it.isBefore(from) } && !d.isAfter(until) } ?: continue
            }
            val time = if (e.allDay) LocalTime.MIN else start.toLocalTime()
            rows.add(LocalDateTime.of(shown, time) to AgendaRow(shown, if (e.allDay) "all day" else time.format(tf), e.title))
        }
        return rows.sortedBy { it.first }.map { it.second }.take(max)
    }

    /** The Todo table: open items, the dated ones first by due date, then the undated, at most [max]. */
    fun openTodos(todos: List<TaskTodo>, max: Int): List<TaskTodo> =
        todos.filter { it.open }.sortedWith(compareBy<TaskTodo> { it.dueMs == null }.thenBy { it.dueMs ?: 0L }).take(max)

    /** The window the Agenda asks the engine for: from the start of [today] for [days] days (and a month either side for the dots). */
    fun window(today: LocalDate, monthsBefore: Int, monthsAfter: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val first = YearMonth.from(today).minusMonths(monthsBefore.toLong()).atDay(1)
        val last = YearMonth.from(today).plusMonths(monthsAfter.toLong()).atEndOfMonth().plusDays(1)
        return first.atStartOfDay(zone).toInstant().toEpochMilli() to last.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

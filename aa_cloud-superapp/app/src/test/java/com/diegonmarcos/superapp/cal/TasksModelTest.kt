package com.diegonmarcos.superapp.cal

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The Tasks popup's declaration and logic.
 *
 *   M1 the baked declaration: label Tasks, default view calendar, three months (previous, current, next)
 *   M2 a month count that is not before + 1 + after is refused; so is a default view that is not a view
 *   M3 months() gives previous, current, next across a year boundary
 *   M4 grid() pads to whole weeks from the first weekday
 *   M5 the Agenda: soonest first, only from today, one day when filtered, capped; all-day events keep their date
 *   M6 the Todo table: open items only, dated first
 *   M7 the engine's answers parse, and a failure is thrown, never drawn as empty
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TasksModelTest {

    private val zone = ZoneId.of("UTC")
    private fun ms(d: LocalDate, h: Int = 9) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 30)

    @Test
    fun `M1 the baked declaration`() {
        val d = TasksPopupDecl.declared
        assertEquals("Tasks", d.label)
        assertEquals("calendar", d.defaultView)
        assertEquals(3, d.monthCount)
        assertEquals(1, d.monthsBefore); assertEquals(1, d.monthsAfter)
        assertTrue(d.widthDp > 0 && d.maxHeightDp > d.tableHeightDp)
        assertEquals("Agenda", d.agendaLabel); assertEquals("Todo", d.todoLabel)
    }

    @Test
    fun `M2 an inconsistent declaration is refused`() {
        fun decl(count: Int, def: String) = """{"label":"Tasks","default_view":"$def","views":[{"id":"calendar","label":"Calendar"}],
          "months":{"count":$count,"before":1,"after":1},"layout":{"width_dp":1,"max_height_dp":1,"month_cell_dp":1,"table_height_dp":1,"table_height_agenda_view_dp":1},
          "tables":{"agenda":{"label":"A","window_days":1,"max_rows":1},"todo":{"label":"T","max_rows":1,"complete":false}}}"""
        assertEquals(3, TasksPopupDecl.parse(decl(3, "calendar")).monthCount)
        assertTrue(runCatching { TasksPopupDecl.parse(decl(4, "calendar")) }.isFailure)
        assertTrue(runCatching { TasksPopupDecl.parse(decl(3, "week")) }.isFailure)
    }

    @Test
    fun `M3 previous current next across a year`() {
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), TasksModel.months(today, 1, 1))
        assertEquals(listOf(YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2)), TasksModel.months(LocalDate.of(2027, 1, 5), 1, 1))
    }

    @Test
    fun `M4 grid pads to whole weeks`() {
        val g = TasksModel.grid(YearMonth.of(2026, 10), DayOfWeek.MONDAY)   // 1 Oct 2026 is a Thursday
        assertEquals(0, g.size % 7)
        assertEquals(3, g.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 10, 1), g[3])
        assertEquals(31, g.filterNotNull().size)
    }

    @Test
    fun `M5 the agenda`() {
        val ev = listOf(
            TaskEvent("later", ms(today.plusDays(3)), ms(today.plusDays(3), 10), false),
            TaskEvent("past", ms(today.minusDays(5)), ms(today.minusDays(5), 10), false),
            TaskEvent("now", ms(today, 8), ms(today, 9), false),
            TaskEvent("holiday", ms(today.plusDays(1), 0), ms(today.plusDays(2), 0), true),
            TaskEvent("far", ms(today.plusDays(200)), ms(today.plusDays(200), 10), false),
        )
        val rows = TasksModel.agenda(ev, today, null, 60, 10, zone)
        assertEquals(listOf("now", "holiday", "later"), rows.map { it.title })
        assertEquals("08:00", rows[0].time)
        assertEquals("all day", rows[1].time)
        assertEquals(today.plusDays(1), rows[1].date)
        assertEquals(listOf("later"), TasksModel.agenda(ev, today, today.plusDays(3), 60, 10, zone).map { it.title })
        assertEquals(2, TasksModel.agenda(ev, today, null, 60, 2, zone).size)
        assertEquals(setOf(today.plusDays(1)), TasksModel.eventDays(listOf(ev[3]), zone))
    }

    @Test
    fun `M5b popup height uses the available space and the agenda day is today in the Calendar view`() {
        assertEquals(800, TasksModel.bubbleMaxHeightDp(800, 640))
        assertEquals(640, TasksModel.bubbleMaxHeightDp(null, 640))
        assertEquals(640, TasksModel.bubbleMaxHeightDp(0, 640))
        assertEquals(240, TasksModel.bubbleMaxHeightDp(100, 640))
        assertEquals(today, TasksModel.agendaDay("calendar", null, today))
        assertEquals(today.plusDays(2), TasksModel.agendaDay("calendar", today.plusDays(2), today))
        assertEquals(null, TasksModel.agendaDay("agenda", null, today))
        val ev = listOf(TaskEvent("now", ms(today, 8), ms(today, 9), false), TaskEvent("later", ms(today.plusDays(3)), ms(today.plusDays(3), 10), false))
        assertEquals(listOf("now"), TasksModel.agenda(ev, today, TasksModel.agendaDay("calendar", null, today), 60, 10, zone).map { it.title })
        assertEquals(listOf("now", "later"), TasksModel.agenda(ev, today, TasksModel.agendaDay("agenda", null, today), 60, 10, zone).map { it.title })
    }

    @Test
    fun `M6 the todo table`() {
        val t = listOf(
            TaskTodo("undated", "NEEDS-ACTION", null), TaskTodo("done", "COMPLETED", 5L),
            TaskTodo("soon", "IN-PROCESS", 100L), TaskTodo("sooner", "NEEDS-ACTION", 50L), TaskTodo("gone", "CANCELLED", null),
        )
        assertEquals(listOf("sooner", "soon", "undated"), TasksModel.openTodos(t, 10).map { it.summary })
        assertEquals(1, TasksModel.openTodos(t, 1).size)
    }

    @Test
    fun `M7 the engine answers`() {
        val e = TasksFeed.parseEvents("""[{"title":"x","start":10,"end":20,"allDay":true}]""")
        assertEquals(TaskEvent("x", 10, 20, true), e.single())
        val t = TasksFeed.parseTodos("""[{"summary":"y","status":"NEEDS-ACTION","due":"77"},{"summary":"z","status":"COMPLETED","due":""}]""")
        assertEquals(77L, t[0].dueMs); assertEquals(null, t[1].dueMs)
        val err = runCatching { TasksFeed.parseEvents("""{"error":"offline"}""") }.exceptionOrNull()
        assertEquals("offline", err?.message)
    }
}

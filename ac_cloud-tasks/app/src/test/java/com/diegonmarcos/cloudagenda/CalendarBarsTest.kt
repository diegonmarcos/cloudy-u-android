package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.BarEvent
import com.diegonmarcos.cloudagenda.data.CalendarBars
import com.diegonmarcos.cloudagenda.data.CalendarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** #1757 Linear Bars and Gantt Calendar: bar segmentation across week rows, lane order, the year swipe. Pure JVM. */
class CalendarBarsTest {
    // November 2026 starts on a Sunday: the first week row holds only the 1st (column 6).
    private val nov = YearMonth.of(2026, 11)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private fun ev(id: String, a: LocalDate, b: LocalDate, lane: String = "") = BarEvent(id, id, lane, a, b)

    @Test fun `the month is week rows of seven days, Monday first`() {
        val weeks = CalendarBars.weeks(nov)
        assertEquals(6, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(listOf<LocalDate?>(null, null, null, null, null, null, d(1)), weeks[0])
        assertEquals(d(30), weeks[5][0])
        assertEquals(30, weeks.flatten().count { it != null })
    }

    @Test fun `a multi-day event is one piece per week row it touches, continuous across the days`() {
        val rows = CalendarBars.month(nov, listOf(ev("trip", d(5), d(17))))
        val pieces = rows.map { w -> w.segments.map { it.col0 to it.col1 } }
        // Thu 5 .. Sun 8, then the whole week of the 9th, then Mon 16 and Tue 17
        assertEquals(listOf(emptyList(), listOf(3 to 6), listOf(0 to 6), listOf(0 to 1), emptyList(), emptyList()), pieces)
        val flags = rows.flatMap { w -> w.segments.map { it.fromPrev to it.toNext } }
        assertEquals(listOf(false to true, true to true, true to false), flags)
        // every day of the event is covered exactly once
        val covered = rows.flatMap { w -> w.segments.flatMap { s -> (s.col0..s.col1).map { w.weekStart.plusDays(it.toLong()) } } }
        assertEquals((5..17).map { d(it) }, covered)
    }

    @Test fun `a one-day event is a one-column piece and an event outside the month draws nothing`() {
        val rows = CalendarBars.month(nov, listOf(ev("one", d(3), d(3)), ev("away", LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 5))))
        val all = rows.flatMap { it.segments }
        assertEquals(1, all.size)
        assertEquals(1, all.single().col0)
        assertEquals(1, all.single().col1)
        assertFalse(all.single().fromPrev || all.single().toNext)
    }

    @Test fun `an event that starts before the month is clipped to its first day and says it comes from before`() {
        val rows = CalendarBars.month(nov, listOf(ev("long", LocalDate.of(2026, 10, 28), d(3))))
        val first = rows[0].segments.single()
        assertEquals(6 to 6, first.col0 to first.col1)
        assertTrue(first.fromPrev)
        val second = rows[1].segments.single()
        assertEquals(0 to 1, second.col0 to second.col1)
        assertFalse(second.toNext)
    }

    @Test fun `overlapping events stack on separate rows and a free row is reused`() {
        val w = CalendarBars.week(d(9), nov, listOf(ev("a", d(9), d(11)), ev("b", d(10), d(12)), ev("c", d(12), d(13)), ev("d", d(9), d(9))))
        val row = w.segments.associate { it.event.id to it.row }
        // first fit, earliest start first: a takes row 0, d (the 9th only) row 1, b (10th..12th) the free part of row 1, c (the 12th) the row a left
        assertEquals(mapOf("a" to 0, "d" to 1, "b" to 1, "c" to 0), row)
        assertEquals(2, w.rows)
        // no two pieces on the same row overlap in columns
        for (x in w.segments) for (y in w.segments) if (x !== y && x.row == y.row) assertTrue(x.col1 < y.col0 || y.col1 < x.col0)
    }

    @Test fun `the Gantt Calendar gives each category its own lane, in the order given, with absolute rows`() {
        val events = listOf(
            ev("hotel", d(9), d(12), "Lodging"), ev("airbnb", d(10), d(13), "Lodging"),
            ev("train", d(10), d(10), "Transportation"), ev("show", d(11), d(11), "Events"),
        )
        val lanes = listOf("Lodging", "Transportation", "Events")
        val w = CalendarBars.week(d(9), nov, events, lanes)
        assertEquals(listOf("Lodging" to 2, "Transportation" to 1, "Events" to 1), w.laneRows)
        assertEquals(4, w.rows)
        val row = w.segments.associate { it.event.id to it.row }
        assertEquals(setOf(0, 1), setOf(row["hotel"], row["airbnb"]))
        assertEquals(2, row["train"])
        assertEquals(3, row["show"])
        // an empty lane still holds its row, so every week row lines up with the label column
        val quiet = CalendarBars.week(d(23), nov, events, lanes)
        assertEquals(listOf("Lodging" to 1, "Transportation" to 1, "Events" to 1), quiet.laneRows)
        assertTrue(quiet.segments.isEmpty())
    }

    @Test fun `lane order - the configured categories first in config order, then the others alphabetically`() {
        val configured = listOf("Lodging", "Transportation", "Events")
        assertEquals(
            listOf("Lodging", "Transportation", "Events", "Admin", "Zoo"),
            CalendarBars.laneOrder(listOf("Zoo", "Events", "Admin", "Lodging", "Transportation", "Events"), configured),
        )
        // a configured lane with no event is not drawn, the match ignores case, no config = alphabetical
        assertEquals(listOf("Events", "Admin"), CalendarBars.laneOrder(listOf("Admin", "Events"), configured))
        assertEquals(listOf("LODGING", "Zed"), CalendarBars.laneOrder(listOf("Zed", "LODGING"), configured))
        assertEquals(listOf("A", "b", "C"), CalendarBars.laneOrder(listOf("C", "b", "A"), emptyList()))
    }

    @Test fun `year swipe - left or up is the next year, right or down the previous, a short drag nothing`() {
        assertEquals(1, CalendarMath.swipeYear(-200f, 10f, 100f))
        assertEquals(-1, CalendarMath.swipeYear(200f, -10f, 100f))
        assertEquals(1, CalendarMath.swipeYear(5f, -300f, 100f))
        assertEquals(-1, CalendarMath.swipeYear(-5f, 300f, 100f))
        assertEquals(0, CalendarMath.swipeYear(40f, 30f, 100f))
        // the dominant axis wins
        assertEquals(1, CalendarMath.swipeYear(-120f, -400f, 100f))
        assertEquals(-1, CalendarMath.swipeYear(-120f, 400f, 100f))
        // applied to the shown year, clamped to the reachable range
        assertEquals(2027, CalendarMath.shiftYear(YearMonth.of(2026, 1), CalendarMath.swipeYear(-300f, 0f, 100f)).year)
        assertEquals(CalendarMath.MAX_YEAR, CalendarMath.shiftYear(YearMonth.of(CalendarMath.MAX_YEAR, 1), 1).year)
    }
}

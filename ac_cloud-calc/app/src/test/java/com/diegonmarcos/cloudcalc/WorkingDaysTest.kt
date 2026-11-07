package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * #1329 Date & Time: working days, the declared holidays, the add-N-working-days walk, the range selection of the calendar and the
 * `date` / `toggle` / `choice` field declarations. Plain JVM (no Robolectric); runs from app/ over this repository's own build.json
 * and data/fleet/app/com.diegonmarcos.cloudcalc/datetime/holidays.json. Every expected count below was worked out by hand (see the comments).
 */
class WorkingDaysTest {
    private val book = WorkingDays.parse(File("../../data/fleet/app/com.diegonmarcos.cloudcalc/datetime/holidays.json").readText())
    private val satSun = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val friSat = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    private fun d(s: String) = LocalDate.parse(s)
    private fun es(weekend: Set<DayOfWeek> = satSun) = book.calendar("ES", weekend)
    private val none get() = book.calendar(WorkingDays.NONE, satSun)

    // ── working days between ────────────────────────────────────────────────────────────────────────────────────

    /**
     * 2026 starts on a Thursday: 52 weeks + 1 Thursday = 261 weekdays, 104 weekend days. The ES national holidays on a weekday are
     * Thu 1 Jan, Tue 6 Jan, Fri 3 Apr (Good Friday; Easter Sunday is 5 Apr), Fri 1 May, Mon 12 Oct, Tue 8 Dec, Fri 25 Dec = 7
     * (15 Aug, 1 Nov and 6 Dec fall on a weekend). 261 - 7 = 254.
     */
    @Test fun `Spain 2026 has 254 working days, 104 weekend days and 7 weekday holidays, the end included`() {
        val s = es().between(d("2026-01-01"), d("2026-12-31"), inclusive = true)
        assertEquals(365, s.days)
        assertEquals(254, s.working)
        assertEquals(104, s.weekend)
        assertEquals(7, s.holidays)
    }

    @Test fun `a range inside one weekend has no working day`() {
        // Sat 31 Oct and Sun 1 Nov 2026.
        assertEquals(0, es().between(d("2026-10-31"), d("2026-11-01"), inclusive = true).working)
        assertEquals(0, es().between(d("2026-10-31"), d("2026-11-02"), inclusive = false).working)
        assertEquals(2, es().between(d("2026-10-31"), d("2026-11-01"), inclusive = true).weekend)
    }

    @Test fun `the end date is excluded unless the toggle includes it`() {
        // Thu 1 Oct to Thu 8 Oct 2026, no holidays: 1..7 Oct = Thu Fri Sat Sun Mon Tue Wed.
        assertEquals(5, none.between(d("2026-10-01"), d("2026-10-08"), inclusive = false).working)
        // The end (a Thursday) counted too.
        assertEquals(6, none.between(d("2026-10-01"), d("2026-10-08"), inclusive = true).working)
        assertEquals(7, none.between(d("2026-10-01"), d("2026-10-08"), inclusive = false).days)
        assertEquals(8, none.between(d("2026-10-01"), d("2026-10-08"), inclusive = true).days)
        // From = To: nothing excluded-end, one day included-end.
        assertEquals(0, none.between(d("2026-10-01"), d("2026-10-01"), inclusive = false).days)
        assertEquals(1, none.between(d("2026-10-01"), d("2026-10-01"), inclusive = true).working)
    }

    @Test fun `working, weekend and holiday days partition the days counted`() {
        val s = es().between(d("2026-03-15"), d("2027-02-20"), inclusive = true)
        assertEquals(s.days, s.working + s.weekend + s.holidays)
    }

    @Test fun `a reversed pair counts the same days`() {
        assertEquals(es().between(d("2026-01-01"), d("2026-06-30"), true), es().between(d("2026-06-30"), d("2026-01-01"), true))
    }

    @Test fun `a custom weekend of Friday and Saturday moves the count`() {
        // Thu 1 Oct .. Sat 3 Oct 2026, end included: Sat+Sun weekend leaves Thu and Fri.
        assertEquals(2, book.calendar("none", satSun).between(d("2026-10-01"), d("2026-10-03"), true).working)
        // Fri+Sat weekend leaves only Thu, and two weekend days.
        val s = book.calendar("none", friSat).between(d("2026-10-01"), d("2026-10-03"), true)
        assertEquals(1, s.working)
        assertEquals(2, s.weekend)
        // And Sunday is then a working day.
        assertTrue(book.calendar("none", friSat).isWorking(d("2026-10-04")))
    }

    @Test fun `region None counts weekends only`() {
        val s = none.between(d("2026-01-01"), d("2026-12-31"), inclusive = true)
        assertEquals(261, s.working)
        assertEquals(0, s.holidays)
        assertEquals(0, book.holidays(WorkingDays.NONE, 2026).size)
        assertEquals(0, book.holidays("", 2026).size)
        assertEquals(0, book.holidays("nowhere", 2026).size)
    }

    @Test fun `a community adds its days to the national ones`() {
        // Easter Monday (6 Apr 2026) is a Catalan holiday, not a national one.
        assertTrue(book.calendar("ES-CT", satSun).isHoliday(d("2026-04-06")))
        assertFalse(es().isHoliday(d("2026-04-06")))
        assertTrue(book.calendar("ES-CT", satSun).isHoliday(d("2026-01-06")))
        assertEquals("Diada de Catalunya", book.calendar("ES-CT", satSun).holidayName(d("2026-09-11")))
    }

    // ── adding working days ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `adding working days skips a weekend`() {
        assertEquals(d("2026-10-05"), none.add(d("2026-10-02"), 1))   // Fri + 1 = Mon
        assertEquals(d("2026-10-09"), none.add(d("2026-10-02"), 5))   // Fri + 5 = next Fri
        assertEquals(d("2026-10-02"), none.add(d("2026-10-02"), 0))
    }

    @Test fun `adding working days skips a weekend and a holiday`() {
        // Thu 2 Apr 2026 + 1: Good Friday (3 Apr) is a holiday, 4-5 Apr a weekend, so Mon 6 Apr.
        assertEquals(d("2026-04-06"), es().add(d("2026-04-02"), 1))
        // Fri 2 Oct + 10: Oct 5-9 (5), 12 is the national day, 13-16 (9), 19 (10).
        assertEquals(d("2026-10-19"), es().add(d("2026-10-02"), 10))
        // Without the holiday the same walk ends a day sooner.
        assertEquals(d("2026-10-16"), none.add(d("2026-10-02"), 10))
    }

    @Test fun `adding a negative number walks back`() {
        assertEquals(d("2026-10-02"), es().add(d("2026-10-19"), -10))
        assertEquals(d("2026-10-02"), es().add(d("2026-10-05"), -1))
    }

    @Test fun `adding honours a custom weekend`() {
        // Thu 1 Oct 2026 + 1 with Fri+Sat off: Fri and Sat are skipped, Sun 4 Oct is the next working day.
        assertEquals(d("2026-10-04"), book.calendar("none", friSat).add(d("2026-10-01"), 1))
    }

    @Test fun `a walk with no working day, or too far, gives no date`() {
        assertNull(book.calendar("none", DayOfWeek.values().toSet()).add(d("2026-10-01"), 1))
        assertNull(none.add(d("2026-10-01"), WorkingDays.MAX_STEPS + 1))
    }

    // ── the declared holidays ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `Easter Sunday is computed for known years`() {
        assertEquals(d("2024-03-31"), WorkingDays.easter(2024))
        assertEquals(d("2025-04-20"), WorkingDays.easter(2025))
        assertEquals(d("2026-04-05"), WorkingDays.easter(2026))
        assertEquals(d("2028-04-16"), WorkingDays.easter(2028))
    }

    @Test fun `the nth and last weekday rules land on the right Mondays and Thursdays`() {
        val us = book.holidays("US", 2026)
        assertEquals("Martin Luther King Jr. Day", us[d("2026-01-19")])   // 3rd Monday of January
        assertEquals("Memorial Day", us[d("2026-05-25")])                  // last Monday of May
        assertEquals("Thanksgiving", us[d("2026-11-26")])                  // 4th Thursday of November
        assertEquals("Summer bank holiday", book.holidays("GB", 2026)[d("2026-08-31")])
    }

    @Test fun `every region extends a declared one and has holidays in every year from now through 2 years either side`() {
        assertTrue(book.regions.size >= 10)
        book.regions.forEach { r ->
            if (r.extends.isNotEmpty()) assertTrue("${r.id} extends ${r.extends}", book.has(r.extends))
            (2024..2028).forEach { y -> assertTrue("${r.id} $y", book.holidays(r.id, y).size >= 8) }
        }
    }

    @Test fun `the default region is the one naming the device country, else None`() {
        assertEquals("ES", book.forCountry("ES"))
        assertEquals("ES", book.forCountry("es"))
        assertEquals("DE", book.forCountry("DE"))
        assertEquals(WorkingDays.NONE, book.forCountry("ZZ"))
        assertEquals(WorkingDays.NONE, book.forCountry(""))
    }

    @Test fun `the weekend option values parse`() {
        assertEquals(satSun, WorkingDays.weekendOf("SAT,SUN"))
        assertEquals(friSat, WorkingDays.weekendOf("FRI,SAT"))
        assertEquals(emptySet<DayOfWeek>(), WorkingDays.weekendOf("NONE"))
    }

    // ── the range selection of the calendar ─────────────────────────────────────────────────────────────────────

    @Test fun `first tap is the start, second the end, a third starts a new range`() {
        val a = RangeSelection(null, null).tap(d("2026-03-10"))
        assertEquals(d("2026-03-10"), a.start); assertNull(a.end); assertTrue(a.pending)
        val b = a.tap(d("2026-03-20"))
        assertEquals(d("2026-03-10"), b.start); assertEquals(d("2026-03-20"), b.end); assertFalse(b.pending)
        val c = b.tap(d("2026-04-01"))
        assertEquals(d("2026-04-01"), c.start); assertNull(c.end)
    }

    @Test fun `tapping before the start swaps the two`() {
        val r = RangeSelection(d("2026-03-10"), null).tap(d("2026-03-02"))
        assertEquals(d("2026-03-02"), r.start); assertEquals(d("2026-03-10"), r.end)
    }

    @Test fun `tapping the start again makes a one-day range, and the range holds both ends`() {
        val r = RangeSelection(d("2026-03-10"), null).tap(d("2026-03-10"))
        assertEquals(d("2026-03-10"), r.end)
        val x = RangeSelection(d("2026-03-10"), d("2026-03-12"))
        assertTrue(x.contains(d("2026-03-10")) && x.contains(d("2026-03-11")) && x.contains(d("2026-03-12")))
        assertFalse(x.contains(d("2026-03-13")))
        assertFalse(RangeSelection(null, null).contains(d("2026-03-13")))
    }

    // ── the form declarations and their outputs ─────────────────────────────────────────────────────────────────

    private val modes = Declarations.parseModes(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("modes").toString())
    private val dt = modes.first { it.id == "datetime" }
    private fun form(id: String) = dt.forms.first { it.id == id }

    @Test fun `the date field type parses, with the range pair and the calendar open`() {
        val between = form("between")
        val from = between.fields.first { it.id == "from" }
        assertEquals("date", from.type)
        assertEquals("to", from.rangeEnd)
        assertTrue(from.calendarOpen)
        assertEquals("date", between.fields.first { it.id == "to" }.type)
        assertEquals("date", form("add").fields.first { it.id == "date" }.type)
        assertEquals("date", form("add_working").fields.first { it.id == "date" }.type)
        assertEquals("", form("add").fields.first { it.id == "date" }.rangeEnd)
        assertEquals("text", form("add").fields.first { it.id == "days" }.type)
    }

    @Test fun `the toggle, the weekend and the region are declared fields with defaults`() {
        val f = form("between")
        assertEquals("toggle", f.fields.first { it.id == "inclusive" }.type)
        assertEquals("false", f.fields.first { it.id == "inclusive" }.default)
        val weekend = f.fields.first { it.id == "weekend" }
        assertEquals("SAT,SUN", weekend.default)
        assertTrue(weekend.options.any { it.value == "FRI,SAT" })
        val region = f.fields.first { it.id == "region" }
        assertEquals("holiday_regions", region.optionsFrom)
        assertEquals("locale_country", region.defaultFrom)
        assertEquals(setOf("from", "to", "end", "weekend", "region"), f.bind.keys)
        f.bind.values.forEach { id -> assertTrue(id, f.fields.any { it.id == id }) }
        form("add_working").bind.values.forEach { id -> assertTrue(id, form("add_working").fields.any { it.id == id }) }
        assertEquals("datetime/holidays.json", JSONObject(dt.spec).getString("holidays"))
    }

    @Test fun `Days between keeps the engine's Days and Weeks and adds the counted outputs`() {
        val outs = form("between").outputs
        assertEquals(listOf("Days", "Weeks"), outs.take(2).map { it.label })
        assertTrue(outs.take(2).all { it.expr.startsWith("days(") && it.calc.isEmpty() })
        assertEquals(listOf("working", "weekend", "holidays"), outs.drop(2).map { it.metric })
        assertTrue(outs.drop(2).all { it.calc == "span" && it.labelOn.contains("end included") && it.labelOff.contains("end excluded") })
        assertEquals("add_working", form("add_working").outputs.single().calc)
    }

    private fun values(vararg kv: Pair<String, String>) = form("between").fields.associate { it.id to it.default } + kv
    private fun out(label: String, v: Map<String, String>): Logic.Result {
        val f = form("between")
        return DateForms.result(book, f.outputs.first { it.label == label }, f.bind, v)
    }

    @Test fun `the form computes working days, weekend days and holidays and names the convention`() {
        val v = values("from" to "2026-01-01", "to" to "2026-12-31", "inclusive" to "true", "region" to "ES")
        assertEquals("254", out("Working days", v).text)
        assertEquals("104", out("Weekend days", v).text)
        assertEquals("7", out("Holidays", v).text)
        val f = form("between")
        assertEquals("Working days (end included)", DateForms.label(f.outputs.first { it.label == "Working days" }, v, f.bind))
        val ex = v + ("inclusive" to "false")
        assertEquals("253", out("Working days", ex).text)
        assertEquals("Working days (end excluded)", DateForms.label(f.outputs.first { it.label == "Working days" }, ex, f.bind))
    }

    @Test fun `a custom weekend and region None reach the form`() {
        val v = values("from" to "2026-10-01", "to" to "2026-10-03", "inclusive" to "true", "weekend" to "FRI,SAT", "region" to "none")
        assertEquals("1", out("Working days", v).text)
        assertEquals("2", out("Weekend days", v).text)
        assertEquals("0", out("Holidays", v).text)
        // Mon 12 Oct 2026 is Spain's national day: a holiday in ES, an ordinary Monday under None.
        val day = values("from" to "2026-10-12", "to" to "2026-10-12", "inclusive" to "true")
        assertEquals("1", out("Working days", day + ("region" to "none")).text)
        assertEquals("0", out("Working days", day + ("region" to "ES")).text)
        assertEquals("1", out("Holidays", day + ("region" to "ES")).text)
    }

    @Test fun `a bad date is an error, not a number`() {
        assertFalse(out("Working days", values("from" to "soon")).ok)
        val f = form("add_working")
        val v = f.fields.associate { it.id to it.default } + ("region" to "ES") + ("date" to "2026-04-02") + ("days" to "1")
        val r = DateForms.result(book, f.outputs.single(), f.bind, v)
        assertTrue(r.ok)
        assertTrue(r.text, r.text.startsWith("2026-04-06 ("))
        assertFalse(DateForms.result(book, f.outputs.single(), f.bind, v + ("days" to "x")).ok)
    }
}

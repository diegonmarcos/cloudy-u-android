package com.diegonmarcos.cloudcalc.tools

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * #1409 Time & Date ▸ Calendars: every calendar of [Calendars] as ONE CYCLE (its year, or its count of days) cut into its
 * own units (months, trecenas, veintenas, moons, epagomenal days). A calendar is declared by a [Spec.key]: the cycle a
 * Gregorian day belongs to and the unit it falls in, read from the [Calendars] maths; the cycle around a day is the run of
 * days sharing that cycle number and its units are the runs of days sharing a label. So the strips never re-derive a
 * calendar: Hebrew's leap Adar, Islamic 29/30, 13-Moon's Day Out of Time all come from the same functions the Date
 * Converter prints.
 */
object Cycles {
    /** One unit of a cycle: its [label] and length in [days]. */
    data class Segment(val label: String, val days: Int)

    /** One cycle instance: its first Gregorian day [start], its [title] (the cycle's year) and its [segments] in order. */
    data class Cycle(val id: String, val name: String, val title: String, val start: LocalDate, val segments: List<Segment>) {
        val length: Int get() = segments.sumOf { it.days }
        /** The first day offset of each segment. */
        fun starts(): List<Int> = segments.runningFold(0) { a, s -> a + s.days }.dropLast(1)
    }

    /** Where a day falls in a cycle: [offset] days from its start, [segment] index, [day] 1-based inside the segment. */
    data class Pos(val offset: Int, val segment: Int, val day: Int)

    /** The cycle number of a day (equal for the days of one cycle) and the label of the unit it falls in. */
    data class Key(val cycle: Long, val title: String, val label: String)

    data class Spec(val id: String, val name: String, val key: (LocalDate) -> Key)


    private fun gregorianMonth(m: Int) = Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun ymd(y: Calendars.Ymd, names: List<String>, era: String) = Key(y.year, "${y.year} $era", names[y.month - 1])
    private fun trecena(index: Long, names: List<String>): String = "1 " + names[((index / 13 * 13) % 20).toInt()]
    private fun maya(date: LocalDate) = Calendars.jdn(date) - Calendars.MAYA_JDN
    private fun aztec(date: LocalDate) = Calendars.jdn(date) - Calendars.AZTEC_JDN

    /** The 13-Moon unit of a day; 29 February (0.0 Hunab Ku) stays inside the moon it falls in. */
    private fun moon(date: LocalDate): String {
        val (m, _) = Calendars.thirteenMoon(if (date.monthValue == 2 && date.dayOfMonth == 29) date.minusDays(1) else date)
        return if (m == 0) "Day Out of Time" else Calendars.MOONS[m - 1]
    }

    val SPECS: List<Spec> = listOf(
        Spec("gregorian", "Gregorian") { Key(it.year.toLong(), "${it.year}", gregorianMonth(it.monthValue)) },
        Spec("julian", "Julian") { ymd(Calendars.julian(it), (1..12).map(::gregorianMonth), "OS") },
        Spec("hebrew", "Hebrew") { d ->
            val h = Calendars.hebrew(d)
            Key(h.year, "${h.year} AM", Calendars.hebrewMonthName(h.month, h.year))
        },
        Spec("islamic", "Islamic (civil)") { ymd(Calendars.islamicCivil(it), Calendars.ISLAMIC_MONTHS, "AH") },
        Spec("persian", "Persian") { ymd(Calendars.persian(it), Calendars.PERSIAN_MONTHS, "AP") },
        Spec("coptic", "Coptic") { ymd(Calendars.coptic(it), Calendars.COPTIC_MONTHS, "AM") },
        Spec("ethiopian", "Ethiopian") { ymd(Calendars.ethiopian(it), Calendars.ETHIOPIAN_MONTHS, "EE") },
        Spec("saka", "Indian (Saka)") { ymd(Calendars.saka(it), Calendars.SAKA_MONTHS, "Saka") },
        Spec("french", "French Republican") { d ->
            val f = Calendars.french(d)
            Key(f.year, "An ${Calendars.romanNumeral(f.year)}", Calendars.FRENCH_MONTHS[f.month - 1])
        },
        Spec("thirteen_moon", "13-Moon") { d ->
            val y = if (d >= LocalDate.of(d.year, 7, 26)) d.year else d.year - 1
            Key(y.toLong(), "$y–${y + 1}", moon(d))
        },
        Spec("fixed", "International Fixed") { d ->
            val (m, x) = Calendars.fixed(d)
            Key(d.year.toLong(), "${d.year}", if (m > 0) Calendars.IFC_MONTHS[m - 1] else if (x == 1) "Leap Day" else "Year Day")
        },
        Spec("discordian", "Discordian") { d ->
            val p = if (d.monthValue == 2 && d.dayOfMonth == 29) d.minusDays(1) else d
            var doy = p.dayOfYear
            if (p.isLeapYear && doy > 60) doy--
            Key(d.year + 1166L, "${d.year + 1166} YOLD", Calendars.SEASONS[(doy - 1) / 73])
        },
        Spec("tzolkin", "Maya Tzolk'in") { d ->
            val k = Math.floorMod(maya(d) + 159, 260L)
            Key(Math.floorDiv(maya(d) + 159, 260L), "260-day count", trecena(k, Calendars.TZOLKIN))
        },
        Spec("haab", "Maya Haab'") { d ->
            val h = Math.floorMod(maya(d) + 348, 365L)
            Key(Math.floorDiv(maya(d) + 348, 365L), "365-day count", Calendars.HAAB[(h / 20).toInt()])
        },
        Spec("tonalpohualli", "Aztec Tōnalpōhualli") { d ->
            val k = Math.floorMod(aztec(d) + 104, 260L)
            Key(Math.floorDiv(aztec(d) + 104, 260L), "260-day count", trecena(k, Calendars.TONALLI))
        },
        Spec("xiuhpohualli", "Aztec Xiuhpōhualli") { d ->
            val c = Math.floorMod(aztec(d) + 201, 365L)
            Key(Math.floorDiv(aztec(d) + 201, 365L), "365-day count", Calendars.XIUHPOHUALLI[(c / 20).toInt()])
        },
    )

    fun spec(id: String): Spec? = SPECS.firstOrNull { it.id == id }

    /** The cycle of [spec] that holds [date]: walked back to its first day and forward to its last, cut into units. */
    fun cycleAt(spec: Spec, date: LocalDate): Cycle {
        val k = spec.key(date)
        var start = date
        while (spec.key(start.minusDays(1)).cycle == k.cycle) start = start.minusDays(1)
        val segs = ArrayList<Segment>()
        var d = start
        var x = spec.key(d)
        while (x.cycle == k.cycle) {
            if (segs.isNotEmpty() && segs.last().label == x.label) segs[segs.size - 1] = Segment(x.label, segs.last().days + 1)
            else segs.add(Segment(x.label, 1))
            d = d.plusDays(1)
            x = spec.key(d)
        }
        return Cycle(spec.id, spec.name, k.title, start, segs)
    }

    /** Where [date] falls in [cycle]; null when the cycle does not hold it. */
    fun position(cycle: Cycle, date: LocalDate): Pos? {
        val off = (date.toEpochDay() - cycle.start.toEpochDay()).toInt()
        if (off < 0 || off >= cycle.length) return null
        val starts = cycle.starts()
        val seg = starts.indexOfLast { it <= off }
        return Pos(off, seg, off - starts[seg] + 1)
    }

    /**
     * THE MAPPING the strips highlight by: a Gregorian day picked on the Gregorian strip is, in every other calendar, the
     * cycle that holds it and its position there. Same day, so the same epoch day in every strip.
     */
    fun mapDay(date: LocalDate): List<Pair<Cycle, Pos>> = SPECS.map { s ->
        val c = cycleAt(s, date)
        c to position(c, date)!!
    }

    /** "Nisan 1 · 5784 AM": what a strip prints for its highlighted day. */
    fun label(cycle: Cycle, pos: Pos): String = "${cycle.segments[pos.segment].label} ${pos.day} · ${cycle.title}"
}

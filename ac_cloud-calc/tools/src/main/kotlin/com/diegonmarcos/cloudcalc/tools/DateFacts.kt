package com.diegonmarcos.cloudcalc.tools

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** #1377 Calc ▸ Tools ▸ Time & Date ▸ Date: the facts of one day. */
object DateFacts {
    fun full(d: LocalDate): String =
        d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + d.dayOfMonth + " " +
            d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.year
    fun dayOfYear(d: LocalDate): String = "${d.dayOfYear} of ${d.lengthOfYear()}"
    fun isoWeek(d: LocalDate): String = "W%02d of %d".format(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), d.get(IsoFields.WEEK_BASED_YEAR))
    fun quarter(d: LocalDate): String = "Q${(d.monthValue - 1) / 3 + 1}"
    fun daysLeft(d: LocalDate): Int = d.lengthOfYear() - d.dayOfYear
    fun nextLeap(year: Int): Int = generateSequence(year + 1) { it + 1 }.first { java.time.Year.isLeap(it.toLong()) }
    fun leap(d: LocalDate): String = if (d.isLeapYear) "Yes" else "No (next ${nextLeap(d.year)})"
}

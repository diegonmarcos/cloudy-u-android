package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsDb
import java.time.LocalDate

/**
 * #1851 ONE answer to "how many steps on this date": Health Connect's day total as the store holds it (`steps_daily`), else the day's
 * rollup (the step-counter slices attributed to the day's stays and moves). Explored > Day and Tracker > Steps both call [forDay], so a
 * date can never show two numbers.
 */
object StepsFromStore {
    fun forDay(date: String, stored: Map<String, Long>, rolled: Map<String, Long>): Long? = stored[date] ?: rolled[date]

    fun read(ctx: Context, from: LocalDate, to: LocalDate): Map<String, Long> {
        val db = MapsDb.get(ctx)
        val stored = db.dailySteps(from.toString(), to.toString())
        val rolled = db.rollups(from.toString(), to.toString()).filter { it.steps > 0 }.associate { it.date.toString() to it.steps }
        val out = LinkedHashMap<String, Long>()
        var d = from
        while (!d.isAfter(to)) { forDay(d.toString(), stored, rolled)?.let { out[d.toString()] = it }; d = d.plusDays(1) }
        return out
    }
}

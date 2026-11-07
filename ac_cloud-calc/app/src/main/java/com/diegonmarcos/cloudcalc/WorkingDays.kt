package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * #1329 Date & Time: working days. libqalculate has no working-day function, so the calendar bookkeeping lives here, pure
 * (java.time and org.json only, no Android) so the JVM suite runs it. What is a weekend and which days are holidays are DATA:
 * the weekend comes from the field's declared options (build.json::ui.modes[datetime]), the holidays from the declared rules in
 * data/fleet/app/com.diegonmarcos.cloudcalc/datetime/holidays.json (the runtime dataset datetime/holidays, #1547; no library: see that file's _doc). Calendar days between two dates stay the engine's `days()`.
 *
 * CONVENTION: a span counts the days from `from` up to `to`, the END EXCLUDED (the engine's `days()`), or the end included when the
 * form's toggle says so. Working + weekend + holidays = the days counted; a holiday that falls on a weekend day is a weekend day.
 */
object WorkingDays {
    /** One holiday rule: fixed month/day, an offset from Western Easter Sunday, or the nth weekday of a month (nth -1 = the last). */
    sealed interface Rule {
        val name: String
        fun on(year: Int): LocalDate?
    }
    data class Fixed(override val name: String, val month: Int, val day: Int) : Rule {
        override fun on(year: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    data class FromEaster(override val name: String, val offset: Int) : Rule {
        override fun on(year: Int): LocalDate = easter(year).plusDays(offset.toLong())
    }
    data class NthWeekday(override val name: String, val month: Int, val weekday: DayOfWeek, val nth: Int) : Rule {
        override fun on(year: Int): LocalDate? {
            if (nth > 0) {
                val first = LocalDate.of(year, month, 1)
                val shift = (weekday.value - first.dayOfWeek.value + 7) % 7
                return first.plusDays((shift + 7L * (nth - 1))).takeIf { it.monthValue == month }
            }
            val last = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1)
            return last.minusDays(((last.dayOfWeek.value - weekday.value + 7) % 7).toLong())
        }
    }

    data class Region(val id: String, val label: String, val country: String, val extends: String, val rules: List<Rule>)

    /** Western (Gregorian) Easter Sunday, the anonymous Gregorian algorithm. */
    fun easter(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** The declared holiday regions: [parse] reads the asset, [holidays] expands one region (with what it extends) for a year. */
    class Book(val regions: List<Region>) {
        private val byId = regions.associateBy { it.id }
        fun has(id: String): Boolean = id in byId

        /** The holidays of [region] in [year], date to name; an unknown or empty region has none. */
        fun holidays(region: String, year: Int): Map<LocalDate, String> {
            val out = LinkedHashMap<LocalDate, String>()
            var id: String? = region
            val seen = HashSet<String>()
            val chain = ArrayList<Region>()
            while (!id.isNullOrEmpty() && seen.add(id)) { val r = byId[id] ?: break; chain.add(r); id = r.extends.ifEmpty { null } }
            chain.reversed().forEach { r -> r.rules.forEach { rule -> rule.on(year)?.let { d -> out.putIfAbsent(d, rule.name) } } }
            return out
        }

        /** The region a device country gets by default: the declared region naming that country, else [NONE]. */
        fun forCountry(country: String): String = if (country.isBlank()) NONE else regions.firstOrNull { it.country.equals(country, ignoreCase = true) }?.id ?: NONE

        /** [Calendar] over [region] and [weekend]; holidays are expanded one year at a time and kept. */
        fun calendar(region: String, weekend: Set<DayOfWeek>): Calendar = Calendar(weekend) { year -> holidays(region, year) }
    }

    const val NONE = "none"

    fun parse(json: String): Book = Book(JSONObject(json).getJSONArray("regions").objects().map { r ->
        Region(r.getString("id"), r.getString("label"), r.optString("country"), r.optString("extends"),
            r.getJSONArray("rules").objects().map { rule(it) })
    })

    private fun rule(o: JSONObject): Rule = when {
        o.has("easter") -> FromEaster(o.getString("name"), o.getInt("easter"))
        o.has("nth") -> NthWeekday(o.getString("name"), o.getInt("month"), DayOfWeek.of(o.getInt("weekday")), o.getInt("nth"))
        else -> Fixed(o.getString("name"), o.getInt("month"), o.getInt("day"))
    }

    /** "SAT,SUN" (the declared option value) to the set of days; unknown names are dropped. */
    fun weekendOf(value: String): Set<DayOfWeek> =
        value.split(',').map { it.trim().uppercase() }.filter { it.length == 3 }
            .mapNotNull { t -> DayOfWeek.values().firstOrNull { it.name.startsWith(t) } }.toSet()

    /** What a span counted. */
    data class Span(val days: Int, val working: Int, val weekend: Int, val holidays: Int)

    /** A weekend plus a holiday lookup (year to date-name map). */
    class Calendar(val weekend: Set<DayOfWeek>, private val yearHolidays: (Int) -> Map<LocalDate, String>) {
        private val cache = HashMap<Int, Map<LocalDate, String>>()
        fun holidayName(d: LocalDate): String? = cache.getOrPut(d.year) { yearHolidays(d.year) }[d]
        fun isWeekend(d: LocalDate): Boolean = d.dayOfWeek in weekend
        fun isHoliday(d: LocalDate): Boolean = holidayName(d) != null
        fun isWorking(d: LocalDate): Boolean = !isWeekend(d) && !isHoliday(d)

        /** The days from [from] to [to] (end excluded unless [inclusive]); a reversed pair counts the same days. */
        fun between(from: LocalDate, to: LocalDate, inclusive: Boolean): Span {
            val lo = minOf(from, to); val hi = maxOf(from, to)
            val end = if (inclusive) hi.plusDays(1) else hi
            var working = 0; var weekend = 0; var holidays = 0; var days = 0
            var d = lo
            while (d.isBefore(end)) {
                days++
                when { isWeekend(d) -> weekend++; isHoliday(d) -> holidays++; else -> working++ }
                d = d.plusDays(1)
            }
            return Span(days, working, weekend, holidays)
        }

        /**
         * [from] moved by [n] working days (negative = back): each step goes to the next working day, so the start itself is
         * never counted; n = 0 returns [from] unchanged. Null when |n| is beyond [MAX_STEPS] or when no day is ever a working day.
         */
        fun add(from: LocalDate, n: Int): LocalDate? {
            if (n == 0) return from
            if (kotlin.math.abs(n) > MAX_STEPS || weekend.size >= 7) return null
            val step = if (n > 0) 1L else -1L
            var left = kotlin.math.abs(n)
            var d = from
            var guard = 0
            while (left > 0) {
                d = d.plusDays(step)
                if (isWorking(d)) left--
                if (++guard > MAX_STEPS * 4) return null
            }
            return d
        }
    }

    const val MAX_STEPS = 100_000

    /** An ISO date (yyyy-MM-dd) as typed or tapped, or null. */
    fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

/**
 * The tap state of a range calendar. A first tap starts a range (the end is open), the second sets the end, a tap before the start
 * swaps the two, and a third tap starts a new range. Pure: the screen keeps the state, this decides it.
 */
data class RangeSelection(val start: LocalDate?, val end: LocalDate?) {
    /** Start chosen, end awaited. */
    val pending: Boolean get() = start != null && end == null

    fun tap(day: LocalDate): RangeSelection = when {
        start == null || end != null -> RangeSelection(day, null)
        day.isBefore(start) -> RangeSelection(day, start)
        else -> RangeSelection(start, day)
    }

    /** Whether [d] is inside the highlighted range (both ends included). */
    fun contains(d: LocalDate): Boolean = start != null && !d.isBefore(start) && !d.isAfter(end ?: start)
}

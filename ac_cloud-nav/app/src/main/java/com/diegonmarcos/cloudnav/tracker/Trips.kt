package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One night's sleep place on a trip: the city and, when known, where. */
data class TripStop(val date: LocalDate, val city: String, val lat: Double?, val lon: Double?)

/**
 * A trip: consecutive nights away from [home]. [start] is the departure day (the evening of the first night away),
 * [end] the return day, the date the last night away ended on (or the last day known when [ongoing]). [vehicleM] sums the vehicle modes of those days.
 */
data class Trip(val start: LocalDate, val end: LocalDate, val nights: Int, val cities: List<String>, val vehicleM: Map<Mode, Double>,
                val stops: List<TripStop>, val ongoing: Boolean) {
    val distanceM: Double get() = vehicleM.values.sum()
}

data class TripsResult(val home: String?, val owner: Boolean, val trips: List<Trip>)

/**
 * Trips from the per-day aggregates (the same [DayStats] the Cities / Places / World tabs and tracker/daily read). Pure.
 * The sleep that ENDS on a date is that date's night (see [Aggregates.perDay]), so a night away is a date whose [DayStats.sleepCity]
 * differs from home. A date with no recorded sleep keeps the previous known city (the phone was off or the sleep unplaced);
 * before the first known city it is skipped. A missing calendar date ends a trip.
 */
object Trips {
    const val HOME_WINDOW_DAYS = 90

    private fun norm(c: String?) = c?.trim()?.takeIf { it.isNotEmpty() }
    private fun same(a: String, b: String) = a.equals(b, ignoreCase = true)

    /**
     * Most frequent sleep city of the last [HOME_WINDOW_DAYS] dates up to the newest date that has one; a tie goes to the more recent.
     * #1887 the window ends at the last recorded sleep, not at the last date given: an imported history that ends months before today
     * still has its home (and so its trips).
     */
    fun detectHome(days: List<DayStats>): String? {
        val sorted = days.sortedBy { it.date }
        val last = sorted.lastOrNull { norm(it.sleepCity) != null }?.date ?: return null
        val inWin = sorted.filter { it.date.isAfter(last.minusDays(HOME_WINDOW_DAYS.toLong())) }
        val counts = LinkedHashMap<String, Int>(); val lastSeen = HashMap<String, LocalDate>()
        for (d in inWin) { val c = norm(d.sleepCity) ?: continue
            val key = counts.keys.firstOrNull { same(it, c) } ?: c
            counts[key] = (counts[key] ?: 0) + 1; lastSeen[key] = d.date }
        return counts.entries.maxWithOrNull(compareBy({ it.value }, { lastSeen[it.key] }))?.key
    }

    /** The trip's map: the located sleep stops joined in order (line coloured by the main vehicle mode), one bed marker per city. */
    fun route(t: Trip): RouteModel {
        val pts = t.stops.filter { it.lat != null && it.lon != null }
        val line = pts.map { LatLon(it.lat!!, it.lon!!) }.let { l -> l.filterIndexed { i, p -> i == 0 || p != l[i - 1] } }
        val mode = t.vehicleM.maxByOrNull { it.value }?.key ?: Mode.UNKNOWN
        return RouteModel(if (line.size >= 2) listOf(RouteLine(mode, line)) else emptyList(),
            pts.distinctBy { it.city.lowercase() }.map { RouteMarker(LatLon(it.lat!!, it.lon!!), "stay_sleep", it.city) }, null)
    }

    /** [homeOverride] (the owner's choice) wins over the detected home. */
    fun group(days: List<DayStats>, homeOverride: String? = null): TripsResult {
        val sorted = days.sortedBy { it.date }
        val override = norm(homeOverride)
        val home = override ?: detectHome(sorted) ?: return TripsResult(null, false, emptyList())
        val trips = ArrayList<Trip>()
        var run = ArrayList<Pair<DayStats, String>>()
        var carry: String? = null; var prev: LocalDate? = null
        fun flush(ongoing: Boolean) {
            if (run.isEmpty()) return
            val first = run.first().first.date; val lastD = run.last().first.date
            val cities = ArrayList<String>(); val stops = ArrayList<TripStop>(); val veh = LinkedHashMap<Mode, Double>()
            for ((d, c) in run) {
                if (cities.none { same(it, c) }) cities += c
                stops += TripStop(d.date, c, d.sleepLat, d.sleepLon)
            }
            // the departure day (evening of the first night) through the return day
            for (d in sorted) if (!d.date.isBefore(first.minusDays(1)) && !d.date.isAfter(lastD))
                d.vehicleM.forEach { (m, v) -> veh[m] = (veh[m] ?: 0.0) + v }
            trips += Trip(first.minusDays(1), lastD, run.size, cities, veh, stops, ongoing)
            run = ArrayList()
        }
        for (d in sorted) {
            if (prev != null && d.date != prev!!.plusDays(1)) { flush(false); carry = null }
            prev = d.date
            val c = norm(d.sleepCity) ?: carry ?: continue
            carry = c
            if (same(c, home)) flush(false) else run.add(d to c)
        }
        flush(ongoing = true)
        return TripsResult(home, override != null, trips)
    }
}

/** The owner's home-city override, kept in the app's own preferences. Blank = detect. */
class HomeStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tracker_home", Context.MODE_PRIVATE)
    fun get(): String? = sp.getString("city", null)?.trim()?.takeIf { it.isNotEmpty() }
    fun set(city: String?) { sp.edit().putString("city", city?.trim()?.takeIf { it.isNotEmpty() }).apply() }
}

/** Wire shape of `tracker/trips` (contract version [VERSION]): fields are only added within a version. */
object TripsJson {
    const val VERSION = 1
    fun toJson(r: TripsResult): JSONObject {
        val arr = JSONArray()
        for (t in r.trips.asReversed()) {
            val veh = JSONObject(); t.vehicleM.forEach { (m, v) -> veh.put(m.name.lowercase(), Math.round(v)) }
            val route = JSONArray()
            for (s in t.stops) route.put(JSONObject().put("date", s.date.toString()).put("city", s.city).apply { if (s.lat != null && s.lon != null) put("lat", s.lat).put("lon", s.lon) })
            arr.put(JSONObject().put("start", t.start.toString()).put("end", t.end.toString()).put("nights", t.nights)
                .put("cities", JSONArray(t.cities)).put("distance_m", Math.round(t.distanceM)).put("vehicle_m", veh)
                .put("route", route).put("ongoing", t.ongoing))
        }
        return JSONObject().put("version", VERSION).put("home", r.home ?: "").put("home_source", if (r.owner) "owner" else "auto").put("trips", arr)
    }
}

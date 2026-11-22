package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.TimelineSync
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * #1851 the aggregates behind Explored > Trips, Places, Cities, World and Insights, persisted in the one store (maps.db `aggregates`)
 * every time the segments are rebuilt (the engine's pass) or a page opens. Kinds: `trips`, `places`, `cities`, `world`, `insights`
 * (key `last30` / `last90`). The JSON of `trips` and `insights` is the fleet door's own contract (`tracker/trips`, `tracker/daily`).
 */
object AggregatesStore {
    const val KEY_MONTH = "last30"
    const val KEY_QUARTER = "last90"

    fun placesJson(l: List<PlaceTotal>): JSONArray = JSONArray().also { a ->
        for (p in l) a.put(JSONObject().put("label", p.label).put("city", p.city ?: "").put("country", p.country ?: "").put("visits", p.visits).put("total_ms", p.totalMs))
    }

    fun citiesJson(l: List<CityTotal>): JSONArray = JSONArray().also { a ->
        for (c in l) a.put(JSONObject().put("city", c.city).put("country", c.country ?: "").put("days", c.days).put("total_ms", c.totalMs))
    }

    fun worldJson(l: List<CountryTotal>): JSONArray = JSONArray().also { a ->
        for (c in l) a.put(JSONObject().put("country", c.country).put("days", c.days).put("total_ms", c.totalMs))
    }

    /** What the persisted rows hold for one rebuild; [kind] to JSON text. */
    fun rows(stats: List<DayStats>, trips: TripsResult, places: List<PlaceTotal>, cities: List<CityTotal>, countries: List<CountryTotal>): Map<String, String> = mapOf(
        "insights" to DayJson.daily(stats).toString(), "trips" to TripsJson.toJson(trips).toString(),
        "places" to placesJson(places).toString(), "cities" to citiesJson(cities).toString(), "world" to worldJson(countries).toString())

    fun persist(app: Context, nowMs: Long, stats: List<DayStats>, trips: TripsResult, places: List<PlaceTotal>, cities: List<CityTotal>, countries: List<CountryTotal>) {
        val db = MapsDb.get(app)
        for ((kind, json) in rows(stats, trips, places, cities, countries)) db.putAggregate(kind, if (kind == "trips" || kind == "insights") KEY_QUARTER else KEY_MONTH, json, nowMs)
    }

    /** The engine's pass: rebuild every aggregate from the segments the store holds now. */
    fun rebuild(app: Context, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = today.minusDays(Trips.HOME_WINDOW_DAYS - 1L)
        val cs = CorrectionStore(app); val corrections = cs.all(); val memory = cs.memory()
        val store = TimelineSync.view(app, DayTimeline.dayStart(first, zone) - 12 * 3_600_000L, nowMs)
        val dates = (0 until Trips.HOME_WINDOW_DAYS).map { first.plusDays(it.toLong()) }
        val stats = Aggregates.perDay(store, dates, nowMs, zone, corrections, memory)
        val month = dates.takeLast(30).map { d -> DayTimeline.build(store, DayTimeline.dayStart(d, zone), minOf(DayTimeline.dayStart(d.plusDays(1), zone), nowMs), corrections, memory) }
        persist(app, nowMs, stats, Trips.group(stats, HomeStore(app).get()), Rankings.places(month), Rankings.cities(month), Rankings.countries(month))
    }
}

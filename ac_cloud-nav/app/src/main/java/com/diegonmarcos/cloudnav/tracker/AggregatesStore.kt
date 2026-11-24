package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsDb
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

    /** The key a kind's row is stored under (the fleet door reads `trips` / `insights` as [KEY_QUARTER], the rankings as [KEY_MONTH]). */
    fun keyOf(kind: String): String = if (kind == "trips" || kind == "insights") KEY_QUARTER else KEY_MONTH

    fun persist(app: Context, nowMs: Long, stats: List<DayStats>, trips: TripsResult, places: List<PlaceTotal>, cities: List<CityTotal>, countries: List<CountryTotal>) {
        val db = MapsDb.get(app)
        for ((kind, json) in rows(stats, trips, places, cities, countries)) db.putAggregate(kind, keyOf(kind), json, nowMs)
    }

    /** #1853 the aggregates' window: the last [Trips.HOME_WINDOW_DAYS] days, or back to the first imported day once a Google history was imported (at most 25 years). */
    fun windowStart(app: Context, today: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): java.time.LocalDate {
        val base = today.minusDays(Trips.HOME_WINDOW_DAYS - 1L)
        val first = runCatching { MapsDb.get(app).firstImportedMs() }.getOrNull() ?: return base
        val d = java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        return minOf(base, maxOf(d, today.minusYears(25)))
    }

    /** The dates the aggregates cover and the ones Places / Cities / World rank over (the last 30 days; all of an imported history). */
    fun windowDates(first: java.time.LocalDate, today: java.time.LocalDate): Pair<List<java.time.LocalDate>, List<java.time.LocalDate>> {
        val n = java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt() + 1
        val dates = (0 until n).map { first.plusDays(it.toLong()) }
        return dates to (if (n > Trips.HOME_WINDOW_DAYS) dates else dates.takeLast(30))
    }

    /** #1863 what Explored shows, read from the cache: the digests of every date, folded into Trips, Insights, Places, Cities, World and the tables. */
    class Explored(val cache: CacheResult, val rank: List<DayDigest>, val trips: TripsResult, val places: List<PlaceTotal>, val cities: List<CityTotal>,
                   val countries: List<CountryTotal>, val world: List<WorldRow>, val cityTable: List<CityRow>) {
        val stats: List<DayStats> get() = cache.stats
    }

    /** #1887 every fold of the aggregates in this process (the JVM test proves a Day date change adds none). */
    @Volatile var exploreCount = 0L

    /**
     * #1863 the cached pass the page, the engine tick and the import share: past dates come from their stored digests (recomputed only
     * when their checksum moved or they were marked dirty), today is live, and every aggregate is folded from the digests.
     * #1887 [cancelled] is asked between dates.
     */

    fun explore(backend: DigestBackend, store: List<com.diegonmarcos.cloudnav.maps.TlSegment>, first: java.time.LocalDate, today: java.time.LocalDate, nowMs: Long,
                zone: ZoneId, corrections: List<Correction>, memory: List<Remembered>, home: String?, cancelled: () -> Boolean = { false }): Explored {
        exploreCount++
        val (dates, rankDates) = windowDates(first, today)
        val r = ExploredCache.refresh(backend, store, dates, nowMs, zone, corrections, memory, cancelled = cancelled)
        return fold(r, rankDates, home)
    }

    /** #1887 every aggregate folded from digests already at hand (the refresh's, or the stored ones a cold process paints first). */
    fun fold(r: CacheResult, rankDates: List<java.time.LocalDate>, home: String?): Explored {
        val keep = rankDates.toHashSet()
        val rank = r.digests.filter { it.date in keep }
        return Explored(r, rank, Trips.group(r.stats, home), ExploredFold.places(rank), ExploredFold.cities(rank), ExploredFold.countries(rank),
            ExploredFold.world(rank), ExploredFold.cityTable(rank))
    }

    fun explore(app: Context, store: List<com.diegonmarcos.cloudnav.maps.TlSegment>, first: java.time.LocalDate, today: java.time.LocalDate, nowMs: Long, zone: ZoneId): Explored {
        val cs = CorrectionStore(app)
        val e = explore(MapsDbDigestBackend(app), store, first, today, nowMs, zone, cs.all(), cs.memory(), HomeStore(app).get())
        runCatching { persist(app, nowMs, e.stats, e.trips, e.places, e.cities, e.countries) }   // #1851 the aggregates live in the one store
        return e
    }

    /**
     * The engine's pass (and the import's): bring the cache up to date with the segments the store holds now; only changed dates are
     * computed. #1887 it runs the page's own single-flight refresh ([ExploredRepository]): one full pass at a time, and its result stays
     * in memory, so the next open of Explored paints at once.
     */
    fun rebuild(app: Context, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        ExploredRepository.get(app).rebuild(zone, nowMs)
    }
}

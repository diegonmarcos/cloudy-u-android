package com.diegonmarcos.superapp.daytimelines

import android.content.Context
import com.diegonmarcos.superapp.notificationcenter.PlaceName
import com.diegonmarcos.superapp.notificationcenter.WeatherFixStore
import com.diegonmarcos.superapp.notificationcenter.WeatherModel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Where the day-timelines card gets its inputs (#1734) and what it does when it cannot.
 *
 *  - POSITION: the Weather badge's persisted fix ([WeatherFixStore], Cloud Nav's coarse network fix, refreshed by the
 *    Weather badge every 4 h). This card makes NO location request of its own. No fix yet: the declared default city
 *    ([DEFAULT_CITY]), and the card says so.
 *  - TEMPERATURE + CONDITIONS: the fleet's existing weather source, MET Norway locationforecast ([WeatherModel.apiUrl]); the
 *    Weather badge's own cached body is merged in first so an open card rarely needs a request. Hours are merged by hour
 *    and kept 48 h so the hours already past today still show after the forecast has moved on.
 *  - EVERYTHING ELSE (sun, moon, signs, planets) is computed locally from the position and the clock ([Astro]); it needs no network.
 *
 * REAL STATE ONLY. A failed fetch keeps the last cache and says how old it is; no cache says why there is none.
 * Nothing here throws.
 */
object DayRepo {
    data class City(val name: String, val lat: Double, val lon: Double)

    /** The declared fallback place when the phone has no fix yet. */
    val DEFAULT_CITY = City("Berlin", 52.52, 13.405)

    /** A cached forecast younger than this is used without a request. */
    const val FRESH_MS = 60 * 60_000L
    private const val KEEP_HOURS = 48L
    private const val SAME_SPOT_KM = 25.0

    interface Store { fun load(): String?; fun save(json: String) }

    data class Cache(val savedMs: Long, val lat: Double, val lon: Double, val hours: List<Hour>)
    data class Resolved(val hours: List<Hour>, val status: DataStatus)

    // ------------------------------------------------------------ parsing / cache (pure)

    /** MET locationforecast/complete body to hours (temperature + the hour's symbol_code); empty on any contract break. */
    fun parseMet(body: String?): List<Hour> = runCatching {
        val ts = JSONObject(body ?: return emptyList()).getJSONObject("properties").getJSONArray("timeseries")
        val out = ArrayList<Hour>()
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val t = Instant.parse(e.getString("time"))
            if (t.epochSecond % 3600L != 0L) continue
            val data = e.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            val sym = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull { k ->
                data.optJSONObject(k)?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() }
            }
            out += Hour(t.epochSecond / 3600L, temp, sym)
        }
        out
    }.getOrDefault(emptyList())

    fun encode(c: Cache): String = JSONObject().put("v", 1).put("saved", c.savedMs).put("lat", c.lat).put("lon", c.lon)
        .put("hours", JSONArray().also { a -> c.hours.forEach { a.put(JSONArray().put(it.epochHour).put(it.tempC).put(it.symbol ?: JSONObject.NULL)) } })
        .toString()

    fun decode(json: String?): Cache? = runCatching {
        val o = JSONObject(json ?: return null)
        val a = o.getJSONArray("hours")
        val hs = (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            Hour(r.getLong(0), r.getDouble(1), if (r.isNull(2)) null else r.getString(2))
        }
        Cache(o.getLong("saved"), o.getDouble("lat"), o.getDouble("lon"), hs)
    }.getOrNull()

    /** [fresh] overrides [old] hour by hour; hours older than 48 h are dropped. */
    fun merge(old: List<Hour>, fresh: List<Hour>, nowMs: Long): List<Hour> {
        val floor = nowMs / 3_600_000L - KEEP_HOURS
        val m = java.util.TreeMap<Long, Hour>()
        for (h in old) m[h.epochHour] = h
        for (h in fresh) m[h.epochHour] = h
        return m.values.filter { it.epochHour >= floor }
    }

    fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dx = (aLon - bLon) * cos(Math.toRadians((aLat + bLat) / 2)) * 111.32
        val dy = (aLat - bLat) * 110.57
        return hypot(dx, dy)
    }

    /**
     * The hours for ([lat], [lon]) and how good they are. [seed] is the Weather badge's cached forecast (with its save time);
     * [fetch] returns a MET body or throws / returns null when offline. [allowFetch] false = cache only (the first, instant paint).
     */
    fun resolve(
        nowMs: Long, lat: Double, lon: Double, store: Store, seed: Pair<List<Hour>, Long>?, allowFetch: Boolean, fetch: (Double, Double) -> String?,
    ): Resolved {
        val cache = decode(runCatching { store.load() }.getOrNull())
        val same = cache != null && km(cache.lat, cache.lon, lat, lon) < SAME_SPOT_KM
        var hours = if (same) cache!!.hours else emptyList()
        var savedMs = if (same) cache!!.savedMs else 0L
        if (seed != null && seed.first.isNotEmpty()) { hours = merge(hours, seed.first, nowMs); savedMs = maxOf(savedMs, seed.second) }
        val age = nowMs - savedMs
        if (hours.isNotEmpty() && age in 0 until FRESH_MS) return Resolved(hours, DataStatus(DataStatus.Kind.LIVE, age))
        if (!allowFetch) return noFetch(hours, age)
        val failure: String
        val got = runCatching { fetch(lat, lon) }
        val parsed = got.getOrNull()?.let { parseMet(it) }.orEmpty()
        if (parsed.isNotEmpty()) {
            val merged = merge(hours, parsed, nowMs)
            runCatching { store.save(encode(Cache(nowMs, lat, lon, merged))) }
            return Resolved(merged, DataStatus(DataStatus.Kind.LIVE, 0L))
        }
        failure = when {
            got.isFailure -> "offline or the weather source is unreachable"
            got.getOrNull() == null -> "the weather source returned nothing"
            else -> "the weather source answered with data this card cannot read"
        }
        return if (hours.isNotEmpty()) Resolved(hours, DataStatus(DataStatus.Kind.CACHED, age.coerceAtLeast(0L), failure))
        else Resolved(emptyList(), DataStatus(DataStatus.Kind.NONE, 0L, "no forecast stored yet; $failure"))
    }

    private fun noFetch(hours: List<Hour>, age: Long): Resolved =
        if (hours.isNotEmpty()) Resolved(hours, DataStatus(DataStatus.Kind.CACHED, age.coerceAtLeast(0L), "refreshing"))
        else Resolved(emptyList(), DataStatus(DataStatus.Kind.NONE, 0L, "loading the forecast"))

    // ------------------------------------------------------------ Android glue

    data class Snapshot(val model: DayModel, val status: DataStatus, val locationNote: String?)

    private class PrefsStore(private val s: WeatherFixStore) : Store {
        override fun load(): String? = s.loadTimelines()
        override fun save(json: String) = s.saveTimelines(json)
    }

    /** The position: the stored fix (named by the Weather badge's cached place when it is the same spot), else the default city. */
    internal fun place(ctx: Context, store: WeatherFixStore, geocode: Boolean): Triple<City, String?, Boolean> {
        val fix = runCatching { store.load() }.getOrNull()
        if (fix == null) return Triple(DEFAULT_CITY, "Default city: no location fix on this phone yet", true)
        val named = runCatching { store.loadForecast() }.getOrNull()
            ?.takeIf { it.place.isNotBlank() && km(it.lat, it.lon, fix.lat, fix.lon) < SAME_SPOT_KM }?.place
        val name = named ?: if (geocode) runCatching { PlaceName.resolve(ctx, fix.lat, fix.lon) }.getOrNull() else null
        return Triple(City(name ?: PlaceName.coords(fix.lat, fix.lon), fix.lat, fix.lon), null, false)
    }

    /** Never throws. [allowFetch] false = what is already on the phone, no network (instant paint). Call off the main thread. */
    fun snapshot(ctx: Context, nowMs: Long = System.currentTimeMillis(), allowFetch: Boolean): Snapshot {
        val zone = ZoneId.systemDefault()
        val ws = runCatching { WeatherFixStore(ctx) }.getOrNull()
        val (city, note, _) = if (ws != null) place(ctx, ws, allowFetch) else Triple(DEFAULT_CITY, "Default city: location store unavailable", true)
        val seed = runCatching { ws?.loadForecast() }.getOrNull()?.let { Pair(parseMet(it.body), it.savedMs) }
        val res = if (ws == null) Resolved(emptyList(), DataStatus(DataStatus.Kind.NONE, 0L, "no storage"))
        else runCatching { resolve(nowMs, city.lat, city.lon, PrefsStore(ws), seed, allowFetch, ::httpFetch) }
            .getOrElse { Resolved(emptyList(), DataStatus(DataStatus.Kind.NONE, 0L, "error: ${it.javaClass.simpleName}")) }
        val model = DayTimelines.build(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), zone, city.name, city.lat, city.lon, res.hours, nowMs)
        return Snapshot(model, res.status, note)
    }

    private fun httpFetch(lat: Double, lon: Double): String? {
        val conn = (URL(WeatherModel.apiUrl(lat, lon)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 5_000
            setRequestProperty("User-Agent", WeatherModel.USER_AGENT); setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

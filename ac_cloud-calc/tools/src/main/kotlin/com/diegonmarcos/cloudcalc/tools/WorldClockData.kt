package com.diegonmarcos.cloudcalc.tools

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * #1423 Time & Date ▸ World Clock data: the declared city catalogue (data/assets/worldmap/cities.json, generated from
 * GeoNames by data/gen/worldclock.py), its search and its rules, plus the world map's maths: equirectangular projection,
 * nominal time-zone bands and the day/night terminator. No Android here; the app reads the assets and hands the text in.
 */
object WorldClockData {
    data class City(val name: String, val country: String, val zone: String, val lat: Double, val lon: Double, val capital: Boolean)

    data class Catalogue(
        val countries: Map<String, String>, val noCapital: Set<String>, val big: List<String>, val minBig: Int,
        val cities: List<City>, val zones: List<String>,
    )

    fun parse(text: String): Catalogue {
        val o = JSONObject(text)
        val cs = o.getJSONArray("countries")
        val ci = o.getJSONArray("cities")
        fun strings(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Catalogue(
            countries = (0 until cs.length()).associate { cs.getJSONObject(it).let { c -> c.getString("code") to c.getString("name") } },
            noCapital = strings("no_capital").toSet(), big = strings("big_countries"), minBig = o.getInt("min_big_cities"),
            cities = (0 until ci.length()).map { ci.getJSONObject(it).let { c ->
                City(c.getString("name"), c.getString("country"), c.getString("zone"), c.getDouble("lat"), c.getDouble("lon"), c.optBoolean("capital"))
            } },
            zones = strings("zones"),
        )
    }

    /**
     * Every rule the catalogue must keep, as readable failures (empty = whole): each country of [iso] not declared
     * capital-less has a capital, each big country has at least minBig cities, every zone is one of [validZones] and
     * every coordinate is on the globe.
     */
    fun problems(c: Catalogue, iso: Collection<String>, validZones: Set<String>): List<String> {
        val out = mutableListOf<String>()
        val caps = c.cities.filter { it.capital }.map { it.country }.toSet()
        for (cc in iso) if (cc !in c.noCapital && cc !in caps) out += "no capital: $cc"
        for (cc in c.countries.keys) if (cc !in caps) out += "no capital: $cc"
        for (cc in c.big) c.cities.count { it.country == cc }.let { if (it < c.minBig) out += "big country $cc has $it cities" }
        for (z in c.zones + c.cities.map { it.zone }) if (z !in validZones) out += "invalid zone: $z"
        for (x in c.cities) if (abs(x.lat) > 90 || abs(x.lon) > 180) out += "off the globe: ${x.name}"
        return out.distinct()
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Cities whose name, country (code or name) or zone contains [query], ignoring case and accents; capitals first. */
    fun search(c: Catalogue, query: String): List<City> {
        val q = fold(query.trim())
        return c.cities.filter { x ->
            q.isEmpty() || q in fold(x.name) || q == x.country.lowercase() || q in fold(c.countries[x.country] ?: "") || q in fold(x.zone)
        }.sortedWith(compareBy({ !it.capital }, { it.name }))
    }

    /** Zones (any IANA region id) containing [query], ignoring case; "_" reads as a space. */
    fun searchZones(c: Catalogue, query: String): List<String> {
        val q = query.trim().lowercase()
        return c.zones.filter { q.isEmpty() || q in it.lowercase().replace('_', ' ') || q in it.lowercase() }
    }

    /** The city the map pins for a selected [zone]: one whose name starts with the zone's last part, else its capital, else any. */
    fun pinFor(c: Catalogue, zone: String): City? {
        val named = zone.substringAfterLast('/').replace('_', ' ')
        val inZone = c.cities.filter { it.zone == zone }
        return inZone.firstOrNull { fold(it.name).startsWith(fold(named)) } ?: inZone.firstOrNull { it.capital } ?: inZone.firstOrNull()
    }

    /** The catalogue city nearest to ([lat], [lon]) within [maxDeg] degrees (longitude wraps), or null. */
    fun nearest(c: Catalogue, lat: Double, lon: Double, maxDeg: Double): City? =
        c.cities.map { it to dist(it.lat, it.lon, lat, lon) }.filter { it.second <= maxDeg }.minByOrNull { it.second }?.first

    internal fun dist(a: Double, b: Double, lat: Double, lon: Double): Double {
        val dl = abs(norm180(b - lon))
        return kotlin.math.sqrt((a - lat) * (a - lat) + dl * dl)
    }

    fun norm180(d: Double): Double { val x = (d + 180.0) % 360.0; return (if (x < 0) x + 360.0 else x) - 180.0 }

    // ── projection (equirectangular) ────────────────────────────────────────────────────────

    fun x(lon: Double, w: Double) = (lon + 180.0) / 360.0 * w
    fun y(lat: Double, h: Double) = (90.0 - lat) / 180.0 * h
    fun lon(x: Double, w: Double) = x / w * 360.0 - 180.0
    fun lat(y: Double, h: Double) = 90.0 - y / h * 180.0

    /** Natural Earth land (data/assets/worldmap/land.json): each polygon a flat lon,lat array. */
    fun parseLand(text: String): List<DoubleArray> {
        val a = JSONObject(text).getJSONArray("polygons")
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> DoubleArray(p.length()) { p.getDouble(it) } } }
    }

    // ── time-zone bands ─────────────────────────────────────────────────────────────────────

    data class Band(val offset: Int, val west: Double, val east: Double) { val label: String get() = "UTC" + (if (offset >= 0) "+" else "−") + abs(offset) }

    /** The 25 nominal bands, UTC−12 … UTC+12, each 15° centred on offset × 15° (the ±12 halves at the date line). */
    fun bands(): List<Band> = (-12..12).map { Band(it, maxOf(-180.0, it * 15 - 7.5), minOf(180.0, it * 15 + 7.5)) }

    /** The nominal offset in hours of a longitude. */
    fun nominalOffset(lon: Double): Int = (norm180(lon) / 15.0).roundToInt()

    // ── the sun ─────────────────────────────────────────────────────────────────────────────

    /** The subsolar point at [epochMs]: declination (= its latitude) and longitude, degrees. */
    data class Sun(val decl: Double, val lon: Double)

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Low-precision solar position (Astronomical Almanac, about 0.01° in 1950-2050). */
    fun sun(epochMs: Long): Sun {
        val n = epochMs / 86_400_000.0 - 10_957.5 // days since J2000.0 (2000-01-01 12:00 UTC)
        val l = 280.460 + 0.9856474 * n
        val g = rad(357.528 + 0.9856003 * n)
        val lambda = rad(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = rad(23.439 - 0.0000004 * n)
        val decl = deg(asin(sin(eps) * sin(lambda)))
        val ra = deg(atan2(cos(eps) * sin(lambda), cos(lambda)))
        val gmst = 280.46061837 + 360.98564736629 * n
        return Sun(decl, norm180(ra - gmst))
    }

    /** The sun's elevation in degrees at ([lat], [lon]) for [s]. */
    fun elevation(s: Sun, lat: Double, lon: Double): Double {
        val h = rad(lon - s.lon)
        return deg(asin(sin(rad(lat)) * sin(rad(s.decl)) + cos(rad(lat)) * cos(rad(s.decl)) * cos(h)))
    }

    fun isDaylight(s: Sun, lat: Double, lon: Double) = elevation(s, lat, lon) > 0.0

    /** The terminator's latitude at [lon] (elevation 0). Declination is kept off 0 so the equinox line stays defined. */
    fun terminatorLat(s: Sun, lon: Double): Double {
        val d = if (abs(s.decl) < 1e-6) 1e-6 else s.decl
        return deg(atan(-cos(rad(lon - s.lon)) / tan(rad(d))))
    }

    /** The night polygon as lon,lat pairs: the terminator every [step]° then closed over the pole in darkness. */
    fun nightPolygon(s: Sun, step: Double = 2.0): DoubleArray {
        val pts = ArrayList<Double>()
        var lon = -180.0
        while (lon <= 180.0 + 1e-9) { pts += lon; pts += terminatorLat(s, lon); lon += step }
        val pole = if (s.decl >= 0) -90.0 else 90.0
        pts += 180.0; pts += pole; pts += -180.0; pts += pole
        return pts.toDoubleArray()
    }
}

package com.diegonmarcos.cloudkeyboard.grabber

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * #1793 the Grabber Place card, the pure part (JVM tests: GrabberPlaceTest). Whether a geocoded text is a place, the distance
 * and flight arithmetic, the map zoom, the rate gate and cache the free services ask for, and the parsers of the answers of
 * OSRM routing and Wikipedia / Wikimedia Commons. No Android class is touched here.
 */

/** Great-circle (haversine) distance and the flight estimate. */
object GeoMath {
    const val EARTH_RADIUS_KM = 6371.0088 // the IUGG mean radius
    const val CRUISE_KMH = 800.0
    const val FLIGHT_OVERHEAD_H = 1.0

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** An ESTIMATE, labelled as one in the card: the great-circle distance at ~800 km/h, plus 1 h for take-off, landing and taxi. */
    fun flightHours(km: Double): Double = km / CRUISE_KMH + FLIGHT_OVERHEAD_H

    fun km(m: Double): String = when {
        m < 1000 -> "${m.roundToInt()} m"
        m < 100_000 -> String.format(Locale.ROOT, "%.1f km", m / 1000.0)
        else -> "${(m / 1000.0).roundToLong()} km"
    }

    /** 42 s -> "1 min", 3720 s -> "1 h 02 min", 2 days -> "2 d 3 h". */
    fun duration(seconds: Double): String {
        val min = (seconds / 60.0).roundToLong().coerceAtLeast(1)
        return when {
            min < 60 -> "$min min"
            min < 48 * 60 -> String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60)
            else -> "${min / 1440} d ${(min % 1440) / 60} h"
        }
    }
}

/** How the user's position reaches a destination. AIR is never routed: it is the great-circle estimate. */
enum class TravelMode(val label: String, val osrmHost: String, val osrmProfile: String) {
    CAR("BY CAR", "routing.openstreetmap.de/routed-car", "driving"),
    FOOT("ON FOOT", "routing.openstreetmap.de/routed-foot", "foot"),
    BIKE("BY BICYCLE", "routing.openstreetmap.de/routed-bike", "bike"),
    AIR("BY AIR · ESTIMATE", "", "");

    val routed: Boolean get() = osrmHost.isNotEmpty()

    /** The free public OSRM servers of FOSSGIS (OpenStreetMap Germany): one per profile, lon,lat order, no geometry. */
    fun routeUrl(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String =
        String.format(Locale.ROOT, "https://%s/route/v1/%s/%.6f,%.6f;%.6f,%.6f?overview=false&alternatives=false&steps=false",
            osrmHost, osrmProfile, fromLon, fromLat, toLon, toLat)
}

/** One route answer: metres and seconds. */
data class Leg(val meters: Double, val seconds: Double)

/** What the geocoder found, made readable for the card: the address line and the place type. */
data class PlaceInfo(val address: String, val type: String, val detail: String)

object PlaceDecision {
    /** OSM categories that name a place on a map (a city, a mountain, a museum), not a shop or a bus stop named like a word. */
    private val PLACE_CATEGORIES = setOf("place", "boundary", "natural", "tourism", "historic", "aeroway", "waterway", "mountain_pass", "landuse", "leisure", "building", "man_made")
    const val MIN_IMPORTANCE = 0.40

    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** The geocoder's own name of the hit, or the first part of its display name. */
    fun hitName(hit: GeoHit): String = hit.shortName.ifBlank { hit.name.substringBefore(',') }

    /**
     * The place / not-a-place decision. No hit is not a place. Text that already reads as an address or a place (the classifier)
     * is one as soon as the geocoder knows it. Any other word or phrase is a place only when the geocoder's best hit is a mapped
     * place of some importance whose name IS the text ("Paris", "Mont Blanc"), so "serendipity" or "apple" stay words even when
     * some café or hamlet carries the name.
     */
    fun isPlace(kind: SelectionKind, query: String, hit: GeoHit?): Boolean {
        if (hit == null || kind == SelectionKind.EMPTY) return false
        if (!hit.lat.isFinite() || !hit.lon.isFinite()) return false
        if (kind.showsMap) return true
        if (hit.category.isNotEmpty() && hit.category !in PLACE_CATEGORIES) return false
        if (hit.importance < MIN_IMPORTANCE) return false
        val q = fold(query)
        return q.isNotEmpty() && fold(hitName(hit)) == q
    }

    /** The map zoom for a hit: a country far out, a house close in (Nominatim place_rank, 4 = country ... 30 = house). */
    fun zoom(kind: SelectionKind, placeRank: Int): Int {
        val z = when {
            placeRank <= 0 -> if (kind == SelectionKind.ADDRESS) 16 else 14
            placeRank <= 4 -> 4
            placeRank <= 8 -> 6
            placeRank <= 12 -> 9
            placeRank <= 16 -> 11
            placeRank <= 20 -> 13
            placeRank <= 25 -> 15
            else -> 16
        }
        return if (kind == SelectionKind.ADDRESS) maxOf(z, 16) else z
    }

    fun info(hit: GeoHit): PlaceInfo {
        val type = (hit.addressType.ifBlank { hit.type }).replace('_', ' ').uppercase(Locale.ROOT)
        val detail = listOf(hit.category, hit.type).filter { it.isNotBlank() }.joinToString("/")
        return PlaceInfo(hit.name, type.ifBlank { "PLACE" }, detail)
    }
}

/** At most one request per [intervalMs] (Nominatim's usage policy: 1 per second). [reserve] returns how long to wait first. */
class RateGate(private val intervalMs: Long) {
    private var next = 0L

    @Synchronized fun reserve(nowMs: Long): Long {
        val at = maxOf(nowMs, next)
        next = at + intervalMs
        return at - nowMs
    }
}

/** A small least-recently-used cache (the geocoder answers, the routes, the term images). */
class Lru<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized fun get(k: K): V? = map[k]
    @Synchronized fun put(k: K, v: V) { map[k] = v }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun keys(): List<K> = map.keys.toList()
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)
}

/** One term image with what its licence asks: where it came from and who made it. */
data class TermImage(val thumbUrl: String, val title: String, val pageUrl: String, val source: String, val attribution: String)

object PlaceParsers {
    /** OSRM /route: the first route's distance (m) and duration (s); null when the server found none ("NoRoute", an island). */
    fun osrmLeg(json: String): Leg? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("code") != "Ok") return@runCatching null
        val r = o.optJSONArray("routes")?.optJSONObject(0) ?: return@runCatching null
        val d = r.optDouble("distance", Double.NaN); val t = r.optDouble("duration", Double.NaN)
        if (!d.isFinite() || !t.isFinite()) null else Leg(d, t)
    }.getOrNull()

    private fun strip(html: String): String = GrabberParsers.clean(html)

    /** Wikipedia REST page summary: its thumbnail, when the page is an article with one (a disambiguation page is not the term). */
    fun wikipediaSummary(json: String, lang: String = "en"): TermImage? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "disambiguation") return@runCatching null
        val thumb = o.optJSONObject("thumbnail")?.optString("source").orEmpty()
        if (thumb.isBlank()) return@runCatching null
        val title = o.optString("title")
        val page = o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            .ifBlank { "https://$lang.wikipedia.org/wiki/" + GrabberRouter.enc(title.replace(' ', '_')) }
        TermImage(thumb, title, page, "WIKIPEDIA", "Image from the Wikipedia article \"$title\" (Wikimedia Commons; author and licence on its file page)")
    }.getOrNull()

    /** Wikimedia Commons search (File namespace) with imageinfo: the first file's thumbnail, its author and its licence. */
    fun commonsFirst(json: String): TermImage? = runCatching {
        val pages = org.json.JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching null
        val all = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }.toList()
        val first = all.minByOrNull { it.optInt("index", Int.MAX_VALUE) } ?: return@runCatching null
        val info = first.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@runCatching null
        val thumb = info.optString("thumburl").ifBlank { info.optString("url") }
        if (thumb.isBlank()) return@runCatching null
        val meta = info.optJSONObject("extmetadata")
        fun m(k: String) = strip(meta?.optJSONObject(k)?.optString("value").orEmpty())
        val artist = m("Artist").ifBlank { "unknown author" }
        val licence = m("LicenseShortName").ifBlank { "see the file page" }
        val title = first.optString("title").removePrefix("File:")
        TermImage(thumb, title, info.optString("descriptionurl"), "WIKIMEDIA COMMONS", "$artist · $licence · Wikimedia Commons")
    }.getOrNull()
}

/** The trip of the card: from where, the air estimate, and each routed mode's leg (or why it has none). */
data class TripPlan(val fromLat: Double, val fromLon: Double, val fromNote: String, val airKm: Double, val flightHours: Double) {
    companion object {
        fun of(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, note: String): TripPlan {
            val km = GeoMath.haversineKm(fromLat, fromLon, toLat, toLon)
            return TripPlan(fromLat, fromLon, note, km, GeoMath.flightHours(km))
        }
    }
}

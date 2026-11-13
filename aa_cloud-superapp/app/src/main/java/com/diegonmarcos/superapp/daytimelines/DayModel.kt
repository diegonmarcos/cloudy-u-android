package com.diegonmarcos.superapp.daytimelines

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One forecast hour from the fleet weather source (MET Norway): [epochHour] = UTC hours since 1970, [symbol] = its symbol_code or null. */
data class Hour(val epochHour: Long, val tempC: Double, val symbol: String?)

/** A colour stop of a bar: [frac] 0..1 along the local day, [argb] an ARGB int. */
data class Stop(val frac: Float, val argb: Int)

/** The sky condition a weather icon stands for. */
enum class Cond(val day: String, val night: String, val label: String) {
    CLEAR("☀️", "🌙", "Clear"),
    PARTLY("🌤️", "☁️", "Partly cloudy"),
    CLOUDY("☁️", "☁️", "Cloudy"),
    FOG("🌫️", "🌫️", "Fog"),
    RAIN("🌧️", "🌧️", "Rain"),
    SLEET("🌨️", "🌨️", "Sleet"),
    SNOW("❄️", "❄️", "Snow"),
    THUNDER("⛈️", "⛈️", "Thunder");

    fun glyph(night: Boolean) = if (night) this.night else day
}

data class TempPoint(val hour: Int, val frac: Float, val tempC: Double, val cond: Cond?)
data class WeatherIcon(val hour: Int, val frac: Float, val cond: Cond, val night: Boolean)
data class TempBar(val points: List<TempPoint>, val stops: List<Stop>, val icons: List<WeatherIcon>, val minC: Double?, val maxC: Double?)
data class MoonBar(
    val above: List<Pair<Float, Float>>, val rise: Float?, val set: Float?, val transit: Float?,
    val riseMs: Long?, val setMs: Long?, val transitMs: Long?, val phase: Astro.Phase, val lonDeg: Double, val sign: Astro.Sign,
)
data class SkyObject(val id: String, val glyph: String, val label: String, val lonDeg: Double, val sign: Astro.Sign, val frac: Float?, val transitMs: Long?)
data class Legend(val daylight: String, val temp: String, val moon: String, val signs: String)

data class DayModel(
    val date: LocalDate, val zoneId: String, val place: String, val lat: Double, val lon: Double,
    val dayStartMs: Long, val dayEndMs: Long, val nowFrac: Float?,
    val sun: Astro.SolarDay, val dayStops: List<Stop>, val temp: TempBar, val moon: MoonBar,
    val sky: List<SkyObject>, val legend: Legend, val header: String,
)

/** How good the temperature data behind the card is: live (just fetched), cached (offline / source down: last result and its age) or none (with the reason). */
data class DataStatus(val kind: Kind, val ageMs: Long = 0L, val reason: String? = null) {
    enum class Kind { LIVE, CACHED, NONE }

    fun line(): String = when (kind) {
        Kind.LIVE -> "Temperature: updated " + ageText(ageMs)
        Kind.CACHED -> "Temperature: cached " + ageText(ageMs) + (reason?.let { " ($it)" } ?: "")
        Kind.NONE -> "Temperature: unavailable" + (reason?.let { " - $it" } ?: "")
    }

    companion object {
        fun ageText(ms: Long): String {
            val m = (ms.coerceAtLeast(0L) / 60_000L)
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                m < 48 * 60 -> "${m / 60} h ago"
                else -> "${m / (24 * 60)} d ago"
            }
        }
    }
}

object DayTimelines {
    private val NIGHT = 0xFF0B1026.toInt()
    private val DAWN = 0xFF2E3A78.toInt()
    private val RISE = 0xFFF29E4C.toInt()
    private val DAY = 0xFF5AB8F5.toInt()
    private val NOON = 0xFF8CD3FF.toInt()
    private val SET = 0xFFEF7B45.toInt()
    private val DUSK = 0xFF3A2D6E.toInt()
    private val RAMP = listOf(
        0.0f to 0xFF3B82F6.toInt(), 0.25f to 0xFF22D3EE.toInt(), 0.5f to 0xFF4ADE80.toInt(), 0.75f to 0xFFFACC15.toInt(), 1.0f to 0xFFEF4444.toInt(),
    )
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val HEAD = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.US)

    /** MET symbol_code ("partlycloudy_day", "heavyrain", "lightsleetshowersandthunder_night"...) to a condition; null = not recognised, no icon. */
    fun condOf(symbol: String?): Cond? {
        val s = symbol?.lowercase(Locale.US)?.substringBefore('_') ?: return null
        return when {
            s.isBlank() -> null
            "thunder" in s -> Cond.THUNDER
            "sleet" in s -> Cond.SLEET
            "snow" in s -> Cond.SNOW
            "rain" in s || "drizzle" in s -> Cond.RAIN
            s == "fog" -> Cond.FOG
            s == "clearsky" || s == "fair" -> Cond.CLEAR
            s == "partlycloudy" -> Cond.PARTLY
            s == "cloudy" -> Cond.CLOUDY
            else -> null
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a ushr sh) and 0xFF) + ((((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * t)).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The relative colour of [t] between the day's [min] (blue) and [max] (red); a flat day sits mid-ramp. */
    fun tempColor(t: Double, min: Double, max: Double): Int {
        val n = if (max - min < 0.5) 0.5f else ((t - min) / (max - min)).toFloat().coerceIn(0f, 1f)
        for (i in 1 until RAMP.size) if (n <= RAMP[i].first) {
            val (f0, c0) = RAMP[i - 1]; val (f1, c1) = RAMP[i]
            return lerp(c0, c1, (n - f0) / (f1 - f0))
        }
        return RAMP.last().second
    }

    /** The day/night gradient from the real events: night, dawn, sunrise, full day, solar noon, full day, sunset, dusk, night. Polar day / night: one flat colour. */
    fun dayStops(sun: Astro.SolarDay, startMs: Long, endMs: Long): List<Stop> {
        fun f(ms: Long) = ((ms - startMs).toDouble() / (endMs - startMs)).toFloat().coerceIn(0f, 1f)
        if (sun.polarNight) return listOf(Stop(0f, NIGHT), Stop(1f, NIGHT))
        if (sun.polarDay) return listOf(Stop(0f, DAY), Stop(f(sun.solarNoon), NOON), Stop(1f, DAY))
        val raw = ArrayList<Stop>()
        raw += Stop(0f, NIGHT)
        sun.dawn?.let { raw += Stop(f(it - 45 * 60_000L), NIGHT); raw += Stop(f(it), DAWN) }
        sun.sunrise?.let { raw += Stop(f(it), RISE); raw += Stop(f(it + 60 * 60_000L), DAY) }
        raw += Stop(f(sun.solarNoon), NOON)
        sun.sunset?.let { raw += Stop(f(it - 60 * 60_000L), DAY); raw += Stop(f(it), SET) }
        sun.dusk?.let { raw += Stop(f(it), DUSK); raw += Stop(f(it + 45 * 60_000L), NIGHT) }
        raw += Stop(1f, NIGHT)
        // Non-decreasing in frac (Brush needs it), first 0 and last 1.
        var last = 0f
        return raw.map { s -> last = maxOf(last, s.frac); Stop(last, s.argb) }
    }

    fun build(date: LocalDate, zone: ZoneId, place: String, lat: Double, lon: Double, hours: List<Hour>, nowMs: Long): DayModel {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun frac(ms: Long) = ((ms - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f)
        val sun = Astro.solarDay(date, zone, lat, lon)
        val dayStops = dayStops(sun, start, end)
        fun isNight(ms: Long) = when {
            sun.polarNight -> true
            sun.polarDay -> false
            else -> sun.sunrise != null && sun.sunset != null && (ms < sun.sunrise || ms > sun.sunset)
        }

        // ---- temperature
        val byHour = java.util.TreeMap<Int, Hour>()
        for (h in hours) {
            val ms = h.epochHour * 3_600_000L
            if (ms < start || ms >= end) continue
            byHour[Instant.ofEpochMilli(ms).atZone(zone).hour] = h
        }
        val pts = byHour.map { (hr, h) ->
            val ms = h.epochHour * 3_600_000L + 1_800_000L
            TempPoint(hr, frac(ms), h.tempC, condOf(h.symbol))
        }
        val minC = pts.minOfOrNull { it.tempC }; val maxC = pts.maxOfOrNull { it.tempC }
        val stops = if (minC == null || maxC == null) emptyList() else pts.map { Stop(it.frac, tempColor(it.tempC, minC, maxC)) }
        val icons = ArrayList<WeatherIcon>()
        var lastCond: Cond? = null; var lastFrac = -1f
        for (p in pts) {
            val c = p.cond ?: continue
            if (c != lastCond && (lastCond == null || p.frac - lastFrac >= 1.5f / 24f)) {
                icons += WeatherIcon(p.hour, p.frac, c, isNight(start + (p.frac * (end - start)).toLong()))
                lastCond = c; lastFrac = p.frac
            }
        }
        val temp = TempBar(pts, stops, icons, minC, maxC)

        // ---- moon
        val md = Astro.moonDay(date, zone, lat, lon)
        val ref = if (nowMs in start until end) nowMs else sun.solarNoon
        val ph = Astro.phase(ref)
        val jdRef = Astro.julianDay(ref)
        val moonLon = Astro.moonEcliptic(jdRef).first
        val moon = MoonBar(
            md.above.map { frac(it.first) to frac(it.second) }, md.rise?.let(::frac), md.set?.let(::frac), md.transit?.let(::frac),
            md.rise, md.set, md.transit, ph, moonLon, Astro.sign(moonLon),
        )

        // ---- sky: bodies at their meridian transit; positions are ecliptic longitudes of the reference instant
        val sky = ArrayList<SkyObject>()
        val sunLon = Astro.sunPos(jdRef).lonDeg
        sky += SkyObject("sun", "☉", "Sun", sunLon, Astro.sign(sunLon), frac(sun.solarNoon), sun.solarNoon)
        sky += SkyObject("moon", "☽", "Moon", moonLon, Astro.sign(moonLon), md.transit?.let(::frac), md.transit)
        for (p in Astro.Planet.values()) {
            val l = Astro.planetEcliptic(p, jdRef).first
            val tr = Astro.transit(date, zone, lon) { Astro.planetEquatorial(p, it) }
            sky += SkyObject(p.name.lowercase(Locale.US), p.glyph, p.label, l, Astro.sign(l), tr?.let(::frac), tr)
        }

        // ---- legend + header
        fun hm(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(HHMM) } ?: "--:--"
        val daylight = when {
            sun.polarDay -> "polar day"
            sun.polarNight -> "polar night"
            else -> hm(sun.sunrise) + " - " + hm(sun.sunset)
        }
        val tempText = if (minC == null || maxC == null) "--" else "${maxC.roundToInt()}°~${minC.roundToInt()}°C"
        val moonText = "${ph.name} ${(ph.illumination * 100).roundToInt()}%"
        val sg = sky.first { it.id == "sun" }.sign; val mg = Astro.sign(moonLon)
        val signs = "☉ ${sg.glyph} ${sg.degree}° · ☽ ${mg.glyph} ${mg.degree}°"
        return DayModel(
            date, zone.id, place, lat, lon, start, end, if (nowMs in start until end) frac(nowMs) else null,
            sun, dayStops, temp, moon, sky, Legend(daylight, tempText, moonText, signs), date.format(HEAD),
        )
    }

    /** The data view: everything the bars are drawn from, as JSON. Times are ISO-8601 in the day's zone. */
    fun toJson(m: DayModel, status: DataStatus): JSONObject {
        val zone = ZoneId.of(m.zoneId)
        fun iso(ms: Long?): Any = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() } ?: JSONObject.NULL
        fun r(d: Double, n: Int = 2): Double { val k = Math.pow(10.0, n.toDouble()); return Math.round(d * k) / k }
        val o = JSONObject()
        o.put("date", m.date.toString()).put("weekday", m.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US))
        o.put("place", m.place).put("lat", r(m.lat, 4)).put("lon", r(m.lon, 4)).put("zone", m.zoneId)
        o.put("dataStatus", JSONObject().put("kind", status.kind.name).put("ageMs", status.ageMs).put("reason", status.reason ?: JSONObject.NULL))
        o.put("sun", JSONObject().put("dawn", iso(m.sun.dawn)).put("sunrise", iso(m.sun.sunrise)).put("solarNoon", iso(m.sun.solarNoon))
            .put("sunset", iso(m.sun.sunset)).put("dusk", iso(m.sun.dusk)).put("polarDay", m.sun.polarDay).put("polarNight", m.sun.polarNight))
        o.put("temperature", JSONObject().put("minC", m.temp.minC ?: JSONObject.NULL).put("maxC", m.temp.maxC ?: JSONObject.NULL)
            .put("hourly", JSONArray().also { a -> m.temp.points.forEach { a.put(JSONObject().put("hour", it.hour).put("tempC", r(it.tempC, 1)).put("condition", it.cond?.name ?: JSONObject.NULL)) } })
            .put("icons", JSONArray().also { a -> m.temp.icons.forEach { a.put(JSONObject().put("hour", it.hour).put("condition", it.cond.name).put("night", it.night)) } }))
        o.put("moon", JSONObject().put("phase", m.moon.phase.name).put("illumination", r(m.moon.phase.illumination, 3))
            .put("ageDays", r(m.moon.phase.ageDays, 1)).put("waxing", m.moon.phase.waxing)
            .put("rise", iso(m.moon.riseMs)).put("set", iso(m.moon.setMs)).put("transit", iso(m.moon.transitMs))
            .put("eclipticLon", r(m.moon.lonDeg)).put("sign", m.moon.sign.name))
        o.put("sky", JSONArray().also { a ->
            m.sky.forEach { a.put(JSONObject().put("id", it.id).put("eclipticLon", r(it.lonDeg)).put("sign", it.sign.name).put("degree", it.sign.degree).put("transit", iso(it.transitMs))) }
        })
        o.put("nowFraction", m.nowFrac?.let { r(it.toDouble(), 4) } ?: JSONObject.NULL)
        return o
    }
}

package com.diegonmarcos.superapp.daytimelines

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * PURE astronomy for the day-timelines card (#1734). No network, no Android: everything the card draws about the sky is
 * computed here from a latitude, a longitude and a clock.
 *
 *  - Sun: the NOAA solar-calculator algorithm (Meeus-based): apparent longitude, declination, equation of time, then
 *    dawn / sunrise / solar noon / sunset / dusk from the hour angle (sunrise zenith 90.833 deg, civil twilight 96 deg).
 *  - Moon: the Astronomical Almanac low-precision series (about 0.3 deg), phase from the Sun-Moon elongation, rise / set from
 *    a 10-minute altitude scan of the day, transit from the hour angle.
 *  - Planets: the JPL "Keplerian elements for approximate positions" (1800-2050) for Mercury..Saturn and the Earth-Moon
 *    barycentre, solved with Kepler's equation; geocentric ecliptic longitude of date.
 */
object Astro {
    const val SYNODIC_DAYS = 29.530588853
    private const val J2000 = 2451545.0
    private const val ZENITH_SUNRISE = 90.833
    private const val ZENITH_CIVIL = 96.0
    /** Geocentric altitude of the Moon's centre at rise / set: 0.7275 x mean parallax (0.95 deg) - 0.567 deg refraction = +0.125 deg. */
    private const val MOON_HORIZON_DEG = 0.125
    private const val SIDEREAL_DEG_PER_DAY = 360.98564736629
    private const val DAY_MS = 86_400_000.0

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    fun norm360(x: Double): Double { val r = x % 360.0; return if (r < 0) r + 360.0 else r }
    private fun norm180(x: Double) = norm360(x + 180.0) - 180.0
    fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5
    private fun centuries(jd: Double) = (jd - J2000) / 36525.0
    private fun gmst(jd: Double) = norm360(280.46061837 + SIDEREAL_DEG_PER_DAY * (jd - J2000))
    private fun obliquity(t: Double) = 23.439291 - 0.0130042 * t

    // ---------------------------------------------------------------- Sun

    /** [lonDeg] apparent ecliptic longitude, [declDeg] declination, [raDeg] right ascension, [eqTimeMin] equation of time. */
    data class SunPos(val lonDeg: Double, val declDeg: Double, val raDeg: Double, val eqTimeMin: Double)

    fun sunPos(jd: Double): SunPos {
        val t = centuries(jd)
        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(rad(2 * m)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lam = norm360(l0 + c - 0.00569 - 0.00478 * sin(rad(omega)))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(lam))))
        val ra = norm360(deg(atan2(cos(rad(eps)) * sin(rad(lam)), cos(rad(lam)))))
        val y = tan(rad(eps / 2)).let { it * it }
        val eq = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return SunPos(lam, decl, ra, eq)
    }

    /** Instants are epoch millis (UTC); a null event does not happen that day at that latitude (polar day / night, no civil twilight end). */
    data class SolarDay(val dawn: Long?, val sunrise: Long?, val solarNoon: Long, val sunset: Long?, val dusk: Long?, val polarDay: Boolean, val polarNight: Boolean)

    /** The NOAA events of the LOCAL [date] in [zone] at ([latDeg], [lonDeg] east-positive). */
    fun solarDay(date: LocalDate, zone: ZoneId, latDeg: Double, lonDeg: Double): SolarDay {
        // The event minutes are relative to a UTC midnight; try the day before / of / after and keep the one on the local date.
        fun pick(f: (Long, Double) -> Double?): Long? {
            for (shift in intArrayOf(0, -1, 1)) {
                val base = date.plusDays(shift.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val noon = noonMinutes(base, lonDeg)
                val m = f(base, noon) ?: continue
                val ms = base + (m * 60_000.0).roundToLong()
                if (java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == date) return ms
            }
            return null
        }
        val noon = pick { _, n -> n } ?: date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val rise = pick { b, n -> eventMinutes(b, n, latDeg, lonDeg, ZENITH_SUNRISE, true) }
        val set = pick { b, n -> eventMinutes(b, n, latDeg, lonDeg, ZENITH_SUNRISE, false) }
        val dawn = pick { b, n -> eventMinutes(b, n, latDeg, lonDeg, ZENITH_CIVIL, true) }
        val dusk = pick { b, n -> eventMinutes(b, n, latDeg, lonDeg, ZENITH_CIVIL, false) }
        val decl = sunPos(julianDay(noon)).declDeg
        val noonAlt = 90.0 - abs(latDeg - decl)
        val up = noonAlt > -0.833
        return SolarDay(dawn, rise, noon, set, dusk, polarDay = rise == null && up, polarNight = rise == null && !up)
    }

    private fun noonMinutes(baseMs: Long, lonDeg: Double): Double {
        var n = 720.0 - 4 * lonDeg
        repeat(3) { n = 720.0 - 4 * lonDeg - sunPos(julianDay(baseMs + (n * 60_000.0).toLong())).eqTimeMin }
        return n
    }

    private fun eventMinutes(baseMs: Long, noonMin: Double, lat: Double, lon: Double, zenith: Double, rising: Boolean): Double? {
        var m = noonMin + if (rising) -360.0 else 360.0
        repeat(4) {
            val p = sunPos(julianDay(baseMs + (m * 60_000.0).toLong()))
            val c = cos(rad(zenith)) / (cos(rad(lat)) * cos(rad(p.declDeg))) - tan(rad(lat)) * tan(rad(p.declDeg))
            if (c < -1.0 || c > 1.0) return null
            val ha = deg(acos(c))
            m = 720.0 - 4 * (lon + if (rising) ha else -ha) - p.eqTimeMin
        }
        return m
    }

    // ---------------------------------------------------------------- Moon

    /** Geocentric ecliptic (longitude, latitude) of the Moon in degrees. */
    fun moonEcliptic(jd: Double): Pair<Double, Double> {
        val t = centuries(jd)
        fun s(a: Double, b: Double) = sin(rad(a + b * t))
        val lon = norm360(
            218.32 + 481267.881 * t + 6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) + 0.66 * s(235.7, 890534.22) +
                0.21 * s(269.9, 954397.74) - 0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03),
        )
        val lat = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.2, 960400.89) - 0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21)
        return lon to lat
    }

    data class Equ(val raDeg: Double, val declDeg: Double)

    fun equatorial(lonDeg: Double, latDeg: Double, jd: Double): Equ {
        val eps = rad(obliquity(centuries(jd)))
        val l = rad(lonDeg); val b = rad(latDeg)
        val ra = norm360(deg(atan2(sin(l) * cos(eps) - tan(b) * sin(eps), cos(l))))
        val dec = deg(asin(sin(b) * cos(eps) + cos(b) * sin(eps) * sin(l)))
        return Equ(ra, dec)
    }

    fun moonEquatorial(jd: Double): Equ = moonEcliptic(jd).let { equatorial(it.first, it.second, jd) }

    fun altitudeDeg(eq: Equ, latDeg: Double, lonDeg: Double, jd: Double): Double {
        val h = rad(gmst(jd) + lonDeg - eq.raDeg)
        return deg(asin(sin(rad(latDeg)) * sin(rad(eq.declDeg)) + cos(rad(latDeg)) * cos(rad(eq.declDeg)) * cos(h)))
    }

    data class Phase(val elongationDeg: Double, val illumination: Double, val ageDays: Double, val waxing: Boolean, val name: String)

    fun phase(epochMs: Long): Phase {
        val jd = julianDay(epochMs)
        val el = norm360(moonEcliptic(jd).first - sunPos(jd).lonDeg)
        val name = when {
            el < 11.25 || el >= 348.75 -> "New Moon"
            el < 78.75 -> "Waxing Crescent"
            el < 101.25 -> "First Quarter"
            el < 168.75 -> "Waxing Gibbous"
            el < 191.25 -> "Full Moon"
            el < 258.75 -> "Waning Gibbous"
            el < 281.25 -> "Last Quarter"
            else -> "Waning Crescent"
        }
        return Phase(el, (1 - cos(rad(el))) / 2, el / 360.0 * SYNODIC_DAYS, el < 180.0, name)
    }

    /** [rise] / [set] / [transit]: epoch millis inside the local day or null; [above]: spans (start, end) in epoch millis the Moon is above the horizon. */
    data class MoonDay(val rise: Long?, val set: Long?, val transit: Long?, val above: List<Pair<Long, Long>>)

    fun moonDay(date: LocalDate, zone: ZoneId, latDeg: Double, lonDeg: Double): MoonDay {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val step = 10 * 60_000L
        fun h(ms: Long) = altitudeDeg(moonEquatorial(julianDay(ms)), latDeg, lonDeg, julianDay(ms)) - MOON_HORIZON_DEG
        val rises = ArrayList<Long>(); val sets = ArrayList<Long>(); val spans = ArrayList<Pair<Long, Long>>()
        var t = start
        var prev = h(t)
        var spanStart: Long? = if (prev > 0) start else null
        while (t < end) {
            val nt = minOf(t + step, end)
            val cur = h(nt)
            if (prev <= 0 && cur > 0) {
                val x = t + ((nt - t) * (-prev / (cur - prev))).roundToLong(); rises += x; spanStart = x
            } else if (prev > 0 && cur <= 0) {
                val x = t + ((nt - t) * (prev / (prev - cur))).roundToLong(); sets += x
                spanStart?.let { spans += it to x }; spanStart = null
            }
            t = nt; prev = cur
        }
        spanStart?.let { spans += it to end }
        val tr = transit(date, zone, lonDeg) { moonEquatorial(it) }
        return MoonDay(rises.firstOrNull(), sets.firstOrNull(), tr, spans)
    }

    /** The meridian transit of a body on the local [date] (null when it does not transit that local day, as the Moon some days). */
    fun transit(date: LocalDate, zone: ZoneId, lonDeg: Double, eqAt: (Double) -> Equ): Long? {
        val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli().toDouble()
        fun converge(from: Double): Double {
            var t = from
            repeat(4) {
                val jd = julianDay(t.toLong())
                t += norm180(eqAt(jd).raDeg - lonDeg - gmst(jd)) / SIDEREAL_DEG_PER_DAY * DAY_MS
            }
            return t
        }
        val c0 = converge(noon)
        for (t in doubleArrayOf(c0, converge(c0 - DAY_MS), converge(c0 + DAY_MS))) {
            val ms = t.roundToLong()
            if (java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == date) return ms
        }
        return null
    }

    // ---------------------------------------------------------------- Planets

    enum class Planet(val label: String, val glyph: String) {
        MERCURY("Mercury", "☿"), VENUS("Venus", "♀"), MARS("Mars", "♂"), JUPITER("Jupiter", "♃"), SATURN("Saturn", "♄")
    }

    // a, da, e, de, I, dI, L, dL, long.peri, d, long.node, d  (AU, deg; rates per Julian century): JPL Table 1, 1800-2050.
    private val ELEMENTS: Map<String, DoubleArray> = mapOf(
        "MERCURY" to doubleArrayOf(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
        "VENUS" to doubleArrayOf(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
        "EARTH" to doubleArrayOf(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0),
        "MARS" to doubleArrayOf(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
        "JUPITER" to doubleArrayOf(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
        "SATURN" to doubleArrayOf(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
    )

    private fun helio(name: String, t: Double): DoubleArray {
        val k = ELEMENTS.getValue(name)
        val a = k[0] + k[1] * t; val e = k[2] + k[3] * t; val inc = k[4] + k[5] * t
        val l = k[6] + k[7] * t; val w = k[8] + k[9] * t; val node = k[10] + k[11] * t
        val m = norm180(l - w)
        var ea = rad(m) + e * sin(rad(m))
        repeat(8) { ea -= (ea - e * sin(ea) - rad(m)) / (1 - e * cos(ea)) }
        val xp = a * (cos(ea) - e); val yp = a * sqrt(1 - e * e) * sin(ea)
        val om = rad(w - node); val bn = rad(node); val ii = rad(inc)
        val x = (cos(om) * cos(bn) - sin(om) * sin(bn) * cos(ii)) * xp + (-sin(om) * cos(bn) - cos(om) * sin(bn) * cos(ii)) * yp
        val y = (cos(om) * sin(bn) + sin(om) * cos(bn) * cos(ii)) * xp + (-sin(om) * sin(bn) + cos(om) * cos(bn) * cos(ii)) * yp
        val z = sin(om) * sin(ii) * xp + cos(om) * sin(ii) * yp
        return doubleArrayOf(x, y, z)
    }

    /** Geocentric ecliptic (longitude of date, latitude) of [p] in degrees. */
    fun planetEcliptic(p: Planet, jd: Double): Pair<Double, Double> {
        val t = centuries(jd)
        val pl = helio(p.name, t); val ea = helio("EARTH", t)
        val x = pl[0] - ea[0]; val y = pl[1] - ea[1]; val z = pl[2] - ea[2]
        return norm360(deg(atan2(y, x)) + 1.3969713 * t) to deg(atan2(z, hypot(x, y)))
    }

    fun planetEquatorial(p: Planet, jd: Double): Equ = planetEcliptic(p, jd).let { equatorial(it.first, it.second, jd) }

    // ---------------------------------------------------------------- Zodiac

    data class Sign(val index: Int, val name: String, val glyph: String, val degree: Int)

    private val SIGNS = listOf(
        "Aries" to "♈", "Taurus" to "♉", "Gemini" to "♊", "Cancer" to "♋", "Leo" to "♌", "Virgo" to "♍",
        "Libra" to "♎", "Scorpio" to "♏", "Sagittarius" to "♐", "Capricorn" to "♑", "Aquarius" to "♒", "Pisces" to "♓",
    )

    /** The tropical sign of an ecliptic longitude (0 deg = start of Aries) and the whole degrees into it. */
    fun sign(lonDeg: Double): Sign {
        val l = norm360(lonDeg)
        val i = floor(l / 30.0).toInt().coerceIn(0, 11)
        return Sign(i, SIGNS[i].first, SIGNS[i].second, floor(l - 30.0 * i).toInt().coerceIn(0, 29))
    }
}

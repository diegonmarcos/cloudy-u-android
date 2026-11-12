package com.diegonmarcos.clocktools

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * #1667 sunrise and sunset on device, from latitude, longitude and date alone (no network, no table): the sunrise equation
 * (Meeus' low-precision solar position, the NOAA form), with the sun's centre 0.833° below the horizon for refraction and
 * the solar disc. Good to about a minute between the polar circles; inside them it says when the sun never sets or never
 * rises that day instead of inventing a time.
 */
object Sun {
    sealed interface Day {
        /** Sunrise and sunset as epoch milliseconds (UTC instants). */
        data class Times(val rise: Long, val set: Long) : Day
        /** The sun stays above the horizon all day (midnight sun). */
        data object PolarDay : Day
        /** The sun stays below the horizon all day. */
        data object PolarNight : Day
    }

    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val DAY_MS = 86_400_000.0
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun jdToMs(jd: Double): Long = ((jd - UNIX_EPOCH_JD) * DAY_MS).roundToLong()

    /**
     * The sun's day at [lat] / [lon] (degrees, east and north positive) on the civil [date] whose noon is nearest the
     * place's solar noon. Latitudes are clamped to ±90 and longitudes wrapped, so no input throws.
     */
    fun day(date: LocalDate, lat: Double, lon: Double): Day {
        val phi = rad(lat.coerceIn(-90.0, 90.0))
        val l = (lon + 180.0).mod(360.0) - 180.0
        // Days since J2000.0 of this date's noon (UTC), then the mean solar noon at this longitude.
        val n = date.toEpochDay() + UNIX_EPOCH_JD + 0.5 - J2000 + 0.0008
        val jStar = Math.round(n) - l / 360.0
        val m = 357.5291 + 0.98560028 * jStar
        val mr = rad(m)
        val c = 1.9148 * sin(mr) + 0.0200 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = rad(m + c + 180.0 + 102.9372)
        val transit = J2000 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(rad(23.4397)))
        val cosW = (sin(rad(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosW < -1.0) return Day.PolarDay
        // NaN (0/0, unreachable for a clamped latitude) reads as night rather than throwing in acos / roundToLong.
        if (cosW.isNaN() || cosW > 1.0) return Day.PolarNight
        val w = deg(acos(cosW))
        return Day.Times(jdToMs(transit - w / 360.0), jdToMs(transit + w / 360.0))
    }
}

package com.diegonmarcos.superapp.daytimelines

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * #1907 the Persistent Weather card's rows UNDER the graphic: seven rows, two columns, nothing else. PURE (no Android):
 * the labels are declared data ([LAYOUT], the one place that says what each row is called and in what order), the values
 * come from the day model (sun, moon, signs), the MET hours (temperature, humidity, wind, precipitation, UV) and the
 * data status (last update). A missing value is an em dash ([EM]), never a crash.
 */
object WeatherRows {
    const val EM = "—"

    data class Cell(val label: String, val value: String)
    data class Row(val id: String, val left: Cell, val right: Cell)

    /** Declared layout: row id to (left label, right label). Order = render order. Pinned by WeatherRowsTest. */
    val LAYOUT: List<Triple<String, String, String>> = listOf(
        Triple("sun_moon", "Sun: Dawn / Sunrise / Sunset / Dusk", "Moon: Rise / Set"),
        Triple("temp", "Temp: min / max", "Feels like: min / max"),
        Triple("humid_wind", "Humidity: min / max", "Wind: min / max"),
        Triple("precip_uv", "Precipitation: probability / amount", "UV index"),
        Triple("pressure_dew", "Pressure: min / max", "Dew point: min~max (comfort)"),
        Triple("phase_zodiac", "Moon phase", "Sun zodiac / Moon zodiac"),
        Triple("update_place", "Last update: time", "Location"),
    )

    /** #1922 dew point comfort bands: (lower bound in C, inclusive; label), ascending and contiguous. The category comes from the day's MAX dew point. */
    val DEW_BANDS: List<Pair<Double, String>> = listOf(
        Double.NEGATIVE_INFINITY to "Dry and crisp",
        10.0 to "Comfortable",
        16.0 to "Noticeably humid",
        21.0 to "Muggy and oppressive",
    )

    fun dewCategory(maxDewC: Double): String = DEW_BANDS.last { maxDewC >= it.first }.second

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Steadman apparent temperature (Australian BoM), shade: T + 0.33 e - 0.70 ws - 4.00; null without humidity and wind. */
    fun feelsLike(tC: Double, humidityPct: Double?, windMs: Double?): Double? {
        if (humidityPct == null || windMs == null) return null
        val e = humidityPct / 100.0 * 6.105 * exp(17.27 * tC / (237.7 + tC))
        return tC + 0.33 * e - 0.70 * windMs - 4.00
    }

    /** Magnus-Tetens dew point (Alduchov-Eskridge a = 17.625, b = 243.04) (C) from temperature and relative humidity; null without humidity. */
    fun dewPoint(tC: Double, humidityPct: Double?): Double? {
        if (humidityPct == null || humidityPct <= 0.0) return null
        val g = ln(humidityPct / 100.0) + 17.625 * tC / (243.04 + tC)
        return 243.04 * g / (17.625 - g)
    }

    private fun n(v: Double?, f: String = "%.0f") = v?.let { String.format(Locale.US, f, it) }
    private fun pair(a: String?, b: String?) = "${a ?: EM} / ${b ?: EM}"

    /** #1922 the notification's expanded text: the SAME [rows], one line per row (single source with the in-app card). */
    fun notificationText(rows: List<Row>): String =
        rows.joinToString("\n") { "${it.left.label}: ${it.left.value}  |  ${it.right.label}: ${it.right.value}" }

    /** [snap] null (nothing loaded yet) gives all seven rows with dashes. */
    fun rows(snap: DayRepo.Snapshot?): List<Row> {
        val m = snap?.model
        val zone = m?.let { runCatching { ZoneId.of(it.zoneId) }.getOrNull() } ?: ZoneId.systemDefault()
        fun hm(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(HHMM) }
        val today = if (m == null || snap == null) emptyList()
        else snap.hours.filter { it.epochHour * 3_600_000L in m.dayStartMs until m.dayEndMs }
        val temps = today.map { it.tempC }
        val feels = today.mapNotNull { feelsLike(it.tempC, it.humidityPct, it.windMs) }
        val hum = today.mapNotNull { it.humidityPct }
        val wind = today.mapNotNull { it.windMs?.times(3.6) }
        val prob = today.mapNotNull { it.precipPct }
        val mm = today.mapNotNull { it.precipMm }
        val uv = today.mapNotNull { it.uv }
        val press = today.mapNotNull { it.pressureHpa }
        val dew = today.mapNotNull { it.dewC ?: dewPoint(it.tempC, it.humidityPct) }
        val sun = m?.sun
        val moon = m?.moon
        val sunSign = m?.sky?.firstOrNull { it.id == "sun" }?.sign
        val ageMs = snap?.status?.takeIf { it.kind != DataStatus.Kind.NONE }?.ageMs
        val values: Map<String, Pair<String, String>> = mapOf(
            "sun_moon" to Pair(
                listOf(sun?.dawn, sun?.sunrise, sun?.sunset, sun?.dusk).joinToString(" / ") { hm(it) ?: EM },
                pair(hm(moon?.riseMs), hm(moon?.setMs))),
            "temp" to Pair(pair(n(temps.minOrNull())?.plus("°"), n(temps.maxOrNull())?.plus("°")),
                pair(n(feels.minOrNull())?.plus("°"), n(feels.maxOrNull())?.plus("°"))),
            "humid_wind" to Pair(pair(n(hum.minOrNull())?.plus("%"), n(hum.maxOrNull())?.plus("%")),
                pair(n(wind.minOrNull())?.plus(" km/h"), n(wind.maxOrNull())?.plus(" km/h"))),
            "precip_uv" to Pair(pair(n(prob.maxOrNull())?.plus("%"), n(mm.takeIf { it.isNotEmpty() }?.sum(), "%.1f")?.plus(" mm")),
                n(uv.maxOrNull(), "%.1f") ?: EM),
            "pressure_dew" to Pair(pair(n(press.minOrNull())?.plus(" hPa"), n(press.maxOrNull())?.plus(" hPa")),
                dew.takeIf { it.isNotEmpty() }?.let { "${n(it.min())}°~${n(it.max())}° (${dewCategory(it.max())})" } ?: EM),
            "phase_zodiac" to Pair(moon?.phase?.let { "${it.name} ${(it.illumination * 100).roundToInt()}%" } ?: EM,
                pair(sunSign?.let { "${it.glyph} ${it.name}" }, moon?.sign?.let { "${it.glyph} ${it.name}" })),
            "update_place" to Pair(if (snap == null || ageMs == null) EM else hm(snap.nowMs - ageMs) ?: EM,
                m?.place?.takeIf { it.isNotBlank() } ?: EM),
        )
        return LAYOUT.map { (id, l, r) ->
            val v = values.getValue(id)
            Row(id, Cell(l, v.first), Cell(r, v.second))
        }
    }
}

package com.diegonmarcos.superapp.daytimelines

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * #1907 the Persistent Weather card's rows UNDER the graphic: six rows, two columns, nothing else. PURE (no Android):
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
        Triple("temp", "Temp: max / min", "Feels like: max / min"),
        Triple("humid_wind", "Humidity: max / min", "Wind: max / min"),
        Triple("precip_uv", "Precipitation: probability / amount", "UV index"),
        Triple("phase_zodiac", "Moon phase", "Sun zodiac / Moon zodiac"),
        Triple("update_place", "Last update: time", "Location"),
    )

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Steadman apparent temperature (Australian BoM), shade: T + 0.33 e - 0.70 ws - 4.00; null without humidity and wind. */
    fun feelsLike(tC: Double, humidityPct: Double?, windMs: Double?): Double? {
        if (humidityPct == null || windMs == null) return null
        val e = humidityPct / 100.0 * 6.105 * exp(17.27 * tC / (237.7 + tC))
        return tC + 0.33 * e - 0.70 * windMs - 4.00
    }

    private fun n(v: Double?, f: String = "%.0f") = v?.let { String.format(Locale.US, f, it) }
    private fun pair(a: String?, b: String?) = "${a ?: EM} / ${b ?: EM}"

    /** [snap] null (nothing loaded yet) gives all six rows with dashes. */
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
        val sun = m?.sun
        val moon = m?.moon
        val sunSign = m?.sky?.firstOrNull { it.id == "sun" }?.sign
        val ageMs = snap?.status?.takeIf { it.kind != DataStatus.Kind.NONE }?.ageMs
        val values: Map<String, Pair<String, String>> = mapOf(
            "sun_moon" to Pair(
                listOf(sun?.dawn, sun?.sunrise, sun?.sunset, sun?.dusk).joinToString(" / ") { hm(it) ?: EM },
                pair(hm(moon?.riseMs), hm(moon?.setMs))),
            "temp" to Pair(pair(n(temps.maxOrNull())?.plus("°"), n(temps.minOrNull())?.plus("°")),
                pair(n(feels.maxOrNull())?.plus("°"), n(feels.minOrNull())?.plus("°"))),
            "humid_wind" to Pair(pair(n(hum.maxOrNull())?.plus("%"), n(hum.minOrNull())?.plus("%")),
                pair(n(wind.maxOrNull())?.plus(" km/h"), n(wind.minOrNull())?.plus(" km/h"))),
            "precip_uv" to Pair(pair(n(prob.maxOrNull())?.plus("%"), n(mm.takeIf { it.isNotEmpty() }?.sum(), "%.1f")?.plus(" mm")),
                n(uv.maxOrNull(), "%.1f") ?: EM),
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

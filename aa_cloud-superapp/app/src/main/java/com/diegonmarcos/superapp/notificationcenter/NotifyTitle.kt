package com.diegonmarcos.superapp.notificationcenter

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The notification centre's title line: `Dublin · Sat 8 Nov 2026 · 14:22` (city · weekday, date · 24 h; no city yet = `Sat 8 Nov 2026 · 14:22`). */
object NotifyTitle {
    private val FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.US)

    fun format(city: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = FMT.format(Instant.ofEpochMilli(nowMs).atZone(zone))
        val c = city?.trim().orEmpty()
        return if (c.isEmpty() || PlaceName.isCoords(c)) dt else "$c · $dt"
    }

    /** The city the Weather badge resolved (its saved forecast's place), or null. */
    fun cityOf(ctx: android.content.Context): String? =
        runCatching { WeatherFixStore(ctx).loadForecast()?.place }.getOrNull()?.takeIf { it.isNotBlank() && !PlaceName.isCoords(it) }
}

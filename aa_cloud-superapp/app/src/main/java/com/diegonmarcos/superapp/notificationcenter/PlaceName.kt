package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * lat/lon -> the CITY NAME for the Weather badge (and the notification title that reads it). Locality, then
 * subAdminArea, adminArea, countryName; if the geocoder has nothing, a short "lat, lon" (2 decimals, never a bare
 * number). The answer is cached per ~0.05 degree cell so a tick does not re-geocode.
 */
object PlaceName {

    /** Pure: the first non-blank of locality / subAdminArea / adminArea / countryName. */
    fun pick(locality: String?, subAdmin: String?, admin: String?, country: String?): String? =
        listOf(locality, subAdmin, admin, country).firstOrNull { !it.isNullOrBlank() }?.trim()

    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.2f, %.2f", lat, lon)

    /** A "lat, lon" fallback (or a bare number, as an older build stored) is not a city. */
    fun isCoords(s: String): Boolean = Regex("^-?\\d+(\\.\\d+)?(\\s*,\\s*-?\\d+(\\.\\d+)?)?$").matches(s.trim())

    /** Cache cell key. */
    fun cell(lat: Double, lon: Double): String = String.format(Locale.US, "%.2f,%.2f", Math.round(lat * 20) / 20.0, Math.round(lon * 20) / 20.0)

    interface Source { fun lookup(lat: Double, lon: Double): String? }
    interface Cache { fun get(cell: String): String?; fun put(cell: String, city: String) }

    /** Pure: cached city, else the source's, else the coordinates fallback. Only a real city is cached. */
    fun resolve(lat: Double, lon: Double, source: Source, cache: Cache): String {
        val cell = cell(lat, lon)
        cache.get(cell)?.let { return it }
        val city = runCatching { source.lookup(lat, lon) }.getOrNull()?.takeIf { it.isNotBlank() && !isCoords(it) }
        if (city != null) { cache.put(cell, city); return city }
        return coords(lat, lon)
    }

    fun resolve(ctx: Context, lat: Double, lon: Double): String =
        resolve(lat, lon, AndroidSource(ctx), PrefsCache(ctx))

    class PrefsCache(ctx: Context) : Cache {
        private val p = ctx.applicationContext.getSharedPreferences("weather_place_cache", Context.MODE_PRIVATE)
        override fun get(cell: String): String? = p.getString(cell, null)
        override fun put(cell: String, city: String) { p.edit().putString(cell, city).apply() }
    }

    class AndroidSource(private val ctx: Context) : Source {
        override fun lookup(lat: Double, lon: Double): String? {
            if (!Geocoder.isPresent()) return null
            val g = Geocoder(ctx, Locale.getDefault())
            val a: Address? = if (Build.VERSION.SDK_INT >= 33) {
                val out = arrayOfNulls<Address>(1)
                val latch = CountDownLatch(1)
                g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { out[0] = addresses.firstOrNull(); latch.countDown() }
                    override fun onError(errorMessage: String?) { latch.countDown() }
                })
                latch.await(8, TimeUnit.SECONDS)
                out[0]
            } else {
                @Suppress("DEPRECATION") g.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
            return a?.let { pick(it.locality, it.subAdminArea, it.adminArea, it.countryName) }
        }
    }
}

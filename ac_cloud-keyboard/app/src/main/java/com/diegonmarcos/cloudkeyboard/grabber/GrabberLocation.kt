package com.diegonmarcos.cloudkeyboard.grabber

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.locationclient.NavLocationClient
import java.util.Locale

/**
 * #1793 "from your location" for the Place card. The keyboard never starts a location fix and holds no location permission: Cloud
 * Nav is the fleet's one location owner (#1770, cloud-android-location-owner-guard), it asks Android for the coarse permission
 * first and the precise one only if you allow it, and it shares its last fix over the fleet IPC door (libs:location-client). The
 * card asks it only when a place is on screen, i.e. the first time the card needs the position. Without a fix the card shows
 * the place and its map, no distances, and the reason with a button to Cloud Nav.
 */
object GrabberLocation {
    /** How old Cloud Nav's fix may be for a distance (a travel estimate tolerates a stale fix better than a map dot). */
    const val MAX_AGE_MS = 24L * 3_600_000L

    /** Debug builds only (GrabberActivity reads it from the emulator walk's extras): a fixed position, labelled MOCK on the card. */
    @Volatile var mock: Pair<Double, Double>? = null

    sealed interface Where {
        data class At(val lat: Double, val lon: Double, val note: String) : Where
        data class Unknown(val why: String, val navMissing: Boolean) : Where
    }

    private fun age(ms: Long): String = when {
        ms < 90_000 -> "just now"
        ms < 90 * 60_000 -> "${ms / 60_000} min old"
        else -> "${ms / 3_600_000} h old"
    }

    /** Blocking (one IPC call to Cloud Nav): call off the main thread. */
    fun where(ctx: Context): Where {
        mock?.let { (lat, lon) -> return Where.At(lat, lon, "MOCK LOCATION · DEBUG BUILD") }
        val now = System.currentTimeMillis()
        return when (val a = NavLocationClient.ask(ctx, now)) {
            is NavLocationClient.Answer.Ok -> {
                val f = a.fix
                if (!NavLocationClient.freshEnough(f, now, now, MAX_AGE_MS)) Where.Unknown("Cloud Nav's last location is ${age(f.ageMs)}: open Cloud Nav so it updates", false)
                else Where.At(f.lat, f.lon, "CLOUD NAV · ${age(f.ageMs)}" + (f.accuracyM?.let { String.format(Locale.ROOT, " · ±%.0f m", it) } ?: ""))
            }
            is NavLocationClient.Answer.Unavailable -> Where.Unknown(a.detail, a.why == NavLocationClient.Why.NOT_INSTALLED)
        }
    }

    /** Cloud Nav's own window, where its location permission and Tracker live; false when it is not installed. */
    fun openNav(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(NavLocationClient.PKG) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

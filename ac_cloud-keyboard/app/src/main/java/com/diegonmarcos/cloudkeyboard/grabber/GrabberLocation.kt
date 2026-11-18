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
    /** Debug builds only (GrabberActivity reads it from the emulator walk's extras): a fixed position, labelled MOCK on the card. */
    @Volatile var mock: Pair<Double, Double>? = null

    sealed interface Where {
        /** A fix: [note] is the card's line ("from your position 12 min ago · CLOUD NAV · ±20 m"); [stale] over 30 min. */
        data class At(val lat: Double, val lon: Double, val note: String, val ageMs: Long = 0L, val ts: Long = 0L, val stale: Boolean = false) : Where
        data class Unknown(val why: String, val navMissing: Boolean) : Where
    }

    /** Blocking (one IPC call to Cloud Nav, which answers from its own store): call off the main thread. */
    fun where(ctx: Context): Where {
        mock?.let { (lat, lon) -> return Where.At(lat, lon, FixAge.text(0) + " · MOCK LOCATION · DEBUG BUILD", 0L, System.currentTimeMillis(), false) }
        val now = System.currentTimeMillis()
        return when (val a = NavLocationClient.ask(ctx, now)) {
            is NavLocationClient.Answer.Ok -> {
                val f = a.fix
                val acc = f.accuracyM?.let { String.format(Locale.ROOT, " · ±%.0f m", it) } ?: ""
                Where.At(f.lat, f.lon, FixAge.text(f.ageMs) + " · CLOUD NAV" + acc, f.ageMs, f.timeMs, FixAge.stale(f.ageMs))
            }
            is NavLocationClient.Answer.Unavailable -> Where.Unknown(a.detail, a.why == NavLocationClient.Why.NOT_INSTALLED)
        }
    }

    /**
     * "Open Cloud Nav to refresh": Cloud Nav's own window, where its location permission and Tracker live and where it takes a
     * fresh fix while it is on screen (it has no separate take-a-fix entry); false when it is not installed. The card watches
     * for the newer fix and recomputes by itself.
     */
    fun openNav(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(NavLocationClient.PKG) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

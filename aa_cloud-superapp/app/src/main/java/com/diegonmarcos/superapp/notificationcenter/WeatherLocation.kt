package com.diegonmarcos.superapp.notificationcenter

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where the Weather badge is, found the cheapest way and never lost (#1654).
 *
 * COARSE ONLY, ONE FIX PER [REFRESH_MS] (4 h). ORDER: (1) the persisted fix
 * while it is younger than [FRESH_MS] (= 4 h, nothing is asked), (2) the
 * system's cached NETWORK (Wi-Fi + cell) fix when fresh, (3) ONE single-shot
 * request to the NETWORK provider. NEVER GPS, never the fine permission, never
 * the passive or fused high-accuracy path, never requestLocationUpdates, and no
 * listener is left subscribed: the request is a one-shot getCurrentLocation
 * that is cancelled on timeout. If it fails the newest STALE fix we ever had
 * is used: a forecast for where the phone was hours ago beats an empty card.
 *
 * NEVER LOST: every fix found is written to [FixStore] and only a NEWER fix
 * replaces it; a failed refresh writes nothing. When there is truly no fix, the
 * [Result.None] carries the actual [Reason].
 *
 * Pure: the platform sits behind [Probe], the storage behind [FixStore], so the
 * order, the persistence rule and the fallback are plain JVM tests.
 */
object WeatherLocation {

    const val NETWORK = "network"
    const val STORED = "stored"

    /** Location and weather are refreshed at most once per this (4 h). */
    const val REFRESH_MS = 4 * 60 * 60_000L

    /** A fix younger than this is used as is - no radio is woken for a forecast. */
    const val FRESH_MS = REFRESH_MS
    const val NETWORK_TIMEOUT_MS = 10_000L

    /** Cached fixes the system already holds: the coarse network provider only. */
    val PEEK_ORDER = listOf(NETWORK)

    /** Providers asked for a NEW fix: the coarse network provider only, never GPS. */
    val REQUEST_ORDER = listOf(NETWORK)

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long, val source: String) {
        val valid: Boolean
            get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0) && timeMs > 0
    }

    enum class Reason { PERMISSION, LOCATION_OFF, BACKGROUND, NO_FIX, SEARCHING }

    sealed interface Result {
        /** [fresh] false = the newest fix we have is older than [FRESH_MS]. */
        data class Found(val fix: Fix, val fresh: Boolean) : Result
        data class None(val reason: Reason) : Result
    }

    data class Env(val coarse: Boolean, val fine: Boolean, val background: Boolean, val anyProviderOn: Boolean)

    interface Probe {
        fun enabled(provider: String): Boolean
        /** The system's cached fix; costs nothing. */
        fun lastKnown(provider: String): Fix?
        /** A new fix, blocking up to [timeoutMs]; costs radio. */
        fun request(provider: String, timeoutMs: Long): Fix?
    }

    interface FixStore {
        fun load(): Fix?
        /** Keeps the newest: an older, equal or invalid fix is ignored. True when stored. */
        fun save(fix: Fix): Boolean
    }

    /** Why there is no fix; the card says this, never a vague wait. */
    fun reasonFor(env: Env): Reason = when {
        !env.coarse && !env.fine -> Reason.PERMISSION
        !env.anyProviderOn -> Reason.LOCATION_OFF
        !env.background -> Reason.BACKGROUND
        else -> Reason.NO_FIX
    }

    private fun allowed(env: Env, @Suppress("UNUSED_PARAMETER") provider: String) = env.coarse || env.fine

    fun resolve(now: Long, env: Env, probe: Probe, store: FixStore): Result {
        val stored = store.load()
        if (!env.coarse && !env.fine) {
            // Permission revoked: the persisted fix still answers.
            return stored?.let { Result.Found(it, fresh(now, it)) } ?: Result.None(Reason.PERMISSION)
        }
        if (stored != null && fresh(now, stored)) return Result.Found(stored, true)

        val usable = PEEK_ORDER.filter { allowed(env, it) && probe.enabled(it) }
        for (p in usable) {
            val fix = probe.lastKnown(p)
            if (fix != null && fix.valid && fresh(now, fix)) return keep(fix, store, true)
        }
        for (p in REQUEST_ORDER.filter { it in usable }) {
            val fix = probe.request(p, NETWORK_TIMEOUT_MS)
            if (fix != null && fix.valid) return keep(fix, store, true)
        }
        // Nothing fresh: the newest stale fix (persisted or cached by the system) beats an empty card.
        val newest = (listOfNotNull(stored) + usable.mapNotNull { runCatching { probe.lastKnown(it) }.getOrNull() }
            .filter { it.valid }).maxByOrNull { it.timeMs }
        if (newest != null) return keep(newest, store, fresh(now, newest))
        return Result.None(reasonFor(env))
    }

    private fun fresh(now: Long, f: Fix) = now - f.timeMs in 0..FRESH_MS

    private fun keep(fix: Fix, store: FixStore, fresh: Boolean): Result {
        store.save(fix)
        return Result.Found(store.load() ?: fix, fresh)
    }

    /** [com.diegonmarcos.superapp.perms.LocationUse] call-site id of the Weather card. */
    const val SITE = "notificationcenter.WeatherLocation (Weather card, 4 h)"

    /** Platform side of [Probe]. */
    class AndroidProbe(private val ctx: Context) : Probe {
        private val lm get() = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        override fun enabled(provider: String): Boolean =
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        override fun lastKnown(provider: String): Fix? {
            // #1666 noted in the app's location-use ledger (X-Ray App ▸ Location use); runs on the 4 h cadence only (#1654).
            com.diegonmarcos.superapp.perms.LocationUse.note(ctx, SITE, "LocationManager.getLastKnownLocation ($provider)", com.diegonmarcos.superapp.perms.LocationUse.Trigger.WEATHER)
            return runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.toFix(provider)
        }

        @SuppressLint("MissingPermission")
        override fun request(provider: String, timeoutMs: Long): Fix? = runCatching {
            com.diegonmarcos.superapp.perms.LocationUse.note(ctx, SITE, "LocationManagerCompat.getCurrentLocation ($provider, one shot)", com.diegonmarcos.superapp.perms.LocationUse.Trigger.WEATHER)
            val got = arrayOfNulls<Location>(1)
            val latch = CountDownLatch(1)
            val cancel = CancellationSignal()
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, { it.run() }, Consumer<Location?> {
                got[0] = it; latch.countDown()
            })
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
            got[0]?.toFix(provider)
        }.getOrNull()

        private fun Location.toFix(provider: String) = Fix(
            latitude, longitude, if (hasAccuracy()) accuracy else -1f,
            if (time > 0) time else System.currentTimeMillis(), provider)
    }

    fun env(ctx: Context): Env {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val on = runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(true)
        return Env(
            coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION),
            fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION),
            background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            anyProviderOn = on,
        )
    }
}

/**
 * The last good fix and the last good forecast, in SharedPreferences: survives
 * the process being killed and a reboot. Nothing here ever deletes either one;
 * a fix is replaced only by a newer one.
 */
class WeatherFixStore(context: Context) : WeatherLocation.FixStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): WeatherLocation.Fix? {
        if (!prefs.contains(K_TIME)) return null
        val f = WeatherLocation.Fix(
            Double.fromBits(prefs.getLong(K_LAT, Double.NaN.toRawBits())),
            Double.fromBits(prefs.getLong(K_LON, Double.NaN.toRawBits())),
            prefs.getFloat(K_ACC, -1f), prefs.getLong(K_TIME, 0L), prefs.getString(K_SRC, "") ?: "")
        return f.takeIf { it.valid }
    }

    @Synchronized
    override fun save(fix: WeatherLocation.Fix): Boolean {
        if (!fix.valid) return false
        val old = load()
        if (old != null && fix.timeMs <= old.timeMs) return false
        // commit(), not apply(): callers are on a worker thread and a process kill right after must not lose it.
        return prefs.edit().putLong(K_LAT, fix.lat.toRawBits()).putLong(K_LON, fix.lon.toRawBits())
            .putFloat(K_ACC, fix.accuracyM).putLong(K_TIME, fix.timeMs).putString(K_SRC, fix.source).commit()
    }

    data class Cached(val place: String, val lat: Double, val lon: Double, val body: String, val savedMs: Long)

    fun saveForecast(c: Cached) {
        prefs.edit().putString(K_PLACE, c.place).putLong(K_FLAT, c.lat.toRawBits()).putLong(K_FLON, c.lon.toRawBits())
            .putString(K_BODY, c.body).putLong(K_BODY_MS, c.savedMs).commit()
    }

    fun loadForecast(): Cached? {
        val body = prefs.getString(K_BODY, null) ?: return null
        return Cached(prefs.getString(K_PLACE, "") ?: "",
            Double.fromBits(prefs.getLong(K_FLAT, 0L)), Double.fromBits(prefs.getLong(K_FLON, 0L)),
            body, prefs.getLong(K_BODY_MS, 0L))
    }

    companion object {
        const val PREFS = "weather_last_fix"
        private const val K_LAT = "lat"; private const val K_LON = "lon"; private const val K_ACC = "acc"
        private const val K_TIME = "time"; private const val K_SRC = "src"
        private const val K_PLACE = "place"; private const val K_FLAT = "f_lat"; private const val K_FLON = "f_lon"
        private const val K_BODY = "body"; private const val K_BODY_MS = "body_ms"
    }
}

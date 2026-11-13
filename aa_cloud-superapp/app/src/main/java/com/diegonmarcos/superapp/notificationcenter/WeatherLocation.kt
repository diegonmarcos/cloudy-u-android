package com.diegonmarcos.superapp.notificationcenter

import android.content.Context

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
 * #1690 SOURCE: Cloud Nav, the fleet's one location owner, over the fleet IPC door ([resolveFromNav], [NavProbe]).
 * The SuperApp itself makes NO location-class call here any more; the provider order above is the rule the
 * Cloud Nav answer goes through (one ask per 4 h, persisted, newest stale fix kept).
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

    /** NAV_UNAVAILABLE (#1690): Cloud Nav, the fleet's location owner, is not installed, did not answer or refused. */
    enum class Reason { PERMISSION, LOCATION_OFF, BACKGROUND, NO_FIX, SEARCHING, NAV_UNAVAILABLE }

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

    /** Every source of a fix Cloud Nav hands over is tagged with this prefix ("cloud-nav · gps"). */
    const val NAV = "cloud-nav"

    /**
     * #1690 the position comes from Cloud Nav (fleet IPC `tracker/location`), never from this app's own location
     * access. Same order and persistence rule as [resolve] (the persisted fix while fresh, else Cloud Nav's fix, else
     * the newest stale one); [ask] runs at most once and only when the persisted fix is not fresh. When there is no
     * fix at all and Cloud Nav is missing / silent / refusing, the reason is [Reason.NAV_UNAVAILABLE] - never a
     * fallback that tracks inside the SuperApp.
     */
    fun resolveFromNav(now: Long, store: FixStore, ask: () -> com.diegonmarcos.superapp.location.NavLocation.Answer): Result {
        val probe = NavProbe(ask)
        val r = resolve(now, Env(coarse = true, fine = false, background = true, anyProviderOn = true), probe, store)
        val a = probe.answer
        if (r is Result.None && a is com.diegonmarcos.superapp.location.NavLocation.Answer.Unavailable &&
            a.why != com.diegonmarcos.superapp.location.NavLocation.Why.NO_FIX) return Result.None(Reason.NAV_UNAVAILABLE)
        return r
    }

    /** Pure [Probe] over Cloud Nav's answer: asked lazily, once. */
    class NavProbe(private val ask: () -> com.diegonmarcos.superapp.location.NavLocation.Answer) : Probe {
        var answer: com.diegonmarcos.superapp.location.NavLocation.Answer? = null
            private set
        private fun fix(): Fix? {
            val a = answer ?: runCatching(ask).getOrElse {
                com.diegonmarcos.superapp.location.NavLocation.Answer.Unavailable(
                    com.diegonmarcos.superapp.location.NavLocation.Why.NOT_RUNNING, it.message ?: "Cloud Nav did not answer")
            }.also { answer = it }
            val f = (a as? com.diegonmarcos.superapp.location.NavLocation.Answer.Ok)?.fix ?: return null
            return Fix(f.lat, f.lon, f.accuracyM ?: -1f, f.timeMs, "$NAV · ${f.source}")
        }
        override fun enabled(provider: String): Boolean = fix() != null
        override fun lastKnown(provider: String): Fix? = fix()
        override fun request(provider: String, timeoutMs: Long): Fix? = fix()
    }

    /** [com.diegonmarcos.superapp.perms.LocationUse] call-site id of the Weather card. */
    const val SITE = "notificationcenter.WeatherLocation (Weather card, 4 h)"

    // #1690 the platform probe (LocationManager getLastKnownLocation + getCurrentLocation) and env() are gone: the
    // SuperApp makes no location-class call for Weather; [NavProbe] reads Cloud Nav's fix over the fleet IPC door.
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

    /** #1734 the day-timelines card's merged hourly cache (temperature + symbols, 48 h), kept beside the forecast in the same store. */
    fun saveTimelines(json: String) { prefs.edit().putString(K_TL, json).commit() }

    fun loadTimelines(): String? = prefs.getString(K_TL, null)

    companion object {
        const val PREFS = "weather_last_fix"
        private const val K_TL = "timelines_cache"
        private const val K_LAT = "lat"; private const val K_LON = "lon"; private const val K_ACC = "acc"
        private const val K_TIME = "time"; private const val K_SRC = "src"
        private const val K_PLACE = "place"; private const val K_FLAT = "f_lat"; private const val K_FLON = "f_lon"
        private const val K_BODY = "body"; private const val K_BODY_MS = "body_ms"
    }
}

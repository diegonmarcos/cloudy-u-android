package com.diegonmarcos.superapp.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * The location facts behind [GpsFix], never throwing; what a permission gates reads null.
 *
 * Battery contract:
 *   [StripWatch]  (the strip's GPS icon, always on while the strip is) asks for NOTHING to be
 *                 computed: the location switch, the provider broadcasts and the system's cached
 *                 fix. It holds NO location subscription (#1654). It never wakes GNSS.
 *   [LiveSession] (the popup's GPS section) requests NO location (#1654): it shows the cached fix
 *                 and listens, read-only, to the satellite status other apps cause; it runs only
 *                 while that section is on screen, capped at [LiveSession.MAX_MS].
 */
object GpsProbe {

    fun lm(ctx: Context): LocationManager? = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun fineGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun coarseGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun locationOn(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) lm(ctx)?.isLocationEnabled == true
        else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
    }.getOrDefault(false)

    /** Settings.Secure.LOCATION_MODE (0 off, 1 device only, 2 battery saving, 3 high accuracy); Android 9+ reports only off / 3. */
    fun mode(ctx: Context): Int? = runCatching {
        @Suppress("DEPRECATION") Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE)
    }.getOrNull()

    val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER)

    /** "gps ✓ · network ✓ · fused ✓ · passive ✓" (✗ disabled, absent providers left out). */
    fun providersLine(ctx: Context): String {
        val m = lm(ctx) ?: return "Providers: —"
        val all = runCatching { m.allProviders }.getOrDefault(emptyList())
        val parts = PROVIDERS.filter { it in all }.map { p ->
            p + if (runCatching { m.isProviderEnabled(p) }.getOrDefault(false)) " ✓" else " ✗"
        }
        return "Providers: " + if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }

    /** Wi-Fi / Bluetooth scanning for location (Settings.Global), when this build lets an app read them. */
    fun scanningLine(ctx: Context): String? {
        fun g(k: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, k) == 1 }.getOrNull()
        val wifi = g("wifi_scan_always_enabled"); val ble = g("ble_scan_always_enabled")
        if (wifi == null && ble == null) return null
        return "Scanning: Wi-Fi ${MobileLink.onOff(wifi)} · Bluetooth ${MobileLink.onOff(ble)}"
    }

    fun fixOf(l: Location, nowElapsedNs: Long = SystemClock.elapsedRealtimeNanos(), usedSats: Int? = null): GpsFix.Fix {
        val age = ((nowElapsedNs - l.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        val sats = usedSats ?: runCatching { l.extras?.getInt("satellites", -1) }.getOrNull()?.takeIf { it >= 0 }
        return GpsFix.Fix(l.provider ?: "?", if (l.hasAccuracy()) l.accuracy else null, age, sats)
    }

    /** The freshest last-known fix any provider holds. No location is requested. */
    fun lastKnown(ctx: Context): Location? {
        if (!fineGranted(ctx) && !coarseGranted(ctx)) return null
        val m = lm(ctx) ?: return null
        return PROVIDERS.mapNotNull { p -> runCatching { @Suppress("MissingPermission") m.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    /** Receiver model, year and the bands it supports (API 34 lists them); [seenDual] = a second-frequency satellite was tracked live. */
    fun capabilityLines(ctx: Context, seenDual: Boolean): List<String> {
        val m = lm(ctx) ?: return emptyList()
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 28) {
            val model = runCatching { m.gnssHardwareModelName }.getOrNull()
            val year = runCatching { m.gnssYearOfHardware }.getOrNull()?.takeIf { it > 0 }
            out += "Receiver: ${model ?: "—"}" + (year?.let { " ($it)" } ?: "")
        }
        val bands = if (Build.VERSION.SDK_INT >= 34) runCatching {
            m.gnssCapabilities.gnssSignalTypes.mapNotNull { GpsFix.band(it.constellationType, it.carrierFrequencyHz) }.distinct()
        }.getOrNull() else null
        out += "Dual-frequency: " + when {
            !bands.isNullOrEmpty() -> (if (bands.any(GpsFix::isSecondFrequency)) "yes" else "no") + " (bands ${bands.joinToString(", ")})"
            seenDual -> "yes (second-frequency signals tracked now)"
            Build.VERSION.SDK_INT < 34 -> "— (the hardware's bands are reported from Android 14)"
            else -> "—"
        }
        return out
    }

    private fun broadcastFilter() = IntentFilter().apply {
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        addAction(LocationManager.MODE_CHANGED_ACTION)
    }

    /**
     * The strip's feed: [onChange] (main thread) when the location switch or a provider flips.
     * Never asks for a fix of its own.
     */
    class StripWatch(ctx: Context, private val onChange: () -> Unit) {
        private val app = ctx.applicationContext
        @Volatile var last: Location? = null
            private set
        private var started = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { last = lastKnown(app) ?: last; onChange() }
        }

        fun start() {
            if (started) return
            started = true
            last = lastKnown(app)
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(app, receiver, broadcastFilter(),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }

        fun stop() {
            if (!started) return
            started = false
            runCatching { app.unregisterReceiver(receiver) }
        }

        /** The dots now (a fix ages between events; the strip re-reads on its minute tick). */
        fun dots(locationOn: Boolean = locationOn(app)): Int = GpsFix.dots(locationOn, last?.let { fixOf(it) })
    }

    /**
     * The popup GPS section's live view: GNSS + network fixes and the satellite status, while the
     * section is on screen. [onUpdate] runs on the main thread, at most about once a second.
     */
    class LiveSession(ctx: Context, private val onUpdate: () -> Unit) {
        private val app = ctx.applicationContext
        private val main = Handler(Looper.getMainLooper())
        var running = false
            private set
        @Volatile var fix: Location? = null
            private set
        @Volatile var sats: List<GpsFix.Sat> = emptyList()
            private set
        private var lastUpdate = 0L
        private val status: GnssStatus.Callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                sats = (0 until s.satelliteCount).map { i ->
                    GpsFix.Sat(s.getConstellationType(i), s.usedInFix(i),
                        if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i).toDouble() else null)
                }
                update()
            }
        }
        private val cap = Runnable { stop() }

        fun usedSats(): Int? = sats.count { it.usedInFix }.takeIf { sats.isNotEmpty() }

        fun start() {
            if (running || !fineGranted(app) || !locationOn(app)) return
            val m = lm(app) ?: return
            running = true
            fix = fix ?: lastKnown(app)
            runCatching {
                @Suppress("MissingPermission")
                if (Build.VERSION.SDK_INT >= 30) m.registerGnssStatusCallback(app.mainExecutor, status)
                else m.registerGnssStatusCallback(status, main)
            }
            main.postDelayed(cap, MAX_MS)
            update()
        }

        fun stop() {
            if (!running) return
            running = false
            main.removeCallbacks(cap)
            main.removeCallbacks(flush); pending = false
            val m = lm(app) ?: return
            runCatching { m.unregisterGnssStatusCallback(status) }
            onUpdate()
        }

        private var pending = false
        private val flush = Runnable { pending = false; lastUpdate = SystemClock.uptimeMillis(); onUpdate() }

        /** Coalesce: at most one repaint per ~second, and the last event is never dropped. */
        private fun update() {
            if (pending) return
            pending = true
            main.postDelayed(flush, (900L - (SystemClock.uptimeMillis() - lastUpdate)).coerceAtLeast(0L))
        }

        companion object {
            /** However the popup is left open, the live GNSS request ends after this. */
            const val MAX_MS = 3 * 60_000L
        }
    }
}

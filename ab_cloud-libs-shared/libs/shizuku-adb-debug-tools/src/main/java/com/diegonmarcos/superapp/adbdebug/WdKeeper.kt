package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.core.BackgroundPolicy
import com.diegonmarcos.superapp.core.LogGate
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.perms.PermsLink
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #1467 "Keep wireless debugging on", ONE keeper for every app that has the switch (Cloud Store, SuperApp). The switch is
 * [ChannelAutoArm.enabled]: Store's own "Keep armed", SuperApp's Configs switch through its host hook. Armed, this keeps:
 *  - a small foreground service ([WdKeeperService]) so battery management does not kill the process that holds the rest;
 *  - a ContentObserver on `adb_wifi_enabled` (every change goes in the audit log, an OFF is re-armed);
 *  - a Wi-Fi-available callback (Android clears the setting on a drop or a BSSID change; the network coming back re-arms);
 *  - BOOT_COMPLETED (the boot receivers call [start]) and a 15-minute WorkManager pass as the backstop under all of them.
 * Who re-arms is [WdKeeperLeader]'s call, from each app's heartbeat claim, so two apps never fight. The write itself is
 * [HostShell.keepWirelessDebuggingOn] (WRITE_SECURE_SETTINGS), the decisions are [WdKeeperCore].
 */
object WdKeeper {
    private const val TAG = "WdKeeper"
    private const val PREFS = "wd_keeper"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val METHOD_CLAIM = "wd_keeper_claim"
    private const val KEY_ARMED = "armed"
    private const val KEY_CAN_WRITE = "can_write"
    private const val KEY_HEARTBEAT = "heartbeat"
    /** The perms catalogue feature the battery row is opened on. */
    const val FEATURE = "wireless-keeper"
    const val WSS = "android.permission.WRITE_SECURE_SETTINGS"
    /** The catalogue row (libs:perms, kind adb_only) that asks for [WSS]. */
    private const val WSS_ROW = "write_secure_settings"
    private const val KEY_AUTO_GRANT_TRIED = "auto_grant_tried"
    private const val WORK = "wd-keeper-backstop"
    private const val SETTLE_MS = 2_500L
    private const val EVENT_DELAY_MS = 3_000L
    private const val SELF_WINDOW_MS = 8_000L

    private val pool by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wd-keeper").apply { isDaemon = true } } }
    @Volatile private var hooked = false
    @Volatile private var routed = false
    @Volatile private var lastNetworkEventAt = 0L
    @Volatile private var wifiEvent: Boolean? = null
    @Volatile private var log: WdAuditLog? = null

    /** The per-tick heartbeat repeats the SAME line every pass and used to flood logcat; route it through a
     *  dedupe/rate-limit so an unchanged `tick[...]` collapses to one line a minute with a repeat count. State-change
     *  lines (re-arm wrote, auto self-grant) stay direct — they are rare and each one matters. */
    private val tickGate = LogGate(sink = { Log.i(TAG, it) })

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The switch is on AND this host may run in the background (#1502: Account never, Store on Wi-Fi with auto-update on). */
    fun armed(ctx: Context): Boolean = runCatching { ChannelAutoArm.enabled(ctx) && HostShell.mayBackground(ctx) }.getOrDefault(false)

    fun audit(ctx: Context): WdAuditLog = log ?: synchronized(this) {
        log ?: WdAuditLog(object : WdAuditLog.Store {
            override fun load() = p(ctx.applicationContext).getString("audit", null)
            override fun save(json: String) { p(ctx.applicationContext).edit().putString("audit", json).apply() }
        }).also { log = it }
    }

    /** Bring everything in line with the switch. Called from App.onCreate, the boot receivers and the switch itself. Idempotent. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!armed(app)) { stop(app); return }
        p(app).edit().putBoolean(KEY_ARMED, true).apply()
        heartbeat(app)
        hook(app)
        registerRoute(app)
        // #1513 the Perms row granted it (or it is held on resume): re-arm at once, on the 30 s floor of evaluate.
        com.diegonmarcos.superapp.perms.AdbGrant.onHeld = { c, perm -> if (perm == WSS) kick(c, "permission-held", 0) }
        WdKeeperService.start(app)
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WdKeeperWorker>(15, TimeUnit.MINUTES).build())
        }.onFailure { Log.w(TAG, "backstop not scheduled: ${it.message}") }
        kick(app, "start", 0)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        p(app).edit().putBoolean(KEY_ARMED, false).apply()
        WdKeeperService.stop(app)
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK) }
    }

    fun kick(ctx: Context, trigger: String, delayMs: Long = EVENT_DELAY_MS) {
        val app = ctx.applicationContext
        runCatching { pool.schedule(Runnable { runCatching { evaluate(app, trigger) }.onFailure { Log.w(TAG, "$trigger: ${it.message}") } }, delayMs, TimeUnit.MILLISECONDS) }
    }

    /** The owner turned Wireless debugging off from this app's own screen: leave it off until it is seen on again. */
    fun noteOwnerOff(ctx: Context) { p(ctx.applicationContext).edit().putBoolean("owner_off", true).apply() }

    @Synchronized
    fun evaluate(app: Context, trigger: String): WdKeeperCore.Action {
        heartbeat(app)
        val now = System.currentTimeMillis()
        val autoGranted = autoSelfGrant(app)
        val on = WirelessDebugging.isOn(app)
        if (on) p(app).edit().putBoolean("owner_off", false).apply()
        val facts = WdKeeperCore.Facts(armed(app), HostShell.canWriteSecureSettings(app), on, wifiConnected(app), leader = true,
            ownerOff = p(app).getBoolean("owner_off", false))
        var action = WdKeeperCore.decide(facts)
        // Peers are asked only when a write is about to happen: a binder call into the other app starts its process.
        if (action == WdKeeperCore.Action.REARM && !WdKeeperLeader.isLeader(app.packageName, claims(app, now), now))
            action = WdKeeperCore.decide(facts.copy(leader = false))
        // #1502 one re-arm write per 30 s: a setting that bounces straight back off (no network for adbd) made observer -> evaluate -> write -> observer a ~6 s loop.
        if (action == WdKeeperCore.Action.REARM && now - p(app).getLong("self_write_at", 0L) < com.diegonmarcos.superapp.core.BackgroundPolicy.RETRY_MIN_MS) {
            tickGate.log("tick[$trigger]: re-arm rate-limited")
            return WdKeeperCore.Action.NOTHING
        }
        if (action == WdKeeperCore.Action.REARM) {
            p(app).edit().putLong("self_write_at", now).putString("self_write_by", if (autoGranted) com.diegonmarcos.superapp.perms.AdbGrant.BY_SELF else app.packageName).apply()
            val wrote = HostShell.keepWirelessDebuggingOn(app)
            Thread.sleep(SETTLE_MS)
            val after = WirelessDebugging.isOn(app)
            Log.i(TAG, "re-arm[$trigger]: wrote=$wrote on=$after")
            // The observer logs a change that landed; one that did not is logged here, so the log never says "ok" for nothing.
            if (!after) audit(app).add(WdAuditLog.Entry(System.currentTimeMillis(), false, facts.wifiConnected, "re-arm rejected ($trigger)", app.packageName))
        } else if (action != WdKeeperCore.Action.NOTHING) tickGate.log("tick[$trigger]: $action")
        return action
    }

    /**
     * #1513 Keeper on, permission missing, channel armed: grant it ONCE, here, no loop (the flag stays until the permission is held,
     * so a later revoke is asked once again). True when this call got it, so the re-arm that follows is logged as the self-grant's.
     */
    private fun autoSelfGrant(app: Context): Boolean {
        val held = HostShell.canWriteSecureSettings(app)
        if (held) { if (p(app).getBoolean(KEY_AUTO_GRANT_TRIED, false)) p(app).edit().putBoolean(KEY_AUTO_GRANT_TRIED, false).apply(); return false }
        if (p(app).getBoolean(KEY_AUTO_GRANT_TRIED, false) || !armed(app) || !com.diegonmarcos.superapp.perms.AdbGrant.shouldAutoRequest(true, false, HostShell.channelReady(app), false)) return false
        p(app).edit().putBoolean(KEY_AUTO_GRANT_TRIED, true).apply()
        val ok = HostShell.selfGrant(app)
        Log.i(TAG, "auto self-grant: $ok")
        return ok
    }

    private fun hook(app: Context) {
        synchronized(this) { if (hooked) return; hooked = true }
        var seen = if (WirelessDebugging.isOn(app)) 1 else 0
        runCatching {
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        val now = System.currentTimeMillis()
                        val on = if (WirelessDebugging.isOn(app)) 1 else 0
                        if (on != seen) {
                            seen = on
                            val mine = now - p(app).getLong("self_write_at", 0L) < SELF_WINDOW_MS
                            val net = if (lastNetworkEventAt == 0L) null else now - lastNetworkEventAt
                            val cause = WdKeeperCore.guessCause(wifiConnected(app), net, SystemClock.elapsedRealtime(), mine && on == 1)
                            audit(app).add(WdAuditLog.Entry(now, on == 1, wifiConnected(app), cause.label, if (mine && on == 1) p(app).getString("self_write_by", null) ?: app.packageName else "-"))
                        }
                        kick(app, if (on == 1) "setting-on" else "setting-off")
                    }
                })
        }.onFailure { Log.w(TAG, "setting observer: ${it.message}") }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { lastNetworkEventAt = System.currentTimeMillis(); wifiEvent = true; kick(app, "wifi-available") }
                    override fun onLost(network: Network) { lastNetworkEventAt = System.currentTimeMillis(); wifiEvent = false }
                })
        }.onFailure { Log.w(TAG, "network callback: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    fun wifiConnected(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
    }.getOrDefault(wifiEvent ?: true)

    // ── Leader election: each app's heartbeat claim, read across the CONSTELLATION_DATA provider ──────────────────────

    fun heartbeat(ctx: Context) { p(ctx.applicationContext).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply() }

    private fun claims(app: Context, now: Long): List<WdKeeperLeader.Claim> {
        val own = WdKeeperLeader.Claim(app.packageName, armed(app), HostShell.canWriteSecureSettings(app), now)
        // #1502 a binder call into a peer starts its process: never into one that may not be woken (Store, Account).
        val peers = AdbPeers.FLEET.filter { it.applicationId != app.packageName && BackgroundPolicy.mayWakePeer(it.applicationId) }.mapNotNull { peer ->
            val b = runCatching { app.contentResolver.call(Uri.parse("content://${peer.authority}"), METHOD_CLAIM, null, null) }.getOrNull()
            if (b == null || !b.containsKey(KEY_HEARTBEAT)) null
            else WdKeeperLeader.Claim(peer.applicationId, b.getBoolean(KEY_ARMED), b.getBoolean(KEY_CAN_WRITE), b.getLong(KEY_HEARTBEAT))
        }
        return peers + own
    }

    /** This app's claim, answered to a peer by [AdbStatusProvider]. A killed process has a stale heartbeat, which is how it drops out. */
    fun claimBundle(ctx: Context): Bundle = Bundle().apply {
        putBoolean(KEY_ARMED, armed(ctx) && p(ctx).getBoolean(KEY_ARMED, false))
        putBoolean(KEY_CAN_WRITE, HostShell.canWriteSecureSettings(ctx))
        putLong(KEY_HEARTBEAT, p(ctx).getLong(KEY_HEARTBEAT, 0L))
    }

    // ── Status, battery, debug API ──────────────────────────────────────────────────────────────────────────────

    fun batteryExempt(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** Opens this app's Permissions page on the battery row (libs:perms owns asking; a lib never opens a settings page itself). */
    /** "Fix in Perms ›": the Permissions page on the Write secure settings row; the grant itself is libs:perms' [com.diegonmarcos.superapp.perms.AdbGrant] row. */
    fun openPermsFix(ctx: Context) = PermsLink.open(ctx, FEATURE, listOf(WSS_ROW))

    fun promptBattery(ctx: Context) = PermsLink.open(ctx, FEATURE, listOf("battery_opt"))

    fun snapshot(ctx: Context): WdKeeperStatus.Snapshot {
        val last = audit(ctx).lastRearm()
        return WdKeeperStatus.Snapshot(armed(ctx), HostShell.canWriteSecureSettings(ctx), batteryExempt(ctx), WdKeeperService.running,
            wifiConnected(ctx), WirelessDebugging.isOn(ctx), last?.at, last?.by)
    }

    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when { s < 90 -> "${s}s ago"; s < 5400 -> "${s / 60} min ago"; s < 129_600 -> "${s / 3600} h ago"; else -> "${s / 86_400} d ago" }
    }

    private fun registerRoute(app: Context) {
        if (routed) return
        routed = true
        runCatching {
            AppDebugServer.route("keeper", listOf(
                AppDebugServer.Op("status", "", "Keep wireless debugging on: armed, permission, battery exemption, service, Wi-Fi, last re-arm, and the audit log"),
                AppDebugServer.Op("log", "", "the audit log of every adb_wifi_enabled change: time, Wi-Fi, cause guess, who re-armed"),
            )) { op, _ -> if (op == "status" || op == "log") statusJson(app) else null }
        }.onFailure { Log.w(TAG, "debug route: ${it.message}") }
    }

    /** One JSON for the debug API of both apps. */
    fun statusJson(ctx: Context): String = WdKeeperStatus.json(snapshot(ctx), audit(ctx).entries())
}

/** The backstop: Doze defers it, so it is only the floor under the observer, the Wi-Fi callback and the service. */
class WdKeeperWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (WdKeeper.armed(applicationContext)) WdKeeper.start(applicationContext)
        return Result.success()
    }
}

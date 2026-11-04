package com.diegonmarcos.superapp.system

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.configs.DeviceControls
import com.diegonmarcos.superapp.system.WirelessDebugKeepAlive.Trigger
import java.util.concurrent.TimeUnit

/**
 * Keeps Wireless Debugging — and the embedded adb channel riding on it — up
 * while the owner's keep-alive switch is on (Configs ▸ Controls ▸ Tools).
 *
 * Android clears `adb_wifi_enabled` on its own: whenever Wi-Fi drops or the
 * BSSID changes (AdbDebuggingManager's NETWORK_STATE_CHANGED handler), and at
 * every boot, where the persisted value is replayed before Wi-Fi is connected
 * and is rejected for lack of one. The adbd that comes back listens on a new
 * port, so the embedded client's old socket is dead even once the setting is
 * back. The decisions live in [WirelessDebugKeepAlive]; this file only wires
 * the triggers, the persisted status and the notification to them.
 *
 * Triggers, all landing on one unique tick:
 *  - periodic (WorkManager, 15 min floor) — survives process death;
 *  - Wi-Fi becoming available — the "Wi-Fi off/on" case, seconds not minutes;
 *  - the setting itself being cleared (ContentObserver) — catches a BSSID
 *    roam, which does not look like a new network to ConnectivityManager;
 *  - BOOT_COMPLETED, via [PrivilegedPlaneBootReceiver].
 * The two in-process callbacks die with the process; the periodic pass is the
 * floor under them.
 *
 * Re-enabling uses WRITE_SECURE_SETTINGS first, not the shell channel: the
 * channel is exactly what is down whenever the setting is off.
 */
class WirelessDebugKeeper(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val trigger = runCatching { Trigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "") }
            .getOrDefault(Trigger.PERIODIC)
        if (!WirelessDebugKeepAlive.shouldTick(trigger, Status.needsOwner(ctx))) return Result.success()

        var o = WirelessDebugKeepAlive.tick(Prefs.enabled(ctx), RealDevice(ctx))
        // adbd and its mDNS advert take a few seconds to appear after a re-arm.
        for (attempt in 1 until ATTEMPTS) {
            if (o.ready || o.needsOwner || isStopped ||
                o.cause == WirelessDebugKeepAlive.CAUSE_OFF || o.cause == WirelessDebugKeepAlive.CAUSE_NO_WIFI) break
            Thread.sleep(RETRY_GAP_MS * attempt)
            o = WirelessDebugKeepAlive.tick(Prefs.enabled(ctx), RealDevice(ctx))
        }
        Log.i(TAG, "tick[$trigger]: $o")
        if (o.cause != WirelessDebugKeepAlive.CAUSE_OFF) Status.record(ctx, o)
        // The "needs owner" state is only recorded (Status); no alert is posted.
        // Withdraw any alert an older build left behind.
        cancelNotice(ctx)
        return Result.success()
    }

    private class RealDevice(private val ctx: Context) : WirelessDebugKeepAlive.Device {
        override fun wirelessDebuggingOn() = WirelessDebugging.isOn(ctx)
        @Suppress("DEPRECATION")
        override fun onWifi() = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
        }.getOrDefault(true)
        override fun enableWirelessDebugging() {
            Log.i(TAG, "re-arm: ${WirelessDebugging.set(ctx, true)}")
            // AdbDebuggingManager rejects on its own handler thread; give it
            // the time to do so before the caller re-reads.
            Thread.sleep(SETTLE_MS)
        }
        override fun channelAnswers() = EmbeddedAdbChannel.isReady(ctx) && EmbeddedAdbChannel.probe(ctx)
        override fun dropChannel() = EmbeddedAdbChannel.disconnect(ctx)
        override fun reconnect() = EmbeddedAdbChannel.autoArm(ctx)
    }

    /** The owner's switch. Default declared in build.json (`default_on` on the
     *  control's own entry), so the shipped behaviour is data. */
    object Prefs {
        private const val FILE = "wd_keepalive"
        private const val KEY_ENABLED = "enabled"

        fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DeviceControls.declaredFlag(WirelessDebugKeeper.CONTROL_ID, "default_on"))

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
            WirelessDebugKeeper.sync(ctx, Trigger.SWITCHED_ON)
        }
    }

    /** What the last tick found — shown in DevControl and /api/adb/status. */
    object Status {
        private const val FILE = "wd_keepalive_status"

        private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun record(ctx: Context, o: WirelessDebugKeepAlive.Outcome) {
            val now = System.currentTimeMillis()
            p(ctx).edit().apply {
                putBoolean("ready", o.ready)
                putBoolean("needs_owner", o.needsOwner)
                putLong("last_tick_at", now)
                if (o.reconnected || o.rearmed) putLong("last_reconnect_at", now)
                if (o.cause.isNotBlank()) { putString("last_failure", o.cause); putLong("last_failure_at", now) }
            }.apply()
        }

        fun needsOwner(ctx: Context) = p(ctx).getBoolean("needs_owner", false)

        /** Ordered (label, value) pairs; one source for the UI and the API. */
        fun rows(ctx: Context): List<Pair<String, String>> {
            val s = p(ctx)
            fun at(k: String) = s.getLong(k, 0L).let { if (it == 0L) "never" else java.util.Date(it).toString() }
            return listOf(
                "keepalive" to Prefs.enabled(ctx).toString(),
                "channel_ready" to EmbeddedAdbChannel.isReady(ctx).toString(),
                "needs_owner" to s.getBoolean("needs_owner", false).toString(),
                "last_tick" to at("last_tick_at"),
                "last_reconnect" to at("last_reconnect_at"),
                "last_failure" to (s.getString("last_failure", null) ?: "none"),
                "last_failure_at" to at("last_failure_at"),
            )
        }
    }

    companion object {
        /** build.json::ui.control_panel id of the switch. */
        const val CONTROL_ID = "wireless_debugging_keepalive"
        const val UNIQUE_NAME = "wireless-debug-keeper"
        private const val TICK_NAME = "wireless-debug-keeper-tick"
        private const val KEY_TRIGGER = "trigger"
        private const val TAG = "WirelessDebugKeeper"
        private const val ATTEMPTS = 3
        private const val RETRY_GAP_MS = 5_000L
        private const val SETTLE_MS = 2_000L
        /** Lets AdbDebuggingManager finish reacting to the same event first. */
        private const val EVENT_DELAY_S = 3L
        private const val ALERT_KEY = "wireless_debug_off"

        @Volatile private var callbacksRegistered = false

        /** Bring scheduling in line with the switch. Called from App.onCreate,
         *  the boot receiver and the switch itself. */
        fun sync(ctx: Context, trigger: Trigger) {
            val app = ctx.applicationContext
            val wm = WorkManager.getInstance(app)
            if (!Prefs.enabled(app)) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                wm.cancelUniqueWork(TICK_NAME)
                cancelNotice(app)
                com.diegonmarcos.superapp.adbdebug.WdKeeper.stop(app)
                return
            }
            // #1467 the shared keeper (libs:shizuku-adb-debug-tools WdKeeper): foreground service, observer, Wi-Fi callback,
            // audit log and leader election with Cloud Store, so whichever app is alive re-arms and the two never fight.
            com.diegonmarcos.superapp.adbdebug.WdKeeper.start(app)
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WirelessDebugKeeper>(15, TimeUnit.MINUTES).build())
            registerCallbacks(app)
            kick(app, trigger, delayS = 0)
        }

        private fun kick(ctx: Context, trigger: Trigger, delayS: Long = EVENT_DELAY_S) {
            if (!Prefs.enabled(ctx)) return
            // APPEND, never REPLACE: a replaced tick's thread keeps running,
            // and two overlapping ticks would drop the channel the other just
            // rebuilt. Queued after a healthy pass, a tick is one probe.
            WorkManager.getInstance(ctx).enqueueUniqueWork(TICK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WirelessDebugKeeper>()
                    .setInitialDelay(delayS, TimeUnit.SECONDS)
                    .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                    .build())
        }

        @Synchronized
        private fun registerCallbacks(app: Context) {
            if (callbacksRegistered) return
            callbacksRegistered = true
            runCatching {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = kick(app, Trigger.NETWORK_AVAILABLE)
                    })
            }.onFailure { Log.w(TAG, "network callback: ${it.message}") }
            runCatching {
                app.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("adb_wifi_enabled"), false,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            // ON as well: the owner switching it on from the
                            // notification should bring the channel back now.
                            kick(app, if (WirelessDebugging.isOn(app)) Trigger.SETTING_ON
                                      else Trigger.SETTING_CLEARED)
                        }
                    })
            }.onFailure { Log.w(TAG, "setting observer: ${it.message}") }
        }

        /** Withdraws the "Wireless debugging is off" alert older builds posted. */
        private fun cancelNotice(ctx: Context) = FleetAlerts.withdraw(ctx, ALERT_KEY)
    }
}

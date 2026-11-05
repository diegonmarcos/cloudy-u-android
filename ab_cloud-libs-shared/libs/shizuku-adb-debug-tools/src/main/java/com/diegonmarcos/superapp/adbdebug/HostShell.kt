package com.diegonmarcos.superapp.adbdebug

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The host glue every app with its OWN uid-2000 channel needs (Cloud Store since #894, Cloud
 * Account since the account redesign, spec section 2.4). Moved here from Cloud Store's
 * `CloudStoreShell` so both apps run one copy; parameterised by nothing but the host Context
 * (the work name is derived from the package).
 *
 * The engine (embedded adb client, Shizuku fallback per build.json::shizuku_client) leaves three
 * things to its host, and this object does them: re-arm after a reboot, keep Wireless debugging on
 * once the app may, and finish a pairing with the WRITE_SECURE_SETTINGS self-grant.
 *
 * A host calls [install] from Application.onCreate and declares [HostShellBootReceiver] for
 * BOOT_COMPLETED plus the [AdbPairingService] entry (the lib manifest merges the service).
 */
object HostShell {
    private const val TAG = "HostShell"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Unique WorkManager name for this host's re-arm job. */
    fun workName(ctx: Context): String = "${ctx.packageName}.shell-channel"

    /**
     * Whether [HostShellService] may (re)start the fleet debug server. The host's switch, set
     * before [install]: Cloud Account wires Settings ▸ Debug API here; a host that sets nothing
     * keeps the server up, as DebugInitProvider already starts it.
     */
    @Volatile var debugServerAllowed: (Context) -> Boolean = { true }

    /**
     * #1502 May this host run in the BACKGROUND (a keep-alive service, a boot re-arm, a WorkManager pass, the wireless-debugging
     * keeper)? The host's policy, set before [install]: Cloud Account says never, Cloud Store only with auto-update on and Wi-Fi
     * (libs:core BackgroundPolicy). Default true, as before, for a host that sets nothing (SuperApp).
     */
    @Volatile var backgroundAllowed: (Context) -> Boolean = { true }

    fun mayBackground(ctx: Context): Boolean = runCatching { backgroundAllowed(ctx) }.getOrDefault(false)

    /** Called once from the host's Application.onCreate. */
    fun install(app: Context) {
        // The pairing service ends with the channel up; what follows is the host's.
        AdbPairingService.onConnected = { c -> onConnected(c.applicationContext) }
        runCatching { RishBridge.requestShizukuPermissionIfNeeded() }
        if (mayBackground(app)) schedule(app, replace = false)
        else {
            // #1502 no WorkManager job and no service for this host: clear what an older build queued, and re-arm in-process,
            // now, while the app is running (the process was started by the user or by a caller, never by this).
            runCatching { WorkManager.getInstance(app).cancelUniqueWork(workName(app)) }
            if (ChannelAutoArm.enabled(app)) Thread({ reconnect(app, 3) }, "host-shell-rearm").apply { isDaemon = true }.start()
        }
        // The channel and the debug server are process state: the foreground service holds the process up.
        if (KeepAlivePolicy.needed(debugServerAllowed(app), ChannelAutoArm.enabled(app), mayBackground(app))) HostShellService.start(app)
        WdKeeper.start(app)   // #1467 keep wireless debugging on: observer, Wi-Fi callback, service, backstop
    }

    /** Re-arm the channel in the background: unique work, so a repeat call never stacks. */
    fun schedule(ctx: Context, replace: Boolean) {
        if (!mayBackground(ctx)) return   // #1502
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                workName(ctx),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HostShellWorker>().build())
        }.onFailure { Log.w(TAG, "channel work not scheduled", it) }
    }

    /**
     * First connect right after the one-time pairing. Grants this app WRITE_SECURE_SETTINGS
     * through the channel it just got, which is what lets every later boot turn Wireless
     * debugging back on without the user (the platform clears it on reboot).
     */
    private fun onConnected(ctx: Context) {
        Thread({ selfGrant(ctx) }, "host-shell-grant").start()
    }

    /** BLOCKS. `pm grant <self> WRITE_SECURE_SETTINGS` over the embedded channel; true when held after. */
    fun selfGrant(ctx: Context): Boolean {
        if (canWriteSecureSettings(ctx)) return true
        val out = EmbeddedAdbChannel.exec(ctx,
            com.diegonmarcos.superapp.perms.PermsShell.grant(ctx.packageName, "android.permission.WRITE_SECURE_SETTINGS") + " 2>&1")
        Log.i(TAG, "grant WRITE_SECURE_SETTINGS -> ${out?.trim()?.take(160) ?: "no output"}")
        return canWriteSecureSettings(ctx)
    }

    fun canWriteSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Flip `adb_wifi_enabled` back on when this app may. True when it is (now) on. */
    fun keepWirelessDebuggingOn(ctx: Context): Boolean {
        if (!canWriteSecureSettings(ctx)) return false
        return runCatching {
            if (Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) != 1)
                Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** BLOCKS. Backoff reconnect: adbd and its mDNS advert take a while after boot. */
    fun reconnect(ctx: Context, attempts: Int): Pair<Boolean, String> {
        if (!ChannelAutoArm.enabled(ctx)) return false to ChannelAutoArm.OFF_MSG
        keepWirelessDebuggingOn(ctx)
        var last = ""
        for (n in 1..attempts) {
            val (ok, msg) = EmbeddedAdbChannel.autoArm(ctx)
            last = msg
            if (ok) { Log.i(TAG, "channel up on attempt $n: $msg"); return true to msg }
            Log.i(TAG, "attempt $n/$attempts: $msg")
            if (n < attempts) Thread.sleep(minOf(5_000L * n, 20_000L))
        }
        return false to last
    }
}

/** Re-arms the channel off the main thread: boot, app start, and after a pairing. */
open class HostShellWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val (ok, msg) = HostShell.reconnect(applicationContext, ATTEMPTS)
        if (!ok) Log.i("HostShell", "no channel ($msg) - privileged steps wait until paired")
        return Result.success()
    }
    private companion object { const val ATTEMPTS = 6 }
}

/** BOOT_COMPLETED: the embedded client's connection is process state, so it is gone after a reboot. */
open class HostShellBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (ChannelAutoArm.enabled(context)) HostShell.schedule(context.applicationContext, replace = true)
            if (KeepAlivePolicy.needed(HostShell.debugServerAllowed(context), ChannelAutoArm.enabled(context), HostShell.mayBackground(context))) HostShellService.start(context.applicationContext)
            WdKeeper.start(context.applicationContext)   // #1467
        }
    }
}

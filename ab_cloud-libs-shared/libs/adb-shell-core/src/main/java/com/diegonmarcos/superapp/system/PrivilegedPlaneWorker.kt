package com.diegonmarcos.superapp.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.configs.AdbOwnerHost

/**
 * Makes the privileged plane self-healing across reboots with no Shizuku.
 *
 * The hard constraint: on a non-rooted phone only adbd can spawn a shell-uid
 * (2000) process, so "auto-start Shizuku" reduces to "run adb unattended" —
 * and that fails after every reboot for one reason only: Android turns
 * Wireless Debugging OFF on reboot. So:
 *
 *  1. If we hold WRITE_SECURE_SETTINGS (granted once by this very plane, see
 *     step 3), flip `adb_wifi_enabled` back on.
 *  2. Autoconnect the embedded adb client (mDNS `_adb-tls-connect` + the
 *     keys stored at the one-time pairing) with backoff — adbd and its mDNS
 *     advert take a while after boot.
 *  3. Once up, `pm grant` every entry of build.json::ui.permissions.privileged
 *     to its apps. That list includes WRITE_SECURE_SETTINGS for this app, so
 *     the FIRST successful connect (right after the user pairs once) is what
 *     bootstraps step 1 for every boot after. Pair once, ever.
 *
 * pm grant of a signature perm persists across reboots and in-place updates.
 */
class PrivilegedPlaneWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result { AdbOwnerHost.wake("system", "plane_worker"); return AdbOwnerHost.timed("system", "plane") { arm() } }

    private fun arm(): Result {
        val ctx = applicationContext
        enableWirelessDebugging(ctx)
        // First consultation of the chosen mode's channel at start: a pure read, it launches nothing.
        // The loopback server is optional and started only from its own button.
        Log.i(TAG, "shell ladder at start: ${com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.name() ?: "none"}" +
            " — optional loopback server ${com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.bootstrapState(ctx)}")
        // #891 the tuning pass needs A shell, not the embedded one: run it now through whatever the
        // ladder resolved (own server, Shizuku). A phone that never paired the embedded adb — the
        // owner's — otherwise never gets its device tuning; the pass is verify-first and single-run.
        if (com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx) != null)
            AdbOwnerHost.tune(ctx) { cmd -> com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.exec(ctx, cmd) }

        // The switch is the owner's (default off, build.json::privileged_channel.default_on): nothing below arms the channel without it.
        if (!com.diegonmarcos.superapp.adbdebug.ChannelAutoArm.enabled(ctx)) {
            Log.i(TAG, "auto-arm off - not connecting the embedded channel")
            return Result.success()
        }
        var last = ""
        for (attempt in 1..ATTEMPTS) {
            val (ok, msg) = EmbeddedAdbChannel.autoArm(ctx)
            last = msg
            if (ok) {
                Log.i(TAG, "privileged plane up on attempt $attempt: $msg")
                AdbOwnerHost.selfGrant(ctx) { cmd -> EmbeddedAdbChannel.exec(ctx, cmd) }
                // Data-driven device tuning (data/device-tuning.json) through the
                // same channel: phantom-process killer off, etc. Verify-first,
                // so the steady-state pass writes nothing.
                // #891 through the ACTIVE shell channel (the same ladder /api/adb/exec uses: our own server, then
                // Shizuku), not the embedded-adb one alone -- on the owner's phone the embedded channel was
                // unpaired while Shizuku answered, and every entry sat at PENDING with every value applied.
                AdbOwnerHost.tune(ctx) { cmd -> com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.exec(ctx, cmd) }
                return Result.success()
            }
            Log.i(TAG, "attempt $attempt/$ATTEMPTS: $msg")
            // ponytail: linear backoff capped at 30s; total budget ~3 min.
            Thread.sleep(minOf(5_000L * attempt, 30_000L))
        }
        Log.w(TAG, "privileged plane NOT up after $ATTEMPTS attempts: $last (paired yet?)")
        AdbOwnerHost.error("system", "plane", "privileged plane not up after $ATTEMPTS attempts")
        return Result.failure()
    }

    private fun enableWirelessDebugging(ctx: Context) {
        // The owner's keep-alive switch decides whether this app turns it on.
        if (!WirelessDebugKeeper.Prefs.enabled(ctx)) {
            Log.i(TAG, "keep-alive off - leaving adb_wifi_enabled as the owner set it")
            return
        }
        val granted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted yet - cannot flip adb_wifi_enabled; relying on it being on")
            return
        }
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
            Log.i(TAG, "adb_wifi_enabled=1")
        }.onFailure { Log.w(TAG, "adb_wifi_enabled write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "PrivilegedPlane"
        private const val ATTEMPTS = 8
        // Settings.Global key; not a public constant but stable since Android 11.
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

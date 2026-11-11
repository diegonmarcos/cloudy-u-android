package com.diegonmarcos.superapp.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel

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

    override fun doWork(): Result { com.diegonmarcos.superapp.xray.EngineProfiler.wake("system", "plane_worker"); return com.diegonmarcos.superapp.xray.EngineProfiler.time("system", "plane") {
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
            DeviceTuning.run(ctx) { cmd -> com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.exec(ctx, cmd) }

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
                selfGrant(ctx)
                // Data-driven device tuning (data/device-tuning.json) through the
                // same channel: phantom-process killer off, etc. Verify-first,
                // so the steady-state pass writes nothing.
                // #891 through the ACTIVE shell channel (the same ladder /api/adb/exec uses: our own server, then
                // Shizuku), not the embedded-adb one alone -- on the owner's phone the embedded channel was
                // unpaired while Shizuku answered, and every entry sat at PENDING with every value applied.
                DeviceTuning.run(ctx) { cmd -> com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.exec(ctx, cmd) }
                return Result.success()
            }
            Log.i(TAG, "attempt $attempt/$ATTEMPTS: $msg")
            // ponytail: linear backoff capped at 30s; total budget ~3 min.
            Thread.sleep(minOf(5_000L * attempt, 30_000L))
        }
        Log.w(TAG, "privileged plane NOT up after $ATTEMPTS attempts: $last (paired yet?)")
        com.diegonmarcos.superapp.xray.EngineProfiler.error("system", "plane", "privileged plane not up after $ATTEMPTS attempts")
        return Result.failure()
    } }

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

    /**
     * Grant everything PrivilegedGrants resolves off the running platform.
     *
     * All of the "which package, which permission" reasoning lives in
     * PrivilegedGrants so the Permissions screen shows exactly what this grants
     * — they drifted apart once already and a permission disappeared from the
     * UI while still being granted here.
     *
     * Contract preserved verbatim: idempotent (no prefs, no state — re-running
     * simply re-grants), never throws, and degrades silently when the channel
     * is down (exec returns null → logged, loop continues).
     */
    private fun selfGrant(ctx: Context) {
        val targets = PrivilegedGrants.resolve(ctx)
        Log.i(TAG, "privileged targets (${targets.size}): " +
            targets.joinToString { "${it.pkg}/${it.perm.substringAfterLast('.')}" })
        for (t in targets) {
            val out = PrivilegedGrants.grant(ctx, t) { cmd -> EmbeddedAdbChannel.exec(ctx, cmd) }
            Log.i(TAG, "grant ${t.pkg} ${t.perm} -> ${out?.trim()?.take(160) ?: "no output (channel down?)"}")
        }
        // Withdraw what policy no longer sanctions. Narrowing an entry to fewer
        // apps only stops FUTURE grants; the grant a previous build already
        // made outlives it, so without this pass the plane can widen but never
        // narrow. Runs after the grants so a package that is both granted and
        // stale (different permissions) ends up in the right state either way.
        for (t in PrivilegedGrants.staleGrants(ctx)) {
            val out = PrivilegedGrants.revoke(ctx, t) { cmd -> EmbeddedAdbChannel.exec(ctx, cmd) }
            Log.i(TAG, "revoke ${t.pkg} ${t.perm} -> ${out?.trim()?.take(160) ?: "no output (channel down?)"}")
        }
        // Whitelist the WHOLE fleet, NOT the resolved targets. resolve() skips
        // permissions that are already granted, so on the steady-state boot —
        // every grant already done — the target list is EMPTY and deriving the
        // whitelist from it would silently drop mail (and everything else) off
        // the deviceidle whitelist. That is the exact regression the comment on
        // batteryWhitelistFleet warns about, one step further along.
        batteryWhitelistFleet(ctx, PrivilegedGrants.fleetPackages(ctx, installedOnly = true))
    }

    /**
     * Doze battery-optimization exemption for the fleet, via shell. On Samsung
     * (SM-G996B) Doze kills even a foreground sync service unless the app is on
     * the deviceidle whitelist — measured 2026-09-03: cloud-mail's process was
     * dead and JMAP stopped fetching whenever the phone idled. `pm grant` can't
     * set this (it is not a runtime permission); `dumpsys deviceidle whitelist
     * +<pkg>` can, and it persists. Whitelist every distinct app RESOLVED from
     * the privileged list (mail, superapp, ...) so the whole constellation keeps
     * syncing in the background with no per-app battery dialog. Takes the
     * resolved set rather than re-reading the `apps` arrays: those are now
     * optional, and re-deriving them here would silently drop mail off the
     * whitelist the moment an entry switched to dynamic resolution.
     */
    private fun batteryWhitelistFleet(ctx: Context, pkgs: Set<String>) {
        for (pkg in pkgs) {
            val out = EmbeddedAdbChannel.exec(ctx, "dumpsys deviceidle whitelist +$pkg 2>&1 && echo OK")
            Log.i(TAG, "deviceidle whitelist +$pkg -> ${out?.trim()?.take(120)}")
        }
    }

    companion object {
        private const val TAG = "PrivilegedPlane"
        private const val ATTEMPTS = 8
        // Settings.Global key; not a public constant but stable since Android 11.
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

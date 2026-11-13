package com.diegonmarcos.superapp.system

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.configs.AdbOwnerHost

/**
 * #1682 P3 step 4d: the SuperApp's half of the privileged plane. The boot worker that arms the channel lives in libs:adb-shell-core
 * (PrivilegedPlaneWorker, the owner's); what it does once the channel is up is the SuperApp's own feature (the declared grants and the
 * device-tuning pass), installed as AdbOwnerHost.plane by App.onCreate. Bodies moved verbatim from the worker; [exec] is the channel the
 * worker armed.
 */
object PrivilegedPlaneHost : AdbOwnerHost.Plane {
    private const val TAG = "PrivilegedPlane"

    override fun tune(ctx: Context, exec: (String) -> String?) { DeviceTuning.run(ctx, exec) }

    override fun selfGrant(ctx: Context, exec: (String) -> String?) = grantAll(ctx, exec)

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
    private fun grantAll(ctx: Context, exec: (String) -> String?) {
        val targets = PrivilegedGrants.resolve(ctx)
        Log.i(TAG, "privileged targets (${targets.size}): " +
            targets.joinToString { "${it.pkg}/${it.perm.substringAfterLast('.')}" })
        for (t in targets) {
            val out = PrivilegedGrants.grant(ctx, t) { exec(it) }
            Log.i(TAG, "grant ${t.pkg} ${t.perm} -> ${out?.trim()?.take(160) ?: "no output (channel down?)"}")
        }
        // Withdraw what policy no longer sanctions. Narrowing an entry to fewer
        // apps only stops FUTURE grants; the grant a previous build already
        // made outlives it, so without this pass the plane can widen but never
        // narrow. Runs after the grants so a package that is both granted and
        // stale (different permissions) ends up in the right state either way.
        for (t in PrivilegedGrants.staleGrants(ctx)) {
            val out = PrivilegedGrants.revoke(ctx, t) { exec(it) }
            Log.i(TAG, "revoke ${t.pkg} ${t.perm} -> ${out?.trim()?.take(160) ?: "no output (channel down?)"}")
        }
        // Whitelist the WHOLE fleet, NOT the resolved targets. resolve() skips
        // permissions that are already granted, so on the steady-state boot —
        // every grant already done — the target list is EMPTY and deriving the
        // whitelist from it would silently drop mail (and everything else) off
        // the deviceidle whitelist. That is the exact regression the comment on
        // batteryWhitelistFleet warns about, one step further along.
        batteryWhitelistFleet(ctx, PrivilegedGrants.fleetPackages(ctx, installedOnly = true), exec)
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
    private fun batteryWhitelistFleet(ctx: Context, pkgs: Set<String>, exec: (String) -> String?) {
        for (pkg in pkgs) {
            val out = exec("dumpsys deviceidle whitelist +$pkg 2>&1 && echo OK")
            Log.i(TAG, "deviceidle whitelist +$pkg -> ${out?.trim()?.take(120)}")
        }
    }
}

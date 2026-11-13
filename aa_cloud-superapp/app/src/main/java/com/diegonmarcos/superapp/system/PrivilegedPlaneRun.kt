package com.diegonmarcos.superapp.system

import android.content.Context
import android.util.Log

/**
 * #1682 P3 step 5: the SuperApp's privileged plane, owner-independent: the declared grants, the device-tuning pass and the fleet
 * deviceidle whitelist, each as shell commands through an `exec` the caller supplies. The owner build hands it the channel its boot
 * worker armed (PrivilegedPlaneHost, src/owner); the client build hands it ShellClient.exec (ClientPlaneWorker, src/client). Bodies
 * moved verbatim from PrivilegedPlaneHost; every command literal is on the SuperApp's allowlist entry (ClientCallSitesAllowlistTest).
 */
object PrivilegedPlaneRun {
    private const val TAG = "PrivilegedPlane"

    fun tune(ctx: Context, exec: (String) -> String?) { DeviceTuning.run(ctx, exec) }

    fun selfGrant(ctx: Context, exec: (String) -> String?) = grantAll(ctx, exec)

    fun grantDump(ctx: Context, exec: (String) -> String?): String? =
        PrivilegedGrants.grant(ctx, PrivilegedGrants.Target(ctx.packageName, "android.permission.DUMP", "Dump system services")) { exec(it) }

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
            val out = exec("dumpsys deviceidle whitelist +$pkg 2>&1")   // #1682 no `&&`: the broker allowlist refuses chains, and the output line is all the log needs
            Log.i(TAG, "deviceidle whitelist +$pkg -> ${out?.trim()?.take(120)}")
        }
    }
}

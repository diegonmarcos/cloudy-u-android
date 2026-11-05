package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Who else is on the mesh, and how to wake them.
 *
 * A member is any installed package shipping a devtools [FleetTokenProvider] —
 * the provider is the membership marker, so the list cannot drift out of sync
 * with a hardcoded roster the way a per-app manifest list would.
 *
 * Visibility note (#733): Android 11+ filters package queries, and a shared
 * signing certificate does NOT make two apps visible to each other — it is not
 * on the platform's automatic-visibility list. The earlier note here assumed it
 * was, so in every member without QUERY_ALL_PACKAGES this list held itself and
 * SuperApp and nothing else. Visibility now comes from the devtools manifest:
 * it queries the MESH_MEMBER action that every member's FleetMemberReceiver
 * answers, so the whole fleet is visible with no broad grant. If [list] ever
 * comes back shorter than the installed fleet, Apps Mesh ▸ Missing membership
 * names the member that is blind and the ones it cannot see.
 */
object FleetPeers {
    private const val TAG = "FleetPeers"

    /** Installed mesh members, this app included. */
    fun list(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS)
            .filter { pi ->
                pi.providers?.any { it.authority == "${pi.packageName}.fleet" } == true
            }
            .map { it.packageName }
            .sorted()
    }.onFailure { Log.w(TAG, "peer list failed: $it") }.getOrElse { emptyList() }

    /**
     * Start a member's process without launching an activity.
     *
     * Touching a ContentProvider starts its hosting process if it is not already
     * running, and unlike `startActivity` that is not subject to the background
     * activity launch restrictions Android 10+ applies — an app sitting in the
     * background cannot reliably launch anything, but it can always do this.
     * Once the process is up its DebugInitProvider runs and [AppDebugServer]
     * binds a port, which is the whole point: reach an app's logs without
     * needing a human to tap its icon first.
     *
     * A force-stopped app stays stopped. Nothing short of the user opening it
     * revives it, by design, and no amount of provider poking changes that.
     */
    private val NO_WAKE = setOf("com.diegonmarcos.cloudaccount", "com.diegonmarcos.cloudstore")

    fun wake(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        // #1502 Cloud Account and Cloud Store are never started on someone else's behalf (libs:core BackgroundPolicy.NO_WAKE_PEERS;
        // restated here because libs:devtools does not depend on libs:core, and the guard test-no-background-wake.sh keeps them equal).
        if (pkg in NO_WAKE) { Log.i(TAG, "wake $pkg refused: it is never woken from the background"); return false }
        val uri = Uri.parse("content://$pkg.fleet/token")
        // The row itself is the peer's token and is deliberately discarded —
        // waking a peer is not a reason to hand its credential to the caller.
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
        }.onFailure { Log.w(TAG, "wake $pkg failed: $it") }.getOrDefault(false)
    }
}

package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.net.Uri
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * One token for the whole fleet — the pre-shared key of the on-device mesh.
 *
 * Membership is not something this code decides. [PERMISSION] is declared
 * `signature`, so the platform grants it only to APKs carrying the Cloud
 * signing key: the peer list IS the signing key, the way a WireGuard peer list
 * is its public keys. A foreign app cannot hold the permission, cannot read
 * [FleetTokenProvider], and so never learns the token — and no user prompt can
 * grant it either, signature permissions are not user-grantable.
 *
 * The token is never a compile-time constant. A constant baked into an APK is
 * not a secret: `applicationInfo.sourceDir` is public API and base.apk is
 * readable by any app that bothers to look, so a shared literal would defend
 * against nothing. This one is minted at runtime by [AUTHORITY_PKG] on first
 * launch and adopted by every other member over the guarded provider.
 *
 * Fails CLOSED. If the authority is unreachable and nothing was ever adopted,
 * [get] returns a value nobody can know, so every guarded route answers 401.
 */
object FleetToken {
    private const val TAG = "FleetToken"

    /** Platform-enforced fleet membership — the same permission `libs:core`
     *  already declares signature-level for the AIDL engines, and the same one
     *  SuperApp surfaces as "Cloud Perms". Reused rather than reinvented: an
     *  app either belongs to the constellation or it does not, and a second
     *  permission would only add a way for the two answers to disagree. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    /** Legacy minting app: since #1835 only the read-only FALLBACK while [OWNER_PKG] holds no token. */
    const val AUTHORITY_PKG = "com.diegonmarcos.superapp"

    /** #1835 the OWNER: Cloud C3 Webserver (`<pkg>.fleettoken` provider, signature-guarded). Read FIRST by [get]. */
    const val OWNER_PKG = "com.diegonmarcos.cloudwebserver"
    private const val OWNER_URI = "content://$OWNER_PKG.fleettoken/token"

    /** Authority of [FleetTokenProvider] in the minting app. Every member ships
     *  the provider under its own `${applicationId}.fleet`, but only this one is
     *  ever read. */
    private const val AUTHORITY_URI = "content://$AUTHORITY_PKG.fleet/token"

    @Volatile private var cached: String? = null
    @Volatile private var ownerAt = 0L
    /** #1835 the owner's value, mirrored into this app's prefs (offline cache); re-read every 15 s. Off-main only. */
    private fun owner(app: Context): String? {
        val fetched = runCatching { app.contentResolver.query(Uri.parse(OWNER_URI), null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        return fetched?.takeIf { it.isNotBlank() }?.also { DevControlPrefs(app).adopt(it) }
    }

    fun get(ctx: Context): String {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        cached?.let { if (now - ownerAt < 15_000L) return it }
        val t = owner(app) ?: cached ?: if (app.packageName == AUTHORITY_PKG) DevControlPrefs(app).token else adopt(app)
        if (t != null) {
            cached = t; ownerAt = now
            return t
        }
        // Fail closed, but do NOT cache the miss: installing SuperApp later then
        // heals the mesh on the next request instead of after a process restart.
        Log.w(TAG, "$AUTHORITY_PKG unreachable and nothing adopted — failing closed")
        return UUID.randomUUID().toString()
    }

    /**
     * Constant-time compare. The loopback bind is no longer the only defence
     * now that fleet data rides these routes, and a byte-at-a-time timing probe
     * is exactly the attack a naive `==` leaves open. `MessageDigest.isEqual`
     * is stdlib and already constant-time.
     */
    fun matches(ctx: Context, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            get(ctx).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Pull the authority's token over the signature-guarded provider and keep it
     * in this app's own private prefs. The cache is what lets a member keep
     * authenticating while SuperApp is force-stopped, updating, or uninstalled.
     *
     * Runs on the caller's thread — [AppDebugServer] only ever calls this from a
     * socket thread, never the main one, because a cross-process query on main
     * is an ANR waiting for a slow provider.
     */
    private fun adopt(app: Context): String? {
        val prefs = DevControlPrefs(app)
        val fetched = runCatching {
            app.contentResolver.query(Uri.parse(AUTHORITY_URI), null, null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.onFailure { Log.w(TAG, "fleet token query failed: $it") }.getOrNull()

        if (!fetched.isNullOrBlank()) {
            prefs.adopt(fetched)
            return fetched
        }
        if (prefs.hasToken) {
            Log.w(TAG, "$AUTHORITY_PKG unreachable — using last adopted token")
            return prefs.token
        }
        return null
    }
}

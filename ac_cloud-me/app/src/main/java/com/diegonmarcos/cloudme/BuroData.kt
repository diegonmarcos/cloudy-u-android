package com.diegonmarcos.cloudme

import android.content.Context

/**
 * The runtime source of the owner's personal Buro data, kept apart from the UI so the screens are
 * pure rendering and the whole fetch path is unit-testable (see [BuroSourceTest]).
 *
 * #1557 github-login-direct: the public apk ships NOTHING personal (guard test-no-bundled-secrets).
 * Each private cloud-me-pvt-* repo's GitHub Action bakes a per-domain, ed25519-signed SQLite pack and
 * publishes it as a PRIVATE release asset. With the user's own GitHub login (device flow, no client
 * secret on device — reuses libs:gh's login), the app downloads the latest asset, verifies the
 * signature (libs:fleet-data-client [com.diegonmarcos.superapp.fleetdata.Ed25519Check]), keeps a
 * last-known-good copy, and for me-secrets decrypts on-device with a Keystore key. Access is gated
 * entirely by GitHub: only the owner's login (or a granted collaborator) can read; anyone else who
 * signs in sees [BuroState.NoAccess]. The default [source] here is owner-pending: it reports
 * [BuroState.SignedOut] until a GitHub App client_id is wired into build.json and the live loader is
 * installed, so every screen renders its sign-in state and nothing fabricates data.
 */
sealed interface BuroState {
    /** No GitHub session yet — show "Sign in with GitHub". */
    object SignedOut : BuroState
    object Loading : BuroState
    /** Signed in, but the login cannot read the owner's private packs. */
    data class NoAccess(val reason: String) : BuroState
    /** The pack is here. [json] is the decrypted (for secrets) dataset text. [fromCache] = last-known-good. */
    data class Ready(val json: String, val fromCache: Boolean) : BuroState
    data class Error(val message: String) : BuroState
}

/** Loads one named pack (me-secrets, me-iddd, me-financial, me-portfolio, me-configs, me-events). */
fun interface BuroSource {
    fun load(ctx: Context, pack: String): BuroState
}

object BuroData {
    /** The reason shown while the GitHub App is not yet wired (owner creates it + notes its client_id). */
    const val PENDING = "Sign in with GitHub to read your private Buro data. (The GitHub App is being set up.)"

    /** Swappable so a live loader (libs:gh + libs:fleet-data-client) or a test fake can replace the default. */
    @Volatile var source: BuroSource = BuroSource { _, _ -> BuroState.SignedOut }

    fun load(ctx: Context, pack: String): BuroState = runCatching { source.load(ctx, pack) }
        .getOrElse { BuroState.Error(it.message ?: "load failed") }
}

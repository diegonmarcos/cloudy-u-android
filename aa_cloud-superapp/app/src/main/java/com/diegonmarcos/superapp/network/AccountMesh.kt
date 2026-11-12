package com.diegonmarcos.superapp.network

import android.content.Context
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.BufferedReader
import java.io.StringReader

/**
 * #867 The mesh tunnel's apply path: this app's WireGuard prefs, fed a vault profile.
 * #1693 libs:account is Cloud Account's now, so this is no longer its AccountHost.Mesh: the Mesh
 * page (AndroidMeshPort) calls it directly.
 */
object AccountMesh {

    fun interfaceAddress(ctx: Context): String = WgState.prefs(ctx).interfaceAddress

    fun apply(ctx: Context, name: String, conf: String): String = applyMesh(WgState.prefs(ctx), name, conf)

    /** The vault's conf goes through the SAME parser the .conf import uses; a
     *  text the WireGuard parser rejects writes nothing. Returns the report line. */
    fun applyMesh(prefs: WireGuardPrefs, name: String, conf: String): String {
        val cfg = try {
            Config.parse(BufferedReader(StringReader(conf)))
        } catch (t: Throwable) {
            return "✗ $name rejected by the WireGuard parser: ${t.message}"
        }
        prefs.tunnelName = name.take(15)
        prefs.hydrateFromConfig(cfg)
        prefs.configProvider = WireGuardPrefs.PROVIDER_CUSTOM
        return "✓ $name applied: ${cfg.peers.size} peers, key from the vault"
    }

    fun publicKey(ctx: Context): String = WgState.prefs(ctx).derivedInterfacePublicKey()

    /**
     * #573 The four profiles stored as the selectable set (keys stripped), [privateKey] into the
     * ONE interface slot when the vault carried the device's, [active] made the tunnel and brought
     * up through the engine ([WgState.backend], the same setState Configs ▸ Mesh ▸ Connect calls),
     * then the handshake read for the report. Every text goes through the upstream parser first;
     * one rejected text writes nothing.
     */
    fun applyAll(ctx: Context, profiles: Map<String, String>, active: String, privateKey: String?): String {
        val prefs = WgState.prefs(ctx)
        val key = privateKey ?: prefs.interfacePrivateKey
        if (key.isBlank()) return "✗ no private key on this device; nothing written"
        for ((name, conf) in profiles) {
            runCatching { Config.parse(BufferedReader(StringReader(WireGuardPrefs.stripPrivateKey(conf).replace(WireGuardPrefs.PROVIDED_BY_DEVICE, key)))) }
                .onFailure { return "✗ $name rejected by the WireGuard parser: ${it.message}; nothing written" }
        }
        if (privateKey != null) prefs.interfacePrivateKey = privateKey
        prefs.saveProfiles(profiles)
        val cfg = runCatching { prefs.activateProfile(active) }.getOrElse { return "✗ $active: ${it.message}" }
        val lines = mutableListOf("✓ ${profiles.size} profiles stored: ${profiles.keys.joinToString(", ") { it.substringAfterLast('/') }}",
            "✓ $active is the tunnel: ${cfg.peers.size} peers, key " + (if (privateKey != null) "from the vault" else "this phone's"))
        val backend = WgState.backend(ctx)
        if (!backend.isEngineInstalled()) return (lines + "✗ not connected: the WireGuard engine (Cloud-Lib-Net-Wg) is not installed — Store ▸ Cloud Constellation ▸ Libs").joinToString("\n")
        MeshIntent.setWanted(ctx, true) // #1120 Apply all connects: the owner wants the mesh
        val up = runCatching { backend.setState(WgState.tunnel, Tunnel.State.UP, prefs.toTunnelConfig()) }
        up.onFailure { t ->
            prefs.tunnelEnabled = false
            // VpnService consent is the engine's to ask (VpnConsentActivity); the host's Connect button holds the launcher.
            return (lines + "✗ not connected: ${t.message} — Configs ▸ Mesh ▸ Connect asks the VPN consent once, then Apply all connects").joinToString("\n")
        }
        prefs.tunnelEnabled = up.getOrNull() == Tunnel.State.UP
        return (lines + status(ctx, waitMs = 6000)).joinToString("\n")
    }

    fun status(ctx: Context): String = status(ctx, waitMs = 0)

    /** up/down · active profile · newest peer handshake; waits up to [waitMs] for a first handshake. Never a key. */
    fun status(ctx: Context, waitMs: Long): String {
        val prefs = WgState.prefs(ctx)
        val backend = WgState.backend(ctx)
        if (!backend.isEngineInstalled()) return "engine not installed"
        val up = runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val active = prefs.activeProfile.substringAfterLast('/').ifBlank { prefs.tunnelName }
        if (!up) return "down · $active"
        val until = System.currentTimeMillis() + waitMs
        var newest = 0L
        do {
            newest = runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull()?.let { st ->
                prefs.peers().maxOfOrNull { p -> runCatching { st.peer(Key.fromBase64(p.publicKey))?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L) } ?: 0L
            } ?: 0L
            if (newest > 0L || System.currentTimeMillis() >= until) break
            Thread.sleep(500)
        } while (true)
        return if (newest > 0L) "✓ up · $active · handshake ${(System.currentTimeMillis() - newest) / 1000}s ago"
        else "up · $active · no handshake yet — the hub must list this phone's public key as its peer"
    }
}

package com.diegonmarcos.superapp.network.mesh

import android.content.Intent
import com.diegonmarcos.superapp.network.AlwaysOnVpn
import com.diegonmarcos.superapp.network.MeshIntent

/**
 * #877 Everything the Cloud Mesh page asks of the tunnel, as one interface. [AndroidMeshPort] is the
 * only implementation and it is a thin shell over the EXISTING paths - WgState.backend (the engine's
 * getState / setState / getStatistics), WireGuardPrefs, AccountMesh, FleetDns. There is no second
 * tunnel path: a test fakes this interface, the app never has two of it.
 *
 * Every method blocks on prefs or the engine binder, so the store calls them off the main thread.
 * A method that cannot do what was asked THROWS with the reason; the store turns it into the notice.
 */
interface MeshPort {
    // ── reads ──
    fun config(): ConfigView
    fun engineInstalled(): Boolean
    fun sample(nowMs: Long): Sample
    /** One round of latency probes: the hub (first fleet resolver) and each peer's own mesh resolver. */
    fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long): LatencyReading
    /** True when the tunnel is up on a config older than the stored one (reconnect to apply). */
    fun pendingApply(): Boolean
    /** Android's Always-on VPN / lockdown for the engine's VPN profile; null when the engine cannot say. */
    fun alwaysOn(): Boolean?
    fun lockdown(): Boolean?
    /** The VPN-consent intent, or null when consent is held (or the engine is absent). */
    fun consentIntent(): Intent?

    // ── #1120 intent, start-up, Always-on (Android) ──
    /** What the owner wants of the mesh (connected until Disconnect) and the three start-up conditions. */
    fun intent(): MeshIntent.Policy
    /** [which] boot | wifi | mobile. */
    fun setStartup(which: String, on: Boolean): String
    /** Android's Always-on VPN / lockdown as it reads now, and whether the privileged channel can change it. */
    fun alwaysOnView(): AlwaysOnVpn.View
    fun setAlwaysOn(on: Boolean): String
    fun setLockdown(on: Boolean): String
    /** The supervisor's last sentence: "reconnected", "retrying shortly", "disconnected by you ...". */
    fun supervisorNote(): String
    /** #1928 The DNS servers Android resolves with now (the active network's; the tunnel's while it is up); empty when unknown. */
    fun dnsServers(): List<String> = emptyList()

    // ── tunnel state (WgState.backend.setState) ──
    fun connect(): String
    fun disconnect(): String
    fun reconnect(): String

    // ── interface fields (WireGuardPrefs) ──
    fun mtu(): String
    fun setMtu(v: String): String
    fun keepalive(): String
    /** Persistent keepalive for EVERY peer. */
    fun setKeepalive(v: String): String
    fun setTunnelName(v: String): String
    fun setAddress(v: String): String
    fun setListenPort(v: String): String
    fun hasPrivateKey(): Boolean
    fun setPrivateKey(v: String): String
    /** A fresh key pair stored as this device's; returns its public half. */
    fun generateKey(): String
    fun excludedApps(): List<String>
    fun setExcludedApps(v: List<String>): String
    /** Launchable apps as (package, label), for the exclusion picker's suggestions. */
    fun installedApps(): List<Pair<String, String>>

    // ── peers ──
    fun addPeer(): String
    fun removePeer(index: Int): String
    fun updatePeer(index: Int, p: PeerCfg): String

    // ── DNS (FleetDns) ──
    fun dnsPreset(): String
    /** Choose the preset and, with the tunnel up, re-apply it; returns what Android now resolves with. */
    fun setDnsPreset(id: String): String
    /** (id, label) of every declared preset, with the reason when one cannot be chosen. */
    fun dnsChoices(): List<Triple<String, String, String>>

    // ── provider + profiles ──
    fun provider(): String
    fun matchesCloudPreset(): Boolean
    fun applyCloudPreset(): String
    fun setProviderCustom(): String
    fun storedProfiles(): Map<String, String>
    fun activeProfile(): String
    /** Make stored profile [name] the tunnel; re-applies when the tunnel is up. */
    fun activateProfile(name: String): String
    /** Store the declared template [id] (this device's key spliced at parse time) and make it the tunnel. */
    fun installDeclared(id: String): String
    /** [conf] through the same parser the .conf import uses, then stored under [name]. */
    fun importText(name: String, conf: String): String

    // ── fallbacks (network/MeshTransport.kt: pinned IPs, DoH, the TLS-443 relay) ──
    /** Which way the tunnel reaches its hubs, as the last connect chose it. */
    fun transport(): TransportView
    /** auto | direct | relay; applies at the next connect. */
    fun setTransportMode(v: String): String
    fun hasRelayKey(): Boolean
    /** Store the relay's key (blank clears it); never echoed back. */
    fun setRelayKey(v: String): String
    /** Every rung probed on its own, in ladder order: Direct UDP, system DNS, pinned IP, DoH, TLS-443 relay. */
    fun testFallbacks(): List<ProbeLine>

    // ── #1074 Cloudflare WARP / One (network/CloudflareWarp.kt; keys only in the encrypted CfKeyStore) ──
    fun cloudflare(): CfView
    /** Register this device with the WARP API when needed, then make the WARP config the tunnel. */
    fun useWarp(): String
    /** Remove the WARP registration and its keys, and give the engine's key slot back to the mesh. */
    fun forgetWarp(): String
    /** The team login page for the Custom Tab. */
    fun oneLoginUrl(): String
    /** Finish Cloudflare One enrolment with the Access token the team login returned. */
    fun enrolOne(token: String): String
}

/** What the page needs from its host fragment: the Android surfaces a composable cannot open itself. */
interface MeshHost {
    /** Ask for VPN consent, then run [then]; the host owns the activity-result launcher. */
    fun requestConsent(intent: Intent, then: () -> Unit)
    fun copy(label: String, text: String)
    fun paste(): String
    fun openVpnSettings()
    fun openAccount()
    /** #1928 Open Configs > Network > DNS (the full Fleet DNS page). */
    fun openConfigsDns() {}
    fun importConf()
    fun exportConf()
    fun exportProfiles()
    /** #1074 Open [url] in a Custom Tab (the Cloudflare One team login). */
    fun openCustomTab(url: String)
    /** #1153 Save [text] as a file the owner picks (Storage Access Framework), suggested [name]. */
    fun exportText(name: String, text: String) {}
    /** #1845 Save [text] as a Markdown file the owner picks; a host without its own picker falls back to the text export. */
    fun exportMarkdown(name: String, text: String) = exportText(name, text)
}

/** #1074 What is registered with Cloudflare: [warp] a WARP device exists, [one] a Cloudflare One device exists, [active] the profile name that is the tunnel when it is a Cloudflare one. */
data class CfView(val warp: Boolean = false, val one: Boolean = false, val active: String = "")

/**
 * Which way the tunnel reaches its hubs. [path]: direct | pinned | doh | relay | none, "" before the first
 * connect; [detail] says how (the name resolved, the relay address and its source, or why nothing worked);
 * [relay] is the engine's relay legs, "" when none runs.
 */
data class TransportView(val mode: String, val path: String, val detail: String, val relay: String)

/** One rung of Test fallbacks ([id]: udp | dns | pinned | doh | relay); [ok] null = not testable right now, [detail] says why. */
data class ProbeLine(val id: String, val ok: Boolean?, val detail: String)

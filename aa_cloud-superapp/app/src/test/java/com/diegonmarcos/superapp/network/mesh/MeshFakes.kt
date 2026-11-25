package com.diegonmarcos.superapp.network.mesh

import android.content.Intent
import com.diegonmarcos.superapp.network.AlwaysOnVpn
import com.diegonmarcos.superapp.network.MeshIntent

/** A scripted [MeshPort]: the tunnel is a few fields, every write is recorded so a test sees what the page asked for. */
class FakePort(
    var engine: Boolean = true,
    var up: Boolean = false,
    var key: Boolean = true,
    var consent: Intent? = null,
) : MeshPort {
    val calls = mutableListOf<String>()
    var peers = mutableListOf(
        PeerCfg("gcp-proxy", "KEY-HUB", "", "35.0.0.1:443", "10.0.0.0/24", "25"),
        PeerCfg("oci-analytics", "KEY-OCI", "", "1.2.3.4:51821", "10.1.0.0/24, 2603:c026:c104:8f00:ff9b::/96", "25"),
    )
    var mtu = "1380"; var failWith: String? = null
    var pendingApply = false
    var stored = mutableMapOf<String, String>()
    var excluded = listOf<String>()
    var preset = "private_only"
    var hs = 1L

    private fun rec(s: String): String { calls += s; failWith?.let { error(it) }; return "ok:$s" }

    /** #1797 The DNS line the engine reports (Android's servers for the tunnel), settable to replay a phone. */
    var dnsInEffect = "Private only · 10.0.0.1"
    override fun config() = ConfigView("wg-mesh", "10.0.0.9/24", "PUBKEY", "10.0.0.1, 10.1.0.1", mtu, "", "", dnsInEffect, peers.toList())
    override fun engineInstalled() = engine
    override fun sample(nowMs: Long) = Sample(nowMs, engine, engine && up, if (engine && up) peers.map { PeerCounters(it.publicKey, 1000, 500, hs) } else emptyList())
    override fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long) = LatencyReading(nowMs, 12, peers.associate { it.publicKey to 20 })
    override fun pendingApply() = pendingApply
    override fun alwaysOn(): Boolean? = if (engine) false else null
    override fun lockdown(): Boolean? = if (engine) false else null
    override fun consentIntent() = consent
    // #1120 the intent, the start-up switches and Android's Always-on, scripted like the real port keeps them
    var intentP = MeshIntent.Policy()
    var channel = false; var aoOn = false; var lockdownOn = false; var note = ""
    override fun intent() = intentP
    override fun setStartup(which: String, on: Boolean) = rec("startup.$which=$on").also {
        intentP = when (which) { "boot" -> intentP.copy(onBoot = on); "wifi" -> intentP.copy(onWifi = on); else -> intentP.copy(onMobile = on) }
    }
    override fun alwaysOnView() = AlwaysOnVpn.View(
        if (engine) aoOn else null, if (engine) lockdownOn else null, channel,
        if (channel) "local-server" else "", if (channel) "" else AlwaysOnVpn.NO_CHANNEL, if (aoOn) "engine.pkg" else null)
    override fun setAlwaysOn(on: Boolean) = rec("alwayson=$on").also { aoOn = on }
    override fun setLockdown(on: Boolean) = rec("lockdown=$on").also { lockdownOn = on }
    override fun supervisorNote() = note
    /** #1928 The servers Android resolves with, settable to replay a phone. */
    var dnsServers = listOf("10.0.0.1", "fd0c:1d00::1")
    override fun dnsServers() = dnsServers
    override fun connect() = rec("connect").also { up = true; intentP = intentP.copy(wanted = true) }
    override fun disconnect() = rec("disconnect").also { up = false; intentP = intentP.copy(wanted = false) }
    override fun reconnect() = rec("reconnect").also { up = true; intentP = intentP.copy(wanted = true) }
    override fun mtu() = mtu
    override fun setMtu(v: String) = rec("mtu=$v").also { mtu = v }
    override fun keepalive() = peers.firstOrNull()?.keepalive.orEmpty()
    override fun setKeepalive(v: String) = rec("keepalive=$v").also { peers = peers.map { it.copy(keepalive = v) }.toMutableList() }
    override fun setTunnelName(v: String) = rec("name=$v")
    override fun setAddress(v: String) = rec("address=$v")
    override fun setListenPort(v: String) = rec("port=$v")
    override fun hasPrivateKey() = key
    override fun setPrivateKey(v: String) = rec("key").also { key = true }
    override fun generateKey() = rec("genkey").also { key = true }
    override fun excludedApps() = excluded
    override fun setExcludedApps(v: List<String>) = rec("excluded=${v.joinToString(",")}").also { excluded = v }
    override fun installedApps() = listOf("com.example.a" to "Alpha", "org.b" to "Beta")
    override fun addPeer() = rec("peer.add").also { peers.add(PeerCfg("", "", "", "", "", "")) }
    override fun removePeer(index: Int) = rec("peer.remove=$index").also { peers.removeAt(index) }
    override fun updatePeer(index: Int, p: PeerCfg) = rec("peer.update=$index").also { peers[index] = p }
    override fun dnsPreset() = preset
    override fun setDnsPreset(id: String) = rec("dns=$id").also { preset = id }
    override fun dnsChoices() = listOf(Triple("private_only", "Private only", ""), Triple("warp", "WARP", "needs a second upstream"))
    override fun provider() = "cloud"
    override fun matchesCloudPreset() = true
    override fun applyCloudPreset() = rec("cloud")
    override fun setProviderCustom() = rec("custom")
    override fun storedProfiles(): Map<String, String> = stored
    override fun activeProfile() = ""
    override fun activateProfile(name: String) = rec("activate=$name")
    override fun installDeclared(id: String) = rec("install=$id")
    override fun importText(name: String, conf: String) = rec("import=$name")
    var mode = "auto"; var relayKey = false; var path = ""
    override fun transport() = TransportView(mode, path, if (path.isBlank()) "" else "35.0.0.1:443", "")
    override fun setTransportMode(v: String) = rec("mode=$v").also { mode = v }
    override fun hasRelayKey() = relayKey
    override fun setRelayKey(v: String) = rec("relaykey").also { relayKey = v.isNotBlank() }
    override fun testFallbacks(): List<ProbeLine> {
        rec("test")
        return listOf(ProbeLine("udp", true, "handshake 4 s ago"), ProbeLine("dns", false, "answered 10.9.9.9"), ProbeLine("relay", null, "engine too old"))
    }
    var cfWarp = false; var cfOne = false; var cfActive = ""
    override fun cloudflare() = CfView(cfWarp, cfOne, cfActive)
    override fun useWarp() = rec("warp.use").also { cfWarp = true; cfActive = "wg-cloudflare-warp" }
    override fun forgetWarp() = rec("warp.forget").also { cfWarp = false }
    override fun oneLoginUrl() = "https://diegonmarcos.cloudflareaccess.com/warp"
    override fun enrolOne(token: String) = rec("one.enrol=$token").also { cfOne = true }
}

class FakeHost : MeshHost {
    val events = mutableListOf<String>()
    var clip = ""
    var consentThen: (() -> Unit)? = null
    override fun requestConsent(intent: Intent, then: () -> Unit) { events += "consent"; consentThen = then }
    override fun copy(label: String, text: String) { events += "copy:$label"; clip = text }
    override fun paste() = clip
    override fun openVpnSettings() { events += "vpn" }
    override fun openAccount() { events += "account" }
    override fun openConfigsDns() { events += "configsDns" }
    override fun importConf() { events += "importConf" }
    override fun exportConf() { events += "exportConf" }
    override fun exportProfiles() { events += "exportProfiles" }
    override fun openCustomTab(url: String) { events += "tab:$url" }
    var exported: Pair<String, String>? = null
    override fun exportText(name: String, text: String) { events += "export:$name"; exported = name to text }
}

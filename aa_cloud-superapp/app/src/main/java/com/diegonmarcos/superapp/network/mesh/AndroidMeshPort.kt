package com.diegonmarcos.superapp.network.mesh

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.firewall.FirewallController
import com.diegonmarcos.superapp.network.AccountMesh
import com.diegonmarcos.superapp.network.CfKeyStore
import com.diegonmarcos.superapp.network.CloudflareWarp
import com.diegonmarcos.superapp.network.AlwaysOnVpn
import com.diegonmarcos.superapp.network.FleetDns
import com.diegonmarcos.superapp.network.MeshIntent
import com.diegonmarcos.superapp.network.MeshSupervisorRunner
import com.diegonmarcos.superapp.network.MeshTransport
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.network.WireGuardProfiles
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.BufferedReader
import java.io.StringReader

/**
 * #877 [MeshPort] over the paths the app already has: [WgState.backend] (the engine's binder client),
 * [WireGuardPrefs], [AccountMesh] and [FleetDns]. It adds none of its own; every connect brings the
 * tunnel up with [WireGuardPrefs.toTunnelConfig], the config the DNS page and the device controls use.
 */
class AndroidMeshPort(context: Context) : MeshPort {

    private val ctx: Context = context.applicationContext
    private val prefs get() = WgState.prefs(ctx)
    private val backend get() = WgState.backend(ctx)

    @Volatile private var lastUp = false
    /** Hash of the config the tunnel was last brought up with by THIS process; null = unknown. */
    @Volatile private var applied: Int? = null
    private var keyCache: Pair<String, String>? = null
    private var dnsText = ""
    private var dnsAtNs = 0L

    // ── reads ──

    private fun derivedKey(): String {
        val priv = prefs.interfacePrivateKey
        keyCache?.takeIf { it.first == priv }?.let { return it.second }
        return prefs.derivedInterfacePublicKey().also { keyCache = priv to it }
    }

    private fun dnsInEffect(): String {
        val now = System.nanoTime()
        if (dnsText.isEmpty() || now - dnsAtNs > DNS_TTL_NS) {
            dnsText = runCatching {
                val d = FleetDns.decl
                val label = FleetDns.effective(d, FleetDns.Prefs(ctx).preset).label
                "$label · ${FleetDns.resolverSummary(ctx)}"
            }.getOrDefault("")
            dnsAtNs = now
        }
        return dnsText
    }

    private fun PeerCfg.toData() = WireGuardPrefs.PeerData(name, publicKey, presharedKey, endpoint, allowedIps, keepalive)

    override fun config(): ConfigView = ConfigView(
        tunnelName = prefs.tunnelName,
        address = prefs.interfaceAddress,
        publicKey = derivedKey(),
        dns = prefs.interfaceDns,
        mtu = prefs.interfaceMtu,
        listenPort = prefs.interfaceListenPort,
        activeProfile = prefs.activeProfile,
        dnsInEffect = dnsInEffect(),
        peers = prefs.peers().map { PeerCfg(it.name, it.publicKey, it.presharedKey, it.endpoint, it.allowedIps, it.persistentKeepalive) },
    )

    override fun engineInstalled(): Boolean = runCatching { backend.isEngineInstalled() }.getOrDefault(false)

    override fun sample(nowMs: Long): Sample {
        val engine = engineInstalled()
        val up = engine && runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        lastUp = up
        if (!up) applied = null
        val stats = if (up) runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull() else null
        val peers = prefs.peers().mapNotNull { p ->
            val live = runCatching { stats?.peer(Key.fromBase64(p.publicKey)) }.getOrNull() ?: return@mapNotNull null
            PeerCounters(p.publicKey, live.rxBytes(), live.txBytes(), live.latestHandshakeEpochMillis())
        }
        return Sample(nowMs, engine, up, peers)
    }

    override fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long): LatencyReading {
        val resolvers = FleetDns.splitServers(cfg.dns)
        val name = FleetDns.decl.testMesh.ifBlank { "example.org" }
        val seen = HashMap<String, Int?>()
        fun rtt(ip: String): Int? = seen.getOrPut(ip) {
            runCatching {
                val t = System.nanoTime()
                FleetDns.query(ip, name, timeoutMs)
                ((System.nanoTime() - t) / 1_000_000).toInt()
            }.getOrNull()
        }
        val hub = resolvers.firstOrNull()?.let(::rtt)
        return LatencyReading(nowMs, hub, LatencyTargets.forPeers(cfg.peers, resolvers).mapValues { rtt(it.value) })
    }

    private fun sig(): Int? = runCatching { prefs.toTunnelConfig().toWgQuickString().hashCode() }.getOrNull()

    override fun pendingApply(): Boolean = lastUp && applied != null && sig() != applied

    override fun alwaysOn(): Boolean? = if (!engineInstalled()) null else runCatching { backend.isAlwaysOn }.getOrNull()
    override fun lockdown(): Boolean? = if (!engineInstalled()) null else runCatching { backend.isLockdownEnabled }.getOrNull()
    override fun intent(): MeshIntent.Policy = MeshIntent.policy(ctx)
    override fun setStartup(which: String, on: Boolean): String {
        when (which) {
            "boot" -> MeshIntent.setOnBoot(ctx, on)
            "wifi" -> MeshIntent.setOnWifi(ctx, on)
            "mobile" -> MeshIntent.setOnMobile(ctx, on)
            else -> error("unknown start-up switch $which")
        }
        MeshSupervisorRunner.kick(ctx)
        return "start-up $which ${if (on) "on" else "off"}"
    }
    /** Read at most every [AO_TTL_NS]: the page polls every second and the read may go through the shell channel. */
    private var aoView: AlwaysOnVpn.View? = null
    private var aoAtNs = 0L
    @Synchronized override fun alwaysOnView(): AlwaysOnVpn.View {
        val now = System.nanoTime()
        aoView?.takeIf { now - aoAtNs < AO_TTL_NS }?.let { return it }
        return AlwaysOnVpn.view(ctx, alwaysOn()).also { aoView = it; aoAtNs = now }
    }
    override fun setAlwaysOn(on: Boolean): String = try { AlwaysOnVpn.write(ctx, on) } finally { aoView = null }
    override fun setLockdown(on: Boolean): String = try { AlwaysOnVpn.writeLockdown(ctx, on) } finally { aoView = null }
    override fun supervisorNote(): String = MeshSupervisorRunner.lastNote(ctx)
    override fun consentIntent(): Intent? = runCatching { backend.consentIntent() }.getOrNull()

    // ── tunnel state ──

    private fun requireEngine() {
        check(backend.isEngineInstalled()) {
            "WireGuard engine not installed - install Cloud-Lib-Net-Wg from Configs > Store > Cloud Constellation > Libs"
        }
    }

    private fun bringUp(): Tunnel.State {
        val cfg = prefs.toTunnelConfig()
        val r = backend.setState(WgState.tunnel, Tunnel.State.UP, cfg)
        prefs.tunnelEnabled = r == Tunnel.State.UP
        applied = if (r == Tunnel.State.UP) cfg.toWgQuickString().hashCode() else null
        lastUp = r == Tunnel.State.UP
        return r
    }

    /** Up the first way that handshakes (MeshTransport: Direct UDP, then the TLS-443 relay); each try is bringUp(). */
    override fun connect(): String {
        requireEngine()
        MeshIntent.setWanted(ctx, true) // #1120 Connect re-arms the auto-reconnect
        val path = MeshTransport.connect(ctx) { bringUp() }
        check(lastUp) { "the engine did not bring the tunnel up" }
        return "connected: ${prefs.tunnelName} · $path"
    }

    override fun disconnect(): String {
        requireEngine()
        MeshIntent.setWanted(ctx, false) // #1120 Disconnect is sticky: nothing reconnects until Connect
        backend.setState(WgState.tunnel, Tunnel.State.DOWN, null)
        MeshTransport.release(ctx)
        prefs.tunnelEnabled = false
        applied = null
        lastUp = false
        return "disconnected"
    }

    override fun reconnect(): String {
        requireEngine()
        MeshIntent.setWanted(ctx, true)
        backend.setState(WgState.tunnel, Tunnel.State.DOWN, null)
        val path = MeshTransport.connect(ctx) { bringUp() }
        check(lastUp) { "the engine did not bring the tunnel up after the restart" }
        return "reconnected: ${prefs.tunnelName} · $path"
    }

    /** With the tunnel up, bring it up again on the new config; otherwise the value waits for the next connect. */
    private fun reapplyIfUp(done: String): String {
        if (!lastUp) return "$done (applies at the next connect)"
        return runCatching { bringUp(); "$done (applied)" }.getOrElse { "$done, but re-applying failed: ${it.message}" }
    }

    // ── interface fields ──

    override fun mtu() = prefs.interfaceMtu

    override fun setMtu(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 1280..1500) { "MTU must be 1280-1500 (or empty for the engine default)" }
        prefs.interfaceMtu = t
        return "MTU ${t.ifEmpty { "default" }} saved"
    }

    override fun keepalive(): String = prefs.peers().firstOrNull()?.persistentKeepalive.orEmpty()

    override fun setKeepalive(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 0..65535) { "keepalive must be 0-65535 seconds" }
        prefs.savePeers(prefs.peers().map { it.copy(persistentKeepalive = t) })
        return "keepalive ${t.ifEmpty { "off" }}s on every peer saved"
    }

    override fun setTunnelName(v: String): String {
        val t = v.trim()
        require(TUNNEL_NAME.matches(t)) { "a tunnel name is 1-${Tunnel.NAME_MAX_LENGTH} of A-Z a-z 0-9 _ = + . -" }
        prefs.tunnelName = t
        return "tunnel name $t saved"
    }

    override fun setAddress(v: String): String {
        require(v.isNotBlank()) { "an interface address is required" }
        prefs.interfaceAddress = v.trim()
        return "address saved"
    }

    override fun setListenPort(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 1..65535) { "listen port must be 1-65535 (or empty)" }
        prefs.interfaceListenPort = t
        return "listen port ${t.ifEmpty { "automatic" }} saved"
    }

    override fun hasPrivateKey() = prefs.interfacePrivateKey.isNotBlank()

    override fun setPrivateKey(v: String): String {
        val t = v.trim()
        require(runCatching { Key.fromBase64(t); t.length == 44 }.getOrDefault(false)) { "not a base64 32-byte key" }
        prefs.interfacePrivateKey = t
        return "private key stored; public half ${MeshFormat.shortKey(derivedKey())}"
    }

    override fun generateKey(): String {
        val pub = prefs.generateInterfaceKeyPair()
        return pub
    }

    override fun excludedApps() = prefs.excludedApps

    override fun setExcludedApps(v: List<String>): String {
        prefs.excludedApps = v
        return reapplyIfUp("${prefs.excludedApps.size} app(s) outside the tunnel")
    }

    override fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ── peers ──

    override fun addPeer(): String {
        prefs.savePeers(prefs.peers() + WireGuardPrefs.PeerData.EMPTY)
        return "empty peer added - fill it in"
    }

    override fun removePeer(index: Int): String {
        val l = prefs.peers()
        require(index in l.indices) { "no such peer" }
        val gone = l.removeAt(index)
        prefs.savePeers(l)
        return "removed ${gone.name.ifBlank { "peer ${index + 1}" }}"
    }

    override fun updatePeer(index: Int, p: PeerCfg): String {
        val l = prefs.peers()
        require(index in l.indices) { "no such peer" }
        l[index] = p.toData()
        prefs.savePeers(l)
        return "peer saved"
    }

    // ── DNS ──

    override fun dnsPreset(): String = FleetDns.Prefs(ctx).preset

    override fun setDnsPreset(id: String): String {
        val preset = FleetDns.decl.preset(id) ?: error("undeclared DNS preset $id")
        check(preset.available) { preset.unavailableReason.ifBlank { "${preset.label} is not available" } }
        FleetDns.Prefs(ctx).preset = id
        val up = FleetDns.meshUp(ctx)
        if (up) bringUp()
        val v = FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))
        dnsAtNs = 0L
        return if (v.ok) "${preset.label}: in effect - ${v.actual.joinToString(", ")}" else "${preset.label} saved - ${v.why}"
    }

    override fun dnsChoices(): List<Triple<String, String, String>> =
        FleetDns.decl.presets.map { Triple(it.id, it.label, if (it.available) "" else it.unavailableReason) }

    // ── provider + profiles ──

    override fun provider() = prefs.configProvider
    override fun matchesCloudPreset() = prefs.matchesCloudPreset()

    override fun applyCloudPreset(): String {
        // #573 the baked preset is ONE device's identity (its Address line). A phone the Account
        // declares as another device keeps its own: the preset is refused, never re-identifies it.
        // ponytail: a guard; per-device baked presets when a second phone wants the Cloud provider.
        declaredAddresses()?.let { mine ->
            val baked = BuildConfig.UI_WG_INTERFACE_ADDRESS.split(',').map { it.trim().substringBefore('/') }
            if (baked.none { it in mine })
                return "✗ Cloud preset is ${BuildConfig.UI_WG_INTERFACE_ADDRESS} - this phone is declared as ${mine.joinToString(", ")}; apply its own profiles from Account › Mesh"
        }
        prefs.applyCloudPreset()
        prefs.configProvider = WireGuardPrefs.PROVIDER_CLOUD
        return "Cloud preset applied - this device still needs its own private key"
    }

    /** The EXPLICITLY picked device's declared wg addresses; null when nothing is picked or no
     *  bundle is landed (the live tunnel is never consulted: it is what this guard protects). */
    private fun declaredAddresses(): Set<String>? {
        // #1693 the picked device lives in Cloud Account: asked over its provider (null = no answer, guard idle).
        return com.diegonmarcos.superapp.fleetendpoints.android.AccountClient.meshDeviceAddresses(ctx)
    }

    override fun setProviderCustom(): String {
        prefs.configProvider = WireGuardPrefs.PROVIDER_CUSTOM
        return "Provider: Custom - your WireGuard settings are untouched"
    }

    override fun storedProfiles() = prefs.profiles()
    override fun activeProfile() = prefs.activeProfile

    override fun activateProfile(name: String): String {
        runCatching { prefs.leaveCloudflare(cfKeys) }
        val cfg = prefs.activateProfile(name)
        return reapplyIfUp("$name is the tunnel: ${cfg.peers.size} peers")
    }

    override fun installDeclared(id: String): String {
        val prof = WireGuardProfiles.byId(id) ?: error("no declared profile $id")
        runCatching { prefs.leaveCloudflare(cfKeys) }
        val key = prefs.interfacePrivateKey
        check(key.isNotBlank()) { "no private key on this device - generate or paste one in Controls first" }
        val text = WireGuardProfiles.render(prof).replace(Regex("(?m)^PrivateKey = *$"), "PrivateKey = ${WireGuardPrefs.PROVIDED_BY_DEVICE}")
        runCatching { Config.parse(BufferedReader(StringReader(text.replace(WireGuardPrefs.PROVIDED_BY_DEVICE, key)))) }
            .onFailure { error("${prof.fileName} is a template the WireGuard parser rejects (${it.message}) - import your own .conf for it") }
        val name = prof.fileName.removeSuffix(".conf")
        prefs.saveProfiles(prefs.profiles() + (name to text))
        return activateProfile(name)
    }

    // ── fallbacks ──

    override fun transport(): TransportView {
        val p = MeshTransport.current(ctx)
        return TransportView(prefs.transportMode, p?.path?.id.orEmpty(), p?.detail.orEmpty(), runCatching { MeshTransport.relayLine(ctx) }.getOrDefault(""))
    }

    override fun setTransportMode(v: String): String {
        require(v in setOf(MeshTransport.MODE_AUTO, MeshTransport.MODE_DIRECT, MeshTransport.MODE_RELAY)) { "a path is auto, direct or relay" }
        prefs.transportMode = v
        return "path $v saved - applies at the next connect"
    }

    override fun hasRelayKey() = prefs.relayKey.isNotBlank()

    override fun setRelayKey(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || Regex("[A-Za-z0-9_.~-]{8,128}").matches(t)) { "a relay key is 8-128 URL-safe characters (the server's path prefix)" }
        prefs.relayKey = t
        return if (t.isEmpty()) "relay key cleared" else "relay key stored (${t.length} characters) - Test fallbacks checks it against the relay"
    }

    override fun testFallbacks(): List<ProbeLine> = MeshTransport.test(ctx).map { ProbeLine(it.id, it.ok, it.detail) }

    override fun importText(name: String, conf: String): String {
        val r = AccountMesh.applyMesh(prefs, name, conf)
        check(r.startsWith("✓")) { r }
        prefs.saveProfiles(prefs.profiles() + (name to conf))
        prefs.activeProfile = name
        return reapplyIfUp(r)
    }

    // ── #1074 Cloudflare WARP / One ──

    private val cfDecl get() = MeshDecl.baked.cloudflare
    private val cfKeys by lazy { CfKeyStore(ctx) }
    private val cfClient by lazy { CloudflareWarp.Client(cfDecl, CloudflareWarp.UrlHttp, cfKeys) }

    override fun cloudflare() = CfView(
        warp = cfKeys.warpRegistration() != null, one = cfKeys.oneRegistration() != null,
        active = prefs.activeProfile.takeIf { it == cfDecl.warp.profileName || it == cfDecl.one.profileName }.orEmpty(),
    )

    override fun useWarp(): String {
        val (priv, reg) = cfClient.warp()
        val name = cfDecl.warp.profileName
        val cfg = prefs.applyCloudflare(name, CloudflareWarp.warpConf(cfDecl, priv, reg), cfKeys)
        return reapplyIfUp("$name is the tunnel: WARP device ${reg.id.take(8)}, ${cfg.peers.size} peer")
    }

    override fun forgetWarp(): String = cfClient.forgetWarp() + "; pick a mesh profile to leave WARP"

    override fun oneLoginUrl() = CloudflareWarp.oneLoginUrl(cfDecl)

    override fun enrolOne(token: String): String {
        val (priv, reg) = cfClient.enrolOne(token)
        val name = cfDecl.one.profileName
        val cfg = prefs.applyCloudflare(name, CloudflareWarp.oneConf(cfDecl, priv, reg), cfKeys)
        return reapplyIfUp("$name is the tunnel: team ${cfDecl.one.team}, ${cfg.peers.size} peer")
    }

    private companion object {
        const val DNS_TTL_NS = 5_000_000_000L
        const val AO_TTL_NS = 10_000_000_000L
        val TUNNEL_NAME = Regex("[A-Za-z0-9_=+.-]{1,${Tunnel.NAME_MAX_LENGTH}}")
    }
}

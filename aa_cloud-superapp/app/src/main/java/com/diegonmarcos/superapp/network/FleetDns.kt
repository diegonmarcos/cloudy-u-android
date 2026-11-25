package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.net.AidlBackend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * #740 The fleet's DNS — ONE choice, applied ONCE, at the SuperApp's VPN.
 *
 * Every fleet app and engine resolves through the Android system resolver (no
 * app names a DNS server of its own), so whatever DNS servers the VPN carries
 * are what the whole fleet uses. The VPN that is actually running is the Cloud
 * Mesh tunnel (net-wg GoBackend → VpnService.Builder.addDnsServer); firestack
 * is compiled but not yet the active engine. So the choice reaches the phone
 * through [WireGuardPrefs.toTunnelConfig], which every connect path calls.
 *
 * #751 With the mesh DOWN the same engine still owns the slot: it carries an
 * explicit choice as a peerless tunnel ([meshDownConfig], pushed by
 * [syncMeshDown]) and raises it whenever the mesh goes down, so the fleet —
 * every app on the system resolver — keeps the chosen resolver either way.
 *
 * The presets, Android's Private DNS menu, the mesh zones and the test names
 * are `build.json::ui.dns`, baked as BuildConfig.UI_DNS_B64 — no resolver
 * address is written here. [vpnServers] and [upstreamsFor] are pure so the
 * mapping is asserted in FleetDnsTest against the declaration this build ships.
 */
object FleetDns {

    data class Preset(
        val id: String,
        val label: String,
        val subtitle: String,
        val kind: String,
        val servers: List<String>,
        val fallback: List<String>,
        val available: Boolean,
        val unavailableReason: String,
        val fallbackAllowed: Boolean,
        val fallbackChoices: List<String>,
        val defaultFallbacks: List<String>,
        val encryption: String,
        val tlsHostnames: List<String>,
    )

    data class Decl(
        val defaultPreset: String,
        val meshZones: List<String>,
        val testPublic: String,
        val testMesh: String,
        val timeoutMs: Int,
        val meshDownTunnel: String,
        val meshDownAddresses: List<String>,
        val androidModes: List<Pair<String, String>>,
        val hostnameSuggestions: List<String>,
        /** #794 the Android Private DNS mode a private preset needs (ui.dns.android_private_dns.private_presets_require):
         *  Automatic sends every lookup over DoT to a validated public server BEFORE any plain server is asked. */
        val privatePresetsRequire: String = "",
        val presets: List<Preset>,
        val selfResolvers: List<SelfResolver> = emptyList(),
        /** #1899 the reachability test by IP (ui.dns.reachability): its targets, checks and timeout. */
        val reach: DnsReach.Decl = DnsReach.Decl(),
    ) {
        fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }
        fun modeLabel(mode: String?): String = androidModes.firstOrNull { it.first == mode }?.second ?: (mode ?: "unset")
    }

    /** #794 fleet code that talks DNS itself (ui.dns.self_resolvers): flagged on the DNS page. */
    data class SelfResolver(val pkg: String, val what: String, val how: String)

    const val KIND_MIRROR = "mirror"
    const val KIND_PUBLIC = "public"
    const val KIND_PRIVATE = "private"

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun parse(json: String): Decl {
        val o = JSONObject(json)
        val tn = o.optJSONObject("test_names") ?: JSONObject()
        val apd = o.optJSONObject("android_private_dns") ?: JSONObject()
        val modes = apd.optJSONArray("modes") ?: JSONArray()
        val ps = o.getJSONArray("presets")
        val md = o.getJSONObject("mesh_down")
        val sr = o.optJSONArray("self_resolvers") ?: JSONArray()
        return Decl(
            defaultPreset = o.getString("default_preset"),
            meshZones = o.optJSONArray("mesh_zones").strings(),
            testPublic = tn.optString("public"),
            testMesh = tn.optString("mesh"),
            timeoutMs = o.optInt("lookup_timeout_ms", 2500),
            meshDownTunnel = md.getString("tunnel_name"),
            meshDownAddresses = md.getJSONArray("addresses").strings(),
            androidModes = (0 until modes.length()).map {
                val m = modes.getJSONObject(it); m.getString("id") to m.getString("label")
            },
            hostnameSuggestions = apd.optJSONArray("hostname_suggestions").strings(),
            privatePresetsRequire = apd.optString("private_presets_require"),
            presets = (0 until ps.length()).map {
                val p = ps.getJSONObject(it)
                Preset(
                    id = p.getString("id"),
                    label = p.getString("label"),
                    subtitle = p.optString("subtitle"),
                    kind = p.getString("kind"),
                    servers = p.optJSONArray("servers").strings(),
                    fallback = p.optJSONArray("fallback").strings(),
                    available = p.optBoolean("available", true),
                    unavailableReason = p.optString("unavailable_reason"),
                    fallbackAllowed = p.optBoolean("fallback_allowed", false),
                    fallbackChoices = p.optJSONArray("fallback_choices").strings(),
                    defaultFallbacks = p.optJSONArray("default_fallbacks").strings(),
                    encryption = p.optString("encryption"),
                    tlsHostnames = p.optJSONArray("tls_hostnames").strings(),
                )
            },
            selfResolvers = (0 until sr.length()).map {
                val r = sr.getJSONObject(it); SelfResolver(r.getString("pkg"), r.getString("what"), r.getString("how"))
            },
            reach = DnsReach.parse(o.optJSONObject("reachability")),
        )
    }

    val decl: Decl by lazy { parse(String(Base64.decode(BuildConfig.UI_DNS_B64, Base64.DEFAULT))) }

    /** The preset in force: the stored id when it is declared and usable, else the declared default. */
    fun effective(d: Decl, presetId: String?): Preset =
        d.preset(presetId)?.takeIf { it.available } ?: d.preset(d.defaultPreset)!!

    /** "10.0.0.1, 10.1.0.1" → [10.0.0.1, 10.1.0.1] — the Cloud Mesh page's DNS field. */
    fun splitServers(csv: String): List<String> = csv.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The DNS server list the VPN carries for [presetId] while the mesh is up.
     *
     * #1797 While a mesh tunnel is up the VPN carries the fleet resolvers ONLY, whatever the preset.
     * #1152 put them first and kept the preset's public servers after them, assuming Android asks in
     * order. It does not. With Private DNS on Automatic, Android sends every lookup over DoT to the
     * servers that pass DoT validation (1.1.1.1 and 8.8.8.8 do, the hubs do not), so the fleet
     * resolver is never asked. With Private DNS Off it ranks servers by measured latency, and an
     * anycast public resolver beats a hub reached through the tunnel. In both cases the public
     * NXDOMAIN for the mesh zone is a final answer and no other server is tried, so every
     * `*.mesh.diegonmarcos.com` name failed with "No address associated with hostname" while the
     * tunnel and both handshakes were up. The hub resolvers forward every non-mesh name upstream
     * (hickory's root forwarder, and unbound to it), so public names still resolve. The preset's
     * own servers apply while the mesh is down ([meshDownServers]). Mirror with no fleet resolver
     * stays empty (Android's own); a private preset with none still throws.
     */
    fun vpnServers(d: Decl, presetId: String?, fallbacks: List<String>, fleetResolvers: List<String>, meshHealthy: Boolean = true): List<String> {
        val own = presetServers(d, presetId, fallbacks, fleetResolvers) // validates the kind; a private preset with no fleet resolver throws
        // #1924 A Public preset IS its declared list (never a mesh address: with the hub down the fleet resolver answered
        // SERVFAIL for every name while 1.1.1.1 / 8.8.8.8 were reachable inside the VPN). A Private preset carries the fleet
        // resolvers while they are healthy and its fallbacks (the mesh-down list) once they are not.
        return when (effective(d, presetId).kind) {
            KIND_PUBLIC -> own
            KIND_PRIVATE -> if (meshHealthy) fleetResolvers else meshDownServers(d, presetId, fallbacks, fleetResolvers)
            else -> if (fleetResolvers.isNotEmpty()) fleetResolvers else own
        }.distinct()
    }

    /**
     * #1924 The ordered upstreams for one lookup of [name]. Mesh names go to the fleet resolver (nothing else can answer
     * them); everything else goes to the preset's own servers, and a Private preset asks the fleet resolver first only while
     * [meshHealthy], its fallbacks first otherwise. The bridge walks this list, so SERVFAIL / REFUSED / a timeout from the
     * fleet resolver moves on to the next entry at once.
     */
    fun queryPlan(d: Decl, name: String, meshUp: Boolean, meshHealthy: Boolean, presetId: String?, fallbacks: List<String>,
                  chosen: Boolean, fleet: List<String>): List<String> {
        if (!meshUp) return if (chosen) meshDownServers(d, presetId, fallbacks, fleet) else emptyList()
        if (fleet.isNotEmpty() && isMeshName(d, name)) return fleet
        val own = presetServers(d, presetId, fallbacks, fleet)
        return when (effective(d, presetId).kind) {
            KIND_PRIVATE -> { val fb = own.filter { it !in fleet }; if (meshHealthy) fleet + fb else fb + fleet }
            KIND_PUBLIC -> own
            else -> if (fleet.isNotEmpty()) fleet else own
        }.distinct()
    }

    /** #1924 SERVFAIL (2), REFUSED (5) or no answer at all (null = timeout) from the mesh resolver means it is unhealthy. */
    fun meshFailure(rcode: Int?): Boolean = rcode == null || rcode == 2 || rcode == 5

    /**
     * #1924 Health of the mesh resolver as the lookups see it. [report] flips [healthy] at once (the bridge's routes read it);
     * the VPN's advertised DNS is re-applied through [onSettled] only after the verdict has held for [debounceMs], so a flapping
     * resolver does not rebuild the VPN on every query.
     */
    class MeshHealth(private val debounceMs: Long = 3_000, private val onSettled: (Boolean) -> Unit = {}) {
        @Volatile var healthy = true; private set
        @Volatile var reason = "no lookup yet"; private set
        /** What the VPN was last configured for. */
        @Volatile var applied = true; private set
        private var pending: java.util.concurrent.ScheduledFuture<*>? = null
        private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fleet-dns-health").apply { isDaemon = true } }
        @Synchronized fun report(ok: Boolean, why: String) {
            healthy = ok; reason = why
            pending?.cancel(false)
            pending = if (ok == applied) null else timer.schedule(Runnable {
                synchronized(this) { if (healthy != applied) { applied = healthy; runCatching { onSettled(applied) } } }
            }, debounceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        fun verdict(): String = if (healthy) "healthy" else "UNHEALTHY"
        fun text(): String = "Mesh resolver: ${verdict()} — $reason"
    }

    @Volatile private var appCtx: Context? = null
    val health = MeshHealth { _ -> appCtx?.let { reapplyVpnDns(it) } }

    /** Re-apply the tunnel config when the preset's advertised DNS differs between healthy and unhealthy. */
    private fun reapplyVpnDns(ctx: Context) {
        val p = Prefs(ctx); val fleet = fleetResolvers(ctx)
        val a = runCatching { vpnServers(decl, p.preset, p.fallbacks, fleet, true) to vpnServers(decl, p.preset, p.fallbacks, fleet, false) }.getOrNull() ?: return
        if (a.first == a.second || !meshUp(ctx)) return
        runCatching { WgState.backend(ctx).setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toTunnelConfig()) }
            .onFailure { android.util.Log.w("FleetDns", "re-apply after mesh health change failed", it) }
    }

    private val probeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wire the bridge's mesh-route outcomes to [health], and probe the fleet resolver while the mesh is up so recovery is seen too. */
    fun installHealth(ctx: Context) {
        appCtx = ctx.applicationContext
        FleetDnsBridge.meshObserver = { ok -> health.report(ok, if (ok) "answered the last lookup" else "SERVFAIL, REFUSED or timeout on the last lookup") }
        if (!probeStarted.compareAndSet(false, true)) return
        val t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fleet-dns-probe").apply { isDaemon = true } }
        t.scheduleWithFixedDelay({
            runCatching {
                if (!meshUp(ctx)) return@runCatching
                val fleet = fleetResolvers(ctx)
                if (fleet.isEmpty()) return@runCatching
                val r = fleet.map { s -> runCatching { query(s, decl.testPublic, decl.timeoutMs) }.fold({ it }, { "timeout" }) }
                val ok = r.any { !it.startsWith("rcode") && it != "timeout" }
                health.report(ok, if (ok) "probe: ${decl.testPublic} answered" else "probe: ${r.joinToString(", ")}")
            }
        }, 20, 20, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** #1797 The servers in [actual] that are not fleet resolvers: each one can answer a mesh name with a public NXDOMAIN. */
    fun nonFleet(actual: List<String>, fleetResolvers: List<String>): List<String> = actual.filter { it !in fleetResolvers }

    /** What the preset alone names (no fleet resolver forced in): the mesh-down list's base. */
    private fun presetServers(d: Decl, presetId: String?, fallbacks: List<String>, fleetResolvers: List<String>): List<String> {
        val p = effective(d, presetId)
        return when (p.kind) {
            KIND_MIRROR -> emptyList()
            KIND_PUBLIC -> p.servers + p.fallback
            KIND_PRIVATE -> {
                check(fleetResolvers.isNotEmpty()) {
                    "${p.label} needs the fleet resolver, but the Cloud Mesh DNS field is empty"
                }
                val extra = if (!p.fallbackAllowed) emptyList<String>() else fallbacks
                    .filter { it in p.fallbackChoices }
                    .mapNotNull { d.preset(it) }
                    .filter { it.available && it.kind == KIND_PUBLIC }
                    .flatMap { it.servers + it.fallback }
                fleetResolvers + extra
            }
            else -> error("preset ${p.id} has kind ${p.kind}, which maps to no upstream")
        }.distinct()
    }

    /**
     * #751 The servers the slot carries while Cloud Mesh is DOWN. The fleet
     * resolver sits behind the mesh, so it is dropped whenever anything else is
     * left (Private with fallbacks = its fallbacks alone); when nothing else is
     * (Private only) it stays and [meshDownConfig] routes it nowhere, so lookups
     * fail as the preset promises. Public presets and Mirror are unchanged.
     */
    fun meshDownServers(d: Decl, presetId: String?, fallbacks: List<String>, fleetResolvers: List<String>): List<String> {
        val all = presetServers(d, presetId, fallbacks, fleetResolvers)
        return all.filter { it !in fleetResolvers }.ifEmpty { all }
    }

    /**
     * #751 The peerless tunnel the engine raises while Cloud Mesh is down, or
     * null (Mirror: the slot is released). No peer = no route: only its DNS
     * servers reach the VPN, every other packet goes out the underlying network
     * as it does beside a split mesh. The one exception is a fleet resolver
     * left in [servers] (Private only): a key-only peer with no endpoint is its
     * route, so a query to it is dropped instead of reaching whatever answers
     * that address on the local network. Keys are throwaway: nothing answers.
     */
    fun meshDownConfig(d: Decl, servers: List<String>, fleetResolvers: List<String>, self: KeyPair, sink: KeyPair): Config? {
        if (servers.isEmpty()) return null
        val cfg = Config.Builder().setInterface(Interface.Builder()
            .setKeyPair(self)
            .parseAddresses(d.meshDownAddresses.joinToString(", "))
            .parseDnsServers(servers.joinToString(", "))
            .build())
        val unreachable = servers.filter { it in fleetResolvers }
        if (unreachable.isNotEmpty()) cfg.addPeer(Peer.Builder()
            .setPublicKey(sink.publicKey)
            .parseAllowedIPs(unreachable.joinToString(", ") { if (':' in it) "$it/128" else "$it/32" })
            .build())
        return cfg.build()
    }

    /** True when [name] sits in one of the fleet resolver's own zones (zone boundary, not a suffix match). */
    fun isMeshName(d: Decl, name: String): Boolean {
        val n = name.trimEnd('.').lowercase()
        return d.meshZones.any { z -> n == z || n.endsWith(".$z") }
    }

    /**
     * Split DNS: which upstreams answer [name]. Mesh names go to the fleet
     * resolver whenever the mesh is up, whatever the preset; everything else
     * goes to [presetServers] (empty = the system resolver, i.e. Mirror).
     */
    fun upstreamsFor(d: Decl, name: String, meshUp: Boolean, presetServers: List<String>, fleetResolvers: List<String>): List<String> =
        if (meshUp && fleetResolvers.isNotEmpty() && isMeshName(d, name)) fleetResolvers else presetServers

    // ── the user's choice ────────────────────────────────────────────────

    class Prefs(context: Context) {
        private val sp = context.applicationContext.getSharedPreferences("fleet_dns_prefs", Context.MODE_PRIVATE)
        var preset: String
            get() = sp.getString("preset", null) ?: FleetDns.decl.defaultPreset
            set(v) { sp.edit().putString("preset", v).apply() }
        /** #751 True once the owner picked a preset on the DNS page. Only a
         *  choice made there reaches a mesh-down phone: the declared default is
         *  Private only, and applying it unasked would leave a phone whose mesh
         *  is down with no DNS at all. */
        val chosen: Boolean get() = sp.contains("preset")
        /** #751 The mesh-down tunnel's two throwaway keys, kept so the same
         *  choice yields the same config and re-pushing it does not rebuild
         *  the VPN. */
        fun meshDownKeys(): Pair<KeyPair, KeyPair> {
            fun key(k: String) = KeyPair(Key.fromBase64(sp.getString(k, null) ?: KeyPair().privateKey.toBase64()
                .also { sp.edit().putString(k, it).apply() }))
            return key("mesh_down_self") to key("mesh_down_sink")
        }
        /** Ordered: the order the user ticked them in. */
        var fallbacks: List<String>
            get() = sp.getString("fallbacks", null)?.let { splitServers(it) }
                ?: FleetDns.decl.presets.firstOrNull { it.fallbackAllowed }?.defaultFallbacks.orEmpty()
            set(v) { sp.edit().putString("fallbacks", v.joinToString(",")).apply() }
        var lastLookup: String
            get() = sp.getString("last_lookup", "") ?: ""
            set(v) { sp.edit().putString("last_lookup", v).apply() }
    }

    /** What the tunnel carries right now for this phone's choice. */
    fun vpnServers(ctx: Context, fleetDnsCsv: String): List<String> {
        val p = Prefs(ctx)
        return vpnServers(decl, p.preset, p.fallbacks, splitServers(fleetDnsCsv), health.healthy)
    }

    /**
     * #751 Hand the engine what to carry while Cloud Mesh is down: the explicit
     * choice as [meshDownConfig], or nothing (Mirror, or no choice made yet).
     * [raiseNow] lets it take the slot at once; pass false while another VPN of
     * ours (the firewall) holds it — the engine then stores the choice and
     * raises it the next time the mesh goes down. Blocks on the engine binder:
     * call it off the main thread. Answers the engine's idle status.
     */
    fun syncMeshDown(ctx: Context, raiseNow: Boolean): String {
        val p = Prefs(ctx)
        val config = if (!p.chosen) null else {
            val fleet = splitServers(WgState.prefs(ctx).interfaceDns)
            val (self, sink) = p.meshDownKeys()
            meshDownConfig(decl, meshDownServers(decl, p.preset, p.fallbacks, fleet), fleet, self, sink)
        }
        return WgState.backend(ctx).setIdleTunnel(decl.meshDownTunnel, config, raiseNow)
    }

    /**
     * #874 The bridge's routes for [name]: the servers [promised] right now,
     * split for mesh names ([upstreamsFor]), each asked directly after Android's
     * resolver (which carries the same list while the VPN is in effect, DoT
     * upgraded); nothing promised = Mirror: Android's resolver on each
     * underlying network, then the uid's default.
     */
    fun bridgeRoutes(ctx: Context, name: String): List<FleetDnsBridge.Route> {
        installHealth(ctx)
        val p = Prefs(ctx); val up = meshUp(ctx); val fleet = fleetResolvers(ctx)
        val label = effective(decl, p.preset).label
        val servers = queryPlan(decl, name, up, health.healthy, p.preset, p.fallbacks, p.chosen, fleet)
        val android = FleetDnsBridge.Route("Android system resolver ($label)")
        return when {
            servers.isEmpty() -> FleetDnsBridge.mirror(label)
            // A mesh name while the mesh is up is the fleet resolver's to answer (hickory's split
            // horizon: vault.diegonmarcos.com → the gateway's mesh address). Android's resolver
            // races every server it holds and handed the PUBLIC address back first, so the
            // tunnel was never used for the fleet's own names. It stays only as the last
            // resort of a preset that allows fallbacks.
            up && fleet.isNotEmpty() && isMeshName(decl, name) ->
                servers.map { FleetDnsBridge.Route("$label $it", listOf(it), mesh = true) } +
                    (if (effective(decl, p.preset).fallbackAllowed) listOf(android) else emptyList())
            else -> listOf(android) + servers.map { FleetDnsBridge.Route("$label $it", listOf(it), mesh = it in fleet) }
        }
    }

    // ── #794 is the choice in effect? ────────────────────────────────────

    /**
     * #794 What the chosen preset promises Android resolves with right now: the
     * mesh tunnel's list while Cloud Mesh is up, the mesh-down list for an
     * explicit choice while it is down, else nothing (Android's own). This is
     * the promise, never what the engine happens to be carrying — the page
     * once showed the engine's state here and so called a preset that could
     * not start "Android's own (mirror)" while the radio said Public open.
     */
    fun promised(d: Decl, presetId: String?, fallbacks: List<String>, chosen: Boolean, fleet: List<String>, meshUp: Boolean, meshHealthy: Boolean = true): List<String> = when {
        meshUp -> vpnServers(d, presetId, fallbacks, fleet, meshHealthy)
        chosen -> meshDownServers(d, presetId, fallbacks, fleet)
        else -> emptyList()
    }

    /** [ok] = Android resolves with what was [promised]; [needsConsent] = the one cause a tap fixes. */
    data class Verdict(
        val ok: Boolean,
        val needsConsent: Boolean,
        val promised: List<String>,
        val actual: List<String>,
        val why: String,
    )

    /**
     * #794 Compare the promise with what Android really hands this app (the
     * active network's DNS servers). Pure: the page, the alert and
     * /api/net/dns/overview all read this one verdict, and FleetDnsTest drives
     * it with phones it invents. [engine] is the label of the app that owns the
     * VPN slot, named in the consent message.
     */
    fun verdict(
        d: Decl, presetId: String?, fallbacks: List<String>, chosen: Boolean, fleet: List<String>,
        meshUp: Boolean, idle: String, android: AndroidDns, engine: String, meshHealthy: Boolean = true,
    ): Verdict {
        val p = effective(d, presetId)
        val actual = android.activeServers
        val needsConsent = !meshUp && chosen && p.kind != KIND_MIRROR &&
            idle.contains(BackendException.Reason.VPN_NOT_AUTHORIZED.name)
        val want = runCatching { promised(d, presetId, fallbacks, chosen, fleet, meshUp, meshHealthy) }.getOrElse {
            return Verdict(false, needsConsent, emptyList(), actual, "${p.label} cannot be applied: ${it.message}")
        }
        val now = actual.joinToString(", ").ifEmpty { "none" }
        if (want.isEmpty()) return Verdict(true, false, want, actual,
            "Android's own DNS (${if (android.onVpn) "VPN" else "network"} ${now})" +
                if (!meshUp && !chosen) " — no preset was chosen here, so none applies without the mesh" else "")
        fun bad(why: String) = Verdict(false, needsConsent, want, actual, why)
        return when {
            android.mode == "hostname" ->
                bad("Android's strict Private DNS (${android.specifier}) answers every lookup — ${p.label} is bypassed")
            p.kind == KIND_PRIVATE && d.privatePresetsRequire.isNotEmpty() && android.privateDnsActive == true &&
                android.mode != d.privatePresetsRequire ->
                bad("Android's Private DNS (${d.modeLabel(android.mode)}) is in use on the VPN: every lookup goes over DoT to a " +
                    "validated public server before the fleet resolver is asked — ${p.label} is bypassed. " +
                    "The device tuning sets it to ${d.modeLabel(d.privatePresetsRequire)}.")
            meshUp && fleet.isNotEmpty() && android.onVpn && want.all { it in fleet } && nonFleet(actual, fleet).isNotEmpty() ->
                bad("${p.label} is NOT in effect: with the mesh up Android also holds ${nonFleet(actual, fleet).joinToString(", ")}, " +
                    "which answers mesh names NXDOMAIN; Android asks whichever server it prefers, so private names fail. " +
                    "Reconnect so the tunnel carries the fleet resolvers only (${fleet.joinToString(", ")}).")
            android.onVpn && actual.toSet() == want.toSet() ->
                Verdict(true, false, want, actual, "${p.label} is in effect: Android resolves with $now")
            needsConsent ->
                bad("${p.label} is NOT in effect: $engine has no VPN permission, so its DNS-only tunnel cannot start. Android resolves with $now instead.")
            !meshUp && idle == "STANDBY" ->
                bad("${p.label} is NOT in effect: another tunnel of the engine holds the VPN slot. Android resolves with $now.")
            !meshUp && idle == "OFF" ->
                bad("${p.label} is NOT in effect: $engine was never handed the choice — pick the preset again. Android resolves with $now.")
            !meshUp && idle.startsWith("DOWN") ->
                bad("${p.label} is NOT in effect: the DNS-only tunnel is down (${idle.removePrefix("DOWN: ")}). Android resolves with $now.")
            else ->
                bad("${p.label} is NOT in effect: Android resolves with $now, the preset promises ${want.joinToString(", ")}.")
        }
    }

    fun meshUp(ctx: Context): Boolean =
        runCatching { WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)

    fun fleetResolvers(ctx: Context): List<String> = splitServers(WgState.prefs(ctx).interfaceDns)

    /** #899 The Store's last rung: the fleet resolvers over the mesh. Throws (naming why) when the tunnel is down. */
    fun meshResolve(ctx: Context, host: String): List<InetAddress> {
        if (!meshUp(ctx)) throw java.io.IOException("mesh down: the wg0 tunnel is not up")
        val servers = fleetResolvers(ctx)
        if (servers.isEmpty()) throw java.io.IOException("mesh up but no fleet resolver is configured")
        var last: Exception? = null
        for (s in servers) {
            try {
                val a = query(s, host, 2500)
                if (a.split('.').size == 4 && a.split('.').all { it.toIntOrNull() != null }) return listOf(InetAddress.getByName(a))
                last = java.io.IOException("$s answered $a")
            } catch (e: Exception) { last = java.io.IOException("$s: ${e.message ?: e.javaClass.simpleName}") }
        }
        throw last ?: java.io.IOException("no fleet resolver answered")
    }

    /** The engine's name as the phone shows it, for the consent message. */
    fun engineLabel(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(AidlBackend.ENGINE_PKG, 0)).toString()
    }.getOrDefault(AidlBackend.ENGINE_PKG)

    /** The live state behind [verdict]. Blocks on the engine binder: off the main thread. */
    class Live(val meshUp: Boolean, val idle: String, val android: AndroidDns, val verdict: Verdict)

    fun live(ctx: Context): Live {
        val p = Prefs(ctx)
        val up = meshUp(ctx)
        val idle = WgState.backend(ctx).idleStatus()
        val a = readAndroid(ctx)
        return Live(up, idle, a, verdict(decl, p.preset, p.fallbacks, p.chosen, fleetResolvers(ctx), up, idle, a, engineLabel(ctx), health.healthy))
    }

    /** Dedupe key of the one alert this raises: re-raising replaces it, a good verdict withdraws it. */
    const val CONSENT_ALERT = "fleet-dns-consent"

    /**
     * #794 Hand the engine the choice (as [syncMeshDown]), then CHECK it took:
     * Android's DNS must become the promised servers. A tunnel that just came
     * up takes a moment to reach LinkProperties, so a mismatch is re-read for
     * up to [settleMs]; missing consent is final at once. Missing consent
     * raises a fleet alert that opens the DNS page, where one tap asks for it;
     * any other verdict takes that alert back. Run at the launcher's start
     * (a reboot), on an engine update or reinstall (consent can be dropped)
     * and after the page's consent. Blocks: off the main thread.
     */
    fun syncAndCheck(ctx: Context, raiseNow: Boolean, settleMs: Long = 4_000): Verdict { return com.diegonmarcos.superapp.xray.EngineProfiler.time("network", "dns") {
        if (Prefs(ctx).chosen) runCatching { syncMeshDown(ctx, raiseNow) }
            .onFailure { android.util.Log.w("FleetDns", "mesh-down DNS not handed to the engine", it) }
        val until = System.currentTimeMillis() + settleMs
        var v = live(ctx).verdict
        while (!v.ok && !v.needsConsent && System.currentTimeMillis() < until) {
            Thread.sleep(500)
            v = live(ctx).verdict
        }
        if (v.needsConsent) FleetAlerts.raise(ctx, FleetAlerts.Alert(
            title = "Fleet DNS is not in effect",
            text = v.why + " Tap to allow it.",
            severity = FleetAlerts.ERROR,
            deepLink = "page:config/dns",
            dedupeKey = CONSENT_ALERT,
        )) else FleetAlerts.withdraw(ctx, CONSENT_ALERT)
        return v
    } }

    // ── Android's own Private DNS ────────────────────────────────────────

    data class AndroidDns(
        val mode: String?,
        val specifier: String?,
        val privateDnsActive: Boolean?,
        val privateDnsServer: String?,
        val activeServers: List<String>,
        val onVpn: Boolean,
    )

    fun readAndroid(ctx: Context): AndroidDns {
        val cr = ctx.contentResolver
        // Settings.Global may refuse a non-@Readable key to an app targeting S+;
        // the shell channel can always read it when it is armed.
        fun global(key: String): String? =
            runCatching { Settings.Global.getString(cr, key) }.getOrNull()
                ?: runCatching {
                    BgHost.shell(ctx)?.exec("settings get global $key")?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                }.getOrNull()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        val vpn = net?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        val p28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return AndroidDns(
            mode = global("private_dns_mode"),
            specifier = global("private_dns_specifier"),
            privateDnsActive = if (p28) lp?.isPrivateDnsActive else null,
            privateDnsServer = if (p28) lp?.privateDnsServerName else null,
            activeServers = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            onVpn = vpn,
        )
    }

    /** #831 One line naming the resolver in effect, for a failure message:
     *  "10.0.0.1, 1.1.1.1 · via VPN · Private DNS dns.example". */
    fun summary(a: AndroidDns): String = listOfNotNull(
        a.activeServers.joinToString(", ").ifEmpty { "no DNS servers on the active network" },
        if (a.onVpn) "via VPN" else null,
        a.privateDnsServer?.takeIf { a.privateDnsActive == true }?.let { "Private DNS $it" }
            ?: a.specifier?.takeIf { a.mode == "hostname" && it.isNotBlank() }?.let { "Private DNS $it (not active)" },
    ).joinToString(" · ")

    fun resolverSummary(ctx: Context): String = summary(readAndroid(ctx))

    private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /**
     * Change Android's Private DNS through the privileged shell channel.
     * Returns null when no channel is armed (the caller deep-links to Android's
     * own settings instead). [mode] must be a declared mode and [host] a plain
     * hostname — both reach a shell, so neither is passed through unchecked.
     */
    fun writeAndroid(ctx: Context, mode: String, host: String): String? {
        require(decl.androidModes.any { it.first == mode }) { "undeclared Private DNS mode $mode" }
        val ch = BgHost.shell(ctx) ?: return null
        val cmds = mutableListOf("settings put global private_dns_mode $mode")
        if (mode == "hostname") {
            require(HOSTNAME.matches(host)) { "not a hostname: $host" }
            cmds += "settings put global private_dns_specifier $host"
        }
        return cmds.joinToString("\n") { ch.exec(it) ?: "(channel refused: $it)" }
    }

    // ── test lookup ──────────────────────────────────────────────────────

    /**
     * One plain-DNS A query to [server] over UDP, so the page can say WHICH
     * upstream answered — the system resolver never reports that. Returns the
     * first A record, "NXDOMAIN" or "rcode N"; throws on timeout / no route.
     */
    fun query(server: String, name: String, timeoutMs: Int): String {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = ByteArrayOutputStream().apply {
            write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in name.trimEnd('.').split('.')) { val b = label.toByteArray(); write(b.size); write(b) }
            write(byteArrayOf(0, 0, 1, 0, 1)) // root, QTYPE=A, QCLASS=IN
        }.toByteArray()
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            return parseAnswer(buf, pkt.length, id, q.size)
        }
    }

    fun parseAnswer(b: ByteArray, len: Int, id: Int, questionEnd: Int): String {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        check(len >= 12 && u16(0) == id) { "reply does not match the query" }
        val rcode = b[3].toInt() and 0x0F
        if (rcode != 0) return if (rcode == 3) "NXDOMAIN" else "rcode $rcode"
        var i = questionEnd
        repeat(u16(6)) {
            while (true) { // owner name: labels, or a compression pointer
                val l = b[i].toInt() and 0xFF
                if (l == 0) { i++; break }
                if ((l and 0xC0) == 0xC0) { i += 2; break }
                i += l + 1
            }
            val type = u16(i); val rdlen = u16(i + 8); val rd = i + 10
            if (type == 1 && rdlen == 4) return (0..3).joinToString(".") { (b[rd + it].toInt() and 0xFF).toString() }
            i = rd + rdlen
        }
        return "no A record"
    }
}

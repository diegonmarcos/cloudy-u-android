package com.diegonmarcos.superapp.network.mesh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.network.AlwaysOnVpn
import com.diegonmarcos.superapp.network.MeshIntent
import com.diegonmarcos.superapp.network.WireGuardProfiles
import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import java.util.concurrent.Executors

/** Why a control that the engine CAN honour cannot be used right now. */
enum class Block { ENGINE_MISSING, NO_KEY }

/**
 * #877 The Cloud Mesh page's state and its verbs. The page's composables read the observable fields and
 * call [run] with an `engine` id from the declaration; nothing in a composable touches the engine,
 * the prefs or a thread. Reads and writes go to [port] OFF the main thread; results come back as
 * [notice] (the line under the tab strip) and as journal entries, so no action ends silently.
 *
 * [exec] runs a unit of work; the default is one background thread so engine calls never reorder.
 */
class MeshStore(
    val port: MeshPort,
    val decl: MeshDecl.Decl,
    val host: MeshHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: (Runnable) -> Unit = defaultExec,
    /** #1153 The persisted, process-shared journal file; null keeps the journal in memory (tests). */
    journalFile: java.io.File? = null,
    private val reachIo: MeshReach.Io = MeshReach.SystemIo,
    /** #1797 Cloud C3's mesh probes (libs:fleetconfigs-xray); a test hands a scripted network. */
    private val meshIo: C3MeshProbe.Io = C3MeshProbe.SystemIo,
    /** #1797 Probe the meshes once when Status first reads an UP tunnel (the app's page; tests drive it by hand). */
    private val autoProbe: Boolean = false,
    /**
     * #1900 Where probes run. The page's own thread ([exec]) is the one Connect / Disconnect / Reconnect queue on, so a probe on
     * it froze every control for the minute the probes took. Probes get their own threads: null = a separate pool when [exec] is the
     * default, else [exec] itself (a test that runs work inline sees the probe finish inline).
     */
    probeExec: ((Runnable) -> Unit)? = null,
) {
    private val probeRun: (Runnable) -> Unit = probeExec ?: if (exec === defaultExec) defaultProbeExec else exec
    // ── observable state ──
    var snapshot by mutableStateOf<MeshSnapshot?>(null)
    /** The open tab of the strip (#1074: Status / Profile / Logs). */
    var tab by mutableStateOf(if (decl.tabs.isEmpty()) "" else decl.startTab.id)
    /** #1074 Cloudflare WARP / One: what is registered and whether One is waiting on the Zero Trust apply. */
    var cf by mutableStateOf(CfView())
    var notice by mutableStateOf("")
    var busy by mutableStateOf(false)
    /** The tunnel is up on a config older than the stored one. */
    var pending by mutableStateOf(false)
    /** Bumped when something the page reads from the port was written: composables re-read. */
    var rev by mutableIntStateOf(0)
    var journalRev by mutableIntStateOf(0)
    var dnsPreset by mutableStateOf("")
    var provider by mutableStateOf("")
    var profiles by mutableStateOf<Map<String, String>>(emptyMap())
    var excluded by mutableStateOf<List<String>>(emptyList())
    var hasKey by mutableStateOf(false)
    var alwaysOn by mutableStateOf<Boolean?>(null)
    /** #1120 Android's Always-on / lockdown as the privileged channel reads it, and whether it can change them. */
    var aoView by mutableStateOf(AlwaysOnVpn.View(null, null, false, "", "", null))
    /** #1120 The owner's intent (connected until Disconnect) and the start-up conditions. */
    var intent by mutableStateOf(MeshIntent.Policy())
    var supervisorNote by mutableStateOf("")
    var lockdown by mutableStateOf<Boolean?>(null)
    var apps by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var dnsChoices by mutableStateOf<List<Triple<String, String, String>>>(emptyList())
    /** Which way the tunnel reaches its hubs (pinned IP, DoH, the TLS-443 relay ...), and the last Test fallbacks. */
    var transport by mutableStateOf<TransportView?>(null)
    var hasRelayKey by mutableStateOf(false)
    var probes by mutableStateOf<List<ProbeLine>>(emptyList())
    /** #1797 When the last Test fallbacks ran (epoch ms; 0 = not yet): the lights show it beside each step. */
    var probesAtMs by mutableStateOf(0L)
    /** A declared profile id whose diff / QR is open. */
    var diffFor by mutableStateOf<String?>(null)
    var qrFor by mutableStateOf<String?>(null)
    /** A pending Cloud-over-custom confirmation. */
    var confirmCloud by mutableStateOf(false)
    /** The control values that are view state, not tunnel state (sort, filter, topology), seeded from the declaration. */
    val view = mutableStateMapOf<String, String>().also { m ->
        for (c in decl.pages.flatMap { it.controls }) if (c.engine.startsWith("view.") && c.kind in setOf("choice", "switch")) m[c.id] = c.default
    }
    val journal = MeshJournal(decl.logCap, journalFile)
    /** #1153 The last Reachability run, and the Logs level filter (all | warn | error). */
    var reach by mutableStateOf<List<MeshReach.Result>>(emptyList())
    var logLevel by mutableStateOf("all")
    /** #1797 The last Cloud C3 mesh profile: the per-mesh table and every profiler step. Null = not probed yet. */
    var meshProfile by mutableStateOf<C3MeshProbe.Report?>(null)
    /** #1900 The Prober matrix (groups A..F): one row per probe, streamed in as each finishes; queued rows carry their route profile already. */
    var prober by mutableStateOf<List<MeshProber.Row>>(emptyList())
    var proberAtMs by mutableStateOf(0L)
    /** #1900 How many probe tasks are running. Never disables a control: a probe is not an action on the tunnel. */
    var probing by mutableIntStateOf(0)
    private val proberEpoch = java.util.concurrent.atomic.AtomicInteger()
    private val proberLock = Any()
    private var lastUp: Boolean? = null
    private var autoProbed = false
    private var seenStatic = false
    private var lastAo: Boolean? = null
    private var lastPath = ""
    private var lastDns = ""

    private val th = Thresholds(decl.handshakeFreshS, decl.handshakeStaleS)
    private var prev: MeshSnapshot? = null
    private var tick = 0

    // ── polling ──

    /** One poll: engine counters, config, the journal lines they imply. Blocking: never on the main thread. */
    @Synchronized fun poll() {
        val now = clock()
        val cfg = runCatching { port.config() }.getOrElse { fail("read config", it); return }
        var s = runCatching { port.sample(now) }.getOrElse { fail("read the engine", it); return }
        val probe = s.up && tick % decl.latencyEveryTicks == 0 && tab == "status"
        if (probe) s = s.copy(latency = runCatching { port.probeLatency(cfg, decl.latencyTimeoutMs, now) }.getOrNull())
        tick++
        val (snap, events) = MeshReducer.reduce(prev, cfg, s, th)
        prev = snap
        if (lastUp != null && lastUp != snap.up) autoProbed = false   // #1900 a profile taken in the other tunnel state is stale: probe again
        lastUp = snap.up
        if (events.isNotEmpty()) { journal.addAll(events); journalRev++ }
        snapshot = snap
        refreshStatic()
        logChanges(snap)
        if (autoProbe && !autoProbed && snap.up && tab == "status" && decl.meshes.isNotEmpty()) {
            autoProbed = true
            perform("mesh probe", probe = true) { probeNow() }
            startProber()
        }
    }

    /** #1153 What changed in the engine's surroundings since the last poll: Always-on, the transport rung, the DNS in effect. */
    private fun logChanges(snap: MeshSnapshot) {
        val ao = aoView.on
        val path = transport?.path.orEmpty()
        val dns = snap.cfg.dnsInEffect
        fun note(kind: String, text: String) { journal.add(LogEvent(clock(), kind, text)); journalRev++ }
        if (!seenStatic) {
            seenStatic = true
            note("engine", "page open: profile ${snap.cfg.activeProfile.substringAfterLast('/').ifBlank { "none" }}, " +
                "always-on ${ao ?: "unknown"}, transport ${path.ifBlank { "unknown" }}, DNS ${dns.ifBlank { "unknown" }}")
        } else {
            if (ao != lastAo) note("engine", "always-on ${lastAo ?: "unknown"} -> ${ao ?: "unknown"}")
            if (path != lastPath) note("transport", "path ${lastPath.ifBlank { "unknown" }} -> ${path.ifBlank { "unknown" }}" +
                (transport?.detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            if (dns != lastDns) note("dns", "DNS in effect: ${dns.ifBlank { "unknown" }}")
        }
        lastAo = ao; lastPath = path; lastDns = dns
    }

    private fun refreshStatic() {
        runCatching {
            pending = port.pendingApply()
            dnsPreset = port.dnsPreset()
            provider = port.provider()
            profiles = port.storedProfiles()
            excluded = port.excludedApps()
            hasKey = port.hasPrivateKey()
            alwaysOn = port.alwaysOn()
            aoView = port.alwaysOnView()
            intent = port.intent()
            supervisorNote = port.supervisorNote()
            lockdown = port.lockdown()
            transport = port.transport()
            hasRelayKey = port.hasRelayKey()
            cf = runCatching { port.cloudflare() }.getOrDefault(cf)
            if (dnsChoices.isEmpty()) dnsChoices = port.dnsChoices()
        }
    }

    /** Whether the ticker should be running: only while the page is resumed, never in the background. */
    fun shouldPoll(resumed: Boolean): Boolean = resumed

    // ── actions ──

    private fun fail(what: String, t: Throwable) {
        notice = "$what failed: ${t.message ?: t.javaClass.simpleName}"
        journal.add(LogEvent(clock(), "error", notice)); journalRev++
    }

    /** Run [block] off the main thread; its result (or its failure's reason) becomes the notice and a journal line. */
    fun perform(label: String, probe: Boolean = false, block: () -> String) {
        if (probe) synchronized(proberLock) { probing++ } else busy = true
        (if (probe) probeRun else exec)(Runnable {
            try {
                val m = block()
                notice = "$label: $m"
                journal.add(LogEvent(clock(), "action", notice))
            } catch (t: Throwable) {
                notice = "$label failed: ${t.message ?: t.javaClass.simpleName}"
                journal.add(LogEvent(clock(), "error", notice))
            } finally {
                journalRev++; rev++
                runCatching { poll() }
                if (probe) synchronized(proberLock) { probing-- } else busy = false
            }
        })
    }

    /** Why [c] cannot be used right now although the engine could honour it; null when it can. */
    fun blockOf(c: MeshDecl.Control): Block? {
        if (c.engine in ENGINE_BOUND && snapshot?.engineInstalled == false) return Block.ENGINE_MISSING
        if (c.engine in NEEDS_KEY && !hasKey && !connected()) return Block.NO_KEY
        return null
    }

    fun connected(): Boolean = snapshot?.up == true

    /** The Connected switch / Reconnect button: ask for VPN consent when needed, then drive the engine. */
    private fun connectFlow(label: String, call: () -> String) {
        val intent = runCatching { port.consentIntent() }.getOrNull()
        if (intent == null) perform(label, block = call) else host.requestConsent(intent) { perform(label, block = call) }
    }

    /** The one dispatch table: every `engine` id the declaration may name, and what it does. */
    private val handlers: Map<String, (String, Int) -> Unit> = mapOf(
        "state.set" to { arg, _ -> if (arg != "on") cancelProber(); if (arg == "on") connectFlow("connect") { port.connect() } else perform("disconnect") { port.disconnect() } },
        "state.reconnect" to { _, _ -> connectFlow("reconnect") { port.reconnect() } },
        "state.connect" to { _, _ -> connectFlow("connect") { port.connect() } },
        "state.disconnect" to { _, _ -> cancelProber(); perform("disconnect") { port.disconnect() } },
        "alwayson.set" to { arg, _ ->
            if (!aoView.canChange) { host.openVpnSettings(); notice = "Always-on: " + aoView.reason }
            else perform("always-on") { port.setAlwaysOn(arg == "on") }
        },
        "lockdown.set" to { arg, _ ->
            if (!aoView.canChange) { host.openVpnSettings(); notice = "Kill switch: " + aoView.reason }
            else perform("kill switch") { port.setLockdown(arg == "on") }
        },
        "startup.boot" to { arg, _ -> perform("start-up") { port.setStartup("boot", arg == "on") } },
        "startup.wifi" to { arg, _ -> perform("start-up") { port.setStartup("wifi", arg == "on") } },
        "startup.mobile" to { arg, _ -> perform("start-up") { port.setStartup("mobile", arg == "on") } },
        "nav.vpnSettings" to { _, _ -> host.openVpnSettings(); notice = "VPN settings opened" },
        "nav.account" to { _, _ -> host.openAccount(); notice = "Account opened: Runtime > Apply all re-imports the vault's profiles" },
        "prefs.mtu" to { a, _ -> perform("MTU") { port.setMtu(a) } },
        "prefs.keepalive" to { a, _ -> perform("keepalive") { port.setKeepalive(a) } },
        "prefs.tunnelName" to { a, _ -> perform("tunnel name") { port.setTunnelName(a) } },
        "prefs.address" to { a, _ -> perform("address") { port.setAddress(a) } },
        "prefs.listenPort" to { a, _ -> perform("listen port") { port.setListenPort(a) } },
        "prefs.privateKey" to { a, _ -> perform("private key") { port.setPrivateKey(a) } },
        "prefs.generateKey" to { _, _ -> perform("generate key") { "public half " + port.generateKey() + " - add it to the hub as this phone's peer" } },
        "prefs.excludedApps" to { a, _ -> perform("excluded apps") { port.setExcludedApps(a.split(',').map { it.trim() }.filter { it.isNotEmpty() }) } },
        "prefs.peer.add" to { _, _ -> perform("add peer") { port.addPeer() } },
        "prefs.peer.remove" to { _, i -> perform("remove peer") { port.removePeer(i) } },
        "prefs.provider" to { a, _ ->
            if (a == "custom") perform("provider") { port.setProviderCustom() }
            else if (port.matchesCloudPreset()) perform("provider") { port.applyCloudPreset() }
            else confirmCloud = true
        },
        "prefs.profile.activate" to { a, _ ->
            perform("profile") { storedKey(port.storedProfiles(), a)?.let { port.activateProfile(it) } ?: port.installDeclared(a) }
        },
        "mesh.applyMesh" to { a, _ ->
            val text = host.paste()
            if (text.isBlank()) { notice = "re-import from clipboard: the clipboard holds no text"; journal.add(LogEvent(clock(), "error", notice)); journalRev++ }
            else perform("re-import $a") { port.importText(nameOf(a), text) }
        },
        "fleetdns.preset" to { a, _ -> perform("DNS preset") { port.setDnsPreset(a) } },
        "transport.mode" to { a, _ -> perform("path") { port.setTransportMode(a) } },
        "prefs.relayKey" to { a, _ -> perform("relay key") { port.setRelayKey(a) } },
        "transport.test" to { _, _ ->
            perform("test fallbacks", probe = true) {
                val r = port.testFallbacks()
                probes = r
                probesAtMs = clock()
                for (pr in r) journal.add(LogEvent(clock(), if (pr.ok == false) "warn" else "probe",
                    "${pr.id} ${when (pr.ok) { true -> "pass"; false -> "FAIL"; null -> "n/a" }}: ${pr.detail}"))
                "${r.count { it.ok == true }} of ${r.size} pass" + r.filter { it.ok == false }.joinToString("") { " · ${it.id} fails" }
            }
        },
        "reach.run" to { _, _ -> perform("reachability", probe = true) { reachNow() } },
        "mesh.probe" to { _, _ -> perform("mesh probe", probe = true) { probeNow() } },
        // #1900 the Prober matrix: profile every route, then probe off the page's thread, streaming; cancel stops it, Disconnect too
        "prober.run" to { _, _ -> journal.add(LogEvent(clock(), "action", "prober: started")); journalRev++; startProber() },
        "prober.cancel" to { _, _ -> cancelProber(); journal.add(LogEvent(clock(), "action", "prober: cancelled")); journalRev++; notice = "prober cancelled" },
        "profiler.copy" to { _, _ ->
            val r = meshProfile
            if (r == null) notice = "copy profile: no mesh profile yet - tap Probe meshes"
            else { host.copy("mesh profile", MeshReport.redact(C3MeshProbe.text(r))); notice = "mesh profile copied (${r.steps.size} steps)" }
        },
        "profiler.export" to { _, _ ->
            val r = meshProfile
            if (r == null) notice = "export profile: no mesh profile yet - tap Probe meshes"
            else host.exportText("cloud-mesh-profile.txt", MeshReport.redact(C3MeshProbe.text(r)))
        },
        "view.statusLog" to { a, _ -> view["status_log_open"] = a },
        "clipboard.all" to { _, _ -> host.copy("mesh status", reportText()); notice = "whole status copied (private keys are never included)" },
        "export.all" to { _, _ -> host.exportText("cloud-mesh-status.txt", reportText()) },
        // #1845 the same whole status as a Markdown file (headings per section, the rows fenced)
        "export.md" to { _, _ -> host.exportMarkdown("cloud-mesh-status.md", MeshReport.markdown(reportText())) },
        "log.level" to { a, _ -> logLevel = if (a in setOf("all", "warn", "error")) a else "all"; journalRev++ },
        "view.sort" to { a, _ -> view["peer_sort"] = a },
        "view.routeFilter" to { a, _ -> view["route_filter"] = a },
        "view.diff" to { a, _ -> diffFor = if (diffFor == a) null else a },
        "view.qr" to { a, _ -> qrFor = if (qrFor == a) null else a },
        "clipboard.peer" to { _, i ->
            val p = snapshot?.cfg?.peers?.getOrNull(i)
            if (p == null) notice = "copy peer: no such peer" else { host.copy("peer", MeshText.peerBlock(p)); notice = "peer ${p.name.ifBlank { i + 1 }} copied (no pre-shared key)" }
        },
        "clipboard.profile" to { a, _ ->
            val t = profileText(a)
            if (t == null) notice = "copy profile: $a is not declared" else { host.copy(a, t); notice = "$a copied - a template, it carries no private key" }
        },
        "clipboard.log" to { _, _ -> host.copy("mesh log", journal.text()); notice = "${journal.list().size} journal lines copied" },
        "log.clear" to { _, _ -> journal.clear(); journalRev++; notice = "journal cleared" },
        "cf.warp.use" to { _, _ -> perform("cloudflare warp") { port.useWarp() } },
        "cf.warp.forget" to { _, _ -> perform("cloudflare warp") { port.forgetWarp() } },
        "cf.one.enrol" to { a, _ ->
            val one = decl.cloudflare.one
            when {
                !one.enabled -> {
                    notice = "cloudflare one: waiting on ${one.waitingOn} (Zero Trust apply)"
                    journal.add(LogEvent(clock(), "action", notice)); journalRev++
                }
                a.isBlank() -> { host.openCustomTab(port.oneLoginUrl()); notice = "cloudflare one: team login opened (${one.loginHost})" }
                else -> perform("cloudflare one") { port.enrolOne(a) }
            }
        },
        "saf.importConf" to { _, _ -> host.importConf() },
        "saf.exportConf" to { _, _ -> host.exportConf() },
        "saf.exportProfiles" to { _, _ -> host.exportProfiles() },
    )

    /** The engine ids this store can dispatch - the declaration may name no other. */
    val engineIds: Set<String> get() = handlers.keys

    /** Dispatch declared control [c]'s engine id with [arg] (a value, or a profile id) and [index] (a peer). */
    fun run(engineId: String, arg: String = "", index: Int = -1) {
        val h = handlers[engineId]
        if (h == null) { notice = "no engine call named $engineId"; return }
        h(arg, index)
    }

    /** #1153 DNS + TCP :443 + HTTPS for each declared mesh URL; every result lands in the journal. */
    private fun reachNow(): String {
        check(decl.reachHosts.isNotEmpty()) { "no mesh URLs are declared" }
        val r = MeshReach.run(decl.reachHosts, reachIo, nowMs = clock)
        reach = r
        for (x in r) journal.add(LogEvent(clock(), if (x.ok) "reach" else "warn", MeshReach.text(listOf(x))))
        return "${r.count { it.ok }} of ${r.size} reachable"
    }

    /**
     * #1797 Cloud C3's mesh profile: the per-mesh table (overall, inside the mesh, route, the mesh's resolver,
     * Android's resolver, which resolver answered, hub, service, IPv6, latency) and the profiler's steps. Every
     * failing mesh lands in the journal as a warning, so a mesh that stops resolving is in the Log too.
     */
    private fun probeNow(): String {
        check(decl.meshes.isNotEmpty()) { "no meshes are declared" }
        val s = snapshot
        val cfg = s?.cfg ?: port.config()
        val input = C3MeshProbe.Input(
            tunnelUp = s?.up == true,
            peers = s?.peers?.map { C3MeshProbe.Peer(it.cfg.name, it.cfg.publicKey, it.cfg.endpoint, it.cfg.allowedIps, it.handshakeAgeS) }
                ?: cfg.peers.map { C3MeshProbe.Peer(it.name, it.publicKey, it.endpoint, it.allowedIps, null) },
            resolvers = ipLiterals(cfg.dnsInEffect),
            fleetResolvers = ipLiterals(cfg.dns),
            interfaceMtu = cfg.mtu,
            freshS = decl.handshakeFreshS,
            targets = decl.meshes,
            mtuSizes = decl.mtuProbeSizes,
            names = decl.reachHosts,
            path = transport?.path.orEmpty(),
            pathDetail = transport?.detail.orEmpty(),
        )
        val r = C3MeshProbe.run(input, meshIo, clock)
        meshProfile = r
        for (row in r.rows) journal.add(LogEvent(clock(), if (row.cell(C3MeshProbe.OVERALL).ok == true) "probe" else "warn",
            "${row.target.label} (${row.target.cidr}): " + C3MeshProbe.COLUMNS.joinToString(" | ") { "$it ${row.cell(it).text}" }))
        return "${r.rows.count { it.cell(C3MeshProbe.OVERALL).ok == true }} of ${r.rows.size} meshes pass"
    }

    /** #1900 What the prober probes: the declared list, plus the DNS page's declared IP targets (one list of well-known sites by IP). */
    private fun proberDecl(): MeshProber.Decl {
        val d = decl.prober
        val dnsIps = runCatching { com.diegonmarcos.superapp.network.FleetDns.decl.reach.targets }.getOrDefault(emptyList())
            .filter { t -> d.ips.none { it.ip == t.ip } }.map { MeshProber.IpTarget("dns-${it.id}", it.label, it.ip, 443) }
        return d.copy(ips = d.ips + dnsIps)
    }

    /** #1900 One poll's worth of context for the profile: the live snapshot, never a report taken earlier. */
    private fun proberCtx(): MeshProber.Ctx = MeshProber.ctxOf(snapshot, runCatching { port.config() }.getOrNull(), transport, decl)

    /**
     * #1900 Profile every route, then probe. The queued rows (each with its route profile) show at once; probes run on the probe
     * threads, [MeshProber.Decl.parallel] at a time, each under its own timeout, and every finished row lands in [prober] on its own.
     * A new run, [cancelProber] or Disconnect supersedes the one in flight.
     */
    fun startProber() {
        val epoch = proberEpoch.incrementAndGet()
        val ctx = proberCtx()
        val pd = proberDecl()
        val specs = MeshProber.plan(pd, ctx)
        synchronized(proberLock) { prober = specs.map { MeshProber.queued(it, ctx) }; proberAtMs = clock(); probing++ }
        probeRun(Runnable {
            try {
                MeshProber.run(specs, ctx, meshIo, {
                    val r = port.testFallbacks()
                    probes = r; probesAtMs = clock()
                    r
                }, pd.timeoutMs, pd.parallel, clock, { proberEpoch.get() != epoch }) { row ->
                    if (proberEpoch.get() == epoch) synchronized(proberLock) { prober = MeshProber.merge(prober, row) }
                }
                if (proberEpoch.get() == epoch) {
                    val rows = prober
                    for (r in rows.filter { it.ok == false }) journal.add(LogEvent(clock(), "warn", "prober ${r.spec.group}/${r.spec.id} FAIL: ${r.detail}"))
                    journal.add(LogEvent(clock(), "probe", "prober: " + MeshProber.GROUPS.joinToString(" · ") { "${MeshProber.LETTERS[it]} ${MeshProber.groupLine(rows, it)}" }))
                    journalRev++
                    notice = "prober: ${rows.count { it.ok == true }} of ${rows.size} pass"
                }
            } catch (t: Throwable) {
                notice = "prober failed: ${t.message ?: t.javaClass.simpleName}"
                journal.add(LogEvent(clock(), "error", notice)); journalRev++
            } finally { synchronized(proberLock) { probing-- } }
        })
    }

    /** #1900 Stop the probes in flight: rows not yet answered stay queued, nothing already measured is lost. */
    fun cancelProber() { proberEpoch.incrementAndGet() }

    /** #1153 The whole Status page as text (Copy all / Export all); #1797 adds the per-mesh table and the profile. */
    fun reportText(): String = MeshReport.build(snapshot, dnsPreset, provider, transport, probes, reach, journal.list(), clock(), profile = meshProfile, prober = prober)

    /** The Cloud preset after the owner confirmed replacing their settings. */
    fun confirmCloudPreset() { confirmCloud = false; perform("provider") { port.applyCloudPreset() } }

    /** The stored-set name of declared profile [id] (its export file name without `.conf`). */
    private fun nameOf(id: String): String = WireGuardProfiles.byId(id)?.fileName?.removeSuffix(".conf") ?: id

    /** The key under which this phone stores declared profile [id], if it does (a vault may prefix a folder). */
    fun storedKey(stored: Map<String, String>, id: String): String? =
        nameOf(id).let { n -> stored.keys.firstOrNull { it.substringAfterLast('/') == n } }

    /** Declared profile [id] as key-free wg-quick text. */
    fun profileText(id: String): String? = WireGuardProfiles.byId(id)?.let { WireGuardProfiles.render(it) }

    companion object {
        private val IP = Regex("(?<![\\w:.])((?:\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7})(?![\\w:.])")
        /** #1797 The IP literals in a DNS line such as "Public open: 10.0.0.1, 1.1.1.1 via VPN", in order. */
        fun ipLiterals(s: String): List<String> = IP.findAll(s).map { it.groupValues[1] }.filter { (it.count { c -> c == ':' } >= 2 && it.any { c -> c.isLetterOrDigit() }) || C3MeshProbe.isV4(it) }.distinct().toList()
        private val ENGINE_BOUND = setOf("state.set", "state.connect", "state.disconnect", "state.reconnect", "fleetdns.preset", "alwayson.set", "lockdown.set")
        private val NEEDS_KEY = setOf("state.set", "state.connect", "state.reconnect")
        val defaultExec: (Runnable) -> Unit = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-page").apply { isDaemon = true } }::execute
        /** #1900 The probes' own threads: a probe never queues behind Connect / Disconnect on [defaultExec]. */
        val defaultProbeExec: (Runnable) -> Unit = Executors.newCachedThreadPool { r -> Thread(r, "mesh-probe").apply { isDaemon = true } }::execute
    }
}

package com.diegonmarcos.superapp.wifi

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PingRun(val host: String = "", val running: Boolean = false, val lines: List<String> = emptyList(), val rtts: List<Double> = emptyList(), val stats: PingStats? = null, val error: String = "")
data class TraceRun(val host: String = "", val running: Boolean = false, val hops: List<Hop> = emptyList(), val error: String = "")
data class PortRun(val host: String = "", val running: Boolean = false, val total: Int = 0, val done: Int = 0, val open: List<PortResult> = emptyList(), val error: String = "")
data class LanRun(val running: Boolean = false, val total: Int = 0, val done: Int = 0, val devices: List<LanDevice> = emptyList(), val phase: String = "")
data class DeviceCaps(val band5: Boolean = false, val band6: Boolean = false, val wifi7: Boolean = false, val rtt: Boolean = false, val rttAvailable: Boolean = false, val staAp: Boolean = false)

/** Everything the page draws (#1263). */
data class WifiState(
    val supported: Boolean = true,
    val enabled: Boolean = true,
    val missing: List<String> = emptyList(),
    val locationOn: Boolean = true,
    val auto: Boolean = true,
    val scans: Int = 0,
    val lastScanAt: Long = 0,
    val lastRefusedAt: Long = 0,
    val throttleRemaining: Int = 4,
    val nextScanAt: Long = 0,
    val throttleSetting: Boolean? = null,
    val aps: List<AccessPoint> = emptyList(),
    val query: ApQuery = ApQuery(),
    val selected: String? = null,
    val link: LinkSnapshot = LinkSnapshot(),
    val linkHistory: List<RssiSample> = emptyList(),
    val roams: List<RoamEvent> = emptyList(),
    val planId: String = "WORLD",
    val country: String = "",
    val rtt: Map<String, List<RttResult>> = emptyMap(),
    val rttRunning: Boolean = false,
    val ping: PingRun = PingRun(),
    val trace: TraceRun = TraceRun(),
    val dns: List<DnsAnswer> = emptyList(),
    val ports: PortRun = PortRun(),
    val lan: LanRun = LanRun(),
    val down: Throughput? = null,
    val up: Throughput? = null,
    val throughputRunning: Boolean = false,
    val locatorOn: Boolean = false,
    val locatorTarget: String? = null,
    val locatorSamples: List<Double> = emptyList(),
    val locatorSound: Boolean = true,
    val heat: Heatmap = Heatmap(6, 6, ""),
    val events: List<String> = emptyList(),
    val error: String = "",
    val ouiSize: Int = 0,
    val caps: DeviceCaps = DeviceCaps(),
)

/**
 * The analyzer's state machine (#1263) over [AndroidWifiSource] and [NetTools]. The page attaches while visible ([attach] /
 * [detach], reference counted, so two tabs or two hosts share one stack): scans run on the declared interval inside Android's
 * throttle, the link is followed through ConnectivityManager and sampled every second for the RSSI graph, the roaming log and the
 * locator. Tools run one of each kind at a time and are cancellable.
 */
class WifiController(
    private val ctx: Context,
    val cfg: WifiConfig,
    val plans: ChannelPlans,
    private val ouiLoader: () -> OuiTable,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val src = AndroidWifiSource(ctx)
    private val tools = NetTools(ctx, cfg)
    @Volatile private var oui: OuiTable = OuiTable.EMPTY
    private val tracker = ApTracker(cfg.alpha, cfg.historyMax, cfg.dropMs) { oui.label(it) }
    val throttle = ScanThrottle(cfg.throttleLimit, cfg.throttleWindowMs)

    private val _state = MutableStateFlow(WifiState(heat = Heatmap(cfg.heatRows, cfg.heatCols, "")))
    val state: StateFlow<WifiState> = _state

    private var attached = 0
    /** True while no tab (or host) is attached: nothing is scanning and the controller can be replaced by one on a fresher config. */
    fun idle(): Boolean = attached == 0
    private var unlisten: (() -> Unit)? = null
    private var loop: Job? = null
    private var pingJob: Job? = null; private var traceJob: Job? = null; private var portJob: Job? = null
    private var lanJob: Job? = null; private var tpJob: Job? = null; private var rttJob: Job? = null; private var locJob: Job? = null
    private var tone: ToneGenerator? = null

    init {
        val cc = runCatching { (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country
        _state.update { it.copy(country = cc.uppercase(), planId = plans.forCountry(cc).id) }
        scope.launch {
            val t = withContext(Dispatchers.IO) { runCatching { ouiLoader() }.getOrDefault(OuiTable.EMPTY) }
            oui = t; _state.update { it.copy(ouiSize = t.size) }
        }
    }

    fun plan(): ChannelPlan = plans.byId(_state.value.planId)

    private fun event(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())
        _state.update { it.copy(events = (it.events + "$t  $s").takeLast(400)) }
    }

    // ---- lifecycle ----

    fun attach() {
        attached++
        refreshEnv()
        if (attached > 1) return
        unlisten = src.listen { fresh -> pull(fresh) }
        src.watchLink { scope.launch { sampleLink() } }
        pull(false)
        loop = scope.launch {
            var tick = 0L
            while (isActive) {
                sampleLink()
                val s = _state.value
                // #1274 environment re-read every 5 s (a grant on the Permissions page shows up without reopening the tab).
                if (tick % 5 == 0L) refreshEnv()
                // Auto schedules itself inside the budget (ScanThrottle.autoDue) and otherwise relies on the passive
                // SCAN_RESULTS_AVAILABLE broadcast (src.listen) for results other apps' scans produce.
                if (s.auto && throttle.autoDue(System.currentTimeMillis(), lastAutoAt, cfg.autoIntervalMs)) scanNow(fromAuto = true)
                tick++
                delay(1000)
            }
        }
    }

    fun detach() {
        attached = (attached - 1).coerceAtLeast(0)
        if (attached > 0) return
        loop?.cancel(); loop = null
        unlisten?.invoke(); unlisten = null
        src.stopLink()
        setLocator(false)
    }

    fun refreshEnv() {
        val thr = src.throttleEnabled
        if (thr != null) throttle.enabled = thr
        val hadMissing = _state.value.missing.isNotEmpty()
        val nowMissing = WifiPermissions.missing(ctx)
        if (hadMissing && nowMissing.isEmpty()) {
            // Location just granted: the cached results are readable now, show them at once and refresh once.
            _state.update { it.copy(missing = nowMissing, error = "") }
            event("permissions granted: reading results")
            scope.launch { pull(false); scanNow() }
        }
        _state.update {
            it.copy(supported = src.supported, enabled = src.enabled, missing = WifiPermissions.missing(ctx), locationOn = WifiPermissions.locationOn(ctx),
                throttleSetting = thr, caps = DeviceCaps(src.is5GhzSupported, src.is6GhzSupported, src.wifi7Supported, src.rttSupported, src.rttAvailable, src.isStaApConcurrency))
        }
    }

    // ---- scanning ----

    fun setAuto(on: Boolean) = _state.update { it.copy(auto = on) }

    private var lastAutoAt = 0L

    /**
     * Ask Android for a scan if the throttle window has room; a refusal is recorded so the page can say why. #1274: nothing
     * is asked while a permission, location services or Wi-Fi is missing (the call would only be refused), a refused call
     * backs off one slot instead of being counted, and the budget is never spent below 0: the cached results are pulled
     * passively instead.
     */
    fun scanNow(fromAuto: Boolean = false) {
        refreshEnv()
        val now = System.currentTimeMillis()
        if (fromAuto) lastAutoAt = now
        val s = _state.value
        if (s.missing.isNotEmpty() || !s.locationOn || !s.enabled) {
            if (!fromAuto) event("scan not requested: " + when { s.missing.isNotEmpty() -> "permission missing"; !s.locationOn -> "location off"; else -> "Wi-Fi off" })
            pull(false); return
        }
        if (!throttle.canScan(now)) {
            if (!fromAuto) event("scan skipped: throttled until ${java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date(throttle.nextAllowedAt(now)))}")
            updateThrottle(now); pull(false); return
        }
        val ok = src.startScan()
        if (ok) throttle.record(now) else throttle.refuse(now)
        _state.update { it.copy(lastScanAt = if (ok) now else it.lastScanAt, lastRefusedAt = if (ok) it.lastRefusedAt else now, error = if (ok) "" else src.lastError.ifEmpty { "Android refused the scan (throttled or Wi-Fi off); showing cached results" }) }
        if (!ok) event("scan refused by Android") else event(if (fromAuto) "auto scan requested" else "scan requested")
        updateThrottle(now)
        pull(false)
    }

    private fun updateThrottle(now: Long) = _state.update { it.copy(throttleRemaining = throttle.remaining(now).coerceAtMost(99), nextScanAt = throttle.nextAllowedAt(now)) }

    private fun pull(fresh: Boolean) {
        scope.launch {
            val raws = withContext(Dispatchers.IO) { src.results() }
            val now = System.currentTimeMillis()
            val aps = tracker.ingest(raws, now)
            _state.update { s ->
                s.copy(aps = aps, scans = s.scans + if (fresh) 1 else 0, error = if (raws.isEmpty() && src.lastError.isNotEmpty()) src.lastError else s.error)
            }
            updateThrottle(now)
        }
    }

    fun setQuery(q: ApQuery) = _state.update { it.copy(query = q) }
    fun select(bssid: String?) = _state.update { it.copy(selected = bssid?.lowercase()) }
    fun setPlan(id: String) = _state.update { it.copy(planId = plans.byId(id).id) }
    fun clearAps() { tracker.clear(); _state.update { it.copy(aps = emptyList()) } }

    // ---- link ----

    private suspend fun sampleLink() {
        val l = withContext(Dispatchers.IO) { src.link() }
        val prev = _state.value.link
        Roaming.detect(l.at, prev.takeIf { it.connected }, l.takeIf { it.connected })?.let { r ->
            if (prev.at > 0) { _state.update { it.copy(roams = (it.roams + r).takeLast(200)) }; event("${r.kind}: ${r.fromBssid.ifEmpty { "-" }} -> ${r.toBssid.ifEmpty { "-" }} (${r.ssid})") }
        }
        _state.update { s ->
            s.copy(link = l, linkHistory = if (l.connected) (s.linkHistory + RssiSample(l.at, l.rssi)).takeLast(cfg.historyMax * 2) else s.linkHistory)
        }
    }

    // ---- RTT ----

    fun startRtt(bssids: List<String>, continuous: Boolean) {
        rttJob?.cancel()
        _state.update { it.copy(rttRunning = true) }
        rttJob = scope.launch {
            do {
                val res = kotlinx.coroutines.suspendCancellableCoroutine<List<RttResult>> { c -> src.range(bssids) { r -> if (c.isActive) c.resumeWith(Result.success(r)) } }
                if (res.isEmpty() && src.lastError.isNotEmpty()) _state.update { it.copy(error = src.lastError) }
                _state.update { s -> s.copy(rtt = s.rtt + res.groupBy { it.bssid }.mapValues { (k, v) -> ((s.rtt[k] ?: emptyList()) + v).takeLast(60) }) }
                if (continuous) delay(1000)
            } while (continuous && isActive)
            _state.update { it.copy(rttRunning = false) }
        }
    }

    fun stopRtt() { rttJob?.cancel(); _state.update { it.copy(rttRunning = false) } }

    // ---- tools ----

    private fun net() = src.wifiNetwork

    fun ping(host: String) {
        pingJob?.cancel()
        _state.update { it.copy(ping = PingRun(host, true)) }
        pingJob = scope.launch {
            try {
                val out = tools.ping(host, cfg.pingCount) { line ->
                    val r = PingParser.reply(line)
                    _state.update { s -> s.copy(ping = s.ping.copy(lines = (s.ping.lines + line).takeLast(200), rtts = s.ping.rtts + listOfNotNull(r?.ms))) }
                }
                _state.update { s -> s.copy(ping = s.ping.copy(running = false, stats = PingParser.stats(out))) }
                event("ping $host: ${PingParser.stats(out).let { "loss %.1f%% avg %.1f ms jitter %.1f ms".format(Locale.ROOT, it.lossPct, it.avg, it.jitter) }}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { s -> s.copy(ping = s.ping.copy(running = false, stats = PingStats.of(s.ping.rtts.size, s.ping.rtts))) }; throw e
            } catch (e: Exception) { _state.update { s -> s.copy(ping = s.ping.copy(running = false, error = e.message ?: "ping failed")) } }
        }
    }

    fun stopPing() { pingJob?.cancel() }

    fun traceroute(host: String) {
        traceJob?.cancel()
        _state.update { it.copy(trace = TraceRun(host, true)) }
        traceJob = scope.launch {
            try { tools.traceroute(host) { h -> _state.update { s -> s.copy(trace = s.trace.copy(hops = s.trace.hops + h)) } } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { s -> s.copy(trace = s.trace.copy(error = e.message ?: "traceroute failed")) } }
            finally { _state.update { s -> s.copy(trace = s.trace.copy(running = false)) } }
        }
    }

    fun stopTrace() { traceJob?.cancel() }

    fun lookup(name: String) {
        scope.launch {
            val a = tools.resolve(name.trim(), net())
            _state.update { s -> s.copy(dns = (listOf(a) + s.dns).take(20)) }
        }
    }

    fun portScan(host: String, spec: String) {
        val ports = PortSpec.parse(spec, cfg.portMax)
        if (ports == null || !PingParser.safeHost(host)) { _state.update { it.copy(ports = PortRun(host, error = "bad host or port list (at most ${cfg.portMax} ports)")) }; return }
        portJob?.cancel()
        _state.update { it.copy(ports = PortRun(host, true, ports.size)) }
        event("port scan $host: ${ports.size} ports")
        portJob = scope.launch {
            try {
                tools.portScan(host, ports, net()) { r, done ->
                    _state.update { s -> s.copy(ports = s.ports.copy(done = done, open = if (r.open) (s.ports.open + r).sortedBy { it.port } else s.ports.open)) }
                }
            } finally { _state.update { s -> s.copy(ports = s.ports.copy(running = false)) } }
        }
    }

    fun stopPorts() { portJob?.cancel() }

    private fun addDevice(d: LanDevice) = _state.update { s ->
        val cur = s.lan.devices.associateBy { it.ip }.toMutableMap()
        cur[d.ip] = cur[d.ip]?.merge(d) ?: d
        s.copy(lan = s.lan.copy(devices = cur.values.sortedBy { Ipv4.toInt(it.ip)?.toLong()?.and(0xffffffffL) ?: Long.MAX_VALUE }))
    }

    /** mDNS / NSD, then SSDP, then the bounded sweep of the /24 (at most 254 hosts) with NetBIOS names. */
    fun discover() {
        lanJob?.cancel()
        val l = _state.value.link
        val hosts = Ipv4.sweepHosts(l.ipv4, l.prefix.takeIf { it > 0 } ?: 24)
        _state.update { it.copy(lan = LanRun(true, hosts.size, 0, emptyList(), "mdns")) }
        event("discovery on ${l.ipv4}/${l.prefix}: ${hosts.size} hosts")
        lanJob = scope.launch {
            try {
                launch { tools.nsd { addDevice(it) } }
                launch { _state.update { s -> s.copy(lan = s.lan.copy(phase = "ssdp")) }; tools.ssdp { addDevice(it) } }
                _state.update { s -> s.copy(lan = s.lan.copy(phase = "sweep")) }
                tools.sweep(hosts, net(), { addDevice(it) }) { n -> _state.update { s -> s.copy(lan = s.lan.copy(done = n)) } }
            } finally { _state.update { s -> s.copy(lan = s.lan.copy(running = false, phase = "")) }; event("discovery: ${_state.value.lan.devices.size} devices") }
        }
    }

    fun stopDiscover() { lanJob?.cancel() }

    fun throughput() {
        tpJob?.cancel()
        _state.update { it.copy(down = null, up = null, throughputRunning = true) }
        tpJob = scope.launch {
            try {
                val d = tools.download(net()) { t -> _state.update { it.copy(down = t) } }
                val u = if (cfg.uploadUrl.isNotBlank()) tools.upload(net()) { t -> _state.update { it.copy(up = t) } } else null
                event("throughput: down %.1f Mbps%s".format(Locale.ROOT, d.mbps, u?.let { ", up %.1f Mbps".format(Locale.ROOT, it.mbps) }.orEmpty()))
            } finally { _state.update { it.copy(throughputRunning = false) } }
        }
    }

    fun stopThroughput() { tpJob?.cancel() }

    // ---- locator ----

    fun setLocatorSound(on: Boolean) = _state.update { it.copy(locatorSound = on) }

    /** Hot / cold: beep faster as the target's signal rises. The connected AP updates every second; others at the scan rate. */
    fun setLocator(on: Boolean, target: String? = _state.value.locatorTarget ?: _state.value.selected ?: _state.value.link.bssid.ifEmpty { null }) {
        locJob?.cancel(); locJob = null
        _state.update { it.copy(locatorOn = on && target != null, locatorTarget = target, locatorSamples = emptyList()) }
        if (!on || target == null) { tone?.release(); tone = null; return }
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        locJob = scope.launch {
            while (isActive) {
                val s = _state.value
                val rssi = if (s.link.bssid.equals(target, true)) s.link.rssi.toDouble() else s.aps.firstOrNull { it.bssid.equals(target, true) }?.smoothed
                if (rssi != null) {
                    _state.update { it.copy(locatorSamples = (it.locatorSamples + rssi).takeLast(60)) }
                    if (s.locatorSound) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, cfg.toneMs) }
                    delay(Locator.intervalMs(rssi, cfg.locatorMinMs, cfg.locatorMaxMs))
                } else delay(1000)
            }
        }
    }

    // ---- heatmap ----

    fun heatTarget(bssid: String) = _state.update { it.copy(heat = Heatmap(cfg.heatRows, cfg.heatCols, bssid.lowercase())) }

    /** Record the target's RSSI at a grid cell: [WifiConfig.heatSamples] one-second samples of the link (or the last scan). */
    fun heatRecord(r: Int, c: Int) {
        scope.launch {
            repeat(cfg.heatSamples) {
                val s = _state.value
                val t = s.heat.target.ifEmpty { s.link.bssid }
                val v = if (s.link.bssid.equals(t, true)) s.link.rssi else s.aps.firstOrNull { it.bssid.equals(t, true) }?.rssi
                if (v != null && v > -127) _state.update { st -> st.copy(heat = st.heat.copy(target = t).record(r, c, v)) }
                delay(1000)
            }
        }
    }

    fun heatClear() = _state.update { it.copy(heat = it.heat.clear()) }

    // ---- export ----

    /** (file name, mime type, bytes) of the requested export: csv, json or pcap. */
    fun export(kind: String): Triple<String, String, ByteArray> {
        val s = _state.value
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date())
        return when (kind) {
            "csv" -> Triple("wifi-scan-$stamp.csv", "text/csv", Export.csv(s.aps).toByteArray())
            "pcap" -> Triple("wifi-beacons-$stamp.pcap", "application/vnd.tcpdump.pcap", Export.pcap(s.aps))
            "heat" -> Triple("wifi-heatmap-$stamp.csv", "text/csv", buildString {
                append("row,col,samples,mean_dbm,target\n")
                s.heat.cells.values.sortedBy { it.row * 100 + it.col }.forEach { c -> append("${c.row},${c.col},${c.samples.size},${"%.1f".format(Locale.ROOT, c.mean)},${s.heat.target}\n") }
            }.toByteArray())
            else -> Triple("wifi-scan-$stamp.json", "application/json", Export.json(s.aps, s.link.takeIf { it.connected }, s.roams, System.currentTimeMillis()).toByteArray())
        }
    }
}

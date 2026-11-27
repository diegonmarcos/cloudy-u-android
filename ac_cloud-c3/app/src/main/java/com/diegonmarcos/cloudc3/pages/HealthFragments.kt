package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.OpsClient
import com.diegonmarcos.cloudc3.cloud.Services
import com.diegonmarcos.cloudlib.healthcore.HealthCore
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The device-side facts the page needs, read once per refresh: the token, the VPN network. Never a probe of the fleet. */
internal fun deviceVantage(ctx: Context): Pair<Boolean, Boolean?> {
    val token = runCatching { DaguPrefs(ctx).bearerToken.isNotBlank() }.getOrDefault(false)
    val vpn = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()
    return token to vpn
}

/** Ops > Health > Health (#1955 C): the Health debugger. The old public and private tables are its Lights, every field kept. */
@Composable
fun HealthDebugBody(onOpenUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val slice = remember { Declarations.fleet }
    val topo = TopologyLiveState.shared
    val net = NetLiveState.shared
    val version = ContainersSource.version
    var probes by remember { mutableStateOf<Map<String, HealthModel.ProbeOut>>(emptyMap()) }
    var ran by remember { mutableStateOf(false) }
    var internet by remember { mutableStateOf<HealthModel.ProbeOut?>(null) }
    var internetRan by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var probedAt by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(deviceVantage(ctx)) }
    var apiOk by remember { mutableStateOf<Boolean?>(null) }
    var apiWhy by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf<List<OpsLive.Report>?>(null) }
    var reportsWhy by remember { mutableStateOf("") }
    var fleet by remember { mutableStateOf<Map<String, XrayModel.State>>(emptyMap()) }

    suspend fun readLive() {
        val src = ApiFleetLive(ctx.applicationContext)
        device = deviceVantage(ctx)
        runCatching { ContainersSource.refresh(src) }
        runCatching { topo.refresh(slice, src, { releaseRuns(ctx.applicationContext, Declarations.topology.release) }, true) }
        runCatching { net.refresh(src, 60, true) }
        when (val r = src.get(OpsLive.REPORTS_PATH)) {
            is FleetLive.Result.Body -> { apiOk = true; apiWhy = ""; reports = OpsLive.parseReports(r.text); reportsWhy = if (reports == null) "the answer is not a report list" else "" }
            is FleetLive.Result.Failed -> { apiOk = false; apiWhy = r.why; reports = null; reportsWhy = r.why }
        }
        // the fleet engines' layer report (#1977): read when served, the explicit not-served state otherwise
        fleet = listOf("cloud_health_layers").associateWith { XrayModel.classify(src.get(XrayModel.dataPath(it))) }
    }
    LaunchedEffect(Unit) { readLive() }

    val pub = remember { Services.publicServices() }
    val priv = remember { Services.privateServices() }
    val engineReason = HealthCore.unavailable()

    fun probeAll() {
        if (busy) return
        scope.launch {
            busy = true
            readLive()
            val reason = HealthCore.unavailable()
            if (reason == null) {
                val net1 = HealthCore.probe("tcp://1.1.1.1:443", 3000)
                internet = net1.okJson?.let { HealthModel.parseProbe(it) }
                internetRan = true
                val out = mutableMapOf<String, HealthModel.ProbeOut>()
                val vpn = device.second
                val targets = (if (internet?.status == HealthModel.St.OK) pub.map { "lights.public:${it.name}" to HealthModel.publicUrl(it) } else emptyList()) +
                    (if (vpn != false) priv.mapNotNull { s -> HealthModel.privateTarget(s)?.let { "lights.private:${s.name}" to it } } else emptyList())
                targets.chunked(8).forEach { chunk ->
                    chunk.map { (id, url) -> async(Dispatchers.IO) { id to HealthCore.probe(url, 4000).okJson?.let { HealthModel.parseProbe(it) } } }.awaitAll()
                        .forEach { (id, p) -> if (p != null) out[id] = p }
                }
                probes = out
            } else { internetRan = true; internet = null; probes = emptyMap() }
            ran = true
            probedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            busy = false
        }
    }

    val entries = remember(version) { ContainersSource.current().entries }
    val inputs = remember(version, topo.live) { TopologyModel.Inputs(ContainersSource.current(), slice, TopologyModel.parseInfra(Declarations.infraSlice), topo.live, Declarations.topology) }
    val checks = remember(probes, ran, internet, internetRan, device, apiOk, apiWhy, reports, reportsWhy, fleet, topo.live, net.tunnels, net.tunnelsWhy, version, engineReason) {
        val v = HealthModel.Vantage(device.first, device.second, internet, internetRan)
        val base = HealthModel.vantageChecks(v) +
            HealthModel.lightsChecks(pub, priv, probes, v, engineReason, ran) +
            HealthPhone.containerChecks(entries) { TopologyModel.containerState(inputs, it) } +
            HealthPhone.vmChecks(slice.vms, topo.live.deployed) +
            HealthPhone.meshChecks(net.tunnels, net.tunnelsWhy, 180, 600) +
            HealthPhone.controlChecks(apiOk, apiWhy) +
            HealthPhone.deliveryChecks(reports, reportsWhy)
        val have = base.map { it.layer }.toSet()
        // every other layer reads the fleet engine report: the explicit state of that read, never a made-up reading
        val st = fleet["cloud_health_layers"]
        val fleetChecks = (st as? XrayModel.State.Got)?.report?.checks.orEmpty()
        val why = if (st == null) "route not served yet (#1977): not asked yet" else XrayModel.say(st)
        val notServed = HealthModel.LAYERS.map { it.id }.filter { it !in HealthModel.PHONE_LAYERS && fleetChecks.none { c -> c.layer == it } }.map { HealthModel.fleetNotServed(it, why) }
        base + fleetChecks + notServed
    }
    val analysis = remember(checks) {
        HealthCore.analyze(HealthModel.reportJson(checks), HealthModel.layersJson()).okJson?.let { HealthModel.parseAnalysis(it) }
    }
    val shown = remember(checks, analysis) { if (analysis == null) checks else HealthModel.applied(checks, analysis) }
    val notes = remember(engineReason, analysis, fleet) {
        buildList {
            engineReason?.let { add("$it: phone probes are not run and say so; nothing is guessed.") }
            if (engineReason == null && analysis == null) add("the engine could not attribute the checks: no root cause is shown.")
            add("https targets: the engine probes dns and tcp; tls and http phases are not served yet (health-core is std only, no TLS engine), so a public URL reads degraded with that reason, never up.")
            fleet.forEach { (f, s) -> add("fleet report $f: ${XrayModel.say(s)}") }
        }
    }
    HealthScreen(HealthUi(shown, analysis, notes, busy, probedAt), onProbe = { probeAll() }, onOpenUrl = onOpenUrl)
}

class HealthDebugFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        HealthDebugBody { url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(url) }
    }

    companion object { fun newInstance() = HealthDebugFragment() }
}

/** Ops > Health > Tests (#1955 C): X-ray C3 (per VM, per service, full) with the old Tests suites kept under Suites. */
class XrayFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val slice = remember { Declarations.fleet }
        val vmIds = remember { slice.vms.filter { it.wgIp != null }.map { it.alias } }
        val version = ContainersSource.version
        val svcNames = remember(version) { ContainersSource.current().entries.map { it.name } }
        var states by remember { mutableStateOf<Map<String, XrayModel.State>>(emptyMap()) }
        var runNote by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val src = ApiFleetLive(ctx.applicationContext)
            val ids = listOf(XrayModel.FULL_FAMILY) + vmIds.map { XrayModel.vmFamily(it) }
            states = ids.associateWith { XrayModel.classify(src.get(XrayModel.dataPath(it))) }
        }
        val full = XrayItem("full", "X-ray C3 full", "every VM (hub last), every service, the layer report, the diff", states[XrayModel.FULL_FAMILY] ?: XrayModel.State.Idle)
        val vms = vmIds.map { XrayItem(it, it, "per-VM X-ray", states[XrayModel.vmFamily(it)] ?: XrayModel.State.Idle) }
        val services = svcNames.map { XrayItem(it, it, "per-service X-ray: ${XrayModel.dataPath(XrayModel.serviceFamily(it))}", states[XrayModel.serviceFamily(it)] ?: XrayModel.State.NotServed(HealthModel.NOT_SERVED_ROUTE)) }
        XrayScreen(full, vms, services, runNote, onRun = { kind, subject ->
            val req = XrayModel.runRequest(kind, subject, if (kind == "xray-vm") vmIds else svcNames)
            if (req == null) runNote = "refused here: $kind of '$subject' is not a declared subject"
            else scope.launch {
                val bearer = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")
                runNote = "asking the API to run ${req.first} (${req.second})..."
                val r = withContext(Dispatchers.IO) { OpsClient.runReport(req.first, req.second, bearer) }
                runNote = when (r) {
                    is OpsClient.Outcome.Ok -> "the API accepted ${req.first} (${req.second}): ${r.body.take(160)}"
                    is OpsClient.Outcome.Failed -> "the API did not run ${req.first}: ${r.kind}: ${r.message.lineSequence().first().take(160)} (the X-ray engines are #1978, the routes #1977)"
                }
            }
        }, suites = { OpsMapBody(XrayModel.SUITES_MAP) })
    }

    companion object { fun newInstance() = XrayFragment() }
}

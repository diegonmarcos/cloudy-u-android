package com.diegonmarcos.cloudc3.pages

import com.diegonmarcos.superapp.fleetxray.FleetXrayExport

/**
 * #1955 C A2 Ops > Health > Tests is X-ray C3: the per-VM X-ray, the per-service X-ray and X-ray C3 full (every VM, every service, the layer
 * report, the diff), plus the old Tests suites kept as Suites. The engines are Rust and run on the fleet (#1978); this file only names the
 * reports, validates what the app may ask for, classifies the answer and exports a report. A route the API does not serve yet is the explicit
 * "route not served yet (#1977)" state, never an empty page that looks like a result.
 */
object XrayModel {
    val TABS = listOf("engines" to "Engines", "full" to "C3 full", "vms" to "VMs", "services" to "Services", "suites" to "Suites")

    /** The only kinds the app may ask the API to run. */
    val KINDS = listOf("xray-vm", "xray-service", "xray-full")
    private val SUBJECT = Regex("^[A-Za-z0-9_.-]{1,64}$")

    const val FULL_FAMILY = "cloud_xray_full"
    fun vmFamily(id: String) = "cloud_xray_vm_$id"
    fun serviceFamily(name: String) = "cloud_xray_service_$name"
    fun dataPath(family: String) = "/reports/data/$family"
    const val SUITES_MAP = "hier_ops_health_tests"

    /** #1983 X-Ray Device: one report per declared device (a fleet device with a short name; the watch has none). The report is built by the SuperApp's X-Ray (#1985). */
    fun deviceFamily(id: String) = "cloud_xray_device_$id"
    const val DEVICE_GAP = "X-Ray Device is built and exported on the phone (SuperApp, #1985) and posts to POST /xray/report, which c3-infra-api does not serve; reading it back needs GET /reports/data/<family> (#1977). No trigger from here: the report is taken on the device itself."

    /** The device cards: the fleet's declared devices that have a short name (SamsungA37, SamsungS21, SurfacePro8). */
    fun deviceIds(devices: List<FleetModel.Device>): List<String> = devices.mapNotNull { it.short?.takeIf { s -> s.isNotBlank() } }

    fun classifyDevice(r: FleetLive.Result): State = if (r is FleetLive.Result.Failed && r.why.startsWith("NOT_FOUND")) State.NotServed(DEVICE_GAP) else classify(r)

    /** The kind and subject of a run, validated; null when the app must not send it (an unknown kind, or a subject that is not a plain declared name). */
    fun runRequest(kind: String, subject: String, declared: Collection<String>): Pair<String, String>? {
        if (kind !in KINDS) return null
        if (kind == "xray-full") return kind to "fleet"
        if (!SUBJECT.matches(subject) || subject !in declared) return null
        return kind to subject
    }

    /** What reading one report gave. */
    sealed class State {
        object Idle : State()
        data class NotServed(val why: String) : State()
        data class Device(val why: String) : State()
        data class Blind(val why: String) : State()
        data class Invalid(val why: String) : State()
        data class Got(val report: HealthModel.Report) : State()
    }

    fun classify(r: FleetLive.Result): State = when (r) {
        is FleetLive.Result.Failed -> when {
            r.why.startsWith("NOT_FOUND") -> State.NotServed(HealthModel.NOT_SERVED_ROUTE)
            r.why.startsWith("NO_TOKEN") -> State.Device(HealthModel.NO_TOKEN_NOTE)
            else -> State.Blind("unreachable from here: ${r.why}")
        }
        is FleetLive.Result.Body -> HealthModel.parseReport(r.text)?.let { State.Got(it) } ?: State.Invalid("the answer is not a c3-report/v1 report")
    }

    /** The one line a state prints. */
    fun say(s: State): String = when (s) {
        State.Idle -> "not read yet"
        is State.NotServed -> s.why
        is State.Device -> "device: ${s.why}"
        is State.Blind -> s.why
        is State.Invalid -> s.why
        is State.Got -> "${s.report.status} · ${s.report.finished.ifBlank { s.report.started }} · ${s.report.runner} · ${s.report.checks.size} checks"
    }

    /** The report as the X-ray Device format (`# X-Ray`, `## Title`, `- key: value`), through the fleet's own exporter. */
    fun markdown(r: HealthModel.Report, name: String): String = FleetXrayExport.markdown(profile(r, name))
    fun html(r: HealthModel.Report, name: String): String = FleetXrayExport.html(profile(r, name))
    fun profile(r: HealthModel.Report, name: String) = FleetXrayExport.Profile(name, r.engine, r.engineVersion, r.finished.ifBlank { r.started },
        r.sections.map { s -> FleetXrayExport.Section(s.id, s.title, s.rows.map { FleetXrayExport.Row(it.key, it.value) }) })

    /** Rows of the full X-ray that changed against the previous one are the engine's `diff`; the app lists the findings ranked. */
    fun ranked(r: HealthModel.Report) = r.findings.sortedWith(compareBy({ it.rank }, { -it.explains }, { it.id }))
}

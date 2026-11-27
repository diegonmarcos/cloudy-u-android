package com.diegonmarcos.cloudc3.pages

import com.diegonmarcos.cloudc3.pages.HealthModel.Check
import com.diegonmarcos.cloudc3.pages.HealthModel.St

/**
 * #1955 C A2 The phone's own readings as c3-report/v1 checks, one function per layer. They only RENAME what an existing source said (docker ps through
 * the API, `GET /security/wireguard`, `GET /reports/status`) into a status with its reason; the probes and the attribution are the Rust engine's.
 * A read that failed for want of a token is BLIND with the device-side note (it blames no VM); a read that did not happen is PENDING.
 */
object HealthPhone {
    private fun noToken(why: String) = why.contains("NO_TOKEN")

    fun vmChecks(vms: List<FleetModel.Vm>, deployed: Map<String, TopologyModel.Deployed>): List<Check> = vms.filter { it.wgIp != null }.map { vm ->
        val id = "vms.docker_ps:${vm.alias}"
        val base = Check(id, "vms", vm.alias, St.PENDING, "not read yet", vm.id, "vm", needs = listOf("vantage.token"), facts = listOf("id" to vm.id, "wg ip" to vm.wgIp.orEmpty(), "role" to vm.wgRole.orEmpty()))
        when (val d = deployed[vm.alias] ?: deployed[vm.id]) {
            null -> base
            is TopologyModel.Deployed.Seen -> base.copy(status = St.OK, details = "docker ps answered: ${d.boxes.size} containers")
            is TopologyModel.Deployed.Blind ->
                if (noToken(d.why)) base.copy(status = St.BLIND, details = HealthModel.NO_TOKEN_NOTE)
                else base.copy(status = St.BLIND, details = "the API could not read this VM: ${d.why.take(120)}")
        }
    }

    fun containerChecks(entries: List<ContainersModel.Entry>, state: (ContainersModel.Entry) -> Pair<TopologyModel.Health, String>): List<Check> = entries.map { e ->
        val (h, text) = state(e)
        val c = Check("containers.state:${e.name}", "containers", e.name, St.PENDING, text, e.name, "container", needs = listOf("vms.docker_ps:${e.host}"),
            facts = listOf("host" to e.host, "service" to e.service, "image" to e.image))
        when (h) {
            TopologyModel.Health.OK -> c.copy(status = St.OK)
            TopologyModel.Health.WARN -> c.copy(status = St.WARN)
            TopologyModel.Health.BAD -> c.copy(status = St.DOWN)
            else -> if (noToken(text)) c.copy(status = St.BLIND, details = HealthModel.NO_TOKEN_NOTE) else c.copy(status = St.BLIND, details = "not read: $text")
        }
    }

    /** The mesh from `GET /security/wireguard`: one check per peer a VM reports. No answer is one BLIND check with the reason (a token-less phone says so and blames nobody). */
    fun meshChecks(tunnels: List<NetLive.WgVm>?, why: String, okS: Int, warnS: Int): List<Check> {
        if (tunnels == null) return listOf(Check("mesh.wireguard", "mesh", "WireGuard peers", if (why.isBlank()) St.PENDING else St.BLIND,
            if (why.isBlank()) "not read yet" else if (noToken(why)) HealthModel.NO_TOKEN_NOTE else "not read: $why", kind = "mesh", needs = listOf("vantage.token")))
        return tunnels.flatMap { vm ->
            if (vm.peers.isEmpty()) listOf(Check("mesh.peers:${vm.vm}", "mesh", "${vm.vm}: peers", St.BLIND, "no peers read (no WireGuard, or the SSH read failed)", vm.vm, "mesh"))
            else vm.peers.map { p ->
                val ip = p.allowedIps.firstOrNull().orEmpty()
                val age = NetLive.handshakeAgeS(p.latestHandshake)
                val (st, d) = when {
                    age == null -> St.WARN to "no handshake (idle or down)"
                    age <= okS -> St.OK to "handshake ${age}s ago"
                    age <= warnS -> St.WARN to "handshake ${age}s ago"
                    else -> St.WARN to "stale: handshake ${age}s ago"
                }
                Check("mesh.peer:${vm.vm}->$ip", "mesh", "${vm.vm} -> $ip", st, d, vm.vm, "mesh", facts = listOf("endpoint" to p.endpoint, "transfer" to p.transfer))
            }
        }
    }

    /** The C3 API itself, from the one route the Health page already calls. */
    fun controlChecks(apiOk: Boolean?, why: String): List<Check> = listOf(
        when {
            apiOk == true -> Check("control.api", "control", "C3 API (GET /reports/status)", St.OK, "answered", kind = "api")
            apiOk == null -> Check("control.api", "control", "C3 API (GET /reports/status)", St.PENDING, "not asked yet", kind = "api")
            noToken(why) -> Check("control.api", "control", "C3 API (GET /reports/status)", St.BLIND, HealthModel.NO_TOKEN_NOTE, kind = "api", needs = listOf("vantage.token"))
            else -> Check("control.api", "control", "C3 API (GET /reports/status)", St.WARN, "no answer from this phone: $why", kind = "api")
        })

    /** Report freshness from `GET /reports/status`: a report that is stale or failed is amber, never green; no list is not served. */
    fun deliveryChecks(reports: List<OpsLive.Report>?, why: String): List<Check> =
        if (reports == null) listOf(Check("delivery.reports", "delivery", "Published reports", if (noToken(why)) St.BLIND else if (why.isBlank()) St.PENDING else St.NOT_SERVED,
            if (noToken(why)) HealthModel.NO_TOKEN_NOTE else why.ifBlank { "not read yet" }, kind = "report"))
        else reports.map { r ->
            val c = Check("delivery.report:${r.family}", "delivery", r.family, St.OK, "published ${r.ageHours ?: "?"} h ago", r.family, "report")
            when { !r.ok -> c.copy(status = St.WARN, details = r.error.ifBlank { "the last run failed" }); r.stale -> c.copy(status = St.WARN, details = "stale: ${r.ageHours ?: "?"} h old"); else -> c }
        }
}

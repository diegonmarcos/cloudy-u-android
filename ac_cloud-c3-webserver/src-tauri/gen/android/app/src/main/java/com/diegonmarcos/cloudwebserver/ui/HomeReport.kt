package com.diegonmarcos.cloudwebserver.ui

import org.json.JSONObject

/**
 * #1845 the whole Home page as Markdown (Export all to Md / Copy all): Summary, Pages, API, SSHD, ADB Shell, Server and VPN, every table with
 * its rows, built from the SAME models the page draws (HomeModel.summaryRows / statusRows, the served, door and ADB JSON, VpnModel), so the
 * text and the screen cannot disagree. Pure: a JVM test pins it. Nothing secret is read into it (no token, no private key).
 */
object HomeReport {
    private fun on(b: Boolean?) = when (b) { true -> "OK"; false -> "FAIL"; null -> "n/a" }
    private fun bullet(k: String, v: String, up: Boolean? = null) = "- ${if (up == null) "" else on(up) + " "}**$k**: ${v.replace("\n", " ")}"

    /** Every scalar field of a status object, in the server's order. */
    private fun scalars(o: JSONObject?): List<String> = o?.keys()?.asSequence()?.toList().orEmpty().mapNotNull { k ->
        val v = o?.opt(k)
        if (v == null || v is JSONObject || v is org.json.JSONArray || JSONObject.NULL == v) null else bullet(k, v.toString())
    }

    private fun section(title: String, lines: List<String>) = "## $title\n\n" + lines.joinToString("\n") + "\n"

    fun markdown(h: HomeData, vpn: VpnReading?, stamp: String): String {
        val pw = h.pw
        val out = StringBuilder("# Cloud C3 Webserver / Home\n\n_${stamp}_\n\n")
        val sum = HomeModel.summaryRows(pw, h.health, h.served, h.sshd, h.adb, h.live)
        val state = HomeModel.summary(pw, listOf(HomeModel.pages(pw, h.served), HomeModel.api(pw, h.health), HomeModel.sshd(h.sshd, pw)), h.adb?.takeIf { it.has("server_up") && !it.isNull("server_up") }?.optBoolean("server_up"))
        out.append(section("Summary", listOf(bullet("Badge", state.name)) + sum.map { r ->
            bullet(r.name, "on ${HomeModel.onOff(r.lights.on)} · keep-alive ${HomeModel.onOff(r.lights.keepAlive)} · healthy ${HomeModel.onOff(r.lights.healthy)}${if (r.lights.disabled) " · disabled" else ""} · ${r.msg}${if (r.cause.isEmpty()) "" else " · cause: ${r.cause}"}", r.lights.healthy)
        } + listOfNotNull(HomeModel.attentionWhy(sum).takeIf { it.isNotEmpty() }?.let { bullet("Why ATTENTION", it) })))
        val svcOn = { k: String -> if (pw == null) null else HomeModel.svcFlag(pw, k) }
        val serverOn = pw?.optBoolean("server")
        val rows = h.served.objs("rows")
        out.append("\n").append(section("Pages", HomeModel.statusRows(svcOn("pages"), serverOn, HomeModel.pages(pw, h.served), rows.joinToString(", ") { ":" + it.str("port") },
            "${rows.count { it.optBoolean("serving") }} of ${rows.size} serving (connect probe per port)").map { bullet(it.key, it.value, it.up) } +
            rows.map { bullet(it.str("name"), ":${it.str("port")} · ${it.str("role")} · ${if (it.optBoolean("serving")) "serving" else "not serving"}", it.optBoolean("serving")) }))
        out.append("\n").append(section("API", HomeModel.statusRows(svcOn("api"), serverOn, HomeModel.api(pw, h.health), HomeModel.portsOf(pw, "diagnostics", "mesh", "dns"),
            if (h.health == null) "no answer from :8003" else if (h.health.optBoolean("ok")) "health ok" else "health not ok").map { bullet(it.key, it.value, it.up) } +
            scalars(h.health).map { "  $it" } + mcpTools(h.mcp).let { listOf(bullet("MCP tools", "${it.size}: ${it.joinToString(", ")}")) } +
            h.mesh.objs("groups").firstOrNull()?.objs("rows").orEmpty().map { bullet("Mesh", it.str("head")) }))
        val doors = h.sshd.objs("doors")
        out.append("\n").append(section("SSHD", HomeModel.statusRows(svcOn("sshd"), serverOn, HomeModel.sshd(h.sshd, pw), doors.filter { it.has("port") && !it.isNull("port") }.joinToString(", ") { "tcp " + it.str("port") },
            "${doors.count { sshState(it) == SshState.GREEN }} of ${doors.size} doors up (bind state)").map { bullet(it.key, it.value, it.up) } +
            doors.flatMap { d -> listOf(bullet(d.str("title"), sshRow(d).drop(1).joinToString(" · "), sshState(d) == SshState.GREEN)) + sshChecks(d).map { (k, v) -> "  - $k: $v" } + listOf(bullet("ssh command", sshCommand(d))) }))
        out.append("\n").append(section("ADB Shell", scalars(h.adb) + listOf(bullet("Owner side", if (h.live.owner) "linked" else "not linked", h.live.owner),
            bullet("Keep-alive", HomeModel.onOff(h.live.keepAlive)), bullet("Echo round trip", HomeModel.onOff(h.live.roundTrip)))))
        out.append("\n").append(section("Server", scalars(pw) + listOf(bullet("Ports bound", HomeModel.portsOf(pw, "admin", "diagnostics", "dns", "mesh", "site", "ssh").ifEmpty { "none" }),
            bullet("Services enabled", HomeModel.servicesMsg(pw)))))
        val (badge, up) = VpnModel.verdict(vpn)
        out.append("\n").append(section("VPN ($badge)", listOf(bullet("Verdict", badge, up), VpnModel.markdown(vpn))))
        return out.toString().trimEnd() + "\n"
    }
}

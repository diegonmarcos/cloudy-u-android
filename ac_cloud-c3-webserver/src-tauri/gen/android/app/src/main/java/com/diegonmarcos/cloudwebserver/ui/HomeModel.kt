package com.diegonmarcos.cloudwebserver.ui

import org.json.JSONObject

/** #1834 what the ADB owner side reads live (null = not readable: this build links no ADB channel, or not read yet). */
data class AdbLive(val owner: Boolean, val keepAlive: Boolean?, val roundTrip: Boolean?)

/** #1834 the three status lights of a Summary row: on/off, keep-alive on/off, healthy (a real probe answered). null = unknown, drawn muted. */
data class Lights(val on: Boolean?, val keepAlive: Boolean?, val healthy: Boolean?)

/**
 * #1834 pure model of Home's Summary: every light reads the real state of the service (its process or port answering, its own keep-alive
 * switch, a probe round trip), never a stored proxy. The probes themselves are made by the server (connect per port, the health route, the
 * sshd banner read-back) and by the ADB owner (an echo round trip); this only turns their JSON into lights and the status messages.
 */
object HomeModel {
    const val LEGEND = "Lights, left to right: on/off · keep-alive on/off · healthy (a probe answered)"

    private fun bool(o: JSONObject?, key: String): Boolean? = if (o == null || !o.has(key) || o.isNull(key)) null else o.optBoolean(key)

    /** #1836 a service's own enable flag (status `svc.<key>.enabled`); a missing block (an older server) means enabled. */
    fun svcFlag(pw: JSONObject?, key: String): Boolean = pw?.optJSONObject("svc")?.optJSONObject(key)?.optBoolean("enabled", true) ?: true

    /** #1836 does the service answer: Server (master) ON and its own flag ON. null = status not read. */
    fun svcOn(pw: JSONObject?, key: String): Boolean? = if (pw == null) null else pw.optBoolean("server") && svcFlag(pw, key)

    fun pages(pw: JSONObject?, served: JSONObject?): Lights {
        val rows = served.objs("rows"); val flag = svcFlag(pw, "pages")
        return Lights(if (served == null) null else flag && rows.any { it.optBoolean("serving") }, bool(pw, "keep_alive"), if (served == null) null else flag && rows.isNotEmpty() && rows.all { it.optBoolean("serving") })
    }

    fun api(pw: JSONObject?, health: JSONObject?): Lights {
        val flag = svcFlag(pw, "api")
        return Lights(if (pw == null) null else pw.optBoolean("server") && pw.optBoolean("running") && flag, bool(pw, "keep_alive"), if (!flag) false else health?.let { it.optBoolean("ok") } ?: if (pw == null) null else false)
    }

    /** #1836 the Server row: on = the process runs with Server ON, healthy = its admin and diagnostics listeners are both bound. */
    fun server(pw: JSONObject?): Lights {
        if (pw == null) return Lights(null, null, null)
        val names = pw.optJSONArray("ports").objs().map { it.optString("name") }
        val on = pw.optBoolean("server") && pw.optBoolean("running")
        return Lights(on, bool(pw, "keep_alive"), on && "admin" in names && "diagnostics" in names)
    }

    /** A door is on when its process check passed (else the old state word says listening / connected). */
    fun doorOn(d: JSONObject): Boolean {
        val p = d.optJSONObject("status")?.optJSONArray("checks").objs().firstOrNull { it.optString("id") == "process" }
        return if (p != null && p.has("ok") && !p.isNull("ok")) p.optBoolean("ok") else d.optString("state").let { it == "listening" || it == "connected" }
    }

    fun sshd(sshd: JSONObject?, pw: JSONObject? = null): Lights {
        val doors = sshd.objs("doors"); val flag = svcFlag(pw, "sshd")
        return Lights(if (sshd == null) null else flag && doors.any { doorOn(it) }, bool(sshd, "keep_alive"), if (sshd == null) null else flag && doors.isNotEmpty() && doors.all { sshState(it) == SshState.GREEN })
    }

    /** #1836 one row of a service table's common status block. */
    data class SRow(val key: String, val value: String, val up: Boolean?)

    /**
     * #1836 the status rows EVERY service table (Pages, API, SSHD, ADB Shell) draws, in this order, read from the live state:
     * the service's verdict, its own enable flag (null = it has no flag here: ADB Shell runs its own server), the master Server, the ports it
     * listens on, what its probe answered, its keep-alive.
     */
    fun statusRows(enabled: Boolean?, serverOn: Boolean?, l: Lights, ports: String, probe: String): List<SRow> {
        val state = when {
            l.on == null -> "unknown (status not read)"
            l.on -> if (l.healthy == true) "up" else "on, probe not healthy"
            enabled == false -> "stopped (service disabled, Server stays up for the others)"
            serverOn == false -> "stopped (Server OFF)"
            else -> "down"
        }
        return listOf(
            SRow("Service status", state, l.on),
            SRow("Enabled", when (enabled) { true -> "ON (own switch)"; false -> "OFF (own switch)"; null -> "own server, not this process" }, enabled),
            SRow("Server (master)", when (serverOn) { true -> "ON"; false -> "OFF"; null -> "?" }, serverOn),
            SRow("Listening on", ports.ifEmpty { "none" }, if (ports.isEmpty()) false else l.on),
            SRow("Probe", probe, l.healthy),
            SRow("Keep-alive", onOff(l.keepAlive).uppercase(), l.keepAlive),
        )
    }

    /** #1836 the ports the server reports as bound, by listener name (status `ports[].name`). */
    fun portsOf(pw: JSONObject?, vararg names: String): String =
        pw?.optJSONArray("ports").objs().filter { it.optString("name") in names }.joinToString(", ") { "${it.optString("proto")} ${it.opt("port")}" }

    /** #1836 the JSON a service's switch posts: OFF clears only its own flag (the others and the Server keep going); ON sets it and also turns the Server ON, which then binds only the enabled services. */
    fun svcSet(key: String, on: Boolean): JSONObject = JSONObject().put("svc_$key", on).also { if (on) it.put("server", true) }


    fun adb(adb: JSONObject?, live: AdbLive): Lights =
        Lights(bool(adb, "server_up"), live.keepAlive, live.roundTrip)

    fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }

    fun startStopMsg(pw: JSONObject?, adb: JSONObject?): String {
        val s = if (pw == null) "Server ?" else if (!pw.optBoolean("server")) "Server OFF" else if (pw.optBoolean("running")) "Server ON, running" else "Server ON, starting"
        return "$s · ADB server " + when (bool(adb, "server_up")) { true -> "answering"; false -> "not answering"; null -> "not probed" }
    }

    fun keepAliveMsg(pw: JSONObject?, live: AdbLive): String =
        "Keep alive ${if (pw == null) "?" else if (pw.optBoolean("keep_alive")) "ON" else "OFF"} (pages, API, sshd) · ADB keep-alive " + (live.keepAlive?.let { if (it) "ON" else "OFF" } ?: "n/a here")

    /** #1836 the Server table's one-line summary of which services are enabled. */
    fun servicesMsg(pw: JSONObject?): String =
        if (pw == null) "?" else listOf("pages" to "Pages", "api" to "API", "sshd" to "SSHD").joinToString(" · ") { (k, n) -> "$n ${if (svcFlag(pw, k)) "ON" else "OFF"}" }

    fun probeMsg(served: JSONObject?, health: JSONObject?, sshd: JSONObject?, adb: JSONObject?, live: AdbLive): String {
        val rows = served.objs("rows"); val doors = sshd.objs("doors")
        return "Pages ${rows.count { it.optBoolean("serving") }}/${rows.size} serving · API " + (if (health == null) "no answer" else if (health.optBoolean("ok")) "ok" else "not ok") +
            " · SSHD ${doors.count { sshState(it) == SshState.GREEN }}/${doors.size} up · ADB " + (live.roundTrip?.let { if (it) "echo ok" else "no echo" } ?: when (bool(adb, "server_up")) { true -> "port answers"; false -> "no answer"; null -> "not probed" })
    }
}

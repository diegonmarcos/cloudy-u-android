package com.diegonmarcos.cloudwebserver.ui

import org.json.JSONObject

/** #1834 what the ADB owner side reads live (null = not readable: this build links no ADB channel, or not read yet). */
data class AdbLive(val owner: Boolean, val keepAlive: Boolean?, val roundTrip: Boolean?)

/** #1834 the three status lights of a Summary row: on/off, keep-alive on/off, healthy (a real probe answered). null = unknown, drawn muted. */
data class Lights(val on: Boolean?, val keepAlive: Boolean?, val healthy: Boolean?, val disabled: Boolean = false)

/** #1836 the Summary badge: STOPPED / DISABLED are neutral choices of the owner, only ATTENTION is a fault. */
enum class SummaryState { UNKNOWN, STOPPED, STARTING, ALL_DISABLED, ALL_UP, ATTENTION }

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

    /** #1836 a service the owner switched off, or the Server stopped by choice (Keep alive OFF), is a neutral state, not a fault. */
    fun neutral(pw: JSONObject?, key: String): Boolean = pw != null && (!svcFlag(pw, key) || (!pw.optBoolean("server") && !pw.optBoolean("keep_alive")))

    private fun off(pw: JSONObject?) = Lights(null, bool(pw, "keep_alive"), null, true)

    /** #1836 the Summary badge: ATTENTION only when an ENABLED service is unhealthy, or the Server is down while Keep alive wants it up. */
    fun summary(pw: JSONObject?, services: List<Lights>, adbUp: Boolean?): SummaryState = when {
        pw == null -> SummaryState.UNKNOWN
        !pw.optBoolean("server") -> if (pw.optBoolean("keep_alive")) SummaryState.ATTENTION else SummaryState.STOPPED
        !pw.optBoolean("running") -> SummaryState.STARTING
        services.all { it.disabled } && adbUp != false -> SummaryState.ALL_DISABLED
        services.any { !it.disabled && it.healthy != true } || adbUp == false -> SummaryState.ATTENTION
        else -> SummaryState.ALL_UP
    }

    fun pages(pw: JSONObject?, served: JSONObject?): Lights {
        if (neutral(pw, "pages")) return off(pw)
        val rows = served.objs("rows"); val flag = svcFlag(pw, "pages")
        // #1845 healthy = every page THIS server serves answers; another app's page (external: its own server, closed app) and a Repos site the owner has not started are named by pagesCause, not a fault of the Pages service
        val mine = ours(rows)
        return Lights(if (served == null) null else flag && rows.any { it.optBoolean("serving") }, bool(pw, "keep_alive"), if (served == null) null else flag && mine.isNotEmpty() && mine.all { it.optBoolean("serving") })
    }

    private val NOT_OURS = setOf("external", "place", "adopted")
    private fun ours(rows: List<JSONObject>) = rows.filter { it.optString("role") !in NOT_OURS }

    /** One row of the Summary: the service, its three lights, its one-line state and the CAUSE when it is not healthy (empty when healthy). */
    data class SumRow(val name: String, val lights: Lights, val msg: String, val cause: String)

    /** #1845 why Pages is not healthy, by name: the pages this server serves that do not answer, then the others' pages that are closed. */
    fun pagesCause(served: JSONObject?): String {
        if (served == null) return "the served list was not read"
        val rows = served.objs("rows")
        val down = ours(rows).filter { !it.optBoolean("serving") }.joinToString(", ") { "${it.optString("name")} :${it.opt("port")} (${it.optString("role")})" }
        val ext = rows.filter { it.optString("role") == "external" && !it.optBoolean("serving") }.map { it.optString("name") }
        val place = rows.count { it.optString("role") == "place" && !it.optBoolean("serving") }
        return listOfNotNull(
            if (down.isEmpty()) null else "not answering: $down",
            if (ext.isEmpty()) null else "${ext.size} other app page(s) closed, their own app is not open: ${ext.joinToString(", ")} (not a fault here)",
            if (place == 0) null else "$place repo site(s) not started (the owner's choice)",
        ).joinToString(" · ")
    }

    fun apiCause(health: JSONObject?): String = when {
        health == null -> "no answer on the diagnostics port :8003 (its listener is not bound)"
        !health.optBoolean("ok") -> "the health route answered not ok"
        else -> ""
    }

    /** #1845 every door that is not up, named with its own verdict and reason; and the reconciliation when the service reads ON. */
    fun sshdCause(sshd: JSONObject?): String {
        val doors = sshd.objs("doors")
        if (doors.isEmpty()) return "no door declared"
        val bad = doors.filter { sshState(it) != SshState.GREEN }
        if (bad.isEmpty()) return ""
        val why = bad.joinToString("; ") { d ->
            val reason = d.optJSONObject("status")?.optString("reason").orEmpty().ifEmpty { d.optString("state") }
            "${d.optString("title")}${if (d.has("port") && !d.isNull("port")) " :" + d.opt("port") else ""} ${sshWord(d)}${if (reason.isEmpty()) "" else " ($reason)"}"
        }
        val alive = doors.count { doorOn(it) && sshState(it) != SshState.GREEN }
        return (if (alive > 0) "ON means a process answers its own check (${alive}), UP needs the bind and banner checks too: " else "") + why
    }

    fun adbCause(adb: JSONObject?, live: AdbLive): String {
        val up = bool(adb, "server_up")
        if (up == true && live.roundTrip != false) return ""
        val parts = mutableListOf<String>()
        if (adb == null) parts += "the ADB status was not read" else if (up == false) parts += "nothing answers on 127.0.0.1:${adb.optString("server_port").ifEmpty { "?" }}" else if (up == null) parts += "no probe (owner side or SuperApp missing)"
        if (live.roundTrip == false) parts += "no echo round trip"
        adb?.optString("last_start")?.takeIf { it.isNotEmpty() }?.let { parts += "last start: $it" }
        return parts.joinToString(" · ")
    }

    /** #1845 the Summary rows (Home draws them, the Markdown export prints them): the one place the lights, the message and the cause are built. */
    fun summaryRows(pw: JSONObject?, health: JSONObject?, served: JSONObject?, sshd: JSONObject?, adb: JSONObject?, live: AdbLive): List<SumRow> {
        val serverOn = pw?.optBoolean("server") == true
        val adbUp = bool(adb, "server_up")
        val pg = HomeModel.pages(pw, served); val ap = HomeModel.api(pw, health); val ss = HomeModel.sshd(sshd, pw); val sv = HomeModel.server(pw); val ab = HomeModel.adb(adb, live)
        val rows = served.objs("rows"); val doors = sshd.objs("doors")
        fun cause(l: Lights, c: String) = if (l.disabled || l.healthy == true) "" else c
        return listOf(
            SumRow("Server", sv, if (pw == null) "not answering" else if (!serverOn) (if (sv.disabled) "Stopped (admin page stays up)" else "OFF, Keep alive wants it up (fault)") else "pid ${pw.optString("pid")} · up ${fmtUp(if (pw.has("uptime_s")) pw.optLong("uptime_s") else null)} · ${servicesMsg(pw)}",
                if (pw != null && serverOn && sv.healthy != true) "the admin or diagnostics listener is not bound" else ""),
            SumRow("Pages", pg, "${rows.count { it.optBoolean("serving") }} of ${rows.size} serving (connect probe per port)" + (if (pg.disabled) " · Disabled" else "") +
                (if (pg.healthy == true) pagesCause(served).let { if (it.isEmpty()) "" else " · $it" } else ""), cause(pg, pagesCause(served))),
            SumRow("API", ap, if (ap.disabled) "Disabled" else if (health == null) "not answering" else if (health.optBoolean("ok")) "health ok (:8003 answered)" else "health not ok", cause(ap, apiCause(health))),
            SumRow("SSHD", ss, (if (ss.disabled) "Disabled · " else "") + "${doors.count { sshState(it) == SshState.GREEN }} of ${doors.size} doors up (bind state)", cause(ss, sshdCause(sshd))),
            SumRow("ADB Shell", ab, when (adbUp) { true -> "server answering"; false -> "server not answering"; null -> "no probe (owner side or SuperApp missing)" } + (live.roundTrip?.let { if (it) " · echo round trip ok" else " · no echo" } ?: ""),
                if (adbUp == true && live.roundTrip != false) "" else adbCause(adb, live)),
        )
    }

    /** #1845 the one line under the badge: which rows make it ATTENTION. */
    fun attentionWhy(rows: List<SumRow>): String =
        rows.filter { !it.lights.disabled && it.cause.isNotEmpty() }.joinToString(" · ") { "${it.name}: ${it.cause}" }

    fun api(pw: JSONObject?, health: JSONObject?): Lights {
        if (neutral(pw, "api")) return off(pw)
        val flag = svcFlag(pw, "api")
        return Lights(if (pw == null) null else pw.optBoolean("server") && pw.optBoolean("running") && flag, bool(pw, "keep_alive"), if (!flag) false else health?.let { it.optBoolean("ok") } ?: if (pw == null) null else false)
    }

    /** #1836 the Server row: on = the process runs with Server ON, healthy = its admin and diagnostics listeners are both bound. */
    fun server(pw: JSONObject?): Lights {
        if (pw == null) return Lights(null, null, null)
        val names = pw.optJSONArray("ports").objs().map { it.optString("name") }
        if (!pw.optBoolean("server") && !pw.optBoolean("keep_alive")) return off(pw)
        val on = pw.optBoolean("server") && pw.optBoolean("running")
        return Lights(on, bool(pw, "keep_alive"), on && "admin" in names && "diagnostics" in names)
    }

    /** A door is on when its process check passed (else the old state word says listening / connected). */
    fun doorOn(d: JSONObject): Boolean {
        val p = d.optJSONObject("status")?.optJSONArray("checks").objs().firstOrNull { it.optString("id") == "process" }
        return if (p != null && p.has("ok") && !p.isNull("ok")) p.optBoolean("ok") else d.optString("state").let { it == "listening" || it == "connected" }
    }

    fun sshd(sshd: JSONObject?, pw: JSONObject? = null): Lights {
        if (neutral(pw, "sshd")) return off(pw)
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
            l.disabled -> if (enabled == false) "Disabled (service OFF, the Server stays up for the others)" else "Stopped (Server OFF by choice)"
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
        pw?.optJSONArray("ports").objs().filter { it.optString("name") in names }.joinToString(", ") { "${it.optString("proto")} ${it.optString("addr").let { a -> if (a.isEmpty()) "" else "$a:" }}${it.opt("port")}" }

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

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

    fun pages(pw: JSONObject?, served: JSONObject?): Lights {
        val rows = served.objs("rows")
        return Lights(if (served == null) null else rows.any { it.optBoolean("serving") }, bool(pw, "keep_alive"), if (served == null) null else rows.isNotEmpty() && rows.all { it.optBoolean("serving") })
    }

    fun api(pw: JSONObject?, health: JSONObject?): Lights =
        Lights(if (pw == null) null else pw.optBoolean("server") && pw.optBoolean("running"), bool(pw, "keep_alive"), health?.let { it.optBoolean("ok") } ?: if (pw == null) null else false)

    /** A door is on when its process check passed (else the old state word says listening / connected). */
    fun doorOn(d: JSONObject): Boolean {
        val p = d.optJSONObject("status")?.optJSONArray("checks").objs().firstOrNull { it.optString("id") == "process" }
        return if (p != null && p.has("ok") && !p.isNull("ok")) p.optBoolean("ok") else d.optString("state").let { it == "listening" || it == "connected" }
    }

    fun sshd(sshd: JSONObject?): Lights {
        val doors = sshd.objs("doors")
        return Lights(if (sshd == null) null else doors.any { doorOn(it) }, bool(sshd, "keep_alive"), if (sshd == null) null else doors.isNotEmpty() && doors.all { sshState(it) == SshState.GREEN })
    }

    fun adb(adb: JSONObject?, live: AdbLive): Lights =
        Lights(bool(adb, "server_up"), live.keepAlive, live.roundTrip)

    fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }

    fun startStopMsg(pw: JSONObject?, adb: JSONObject?): String {
        val s = if (pw == null) "Server ?" else if (!pw.optBoolean("server")) "Server OFF" else if (pw.optBoolean("running")) "Server ON, running" else "Server ON, starting"
        return "$s · ADB server " + when (bool(adb, "server_up")) { true -> "answering"; false -> "not answering"; null -> "not probed" }
    }

    fun keepAliveMsg(pw: JSONObject?, live: AdbLive): String =
        "Keep alive ${if (pw == null) "?" else if (pw.optBoolean("keep_alive")) "ON" else "OFF"} (pages, API, sshd) · ADB keep-alive " + (live.keepAlive?.let { if (it) "ON" else "OFF" } ?: "n/a here")

    fun probeMsg(served: JSONObject?, health: JSONObject?, sshd: JSONObject?, adb: JSONObject?, live: AdbLive): String {
        val rows = served.objs("rows"); val doors = sshd.objs("doors")
        return "Pages ${rows.count { it.optBoolean("serving") }}/${rows.size} serving · API " + (if (health == null) "no answer" else if (health.optBoolean("ok")) "ok" else "not ok") +
            " · SSHD ${doors.count { sshState(it) == SshState.GREEN }}/${doors.size} up · ADB " + (live.roundTrip?.let { if (it) "echo ok" else "no echo" } ?: when (bool(adb, "server_up")) { true -> "port answers"; false -> "no answer"; null -> "not probed" })
    }
}

package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.Api
import org.json.JSONObject

/**
 * The SSHd section (the web shell's renderSshd, /__app__/api/sshd, declared in build.json::ssh_doors): Keep alive with its live state,
 * every SSH door (state, engine, port and bind, observed bind, auth, mesh-only, authorized keys, host app, uptime, sessions, note) with
 * Start / Stop / Follow Keep alive and the per-port Mesh-only switch, the other SSH servers answering on loopback, and Copy All Infos.
 */
@Composable
fun SshdPage() {
    val (tick, bump) = rememberTick()
    val d = rememberObj(Api.SSHD, tick)
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    PageColumn("c3_sshd") {
        Acts { Act("Refresh", "c3_sshd_refresh") { bump() } }
        Loaded(d, "sshd") { data ->
            val on = data.optBoolean("keep_alive")
            val hs = data.objs("hosted").joinToString("; ") { h -> "sshd :${h.str("port")} " + if (h.optBoolean("listening")) "listening" else if (h.optBoolean("wanted")) "not listening yet" else "off" }
            Card("Keep alive", "c3_keepalive", badges = { Badge(if (on) "ON" else "OFF", if (on) Tone.Up else Tone.Down) }) {
                SwitchRow("Keep alive", on, "Keep alive is ${if (on) "ON" else "OFF"}; the supervised door is ${if (data.optBoolean("wanted")) "wanted" else "not wanted"}${if (hs.isNotEmpty()) "; $hs" else ""}. ON keeps the server and the hosted sshd up with a notification and starts them at boot; OFF stops them when the app closes.", "c3_sshd_keep_alive") {
                    actions.post(Api.POWER, JSONObject().put("keep_alive", it)) { "Applied" }
                }
            }
            val cidrs = data.optJSONArray("mesh_cidrs").strs()
            data.objs("doors").forEach { door -> Door(door, cidrs, actions) }
            val other = data.objs("other")
            if (other.isNotEmpty()) Card("Other SSH servers answering on loopback", "c3_sshd_other") { other.forEach { x -> Kv("tcp ${x.str("port")}", "${x.str("engine")} (${x.str("banner")})") } }
            Card("All doors", "c3_sshd_all") {
                Acts { Act("Copy All Infos", "c3_copy_doors") { copyText(ctx, "doors", doorsText(data)) } }
                Note("Keep alive ${if (on) "ON" else "OFF"}; the supervised door is ${if (data.optBoolean("wanted")) "wanted" else "not wanted"}")
            }
        }
    }
}

/** doorFacts: the rows of one door. */
fun doorFacts(d: JSONObject): List<Pair<String, String>> {
    val ses = if (!d.has("sessions") || d.isNull("sessions")) "unknown: ${d.str("sessions_note")}"
        else d.optJSONArray("sessions").strs().let { if (it.isEmpty()) "none connected" else "${it.size} connected: ${it.joinToString(", ")}" }
    val port = if (!d.has("port") || d.isNull("port")) "no port" else "tcp ${d.str("port")}"
    return listOf(
        "State" to d.str("state"),
        "Engine" to d.str("engine") + (d.optString("banner").takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: ""),
        "Port and bind" to port + (d.optString("bind").takeIf { it.isNotEmpty() && it != "null" }?.let { " on $it" } ?: ""),
        "Bind (observed)" to bindText(d),
        "Auth" to d.str("auth"),
        "Mesh-only (this port)" to if (!d.has("mesh_only")) "-" else if (d.optBoolean("mesh_only")) "ON" else "OFF",
        "Authorized keys" to if (!d.has("authorized_keys") || d.isNull("authorized_keys")) "not readable here: ${d.str("authorized_keys_note")}" else d.str("authorized_keys"),
        "Hosted by" to d.str("app") + (d.optString("package").takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: ""),
        "Seen up" to fmtUp(d.longOrNull("seen_up_s")),
        "Sessions" to ses,
        "Note" to d.str("note"),
    )
}

fun bindText(d: JSONObject): String = when (val v = d.optString("bind_state")) {
    "n/a" -> "no listener"
    "unknown" -> "not observable: this Android hides /proc/net from apps"
    else -> d.optJSONArray("binds").strs().let { if (it.isEmpty()) "nothing listening" else it.joinToString(", ") } + " (" + (if (v == "exposed") "all interfaces / EXPOSED" else v) + ")"
}

fun doorsText(data: JSONObject): String {
    val out = mutableListOf("Keep alive: ${if (data.optBoolean("keep_alive")) "ON" else "OFF"} (mesh ranges ${data.optJSONArray("mesh_cidrs").strs().joinToString(", ")})")
    data.objs("doors").forEach { d -> out += d.str("title") + "\n" + doorFacts(d).joinToString("\n") { "  ${it.first}: ${it.second}" } }
    data.objs("other").forEach { x -> out += "Other SSH server\n  tcp ${x.str("port")}: ${x.str("engine")} (${x.str("banner")})" }
    return out.joinToString("\n\n")
}

@Composable
private fun Door(d: JSONObject, cidrs: List<String>, actions: Actions) {
    val ctx = LocalContext.current
    val st = d.str("state")
    val up = st == "listening" || st == "connected"
    val id = d.optString("id")
    Card(d.str("title"), "c3_door_$id", badges = {
        Badge(st, if (up) Tone.Up else Tone.Down)
        when (d.optString("bind_state")) { "mesh-only" -> Badge("mesh-only", Tone.Up); "exposed" -> Badge("all interfaces / EXPOSED", Tone.Bad); else -> {} }
    }) {
        doorFacts(d).forEach { (k, v) -> Kv(k, v) }
        val c = d.optJSONObject("control") ?: JSONObject()
        val avail = c.optBoolean("available")
        fun op(o: String) { if (!avail) { toast(ctx, c.optString("note")) } else { actions.post(Api.SSHD, JSONObject().put("door", id).put("op", o)) { "Asked" } } }
        Acts {
            Act("Start", "c3_door_start") { op("start") }
            Act("Stop", "c3_door_stop") { op("stop") }
            if (avail && c.has("explicit") && !c.isNull("explicit")) Act("Follow Keep alive", "c3_door_follow") { op("follow") }
        }
        Note(c.optString("note"))
        if (d.has("port") && !d.isNull("port")) {
            val on = d.optBoolean("mesh_only")
            SwitchRow("SSH reachable over mesh VPN only", on, "Port ${d.str("port")} only. ON restricts it to loopback and the mesh ranges ${cidrs.joinToString(", ")} (resolved at each start; with no mesh address it stays closed). OFF opens every interface, Wi-Fi, LAN and cellular included.", "c3_door_mesh_only") {
                actions.post(Api.SSHD, JSONObject().put("door", id).put("mesh_only", it)) { "Mesh-only applied" }
            }
        }
    }
}

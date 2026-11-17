package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitDensity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.Decl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home = the STATUS cockpit, the web shell's renderHome: Status (power + health + version), the Server switches, the ADB shell status table
 * (#1723), Served pages, SSHd engines, Ports & DNS, the Device strip, the API topology link and the full device report (About groups).
 * #1723 drawn as a dense console: thin panels, compact aligned table rows (Dense.kt, KitDensity), every item of before kept.
 */
@Composable
fun HomePage(open: (String, String?) -> Unit) {
    val (tick, bump) = rememberTick()
    val power = rememberObj(Api.POWER, tick)
    val health = rememberObj(Api.HEALTH, tick)
    val serving = rememberObj(Api.SERVING, tick)
    val served = rememberObj(Api.SERVED, tick)
    val sshd = rememberObj(Api.SSHD, tick)
    val about = rememberArr(Api.ABOUT, tick)
    DensePage("c3_home") {
        DActs { DAct("Refresh", "c3_home_refresh") { bump() } }
        DLoaded(power, "power") { pw ->
            val sv = (serving as? Load.Ok)?.value
            HealthCard(pw, (health as? Load.Ok)?.value, health is Load.Err, sv)
            PowerCard(pw, bump)
        }
        // #1724 each requirement of the keep-alive service, real state + fix (its own section, independent of the server round trips)
        KeepAliveHealthCard(tick, open)
        // #1723 the ADB shell status table (the owner side reads the real server; the shipped stand-in names the owner)
        com.diegonmarcos.cloudwebserver.owner.AdbOwnerSide.HomeStatus(tick, open)
        DLoaded(served, "served pages") { ServedStrip(it) { open(Decl.SECTION_PAGES, Decl.PAGE_ALL) } }
        DLoaded(sshd, "sshd") { SshdStrip(it) { open(Decl.SECTION_SSHD, null) } }
        DLoaded(serving, "serving") { PortsStrip(it) }
        DLoaded(about, "device") { DeviceStrip(it) { open(Decl.SECTION_CONFIGS, Decl.PAGE_DEVICE) } }
        DenseCard("API topology", "c3_home_topology") {
            DRow("Topology", "Open the API topology (MCP → API → IPC)", tag = "c3_home_goto_api") { open(Decl.SECTION_API, Decl.PAGE_TOPOLOGY) }
        }
        DLoaded(about, "about") { AboutGroups(it) }
    }
}

/** renderHealth: the master banner. */
@Composable
fun HealthCard(pw: JSONObject, health: JSONObject?, healthDown: Boolean, sv: JSONObject?) {
    val on = pw.optBoolean("server")
    val running = pw.optBoolean("running")
    val state = if (on) (if (running) "Server ON, serving" else "Server ON, starting") else "Server OFF: pages, sshd, DNS and sites stopped; this admin page stays up"
    DenseCard("Status", "c3_home_status", badges = { Badge(if (on) "ON" else "OFF", if (on) Tone.Up else Tone.Down) }) {
        DRow("State", state, up = on && running)
        DRow("Version", if (sv != null) "${sv.str("app")} ${sv.str("version_name")}" else "-")
        DRow("Uptime", if (health != null && health.has("uptime_s")) "${fmtUp(health.longOrNull("uptime_s"))} (pid ${health.str("pid")})" else "-")
        DRow("Health", if (health != null) (if (health.optBoolean("ok")) "ok" else "not ok") else if (healthDown) "not answering (Server OFF stops :8003)" else "-")
        DRow("Keep alive", "${if (pw.optBoolean("keep_alive")) "ON" else "OFF"} · ${pw.str("sites")} active sites")
    }
}

/** renderPower: Server, Keep alive and the SSH door switches with the live state (POST /__app__/api/power). */
@Composable
fun PowerCard(st: JSONObject, bump: () -> Unit) {
    val actions = rememberActions(bump)
    var confirmOff by remember { mutableStateOf(false) }
    val ssh = st.optJSONObject("ssh_door")
    fun set(key: String, value: Any?) = actions.post(Api.POWER, JSONObject().put(key, value ?: JSONObject.NULL)) { "Applied" }
    DenseCard("Server", "c3_power") {
        SwitchRow("Server", st.optBoolean("server"), "OFF stops the served pages, DNS, the mesh listener, sshd and every site; this admin page stays up so you can switch it back ON", "c3_power_server") { to ->
            if (!to) { confirmOff = true } else { set("server", true) }
        }
        SwitchRow("Keep alive", st.optBoolean("keep_alive"), "ON keeps the server up with a notification and starts it at boot; OFF stops it when the app closes", "c3_power_keep_alive") { set("keep_alive", it) }
        SwitchRow("SSH door :${ssh.str("port")}", ssh?.optBoolean("wanted") == true, "ON asks Cloud Terminal Nix-on-Droid to start its sshd; follows Keep alive until set here. It is stopped again only if this app started it", "c3_power_ssh_door") { set("ssh_door", it) }
        DRow("State", if (st.optBoolean("running")) "running" else "stopped")
        DRow("Ports", st.optJSONArray("ports").objs().joinToString(", ") { "${it.str("proto")} ${it.str("port")}" }.ifEmpty { "none" })
        val follows = ssh == null || !ssh.has("explicit") || ssh.isNull("explicit")
        DRow("SSH door", "tcp ${ssh.str("port")} ${ssh.str("state")} · ${if (follows) "follows Keep alive" else "own switch"} · via ${ssh.str("via")}")
        if (!follows) DActs { DAct("Follow Keep alive", "c3_power_ssh_follow") { set("ssh_door", null) } }
        DRow("Active sites", st.str("sites"))
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Turn the Server OFF?") },
        text = { Text("Served pages, sshd, DNS and sites stop. This admin page stays up, so you can turn it back ON here.") },
        confirmButton = { TextButton({ confirmOff = false; set("server", false) }) { Text("Turn OFF") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } },
    )
}

/** A switch as one table row: state dot, label, ON/OFF, the compact toggle at the right; the explanation is the caption below. */
@Composable
fun SwitchRow(label: String, on: Boolean, note: String, tag: String, onSet: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH, vertical = Dense.ROW_V), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DRow2(label, if (on) "ON" else "OFF", on) }
            DAct(if (on) "Turn OFF" else "Turn ON", tag) { onSet(!on) }
        }
        DNote(note)
    }
}

/** DRow without its own outer padding (it sits inside another padded row). */
@Composable
private fun DRow2(k: String, v: String, up: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(Dense.KEY_COL), verticalAlignment = Alignment.CenterVertically) {
            DDot(up); Spacer(Modifier.width(4.dp))
            Text(k, style = MaterialTheme.typography.bodySmall.copy(fontSize = KitDensity.caption), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Text(v, style = MaterialTheme.typography.bodySmall.copy(fontSize = KitDensity.body), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** renderServedStrip: a real table (name | port | role | state). */
@Composable
fun ServedStrip(pg: JSONObject, goto: () -> Unit) {
    val rows = pg.objs("rows")
    DenseCard("Served pages · ${rows.size}", "c3_home_pages", trailing = { DLink("Pages ›", "c3_home_goto_pages", goto) }) {
        val w = listOf<Int?>(null, 46, 74, 70)
        DCols(listOf("name", "port", "role", "state"), w, head = true)
        rows.forEach { r ->
            val on = r.optBoolean("serving")
            DCols(listOf(r.str("name") + if (r.has("app")) " · app ${r.str("app")}" else "", ":${r.str("port")}", r.str("role"), if (on) "serving" else if (r.optBoolean("paused")) "stopped" else "not serving"), w, up = on)
        }
        DRow("Undeclared ports", (pg.optJSONObject("undeclared")?.optJSONArray("items")?.length() ?: 0).toString())
    }
}

/** renderSshdStrip. */
@Composable
fun SshdStrip(d: JSONObject, goto: () -> Unit) {
    DenseCard("SSHd engines", "c3_home_sshd", badges = { Badge("keep alive ${if (d.optBoolean("keep_alive")) "ON" else "OFF"}") }, trailing = { DLink("SSHd ›", "c3_home_goto_sshd", goto) }) {
        d.objs("doors").forEach { x ->
            val st = x.str("state")
            val ctl = x.optJSONObject("control")
            val own = ctl != null && ctl.has("explicit") && !ctl.isNull("explicit")
            DRow(x.str("title"), "${if (x.has("port") && !x.isNull("port")) "tcp ${x.str("port")}" else "no port"} · $st · ${sshWord(x)} · mesh ${sshMesh(x)} · ${if (own) "own switch" else "follows keep alive"}", up = sshState(x) == SshState.GREEN)
        }
    }
}

/** renderPortsStrip. */
@Composable
fun PortsStrip(sv: JSONObject) {
    DenseCard("Ports & DNS", "c3_home_ports") {
        sv.objs("endpoints").forEach { e ->
            val up = if (e.has("up") && !e.isNull("up")) e.optBoolean("up") else null
            DRow(e.str("name"), "127.0.0.1:${e.str("port")} · ${e.str("role")}${when (up) { true -> " · answering now"; false -> " · not answering"; null -> "" }}", up = up == true, mono = true)
        }
        val dns = sv.optJSONObject("dns")
        DRow("DNS", "udp ${dns.str("host")}:${dns.str("port")} · ${dns.str("answer")}", mono = true)
    }
}

/** deviceFacts: uptime, memory, storage, battery and network out of the About groups. */
fun deviceFacts(groups: JSONArray): List<Pair<String, String>> {
    val sections = groups.objs().flatMap { it.objs("sections") }
    fun sec(title: String) = sections.firstOrNull { it.optString("title") == title }
    fun v(s: JSONObject?, key: String) = s?.optJSONArray("rows").pairs().firstOrNull { it.first == key }?.second
    fun first(s: JSONObject?) = s?.optJSONArray("rows").pairs().firstOrNull()?.let { "${it.first} ${it.second}" }
    val up = v(sec("App"), "Server uptime") ?: v(sec("Kernel"), "System uptime") ?: sections.firstNotNullOfOrNull { v(it, "Server uptime") }
    val mem = sec("Memory")
    val bat = sec("Battery & Usage")
    val ram = v(mem, "Available")?.let { "$it free of ${v(mem, "Total RAM") ?: "-"}" }
    val batt = v(bat, "Level")?.let { l -> l + (v(bat, "Status")?.let { " $it" } ?: "") }
    return listOf("Uptime" to up, "Memory" to ram, "Storage" to first(sec("Storage")), "Battery" to batt, "Network" to first(sec("Network"))).map { it.first to (it.second ?: "-") }
}

@Composable
fun DeviceStrip(groups: JSONArray, goto: () -> Unit) {
    val ctx = LocalContext.current
    DenseCard("Device", "c3_home_device", trailing = { DLink("Full report ›", "c3_home_goto_device", goto) }) {
        deviceFacts(groups).forEach { (k, v) -> DRow(k, v) }
        DActs { DAct("Copy All Infos", "c3_copy_about") { copyText(ctx, "about", aboutText(groups)) } }
    }
}

/** renderAbout: each macro group's sections. */
@Composable
fun AboutGroups(groups: JSONArray) {
    groups.objs().forEach { g ->
        DNote(g.str("macro").uppercase())
        g.objs("sections").forEach { s -> DenseCard(s.str("title")) { s.optJSONArray("rows").pairs().forEach { (k, v) -> DRow(k, v) } } }
    }
}

/** aboutText: the copy-all format of the web shell. */
fun aboutText(groups: JSONArray): String = groups.objs().joinToString("\n\n") { g ->
    "== ${g.optString("macro")} ==\n" + g.objs("sections").joinToString("\n\n") { s ->
        "# ${s.optString("title")}\n" + s.optJSONArray("rows").pairs().joinToString("\n") { "${it.first}: ${it.second}" }
    }
} + "\n"

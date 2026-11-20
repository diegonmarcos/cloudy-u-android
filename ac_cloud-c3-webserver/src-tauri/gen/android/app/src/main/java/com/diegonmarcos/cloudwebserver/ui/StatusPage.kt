package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.owner.AdbOwnerSide
import com.diegonmarcos.superapp.uikit.KitDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1834 Home = a Summary (three real status lights per service and the three bulk actions) and then ONE TABLE PER SERVICE (Pages, API,
 * SSHD, ADB Shell), each carrying that service's whole control chain. The tables the previous Home drew (Status, Server, Keep-alive
 * health, Endpoints, Ports & DNS, Device, About) are folded into the service they describe; test-c3-webserver-home-dense.sh freezes their
 * field list and checks each field is still drawn here (the keep-alive health block and the Server summary row were removed by the owner,
 * the device report stays on Configs > Device). Every state shown is a real probe the server made (a TCP connect, the health route,
 * the sshd banner read-back, the ADB echo round trip), never a stored flag.
 */
object UiMode {
    /** View only / Edit: View only refuses every switch on the page (read, never change). Edit is the default so nothing that worked stops working. */
    var edit by mutableStateOf(true)
}

/** A category band: the heading every group of Home starts with. */
@Composable
fun SectionBand(id: String, title: String, note: String = "") {
    androidx.compose.foundation.layout.Column(Modifier.testTag("c3_sec_$id").padding(top = 6.dp, start = 2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = KitDensity.eyebrowTracking)
        if (note.isNotEmpty()) DNote(note)
    }
}

/** Everything Home reads (null = not answering / not read yet) and the three things it can do to the whole server. */
class HomeData(
    val pw: JSONObject?, val health: JSONObject?, val sv: JSONObject?, val served: JSONObject?, val sshd: JSONObject?, val about: JSONArray?,
    val roots: JSONObject?, val adb: JSONObject?, val mcp: JSONObject?, val mesh: JSONObject?, val live: AdbLive,
    val bump: () -> Unit, val open: (String, String?) -> Unit,
    /** Turn the Server OFF (asks first); the flag says the ADB server goes down with it (Stop All). */
    val askOff: (Boolean) -> Unit,
    val setPower: (String, Any?) -> Unit,
)

/** The mcp tools of the api-mcp sub page: every row head is one tool. */
fun mcpTools(mcp: JSONObject?): List<String> = mcp.objs("groups").flatMap { g -> g.objs("rows").map { it.str("head") } }

private fun edit(ctx: android.content.Context, go: () -> Unit) { if (UiMode.edit) go() else toast(ctx, "View only: switch Mode to Edit to change this") }

/** Summary: one row per service with its three lights, the Mode switch and the three bulk actions with their live status messages. */
@Composable
fun SummaryTable(h: HomeData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var probed by remember { mutableStateOf("") }
    var started by remember { mutableStateOf("") }
    var kept by remember { mutableStateOf("") }
    val pw = h.pw
    val serverOn = pw?.optBoolean("server") == true
    val adbUp = h.adb?.takeIf { it.has("server_up") && !it.isNull("server_up") }?.optBoolean("server_up")
    val pages = HomeModel.pages(pw, h.served); val api = HomeModel.api(pw, h.health); val ssh = HomeModel.sshd(h.sshd); val adb = HomeModel.adb(h.adb, h.live)
    val rows = h.served.objs("rows"); val doors = h.sshd.objs("doors")
    val allUp = pages.healthy == true && api.healthy == true && ssh.healthy == true && adbUp != false
    DenseCard("Summary", "c3_status_summary", badges = { Badge(if (pw == null) "?" else if (!serverOn) "OFF" else if (pw.optBoolean("running").not()) "STARTING" else if (allUp) "ALL UP" else "ATTENTION", if (allUp && serverOn) Tone.Up else Tone.Down) },
        trailing = { DLink("Device ›", "c3_home_goto_device") { h.open(Decl.SECTION_CONFIGS, Decl.PAGE_DEVICE) } }) {
        DLights("Pages", pages, "${rows.count { it.optBoolean("serving") }} of ${rows.size} serving (connect probe per port)", "c3_sum_pages") { h.open(Decl.SECTION_PAGES, Decl.PAGE_ALL) }
        DLights("API", api, if (h.health == null) "not answering" else if (h.health.optBoolean("ok")) "health ok (:8003 answered)" else "health not ok", "c3_sum_api") { h.open(Decl.SECTION_API, Decl.PAGE_TOPOLOGY) }
        DLights("SSHD", ssh, "${doors.count { sshState(it) == SshState.GREEN }} of ${doors.size} doors up (bind state)", "c3_sum_sshd") { h.open(Decl.SECTION_SSHD, null) }
        DLights("ADB Shell", adb, when (adbUp) { true -> "server answering"; false -> "server not answering"; null -> "no probe (owner side or SuperApp missing)" } +
            (h.live.roundTrip?.let { if (it) " · echo round trip ok" else " · no echo" } ?: ""), "c3_sum_adb") { h.open(Decl.SECTION_ADB_SHELL, Decl.PAGE_ADB_STATUS) }
        DNote(HomeModel.LEGEND)
        DRow("Mode", if (UiMode.edit) "Edit (switches work)" else "View only (switches locked)")
        DActs {
            DAct((if (UiMode.edit) "● " else "○ ") + "Edit", "c3_mode_edit") { UiMode.edit = true }
            DAct((if (!UiMode.edit) "● " else "○ ") + "View only", "c3_mode_view") { UiMode.edit = false }
        }
        val anyOn = serverOn || adbUp == true
        DActMsg(if (anyOn) "Stop All" else "Start All", "c3_all_startstop", HomeModel.startStopMsg(pw, h.adb) + (if (started.isEmpty()) "" else " · $started")) {
            edit(ctx) {
                if (anyOn) h.askOff(true)
                else {
                    h.setPower("server", true)
                    if (h.live.owner) scope.launch { started = "ADB: " + withContext(Dispatchers.IO) { runCatching { AdbOwnerSide.startServer(ctx) }.getOrElse { "start failed (${it.javaClass.simpleName})" } }; h.bump() }
                }
            }
        }
        val allKa = pw?.optBoolean("keep_alive") == true && h.live.keepAlive != false
        DActMsg(if (allKa) "Keep Alive All: turn OFF" else "Keep Alive All: turn ON", "c3_master_keep_alive", HomeModel.keepAliveMsg(pw, h.live) + (if (kept.isEmpty()) "" else " · $kept")) {
            edit(ctx) {
                h.setPower("keep_alive", !allKa)
                if (h.live.owner) { runCatching { AdbOwnerSide.setKeepAlive(ctx, !allKa) }; kept = "ADB keep-alive ${if (!allKa) "ON" else "OFF"}" }
                h.bump()
            }
        }
        DActMsg("Probe All", "c3_all_probe", if (probed.isEmpty()) HomeModel.probeMsg(h.served, h.health, h.sshd, h.adb, h.live) else probed) {
            h.bump()
            probed = "probed ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}: " + HomeModel.probeMsg(h.served, h.health, h.sshd, h.adb, h.live)
        }
    }
}

/** Pages: the Server switch (start/stop everything it serves), keep alive, the probe, the roots' chips, the served list, the ports and URLs. */
@Composable
fun PagesTable(h: HomeData) {
    val pw = h.pw
    val served = h.served
    val rows = served.objs("rows")
    val rs = h.roots?.optJSONArray("roots").objs()
    val on = pw?.optBoolean("server") == true
    val running = pw?.optBoolean("running") == true
    val state = if (pw == null) "-" else if (on) (if (running) "Server ON, serving" else "Server ON, starting") else "Server OFF: pages, sshd, DNS and sites stopped; this admin page stays up"
    DenseCard("Pages · ${rows.size} served", "c3_home_pages", badges = { Badge(if (on) "ON" else "OFF", if (on) Tone.Up else Tone.Down) }, trailing = { DLink("Pages ›", "c3_home_goto_pages") { h.open(Decl.SECTION_PAGES, Decl.PAGE_ALL) } }) {
        if (pw != null) {
            SwitchRow("Server", on, "OFF stops the served pages, DNS, the mesh listener, sshd and every site; this admin page stays up so you can switch it back ON", "c3_power_server") { to -> if (!to) h.askOff(false) else h.setPower("server", true) }
            SwitchRow("Keep alive", pw.optBoolean("keep_alive"), "ON keeps the server up with a notification and starts it at boot; OFF stops it when the app closes", "c3_power_keep_alive") { h.setPower("keep_alive", it) }
        }
        DActs { DAct("Probe", "c3_pages_probe") { h.bump() } }
        DRow("Depends on", "Server ON (switch above) · the served roots' apps · storage access (Configs > Perms)", tag = "c3_pages_depends") { h.open(Decl.SECTION_CONFIGS, Decl.PAGE_PERMS) }
        DRow("State", state, up = on && running)
        DRow("Keep alive", if (pw == null) "-" else "${if (pw.optBoolean("keep_alive")) "ON" else "OFF"} · ${pw.str("sites")} active sites")
        DRow("Ports", pw?.optJSONArray("ports").objs().joinToString(", ") { "${it.str("proto")} ${it.str("port")}" }.ifEmpty { "none" })
        DRow("Active sites", pw.str("sites"))
        DRow("Page status", "${rs.count { it.optBoolean("page_on") }} of ${rs.size} roots: app on AND page served", up = rs.isNotEmpty() && rs.all { it.optBoolean("page_on") })
        DRow("App status", "${rs.count { it.optBoolean("app_on") }} of ${rs.size} apps answering", up = rs.isNotEmpty() && rs.all { it.optBoolean("app_on") })
        DRow("Serving status", "${rs.count { it.optBoolean("serving") }} of ${rs.count { it.has("serving") && !it.isNull("serving") }} ports accepting a connection")
        rs.forEach { r -> DRow(r.str("label"), (if (r.optBoolean("page_on")) "page served" else r.str("status")) + " · app " + (if (r.optBoolean("app_on")) "on" else "off") + " · " + (if (r.has("port") && !r.isNull("port")) ":" + r.str("port") else "no port"), up = r.optBoolean("page_on")) }
        DRow("Served list", "${rows.size} declared pages", tag = "c3_status_goto_pages") { h.open(Decl.SECTION_PAGES, Decl.PAGE_ALL) }
        val w = listOf<Int?>(null, 46, 74, 70)
        DCols(listOf("name", "port", "role", "state"), w, head = true)
        rows.forEach { r ->
            val up = r.optBoolean("serving")
            DCols(listOf(r.str("name") + if (r.has("app")) " · app ${r.str("app")}" else "", ":${r.str("port")}", r.str("role"), if (up) "serving" else if (r.optBoolean("paused")) "stopped" else "not serving"), w, up = up)
        }
        DRow("Undeclared ports", (served?.optJSONObject("undeclared")?.optJSONArray("items")?.length() ?: 0).toString())
        DNote("PAGES")
        rows.forEach { r -> DRow(r.str("name"), "http://127.0.0.1:${r.str("port")}/ · ${r.str("role")}", up = r.optBoolean("serving"), mono = true) }
        h.sv.objs("endpoints").forEach { e ->
            val up = if (e.has("up") && !e.isNull("up")) e.optBoolean("up") else null
            DRow(e.str("name"), "127.0.0.1:${e.str("port")} · ${e.str("role")}${when (up) { true -> " · answering now"; false -> " · not answering"; null -> "" }}", up = up == true, mono = true)
        }
    }
}

/** API: start/stop (the Server), keep alive, the health probe, version, uptime, the MCP catalogue, the mesh, DNS and the topology. */
@Composable
fun ApiTable(h: HomeData) {
    val ctx = LocalContext.current
    val pw = h.pw
    val health = h.health
    val ok = health?.optBoolean("ok") == true
    DenseCard("API", "c3_status_api", badges = { Badge(if (health == null) "NO ANSWER" else if (ok) "HEALTH OK" else "NOT OK", if (ok) Tone.Up else Tone.Down) }, trailing = { DLink("API ›", "c3_status_goto_api") { h.open(Decl.SECTION_API, Decl.PAGE_TOPOLOGY) } }) {
        DActs {
            DAct(if (pw?.optBoolean("server") == true) "Stop" else "Start", "c3_api_toggle") { edit(ctx) { if (pw?.optBoolean("server") == true) h.askOff(false) else h.setPower("server", true) } }
            DAct("Probe health", "c3_api_probe") { h.bump() }
            DAct((if (pw?.optBoolean("keep_alive") == true) "Keep alive: turn OFF" else "Keep alive: turn ON"), "c3_api_keep_alive") { edit(ctx) { h.setPower("keep_alive", pw?.optBoolean("keep_alive") != true) } }
        }
        DRow("Depends on", "Server ON (the API stops with it) · the loopback port :8003")
        DRow("Health", if (health != null) (if (ok) "ok" else "not ok") else "not answering (Server OFF stops :8003)", up = ok)
        DRow("Version", if (h.sv != null) "${h.sv.str("app")} ${h.sv.str("version_name")}" else "-")
        DRow("Uptime", if (health != null && health.has("uptime_s")) "${fmtUp(health.longOrNull("uptime_s"))} (pid ${health.str("pid")})" else "-")
        DRow("MCP tools", mcpTools(h.mcp).size.toString() + " listed (generated catalogue)")
        DNote("MCP")
        DRow("Endpoint", Api.base() + "/__app__/api/sub/api-mcp", mono = true)
        h.mcp.objs("groups").forEach { g -> DRow(g.str("title"), g.objs("rows").joinToString(", ") { it.str("head") }.ifEmpty { "-" }) }
        h.mesh.objs("groups").firstOrNull()?.objs("rows")?.forEach { DRow("Mesh", it.str("head"), mono = true) }
        val dns = h.sv?.optJSONObject("dns")
        if (dns != null) DRow("DNS", "udp ${dns.str("host")}:${dns.str("port")} · ${dns.str("answer")}", mono = true)
        DRow("Topology", "Open the API topology (MCP → API → IPC)", tag = "c3_home_goto_api") { h.open(Decl.SECTION_API, Decl.PAGE_TOPOLOGY) }
    }
}

/** SSHD: each door with its verdict and Start / Stop / Restart / Re-check, the SSH door switch and follow, keep alive, addresses. */
@Composable
fun SshdTable(h: HomeData) {
    val ctx = LocalContext.current
    val actions = rememberActions(h.bump)
    val d = h.sshd
    val pw = h.pw
    val ssh = pw?.optJSONObject("ssh_door")
    DenseCard("SSHD", "c3_home_sshd", badges = { Badge("keep alive ${if (d?.optBoolean("keep_alive") == true) "ON" else "OFF"}") }, trailing = { DLink("SSHd ›", "c3_home_goto_sshd") { h.open(Decl.SECTION_SSHD, null) } }) {
        if (pw != null) {
            SwitchRow("Keep alive", pw.optBoolean("keep_alive"), "ON keeps the server up with a notification and starts it at boot; the doors follow it until set below", "c3_sshd_keep_alive") { h.setPower("keep_alive", it) }
            SwitchRow("SSH door :${ssh.str("port")}", ssh?.optBoolean("wanted") == true, "ON asks Cloud Terminal Nix-on-Droid to start its sshd; follows Keep alive until set here. It is stopped again only if this app started it", "c3_power_ssh_door") { h.setPower("ssh_door", it) }
            val follows = ssh == null || !ssh.has("explicit") || ssh.isNull("explicit")
            DRow("SSH door", "tcp ${ssh.str("port")} ${ssh.str("state")} · ${if (follows) "follows Keep alive" else "own switch"} · via ${ssh.str("via")}")
            if (!follows) DActs { DAct("Follow Keep alive", "c3_power_ssh_follow") { h.setPower("ssh_door", null) } }
        }
        DRow("Depends on", "Server ON · Keep alive (the doors follow it) · Cloud Terminal Nix-on-Droid sshd for its door")
        d.objs("doors").forEach { x ->
            val id = x.optString("id")
            val own = x.optJSONObject("control")?.let { it.has("explicit") && !it.isNull("explicit") } == true
            DRow(x.str("title"), "${if (x.has("port") && !x.isNull("port")) "tcp ${x.str("port")}" else "no port"} · ${x.str("state")} · ${sshWord(x)} · mesh ${sshMesh(x)} · ${if (own) "own switch" else "follows keep alive"}", up = sshState(x) == SshState.GREEN)
            val c = x.optJSONObject("control") ?: JSONObject()
            fun op(o: String) = edit(ctx) { if (!c.optBoolean("available")) toast(ctx, c.optString("note")) else actions.post(Api.SSHD, JSONObject().put("door", id).put("op", o)) { "Asked" } }
            DActs {
                DAct("Start", "c3_home_door_start_$id") { op("start") }
                DAct("Stop", "c3_home_door_stop_$id") { op("stop") }
                DAct("Restart", "c3_home_door_restart_$id") { op("restart") }
                DAct("Re-check", "c3_home_door_recheck_$id") { h.bump() }
            }
            DRow("ssh command", sshCommand(x), mono = true, tag = "c3_home_door_ssh_$id")
        }
        DNote("SSHD")
        d.objs("doors").forEach { x -> DRow(x.str("title"), if (x.has("port") && !x.isNull("port")) "tcp 127.0.0.1:${x.str("port")} · ${x.str("state")}" else "no port", up = sshState(x) == SshState.GREEN, mono = true) }
        DNote("DEVICE")
        val network = h.about.objs().flatMap { it.objs("sections") }.filter { it.optString("title") == "Network" }.flatMap { it.optJSONArray("rows").pairs() }
        if (network.isEmpty()) DRow("IPs", "-")
        network.forEach { (k, v) -> DRow(k, v, mono = true) }
    }
}

/** ADB Shell: the owner's status table (lib reads) with its keep-alive switch, Start / Stop server, Probe, the loopback server and endpoint. */
@Composable
fun AdbTable(h: HomeData, tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    val adb = h.adb
    val port = adb?.takeIf { it.has("server_port") && !it.isNull("server_port") }?.str("server_port")
    val up = adb?.takeIf { it.has("server_up") && !it.isNull("server_up") }?.optBoolean("server_up")
    AdbOwnerSide.HomeStatus(tick, h.open) {
        if (h.live.owner) {
            SwitchRow("Keep alive", h.live.keepAlive == true, "ON lets the app bring the ADB server up by itself (app start, boot, a command that finds it down); OFF never starts it or asks for Wireless debugging pairing by itself. Tapping Start still may", "c3_adb_keep_alive") { to ->
                runCatching { AdbOwnerSide.setKeepAlive(ctx, to) }; h.bump()
            }
            DActs {
                DAct(if (up == true) "Stop server" else "Start server", "c3_adb_toggle") {
                    edit(ctx) { scope.launch { said = withContext(Dispatchers.IO) { runCatching { if (up == true) AdbOwnerSide.stopServer(ctx) else AdbOwnerSide.startServer(ctx) }.getOrElse { "failed (${it.javaClass.simpleName})" } }; h.bump() } }
                }
                DAct("Probe", "c3_adb_probe") { h.bump() }
            }
            if (said.isNotEmpty()) DNote(said)
        }
        DRow("Depends on", "Wireless debugging ON · paired once · the embedded adb session that launches the server (rows above, each opens its page)")
        DNote("ADB SHELL")
        DRow("Loopback server", if (port == null) "not declared" else "127.0.0.1:$port · " + if (up == true) "answering now" else "not answering", up = up == true, mono = true)
        DRow("Opens", adb?.let { "${it.str("package")} · ${it.str("extra")}=${it.str("value")}" } ?: "-", mono = true)
        DRow("Server", if (port == null) "-" else "127.0.0.1:$port", up = up == true, mono = true)
    }
}

package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.diegonmarcos.superapp.uikit.KitDensity
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1775 the STATUS page by category (Home tab). REORGANISE only (rule 16): every card the previous Home drew is still drawn, regrouped
 * under Summary, Server, Pages, API, SSHD, ADB Shell and Endpoints; nothing is removed. Every state shown here is a real probe the server
 * made (a TCP connect, the health route, the sshd bind state, the root listing, the ADB server connect), never a proxy.
 */
object UiMode {
    /** View only / Edit: View only refuses every switch on the page (read, never change). Edit is the default so nothing that worked stops working. */
    var edit by mutableStateOf(true)
}

/** A category band: the heading every group of Status starts with. */
@Composable
fun SectionBand(id: String, title: String, note: String = "") {
    androidx.compose.foundation.layout.Column(Modifier.testTag("c3_sec_$id").padding(top = 6.dp, start = 2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = KitDensity.eyebrowTracking)
        if (note.isNotEmpty()) DNote(note)
    }
}

/** The top: the full status summary, the Mode toggle and the Keep-alive master toggle. */
@Composable
fun StatusTop(pw: JSONObject, health: JSONObject?, served: JSONObject?, sshd: JSONObject?, adb: JSONObject?, bump: () -> Unit) {
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    val rows = served.objs("rows")
    val servingNow = rows.count { it.optBoolean("serving") }
    val doors = sshd.objs("doors")
    val doorsUp = doors.count { sshState(it) == SshState.GREEN }
    val serverOn = pw.optBoolean("server")
    val apiOk = health?.optBoolean("ok") == true
    val adbUp = adb?.takeIf { it.has("server_up") && !it.isNull("server_up") }?.optBoolean("server_up")
    DenseCard("Status summary", "c3_status_summary", badges = { Badge(if (!serverOn) "OFF" else if (!pw.optBoolean("running")) "STARTING" else if (apiOk && servingNow == rows.size && doorsUp == doors.size && adbUp != false) "ALL UP" else "ATTENTION", if (serverOn && pw.optBoolean("running") && apiOk && servingNow == rows.size && doorsUp == doors.size) Tone.Up else Tone.Down) }) {
        DRow("Server", if (serverOn) (if (pw.optBoolean("running")) "ON, running" else "ON, starting") else "OFF", up = serverOn && pw.optBoolean("running"))
        DRow("Pages", "$servingNow of ${rows.size} serving (connect probe per port)", up = rows.isNotEmpty() && servingNow == rows.size)
        DRow("API", if (health == null) "not answering" else if (apiOk) "health ok (:8003 answered)" else "health not ok", up = apiOk)
        DRow("SSHD", "$doorsUp of ${doors.size} doors up (bind state)", up = doors.isNotEmpty() && doorsUp == doors.size)
        DRow("ADB Shell", when (adbUp) { true -> "server answering"; false -> "server not answering"; null -> "no probe (owner side or SuperApp missing)" }, up = adbUp == true)
        DRow("Keep alive", if (pw.optBoolean("keep_alive")) "ON" else "OFF", up = pw.optBoolean("keep_alive"))
        DRow("Mode", if (UiMode.edit) "Edit (switches work)" else "View only (switches locked)")
        DActs {
            DAct((if (UiMode.edit) "● " else "○ ") + "Edit", "c3_mode_edit") { UiMode.edit = true }
            DAct((if (!UiMode.edit) "● " else "○ ") + "View only", "c3_mode_view") { UiMode.edit = false }
        }
        SwitchRow("Keep alive (master)", pw.optBoolean("keep_alive"), "The master switch for everything: ON keeps the server, pages, API and sshd up with a notification and starts them at boot; OFF lets them stop when the app closes. ON by default.", "c3_master_keep_alive") {
            actions.post(Api.POWER, JSONObject().put("keep_alive", it)) { "Applied" }
        }
    }
}

/** Pages category: every root's three real chips counted, then the served list. */
@Composable
fun PagesStatus(roots: JSONObject?, served: JSONObject?, open: (String, String?) -> Unit) {
    val rs = roots?.optJSONArray("roots").objs()
    DenseCard("Pages status", "c3_status_pages", trailing = { DLink("Pages ›", "c3_status_goto_pages") { open(Decl.SECTION_PAGES, Decl.PAGE_ALL) } }) {
        DRow("Page status", "${rs.count { it.optBoolean("page_on") }} of ${rs.size} roots: app on AND page served", up = rs.isNotEmpty() && rs.all { it.optBoolean("page_on") })
        DRow("App status", "${rs.count { it.optBoolean("app_on") }} of ${rs.size} apps answering", up = rs.isNotEmpty() && rs.all { it.optBoolean("app_on") })
        DRow("Serving status", "${rs.count { it.optBoolean("serving") }} of ${rs.count { it.has("serving") && !it.isNull("serving") }} ports accepting a connection")
        rs.forEach { r -> DRow(r.str("label"), (if (r.optBoolean("page_on")) "page served" else r.str("status")) + " · app " + (if (r.optBoolean("app_on")) "on" else "off") + " · " + (if (r.has("port") && !r.isNull("port")) ":" + r.str("port") else "no port"), up = r.optBoolean("page_on")) }
        DRow("Served list", "${served.objs("rows").size} declared pages")
    }
}

/** API category: the health probe, the MCP tools and the topology entry. */
@Composable
fun ApiStatus(health: JSONObject?, mcp: JSONObject?, open: (String, String?) -> Unit) {
    DenseCard("API status", "c3_status_api", trailing = { DLink("API ›", "c3_status_goto_api") { open(Decl.SECTION_API, Decl.PAGE_TOPOLOGY) } }) {
        DRow("Health", if (health == null) "not answering" else if (health.optBoolean("ok")) "ok" else "not ok", up = health?.optBoolean("ok") == true)
        DRow("Uptime", if (health != null && health.has("uptime_s")) fmtUp(health.longOrNull("uptime_s")) else "-")
        DRow("MCP tools", mcpTools(mcp).size.toString() + " listed (generated catalogue)")
    }
}

/** The MCP tools of the api-mcp sub page: every row head is one tool. */
fun mcpTools(mcp: JSONObject?): List<String> = mcp.objs("groups").flatMap { g -> g.objs("rows").map { it.str("head") } }

/** The ADB shell server probe (a real connect to its loopback port) next to the owner table. */
@Composable
fun AdbProbe(adb: JSONObject?) {
    DenseCard("ADB shell server", "c3_status_adb") {
        val port = adb?.takeIf { it.has("server_port") && !it.isNull("server_port") }?.str("server_port")
        val up = adb?.takeIf { it.has("server_up") && !it.isNull("server_up") }?.optBoolean("server_up")
        DRow("Loopback server", if (port == null) "not declared" else "127.0.0.1:$port · " + if (up == true) "answering now" else "not answering", up = up == true, mono = true)
        DRow("Opens", adb?.let { "${it.str("package")} · ${it.str("extra")}=${it.str("value")}" } ?: "-", mono = true)
    }
}

/** The Endpoints list: URLs / IPs and ports, by category. */
@Composable
fun EndpointsList(served: JSONObject?, sshd: JSONObject?, about: JSONArray?, mesh: JSONObject?, mcp: JSONObject?, adb: JSONObject?) {
    DenseCard("Endpoints", "c3_status_endpoints") {
        DNote("DEVICE")
        val network = about.objs().flatMap { it.objs("sections") }.filter { it.optString("title") == "Network" }.flatMap { it.optJSONArray("rows").pairs() }
        if (network.isEmpty()) DRow("IPs", "-")
        network.forEach { (k, v) -> DRow(k, v, mono = true) }
        mesh.objs("groups").firstOrNull()?.objs("rows")?.forEach { DRow("Mesh", it.str("head"), mono = true) }
        DNote("PAGES")
        served.objs("rows").forEach { r -> DRow(r.str("name"), "http://127.0.0.1:${r.str("port")}/ · ${r.str("role")}", up = r.optBoolean("serving"), mono = true) }
        DNote("SSHD")
        sshd.objs("doors").forEach { x -> DRow(x.str("title"), if (x.has("port") && !x.isNull("port")) "tcp 127.0.0.1:${x.str("port")} · ${x.str("state")}" else "no port", up = sshState(x) == SshState.GREEN, mono = true) }
        DNote("MCP")
        DRow("Endpoint", Api.base() + "/__app__/api/sub/api-mcp", mono = true)
        mcp.objs("groups").forEach { g -> DRow(g.str("title"), g.objs("rows").joinToString(", ") { it.str("head") }.ifEmpty { "-" }) }
        DNote("ADB SHELL")
        val port = adb?.takeIf { it.has("server_port") && !it.isNull("server_port") }?.str("server_port")
        DRow("Server", if (port == null) "-" else "127.0.0.1:$port", up = adb?.optBoolean("server_up") == true, mono = true)
    }
}

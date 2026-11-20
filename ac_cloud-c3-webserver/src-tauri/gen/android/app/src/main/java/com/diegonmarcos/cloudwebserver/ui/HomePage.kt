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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.diegonmarcos.cloudwebserver.owner.AdbOwnerSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Home (#1834): the Summary (three real lights per service, Start/Stop All, Keep Alive All, Probe All) and then one table per service,
 * Pages, API, SSHD, ADB Shell (StatusPage.kt), each with its whole control chain. The tables of the previous Home are folded into the
 * service they describe (rule 16: test-c3-webserver-home-dense.sh holds every field); the device report stays on Configs > Device.
 * Drawn as the dense console of #1723 (Dense.kt, KitDensity).
 */
@Composable
fun HomePage(open: (String, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (tick, bump) = rememberTick()
    val power = rememberObj(Api.POWER, tick)
    val health = rememberObj(Api.HEALTH, tick)
    val serving = rememberObj(Api.SERVING, tick)
    val served = rememberObj(Api.SERVED, tick)
    val sshd = rememberObj(Api.SSHD, tick)
    val about = rememberArr(Api.ABOUT, tick)
    val roots = rememberObj(Api.ROOTS, tick)
    val adb = rememberObj(Api.ADB_SHELL, tick)
    val mcp = rememberObj("/__app__/api/sub/api-mcp", tick)
    val mesh = rememberObj("/__app__/api/sub/api-mesh", tick)
    // the ADB owner's real reads (the keep-alive switch, an echo round trip), off the main thread, re-read on every refresh
    var live by remember { mutableStateOf(AdbLive(false, null, null)) }
    LaunchedEffect(tick) { live = withContext(Dispatchers.IO) { runCatching { AdbOwnerSide.readLive(ctx) }.getOrDefault(live) } }
    val actions = rememberActions(bump)
    var confirmOff by remember { mutableStateOf<Boolean?>(null) }   // non-null = the Server OFF question is open (true = Stop All, ADB goes down too)
    val data = HomeData(
        (power as? Load.Ok)?.value, (health as? Load.Ok)?.value, (serving as? Load.Ok)?.value, (served as? Load.Ok)?.value, (sshd as? Load.Ok)?.value,
        (about as? Load.Ok)?.value, (roots as? Load.Ok)?.value, (adb as? Load.Ok)?.value, (mcp as? Load.Ok)?.value, (mesh as? Load.Ok)?.value, live, bump, open,
        { all -> confirmOff = all },
        { key, value -> actions.post(Api.POWER, JSONObject().put(key, value ?: JSONObject.NULL)) { "Applied" } },
    )
    DensePage("c3_home") {
        DActs { DAct("Refresh", "c3_home_refresh") { bump() } }
        if (power is Load.Err) DErr("power: ${power.reason}")
        SectionBand("summary", "Summary")
        SummaryTable(data)
        SectionBand("pages", "Pages")
        PagesTable(data)
        SectionBand("api", "API")
        ApiTable(data)
        SectionBand("sshd", "SSHD")
        SshdTable(data)
        SectionBand("adb", "ADB Shell")
        AdbTable(data, tick)
    }
    if (confirmOff != null) AlertDialog(
        onDismissRequest = { confirmOff = null },
        title = { Text("Turn the Server OFF?") },
        text = { Text("Served pages, sshd, DNS and sites stop. This admin page stays up, so you can turn it back ON here.") },
        confirmButton = { TextButton({
            val all = confirmOff == true; confirmOff = null
            actions.post(Api.POWER, JSONObject().put("server", false)) { "Applied" }
            if (all) scope.launch { withContext(Dispatchers.IO) { runCatching { AdbOwnerSide.stopServer(ctx) } }; bump() }
        }) { Text("Turn OFF") } },
        dismissButton = { TextButton({ confirmOff = null }) { Text("Cancel") } },
    )
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
            val ctx = LocalContext.current
            DAct(if (on) "Turn OFF" else "Turn ON", tag) { if (UiMode.edit) onSet(!on) else toast(ctx, "View only: switch Mode to Edit to change this") }
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

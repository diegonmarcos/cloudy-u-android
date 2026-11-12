package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.Decl
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * #1677 the ADB Shell tab, native: the SuperApp's ADB Shell page (build.json::adb_shell), opened through the Rust core (POST
 * /__app__/api/adb-shell -> served::launch_with -> the SuperApp's launcher intent with its page extra). Selecting the tab opens it
 * once; a missing SuperApp is the server's clear 'not installed' reason, shown here. P2 (#1682) moves the ADB Shell into this app.
 */
@Composable
fun AdbShellPage(open: (String, String?) -> Unit) {
    val (tick, bump) = rememberTick()
    val link = rememberObj(Api.ADB_SHELL, tick)
    var said by rememberSaveable { mutableStateOf("") }
    var auto by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun openShell() {
        scope.launch {
            said = when (val r = Api.post(Api.ADB_SHELL, JSONObject())) {
                is Api.Reply.Ok -> "Opened Cloud SuperApp > ADB Shell."
                is Api.Reply.Err -> r.reason
            }
        }
    }
    LaunchedEffect(link) { if (auto && link is Load.Ok && !link.value.has("error")) { auto = false; said = "Opening Cloud SuperApp > ADB Shell..."; openShell() } }
    PageColumn("c3_adb_shell") {
        Card("ADB Shell", "c3_adb") {
            when (link) {
                is Load.Ok -> link.value.let { l ->
                    if (l.has("error")) ErrorNote(l.str("error"))
                    else Note("The ADB Shell lives in Cloud SuperApp (Configs › ADB Shell). This tab opens it there" + (l.optString("package").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") + ".")
                }
                else -> Loaded(link, "ADB Shell link") {}
            }
            Acts { Act("▶ Open ADB Shell in Cloud SuperApp", "c3_adb_open") { openShell() }; Act("Refresh", "c3_adb_refresh") { bump() } }
            if (said.isNotEmpty()) Note(said)
            Note("This app's own settings are under the ⚙ gear in the top bar (Configs).")
            Acts { LinkText("Open Configs ›", "c3_adb_goto_configs") { open(Decl.SECTION_CONFIGS, null) } }
        }
    }
}

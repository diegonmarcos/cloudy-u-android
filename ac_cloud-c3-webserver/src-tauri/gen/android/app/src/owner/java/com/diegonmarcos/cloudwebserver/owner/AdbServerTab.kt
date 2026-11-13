package com.diegonmarcos.cloudwebserver.owner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.ui.Act
import com.diegonmarcos.cloudwebserver.ui.Acts
import com.diegonmarcos.cloudwebserver.ui.Card
import com.diegonmarcos.cloudwebserver.ui.Note
import com.diegonmarcos.cloudwebserver.ui.PageColumn
import com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap
import com.diegonmarcos.superapp.adbdebug.AutoStartCore
import com.diegonmarcos.superapp.adbdebug.ChannelAction
import com.diegonmarcos.superapp.adbdebug.ChannelActions
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelModeBar
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.ConnectPlan
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.adbdebug.LocalServerAutoStart
import com.diegonmarcos.superapp.adbdebug.StepState
import com.diegonmarcos.superapp.adbdebug.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #1713 the Server sub page of the ADB Shell: ONLY the local ADB shell server's management. State (a real echo round trip read by
 * libs:shizuku-adb-debug-tools ChannelReader, never a proxy), pid / port / uid / uptime, which launcher started it, the last start
 * result and the server log, Start / Stop / Restart, the auto-start chain (the lib's LocalServerAutoStart, #1709: this page reads its
 * steps, it keeps none) and the profile selector (Local server default). Everything else the old Server tab held lives on Status
 * (Connect, Re-check, Tester, Logs) and All (the lib's full ADB Shell page): see AdbOwnerSide.FIELD_HOME.
 */
@Composable
internal fun AdbServerTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<ChannelFacts?>(null) }
    var readFailed by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var logTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ChannelModePrefs.current(ctx)) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { logTick++ }
        ChannelLog.shared.addListener(l); LocalServerAutoStart.addListener(l)
        onDispose { ChannelLog.shared.removeListener(l); LocalServerAutoStart.removeListener(l) }
    }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) } }
        facts = r.getOrNull(); readFailed = r.exceptionOrNull()?.let { "could not read the server (${it.javaClass.simpleName})" } ?: ""
    }
    fun run(label: String, work: () -> String) {
        if (busy.isNotEmpty()) return
        busy = label; said = ""
        scope.launch(Dispatchers.IO) {
            said = runCatching(work).getOrElse { "$label failed: ${it.message ?: it.javaClass.simpleName}" }
            busy = ""; tick++
        }
    }
    val f = facts?.copy(mode = mode)
    val enab = f?.let { runCatching { ChannelActions.enablement(it) }.getOrNull() }
    val stamp = f?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it.probedAt)) }
    PageColumn("c3_adb_server") {
        Card("Server state", "c3_adb_server_state") {
            if (readFailed.isNotEmpty()) Note(readFailed)
            val st = when {
                f == null -> "reading..."
                f.serverRunning -> "RUNNING (answered an echo round trip)"
                else -> "NOT RUNNING (no answer on 127.0.0.1:${f.serverPort})"
            }
            Note("State: $st")
            Note("Port: " + (f?.serverPort?.takeIf { it > 0 }?.let { "127.0.0.1:$it (loopback only, token)" } ?: "unknown"))
            Note("Process: pid " + (f?.serverPid ?: "-") + "  ·  " + (f?.serverUid ?: "uid -") + "  ·  uptime " + (f?.serverUptime ?: "-"))
            Note("Started by: " + (AdbShellBootstrap.lastLauncher(ctx) ?: "nobody yet this boot") + " (automatic: embedded adb first, Shizuku only as the fallback)")
            Note("Last start result: " + AdbShellBootstrap.bootstrapState(ctx))
            LocalServerAutoStart.lastOutcome?.let { Note("Last automatic run: " + AutoStartCore.describe(it)) }
            if (stamp != null) Note("Read at $stamp")
            Acts {
                val running = f?.serverRunning == true
                val startOk = enab?.get(ChannelAction.RESTART_SERVER)?.enabled == true || f != null
                Act("Start", "c3_adb_server_start", enabled = busy.isEmpty() && !running && startOk) { run("Start server") { ChannelOps.startServer(ctx, restart = false) } }
                Act("Stop", "c3_adb_server_stop", enabled = busy.isEmpty() && enab?.get(ChannelAction.STOP_SERVER)?.enabled == true) { run("Stop server") { ChannelOps.stopServer(ctx) } }
                Act("Restart", "c3_adb_server_restart", enabled = busy.isEmpty() && startOk) { run("Restart server") { ChannelOps.startServer(ctx, restart = true) } }
                Act("Re-check", "c3_adb_server_recheck", enabled = busy.isEmpty()) { tick++ }
            }
            if (busy.isNotEmpty()) Note("$busy...")
            if (said.isNotEmpty()) Note(said)
            val why = enab?.filter { (a, e) -> !e.enabled && (a == ChannelAction.STOP_SERVER || a == ChannelAction.RESTART_SERVER) }?.entries?.joinToString("  ·  ") { "${it.key.label}: ${it.value.why}" }
            if (!why.isNullOrBlank()) Note(why)
        }
        Card("Auto-start chain", "c3_adb_server_chain") {
            val owns = ChannelModePrefs.ownsServer()
            val plan = remember(mode) { runCatching { ConnectPlan.steps(mode, LaunchVia.ADB, owns) }.getOrDefault(emptyList()) }
            Note("Profile ${mode.title()}: " + if (plan.isEmpty()) "no steps" else plan.joinToString(" > ") { it.label })
            val last = remember(logTick, tick) { LocalServerAutoStart.lastSteps }
            if (last.isEmpty()) Note("No automatic run yet this process. It runs by itself after a reboot, at app start and when a command finds the channel down.")
            for (s in last) Column(Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
                val g = when (s.state) { StepState.DONE -> "✓"; StepState.FAILED -> "✗"; StepState.RUNNING -> "…"; StepState.WAITING -> "!"; StepState.SKIPPED -> "-"; StepState.PENDING -> "·" }
                Note("$g ${s.id.label}" + if (s.detail.isNotBlank()) " - ${s.detail.take(80)}" else "")
            }
        }
        Card("Profile", "c3_adb_server_profile") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ChannelModeBar { m -> mode = m; tick++ } }
            Note("Local server is the default; Shizuku and Embedded adb are the other profiles (Auto walks the ladder).")
        }
        Card("Server log", "c3_adb_server_log") {
            val lines = remember(logTick) { ChannelLog.shared.entries().filter { it.tag in SERVER_TAGS }.takeLast(40) }
            val t = SimpleDateFormat("HH:mm:ss", Locale.US)
            Text(
                if (lines.isEmpty()) "no server log lines yet" else lines.joinToString("\n") { "${t.format(Date(it.at))} [${it.tag}] ${it.text}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private val SERVER_TAGS = setOf("server", "action", "mode", "auto", "autostart", "test")

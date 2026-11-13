package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.ui.DNote
import com.diegonmarcos.cloudwebserver.ui.DRow
import com.diegonmarcos.cloudwebserver.ui.DenseCard
import com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap
import com.diegonmarcos.superapp.adbdebug.AutoStartCore
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.LocalServerAutoStart
import com.diegonmarcos.superapp.adbdebug.StepRun
import com.diegonmarcos.superapp.adbdebug.StepState
import com.diegonmarcos.superapp.adbdebug.title
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line of the Home ADB shell table: label, value, state dot (null = no dot) and the ADB Shell sub page a tap opens. */
data class AdbHomeRow(val id: String, val label: String, val value: String, val up: Boolean?, val page: String)

/**
 * #1723 the rows of Home's ADB shell status table, pure over the lib's own reads (nothing duplicated): [ChannelFacts] from
 * ChannelReader (the real echo round trip, #1713 AdbServerTab reads the same), the launcher / last start result from AdbShellBootstrap,
 * the last automatic run from LocalServerAutoStart.lastSteps / lastOutcome. [facts] null = not read yet or the read failed ([failed] says why).
 */
object AdbHomeModel {
    fun stepsText(steps: List<StepRun>): String =
        if (steps.isEmpty()) "none yet this process"
        else steps.joinToString(" > ") { s ->
            val g = when (s.state) { StepState.DONE -> "ok"; StepState.FAILED -> "FAIL"; StepState.RUNNING -> "..."; StepState.WAITING -> "wait"; StepState.SKIPPED -> "skip"; StepState.PENDING -> "-" }
            "${s.id.label} $g"
        }

    fun rows(
        facts: ChannelFacts?, failed: String, profile: String, launcher: String?, startResult: String, lastRun: String, steps: List<StepRun>,
        clients: List<String>?,
    ): List<AdbHomeRow> {
        val f = facts
        val running = f?.serverRunning == true
        val state = when {
            f == null -> failed.ifEmpty { "reading..." }
            running -> "RUNNING (echo round trip answered)"
            else -> "NOT RUNNING (no answer on 127.0.0.1:${f.serverPort})"
        }
        fun yn(b: Boolean?, yes: String, no: String) = when (b) { true -> yes; false -> no; null -> "unknown" }
        return listOf(
            AdbHomeRow("profile", "Profile", profile, null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("state", "Server", state, if (f == null) null else running, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("port", "Port", f?.serverPort?.takeIf { it > 0 }?.let { "127.0.0.1:$it (loopback, token)" } ?: "unknown", null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("pid", "PID / uid", (f?.serverPid ?: "-") + "  ·  " + (f?.serverUid ?: "uid -"), null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("uptime", "Uptime", f?.serverUptime ?: "-", null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("launcher", "Started by", launcher ?: "nobody yet this boot", null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("last_start", "Last start", startResult, null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("auto_chain", "Auto-start", "$lastRun · ${stepsText(steps)}", null, Decl.PAGE_ADB_SERVER),
            AdbHomeRow("wireless", "Wireless dbg", yn(f?.wirelessDebug, "ON", "OFF"), f?.wirelessDebug, Decl.PAGE_ADB_STATUS),
            AdbHomeRow("paired", "Paired", if (f == null) "unknown" else if (f.adbPaired) "yes" + (f.adbEndpoint?.let { " ($it)" } ?: "") else "no", f?.adbPaired, Decl.PAGE_ADB_STATUS),
            AdbHomeRow("clients", "Clients", clients?.let { if (it.isEmpty()) "none allowed yet" else "${it.size} allowed: " + it.joinToString(", ") } ?: "unknown", null, Decl.PAGE_ADB_PERMS),
        )
    }
}

/** Home's ADB shell status table: re-read when Home refreshes ([tick]) or when the channel log / auto-start run changes; never throws. */
@Composable
fun AdbHomeStatus(tick: Int, open: (String, String?) -> Unit) {
    val ctx: Context = LocalContext.current
    var facts by remember { mutableStateOf<ChannelFacts?>(null) }
    var failed by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<String>?>(null) }
    var logTick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { logTick++ }
        ChannelLog.shared.addListener(l); LocalServerAutoStart.addListener(l)
        onDispose { ChannelLog.shared.removeListener(l); LocalServerAutoStart.removeListener(l) }
    }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) } }
        facts = r.getOrNull(); failed = r.exceptionOrNull()?.let { "could not read the server (${it.javaClass.simpleName})" } ?: ""
        clients = withContext(Dispatchers.IO) { runCatching { ShellBroker.callerSummaries(ctx).map { it.first } }.getOrNull() }
    }
    val mode = remember(tick) { runCatching { ChannelModePrefs.current(ctx).title() }.getOrDefault("unknown") }
    val steps = remember(logTick, tick) { runCatching { LocalServerAutoStart.lastSteps }.getOrDefault(emptyList()) }
    val lastRun = remember(logTick, tick) { runCatching { LocalServerAutoStart.lastOutcome?.let { AutoStartCore.describe(it) } }.getOrNull() ?: "no automatic run yet" }
    val launcher = runCatching { AdbShellBootstrap.lastLauncher(ctx) }.getOrNull()
    val start = runCatching { AdbShellBootstrap.bootstrapState(ctx) }.getOrDefault("unknown")
    val rows = AdbHomeModel.rows(facts?.copy(mode = ChannelModePrefs.current(ctx)), failed, mode, launcher, start, lastRun, steps, clients)
    DenseCard("ADB shell", "c3_home_adb") {
        rows.forEach { r -> DRow(r.label, r.value, up = r.up, tag = "c3_home_adb_${r.id}") { open(Decl.SECTION_ADB_SHELL, r.page) } }
        DNote("Tap a row to open its ADB Shell page (Server / Status / Perms).")
    }
}

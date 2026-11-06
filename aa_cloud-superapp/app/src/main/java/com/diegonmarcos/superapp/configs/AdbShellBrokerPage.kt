@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.configs

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.AdbShellPage
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import com.diegonmarcos.superapp.adbdebug.WdAuditLog
import com.diegonmarcos.superapp.adbdebug.WdKeeper
import com.diegonmarcos.superapp.adbdebug.WdKeeperStatus
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.system.WirelessDebugKeeper
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the page shows, read off the device and the broker (see [loadAdbPage]). */
internal data class AdbPageState(
    val status: ShellStatus? = null,
    val wireless: Boolean? = null,
    val paired: Boolean = false,
    val port: String? = null,
    val keeperOn: Boolean = false,
    val keeper: WdKeeperStatus.Snapshot? = null,
    val callers: List<AdbShellModel.CallerRow> = emptyList(),
    val audit: List<AuditLog.Entry> = emptyList(),
    val keeperLog: List<WdAuditLog.Entry> = emptyList(),
    val note: String = "",
)

/** The page's verbs; the stateful wrapper wires them, a test passes no-ops. */
internal class AdbPageActions(
    val primary: (AdbShellModel.Primary) -> Unit = {}, val rearm: () -> Unit = {}, val pair: () -> Unit = {},
    val openSettings: () -> Unit = {}, val testNow: () -> Unit = {}, val toggleKeeper: () -> Unit = {}, val fixPerms: () -> Unit = {},
)

private fun stamp(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

/** Read everything the page shows. Blocking: call off the main thread. The status is the broker's real `id -u` round trip. */
internal fun loadAdbPage(ctx: Context): AdbPageState {
    val facts = runCatching { ChannelReader.read(ctx) }.getOrNull()
    val keeper = runCatching { WdKeeper.snapshot(ctx) }.getOrNull()
    return AdbPageState(
        status = runCatching { ShellBroker.status(ctx) }.getOrNull(),
        wireless = runCatching { WirelessDebugging.isOn(ctx) }.getOrNull(),
        paired = facts?.adbPaired == true, port = AdbShellModel.port(facts?.adbEndpoint),
        keeperOn = runCatching { WirelessDebugKeeper.Prefs.enabled(ctx) }.getOrDefault(false), keeper = keeper,
        callers = runCatching { AdbShellModel.callers(ShellBroker.callerSummaries(ctx), ShellBroker.auditEntries(ctx)) }.getOrDefault(emptyList()),
        audit = runCatching { ShellBroker.auditEntries(ctx) }.getOrDefault(emptyList()),
        keeperLog = runCatching { WdKeeper.audit(ctx).entries() }.getOrDefault(emptyList()),
    )
}

/** Configs > ADB Shell in the SuperApp: loads on open and on resume (no polling), then draws [AdbShellContent]. */
@Composable
internal fun AdbShellBrokerPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(AdbPageState()) }
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        // #1550 re-check when Wireless debugging / adb is switched, and on resume; no polling.
        val stop = com.diegonmarcos.superapp.shellclient.AdbWatch.observe(ctx) { runCatching { ShellBroker.invalidate(ctx) }; tick++ }
        onDispose { owner.lifecycle.removeObserver(o); stop() }
    }
    LaunchedEffect(tick) { val s = withContext(Dispatchers.IO) { loadAdbPage(ctx) }; state = s.copy(note = state.note) }
    fun run(msg: String, work: () -> String) {
        state = state.copy(note = "$msg...")
        scope.launch(Dispatchers.IO) { val r = runCatching(work).getOrElse { it.message ?: "failed" }; state = state.copy(note = r); tick++ }
    }
    val rearm = { run("Re-arming") { ChannelOps.reconnect(ctx) } }
    val pair = { run("Pairing") { ChannelOps.pair(ctx) } }
    val actions = AdbPageActions(
        primary = { if (it == AdbShellModel.Primary.PAIR) pair() else rearm() }, rearm = rearm, pair = pair,
        openSettings = { AdbShellLink.open(ctx, ControlStatus.Action.OPEN_SETTINGS) },
        testNow = { run("Testing") { ShellBroker.invalidate(ctx); ShellBroker.status(ctx).let { it.adbLine() + "\n" + it.line() } } },
        toggleKeeper = { WirelessDebugKeeper.Prefs.setEnabled(ctx, !state.keeperOn); tick++ },
        fixPerms = { WdKeeper.openPermsFix(ctx) },
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { AdbShellContent(state, actions, console = { AdbShellPage() }) }
}

@Composable
private fun dim(): Color = LocalContentColor.current.copy(alpha = 0.65f)

/** A section: a tappable header (collapsible ones show a chevron) and, when open, its body. */
@Composable
private fun SectionBlock(s: AdbShellModel.Section, open: Boolean, onToggle: () -> Unit, body: @Composable () -> Unit) {
    KitStoreRowLine(Modifier.testTag("adb:section:${s.id}"), onClick = if (s.collapsed) onToggle else null) {
        KitStoreName(s.title, Modifier.weight(1f), color = LocalContentColor.current)
        if (s.collapsed) KitStoreCaption(if (open) "▾" else "▸", color = dim())
    }
    if (open) Column(Modifier.testTag("adb:body:${s.id}")) {
        if (s.help.isNotBlank()) KitStoreRowLine { KitStoreCaption(s.help, color = dim()) }
        body()
    }
}

/** One label / value row with a state glyph. */
@Composable
private fun Fact(label: String, value: String, glyph: String = "·", ink: Color = Color.Unspecified, action: (@Composable () -> Unit)? = null) {
    KitStoreRowLine {
        KitStoreDot(glyph, ink)
        KitStoreCaption(label, Modifier.weight(0.45f), color = dim())
        Text(value, Modifier.weight(0.55f), fontSize = KitStoreRow.body)
        action?.invoke()
    }
}

private fun ink(ok: Boolean?): Color = when (ok) { true -> KitStoreRow.UP; false -> KitStoreRow.BAD; null -> KitStoreRow.UPDATE }
private fun tick(ok: Boolean?): String = when (ok) { true -> "✓"; false -> "✕"; null -> "?" }

/** The page, stateless: the sections of [AdbShellModel.ORDER], each drawn from [s]. */
@Composable
internal fun AdbShellContent(
    s: AdbPageState, a: AdbPageActions, openIds: Set<String> = AdbShellModel.initiallyOpen(),
    console: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(openIds) }
    fun toggle(id: String) { open = if (id in open) open - id else open + id }
    Column(Modifier.fillMaxWidth().testTag("adb:page")) {
        for (sec in AdbShellModel.ORDER) SectionBlock(sec, sec.id in open, { toggle(sec.id) }) {
            when (sec) {
                AdbShellModel.Section.STATUS -> {
                    val v = AdbShellModel.verdict(s.status)
                    KitStoreRowLine(Modifier.testTag("adb:status")) {
                        KitStoreDot("●", ink(v))
                        Column(Modifier.weight(1f)) {
                            Text(AdbShellModel.title(s.status), fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold)
                            Text(AdbShellModel.detail(s.status), fontSize = KitStoreRow.body)
                        }
                        val p = AdbShellModel.primary(s.paired)
                        KitStoreQuick(p.label, if (v == true) KitStoreRow.BLUE_FILL else KitStoreRow.AMBER_FILL, Modifier.testTag("adb:primary"), onClick = { a.primary(p) })
                    }
                    val b = AdbShellModel.brokerVerdict(s.status)
                    Fact("Shell broker", s.status?.line() ?: "checking", tick(b), ink(b))
                    Fact("Wireless debugging", when (s.wireless) { true -> "on"; false -> "off"; null -> "unknown" }, tick(s.wireless), ink(s.wireless))
                    Fact("Paired", if (s.paired) "yes" else "no", tick(s.paired), ink(s.paired))
                    Fact("Port", s.port ?: "-")
                }
                AdbShellModel.Section.ACTIONS -> {
                    FlowRow(Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), Arrangement.spacedBy(KitStoreRow.gap), Arrangement.spacedBy(KitStoreRow.gap)) {
                        KitStoreQuick("Re-arm", KitStoreRow.BLUE_FILL, onClick = a.rearm)
                        KitStoreQuick("Pair", KitStoreRow.BLUE_FILL, onClick = a.pair)
                        KitStoreQuick("Wireless debugging settings", KitStoreRow.BLUE_FILL, onClick = a.openSettings)
                        KitStoreQuick("Test now", KitStoreRow.AMBER_FILL, onClick = a.testNow)
                    }
                    if (s.note.isNotBlank()) KitStoreRowLine { SelectionContainer { Text(s.note, fontSize = KitStoreRow.body) } }
                }
                AdbShellModel.Section.KEEPER -> {
                    Fact("Keeper", if (s.keeperOn) "on" else "off", tick(s.keeperOn), ink(s.keeperOn)) {
                        KitStoreQuick(if (s.keeperOn) "Turn off" else "Turn on", KitStoreRow.BLUE_FILL, onClick = a.toggleKeeper)
                    }
                    val can = s.keeper?.canWrite
                    Fact("WRITE_SECURE_SETTINGS", when (can) { true -> "granted"; false -> "not granted"; null -> "unknown" }, tick(can), ink(can)) {
                        if (can == false) KitStoreQuick("Fix in Perms ›", KitStoreRow.AMBER_FILL, onClick = a.fixPerms)
                    }
                    Fact("Last re-arm", AdbShellModel.rearmLine(s.keeper) { WdKeeper.ago(it) })
                }
                AdbShellModel.Section.CALLERS -> {
                    if (s.callers.isEmpty()) KitStoreRowLine { KitStoreCaption("No callers declared.", color = dim()) }
                    for (c in s.callers) KitStoreRowLine(Modifier.testTag("adb:caller")) {
                        KitStoreName(AdbShellModel.shortCaller(c.key), Modifier.weight(0.4f), color = LocalContentColor.current)
                        KitStoreCaption(c.summary, Modifier.weight(0.35f), color = dim())
                        KitStoreCaption(c.lastCallAt?.let { stamp(it) } ?: "never", Modifier.weight(0.25f), mono = true)
                    }
                }
                AdbShellModel.Section.AUDIT -> {
                    if (s.audit.isEmpty()) KitStoreRowLine { KitStoreCaption("Nothing recorded yet.", color = dim()) }
                    SelectionContainer {
                        Column {
                            for (e in AdbShellModel.auditRows(s.audit)) KitStoreRowLine(Modifier.testTag("adb:audit")) {
                                KitStoreCaption(stamp(e.ts), Modifier.weight(0.17f), mono = true)
                                KitStoreCaption(AdbShellModel.shortCaller(e.caller), Modifier.weight(0.23f))
                                KitStoreCaption(e.cmd, Modifier.weight(0.4f), mono = true)
                                KitStoreCaption("${e.outcome} ${e.exit}", Modifier.weight(0.2f), color = if (e.exit == 0) KitStoreRow.UP else KitStoreRow.BAD)
                            }
                        }
                    }
                }
                // #1550 the full channel console restored: status layers, connect, active connection, logs, declared needs, setup, keeper.
                AdbShellModel.Section.CONSOLE -> Column(Modifier.testTag("adb:console")) { console() }
                AdbShellModel.Section.KEEPER_LOG -> {
                    if (s.keeperLog.isEmpty()) KitStoreRowLine { KitStoreCaption("Nothing recorded yet.", color = dim()) }
                    for (e in AdbShellModel.keeperRows(s.keeperLog)) KitStoreRowLine(Modifier.testTag("adb:keeper_log")) {
                        KitStoreCaption(stamp(e.at), Modifier.weight(0.17f), mono = true)
                        KitStoreCaption(if (e.on) "ON" else "OFF", Modifier.weight(0.1f), mono = true)
                        KitStoreCaption(e.cause, Modifier.weight(0.43f))
                        KitStoreCaption(if (e.by == "-") "nobody" else e.by, Modifier.weight(0.3f))
                    }
                }
            }
        }
    }
}

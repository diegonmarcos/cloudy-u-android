@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.configs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.LaunchViaPrefs
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.AdbWatch
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The cockpit's verbs; the stateful wrapper wires them, a test passes no-ops. */
internal class CockpitActions(
    val act: (AdbCockpitModel.Do) -> Unit = {}, val autoFix: () -> Unit = {}, val pickMode: (ChannelMode) -> Unit = {},
    val copyLog: () -> Unit = {}, val clearLog: () -> Unit = {}, val setKeepAlive: (Boolean) -> Unit = {},
)

@Composable
private fun lightInk(l: AdbCockpitModel.Light): Color {
    val p = LocalKitPalette.current
    return when (l) {
        AdbCockpitModel.Light.GREEN -> p.ok
        AdbCockpitModel.Light.AMBER -> p.warn
        AdbCockpitModel.Light.RED -> p.bad
        AdbCockpitModel.Light.GREY -> p.textSecondary.copy(alpha = 0.55f)
    }
}

@Composable
private fun Dot(l: AdbCockpitModel.Light, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(KitStoreRow.step(size)).background(lightInk(l), CircleShape).semantics { contentDescription = l.name.lowercase() })
}

/** A small tappable verb. [primary] = the next thing to do; a disabled one is dimmed and says why in its description. */
@Composable
private fun Chip(label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false, why: String? = null) {
    val p = LocalKitPalette.current
    Text(label, fontSize = KitStoreRow.meta, color = if (primary) p.accent else p.textPrimary, maxLines = 1,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f)
            .background(if (primary) p.accent.copy(alpha = 0.16f) else p.textPrimary.copy(alpha = 0.08f))
            .border(KitStoreRow.step(1), if (primary) p.accent.copy(alpha = 0.6f) else p.hairline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag(tag)
            .semantics { if (why != null) contentDescription = "$label: $why" })
}

private fun hms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

/**
 * The Status cockpit, stateless. [inputs] null = still reading the device. Top control bar (Connect / Disconnect, Reconnect, Mode),
 * the master health pill, Auto-fix, the dependency-ordered step chain and the log tail. Drawn from [AdbCockpitModel] only.
 */
@Composable
internal fun AdbCockpitContent(
    inputs: AdbCockpitModel.Inputs?, busy: String, note: String, log: List<ChannelLog.Entry>, a: CockpitActions,
    modeMenuOpen: Boolean = false, filterOpen: Boolean = false, query: String = "", keepAlive: AdbCockpitModel.KeepAlive? = null,
) {
    val p = LocalKitPalette.current
    var menu by remember { mutableStateOf(modeMenuOpen) }
    var filtering by remember { mutableStateOf(filterOpen) }
    var q by remember { mutableStateOf(query) }
    val m = AdbCockpitModel.master(inputs)
    val steps = inputs?.let { AdbCockpitModel.steps(it) }.orEmpty()
    val mode = inputs?.facts?.mode
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.gap)
        .testTag("adbc:root"), verticalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
        // ── header: the master light ──
        Row(Modifier.fillMaxWidth().border(KitStoreRow.step(1), p.hairline).padding(KitStoreRow.gap).testTag("adbc:header"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
            Dot(m.light)
            Column(Modifier.weight(1f)) {
                Text("Connection", fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, color = p.textPrimary)
                KitStoreCaption(m.detail, color = p.textSecondary)
            }
            Row(Modifier.border(KitStoreRow.step(1), lightInk(m.light)).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag("adbc:master"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                Dot(m.light, size = 8)
                Text(m.label, fontSize = KitStoreRow.meta, color = p.textPrimary)
            }
        }
        // ── top control bar: Connect / Disconnect, Reconnect, Mode ──
        Row(Modifier.fillMaxWidth().testTag("adbc:bar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            val t = inputs?.let { AdbCockpitModel.toggle(it.facts) }
            Chip(t?.what?.label ?: "Connect", "adbc:toggle", { t?.let { a.act(it.what) } }, enabled = t != null && t.enabled, primary = t?.primary == true, why = t?.why)
            Chip("↻ Reconnect", "adbc:reconnect", { a.act(AdbCockpitModel.Do.RECONNECT) }, enabled = inputs != null)
            Row(Modifier.weight(1f).border(KitStoreRow.step(1), p.hairline).clickable(role = Role.Button) { menu = !menu }
                .padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag("adbc:mode"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(mode?.let { AdbCockpitModel.modeName(it) } ?: "Mode", fontSize = KitStoreRow.meta, fontWeight = FontWeight.Bold, color = p.accent, modifier = Modifier.weight(1f))
                KitStoreCaption(if (menu) "▴ Mode" else "▾ Mode", color = p.textSecondary)
            }
        }
        if (menu) FlowRow(Modifier.fillMaxWidth().testTag("adbc:mode-menu"), Arrangement.spacedBy(KitStoreRow.tight), Arrangement.spacedBy(KitStoreRow.tight)) {
            for (md in AdbCockpitModel.SELECTABLE) Chip(AdbCockpitModel.modeName(md), "adbc:mode:${md.id}", { menu = false; a.pickMode(md) }, primary = md == mode)
        }
        // ── keep-alive: the "Keep Wireless debugging on" switch + the keeper's real state ──
        keepAlive?.let { ka -> KeepAliveRow(ka, a.setKeepAlive) }
        // ── Auto-fix ──
        Row(Modifier.fillMaxWidth().background(p.accent.copy(alpha = 0.10f)).border(KitStoreRow.step(1), p.accent)
            .clickable(enabled = busy.isEmpty() && inputs != null, role = Role.Button, onClick = a.autoFix).padding(KitStoreRow.gap).testTag("adbc:autofix"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("✦ Auto-fix", fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, color = p.accent)
            Text(if (busy.isNotEmpty()) "   $busy" else "   walk the chain", fontSize = KitStoreRow.caption, color = p.textSecondary, modifier = Modifier.weight(1f))
            KitStoreCaption("grants · flips · pairs · connects", color = p.textSecondary)
        }
        if (note.isNotBlank()) SelectionContainer { Text(note, fontSize = KitStoreRow.caption, color = p.textPrimary, modifier = Modifier.testTag("adbc:note")) }
        // ── the chain ──
        KitStoreCaption("SETUP CHAIN · TOP-DOWN", color = p.textSecondary)
        Column(Modifier.fillMaxWidth().border(KitStoreRow.step(1), p.hairline).testTag("adbc:chain")) {
            if (steps.isEmpty()) Text("Reading the device...", fontSize = KitStoreRow.body, color = p.textSecondary, modifier = Modifier.padding(KitStoreRow.gap))
            for (s in steps) StepRow(s, a)
        }
        // ── logs ──
        Column(Modifier.fillMaxWidth().border(KitStoreRow.step(1), p.hairline).testTag("adbc:logs")) {
            Row(Modifier.fillMaxWidth().padding(KitStoreRow.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                Text("Logs", fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, color = p.textPrimary, modifier = Modifier.weight(1f))
                Chip(if (filtering) "filter ✓" else "filter", "adbc:log-filter", { filtering = !filtering; if (!filtering) q = "" })
                Chip("copy", "adbc:log-copy", a.copyLog)
                Chip("clear", "adbc:log-clear", a.clearLog)
            }
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = KitStoreRow.caption, color = p.textPrimary)
            if (filtering) BasicTextField(q, { q = it }, singleLine = true, textStyle = mono, cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.gap).background(p.textPrimary.copy(alpha = 0.07f)).padding(KitStoreRow.tight).testTag("adbc:log-query"),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Filter by tag or text…", style = mono.copy(color = p.textSecondary)); inner() } })
            val shown = remember(log, q) { AdbCockpitModel.filter(log, q) }
            val scroll = rememberScrollState()
            LaunchedEffect(shown.size) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer {
                Column(Modifier.fillMaxWidth().height(KitStoreRow.step(150)).verticalScroll(scroll).padding(KitStoreRow.gap).testTag("adbc:log-body")) {
                    if (shown.isEmpty()) Text(if (log.isEmpty()) "No adb activity yet." else "No lines match.", style = mono.copy(color = p.textSecondary))
                    for (e in shown) {
                        val sev = AdbCockpitModel.severity(e)
                        Text(hms(e.at) + "  [" + e.tag + "] " + e.text, style = mono.copy(color = when (sev) {
                            AdbCockpitModel.Sev.ERR -> p.bad; AdbCockpitModel.Sev.OK -> p.ok; AdbCockpitModel.Sev.INFO -> p.textSecondary
                        }), modifier = Modifier.testTag("adbc:log:${sev.name.lowercase()}"))
                    }
                }
            }
        }
    }
}

/**
 * The keep-alive panel: a light + the live keeper status, its four sub-lights (Service, Armed, Secure settings, Battery) and an
 * ON/OFF switch bound to the one "Keep Wireless debugging on" preference. Every light is a real [AdbCockpitModel.KeepAlive] fact.
 */
@Composable
private fun KeepAliveRow(ka: AdbCockpitModel.KeepAlive, set: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().border(KitStoreRow.step(1), p.hairline).padding(KitStoreRow.gap).testTag("adbc:keepalive"),
        verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
            Dot(ka.light)
            Column(Modifier.weight(1f)) {
                Text("Keep-alive", fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, color = p.textPrimary)
                KitStoreCaption(ka.status, color = p.textSecondary)
            }
            Chip(if (ka.on) "ON ✓" else "OFF", "adbc:keepalive-toggle", { set(!ka.on) }, primary = ka.on)
        }
        if (ka.subs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            for (x in ka.subs) Row(Modifier.border(KitStoreRow.step(1), p.hairline).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.step(1)).testTag("adbc:ka-sub:${x.label}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                Dot(x.light, size = 6)
                KitStoreCaption(x.label, color = p.textSecondary)
            }
        }
        KitStoreCaption(ka.detail, color = p.textSecondary, modifier = Modifier.testTag("adbc:ka-detail"))
    }
}

/** One chain row: (light) Step : message, its sub-lights and its action chips. A row of an inactive mode is dimmed. */
@Composable
private fun StepRow(s: AdbCockpitModel.Step, a: CockpitActions) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(KitStoreRow.gap).testTag("adbc:step:${s.id}"), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
        Dot(s.light, Modifier.padding(top = KitStoreRow.tight).testTag("adbc:light:${s.id}"))
        Column(Modifier.weight(1f).alpha(if (s.dim) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                Text(s.name, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, color = p.textPrimary)
                s.badge?.let { KitStoreCaption(it, Modifier.border(KitStoreRow.step(1), p.hairline).padding(horizontal = KitStoreRow.tight), color = if (s.dim) p.textSecondary else p.accent) }
            }
            Text(s.message, fontSize = KitStoreRow.caption, color = when (s.light) {
                AdbCockpitModel.Light.GREEN -> p.ok; AdbCockpitModel.Light.AMBER -> p.warn; AdbCockpitModel.Light.RED -> p.bad; AdbCockpitModel.Light.GREY -> p.textSecondary
            }, modifier = Modifier.testTag("adbc:msg:${s.id}"))
            if (s.subs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                for (x in s.subs) Row(Modifier.border(KitStoreRow.step(1), p.hairline).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.step(1)).testTag("adbc:sub:${x.label}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                    Dot(x.light, size = 6)
                    KitStoreCaption(x.label, color = p.textSecondary)
                }
            }
            if (s.acts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                for (x in s.acts) Chip(x.what.label, "adbc:act:${s.id}:${x.what.name.lowercase()}", { a.act(x.what) }, x.enabled, x.primary && !s.dim, x.why)
            }
        }
    }
}

/** Status tab: reads the device on open, on resume and when Wireless debugging / adb flips (no polling), tails the channel log live. */
@Composable
internal fun AdbStatusTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf<AdbCockpitModel.Inputs?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var logTick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        val stop = AdbWatch.observe(ctx) { runCatching { ShellBroker.invalidate(ctx) }; tick++ }
        val l: () -> Unit = { logTick++ }
        ChannelLog.shared.addListener(l)
        onDispose { owner.lifecycle.removeObserver(o); stop(); ChannelLog.shared.removeListener(l) }
    }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) {
            runCatching { AdbCockpitModel.Inputs(ChannelReader.read(ctx), AdbCockpitOps.digest(ctx), LaunchViaPrefs.current(ctx)) }.getOrNull()
        }
        if (r != null) inputs = r.copy(lastFailure = failure)
    }
    fun go(label: String, work: () -> CockpitResult) {
        busy = label; note = ""
        scope.launch(Dispatchers.IO) {
            val r = runCatching(work).getOrElse { CockpitResult(it.message ?: "failed", it.message ?: "failed") }
            failure = r.failure; note = r.note; busy = ""; tick++
        }
    }
    val a = CockpitActions(
        act = { what ->
            val f = inputs?.facts
            if (what == AdbCockpitModel.Do.OPEN_PERMS) AdbCockpitOps.run(ctx, what, false, false).let { note = it.note }
            else go(what.label + "...") { AdbCockpitOps.run(ctx, what, f?.adbPaired == true, f?.devOptions == true) }
        },
        autoFix = { go("Auto-fix running...") { AdbCockpitOps.autoFix(ctx) } },
        pickMode = { md -> ChannelModePrefs.set(ctx, md); failure = null; tick++ },
        copyLog = {
            runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ADB log", ChannelLog.shared.asText())) }
            note = "Log copied."
        },
        clearLog = { ChannelLog.shared.clear() },
        setKeepAlive = { on -> com.diegonmarcos.superapp.system.WirelessDebugKeeper.Prefs.setEnabled(ctx, on); tick++ },
    )
    val log = remember(logTick, tick) { ChannelLog.shared.entries() }
    val keepAlive = remember(tick) {
        runCatching {
            val on = com.diegonmarcos.superapp.system.WirelessDebugKeeper.Prefs.enabled(ctx)
            val s = com.diegonmarcos.superapp.adbdebug.WdKeeper.snapshot(ctx)
            AdbCockpitModel.keepAlive(on, s.armed, s.serviceRunning, s.canWrite, s.batteryExempt,
                s.lastRearmAt?.let { com.diegonmarcos.superapp.adbdebug.WdKeeper.ago(it) })
        }.getOrNull()
    }
    AdbCockpitContent(inputs, busy, note, log, a, keepAlive = keepAlive)
}

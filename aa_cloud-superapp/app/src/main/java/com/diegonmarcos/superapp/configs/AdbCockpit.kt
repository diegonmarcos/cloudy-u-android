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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.adbdebug.StepId
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
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The page's verbs; the stateful wrapper wires them, a test passes no-ops. Connect is [act] with [AdbCockpitModel.Do.CONNECT]: there is no other fix. */
internal class CockpitActions(
    val act: (AdbCockpitModel.Do) -> Unit = {}, val pickMode: (ChannelMode) -> Unit = {},
    val copyLog: () -> Unit = {}, val clearLog: () -> Unit = {}, val setKeepAlive: (Boolean) -> Unit = {},
)

/**
 * The Status page's measures. This ONE page is roomy on purpose (#1647): wide margins, large lights, readable type, one card per
 * group. Every size the page draws comes from here, so the test can hold the page to them.
 */
internal object CockpitRoom {
    /** Page margin, and the space between two cards. */
    val page = 16.dp
    /** Padding inside a card. */
    val card = 18.dp
    /** Space between two rows of a card. */
    val row = 18.dp
    /** Space between the parts of a row. */
    val gap = 12.dp
    /** A status light. */
    val light = 18.dp
    /** An action icon's box. */
    val icon = 44.dp
    val corner = 14.dp
    val logs = 240.dp
    val head = KitStoreRow.title * 1.5f
    val name = KitStoreRow.title * 1.3f
    val message = KitStoreRow.body * 1.25f
    val small = KitStoreRow.caption * 1.25f
}

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

/** A status light: a large filled circle in the state's ink; its state is its description. */
@Composable
private fun Dot(l: AdbCockpitModel.Light, modifier: Modifier = Modifier) {
    Box(modifier.size(CockpitRoom.light).background(lightInk(l), CircleShape).semantics { contentDescription = l.name.lowercase() })
}

/** One card: a rounded, softly filled block with room around its content. */
@Composable
private fun Card(tag: String, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(CockpitRoom.corner)
    Column(Modifier.testTag(tag).fillMaxWidth().clip(shape).background(p.textPrimary.copy(alpha = 0.05f)).border(1.dp, p.hairline, shape)
        .padding(CockpitRoom.card), verticalArrangement = Arrangement.spacedBy(CockpitRoom.row)) { content() }
}

/** A large labelled verb (Connect, Reconnect, a mode, a log tool). [primary] = filled; a disabled one is dimmed and says why. */
@Composable
private fun CockpitVerb(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, why: String? = null, small: Boolean = false) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(if (small) 8.dp else 10.dp)
    Text(label, fontSize = if (small) CockpitRoom.small else CockpitRoom.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
        color = if (primary) p.accent else p.textPrimary,
        modifier = modifier.testTag(tag).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(if (primary) p.accent.copy(alpha = 0.18f) else p.textPrimary.copy(alpha = 0.07f))
            .border(1.dp, if (primary) p.accent.copy(alpha = 0.7f) else p.hairline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 18.dp, vertical = if (small) 8.dp else 14.dp)
            .semantics { if (why != null) contentDescription = "$label: $why" })
}

/** A row's action: an icon in a round box with its word under it. [primary] = the next thing to do; a disabled one says why. */
@Composable
private fun ActIcon(x: AdbCockpitModel.Act, tag: String, primary: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val ink = if (primary) p.accent else p.textPrimary
    Column(Modifier.alpha(if (x.enabled) 1f else 0.4f).clickable(enabled = x.enabled, role = Role.Button, onClick = onClick).testTag(tag)
        .semantics { contentDescription = x.what.label + (x.why?.let { ": $it" } ?: "") },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(CockpitRoom.icon).background(if (primary) p.accent.copy(alpha = 0.18f) else p.textPrimary.copy(alpha = 0.07f), CircleShape)
            .border(1.dp, if (primary) p.accent.copy(alpha = 0.7f) else p.hairline, CircleShape), contentAlignment = Alignment.Center) {
            Text(x.what.icon, fontSize = CockpitRoom.head, color = ink)
        }
        Text(x.what.label, fontSize = CockpitRoom.small, color = p.textSecondary, maxLines = 1)
    }
}

private fun hms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

/**
 * The Status page, stateless. [inputs] null = still reading the device. Top to bottom:
 *  - the control card: Connect / Disconnect, Reconnect and the Mode selector (Shizuku | Local Server | Embedded), then the
 *    connection's own light and message. Connect IS the auto-fix: it walks the selected mode's whole chain.
 *  - one card per group (Perms, Setup, Embedded, Local Server, Shizuku), each row (light) (step name): (message) (action icons).
 *  - the keep-alive card, then the logs.
 * Drawn from [AdbCockpitModel] only, at [CockpitRoom]'s measures.
 */
@Composable
internal fun AdbCockpitContent(
    inputs: AdbCockpitModel.Inputs?, busy: String, note: String, log: List<ChannelLog.Entry>, a: CockpitActions,
    filterOpen: Boolean = false, query: String = "", keepAlive: AdbCockpitModel.KeepAlive? = null,
    tail: (@Composable () -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    var filtering by remember { mutableStateOf(filterOpen) }
    var q by remember { mutableStateOf(query) }
    val m = AdbCockpitModel.master(inputs)
    val conn = inputs?.let { AdbCockpitModel.steps(it).last() }
    val groups = inputs?.let { AdbCockpitModel.groups(it) }.orEmpty()
    val mode = inputs?.facts?.mode
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CockpitRoom.page).testTag("adbc:root"),
        verticalArrangement = Arrangement.spacedBy(CockpitRoom.page)) {
        // ── the control card: Connect / Disconnect, Reconnect, Mode; then the connection's light ──
        Card("adbc:control") {
            FlowRow(Modifier.fillMaxWidth().testTag("adbc:bar"), horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap), verticalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
                val t = inputs?.let { AdbCockpitModel.toggle(it.facts) }
                CockpitVerb(t?.what?.label ?: "Connect", "adbc:toggle", { t?.let { a.act(it.what) } }, enabled = t != null && t.enabled && busy.isEmpty(), primary = t?.primary == true, why = t?.why)
                CockpitVerb("↻ Reconnect", "adbc:reconnect", { a.act(AdbCockpitModel.Do.RECONNECT) }, enabled = inputs != null && busy.isEmpty())
                Row(Modifier.testTag("adbc:mode"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (md in AdbCockpitModel.SELECTABLE) CockpitVerb(AdbCockpitModel.modeName(md), "adbc:mode:${md.id}", { a.pickMode(md) }, primary = md == mode, small = true)
                }
            }
            if (mode == ChannelMode.AUTO) Text("Mode: Auto. Every channel is live; pick one above to use that channel alone.", fontSize = CockpitRoom.small, color = p.textSecondary,
                modifier = Modifier.testTag("adbc:mode-auto"))
            Row(Modifier.fillMaxWidth().testTag("adbc:header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
                Dot(m.light, Modifier.testTag("adbc:light:connection"))
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.label + ":", fontSize = CockpitRoom.head, fontWeight = FontWeight.Bold, color = p.textPrimary, modifier = Modifier.testTag("adbc:master"))
                    Text(if (busy.isNotEmpty()) busy else conn?.message ?: m.detail, fontSize = CockpitRoom.message,
                        color = if (busy.isNotEmpty() || conn == null || conn.light == AdbCockpitModel.Light.GREY) p.textSecondary else lightInk(conn.light),
                        modifier = Modifier.testTag("adbc:msg:connection"))
                }
            }
            // What blocks it, when that is not the connection's own message (a red step upstream).
            if (conn != null && m.detail != conn.message) Text(m.detail, fontSize = CockpitRoom.message, color = lightInk(m.light), modifier = Modifier.testTag("adbc:detail"))
            inputs?.let { i ->
                Text("Connect runs the whole chain: " + AdbCockpitModel.connectChain(i.facts.mode, i.via, i.facts.ownsServer).joinToString(" → "),
                    fontSize = CockpitRoom.small, color = p.textSecondary, modifier = Modifier.testTag("adbc:chain"))
            }
            if (note.isNotBlank()) SelectionContainer { Text(note, fontSize = CockpitRoom.message, color = p.textPrimary, modifier = Modifier.testTag("adbc:note")) }
        }
        // ── the groups ──
        if (groups.isEmpty()) Card("adbc:reading") { Text("Reading the device...", fontSize = CockpitRoom.message, color = p.textSecondary) }
        for (g in groups) GroupCard(g, a)
        // ── keep-alive: the "Keep Wireless debugging on" switch + the keeper's real state ──
        keepAlive?.let { ka -> KeepAliveCard(ka, a.setKeepAlive) }
        // ── logs ──
        Card("adbc:logs") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Logs", fontSize = CockpitRoom.head, fontWeight = FontWeight.Bold, color = p.textPrimary, modifier = Modifier.weight(1f))
                CockpitVerb(if (filtering) "filter ✓" else "filter", "adbc:log-filter", { filtering = !filtering; if (!filtering) q = "" }, small = true)
                CockpitVerb("copy", "adbc:log-copy", a.copyLog, small = true)
                CockpitVerb("clear", "adbc:log-clear", a.clearLog, small = true)
            }
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = CockpitRoom.small, color = p.textPrimary)
            if (filtering) BasicTextField(q, { q = it }, singleLine = true, textStyle = mono, cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().background(p.textPrimary.copy(alpha = 0.07f)).padding(10.dp).testTag("adbc:log-query"),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Filter by tag or text…", style = mono.copy(color = p.textSecondary)); inner() } })
            val shown = remember(log, q) { AdbCockpitModel.filter(log, q) }
            val scroll = rememberScrollState()
            LaunchedEffect(shown.size) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer {
                Column(Modifier.fillMaxWidth().height(CockpitRoom.logs).verticalScroll(scroll).testTag("adbc:log-body"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        // #1678 the Tester card, last on the Status tab
        tail?.invoke()
    }
}

/** One group's card: its title (and whether its mode is the selected one), then its rows. A mode the selector is not on is dimmed. */
@Composable
private fun GroupCard(g: AdbCockpitModel.Group, a: CockpitActions) {
    val p = LocalKitPalette.current
    Card("adbc:step:${g.id}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
            Text(g.title, fontSize = CockpitRoom.head, fontWeight = FontWeight.Bold, color = p.textPrimary, modifier = Modifier.alpha(if (g.dim) 0.6f else 1f))
            g.badge?.let { Text(if (g.dim) "$it · switch mode to use" else it, fontSize = CockpitRoom.small, color = if (g.dim) p.textSecondary else p.accent, modifier = Modifier.testTag("adbc:modebadge:${g.id}")) }
        }
        for (r in g.rows) StepRow(g, r, a)
    }
}

/** One row, in the page's one shape: (light) (step name): (message) (action icons). */
@Composable
private fun StepRow(g: AdbCockpitModel.Group, r: AdbCockpitModel.Row, a: CockpitActions) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().testTag("adbc:row:${r.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
        Dot(r.light, Modifier.testTag("adbc:light:${r.id}"))
        FlowRow(Modifier.weight(1f).alpha(if (g.dim) 0.6f else 1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.name + ":", fontSize = CockpitRoom.name, fontWeight = FontWeight.Bold, color = p.textPrimary, modifier = Modifier.testTag("adbc:name:${r.id}"))
            Text(r.message, fontSize = CockpitRoom.message, color = if (r.light == AdbCockpitModel.Light.GREY) p.textSecondary else lightInk(r.light),
                modifier = Modifier.testTag("adbc:msg:${r.id}"))
        }
        if (r.acts.isNotEmpty()) Row(Modifier.testTag("adbc:acts:${r.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (x in r.acts) ActIcon(x, "adbc:act:${g.id}:${x.what.name.lowercase()}", x.primary && !g.dim) { a.act(x.what) }
        }
    }
}

/**
 * The keep-alive card, in the same row shape: the keeper's light, its live status and the ON/OFF switch bound to the one "Keep
 * Wireless debugging on" preference, then its four lights (Service, Armed, Secure settings, Battery) and what it last did. Every
 * light is a real [AdbCockpitModel.KeepAlive] fact.
 */
@Composable
private fun KeepAliveCard(ka: AdbCockpitModel.KeepAlive, set: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    Card("adbc:keepalive") {
        Text("Keep-alive", fontSize = CockpitRoom.head, fontWeight = FontWeight.Bold, color = p.textPrimary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
            Dot(ka.light)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Keep Wireless debugging on:", fontSize = CockpitRoom.name, fontWeight = FontWeight.Bold, color = p.textPrimary)
                Text(ka.status, fontSize = CockpitRoom.message, color = if (ka.light == AdbCockpitModel.Light.GREY) p.textSecondary else lightInk(ka.light))
            }
            CockpitVerb(if (ka.on) "ON ✓" else "OFF", "adbc:keepalive-toggle", { set(!ka.on) }, primary = ka.on, small = true)
        }
        if (ka.subs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(CockpitRoom.row), verticalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
            for (x in ka.subs) Row(Modifier.testTag("adbc:ka-sub:${x.label}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(x.light)
                Text(x.label, fontSize = CockpitRoom.message, color = p.textPrimary)
            }
        }
        Text(ka.detail, fontSize = CockpitRoom.small, color = p.textSecondary, modifier = Modifier.testTag("adbc:ka-detail"))
    }
}

/**
 * Status tab: reads the device on open, on resume and when Wireless debugging / adb flips (no polling), tails the channel log live.
 * Connect runs the whole chain; when it stopped for the person at a step and a fresh reading shows that step done, it goes on by itself.
 */
@Composable
internal fun AdbStatusTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf<AdbCockpitModel.Inputs?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf<StepId?>(null) }
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
    fun go(label: String, connect: Boolean = false, work: () -> CockpitResult) {
        busy = label; note = ""
        scope.launch(Dispatchers.IO) {
            val r = runCatching(work).getOrElse { CockpitResult(it.message ?: "failed", it.message ?: "failed") }
            if (connect) waiting = r.waiting
            failure = r.failure; note = r.note; busy = ""; tick++
        }
    }
    fun connect() = go("Connecting: walking the whole chain...", connect = true) { AdbCockpitOps.connect(ctx) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) {
            runCatching { AdbCockpitModel.Inputs(ChannelReader.read(ctx), AdbCockpitOps.digest(ctx), LaunchViaPrefs.current(ctx)) }.getOrNull()
        }
        if (r != null) {
            inputs = r.copy(lastFailure = failure)
            // The step Connect stopped at for the person is done now: go on with the chain.
            if (busy.isEmpty() && AdbCockpitModel.resumes(waiting, r.facts)) { waiting = null; connect() }
        }
    }
    val a = CockpitActions(
        act = { what ->
            val f = inputs?.facts
            when (what) {
                AdbCockpitModel.Do.OPEN_PERMS -> AdbCockpitOps.run(ctx, what, false, false).let { note = it.note }
                AdbCockpitModel.Do.CONNECT -> connect()
                else -> go(what.label + "...") { AdbCockpitOps.run(ctx, what, f?.adbPaired == true, f?.devOptions == true) }
            }
        },
        pickMode = { md -> ChannelModePrefs.set(ctx, md); failure = null; waiting = null; tick++ },
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
    AdbCockpitContent(inputs, busy, note, log, a, keepAlive = keepAlive, tail = { AdbTesterCard { connect() } })
}

/** #1678 The Tester card: one button runs [AdbTester] over the broker; per command its channel, exit, time and output; Connect on failure. */
@Composable
internal fun AdbTesterCard(onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<AdbTester.Line>?>(null) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = CockpitRoom.small, color = p.textPrimary)
    Card("adbc:tester") {
        Text("Tester", fontSize = CockpitRoom.head, fontWeight = FontWeight.Bold, color = p.textPrimary)
        Text("Runs harmless commands through the same broker the status dropdown and X-Ray use.", fontSize = CockpitRoom.small, color = p.textSecondary)
        CockpitVerb(if (running) "Testing..." else "Run tests", "adbc:tester:run", {
            running = true
            scope.launch(Dispatchers.IO) {
                val r = runCatching { AdbTester.run(ctx) }.getOrElse { listOf(AdbTester.Line("tester", false, "none", null, 0, it.javaClass.simpleName)) }
                lines = r; running = false
            }
        }, enabled = !running, primary = true)
        lines?.forEachIndexed { i, l ->
            Column(Modifier.fillMaxWidth().testTag("adbc:tester:line:$i")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CockpitRoom.gap)) {
                    Dot(if (l.ok) AdbCockpitModel.Light.GREEN else AdbCockpitModel.Light.RED, Modifier)
                    Text(l.cmd, style = mono.copy(fontWeight = FontWeight.Bold))
                }
                Text("via ${l.channel} · exit ${l.exit?.toString() ?: "-"} · ${l.ms} ms", style = mono.copy(color = p.textSecondary))
                SelectionContainer { Text(l.output, style = mono.copy(color = if (l.ok) p.textPrimary else p.bad)) }
            }
        }
        if (lines?.any { !it.ok } == true)
            CockpitVerb("Connect (auto-fix)", "adbc:tester:connect", onConnect, primary = true)
    }
}

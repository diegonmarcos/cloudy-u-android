@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.adbdebug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val C_OK = Color(0xFF2F855A)
private val C_WARN = Color(0xFFB7791F)
private val C_BAD = Color(0xFFC53030)
private val C_UNKNOWN = Color(0xFF718096)
private val C_ACCENT = Color(0xFF7C3AED)

private fun Dot.color(): Color = when (this) {
    Dot.OK -> C_OK
    Dot.WARN -> C_WARN
    Dot.BAD -> C_BAD
    Dot.UNKNOWN -> C_UNKNOWN
}

private fun ConnState.dot(): Dot = when (this) {
    ConnState.UP -> Dot.OK
    ConnState.DEGRADED, ConnState.CONNECTING -> Dot.WARN
    ConnState.DOWN -> Dot.BAD
}

private fun ConnState.title(): String = when (this) {
    ConnState.UP -> "Up"
    ConnState.DEGRADED -> "Degraded"
    ConnState.CONNECTING -> "Connecting"
    ConnState.DOWN -> "Down"
}

private fun stamp(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

private fun glyph(s: StepState): String = when (s) {
    StepState.PENDING -> "·"
    StepState.RUNNING -> "▶"
    StepState.DONE -> "✓"
    StepState.SKIPPED -> "–"
    StepState.WAITING -> "…"
    StepState.FAILED -> "✕"
}

private fun glyphColor(s: StepState): Color = when (s) {
    StepState.DONE -> C_OK
    StepState.FAILED -> C_BAD
    StepState.WAITING, StepState.RUNNING -> C_WARN
    else -> C_UNKNOWN
}

@Composable
private fun StateDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val fg = LocalContentColor.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.15f)))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C_ACCENT)
        content()
    }
}

@Composable
private fun Dim(text: String, modifier: Modifier = Modifier, size: Int = 10) {
    Text(text, modifier, fontSize = size.sp, color = LocalContentColor.current.copy(alpha = 0.65f))
}

/**
 * THE ADB Shell page: one dense control for the privileged channel, the same composable in every app
 * that links this module (the SuperApp's Configs > Network > ADB Shell, Cloud Store's Settings, Cloud
 * Account's Setup, and the full-screen [AdbShellActivity] every status chip opens). Sections, in order:
 * status table, connect, active connection, logs, declared needs, setup checklist. Every rule behind
 * it ([ChannelState], [ConnectPlan], [ChannelActions], [ChannelLog], [DeclaredNeeds], [SetupChecklist])
 * is pure and tested; this file only draws them. No minimum heights anywhere.
 *
 * It does not scroll by itself: the host does (a nested vertical scroll cannot be measured).
 */
@Composable
fun AdbShellPage(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = ChannelLog.shared

    var mode by remember { mutableStateOf(ChannelModePrefs.current(ctx)) }
    var via by remember { mutableStateOf(LaunchViaPrefs.current(ctx)) }
    var raw by remember { mutableStateOf<ChannelFacts?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<StepRun>>(emptyList()) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var testOut by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var logTick by remember { mutableStateOf(0) }
    val lastProbe = remember { arrayOf("") }

    DisposableEffect(Unit) {
        val l: () -> Unit = { logTick++ }
        log.addListener(l)
        onDispose { log.removeListener(l) }
    }
    LaunchedEffect(tick, mode) {
        val f = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) }.getOrNull() }
        if (f != null) {
            raw = f
            val s = "route=${ChannelState.route(f.copy(mode = mode)) ?: "none"} state=${ChannelState.connState(f.copy(mode = mode))} " +
                "adb=${f.adbConnected} server=${f.serverRunning} shizuku=${f.shizuku}"
            if (s != lastProbe[0]) { lastProbe[0] = s; log.add("probe", s) }
        } else log.add("probe", "could not read the layers")
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6_000)
            if (!connecting && busy == null) tick++
        }
    }

    // The other adb app(s) on this phone: one pass per probe, off the main thread (binder calls into their providers).
    var peers by remember { mutableStateOf<List<PeerStatus>?>(null) }
    LaunchedEffect(tick) { peers = withContext(Dispatchers.IO) { runCatching { AdbPeerReader.read(ctx) }.getOrNull() } }

    val facts = raw?.copy(mode = mode, connecting = connecting)
    val conn = facts?.let { ChannelState.connState(it) }

    fun connect() {
        if (connecting) return
        connecting = true; outcome = null; steps = emptyList(); note = ""
        scope.launch(Dispatchers.IO) {
            val out = ConnectPlan.run(mode, via, DeviceConnectExecutor(ctx), log, ChannelModePrefs.ownsServer()) { steps = it }
            outcome = out
            connecting = false
            tick++
        }
    }

    fun runChannelAction(action: ChannelAction, work: (Context) -> String) {
        if (busy != null || connecting) return
        busy = action.label
        scope.launch(Dispatchers.IO) {
            val r = runCatching { work(ctx) }.getOrElse { log.add("action", "${action.label} failed: ${it.message}"); it.message ?: "failed" }
            if (action == ChannelAction.TEST) { testOut = r; note = "" } else note = r
            busy = null
            tick++
        }
    }

    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── header ───────────────────────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ADB Shell", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
            Text(conn?.title() ?: "Reading", fontSize = 12.sp, modifier = Modifier.weight(1f))
            DenseButton("Refresh", onClick = { tick++ })
        }
        // WHO this app is to adbd (the pairing name in the phone's paired-devices list), the same line in every app.
        val identity = remember { AdbIdentity.host(ctx.packageName) }
        Dim("Connection name: ${identity.deviceName}  ·  ${identity.applicationId}", size = 11)

        // ── 1  status table ──────────────────────────────────────────────────────────────────────
        Section("1  STATUS") {
            val rows = facts?.let { ChannelState.rows(it) }
            if (rows == null) Dim("Reading the layers...", size = 11)
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(14.dp))
                    Dim("Layer", Modifier.width(98.dp), 9)
                    Dim("State", Modifier.weight(1f), 9)
                    Dim("Probed", Modifier.width(48.dp), 9)
                }
                for (r in rows) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    StateDot(r.dot.color(), Modifier.padding(top = 4.dp, end = 6.dp))
                    Text(r.label, Modifier.width(98.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(r.value, Modifier.weight(1f), fontSize = 11.sp)
                    Text(stamp(r.probedAt), Modifier.width(48.dp), fontSize = 9.sp, textAlign = TextAlign.End,
                        color = LocalContentColor.current.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── 2  connect ───────────────────────────────────────────────────────────────────────────
        Section("2  CONNECT") {
            ChannelModeBar { m -> mode = m; log.add("mode", "mode set to ${m.title()}"); tick++ }
            if (mode == ChannelMode.LOCAL_SERVER) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dim("Launch the server via", size = 11)
                for (v in LaunchVia.values()) DenseButton(v.title, primary = v == via, onClick = {
                    via = v; LaunchViaPrefs.set(ctx, v); log.add("mode", "server launcher set to ${v.title}"); tick++
                })
            }
            val owns = ChannelModePrefs.ownsServer()
            for ((i, m) in ConnectPlan.methods(mode, via, owns).withIndex())
                Text("${i + 1}. ${m.title} - ${m.detail}", fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenseButton(if (connecting) "Connecting..." else "Connect", primary = true, enabled = !connecting && busy == null, onClick = { connect() })
                Dim("runs: " + ConnectPlan.steps(mode, via, owns).joinToString(" > ") { it.label }, Modifier.weight(1f))
            }
            for (s in steps) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(glyph(s.state), Modifier.width(12.dp), fontSize = 11.sp, color = glyphColor(s.state), fontFamily = FontFamily.Monospace)
                Text(s.id.label, Modifier.weight(1f), fontSize = 11.sp)
                if (s.detail.isNotBlank()) Dim(s.detail.take(60), Modifier.weight(1f))
            }
            outcome?.let { o ->
                when (o.status) {
                    ConnectStatus.SUCCESS -> Text("Connected: every step passed.", fontSize = 11.sp, color = C_OK)
                    ConnectStatus.WAITING_FOR_USER -> Text(o.explanation.orEmpty(), fontSize = 11.sp, color = C_WARN)
                    ConnectStatus.FAILED -> Text(o.explanation.orEmpty(), fontSize = 11.sp, color = C_BAD)
                }
            }
        }

        // ── 3  active connection ─────────────────────────────────────────────────────────────────
        Section("3  ACTIVE CONNECTION") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
                Text((conn?.title() ?: "Reading") + (facts?.let { f -> ChannelState.route(f)?.let { " via $it" } } ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            // Keep the channel armed: the app's declared default (build.json::privileged_channel.default_on) until the user flips it.
            var autoArm by remember { mutableStateOf(ChannelAutoArm.enabled(ctx)) }
            if (ChannelAutoArm.hostSwitch == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DenseButton(if (autoArm) "Keep armed: ON" else "Keep armed: OFF", primary = autoArm,
                        onClick = { autoArm = !autoArm; ChannelAutoArm.set(ctx, autoArm) })
                    Dim(if (autoArm) "Re-arms the channel after a reboot and when Wireless debugging is available." else "Never connects by itself; use Reconnect.")
                }
            } else Dim("Auto-connect follows Configs > Keep Wireless debugging on (" + (if (autoArm) "on" else "off") + ").")
            val enab = facts?.let { ChannelActions.enablement(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (a in ChannelAction.values()) {
                    val on = enab?.get(a)?.enabled == true && busy == null && !connecting
                    DenseButton(a.label, enabled = on, onClick = {
                        when (a) {
                            ChannelAction.DISCONNECT -> runChannelAction(a) { ChannelOps.disconnect(it) }
                            ChannelAction.RECONNECT -> runChannelAction(a) { ChannelOps.reconnect(it) }
                            ChannelAction.RESTART_SERVER -> runChannelAction(a) { ChannelOps.startServer(it, restart = true) }
                            ChannelAction.STOP_SERVER -> runChannelAction(a) { ChannelOps.stopServer(it) }
                            ChannelAction.TEST -> runChannelAction(a) { ChannelOps.test(it) }
                        }
                    })
                }
            }
            val why = enab?.entries?.filter { !it.value.enabled }?.joinToString("  ·  ") { "${it.key.label}: ${it.value.why}" }
            if (!why.isNullOrBlank()) Dim(why)
            busy?.let { Dim("$it...", size = 11) }
            if (note.isNotBlank()) Text(note, fontSize = 11.sp)
            if (testOut.isNotBlank()) SelectionContainer { Text(testOut, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }

        // ── 4  logs ──────────────────────────────────────────────────────────────────────────────
        Section("4  LOGS") {
            val entries = remember(logTick) { log.entries() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DenseButton("Copy", enabled = entries.isNotEmpty(), onClick = {
                    runCatching {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("ADB Shell log", log.asText()))
                    }
                })
                DenseButton("Clear", enabled = entries.isNotEmpty(), onClick = { log.clear() })
                Dim("${entries.size} events in memory; tokens and codes are never kept.", Modifier.weight(1f))
            }
            val scroll = rememberScrollState()
            LaunchedEffect(logTick) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxWidth().height(150.dp).border(1.dp, LocalContentColor.current.copy(alpha = 0.2f)).padding(4.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    if (entries.isEmpty()) Dim("No events yet.")
                    for (e in entries) Text("${stamp(e.at)} [${e.tag}] ${e.text}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── 5  declared needs ────────────────────────────────────────────────────────────────────
        Section("5  DECLARED NEEDS") {
            val label = remember { runCatching { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }.getOrDefault(ctx.packageName) }
            val needs = remember { DeclaredNeeds.current() }
            Text(label + " · " + if (ChannelModePrefs.ownsServer()) "own local server 127.0.0.1:${AdbShellBootstrap.port()} (${AdbShellBootstrap.niceName()})"
                else "no local server of its own", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            if (needs.isEmpty()) Dim("This app declares no needs in build.json::privileged_channel.")
            for (n in needs) Column {
                Text("• ${n.label} - ${n.why}", fontSize = 11.sp)
                if (n.commands.isNotEmpty()) Dim(n.commands.joinToString(", "), Modifier.padding(start = 10.dp))
            }
        }

        // ── 6  setup ─────────────────────────────────────────────────────────────────────────────
        Section("6  SETUP") {
            val items = facts?.let { SetupChecklist.items(it, via) }
            if (items == null) Dim("Reading the device...", size = 11)
            else {
                Dim(SetupChecklist.summary(items) + " for ${mode.title()} mode", size = 11)
                for (item in items) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (item.status) { SetupStatus.OK -> "✓"; SetupStatus.TODO -> "✕"; SetupStatus.UNKNOWN -> "?" }, Modifier.width(12.dp), fontSize = 12.sp,
                        color = when (item.status) { SetupStatus.OK -> C_OK; SetupStatus.TODO -> C_BAD; SetupStatus.UNKNOWN -> C_UNKNOWN })
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 11.sp)
                        Dim(item.detail)
                    }
                    item.action?.let { a ->
                        DenseButton(item.actionLabel, onClick = {
                            scope.launch(Dispatchers.IO) { note = ChannelOps.setup(ctx, a) }
                        })
                    }
                }
            }
        }

        // ── 7  apps on this phone ───────────────────────────────────────────────────────────────
        Section("7  SIDE BY SIDE") {
            val list = peers
            if (list == null) Dim("Reading the other apps...", size = 11)
            else {
                for (st in list) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateDot(AdbPeers.dot(st.link).color())
                    Text(st.peer.label + (if (st.peer.applicationId == ctx.packageName) " (this app)" else ""), Modifier.width(98.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(AdbPeers.title(st.link) + if (AdbPeers.holdsPairing(st, System.currentTimeMillis())) ", pairing now" else "", Modifier.weight(1f), fontSize = 11.sp)
                }
                Dim("Each app has its own key, paired-devices entry, port and process; only the pairing window is shared, so one app pairs at a time.")
            }
        }

        // ── 8  keep wireless debugging on (#1467) ──────────────────────────────────────────────
        Section("8  KEEP WIRELESS DEBUGGING ON") { KeeperSection() }
    }
}

/**
 * THE page as every app hosts it inside its own frame (SuperApp > Configs > ADB Shell, Cloud Store > Configs > ADB Shell):
 * the page itself, scrolling, with [pad] around it. The theme is the host's own (SuperApp's kit theme, Cloud Store's dense
 * Material), so the page looks the same in both and there is no second copy of any of it.
 */
@Composable
fun AdbShellHostedPage(pad: androidx.compose.ui.unit.Dp = HOSTED_PAD) {
    Column(Modifier.fillMaxSize().testTag(HOSTED_TAG).verticalScroll(rememberScrollState()).padding(pad)) { AdbShellPage() }
}

const val HOSTED_TAG = "adb_shell_page"

/** The padding around the hosted page: ONE value, so both apps frame it identically (the page adds its own 8 dp inside). */
val HOSTED_PAD: androidx.compose.ui.unit.Dp = 12.dp

/**
 * The page as a whole screen: themed, on a surface, scrolling. [dark] null follows the system. Used by
 * [AdbShellActivity] and by hosts that have no scroll or theme of their own.
 */
@Composable
fun AdbShellScreen(dark: Boolean? = null) {
    val isDark = dark ?: isSystemInDarkTheme()
    // Dense, like every fleet Compose screen: a bare MaterialTheme(colorScheme) hands every Text a fixed 24.sp line and every
    // Material control a 48 dp floor, which is what made the converted pages "huge" (density-guard). This lib does not depend
    // on libs:ui-kit, so it carries the same two removals itself.
    MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme(), typography = denseTypography()) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 36.dp,
        ) {
            Surface(Modifier.fillMaxSize()) {
                AdbShellHostedPage(pad = 0.dp)
            }
        }
    }
}

/** Material's typography without the fixed line height and tracking that swell every dense Text (see [AdbShellScreen]). */
private fun denseTypography(): androidx.compose.material3.Typography {
    val b = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.d() = copy(
        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified, lineHeightStyle = null,
    )
    return b.copy(
        displayLarge = b.displayLarge.d(), displayMedium = b.displayMedium.d(), displaySmall = b.displaySmall.d(),
        headlineLarge = b.headlineLarge.d(), headlineMedium = b.headlineMedium.d(), headlineSmall = b.headlineSmall.d(),
        titleLarge = b.titleLarge.d(), titleMedium = b.titleMedium.d(), titleSmall = b.titleSmall.d(),
        bodyLarge = b.bodyLarge.d(), bodyMedium = b.bodyMedium.d(), bodySmall = b.bodySmall.d(),
        labelLarge = b.labelLarge.d(), labelMedium = b.labelMedium.d(), labelSmall = b.labelSmall.d(),
    )
}

/**
 * The compact status chip for Compose hosts: a dot and the one-line state, tapping opens the page.
 * Every place that used to carry its own channel controls shows this instead (View hosts have the
 * same text from [ChannelState.chip] on their own button).
 */
@Composable
fun AdbShellChip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var facts by remember { mutableStateOf<ChannelFacts?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            facts = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) }.getOrNull() }
            delay(10_000)
        }
    }
    val conn = facts?.let { ChannelState.connState(it) }
    Row(modifier.clickable { AdbShellLink.open(ctx) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateDot((conn?.dot() ?: Dot.UNKNOWN).color())
        Text(ChannelState.chip(facts) + "  ›", fontSize = 11.sp)
    }
}

/** #1467 Configs > Keep wireless debugging on: status chips (permission, battery, service, Wi-Fi, last re-arm) and the audit log. */
@Composable
private fun KeeperSection() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<WdKeeperStatus.Snapshot?>(null) }
    var entries by remember { mutableStateOf<List<WdAuditLog.Entry>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    // #1513 no polling: read on open and on resume. Every permission is asked in Configs > Perms, never here.
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { runCatching { WdKeeper.snapshot(ctx) to WdKeeper.audit(ctx).entries() }.getOrNull() }
            ?.let { snap = it.first; entries = it.second }
    }
    DisposableEffect(ctx) {
        val app = ctx.applicationContext as? android.app.Application
        var host: Context? = ctx
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        val cb = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { if (a === host) tick++ }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app?.registerActivityLifecycleCallbacks(cb)
        onDispose { app?.unregisterActivityLifecycleCallbacks(cb) }
    }
    val s = snap
    if (s == null) { Dim("Reading...", size = 11); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val chips = WdKeeperStatus.chips(s) { WdKeeper.ago(it) }
        for (c in chips) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateDot(when (c.level) {
                WdKeeperStatus.Level.OK -> C_OK
                WdKeeperStatus.Level.WARN -> C_WARN
                WdKeeperStatus.Level.BAD -> C_BAD
                WdKeeperStatus.Level.UNKNOWN -> C_UNKNOWN
            })
            Text(c.label + ": " + c.value, fontSize = 11.sp)
        }
    }
    if (!s.armed) Dim("Off. Turn on Keep armed (or Configs > Keep Wireless debugging on) to arm the keeper.")
    if (!s.canWrite) {
        Dim("The keeper cannot turn wireless debugging back on without the Write secure settings permission.", size = 11)
        DenseButton("Fix in Perms ›", primary = true, onClick = { WdKeeper.openPermsFix(ctx) })
    }
    if (s.armed && !s.batteryExempt) {
        Dim("Android may kill this app in the background. Set its battery to Unrestricted (on Samsung also Never sleeping apps).")
        DenseButton("Battery: allow unrestricted", onClick = { WdKeeper.promptBattery(ctx) })
    }
    Dim("Every change of Wireless debugging, newest first (one row per event):")
    if (entries.isEmpty()) Dim("Nothing recorded yet.")
    else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((t, w) in listOf("Time" to 0.14f, "State" to 0.1f, "Wi-Fi" to 0.1f, "Cause" to 0.36f, "Re-armed by" to 0.3f))
                Text(t, Modifier.weight(w), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (e in entries.takeLast(30).reversed()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stamp(e.at), Modifier.weight(0.14f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(if (e.on) "ON" else "OFF", Modifier.weight(0.1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(if (e.wifi) "yes" else "no", Modifier.weight(0.1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(e.cause, Modifier.weight(0.36f), fontSize = 10.sp)
                    Text(if (e.by == "-") "nobody" else e.by, Modifier.weight(0.3f), fontSize = 10.sp)
                }
            }
        }
    }
}

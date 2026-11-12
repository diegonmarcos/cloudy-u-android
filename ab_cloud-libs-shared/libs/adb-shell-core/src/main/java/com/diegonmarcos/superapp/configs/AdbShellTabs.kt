package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Re-read key: bumps on resume (no polling). */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return tick
}

/** Shell tab: the terminal (input, output, tabs) and the command bookmarks (the snippet palette with its starred favourites) from [AdbTerminal]. */
@Composable
fun AdbTermTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ShellStatus?>(null) }
    var bump by remember { mutableStateOf(0) }
    val tick = resumeTick()
    LaunchedEffect(tick, bump) { status = withContext(Dispatchers.IO) { runCatching { ShellBroker.status(ctx) }.getOrNull() } }
    val rearm: () -> Unit = { scope.launch(Dispatchers.IO) { runCatching { ChannelOps.reconnect(ctx) }; bump++ } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("adb:tab:shell")) {
        AdbTerminal(status, onReconnect = rearm, outputHeight = KitStoreRow.step(520))
    }
}

/**
 * #1676 Perms tab: ONLY the prerequisites ADB Shell needs ([AdbPrereqs]), in chain order, human steps first, each with a live light from a
 * real read, its owner, its action and a one-line why; the first failing step is highlighted. Every other permission stays in Configs > Perms
 * (one tap away at the bottom). Reads run only while this tab is composed (on open, on resume, after an action), off the main thread.
 */
@Composable
fun AdbPermsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AdbPrereqs.Row>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    val tick = resumeTick()
    LaunchedEffect(tick, bump) { rows = withContext(Dispatchers.IO) { runCatching { readAdbPrereqs(ctx) }.getOrDefault(emptyList()) } }
    val act: (AdbPrereqs.Action) -> Unit = { a -> scope.launch(Dispatchers.IO) { note = runCatching { runPrereqAction(ctx, a) }.getOrElse { "failed: ${it.message}" }; bump++ } }
    AdbPermsChain(rows, note, act) { PermsLink.open(ctx, "adb-shell", emptyList()) }
}

/** Real reads only: the channel facts (adb echo round trip, server probe) plus this app's own grants. Blocking. */
fun readAdbPrereqs(ctx: Context): List<AdbPrereqs.Row> {
    val app = ctx.applicationContext
    val f = ChannelReader.read(app)
    fun held(p: String): Boolean? = runCatching { app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }.getOrNull()
    val live = AdbPrereqs.Live(
        writeSecure = held(android.Manifest.permission.WRITE_SECURE_SETTINGS),
        dump = held(android.Manifest.permission.DUMP),
        batteryExempt = runCatching { app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) }.getOrNull(),
        sdk = Build.VERSION.SDK_INT,
    )
    return AdbPrereqs.rows(f, live, app.packageName)
}

/** One row's button. Settings pages are deep links; grants are the Perms catalogue's own lines over the armed broker; never a prompt. */
fun runPrereqAction(ctx: Context, a: AdbPrereqs.Action): String = when (a) {
    is AdbPrereqs.Action.Open -> ChannelOps.setup(ctx, a.setup)
    is AdbPrereqs.Action.Grant -> {
        val ch = BrokerGrantChannel(ctx)
        val row = readPermRows(ctx, null).firstOrNull { it.entry.id == a.entry }
        val lines = if (row == null) emptyList() else adbLinesFor(ctx, listOf(row))
        when {
            !ch.up() -> "No adb channel up yet: connect first."
            lines.isEmpty() -> "Nothing to run for ${a.entry} on this device."
            else -> {
                val outs = lines.map { ch.exec(it.command) }
                when {
                    outs.any { it == null } -> "No adb channel answered."
                    else -> outs.firstOrNull { it!!.startsWith("error:") } ?: "Granted via adb."
                }
            }
        }
    }
    AdbPrereqs.Action.Connect -> AdbCockpitOps.connect(ctx).note
    is AdbPrereqs.Action.InPerms -> { PermsLink.open(ctx, "adb-shell", listOf(a.entry)); "Opened Configs > Perms." }
}

/** The chain as drawn (stateless: the test drives it with fixed rows). */
@Composable
fun AdbPermsChain(rows: List<AdbPrereqs.Row>?, note: String?, onAction: (AdbPrereqs.Action) -> Unit, onOpenAll: () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("adb:tab:perms")) {
        if (rows == null) { KitStoreRowLine { KitStoreCaption("Checking ADB Shell prerequisites...", color = p.textSecondary) } }
        else {
            val first = AdbPrereqs.firstFailing(rows)
            val grant = AdbPrereqs.canGrant(rows)
            KitStoreRowLine(Modifier.testTag("adb:prereq:summary")) {
                KitStoreCaption(if (first == null) "All ${rows.size} ADB Shell prerequisites on" else "${rows.count { it.status == AdbPrereqs.Status.ON }}/${rows.size} on - next: ${first.label}",
                    color = if (first == null) p.ok else p.warn)
            }
            if (note != null) KitStoreRowLine { KitStoreCaption(note, color = p.textSecondary) }
            rows.forEachIndexed { i, r ->
                val hot = r === first
                val ink = when (r.status) { AdbPrereqs.Status.ON -> p.ok; AdbPrereqs.Status.OFF -> p.bad; AdbPrereqs.Status.UNKNOWN -> p.textSecondary }
                val m = if (hot) Modifier.background(p.warn.copy(alpha = 0.14f)) else Modifier
                Column(m.fillMaxWidth().testTag("adb:prereq:${r.id}")) {
                KitStoreRowLine {
                    KitStoreDot("●", ink)
                    Column(Modifier.weight(1f)) {
                        KitStoreName("${i + 1}. ${r.label}", color = p.textPrimary)
                        KitStoreCaption("${r.owner.word} · ${r.status.word} · ${r.why}", color = p.textSecondary)
                        if (hot) KitStoreCaption("First step to fix", color = p.warn, modifier = Modifier.testTag("adb:prereq:first-fail"))
                    }
                }
                KitStoreRowLine {
                    r.actions.forEachIndexed { k, a ->
                        if (a is AdbPrereqs.Action.Grant && (!grant || r.status == AdbPrereqs.Status.ON)) return@forEachIndexed
                        KitStoreQuick(a.label, KitStoreRow.BLUE_FILL, Modifier.testTag("adb:prereq:${r.id}:act$k")) { onAction(a) }
                    }
                }
                }
            }
        }
        KitStoreRowLine { KitStoreQuick("All permissions: Configs > Perms ›", KitStoreRow.BLUE_FILL, Modifier.testTag("adb:perms-open")) { onOpenAll() } }
    }
}

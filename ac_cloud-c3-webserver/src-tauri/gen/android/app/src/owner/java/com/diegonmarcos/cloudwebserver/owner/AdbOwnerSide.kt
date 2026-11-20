package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.ui.Tone
import com.diegonmarcos.superapp.configs.AdbPermsTab
import com.diegonmarcos.superapp.configs.AdbShellBrokerPage
import com.diegonmarcos.superapp.configs.AdbStatusTab
import com.diegonmarcos.superapp.configs.AdbTermTab
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.themebg.ThemeBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.ui.Act
import com.diegonmarcos.cloudwebserver.ui.Acts
import com.diegonmarcos.cloudwebserver.ui.Card
import com.diegonmarcos.cloudwebserver.ui.Note
import com.diegonmarcos.cloudwebserver.ui.PageColumn
import com.diegonmarcos.superapp.adbdebug.AdbShellPage
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.system.AdbReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1682 P2b the REAL ADB owner side, compiled only when adb-owner.json names this app (P3 cut-over) or by the non-shipped CI
 * verify (C3_ADB_OWNER_VERIFY=1). It hosts the one ADB Shell: the lib's page (pairing + its notification, embedded adb, local
 * server / embedded / Shizuku modes), the readiness source (libs:adb-shell-core AdbReadiness, pushed, no polling), Connect with
 * auto-fix (the lib page's own Connect), the Tester (`id -u` over the broker) and the channel log. The AdbShellServer stays
 * loopback + token inside the lib; nothing here is reachable from the Rust HTTP / SSHd / API / mesh routes.
 */
object AdbOwnerSide {
    const val active: Boolean = true
    const val why: String = ""

    /**
     * Process start: own the broker's change callback (AdbReadiness). Connect with auto-fix is the lib ADB Shell page's own control
     * (adb-shell-one-place: the page is the only place that connects), drawn below; the Status card's Connect re-checks. Never throws.
     */
    fun install(ctx: Context) {
        runCatching { com.diegonmarcos.superapp.system.AdbReadinessOwner.install() }
    }

    /**
     * #1713 where every field of the previous Server tab went (rule 16, held by test-c3-webserver-adb-subpages.sh): the card
     * title -> the sub page that draws it. The sub pages themselves are the fleet's shared second row (libs:ui-nav-bars
     * NavSectionLayout in ui/Shell.kt) over build.json::ui.sections[adb_shell].pages, not a tab row of this file.
     */
    val FIELD_HOME: Map<String, String> = mapOf(
        "Status" to Decl.PAGE_ADB_STATUS, "Tester" to Decl.PAGE_ADB_ALL, "Logs" to Decl.PAGE_ADB_ALL, "ADB Shell" to Decl.PAGE_ADB_ALL,
    )

    /** #1723 the ADB shell status table on Home (AdbHomeStatus.kt); every row opens its ADB Shell sub page. */
    @Composable
    fun HomeStatus(tick: Int, open: (String, String?) -> Unit, extra: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {}) = AdbHomeStatus(tick, open, extra)

    /** #1834 Home's Summary and ADB table read the REAL state: the keep-alive switch and an echo round trip. Blocking: call off the main thread. */
    fun readLive(ctx: Context): com.diegonmarcos.cloudwebserver.ui.AdbLive = com.diegonmarcos.cloudwebserver.ui.AdbLive(
        true, runCatching { com.diegonmarcos.superapp.adbdebug.ChannelAutoArm.enabled(ctx) }.getOrNull(),
        runCatching { com.diegonmarcos.superapp.adbdebug.ChannelReader.read(ctx).serverRunning }.getOrNull(),
    )
    /** The ADB keep-alive switch (ChannelAutoArm: the one the lib page draws and every implicit start asks, #1834). */
    fun setKeepAlive(ctx: Context, on: Boolean) = com.diegonmarcos.superapp.adbdebug.ChannelAutoArm.set(ctx, on)
    /** The lib's own Start / Stop server actions (explicit, so they may offer pairing). Blocking. */
    fun startServer(ctx: Context): String = com.diegonmarcos.superapp.adbdebug.ChannelOps.startServer(ctx, false)
    fun stopServer(ctx: Context): String = com.diegonmarcos.superapp.adbdebug.ChannelOps.stopServer(ctx)

    /** The owner page body for one declared sub page (ids = build.json::ui, Decl.PAGE_ADB_*). An unknown id falls back to Status. */
    @Composable
    fun Page(page: String? = null) {
        val c = ThemeBackground.colors(true)
        // the cockpit composables draw with the fleet UI kit: it needs its palette, built from this app's fleet theme
        val palette = remember(c) {
            KitPalette(
                surface = c.top, surfaceSelected = c.ink.copy(alpha = 0.10f).compositeOver(c.top), textPrimary = c.ink, textSecondary = c.inkDim,
                accent = c.accent, hairline = Tone.Line, tileInk = c.top, ok = Tone.Up, warn = Tone.Warn, bad = Tone.Bad,
            )
        }
        Box(Modifier.fillMaxSize().testTag("c3_adb_owner_root")) {
            when (page) {
                Decl.PAGE_ADB_TERM -> CloudKitTheme(palette) { AdbTermTab() }
                Decl.PAGE_ADB_PERMS -> CloudKitTheme(palette) { AdbPermsTab() }
                Decl.PAGE_ADB_SERVER -> AdbServerTab()
                Decl.PAGE_ADB_ALL -> AllTab(palette)
                else -> AdbStatusPage()
            }
        }
    }

    /** #1743 the Tester ("Run id -u") and the channel log, moved off Status (explicit removal of the bottom panel): reachable here only. */
    @Composable
    private fun TesterAndLogs() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var tester by remember { mutableStateOf("") }
        var logText by remember { mutableStateOf(ChannelLog.shared.asText()) }
        DisposableEffect(Unit) {
            val l: () -> Unit = { logText = ChannelLog.shared.asText() }
            ChannelLog.shared.addListener(l)
            onDispose { ChannelLog.shared.removeListener(l) }
        }
        val ready by AdbReadiness.state.collectAsState()
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("c3_adb_owner"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            // the previous Status card (readiness line, Connect, Re-check) kept whole; Status's own Connect is the chain runner
            Card("Status", "c3_adb_owner_status") {
                Note(ready.linkText())
                Acts {
                    Act("Connect", "c3_adb_owner_connect") { AdbReadiness.connect(ctx) {} }
                    Act("Re-check", "c3_adb_owner_recheck") { scope.launch { withContext(Dispatchers.IO) { AdbReadiness.check(ctx, fresh = true) } } }
                }
            }
            Card("Tester", "c3_adb_owner_tester") {
                Acts {
                    Act("Run id -u", "c3_adb_owner_test") {
                        scope.launch {
                            tester = withContext(Dispatchers.IO) {
                                runCatching {
                                    when (val r = ShellBroker.exec(ctx, ShellBroker.SELF, "id -u")) {
                                        is ShellResult.Ok -> "exit ${r.exitCode} via ${r.source}: ${r.stdout.trim()}"
                                        else -> r.toString()
                                    }
                                }.getOrElse { "tester failed (${it.javaClass.simpleName})" }
                            }
                            AdbReadiness.check(ctx)
                        }
                    }
                }
                if (tester.isNotEmpty()) Note(tester)
            }
            Card("Logs", "c3_adb_owner_logs") { Note(logText.ifBlank { "no channel log yet" }) }
        }
    }

    @Composable
    private fun AllTab(palette: KitPalette) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // #1743 every part that scrolls by itself gets a bounded height: a scroller inside this scroller with unbounded height crashes Compose
            Box(Modifier.fillMaxWidth().height(560.dp)) { CloudKitTheme(palette) { AdbShellBrokerPage() } }
            // #1743 the previous Status page's full cockpit (mode selector, per-mode cards, keep-alive, logs) lives here now (it scrolls itself)
            Box(Modifier.fillMaxWidth().height(640.dp)) { CloudKitTheme(palette) { AdbStatusTab() } }
            TesterAndLogs()
            // the old Server tab's ADB Shell card: the lib's full page (pairing, modes, connect, server buttons, logs, needs, setup, keep-alive)
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).testTag("c3_adb_owner_all")) {
                Card("ADB Shell", "c3_adb_owner_page") { Column(Modifier.fillMaxWidth()) { AdbShellPage() } }
            }
        }
    }
}

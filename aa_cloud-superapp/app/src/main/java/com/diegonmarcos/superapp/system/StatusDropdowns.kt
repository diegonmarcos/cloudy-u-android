package com.diegonmarcos.superapp.system

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.battery.BatteryRepository
import com.diegonmarcos.superapp.launcher.DropdownGroup
import com.diegonmarcos.superapp.launcher.StatusBarDecl
import com.diegonmarcos.superapp.uikit.KitStoreRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The right-hand status-bar dropdowns' grouped body (build.json::home_status_bar.dropdowns): one block per declared group, headed
 * by the strip icon it belongs to (R / S / C / F / BAT), each block's lines read by [SystemGroups]. Sizes come from the ui-kit's
 * Store row tokens (no dp/sp literal but the 1 dp rule). App groups refresh every [REFRESH_MS] while open (the Graphics FPS is
 * live); ADB groups are read once per open and say "needs ADB" with no channel.
 */
object StatusDropdowns {
    const val REFRESH_MS = 1_000L
    const val SYSTEM = "right_system"
    const val BATTERY = "right_battery"
    /** A group of this source is a header: its rule and title, no lines. A MACRO header (id starts with "m"; Device Profile Scan, Profiling Trackers) is set apart; a tracker header (id "trNN_...") labels one tracker. */
    const val HEADER = "header"
    fun isMacro(g: DropdownGroup) = g.source == HEADER && g.id.startsWith("m")

    private val INK = Color.White
    private val DIM = Color(0xAAFFFFFF)
    private val ACCENT = Color(0xFF7FB8FF)
    private val LABEL_W = KitStoreRow.step(104)

    @Composable
    private fun Cap(text: String, modifier: Modifier = Modifier, color: Color = INK, bold: Boolean = false) =
        Text(text, modifier, color = color, fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else null)

    /** The declared groups of dropdown [id] (empty when the build declares none). */
    fun groups(id: String): List<DropdownGroup> = StatusBarDecl.declared.dropdown(id)?.groups.orEmpty()

    private fun appLines(ctx: Context, g: DropdownGroup): List<DdLine> = when (g.id) {
        "memory" -> SystemGroups.memory(ctx)
        "storage" -> SystemGroups.storage(ctx)
        "cpu" -> SystemGroups.cpu(ctx)
        "graphics" -> SystemGroups.graphics(ctx)
        "device" -> SystemGroups.device(ctx)
        "soc" -> SystemGroups.soc(ctx)
        "gpu" -> SystemGroups.gpu(ctx)
        "display" -> SystemGroups.display(ctx)
        "capacity" -> SystemGroups.capacity(ctx)
        "battery_live" -> SystemGroups.battery(ctx)
        else -> emptyList()
    }

    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The "Name" of a batterystats uid ("u0a143" -> the app's label); cached per uid name (the dump has ~80 rows). */
    private fun appName(ctx: Context): (String) -> String = { n ->
        nameCache.getOrPut(n) {
            DumpsysParsers.uidOf(n)?.let { runCatching { BatteryRepository.appLabel(ctx, it).second }.getOrNull() } ?: n
        }
    }

    @Composable
    private fun GroupBlock(ctx: Context, g: DropdownGroup, tagPrefix: String) {
        val short = StatusBarDecl.declared.elements.firstOrNull { it.id == g.element }?.short?.ifBlank { null }
        // #1678 one readiness source: the rows re-read when its verdict flips (a flow, no polling) and on every open.
        val ready by AdbReadiness.state.collectAsState()
        val lines by produceState<List<DdLine>?>(null, g.id, ready.usable) {
            val probeText = if (g.probe != null) withContext(Dispatchers.IO) { SystemGroups.adb(ctx, g.probe) } else null
            if (g.source == HEADER) {
                value = emptyList()
            } else if (g.id == "suspects") {
                value = withContext(Dispatchers.IO) { SystemGroups.suspectLines(ctx, g.rows) }
                    ?.ifEmpty { listOf(DdLine("Status", "no suspects ranked")) } ?: listOf(DdLine("Status", SystemGroups.NEEDS_ADB))
            } else if (g.source == "adb") {
                value = withContext(Dispatchers.IO) { SystemGroups.adbLines(g.id, probeText, g.rows, appName(ctx)) }
            } else {
                // an app group may append the lines of a probe (CPU: top processes, Graphics: gfxinfo)
                val what = when (g.probe) { "top" -> "Top processes"; "gfxinfo" -> "Frame stats"; else -> "Probe" }
                val thermal = when {
                    g.probe == null -> emptyList()
                    probeText == null -> listOf(DdLine(what, SystemGroups.NEEDS_ADB))
                    else -> (when (g.probe) {
                        "thermal" -> DumpsysParsers.thermalFull(probeText).take(g.rows)
                        "top" -> DumpsysParsers.topLines(probeText, g.rows)
                        "gfxinfo" -> DumpsysParsers.gfxinfoLines(probeText)
                        else -> emptyList()
                    }).ifEmpty { listOf(DdLine(what, "no data in the dump")) }
                }
                while (true) {
                    value = withContext(Dispatchers.IO) { appLines(ctx, g) } + thermal
                    delay(REFRESH_MS)
                }
            }
        }
        val macro = isMacro(g)
        Column(Modifier.fillMaxWidth().padding(top = if (macro) KitStoreRow.padV + KitStoreRow.padV else KitStoreRow.padV).testTag("$tagPrefix:group:${g.id}")) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (macro) ACCENT else KitStoreRow.RULE))
            Row(Modifier.padding(top = KitStoreRow.tight, bottom = KitStoreRow.tight)) {
                if (short != null) Cap(short, Modifier.padding(end = KitStoreRow.gap).testTag("$tagPrefix:icon:${g.id}"), ACCENT, bold = true)
                Cap(g.label, Modifier.testTag("$tagPrefix:title:${g.id}"), if (g.source == HEADER) ACCENT else INK, bold = true)
            }
            val ls = lines
            if (g.source == HEADER) Unit
            else if (ls == null) Cap("reading...", color = DIM)
            else {
                // #1638 Last 3h Memory Usage is drawn as a graph: one bar per process, scaled to the heaviest 3 h average PSS
                val bars = if (g.id == "memory_3h") DumpsysParsers.memory3hBars(ls) else emptyList()
                ls.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.step(1)).testTag("$tagPrefix:row:${g.id}:$i")) {
                        Cap(l.label, Modifier.width(LABEL_W), DIM)
                        if (l.value == SystemGroups.NEEDS_ADB) {
                            // #1678 a needs-ADB row is a link: what is missing, and a tap opens ADB Shell and starts Connect (auto-fix).
                            val fail = g.probe?.let { SystemGroups.probeFailure(it) }
                            Cap(if (ready.usable && fail != null) "$fail - tap to retry" else ready.linkText(),
                                Modifier.weight(1f).testTag("$tagPrefix:adblink:${g.id}:$i").clickable {
                                    if (ready.usable && fail != null) AdbReadiness.connect(ctx) {} else AdbReadiness.connect(ctx) { AdbReadiness.openAdbShell(ctx) }
                                }, ACCENT)
                        } else Cap(l.value, Modifier.weight(1f), if (l.value.startsWith("needs ADB")) ACCENT else INK)
                    }
                    bars.getOrNull(i)?.let { f ->
                        Box(Modifier.fillMaxWidth().height(KitStoreRow.step(4)).background(KitStoreRow.RULE).testTag("$tagPrefix:bar:${g.id}:$i")) {
                            Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.01f)).height(KitStoreRow.step(4)).background(ACCENT))
                        }
                    }
                }
            }
        }
    }

    /** Every declared group of the System dropdown, in declared order. */
    @Composable
    fun SystemGroupsView(ctx: Context) {
        for (g in groups(SYSTEM)) GroupBlock(ctx, g, "sysdd")
    }

    /** The two since-last-charge sections that follow the battery popup's own (Usage Infos, then Per-App Usage). */
    @Composable
    fun BatterySinceCharge(ctx: Context) {
        for (g in groups(BATTERY)) GroupBlock(ctx, g, "batdd")
    }
}

package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object XrayTags {
    const val ROOT = "xray_screen"
    fun tab(id: String) = "xray_tab_$id"
    fun run(id: String) = "xray_run_$id"
    fun state(id: String) = "xray_state_$id"
    const val SUITES = "xray_suites"
}

/** One thing that can be X-rayed: its id (the validated subject), its title, and what reading its report gave. */
data class XrayItem(val id: String, val title: String, val sub: String, val state: XrayModel.State, val runnable: Boolean = true)

@Composable
internal fun XChip(tag: String, label: String, on: Boolean, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitStoreRow.tight)
    Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.caption,
        modifier = Modifier.padding(end = KitStoreRow.tight).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag(tag))
}

@Composable
internal fun XLine(text: String, color: androidx.compose.ui.graphics.Color = LocalKitPalette.current.textSecondary, mono: Boolean = false) {
    Text(text, color = color, fontSize = KitStoreRow.caption, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 1.dp))
}

@Composable
private fun ReportBody(r: HealthModel.Report) {
    val p = LocalKitPalette.current
    XLine("${r.status} · ${r.engine} ${r.engineVersion} · run ${r.runId} · ${r.runner} · ${r.vantage}" + if (r.subject.isNotBlank()) " · ${r.subject}" else "", mono = true)
    if (r.summary.isNotEmpty()) XLine(r.summary.entries.joinToString("  ") { "${it.key} ${it.value}" }, mono = true)
    if (r.findings.isNotEmpty()) {
        XLine("FINDINGS (ranked)", color = p.accent)
        XrayModel.ranked(r).forEach { f -> XLine("${f.rank} ${f.severity} [${f.layer}] ${f.title}" + if (f.explains > 0) "   explains ${f.explains}" else "", color = p.textPrimary) ; if (f.detail.isNotBlank()) XLine("   ${f.detail}") }
    }
    r.sections.forEach { s ->
        XLine("${s.title}   ${s.status}", color = p.accent)
        s.rows.forEach { XLine("  ${it.key}: ${it.value}", mono = true) }
    }
    r.missing.forEach { (i, t) -> XLine("not served ($i): $t") }
    if (r.redactions > 0) XLine("${r.redactions} values redacted before they left the VM")
}

@Composable
private fun ItemRow(i: XrayItem, kind: String, onRun: () -> Unit, runnable: Boolean) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(XrayTags.state(i.id))) {
        Row(Modifier.padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(i.title, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (runnable && i.runnable) XChip(XrayTags.run(i.id), "X-ray", false, onRun)
        }
        if (i.sub.isNotBlank()) XLine(i.sub)
        if (i.state is XrayModel.State.Device) NoTokenLine(XrayModel.say(i.state), "xray_notoken_${i.id}")
        else XLine(XrayModel.say(i.state), color = when (i.state) { is XrayModel.State.Got -> KitStoreRow.UP; XrayModel.State.Idle -> p.textSecondary; else -> KitStoreRow.UPDATE })
        (i.state as? XrayModel.State.Got)?.let { ReportBody(it.report) }
    }
}

/**
 * X-ray C3: C3 full, the VMs, the services, and the old Tests suites (kept, [suites] draws them). [runNote] is the API's own answer to the last
 * run request, printed as it came; a route that is not served yet says so instead of showing nothing.
 */
@Composable
fun XrayScreen(full: XrayItem, vms: List<XrayItem>, services: List<XrayItem>, runNote: String, onRun: (String, String) -> Unit, suites: @Composable () -> Unit, initialTab: String = "full",
    engines: @Composable () -> Unit = {}, fedBy: (String) -> List<String> = { emptyList() }, devices: List<XrayItem> = emptyList()) {
    var tab by remember { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize().testTag(XrayTags.ROOT)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
            XrayModel.TABS.forEach { (id, label) ->
                val n = when (id) { "vms" -> " ${vms.size + devices.size}"; "services" -> " ${services.size}"; else -> "" }
                XChip(XrayTags.tab(id), label + n, tab == id) { tab = id }
            }
        }
        if (runNote.isNotBlank()) XLine(runNote, color = KitStoreRow.UPDATE)
        // the Suites body (the old Tests map page) scrolls itself: it sits outside the tab scroll (a scroll inside a scroll crashes)
        if (tab == "suites") Column(Modifier.fillMaxWidth().weight(1f).testTag(XrayTags.SUITES)) { suites() }
        else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                "engines" -> Column(Modifier.testTag(EngineTags.ROOT)) { engines() }
                "full" -> {
                    fedBy("full").forEach { XLine("fed by: $it", mono = true) }
                    XLine("X-ray C3 full = the X-ray of each VM (hub last), then of each service, the layer report and the diff against the previous full run.")
                    ItemRow(full, "xray-full", { onRun("xray-full", "fleet") }, true)
                }
                "vms" -> { fedBy("vms").forEach { XLine("fed by: $it", mono = true) }; vms.forEach { ItemRow(it, "xray-vm", { onRun("xray-vm", it.id) }, true) }
                    if (devices.isNotEmpty()) {
                        XLine("DEVICES (X-Ray Device, one report per phone or laptop)", color = LocalKitPalette.current.accent)
                        devices.forEach { ItemRow(it, "xray-device", { }, false) }
                    }
                }
                "services" -> { fedBy("services").forEach { XLine("fed by: $it", mono = true) }; services.forEach { ItemRow(it, "xray-service", { onRun("xray-service", it.id) }, true) } }
            }
        }
    }
}

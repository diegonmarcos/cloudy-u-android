package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Test tags of the Health debugger (the old row tag `c3:health:<name>` is kept on the Lights rows). */
object HealthTags {
    const val ROOT = "health_screen"
    const val BANNER = "health_root_banner"
    const val PROBE = "health_probe_now"
    const val VANTAGE = "health_vantage"
    const val NOTES = "health_notes"
    fun tab(id: String) = "health_tab_$id"
    fun layer(id: String) = "health_layer_$id"
    fun row(id: String) = "health_row_$id"
    fun scope(s: String) = "health_scope_$s"
    fun lightsRow(name: String) = "c3:health:$name"
}

/** Everything the screen draws: the checks (with the engine's attribution), the engine's analysis, and every note about what could not be read. */
data class HealthUi(
    val checks: List<HealthModel.Check>,
    val analysis: HealthModel.Analysis?,
    val notes: List<String>,
    val busy: Boolean = false,
    val probedAt: String = "",
)

private val HEALTH_TABS = listOf("overview" to "Overview", "lights" to "Lights", "containers" to "Containers", "vms" to "VMs", "mesh" to "Network / Mesh", "more" to "More layers", "profile" to "Profile", "cause" to "Cause")

@Composable
private fun inkOf(s: HealthModel.St): Color {
    val p = LocalKitPalette.current
    return when (s) {
        HealthModel.St.OK -> KitStoreRow.UP
        HealthModel.St.DOWN -> KitStoreRow.BAD
        HealthModel.St.WARN -> KitStoreRow.UPDATE
        else -> p.textSecondary
    }
}

@Composable
private fun HChip(tag: String, label: String, on: Boolean, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitStoreRow.tight)
    Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.caption,
        modifier = Modifier.padding(end = KitStoreRow.tight).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag(tag))
}

@Composable
private fun HTitle(text: String) {
    Text(text, color = LocalKitPalette.current.accent, fontSize = KitStoreRow.meta,
        modifier = Modifier.padding(start = KitStoreRow.padH, top = KitStoreRow.gap, bottom = KitStoreRow.tight))
}

@Composable
private fun HLine(text: String, color: Color = LocalKitPalette.current.textSecondary, mono: Boolean = false, tag: String? = null) {
    Text(text, color = color, fontSize = KitStoreRow.caption, fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 1.dp).then(if (tag == null) Modifier else Modifier.testTag(tag)))
}

private fun phaseColor(k: String, p: com.diegonmarcos.superapp.uikit.KitPalette) = when (k) { "dns" -> p.accent; "tcp" -> KitStoreRow.UP; "tls" -> KitStoreRow.UPDATE; else -> p.textPrimary }

/** The profiler bar of one probe: dns, tcp, tls, http in order, each as wide as its share of the total, with the times as text. */
@Composable
fun PhaseBar(c: HealthModel.Check) {
    val parts = HealthModel.bar(c)
    if (parts.isEmpty()) return
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = 2.dp).height(6.dp)) {
        parts.forEach { (k, share) -> Box(Modifier.weight(share.coerceAtLeast(0.02f)).height(6.dp).background(phaseColor(k, p))) }
    }
    HLine(c.phases.list().filter { it.second != null }.joinToString("  ") { "${it.first} ${it.second}" } + "  = ${c.phases.total} ms" + (c.code?.let { "  code $it" } ?: ""), mono = true)
}

/** One check row: glyph and word, name, reason, time (only for a status that carries a number), the phase bar, who explains it. */
@Composable
fun CheckRow(c: HealthModel.Check, showFacts: Boolean = false, onTap: (() -> Unit)? = null, tag: String = HealthTags.row(c.id)) {
    val p = LocalKitPalette.current
    val tap = if (onTap == null) Modifier else Modifier.clickable { onTap() }
    Column(Modifier.fillMaxWidth().testTag(tag).then(tap).padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${HealthModel.glyph(c.status)} ${HealthModel.word(c.status)}", color = inkOf(c.status), fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
            Text(c.name, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (c.timeText.isNotBlank()) Text(c.timeText, color = p.textSecondary, fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace)
        }
        if (c.details.isNotBlank()) HLine(c.details)
        PhaseBar(c)
        if (showFacts) c.facts.filter { it.second.isNotBlank() && it.first != "scope" }.chunked(2).forEach { pair -> HLine(pair.joinToString("   ") { "${it.first}: ${it.second}" }, mono = true) }
        c.explainedBy?.let { HLine("caused by $it", color = KitStoreRow.UPDATE) }
    }
}

@Composable
private fun Overview(ui: HealthUi, onTab: (String) -> Unit) {
    val p = LocalKitPalette.current
    val checks = ui.checks
    val roots = ui.analysis?.roots.orEmpty()
    HTitle("This phone")
    checks.filter { it.layer == "vantage" }.forEach { CheckRow(it) }
    if (roots.isNotEmpty()) {
        roots.forEach { r ->
            val c = checks.firstOrNull { it.id == r }
            Column(Modifier.fillMaxWidth().padding(KitStoreRow.padH, 3.dp).background(KitStoreRow.BAD.copy(alpha = 0.12f)).padding(6.dp).testTag(HealthTags.BANNER)) {
                Text("ROOT CAUSE  ${c?.name ?: r}", color = KitStoreRow.BAD, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold)
                Text((c?.details.orEmpty()) + "   explains ${HealthModel.impact(checks, r).size} checks in ${HealthModel.impact(checks, r).map { it.layer }.distinct().size} layers", color = p.textPrimary, fontSize = KitStoreRow.caption)
            }
        }
    } else {
        val ok = checks.count { it.status == HealthModel.St.OK }
        val ns = checks.count { it.status == HealthModel.St.NOT_SERVED || it.status == HealthModel.St.BLIND || it.status == HealthModel.St.PENDING }
        HLine("No root cause: $ok checks ok, $ns not read or not served.", color = KitStoreRow.UP, tag = HealthTags.BANNER)
    }
    HTitle("Layers   ok  warn  down  caused  blocked  not served")
    val lights = ui.analysis?.lights.orEmpty().associateBy { it.layer }
    HealthModel.LAYERS.forEach { l ->
        val li = lights[l.id]
        val own = checks.count { it.layer == l.id }
        Row(Modifier.fillMaxWidth().clickable { onTab(tabOfLayer(l.id)) }.padding(horizontal = KitStoreRow.padH, vertical = 3.dp).testTag(HealthTags.layer(l.id)), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (li == null) "." else HealthModel.lightGlyph(li.light), color = if (li?.light == "green") KitStoreRow.UP else if (li?.light == "red" || li?.light == "red_hollow") KitStoreRow.BAD else p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = KitStoreRow.body, modifier = Modifier.padding(end = 8.dp))
            Text(l.label, color = p.textPrimary, fontSize = KitStoreRow.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (li == null || own == 0) "no checks" else "${li.ok}  ${li.warn}  ${li.down}  ${li.caused}  ${li.blocked}  ${li.notServed}", color = p.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        li?.let { HLine(HealthModel.lightWord(it.light)) }
    }
}

private fun tabOfLayer(id: String) = when (id) { "lights", "containers", "vms", "mesh" -> id; else -> "more" }

/** The Lights: the old Health page's public and private services, every field of each row, a scope switch and the phased probe per row. */
@Composable
private fun Lights(ui: HealthUi, onOpenUrl: (String) -> Unit) {
    var scope by remember { mutableStateOf("All") }
    val all = ui.checks.filter { it.layer == "lights" }
    val pub = HealthModel.scoped(all, "Public"); val priv = HealthModel.scoped(all, "Private")
    HLine("${pub.size} public · ${priv.size} private", tag = "health_lights_counts")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
        listOf("All" to all.size, "Public" to pub.size, "Private" to priv.size).forEach { (s, n) -> HChip(HealthTags.scope(s), "$s $n", scope == s) { scope = s } }
    }
    HealthModel.worstFirst(HealthModel.scoped(all, scope)).forEach { c ->
        val url = c.facts.firstOrNull { it.first == "public_url" }?.second.orEmpty()
        HLine(c.facts.firstOrNull { it.first == "scope" }?.second.orEmpty(), color = LocalKitPalette.current.accent)
        CheckRow(c, showFacts = true, onTap = if (url.isBlank()) null else ({ onOpenUrl(url) }), tag = HealthTags.lightsRow(c.name))
    }
    if (all.isEmpty()) HLine("No declared service.")
}

@Composable
private fun LayerList(ui: HealthUi, layers: List<String>) {
    layers.forEach { id ->
        val l = HealthModel.LAYERS.first { it.id == id }
        val li = ui.analysis?.lights.orEmpty().firstOrNull { it.layer == id }
        HTitle(l.label + (li?.let { "   ${HealthModel.lightGlyph(it.light)} ${HealthModel.lightWord(it.light)}" } ?: ""))
        val cs = ui.checks.filter { it.layer == id }
        if (cs.isEmpty()) HLine("no checks in this layer") else HealthModel.worstFirst(cs).forEach { CheckRow(it) }
    }
}

@Composable
private fun Profile(ui: HealthUi) {
    val timed = ui.checks.filter { HealthModel.bar(it).isNotEmpty() }.sortedByDescending { it.phases.total }
    HTitle("Phased timings, slowest first (${timed.size})")
    if (timed.isEmpty()) HLine("No timed probe yet: press Probe now. Only up, degraded and down probes carry a time.")
    timed.forEach { CheckRow(it) }
}

@Composable
private fun Cause(ui: HealthUi) {
    val roots = ui.analysis?.roots.orEmpty()
    HTitle("Root cause and impact")
    if (roots.isEmpty()) HLine("No check is a root cause: nothing is down with no failing dependency.", color = KitStoreRow.UP)
    roots.forEach { r ->
        val c = ui.checks.firstOrNull { it.id == r } ?: return@forEach
        CheckRow(c)
        val imp = HealthModel.impact(ui.checks, r)
        HLine("explains ${imp.size} checks:", color = KitStoreRow.UPDATE)
        imp.take(40).forEach { i -> HLine("  ${HealthModel.glyph(i.status)} ${i.layer} / ${i.name}  <- " + HealthModel.chain(ui.checks, i.id).drop(1).joinToString(" <- ") { it.name }, mono = true) }
        if (imp.size > 40) HLine("  and ${imp.size - 40} more")
    }
}

/** The Health debugger: overview with the root cause and the layer lights, the Lights, Containers, VMs, Mesh, the other layers, the profiler and the cause view. */
@Composable
fun HealthScreen(ui: HealthUi, onProbe: () -> Unit, onOpenUrl: (String) -> Unit, initialTab: String = "overview") {
    var tab by remember { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize().testTag(HealthTags.ROOT)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
            HChip(HealthTags.PROBE, if (ui.busy) "Probing..." else "Probe now", true) { if (!ui.busy) onProbe() }
            HEALTH_TABS.forEach { (id, label) -> HChip(HealthTags.tab(id), label, tab == id) { tab = id } }
        }
        if (ui.probedAt.isNotBlank()) HLine("probed ${ui.probedAt}")
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                "overview" -> Overview(ui) { tab = it }
                "lights" -> Lights(ui, onOpenUrl)
                "containers" -> LayerList(ui, listOf("containers"))
                "vms" -> LayerList(ui, listOf("vms"))
                "mesh" -> LayerList(ui, listOf("mesh"))
                "more" -> LayerList(ui, HealthModel.LAYERS.map { it.id }.filter { it !in listOf("lights", "containers", "vms", "mesh", "vantage") })
                "profile" -> Profile(ui)
                "cause" -> Cause(ui)
            }
            if (ui.notes.isNotEmpty()) {
                HTitle("Notes")
                ui.notes.forEach { HLine(it, tag = HealthTags.NOTES) }
            }
        }
    }
}

package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1900 The Prober section's matrix: groups A..F, one row per probe. Each row reads route profile -> probe result -> status:
 * the status light and word come from the probe alone, the route line is what was profiled before the probe was sent. Rows
 * stream in as probes finish (queued rows show their profile at once), the Run / Cancel buttons never disable another control.
 * Tap a row for the whole detail; failing rows always show theirs.
 */
@Composable
internal fun MeshProberRow(store: MeshStore, tag: String) {
    val page = store.decl.page("status")
    val rows = store.prober
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(tag)) {
        Row(Modifier.padding(bottom = MeshDensity.dp(MeshDensity.S4)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)),
            verticalAlignment = Alignment.CenterVertically) {
            page?.control("status_prober_run")?.let { c -> MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)), emphasised = store.probing == 0) }
            page?.control("status_prober_cancel")?.let { c -> MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)), enabled = store.probing > 0) }
            MText(if (store.probing > 0) "${rows.count { it.done }} of ${rows.size} done…" else if (rows.isEmpty()) "not run" else "${rows.count { it.ok == true }} of ${rows.size} pass · ${MeshFormat.clock(store.proberAtMs)}",
                Modifier.testTag("mesh:prober:progress"), size = MeshDensity.T_META, mono = true, muted = true)
        }
        Reason(declLabel("row", "prober_how", "Every row is profiled first (interface, DNS, route, mesh peer, fallback), then probed; the status is the probe's. Probes run in the background: nothing here blocks Disconnect."))
        for (g in MeshProber.GROUPS) {
            val gr = rows.filter { it.spec.group == g }
            if (gr.isEmpty()) continue
            MText("${MeshProber.LETTERS[g]}  ${declLabel("pgroup", g, MeshProber.TITLES.getValue(g))} · ${MeshProber.groupLine(rows, g)}", Modifier.padding(top = MeshDensity.dp(MeshDensity.S4)).testTag("mesh:prober:group:$g"),
                size = MeshDensity.T_META, bold = true)
            for (r in gr) {
                val key = "$g/${r.spec.id}"
                Row(Modifier.fillMaxWidth().testTag("mesh:prober:$key").clickable { open = if (open == key) null else key }
                    .padding(vertical = MeshDensity.dp(MeshDensity.S2)),
                    horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.Top) {
                    Light(stateOf(r.ok), r.status)
                    Column(Modifier.weight(1f)) {
                        MText("${r.spec.label} · ${r.status}" + if (r.done) " · ${r.ms} ms · ${r.path.ifBlank { "-" }}" else "", size = MeshDensity.T_META, bold = true)
                        MText(r.route.line(), size = MeshDensity.T_MICRO, mono = true, muted = true, maxLines = if (open == key) Int.MAX_VALUE else 2)
                        if (r.done && (r.ok != true || open == key)) MText(r.detail, size = MeshDensity.T_MICRO, mono = true, maxLines = if (open == key) Int.MAX_VALUE else 3)
                    }
                }
            }
        }
    }
}

/** #1900 The title of one of the Status page's three sections (Status / Profiler / Prober). */
@Composable
internal fun MeshSectionTitle(id: String, label: String) {
    val p = LocalKitPalette.current
    MText(declLabel("sec", id, label).uppercase(), Modifier.fillMaxWidth().padding(top = MeshDensity.dp(MeshDensity.S12)).testTag("mesh:section:$id"),
        size = MeshDensity.T_HEAD, bold = true, color = p.accent)
}

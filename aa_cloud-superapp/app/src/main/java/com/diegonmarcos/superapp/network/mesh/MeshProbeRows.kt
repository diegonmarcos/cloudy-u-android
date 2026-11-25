package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1797 The rows Status gained on top of everything it already drew (rule 16: add and reorganise,
 * never remove):
 *  - [MeshLightsRow] the light indicators at the top, one summary line each, every one read from a
 *    real state (the engine's handshakes, the DNS servers Android holds, the last mesh probe, the last
 *    Reachability run). Nothing is a proxy for something else.
 *  - [MeshTableRow] one row per declared mesh, every cell a Cloud C3 probe; tap a cell for its detail.
 *  - [MeshPeersRow] every peer field (handshake, health, endpoint, allowed IPs, rx/tx bytes and rates,
 *    latency, keepalive, public key) where the page had only some of them.
 *  - [MeshProfilerRow] Cloud C3's profiler: every step by group, plus Copy / Export of the report.
 *  - [MeshStatusLogRow] the complete Log (every entry, level and source) on Status too, behind a switch.
 * The probes are Cloud C3's ([C3MeshProbe], libs:fleetconfigs-xray); this file only renders them.
 */

/** ON / OFF / UNKNOWN from a probe verdict: true = passed, false = failed, null = not measured. */
internal fun stateOf(ok: Boolean?): StatusLight.State = when (ok) {
    true -> StatusLight.State.ON
    false -> StatusLight.State.OFF
    null -> StatusLight.State.UNKNOWN
}

/** One light line: what, its verdict word, and the reading behind it. */
data class LightLine(val id: String, val ok: Boolean?, val word: String, val detail: String)

/**
 * #1797 The top lights, pure so the parity test reads exactly what the page draws. Each verdict comes
 * from the state it names: the tunnel from the engine, handshakes from each peer's age, DNS from the
 * servers Android holds against the fleet's, names / IPs from the last Cloud C3 probe, Reachability from
 * its last run. A light that has nothing measured yet says so (null) instead of borrowing a verdict.
 */
fun lightLines(
    s: MeshSnapshot,
    profile: C3MeshProbe.Report?,
    reach: List<MeshReach.Result>,
    probes: List<ProbeLine> = emptyList(),
    probesAtMs: Long = 0L,
    transport: TransportView? = null,
): List<LightLine> {
    val out = mutableListOf<LightLine>()
    out += LightLine("tunnel", when { !s.engineInstalled -> null; s.up -> s.light == Light.UP; else -> false },
        when { !s.engineInstalled -> "unknown"; !s.up -> "down"; s.light == Light.UP -> "up"; else -> "up, no fresh handshake" },
        "${s.cfg.tunnelName} · ${s.cfg.activeProfile.substringAfterLast('/').ifBlank { "no profile" }}")
    // #1900 one verdict on a handshake ([MeshProber.handshakeOk]) for the lights, the profiler and the Prober: "fresh" is Health.FRESH only
    val fresh = s.peers.count { MeshProber.handshakeOk(it.health) == true }
    out += LightLine("handshake", if (!s.up) (if (s.engineInstalled) false else null) else fresh == s.peers.size && s.peers.isNotEmpty(),
        "$fresh of ${s.peers.size} fresh",
        s.peers.joinToString(" · ") { "${it.cfg.name.ifBlank { MeshFormat.shortKey(it.cfg.publicKey) }} ${MeshFormat.age(it.handshakeAgeS)} ${it.health}" }.ifBlank { "no peers" })
    val held = MeshStore.ipLiterals(s.cfg.dnsInEffect)
    val fleet = MeshStore.ipLiterals(s.cfg.dns)
    val stray = held.filter { it !in fleet }
    out += LightLine("dns", when { !s.up -> null; held.isEmpty() -> false; else -> stray.isEmpty() },
        when { !s.up -> "mesh down: the preset applies"; held.isEmpty() -> "no DNS server"; stray.isEmpty() -> "fleet resolvers only"; else -> "public resolvers beside the fleet" },
        "Android holds ${held.joinToString(", ").ifEmpty { "none" }}; fleet ${fleet.joinToString(", ").ifEmpty { "none" }}" +
            if (s.up && stray.isNotEmpty()) "; ${stray.joinToString(", ")} answer mesh names NXDOMAIN, reconnect to apply the fleet-only list" else "")
    fun fromProfile(id: String, cols: List<String>, what: String) {
        if (profile == null) { out += LightLine(id, null, "not probed", "tap Probe meshes"); return }
        val cells = profile.rows.flatMap { r -> cols.map { r.target.label to r.cell(it) } }
        val bad = cells.filter { it.second.ok == false }
        val ok: Boolean? = when { bad.isNotEmpty() -> false; cells.any { it.second.ok == true } -> true; else -> null }
        out += LightLine(id, ok, if (bad.isEmpty()) "${profile.rows.size} of ${profile.rows.size} meshes" else "${bad.map { it.first }.distinct().size} mesh(es) fail",
            (if (bad.isEmpty()) "$what: " + cells.joinToString(" · ") { "${it.first} ${it.second.text}" } else bad.joinToString(" · ") { "${it.first}: ${it.second.detail}" }) +
                " · probed ${MeshFormat.clock(profile.atMs)}")
    }
    fromProfile("names", listOf("system"), "Android resolves")
    fromProfile("ips", listOf("hub", "service"), "hub and service answer")
    out += LightLine("reach", if (reach.isEmpty()) null else reach.all { it.ok },
        if (reach.isEmpty()) "not run" else "${reach.count { it.ok }} of ${reach.size} reachable",
        reach.joinToString(" · ") { r -> r.host + " " + (r.steps.firstOrNull { !it.ok }?.let { "${it.id} FAIL ${it.detail}" } ?: "PASS") }.ifBlank { "tap Run again" })
    // #1797 the fallback ladder: the active path, whether a fallback is ready, then one light per Test fallbacks step
    // (the same ProbeLines the Path section lists, from the same run; nothing re-measured or inferred here).
    val path = transport?.path.orEmpty()
    out += LightLine("path", when (path) { "" -> null; "none" -> false; else -> true },
        when (path) { "" -> "unknown"; "direct" -> "Direct UDP"; "relay" -> "TLS-443 relay"; "none" -> "no path"; else -> path },
        listOfNotNull(transport?.detail?.takeIf { it.isNotBlank() }, transport?.relay?.takeIf { it.isNotBlank() }, transport?.mode?.let { "mode $it" })
            .joinToString(" · ").ifBlank { "no connect recorded yet" })
    val at = if (probesAtMs > 0) " · tested ${MeshFormat.clock(probesAtMs)}" else ""
    val relay = probes.firstOrNull { it.id == "relay" }
    val key = probes.firstOrNull { it.id == "relay_key" }
    out += LightLine("fallbacks_ready", if (probes.isEmpty()) null else relay?.ok == true && key?.ok != false,
        when { probes.isEmpty() -> "not tested"; relay?.ok == true && key?.ok != false -> "relay ready"; else -> "no fallback ready" },
        (if (probes.isEmpty()) "tap Test fallbacks" else probes.joinToString(" · ") { "${it.id} ${C3MeshProbe.verdict(it.ok).trim()}" }) + at)
    // #1798 one light per path of the Auto ladder, in the order it tries them: ready when its own steps last passed
    fun ok(prefix: String) = probes.filter { it.id == prefix || it.id.startsWith("$prefix:") }.let { l -> if (l.isEmpty()) null else l.any { it.ok == true } }
    val active = path.ifBlank { "?" }
    for ((pid, steps) in listOf("direct" to listOf("udp"), "pinned" to listOf("pinned"), "doh" to listOf("doh"), "relay" to listOf("relay", "relay_key"))) {
        val v = if (pid == "relay") ok("relay")?.let { it && key?.ok != false } else steps.mapNotNull { ok(it) }.let { if (it.isEmpty()) null else it.any { x -> x } }
        out += LightLine("path:$pid", v, (if (active == pid) "active · " else "") + when (v) { true -> "ready"; false -> "not ready"; null -> "not tested" },
            probes.filter { p -> steps.any { p.id == it || p.id.startsWith("$it:") } }.joinToString(" · ") { "${it.id} ${C3MeshProbe.verdict(it.ok).trim()}" }.ifBlank { "tap Test fallbacks" } + at)
    }
    if (probes.isEmpty()) out += LightLine("fb", null, "not tested", "tap Test fallbacks in Path to the hubs")
    for (p in probes) out += LightLine("fb:${p.id}", p.ok, C3MeshProbe.verdict(p.ok).trim(), p.detail + at)
    return out
}

@Composable
internal fun MeshLightsRow(store: MeshStore, s: MeshSnapshot, tag: String) {
    MHeader(stringResource(R.string.mesh_row_lights))
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(tag)) {
        for (l in lightLines(s, store.meshProfile, store.reach, store.probes, store.probesAtMs, store.transport)) Row(
            Modifier.testTag("mesh:light:${l.id}").clickable { open = if (open == l.id) null else l.id }
                .padding(vertical = MeshDensity.dp(MeshDensity.S2)),
            horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)),
            verticalAlignment = Alignment.Top,
        ) {
            Light(stateOf(l.ok), l.word)
            val name = if (l.id.startsWith("path:")) declLabel("light", "path_" + l.id.removePrefix("path:"), l.id) else if (l.id.startsWith("fb:")) l.id.removePrefix("fb:").let { id ->
                val base = id.substringBefore(':')
                val sub = if (':' in id) id.substringAfter(':') else ""
                if (base == "doh" && sub.isNotEmpty()) sub else declLabel("probe", base, base) + if (sub.isNotEmpty()) " $sub" else ""
            } else declLabel("light", l.id, l.id)
            MText(name, Modifier.width(MeshDensity.dp(MeshDensity.COLUMN)), size = MeshDensity.T_META, bold = true)
            Column(Modifier.weight(1f)) {
                MText(l.word, size = MeshDensity.T_META)
                // tap: the full detail; closed: two lines
                MText(l.detail, size = MeshDensity.T_MICRO, mono = true, muted = true, maxLines = if (open == l.id) Int.MAX_VALUE else 2)
            }
        }
    }
}

@Composable
internal fun MeshTableRow(store: MeshStore, tag: String) {
    MHeader(stringResource(R.string.mesh_row_meshes))
    val p = LocalKitPalette.current
    val r = store.meshProfile
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.testTag(tag)) {
        if (r == null) MText(stringResource(R.string.mesh_meshes_none, store.decl.meshes.size), size = MeshDensity.T_META, muted = true)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S4))) {
                    MText(declLabel("col", "mesh", "mesh"), Modifier.width(MeshDensity.dp(MeshDensity.COLUMN)), size = MeshDensity.T_CAPTION, bold = true, color = p.accent)
                    for (c in C3MeshProbe.COLUMNS) MText(declLabel("col", c, c), Modifier.width(MeshDensity.dp(MeshDensity.COLUMN)), size = MeshDensity.T_CAPTION, bold = true, color = p.accent)
                }
                for (t in store.decl.meshes) {
                    val row = r?.rows?.firstOrNull { it.target.id == t.id }
                    Row(Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S2)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S4)),
                        verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(MeshDensity.dp(MeshDensity.COLUMN))) {
                            MText(t.label, size = MeshDensity.T_META, bold = true)
                            MText(t.cidr, size = MeshDensity.T_MICRO, mono = true, muted = true)
                        }
                        for (c in C3MeshProbe.COLUMNS) {
                            val cell = row?.cell(c)
                            Row(Modifier.width(MeshDensity.dp(MeshDensity.COLUMN)).testTag("mesh:mesh:${t.id}:$c")
                                .clickable { open = if (open == (t.id to c)) null else (t.id to c) },
                                horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S2)), verticalAlignment = Alignment.CenterVertically) {
                                if (c != "last") Light(stateOf(cell?.ok), cell?.text ?: "-")
                                MText(cell?.text ?: "-", size = MeshDensity.T_MICRO, mono = true, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
        open?.let { (m, c) ->
            val cell = r?.rows?.firstOrNull { it.target.id == m }?.cell(c)
            KvRow("$m · ${declLabel("col", c, c)}", cell?.detail ?: stringResource(R.string.mesh_meshes_none, store.decl.meshes.size), tag = "mesh:mesh:detail")
        }
        // Every failing cell's reason, without a tap: the failure is never hidden behind one.
        r?.rows?.forEach { row ->
            for (c in C3MeshProbe.COLUMNS) row.cell(c).takeIf { it.ok == false }?.let {
                KvRow("${row.target.label} · ${declLabel("col", c, c)}", it.detail, tag = "mesh:mesh:fail:${row.target.id}:$c")
            }
        }
        Reason(stringResource(R.string.mesh_meshes_how))
        store.decl.page("status")?.control("reach_meshes_run")?.let { c ->
            MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) },
                Modifier.testTag("mesh:meshes:probe").padding(vertical = MeshDensity.dp(MeshDensity.S4)), enabled = !store.busy)
        }
    }
}

@Composable
internal fun MeshPeersRow(store: MeshStore, s: MeshSnapshot, tag: String) {
    MHeader(stringResource(R.string.mesh_row_peers))
    if (s.peers.isEmpty()) MText(stringResource(R.string.mesh_no_peers), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
    s.peers.forEachIndexed { i, p ->
        val name = p.cfg.name.ifBlank { stringResource(R.string.mesh_peer_n, i + 1) }
        Column(Modifier.testTag(if (i == 0) tag else "mesh:peerrow:$i").padding(vertical = MeshDensity.dp(MeshDensity.S2))) {
            MText(name, size = MeshDensity.T_BODY, bold = true)
            for ((k, v) in peerFields(p, s)) KvRow(declLabel("pf", k, k), v, tag = "mesh:peerrow:$i:$k",
                onCopy = if (k == "public_key" && p.cfg.publicKey.isNotBlank()) { { store.host.copy("public key", p.cfg.publicKey) } } else null)
        }
    }
}

/** #1797 Every field of one peer, in the order Status shows them; the parity test reads this same list. */
fun peerFields(p: PeerLive, s: MeshSnapshot): List<Pair<String, String>> = listOf(
    "handshake" to (if (!s.engineInstalled) "unknown" else if (!s.up) "-" else p.handshakeAgeS?.let { MeshFormat.age(it) + " ago" } ?: "none yet"),
    "health" to p.health.name,
    "endpoint" to p.cfg.endpoint.ifBlank { "-" },
    "allowed_ips" to p.cfg.allowedIps.ifBlank { "-" },
    "rx" to "${MeshFormat.bytes(p.rx)} (${p.rx} B) ${MeshFormat.rate(p.rxRate)}",
    "tx" to "${MeshFormat.bytes(p.tx)} (${p.tx} B) ${MeshFormat.rate(p.txRate)}",
    "latency" to MeshFormat.latency(p.latencyMs),
    "keepalive" to p.cfg.keepalive.ifBlank { "-" },
    "public_key" to p.cfg.publicKey.ifBlank { "-" },
)

@Composable
internal fun MeshProfilerRow(store: MeshStore, tag: String) {
    MHeader(stringResource(R.string.mesh_row_profiler))
    val page = store.decl.page("status")
    Row(Modifier.testTag(tag).padding(bottom = MeshDensity.dp(MeshDensity.S4)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6))) {
        for (id in listOf("reach_meshes_run", "status_profiler_copy", "status_profiler_export")) page?.control(id)?.let { c ->
            MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)),
                enabled = !store.busy && (id == "reach_meshes_run" || store.meshProfile != null))
        }
    }
    Reason(stringResource(R.string.mesh_profiler_how))
    val r = store.meshProfile
    if (r == null) { MText(stringResource(R.string.mesh_meshes_none, store.decl.meshes.size), size = MeshDensity.T_META, muted = true); return }
    KvRow(declLabel("pgrp", "at", "at"), MeshFormat.clock(r.atMs) + " · " + r.steps.size + " steps · " + r.steps.count { it.ok == false } + " fail", tag = "mesh:profiler:at")
    for (g in C3MeshProbe.GROUPS) {
        val gs = r.steps.filter { it.group == g }
        if (gs.isEmpty()) continue
        MText(declLabel("pgrp", g, g), Modifier.padding(top = MeshDensity.dp(MeshDensity.S4)), size = MeshDensity.T_META, bold = true)
        gs.forEachIndexed { i, st ->
            Row(Modifier.testTag("mesh:profiler:$g:$i").padding(vertical = MeshDensity.dp(MeshDensity.S1)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)),
                verticalAlignment = Alignment.Top) {
                Light(stateOf(st.ok), C3MeshProbe.verdict(st.ok))
                Column(Modifier.weight(1f)) {
                    MText(st.id, size = MeshDensity.T_CAPTION, mono = true, bold = true)
                    MText(C3MeshProbe.verdict(st.ok) + " · " + st.ms + " ms · " + st.detail, size = MeshDensity.T_MICRO, mono = true, muted = true)
                }
            }
        }
    }
}

@Composable
internal fun MeshStatusLogRow(store: MeshStore, tag: String) {
    @Suppress("UNUSED_VARIABLE") val rev = store.journalRev
    val all = store.journal.list()
    MHeader(stringResource(R.string.mesh_row_log, all.size))
    val c = store.decl.page("status")?.control("status_log_open")
    val open = store.view["status_log_open"] == "on"
    if (c != null) SwitchLine(declLabel("ctl", c.id, c.label), open, { on -> store.run(c.engine, if (on) "on" else "off") }, Modifier.testTag(tag),
        reason = stringResource(R.string.mesh_status_log_how, all.size))
    if (!open) return
    all.asReversed().forEachIndexed { i, e ->
        Row(Modifier.testTag("mesh:statuslog:$i").padding(vertical = MeshDensity.dp(MeshDensity.S1)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6))) {
            MText(MeshFormat.clock(e.atMs), size = MeshDensity.T_CAPTION, mono = true, muted = true)
            MText(MeshJournal.levelOf(e.kind).uppercase(), size = MeshDensity.T_CAPTION, mono = true, muted = true)
            MText(e.kind, size = MeshDensity.T_CAPTION, mono = true, bold = true)
            MText(e.text, Modifier.weight(1f), size = MeshDensity.T_CAPTION, mono = true)
        }
    }
}

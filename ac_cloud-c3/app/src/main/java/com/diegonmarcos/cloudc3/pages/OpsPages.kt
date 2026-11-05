package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Test tags of the Ops maps, Health and Observability pages. */
object OpsTags {
    fun map(id: String) = "ops_map_$id"
    fun row(id: String, name: String) = "ops_row_${id}_$name"
    const val HEALTH = "ops_health"
    const val LOGS = "ops_logs"
    const val LOGS_SEARCH = "ops_logs_search"
    const val LOGS_QUERY = "ops_logs_query"
    fun chip(group: String, id: String) = "ops_chip_${group}_$id"
}

private const val GLYPH_DOT = "·"
private const val GLYPH_OK = "✓"
private const val GLYPH_WARN = "⚠"
private const val GLYPH_PLANNED = "◌"

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalKitPalette.current.accent, fontSize = KitStoreRow.meta,
        modifier = Modifier.padding(start = KitStoreRow.padH, top = KitStoreRow.gap, bottom = KitStoreRow.tight))
}

@Composable
private fun Summary(text: String) {
    if (text.isNotBlank()) Text(text, color = LocalKitPalette.current.textSecondary, fontSize = KitStoreRow.caption,
        modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight))
}

private fun stateInk(state: String): Color = when (state) {
    "planned" -> KitStoreRow.UPDATE
    "unverified", "fallback" -> KitStoreRow.MISSING
    else -> Color.Unspecified
}

/** One node of a map: its name, note and state, then one line per outgoing edge. */
@Composable
private fun MapRow(id: String, r: OpsMaps.Row) {
    val p = LocalKitPalette.current
    KitStoreRowLine(Modifier.testTag(OpsTags.row(id, r.name))) {
        KitStoreDot(if (r.state == "planned") GLYPH_PLANNED else GLYPH_DOT, stateInk(r.state).takeIf { it != Color.Unspecified } ?: p.textSecondary)
        Column(Modifier.weight(1f)) {
            KitStoreName(r.name, color = p.textPrimary)
            if (r.note.isNotBlank()) KitStoreCaption(r.note, color = p.textSecondary)
            r.edges.forEach { e ->
                KitStoreCaption("→ ${e.to}" + if (e.label.isBlank()) "" else "  ${e.label}", color = stateInk(e.state).takeIf { it != Color.Unspecified } ?: p.accent, mono = true)
            }
        }
        if (r.state.isNotBlank()) KitStoreCaption(r.state, color = stateInk(r.state).takeIf { it != Color.Unspecified } ?: p.textSecondary)
    }
}

/** A map: sections of nodes with their edges. Pure of Android so the JVM suite renders it. */
@Composable
fun OpsMapScreen(id: String, summary: String, sections: List<OpsMaps.Section>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(OpsTags.map(id))) {
        Summary(summary)
        sections.forEach { s ->
            SectionTitle("${s.title} (${s.rows.size})")
            s.rows.forEach { MapRow(id, it) }
        }
    }
}

/** #1495 A catalog page of the one hierarchy (data/c3_hierarchy.json): one dense row per API route pattern or MCP tool of the page. */
@Composable
fun OpsCatalogScreen(tag: String, mapId: String, decl: JsonObject = Declarations.opsMaps) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(tag)) {
        Summary(OpsMaps.summary(decl, mapId))
        OpsMaps.rows(decl, mapId).forEach { s ->
            SectionTitle("${s.title} (${s.rows.size})")
            s.rows.forEach { r ->
                KitStoreRowLine(Modifier.testTag(OpsTags.row(mapId, r.name))) {
                    Column(Modifier.weight(1f)) {
                        KitStoreName(r.name, color = p.textPrimary)
                        if (r.note.isNotBlank()) KitStoreCaption(r.note, color = p.textSecondary, mono = true)
                    }
                }
            }
        }
    }
}

/** The Ops maps as a page: the map id names a map of data/ops_maps.json; the live catalog is the API copy when it answered. */
class OpsMapFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val id = remember { arguments?.getString(ARG_MAP).orEmpty() }
        val ctx = LocalContext.current
        val version = ContainersSource.version
        LaunchedEffect(Unit) { ContainersSource.refresh(ApiFleetLive(ctx.applicationContext)) }
        val sections = remember(version) {
            OpsMaps.build(id, OpsMaps.Sources(Declarations.opsMaps, Declarations.meshJson, TopologyModel.parseInfra(Declarations.infraSlice),
                Declarations.infraSlice, ContainersSource.current(), Declarations.fleet.domains))
        }
        OpsMapScreen(id, OpsMaps.summary(Declarations.opsMaps, id), sections)
    }

    companion object {
        private const val ARG_MAP = "ops_map"
        fun newInstance(id: String) = OpsMapFragment().apply { arguments = Bundle().apply { putString(ARG_MAP, id) } }
    }
}

// ── Health: uptime of every service, then the reports ───────────────────────

/** Services (one row per declared container, worst first) with their live `docker ps` uptime, then VM uptime and report freshness. */
@Composable
fun OpsHealthScreen(inputs: TopologyModel.Inputs, uptime: List<OpsLive.Uptime>?, reports: List<OpsLive.Report>?, notes: List<String>) {
    val p = LocalKitPalette.current
    val rows = inputs.catalog.entries.map { it to TopologyModel.containerState(inputs, it) }.sortedWith(compareBy({ it.second.first }, { it.first.name }))
    val up = rows.count { it.second.first == TopologyModel.Health.OK }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(OpsTags.HEALTH)) {
        notes.forEach { Summary(it) }
        SectionTitle("Services up: $up/${rows.size}")
        rows.forEach { (e, st) ->
            val (glyph, ink) = when (st.first) {
                TopologyModel.Health.OK -> GLYPH_OK to KitStoreRow.UP
                TopologyModel.Health.BAD -> GLYPH_WARN to KitStoreRow.BAD
                TopologyModel.Health.WARN -> GLYPH_WARN to KitStoreRow.UPDATE
                else -> GLYPH_DOT to p.textSecondary
            }
            KitStoreRowLine(Modifier.testTag(OpsTags.row("health", e.name))) {
                KitStoreDot(glyph, ink)
                KitStoreName(e.name, Modifier.weight(1f), p.textPrimary)
                KitStoreCaption("${e.host}  ${st.second}", color = ink, mono = true)
            }
        }
        uptime?.let { list ->
            SectionTitle("VM uptime, from the API's health checks (${list.size})")
            list.forEach { u ->
                KitStoreRowLine {
                    KitStoreName(u.vm, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption("${u.percent}%  ${u.checks} checks", color = if (u.percent >= 99.0) KitStoreRow.UP else KitStoreRow.UPDATE, mono = true)
                }
            }
        }
        reports?.let { list ->
            SectionTitle("Reports (${list.size})")
            list.forEach { r ->
                val ink = if (r.ok && !r.stale) KitStoreRow.UP else KitStoreRow.BAD
                KitStoreRowLine {
                    KitStoreDot(if (r.ok && !r.stale) GLYPH_OK else GLYPH_WARN, ink)
                    KitStoreName(r.family, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(r.error.ifBlank { r.ageHours?.let { "${it}h old" + if (r.stale) ", stale" else "" } ?: "never published" }, color = ink)
                }
            }
        }
    }
}

class OpsHealthFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val slice = remember { Declarations.fleet }
        val decl = remember { Declarations.topology }
        val version = ContainersSource.version
        val state = TopologyLiveState.shared
        var uptime by remember { mutableStateOf<List<OpsLive.Uptime>?>(null) }
        var reports by remember { mutableStateOf<List<OpsLive.Report>?>(null) }
        var notes by remember { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(Unit) {
            val src = ApiFleetLive(ctx.applicationContext)
            ContainersSource.refresh(src)
            runCatching { state.refresh(slice, src, { releaseRuns(ctx.applicationContext, decl.release) }) }
            val n = mutableListOf<String>()
            when (val r = src.get(OpsLive.UPTIME_PATH)) {
                is FleetLive.Result.Body -> uptime = OpsLive.parseUptime(r.text) ?: run { n += "VM uptime: unreadable answer"; null }
                is FleetLive.Result.Failed -> n += "VM uptime unavailable: ${r.why}"
            }
            when (val r = src.get(OpsLive.REPORTS_PATH)) {
                is FleetLive.Result.Body -> reports = OpsLive.parseReports(r.text) ?: run { n += "Reports: unreadable answer"; null }
                is FleetLive.Result.Failed -> n += "Reports unavailable: ${r.why}"
            }
            state.live.deployed.filterValues { it is TopologyModel.Deployed.Blind }.forEach { (vm, d) -> n += "Live read of $vm failed: ${(d as TopologyModel.Deployed.Blind).why}" }
            if (state.live.deployed.isEmpty()) n += "Live state not read yet: services show as unknown."
            notes = n
        }
        val inputs = remember(version, state.live) {
            TopologyModel.Inputs(ContainersSource.current(), slice, TopologyModel.parseInfra(Declarations.infraSlice), state.live, decl)
        }
        OpsHealthScreen(inputs, uptime, reports, notes)
    }
}

// ── Observability: the logs catcher ─────────────────────────────────────────

@Composable
private fun Chip(tag: String, label: String, on: Boolean, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitStoreRow.tight)
    KitStoreCaption(label, color = if (on) p.tileInk else p.textPrimary,
        modifier = Modifier.padding(end = KitStoreRow.tight).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag(tag))
}

/** A row of chips for one filter: "All" (null) then the choices. */
@Composable
private fun Chips(group: String, choices: List<String>, selected: String?, onPick: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
        KitStoreCaption(group, color = LocalKitPalette.current.textSecondary, modifier = Modifier.padding(end = KitStoreRow.gap))
        Chip(OpsTags.chip(group, ""), "All", selected == null) { onPick(null) }
        choices.forEach { c -> Chip(OpsTags.chip(group, c), c, selected == c) { onPick(if (selected == c) null else c) } }
    }
}

@Composable
fun OpsLogsScreen(
    vms: List<String>, services: List<String>, levels: List<String>,
    vm: String?, service: String?, level: String?, query: String,
    lines: List<OpsLive.LogLine>?, note: String?, busy: Boolean,
    onVm: (String?) -> Unit, onService: (String?) -> Unit, onLevel: (String?) -> Unit, onQuery: (String) -> Unit, onSearch: () -> Unit,
) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(OpsTags.LOGS)) {
        Chips("VM", vms, vm, onVm)
        if (services.isNotEmpty()) Chips("Service", services, service, onService)
        Chips("Level", levels, level, onLevel)
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicTextField(query, onQuery, singleLine = true, cursorBrush = SolidColor(p.accent),
                textStyle = TextStyle(color = p.textPrimary, fontSize = KitStoreRow.body, fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f).border(1.dp, p.hairline, RoundedCornerShape(KitStoreRow.tight)).padding(KitStoreRow.gap).testTag(OpsTags.LOGS_QUERY))
            Chip(OpsTags.LOGS_SEARCH, if (busy) "..." else "Search", true, onSearch)
        }
        note?.let { Summary(it) }
        lines?.let { ls ->
            SectionTitle("${ls.size} lines, newest first")
            ls.forEachIndexed { i, l ->
                val ink = when (l.level) { "error" -> KitStoreRow.BAD; "warn" -> KitStoreRow.UPDATE; else -> p.textSecondary }
                KitStoreRowLine(Modifier.testTag("ops_log_$i")) {
                    Column(Modifier.weight(1f)) {
                        KitStoreCaption("${l.ts.take(19)}  ${l.vm}  ${l.container}  ${l.level}", color = ink, mono = true)
                        Text(l.text, color = p.textPrimary, fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The logs catcher: a search over c3-infra-api GET /logs/search, filtered by VM, service and level (the VM filter narrows the answer). */
class OpsLogsFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val slice = remember { Declarations.fleet }
        val cfg = remember { Declarations.opsMaps["logs"] as? JsonObject }
        val levels = remember { (cfg?.get("levels") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
        val limit = remember { (cfg?.get("limit") as? JsonPrimitive)?.intOrNull ?: 100 }
        val version = ContainersSource.version
        var vm by remember { mutableStateOf<String?>(null) }
        var service by remember { mutableStateOf<String?>(null) }
        var level by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var lines by remember { mutableStateOf<List<OpsLive.LogLine>?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val vmChoices = remember { slice.vms.filter { it.wgIp != null }.map { it.alias } }
        val services = remember(vm, version) {
            ContainersSource.current().entries.filter { vm == null || it.host == vm }.map { it.service }.distinct().sorted()
        }
        OpsLogsScreen(vmChoices, if (vm == null) emptyList() else services, levels, vm, service, level, query, lines, note, busy,
            onVm = { vm = it; service = null }, onService = { service = it }, onLevel = { level = it }, onQuery = { query = it },
            onSearch = {
                if (!busy) scope.launch {
                    busy = true
                    val names = vm?.let { a -> slice.vms.filter { it.alias == a }.flatMap { listOf(it.id, it.alias) }.toSet() }
                    when (val r = ApiFleetLive(ctx.applicationContext).get(OpsLive.logsPath(query, service.orEmpty(), level.orEmpty(), limit))) {
                        is FleetLive.Result.Body -> {
                            val parsed = OpsLive.parseLogs(r.text)
                            lines = parsed?.let { OpsLive.byVm(it, names) }
                            note = if (parsed == null) "The API's answer was not a log list." else if (lines.isNullOrEmpty()) "No log line matches these filters." else null
                        }
                        is FleetLive.Result.Failed -> { lines = null; note = "Log search unavailable: ${r.why}" }
                    }
                    busy = false
                }
            })
    }
}

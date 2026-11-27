package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.safeDrawText

/** Test tags of Ops > Mail Tables and the two topologies. */
object MailTags {
    const val TABLES = "mail_tables"
    const val SEARCH = "mail_tables_search"
    const val MAP_SUMMARY = "mail_tables_map_summary"
    fun section(id: String) = "mail_table_$id"
    fun row(id: String, i: Int) = "mail_row_${id}_$i"
    fun filter(table: String, key: String, v: String) = "mail_filter_${table}_${key}_$v"
    fun canvas(id: String) = "mail_topology_$id"
    fun detail(node: String) = "mail_detail_$node"
    const val DETAIL_CLOSE = "mail_detail_close"
}

private fun mono(size: Float, c: Color, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, color = c, fontWeight = w)

// ── Tables ──────────────────────────────────────────────────────────────────

/** Every declared table of data/mail_stack.json: one search box, per-table filter chips, dense monospaced rows, "not served" for a missing key. */
@Composable
fun MailTablesScreen(decl: MailStack.Decl, answer: MailStack.Answer?, banner: String, infraRaw: kotlinx.serialization.json.JsonObject, domains: List<FleetModel.Domain>,
                     maps: OpsMaps.Sources? = null) {
    val p = LocalKitPalette.current
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(MailTags.TABLES)) {
        if (banner.isNotBlank()) Text(banner, style = mono(11f, p.textSecondary), modifier = Modifier.padding(KitStoreRow.padH, 4.dp))
        BasicTextField(query, { query = it }, singleLine = true, textStyle = mono(13f, p.textPrimary), cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().padding(KitStoreRow.padH, 4.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).padding(8.dp).testTag(MailTags.SEARCH),
            decorationBox = { inner -> if (query.isEmpty()) Text("search every table", style = mono(13f, p.textSecondary)); inner() })
        // #1955 B the old Mail subpage merged into Tables: its summary line heads the map-bound tables
        decl.tables.firstOrNull { it.map.isNotBlank() }?.let { t ->
            val sum = maps?.let { OpsMaps.summary(it.decl, t.map) }.orEmpty()
            Text("Map: ${sum.ifBlank { MailStack.NOT_SERVED }}", color = p.accent, fontSize = KitStoreRow.meta,
                modifier = Modifier.padding(start = KitStoreRow.padH, top = KitStoreRow.gap).testTag(MailTags.MAP_SUMMARY))
        }
        decl.tables.forEach { t ->
            val all = MailStack.rows(t, answer, infraRaw, domains, maps)
            val keys = t.filters.associateWith { picked["${t.id}.$it"] }.filterValues { it != null }.mapValues { it.value!! }
            val shown = MailStack.filter(t, all, query, keys)
            Column(Modifier.testTag(MailTags.section(t.id))) {
                Text("${t.title} (${shown.size}/${all.size})", color = p.accent, fontSize = KitStoreRow.meta,
                    modifier = Modifier.padding(start = KitStoreRow.padH, top = KitStoreRow.gap, bottom = KitStoreRow.tight))
                t.filters.forEach { f ->
                    val ch = MailStack.choices(all, f)
                    if (ch.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH)) {
                        Text("$f ", style = mono(11f, p.textSecondary), modifier = Modifier.padding(end = 4.dp, top = 4.dp))
                        ch.forEach { v ->
                            val on = picked["${t.id}.$f"] == v
                            Text(v, style = mono(11f, if (on) p.tileInk else p.textPrimary),
                                modifier = Modifier.padding(end = 4.dp).background(if (on) p.textPrimary else Color.Transparent, RoundedCornerShape(4.dp))
                                    .border(1.dp, p.hairline, RoundedCornerShape(4.dp)).clickable { if (on) picked.remove("${t.id}.$f") else picked["${t.id}.$f"] = v }
                                    .padding(6.dp, 3.dp).testTag(MailTags.filter(t.id, f, v)))
                        }
                    }
                }
                if (all.isEmpty()) Text(MailStack.emptyReason(t, answer), style = mono(11f, p.textSecondary), modifier = Modifier.padding(KitStoreRow.padH, 4.dp))
                val missing = MailStack.missingFields(t, all)
                if (missing.isNotEmpty()) Text("not served by the API: ${missing.joinToString()}", style = mono(11f, KitStoreRow.UPDATE), modifier = Modifier.padding(KitStoreRow.padH, 2.dp))
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH)) {
                    Row(Modifier.background(p.surfaceSelected)) {
                        t.columns.forEach { c -> Text(c.label, style = mono(11f, p.accent, FontWeight.Bold), modifier = Modifier.width(150.dp).padding(4.dp)) }
                    }
                    shown.forEachIndexed { i, r ->
                        Row(Modifier.testTag(MailTags.row(t.id, i)).border(0.5.dp, p.hairline)) {
                            t.columns.forEach { c ->
                                val v = MailStack.cell(r, c.key)
                                val ink = when {
                                    v == MailStack.NOT_SERVED -> KitStoreRow.UPDATE
                                    c.key == "state" -> MailStack.healthColor(MailStack.healthOf(v), p)
                                    else -> p.textPrimary
                                }
                                Text(v, style = mono(11f, ink), maxLines = 3, modifier = Modifier.width(150.dp).padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Generic topology canvas ─────────────────────────────────────────────────

/** ONE canvas for any declared topology: blocks, wires, pulses along from -> to, health colours, pan, pinch zoom, tap for details. */
@Composable
fun MailTopologyCanvas(topology: MailStack.Topology, decl: MailStack.Decl, answer: MailStack.Answer?, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val plan = remember(topology, answer, p) { MailStack.plan(topology, answer, decl, p) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf<MailStack.View?>(null) }
    val v = view ?: MailStack.fit(plan.bounds, box.width.toFloat().coerceAtLeast(1f), box.height.toFloat().coerceAtLeast(1f))
    val phase by rememberInfiniteTransition(label = "mail-flow").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    Canvas(modifier.onSizeChanged { box = it }.background(p.surface).testTag(MailTags.canvas(topology.id))
        .pointerInput(plan) { detectTransformGestures { c, pan, z, _ -> view = (view ?: v).transform(c, pan, z) } }
        .pointerInput(plan, v) { detectTapGestures { at -> MailStack.hit(plan, view ?: v, at)?.let(onOpen) } }) {
        val s = v.scale
        val grid = 40f * s
        if (grid > 8f) {
            var gx = ((v.ox % grid) + grid) % grid
            while (gx < size.width) { drawLine(p.hairline.copy(alpha = 0.25f), Offset(gx, 0f), Offset(gx, size.height)); gx += grid }
            var gy = ((v.oy % grid) + grid) % grid
            while (gy < size.height) { drawLine(p.hairline.copy(alpha = 0.25f), Offset(0f, gy), Offset(size.width, gy)); gy += grid }
        }
        plan.edges.forEach { e ->
            val a = v.toScreen(e.p0); val b = v.toScreen(e.p1); val c = v.toScreen(e.p2); val d = v.toScreen(e.p3)
            val path = Path().apply { moveTo(a.x, a.y); cubicTo(b.x, b.y, c.x, c.y, d.x, d.y) }
            drawPath(path, e.color.copy(alpha = 0.75f), style = Stroke(width = 2f * density * s.coerceIn(0.6f, 1.6f),
                pathEffect = if (e.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
            val tip = MailStack.bezier(e, 0.98f); val ctr = v.toScreen(tip); val back = v.toScreen(MailStack.bezier(e, 0.92f))
            val dir = (ctr - back).let { o -> val l = kotlin.math.sqrt(o.x * o.x + o.y * o.y).coerceAtLeast(0.001f); Offset(o.x / l, o.y / l) }
            val k = 9f * density * s.coerceIn(0.6f, 1.4f)
            val arrow = Path().apply {
                moveTo(d.x, d.y)
                lineTo(d.x - dir.x * k + dir.y * k * 0.5f, d.y - dir.y * k - dir.x * k * 0.5f)
                lineTo(d.x - dir.x * k - dir.y * k * 0.5f, d.y - dir.y * k + dir.x * k * 0.5f); close()
            }
            drawPath(arrow, e.color)
            MailStack.pulses(e, phase).forEach { w -> drawCircle(e.color, 4.5f * density * s.coerceIn(0.7f, 1.5f), v.toScreen(w)) }
            if (s >= 0.6f && e.label.isNotBlank()) {
                val m = v.toScreen(MailStack.bezier(e, 0.5f))
                safeDrawText(measurer, e.label, Offset(m.x - 28f * s, m.y - 14f * density * s), mono(9f * s.coerceIn(0.8f, 1.4f), p.textSecondary), maxLines = 1, softWrap = false)
            }
        }
        plan.nodes.forEach { n ->
            val tl = v.toScreen(Offset(n.rect.l, n.rect.t)); val br = v.toScreen(Offset(n.rect.r, n.rect.b))
            val sz = Size(br.x - tl.x, br.y - tl.y)
            drawRoundRect(p.surfaceSelected, tl, sz, CornerRadius(8f * density * s))
            drawRoundRect(n.color, tl, sz, CornerRadius(8f * density * s), style = Stroke(2f * density))
            drawRect(n.color, tl, Size(5f * density * s.coerceIn(0.6f, 1.2f), sz.height))
            if (s >= 0.45f) {
                val fs = 12f * s.coerceIn(0.7f, 1.6f)
                safeDrawText(measurer, n.label, Offset(tl.x + 12f * density * s, tl.y + 8f * density * s), mono(fs, p.textPrimary, FontWeight.Bold), maxLines = 1, softWrap = false)
                safeDrawText(measurer, MailStack.healthLabel(n.health) + if (n.sub.isBlank()) "" else "  " + n.sub, Offset(tl.x + 12f * density * s, tl.y + sz.height * 0.55f), mono(fs * 0.8f, n.color), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun DetailLine(k: String, v: String) {
    val p = LocalKitPalette.current
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(k, style = mono(11f, p.textSecondary), modifier = Modifier.width(92.dp))
        Text(v.ifBlank { "-" }, style = mono(11f, p.textPrimary))
    }
}

/** The detail sheet of one node: role, host or VM, container, ports, TLS, last check, latency, queue, recent errors, log links. */
@Composable
fun MailNodeDetail(d: MailStack.NodeDetail, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val n = d.node
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).background(p.surface).border(1.dp, MailStack.healthColor(d.health, p), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        .verticalScroll(rememberScrollState()).padding(12.dp).testTag(MailTags.detail(n.id))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.label, style = mono(14f, p.textPrimary, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(MailStack.healthLabel(d.health), style = mono(12f, MailStack.healthColor(d.health, p)))
            Text("  close", style = mono(12f, p.accent), modifier = Modifier.clickable(onClick = onClose).padding(8.dp).testTag(MailTags.DETAIL_CLOSE))
        }
        DetailLine("role", n.role); DetailLine("host", n.host); DetailLine("vm", n.vm); DetailLine("container", n.container)
        DetailLine("ports", n.ports.joinToString(", ")); DetailLine("tls", n.tls.joinToString(", "))
        DetailLine("last check", d.lastCheck); DetailLine("latency", d.latencyMs?.let { "$it ms" } ?: MailStack.NOT_SERVED)
        DetailLine("queue", if (n.queueProbe.isBlank()) "-" else d.queue?.toString() ?: MailStack.NOT_SERVED)
        DetailLine("logs", n.logs.joinToString(", "))
        d.probes.forEach { DetailLine(it.id, "${MailStack.healthLabel(it.health)} ${it.detail}") }
        d.tls.forEach { DetailLine(it.id, "${MailStack.healthLabel(it.health)} ${it.detail}") }
        d.notServed.forEach { DetailLine(it, MailStack.NOT_SERVED) }
        DetailLine("errors", if (d.errors.isEmpty()) "none" else d.errors.joinToString("\n"))
    }
}

@Composable
fun MailTopologyScreen(topology: MailStack.Topology, decl: MailStack.Decl, answer: MailStack.Answer?, banner: String, initialOpen: String? = null) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(initialOpen) }
    Column(Modifier.fillMaxSize()) {
        Text(topology.title + if (banner.isBlank()) "" else "  |  $banner", style = mono(11f, p.textSecondary), modifier = Modifier.padding(KitStoreRow.padH, 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MailTopologyCanvas(topology, decl, answer, Modifier.fillMaxSize()) { open = it }
            topology.nodes.firstOrNull { it.id == open }?.let { n ->
                Box(Modifier.align(Alignment.BottomCenter)) { MailNodeDetail(MailStack.detail(n, answer)) { open = null } }
            }
        }
    }
}

// ── Fragment ────────────────────────────────────────────────────────────────

/** Loads GET /mail/stack once and shows the Tables page or one topology of data/mail_stack.json. */
class MailStackFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val view = remember { arguments?.getString(ARG_VIEW).orEmpty() }
        val decl = remember { Declarations.mailStack }
        val ctx = LocalContext.current
        var answer by remember { mutableStateOf<MailStack.Answer?>(null) }
        var banner by remember { mutableStateOf("loading the live mail stack...") }
        LaunchedEffect(Unit) {
            when (val r = ApiFleetLive(ctx.applicationContext).get(decl.apiPath)) {
                is FleetLive.Result.Body -> {
                    answer = MailStack.parseAnswer(r.text)
                    banner = if (answer == null) "unreadable answer from ${decl.apiPath}" else answer!!.let { a ->
                        listOfNotNull("generated ${a.generatedAt}".takeIf { a.generatedAt.isNotBlank() }, a.reportError.takeIf { it.isNotBlank() }, a.liveError.takeIf { it.isNotBlank() }).joinToString("; ")
                    }
                }
                is FleetLive.Result.Failed -> banner = "not served: ${decl.apiPath} unavailable (${r.why}); the API may not be deployed yet"
            }
        }
        val topo = decl.topology(view)
        if (topo != null) MailTopologyScreen(topo, decl, answer, banner)
        else {
            val version = ContainersSource.version
            val maps = remember(version) {
                OpsMaps.Sources(Declarations.opsMaps, Declarations.meshJson, TopologyModel.parseInfra(Declarations.infraSlice),
                    Declarations.infraSlice, ContainersSource.current(), Declarations.fleet.domains)
            }
            MailTablesScreen(decl, answer, banner, Declarations.infraSlice, Declarations.fleet.domains, maps)
        }
    }

    companion object {
        private const val ARG_VIEW = "mail_view"
        fun newInstance(view: String): Fragment = MailStackFragment().apply { arguments = Bundle().apply { putString(ARG_VIEW, view) } }
    }
}

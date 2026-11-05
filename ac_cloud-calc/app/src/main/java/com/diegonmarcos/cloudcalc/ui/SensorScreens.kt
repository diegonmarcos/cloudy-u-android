@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import com.diegonmarcos.superapp.perms.rememberPermsResumes
import com.diegonmarcos.superapp.perms.PermsNeeds
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.PermsChip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.measure.Bands
import com.diegonmarcos.cloudcalc.measure.Baseline
import com.diegonmarcos.cloudcalc.measure.Cadence
import com.diegonmarcos.cloudcalc.measure.Formulas
import com.diegonmarcos.cloudcalc.measure.Screen
import com.diegonmarcos.cloudcalc.measure.Sensors
import com.diegonmarcos.cloudcalc.measure.Units
import com.diegonmarcos.cloudcalc.measure.Vibration
import com.diegonmarcos.cloudcalc.sensors.SensorDecl
import com.diegonmarcos.cloudcalc.sensors.SensorHub
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The test tags the Sensors screens are driven by. */
object SensorTags {
    fun card(id: String) = "sensor_card_$id"
    fun absent(id: String) = "sensor_absent_$id"
    fun allow(id: String) = "sensor_allow_$id"
    fun value(id: String) = "sensor_value_$id"
    const val ALL_LIST = "sensor_all_list"
    const val COMPASS_HEADING = "compass_heading"
    const val SEISMO_RMS = "seismo_rms"
    const val METAL_DELTA = "metal_delta"
    const val RULER = "ruler_canvas"
    const val PROTRACTOR = "protractor_canvas"
    const val PROTRACTOR_ANGLE = "protractor_angle"
}

private fun show(v: Double, decimals: Int): String = if (v.isNaN()) "—" else Sensors.format(v, decimals)

/** Feeds the newest values of [d] into [latest] while [active]; it draws nothing. */
@Composable
private fun SensorFeed(d: Sensors.Decl, active: Boolean, latest: SnapshotStateMap<String, DoubleArray>) {
    val ctx = LocalContext.current
    DisposableEffect(d.id, active) {
        val stop: () -> Unit = if (active) SensorHub.listen(ctx, d) { latest[d.id] = it } else ({})
        onDispose { stop() }
    }
}

// ── the sensor groups: one card per declared sensor ───────────────────────────────────────────────

@Composable
fun SensorGroupMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cat = SensorDecl.catalog
    val group = remember(mode.id) { JSONObject(mode.spec).getString("group") }
    val sensors = remember(group) { cat.inGroup(group) }
    val latest = remember(mode.id) { mutableStateMapOf<String, DoubleArray>() }
    // #1515 a grant is made in Configs > Perms; the screen re-reads it each time it resumes.
    val resumes = rememberPermsResumes()
    fun isGranted(d: Sensors.Decl) = resumes >= 0 && SensorHub.granted(ctx, d)
    // Every sensor of the group is fed from here, not from its card, so a card scrolled out of view still keeps its derived neighbours current.
    sensors.forEach { d -> SensorFeed(d, SensorHub.present(ctx, d) && isGranted(d), latest) }
    // A derived value may need a sensor of another group (never fed otherwise).
    val outside = remember(group) {
        sensors.flatMap { s -> s.derived.flatMap { it.needs } }.distinct().filter { id -> sensors.none { it.id == id } }.mapNotNull { cat.sensor(it) }
    }
    outside.forEach { d -> SensorFeed(d, SensorHub.present(ctx, d) && isGranted(d), latest) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(CalcMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap),
    ) {
        items(sensors, key = { it.id }) { d -> SensorCard(d, cat, latest, isGranted(d)) }
    }
}

@Composable
private fun SensorCard(d: Sensors.Decl, cat: Sensors.Catalog, latest: SnapshotStateMap<String, DoubleArray>, granted: Boolean) {
    val ctx = LocalContext.current
    val present = remember(d.id) { SensorHub.present(ctx, d) }
    var asked by remember(d.id) { mutableStateOf(false) }
    var unit by rememberSaveable(d.id) { mutableStateOf(d.unit) }
    Card(Modifier.fillMaxWidth().testTag(SensorTags.card(d.id))) {
        Column(Modifier.padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
            Text(d.label, style = MaterialTheme.typography.titleSmall)
            when {
                !present -> Text(stringResource(R.string.sensor_absent), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(SensorTags.absent(d.id)))
                !granted -> {
                    Text(stringResource(if (asked) R.string.sensor_denied else R.string.sensor_perm_why))
                    OutlinedButton(onClick = { asked = true; PermsLink.open(ctx, "sensors", PermsNeeds.idsFor(ctx, listOf(SensorHub.permissionOf(d.permission)))) }, modifier = Modifier.testTag(SensorTags.allow(d.id))) {
                        Text(PermsChip.TEXT)
                    }
                }
                else -> {
                    if (d.units.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                        d.units.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) }
                    }
                    Readings(d, cat, unit, latest)
                }
            }
        }
    }
}

@Composable
private fun Readings(d: Sensors.Decl, cat: Sensors.Catalog, unit: String, latest: SnapshotStateMap<String, DoubleArray>) {
    val raw = latest[d.id]
    if (raw == null) {
        Text(stringResource(R.string.sensor_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val v = d.scaled(raw)
    fun axis(i: Int): String {
        val x = v[i]
        if (x.isNaN()) return "—"
        val u = d.unitOfAxis(i)
        val onChosen = u == d.unit && unit != d.unit
        val shown = if (onChosen) Units.convert(cat.conversions, x, u, unit) ?: x else x
        val label = if (onChosen) unit else u
        return Sensors.format(shown, d.decimalsOfAxis(i)) + if (label.isEmpty()) "" else " $label"
    }
    when (d.display) {
        "scalar" -> {
            val none = v.isEmpty() || v[0].isNaN()
            Text(
                if (none) stringResource(R.string.sensor_not_reported) else axis(0),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(SensorTags.value(d.id)),
            )
        }
        "state" -> Text(show(v.firstOrNull() ?: Double.NaN, 0), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(SensorTags.value(d.id)))
        "vector", "rotation", "gnss" -> d.axes.forEachIndexed { i, a -> if (i < v.size) Fact(a, axis(i)) }
        "event" -> EventReadout(d, raw)
        "trigger" -> TriggerReadout(raw)
        "presence" -> Text(
            stringResource(if ((v.firstOrNull() ?: 0.0) > 0.5) R.string.sensor_nfc_on else R.string.sensor_nfc_off),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(SensorTags.value(d.id)),
        )
    }
    d.derived.forEach { dv -> DerivedRow(dv, v, cat, latest) }
}

@Composable
private fun DerivedRow(dv: Sensors.Derived, own: DoubleArray, cat: Sensors.Catalog, latest: SnapshotStateMap<String, DoubleArray>) {
    val ctx = LocalContext.current
    val lacking = dv.needs.firstOrNull { id -> cat.sensor(id)?.let { !SensorHub.present(ctx, it) } ?: true }
    if (lacking != null) {
        Fact(dv.label, stringResource(R.string.sensor_needs, cat.sensor(lacking)?.label ?: lacking))
        return
    }
    val r = Sensors.derive(cat, dv, own) { id -> latest[id]?.let { raw -> cat.sensor(id)?.scaled(raw) } }
    val words = r?.words
    val text = when {
        r == null -> "—"
        words != null && dv.unit.isEmpty() -> words
        else -> Sensors.format(r.number, dv.decimals) + (if (dv.unit.isEmpty()) "" else " ${dv.unit}") + (words?.let { " · $it" } ?: "")
    }
    Fact(dv.label, text)
}

@Composable
private fun EventReadout(d: Sensors.Decl, raw: DoubleArray) {
    var count by remember(d.id) { mutableIntStateOf(0) }
    val times = remember(d.id) { mutableStateListOf<Long>() }
    var now by remember(d.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(raw) {
        count++
        times.add(System.currentTimeMillis())
        while (times.size > 60) times.removeAt(0)
    }
    LaunchedEffect(d.id) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Text(count.toString(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(SensorTags.value(d.id)))
    Fact(stringResource(R.string.sensor_events_since), count.toString())
    Cadence.perMinute(times.toList(), now)?.let { Fact(stringResource(R.string.sensor_cadence), stringResource(R.string.sensor_cadence_value, it)) }
}

@Composable
private fun TriggerReadout(raw: DoubleArray) {
    var count by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(raw) {
        count++
        last = DateFormat.getTimeInstance().format(Date())
    }
    Fact(stringResource(R.string.sensor_events_since), count.toString())
    Fact(stringResource(R.string.sensor_last), last)
}

// ── All sensors: SensorManager's own list ─────────────────────────────────────────────────────────

@Composable
fun SensorAllMode(@Suppress("UNUSED_PARAMETER") mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cat = SensorDecl.catalog
    val list = remember { SensorHub.all(ctx) }
    if (list.isEmpty()) {
        Text(stringResource(R.string.sensor_all_none), Modifier.padding(CalcMetrics.gutter))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.sensor_all_count, list.size), Modifier.padding(CalcMetrics.gutter), style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(SensorTags.ALL_LIST),
            contentPadding = PaddingValues(horizontal = CalcMetrics.gutter, vertical = CalcMetrics.small),
            verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap),
        ) {
            items(list, key = { "${it.type}/${it.name}/${it.id}" }) { s ->
                val known = cat.sensors.firstOrNull { it.source == "sensor" && it.androidType == s.type }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.hairline)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.sensor_all_props, s.vendor, s.stringType, "%.4g".format(s.maximumRange), "%.4g".format(s.resolution), "%.3g".format(s.power)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            known?.let { stringResource(R.string.sensor_all_in, it.group) } ?: stringResource(R.string.sensor_all_vendor),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── tools ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SensorCompassMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cat = SensorDecl.catalog
    val spec = remember(mode.id) { JSONObject(mode.spec) }
    val gravity = cat.sensor(spec.getString("gravity"))?.takeIf { SensorHub.present(ctx, it) }
        ?: cat.sensor(spec.getString("accelerometer"))?.takeIf { SensorHub.present(ctx, it) }
    val field = cat.sensor(spec.getString("field"))?.takeIf { SensorHub.present(ctx, it) }
    if (gravity == null || field == null) {
        Text(stringResource(R.string.compass_missing), Modifier.padding(CalcMetrics.gutter), color = MaterialTheme.colorScheme.error)
        return
    }
    val latest = remember(mode.id) { mutableStateMapOf<String, DoubleArray>() }
    SensorFeed(gravity, true, latest)
    SensorFeed(field, true, latest)
    val g = latest[gravity.id]
    val m = latest[field.id]
    val heading = if (g != null && m != null && g.size >= 3 && m.size >= 3) Formulas.heading(g[0], g[1], g[2], m[0], m[1], m[2]) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Text(
            heading?.let { "%.0f° %s".format(it, Bands.label(cat.bands[spec.getString("band")].orEmpty(), it).orEmpty()) } ?: stringResource(R.string.compass_waiting),
            style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag(SensorTags.COMPASS_HEADING),
        )
        val ink = MaterialTheme.colorScheme.onSurface
        val north = MaterialTheme.colorScheme.error
        Canvas(Modifier.fillMaxWidth().height(CalcMetrics.plotHeight)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2.2f
            drawCircle(ink, r, c, style = Stroke(CalcMetrics.hairline.toPx()))
            if (heading != null) rotate(-heading.toFloat(), c) {
                drawLine(north, c, Offset(c.x, c.y - r), strokeWidth = CalcMetrics.stroke.toPx() * 2)
                drawLine(ink, c, Offset(c.x, c.y + r), strokeWidth = CalcMetrics.stroke.toPx())
            }
        }
        if (m != null) Fact(stringResource(R.string.compass_field), "%.1f µT".format(Formulas.norm(field.scaled(m))))
        Text(stringResource(R.string.compass_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SensorSeismoMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cat = SensorDecl.catalog
    val spec = remember(mode.id) { JSONObject(mode.spec) }
    val d = cat.sensor(spec.getString("sensor"))?.takeIf { SensorHub.present(ctx, it) }
    if (d == null) {
        Text(stringResource(R.string.seismo_missing), Modifier.padding(CalcMetrics.gutter), color = MaterialTheme.colorScheme.error)
        return
    }
    val winSize = spec.getInt("window")
    val latest = remember(mode.id) { mutableStateMapOf<String, DoubleArray>() }
    SensorFeed(d, true, latest)
    val window = remember(mode.id) { Vibration.Window(winSize) }
    var stats by remember(mode.id) { mutableStateOf<Vibration.Stats?>(null) }
    val trace = remember(mode.id) { mutableStateListOf<Float>() }
    val raw = latest[d.id]
    LaunchedEffect(raw) {
        if (raw == null) return@LaunchedEffect
        val mag = Formulas.norm(d.scaled(raw))
        window.add(mag)
        val st = window.stats()
        stats = st
        if (st != null) trace.add((mag - st.mean).toFloat())
        while (trace.size > winSize) trace.removeAt(0)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        val st = stats
        Text(
            st?.let { Bands.label(cat.bands[spec.getString("band")].orEmpty(), it.rms).orEmpty() } ?: stringResource(R.string.sensor_waiting),
            style = MaterialTheme.typography.displaySmall,
        )
        if (st != null) {
            Fact(stringResource(R.string.seismo_rms), "%.3f m/s²".format(st.rms))
            Fact(stringResource(R.string.seismo_peak), "%.3f m/s²  (%.1f mg)".format(st.peak, st.peak / Formulas.STANDARD_GRAVITY * 1000))
            LinearProgressIndicator(progress = { (st.rms / 3.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().testTag(SensorTags.SEISMO_RMS))
        }
        val line = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(CalcMetrics.spectrumHeight)) {
            if (trace.size < 2) return@Canvas
            val peak = trace.maxOf { abs(it) }.coerceAtLeast(0.05f)
            val path = Path()
            trace.forEachIndexed { i, y ->
                val px = i * size.width / (winSize - 1)
                val py = size.height / 2 - y / peak * size.height / 2
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, line, style = Stroke(CalcMetrics.stroke.toPx()))
        }
        OutlinedButton(onClick = { window.clear(); trace.clear(); stats = null }) { Text(stringResource(R.string.seismo_reset)) }
        Text(stringResource(R.string.seismo_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SensorMetalMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cat = SensorDecl.catalog
    val spec = remember(mode.id) { JSONObject(mode.spec) }
    val d = cat.sensor(spec.getString("sensor"))?.takeIf { SensorHub.present(ctx, it) }
    if (d == null) {
        Text(stringResource(R.string.metal_missing), Modifier.padding(CalcMetrics.gutter), color = MaterialTheme.colorScheme.error)
        return
    }
    val latest = remember(mode.id) { mutableStateMapOf<String, DoubleArray>() }
    SensorFeed(d, true, latest)
    val need = spec.getInt("baseline_samples")
    val baseline = remember(mode.id) { Baseline(need) }
    var seen by remember(mode.id) { mutableIntStateOf(0) }
    val raw = latest[d.id]
    val now = raw?.let { Formulas.norm(d.scaled(it)) }
    LaunchedEffect(raw) {
        if (now != null && !baseline.ready) { baseline.add(now); seen = minOf(seen + 1, need) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        val delta = now?.let { baseline.delta(it) }
        Text(
            when {
                now == null -> stringResource(R.string.sensor_waiting)
                delta == null -> stringResource(R.string.metal_calibrating, seen, need)
                else -> Bands.label(cat.bands[spec.getString("band")].orEmpty(), abs(delta)).orEmpty()
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        if (now != null) Fact(stringResource(R.string.metal_field), "%.1f µT".format(now))
        baseline.value?.let { Fact(stringResource(R.string.metal_baseline), "%.1f µT".format(it)) }
        if (delta != null) {
            Fact(stringResource(R.string.metal_delta), "%+.1f µT".format(delta))
            LinearProgressIndicator(progress = { (abs(delta) / 100.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().testTag(SensorTags.METAL_DELTA))
        }
        OutlinedButton(onClick = { baseline.reset(); seen = 0 }) { Text(stringResource(R.string.metal_reset)) }
    }
}

@Composable
fun RulerMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val units = remember(mode.id) { JSONObject(mode.spec).getJSONArray("units").let { a -> (0 until a.length()).map { a.getString(it) } } }
    var unit by rememberSaveable(mode.id) { mutableStateOf(units.first()) }
    val dpi = ctx.resources.displayMetrics.ydpi.toDouble()
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ink, fontSize = 11.sp)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().testTag(SensorTags.RULER)) {
            val inch = unit == "in"
            val stepMm = if (inch) Screen.MM_PER_INCH / 16.0 else 1.0
            val perMajor = if (inch) 16 else 10
            val perMid = if (inch) 8 else 5
            var n = 0
            while (true) {
                val y = Screen.mmToPx(n * stepMm, dpi).toFloat()
                if (y > size.height) break
                val len = when { n % perMajor == 0 -> 44.dp.toPx(); n % perMid == 0 -> 30.dp.toPx(); else -> 18.dp.toPx() }
                drawLine(ink, Offset(0f, y), Offset(len, y), strokeWidth = CalcMetrics.hairline.toPx())
                if (n % perMajor == 0 && n > 0) {
                    drawText(measurer, (n / perMajor).toString(), Offset(len + 6.dp.toPx(), y - 7.dp.toPx()), style = labelStyle)
                }
                n++
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
            units.forEach { u ->
                FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(if (u == "in") stringResource(R.string.ruler_unit_in) else stringResource(R.string.ruler_unit_mm)) })
            }
        }
        Text(stringResource(R.string.ruler_hint), Modifier.align(Alignment.BottomEnd).padding(CalcMetrics.gap), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProtractorMode(@Suppress("UNUSED_PARAMETER") mode: Declarations.Mode) {
    var angle by rememberSaveable { mutableStateOf(45.0) }
    val ink = MaterialTheme.colorScheme.onSurface
    val arm = MaterialTheme.colorScheme.primary
    // The base line is the 0 and 180 degree line; a point below it snaps to the nearer end.
    val place = { p: Offset, box: IntSize ->
        val a = Screen.angleDeg(box.width / 2.0, box.height * 0.9, p.x.toDouble(), p.y.toDouble())
        angle = if (a <= 180.0) a else if (a > 270.0) 0.0 else 180.0
    }
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter), verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Text("%.1f°".format(angle), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag(SensorTags.PROTRACTOR_ANGLE))
        Fact(stringResource(R.string.protractor_supplement), "%.1f°".format(180.0 - angle))
        Canvas(
            Modifier.weight(1f).fillMaxWidth().testTag(SensorTags.PROTRACTOR)
                .pointerInput(Unit) { detectTapGestures { place(it, size) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> place(change.position, size) } },
        ) {
            val c = Offset(size.width / 2f, size.height * 0.9f)
            val r = minOf(size.width / 2f, size.height * 0.85f)
            drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = CalcMetrics.stroke.toPx())
            drawArc(ink, 180f, 180f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(CalcMetrics.hairline.toPx()))
            for (deg in 0..180 step 10) {
                val rad = Math.toRadians(deg.toDouble())
                val inner = if (deg % 30 == 0) r * 0.88f else r * 0.94f
                drawLine(
                    ink,
                    Offset(c.x + (inner * cos(rad)).toFloat(), c.y - (inner * sin(rad)).toFloat()),
                    Offset(c.x + (r * cos(rad)).toFloat(), c.y - (r * sin(rad)).toFloat()),
                    strokeWidth = CalcMetrics.hairline.toPx(),
                )
            }
            val rad = Math.toRadians(angle)
            drawLine(arm, c, Offset(c.x + (r * cos(rad)).toFloat(), c.y - (r * sin(rad)).toFloat()), strokeWidth = CalcMetrics.stroke.toPx() * 2)
        }
        Text(stringResource(R.string.protractor_hint), style = MaterialTheme.typography.bodySmall)
    }
}

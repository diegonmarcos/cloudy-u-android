package com.diegonmarcos.cloudtoolssmallllms.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.ConnState
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedFilter
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedProfile
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedTree
import com.diegonmarcos.cloudtoolssmallllms.core.HardwareItem
import com.diegonmarcos.cloudtoolssmallllms.core.LiveReading
import com.diegonmarcos.cloudtoolssmallllms.core.Num
import com.diegonmarcos.cloudtoolssmallllms.core.Presence
import com.diegonmarcos.cloudtoolssmallllms.core.ResolvedCategory
import com.diegonmarcos.cloudtoolssmallllms.core.ResolvedEntry
import com.diegonmarcos.cloudtoolssmallllms.core.ResolvedProfile
import com.diegonmarcos.cloudtoolssmallllms.core.SensorCatalogue
import com.diegonmarcos.cloudtoolssmallllms.core.SensorFilter
import com.diegonmarcos.cloudtoolssmallllms.core.SensorRow
import com.diegonmarcos.cloudtoolssmallllms.core.Uuid
import com.diegonmarcos.cloudtoolssmallllms.data.AndroidConnectedFacts
import com.diegonmarcos.cloudtoolssmallllms.data.AndroidDeviceFacts
import com.diegonmarcos.cloudtoolssmallllms.data.AndroidRegistrar
import com.diegonmarcos.cloudtoolssmallllms.data.ScanMemory
import com.diegonmarcos.cloudtoolssmallllms.data.AndroidSensorSource
import com.diegonmarcos.cloudtoolssmallllms.data.BleFound
import com.diegonmarcos.cloudtoolssmallllms.data.BleScan
import com.diegonmarcos.cloudtoolssmallllms.data.ConnectedGrants
import com.diegonmarcos.cloudtoolssmallllms.data.GattLine
import com.diegonmarcos.cloudtoolssmallllms.data.GattSession
import com.diegonmarcos.cloudtoolssmallllms.data.GattStatus
import com.diegonmarcos.cloudtoolssmallllms.data.HardwareProbe
import com.diegonmarcos.cloudtoolssmallllms.data.UsbFound
import com.diegonmarcos.cloudtoolssmallllms.data.UsbList
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine

/**
 * Sensors ([page] = Inbuilt or Connected): the phone's own sensors and other hardware, or the declared external sensors. Every name,
 * type, profile and UUID is the tools/sensors dataset (core SensorCatalogue); the rows are the Cloud Store's dense rows (ui-kit KitStoreRow).
 */
@Composable
fun SensorsPage(page: String, json: String?) {
    val loaded = remember(json) { runCatching { SensorCatalogue.parse(json ?: error("not served yet by Cloud Store")) } }
    val cat = loaded.getOrNull()
    if (cat == null) {
        // A missing or invalid sensors.json must never take the app down: the page says why, and nothing is logged.
        Text(stringResource(R.string.sensors_unavailable, loaded.exceptionOrNull()?.message.orEmpty()), fontSize = KitStoreRow.meta, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV))
        return
    }
    if (page == Decl.PAGE_SENSORS_CONNECTED) ConnectedPage(cat) else InbuiltPage(cat)
}

/** Runs [start] while the screen is visible (the host is started) and [stop] when it is hidden or this leaves the composition: the listener lifecycle. */
@Composable
fun WhileStarted(key: Any?, start: () -> Unit, stop: () -> Unit) {
    val owner = LocalContext.current.lifecycleOwner()
    val startNow by rememberUpdatedState(start)
    val stopNow by rememberUpdatedState(stop)
    DisposableEffect(key, owner) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_START) startNow() else if (event == Lifecycle.Event.ON_STOP) stopNow()
            }
        }
        if (owner == null) startNow() else owner.lifecycle.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); stopNow() }
    }
}

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.lifecycleOwner()
    else -> null
}

private val RULE_MODIFIER = Modifier.fillMaxWidth()

@Composable
private fun Rule() { Box(RULE_MODIFIER.height(ToolsMetrics.hairline).background(KitStoreRow.RULE)) }

@Composable
private fun Heading(text: String, tag: String? = null) {
    KitStoreCaption(text, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).let { if (tag != null) it.testTag(tag) else it })
}

// ── Inbuilt ──────────────────────────────────────────────────────────────────

/** A labelled row of single-choice chips (one filter or switch); the chosen one is filled. */
@Composable
private fun <T> Chips(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        item { KitStoreCaption(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(options) { (text, v) -> KitStoreQuick(text, if (v == selected) KitStoreRow.BLUE_FILL else KitStoreRow.RULE, onClick = { onSelect(v) }) }
    }
}

private fun kindText(kind: String): Int = when (kind) { "derived" -> R.string.kind_derived; "feature" -> R.string.kind_feature; else -> R.string.kind_hardware }

/** The Inbuilt page: the declared sensor catalogue resolved on THIS phone, organized by category or as one filterable list. */
@Composable
private fun InbuiltPage(cat: SensorCatalogue) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val rows = remember { sm?.let { cat.rows(AndroidSensorSource(it)) }.orEmpty() }
    var refresh by remember { mutableIntStateOf(0) }
    val probe = remember { HardwareProbe(ctx, rows) }
    val resolved = remember(rows, refresh) { cat.tree.resolve(rows, AndroidDeviceFacts(ctx, probe, cat.tree)) }
    var byList by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(SensorFilter()) }
    var open by remember { mutableStateOf<Int?>(null) }
    var openEntry by remember { mutableStateOf<String?>(null) }
    val shown = remember(resolved, filter) { cat.tree.filter(resolved, filter) }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SENSORS_INBUILT)) {
        item(key = "h_catalogue") {
            KitStoreRowLine(onClick = { refresh++ }) {
                Column(Modifier.weight(1f)) { Heading(stringResource(R.string.catalogue_heading, resolved.sumOf { it.presentCount }, resolved.sumOf { it.entries.size }, rows.size)) }
                KitStoreQuick(stringResource(R.string.sensors_refresh), KitStoreRow.BLUE_FILL)
            }
        }
        item(key = "views") { Chips(stringResource(R.string.f_view), listOf(stringResource(R.string.view_category) to false, stringResource(R.string.view_list) to true), byList) { byList = it } }
        if (!byList) {
            for (c in resolved) {
                item(key = "c_" + c.id) { Heading(stringResource(R.string.category_count, c.label, c.presentCount, c.entries.size)) }
                items(c.entries, key = { "e_" + it.entry.id }) { e -> EntryRow(e, cat, sm, open, { open = it }, openEntry, { openEntry = it }) }
            }
        } else {
            item(key = "filters") { FilterBar(cat, resolved, rows, filter) { filter = it } }
            item(key = "shown") { Heading(stringResource(R.string.list_count, shown.size)) }
            items(shown, key = { "l_" + it.entry.id }) { e -> EntryRow(e, cat, sm, open, { open = it }, openEntry, { openEntry = it }) }
        }
    }
}

@Composable
private fun FilterBar(cat: SensorCatalogue, resolved: List<ResolvedCategory>, rows: List<SensorRow>, f: SensorFilter, set: (SensorFilter) -> Unit) {
    val all = stringResource(R.string.f_all)
    Chips(stringResource(R.string.f_category), listOf<Pair<String, String?>>(all to null) + resolved.map { it.label to it.id }, f.category) { set(f.copy(category = it)) }
    Chips(stringResource(R.string.f_presence), listOf(all to Presence.ALL, stringResource(R.string.f_present) to Presence.PRESENT, stringResource(R.string.f_absent) to Presence.ABSENT), f.presence) { set(f.copy(presence = it)) }
    Chips(stringResource(R.string.f_type), listOf<Pair<String, String?>>(all to null, stringResource(R.string.kind_hardware) to "hardware", stringResource(R.string.kind_derived) to "derived", stringResource(R.string.kind_feature) to "feature"), f.kind) { set(f.copy(kind = it)) }
    Chips(stringResource(R.string.f_reporting), listOf<Pair<String, Int?>>(all to null) + (0..3).map { cat.modeOf(it) to it }, f.reporting) { set(f.copy(reporting = it)) }
    Chips(stringResource(R.string.f_wake), listOf<Pair<String, Boolean?>>(all to null, stringResource(R.string.wake_yes) to true, stringResource(R.string.wake_no) to false), f.wake) { set(f.copy(wake = it)) }
    Chips(stringResource(R.string.f_power), listOf<Pair<String, Float?>>(all to null, stringResource(R.string.power_01) to 0.1f, stringResource(R.string.power_1) to 1f, stringResource(R.string.power_5) to 5f), f.maxPowerMa) { set(f.copy(maxPowerMa = it)) }
    Chips(stringResource(R.string.f_vendor), listOf<Pair<String, String?>>(all to null) + cat.tree.vendors(rows).map { it to it }, f.vendor) { set(f.copy(vendor = it)) }
}

/** One catalogue member: present or absent on this phone, its detail lines, and the SensorManager sensors that answer it (tap one for the live graph). */
@Composable
private fun EntryRow(e: ResolvedEntry, cat: SensorCatalogue, sm: SensorManager?, open: Int?, onOpen: (Int?) -> Unit, openEntry: String?, onOpenEntry: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val gps = e.entry.id == "gps" && e.present
    Column(Modifier.testTag(Tags.catalogueRow(e.entry.id))) {
        KitStoreRowLine(onClick = if (gps) ({ onOpenEntry(if (openEntry == e.entry.id) null else e.entry.id) }) else null) {
            KitStoreDot(stringResource(if (e.present) R.string.hw_yes else R.string.hw_no), if (e.present) KitStoreRow.UP else KitStoreRow.BAD)
            Column(Modifier.weight(1f)) {
                KitStoreName(e.entry.label, color = scheme.onSurface)
                KitStoreCaption(stringResource(R.string.entry_line, stringResource(kindText(e.entry.kind)), stringResource(if (e.present) R.string.entry_present else R.string.entry_absent)), color = scheme.onSurfaceVariant)
                if (e.entry.deprecated) KitStoreCaption(stringResource(R.string.entry_deprecated), color = scheme.onSurfaceVariant)
                if (e.present && e.entry.liveOnly) KitStoreCaption(stringResource(R.string.entry_live_only), color = scheme.onSurfaceVariant)
                e.entry.note?.let { Text(it, fontSize = KitStoreRow.caption, color = scheme.onSurfaceVariant) }
                for (l in e.lines) Text(l, fontSize = KitStoreRow.caption, color = scheme.onSurfaceVariant)
                if (gps && openEntry == e.entry.id) GnssLive(cat)
            }
        }
        for (r in e.sensors) SensorRowItem(r, cat, sm, expanded = open == r.raw.id) { onOpen(if (open == r.raw.id) null else r.raw.id) }
        Rule()
    }
}

@Composable
private fun SensorRowItem(r: SensorRow, cat: SensorCatalogue, sm: SensorManager?, expanded: Boolean, onClick: () -> Unit) {
    val s = r.raw
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.testTag(Tags.sensorRow(s.id))) {
        KitStoreRowLine(onClick = onClick) {
            KitStoreDot(stringResource(if (expanded) R.string.sensor_dot_open else R.string.sensor_dot), scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                KitStoreName(s.name, color = scheme.onSurface)
                val no = stringResource(R.string.no); val yes = stringResource(R.string.yes)
                KitStoreCaption(stringResource(R.string.sensor_vendor_line, s.vendor, s.version, r.mode), color = scheme.onSurfaceVariant)
                KitStoreCaption(stringResource(R.string.sensor_type_line, r.typeConst, r.typeLabel, s.type), color = scheme.onSurfaceVariant)
                KitStoreCaption(stringResource(R.string.sensor_numbers_line, Num.short(s.powerMa), Num.short(s.resolution), Num.short(s.maxRange)), color = scheme.onSurfaceVariant)
                KitStoreCaption(stringResource(R.string.sensor_delay_line, s.minDelayUs.toString(), s.maxDelayUs.toString()), color = scheme.onSurfaceVariant)
                KitStoreCaption(stringResource(R.string.sensor_flags_line, if (s.wakeUp) yes else no, s.fifoMax, if (s.dynamic) yes else no), color = scheme.onSurfaceVariant)
            }
        }
        if (expanded && sm != null) LivePanel(s.id, cat, sm)
        Rule()
    }
}

/** The live graph and values of one sensor: the listener (SENSOR_DELAY_UI) exists only while this is visible. */
@Composable
private fun LivePanel(sensorId: Int, cat: SensorCatalogue, sm: SensorManager) {
    var tick by remember { mutableIntStateOf(0) }
    val live = remember(sensorId) { LiveReading(AndroidRegistrar(sm), cat.history) { tick++ } }
    WhileStarted(sensorId, start = { live.show(sensorId) }, stop = { live.hide() })
    tick.let { }   // read: every sample redraws the graph
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(KitStoreRow.UP, KitStoreRow.UPDATE, KitStoreRow.MISSING, KitStoreRow.BLUE_FILL, KitStoreRow.AMBER_FILL, KitStoreRow.BAD)
    Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(Tags.SENSOR_LIVE)) {
        val samples = live.samples
        when {
            !live.supported -> KitStoreCaption(stringResource(R.string.sensor_live_refused), color = scheme.error)
            samples.isEmpty() -> KitStoreCaption(stringResource(R.string.sensor_live_waiting), color = scheme.onSurfaceVariant)
            else -> {
                KitStoreCaption(stringResource(R.string.sensor_live_values, live.last.joinToString(" · ") { Num.short(it) }), color = scheme.onSurface, mono = true)
                val series = cat.maxSeries
                val (lo, hi) = live.range(series)
                val stroke = ToolsMetrics.hairline
                Canvas(Modifier.fillMaxWidth().height(ToolsMetrics.graph).padding(vertical = ToolsMetrics.small)) {
                    val w = size.width; val h = size.height
                    val step = w / (cat.history - 1).coerceAtLeast(1)
                    val x0 = w - step * (samples.size - 1)
                    for (i in 0 until minOf(series, samples.maxOf { it.size })) {
                        val path = Path()
                        samples.forEachIndexed { n, v ->
                            if (i < v.size) {
                                val p = Offset(x0 + step * n, h - (v[i] - lo) / (hi - lo) * h)
                                if (n == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            }
                        }
                        drawPath(path, palette[i % palette.size], style = Stroke(width = stroke.toPx()))
                    }
                }
            }
        }
    }
}

/** The live GNSS status while this is visible: per constellation the satellites visible and used in the fix and the mean C/N0, and the carrier bands seen; needs the location grant from Configs > Perms. */
@SuppressLint("MissingPermission")
@Composable
private fun GnssLive(cat: SensorCatalogue) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (!ConnectedGrants.location(ctx)) {
        KitStoreCaption(stringResource(R.string.hw_gnss_need_perm), color = scheme.error)
        return
    }
    var sats by remember { mutableStateOf<GnssSummary?>(null) }
    val lm = remember { ctx.getSystemService(LocationManager::class.java) }
    val callback = remember {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val per = java.util.TreeMap<Int, FloatArray>()   // type -> [visible, used, cn0 sum]
                val bands = java.util.TreeSet<String>()
                for (i in 0 until status.satelliteCount) {
                    val a = per.getOrPut(status.getConstellationType(i)) { FloatArray(3) }
                    a[0] += 1f; if (status.usedInFix(i)) a[1] += 1f; a[2] += status.getCn0DbHz(i)
                    if (status.hasCarrierFrequencyHz(i)) cat.gnss.band(status.getCarrierFrequencyHz(i)).takeIf { it.isNotEmpty() }?.let { bands += it }
                }
                sats = GnssSummary(per.mapValues { Triple(it.value[0].toInt(), it.value[1].toInt(), if (it.value[0] > 0f) it.value[2] / it.value[0] else 0f) }, bands)
            }
        }
    }
    var registered by remember { mutableStateOf(false) }
    WhileStarted(callback, start = {
        if (!registered) registered = runCatching { lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper())) }.getOrDefault(false)
    }, stop = {
        if (registered) { runCatching { lm.unregisterGnssStatusCallback(callback) }; registered = false }
    })
    val s = sats
    if (s == null) { KitStoreCaption(stringResource(R.string.hw_gnss_waiting), color = scheme.onSurface); return }
    KitStoreCaption(stringResource(R.string.hw_gnss_status, s.perConstellation.values.sumOf { it.first }, s.perConstellation.values.sumOf { it.second }), color = scheme.onSurface)
    for ((t, v) in s.perConstellation) KitStoreCaption(stringResource(R.string.hw_gnss_constellation, cat.gnss.constellations[t] ?: cat.gnss.constellations.values.last(), v.first, v.second, Num.short(v.third)), color = scheme.onSurfaceVariant)
    if (s.bands.isNotEmpty()) KitStoreCaption(stringResource(R.string.hw_gnss_bands, s.bands.joinToString(", ")), color = scheme.onSurfaceVariant)
}

private data class GnssSummary(val perConstellation: Map<Int, Triple<Int, Int, Float>>, val bands: Set<String>)

// ── Connected ────────────────────────────────────────────────────────────────

private fun transportText(ctx: Context, id: String): String {
    val ids = ctx.resources.getStringArray(R.array.transport_ids); val labels = ctx.resources.getStringArray(R.array.transport_labels)
    return labels.getOrElse(ids.indexOf(id)) { id }
}

private fun stateText(s: ConnState): Int = when (s) {
    ConnState.CONNECTED -> R.string.conn_state_connected; ConnState.PAIRED -> R.string.conn_state_paired
    ConnState.AVAILABLE -> R.string.conn_state_available; ConnState.ABSENT -> R.string.conn_state_absent
}

/** The Connected page: the declared external-sensor catalogue resolved on THIS phone (connected, paired, available or absent), by category or as one filterable list. */
@Composable
private fun ConnectedPage(cat: SensorCatalogue) {
    val ctx = LocalContext.current
    val facts = remember { AndroidConnectedFacts(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    val resolved = remember(tick) { ConnectedTree.resolve(cat.connected, facts) }
    var byList by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ConnectedFilter()) }
    var open by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    val scanAll = remember { BleScan(ctx, cat.connected.scanTimeoutMs, { f -> ScanMemory.remember(f) }, { scanning = false; tick++ }) }
    WhileStarted(scanAll, start = {}, stop = { scanAll.stop(notify = false); scanning = false })
    val shown = remember(resolved, filter) { ConnectedTree.filter(resolved, filter) }
    val labelOf = { id: String -> cat.connected.categories.firstOrNull { it.first == id }?.second ?: id }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SENSORS_CONNECTED)) {
        item(key = "h_connected") {
            KitStoreRowLine(onClick = {
                note = null
                if (!ConnectedGrants.ble(ctx)) note = R.string.scan_need_perms
                else if (!scanAll.startAll()) note = R.string.scan_no_bt
                else scanning = true
            }) {
                Column(Modifier.weight(1f)) {
                    Heading(stringResource(R.string.connected_heading, resolved.count { it.state != ConnState.ABSENT }, resolved.size))
                    note?.let { KitStoreCaption(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    if (scanning) KitStoreCaption(stringResource(R.string.scan_scanning, cat.connected.scanTimeoutMs / 1000), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                KitStoreQuick(stringResource(R.string.scan_all), KitStoreRow.BLUE_FILL, Modifier.testTag(Tags.SCAN_ALL))
            }
        }
        item(key = "views") { Chips(stringResource(R.string.f_view), listOf(stringResource(R.string.view_category) to false, stringResource(R.string.view_list) to true), byList) { byList = it } }
        if (!byList) {
            for ((c, members) in ConnectedTree.grouped(cat.connected, resolved)) {
                item(key = "c_" + c.first) { Heading(stringResource(R.string.category_count, c.second, members.count { it.state != ConnState.ABSENT }, members.size)) }
                items(members, key = { "p_" + it.profile.id }) { r -> ProfileRow(r, labelOf(r.profile.category), cat, expanded = open == r.profile.id) { open = r.profile.id } }
            }
        } else {
            item(key = "filters") {
                val all = stringResource(R.string.f_all)
                Chips(stringResource(R.string.f_category), listOf<Pair<String, String?>>(all to null) + cat.connected.categories.map { it.second to it.first }, filter.category) { filter = filter.copy(category = it) }
                Chips(stringResource(R.string.f_transport), listOf<Pair<String, String?>>(all to null) + ConnectedTree.transports(cat.connected).map { transportText(ctx, it) to it }, filter.transport) { filter = filter.copy(transport = it) }
                Chips(stringResource(R.string.f_presence), listOf(all to Presence.ALL, stringResource(R.string.f_present) to Presence.PRESENT, stringResource(R.string.f_absent) to Presence.ABSENT), filter.present) { filter = filter.copy(present = it) }
            }
            item(key = "shown") { Heading(stringResource(R.string.list_count, shown.size)) }
            items(shown, key = { "l_" + it.profile.id }) { r -> ProfileRow(r, labelOf(r.profile.category), cat, expanded = open == r.profile.id) { open = r.profile.id } }
        }
    }
}

@Composable
private fun ProfileRow(r: ResolvedProfile, categoryLabel: String, cat: SensorCatalogue, expanded: Boolean, onScan: () -> Unit) {
    val p = r.profile
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val ids = listOfNotNull(
        p.uuid?.let { stringResource(R.string.profile_uuid, Uuid.short(it)) }, p.usbClass?.let { stringResource(R.string.profile_usb, it) },
        p.feature?.let { stringResource(R.string.profile_feature, it) }, p.packageName?.let { stringResource(R.string.profile_package, it) },
    ).joinToString(" · ")
    val dot = when (r.state) { ConnState.CONNECTED -> KitStoreRow.UP; ConnState.PAIRED -> KitStoreRow.UPDATE; ConnState.AVAILABLE -> KitStoreRow.AMBER_FILL; ConnState.ABSENT -> KitStoreRow.BAD }
    Column(Modifier.testTag(Tags.profileRow(p.id))) {
        KitStoreRowLine {
            KitStoreDot(stringResource(if (r.state == ConnState.ABSENT) R.string.hw_no else R.string.hw_yes), dot)
            Column(Modifier.weight(1f)) {
                KitStoreName(p.label, color = scheme.onSurface)
                KitStoreCaption(stringResource(R.string.conn_state_line, stringResource(stateText(r.state)), p.transport.joinToString(" · ") { transportText(ctx, it) }), color = scheme.onSurfaceVariant)
                KitStoreCaption(stringResource(R.string.profile_line, categoryLabel, p.connection, ids), color = scheme.onSurfaceVariant)
                Text(p.typicalUse, fontSize = KitStoreRow.caption, color = scheme.onSurfaceVariant)
            }
            if (p.ble || p.usb) KitStoreQuick(stringResource(R.string.scan_action), KitStoreRow.BLUE_FILL, Modifier.testTag(Tags.profileScan(p.id)), onClick = onScan)
        }
        if (expanded) ScanPanel(p, cat)
        Rule()
    }
}

/** The scan of one profile, started by the tap that opened it: BLE by its service UUID for [BleScan]'s timeout, the USB devices listed; GATT reads of the standard profiles. */
@Composable
private fun ScanPanel(p: ConnectedProfile, cat: SensorCatalogue) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val timeout = cat.connected.scanTimeoutMs
    var ble by remember(p.id) { mutableStateOf(listOf<BleFound>()) }
    var usb by remember(p.id) { mutableStateOf(listOf<UsbFound>()) }
    var scanning by remember(p.id) { mutableStateOf(false) }
    var note by remember(p.id) { mutableStateOf<Int?>(null) }
    var status by remember(p.id) { mutableStateOf<GattStatus?>(null) }
    var lines by remember(p.id) { mutableStateOf(listOf<GattLine>()) }
    var session by remember(p.id) { mutableStateOf<GattSession?>(null) }
    val scan = remember(p.id) { BleScan(ctx, timeout, { f -> ScanMemory.remember(f); ble = ble.filter { it.address != f.address } + f }, { scanning = false }) }

    fun startScan() {
        note = null; ble = emptyList()
        if (p.usb) usb = UsbList.read(ctx)
        if (p.ble) {
            if (!ConnectedGrants.ble(ctx)) note = R.string.scan_need_perms
            else if (!scan.start(p)) note = R.string.scan_no_bt
            else scanning = true
        }
    }
    fun stopAll() { scan.stop(); session?.close(); session = null }
    var begun by remember(p.id) { mutableStateOf(false) }
    // The tap that opened this panel is the scan's start; coming back from the background never rescans by itself.
    WhileStarted(p.id, start = { if (!begun) { begun = true; startScan() } }, stop = { stopAll() })

    Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
        note?.let { KitStoreCaption(stringResource(it), color = scheme.error) }
        if (scanning) KitStoreCaption(stringResource(R.string.scan_scanning, timeout / 1000), color = scheme.onSurfaceVariant)
        else KitStoreQuick(stringResource(R.string.scan_again), KitStoreRow.BLUE_FILL, onClick = { startScan() })
        if (p.ble && p.reads.isNotEmpty()) KitStoreCaption(stringResource(R.string.gatt_tap), color = scheme.onSurfaceVariant)
        for (f in ble) {
            val matches = cat.connected.bleMatches(f.serviceUuids, f.manufacturerData).joinToString { it.label }
            val shown = if (matches.isEmpty()) stringResource(R.string.scan_no_match) else stringResource(R.string.scan_matches, matches)
            KitStoreRowLine(onClick = if (p.reads.isEmpty()) null else ({
                scan.stop(); session?.close(); lines = emptyList()
                session = GattSession(ctx, f.device, p, cat.gattKeys, timeout, { status = it }, { lines = it })
            })) {
                Text(stringResource(R.string.scan_ble_found, f.name ?: f.address, f.rssi, shown), fontSize = KitStoreRow.meta, color = scheme.onSurface)
            }
        }
        for (u in usb) {
            val matches = cat.connected.usbMatches(u.classes).joinToString { it.label }
            val shown = if (matches.isEmpty()) stringResource(R.string.scan_no_match) else stringResource(R.string.scan_matches, matches)
            KitStoreRowLine {
                Text(stringResource(R.string.scan_usb_found, u.product ?: u.name, u.vidPid, u.classes.sorted().joinToString("/"), shown), fontSize = KitStoreRow.meta, color = scheme.onSurface)
            }
        }
        if (!scanning && ble.isEmpty() && usb.isEmpty() && note == null) KitStoreCaption(stringResource(if (p.usb && !p.ble) R.string.usb_none else R.string.scan_none), color = scheme.onSurfaceVariant)
        status?.let { KitStoreCaption(stringResource(gattText(it)), color = scheme.onSurfaceVariant) }
        for (l in lines) KitStoreCaption("${l.label}: ${l.value}", color = scheme.onSurface, mono = true)
    }
}

private fun gattText(s: GattStatus): Int = when (s) {
    GattStatus.CONNECTING -> R.string.gatt_connecting
    GattStatus.DISCOVERING -> R.string.gatt_discovering
    GattStatus.READING -> R.string.gatt_reading
    GattStatus.CLOSED -> R.string.gatt_closed
    GattStatus.FAILED -> R.string.gatt_failed
}

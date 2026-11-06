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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedProfile
import com.diegonmarcos.cloudtoolssmallllms.core.HardwareItem
import com.diegonmarcos.cloudtoolssmallllms.core.LiveReading
import com.diegonmarcos.cloudtoolssmallllms.core.Num
import com.diegonmarcos.cloudtoolssmallllms.core.SensorCatalogue
import com.diegonmarcos.cloudtoolssmallllms.core.SensorRow
import com.diegonmarcos.cloudtoolssmallllms.core.Uuid
import com.diegonmarcos.cloudtoolssmallllms.data.AndroidRegistrar
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

@Composable
private fun InbuiltPage(cat: SensorCatalogue) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val rows = remember { sm?.let { cat.rows(AndroidSensorSource(it)) }.orEmpty() }
    var open by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val probe = remember { HardwareProbe(ctx, rows) }
    var openHw by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SENSORS_INBUILT)) {
        item(key = "h_sensors") { Heading(stringResource(R.string.sensors_heading, rows.size)) }
        items(rows, key = { it.raw.id }) { r ->
            SensorRowItem(r, cat, sm, expanded = open == r.raw.id) { open = if (open == r.raw.id) null else r.raw.id }
        }
        item(key = "h_hardware") {
            KitStoreRowLine(onClick = { refresh++ }) {
                Column(Modifier.weight(1f)) { Heading(stringResource(R.string.hardware_heading)) }
                KitStoreQuick(stringResource(R.string.sensors_refresh), KitStoreRow.BLUE_FILL)
            }
        }
        items(cat.hardware, key = { "hw_" + it.id }) { h -> HardwareRowItem(h, probe, refresh, expanded = openHw == h.id) { openHw = if (openHw == h.id) null else h.id } }
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

@Composable
private fun HardwareRowItem(h: HardwareItem, probe: HardwareProbe, refresh: Int, expanded: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val reading = remember(h.id, refresh) { probe.read(h) }
    Column(Modifier.testTag(Tags.hardwareRow(h.id))) {
        KitStoreRowLine(onClick = onClick) {
            KitStoreDot(stringResource(if (reading.available) R.string.hw_yes else R.string.hw_no), if (reading.available) KitStoreRow.UP else KitStoreRow.BAD)
            Column(Modifier.weight(1f)) {
                KitStoreName(h.label, color = scheme.onSurface)
                KitStoreCaption(stringResource(if (reading.available) R.string.hw_available else R.string.hw_missing), color = scheme.onSurfaceVariant)
                for (l in reading.lines) Text(l, fontSize = KitStoreRow.caption, color = scheme.onSurfaceVariant)
                if (expanded && h.probe == "gnss" && reading.available) GnssLive()
            }
        }
        Rule()
    }
}

/** The GNSS status (satellites visible and used in the fix) while this is visible; needs the location grant from Configs > Perms. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
private fun GnssLive() {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (!ConnectedGrants.location(ctx)) {
        KitStoreCaption(stringResource(R.string.hw_gnss_need_perm), color = scheme.error)
        return
    }
    var sats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val lm = remember { ctx.getSystemService(LocationManager::class.java) }
    val callback = remember {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = status.satelliteCount to (0 until status.satelliteCount).count { status.usedInFix(it) }
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
    KitStoreCaption(if (s == null) stringResource(R.string.hw_gnss_waiting) else stringResource(R.string.hw_gnss_status, s.first, s.second), color = scheme.onSurface)
}

// ── Connected ────────────────────────────────────────────────────────────────

@Composable
private fun ConnectedPage(cat: SensorCatalogue) {
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SENSORS_CONNECTED)) {
        items(cat.connected.profiles, key = { it.id }) { p ->
            ProfileRow(p, cat, expanded = open == p.id) { open = p.id }
        }
    }
}

@Composable
private fun ProfileRow(p: ConnectedProfile, cat: SensorCatalogue, expanded: Boolean, onScan: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ids = listOfNotNull(p.uuid?.let { stringResource(R.string.profile_uuid, Uuid.short(it)) }, p.usbClass?.let { stringResource(R.string.profile_usb, it) }).joinToString(" · ")
    Column(Modifier.testTag(Tags.profileRow(p.id))) {
        KitStoreRowLine {
            Column(Modifier.weight(1f)) {
                KitStoreName(p.label, color = scheme.onSurface)
                KitStoreCaption(stringResource(R.string.profile_line, p.category, p.connection, ids), color = scheme.onSurfaceVariant)
                Text(p.typicalUse, fontSize = KitStoreRow.caption, color = scheme.onSurfaceVariant)
            }
            KitStoreQuick(stringResource(R.string.scan_action), KitStoreRow.BLUE_FILL, Modifier.testTag(Tags.profileScan(p.id)), onClick = onScan)
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
    val scan = remember(p.id) { BleScan(ctx, timeout, { f -> ble = ble.filter { it.address != f.address } + f }, { scanning = false }) }

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

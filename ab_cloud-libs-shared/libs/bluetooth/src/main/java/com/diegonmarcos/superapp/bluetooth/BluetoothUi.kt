package com.diegonmarcos.superapp.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uiicons.UiIcons

/** The tabs of the Bluetooth page (#1221; Analyze and Locate #1264). [id] is the page id suffix in build.json (`bluetooth-find`, ...). */
enum class BtTab(val id: String, val label: String) {
    Find("find", "Find"), Analyze("analyze", "Analyze"), Locate("locate", "Locate"), Connect("connect", "Connect"), Interact("interact", "Interact"),
    Debug("debug", "Debug"), Dev("dev", "Dev");

    companion object { fun of(id: String?): BtTab = entries.firstOrNull { it.id == id } ?: Find }
}

internal val BtOk = Color(0xFF7FC98F)
internal val BtWarn = Color(0xFFE2B85C)
internal val BtBad = Color(0xFFE5737A)

/**
 * One tab of the page over the shared [controller]. [onOpenTab] lets a tab send the user on (Find: a picked device goes to Connect).
 * The body is a LazyColumn, so the host's scroll sink (KitPage) sees it scroll.
 */
@Composable
fun BluetoothPanel(controller: BleController, tab: BtTab, modifier: Modifier = Modifier, onOpenTab: (BtTab) -> Unit = {}) {
    val s by controller.state.collectAsState()
    // #1666 a scan or classic inquiry the page started ends when the page leaves or the app goes to the background.
    BtLifecycle.Scope(controller)
    DisposableEffect(controller) { controller.refreshAdapter(); onDispose { } }
    LazyColumn(modifier.fillMaxSize().testTag("bluetooth:${tab.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("gate") { PermissionGate(controller, s) }
        when (tab) {
            BtTab.Find -> findTab(controller, s, onOpenTab)
            BtTab.Analyze -> analyzeTab(controller, s, onOpenTab)
            BtTab.Locate -> locateTab(controller, s)
            BtTab.Connect -> connectTab(controller, s, onOpenTab)
            BtTab.Interact -> interactTab(controller, s)
            BtTab.Debug -> debugTab(controller, s)
            BtTab.Dev -> devTab(controller, s)
        }
        item("end") { Spacer(Modifier.height(24.dp)) }
    }
}

// ---- shared bits ----

@Composable
internal fun BtCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
internal fun BtTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun BtMuted(text: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(text, modifier, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (mono) FontFamily.Monospace else null)
}

@Composable
internal fun BtPill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
    Text(text, (if (onClick != null) m.clickable { onClick() } else m).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 10.sp, color = color)
}

@Composable
internal fun BtChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (selected) 0.22f else 0.08f)).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 11.sp, color = c,
    )
}

@Composable
internal fun BtButton(label: String, enabled: Boolean = true, primary: Boolean = false, tag: String = label, onClick: () -> Unit) {
    if (primary) Button(onClick, Modifier.testTag("bt:$tag"), enabled = enabled) { Text(label, fontSize = 12.sp) }
    else OutlinedButton(onClick, Modifier.testTag("bt:$tag"), enabled = enabled, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(label, fontSize = 12.sp) }
}

@Composable
internal fun BtIcon(name: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 18) {
    Icon(UiIcons.vector(name), null, Modifier.size(size.dp), tint = tint)
}

@Composable
internal fun BtRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Runtime permissions and the adapter switch: the row every tab starts with while something is missing. */
@Composable
internal fun PermissionGate(controller: BleController, s: BleState) {
    val ctx = LocalContext.current
    var missing by remember { mutableStateOf(BlePermissions.missing(ctx)) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.refreshAdapter() }
    // Re-read while something is missing: the grant happens on the Permissions page, not here.
    DisposableEffect(Unit) { missing = BlePermissions.missing(ctx); onDispose { } }
    androidx.compose.runtime.LaunchedEffect(missing.isEmpty()) {
        while (missing.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            val now = BlePermissions.missing(ctx)
            if (now != missing) { missing = now; controller.refreshAdapter() }
        }
    }
    if (missing.isNotEmpty()) {
        // #1274 the lib never asks: the banner opens the app's Permissions page on its entries.
        val feature = if (android.os.Build.VERSION.SDK_INT >= 31) "bluetooth" else "bluetooth-legacy"
        val need = remember(missing) { com.diegonmarcos.superapp.perms.PermsNeeds.missing(ctx, feature) }
        BtCard(Modifier.testTag("bluetooth:perms-banner")) {
            BtTitle("Permissions needed")
            BtMuted(if (need.isNotEmpty()) com.diegonmarcos.superapp.perms.PermsNeeds.labels(need) else BlePermissions.describe(missing))
            BtButton("Open Permissions", primary = true) {
                com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "Bluetooth", need.map { it.id }.ifEmpty { listOf("bt_scan", "bt_connect", "fine_location") })
            }
        }
    } else if (!s.supported) {
        BtCard { BtTitle("No Bluetooth LE on this device"); BtMuted("Nothing can be scanned or connected.") }
    } else if (!s.enabled) {
        BtCard {
            BtTitle("Bluetooth is off")
            BtButton("Turn on", primary = true) { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
    }
}

// ---- Find ----

private fun androidx.compose.foundation.lazy.LazyListScope.findTab(c: BleController, s: BleState, open: (BtTab) -> Unit) {
    item("scan") {
        BtCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtButton(if (s.scanning) "Stop scan" else "Scan", primary = true, tag = "scan") { if (s.scanning) c.stopScan() else c.startScan() }
                BtMuted(if (s.scanning) "Scanning, ${s.seen.size} seen" else "${s.seen.size} seen")
            }
            if (s.scanError.isNotEmpty()) BtPill(s.scanError, BtBad)
            BtRow { c.catalog.filters.forEach { f -> BtChoice(f.label, s.query.filterId == f.id) { c.setQuery(s.query.copy(filterId = f.id)) } } }
            OutlinedTextField(
                s.query.nameContains, { c.setQuery(s.query.copy(nameContains = it)) }, Modifier.fillMaxWidth().testTag("bt:name-filter"),
                singleLine = true, label = { Text("Name contains", fontSize = 11.sp) },
            )
            Text("Signal floor ${s.query.minRssi} dBm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(s.query.minRssi.toFloat(), { c.setQuery(s.query.copy(minRssi = it.toInt())) }, valueRange = -100f..-30f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.query.connectableOnly, { c.setQuery(s.query.copy(connectableOnly = it)) }); Spacer(Modifier.width(6.dp)); BtMuted("Connectable only")
                Spacer(Modifier.width(14.dp))
                Switch(s.query.hideUnnamed, { c.setQuery(s.query.copy(hideUnnamed = it)) }); Spacer(Modifier.width(6.dp)); BtMuted("Hide unnamed")
            }
        }
    }
    val shown = c.visible(s)
    item("count") { BtTitle("Devices (${shown.size})") }
    if (shown.isEmpty()) item("empty") { BtMuted(if (s.scanning) "Nothing matches yet." else "Press Scan to look for devices.") }
    items(shown, key = { it.address }) { d -> DeviceRow(c, d, s.selected == d.address) { c.select(d.address); open(BtTab.Connect) } }
    val bondedOnly = s.bonded.filter { b -> shown.none { it.address == b.address } }
    if (bondedOnly.isNotEmpty()) {
        item("bonded") { BtTitle("Paired (${bondedOnly.size})") }
        items(bondedOnly, key = { "b" + it.address }) { d -> DeviceRow(c, d, s.selected == d.address) { c.select(d.address); open(BtTab.Connect) } }
    }
}

@Composable
private fun DeviceRow(c: BleController, d: BleDevice, selected: Boolean, onPick: () -> Unit) {
    BtCard(Modifier.clickable { onPick() }.testTag("bt:device:${d.address}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtIcon(if (selected) "bluetooth_connected" else "ic_bluetooth", if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                BtMuted(d.address, mono = true)
            }
            if (d.rssi > -127) {
                val bars = BleFilters.bars(d.rssi)
                Column(horizontalAlignment = Alignment.End) {
                    Text("$bars/4", fontSize = 11.sp, color = if (bars >= 3) BtOk else if (bars >= 1) BtWarn else BtBad)
                    BtMuted("${d.rssi} dBm")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (d.bonded) BtPill("paired", BtOk)
            if (d.classic) BtPill("classic", BtWarn)
            if (!d.connectable) BtPill("not connectable", BtBad)
            d.txPower?.let { BtPill("tx $it dBm", MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (d.serviceUuids.isNotEmpty()) BtMuted(d.serviceUuids.joinToString(", ") { c.catalog.service(it)?.name ?: Uuids.short(it) })
        if (d.manufacturer.isNotEmpty()) BtMuted(d.manufacturer.entries.joinToString("; ") { "0x%04X: %s".format(it.key, it.value) }, mono = true)
    }
}

// ---- Connect ----

private fun androidx.compose.foundation.lazy.LazyListScope.connectTab(c: BleController, s: BleState, open: (BtTab) -> Unit) {
    val d = s.selectedDevice
    item("banner") {
        BtCard(Modifier.testTag("bt:banner")) {
            if (d == null) {
                BtTitle("No device selected")
                BtMuted("Pick one in Find.")
                BtButton("Go to Find") { open(BtTab.Find) }
            } else {
                val connected = s.conn == ConnState.Connected
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { BtTitle(d.label); BtMuted(d.address, mono = true) }
                    BtPill(s.conn.name.uppercase(), when (s.conn) { ConnState.Connected -> BtOk; ConnState.Disconnected -> BtBad; else -> BtWarn })
                    BtPill("bond " + GattSession.bondName(if (s.bondState != GattSession.BOND_NONE) s.bondState else if (d.bonded) GattSession.BOND_BONDED else GattSession.BOND_NONE),
                        if (d.bonded || s.bondState == GattSession.BOND_BONDED) BtOk else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val uuids = (d.serviceUuids + s.services.map { it.uuid }).distinct()
                val family = c.catalog.deviceCategory(uuids)
                KeyValue("Detected type", c.catalog.categoryLabel(family))
                KeyValue("Battery", s.values.entries.firstOrNull { Uuids.assigned(it.key.substringAfter('/')) == 0x2A19 }?.value?.decoded?.text ?: "--")
                KeyValue("RSSI", (s.rssi ?: d.rssi.takeIf { it > -127 })?.let { "$it dBm" } ?: "--")
                KeyValue("MTU", "${s.mtu} bytes")
                KeyValue("Manufacturer", s.values.entries.firstOrNull { Uuids.assigned(it.key.substringAfter('/')) == 0x2A29 }?.value?.decoded?.text
                    ?: d.manufacturer.keys.firstOrNull()?.let { "company 0x%04X".format(it) } ?: "--")
                if (s.busy.isNotEmpty()) BtMuted(s.busy + "...")
                if (s.message.isNotEmpty()) BtPill(s.message, BtWarn)
                BtRow {
                    if (connected || s.conn == ConnState.Connecting) BtButton("Disconnect", tag = "disconnect") { c.disconnect() }
                    else BtButton("Connect", primary = true, enabled = d.connectable, tag = "connect") { c.connect() }
                    BtButton("Bond", enabled = !d.bonded && s.bondState != GattSession.BOND_BONDED) { c.bond() }
                    BtButton("Reload services", enabled = connected) { c.discover() }
                    BtButton("Read RSSI", enabled = connected) { c.readRssi() }
                }
                BtMuted("Request MTU")
                BtRow { listOf(23, 64, 185, 247, 517).forEach { m -> BtChoice("$m", s.mtu == m) { if (connected) c.requestMtu(m) } } }
                if (connected) BtButton("Open GATT tree") { open(BtTab.Interact) }
            }
        }
    }
    if (d != null && s.services.isNotEmpty()) {
        item("summary") {
            BtCard {
                BtTitle("Services (${s.services.size})")
                s.services.forEach { sv -> BtMuted("${c.catalog.serviceName(sv.uuid)}  ${Uuids.short(sv.uuid)}  (${sv.characteristics.size} characteristics)") }
            }
        }
    }
}

@Composable
internal fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BtMuted(k)
        Text(v, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- Debug ----

@Composable
internal fun rememberLog(log: BleLog): List<LogEntry> {
    var snap by remember { mutableStateOf(log.snapshot()) }
    DisposableEffect(log) {
        val l: () -> Unit = { snap = log.snapshot() }
        log.onChange(l)
        onDispose { log.removeOnChange(l) }
    }
    return snap
}

private fun androidx.compose.foundation.lazy.LazyListScope.debugTab(c: BleController, s: BleState) {
    item("state") {
        BtCard {
            BtTitle("State")
            KeyValue("Adapter", if (!s.supported) "none" else if (s.enabled) "on" else "off")
            KeyValue("Scan", if (s.scanning) "running" else "idle")
            KeyValue("Device", s.selectedDevice?.let { "${it.label} ${it.address}" } ?: "--")
            KeyValue("Connection", s.conn.name)
            KeyValue("MTU / RSSI", "${s.mtu} / ${s.rssi ?: "--"}")
            KeyValue("Notifying", s.notifying.size.toString())
        }
    }
    item("console") { LogConsole(c.log) }
}

@Composable
private fun LogConsole(log: BleLog) {
    val ctx = LocalContext.current
    val all = rememberLog(log)
    var kinds by remember { mutableStateOf(LogKind.entries.toSet()) }
    val shown = all.filter { it.kind in kinds }
    BtCard(Modifier.testTag("bt:console")) {
        BtTitle("Bluetooth ATT event log (${shown.size})")
        BtRow {
            LogKind.entries.forEach { k -> BtChoice(k.name, k in kinds) { kinds = if (k in kinds) kinds - k else kinds + k } }
        }
        BtRow {
            BtButton("Clear") { log.clear() }
            BtButton("Copy") {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("bluetooth log", log.export(kinds)))
            }
            BtButton("Export") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log.export(kinds))
                ctx.startActivity(Intent.createChooser(send, "Export Bluetooth log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (shown.isEmpty()) BtMuted("No traffic yet.")
        // Newest first, so the latest line is under the controls without scrolling to the end.
        shown.asReversed().take(200).forEach { e ->
            Text(BleLog.line(e), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = when (e.kind) { LogKind.Error -> BtBad; LogKind.Tx -> BtWarn; LogKind.Rx, LogKind.Notify -> BtOk; LogKind.Sys -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
    }
}

// ---- Dev ----

private fun androidx.compose.foundation.lazy.LazyListScope.devTab(c: BleController, s: BleState) {
    item("raw") { RawWrite(c, s) }
    item("notify") {
        val connected = s.conn == ConnState.Connected
        val chars = s.services.flatMap { it.characteristics }
        BtCard {
            BtTitle("Notify stream")
            BtRow {
                BtButton("Subscribe all", enabled = connected && chars.any { it.canNotify || it.canIndicate }, tag = "subscribe-all") {
                    chars.filter { it.canNotify }.forEach { c.setNotify(it, NotifyMode.Notify) }
                    chars.filter { !it.canNotify && it.canIndicate }.forEach { c.setNotify(it, NotifyMode.Indicate) }
                }
                BtButton("Unsubscribe all", enabled = connected && s.notifying.isNotEmpty()) { chars.filter { it.key in s.notifying }.forEach { c.setNotify(it, NotifyMode.Off) } }
                BtButton("Read all", enabled = connected) { c.readAll() }
            }
            val entries = rememberLog(c.log).filter { it.kind == LogKind.Notify }.asReversed().take(30)
            if (entries.isEmpty()) BtMuted("No notifications yet.")
            entries.forEach { Text(BleLog.line(it), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = BtOk) }
        }
    }
    item("perm") { PermissionMatrix() }
    item("env") {
        BtCard {
            BtTitle("Runtime")
            BtMuted("Native Android BLE: full GATT discovery with no service allow-list, MTU up to 517, bonding, and a log of every ATT step.")
            BtMuted("Cloud Browser exposes the same stack to pages as navigator.bluetooth (Web Bluetooth) behind a native device chooser.")
            BtMuted("Not covered: raw HCI snoop and passive advertising capture (system level, adb bugreport).")
        }
    }
}

@Composable
private fun RawWrite(c: BleController, s: BleState) {
    val writable = s.services.flatMap { it.characteristics }.filter { it.canWrite }
    var pick by remember { mutableStateOf<String?>(null) }
    var hex by remember { mutableStateOf("") }
    var withResponse by remember { mutableStateOf(true) }
    val target = writable.firstOrNull { it.key == pick } ?: preferredWrite(c.catalog, writable)
    BtCard(Modifier.testTag("bt:raw")) {
        BtTitle("Raw hex write")
        if (writable.isEmpty()) BtMuted(if (s.conn == ConnState.Connected) "No writable characteristic on this device." else "Connect first.")
        else {
            BtRow { writable.forEach { w -> BtChoice(c.catalog.characteristicName(w.uuid).takeIf { it != "Unknown characteristic" } ?: Uuids.short(w.uuid), w.key == target?.key) { pick = w.key } } }
            OutlinedTextField(hex, { hex = it }, Modifier.fillMaxWidth().testTag("bt:hex"), singleLine = true, label = { Text("Hex bytes, e.g. AB 00 04 31", fontSize = 11.sp) })
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(withResponse, { withResponse = it }); Spacer(Modifier.width(6.dp)); BtMuted("With response") }
            BtButton("Transmit", primary = true, enabled = target != null && hex.isNotBlank()) { target?.let { c.writeHex(it, hex, withResponse) } }
            if (s.message.isNotEmpty()) BtMuted(s.message)
        }
        BtTitle("Test payloads")
        c.catalog.probes.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { hex = p.hex }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface); BtMuted(p.note) }
                BtMuted(p.hex, mono = true)
            }
        }
    }
}

/** The characteristic a quick action writes to: a vendor / UART one when the catalog knows the family, else the first writable. */
internal fun preferredWrite(catalog: GattCatalog, writable: List<GattCharacteristic>): GattCharacteristic? =
    writable.firstOrNull { catalog.categoryOf(it.serviceUuid) in setOf("ring", "uart") } ?: writable.firstOrNull()

@Composable
private fun PermissionMatrix() {
    val ctx = LocalContext.current
    BtCard {
        BtTitle("Permissions")
        BlePermissions.required().forEach { p ->
            val ok = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BtPill(if (ok) "granted" else "missing", if (ok) BtOk else BtBad); BtMuted(p.substringAfterLast('.'))
            }
        }
        BtMuted("Grant them (or all at once over adb) on the Permissions page.")
        BtButton("Open Permissions") { com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "Bluetooth", listOf("bt_scan", "bt_connect", "bt_advertise", "fine_location")) }
    }
}

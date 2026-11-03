package com.diegonmarcos.cloudcalc.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.measure.LfCommands
import com.diegonmarcos.cloudcalc.measure.LfLibrary
import com.diegonmarcos.cloudcalc.measure.LfParse
import com.diegonmarcos.cloudcalc.measure.LfReader
import com.diegonmarcos.cloudcalc.measure.LfSerial
import com.diegonmarcos.cloudcalc.measure.UsbChip
import com.diegonmarcos.cloudcalc.measure.LfTag
import com.diegonmarcos.cloudcalc.measure.LfType
import com.diegonmarcos.cloudcalc.measure.Em4100
import com.diegonmarcos.superapp.perms.PermsNeeds
import com.diegonmarcos.superapp.perms.PermsUsb
import com.diegonmarcos.superapp.perms.rememberPermsAsk
import org.json.JSONObject

// #1407 Measure > LF-RFID-Tags. The phone's NFC radio is 13.56 MHz only, so 125/134 kHz tags go through a USB-OTG reader.
// LfLink opens a reader over USB (CDC-ACM, CH340, CP210x: :measure's LfSerial init transfers), Bluetooth SPP or BLE UART (Nordic UART);
// it is process-wide so a tab switch keeps the connection. The maths and parsing are :measure's LfRfid.kt (JVM-tested, PIT-mutated).

/** One open link to a reader: USB serial, Bluetooth SPP or BLE UART. Bytes in arrive through LfLink.onBytes. */
internal interface LfTransport { fun write(b: ByteArray); fun close() }

internal class UsbTransport(private val c: UsbDeviceConnection, private val out: UsbEndpoint, inp: UsbEndpoint) : LfTransport {
    @Volatile private var open = true
    init {
        Thread {
            val b = ByteArray(512)
            while (open) { val n = c.bulkTransfer(inp, b, b.size, 300); if (n > 0) LfLink.onBytes(b.copyOf(n)) }
        }.apply { isDaemon = true; start() }
    }
    override fun write(b: ByteArray) { Thread { c.bulkTransfer(out, b, b.size, 1000) }.start() }
    override fun close() { open = false; c.close() }
}

@SuppressLint("MissingPermission")
internal class SppTransport(private val s: BluetoothSocket) : LfTransport {
    init {
        Thread {
            val b = ByteArray(512)
            runCatching { while (true) { val n = s.inputStream.read(b); if (n < 0) break; if (n > 0) LfLink.onBytes(b.copyOf(n)) } }
            LfLink.status = "Bluetooth link closed"
        }.apply { isDaemon = true; start() }
    }
    override fun write(b: ByteArray) { Thread { runCatching { s.outputStream.write(b); s.outputStream.flush() } }.start() }
    override fun close() { runCatching { s.close() } }
}

@SuppressLint("MissingPermission")
internal class BleTransport(ctx: Context, d: BluetoothDevice, private val onReady: (Boolean) -> Unit) : LfTransport {
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val queue = java.util.ArrayDeque<ByteArray>()
    private var busy = false
    private val gatt: BluetoothGatt = d.connectGatt(ctx, false, object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247) else if (state == BluetoothProfile.STATE_DISCONNECTED) LfLink.status = "Bluetooth link closed"
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu = m; g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(LfSerial.NUS_SERVICE) ?: run { onReady(false); return }
            rx = svc.getCharacteristic(LfSerial.NUS_RX)
            val tx = svc.getCharacteristic(LfSerial.NUS_TX)
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(LfSerial.CCCD)?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(it) }
            onReady(rx != null)
        }
        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { ch.value?.let { LfLink.onBytes(it) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { synchronized(queue) { busy = false }; pump() }
    })
    private fun pump() {
        val next = synchronized(queue) { if (busy) null else queue.pollFirst()?.also { busy = true } } ?: return
        val c = rx ?: return
        c.value = next; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION") if (!gatt.writeCharacteristic(c)) synchronized(queue) { busy = false }
    }
    override fun write(b: ByteArray) { synchronized(queue) { LfSerial.chunks(b, mtu).forEach { queue.addLast(it) } }; pump() }
    override fun close() { runCatching { gatt.disconnect(); gatt.close() } }
}

/** A reader the tab can connect to: a USB device or a paired Bluetooth device. */
data class LfEndpoint(val label: String, val reader: LfReader, val usb: UsbDevice? = null, val bt: BluetoothDevice? = null)

object LfLink {
    var connected by mutableStateOf<LfEndpoint?>(null); private set
    var reader by mutableStateOf<LfReader?>(null); private set
    var status by mutableStateOf("No reader attached")
    var lastTag by mutableStateOf<LfTag?>(null)
    val log = mutableStateListOf<String>()
    val library = mutableStateListOf<LfTag>()
    private var link: LfTransport? = null
    private val buf = StringBuilder()
    private var raw = ByteArray(0)

    private fun bulkPair(i: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val eps = (0 until i.endpointCount).map { i.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val inp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val out = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return inp to out
    }

    @SuppressLint("MissingPermission")
    fun endpoints(ctx: Context, btAllowed: Boolean): List<LfEndpoint> {
        val usb = runCatching {
            ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.filter { d -> (0 until d.interfaceCount).any { bulkPair(d.getInterface(it)) != null } }
        }.getOrNull().orEmpty().map { d ->
            val r = LfSerial.reader(d.vendorId, d.productId, d.productName)
            LfEndpoint("USB ${r.label} (${LfSerial.chip(d.vendorId).name})", r, usb = d)
        }
        val bt = if (!btAllowed) emptyList() else runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().map { d ->
                LfEndpoint("Bluetooth ${d.name ?: d.address}", LfSerial.reader(0, 0, d.name).let { if (it == LfReader.FLIPPER) LfReader.EM_SERIAL else it }, bt = d)
            }
        }.getOrNull().orEmpty()
        return usb + bt
    }

    fun connect(ctx: Context, e: LfEndpoint) {
        e.usb?.let { d -> if (!PermsUsb.granted(ctx, d)) { status = "Asking for USB permission"; PermsUsb.request(ctx, d) { ok -> if (ok) { connect(ctx, e) } else { status = "USB permission denied" } }; return } }
        disconnect()
        status = "Connecting to ${e.label}"
        if (e.usb != null) openUsb(ctx, e) else Thread { openBt(ctx, e) }.start()
    }

    private fun openUsb(ctx: Context, e: LfEndpoint) {
        val d = e.usb!!
        val c = ctx.getSystemService(UsbManager::class.java)?.openDevice(d) ?: run { status = "Could not open the reader"; return }
        val ifaces = (0 until d.interfaceCount).map { d.getInterface(it) }
        val data = ifaces.firstOrNull { bulkPair(it) != null } ?: run { c.close(); status = "No serial interface"; return }
        ifaces.forEach { c.claimInterface(it, true) }
        val chip = LfSerial.chip(d.vendorId)
        // CDC-ACM line state goes to the control (communication) interface; CP210x takes its own interface number.
        val ctrlIface = if (chip == UsbChip.CDC_ACM) (ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM } ?: data).id else data.id
        for (t in LfSerial.init(chip, LfSerial.baud(e.reader), ctrlIface)) {
            val payload = t.data.toByteArray()
            c.controlTransfer(t.type, t.request, t.value, t.index, payload.takeIf { it.isNotEmpty() }, payload.size, 500)
        }
        val (i, o) = bulkPair(data)!!
        attach(e, UsbTransport(c, o, i))
    }

    @SuppressLint("MissingPermission")
    private fun openBt(ctx: Context, e: LfEndpoint) {
        val d = e.bt!!
        if (d.type == BluetoothDevice.DEVICE_TYPE_LE) {
            var t: BleTransport? = null
            t = BleTransport(ctx, d) { ok -> if (ok) attach(e, t!!) else { t?.close(); status = "${e.label}: no BLE UART (Nordic UART) service" } }
            return
        }
        runCatching {
            val s = d.createRfcommSocketToServiceRecord(LfSerial.SPP)
            s.connect(); attach(e, SppTransport(s))
        }.onFailure { status = "${e.label}: Bluetooth serial failed (${it.message})" }
    }

    private fun attach(e: LfEndpoint, t: LfTransport) { link = t; connected = e; reader = e.reader; status = "${e.label} connected" }

    fun onBytes(b: ByteArray) {
        if (reader == LfReader.EM_SERIAL) {
            raw = (raw + b).takeLast(64).toByteArray()
            Em4100.parseRdm6300(raw)?.let { id -> lastTag = LfParse.em(id); raw = ByteArray(0); return }
        }
        synchronized(buf) {
            buf.append(String(b, Charsets.UTF_8))
            while (buf.contains('\n')) {
                val line = buf.substring(0, buf.indexOf('\n')).trimEnd('\r'); buf.delete(0, buf.indexOf('\n') + 1)
                log.add(line); if (log.size > 200) log.removeAt(0)
                LfParse.parse(log.takeLast(12).joinToString("\n"))?.let { lastTag = it }
            }
        }
    }

    fun send(cmd: String) {
        val l = link ?: run { status = "Connect a reader first"; return }
        val r = reader ?: return
        if (cmd.isEmpty()) return
        log.add("> $cmd")
        l.write((cmd + r.eol.ifEmpty { "\n" }).toByteArray())
    }

    fun disconnect() { link?.close(); link = null; connected = null; reader = null; status = "No reader attached" }

    fun load(ctx: Context) {
        if (library.isNotEmpty()) return
        prefs(ctx).getString("tags", null)?.let { runCatching { library.addAll(LfLibrary.fromJson(it)) } }
    }

    fun save(ctx: Context) = prefs(ctx).edit().putString("tags", LfLibrary.toJson(library.toList())).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud_calc_lfrfid", Context.MODE_PRIVATE)
}

@Composable
fun LfRfidMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    remember { LfLink.load(ctx.applicationContext); 0 }
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("125/134 kHz tags need an external reader: the phone's NFC radio only speaks 13.56 MHz.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReaderCard()
        when (JSONObject(mode.spec).optString("tab")) {
            "edit" -> LfEditor()
            "library" -> LfLibraryPane()
            else -> LfRead()
        }
    }
}

@Composable
private fun ReaderCard() {
    val ctx = LocalContext.current
    var btMissing by remember { mutableStateOf(PermsNeeds.missing(ctx, "bluetooth")) }
    var list by remember { mutableStateOf(LfLink.endpoints(ctx, btMissing.isEmpty())) }
    val ask = rememberPermsAsk { btMissing = PermsNeeds.missing(ctx, "bluetooth"); list = LfLink.endpoints(ctx, btMissing.isEmpty()) }
    Text(LfLink.status, style = MaterialTheme.typography.titleSmall)
    if (LfLink.connected == null) {
        if (list.isEmpty()) Text(
            "No reader found. Plug one in with a USB-OTG adapter, or pair it in Bluetooth settings:\n" +
                "- Proxmark3 (RDV4 / Easy, Iceman firmware) over USB, or its Blue Shark / HC-05 Bluetooth add-on: read, decode, clone and write T5577.\n" +
                "- Flipper Zero over USB (its Bluetooth serial speaks RPC, not the CLI): read, write and emulate EM4100 / HID.\n" +
                "- RDM6300 or a USB EM4100 reader (CDC, CH340 or CP210x serial), or a BLE UART (Nordic UART) module: read EM4100 IDs only.",
            fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { list = LfLink.endpoints(ctx, btMissing.isEmpty()) }) { Text("Refresh") }
            if (btMissing.isNotEmpty()) OutlinedButton(onClick = { ask(btMissing) }) { Text("Allow Bluetooth") }
        }
        list.forEach { e -> Button(onClick = { LfLink.connect(ctx.applicationContext, e) }) { Text("Connect ${e.label}") } }
    } else OutlinedButton(onClick = { LfLink.disconnect() }) { Text("Disconnect") }
}

@Composable
private fun TagView(t: LfTag) {
    Text("${t.type.label}  ${t.id}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    t.fields.forEach { (k, v) -> Text("$k: $v", fontSize = 13.sp) }
    t.blocks.forEachIndexed { i, b -> Text("Block $i  $b", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
}

@Composable
private fun LfRead() {
    val ctx = LocalContext.current
    val r = LfLink.reader
    if (r == LfReader.PROXMARK3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { LfLink.send(LfCommands.search(r)) }) { Text("Search") }
        OutlinedButton(onClick = { LfLink.send(LfCommands.EM_READER) }) { Text("EM") }
        OutlinedButton(onClick = { LfLink.send(LfCommands.HID_READER) }) { Text("HID") }
        OutlinedButton(onClick = { LfLink.send(LfCommands.FDX_READER) }) { Text("FDX") }
        OutlinedButton(onClick = { LfLink.send(LfCommands.T55XX_DETECT); LfLink.send(LfCommands.T55XX_DUMP) }) { Text("T5577") }
    }
    if (r == LfReader.FLIPPER) Button(onClick = { LfLink.send(LfCommands.search(r)) }) { Text("Read") }
    if (r == LfReader.EM_SERIAL) Text("Hold an EM4100 tag on the reader.", fontSize = 12.sp)
    LfLink.lastTag?.let { t ->
        TagView(t)
        Button(onClick = { LfLink.library.add(t.copy(name = "Tag ${LfLink.library.size + 1}")); LfLink.save(ctx) }) { Text("Save to library") }
    }
    LfLink.log.takeLast(12).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
}

@Composable
private fun LfEditor() {
    val r = LfLink.reader
    val blocks = remember { mutableStateListOf(*(LfLink.lastTag?.blocks?.takeIf { it.isNotEmpty() } ?: listOf("00148040", "00000000", "00000000")).toTypedArray()) }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var error by remember { mutableStateOf("") }
    fun ask(what: String, build: () -> List<String>) = runCatching { confirm = what to build() }.onFailure { error = it.message ?: "invalid" }
    if (r?.canWrite != true) Text("Writing needs a Proxmark3 (T5577) or a Flipper Zero; RDM6300-style readers are read-only.", fontSize = 12.sp)
    Text("T5577 blocks", style = MaterialTheme.typography.titleSmall)
    blocks.forEachIndexed { i, b ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(b, { blocks[i] = it.uppercase().take(8) }, Modifier.weight(1f), label = { Text("Block $i") }, singleLine = true)
            TextButton(onClick = { ask("Write block $i", build = { listOf(LfCommands.t55xxWrite(i, blocks[i], pwd.ifEmpty { null })) }) }, enabled = r == LfReader.PROXMARK3) { Text("Write") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (blocks.size < 8) OutlinedButton(onClick = { blocks.add("00000000") }) { Text("Add block") }
        OutlinedTextField(pwd, { pwd = it.uppercase().take(8) }, Modifier.weight(1f), label = { Text("Password (optional)") }, singleLine = true)
    }
    LfLink.lastTag?.takeIf { it.type != LfType.T5577 }?.let { t ->
        Text("Last read: ${t.type.label} ${t.id}", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r != null && r.canWrite) Button(onClick = { ask("Clone ${t.type.label} ${t.id} to a T5577", build = { listOf(LfCommands.clone(r, t)) }) }) { Text("Clone") }
            if (r == LfReader.FLIPPER) OutlinedButton(onClick = { ask("Emulate ${t.id}", build = { listOf(LfCommands.emulate(t)) }) }) { Text("Emulate") }
        }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    confirm?.let { (what, cmds) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("$what?") },
            text = { Text("This overwrites the tag on the reader. Sends:\n" + cmds.joinToString("\n"), fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { cmds.forEach(LfLink::send); confirm = null; error = "" }) { Text("Write") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun LfLibraryPane() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val tags = if (text.trimStart().startsWith("[")) LfLibrary.fromJson(text) else LfLibrary.fromCsv(text)
            LfLink.library.addAll(tags); LfLink.save(ctx); msg = "Imported ${tags.size} tags"
        }.onFailure { msg = "Import failed: ${it.message}" }
    }
    fun share(body: String, type: String) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_TEXT, body), "Export LF tags"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { share(LfLibrary.toJson(LfLink.library.toList()), "application/json") }) { Text("Export JSON") }
        OutlinedButton(onClick = { share(LfLibrary.toCsv(LfLink.library.toList()), "text/csv") }) { Text("Export CSV") }
        OutlinedButton(onClick = { import.launch("*/*") }) { Text("Import") }
    }
    if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
    if (LfLink.library.isEmpty()) Text("No saved tags yet. Read a tag and tap Save to library.", fontSize = 12.sp)
    LfLink.library.toList().forEachIndexed { i, t ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall)
            TagView(t)
            Row {
                TextButton(onClick = { LfLink.lastTag = t }) { Text("Load") }
                TextButton(onClick = { LfLink.library.removeAt(i); LfLink.save(ctx) }) { Text("Delete") }
            }
        }
    }
}

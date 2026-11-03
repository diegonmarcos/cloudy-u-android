package com.diegonmarcos.cloudcalc.ui

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
import com.diegonmarcos.cloudcalc.measure.LfTag
import com.diegonmarcos.cloudcalc.measure.LfType
import com.diegonmarcos.cloudcalc.measure.Em4100
import com.diegonmarcos.superapp.perms.PermsUsb
import org.json.JSONObject

// #1407 Measure > LF-RFID-Tags. The phone's NFC radio is 13.56 MHz only, so 125/134 kHz tags go through a USB-OTG reader.
// LfLink is a minimal CDC-ACM serial link (Proxmark3 and Flipper Zero are CDC-ACM; so are many EM4100 USB readers), process-wide
// so a tab switch keeps the connection. The maths and parsing are :measure's LfRfid.kt (JVM-tested, PIT-mutated).

object LfLink {
    var device by mutableStateOf<UsbDevice?>(null); private set
    var reader by mutableStateOf<LfReader?>(null); private set
    var status by mutableStateOf("No reader attached")
    var lastTag by mutableStateOf<LfTag?>(null)
    val log = mutableStateListOf<String>()
    val library = mutableStateListOf<LfTag>()
    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var reading: Thread? = null
    private val buf = StringBuilder()
    private var raw = ByteArray(0)

    fun readerOf(d: UsbDevice): LfReader = when (d.vendorId) {
        0x9AC4, 0x2D2D -> LfReader.PROXMARK3
        0x0483 -> if (d.productName?.contains("flip", true) == true || d.productId == 0x5740) LfReader.FLIPPER else LfReader.EM_SERIAL
        else -> LfReader.EM_SERIAL
    }

    fun serialDevices(ctx: Context): List<UsbDevice> = runCatching {
        ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.filter { d -> (0 until d.interfaceCount).any { bulkPair(d.getInterface(it)) != null } }
    }.getOrNull().orEmpty()

    private fun bulkPair(i: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val eps = (0 until i.endpointCount).map { i.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val inp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val out = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return inp to out
    }

    fun connect(ctx: Context, d: UsbDevice) {
        if (!PermsUsb.granted(ctx, d)) { status = "Asking for USB permission"; PermsUsb.request(ctx, d) { ok -> if (ok) { connect(ctx, d) } else { status = "USB permission denied" } }; return }
        disconnect()
        val c = ctx.getSystemService(UsbManager::class.java)?.openDevice(d) ?: run { status = "Could not open the reader"; return }
        val data = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { bulkPair(it) != null } ?: run { c.close(); status = "No serial interface"; return }
        (0 until d.interfaceCount).forEach { c.claimInterface(d.getInterface(it), true) }
        // CDC-ACM: 115200 8N1, then DTR + RTS (Proxmark3 and Flipper only talk once DTR is up).
        val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)
        c.controlTransfer(0x21, 0x20, 0, 0, coding, coding.size, 500)
        c.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 500)
        val (i, o) = bulkPair(data)!!
        conn = c; epIn = i; epOut = o; device = d; reader = readerOf(d)
        status = "${reader!!.label} connected (${d.productName ?: "USB %04X:%04X".format(d.vendorId, d.productId)})"
        reading = Thread {
            val b = ByteArray(512)
            while (conn === c) {
                val n = c.bulkTransfer(i, b, b.size, 300)
                if (n > 0) onBytes(b.copyOf(n))
            }
        }.apply { isDaemon = true; start() }
    }

    private fun onBytes(b: ByteArray) {
        if (reader == LfReader.EM_SERIAL) {
            raw = (raw + b).takeLast(64).toByteArray()
            Em4100.parseRdm6300(raw)?.let { id -> lastTag = LfParse.em(id); raw = ByteArray(0) }
            return
        }
        buf.append(String(b, Charsets.UTF_8))
        while (buf.contains('\n')) {
            val line = buf.substring(0, buf.indexOf('\n')).trimEnd('\r'); buf.delete(0, buf.indexOf('\n') + 1)
            log.add(line); if (log.size > 200) log.removeAt(0)
            LfParse.parse(log.takeLast(12).joinToString("\n"))?.let { lastTag = it }
        }
    }

    fun send(cmd: String) {
        val c = conn ?: run { status = "Connect a reader first"; return }
        val r = reader ?: return
        if (cmd.isEmpty()) return
        log.add("> $cmd")
        val bytes = (cmd + r.eol).toByteArray()
        Thread { c.bulkTransfer(epOut, bytes, bytes.size, 1000) }.start()
    }

    fun disconnect() { conn?.close(); conn = null; device = null; reader = null; status = "No reader attached" }

    fun load(ctx: Context) {
        if (library.isNotEmpty()) return
        prefs(ctx).getString("tags", null)?.let { runCatching { library.addAll(LfLibrary.fromJson(it)) } }
    }

    fun save(ctx: Context) = prefs(ctx).edit().putString("tags", LfLibrary.toJson(library.toList())).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lfrfid", Context.MODE_PRIVATE)
}

@Composable
fun LfRfidMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    remember { LfLink.load(ctx.applicationContext); 0 }
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("125/134 kHz tags need an external USB-OTG reader: the phone's NFC radio only speaks 13.56 MHz.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var devices by remember { mutableStateOf(LfLink.serialDevices(ctx)) }
    Text(LfLink.status, style = MaterialTheme.typography.titleSmall)
    if (LfLink.device == null) {
        if (devices.isEmpty()) Text(
            "No reader found. Plug one in with a USB-OTG adapter:\n" +
                "- Proxmark3 (RDV4 / Easy, Iceman firmware): read, decode, clone and write T5577.\n" +
                "- Flipper Zero (USB, CLI mode): read, write and emulate EM4100 / HID.\n" +
                "- RDM6300 or a USB EM4100 reader (CDC serial): read EM4100 IDs only.",
            fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { devices = LfLink.serialDevices(ctx) }) { Text("Refresh") }
            devices.forEach { d -> Button(onClick = { LfLink.connect(ctx.applicationContext, d) }) { Text("Connect ${LfLink.readerOf(d).label}") } }
        }
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

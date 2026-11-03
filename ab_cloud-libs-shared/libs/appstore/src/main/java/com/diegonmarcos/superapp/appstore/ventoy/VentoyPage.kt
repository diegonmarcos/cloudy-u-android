package com.diegonmarcos.superapp.appstore.ventoy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.usbdrive.StorageVolumes
import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import kotlin.concurrent.thread

/**
 * #1405 Cloud Store > Cloud > Ventoy: pick a USB-C drive (libs:usb-drive), see whether it is a Ventoy drive, copy the
 * verified Ventoy installer onto it, and install or remove images from the four tables (assets/appstore-ventoy.json).
 * Every write goes through [SafDriveFs] on the drive's own SAF grant and the [VentoyDrive] rules; nothing here
 * writes to the phone's own storage. Built by StoreCloudFragment, which registers the two activity results.
 */
class VentoyPage(private val host: Fragment) {

    private val main = Handler(Looper.getMainLooper())
    private val catalogue: VentoyCatalogue.Catalogue? by lazy {
        runCatching { VentoyCatalogue.parse(host.requireContext().assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrNull()
    }

    private var selected by mutableStateOf<String?>(null)
    private var drive by mutableStateOf<DriveFs?>(null)
    private var onDrive by mutableStateOf<List<String>>(emptyList())
    private var ventoy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var installerVersion by mutableStateOf("")
    private var gateFor by mutableStateOf<VentoyGate.Drive?>(null)
    private val resolved = mutableStateMapOf<String, VentoyCatalogue.Resolved>()
    private val resolveErr = mutableStateMapOf<String, String>()
    private val busy = mutableStateMapOf<String, String>()
    private val modes = mutableStateMapOf<String, VentoyDrive.Mode>()
    private val persistGb = mutableStateMapOf<String, Int>()

    private val prefs by lazy { host.requireContext().getSharedPreferences("store_ventoy", Context.MODE_PRIVATE) }

    private val grant = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        val path = selected
        if (res.resultCode != Activity.RESULT_OK || uri == null || path == null) { message = "Access to the drive was not granted."; return@registerForActivityResult }
        val ctx = host.requireContext()
        val ok = runCatching { SafDriveFs(ctx.contentResolver, uri) }.isSuccess
        if (!ok) { message = "Refused: that folder is not the root of a removable drive."; return@registerForActivityResult }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("tree:$path", uri.toString()).apply()
        open(ctx, path)
    }

    private val pickLocal = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { installLocal(host.requireContext(), it) } }

    // ── drive ─────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun volumes(ctx: Context) = VentoyTarget.targets(StorageVolumes.read(ctx))

    private fun open(ctx: Context, path: String) {
        selected = path
        val tree = prefs.getString("tree:$path", null)?.let(Uri::parse)
            ?.takeIf { t -> ctx.contentResolver.persistedUriPermissions.any { it.uri == t && it.isWritePermission } }
        drive = tree?.let { runCatching { SafDriveFs(ctx.contentResolver, it) }.getOrNull() }
        refresh(ctx)
    }

    private fun refresh(ctx: Context) {
        val fs = drive ?: run { onDrive = emptyList(); ventoy = false; return }
        val label = volumes(ctx).firstOrNull { it.path == selected }?.description.orEmpty()
        thread(name = "ventoy-scan") {
            val list = runCatching { VentoyDrive.onDrive(fs) }.getOrDefault(emptyList())
            val isV = runCatching { VentoyDrive.isVentoy(fs, label) }.getOrDefault(false)
            main.post { onDrive = list; ventoy = isV }
        }
    }

    private fun requestGrant(ctx: Context, path: String) {
        selected = path
        val i = StorageVolumes.grantIntent(ctx, path) ?: run { message = "This Android version cannot grant one drive; open it in Cloud Drive."; return }
        runCatching { grant.launch(i) }.onFailure { message = "No system picker for drive access: ${it.message}" }
    }

    // ── installs ──────────────────────────────────────────────────────────────────────────────────────────────────
    private fun work(key: String, what: String, body: ((String) -> Unit) -> String) {
        if (busy.containsKey(key)) return
        busy[key] = what
        thread(name = "ventoy-$key") {
            val r = runCatching { body { p -> main.post { busy[key] = p } } }
            main.post {
                busy.remove(key)
                message = r.fold({ it }, { e -> if (e is ChecksumMismatch) "Checksum mismatch, nothing kept: ${e.message}" else "Failed: ${e.message}" })
                host.context?.let(::refresh)
            }
        }
    }

    private fun progress(n: Long, total: Long) = if (total > 0) "${UsbVolumeModel.human(n)} of ${UsbVolumeModel.human(total)}" else UsbVolumeModel.human(n)

    private fun persistence(row: VentoyCatalogue.Row) = VentoyDrive.Persistence((persistGb[row.id] ?: 4).toLong() shl 30, row.persistenceLabel, row.persistenceConf)

    private fun install(row: VentoyCatalogue.Row) {
        val fs = drive ?: return
        val mode = modes[row.id] ?: VentoyDrive.Mode.ISO_ONLY
        work(row.id, "resolving...") { say ->
            val r = resolved[row.id] ?: VentoyNet.resolve(row.source) ?: error("the official feed did not list an image")
            val sha = VentoyDrive.install(fs, r.fileName, r.sha256, mode, if (mode == VentoyDrive.Mode.LIVE_PERSISTENT) persistence(row) else null) { out ->
                VentoyNet.stream(r.url) { ins, len -> VentoyCatalogue.copyHashing(ins, out) { say(progress(it, len)) } }
            }
            "${row.name} ${r.version} installed, sha256 verified ($sha)." + if (mode == VentoyDrive.Mode.FULL_INSTALL) " " + mode.note else ""
        }
    }

    private fun installLocal(ctx: Context, uri: Uri) {
        val fs = drive ?: run { message = "Pick and grant a drive first."; return }
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        work("local", "copying $name...") { say ->
            require(VentoyDrive.isBootable(name)) { "$name is not an image Ventoy boots (${VentoyDrive.BOOTABLE.joinToString()})" }
            val sha = VentoyDrive.install(fs, name, null, VentoyDrive.Mode.ISO_ONLY, null) { out ->
                (ctx.contentResolver.openInputStream(uri) ?: error("cannot read $name")).use { VentoyCatalogue.copyHashing(it, out) { n -> say(progress(n, -1)) } }
            }
            "$name installed. sha256 $sha: compare it with the publisher's."
        }
    }

    private fun remove(path: String) {
        val fs = drive ?: return
        work(path, "removing...") { VentoyDrive.remove(fs, path); "Removed $path and its persistence." }
    }

    private fun copyInstaller() {
        val fs = drive ?: return
        val cat = catalogue ?: return
        work("installer", "resolving the Ventoy release...") { say ->
            val done = cat.installerAssets.map { re ->
                val r = VentoyNet.resolve(cat.ventoy.copy(asset = re)) ?: error("the Ventoy release has no asset $re")
                main.post { installerVersion = r.version }
                VentoyDrive.placeVerified(fs, INSTALLER_DIR, r.fileName, r.sha256) { out ->
                    VentoyNet.stream(r.url) { ins, len -> VentoyCatalogue.copyHashing(ins, out) { say("${r.fileName}: " + progress(it, len)) } }
                }
                r.fileName
            }
            fs.write("$INSTALLER_DIR/README.txt") { it.write(INSTRUCTIONS.toByteArray()) }
            "Copied ${done.joinToString()} to $INSTALLER_DIR, sha256 verified against the release's sha256.txt."
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────────────────────────────────────────
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(ctx: Context) {
        val tick by StorageVolumes.tick.collectAsState()
        var vols by remember { mutableStateOf(volumes(ctx)) }
        LaunchedEffect(tick) {
            vols = volumes(ctx)
            val sel = selected
            if (sel == null || vols.none { it.path == sel }) vols.firstOrNull()?.path?.let { open(ctx, it) } ?: run { selected = null; drive = null; onDrive = emptyList() }
        }
        val cat = catalogue
        Column(Modifier.fillMaxWidth().testTag("ventoy-page")) {
            Caption("Ventoy (GPL-3.0) boots any ISO copied onto its drive. Pick a USB-C drive, then add or remove images. Every download is checked against the publisher's sha256.")
            Heading("Drive")
            if (vols.isEmpty()) Caption("No USB-C drive is mounted. Plug one in (the phone's own storage is never offered).")
            vols.forEach { v ->
                val sel = v.path == selected
                Card(sel) {
                    Text((if (sel) "● " else "○ ") + UsbVolumeModel.labelOf(v), color = WHITE, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Caption(listOfNotNull(v.path, UsbVolumeModel.sizeHint(v.totalBytes, v.freeBytes).ifEmpty { null }).joinToString(" · "))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!sel) Btn("Use this drive") { open(ctx, v.path!!) }
                        if (sel && drive == null) Btn("Grant access") { requestGrant(ctx, v.path!!) }
                    }
                    if (sel && drive != null) Caption(if (ventoy) "Ventoy drive detected · ${onDrive.size} image(s) on it" else "Not a Ventoy drive yet: images can be copied, but the drive will not boot them until Ventoy is installed.")
                }
            }
            Heading("Install Ventoy")
            Card(false) {
                Caption(VentoyRawInstall.WHY)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Copy installer", enabled = drive != null && !busy.containsKey("installer")) { copyInstaller() }
                    Btn("Erase & install Ventoy", enabled = VentoyRawInstall.AVAILABLE && drive != null) {
                        vols.firstOrNull { it.path == selected }?.let { gateFor = VentoyGate.Drive(UsbVolumeModel.labelOf(it), it.totalBytes, it) }
                    }
                }
                busy["installer"]?.let { Caption(it) }
                Caption(INSTRUCTIONS + if (installerVersion.isNotEmpty()) "\nRelease: $installerVersion" else "")
            }
            if (message.isNotEmpty()) Text(message, color = AMBER, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp).testTag("ventoy-message"))
            Btn("Install a local image", enabled = drive != null && !busy.containsKey("local")) { pickLocal.launch(arrayOf("*/*")) }
            busy["local"]?.let { Caption(it) }
            val catalogueNames = cat?.rows?.mapNotNull { resolved[it.id]?.fileName }.orEmpty().toSet()
            val others = onDrive.filter { it.substringAfterLast('/') !in catalogueNames }
            if (others.isNotEmpty()) {
                Heading("On the drive")
                others.forEach { p -> Card(false) { Text(p, color = WHITE, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Btn("Remove", enabled = !busy.containsKey(p)) { remove(p) } } }
            }
            if (cat == null) { Caption("The catalogue did not load."); return@Column }
            LaunchedEffect(Unit) { resolveAll(cat) }
            cat.tables.forEach { t -> TableView(t) }
        }
        gateFor?.let { GateDialog(it) }
    }

    private fun resolveAll(cat: VentoyCatalogue.Catalogue) {
        cat.rows.filter { it.installable && !resolved.containsKey(it.id) }.forEach { row ->
            thread(name = "ventoy-resolve-${row.id}") {
                val r = runCatching { VentoyNet.resolve(row.source) }
                main.post { r.getOrNull()?.let { resolved[row.id] = it } ?: run { resolveErr[row.id] = r.exceptionOrNull()?.message ?: "not listed by the feed" } }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TableView(t: VentoyCatalogue.Table) {
        Heading(t.label, tag = "ventoy-table-${t.id}")
        Caption(t.blurb)
        t.rows.forEach { row ->
            val r = resolved[row.id]
            val here = r != null && onDrive.any { it.substringAfterLast('/') == r.fileName }
            val path = onDrive.firstOrNull { r != null && it.substringAfterLast('/') == r.fileName }
            Card(here) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.name, color = WHITE, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    when {
                        row.kind == VentoyCatalogue.Kind.DEVICE -> Badge("flash, not Ventoy", RED)
                        here -> Badge("on drive", GREEN)
                    }
                }
                val facts = when {
                    r != null -> listOf(r.version, if (r.size > 0) UsbVolumeModel.human(r.size) else "size ?", r.fileName)
                    !row.installable -> listOf("link only")
                    resolveErr[row.id] != null -> listOf("feed: " + resolveErr[row.id])
                    else -> listOf("checking the official feed...")
                }
                Caption(facts.joinToString(" · "))
                if (r != null) Text("sha256 ${r.sha256}", color = DIM, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(r?.url ?: row.link, color = BLUE, fontSize = 11.sp)
                if (row.note.isNotEmpty()) Caption(row.note)
                if (row.installable) {
                    val mode = modes[row.id] ?: VentoyDrive.Mode.ISO_ONLY
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VentoyDrive.Mode.values().forEach { m -> Chip(m.label, m == mode) { modes[row.id] = m } }
                    }
                    if (mode == VentoyDrive.Mode.LIVE_PERSISTENT) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 4, 8, 16).forEach { gb -> Chip("$gb GB", (persistGb[row.id] ?: 4) == gb) { persistGb[row.id] = gb } }
                    }
                    if (mode != VentoyDrive.Mode.ISO_ONLY) Caption(mode.note + if (mode == VentoyDrive.Mode.LIVE_PERSISTENT) " Label ${row.persistenceLabel}." else "")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Install", enabled = drive != null && !busy.containsKey(row.id)) { install(row) }
                        if (path != null) Btn("Remove", enabled = !busy.containsKey(path)) { remove(path) }
                    }
                    busy[row.id]?.let { Caption(it) }
                }
            }
        }
    }

    @Composable
    private fun GateDialog(d: VentoyGate.Drive) {
        var typed by remember(d) { mutableStateOf("") }
        val shown = VentoyGate.prompt(d)
        AlertDialog(
            onDismissRequest = { gateFor = null },
            title = { Text("Erase ${d.label}?") },
            text = { Column { Text(shown); OutlinedTextField(typed, { typed = it }, singleLine = true) } },
            dismissButton = { TextButton({ gateFor = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton({
                    val armed = VentoyGate.arm(d, typed, shown)
                    gateFor = null
                    message = if (armed == null) "Not confirmed: nothing was erased." else when (val r = VentoyRawInstall.install(armed)) { is VentoyRawInstall.Result.Unsupported -> r.why }
                }) { Text("Erase") }
            },
        )
    }

    // ── small parts ───────────────────────────────────────────────────────────────────────────────────────────────
    @Composable private fun Caption(t: String) = Text(t, color = DIM, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
    @Composable private fun Heading(t: String, tag: String = "") =
        Text(t, color = WHITE, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp).testTag(tag.ifEmpty { "ventoy-h-$t" }))
    @Composable private fun Badge(t: String, c: Color) = Text(t, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    @Composable private fun Btn(t: String, enabled: Boolean = true, onClick: () -> Unit) = OutlinedButton(onClick, enabled = enabled) { Text(t) }
    @Composable private fun Chip(t: String, on: Boolean, onClick: () -> Unit) =
        FilterChip(on, onClick, label = { Text(t, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(labelColor = DIM, selectedLabelColor = WHITE))
    @Composable private fun Card(on: Boolean, body: @Composable () -> Unit) =
        Surface(color = Color(0x22FFFFFF), shape = RoundedCornerShape(10.dp), border = if (on) BorderStroke(1.dp, GREEN) else null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(10.dp)) { body() } }

    companion object {
        const val ASSET = "appstore-ventoy.json"
        const val INSTALLER_DIR = "/ventoy-installer"
        const val INSTRUCTIONS = "On a PC: copy ventoy-installer/ off this drive, unpack the archive for your OS, run Ventoy2Disk " +
            "(Windows: Ventoy2Disk.exe; Linux: sudo sh Ventoy2Disk.sh -i /dev/sdX) and pick this drive. Ventoy2Disk erases the drive, " +
            "so copy images back afterwards. Then plug it into the phone again and install images here."
        private val WHITE = Color(0xFFFFFFFF); private val DIM = Color(0x99FFFFFF); private val GREEN = Color(0xFF48BB78)
        private val RED = Color(0xFFF56565); private val AMBER = Color(0xFFECC94B); private val BLUE = Color(0xFF63B3ED)
    }
}

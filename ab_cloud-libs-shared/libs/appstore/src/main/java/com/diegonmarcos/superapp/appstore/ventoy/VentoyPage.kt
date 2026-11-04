package com.diegonmarcos.superapp.appstore.ventoy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.appstore.Ink
import com.diegonmarcos.superapp.appstore.StoreCaption
import com.diegonmarcos.superapp.appstore.StoreHeading
import com.diegonmarcos.superapp.appstore.StoreQuick
import com.diegonmarcos.superapp.appstore.StoreRow
import com.diegonmarcos.superapp.appstore.StoreVerb
import com.diegonmarcos.superapp.usbdrive.StorageVolumes
import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import kotlin.concurrent.thread

/**
 * #1405 Cloud Store > Cloud > Ventoy (#1444: rows are the Apps list's StoreRow; Erase & install Ventoy runs through the Cloud Terminal, see [VentoyRawInstall]): pick a USB-C drive (libs:usb-drive), see whether it is a Ventoy drive, copy the
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
    private val expanded = mutableStateMapOf<String, Boolean>()

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
        message = "Pick the drive itself; if Android says \"Can't use this folder\" (SD cards), open its ISO folder and use that."
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

    // ── raw install (#1444) ───────────────────────────────────────────────────────────────────────────────────────
    private fun rawInstall(armed: VentoyGate.Armed) {
        val ctx = host.requireContext().applicationContext
        val cat = catalogue ?: run { message = "The catalogue did not load."; return }
        work("raw", "starting...") { say ->
            val py = ctx.assets.open(VentoyRawInstall.ASSET).use { it.readBytes() }
            val release = {
                val re = cat.installerAssets.first { it.contains("linux") }
                VentoyNet.resolve(cat.ventoy.copy(asset = re)) ?: error("the Ventoy release has no asset $re")
            }
            when (val r = VentoyRawInstall.install(armed, ctx, py, release) { p -> say(p) }) {
                is VentoyRawInstall.Result.Done -> r.msg
                is VentoyRawInstall.Result.Untouched -> "Nothing was erased: ${r.why}"
                is VentoyRawInstall.Result.Broken -> "FAILED: ${r.why}"
            }
        }
    }

    // ── UI: the Apps list's rows (StoreRow), one compact row per thing, buttons in the expand ─────────────────────
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
            StoreCaption("Ventoy (GPL-3.0) boots any ISO copied onto its drive. Pick a USB-C drive, then add or remove images. Every download is checked against the publisher's sha256.")
            StoreHeading("Drive", Ink.WHITE, tag = "ventoy-h-Drive")
            if (vols.isEmpty()) StoreCaption("No USB-C drive is mounted. Plug one in (the phone's own storage is never offered).")
            vols.forEach { v -> DriveRow(ctx, v) }
            StoreHeading("Install Ventoy", Ink.WHITE, tag = "ventoy-h-Install Ventoy")
            InstallRow(vols)
            // Order matters for accessibility (and the emulator test that reads the UI tree): Compose hides a node that a LATER
            // sibling's 48dp touch target covers, so a status line never sits right above a button or the next row's head.
            StoreRow.Actions(listOf(StoreVerb("Install a local image", Ink.BLUE_FILL, if (drive != null && !busy.containsKey("local")) ({ pickLocal.launch(arrayOf("*/*")) }) else null)))
            busy["local"]?.let { StoreRow.Line(it) }
            if (message.isNotEmpty()) StoreRow.Line(message, color = Ink.AMBER_FILL, tag = "ventoy-message")
            val catalogueNames = cat?.rows?.mapNotNull { resolved[it.id]?.fileName }.orEmpty().toSet()
            val others = onDrive.filter { it.substringAfterLast('/') !in catalogueNames }
            if (others.isNotEmpty()) {
                StoreHeading("On the drive", Ink.WHITE, tag = "ventoy-h-On the drive")
                others.forEach { p ->
                    StoreRow.Item(p, open = true, onToggle = {}, dot = "●", dotColor = Ink.GREEN_FILL, meta = "on drive") {
                        StoreRow.Actions(listOf(StoreVerb("Remove", Ink.GREY_FILL, if (busy.containsKey(p)) null else ({ remove(p) }))))
                    }
                }
            }
            if (cat == null) { StoreCaption("The catalogue did not load."); return@Column }
            LaunchedEffect(Unit) { resolveAll(cat) }
            cat.tables.forEach { t -> TableView(t) }
        }
        gateFor?.let { GateDialog(it) }
    }

    @Composable
    private fun DriveRow(ctx: Context, v: com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.Volume) {
        val sel = v.path == selected
        val key = "drive:${v.path}"
        StoreRow.Item(UsbVolumeModel.labelOf(v), open = expanded[key] ?: sel, onToggle = { expanded[key] = !(expanded[key] ?: sel) },
            dot = if (sel) "●" else "○", dotColor = if (sel) Ink.GREEN_FILL else Ink.DIM,
            meta = UsbVolumeModel.sizeHint(v.totalBytes, v.freeBytes), tag = "ventoy-drive") {
            StoreRow.Actions(listOfNotNull(
                if (!sel) StoreVerb("Use this drive", Ink.BLUE_FILL, { open(ctx, v.path!!) }) else null,
                if (sel && drive == null) StoreVerb("Grant access", Ink.BLUE_FILL, { requestGrant(ctx, v.path!!) }) else null,
            ))
            StoreRow.Line(v.path.orEmpty(), mono = true)
            if (sel && (drive as? SafDriveFs)?.isoOnly == true) StoreRow.Line("Granted: the drive's ISO folder. Images install there; persistence needs the whole drive (Android refuses the root of an SD card).")
            if (sel && drive != null) StoreRow.Line(if (ventoy) "Ventoy drive detected · ${onDrive.size} image(s) on it" else "Not a Ventoy drive yet: images can be copied, but the drive will not boot them until Ventoy is installed.")
        }
    }

    @Composable
    private fun InstallRow(vols: List<com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.Volume>) {
        StoreRow.Item("Ventoy on this drive", open = expanded["install"] ?: false, onToggle = { expanded["install"] = !(expanded["install"] ?: false) },
            dot = if (ventoy) "●" else "○", dotColor = if (ventoy) Ink.GREEN_FILL else Ink.DIM,
            meta = busy["raw"] ?: busy["installer"] ?: if (installerVersion.isNotEmpty()) "release $installerVersion" else if (ventoy) "installed" else "not installed", tag = "ventoy-install") {
            StoreRow.Actions(listOf(
                StoreVerb("Copy installer", Ink.BLUE_FILL, if (drive != null && !busy.containsKey("installer")) ({ copyInstaller() }) else null),
                StoreVerb("Erase & install Ventoy", Ink.AMBER_FILL, if (VentoyRawInstall.AVAILABLE && drive != null && !busy.containsKey("raw")) ({
                    vols.firstOrNull { it.path == selected }?.let { gateFor = VentoyGate.Drive(UsbVolumeModel.labelOf(it), it.totalBytes, it) }
                }) else null, tag = "ventoy-erase"),
            ))
            StoreRow.Line(VentoyRawInstall.WHY)
            StoreRow.Line(INSTRUCTIONS)
        }
    }

    private fun resolveAll(cat: VentoyCatalogue.Catalogue) {
        cat.rows.filter { it.installable && !resolved.containsKey(it.id) }.forEach { row ->
            thread(name = "ventoy-resolve-${row.id}") {
                val r = runCatching { VentoyNet.resolve(row.source) }
                main.post { r.getOrNull()?.let { resolved[row.id] = it } ?: run { resolveErr[row.id] = r.exceptionOrNull()?.message ?: "not listed by the feed" } }
            }
        }
    }

    @Composable
    private fun TableView(t: VentoyCatalogue.Table) {
        StoreHeading(t.label, Ink.WHITE, tag = "ventoy-table-${t.id}")
        StoreCaption(t.blurb)
        StoreRow.Summary("${t.rows.size} rows · ${t.rows.count { r -> resolved[r.id]?.let { x -> onDrive.any { it.substringAfterLast('/') == x.fileName } } == true }} on the drive")
        t.rows.forEach { row ->
            val r = resolved[row.id]
            val path = onDrive.firstOrNull { r != null && it.substringAfterLast('/') == r.fileName }
            val here = path != null
            val mode = modes[row.id] ?: VentoyDrive.Mode.ISO_ONLY
            val meta = busy[row.id] ?: when {
                row.kind == VentoyCatalogue.Kind.DEVICE -> "flash, not Ventoy"
                r != null -> listOf(r.version, if (r.size > 0) UsbVolumeModel.human(r.size) else "size ?", if (here) "on drive" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                !row.installable -> "link only"
                resolveErr[row.id] != null -> "feed: " + resolveErr[row.id]
                else -> "checking..."
            }
            val canInstall = row.installable && drive != null && !busy.containsKey(row.id)
            StoreRow.Item(row.name, open = expanded[row.id] ?: false, onToggle = { expanded[row.id] = !(expanded[row.id] ?: false) },
                dot = if (here) "●" else "○", dotColor = when { row.kind == VentoyCatalogue.Kind.DEVICE -> Ink.AMBER_FILL; here -> Ink.GREEN_FILL; else -> Ink.DIM },
                meta = meta, metaColor = if (row.kind == VentoyCatalogue.Kind.DEVICE) Ink.AMBER_FILL else Ink.DIM,
                quick = if (canInstall && !here) StoreQuick("Install", Ink.GREEN_FILL) { install(row) } else null, tag = "ventoy-row-${row.id}") {
                if (r != null) StoreRow.Line(r.fileName, mono = true)
                if (r != null) StoreRow.Line("sha256 ${r.sha256}", mono = true)
                StoreRow.Line(r?.url ?: row.link, color = Ink.BLUE_FILL)
                if (row.note.isNotEmpty()) StoreRow.Line(row.note)
                if (row.installable) {
                    StoreRow.Filters(VentoyDrive.Mode.values().map { it.label }, mode.ordinal, { modes[row.id] = VentoyDrive.Mode.values()[it] })
                    if (mode == VentoyDrive.Mode.LIVE_PERSISTENT) {
                        val gbs = listOf(1, 2, 4, 8, 16)
                        StoreRow.Filters(gbs.map { "$it GB" }, gbs.indexOf(persistGb[row.id] ?: 4), { persistGb[row.id] = gbs[it] })
                    }
                    if (mode != VentoyDrive.Mode.ISO_ONLY) StoreRow.Line(mode.note + if (mode == VentoyDrive.Mode.LIVE_PERSISTENT) " Label ${row.persistenceLabel}." else "")
                    StoreRow.Actions(listOfNotNull(
                        StoreVerb("Install", Ink.GREEN_FILL, if (canInstall) ({ install(row) }) else null),
                        path?.let { p -> StoreVerb("Remove", Ink.GREY_FILL, if (busy.containsKey(p)) null else ({ remove(p) })) },
                    ))
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
                    if (armed == null) message = "Not confirmed: nothing was erased." else rawInstall(armed)
                }) { Text("Erase") }
            },
        )
    }

    companion object {
        const val ASSET = "appstore-ventoy.json"
        const val INSTALLER_DIR = "/ventoy-installer"
        const val INSTRUCTIONS = "On a PC: copy ventoy-installer/ off this drive, unpack the archive for your OS, run Ventoy2Disk " +
            "(Windows: Ventoy2Disk.exe; Linux: sudo sh Ventoy2Disk.sh -i /dev/sdX) and pick this drive. Ventoy2Disk erases the drive, " +
            "so copy images back afterwards. Then plug it into the phone again and install images here."
    }
}

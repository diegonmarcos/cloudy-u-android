package com.diegonmarcos.cloudstore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.perms.PermsChip
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.rememberPermsResumes
import com.diegonmarcos.superapp.torrent.EngineState
import com.diegonmarcos.superapp.torrent.PrefsSettings
import com.diegonmarcos.superapp.torrent.TorrentEngines
import com.diegonmarcos.superapp.torrent.TorrentFormat
import com.diegonmarcos.superapp.torrent.TorrentPaths
import java.io.File

/** Test tags of Configs > Torrent. */
object TorrentConfigTags {
    const val PATH = "torrent_cfg:path"
    const val DEFAULT_NOTE = "torrent_cfg:default"
    const val USE_DEFAULT = "torrent_cfg:use-default"
    const val CHOOSE = "torrent_cfg:choose-folder"
    const val GRANT = "torrent_cfg:grant-all-files"
    const val NO_ACCESS = "torrent_cfg:no-access"
    const val DRIVES = "torrent_cfg:drives"
    fun drive(path: String) = "torrent_cfg:drive:$path"
    const val IMPORT = "torrent_cfg:import"
    const val IMPORT_RESULT = "torrent_cfg:import-result"
    const val SEED = "torrent_cfg:seed"
    fun ratio(r: Int) = "torrent_cfg:ratio:$r"
    const val ENGINE = "torrent_cfg:engine"
}

/**
 * Settings > Configs > Torrent: where torrents are saved, the external drive, the .torrent import and the seeding policy.
 *
 *  - DOWNLOAD FOLDER: `Downloads/Cloudy-store/Torrent` by default, created when missing. Writing it takes all-files access
 *    (Android 11+ gives an app no raw write to the public Downloads folder otherwise); without it the folder degrades to the
 *    app's own Downloads folder and this page says so, with the grant one tap away (libs:perms opens the system screen; the Store
 *    never calls a permission API of its own).
 *  - EXTERNAL DRIVE: every mounted volume the phone lists is offered as a root (`<drive>/Cloudy-store/Torrent`), or any folder
 *    can be picked with the system folder picker (a volume folder maps to its path; a cloud provider's folder cannot hold a
 *    torrent and is refused in words).
 *  - IMPORT: the file picker adds each .torrent to the engine.
 */
@Composable
internal fun TorrentConfigPage() {
    val ctx = LocalContext.current
    val engine = remember { TorrentEngines.get(ctx) }
    val settings = remember { engine.settings as PrefsSettings }
    var rev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    val resumes = rememberPermsResumes()
    val granted = remember(rev, resumes) { PermsChip.missing(ctx, listOf("all_files")).isEmpty() }
    val bad = Color(0xFFF56565)

    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = TorrentPaths.pathFromTreeDocId(DocumentsContract.getTreeDocumentId(uri))
        msg = if (dir == null) "That location is not a storage volume (a cloud provider cannot hold a torrent). Pick a folder on internal storage or a drive."
        else useFolder(settings, dir)
        rev++
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importResult = TorrentIntake.importUris(ctx, uris).joinToString("\n")
    }

    Head("Download folder")
    Text(settings.saveDir.path, Modifier.testTag(TorrentConfigTags.PATH), fontSize = StoreDensity.T_BODY.sp)
    Note("Default: Downloads/${TorrentPaths.DEFAULT_RELATIVE} (${TorrentPaths.defaultDir(File("/storage/emulated/0/Download")).path}), created when missing." +
        if (settings.saveDirIsCustom) " Using a folder you chose." else " Using the default.")
    Text("The default folder is Downloads/${TorrentPaths.DEFAULT_RELATIVE}", Modifier.testTag(TorrentConfigTags.DEFAULT_NOTE), fontSize = StoreDensity.T_CAPTION.sp)
    if (settings.usingFallback && !granted) {
        Text("All files access is not granted, so the default folder cannot be created in Downloads. Torrents are saved to the app's own folder (${settings.fallbackDir().path}) until you grant it.",
            Modifier.testTag(TorrentConfigTags.NO_ACCESS), fontSize = StoreDensity.T_CAPTION.sp, color = bad)
    }
    if (!granted) Button({ PermsLink.open(ctx, "app", listOf("all_files")) }, Modifier.testTag(TorrentConfigTags.GRANT)) {
        Text("Grant all files access", fontSize = StoreDensity.T_BODY.sp)
    } else Note("All files access is granted: the download folder and external drives are writable.")
    Row(horizontalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S8).dp)) {
        Button({ settings.resetSaveDir(); msg = useFolder(settings, settings.defaultDir, custom = false); rev++ }, Modifier.testTag(TorrentConfigTags.USE_DEFAULT)) {
            Text("Use default", fontSize = StoreDensity.T_BODY.sp)
        }
        Button({ tree.launch(null) }, Modifier.testTag(TorrentConfigTags.CHOOSE)) { Text("Choose folder…", fontSize = StoreDensity.T_BODY.sp) }
    }
    msg?.let { Note(it) }

    Head("External drive")
    Note("Every storage volume the phone lists. Writing to a drive needs all files access (or pick a folder on it above).")
    val volumes = remember(rev, resumes) { TorrentEngines.volumes(ctx) }
    Column(Modifier.fillMaxWidth().testTag(TorrentConfigTags.DRIVES), verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
        if (volumes.isEmpty()) Note("No storage volume is listed.")
        volumes.forEach { v ->
            val ext = TorrentPaths.isExternalVolume(v)
            val target = TorrentPaths.defaultDir(File(v, "Download"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body((if (ext) "External: " else "Internal: ") + v.path)
                    Note("${TorrentFormat.bytes(TorrentPaths.freeBytes(v))} free · torrents go to ${target.path}")
                }
                Button({ msg = useFolder(settings, target); rev++ }, Modifier.testTag(TorrentConfigTags.drive(v.path))) { Text("Use", fontSize = StoreDensity.T_BODY.sp) }
            }
        }
    }

    Head("Import .torrent files")
    Note("Pick one or more .torrent files; each is added to the engine and saved to the folder above.")
    Button({ pick.launch(TorrentIntake.PICK_TYPES) }, Modifier.testTag(TorrentConfigTags.IMPORT)) {
        Text("Import .torrent files…", fontSize = StoreDensity.T_BODY.sp)
    }
    importResult?.let { Text(it, Modifier.testTag(TorrentConfigTags.IMPORT_RESULT), fontSize = StoreDensity.T_CAPTION.sp) }

    Head("Seeding")
    var seed by remember { mutableStateOf(settings.seedAfterComplete) }
    SwitchRow("Keep seeding after a download finishes", "New torrents inherit this; each torrent has its own switch on the Torrent page.", seed, TorrentConfigTags.SEED) {
        seed = it; settings.seedAfterComplete = it
    }
    var ratio by remember { mutableStateOf(settings.seedRatioLimit) }
    Note("Stop seeding at upload/download ratio (0 = never stop):")
    Row(horizontalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
        listOf(0, 1, 2, 5).forEach { r ->
            Button({ ratio = r.toFloat(); settings.seedRatioLimit = r.toFloat() }, Modifier.testTag(TorrentConfigTags.ratio(r))) {
                Text(if (ratio == r.toFloat()) "[$r]" else "$r", fontSize = StoreDensity.T_BODY.sp)
            }
        }
    }

    Head("Engine")
    val st = engine.state
    Text(when (st) {
        is EngineState.Running -> "BitTorrent engine running (libtorrent)."
        is EngineState.Unavailable -> st.reason
        EngineState.Stopped -> "BitTorrent engine not started yet; it starts with the Torrent page or the first add."
    }, Modifier.testTag(TorrentConfigTags.ENGINE), fontSize = StoreDensity.T_CAPTION.sp, color = if (st is EngineState.Unavailable) bad else Color.Unspecified)
}

/** Makes [dir] the save folder (unless [custom] is false, where it already is the default) after proving it can be created and written; the sentence says which. */
private fun useFolder(settings: PrefsSettings, dir: File, custom: Boolean = true): String {
    val p = TorrentPaths.prepare(dir)
    if (!p.ok) return (p.why ?: "Cannot use ${dir.path}.") + " Grant all files access above, then try again."
    if (custom) settings.saveDir = dir
    return "Download folder: ${dir.path}"
}

package com.diegonmarcos.cloudstore

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.appstore.StoreKit
import com.diegonmarcos.superapp.perms.PermsChip
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.rememberPermsResumes
import com.diegonmarcos.superapp.torrent.EngineState
import com.diegonmarcos.superapp.torrent.ParseKind
import com.diegonmarcos.superapp.torrent.PrefsSettings
import com.diegonmarcos.superapp.torrent.TorrentEngines
import com.diegonmarcos.superapp.torrent.TorrentFormat
import com.diegonmarcos.superapp.torrent.TorrentIndexers
import com.diegonmarcos.superapp.torrent.TorrentPaths
import com.diegonmarcos.superapp.torrent.TorrentSources
import com.diegonmarcos.superapp.torrent.UserSource
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitPartTags
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
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
    /** The ratio-limit choice is a KitSegmented: its segment for [r] carries this tag. */
    fun ratio(r: Int) = KitPartTags.segment("torrent_cfg:ratio", r.toString())
    const val ENGINE = "torrent_cfg:engine"

    // The sources manager: the declared built-ins, the user's own, and the add / edit form.
    const val SOURCES = "torrent_cfg:sources"
    const val BUILTIN_LIST = "torrent_cfg:builtin-list"
    fun builtinRow(id: String) = "torrent_cfg:builtin:$id"
    /** A Kit list row carries its own prefix: this is the tag a test finds it by. */
    fun builtin(id: String) = KitPartTags.listRow(builtinRow(id))
    const val USER_LIST = "torrent_cfg:user-list"
    const val USER_EMPTY = "torrent_cfg:user-empty"
    fun userRow(id: String) = "torrent_cfg:user:$id"
    fun user(id: String) = KitPartTags.listRow(userRow(id))
    fun userSwitch(id: String) = "torrent_cfg:user-switch:$id"
    fun userEdit(id: String) = "torrent_cfg:user-edit:$id"
    const val SOURCE_ADD = "torrent_cfg:source-add"
    const val FORM = "torrent_cfg:source-form"
    const val FIELD_ID = "torrent_cfg:field-id"
    const val FIELD_LABEL = "torrent_cfg:field-label"
    const val FIELD_HOME = "torrent_cfg:field-homepage"
    const val FIELD_URL = "torrent_cfg:field-url"
    fun kind(k: ParseKind) = "torrent_cfg:kind:${k.id}"
    const val SOURCE_SAVE = "torrent_cfg:source-save"
    const val SOURCE_CANCEL = "torrent_cfg:source-cancel"
    const val SOURCE_REMOVE = "torrent_cfg:source-remove"
    const val SOURCE_ERROR = "torrent_cfg:source-error"
    // #1681 the catalogue entries of the user's own (kept on the device)
    const val CATALOGUE = "torrent_cfg:catalogue"
    const val CAT_NAME = "torrent_cfg:catalogue:name"
    const val CAT_CATEGORY = "torrent_cfg:catalogue:category"
    const val CAT_MAGNET = "torrent_cfg:catalogue:magnet"
    const val CAT_URL = "torrent_cfg:catalogue:url"
    const val CAT_ADD = "torrent_cfg:catalogue:add"
    const val CAT_ERROR = "torrent_cfg:catalogue:error"
    fun catEntry(id: String) = "torrent_cfg:catalogue:entry:$id"
    fun catRemove(id: String) = "torrent_cfg:catalogue:remove:$id"
}

/** The source form's state: [editing] is the id of the source being edited, null for a new one. */
private data class Draft(val editing: String?, val id: String, val label: String, val homepage: String, val url: String, val kind: ParseKind, val enabled: Boolean = true)

/**
 * Settings > Configs > Torrent: where torrents are saved, the external drive, the .torrent import, the seeding policy and the
 * torrent SOURCES. Drawn with the Store's Kit components (the same palette and parts Search > Torrent uses), in this order:
 *
 *  - DOWNLOAD FOLDER: `Downloads/Cloudy-store/Torrent` by default, created when missing. Writing it takes all-files access
 *    (Android 11+ gives an app no raw write to the public Downloads folder otherwise); without it the folder degrades to the
 *    app's own Downloads folder and this page says so, with the grant one tap away (libs:perms opens the system screen; the Store
 *    never calls a permission API of its own).
 *  - EXTERNAL DRIVE: every mounted volume the phone lists is offered as a root (`<drive>/Cloudy-store/Torrent`), or any folder
 *    can be picked with the system folder picker (a volume folder maps to its path; a cloud provider's folder cannot hold a
 *    torrent and is refused in words).
 *  - IMPORT: the file picker adds each .torrent to the engine.
 *  - SEEDING: keep seeding, and the ratio limit.
 *  - SOURCES: the built-in indexers (declared in libs:torrent, read-only) and the user's own (add / edit / remove / switch, kept in
 *    SharedPreferences libs_torrent); Search > Torrent searches the built-ins and every enabled source of the user.
 *  - CATALOGUE ENTRIES (#1681): the user's own Torrent catalogue entries (name, category, magnet or https .torrent), kept on the device.
 *  - ENGINE: whether the BitTorrent session is usable, with the reason when not.
 */
@Composable
internal fun TorrentConfigPage() = StoreKit.Themed { TorrentConfigBody() }

@Composable
private fun TorrentConfigBody() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val engine = remember { TorrentEngines.get(ctx) }
    val settings = remember { engine.settings as PrefsSettings }
    val sources = remember { TorrentEngines.sources(ctx) }
    var rev by remember { mutableStateOf(0) }
    var srcRev by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var catRev by remember { mutableStateOf(0) }
    var catName by remember { mutableStateOf("") }
    var catCategory by remember { mutableStateOf("os") }
    var catMagnet by remember { mutableStateOf("") }
    var catUrl by remember { mutableStateOf("") }
    var catError by remember { mutableStateOf<String?>(null) }
    val resumes = rememberPermsResumes()
    val granted = remember(rev, resumes) { PermsChip.missing(ctx, listOf("all_files")).isEmpty() }

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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        // ── Download folder ──
        KitSectionHeader("Download folder", "Where new torrents are saved.")
        KitCard {
            Text(settings.saveDir.path, Modifier.testTag(TorrentConfigTags.PATH), color = p.textPrimary, fontSize = KitDensity.body)
            Text("Default: Downloads/${TorrentPaths.DEFAULT_RELATIVE} (${TorrentPaths.defaultDir(File("/storage/emulated/0/Download")).path}), created when missing." +
                if (settings.saveDirIsCustom) " Using a folder you chose." else " Using the default.", color = p.textSecondary, fontSize = KitDensity.caption)
            Text("The default folder is Downloads/${TorrentPaths.DEFAULT_RELATIVE}", Modifier.testTag(TorrentConfigTags.DEFAULT_NOTE), color = p.textSecondary, fontSize = KitDensity.caption)
            if (settings.usingFallback && !granted) {
                Box(Modifier.testTag(TorrentConfigTags.NO_ACCESS)) {
                    KitStatusBanner("All files access is not granted, so the default folder cannot be created in Downloads. Torrents are saved to the app's own folder (${settings.fallbackDir().path}) until you grant it.",
                        KitState.BAD, tag = "torrent-no-access")
                }
            }
            if (!granted) KitStatusBanner("All files access is needed to write Downloads and external drives.", KitState.WARN, tag = "torrent-grant",
                action = KitAction("Grant all files access", TorrentConfigTags.GRANT) { PermsLink.open(ctx, "app", listOf("all_files")) })
            else KitStatusBanner("All files access is granted: the download folder and external drives are writable.", KitState.OK, tag = "torrent-granted")
            KitActionBar(listOf(
                KitAction("Use default", TorrentConfigTags.USE_DEFAULT) { settings.resetSaveDir(); msg = useFolder(settings, settings.defaultDir, custom = false); rev++ },
                KitAction("Choose folder…", TorrentConfigTags.CHOOSE) { tree.launch(null) },
            ), filledFirst = false)
            msg?.let { KitStatusBanner(it, if (it.startsWith("Download folder:")) KitState.OK else KitState.BAD, tag = "torrent-folder-msg") }
        }

        // ── External drive ──
        KitSectionHeader("External drive", "Every storage volume the phone lists. Writing to a drive needs all files access (or pick a folder on it above).")
        val volumes = remember(rev, resumes) { TorrentEngines.volumes(ctx) }
        KitCard(Modifier.testTag(TorrentConfigTags.DRIVES)) {
            if (volumes.isEmpty()) Text("No storage volume is listed.", color = p.textSecondary, fontSize = KitDensity.caption)
            volumes.forEach { v ->
                val ext = TorrentPaths.isExternalVolume(v)
                val target = TorrentPaths.defaultDir(File(v, "Download"))
                KitListRow(
                    label = (if (ext) "External: " else "Internal: ") + v.path,
                    secondary = "${TorrentFormat.bytes(TorrentPaths.freeBytes(v))} free · torrents go to ${target.path}",
                    pill = (if (ext) "external" else "internal") to (if (ext) KitState.OK else KitState.IDLE),
                    tag = "torrent-drive:${v.path}",
                    trailing = { KitOutlinedButton({ msg = useFolder(settings, target); rev++ }, Modifier.testTag(TorrentConfigTags.drive(v.path))) { Text("Use") } },
                )
            }
        }

        // ── Import ──
        KitSectionHeader("Import .torrent files", "Pick one or more .torrent files; each is added to the engine and saved to the folder above.")
        KitCard {
            KitActionBar(listOf(KitAction("Import .torrent files…", TorrentConfigTags.IMPORT) { pick.launch(TorrentIntake.PICK_TYPES) }))
            importResult?.let { Text(it, Modifier.testTag(TorrentConfigTags.IMPORT_RESULT), color = p.textSecondary, fontSize = KitDensity.caption) }
        }

        // ── Seeding ──
        KitSectionHeader("Seeding", "What a torrent does once it has finished.")
        KitCard {
            var seed by remember { mutableStateOf(settings.seedAfterComplete) }
            KitSwitchRow("Keep seeding after a download finishes", "New torrents inherit this; each torrent has its own switch on the Torrent page.", seed,
                { seed = it; settings.seedAfterComplete = it }, Modifier.testTag(TorrentConfigTags.SEED))
            var ratio by remember { mutableStateOf(settings.seedRatioLimit) }
            Text("Stop seeding at upload/download ratio (0 = never stop):", color = p.textSecondary, fontSize = KitDensity.caption)
            KitSegmented(
                options = listOf("0" to "0 (never)", "1" to "1", "2" to "2", "5" to "5"),
                selected = ratio.takeIf { it == it.toInt().toFloat() }?.toInt()?.toString(),
                onSelect = { ratio = it.toFloat(); settings.seedRatioLimit = ratio },
                tag = "torrent_cfg:ratio",
            )
        }

        // ── Sources ──
        KitSectionHeader("Torrent sources", "Search > Torrent searches the built-in indexers and every source of your own that is switched on.", Modifier.testTag(TorrentConfigTags.SOURCES))
        Text("Built-in (declared, always searched)", color = p.textSecondary, fontSize = KitDensity.caption)
        KitCard(Modifier.testTag(TorrentConfigTags.BUILTIN_LIST)) {
            TorrentIndexers.all.forEach { ix ->
                KitListRow(ix.label, secondary = ix.blurb + "  " + ix.homepage, pill = "built-in" to KitState.OK, tag = TorrentConfigTags.builtinRow(ix.id))
            }
        }
        val user = remember(srcRev) { sources.user() }
        Text("Yours (${user.size})", color = p.textSecondary, fontSize = KitDensity.caption)
        KitCard(Modifier.testTag(TorrentConfigTags.USER_LIST)) {
            if (user.isEmpty()) Text("No source of your own yet. Add one with its search address; {q} marks where the search words go.", Modifier.testTag(TorrentConfigTags.USER_EMPTY),
                color = p.textSecondary, fontSize = KitDensity.caption)
            user.forEach { u ->
                KitListRow(
                    label = u.label,
                    secondary = "${u.kind.label} · ${u.searchUrl}",
                    pill = (if (u.enabled) "on" else "off") to (if (u.enabled) KitState.OK else KitState.IDLE),
                    tag = TorrentConfigTags.userRow(u.id),
                    onClick = { draft = Draft(u.id, u.id, u.label, u.homepage, u.searchUrl, u.kind, u.enabled); formError = null },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KitTextButton({ draft = Draft(u.id, u.id, u.label, u.homepage, u.searchUrl, u.kind, u.enabled); formError = null }, Modifier.testTag(TorrentConfigTags.userEdit(u.id))) { Text("Edit") }
                            Switch(u.enabled, { on -> sources.setEnabled(u.id, on); srcRev++ }, Modifier.testTag(TorrentConfigTags.userSwitch(u.id)))
                        }
                    },
                )
            }
            if (draft == null) KitActionBar(listOf(KitAction("Add a source", TorrentConfigTags.SOURCE_ADD) { draft = Draft(null, "", "", "", "", ParseKind.RSS); formError = null }))
        }
        draft?.let { d ->
            KitCard(Modifier.testTag(TorrentConfigTags.FORM)) {
                Text(if (d.editing == null) "Add a source" else "Edit ${d.label}", color = p.textPrimary, fontSize = KitDensity.title)
                SourceField("Name", d.label, TorrentConfigTags.FIELD_LABEL, hint = "My indexer") {
                    // a new source's id follows its name until it is typed by hand
                    draft = d.copy(label = it, id = if (d.editing == null && d.id == TorrentSources.idFor(d.label)) TorrentSources.idFor(it) else d.id)
                }
                SourceField("Id" + if (d.editing != null) " (fixed)" else "", d.id, TorrentConfigTags.FIELD_ID, enabled = d.editing == null, hint = "my-indexer") { draft = d.copy(id = it) }
                SourceField("Homepage (optional)", d.homepage, TorrentConfigTags.FIELD_HOME, hint = "https://example.org") { draft = d.copy(homepage = it) }
                SourceField("Search address", d.url, TorrentConfigTags.FIELD_URL, hint = "https://example.org/rss?q={q}") { draft = d.copy(url = it) }
                Text("https only. Put ${TorrentSources.PLACEHOLDER} where the search words go.", color = p.textSecondary, fontSize = KitDensity.caption)
                Text("Result format", color = p.textSecondary, fontSize = KitDensity.caption)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ParseKind.values().forEach { k -> StoreKit.Pill(k.label, d.kind == k, TorrentConfigTags.kind(k)) { draft = d.copy(kind = k) } }
                }
                Text(d.kind.blurb, color = p.textSecondary, fontSize = KitDensity.caption)
                formError?.let { Box(Modifier.testTag(TorrentConfigTags.SOURCE_ERROR)) { KitStatusBanner(it, KitState.BAD, tag = "torrent-source-error") } }
                val actions = mutableListOf(
                    KitAction("Save", TorrentConfigTags.SOURCE_SAVE) {
                        val s = UserSource(d.id, d.label, d.homepage, d.url, d.kind, d.enabled)
                        val r = if (d.editing == null) sources.add(s) else sources.update(d.editing, s)
                        if (r.ok) { draft = null; formError = null; srcRev++ } else formError = r.error
                    },
                    KitAction("Cancel", TorrentConfigTags.SOURCE_CANCEL) { draft = null; formError = null },
                )
                if (d.editing != null) actions += KitAction("Remove", TorrentConfigTags.SOURCE_REMOVE) { confirmRemove = d.editing }
                KitActionBar(actions)
            }
        }
        confirmRemove?.let { id ->
            KitConfirmDialog("Remove this source?", "'$id' stops being searched. Torrents already downloaded from it are not touched.", "Remove", "Keep",
                onConfirm = { sources.remove(id); confirmRemove = null; draft = null; srcRev++ }, onDismiss = { confirmRemove = null })
        }

        // ── Catalogue: the user's own entries (#1681) ──
        KitSectionHeader("Catalogue entries", "Your own entries in the Torrent catalogue, kept on this phone. A magnet link or an https .torrent address.",
            Modifier.testTag(TorrentConfigTags.CATALOGUE))
        val mine = remember(catRev) { TorrentCatalogue.userEntries(ctx) }
        KitCard {
            if (mine.isEmpty()) Text("No entry of your own yet.", color = p.textSecondary, fontSize = KitDensity.caption)
            mine.forEach { e ->
                KitListRow(e.name, secondary = e.category + " · " + (e.magnet ?: e.torrentUrl ?: ""), tag = TorrentConfigTags.catEntry(e.id),
                    trailing = { KitTextButton({ TorrentCatalogue.removeUser(ctx, e.id); catRev++ }, Modifier.testTag(TorrentConfigTags.catRemove(e.id))) { Text("Remove") } })
            }
            SourceField("Name", catName, TorrentConfigTags.CAT_NAME, hint = "My distro ISO") { catName = it }
            SourceField("Category id", catCategory, TorrentConfigTags.CAT_CATEGORY, hint = "os, movies, software, …") { catCategory = it }
            SourceField("Magnet link", catMagnet, TorrentConfigTags.CAT_MAGNET, hint = "magnet:?xt=urn:btih:…") { catMagnet = it }
            SourceField(".torrent address (optional)", catUrl, TorrentConfigTags.CAT_URL, hint = "https://example.org/file.torrent") { catUrl = it }
            catError?.let { Box(Modifier.testTag(TorrentConfigTags.CAT_ERROR)) { KitStatusBanner(it, KitState.BAD, tag = "torrent-catalogue-error") } }
            KitActionBar(listOf(KitAction("Add to catalogue", TorrentConfigTags.CAT_ADD) {
                catError = TorrentCatalogue.addUser(ctx, catName, catCategory.trim().lowercase(), catMagnet, catUrl)
                if (catError == null) { catName = ""; catMagnet = ""; catUrl = ""; catRev++ }
            }))
        }

        // ── Engine ──
        KitSectionHeader("Engine", "The BitTorrent session under the downloads.")
        val st = engine.state
        Box(Modifier.testTag(TorrentConfigTags.ENGINE).semantics(mergeDescendants = true) {}) {
            KitStatusBanner(when (st) {
                is EngineState.Running -> "BitTorrent engine running (libtorrent)."
                is EngineState.Unavailable -> st.reason
                EngineState.Stopped -> "BitTorrent engine not started yet; it starts with the Torrent page or the first add."
            }, when (st) { is EngineState.Running -> KitState.OK; is EngineState.Unavailable -> KitState.BAD; EngineState.Stopped -> KitState.IDLE }, tag = "torrent-engine")
        }
    }
}

/** One labelled single-line text field in the Kit look (palette surface, hairline border, the density's body size). */
@Composable
private fun SourceField(label: String, value: String, tag: String, enabled: Boolean = true, hint: String = "", onChange: (String) -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 4)) {
        Text(label, color = p.textSecondary, fontSize = KitDensity.caption)
        Box(Modifier.fillMaxWidth().clip(shape).background(if (enabled) p.surfaceSelected else p.surface).border(KitDensity.rule, p.hairline, shape)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV)) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = p.textSecondary, fontSize = KitDensity.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = if (enabled) p.textPrimary else p.textSecondary, fontSize = KitDensity.body), cursorBrush = SolidColor(p.accent))
        }
    }
}

/** Makes [dir] the save folder (unless [custom] is false, where it already is the default) after proving it can be created and written; the sentence says which. */
private fun useFolder(settings: PrefsSettings, dir: File, custom: Boolean = true): String {
    val p = TorrentPaths.prepare(dir)
    if (!p.ok) return (p.why ?: "Cannot use ${dir.path}.") + " Grant all files access above, then try again."
    if (custom) settings.saveDir = dir
    return "Download folder: ${dir.path}"
}

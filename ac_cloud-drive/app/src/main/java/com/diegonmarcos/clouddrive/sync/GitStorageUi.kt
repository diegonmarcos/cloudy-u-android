package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.superapp.usbdrive.StorageVolumes
import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding

/** #1376 the selected storage, persisted per app. */
fun gitStorageSelected(ctx: Context): GitStorage.Kind =
    GitStorage.Kind.of(ctx.getSharedPreferences("git_storage", Context.MODE_PRIVATE).getString(GitStorage.KEY, null))

fun gitStorageSelect(ctx: Context, k: GitStorage.Kind) {
    ctx.getSharedPreferences("git_storage", Context.MODE_PRIVATE).edit().putString(GitStorage.KEY, k.id).apply()
}

/** The root a non-store storage is searched from: shared storage, or the attached drive (USB first). */
fun gitStorageRoot(ctx: Context, k: GitStorage.Kind): File? = when (k) {
    GitStorage.Kind.STORE -> com.diegonmarcos.clouddrive.SharedStore.root()
    GitStorage.Kind.EMULATED -> Environment.getExternalStorageDirectory()
    GitStorage.Kind.EXTERNAL -> StorageVolumes.drives(ctx).let { ds -> ds.firstOrNull { it.kind == UsbVolumeModel.Kind.USB } ?: ds.firstOrNull() }?.let { File(it.path) }
}

/** #1356/#1376 the selector, compact: one small pill in the Git page's top row naming the selected storage; a tap lists the three (Emulated | cloud-shared-store | External). */
@Composable
fun GitStorageMenu(selected: GitStorage.Kind, onSelect: (GitStorage.Kind) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label: @Composable (GitStorage.Kind) -> String = { k -> stringResource(when (k) {
        GitStorage.Kind.STORE -> R.string.git_storage_store
        GitStorage.Kind.EMULATED -> R.string.git_storage_emulated
        GitStorage.Kind.EXTERNAL -> R.string.git_storage_external
    }) }
    val icon: (GitStorage.Kind) -> String = { k -> when (k) { GitStorage.Kind.STORE -> "cloud"; GitStorage.Kind.EMULATED -> "smartphone"; GitStorage.Kind.EXTERNAL -> "usb" } }
    Box(modifier.testTag(DriveTags.SYNC_GIT_STORAGE)) {
        // #1452 the SAME pill + dropdown as every other action of the bar (Sync, Clone, More): icon only (Check | Sync | Clone | Logs | More stay in view);
        // the selected name is its description and the menu's rows
        val described = label(selected)
        com.diegonmarcos.clouddrive.ui.Pill("", { open = true }, Modifier.semantics { contentDescription = described }, icon = IconCatalog.vectorOrDefault(icon(selected)))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // #1452 the options in the owner's order
            listOf(GitStorage.Kind.EMULATED, GitStorage.Kind.STORE, GitStorage.Kind.EXTERNAL).forEach { k ->
                DropdownMenuItem(
                    text = { Text(label(k)) },
                    leadingIcon = { Icon(IconCatalog.vectorOrDefault(icon(k)), contentDescription = null) },
                    onClick = { open = false; onSelect(k) },
                )
            }
        }
    }
}

/** What a non-store storage holds: the empty states, or its repositories as page rows (id "storage:<path>"). Null while read. [refresh] re-reads it (a clone landed, the git folder was created). */
@Composable
fun rememberGitStorageView(kind: GitStorage.Kind, refresh: Int = 0): GitStorage.Listing? {
    val ctx = LocalContext.current
    val tick by StorageVolumes.tick.collectAsState()
    val view by produceState<GitStorage.Listing?>(null, kind, tick, refresh) {
        value = withContext(Dispatchers.IO) { GitStorage.view(gitStorageRoot(ctx, kind), rawAttached = kind == GitStorage.Kind.EXTERNAL && rawUsbAttached(ctx)) }
    }
    return view
}

/** #1356 a USB mass-storage device (interface class 8) is attached; with no mounted drive, Android did not mount it (libaums reads it raw). */
fun rawUsbAttached(ctx: Context): Boolean = runCatching {
    val um = ctx.getSystemService(android.hardware.usb.UsbManager::class.java) ?: return false
    um.deviceList.values.any { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE } }
}.getOrDefault(false)

/** #1356 the clone target picker's choices: Cloud-Store, Emulated, every attached drive by its label (a raw-only USB drive: read-only for git). */
fun gitCloneTargets(ctx: Context): List<GitStorage.Target> {
    val drives = StorageVolumes.drives(ctx).map { GitStorage.Target(GitStorage.Kind.EXTERNAL, it.label, File(it.path), it.id) }
    val raw = if (drives.isEmpty() && rawUsbAttached(ctx)) listOf(GitStorage.Target(GitStorage.Kind.EXTERNAL, ctx.getString(R.string.git_storage_external) + " (raw)", null, raw = true)) else emptyList()
    return GitStorage.targets(
        GitStorage.Target(GitStorage.Kind.STORE, ctx.getString(R.string.git_storage_store), com.diegonmarcos.clouddrive.SharedStore.root()),
        GitStorage.Target(GitStorage.Kind.EMULATED, ctx.getString(R.string.git_storage_emulated), Environment.getExternalStorageDirectory()),
        drives + raw,
    ).map { if (it.kind == GitStorage.Kind.EXTERNAL && it.label.isBlank()) it.copy(label = ctx.getString(R.string.git_storage_external)) else it }
}

/** #1356 how git may write on [kind]'s root now (all-files access where mounted, else the volume's SAF grant; raw = read-only). */
fun gitStorageAccess(ctx: Context, kind: GitStorage.Kind): GitStorage.Access? {
    if (kind == GitStorage.Kind.STORE) return null
    val root = gitStorageRoot(ctx, kind) ?: return if (kind == GitStorage.Kind.EXTERNAL && rawUsbAttached(ctx)) GitStorage.Access.RAW_READ_ONLY else null
    return GitStorage.access(raw = false, canWrite = root.canWrite())
}

/** #1356 the conflict solver over every repository with conflicts: one line each, a tap opens the engine's solver on it. */
@Composable
fun GitConflictsDialog(repos: List<Pair<ManagedRepo, Int>>, onOpen: (ManagedRepo) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_conflicts_title)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.testTag(DriveTags.SYNC_GIT_STORAGE + "_conflicts")) {
                if (repos.isEmpty()) Text(stringResource(R.string.git_conflicts_none))
                repos.forEach { (r, n) ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(r.name) },
                        supportingContent = { Text(stringResource(R.string.git_conflicts_count, n)) },
                        modifier = Modifier.clickable { onOpen(r) }.testTag(DriveTags.SYNC_GIT_STORAGE + "_conflict_" + r.name),
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.repo_status_window_close)) } },
    )
}

fun GitStorage.Listing.Repos.managed(): List<ManagedRepo> = repos.map { ManagedRepo(id = "storage:" + it.absolutePath, name = it.name, path = it.absolutePath) }

/** The empty states of a non-store storage: no drive attached, a raw drive (read-only for git), no git folder (#1356 with Create), a git folder with no repository. */
@Composable
fun GitStorageEmpty(kind: GitStorage.Kind, view: GitStorage.Listing, modifier: Modifier = Modifier, onCreated: () -> Unit = {}) {
    val icon = IconCatalog.vectorOrDefault(if (kind == GitStorage.Kind.EXTERNAL) "usb" else "folder")
    when (view) {
        GitStorage.Listing.RawOnly -> EmptyState(icon, stringResource(R.string.git_storage_raw_read_only), stringResource(R.string.git_storage_raw_hint), modifier)
        GitStorage.Listing.NoDrive -> EmptyState(icon, stringResource(R.string.git_storage_no_drive), stringResource(R.string.git_storage_no_drive_hint), modifier)
        is GitStorage.Listing.NoGitFolder -> androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            EmptyState(icon, stringResource(R.string.git_storage_no_folder), stringResource(R.string.git_storage_no_folder_hint, view.root), Modifier.weight(1f, fill = false))
            val folder = File(view.root, "git")
            var failed by remember { mutableStateOf(false) }
            com.diegonmarcos.clouddrive.ui.Pill(stringResource(R.string.git_storage_create) + " " + folder.absolutePath, {
                failed = !runCatching { folder.mkdirs() || folder.isDirectory }.getOrDefault(false); if (!failed) onCreated()
            }, filled = true, modifier = Modifier.testTag(DriveTags.SYNC_GIT_STORAGE + "_create"))
            if (failed) Text(stringResource(R.string.git_storage_create_failed, folder.absolutePath), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        is GitStorage.Listing.Repos -> EmptyState(icon, stringResource(R.string.git_storage_empty), view.folder, modifier)
    }
}

/** #1356 the volume is mounted but git cannot write it: grant this volume through SAF (StorageVolume.createOpenDocumentTreeIntent). */
@Composable
fun GitStorageGrantBanner(kind: GitStorage.Kind) {
    val ctx = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { uri -> runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    }
    androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_STORAGE + "_grant"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.git_storage_needs_grant), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.git_storage_needs_grant_hint), style = MaterialTheme.typography.labelSmall)
        }
        com.diegonmarcos.clouddrive.ui.Pill(stringResource(R.string.git_storage_grant), {
            gitStorageRoot(ctx, kind)?.let { StorageVolumes.grantIntent(ctx, it.absolutePath) }?.let { runCatching { launcher.launch(it) } }
        }, filled = true)
    }
}

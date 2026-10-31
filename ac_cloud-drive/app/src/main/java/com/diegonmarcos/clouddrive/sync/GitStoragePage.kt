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
import com.diegonmarcos.clouddrive.usb.StorageVolumes
import com.diegonmarcos.clouddrive.usb.UsbVolumeModel
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
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

/** #1356/#1376 the selector, compact: one small pill in the Git page's top row naming the selected storage; a tap lists the three. */
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
        FilterChip(
            selected = true, onClick = { open = true },
            label = { Text(label(selected), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
            leadingIcon = { Icon(IconCatalog.vectorOrDefault(icon(selected)), contentDescription = null, modifier = Modifier.size(DriveMetrics.icon)) },
            modifier = Modifier.height(DriveMetrics.chipHeight),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GitStorage.Kind.values().forEach { k ->
                DropdownMenuItem(
                    text = { Text(label(k)) },
                    leadingIcon = { Icon(IconCatalog.vectorOrDefault(icon(k)), contentDescription = null) },
                    onClick = { open = false; onSelect(k) },
                )
            }
        }
    }
}

/** What a non-store storage holds: the empty states, or its repositories as page rows (id "storage:<path>"). Null while read. */
@Composable
fun rememberGitStorageView(kind: GitStorage.Kind): GitStorage.View? {
    val ctx = LocalContext.current
    val tick by StorageVolumes.tick.collectAsState()
    val view by produceState<GitStorage.View?>(null, kind, tick) { value = withContext(Dispatchers.IO) { GitStorage.view(gitStorageRoot(ctx, kind)) } }
    return view
}

fun GitStorage.View.Repos.managed(): List<ManagedRepo> = repos.map { ManagedRepo(id = "storage:" + it.absolutePath, name = it.name, path = it.absolutePath) }

/** The empty states of a non-store storage: no drive attached, no git folder, a git folder with no repository. */
@Composable
fun GitStorageEmpty(kind: GitStorage.Kind, view: GitStorage.View, modifier: Modifier = Modifier) {
    val icon = IconCatalog.vectorOrDefault(if (kind == GitStorage.Kind.EXTERNAL) "usb" else "folder")
    when (view) {
        GitStorage.View.NoDrive -> EmptyState(icon, stringResource(R.string.git_storage_no_drive), stringResource(R.string.git_storage_no_drive_hint), modifier)
        is GitStorage.View.NoGitFolder -> EmptyState(icon, stringResource(R.string.git_storage_no_folder), stringResource(R.string.git_storage_no_folder_hint, view.root), modifier)
        is GitStorage.View.Repos -> EmptyState(icon, stringResource(R.string.git_storage_empty), view.folder, modifier)
    }
}

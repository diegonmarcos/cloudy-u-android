package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.usb.StorageVolumes
import com.diegonmarcos.clouddrive.usb.UsbVolumeModel
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.superapp.bottomnav.NavPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** #1376 the selected storage, persisted per app. */
fun gitStorageSelected(ctx: Context): GitStorage.Kind =
    GitStorage.Kind.of(ctx.getSharedPreferences(GitStorage.PREFS, Context.MODE_PRIVATE).getString(GitStorage.KEY, null))

fun gitStorageSelect(ctx: Context, k: GitStorage.Kind) {
    ctx.getSharedPreferences(GitStorage.PREFS, Context.MODE_PRIVATE).edit().putString(GitStorage.KEY, k.id).apply()
}

/** The root a non-store storage is searched from: shared storage, or the attached drive (USB first). */
fun gitStorageRoot(ctx: Context, k: GitStorage.Kind): File? = when (k) {
    GitStorage.Kind.STORE -> com.diegonmarcos.clouddrive.SharedStore.root()
    GitStorage.Kind.EMULATED -> Environment.getExternalStorageDirectory()
    GitStorage.Kind.EXTERNAL -> StorageVolumes.drives(ctx).let { ds -> ds.firstOrNull { it.kind == UsbVolumeModel.Kind.USB } ?: ds.firstOrNull() }?.let { File(it.path) }
}

/** The selector's three tabs, drawn by the page's own NavBarTopSecondRow (the Explorer's strip style). */
@Composable
fun gitStoragePages(): List<NavPage> = listOf(
    NavPage(GitStorage.Kind.STORE.id, stringResource(R.string.git_storage_store), "cloud"),
    NavPage(GitStorage.Kind.EMULATED.id, stringResource(R.string.git_storage_emulated), "smartphone"),
    NavPage(GitStorage.Kind.EXTERNAL.id, stringResource(R.string.git_storage_external), "usb"),
)

/** Emulated / External: the repositories in that storage's git folder, each synced through the page's coordinator. */
@Composable
fun GitStorageRepos(kind: GitStorage.Kind, git: GitSyncCoordinator, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tick by StorageVolumes.tick.collectAsState()
    val view by produceState<GitStorage.View?>(null, kind, tick) { value = withContext(Dispatchers.IO) { GitStorage.view(gitStorageRoot(ctx, kind)) } }
    val icon = IconCatalog.vectorOrDefault(if (kind == GitStorage.Kind.EXTERNAL) "usb" else "folder")
    when (val v = view) {
        null -> {}
        GitStorage.View.NoDrive -> EmptyState(icon, stringResource(R.string.git_storage_no_drive), stringResource(R.string.git_storage_no_drive_hint), modifier)
        is GitStorage.View.NoGitFolder -> EmptyState(icon, stringResource(R.string.git_storage_no_folder), stringResource(R.string.git_storage_no_folder_hint, v.root), modifier)
        is GitStorage.View.Repos -> if (v.repos.isEmpty()) EmptyState(icon, stringResource(R.string.git_storage_empty), v.folder, modifier)
            else LazyColumn(modifier) {
                items(v.repos, key = { it.absolutePath }) { dir ->
                    DriveCard(dir.name, summary = dir.absolutePath, summaryMonospace = true) {
                        PillRow { Pill(stringResource(R.string.git_storage_sync), { git.syncNow(ManagedRepo(id = "storage:" + dir.absolutePath, name = dir.name, path = dir.absolutePath)) }, filled = true) }
                    }
                }
            }
    }
}

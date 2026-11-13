package com.diegonmarcos.clouddrive.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.FilesScreen
import com.diegonmarcos.clouddrive.files.FilesUiState
import com.diegonmarcos.clouddrive.files.Location
import com.diegonmarcos.clouddrive.files.PaneId
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.manage.AndroidVolumeProbe
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1714 the Editor's file explorer panel: a root selector (Emulated, Termux, Nix, External: [EditorRoots]) over the Drive
 * Explorer's own file list ([FilesScreen] on a second [FilesController], so Home, the access-guarded Up of #1696, the
 * breadcrumb and the listing are the same component). A tap on a file reaches [EditorHook.open] through FilesScreen's
 * openEntry, which respects the Editor's file-type table (Office formats go to another app). A root that cannot be browsed
 * shows a reason row with its grant action instead of a list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorExplorerPanel(actions: DriveActions, currentPath: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        val prefs = DrivePrefs(ctx.applicationContext)
        val snap = prefs.snapshot.value
        val home = Location.Local(runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/"))
        FilesController(ctx.applicationContext, FilesUiState.initial(home, home, snap.defaultSort, snap.showHidden, false), scope, prefs)
    }
    // Re-probe when the user comes back from a grant screen.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val roots by produceState(emptyList<EditorRoots.Root>(), tick) {
        value = withContext(Dispatchers.IO) {
            val access = object : EditorRoots.Access {
                override val hasAllFiles = Places.hasAllFilesAccess(ctx)
                override fun canList(path: String) = controller.canList(path)
                override fun installed(fleet: String?): Boolean {
                    val pkg = if (fleet == null) StoreAccess.ownerPackage() else Declarations.constellation.firstOrNull { it.fleet == fleet }?.packageName.orEmpty()
                    return pkg.isNotBlank() && runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
                }
            }
            runCatching { EditorRoots.roots(Declarations.manage, AndroidVolumeProbe(ctx), access) }.getOrDefault(emptyList())
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var driveIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = roots.firstOrNull { it.id == selectedId }
    // First open (or a root that went away): the root the shown file sits in, else the first accessible root, else the first one.
    LaunchedEffect(roots) {
        if (roots.isNotEmpty() && selected == null) {
            val r = currentPath?.let { EditorRoots.rootOfPath(roots, it) } ?: roots.firstOrNull { it.accessible } ?: roots.first()
            selectedId = r.id
        }
    }
    LaunchedEffect(selected?.id, selected?.places?.size, driveIndex) {
        val r = selected ?: return@LaunchedEffect
        EditorRoots.startOf(r, driveIndex)?.takeIf { r.accessible }?.let { controller.open(PaneId.A, Location.Local(it)) }
    }
    Surface(modifier.fillMaxHeight().testTag(DriveTags.EDITOR_PANEL), color = MaterialTheme.colorScheme.surface, tonalElevation = DriveMetrics.gap, shadowElevation = DriveMetrics.pad) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = DriveMetrics.padWide), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_explorer_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.Close, stringResource(R.string.editor_explorer_close), Modifier.size(DriveMetrics.icon)) }
            }
            // The root selector, always first.
            // A FlowRow, not a scrolling row: all four roots are on screen at once, however narrow the panel.
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.pad), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                roots.forEach { r ->
                    FilterChip(selected = r.id == selectedId, onClick = { selectedId = r.id; driveIndex = 0 }, modifier = Modifier.testTag(DriveTags.editorRoot(r.id)),
                        label = { Text(r.label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Icon(if (r.accessible) IconCatalog.vectorOrDefault(r.icon) else Icons.Filled.Lock, null, Modifier.size(DriveMetrics.icon)) })
                }
            }
            // More than one removable drive: one chip per drive under the selector.
            if (selected != null && selected.places.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.pad), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                    selected.places.forEachIndexed { i, p -> FilterChip(selected = i == driveIndex, onClick = { driveIndex = i }, label = { Text(p.label, style = MaterialTheme.typography.labelSmall) }) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    selected == null -> Unit
                    selected.accessible -> FilesScreen(controller, actions, hasAccess = true)
                    else -> ReasonRow(selected, actions) { tick++ }
                }
            }
        }
    }
}

/** The label the reason row prints for an inaccessible root (the app's name for a fleet root, else the root's label). */
@Composable
private fun ReasonRow(root: EditorRoots.Root, actions: DriveActions, onRetry: () -> Unit) {
    val reason = root.reason ?: return
    val who = root.label
    val text = stringResource(when (reason) {
        EditorRoots.Reason.NO_STORAGE_GRANT -> R.string.editor_root_reason_grant
        EditorRoots.Reason.APP_NOT_INSTALLED -> R.string.editor_root_reason_not_installed
        EditorRoots.Reason.NOT_READABLE -> R.string.editor_root_reason_not_readable
        EditorRoots.Reason.NO_DRIVE -> R.string.editor_root_reason_no_drive
    }, who)
    val pkg = if (root.fleet.isBlank()) StoreAccess.ownerPackage() else Declarations.constellation.firstOrNull { it.fleet == root.fleet }?.packageName.orEmpty()
    Column(Modifier.fillMaxWidth().padding(DriveMetrics.pad).testTag(DriveTags.EDITOR_ROOT_REASON), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(DriveMetrics.glyphIcon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
            when (reason) {
                EditorRoots.Reason.NO_STORAGE_GRANT -> TextButton(onClick = { actions.requestStorageAccess() }) { Text(stringResource(R.string.editor_root_action_grant)) }
                EditorRoots.Reason.NOT_READABLE -> TextButton(onClick = { actions.launchApp(pkg, "") }) { Text(stringResource(R.string.editor_root_action_open_app)) }
                EditorRoots.Reason.NO_DRIVE -> TextButton(onClick = { actions.requestTreeGrant() }) { Text(stringResource(R.string.editor_root_action_folder)) }
                EditorRoots.Reason.APP_NOT_INSTALLED -> Unit
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_root_action_retry)) }
        }
    }
}

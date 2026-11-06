package com.diegonmarcos.clouddrive.manage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1537 Manage ▸ Overview: every volume with its usage (used / free / total and a bar), grouped LOCAL (Emulated, Termux
 * home, Nix home, external drives) and CLOUD (Cloud-S3, Google Drive, the other declared mounts). The sources are
 * build.json ui.manage; the numbers come from [VolumeOverview] (StatFs and StorageManager for local, `rclone about` for cloud).
 * A volume that cannot be read shows n/a and why. One dense KitStoreRow line per volume.
 */
@Composable
fun OverviewPage(storage: StorageCoordinator) {
    val ctx = LocalContext.current
    val probe = remember { AndroidVolumeProbe(ctx) }
    val decl = Declarations.manage
    var local by remember { mutableStateOf<List<VolumeUsage>?>(null) }
    LaunchedEffect(Unit) { local = withContext(Dispatchers.IO) { VolumeOverview.local(decl.local, probe) } }
    val targets = remember { VolumeOverview.cloudTargets(decl.cloud, Declarations.remotes, Declarations.mountProviders) }
    var cloud by remember { mutableStateOf(emptyMap<String, VolumeUsage>()) }
    LaunchedEffect(Unit) {
        // rclone about is slow and offline-prone: each row fills in when its own answer arrives.
        for (t in targets) {
            val u = withContext(Dispatchers.IO) { VolumeOverview.cloudUsage(t) { name -> storage.ops.about(name).map { CloudAbout(it.used, it.total, it.free) } } }
            cloud = cloud + (t.id to u)
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(DriveTags.OVERVIEW)) {
        item { SectionHeader(stringResource(R.string.manage_overview_local), count = local?.size) }
        items(local.orEmpty(), key = { it.id }) { VolumeRow(it.label, it.icon, it) }
        item { SectionHeader(stringResource(R.string.manage_overview_cloud), count = targets.size) }
        items(targets, key = { it.id }) { t -> VolumeRow(t.label, t.icon, cloud[t.id], id = t.id) }
    }
}

@Composable
private fun VolumeRow(label: String, icon: String, usage: VolumeUsage?, id: String = usage?.id.orEmpty()) {
    KitStoreRowLine(Modifier.testTag(DriveTags.overviewRow(id))) {
        Icon(IconCatalog.vectorOrDefault(icon), contentDescription = null, Modifier.size(DriveMetrics.glyphIcon))
        Spacer(Modifier.width(KitStoreRow.gap))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KitStoreName(label, Modifier.weight(1f))
            }
            when {
                usage == null -> KitStoreCaption(stringResource(R.string.manage_overview_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                usage.unavailable != null -> KitStoreCaption(stringResource(R.string.manage_overview_na, usage.unavailable), color = MaterialTheme.colorScheme.error)
                else -> {
                    if (usage.hasBar) LinearProgressIndicator(progress = { usage.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = KitStoreRow.tight).height(KitStoreRow.gap).testTag(DriveTags.OVERVIEW_BAR))
                    KitStoreCaption(usageLine(usage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            usage?.path?.let { KitStoreCaption(it, color = MaterialTheme.colorScheme.onSurfaceVariant, mono = true) }
        }
    }
    Hairline()
}

@Composable
private fun usageLine(u: VolumeUsage): String {
    val used = u.used?.let { FileOps.humanBytes(it) }
    val free = u.free?.let { FileOps.humanBytes(it) }
    val total = u.total?.let { FileOps.humanBytes(it) }
    return if (used != null && free != null && total != null) stringResource(R.string.manage_overview_usage, used, free, total)
    else stringResource(R.string.manage_overview_used_only, used ?: free ?: total ?: "?")
}

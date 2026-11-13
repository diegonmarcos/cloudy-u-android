package com.diegonmarcos.clouddrive.manage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Overview > APPS: Cloud Apps / Phone Apps summed from the Local Apps cache file (re-read every few seconds, so a Local Apps refresh shows here). */
@Composable
internal fun AppsSummary() {
    val ctx = LocalContext.current
    val file = remember { File(ctx.filesDir, "cloud-drive-cache/" + LocalAppsCache.FILE_NAME) }
    var snap by remember { mutableStateOf<AppSnapshot.Snapshot?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var seen = -1L
        while (true) {
            val stamp = file.lastModified()
            if (stamp != seen) { seen = stamp; snap = withContext(Dispatchers.IO) { LocalAppsCache(file).read() } }
            now = System.currentTimeMillis()
            delay(3000)
        }
    }
    val s = snap
    Column(Modifier.fillMaxWidth().testTag(DriveTags.OVERVIEW_APPS).padding(horizontal = DriveMetrics.sectionInset)) {
        if (s == null) KitStoreCaption(stringResource(R.string.manage_apps_sum_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val fleet = remember { runCatching { Declarations.constellation.map { it.packageName }.filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet()) }
            val rows = OverviewAppsSummary.summarize(s.rows, fleet)
            val heads = listOf(R.string.manage_apps_sum_group, R.string.manage_apps_sum_total, R.string.manage_apps_sum_app, R.string.manage_apps_sum_data, R.string.manage_apps_sum_cache, R.string.manage_apps_sum_amount)
            Row(Modifier.fillMaxWidth()) {
                heads.forEachIndexed { i, h -> KitStoreCaption(stringResource(h), Modifier.weight(if (i == 0) 1.4f else 1f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            for (r in rows) {
                Row(Modifier.fillMaxWidth().testTag(DriveTags.appsSumRow(r.id)).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    KitStoreName(stringResource(if (r.id == OverviewAppsSummary.CLOUD) R.string.manage_apps_sum_cloud else R.string.manage_apps_sum_phone), Modifier.weight(1.4f))
                    for (v in listOf(r.total, r.app, r.data, r.cache)) KitStoreCaption(v?.let { FileOps.humanBytes(it) } ?: "n/a", Modifier.weight(1f))
                    KitStoreCaption(r.amount.toString(), Modifier.weight(1f))
                }
            }
            KitStoreCaption(LocalAppsCache.ageLabel(now, s.capturedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Hairline()
}

/** Overview > DATA: one horizontal strip per declared folder group, newest first, via MediaStore. */
@Composable
internal fun DataStrip(d: Declarations.DataStripDecl) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<StripState?>(null) }
    LaunchedEffect(d.id) { state = withContext(Dispatchers.IO) { loadStrip(ctx, d) } }
    Column(Modifier.fillMaxWidth().testTag(DriveTags.dataStrip(d.id)).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad)) {
        KitStoreName(d.label)
        when (val st = state) {
            null -> KitStoreCaption(stringResource(R.string.manage_overview_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is StripState.Unavailable -> KitStoreCaption(stringResource(R.string.manage_overview_na, st.reason), color = MaterialTheme.colorScheme.error)
            is StripState.Files -> if (st.files.isEmpty()) KitStoreCaption(stringResource(R.string.manage_data_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = DriveMetrics.pad), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
                st.files.forEachIndexed { i, f -> DataItem(d.id, i, f) }
            }
        }
    }
    Hairline()
}

@Composable
private fun DataItem(strip: String, index: Int, f: RecentFile) {
    val ctx = LocalContext.current
    val thumb by produceThumb(ctx, f)
    Column(Modifier.width(88.dp).testTag(DriveTags.dataItem(strip, index)).clickable { openFile(ctx, f) }, horizontalAlignment = Alignment.CenterHorizontally) {
        val bmp = thumb
        if (bmp != null) Image(bmp, contentDescription = f.name, Modifier.size(88.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) { Icon(IconCatalog.vectorOrDefault("description"), contentDescription = f.name, Modifier.size(DriveMetrics.glyphIcon * 2), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (bmp == null) { KitStoreCaption(f.name, color = MaterialTheme.colorScheme.onSurface); KitStoreCaption(FileOps.humanBytes(f.size), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun produceThumb(ctx: Context, f: RecentFile) = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f.uri) {
    if (f.isImage) value = withContext(Dispatchers.IO) { runCatching { decodeThumb(ctx, Uri.parse(f.uri), 176) }.getOrNull() }
}

private fun decodeThumb(ctx: Context, uri: Uri, px: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}

private fun openFile(ctx: Context, f: RecentFile) {
    val r = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), f.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    r.exceptionOrNull()?.let { android.widget.Toast.makeText(ctx, ctx.getString(R.string.manage_data_open_failed, f.name, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_SHORT).show() }
}

private fun hasGrant(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Blocking; never throws: a missing grant or a failed query comes back as [StripState.Unavailable] with the reason. */
internal fun loadStrip(ctx: Context, d: Declarations.DataStripDecl): StripState {
    if (!hasGrant(ctx)) return StripState.Unavailable(ctx.getString(R.string.manage_data_no_grant))
    val (sel, args) = RecentMedia.selection(d) ?: return StripState.Unavailable("no folder declared")
    return runCatching {
        val base = MediaStore.Files.getContentUri("external")
        val q = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, d.limit)
        }
        val out = ArrayList<RecentFile>()
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE), q, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += RecentFile(id, c.getString(1).orEmpty(), c.getLong(2), c.getString(3).orEmpty(), ContentUris.withAppendedId(base, id).toString())
            }
        }
        StripState.Files(out) as StripState
    }.getOrElse { StripState.Unavailable(it.message ?: it.javaClass.simpleName) }
}

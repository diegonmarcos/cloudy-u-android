package com.diegonmarcos.clouddrive.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.convert.Converter
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import java.io.File

/**
 * Row menu ▸ Unzip…: the destination is an editable path that starts as the sibling folder named
 * after the archive; Browse… shows the folders around it so another one is a tap away. The job
 * itself (progress card, cancel, Zip-Slip refusal) is FilesController.unzip.
 */
@Composable
fun UnzipDialog(entry: FileOps.Entry, defaultDest: String, onDismiss: () -> Unit, onUnzip: (String) -> Unit) {
    var dest by remember { mutableStateOf(defaultDest) }
    var browsing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_unzip)) },
        text = {
            Column {
                Text(entry.name + (ArchiveExtractor.kindOf(entry.name)?.let { " · " + it.label } ?: ""), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(dest, { dest = it }, label = { Text(stringResource(R.string.files_unzip_dest)) }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gap))
                Row {
                    TextButton(onClick = { browsing = !browsing }) { Text(stringResource(R.string.files_unzip_browse)) }
                    TextButton(onClick = { dest = defaultDest }) { Text(stringResource(R.string.files_unzip_default)) }
                }
                if (browsing) FolderBrowser(dest) { dest = it }
            }
        },
        confirmButton = { TextButton(enabled = dest.isNotBlank(), onClick = { onUnzip(dest.trim()) }) { Text(stringResource(R.string.files_unzip)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

/** The nearest existing folder at or above [path], and its sub-folders; picking one calls [onPick] with its path. */
@Composable
private fun FolderBrowser(path: String, onPick: (String) -> Unit) {
    val dir = remember(path) { generateSequence(File(path.trim())) { it.parentFile }.firstOrNull { it.isDirectory } }
    val children = remember(dir) { dir?.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList() }
    Column {
        Text(dir?.path ?: "", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = DriveMetrics.rowHeight * 5)) {
            dir?.parentFile?.let { up -> item { BrowserRow("↑ " + stringResource(R.string.files_unzip_up)) { onPick(up.path) } } }
            if (dir != null) item { BrowserRow("✓ " + stringResource(R.string.files_unzip_here_folder)) { onPick(dir.path) } }
            items(children, key = { it.path }) { c -> BrowserRow(c.name + "/") { onPick(c.path) } }
        }
    }
}

@Composable
private fun BrowserRow(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * #1607 Row menu ▸ Compress: choose the archive format (.zip or .tar.gz) and whether the archive
 * lands beside the item or in the device's Downloads. The job itself (progress card, cancel, de-dup
 * naming, the real zip / tar.gz writers) is FilesController.compress.
 */
@Composable
fun CompressDialog(entry: FileOps.Entry, onDismiss: () -> Unit, onCompress: (tarGz: Boolean, toDownloads: Boolean) -> Unit) {
    var tarGz by remember { mutableStateOf(false) }
    var toDownloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_compress_title, entry.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(stringResource(R.string.files_compress_format), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { tarGz = false }) { Text((if (!tarGz) "● " else "   ") + stringResource(R.string.files_compress_zip)) }
                    TextButton(onClick = { tarGz = true }) { Text((if (tarGz) "● " else "   ") + stringResource(R.string.files_compress_targz)) }
                }
                Text(stringResource(R.string.files_compress_destination), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = DriveMetrics.gap))
                Row {
                    TextButton(onClick = { toDownloads = false }) { Text((if (!toDownloads) "● " else "   ") + stringResource(R.string.files_compress_beside)) }
                    TextButton(onClick = { toDownloads = true }) { Text((if (toDownloads) "● " else "   ") + stringResource(R.string.files_compress_downloads)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCompress(tarGz, toDownloads) }) { Text(stringResource(R.string.files_compress_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

/** Row menu ▸ Convert…: one row per registry converter that reads this file's type. */
@Composable
fun ConvertDialog(entry: FileOps.Entry, converters: List<Converter>, onDismiss: () -> Unit, onConvert: (Converter) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_convert_to, entry.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                converters.forEach { c ->
                    Text(c.label, Modifier.fillMaxWidth().clickable { onConvert(c) }.padding(vertical = DriveMetrics.pad), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

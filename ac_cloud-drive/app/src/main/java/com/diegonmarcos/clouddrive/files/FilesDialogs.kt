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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One text field, one confirm — new folder, new file, rename, archive name. */
@Composable
fun TextFieldDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = FileOps.sanitize(value) != null, onClick = { onConfirm(value.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = colorResource(R.color.status_light_off)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_keep)) } },
    )
}

/** Size, contents, permissions, hashes — hashing live, cancellable by closing. */
@Composable
fun PropertiesDialog(state: FilesController.PropertiesState, onDismiss: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val e = state.entry
    val r = state.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name) },
        text = {
            Column {
                PropRow(stringResource(R.string.files_props_path), (e.location as? Location.Local)?.path ?: e.location.key, mono = true)
                PropRow(stringResource(R.string.files_props_size), FileOps.humanBytes(r?.size ?: e.size))
                if (e.isDirectory && r != null) PropRow(stringResource(R.string.files_props_contents), stringResource(R.string.files_props_contents_value, r.files, r.folders))
                PropRow(stringResource(R.string.files_props_modified), fmt.format(Date(e.modified)))
                if (r != null) PropRow(stringResource(R.string.files_props_permissions), listOf("r" to r.readable, "w" to r.writable, "x" to r.executable).joinToString("") { if (it.second) it.first else "-" }, mono = true)
                if (!e.isDirectory) {
                    PropRow(stringResource(R.string.files_props_md5), r?.md5 ?: stringResource(R.string.files_props_hashing), mono = true)
                    PropRow(stringResource(R.string.files_props_sha256), r?.sha256 ?: state.progress, mono = true)
                }
                if (state.running) CircularProgressIndicator(Modifier.padding(top = DriveMetrics.pad))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_ok)) } },
    )
}

@Composable
private fun PropRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = DriveMetrics.tight)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

/** Pattern in, validated preview out, apply only when nothing conflicts. */
@Composable
fun BulkRenameDialog(files: List<File>, onDismiss: () -> Unit, onApply: (List<FileOps.RenameStep>) -> Unit) {
    var pattern by remember { mutableStateOf("{name}") }
    val plan = remember(pattern, files) { FileOps.renamePlan(files, pattern) }
    val blocked = plan.any { it.reason != null && it.changes }
    val changes = plan.count { it.changes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_rename_pattern)) },
        text = {
            Column {
                OutlinedTextField(pattern, { pattern = it }, label = { Text(stringResource(R.string.files_rename_pattern_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (blocked) Text(stringResource(R.string.files_rename_blocked), color = colorResource(R.color.status_light_off), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = DriveMetrics.gap))
                LazyColumn(Modifier.heightIn(max = DriveMetrics.dialogList).padding(top = DriveMetrics.pad)) {
                    items(plan, key = { it.path }) { step ->
                        Text(
                            stringResource(R.string.files_rename_preview, step.oldName, step.newName) + (step.reason?.let { "  · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = if (step.reason != null && step.changes) colorResource(R.color.status_light_off) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !blocked && changes > 0, onClick = { onApply(plan) }) { Text(stringResource(R.string.files_rename_apply, changes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

/** Groups of byte-identical files under a folder; tap a path to reveal it. */
@Composable
fun DuplicatesDialog(groups: List<FileOps.DuplicateGroup>?, onReveal: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_duplicates)) },
        text = {
            when {
                groups == null -> CircularProgressIndicator()
                groups.isEmpty() -> Text(stringResource(R.string.files_duplicates_none))
                else -> LazyColumn(Modifier.heightIn(max = DriveMetrics.dialogList)) {
                    items(groups, key = { it.paths.first() }) { g ->
                        Column(Modifier.padding(vertical = DriveMetrics.gapWide)) {
                            Text(stringResource(R.string.files_duplicates_group, g.paths.size, FileOps.humanBytes(g.size)), style = MaterialTheme.typography.labelLarge)
                            g.paths.forEach { p ->
                                Row(Modifier.fillMaxWidth().clickable { onReveal(p) }.padding(vertical = DriveMetrics.tight)) {
                                    Text(p, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_close)) } },
    )
}

/** #458/#577 the conversion targets the app can honestly write (PdfConvert.TARGETS); docx/xlsx/odt are named as not offered, with the reason. */
@Composable
fun ConvertPdfDialog(entry: FileOps.Entry, onDismiss: () -> Unit, onConvert: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_convert_pdf)) },
        text = {
            Column {
                Text(entry.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = DriveMetrics.pad)) {
                    com.diegonmarcos.clouddrive.PdfConvert.DIALOG_TARGETS.forEach { t ->
                        TextButton(onClick = { onConvert(t) }) { Text(if (t == com.diegonmarcos.clouddrive.PdfConvert.OCR) "OCR" else t) }
                    }
                }
                Text(stringResource(R.string.files_convert_pdf_not_offered), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

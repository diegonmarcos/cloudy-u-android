package app.sterna.ui.message

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import app.sterna.ui.components.Icon
import app.sterna.ui.theme.MailMetrics
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.sterna.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.core.jmap.model.Email
import app.sterna.export.ExportFormat
import app.sterna.export.exportFileName

internal fun exportFormatTag(f: ExportFormat) = "export_format_${f.name}"
internal const val EXPORT_MENU_TAG = "export_menu_item"

/** The reader overflow's "Export..." entry. */
@Composable
internal fun ExportMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier.testTag(EXPORT_MENU_TAG),
        text = { Text(stringResource(R.string.message_export)) },
        leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun formatTitle(f: ExportFormat) = stringResource(
    when (f) {
        ExportFormat.MHTML -> R.string.export_fmt_mhtml
        ExportFormat.ATTACHMENTS_ZIP -> R.string.export_fmt_zip
        ExportFormat.HTML -> R.string.export_fmt_html
        ExportFormat.EML -> R.string.export_fmt_eml
        ExportFormat.MBOX -> R.string.export_fmt_mbox
        ExportFormat.MARKDOWN -> R.string.export_fmt_md
    },
)

@Composable
private fun formatHint(f: ExportFormat) = stringResource(
    when (f) {
        ExportFormat.MHTML -> R.string.export_hint_mhtml
        ExportFormat.ATTACHMENTS_ZIP -> R.string.export_hint_zip
        ExportFormat.HTML -> R.string.export_hint_html
        ExportFormat.EML -> R.string.export_hint_eml
        ExportFormat.MBOX -> R.string.export_hint_mbox
        ExportFormat.MARKDOWN -> R.string.export_hint_md
    },
)

/** The sheet's body: every format, the destination buttons, and progress + cancel while a job runs. */
@Composable
internal fun ExportSheetContent(
    selected: ExportFormat,
    enabled: (ExportFormat) -> Boolean,
    running: ExportUiState?,
    onSelect: (ExportFormat) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s24).padding(bottom = MailMetrics.s24).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MailMetrics.s4),
    ) {
        Text(stringResource(R.string.message_export), style = MaterialTheme.typography.titleLarge)
        EXPORT_FORMATS.forEach { f ->
            val on = enabled(f) && running == null
            Row(
                Modifier.fillMaxWidth().testTag(exportFormatTag(f))
                    .selectable(selected = f == selected, enabled = on, role = Role.RadioButton) { onSelect(f) }
                    .padding(vertical = MailMetrics.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = f == selected, onClick = null, enabled = on)
                Column(Modifier.padding(start = MailMetrics.s12)) {
                    Text(formatTitle(f), style = MaterialTheme.typography.bodyLarge)
                    Text(formatHint(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            stringResource(R.string.export_remote_images_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("export_remote_note").padding(top = MailMetrics.s4),
        )
        if (running != null) {
            if (running.total > 0) {
                LinearProgressIndicator(progress = { (running.done.toFloat() / running.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s8))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s8))
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("export_cancel")) {
                Text(stringResource(R.string.export_cancel))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = MailMetrics.s8), horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                Button(onClick = onSave, enabled = enabled(selected), modifier = Modifier.testTag("export_save")) {
                    Text(stringResource(R.string.export_save))
                }
                OutlinedButton(onClick = onShare, enabled = enabled(selected), modifier = Modifier.testTag("export_share")) {
                    Text(stringResource(R.string.export_share))
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun ExportSheet(
    selected: ExportFormat,
    enabled: (ExportFormat) -> Boolean,
    running: ExportUiState?,
    onSelect: (ExportFormat) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ExportSheetContent(selected, enabled, running, onSelect, onSave, onShare, onCancel)
    }
}

/**
 * The sheet with its state and its document picker. The launcher lives HERE and the host stays composed
 * while the picker is up, so the result has somewhere to land; the sheet closes itself when the job it
 * started has finished (progress and Cancel show meanwhile).
 */
@Composable
internal fun ExportSheetHost(
    viewModel: MessageViewModel,
    ownerId: String,
    email: Email,
    onDismiss: () -> Unit,
) {
    var formatName by rememberSaveable { mutableStateOf(ExportFormat.MHTML.name) }
    val format = ExportFormat.valueOf(formatName)
    val running by viewModel.exportState.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) { started = true; viewModel.exportMessage(format, uri, ownerId) }
    }
    LaunchedEffect(running, started) {
        if (started && running == null) onDismiss()
    }
    ExportSheet(
        selected = format,
        enabled = { exportFormatEnabled(it, email) },
        running = running,
        onSelect = { formatName = it.name },
        onSave = { picker.launch(exportFileName(email.receivedAt, email.subject, format.ext)) },
        onShare = { started = true; viewModel.exportMessage(format, null, ownerId) },
        onCancel = { viewModel.cancelExport() },
        onDismiss = onDismiss,
    )
}

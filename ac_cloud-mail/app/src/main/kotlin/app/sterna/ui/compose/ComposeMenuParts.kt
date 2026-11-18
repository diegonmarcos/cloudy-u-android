package app.sterna.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.sterna.R
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.MailMetrics

/** A section header in the composer's overflow (#1800): a divider above (but the first), then the label. */
@Composable
internal fun ComposeMenuSection(@StringRes label: Int, first: Boolean = false) {
    if (!first) HorizontalDivider()
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
    )
}

/**
 * Add / manage templates (#1800): every saved template with Insert, Edit and Delete, and a form that
 * adds a new one or saves the one being edited. [onChange] persists the whole list.
 */
@Composable
internal fun ComposeTemplatesDialog(
    templates: List<ComposeTemplate>,
    onChange: (List<ComposeTemplate>) -> Unit,
    onInsert: (ComposeTemplate) -> Unit,
    onClose: () -> Unit,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.compose_templates_title)) },
        text = {
            Column(Modifier.heightIn(max = MailMetrics.s48 * 10).verticalScroll(rememberScrollState())) {
                if (templates.isEmpty()) {
                    Text(stringResource(R.string.compose_templates_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                templates.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                t.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onInsert(t) }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.compose_template_insert))
                        }
                        IconButton(onClick = { editingId = t.id; name = t.name; text = t.text }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.compose_template_edit))
                        }
                        IconButton(onClick = { onChange(ComposeTemplateCodec.remove(templates, t.id)); if (editingId == t.id) { editingId = null; name = ""; text = "" } }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.compose_template_delete))
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.compose_template_name)) },
                    modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s12),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3,
                    label = { Text(stringResource(R.string.compose_template_text)) },
                    modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s8),
                )
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val id = editingId ?: ("tpl-" + java.util.UUID.randomUUID().toString().take(8))
                        onChange(ComposeTemplateCodec.upsert(templates, ComposeTemplate(id, name.trim().ifBlank { text.trim().lineSequence().first().take(40) }, text)))
                        editingId = null; name = ""; text = ""
                    },
                ) { Text(stringResource(if (editingId == null) R.string.compose_template_add else R.string.compose_template_save)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.compose_template_close)) } },
    )
}

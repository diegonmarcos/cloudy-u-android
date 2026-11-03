package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #1419 Configs ▸ Git ▸ Auto Commit: the rule, a sample message, the writer (Rule | OpenRouter model) and the model picker. */
@Composable
fun AutoCommitSection() {
    val ctx = LocalContext.current.applicationContext
    var writer by remember { mutableStateOf(AutoCommitDrive.writer(ctx)) }
    var model by remember { mutableStateOf(AutoCommitDrive.model(ctx)) }
    var open by remember { mutableStateOf(false) }
    val models by produceState(emptyList<String>(), writer) {
        value = if (writer == AutoCommit.Writer.MODEL) withContext(Dispatchers.IO) { AutoCommitDrive.models(ctx) } else emptyList()
    }
    Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_AUTO_COMMIT).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad)) {
        Text(stringResource(R.string.git_auto_commit_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.git_auto_commit_rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(DriveMetrics.pad))
        Text(stringResource(R.string.git_auto_commit_sample), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = DriveMetrics.pad))
        Text(
            AutoCommit.message(AutoCommit.SAMPLE),
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(DriveMetrics.pad),
        )
        Text(stringResource(R.string.git_auto_commit_writer), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = DriveMetrics.pad))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            AutoCommit.Writer.values().forEach { w ->
                FilterChip(selected = writer == w, onClick = { writer = w; AutoCommitDrive.setWriter(ctx, w) }, label = { Text(w.label) })
                Spacer(Modifier.width(DriveMetrics.pad))
            }
        }
        if (writer == AutoCommit.Writer.MODEL) {
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(stringResource(R.string.git_auto_commit_model) + ": " + model.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    models.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; AutoCommitDrive.setModel(ctx, id); open = false }) }
                }
            }
            Text(stringResource(R.string.git_auto_commit_model_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

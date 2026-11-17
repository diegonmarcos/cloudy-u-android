package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.DuplicateChoice
import com.diegonmarcos.cloudagenda.data.ImportedEvent

object ImportTags {
    const val SHEET = "import_sheet"
    const val TITLE = "import_title"
    const val SAVE = "import_save"
    const val CANCEL = "import_cancel"
    fun project(name: String) = "import_project_$name"
    const val UPDATE = "import_dup_update"
    const val SKIP = "import_dup_skip"
}

/**
 * #1766 the New event sheet: the events another app handed over (Add to calendar, an .ics file), prefilled. The target project is a
 * chip row that starts on Inbox; events already imported (same UID) ask update or skip. Saving writes them into the store, so
 * they show in Circles, Linear Bars and Gantt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportSheet(controller: TasksController) {
    val events = controller.incoming
    if (events.isEmpty()) return
    val dupes = remember(events) { controller.duplicateCount(events) }
    var project by remember(events) { mutableStateOf<String?>(null) }   // null = Inbox
    var choice by remember(events) { mutableStateOf(DuplicateChoice.UPDATE) }
    var title by remember(events) { mutableStateOf(events.first().title) }
    val shown = if (events.size == 1) listOf(events.first().copy(title = title)) else events
    val names = controller.snapshot.projects.filter { !it.name.equals("Inbox", true) }
    SheetDialog(
        title = if (events.size == 1) "New event" else "New events (${events.size})",
        onDismiss = controller::dismissImport,
        modifier = Modifier.testTag(ImportTags.SHEET),
        actions = {
            TextButton(onClick = controller::dismissImport, modifier = Modifier.testTag(ImportTags.CANCEL)) { Text("Cancel") }
            TextButton(onClick = { controller.importIncoming(project, choice, shown) }, modifier = Modifier.testTag(ImportTags.SAVE)) { Text("Save") }
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (events.size == 1) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.testTag(ImportTags.TITLE))
            }
            for (e in shown) EventSummary(e, showTitle = events.size > 1)
            Text("Project", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(project == null, { project = null }, label = { Text("Inbox") }, modifier = Modifier.testTag(ImportTags.project("Inbox")))
                for (p in names) FilterChip(project == p.id, { project = p.id }, label = { Text(p.name) }, modifier = Modifier.testTag(ImportTags.project(p.name)))
            }
            if (dupes > 0) {
                Text(if (dupes == 1) "Already in Cloud Tasks (same UID)" else "$dupes events are already in Cloud Tasks (same UID)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(choice == DuplicateChoice.UPDATE, { choice = DuplicateChoice.UPDATE }, label = { Text("Update") }, modifier = Modifier.testTag(ImportTags.UPDATE))
                    FilterChip(choice == DuplicateChoice.SKIP, { choice = DuplicateChoice.SKIP }, label = { Text("Skip") }, modifier = Modifier.testTag(ImportTags.SKIP))
                }
            }
        }
    }
}

@Composable
private fun EventSummary(e: ImportedEvent, showTitle: Boolean) {
    Column {
        if (showTitle) Text(e.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
        val t = e.detailsText().lines()
        Text(t.first(), style = MaterialTheme.typography.bodyMedium)
        if (e.location.isNotBlank()) Text("Location: ${e.location}", style = MaterialTheme.typography.bodySmall)
        if (e.description.isNotBlank()) Text(e.description.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (e.attendees.isNotEmpty()) Text("Guests: " + e.attendees.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        if (e.rrule.isNotBlank()) Text("Repeats: ${e.rrule}", style = MaterialTheme.typography.bodySmall)
    }
}

package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.diegonmarcos.cloudagenda.data.DueBucket
import com.diegonmarcos.cloudagenda.data.TaskGrouping
import com.diegonmarcos.superapp.uikit.KitBadge
import com.diegonmarcos.superapp.uikit.KitBadgeChip
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitSkeletonRows
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import java.time.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskFiltering
import com.diegonmarcos.cloudagenda.data.TaskNode
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.links.newAttachment
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object TasksTags {
    const val PAGE = "tasks_page"
    const val HEADER = "tasks_header"
    const val LIST = "tasks_list"
    const val EMPTY = "tasks_empty"
    const val NO_MATCH = "tasks_no_match"
    const val LOADING = "tasks_loading"
    fun group(id: String) = "tasks_group_$id"
    fun actions(id: String) = "tasks_actions_$id"
    const val ADD_INPUT = "add_task_input"
    const val ADD = "add_task"
    fun row(id: String) = "task_row_$id"
    fun check(id: String) = "task_check_$id"
    fun sub(id: String) = "subtask_row_$id"
}

/** What the page needs from the app around it, beyond the controller: the mail source and the system pickers. */
interface TasksHost {
    val mail: MailSource
    fun pickDocument(kind: AttachmentKind)
    fun open(a: Attachment)
}

private val StringSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * The Tasks page. Supplies the hamburger drawer (projects, each expandable to its lists, collapsed by default), draws
 * the top filter bar over the selected list's tasks and subtasks, and opens the task editor.
 *
 * [pickedLink] is a link a system picker returned while the editor was open; the page hands it to the editor once.
 */
@Composable
fun TasksScreen(controller: TasksController, host: TasksHost, pickedLink: Attachment?, onPickedConsumed: () -> Unit, modifier: Modifier = Modifier, today: LocalDate = remember { LocalDate.now() }) {
    val snap = controller.snapshot
    val p = LocalKitPalette.current
    var expanded by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var request by remember { mutableStateOf<DrawerRequest?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagsOpen by remember { mutableStateOf(false) }
    var mailPicking by remember { mutableStateOf(false) }
    // A mail the Cloud Mail picker returned, waiting for the editor to take it into its draft.
    var mailLink by remember { mutableStateOf<Attachment?>(null) }

    TopBarDrawer { close ->
        ProjectsDrawerContent(
            snapshot = controller.snapshot, selectedListId = controller.selectedListId, expanded = expanded,
            onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
            onSelectList = { l -> controller.selectList(l.id); close() },
            onRequest = { request = it },
        )
    }

    Column(modifier.fillMaxSize().testTag(TasksTags.PAGE)) {
        FilterBar(controller.filter, snap.tags, controller::updateFilter, onManageTags = { tagsOpen = true })
        val list = snap.list(controller.selectedListId)
        if (list == null) {
            KitEmptyState("No list yet", "Open the menu, add a project, then a list.", Modifier.testTag(TasksTags.EMPTY))
        } else {
            val nodes = TaskFiltering.visible(snap, list.id, controller.filter)
            val crumb = (snap.project(list.projectId)?.name?.let { "$it  ›  " } ?: "") + list.name
            Text(crumb, color = p.textSecondary, fontSize = KitStoreRow.meta, modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(TasksTags.HEADER))
            if (!controller.loaded) KitSkeletonRows(modifier = Modifier.padding(KitDensity.small).testTag(TasksTags.LOADING))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(TasksTags.LIST)) {
                if (nodes.isEmpty()) {
                    item {
                        val filtered = controller.filter.active
                        Column(Modifier.fillMaxWidth().padding(24.dp).testTag(if (filtered) TasksTags.NO_MATCH else TasksTags.EMPTY), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (filtered) "No task matches the filter." else "No tasks in this list yet.", color = p.textSecondary, fontSize = KitStoreRow.body)
                            if (filtered) TextButton(onClick = { controller.updateFilter(controller.filter.clear()) }) { Text("Clear all") }
                        }
                    }
                }
                // Grouped by due date; a group is a Store section line, then one dense line per task and subtask.
                for ((bucket, rows) in TaskGrouping.byDue(nodes, today)) {
                    item(key = "group_" + bucket.name) {
                        Text(
                            bucket.label.uppercase() + "  ·  " + rows.size, color = if (bucket == DueBucket.OVERDUE) KitStoreRow.BAD else p.textSecondary,
                            fontSize = KitStoreRow.caption, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(start = KitStoreRow.padH, top = KitStoreRow.padH, bottom = KitStoreRow.tight).testTag(TasksTags.group(bucket.name)),
                        )
                    }
                    items(rows, key = { it.task.id }) { node ->
                        TaskNodeRows(node, snap, controller, today, onOpen = { editingId = it.id })
                    }
                }
                item { AddTaskRow(onAdd = { controller.addTask(list.id, it) }) }
            }
        }
    }

    // ── dialogs the drawer asked for ────────────────────────────────────────────────────────────────────
    when (val r = request) {
        null -> {}
        DrawerRequest.NewProject -> NameDialog("New project", "", { controller.addProject(it); request = null }, { request = null })
        is DrawerRequest.NewList -> NameDialog("New list in ${r.project.name}", "", {
            controller.addList(r.project.id, it); expanded = expanded + r.project.id; request = null
        }, { request = null })
        is DrawerRequest.RenameProject -> NameDialog("Rename project", r.project.name, { controller.renameProject(r.project.id, it); request = null }, { request = null })
        is DrawerRequest.RenameList -> NameDialog("Rename list", r.list.name, { controller.renameList(r.list.id, it); request = null }, { request = null })
        is DrawerRequest.DeleteProject -> KitConfirmDialog(
            "Delete project?", "\"${r.project.name}\" with all its lists, tasks and subtasks will be deleted.", "Delete", "Cancel",
            onConfirm = { controller.deleteProject(r.project.id); request = null }, onDismiss = { request = null },
        )
        is DrawerRequest.DeleteList -> KitConfirmDialog(
            "Delete list?", "\"${r.list.name}\" with all its tasks and subtasks will be deleted.", "Delete", "Cancel",
            onConfirm = { controller.deleteList(r.list.id); request = null }, onDismiss = { request = null },
        )
    }

    // ── the editor ──────────────────────────────────────────────────────────────────────────────────────
    val editing = snap.task(editingId)
    if (editing != null) {
        Dialog(onDismissRequest = { editingId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            TaskEditor(
                task = editing, snapshot = snap,
                actions = object : EditorActions {
                    override fun pickMail() { mailPicking = true }
                    override fun pickDocument(kind: AttachmentKind) = host.pickDocument(kind)
                    override fun open(a: Attachment) = host.open(a)
                },
                onSave = { controller.saveTask(it); editingId = null },
                onClose = { editingId = null },
                onDelete = { controller.deleteTask(editing.id); editingId = null },
                onOpenSubtask = { draft, sub -> controller.saveTask(draft); editingId = sub.id },
                onAddSubtask = { controller.addTask(editing.listId, it, parentId = editing.id) },
                onManageTags = { tagsOpen = true },
                pending = pickedLink ?: mailLink,
                onPendingConsumed = { if (pickedLink != null) onPickedConsumed(); mailLink = null },
            )
        }
    }
    if (mailPicking) {
        MailPickerDialog(host.mail, onPick = { m: MailItem ->
            mailPicking = false
            mailLink = newAttachment(AttachmentKind.MAIL, m.ref, m.subject.ifBlank { m.sender })
        }, onDismiss = { mailPicking = false })
    }
    if (tagsOpen) {
        TagsDialog(snap.tags, controller::addTag, controller::renameTag, controller::recolorTag, controller::deleteTag, onDismiss = { tagsOpen = false })
    }
}

@Composable
private fun TaskNodeRows(node: TaskNode, snap: Snapshot, controller: TasksController, today: LocalDate, onOpen: (Task) -> Unit) {
    TaskRow(node.task, snap, depth = 0, controller, today, onOpen)
    for (s in node.subtasks) TaskRow(s, snap, depth = 1, controller, today, onOpen)
}

private fun glyphOf(s: TaskStatus) = when (s) {
    TaskStatus.ACTION -> "◯"
    TaskStatus.WAITING -> "◔"
    TaskStatus.HOLD -> "⏸"
    TaskStatus.COMPLETED -> "✓"
    TaskStatus.ARCHIVED -> "▫"
}

private fun inkOf(s: TaskStatus) = when (s) {
    TaskStatus.ACTION -> KitStoreRow.MISSING
    TaskStatus.WAITING -> KitStoreRow.UPDATE
    TaskStatus.HOLD -> KitStoreRow.BAD
    TaskStatus.COMPLETED -> KitStoreRow.UP
    TaskStatus.ARCHIVED -> KitStoreRow.RULE
}

private fun stateOf(s: TaskStatus) = when (s) {
    TaskStatus.ACTION -> KitState.IDLE
    TaskStatus.WAITING -> KitState.WARN
    TaskStatus.HOLD -> KitState.BAD
    TaskStatus.COMPLETED -> KitState.OK
    TaskStatus.ARCHIVED -> KitState.IDLE
}

/**
 * One line in the Cloud Store's dense row (KitStoreRowLine): the status glyph (a tap completes), the name, then chips: project, due
 * date, status. The row's actions are collapsed behind one menu; a tap on the line opens the editor.
 */
@Composable
private fun TaskRow(t: Task, snap: Snapshot, depth: Int, controller: TasksController, today: LocalDate, onOpen: (Task) -> Unit) {
    val p = LocalKitPalette.current
    val done = t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ARCHIVED
    var menu by remember { mutableStateOf(false) }
    val due = TaskGrouping.due(t)
    val project = snap.project(snap.list(t.listId)?.projectId)?.name
    KitStoreRowLine(Modifier.testTag(if (depth == 0) TasksTags.row(t.id) else TasksTags.sub(t.id)), onClick = { onOpen(t) }) {
        Spacer(Modifier.width(KitStoreRow.step(14) * depth))
        KitStoreDot(
            glyphOf(t.status), inkOf(t.status),
            Modifier.testTag(TasksTags.check(t.id)).clickable(role = Role.Checkbox) { controller.setStatus(t.id, if (t.status == TaskStatus.COMPLETED) TaskStatus.ACTION else TaskStatus.COMPLETED) },
        )
        KitStoreName(t.name, Modifier.weight(1f), if (done) p.textSecondary else p.textPrimary)
        if (depth == 0 && project != null) KitBadgeChip(KitBadge(project), Modifier.widthIn(max = 72.dp).padding(start = KitStoreRow.tight))
        if (due != null) KitBadgeChip(
            KitBadge(due.toString().substring(5), if (!done && due.isBefore(today)) KitState.BAD else if (!done && due == today) KitState.WARN else null),
            Modifier.padding(start = KitStoreRow.tight),
        )
        KitBadgeChip(KitBadge(t.status.label, stateOf(t.status)), Modifier.padding(start = KitStoreRow.tight))
        Box {
            Text("⋯", color = p.textSecondary, fontSize = KitStoreRow.title, modifier = Modifier.clickable(role = Role.Button) { menu = true }.padding(start = KitStoreRow.gap, end = KitStoreRow.tight).testTag(TasksTags.actions(t.id)))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(t) })
                DropdownMenuItem(text = { Text(if (t.status == TaskStatus.COMPLETED) "Reopen" else "Complete") }, onClick = {
                    menu = false; controller.setStatus(t.id, if (t.status == TaskStatus.COMPLETED) TaskStatus.ACTION else TaskStatus.COMPLETED)
                })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; controller.setStatus(t.id, TaskStatus.ARCHIVED) })
            }
        }
    }
    HorizontalDivider(color = p.hairline.copy(alpha = 0.4f))
}

@Composable
private fun AddTaskRow(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text("New task") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TasksTags.ADD_INPUT))
        TextButton(onClick = { onAdd(text.trim()); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.testTag(TasksTags.ADD)) { Text("Add") }
    }
}

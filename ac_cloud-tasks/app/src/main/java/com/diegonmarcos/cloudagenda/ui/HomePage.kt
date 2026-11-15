package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.ProjectScopes
import com.diegonmarcos.cloudagenda.data.ProjectSelection
import com.diegonmarcos.cloudagenda.data.ProjectStatus
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.LocalDate

object HomeTags {
    const val PAGE = "home_page"
    const val SELECTOR = "home_selector"
    const val CHIP_ALL = "home_chip_all"
    const val CHIP_NONE = "home_chip_none"
    const val TOTAL = "home_total"
    const val SCOPE_LINE = "scope_line"
    fun chip(name: String) = "home_chip_$name"
    fun card(name: String) = "home_card_$name"
    fun sample(name: String) = "home_sample_$name"
    fun delete(name: String) = "home_delete_$name"
}

/** The one line every scoped page opens with: which projects feed it (the Home selector's choice). */
@Composable
fun ScopeLine(selection: ProjectSelection, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val text = when {
        selection.all -> "Projects: all"
        selection.none -> "Projects: none selected (open Home to pick)"
        else -> "Projects: " + selection.ids.sorted().joinToString(", ")
    }
    Text(text, color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(HomeTags.SCOPE_LINE))
}

/**
 * #1757 Home > Overview: the multi-select project selector (chips with All / None) and one status card per project (open, in progress, done,
 * overdue, next due). The selection decides which projects feed Inbox, Backlog, Tasks and Library, so projects are viewed separately or
 * merged; it is committed to the prefs by [ProjectScope.update]. A card tap toggles its project like its chip does. A sample project's
 * card carries a SAMPLE flag and a Delete sample action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomePage(items: List<MockTask>, snapshot: Snapshot, scope: ProjectScope, today: LocalDate, onDeleteProject: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val names = remember(items, snapshot) { ProjectScopes.names(items, snapshot) }
    val cards = remember(items, snapshot, today) { ProjectScopes.statuses(items, snapshot, names, today) }
    val sel = scope.selection
    var deleting by remember { mutableStateOf<ProjectStatus?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV).testTag(HomeTags.PAGE)) {
        MockNotice()
        Text("Projects", color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.padH))
        Text("Pick one or several: they feed Inbox, Backlog, Tasks and Library.", color = p.textSecondary, fontSize = KitStoreRow.caption)
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = KitStoreRow.gap).testTag(HomeTags.SELECTOR),
            horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap), verticalArrangement = Arrangement.spacedBy(KitStoreRow.gap),
        ) {
            Chip("All", sel.all, Modifier.testTag(HomeTags.CHIP_ALL)) { scope.update(ProjectSelection.ALL) }
            Chip("None", sel.none, Modifier.testTag(HomeTags.CHIP_NONE)) { scope.update(ProjectSelection.NONE) }
            for (n in names) Chip(n, sel.includes(n), Modifier.testTag(HomeTags.chip(n))) { scope.update(sel.toggle(n, names)) }
        }
        val picked = cards.filter { sel.includes(it.name) }
        StatusCard(ProjectScopes.total(picked).copy(name = if (sel.all) "All projects" else "Selected (" + picked.size + ")"), true, today, Modifier.testTag(HomeTags.TOTAL), summary = true)
        for (c in cards) {
            val on = sel.includes(c.name)
            StatusCard(c, on, today, Modifier.testTag(HomeTags.card(c.name)), onToggle = { scope.update(sel.toggle(c.name, names)) }, onDelete = { deleting = c })
        }
    }
    deleting?.let { c ->
        KitConfirmDialog(
            "Delete sample?", "\"${c.name}\" is sample data. It is deleted with all its lists and tasks and is not seeded again.", "Delete", "Cancel",
            onConfirm = { c.projectId?.let(onDeleteProject); deleting = null }, onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small)
    Text(
        label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(shape).background(if (on) p.accent else p.surface).border(1.dp, if (on) p.accent else p.hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight),
    )
}

@Composable
private fun StatusCard(c: ProjectStatus, on: Boolean, today: LocalDate, modifier: Modifier, summary: Boolean = false, onToggle: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small)
    Column(
        modifier.fillMaxWidth().padding(top = KitStoreRow.gap).alpha(if (on) 1f else 0.55f).clip(shape).background(p.surface)
            .border(1.dp, if (on && !summary) p.accent else p.hairline, shape).let { m -> if (onToggle != null) m.clickable(onClick = onToggle) else m }
            .padding(KitStoreRow.padH),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (c.sample) Text(
                "SAMPLE", color = p.tileInk, fontSize = KitStoreRow.caption, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(KitDensity.small)).background(KitStoreRow.UPDATE).padding(horizontal = KitStoreRow.gap, vertical = 1.dp).testTag(HomeTags.sample(c.name)),
            )
            if (!on && !summary) Text("  off", color = p.textSecondary, fontSize = KitStoreRow.caption)
        }
        // the bar: done | in progress | open, in the Store's status inks
        Row(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.gap).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.hairline.copy(alpha = 0.4f))) {
            if (c.done > 0) Box(Modifier.weight(c.done.toFloat()).height(6.dp).background(KitStoreRow.UP))
            if (c.inProgress > 0) Box(Modifier.weight(c.inProgress.toFloat()).height(6.dp).background(KitStoreRow.UPDATE))
            if (c.open > 0) Box(Modifier.weight(c.open.toFloat()).height(6.dp).background(KitStoreRow.MISSING))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Open", c.open, KitStoreRow.MISSING)
            Stat("In progress", c.inProgress, KitStoreRow.UPDATE)
            Stat("Done", c.done, KitStoreRow.UP)
            Stat("Overdue", c.overdue, if (c.overdue > 0) KitStoreRow.BAD else p.textSecondary)
        }
        Text(
            "Next due: " + (if (c.nextDue == null) "nothing dated" else (c.nextDueTitle ?: "") + "  ·  " + dayLabel(c.nextDue)),
            color = p.textSecondary, fontSize = KitStoreRow.meta, modifier = Modifier.padding(top = KitStoreRow.gap),
        )
        if (c.sample && onDelete != null) KitTextButton(onDelete, Modifier.testTag(HomeTags.delete(c.name))) { Text("Delete sample", color = KitStoreRow.BAD) }
    }
}

@Composable
private fun Stat(label: String, value: Int, ink: Color) {
    val p = LocalKitPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = ink, fontSize = KitDensity.head, fontWeight = FontWeight.Bold)
        Text(label, color = p.textSecondary, fontSize = KitStoreRow.caption)
    }
}

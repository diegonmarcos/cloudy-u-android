package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.sync.jobs.GitJobs
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.scrollFadeEnd
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow

/**
 * #1315 THE TOP BAR of the Git page, DECLARED (build.json ui.sync.git.top_bar) and drawn as one sticky block with the column
 * header: one line of split-menu buttons (Check / Sync / Clone / the overflow) and the Settings gear; one job line while a job
 * runs (or until its result is dismissed); the search field with the Filters button (and its active count); the quick-filter
 * rail with live counts; the declared summary. Every item is dispatched by the page on its declared `kind`; the enabled state and
 * its reason are [GitTopBarState]'s pure rules.
 */
object GitTopBarState {
    /** Why [item] cannot run now, or null: an exclusive item while a writing job runs, a scoped item with nothing in scope. */
    fun reason(item: Declarations.GitTopItemDecl, writingJob: String?, scopeCount: Int, labels: (String) -> String): String? = when {
        item.exclusive && writingJob != null -> labels("busy_with") + " " + writingJob
        (item.kind == "compound" || item.kind == "op" || item.kind == "pull") && scopeCount == 0 -> labels("nothing_in_scope")
        else -> null
    }

    /** The counters the summary line prints, in the declared order ("" for an unknown one). */
    fun summary(counters: List<String>, total: Int, cloned: Int, needsYou: Int, ahead: Int, behind: Int, checkedAge: String, labels: (String) -> String): String =
        counters.mapNotNull { c ->
            when (c) {
                "total" -> "$total ${labels("c_total")}"
                "cloned" -> "$cloned ${labels("c_cloned")}"
                "not_cloned" -> "${total - cloned} ${labels("c_not_cloned")}"
                "needs_you" -> "$needsYou ${labels("c_needs_you")}"
                "ahead" -> "$ahead↑"
                "behind" -> "$behind↓"
                "checked_age" -> if (checkedAge.isBlank()) null else "${labels("c_checked")} $checkedAge"
                else -> null
            }
        }.joinToString(" · ")
}

/** The split-menu toolbar: one button per declared group, its items in a dropdown; the Settings gear at the end. */
@Composable
internal fun GitTopBar(
    decl: Declarations.GitTopBarDecl,
    prefs: DrivePrefs?,
    enabledReason: (Declarations.GitTopItemDecl) -> String?,
    onItem: (Declarations.GitTopItemDecl) -> Unit,
) {
    var sheet by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_TOP_BAR), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        // #1356 Check | Sync | Clone | Logs | More ..... Settings: the groups scroll inside their own weighted row (the spacer),
        // so Settings stays at the far right on any width.
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
        decl.groups.forEach { g ->
            var open by remember(g.id) { mutableStateOf(false) }
            // #1356 a group of ONE item (Check, Sync, Clone, Logs) is a button: a tap runs it (a disabled one opens its menu, which says why).
            val single = g.items.singleOrNull()
            Box {
                Pill(g.label, { if (single != null && enabledReason(single) == null) onItem(single) else open = true }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_TOP_GROUP + "_" + g.id))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    g.items.forEach { item ->
                        val why = enabledReason(item)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.label, color = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    (why ?: item.hint.ifBlank { null })?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            },
                            leadingIcon = { Icon(IconCatalog.vectorOrDefault(item.icon.ifBlank { g.icon }), contentDescription = null) },
                            enabled = why == null,
                            onClick = { open = false; onItem(item) },
                            modifier = Modifier.testTag(DriveTags.SYNC_GIT_TOP_ITEM + "_" + item.id),
                        )
                    }
                }
            }
        }
        }
        Spacer(Modifier.width(DriveMetrics.gap))
        if (prefs != null) Row(Modifier.testTag(DriveTags.SYNC_AUTO_SETTINGS)) { IslandAction(Icons.Filled.Settings, decl.label("settings")) { sheet = true } }
    }
    if (sheet && prefs != null) AutoSettingsSheet(AutoChecks.Page.GIT, prefs) { sheet = false }
}

/** The one job line: the running job (or the last finished one until dismissed), its progress, Cancel / dismiss, a tap opens the report. */
@Composable
internal fun GitJobLine(job: GitJobs.State?, labels: (String) -> String, onCancel: () -> Unit, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val j = job ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset).testTag(DriveTags.SYNC_GIT_JOB_LINE)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${j.label} ${j.done}/${j.total}" + (if (j.failed.isNotEmpty()) " · ${j.failed.size} ${labels("failed")}" else "") + (if (j.cancelled) " · ${labels("cancelled")}" else ""),
                Modifier.weight(1f).padding(vertical = DriveMetrics.gap), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onOpen) { Text(labels("report")) }
            if (!j.finished) Pill(labels("job_cancel"), onCancel, danger = true) else Pill(labels("job_dismiss"), onDismiss)
        }
        if (!j.finished) LinearProgressIndicator(progress = { if (j.total == 0) 0f else j.done.toFloat() / j.total }, modifier = Modifier.fillMaxWidth())
    }
}

/** The search field and the Filters button (with its active count), then Clear when anything is on. */
@Composable
internal fun GitSearchRow(decl: Declarations.GitTopBarDecl, query: String, onQuery: (String) -> Unit, active: Int, onFilters: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        if (decl.search) GitSearchField(query, onQuery, decl.searchPlaceholder, Modifier.weight(1f).testTag(DriveTags.SYNC_GIT_SEARCH))
        else Spacer(Modifier.weight(1f))
        Pill(decl.label("filters") + (if (active > 0) " ($active)" else ""), onFilters, filled = active > 0, modifier = Modifier.testTag(DriveTags.SYNC_GIT_FILTERS_BUTTON))
        if (active > 0 || query.isNotBlank()) Pill(decl.label("clear") + " ×", onClear)
    }
}

/**
 * #1356 the compact search field: the shape of libs:ui-kit's KitSearchBar (the fleet's compact search)
 * at 40dp: a hairline-bordered row, the magnifier inline, small text, a clear (x) only while there is
 * text. No Material outlined field (56dp plus its label space) and no extra margins.
 */
@Composable
internal fun GitSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, tag: String = DriveTags.SYNC_GIT_SEARCH) {
    val shape = RoundedCornerShape(GitSearchMetrics.corner)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.height(GitSearchMetrics.height).clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(GitSearchMetrics.rule, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = GitSearchMetrics.inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(GitSearchMetrics.icon), tint = ink)
        Spacer(Modifier.width(GitSearchMetrics.gap))
        val style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true, textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).testTag(tag + ":field"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, style = style.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) Box(
            Modifier.size(GitSearchMetrics.height).clickable(onClickLabel = "Clear search") { onQuery("") }.testTag(tag + ":clear"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Clear search", Modifier.size(GitSearchMetrics.icon), tint = ink) }
        else Spacer(Modifier.width(GitSearchMetrics.inset))
    }
}

/** #1356 the compact search field's sizes (KitSearchBar's scale, 40dp tall). */
internal object GitSearchMetrics {
    val height = DriveMetrics.rowHeight
    val icon = DriveMetrics.icon
    val inset = DriveMetrics.pad + DriveMetrics.tight
    val gap = DriveMetrics.gapWide
    val corner = DriveMetrics.pad
    val rule = DriveMetrics.hairline
}

/** The quick-filter rail: every option declared `quick`, with its live count; a tap selects it (or its filter's default when already on). */
@Composable
internal fun QuickFilterRail(filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>, counts: (Declarations.GitFilterDecl) -> Map<String, Int>, onPick: (Declarations.GitFilterDecl, String) -> Unit) {
    val quick = filters.flatMap { f -> f.options.filter { it.quick }.map { f to it } }
    if (quick.isEmpty()) return
    val rail = androidx.compose.foundation.lazy.rememberLazyListState()
    // #1356 one sideways-scrolling row of compact chips; the end edge fades while more chips lie past it.
    LazyRow(Modifier.fillMaxWidth().scrollFadeEnd(rail.canScrollForward).testTag(DriveTags.SYNC_GIT_QUICK_FILTERS), state = rail, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DriveMetrics.sectionInset), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        items(quick, key = { it.first.id + ":" + it.second.id }) { (f, o) ->
            val n = counts(f)[o.id] ?: 0
            FilterChip(selected = RepoFilters.selected(f, selection) == o.id, onClick = { onPick(f, o.id) }, label = { Text("${o.label} ($n)", style = MaterialTheme.typography.labelSmall, maxLines = 1) }, modifier = Modifier.height(DriveMetrics.chipHeight))
        }
    }
}

/** The Filters sheet: every declared filter, its options as chips with their live counts; Clear all / Done. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun GitFilterSheet(decl: Declarations.GitTopBarDecl, filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>, counts: (Declarations.GitFilterDecl) -> Map<String, Int>, onPick: (Declarations.GitFilterDecl, String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(DriveTags.SYNC_GIT_FILTER_SHEET)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.padWide).padding(bottom = DriveMetrics.padWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
            Text(decl.label("filters"), style = MaterialTheme.typography.titleLarge)
            filters.forEach { f ->
                val c = counts(f)
                Column(Modifier.testTag(DriveTags.SYNC_GIT_FILTER_SHEET + "_" + f.id), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                    Text(f.label, style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                        f.options.forEach { o -> FilterChip(selected = RepoFilters.selected(f, selection) == o.id, onClick = { onPick(f, o.id) }, label = { Text("${o.label} (${c[o.id] ?: 0})") }) }
                    }
                }
            }
            PillRow {
                Pill(decl.label("clear_all"), onClear)
                Pill(decl.label("done"), onDismiss, filled = true)
            }
        }
    }
}

/** The confirmation of a declared item: its label, its hint, the target count; red when danger. */
@Composable
internal fun GitTopConfirm(item: Declarations.GitTopItemDecl, decl: Declarations.GitTopBarDecl, targets: Int, onConfirm: () -> Unit, onDismiss: () -> Unit, cancelLabel: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.label) },
        text = { Column { if (item.hint.isNotBlank()) Text(item.hint); Text(decl.label("confirm_targets").replace("%d", targets.toString())) } },
        confirmButton = { TextButton(onClick = onConfirm, colors = if (item.danger) androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.ButtonDefaults.textButtonColors()) { Text(item.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

/** The legend, GENERATED from the declared status icons (ui.sync.git.status.icons): one line per section, the kinds grouped by their light. */
@Composable
internal fun GitLegendDialog(decl: Declarations.GitTopBarDecl, icons: Map<String, Map<String, String>>, onDismiss: () -> Unit, closeLabel: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(decl.label("legend_title")) },
        text = {
            Column(Modifier.testTag(DriveTags.SYNC_GIT_LEGEND), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                (icons.ifEmpty { RepoStatus.DEFAULT_ICONS }).forEach { (section, kinds) ->
                    Text(decl.label("legend_" + section), style = MaterialTheme.typography.labelLarge)
                    kinds.entries.groupBy({ it.value }, { it.key }).forEach { (light, names) ->
                        val state = runCatching { com.diegonmarcos.clouddrive.ui.StatusLight.State.valueOf(light.uppercase()) }.getOrDefault(com.diegonmarcos.clouddrive.ui.StatusLight.State.UNKNOWN)
                        Text(com.diegonmarcos.clouddrive.ui.StatusLight.glyph(state) + "  " + names.joinToString(", ") { it.replace('_', ' ') }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(closeLabel) } },
    )
}

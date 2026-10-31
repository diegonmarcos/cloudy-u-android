package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.scrollFadeEnd
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusDot
import com.diegonmarcos.clouddrive.ui.StatusLight

/** #1138 the repo table's column width: the name column is wide, every other column is one table cell. */
internal fun repoCellModifier(c: RepoColumn): Modifier =
    Modifier.width(when (c) { RepoColumn.NAME -> DriveMetrics.tableName; RepoColumn.STATUS -> DriveMetrics.tap * 2; else -> DriveMetrics.tableCell }).padding(horizontal = DriveMetrics.gap)

@Composable
private fun columnLabel(c: RepoColumn): String = stringResource(when (c) {
    RepoColumn.STATUS -> R.string.repo_col_status
    RepoColumn.NAME -> R.string.repo_col_name
    RepoColumn.PROVIDER -> R.string.repo_col_provider
    RepoColumn.VISIBILITY -> R.string.repo_col_vis
    RepoColumn.LFS -> R.string.repo_col_lfs
    RepoColumn.SIZE_MAIN -> R.string.repo_col_size_main
    RepoColumn.SIZE_TOTAL -> R.string.repo_col_size_total
    RepoColumn.SIZE_DISK -> R.string.repo_col_size_disk
    RepoColumn.BRANCHES -> R.string.repo_col_branches
    RepoColumn.DIRTY -> R.string.repo_col_dirty
    RepoColumn.AHEAD_BEHIND -> R.string.repo_col_ab
    RepoColumn.LAST_COMMIT -> R.string.repo_col_last_commit
})

/** The sortable header: a tap on a column sorts by it, a second tap flips the direction. Scrolls with the rows ([scroll] is shared). */
@Composable
internal fun RepoTableHeader(column: RepoColumn, ascending: Boolean, scroll: ScrollState, tag: String = DriveTags.SYNC_GIT_TABLE_HEADER, onSort: (RepoColumn) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = DriveMetrics.tap).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // #1356 Status and Name are pinned (they never scroll off); the fact columns scroll sideways under the same state the rows use.
        val label: @Composable (RepoColumn) -> Unit = { c ->
            val arrow = if (c == column) (if (ascending) " ▲" else " ▼") else ""
            Text(
                columnLabel(c) + arrow, repoCellModifier(c).clickable { onSort(c) },
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                textAlign = when (c) { RepoColumn.NAME -> TextAlign.Start; RepoColumn.STATUS -> TextAlign.Center; else -> TextAlign.End }, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(start = DriveMetrics.sectionInset), verticalAlignment = Alignment.CenterVertically) {
            RepoTable.COLUMNS.filter { it in RepoTable.PINNED }.forEach { label(it) }
        }
        Row(Modifier.weight(1f).scrollFadeEnd(scroll.canScrollForward).horizontalScroll(scroll).padding(end = DriveMetrics.sectionInset), verticalAlignment = Alignment.CenterVertically) {
            RepoTable.COLUMNS.filter { it !in RepoTable.PINNED }.forEach { label(it) }
            // the rows' trailing disclosure cell: the last fact column scrolls fully into view
            Spacer(Modifier.width(DriveMetrics.tableName))
        }
    }
}

/** The nine fact cells after the name, in column order (the leftmost STATUS cell is [RepoStatusCell]). */
@Composable
internal fun RepoCells(r: RepoTableRow) {
    val yes = stringResource(R.string.manage_yes)
    val no = stringResource(R.string.manage_no)
    val texts = listOf(
        RepoColumn.PROVIDER to (r.provider ?: RepoTable.DASH),
        RepoColumn.VISIBILITY to RepoTable.visibility(r.isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub)),
        RepoColumn.LFS to RepoTable.lfs(r.lfs, stringResource(R.string.repo_lfs_lfs), stringResource(R.string.repo_lfs_normal)),
        RepoColumn.SIZE_MAIN to RepoTable.size(r.sizeMainBytes),
        RepoColumn.SIZE_TOTAL to RepoTable.size(r.sizeTotalBytes),
        RepoColumn.SIZE_DISK to RepoTable.size(r.sizeDiskBytes),
        RepoColumn.BRANCHES to RepoTable.count(r.branches),
        RepoColumn.DIRTY to RepoTable.dirty(r.dirtyFiles, yes, no),
        RepoColumn.AHEAD_BEHIND to RepoTable.aheadBehind(r.ahead, r.behind),
        RepoColumn.LAST_COMMIT to RepoTable.age(r.lastCommitMs, System.currentTimeMillis()),
    )
    texts.forEach { (c, t) ->
        Text(t, repoCellModifier(c), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** #1238 the leftmost cell: the status icon; a tap on it (not on the row) opens the repository's full-screen status window. */
@Composable
internal fun RepoStatusCell(icons: RepoStatus.Icons, name: String, onOpen: () -> Unit, caption: String = "") {
    val decl = com.diegonmarcos.clouddrive.Declarations.sync.git.status.icons
    Box(
        repoCellModifier(RepoColumn.STATUS).heightIn(min = DriveMetrics.tap).testTag(DriveTags.SYNC_GIT_STATUS_CELL)
            .clickable(onClickLabel = stringResource(R.string.repo_status_open), onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // LOCAL | SERVER | SYNC, each its own light with its own kind in the accessible label.
            Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                // "not checked yet" / "never" draw a hollow dotted circle, never the "?" of a read that does not end.
                fun glyph(l: StatusLight.State) = if (l == StatusLight.State.UNVERIFIABLE) "\u25CC" else StatusLight.glyph(l)
                val lo = RepoStatus.lightOf("local", icons.local, decl); val se = RepoStatus.lightOf("server", icons.server, decl); val sy = RepoStatus.lightOf("sync", icons.sync, decl)
                StatusDot(lo, "$name local ${icons.local.name.lowercase()}", glyph = glyph(lo))
                StatusDot(se, "$name server ${icons.server.name.lowercase()}", glyph = glyph(se))
                StatusDot(sy, "$name sync ${icons.sync.name.lowercase()}", glyph = glyph(sy))
            }
            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The label of one action: a string of its own for the added ids and the renamed declared ones, else the declared label. */
@Composable
internal fun actionLabel(id: String, declared: Declarations.GitOpDecl?): String = when (id) {
    RepoActions.SYNC_NO_WINNER -> stringResource(R.string.repo_act_sync_no_winner)
    RepoActions.SYNC_REMOTE_WINS -> stringResource(R.string.repo_act_sync_remote_wins)
    RepoActions.SYNC_LOCAL_WINS -> stringResource(R.string.repo_act_sync_local_wins)
    "stats" -> stringResource(R.string.repo_act_status)
    "remote" -> stringResource(R.string.repo_act_auth)
    "metadata" -> stringResource(R.string.repo_act_remote_url)
    RepoActions.META_BRANCH -> stringResource(R.string.repo_act_default_branch)
    RepoActions.META_SYNC -> stringResource(R.string.repo_act_last_sync)
    RepoActions.CHECKOUT_MAIN -> stringResource(R.string.repo_act_checkout_main)
    RepoActions.BRANCHES -> stringResource(R.string.repo_act_branches)
    RepoActions.PRS -> stringResource(R.string.repo_act_prs)
    RepoActions.UNPUSHED -> stringResource(R.string.repo_act_unpushed)
    RepoActions.OPEN_FOLDER -> stringResource(R.string.sync_open)
    "history" -> stringResource(R.string.repo_act_commit_history)
    RepoActions.RUNS -> stringResource(R.string.repo_act_runs)
    RepoActions.CLEAN_1, RepoActions.CLEAN_5, RepoActions.CLEAN_10 -> stringResource(R.string.repo_act_clean, RepoActions.cleanDepth(id))
    RepoActions.SYNC_NOW -> stringResource(R.string.sync_now)
    RepoActions.SETTINGS -> stringResource(R.string.sync_settings)
    RepoActions.CLONE_FULL -> stringResource(R.string.repo_act_clone_full)
    RepoActions.CLONE_DEPTH1 -> stringResource(R.string.repo_act_clone_depth1)
    RepoActions.CLONE_OAUTH -> stringResource(R.string.repo_act_clone_oauth)
    RepoActions.CLONE_SSH -> stringResource(R.string.repo_act_clone_ssh)
    else -> declared?.label ?: id
}

/**
 * #1289 The repository detail's menu: the DECLARED groups of ui.sync.git.repo_menu in their declared order, each a
 * title and its items (chips that wrap, or one item per line for a `lines` group); a `danger` subgroup is titled and
 * drawn red. Labels are the declared ones in the device language ([Declarations.GitMenuItemDecl.labelFor]), else
 * [actionLabel]. [active] is the chip to draw filled; [onAction] receives the item id; [slot] draws a group the page
 * renders itself (Configs-AutoSync) and returns true when it did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepoActionGroupsView(
    page: Declarations.GitPageDecl,
    busy: Boolean,
    blocked: (Declarations.GitOpDecl) -> Boolean,
    active: (String) -> Boolean,
    onAction: (String) -> Unit,
    /** #1195 an action that cannot run now (a not-cloned repository: clone-required ones), drawn disabled. */
    unavailable: (String) -> Boolean = { false },
    slot: @Composable (Declarations.GitMenuGroupDecl) -> Boolean = { false },
) {
    val lang = java.util.Locale.getDefault().language
    Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.pad), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        page.repoMenu.groups.forEach { g ->
            Column(Modifier.testTag(DriveTags.SYNC_GIT_MENU_GROUP + "_" + g.id), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                Text(
                    g.labelFor(lang), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = if (g.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!slot(g)) {
                    MenuItems(page, g, g.id, g.danger, lang, busy, blocked, active, onAction, unavailable)
                    g.subgroups.forEach { sub ->
                        Text(
                            sub.labelFor(lang), Modifier.padding(top = DriveMetrics.gap),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = if (sub.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MenuItems(page, sub, g.id, sub.danger || g.danger, lang, busy, blocked, active, onAction, unavailable)
                    }
                }
            }
        }
    }
}

/** #1289 one group's (or subgroup's) items: wrapping chips, or one per line for a `lines` group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuItems(
    page: Declarations.GitPageDecl, g: Declarations.GitMenuGroupDecl, groupId: String, danger: Boolean, lang: String, busy: Boolean,
    blocked: (Declarations.GitOpDecl) -> Boolean, active: (String) -> Boolean, onAction: (String) -> Unit, unavailable: (String) -> Boolean,
) {
    val chip: @Composable (Declarations.GitMenuItemDecl) -> Unit = { item ->
        val op = page.op(item.id)
        Pill(
            item.labelFor(lang).ifBlank { actionLabel(item.id, op) }, { onAction(item.id) },
            icon = op?.let { IconCatalog.vectorOrDefault(it.icon) },
            filled = active(item.id),
            // Settings, auth and the readers stay available while an operation runs; the verbs wait.
            enabled = (!busy || groupId in RepoActions.ALWAYS_ON_GROUPS) && !(op != null && blocked(op)) && !unavailable(item.id),
            danger = danger,
            modifier = Modifier.testTag(DriveTags.SYNC_GIT_MENU_ITEM + "_" + item.id),
        )
    }
    if (g.lines) Column(verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) { g.items.forEach { chip(it) } }
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) { g.items.forEach { chip(it) } }
}

/** The repo metadata as a key | value table; the values are selectable (long-press, copy). */
@Composable
internal fun RepoMetaTable(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_META_TABLE)) {
        Text(stringResource(R.string.repo_meta_table_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.tight)) {
                        Text(k, Modifier.width(DriveMetrics.tableName), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

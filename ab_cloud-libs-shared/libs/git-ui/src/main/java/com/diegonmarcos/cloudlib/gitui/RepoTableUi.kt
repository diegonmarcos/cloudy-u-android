package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow

/** #1138 the repo table's columns, in display order. #1238: STATUS is the leftmost and LFS follows Pvt/Pub. #1452: MESSAGE (Status Message) follows the name, so the status text has a cell of its own. */
/**
 * #1645 the light git stats LEAD (right after the name and its Status Message): Cloned, Divergence (AHEAD_BEHIND), Dirty/Stashed (DIRTY),
 * Detached HEAD, Unpushed, checked-out Branch (CURRENT_BRANCH); every one is a sortable column read from real git state.
 */
enum class RepoColumn { STATUS, NAME, MESSAGE, CLONED, AHEAD_BEHIND, DIRTY, DETACHED, UNPUSHED, CURRENT_BRANCH, PROVIDER, VISIBILITY, LFS, SIZE_MAIN, SIZE_TOTAL, SIZE_DISK, BRANCHES, LAST_COMMIT }

/** The string resource that labels [c] (a column without one is a programming error caught by RepoColumnTest). */
fun columnLabelRes(c: RepoColumn): Int = when (c) {
    RepoColumn.STATUS -> R.string.repo_col_status
    RepoColumn.NAME -> R.string.repo_col_name
    RepoColumn.MESSAGE -> R.string.repo_col_message
    RepoColumn.PROVIDER -> R.string.repo_col_provider
    RepoColumn.VISIBILITY -> R.string.repo_col_vis
    RepoColumn.LFS -> R.string.repo_col_lfs
    RepoColumn.SIZE_MAIN -> R.string.repo_col_size_main
    RepoColumn.SIZE_TOTAL -> R.string.repo_col_size_total
    RepoColumn.SIZE_DISK -> R.string.repo_col_size_disk
    RepoColumn.BRANCHES -> R.string.repo_col_branches
    RepoColumn.DIRTY -> R.string.repo_col_dirty
    RepoColumn.CLONED -> R.string.repo_col_cloned
    RepoColumn.DETACHED -> R.string.repo_col_detached
    RepoColumn.UNPUSHED -> R.string.repo_col_unpushed
    RepoColumn.CURRENT_BRANCH -> R.string.repo_col_branch
    RepoColumn.AHEAD_BEHIND -> R.string.repo_col_ab
    RepoColumn.LAST_COMMIT -> R.string.repo_col_last_commit
}

/** The header's sort arrow for [c]: none unless it is the sorted column. */
fun sortArrow(c: RepoColumn, sorted: RepoColumn, ascending: Boolean): String = if (c != sorted) "" else if (ascending) " ▲" else " ▼"

/** #1138 a column's width: the name column is wide, the message column wider, the status column two taps, every other column one table cell. */
@Composable
fun repoCellModifier(c: RepoColumn): Modifier {
    val k = LocalGitUiKit.current
    return Modifier.width(when (c) { RepoColumn.NAME -> k.tableName; RepoColumn.MESSAGE -> k.tableName * 1.5f; RepoColumn.STATUS -> k.tap * 2; else -> k.tableCell }).padding(horizontal = k.gap)
}

/**
 * The sortable header: a tap on a column sorts by it, a second tap flips the direction. [pinned] never scroll off;
 * [scrolling] move sideways under [scroll], the same state the rows use.
 */
@Composable
fun RepoTableHeader(
    column: RepoColumn, ascending: Boolean, scroll: ScrollState, pinned: List<RepoColumn>, scrolling: List<RepoColumn>,
    tag: String = LocalGitUiKit.current.tagTableHeader, onSort: (RepoColumn) -> Unit,
) {
    val k = LocalGitUiKit.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = KitStoreRow.padV).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label: @Composable (RepoColumn) -> Unit = { c ->
            Text(
                stringResource(columnLabelRes(c)) + sortArrow(c, column, ascending), repoCellModifier(c).clickable { onSort(c) },
                style = MaterialTheme.typography.labelSmall, fontSize = KitStoreRow.caption, fontWeight = FontWeight.SemiBold,
                textAlign = when (c) { RepoColumn.NAME, RepoColumn.MESSAGE -> TextAlign.Start; RepoColumn.STATUS -> TextAlign.Center; else -> TextAlign.End }, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(start = KitStoreRow.padH), verticalAlignment = Alignment.CenterVertically) { pinned.forEach { label(it) } }
        Row(Modifier.weight(1f).padding(end = k.pad).let { k.fadeEnd(it, scroll.canScrollForward) }.horizontalScroll(scroll).padding(end = KitStoreRow.padH), verticalAlignment = Alignment.CenterVertically) {
            scrolling.forEach { label(it) }
            Spacer(Modifier.width(k.tap))
        }
    }
}

/** The repo metadata as a key | value table; the values are selectable (long-press, copy). */
@Composable
fun RepoMetaTable(rows: List<Pair<String, String>>) {
    val k = LocalGitUiKit.current
    Column(Modifier.fillMaxWidth().padding(top = k.pad).testTag(k.tagMetaTable)) {
        Text(stringResource(R.string.repo_meta_table_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                rows.forEach { (key, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = k.tight)) {
                        Text(key, Modifier.width(k.tableName), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** The Pvt | Pub cell word of a repository in the device language; the dash when the visibility is not known. */
@Composable
fun repoVisibilityWord(isPrivate: Boolean?): String = GitTable.visibility(isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub))

/** The LFS | Normal cell word of a repository in the device language; the dash when the registry does not list it. */
@Composable
fun repoLfsWord(lfs: Boolean?): String = GitTable.lfs(lfs, stringResource(R.string.repo_lfs_lfs), stringResource(R.string.repo_lfs_normal))

/**
 * #1452 ONE LINE PER REPOSITORY, the Cloud Store Apps list's row: no pinned column (the whole line scrolls sideways on [scroll], the
 * state the header shares), a hairline-high compact row, every cell on one line. Both the main table and the folder tables draw
 * their rows through this, so the two cannot drift apart.
 */
@Composable
fun RepoTableLine(scroll: ScrollState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val k = LocalGitUiKit.current
    // The Cloud Store list row's own padding (KitStoreRow: 12 x 8 Store steps at the Store's scale); no height floor: the text sets the height.
    Row(
        modifier.fillMaxWidth().let { if (onClick != null) it.clickable(interactionSource = null, indication = null, onClick = onClick) else it }.padding(start = KitStoreRow.padH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(end = k.pad).let { k.fadeEnd(it, scroll.canScrollForward) }.horizontalScroll(scroll).padding(end = KitStoreRow.padH, top = KitStoreRow.padV, bottom = KitStoreRow.padV),
            verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}

/** The Name cell: the repository's name on one line (ellipsis), then its badges; it scrolls with the rest. */
@Composable
fun RepoNameCell(name: String, badges: @Composable RowScope.() -> Unit = {}) {
    Row(repoCellModifier(RepoColumn.NAME), verticalAlignment = Alignment.CenterVertically) {
        KitStoreName(name, Modifier.weight(1f, fill = false))
        badges()
    }
}

/** The Status Message cell: what the status icons cannot say, on one line (ellipsis; the status window has it whole); red when it is a problem. */
@Composable
fun RepoMessageCell(text: String, problem: Boolean = false) {
    KitStoreCaption(
        text.ifBlank { GitTable.DASH }, repoCellModifier(RepoColumn.MESSAGE),
        color = if (problem) KitStoreRow.BAD else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The cells after the name, in column order: Status Message, then the fact cells (the leftmost STATUS cell is [RepoStatusCell], then [RepoNameCell]). */
@Composable
fun RepoCells(r: GitTableRow<*>) {
    val yes = stringResource(R.string.repo_dirty_yes)
    val no = stringResource(R.string.repo_dirty_no)
    val yesNo: (Boolean?) -> String = { b -> if (b == null) GitTable.DASH else if (b) yes else no }
    val lead = listOf(
        RepoColumn.CLONED to yesNo(r.cloned),
        RepoColumn.AHEAD_BEHIND to GitTable.aheadBehind(r.ahead, r.behind),
        RepoColumn.DIRTY to GitTable.dirtyStashed(r.dirtyFiles, r.stashed, yes, no),
        RepoColumn.DETACHED to yesNo(r.detached),
        RepoColumn.UNPUSHED to GitTable.count(r.unpushed),
        RepoColumn.CURRENT_BRANCH to (r.branch ?: GitTable.DASH),
    )
    val texts = listOf(
        RepoColumn.PROVIDER to (r.provider ?: GitTable.DASH),
        RepoColumn.VISIBILITY to repoVisibilityWord(r.isPrivate),
        RepoColumn.LFS to repoLfsWord(r.lfs),
        RepoColumn.SIZE_MAIN to GitTable.size(r.sizeMainBytes),
        RepoColumn.SIZE_TOTAL to GitTable.size(r.sizeTotalBytes),
        RepoColumn.SIZE_DISK to GitTable.size(r.sizeDiskBytes),
        RepoColumn.BRANCHES to GitTable.count(r.branches),
        RepoColumn.LAST_COMMIT to GitTable.age(r.lastCommitMs, System.currentTimeMillis()),
    )
    RepoMessageCell(r.message, r.messageProblem)
    lead.forEach { (c, t) -> KitStoreCaption(t, repoCellModifier(c), mono = true, align = if (c == RepoColumn.CURRENT_BRANCH) TextAlign.Start else TextAlign.End) }
    texts.forEach { (c, t) ->
        KitStoreCaption(t, repoCellModifier(c), mono = true, align = TextAlign.End)
    }
}

/**
 * #1238 the leftmost cell: the host's status lights ([dots], LOCAL | SERVER | SYNC) over an optional caption; a tap on it (not
 * on the row) opens the repository's full-screen status window. The status model and its lights stay the host's.
 */
@Composable
fun RepoStatusCell(onOpen: () -> Unit, caption: String = "", dots: @Composable RowScope.() -> Unit) {
    val k = LocalGitUiKit.current
    Box(
        repoCellModifier(RepoColumn.STATUS).testTag(k.tagStatusCell)
            .clickable(onClickLabel = stringResource(R.string.repo_status_open), onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(k.gap), content = dots)
            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

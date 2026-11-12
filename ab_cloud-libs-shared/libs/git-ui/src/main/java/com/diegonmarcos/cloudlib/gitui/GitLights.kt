package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreRow

/**
 * #1670 THE FIVE LIGHTS of a repository row (they replace the three LOCAL | SERVER | SYNC lights): one per #1645 light git stat,
 * read from the SAME row model the stat columns print, so a light and its column can never disagree. Each light answers its
 * column's question: [YES], [NO] or [UNKNOWN] (the column prints the dash). The checked-out Branch stays a text column.
 */
enum class GitLight { YES, NO, UNKNOWN }

object GitLights {
    /** The five lights, in the column order of the table: Cloned, Divergence, Dirty/Stashed, Detached HEAD, Unpushed. */
    val COLUMNS: List<RepoColumn> = listOf(RepoColumn.CLONED, RepoColumn.AHEAD_BEHIND, RepoColumn.DIRTY, RepoColumn.DETACHED, RepoColumn.UNPUSHED)

    /** The answer that is the healthy one: a clone present; no divergence, nothing dirty or stashed, HEAD on a branch, nothing unpushed. */
    fun healthy(c: RepoColumn): GitLight = if (c == RepoColumn.CLONED) GitLight.YES else GitLight.NO

    fun of(r: GitTableRow<*>, c: RepoColumn): GitLight = when (c) {
        RepoColumn.CLONED -> yn(r.cloned)
        RepoColumn.AHEAD_BEHIND -> if (r.ahead == null || r.behind == null) GitLight.UNKNOWN else yn(r.ahead > 0 || r.behind > 0)
        RepoColumn.DIRTY -> when {
            (r.dirtyFiles ?: 0) > 0 || (r.stashed ?: 0) > 0 -> GitLight.YES
            r.dirtyFiles == null -> GitLight.UNKNOWN
            else -> GitLight.NO
        }
        RepoColumn.DETACHED -> yn(r.detached)
        RepoColumn.UNPUSHED -> r.unpushed?.let { yn(it > 0) } ?: GitLight.UNKNOWN
        else -> GitLight.UNKNOWN
    }

    fun of(r: GitTableRow<*>): List<Pair<RepoColumn, GitLight>> = COLUMNS.map { it to of(r, it) }

    private fun yn(b: Boolean?): GitLight = when (b) { null -> GitLight.UNKNOWN; true -> GitLight.YES; false -> GitLight.NO }

    /** The glyph of a light, in the Cloud Store's dot style: ✓ healthy, ⚠ a problem, ○ not cloned (not a fault), · unknown. */
    fun glyph(c: RepoColumn, l: GitLight): String = when {
        l == GitLight.UNKNOWN -> "·"
        l == healthy(c) -> "✓"
        c == RepoColumn.CLONED -> "○"
        else -> "⚠"
    }

    /** true = draw it red (a problem), false = green (healthy), null = dim (unknown, or not cloned). */
    fun problem(c: RepoColumn, l: GitLight): Boolean? = when {
        l == GitLight.UNKNOWN -> null
        l == healthy(c) -> false
        c == RepoColumn.CLONED -> null
        else -> true
    }
}

/**
 * #1670 the leftmost cell: the FIVE lights over the optional caption; a tap opens the full status window ([RepoStatusCell]).
 * Every table (main, folder tables) and every state (before, during, after a sync) draws its lights through this one cell.
 */
@Composable
fun RepoLightsCell(row: GitTableRow<*>, name: String, onOpen: () -> Unit, caption: String = "", lightTag: String = "status_light") {
    val yes = stringResource(R.string.repo_dirty_yes)
    val no = stringResource(R.string.repo_dirty_no)
    RepoStatusCell(onOpen, caption, dots = {
        GitLights.of(row).forEach { (c, l) -> GitLightDot(c, l, name, yes, no, lightTag) }
    })
}

/** One light, the Store dot ([KitStoreDot]) with its spoken description ("<name> <column> yes / no / unknown"). */
@Composable
fun GitLightDot(c: RepoColumn, l: GitLight, name: String, yes: String, no: String, tag: String = "status_light") {
    val ink = when (GitLights.problem(c, l)) { true -> KitStoreRow.BAD; false -> KitStoreRow.UP; null -> LocalContentColor.current.copy(alpha = 0.6f) }
    val said = name + " " + stringResource(columnLabelRes(c)) + " " + when (l) { GitLight.YES -> yes; GitLight.NO -> no; GitLight.UNKNOWN -> GitTable.DASH }
    KitStoreDot(GitLights.glyph(c, l), ink, Modifier.testTag(tag).semantics { contentDescription = said }, endPad = KitStoreRow.tight)
}

/** #1670 THE ONE TEXT STYLE of the status metadata (the #1645 status table's): label semibold, value monospace, both labelSmall. */
object GitStatusStyle {
    @Composable fun label(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    @Composable fun value(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
}

/**
 * #1670 THE ONE STATUS METADATA GROUP: every field (the #1645 status table, the older STATUS lines, the repository metadata) in ONE
 * key | value table and ONE text style ([GitStatusStyle]); the values are selectable (long-press, copy).
 */
@Composable
fun GitStatusTable(rows: List<Pair<String, String>>, title: String = stringResource(R.string.repo_status_title)) {
    val k = LocalGitUiKit.current
    Column(Modifier.fillMaxWidth().padding(top = k.gapWide).testTag(k.tagStatusBlock)) {
        Text(title, style = GitStatusStyle.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                rows.forEach { (key, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = k.tight)) {
                        Text(key, Modifier.width(k.tableName), style = GitStatusStyle.label())
                        Text(v, Modifier.weight(1f), style = GitStatusStyle.value())
                    }
                }
            }
        }
    }
}

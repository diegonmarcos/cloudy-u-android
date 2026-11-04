package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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

/** #1408 the header lives in libs:git-ui; Drive passes its column split (pinned | scrolling) from [RepoTable]. */
@Composable
internal fun RepoTableHeader(column: RepoColumn, ascending: Boolean, scroll: ScrollState, tag: String = DriveTags.SYNC_GIT_TABLE_HEADER, onSort: (RepoColumn) -> Unit) =
    com.diegonmarcos.cloudlib.gitui.RepoTableHeader(column, ascending, scroll, RepoTable.COLUMNS.filter { it in RepoTable.PINNED }, RepoTable.COLUMNS.filter { it !in RepoTable.PINNED }, tag, onSort)

/** #1238 the leftmost cell: the cell is libs:git-ui's; Drive draws its LOCAL | SERVER | SYNC lights from its status model. */
@Composable
internal fun RepoStatusCell(icons: RepoStatus.Icons, name: String, onOpen: () -> Unit, caption: String = "") {
    val decl = Declarations.sync.git.status.icons
    com.diegonmarcos.cloudlib.gitui.RepoStatusCell(onOpen, caption) {
        // "not checked yet" / "never" draw a hollow dotted circle, never the "?" of a read that does not end.
        fun glyph(l: StatusLight.State) = if (l == StatusLight.State.UNVERIFIABLE) "\u25CC" else StatusLight.glyph(l)
        val lo = RepoStatus.lightOf("local", icons.local, decl); val se = RepoStatus.lightOf("server", icons.server, decl); val sy = RepoStatus.lightOf("sync", icons.sync, decl)
        StatusDot(lo, "$name local ${icons.local.name.lowercase()}", glyph = glyph(lo))
        StatusDot(se, "$name server ${icons.server.name.lowercase()}", glyph = glyph(se))
        StatusDot(sy, "$name sync ${icons.sync.name.lowercase()}", glyph = glyph(sy))
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
 * #1289 The repository detail's menu: the DECLARED groups of ui.sync.git.repo_menu in their declared order. #1408 the drawing is
 * libs:git-ui's [GitMenuView]; Drive resolves the declaration into its model: labels are the declared ones in the device language
 * ([Declarations.GitMenuItemDecl.labelFor]), else [actionLabel]; [active] is the chip to draw filled; [onAction] receives the item
 * id; [slot] draws a group the page renders itself (Configs-AutoSync) and returns true when it did.
 */
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
    @Composable
    fun lib(g: Declarations.GitMenuGroupDecl): GitMenuGroup = GitMenuGroup(
        g.id, g.labelFor(lang), g.danger, g.lines, g.row,
        g.items.map { item ->
            val op = page.op(item.id)
            GitMenuItem(
                item.id, item.labelFor(lang).ifBlank { actionLabel(item.id, op) },
                icon = op?.let { IconCatalog.vectorOrDefault(it.icon) },
                rowIcon = IconCatalog.vectorOrDefault(item.icon.ifBlank { op?.icon.orEmpty() }),
                // Settings, auth and the readers stay available while an operation runs; the verbs wait (GitMenuView).
                available = !(op != null && blocked(op)) && !unavailable(item.id),
                active = active(item.id),
            )
        },
        g.subgroups.map { lib(it) },
    )
    val groups = page.repoMenu.groups.map { lib(it) }
    GitMenuView(groups, busy, RepoActions.ALWAYS_ON_GROUPS, onAction, slot = { lg -> slot(page.repoMenu.groups.first { it.id == lg.id }) })
}

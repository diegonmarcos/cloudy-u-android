package app.sterna.ui.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.core.data.mail.AlertCounts
import app.sterna.core.data.settings.SortOrder
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.MailMetrics

/** The three icon groups of the list's top bar, in the order they are drawn. */
internal enum class ListViewGroup(@StringRes val label: Int, val icon: ImageVector, val multi: Boolean) {
    VIEW_MODE(R.string.list_view_mode, Icons.Filled.ViewAgenda, multi = false),
    RANK(R.string.list_rank, Icons.AutoMirrored.Filled.Sort, multi = false),
    FILTER(R.string.list_filter, Icons.Filled.FilterList, multi = true),
}

/** What the controls show: this view's effective choices, and whether each group is off its default. */
internal data class ListViewUi(
    val group: GroupMode = ListViewDefaults.group,
    val rank: RankMode? = ListViewDefaults.rank,
    val filters: Set<ListFilter> = ListViewDefaults.filters,
    val viewModeActive: Boolean = false,
    val rankActive: Boolean = false,
    val filterActive: Boolean = false,
    /** The folder's All / Alerts / no-Alerts counts; null where the split is not offered (not the Inbox). */
    val alertCounts: AlertCounts? = null,
) {
    fun groupActive(g: ListViewGroup) = when (g) {
        ListViewGroup.VIEW_MODE -> viewModeActive
        ListViewGroup.RANK -> rankActive
        ListViewGroup.FILTER -> filterActive
    }

    companion object {
        fun of(view: ListView, globalConversation: Boolean, globalSort: SortOrder, alertCounts: AlertCounts? = null) = ListViewUi(
            group = view.effectiveGroup(globalConversation),
            rank = RankMode.entries.firstOrNull { it.order == view.effectiveSort(globalSort) },
            filters = view.filters,
            viewModeActive = view.viewModeActive(globalConversation),
            rankActive = view.rankActive(globalSort),
            filterActive = view.filterActive(),
            alertCounts = alertCounts,
        )
    }
}

/** What a tap on a function does; the ViewModel's three setters. */
internal class ListViewActions(
    val onGroup: (GroupMode) -> Unit,
    val onRank: (RankMode) -> Unit,
    val onFilter: (ListFilter) -> Unit,
    /** Reset to defaults: this folder's saved choices are forgotten. */
    val onReset: () -> Unit = {},
)

/**
 * Every function of the list's view controls, declared ONCE. The three icon menus and the overflow
 * dropdown both iterate this list, so the overflow cannot miss one and the two cannot disagree.
 */
internal enum class ListViewFunction(val group: ListViewGroup, @StringRes val label: Int) {
    GROUP_SUBJECT(ListViewGroup.VIEW_MODE, R.string.list_group_subject),
    GROUP_SENDER(ListViewGroup.VIEW_MODE, R.string.list_group_sender),
    GROUP_DOMAIN(ListViewGroup.VIEW_MODE, R.string.list_group_domain),
    GROUP_NONE(ListViewGroup.VIEW_MODE, R.string.list_group_none),
    RANK_NEWEST(ListViewGroup.RANK, R.string.inbox_sort_newest_first),
    RANK_SENDER(ListViewGroup.RANK, R.string.list_rank_sender),
    RANK_SUBJECT(ListViewGroup.RANK, R.string.list_rank_subject),
    FILTER_ALL(ListViewGroup.FILTER, R.string.list_filter_all),
    FILTER_STARRED(ListViewGroup.FILTER, R.string.list_filter_starred),
    FILTER_UNREAD(ListViewGroup.FILTER, R.string.list_filter_unread),
    FILTER_ATTACHMENTS(ListViewGroup.FILTER, R.string.list_filter_attachments),
    FILTER_AUTH_CODES(ListViewGroup.FILTER, R.string.list_filter_auth_codes),
    FILTER_AUTH_LINKS(ListViewGroup.FILTER, R.string.list_filter_auth_links),
    FILTER_AUTH_ANY(ListViewGroup.FILTER, R.string.list_filter_auth_any),

    /** The Inbox's two breakdown views; their names come from build.json::mail_inbox_split, so no string. */
    FILTER_ALERTS(ListViewGroup.FILTER, 0),
    FILTER_NO_ALERTS(ListViewGroup.FILTER, 0),
    RESET_DEFAULTS(ListViewGroup.FILTER, R.string.list_reset_defaults);

    /** Whether this function is offered in [ui]: the two alert views only where the counts exist (the Inbox). */
    fun visible(ui: ListViewUi): Boolean =
        (this != FILTER_ALERTS && this != FILTER_NO_ALERTS) || ui.alertCounts != null

    /** The text drawn: the label, with the folder's count on All and on the two alert views. */
    @Composable
    fun text(ui: ListViewUi): String {
        val rules = ListViewDefaults.alertRules
        val c = ui.alertCounts
        return when (this) {
            FILTER_ALERTS -> rules.labelAlerts + (c?.let { "  (${it.alerts})" } ?: "")
            FILTER_NO_ALERTS -> rules.labelNoAlerts + (c?.let { "  (${it.noAlerts})" } ?: "")
            FILTER_ALL -> stringResource(label) + (c?.let { "  (${it.all})" } ?: "")
            else -> stringResource(label)
        }
    }

    /** Whether this choice is the active one (checked) in [ui]. */
    fun isActive(ui: ListViewUi): Boolean = when (this) {
        GROUP_SUBJECT -> ui.group == GroupMode.SUBJECT
        GROUP_SENDER -> ui.group == GroupMode.SENDER
        GROUP_DOMAIN -> ui.group == GroupMode.DOMAIN
        GROUP_NONE -> ui.group == GroupMode.NONE
        RANK_NEWEST -> ui.rank == RankMode.NEWEST
        RANK_SENDER -> ui.rank == RankMode.SENDER
        RANK_SUBJECT -> ui.rank == RankMode.SUBJECT
        FILTER_ALL -> ui.filters.isEmpty()
        FILTER_STARRED -> ListFilter.STARRED in ui.filters
        FILTER_UNREAD -> ListFilter.UNREAD in ui.filters
        FILTER_ATTACHMENTS -> ListFilter.ATTACHMENTS in ui.filters
        FILTER_AUTH_CODES -> ListFilter.AUTH_CODES in ui.filters
        FILTER_AUTH_LINKS -> ListFilter.AUTH_LINKS in ui.filters
        FILTER_AUTH_ANY -> ListFilter.AUTH_ANY in ui.filters
        FILTER_ALERTS -> ListFilter.ALERTS in ui.filters
        FILTER_NO_ALERTS -> ListFilter.NO_ALERTS in ui.filters
        RESET_DEFAULTS -> false
    }

    fun run(actions: ListViewActions) = when (this) {
        GROUP_SUBJECT -> actions.onGroup(GroupMode.SUBJECT)
        GROUP_SENDER -> actions.onGroup(GroupMode.SENDER)
        GROUP_DOMAIN -> actions.onGroup(GroupMode.DOMAIN)
        GROUP_NONE -> actions.onGroup(GroupMode.NONE)
        RANK_NEWEST -> actions.onRank(RankMode.NEWEST)
        RANK_SENDER -> actions.onRank(RankMode.SENDER)
        RANK_SUBJECT -> actions.onRank(RankMode.SUBJECT)
        FILTER_ALL -> actions.onFilter(ListFilter.ALL)
        FILTER_STARRED -> actions.onFilter(ListFilter.STARRED)
        FILTER_UNREAD -> actions.onFilter(ListFilter.UNREAD)
        FILTER_ATTACHMENTS -> actions.onFilter(ListFilter.ATTACHMENTS)
        FILTER_AUTH_CODES -> actions.onFilter(ListFilter.AUTH_CODES)
        FILTER_AUTH_LINKS -> actions.onFilter(ListFilter.AUTH_LINKS)
        FILTER_AUTH_ANY -> actions.onFilter(ListFilter.AUTH_ANY)
        FILTER_ALERTS -> actions.onFilter(ListFilter.ALERTS)
        FILTER_NO_ALERTS -> actions.onFilter(ListFilter.NO_ALERTS)
        RESET_DEFAULTS -> actions.onReset()
    }

    companion object {
        fun of(group: ListViewGroup) = entries.filter { it.group == group }
    }
}

/**
 * The icon groups, in the owner's order: View mode, Rank, Filter. Each is one dense icon button that
 * opens its own small menu, and tints primary when any non-default choice is set. [unreadForced]
 * is the unread view, where the unread filter is on and cannot be lifted from here.
 */
@Composable
internal fun ListViewIconGroups(ui: ListViewUi, actions: ListViewActions, unreadForced: Boolean) {
    ListViewGroup.entries.forEach { group ->
        var open by remember { mutableStateOf(false) }
        // Boxed with its button: a DropdownMenu anchors on the node that contains it (#74).
        Box {
            IconButton(onClick = { open = true }) {
                Icon(
                    group.icon,
                    contentDescription = stringResource(group.label),
                    tint = if (ui.groupActive(group)) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
                ListViewFunction.of(group).forEach { fn ->
                    if (fn.visible(ui)) DropdownMenuItem(
                        text = { Text(fn.text(ui)) },
                        leadingIcon = { if (fn.isActive(ui)) Icon(Icons.Filled.Check, contentDescription = null) },
                        enabled = !(unreadForced && fn == ListViewFunction.FILTER_UNREAD),
                        onClick = {
                            fn.run(actions)
                            // A single choice closes its menu; the filters combine, so theirs stays open.
                            if (!group.multi || fn == ListViewFunction.RESET_DEFAULTS) open = false
                        },
                    )
                }
            }
        }
    }
}

/** Every function again, by name with a check on the active ones, for the overflow dropdown. */
@Composable
internal fun ColumnScope.ListViewOverflowItems(
    ui: ListViewUi,
    actions: ListViewActions,
    unreadForced: Boolean,
    onPicked: () -> Unit,
) {
    ListViewFunction.entries.forEach { fn ->
        if (fn.visible(ui)) DropdownMenuItem(
            text = { Text(fn.text(ui)) },
            leadingIcon = { if (fn.isActive(ui)) Icon(Icons.Filled.Check, contentDescription = null) },
            enabled = !(unreadForced && fn == ListViewFunction.FILTER_UNREAD),
            onClick = { fn.run(actions); onPicked() },
        )
    }
}

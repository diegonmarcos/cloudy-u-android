package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight

/**
 * #1408 the repository detail's menu model, resolved by the host from its declaration (ui.sync.git.repo_menu): the labels are
 * already in the device language, the icons already looked up. [available] = the host's own gate (a blocked op, a clone-required
 * action on a repo that is not cloned); the busy rule is this module's.
 */
data class GitMenuItem(val id: String, val label: String, val icon: ImageVector?, val rowIcon: ImageVector?, val available: Boolean = true, val active: Boolean = false)

/** One group (or subgroup): [lines] = one item per line, [row] = icon actions on ONE horizontal row, [danger] = drawn red. */
data class GitMenuGroup(
    val id: String, val label: String, val danger: Boolean = false, val lines: Boolean = false, val row: Boolean = false,
    val items: List<GitMenuItem> = emptyList(), val subgroups: List<GitMenuGroup> = emptyList(),
)

/**
 * The menu: [groups] in their given order, each a title and its items (chips that wrap, or one per line); a `danger` subgroup is
 * titled and drawn red. Settings, auth and the readers ([alwaysOn] group ids) stay available while an operation runs ([busy]); the
 * verbs wait. [onAction] receives the item id; [slot] draws a group the host renders itself and returns true when it did.
 */
@Composable
fun GitMenuView(
    groups: List<GitMenuGroup>, busy: Boolean, alwaysOn: Set<String>, onAction: (String) -> Unit,
    slot: @Composable (GitMenuGroup) -> Boolean = { false },
) {
    val k = LocalGitUiKit.current
    Column(Modifier.fillMaxWidth().padding(top = k.pad), verticalArrangement = Arrangement.spacedBy(k.gapWide)) {
        groups.forEach { g ->
            Column(Modifier.testTag(k.tagMenuGroup + "_" + g.id), verticalArrangement = Arrangement.spacedBy(k.gap)) {
                Text(
                    g.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = if (g.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!slot(g)) {
                    MenuItems(g, g.id, g.danger, busy, alwaysOn, onAction)
                    g.subgroups.forEach { sub ->
                        Text(
                            sub.label, Modifier.padding(top = k.gap),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = if (sub.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MenuItems(sub, g.id, sub.danger || g.danger, busy, alwaysOn, onAction)
                    }
                }
            }
        }
    }
}

/** One group's (or subgroup's) items: wrapping chips, one per line, or icon actions on one row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuItems(g: GitMenuGroup, groupId: String, danger: Boolean, busy: Boolean, alwaysOn: Set<String>, onAction: (String) -> Unit) {
    val k = LocalGitUiKit.current
    val enabled = { item: GitMenuItem -> (!busy || groupId in alwaysOn) && item.available }
    val chip: @Composable (GitMenuItem) -> Unit = { item ->
        k.pill(GitPill(item.label, { onAction(item.id) }, Modifier.testTag(k.tagMenuItem + "_" + item.id), item.icon, item.active, enabled(item), danger))
    }
    // layout "row": the items are icon actions on ONE horizontal row (Manual Sync, Dangerous); danger keeps the red.
    if (g.row) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(k.tagMenuGroup + "_row_" + g.id),
        horizontalArrangement = Arrangement.spacedBy(k.gapWide),
    ) {
        g.items.forEach { item ->
            val on = enabled(item)
            val ink = when { !on -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f); danger -> MaterialTheme.colorScheme.error; item.active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface }
            Column(
                Modifier.widthIn(min = k.rowHeight).clip(RoundedCornerShape(k.pad))
                    .clickable(enabled = on) { onAction(item.id) }.padding(horizontal = k.gap, vertical = k.tight)
                    .testTag(k.tagMenuItem + "_" + item.id),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item.rowIcon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(k.icon)) }
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
            }
        }
    }
    else if (g.lines) Column(verticalArrangement = Arrangement.spacedBy(k.gap)) { g.items.forEach { chip(it) } }
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(k.gapWide), verticalArrangement = Arrangement.spacedBy(k.gapWide)) { g.items.forEach { chip(it) } }
}

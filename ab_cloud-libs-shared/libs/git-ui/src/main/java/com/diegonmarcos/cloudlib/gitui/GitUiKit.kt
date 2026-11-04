package com.diegonmarcos.cloudlib.gitui

import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #1408 what the host lends the git UI: its metrics, its end-fade modifier and its test tags. The defaults are Cloud
 * Drive's own values, so a screen renders the same with or without a provider; Drive provides the fade (its Chrome).
 */
data class GitUiKit(
    val tight: Dp = 2.dp,
    val gap: Dp = 4.dp,
    val pad: Dp = 8.dp,
    val sectionInset: Dp = 10.dp,
    val tap: Dp = 36.dp,
    val gapWide: Dp = 6.dp,
    val rowHeight: Dp = 40.dp,
    val icon: Dp = 16.dp,
    val tableName: Dp = 160.dp,
    val tableCell: Dp = 76.dp,
    val fadeEnd: Modifier.(Boolean) -> Modifier = { this },
    val tagTableHeader: String = "sync_git_table_header",
    val tagMetaTable: String = "sync_git_meta_table",
    val tagStatusCell: String = "sync_git_status_cell",
    val tagStatusBlock: String = "sync_git_status_block",
    val tagWorkflow: String = "sync_git_workflow",
    val tagMenuGroup: String = "sync_git_menu_group",
    val tagMenuItem: String = "sync_git_menu_item",
    /** The host's pill button (Drive's own); the default is a plain Material button so a screen still works without a provider. */
    val pill: @Composable (GitPill) -> Unit = { p ->
        Button(p.onClick, p.modifier, enabled = p.enabled) { p.icon?.let { Icon(it, contentDescription = null) }; Text(p.text) }
    },
)

val LocalGitUiKit = staticCompositionLocalOf { GitUiKit() }

/** What the host's pill button draws: [filled] = the accent look, [danger] = the error look. */
data class GitPill(val text: String, val onClick: () -> Unit, val modifier: Modifier = Modifier, val icon: ImageVector? = null, val filled: Boolean = false, val enabled: Boolean = true, val danger: Boolean = false)

package com.diegonmarcos.cloudlib.gitui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    val tableName: Dp = 160.dp,
    val tableCell: Dp = 76.dp,
    val fadeEnd: Modifier.(Boolean) -> Modifier = { this },
    val tagTableHeader: String = "sync_git_table_header",
    val tagMetaTable: String = "sync_git_meta_table",
    val tagStatusCell: String = "sync_git_status_cell",
)

val LocalGitUiKit = staticCompositionLocalOf { GitUiKit() }

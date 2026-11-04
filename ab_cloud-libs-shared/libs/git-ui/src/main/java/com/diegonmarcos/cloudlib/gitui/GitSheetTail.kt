package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * #1408 the tail every repository sheet shares, cloned or not: the metadata table, the Close pill and, for a clone, a footer line
 * ([footer], e.g. how it syncs). What goes above it (status, menu, panels) is the host's, tied to its coordinator.
 */
@Composable
fun GitSheetTail(meta: List<Pair<String, String>>, closeLabel: String, onClose: () -> Unit, footer: String? = null) {
    val k = LocalGitUiKit.current
    RepoMetaTable(meta)
    PillLine { k.pill(GitPill(closeLabel, onClose)) }
    footer?.let { Text(it, Modifier.padding(top = k.pad), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

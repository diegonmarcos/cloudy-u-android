package com.diegonmarcos.cloudme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.health.Pill
import com.diegonmarcos.cloudme.health.accent

object FolderTreeTags {
    const val LIST = "me:foldertree:list"
    const val EMPTY = "me:foldertree:empty"
    fun row(path: String) = "me:foldertree:row:$path"
}

/** Tasks > RoadMaps (#1367; was RoadMap > Folders): the vault's folders as an expandable tree, section titles for the separators. Its own one scroller. */
@Composable
fun FolderTreeScreen(asset: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val sections = remember(asset) {
        runCatching { FolderTree.parse(CloudMeApiData.text(ctx, asset)) }.getOrDefault(emptyList())
    }
    var open by rememberSaveable { mutableStateOf(listOf<String>()) }
    val rows = remember(sections, open) { FolderTree.rows(sections, open.toSet()) }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Expand all", true, accent()) { open = FolderTree.containers(sections).toList() }
            Pill("Collapse all", false) { open = emptyList() }
        }
        if (rows.isEmpty()) {
            Text("No folder tree shipped with this build.", Modifier.padding(16.dp).testTag(FolderTreeTags.EMPTY), color = dim)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(FolderTreeTags.LIST), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(rows) { row ->
                when (row) {
                    is FolderRow.Title -> Text(row.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
                    is FolderRow.Item -> Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = row.folder.children.isNotEmpty()) {
                                open = if (row.folder.path in open) open - row.folder.path else open + row.folder.path
                            }
                            .testTag(FolderTreeTags.row(row.folder.path))
                            .padding(start = (12 + row.depth * 18).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                            if (row.folder.children.isNotEmpty()) Text(if (row.open) "▾" else "▸", color = dim)
                        }
                        Text("📁 " + row.folder.name, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

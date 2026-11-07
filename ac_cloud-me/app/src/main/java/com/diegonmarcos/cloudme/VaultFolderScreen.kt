package com.diegonmarcos.cloudme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VaultFolderTags {
    const val LIST = "me:vault:notes"
    const val EMPTY = "me:vault:empty"
    fun sub(path: String) = "me:vault:sub:$path"
    fun note(name: String) = "me:vault:note:$name"
}

/** Tasks > <row> > <chip> (#1398): one vault folder's notes; on a Library topic its sub-folders lead as chips and a tap shows that sub-folder's notes. */
@Composable
fun VaultFolderScreen(asset: String, path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val folder = remember(asset, path) {
        runCatching { VaultRows.find(VaultRows.parse(CloudMeApiData.text(ctx, asset)), path) }.getOrNull()
    }
    var sub by rememberSaveable(path) { mutableStateOf("") }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (folder == null) {
        Text("This folder is not in the vault snapshot shipped with this build.", modifier.padding(16.dp).testTag(VaultFolderTags.EMPTY), color = dim)
        return
    }
    val shown = folder.children.firstOrNull { it.path == sub } ?: folder
    Column(modifier.fillMaxSize()) {
        if (folder.children.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(folder) + folder.children).forEach { f ->
                    val on = f == shown
                    Text(if (f == folder) "All" else f.label, Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { sub = if (f == folder) "" else f.path }.testTag(VaultFolderTags.sub(f.path))
                        .padding(horizontal = 12.dp, vertical = 6.dp), color = ink, fontSize = 13.sp)
                }
            }
        }
        Text(shown.label, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("${shown.notes.size} notes · ${shown.path}", Modifier.padding(horizontal = 16.dp), color = dim, fontSize = 12.sp)
        if (shown.notes.isEmpty()) {
            Text("No notes in this folder yet.", Modifier.padding(16.dp).testTag(VaultFolderTags.EMPTY), color = dim)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(VaultFolderTags.LIST), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(shown.notes) { n ->
                Text("📝 $n", Modifier.fillMaxWidth().testTag(VaultFolderTags.note(n)).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

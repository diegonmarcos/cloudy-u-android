package com.diegonmarcos.cloudme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

object ProjHomeTags {
    const val GRID = "me:projhome:grid"
    fun header(id: String) = "me:projhome:group:$id"
    fun tile(id: String) = "me:projhome:tile:$id"
}

/** #1914 Proj > Home: every group of the Proj section as a header and one icon tile per leaf, all read from the declared page tree. Tapping a tile opens the leaf. */
@Composable
fun ProjHomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val onTarget = LocalMeTarget.current
    val groups = Sections.byId("proj")?.pages.orEmpty().filter { it.pages.isNotEmpty() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp), modifier = modifier.fillMaxWidth().testTag(ProjHomeTags.GRID),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (g in groups) {
            item(key = "h-${g.id}", span = { GridItemSpan(maxLineSpan) }) {
                Text(g.label, Modifier.padding(top = 8.dp).testTag(ProjHomeTags.header(g.id)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(g.pages, key = { it.id }) { leaf ->
                Column(Modifier.clickable { onTarget("page:proj/${leaf.id}") }.padding(8.dp).testTag(ProjHomeTags.tile(leaf.id)), horizontalAlignment = Alignment.CenterHorizontally) {
                    val res = ctx.resources.getIdentifier(leaf.icon, "drawable", ctx.packageName).takeIf { it != 0 }
                    if (res != null) Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(leaf.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

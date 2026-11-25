package com.diegonmarcos.cloudme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
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

/** #1918 the banner of every main category (data/web/me-proj-emoji.json, the Linktree banner files): its GIF is fetched once and drawn animated. */
object ProjBanners {
    fun urls(ctx: android.content.Context): Map<String, String> = runCatching {
        val b = org.json.JSONObject(ctx.assets.open("me-proj-emoji.json").bufferedReader().use { it.readText() }).getJSONObject("banners")
        b.keys().asSequence().associateWith { b.getJSONObject(it).getString("url") }
    }.getOrDefault(emptyMap())
}

@Composable
private fun BannerImage(url: String, tag: String) {
    val drawable = androidx.compose.runtime.produceState<android.graphics.drawable.Drawable?>(null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
            }.getOrNull()
        }
    }.value
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().height(140.dp).testTag(tag),
        factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
        update = { v -> v.setImageDrawable(drawable); (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start() },
    )
}

object ProjHomeTags {
    fun banner(id: String) = "me:projhome:banner:$id"
    const val GRID = "me:projhome:grid"
    fun header(id: String) = "me:projhome:group:$id"
    fun tile(id: String) = "me:projhome:tile:$id"
}

/** #1914 Proj > Home: every group of the Proj section as a header and one icon tile per leaf, all read from the declared page tree. Tapping a tile opens the leaf. */
@Composable
fun ProjHomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val onTarget = LocalMeTarget.current
    val banners = remember { ProjBanners.urls(ctx) }
    // #1918 the groups with leaves, and Repos (a leaf that is its own category): each main category shows its Linktree banner
    val groups = Sections.byId("proj")?.pages.orEmpty().filter { it.pages.isNotEmpty() || it.id in banners }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp), modifier = modifier.fillMaxWidth().testTag(ProjHomeTags.GRID),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (g in groups) {
            item(key = "h-${g.id}", span = { GridItemSpan(maxLineSpan) }) {
                Text(g.label, Modifier.padding(top = 8.dp).testTag(ProjHomeTags.header(g.id)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            banners[g.id]?.let { url -> item(key = "b-${g.id}", span = { GridItemSpan(maxLineSpan) }) { BannerImage(url, ProjHomeTags.banner(g.id)) } }
            if (g.pages.isEmpty()) item(key = "t-${g.id}") { Column(Modifier.clickable { onTarget("page:proj/${g.id}") }.padding(8.dp).testTag(ProjHomeTags.tile(g.id)), horizontalAlignment = Alignment.CenterHorizontally) { Text(g.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2) } }
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

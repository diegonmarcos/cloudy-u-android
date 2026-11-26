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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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

/**
 * #1918 the banner of every main category (data/web/me-proj-emoji.json, the Linktree banner files).
 * #1937 root cause of the empty block: the banner was fetched ONLY from https://linktree.diegonmarcos.com/ at draw time (the fleet edge); with the edge down the fetch failed,
 * the Drawable stayed null and the 140dp row drew nothing. Every banner is now a BUNDLED asset (`asset`, data/web/banners/<file>), read first; the network is only a fallback
 * for an asset that is missing, and a banner that cannot be loaded at all draws a visible titled panel, never an empty block.
 */
object ProjBanners {
    data class Entry(val url: String, val asset: String)

    fun parse(json: String): Map<String, Entry> = runCatching {
        val b = org.json.JSONObject(json).getJSONObject("banners")
        b.keys().asSequence().associateWith { b.getJSONObject(it).let { o -> Entry(o.optString("url"), o.optString("asset")) } }
    }.getOrDefault(emptyMap())

    fun entries(ctx: android.content.Context): Map<String, Entry> = runCatching {
        parse(ctx.assets.open("me-proj-emoji.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyMap())

    /** A GIF, WebP or PNG/JPEG file: the first bytes say so; anything else (an HTML error page, an empty body) is not a banner. */
    fun looksLikeImage(b: ByteArray?): Boolean {
        if (b == null || b.size < 12) return false
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return at(0, "GIF8") || (at(0, "RIFF") && at(8, "WEBP")) || (b[0] == 0x89.toByte() && at(1, "PNG")) || (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte())
    }

    /** The banner's bytes: the bundled asset first; only when it is missing or not an image, [fetch] (the network) for its url. Null = nothing to draw (the caller draws the panel). */
    fun bytes(entry: Entry, open: (String) -> java.io.InputStream?, fetch: (String) -> ByteArray?): ByteArray? {
        val bundled = if (entry.asset.isBlank()) null else runCatching { open(entry.asset)?.use { it.readBytes() } }.getOrNull()
        if (looksLikeImage(bundled)) return bundled
        val remote = if (entry.url.isBlank()) null else runCatching { fetch(entry.url) }.getOrNull()
        return remote?.takeIf { looksLikeImage(it) }
    }

    fun decode(bytes: ByteArray): android.graphics.drawable.Drawable? = runCatching {
        android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
    }.getOrNull()
}

/** #1937 the declared tile icon of every Proj page (`tile_icon` in build.json ui.sections proj): the project's own app icon or a distinct vector, bundled as a drawable. */
object ProjTileIcons {
    fun parse(sections: org.json.JSONArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(a: org.json.JSONArray?) { for (i in 0 until (a?.length() ?: 0)) { val o = a!!.optJSONObject(i) ?: continue
            o.optString("tile_icon").takeIf { it.isNotBlank() }?.let { out[o.optString("id")] = it }; walk(o.optJSONArray("pages")) } }
        for (i in 0 until sections.length()) sections.optJSONObject(i)?.takeIf { it.optString("id") == "proj" }?.let { walk(it.optJSONArray("pages")) }
        return out
    }

    val declared: Map<String, String> by lazy {
        runCatching { parse(org.json.JSONArray(String(android.util.Base64.decode(BuildConfig.UI_SECTIONS_B64, android.util.Base64.DEFAULT)))) }.getOrDefault(emptyMap())
    }

    /** The tile's text: the label without its leading emoji (the emoji stays on the top-nav chips and the area headings). */
    fun plainLabel(label: String): String = Regex("^[^\\p{L}\\p{N}]+").replace(label, "").trim().ifEmpty { label }
}

@Composable
private fun BannerImage(entry: ProjBanners.Entry, title: String, tag: String) {
    val ctx = LocalContext.current
    val drawable = androidx.compose.runtime.produceState<android.graphics.drawable.Drawable?>(null, entry) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProjBanners.bytes(entry, { ctx.assets.open(it) }, { u -> java.net.URL(u).openStream().use { it.readBytes() } })?.let { ProjBanners.decode(it) }
        }
    }.value
    if (drawable == null) {
        // loading, or nothing could be loaded: a visible titled panel of the banner's size, never an empty block
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(140.dp).testTag(tag).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        return
    }
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
    fun icon(id: String) = "me:projhome:icon:$id"
}

/** #1914 Proj > Home: every group of the Proj section as a header and one icon tile per leaf, all read from the declared page tree. Tapping a tile opens the leaf. */
@Composable
fun ProjHomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val onTarget = LocalMeTarget.current
    val banners = remember { ProjBanners.entries(ctx) }
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
            banners[g.id]?.let { entry -> item(key = "b-${g.id}", span = { GridItemSpan(maxLineSpan) }) { BannerImage(entry, ProjTileIcons.plainLabel(g.label), ProjHomeTags.banner(g.id)) } }
            if (g.pages.isEmpty()) item(key = "t-${g.id}") { Column(Modifier.clickable { onTarget("page:proj/${g.id}") }.padding(8.dp).testTag(ProjHomeTags.tile(g.id)), horizontalAlignment = Alignment.CenterHorizontally) { Text(ProjTileIcons.plainLabel(g.label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2) } }
            items(g.pages, key = { it.id }) { leaf ->
                Column(Modifier.clickable { onTarget("page:proj/${leaf.id}") }.padding(8.dp).testTag(ProjHomeTags.tile(leaf.id)), horizontalAlignment = Alignment.CenterHorizontally) {
                    // #1937 the project's own icon (declared `tile_icon`, bundled); never the shared globe: a missing drawable draws the label's initial in a badge
                    val res = ProjTileIcons.declared[leaf.id]?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) }?.takeIf { it != 0 }
                    if (res != null) Image(painterResource(res), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).testTag(ProjHomeTags.icon(leaf.id)))
                    else androidx.compose.foundation.layout.Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).testTag(ProjHomeTags.icon(leaf.id)), contentAlignment = Alignment.Center) {
                        Text(ProjTileIcons.plainLabel(leaf.label).take(1), fontWeight = FontWeight.Bold)
                    }
                    Text(ProjTileIcons.plainLabel(leaf.label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

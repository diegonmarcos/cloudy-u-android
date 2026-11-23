package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Store > Search > Phone and Torrent as an icon grid (#1772). Display only: the tiles are built from the same results and
 * recommendations the rows used, every field stays reachable (the tile's detail is the old row), and the model + adapters here
 * are pure so a test can prove nothing was dropped. Images are fetched at run time from the source listing and cached on the
 * device ([StoreImages]); nothing is bundled in an APK.
 */
object StoreGrid {
    /** The fleet launcher grid: 4 columns on a phone, 5 on a wide screen. */
    fun columns(widthDp: Int): Int = if (widthDp >= 400) 5 else 4
    fun <T> rows(items: List<T>, cols: Int): List<List<T>> = items.chunked(cols.coerceAtLeast(1))

    /** The launcher glyph edge (the Cloud tab grid's icon ceiling) and the larger torrent cover edge, in dp. */
    const val ICON_DP = 32
    const val COVER_DP = 48

    /** One grid tile: [imageUrl] empty = no image known (the generated [color] + [initial] tile is drawn instead). */
    class Tile(val key: String, val label: String, val badge: String, val sub: String, val imageUrl: String, val color: Int, val initial: String)

    private val PALETTE = intArrayOf(0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF8B5CF6.toInt(),
        0xFFEC4899.toInt(), 0xFF14B8A6.toInt(), 0xFFF97316.toInt(), 0xFF6366F1.toInt(), 0xFF84CC16.toInt(), 0xFF06B6D4.toInt(), 0xFFA855F7.toInt())

    /** A stable colour per category (or source) id: the same id is always the same colour. */
    fun colorOf(id: String): Int = PALETTE[Math.floorMod(id.hashCode(), PALETTE.size)]
    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    /** Only https image URLs are ever fetched. */
    fun usable(url: String): Boolean = url.startsWith("https://")

    /** A Phone result: its real icon from the source listing, the primary source as the badge. */
    fun phoneTile(r: StoreSuggest.Result, iconUrl: String, sourceLabel: (String) -> String): Tile =
        Tile(r.key, r.title, sourceLabel(r.primary), r.pkg, iconUrl, colorOf(r.primary), initial(r.title))

    /** The line the old Torrent recommendation row showed under the name (kept as the tile's detail). */
    fun recLine(rec: StoreRecs.Rec, st: StoreRecs.Stat?): String =
        StoreTorrentHost.sizeText(rec.sizeBytes) + "  ·  S " + StoreTorrentHost.seedText(st?.seeders ?: -1) + " / L " + StoreTorrentHost.seedText(st?.leechers ?: -1) +
            "  ·  " + (st?.sourceLabel?.ifEmpty { null } ?: rec.publisher.ifEmpty { "catalogue" }) + "  ·  " + rec.licence

    /** A catalogue recommendation: cover image (or the category-coloured initial), name, size, and the S/L badge when a source said. */
    fun torrentTile(rec: StoreRecs.Rec, st: StoreRecs.Stat?): Tile =
        Tile(rec.id, rec.name, if (st != null && st.seeders >= 0) "S ${st.seeders} / L ${maxOf(st.leechers, 0)}" else "", StoreTorrentHost.sizeText(rec.sizeBytes),
            rec.image.trim(), colorOf(rec.category), initial(rec.name))

    /** A tile's image plan: the URL to load, or null = draw the generated tile. */
    fun imagePlan(t: Tile): String? = t.imageUrl.takeIf { usable(it) }

    // ── composables ────────────────────────────────────────────────────

    /** Rows of tiles in the launcher's columns; after a row, the detail of each of its open items (full width). */
    @Composable
    fun <T> Grid(items: List<T>, isOpen: (T) -> Boolean, tile: @Composable (T, Modifier) -> Unit, detail: @Composable (T) -> Unit, tag: String? = null) {
        BoxWithConstraints(Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it }) {
            val cols = columns(maxWidth.value.toInt())
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                rows(items, cols).forEach { chunk ->
                    Row(Modifier.fillMaxWidth()) {
                        chunk.forEach { tile(it, Modifier.weight(1f)) }
                        repeat(cols - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                    chunk.filter(isOpen).forEach { detail(it) }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun TileView(t: Tile, modifier: Modifier, tag: String, imageDp: Int = ICON_DP, onClick: () -> Unit, onLongClick: () -> Unit = onClick, action: (@Composable () -> Unit)? = null) {
        val p = LocalKitPalette.current
        Column(modifier.testTag(tag).clip(RoundedCornerShape(KitDensity.corner)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(KitDensity.small / 2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sdp(StoreDensity.S2))) {
            TileImage(t, imageDp.dp)
            Text(t.label, color = p.textPrimary, fontSize = KitDensity.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (t.sub.isNotEmpty() && t.sub != t.label) Text(t.sub, color = p.textSecondary, fontSize = KitDensity.caption * 0.85f, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (t.badge.isNotEmpty()) Text(t.badge, color = p.textSecondary, fontSize = KitDensity.caption * 0.85f, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner)).background(p.surfaceSelected).padding(horizontal = KitDensity.small / 2))
            action?.invoke()
        }
    }

    @Composable
    fun TileImage(t: Tile, edge: Dp) {
        val ctx = LocalContext.current
        val url = imagePlan(t)
        val img: ImageBitmap? by produceState<ImageBitmap?>(null, url) { value = if (url == null) null else StoreImages.get(ctx, url) }
        val shape = RoundedCornerShape(edge / 4)
        val b = img
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(edge).clip(shape))
        else Box(Modifier.size(edge).clip(shape).background(Color(t.color)), contentAlignment = Alignment.Center) {
            Text(t.initial, color = Color.White, fontSize = KitDensity.title, fontWeight = FontWeight.Bold)
        }
    }
}

/** Run-time image loader: memory LRU + a file cache under the app's cache dir; https only, size-capped, never throws. */
object StoreImages {
    private val mem by lazy { LruCache<String, Bitmap>(96) }
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)
    private const val MAX_BYTES = 2_000_000
    private const val EDGE_PX = 192

    fun cacheName(url: String): String = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun get(ctx: Context, url: String): ImageBitmap? {
        mem.get(url)?.let { return it.asImageBitmap() }
        if (url in failed) return null
        return withContext(Dispatchers.IO) { gate.withPermit { load(ctx.applicationContext, url) } }?.also { mem.put(url, it) }?.asImageBitmap()
    }

    private fun load(ctx: Context, url: String): Bitmap? = runCatching {
        val dir = File(ctx.cacheDir, "store-images").apply { mkdirs() }
        val f = File(dir, cacheName(url))
        if (!f.isFile || f.length() == 0L) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "CloudStore/1 (icon fetch)")
            c.inputStream.use { ins -> val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); require(out.size() <= MAX_BYTES) { "too big" } }
                require(out.size() > 0) { "empty" }; f.writeBytes(out.toByteArray()) }
        }
        decode(f.readBytes())
    }.getOrNull().also { if (it == null) failed += url }

    private fun decode(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= EDGE_PX && o.outHeight / (s * 2) >= EDGE_PX) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}

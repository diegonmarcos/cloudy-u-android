package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.diegonmarcos.superapp.updater.Updater
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders one page's list of blocks. Thirteen block kinds cover every page in
 * the app that is not a library surface:
 *
 *   section_title  a heading between blocks
 *   note           a paragraph — used for honest empty states
 *   stats          label/value rows in a card
 *   cards          a card per item, with optional icon, body, meta rows,
 *                  progress — the shape Projects > Health > Gym renders a
 *                  whole training programme with, one item per movement
 *   link_grid      a four-column icon grid of navigation targets
 *   image_grid     a three-column photo grid, loaded from URLs
 *   bullets        a card of rows, each an optional bold `label` and its `text` (Achieves)
 *   quote          a quotation with its `by` line
 *   table          `columns` and `rows` ({cells, group?}) with an optional `link` row (the NomadMania table)
 *   link           one tappable row: `label` and `url`
 *   about          version, commit, build time and the update control
 *   permissions    the runtime permissions from build.json, requestable inline
 *   ledger         the owner's typed records and derived reports (Buro, LLC): see [LedgerBlock]
 *   profile_record a schema-driven record with status chips and PDF export (Profile > Health, DD): see [ProfileRecordBlock]
 *
 * Adding a page means adding JSON, not Kotlin. An unrecognised kind renders
 * its `title` and nothing else, so a typo shows up as a stub on the phone
 * rather than as a missing block nobody notices.
 *
 * 2026-10-16 owner decision: all apps must be Compose. Every block is a composable over the
 * me_* palette; a card or tile that declares a `target` goes through [LocalMeTarget].
 */
@Composable
fun StackScreen(stack: JSONArray, modifier: Modifier = Modifier) {
    // #1367 keyed to the page's stack: a page switch opens the new page at its top, not at the old page's offset.
    val scroll = remember(stack) { ScrollState(0) }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
    ) {
        var any = false
        for (i in 0 until stack.length()) {
            val block = stack.optJSONObject(i) ?: continue
            any = true
            Block(block)
        }
        if (!any) NoteCard("Nothing here yet", "")
    }
}

@Composable
private fun Block(o: JSONObject) {
    when (o.optString("kind")) {
        "section_title" -> Heading(o.optString("title"))
        "note" -> NoteCard(o.optString("title"), o.optString("body"))
        "stats" -> StatsCard(o)
        "cards" -> {
            Titles(o)
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { ItemCard(it) }
        }
        "link_grid" -> {
            Titles(o)
            LinkGrid(o.optJSONArray("links") ?: JSONArray())
        }
        "image_grid" -> {
            Titles(o)
            ImageGrid(o.optJSONArray("images") ?: JSONArray())
        }
        "bullets" -> BulletsCard(o)
        "quote" -> QuoteCard(o)
        "table" -> TableCard(o)
        "link" -> LinkRow(o.optString("label"), o.optString("url"))
        "about" -> AboutCard(o)
        "permissions" -> PermissionsCard(o)
        // Per section: one row, two rows or the side menu (persisted in nav_modes, part of the backup).
        "nav_modes" -> {
            Titles(o)
            com.diegonmarcos.superapp.navbartop2.NavModeConfigCard(Sections.all().map { it.nav })
        }
        "ledger" -> LedgerBlock(o)
        "profile_record" -> ProfileRecordBlock(o)
        // `fragment` is consumed by SectionScreen when it is a page's
        // only block; reaching here means it was mixed into a longer
        // stack, which this renderer has no container for.
        "fragment" -> Unit
        else -> o.optString("title").takeIf { it.isNotBlank() }?.let { Heading(it) }
    }
}

/** The optional heading and dim subtitle a grid or card list opens with. */
@Composable
private fun Titles(o: JSONObject) {
    o.optString("title").takeIf { it.isNotBlank() }?.let { Heading(it) }
    o.optString("subtitle").takeIf { it.isNotBlank() }?.let { Dim(it) }
}

// ── blocks ───────────────────────────────────────────────────────

@Composable
private fun StatsCard(o: JSONObject) {
    MeCard {
        o.optString("title").takeIf { it.isNotBlank() }?.let { CardTitle(it) }
        o.optString("subtitle").takeIf { it.isNotBlank() }?.let { Dim(it) }
        val rows = o.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            KeyValueRow(r.optString("label"), r.optString("value"))
        }
    }
}

@Composable
private fun BulletsCard(o: JSONObject) {
    MeCard {
        o.optString("title").takeIf { it.isNotBlank() }?.let { CardTitle(it) }
        val items = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val label = item.optString("label")
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (label.isNotBlank()) {
                    Text(label, color = colorResource(R.color.me_primary), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(item.optString("text"), color = colorResource(R.color.me_text), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun QuoteCard(o: JSONObject) {
    MeCard {
        Text("\"${o.optString("text")}\"", color = colorResource(R.color.me_text), fontSize = 15.sp, lineHeight = 22.sp,
            fontStyle = FontStyle.Italic)
        o.optString("by").takeIf { it.isNotBlank() }?.let { Dim("\u2014 $it") }
    }
}

/** `columns` header, then `rows`; a row flagged `group` is a bold subtotal that the rows under it belong to. */
@Composable
private fun TableCard(o: JSONObject) {
    val cols = o.optJSONArray("columns") ?: JSONArray()
    val rows = o.optJSONArray("rows") ?: JSONArray()
    MeCard {
        o.optString("title").takeIf { it.isNotBlank() }?.let { CardTitle(it) }
        TableRow((0 until cols.length()).map { cols.optString(it) }, group = false, header = true)
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val cells = r.optJSONArray("cells") ?: continue
            TableRow((0 until cells.length()).map { cells.optString(it) }, group = r.optBoolean("group"), header = false)
        }
        o.optJSONObject("link")?.let { LinkRow(it.optString("label"), it.optString("url")) }
    }
}

@Composable
private fun TableRow(cells: List<String>, group: Boolean, header: Boolean) {
    val ink = colorResource(if (header) R.color.me_text_dim else R.color.me_text)
    val weight = if (group || header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(top = if (group) 12.dp else 6.dp, start = if (group || header) 0.dp else 12.dp)) {
        cells.forEachIndexed { i, c ->
            Text(c, color = ink, fontSize = 13.sp, fontWeight = weight, modifier = Modifier.weight(if (i == 0) 2.4f else 1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val onTarget = LocalMeTarget.current
    Text(label, color = colorResource(R.color.me_primary), fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp).clickable { onTarget(url) })
}

@Composable
private fun ItemCard(o: JSONObject) {
    val onTarget = LocalMeTarget.current
    val accent = parseColor(o.optString("accent"), colorResource(R.color.me_primary))
    val target = o.optString("target").takeIf { it.isNotBlank() }
    MeCard(onClick = target?.let { t -> { onTarget(t) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A card that declares an `icon` shows the drawing instead of the
            // accent dot: the dot is a colour code for a card whose subject cannot
            // be pictured, and where the subject CAN be — a gym movement — the
            // picture is the thing the eye reads first and the dot is noise beside
            // it. The accent still tints the figure, so the colour coding survives.
            val icon = iconRes(LocalContext.current, o.optString("icon"))
            if (icon != null) {
                Image(painterResource(icon), null, Modifier.padding(end = 12.dp).size(40.dp), colorFilter = ColorFilter.tint(accent))
            } else {
                Box(Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape).background(accent))
            }
            CardTitle(o.optString("title"))
        }
        o.optString("subtitle").takeIf { it.isNotBlank() }?.let { Dim(it) }

        // `body` is the long form — a role's description, a course's
        // curriculum. Rendered like a note's body rather than as another dim
        // subtitle, because paragraphs need the line spacing.
        o.optString("body").takeIf { it.isNotBlank() }?.let { body ->
            Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 8.dp))
        }

        val meta = o.optJSONArray("meta") ?: JSONArray()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            KeyValueRow(m.optString("label"), m.optString("value"))
        }

        // Progress is optional and only drawn when declared, so a course
        // without a percentage does not render a permanently empty bar.
        if (o.has("progress")) {
            LinearProgressIndicator(
                progress = { o.optInt("progress", 0).coerceIn(0, 100) / 100f },
                color = accent, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun LinkGrid(links: JSONArray) {
    val tiles = (0 until links.length()).mapNotNull { links.optJSONObject(it) }
    Column(Modifier.fillMaxWidth()) {
        tiles.chunked(COLUMNS).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                rowTiles.forEach { LinkTile(it, Modifier.weight(1f)) }
                // Pad the last row so three tiles do not stretch to fill four slots.
                repeat(COLUMNS - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LinkTile(o: JSONObject, modifier: Modifier) {
    val onTarget = LocalMeTarget.current
    val target = o.optString("target")
    Box(modifier.padding(horizontal = 3.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.me_surface))
                .border(1.dp, colorResource(R.color.me_outline), RoundedCornerShape(14.dp))
                .then(if (target.isNotBlank()) Modifier.clickable { onTarget(target) } else Modifier)
                .padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            iconRes(LocalContext.current, o.optString("icon"))?.let { res ->
                Image(painterResource(res), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(colorResource(R.color.me_primary)))
            }
            Text(o.optString("label"), color = colorResource(R.color.me_text), fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A square photo grid, three across — the shape a picture feed has. Coil
 *  loads and caches each cell; the app already declares INTERNET and the
 *  URLs are the public CDN the web profile uses, so nothing new is
 *  exposed. A cell that fails to load stays the placeholder colour rather
 *  than collapsing the grid. */
@Composable
private fun ImageGrid(images: JSONArray) {
    val urls = (0 until images.length()).map { images.optString(it) }.filter { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        urls.chunked(PHOTO_COLUMNS).forEach { rowUrls ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowUrls.forEach { RemoteImage(it, Modifier.weight(1f)) }
                repeat(PHOTO_COLUMNS - rowUrls.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, url) {
        value = runCatching {
            val result = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).build())
            (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), RoundedCornerShape(14.dp)),
    ) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun AboutCard(o: JSONObject) {
    val ctx = LocalContext.current
    MeCard {
        CardTitle(o.optString("title").ifBlank { ctx.getString(R.string.app_name) })
        // one line per fact, divided; long values ellipsize instead of pushing the label off the card
        listOf("Version" to versionName(ctx), "Commit" to BuildConfig.GIT_SHORT_SHA, "Built" to BuildConfig.BUILD_TIMESTAMP, "Package" to ctx.packageName)
            .forEachIndexed { i, (k, v) ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = colorResource(R.color.me_outline), thickness = 1.dp)
                KeyValueRow(k, v, Modifier.padding(bottom = 8.dp))
            }
        com.diegonmarcos.superapp.uikit.KitTonalButton(onClick = { Updater.checkNow(ctx) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("me:about:check-updates")) { Text("Check for updates") }
    }
}

@Composable
private fun PermissionsCard(o: JSONObject) {
    // The page's own heading, then PermissionsList's summary and group cards; the one launcher lives in MainActivity.
    Titles(o)
    PermissionsList()
}

// ── small helpers ────────────────────────────────────────────────

@Composable
private fun MeCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) = Text(
    text, color = colorResource(R.color.me_text), fontSize = 17.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
)

@Composable
private fun CardTitle(text: String) =
    Text(text, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Dim(text: String) =
    Text(text, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))

@Composable
private fun NoteCard(title: String, body: String) = MeCard {
    if (title.isNotBlank()) CardTitle(title)
    if (body.isNotBlank()) {
        Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, maxLines = 1)
        Text(value, color = colorResource(R.color.me_text), fontSize = 13.sp, textAlign = TextAlign.End, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 16.dp))
    }
}

private fun versionName(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
}.getOrDefault("—")

private fun iconRes(ctx: Context, name: String): Int? {
    if (name.isBlank()) return null
    @Suppress("DiscouragedApi")
    val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    return if (id == 0) null else id
}

/** `#RRGGBB` / `#AARRGGBB`, falling back to the theme accent — a bad
 *  colour string in JSON must not take a whole page down. */
private fun parseColor(value: String, fallback: Color): Color =
    if (value.isBlank()) fallback
    else runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)

private const val COLUMNS = 4
private const val PHOTO_COLUMNS = 3

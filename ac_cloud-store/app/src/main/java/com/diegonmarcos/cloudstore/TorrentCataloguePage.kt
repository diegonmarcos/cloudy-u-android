package com.diegonmarcos.cloudstore

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.torrent.TorrentEngine
import com.diegonmarcos.superapp.torrent.TorrentFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the Torrent catalogue (#1681). */
object TorrentCatalogueTags {
    const val SECTION = "torrent:catalogue"
    const val TOGGLE = "torrent:catalogue:toggle"
    const val NOT_SERVED = "torrent:catalogue:not-served"
    const val EMPTY = "torrent:catalogue:empty"
    const val ALL = "torrent:catalogue:cat:all"
    fun category(id: String) = "torrent:catalogue:cat:$id"
    fun entry(id: String) = "torrent:catalogue:entry:$id"
    fun download(id: String) = "torrent:catalogue:download:$id"
    fun external(id: String) = "torrent:catalogue:external:$id"
}

/** What the catalogue section holds: the merged catalogue, whether the declared part was served, and the view state. */
internal data class CatalogueView(
    val catalogue: TorrentCatalogue.Catalogue = TorrentCatalogue.EMPTY,
    val served: Boolean = false,
    val loaded: Boolean = false,
    val open: Boolean = true,
    val category: String? = null,
)

/**
 * The catalogue as rows of the Torrent page's list: a heading that folds it, the category chips (All + every declared category with
 * its count, so an empty category is visible and can be filled later), then one dense row per entry: name, category, size,
 * publisher and licence, with Download (the fleet engine, libs:torrent) and Open in app (the user's torrent app, by chooser).
 */
internal fun LazyListScope.catalogueItems(
    ctx: Context,
    engine: TorrentEngine,
    view: CatalogueView,
    onView: (CatalogueView) -> Unit,
    onNote: (String) -> Unit,
    scope: CoroutineScope,
) {
    val c = view.catalogue
    item(key = "catalogue:head") {
        Row(Modifier.fillMaxWidth().testTag(TorrentCatalogueTags.SECTION).clickable { onView(view.copy(open = !view.open)) }
            .padding(top = StoreDensity.dpValue(StoreDensity.S8).dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Catalogue (${c.entries.size})", Modifier.weight(1f), fontSize = StoreDensity.T_TITLE.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(if (view.open) "Hide" else "Show", Modifier.testTag(TorrentCatalogueTags.TOGGLE), fontSize = StoreDensity.T_CAPTION.sp,
                color = MaterialTheme.colorScheme.primary)
        }
    }
    if (!view.open) return
    if (view.loaded && !view.served) item(key = "catalogue:not-served") {
        Text("The declared catalogue has not been served to this phone yet (fleet data syncs it at runtime). Your own entries from Configs > Torrent are shown.",
            Modifier.testTag(TorrentCatalogueTags.NOT_SERVED), fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item(key = "catalogue:chips") {
        val gap = StoreDensity.dpValue(StoreDensity.S4).dp
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
            FilterChip(view.category == null, { onView(view.copy(category = null)) }, { Text("All ${c.entries.size}", fontSize = StoreDensity.T_CAPTION.sp) },
                Modifier.testTag(TorrentCatalogueTags.ALL))
            c.categories.forEach { cat ->
                FilterChip(view.category == cat.id, { onView(view.copy(category = cat.id)) }, { Text("${cat.label} ${c.count(cat.id)}", fontSize = StoreDensity.T_CAPTION.sp) },
                    Modifier.testTag(TorrentCatalogueTags.category(cat.id)))
            }
        }
    }
    val shown = c.inCategory(view.category)
    if (shown.isEmpty()) item(key = "catalogue:empty") {
        Text(if (view.loaded) "No entry in this category yet. Add your own in Configs > Torrent." else "Loading the catalogue…",
            Modifier.testTag(TorrentCatalogueTags.EMPTY), fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(shown, key = { "catalogue:" + it.id }) { e -> CatalogueRow(ctx, engine, e, c, onNote, scope) }
}

@Composable
private fun CatalogueRow(ctx: Context, engine: TorrentEngine, e: TorrentCatalogue.Entry, c: TorrentCatalogue.Catalogue, onNote: (String) -> Unit, scope: CoroutineScope) {
    val h = StoreDensity.dpValue(StoreDensity.S12).dp
    val v = StoreDensity.dpValue(StoreDensity.S6).dp
    val cat = c.categories.firstOrNull { it.id == e.category }?.label ?: e.category
    Column(Modifier.fillMaxWidth().testTag(TorrentCatalogueTags.entry(e.id)).background(Color(0xFF1C1C24)).padding(h, v),
        verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S2).dp)) {
        Text(e.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(catalogueLine(e, cat), fontSize = StoreDensity.T_CAPTION.sp, color = Color(0x99FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
            Button({ scope.launch { onNote(withContext(Dispatchers.IO) { runCatching { TorrentCatalogue.download(engine, e) }.getOrElse { "${e.name}: ${it.message}" } }) } },
                Modifier.testTag(TorrentCatalogueTags.download(e.id))) { Text("Download", fontSize = StoreDensity.T_CAPTION.sp) }
            OutlinedButton({
                val i = TorrentCatalogue.externalIntent(e)
                onNote(if (i != null && runCatching { ctx.startActivity(i) }.isSuccess) "Handed ${e.name} to your torrent app." else "No app on this phone opens torrent links.")
            }, Modifier.testTag(TorrentCatalogueTags.external(e.id))) { Text("Open in app", fontSize = StoreDensity.T_CAPTION.sp) }
        }
    }
}

/** "Operating Systems · 6.0 GB · Canonical · GPL…" and "yours" for the user's own entries. */
internal fun catalogueLine(e: TorrentCatalogue.Entry, categoryLabel: String): String =
    listOfNotNull(categoryLabel, e.sizeBytes.takeIf { it > 0 }?.let { TorrentFormat.bytes(it) }, e.publisher.ifBlank { null }, e.licence.ifBlank { null },
        if (e.user) "yours" else null, if (e.magnet != null) "magnet" else ".torrent").joinToString(" · ")

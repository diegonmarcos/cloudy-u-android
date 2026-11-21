package com.diegonmarcos.cloudbrowser.sites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.diegonmarcos.superapp.uikit.KitLauncherFolderPopup
import com.diegonmarcos.superapp.uikit.KitLauncherFolderTile
import com.diegonmarcos.superapp.uikit.KitLauncherRows
import com.diegonmarcos.superapp.uikit.KitLauncherTile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudbrowser.offline.SiteOffline
import com.diegonmarcos.cloudlib.web.CachedSite
import com.diegonmarcos.cloudlib.web.CachedSites
import com.diegonmarcos.cloudlib.web.SiteMirrorRunner
import com.diegonmarcos.cloudlib.web.SiteMirrorStore
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserPermsList
import com.diegonmarcos.superapp.browser.BrowserSitePermissions
import com.diegonmarcos.superapp.uikit.KitDates
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitFileTree
import com.diegonmarcos.superapp.uikit.KitFileTreeInk
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitFileTree
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Work off the main thread whose answer lands in state: [work] runs once per [key] on its own thread. */
@Composable
private fun <T> loaded(initial: T, key: Any?, work: () -> T): State<T> =
    remember(key) { mutableStateOf(initial).also { st -> Thread { st.value = runCatching(work).getOrDefault(initial) }.start() } }

private fun rel(ts: Long) = KitDates.relative(Instant.ofEpochMilli(ts).toString())

// ───────────────────────────── Weby ─────────────────────────────

private sealed interface WebyDialog {
    data class AddBookmark(val path: String) : WebyDialog
    data object AddFolder : WebyDialog
    data class TileMenu(val path: String, val tile: WebyTile) : WebyDialog
    data class RemoveFolder(val path: String) : WebyDialog
}

/** A tile's icon: the site's own picture once fetched (cached on the phone), else a GREY square with the initial. */
@Composable
private fun WebyIcon(tile: WebyTile, sz: androidx.compose.ui.unit.Dp, icons: WebyIconCache, rev: Int, glyphs: Map<String, WebyGlyph> = emptyMap()) {
    // #1842 a Cloud web app wears the SuperApp's own icon (its vector drawable's paths, derived at build time), on a white rounded square
    val glyph = tile.icon?.let { glyphs[it] }
    if (glyph != null) { WebyGlyphIcon(glyph, sz, tile.id); return }
    val st = remember(tile.id, tile.url, rev) { mutableStateOf(icons.peek(tile)).also { s -> if (s.value == null) icons.request(tile) { s.value = it } } }
    val bmp = st.value
    Box(Modifier.size(sz).clip(RoundedCornerShape(sz / 4)).background(if (bmp == null) Color.Gray else Color.White), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().padding(sz / 12), contentScale = ContentScale.Fit)
        else Text(WebyIcons.initial(tile.label), color = Color.White, fontSize = KitDensity.display, fontWeight = FontWeight.Bold)
    }
}

/** #1842 a SuperApp icon drawn from its path data: the viewport scaled to the square, ink on a white rounded square. */
@Composable
private fun WebyGlyphIcon(g: WebyGlyph, sz: androidx.compose.ui.unit.Dp, id: String) {
    val paths = remember(g) { g.paths.map { PathParser().parsePathString(it).toPath() } }
    Box(Modifier.size(sz).clip(RoundedCornerShape(sz / 4)).background(Color.White).testTag("weby:glyph:$id"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(sz * 0.7f)) {
            scale(size.width / g.w, size.height / g.h, pivot = Offset.Zero) { paths.forEach { drawPath(it, Color(0xFF1F2937)) } }
        }
    }
}

/** One launcher cell, an app or a folder tile, as the SuperApp's phone grid draws them (shared KitLauncher pieces). */
@Composable
private fun WebyCell(
    cell: WebyLayout.Cell, path: String, modifier: Modifier, icons: WebyIconCache, rev: Int, launchable: Set<String?>, glyphs: Map<String, WebyGlyph>,
    onTile: (WebyTile) -> Unit, onTileLong: (WebyTile) -> Unit, onFolder: (String) -> Unit, onFolderLong: (String) -> Unit,
) {
    when (cell) {
        is WebyLayout.Cell.App -> KitLauncherTile(cell.tile.label, modifier.testTag("weby:tile:${cell.tile.id}"), enabled = cell.tile.url != null || cell.tile.pkg in launchable,
            onClick = { onTile(cell.tile) }, onLongClick = { onTileLong(cell.tile) }) { sz -> WebyIcon(cell.tile, sz, icons, rev, glyphs) }
        is WebyLayout.Cell.Folder -> {
            val sub = "$path/${cell.folder.title}"
            KitLauncherFolderTile(cell.folder.title, WebyLayout.previewTiles(cell.folder).map { tl -> @Composable { sz: androidx.compose.ui.unit.Dp -> WebyIcon(tl, sz, icons, rev, glyphs) } },
                modifier.testTag("weby:foldertile:$sub"), onOpen = { onFolder(sub) }, onLongClick = { onFolderLong(sub) })
        }
    }
}

/**
 * #1493/#1531 Weby: the SuperApp's phone apps as the Android launcher home screen, 8 columns by 8 rows: a titled section per group, its
 * apps then its folders as folder tiles (2x2 / 3x3 preview); a folder opens a popup with its own grid and title. Each icon is the
 * app's website icon (WebyIcons, cached), grey with the initial when there is none. A tap opens the site, a long-press the menu.
 */
@Composable
fun WebyPage(decl: SitesDecl, fixed: List<WebyFolder>? = null, down: Set<String> = emptySet(), onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val t = remember { decl.texts(decl.weby) }
    val store = remember { WebyStore(ctx, WebySeed.derive(decl.weby)) }
    val icons = remember { WebyIconCache(ctx, decl.weby) }
    var folders by remember { mutableStateOf(store.load()) }
    // #1634 the full Phone-apps catalogue: the SuperApp's folders and every app, derived on each open (never saved), the installed ones read off-thread
    val installed by produceState(emptyList<WebyInstalled>()) { value = withContext(Dispatchers.IO) { WebyInstalledApps.read(ctx) } }
    val catalogue = remember(installed) { WebyCatalogue.derive(decl.weby, installed) }
    val launchable = remember(installed) { installed.map { it.pkg }.toSet<String?>() }
    fun launch(tile: WebyTile): Boolean = tile.url?.let { onOpen(it); true } ?: tile.pkg?.takeIf { it in launchable }?.let { WebyInstalledApps.launch(ctx, it) } ?: false
    var dialog by remember { mutableStateOf<WebyDialog?>(null) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    fun commit(n: List<WebyFolder>) { folders = n; store.save(n) }
    // #1842 Weby splits into Quickmarks (the quick groups and the user's folders, editable) and Bookmarks (all): the browser's bookmarks, every
    // Quickmark and the full Phone-apps catalogue. Cloudy > WebApps draws THIS page over its own, fixed, folders (nothing editable).
    var view by remember { mutableStateOf(WEBY_QUICK) }
    val glyphs = remember { WebyGlyphs.parse(decl.cloudy.optJSONObject("webapps")?.optJSONObject("icons")) }
    val browserMarks = remember(view) { if (fixed == null && view == WEBY_ALL) runCatching { com.diegonmarcos.superapp.browser.BrowserBookmarks(ctx).all() }.getOrDefault(emptyList()).map { Triple(it.url, it.title, it.folder) } else emptyList() }
    val pageFolders: List<WebyFolder> = when {
        fixed != null -> fixed
        view == WEBY_ALL -> WebyBookmarks.all(folders, browserMarks, catalogue)
        else -> folders
    }
    fun editable(path: String) = fixed == null && view == WEBY_QUICK && !WebyCatalogue.isCatalogue(path)
    val sections = WebyOps.flatten(pageFolders)

    LazyVerticalGrid(
        GridCells.Fixed(WebyLayout.COLUMNS), Modifier.fillMaxSize().testTag("weby:grid"),
        contentPadding = PaddingValues(bottom = KitDensity.large),
    ) {
        if (fixed == null) item(span = { GridItemSpan(maxLineSpan) }) {
            KitSegmented(listOf(WEBY_QUICK to t("view_quickmarks"), WEBY_ALL to t("view_bookmarks")), view, { view = it }, Modifier.padding(KitStoreRow.padH).testTag("weby:views"), tag = "weby_views")
        }
        if (fixed == null && view == WEBY_QUICK) item(span = { GridItemSpan(maxLineSpan) }) {
            KitStoreRowLine(Modifier.testTag("weby:actions")) {
                KitStoreQuick(t("add_bookmark"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:add_bookmark"), onClick = { dialog = WebyDialog.AddBookmark(WebyOps.flatten(folders).firstOrNull()?.first.orEmpty()) })
                Spacer(Modifier.width(KitStoreRow.gap))
                KitStoreQuick(t("add_folder"), KitStoreRow.AMBER_FILL, Modifier.testTag("weby:add_folder"), onClick = { dialog = WebyDialog.AddFolder })
            }
        }
        if (pageFolders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { KitStoreRowLine { KitStoreCaption(t("empty"), color = p.textSecondary) } }
        for (f in pageFolders) {
            val path = f.title
            val derived = !editable(path)
            item(key = "h:$path", span = { GridItemSpan(maxLineSpan) }) {
                KitStoreRowLine(Modifier.testTag("weby:folder:$path")) {
                    KitStoreName(path, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(WebyLayout.cells(f).size.toString(), color = p.textSecondary)
                    if (!derived) {
                        Spacer(Modifier.width(KitStoreRow.gap))
                        KitStoreQuick(t("add_short"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:folder:$path:add"), onClick = { dialog = WebyDialog.AddBookmark(path) })
                        Spacer(Modifier.width(KitStoreRow.gap))
                        KitStoreQuick(t("remove_short"), KitStoreRow.BAD, Modifier.testTag("weby:folder:$path:remove"), onClick = { dialog = WebyDialog.RemoveFolder(path) })
                    }
                }
            }
            items(WebyLayout.cells(f), key = { c -> if (c is WebyLayout.Cell.App) "$path|${c.tile.id}" else "$path|f:${(c as WebyLayout.Cell.Folder).folder.title}" }) { c ->
                WebyCell(c, path, Modifier.fillMaxWidth().alpha(if (c is WebyLayout.Cell.App && c.tile.id in down) 0.45f else 1f), icons, rev, launchable, glyphs,
                    onTile = { tile -> launch(tile) }, onTileLong = { tile -> dialog = WebyDialog.TileMenu(path, tile) },
                    onFolder = { openPath = it }, onFolderLong = { if (editable(it)) dialog = WebyDialog.RemoveFolder(it) })
            }
        }
    }

    // The folder popup: its own grid and title; a tap on an app closes it and opens the site, a folder inside opens in its place.
    val shown = openPath?.let { op -> sections.firstOrNull { it.first == op } }
    if (shown != null) {
        val (path, f) = shown
        KitLauncherFolderPopup(f.title, onDismiss = { openPath = null }) {
            Column(Modifier.testTag("weby:popup:$path")) {
                KitLauncherRows(WebyLayout.cells(f), WebyLayout.POPUP_COLUMNS) { c, m ->
                    WebyCell(c, path, m, icons, rev, launchable, glyphs,
                        onTile = { tile -> if (launch(tile)) openPath = null }, onTileLong = { tile -> dialog = WebyDialog.TileMenu(path, tile) },
                        onFolder = { openPath = it }, onFolderLong = { if (editable(it)) dialog = WebyDialog.RemoveFolder(it) })
                }
            }
        }
    }

    when (val d = dialog) {
        is WebyDialog.AddBookmark -> {
            var title by remember { mutableStateOf("") }
            var url by remember { mutableStateOf("") }
            var folder by remember { mutableStateOf(d.path) }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("add_bookmark")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    TextField(title, { title = it }, label = { Text(t("field_title")) }, singleLine = true, modifier = Modifier.testTag("weby:field:title"))
                    TextField(url, { url = it }, label = { Text(t("field_url")) }, singleLine = true, modifier = Modifier.testTag("weby:field:url"))
                    TextField(folder, { folder = it }, label = { Text(t("field_folder")) }, singleLine = true, modifier = Modifier.testTag("weby:field:folder"))
                } },
                confirmButton = { TextButton({ commit(WebyOps.addBookmark(folders, folder, title, url)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
                dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        }
        is WebyDialog.AddFolder -> {
            var path by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("add_folder")) },
                text = { TextField(path, { path = it }, label = { Text(t("field_folder")) }, singleLine = true, modifier = Modifier.testTag("weby:field:folder")) },
                confirmButton = { TextButton({ commit(WebyOps.addFolder(folders, path)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
                dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        }
        is WebyDialog.TileMenu -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(d.tile.label) },
            text = { Column {
                d.tile.url?.let { u -> TextButton({ dialog = null; openPath = null; onOpen(u) }, Modifier.testTag("weby:menu:open")) { Text(t("menu_open")) } }
                if (d.tile.pkg in launchable) TextButton({ dialog = null; openPath = null; d.tile.pkg?.let { WebyInstalledApps.launch(ctx, it) } }, Modifier.testTag("weby:menu:launch")) { Text(t("menu_launch")) }
                TextButton({ dialog = null; icons.refresh(d.tile) { rev++ } }, Modifier.testTag("weby:menu:refresh_icon")) { Text(t("menu_refresh_icon")) }
                if (editable(d.path)) TextButton({ commit(WebyOps.removeBookmark(folders, d.path, d.tile.id)); dialog = null }, Modifier.testTag("weby:menu:remove")) { Text(t("menu_remove")) }
            } },
            confirmButton = {}, dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        is WebyDialog.RemoveFolder -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("remove_folder")) }, text = { Text(d.path) },
            confirmButton = { TextButton({ commit(WebyOps.removeFolder(folders, d.path)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
            dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        null -> Unit
    }
}

/** #1842 Weby's two views. */
private const val WEBY_QUICK = "quickmarks"
private const val WEBY_ALL = "bookmarks"

// ───────────────────────────── Cloudy ─────────────────────────────

/**
 * #1842 Cloudy: [page] is a ui.sections[cloudy].pages id, `webapps` or `c3_urls`. WebApps is Weby's own page over fixed folders (WebApps by Cloud
 * Store category, GitHub Pages, Local Webserver: [CloudyWebApps]); C3 Urls is one icon grid of the fleet's containers by c3-infra category
 * with a private / public chooser. A tap on a site opens it in the browser.
 */
@Composable
fun CloudyPage(page: String?, decl: SitesDecl, onOpen: (String) -> Unit) {
    val c = decl.cloudy
    val t = remember { decl.texts(c) }
    if (page == "c3_urls") C3UrlsPage(c, t, onOpen) else CloudyWebAppsPage(decl, c, t, onOpen)
}

@Composable
private fun CloudyWebAppsPage(decl: SitesDecl, c: org.json.JSONObject, t: (String) -> String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val cache = remember { CloudyCache(ctx) }
    val ws = c.optJSONObject("webserver") ?: org.json.JSONObject()
    val webapps = c.optJSONObject("webapps") ?: org.json.JSONObject()
    val timeout = c.optInt("timeout_ms", 0)
    val seed = remember { Cloudy.seedPages(c) }
    val gh by loaded(Cloudy.merge(seed, cache.pagesJson()), "gh") { Cloudy.merge(seed, cache.refresh(c.optString("pages_url"), timeout) ?: cache.pagesJson()) }
    val local by loaded(Cloudy.declaredWebserver(ws), "ws") {
        val store = runCatching { File(ws.optString("repos_store")).readText() }.getOrNull()
        Cloudy.declaredWebserver(ws) + Cloudy.storeEntries(store)
    }
    val up by loaded(emptyMap<Int, Boolean>(), local) { local.mapNotNull { it.port }.distinct().associateWith { Cloudy.probe(it, timeout) } }
    // the cloudy-h-webapps-mob repo's generated app-links.json: the web address of each Cloud app, kept on the phone once fetched
    val links by loaded(CloudyWebApps.links(cache.linksJson()), "links") { CloudyWebApps.links(cache.refreshLinks(webapps.optString("links_url"), timeout * 4) ?: cache.linksJson()) }
    val folders = remember(links, gh, local) { CloudyWebApps.derive(webapps, links, gh, local) }
    val down = remember(local, up) { local.filter { e -> e.port != null && up[e.port] == false }.map { it.url }.toSet() }
    Column(Modifier.fillMaxSize().testTag("cloudy:webapps")) {
        // The Cloud-Webserver APK: an explicit intent to its launcher activity, a clear message when it is not installed.
        KitStoreRowLine(Modifier.testTag("cloudy:ws:open"), onClick = { openWebserver(ctx, ws, t) }) {
            KitStoreName(t("ws_open"), Modifier.weight(1f), LocalKitPalette.current.accent)
            KitStoreDot("›", LocalKitPalette.current.textSecondary, endPad = KitStoreRow.tight)
        }
        Box(Modifier.weight(1f)) { WebyPage(decl, fixed = folders, down = down, onOpen = onOpen) }
    }
}

/** #1842 Cloudy C3 Urls: every c3-infra container ONCE in an icon grid by category; a tap opens the chooser (private / public), a tap on a row opens it, a long-press copies it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun C3UrlsPage(c: org.json.JSONObject, t: (String) -> String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val groups = remember { Cloudy.c3Groups(c.optJSONArray("routes"), c.optString("private_scheme"), c.optString("public_scheme")) }
    var chosen by remember { mutableStateOf<C3Container?>(null) }
    LazyVerticalGrid(GridCells.Fixed(WebyLayout.COLUMNS), Modifier.fillMaxSize().testTag("cloudy:c3"), contentPadding = PaddingValues(bottom = KitDensity.large)) {
        if (groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { KitStoreRowLine { KitStoreCaption(t("urls_empty"), color = p.textSecondary) } }
        for (g in groups) {
            item(key = "h:${g.category}", span = { GridItemSpan(maxLineSpan) }) {
                KitStoreRowLine(Modifier.testTag("cloudy:c3:category:${g.category}")) {
                    KitStoreName(g.category, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(g.containers.size.toString(), color = p.textSecondary)
                }
            }
            items(g.containers, key = { "${g.category}|${it.name}" }) { ct ->
                KitLauncherTile(ct.name, Modifier.fillMaxWidth().testTag("cloudy:c3:tile:${ct.name}"), onClick = { chosen = ct }) { sz ->
                    Box(Modifier.size(sz).clip(RoundedCornerShape(sz / 4)).background(Color.hsv((kotlin.math.abs(g.category.hashCode()) % 360).toFloat(), 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
                        Text(WebyIcons.initial(ct.name), color = Color.White, fontSize = KitDensity.display, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    chosen?.let { ct ->
        AlertDialog(onDismissRequest = { chosen = null }, title = { Text(ct.name) },
            text = { Column(Modifier.testTag("cloudy:c3:chooser")) {
                for ((key, label, url) in listOf(Triple("private", t("c3_private"), ct.privateUrl), Triple("public", t("c3_public"), ct.publicUrl))) {
                    Column(
                        Modifier.fillMaxWidth().testTag("cloudy:c3:choose:$key").alpha(if (url == null) 0.4f else 1f)
                            .combinedClickable(enabled = url != null, onClick = { chosen = null; onOpen(url!!) }, onLongClick = { copyUrl(ctx, url!!, t("c3_copied")) })
                            .padding(vertical = KitDensity.small),
                    ) {
                        Text(label, fontWeight = FontWeight.Bold)
                        Text(url ?: "–", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(t("c3_hint"), color = p.textSecondary)
            } },
            confirmButton = {}, dismissButton = { TextButton({ chosen = null }) { Text(t("cancel")) } })
    }
}

/** Copy [url] to the clipboard and say so. */
private fun copyUrl(ctx: android.content.Context, url: String, said: String) {
    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
    android.widget.Toast.makeText(ctx, said, android.widget.Toast.LENGTH_SHORT).show()
}

/** Start the Cloud-Webserver app (explicit intent to its launcher activity), or say it is not installed. */
private fun openWebserver(ctx: android.content.Context, ws: org.json.JSONObject, t: (String) -> String) {
    val target = Cloudy.webserverTarget(ws)
    val comp = target?.let { android.content.ComponentName(it.pkg, it.cls) }
    val installed = comp != null && runCatching { ctx.packageManager.getActivityInfo(comp, 0); true }.getOrDefault(false)
    if (comp == null || !installed) { android.widget.Toast.makeText(ctx, t("ws_not_installed"), android.widget.Toast.LENGTH_LONG).show(); return }
    runCatching { ctx.startActivity(android.content.Intent().setComponent(comp).setAction(android.content.Intent.ACTION_MAIN).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { android.widget.Toast.makeText(ctx, t("ws_not_installed"), android.widget.Toast.LENGTH_LONG).show() }
}

// ───────────────────────────── Domains ─────────────────────────────

/** #1493 Domains: [page] is a ui.sections[domains].pages id (headless_browsers | files | site_perms | history | visited). */
@Composable
fun DomainsPage(page: String?, decl: SitesDecl, onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val d = decl.domains
    val t = remember { decl.texts(d) }
    when (page) {
        "headless_browsers", null -> HeadlessPage(d, t, onOpen)
        "files" -> FilesPage(t)
        "site_perms" -> PermsPage(d, t)
        "history" -> HistoryPage(d, t)
        else -> VisitedPage(d, t)
    }
}

/** Headless Browsers: one card per ui.domains.headless service (title, tagline, stack bullets, note, tappable link row). */
@Composable
private fun HeadlessPage(d: org.json.JSONObject, t: (String) -> String, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val svcs = d.optJSONArray("headless")
    val rows = (0 until (svcs?.length() ?: 0)).mapNotNull { svcs?.optJSONObject(it) }
    LazyColumn(Modifier.fillMaxSize().testTag("domains:headless")) {
        items(rows, key = { it.optString("id") }) { s ->
            val id = s.optString("id")
            val stack = s.optJSONObject("stack")
            val link = s.optJSONObject("link")
            Column(Modifier.testTag("domains:headless:$id")) {
                KitStoreRowLine {
                    Column(Modifier.weight(1f)) {
                        KitStoreName(s.optString("title"), color = p.textPrimary)
                        KitStoreCaption(s.optString("tagline"), color = p.textSecondary)
                    }
                }
                KitStoreRowLine { KitStoreCaption(t("stack"), color = p.textSecondary) }
                for (k in listOf("engine", "proxy", "stealth", "output")) {
                    val v = stack?.optString(k).orEmpty()
                    if (v.isNotEmpty()) KitStoreRowLine { KitStoreCaption("\u2022 " + t(k) + ": " + v, color = p.textPrimary) }
                }
                s.optString("note").takeIf { it.isNotEmpty() }?.let { KitStoreRowLine { KitStoreCaption(it, color = p.textSecondary) } }
                if (link != null) KitStoreRowLine(Modifier.testTag("domains:headless:link:$id"), onClick = { onOpen(link.optString("url")) }) {
                    Column(Modifier.weight(1f)) {
                        KitStoreName(t("link") + ": " + link.optString("label"), color = p.accent)
                        KitStoreCaption(link.optString("url"), color = p.textSecondary, mono = true)
                        link.optString("detail").takeIf { it.isNotEmpty() }?.let { KitStoreCaption(it, color = p.textSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesPage(t: (String) -> String) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val files by loaded(emptyList<SiteFile>(), "files") { SiteCache.load(ctx) }
    val open = remember { mutableStateListOf<String>() }
    val entries = remember(files) { SiteCache.entries(files) }
    val rows = remember(files, open.toList()) { KitFileTree.rows(entries, open.toSet(), SiteCache.hosts(files)) }
    val ink = KitFileTreeInk(p.textPrimary, p.textSecondary, p.accent)
    // #1518 Cached sites: the sites pinned or downloaded into the web-core copy, with their size and files, Refresh | Unpin | Delete
    val runner = remember { SiteMirrorRunner.of(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var run by remember { mutableStateOf(runner.state) }
    DisposableEffect(runner) { runner.onState = { run = it; if (it.done) tick++ }; onDispose { runner.onState = null } }
    val cached by loaded(emptyList<CachedSite>(), tick) { CachedSites.rows(SiteMirrorStore.cache(ctx), SiteOffline.pinned(ctx)) }
    LazyColumn(Modifier.fillMaxSize().testTag("domains:files")) {
        cachedSites(cached, run, t, p,
            onRefresh = { h -> runner.refresh(h) { tick++ } },
            onUnpin = { h -> SiteOffline.setPinned(ctx, CachedSites.unpin(SiteOffline.pinned(ctx), h)); tick++ },
            onDelete = { h -> Thread { SiteOffline.setPinned(ctx, CachedSites.delete(SiteMirrorStore.cache(ctx), SiteOffline.pinned(ctx), h)); onMain { tick++ } }.start() },
            onStop = { runner.cancel() })
        if (files.isEmpty()) item { KitStoreRowLine { KitStoreCaption(t("files_empty"), color = p.textSecondary) } }
        kitFileTree(rows, ink, { com.diegonmarcos.superapp.browser.SiteData.human(it) }, { if (it <= 0L) "" else rel(it) },
            onToggle = { k -> if (!open.remove(k)) open.add(k) }, onOpen = { }, tagPrefix = "domains:files")
    }
}

/** #1518 the Cached sites section of Files Cached: one row per site (size, files, last refresh) and the running download's progress. */
private fun LazyListScope.cachedSites(
    rows: List<CachedSite>, run: SiteMirrorRunner.State, t: (String) -> String, p: com.diegonmarcos.superapp.uikit.KitPalette,
    onRefresh: (String) -> Unit, onUnpin: (String) -> Unit, onDelete: (String) -> Unit, onStop: () -> Unit,
) {
    item { KitStoreRowLine { KitStoreName(t("cached_sites"), Modifier.weight(1f), p.textPrimary) } }
    if (rows.isEmpty()) item { KitStoreRowLine { KitStoreCaption(t("cached_empty"), color = p.textSecondary) } }
    items(rows, key = { "cached:" + it.host }) { r ->
        KitStoreRowLine(Modifier.testTag("domains:cached:${r.host}")) {
            Column(Modifier.weight(1f)) {
                KitStoreName(r.host + if (r.pinned) " · " + t("pinned") else "", color = p.textPrimary)
                KitStoreCaption(
                    com.diegonmarcos.superapp.browser.SiteData.human(r.bytes) + " · " + r.files + " " + t("files") + " · " + t("refreshed") + " " +
                        if (r.lastRefresh <= 0L) t("never") else rel(r.lastRefresh),
                    color = p.textSecondary,
                )
            }
            KitStoreQuick(t("refresh"), KitStoreRow.BLUE_FILL, Modifier.testTag("domains:cached:refresh:${r.host}"), onClick = { onRefresh(r.host) })
            if (r.pinned) {
                Spacer(Modifier.width(KitStoreRow.tight))
                KitStoreQuick(t("unpin"), KitStoreRow.AMBER_FILL, Modifier.testTag("domains:cached:unpin:${r.host}"), onClick = { onUnpin(r.host) })
            }
            Spacer(Modifier.width(KitStoreRow.tight))
            KitStoreQuick(t("delete"), KitStoreRow.BAD, Modifier.testTag("domains:cached:delete:${r.host}"), onClick = { onDelete(r.host) })
        }
    }
    if (run.host.isNotEmpty() && (run.running || run.waiting.isNotEmpty() || run.message.isNotEmpty())) item {
        KitStoreRowLine(Modifier.testTag("domains:cached:progress")) {
            KitStoreCaption(
                run.host + " · " + (run.waiting.ifEmpty { run.message }.ifEmpty { run.current }) + " · " + run.files + " " + t("files") + " · " +
                    com.diegonmarcos.superapp.browser.SiteData.human(run.bytes) + if (run.pending > 0) " · " + run.pending + " …" else "",
                Modifier.weight(1f), p.textSecondary,
            )
            if (run.running || run.waiting.isNotEmpty()) KitStoreQuick(t("stop"), KitStoreRow.BAD, onClick = onStop)
        }
    }
}

@Composable
private fun PermsPage(d: org.json.JSONObject, t: (String) -> String) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var host by remember { mutableStateOf<String?>(null) }
    val tick = remember { mutableIntStateOf(0) }
    val sites by loaded(emptyList<String>(), tick.intValue) {
        Visited.everSeen(Visited.sites(VisitLogStore(ctx).sync()), BrowserSitePermissions(ctx).rules().keys.map { it.substringBefore('|') })
    }
    val h = host
    if (h == null) {
        LazyColumn(Modifier.fillMaxSize().testTag("domains:perms")) {
            if (sites.isEmpty()) item { KitStoreRowLine { KitStoreCaption(t("perms_empty"), color = p.textSecondary) } }
            items(sites, key = { it }) { s ->
                KitStoreRowLine(Modifier.testTag("domains:perms:site:$s"), onClick = { host = s }) {
                    KitStoreName(s, Modifier.weight(1f), p.textPrimary)
                    KitStoreDot("›", p.textSecondary, endPad = KitStoreRow.tight)
                }
            }
        }
    } else SiteDetail(h, d, t, onBack = { host = null; tick.intValue++ })
}

@Composable
private fun SiteDetail(host: String, d: org.json.JSONObject, t: (String) -> String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val perms = remember { BrowserConfig.parseBase64(com.diegonmarcos.cloudbrowser.BuildConfig.UI_BROWSER_CONFIG_B64).sitePerms }
    val store = remember { BrowserSitePermissions(ctx) }
    val actions = remember { d.optJSONArray("actions") ?: org.json.JSONArray() }
    val media = remember { (0 until (d.optJSONArray("media_mime_prefixes")?.length() ?: 0)).map { d.optJSONArray("media_mime_prefixes")!!.optString(it) } }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("domains:perms:detail")) {
        KitStoreRowLine(onClick = onBack) {
            KitStoreDot("‹", p.accent)
            KitStoreName(host, Modifier.weight(1f), p.textPrimary)
        }
        KitStoreRowLine { KitStoreCaption(t("actions"), color = p.textSecondary) }
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val id = a.optString("id")
            KitStoreRowLine(Modifier.testTag("domains:action:$id"), onClick = {
                Thread {
                    val ops = SiteActions.plan(id, host, SiteCache.cached(SiteCache.load(ctx)), media)
                    onMain {
                        if (ops != null) SiteActions.run(ops, AndroidSiteExec(ctx))
                        status = a.optString("label") + " · " + t("done")
                    }
                }.start()
            }) {
                KitStoreName(a.optString("label"), Modifier.weight(1f), p.textPrimary)
                KitStoreCaption(a.optString("detail"), color = p.textSecondary)
            }
        }
        if (status.isNotEmpty()) KitStoreRowLine(Modifier.testTag("domains:status")) { KitStoreCaption(status, color = KitStoreRow.UP) }
        Column(Modifier.padding(horizontal = KitStoreRow.padH)) {
            BrowserPermsList(host, perms, { store.resolve(host, it) }, { store.defaultOf(it) }, { store.hasOwn(host, it.id) },
                { perm, v -> store.set(host, perm.id, v) }, { perm -> store.reset(host, perm.id) })
        }
    }
}

@Composable
private fun HistoryPage(d: org.json.JSONObject, t: (String) -> String) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var mode by remember { mutableStateOf(d.optString("history_default", "pages")) }
    val browsed by loaded(emptyList<com.diegonmarcos.superapp.browser.BrowserVisit>(), "history") { VisitLogStore(ctx).sync() }
    // #1498 what agents did through the headless browser, tagged "agent" (title [agent:<name>] <command>), newest first with the browsing
    val visits = remember(browsed) {
        (browsed + com.diegonmarcos.superapp.headless.HeadlessRuntime.audit(ctx).map {
            com.diegonmarcos.superapp.browser.BrowserVisit(it.url.ifEmpty { "agent://headless" }, com.diegonmarcos.superapp.headless.HeadlessAudit.title(it), it.ts)
        }).sortedByDescending { it.ts }
    }
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxSize().testTag("domains:history")) {
        KitSegmented(optionsOf(d, "history_modes"), mode, { mode = it }, Modifier.padding(KitStoreRow.padH), tag = "history")
        LazyColumn(Modifier.fillMaxSize()) {
            if (visits.isEmpty()) item { KitStoreRowLine { KitStoreCaption(t("history_empty"), color = p.textSecondary) } }
            if (mode == "domains") { for (day in HistoryDays.domainsByDay(visits, zone)) {
                dayHeader(day.day, day.domains.sumOf { it.pages }, p.textSecondary, p.textPrimary)
                items(day.domains, key = { day.day + it.domain }) { x ->
                    KitStoreRowLine(Modifier.testTag("domains:history:domain:${day.day}:${x.domain}")) {
                        KitStoreName(x.domain, Modifier.weight(1f), p.textPrimary)
                        KitStoreCaption(x.pages.toString(), color = p.textSecondary)
                    }
                }
            } } else { for (day in HistoryDays.pagesByDay(visits, zone)) {
                dayHeader(day.day, day.pages.size, p.textSecondary, p.textPrimary)
                items(day.pages, key = { day.day + it.url + it.ts }) { v ->
                    KitStoreRowLine(Modifier.testTag("domains:history:page")) {
                        Column(Modifier.weight(1f)) {
                            KitStoreName(v.title, color = p.textPrimary)
                            KitStoreCaption(v.url.substringAfter("://"), color = p.textSecondary, mono = true)
                        }
                    }
                }
            }
            }
        }
    }
}

private fun LazyListScope.dayHeader(day: String, n: Int, dim: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) {
    item(key = "day:$day") {
        KitStoreRowLine(Modifier.testTag("domains:history:day:$day")) {
            KitStoreDot("▾", dim)
            KitStoreName(day, Modifier.weight(1f), ink)
            KitStoreCaption(n.toString(), color = dim)
        }
    }
}

@Composable
private fun VisitedPage(d: org.json.JSONObject, t: (String) -> String) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var by by remember { mutableStateOf(d.optString("visited_default", Visited.BY_LAST)) }
    val visits by loaded(emptyList<com.diegonmarcos.superapp.browser.BrowserVisit>(), "visited") { VisitLogStore(ctx).sync() }
    val sites = remember(visits, by) { Visited.sort(Visited.sites(visits), by) }
    Column(Modifier.fillMaxSize().testTag("domains:visited")) {
        KitSegmented(optionsOf(d, "visited_sorts"), by, { by = it }, Modifier.padding(KitStoreRow.padH), tag = "visited")
        LazyColumn(Modifier.fillMaxSize()) {
            if (sites.isEmpty()) item { KitStoreRowLine { KitStoreCaption(t("visited_empty"), color = p.textSecondary) } }
            items(sites, key = { it.domain }) { s ->
                KitStoreRowLine(Modifier.testTag("domains:visited:${s.domain}")) {
                    KitStoreName(s.domain, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(s.pages.toString(), color = p.textSecondary)
                    Spacer(Modifier.width(KitStoreRow.gap))
                    KitStoreCaption(rel(s.last), color = p.textSecondary, align = TextAlign.End)
                }
            }
        }
    }
}

/** A declared list of {id, label} (ui.domains.<key>) as KitSegmented's options. */
private fun optionsOf(d: org.json.JSONObject, key: String): List<Pair<String, String>> {
    val a = d.optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> o.optString("id") to o.optString("label") } }
}

package com.diegonmarcos.cloudbrowser.sites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
private fun WebyIcon(tile: WebyTile, sz: androidx.compose.ui.unit.Dp, icons: WebyIconCache, rev: Int) {
    val st = remember(tile.id, tile.url, rev) { mutableStateOf(icons.peek(tile)).also { s -> if (s.value == null) icons.request(tile) { s.value = it } } }
    val bmp = st.value
    Box(Modifier.size(sz).clip(RoundedCornerShape(sz / 4)).background(if (bmp == null) Color.Gray else Color.White), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().padding(sz / 12), contentScale = ContentScale.Fit)
        else Text(WebyIcons.initial(tile.label), color = Color.White, fontSize = KitDensity.display, fontWeight = FontWeight.Bold)
    }
}

/** One launcher cell, an app or a folder tile, as the SuperApp's phone grid draws them (shared KitLauncher pieces). */
@Composable
private fun WebyCell(
    cell: WebyLayout.Cell, path: String, modifier: Modifier, icons: WebyIconCache, rev: Int,
    onTile: (WebyTile) -> Unit, onTileLong: (WebyTile) -> Unit, onFolder: (String) -> Unit, onFolderLong: (String) -> Unit,
) {
    when (cell) {
        is WebyLayout.Cell.App -> KitLauncherTile(cell.tile.label, modifier.testTag("weby:tile:${cell.tile.id}"), enabled = cell.tile.url != null,
            onClick = { onTile(cell.tile) }, onLongClick = { onTileLong(cell.tile) }) { sz -> WebyIcon(cell.tile, sz, icons, rev) }
        is WebyLayout.Cell.Folder -> {
            val sub = "$path/${cell.folder.title}"
            KitLauncherFolderTile(cell.folder.title, WebyLayout.previewTiles(cell.folder).map { tl -> @Composable { sz: androidx.compose.ui.unit.Dp -> WebyIcon(tl, sz, icons, rev) } },
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
fun WebyPage(decl: SitesDecl, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val t = remember { decl.texts(decl.weby) }
    val store = remember { WebyStore(ctx, WebySeed.derive(decl.weby)) }
    val icons = remember { WebyIconCache(ctx, decl.weby) }
    var folders by remember { mutableStateOf(store.load()) }
    var dialog by remember { mutableStateOf<WebyDialog?>(null) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    fun commit(n: List<WebyFolder>) { folders = n; store.save(n) }
    val sections = WebyOps.flatten(folders)

    LazyVerticalGrid(
        GridCells.Fixed(WebyLayout.COLUMNS), Modifier.fillMaxSize().testTag("weby:grid"),
        contentPadding = PaddingValues(bottom = KitDensity.large),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            KitStoreRowLine(Modifier.testTag("weby:actions")) {
                KitStoreQuick(t("add_bookmark"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:add_bookmark"), onClick = { dialog = WebyDialog.AddBookmark(sections.firstOrNull()?.first.orEmpty()) })
                Spacer(Modifier.width(KitStoreRow.gap))
                KitStoreQuick(t("add_folder"), KitStoreRow.AMBER_FILL, Modifier.testTag("weby:add_folder"), onClick = { dialog = WebyDialog.AddFolder })
            }
        }
        if (folders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { KitStoreRowLine { KitStoreCaption(t("empty"), color = p.textSecondary) } }
        for (f in folders) {
            val path = f.title
            item(key = "h:$path", span = { GridItemSpan(maxLineSpan) }) {
                KitStoreRowLine(Modifier.testTag("weby:folder:$path")) {
                    KitStoreName(path, Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(WebyLayout.cells(f).size.toString(), color = p.textSecondary)
                    Spacer(Modifier.width(KitStoreRow.gap))
                    KitStoreQuick(t("add_short"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:folder:$path:add"), onClick = { dialog = WebyDialog.AddBookmark(path) })
                    Spacer(Modifier.width(KitStoreRow.gap))
                    KitStoreQuick(t("remove_short"), KitStoreRow.BAD, Modifier.testTag("weby:folder:$path:remove"), onClick = { dialog = WebyDialog.RemoveFolder(path) })
                }
            }
            items(WebyLayout.cells(f), key = { c -> if (c is WebyLayout.Cell.App) "$path|${c.tile.id}" else "$path|f:${(c as WebyLayout.Cell.Folder).folder.title}" }) { c ->
                WebyCell(c, path, Modifier.fillMaxWidth(), icons, rev,
                    onTile = { tile -> tile.url?.let(onOpen) }, onTileLong = { tile -> dialog = WebyDialog.TileMenu(path, tile) },
                    onFolder = { openPath = it }, onFolderLong = { dialog = WebyDialog.RemoveFolder(it) })
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
                    WebyCell(c, path, m, icons, rev,
                        onTile = { tile -> tile.url?.let { openPath = null; onOpen(it) } }, onTileLong = { tile -> dialog = WebyDialog.TileMenu(path, tile) },
                        onFolder = { openPath = it }, onFolderLong = { dialog = WebyDialog.RemoveFolder(it) })
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
                TextButton({ dialog = null; icons.refresh(d.tile) { rev++ } }, Modifier.testTag("weby:menu:refresh_icon")) { Text(t("menu_refresh_icon")) }
                TextButton({ commit(WebyOps.removeBookmark(folders, d.path, d.tile.id)); dialog = null }, Modifier.testTag("weby:menu:remove")) { Text(t("menu_remove")) }
            } },
            confirmButton = {}, dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        is WebyDialog.RemoveFolder -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("remove_folder")) }, text = { Text(d.path) },
            confirmButton = { TextButton({ commit(WebyOps.removeFolder(folders, d.path)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
            dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        null -> Unit
    }
}

// ───────────────────────────── Cloudy ─────────────────────────────

/** #1493/#1531 Cloudy: [page] is a ui.sections[cloudy].pages id (gh_pages | webserver | private_urls | public_urls). A tap opens the site in the browser. */
@Composable
fun CloudyPage(page: String?, decl: SitesDecl, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = decl.cloudy
    val t = remember { decl.texts(c) }
    val cache = remember { CloudyCache(ctx) }
    val ws = c.optJSONObject("webserver") ?: org.json.JSONObject()
    val timeout = c.optInt("timeout_ms", 0)
    when (page) {
        "webserver" -> {
            val entries by loaded(Cloudy.declaredWebserver(ws), "ws") {
                val store = runCatching { File(ws.optString("repos_store")).readText() }.getOrNull()
                Cloudy.declaredWebserver(ws) + Cloudy.storeEntries(store)
            }
            val up by loaded(emptyMap<Int, Boolean>(), entries) { entries.mapNotNull { it.port }.distinct().associateWith { Cloudy.probe(it, timeout) } }
            Column(Modifier.fillMaxSize()) {
                // The Cloud-Webserver APK: an explicit intent to its launcher activity, a clear message when it is not installed.
                KitStoreRowLine(Modifier.testTag("cloudy:ws:open"), onClick = { openWebserver(ctx, ws, t) }) {
                    KitStoreName(t("ws_open"), Modifier.weight(1f), LocalKitPalette.current.accent)
                    KitStoreDot("›", LocalKitPalette.current.textSecondary, endPad = KitStoreRow.tight)
                }
                Box(Modifier.weight(1f)) { CloudyList(entries, t("ws_empty"), "cloudy:ws", { e -> e.port?.let { up[it] } }, t, onOpen) }
            }
        }
        "private_urls", "public_urls" -> {
            val priv = page == "private_urls"
            val routes = c.optJSONArray("routes")
            val entries = remember(page) { Cloudy.fleetUrls(routes, priv, c.optString("private_scheme"), c.optString("public_scheme")) }
            CloudyList(entries, t("urls_empty"), if (priv) "cloudy:private" else "cloudy:public", { null }, t, onOpen)
        }
        else -> {
            val seed = remember { Cloudy.seedPages(c) }
            val entries by loaded(Cloudy.merge(seed, cache.pagesJson()), "gh") { Cloudy.merge(seed, cache.refresh(c.optString("pages_url"), timeout) ?: cache.pagesJson()) }
            CloudyList(entries, t("gh_empty"), "cloudy:gh", { null }, t, onOpen)
        }
    }
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

@Composable
private fun CloudyList(entries: List<CloudyEntry>, empty: String, tag: String, up: (CloudyEntry) -> Boolean?, t: (String) -> String, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().testTag(tag)) {
        if (entries.isEmpty()) item { KitStoreRowLine { KitStoreCaption(empty, color = p.textSecondary) } }
        items(entries, key = { it.url + it.title }) { e ->
            val s = up(e)
            KitStoreRowLine(Modifier.testTag("$tag:row:${e.title}"), onClick = { onOpen(e.url) }) {
                KitStoreDot(if (s == null) "◯" else "●", when (s) { true -> KitStoreRow.UP; false -> KitStoreRow.BAD; null -> p.textSecondary })
                Column(Modifier.weight(1f)) {
                    KitStoreName(e.title, color = p.textPrimary)
                    if (e.detail.isNotEmpty()) KitStoreCaption(e.detail, color = p.textSecondary)
                }
                KitStoreCaption(if (s == null) e.url.substringAfter("://").trimEnd('/') else t(if (s) "up" else "down"), color = p.textSecondary, mono = s == null)
            }
        }
    }
}

// ───────────────────────────── Domains ─────────────────────────────

/** #1493 Domains: [page] is a ui.sections[domains].pages id (headless | files | site_perms | history | visited). */
@Composable
fun DomainsPage(page: String?, decl: SitesDecl, onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val d = decl.domains
    val t = remember { decl.texts(d) }
    when (page) {
        "headless", null -> HeadlessPage(d, t, onOpen)
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

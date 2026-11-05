package com.diegonmarcos.cloudbrowser.sites

import androidx.compose.foundation.ExperimentalFoundationApi
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
    data class RemoveTile(val path: String, val tile: WebyTile) : WebyDialog
    data class RemoveFolder(val path: String) : WebyDialog
}

/** #1493 Weby: the bookmarks as a launcher icon grid in folders; a tap opens the site in the browser. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebyPage(decl: SitesDecl, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val t = remember { decl.texts(decl.weby) }
    val store = remember { WebyStore(ctx, WebySeed.derive(decl.weby)) }
    var folders by remember { mutableStateOf(store.load()) }
    var dialog by remember { mutableStateOf<WebyDialog?>(null) }
    fun commit(n: List<WebyFolder>) { folders = n; store.save(n) }
    val sections = WebyOps.flatten(folders)

    LazyVerticalGrid(
        GridCells.Adaptive(KitDensity.avatar + KitDensity.large), Modifier.fillMaxSize().testTag("weby:grid"),
        contentPadding = PaddingValues(bottom = KitDensity.large), horizontalArrangement = Arrangement.spacedBy(KitDensity.small),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            KitStoreRowLine(Modifier.testTag("weby:actions")) {
                KitStoreQuick(t("add_bookmark"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:add_bookmark"), onClick = { dialog = WebyDialog.AddBookmark(sections.firstOrNull()?.first.orEmpty()) })
                Spacer(Modifier.width(KitStoreRow.gap))
                KitStoreQuick(t("add_folder"), KitStoreRow.AMBER_FILL, Modifier.testTag("weby:add_folder"), onClick = { dialog = WebyDialog.AddFolder })
            }
        }
        if (sections.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { KitStoreRowLine { KitStoreCaption(t("empty"), color = p.textSecondary) } }
        for ((path, f) in sections) {
            item(key = "h:$path", span = { GridItemSpan(maxLineSpan) }) {
                KitStoreRowLine(Modifier.testTag("weby:folder:$path")) {
                    KitStoreName(path.replace("/", t("path_sep")), Modifier.weight(1f), p.textPrimary)
                    KitStoreCaption(f.tiles.size.toString(), color = p.textSecondary)
                    Spacer(Modifier.width(KitStoreRow.gap))
                    KitStoreQuick(t("add_short"), KitStoreRow.BLUE_FILL, Modifier.testTag("weby:folder:$path:add"), onClick = { dialog = WebyDialog.AddBookmark(path) })
                    Spacer(Modifier.width(KitStoreRow.gap))
                    KitStoreQuick(t("remove_short"), KitStoreRow.BAD, Modifier.testTag("weby:folder:$path:remove"), onClick = { dialog = WebyDialog.RemoveFolder(path) })
                }
            }
            items(f.tiles, key = { "$path|${it.id}" }) { tile ->
                val on = tile.url != null
                Column(
                    Modifier.fillMaxWidth().padding(vertical = KitDensity.small).alpha(if (on) 1f else 0.4f).testTag("weby:tile:${tile.id}")
                        .combinedClickable(enabled = on, onClick = { tile.url?.let(onOpen) }, onLongClick = { dialog = WebyDialog.RemoveTile(path, tile) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(KitDensity.avatar).clip(RoundedCornerShape(KitDensity.corner)).background(p.surfaceSelected), contentAlignment = Alignment.Center) {
                        Text(tile.label.firstOrNull()?.uppercase().orEmpty(), color = p.textPrimary, fontSize = KitDensity.display, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(KitStoreRow.tight))
                    Text(tile.label, color = p.textPrimary, fontSize = KitStoreRow.caption, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = KitStoreRow.tight))
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
        is WebyDialog.RemoveTile -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("remove_bookmark")) }, text = { Text(d.tile.label) },
            confirmButton = { TextButton({ commit(WebyOps.removeBookmark(folders, d.path, d.tile.id)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
            dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        is WebyDialog.RemoveFolder -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("remove_folder")) }, text = { Text(d.path) },
            confirmButton = { TextButton({ commit(WebyOps.removeFolder(folders, d.path)); dialog = null }, Modifier.testTag("weby:confirm")) { Text(t("ok")) } },
            dismissButton = { TextButton({ dialog = null }) { Text(t("cancel")) } })
        null -> Unit
    }
}

// ───────────────────────────── Cloudy ─────────────────────────────

/** #1493 Cloudy: [page] is a ui.sections[cloudy].pages id (gh_pages | webserver). A tap opens the site in the browser. */
@Composable
fun CloudyPage(page: String?, decl: SitesDecl, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = decl.cloudy
    val t = remember { decl.texts(c) }
    val cache = remember { CloudyCache(ctx) }
    val ws = c.optJSONObject("webserver") ?: org.json.JSONObject()
    val timeout = c.optInt("timeout_ms", 0)
    if (page == "webserver") {
        val entries by loaded(Cloudy.declaredWebserver(ws), "ws") {
            val store = runCatching { File(ws.optString("repos_store")).readText() }.getOrNull()
            Cloudy.declaredWebserver(ws) + Cloudy.storeEntries(store)
        }
        val up by loaded(emptyMap<Int, Boolean>(), entries) { entries.mapNotNull { it.port }.distinct().associateWith { Cloudy.probe(it, timeout) } }
        CloudyList(entries, t("ws_empty"), "cloudy:ws", { e -> e.port?.let { up[it] } }, t, onOpen)
    } else {
        val seed = remember { Cloudy.seedPages(c) }
        val entries by loaded(Cloudy.merge(seed, cache.pagesJson()), "gh") { Cloudy.merge(seed, cache.refresh(c.optString("pages_url"), timeout) ?: cache.pagesJson()) }
        CloudyList(entries, t("gh_empty"), "cloudy:gh", { null }, t, onOpen)
    }
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

/** #1493 Domains: [page] is a ui.sections[domains].pages id (files | perms | history | visited). */
@Composable
fun DomainsPage(page: String?, decl: SitesDecl) {
    val ctx = LocalContext.current
    val d = decl.domains
    val t = remember { decl.texts(d) }
    when (page) {
        "files" -> FilesPage(t)
        "site_perms" -> PermsPage(d, t)
        "history" -> HistoryPage(d, t)
        else -> VisitedPage(d, t)
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

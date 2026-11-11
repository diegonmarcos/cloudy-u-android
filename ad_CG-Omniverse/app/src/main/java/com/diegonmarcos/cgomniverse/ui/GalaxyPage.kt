package com.diegonmarcos.cgomniverse.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.diegonmarcos.cloudlib.web.GpuPageModel
import com.diegonmarcos.cloudlib.web.PageConsole
import com.diegonmarcos.cloudlib.web.PageDiag
import com.diegonmarcos.cloudlib.web.SiteGpuChoice
import com.diegonmarcos.cloudlib.web.WorldRoute
import com.diegonmarcos.cloudlib.web.WorldRouting
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.GraphicsStats
import com.diegonmarcos.cgomniverse.OmniXray
import com.diegonmarcos.cgomniverse.Offline
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.Store
import com.diegonmarcos.cgomniverse.Tabs
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.cloudlib.web.CloudChromeClient
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.GpuProbe
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.delay

/**
 * Galaxy: a browser-style world viewer. The tab strip holds the worlds open now ([Tabs]; a close button per tab, "+" opens the
 * Store to pick one); the selected tab fills the rest of the page ([WorldView]). With no tab open it says how to open one.
 */
@Composable
fun GalaxyPage(tabs: Tabs.State, onSelect: (String) -> Unit, onClose: (String) -> Unit, onAdd: () -> Unit) {
    val p = LocalKitPalette.current
    val open = tabs.open.mapNotNull { id -> Decl.worlds.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize().testTag(Tags.GALAXY)) {
        Row(
            Modifier.fillMaxWidth().background(p.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)
                .testTag(Tags.GALAXY_TABS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            open.forEach { w ->
                val sel = w.id == tabs.selected
                Row(
                    Modifier.padding(horizontal = 2.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) p.surfaceSelected else p.surface)
                        .then(if (sel) Modifier.border(1.dp, p.accent, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { onSelect(w.id) }.padding(start = 12.dp).testTag(Tags.galaxyTab(w.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(w.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) p.textPrimary else p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp))
                    IconButton(onClick = { onClose(w.id) }, modifier = Modifier.size(36.dp).testTag(Tags.galaxyTabClose(w.id))) {
                        Icon(Icons.Filled.Close, stringResource(R.string.galaxy_tab_close, w.name), tint = p.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            IconButton(onClick = onAdd, modifier = Modifier.testTag(Tags.GALAXY_ADD)) {
                Icon(Icons.Filled.Add, stringResource(R.string.galaxy_add), tint = p.accent)
            }
        }
        val cur = open.firstOrNull { it.id == tabs.selected }
        if (cur == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.galaxy_none), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary,
                    modifier = Modifier.testTag(Tags.GALAXY_EMPTY))
            }
        } else {
            WorldView(cur, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * One world, filling its tab: a WebView with WebGL on and hardware acceleration, a bar with desktop mode, reload and open-in-browser, and
 * for a VR entry the WebXR state this WebView reports (Android's WebView has no immersive WebXR today, so a VR world offers the
 * browser for it). Back walks the page's history (and is the app's again once there is none). A main-frame error (an unreachable host, a blocked scheme) or a
 * crashed renderer replaces the page with a fallback card: Open in browser, or Reload. A world that is offline ([Offline]: no page
 * within the load watchdog, the host never answering, or HTTP 404/410) gets the offline card instead: Open in browser, its Wayback
 * Machine copy, or Reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldView(w: World, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember(w.id) { mutableStateOf(false) }
    var progress by remember(w.id) { mutableIntStateOf(0) }
    var error by remember(w.id) { mutableStateOf<String?>(null) }
    var xr by remember(w.id) { mutableStateOf<Boolean?>(null) }
    var generation by remember(w.id) { mutableIntStateOf(0) }
    // every load attempt (first load, reload, re-create) restarts the watchdog; finished = the main frame loaded a page
    var attempt by remember(w.id) { mutableIntStateOf(0) }
    var finished by remember(w.id) { mutableStateOf(false) }
    var offline by remember(w.id) { mutableStateOf(false) }
    // an explicit reload of a pinned world is the one thing that refetches it (cache-first otherwise, never expiring)
    var refreshing by remember(w.id) { mutableStateOf(false) }
    val host = Uri.parse(w.url).host.orEmpty()
    val pinned = Pinned.isPinned(ctx, w.id)
    val reload = { refreshing = true; error = null; offline = false; finished = false; attempt++; if (web == null) generation++ else web?.reload(); Unit }
    LaunchedEffect(w.id, attempt) {
        val start = System.currentTimeMillis()
        delay(Offline.LOAD_TIMEOUT_MS)
        if (error == null && Offline.timedOut(System.currentTimeMillis() - start, finished)) { offline = true; web?.stopLoading() }
    }
    // per-world desktop mode (desktop UA + viewport, #1371): a site that gates phones by user agent takes its desktop path
    var desktop by remember(w.id) { mutableStateOf(Web.desktop(ctx, w.id)) }
    var caps by remember { mutableStateOf(GpuProbe.last) }
    LaunchedEffect(Unit) { GpuProbe.probe(ctx) { caps = it } }
    val crashed = stringResource(R.string.world_crashed)
    // per-site WebGPU choice: AUTO sends a WebGPU world to Cloud-Browser once the probe has measured no adapter here
    var gpuChoice by remember(w.id) { mutableStateOf(Web.gpuChoice(ctx, w.id)) }
    val route = WorldRouting.route(gpuChoice, Web.needsWebGpu(w), caps)
    LaunchedEffect(w.id, route) {
        if (route == WorldRoute.CLOUD_BROWSER && gpuChoice == SiteGpuChoice.AUTO && Web.routed.add(w.id)) Web.openInBrowser(ctx, w.url)
    }

    // Page diagnostics (#1537): measured in the page itself, plus the console errors it logged (PageConsole)
    var showDiag by remember(w.id) { mutableStateOf(false) }
    var diag by remember(w.id) { mutableStateOf<PageDiag?>(null) }
    LaunchedEffect(showDiag) {
        if (!showDiag) return@LaunchedEffect
        diag = null
        web?.evaluateJavascript(PageDiag.SCRIPT, null)
        delay(1500)
        web?.evaluateJavascript(PageDiag.READ) { diag = PageDiag.parse(it) }
    }

    // Graphics stats HUD: fps / frame time from an injected requestAnimationFrame loop, draw calls and triangles from a wrap of the WebGL
    // draw calls (best effort: a page that blocks injection or never draws shows n/a), the GPU from the app's own probe
    var showStats by remember(w.id) { mutableStateOf(false) }
    var stats by remember(w.id) { mutableStateOf<GraphicsStats.Stats?>(null) }
    LaunchedEffect(showStats, generation, desktop, attempt) {
        if (!showStats) return@LaunchedEffect
        while (true) {
            val wv = web
            if (wv != null) runCatching {
                wv.evaluateJavascript(GraphicsStats.PAGE_SCRIPT, null) // idempotent: re-injected after every navigation
                wv.evaluateJavascript(GraphicsStats.PAGE_READ) { stats = GraphicsStats.parseWorld(it, caps?.renderer ?: caps?.vendor) }
            }
            delay(750)
        }
    }

    BackHandler(enabled = canBack) { web?.goBack() }

    Column(modifier.background(Color.Black).testTag(Tags.WORLD)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Uri.parse(w.url).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1)
            }
            if (w.vr) {
                IconButton(
                    onClick = { if (xr != true) Web.openInBrowser(ctx, w.url) },
                    modifier = Modifier.testTag(Tags.WORLD_VR),
                ) { Icon(Icons.Filled.ViewInAr, stringResource(if (xr == true) R.string.world_xr_ready else R.string.world_xr_browser), tint = if (xr == true) p.ok else p.accent) }
            }
            IconButton(onClick = { desktop = !desktop; Web.setDesktop(ctx, w.id, desktop); error = null; offline = false; finished = false; attempt++ }, modifier = Modifier.testTag(Tags.WORLD_DESKTOP)) {
                Icon(Icons.Filled.DesktopWindows, stringResource(R.string.world_desktop), tint = if (desktop) p.accent else p.textSecondary)
            }
            IconButton(onClick = { showDiag = true }, modifier = Modifier.testTag(Tags.WORLD_DIAG)) {
                Icon(Icons.Filled.BugReport, GpuPageModel.DIAG_TITLE, tint = p.textSecondary)
            }
            IconButton(onClick = { showStats = !showStats }, modifier = Modifier.testTag(Tags.WORLD_STATS)) {
                Icon(Icons.Filled.Speed, stringResource(R.string.world_stats), tint = if (showStats) p.accent else p.textSecondary)
            }
            IconButton(onClick = reload, modifier = Modifier.testTag(Tags.WORLD_RELOAD)) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.world_reload), tint = p.textPrimary)
            }
            IconButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_BROWSER)) {
                Icon(Icons.Filled.OpenInBrowser, stringResource(R.string.open_browser), tint = p.textPrimary)
            }
        }
        if (progress in 1..99 && !offline) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = p.accent)
        if (w.vr && xr == false) Text(stringResource(R.string.world_xr_note), style = MaterialTheme.typography.labelSmall, color = p.textSecondary,
            modifier = Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 14.dp, vertical = 4.dp))
        // what this WebView can draw with: the measured reason, and the actions (Cloud-Browser, or stay here), remembered per site
        caps?.takeIf { !it.webgpuUsable && Web.needsWebGpu(w) }?.let { c ->
            Column(Modifier.fillMaxWidth().background(p.surface).padding(start = 14.dp, end = 4.dp, bottom = 4.dp).testTag(Tags.WORLD_GPU)) {
                Text(stringResource(R.string.world_gpu_missing, c.summary(), GpuPageModel.banner(c, Build.TYPE != "user")),
                    style = MaterialTheme.typography.labelSmall, color = p.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitButton(onClick = { gpuChoice = SiteGpuChoice.CLOUD_BROWSER; Web.setGpuChoice(ctx, w.id, gpuChoice); Web.openInBrowser(ctx, w.url) },
                        modifier = Modifier.testTag(Tags.WORLD_GPU_BROWSER)) { Text(stringResource(R.string.open_cloud_browser)) }
                    KitOutlinedButton(onClick = { gpuChoice = SiteGpuChoice.IN_APP; Web.setGpuChoice(ctx, w.id, gpuChoice) },
                        modifier = Modifier.testTag(Tags.WORLD_GPU_STAY)) { Text(stringResource(R.string.world_gpu_stay)) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(w.id, generation, desktop) {
                AndroidView(factory = { c ->
                    CloudWeb.create(c, WebProfile.WEB_WORLD, desktop).also { wv ->
                        wv.webChromeClient = object : CloudChromeClient(ctx as? android.app.Activity) {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress; OmniXray.worldProgress(w.id, newProgress) }
                            // protected media (some worlds stream DRM video) is the one resource granted; nothing else is asked of the user
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                                if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                            }
                        }
                        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD) {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val s = request.url.scheme.orEmpty()
                                if (s == "http" || s == "https") return false
                                Web.openInBrowser(c, request.url.toString()); return true
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                (if (pinned) Pinned.intercept(c, host, request, refreshing) else null) ?: super.shouldInterceptRequest(view, request)
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { error = null; OmniXray.worldStarted(w.id, url) }
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canBack = view.canGoBack() }
                            override fun onPageFinished(view: WebView, url: String) {
                                OmniXray.worldFinished(w.id, url)
                                // #1631 what this world's page can draw with, measured in the page (no bridge is added to a foreign page)
                                view.evaluateJavascript(PageDiag.SCRIPT, null)
                                view.postDelayed({
                                    view.evaluateJavascript(PageDiag.READ) { raw ->
                                        PageDiag.parse(raw)?.let { d -> OmniXray.worldDiag(w.id, d.rows().joinToString("; ") { it.label + ": " + it.value }) }
                                    }
                                }, 1500)
                                if (error == null && !offline) finished = true
                                refreshing = false
                                if (w.vr) view.evaluateJavascript("(function(){return !!(navigator.xr&&navigator.xr.isSessionSupported)})()") { xr = it == "true" }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                OmniXray.worldError(w.id, request.url.toString(), request.isForMainFrame, err.errorCode, err.description?.toString().orEmpty())
                                if (!request.isForMainFrame) return
                                if (Offline.isOfflineError(err.errorCode)) offline = true else error = err.description?.toString()
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (response.statusCode >= 400) OmniXray.worldHttp(w.id, request.url.toString(), request.isForMainFrame, response.statusCode)
                                if (request.isForMainFrame && Offline.isOfflineHttp(response.statusCode)) offline = true
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                OmniXray.worldRenderGone(w.id)
                                if (web === view) web = null
                                view.destroy(); error = crashed; return true
                            }
                        }
                        wv.loadUrl(Store.launchUrl(w))
                        web = wv
                    }
                }, modifier = Modifier.fillMaxSize().testTag(Tags.WORLD_WEB))
            }
            if (showStats && !offline && error == null) Column(
                Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 6.dp).testTag(Tags.WORLD_STATS_HUD),
            ) {
                GraphicsStats.lines(stats).forEach {
                    Text(it, color = Color(0xFFB6FFB6), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            // a world that cannot load here gets a clear card, never the WebView's raw error page
            if (offline) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16)).padding(20.dp), contentAlignment = Alignment.Center) {
                    KitCard(Modifier.testTag(Tags.WORLD_OFFLINE)) {
                        Text(stringResource(R.string.world_offline_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                        Text(stringResource(R.string.world_offline_body, w.name), style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        if (w.checked.isNotEmpty()) Text(stringResource(R.string.world_offline_checked, w.status.name.lowercase(), w.checked),
                            style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KitButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_OFFLINE_BROWSER)) {
                                Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_in_browser))
                            }
                            if (w.wayback.isNotEmpty()) KitOutlinedButton(onClick = { Web.openInBrowser(ctx, w.wayback) }, modifier = Modifier.testTag(Tags.WORLD_OFFLINE_WAYBACK)) {
                                Icon(Icons.Filled.History, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_wayback))
                            }
                        }
                        KitOutlinedButton(onClick = reload, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.world_reload))
                        }
                    }
                }
            }
            if (!offline) error?.let { msg ->
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16)).padding(20.dp), contentAlignment = Alignment.Center) {
                    KitCard(Modifier.testTag(Tags.WORLD_FALLBACK)) {
                        Text(stringResource(R.string.world_fallback_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                        Text(stringResource(R.string.world_fallback_body, w.name), style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Text(msg, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KitButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_FALLBACK_BROWSER)) {
                                Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_in_browser))
                            }
                            KitOutlinedButton(onClick = reload) {
                                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.world_reload))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDiag) {
        ModalBottomSheet(onDismissRequest = { showDiag = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag(Tags.WORLD_DIAG_SHEET)) {
                Text(GpuPageModel.DIAG_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                Text(w.url, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
                val d = diag
                if (d == null) Text("measuring in the page ...", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                else d.rows().forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(r.label, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.weight(0.45f))
                        Text(r.value, style = MaterialTheme.typography.bodySmall, color = p.textPrimary, modifier = Modifier.weight(0.55f))
                    }
                }
                val errors = PageConsole.errors().takeLast(30)
                Text("Console errors (${errors.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = p.textPrimary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (errors.isEmpty()) Text("none logged by this page", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                errors.forEach { Text(PageConsole.format(it), style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(vertical = 1.dp)) }
            }
        }
    }
    DisposableEffect(w.id) { onDispose { web?.destroy(); web = null } }
}

package com.diegonmarcos.cloudme

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * A page that IS the web page.
 *
 * Profile > Professional and Profile > Personal are the LinkedIn and Instagram
 * layouts from front-diegonmarcos/b-Media/mySocials, rendered by loading those
 * exact files out of assets. They were reimplemented with this app's blocks
 * first, and that was wrong: a reimplementation is a second design to keep in
 * step, and it was visibly not the same page. The HTML and CSS already exist
 * and are already the design — so ship them.
 *
 * data/regen-web.py copies each page plus everything it references, so the
 * bundle is what the browser would have fetched. Images and fonts still come
 * from the CDN the pages already use, which is why this needs the network the
 * app already declares.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The WebView is the one thing on the page
 * and stays a WebView, hosted by AndroidView; Back walks its own history first and the page's
 * state survives a recreation.
 * `{"kind":"fragment","id":"web","url":"mysocials/linkedin.html"}`
 *
 * Profile > Social, Health and DD are the same kind of page (cloud-me-profiles b_Profiles/mySocials). Health and DD
 * add `"inject":"health"|"dd"`: the bundled page is the public SCHEMA only and reads "Locked — sign in";
 * once the page has loaded, the owner's values from the on-device store (imported from the private repo
 * cloud-me-pvt-data, profiles/<id>.data.json) are handed to `window.CloudMeProfile.inject(...)`. The values
 * never ship in assets. `CloudMeBridge.importData()` lets the page ask for that import.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebPage(block: JSONObject, modifier: Modifier = Modifier, pageId: String = block.optString("url")) {
    val url = block.optString("url")
    val inject = block.optString("inject")
    val ctx = LocalContext.current
    // #1323 a remote Lnktree page is cached on disk (LnktreeCache), its WebView is kept alive across tab switches (LnktreeHolder)
    // and a pull-down at its top is a HARD refresh. Asset pages and the Health/DD injectors keep the old behaviour.
    val cfg = LnktreeCacheConfig.baked
    val holder = LocalLnktreeHolder.current
    val cached = holder != null && inject.isBlank() && url.startsWith("https://") && cfg.caches(pageId)
    var refreshing by remember { mutableStateOf(false) }
    var atTop by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** The page's WebView state (history, scroll), refreshed whenever a page finishes loading. */
    val saved = rememberSaveable(url) { Bundle() }
    // Back walks the page's own history before it leaves the section — the
    // profiles have internal navigation and losing it would strand you.
    var canGoBack by remember(url) { mutableStateOf(false) }
    val web = remember(url) { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = canGoBack) { web.value?.goBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || inject.isBlank()) return@rememberLauncherForActivityResult
        runCatching {
            val schema = loadSchema(ctx, inject)!!
            val data = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            if (ProfileRecord.validate(schema, data).isEmpty()) {
                ProfileStore.save(ctx, if (inject == "health") ProfileStore.HEALTH else ProfileStore.DD, data)
                web.value?.let { pushProfile(it, ctx, inject) }
            }
        }
    }

    // #1164 the page's scroll feeds the shell's one chrome collapse (first row, second row, bottom nav).
    val collapse = com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse.current
    // #1198 a page opens with the chrome in full: the collapse is the shell's ONE state, so a page left scrolled (Leaf.y) handed its
    // hidden first row to the next one, and a page at its top (Lnktree) had no scroll up to bring it back (run 40805138692)
    androidx.compose.runtime.LaunchedEffect(url) { collapse?.restore() }
    // #1198 a remote page (Lnktree) can lose its renderer process; an unhandled loss takes the whole app down
    // (WebViewClient.onRenderProcessGone returns false -> the process is killed). Handle it and build a fresh WebView.
    var generation by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(20_000); refreshing = false } }
    // #1911 the WebView is bound to its page's URL: a tab switch builds a new one (it used to keep the previous tab's page and client)
    androidx.compose.runtime.key(generation, url, pageId) {
    val page: @Composable (Modifier) -> Unit = { mod ->
    AndroidView(
        modifier = mod,
        update = { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, collapse) },
        factory = { ctx ->
            var fresh = false
            val make = {
                fresh = true
                // a fleet page (APP_PAGE, #1371); bundled pages come from the https asset origin, so they fetch their own
                // stylesheets and data same-origin with no file:// access at all
                CloudWeb.create(ctx, WebProfile.APP_PAGE).apply { setBackgroundColor(0xFF0B0414.toInt()) }
            }
            // A kept WebView comes back as it was left (page, scroll, history): no reload, so switching is instant.
            val view = if (cached) holder!!.web(url, make) else make()
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.apply {
                // The client is rebuilt on every attach: it closes over THIS composition's state.
                webViewClient = object : CloudWebClient(WebProfile.APP_PAGE, CloudWeb.assetLoader(ctx)) {
                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        saved.clear()
                        canGoBack = false
                        web.value = null
                        if (cached) holder!!.forget(url)
                        generation++   // drops this WebView (onRelease destroys it) and creates a new one
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? =
                        // #1771 the script-blocking font stylesheet of the bundled pages is answered at once (FontSheetGate)
                        if (request != null && FontSheetGate.blocks(request.url.host, request.url.path)) FontSheetGate.emptySheet()
                        else (if (cached && request != null) LnktreeCache.intercept(ctx, pageId, url, request) else null) ?: super.shouldInterceptRequest(view, request)  // #1911 a cache miss falls to the asset loader, so appassets never reaches DNS

                    // #1911 the emulator walk reads these from logcat: a main-frame error is MAINFRAME_ERROR, a loaded page MAINFRAME_OK with its title
                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        if (request?.isForMainFrame == true) android.util.Log.e("CloudMeWeb", "MAINFRAME_ERROR page=$pageId url=${request.url} err=${error?.description}")
                        super.onReceivedError(view, request, error)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        android.util.Log.i("CloudMeWeb", "MAINFRAME_OK page=$pageId url=$url title=${view?.title}")
                        refreshing = false
                        // #1913 the walk's image check: once the page had time to fetch its images, count those that finished with naturalWidth 0
                        view?.postDelayed({
                            view.evaluateJavascript("(function(){var n=document.images,b=[];for(var i=0;i<n.length;i++){if(n[i].complete&&n[i].naturalWidth==0&&n[i].getAttribute('data-fb')!='done')b.push(n[i].currentSrc||n[i].src)}return n.length+' '+b.length+' '+(b[0]||'')})()") { r ->
                                android.util.Log.i("CloudMeWeb", "IMG_CHECK page=$pageId images_broken=$r")
                            }
                        }, 8000)
                        view?.let { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(it) }
                        view?.let { if (inject.isNotBlank()) pushProfile(it, ctx, inject) }
                        view?.saveState(saved)
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        canGoBack = view?.canGoBack() == true
                    }
                }
                if (inject.isNotBlank()) addJavascriptInterface(object {
                    @JavascriptInterface fun importData() { post { picker.launch(arrayOf("application/json", "text/plain", "*/*")) } }
                }, "CloudMeBridge")
                web.value = this
                // the scroll bridge must exist BEFORE the first load: a JavaScript interface added later only reaches pages loaded after it
                com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(this, collapse)
                canGoBack = canGoBack()
                if (!fresh) onResume()
                else if (!saved.isEmpty) restoreState(saved) else loadUrl(if (url.startsWith("https://")) url else CloudWeb.assetUrl(url))
            }
        },
        onRelease = {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            if (cached && holder!!.peek(url) === it) it.onPause() else { it.stopLoading(); it.destroy() }
        },
    )
    }
    if (cached) {
        // #1323 pull-down = HARD refresh. Only armed when the finger lands with the page scrolled to its top, so it never
        // fights the WebView's own scrolling. Deletes the page's cache files (scope: build.json), empties the WebView cache, reloads.
        // Material3's pull-to-refresh building block: PullToRefreshBox is this modifier plus the indicator, without the `enabled` switch.
        val ptr = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        androidx.compose.foundation.layout.Box(
            modifier = modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.any { it.pressed && !it.previousPressed }) atTop = web.value?.let { !it.canScrollVertically(-1) } ?: true
                        }
                    }
                }
                .pullToRefresh(
                    isRefreshing = refreshing,
                    state = ptr,
                    enabled = atTop,
                    onRefresh = {
                        refreshing = true
                        scope.launch {
                            withContext(Dispatchers.IO) { LnktreeCache.hardRefresh(ctx, pageId) }
                            val w = web.value
                            if (w == null) refreshing = false else { w.clearCache(true); w.reload() }
                        }
                    },
                ),
        ) {
            page(Modifier.fillMaxSize())
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = ptr, isRefreshing = refreshing,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            )
        }
    } else page(modifier.fillMaxSize())
    }
}

/** Hands the stored values of Health/DD to the loaded page; a page with nothing stored stays locked. */
private fun pushProfile(view: WebView, ctx: android.content.Context, id: String) {
    val doc = runCatching { ProfileStore.load(ctx, if (id == "health") ProfileStore.HEALTH else ProfileStore.DD) }.getOrNull() ?: return
    view.evaluateJavascript("window.CloudMeProfile&&window.CloudMeProfile.inject(${JSONObject.quote(doc.toString())})", null)
}

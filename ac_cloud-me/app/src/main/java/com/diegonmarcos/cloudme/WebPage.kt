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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    var offlineNote by remember(url, pageId) { mutableStateOf<String?>(null) }
    // #1951 cache_then_live: the live page differed from the painted cached copy; the shown page swaps to it and says so for a moment
    var updatedNote by remember(url, pageId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(updatedNote) { if (updatedNote) { kotlinx.coroutines.delay(3_000); updatedNote = false } }
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
    // #1937 a remote page that cannot be shown (no network and no cached copy, an HTTP error, an empty body) is a visible error with Retry, never a blank dark page:
    // the WebView's own background is the app's near-black (0xFF0B0414), so a failed main frame used to read as a black screen with nothing on it.
    var loadError by remember(url, pageId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(loadError) { if (loadError != null) collapse?.restore() }   // the top bar and the strips come back with the error
    val retry: () -> Unit = { loadError = null; refreshing = false; web.value?.let { w -> if (url.startsWith("https://")) w.loadUrl(url) else w.reload() } ?: run { generation++ } }
    androidx.compose.runtime.LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(20_000); refreshing = false } }
    // #1911 the WebView is bound to its page's URL: a tab switch builds a new one (it used to keep the previous tab's page and client)
    androidx.compose.runtime.key(generation, url, pageId) {
    val page: @Composable (Modifier) -> Unit = { mod ->
    AndroidView(
        modifier = mod,
        update = { sr ->
            sr.isRefreshing = refreshing   // #1947 the spinner follows the page's state: it stops on onPageFinished, on an error and after the 20 s cap
            (sr.tag as? WebView)?.let { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, collapse) }
        },
        factory = { ctx ->
            var fresh = false
            var swapsDone = 0
            var swapScrollY = -1   // #1951 the scroll position kept across a live swap
            val make = {
                fresh = true
                // a fleet page (APP_PAGE, #1371); bundled pages come from the https asset origin, so they fetch their own
                // stylesheets and data same-origin with no file:// access at all
                CloudWeb.create(ctx, WebProfile.APP_PAGE).apply { setBackgroundColor(0xFF0B0414.toInt()); WebDarkMode.apply(settings); if (cached) LnktreeSw.block(this, url) }   // #1957 the site's service worker is off: the app cache answers   // #1938 readable in the dark theme: the site's own scheme, else algorithmic darkening
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
                        if (request?.isForMainFrame != true && request != null) android.util.Log.w("CloudMeWeb", "SUBRES_ERROR page=$pageId url=${request.url} err=${error?.errorCode}:${error?.description}")   // #1957 the reason behind a broken image
                        if (request?.isForMainFrame == true) {
                            android.util.Log.e("CloudMeWeb", "MAINFRAME_ERROR page=$pageId url=${request.url} err=${error?.description}")
                            if (url.startsWith("https://")) { refreshing = false; loadError = WebLoadState.describe(error?.description?.toString(), null) }
                        }
                        super.onReceivedError(view, request, error)
                    }

                    // #1937 an edge that answers 5xx/4xx for the page itself (the fleet edge down) is an error too, not a page to draw
                    override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                        if (request?.isForMainFrame != true && request != null) android.util.Log.w("CloudMeWeb", "SUBRES_HTTP page=$pageId url=${request.url} status=${errorResponse?.statusCode}")
                        if (request?.isForMainFrame == true && url.startsWith("https://") && WebLoadState.isHttpFailure(errorResponse?.statusCode ?: 0)) {
                            android.util.Log.e("CloudMeWeb", "MAINFRAME_HTTP page=$pageId url=${request.url} status=${errorResponse?.statusCode}")
                            refreshing = false; loadError = WebLoadState.describe(null, errorResponse?.statusCode)
                        }
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    // #1951 the walk's first-paint mark: the first frame with content of the page
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        android.util.Log.i("CloudMeWeb", "MAINFRAME_PAINT page=$pageId url=$url")
                        super.onPageCommitVisible(view, url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        android.util.Log.i("CloudMeWeb", "MAINFRAME_OK page=$pageId url=$url title=${view?.title}")
                        refreshing = false
                        offlineNote = if (cached) LnktreeCache.offlineSince[pageId]?.let { "Offline copy from ${LnktreeResolver.age(it, System.currentTimeMillis())}" } else null
                        // #1937 a page that finished with nothing in it (an empty 200 from a half-up edge) is the black page: show the error instead
                        // #1951 judged only once the page has settled (main frame finished, no swap pending); a page still loading never shows the error
                        if (loadError == null && url?.startsWith("https://") == true) {
                            val t0 = System.currentTimeMillis()
                            lateinit var probe: Runnable
                            probe = Runnable {
                                val v = view ?: return@Runnable
                                when (WebLoadState.probeAction(v.progress, LnktreeCache.swapPending.contains(pageId), System.currentTimeMillis() - t0)) {
                                    WebLoadState.Probe.WAIT -> v.postDelayed(probe, WebLoadState.PROBE_RETRY_MS)
                                    WebLoadState.Probe.GIVE_UP -> {}
                                    WebLoadState.Probe.PROBE -> v.evaluateJavascript(WebLoadState.PROBE_JS) { r -> if (WebLoadState.isBlank(r) && loadError == null) loadError = WebLoadState.describe("the page loaded empty", null) }
                                }
                            }
                            view?.postDelayed(probe, WebLoadState.PROBE_DELAY_MS)
                        }
                        if (swapScrollY >= 0) { val y = swapScrollY; swapScrollY = -1; view?.postDelayed({ view.scrollTo(0, y) }, 150); updatedNote = true; android.util.Log.i("CloudMeWeb", "LIVE_SWAPPED page=$pageId scrollY=$y") }
                        // #1913 the walk's image check: once the page had time to fetch its images, count those that finished with naturalWidth 0
                        view?.postDelayed({
                            view.evaluateJavascript(WebLoadState.IMG_CHECK_JS) { r ->
                                android.util.Log.i("CloudMeWeb", "IMG_CHECK page=$pageId images_broken=\"${WebLoadState.imgSummary(r)}\"")
                                WebLoadState.imgBroken(r).forEach { android.util.Log.i("CloudMeWeb", "IMG_BROKEN page=$pageId url=$it") }
                            }
                        }, 8000)
                        view?.evaluateJavascript(WebReveal.JS, null)   // #1938 a section the page keeps hidden until a 95% intersection is shown while any part of it is on screen
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
                // #1951 the live page differed from the cached copy just painted: reload it (now served from the updated cache) at the same scroll position
                if (cached) LnktreeCache.swapListeners[pageId] = { _ -> post { if (swapsDone < 1 && CacheKey.normalize(this.url ?: "") == CacheKey.normalize(url)) { swapsDone++; swapScrollY = scrollY; reload() } } }
                // the scroll bridge must exist BEFORE the first load: a JavaScript interface added later only reaches pages loaded after it
                com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(this, collapse)
                canGoBack = canGoBack()
                if (!fresh) onResume()
                else if (!saved.isEmpty) restoreState(saved) else loadUrl(if (url.startsWith("https://")) url else CloudWeb.assetUrl(url))
            }
            // #1947 pull-down at the top of the page reloads it, cached or not. SwipeRefreshLayout reads the WebView's real scroll state;
            // a page that scrolls inside an inner element is asked through JS on touch-down, so a pull while scrolling up inside the page never refreshes.
            var innerScrolled = false
            view.setOnTouchListener { v, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val d = v.resources.displayMetrics.density
                    (v as WebView).evaluateJavascript(WebPullRefresh.innerScrolledJs(ev.x / d, ev.y / d)) { r -> innerScrolled = r?.trim() == "true" }
                }
                false
            }
            androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).also { sr ->
                sr.addView(view, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                sr.tag = view
                sr.setColorSchemeColors(0xFF7C3AED.toInt())
                sr.setProgressBackgroundColorSchemeColor(0xFF1A0033.toInt())
                sr.isEnabled = WebPullRefresh.armed(cached)
                sr.setOnChildScrollUpCallback { _, _ -> WebPullRefresh.canChildScrollUp(view.scrollY, view.canScrollVertically(-1), innerScrolled) }
                sr.setOnRefreshListener {
                    refreshing = true
                    scope.launch {
                        // #1323 a cached page: its cache files (scope: build.json) are deleted first; every page: WebView cache emptied, reloaded from the network
                        WebPullRefresh.run(cached, { withContext(Dispatchers.IO) { LnktreeCache.hardRefresh(ctx, pageId); LnktreeCache.refill(ctx, pageId, url) } }, { view.clearCache(true) }, { view.reload() })
                    }
                }
            }
        },
        onRelease = { sr ->
            val w = sr.tag as WebView
            if ((w.parent as? android.view.ViewGroup) === sr) sr.removeView(w)
            if (cached && holder!!.peek(url) === w) w.onPause() else { w.stopLoading(); w.destroy() }
        },
    )
    }
    // #1323/#1947 pull-down at the page's top is a HARD refresh on every embedded page (the SwipeRefreshLayout in `page`); the old Compose
    // pullToRefresh never fired over a WebView (no nested scroll), so its indicator is gone: the layout's own spinner is the one indicator.
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        page(Modifier.fillMaxSize())
        // #1947 network_first: the live page is shown; only when the network failed is the stored copy shown, with its age
        offlineNote?.let {
            androidx.compose.material3.Text(it, color = androidx.compose.ui.graphics.Color(0xFFB8B3C7), style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).background(androidx.compose.ui.graphics.Color(0xCC0B0414)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("me:web:offline"))
        }
        if (updatedNote) androidx.compose.material3.Text("Updated", color = androidx.compose.ui.graphics.Color(0xFFB8B3C7), style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).background(androidx.compose.ui.graphics.Color(0xCC0B0414)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("me:web:updated"))
        loadError?.let { WebLoadErrorPanel(it, retry, Modifier.fillMaxSize()) }
    }
    }
}

/** Hands the stored values of Health/DD to the loaded page; a page with nothing stored stays locked. */
private fun pushProfile(view: WebView, ctx: android.content.Context, id: String) {
    val doc = runCatching { ProfileStore.load(ctx, if (id == "health") ProfileStore.HEALTH else ProfileStore.DD) }.getOrNull() ?: return
    view.evaluateJavascript("window.CloudMeProfile&&window.CloudMeProfile.inject(${JSONObject.quote(doc.toString())})", null)
}

/** #1937 the pure rules of a failed remote page (unit-tested): what counts as a failure and what the panel says. */
object WebLoadState {
    /** One JavaScript expression: how much visible content the document holds (text characters + images + canvases, svgs, iframes, videos). */
    const val PROBE_JS = "(function(){var b=document.body;if(!b)return 0;return b.innerText.trim().length+document.images.length+document.querySelectorAll('canvas,svg,iframe,video').length})()"
    /** #1951 the probe runs [PROBE_DELAY_MS] after the main frame finished, and only once the page settled: the WebView says 100% and no live swap is pending. Still loading = WAIT; never an error for it. */
    enum class Probe { WAIT, PROBE, GIVE_UP }
    const val PROBE_DELAY_MS = 2500L
    const val PROBE_RETRY_MS = 1500L
    const val PROBE_GIVE_UP_MS = 90_000L
    fun probeAction(progress: Int, swapPending: Boolean, waitedMs: Long): Probe = when {
        progress >= 100 && !swapPending -> Probe.PROBE
        waitedMs >= PROBE_GIVE_UP_MS -> Probe.GIVE_UP
        else -> Probe.WAIT
    }
    fun isBlank(jsResult: String?): Boolean = jsResult == null || jsResult.trim().trim('"') in setOf("0", "null", "")
    fun isHttpFailure(status: Int): Boolean = status >= 400
    /**
     * #1957 the image check: every <img> that finished with no pixels, by URL. An img with no src at all (a hidden modal's empty slot) is
     * not an image the page asked for and is not counted; one the site's own fallback already replaced (data-fb=done) neither.
     * The JS answers a JSON string {"total":n,"empty":k,"broken":[url...]}.
     */
    const val IMG_CHECK_JS = "(function(){var n=document.images,b=[],e=0;for(var i=0;i<n.length;i++){var x=n[i],s=x.getAttribute('src');" +
        "if(!s&&!x.getAttribute('srcset')){e++;continue}if(x.complete&&x.naturalWidth==0&&x.getAttribute('data-fb')!='done')b.push(x.currentSrc||x.src)}" +
        "var h=document.getElementById('me-hero3d'),g=null;if(h){var c=document.createElement('canvas');g=!!(c.getContext('webgl2')||c.getContext('webgl'))}" +
        "return JSON.stringify({total:n.length,empty:e,broken:b,hero:h?h.width+'x'+h.height:null,webgl:g})})()"
    private fun imgJson(r: String?): org.json.JSONObject? = runCatching { org.json.JSONObject(org.json.JSONTokener(r ?: "").nextValue() as String) }.getOrNull()
    fun imgBroken(r: String?): List<String> = imgJson(r)?.optJSONArray("broken")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    /** The IMG_CHECK line: "<total> <broken> <first broken url>" (the walks read the second number), then empty=<k>. */
    fun imgSummary(r: String?): String { val o = imgJson(r) ?: return "? ? $r"; val b = imgBroken(r); return "${o.optInt("total")} ${b.size} ${b.firstOrNull() ?: ""} empty=${o.optInt("empty")} hero=${o.opt("hero")} webgl=${o.opt("webgl")}" }
    fun describe(reason: String?, status: Int?): String = when {
        status != null && status >= 400 -> "The server answered HTTP $status."
        !reason.isNullOrBlank() -> reason
        else -> "The page did not load."
    }
}

/** #1937 the visible error of a remote page that did not load: what happened, the cached copy is shown first when there is one (LnktreeCache), and Retry. Never a blank page. */
@Composable
internal fun WebLoadErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier.background(androidx.compose.ui.graphics.Color(0xFF0B0414)).padding(24.dp).testTag("me:web:error"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text("This page could not be loaded", color = androidx.compose.ui.graphics.Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        androidx.compose.material3.Text(message, color = androidx.compose.ui.graphics.Color(0xFFB8B3C7), modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        androidx.compose.material3.Button(onClick = onRetry, modifier = Modifier.testTag("me:web:retry")) { androidx.compose.material3.Text("Retry") }
    }
}

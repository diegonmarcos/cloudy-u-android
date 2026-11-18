package com.diegonmarcos.cloudwiki.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudwiki.Chronos
import com.diegonmarcos.cloudwiki.ChronosPage
import com.diegonmarcos.cloudwiki.ChronosTree
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import java.io.ByteArrayInputStream

/**
 * The web view's client (#1789): the fleet base (libs:web-core, render-process recovery) plus the one origin the pages may use. Every
 * request is answered here: the asset origin's [Chronos.SERVE_PATH] from assets/chronos (a directory answers its index.html, so the repo's
 * `href="3.Quantum-Cycles/"` links work as on the web), anything else with an empty 403, so a page's script runs against the APK's own
 * files only and nothing leaves the phone (the app declares no network permission either). A link off the origin is never followed
 * inside the view: a tap on an http(s) link opens it in the phone's browser, any other scheme is dropped.
 */
private class ChronosWebClient(ctx: Context, private val tree: ChronosTree, private val onShown: (ChronosPage?, Boolean) -> Unit) :
    CloudWebClient(WebProfile.APP_PAGE) {
    private val app = ctx.applicationContext
    private val loader = WebViewAssetLoader.Builder().setDomain(CloudWeb.ASSET_HOST)
        .addPathHandler(Chronos.SERVE_PATH) { path -> serve(path) }
        .build()
    var onRecover: (WebView) -> Unit = {}

    private fun serve(path: String): WebResourceResponse? {
        val asset = Chronos.assetFor(path) ?: return null
        val open = { p: String -> runCatching { app.assets.open(Chronos.ASSETS_DIR + p) }.getOrNull() }
        // a directory asked for without its trailing slash still answers its page (its relative links then resolve one level up,
        // which is why the pages are always loaded by their directory URL)
        val (name, stream) = open(asset)?.let { asset to it }
            ?: (if (!asset.substringAfterLast('/').contains('.')) open("$asset/index.html")?.let { "$asset/index.html" to it } else null)
            ?: return null
        val mime = mime(name)
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null, stream)
    }

    private fun onOrigin(u: Uri?): Boolean = u != null && u.scheme == "https" && u.host == CloudWeb.ASSET_HOST

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val u = request.url
        if (!onOrigin(u)) return blocked()
        return loader.shouldInterceptRequest(u) ?: notFound()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url
        if (onOrigin(u) && u.path.orEmpty().startsWith(Chronos.SERVE_PATH)) return false
        if (request.isForMainFrame && request.hasGesture() && (u.scheme == "https" || u.scheme == "http")) {
            try { view.context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (ignored: ActivityNotFoundException) { }
        }
        return true
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val u = url?.let(Uri::parse)
        onShown(if (onOrigin(u)) tree.pageAt(u?.path) else null, view.canGoBack())
    }

    override fun onRecovered(dead: WebView, fresh: WebView?) { if (fresh != null) onRecover(fresh) }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        fun mime(p: String): String = when (p.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
            "md" -> "text/markdown"; "txt" -> "text/plain"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"; "woff2" -> "font/woff2"; "woff" -> "font/woff"
            "ttf" -> "font/ttf"; "otf" -> "font/otf"; "wasm" -> "application/wasm"; else -> "application/octet-stream"
        }
    }
}

/** The live web view (replaced after a renderer crash) and the page last asked of it. */
private class HeldView { var view: WebView? = null; var loaded: String? = null }

/** The address of [page] on the asset origin (built from parts: the app spells no URL). */
fun chronosUrl(page: ChronosPage): String =
    Uri.Builder().scheme("https").authority(CloudWeb.ASSET_HOST).encodedPath(Chronos.path(page)).build().toString()

/**
 * One Chronos page: the pages below it in the repo's hierarchy as top tabs (when it has any), over the page itself in the fleet web view
 * (CloudWeb, WebProfile.APP_PAGE: JavaScript on, served by [ChronosWebClient] from the APK only). A link followed inside a page moves the
 * side menu and the title with it ([onOpen] with the page now shown); Back walks the view's own history first.
 */
@Composable
fun ChronosSimPage(tree: ChronosTree, page: ChronosPage, onOpen: (String) -> Unit) {
    val open by rememberUpdatedState(onOpen)
    var canBack by remember { mutableStateOf(false) }
    val held = remember { HeldView() }
    BackHandler(enabled = canBack) { held.view?.goBack() }
    Column(Modifier.fillMaxSize().testTag(Tags.CHRONOS)) {
        val kids = page.children.mapNotNull { tree.page(it) }
        if (kids.isNotEmpty()) {
            NavBarTopSecondRow(
                pages = kids.map { NavPage(it.id, it.label, it.icon) },
                selectedId = null,
                onSelect = { open(it.id) },
                modifier = Modifier.testTag(Tags.CHRONOS_TABS),
                underTopChrome = false,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(Tags.CHRONOS_WEB)) {
            AndroidView(
                factory = { c ->
                    val client = ChronosWebClient(c, tree) { shown, back ->
                        canBack = back
                        // a page reached inside the view is already loaded: the selection follows it without a second load
                        if (shown != null) { held.loaded = shown.id; open(shown.id) }
                    }
                    CloudWeb.create(c, WebProfile.APP_PAGE).also { wv ->
                        client.onRecover = { fresh -> held.view = fresh }
                        wv.webViewClient = client
                        held.view = wv
                        held.loaded = page.id
                        wv.loadUrl(chronosUrl(page))
                    }
                },
                // a page picked in the side menu, the title or the tabs: load it once
                update = { wv -> if (held.loaded != page.id) { held.loaded = page.id; (held.view ?: wv).loadUrl(chronosUrl(page)) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

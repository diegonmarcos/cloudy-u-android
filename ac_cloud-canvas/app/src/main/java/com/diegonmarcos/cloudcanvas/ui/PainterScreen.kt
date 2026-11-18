package com.diegonmarcos.cloudcanvas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.cloudcanvas.R
import com.diegonmarcos.cloudcanvas.painter.PainterStore
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import java.io.File
import org.json.JSONObject

/**
 * Painter (#1808): the owner's "Interactive Hexagon Grid" prototype (Three.js r128) as a page of the fleet web view (libs:web-core,
 * profile APP_PAGE, hardware-accelerated). The page is assets/painter (index.html, painter.css, grid.js, painter.js, own code); Three.js,
 * OrbitControls and Inter are vendored in assets/painter/vendor and pinned by its PIN.json (tools/vendor-painter.py). Every request is
 * answered from the APK over WebViewAssetLoader at https://appassets.androidplatform.net/painter/, so it works with no network.
 * The state (painted cells per shape, colour, mode, scale, 2D/3D) is saved to app storage ([PainterStore]) through the web message
 * listener `CloudPainter`, allowed for the asset origin and the main frame only: 0.5 s after each change, when the tab is left and
 * when the page is hidden (the app pausing).
 * The back gesture is not taken by the page: it has no history and no modal of its own, so back does what it does on every tab.
 */
class PainterController(ctx: Context) {
    val store = PainterStore(File(ctx.filesDir, "painter"))
    var message by mutableStateOf<String?>(null)
    private var reply: JavaScriptReplyProxy? = null
    /** Launch overrides passed to the page's query string (shape, layout, mode, color, scale; the screenshot job uses them). */
    var query: Map<String, String> = emptyMap()

    /** A message from the page (main thread). */
    fun onMessage(data: String, proxy: JavaScriptReplyProxy) {
        reply = proxy
        val m = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (m.optString("op")) {
            "ready" -> runCatching { proxy.postMessage(JSONObject().put("op", "load").put("state", store.read() ?: JSONObject.NULL).toString()) }
            "save" -> runCatching { store.write(m.getString("state")); message = null }
                .onFailure { message = "Painter could not be saved: ${it.message}" }
            "error" -> message = "Painter: " + m.optString("message").take(200)
        }
    }

    /** Asks the page to save what it holds (leaving the tab, or the app going to the background). */
    fun flush() { runCatching { reply?.postMessage("{\"op\":\"flush\"}") } }

    fun url(): String = painterUrl(query)

    companion object { val QUERY_KEYS = setOf("shape", "layout", "mode", "color", "scale") }
}

/** The web view's client: the fleet base (render-process recovery) plus the offline answers for the bundled page. */
private class PainterWebClient(ctx: Context, private val onRecover: (WebView) -> Unit) : CloudWebClient(WebProfile.APP_PAGE) {
    private val app = ctx.applicationContext
    private val bundled = WebViewAssetLoader.AssetsPathHandler(app)
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/painter/") { path -> if (path.contains("..")) null else bundled.handle("painter/$path") }
        .build()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)

    /** The page never navigates away; anything off the asset origin goes to the browser. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == CloudWeb.ASSET_HOST) return false
        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    override fun onRecovered(dead: WebView, fresh: WebView?) { if (fresh != null) onRecover(fresh) }
}

const val PAINTER_URL = "https://${CloudWeb.ASSET_HOST}/painter/index.html"

/** The page URL with the known launch overrides (shape, layout, mode, color, scale) as its query string; anything else is dropped. */
fun painterUrl(query: Map<String, String>): String {
    val q = query.filter { it.key in PainterController.QUERY_KEYS && it.value.isNotBlank() }.toSortedMap()
    return if (q.isEmpty()) PAINTER_URL else PAINTER_URL + "?" + q.entries.joinToString("&") { Uri.encode(it.key) + "=" + Uri.encode(it.value) }
}

@Composable
fun PainterScreen(ctl: PainterController, modifier: Modifier = Modifier) {
    // leaving the tab flushes; the page itself saves 0.5 s after each change and on visibilitychange/pagehide (the app pausing)
    DisposableEffect(Unit) { onDispose { ctl.flush() } }
    Column(modifier.fillMaxSize()) {
        ctl.message?.let { Text(it, color = colorResource(R.color.app_accent), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("canvas_painter_web")) {
            AndroidView(factory = { c -> painterWebView(c, ctl) }, modifier = Modifier.fillMaxSize())
        }
    }
}

@SuppressLint("RequiresFeature", "ClickableViewAccessibility")
/** The fleet web view (APP_PAGE, hardware-accelerated), the offline client and the bridge, loading the Painter page. */
private fun painterWebView(c: Context, ctl: PainterController): WebView {
    fun bridge(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "CloudPainter", setOf("https://${CloudWeb.ASSET_HOST}")) { _, msg: WebMessageCompat, _, isMainFrame, proxy ->
                if (isMainFrame) msg.data?.let { ctl.onMessage(it, proxy) }
            }
        } else ctl.message = "This Android System WebView cannot save Painter (update it from the Play Store); painting still works"
    }
    val wv = CloudWeb.create(c, WebProfile.APP_PAGE)
    // the grid owns its gestures (paint strokes, pan, pinch, orbit): the shell must not take a drag as a scroll of the chrome
    wv.setOnTouchListener { v, e ->
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
    wv.webViewClient = PainterWebClient(c) { fresh -> bridge(fresh) }
    bridge(wv)
    wv.loadUrl(ctl.url())
    return wv
}

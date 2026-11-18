package com.diegonmarcos.cloudcanvas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.diegonmarcos.cloudcanvas.board.BoardMeta
import com.diegonmarcos.cloudcanvas.board.BoardStore
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import java.io.File
import org.json.JSONObject

/**
 * Draw > Board (#1787): an infinite whiteboard in the fleet web view (libs:web-core, profile APP_PAGE, hardware-accelerated).
 * The engine is Quickdraw (MIT, https://github.com/quickdrawjs/quickdraw), vendored offline in assets/board and pinned by
 * assets/board/PIN.json (tools/vendor-board.py). The brief's first choice, the fleet's tldraw build, is kept pinned in
 * vendor/tldraw but not shipped: tldraw 5.x replaces the whole editor after 5 s in a production build without a licence key.
 * Every request is answered from the APK over WebViewAssetLoader at https://appassets.androidplatform.net/board/, so the page works
 * with no network; a link that leaves the page (the Quickdraw mark) opens in the browser, never inside the board. Boards are saved
 * to app storage ([BoardStore]) through the web message listener `CloudBoard` (assets/board/quickdraw/cloud-board-bridge.js),
 * allowed for the asset origin and the main frame only.
 */
class BoardController(ctx: Context) {
    val store = BoardStore(File(ctx.filesDir, "boards"))
    var boards by mutableStateOf(store.list())
    var currentId by mutableStateOf(store.currentId())
    var message by mutableStateOf<String?>(null)
    private var reply: JavaScriptReplyProxy? = null
    /** The tool the page opens with (launch extra `tool`: draw, geo, text, note...; the screenshot job draws with it). */
    var startTool: String? = null

    init { boards = store.list() }

    val current: BoardMeta? get() = boards.firstOrNull { it.id == currentId }

    /** A message from the page (main thread). */
    fun onMessage(data: String, proxy: JavaScriptReplyProxy) {
        reply = proxy
        val m = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (m.optString("op")) {
            "ready" -> sendLoad()
            "save" -> runCatching { store.write(m.getString("id"), m.getString("snapshot")); boards = store.list() }
                .onFailure { message = "The board could not be saved: ${it.message}" }
            "error" -> message = "Board: " + m.optString("message").take(200)
        }
    }

    private fun sendLoad() {
        val id = currentId
        val o = JSONObject().put("op", "load").put("id", id).put("snapshot", store.read(id) ?: JSONObject.NULL)
        runCatching { reply?.postMessage(o.toString()) }
    }

    /** Asks the page to save what it holds (before a switch, or when the app leaves the screen). */
    fun flush() { runCatching { reply?.postMessage("{\"op\":\"flush\"}") } }

    fun switchTo(id: String) {
        if (id == currentId) return
        flush()
        store.open(id); currentId = id; sendLoad()
    }

    fun create(name: String) { flush(); val m = store.create(name); boards = store.list(); currentId = m.id; sendLoad() }

    fun rename(name: String) { store.rename(currentId, name); boards = store.list() }

    fun deleteCurrent() { store.delete(currentId); boards = store.list(); currentId = store.currentId(); sendLoad() }
}

/** The web view's client: the fleet base (render-process recovery) plus the offline answers for the vendored engine. */
private class BoardWebClient(ctx: Context, private val onRecover: (WebView) -> Unit) : CloudWebClient(WebProfile.APP_PAGE) {
    private val app = ctx.applicationContext
    private val bundled = WebViewAssetLoader.AssetsPathHandler(app)
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/board/") { path -> if (path.contains("..")) null else bundled.handle("board/$path") }
        .build()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)

    /** The board never navigates away: a link off the asset origin (the engine's corner mark) goes to the browser. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == CloudWeb.ASSET_HOST) return false
        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    override fun onRecovered(dead: WebView, fresh: WebView?) { if (fresh != null) onRecover(fresh) }
}

const val BOARD_URL = "https://${CloudWeb.ASSET_HOST}/board/quickdraw/index.html"

@Composable
fun BoardScreen(ctl: BoardController, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val ink = colorResource(R.color.app_text)
    DisposableEffect(Unit) { onDispose { ctl.flush() } }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ctl.boards.forEach { b -> FilterChip(selected = b.id == ctl.currentId, onClick = { ctl.switchTo(b.id) }, label = { Text(b.name) }, modifier = Modifier.testTag("board_" + b.id)) }
            AssistChip(onClick = { ctl.create(BoardStore.DEFAULT_NAME) }, label = { Text("New board") })
            AssistChip(onClick = { renaming = true }, label = { Text("Rename") })
            AssistChip(onClick = { deleting = true }, label = { Text("Delete") })
        }
        ctl.message?.let { Text(it, color = colorResource(R.color.app_accent), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("canvas_board_web")) {
            AndroidView(factory = { c -> boardWebView(c, ctl) }, modifier = Modifier.fillMaxSize())
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(ctl.current?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename board") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { ctl.rename(name); renaming = false }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${ctl.current?.name ?: "board"}?") },
            text = { Text("The board and everything on it is removed from the phone.", color = ink) },
            confirmButton = { TextButton(onClick = { ctl.deleteCurrent(); deleting = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("RequiresFeature", "ClickableViewAccessibility")
/** The fleet web view (APP_PAGE, hardware-accelerated, LAYER_TYPE_NONE), the offline client and the bridge, loading the board page. */
private fun boardWebView(c: Context, ctl: BoardController): WebView {
    fun bridge(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "CloudBoard", setOf("https://${CloudWeb.ASSET_HOST}")) { _, msg: WebMessageCompat, _, isMainFrame, proxy ->
                if (isMainFrame) msg.data?.let { ctl.onMessage(it, proxy) }
            }
        } else ctl.message = "This Android System WebView cannot save boards (update it from the Play Store); drawing still works"
    }
    val wv = CloudWeb.create(c, WebProfile.APP_PAGE)
    // the whiteboard owns its gestures (pan, pinch, draw): the shell must not take a drag as a scroll of the chrome
    wv.setOnTouchListener { v, e ->
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
    wv.webViewClient = BoardWebClient(c) { fresh -> bridge(fresh) }
    bridge(wv)
    wv.loadUrl(BOARD_URL + (ctl.startTool?.let { "?tool=" + Uri.encode(it) } ?: ""))
    return wv
}

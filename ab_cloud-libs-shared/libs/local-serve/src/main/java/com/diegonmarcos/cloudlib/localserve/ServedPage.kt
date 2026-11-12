package com.diegonmarcos.cloudlib.localserve

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import java.io.ByteArrayInputStream
import java.io.File

object ServedPageTags {
    const val WEB = "served_page"
    const val FRAME = "served_page_frame"
    const val LOADING = "served_page_loading"
    const val ERROR = "served_page_error"
    const val RETRY = "served_page_retry"
}

/** What the frame shows over (or instead of) the page. */
sealed interface ServedState {
    data object Loading : ServedState
    data object Shown : ServedState
    data class Failed(val reason: String) : ServedState
}

/** The frame's words; English defaults, an app passes its own strings. */
data class ServedPageLabels(
    val loading: String = "Loading…",
    val failed: String = "The page could not be shown: %s",
    val serverDown: String = "the local server did not start",
    val retry: String = "Retry",
)

/**
 * Shows [file] through a [LocalServer] on its own folder, in a WebView held to loopback: relative CSS, JS and images load from the
 * folder, every other host is blocked. The server stops when the page leaves composition (the tab closes or changes mode).
 *
 * #1669 the frame: the WebView is MATCH_PARENT inside the frame and the frame takes the caller's whole area. A WebView added by
 * AndroidView gets WRAP_CONTENT layout params by default, and Chromium then lays the page out at a zero / content height: a page
 * whose body is `height:100%` (an app-shell page with its own inner scroller) drew in a strip of the area and the rest stayed the
 * empty window colour. The page draws on [background] (the app's surface, never black), with a loading state until the main
 * frame has finished and an error state with Retry when it fails. The viewport is the page's own meta viewport at scale 1
 * ([applyServedViewport]). The view takes no window insets: the host already placed it between its bars.
 */
@Composable
fun ServedPage(
    file: File,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    labels: ServedPageLabels = ServedPageLabels(),
    // #1483 the host's hooks on the served WebView: [onWebView] once it exists (a shell attaches its chrome scroll bridge there),
    // [onPageShown] each time the page finished loading (the bridge's page script goes in then). Both default to nothing.
    onWebView: (WebView) -> Unit = {},
    onPageShown: (WebView) -> Unit = {},
) {
    val server = remember(file) { LocalServer(file.absoluteFile.parentFile ?: file.absoluteFile).also { runCatching { it.start() } } }
    DisposableEffect(server) { onDispose { server.close() } }
    var state by remember(server) { mutableStateOf<ServedState>(if (server.isRunning) ServedState.Loading else ServedState.Failed(labels.serverDown)) }
    val web = remember(server) { arrayOfNulls<WebView>(1) }
    Box(modifier.fillMaxSize().background(background).testTag(ServedPageTags.FRAME)) {
        if (server.isRunning) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag(ServedPageTags.WEB),
                factory = { ctx ->
                    servedWebView(ctx, background.toArgb(), onState = { state = it; if (it == ServedState.Shown) web[0]?.let { v -> runCatching { onPageShown(v) } } }, onView = { web[0] = it }).apply {
                        runCatching { onWebView(this) }
                        runCatching { loadUrl(server.url(file.name)) }.onFailure { state = ServedState.Failed(it.message ?: it.javaClass.simpleName) }
                    }
                },
                onRelease = { runCatching { it.stopLoading(); it.destroy() } },
            )
        }
        when (val s = state) {
            ServedState.Shown -> Unit
            ServedState.Loading -> Column(
                Modifier.fillMaxSize().background(background).testTag(ServedPageTags.LOADING),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(labels.loading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ServedState.Failed -> Column(
                Modifier.fillMaxSize().background(background).padding(24.dp).testTag(ServedPageTags.ERROR),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(labels.failed.replace("%s", s.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                val view = web[0]
                if (view != null) TextButton({ state = ServedState.Loading; runCatching { view.reload() } }, Modifier.testTag(ServedPageTags.RETRY)) { Text(labels.retry) }
            }
        }
    }
}

/** The page's own meta viewport at scale 1: wide viewport honours `width=device-width`, overview never zooms such a page out. */
fun applyServedViewport(wv: WebView) {
    wv.settings.useWideViewPort = true
    wv.settings.loadWithOverviewMode = true
    wv.settings.textZoom = 100
    wv.setInitialScale(0)
}

/** The served WebView, sized to its frame (MATCH_PARENT), on [backgroundArgb], reporting the main frame's state. */
fun servedWebView(ctx: Context, backgroundArgb: Int, onState: (ServedState) -> Unit = {}, onView: (WebView) -> Unit = {}): WebView {
    fun prepare(v: WebView) {
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.setBackgroundColor(backgroundArgb)
        applyServedViewport(v)
        // the host already placed the view between its bars: the page must not pad itself by the system bars a second time
        v.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets().consumeDisplayCutout()
        }
        onView(v)
    }
    val wv = CloudWeb.create(ctx, WebProfile.APP_PAGE)
    wv.webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {
        private var failed = false
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isLoopback(request.url.host)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (isLoopback(request.url.host)) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { failed = false; onState(ServedState.Loading) }
        override fun onPageFinished(view: WebView, url: String?) { if (!failed) onState(ServedState.Shown) }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { failed = true; onState(ServedState.Failed(error.description?.toString().orEmpty().ifBlank { "error ${error.errorCode}" })) }
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) { failed = true; onState(ServedState.Failed("HTTP ${errorResponse.statusCode}")) }
        }
        override fun onRecovered(dead: WebView, fresh: WebView?) { if (fresh != null) prepare(fresh) else onState(ServedState.Failed("the page renderer stopped")) }
    }
    prepare(wv)
    return wv
}

internal fun isLoopback(host: String?): Boolean = host == "127.0.0.1" || host == "localhost"

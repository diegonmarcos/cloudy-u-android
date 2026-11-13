package com.diegonmarcos.cloudwebserver.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile

/** The controls a toolbar needs on an open frame (back, reload), filled in once the view exists. */
class FrameHandle {
    var view: WebView? = null
    fun back(): Boolean = view?.takeIf { it.canGoBack() }?.let { runCatching { it.goBack() }; true } ?: false
    fun reload() { view?.let { runCatching { it.reload() } } }
}

/**
 * #1682 P1 the browser frame of Pages > Pages and the preview of File Explorer: one libs:web-core web view (WebProfile.APP_PAGE: our
 * own loopback origin, `/__app__/root/<id>/...`, served same-origin by the Rust core) that fills the area between the toolbar and the
 * bottom nav. The #1669 ServedPage fix applies: the view is MATCH_PARENT inside a frame that takes the caller's whole area (an
 * AndroidView WebView defaults to WRAP_CONTENT and Chromium then lays an app-shell page out in a strip). [onLocation] reports the
 * page the frame is on (links navigate inside the root), [onError] a main-frame failure; the frame never throws.
 */
@Composable
fun WebFrame(url: String, handle: FrameHandle, modifier: Modifier = Modifier, onLocation: (String) -> Unit = {}, onError: (String?) -> Unit = {}, onLink: ((String) -> Unit)? = null) {
    val link by rememberUpdatedState(onLink)
    val bg = MaterialTheme.colorScheme.surface.toArgb()
    var loaded by remember { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("c3_frame")) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("c3_frame_web"),
            factory = { ctx ->
                CloudWeb.create(ctx, WebProfile.APP_PAGE).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(bg)
                    webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {
                        /** #1710 a tap that leaves the frame's origin goes to the declared link-open (Open in browser | Open embedded), as the web shell's link handler did for every frame. */
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val cb = link ?: return false
                            val to = request?.url ?: return false
                            if (request.isForMainFrame.not() || !request.hasGesture()) return false
                            val from = runCatching { android.net.Uri.parse(view?.url ?: "") }.getOrNull() ?: return false
                            if (to.scheme == from.scheme && to.host == from.host && to.port == from.port) return false
                            cb(to.toString())
                            return true
                        }

                        override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, u, favicon)
                            onError(null)
                        }

                        override fun onPageFinished(view: WebView?, u: String?) {
                            super.onPageFinished(view, u)
                            if (u != null) onLocation(u)
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) onError(error?.description?.toString() ?: "the page could not be loaded")
                        }
                    }
                    handle.view = this
                }
            },
            update = { wv -> if (loaded != url) { loaded = url; runCatching { wv.loadUrl(url) }.onFailure { onError(it.message) } } },
            onRelease = { wv -> if (handle.view === wv) handle.view = null; runCatching { wv.stopLoading(); wv.destroy() } },
        )
    }
    DisposableEffect(Unit) { onDispose { handle.view = null } }
}

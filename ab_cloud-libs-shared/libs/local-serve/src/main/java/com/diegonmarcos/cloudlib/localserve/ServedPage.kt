package com.diegonmarcos.cloudlib.localserve

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import java.io.ByteArrayInputStream
import java.io.File

object ServedPageTags { const val WEB = "served_page" }

/**
 * Shows [file] through a [LocalServer] on its own folder, in a WebView held to loopback: relative CSS, JS and images load from the
 * folder, every other host is blocked. The server stops when the page leaves composition (the tab closes or changes mode).
 */
@Composable
fun ServedPage(file: File, modifier: Modifier = Modifier) {
    val server = remember(file) { LocalServer(file.absoluteFile.parentFile ?: file.absoluteFile).also { runCatching { it.start() } } }
    DisposableEffect(server) { onDispose { server.close() } }
    if (!server.isRunning) return
    AndroidView(
        modifier = modifier.testTag(ServedPageTags.WEB),
        factory = { ctx ->
            CloudWeb.create(ctx, WebProfile.APP_PAGE).apply {
                webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isLoopback(request.url.host)
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (isLoopback(request.url.host)) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                loadUrl(server.url(file.name))
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

internal fun isLoopback(host: String?): Boolean = host == "127.0.0.1" || host == "localhost"

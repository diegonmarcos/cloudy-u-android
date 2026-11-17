package com.diegonmarcos.cloudme

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * #1771 The bundled mySocials pages name the Google Fonts stylesheet in a plain <link> ahead of their own data and script files.
 * A stylesheet still loading holds back every later parser-inserted script, so on a phone that cannot reach the font host quickly
 * (the first page of a cold WebView, a captive or absent network) the Professional page stayed an empty body until the request
 * gave up: the page's script, which draws the whole profile, had not run yet. The stylesheet only picks the Inter face; the page
 * falls back to its system font stack without it. So that one request is answered at once with an empty sheet and the page's
 * scripts run on the first parse. Nothing else is touched: images, data and the analytics calls keep their normal path.
 */
object FontSheetGate {
    /** True for the render-blocking font stylesheet request (the font files it would name are never requested then). */
    fun blocks(host: String?, path: String?): Boolean =
        host.equals("fonts.googleapis.com", ignoreCase = true) && (path ?: "").startsWith("/css")

    fun emptySheet(): WebResourceResponse =
        WebResourceResponse("text/css", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}

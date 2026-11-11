package com.diegonmarcos.cgomniverse.ui

import android.webkit.JavascriptInterface
import com.diegonmarcos.cgomniverse.OmniXray

/**
 * #1631 window.OmniDiag, added ONLY to the bundled Avatar page (assets/avatar/index.html, served from the app's own asset origin):
 * the page pushes its probe snapshot (WebGL contexts, draws, frames, fetches, errors, canvas) here. Never added to a Store world.
 * JavaScript calls arrive on a WebView thread; [OmniXray] is synchronized.
 */
class OmniXrayBridge {
    @JavascriptInterface fun report(json: String) { OmniXray.pageReport(json.take(120_000)) }
}

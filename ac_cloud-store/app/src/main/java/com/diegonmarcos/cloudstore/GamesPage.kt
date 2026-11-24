package com.diegonmarcos.cloudstore

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Search > Games (#1895). The Games tab of Cloud Media Center moved here: the same page (assets/games/index.html, games.js,
 * games.css: a catalogue of well-known Android games by category with Install and Open, fetched at runtime from
 * data/fleet/games-catalogue.json) in the fleet web view (libs:web-core, profile APP_PAGE: JavaScript and local storage), every
 * request answered from the APK over WebViewAssetLoader at https://appassets.androidplatform.net/assets/games/.
 *
 * What Media Center's Tauri webview gave the page is a web message bridge, `CloudGames` (main frame of the asset origin only):
 *  - `installed_packages` {pkgs}: of the package names, the ones that are launchable on this phone (was the Rust command of the
 *    same name over PackageManager.getLaunchIntentForPackage);
 *  - `open_url` {url}: hands an https or intent: URL to the system (was tauri-plugin-opener's openUrl); the reply is an error
 *    when nothing handles it, so the page tries the next URL exactly as before.
 * [handle] is the whole bridge, pure over a Context so a test drives it.
 */
object GamesPage {
    const val URL = "https://${CloudWeb.ASSET_HOST}/assets/games/index.html"
    const val BRIDGE = "CloudGames"
    const val TAG = "store-games:web"

    /** The reply to one request {id, op, args}: {id, result} or {id, error}. Never throws. */
    fun handle(ctx: Context, data: String): String {
        val req = runCatching { JSONObject(data) }.getOrNull() ?: return JSONObject().put("id", JSONObject.NULL).put("error", "bad request").toString()
        val id = req.opt("id")
        val out = JSONObject().put("id", id)
        runCatching {
            val args = req.optJSONObject("args") ?: JSONObject()
            when (req.optString("op")) {
                "installed_packages" -> out.put("result", installed(ctx, args.optJSONArray("pkgs") ?: JSONArray()))
                "open_url" -> { open(ctx, args.optString("url")); out.put("result", JSONObject.NULL) }
                else -> out.put("error", "unknown op")
            }
        }.onFailure { out.put("error", it.message ?: it.javaClass.simpleName) }
        return out.toString()
    }

    /** The launchable subset of [pkgs]; a package name the page sends that is not a name is skipped. */
    fun installed(ctx: Context, pkgs: JSONArray): JSONArray {
        val pm = ctx.packageManager
        val found = JSONArray()
        for (i in 0 until pkgs.length()) {
            val p = pkgs.optString(i)
            if (p.isNotEmpty() && runCatching { pm.getLaunchIntentForPackage(p) }.getOrNull() != null) found.put(p)
        }
        return found
    }

    /** https and intent: URLs only; an intent: URL loses any component or selector it names (it may only address by action and package). */
    fun intentFor(url: String): Intent {
        val i = when {
            url.startsWith("intent:") -> Intent.parseUri(url, Intent.URI_INTENT_SCHEME).also { it.component = null; it.selector = null }
            url.startsWith("https://") -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
            else -> throw IllegalArgumentException("not an https or intent URL")
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(ctx: Context, url: String) { ctx.startActivity(intentFor(url)) }

    private class Client(private val app: Context) : CloudWebClient(WebProfile.APP_PAGE, CloudWeb.assetLoader(app)) {
        /** The page never navigates away: a link off the asset origin goes to the system. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == CloudWeb.ASSET_HOST) return false
            runCatching { open(app, request.url.toString()) }
            return true
        }
        override fun onRecovered(dead: WebView, fresh: WebView?) { if (fresh != null) bridge(fresh) }
    }

    @SuppressLint("RequiresFeature")
    private fun bridge(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val app = wv.context.applicationContext
        WebViewCompat.addWebMessageListener(wv, BRIDGE, setOf("https://${CloudWeb.ASSET_HOST}")) { _, msg: WebMessageCompat, _, isMainFrame, proxy ->
            if (isMainFrame) msg.data?.let { proxy.postMessage(handle(app, it)) }
        }
    }

    fun webView(c: Context): WebView {
        val wv = CloudWeb.create(c, WebProfile.APP_PAGE)
        wv.webViewClient = Client(c.applicationContext)
        bridge(wv)
        wv.loadUrl(URL)
        return wv
    }

    /** The pane libs:appstore's Search > Games draws ([com.diegonmarcos.superapp.appstore.StorePages.gamesPane]). */
    @Composable
    fun Pane(modifier: Modifier) {
        AndroidView(factory = { c -> webView(c) }, modifier = modifier.testTag(TAG))
    }
}

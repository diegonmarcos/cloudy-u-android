package com.diegonmarcos.cloudlib.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * The one place a WebView of the fleet is built and configured (#1371). Apps call [create] (or [configure] for a
 * view another framework built, e.g. a Capacitor bridge or a subclass) with a [WebProfile]; nothing else sets
 * WebSettings by hand (cloud-android-webview-guard.py).
 *
 * GPU: the window is hardware-accelerated (the platform default) and the view keeps LAYER_TYPE_NONE, so the WebView
 * draws through its GL functor straight into the window. A LAYER_TYPE_HARDWARE layer would add an offscreen copy of
 * the whole page per frame; a host may still latch a device onto software after [configure] (Mail's crash guard).
 * WebGL2 and WebGPU have no WebSettings switch: they are on whenever the installed System WebView and GPU allow,
 * which [GpuProbe] measures at runtime. androidx.webkit has no WebGPU feature or setting either (checked against
 * WebViewFeature, #1430); the gate is Chromium's per-GPU allowlist, see [GpuCaps.reason]. The engine is
 * [WebEngine.resolve]d per profile (GeckoView is declared, not linked).
 */
object CloudWeb {
    const val ASSET_HOST: String = "appassets.androidplatform.net"   // WebViewAssetLoader.DEFAULT_DOMAIN
    const val CHROME_PACKAGE = "com.android.chrome"
    const val DESKTOP_WIDTH = 1280

    /** A bundled asset under `assets/`, served over https so ES modules and fetch() work (file:// cannot). */
    fun assetUrl(path: String): String = "https://$ASSET_HOST/assets/${path.removePrefix("/")}"

    fun assetLoader(ctx: Context): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(ctx))
        .build()

    private val profiles = WeakHashMap<WebView, WebProfile>()
    private val desktopScripts = WeakHashMap<WebView, ScriptHandler>()
    private val desktopViews = WeakHashMap<WebView, Boolean>()

    fun profileOf(wv: WebView): WebProfile? = profiles[wv]

    /**
     * Builds a WebView for [profile]. [desktop] asks for a desktop UA and viewport ([desktopMode]). [prepare] runs on
     * the bare view before any setting is touched (the Browser binds its WebView profile there, which must come first).
     */
    @JvmStatic
    @JvmOverloads
    fun create(ctx: Context, profile: WebProfile, desktop: Boolean = false, prepare: (WebView) -> Unit = {}): WebView =
        configure(WebView(ctx).also(prepare), profile, desktop)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun <T : WebView> configure(wv: T, profile: WebProfile, desktop: Boolean = false): T {
        profiles[wv] = profile
        wv.setLayerType(View.LAYER_TYPE_NONE, null)
        // debugging only (#1430): a debuggable build of a GPU world or the Browser opens chrome://inspect, and the
        // WebView DevTools app's flags (enable-unsafe-webgpu, Vulkan) reach it; a release build never does
        if ((profile == WebProfile.WEB_WORLD || profile == WebProfile.BROWSER) &&
            wv.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) optional { WebView.setWebContentsDebuggingEnabled(true) }
        wv.settings.apply {
            javaScriptEnabled = profile.javaScript
            domStorageEnabled = profile.storage
            allowFileAccess = profile.fileAccess
            allowContentAccess = profile != WebProfile.UNTRUSTED_HTML
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = profile == WebProfile.APP_PAGE
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkImage = !profile.remoteImages
            mixedContentMode = profile.mixedContent
            mediaPlaybackRequiresUserGesture = !profile.autoplay
            setSupportZoom(profile.zoom)
            builtInZoomControls = profile.zoom
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // the Browser's sign-in popups (window.open + window.opener) need it; nothing else opens windows
            javaScriptCanOpenWindowsAutomatically = profile == WebProfile.BROWSER
            setSupportMultipleWindows(profile == WebProfile.BROWSER)
            setGeolocationEnabled(profile == WebProfile.BROWSER)
        }
        // optional platform knobs: a WebView provider (or a Robolectric shadow) without one must not fail the build of
        // the view. Keep tiles just off-screen rasterised so a scroll or a camera move never shows a blank; the
        // renderer of a visible web view is IMPORTANT to the OOM killer, and a world keeps that while hidden too, so
        // flipping a tab does not cost its GPU context.
        optional { wv.settings.offscreenPreRaster = profile != WebProfile.UNTRUSTED_HTML }
        optional { wv.settings.safeBrowsingEnabled = true }
        // #1421 pages render as their authors wrote them: never algorithmically darkened (a dark app theme otherwise
        // recolours light-only pages, which reads as "the site's JS/CSS renders strangely"); a page's own
        // prefers-color-scheme dark styles still apply
        if (profile == WebProfile.BROWSER) optional {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING))
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }
        optional { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, profile != WebProfile.WEB_WORLD) }
        optional { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, profile.thirdPartyCookies) }
        if (profile == WebProfile.WEB_WORLD) {
            wv.setBackgroundColor(0)
            // a 3D scene owns its gestures: the shell must not take a drag or a pinch as a scroll
            wv.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        desktopMode(wv, desktop)
        val client: WebViewClient? = wv.webViewClient
        if (client == null || client.javaClass == WebViewClient::class.java) wv.webViewClient = CloudWebClient(profile)
        if (wv.webChromeClient == null) wv.webChromeClient = CloudChromeClient(CloudChromeClient.activityOf(wv.context))
        return wv
    }

    private inline fun optional(block: () -> Unit) {
        try { block() } catch (ignored: Throwable) { }
    }

    /** Clears the app's one Chromium HTTP cache (per app, not per view), so a throwaway view serves. */
    fun clearCache(ctx: Context) {
        val wv = WebView(ctx)
        wv.clearCache(true)
        wv.destroy()
    }

    /**
     * The Browser's per-site switches (user settings, shields, an offline archive's file access) on a BROWSER view.
     * Any other profile is left as declared: its JS and file access are not a runtime choice.
     */
    fun site(wv: WebView, javaScript: Boolean, fileAccess: Boolean) {
        if (profiles[wv] != WebProfile.BROWSER) return
        wv.settings.javaScriptEnabled = javaScript
        wv.settings.allowFileAccess = fileAccess
    }

    /** Sets a specific user agent (null = the WebView's default); desktop mode is [desktopMode]. */
    fun userAgent(wv: WebView, ua: String?) {
        wv.settings.userAgentString = ua
    }

    /** Appends [marker] to the current user agent (a page that must know it is embedded reads it). */
    fun appendUserAgent(wv: WebView, marker: String) {
        val s = wv.settings
        if (!s.userAgentString.orEmpty().endsWith(marker)) s.userAgentString = s.userAgentString + marker
    }

    /**
     * JS for an UNTRUSTED_HTML view whose page is our own code over foreign markup (Mail's compose editor, the
     * signature preview): storage, file and content access stay off and the host's client must deny navigation.
     * Any other profile is left as declared.
     */
    fun sandboxedJs(wv: WebView, on: Boolean) {
        if (profiles[wv] == WebProfile.UNTRUSTED_HTML) wv.settings.javaScriptEnabled = on
    }

    /** Lifts (or restores) the remote-image gate of an UNTRUSTED_HTML view once the reader allows it. */
    fun remoteImages(wv: WebView, allowed: Boolean) {
        wv.settings.blockNetworkImage = !allowed
    }

    /**
     * Desktop mode: a desktop Chrome UA with the installed WebView's own Chrome version, plus a desktop viewport
     * ([DESKTOP_WIDTH] css px, injected at document start where the WebView supports it). Sites that gate on
     * `navigator.userAgent` (foodforfish.org tests /Android|iPhone|.../) then take the desktop path. It cannot change
     * what the GPU can do: a capability check (navigator.gpu) still answers for this device.
     */
    fun desktopMode(wv: WebView, on: Boolean) {
        val s = wv.settings
        // only a UA this sets is reset: a host's own UA (Capacitor's, an appended marker) is not ours to clear
        if (on) s.userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(wv.context))
        else if (desktopViews.remove(wv) != null) s.userAgentString = null
        if (on) desktopViews[wv] = true
        desktopScripts.remove(wv)?.remove()
        if (on && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            desktopScripts[wv] = WebViewCompat.addDocumentStartJavaScript(wv, DESKTOP_VIEWPORT_JS, setOf("*"))
        }
    }

    fun isDesktop(wv: WebView): Boolean = desktopViews.containsKey(wv)

    fun desktopUserAgent(mobileUa: String): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUa)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private val DESKTOP_VIEWPORT_JS = """
        (function(){function f(){var m=document.querySelector('meta[name=viewport]');
        if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
        m.content='width=$DESKTOP_WIDTH';}f();document.addEventListener('DOMContentLoaded',f);})();
    """.trimIndent()

    /** Opens [url] in Chrome (which ships WebGPU on Android 12+), or in any browser when Chrome is absent. */
    fun openInChrome(ctx: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(Intent(intent).setPackage(CHROME_PACKAGE)); true }.getOrElse {
            runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
        }
    }
}

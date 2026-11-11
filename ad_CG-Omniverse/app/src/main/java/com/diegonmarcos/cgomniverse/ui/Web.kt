package com.diegonmarcos.cgomniverse.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.cgomniverse.RenderProbe
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.SiteGpuChoice
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The app's two kinds of page: the bundled Avatar scene and a world from the Store. Built by libs:web-core (WEB_WORLD). */
object Web {
    /** The bundled Avatar scene, served by CloudWeb.assetLoader so its ES modules load (a file:// page may not import modules). */
    val AVATAR_URL: String = CloudWeb.assetUrl("avatar/index.html")

    /** Links open in Cloud-Browser, the fleet's one browser; never an external app. */
    fun openInBrowser(ctx: Context, url: String) {
        CloudWeb.openInCloudBrowser(ctx, url)
    }

    /**
     * #1657 the pixels the screen really shows in [wv]'s area below [RenderProbe.SAMPLE_TOP]: a PixelCopy of the window, so the
     * WebView's GPU layers (canvas, video, WebGL) count exactly as the user sees them. Null when the window cannot be read.
     */
    suspend fun screenSample(act: Activity?, wv: WebView): RenderProbe.Screen? {
        if (act == null || wv.width <= 0 || wv.height <= 0 || !wv.isAttachedToWindow) return null
        val at = IntArray(2).also { wv.getLocationInWindow(it) }
        val rect = Rect(at[0], at[1] + (wv.height * RenderProbe.SAMPLE_TOP).toInt(), at[0] + wv.width, at[1] + wv.height)
        val n = RenderProbe.SAMPLE
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val ok = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Boolean> { c ->
                runCatching {
                    PixelCopy.request(act.window, rect, bmp, { r -> if (c.isActive) c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }.onFailure { if (c.isActive) c.resume(false) }
            }
        } ?: false
        if (!ok) return null
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        return RenderProbe.screen(px)
    }

    /** A world declares a WebGPU stack (store.json stack / framework). */
    fun needsWebGpu(w: World): Boolean = w.stack.any { it.equals("WebGPU", true) } || w.framework.contains("WebGPU", true)

    /** Worlds already auto-routed this process, so a tab re-select does not relaunch the browser. */
    val routed = mutableSetOf<String>()

    /** Per-site WebGPU choice (AUTO / IN_APP / CLOUD_BROWSER), remembered on the device. */
    fun gpuChoice(ctx: Context, worldId: String): SiteGpuChoice = SiteGpuChoice.parse(prefs(ctx).getString("gpu.$worldId", null))
    fun setGpuChoice(ctx: Context, worldId: String, c: SiteGpuChoice) = prefs(ctx).edit().putString("gpu.$worldId", c.name).apply()

    /** Per-world desktop mode (desktop UA + viewport), remembered on the device. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun desktop(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("desktop.$worldId", false)
    fun setDesktop(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("desktop.$worldId", on).apply()

    /** #1657 per-world presentation, remembered once the CPU presentation is seen on the screen: true = CPU-backed canvases and the world's CPU launch query. */
    fun cpuPresent(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("cpu.$worldId", false)
    fun setCpuPresent(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("cpu.$worldId", on).apply()

    /** Installs [RenderProbe.CPU_CANVAS_JS] at document start of every frame of [wv] (before any page script); false when this WebView cannot. */
    fun cpuCanvas(wv: WebView): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        runCatching { WebViewCompat.addDocumentStartJavaScript(wv, RenderProbe.CPU_CANVAS_JS, setOf("*")) }.isSuccess

    /** Galaxy's open tabs and the selected one ([com.diegonmarcos.cgomniverse.Tabs.encode]); null until the first save. */
    fun tabs(ctx: Context): String? = prefs(ctx).getString("galaxy.tabs", null)
    fun setTabs(ctx: Context, encoded: String) = prefs(ctx).edit().putString("galaxy.tabs", encoded).apply()
}

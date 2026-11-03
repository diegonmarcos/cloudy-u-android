package com.diegonmarcos.cloudlib.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import org.json.JSONObject

/**
 * What the installed Android System WebView can draw with on this device, measured, not assumed (#1371).
 * [webgpu] = `navigator.gpu` exists; [adapter] = requestAdapter() returned one (the API can exist with no usable
 * adapter); [features] = the adapter's optional features (a site may require some, e.g. float32-filterable).
 */
data class GpuCaps(
    val webgl2: Boolean,
    val webgpu: Boolean,
    val adapter: Boolean,
    val features: List<String>,
    val webViewVersion: String?,
    val renderer: String?,
    /** requestAdapter({featureLevel: 'compatibility'}) returned one: WebGPU on OpenGL ES (Chromium 146+). */
    val compatAdapter: Boolean = false,
    /** GPUAdapterInfo "vendor/architecture" of the adapter, when one exists. */
    val adapterInfo: String? = null,
    /** The engine that was measured ([WebEngine]). */
    val engine: WebEngine = WebEngine.SYSTEM_WEBVIEW,
) {
    /** WebGPU usable in-app: the API and an adapter. */
    val webgpuUsable: Boolean get() = webgpu && adapter

    /** WebGPU usable at all, core or compatibility (OpenGL ES) feature level. */
    val webgpuAny: Boolean get() = webgpu && (adapter || compatAdapter)

    fun has(vararg required: String): Boolean = webgpuUsable && required.all { it in features }

    /** One line a UI can show (Galaxy, Browser, Configs). */
    fun summary(): String = buildString {
        append("WebGL2 ").append(if (webgl2) "on" else "off")
        append(" · WebGPU ").append(
            when {
                webgpuUsable -> "on (${features.size} features)"
                webgpu && compatAdapter -> "compatibility mode only (OpenGL ES)"
                webgpu -> "API without adapter"
                else -> "not in this WebView"
            },
        )
        webViewVersion?.let { append(" · WebView ").append(it) }
    }

    /**
     * Why WebGPU is missing, in words a UI can show under [summary]; null when a core adapter exists (#1430).
     * "API without adapter" means Chromium's GPU blocklist refused the device's Vulkan driver: on Android, Chromium
     * enables WebGPU only for allowlisted GPUs (ARM, Qualcomm, Intel on Android 12+, Imagination on 16+; Samsung
     * Xclipse is not yet listed), and Chrome on the same phone reads the same list. No app setting overrides it.
     */
    fun reason(): String? = when {
        webgpuUsable -> null
        !webgpu -> "This WebView build does not expose navigator.gpu."
        compatAdapter -> "Only WebGPU compatibility mode (OpenGL ES) is available; a site that needs a core " +
            "adapter or extra features still fails."
        else -> "WebGPU is present but this GPU${renderer?.let { " ($it)" } ?: ""} is not on Chromium's WebGPU " +
            "allowlist for Android, so no adapter is offered. Chrome on this phone uses the same list."
    }
}

object GpuProbe {
    @Volatile
    var last: GpuCaps? = null
        private set

    private const val PAGE = """<!doctype html><script>
(async () => {
  const r = {webgl2: false, webgpu: false, adapter: false, compat: false, features: [], renderer: null, info: null};
  const info = a => { try { const i = a.info || {}; return [i.vendor, i.architecture].filter(Boolean).join('/') || null; } catch (e) { return null; } };
  try {
    const gl = document.createElement('canvas').getContext('webgl2');
    r.webgl2 = !!gl;
    const dbg = gl && gl.getExtension('WEBGL_debug_renderer_info');
    r.renderer = gl ? String(gl.getParameter(dbg ? dbg.UNMASKED_RENDERER_WEBGL : gl.RENDERER)) : null;
  } catch (e) {}
  try {
    if (navigator.gpu) {
      r.webgpu = true;
      const a = await navigator.gpu.requestAdapter({powerPreference: 'high-performance'});
      if (a) { r.adapter = true; r.features = Array.from(a.features); r.info = info(a); }
      else {
        const c = await navigator.gpu.requestAdapter({featureLevel: 'compatibility'});
        if (c) { r.compat = true; r.features = Array.from(c.features); r.info = info(c); }
      }
    }
  } catch (e) {}
  CloudGpu.done(JSON.stringify(r));
})();
</script>"""

    /**
     * Measures once per process (cached in [last]) in a detached WEB_WORLD view on a secure origin
     * (navigator.gpu only exists in a secure context). [done] runs on the main thread; on a 6 s timeout it gets a
     * result with everything false.
     */
    fun probe(ctx: Context, done: (GpuCaps) -> Unit) {
        last?.let { done(it); return }
        val main = Handler(Looper.getMainLooper())
        main.post {
            val app = ctx.applicationContext
            val version = WebViewCompat.getCurrentWebViewPackage(app)?.versionName
            val wv = CloudWeb.create(app, WebProfile.WEB_WORLD)
            var finished = false
            fun finish(caps: GpuCaps) {
                if (finished) return
                finished = true
                last = caps
                // WARN so CI emulator logcat (*:W) keeps the one line per process
                android.util.Log.w(
                    "GpuProbe",
                    "${caps.engine}: ${caps.summary()} · renderer=${caps.renderer} · adapter=${caps.adapterInfo} · " +
                        "reason=${caps.reason()}",
                )
                wv.destroy()
                done(caps)
            }
            wv.addJavascriptInterface(
                Bridge { json -> main.post { finish(parse(json, version)) } },
                "CloudGpu",
            )
            wv.loadDataWithBaseURL("https://${CloudWeb.ASSET_HOST}/gpu-probe", PAGE, "text/html", "utf-8", null)
            main.postDelayed({ finish(GpuCaps(false, false, false, emptyList(), version, null)) }, 6_000)
        }
    }

    internal fun parse(json: String, version: String?): GpuCaps {
        val o = JSONObject(json)
        val f = o.optJSONArray("features")
        return GpuCaps(
            webgl2 = o.optBoolean("webgl2"),
            webgpu = o.optBoolean("webgpu"),
            adapter = o.optBoolean("adapter"),
            features = List(f?.length() ?: 0) { f!!.getString(it) },
            webViewVersion = version,
            renderer = o.optString("renderer").takeIf { it.isNotEmpty() && it != "null" },
            compatAdapter = o.optBoolean("compat"),
            adapterInfo = o.optString("info").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private class Bridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun done(json: String) = onResult(json)
    }
}

/** Features foodforfish.org requests from requestDevice: the reference WebGPU world of #1371. */
val FOODFORFISH_FEATURES = arrayOf("rg11b10ufloat-renderable", "float32-filterable")

package com.diegonmarcos.cloudlib.web

/** One line of the Cloud-Browser Configs > GPU page. */
data class GpuRow(val label: String, val value: String)

/**
 * #1518 the Configs > GPU page, the pure half: what is shown (provider and version, WebGL renderer and vendor, the
 * WebGPU adapter result or the exact reason it is missing, a chrome://gpu-like feature list), the owner steps, and the
 * WebView DevTools intent. The probe ([GpuProbe]) measures; this only words it, so the JVM suite holds the page.
 */
object GpuPageModel {
    /** The action of the WebView DevTools activity every System WebView channel ships. */
    const val DEV_UI_ACTION = "com.android.webview.SHOW_DEV_UI"

    /** The same start from a shell (the ADB variant, through the shell broker where one is installed). */
    const val DEV_UI_ADB = "am start -a $DEV_UI_ACTION"

    val steps: List<String> get() = WebGpuPlan.OWNER_STEPS

    fun providerRow(pkg: String?, version: String?): GpuRow =
        GpuRow("WebView provider", if (pkg == null) "unknown" else listOfNotNull(pkg, version).joinToString(" "))

    /** The WebGPU line: the adapter, or the exact reason there is none (never just "unavailable"). */
    fun adapterRow(c: GpuCaps): GpuRow = GpuRow("WebGPU adapter", when {
        c.webgpuUsable -> "available" + (c.adapterInfo?.let { ": $it" } ?: "") + " (${c.features.size} features)"
        !c.webgpu -> "null: navigator.gpu is undefined (${c.reason()})"
        c.compatAdapter -> "null for a core adapter: ${c.adapterNote ?: "requestAdapter() returned null"}; ${c.reason()}"
        else -> "null: ${c.adapterNote ?: "requestAdapter() returned null"}; ${c.reason()}"
    })

    /** The rows of the page, top to bottom; [caps] null = the probe has not answered yet. */
    fun rows(pkg: String?, version: String?, caps: GpuCaps?): List<GpuRow> {
        val out = ArrayList<GpuRow>()
        out += providerRow(pkg, version)
        if (caps == null) { out += GpuRow("Probe", "measuring in a hidden WebView ..."); return out }
        out += GpuRow("WebGL renderer", caps.renderer ?: "none (WebGL did not start)")
        out += GpuRow("WebGL vendor", caps.vendor ?: "unknown")
        out += GpuRow("WebGL2", if (caps.webgl2) "on" else "off")
        out += adapterRow(caps)
        return out
    }

    /** The chrome://gpu-like feature list: what the WebGL context and the adapter reported, one row each. */
    fun features(caps: GpuCaps?): List<GpuRow> {
        if (caps == null) return emptyList()
        val out = ArrayList<GpuRow>()
        for ((k, v) in caps.glInfo) out += GpuRow(k, v)
        if (caps.glExtensions.isNotEmpty()) out += GpuRow("WebGL extensions", "${caps.glExtensions.size}: " + caps.glExtensions.sorted().joinToString(", "))
        if (caps.features.isNotEmpty()) out += GpuRow("WebGPU features", caps.features.sorted().joinToString(", "))
        if (caps.compatAdapter) out += GpuRow("WebGPU compatibility mode", "adapter available (OpenGL ES)")
        return out
    }
}

/**
 * #1518 the maximum refresh rate is requested ONLY while a WebGL / WebGPU canvas page is fullscreen, and reverted the
 * moment it is not, or when Super Saving Mode is on (it turns the system battery saver on and caps the rate at 60 Hz).
 */
object RefreshRatePolicy {
    fun wantMax(fullscreen: Boolean, gpuCanvas: Boolean, superSaving: Boolean): Boolean = fullscreen && gpuCanvas && !superSaving
}

/** Applies [RefreshRatePolicy] edge-triggered: [apply] runs only when the wanted state changes, so on and off each happen once. */
class RefreshRateToggle(private val apply: (Boolean) -> Unit) {
    var on = false; private set

    fun update(fullscreen: Boolean, gpuCanvas: Boolean, superSaving: Boolean) {
        val want = RefreshRatePolicy.wantMax(fullscreen, gpuCanvas, superSaving)
        if (want != on) { on = want; apply(want) }
    }

    /** Always reverts (leaving the app, a page change, Super Saving). */
    fun release() = update(false, false, false)
}

package com.diegonmarcos.cloudlib.web

/**
 * The engine behind an embedded web view (#1418, #1371). SYSTEM_WEBVIEW is the only engine linked today.
 *
 * GECKO is declared, not linked: GeckoView (org.mozilla.geckoview, MPL-2.0, roughly 50-80 MB per ABI from Mozilla's
 * maven) gets WebGPU only through the `dom.webgpu.enabled` pref, which Firefox for Android still ships off (behind a
 * flag; Mozilla plans its Android work for 2026), and it is a different View with its own session, delegates and
 * permission API, so it cannot sit behind [CloudWeb.configure]. [available] turns true only when an app links
 * GeckoView itself; until then every profile resolves to SYSTEM_WEBVIEW and a world without WebGPU shows
 * [GpuCaps.reason] and offers [CloudWeb.openInChrome].
 */
enum class WebEngine {
    SYSTEM_WEBVIEW,
    GECKO;

    val available: Boolean
        get() = this == SYSTEM_WEBVIEW ||
            runCatching { Class.forName("org.mozilla.geckoview.GeckoRuntime") }.isSuccess

    companion object {
        /** The engine a profile asks for. GPU worlds and the Browser are the candidates for GECKO once linked. */
        @Suppress("UNUSED_PARAMETER")
        fun preferred(profile: WebProfile): WebEngine = SYSTEM_WEBVIEW

        fun resolve(profile: WebProfile): WebEngine = preferred(profile).takeIf { it.available } ?: SYSTEM_WEBVIEW
    }
}

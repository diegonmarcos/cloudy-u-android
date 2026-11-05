package com.diegonmarcos.cloudlib.web

/**
 * What it takes to get WebGPU (and the GPU paths around it) into the System WebView, and why it is still missing: pure,
 * so the JVM suite holds every rule (#1430 follow-up). Verified against chromium main:
 *  - androidx.webkit (WebViewFeature) and WebSettings have no WebGPU switch.
 *  - WebView reads `/data/local/tmp/webview-command-line` ONLY when the OS itself is debuggable
 *    (`CommandLineUtil.initCommandLine`: `if (AndroidInfo.isDebugAndroid())`, i.e. a userdebug/eng image); a production
 *    phone ignores the file, whoever writes it, the shell broker included.
 *  - The WebView DevTools "Flags" UI only offers `ProductionSupportedFlagList`, which has no `enable-unsafe-webgpu` and
 *    no `--ignore-gpu-blocklist`; so no flag path reaches WebGPU on a production Android build.
 *  - GeckoView ships `dom.webgpu.enabled` on only for Windows and Apple-silicon macOS (Nightly elsewhere).
 */
object WebGpuPlan {
    const val COMMAND_LINE_FILE = "/data/local/tmp/webview-command-line"

    /** The switches a debuggable-OS WebView honours for the GPU paths; the leading `_` is the program name slot. */
    val FLAGS: List<String> = listOf(
        "--enable-unsafe-webgpu",
        "--ignore-gpu-blocklist",
        "--enable-gpu-rasterization",
        "--enable-zero-copy",
        "--use-angle=vulkan",
        "--enable-features=Vulkan,WebGPUService,CanvasOopRasterization",
    )

    fun fileContent(flags: List<String> = FLAGS): String = "_ " + flags.joinToString(" ")

    /** The shell commands the broker runs (shell uid). The content holds no quote, so single quotes carry it safely. */
    fun applyCommand(flags: List<String> = FLAGS): String =
        "echo '${fileContent(flags)}' > $COMMAND_LINE_FILE && chmod 644 $COMMAND_LINE_FILE"

    fun revertCommand(): String = "rm -f $COMMAND_LINE_FILE"

    /** Whether the written file can matter at all on this device. */
    fun flagFileHonoured(osDebuggable: Boolean): Boolean = osDebuggable

    enum class Status { READY, NO_API, BLOCKED_OR_FLAG_IGNORED, FLAG_APPLIED_NO_ADAPTER, COMPAT_ONLY }

    data class Verdict(val status: Status, val text: String)

    /** The exact status for the Configs row: [fileWritten] = the flags file exists on the device. */
    fun diagnose(caps: GpuCaps, osDebuggable: Boolean, fileWritten: Boolean): Verdict = when {
        caps.webgpuUsable -> Verdict(Status.READY, "WebGPU adapter: ${caps.adapterInfo ?: "available"}")
        !caps.webgpu -> Verdict(Status.NO_API, "This WebView build does not expose navigator.gpu.")
        caps.compatAdapter -> Verdict(Status.COMPAT_ONLY, "Only compatibility mode (OpenGL ES) is available.")
        fileWritten && osDebuggable -> Verdict(
            Status.FLAG_APPLIED_NO_ADAPTER,
            "Flags are written and this OS honours them: restart the app (the WebView renderer) and re-check.",
        )
        else -> Verdict(
            Status.BLOCKED_OR_FLAG_IGNORED,
            if (osDebuggable) "navigator.gpu has no adapter: the GPU${caps.renderer?.let { " ($it)" } ?: ""} is on Chromium's blocklist; apply the flags."
            else "navigator.gpu has no adapter: the GPU${caps.renderer?.let { " ($it)" } ?: ""} is not on Chromium's WebGPU " +
                "allowlist, and this production Android ignores the WebView flags file, so no app or broker setting can change it.",
        )
    }
}

/** Where a world opens, and why: pure. */
enum class WorldRoute { IN_APP, CLOUD_BROWSER }

/** The per-site choice, persisted: AUTO follows the capability. */
enum class SiteGpuChoice {
    AUTO, IN_APP, CLOUD_BROWSER;

    companion object {
        fun parse(s: String?): SiteGpuChoice = entries.firstOrNull { it.name == s } ?: AUTO
    }
}

object WorldRouting {
    /**
     * [needsWebGpu] = the world declares a WebGPU stack. An explicit choice wins; AUTO routes a WebGPU world to
     * Cloud-Browser only once the probe has measured that this view has no adapter (an unmeasured probe stays in-app).
     */
    fun route(choice: SiteGpuChoice, needsWebGpu: Boolean, caps: GpuCaps?): WorldRoute = when (choice) {
        SiteGpuChoice.IN_APP -> WorldRoute.IN_APP
        SiteGpuChoice.CLOUD_BROWSER -> WorldRoute.CLOUD_BROWSER
        SiteGpuChoice.AUTO -> if (needsWebGpu && caps != null && !caps.webgpuUsable) WorldRoute.CLOUD_BROWSER else WorldRoute.IN_APP
    }
}

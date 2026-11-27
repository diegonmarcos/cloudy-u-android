package com.diegonmarcos.cloudme

import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import java.io.File

/**
 * #1980 WebView DevTools for the emulator walk only: the walk evaluates `[...document.images]` counts inside the page's WebView over the
 * webview_devtools_remote socket. Armed only when the build is debuggable AND the marker file /data/local/tmp/cloudme-webview-debug exists
 * (adb shell touch, shell uid only); a phone never has that file, so no user-facing build opens DevTools.
 */
object WebDebug1980 {
    const val MARKER = "/data/local/tmp/cloudme-webview-debug"

    fun requested(debuggable: Boolean, markerExists: Boolean): Boolean = debuggable && markerExists

    fun armIfRequested(ctx: Context) {
        val debuggable = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (requested(debuggable, runCatching { File(MARKER).exists() }.getOrDefault(false))) runCatching { WebView.setWebContentsDebuggingEnabled(true) }
    }
}

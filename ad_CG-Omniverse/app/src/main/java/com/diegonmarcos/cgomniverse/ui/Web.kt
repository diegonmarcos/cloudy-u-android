package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import com.diegonmarcos.cloudlib.web.CloudWeb

/** The app's two kinds of page: the bundled Avatar scene and a world from the Store. Built by libs:web-core (WEB_WORLD). */
object Web {
    /** The bundled Avatar scene, served by CloudWeb.assetLoader so its ES modules load (a file:// page may not import modules). */
    val AVATAR_URL: String = CloudWeb.assetUrl("avatar/index.html")

    /** Worlds that need WebGPU (Android WebView may not expose it) open in Chrome, which ships it on Android 12+. */
    fun openInBrowser(ctx: Context, url: String) {
        CloudWeb.openInChrome(ctx, url)
    }

    /** Per-world desktop mode (desktop UA + viewport), remembered on the device. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun desktop(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("desktop.$worldId", false)
    fun setDesktop(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("desktop.$worldId", on).apply()

    /** Galaxy's open tabs and the selected one ([com.diegonmarcos.cgomniverse.Tabs.encode]); null until the first save. */
    fun tabs(ctx: Context): String? = prefs(ctx).getString("galaxy.tabs", null)
    fun setTabs(ctx: Context, encoded: String) = prefs(ctx).edit().putString("galaxy.tabs", encoded).apply()
}

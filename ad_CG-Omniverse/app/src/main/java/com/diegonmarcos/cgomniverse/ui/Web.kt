package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.SiteGpuChoice

/** The app's two kinds of page: the bundled Avatar scene and a world from the Store. Built by libs:web-core (WEB_WORLD). */
object Web {
    /** The bundled Avatar scene, served by CloudWeb.assetLoader so its ES modules load (a file:// page may not import modules). */
    val AVATAR_URL: String = CloudWeb.assetUrl("avatar/index.html")

    /** Links open in Cloud-Browser, the fleet's one browser; never an external app. */
    fun openInBrowser(ctx: Context, url: String) {
        CloudWeb.openInCloudBrowser(ctx, url)
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

    /** Galaxy's open tabs and the selected one ([com.diegonmarcos.cgomniverse.Tabs.encode]); null until the first save. */
    fun tabs(ctx: Context): String? = prefs(ctx).getString("galaxy.tabs", null)
    fun setTabs(ctx: Context, encoded: String) = prefs(ctx).edit().putString("galaxy.tabs", encoded).apply()
}

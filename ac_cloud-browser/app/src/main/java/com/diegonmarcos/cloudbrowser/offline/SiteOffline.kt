package com.diegonmarcos.cloudbrowser.offline

import android.app.Activity
import android.content.Context
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.cloudlib.web.MirrorScope
import com.diegonmarcos.cloudlib.web.PinnedSites
import com.diegonmarcos.cloudlib.web.SiteMirrorRunner
import com.diegonmarcos.cloudlib.web.SiteMirrorStore
import com.diegonmarcos.superapp.browser.BrowserHostHooks

/**
 * #1518 Cloud-Browser's own offline copy (the web-core SiteCache, the same one Galaxy uses): a pinned site is served
 * cache-first and never expires, only Refresh refetches; the sites pinned from the start are build.json::pin_offline.
 * The page menu pins (pin_site) and downloads the whole site (download_site); the rules are web-core's, JVM-tested.
 */
object SiteOffline {
    private val declared: List<String> by lazy { BuildConfig.PIN_OFFLINE.split(',').filter { it.isNotBlank() } }
    @Volatile private var memo: List<String>? = null

    fun pinned(ctx: Context): List<String> = memo ?: SiteMirrorStore.pinned(ctx, declared).also { memo = it }

    fun setPinned(ctx: Context, list: List<String>) { memo = list; SiteMirrorStore.setPinned(ctx, list) }

    /** Wires the shared host to the offline copy: serving, the two menu actions and the fact behind the pin toggle. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        BrowserHostHooks.intercept = { req -> SiteMirrorStore.intercept(app, pinned(app), req) }
        BrowserHostHooks.fact = { id, url -> id == "site_pinned" && PinnedSites.isPinned(pinned(app), PinnedSites.keyOf(url)) }
        BrowserHostHooks.action = { id, url ->
            val key = PinnedSites.keyOf(url)
            val start = MirrorScope.origin(url)?.let { "$it/" } ?: url
            when (id) {
                "pin_site" ->
                    if (PinnedSites.isPinned(pinned(app), key)) {
                        setPinned(app, PinnedSites.unpin(pinned(app), key)); "Unpinned $key (the copy stays until deleted)"
                    } else {
                        setPinned(app, PinnedSites.pin(pinned(app), key))
                        // unattended: waits for the charger and Wi-Fi, no polling
                        SiteMirrorRunner.of(app).download(start, userTap = false)
                        "Pinned $key: served from its offline copy; the full copy downloads on the charger and Wi-Fi"
                    }
                "download_site" -> {
                    setPinned(app, PinnedSites.pin(pinned(app), key))
                    SiteMirrorRunner.of(app).download(start, userTap = true)
                    "Downloading $key (progress: Domains > Files Cached)"
                }
                else -> null
            }
        }
    }

    /** The default pins that hold no copy yet get their full download queued, unattended (charger and Wi-Fi). */
    fun seedDeclared(ctx: Context) {
        val app = ctx.applicationContext
        val cache = SiteMirrorStore.cache(app)
        Thread {
            declared.map { PinnedSites.keyOf(it) }.firstOrNull { cache.stats(it).files == 0 && PinnedSites.isPinned(pinned(app), it) }
                ?.let { SiteMirrorRunner.of(app).download("https://$it/", userTap = false) }
        }.start()
    }
}

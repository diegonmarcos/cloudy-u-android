package com.diegonmarcos.superapp.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * THE one place this process asks PackageManager for an app icon.
 *
 * WHY (2026-09-19 crash loop): on this Samsung, getApplicationIcon routes
 * through Knox — the ANR trace's main thread sat in
 * android.sec.enterprise.ApplicationPolicy.getApplicationIconFromDb, a binder
 * call that had degraded to SECONDS per icon. Every surface that fetched
 * icons per-render on the main thread (the music island refreshes on every
 * playback callback; the Home shade re-renders per ntfy poll; the apps grid's
 * curated fallbacks) multiplied that into 30-second startup wedges, an
 * "Input dispatching timed out" loop, and an unusable launcher.
 *
 * Contract: at most ONE PackageManager icon call per package per process.
 * [cached] is a map read; [load] pays the PM price on first sight and caches
 * (call it off-main where the call path allows); [into] never blocks — cache
 * hit paints now, a miss paints later from the cache thread, tag-guarded so
 * recycled views never show a stale package's icon. Cached drawables are
 * handed out via constantState.newDrawable(): one instance across many
 * ImageViews would share bounds/state.
 */
object AppIconCache {

    private val cache = ConcurrentHashMap<String, Drawable>()
    private val misses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-icon-cache").apply { isDaemon = true }
    }

    fun cached(pkg: String): Drawable? = cache[pkg]

    /** #1649 How many icons the cache holds (X-Ray App, UI engine). */
    fun size(): Int = cache.size

    fun knownMissing(pkg: String): Boolean = pkg in misses

    /** Resolve + cache. The FIRST call per package pays the (Knox-slow)
     *  PackageManager price — prefer off-main; every later call is a map
     *  read. Null = the package has no icon here, remembered so it is never
     *  re-probed. */
    fun load(ctx: Context, pkg: String): Drawable? {
        cache[pkg]?.let { return it }
        if (pkg in misses) return null
        val d = com.diegonmarcos.superapp.xray.EngineProfiler.time("ui", "icon") {
            runCatching { ctx.applicationContext.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
        if (d == null) {
            misses.add(pkg)
            return null
        }
        cache[pkg] = d
        return d
    }

    /** Cache-hit paints now; a miss loads on the cache thread and swaps in on
     *  the view's UI thread. Never blocks the caller — this is the ONLY form
     *  main-thread render paths may use. */
    fun into(view: ImageView, pkg: String) {
        view.tag = pkg
        cached(pkg)?.let {
            view.setImageDrawable(fresh(view, it))
            return
        }
        io.execute {
            val d = load(view.context, pkg) ?: return@execute
            view.post { if (view.tag == pkg) view.setImageDrawable(fresh(view, d)) }
        }
    }

    private fun fresh(view: View, d: Drawable): Drawable =
        d.constantState?.newDrawable(view.resources) ?: d
}

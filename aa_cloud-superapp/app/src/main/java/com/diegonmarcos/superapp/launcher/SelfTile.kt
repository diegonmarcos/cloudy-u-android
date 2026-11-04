package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.ContextWrapper

/**
 * The SuperApp's OWN tile (Cloud ▸ Apps, Phone folders, search) never starts an activity.
 *
 * Firing the app's launcher intent from inside the home screen starts [com.diegonmarcos.superapp.MainActivity], the
 * app door: its own task, [com.diegonmarcos.superapp.ShellActivity.isDefaultLauncher] false, so it opened Home wearing
 * Android's system status bar (launcher chrome is the HOME door's alone). The tile means "open the SuperApp's
 * settings", so it is routed in-process to Configs in the host that is already running, which keeps its launcher chrome.
 */
object SelfTile {
    /** Where the self tile lands: the Configs section. */
    const val TARGET = "section:config"

    /** The route for [pkg], or null when it is another app. */
    fun routeFor(ownPackage: String, pkg: String): String? = if (pkg == ownPackage) TARGET else null

    /** True = [pkg] was our own package and the running host was sent to Configs; false = caller launches it normally. */
    fun open(ctx: Context, pkg: String): Boolean {
        val route = routeFor(ctx.packageName, pkg) ?: return false
        var c: Context? = ctx
        while (c != null) {
            if (c is TileGridFragment.TileClickListener) { c.onTileClicked(route); return true }
            c = (c as? ContextWrapper)?.baseContext
        }
        return false
    }
}

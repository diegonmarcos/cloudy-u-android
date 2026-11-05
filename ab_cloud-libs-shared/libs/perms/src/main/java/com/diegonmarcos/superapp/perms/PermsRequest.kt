package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

/**
 * #1519 how a runtime request is shaped and routed, the one place (PermsRequestTest). Android 12+ (API 31) ignores or
 * downgrades ACCESS_FINE_LOCATION asked on its own: it must travel WITH ACCESS_COARSE_LOCATION in one request, also when the
 * user earlier picked "Approximate" and now upgrades. After a permanent denial the dialog is a silent no-op, so the route is
 * the app's settings page.
 */
object PermsRequest {
    const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    const val COMBINE_SDK = 31

    /** [perms] as one system request: fine location always comes with coarse on API 31+. Order kept, no duplicates. */
    fun expand(perms: Collection<String>, sdk: Int = Build.VERSION.SDK_INT): List<String> {
        val out = perms.filter { it.isNotEmpty() }.distinct().toMutableList()
        if (sdk >= COMBINE_SDK && FINE in out && COARSE !in out) out.add(out.indexOf(FINE) + 1, COARSE)
        return out
    }

    /** Denied and the dialog is gone for good: asked before, and Android no longer offers a rationale. */
    fun permanentlyDenied(granted: Boolean, asked: Boolean, rationale: Boolean): Boolean = !granted && asked && !rationale

    /** Where a tap on a row leads. */
    enum class Route { DIALOG, SETTINGS, NONE }

    fun route(e: PermEntry, state: PermState): Route = when {
        state == PermState.GRANTED || state == PermState.NOT_APPLICABLE -> Route.NONE
        e.kind == PermKind.RUNTIME && state == PermState.DENIED && e.autoRequest -> Route.DIALOG
        else -> Route.SETTINGS
    }

    /** The entries of a batch that the dialog can still answer (denied, not refused for good). */
    fun dialogable(entries: List<PermEntry>, status: (PermEntry) -> PermStatus): List<PermEntry> =
        entries.filter { route(it, status(it).state) == Route.DIALOG }

    /**
     * Asks [entries]: the combined system dialog for those it can still answer ([launch] gets the expanded request), else
     * the settings page of the first permanently denied one. Returns the route taken.
     */
    fun ask(ctx: Context, entries: List<PermEntry>, activity: Activity? = findActivity(ctx), launch: (Array<String>) -> Unit): Route {
        val vars = Perms.vars(ctx)
        val decl = Perms.declared(ctx)
        val status = { e: PermEntry -> PermStatusReader.status(ctx, e, activity, vars, decl) }
        val dialog = dialogable(entries, status)
        val perms = expand(dialog.map { it.permission }, Build.VERSION.SDK_INT)
        if (perms.isNotEmpty()) {
            PermAskLog.markAsked(ctx, perms)
            PermAskLog.markAskedIds(ctx, dialog.map { it.id } + entries.filter { it.permission in perms }.map { it.id })
            launch(perms.toTypedArray())
            return Route.DIALOG
        }
        val page = entries.firstOrNull { route(it, status(it).state) == Route.SETTINGS } ?: return Route.NONE
        PermsSettings.open(ctx, page)
        return Route.SETTINGS
    }

    fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}

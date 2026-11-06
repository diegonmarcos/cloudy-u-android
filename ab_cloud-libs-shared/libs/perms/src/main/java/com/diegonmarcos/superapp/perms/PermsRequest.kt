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
    fun requestVia(ctx: Context, entries: List<PermEntry>, activity: Activity? = findActivity(ctx), launch: (Array<String>) -> Unit): Route {
        val vars = Perms.vars(ctx)
        val decl = Perms.declared(ctx)
        val status = { e: PermEntry -> PermStatusReader.status(ctx, e, activity, vars, decl) }
        val dialog = dialogable(entries, status)
        val perms = expand(dialog.map { it.permission }, Build.VERSION.SDK_INT)
        if (perms.isNotEmpty()) {
            PermAskLog.markAsked(ctx, perms)
            PermAskLog.markAskedIds(ctx, dialog.map { it.id } + entries.filter { it.permission in perms }.map { it.id })
            markLaunch()
            launch(perms.toTypedArray())
            return Route.DIALOG
        }
        val page = entries.firstOrNull { route(it, status(it).state) == Route.SETTINGS } ?: return Route.NONE
        PermsSettings.open(ctx, page)
        return Route.SETTINGS
    }

    // ── #1546 the request decision logic, pure (PermsRequestFlowTest) ──

    /**
     * The state of a runtime permission from what Android reports. Fine location on API 31+ is an UPGRADE while approximate
     * is granted (or coarse still has a rationale): Android shows the "Change to precise" dialog for a combined request, so
     * the row stays DENIED and its Grant asks again. Before #1546 the "asked + no rationale" heuristic read that state as a
     * permanent denial (an old fine-only request, which Android 12+ drops without a dialog, had poisoned the ask log), so
     * Grant opened App info instead of the upgrade dialog and precise location could never be granted from the page.
     */
    fun runtimeState(
        perm: String, granted: Boolean, asked: Boolean, rationale: Boolean, autoRequest: Boolean,
        sdk: Int = Build.VERSION.SDK_INT, coarseGranted: Boolean = false, coarseRationale: Boolean = false,
    ): PermStatus = when {
        granted -> PermStatus(PermState.GRANTED)
        !asked -> PermStatus(PermState.DENIED, "not asked yet")
        perm == FINE && sdk >= COMBINE_SDK && coarseGranted ->
            PermStatus(PermState.DENIED, "approximate only: Grant asks Android to change it to precise")
        rationale || (perm == FINE && sdk >= COMBINE_SDK && coarseRationale) -> PermStatus(PermState.DENIED, "denied once: Android will ask again")
        !autoRequest -> PermStatus(PermState.DENIED, "granted from its settings page")
        else -> PermStatus(PermState.PERMANENTLY_DENIED, "don't ask again: only the settings page can grant it")
    }

    /** A result faster than this came back without a dialog on screen: Android refused silently (permanent denial). */
    const val SILENT_MS = 450L

    /** What follows a request's result. */
    sealed interface Next {
        object Done : Next
        /** Denied with no dialog shown: the app's settings page is the only way left. */
        data class AppSettings(val permission: String) : Next
        /** Location granted but the system Location switch is off: Wi-Fi scans and the SSID stay hidden. */
        object LocationSettings : Next
    }

    /**
     * After [result] (permission -> granted) came back [elapsedMs] after the launch: a denial with no rationale that came back
     * faster than [SILENT_MS] means no dialog was shown, so the app's settings page opens (a denial the user just tapped
     * stays on the page); a location grant with the system Location switch off opens the Location settings.
     */
    fun after(result: Map<String, Boolean>, rationale: (String) -> Boolean, elapsedMs: Long, locationOn: Boolean): Next {
        val denied = result.filter { !it.value }.keys
        val silent = denied.filter { !rationale(it) }
        if (silent.isNotEmpty() && elapsedMs in 0 until SILENT_MS) return Next.AppSettings(if (FINE in silent) FINE else silent.first())
        if ((result[FINE] == true || result[COARSE] == true) && !locationOn) return Next.LocationSettings
        return Next.Done
    }

    @Volatile private var launchedAt = 0L

    /** Every launch of a request goes through here so [handleResult] can tell a silent refusal from a tapped one. */
    fun markLaunch() { launchedAt = android.os.SystemClock.elapsedRealtime() }

    /** The hosts' result callback: acts on [after] (settings page / Location switch). Returns what it did. */
    fun handleResult(ctx: Context, result: Map<String, Boolean>, activity: Activity? = findActivity(ctx)): Next {
        if (result.isEmpty()) return Next.Done
        val elapsed = if (launchedAt == 0L) -1L else android.os.SystemClock.elapsedRealtime() - launchedAt
        val next = after(result, { p -> activity?.shouldShowRequestPermissionRationale(p) ?: false }, elapsed, PermsWifiScan.locationOn(ctx))
        val cat = Perms.catalogue(ctx).entries
        when (next) {
            is Next.AppSettings -> cat.firstOrNull { it.permission == next.permission }?.let { PermsSettings.open(ctx, it) }
            Next.LocationSettings -> cat.firstOrNull { it.id == "location_services" }?.let { PermsSettings.open(ctx, it) }
            Next.Done -> {}
        }
        return next
    }

    fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}

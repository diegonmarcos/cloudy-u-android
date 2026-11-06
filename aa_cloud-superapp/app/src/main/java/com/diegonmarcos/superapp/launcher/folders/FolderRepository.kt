package com.diegonmarcos.superapp.launcher.folders

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.SystemClock
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1553 Runtime home of the Folders page: it reads the two served tables for a scope, enumerates that scope's apps,
 * merges the user's overrides and resolves everything into sections via the pure [FolderTables].
 *
 * Datasets (served by the fleet-data client, Cloud Store provider, tier 3-4 — NOT baked):
 *   • `launcher-folders/<scope>`              → TABLE 1 (folders)
 *   • `launcher-folder-assignments/<scope>`   → TABLE 2 (assignments)
 * When a dataset is not served yet, its table is empty and every app falls to Others — the page still works.
 *
 * Enumeration is on demand: [snapshot] recomputes when the cache for a scope is older than [STALE_MS] (the same
 * lazy 60 s rule the Store uses), and [refresh] forces a re-scan now so a newly installed/added app appears in Others.
 * No polling.
 */
class FolderRepository(
    private val ctx: Context,
    private val overrides: FolderOverrideStore = FolderOverrideStore(ctx),
    /** App enumeration per scope — injectable so the resolution can be tested without a device. */
    private val enumerate: (Context, FolderScope) -> List<FolderApp> = ::enumerateApps,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Cached(val apps: List<FolderApp>, val at: Long)
    private val cache = HashMap<FolderScope, Cached>()

    /**
     * Table 1 for [scope] from the served dataset. When nothing is served yet, the Phone scope falls back to the
     * launcher's own folder taxonomy (PhoneFolders) so the page shows the SAME folder ids the grid groups by — a
     * reassignment there is therefore meaningful to the launcher grid at once (see [FolderGridBridge]). Cloud has no
     * such baked taxonomy, so it stays empty (everything in Others) until the dataset is served.
     */
    fun folders(scope: FolderScope): List<FolderDef> {
        val served = FleetData.get(ctx, "launcher-folders/${scope.key}", parse = FolderTables::parseFolders).orEmpty()
        if (served.isNotEmpty() || scope != FolderScope.PHONE) return served
        return runCatching {
            com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig().mapIndexed { i, f ->
                FolderDef(section = "Apps", id = f.id, label = f.label, icon = "", order = i)
            }
        }.getOrDefault(emptyList())
    }

    /** Table 2 for [scope] from the served dataset (empty when unserved). */
    fun assignments(scope: FolderScope): List<FolderAssignment> =
        FleetData.get(ctx, "launcher-folder-assignments/${scope.key}", parse = FolderTables::parseAssignments).orEmpty()

    /** The resolved sections for [scope], recomputing the app enumeration only when stale (or when [force]). */
    fun snapshot(scope: FolderScope, force: Boolean = false): List<ResolvedSection> {
        val c = cache[scope]
        val apps = if (!force && c != null && now() - c.at < STALE_MS) c.apps
                   else enumerate(ctx, scope).also { cache[scope] = Cached(it, now()) }
        return FolderTables.resolve(folders(scope), assignments(scope), overrides.all(scope), apps)
    }

    /** The on-demand Refresh action: re-enumerate now, so new apps surface in Others. */
    fun refresh(scope: FolderScope): List<ResolvedSection> = snapshot(scope, force = true)

    /** Record a user edit (an override that wins over the served assignment), then return the fresh resolution. */
    fun assign(scope: FolderScope, pkg: String, folderId: String): List<ResolvedSection> {
        overrides.set(scope, pkg, folderId)
        return snapshot(scope)
    }

    companion object {
        /** Reuse the Store's lazy-refresh window: a scope rescans at most this often unless Refresh is tapped. */
        const val STALE_MS = 60_000L

        /** Phone scope = the installed launchable apps; Cloud scope = the constellation fleet's apps. */
        fun enumerateApps(ctx: Context, scope: FolderScope): List<FolderApp> = when (scope) {
            FolderScope.PHONE -> runCatching {
                val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.getActivityList(null, Process.myUserHandle())
                    .map { FolderApp(it.componentName.packageName, it.label?.toString().orEmpty()) }
                    .distinctBy { it.pkg }
            }.getOrDefault(emptyList())
            FolderScope.CLOUD -> runCatching {
                Fleet.parse(com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64)
                    .filter { it.kind == "app" && it.pkg.isNotBlank() }
                    .map { FolderApp(it.pkg, it.label.ifBlank { it.id }) }
                    .distinctBy { it.pkg }
            }.getOrDefault(emptyList())
        }
    }
}

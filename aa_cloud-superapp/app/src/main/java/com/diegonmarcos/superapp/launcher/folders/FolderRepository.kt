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
    private val enumerate: (Context, FolderScope) -> List<FolderApp> = { c, s -> enumerateApps(c, s) },
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    /** The launcher's baked classification (PhoneAppClassifier over ui.phone_folders): app -> folder id. Injectable for tests. */
    private val classify: (Context, List<FolderApp>) -> Map<String, String> = { c, a -> classifyPhoneApps(c, a) },
) {
    private class Cached(val apps: List<FolderApp>, val at: Long)
    private val cache = HashMap<FolderScope, Cached>()

    /**
     * Table 1 for [scope] from the served dataset. When nothing is served yet, the Phone scope falls back to the
     * launcher's own folder taxonomy (PhoneFolders) so the page shows the SAME folder ids the grid groups by — a
     * reassignment there is therefore meaningful to the launcher grid at once (see [FolderGridBridge]). Cloud has no
     * such baked taxonomy, so it stays empty (everything in Others) until the dataset is served.
     */
    fun folders(scope: FolderScope): List<FolderDef> = overrides.savedFolders(scope) ?: servedFolders(scope)

    /** Table 1 as served (plus the Phone taxonomy fallback), ignoring the user's saved edits. */
    fun servedFolders(scope: FolderScope): List<FolderDef> {
        val served = FleetData.get(ctx, "launcher-folders/${scope.key}", parse = FolderTables::parseFolders).orEmpty()
        if (served.isNotEmpty() || scope != FolderScope.PHONE) return served
        return runCatching {
            com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig().mapIndexed { i, f ->
                FolderDef(section = "Apps", id = f.id, label = f.label, icon = "", order = i)
            }
        }.getOrDefault(emptyList())
    }

    /** Table 2 for [scope]: the user's saved table, else the served dataset (empty when unserved). */
    fun assignments(scope: FolderScope): List<FolderAssignment> = overrides.savedAssignments(scope) ?: servedAssignments(scope)

    /** Table 2 as served, ignoring the user's saved edits. */
    fun servedAssignments(scope: FolderScope): List<FolderAssignment> =
        FleetData.get(ctx, "launcher-folder-assignments/${scope.key}", parse = FolderTables::parseAssignments).orEmpty()

    /** The resolved sections for [scope], recomputing the app enumeration only when stale (or when [force]). */
    fun snapshot(scope: FolderScope, force: Boolean = false): List<ResolvedSection> =
        FolderTables.resolve(folders(scope), assignments(scope), overrides.all(scope), apps(scope, force))

    private fun apps(scope: FolderScope, force: Boolean): List<FolderApp> {
        val c = cache[scope]
        return if (!force && c != null && now() - c.at < STALE_MS) c.apps
               else enumerate(ctx, scope).also { cache[scope] = Cached(it, now()) }
    }

    /**
     * DECLARATIVE Table 2: the served rows, plus - Phone scope only - the baked declarative classification
     * (PhoneAppClassifier keywords / ui.external_apps) for every app the served rows do not name. The served dataset lists
     * only fleet apps and is empty when no provider serves it; without this every third-party app (and every app on an
     * unserved device) fell to "No folder" although the launcher files it by keyword. A classified id that is not a folder
     * of the declared Table 1 is dropped, so only genuinely undeclared apps stay unassigned.
     */
    fun declaredAssignments(scope: FolderScope, apps: List<FolderApp>): List<FolderAssignment> {
        val served = servedAssignments(scope)
        if (scope != FolderScope.PHONE) return served
        val have = served.map { it.pkg }.toSet()
        val ids = servedFolders(scope).map { it.id }.toSet()
        val extra = runCatching { classify(ctx, apps.filter { it.pkg !in have }) }.getOrDefault(emptyMap())
        return served + extra.filter { (_, fid) -> fid in ids }.map { (pkg, fid) -> FolderAssignment(pkg, fid, 999) }
    }

    /** The declarative model: straight from the SoT (served folders + declared assignments), no saved edits. */
    fun declarativeModel(scope: FolderScope, force: Boolean = false): FolderEditModel {
        val apps = apps(scope, force)
        return FolderEditModel.build(servedFolders(scope), declaredAssignments(scope, apps), apps)
    }

    /**
     * The run-time model: what the device applies - the saved tables if any, else the declarative ones, then the per-app
     * moves the launcher bridge reads - plus every enumerated app.
     */
    fun editorModel(scope: FolderScope, force: Boolean = false): FolderEditModel {
        val apps = apps(scope, force)
        val folders = folders(scope)
        val base = overrides.savedAssignments(scope) ?: declaredAssignments(scope, apps)
        val moves = overrides.all(scope).filter { (_, fid) -> folders.any { it.id == fid } }
        val merged = base.filter { it.pkg !in moves } + moves.map { (pkg, fid) -> FolderAssignment(pkg, fid, base.firstOrNull { it.pkg == pkg }?.order ?: 999) }
        return FolderEditModel.build(folders, merged, apps)
    }

    /**
     * Save the editor's tables as the on-device override for [scope]. Also writes the per-app moves the launcher grid
     * reads through [FolderGridBridge] (apps whose folder differs from the declared one), replacing earlier moves.
     */
    fun save(scope: FolderScope, model: FolderEditModel) {
        val declared = declaredAssignments(scope, apps(scope, false)).associate { it.pkg to it.folderId }
        overrides.resetScope(scope)
        overrides.saveTables(scope, model.folders, model.toAssignments())
        for (a in model.toAssignments()) if (a.folderId != OTHERS_FOLDER_ID && a.folderId != declared[a.pkg]) overrides.set(scope, a.pkg, a.folderId)
    }

    /** Revert [scope] to the served defaults. */
    fun reset(scope: FolderScope) = overrides.resetScope(scope)

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

        /** Baked classification of [apps] into the launcher's own phone folders (keywords + metadata): app -> folder id. */
        fun classifyPhoneApps(ctx: Context, apps: List<FolderApp>): Map<String, String> {
            val folders = com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig()
            if (folders.isEmpty() || apps.isEmpty()) return emptyMap()
            val me = Process.myUserHandle()
            val grouped = com.diegonmarcos.superapp.apps.PhoneAppClassifier.groupByFolder(
                apps.map { com.diegonmarcos.superapp.apps.PhoneApp(it.pkg, null, it.label, null, me) }, folders, ctx)
            return grouped.flatMap { (fid, list) -> list.map { it.packageName to fid } }.toMap()
        }

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

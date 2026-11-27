package com.diegonmarcos.superapp.apps

import android.content.Context
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1936 Phone ▸ Apps lists THIRD-PARTY phone apps only: no app of the Cloud constellation is displayed in any section of
 * the page (Quickmarks folders and tiles, Active Apps, Last Apps, All Apps, Smart Folders, the search results). THE ONE
 * PREDICATE ([isFleet] over [packages]) and THE ONE PLACE it is applied: [SuitePhonePage] takes every list from here
 * ([groups] for the curated Quickmarks, [apps] for the enumerated ones, [exclude] for the enumerators, [SuperappSearchIndex]
 * for search), so a new section cannot forget it.
 *
 * Fleet membership = the declared fleet: ui.external_apps (hub / alt / install packages and every declared fork such as
 * cld.termux and cld.termux.nix), the constellation manifest (every app AND every lib APK, with its alt id), this app's own
 * package, and the debug / dev variants of any of them. Everything removed from the page is reachable in Cloud ▸ Apps
 * (GroupedTilesFragment lists exactly [forContext], the same set this page excludes, so the two grids partition one list).
 *
 * Root cause (before #1936): the Quickmarks folders PREPENDED the Cloud app to every folder and drew it as the folder face
 * (#1764 / #1864 withCloudApp, icon_pkg), the curated entries and the user's own Quickmark adds were never filtered, the
 * enumerated sections used a set built from ui.external_apps alone (no lib APKs, no variants), and the search scope
 * ([PhoneAppsFragment.snapshot]) was not filtered at all.
 */
internal object PhonePageFleet {
    private val VARIANT_SUFFIXES = listOf(".debug", ".dev", ".beta", ".staging")

    /** The declared fleet as package ids. Pure: the callers hand in what they read ([forContext] reads it on a device). */
    fun packages(ownPackage: String, declaredApps: Collection<String>, manifest: Collection<String>): Set<String> =
        (declaredApps + manifest + ownPackage).filter { it.isNotBlank() }.let { base -> (base + base.flatMap { b -> VARIANT_SUFFIXES.map { b + it } }).toSet() }

    fun forContext(ctx: Context): Set<String> = packages(
        ctx.packageName,
        Sections.constellationPackages(ctx.packageName),
        runCatching { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).flatMap { listOfNotNull(it.pkg, it.altId) } }.getOrDefault(emptyList()),
    )

    /** THE predicate. */
    fun isFleet(pkg: String, fleet: Set<String>): Boolean =
        pkg in fleet || VARIANT_SUFFIXES.any { pkg.endsWith(it) && pkg.removeSuffix(it) in fleet }

    /** What an enumerator (All Apps, Smart Folders, Active, Last) must leave out. */
    fun exclude(fleet: Set<String>): Set<String> = fleet

    fun apps(pkgs: List<String>, fleet: Set<String>): List<String> = pkgs.filterNot { isFleet(it, fleet) }

    /**
     * The curated Quickmarks of the page: every fleet entry leaves its group tiles and its folders, in the declared order of
     * what stays. A folder left with no phone app is not rendered (unless it opens an All Apps folder, which holds its own
     * list); a group left with nothing is dropped by the page as before.
     */
    fun groups(groups: List<SuitePhoneAppsFragment.Group>, fleet: Set<String>): List<SuitePhoneAppsFragment.Group> =
        groups.map { g ->
            g.copy(
                entries = g.entries.filterNot { isFleet(it.pkg, fleet) },
                folders = g.folders.map { f -> f.copy(entries = f.entries.filterNot { isFleet(it.pkg, fleet) }) }
                    .filter { f -> f.entries.isNotEmpty() || f.opensFolder.isNotEmpty() || f.links.isNotEmpty() },
            )
        }

    /** The guard's verdict: the fleet packages found in a list a section rendered (empty = clean). */
    fun leaks(rendered: List<String>, fleet: Set<String>): List<String> = rendered.filter { isFleet(it, fleet) }

    /** Every package a section of the page renders, for the guard test: the curated groups' tiles and folder members. */
    fun renderedPackages(groups: List<SuitePhoneAppsFragment.Group>): List<String> =
        groups.flatMap { g -> g.entries.map { it.pkg } + g.folders.flatMap { f -> f.entries.map { it.pkg } } }
}

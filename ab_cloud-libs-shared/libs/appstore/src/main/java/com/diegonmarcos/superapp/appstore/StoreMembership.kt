package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1681 THE ONE answer to "which Store tab does this app belong to": Cloud lists the fleet (every package the fleet manifest
 * names, alt ids included, plus the manifest's own package-less reference rows), Phone lists everything else installed or declared
 * on the phone. Both pages ask this object and nothing else, so an app is on exactly one of them: [split] is disjoint and complete
 * by construction, and StoreMembershipTest holds it against the real fleet manifest.
 */
object StoreMembership {
    enum class Tab { CLOUD, PHONE }

    /** Every package id the manifest names: (package, alt id) per row; blank ids are ignored. */
    fun fleetPackagesOf(rows: List<Pair<String, String?>>): Set<String> =
        rows.flatMap { (pkg, alt) -> listOfNotNull(pkg.trim().ifEmpty { null }, alt?.trim()?.ifEmpty { null }) }.toSet()

    fun fleetPackages(fleet: List<Fleet.App>): Set<String> = fleetPackagesOf(fleet.map { it.pkg to it.altId })

    /** The tab [pkg] is drawn on. A blank package is a fleet reference row (no APK), which only the Cloud page draws. */
    fun tabOf(pkg: String, fleet: Set<String>): Tab = if (pkg.isBlank() || pkg in fleet) Tab.CLOUD else Tab.PHONE

    fun isCloud(pkg: String, fleet: Set<String>): Boolean = tabOf(pkg, fleet) == Tab.CLOUD
    fun isPhone(pkg: String, fleet: Set<String>): Boolean = tabOf(pkg, fleet) == Tab.PHONE

    data class Split(val cloud: Set<String>, val phone: Set<String>)

    /** Every package of [packages] on exactly one side. */
    fun split(packages: Collection<String>, fleet: Set<String>): Split {
        val (cloud, phone) = packages.toSet().partition { isCloud(it, fleet) }
        return Split(cloud.toSet(), phone.toSet())
    }

    /** The Cloud page's rows: only the ones this classifier files on Cloud. */
    fun cloudRows(rows: List<Fleet.App>, fleet: Set<String>): List<Fleet.App> = rows.filter { isCloud(it.pkg, fleet) }

    /** The Phone page's candidates (package to label): only the ones this classifier files on Phone. */
    fun <V> phoneOnly(candidates: Map<String, V>, fleet: Set<String>): Map<String, V> = candidates.filterKeys { isPhone(it, fleet) }
}

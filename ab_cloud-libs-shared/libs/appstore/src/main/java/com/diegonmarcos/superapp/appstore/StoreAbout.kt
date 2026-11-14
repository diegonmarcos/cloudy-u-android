package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1736 "About" hand-off: another fleet app (the SuperApp's Cloud app menu) opens Cloud Store with
 * `com.diegonmarcos.cloudstore.OPEN` + extra [EXTRA] = a fleet package. The Store's MainActivity parks it here and
 * Store ▸ Cloud opens that app's About sheet ([ApkDetailSheet]) on its next resume. Only a package that is in the
 * Store's own baked fleet list opens anything; an unknown package is dropped, never an error.
 */
object StoreAbout {
    const val EXTRA = "about_pkg"

    @Volatile var pending: String? = null

    /** Park [pkg] (blank = nothing). */
    fun park(pkg: String?) { if (!pkg.isNullOrBlank()) pending = pkg }

    fun take(): String? = pending.also { pending = null }

    /** Pure: the fleet row [pkg] names (by package or stock alt id), or null. */
    fun appFor(fleet: List<Fleet.App>, pkg: String?): Fleet.App? =
        if (pkg.isNullOrBlank()) null else fleet.firstOrNull { it.pkg == pkg || it.altId == pkg }
}

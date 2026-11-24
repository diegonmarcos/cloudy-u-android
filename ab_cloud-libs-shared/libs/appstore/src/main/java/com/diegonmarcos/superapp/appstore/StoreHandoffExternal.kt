package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.install.StoreHandoff

/**
 * #1765 Cloud Store installs a NON-fleet package a fleet app handed it (the Cloud Store Games page's F-Droid Install).
 * It is [ExternalInstall] on the declared ladder of [SourceResolver] (F-Droid: signed index, sha256-verified), and only
 * a package whose ladder has a direct rung is installed: a Play-only app is refused with the reason, never fetched.
 * Registered by Cloud Store's MainActivity; runs inside [com.diegonmarcos.superapp.updater.install.StoreHandoffWorker].
 */
object StoreHandoffExternal {
    fun register() { StoreHandoff.external = StoreHandoff.ExternalInstaller { ctx, pkg -> install(ctx, pkg) } }

    /** Pure: may a hand-off install [pkg] (a well-formed package id, with at least one direct rung). */
    fun acceptable(pkg: String, directRungs: Int): Boolean = PKG.matches(pkg) && directRungs > 0

    internal fun install(ctx: Context, pkg: String): String? {
        if (!PKG.matches(pkg)) return "not a package id"
        val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(ctx))
        val app = SourceResolver.resolve(cfg, pkg)
        if (!acceptable(pkg, app.direct.size)) return "${app.label} publishes no APK outside Google Play"
        UpdateProgress.beginDownload()
        return ExternalInstall.run(ctx, cfg, app)
    }

    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
}

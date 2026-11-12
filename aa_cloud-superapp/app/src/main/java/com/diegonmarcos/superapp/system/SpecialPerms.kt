package com.diegonmarcos.superapp.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The two special permissions the SuperApp needs to do its job and Android
 * never shows a runtime dialog for:
 *
 *  - INSTALL_UNKNOWN: REQUEST_INSTALL_PACKAGES is only half the story; the user
 *    must also allow this app as an install source. Without it every fleet
 *    install (the `extapp:` launcher, the store) stops at a system dead end.
 *  - HOME: the SuperApp is a launcher; being the default home app is a role.
 *
 * The mapping from raw platform facts to granted / not granted is [granted],
 * a pure function a JVM test drives; [probe] reads the facts live, every call,
 * so a row or the first-run step re-checks on resume instead of trusting a flag.
 */
object SpecialPerms {

    enum class Kind { INSTALL_UNKNOWN, HOME }

    /** Raw platform facts. [roleHeld] is null below API 29 (no RoleManager);
     *  [homeResolvesTo] is the package CATEGORY_HOME resolves to (older path). */
    data class Facts(
        val sdk: Int,
        val ourPackage: String,
        val canRequestInstalls: Boolean,
        val roleHeld: Boolean?,
        val homeResolvesTo: String?,
    )

    fun granted(kind: Kind, f: Facts): Boolean = when (kind) {
        // Below O the per-source toggle does not exist: the manifest permission is the grant.
        Kind.INSTALL_UNKNOWN -> f.sdk < 26 || f.canRequestInstalls
        Kind.HOME -> if (f.sdk >= 29 && f.roleHeld != null) f.roleHeld else f.homeResolvesTo == f.ourPackage
    }

    fun label(kind: Kind): String = when (kind) {
        Kind.INSTALL_UNKNOWN -> "Install unknown apps"
        Kind.HOME -> "Default home app (launcher)"
    }

    fun stateText(kind: Kind, granted: Boolean): String = when (kind) {
        Kind.INSTALL_UNKNOWN -> if (granted) "✓ Allowed — SuperApp can install fleet apps" else "◯ Not allowed — fleet installs will stop at a system page"
        Kind.HOME -> if (granted) "✓ SuperApp is the default home app" else "◯ Another app is the home app"
    }

    fun facts(ctx: Context): Facts {
        val sdk = Build.VERSION.SDK_INT
        val canInstall = sdk < 26 || runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        val role = if (sdk >= 29) runCatching {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME))
                rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) else null
        }.getOrNull() else null
        val home = runCatching {
            ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
            )?.activityInfo?.packageName
        }.getOrNull()
        return Facts(sdk, ctx.packageName, canInstall, role, home)
    }

    fun isGranted(ctx: Context, kind: Kind): Boolean = granted(kind, facts(ctx))

    /** Both kinds' state, for a cheap "did anything change" comparison on resume. */
    fun snapshot(ctx: Context): Map<Kind, Boolean> = facts(ctx).let { f -> Kind.values().associateWith { granted(it, f) } }

    const val REQUEST_HOME_ROLE = 0x484f

    /**
     * Open the one place that grants [kind]. Install: the per-app unknown-sources
     * page scoped to this package. Home: the role request dialog on API 29+
     * (startActivityForResult, which the role intent requires), falling back to
     * the system Home settings page.
     */
    fun request(activity: Activity, kind: Kind) {
        when (kind) {
            Kind.INSTALL_UNKNOWN -> {
                // #1515 the settings ladder (scoped page, then the list, then App info) is libs:perms' own
                // (catalogue entry install_unknown); this app never builds that intent itself.
                ScreenLocker.openPermsSettings(activity, "install_unknown")
            }
            Kind.HOME -> {
                val viaRole = Build.VERSION.SDK_INT >= 29 && runCatching {
                    val rm = activity.getSystemService(android.app.role.RoleManager::class.java)
                    if (rm == null || !rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) false
                    else {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(
                            rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
                        true
                    }
                }.getOrDefault(false)
                if (!viaRole && runCatching { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isFailure)
                    runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
    }
}

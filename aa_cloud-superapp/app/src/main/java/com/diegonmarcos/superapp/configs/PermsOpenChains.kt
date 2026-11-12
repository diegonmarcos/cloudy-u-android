package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * #1688 every "Open" link of Configs > Perms as a DECLARED fallback chain: candidates tried in order, each one
 * resolved (PackageManager) BEFORE it is launched, the first that resolves AND starts wins; none ⇒ a reason, never a
 * crash and never a silent no-op. Every chain ends on this app's App info page, which always exists.
 *
 * Root cause of the broken "Default home app" Open: SpecialPerms.request fired RoleManager.createRequestRoleIntent
 * (ROLE_HOME) first and counted the launch as success. On One UI the home role request is not offered to a launcher
 * (the dialog finishes at once with RESULT_CANCELED, also when the role is already held), so nothing appeared and the
 * Settings fallback never ran. The home chain now opens the Home settings page first.
 */
object PermsOpenChains {

    /** One candidate: an action, optionally scoped to this package (package:<pkg> data), or the role request. */
    data class Step(val action: String, val scoped: Boolean = false, val role: String = "") {
        val isRole: Boolean get() = role.isNotEmpty()
    }

    const val ROLE_HOME = "android.app.role.HOME"
    private val APP_INFO = Step(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, scoped = true)

    /** id → chain. Ids are the Open links of the page (PermissionsFragment.buildPageItems). */
    val chains: Map<String, List<Step>> = mapOf(
        "home" to listOf(Step(Settings.ACTION_HOME_SETTINGS), Step(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Step("", role = ROLE_HOME), APP_INFO),
        "default_apps" to listOf(Step(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Step(Settings.ACTION_SETTINGS), APP_INFO),
        "battery_opt" to listOf(Step(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), APP_INFO),
        "usage_access" to listOf(Step(Settings.ACTION_USAGE_ACCESS_SETTINGS), APP_INFO),
        "notification_listener" to listOf(Step(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), APP_INFO),
        "all_files" to listOf(Step(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, scoped = true), Step(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), APP_INFO),
        "overlay" to listOf(Step(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, scoped = true), Step(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), APP_INFO),
        "write_settings" to listOf(Step(Settings.ACTION_MANAGE_WRITE_SETTINGS, scoped = true), Step(Settings.ACTION_MANAGE_WRITE_SETTINGS), APP_INFO),
        "unknown_sources" to listOf(Step(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, scoped = true), Step(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), APP_INFO),
        "battery_saver" to listOf(Step(Settings.ACTION_BATTERY_SAVER_SETTINGS), APP_INFO),
        "app_info" to listOf(APP_INFO),
    )

    fun intent(ctx: Context, s: Step): Intent? = runCatching {
        if (s.isRole) {
            if (android.os.Build.VERSION.SDK_INT < 29) return@runCatching null
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: return@runCatching null
            if (!rm.isRoleAvailable(s.role)) return@runCatching null
            rm.createRequestRoleIntent(s.role)
        } else Intent(s.action).apply { if (s.scoped) data = Uri.fromParts("package", ctx.packageName, null) }
    }.getOrNull()

    data class Opened(val ok: Boolean, val via: String, val reason: String)

    /** Try [id]'s chain; [launch] starts one resolved intent and answers whether it started. */
    fun open(ctx: Context, id: String, launch: (Intent) -> Boolean = { i -> defaultLaunch(ctx, i) }): Opened {
        val chain = chains[id] ?: return Opened(false, "", "no Open chain declared for $id")
        val tried = ArrayList<String>()
        for (s in chain) {
            val name = if (s.isRole) "role request ${s.role}" else s.action + (if (s.scoped) " (this app)" else "")
            val i = intent(ctx, s)
            val resolves = i != null && runCatching { i.resolveActivity(ctx.packageManager) != null }.getOrDefault(false)
            if (!resolves) { tried += "$name: not on this device"; continue }
            if (runCatching { launch(i!!) }.getOrDefault(false)) return Opened(true, name, "")
            tried += "$name: refused to start"
        }
        return Opened(false, "", "No settings page could be opened. " + tried.joinToString("; "))
    }

    private fun defaultLaunch(ctx: Context, i: Intent): Boolean = runCatching {
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    }.getOrDefault(false)
}

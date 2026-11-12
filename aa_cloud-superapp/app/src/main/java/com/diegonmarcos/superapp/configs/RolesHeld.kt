package com.diegonmarcos.superapp.configs

import android.content.Context

/**
 * #1688 Perms > Roles held: every role (dumpsys role), device-admin entry and listener (notification, accessibility,
 * companion) the SuperApp holds, from REAL state, each with the exact release command (Copy command / Run on ADB behind
 * a confirm). Why it exists: a hidden system role kept the SuperApp "essential" in Samsung Maximum power saving even
 * after Home moved to another app.
 *
 * Broker rules (libs:shell-broker shell-allowlist.json): roles-read-1 / roles-read-2 (read-only) and roles-release-self
 * (remove-role-holder / remove-active-admin for com.diegonmarcos.superapp only).
 */
object RolesHeld {

    enum class Kind(val word: String) { ROLE("Role"), DEVICE_ADMIN("Device admin"), NOTIFICATION_LISTENER("Notification listener"),
        ACCESSIBILITY("Accessibility"), COMPANION("Companion device") }

    data class Held(
        val kind: Kind,
        val name: String,
        /** The exact release lines; empty when adb cannot release it ([note] says where). */
        val release: List<String>,
        val note: String = "",
    ) {
        val id: String get() = "${kind.name.lowercase()}:$name"
        fun toggle(): AdbToggle = AdbToggle(id, "${kind.word} $name", on = true, enable = emptyList(), disable = release,
            disableCannot = if (release.isEmpty()) note.ifEmpty { "adb cannot release it" } else "")
    }

    const val READ_ROLES = "dumpsys role"
    private val ROLE = Regex("android\\.app\\.role\\.[A-Z_]+|com\\.[a-z0-9_.]+\\.role\\.[A-Z_]+")

    /**
     * Roles whose holders include [pkg], from `dumpsys role`. Tolerant of the formats Android prints
     * (`name=<role>` then `holders=a,b`, or the role on its own line followed by a holders line).
     */
    fun parseRoles(dump: String?, pkg: String): List<String> {
        if (dump.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        var current: String? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val holders = line.startsWith("holders") || line.startsWith("mHolders")
            if (!holders) ROLE.find(line)?.let { current = it.value }
            if (holders && current != null) {
                val list = line.substringAfter('=', line.substringAfter(':', "")).split(',', ' ', '[', ']', '{', '}').map { it.trim() }
                if (pkg in list) out += current!!
            }
        }
        return out.toList()
    }

    fun releaseRole(role: String, pkg: String) = listOf("cmd role remove-role-holder $role $pkg 0")
    fun releaseAdmin(component: String) = listOf("dpm remove-active-admin --user 0 $component")
    fun releaseListener(component: String) = listOf("cmd notification disallow_listener $component")
    fun releaseAccessibility(enabled: String, pkg: String): List<String> {
        val rest = enabled.split(':').filter { it.isNotBlank() && !it.startsWith("$pkg/") }
        return if (rest.isEmpty()) listOf("settings put secure accessibility_enabled 0", "settings delete secure enabled_accessibility_services")
        else listOf("settings put secure enabled_accessibility_services ${rest.joinToString(":")}")
    }

    /** Pure assembly from the real reads; tested in RolesHeldTest. */
    fun assemble(pkg: String, roleDump: String?, publicRolesHeld: List<String>, admins: List<String>, listeners: List<String>,
                 a11yEnabled: String, companions: List<String>): List<Held> {
        val roles = (parseRoles(roleDump, pkg) + publicRolesHeld).distinct()
        return roles.map { Held(Kind.ROLE, it, releaseRole(it, pkg)) } +
            admins.filter { it.startsWith("$pkg/") }.map { Held(Kind.DEVICE_ADMIN, it, releaseAdmin(it)) } +
            listeners.filter { it.startsWith("$pkg/") }.map { Held(Kind.NOTIFICATION_LISTENER, it, releaseListener(it)) } +
            a11yEnabled.split(':').filter { it.startsWith("$pkg/") }.map { Held(Kind.ACCESSIBILITY, it, releaseAccessibility(a11yEnabled, pkg)) } +
            companions.map { Held(Kind.COMPANION, it, emptyList(), "release it in Settings > Connected devices (the companion association has no safe adb line)") }
    }

    /** Public roles RoleManager can answer without adb (API 29+). */
    private val PUBLIC_ROLES = listOf("android.app.role.HOME", "android.app.role.DIALER", "android.app.role.SMS", "android.app.role.ASSISTANT",
        "android.app.role.BROWSER", "android.app.role.CALL_SCREENING", "android.app.role.CALL_REDIRECTION", "android.app.role.EMERGENCY",
        "android.app.role.NOTES", "android.app.role.SYSTEM_GALLERY", "android.app.role.WALLET")

    /** Blocking (binder + shell); call off the main thread. Never throws. [shell] null ⇒ only the in-process reads. */
    fun read(ctx: Context, shell: ((String) -> String?)?): Pair<List<Held>, String> {
        val pkg = ctx.packageName
        val dump = shell?.let { runCatching { it(READ_ROLES) }.getOrNull() }?.takeIf { !it.startsWith("error:") }
        val public = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            PUBLIC_ROLES.filter { r -> runCatching { rm?.isRoleAvailable(r) == true && rm.isRoleHeld(r) }.getOrDefault(false) }
        }.getOrDefault(emptyList()) else emptyList()
        val admins = runCatching {
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager)?.activeAdmins
                ?.map { it.flattenToShortString().let { s -> if (s.startsWith("$pkg/")) s else it.packageName + "/" + it.className } }
        }.getOrNull().orEmpty()
        val listeners = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().split(':').filter { it.isNotBlank() }
                .map { android.content.ComponentName.unflattenFromString(it)?.flattenToShortString() ?: it }
        }.getOrNull().orEmpty()
        val a11y = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrNull().orEmpty().split(':').map { android.content.ComponentName.unflattenFromString(it)?.flattenToShortString() ?: it }.joinToString(":")
        val companions = runCatching {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? android.companion.CompanionDeviceManager)?.associations.orEmpty()
        }.getOrNull().orEmpty()
        val held = runCatching { assemble(pkg, dump, public, admins, listeners, a11y, companions) }.getOrDefault(emptyList())
        val src = if (dump != null) "dumpsys role + platform reads" else "platform reads only (no ADB channel: hidden roles cannot be listed; open ADB Shell and press Connect)"
        return held to src
    }
}

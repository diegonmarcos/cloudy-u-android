package com.diegonmarcos.superapp.media

import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.BudsRule

/**
 * #1966 the Media card's buds-app slot. The setting ([KEY], default declared in build.json, never here) names the app; the declared
 * device map ([BudsRule]) overrides it while matching earbuds are connected. Pure: names and the installed check are arguments.
 */
object BudsApp {
    const val KEY = "buds_app"

    /** The packages to try, in order: the rule of the first connected device it matches, else the setting's [setting] package. */
    fun candidates(setting: String, connected: List<String>, rules: List<BudsRule>): List<String> {
        for (n in connected) rules.firstOrNull { r -> r.prefixes.any { n.startsWith(it, ignoreCase = true) } }?.let { return it.packages }
        return listOfNotNull(setting.takeIf { it.isNotBlank() })
    }

    /** The package the button opens, or null when the app is not installed (no button). */
    fun resolve(setting: String, connected: List<String>, rules: List<BudsRule>, installed: (String) -> Boolean): String? =
        candidates(setting, connected, rules).firstOrNull(installed)

    /** Why there is no button, for the setting; null when the button shows. */
    fun reason(setting: String, connected: List<String>, rules: List<BudsRule>, installed: (String) -> Boolean): String? {
        if (resolve(setting, connected, rules, installed) != null) return null
        val c = candidates(setting, connected, rules)
        if (c.isEmpty()) return "No buds app is set"
        val name = rules.firstOrNull { r -> c == r.packages }?.label ?: c.first()
        return "$name is not installed on this phone, so the Media card shows no buds button"
    }
}

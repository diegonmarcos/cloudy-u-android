package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations

/** One terminal link of Overview > TERMINALS: [packageName] is "" when the fleet registry does not carry the app. */
data class TerminalLink(val id: String, val label: String, val icon: String, val fleet: String, val packageName: String, val installed: Boolean)

/**
 * Overview > TERMINALS: one icon link per declared terminal (build.json ui.manage.overview.terminals: Cloud Terminal Termux and
 * Cloud Terminal Nix). The package is read from the fleet app registry (the constellation manifest baked into
 * [Declarations.constellation]), never typed here; [installed] is the phone's answer for that package.
 */
object OverviewTerminals {
    fun resolve(declared: List<Declarations.ManageSource>, registry: List<Declarations.ConstellationAppDecl>, isInstalled: (String) -> Boolean): List<TerminalLink> =
        declared.map { d ->
            val pkg = registry.firstOrNull { it.fleet == d.fleet }?.packageName.orEmpty()
            TerminalLink(d.id, d.label, d.icon, d.fleet, pkg, pkg.isNotBlank() && runCatching { isInstalled(pkg) }.getOrDefault(false))
        }
}

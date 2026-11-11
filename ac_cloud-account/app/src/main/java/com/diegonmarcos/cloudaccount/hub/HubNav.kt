package com.diegonmarcos.cloudaccount.hub

/**
 * The five bottom-nav sections (build.json::ui, #1081, #1482): Vault (IDs, PayCards, Secrets, Autocomplete; private), Config (the
 * Fleet Config files: Android, Desktop, C3, Repos; public, no secrets), Connect (Status, Authentication providers), Devices
 * (Mesh, Peer Control, Backup, Files, Working, Diff) and Activity; plus `configs`, the app's own General | Perms | About menu
 * opened from the top bar, which is not in the bottom nav. One place per thing. A page inside a strip ("sites" under "autocomplete") is addressed by its own id;
 * NavSection.page resolves it at any depth.
 *
 * Pages in libs:account still open each other with the ids of the old islands (`open("setup", "perms")`,
 * `go("setup", "adb-shell")`, `open("account", "connect")`), and three earlier layouts (the five islands, the four of
 * 2026-10-13 and the four of 2026-10-14) had ids of their own; [resolve] maps every old (section, page) to where that
 * page lives now, so no link, OPEN intent or bookmark breaks and no lib file changes.
 */
object HubNav {
    /** #1625 The bottom nav, left to right: the contacts sections plus the app's own Configs. */
    val ORDER = listOf("groups", "business", "all", "people", "configs")
    const val DEFAULT = "all"

    /**
     * Every section id a link may name: the contacts bottom nav plus the identity/vault sections, which stay declared
     * and reachable by deep link (#1625 moved them off the bottom bar, not out of the app). resolve() passes any of
     * these through unchanged; LEGACY still maps the old island ids onto them.
     */
    val KNOWN = ORDER + listOf("vault", "config", "connect", "devices", "activity")

    /** Old (section, page) -> new (section, page). "" = the section's first page. */
    val LEGACY: Map<Pair<String, String>, Pair<String, String>> = mapOf(
        // the five islands of the first redesign (Account, Profiles, Setup, Secrets, Settings)
        ("account" to "") to ("devices" to "me"),
        ("account" to "profile") to ("devices" to "me"),
        ("account" to "connect") to ("connect" to "signin"),
        ("account" to "autofill") to ("vault" to "personal"),
        ("account" to "sites") to ("vault" to "sites"),
        ("account" to "snippets") to ("vault" to "snippets"),
        ("account" to "import") to ("vault" to "import"),
        ("setup" to "") to ("config" to "runbook"),
        ("setup" to "runbook") to ("config" to "runbook"),
        ("setup" to "apps") to ("config" to "cloud-apps"),
        ("setup" to "configs") to ("connect" to "push"),
        ("setup" to "perms") to ("connect" to "perms"),
        ("setup" to "adb-shell") to ("connect" to "adb-shell"),
        ("settings" to "") to ("connect" to "settings"),
        // Profiles, Secrets (four islands of 2026-10-13/14)
        ("profiles" to "") to ("connect" to "apps"),
        ("profiles" to "apps") to ("connect" to "apps"),
        ("profiles" to "autofill") to ("vault" to "personal"),
        ("profiles" to "personal") to ("vault" to "personal"),
        ("profiles" to "sites") to ("vault" to "sites"),
        ("profiles" to "snippets") to ("vault" to "snippets"),
        ("profiles" to "phone") to ("devices" to "files"),
        ("profiles" to "devices") to ("devices" to "files"),
        ("profiles" to "working") to ("devices" to "working"),
        ("profiles" to "diff") to ("devices" to "diff"),
        ("secrets" to "") to ("vault" to "providers"),
        ("secrets" to "providers") to ("vault" to "providers"),
        ("secrets" to "connections") to ("devices" to "bundle"),
        ("secrets" to "bundle") to ("devices" to "bundle"),
        ("secrets" to "secrets") to ("vault" to "keys"),
        ("secrets" to "grants") to ("vault" to "grants"),
        // Home
        ("home" to "") to ("devices" to "me"),
        ("home" to "home") to ("devices" to "me"),
        ("home" to "account") to ("devices" to "me"),
        ("home" to "connections") to ("connect" to "connections"),
        ("home" to "connect") to ("connect" to "signin"),
        ("home" to "import") to ("vault" to "import"),
        // Configs: the id is kept; its pages moved under Android / Connect
        ("configs" to "settings") to ("connect" to "settings"),
        ("configs" to "setup") to ("config" to "runbook"),
        ("configs" to "runbook") to ("config" to "runbook"),
        ("configs" to "apps") to ("config" to "cloud-apps"),
        ("configs" to "configs") to ("connect" to "push"),
        ("configs" to "perms") to ("connect" to "perms"),
        ("configs" to "adb-shell") to ("connect" to "adb-shell"),
        // #1438 Configs > Android is Cloud Apps / Phone Apps / Runbook; Push, Perms and ADB Shell moved to Connect > Setup;
        // Personal Info > Wallet became Pay Cards.
        ("configs" to "push") to ("connect" to "push"),
        ("personal" to "wallet") to ("vault" to "cards"),
        // #1482 the layout of 2026-11-05: Personal Info and Data merged into Vault, Devices out of Data, the fleet config
        // files out of Configs into Config (Configs is now the app's own General | Perms | About).
        ("personal" to "") to ("vault" to "ids"),
        ("personal" to "ids") to ("vault" to "ids"),
        ("personal" to "cards") to ("vault" to "cards"),
        ("personal" to "entries") to ("vault" to "entries"),
        ("personal" to "me") to ("devices" to "me"),
        ("personal" to "others") to ("vault" to "entries"),
        ("personal" to "autocomplete") to ("vault" to "personal"),
        ("personal" to "personal") to ("vault" to "personal"),
        ("personal" to "sites") to ("vault" to "sites"),
        ("personal" to "snippets") to ("vault" to "snippets"),
        ("data" to "") to ("vault" to "providers"),
        ("data" to "databases") to ("devices" to "databases"),
        ("data" to "backups") to ("devices" to "databases"),
        ("data" to "mesh") to ("devices" to "mesh"),
        ("data" to "devices") to ("devices" to "mesh"),
        ("data" to "files") to ("devices" to "files"),
        ("data" to "working") to ("devices" to "working"),
        ("data" to "diff") to ("devices" to "diff"),
        ("data" to "bundle") to ("devices" to "bundle"),
        ("configs" to "android") to ("config" to "cloud-apps"),
        ("configs" to "cloud-apps") to ("config" to "cloud-apps"),
        ("configs" to "phone-apps") to ("config" to "phone-apps"),
        ("configs" to "desktop") to ("config" to "desktop"),
        ("configs" to "c3") to ("config" to "infra"),
        ("configs" to "infra") to ("config" to "infra"),
        ("configs" to "containers") to ("config" to "containers"),
        ("configs" to "repos") to ("config" to "repos"),
        ("data" to "keys") to ("vault" to "keys"),
        ("data" to "tokens") to ("vault" to "tokens"),
        ("data" to "grants") to ("vault" to "grants"),
        ("data" to "import") to ("vault" to "import"),
        ("data" to "providers") to ("vault" to "providers"),
        ("data" to "secrets") to ("vault" to "providers"),
    )

    /**
     * Where a link with [section]/[page] goes: a pair [LEGACY] names is translated (the section may be a current
     * one whose page was renamed), any other current section passes through, an unknown section opens the default.
     */
    fun resolve(section: String, page: String?): Pair<String, String?> {
        val exact = LEGACY[section to page.orEmpty()]
        if (exact != null) return exact.first to exact.second.ifEmpty { null }
        if (section in KNOWN) return section to page
        val hit = LEGACY[section to ""] ?: return DEFAULT to null
        return hit.first to hit.second.ifEmpty { null }
    }
}

package com.diegonmarcos.cloudaccount.hub

/**
 * The five sections (build.json::ui, #1081, #1438): Personal Info (IDs, Pay Cards, Others, Autocomplete), Configs (Android, Desktop, C3;
 * public, no secrets), Data (Secrets, Backups, Devices; private), Connect (Status, Authentication providers) and
 * Activity. One place per thing. A page inside a strip ("sites" under "autocomplete") is addressed by its own id;
 * NavSection.page resolves it at any depth.
 *
 * Pages in libs:account still open each other with the ids of the old islands (`open("setup", "perms")`,
 * `go("setup", "adb-shell")`, `open("account", "connect")`), and three earlier layouts (the five islands, the four of
 * 2026-10-13 and the four of 2026-10-14) had ids of their own; [resolve] maps every old (section, page) to where that
 * page lives now, so no link, OPEN intent or bookmark breaks and no lib file changes.
 */
object HubNav {
    /** The bottom nav, left to right, as the owner asked for it. */
    val ORDER = listOf("personal", "configs", "data", "connect", "activity")
    const val DEFAULT = "personal"

    /** Old (section, page) -> new (section, page). "" = the section's first page. */
    val LEGACY: Map<Pair<String, String>, Pair<String, String>> = mapOf(
        // the five islands of the first redesign (Account, Profiles, Setup, Secrets, Settings)
        ("account" to "") to ("personal" to "me"),
        ("account" to "profile") to ("personal" to "me"),
        ("account" to "connect") to ("connect" to "signin"),
        ("account" to "autofill") to ("personal" to "personal"),
        ("account" to "sites") to ("personal" to "sites"),
        ("account" to "snippets") to ("personal" to "snippets"),
        ("account" to "import") to ("data" to "import"),
        ("setup" to "") to ("configs" to "runbook"),
        ("setup" to "runbook") to ("configs" to "runbook"),
        ("setup" to "apps") to ("configs" to "cloud-apps"),
        ("setup" to "configs") to ("connect" to "push"),
        ("setup" to "perms") to ("connect" to "perms"),
        ("setup" to "adb-shell") to ("connect" to "adb-shell"),
        ("settings" to "") to ("connect" to "settings"),
        // Profiles, Secrets (four islands of 2026-10-13/14)
        ("profiles" to "") to ("connect" to "apps"),
        ("profiles" to "apps") to ("connect" to "apps"),
        ("profiles" to "autofill") to ("personal" to "personal"),
        ("profiles" to "personal") to ("personal" to "personal"),
        ("profiles" to "sites") to ("personal" to "sites"),
        ("profiles" to "snippets") to ("personal" to "snippets"),
        ("profiles" to "phone") to ("data" to "files"),
        ("profiles" to "devices") to ("data" to "files"),
        ("profiles" to "working") to ("data" to "working"),
        ("profiles" to "diff") to ("data" to "diff"),
        ("secrets" to "") to ("data" to "providers"),
        ("secrets" to "providers") to ("data" to "providers"),
        ("secrets" to "connections") to ("data" to "bundle"),
        ("secrets" to "bundle") to ("data" to "bundle"),
        ("secrets" to "secrets") to ("data" to "keys"),
        ("secrets" to "grants") to ("data" to "grants"),
        // Home
        ("home" to "") to ("personal" to "me"),
        ("home" to "home") to ("personal" to "me"),
        ("home" to "account") to ("personal" to "me"),
        ("home" to "connections") to ("connect" to "connections"),
        ("home" to "connect") to ("connect" to "signin"),
        ("home" to "import") to ("data" to "import"),
        // Configs: the id is kept; its pages moved under Android / Connect
        ("configs" to "") to ("configs" to "runbook"),
        ("configs" to "settings") to ("connect" to "settings"),
        ("configs" to "setup") to ("configs" to "runbook"),
        ("configs" to "runbook") to ("configs" to "runbook"),
        ("configs" to "apps") to ("configs" to "cloud-apps"),
        ("configs" to "configs") to ("connect" to "push"),
        ("configs" to "perms") to ("connect" to "perms"),
        ("configs" to "adb-shell") to ("connect" to "adb-shell"),
        // #1438 Configs > Android is Cloud Apps / Phone Apps / Runbook; Push, Perms and ADB Shell moved to Connect > Setup;
        // Personal Info > Wallet became Pay Cards.
        ("configs" to "push") to ("connect" to "push"),
        ("personal" to "wallet") to ("personal" to "cards"),
    )

    /**
     * Where a link with [section]/[page] goes: a pair [LEGACY] names is translated (the section may be a current
     * one whose page was renamed), any other current section passes through, an unknown section opens the default.
     */
    fun resolve(section: String, page: String?): Pair<String, String?> {
        val exact = LEGACY[section to page.orEmpty()]
        if (exact != null) return exact.first to exact.second.ifEmpty { null }
        if (section in ORDER) return section to page
        val hit = LEGACY[section to ""] ?: return DEFAULT to null
        return hit.first to hit.second.ifEmpty { null }
    }
}

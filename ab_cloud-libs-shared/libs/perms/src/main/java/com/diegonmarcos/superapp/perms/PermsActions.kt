package com.diegonmarcos.superapp.perms

/**
 * #1403 the Perms page's filter chips and actions row, as pure rules (PermsActionsTest): which chips a row answers to,
 * which shell commands grant a row over the adb channel (every kind, not only the adb-only ones), and what a grant-all
 * plan holds. PermsToolbar draws them; the host app hands in its adb channel ([PermsAdb]).
 */
enum class StateFilter(val label: String) { MISSING("Missing"), DENIED("Denied"), ALLOWED("Allowed") }

/** GRANT = a runtime dialog, TAP = special access behind a Settings toggle, ADB = only adb (or an app-op) can set it. */
enum class KindFilter(val label: String) { GRANT("Grant"), TAP("Tap"), ADB("Adb"), HELPER("Helper apps"), HEALTH("Health"), INSTALL("At install") }

/** The host's adb channel: [channel] runs commands, [connect] opens the app's own adb pairing / connect. */
class PermsAdb(val channel: PermsGrant.GrantChannel, val connect: () -> Unit)

object PermsActions {

    /** Missing = denied or never answered; Denied = refused for good; Allowed = granted. */
    fun states(i: PermItem): Set<StateFilter> = when (i.state) {
        PermState.GRANTED -> setOf(StateFilter.ALLOWED)
        PermState.PERMANENTLY_DENIED -> setOf(StateFilter.MISSING, StateFilter.DENIED)
        PermState.DENIED -> setOf(StateFilter.MISSING)
        else -> emptySet()
    }

    fun kind(i: PermItem): KindFilter = when (i.kind) {
        PermItemKind.HELPER -> KindFilter.HELPER
        PermItemKind.HEALTH -> KindFilter.HEALTH
        PermItemKind.INSTALL -> KindFilter.INSTALL
        else -> when (i.permKind) {
            PermKind.RUNTIME -> KindFilter.GRANT
            PermKind.APPOPS, PermKind.ADB_ONLY -> KindFilter.ADB
            PermKind.SPECIAL -> KindFilter.TAP
            null -> if (i.kind == PermItemKind.RUNTIME) KindFilter.GRANT else KindFilter.TAP
        }
    }

    /** The kind chips worth drawing: only kinds the page has. */
    fun kindsPresent(items: List<PermItem>): List<KindFilter> = KindFilter.values().filter { k -> items.any { kind(it) == k } }

    /** An empty set = no filter on that axis; chips of one axis are OR-ed, the two axes AND-ed. */
    fun filter(items: List<PermItem>, states: Set<StateFilter>, kinds: Set<KindFilter>): List<PermItem> =
        items.filter { i -> (states.isEmpty() || states.any { it in states(i) }) && (kinds.isEmpty() || kind(i) in kinds) }

    sealed interface Grant {
        data class Shell(val commands: List<String>) : Grant
        data class Cannot(val reason: String) : Grant
    }

    /** Catalogue entry id -> the app-op `appops set` allows for it (special access the shell may set). */
    internal val OPS: Map<String, String> = mapOf(
        "all_files" to "MANAGE_EXTERNAL_STORAGE",
        "overlay" to "SYSTEM_ALERT_WINDOW",
        "write_settings" to "WRITE_SETTINGS",
        "install_unknown" to "REQUEST_INSTALL_PACKAGES",
        "exact_alarm" to "SCHEDULE_EXACT_ALARM",
        "usage_access" to "GET_USAGE_STATS",
        "vpn" to "ACTIVATE_VPN",
        "background_run" to "RUN_ANY_IN_BACKGROUND",
        "restricted_settings" to "ACCESS_RESTRICTED_SETTINGS",
    )

    /**
     * The commands that grant [i] on [pkg] (a helper row grants its helper), or why adb cannot. [components] = entry id ->
     * the app's own component for the bind-service entries (notification_listener, accessibility, device_admin);
     * [enabledA11y] = the current secure enabled_accessibility_services, kept when ours is added.
     */
    fun commands(pkg: String, i: PermItem, components: Map<String, String> = emptyMap(), enabledA11y: String = ""): Grant {
        val entry = i.id.substringAfter(':')
        val target = if (i.kind == PermItemKind.HELPER) i.id.substringBefore(':') else pkg
        fun sh(vararg c: String) = Grant.Shell(c.toList())
        fun component(what: String, use: (String) -> Grant) = components[entry]?.let(use) ?: Grant.Cannot("the app declares no $what")
        return when {
            i.kind == PermItemKind.INSTALL -> Grant.Cannot("granted at install")
            i.kind == PermItemKind.HEALTH || entry == "health_connect" -> Grant.Cannot("Health Connect grants only on its own screen")
            entry == "usb_host" -> Grant.Cannot("USB access is asked per device when it is plugged in")
            entry in OPS -> sh(PermsShell.appop(target, OPS.getValue(entry)))
            entry == "battery_opt" -> sh("cmd deviceidle whitelist +$target")
            entry == "location_services" -> sh("cmd location set-location-enabled true")
            entry == "dnd" || entry == "dnd_access" -> sh("cmd notification allow_dnd $target")
            entry == "standby_active" -> sh("am set-standby-bucket $target active")
            entry == "keep_unused" -> sh(PermsShell.appop(target, "AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "ignore"))
            entry == "notification_listener" -> component("notification listener service") { sh("cmd notification allow_listener $it") }
            entry == "accessibility" -> component("accessibility service") { c ->
                val list = (enabledA11y.split(':').filter { it.isNotBlank() && it != c } + c).joinToString(":")
                sh("settings put secure enabled_accessibility_services $list", "settings put secure accessibility_enabled 1")
            }
            entry == "device_admin" -> component("device admin receiver") { sh("dpm set-active-admin --user 0 $it") }
            i.permKind == PermKind.RUNTIME || i.permKind == PermKind.ADB_ONLY || i.kind == PermItemKind.RUNTIME ->
                if (i.permission.isNotEmpty()) sh(PermsShell.grant(target, i.permission)) else Grant.Cannot("no permission name declared")
            i.appop.isNotEmpty() -> sh(PermsShell.appop(target, i.appop.removePrefix("android:").uppercase()))
            else -> Grant.Cannot("adb cannot set ${i.title}; only its Settings page can")
        }
    }

    /** ADB_MISSING = rows not granted and not refused for good; DENIED_ALSO adds the ones refused for good. */
    enum class Mode { ADB_MISSING, DENIED_ALSO }

    fun targets(items: List<PermItem>, mode: Mode): List<PermItem> = items.filter { i ->
        i.used && i.kind != PermItemKind.INSTALL && when (mode) {
            Mode.ADB_MISSING -> i.state == PermState.DENIED
            Mode.DENIED_ALSO -> i.missing
        }
    }

    data class Plan(val steps: List<Pair<PermItem, List<String>>>, val cannot: List<Pair<PermItem, String>>)

    fun plan(pkg: String, items: List<PermItem>, mode: Mode, components: Map<String, String> = emptyMap(), enabledA11y: String = ""): Plan {
        val steps = ArrayList<Pair<PermItem, List<String>>>(); val cannot = ArrayList<Pair<PermItem, String>>()
        for (i in targets(items, mode)) when (val g = commands(pkg, i, components, enabledA11y)) {
            is Grant.Shell -> steps += i to g.commands
            is Grant.Cannot -> cannot += i to g.reason
        }
        return Plan(steps, cannot)
    }

    /**
     * #1601 "Reset all to default": the inverse of [commands] — the shell lines that put [i] back to its default state
     * (runtime / development permission revoked, app-op back to default, battery / DND / listener / accessibility / device
     * admin undone), or why the shell cannot reset it. Mirrors [commands] one-for-one so the page resets exactly what it grants.
     */
    fun resetCommands(pkg: String, i: PermItem, components: Map<String, String> = emptyMap(), enabledA11y: String = ""): Grant {
        val entry = i.id.substringAfter(':')
        val target = if (i.kind == PermItemKind.HELPER) i.id.substringBefore(':') else pkg
        fun sh(vararg c: String) = Grant.Shell(c.toList())
        fun component(what: String, use: (String) -> Grant) = components[entry]?.let(use) ?: Grant.Cannot("the app declares no $what")
        return when {
            i.kind == PermItemKind.INSTALL -> Grant.Cannot("granted at install, nothing to reset")
            i.kind == PermItemKind.HEALTH || entry == "health_connect" -> Grant.Cannot("Health Connect resets only on its own screen")
            entry == "usb_host" -> Grant.Cannot("USB access is per device, nothing to reset")
            entry == "location_services" -> Grant.Cannot("a device-wide switch, not this app's to reset")
            entry == "standby_active" -> Grant.Cannot("the system owns the standby bucket, nothing to reset")
            entry == "keep_unused" -> sh(PermsShell.appop(target, "AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "default"))
            entry in OPS -> sh(PermsShell.appop(target, OPS.getValue(entry), "default"))
            entry == "battery_opt" -> sh("cmd deviceidle whitelist -$target")
            entry == "dnd" || entry == "dnd_access" -> sh("cmd notification disallow_dnd $target")
            entry == "notification_listener" -> component("notification listener service") { sh("cmd notification disallow_listener $it") }
            entry == "accessibility" -> component("accessibility service") { c ->
                val list = enabledA11y.split(':').filter { it.isNotBlank() && it != c }.joinToString(":")
                sh("settings put secure enabled_accessibility_services $list")
            }
            entry == "device_admin" -> component("device admin receiver") { sh("dpm remove-active-admin --user 0 $it") }
            i.permKind == PermKind.RUNTIME || i.permKind == PermKind.ADB_ONLY || i.kind == PermItemKind.RUNTIME ->
                if (i.permission.isNotEmpty()) sh(PermsShell.revoke(target, i.permission)) else Grant.Cannot("no permission name declared")
            i.appop.isNotEmpty() -> sh(PermsShell.appop(target, i.appop.removePrefix("android:").uppercase(), "default"))
            else -> Grant.Cannot("adb cannot reset ${i.title}; only its Settings page can")
        }
    }

    /** #1601 the reset-everything plan: every row the shell can reset (install-time rows skipped), each with its reset commands. */
    fun resetPlan(pkg: String, items: List<PermItem>, components: Map<String, String> = emptyMap(), enabledA11y: String = ""): Plan {
        val steps = ArrayList<Pair<PermItem, List<String>>>(); val cannot = ArrayList<Pair<PermItem, String>>()
        for (i in items.filter { it.kind != PermItemKind.INSTALL }) when (val g = resetCommands(pkg, i, components, enabledA11y)) {
            is Grant.Shell -> steps += i to g.commands
            is Grant.Cannot -> cannot += i to g.reason
        }
        return Plan(steps, cannot)
    }

    /** Copy Adb Grants: one `adb shell` line per command for everything missing, and a comment for what adb cannot set. */
    fun copyText(plan: Plan): String =
        (plan.steps.flatMap { (_, cs) -> cs.map { "adb shell $it" } } + plan.cannot.map { (i, why) -> "# ${i.title}: $why" }).joinToString("\n")

    data class Result(val granted: Int, val failed: List<Pair<String, String>>, val cannot: List<Pair<String, String>>) {
        fun text(): String = "$granted granted" + (if (failed.isEmpty()) "" else ", ${failed.size} failed") +
            (if (cannot.isEmpty()) "" else ", ${cannot.size} adb cannot set") +
            (failed + cannot).joinToString("") { (t, why) -> "\n$t: $why" }
    }

    /** Runs [plan] through [channel]; a row fails on its first failing command. Nothing runs while the channel is down. */
    fun run(plan: Plan, channel: PermsGrant.GrantChannel): Result {
        val cannot = plan.cannot.map { (i, why) -> i.title to why }
        if (!channel.up()) return Result(0, plan.steps.map { (i, _) -> i.title to PermsGrant.DISABLED_REASON }, cannot)
        var ok = 0; val failed = ArrayList<Pair<String, String>>()
        for ((i, cs) in plan.steps) {
            val why = cs.firstNotNullOfOrNull { PermsGrant.failure(channel.exec(it)) }
            if (why == null) ok++ else failed += i.title to why
        }
        return Result(ok, failed, cannot)
    }
}

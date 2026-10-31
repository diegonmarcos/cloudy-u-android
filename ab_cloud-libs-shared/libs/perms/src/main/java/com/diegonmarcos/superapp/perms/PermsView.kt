package com.diegonmarcos.superapp.perms

/**
 * #1372 THE view rules of every Perms UI in the fleet (the Perms section, Cloud Me's Perms page, the first-run sheet and
 * the in-place prompt card), as pure functions over [PermItem]s (PermsViewTest). The composables in PermsUi.kt only draw
 * what these return; an app feeds items from the catalogue ([item], [helperItem]) or from its own declaration (Cloud Me).
 */
enum class PermItemKind { RUNTIME, SPECIAL, HEALTH, HELPER, INSTALL }

/** The one trailing action of a row: the system dialog ([GRANT]) or the exact settings page ([OPEN]). */
enum class PermAction(val word: String) { GRANT("Grant"), OPEN("Open") }

/** The state chip: what it says and its tone (green, amber, red, grey). */
enum class PermChip(val word: String, val tone: Tone) {
    GRANTED("Granted", Tone.OK), MISSING("Missing", Tone.WARN), DENIED("Denied", Tone.BAD),
    NOT_APPLICABLE("Not applicable", Tone.IDLE), UNKNOWN("Check", Tone.IDLE);
    enum class Tone { OK, WARN, BAD, IDLE }
}

/**
 * One row. [reason] is the plain-language line (the catalogue's or the app's `why`); [detail] is what the state reader
 * said (why it does not apply, why it cannot be read). [used] = false puts it under Not used yet. [highlight] = the rows a
 * caller asked for (first in their group).
 */
data class PermItem(
    val id: String,
    val title: String,
    val reason: String,
    val icon: String,
    val kind: PermItemKind,
    val state: PermState,
    val detail: String = "",
    val action: PermAction? = null,
    val used: Boolean = true,
    val highlight: Boolean = false,
) {
    val missing: Boolean get() = state == PermState.DENIED || state == PermState.PERMANENTLY_DENIED
}

object PermsView {
    const val RUNTIME = "Runtime"
    const val SPECIAL = "Special access"
    const val HEALTH = "Health Connect"
    const val HELPERS = "Helper apps"
    const val INSTALL = "Granted at install"
    const val NOT_USED = "Not used yet"
    /** Granted at install folds away when it is longer than this (an app can declare dozens of normal permissions). */
    const val INSTALL_OPEN_MAX = 3

    data class Progress(val granted: Int, val total: Int) {
        val label: String get() = "$granted of $total granted"
        val missing: Int get() = total - granted
        val fraction: Float get() = if (total == 0) 1f else granted.toFloat() / total
    }

    data class Group(val title: String, val items: List<PermItem>, val collapsed: Boolean = false) {
        /** "2 / 3" for a group that counts, the size for a folded one. */
        val count: String get() = if (collapsed) "${items.size}" else "${items.count { it.state == PermState.GRANTED }} / ${items.size}"
    }

    fun chip(state: PermState): PermChip = when (state) {
        PermState.GRANTED -> PermChip.GRANTED
        PermState.DENIED -> PermChip.MISSING
        PermState.PERMANENTLY_DENIED -> PermChip.DENIED
        PermState.NOT_APPLICABLE -> PermChip.NOT_APPLICABLE
        PermState.UNKNOWN -> PermChip.UNKNOWN
    }

    /** The second line: how to fix a permanent denial, why it does not apply, else the reason. */
    fun reasonLine(i: PermItem): String = when {
        i.state == PermState.PERMANENTLY_DENIED -> "Denied twice: allow it in Settings"
        i.state == PermState.NOT_APPLICABLE && i.detail.isNotBlank() -> i.detail
        else -> i.reason.ifBlank { i.detail }
    }

    /** Health Connect as one row: granted when every type is, missing when one is, not applicable without it. */
    fun healthState(items: List<PermItem>): PermState {
        val h = items.filter { it.kind == PermItemKind.HEALTH }
        return when {
            h.isEmpty() || h.all { it.state == PermState.NOT_APPLICABLE } -> PermState.NOT_APPLICABLE
            h.any { it.missing } -> PermState.DENIED
            h.all { it.state == PermState.GRANTED } -> PermState.GRANTED
            else -> PermState.UNKNOWN
        }
    }

    /** "X of N granted" over what Grant all can act on: runtime, special and helper rows, Health Connect counted once. */
    fun progress(items: List<PermItem>): Progress {
        // a missing row counts only when the phone can fix it (an adb-only grant has no action and is not "missing" here)
        val rows = items.filter { (it.kind == PermItemKind.RUNTIME || it.kind == PermItemKind.SPECIAL || it.kind == PermItemKind.HELPER) &&
            (it.state == PermState.GRANTED || (it.missing && it.action != null)) }
        val health = items.filter { it.kind == PermItemKind.HEALTH }
        val hs = healthState(health)
        val h = when {
            hs == PermState.GRANTED -> 1 to 1
            hs == PermState.DENIED && health.any { it.action != null } -> 0 to 1
            else -> 0 to 0
        }
        return Progress(rows.count { it.state == PermState.GRANTED } + h.first, rows.size + h.second)
    }

    /** The page's groups in order, empty ones left out; highlighted rows first inside a group; unused rows folded. */
    fun groups(items: List<PermItem>): List<Group> {
        fun of(kind: PermItemKind) = items.filter { it.used && it.kind == kind }.sortedBy { if (it.highlight) 0 else 1 }
        val install = of(PermItemKind.INSTALL)
        return listOf(
            Group(RUNTIME, of(PermItemKind.RUNTIME)),
            Group(SPECIAL, of(PermItemKind.SPECIAL)),
            Group(HEALTH, of(PermItemKind.HEALTH)),
            Group(HELPERS, of(PermItemKind.HELPER)),
            Group(INSTALL, install, collapsed = install.size > INSTALL_OPEN_MAX),
            Group(NOT_USED, items.filter { !it.used }, collapsed = true),
        ).filter { it.items.isNotEmpty() }
    }

    /** The Health Connect summary row's line: "Reads 3 and writes 2 data types", or why it does not apply. */
    fun healthLine(health: List<PermItem>): String {
        if (healthState(health) == PermState.NOT_APPLICABLE) return health.firstOrNull { it.detail.isNotBlank() }?.detail ?: "Health Connect is not available"
        val reads = health.count { it.title.startsWith("Read") }
        val writes = health.count { it.title.startsWith("Write") }
        return if (reads + writes == health.size) "Reads $reads and writes $writes data types" else "${health.size} data types"
    }

    fun typesToggle(n: Int, open: Boolean): String = if (open) "Hide $n types" else "Show $n types"

    /** #1372 the first-run gate exists only while something it lists is missing: everything granted = no gate at all. */
    fun gateShows(items: List<PermItem>): Boolean = missing(items).isNotEmpty()

    /** What the first-run gate lists: every used missing row, Health Connect as one line. */
    fun missing(items: List<PermItem>): List<PermItem> {
        val rows = items.filter { it.used && it.missing && it.kind != PermItemKind.HEALTH && it.kind != PermItemKind.INSTALL }
        val health = items.filter { it.kind == PermItemKind.HEALTH }
        return if (health.any { it.used && it.missing }) rows + health.first { it.missing }.copy(title = HEALTH, reason = healthLine(health)) else rows
    }

    /** The prompt card's sentence: one name, or a count and the first three names. */
    fun promptText(missing: List<String>): String = when (missing.size) {
        0 -> ""
        1 -> "${missing[0]} is not granted"
        else -> "${missing.size} permissions missing: " + missing.take(3).joinToString(", ") + if (missing.size > 3) " and ${missing.size - 3} more" else ""
    }

    /** A catalogue entry as a row: the button is the dialog while it can still be shown, else the settings page. */
    fun item(e: PermEntry, s: PermStatus, highlight: Boolean = false): PermItem = PermItem(
        id = e.id, title = e.label, reason = e.why, icon = e.icon,
        kind = if (e.kind == PermKind.RUNTIME) PermItemKind.RUNTIME else PermItemKind.SPECIAL,
        state = s.state, detail = s.reason, action = action(e, s), highlight = highlight,
    )

    fun action(e: PermEntry, s: PermStatus): PermAction? = when {
        e.kind == PermKind.ADB_ONLY -> null
        e.kind != PermKind.RUNTIME && s.state == PermState.UNKNOWN -> PermAction.OPEN
        !s.missing -> null
        e.kind == PermKind.RUNTIME && s.state == PermState.DENIED && e.autoRequest -> PermAction.GRANT
        else -> PermAction.OPEN
    }

    /**
     * A fleet app's canonical name in words: "cloud-terminal-termux" -> "Cloud Terminal Termux". A helper lib's id drops its
     * "lib" segment: "cloud-lib-sample-engine" -> "Cloud Sample Engine". A label already in words is kept.
     */
    fun appName(raw: String): String {
        if (raw.contains(' ') || !raw.contains('-')) return raw
        val parts = raw.split('-').filter { it.isNotEmpty() }
        val words = if (parts.size > 2 && parts[0] == "cloud" && parts[1] == "lib") listOf(parts[0]) + parts.drop(2) else parts
        return words.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** A helper app's grant as a row: titled by the helper, its reason the work it does for this app. */
    fun helperItem(h: HelperNeed, e: PermEntry, s: PermStatus, highlight: Boolean = false, name: String = appName(h.label)): PermItem = PermItem(
        id = "${h.pkg}:${e.id}", title = "$name: ${e.label}", reason = h.why.ifBlank { e.why }, icon = e.icon.ifBlank { "apps" },
        kind = PermItemKind.HELPER, state = s.state, detail = s.reason,
        action = if (s.missing || s.state == PermState.UNKNOWN) PermAction.OPEN else null, highlight = highlight,
    )

    /** The order Grant all walks: one dialog for every runtime row, then each page, helpers last. */
    fun walk(items: List<PermItem>): List<PermItem> {
        // an own row the phone cannot read (an OEM guide) is opened from its row only, never again by every Grant all
        val todo = items.filter { it.action != null && it.kind != PermItemKind.INSTALL &&
            (it.missing || (it.kind == PermItemKind.HELPER && it.state == PermState.UNKNOWN)) }
        return todo.filter { it.action == PermAction.GRANT } + todo.filter { it.action == PermAction.OPEN && it.kind != PermItemKind.HELPER } +
            todo.filter { it.kind == PermItemKind.HELPER }
    }
}
